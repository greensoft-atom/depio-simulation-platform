package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tank classes and their barrels (01 §4, "The barrel model and the first tier"). */
// A loop on the room thread that never ends fails here, not stalls the build. In a thread of
// its own: in the test's thread the timeout only interrupts it, which a busy loop never notices,
// and a mutant that stopped volleys ran until the build's own limit.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ClassTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    private static Room room(Content content) {
        return new Room(new World(2_000f, 2_000f, 512, 100f, 9L), content);
    }

    /** A still tank at (500, 500), aiming along +x with its trigger held, ready to fire. */
    private static Entity gunner(Room r) {
        Entity t = r.spawnTank((byte) 0, 1L);
        t.x = 500f;
        t.y = 500f;
        t.playerControlled = true;
        t.aimAngle = 0f;
        t.angle = 0f;
        t.wantsFire = true;
        t.reloadTicks = 0;
        return t;
    }

    private static void levelTo(Room r, Entity t, int level) {
        TankStats s = r.world().tankStats[t.id];
        s.addXp(r.content().levels().xpRequired(level) - s.xp, r.content().levels());
    }

    private static List<Entity> bullets(Room r) {
        World w = r.world();
        return java.util.stream.IntStream.range(0, w.bullets.size)
                .mapToObj(i -> w.entities[w.bullets.items[i]]).toList();
    }

    /** The reload a level-15 tank with no points in reload has, before its class: 8 ticks. */
    private static final int BASE_RELOAD = 8;

    @Test
    @DisplayName("a Twin fires two bullets a volley, 20 units apart, the second half a reload later")
    void twin() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 15);
        assertThat(r.chooseClass(t, ClassTable.TWIN)).isTrue();

        run(r, 1);
        assertThat(bullets(r)).hasSize(1);
        // Let go: the volley that has begun still completes (01 §4).
        t.wantsFire = false;
        run(r, BASE_RELOAD / 2 - 1);
        assertThat(bullets(r)).as("not before half the reload").hasSize(1);
        run(r, 1);
        List<Entity> both = bullets(r);
        assertThat(both).hasSize(2);
        // The first barrel is 10 units to the left of the line, the second 10 to the right.
        Entity first = both.get(0).y > 500f ? both.get(0) : both.get(1);
        Entity second = both.get(0).y > 500f ? both.get(1) : both.get(0);
        assertThat(first.y - second.y).isEqualTo(20f);
        // Each has flown from the same line across the muzzle: the first four ticks further.
        assertThat(first.x - second.x).isEqualTo(4 * first.vx);
        float damage = r.world().tankStats[t.id].value(Stat.BULLET_DAMAGE);
        assertThat(first.damage).isEqualTo(damage * 0.65f);
        assertThat(second.damage).isEqualTo(damage * 0.65f);

        run(r, BASE_RELOAD * 2);
        assertThat(bullets(r)).as("the trigger was let go: no next volley").hasSize(2);
    }

    @Test
    @DisplayName("a Sniper's bullet is faster and lives longer, and it reloads more slowly")
    void sniper() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 15);
        assertThat(r.chooseClass(t, ClassTable.SNIPER)).isTrue();

        int fired = 0;
        int firstTick = -1;
        int secondTick = -1;
        for (int i = 0; i < 30 && secondTick < 0; i++) {
            t.wantsFire = true;
            run(r, 1);
            if (bullets(r).size() > fired) {
                fired = bullets(r).size();
                if (firstTick < 0) {
                    firstTick = r.tick();
                } else {
                    secondTick = r.tick();
                }
            }
        }
        assertThat(secondTick - firstTick).isEqualTo(Math.round(BASE_RELOAD * 1.5f));
        for (Entity b : bullets(r)) {
            assertThat(b.vx).isEqualTo(15f);         // 10 × 1.5, no points in speed
        }
        assertThat(bullets(r)).as("90 ticks each, less the ticks each has flown")
                .extracting(b -> b.lifetimeTicks)
                .containsExactlyInAnyOrder(90 - (secondTick - firstTick) - 1, 90 - 1);
    }

    @Test
    @DisplayName("a Machine Gun fires twice as often, weaker, and spread either side of its aim")
    void machineGun() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 15);
        assertThat(r.chooseClass(t, ClassTable.MACHINE_GUN)).isTrue();

        float least = Float.MAX_VALUE;
        float most = -Float.MAX_VALUE;
        for (int i = 0; i < 200; i++) {
            t.wantsFire = true;
            run(r, 1);
        }
        List<Entity> fired = bullets(r);
        // 200 ticks at a reload of 4, less those that have expired: 75 ticks' worth alive.
        assertThat(fired).hasSizeBetween(75 / (BASE_RELOAD / 2), 75 / (BASE_RELOAD / 2) + 1);
        float damage = r.world().tankStats[t.id].value(Stat.BULLET_DAMAGE);
        for (Entity b : fired) {
            float heading = (float) Math.atan2(b.vy, b.vx);
            least = Math.min(least, heading);
            most = Math.max(most, heading);
            assertThat(b.damage).isEqualTo(damage * 0.7f);
        }
        assertThat(least).isGreaterThanOrEqualTo(-0.1701f);
        assertThat(most).isLessThanOrEqualTo(0.1701f);
        assertThat(most - least).as("spread, not one line").isGreaterThan(0.2f);
    }

    @Test
    @DisplayName("a Flank Guard fires ahead and behind at once")
    void flankGuard() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 15);
        assertThat(r.chooseClass(t, ClassTable.FLANK_GUARD)).isTrue();

        run(r, 1);
        List<Entity> fired = bullets(r);
        assertThat(fired).hasSize(2);
        Entity ahead = fired.get(0).vx > 0 ? fired.get(0) : fired.get(1);
        Entity behind = fired.get(0).vx > 0 ? fired.get(1) : fired.get(0);
        assertThat(ahead.vx).isEqualTo(10f);
        assertThat(behind.vx).isEqualTo(-10f);
        assertThat(ahead.x - 500f).isCloseTo(500f - behind.x, org.assertj.core.data.Offset.offset(1e-3f));
    }

    @Test
    @DisplayName("a class is refused below its level, from the wrong parent, and when it is not one")
    void choosingIsValidated() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        TankStats s = r.world().tankStats[t.id];
        levelTo(r, t, 14);
        assertThat(r.chooseClass(t, ClassTable.TWIN)).as("level 14").isFalse();
        levelTo(r, t, 15);
        assertThat(r.chooseClass(t, ClassTable.BASIC)).isFalse();
        assertThat(r.chooseClass(t, 99)).isFalse();
        assertThat(r.chooseClass(t, -1)).isFalse();
        assertThat(s.classId).isEqualTo(ClassTable.BASIC);

        assertThat(r.chooseClass(t, ClassTable.TWIN)).isTrue();
        assertThat(r.chooseClass(t, ClassTable.SNIPER)).as("Sniper's parent is Basic, not Twin").isFalse();
        assertThat(s.classId).isEqualTo(ClassTable.TWIN);

        Entity shape = r.spawnShape();
        assertThat(r.chooseClass(shape, ClassTable.TWIN)).as("not a tank").isFalse();
    }

    @Test
    @DisplayName("a class chosen mid-volley drops the old class's remaining barrels")
    void aChoiceDropsTheVolley() {
        // Basic here has a late second barrel, and its child has one too, at another delay.
        ClassTable classes = new ClassTable(List.of(
                new ClassTable.TankClass(0, "two", 1, -1, 1f, 1f, List.of(
                        new ClassTable.Barrel(0f, 0f, 0f, 1f, 1f, 1f, 75, 0f),
                        new ClassTable.Barrel(0f, 0f, 0.9f, 1f, 1f, 1f, 75, 0f))),
                new ClassTable.TankClass(1, "child", 1, 0, 1f, 1f, List.of(
                        new ClassTable.Barrel(0f, 0f, 0f, 1f, 1f, 1f, 75, 0f),
                        new ClassTable.Barrel(0f, 0f, 0.9f, 1f, 1f, 1f, 75, 0f)))));
        Room r = room(new Content(StatTable.defaults(), LevelTable.defaults(), ShapeTable.defaults(),
                Recovery.defaults(), Fixtures.NO_PROTECTION, classes));
        Entity t = gunner(r);
        run(r, 1);
        t.wantsFire = false;
        assertThat(bullets(r)).hasSize(1);
        assertThat(r.chooseClass(t, 1)).isTrue();
        run(r, 12);                                 // the level-1 reload is 8; 0.9 of it is 7
        assertThat(bullets(r)).as("the second barrel belonged to the old volley").hasSize(1);
    }

    @Test
    @DisplayName("a slot is handed back as Basic, and a tank put back in the world keeps its class")
    void deathReturnsToBasic() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 15);
        r.chooseClass(t, ClassTable.SNIPER);
        TankStats kept = new TankStats();
        kept.copyFrom(r.world().tankStats[t.id]);
        assertThat(kept.classId).as("a resume keeps it (02 §10)").isEqualTo(ClassTable.SNIPER);

        int seat = t.id;
        r.world().kill(t);
        r.world().sweep();
        Entity next = r.spawnTank((byte) 0, 2L);
        assertThat(next.id).isEqualTo(seat);
        assertThat(r.world().tankStats[seat].classId).isEqualTo(ClassTable.BASIC);
    }

    @Test
    @DisplayName("a bot takes a class when it reaches one, each of the four in turn across rooms")
    void botsChoose() {
        Set<Integer> chosen = new HashSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            World w = new World(2_000f, 2_000f, 256, 100f, seed);
            Room r = new Room(w, Fixtures.UNPROTECTED);
            Entity bot = r.spawnTank((byte) 0);
            bot.x = 1_000f;
            bot.y = 1_000f;
            bot.reloadTicks = 0;
            // Enough experience for level 15 in one kill, right in front of its barrel.
            Entity alpha = r.spawnShape();
            alpha.subtype = 3;
            alpha.x = bot.x + (float) Math.cos(bot.angle) * 60f;
            alpha.y = bot.y + (float) Math.sin(bot.angle) * 60f;
            alpha.hp = 1f;
            alpha.vx = alpha.vy = 0f;
            run(r, 1);
            TankStats s = w.tankStats[bot.id];
            assertThat(s.level).as("seed %d", seed).isGreaterThanOrEqualTo(15);
            assertThat(s.classId).as("seed %d", seed).isBetween(ClassTable.TWIN, ClassTable.FLANK_GUARD);
            chosen.add(s.classId);
        }
        assertThat(chosen).containsExactlyInAnyOrder(
                ClassTable.TWIN, ClassTable.SNIPER, ClassTable.MACHINE_GUN, ClassTable.FLANK_GUARD);
    }

    // ---- the second tier (01 §4, "The second tier") ------------------------------------------

    @Test
    @DisplayName("a Destroyer's bullet is twice the size and three times the damage, and its recoil pushes the tank back")
    void destroyer() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        assertThat(r.chooseClass(t, ClassTable.MACHINE_GUN)).isTrue();
        assertThat(r.chooseClass(t, ClassTable.DESTROYER)).isTrue();

        run(r, 1);
        Entity b = bullets(r).get(0);
        assertThat(b.radius).isEqualTo(16f);
        assertThat(b.damage).isEqualTo(r.world().tankStats[t.id].value(Stat.BULLET_DAMAGE) * 3f);
        assertThat(b.vx).isEqualTo(7f);                          // 10 × 0.7
        assertThat(b.x - b.vx).as("spawned clear of the tank, by its own radius")
                .isEqualTo(500f + 30f + 16f + 1f);
        assertThat(t.vx).as("pushed back along the barrel").isEqualTo(-1.5f);
        run(r, 1);
        assertThat(t.x).as("and it moves").isLessThan(500f);
    }

    @Test
    @DisplayName("a Tri-Angle's rear barrels drive it forward, and its front one does not hold it back")
    void triAngleThrust() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.FLANK_GUARD);
        assertThat(r.chooseClass(t, ClassTable.TRI_ANGLE)).isTrue();
        run(r, 1);
        assertThat(bullets(r)).hasSize(3);
        // Two pushes of 0.5 from barrels at ±150°: cos 30° forward each, sideways cancelled.
        assertThat(t.vx).isCloseTo(2 * 0.5f * (float) Math.cos(Math.PI / 6), org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(t.vy).isCloseTo(0f, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    @DisplayName("Quad Tank and Twin Flank can be reached from Twin or from Flank Guard, and from nothing else")
    void twoParents() {
        for (int parent : new int[] {ClassTable.TWIN, ClassTable.FLANK_GUARD}) {
            Room r = room(Fixtures.UNPROTECTED);
            Entity t = gunner(r);
            levelTo(r, t, 30);
            assertThat(r.chooseClass(t, parent)).isTrue();
            assertThat(r.chooseClass(t, ClassTable.QUAD_TANK)).as("from %d", parent).isTrue();
        }
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.SNIPER);
        assertThat(r.chooseClass(t, ClassTable.TWIN_FLANK)).as("not from Sniper").isFalse();
        assertThat(r.chooseClass(t, ClassTable.ASSASSIN)).isTrue();
    }

    @Test
    @DisplayName("a Gunner fires four small bullets a volley, a quarter of the reload apart")
    void gunner() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        assertThat(r.chooseClass(t, ClassTable.GUNNER)).isTrue();
        t.wantsFire = true;
        run(r, 1);
        t.wantsFire = false;
        assertThat(bullets(r)).hasSize(1);
        run(r, BASE_RELOAD);
        assertThat(bullets(r)).hasSize(4).allSatisfy(b -> assertThat(b.radius).isEqualTo(4.8f));
    }

    @Test
    @DisplayName("a Trapper lays a trap that slides ten times its launch speed, stops, and waits out its 24 s")
    void trapper() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.SNIPER);
        assertThat(r.chooseClass(t, ClassTable.TRAPPER)).isTrue();
        run(r, 1);
        t.wantsFire = false;
        Entity trap = bullets(r).get(0);
        assertThat(trap.subtype).isEqualTo((byte) ClassTable.Barrel.TRAP);
        assertThat(trap.wireClass).as("updated, not extrapolated").isEqualTo(Entity.WIRE_UNIT);
        assertThat(trap.radius).isEqualTo(12f);
        float laidAt = trap.x - 8f;                             // one tick at 8 before the first slowing
        run(r, 150);
        assertThat(trap.vx).as("stopped").isLessThan(1e-4f);
        assertThat(trap.x - laidAt).isCloseTo(80f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(trap.alive).isTrue();
        run(r, 600 - 151 - 1);
        assertThat(trap.alive).as("still there at 599 ticks").isTrue();
        run(r, 2);
        assertThat(trap.alive).as("gone at 600").isFalse();
    }

    @Test
    @DisplayName("a trap hurts a tank that meets it, and is used up doing it")
    void trapsHurt() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.TRAPPER);
        run(r, 1);
        t.wantsFire = false;
        Entity trap = bullets(r).get(0);
        run(r, 150);                                            // lying still, 80 units out
        Entity victim = r.spawnTank((byte) 1, 2L);
        victim.x = trap.x + 30f;
        victim.y = trap.y;
        victim.playerControlled = true;
        float hpBefore = victim.hp;
        float trapBefore = trap.hp;
        run(r, 1);
        assertThat(victim.hp).isLessThan(hpBefore);
        assertThat(trap.hp).isLessThan(trapBefore);
    }

    // ---- knocked traps (01 §4, "The rest of the tree": part a) ---------------------------------

    /** A Trapper's trap, laid and come to rest 80 units ahead of its tank at (500, 500). */
    private static Entity restingTrap(Room r) {
        Entity t = gunner(r);
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.TRAPPER);
        run(r, 1);
        t.wantsFire = false;
        Entity trap = bullets(r).get(0);
        run(r, 150);
        assertThat(Math.hypot(trap.vx, trap.vy)).as("at rest").isLessThan(1e-4);
        return trap;
    }

    @Test
    @DisplayName("a trap is knocked by a tank that meets it, and slides off")
    void trapsAreKnockedByTanks() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity trap = restingTrap(r);
        // Sturdy enough to outlast the meeting: a Trapper's own, 16, is used up by a tank's 20
        // at once, and a dead trap is swept, velocity and all.
        trap.hp = trap.maxHp = 1_000f;
        float x = trap.x;
        Entity victim = r.spawnTank((byte) 1, 2L);
        victim.x = trap.x + 30f;                        // to its right: it is pushed left
        victim.y = trap.y;
        victim.playerControlled = true;
        run(r, 1);
        assertThat(trap.vx).isNegative();
        run(r, 5);
        assertThat(trap.x).as("and it slides").isLessThan(x);
    }

    @Test
    @DisplayName("a trap is knocked along by a bullet that hits it; a bullet that hits something flies on unknocked")
    void trapsAreKnockedByBulletsAndBulletsAreNot() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity trap = restingTrap(r);
        Entity shooter = r.spawnTank((byte) 1, 3L);
        shooter.x = trap.x;                             // above it: the trap's own tank is not in the way
        shooter.y = trap.y - 200f;
        shooter.playerControlled = true;
        shooter.aimAngle = (float) (Math.PI / 2);
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        run(r, 1);
        shooter.wantsFire = false;
        Entity bullet = bullets(r).stream().filter(b -> b.ownerId == shooter.id).findFirst().orElseThrow();
        bullet.hp = bullet.maxHp = 1_000f;              // it survives the hit
        float vx = bullet.vx;
        float vy = bullet.vy;
        for (int i = 0; i < 40 && trap.vy == 0f; i++) {
            run(r, 1);
        }
        assertThat(trap.vy).as("knocked along the bullet's way").isPositive();
        run(r, 3);
        assertThat(bullet.alive).isTrue();
        assertThat(bullet.vx).as("the bullet is not knocked (D-9)").isEqualTo(vx);
        assertThat(bullet.vy).isEqualTo(vy);
    }

    @Test
    @DisplayName("the other way round: a bullet older than the trap, so first in the list, knocks it and is not knocked")
    void aTrapIsKnockedWhicheverComesFirst() {
        // A pair of projectiles is settled from the one with the lower slot. The test above has
        // the trap first; here the bullet is, fired before the trap is laid.
        Room r = room(Fixtures.UNPROTECTED);
        Entity shooter = gunner(r);
        shooter.x = 500f;
        shooter.y = 400f;
        shooter.aimAngle = (float) (Math.PI / 2);
        run(r, 1);
        shooter.wantsFire = false;
        Entity bullet = bullets(r).get(0);
        bullet.vx = 0f;
        bullet.vy = 0.5f;                               // slow: it arrives after the trap has settled
        bullet.hp = bullet.maxHp = 1_000f;
        bullet.lifetimeTicks = 255;

        Entity trapper = r.spawnTank((byte) 1, 7L);
        trapper.x = 377f;                               // its trap comes to rest at (500, 500)
        trapper.y = 500f;
        trapper.playerControlled = true;
        trapper.aimAngle = 0f;
        levelTo(r, trapper, 30);
        r.chooseClass(trapper, ClassTable.SNIPER);
        r.chooseClass(trapper, ClassTable.TRAPPER);
        trapper.wantsFire = true;
        trapper.reloadTicks = 0;
        run(r, 1);
        trapper.wantsFire = false;
        Entity trap = bullets(r).stream().filter(b -> b.subtype == ClassTable.Barrel.TRAP).findFirst().orElseThrow();
        assertThat(bullet.id).as("the bullet first in the list").isLessThan(trap.id);
        trap.hp = trap.maxHp = 1_000f;
        for (int i = 0; i < 200 && trap.vy == 0f; i++) {
            run(r, 1);
        }
        assertThat(trap.vy).as("knocked along the bullet's way").isPositive();
        assertThat(bullet.vx).as("the bullet is not knocked (D-9)").isZero();
        assertThat(bullet.vy).isEqualTo(0.5f);
    }

    private static List<Entity> drones(Room r) {
        return bullets(r).stream().filter(b -> b.subtype == ClassTable.Barrel.DRONE).toList();
    }

    @Test
    @DisplayName("an Overseer keeps eight drones out, trigger or not, and launches no more")
    void overseerKeepsEight() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.SNIPER);
        assertThat(r.chooseClass(t, ClassTable.OVERSEER)).isTrue();
        run(r, 12 * 10);
        assertThat(drones(r)).hasSize(8).allSatisfy(d -> {
            assertThat(d.wireClass).isEqualTo(Entity.WIRE_UNIT);
            assertThat(d.radius).isEqualTo(10f);
            assertThat(d.lifetimeTicks).as("no expiry").isNegative();
        });
        run(r, 12 * 5);
        assertThat(drones(r)).as("and no more").hasSize(8);
    }

    @Test
    @DisplayName("drones circle their owner, attack 300 units along its aim while the trigger is held, and go when it goes")
    void dronesSteer() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        t.x = 1_000f;
        t.y = 1_000f;
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.OVERSEER);
        run(r, 200);
        for (Entity d : drones(r)) {
            float dist = (float) Math.hypot(d.x - t.x, d.y - t.y);
            assertThat(dist).as("circling, not attacking").isLessThan(160f);
        }
        t.attacking = true;
        t.aimAngle = 0f;
        run(r, 150);
        for (Entity d : drones(r)) {
            assertThat(Math.hypot(d.x - (t.x + 300f), d.y - t.y)).as("gathered at the reach").isLessThan(60.0);
        }
        List<Entity> out = drones(r);
        r.world().kill(t);
        run(r, 1);
        assertThat(out).as("gone with their owner").allSatisfy(d -> assertThat(d.alive).isFalse());
    }

    // ---- the third tier (01 §4, "The third tier") --------------------------------------------

    // ---- the bosses (01 §8.5, "Bosses") --------------------------------------------------------

    @Test
    @DisplayName("the bosses' classes: appended, reached by no upgrade from any class at any level, and as designed")
    void bossClasses() {
        ClassTable classes = ClassTable.defaults();
        ClassTable.TankClass guardian = classes.get(ClassTable.GUARDIAN);
        ClassTable.TankClass enraged = classes.get(ClassTable.GUARDIAN_ENRAGED);
        assertThat(ClassTable.GUARDIAN).as("appended: ids are on the wire").isEqualTo(ClassTable.MEGA_SMASHER + 1);
        assertThat(ClassTable.GUARDIAN_ENRAGED).isEqualTo(ClassTable.GUARDIAN + 1);
        assertThat(guardian.name()).isEqualTo("Guardian");
        assertThat(enraged.name()).isEqualTo("Guardian, enraged");
        ClassTable.TankClass octo = classes.get(ClassTable.OCTO_TANK);
        assertThat(guardian.barrels()).as("eight round it, as the Octo Tank's").isEqualTo(octo.barrels());
        assertThat(guardian.reloadMul()).isEqualTo(octo.reloadMul());
        assertThat(guardian.bodySize()).isEqualTo(2.5f);
        assertThat(guardian.healthMul()).isEqualTo(12f);
        assertThat(guardian.caps().get(Stat.MOVEMENT_SPEED)).as("slow: no points in speed").isZero();
        assertThat(enraged.healthMul()).as("becoming enraged does not change its health").isEqualTo(guardian.healthMul());
        assertThat(enraged.bodySize()).isEqualTo(guardian.bodySize());
        assertThat(enraged.caps()).isEqualTo(guardian.caps());
        assertThat(enraged.reloadMul()).as("twice as fast").isEqualTo(guardian.reloadMul() / 2);
        assertThat(enraged.barrels()).hasSameSizeAs(guardian.barrels());
        for (int i = 0; i < guardian.barrels().size(); i++) {
            ClassTable.Barrel g = guardian.barrels().get(i);
            ClassTable.Barrel e = enraged.barrels().get(i);
            assertThat(e.lifetimeTicks()).as("half as long").isEqualTo(g.lifetimeTicks() / 2);
            assertThat(e.angle()).isEqualTo(g.angle());
            assertThat(e.delay()).isEqualTo(g.delay());
        }
        for (int from = 0; from < classes.size(); from++) {
            for (int level = 0; level <= 1_000; level++) {
                assertThat(classes.mayChoose(from, level, ClassTable.GUARDIAN)).isFalse();
                assertThat(classes.mayChoose(from, level, ClassTable.GUARDIAN_ENRAGED)).isFalse();
            }
        }
        for (int id = 0; id < ClassTable.GUARDIAN; id++) {
            assertThat(classes.get(id).healthMul()).as(classes.get(id).name()).isEqualTo(1f);
        }
    }

    @Test
    @DisplayName("a class given by the arena: a Guardian has twelve times the health, the gain added, and a boss's body")
    void assignedClass() {
        Room r = room(Content.defaults());
        Entity t = r.spawnTank((byte) 2);
        r.grantExperience(t, r.content().levels().xpRequired(45));   // grown as a wave's tanks are
        run(r, 1);
        float before = t.maxHp;
        assertThat(t.hp).isEqualTo(before);

        assertThat(r.assignClass(t, ClassTable.GUARDIAN)).isTrue();
        run(r, 1);
        assertThat(r.world().tankStats[t.id].classId).isEqualTo(ClassTable.GUARDIAN);
        assertThat(t.maxHp).isCloseTo(12 * before, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(t.hp).as("the gain added to what it had").isEqualTo(t.maxHp);
        assertThat(t.radius).isEqualTo(Room.TANK_RADIUS * 2.5f);

        float guardianMax = t.maxHp;
        t.hp = t.maxHp / 3;
        assertThat(r.assignClass(t, ClassTable.GUARDIAN_ENRAGED)).isTrue();
        run(r, 1);
        assertThat(t.maxHp).as("the same health").isEqualTo(guardianMax);
        assertThat(t.hp).as("the enraged form keeps its wounds, a tick's recovery aside").isLessThan(t.maxHp / 2);
        assertThat(r.chooseClass(t, ClassTable.TWIN)).as("choosing still keeps the upgrade rules").isFalse();

        r.world().kill(t);
        run(r, 1);
        assertThat(r.assignClass(t, ClassTable.GUARDIAN)).as("not a dead tank").isFalse();
    }

    @Test
    @DisplayName("no class keeps more alive at full reload than the second tier's most: 75 bullets, 120 traps (D-22)")
    void theBudget() {
        ClassTable classes = ClassTable.defaults();
        float reload = StatTable.defaults().valueOf(Stat.RELOAD, 45, Stat.MAX_POINTS_PER_STAT);
        double secondBullets = 0;
        double secondTraps = 0;
        for (int id = 0; id < classes.size(); id++) {
            ClassTable.TankClass c = classes.get(id);
            int ticks = Room.reloadTicks(reload, c.reloadMul());
            // Each barrel keeps its lifetime over the reload alive: summed in ticks, so the
            // comparison is exact.
            int bullets = 0;
            int traps = 0;
            for (ClassTable.Barrel b : c.barrels()) {
                if (b.kind() == ClassTable.Barrel.BULLET) {
                    bullets += b.lifetimeTicks();
                } else if (b.kind() == ClassTable.Barrel.TRAP) {
                    traps += b.lifetimeTicks();
                } else if (b.kind() == ClassTable.Barrel.MINION) {
                    // Each of its minions fires every two reloads (01 §4, "Minions").
                    bullets += c.maxDrones() * ClassTable.MINION_SHOT.lifetimeTicks() / 2;
                } else if (b.kind() == ClassTable.Barrel.ROCKET || b.kind() == ClassTable.Barrel.SKIMMER) {
                    // The missile, and each one's shots: its lifetime over the interval keeps them alive.
                    boolean skimmer = b.kind() == ClassTable.Barrel.SKIMMER;
                    int shots = skimmer ? 2 : 1;
                    int shotLife = (skimmer ? ClassTable.SKIMMER_SHOT : ClassTable.ROCKET_SHOT).lifetimeTicks();
                    int interval = skimmer ? ClassTable.SKIMMER_INTERVAL : ClassTable.ROCKET_INTERVAL;
                    bullets += b.lifetimeTicks() + b.lifetimeTicks() * shots * shotLife / interval;
                }
            }
            if (id <= ClassTable.OVERSEER) {
                secondBullets = Math.max(secondBullets, bullets / (double) ticks);
                secondTraps = Math.max(secondTraps, traps / (double) ticks);
            }
            assertThat(bullets).as("%s's bullets, over %d ticks", c.name(), ticks).isLessThanOrEqualTo(75 * ticks);
            assertThat(traps).as("%s's traps, over %d ticks", c.name(), ticks).isLessThanOrEqualTo(120 * ticks);
        }
        assertThat(new double[] {secondBullets, secondTraps}).as("the second tier's most, which is the budget")
                .containsExactly(75.0, 120.0);
    }

    @Test
    @DisplayName("the third tier's ids, names, parents and levels: ids are on the wire, so a row is appended, never moved")
    void theThirdTier() {
        ClassTable classes = ClassTable.defaults();
        Object[][] rows = {
                {15, "Triplet", List.of(ClassTable.TRIPLE_SHOT), 45},
                {16, "Penta Shot", List.of(ClassTable.TRIPLE_SHOT), 45},
                {17, "Spread Shot", List.of(ClassTable.TRIPLE_SHOT), 45},
                {18, "Octo Tank", List.of(ClassTable.QUAD_TANK), 45},
                {19, "Triple Twin", List.of(ClassTable.TWIN_FLANK), 45},
                {20, "Ranger", List.of(ClassTable.ASSASSIN), 45},
                {21, "Predator", List.of(ClassTable.HUNTER), 45},
                {22, "Streamliner", List.of(ClassTable.HUNTER), 45},
                {23, "Sprayer", List.of(ClassTable.MACHINE_GUN), 45},
                {24, "Annihilator", List.of(ClassTable.DESTROYER), 45},
                {25, "Booster", List.of(ClassTable.TRI_ANGLE), 45},
                {26, "Fighter", List.of(ClassTable.TRI_ANGLE), 45},
                {27, "Overlord", List.of(ClassTable.OVERSEER), 45},
                {28, "Tri-Trapper", List.of(ClassTable.TRAPPER), 45},
                {29, "Mega Trapper", List.of(ClassTable.TRAPPER), 45},
                {30, "Gunner Trapper", List.of(ClassTable.GUNNER, ClassTable.TRAPPER), 45},
                {31, "Overtrapper", List.of(ClassTable.TRAPPER, ClassTable.OVERSEER), 45},
                {32, "Hybrid", List.of(ClassTable.DESTROYER), 45},
                {33, "Auto 3", List.of(ClassTable.FLANK_GUARD), 30},
                {34, "Auto 5", List.of(ClassTable.AUTO_3), 45},
                {35, "Auto Gunner", List.of(ClassTable.GUNNER), 45},
                {36, "Auto Trapper", List.of(ClassTable.TRAPPER), 45},
                {37, "Smasher", List.of(ClassTable.BASIC), 30},
                {38, "Spike", List.of(ClassTable.SMASHER), 45},
                {39, "Auto Smasher", List.of(ClassTable.SMASHER), 45},
                {40, "Stalker", List.of(ClassTable.ASSASSIN), 45},
                {41, "Manager", List.of(ClassTable.OVERSEER), 45},
                {42, "Landmine", List.of(ClassTable.SMASHER), 45},
                {43, "Necromancer", List.of(ClassTable.OVERSEER), 45},
                {44, "Factory", List.of(ClassTable.OVERSEER), 45},
                {45, "Battleship", List.of(ClassTable.OVERSEER, ClassTable.TWIN_FLANK), 45},
                {46, "Rocketeer", List.of(ClassTable.DESTROYER), 45},
                {47, "Skimmer", List.of(ClassTable.DESTROYER), 45},
                {48, "Mega Smasher", List.of(ClassTable.SMASHER), 45},
        };
        for (Object[] row : rows) {
            ClassTable.TankClass c = classes.get((Integer) row[0]);
            assertThat(c.id()).isEqualTo(row[0]);
            assertThat(c.name()).isEqualTo(row[1]);
            assertThat(c.parents()).as(c.name()).isEqualTo(row[2]);
            assertThat(c.opensAt()).as(c.name()).isEqualTo(row[3]);
        }
    }

    @Test
    @DisplayName("a Spread Shot fans eleven bullets out over one reload, the middle first and the widest last")
    void spreadShot() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.TWIN);
        r.chooseClass(t, ClassTable.TRIPLE_SHOT);
        assertThat(r.chooseClass(t, ClassTable.SPREAD_SHOT)).isTrue();
        run(r, 1);
        t.wantsFire = false;
        assertThat(bullets(r)).as("the middle one first").singleElement()
                .satisfies(b -> assertThat(b.vy).isEqualTo(0f));
        run(r, Math.round(BASE_RELOAD * 3.2f) - 1);
        List<Entity> fan = bullets(r);
        assertThat(fan).hasSize(11);
        double widest = fan.stream().mapToDouble(b -> Math.abs(Math.atan2(b.vy, b.vx))).max().orElseThrow();
        assertThat(widest).isCloseTo(1.25, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    @DisplayName("a Gunner Trapper fires small bullets ahead and lays traps behind, in one volley")
    void gunnerTrapper() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.TRAPPER);
        assertThat(r.chooseClass(t, ClassTable.GUNNER_TRAPPER)).as("from Trapper, as from Gunner").isTrue();
        run(r, 1);
        t.wantsFire = false;
        run(r, BASE_RELOAD);
        assertThat(bullets(r)).filteredOn(b -> b.subtype == ClassTable.Barrel.BULLET).hasSize(2)
                .allSatisfy(b -> assertThat(b.vx).isPositive());
        assertThat(bullets(r)).filteredOn(b -> b.subtype == ClassTable.Barrel.TRAP).singleElement()
                .satisfies(b -> assertThat(b.vx).isNegative());
    }

    private static List<Entity> fired(Room r, int kind) {
        return bullets(r).stream().filter(b -> b.subtype == kind).toList();
    }

    @Test
    @DisplayName("without the trigger only drones launch: a Hybrid keeps two out and fires no Destroyer shot unasked")
    void withoutTheTrigger() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        r.chooseClass(t, ClassTable.DESTROYER);
        assertThat(r.chooseClass(t, ClassTable.HYBRID)).isTrue();
        run(r, 200);
        assertThat(drones(r)).hasSize(2);
        assertThat(fired(r, ClassTable.Barrel.BULLET)).as("no shot unasked").isEmpty();

        Room o = room(Fixtures.UNPROTECTED);
        Entity u = gunner(o);
        u.wantsFire = false;
        levelTo(o, u, 45);
        o.chooseClass(u, ClassTable.SNIPER);
        o.chooseClass(u, ClassTable.TRAPPER);
        assertThat(o.chooseClass(u, ClassTable.OVERTRAPPER)).isTrue();
        run(o, 200);
        assertThat(drones(o)).hasSize(2);
        assertThat(fired(o, ClassTable.Barrel.TRAP)).as("no trap unasked").isEmpty();
    }

    @Test
    @DisplayName("the trigger pulled during a volley the drones began fires at once, and still once a volley")
    void theTriggerJoinsAVolley() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        r.chooseClass(t, ClassTable.DESTROYER);
        r.chooseClass(t, ClassTable.HYBRID);
        run(r, 200);
        int reload = Math.round(BASE_RELOAD * 4f);
        for (int i = 0; i < reload && t.reloadTicks < reload / 2; i++) {
            run(r, 1);                                  // well inside a volley the drones began
        }
        assertThat(t.reloadTicks).as("the drones keep a volley going").isGreaterThanOrEqualTo(reload / 2);
        t.wantsFire = true;
        run(r, 1);
        assertThat(fired(r, ClassTable.Barrel.BULLET)).as("not after the drones' reload").hasSize(1);
        // Shots, not bullets alive: three reloads outlast a bullet's 75 ticks. By slot and
        // generation, since a slot is reused.
        Set<Long> shots = new HashSet<>();
        for (int i = 0; i <= reload * 3; i++) {
            fired(r, ClassTable.Barrel.BULLET).forEach(b -> shots.add(((long) b.id << 16) | (b.generation & 0xFFFF)));
            t.wantsFire = true;
            run(r, 1);
        }
        assertThat(shots).as("held for three reloads: one a volley").hasSizeBetween(3, 4);
    }

    @Test
    @DisplayName("joining a volley adds the class's other barrels, not a second launch from the one that began it")
    void joiningDoesNotRelaunch() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        r.chooseClass(t, ClassTable.DESTROYER);
        r.chooseClass(t, ClassTable.HYBRID);
        run(r, 1);                                      // the drones' volley: one launched
        assertThat(drones(r)).hasSize(1);
        t.wantsFire = true;
        run(r, 1);
        assertThat(fired(r, ClassTable.Barrel.BULLET)).hasSize(1);
        assertThat(drones(r)).as("its drone barrel has had its turn this volley").hasSize(1);
    }

    @Test
    @DisplayName("a class chosen during the drones' volley drops it: the new class's trigger waits for the reload")
    void aChoiceDropsTheDronesVolley() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.OVERSEER);
        run(r, 1);                                      // the Overseer's drones begin a volley
        assertThat(r.chooseClass(t, ClassTable.OVERTRAPPER)).isTrue();
        t.wantsFire = true;
        run(r, 1);
        assertThat(fired(r, ClassTable.Barrel.TRAP)).as("no volley left to join").isEmpty();
        for (int i = 0; i < Math.round(BASE_RELOAD * 1.5f); i++) {
            t.wantsFire = true;
            run(r, 1);
        }
        assertThat(fired(r, ClassTable.Barrel.TRAP)).as("then, at the reload").hasSize(1);
    }

    @Test
    @DisplayName("the trigger pulled as the drones' volley ends fires one shot, not the old volley's and the new one's")
    void theTriggerAtAVolleysEnd() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        r.chooseClass(t, ClassTable.DESTROYER);
        r.chooseClass(t, ClassTable.HYBRID);
        run(r, 200);
        for (int i = 0; i < 64 && t.reloadTicks != 1; i++) {
            run(r, 1);                                  // the drones' volley ends on the next tick
        }
        assertThat(t.reloadTicks).isEqualTo(1);
        t.wantsFire = true;
        run(r, 1);
        assertThat(fired(r, ClassTable.Barrel.BULLET)).hasSize(1);
    }

    // ---- turrets (01 §4, "The third tier", "Turrets") -----------------------------------------

    /** A still tank of another team, where a turret can see it. */
    private static Entity target(Room r, float x, float y) {
        Entity v = r.spawnTank((byte) 2, 9L);
        v.x = x;
        v.y = y;
        v.playerControlled = true;
        return v;
    }

    /** The heading of each bullet fired during the next {@code ticks} steps, as it was fired. */
    private static List<Double> shotsDuring(Room r, int ticks) {
        Set<Long> seen = new HashSet<>();
        List<Double> headings = new java.util.ArrayList<>();
        for (int i = 0; i <= ticks; i++) {
            for (Entity b : fired(r, ClassTable.Barrel.BULLET)) {
                if (seen.add(((long) b.id << 16) | (b.generation & 0xFFFF)) && i > 0) {
                    headings.add(Math.atan2(b.vy, b.vx));
                }
            }
            if (i < ticks) {
                run(r, 1);
            }
        }
        return headings;
    }

    @Test
    @DisplayName("an Auto 3's turrets each watch a third of the circle and fire, untriggered, straight at what is in it")
    void autoThree() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        t.aimAngle = (float) Math.PI;                   // facing -x: a turret's line passes π
        levelTo(r, t, 30);
        r.chooseClass(t, ClassTable.FLANK_GUARD);
        assertThat(r.chooseClass(t, ClassTable.AUTO_3)).isTrue();
        assertThat(shotsDuring(r, 3 * BASE_RELOAD)).as("nothing to shoot at, nothing shot").isEmpty();

        // At -85°: the +120° turret's third, whose line is at 300°, the same as -60°.
        target(r, 530f, 154f);
        double at = Math.atan2(-346, 30);
        List<Double> shots = shotsDuring(r, 3 * BASE_RELOAD);
        assertThat(shots).as("one turret of three, once a reload").hasSizeBetween(2, 4)
                .allSatisfy(h -> assertThat(h).isCloseTo(at, org.assertj.core.data.Offset.offset(1e-4)));
    }

    @Test
    @DisplayName("a turret takes a tank before a shape, and a shape when there is no tank")
    void turretsChoose() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        r.chooseClass(t, ClassTable.GUNNER);
        assertThat(r.chooseClass(t, ClassTable.AUTO_GUNNER)).isTrue();
        Entity shape = r.spawnShape();
        shape.x = 500f;
        shape.y = 400f;                                 // 100 units away, straight up the page
        shape.vx = shape.vy = 0f;
        shape.radius = 18f;
        shape.mass = 1e6f;                              // not knocked off its place by the shots
        shape.hp = shape.maxHp = 1e6f;
        Entity far = target(r, 200f, 500f);             // 300 units away, behind
        Entity further = target(r, 500f, 850f);         // 350 away: the nearer tank is the one
        int reload = Math.round(BASE_RELOAD * 1.4f);
        assertThat(shotsDuring(r, 3 * reload)).as("the tank, and only the turret: no trigger")
                .hasSizeBetween(2, 4)
                .allSatisfy(h -> assertThat(Math.abs(h)).isCloseTo(Math.PI, org.assertj.core.data.Offset.offset(0.02)));

        r.world().kill(far);
        r.world().kill(further);
        assertThat(shotsDuring(r, 3 * reload)).as("now the shape").hasSizeBetween(2, 4)
                .allSatisfy(h -> assertThat(h).isCloseTo(-Math.PI / 2, org.assertj.core.data.Offset.offset(0.02)));
    }

    @Test
    @DisplayName("a turret leaves its own team, a tank in its spawn protection and anything past 400 units alone")
    void turretsHoldFire() {
        for (int c = 0; c < 4; c++) {
            Room r = room(Fixtures.UNPROTECTED);
            Entity t = gunner(r);
            t.wantsFire = false;
            t.team = 1;
            levelTo(r, t, 45);
            r.chooseClass(t, ClassTable.SNIPER);
            r.chooseClass(t, ClassTable.TRAPPER);
            assertThat(r.chooseClass(t, ClassTable.AUTO_TRAPPER)).isTrue();
            Entity v = target(r, 500f + (c == 2 ? 420f : 390f), 500f);
            if (c == 0) {
                v.team = 1;
            } else if (c == 1) {
                v.protectedUntilTick = 1_000;
            }
            List<Double> shots = shotsDuring(r, 3 * Math.round(BASE_RELOAD * 1.5f));
            if (c == 3) {
                assertThat(shots).as("the control: 390 units, another team, unprotected").isNotEmpty();
            } else {
                assertThat(shots).as("case %d", c).isEmpty();
            }
            assertThat(fired(r, ClassTable.Barrel.TRAP)).as("and no trap without the trigger").isEmpty();
        }
    }

    // ---- no barrels: the Smasher line (01 §4, "The third tier") -------------------------------

    @Test
    @DisplayName("a Smasher has no barrels: trigger or not, player or bot, it fires nothing")
    void smasherFiresNothing() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        levelTo(r, t, 30);
        assertThat(r.chooseClass(t, ClassTable.SMASHER)).as("from Basic, at 30").isTrue();
        for (int i = 0; i < 100; i++) {
            t.wantsFire = true;
            t.attacking = true;
            run(r, 1);
        }
        t.playerControlled = false;                     // a bot always holds the trigger
        run(r, 100);
        assertThat(bullets(r)).isEmpty();
    }

    @Test
    @DisplayName("a Smasher's caps are 10 in the body's four stats and 0 in the bullets'; points spent before stay")
    void smasherCaps() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        TankStats s = r.world().tankStats[t.id];
        levelTo(r, t, 30);
        assertThat(r.spendPoint(t, Stat.BULLET_DAMAGE)).isTrue();
        for (int i = 0; i < Stat.MAX_POINTS_PER_STAT; i++) {
            r.spendPoint(t, Stat.BODY_DAMAGE);
        }
        assertThat(r.spendPoint(t, Stat.BODY_DAMAGE)).as("a Basic's cap is 7").isFalse();
        assertThat(r.chooseClass(t, ClassTable.SMASHER)).isTrue();
        for (int i = 0; i < 3; i++) {
            assertThat(r.spendPoint(t, Stat.BODY_DAMAGE)).as("a Smasher's is 10").isTrue();
        }
        assertThat(r.spendPoint(t, Stat.BODY_DAMAGE)).isFalse();
        assertThat(s.points[Stat.BODY_DAMAGE]).isEqualTo((byte) 10);
        assertThat(r.spendPoint(t, Stat.RELOAD)).as("nothing in the bullets' stats").isFalse();
        assertThat(s.points[Stat.BULLET_DAMAGE]).as("spent before, and kept").isEqualTo((byte) 1);
        assertThat(r.spendPoint(t, Stat.COUNT)).as("not a stat").isFalse();
    }

    @Test
    @DisplayName("a Spike's body hurts half as much again as a Smasher's, and a Mega Smasher's a quarter")
    void spike() {
        float[] dealt = new float[3];
        int[] classes = {ClassTable.SMASHER, ClassTable.SPIKE, ClassTable.MEGA_SMASHER};
        for (int c = 0; c < 3; c++) {
            Room r = room(Fixtures.UNPROTECTED);
            Entity t = gunner(r);
            t.wantsFire = false;
            levelTo(r, t, 45);
            r.chooseClass(t, ClassTable.SMASHER);
            r.chooseClass(t, classes[c]);
            Entity block = r.spawnShape();
            block.x = 500f + 30f + 18f - 4f;            // just inside the tank's reach
            block.y = 500f;
            block.vx = block.vy = 0f;
            block.radius = 18f;
            block.mass = 1e6f;
            block.hp = block.maxHp = 1_000f;           // small enough that a float still counts 0.8
            run(r, 1);
            dealt[c] = 1_000f - block.hp;
        }
        assertThat(dealt[0]).isPositive();
        assertThat(dealt[1]).isCloseTo(dealt[0] * 1.5f, org.assertj.core.data.Offset.offset(1e-3f));
        assertThat(dealt[2]).as("a Mega Smasher's, a quarter again").isCloseTo(dealt[0] * 1.25f, org.assertj.core.data.Offset.offset(1e-3f));
    }

    @Test
    @DisplayName("a bullet that hits a Spike is used up half as much again as one that hits a Smasher")
    void spikeWearsBullets() {
        float[] worn = new float[2];
        int[] classes = {ClassTable.SMASHER, ClassTable.SPIKE};
        for (int c = 0; c < 2; c++) {
            Room r = room(Fixtures.UNPROTECTED);
            Entity smasher = r.spawnTank((byte) 1, 2L);
            smasher.x = 800f;
            smasher.y = 500f;
            smasher.playerControlled = true;
            levelTo(r, smasher, 45);
            r.chooseClass(smasher, ClassTable.SMASHER);
            r.chooseClass(smasher, classes[c]);
            Entity shooter = gunner(r);                 // at (500, 500), aiming at it
            run(r, 1);
            shooter.wantsFire = false;
            Entity bullet = bullets(r).get(0);
            bullet.hp = bullet.maxHp = 1_000f;          // it survives the hit, so the hit can be read
            for (int i = 0; i < 60 && bullet.hp == 1_000f; i++) {
                run(r, 1);
            }
            worn[c] = 1_000f - bullet.hp;
        }
        assertThat(worn[0]).as("a Smasher's body damage, 20 with no points").isEqualTo(20f);
        assertThat(worn[1]).isEqualTo(30f);
    }

    @Test
    @DisplayName("a bot that becomes a Smasher spends its points where a Smasher can: none on bullets")
    void botSmashersSpendOnTheBody() {
        int found = 0;
        for (long seed = 1; seed <= 60 && found < 3; seed++) {
            Room r = new Room(new World(2_000f, 2_000f, 256, 100f, seed), Fixtures.UNPROTECTED);
            Entity bot = r.spawnTank((byte) 0);
            r.grantExperience(bot, r.content().levels().xpRequired(45));
            TankStats s = r.world().tankStats[bot.id];
            ClassTable.TankClass c = r.content().classes().get(s.classId);
            if (!c.parents().contains(ClassTable.SMASHER) && s.classId != ClassTable.SMASHER) {
                continue;
            }
            found++;
            assertThat(s.unspentPoints).as("seed %d: all 33 spent", seed).isZero();
            for (int stat : new int[] {Stat.BULLET_SPEED, Stat.BULLET_PENETRATION, Stat.BULLET_DAMAGE, Stat.RELOAD}) {
                assertThat(s.points[stat]).as("seed %d, %s", seed, Stat.name(stat))
                        .isLessThanOrEqualTo((byte) c.cap(stat));
            }
        }
        assertThat(found).as("some bots take the Smasher's road").isEqualTo(3);
    }

    // ---- hiding (01 §4, "Hidden tanks", D-23) -------------------------------------------------

    @Test
    @DisplayName("a Stalker still, the trigger let go, for 50 ticks is hidden; moving or the trigger shows it at once")
    void stalkerHides() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.ASSASSIN);
        assertThat(r.chooseClass(t, ClassTable.STALKER)).isTrue();
        run(r, 49);
        assertThat(t.hidden).as("49 ticks").isFalse();
        run(r, 1);
        assertThat(t.hidden).as("50").isTrue();

        t.moveX = 1f;
        run(r, 1);
        assertThat(t.hidden).as("moving").isFalse();
        t.moveX = 0f;
        run(r, 49);
        assertThat(t.hidden).isFalse();
        run(r, 1);
        assertThat(t.hidden).as("still again for 50").isTrue();

        t.attacking = true;                             // the trigger, as the arena sets it
        t.wantsFire = true;
        run(r, 1);
        assertThat(t.hidden).as("the trigger").isFalse();
        t.attacking = false;
        run(r, 50);
        assertThat(t.hidden).isTrue();
        t.vx = 3f;                                      // pushed is not moving
        run(r, 1);
        assertThat(t.hidden).as("knocked, not moving").isTrue();
    }

    @Test
    @DisplayName("a bot never stands still, so never hides; a class that does not hide never does")
    void onlyStillPlayersHide() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity bot = r.spawnTank((byte) 0);
        levelTo(r, bot, 45);
        r.chooseClass(bot, ClassTable.SNIPER);
        r.chooseClass(bot, ClassTable.ASSASSIN);
        assertThat(r.chooseClass(bot, ClassTable.STALKER)).isTrue();
        Entity assassin = gunner(r);
        assassin.wantsFire = false;
        levelTo(r, assassin, 30);
        r.chooseClass(assassin, ClassTable.SNIPER);
        r.chooseClass(assassin, ClassTable.ASSASSIN);
        run(r, 200);
        assertThat(bot.hidden).isFalse();
        assertThat(assassin.hidden).isFalse();
    }

    @Test
    @DisplayName("a tank is seen arriving: one put back into a room long running is not hidden on its first tick")
    void arrivingIsBeingSeen() {
        Room r = room(Fixtures.UNPROTECTED);
        run(r, 100);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.ASSASSIN);
        r.chooseClass(t, ClassTable.STALKER);
        run(r, 1);
        assertThat(t.hidden).isFalse();
        run(r, 49);
        assertThat(t.hidden).as("still, since it arrived").isTrue();
    }

    @Test
    @DisplayName("a turret does not see a hidden tank")
    void turretsDoNotSeeTheHidden() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity auto = gunner(r);
        auto.wantsFire = false;
        levelTo(r, auto, 45);
        r.chooseClass(auto, ClassTable.SNIPER);
        r.chooseClass(auto, ClassTable.TRAPPER);
        r.chooseClass(auto, ClassTable.AUTO_TRAPPER);
        Entity stalker = target(r, 800f, 500f);
        levelTo(r, stalker, 45);
        r.chooseClass(stalker, ClassTable.SNIPER);
        r.chooseClass(stalker, ClassTable.ASSASSIN);
        r.chooseClass(stalker, ClassTable.STALKER);
        assertThat(shotsDuring(r, 40)).as("seen: shot at").isNotEmpty();
        run(r, 20);
        assertThat(stalker.hidden).isTrue();
        assertThat(shotsDuring(r, 40)).as("hidden: not").isEmpty();
    }

    // ---- the Necromancer and the Factory (01 §4, "The third tier") ----------------------------

    /**
     * A shape of {@code kind} with a sliver of health left, at {@code x, y}, that does not hurt
     * what breaks it: a drone that breaks a real square takes its 8, and dies on its second.
     */
    private static Entity square(Room r, float x, float y, byte kind) {
        Entity s = r.spawnShape();
        s.subtype = kind;
        s.radius = 18f;
        s.x = x;
        s.y = y;
        s.vx = s.vy = 0f;
        s.hp = 0.1f;
        s.damage = 0f;
        return s;
    }

    private static Entity necromancer(Room r) {
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.OVERSEER);
        assertThat(r.chooseClass(t, ClassTable.NECROMANCER)).isTrue();
        return t;
    }

    @Test
    @DisplayName("a square a Necromancer breaks becomes its drone where it was; a triangle does not, nor anyone else's square")
    void squaresBecomeDrones() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = necromancer(r);
        run(r, 30);
        assertThat(drones(r)).as("it launches none").isEmpty();

        Entity sq = square(r, 500f + 30f + 18f - 4f, 500f, ShapeTable.SQUARE);   // touching its body
        float x = sq.x;
        run(r, 1);
        assertThat(sq.alive).isFalse();
        assertThat(drones(r)).singleElement().satisfies(d -> {
            assertThat(d.x).isEqualTo(x);
            assertThat(d.y).isEqualTo(500f);
            assertThat(d.radius).isEqualTo(12f);
            assertThat(d.ownerId).isEqualTo(t.id);
            assertThat(d.wireClass).isEqualTo(Entity.WIRE_UNIT);
        });

        square(r, 500f, 500f + 30f + 18f - 4f, (byte) 1);                       // a triangle
        run(r, 1);
        assertThat(drones(r)).as("not a triangle").hasSize(1);

        Entity other = r.spawnTank((byte) 2, 5L);
        other.playerControlled = true;
        other.x = 1_500f;
        other.y = 1_500f;
        square(r, 1_500f + 44f, 1_500f, ShapeTable.SQUARE);
        run(r, 1);
        assertThat(drones(r)).as("not another tank's square").hasSize(1);
    }

    @Test
    @DisplayName("a square its drone breaks becomes a drone too, up to twelve")
    void upToTwelve() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = necromancer(r);
        square(r, 544f, 500f, ShapeTable.SQUARE);
        run(r, 1);
        Entity first = drones(r).get(0);
        square(r, first.x, first.y, ShapeTable.SQUARE);                          // on the drone
        run(r, 1);
        assertThat(drones(r)).as("by the drone").hasSize(2);

        // Around its body, and time between them for each new drone to leave for its circle: a
        // square laid on a drone at rest wears it out.
        for (int i = 0; i < 14; i++) {
            float a = i * 0.45f;
            square(r, t.x + (float) Math.cos(a) * 44f, t.y + (float) Math.sin(a) * 44f, ShapeTable.SQUARE);
            run(r, 10);
        }
        assertThat(drones(r)).hasSize(12);
        assertThat(t.alive).isTrue();
    }

    @Test
    @DisplayName("a Battleship keeps sixteen small drones out at most, and each is gone after its 150 ticks")
    void battleship() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.TWIN);
        r.chooseClass(t, ClassTable.TWIN_FLANK);
        assertThat(r.chooseClass(t, ClassTable.BATTLESHIP)).as("from Twin Flank, as from Overseer").isTrue();
        run(r, 1);
        Entity first = drones(r).get(0);
        assertThat(first.radius).isCloseTo(5.6f, org.assertj.core.data.Offset.offset(1e-4f));
        run(r, 148);
        assertThat(first.alive).as("149 ticks").isTrue();
        run(r, 1);
        assertThat(first.alive).as("gone at 150").isFalse();
        run(r, 150);
        assertThat(drones(r)).as("never more than sixteen").hasSizeBetween(13, 16);
    }

    // ---- a body of its own size (01 §4, "The rest of the tree": part d) -----------------------

    private static Entity megaSmasher(Room r) {
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SMASHER);
        assertThat(r.chooseClass(t, ClassTable.MEGA_SMASHER)).isTrue();
        return t;
    }

    @Test
    @DisplayName("a Mega Smasher's body is 1.3 times the size, put back into the world or not; a tank after it is Basic's")
    void megaSmasherBody() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = megaSmasher(r);
        run(r, 1);
        assertThat(t.radius).isCloseTo(39f, org.assertj.core.data.Offset.offset(1e-4f));

        // Put back as a resumed stay is (02 §10): a fresh tank, and the stats copied in.
        TankStats saved = new TankStats();
        saved.copyFrom(r.world().tankStats[t.id]);
        r.world().kill(t);
        r.world().sweep();
        Entity back = r.spawnTank((byte) 0, 1L);
        back.playerControlled = true;
        r.world().tankStats[back.id].copyFrom(saved);
        run(r, 1);
        assertThat(back.radius).as("put back, the right size").isCloseTo(39f, org.assertj.core.data.Offset.offset(1e-4f));

        r.world().kill(back);
        r.world().sweep();
        Entity next = r.spawnTank((byte) 0, 2L);
        next.playerControlled = true;
        run(r, 1);
        assertThat(next.radius).as("the next tank in the seat is Basic").isEqualTo(30f);
    }

    @Test
    @DisplayName("a collision query reaches the largest body: a bullet grazing a Mega Smasher filed a cell away hits it")
    void theLargestBodyIsReached() {
        // Shapes no bigger than a tank, so that how far a query reaches is the body's alone.
        ShapeTable small = new ShapeTable(new ShapeTable.Type[] {
                new ShapeTable.Type((byte) 0, "square", 18f, 3f, 10f, 8f, 10, 600)});
        Room r = room(new Content(StatTable.defaults(), LevelTable.defaults(), small, Recovery.defaults(),
                Fixtures.NO_PROTECTION));
        Entity t = megaSmasher(r);
        t.x = 601f;                                     // filed in the grid's cell from 600
        t.y = 500f;
        run(r, 1);
        Entity shooter = r.spawnTank((byte) 1, 5L);
        shooter.x = 200f;
        shooter.y = 900f;
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        run(r, 1);
        shooter.wantsFire = false;
        Entity bullet = bullets(r).stream().filter(b -> b.ownerId == shooter.id).findFirst().orElseThrow();
        bullet.x = 556f;                                // 45 from its centre: inside 39 + 8, a cell over
        bullet.y = 500f;
        bullet.vx = 0f;
        bullet.vy = 0f;
        float hp = t.hp;
        run(r, 1);
        assertThat(t.hp).isLessThan(hp);
    }

    // ---- the Predator's zoom (01 §4, "The rest of the tree": part e) ---------------------------

    @Test
    @DisplayName("a Predator holding zoom sees 700 ahead along its aim; let go, of another class, or a bot, it does not")
    void predatorZoom() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.HUNTER);
        assertThat(r.chooseClass(t, ClassTable.PREDATOR)).isTrue();
        t.zooming = true;
        run(r, 1);
        assertThat(t.viewShiftX).isCloseTo(700f, org.assertj.core.data.Offset.offset(1e-3f));
        assertThat(t.viewShiftY).isCloseTo(0f, org.assertj.core.data.Offset.offset(1e-3f));
        t.aimAngle = (float) (Math.PI / 2);
        run(r, 1);
        assertThat(t.viewShiftY).as("along the aim").isCloseTo(700f, org.assertj.core.data.Offset.offset(1e-3f));
        t.zooming = false;
        run(r, 1);
        // Near, not equal: a cosine a hair below zero makes the shift -0.0, which is zero.
        assertThat(Math.hypot(t.viewShiftX, t.viewShiftY)).as("let go").isLessThan(1e-6);

        Entity hunter = gunner(r);
        hunter.wantsFire = false;
        levelTo(r, hunter, 45);
        r.chooseClass(hunter, ClassTable.SNIPER);
        r.chooseClass(hunter, ClassTable.HUNTER);
        hunter.zooming = true;
        Entity bot = r.spawnTank((byte) 0);
        levelTo(r, bot, 45);
        r.chooseClass(bot, ClassTable.SNIPER);
        r.chooseClass(bot, ClassTable.HUNTER);
        r.chooseClass(bot, ClassTable.PREDATOR);
        bot.zooming = true;
        run(r, 1);
        assertThat(Math.hypot(hunter.viewShiftX, hunter.viewShiftY)).as("a Hunter has no zoom").isLessThan(1e-6);
        assertThat(Math.hypot(bot.viewShiftX, bot.viewShiftY)).as("a bot has no view").isLessThan(1e-6);
    }

    // ---- missiles (01 §4, "The rest of the tree": part c) --------------------------------------

    private static List<Entity> missiles(Room r, int kind) {
        return bullets(r).stream().filter(b -> b.subtype == kind).toList();
    }

    private static Entity destroyerLine(Room r, int child) {
        Entity t = gunner(r);
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.MACHINE_GUN);
        r.chooseClass(t, ClassTable.DESTROYER);
        assertThat(r.chooseClass(t, child)).isTrue();
        run(r, 1);
        t.wantsFire = false;
        return t;
    }

    @Test
    @DisplayName("a Rocketeer's missile flies straight for 75 ticks, trailing a shot straight back every 3, its tank's")
    void rocketeer() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = destroyerLine(r, ClassTable.ROCKETEER);
        Entity rocket = missiles(r, ClassTable.Barrel.ROCKET).get(0);
        assertThat(rocket.wireClass).isEqualTo(Entity.WIRE_UNIT);
        assertThat(rocket.radius).isCloseTo(11.2f, org.assertj.core.data.Offset.offset(1e-4f));
        assertThat(rocket.vx).isEqualTo(8f);                      // 10 × 0.8
        assertThat(shotsDuring(r, 30)).as("every 3 ticks, straight back").hasSize(10)
                .allSatisfy(h -> assertThat(Math.abs(h)).isCloseTo(Math.PI, org.assertj.core.data.Offset.offset(1e-4)));
        assertThat(fired(r, ClassTable.Barrel.BULLET)).allSatisfy(b -> {
            assertThat(b.ownerId).as("its tank's").isEqualTo(t.id);
            assertThat(b.radius).isEqualTo(4f);
        });
        assertThat(rocket.vx).as("straight, unslowed").isEqualTo(8f);
        run(r, 74 - 31);
        assertThat(rocket.alive).as("74 ticks").isTrue();
        run(r, 1);
        assertThat(rocket.alive).as("gone at 75").isFalse();
    }

    @Test
    @DisplayName("a Skimmer's missile turns 0.2 a tick and fires two shots, either side of its facing, every 4 ticks")
    void skimmer() {
        Room r = room(Fixtures.UNPROTECTED);
        destroyerLine(r, ClassTable.SKIMMER);
        Entity skim = missiles(r, ClassTable.Barrel.SKIMMER).get(0);
        float before = skim.angle;
        run(r, 1);
        assertThat(skim.angle - before).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(1e-5f));
        List<Double> shots = shotsDuring(r, 40);
        assertThat(shots).as("ten pairs").hasSize(20);
        for (int i = 0; i < shots.size(); i += 2) {
            double apart = Math.abs(Math.IEEEremainder(shots.get(i) - shots.get(i + 1), 2 * Math.PI));
            assertThat(apart).as("a pair is either side").isCloseTo(Math.PI, org.assertj.core.data.Offset.offset(1e-3));
        }
    }

    @Test
    @DisplayName("a missile whose tank is gone flies on, silent")
    void aMissileOutlivesItsTankSilently() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = destroyerLine(r, ClassTable.ROCKETEER);
        Entity rocket = missiles(r, ClassTable.Barrel.ROCKET).get(0);
        r.world().kill(t);
        assertThat(shotsDuring(r, 20)).isEmpty();
        assertThat(rocket.alive).isTrue();
    }

    private static List<Entity> minions(Room r) {
        return bullets(r).stream().filter(b -> b.subtype == ClassTable.Barrel.MINION).toList();
    }

    @Test
    @DisplayName("a Factory keeps six minions out; while it attacks they shoot along its aim, its bullets; they go with it")
    void factory() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity t = gunner(r);
        t.wantsFire = false;
        levelTo(r, t, 45);
        r.chooseClass(t, ClassTable.SNIPER);
        r.chooseClass(t, ClassTable.OVERSEER);
        assertThat(r.chooseClass(t, ClassTable.FACTORY)).isTrue();
        run(r, 200);
        assertThat(minions(r)).hasSize(6).allSatisfy(m -> {
            assertThat(m.wireClass).isEqualTo(Entity.WIRE_UNIT);
            assertThat(m.radius).isEqualTo(12f);
        });
        assertThat(shotsDuring(r, 30)).as("not attacking: they hold fire").isEmpty();

        t.attacking = true;
        List<Double> headings = shotsDuring(r, 60);
        assertThat(headings).as("six minions, each every 24 ticks").hasSizeBetween(12, 18)
                .allSatisfy(h -> assertThat(h).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-4)));
        assertThat(fired(r, ClassTable.Barrel.BULLET)).allSatisfy(b -> {
            assertThat(b.ownerId).as("its tank's bullets").isEqualTo(t.id);
            assertThat(b.playerTag).isEqualTo(t.playerTag);
            assertThat(b.radius).isEqualTo(4.8f);
        });

        List<Entity> out = minions(r);
        r.world().kill(t);
        run(r, 1);
        assertThat(out).as("gone with their tank").allSatisfy(m -> assertThat(m.alive).isFalse());
    }

    @Test
    @DisplayName("a bot past level 45 takes a class of the third tier")
    void botsReachTheThirdTier() {
        Set<Integer> chosen = new HashSet<>();
        for (long seed = 1; seed <= 30; seed++) {
            Room r = new Room(new World(2_000f, 2_000f, 256, 100f, seed), Fixtures.UNPROTECTED);
            Entity bot = r.spawnTank((byte) 0);
            r.grantExperience(bot, r.content().levels().xpRequired(45));
            int c = r.world().tankStats[bot.id].classId;
            assertThat(c).as("seed %d", seed).isGreaterThanOrEqualTo(ClassTable.TRIPLET);
            chosen.add(c);
        }
        assertThat(chosen).as("more than one road taken").hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("experience granted from outside a kill is spent by a bot as earned, and only banked by a player")
    void grantedExperience() {
        Room r = room(Fixtures.UNPROTECTED);
        Entity bot = r.spawnTank((byte) 0);
        r.grantExperience(bot, r.content().levels().xpRequired(45));
        TankStats s = r.world().tankStats[bot.id];
        assertThat(s.level).isEqualTo(45);
        assertThat(s.unspentPoints).as("spent").isZero();
        assertThat(s.classId).as("both tiers taken").isGreaterThanOrEqualTo(ClassTable.TRIPLE_SHOT);

        Entity player = gunner(r);
        r.grantExperience(player, r.content().levels().xpRequired(45));
        TankStats p = r.world().tankStats[player.id];
        assertThat(p.level).isEqualTo(45);
        assertThat(p.unspentPoints).as("a player chooses for themself").isPositive();
        assertThat(p.classId).isEqualTo(ClassTable.BASIC);

        Entity shape = r.spawnShape();
        r.grantExperience(shape, 1_000);                        // not a tank: nothing to pay
        r.world().kill(bot);
        r.grantExperience(bot, 1_000);
        assertThat(s.xp).as("nor a dead one").isEqualTo(r.content().levels().xpRequired(45));
    }

    @Test
    @DisplayName("a bot past level 30 takes a class of each tier on the way")
    void botsReachTheSecondTier() {
        Set<Integer> chosen = new HashSet<>();
        for (long seed = 1; seed <= 30; seed++) {
            World w = new World(2_000f, 2_000f, 256, 100f, seed);
            Room r = new Room(w, Fixtures.UNPROTECTED);
            Entity bot = r.spawnTank((byte) 0);
            bot.x = 1_000f;
            bot.y = 1_000f;
            bot.reloadTicks = 0;
            w.tankStats[bot.id].addXp(r.content().levels().xpRequired(30) - 3_000, r.content().levels());
            Entity alpha = r.spawnShape();
            alpha.subtype = 3;                                    // 3 000: past level 30 with the rest
            alpha.x = bot.x + (float) Math.cos(bot.angle) * 60f;
            alpha.y = bot.y + (float) Math.sin(bot.angle) * 60f;
            alpha.hp = 1f;
            alpha.vx = alpha.vy = 0f;
            run(r, 1);
            int c = w.tankStats[bot.id].classId;
            assertThat(r.content().classes().get(c).opensAt()).as("seed %d", seed).isEqualTo(30);
            chosen.add(c);
        }
        assertThat(chosen).as("more than one road taken").hasSizeGreaterThan(3);
    }

    @Test
    @DisplayName("every bullet flies at a whole number of half units a tick, which the wire carries exactly")
    void speedsAreHalfUnits() {
        StatTable stats = StatTable.defaults();
        for (ClassTable.TankClass c : List.of(ClassTable.defaults().get(ClassTable.BASIC),
                ClassTable.defaults().get(ClassTable.SNIPER))) {
            for (int points = 0; points <= Stat.MAX_POINTS_PER_STAT; points++) {
                float raw = Room.BULLET_SPEED * stats.valueOf(Stat.BULLET_SPEED, 45, points)
                        * c.barrels().get(0).speedMul();
                float speed = Room.bulletSpeed(raw);
                assertThat(speed * 2).isEqualTo((float) Math.round(speed * 2));
                assertThat(Math.abs(speed - raw)).isLessThanOrEqualTo(0.25f);
            }
        }
        assertThat(Room.bulletSpeed(500f)).as("at most what a byte of half units holds").isEqualTo(127.5f);
    }

    @Test
    @DisplayName("the class table's version is a hash of its JSON: the same table the same, any change another (D-24)")
    void theTableIsVersionedByItsContent() {
        ClassTable shipped = ClassTable.defaults();
        assertThat(shipped.version()).isEqualTo(ClassTable.defaults().version());
        assertThat(shipped.json()).isEqualTo(ClassTable.defaults().json());
        assertThat(shipped.version()).as("an unsigned 32 bits, as the Welcome's varint carries it")
                .isBetween(0L, 0xFFFFFFFFL);

        ClassTable a = new ClassTable(List.of(new ClassTable.TankClass(0, "Basic", 1, -1, 1f, 1f,
                List.of(new ClassTable.Barrel(0f, 0f, 0f, 1f, 1f, 1f, 75, 0f)))));
        ClassTable b = new ClassTable(List.of(new ClassTable.TankClass(0, "Basic", 1, -1, 1f, 1f,
                List.of(new ClassTable.Barrel(0f, 0f, 0f, 1f, 1f, 1f, 76, 0f)))));
        assertThat(a.version()).as("a lifetime one tick longer").isNotEqualTo(b.version());

        String json = shipped.json();
        assertThat(json).startsWith("[{\"id\":0,\"name\":\"Basic\"").endsWith("]");
        assertThat(json.split("\"id\":", -1).length - 1).as("every class, once").isEqualTo(shipped.size());
        assertThat(json).contains("\"name\":\"Mega Smasher\"", "\"zoom\":700.0", "\"caps\":[10,10,10,0,0,0,0,10]");
    }

    @Test
    @DisplayName("a class table is refused with a loop in it, a delay past the reload, or a lifetime past a byte")
    void badTablesAreRefused() {
        ClassTable.Barrel ok = new ClassTable.Barrel(0f, 0f, 0f, 1f, 1f, 1f, 75, 0f);
        ClassTable.TankClass basic = new ClassTable.TankClass(0, "Basic", 1, -1, 1f, 1f, List.of(ok));
        assertThatThrownBy(() -> new ClassTable(List.of(basic,
                new ClassTable.TankClass(1, "a", 15, 2, 1f, 1f, List.of(ok)),
                new ClassTable.TankClass(2, "b", 15, 1, 1f, 1f, List.of(ok)))))
                .as("a parent after its child").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassTable(List.of(basic,
                new ClassTable.TankClass(1, "self", 15, 1, 1f, 1f, List.of(ok)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassTable.Barrel(0f, 0f, 1f, 1f, 1f, 1f, 75, 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassTable.Barrel(0f, 0f, 0f, 1f, 1f, 1f, 256, 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassTable(List.of(
                new ClassTable.TankClass(0, "Basic", 1, -1, 1f, 1f, List.of(ok)),
                new ClassTable.TankClass(0, "again", 15, 0, 1f, 1f, List.of(ok)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
