package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.backend.persistence.DailyGoalRepository;
import com.backend.persistence.DailyGoals;

/** A player's goals for today, UTC, and their progress (docs 04 §8, D-66): drawn as the worker draws them. */
public final class GoalService {

    /** One of the day's goals, the progress towards it, and whether it is met. */
    public record Progress(DailyGoals.Goal goal, long progress, boolean done) { }

    /** Today: its date, when it ends, its three goals, and whether all three are met. */
    public record Today(LocalDate day, Instant resetsAt, List<Progress> goals, boolean setDone) { }

    private final AuthService auth;
    private final DailyGoalRepository progress;
    private final Clock clock;

    public GoalService(AuthService auth, DailyGoalRepository progress, Clock clock) {
        this.auth = auth;
        this.progress = progress;
        this.clock = clock;
    }

    /** @return the player's day; null for a session that is not one */
    public Today of(String token) throws SQLException {
        long player = auth.playerIdOf(token);
        if (player < 0) {
            return null;
        }
        LocalDate day = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
        Map<String, Long> made = progress.progress(player, day);
        List<Progress> three = new ArrayList<>(DailyGoals.PER_DAY);
        boolean all = true;
        for (DailyGoals.Goal g : DailyGoals.of(player, day)) {
            long p = made.getOrDefault(g.id(), 0L);
            three.add(new Progress(g, p, p >= g.target()));
            all &= p >= g.target();
        }
        return new Today(day, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), three, all);
    }
}
