package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import javax.sql.DataSource;

/**
 * Accounts and the rows that must exist alongside them
 * (docs detailed-design/04-platform-services.md §1).
 *
 * An account without its player row is a broken account: every later read assumes the
 * pair exists. So registration is one transaction, and the unique index on the username —
 * not a prior SELECT — is what decides who gets a contested name. Checking first and
 * inserting after is a race that two simultaneous registrations both win.
 */
public final class AccountRepository {

    /** Duplicate entry. The one constraint failure that is an expected answer, not a fault. */
    private static final int DUPLICATE_ENTRY = 1062;

    /**
     * {@code account.status}: 0 active, 1 suspended, 2 banned (06 §3).
     *
     * A suspension ends at {@code banned_until}, or never if that is null; a ban does not end.
     * Any other value is one this code predates, and is read as a ban: the column is written
     * by operators, and a value nobody taught the login path about must not let anyone in.
     * Before this, every non-zero value was a ban that lapsed with {@code banned_until}, so a
     * permanent ban with a stale date from an earlier suspension was no ban at all.
     */
    public enum Status {
        ACTIVE, SUSPENDED, BANNED;

        public static Status of(int code) {
            return switch (code) {
                case 0 -> ACTIVE;
                case 1 -> SUSPENDED;
                default -> BANNED;
            };
        }
    }

    /** What login needs, and nothing more. The hash is bytes: it is never rendered. */
    public record Credentials(long playerId, byte[] passwordHash, Status status,
                              Long bannedUntilMillis) { }

    /** What issuing a join ticket needs. */
    public record Profile(long playerId, String displayName, String publicCode) { }

    public enum Renamed { OK, TOO_SOON, NO_SUCH_PLAYER }

    /** A display name changes once in this, free; the first at once (04 §1, Q-48's balance). */
    public static final java.time.Duration RENAME_EVERY = java.time.Duration.ofDays(30);

