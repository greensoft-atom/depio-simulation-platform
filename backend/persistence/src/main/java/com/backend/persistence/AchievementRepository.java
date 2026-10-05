package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/** A player's counted stats, which their achievements' progress is (docs 04 §8, D-64). */
public final class AchievementRepository {

    private final DataSource ds;

    public AchievementRepository(DataSource ds) {
        this.ds = ds;
    }

    /** @return the player's counts, {@link Achievements.Counts#NONE} before their first result */
    public Achievements.Counts counts(long playerId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT kills, wins, matches, assists, best_score, playtime_s"
                     + " FROM player_stat WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Achievements.Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6)) : Achievements.Counts.NONE;
            }
        }
    }
}
