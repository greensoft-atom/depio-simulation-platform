package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Team rules (01 §8.3): a team's own do it no harm, and each team arrives on its own side. */
class TeamTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static Room room() {
        return new Room(new World(3_000f, 3_000f, 1_024, 100f, 5L), Fixtures.UNPROTECTED);
    }

    /** A still tank of {@code team} at x, y, aiming along +x. */
    private static Entity tank(Room r, int team, float x, float y) {
        Entity t = r.spawnTank((byte) team, 100L + team * 10 + (long) x);
        t.x = x;
        t.y = y;
        t.playerControlled = true;
        t.aimAngle = 0f;
        return t;
    }

    private static Entity fire(Room r, Entity shooter) {
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        r.step(TIMER);
        shooter.wantsFire = false;
        World w = r.world();
        for (int i = 0; i < w.bullets.size; i++) {
            Entity b = w.entities[w.bullets.items[i]];
            if (b.ownerId == shooter.id) {
                return b;
            }
        }
        throw new AssertionError("no shot");
    }

    @Test
    @DisplayName("a bullet passes through a tank of its own team, untouched and unspent, and hits the enemy behind")
    void teammatesAreNotShot() {
        Room r = room();
        Entity a = tank(r, 1, 500f, 1_500f);
        Entity mate = tank(r, 1, 700f, 1_500f);
        Entity enemy = tank(r, 2, 900f, 1_500f);
        Entity bullet = fire(r, a);
        bullet.hp = bullet.maxHp = 1_000f;
        float mateHp = mate.hp;
        float enemyHp = enemy.hp;
        float vx = bullet.vx;
        for (int i = 0; i < 40; i++) {                  // 400 units at 10 a tick
            r.step(TIMER);
        }
        assertThat(mate.hp).as("its teammate, untouched").isEqualTo(mateHp);
        assertThat(enemy.hp).as("the enemy behind, hit").isLessThan(enemyHp);
        assertThat(bullet.vx).as("not knocked (D-9)").isEqualTo(vx);
    }

    @Test
    @DisplayName("team 0 is no team: two tanks of it hurt each other, as the public arena's do")
    void teamZeroIsNoTeam() {
        Room r = room();
        Entity a = tank(r, 0, 500f, 1_500f);
        Entity b = tank(r, 0, 700f, 1_500f);
        float hp = b.hp;
        fire(r, a);
        for (int i = 0; i < 25; i++) {
            r.step(TIMER);
        }
        assertThat(b.hp).isLessThan(hp);
    }

    @Test
    @DisplayName("teammates touching are pushed apart and neither is hurt; enemies touching are both hurt")
    void teammatesBump() {
        Room r = room();
        Entity a = tank(r, 1, 1_000f, 1_000f);
        Entity mate = tank(r, 1, 1_040f, 1_000f);
        Entity x = tank(r, 1, 1_000f, 2_000f);
        Entity enemy = tank(r, 2, 1_040f, 2_000f);
        float full = a.hp;
        r.step(TIMER);
        assertThat(List.of(a.hp, mate.hp)).as("teammates, unhurt").containsOnly(full);
        assertThat(mate.vx - a.vx).as("but pushed apart").isPositive();
        assertThat(x.hp).as("enemies, hurt").isLessThan(full);
        assertThat(enemy.hp).isLessThan(full);
    }

    @Test
    @DisplayName("a team's projectiles pass through each other, and a teammate's trap does no harm and is not spent")
    void aTeamsProjectilesAreItsOwn() {
        Room r = room();
        Entity a = tank(r, 1, 500f, 1_000f);
        Entity mate = tank(r, 1, 500f, 2_000f);
        Entity mine = fire(r, a);
        Entity theirs = fire(r, mate);
        theirs.x = mine.x;                              // on top of each other
        theirs.y = mine.y;
        theirs.vx = mine.vx = 0f;
        float mineHp = mine.hp;
        float theirsHp = theirs.hp;
        r.step(TIMER);
        assertThat(List.of(mine.hp, theirs.hp)).containsExactly(mineHp, theirsHp);

        // A teammate's trap, laid where the tank stands.
        Entity trap = fire(r, a);
        trap.subtype = (byte) ClassTable.Barrel.TRAP;
        trap.wireClass = Entity.WIRE_UNIT;
        trap.vx = trap.vy = 0f;
        trap.x = mate.x + 30f;
        trap.y = mate.y;
        float mateHp = mate.hp;
        float trapHp = trap.hp;
        r.step(TIMER);
        assertThat(mate.hp).isEqualTo(mateHp);
        assertThat(trap.hp).as("not spent").isEqualTo(trapHp);
    }

    @Test
    @DisplayName("each team arrives on its own side, team 1 the left third and team 2 the right; team 0 anywhere")
    void teamsArriveOnTheirSides() {
        Room r = room();
        float third = r.world().width / 3f;
        boolean middle = false;
        for (int i = 0; i < 40; i++) {
            assertThat(r.spawnTank((byte) 1).x).as("team 1").isLessThanOrEqualTo(third);
            assertThat(r.spawnTank((byte) 2).x).as("team 2").isGreaterThanOrEqualTo(2 * third);
            float x = r.spawnTank((byte) 0).x;
            middle |= x > third && x < 2 * third;
        }
        assertThat(middle).as("team 0 anywhere, the middle included").isTrue();
    }
}
