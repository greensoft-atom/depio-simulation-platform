package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The inbox (docs detailed-design/04-platform-services.md §9, Q-20): what a player should learn on
 * their return, each item a kind and a reference, never text, so the client makes every word.
 * One item a player, kind and reference: a repeat is ignored, so an item written again after a
 * retry is the same item.
 */
public final class InboxRepository {

    public static final int FRIEND_REQUEST = 1;
    public static final int FRIEND_ACCEPTED = 2;
    public static final int TEAM_INVITE = 3;
    public static final int TOURNAMENT_PRIZE = 4;
    public static final int TEAM_APPLICATION = 5;
    /** A season's place paid (04 §7): {@code ref} is the season × 10 + the board's mode id (1, 2 or 3, or 5 for teams). */
    public static final int SEASON_REWARD = 6;

    /** How long an item is kept, read or not. */
    public static final Duration KEPT = Duration.ofDays(30);

    /** The newest items a read returns. */
    static final int READ_LIMIT = 50;

    private static final int DUPLICATE_ENTRY = 1062;

    /** {@code ref}: the other player's id, the team's, the tournament's, or a season's board, by kind. */
    public record Item(long id, int kind, long ref, Instant at, boolean read) { }

    private final Tx tx;

    public InboxRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /** @return whether it is new: false for a repeat */
    public boolean add(long player, int kind, long ref, Instant now) throws SQLException {
        return tx.execute(c -> add(c, player, kind, ref, now));
    }

    /** The same, inside the transaction of what it reports. */
    static boolean add(Connection c, long player, int kind, long ref, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO inbox (player_id, kind, ref, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, player);
            ps.setInt(2, kind);
            ps.setLong(3, ref);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // A duplicate rolls back this statement only; the caller's transaction carries on.
            if (e.getErrorCode() == DUPLICATE_ENTRY) {
                return false;
            }
            throw e;
        }
    }

    /** The player's newest items, newest first. */
    public List<Item> itemsOf(long player) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, ref, created_at, read_at FROM inbox"
                    + " WHERE player_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, player);
                ps.setInt(2, READ_LIMIT);
                List<Item> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Item(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getTimestamp(4).toInstant(),
                                rs.getTimestamp(5) != null));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /** Marks read the player's items up to {@code upTo}. @return how many were unread */
    public int markRead(long player, long upTo, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE inbox SET read_at = ? WHERE player_id = ? AND id <= ? AND read_at IS NULL")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setLong(2, player);
                ps.setLong(3, upTo);
                return ps.executeUpdate();
            }
        });
    }

    /** Retention: deletes items created before {@code cutoff}, {@code batch} at a time. @return how many */
    public int purgeOlderThan(Instant cutoff, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM inbox WHERE created_at < ? LIMIT ?")) {
                    ps.setTimestamp(1, Timestamp.from(cutoff));
                    ps.setInt(2, batch);
                    return ps.executeUpdate();
                }
            });
            total += removed;
            if (removed < batch) {
                return total;
            }
        }
    }
}
