package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a tank arrives, and its first three seconds (01 §7). A tank used to arrive anywhere,
 * which with body damage could mean inside an alpha pentagon or against a tank ten levels up,
 * and could be shot the tick it arrived.
 */
class SpawnTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    private static float nearestTank(World w, Entity t) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < w.tanks.size; i++) {
            Entity o = w.entities[w.tanks.items[i]];
            if (o != t) {
                best = Math.min(best, (float) Math.hypot(o.x - t.x, o.y - t.y));
            }
        }
        return best;
    }

    @Test
    @DisplayName("a tank arrives clear of every other tank, when the map has room")
    void spawnsClearOfTanks() {
        Room r = new Room(new World(6_000f, 6_000f, 1_024, 200f, 1L));
        for (int i = 0; i < 20; i++) {
            Entity t = r.spawnTank((byte) 0);
            assertThat(nearestTank(r.world(), t)).as("tank %d", i)
                    .isGreaterThanOrEqualTo(r.content().spawning().clearance());
        }
    }

    @Test
    @DisplayName("a tank never arrives inside a shape")
    void spawnsClearOfShapes() {
        Room r = new Room(new World(3_000f, 3_000f, 2_048, 200f, 2L));
        for (int i = 0; i < 600; i++) {
            r.spawnShape();
        }
        r.step(TIMER);                              // the hash the spawn consults
        for (int i = 0; i < 20; i++) {
            Entity t = r.spawnTank((byte) 0);
            for (int j = 0; j < r.world().shapes.size; j++) {
                Entity s = r.world().entities[r.world().shapes.items[j]];
                assertThat(Math.hypot(s.x - t.x, s.y - t.y)).as("tank %d against shape %d", i, s.id)
                        .isGreaterThan(s.radius + t.radius);
            }
        }
    }

    @Test
    @DisplayName("a map with no clear place still takes a tank, as far from the others as it can")
    void crowdedMapStillSpawns() {
        Room r = new Room(new World(800f, 800f, 256, 200f, 3L));
        for (int i = 0; i < 30; i++) {
            assertThat(r.spawnTank((byte) 0)).isNotNull();
        }
    }

    @Test
    @DisplayName("protection lasts exactly the configured number of steps, not one fewer")
    void protectionLastsItsWholeLength() {
        Room r = new Room(new World(4_000f, 4_000f, 256, 200f, 6L));
        Entity fresh = r.spawnTank((byte) 0, 1L);
        int configured = r.content().spawning().protectionTicks();

        int protectedSteps = 0;
        for (int k = 0; k < configured + 5; k++) {
            r.step(TIMER);                          // tick advances first: this is the step's tick
            if (fresh.protectedAt(r.tick())) {
                protectedSteps++;
            }
        }

        assertThat(protectedSteps).as("75 steps are the 3 s promised").isEqualTo(configured);
    }

    @Test
    @DisplayName("for three seconds a new tank takes no damage, deals none and cannot shoot")
    void spawnProtection() {
        Room r = new Room(new World(4_000f, 4_000f, 256, 200f, 4L));
        World w = r.world();
        Entity fresh = r.spawnTank((byte) 0, 1L);
        fresh.playerControlled = true;
        fresh.x = 1_000f;
        fresh.y = 1_000f;
        Entity old = r.spawnTank((byte) 0, 2L);
        old.playerControlled = true;
        old.protectedUntilTick = 0;                 // long since arrived
        old.x = 1_050f;                             // touching the new one
        old.y = 1_000f;
        float freshHp = fresh.hp;
        float oldHp = old.hp;
        fresh.wantsFire = true;
        fresh.reloadTicks = 0;
        fresh.aimAngle = (float) Math.PI;           // away from the other tank, or every shot
        fresh.angle = (float) Math.PI;              // would end inside it the tick it was fired

        int protection = r.content().spawning().protectionTicks();
        for (int i = 0; i < protection - 2; i++) {
            r.step(TIMER);
            old.x = fresh.x + 50f;                  // held in contact the whole time
            old.y = fresh.y;
            old.vx = old.vy = 0f;
        }
        assertThat(fresh.hp).as("took nothing").isEqualTo(freshHp);
        assertThat(old.hp).as("dealt nothing").isEqualTo(oldHp);
        assertThat(w.bullets.size).as("and fired nothing").isZero();

        run(r, 5);
        assertThat(w.bullets.size).as("then it may shoot").isPositive();
        assertThat(fresh.hp).as("and be hurt").isLessThan(freshHp);
    }

    @Test
    @DisplayName("a bullet that hits a protected tank is spent on it and hurts it not at all")
    void protectedTanksAbsorbBullets() {
        Room r = new Room(new World(4_000f, 4_000f, 256, 200f, 5L));
        Entity shooter = r.spawnTank((byte) 0, 1L);
        shooter.protectedUntilTick = 0;             // long since arrived
        shooter.playerControlled = true;
        shooter.x = 500f;
        shooter.y = 500f;
        shooter.aimAngle = 0f;
        shooter.angle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        Entity fresh = r.spawnTank((byte) 1, 2L);   // just arrived
        fresh.playerControlled = true;
        fresh.x = 620f;
        fresh.y = 500f;
        float hp = fresh.hp;

        r.step(TIMER);
        assertThat(r.world().bullets.size).as("fired").isEqualTo(1);
        shooter.wantsFire = false;
        run(r, 15);

        assertThat(r.world().bullets.size).as("spent on the protected tank, not passed through").isZero();
        assertThat(fresh.hp).isEqualTo(hp);
    }

    @Test
    @DisplayName("a slot handed back forgets the name of the player whose tank it was")
    void aSlotForgetsItsName() {
        Entity e = new Entity();
        e.name = new byte[] {'A', 'd', 'a'};
        e.reset();
        assertThat(e.name).isNull();
    }
}
