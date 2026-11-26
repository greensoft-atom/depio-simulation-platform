package com.backend.account.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refresh tokens (10 §3.4): 32 random bytes in base64url, kept by their SHA-256 alone, never logged.
 *
 * <ul>
 * <li><b>Rotated at each use</b>: the token is spent and the next one issued, in the same family.</li>
 * <li><b>Spent twice</b>: within {@link #OVERLAP} of its first use, taken again, since an app that timed out retries;
 *     after, the whole family is revoked (RFC 9700 §4.14.2): one of the two holders was not the app.</li>
 * <li><b>Lives</b> {@link #LIFETIME} from its issue, and its family {@link #FAMILY_LIFETIME} from the login: a refresh
 *     past either end is refused.</li>
 * </ul>
 *
 * A rotation locks the token's row ({@code FOR UPDATE}), so two refreshes of one token at once are taken one after the
 * other: the second sees the first's use.
 */
public final class RefreshTokens {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokens.class);

    public static final Duration LIFETIME = Duration.ofDays(60);
    public static final Duration FAMILY_LIFETIME = Duration.ofDays(365);
    public static final Duration OVERLAP = Duration.ofSeconds(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_CHARS = 43;

    public enum Outcome { ROTATED, UNKNOWN, REVOKED, EXPIRED, REUSED }

    /** A refresh: its outcome, and when rotated, whose, the new token and the family's auth_time. */
    public record Rotation(Outcome outcome, long accountId, String token, Instant authTime) {
        static Rotation refused(Outcome why) {
            return new Rotation(why, -1, null, null);
        }
    }

    /** @return a new family's first token */
    public String issue(Connection c, long accountId, Instant authTime, Instant now) throws SQLException {
        byte[] family = new byte[16];
        RANDOM.nextBytes(family);
        return insert(c, family, accountId, authTime, now, now.plus(FAMILY_LIFETIME));
    }

    public Rotation rotate(Connection c, String token, Instant now) throws SQLException {
        byte[] hash = hashOf(token);
        if (hash == null) {
            return Rotation.refused(Outcome.UNKNOWN);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT family, account_id, expires_at, family_expires_at,"
                + " auth_time, used_at, revoked_at FROM refresh_token WHERE token_hash = ? FOR UPDATE")) {
            ps.setBytes(1, hash);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    return Rotation.refused(Outcome.UNKNOWN);
                }
                byte[] family = r.getBytes(1);
                long account = r.getLong(2);
                Instant expires = r.getTimestamp(3).toInstant();
                Instant familyExpires = r.getTimestamp(4).toInstant();
                Instant authTime = r.getTimestamp(5).toInstant();
                Timestamp used = r.getTimestamp(6);
                if (r.getTimestamp(7) != null) {
                    return Rotation.refused(Outcome.REVOKED);
                }
                if (!now.isBefore(expires) || !now.isBefore(familyExpires)) {
                    return Rotation.refused(Outcome.EXPIRED);
                }
                if (used != null) {
                    if (now.isAfter(used.toInstant().plus(OVERLAP))) {
                        revoke(c, "family = ?", family, now);
                        log.warn("a refresh token of account {} was used again {} after it was spent: its family revoked",
                                account, Duration.between(used.toInstant(), now));
                        return Rotation.refused(Outcome.REUSED);
                    }
                } else {
                    try (PreparedStatement spend = c.prepareStatement("UPDATE refresh_token SET used_at = ? WHERE token_hash = ?")) {
                        spend.setTimestamp(1, Timestamp.from(now));
                        spend.setBytes(2, hash);
                        spend.executeUpdate();
                    }
                }
                return new Rotation(Outcome.ROTATED, account, insert(c, family, account, authTime, now, familyExpires), authTime);
            }
        }
    }

    /** The token's family revoked; nothing for a token not known. */
    public void revokeFamily(Connection c, String token, Instant now) throws SQLException {
        byte[] hash = hashOf(token);
        if (hash == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE refresh_token SET revoked_at = ? WHERE revoked_at IS NULL AND family ="
                        + " (SELECT family FROM (SELECT family FROM refresh_token WHERE token_hash = ?) f)")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setBytes(2, hash);
            ps.executeUpdate();
        }
    }

    /** @return how many of the account's tokens were revoked */
    public int revokeAll(Connection c, long accountId, Instant now) throws SQLException {
        return revoke(c, "account_id = ?", accountId, now);
    }

    private static int revoke(Connection c, String where, Object key, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE refresh_token SET revoked_at = ? WHERE revoked_at IS NULL AND " + where)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setObject(2, key);
            return ps.executeUpdate();
        }
    }

    private static String insert(Connection c, byte[] family, long accountId, Instant authTime, Instant now,
                                 Instant familyExpires) throws SQLException {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = now.plus(LIFETIME);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO refresh_token (token_hash, family, account_id,"
                + " issued_at, expires_at, family_expires_at, auth_time) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setBytes(1, hashOf(token));
            ps.setBytes(2, family);
            ps.setLong(3, accountId);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(expires));
            ps.setTimestamp(6, Timestamp.from(familyExpires));
            ps.setTimestamp(7, Timestamp.from(authTime));
            ps.executeUpdate();
        }
        return token;
    }

    /** The SHA-256 a token is kept by; null for what cannot be one of ours. */
    private static byte[] hashOf(String token) {
        if (token == null || token.length() != TOKEN_CHARS) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
