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
 * Friends and blocks (docs detailed-design/04-platform-services.md §9, Q-20, D-45). A friendship
 * is two rows, one each way; every change locks both players' rows, lowest id first, before it
 * reads what it changes, so the caps and a request made both ways at once hold.
 */
public final class FriendRepository {

    public static final Duration REQUEST_LIFE = Duration.ofDays(7);
    /** Requests one player may have out at once, unlapsed (Q-46). */
    public static final int MAX_ASKED = 50;
    /** The requests to a player listed, the newest (Q-46). */
    public static final int LISTED = 100;

    /**
     * {@code IGNORED}: from a player the other has blocked, kept and never shown to them (D-56); the
     * API answers it as {@code ASKED}, and tells no one.
     */
    public enum Asked {
        ASKED, FRIENDS, IGNORED, YOURSELF, NO_SUCH_PLAYER, ALREADY_FRIENDS, ALREADY_ASKED, FRIENDS_FULL, YOU_BLOCKED,
        TOO_MANY_ASKED
    }

    public enum Blocked { BLOCKED, YOURSELF, NO_SUCH_PLAYER, BLOCKS_FULL }

    public record Person(long playerId, String name) { }

    public record Request(long playerId, String name, Instant expiresAt) { }

    private final Tx tx;
    private final int maxFriends;
    private final int maxBlocks;
    private final int maxAsked;

    public FriendRepository(DataSource dataSource, int maxFriends, int maxBlocks) {
        this(dataSource, maxFriends, maxBlocks, MAX_ASKED);
    }

    /** With {@code maxAsked} requests out at once a player. */
    public FriendRepository(DataSource dataSource, int maxFriends, int maxBlocks, int maxAsked) {
        this.tx = new Tx(dataSource);
        this.maxFriends = maxFriends;
        this.maxBlocks = maxBlocks;
        this.maxAsked = maxAsked;
    }

