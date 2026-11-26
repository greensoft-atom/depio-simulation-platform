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
 * pair exists. So making one is one transaction, and the unique index on the subject — not a
 * prior SELECT — is what decides which of two first logins of an account makes its player.
 * Checking first and inserting after is a race that both win.
 *
 * <p>Since D-117 an account is the account service's, found by its subject, the tokens' {@code sub}; or a guest's,
 * found by its key's hash (D-46). The backend keeps no username and no password.
 */
public final class AccountRepository {

    /** Duplicate entry. The one constraint failure that is an expected answer, not a fault. */
    private static final int DUPLICATE_ENTRY = 1062;

    /**
     * {@code account.status}: 0 active, 1 suspended, 2 banned (06 §3), 3 deleted: erased by its player (D-84).
     *
     * A suspension ends at {@code banned_until}, or never if that is null; a ban does not end.
     * Any other value is one this code predates, and is read as a ban: the column is written
     * by operators, and a value nobody taught the login path about must not let anyone in.
     * Before this, every non-zero value was a ban that lapsed with {@code banned_until}, so a
     * permanent ban with a stale date from an earlier suspension was no ban at all.
     */
    public enum Status {
        ACTIVE, SUSPENDED, BANNED, DELETED;

        public static Status of(int code) {
            return switch (code) {
                case 0 -> ACTIVE;
                case 1 -> SUSPENDED;
                case 3 -> DELETED;
                default -> BANNED;
            };
        }
    }

    /** What deleting an account needs of it (D-84, D-117): whose it is, and whether it is a guest's. */
    public record Owner(long playerId, String subject, Status status, boolean guest) { }

    public enum Erased { ERASED, ALREADY, NO_SUCH_PLAYER }

    /** The display name an erased player keeps, wherever others still see them: a match, a bracket, a past season (D-84). */
    public static final String ERASED_NAME = "Deleted player";

