package com.backend.sim;

/**
 * How much experience a level costs, and what reaching it grants
 * (docs detailed-design/01-arena.md §4).
 *
 * <h2>Cumulative, not per level</h2>
 *
 * {@code xpRequired(n)} is the total a tank must have accumulated to be level {@code n}, so
 * a player's experience and their score are the same running number. Storing the increments
 * instead would mean keeping a second total in step with the first, and the two would
 * eventually disagree.
 */
public final class LevelTable {

    private final int[] xpRequired;        // indexed by level; [0] and [1] are 0
    private final byte[] pointsAt;         // skill points granted on reaching this level

    public LevelTable(int[] xpRequired, byte[] pointsAt) {
        if (xpRequired.length != pointsAt.length || xpRequired.length < 2) {
            throw new IllegalArgumentException("xp and points tables must match and cover level 1");
        }
        this.xpRequired = xpRequired.clone();
        this.pointsAt = pointsAt.clone();
    }

    public int maxLevel() {
        return xpRequired.length - 1;
    }

    /** Total experience needed to be this level. 0 for level 1, and for anything below it. */
    public int xpRequired(int level) {
        if (level <= 1) {
            return 0;
        }
        return level > maxLevel() ? Integer.MAX_VALUE : xpRequired[level];
    }

    /** Skill points granted on reaching this level. */
    public int pointsAt(int level) {
        return level < 1 || level > maxLevel() ? 0 : pointsAt[level];
    }

    /** Every point a tank will have been granted by the time it is {@code level}. */
    public int totalPointsBy(int level) {
        int total = 0;
        for (int l = 1; l <= Math.min(level, maxLevel()); l++) {
            total += pointsAt[l];
        }
        return total;
    }

    /**
     * The shipped curve.
     *
     * <h2>Where the numbers come from, honestly</h2>
     *
     * The design document gives four anchors and no formula: level 2 at 4 experience, 10 at
     * 160, 30 at 5 300 and 45 at 23 000. No single power law passes through all four — a
     * curve fitted to the first two undershoots level 30 by nearly three times — so this
     * interpolates between the anchors geometrically, which is what a designer sketching a
     * curve through known points would do.
     *
     * The anchors are hit exactly and the levels between them are smooth. That is the whole
     * claim. Whether the resulting pace is *good* is a question for somebody who has played
     * a match, and the table exists so that answering it changes one table, not the code
     * around it.
     */
    public static LevelTable defaults() {
        final int maxLevel = 45;
        int[] anchorLevel = {2, 10, 30, 45};
        int[] anchorXp = {4, 160, 5_300, 23_000};

        int[] xp = new int[maxLevel + 1];
        for (int level = 2; level <= maxLevel; level++) {
            xp[level] = interpolate(level, anchorLevel, anchorXp);
        }
        // A level that costs no more than the one before it could be gained twice over from
        // one kill, so nudge each clear of its predecessor. With the anchors above this
        // never fires — the raw interpolation is already strictly increasing, measured over
        // every level — so it is a guard for other tables, not for this one. Said plainly
        // because the comment here used to claim it was load-bearing, and it is not.
        for (int level = 3; level <= maxLevel; level++) {
            if (xp[level] <= xp[level - 1]) {
                xp[level] = xp[level - 1] + 1;
            }
        }

        byte[] points = new byte[maxLevel + 1];
        // One per level to 28, then one every third level. 27 + 6 = 33 points at level 45,
        // against a cap of 7 in any one stat: enough to max four stats, not enough for five.
        for (int level = 2; level <= 28; level++) {
            points[level] = 1;
        }
        for (int level = 30; level <= maxLevel; level += 3) {
            points[level] = 1;
        }
        return new LevelTable(xp, points);
    }

    /** Geometric interpolation: linear in the logarithm, so the curve keeps bending upward. */
    private static int interpolate(int level, int[] anchorLevel, int[] anchorXp) {
        for (int i = 0; i < anchorLevel.length; i++) {
            if (level == anchorLevel[i]) {
                return anchorXp[i];                 // anchors are exact, never rounded through
            }
        }
        int i = 0;
        while (i < anchorLevel.length - 2 && level > anchorLevel[i + 1]) {
            i++;
        }
        double t = (double) (level - anchorLevel[i]) / (anchorLevel[i + 1] - anchorLevel[i]);
        double lo = Math.log(anchorXp[i]);
        double hi = Math.log(anchorXp[i + 1]);
        return (int) Math.round(Math.exp(lo + t * (hi - lo)));
    }
}
