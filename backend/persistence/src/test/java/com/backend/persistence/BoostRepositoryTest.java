package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.backend.persistence.BoostRepository.Outcome;
import com.backend.persistence.BoostRepository.Running;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Boosts (docs 04 §8, 06 §3, D-38), against a real MySQL as the rest of this module. */
class BoostRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final int XP = 0;
    private static final int COINS = 1;
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static Database db;
    private static BoostRepository boosts;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        boosts = new BoostRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void freshSchema() {
        db.resetForTests();
    }

    private static long createPlayer(String name) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (username, password_hash) VALUES (?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setBytes(2, new byte[] {1, 2, 3});
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player (id, public_code, display_name) VALUES (?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, String.format("P%011d", id));
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return id;
        }
    }

    private static void hold(long playerId, String itemId, int qty) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO inventory_item (player_id, item_id, qty) VALUES (?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE qty = VALUES(qty)")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemId);
            ps.setInt(3, qty);
            ps.executeUpdate();
        }
    }

    private static int held(long playerId, String itemId) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT qty FROM inventory_item WHERE player_id = ? AND item_id = ?")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Test
    @DisplayName("a boost held is activated once per key: one item taken, however often the key is sent")
    void oncePerKey() throws Exception {
        long ada = createPlayer("ada");
        hold(ada, "boost_xp_hour", 2);
        assertThat(boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "key-1", T)).isEqualTo(Outcome.ACTIVATED);
        assertThat(boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "key-1", T.plusSeconds(5)))
                .as("a retry").isEqualTo(Outcome.ALREADY_ACTIVATED);
        assertThat(held(ada, "boost_xp_hour")).isEqualTo(1);
        assertThat(boosts.running(ada, T)).containsExactly(
                new Running(XP, "boost_xp_hour", 100, T.plus(60, ChronoUnit.MINUTES)));

        long bob = createPlayer("bob");
        hold(bob, "boost_xp_hour", 1);
        assertThat(boosts.activate(bob, "boost_xp_hour", XP, 100, 60, "key-1", T))
                .as("a key is the player's own").isEqualTo(Outcome.ACTIVATED);
    }

    @Test
    @DisplayName("the same boost again adds its minutes; another of that kind waits until it ends; each kind runs apart")
    void oneAKind() throws Exception {
        long ada = createPlayer("ada");
        hold(ada, "boost_xp_hour", 2);
        hold(ada, "boost_xp_half", 1);
        hold(ada, "boost_coins_hour", 1);
        boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "k1", T);
        assertThat(boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "k2", T.plus(10, ChronoUnit.MINUTES)))
                .isEqualTo(Outcome.ACTIVATED);
        assertThat(boosts.running(ada, T)).as("an hour more, from when it would have ended")
                .containsExactly(new Running(XP, "boost_xp_hour", 100, T.plus(120, ChronoUnit.MINUTES)));

        assertThat(boosts.activate(ada, "boost_xp_half", XP, 50, 30, "k3", T.plus(20, ChronoUnit.MINUTES)))
                .isEqualTo(Outcome.OTHER_RUNNING);
        assertThat(held(ada, "boost_xp_half")).as("nothing taken").isEqualTo(1);
        assertThat(boosts.activate(ada, "boost_coins_hour", COINS, 100, 60, "k4", T.plus(20, ChronoUnit.MINUTES)))
                .isEqualTo(Outcome.ACTIVATED);
        assertThat(boosts.running(ada, T.plus(20, ChronoUnit.MINUTES))).hasSize(2);

        Instant later = T.plus(120, ChronoUnit.MINUTES);           // the first has ended, exactly
        assertThat(boosts.activate(ada, "boost_xp_half", XP, 50, 30, "k3", later)).isEqualTo(Outcome.ACTIVATED);
        assertThat(boosts.running(ada, later)).contains(new Running(XP, "boost_xp_half", 50, later.plus(30, ChronoUnit.MINUTES)));
        assertThat(boosts.percentsAt(List.of(ada), T.plus(90, ChronoUnit.MINUTES)).get(ada)[XP])
                .as("a match that ended during the first still counts the first, applied however late").isEqualTo(100);
        assertThat(boosts.percentsAt(List.of(ada), T.plus(130, ChronoUnit.MINUTES)).get(ada)[XP])
                .as("and one that ended in the second, the second").isEqualTo(50);
    }

    @Test
    @DisplayName("a boost not held is refused, nothing written, the key still free")
    void notHeld() throws Exception {
        long ada = createPlayer("ada");
        assertThat(boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "k1", T)).isEqualTo(Outcome.NOT_OWNED);
        assertThat(boosts.running(ada, T)).isEmpty();
        hold(ada, "boost_xp_hour", 0);
        assertThat(boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "k1", T)).isEqualTo(Outcome.NOT_OWNED);
        hold(ada, "boost_xp_hour", 1);
        assertThat(boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "k1", T)).isEqualTo(Outcome.ACTIVATED);
    }

    @Test
    @DisplayName("a match counts if it ended while the boost ran: from its start, inclusive, to its end, exclusive")
    void percentsAtAMatchsEnd() throws Exception {
        long ada = createPlayer("ada");
        long bob = createPlayer("bob");
        hold(ada, "boost_xp_hour", 1);
        hold(ada, "boost_coins_hour", 1);
        boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "k1", T);
        boosts.activate(ada, "boost_coins_hour", COINS, 40, 60, "k2", T.plus(30, ChronoUnit.MINUTES));

        assertThat(boosts.percentsAt(List.of(ada, bob), T.minusMillis(1))).isEmpty();
        assertThat(boosts.percentsAt(List.of(ada, bob), T).get(ada)).containsExactly(100, 0);
        assertThat(boosts.percentsAt(List.of(ada, bob), T.plus(45, ChronoUnit.MINUTES)).get(ada)).containsExactly(100, 40);
        assertThat(boosts.percentsAt(List.of(ada, bob), T.plus(60, ChronoUnit.MINUTES)).get(ada))
                .as("the xp boost's end is not in it").containsExactly(0, 40);
        assertThat(boosts.percentsAt(List.of(bob), T.plus(45, ChronoUnit.MINUTES))).as("no boosts, no entry").isEmpty();
    }

    @Test
    @DisplayName("two taps at once, one boost held: one takes it, the other is told there is none")
    void twoAtOnce() throws Exception {
        long ada = createPlayer("ada");
        hold(ada, "boost_xp_hour", 1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> {
                go.await();
                return boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "tap-a", T);
            });
            Future<Outcome> b = pool.submit(() -> {
                go.await();
                return boosts.activate(ada, "boost_xp_hour", XP, 100, 60, "tap-b", T);
            });
            go.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(Outcome.ACTIVATED, Outcome.NOT_OWNED);
            assertThat(held(ada, "boost_xp_hour")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an ended boost is deleted 31 days after its end, a day past how late a result may be; a key 30 days after its use (D-38)")
    void endedBoostsAndOldKeysAreDeleted() throws Exception {
        assertThat(BoostRepository.KEPT.toMillis()).as("longer than a result may be late, so a late one finds its boost")
                .isGreaterThan(MatchResultRepository.RESULT_ACCEPT_WINDOW_MILLIS);
        long ada = createPlayer("ada");
        Instant ended = T.minus(BoostRepository.KEPT);
        boost(ada, XP, T.minus(40, ChronoUnit.DAYS), ended.minusSeconds(60));
        boost(ada, COINS, T.minus(40, ChronoUnit.DAYS), ended.minusSeconds(1));
        boost(ada, XP, T.minus(31, ChronoUnit.DAYS), ended.plusSeconds(60));
        boost(ada, COINS, T.minusSeconds(60), T.plusSeconds(3_600));
        Instant used = T.minus(BoostRepository.KEYS_KEPT);
        key(ada, "act:" + ada + ":old", used.minusSeconds(60));
        key(ada, "act:" + ada + ":new", used.plusSeconds(60));

        assertThat(boosts.purge(T, 1)).as("two boosts and a key, one at a time").isEqualTo(3);
        assertThat(rows("SELECT COUNT(*) FROM boost")).as("one ended within the 31 days, one running").isEqualTo(2);
        assertThat(rows("SELECT COUNT(*) FROM boost_activation WHERE idem_key LIKE '%:new'")).isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM boost_activation")).isEqualTo(1);
        assertThat(boosts.purge(T, 1)).as("and nothing the second time").isZero();
    }

    /** A boost's run, as stored. */
    private static void boost(long playerId, int kind, Instant started, Instant ends) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO boost (player_id, kind, item_id, percent, started_at,"
                     + " ends_at) VALUES (?, ?, 'boost_xp_hour', 100, ?, ?)")) {
            ps.setLong(1, playerId);
            ps.setInt(2, kind);
            ps.setTimestamp(3, java.sql.Timestamp.from(started));
            ps.setTimestamp(4, java.sql.Timestamp.from(ends));
            ps.executeUpdate();
        }
    }

    /** A key used at {@code at}. */
    private static void key(long playerId, String key, Instant at) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO boost_activation (idem_key, player_id, item_id, at) VALUES (?, ?, 'boost_xp_hour', ?)")) {
            ps.setString(1, key);
            ps.setLong(2, playerId);
            ps.setTimestamp(3, java.sql.Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    private static int rows(String sql) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
