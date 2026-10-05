package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What the arena's report at shutdown measures: the simulation apart from the whole tick (07 §4, O-3). */
class RoomThreadTimersTest {

    @Test
    @Timeout(30)
    @DisplayName("the simulation's total is the step alone; the whole tick, everything else in it, is timed apart")
    void simulationApartFromTheWholeTick() throws Exception {
        RoomRegistry registry = new RoomRegistry(1_000f, 256, 2, 0, 1);
        RoomThread room = null;
        try {
            registry.allocate();
            room = registry.rooms().get(0);
            // Work the tick does outside the simulation, as sending the snapshots is: 20 ms of it.
            room.tickHook = () -> LockSupport.parkNanos(20_000_000L);
            Thread.sleep(1_000);
        } finally {
            registry.close();                       // the rooms stopped: their histograms are ours to read
        }
        assertThat(room.tickTimer().totalP99Millis()).as("the whole tick").isGreaterThanOrEqualTo(20.0);
        assertThat(room.simTimer().totalP99Millis()).as("the simulation alone, an empty room's")
                .isGreaterThan(0.0).isLessThan(20.0);

        // And the report at shutdown says which is which, each against its own budget.
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
        ArenaMain.reportRooms(new java.io.PrintStream(text, true, java.nio.charset.StandardCharsets.UTF_8),
                java.util.List.of(room));
        String report = text.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(report).contains("=== room-1 simulation tick ===", "budget 2.0 ms",
                "=== room-1 whole tick: simulation, snapshots and the rest ===", "budget 15.0 ms");
        assertThat(report.substring(report.indexOf("whole tick"))).as("the whole tick, 20 ms and more, fails its budget")
                .contains("FAIL");
    }
}
