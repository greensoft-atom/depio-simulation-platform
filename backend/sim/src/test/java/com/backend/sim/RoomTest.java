package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.IntList;
import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomTest {

    private static Room room(long seed) {
        return new Room(new World(4_000f, 4_000f, 4_096, 200f, seed));
    }

    private static void run(Room r, int ticks) {
        PhaseTimer t = Room.newTimer();
        for (int i = 0; i < ticks; i++) {
            r.step(t);
        }
    }

    @Test
    @DisplayName("an entity reusing a slot does not inherit the last owner's team")
    void aReusedSlotStartsWithNoTeam() {
        World w = new World(2_000f, 2_000f, 64, 200f, 3L);
        Entity tank = w.spawn(Entity.KIND_TANK);
        tank.team = 1;
        int slot = tank.id;
        w.kill(tank);
        w.sweep();

        Entity next = w.spawn(Entity.KIND_SHAPE);   // the free list hands the slot straight back

        assertThat(next.id).isEqualTo(slot);
        assertThat(next.team).as("a shape, on no team").isZero();
    }

    @Test
    @DisplayName("killed entities free their slots and leave the id lists consistent")
    void sweepRecyclesSlots() {
        World w = new World(1_000f, 1_000f, 16, 100f, 1L);
        Room r = new Room(w);
        for (int i = 0; i < 4; i++) {
            r.spawnTank((byte) 0);
        }
        assertThat(w.liveCount()).isEqualTo(4);
        assertThat(w.tanks.size).isEqualTo(4);

        Entity victim = w.entities[w.tanks.items[1]];
        short generationBefore = victim.generation;
        w.kill(victim);

        // Not reusable until the sweep: a pass still holding the id must not see a new entity.
        assertThat(w.liveCount()).isEqualTo(4);
        w.sweep();

        assertThat(w.liveCount()).isEqualTo(3);
        assertThat(w.tanks.size).isEqualTo(3);
        assertThat(victim.generation).isEqualTo((short) (generationBefore + 1));
        for (int i = 0; i < w.tanks.size; i++) {
            assertThat(w.entities[w.tanks.items[i]].alive).isTrue();
        }
    }

    @Test
    @DisplayName("the pool refuses to grow when exhausted")
    void poolIsAHardCeiling() {
        World w = new World(1_000f, 1_000f, 3, 100f, 1L);
        Room r = new Room(w);
        assertThat(r.spawnTank((byte) 0)).isNotNull();
        assertThat(r.spawnTank((byte) 0)).isNotNull();
        assertThat(r.spawnTank((byte) 0)).isNotNull();
        assertThat(r.spawnTank((byte) 0)).isNull();
        assertThat(w.capacity()).isEqualTo(3);
    }

    @Test
    @DisplayName("the spatial hash returns every entity within the radius")
    void hashFindsNeighbours() {
        World w = new World(1_000f, 1_000f, 64, 100f, 7L);
        Entity a = w.spawn(Entity.KIND_TANK);
        Entity b = w.spawn(Entity.KIND_TANK);
        Entity far = w.spawn(Entity.KIND_TANK);
        a.x = 500;  a.y = 500;
        b.x = 530;  b.y = 500;          // 30 away
        far.x = 900; far.y = 900;

        w.hash.clear();
        w.hash.insert(a.id, a.x, a.y);
        w.hash.insert(b.id, b.x, b.y);
        w.hash.insert(far.id, far.x, far.y);

        IntList out = new IntList(8);
        w.hash.queryInto(a.x, a.y, 50f, out);

        boolean sawB = false, sawFar = false;
        for (int i = 0; i < out.size; i++) {
            sawB |= out.items[i] == b.id;
            sawFar |= out.items[i] == far.id;
        }
        assertThat(sawB).as("neighbour within radius").isTrue();
        assertThat(sawFar).as("entity far outside the queried cells").isFalse();
    }

    @Test
    @DisplayName("nothing escapes the map, however hard it is knocked")
    void entitiesStayInsideTheMap() {
        Room r = room(3L);
        for (int i = 0; i < 40; i++) {
            r.spawnTank((byte) (i % 2));
        }
        for (int i = 0; i < 100; i++) {
            r.spawnShape();
        }
        run(r, 600);

        World w = r.world();
        for (Entity e : w.entities) {
            if (e.alive && e.kind != Entity.KIND_BULLET) {
                assertThat(e.x).isBetween(0f, w.width);
                assertThat(e.y).isBetween(0f, w.height);
            }
        }
    }

    @Test
    @DisplayName("the same seed produces the same world, tick for tick")
    void simulationIsDeterministic() {
        Room a = room(99L);
        Room b = room(99L);
        for (int i = 0; i < 30; i++) {
            a.spawnTank((byte) (i % 2));
            b.spawnTank((byte) (i % 2));
        }
        for (int i = 0; i < 60; i++) {
            a.spawnShape();
            b.spawnShape();
        }
        run(a, 400);
        run(b, 400);

        assertThat(a.world().liveCount()).isEqualTo(b.world().liveCount());
        assertThat(a.world().bullets.size).isEqualTo(b.world().bullets.size);
        for (int i = 0; i < a.world().capacity(); i++) {
            Entity ea = a.world().entities[i], eb = b.world().entities[i];
            assertThat(eb.alive).isEqualTo(ea.alive);
            if (ea.alive) {
                assertThat(eb.x).isEqualTo(ea.x);
                assertThat(eb.y).isEqualTo(ea.y);
                assertThat(eb.hp).isEqualTo(ea.hp);
            }
        }
    }

    @Test
    @DisplayName("a destroyed shape is replaced by one of its kind, so the room keeps the mix it was drawn with (M-12)")
    void theShapeMixHolds() {
        Room r = room(12L);
        r.resetForNewMatch(600);
        for (int i = 0; i < 30; i++) {
            r.spawnTank((byte) (i % 2));
        }
        int[] before = mix(r);
        run(r, 3_000);
        // Bots destroy squares by the hundred and alpha pentagons hardly ever. Replaced by a
        // fresh draw, the mix drifted toward alphas: 105 in 1 500 after 13 minutes, where
        // the table says one in two hundred.
        assertThat(mix(r)).containsExactly(before);
    }

    private static int[] mix(Room r) {
        int[] n = new int[r.content().shapes().size()];
        World w = r.world();
        for (int i = 0; i < w.shapes.size; i++) {
            Entity e = w.entities[w.shapes.items[i]];
            if (e.alive) {
                n[e.subtype]++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("bullets expire, so their population stays bounded")
    void bulletsAreBounded() {
        Room r = room(11L);
        for (int i = 0; i < 20; i++) {
            r.spawnTank((byte) 0);
        }
        run(r, 1_000);

        // 20 tanks, each of the class that keeps the most bullets in the air, firing as fast
        // as the best possible build allows. Derived from the tables rather than a constant:
        // reload is a stat and bots choose classes, so a bound written against the level-1
        // Basic would fail the moment a bot upgraded.
        float fastestStat = r.content().stats().valueOf(
                Stat.RELOAD, r.content().levels().maxLevel(), Stat.MAX_POINTS_PER_STAT);
        ClassTable classes = r.content().classes();
        long perTank = 0;
        for (int c = 0; c < classes.size(); c++) {
            ClassTable.TankClass tankClass = classes.get(c);
            int reload = Math.max(1, Math.round(fastestStat * tankClass.reloadMul()));
            long inAir = 0;
            boolean steered = false;
            for (ClassTable.Barrel barrel : tankClass.barrels()) {
                int kind = barrel.kind();
                if (kind == ClassTable.Barrel.BULLET || kind == ClassTable.Barrel.TRAP) {
                    inAir += barrel.lifetimeTicks() / reload + 2;
                } else if (kind == ClassTable.Barrel.DRONE || kind == ClassTable.Barrel.MINION
                        || kind == ClassTable.Barrel.CONVERT) {
                    steered = true;             // drones and minions: as many as the class keeps
                }
                if (kind == ClassTable.Barrel.MINION) {
                    // and each minion's shots, one every two reloads
                    inAir += (long) tankClass.maxDrones() * (ClassTable.MINION_SHOT.lifetimeTicks() / (2 * reload) + 2);
                }
                if (kind == ClassTable.Barrel.ROCKET || kind == ClassTable.Barrel.SKIMMER) {
                    // missiles, which are not steered, and their shots
                    boolean skimmer = kind == ClassTable.Barrel.SKIMMER;
                    int shotLife = (skimmer ? ClassTable.SKIMMER_SHOT : ClassTable.ROCKET_SHOT).lifetimeTicks();
                    int interval = skimmer ? ClassTable.SKIMMER_INTERVAL : ClassTable.ROCKET_INTERVAL;
                    long missiles = barrel.lifetimeTicks() / reload + 2;
                    inAir += missiles * (1 + (skimmer ? 2 : 1) * (shotLife / interval + 2));
                }
            }
            if (steered) {
                inAir += tankClass.maxDrones();
            }
            perTank = Math.max(perTank, inAir);
        }
        assertThat((long) r.world().bullets.size).isLessThanOrEqualTo(20 * perTank);
    }
}
