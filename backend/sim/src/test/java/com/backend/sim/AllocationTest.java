package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The simulation allocates nothing in steady state (docs detailed-design/07 §2).
 *
 * Held by a test, not only measured by the benchmark: until M-14 nothing but the benchmark
 * looked, and six parts of the third class tier went by before it was run and showed a tick
 * allocating every time.
 */
class AllocationTest {

    private static final int TANKS = 100;

    /** Keeps the room full of level-45 bots, as the benchmark does: every class of the tree, in play. */
    private static void topUp(Room r) {
        while (r.world().tanks.size < TANKS) {
            Entity bot = r.spawnTank((byte) 0);
            r.grantExperience(bot, r.content().levels().xpRequired(45));
        }
    }

    @Test
    @DisplayName("a room of level-45 bots of every class allocates nothing, tick after tick")
    void steadyStateAllocatesNothing() {
        World w = new World(4_000f, 4_000f, 8_192, 200f, 7L);
        Room r = new Room(w);
        for (int i = 0; i < 1_000; i++) {
            r.spawnShape();
        }
        PhaseTimer timer = Room.newTimer();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 1_500; i++) {                   // warm: pools and lists grown, code compiled
            topUp(r);
            r.step(timer);
        }
        int allocating = 0;
        long bytes = 0;
        int ticks = 1_500;
        for (int i = 0; i < ticks; i++) {
            topUp(r);
            long before = mx.getCurrentThreadAllocatedBytes();
            r.step(timer);
            long spent = mx.getCurrentThreadAllocatedBytes() - before;
            if (spent > 0) {
                allocating++;
                bytes += spent;
            }
        }
        // A list growing to a new most, once, is allowed; anything a tick does every time is not.
        assertThat(allocating).as("ticks that allocated, of %d: %d bytes in all", ticks, bytes)
                .isLessThanOrEqualTo(ticks / 100);
    }
}
