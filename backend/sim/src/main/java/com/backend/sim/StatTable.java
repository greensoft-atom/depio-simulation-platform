package com.backend.sim;

/**
 * What each stat is worth (docs detailed-design/01-arena.md §3).
 *
 * <h2>Two shapes of growth, not an expression language</h2>
 *
 * Six of the eight stats grow by addition and two by proportion, so there are two modes and
 * one formula each. A general modifier language would be the obvious next step and would be
 * wrong here: nothing needs it, and balance data that can express anything is balance data
 * nobody can reason about.
 *
 * <pre>
 *   ADD   value = base + perLevel·level + perPoint·points
 *   SCALE value = base · (1 + perPoint·points)
 * </pre>
 *
 * {@code RELOAD} is the reason {@code SCALE} exists: fewer ticks between shots is better, so
 * its per-point value is negative and a flat subtraction would reach zero and then go
 * through it. A proportion cannot.
 *
 * <h2>Where equipment plugs in</h2>
 *
 * Equipment's bonus is applied in {@link TankStats#refresh}, between this table and the
 * effective value ({@code TankStats.setBonus}, 04 §8) — not here. This table is what a stat is
 * worth *before* anything is worn.
 */
public final class StatTable {

    /** {@code base + perLevel·level + perPoint·points}. */
    public static final byte ADD = 0;
    /** {@code base · (1 + perPoint·points)}. */
    public static final byte SCALE = 1;

    /** One stat's growth. */
    public record Entry(float base, float perLevel, float perPoint, byte mode) { }

    private final Entry[] entries;

    public StatTable(Entry[] entries) {
        if (entries.length != Stat.COUNT) {
            throw new IllegalArgumentException(
                    "expected " + Stat.COUNT + " stats, got " + entries.length);
        }
        this.entries = entries.clone();
    }

    public Entry entry(int stat) {
        return entries[stat];
    }

    /** The value of {@code stat} for a tank at {@code level} with {@code points} spent in it. */
    public float valueOf(int stat, int level, int points) {
        Entry e = entries[stat];
        return e.mode() == SCALE
                ? e.base() * (1f + e.perPoint() * points)
                : e.base() + e.perLevel() * level + e.perPoint() * points;
    }

    /**
     * The shipped balance.
     *
     * <h2>These numbers are a starting point, not a result</h2>
     *
     * They follow the design document, which in turn follows diep.io's shape, because
     * starting from a set of numbers known to produce a playable match beats starting from
     * numbers invented here. They have never been played. Every one of them should move once
     * somebody has actually played a match, and the reason they live in a table is so that
     * moving them is a data change rather than a release.
     */
    public static StatTable defaults() {
        Entry[] e = new Entry[Stat.COUNT];
        // Regen is a fraction of max health per tick: 0.0003 is 0.75 % a second, so a full
        // heal on the stat alone takes over two minutes (Recovery's burst cuts that short).
        e[Stat.HEALTH_REGEN] = new Entry(0.0003f, 0f, 0.6f, SCALE);
        e[Stat.MAX_HEALTH] = new Entry(50f, 2f, 20f, ADD);
        e[Stat.BODY_DAMAGE] = new Entry(20f, 0f, 6f, ADD);
        // A multiplier on the barrel's speed rather than a speed, so a future class with a
        // slow heavy barrel scales the same way as a fast one.
        e[Stat.BULLET_SPEED] = new Entry(1f, 0f, 0.15f, ADD);
        // A guess the design document left open ("+?"). Penetration is a bullet's own
        // health, so each point is 4 more of it to wear through before the bullet is spent.
        e[Stat.BULLET_PENETRATION] = new Entry(8f, 0f, 4f, ADD);
        e[Stat.BULLET_DAMAGE] = new Entry(7f, 0f, 3f, ADD);
        // 8 ticks at 25 Hz is about three shots a second; seven points takes it to 3.5.
        e[Stat.RELOAD] = new Entry(8f, 0f, -0.08f, SCALE);
        e[Stat.MOVEMENT_SPEED] = new Entry(1f, 0f, 0.07f, ADD);
        return new StatTable(e);
    }
}
