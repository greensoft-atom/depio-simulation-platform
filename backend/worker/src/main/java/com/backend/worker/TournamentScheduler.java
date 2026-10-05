package com.backend.worker;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.MatchMode;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.handoff.TournamentGrants;
import com.backend.handoff.Ulid;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.MatchResultRepository;
import com.backend.persistence.TournamentRepository;
import com.backend.persistence.TournamentRepository.Entry;
import com.backend.persistence.TournamentRepository.Match;
import com.backend.persistence.TournamentRepository.Rostered;
import com.backend.persistence.TournamentRepository.TeamEntry;
import com.backend.persistence.TournamentRepository.Tournament;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tournaments' clock (docs detailed-design/04-platform-services.md §6, D-40): every 5 s,
 * each tournament not yet over is seeded or cancelled at its deadline, started at its start, and
 * run a round at a time — its matches made, decided, and the round ended, or after the final the
 * prizes paid and the tournament finished.
 *
 * <h2>In every worker, with no lock (D-41)</h2>
 *
 * Every write is conditional on what the tick read — a tournament's state and version, a
 * match's state, a prize's ledger key — so a second worker, or a restart, changes nothing twice.
 * The one step with effects outside MySQL, making a match, writes every ticket first and then
 * claims it, and revokes the tickets if the claim was another worker's (T-37).
 */
