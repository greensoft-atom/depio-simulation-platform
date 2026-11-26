package com.backend.account.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;

import com.backend.account.token.TokenIssuer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    private static Database db;
    private final RefreshTokens tokens = new RefreshTokens();
    private static final Instant LOGIN = Instant.parse("2026-11-26T10:00:00Z");
    private long ada;
    private long bob;

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
        try (Connection c = db.dataSource().getConnection()) {
            Accounts accounts = new Accounts();
            ada = accounts.create(c, "ada", new byte[] {1}, TokenIssuer.randomId(), LOGIN).id();
            bob = accounts.create(c, "bob", new byte[] {1}, TokenIssuer.randomId(), LOGIN).id();
        }
    }

    @Test
    @DisplayName("a token is 32 random bytes in base64url, kept as its SHA-256 alone")
    void keptByItsHash() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            String token = tokens.issue(c, ada, LOGIN, LOGIN);
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ? AND account_id = ?")) {
                ps.setBytes(1, hash);
                ps.setLong(2, ada);
                try (ResultSet r = ps.executeQuery()) {
                    r.next();
                    assertThat(r.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    @DisplayName("a refresh spends the token and gives the next, carrying the login's auth_time")
    void rotated() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            String first = tokens.issue(c, ada, LOGIN.minusSeconds(60), LOGIN);
            RefreshTokens.Rotation r = tokens.rotate(c, first, LOGIN.plusSeconds(600));
            assertThat(r.outcome()).isEqualTo(RefreshTokens.Outcome.ROTATED);
            assertThat(r.accountId()).isEqualTo(ada);
            assertThat(r.token()).hasSize(43).isNotEqualTo(first);
            assertThat(r.authTime()).isEqualTo(LOGIN.minusSeconds(60));
            assertThat(tokens.rotate(c, r.token(), LOGIN.plusSeconds(700)).outcome()).isEqualTo(RefreshTokens.Outcome.ROTATED);
        }
    }

    @Test
    @DisplayName("a spent token again within 30 s is taken (an app's retry); after, its whole family is revoked")
    void reused() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            String first = tokens.issue(c, ada, LOGIN, LOGIN);
            RefreshTokens.Rotation once = tokens.rotate(c, first, LOGIN.plusSeconds(10));
            RefreshTokens.Rotation retry = tokens.rotate(c, first, LOGIN.plusSeconds(10).plus(RefreshTokens.OVERLAP));
            assertThat(retry.outcome()).as("at the overlap's end, still taken").isEqualTo(RefreshTokens.Outcome.ROTATED);
            assertThat(tokens.rotate(c, first, LOGIN.plusSeconds(41)).outcome()).isEqualTo(RefreshTokens.Outcome.REUSED);
            assertThat(tokens.rotate(c, once.token(), LOGIN.plusSeconds(42)).outcome()).isEqualTo(RefreshTokens.Outcome.REVOKED);
            assertThat(tokens.rotate(c, retry.token(), LOGIN.plusSeconds(42)).outcome()).isEqualTo(RefreshTokens.Outcome.REVOKED);
        }
    }

    @Test
    @DisplayName("a token lives 60 days, its family a year from the login: no refresh past either")
    void lifetimes() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            String t = tokens.issue(c, ada, LOGIN, LOGIN);
            assertThat(tokens.rotate(c, t, LOGIN.plus(RefreshTokens.LIFETIME)).outcome()).isEqualTo(RefreshTokens.Outcome.EXPIRED);
            Instant at = LOGIN;
            String next = tokens.issue(c, ada, LOGIN, LOGIN);
            for (int i = 0; i < 7; i++) {             // every 50 days: 350 days on
                at = at.plus(Duration.ofDays(50));
                RefreshTokens.Rotation r = tokens.rotate(c, next, at);
                assertThat(r.outcome()).isEqualTo(RefreshTokens.Outcome.ROTATED);
                next = r.token();
            }
            assertThat(tokens.rotate(c, next, LOGIN.plus(RefreshTokens.FAMILY_LIFETIME)).outcome())
                    .as("15 days after its issue, but the family's year is over").isEqualTo(RefreshTokens.Outcome.EXPIRED);
        }
    }

    @Test
    @DisplayName("a logout revokes its family alone; a password's change or a deletion every family of the account")
    void revoked() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            String phone = tokens.issue(c, ada, LOGIN, LOGIN);
            String tablet = tokens.issue(c, ada, LOGIN, LOGIN);
            String other = tokens.issue(c, bob, LOGIN, LOGIN);
            tokens.revokeFamily(c, phone, LOGIN.plusSeconds(1));
            tokens.revokeFamily(c, "no-such-token", LOGIN.plusSeconds(1));
            assertThat(tokens.rotate(c, phone, LOGIN.plusSeconds(2)).outcome()).isEqualTo(RefreshTokens.Outcome.REVOKED);
            RefreshTokens.Rotation t = tokens.rotate(c, tablet, LOGIN.plusSeconds(2));
            assertThat(t.outcome()).isEqualTo(RefreshTokens.Outcome.ROTATED);
            assertThat(tokens.revokeAll(c, ada, LOGIN.plusSeconds(3))).as("the tablet's two; the phone's was already").isEqualTo(2);
            assertThat(tokens.rotate(c, t.token(), LOGIN.plusSeconds(4)).outcome()).isEqualTo(RefreshTokens.Outcome.REVOKED);
            assertThat(tokens.rotate(c, other, LOGIN.plusSeconds(4)).outcome()).as("another account's").isEqualTo(RefreshTokens.Outcome.ROTATED);
            assertThat(tokens.rotate(c, "not-one-of-ours", LOGIN).outcome()).isEqualTo(RefreshTokens.Outcome.UNKNOWN);
            assertThat(tokens.rotate(c, null, LOGIN).outcome()).isEqualTo(RefreshTokens.Outcome.UNKNOWN);
        }
    }
}
