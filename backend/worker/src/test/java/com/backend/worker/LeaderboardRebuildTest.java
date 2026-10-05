package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

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
import com.backend.persistence.LeaderboardSource;
import com.backend.persistence.MatchResultRepository;
import com.jredis.client.JRedisClient;
import com.jredis.client.ScoredMember;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boards rebuilt from MySQL are the boards the live path built (05 §8).
 *
 * The live path is the real one - queue, consumer, MySQL, then the store - so "the same as
 * live" is checked against what production would have, not against a second copy of the
 * rebuild's own reasoning.
 */
class LeaderboardRebuildTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private static Database db;
    private static JRedisEmbedded liveServer;
    private static JRedisEmbedded emptyServer;
    private static JRedisClient live;
    private static JRedisClient empty;
    private static AccountRepository accounts;
    private static MatchResultStream queue;

    /** Each result played in a test: when it ended, and the scores in it. */
    private final List<long[]> played = new ArrayList<>();

    /** Display names by player, as a ticket carries them into a result. */
    private final Map<Long, String> names = new HashMap<>();

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        liveServer = JRedisEmbedded.start();
        emptyServer = JRedisEmbedded.start();
        live = liveServer.newClient();
        empty = emptyServer.newClient();
        accounts = new AccountRepository(db.dataSource());
        queue = new MatchResultStream(live, MatchResultQueue.DEFAULT_WORKER_ID, MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
    }

    @AfterAll
    static void tearDown() {
        liveServer.close();
        emptyServer.close();
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
        live.sync().send("FLUSHALL");
        queue.ensureGroup();
        empty.sync().send("FLUSHALL");
        played.clear();
        names.clear();
    }

    @Test
    @DisplayName("rebuilt from MySQL alone, every board that would still exist is the live one")
    void rebuildMatchesLive() throws Exception {
        long a = register("ada");
        long b = register("bob");
        long c = register("cy");
        long d = register("dee");
        long e = register("eve");
        long now = System.currentTimeMillis();
        play(new LeaderboardStore(live), now - HOUR, a, 500, b, 300);
        play(new LeaderboardStore(live), now - 26 * HOUR, a, 800, c, 200);
        // A day still inside the window a rebuild looks at, whose live board has expired all
        // the same: its last score was over three days ago. Skipped, as live would have lost it.
        play(new LeaderboardStore(live), now - 3 * DAY - HOUR, c, 400);
        play(new LeaderboardStore(live), now - 5 * DAY, b, 900);
        play(new LeaderboardStore(live), now - 12 * DAY, d, 1000);
        // Never ranked, and the latest result of its day: a board's expiry runs from its last
        // score, and live never refreshed it for a score of zero, so neither may a rebuild.
        play(new LeaderboardStore(live), now - HOUR / 2, e, 0);

        LeaderboardRebuild.Report report = rebuild(empty, now);

        assertThat(board(empty, Board.ALLTIME.key(Instant.ofEpochMilli(now))))
                .isEqualTo(board(live, Board.ALLTIME.key(Instant.ofEpochMilli(now))))
                .containsOnlyKeys(Long.toString(a), Long.toString(b), Long.toString(c), Long.toString(d));
        assertThat(report.allTime()).isEqualTo(4);
        assertThat(empty.sync().hgetall("lb:name")).isEqualTo(live.sync().hgetall("lb:name"));

        // Which day and week boards the live store would still hold: those whose last score,
        // plus their TTL, is still ahead. The live store here keeps the older ones too,
        // because it was written just now; production's would have expired them.
        Map<String, Long> lastScore = new TreeMap<>();
        for (long[] result : played) {
            boolean scored = false;
            for (int i = 2; i < result.length; i += 2) {
                scored |= result[i] > 0;
            }
            if (!scored) {
                continue;
            }
            for (Board board : List.of(Board.DAILY, Board.WEEKLY)) {
                lastScore.merge(board.key(Instant.ofEpochMilli(result[0])), result[0], Math::max);
            }
        }
        Map<String, Long> expectedTtl = new TreeMap<>();
        lastScore.forEach((key, last) -> {
            long ttl = (key.contains(":day:") ? Board.DAILY : Board.WEEKLY).ttlSeconds() * 1000L;
            if (last + ttl > now) {
                expectedTtl.put(key, (last + ttl - now) / 1000);
            }
        });
        assertThat(report.windows()).extracting(w -> w.substring(0, w.indexOf(' ')))
                .containsExactlyInAnyOrderElementsOf(expectedTtl.keySet());
        for (var entry : expectedTtl.entrySet()) {
            assertThat(board(empty, entry.getKey())).as(entry.getKey())
                    .isEqualTo(board(live, entry.getKey()));
            assertThat(empty.sync().ttl(entry.getKey())).as("expires when the live one would")
                    .isBetween(entry.getValue() - 5, entry.getValue() + 1);
        }
    }

    @Test
    @DisplayName("on a live store it lowers nothing, moves no expiry, and ranks what was never ranked")
    void rebuildOnALiveStoreIsHarmless() throws Exception {
        long a = register("ada");
        long b = register("bob");
        long f = register("fay");
        long now = System.currentTimeMillis();
        play(new LeaderboardStore(live), now - 26 * HOUR, a, 500, b, 300);

        // Applied while the store could not be written to: in MySQL, on no board.
        JRedisEmbedded goneServer = JRedisEmbedded.start();
        JRedisClient gone = goneServer.newClient();
        goneServer.close();
        MatchResultConsumer unreachable = play(new LeaderboardStore(gone), now - 2 * HOUR, f, 700);
        assertThat(unreachable.leaderboardFailureCount()).isEqualTo(1);
        gone.close();

        // A board that knows more than MySQL: whatever put it there, a rebuild must not lower it.
        String allTime = Board.ALLTIME.key(Instant.ofEpochMilli(now));
        live.sync().zadd(allTime, 5000, Long.toString(a));

        Map<String, Map<String, Double>> before = new HashMap<>();
        Map<String, Long> ttlBefore = new HashMap<>();
        for (String key : boardKeys(live)) {
            before.put(key, board(live, key));
            ttlBefore.put(key, live.sync().ttl(key));
        }

        rebuild(live, now);

        for (String key : before.keySet()) {
            Map<String, Double> after = board(live, key);
            Map<String, Double> withoutFay = new TreeMap<>(after);
            withoutFay.remove(Long.toString(f));
            assertThat(withoutFay).as("nothing already on " + key + " changed").isEqualTo(before.get(key));
            assertThat(live.sync().ttl(key)).as("the expiry of " + key + " is its own")
                    .isBetween(ttlBefore.get(key) - 2, ttlBefore.get(key));
        }
        assertThat(board(live, allTime)).containsEntry(Long.toString(a), 5000.0)
                .containsEntry(Long.toString(f), 700.0);
        for (Board board : List.of(Board.DAILY, Board.WEEKLY)) {
            assertThat(board(live, board.key(Instant.ofEpochMilli(now - 2 * HOUR))))
                    .as("the unranked result reached the " + board.apiName() + " board")
                    .containsEntry(Long.toString(f), 700.0);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private long register(String name) throws SQLException {
        long id = accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        names.put(id, name);
        return id;
    }

    /** One result through the real path: queued, applied, ranked on {@code boards}. */
    private MatchResultConsumer play(LeaderboardStore boards, long endedAt, long... playerScores)
            throws Exception {
        List<PlayerOutcome> players = new ArrayList<>();
        for (int i = 0; i < playerScores.length; i += 2) {
            players.add(new PlayerOutcome(playerScores[i], names.get(playerScores[i]), 0, 0, 1, 0,
                    (int) playerScores[i + 1], 120));
        }
        queue.publish(MatchResultCodec.encode(new MatchOutcome(Ulid.generate(),
                MatchOutcome.KIND_OPEN, 0, "arena-1", endedAt - 120_000, endedAt, players)), System.currentTimeMillis());
        MatchResultConsumer consumer = new MatchResultConsumer(queue,
                new MatchResultRepository(db.dataSource(), AccountLevels::levelFor), boards);
        assertThat(consumer.handle(queue.claim(2.0))).isTrue();
        long[] record = new long[playerScores.length + 1];
        record[0] = endedAt;
        System.arraycopy(playerScores, 0, record, 1, playerScores.length);
        played.add(record);
        return consumer;
    }

    private static LeaderboardRebuild.Report rebuild(JRedisClient onto, long now) throws Exception {
        return new LeaderboardRebuild(new LeaderboardSource(db.dataSource()), new LeaderboardStore(onto))
                .run(Instant.ofEpochMilli(now));
    }

    private static Map<String, Double> board(JRedisClient client, String key) {
        Map<String, Double> out = new TreeMap<>();
        for (ScoredMember m : client.sync().zrangeWithScores(key, 0, -1)) {
            out.put(m.member, m.score);
        }
        return out;
    }

    private static List<String> boardKeys(JRedisClient client) {
        List<String> keys = new ArrayList<>();
        for (var reply : client.sync().send("KEYS", "lb:score:*").asList()) {
            keys.add(reply.asString());
        }
        return keys;
    }
}
