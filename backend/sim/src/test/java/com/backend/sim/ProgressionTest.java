package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Experience, levels, points, and what the simulation does differently once they exist. */
class ProgressionTest {

    private static final LevelTable LEVELS = LevelTable.defaults();
    private static final StatTable STATS = StatTable.defaults();

    private static void run(Room r, int ticks) {
        PhaseTimer t = Room.newTimer();
        for (int i = 0; i < ticks; i++) {
            r.step(t);
        }
    }

    // ---- one tank's progression ---------------------------------------------------------

    @Test
    @DisplayName("experience crosses several levels at once when one kill is worth them")
    void oneKillCanBeWorthSeveralLevels() {
        TankStats s = new TankStats();

        // An alpha pentagon is 3 000, which is level 26 from a standing start. A single
        // step would have banked the experience and handed out one level.
        int gained = s.addXp(3_000, LEVELS);

        assertThat(gained).isGreaterThan(20);
        assertThat(s.level).isEqualTo(1 + gained);
        assertThat(s.xp).isEqualTo(3_000);
        assertThat(s.unspentPoints).isEqualTo(LEVELS.totalPointsBy(s.level));
        assertThat(LEVELS.xpRequired(s.level)).isLessThanOrEqualTo(3_000);
        assertThat(LEVELS.xpRequired(s.level + 1)).isGreaterThan(3_000);
    }

    @Test
    @DisplayName("levelling stops at the top rather than running off the table")
    void levellingStopsAtTheTop() {
        TankStats s = new TankStats();

        s.addXp(Integer.MAX_VALUE / 2, LEVELS);

        assertThat(s.level).isEqualTo(LEVELS.maxLevel());
        assertThat(s.unspentPoints).isEqualTo(33);
        assertThat(s.addXp(1_000_000, LEVELS)).as("no more levels to gain").isZero();
    }

    @Test
    @DisplayName("a point is spent only when there is one, and never past the cap")
    void spendingIsBounded() {
        TankStats s = new TankStats();
        s.addXp(4, LEVELS);                       // level 2, one point

        assertThat(s.unspentPoints).isEqualTo(1);
        assertThat(s.spendPoint(Stat.BULLET_DAMAGE, Stat.MAX_POINTS_PER_STAT)).isTrue();
        assertThat(s.unspentPoints).isZero();
        assertThat(s.spendPoint(Stat.BULLET_DAMAGE, Stat.MAX_POINTS_PER_STAT)).as("nothing left to spend").isFalse();

        s.addXp(30_000, LEVELS);                  // everything the curve has
        for (int i = 0; i < Stat.MAX_POINTS_PER_STAT; i++) {
            s.spendPoint(Stat.RELOAD, Stat.MAX_POINTS_PER_STAT);
        }
        assertThat(s.points[Stat.RELOAD]).isEqualTo((byte) Stat.MAX_POINTS_PER_STAT);
        assertThat(s.spendPoint(Stat.RELOAD, Stat.MAX_POINTS_PER_STAT)).as("capped").isFalse();
        // A client can send any byte it likes, and none of these is worth a disconnection.
        assertThat(s.spendPoint(-1, Stat.MAX_POINTS_PER_STAT)).isFalse();
        assertThat(s.spendPoint(Stat.COUNT, Stat.MAX_POINTS_PER_STAT)).isFalse();
    }

    @Test
    @DisplayName("effective stats are recomputed when something changes, and not otherwise")
    void recomputeIsDirtyDriven() {
        TankStats s = new TankStats();
        s.refresh(STATS);

        assertThat(s.isDirty()).isFalse();
        assertThat(s.value(Stat.MAX_HEALTH)).isEqualTo(52f);

        s.addXp(4, LEVELS);                       // level 2: max health moves with level
        assertThat(s.isDirty()).as("a level up changes max health").isTrue();
        s.refresh(STATS);
        assertThat(s.value(Stat.MAX_HEALTH)).isEqualTo(54f);

        s.spendPoint(Stat.MAX_HEALTH, Stat.MAX_POINTS_PER_STAT);
        assertThat(s.isDirty()).isTrue();
        s.refresh(STATS);
        assertThat(s.value(Stat.MAX_HEALTH)).isEqualTo(74f);
    }

