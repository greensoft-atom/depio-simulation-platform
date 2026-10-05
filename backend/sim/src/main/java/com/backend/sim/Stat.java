package com.backend.sim;

/**
 * The eight upgradeable stats (docs detailed-design/01-arena.md §3).
 *
 * Indices, not an enum, because they are array subscripts in the hot path and on the wire.
 * An enum here would mean {@code ordinal()} calls and a values() copy in code that runs
 * every tick for every tank.
 *
 * The order is a wire contract: the client sends "upgrade stat 5" and both sides must agree
 * which one that is. Append, never reorder.
 */
public final class Stat {

    /** Fraction of max health regained per tick. */
    public static final int HEALTH_REGEN = 0;
    public static final int MAX_HEALTH = 1;
    /** Damage dealt by driving into something. */
    public static final int BODY_DAMAGE = 2;
    /** Multiplies the barrel's bullet speed. */
    public static final int BULLET_SPEED = 3;
    /** A bullet's own health: how much it survives before dying. */
    public static final int BULLET_PENETRATION = 4;
    public static final int BULLET_DAMAGE = 5;
    /** Ticks between shots. Lower is better, so its per-point value is negative. */
    public static final int RELOAD = 6;
    /** Multiplies acceleration. */
    public static final int MOVEMENT_SPEED = 7;

    public static final int COUNT = 8;

    /**
     * Points allowed in one stat, unless the tank's class sets its own (a Smasher's is 10 in the
     * body's stats and 0 in the bullets', 01 §4).
     *
     * A cap is what stops a build being "everything into damage": with 33 points and a cap
     * of 7, a player can max at most four stats and must give something up.
     */
    public static final int MAX_POINTS_PER_STAT = 7;

    private static final String[] NAMES = {
            "health regen", "max health", "body damage", "bullet speed",
            "bullet penetration", "bullet damage", "reload", "movement speed",
    };

    public static String name(int stat) {
        return NAMES[stat];
    }

    public static boolean isValid(int stat) {
        return stat >= 0 && stat < COUNT;
    }

    private Stat() {
    }
}
