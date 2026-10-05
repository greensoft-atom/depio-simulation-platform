package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.MatchMode;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.handoff.TournamentGrants;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.TournamentRepository;
import com.backend.persistence.TournamentRepository.Match;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The tournament clock (04 §6, D-40, D-41): seeding or cancelling at the deadline, starting,
 * making each match once, deciding it from what MySQL recorded or from the time passing, ending
 * rounds, paying the prizes once and finishing. Real MySQL, a real store.
 */
class TournamentSchedulerTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Registration closes a minute after T, and the tournament starts a minute later. */
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static Database db;
    private static JRedisEmbedded server;
    private static JRedisClient store;
    private static TournamentRepository tournaments;
    private static EconomyRepository economy;
    private static AccountRepository accounts;

    private TournamentScheduler scheduler;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        server = JRedisEmbedded.start();
        store = server.newClient();
        tournaments = new TournamentRepository(db.dataSource());
        economy = new EconomyRepository(db.dataSource());
        accounts = new AccountRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        server.close();
        db.close();
    }

    @BeforeEach
    void fresh() throws Exception {
        db.resetForTests();
        store.sync().send("FLUSHALL");
        announce(0);
        scheduler = new TournamentScheduler(tournaments, economy, new ArenaDirectory(store), new TicketStore(store),
                new TournamentGrants(store), new com.backend.handoff.MatchArrivals(store), new LobbyPush(store),
                new com.backend.persistence.InboxRepository(db.dataSource()), Clock.systemUTC());
    }

    @Test
    @DisplayName("at the deadline, fewer than two entries are cancelled and more seeded; at the start, it runs")
    void seedsOrCancelsThenStarts() throws Exception {
        long a = player("aaa", 1_300);
        long b = player("bbb", 1_500);
        long c = player("ccc", 1_400);
        long cup = cup(500, 200, 100);
        enter(cup, a, b, c);
        long lonely = cup(500, 200, 100);
        enter(lonely, player("lone", 1_000));

        scheduler.tick(T.plusSeconds(59));
        assertThat(tournaments.get(cup).state()).as("before the deadline").isEqualTo(TournamentRepository.REGISTRATION);
        assertThat(tournaments.get(lonely).state()).isEqualTo(TournamentRepository.REGISTRATION);

        scheduler.tick(T.plusSeconds(60));
        assertThat(tournaments.get(lonely).state()).as("one entry").isEqualTo(TournamentRepository.CANCELLED);
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.SEEDED);
        assertThat(tournaments.entries(cup)).extracting(TournamentRepository.Entry::playerId)
                .as("by duel rating").containsExactly(b, c, a);

        scheduler.tick(T.plusSeconds(119));
        assertThat(tournaments.get(cup).state()).as("before the start").isEqualTo(TournamentRepository.SEEDED);
        scheduler.tick(T.plusSeconds(120));
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.RUNNING);
        assertThat(tournaments.get(cup).currentRound()).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    @DisplayName("tickets that could not all be written leave the match unclaimed and its room given back; the next tick makes it (T-37)")
    void theTicketsComeBeforeTheClaim() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long c = player("ccc", 1_300);
        long cup = running(a, b, c);                          // 2 plays 3 in slot 1
        int dead;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            dead = probe.getLocalPort();
        }
        try (JRedisClient down = JRedisClient.builder().address("127.0.0.1", dead).commandTimeoutMillis(300)
                .build().start()) {
            new TournamentScheduler(tournaments, economy, new ArenaDirectory(store), new TicketStore(down),
                    new TournamentGrants(store), new com.backend.handoff.MatchArrivals(store), new LobbyPush(store),
                    new com.backend.persistence.InboxRepository(db.dataSource()), Clock.systemUTC())
                    .tick(T.plusSeconds(125));
        }
        assertThat(tournaments.matches(cup).get(1).state()).as("not claimed: its seats have no tickets")
                .isEqualTo(TournamentRepository.PENDING);
        assertThat(store.sync().zcard("rooms:promised:arena-1")).as("and its room given back").isZero();

        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(1).state()).isEqualTo(TournamentRepository.READY);
    }

    @Test
    @Timeout(60)
    @DisplayName("a match with both players is made once: a ticket each naming it, the grant kept and pushed")
    void makesAMatchOnce() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long c = player("ccc", 1_300);
        long cup = running(a, b, c);                          // seed 1 has a bye; 2 plays 3 in slot 1
        LinkedBlockingQueue<LobbyPush.Delivery> heard = new LinkedBlockingQueue<>();
        JRedisClient gateway = server.newClient();
        gateway.pubSub().subscribe("push:gw-1", (ch, msg) -> heard.add(LobbyPush.Delivery.parse(msg)))
                .get(5, TimeUnit.SECONDS);
        store.sync().set("conn:" + b, "gw-1#k1");
        store.sync().set("conn:" + c, "gw-1#k2");

        scheduler.tick(T.plusSeconds(125));

        Match made = tournaments.matches(cup).get(1);
        assertThat(made.state()).isEqualTo(TournamentRepository.READY);
        assertThat(made.readyAt()).isEqualTo(T.plusSeconds(125));
        Map<Long, String> tickets = new HashMap<>();
        for (long p : new long[] {b, c}) {
            JsonNode grant = JSON.readTree(new TournamentGrants(store).get(cup, p).get(5, TimeUnit.SECONDS));
            assertThat(grant.get("tournamentId").asLong()).isEqualTo(cup);
            assertThat(grant.get("round").asInt()).isEqualTo(1);
            assertThat(grant.get("arenaHost").asText()).isEqualTo("10.0.0.5");
            assertThat(grant.get("arenaPort").asInt()).isEqualTo(9011);
            assertThat(grant.get("tls").asBoolean()).isTrue();
            assertThat(grant.get("mode").asText()).isEqualTo("duel");
            tickets.put(p, grant.get("ticketId").asText());

            LobbyPush.Delivery pushed = heard.poll(5, TimeUnit.SECONDS);
            assertThat(pushed).as("pushed to %s", p).isNotNull();
            JsonNode message = JSON.readTree(pushed.message());
            assertThat(message.get("t").asText()).isEqualTo("evt.tournament.match");
            assertThat(message.get("d")).as("the grant itself").isEqualTo(grant);
            assertThat(new TournamentGrants(store).called(p).get(5, TimeUnit.SECONDS))
                    .as("called, so that no queue or sandbox is opened meanwhile (Q-44)").isTrue();
            assertThat(store.sync().send("TTL", "tcall:" + p).asLong()).as("for the ticket's life").isBetween(1L, 60L);
        }
        assertThat(new TournamentGrants(store).get(cup, a).get(5, TimeUnit.SECONDS)).as("a bye has none").isNull();
        assertThat(new TournamentGrants(store).called(a).get(5, TimeUnit.SECONDS)).as("nor is it called").isFalse();

        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(1).matchUid()).as("made once").isEqualTo(made.matchUid());
        assertThat(tournaments.get(cup).currentRound()).as("a match still to play").isEqualTo(1);
        for (long p : new long[] {b, c}) {
            String again = JSON.readTree(new TournamentGrants(store).get(cup, p).get(5, TimeUnit.SECONDS))
                    .get("ticketId").asText();
            assertThat(again).as("no second ticket").isEqualTo(tickets.get(p));
            Ticket ticket = new TicketStore(store).claim(tickets.get(p)).get(5, TimeUnit.SECONDS);
            assertThat(ticket.playerId()).isEqualTo(p);
            assertThat(ticket.matchUid()).isEqualTo(made.matchUid());
            assertThat(ticket.mode()).isEqualTo(MatchMode.DUEL.id);
            assertThat(ticket.team()).isZero();
            assertThat(ticket.bonus()).as("on equal terms (Q-16)").isEmpty();
            assertThat(ticket.displayName()).isEqualTo(p == b ? "bbb" : "ccc");
        }
        gateway.close();
    }

    @Test
    @DisplayName("with no arena room free, the match waits, and is made on a tick after one frees")
    void waitsForARoom() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long cup = running(a, b);
        announce(50);                                         // every room taken

        scheduler.tick(T.plusSeconds(125));
        assertThat(tournaments.matches(cup).get(0).state()).isEqualTo(TournamentRepository.PENDING);
        assertThat(new TournamentGrants(store).get(cup, a).get(5, TimeUnit.SECONDS)).isNull();

        announce(49);
        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(0).state()).isEqualTo(TournamentRepository.READY);
        assertThat(tournaments.matches(cup).get(0).readyAt()).isEqualTo(T.plusSeconds(130));
    }

    @Test
    @DisplayName("a round's matches past the rooms free wait for a later tick, not sent to a room promised (T-21)")
    void roomsPromised() throws Exception {
        long a = player("aaa", 1_600);
        long b = player("bbb", 1_500);
        long c = player("ccc", 1_400);
        long d = player("ddd", 1_300);
        long cup = running(a, b, c, d);
        announce(49);                                         // one room free, two matches

        scheduler.tick(T.plusSeconds(125));
        List<Match> round1 = tournaments.matches(cup);
        assertThat(round1.subList(0, 2)).extracting(Match::state)
                .containsExactly(TournamentRepository.READY, TournamentRepository.PENDING);
        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(1).state()).as("the first's room not opened yet: still promised")
                .isEqualTo(TournamentRepository.PENDING);

        new ArenaDirectory(store).announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.5", 9011, 0, 1_000, true, 49,
                50), "[]", List.of(round1.get(0).matchUid())).get(5, TimeUnit.SECONDS);   // the first's room open, another closed
        scheduler.tick(T.plusSeconds(135));
        assertThat(tournaments.matches(cup).get(1).state()).isEqualTo(TournamentRepository.READY);
    }

    @Test
    @DisplayName("a match another worker claimed first is left no tickets, grant or promise by this one")
    void onlyTheClaimerWritesTickets() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long cup = running(a, b);
        Match seen = tournaments.matches(cup).get(0);
        assertThat(seen.state()).isEqualTo(TournamentRepository.PENDING);
        tournaments.ready(cup, 1, 0, "01JCOTHERWORKER00000000001", T.plusSeconds(124));

        assertThat(scheduler.make(cup, seen, List.of(new TournamentScheduler.Seat(a, "aaa", 0),
                new TournamentScheduler.Seat(b, "bbb", 0)), MatchMode.DUEL, T.plusSeconds(125))).isFalse();
        assertThat(new TournamentGrants(store).get(cup, a).get(5, TimeUnit.SECONDS)).isNull();
        assertThat(new TournamentGrants(store).get(cup, b).get(5, TimeUnit.SECONDS)).isNull();
        assertThat(store.sync().dbsize()).as("the arena's announcement alone: the promise given back").isEqualTo(2L);
    }

    @Test
    @DisplayName("a win advances the winner, a draw the higher seed, and so does no result by 270 s; then prizes")
    void aWholeBracket() throws Exception {
        long a = player("aaa", 1_600);
        long c = player("ccc", 1_400);                        // before b: the draw's first row is c's
        long b = player("bbb", 1_500);
        long d = player("ddd", 1_300);
        long cup = running(a, b, c, d);                       // slot 0: 1 v 4; slot 1: 2 v 3
        scheduler.tick(T.plusSeconds(125));
        List<Match> round1 = tournaments.matches(cup);
        record(round1.get(0).matchUid(), Map.of(a, 2, d, 1));  // an upset
        record(round1.get(1).matchUid(), Map.of(b, 1, c, 1));  // a draw

        scheduler.tick(T.plusSeconds(130));
        List<Match> after = tournaments.matches(cup);
        assertThat(after.get(0).winner()).isEqualTo(d);
        assertThat(after.get(1).winner()).as("the higher seed").isEqualTo(b);
        assertThat(tournaments.get(cup).currentRound()).as("the round over").isEqualTo(2);
        assertThat(tournaments.get(cup).roundEndedAt()).isEqualTo(T.plusSeconds(130));
        assertThat(after.get(2)).extracting(Match::playerA, Match::playerB).containsExactly(d, b);

        scheduler.tick(T.plusSeconds(130 + 119));
        assertThat(tournaments.matches(cup).get(2).state()).as("two minutes between rounds")
                .isEqualTo(TournamentRepository.PENDING);
        scheduler.tick(T.plusSeconds(130 + 120));
        assertThat(tournaments.matches(cup).get(2).state()).isEqualTo(TournamentRepository.READY);

        scheduler.tick(T.plusSeconds(250 + 269));
        assertThat(tournaments.matches(cup).get(2).state()).as("still waiting for a result")
                .isEqualTo(TournamentRepository.READY);
        scheduler.tick(T.plusSeconds(250 + 270));
        assertThat(tournaments.matches(cup).get(2).winner()).as("nobody came: the higher seed").isEqualTo(b);
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(coins(a, b, c, d)).as("1st, 2nd, and each semi-final's loser")
                .containsExactly(100L, 500L, 100L, 200L);
        assertThat(gems(a, b, c, d)).as("and gems, from four entries (04 §8)").containsExactly(5L, 30L, 5L, 15L);
        assertThat(ledger(b)).as("a prize in coins and one in gems, of this tournament")
                .containsExactly(EconomyRepository.REASON_TOURNAMENT_PRIZE + " " + cup,
                        EconomyRepository.REASON_TOURNAMENT_PRIZE + " " + cup);

        record(tournaments.matches(cup).get(2).matchUid(), Map.of(d, 1));   // a result too late
        scheduler.tick(T.plusSeconds(600));
        assertThat(tournaments.matches(cup).get(2).winner()).isEqualTo(b);
        assertThat(coins(a, b, c, d)).as("paid once").containsExactly(100L, 500L, 100L, 200L);
        assertThat(gems(a, b, c, d)).as("gems once").containsExactly(5L, 30L, 5L, 15L);
        assertThat(new com.backend.persistence.InboxRepository(db.dataSource()).itemsOf(b)).as("told once").hasSize(1);
    }

    @Test
    @DisplayName("somebody came: no result by 270 s is waited for, and a late one decides; none in 30 minutes, the higher seed (D-33, Q-45)")
    void aMatchSomebodyCameToWaitsForItsResult() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long c = player("ccc", 1_300);
        long d = player("ddd", 1_200);
        long cup = running(a, b, c, d);                       // slot 0: 1 v 4; slot 1: 2 v 3
        scheduler.tick(T.plusSeconds(125));
        List<Match> round1 = tournaments.matches(cup);
        com.backend.handoff.MatchArrivals arrivals = new com.backend.handoff.MatchArrivals(store);
        arrivals.mark(round1.get(0).matchUid()).get(5, TimeUnit.SECONDS);
        arrivals.mark(round1.get(1).matchUid()).get(5, TimeUnit.SECONDS);

        scheduler.tick(T.plusSeconds(125 + 270));
        assertThat(tournaments.matches(cup)).extracting(Match::state).as("somebody came: waited for")
                .startsWith(TournamentRepository.READY, TournamentRepository.READY);
        record(round1.get(0).matchUid(), Map.of(a, 2, d, 1));  // late, and an upset
        scheduler.tick(T.plusSeconds(125 + 1_000));
        assertThat(tournaments.matches(cup).get(0).winner()).as("the result decides, however late").isEqualTo(d);
        assertThat(tournaments.matches(cup).get(1).state()).isEqualTo(TournamentRepository.READY);
        scheduler.tick(T.plusSeconds(125 + 1_799));
        assertThat(tournaments.matches(cup).get(1).state()).isEqualTo(TournamentRepository.READY);
        scheduler.tick(T.plusSeconds(125 + 1_800));
        assertThat(tournaments.matches(cup).get(1).winner()).as("none in 30 minutes: the higher seed").isEqualTo(b);
    }

    @Test
    @DisplayName("a match cut short is not a finish: the higher seed, though the other was ahead (D-34, Q-45)")
    void aMatchCutShortGoesToTheHigherSeed() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long cup = running(a, b);
        scheduler.tick(T.plusSeconds(125));
        record(tournaments.matches(cup).get(0).matchUid(), Map.of(a, 2, b, 1), true);
        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(0).winner()).isEqualTo(a);
    }

    @Test
    @DisplayName("a round robin: a win 3; both first, cut short or no result a draw, 1 each; round after round to the last; the standings, ties by seed, pay 1st, 2nd and 3rd (04 §6, plan item 66)")
    void aRoundRobin() throws Exception {
        long a = player("raa", 1_600);
        long b = player("rbb", 1_500);
        long c = player("rcc", 1_400);
        long d = player("rdd", 1_300);
        long league = tournaments.create("League", 8, T.plusSeconds(60), T.plusSeconds(120), 2, 500, 200, 100,
                com.backend.persistence.MatchResultRepository.MODE_DUEL, TournamentRepository.ROUND_ROBIN);
        enter(league, a, b, c, d);
        scheduler.tick(T.plusSeconds(60));
        scheduler.tick(T.plusSeconds(120));
        Instant now = T.plusSeconds(125);
        for (int round = 1; round <= 3; round++) {
            scheduler.tick(now);                               // the round's two matches made
            int current = tournaments.get(league).currentRound();
            assertThat(current).isEqualTo(round);
            boolean silent = false;
            for (Match m : tournaments.matches(league)) {
                if (m.round() != current) {
                    continue;
                }
                assertThat(m.state()).isEqualTo(TournamentRepository.READY);
                java.util.Set<Long> pair = java.util.Set.of(m.playerA(), m.playerB());
                if (pair.equals(java.util.Set.of(a, b))) {
                    record(m.matchUid(), Map.of(a, 1, b, 2));               // a wins
                } else if (pair.equals(java.util.Set.of(a, c))) {
                    record(m.matchUid(), Map.of(a, 1, c, 1));               // both first: a draw
                } else if (pair.equals(java.util.Set.of(b, c))) {
                    record(m.matchUid(), Map.of(b, 1, c, 2), true);         // cut short, b ahead: a draw
                } else if (pair.equals(java.util.Set.of(b, d))) {
                    record(m.matchUid(), Map.of(d, 1, b, 2));               // d wins
                } else if (pair.equals(java.util.Set.of(c, d))) {
                    record(m.matchUid(), Map.of(c, 1, d, 2));               // c wins
                } else {
                    silent = true;                                          // a and d: no result at all
                }
            }
            now = now.plusSeconds(silent ? 270 : 5);
            scheduler.tick(now);
            int settled = current;
            assertThat(tournaments.matches(league)).filteredOn(x -> x.round() == settled)
                    .allSatisfy(x -> assertThat(x.state()).isEqualTo(TournamentRepository.DONE));
            now = now.plusSeconds(120);                        // the minutes between rounds
        }
        assertThat(tournaments.get(league).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(tournaments.standings(league)).extracting(TournamentRepository.Standing::entry, TournamentRepository.Standing::points)
                .as("a and c level, a the higher seed").containsExactly(org.assertj.core.groups.Tuple.tuple(a, 5),
                        org.assertj.core.groups.Tuple.tuple(c, 5), org.assertj.core.groups.Tuple.tuple(d, 4),
                        org.assertj.core.groups.Tuple.tuple(b, 1));
        assertThat(coins(a, b, c, d)).as("by the standings").containsExactly(500L, 0L, 200L, 100L);
        assertThat(gems(a, b, c, d)).as("gems by the standings too").containsExactly(30L, 0L, 15L, 5L);
    }

    @Test
    @DisplayName("three entries are paid their coins and no gems: gems are from four entries (04 §8, plan item 68)")
    void threeEntriesPayNoGems() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long c = player("ccc", 1_300);
        long cup = running(a, b, c);                          // seed 1 has a bye; 2 plays 3
        playedByNobody(cup);
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(coins(a, b, c)).containsExactly(500L, 200L, 100L);
        assertThat(gems(a, b, c)).containsExactly(0L, 0L, 0L);
    }

    @Test
    @DisplayName("four entries and no coin prizes: the places are paid gems all the same, and told (04 §8)")
    void gemsWithoutCoinPrizes() throws Exception {
        long a = player("aaa", 1_600);
        long b = player("bbb", 1_500);
        long c = player("ccc", 1_400);
        long d = player("ddd", 1_300);
        long cup = runningWith(0, 0, 0, a, b, c, d);
        playedByNobody(cup);                                  // 1 and 2 through; 1 wins the final
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(coins(a, b, c, d)).containsExactly(0L, 0L, 0L, 0L);
        assertThat(gems(a, b, c, d)).containsExactly(30L, 15L, 5L, 5L);
        com.backend.persistence.InboxRepository inbox = new com.backend.persistence.InboxRepository(db.dataSource());
        for (long p : new long[] {a, b, c, d}) {
            assertThat(inbox.itemsOf(p)).extracting(com.backend.persistence.InboxRepository.Item::kind)
                    .as("told, %d", p).containsExactly(com.backend.persistence.InboxRepository.TOURNAMENT_PRIZE);
        }
    }

    @Test
    @DisplayName("a prize paid before a tick died is not paid again, and a prize of nothing is not paid at all")
    void prizesOnce() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long cup = runningWith(500, 0, 0, a, b);
        LinkedBlockingQueue<LobbyPush.Delivery> heard = new LinkedBlockingQueue<>();
        JRedisClient gateway = server.newClient();
        gateway.pubSub().subscribe("push:gw-1", (ch, msg) -> heard.add(LobbyPush.Delivery.parse(msg)))
                .get(5, TimeUnit.SECONDS);
        store.sync().set("conn:" + a, "gw-1#k1");
        store.sync().set("conn:" + b, "gw-1#k2");
        scheduler.tick(T.plusSeconds(125));
        for (int i = 0; i < 2; i++) {                         // the match's own pushes, one each
            assertThat(JSON.readTree(heard.poll(5, TimeUnit.SECONDS).message()).get("t").asText())
                    .isEqualTo("evt.tournament.match");
        }
        record(tournaments.matches(cup).get(0).matchUid(), Map.of(a, 1, b, 2));
        economy.credit(a, 500, EconomyRepository.REASON_TOURNAMENT_PRIZE, String.valueOf(cup),
                "tourney:" + cup + ":1:" + a);                 // what the tick that died had paid

        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(coins(a, b)).containsExactly(500L, 0L);
        assertThat(ledger(a)).as("one row").hasSize(1);
        com.backend.persistence.InboxRepository inbox = new com.backend.persistence.InboxRepository(db.dataSource());
        assertThat(inbox.itemsOf(a)).extracting(com.backend.persistence.InboxRepository.Item::kind,
                com.backend.persistence.InboxRepository.Item::ref).as("told, though the prize was paid before")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(com.backend.persistence.InboxRepository.TOURNAMENT_PRIZE, cup));
        assertThat(inbox.itemsOf(b)).as("nothing for a prize of nothing").isEmpty();
        LobbyPush.Delivery look = heard.poll(5, TimeUnit.SECONDS);
        assertThat(look).isNotNull();
        assertThat(look.to()).isEqualTo(a);
        assertThat(JSON.readTree(look.message()).get("t").asText()).isEqualTo("evt.inbox");
        assertThat(heard.poll(300, TimeUnit.MILLISECONDS)).as("b paid nothing, told nothing").isNull();
        gateway.close();
    }

    @Test
    @DisplayName("a tournament whose step fails is counted and tried again, and holds up no other")
    void aFailureHoldsUpNoOther() throws Exception {
        long a = player("aaa", 1_500);
        long b = player("bbb", 1_400);
        long first = running(a, b);
        long second = cup(500, 200, 100);
        enter(second, a, b);
        JRedisClient gone = server.newClient();
        gone.close();
        TournamentScheduler storeless = new TournamentScheduler(tournaments, economy, new ArenaDirectory(store),
                new TicketStore(gone), new TournamentGrants(store), new com.backend.handoff.MatchArrivals(store), new LobbyPush(store),
                new com.backend.persistence.InboxRepository(db.dataSource()), Clock.systemUTC());

        storeless.tick(T.plusSeconds(125));                   // the first's tickets cannot be written
        assertThat(storeless.failedCount()).isEqualTo(1);
        assertThat(tournaments.get(second).state()).as("the next one seeded all the same")
                .isEqualTo(TournamentRepository.SEEDED);
        assertThat(tournaments.matches(first).get(0).state()).as("not claimed without its tickets (T-37)")
                .isEqualTo(TournamentRepository.PENDING);
    }

    @Test
    @Timeout(60)
    @DisplayName("a teams' tournament: its rosters still in their teams sent tickets by side, the side placed first winning, each roster member paid (Q-19, D-44)")
    void aTeamsTournament() throws Exception {
        long[] a = {player("aa1", 1_200), player("aa2", 1_200), player("aa3", 1_200)};
        long[] b = {player("bb1", 1_200), player("bb2", 1_200), player("bb3", 1_200)};
        long ta = team("TeamA", 1_200, a);
        long tb = team("TeamB", 1_300, b);
        long cup = teamsCup(ta, a, tb, b);
        com.backend.persistence.TeamRepository teamRows = new com.backend.persistence.TeamRepository(db.dataSource(), 30);
        assertThat(teamRows.leave(a[2], T)).isEqualTo(com.backend.persistence.TeamRepository.Outcome.OK);
        long elsewhere = player("cc1", 1_200);
        long tc = teamRows.create(elsewhere, "TeamC", T).teamId();
        java.time.Instant dayOn = T.plus(java.time.Duration.ofHours(25));        // past the cooldown
        teamRows.invite(elsewhere, a[2], dayOn);
        assertThat(teamRows.answer(a[2], tc, true, dayOn)).as("in another team now")
                .isEqualTo(com.backend.persistence.TeamRepository.Outcome.OK);
        assertThat(teamRows.leave(b[2], T)).as("one of each side gone").isEqualTo(com.backend.persistence.TeamRepository.Outcome.OK);
        scheduler.tick(T.plusSeconds(60));
        scheduler.tick(T.plusSeconds(120));
        assertThat(tournaments.teamEntries(cup)).extracting(com.backend.persistence.TournamentRepository.TeamEntry::teamId)
                .as("by team rating").containsExactly(tb, ta);

        scheduler.tick(T.plusSeconds(125));
        Match fin = tournaments.matches(cup).get(0);
        assertThat(List.of(fin.playerA(), fin.playerB())).containsExactly(tb, ta);
        Map<Long, Integer> sides = new HashMap<>();
        for (long p : new long[] {b[0], b[1], a[0], a[1]}) {
            JsonNode grant = JSON.readTree(new TournamentGrants(store).get(cup, p).get(5, TimeUnit.SECONDS));
            assertThat(grant.get("mode").asText()).isEqualTo("teams");
            Ticket ticket = new TicketStore(store).claim(grant.get("ticketId").asText()).get(5, TimeUnit.SECONDS);
            assertThat(ticket.mode()).isEqualTo(MatchMode.TEAMS.id);
            assertThat(ticket.matchUid()).isEqualTo(fin.matchUid());
            sides.put(p, ticket.team());
        }
        assertThat(sides).as("player_a's team side 1, player_b's side 2").containsEntry(b[0], 1).containsEntry(b[1], 1)
                .containsEntry(a[0], 2).containsEntry(a[1], 2);
        assertThat(new TournamentGrants(store).get(cup, a[2]).get(5, TimeUnit.SECONDS)).as("left the team, in another: no ticket")
                .isNull();
        assertThat(new TournamentGrants(store).get(cup, b[2]).get(5, TimeUnit.SECONDS)).as("left the team: no ticket").isNull();

        recordSides(fin.matchUid(), new long[] {b[0], b[1]}, 2, new long[] {a[0], a[1]}, 1);
        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(0).winner()).as("side 2 placed first: player_b's team").isEqualTo(ta);
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(coins(a[0], a[1], a[2], b[0], b[1], b[2])).as("each on the roster, the one who left too")
                .containsExactly(500L, 500L, 500L, 200L, 200L, 200L);
    }

    @Test
    @Timeout(60)
    @DisplayName("a teams' tournament's draw sends the higher-seeded team on")
    void aTeamsDraw() throws Exception {
        long[] a = {player("aa1", 1_200), player("aa2", 1_200), player("aa3", 1_200)};
        long[] b = {player("bb1", 1_200), player("bb2", 1_200), player("bb3", 1_200)};
        long ta = team("TeamA", 1_350, a);
        long tb = team("TeamB", 1_300, b);
        long cup = teamsCup(ta, a, tb, b);
        scheduler.tick(T.plusSeconds(60));
        scheduler.tick(T.plusSeconds(120));
        scheduler.tick(T.plusSeconds(125));
        Match fin = tournaments.matches(cup).get(0);
        recordSides(fin.matchUid(), a, 1, b, 1);
        scheduler.tick(T.plusSeconds(130));
        assertThat(tournaments.matches(cup).get(0).winner()).isEqualTo(ta);
    }

    @Test
    @Timeout(60)
    @DisplayName("a teams' tournament of four pays each roster member its place's gems (04 §8, Q-19)")
    void aTeamsTournamentPaysEachRosterGems() throws Exception {
        long[][] rosters = new long[4][];
        long cup = tournaments.create("Team cup", 8, T.plusSeconds(60), T.plusSeconds(120), 2, 0, 0, 0,
                com.backend.persistence.MatchResultRepository.MODE_TEAMS);
        for (int i = 0; i < 4; i++) {
            rosters[i] = new long[] {player("t" + i + "a", 1_200), player("t" + i + "b", 1_200)};
            long team = team("Team" + i, 1_400 - 10 * i, rosters[i]);
            assertThat(tournaments.registerTeam(cup, team, java.util.Arrays.stream(rosters[i]).boxed().toList(), T))
                    .isEqualTo(TournamentRepository.Registration.OK);
        }
        scheduler.tick(T.plusSeconds(60));
        scheduler.tick(T.plusSeconds(120));
        playedByNobody(cup);
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(gems(rosters[0][0], rosters[0][1], rosters[1][0], rosters[1][1], rosters[2][0], rosters[2][1],
                rosters[3][0], rosters[3][1])).containsExactly(30L, 30L, 15L, 15L, 5L, 5L, 5L, 5L);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Every match left to nobody, from the first round's: each the higher seed's 270 s after it is made (D-33). */
    private void playedByNobody(long cup) throws Exception {
        Instant now = T.plusSeconds(125);
        while (tournaments.get(cup).state() == TournamentRepository.RUNNING && now.isBefore(T.plusSeconds(3_600))) {
            scheduler.tick(now);                              // the round's matches made
            now = now.plusSeconds(270);
            scheduler.tick(now);                              // decided, and the round over
            now = now.plusSeconds(120);                       // the minutes between rounds
        }
    }

    private static List<Long> gems(long... players) throws SQLException {
        List<Long> out = new java.util.ArrayList<>();
        for (long p : players) {
            out.add(economy.wallet(p).gems());
        }
        return out;
    }

    /** arena-1, 50 rooms, this many taken. */
    private static void announce(int roomsTaken) throws Exception {
        new ArenaDirectory(store).announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.5", 9011, 0, 1_000,
                true, roomsTaken, 50)).get(5, TimeUnit.SECONDS);
    }

    private static long player(String name, int rating) throws SQLException {
        long id = accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET rating_duel = ?, rated_duels = ? WHERE id = ?")) {
            ps.setInt(1, rating);
            ps.setInt(2, com.backend.persistence.RatingBoards.MIN_RATED);   // played enough to enter (Q-43)
            ps.setLong(3, id);
            ps.executeUpdate();
        }
        return id;
    }

    /** Registration until T + 60 s, the start at T + 120 s, two minutes between rounds. */
    private static long cup(long prize1, long prize2, long prize3) throws SQLException {
        return tournaments.create("Cup", 8, T.plusSeconds(60), T.plusSeconds(120), 2, prize1, prize2, prize3);
    }

    private static void enter(long cup, long... players) throws SQLException {
        for (long p : players) {
            assertThat(tournaments.register(cup, p, T)).isEqualTo(TournamentRepository.Registration.OK);
        }
    }

    private long running(long... players) throws Exception {
        return runningWith(500, 200, 100, players);
    }

    /** Entered, seeded and started by the scheduler: round 1 is to be made. */
    private long runningWith(long prize1, long prize2, long prize3, long... players) throws Exception {
        long cup = cup(prize1, prize2, prize3);
        enter(cup, players);
        scheduler.tick(T.plusSeconds(60));
        scheduler.tick(T.plusSeconds(120));
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.RUNNING);
        return cup;
    }

    /** What the worker's application of a result would have written: the match and its placings. */
    private static void record(String uid, Map<Long, Integer> placements) throws SQLException {
        record(uid, placements, false);
    }

    private static void record(String uid, Map<Long, Integer> placements, boolean cut) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long match;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO matches (match_uid, mode, kind, arena,"
                    + " started_at, ended_at, cut_short) VALUES (?, 1, 1, 'arena-1', NOW(3), NOW(3), ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uid);
                ps.setBoolean(2, cut);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    match = keys.getLong(1);
                }
            }
            for (Map.Entry<Long, Integer> e : placements.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO match_player (match_id, player_id, team,"
                        + " placement, kills, deaths, score, xp_gained, rating_delta) VALUES (?, ?, 0, ?, 0, 0, 0, 0, 0)")) {
                    ps.setLong(1, match);
                    ps.setLong(2, e.getKey());
                    ps.setInt(3, e.getValue());
                    ps.executeUpdate();
                }
            }
        }
    }

    /** The player's ledger rows, each as "reason ref". */
    private static List<String> ledger(long player) throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT reason, ref FROM ledger WHERE player_id = ? ORDER BY id")) {
            ps.setLong(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1) + " " + rs.getString(2));
                }
            }
        }
        return out;
    }

    private static long team(String name, int rating, long... players) throws SQLException {
        com.backend.persistence.TeamRepository teams = new com.backend.persistence.TeamRepository(db.dataSource(), 30);
        long id = teams.create(players[0], name, T).teamId();
        for (int i = 1; i < players.length; i++) {
            teams.invite(players[0], players[i], T);
            teams.answer(players[i], id, true, T);
        }
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE team SET rating = ?, rated_matches = ? WHERE id = ?")) {
            ps.setInt(1, rating);
            ps.setInt(2, com.backend.persistence.RatingBoards.MIN_RATED);   // played enough to enter (Q-43)
            ps.setLong(3, id);
            ps.executeUpdate();
        }
        return id;
    }

    /** A teams' tournament, as cup() times it, the two teams entered with their rosters. */
    private static long teamsCup(long one, long[] oneRoster, long two, long[] twoRoster) throws SQLException {
        long cup = tournaments.create("Team cup", 8, T.plusSeconds(60), T.plusSeconds(120), 2, 500, 200, 100,
                com.backend.persistence.MatchResultRepository.MODE_TEAMS);
        assertThat(tournaments.registerTeam(cup, one, java.util.Arrays.stream(oneRoster).boxed().toList(), T))
                .isEqualTo(TournamentRepository.Registration.OK);
        assertThat(tournaments.registerTeam(cup, two, java.util.Arrays.stream(twoRoster).boxed().toList(), T))
                .isEqualTo(TournamentRepository.Registration.OK);
        return cup;
    }

    /** A team match's result, as the worker would have written it: side 1 and side 2, placed. */
    private static void recordSides(String uid, long[] one, int onePlaced, long[] two, int twoPlaced) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long match;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO matches (match_uid, mode, kind, arena,"
                    + " started_at, ended_at) VALUES (?, 5, 1, 'arena-1', NOW(3), NOW(3))",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uid);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    match = keys.getLong(1);
                }
            }
            for (int side = 1; side <= 2; side++) {
                for (long p : side == 1 ? one : two) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO match_player (match_id, player_id, team,"
                            + " placement, kills, deaths, score, xp_gained, rating_delta) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0)")) {
                        ps.setLong(1, match);
                        ps.setLong(2, p);
                        ps.setInt(3, side);
                        ps.setInt(4, side == 1 ? onePlaced : twoPlaced);
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static List<Long> coins(long... players) throws SQLException {
        List<Long> out = new java.util.ArrayList<>();
        for (long p : players) {
            out.add(economy.coins(p));
        }
        return out;
    }
}
