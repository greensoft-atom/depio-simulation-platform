package com.backend.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.backend.common.Metrics;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.MatchMode;
import com.backend.handoff.StoreUnavailableException;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.handoff.TournamentGrants;
import com.backend.handoff.Ulid;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes matches out of the queues, once a second (docs detailed-design/04-platform-services.md
 * §4, "The matcher").
 *
 * <h2>One at a time, by lease</h2>
 *
 * Every {@code platform} runs one of these, and whichever holds {@code mm:leader} does the
 * work: taken with {@code SET NX PX}, kept by renewing it each round. When the holder dies the
 * lease lapses in five seconds and another takes over. A lease can lapse under a round that is
 * merely slow, and then two match at once for a moment, which is why every step is a watched
 * transaction on the players' own records (D-55): a player is never in two matches.
 *
 * <h2>What a round does, per queued mode</h2>
 *
 * Drops whoever is no longer queued, and every entry with a member who has no lobby connection:
 * the push is how a match is announced, and a player whose lobby has gone would hold the others
 * in a room for nothing. Fills two teams at a time from the rest, by rating, oldest first
 * ({@link #lineups}). For each match: takes its entries out of the queue and asks its players in
 * one step, if they are still as read, then tells them, {@code evt.match.ready} (D-27).
 *
 * <h2>And the matches asked about</h2>
 *
 * First, each round: a match every player has accepted is made. It picks an arena with a free
 * room, issues each player a ticket naming the match (D-20), then records the match for them to
 * fetch, if they all still accept, and pushes {@code evt.match.found}; with no room anywhere, the
 * entries go back, first in line. A match one player declined or withdrew from, or that ran out
 * of time, is off: each entry with a player who declined, withdrew or did not answer is out,
 * those who declined or did not answer locked out of the queue for a minute, and every other
 * entry goes back where it was. Both read the answers again as they write (T-33).
 */
public final class Matchmaker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Matchmaker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String LEADER_KEY = "mm:leader";
    static final long LEASE_MILLIS = 5_000;
    static final long ROUND_MILLIS = 1_000;

    /** The rating window: ±100 at first, 50 wider for every 10 s waited. */
    static final int WINDOW_START = 100;
    static final int WINDOW_STEP = 50;
    static final long WINDOW_STEP_MILLIS = 10_000;

    /** How long the players of a match found have to accept it (D-27). */
    static final long CONFIRM_MILLIS = 10_000;
    /** How long a player who declined, or did not answer, may not queue. */
    static final int LOCK_SECONDS = 60;

    /**
     * The most entries the oldest is matched among, besides itself: a few thousand splits at most,
     * and enough for a free-for-all of eleven more (a test holds every mode to it).
     */
    static final int CANDIDATES = 10;

    /** A match found: its sides of entries, two teams or more players, the oldest entry in the first. */
    record Lineup(List<List<MatchQueue.Waiting>> sides) {

        List<MatchQueue.Waiting> first() {
            return sides.get(0);
        }

        List<MatchQueue.Waiting> second() {
            return sides.get(1);
        }

        List<MatchQueue.Waiting> entries() {
            return sides.stream().flatMap(List::stream).toList();
        }
    }

    private final JRedisClient store;
    private final MatchQueue queue;
    private final ArenaDirectory arenas;
    private final TicketStore tickets;
    private final LobbyPush push;
    private final TournamentGrants grants;
    private final String instance;
    private final LongSupplier clock;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matchmaker");
        t.setDaemon(true);
        return t;
    });

    /** Matches made, by mode. */
    private final Metrics.LabeledCounter made = new Metrics.LabeledCounter();

    /** Seconds, the upper bounds of the wait histogram's buckets: a few seconds to ten minutes. */
    static final double[] WAIT_BUCKETS = {1, 2, 5, 10, 20, 30, 60, 120, 300, 600};
    /** How long each player waited, queued to their match found, by mode (04 §11). */
    private final Metrics.LabeledHistogram waits = new Metrics.LabeledHistogram(WAIT_BUCKETS);
    /** Players left waiting in each queue by this matcher's last round of it; cleared when another leads. */
    private final java.util.Map<String, Double> depth = new java.util.concurrent.ConcurrentHashMap<>();
    /** How each confirm step ended: made, declined, withdrawn, lapsed, no_room. */
    private final Metrics.LabeledCounter confirms = new Metrics.LabeledCounter();
    /** Tickets issued for made matches, by mode: against the arenas' joins, what never arrived. */
    private final Metrics.LabeledCounter ticketsIssued = new Metrics.LabeledCounter();

    /** Said once when players start waiting for a room, not every second they wait. Matcher thread only. */
    private boolean saidNoRoom;

    public Matchmaker(JRedisClient store, MatchQueue queue, ArenaDirectory arenas,
                      TicketStore tickets, LobbyPush push, TournamentGrants grants, String instance, LongSupplier clock) {
        this.store = store;
        this.grants = grants;
        this.queue = queue;
        this.arenas = arenas;
        this.tickets = tickets;
        this.push = push;
        this.instance = instance;
        this.clock = clock;
    }

    /** Rounds with a fixed delay between them, so a slow one is never followed by a pile-up. */
    public void start() {
        thread.scheduleWithFixedDelay(this::round, ROUND_MILLIS, ROUND_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void registerMetrics(Metrics m) {
        m.labeledCounter("backend_platform_matches_made_total", "Matches made by the matcher, by mode.",
                "mode", made);
        m.labeledHistogram("backend_platform_queue_wait_seconds",
                "How long each player waited, from queueing to a match found, by mode.", "mode", waits);
        m.labeledGauge("backend_platform_queue_players",
                "Players left waiting in each queue by this platform's last round; 0 where another leads.",
                "mode", this::waiting);
        m.labeledCounter("backend_platform_match_tickets_issued_total",
                "Tickets issued for made matches, by mode; with the arenas' joins, how many never arrived.",
                "mode", ticketsIssued);
        m.labeledCounter("backend_platform_confirms_total",
                "How each match found ended its confirm step: made, declined, withdrawn, lapsed, no_room.", "outcome", confirms);
    }

    /** One round. A failure is logged and the next round tries again: the queue is in the store. */
    void round() {
        try {
            if (!lead()) {
                depth.clear();                       // another's rounds are the ones to read
                return;
            }
            settle();
            for (MatchMode mode : MatchMode.values()) {
                if (mode.queued()) {
                    match(mode);
                }
            }
        } catch (RuntimeException e) {
            log.warn("matchmaking round failed: {}", e.toString());
        }
    }

    /** Takes the lease if nobody holds it, or renews it if this instance does. */
    boolean lead() {
        if (Boolean.TRUE.equals(await(store.set(LEADER_KEY, instance, SetArgs.nx().andPx(LEASE_MILLIS))))) {
            return true;
        }
        if (instance.equals(await(store.get(LEADER_KEY)))) {
            await(store.pexpire(LEADER_KEY, LEASE_MILLIS));
            return true;
        }
        return false;
    }

    /** @return the matches found for this mode this round, and asked about */
    int match(MatchMode mode) {
        List<Long> stale = new ArrayList<>();
        List<MatchQueue.Waiting> waiting = queue.waiting(mode, stale);
        queue.drop(mode, stale, false);

        List<List<CompletableFuture<Boolean>>> inLobby = new ArrayList<>(waiting.size());
        List<List<CompletableFuture<Boolean>>> called = new ArrayList<>(waiting.size());
        for (MatchQueue.Waiting w : waiting) {
            inLobby.add(w.members().stream().map(m -> push.connected(m.playerId())).toList());
            called.add(w.members().stream().map(m -> grants.called(m.playerId())).toList());
        }
        List<MatchQueue.Waiting> present = new ArrayList<>(waiting.size());
        List<Long> gone = new ArrayList<>();
        List<Long> toTell = new ArrayList<>();
        for (int i = 0; i < waiting.size(); i++) {
            List<MatchQueue.Member> members = waiting.get(i).members();
            List<Long> here = new ArrayList<>();
            boolean anyCalled = false;
            for (int m = 0; m < members.size(); m++) {
                if (await(inLobby.get(i).get(m))) {
                    here.add(members.get(m).playerId());
                }
                anyCalled |= await(called.get(i).get(m));     // to a tournament match: it comes first (Q-44)
            }
            if (here.size() == members.size() && !anyCalled) {
                present.add(waiting.get(i));
            } else {
                // A party goes whole: its members still here are told they are no longer queued.
                members.forEach(m -> gone.add(m.playerId()));
                toTell.addAll(here);
            }
        }
        if (!gone.isEmpty()) {
            log.info("{}: dropping {} queued without a lobby connection or called to a tournament, or with a"
                    + " member so", mode.key, gone.size());
            queue.drop(mode, gone, true);
            for (long p : toTell) {
                push.send(p, "evt.queue.update", queueUpdate(null));
            }
        }

        int count = 0;
        int left = present.stream().mapToInt(e -> e.members().size()).sum();
        for (Lineup found : lineups(present, mode.teamSize, mode.roster / mode.teamSize, clock.getAsLong())) {
            if (ask(mode, found)) {               // else changed since read: the next round reads it again
                count++;
                left -= found.entries().stream().mapToInt(e -> e.members().size()).sum();
            }
        }
        depth.put(mode.key, (double) left);
        return count;
    }

    /** Players left waiting in each queued mode by the last round, 0 for one not yet matched. */
    java.util.Map<String, Double> waiting() {
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        for (MatchMode mode : MatchMode.values()) {
            if (mode.queued()) {
                out.put(mode.key, depth.getOrDefault(mode.key, 0.0));
            }
        }
        return out;
    }

    Metrics.LabeledHistogram waits() {
        return waits;
    }

    Metrics.LabeledCounter confirms() {
        return confirms;
    }

    Metrics.LabeledCounter ticketsIssued() {
        return ticketsIssued;
    }

    /**
     * Asks every player of a match found whether they are coming: {@code evt.match.ready}.
     * @return false when its entries were not as read, and nobody was asked
     */
    private boolean ask(MatchMode mode, Lineup found) {
        String matchUid = Ulid.generate();
        long now = clock.getAsLong();
        if (!queue.ask(mode, matchUid, found.sides(), now + CONFIRM_MILLIS)) {
            return false;
        }
        for (MatchQueue.Waiting entry : found.entries()) {
            for (int i = 0; i < entry.members().size(); i++) {
                waits.observe(mode.key, Math.max(0, now - entry.since()) / 1_000.0);
            }
        }
        ObjectNode ready = JSON.createObjectNode().put("matchUid", matchUid).put("mode", mode.key)
                .put("seconds", CONFIRM_MILLIS / 1_000);
        for (MatchQueue.Waiting entry : found.entries()) {
            for (MatchQueue.Member m : entry.members()) {
                push.send(m.playerId(), "evt.match.ready", ready);
            }
        }
        return true;
    }

    /**
     * The matches waiting for answers: each made once all its players have accepted, and off once
     * one has declined or withdrawn, or its time is up. Only the matcher that ends one's wait acts
     * on it.
     *
     * @return the matches made
     */
    int settle() {
        int made = 0;
        long now = clock.getAsLong();
        for (MatchQueue.Pending p : queue.pending()) {
            boolean refused = !p.declined().isEmpty() || !p.withdrawn().isEmpty();
            if (!refused && p.accepted().containsAll(p.players())) {
                made += make(p) ? 1 : 0;
            } else if (refused || now >= p.deadline()) {
                callOff(p, now >= p.deadline());
            }
        }
        return made;
    }

    /**
     * Everyone accepted: an arena, the tickets, then the match recorded for each player, if they
     * all still accept, and the push; with no room, back to the queue. The tickets come first and
     * are told to nobody until the match is recorded, so a failure on the way leaves it waiting
     * for the next round (T-34).
     */
    boolean make(MatchQueue.Pending p) {
        MatchMode mode = p.mode();
        ArenaDirectory.Endpoint arena = arenas.reserveForMatch(p.matchUid());
        if (arena == null) {
            MatchQueue.Ended ended = queue.end(p, false, java.util.Set.of(), LOCK_SECONDS);
            if (ended != null) {
                confirms.increment("no_room");       // first in line next round
                tell(mode, ended);
            }
            if (!saidNoRoom) {
                log.warn("{}: no arena has a free room for an accepted match", mode.key);
                saidNoRoom = true;
            }
            return false;
        }
        saidNoRoom = false;
        // In a mode with teams, the first side is team 1 and the second team 2; without, every player is team 0.
        java.util.Map<Long, MatchQueue.Grant> grants = new java.util.LinkedHashMap<>();
        for (int side = 0; side < p.sides().size(); side++) {
            int team = mode.teams() ? side + 1 : 0;
            for (MatchQueue.Waiting entry : p.sides().get(side)) {
                for (MatchQueue.Member m : entry.members()) {
                    Ticket ticket = Ticket.forMatch(m.playerId(), m.name(), team, p.matchUid(), mode.id, m.bonus(), m.skin());
                    await(tickets.issue(ticket));
                    grants.put(m.playerId(), new MatchQueue.Grant(arena.host(), arena.port(), ticket.id(), arena.tls(),
                            p.matchUid()));
                }
            }
        }
        if (!queue.make(p, grants, TicketStore.TTL_SECONDS)) {
            arenas.release(arena.name(), p.matchUid());   // changed since read: the next round reads it again
            return false;
        }
        grants.forEach((player, grant) -> {
            ticketsIssued.increment(mode.key);
            push.send(player, "evt.match.found", foundMessage(mode, grant));
        });
        made.increment(mode.key);
        confirms.increment("made");
        log.info("{} {}: players {} → arena {}", mode.key, p.matchUid(), grants.keySet(), arena.name());
        return true;
    }

    /**
     * Someone declined or withdrew, or said nothing in time: their entries are out, and those who
     * declined or said nothing are locked out of the queue for a while, their party-mates not;
     * everyone else is back where they were. One yet to answer when another declined was not
     * silent: their time was not up.
     */
    private void callOff(MatchQueue.Pending p, boolean timeUp) {
        java.util.Set<Long> excused = new java.util.HashSet<>();
        for (long player : p.players()) {
            // Called to a tournament match meanwhile is not refusing: out at the next round, unlocked (Q-44).
            if (timeUp && !p.accepted().contains(player) && await(grants.called(player))) {
                excused.add(player);
            }
        }
        MatchQueue.Ended ended = queue.end(p, timeUp, excused, LOCK_SECONDS);
        if (ended == null) {
            return;                                  // another matcher's
        }
        confirms.increment(ended.declined() ? "declined" : ended.withdrawn() ? "withdrawn" : "lapsed");
        tell(p.mode(), ended);
        log.info("{} {}: off, {} out", p.mode().key, p.matchUid(), ended.out());
    }

    /** Those out told they are no longer queued, and those back that they are queued again. */
    private void tell(MatchMode mode, MatchQueue.Ended ended) {
        for (long player : ended.out()) {
            push.send(player, "evt.queue.update", queueUpdate(null));
        }
        for (MatchQueue.Waiting entry : ended.back()) {
            for (MatchQueue.Member m : entry.members()) {
                push.send(m.playerId(), "evt.queue.update", queueUpdate(mode));
            }
        }
    }

    /** What {@code evt.match.found} carries, and {@code GET /v1/queue} too: the grant, and the mode. */
    static ObjectNode foundMessage(MatchMode mode, MatchQueue.Grant grant) {
        return JSON.createObjectNode()
                .put("arenaHost", grant.arenaHost())
                .put("arenaPort", grant.arenaPort())
                .put("ticketId", grant.ticketId())
                .put("tls", grant.tls())
                .put("mode", mode.key);
    }

    /** What {@code evt.queue.update} carries: queued for {@code mode}, or, for null, no longer queued. */
    static ObjectNode queueUpdate(MatchMode mode) {
        return JSON.createObjectNode()
                .put("state", mode == null ? "none" : MatchQueue.QUEUED)
                .put("mode", mode == null ? null : mode.key);
    }

    /**
     * Fills two teams of {@code teamSize} at a time, oldest first (04 §4, "The matcher, with
     * teams"). The oldest entry not yet matched, and the {@value #CANDIDATES} younger ones whose
     * mean ratings are closest to its own inside its window, are split into two teams, the oldest
     * in the first, no party split, with the least difference between the teams' ratings. The
     * oldest decides the window, since it has waited longest. An entry that fills no match waits
     * for the next round, and the next oldest has its turn. In a duel a team is one player, and
     * this is the closest-rated other.
     */
    static List<Lineup> lineups(List<MatchQueue.Waiting> oldestFirst, int teamSize, long nowMillis) {
        return lineups(oldestFirst, teamSize, 2, nowMillis);
    }

    /**
     * As above for two sides. With more, each of one player (a free-for-all), the match is the
     * oldest and the closest younger ones inside its window, one a side; too few there, and it
     * waits. With one (co-op), the oldest and the next that fit, whole, fill its team: an unrated
     * mode's entries all rate 0, so they come in the order they waited.
     */
    static List<Lineup> lineups(List<MatchQueue.Waiting> oldestFirst, int teamSize, int sides, long nowMillis) {
        List<Lineup> lineups = new ArrayList<>();
        int n = oldestFirst.size();
        boolean[] used = new boolean[n];
        int[] mean = oldestFirst.stream().mapToInt(MatchQueue.Waiting::rating).toArray();
        int[] gap = new int[n];
        for (int a = 0; a < n; a++) {
            if (used[a]) {
                continue;
            }
            MatchQueue.Waiting oldest = oldestFirst.get(a);
            int window = window(nowMillis - oldest.since());
            // The closest younger ones inside the window, the older first between two as close:
            // one pass keeping the best so far in order, so a long queue costs no sort.
            List<Integer> picked = new ArrayList<>(CANDIDATES + 1);
            for (int b = a + 1; b < n; b++) {
                gap[b] = Math.abs(mean[b] - mean[a]);
                // Two entries of one team never meet (Q-18): in a team match each is a whole side.
                if (used[b] || gap[b] > window || (oldest.team() != 0 && oldestFirst.get(b).team() == oldest.team())
                        || (picked.size() == CANDIDATES && gap[b] >= gap[picked.get(CANDIDATES - 1)])) {
                    continue;
                }
                int at = picked.size();
                while (at > 0 && gap[b] < gap[picked.get(at - 1)]) {
                    at--;
                }
                picked.add(at, b);
                if (picked.size() > CANDIDATES) {
                    picked.remove(CANDIDATES);
                }
            }
            if (sides == 1) {
                // One team, filled from the oldest by the next that fit, whole: co-op's.
                List<MatchQueue.Waiting> team = new ArrayList<>(List.of(oldest));
                List<Integer> taken = new ArrayList<>();
                int size = oldest.members().size();
                for (int b : picked) {
                    int players = oldestFirst.get(b).members().size();
                    if (size + players <= teamSize) {
                        team.add(oldestFirst.get(b));
                        taken.add(b);
                        size += players;
                    }
                }
                if (size == teamSize) {
                    used[a] = true;
                    taken.forEach(b -> used[b] = true);
                    lineups.add(new Lineup(List.of(team)));
                }
                continue;
            }
            if (sides > 2) {
                if (picked.size() >= sides - 1) {
                    List<List<MatchQueue.Waiting>> each = new ArrayList<>(List.of(List.of(oldest)));
                    used[a] = true;
                    for (int b : picked.subList(0, sides - 1)) {
                        each.add(List.of(oldestFirst.get(b)));
                        used[b] = true;
                    }
                    lineups.add(new Lineup(each));
                }
                continue;
            }
            List<MatchQueue.Waiting> candidates = picked.stream().map(oldestFirst::get).toList();
            Split best = new Split();
            split(candidates, 0, teamSize, oldest.members().size(), oldest.ratings(), 0, 0, 0, 0, best);
            if (best.gap == Long.MAX_VALUE) {
                continue;
            }
            used[a] = true;
            List<MatchQueue.Waiting> first = new ArrayList<>(List.of(oldest));
            List<MatchQueue.Waiting> second = new ArrayList<>();
            for (int i = 0; i < picked.size(); i++) {
                if ((best.first & 1 << i) != 0) {
                    first.add(candidates.get(i));
                    used[picked.get(i)] = true;
                } else if ((best.second & 1 << i) != 0) {
                    second.add(candidates.get(i));
                    used[picked.get(i)] = true;
                }
            }
            lineups.add(new Lineup(List.of(first, second)));
        }
        return lineups;
    }

    /** The best split found so far: the candidates in each team, a bit each, and the teams' difference. */
    private static final class Split {
        int first;
        int second;
        long gap = Long.MAX_VALUE;
    }

    /**
     * Puts each candidate from {@code i} on in the first team, the second, or neither, as long as
     * each team has room, and keeps the split whose teams' ratings differ least: the first found
     * of those equal, which has the closer-rated. Both teams have {@code teamSize} players, so
     * their ratings added up differ as their means do, {@code teamSize} times over.
     */
    private static void split(List<MatchQueue.Waiting> candidates, int i, int teamSize,
                              int size1, long sum1, int bits1, int size2, long sum2, int bits2, Split best) {
        if (size1 == teamSize && size2 == teamSize) {
            if (Math.abs(sum1 - sum2) < best.gap) {
                best.gap = Math.abs(sum1 - sum2);
                best.first = bits1;
                best.second = bits2;
            }
            return;
        }
        if (i == candidates.size()) {
            return;
        }
        MatchQueue.Waiting c = candidates.get(i);
        int size = c.members().size();
        if (size1 + size <= teamSize) {
            split(candidates, i + 1, teamSize, size1 + size, sum1 + c.ratings(), bits1 | 1 << i, size2, sum2, bits2, best);
        }
        if (size2 + size <= teamSize) {
            split(candidates, i + 1, teamSize, size1, sum1, bits1, size2 + size, sum2 + c.ratings(), bits2 | 1 << i, best);
        }
        split(candidates, i + 1, teamSize, size1, sum1, bits1, size2, sum2, bits2, best);
    }

    static int window(long waitedMillis) {
        return WINDOW_START + WINDOW_STEP * (int) (Math.max(0, waitedMillis) / WINDOW_STEP_MILLIS);
    }

    @Override
    public void close() {
        thread.shutdownNow();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