    /** The player's display name, already checked, unless they changed it within {@link #RENAME_EVERY}. */
    public Renamed rename(long playerId, String displayName, java.time.Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name_changed_at FROM player WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Renamed.NO_SUCH_PLAYER;
                    }
                    Timestamp last = rs.getTimestamp(1);
                    if (last != null && now.isBefore(last.toInstant().plus(RENAME_EVERY))) {
                        return Renamed.TOO_SOON;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE player SET display_name = ?, name_changed_at = ? WHERE id = ?")) {
                ps.setString(1, displayName);
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setLong(3, playerId);
                ps.executeUpdate();
            }
            return Renamed.OK;
        });
    }

    private final Tx tx;
    private final DataSource dataSource;

    public AccountRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.tx = new Tx(dataSource);
    }

    /**
     * Creates an account and its player row.
     *
     * @return the new player id, or -1 if the username is taken
     */
    public long register(String username, String displayName, byte[] passwordHash)
            throws SQLException {
        try {
            return tx.execute(c -> {
                long id = insertAccount(c, username, passwordHash);
                insertPlayer(c, id, displayName);
                return id;
            });
        } catch (SQLException e) {
            if (e.getErrorCode() == DUPLICATE_ENTRY) {
                return -1;
            }
            throw e;
        }
    }

    /** What upgrading a guest came to (D-46). */
    public enum Upgraded { OK, USERNAME_TAKEN, NOT_A_GUEST }

    /**
     * A guest (D-46): an account under a username nobody can register, with no password, found
     * by its key's SHA-256 alone.
     *
     * @return the new player's id
     */
    public long createGuest(String username, String displayName, byte[] keyHash) throws SQLException {
        return tx.execute(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (username, password_hash, guest_key_hash) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setBytes(2, new byte[0]);
                ps.setBytes(3, keyHash);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            insertPlayer(c, id, displayName);
            return id;
        });
    }

    /** @return the guest's credentials, or null if no guest has this key's hash */
    public Credentials findGuest(byte[] keyHash) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, password_hash, status, banned_until FROM account WHERE guest_key_hash = ?")) {
            ps.setBytes(1, keyHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp bannedUntil = rs.getTimestamp("banned_until");
                return new Credentials(rs.getLong("id"), rs.getBytes("password_hash"),
                        Status.of(rs.getInt("status")), bannedUntil == null ? null : bannedUntil.getTime());
            }
        }
    }

    /**
     * Makes a guest a full account, the same player (D-46): the username and password hash set
     * and the key cleared in one statement, and the display name changed if one is given.
     */
    public Upgraded upgrade(long playerId, String username, byte[] passwordHash, String displayName)
            throws SQLException {
        try {
            return tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE account SET username = ?, password_hash = ?,"
                        + " guest_key_hash = NULL WHERE id = ? AND guest_key_hash IS NOT NULL")) {
                    ps.setString(1, username);
                    ps.setBytes(2, passwordHash);
                    ps.setLong(3, playerId);
                    if (ps.executeUpdate() != 1) {
                        return Upgraded.NOT_A_GUEST;
                    }
                }
                if (displayName != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE player SET display_name = ? WHERE id = ?")) {
                        ps.setString(1, displayName);
                        ps.setLong(2, playerId);
                        ps.executeUpdate();
                    }
                }
                return Upgraded.OK;
            });
        } catch (SQLException e) {
            if (e.getErrorCode() == DUPLICATE_ENTRY) {
                return Upgraded.USERNAME_TAKEN;
            }
            throw e;
        }
    }

    /**
     * Whether {@link #upgrade} would find a guest and the name free, read before the caller spends a
     * hash on it (S-18). The upgrade checks both again as it writes.
     */
    public Upgraded upgradable(long playerId, String username) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT guest_key_hash IS NOT NULL,"
                     + " EXISTS (SELECT 1 FROM account WHERE username_key = LOWER(?)) FROM account WHERE id = ?")) {
            ps.setString(1, username);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next() || !rs.getBoolean(1) ? Upgraded.NOT_A_GUEST
                        : rs.getBoolean(2) ? Upgraded.USERNAME_TAKEN : Upgraded.OK;
            }
        }
    }

    /** @return the credentials, or null if no such username exists. */
    public Credentials findForLogin(String username) throws SQLException {
        String sql = """
                SELECT id, password_hash, status, banned_until
                  FROM account
                 WHERE username_key = LOWER(?)
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp bannedUntil = rs.getTimestamp("banned_until");
                return new Credentials(
                        rs.getLong("id"),
                        rs.getBytes("password_hash"),
                        Status.of(rs.getInt("status")),
                        bannedUntil == null ? null : bannedUntil.getTime());
            }
        }
    }

    /** @return the profile, or null if the player does not exist. */
    public Profile findProfile(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT display_name, public_code FROM player WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Profile(playerId, rs.getString(1), rs.getString(2))
                        : null;
            }
        }
    }

    /** @return the player's account level, or -1 if the player does not exist. */
    public int level(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT level FROM player WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /**
     * @return the player's rating in a rated mode, {@link MatchResultRepository#MODE_DUEL},
     *         {@link MatchResultRepository#MODE_TVT} or {@link MatchResultRepository#MODE_RFFA},
     *         or -1 if the player does not exist. Read when
     *         the player queues, to match by (04 §4); the rating a result moves is re-read under the lock.
     */
    public int rating(long playerId, int mode) throws SQLException {
        String column = switch (mode) {
            case MatchResultRepository.MODE_DUEL -> "rating_duel";
            case MatchResultRepository.MODE_TVT -> "rating_tvt";
            case MatchResultRepository.MODE_RFFA -> "rating_rffa";
            default -> throw new IllegalArgumentException("mode " + mode + " is not rated");
        };
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM player WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /**
     * Replaces a password hash, but only if it is still {@code expected}: the one the caller
     * verified. A password changed between that check and this write stays changed, rather
     * than being overwritten by a rehash of the old one.
     *
     * @return whether it was replaced
     */
    public boolean replacePasswordHash(long accountId, byte[] expected, byte[] replacement)
            throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE account SET password_hash = ? WHERE id = ? AND password_hash = ?")) {
            ps.setBytes(1, replacement);
            ps.setLong(2, accountId);
            ps.setBytes(3, expected);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Records a successful login. Deliberately outside the login transaction and allowed to
     * fail quietly: a write to an audit column must never be the reason a player cannot log in.
     */
    public void touchLogin(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE account SET last_login_at = CURRENT_TIMESTAMP(3) WHERE id = ?")) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    private static long insertAccount(Connection c, String username, byte[] passwordHash)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account (username, password_hash) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setBytes(2, passwordHash);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertPlayer(Connection c, long id, String displayName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO player (id, public_code, display_name) VALUES (?,?,?)")) {
            ps.setLong(1, id);
            // Derived from the id, so it is unique without a collision-retry loop. It is
            // therefore also enumerable, which is a fair trade for a friend code and can be
            // changed later without touching anything that reads it.
            ps.setString(2, String.format("P%011d", id));
            ps.setString(3, displayName);
            ps.executeUpdate();
        }
        // The stats row too, made empty with the player: a result's locking read of a row that does not exist
        // takes a gap lock, and every new player's id lies in the same gap above the last stats row, so two first
        // results deadlocked, each waiting on the other's insert (D-43).
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO player_stat (player_id) VALUES (?)")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
