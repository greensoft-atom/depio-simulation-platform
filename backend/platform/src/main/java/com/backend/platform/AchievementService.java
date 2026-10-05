package com.backend.platform;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.backend.persistence.AchievementRepository;
import com.backend.persistence.Achievements;

/** A player's achievements and their progress (docs 04 §8, D-64): reached is the stat at or over the threshold. */
public final class AchievementService {

    /** One achievement, the player's progress towards it, and whether it is reached. */
    public record Progress(Achievements.Achievement achievement, long progress, boolean reached) { }

    private final AuthService auth;
    private final AchievementRepository counts;

    public AchievementService(AuthService auth, AchievementRepository counts) {
        this.auth = auth;
        this.counts = counts;
    }

    /** @return every achievement, in the table's order; null for a session that is not one */
    public List<Progress> of(String token) throws SQLException {
        long player = auth.playerIdOf(token);
        if (player < 0) {
            return null;
        }
        Achievements.Counts mine = counts.counts(player);
        List<Progress> all = new ArrayList<>(Achievements.ALL.size());
        for (Achievements.Achievement a : Achievements.ALL) {
            long progress = mine.of(a.stat());
            all.add(new Progress(a, progress, progress >= a.threshold()));
        }
        return all;
    }
}
