package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * What the leaderboards are made of, read back from MySQL (05 §8).
 *
 * The boards in the store are a projection: every score on them came from a result MySQL
 * committed first. So they can be rebuilt from here after the store is lost, and results
 * applied while the store could not be written to - applied but never ranked - reach the
 * boards the same way.
 */
public final class LeaderboardSource {

    /** One player's best in whatever span was asked for. */
    public record Best(long playerId, String displayName, int score) { }

    /** The bests in a window, and when the last score in it was made. */
    public record Window(List<Best> bests, Instant lastScoreAt) { }

    private final DataSource dataSource;

    public LeaderboardSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Every player's best single-match score, in pages ordered by player id: the page after
     * {@code afterPlayerId}. The all-time board has one row per player who ever scored, which
     * is eventually most of the player table, so it is read in pages rather than at once.
     */
    public List<Best> allTime(long afterPlayerId, int limit) throws SQLException {
        String sql = """
                SELECT s.player_id, p.display_name, s.best_score
                  FROM player_stat s JOIN player p ON p.id = s.player_id
                 WHERE s.player_id > ? AND s.best_score > 0
                 ORDER BY s.player_id
                 LIMIT ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, afterPlayerId);
            ps.setInt(2, limit);
            List<Best> out = new ArrayList<>(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Best(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                }
            }
            return out;
        }
    }

    /**
     * Each player's best score among the matches that ended in {@code [from, to)}, as the
     * worker offered them to the board of that day or week: by the match's own end, and only
     * scores above zero. {@code lastScoreAt} is null when nothing in the window scored.
     */
    public Window window(Instant from, Instant to) throws SQLException {
        String sql = """
                SELECT mp.player_id, p.display_name, MAX(mp.score), MAX(m.ended_at)
                  FROM matches m
                  JOIN match_player mp ON mp.match_id = m.id
                  JOIN player p ON p.id = mp.player_id
                 WHERE m.ended_at >= ? AND m.ended_at < ? AND mp.score > 0
                 GROUP BY mp.player_id, p.display_name
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setTimestamp(2, Timestamp.from(to));
            List<Best> out = new ArrayList<>();
            Instant last = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Best(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                    Instant ended = rs.getTimestamp(4).toInstant();
                    last = last == null || ended.isAfter(last) ? ended : last;
                }
            }
            return new Window(out, last);
        }
    }
}
