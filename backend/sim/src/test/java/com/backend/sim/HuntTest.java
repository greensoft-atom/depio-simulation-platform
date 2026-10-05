package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The hunting tank (01 §8.5): co-op's waves go for the nearest player of another team. */
class HuntTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static Room room() {
        return new Room(new World(3_000f, 3_000f, 1_024, 100f, 5L), Fixtures.UNPROTECTED);
    }

    /** A still player's tank of {@code team}. */
    private static Entity player(Room r, int team, float x, float y) {
        Entity t = r.spawnTank((byte) team, 100L + team * 10 + (long) x + (long) y);
        t.x = x;
        t.y = y;
        t.vx = t.vy = 0f;
        t.playerControlled = true;
        return t;
    }

    /** A hunting tank of team 2, still, where it is put. */
    private static Entity hunter(Room r, float x, float y) {
        Entity h = r.spawnTank((byte) 2);
        h.x = x;
        h.y = y;
        h.vx = h.vy = 0f;
        h.hunts = true;
        return h;
    }

    private static int shotsBy(Room r, Entity shooter) {
        int shots = 0;
        World w = r.world();
        for (int i = 0; i < w.bullets.size; i++) {
            shots += w.entities[w.bullets.items[i]].ownerId == shooter.id ? 1 : 0;
        }
        return shots;
    }

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    @Test
    @DisplayName("far off, it closes on the player's tank and holds its fire")
    void farOffItCloses() {
        Room r = room();
        player(r, 1, 500f, 1_500f);
        Entity h = hunter(r, 1_500f, 1_500f);
        run(r, 20);
        assertThat(h.x).as("toward the player").isLessThan(1_490f);
        assertThat(h.angle).isCloseTo((float) Math.PI, within(0.01f));
        assertThat(shotsBy(r, h)).as("1 000 units off: not yet").isZero();
    }

    @Test
    @DisplayName("within 600 it fires, and within 400 it closes no further")
    void nearItFires() {
        Room r = room();
        Entity p = player(r, 1, 500f, 1_500f);
        p.hp = p.maxHp = 1e9f;                         // lives through it
        Entity h = hunter(r, 1_050f, 1_500f);
        run(r, 60);
        assertThat(shotsBy(r, h)).as("550 units off").isPositive();
        run(r, 100);
        assertThat(h.x - p.x).as("it stops closing at 400, give or take its coasting").isBetween(360f, 420f);
    }

    @Test
    @DisplayName("inside 400 it does not close: it turns to its quarry and stands")
    void insideItStands() {
        Room r = room();
        Entity p = player(r, 1, 500f, 1_500f);
        p.hp = p.maxHp = 1e9f;
        Entity h = hunter(r, 800f, 1_500f);
        h.reloadTicks = 1_000;                         // no shot, so no recoil, in this test
        run(r, 5);
        assertThat(h.angle).isCloseTo((float) Math.PI, within(0.01f));
        assertThat(h.vx).as("no push toward it").isGreaterThanOrEqualTo(0f);
        assertThat(h.x).isCloseTo(800f, within(0.5f));
    }

    @Test
    @DisplayName("it goes for the nearest player of another team, never its own nor a tank nobody drives")
    void theNearestOfAnother() {
        Room r = room();
        Entity h = hunter(r, 1_500f, 1_500f);
        player(r, 2, 1_400f, 1_500f);                  // its own team: not its quarry
        Entity nobody = r.spawnTank((byte) 1);         // a wanderer nobody drives
        nobody.x = 1_600f;
        nobody.y = 1_500f;
        player(r, 1, 500f, 1_500f);                    // 1 000 off
        player(r, 1, 1_500f, 2_300f);                  // 800 off: the nearest
        player(r, 1, 1_500f, 1_400f).alive = false;    // dead this tick: no quarry
        r.step(TIMER);
        assertThat(h.angle).isCloseTo((float) (Math.PI / 2), within(0.01f));
    }

    @Test
    @DisplayName("a tank put where a hunter was does not hunt: the pool's reset forgets it")
    void thePoolForgets() {
        Entity h = hunter(room(), 1_500f, 1_500f);
        h.reset();                                     // what a slot goes through before its next use
        assertThat(h.hunts).isFalse();
    }

    @Test
    @DisplayName("with nobody to hunt it stands, and holds its fire")
    void aloneItWaits() {
        Room r = room();
        Entity h = hunter(r, 1_500f, 1_500f);
        run(r, 60);
        assertThat(h.x).isCloseTo(1_500f, within(1f));
        assertThat(h.y).isCloseTo(1_500f, within(1f));
        assertThat(shotsBy(r, h)).isZero();
    }
}
