package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Domination's dominators (01 §8.7, Q-29): anchored, and captured rather than killed. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DominationTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    private static Room room() {
        return new Room(new World(2_000f, 2_000f, 512, 100f, 9L), Fixtures.UNPROTECTED);
    }

    /** A dominator as the arena makes one: anchored, captured not killed, of its class. */
    private static Entity dominator(Room r, byte team, float x, float y) {
        Entity d = r.spawnTank(team);
        d.x = x;
        d.y = y;
        d.anchored = true;
        d.captures = true;
        r.assignClass(d, ClassTable.DOMINATOR);
        return d;
    }

    /** A player's tank, aiming along +x with its trigger held. */
    private static Entity player(Room r, byte team, long tag, float x, float y) {
        Entity t = r.spawnTank(team, tag);
        t.x = x;
        t.y = y;
        t.playerControlled = true;
        t.aimAngle = 0f;
        t.wantsFire = true;
        t.reloadTicks = 0;
        return t;
    }

    @Test
    @DisplayName("the Dominator's class: appended, reached by no upgrade, one turret all round, twice a body, eight times the health, no speed")
    void theClass() {
        ClassTable classes = ClassTable.defaults();
        ClassTable.TankClass d = classes.get(ClassTable.DOMINATOR);
        assertThat(ClassTable.DOMINATOR).as("appended: ids are on the wire").isEqualTo(classes.size() - 1);
        assertThat(d.name()).isEqualTo("Dominator");
        assertThat(d.barrels()).hasSize(1);
        assertThat(d.barrels().get(0).turret()).isTrue();
        assertThat(d.barrels().get(0).arc()).isEqualTo((float) Math.PI);
        assertThat(d.bodySize()).isEqualTo(2f);
        assertThat(d.healthMul()).isEqualTo(8f);
        assertThat(d.caps().get(Stat.MOVEMENT_SPEED)).isZero();
        for (int from = 0; from < classes.size(); from++) {
            for (int level = 0; level <= 1_000; level++) {
                assertThat(classes.mayChoose(from, level, ClassTable.DOMINATOR)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("an anchored tank drives nowhere and is not moved by a tank pushing into it")
    void anchored() {
        Room r = room();
        Entity d = dominator(r, (byte) 0, 1_000f, 1_000f);
        d.captures = false;
        d.hp = d.maxHp = 1e9f;                             // outlasts the pushing
        Entity pusher = player(r, (byte) 1, 7L, 900f, 1_000f);
        pusher.wantsFire = false;
        pusher.moveX = 1f;                                  // straight into it
        for (int i = 0; i < 200; i++) {
            run(r, 1);
            assertThat(d.x).as("tick %d", i).isEqualTo(1_000f);
            assertThat(d.y).isEqualTo(1_000f);
        }
        assertThat(pusher.x).as("the pusher is held off it").isLessThan(1_000f - d.radius);
    }

    @Test
    @DisplayName("a player's lethal blow captures it: the player's team, full health, and paid as a kill")
    void aPlayerCaptures() {
        Room r = room();
        Entity d = dominator(r, (byte) 0, 700f, 500f);
        run(r, 1);                                          // its class's health applied
        d.hp = 1f;
        Entity shooter = player(r, (byte) 1, 42L, 500f, 500f);
        int xpBefore = r.world().tankStats[shooter.id].xp;
        int kills = r.kills().size();
        for (int i = 0; i < 200 && d.team == 0; i++) {
            run(r, 1);
        }
        assertThat(d.team).as("taken by the shooter's team").isEqualTo((byte) 1);
        assertThat(d.alive).isTrue();
        assertThat(d.hp).isEqualTo(d.maxHp);
        assertThat(r.kills().size()).as("logged as a kill").isGreaterThan(kills);
        assertThat(r.world().tankStats[shooter.id].xp).as("and paid").isGreaterThan(xpBefore);
    }

    @Test
    @DisplayName("a player's tank that rams it to death captures it too")
    void aBodyBlowCaptures() {
        Room r = room();
        Entity d = dominator(r, (byte) 2, 1_000f, 1_000f);
        run(r, 1);
        d.hp = 1f;
        Entity rammer = player(r, (byte) 1, 7L, 900f, 1_000f);
        rammer.wantsFire = false;
        rammer.moveX = 1f;
        for (int i = 0; i < 200 && d.team == 2; i++) {
            run(r, 1);
        }
        assertThat(d.team).isEqualTo((byte) 1);
        assertThat(d.alive).isTrue();
        assertThat(d.hp).isEqualTo(d.maxHp);
    }

    @Test
    @DisplayName("a blow from a tank no player drives leaves it as it was, at full health")
    void onlyAPlayerCaptures() {
        Room r = room();
        Entity d = dominator(r, (byte) 2, 700f, 500f);
        run(r, 1);
        d.hp = 1f;
        Entity shooter = r.spawnTank((byte) 1);            // no player's tag: an arena's own tank
        shooter.x = 500f;
        shooter.y = 500f;
        shooter.playerControlled = true;                   // aimed and firing, for the test
        shooter.aimAngle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        for (int i = 0; i < 200 && d.hp < d.maxHp; i++) {
            run(r, 1);
        }
        assertThat(d.team).isEqualTo((byte) 2);
        assertThat(d.alive).isTrue();
        assertThat(d.hp).as("struck down, and back at full").isEqualTo(d.maxHp);
    }

    @Test
    @DisplayName("a dominator's turret does not aim at another dominator; a player's turret does")
    void turretsLeaveDominatorsAlone() {
        Room r = room();
        dominator(r, (byte) 1, 500f, 500f);
        dominator(r, (byte) 2, 700f, 500f);
        run(r, 60);
        assertThat(r.world().bullets.size).as("neither fires at the other").isZero();

        World w = r.world();
        while (w.shapes.size > 0) {
            w.kill(w.entities[w.shapes.items[0]]);           // nothing else for a turret to aim at
        }
        Entity p = player(r, (byte) 1, 9L, 500f, 700f);
        p.wantsFire = false;
        r.assignClass(p, ClassTable.DOMINATOR);             // a turret that aims itself
        run(r, 60);
        assertThat(w.bullets.size).as("the player's turret fires at the enemy's").isPositive();
        Entity shot = w.entities[w.bullets.items[0]];
        assertThat(shot.vx).as("toward it, up and to the right").isPositive();
        assertThat(shot.vy).isNegative();
    }

    @Test
    @DisplayName("a slot handed back forgets it was anchored and captured")
    void aSlotForgets() {
        Entity e = new Entity();
        e.anchored = true;
        e.captures = true;
        e.reset();
        assertThat(e.anchored).isFalse();
        assertThat(e.captures).isFalse();
    }
}
