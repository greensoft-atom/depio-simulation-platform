package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;

import com.backend.persistence.MatchResultRepository.MatchResult;
import com.backend.persistence.MatchResultRepository.PlayerResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A DATETIME carries no zone, so whoever writes one decides what it means. The driver used to
 * write the process's own local time, which made the stored value depend on the machine: two
 * machines in different zones disagreed about when a match ended, and in a zone with summer
 * time one hour a year could not be told apart from the next.
 */
class StoredTimesTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    @Test
    @DisplayName("times are stored as UTC, whatever zone the process runs in")
    void storedAsUtc() throws Exception {
        TimeZone original = TimeZone.getDefault();
        // A zone with summer time and a large offset, so a local write cannot pass for UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Database db = new Database(URL, USER, PASSWORD, 2);
        try {
            db.resetForTests();
            long player = createPlayer(db);
            Instant ended = LocalDate.now(ZoneOffset.UTC).minusDays(2).atTime(23, 30)
                    .toInstant(ZoneOffset.UTC);
            new MatchResultRepository(db.dataSource(), xp -> 1).apply(new MatchResult(
                    "01JBZONE000000000000000001", 0, 0, "arena-1",
                    ended.toEpochMilli() - 60_000, ended.toEpochMilli(),
                    List.of(new PlayerResult(player, 0, 0, 1, 0, 500, 10, 0, 0, false, 60))));

            try (Connection c = db.dataSource().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT DATE_FORMAT(ended_at, '%Y-%m-%d %H:%i'), ended_at FROM matches")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).as("the value MySQL holds")
                            .isEqualTo(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                    .withZone(ZoneOffset.UTC).format(ended));
                    Timestamp back = rs.getTimestamp(2);
                    assertThat(back.toInstant()).as("and read back, the same instant").isEqualTo(ended);
                }
            }
        } finally {
            db.close();
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("times MySQL fills in itself are UTC too, whatever zone the server runs in")
    void serverTimesAreUtc() throws Exception {
        // CURRENT_TIMESTAMP is the session's zone, not the driver's: a ledger row stamped by
        // its default came out two hours after the match it paid for, on a server in CEST.
        Database db = new Database(URL, USER, PASSWORD, 2);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), NOW(3))");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).as("the session's NOW() against UTC, in seconds").isZero();
        } finally {
            db.close();
        }
    }

    private static long createPlayer(Database db) throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (username, password_hash) VALUES ('zoned', x'01')",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player (id, public_code, display_name) VALUES (?, 'P00000000001', 'zoned')")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return id;
        }
    }
}
