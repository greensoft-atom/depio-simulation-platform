package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the backups' steps recorded (docs 06 §10, D-71): each kind's last success, and whether its last run failed. */
class BackupRunRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant T = Instant.parse("2026-10-04T12:00:00Z");

    private static Database db;
    private static BackupRunRepository runs;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        runs = new BackupRunRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    /** A step's row, as the scripts write it. */
    private static void ran(int kind, Instant finished, boolean ok) throws SQLException {
        ran(kind, finished, ok, null);
    }

    private static void ran(int kind, Instant finished, boolean ok, Double seconds) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO backup_run (kind, started_at, finished_at, ok, detail, seconds)"
                     + " VALUES (?, ?, ?, ?, 'test', ?)")) {
            ps.setInt(1, kind);
            ps.setTimestamp(2, Timestamp.from(finished.minusSeconds(60)));
            ps.setTimestamp(3, Timestamp.from(finished));
            ps.setBoolean(4, ok);
            ps.setObject(5, seconds);
            ps.executeUpdate();
        }
    }

    private static long count() throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM backup_run");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("each kind: when it last succeeded, and whether its last run failed; a kind never run is neither")
    void latest() throws SQLException {
        ran(BackupRunRepository.DUMP, T.minus(Duration.ofDays(2)), true);
        ran(BackupRunRepository.DUMP, T.minus(Duration.ofDays(1)), true);
        ran(BackupRunRepository.DUMP, T.minus(Duration.ofHours(1)), false);
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(3)), false);
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(2)), true);

        var latest = runs.latest();
        assertThat(latest.get(BackupRunRepository.DUMP))
                .isEqualTo(new BackupRunRepository.Latest(T.minus(Duration.ofDays(1)), true, null));
        assertThat(latest.get(BackupRunRepository.PROOF)).as("a failure, then a success")
                .isEqualTo(new BackupRunRepository.Latest(T.minus(Duration.ofDays(2)), false, null));
        assertThat(latest).doesNotContainKey(BackupRunRepository.OFFSITE);

        ran(BackupRunRepository.OFFSITE, T, false);
        assertThat(runs.latest().get(BackupRunRepository.OFFSITE)).as("failed, never succeeded")
                .isEqualTo(new BackupRunRepository.Latest(null, true, null));
    }

    @Test
    @DisplayName("the last successful proof's restore time, what the restore objective is watched by; a failed one's is not (D-72)")
    void restoreSeconds() throws SQLException {
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(14)), true, 900.0);
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(7)), true, 1_250.5);
        ran(BackupRunRepository.PROOF, T, false, 30.0);
        assertThat(runs.latest().get(BackupRunRepository.PROOF))
                .isEqualTo(new BackupRunRepository.Latest(T.minus(Duration.ofDays(7)), true, 1_250.5));
    }

    @Test
    @DisplayName("rows past their 90 days are deleted, a batch at a time")
    void purge() throws SQLException {
        ran(BackupRunRepository.DUMP, T.minus(Duration.ofDays(100)), true);
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(91)), true);
        ran(BackupRunRepository.DUMP, T.minus(BackupRunRepository.KEPT).plus(1, ChronoUnit.SECONDS), true);
        ran(BackupRunRepository.DUMP, T.minus(BackupRunRepository.KEPT), true);                     // the 90th day exactly
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(1)), true);    // so the old proof is not its kind's newest
        assertThat(runs.purgeBefore(T.minus(BackupRunRepository.KEPT), 1)).isEqualTo(2);
        assertThat(count()).as("the three inside the 90 days").isEqualTo(3);
    }

    @Test
    @DisplayName("each kind's newest success is kept however old, and a failure after it is not one (D-48)")
    void theNewestSuccessIsKept() throws SQLException {
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(120)), true);
        ran(BackupRunRepository.PROOF, T.minus(Duration.ofDays(100)), false);
        ran(BackupRunRepository.DUMP, T.minus(Duration.ofDays(100)), true);
        ran(BackupRunRepository.DUMP, T.minus(Duration.ofDays(95)), true);
        assertThat(runs.purgeBefore(T.minus(BackupRunRepository.KEPT), 10)).isEqualTo(2);
        assertThat(runs.latest().get(BackupRunRepository.PROOF).succeeded()).isEqualTo(T.minus(Duration.ofDays(120)));
        assertThat(runs.latest().get(BackupRunRepository.DUMP).succeeded()).isEqualTo(T.minus(Duration.ofDays(95)));
    }
}
