package com.backend.arena;

import com.backend.sim.ClassTable;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.World;

/**
 * Co-op's waves (docs detailed-design/01-arena.md §8.5): the arena's own hunting tanks, a wave
 * at a time, against the players' team. Wave <i>w</i> is 2 + <i>w</i> tanks of team 2 at level
 * 5<i>w</i>, 45 at most, grown as the benchmark grows a bot; it comes {@code delayTicks} after
 * the match starts, and after the last tank of the one before dies. Ten, then it is over. Waves
 * 5 and 10 bring one boss instead: a Guardian of level 45, enraged below half its health (Q-28).
 *
 * Room thread only: its tanks are the room's.
 */
final class Waves {

    static final int WAVES = 10;
    /** Five seconds at 25 Hz: the first comes this long after the start, each next after the last cleared. */
    static final int DELAY_TICKS = 5 * 25;
    /** What a wave cleared adds to the score of each player of the team still in the match (Q-37). */
    static final int CLEARED_SCORE = 100;
    static final byte TEAM = 2;
    /** A boss's level: a wave's tanks' most. */
    static final int BOSS_LEVEL = 45;

    private final Room room;
    private final int delayTicks;
    /** The wave out now, or the last; 0 before the first. */
    private int wave;
    /** When the next wave comes; -1 while one is out. */
    private int nextAtTick;

    Waves(Room room, int delayTicks) {
        this.room = room;
        this.delayTicks = delayTicks;
        this.nextAtTick = room.tick() + delayTicks;
    }

    /** One tick, after the room's step. @return whether a wave came this tick */
    boolean tick() {
        if (nextAtTick < 0) {
            enrage(room);
            if (wave < WAVES && huntersAlive(room) == 0) {
                nextAtTick = room.tick() + delayTicks;          // cleared: the next after the delay
            }
            return false;
        }
        if (room.tick() < nextAtTick) {
            return false;
        }
        wave++;
        nextAtTick = -1;
        if (wave % 5 == 0) {
            summonGuardian(room);
            return true;
        }
        int level = Math.min(room.content().levels().maxLevel(), 5 * wave);
        int xp = room.content().levels().xpRequired(level);
        for (int i = 0; i < 2 + wave; i++) {
            Entity t = room.spawnTank(TEAM);
            if (t == null) {
                break;                                          // the world is full: a smaller wave
            }
            t.hunts = true;
            room.grantExperience(t, xp);
        }
        return true;
    }

    int wave() {
        return wave;
    }

    /** Waves whose every tank is dead. */
    int cleared() {
        return nextAtTick >= 0 || huntersAlive(room) == 0 ? wave : wave - 1;
    }

    /** The tenth wave cleared: the players have won. */
    boolean over() {
        return wave == WAVES && cleared() == WAVES;
    }

    /** A Guardian, hunting, of the arena's own: a boss wave's, or a sandbox's (01 §8.10). Null with the world full. */
    static Entity summonGuardian(Room room) {
        Entity boss = room.spawnTank(TEAM);
        if (boss != null) {
            boss.hunts = true;
            // The class first, then the level: grown first, a bot's points went by the classes it drew on the way,
            // some into speed always and, a boss in seven, none into its bullets; the boss's caps never held (M-18).
            room.assignClass(boss, ClassTable.GUARDIAN);
            room.grantExperience(boss, room.content().levels().xpRequired(
                    Math.min(room.content().levels().maxLevel(), BOSS_LEVEL)));
        }
        return boss;
    }

    /** A Guardian below half its health becomes the enraged one: the same health, twice the fire. */
    static void enrage(Room room) {
        World w = room.world();
        for (int i = 0; i < w.tanks.size; i++) {
            Entity t = w.entities[w.tanks.items[i]];
            if (t.alive && t.hunts && t.hp < t.maxHp / 2
                    && w.tankStats[t.id].classId == ClassTable.GUARDIAN) {
                room.assignClass(t, ClassTable.GUARDIAN_ENRAGED);
            }
        }
    }

    static int huntersAlive(Room room) {
        World w = room.world();
        int alive = 0;
        for (int i = 0; i < w.tanks.size; i++) {
            Entity t = w.entities[w.tanks.items[i]];
            alive += t.alive && t.hunts ? 1 : 0;
        }
        return alive;
    }
}
