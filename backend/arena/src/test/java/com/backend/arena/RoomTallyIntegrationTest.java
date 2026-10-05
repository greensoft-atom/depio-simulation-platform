package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;
import com.backend.handoff.MatchOutcome;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The join between the simulation and the tally: kills the simulation recorded turn into
 * score the result carries.
 *
 * Driven by a real {@link Room} rather than a hand-built {@link com.backend.sim.KillLog}, so
 * the tags, the kinds and the ordering are the ones the simulation actually produces — and
 * deterministically, by putting a victim at point blank range with one hit point, rather
 * than by running a match and hoping something dies.
 */
class RoomTallyIntegrationTest {

    private static final PhaseTimer TIMER = Room.newTimer();
    private static final long T0 = 1_700_000_000_000L;

    /** The wire id of a square, the cheapest thing in the room. */
    private static final byte SQUARE = 0;

    /** A shooter aimed along +x at a victim placed just in front of it. */
    private static Entity shooterAt(Room r, long tag, float x, float y) {
        Entity e = r.spawnTank((byte) 0, tag);
        e.protectedUntilTick = 0;       // past spawn protection: this test is about the tally
        e.x = x;
        e.y = y;
        e.playerControlled = true;
        e.aimAngle = 0f;
        e.angle = 0f;
        e.reloadTicks = 0;
        return e;
    }

    @Test
    @DisplayName("a shape the simulation killed becomes score in the result")
    void shapeKillBecomesScore() {
        World w = new World(2_000f, 2_000f, 256, 100f, 3L);
        Room r = new Room(w);
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBROOMTALLY0000000000001", T0);

        Entity shooter = shooterAt(r, 77L, 500f, 500f);
        tally.playerJoined(77L, 9001L, "Ada", 0, T0);

        Entity shape = r.spawnShape();
        shape.x = 600f;
        shape.y = 500f;
        shape.vx = 0f;
        shape.vy = 0f;
        shape.hp = 1f;
        // Pinned to a square. Which kind spawns is a weighted draw, so leaving it to the
        // seed would make the score this asserts a property of the seed.
        shape.subtype = SQUARE;

        for (int i = 0; i < 40; i++) {
            shooter.wantsFire = true;
            r.step(TIMER);
            tally.apply(r.kills());              // exactly what the room thread does each tick
            r.kills().clear();
        }

        MatchOutcome outcome = tally.finish(T0 + 60_000);
        assertThat(outcome.players()).hasSize(1);
        assertThat(outcome.players().get(0).score())
                .as("the shape kill reached the tally, worth what a square is worth")
                .isEqualTo(r.content().shapes().xpOf(SQUARE))
                .isEqualTo(10);
        assertThat(outcome.players().get(0).kills()).as("a shape is not a kill").isZero();
    }

    @Test
    @DisplayName("an assist the simulation logged reaches the result, its share of the kill in the score (01 §7)")
    void anAssistReachesTheResult() {
        World w = new World(2_000f, 2_000f, 256, 100f, 5L);
        Room r = new Room(w);
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBROOMTALLY0000000000009", T0);

        Entity helper = shooterAt(r, 99L, 500f, 300f);
        Entity killer = shooterAt(r, 77L, 500f, 500f);
        Entity victim = r.spawnTank((byte) 1, 88L);
        victim.protectedUntilTick = 0;
        victim.playerControlled = true;
        victim.x = 620f;
        victim.y = 300f;
        victim.hp = 1_000f;
        w.tankStats[victim.id].addXp(400, r.content().levels());     // a kill worth a quarter of it: 100
        tally.playerJoined(77L, 9001L, "Ada", 0, T0);
        tally.playerJoined(88L, 9002L, "Bob", 1, T0);
        tally.playerJoined(99L, 9003L, "Cy", 0, T0);

        for (int i = 0; i < 15; i++) {                               // Cy hurts Bob, who lives
            helper.wantsFire = i == 0;
            r.step(TIMER);
            tally.apply(r.kills());
            r.kills().clear();
        }
        assertThat(victim.alive).as("hurt, not killed").isTrue();
        assertThat(victim.hp).isLessThan(victim.maxHp);
        victim.x = 620f;                                             // then Ada finishes him
        victim.y = 500f;
        victim.hp = 1f;
        for (int i = 0; i < 40 && victim.alive; i++) {
            killer.wantsFire = true;
            r.step(TIMER);
            tally.apply(r.kills());
            r.kills().clear();
        }

        MatchOutcome outcome = tally.finish(T0 + 60_000);
        MatchOutcome.PlayerOutcome cy = outcome.players().stream().filter(p -> p.playerId() == 9003L).findFirst().orElseThrow();
        MatchOutcome.PlayerOutcome ada = outcome.players().stream().filter(p -> p.playerId() == 9001L).findFirst().orElseThrow();
        assertThat(ada.kills()).isEqualTo(1);
        assertThat(ada.assists()).isZero();
        assertThat(cy.assists()).isEqualTo(1);
        assertThat(cy.score()).as("a quarter of the kill").isEqualTo(25);
    }

    @Test
    @DisplayName("a tank kill credits the shooter and charges the victim, in the result")
    void tankKillBecomesKillAndDeath() {
        World w = new World(2_000f, 2_000f, 256, 100f, 5L);
        Room r = new Room(w);
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBROOMTALLY0000000000002", T0);

        Entity shooter = shooterAt(r, 77L, 500f, 500f);
        Entity victim = r.spawnTank((byte) 1, 88L);
        victim.protectedUntilTick = 0;
        victim.x = 620f;
        victim.y = 500f;
        victim.hp = 1f;
        victim.playerControlled = true;          // so it stays put and does not shoot back
        // A kill is worth a share of what the victim had earned, so a victim who had earned
        // nothing is worth only the floor. Give Bob a life worth taking.
        w.tankStats[victim.id].addXp(400, r.content().levels());
        tally.playerJoined(77L, 9001L, "Ada", 0, T0);
        tally.playerJoined(88L, 9002L, "Bob", 1, T0);

        for (int i = 0; i < 40; i++) {
            shooter.wantsFire = true;
            r.step(TIMER);
            tally.apply(r.kills());
            r.kills().clear();
        }

        MatchOutcome outcome = tally.finish(T0 + 60_000);
        MatchOutcome.PlayerOutcome ada = outcome.players().stream()
                .filter(p -> p.playerId() == 9001L).findFirst().orElseThrow();
        MatchOutcome.PlayerOutcome bob = outcome.players().stream()
                .filter(p -> p.playerId() == 9002L).findFirst().orElseThrow();

        assertThat(ada.kills()).isEqualTo(1);
        // A quarter of the 400 Bob had earned — the reason hunting beats farming when there
        // is something worth hunting.
        assertThat(ada.score()).isEqualTo(100);
        assertThat(ada.placement()).isEqualTo(1);
        assertThat(bob.deaths()).isEqualTo(1);
        assertThat(bob.kills()).isZero();
        assertThat(bob.placement()).isEqualTo(2);
    }
}
