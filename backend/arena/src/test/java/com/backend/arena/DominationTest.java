package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.backend.common.PhaseTimer;
import com.backend.sim.ClassTable;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.Stat;
import com.backend.sim.TankStats;
import com.backend.sim.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domination's dominators and its clock (01 §8.7, Q-29). */
class DominationTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static List<Entity> dominators(Room r) {
        List<Entity> out = new ArrayList<>();
        World w = r.world();
        for (int i = 0; i < w.tanks.size; i++) {
            Entity t = w.entities[w.tanks.items[i]];
            if (t.alive && t.anchored) {
                out.add(t);
            }
        }
        out.sort(java.util.Comparator.comparingDouble(t -> t.y));
        return out;
    }

    private static void run(Room r, Domination d, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
            d.tick();
        }
    }

    @Test
    @DisplayName("a dominator's points follow its class's caps, every one as armed as the next (M-18)")
    void aDominatorsPointsFollowItsCaps() {
        for (long seed = 1; seed <= 30; seed++) {
            Room r = new Room(new World(6_000f, 6_000f, 1_024, 100f, seed));
            new Domination(r);
            for (Entity d : dominators(r)) {
                byte[] points = r.world().tankStats[d.id].points;
                assertThat(points[com.backend.sim.Stat.MOVEMENT_SPEED]).as("seed %d", seed).isZero();
                assertThat(points[com.backend.sim.Stat.BULLET_DAMAGE] + points[com.backend.sim.Stat.RELOAD]
                        + points[com.backend.sim.Stat.BULLET_SPEED] + points[com.backend.sim.Stat.BULLET_PENETRATION])
                        .as("seed %d", seed).isPositive();
            }
        }
    }

    @Test
    @DisplayName("three dominators on the middle line, neutral, level 45, anchored, captured not killed, eight times the health")
    void placed() {
        Room r = new Room(new World(3_000f, 3_000f, 1_024, 100f, 7L));
        Domination d = new Domination(r);
        r.step(TIMER);
        List<Entity> all = dominators(r);
        assertThat(all).hasSize(Domination.DOMINATORS);
        float[] ys = {750f, 1_500f, 2_250f};
        for (int i = 0; i < all.size(); i++) {
            Entity e = all.get(i);
            TankStats stats = r.world().tankStats[e.id];
            assertThat(e.x).isEqualTo(1_500f);
            assertThat(e.y).isEqualTo(ys[i]);
            assertThat(e.team).as("neutral").isZero();
            assertThat(e.captures).isTrue();
            assertThat(stats.classId).isEqualTo(ClassTable.DOMINATOR);
            assertThat(stats.level).isEqualTo(45);
            assertThat(e.maxHp).isCloseTo(8 * stats.value(Stat.MAX_HEALTH), org.assertj.core.data.Offset.offset(0.01f));
            assertThat(e.playerTag).as("the arena's own").isZero();
        }
        assertThat(d.held()).as("nobody holds any").isEmpty();
        assertThat(d.won()).isFalse();
    }

    @Test
    @DisplayName("what each team holds is counted; all three held for 60 s wins, not a tick sooner, and losing one starts it again")
    void theClock() {
        Room r = new Room(new World(3_000f, 3_000f, 1_024, 100f, 7L));
        Domination d = new Domination(r);
        r.step(TIMER);
        List<Entity> all = dominators(r);
        all.get(0).team = 1;
        all.get(1).team = 1;
        all.get(2).team = 2;
        run(r, d, 1);
        assertThat(d.held()).isEqualTo(Map.of(1, 2, 2, 1));

        all.get(2).team = 1;
        run(r, d, Domination.HOLD_TICKS - 1);
        assertThat(d.won()).as("a tick short").isFalse();
        all.get(1).team = 2;                                   // lost, and taken back
        run(r, d, 1);
        all.get(1).team = 1;
        run(r, d, Domination.HOLD_TICKS - 1);
        assertThat(d.won()).as("the clock started again").isFalse();
        run(r, d, 1);
        assertThat(d.won()).isTrue();
        assertThat(d.held()).isEqualTo(Map.of(1, 3));
        assertThat(Domination.HOLD_TICKS).as("60 s at 25 Hz").isEqualTo(1_500);
    }
}
