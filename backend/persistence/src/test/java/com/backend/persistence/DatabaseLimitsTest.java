package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Every MySQL call has a time limit (06 §7, O-9), against a real MySQL behind a proxy that can fall silent. */
class DatabaseLimitsTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    /** The test server, as the URL names it: JDBC_URL may name another than 127.0.0.1:3306 (T-58). */
    private static final String SERVER = URL.replaceFirst("^jdbc:mysql://([^/]+)/.*$", "$1");
    private static final int SERVER_PORT = Integer.parseInt(SERVER.substring(SERVER.lastIndexOf(':') + 1));
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Database.Limits SHORT = new Database.Limits(1_000, 1_000, 500, 20);

    /**
     * As in a process whose one pool is the primary's source: a pool on one host here sets the
     * driver manager's login timeout for the whole JVM, which the driver honours, and which would
     * bound a probe whatever its own limits.
     */
    private static void asOnlyThePrimarysSource() {
        java.sql.DriverManager.setLoginTimeout(0);
    }

    private static String through(SilentProxy proxy) {
        return URL.replace(SERVER, "127.0.0.1:" + proxy.port());
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // a blocked read ignores an interrupt
    @DisplayName("a statement to a server that has fallen silent fails at the socket's limit, not TCP's hours")
    void aSilentServerIsGivenUp() throws Exception {
        try (SilentProxy proxy = new SilentProxy(SERVER_PORT, false);
             Database db = new Database(through(proxy), USER, PASSWORD, 1, SHORT);
             Connection c = db.dataSource().getConnection();
             Statement s = c.createStatement()) {
            s.executeQuery("SELECT 1").close();
            proxy.silence();
            long start = System.nanoTime();
            assertThatThrownBy(() -> s.executeQuery("SELECT 1")).isInstanceOf(SQLException.class);
            assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(5_000);
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // a blocked read ignores an interrupt
    @DisplayName("a frozen host, which accepts and never answers, is left out of the primary's probe at its limit")
    void aFrozenHostIsLeftOut() throws Exception {
        try (SilentProxy frozen = new SilentProxy(SERVER_PORT, true)) {
            String both = URL.replace(SERVER, "127.0.0.1:" + frozen.port() + "," + SERVER);
            // The pool's own limits long: only the probe's short ones leave the host out in time. Timed
            // from before the pool is made, which opens its first connection, and so probes, at once.
            asOnlyThePrimarysSource();
            long start = System.nanoTime();
            try (Database db = new Database(both, USER, PASSWORD, 1, new Database.Limits(20_000, 20_000, 500, 20))) {
                try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
                    assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(5_000);
                    s.executeQuery("SELECT SLEEP(1)").close();          // the pool's limit, not the probe's
                }
            }
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // a blocked read ignores an interrupt
    @DisplayName("a host that never answers TCP, its machine gone, is given up at the connect limit: by the pool, and by the probe")
    void aHostThatNeverAnswersIsGivenUp() throws Exception {
        String gone = URL.replace(SERVER, "192.0.2.1:3306");          // reserved, never routed
        long start = System.nanoTime();
        assertThatThrownBy(() -> new Database(gone, USER, PASSWORD, 1, SHORT).close()).as("the pool's first connection")
                .isInstanceOf(RuntimeException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(5_000);
        String both = URL.replace(SERVER, "192.0.2.1:3306," + SERVER);
        asOnlyThePrimarysSource();
        start = System.nanoTime();                                              // the pool probes as it is made
        try (Database db = new Database(both, USER, PASSWORD, 1, new Database.Limits(20_000, 20_000, 500, 20))) {
            try (Connection c = db.dataSource().getConnection()) {
                assertThat(c.isValid(2)).isTrue();
            }
            assertThat((System.nanoTime() - start) / 1_000_000).as("ms, the probe's limit").isLessThan(5_000);
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // a blocked read ignores an interrupt
    @DisplayName("a lock wait is told as 1205, which Tx retries, before the socket gives up; migrations are not cut")
    void lockWaitsAndMigrations() throws Exception {
        try (Database db = new Database(URL, USER, PASSWORD, 1, SHORT)) {
            try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT @@session.innodb_lock_wait_timeout")) {
                r.next();
                assertThat(r.getInt(1)).isEqualTo(20);
                assertThatThrownBy(() -> s.executeQuery("SELECT SLEEP(2)")).as("a pooled statement is cut")
                        .isInstanceOf(SQLException.class);
            }
            try (var migrations = db.migrationDataSource(); Connection c = migrations.getConnection();
                 Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT SLEEP(2)")) {
                assertThat(r.next()).as("a migration's is not").isTrue();
            }
            db.clean();                                       // empty, so a first migration needs no baseline
            try {
                db.migrate("classpath:db/slow");               // two seconds, against a limit of one
            } finally {
                db.resetForTests();
            }
        }
        String both = URL.replace(SERVER, SERVER + "," + SERVER);
        try (Database db = new Database(both, USER, PASSWORD, 1, SHORT)) {
            try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeQuery("SELECT SLEEP(2)")).as("through the primary's source too")
                        .isInstanceOf(SQLException.class);
            }
            try (var migrations = db.migrationDataSource(); Connection c = migrations.getConnection();
                 Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT SLEEP(2)")) {
                assertThat(r.next()).as("and a migration's is not").isTrue();
            }
        }
    }
}
