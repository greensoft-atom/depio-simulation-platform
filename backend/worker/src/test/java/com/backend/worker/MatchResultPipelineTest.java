package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.LeaderboardStore.Board;
import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchOutcome.PlayerOutcome;
import com.backend.handoff.MatchResultCodec;
import com.backend.handoff.MatchResultQueue;
import com.backend.handoff.MatchResultStream;
import com.backend.handoff.Ulid;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.MatchResultRepository;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The result pipeline end to end: a queued entry becomes rows in MySQL, exactly once.
 *
 * Both stores are real. The queue's delivery guarantee is a property of {@code BLMOVE}
 * and {@code LREM}, and the exactly-once effect is a property of MySQL's unique keys —
 * neither survives being replaced with a fake.
 */
class MatchResultPipelineTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static JRedisEmbedded store;
    private static JRedisClient client;
    private static AccountRepository accounts;
    private static MatchResultStream queue;

    /** Fresh per test: its counters are cumulative, so a shared one measures the whole class. */
    private MatchResultConsumer consumer;
    private LeaderboardStore leaderboards;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        store = JRedisEmbedded.start();
        client = store.newClient();
        accounts = new AccountRepository(db.dataSource());
        queue = new MatchResultStream(client, MatchResultQueue.DEFAULT_WORKER_ID, MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
        client.sync().send("FLUSHALL");
        queue.ensureGroup();
        leaderboards = new LeaderboardStore(client);
        consumer = new MatchResultConsumer(queue, new MatchResultRepository(db.dataSource(), AccountLevels::levelFor),
                leaderboards);
    }

    /** As an arena publishes: to the stream, now. */
    private static void publish(String payload) {
        queue.publish(payload, System.currentTimeMillis());
    }

    private static long register(String username) throws SQLException {
        return accounts.register(username, username,
                "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
    }

    private static MatchOutcome outcomeFor(String uid, long winner, long loser) {
        return new MatchOutcome(uid, MatchOutcome.KIND_TIMED, 0, "arena-1",
                System.currentTimeMillis() - 300_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(winner, "Ada", 0, 1, 7, 2, 450, 300),
                        new PlayerOutcome(loser, "Bob", 0, 2, 2, 7, 120, 300)));
    }

    private static long scalar(String sql, Object arg) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    @Test
    @DisplayName("a duel's result moves both ratings by Elo, and a free-for-all's moves none")
    void aDuelIsRated() throws Exception {
        MatchResultConsumer rated = new MatchResultConsumer(queue, new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor, EloRating::deltas), leaderboards);
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome ffa = outcomeFor(Ulid.generate(), ada, bob);
        publish(MatchResultCodec.encode(ffa));
        assertThat(rated.handle(queue.claim(2.0))).isTrue();
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).isEqualTo(1_200);

        MatchOutcome duel = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED,
                com.backend.handoff.MatchMode.DUEL.id, "arena-1",
                System.currentTimeMillis() - 60_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(ada, "Ada", 0, 1, 3, 0, 300, 60),
                        new PlayerOutcome(bob, "Bob", 1, 2, 0, 3, 20, 60)));
        publish(MatchResultCodec.encode(duel));
        assertThat(rated.handle(queue.claim(2.0))).isTrue();
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).isEqualTo(1_216);
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", bob)).isEqualTo(1_184);
        assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", bob)).isEqualTo(1);

        // Cut short by a stop the drain could not wait out (D-29): recorded and paid, not rated.
        long coins = scalar("SELECT coins FROM player WHERE id = ?", ada);
        MatchOutcome cut = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED,
                com.backend.handoff.MatchMode.DUEL.id, "arena-1",
                System.currentTimeMillis() - 60_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(ada, "Ada", 0, 1, 1, 0, 100, 60),
                        new PlayerOutcome(bob, "Bob", 0, 2, 0, 1, 20, 60)), true);
        publish(MatchResultCodec.encode(cut));
        assertThat(MatchResultCodec.decode(MatchResultCodec.encode(cut)).cutShort()).as("it travels").isTrue();
        assertThat(rated.handle(queue.claim(2.0))).isTrue();
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).as("not rated").isEqualTo(1_216);
        assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", ada)).isEqualTo(1);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).as("paid").isGreaterThan(coins);
        assertThat(scalar("SELECT COUNT(*) FROM matches WHERE match_uid = ?", cut.matchUid())).as("recorded").isEqualTo(1);
        assertThat(scalar("SELECT cut_short FROM matches WHERE match_uid = ?", cut.matchUid()))
                .as("and recorded cut, for a tournament to read (Q-45)").isEqualTo(1);
        assertThat(scalar("SELECT cut_short FROM matches WHERE match_uid = ?", duel.matchUid())).isZero();
    }

    @Test
    @DisplayName("what a match paid is told to each player it paid, as applied, and once: evt.rewards (05 §6)")
    void whatAMatchPaidIsTold() throws Exception {
        java.util.Map<Long, com.fasterxml.jackson.databind.JsonNode> told = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger tellings = new java.util.concurrent.atomic.AtomicInteger();
        MatchResultConsumer rated = new MatchResultConsumer(queue, new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor, EloRating::deltas), leaderboards).telling((player, rewards) -> {
                    told.put(player, rewards);
                    tellings.incrementAndGet();
                });
        long ada = register("ada");
        long bob = register("bob");
        long adaCoins = scalar("SELECT coins FROM player WHERE id = ?", ada);
        try (Connection c = db.dataSource().getConnection();       // level 5's xp, level 4 stored: a milestone
             PreparedStatement ps = c.prepareStatement("UPDATE player SET xp = ?, level = 4 WHERE id = ?")) {
            ps.setLong(1, AccountLevels.xpRequired(5));
            ps.setLong(2, ada);
            ps.executeUpdate();
        }
        try (Connection c = db.dataSource().getConnection();       // his forty-ninth stay counted: an achievement next
             PreparedStatement ps = c.prepareStatement("REPLACE INTO player_stat (player_id, matches) VALUES (?, 49)")) {
            ps.setLong(1, bob);
            ps.executeUpdate();
        }
        MatchOutcome duel = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED,
                com.backend.handoff.MatchMode.DUEL.id, "arena-1",
                System.currentTimeMillis() - 60_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(ada, "Ada", 0, 1, 3, 0, 300, 60),
                        new PlayerOutcome(bob, "Bob", 1, 2, 0, 3, 20, 60)));
        String payload = MatchResultCodec.encode(duel);
        publish(payload);
        assertThat(rated.handle(queue.claim(2.0))).isTrue();

        assertThat(told).containsOnlyKeys(ada, bob);
        var a = told.get(ada);
        assertThat(a.get("matchUid").asText()).isEqualTo(duel.matchUid());
        assertThat(a.get("mode").asText()).isEqualTo("duel");
        assertThat(a.get("placement").asInt()).isEqualTo(1);
        assertThat(a.get("coins").asLong() + a.get("goalCoins").asLong() + a.at("/pass/coins").asLong())
                .as("what reached the wallet, the match's, the goals' and the pass's")
                .isEqualTo(scalar("SELECT coins FROM player WHERE id = ?", ada) - adaCoins).isPositive();
        assertThat(a.get("goals").isArray()).as("the goals met, named").isTrue();
        assertThat(List.of(a.at("/pass/points").asInt(), a.at("/pass/tier").asInt(), a.at("/pass/gems").asInt(),
                a.at("/pass/items").isArray())).as("the season pass's points, told (D-69)")
                .containsExactly(10 + 50 * a.get("goals").size(), 0, 0, true);
        long adaMatch = scalar("SELECT id FROM matches WHERE match_uid = ?", duel.matchUid());
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id, xp_gained, rating_delta FROM match_player WHERE match_id = ?")) {
            ps.setLong(1, adaMatch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var t = told.get(rs.getLong(1));
                    assertThat(t.get("xp").asInt()).isEqualTo(rs.getInt(2));
                    assertThat(t.get("ratingDelta").asInt()).as("as the transaction applied it").isEqualTo(rs.getInt(3));
                }
            }
        }
        assertThat(told.get(bob).get("ratingDelta").asInt()).isNegative();
        assertThat(told.get(bob).get("placement").asInt()).isEqualTo(2);
        assertThat(a.get("gems").asInt()).as("level 5's milestone (04 §8)").isEqualTo(20);
        assertThat(a.get("achievements")).isEmpty();
        assertThat(told.get(bob).get("gems").asInt()).as("his fiftieth match's achievement (04 §8)").isEqualTo(10);
        assertThat(told.get(bob).get("achievements")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("matches_50");

        publish(payload);                                          // delivered again: paid already
        assertThat(rated.handle(queue.claim(2.0))).isTrue();
        assertThat(tellings.get()).as("nobody is told twice").isEqualTo(2);
    }

    @Test
    @DisplayName("a result from before the field reads as not cut short: an older arena's is rated as it was")
    void anOlderResultIsNotCutShort() {
        String older = "{\"id\":\"01JC0000000000000000000000\",\"type\":\"match.result\",\"v\":1,\"ts\":1,"
                + "\"payload\":{\"matchUid\":\"01JC0000000000000000000000\",\"kind\":1,\"mode\":1,\"arena\":\"a\","
                + "\"startedAtMillis\":1,\"endedAtMillis\":2,\"players\":[]}}";
        assertThat(MatchResultCodec.decode(older).cutShort()).isFalse();
    }

    @Test
    @DisplayName("a team-vs-team's result moves each player's team rating by Elo on the teams' means (D-26)")
    void aTeamMatchIsRated() throws Exception {
        assertThat(new int[] {MatchResultRepository.MODE_DUEL, MatchResultRepository.MODE_TVT, MatchResultRepository.MODE_RFFA,
                        MatchResultRepository.MODE_TEAMS})
                .as("the repository's ids are the modes'")
                .containsExactly(com.backend.handoff.MatchMode.DUEL.id, com.backend.handoff.MatchMode.TVT.id,
                        com.backend.handoff.MatchMode.RFFA.id, com.backend.handoff.MatchMode.TEAMS.id);
        MatchResultConsumer rated = new MatchResultConsumer(queue, new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor, EloRating::deltas), leaderboards);
        long ada = register("ada");
        long bob = register("bob");
        long cy = register("cy");
        long di = register("di");
        MatchOutcome tvt = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED,
                com.backend.handoff.MatchMode.TVT.id, "arena-1",
                System.currentTimeMillis() - 60_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(ada, "Ada", 1, 1, 3, 0, 300, 60),
                        new PlayerOutcome(bob, "Bob", 1, 1, 0, 0, 20, 60),
                        new PlayerOutcome(cy, "Cy", 2, 2, 0, 2, 20, 60),
                        new PlayerOutcome(di, "Di", 2, 2, 0, 1, 20, 60)));
        publish(MatchResultCodec.encode(tvt));
        assertThat(rated.handle(queue.claim(2.0))).isTrue();
        // Equal means: E is a half, and a new player's K is 32.
        for (long winner : new long[] {ada, bob}) {
            assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", winner)).isEqualTo(1_216);
        }
        for (long loser : new long[] {cy, di}) {
            assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", loser)).isEqualTo(1_184);
            assertThat(scalar("SELECT rated_tvts FROM player WHERE id = ?", loser)).isEqualTo(1);
        }
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).as("the duel's, untouched").isEqualTo(1_200);
    }

    @Test
    @DisplayName("a team match's outcome rates its two teams by Elo, and none of its players (Q-18, D-43)")
    void aTeamMatchRatesTheTeams() throws Exception {
        MatchResultConsumer rated = new MatchResultConsumer(queue, new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor, EloRating::deltas), leaderboards);
        com.backend.persistence.TeamRepository teams = new com.backend.persistence.TeamRepository(db.dataSource(), 30);
        long[] one = {register("ada"), register("bob")};
        long[] two = {register("cy"), register("di")};
        java.time.Instant now = java.time.Instant.now();
        long tanks = teams.create(one[0], "Tanks", now).teamId();
        teams.invite(one[0], one[1], now);
        teams.answer(one[1], tanks, true, now);
        long rams = teams.create(two[0], "Rams", now).teamId();
        MatchOutcome match = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED,
                com.backend.handoff.MatchMode.TEAMS.id, "arena-1",
                System.currentTimeMillis() - 60_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(one[0], "Ada", 1, 1, 3, 0, 300, 60),
                        new PlayerOutcome(one[1], "Bob", 1, 1, 0, 0, 20, 60),
                        new PlayerOutcome(two[0], "Cy", 2, 2, 0, 2, 20, 60)));
        publish(MatchResultCodec.encode(match));
        assertThat(rated.handle(queue.claim(2.0))).isTrue();
        // Equal teams: E is a half, and a new team's K is 32.
        assertThat(scalar("SELECT rating FROM team WHERE id = ?", tanks)).isEqualTo(1_216);
        assertThat(scalar("SELECT rating FROM team WHERE id = ?", rams)).isEqualTo(1_184);
        assertThat(scalar("SELECT wins FROM team WHERE id = ?", tanks)).isEqualTo(1);
        assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", one[0])).as("no player's").isEqualTo(1_200);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", one[0])).as("paid as any match").isPositive();
    }

    @Test
    @DisplayName("a boost running when the match ended raises its experience or coins by its percent; not otherwise (D-38)")
    void boostsRaiseRewards() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        com.backend.persistence.BoostRepository boosts = new com.backend.persistence.BoostRepository(db.dataSource());
        for (String item : List.of("boost_xp", "boost_coins")) {
            try (Connection c = db.dataSource().getConnection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO inventory_item (player_id, item_id, qty) VALUES (?, ?, 1)")) {
                ps.setLong(1, ada);
                ps.setString(2, item);
                ps.executeUpdate();
            }
        }
        java.time.Instant start = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES);
        boosts.activate(ada, "boost_xp", 0, 100, 60, "k1", start);
        boosts.activate(ada, "boost_coins", 1, 40, 60, "k2", start);
        MatchResultConsumer boosted = new MatchResultConsumer(queue,
                new MatchResultRepository(db.dataSource(), AccountLevels::levelFor), leaderboards, boosts::percentsAt);

        publish(MatchResultCodec.encode(outcomeFor(Ulid.generate(), ada, bob)));
        assertThat(boosted.handle(queue.claim(2.0))).isTrue();
        // Unboosted, 485 xp and 95 coins (the test above): doubled, and 40 % more, rounded down.
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", ada)).isEqualTo(970);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).isEqualTo(133);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", bob)).as("no boost of his own").isEqualTo(12 + 10);

        // A match that ended after both had run out: as unboosted.
        long after = start.plus(61, java.time.temporal.ChronoUnit.MINUTES).toEpochMilli();
        publish(MatchResultCodec.encode(new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED, 0, "arena-1",
                after - 300_000, after, List.of(new PlayerOutcome(ada, "Ada", 0, 1, 7, 2, 450, 300),
                        new PlayerOutcome(bob, "Bob", 0, 2, 2, 7, 120, 300)))));
        assertThat(boosted.handle(queue.claim(2.0))).isTrue();
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", ada)).isEqualTo(970 + 485);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).isEqualTo(133 + 95);
    }

    @Test
    @DisplayName("a queued match becomes rows, coins and xp")
    void queuedMatchIsApplied() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = outcomeFor(Ulid.generate(), ada, bob);

        publish(MatchResultCodec.encode(match));
        assertThat(queue.depth()).isEqualTo(1);

        MatchResultStream.Entry entry = queue.claim(2.0);
        assertThat(consumer.handle(entry)).isTrue();

        assertThat(consumer.appliedCount()).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM matches WHERE match_uid = ?", match.matchUid()))
                .isEqualTo(1);
        // Stated in the row rather than inferred from the placement later.
        assertThat(scalar("SELECT kind FROM matches WHERE match_uid = ?", match.matchUid()))
                .isEqualTo(MatchOutcome.KIND_TIMED);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada)).isEqualTo(1);
        assertThat(scalar("SELECT kills FROM match_player WHERE player_id = ?", ada)).isEqualTo(7);

        // The winner's reward: 450 score + 7 kills * 5 = 485 xp, 45 + 50 = 95 coins.
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", ada)).isEqualTo(485);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).isEqualTo(95);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", bob))
                .as("taking part pays less than winning").isEqualTo(12 + 10);

        assertThat(queue.abandoned()).as("acknowledged, so nothing is left in flight").isEmpty();
        assertThat(queue.depth()).isZero();
    }

    @Test
    @DisplayName("an entry delivered twice is applied once")
    void redeliveryIsAbsorbed() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = outcomeFor(Ulid.generate(), ada, bob);
        String entry = MatchResultCodec.encode(match);

        publish(entry);
        consumer.handle(queue.claim(2.0));
        publish(entry);                       // exactly what an unacked retry looks like
        consumer.handle(queue.claim(2.0));

        assertThat(consumer.appliedCount()).isEqualTo(1);
        assertThat(consumer.duplicateCount()).isEqualTo(1);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada))
                .as("paid once, not twice").isEqualTo(95);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada)).isEqualTo(1);
    }

    @Test
    @DisplayName("an applied match puts both players on every board, under the names the database holds as it is applied (D-60)")
    void queuedMatchIsRanked() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = outcomeFor(Ulid.generate(), ada, bob);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET display_name = 'Ada Renamed' WHERE id = ?")) {
            ps.setLong(1, ada);
            ps.executeUpdate();                        // renamed after the match began, before it is applied
        }

        publish(MatchResultCodec.encode(match));
        consumer.handle(queue.claim(2.0));

        Instant ended = Instant.ofEpochMilli(match.endedAtMillis());
        for (Board board : Board.values()) {
            assertThat(client.sync().zscore(board.key(ended), Long.toString(ada)))
                    .as(board.apiName()).isEqualTo(450.0);
            assertThat(client.sync().zscore(board.key(ended), Long.toString(bob)))
                    .as(board.apiName()).isEqualTo(120.0);
        }
        // The name is the database's as the result is applied, not the one the result carries
        // from the match's start: a rename meanwhile is not undone. Rendering a board still needs
        // no query against MySQL and no call back to platform.
        assertThat(leaderboards.top(Board.ALLTIME, 10, ended).get())
                .extracting(LeaderboardStore.Entry::name)
                .containsExactly("Ada Renamed", "bob");
        assertThat(consumer.leaderboardFailureCount()).isZero();
    }

    @Test
    @DisplayName("a redelivery repairs a board update that never landed")
    void redeliveryRepairsTheBoard() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = outcomeFor(Ulid.generate(), ada, bob);
        String entry = MatchResultCodec.encode(match);

        publish(entry);
        consumer.handle(queue.claim(2.0));

        // Exactly the state a worker killed between the MySQL commit and the board write
        // leaves behind: the rows are there, the ranking is not.
        Instant ended = Instant.ofEpochMilli(match.endedAtMillis());
        client.sync().del(Board.ALLTIME.key(ended));

        publish(entry);
        consumer.handle(queue.claim(2.0));

        assertThat(consumer.duplicateCount()).as("MySQL knows it has seen this").isEqualTo(1);
        // And the board is written anyway. Skipping it for a known result would make this
        // loss permanent: no later delivery of this match will ever come.
        assertThat(client.sync().zscore(Board.ALLTIME.key(ended), Long.toString(ada)))
                .isEqualTo(450.0);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada))
                .as("and still paid exactly once").isEqualTo(95);
    }

    @Test
    @DisplayName("a worker that dies mid-apply leaves the entry recoverable")
    void crashBeforeAckIsRecovered() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = outcomeFor(Ulid.generate(), ada, bob);
        publish(MatchResultCodec.encode(match));

        // Claimed and then abandoned: the entry is off the queue but on the processing list,
        // which is precisely where a killed worker leaves it.
        MatchResultStream.Entry claimed = queue.claim(2.0);
        assertThat(claimed).isNotNull();
        assertThat(queue.depth()).as("no longer on the queue").isZero();
        assertThat(queue.abandoned()).as("but not lost").hasSize(1);

        int recovered = consumer.recoverAbandoned();

        assertThat(recovered).isEqualTo(1);
        assertThat(queue.abandoned()).isEmpty();
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).isEqualTo(95);
    }

    @Test
    @DisplayName("a result more than 30 days late is dead-lettered, not paid")
    void lateResultIsDeadLettered() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        long ended = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(31);
        MatchOutcome late = new MatchOutcome("01JBLATEPIPE00000000000001", MatchOutcome.KIND_TIMED,
                0, "arena-1", ended - 300_000, ended,
                List.of(new PlayerOutcome(ada, "Ada", 0, 1, 7, 2, 450, 300),
                        new PlayerOutcome(bob, "Bob", 0, 2, 2, 7, 120, 300)));
        // A spool on an arena that was down for a month: past the window in which the result
        // could be recognised as already applied (D-14).
        publish(MatchResultCodec.encode(late));
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();

        assertThat(queue.deadCount()).as("kept as evidence for a person to decide").isEqualTo(1);
        assertThat(consumer.appliedCount()).isZero();
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", ada)).isZero();
    }

    @Test
    @DisplayName("the daily retention run deletes history past its retention")
    void retentionRun() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        publish(MatchResultCodec.encode(outcomeFor("01JBRETAIN0000000000000001", ada, bob)));
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();
        try (Connection c = db.dataSource().getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE matches SET ended_at = ended_at - INTERVAL 181 DAY");
        }

        com.backend.persistence.InboxRepository inbox = new com.backend.persistence.InboxRepository(db.dataSource());
        java.time.Instant now = java.time.Instant.now();
        inbox.add(ada, com.backend.persistence.InboxRepository.TEAM_INVITE, 1, now.minus(java.time.Duration.ofDays(31)));
        inbox.add(bob, com.backend.persistence.InboxRepository.TEAM_INVITE, 1, now.minus(java.time.Duration.ofDays(29)));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO player_day (day, player_id) VALUES (?, ?)")) {
            for (int back : new int[] {91, 90}) {
                ps.setObject(1, today.minusDays(back));
                ps.setLong(2, ada);
                ps.executeUpdate();
            }
        }

        com.backend.persistence.FriendRepository friends = new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100);
        com.backend.persistence.TeamRepository teams = new com.backend.persistence.TeamRepository(db.dataSource(), 30);
        java.time.Instant weekAgo = now.minus(java.time.Duration.ofDays(8));
        long cyd = register("cyd");
        friends.ask(ada, cyd, weekAgo);
        friends.ask(bob, cyd, now);
        teams.create(ada, "Lapsed", weekAgo);
        teams.invite(ada, cyd, weekAgo);
        long appliedTo = teams.create(register("dee"), "Applied", weekAgo).teamId();
        assertThat(teams.apply(cyd, appliedTo, weekAgo).outcome()).isEqualTo(com.backend.persistence.TeamRepository.Outcome.OK);

        // A boost ended, and its key used, 40 days ago; a tournament finished 100 days after it was to start (D-38).
        com.backend.persistence.TournamentRepository tournaments = new com.backend.persistence.TournamentRepository(db.dataSource());
        long cup = tournaments.create("Old cup", 4, now.minus(java.time.Duration.ofDays(101)),
                now.minus(java.time.Duration.ofDays(100)), 5, 0, 0, 0);
        try (Connection c = db.dataSource().getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE tournament SET state = " + com.backend.persistence.TournamentRepository.FINISHED
                    + " WHERE id = " + cup);
            st.executeUpdate("INSERT INTO boost (player_id, kind, item_id, percent, started_at, ends_at) VALUES ("
                    + ada + ", 0, 'boost_xp_hour', 100, NOW(3) - INTERVAL 41 DAY, NOW(3) - INTERVAL 40 DAY)");
            st.executeUpdate("INSERT INTO boost_activation (idem_key, player_id, item_id, at) VALUES ('act:" + ada
                    + ":old', " + ada + ", 'boost_xp_hour', NOW(3) - INTERVAL 40 DAY)");
            st.executeUpdate("INSERT INTO daily_goal (player_id, day, goal_id, progress) VALUES ("
                    + ada + ", UTC_DATE() - INTERVAL 8 DAY, 'kills_10', 4), (" + bob + ", UTC_DATE() - INTERVAL 7 DAY, 'kills_10', 4)");
        }

        // An order left pending two days, and one an hour (D-68).
        com.backend.persistence.PaymentRepository payments = new com.backend.persistence.PaymentRepository(db.dataSource());
        String stale = payments.place(ada, "00000000-0000-4000-8000-0000000000d1", "gems_80", 80, 99, "USD", "simulated",
                now.minus(java.time.Duration.ofDays(2))).order().id();
        String young = payments.place(ada, "00000000-0000-4000-8000-0000000000d2", "gems_80", 80, 99, "USD", "simulated",
                now.minus(java.time.Duration.ofHours(1))).order().id();

        // Eight seasons, the eighth being played; a pass in the first and in the third (D-69: six kept).
        try (Connection c = db.dataSource().getConnection();
             java.sql.Statement st = c.createStatement()) {
            for (int s = 2; s <= 8; s++) {
                st.executeUpdate("UPDATE season SET placed_at = NOW(3) WHERE id = " + (s - 1));
                st.executeUpdate("INSERT INTO season (id, starts_at, ends_at) VALUES (" + s + ", NOW(3), NOW(3) + INTERVAL 61 DAY)");
            }
            st.executeUpdate("INSERT INTO backup_run (kind, started_at, finished_at, ok, detail) VALUES"
                    + " (1, NOW(3) - INTERVAL 100 DAY, NOW(3) - INTERVAL 100 DAY, TRUE, 'old'),"
                    + " (1, NOW(3) - INTERVAL 10 DAY, NOW(3) - INTERVAL 10 DAY, TRUE, 'kept')");
            st.executeUpdate("INSERT INTO season_pass (player_id, season_id, points) VALUES (" + ada + ", 1, 10), (" + ada + ", 3, 10)"
                    + " ON DUPLICATE KEY UPDATE points = points");               // the first may be there, from the results above
        }

        try (Retention retention = new Retention(new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor), inbox, new com.backend.persistence.StatsRepository(db.dataSource()),
                friends, teams, new com.backend.persistence.BoostRepository(db.dataSource()), tournaments,
                new com.backend.persistence.DailyGoalRepository(db.dataSource()), payments,
                new com.backend.persistence.SeasonPassRepository(db.dataSource()),
                new com.backend.persistence.BackupRunRepository(db.dataSource()))) {
            assertThat(retention.lastRunSeconds()).as("before any run").isNaN();
            assertThat(retention.runOnce()).isEqualTo(1);
            assertThat(retention.lastRunSeconds()).as("how long the run took, reported (D-72)").isNotNaN().isPositive();
            assertThat(retention.runOnce()).as("and nothing the second time").isZero();
        }
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada)).isZero();
        assertThat(inbox.itemsOf(ada)).as("an inbox item past its 30 days (Q-20)").isEmpty();
        assertThat(inbox.itemsOf(bob)).as("and none younger").hasSize(1);
        assertThat(scalar("SELECT COUNT(*) FROM friend_request WHERE from_id = ?", ada)).as("a lapsed request (D-40)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM friend_request WHERE from_id = ?", bob)).as("and none live").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM team_invite WHERE player_id = ?", cyd)).as("a lapsed invitation").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM team_application WHERE player_id = ?", cyd)).as("a lapsed application (Q-49)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM boost WHERE player_id = ?", ada)).as("a boost 40 days ended (D-38)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM boost_activation WHERE player_id = ?", ada)).as("and its key").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM tournament WHERE id = ?", cup)).as("a tournament past its 90 days (Q-47)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM player_day WHERE player_id = ? AND day < CURRENT_DATE - INTERVAL 90 DAY", ada))
                .as("a day of activity past its 90 (05 §11)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM player_day WHERE player_id = ? AND day = CURRENT_DATE - INTERVAL 90 DAY", ada))
                .as("and not the 90th").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM daily_goal WHERE player_id = ?", ada)).as("a day's goals past their week (D-66)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM daily_goal WHERE player_id = ?", bob)).as("and not the seventh day's").isEqualTo(1);
        assertThat(payments.get(stale).state()).as("an order pending past its day (D-68)").isEqualTo(
                com.backend.persistence.PaymentRepository.EXPIRED);
        assertThat(payments.get(young).state()).as("and not one younger").isEqualTo(com.backend.persistence.PaymentRepository.PENDING);
        assertThat(scalar("SELECT COUNT(*) FROM season_pass WHERE player_id = ? AND season_id = 1", ada))
                .as("a pass seven seasons back (D-69)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM season_pass WHERE player_id = ? AND season_id = 3", ada))
                .as("and not one of the six kept").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM backup_run WHERE detail = ?", "old")).as("a backup's row past its 90 days (D-71)").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM backup_run WHERE detail = ?", "kept")).isEqualTo(1);
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", ada)).as("kept").isPositive();
    }

    @Test
    @DisplayName("retention: a step that fails stops none after it and the run's time is recorded (D-49); each backup kind's newest success is kept, however old (D-48)")
    void aFailedRetentionStepStopsNoOther() throws Exception {
        long eve = register("eve");
        try (Connection c = db.dataSource().getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO daily_goal (player_id, day, goal_id, progress) VALUES (" + eve
                    + ", UTC_DATE() - INTERVAL 20 DAY, 'stays_3', 1)");
            st.executeUpdate("INSERT INTO backup_run (kind, started_at, finished_at, ok, detail) VALUES"
                    + " (2, NOW(3) - INTERVAL 100 DAY, NOW(3) - INTERVAL 100 DAY, TRUE, 'the last proof that passed'),"
                    + " (2, NOW(3) - INTERVAL 95 DAY, NOW(3) - INTERVAL 95 DAY, FALSE, 'a failed one, older than kept'),"
                    + " (1, NOW(3) - INTERVAL 100 DAY, NOW(3) - INTERVAL 100 DAY, TRUE, 'an old dump'),"
                    + " (1, NOW(3) - INTERVAL 10 DAY, NOW(3) - INTERVAL 10 DAY, TRUE, 'a newer dump')");
            st.executeUpdate("DROP TABLE inbox");                    // the inbox's step fails
        }
        try (Retention retention = new Retention(new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor), new com.backend.persistence.InboxRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()), new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100),
                new com.backend.persistence.TeamRepository(db.dataSource(), 30), new com.backend.persistence.BoostRepository(db.dataSource()),
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.DailyGoalRepository(db.dataSource()),
                new com.backend.persistence.PaymentRepository(db.dataSource()),
                new com.backend.persistence.SeasonPassRepository(db.dataSource()),
                new com.backend.persistence.BackupRunRepository(db.dataSource()))) {
            retention.runOnce();
            assertThat(retention.lastRunSeconds()).as("timed though a step failed").isNotNaN();
        }
        assertThat(scalar("SELECT COUNT(*) FROM daily_goal WHERE player_id = ?", eve)).as("a later step ran").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM backup_run WHERE detail = ?", "the last proof that passed"))
                .as("the newest success of its kind, kept, or no alert could say how long since one").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM backup_run WHERE detail = ?", "a failed one, older than kept")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM backup_run WHERE detail = ?", "an old dump")).as("not the newest").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM backup_run WHERE detail = ?", "a newer dump")).isEqualTo(1);
    }

    @Test
    @DisplayName("assists reach match_player and player_stat; an older arena's result, without them, counts none (01 §7, V25)")
    void assistsAreStored() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED, 0, "arena-1",
                System.currentTimeMillis() - 300_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(ada, "Ada", 0, 1, 1, 0, 300, 300, 2),
                        new PlayerOutcome(bob, "Bob", 0, 2, 0, 1, 100, 300, 1)));
        publish(MatchResultCodec.encode(match));
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();
        assertThat(scalar("SELECT assists FROM match_player WHERE player_id = ?", ada)).isEqualTo(2);
        assertThat(scalar("SELECT assists FROM player_stat WHERE player_id = ?", bob)).isEqualTo(1);

        String older = MatchResultCodec.encode(outcomeFor(Ulid.generate(), ada, bob)).replace(",\"assists\":0", "");
        assertThat(older).doesNotContain("assists");
        publish(older);
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();
        assertThat(scalar("SELECT assists FROM player_stat WHERE player_id = ?", ada)).as("none added").isEqualTo(2);
    }

    @Test
    @DisplayName("an entry nobody can read is set aside instead of blocking the queue")
    void unreadableEntryIsDeadLettered() throws Exception {
        publish("{ this is not json");
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();

        // Version 0, an envelope with no version at all. This used to be version 99, and the
        // two are not the same: 99 is newer than this build, which a later worker may read,
        // so it is deferred rather than discarded (see newerVersionIsDeferredNotDiscarded).
        // Nothing will ever read version 0.
        publish("{\"id\":\"x\",\"type\":\"match.result\",\"v\":0,\"ts\":1,\"payload\":null}");
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();

        // Retrying an unreadable entry for ever would stop every good entry behind it.
        assertThat(consumer.deadLetteredCount()).isEqualTo(2);
        assertThat(queue.deadCount()).as("kept, because it is evidence").isEqualTo(2);
        assertThat(queue.abandoned()).isEmpty();
        assertThat(consumer.appliedCount()).isZero();
    }

    @Test
    @DisplayName("a newer producer's extra fields do not stop an older worker")
    void unknownFieldsAreTolerated() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = outcomeFor(Ulid.generate(), ada, bob);
        String entry = MatchResultCodec.encode(match)
                .replace("\"payload\":{", "\"region\":\"eu\",\"payload\":{\"weather\":\"rain\",");

        publish(entry);
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();

        // During a rolling deploy the new arena writes what the old worker reads. Refusing
        // over a field nobody needed would be an outage we caused ourselves.
        assertThat(consumer.appliedCount()).isEqualTo(1);
        assertThat(consumer.deadLetteredCount()).isZero();
    }

    // ---- durability: the defects the audit of 2026-09-23 found (docs/defects.md §3) ------

    /** An outcome for players who may or may not exist, with explicit numbers. */
    private static MatchOutcome outcomeWith(PlayerOutcome... players) {
        return new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED, 0, "arena-1",
                System.currentTimeMillis() - 300_000, System.currentTimeMillis(), List.of(players));
    }

    @Test
    @DisplayName("a result naming only players that do not exist is not mistaken for a duplicate")
    void missingPlayersAreNotDuplicates() throws Exception {
        long nobody = 987_654_321L;                 // no such player row
        publish(MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(nobody, "Ghost", 0, 1, 3, 1, 450, 300))));

        consumer.handle(queue.claim(2.0));

        // INSERT IGNORE turns a foreign-key violation into a warning and zero rows, which
        // the old code read as "already applied" — so this was counted as a redelivery,
        // acknowledged, and logged at debug. Nothing was ever paid and nobody could tell.
        assertThat(consumer.duplicateCount())
                .as("a player who does not exist is not a result that was already applied")
                .isZero();
        assertThat(consumer.unapplicablePlayerCount()).isEqualTo(1);
        assertThat(queue.abandoned()).as("and it is not retried for ever either").isEmpty();
    }

    @Test
    @DisplayName("a player who does not exist is not put on the boards either")
    void missingPlayersAreNotRanked() throws Exception {
        long ada = register("ada");
        long nobody = 987_654_322L;
        MatchOutcome match = outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 3, 1, 450, 300),
                new PlayerOutcome(nobody, "Ghost", 0, 2, 1, 3, 200, 300));
        publish(MatchResultCodec.encode(match));

        consumer.handle(queue.claim(2.0));

        // The boards are a projection of what MySQL committed (05 §8): a score it refused
        // would sit on them until the day's board expired, and never on a rebuilt one.
        Instant ended = Instant.ofEpochMilli(match.endedAtMillis());
        for (Board board : Board.values()) {
            assertThat(client.sync().zscore(board.key(ended), Long.toString(ada))).isEqualTo(450.0);
            assertThat(client.sync().zscore(board.key(ended), Long.toString(nobody)))
                    .as(board.apiName()).isNull();
        }
    }

    @Test
    @DisplayName("a worker starting while the database is down parks one entry, and claims no more")
    void startingInAnOutageClaimsNothing() throws Exception {
        long ada = register("ada");
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        MatchResultRepository real = new MatchResultRepository(db.dataSource(), AccountLevels::levelFor);
        MatchResultConsumer.Applier outage = result -> {
            if (down.get()) {
                throw new java.sql.SQLTransientConnectionException("connection lost", "08S01");
            }
            return real.apply(result);
        };
        for (int i = 0; i < 5; i++) {
            publish(MatchResultCodec.encode(outcomeWith(
                    new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 100 + i, 300))));
        }
        queue.claim(2.0);                               // left parked by a worker that died

        MatchResultConsumer c = new MatchResultConsumer(queue, outage, leaderboards, 200);
        c.recoverAbandoned();                           // what a start does first: it fails
        Thread loop = running(c);
        try {
            Thread.sleep(1_000);
            assertThat(queue.abandoned()).as("the one it found parked").hasSize(1);
            assertThat(queue.depth()).as("and none claimed since").isEqualTo(4);
            down.set(false);
            assertThat(eventually(() -> c.appliedCount() == 5, 15_000)).isTrue();
        } finally {
            c.stop();
            loop.join(10_000);
        }
    }

    @Test
    @DisplayName("an entry the store refuses to set aside stays where it was, and is not lost")
    void aRefusedSetAsideKeepsTheEntry() throws Exception {
        publish("{not a result");
        MatchResultStream.Entry entry = queue.claim(2.0);
        client.sync().set(MatchResultQueue.DEAD_KEY, "not a list");   // WRONGTYPE for LPUSH

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.handle(entry))
                .hasMessageContaining("WRONGTYPE");

        // One transaction ran the removal after the refused push: the entry was on no list.
        assertThat(queue.abandoned()).as("still claimed, for the next attempt").containsExactly(entry);
    }

    @Test
    @DisplayName("an impossible value is refused, not silently clamped into the table")
    void outOfRangeIsRefusedNotClamped() throws Exception {
        long ada = register("ada");
        // 70 000 kills does not fit SMALLINT UNSIGNED. INSERT IGNORE stored 65 535 and then
        // paid xp and coins on the UNclamped value, so the row and the balance disagreed for
        // good and the ledger still reconciled, because the ledger was wrong the same way.
        publish(MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 70_000, 1, 450, 300))));

        consumer.handle(queue.claim(2.0));

        assertThat(queue.deadCount()).as("set aside as evidence").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada))
                .as("and nothing half-written").isZero();
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).isZero();
        assertThat(queue.abandoned()).isEmpty();
    }

    @Test
    @DisplayName("a negative score is dead-lettered rather than parked for ever")
    void negativeValueDoesNotPark() throws Exception {
        long ada = register("ada");
        publish(MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 0, 0, -500, 300))));

        consumer.handle(queue.claim(2.0));

        // A negative xp against BIGINT UNSIGNED is an error no retry will ever fix. Left on
        // the processing list it is re-tried on every start, for ever, and the only symptom
        // is a list that never drains.
        assertThat(queue.abandoned()).as("not parked on the processing list").isEmpty();
        assertThat(queue.deadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry from a newer producer is kept for a newer worker, not dead-lettered")
    void newerVersionIsDeferredNotDiscarded() throws Exception {
        long ada = register("ada");
        String fromTheFuture = MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300)))
                .replace("\"v\":" + MatchResultCodec.VERSION, "\"v\":" + (MatchResultCodec.VERSION + 1));
        publish(fromTheFuture);

        consumer.handle(queue.claim(2.0));

        // Deploy the arena before the workers and every result in the rollout window used to
        // go to the dead list, which nothing ever replays. A newer version is not garbage;
        // it is simply not for this build.
        assertThat(queue.deadCount()).as("not treated as garbage").isZero();
        assertThat(queue.abandoned()).as("and not left clogging the processing list").isEmpty();
        assertThat(queue.deferredCount()).isEqualTo(1);
    }

    /** Runs the consumer's real loop on its own thread, as {@code WorkerMain} does. */
    private static Thread running(MatchResultConsumer c) {
        Thread t = new Thread(c::run, "consumer-under-test");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Polls until {@code condition} holds or the deadline passes. */
    private static boolean eventually(java.util.function.BooleanSupplier condition, long millis)
            throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("a database that fails and then recovers is caught up without a restart")
    void transientFailureRecoversWithoutRestart() throws Exception {
        long ada = register("ada");
        MatchResultRepository real = new MatchResultRepository(db.dataSource(), AccountLevels::levelFor);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // Two failures, then the database is back. A lost connection is SQLSTATE class 08.
        MatchResultConsumer.Applier flaky = result -> {
            if (calls.incrementAndGet() <= 2) {
                throw new java.sql.SQLTransientConnectionException("connection lost", "08S01");
            }
            return real.apply(result);
        };
        MatchResultConsumer c = new MatchResultConsumer(queue, flaky, leaderboards, 200);
        publish(MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));

        Thread loop = running(c);
        try {
            // The old worker left this on the processing list and looked at that list only at
            // start-up, so a one-minute blip parked results until somebody restarted it.
            assertThat(eventually(() -> c.appliedCount() == 1, 15_000))
                    .as("applied once the database came back, by the same running worker")
                    .isTrue();
        } finally {
            c.stop();
            loop.join(10_000);
        }
        assertThat(calls.get()).as("it really did fail first").isGreaterThanOrEqualTo(3);
        assertThat(queue.abandoned()).isEmpty();
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada)).isEqualTo(1);
    }

    @Test
    @DisplayName("while the database is down the worker parks one entry, and claims no more")
    void outageParksOneEntryOnly() throws Exception {
        long ada = register("ada");
        MatchResultRepository real = new MatchResultRepository(db.dataSource(), AccountLevels::levelFor);
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        MatchResultConsumer.Applier outage = result -> {
            if (down.get()) {
                throw new java.sql.SQLTransientConnectionException("connection lost", "08S01");
            }
            return real.apply(result);
        };
        MatchResultConsumer c = new MatchResultConsumer(queue, outage, leaderboards, 200);
        for (int i = 0; i < 5; i++) {
            publish(MatchResultCodec.encode(outcomeWith(
                    new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 100 + i, 300))));
        }

        Thread loop = running(c);
        try {
            Thread.sleep(3_000);                        // fifteen pause-and-retry cycles
            // Each cycle used to claim one more: the whole queue ended up on this worker's own
            // list, which no other worker will ever take from.
            assertThat(queue.abandoned()).as("parked on this worker").hasSize(1);
            assertThat(queue.depth()).as("left on the shared queue").isEqualTo(4);

            down.set(false);
            assertThat(eventually(() -> c.appliedCount() == 5, 15_000))
                    .as("all applied once it is back").isTrue();
        } finally {
            c.stop();
            loop.join(10_000);
        }
    }

    @Test
    @DisplayName("an unexpected failure on one entry neither kills the worker nor loses the entry")
    void unexpectedFailureDoesNotEndTheLoop() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome poisoned = outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300));
        MatchOutcome healthy = outcomeWith(new PlayerOutcome(bob, "Bob", 0, 1, 2, 1, 450, 300));
        MatchResultRepository real = new MatchResultRepository(db.dataSource(), AccountLevels::levelFor);
        MatchResultConsumer.Applier buggy = result -> {
            if (result.matchUid().equals(poisoned.matchUid())) {
                throw new IllegalStateException("a bug that only this entry triggers");
            }
            return real.apply(result);
        };
        MatchResultConsumer c = new MatchResultConsumer(queue, buggy, leaderboards, 200);
        publish(MatchResultCodec.encode(poisoned));
        publish(MatchResultCodec.encode(healthy));

        Thread loop = running(c);
        try {
            assertThat(eventually(() -> c.appliedCount() == 1, 15_000))
                    .as("the entry behind the bad one is still applied").isTrue();
            assertThat(c.isRunning()).as("and the worker is still running").isTrue();
            assertThat(loop.isAlive()).isTrue();
        } finally {
            c.stop();
            loop.join(10_000);
        }
        // Not dead-lettered: an unexplained failure might be a bug fixed by the next deploy,
        // and discarding a payment on a guess is the one mistake that cannot be undone.
        assertThat(queue.deadCount()).isZero();
        assertThat(queue.abandoned()).as("kept, and retried").hasSize(1);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", bob)).isEqualTo(1);
    }

    @Test
    @DisplayName("a worker starting up does not take another worker's entries in flight")
    void workersDoNotStealEachOthersWork() throws Exception {
        long ada = register("ada");
        MatchResultStream first = new MatchResultStream(client, "worker-a", MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
        MatchResultStream second = new MatchResultStream(client, "worker-b", MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
        MatchResultRepository repo = new MatchResultRepository(db.dataSource(), AccountLevels::levelFor);
        MatchResultConsumer a = new MatchResultConsumer(first, repo, leaderboards);
        MatchResultConsumer b = new MatchResultConsumer(second, repo, leaderboards);

        publish(MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        assertThat(first.claim(2.0)).as("worker A has it, mid-transaction").isNotNull();

        // Worker B restarts during a rolling deploy. With one shared processing list it could
        // not tell A's live entry from a dead worker's, and applied it underneath A.
        b.recoverAbandoned();

        assertThat(b.appliedCount()).isZero();
        assertThat(first.abandoned()).as("still A's").hasSize(1);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada)).isZero();

        a.recoverAbandoned();
        assertThat(a.appliedCount()).as("and A finishes what it started").isEqualTo(1);
    }

    @Test
    @DisplayName("an upgrade returns what the old shared processing list held")
    void legacyProcessingListIsReclaimed() throws Exception {
        long ada = register("ada");
        String entry = MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300)));
        // Exactly where an earlier build left an entry it had claimed when it was stopped.
        client.sync().lpush(MatchResultQueue.LEGACY_PROCESSING_KEY, entry);

        consumer.recoverAbandoned();

        assertThat(client.sync().llen(MatchResultQueue.LEGACY_PROCESSING_KEY)).isZero();
        assertThat(queue.depth()).as("back on the queue, not stranded").isEqualTo(1);
        consumer.handle(queue.claim(2.0));
        assertThat(consumer.appliedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("deferred entries are offered again when a worker starts")
    void deferredEntriesAreReturnedAtStartup() throws Exception {
        client.sync().lpush(MatchResultQueue.DEFERRED_KEY, "{\"placeholder\":1}");

        consumer.recoverAbandoned();

        assertThat(queue.deferredCount()).isZero();
        assertThat(queue.depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired worker's entries are taken over by another once idle a while (D-33)")
    void aRetiredWorkersEntriesAreTakenOver() throws Exception {
        long ada = register("ada");
        MatchResultStream retired = new MatchResultStream(client, "worker-gone", MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
        MatchResultStream live = new MatchResultStream(client, "worker-b", 1_000);
        MatchResultConsumer b = new MatchResultConsumer(live,
                new MatchResultRepository(db.dataSource(), AccountLevels::levelFor), leaderboards);
        publish(MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        assertThat(retired.claim(2.0)).as("claimed, and its worker never comes back").isNotNull();

        b.redrive();
        assertThat(b.appliedCount()).as("not idle long enough yet: it may be a live worker's").isZero();
        Thread.sleep(1_200);
        b.redrive();
        assertThat(b.appliedCount()).isEqualTo(1);
        assertThat(queue.pendingCount()).as("acknowledged by the worker that took it over").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", ada)).isEqualTo(1);
    }

    @Test
    @DisplayName("the list is an inbox: what an older arena pushes there is drained into the stream, at start and at each re-drive")
    void theInboxIsDrainedIntoTheStream() throws Exception {
        long ada = register("ada");
        client.sync().lpush(MatchResultQueue.KEY, MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        // And what the list-reading worker of the previous release had claimed when it stopped.
        client.sync().lpush(MatchResultQueue.processingKeyFor(MatchResultQueue.DEFAULT_WORKER_ID),
                MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 300, 300))));
        consumer.recoverAbandoned();
        assertThat(client.sync().llen(MatchResultQueue.KEY)).isZero();
        assertThat(client.sync().llen(MatchResultQueue.processingKeyFor(MatchResultQueue.DEFAULT_WORKER_ID))).isZero();
        assertThat(queue.depth()).as("both in the stream, for the group").isEqualTo(2);

        client.sync().lpush(MatchResultQueue.KEY, MatchResultCodec.encode(outcomeWith(
                new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 100, 300))));
        assertThat(queue.depth()).as("the inbox counts too").isEqualTo(3);
        consumer.redrive();
        assertThat(client.sync().llen(MatchResultQueue.KEY)).isZero();
        for (int i = 0; i < 3; i++) {
            assertThat(consumer.handle(queue.claim(2.0))).isTrue();
        }
        assertThat(consumer.appliedCount()).isEqualTo(3);
        assertThat(queue.depth()).isZero();
    }

    @Test
    @DisplayName("a pending entry the stream trimmed away before anyone applied it is counted and logged, not hidden")
    void aTrimmedPendingEntryIsCounted() throws Exception {
        long ada = register("ada");
        publish(MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        publish(MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 300, 300))));
        assertThat(queue.claim(2.0)).as("this worker's, in flight").isNotNull();
        MatchResultStream retired = new MatchResultStream(client, "worker-gone", MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
        assertThat(retired.claim(2.0)).as("a retired worker's").isNotNull();
        client.sync().send("XTRIM", MatchResultStream.KEY, "MAXLEN", "0");     // a day of retention passed

        MatchResultConsumer c = new MatchResultConsumer(new MatchResultStream(client, MatchResultQueue.DEFAULT_WORKER_ID, 300),
                new MatchResultRepository(db.dataSource(), AccountLevels::levelFor), leaderboards);
        Thread.sleep(400);
        c.redrive();
        assertThat(c.trimmedUnappliedCount()).as("both: its own, and the retired worker's").isEqualTo(2);
        assertThat(c.appliedCount()).isZero();
        assertThat(queue.pendingCount()).as("no longer pending: nothing left to apply").isZero();
    }

    @Test
    @DisplayName("its own entry trimmed while pending is counted once, by the next re-drive, however briefly it was idle")
    void anOwnTrimmedEntryIsCountedOnce() throws Exception {
        long ada = register("ada");
        publish(MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        assertThat(queue.claim(2.0)).as("in flight").isNotNull();
        assertThat(queue.pendingCount()).as("pending for this worker").isEqualTo(1);
        client.sync().send("XTRIM", MatchResultStream.KEY, "MAXLEN", "0");
        consumer.redrive();                                     // idle for seconds, not a minute
        assertThat(consumer.trimmedUnappliedCount()).isEqualTo(1);
        assertThat(queue.pendingCount()).as("acknowledged: nothing left to apply").isZero();
        consumer.redrive();
        assertThat(consumer.trimmedUnappliedCount()).as("not counted again").isEqualTo(1);
    }

    @Test
    @DisplayName("a stream lost with its group, a store restored empty, is made again and read from its start")
    void aLostGroupIsMadeAgain() throws Exception {
        long ada = register("ada");
        consumer.recoverAbandoned();
        client.sync().send("DEL", MatchResultStream.KEY);
        publish(MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        consumer.redrive();                                     // finds no group, and makes it
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();
        assertThat(consumer.appliedCount()).isEqualTo(1);
        client.sync().send("DEL", MatchResultStream.KEY);
        assertThat(queue.claim(0.2)).as("no group: made again, nothing yet").isNull();
        publish(MatchResultCodec.encode(outcomeWith(new PlayerOutcome(ada, "Ada", 0, 1, 2, 1, 450, 300))));
        assertThat(consumer.handle(queue.claim(2.0))).as("read, without a redrive between").isTrue();
        assertThat(consumer.appliedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("only failures a retry could change are retried")
    void failuresAreClassified() {
        // Permanent: the value, the reference or the constraint is wrong, and stays wrong.
        assertThat(MatchResultConsumer.isPermanent(
                new SQLException("Check constraint violated", "HY000", 3819)))
                .as("a CHECK violation, whose SQLSTATE is the generic HY000").isTrue();
        assertThat(MatchResultConsumer.isPermanent(
                new SQLException("Cannot add or update a child row", "23000", 1452))).isTrue();
        assertThat(MatchResultConsumer.isPermanent(
                new SQLException("BIGINT UNSIGNED value is out of range", "22003", 1690))).isTrue();

        // Transient: the same statement may well succeed in a moment.
        assertThat(MatchResultConsumer.isPermanent(
                new SQLException("Communications link failure", "08S01", 0))).isFalse();
        assertThat(MatchResultConsumer.isPermanent(
                new SQLException("Deadlock found", "40001", 1213))).isFalse();
        assertThat(MatchResultConsumer.isPermanent(
                new SQLException("Lock wait timeout exceeded", "HY000", 1205))).isFalse();
        assertThat(MatchResultConsumer.isPermanent(new SQLException("no state at all")))
                .as("unknown means retry: discarding something recoverable cannot be undone")
                .isFalse();
    }

    @Test
    @DisplayName("a player who barely turned up is not paid for it")
    void tooShortToCount() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        MatchOutcome match = new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_TIMED, 0,
                "arena-1", System.currentTimeMillis() - 10_000, System.currentTimeMillis(),
                List.of(new PlayerOutcome(ada, "Ada", 0, 1, 1, 0, 100, 60),
                        new PlayerOutcome(bob, "Bob", 0, 2, 0, 1, 0, 2)));

        publish(MatchResultCodec.encode(match));
        consumer.handle(queue.claim(2.0));

        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada)).isPositive();
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", bob))
                .as("two seconds in a match earns nothing, or quitting becomes profitable")
                .isZero();
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", bob))
                .as("but they were still there, and the record says so").isEqualTo(1);
        // Nor anything else a stay counts for: a stay of a second, again and again, would earn the pass, the goals
        // and the achievements what playing earns (D-45).
        assertThat(scalar("SELECT matches FROM player_stat WHERE player_id = ?", bob)).as("no match counted").isZero();
        assertThat(scalar("SELECT COALESCE(SUM(points), 0) FROM season_pass WHERE player_id = ?", bob)).as("no pass points")
                .isZero();
        assertThat(scalar("SELECT COUNT(*) FROM player_day WHERE player_id = ?", bob)).as("not an active day").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM player WHERE id = ? AND first_played_on IS NULL", bob))
                .as("nor a first day played").isEqualTo(1);
        assertThat(scalar("SELECT matches FROM player_stat WHERE player_id = ?", ada)).as("the one who played: counted")
                .isEqualTo(1);
    }
}
