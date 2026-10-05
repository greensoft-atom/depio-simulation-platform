package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assists (01 §7, plan item 65): a tank remembers the last players who hurt it, and those who
 * did within five seconds of its death, the killer apart, are paid a quarter of the kill.
 */
class AssistTest {

    private final World w = new World(2_000f, 2_000f, 32, 100f, 5L);
    private final Room r = new Room(w, Fixtures.UNPROTECTED);
    private final PhaseTimer timer = Room.newTimer();

    private void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(timer);
        }
    }

    /** A player's tank at (x, y), still, aimed right, its trigger up. */
    private Entity tank(long tag, float x, float y) {
        Entity e = r.spawnTank((byte) 0, tag);
        e.x = x;
        e.y = y;
        e.vx = 0f;
        e.vy = 0f;
        e.playerControlled = true;
        e.aimAngle = 0f;
        e.wantsFire = false;
        return e;
    }

    /** One bullet from {@code shooter} into {@code victim}, left with {@code hp}, put in its path. */
    private void hit(Entity shooter, Entity victim, float hp) {
        victim.x = 1_900f;                           // out of the way while the shot leaves the barrel
        victim.y = 1_900f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        r.step(timer);
        shooter.wantsFire = false;
        Entity bullet = newestBulletOf(shooter);
        victim.hp = hp;
        victim.x = bullet.x + 12f;
        victim.y = bullet.y;
        victim.vx = 0f;
        victim.vy = 0f;
        for (int i = 0; i < 5 && bullet.alive && victim.alive; i++) {
            r.step(timer);
        }
    }

    /** The shooter's bullet nearest its barrel: the one just fired. */
    private Entity newestBulletOf(Entity shooter) {
        Entity newest = null;
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < w.bullets.size; i++) {
            Entity b = w.entities[w.bullets.items[i]];
            if (b.ownerId != shooter.id || b.ownerGeneration != shooter.generation) {
                continue;
            }
            float d = (b.x - shooter.x) * (b.x - shooter.x) + (b.y - shooter.y) * (b.y - shooter.y);
            if (d < nearest) {
                nearest = d;
                newest = b;
            }
        }
        return newest;
    }

    private int xpOf(Entity tank) {
        return w.tankStats[tank.id].xp;
    }

    private int assistsIn(KillLog log, long tag) {
        int n = 0;
        for (int i = 0; i < log.assists(); i++) {
            if (log.assisterTag(i) == tag) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("one who hurt a tank shortly before another killed it is paid a quarter of the kill, and counted")
    void anAssistIsPaid() {
        Entity helper = tank(1L, 100f, 300f);
        Entity killer = tank(2L, 100f, 700f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        r.grantExperience(victim, 400);
        hit(helper, victim, 1_000f);
        assertThat(victim.alive).isTrue();
        int helperBefore = xpOf(helper);
        int killerBefore = xpOf(killer);
        r.kills().clear();
        hit(killer, victim, 1f);

        assertThat(victim.alive).isFalse();
        int kill = xpOf(killer) - killerBefore;
        assertThat(kill).as("a quarter of what the victim had").isEqualTo(100);
        assertThat(xpOf(helper) - helperBefore).as("a quarter of the kill").isEqualTo(25);
        assertThat(assistsIn(r.kills(), 1L)).isEqualTo(1);
        assertThat(assistsIn(r.kills(), 2L)).as("the killer is not their own assist").isZero();
    }

    @Test
    @DisplayName("a hit more than five seconds before the death is not an assist")
    void anOldHitIsNot() {
        Entity helper = tank(1L, 100f, 300f);
        Entity killer = tank(2L, 100f, 700f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        hit(helper, victim, 1_000f);
        run(Room.ASSIST_TICKS + 1);
        int helperBefore = xpOf(helper);
        r.kills().clear();
        hit(killer, victim, 1f);
        assertThat(victim.alive).isFalse();
        assertThat(xpOf(helper)).isEqualTo(helperBefore);
        assertThat(r.kills().assists()).isZero();
    }

    @Test
    @DisplayName("the killer who hurt it before is paid the kill alone, not a quarter more")
    void theKillerIsNotTheirOwnAssist() {
        Entity killer = tank(2L, 100f, 700f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        hit(killer, victim, 1_000f);
        int killerBefore = xpOf(killer);
        r.kills().clear();
        hit(killer, victim, 1f);
        assertThat(victim.alive).isFalse();
        assertThat(xpOf(killer) - killerBefore).as("the floor, a fresh tank's worth").isEqualTo(Room.TANK_KILL_XP_FLOOR);
        assertThat(r.kills().assists()).isZero();
    }

    @Test
    @DisplayName("a tank remembers the last three players who hurt it, the oldest forgotten")
    void theRingHoldsThree() {
        Entity[] helpers = {tank(11L, 100f, 200f), tank(12L, 100f, 400f), tank(13L, 100f, 600f), tank(14L, 100f, 800f)};
        Entity killer = tank(2L, 100f, 1_000f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        for (Entity h : helpers) {
            hit(h, victim, 1_000f);
        }
        r.kills().clear();
        hit(killer, victim, 1f);
        assertThat(victim.alive).isFalse();
        assertThat(assistsIn(r.kills(), 11L)).as("the oldest, forgotten").isZero();
        assertThat(assistsIn(r.kills(), 12L) + assistsIn(r.kills(), 13L) + assistsIn(r.kills(), 14L)).isEqualTo(3);
    }

    @Test
    @DisplayName("one player hurting a tank twice holds one place, and is one assist")
    void onePlaceAPlayer() {
        Entity ada = tank(11L, 100f, 200f);
        Entity bob = tank(12L, 100f, 400f);
        Entity killer = tank(2L, 100f, 1_000f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        hit(ada, victim, 1_000f);
        hit(ada, victim, 1_000f);
        hit(bob, victim, 1_000f);
        r.kills().clear();
        hit(killer, victim, 1f);
        assertThat(victim.alive).isFalse();
        assertThat(assistsIn(r.kills(), 11L)).as("twice, one assist").isEqualTo(1);
        assertThat(assistsIn(r.kills(), 12L)).isEqualTo(1);
    }

    @Test
    @DisplayName("a bot's hit takes no player's place in the ring")
    void aBotTakesNoPlace() {
        Entity ada = tank(11L, 100f, 200f);
        Entity bob = tank(12L, 100f, 400f);
        Entity cy = tank(13L, 100f, 600f);
        Entity bot = tank(0L, 100f, 800f);
        Entity killer = tank(2L, 100f, 1_000f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        for (Entity attacker : new Entity[] {ada, bob, cy, bot}) {
            hit(attacker, victim, 1_000f);
        }
        r.kills().clear();
        hit(killer, victim, 1f);
        assertThat(victim.alive).isFalse();
        assertThat(assistsIn(r.kills(), 11L)).as("the first, still remembered").isEqualTo(1);
        assertThat(r.kills().assists()).isEqualTo(3);
    }

    @Test
    @DisplayName("a bot's hit is no assist: nobody to credit")
    void aBotsHitIsNot() {
        Entity bot = tank(0L, 100f, 300f);
        Entity killer = tank(2L, 100f, 700f);
        Entity victim = tank(3L, 1_500f, 1_500f);
        hit(bot, victim, 1_000f);
        r.kills().clear();
        hit(killer, victim, 1f);
        assertThat(victim.alive).isFalse();
        assertThat(r.kills().assists()).isZero();
    }
}
