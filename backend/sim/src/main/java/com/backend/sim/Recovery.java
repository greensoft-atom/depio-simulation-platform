package com.backend.sim;

/**
 * How a tank gets its health back (01 §3): slowly always, at its stat's rate, and fast once it
 * has been left alone for a while.
 *
 * @param quietTicks how long without taking damage before the fast part starts
 * @param burstPerTick the fast part, as a share of maximum health a tick, on top of the stat
 */
public record Recovery(int quietTicks, float burstPerTick) {

    /**
     * Thirty seconds, as the design has it; then 1 % of maximum health a tick, a full heal in
     * about four seconds. The design names the burst but not its rate, so that number is a
     * starting point like every other in the tables: a tank that got away should come back
     * ready, and one still in the fight should not.
     */
    public static Recovery defaults() {
        return new Recovery(30 * 25, 0.01f);
    }
}
