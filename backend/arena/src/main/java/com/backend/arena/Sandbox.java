package com.backend.arena;

import com.backend.protocol.ClientMessage;
import com.backend.sim.Entity;
import com.backend.sim.Room;

/**
 * The sandbox (docs detailed-design/01-arena.md §8.10, Q-32): what its players may do to their own
 * tanks, and the Guardian they may summon. Its room's lifecycle, the clock and an empty room's
 * end, and publishing nothing, are {@link RoomThread}'s. Room thread only.
 */
final class Sandbox {

    private final Room room;

    Sandbox(Room room) {
        this.room = room;
    }

    /** One player's request, for their own tank, alive: anything out of range does nothing. */
    void apply(Entity tank, int action, int value) {
        if (action == ClientMessage.SANDBOX_LEVEL) {
            room.setLevel(tank, value);                     // refuses a level outside the table
        } else if (action == ClientMessage.SANDBOX_GUARDIAN && Waves.huntersAlive(room) == 0) {
            Waves.summonGuardian(room);                     // one at a time: nothing else hunts here
        }
    }

    /** One tick, after the room's step: a Guardian below half its health is enraged, as in co-op. */
    void tick() {
        Waves.enrage(room);
    }
}
