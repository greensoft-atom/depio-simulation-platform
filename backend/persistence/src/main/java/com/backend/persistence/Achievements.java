package com.backend.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Achievements (docs detailed-design/04-platform-services.md §8, D-64): goals a player can see
 * coming, each a threshold on one of the stats every result already counts ({@code player_stat}),
 * paid in gems once, when a result carries the stat across it. First cuts of the balance the owner
 * left to Claude (Q-48), their reasons in 04 §8: seventeen, 430 gems in all.
 *
 * In code, not content: {@code worker} pays by this table and {@code platform} lists it, and one
 * release ships both.
 */
public final class Achievements {

    private Achievements() {
    }

    /** A counted stat, as {@code player_stat} holds it, and its name on the wire. */
    public enum Stat {
        KILLS("kills"), WINS("wins"), MATCHES("matches"), ASSISTS("assists"), BEST_SCORE("bestScore"),
        PLAYTIME("playtime");

        public final String apiName;

        Stat(String apiName) {
            this.apiName = apiName;
        }
    }

    /** One achievement: reached when its stat is at or over the threshold. Play time is in seconds. */
    public record Achievement(String id, Stat stat, long threshold, int gems) { }

    private static final long HOUR = 3_600;

    public static final List<Achievement> ALL = List.of(
            new Achievement("kills_100", Stat.KILLS, 100, 10),
            new Achievement("kills_1000", Stat.KILLS, 1_000, 20),
            new Achievement("kills_10000", Stat.KILLS, 10_000, 50),
            new Achievement("wins_10", Stat.WINS, 10, 10),
            new Achievement("wins_100", Stat.WINS, 100, 20),
            new Achievement("wins_1000", Stat.WINS, 1_000, 50),
            new Achievement("matches_50", Stat.MATCHES, 50, 10),
            new Achievement("matches_500", Stat.MATCHES, 500, 20),
            new Achievement("matches_5000", Stat.MATCHES, 5_000, 50),
            new Achievement("assists_100", Stat.ASSISTS, 100, 10),
            new Achievement("assists_1000", Stat.ASSISTS, 1_000, 20),
            new Achievement("best_score_10000", Stat.BEST_SCORE, 10_000, 10),
            new Achievement("best_score_50000", Stat.BEST_SCORE, 50_000, 20),
            new Achievement("best_score_100000", Stat.BEST_SCORE, 100_000, 50),
            new Achievement("playtime_10h", Stat.PLAYTIME, 10 * HOUR, 10),
            new Achievement("playtime_100h", Stat.PLAYTIME, 100 * HOUR, 20),
            new Achievement("playtime_1000h", Stat.PLAYTIME, 1_000 * HOUR, 50));

    /** A player's counted stats, as {@code player_stat} holds them: all 0 before their first result. */
    public record Counts(long kills, long wins, long matches, long assists, long bestScore, long playtime) {

        public static final Counts NONE = new Counts(0, 0, 0, 0, 0, 0);

        public long of(Stat stat) {
            return switch (stat) {
                case KILLS -> kills;
                case WINS -> wins;
                case MATCHES -> matches;
                case ASSISTS -> assists;
                case BEST_SCORE -> bestScore;
                case PLAYTIME -> playtime;
            };
        }
    }

    /** The achievements a result reaches: under the threshold before it, at or over it after. */
    public static List<Achievement> crossed(Counts before, Counts after) {
        List<Achievement> reached = new ArrayList<>();
        for (Achievement a : ALL) {
            if (before.of(a.stat()) < a.threshold() && after.of(a.stat()) >= a.threshold()) {
                reached.add(a);
            }
        }
        return reached;
    }
}
