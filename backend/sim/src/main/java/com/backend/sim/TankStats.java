package com.backend.sim;

/**
 * One tank's progression and its effective stats (docs detailed-design/01-arena.md §3).
 *
 * <h2>One per pool slot, never per spawn</h2>
 *
 * {@link World} keeps one of these beside each entity slot and hands it back reset, exactly
 * as it does with the entity itself. A tank that dies and respawns reuses the same object,
 * so a room that has been running for an hour allocates nothing here.
 *
 * <h2>Progression is per life, score is per match</h2>
 *
 * Dying costs the level and the points: this resets. What it does *not* reset is the
 * player's match score, which lives in the arena's tally and keeps counting across lives.
 * The two are different questions — "how strong am I right now" and "how well has this stay
 * gone" — and answering both with one number would mean either death costing nothing or a
 * leaderboard that forgets everything a player did before their last mistake.
 */
public final class TankStats {

    public int level = 1;
    /** Experience in this life. Reset on death, unlike the match score. */
    public int xp;
    public int unspentPoints;
    /** Its class in {@link ClassTable}: Basic until one is chosen, and again after a death. */
    public int classId = ClassTable.BASIC;
    /** How the tank is drawn (04 §8, D-70): its skin's number from the player's ticket, 0 for none. */
    public int skin;

    public final byte[] points = new byte[Stat.COUNT];

    /**
     * The last players who hurt it, for assists (01 §7): each one's tag, the slot and generation
     * of their tank, and the tick of their last hit; a tag of 0 an empty place. Here, with what
     * only a tank has, so no bullet or shape carries them.
     */
    public final long[] attackerTags = new long[Room.ATTACKERS];
    public final int[] attackerIds = new int[Room.ATTACKERS];
    public final short[] attackerGenerations = new short[Room.ATTACKERS];
    public final int[] attackerTicks = new int[Room.ATTACKERS];

    private final float[] effective = new float[Stat.COUNT];
    /** Equipment's bonus a stat, a whole percent (01 §3, D-37): set at spawn from the player's ticket. */
    private final byte[] bonus = new byte[Stat.COUNT];
    private boolean dirty = true;

    /** Becomes what {@code other} was: a tank taken out of the world and put back in (02 §10). */
    public void copyFrom(TankStats other) {
        level = other.level;
        xp = other.xp;
        unspentPoints = other.unspentPoints;
        classId = other.classId;
        System.arraycopy(other.points, 0, points, 0, points.length);
        System.arraycopy(other.bonus, 0, bonus, 0, bonus.length);
        skin = other.skin;
        dirty = true;
    }

    /** Back to a fresh level-1 tank. Called when a slot is handed out, not when one dies. */
    void reset() {
        java.util.Arrays.fill(attackerTags, 0L);    // nobody has hurt it yet (01 §7)
        restart();
        java.util.Arrays.fill(bonus, (byte) 0);
        skin = 0;
    }

    /** Back to level 1 with nothing spent, Basic, still wearing what it wore (01 §8.10). */
    void restart() {
        level = 1;
        xp = 0;
        unspentPoints = 0;
        classId = ClassTable.BASIC;
        java.util.Arrays.fill(points, (byte) 0);
        dirty = true;
    }

    /**
     * Recomputes the effective stats if anything has changed. Cheap enough to call every
     * tick for every tank: in the ordinary case it is one boolean test.
     */
    public void refresh(StatTable table) {
        if (!dirty) {
            return;
        }
        for (int s = 0; s < Stat.COUNT; s++) {
            float v = table.valueOf(s, level, points[s]);
            if (bonus[s] != 0) {
                // Better by the percent: reload is ticks between shots, so fewer of them.
                float by = 1f + bonus[s] / 100f;
                v = s == Stat.RELOAD ? v / by : v * by;
            }
            effective[s] = v;
        }
        dirty = false;
    }

    /** What the player wears, a whole percent a stat, 0 to 25 each: capped before it gets here. */
    public void setBonus(byte[] percents) {
        System.arraycopy(percents, 0, bonus, 0, Stat.COUNT);
        dirty = true;
    }

    /**
     * The value of a stat as of the last {@link #refresh}.
     *
     * Deliberately not self-refreshing. A getter that could recompute would put a branch —
     * and, worse, an eight-stat loop — inside whichever inner loop happened to call it
     * first, and the cost would move around as the code changed.
     */
    public float value(int stat) {
        return effective[stat];
    }

    /**
     * Spends one unspent point.
     *
     * @param cap the most points the tank's class allows in the stat (01 §4)
     * @return false when there is nothing to spend, the stat is already capped, or the
     *         index is not a stat. All three are things a client can ask for, and none of
     *         them is worth closing a connection over.
     */
    public boolean spendPoint(int stat, int cap) {
        if (!Stat.isValid(stat) || unspentPoints <= 0 || points[stat] >= cap) {
            return false;
        }
        points[stat]++;
        unspentPoints--;
        dirty = true;
        return true;
    }

    /**
     * Awards experience and levels up as far as it reaches.
     *
     * A loop rather than a single step because one kill can be worth several levels — an
     * alpha pentagon is worth three thousand, which takes a level-1 tank a long way in one
     * go — and a single step would silently discard the rest.
     *
     * @return the number of levels gained, for the caller to announce.
     */
    public int addXp(int amount, LevelTable levels) {
        if (amount <= 0) {
            return 0;
        }
        xp += amount;
        int gained = 0;
        while (level < levels.maxLevel() && xp >= levels.xpRequired(level + 1)) {
            level++;
            unspentPoints += levels.pointsAt(level);
            gained++;
        }
        if (gained > 0) {
            dirty = true;                       // max health moves with level
        }
        return gained;
    }

    /** Test and diagnostic access; production code reads {@link #value}. */
    boolean isDirty() {
        return dirty;
    }
}