    /** What a login needs, and nothing more: whose, and whether a ban stands. */
    public record Credentials(long playerId, Status status, Long bannedUntilMillis) { }

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
     * The account service's account, a player made for it at its first login (D-117): the account and its player row.
     *
     * @return the new player id, or -1 if the subject has one already
     */
    public long createForSubject(String subject, String displayName) throws SQLException {
        try {
            return tx.execute(c -> {
                long id = insertAccount(c, subject);
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

    /** What linking a guest to an account came to (D-117). */
    public enum Linked { OK, NOT_A_GUEST, ACCOUNT_IN_USE }

    /**
     * A guest (D-46): an account with no subject, found by its key's SHA-256 alone.
     *
     * @return the new player's id
     */
    public long createGuest(String displayName, byte[] keyHash) throws SQLException {
        return tx.execute(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (guest_key_hash) VALUES (?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setBytes(1, keyHash);
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
                     "SELECT id, status, banned_until FROM account WHERE guest_key_hash = ?")) {
            ps.setBytes(1, keyHash);
            return credentials(ps);
        }
    }

    /** @return the account service's account's credentials, by its subject, or null if no player has it yet */
    public Credentials findBySubject(String subject) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, status, banned_until FROM account WHERE subject = ?")) {
            ps.setString(1, subject);
            return credentials(ps);
        }
    }

    private static Credentials credentials(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Timestamp bannedUntil = rs.getTimestamp("banned_until");
            return new Credentials(rs.getLong("id"), Status.of(rs.getInt("status")),
                    bannedUntil == null ? null : bannedUntil.getTime());
        }
    }

    /** Whether the account service told of this subject's account deleted (D-117): its late tokens make no player. */
    public boolean erasedSubject(String subject) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM erased_subject WHERE subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** A subject recorded as erased, whether or not a player ever had it; again, nothing more. */
    public void recordErasedSubject(String subject) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            recordErased(c, subject);
        }
    }

    private static void recordErased(Connection c, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO erased_subject (subject) VALUES (?)")) {
            ps.setString(1, subject);
            ps.executeUpdate();
        }
    }

    /**
     * A guest made the account service's account, the same player (D-117): the subject set and the key cleared in one
     * statement, guarded by the key being there, so a second link is {@code NOT_A_GUEST}; a subject another player has
     * is {@code ACCOUNT_IN_USE}, by its unique index.
     */
    public Linked link(long playerId, String subject) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE account SET subject = ?, guest_key_hash = NULL"
                     + " WHERE id = ? AND guest_key_hash IS NOT NULL AND subject IS NULL")) {
            ps.setString(1, subject);
            ps.setLong(2, playerId);
            return ps.executeUpdate() == 1 ? Linked.OK : Linked.NOT_A_GUEST;
        } catch (SQLException e) {
            if (e.getErrorCode() == DUPLICATE_ENTRY) {
                return Linked.ACCOUNT_IN_USE;
            }
            throw e;
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

    /** @return the account's owner, or null if there is no such account */
    public Owner owner(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT subject, status, guest_key_hash IS NOT NULL FROM account WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Owner(playerId, rs.getString(1), Status.of(rs.getInt(2)), rs.getBoolean(3)) : null;
            }
        }
    }

    /**
     * The guests nobody has played for a while (D-87): made before {@code idleSince}, no login since (or ever), and no
     * day in {@code player_day} on or after its date; lowest ids first, at most {@code limit}. One pass over the
     * accounts and one over the days kept, as the admin's count makes them ({@link StatsRepository#guests}); nothing is
     * indexed for it, since it runs daily.
     */
    public java.util.List<Long> idleGuests(java.time.Instant idleSince, int limit) throws SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT a.id FROM account a"
                     + " LEFT JOIN (SELECT DISTINCT player_id FROM player_day WHERE day >= ?) d ON d.player_id = a.id"
                     + " WHERE a.guest_key_hash IS NOT NULL AND a.created_at < ?"
                     + " AND (a.last_login_at IS NULL OR a.last_login_at < ?) AND d.player_id IS NULL"
                     + " ORDER BY a.id LIMIT ?")) {
            ps.setString(1, idleSince.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
            ps.setTimestamp(2, Timestamp.from(idleSince));
            ps.setTimestamp(3, Timestamp.from(idleSince));
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * Erases what identifies a player, in one transaction (D-84): the account's subject, guest key and last login; the
     * player's display name, and their listing on the rating boards; whom they knew, either way, their inbox, and their
     * team invitations and applications. The rows that others' history and the ledger need stay, under the bare id. A
     * subject is recorded as erased (D-117), so a token of its account exchanged late makes no new player.
     */
    public Erased erase(long playerId) throws SQLException {
        return tx.execute(c -> {
            String subject;
            try (PreparedStatement ps = c.prepareStatement("SELECT status, subject FROM account WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Erased.NO_SUCH_PLAYER;
                    }
                    if (Status.of(rs.getInt(1)) == Status.DELETED) {
                        return Erased.ALREADY;
                    }
                    subject = rs.getString(2);
                }
            }
            update(c, "UPDATE account SET subject = NULL, guest_key_hash = NULL, last_login_at = NULL, banned_until = NULL,"
                    + " status = " + Status.DELETED.ordinal() + " WHERE id = ?", playerId);
            if (subject != null) {
                recordErased(c, subject);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE player SET display_name = ?, rated_duels = 0, rated_tvts = 0, rated_rffas = 0 WHERE id = ?")) {
                ps.setString(1, ERASED_NAME);
                ps.setLong(2, playerId);
                ps.executeUpdate();
            }
            // A friendship is two rows, and friend_id has no index of its own: the other side's found by this side's.
            java.util.List<Long> friends = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT friend_id FROM friend WHERE player_id = ?")) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        friends.add(rs.getLong(1));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend WHERE player_id = ? AND friend_id = ?")) {
                for (long friend : friends) {
                    ps.setLong(1, friend);
                    ps.setLong(2, playerId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            for (String sql : new String[] {
                    "DELETE FROM friend WHERE player_id = ?",
                    "DELETE FROM friend_request WHERE from_id = ?",
                    "DELETE FROM friend_request WHERE to_id = ?",
                    "DELETE FROM block WHERE player_id = ?",
                    "DELETE FROM block WHERE blocked_id = ?",
                    "DELETE FROM inbox WHERE player_id = ?",
                    "DELETE FROM team_invite WHERE player_id = ?",
                    "DELETE FROM team_application WHERE player_id = ?"}) {
                update(c, sql, playerId);
            }
            return Erased.ERASED;
        });
    }

    private static void update(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
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

    private static long insertAccount(Connection c, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account (subject) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, subject);
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
