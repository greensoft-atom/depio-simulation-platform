package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Equipment's bonus on a tank's stats: better by a whole percent a stat (docs 01 §3, D-37). */
class EquipmentBonusTest {

    private static final StatTable STATS = StatTable.defaults();

    private static byte[] bonus(int stat, int percent) {
        byte[] b = new byte[Stat.COUNT];
        b[stat] = (byte) percent;
        return b;
    }

    @Test
    @DisplayName("a bonus raises its stat by the percent, and makes reload that much quicker")
    void betterByThePercent() {
        TankStats s = new TankStats();
        s.refresh(STATS);
        float damage = s.value(Stat.BULLET_DAMAGE);
        float reload = s.value(Stat.RELOAD);
        float speed = s.value(Stat.MOVEMENT_SPEED);

        byte[] worn = bonus(Stat.BULLET_DAMAGE, 10);
        worn[Stat.RELOAD] = 25;
        s.setBonus(worn);
        s.refresh(STATS);

        assertThat(s.value(Stat.BULLET_DAMAGE)).isCloseTo(damage * 1.10f, within(1e-4f));
        assertThat(s.value(Stat.RELOAD)).as("ticks between shots: fewer, a quarter more shots")
                .isCloseTo(reload / 1.25f, within(1e-4f));
        assertThat(s.value(Stat.MOVEMENT_SPEED)).as("a stat without one").isEqualTo(speed);
    }

    @Test
    @DisplayName("kept through a level and a point spent, carried by a resume; a slot handed out anew has none")
    void keptWithThePlayer() {
        TankStats s = new TankStats();
        s.setBonus(bonus(Stat.MAX_HEALTH, 20));
        s.addXp(4, LevelTable.defaults());                  // level 2, a point
        s.spendPoint(Stat.MAX_HEALTH, Stat.MAX_POINTS_PER_STAT);
        s.refresh(STATS);
        assertThat(s.value(Stat.MAX_HEALTH)).isCloseTo(STATS.valueOf(Stat.MAX_HEALTH, 2, 1) * 1.2f, within(1e-3f));

        TankStats resumed = new TankStats();
        resumed.copyFrom(s);
        resumed.refresh(STATS);
        assertThat(resumed.value(Stat.MAX_HEALTH)).as("a resume").isEqualTo(s.value(Stat.MAX_HEALTH));

        s.reset();
        s.refresh(STATS);
        assertThat(s.value(Stat.MAX_HEALTH)).as("the slot handed to someone else")
                .isEqualTo(STATS.valueOf(Stat.MAX_HEALTH, 1, 0));
    }

    @Test
    @DisplayName("the skin, as the bonus: carried by a resume, kept through a sandbox's restart; a slot handed out anew has none (D-70)")
    void theSkinIsKeptWithThePlayer() {
        TankStats s = new TankStats();
        s.skin = 4;
        TankStats resumed = new TankStats();
        resumed.copyFrom(s);
        assertThat(resumed.skin).as("a resume").isEqualTo(4);
        s.restart();
        assertThat(s.skin).as("still wearing what it wore").isEqualTo(4);
        s.reset();
        assertThat(s.skin).as("the slot handed to someone else").isZero();
    }
}
