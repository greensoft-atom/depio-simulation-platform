package com.backend.arena;

import java.util.HashMap;
import java.util.Map;

import com.backend.sim.ClassTable;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.World;

/**
 * Domination's dominators and its clock (docs detailed-design/01-arena.md §8.7, Q-29): three
 * of the arena's own tanks on the map's middle line, neutral at the start, anchored, and captured
 * by a player's lethal blow instead of killed; a team holding all three for 60 s wins.
 *
 * Room thread only: its tanks are the room's.
 */
final class Domination {

    static final int DOMINATORS = 3;
    /** 60 s at 25 Hz: how long a team must hold every dominator to win at once. */
    static final int HOLD_TICKS = 60 * 25;
    /** A dominator's level: a tank's most. */
    static final int LEVEL = 45;

    private final Room room;
    private final Entity[] dominators = new Entity[DOMINATORS];
    /** The team holding all of them, and since when; 0 while nobody does. */
    private int holder;
    private int heldSinceTick;
    private boolean won;

    Domination(Room room) {
        this.room = room;
        World w = room.world();
        for (int i = 0; i < DOMINATORS; i++) {
            Entity d = room.spawnTank((byte) 0);
            if (d == null) {
                continue;                                      // the world is full: fewer to fight over
            }
            room.assignClass(d, ClassTable.DOMINATOR);                    // first, as a boss's (M-18)
            room.grantExperience(d, room.content().levels().xpRequired(
                    Math.min(room.content().levels().maxLevel(), LEVEL)));
            d.anchored = true;
            d.captures = true;
            d.x = w.width / 2;
            d.y = w.height * (i + 1) / (DOMINATORS + 1);
            dominators[i] = d;
        }
    }

    /** One tick, after the room's step: who holds what, and the clock of whoever holds all. */
    void tick() {
        int all = 0;
        for (Entity d : dominators) {
            if (d == null || d.team == 0 || (all != 0 && d.team != all)) {
                all = -1;
                break;
            }
            all = d.team;
        }
        if (all <= 0) {
            holder = 0;
        } else if (all != holder) {
            holder = all;
            heldSinceTick = room.tick();
        } else if (room.tick() - heldSinceTick >= HOLD_TICKS - 1) {
            won = true;
        }
    }

    /** A team has held every dominator for 60 s. */
    boolean won() {
        return won;
    }

    /** The dominators each team holds, neutral ones not counted. */
    Map<Integer, Integer> held() {
        Map<Integer, Integer> out = new HashMap<>();
        for (Entity d : dominators) {
            if (d != null && d.team != 0) {
                out.merge((int) d.team, 1, Integer::sum);
            }
        }
        return out;
    }
}