final class TournamentScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TournamentScheduler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A ticket's holder: a player, their name, and their side, 0 in a duel (a team match's 1 or 2). */
    record Seat(long player, String name, int side) { }

    /** 30 s to come, 180 s to play, 60 to spare: after this with no result and nobody come, the higher seed. */
    static final long NO_RESULT_SECONDS = 270;
    /** Somebody came: the result is waited for this long, a result pipeline's tolerable outage (Q-45). */
    static final long LATE_RESULT_SECONDS = 1_800;

    private final TournamentRepository tournaments;
    private final EconomyRepository economy;
    private final ArenaDirectory arenas;
    private final TicketStore tickets;
    private final TournamentGrants grants;
    private final com.backend.handoff.MatchArrivals arrivals;
    private final LobbyPush push;
    private final com.backend.persistence.InboxRepository inbox;
    private final Clock clock;
    private final LongAdder failed = new LongAdder();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tournament-tick");
        t.setDaemon(true);
        return t;
    });

    TournamentScheduler(TournamentRepository tournaments, EconomyRepository economy, ArenaDirectory arenas,
                        TicketStore tickets, TournamentGrants grants, com.backend.handoff.MatchArrivals arrivals,
                        LobbyPush push, com.backend.persistence.InboxRepository inbox, Clock clock) {
        this.tournaments = tournaments;
        this.economy = economy;
        this.arenas = arenas;
        this.tickets = tickets;
        this.grants = grants;
        this.arrivals = arrivals;
        this.push = push;
        this.inbox = inbox;
        this.clock = clock;
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::tickNow, 5, 5, TimeUnit.SECONDS);
    }

    /** Ticks that failed, a tournament's step or the whole tick; each is tried again 5 s later. */
    long failedCount() {
        return failed.sum();
    }

    private void tickNow() {
        try {
            tick(clock.instant());
        } catch (Exception e) {
            // Caught, or the executor would never run it again.
            failed.increment();
            log.warn("tournament tick failed, trying again: {}", e.toString());
        }
    }

    void tick(Instant now) throws SQLException {
        for (Tournament t : tournaments.inStates(TournamentRepository.REGISTRATION, TournamentRepository.SEEDED,
                TournamentRepository.RUNNING)) {
            try {
                step(t, now);
            } catch (Exception e) {
                failed.increment();
                log.warn("tournament {}: its step failed, trying again: {}", t.id(), e.toString());
            }
        }
    }

    private void step(Tournament t, Instant now) throws Exception {
        switch (t.state()) {
            case TournamentRepository.REGISTRATION -> {
                if (!now.isBefore(t.registrationEnds())) {
                    close(t);
                }
            }
            case TournamentRepository.SEEDED -> {
                if (!now.isBefore(t.startsAt()) && tournaments.start(t.id(), t.version())) {
                    log.info("tournament {} started", t.id());
                }
            }
            default -> run(t, now);
        }
    }

    /** Whether its entries are teams, and its matches team matches (Q-19, D-44). */
    private static boolean ofTeams(Tournament t) {
        return t.mode() == MatchResultRepository.MODE_TEAMS;
    }

    /** The deadline: fewer than two entries, cancelled; otherwise seeded, from the entries it locks (T-36). */
    private void close(Tournament t) throws SQLException {
        switch (tournaments.close(t.id(), t.version())) {
            case SEEDED -> log.info("tournament {} seeded", t.id());
            case CANCELLED -> log.info("tournament {} cancelled: fewer than two entries", t.id());
            case STALE -> { }                                   // another worker's tick closed it first
        }
    }

    /** The current round: its matches made and decided, and once every one is done, the round over. */
    private void run(Tournament t, Instant now) throws Exception {
        int round = t.currentRound();
        boolean open = round == 1
                || !now.isBefore(t.roundEndedAt().plusSeconds(60L * t.roundMinutes()));
        Map<Long, Integer> seeds = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        if (ofTeams(t)) {
            for (TeamEntry e : tournaments.teamEntries(t.id())) {
                seeds.put(e.teamId(), e.seed());
            }
        } else {
            for (Entry e : tournaments.entries(t.id())) {
                seeds.put(e.playerId(), e.seed());
                names.put(e.playerId(), e.name());
            }
        }
        boolean done = true;
        for (Match m : tournaments.matches(t.id())) {
            if (m.round() != round || m.state() == TournamentRepository.DONE) {
                continue;
            }
            if (m.state() == TournamentRepository.READY) {
                done &= settle(t, m, seeds, now);
            } else {
                done = false;
                if (open) {
                    make(t.id(), m, seats(t, m, names), ofTeams(t) ? MatchMode.TEAMS : MatchMode.DUEL, now);
                }
            }
        }
        if (done) {
            over(t, now);
        }
    }

    /**
     * Whom a match's tickets are for: a duel's two players; a team match's rosters, those still in
     * their teams, side 1 for {@code player_a}'s team and side 2 for {@code player_b}'s (Q-19, D-44).
     */
    private List<Seat> seats(Tournament t, Match m, Map<Long, String> names) throws SQLException {
        if (!ofTeams(t)) {
            return List.of(new Seat(m.playerA(), names.get(m.playerA()), 0),
                    new Seat(m.playerB(), names.get(m.playerB()), 0));
        }
        List<Seat> seats = new java.util.ArrayList<>();
        for (Rostered r : tournaments.stillOnTeam(t.id(), m.playerA())) {
            seats.add(new Seat(r.playerId(), r.name(), 1));
        }
        for (Rostered r : tournaments.stillOnTeam(t.id(), m.playerB())) {
            seats.add(new Seat(r.playerId(), r.name(), 2));
        }
        return seats;
    }

    /**
     * Makes a pending match, as the matchmaker makes one: an arena with a free room, the room
     * promised (D-42), a ticket for every seat, then the match claimed, and a grant kept and a push
     * for each seat. The tickets come before the claim: a store failing part-way through them
     * left the match claimed with seats that never had one, decided by walkover (T-37). Keyed by
     * the player, the grants stay with the claim's winner: another worker may be making it too.
     *
     * @return whether this worker made it: false with no room anywhere, which the next tick tries
     *         again, or when another worker had claimed it
     */
    boolean make(long id, Match m, List<Seat> seats, MatchMode mode, Instant now) throws Exception {
        String matchUid = Ulid.generate();
        ArenaDirectory.Endpoint arena = arenas.reserveForMatch(matchUid);
        if (arena == null) {
            log.warn("tournament {} round {} slot {}: no arena has a room free, trying again", id, m.round(), m.slot());
            return false;
        }
        List<Ticket> issued = new java.util.ArrayList<>(seats.size());
        boolean claimed = false;
        try {
            for (Seat seat : seats) {
                // No bonus: tournament matches are on equal terms (Q-16).
                Ticket ticket = Ticket.forMatch(seat.player(), seat.name(), seat.side(), matchUid, mode.id);
                await(tickets.issue(ticket));
                issued.add(ticket);
            }
            claimed = tournaments.ready(id, m.round(), m.slot(), matchUid, now);
        } finally {
            if (!claimed) {
                // The room and the tickets given back: only the claim's winner leaves any. The next
                // tick tries again with new ones.
                arenas.release(arena.name(), matchUid);
                for (Ticket t : issued) {
                    try {
                        await(tickets.revoke(t.id()));
                    } catch (Exception e) {
                        log.debug("ticket {} not revoked, and lapses with its minute: {}", t.id(), e.toString());
                    }
                }
            }
        }
        if (!claimed) {
            return false;
        }
        for (int i = 0; i < seats.size(); i++) {
            long player = seats.get(i).player();
            ObjectNode grant = JSON.createObjectNode()
                    .put("tournamentId", id)
                    .put("round", m.round())
                    .put("arenaHost", arena.host())
                    .put("arenaPort", arena.port())
                    .put("ticketId", issued.get(i).id())
                    .put("tls", arena.tls())
                    .put("mode", mode.key);
            await(grants.put(id, player, grant.toString()));
            push.send(player, "evt.tournament.match", grant);
        }
        log.info("tournament {} round {} slot {}: {} v {}, match {} on {}", id, m.round(), m.slot(), m.playerA(),
                m.playerB(), matchUid, arena.name());
        return true;
    }

    /**
     * Decides a ready match from what MySQL recorded for it (D-40): its one winner advances, a
     * team match's by the side placed first (D-44); a draw, the higher seed; no result
     * {@value #NO_RESULT_SECONDS} s after the tickets, the higher seed.
     *
     * @return whether it is decided
     */
    private boolean settle(Tournament t, Match m, Map<Long, Integer> seeds, Instant now) throws Exception {
        long id = t.id();
        List<Long> first = ofTeams(t) ? teamsOf(m, tournaments.sidesPlacedFirst(m.matchUid()))
                : tournaments.resultOf(m.matchUid());
        // Where an elimination must send someone on, the higher seed, a round robin's is a draw (04 §6).
        boolean roundRobin = t.format() == TournamentRepository.ROUND_ROBIN;
        Long winner;
        if (first == null) {
            // No result is not a late one (D-33): a match somebody came to is waited for longer.
            if (now.isBefore(m.readyAt().plusSeconds(NO_RESULT_SECONDS))
                    || now.isBefore(m.readyAt().plusSeconds(LATE_RESULT_SECONDS)) && await(arrivals.arrived(m.matchUid()))) {
                return false;
            }
            winner = roundRobin ? null : higherSeed(m, seeds);
            log.info("tournament {} round {} slot {}: no result, {}", id, m.round(), m.slot(),
                    winner == null ? "a draw" : winner + " advances as the higher seed");
        } else if (tournaments.cutShort(m.matchUid())) {
            winner = roundRobin ? null : higherSeed(m, seeds);     // cut short is not a finish (D-34)
            log.info("tournament {} round {} slot {}: cut short, {}", id, m.round(), m.slot(),
                    winner == null ? "a draw" : winner + " advances as the higher seed");
        } else if (first.size() == 1) {
            winner = first.get(0);
        } else {
            winner = roundRobin ? null : higherSeed(m, seeds);
        }
        if (winner == null) {
            tournaments.draw(id, m.round(), m.slot());
        } else {
            tournaments.decide(id, m.round(), m.slot(), winner);
        }
        return true;
    }

    /** A team match's sides placed first, as the teams that played them; null for no result. */
    private static List<Long> teamsOf(Match m, List<Integer> sides) {
        if (sides == null) {
            return null;
        }
        List<Long> teams = new java.util.ArrayList<>();
        for (int side : sides) {
            teams.add(side == 1 ? m.playerA() : m.playerB());
        }
        return teams;
    }

    private static long higherSeed(Match m, Map<Long, Integer> seeds) {
        return seeds.get(m.playerA()) < seeds.get(m.playerB()) ? m.playerA() : m.playerB();
    }

    /** Every match of the round done: after the final, the prizes and the end; otherwise the next round. */
    private void over(Tournament t, Instant now) throws SQLException {
        List<Match> all = tournaments.matches(t.id());
        Match last = all.get(all.size() - 1);
        if (t.currentRound() < last.round()) {
            if (tournaments.endRound(t.id(), t.version(), now)) {
                log.info("tournament {}: round {} over", t.id(), t.currentRound());
            }
            return;
        }
        if (t.format() == TournamentRepository.ROUND_ROBIN) {
            // Places 1, 2 and 3 of the standings, the third once (04 §6).
            List<TournamentRepository.Standing> standings = tournaments.standings(t.id());
            long[] prizes = {t.prize1(), t.prize2(), t.prize3()};
            for (int place = 1; place <= Math.min(3, standings.size()); place++) {
                pay(t, place, standings.get(place - 1).entry(), prizes[place - 1], now);
            }
        } else {
            pay(t, 1, last.winner(), t.prize1(), now);
            pay(t, 2, loser(last), t.prize2(), now);
            for (Match m : all) {
                if (m.round() == last.round() - 1) {
                    pay(t, 3, loser(m), t.prize3(), now);
                }
            }
        }
        if (tournaments.finish(t.id(), t.version())) {
            log.info("tournament {} finished: {} won", t.id(), last.winner());
        }
    }

    /** The entry a done match put out: for a bye, the side nobody filled, null. */
    private static Long loser(Match m) {
        return m.winner().equals(m.playerA()) ? m.playerB() : m.playerA();
    }

    /** A place's gems, 1st, 2nd and 3rd (04 §8): first cuts, as every balance number. */
    static final long[] PLACE_GEMS = {30, 15, 5};
    /** Gems are paid from this many entries: two entrants agreeing to meet farm none (04 §8). */
    static final int GEMS_FROM_ENTRIES = 4;

    /**
     * Through the ledger, once whatever retries (06 §5); nothing for nobody, or for a prize of
     * nothing. A team's place pays each player on its roster (Q-19). Gems by the rule, from four
     * entries, whatever the coins (04 §8).
     */
    private void pay(Tournament t, int place, Long entry, long coins, Instant now) throws SQLException {
        long gems = entries(t) >= GEMS_FROM_ENTRIES ? PLACE_GEMS[place - 1] : 0;
        if (entry == null || (coins <= 0 && gems <= 0)) {
            return;
        }
        if (ofTeams(t)) {
            for (Rostered r : tournaments.rosterOf(t.id(), entry)) {
                payPlayer(t, place, r.playerId(), coins, gems, now);
            }
        } else {
            payPlayer(t, place, entry, coins, gems, now);
        }
    }

    /** How many entered: players, or teams. */
    private int entries(Tournament t) throws SQLException {
        return (ofTeams(t) ? tournaments.teamsBySeed(t.id()) : tournaments.bySeed(t.id())).size();
    }

    /**
     * The credits, then the inbox's item (Q-20), written whether the credits were this tick's or an
     * earlier one's: a tick that died between them is repaired by the next, and the item, one a
     * player and tournament, is not written twice.
     */
    private void payPlayer(Tournament t, int place, long player, long coins, long gems, Instant now)
            throws SQLException {
        String key = "tourney:" + t.id() + ":" + place + ":" + player;
        if (coins > 0) {
            economy.credit(player, coins, EconomyRepository.REASON_TOURNAMENT_PRIZE, String.valueOf(t.id()), key);
        }
        if (gems > 0) {
            economy.credit(player, gems, EconomyRepository.REASON_TOURNAMENT_PRIZE, String.valueOf(t.id()),
                    key + ":gems", EconomyRepository.CURRENCY_GEMS);
        }
        if (inbox.add(player, com.backend.persistence.InboxRepository.TOURNAMENT_PRIZE, t.id(), now)) {
            push.send(player, "evt.inbox", null);
        }
    }

    private static <T> T await(CompletableFuture<T> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
