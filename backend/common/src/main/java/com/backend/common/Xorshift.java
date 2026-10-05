package com.backend.common;

/**
 * xorshift128+, one instance per room. Not thread-safe by design: a room's RNG is
 * touched only by its own thread, and sharing one would make replays non-deterministic.
 */
public final class Xorshift {

    private long s0, s1;

    public Xorshift(long seed) {
        // SplitMix64 to spread a weak seed across both words.
        s0 = mix(seed);
        s1 = mix(s0);
        if (s0 == 0 && s1 == 0) {
            s1 = 1;                       // the all-zero state is a fixed point
        }
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        long x = s0;
        final long y = s1;
        s0 = y;
        x ^= x << 23;
        s1 = x ^ y ^ (x >>> 17) ^ (y >>> 26);
        return s1 + y;
    }

    /** Uniform in [0, bound). */
    public int nextInt(int bound) {
        return Math.floorMod(nextLong(), bound);
    }

    /** Uniform in [0, 1). */
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    public float nextFloat(float min, float max) {
        return min + nextFloat() * (max - min);
    }
}
