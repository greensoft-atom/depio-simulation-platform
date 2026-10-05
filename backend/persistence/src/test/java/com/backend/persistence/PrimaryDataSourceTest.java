package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which server a process writes to when given MySQL's primary and its replica: the writable one
 * with the highest epoch, never one below the highest seen (docs detailed-design/06 §10, D-35).
 */
class PrimaryDataSourceTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    /** The test server, as the URL names it: JDBC_URL may name another than 127.0.0.1:3306 (T-58). */
    private static final String SERVER = URL.replaceFirst("^jdbc:mysql://([^/]+)/.*$", "$1");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    /** Servers by address, each writable or not, at an epoch; any other address does not answer. */
    private static String choose(Map<String, PrimaryDataSource.Probe> servers, AtomicLong seen, String... hosts)
            throws SQLException {
        return PrimaryDataSource.choose(List.of(hosts), host -> {
            PrimaryDataSource.Probe p = servers.get(host);
            if (p == null) {
                throw new SQLException("connection refused: " + host);
            }
            return p;
        }, seen);
    }

    private static PrimaryDataSource.Probe writable(long epoch) {
        return new PrimaryDataSource.Probe(true, epoch);
    }

    private static PrimaryDataSource.Probe readOnly(long epoch) {
        return new PrimaryDataSource.Probe(false, epoch);
    }

    @Test
    @DisplayName("the primary, whichever host is listed first")
    void thePrimaryWhicheverComesFirst() throws Exception {
        AtomicLong seen = new AtomicLong(-1);
        assertThat(choose(Map.of("r:1", readOnly(0), "p:1", writable(0)), seen, "r:1", "p:1")).isEqualTo("p:1");
    }

    @Test
    @DisplayName("after a promotion, the new primary, even with the old one writable again")
    void theHigherEpochWins() throws Exception {
        AtomicLong seen = new AtomicLong(-1);
        assertThat(choose(Map.of("old:1", writable(0), "new:1", writable(1)), seen, "old:1", "new:1"))
                .isEqualTo("new:1");
        assertThat(seen.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("never back to a primary below the highest epoch seen, nor one a replica shows to be stale")
    void neverBackToALowerEpoch() {
        AtomicLong seen = new AtomicLong(1);
        assertThatThrownBy(() -> choose(Map.of("old:1", writable(0)), seen, "old:1", "new:1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("epoch 1");
        assertThat(seen.get()).isEqualTo(1);
        AtomicLong fresh = new AtomicLong(-1);
        assertThatThrownBy(() -> choose(Map.of("stale:1", writable(0), "replica:1", readOnly(1)), fresh,
                "stale:1", "replica:1"))
                .as("a replica at epoch 1 follows a primary at 1: this one, at 0, is stale")
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("a host that does not answer is passed over; none answering is an error naming them")
    void unreachableHosts() throws Exception {
        AtomicLong seen = new AtomicLong(-1);
        assertThat(choose(Map.of("p:1", writable(0)), seen, "gone:1", "p:1")).isEqualTo("p:1");
        assertThatThrownBy(() -> choose(Map.of(), seen, "a:1", "b:1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("a:1").hasMessageContaining("b:1");
    }

    @Test
    @DisplayName("the hosts of a URL, with MySQL's port when none is given")
    void hostsOfAUrl() {
        assertThat(PrimaryDataSource.hosts("jdbc:mysql://a:3306,b:3307/db?x=1")).containsExactly("a:3306", "b:3307");
        assertThat(PrimaryDataSource.hosts("jdbc:mysql://a/db")).containsExactly("a:3306");
        assertThat(PrimaryDataSource.hosts("jdbc:mysql://a:1,b/db")).containsExactly("a:1", "b:3306");
    }

    @Test
    @DisplayName("against the development server: the epoch's table, one row at 0, and a URL naming a dead host too")
    void againstARealServer() throws Exception {
        int dead;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            dead = probe.getLocalPort();
        }
        String both = URL.replace(SERVER, SERVER + ",127.0.0.1:" + dead);
        try (Database single = new Database(URL, USER, PASSWORD, 2)) {
            assertThat(single.dataSource().isWrapperFor(PrimaryDataSource.class)).as("one host: as before").isFalse();
        }
        try (Database db = new Database(both, USER, PASSWORD, 2)) {
            assertThat(db.dataSource().isWrapperFor(PrimaryDataSource.class)).as("two: the primary's source").isTrue();
            db.resetForTests();                                 // from nothing: no epoch table yet to ask
        }
        try (Database db = new Database(both, USER, PASSWORD, 2);
             Connection c = db.dataSource().getConnection();
             Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT id, epoch FROM ha_epoch")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getInt(1)).isEqualTo(1);
                assertThat(r.getLong(2)).isZero();
                assertThat(r.next()).as("one row").isFalse();
            }
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO ha_epoch (id, epoch) VALUES (2, 5)"))
                    .as("a second row is refused").isInstanceOf(SQLException.class);
        }
        // A database that has no epoch table yet, as before its first migration: its epoch is 0,
        // and a fresh pool's first connection is not refused for the want of one.
        try (Database single = new Database(URL, USER, PASSWORD, 1);
             Connection c = single.dataSource().getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("DROP TABLE ha_epoch");
        }
        try (Database db = new Database(both, USER, PASSWORD, 1);
             Connection c = db.dataSource().getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        } finally {
            try (Database single = new Database(URL, USER, PASSWORD, 1)) {
                single.resetForTests();
            }
        }
    }

    @Test
    @DisplayName("a replica's lag is the primary's last heartbeat less its own, both the primary's clock; one not read has none (D-58)")
    void lagsByTheHeartbeat() {
        java.time.Instant t = java.time.Instant.parse("2026-10-02T10:00:10.500Z");
        Map<String, PrimaryDataSource.Heartbeat> read = new java.util.LinkedHashMap<>();
        read.put("c:3306", new PrimaryDataSource.Heartbeat(false, t.minusMillis(3_200)));
        read.put("a:3306", new PrimaryDataSource.Heartbeat(true, t));
        read.put("b:3306", null);
        Map<String, Double> lags = PrimaryDataSource.lags(read);
        assertThat(lags).as("every host but the primary").containsOnlyKeys("c:3306", "b:3306");
        assertThat(lags.get("c:3306")).isEqualTo(3.2, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(lags.get("b:3306")).as("not read").isNaN();

        read.put("d:3306", new PrimaryDataSource.Heartbeat(true, t.minusSeconds(60)));
        assertThat(PrimaryDataSource.lags(read)).as("an old primary still writable, measured against the newest")
                .containsEntry("d:3306", 60.0).doesNotContainKey("a:3306");
        read.remove("d:3306");
        read.put("a:3306", null);
        assertThat(PrimaryDataSource.lags(read).values()).as("no primary read: nothing to measure against")
                .hasSize(3).allMatch(d -> d.isNaN());
    }

    @Test
    @DisplayName("against the development server: the pool stamps the heartbeat, and a dead host's lag is not known (D-58)")
    void heartbeatAgainstARealServer() throws Exception {
        int dead;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            dead = probe.getLocalPort();
        }
        String both = URL.replace(SERVER, SERVER + ",127.0.0.1:" + dead);
        try (Database db = new Database(both, USER, PASSWORD, 2)) {
            db.resetForTests();
            java.sql.Timestamp before = stamp(db);
            Thread.sleep(20);
            db.heartbeat();
            assertThat(stamp(db)).as("stamped now").isAfter(before);
            Map<String, Double> lags = db.replicaLags();
            assertThat(lags).containsOnlyKeys("127.0.0.1:" + dead);
            assertThat(lags.get("127.0.0.1:" + dead)).isNaN();
        }
        try (Database single = new Database(URL, USER, PASSWORD, 1)) {
            assertThat(single.replicaLags()).as("one host: no replica to measure").isEmpty();
        }
    }

    private static java.sql.Timestamp stamp(Database db) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT at FROM ha_heartbeat WHERE id = 1")) {
            r.next();
            return r.getTimestamp(1);
        }
    }
}
