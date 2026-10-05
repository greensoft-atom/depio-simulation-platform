package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Health comes back slowly while a tank is in the fight, and fast once it has been left alone
 * for half a minute (01 §3). The slow part was all there was: a tank that escaped a fight
 * needed over a minute to recover, whatever it did.
 */
class RecoveryTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static Room emptyRoom(long seed) {
        return new Room(new World(4_000f, 4_000f, 256, 200f, seed));
    }

    /** Past its spawn protection, which is {@link SpawnTest}'s business. */
    private static Entity stillTank(Room r, long tag, float x, float y) {
        Entity t = r.spawnTank((byte) 0, tag);
        t.protectedUntilTick = 0;
        t.playerControlled = true;
        t.x = x;
        t.y = y;
        return t;
    }

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    @Test
    @DisplayName("half a minute without damage turns slow recovery into fast")
    void quietTanksRecoverFast() {
        Room r = emptyRoom(1L);
        Entity t = stillTank(r, 7L, 1_000f, 1_000f);
        Recovery rules = r.content().recovery();
        t.hp = t.maxHp / 2f;
        t.lastDamagedTick = r.tick();                 // as if just hit

        run(r, rules.quietTicks() - 10);
        float slow = t.hp - t.maxHp / 2f;
        assertThat(t.hp).as("still recovering slowly").isLessThan(t.maxHp);
        // Only the stat's rate so far: 0.03 % of maximum health a tick.
        assertThat(slow).isCloseTo(t.maxHp * r.world().tankStats[t.id].value(Stat.HEALTH_REGEN)
                * (rules.quietTicks() - 10), org.assertj.core.data.Offset.offset(0.5f));

        run(r, 10 + (int) Math.ceil(1f / rules.burstPerTick()));
        assertThat(t.hp).as("then full, within the burst's few seconds").isEqualTo(t.maxHp);
    }

    @Test
    @DisplayName("a bullet hit starts the quiet half minute again")
    void aHitRestartsTheClock() {
        Room r = emptyRoom(2L);
        Entity shooter = stillTank(r, 1L, 500f, 500f);
        Entity victim = stillTank(r, 2L, 620f, 500f);
        shooter.aimAngle = 0f;
        shooter.angle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        victim.lastDamagedTick = -1_000_000;          // long quiet

        float before = victim.hp;
        int ticks = 0;
        while (victim.hp >= before && ticks++ < 50) {
            r.step(TIMER);
        }
        assertThat(victim.hp).as("hit").isLessThan(before);
        assertThat(victim.lastDamagedTick).isEqualTo(r.tick());
    }

    @Test
    @DisplayName("contact damage starts the quiet half minute again too")
    void contactRestartsTheClock() {
        Room r = emptyRoom(3L);
        Entity a = stillTank(r, 1L, 1_000f, 1_000f);
        Entity b = stillTank(r, 2L, 1_050f, 1_000f);
        a.lastDamagedTick = -1_000_000;
        b.lastDamagedTick = -1_000_000;

        r.step(TIMER);

        assertThat(a.lastDamagedTick).isEqualTo(r.tick());
        assertThat(b.lastDamagedTick).isEqualTo(r.tick());
    }
}
