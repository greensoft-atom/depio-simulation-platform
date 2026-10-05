package com.backend.worker;

import java.util.Arrays;

/**
 * The account level a lifetime xp total is worth (D-12).
 *
 * Not the tank's level. A tank levels 1 to 45 inside one life and starts again; an account
 * level is what a player has built across every match, and what later features unlock by
 * (the co-op tiers of 04 §5). It lives here, beside {@link RewardRules}, because it is the
 * same kind of decision — balance — and the same reasoning applies: one place, one version.
 *
 * <h2>The curve</h2>
 *
 * {@code xp(L) = 3 000 × (L − 1)^p}, with p chosen so that level 100 costs exactly 6 000 000
 * (p ≈ 1.654). A match's xp is roughly its score (1 per point, 5 per kill), and the tank table
 * says what a score means: an ordinary life reaches tank level 25 to 30, 3 000 to 5 000
 * points; a strong one reaches 45, 23 000.
 *
 * <table>
 * <tr><th>Level</th><th>Lifetime xp</th><th>Roughly</th></tr>
 * <tr><td>2</td><td>3 000</td><td>the first ordinary session</td></tr>
 * <tr><td>10</td><td>113 600</td><td>a week of casual play, some 25 sessions</td></tr>
 * <tr><td>30</td><td>787 000</td><td>a couple of months</td></tr>
 * <tr><td>100</td><td>6 000 000</td><td>the long goal: about 260 strong sessions</td></tr>
 * </table>
 *
 * One power law rather than anchors interpolated piecewise, as the tank's table is: piecewise,
 * the growth rate changed at each anchor, and level 11 cost 10 600 xp straight after level 10
 * had cost 35 500 — a kink a player feels as "that was suspiciously quick". A power above 1
 * makes every level cost more than the one before, always.
 *
 * A first cut, like every balance number here: defensible from the tank table, never played.
 * Tuning it is editing two numbers. Nobody loses a level if they are raised: the store keeps
 * the higher of old and new.
 */
public final class AccountLevels {

    public static final int MAX_LEVEL = 100;

    private static final long LEVEL_TWO_XP = 3_000;
    private static final long MAX_LEVEL_XP = 6_000_000;
    private static final double POWER =
            Math.log((double) MAX_LEVEL_XP / LEVEL_TWO_XP) / Math.log(MAX_LEVEL - 1);

    /** Indexed by level: the lifetime xp at which it is reached. [0] is unused, [1] is 0. */
    private static final long[] XP_REQUIRED = build();

    public static long xpRequired(int level) {
        return XP_REQUIRED[level];
    }

    /** The highest level whose requirement {@code xp} meets; 1 for nothing at all. */
    public static int levelFor(long xp) {
        if (xp <= 0) {
            return 1;
        }
        int i = Arrays.binarySearch(XP_REQUIRED, 1, MAX_LEVEL + 1, xp);
        return i >= 0 ? i : -i - 2;           // exact match, or the last level below xp
    }

    private static long[] build() {
        long[] xp = new long[MAX_LEVEL + 1];
        for (int level = 2; level <= MAX_LEVEL; level++) {
            xp[level] = Math.round(LEVEL_TWO_XP * Math.pow(level - 1, POWER));
        }
        return xp;
    }

    private AccountLevels() {
    }
}
