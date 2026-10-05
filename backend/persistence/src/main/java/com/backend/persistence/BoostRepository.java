package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Boosts (docs detailed-design/04-platform-services.md §8, 06 §3, D-38): activating one takes an
 * item and runs it, once per key; a match's rewards are raised by those running when it ended.
 */
public final class BoostRepository {

    public enum Outcome {
        ACTIVATED,
        /** The key was used already: its first attempt took the item. */
        ALREADY_ACTIVATED,
        NOT_OWNED,
        /** A different boost of that kind runs (Q-13: one a kind). */
        OTHER_RUNNING
    }

    /** A boost running: its kind, item, percent and end. */
    public record Running(int kind, String itemId, int percent, Instant endsAt) { }

    /** Kinds, as {@code boost.kind}: experience and coins. */
    public static final int KINDS = 2;

    /** An ended boost, kept a day longer than a result may be late, so a late one finds it (06 §9). */
    public static final Duration KEPT = Duration.ofDays(31);
    /** A key, kept long past any retry of it, which comes within seconds. */
    public static final Duration KEYS_KEPT = Duration.ofDays(30);

    private final Tx tx;

    public BoostRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /**
     * Activates one of {@code itemId}, a boost of {@code kind} giving {@code percent} for {@code
     * minutes}, at {@code now}. The player is locked, as a purchase locks them, so two taps at once
     * take one item and a retry of a key none.
     */
    public Outcome activate(long playerId, String itemId, int kind, int percent, int minutes,
                            String clientKey, Instant now) throws SQLException {
        String key = "act:" + playerId + ":" + clientKey;
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM player WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, playerId);
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM boost_activation WHERE idem_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Outcome.ALREADY_ACTIVATED;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT qty FROM inventory_item WHERE player_id = ? AND item_id = ?")) {
                ps.setLong(1, playerId);
                ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) <= 0) {
                        return Outcome.NOT_OWNED;
                    }
                }
            }
            Instant runningStart = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id, started_at FROM boost WHERE player_id = ? AND kind = ? AND ends_at > ?")) {
                ps.setLong(1, playerId);
                ps.setInt(2, kind);
                ps.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (!rs.getString(1).equals(itemId)) {
                            return Outcome.OTHER_RUNNING;
                        }
                        runningStart = rs.getTimestamp(2).toInstant();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO boost_activation (idem_key, player_id, item_id) VALUES (?, ?, ?)")) {
                ps.setString(1, key);
                ps.setLong(2, playerId);
                ps.setString(3, itemId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE inventory_item SET qty = qty - 1 WHERE player_id = ? AND item_id = ?")) {
                ps.setLong(1, playerId);
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            if (runningStart != null) {
                // The same boost again: its minutes added to the run, from when it would have ended.
                try (PreparedStatement ps = c.prepareStatement("UPDATE boost SET ends_at = ends_at + INTERVAL ? MINUTE"
                        + " WHERE player_id = ? AND kind = ? AND started_at = ?")) {
                    ps.setInt(1, minutes);
                    ps.setLong(2, playerId);
                    ps.setInt(3, kind);
                    ps.setTimestamp(4, Timestamp.from(runningStart));
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO boost"
                        + " (player_id, kind, item_id, percent, started_at, ends_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, playerId);
                    ps.setInt(2, kind);
                    ps.setString(3, itemId);
                    ps.setInt(4, percent);
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.setTimestamp(6, Timestamp.from(now.plus(minutes, ChronoUnit.MINUTES)));
                    ps.executeUpdate();
                }
            }
            return Outcome.ACTIVATED;
        });
    }

    /** The player's boosts running at {@code now}, by kind. */
    public List<Running> running(long playerId, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, item_id, percent, ends_at FROM boost"
                    + " WHERE player_id = ? AND started_at <= ? AND ends_at > ? ORDER BY kind")) {
                ps.setLong(1, playerId);
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setTimestamp(3, Timestamp.from(now));
                List<Running> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Running(rs.getInt(1), rs.getString(2), rs.getInt(3),
                                rs.getTimestamp(4).toInstant()));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /**
     * For a match that ended at {@code at}: each player's percent a kind, indexed by kind, from the
     * boosts that were running then. A player with none is absent.
     */
    public Map<Long, int[]> percentsAt(Collection<Long> playerIds, Instant at) throws SQLException {
        Map<Long, int[]> out = new HashMap<>();
        if (playerIds.isEmpty()) {
            return out;
        }
        return tx.execute(c -> {
            String marks = String.join(",", java.util.Collections.nCopies(playerIds.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement("SELECT player_id, kind, percent FROM boost"
                    + " WHERE player_id IN (" + marks + ") AND started_at <= ? AND ends_at > ?")) {
                int i = 1;
                for (long id : playerIds) {
                    ps.setLong(i++, id);
                }
                ps.setTimestamp(i++, Timestamp.from(at));
                ps.setTimestamp(i, Timestamp.from(at));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.computeIfAbsent(rs.getLong(1), id -> new int[KINDS])[rs.getInt(2)] = rs.getInt(3);
                    }
                }
            }
            return out;
        });
    }

    /**
     * Deletes the boosts that ended {@link #KEPT} before {@code now} and the keys used {@link
     * #KEYS_KEPT} before it, {@code batch} rows a transaction (D-38).
     *
     * @return the rows deleted, of both
     */
    public int purge(Instant now, int batch) throws SQLException {
        return purge("DELETE FROM boost WHERE ends_at < ? LIMIT ?", now.minus(KEPT), batch)
                + purge("DELETE FROM boost_activation WHERE at < ? LIMIT ?", now.minus(KEYS_KEPT), batch);
    }

    private int purge(String sql, Instant before, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setTimestamp(1, Timestamp.from(before));
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
