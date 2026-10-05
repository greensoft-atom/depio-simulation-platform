package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A tank rebuilt at a level where it stands: the sandbox's first power (01 §8.10, Q-32). */
class SetLevelTest {

    private static final StatTable STATS = StatTable.defaults();
    private static final LevelTable LEVELS = LevelTable.defaults();

    private static Room room() {
        return new Room(new World(2_000f, 2_000f, 512, 100f, 9L), Fixtures.UNPROTECTED);
    }

    /** A player's tank at level 30, a Twin, points spent, and hurt. */
    private static Entity grown(Room r) {
        Entity t = r.spawnTank((byte) 0, 7L);
        t.playerControlled = true;
        r.grantExperience(t, LEVELS.xpRequired(30));
        r.spendPoint(t, Stat.MAX_HEALTH);
        r.spendPoint(t, Stat.RELOAD);
        r.chooseClass(t, ClassTable.TWIN);
        t.hp = 1f;
        return t;
    }

    private static int pointsUpTo(int level) {
        int sum = 0;
        for (int l = 2; l <= level; l++) {
            sum += LEVELS.pointsAt(l);
        }
        return sum;
    }

    @Test
    @DisplayName("up to a level: Basic, every point that level gives unspent, full health, where it stood")
    void up() {
        Room r = room();
        Entity t = grown(r);
        float x = t.x;
        float y = t.y;
        assertThat(r.setLevel(t, 45)).isTrue();
        TankStats s = r.world().tankStats[t.id];
        assertThat(s.level).isEqualTo(45);
        assertThat(s.xp).isEqualTo(LEVELS.xpRequired(45));
        assertThat(s.unspentPoints).isEqualTo(pointsUpTo(45));
        assertThat(s.points).containsOnly(0);
        assertThat(s.classId).isEqualTo(ClassTable.BASIC);
        assertThat(t.maxHp).isCloseTo(STATS.valueOf(Stat.MAX_HEALTH, 45, 0), within(1e-3f));
        assertThat(t.hp).as("full health").isEqualTo(t.maxHp);
        assertThat(t.x).isEqualTo(x);
        assertThat(t.y).isEqualTo(y);
    }

    @Test
    @DisplayName("down to a level: the health it had above is gone, and it is whole at the lower one")
    void down() {
        Room r = room();
        Entity t = grown(r);
        r.setLevel(t, 45);
        assertThat(r.setLevel(t, 1)).isTrue();
        TankStats s = r.world().tankStats[t.id];
        assertThat(s.level).isEqualTo(1);
        assertThat(s.xp).isZero();
        assertThat(s.unspentPoints).isZero();
        assertThat(t.maxHp).isCloseTo(STATS.valueOf(Stat.MAX_HEALTH, 1, 0), within(1e-3f));
        assertThat(t.hp).isEqualTo(t.maxHp);
    }

    @Test
    @DisplayName("what the player wears stays on")
    void keepsWhatItWears() {
        Room r = room();
        Entity t = grown(r);
        byte[] worn = new byte[Stat.COUNT];
        worn[Stat.MAX_HEALTH] = 20;
        r.world().tankStats[t.id].setBonus(worn);
        r.setLevel(t, 10);
        assertThat(t.maxHp).isCloseTo(STATS.valueOf(Stat.MAX_HEALTH, 10, 0) * 1.2f, within(1e-3f));
    }

    @Test
    @DisplayName("a volley under way is dropped: its barrels were the old class's")
    void dropsAVolley() {
        Room r = room();
        Entity t = grown(r);
        t.volleyPending = 3;
        t.selfVolley = true;
        r.setLevel(t, 20);
        assertThat(t.volleyPending).isZero();
        assertThat(t.selfVolley).isFalse();
    }

    @Test
    @DisplayName("refused, and nothing changed: a level outside the table, a dead tank, a shape")
    void refused() {
        Room r = room();
        Entity t = grown(r);
        TankStats s = r.world().tankStats[t.id];
        assertThat(r.setLevel(t, 0)).isFalse();
        assertThat(r.setLevel(t, LEVELS.maxLevel() + 1)).isFalse();
        assertThat(s.level).isEqualTo(30);
        assertThat(s.classId).isEqualTo(ClassTable.TWIN);
        assertThat(t.hp).isEqualTo(1f);

        Entity shape = r.spawnShape();
        assertThat(r.setLevel(shape, 10)).isFalse();

        r.world().kill(t);
        assertThat(r.setLevel(t, 10)).isFalse();
    }
}
