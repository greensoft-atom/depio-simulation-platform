package com.backend.account.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import com.backend.account.token.TokenIssuer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HousekeepingTest {

    private static Database db;
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @BeforeAll
    static void setUp() {
        db = TestDatabase.open();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() throws Exception {
        TestDatabase.empty(db);
    }

    @Test
    @DisplayName("a token a day past its end or its revocation, a window an hour old and an event a week taken: purged")
    void purged() throws Exception {
        RefreshTokens tokens = new RefreshTokens();
        Outbox outbox = new Outbox(db.dataSource());
        String fresh;
        try (Connection c = db.dataSource().getConnection()) {
            long ada = new Accounts().create(c, "ada", new byte[] {1}, TokenIssuer.randomId(), NOW).id();
            tokens.issue(c, ada, NOW, NOW.minus(RefreshTokens.LIFETIME).minus(Duration.ofDays(2)));   // ended 2 days ago
            tokens.issue(c, ada, NOW, NOW.minus(RefreshTokens.LIFETIME).plusSeconds(1800));           // ends in half an hour
            tokens.issue(c, ada, NOW, NOW.minus(RefreshTokens.LIFETIME).minus(Duration.ofHours(12)));  // ended 12 hours ago: in the margin
            String revoked = tokens.issue(c, ada, NOW, NOW.minus(Duration.ofDays(3)));
            tokens.revokeFamily(c, revoked, NOW.minus(Duration.ofDays(2)));
            fresh = tokens.issue(c, ada, NOW, NOW);
            outbox.add(c, "jti-000000000000000001", "deleted", "old", NOW.minus(Duration.ofDays(9)));
            outbox.add(c, "jti-000000000000000002", "deleted", "recent", NOW.minus(Duration.ofDays(9)));
            outbox.add(c, "jti-000000000000000003", "deleted", "untaken", NOW.minus(Duration.ofDays(30)));
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE event_outbox SET taken_at = '2026-11-18 10:00:00' WHERE token = 'old'");
                s.executeUpdate("UPDATE event_outbox SET taken_at = '2026-11-25 10:00:00' WHERE token = 'recent'");
                s.executeUpdate("INSERT INTO attempt VALUES ('a:1', " + (NOW.getEpochSecond() - 7200) + ", 3), ('a:1', "
                        + (NOW.getEpochSecond() - 60) + ", 3)");
            }
        }
        Housekeeping.Purged p = new Housekeeping(db.dataSource()).run(NOW);
        assertThat(p.tokens()).isEqualTo(2);
        assertThat(p.windows()).isEqualTo(1);
        assertThat(p.events()).isEqualTo(1);
        try (Connection c = db.dataSource().getConnection()) {
            assertThat(count(c, "refresh_token")).as("a day's margin: the one ended 12 hours ago kept").isEqualTo(3);
            assertThat(count(c, "attempt")).isEqualTo(1);
            assertThat(count(c, "event_outbox")).as("an untaken event is kept however old").isEqualTo(2);
            assertThat(tokens.rotate(c, fresh, NOW).outcome()).isEqualTo(RefreshTokens.Outcome.ROTATED);
        }
    }

    private static int count(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table); ResultSet r = ps.executeQuery()) {
            r.next();
            return r.getInt(1);
        }
    }
}
