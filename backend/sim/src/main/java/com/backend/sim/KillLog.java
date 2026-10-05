package com.backend.sim;

/**
 * What died this tick, and to whom it is credited.
 *
 * The simulation records kills; it does not score them. Scoring needs to know what a player
 * is, and the simulation deliberately does not — it has no accounts, no connections and no
 * protocol. The room thread drains this list each tick and does the arithmetic.
 *
 * Parallel arrays rather than objects, because the tick loop allocates nothing in steady
 * state and a kill is four numbers.
 */
public final class KillLog {

    /** More kills in a single tick than this means something is wrong, so stop recording. */
    private static final int MAX = 4096;

    private long[] killerTags = new long[64];
    private long[] victimTags = new long[64];
    private byte[] victimKinds = new byte[64];
    private int[] xpAwarded = new int[64];

    private int size;

    public int size() {
        return size;
    }

    /** 0 when the killer was not a player — a bot tank or its bullet, or a shape. */
    public long killerTag(int i) {
        return killerTags[i];
    }

    /** 0 when the victim was not a player — a shape, or a bot tank. */
    public long victimTag(int i) {
        return victimTags[i];
    }

    public byte victimKind(int i) {
        return victimKinds[i];
    }

    /**
     * What this kill was worth in experience.
     *
     * Carried rather than recomputed by the reader, because the value depends on what died —
     * an alpha pentagon is worth three hundred squares — and the only thing that still knows
     * that is the collision that resolved it. A reader given the kind alone would have to
     * keep a second copy of the shape table in step with this one.
     */
    public int xp(int i) {
        return xpAwarded[i];
    }

    void record(long killerTag, long victimTag, byte victimKind, int xp) {
        if (size == killerTags.length && !grow()) {
            return;
        }
        killerTags[size] = killerTag;
        victimTags[size] = victimTag;
        victimKinds[size] = victimKind;
        xpAwarded[size] = xp;
        size++;
    }

    private long[] assisterTags = new long[16];
    private long[] assistVictimTags = new long[16];
    private int[] assistXp = new int[16];
    private int assists;

    /** Assists this tick (01 §7): one who hurt a tank shortly before another killed it. */
    public int assists() {
        return assists;
    }

    public long assisterTag(int i) {
        return assisterTags[i];
    }

    /** 0 when the tank killed was not a player's. */
    public long assistVictimTag(int i) {
        return assistVictimTags[i];
    }

    /** What the assist paid: 0 when the assister's tank was gone by the death. */
    public int assistXp(int i) {
        return assistXp[i];
    }

    void recordAssist(long assisterTag, long victimTag, int xp) {
        if (assists == assisterTags.length) {
            int next = assists * 2;
            if (next > MAX) {
                return;
            }
            assisterTags = java.util.Arrays.copyOf(assisterTags, next);
            assistVictimTags = java.util.Arrays.copyOf(assistVictimTags, next);
            assistXp = java.util.Arrays.copyOf(assistXp, next);
        }
        assisterTags[assists] = assisterTag;
        assistVictimTags[assists] = victimTag;
        assistXp[assists] = xp;
        assists++;
    }

    /** Called by the room thread once it has read the tick's kills. */
    public void clear() {
        size = 0;
        assists = 0;
    }

    private boolean grow() {
        int next = killerTags.length * 2;
        if (next > MAX) {
            return false;
        }
        long[] k = new long[next];
        long[] v = new long[next];
        byte[] kinds = new byte[next];
        int[] xps = new int[next];
        System.arraycopy(killerTags, 0, k, 0, size);
        System.arraycopy(victimTags, 0, v, 0, size);
        System.arraycopy(victimKinds, 0, kinds, 0, size);
        System.arraycopy(xpAwarded, 0, xps, 0, size);
        killerTags = k;
        victimTags = v;
        victimKinds = kinds;
        xpAwarded = xps;
        return true;
    }
}
