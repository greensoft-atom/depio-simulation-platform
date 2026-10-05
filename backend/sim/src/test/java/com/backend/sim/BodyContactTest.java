package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bodies meeting (01 §3, M-8): body damage is dealt per second of contact, both ways, and a
 * tank cannot drive through what it hits.
 *
 * Before this, body damage only wore down bullets that hit the tank. Tanks pushed each other
 * apart and took nothing from it, and were never tested against shapes at all, so they drove
 * straight through them.
 */
class BodyContactTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    /**
     * A still, player-controlled tank that does not shoot: only its body is in play. Past its
     * spawn protection, which is {@link SpawnTest}'s business.
     */
    private static Entity tank(Room r, long tag, float x, float y) {
        Entity t = r.spawnTank((byte) 0, tag);
        t.protectedUntilTick = 0;
        t.playerControlled = true;
        t.x = x;
        t.y = y;
        t.vx = 0f;
        t.vy = 0f;
        return t;
    }

    /** A shape of the given kind, placed and still. */
    private static Entity shape(Room r, int kind, float x, float y) {
        Entity s = r.spawnShape();
        ShapeTable.Type type = r.content().shapes().get(kind);
        s.subtype = type.id();
        s.radius = type.radius();
        s.mass = type.mass();
        s.hp = type.hp();
        s.maxHp = type.hp();
        s.damage = type.bodyDamage();
        s.x = x;
        s.y = y;
        s.vx = 0f;
        s.vy = 0f;
        return s;
    }

    /** A room with nothing in it but what a test puts there. */
    private static Room emptyRoom(long seed) {
        return new Room(new World(4_000f, 4_000f, 256, 200f, seed));
    }

    @Test
    @DisplayName("two tanks that finish each other off both die: the first found is not revived by its pay")
    void aMutualKillLeavesNobodyStanding() {
        Room r = emptyRoom(9L);
        Entity a = tank(r, 1L, 1_000f, 1_000f);
        Entity b = tank(r, 2L, 1_050f, 1_000f);         // touching
        a.hp = 0.5f;
        b.hp = 0.5f;                                   // each dies of one tick of the other

        r.step(TIMER);

        // The lower slot was paid for its kill before its own death was looked at, and the
        // levels that bought refilled its health: slot order decided who lived.
        assertThat(a.alive).as("a").isFalse();
        assertThat(b.alive).as("b").isFalse();
    }

    @Test
    @DisplayName("a tank that dies breaking a pentagon dies: a pentagon's worth of levels does not save it")
    void dyingOnAShapeIsNotUndoneByItsXp() {
        Room r = emptyRoom(10L);
        Entity t = tank(r, 1L, 1_000f, 1_000f);
        Entity pentagon = shape(r, 2, 1_055f, 1_000f);
        t.hp = 0.3f;
        pentagon.hp = 0.1f;

        r.step(TIMER);

        assertThat(t.alive).isFalse();
        assertThat(pentagon.alive).isFalse();
    }

    @Test
    @DisplayName("a tank that drives into a square breaks it, pays for it, and is paid for it")
    void rammingBreaksAShape() {
        Room r = emptyRoom(1L);
        World w = r.world();
        Entity t = tank(r, 7L, 1_000f, 1_000f);
        Entity square = shape(r, 0, 1_060f, 1_000f);
        int shapes = w.shapes.size;
        float before = t.hp;

        t.moveX = 1f;
        int ticks = 0;
        float lowest = t.hp;
        while (square.alive && ticks++ < 200) {
            lowest = Math.min(lowest, t.hp);
            r.step(TIMER);
        }

        assertThat(square.alive).as("broken by the tank's body alone").isFalse();
        assertThat(w.tankStats[t.id].xp).as("worth what a bullet kill is").isEqualTo(10);
        // 8 a second of contact from a square, and a base tank needs half a second to break
        // one: a few points, not none and not most of its health. Measured before the kill,
        // because the experience levels the tank up, and levelling adds health.
        assertThat(before - lowest).isBetween(1f, 8f);
        assertThat(w.shapes.size).as("replaced, like any broken shape").isEqualTo(shapes);
        KillLog log = r.kills();
        assertThat(log.size()).isEqualTo(1);
        assertThat(log.killerTag(0)).isEqualTo(7L);
        assertThat(log.victimKind(0)).isEqualTo(Entity.KIND_SHAPE);
        assertThat(log.xp(0)).isEqualTo(10);
    }

    @Test
    @DisplayName("a tank cannot drive through a shape too big to break")
    void shapesAreSolid() {
        Room r = emptyRoom(2L);
        Entity t = tank(r, 7L, 1_000f, 1_000f);
        Entity alpha = shape(r, 3, 1_150f, 1_000f);
        // Enough health that contact cannot kill it within the test (192, against 0.8 a
        // tick), so that only the push, not the tank dying first, keeps it from driving
        // through. Through the stat: the room re-derives maximum health every tick, so a
        // number written straight into the tank does not last one.
        TankStats stats = r.world().tankStats[t.id];
        stats.unspentPoints = Stat.MAX_POINTS_PER_STAT;
        for (int i = 0; i < Stat.MAX_POINTS_PER_STAT; i++) {
            stats.spendPoint(Stat.MAX_HEALTH, Stat.MAX_POINTS_PER_STAT);
        }
        stats.refresh(r.content().stats());

        t.moveX = 1f;
        for (int i = 0; i < 150; i++) {
            r.step(TIMER);
            assertThat(t.x).as("tick %d: the tank went through the shape", i).isLessThan(alpha.x);
        }
        assertThat(t.alive).as("alive throughout, so the push is what held it").isTrue();
        assertThat(t.hp).as("and it hurt").isLessThan(t.maxHp);
        assertThat(alpha.alive).isTrue();
    }

    @Test
    @DisplayName("two tanks pushing wear each other down, and the stronger body wins the kill")
    void tanksWearEachOtherDown() {
        Room r = emptyRoom(3L);
        World w = r.world();
        Entity strong = tank(r, 1L, 1_000f, 1_000f);
        Entity weak = tank(r, 2L, 1_070f, 1_000f);
        TankStats s = w.tankStats[strong.id];
        s.unspentPoints = Stat.MAX_POINTS_PER_STAT;
        for (int i = 0; i < Stat.MAX_POINTS_PER_STAT; i++) {
            s.spendPoint(Stat.BODY_DAMAGE, Stat.MAX_POINTS_PER_STAT);
        }
        s.refresh(r.content().stats());
        float strongBefore = strong.hp;

        strong.moveX = 1f;
        weak.moveX = -1f;
        int ticks = 0;
        while (weak.alive && strong.alive && ticks++ < 1_000) {
            r.step(TIMER);
        }

        assertThat(weak.alive).isFalse();
        assertThat(strong.alive).isTrue();
        float taken = strongBefore - strong.hp;
        assertThat(taken).as("the winner was hurt too, by the loser's body").isPositive()
                .isLessThan(weak.maxHp);
        KillLog log = r.kills();
        assertThat(log.size()).isEqualTo(1);
        assertThat(log.killerTag(0)).isEqualTo(1L);
        assertThat(log.victimTag(0)).isEqualTo(2L);
        assertThat(log.victimKind(0)).isEqualTo(Entity.KIND_TANK);
        assertThat(w.tankStats[strong.id].xp).as("paid as for a bullet kill").isEqualTo(log.xp(0))
                .isPositive();
    }

    @Test
    @DisplayName("one tick of contact costs each tank the other's body damage over 25, once")
    void oneTickOfContactIsExact() {
        Room r = emptyRoom(6L);
        World w = r.world();
        Entity a = tank(r, 1L, 1_000f, 1_000f);
        Entity b = tank(r, 2L, 1_050f, 1_000f);   // 50 apart, radii 30: touching
        float regen = a.maxHp * w.tankStats[a.id].value(Stat.HEALTH_REGEN);
        a.hp = b.hp = 40f;                         // below full, so regen applies to both alike

        r.step(TIMER);

        float perTick = w.tankStats[b.id].value(Stat.BODY_DAMAGE) / 25f;
        assertThat(40f + regen - a.hp).as("per second of contact, not per tick; and once per pair")
                .isCloseTo(perTick, org.assertj.core.data.Offset.offset(1e-4f));
        assertThat(40f + regen - b.hp).isCloseTo(perTick, org.assertj.core.data.Offset.offset(1e-4f));
    }

    @Test
    @DisplayName("a tank killed by a shape is logged as a death with nobody to credit")
    void deathByShapeIsCounted() {
        Room r = emptyRoom(4L);
        Entity t = tank(r, 5L, 1_000f, 1_000f);
        shape(r, 2, 1_055f, 1_000f);            // a pentagon, already touching
        t.hp = 0.1f;

        r.step(TIMER);

        assertThat(t.alive).isFalse();
        KillLog log = r.kills();
        assertThat(log.size()).as("the tally counts deaths from this log").isEqualTo(1);
        assertThat(log.killerTag(0)).isZero();
        assertThat(log.victimTag(0)).isEqualTo(5L);
        assertThat(log.victimKind(0)).isEqualTo(Entity.KIND_TANK);
    }

    @Test
    @DisplayName("a shape knocked away slows back to a drift rather than flying on for ever")
    void knockedShapesSlowDown() {
        Room r = emptyRoom(5L);
        Entity pentagon = shape(r, 2, 2_000f, 2_000f);
        pentagon.vx = 5f;                         // as hard as any ram leaves it

        for (int i = 0; i < 100; i++) {
            r.step(TIMER);
        }

        float speed = (float) Math.hypot(pentagon.vx, pentagon.vy);
        assertThat(speed).isLessThanOrEqualTo(Room.SHAPE_DRIFT_MAX + 1e-4f);
    }
}