    /** Retention: deletes requests lapsed by {@code now}, {@code batch} at a time (D-40). @return how many */
    public int purgeLapsed(Instant now, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_request WHERE expires_at <= ? LIMIT ?")) {
                    ps.setTimestamp(1, Timestamp.from(now));
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

    /**
     * Asks {@code to} to be a friend; if they had asked {@code from} first, and neither is full,
     * they are friends at once. A player with {@link #MAX_ASKED} requests out asks no more until
     * one is answered, withdrawn or lapsed (Q-46). A request from a player {@code to} has blocked
     * is kept as any other, and {@code IGNORED}: never in {@code to}'s inbox or requests, so the
     * asker sees nothing a request to anyone else would not show (D-56).
     */
    public Asked ask(long from, long to, Instant now) throws SQLException {
        if (from == to) {
            return Asked.YOURSELF;
        }
        return tx.execute(c -> {
            if (!lockBoth(c, from, to)) {
                return Asked.NO_SUCH_PLAYER;
            }
            if (blocks(c, from, to)) {
                return Asked.YOU_BLOCKED;
            }
            boolean hidden = blocks(c, to, from);
            if (exists(c, "SELECT 1 FROM friend WHERE player_id = ? AND friend_id = ?", from, to)) {
                return Asked.ALREADY_FRIENDS;
            }
            if (live(c, to, from, now)) {
                if (count(c, "SELECT COUNT(*) FROM friend WHERE player_id = ?", from) >= maxFriends
                        || count(c, "SELECT COUNT(*) FROM friend WHERE player_id = ?", to) >= maxFriends) {
                    return Asked.FRIENDS_FULL;
                }
                for (long[] pair : new long[][] {{from, to}, {to, from}}) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO friend (player_id, friend_id, since) VALUES (?, ?, ?)")) {
                        ps.setLong(1, pair[0]);
                        ps.setLong(2, pair[1]);
                        ps.setTimestamp(3, Timestamp.from(now));
                        ps.executeUpdate();
                    }
                }
                dropRequests(c, from, to);
                InboxRepository.add(c, to, InboxRepository.FRIEND_ACCEPTED, from, now);
                return Asked.FRIENDS;
            }
            if (live(c, from, to, now)) {
                return Asked.ALREADY_ASKED;
            }
            if (count(c, "SELECT COUNT(*) FROM friend WHERE player_id = ?", from) >= maxFriends) {
                return Asked.FRIENDS_FULL;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM friend_request WHERE from_id = ? AND expires_at > ?")) {
                ps.setLong(1, from);
                ps.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxAsked) {
                        return Asked.TOO_MANY_ASKED;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO friend_request (from_id, to_id, expires_at)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                ps.setTimestamp(3, Timestamp.from(now.plus(REQUEST_LIFE)));
                ps.executeUpdate();
            }
            if (hidden) {
                return Asked.IGNORED;
            }
            InboxRepository.add(c, to, InboxRepository.FRIEND_REQUEST, from, now);
            return Asked.ASKED;
        });
    }

    /** Ends a friendship, both ways. */
    public boolean remove(long player, long friend) throws SQLException {
        return tx.execute(c -> {
            lockBoth(c, player, friend);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend WHERE (player_id = ? AND friend_id = ?)"
                    + " OR (player_id = ? AND friend_id = ?)")) {
                ps.setLong(1, player);
                ps.setLong(2, friend);
                ps.setLong(3, friend);
                ps.setLong(4, player);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Declines {@code other}'s request, or withdraws one's own to them. */
    public boolean dropRequest(long player, long other) throws SQLException {
        return tx.execute(c -> dropRequests(c, player, other) > 0);
    }

    public List<Person> friendsOf(long player) throws SQLException {
        return people("SELECT f.friend_id, p.display_name FROM friend f JOIN player p ON p.id = f.friend_id"
                + " WHERE f.player_id = ? ORDER BY p.display_name, f.friend_id", player);
    }

    /**
     * Requests to the player that have not lapsed, newest first, the {@value #LISTED} newest; none
     * from a player they block (D-56).
     */
    public List<Request> requestsTo(long player, Instant now) throws SQLException {
        return requests("SELECT r.from_id, p.display_name, r.expires_at FROM friend_request r JOIN player p ON p.id = r.from_id"
                + " WHERE r.to_id = ? AND r.expires_at > ? AND NOT EXISTS (SELECT 1 FROM block b"
                + " WHERE b.player_id = r.to_id AND b.blocked_id = r.from_id)"
                + " ORDER BY r.expires_at DESC LIMIT " + LISTED, player, now);
    }

    /** The player's own requests that have not lapsed, newest first: {@link #MAX_ASKED} at most. */
    public List<Request> askedBy(long player, Instant now) throws SQLException {
        return requests("SELECT r.to_id, p.display_name, r.expires_at FROM friend_request r JOIN player p ON p.id = r.to_id"
                + " WHERE r.from_id = ? AND r.expires_at > ? ORDER BY r.expires_at DESC", player, now);
    }

    /** Blocks {@code target}: a friendship and any request either way end. Blocking twice is blocking once. */
    public Blocked block(long player, long target, Instant now) throws SQLException {
        if (player == target) {
            return Blocked.YOURSELF;
        }
        return tx.execute(c -> {
            if (!lockBoth(c, player, target)) {
                return Blocked.NO_SUCH_PLAYER;
            }
            if (blocks(c, player, target)) {
                return Blocked.BLOCKED;
            }
            if (count(c, "SELECT COUNT(*) FROM block WHERE player_id = ?", player) >= maxBlocks) {
                return Blocked.BLOCKS_FULL;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO block (player_id, blocked_id, since) VALUES (?, ?, ?)")) {
                ps.setLong(1, player);
                ps.setLong(2, target);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend WHERE (player_id = ? AND friend_id = ?)"
                    + " OR (player_id = ? AND friend_id = ?)")) {
                ps.setLong(1, player);
                ps.setLong(2, target);
                ps.setLong(3, target);
                ps.setLong(4, player);
                ps.executeUpdate();
            }
            dropRequests(c, player, target);
            return Blocked.BLOCKED;
        });
    }

    /** Unblocks {@code target}, and drops their request made meanwhile, never seen: nothing arrives late (D-56). */
    public boolean unblock(long player, long target) throws SQLException {
        return tx.execute(c -> {
            lockBoth(c, player, target);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM block WHERE player_id = ? AND blocked_id = ?")) {
                ps.setLong(1, player);
                ps.setLong(2, target);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_request WHERE from_id = ? AND to_id = ?")) {
                ps.setLong(1, target);
                ps.setLong(2, player);
                ps.executeUpdate();
            }
            return true;
        });
    }

    public List<Person> blockedBy(long player) throws SQLException {
        return people("SELECT b.blocked_id, p.display_name FROM block b JOIN player p ON p.id = b.blocked_id"
                + " WHERE b.player_id = ? ORDER BY p.display_name, b.blocked_id", player);
    }

    /** Whether the two are friends: who may learn the other is not in the lobby (S-16). */
    public boolean areFriends(long player, long other) throws SQLException {
        return tx.execute(c -> exists(c, "SELECT 1 FROM friend WHERE player_id = ? AND friend_id = ?", player, other));
    }

    /** Whether {@code player} has blocked {@code other}: what party and team invitations ask. */
    public boolean blocks(long player, long other) throws SQLException {
        return tx.execute(c -> blocks(c, player, other));
    }

    // ---- inside a transaction ------------------------------------------------------------

    /** Locks both players' rows, lowest id first (D-45). @return whether both exist */
    private static boolean lockBoth(Connection c, long a, long b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM player WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
            ps.setLong(1, Math.min(a, b));
            ps.setLong(2, Math.max(a, b));
            int found = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found++;
                }
            }
            return found == 2;
        }
    }

    static boolean blocks(Connection c, long player, long other) throws SQLException {
        return exists(c, "SELECT 1 FROM block WHERE player_id = ? AND blocked_id = ?", player, other);
    }

    private static boolean live(Connection c, long from, long to, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM friend_request WHERE from_id = ? AND to_id = ? AND expires_at > ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setTimestamp(3, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int dropRequests(Connection c, long a, long b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_request WHERE (from_id = ? AND to_id = ?)"
                + " OR (from_id = ? AND to_id = ?)")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setLong(3, b);
            ps.setLong(4, a);
            return ps.executeUpdate();
        }
    }

    private static boolean exists(Connection c, String sql, long a, long b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int count(Connection c, String sql, long a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, a);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<Person> people(String sql, long player) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, player);
                List<Person> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Person(rs.getLong(1), rs.getString(2)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    private List<Request> requests(String sql, long player, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, player);
                ps.setTimestamp(2, Timestamp.from(now));
                List<Request> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Request(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
                    }
                }
                return List.copyOf(out);
            }
        });
    }
}
