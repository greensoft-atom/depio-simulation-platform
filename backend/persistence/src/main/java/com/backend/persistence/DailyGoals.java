package com.backend.persistence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily goals (docs detailed-design/04-platform-services.md §8, D-66): three a player a UTC day,
 * drawn from this table by a fixed function of the player and the day, so whoever counts and
 * whoever lists them agree without storing the draw. First cuts of the balance the owner left to
 * Claude (Q-48), their reasons in 04 §8.
 */
public final class DailyGoals {

    private DailyGoals() {
    }

    /** What a goal counts, and its name on the wire. */
    public enum Kind {
        STAYS("stays"), KILLS("kills"), WINS("wins"), ASSISTS("assists"), SCORE("score"), PLAYTIME("playtime"),
        RATED("rated");

        public final String apiName;

        Kind(String apiName) {
            this.apiName = apiName;
        }
    }

    /** A goal: met when the day's count of its kind reaches the target. Play time is in seconds. */
    public record Goal(String id, Kind kind, long target, int coins) { }

    /** The goals a day. */
    public static final int PER_DAY = 3;

    /** What the three together pay: a boost a week for a player who comes back each day. */
    public static final int SET_GEMS = 3;

    /** Each kind's easy goal, then its hard one. */
    static final List<List<Goal>> POOL = List.of(
            List.of(new Goal("stays_3", Kind.STAYS, 3, 100), new Goal("stays_6", Kind.STAYS, 6, 200)),
            List.of(new Goal("kills_10", Kind.KILLS, 10, 100), new Goal("kills_30", Kind.KILLS, 30, 200)),
            List.of(new Goal("wins_1", Kind.WINS, 1, 150), new Goal("wins_3", Kind.WINS, 3, 300)),
            List.of(new Goal("assists_5", Kind.ASSISTS, 5, 100), new Goal("assists_15", Kind.ASSISTS, 15, 200)),
            List.of(new Goal("score_5000", Kind.SCORE, 5_000, 100), new Goal("score_15000", Kind.SCORE, 15_000, 200)),
            List.of(new Goal("playtime_20m", Kind.PLAYTIME, 20 * 60, 100), new Goal("playtime_60m", Kind.PLAYTIME, 60 * 60, 200)),
            List.of(new Goal("rated_1", Kind.RATED, 1, 150), new Goal("rated_3", Kind.RATED, 3, 300)));

    /** A player's three for a day: three different kinds, each easy or hard, always the same three. */
    public static List<Goal> of(long playerId, LocalDate day) {
        long state = playerId * 0x9E3779B97F4A7C15L ^ day.toEpochDay();
        int[] kinds = new int[POOL.size()];
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = i;
        }
        List<Goal> three = new ArrayList<>(PER_DAY);
        for (int i = 0; i < PER_DAY; i++) {
            state += 0x9E3779B97F4A7C15L;
            long r = mix(state);
            int j = i + (int) Long.remainderUnsigned(r, kinds.length - i);
            int kind = kinds[j];
            kinds[j] = kinds[i];
            kinds[i] = kind;
            three.add(POOL.get(kind).get((int) (r >>> 63)));
        }
        return three;
    }

    /** A goal by its id, or null. */
    public static Goal byId(String id) {
        for (List<Goal> tiers : POOL) {
            for (Goal g : tiers) {
                if (g.id().equals(id)) {
                    return g;
                }
            }
        }
        return null;
    }

    /** What one result counts towards each kind. */
    public record Counted(long stays, long kills, long wins, long assists, long score, long playtime, long rated) {

        public long of(Kind kind) {
            return switch (kind) {
                case STAYS -> stays;
                case KILLS -> kills;
                case WINS -> wins;
                case ASSISTS -> assists;
                case SCORE -> score;
                case PLAYTIME -> playtime;
                case RATED -> rated;
            };
        }
    }

    /** SplitMix64's finaliser. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