    @Test
    @DisplayName("a recycled slot hands back a level-1 tank, not the last one's build")
    void slotsAreHandedBackClean() {
        World w = new World(1_000f, 1_000f, 8, 100f, 1L);
        Room r = new Room(w, Fixtures.UNPROTECTED);

        Entity first = r.spawnTank((byte) 0, 1L);
        TankStats stats = w.tankStats[first.id];
        stats.addXp(5_000, LEVELS);
        stats.spendPoint(Stat.BULLET_DAMAGE, Stat.MAX_POINTS_PER_STAT);
        int slot = first.id;

        w.kill(first);
        w.sweep();
        Entity second = r.spawnTank((byte) 0, 2L);

        assertThat(second.id).as("the same seat, to make the point").isEqualTo(slot);
        assertThat(w.tankStats[slot]).as("and the same object, not a new one").isSameAs(stats);
        assertThat(stats.level).isEqualTo(1);
        assertThat(stats.xp).isZero();
        assertThat(stats.unspentPoints).isZero();
        assertThat(stats.points[Stat.BULLET_DAMAGE]).isZero();
        assertThat(second.maxHp).isEqualTo(52f);
    }

    // ---- what the simulation does with them ---------------------------------------------

    @Test
    @DisplayName("a tank that never stops shooting levels up, and gets tougher for it")
    void tanksLevelUpFromPlay() {
        World w = new World(2_000f, 2_000f, 2_048, 150f, 7L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        r.resetForNewMatch(400);
        for (int i = 0; i < 8; i++) {
            r.spawnTank((byte) 0);
        }

        run(r, 1_500);

        int best = 0;
        float bestMaxHp = 0;
        for (int i = 0; i < w.tanks.size; i++) {
            Entity e = w.entities[w.tanks.items[i]];
            TankStats s = w.tankStats[e.id];
            if (s.level > best) {
                best = s.level;
                bestMaxHp = e.maxHp;
            }
        }
        assertThat(best).as("a minute of shooting into a full room").isGreaterThan(1);
        // The level has to reach the entity, not just the stat block: 50 + 2 per level.
        assertThat(bestMaxHp).isGreaterThan(52f);
    }

    @Test
    @DisplayName("a bot spends its points as it earns them, so its reload actually improves")
    void botsSpendWhatTheyEarn() {
        World w = new World(2_000f, 2_000f, 2_048, 150f, 21L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        r.resetForNewMatch(400);
        for (int i = 0; i < 8; i++) {
            r.spawnTank((byte) 0);
        }

        run(r, 1_500);

        int spent = 0;
        int unspent = 0;
        for (int i = 0; i < w.tanks.size; i++) {
            TankStats s = w.tankStats[w.entities[w.tanks.items[i]].id];
            for (byte p : s.points) {
                spent += p;
            }
            unspent += s.unspentPoints;
        }
        // Bullet count is what a tick costs, and reload is what sets bullet count. A load
        // measurement taken from bots that banked their points would be an underestimate.
        assertThat(spent).as("points reached the stats").isPositive();
        assertThat(unspent).as("and none were left sitting in the bank").isZero();
        // Spread evenly, reload included: filled in index order, every point went to the
        // first stats and reload, the seventh, got none for forty levels.
        int reload = 0;
        for (int i = 0; i < w.tanks.size; i++) {
            TankStats s = w.tankStats[w.entities[w.tanks.items[i]].id];
            int least = Integer.MAX_VALUE;
            int most = 0;
            for (byte p : s.points) {
                least = Math.min(least, p);
                most = Math.max(most, p);
            }
            assertThat(most - least).as("tank %d's points, spread", i).isLessThanOrEqualTo(1);
            reload += s.points[Stat.RELOAD];
        }
        assertThat(reload).as("reload, across the bots").isPositive();
    }

    @Test
    @DisplayName("experience for a kill goes nowhere when the shooter's seat has been taken")
    void staleSeatIsNotCredited() {
        World w = new World(1_000f, 1_000f, 32, 100f, 5L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        PhaseTimer t = Room.newTimer();

        Entity shooter = r.spawnTank((byte) 0, 1L);
        shooter.x = 100f;
        shooter.y = 500f;
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        r.step(t);

        assertThat(w.bullets.size).isEqualTo(1);
        Entity bullet = w.entities[w.bullets.items[0]];
        assertThat(bullet.ownerGeneration).isEqualTo(shooter.generation);
        int seat = shooter.id;

        // The shooter dies and the seat is handed to somebody else, which is the ordinary
        // course of events in a busy room — a bullet outlives its shooter every time.
        w.kill(shooter);
        w.sweep();
        Entity newcomer = r.spawnTank((byte) 0, 2L);
        newcomer.x = 900f;
        newcomer.y = 100f;
        newcomer.playerControlled = true;
        newcomer.wantsFire = false;
        assertThat(newcomer.id).as("the same seat").isEqualTo(seat);

        Entity shape = r.spawnShape();
        shape.x = bullet.x + 12f;
        shape.y = bullet.y;
        shape.hp = 1f;
        shape.vx = 0f;
        shape.vy = 0f;
        run(r, 3);

        assertThat(shape.alive).as("the shot still lands — the bullet is real").isFalse();
        // And credits nobody. Slot plus generation is the only pair that identifies a tank:
        // every bot shares player tag 0, so the tag alone would have paid the newcomer.
        assertThat(w.tankStats[seat].xp).isZero();
        assertThat(w.tankStats[seat].level).isEqualTo(1);
    }

    @Test
    @DisplayName("a live shooter is credited, which is the other half of that check")
    void liveShooterIsCredited() {
        World w = new World(1_000f, 1_000f, 32, 100f, 5L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        PhaseTimer t = Room.newTimer();

        Entity shooter = r.spawnTank((byte) 0, 1L);
        shooter.x = 100f;
        shooter.y = 500f;
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        r.step(t);

        Entity bullet = w.entities[w.bullets.items[0]];
        Entity shape = r.spawnShape();
        shape.x = bullet.x + 12f;
        shape.y = bullet.y;
        shape.hp = 1f;
        shape.vx = 0f;
        shape.vy = 0f;
        run(r, 3);

        assertThat(shape.alive).isFalse();
        assertThat(w.tankStats[shooter.id].xp)
                .as("worth what its kind is worth").isEqualTo(r.content().shapes().xpOf(shape.subtype));
    }

    @Test
    @DisplayName("a bullet grazing a shape far larger than a tank still hits it")
    void collisionReachesPastATankRadius() {
        ShapeTable alphasOnly = new ShapeTable(new ShapeTable.Type[] {
                new ShapeTable.Type((byte) 3, "alpha pentagon", 80f, 40f, 3_000f, 20f, 3_000, 1),
        });
        // Cells smaller than the shape, which is the case that exposes the reach: the grid
        // files an entity by its centre, so a query has to span the *other* radius too.
        World w = new World(1_000f, 1_000f, 64, 32f, 5L);
        Room r = new Room(w, new Content(STATS, LEVELS, alphasOnly, Recovery.defaults(),
                Fixtures.NO_PROTECTION));
        PhaseTimer t = Room.newTimer();

        Entity alpha = r.spawnShape();
        alpha.x = 500f;
        alpha.y = 500f;
        alpha.vx = 0f;
        alpha.vy = 0f;

        Entity shooter = r.spawnTank((byte) 0, 1L);
        shooter.x = 300f;
        shooter.y = 440f;                       // 60 units off centre: a graze, not a hit
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        r.step(t);
        shooter.wantsFire = false;

        run(r, 25);

        // 60 is well inside the 88 that a bullet and an alpha pentagon overlap at, but well
        // outside the 38 a query would reach if it assumed everything was tank-sized.
        assertThat(alpha.hp).as("the graze connected").isLessThan(3_000f);
    }

    @Test
    @DisplayName("a level-up raises current health, not only the ceiling")
    void levellingHealsByTheIncrease() {
        World w = new World(1_000f, 1_000f, 32, 100f, 41L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity tank = r.spawnTank((byte) 0, 1L);
        tank.playerControlled = true;            // still, and not shooting
        tank.hp = 30f;                           // hurt: 30 of 52
        w.tankStats[tank.id].addXp(4, LEVELS);   // level 2: max health 52 -> 54

        r.step(Room.newTimer());

        // Levelling that only raised the ceiling would leave the tank at the same health with
        // a longer bar — worse off, in proportion, the moment it earned something. Deleting
        // the two lines that prevent that used to leave every test green.
        assertThat(tank.maxHp).isEqualTo(54f);
        assertThat(tank.hp).as("30 plus the 2 the level added, plus a tick of regen")
                .isGreaterThan(31.9f).isLessThan(32.1f);
    }

    @Test
    @DisplayName("killing a tank that has earned nothing is still worth the floor")
    void aFreshTankIsWorthTheFloor() {
        World w = new World(1_000f, 1_000f, 32, 100f, 42L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        PhaseTimer t = Room.newTimer();
        Entity shooter = r.spawnTank((byte) 0, 1L);
        shooter.x = 300f;
        shooter.y = 500f;
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        Entity victim = r.spawnTank((byte) 1, 2L);
        victim.x = 420f;
        victim.y = 500f;
        victim.hp = 1f;
        victim.playerControlled = true;
        assertThat(w.tankStats[victim.id].xp).as("a fresh spawn has earned nothing").isZero();

        for (int i = 0; i < 20 && victim.alive; i++) {
            r.step(t);
        }

        assertThat(victim.alive).isFalse();
        // A quarter of nothing is nothing, and a kill worth nothing makes spawn-killing free.
        assertThat(w.tankStats[shooter.id].xp).isEqualTo(Room.TANK_KILL_XP_FLOOR);
    }

    @Test
    @DisplayName("a reload that rounds to zero ticks is held at one, not a crashed room")
    void reloadNeverReachesZero() {
        StatTable.Entry[] entries = new StatTable.Entry[Stat.COUNT];
        for (int i = 0; i < Stat.COUNT; i++) {
            entries[i] = STATS.entry(i);
        }
        // A future table whose reload is under half a tick. The shipped one bottoms out at
        // 3.5, so nothing exercised this — and without the floor, spawning a tank calls
        // nextInt(0), which throws on the room thread.
        entries[Stat.RELOAD] = new StatTable.Entry(0.3f, 0f, 0f, StatTable.SCALE);
        Content fast = new Content(new StatTable(entries), LEVELS, ShapeTable.defaults());
        World w = new World(1_000f, 1_000f, 256, 100f, 43L);
        Room r = new Room(w, fast);

        Entity tank = r.spawnTank((byte) 0);     // threw ArithmeticException without the floor
        assertThat(tank).isNotNull();
        r.step(Room.newTimer());
        int afterOne = w.bullets.size;
        r.step(Room.newTimer());

        assertThat(w.bullets.size - afterOne).as("at most one shot per tick").isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a populated room contains more than one kind of shape")
    void roomsHaveVariety() {
        World w = new World(3_000f, 3_000f, 4_096, 200f, 13L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        r.resetForNewMatch(1_000);

        boolean[] seen = new boolean[256];
        int kinds = 0;
        for (int i = 0; i < w.shapes.size; i++) {
            byte id = w.entities[w.shapes.items[i]].subtype;
            if (!seen[id]) {
                seen[id] = true;
                kinds++;
            }
        }
        assertThat(kinds).as("a thousand shapes should turn up every kind").isEqualTo(4);
    }
}
