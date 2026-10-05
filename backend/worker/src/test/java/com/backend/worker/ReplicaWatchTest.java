package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

import com.backend.common.Metrics;
import com.backend.persistence.Database;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The replicas' health (docs architecture/02-availability.md §7, D-58), against MySQL and a store. */
class ReplicaWatchTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    /** The test server, as the URL names it: JDBC_URL may name another than 127.0.0.1:3306 (T-58). */
    private static final String SERVER = URL.replaceFirst("^jdbc:mysql://([^/]+)/.*$", "$1");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "backend-dev-password");

    @Test
    @Timeout(30)
    @DisplayName("the heartbeat stamped every second, a replica not read reported so, and the stores' replicas counted (D-58)")
    void watches() throws Exception {
        int dead;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            dead = probe.getLocalPort();
        }
        String both = URL.replace(SERVER, SERVER + ",127.0.0.1:" + dead);
        try (Database db = new Database(both, USER, PASSWORD, 2);
             JRedisEmbedded server = JRedisEmbedded.start();
             JRedisClient store = server.newClient()) {
            db.resetForTests();
            Timestamp before = stamp(db);
            try (ReplicaWatch watch = new ReplicaWatch(db)) {
                watch.start();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (!stamp(db).after(before) || watch.lags().isEmpty()) {
                    assertThat(System.nanoTime()).as("stamped, and read").isLessThan(deadline);
                    Thread.sleep(100);
                }
                Timestamp once = stamp(db);
                Thread.sleep(ReplicaWatch.BEAT_MILLIS * 2);
                assertThat(stamp(db)).as("and again a second on").isAfter(once);

                Metrics m = new Metrics();
                watch.registerMetrics(m, store, store);
                String text = m.render();
                assertThat(text).contains("backend_mysql_replica_up{host=\"127.0.0.1:" + dead + "\"} 0");
                assertThat(text).contains("backend_mysql_replica_lag_seconds{host=\"127.0.0.1:" + dead + "\"} NaN");
                assertThat(text).as("the primary is not its own replica").doesNotContain("host=\"" + SERVER + "\"");
                assertThat(text).contains("backend_store_replicas 0");
                assertThat(text).as("one store: not counted twice").doesNotContain("backend_events_store_replicas");
            }
        }
    }

    private static Timestamp stamp(Database db) throws Exception {
        try (Connection c = db.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT at FROM ha_heartbeat WHERE id = 1")) {
            r.next();
            return r.getTimestamp(1);
        }
    }
}
