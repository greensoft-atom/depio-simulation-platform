package com.backend.sim;

/**
 * Where a tank arrives, and how long it is safe there (01 §7).
 *
 * @param protectionTicks how long a new tank takes no damage, deals none and cannot shoot
 * @param clearance how far from every other tank a spawn point is sought
 * @param attempts how many random points are tried before settling for the best of them
 */
public record Spawning(int protectionTicks, float clearance, int attempts) {

    /** Three seconds, 500 units and twenty tries, as the design has them. */
    public static Spawning defaults() {
        return new Spawning(3 * 25, 500f, 20);
    }
}
