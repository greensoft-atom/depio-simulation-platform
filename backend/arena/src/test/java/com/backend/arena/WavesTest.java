package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.backend.common.PhaseTimer;
import com.backend.sim.ClassTable;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.Stat;
import com.backend.sim.TankStats;
import com.backend.sim.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Co-op's waves (01 §8.5): the arena's hunting tanks, a wave at a time. */
class WavesTest {

    private static final PhaseTimer TIMER = Room.newTimer();
    private static final int DELAY = 10;

    private static List<Entity> hunters(Room r) {
        List<Entity> out = new ArrayList<>();
        World w = r.world();
        for (int i = 0; i < w.tanks.size; i++) {
            Entity t = w.entities[w.tanks.items[i]];
            if (t.alive && t.hunts) {
                out.add(t);
            }
        }
        return out;
    }

    /** Steps the room and the waves together, as the room's thread does. @return waves that came */
    private static int run(Room r, Waves waves, int ticks) {
        int came = 0;
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
            came += waves.tick() ? 1 : 0;
        }
        return came;
    }

    private static void killAll(Room r) {
        for (Entity t : hunters(r)) {
            t.alive = false;
        }
    }

    @Test
    @DisplayName("a Guardian's points follow its own caps: none in speed, and its bullets always armed (M-18)")
    void aGuardiansPointsFollowItsCaps() {
        for (long seed = 1; seed <= 30; seed++) {
            Room r = new Room(new World(3_000f, 3_000f, 1_024, 100f, seed));
            Entity boss = Waves.summonGuardian(r);
            byte[] points = r.world().tankStats[boss.id].points;
            assertThat(points[com.backend.sim.Stat.MOVEMENT_SPEED]).as("seed %d: a boss is slow", seed).isZero();
            assertThat(points[com.backend.sim.Stat.BULLET_SPEED] + points[com.backend.sim.Stat.BULLET_PENETRATION]
                    + points[com.backend.sim.Stat.BULLET_DAMAGE] + points[com.backend.sim.Stat.RELOAD])
                    .as("seed %d: not spent under a class drawn on the way that has none in bullets", seed).isPositive();
        }
    }

    @Test
    @DisplayName("the first wave comes after the delay: three hunting tanks of team 2, level 5, in the right third")
    void theFirstWave() {
        Room r = new Room(new World(3_000f, 3_000f, 1_024, 100f, 7L));
        Waves waves = new Waves(r, DELAY);
        assertThat(run(r, waves, DELAY - 1)).isZero();
        assertThat(hunters(r)).isEmpty();
        assertThat(run(r, waves, 1)).isEqualTo(1);
        assertThat(waves.wave()).isEqualTo(1);
        assertThat(hunters(r)).hasSize(3).allSatisfy(t -> {
            assertThat(t.team).isEqualTo((byte) 2);
            assertThat(r.world().tankStats[t.id].level).isEqualTo(5);
            assertThat(t.x).as("the right third").isGreaterThanOrEqualTo(2_000f);
            assertThat(t.playerControlled).isFalse();
        });
    }

    @Test
    @DisplayName("a wave not cleared keeps the next waiting; cleared, the next comes after the delay, one tank more and five levels up")
    void theNextWave() {
        Room r = new Room(new World(3_000f, 3_000f, 1_024, 100f, 7L));
        Waves waves = new Waves(r, DELAY);
        run(r, waves, DELAY);
        List<Entity> first = hunters(r);
        for (int i = 1; i < first.size(); i++) {
            first.get(i).alive = false;
        }
        assertThat(run(r, waves, DELAY * 3)).as("one left").isZero();
        killAll(r);
        assertThat(run(r, waves, DELAY)).isZero();
        assertThat(run(r, waves, 1)).isEqualTo(1);
        assertThat(waves.wave()).isEqualTo(2);
        assertThat(waves.cleared()).isEqualTo(1);
        assertThat(hunters(r)).hasSize(4).allSatisfy(t -> assertThat(r.world().tankStats[t.id].level).isEqualTo(10));
    }

    @Test
    @DisplayName("waves 5 and 10 bring one boss instead of their hunters: a Guardian of level 45, hunting, twelve times a tank's health (Q-28)")
    void bossWaves() {
        Room r = new Room(new World(3_000f, 3_000f, 2_048, 100f, 7L));
        Waves waves = new Waves(r, DELAY);
        for (int w = 1; w <= Waves.WAVES; w++) {
            run(r, waves, DELAY + 2);
            List<Entity> out = hunters(r);
            if (w == 5 || w == 10) {
                assertThat(out).as("wave %d", w).hasSize(1);
                Entity boss = out.get(0);
                TankStats stats = r.world().tankStats[boss.id];
                assertThat(stats.classId).isEqualTo(ClassTable.GUARDIAN);
                assertThat(stats.level).isEqualTo(45);
                assertThat(boss.team).isEqualTo(Waves.TEAM);
                assertThat(boss.maxHp).isCloseTo(12 * stats.value(Stat.MAX_HEALTH), org.assertj.core.data.Offset.offset(0.01f));
                assertThat(boss.hp).isEqualTo(boss.maxHp);
            } else {
                assertThat(out).as("wave %d", w).hasSize(2 + w)
                        .noneSatisfy(t -> assertThat(r.world().tankStats[t.id].classId).isGreaterThanOrEqualTo(ClassTable.GUARDIAN));
            }
            killAll(r);
        }
    }

    @Test
    @DisplayName("a Guardian below half its health becomes the enraged one, and stays so")
    void aBossEnrages() {
        Room r = new Room(new World(3_000f, 3_000f, 2_048, 100f, 7L));
        Waves waves = new Waves(r, DELAY);
        for (int w = 1; w < 5; w++) {
            run(r, waves, DELAY + 2);
            if (w == 4) {
                Entity hunter = hunters(r).get(0);
                int before = r.world().tankStats[hunter.id].classId;
                hunter.hp = hunter.maxHp * 0.4f;
                run(r, waves, 1);
                assertThat(r.world().tankStats[hunter.id].classId).as("only a Guardian enrages").isEqualTo(before);
            }
            killAll(r);
        }
        run(r, waves, DELAY + 2);
        Entity boss = hunters(r).get(0);
        TankStats stats = r.world().tankStats[boss.id];
        boss.hp = boss.maxHp * 0.51f;
        run(r, waves, 1);
        assertThat(stats.classId).as("at half and more, not yet").isEqualTo(ClassTable.GUARDIAN);
        boss.hp = boss.maxHp * 0.49f;
        run(r, waves, 1);
        assertThat(stats.classId).isEqualTo(ClassTable.GUARDIAN_ENRAGED);
        float max = boss.maxHp;
        boss.hp = boss.maxHp;
        run(r, waves, 1);
        assertThat(stats.classId).as("healed, it stays enraged").isEqualTo(ClassTable.GUARDIAN_ENRAGED);
        assertThat(boss.maxHp).isEqualTo(max);
    }

    @Test
    @DisplayName("the tenth wave cleared is the end, and no eleventh comes; levels stop at 45")
    void theTenthIsTheLast() {
        Room r = new Room(new World(3_000f, 3_000f, 2_048, 100f, 7L));
        Waves waves = new Waves(r, DELAY);
        for (int w = 1; w <= Waves.WAVES; w++) {
            run(r, waves, DELAY + 1);
            assertThat(waves.wave()).isEqualTo(w);
            assertThat(waves.over()).isFalse();
            if (w == Waves.WAVES) {
                assertThat(hunters(r)).as("the tenth is a boss's (Q-28)").hasSize(1)
                        .allSatisfy(t -> assertThat(r.world().tankStats[t.id].level).isEqualTo(45));
            }
            killAll(r);
        }
        run(r, waves, DELAY * 3);
        assertThat(waves.over()).isTrue();
        assertThat(waves.cleared()).isEqualTo(Waves.WAVES);
        assertThat(hunters(r)).isEmpty();
    }
}
