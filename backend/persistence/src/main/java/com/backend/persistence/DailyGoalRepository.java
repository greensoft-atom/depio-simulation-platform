package com.backend.persistence;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/** A player's progress on their day's goals (docs 04 §8, D-66); which goals they are is drawn, not stored. */
public final class DailyGoalRepository {

    /** How long a day's progress is kept: a week, for support's questions about it. */
    public static final int KEPT_DAYS = 7;

    private final DataSource ds;
    private final Tx tx;

    public DailyGoalRepository(DataSource ds) {
        this.ds = ds;
        this.tx = new Tx(ds);
    }

    /** The progress stored for each goal the player has moved that day, by its id. */
    public Map<String, Long> progress(long playerId, LocalDate day) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return progress(c, playerId, day);
        }
    }

    static Map<String, Long> progress(Connection c, long playerId, LocalDate day) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT goal_id, progress FROM daily_goal WHERE player_id = ? AND day = ?")) {
            ps.setLong(1, playerId);
            ps.setDate(2, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    /**
     * Adds to a goal's progress where it is stored, the row made if it is not there: the sum is the store's, from what
     * the latest commit left, not from a value this transaction read earlier, which under REPEATABLE READ can be its
     * snapshot's from before it waited for the player's lock, and a result committed meanwhile would be lost (D-46).
     */
    static void add(Connection c, long playerId, LocalDate day, String goalId, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO daily_goal (player_id, day, goal_id, progress)"
                + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE progress = progress + VALUES(progress)")) {
            ps.setLong(1, playerId);
            ps.setDate(2, Date.valueOf(day));
            ps.setString(3, goalId);
            ps.setLong(4, delta);
            ps.executeUpdate();
        }
    }

    /**
     * The goals' progress, read under lock: a locking read sees the latest commit, not the transaction's snapshot. The
     * rows are there ({@link #add} made them), each read by its whole key, so the lock is the rows' alone, no gap.
     */
    static Map<String, Long> progressLocked(Connection c, long playerId, LocalDate day, List<String> goalIds)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT goal_id, progress FROM daily_goal WHERE player_id = ? AND day = ?"
                + " AND goal_id IN (");
        for (int i = 0; i < goalIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") FOR UPDATE");
        Map<String, Long> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setLong(1, playerId);
            ps.setDate(2, Date.valueOf(day));
            for (int i = 0; i < goalIds.size(); i++) {
                ps.setString(3 + i, goalIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    /** Retention: progress of days before {@code day}, {@code batch} rows at a time. @return how many */
    public int purgeBefore(LocalDate day, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM daily_goal WHERE day < ? LIMIT ?")) {
                    ps.setDate(1, Date.valueOf(day));
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
