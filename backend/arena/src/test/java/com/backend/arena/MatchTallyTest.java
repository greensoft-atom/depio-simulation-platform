package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchOutcome.PlayerOutcome;
import com.backend.sim.Entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchTallyTest {

    private static final long T0 = 1_700_000_000_000L;

    private static MatchTally started() {
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBTESTMATCH00000000000001", T0);
        return tally;
    }

    private static PlayerOutcome player(MatchOutcome outcome, long playerId) {
        return outcome.players().stream().filter(p -> p.playerId() == playerId).findFirst()
                .orElseThrow(() -> new AssertionError("player " + playerId + " missing"));
    }

    /** What a square is worth, from the shipped shape table. */
    private static final int SQUARE_XP = 10;

    /** A stand-in for a tank kill, which is really a share of what the victim had earned. */
    private static final int TANK_XP = 100;

    @Test
    @DisplayName("a player who leaves and comes back within a timed match is one entry, and the gap is not play")
    void rejoinContinuesTheEntry() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.playerJoined(2L, 200L, "Bob", 0, T0);
        tally.playerJoined(3L, 300L, "Cy", 0, T0);
        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);
        tally.playerLeft(1L, T0 + 10_000);
        // Back after 20 s, with the tag the room kept for them in this match.
        tally.playerJoined(1L, 100L, "Ada", 0, T0 + 30_000);
        tally.applyKill(1L, 3L, Entity.KIND_TANK, TANK_XP);

        MatchOutcome outcome = tally.finish(T0 + 50_000);
        assertThat(outcome.players()).extracting(PlayerOutcome::playerId)
                .as("one entry per player").containsExactlyInAnyOrder(100L, 200L, 300L);
        assertThat(player(outcome, 100L).kills()).as("both stays count").isEqualTo(2);
        assertThat(player(outcome, 100L).playtimeSeconds()).as("10 s, then 20 s").isEqualTo(30);
        assertThat(player(outcome, 100L).placement()).isEqualTo(1);
        // A second entry for Ada would have pushed these down a place each.
        assertThat(outcome.players()).extracting(PlayerOutcome::placement)
                .containsExactlyInAnyOrder(1, 2, 2);
    }

    @Test
    @DisplayName("a kill credits the killer and charges the victim")
    void killsAndDeaths() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.playerJoined(2L, 200L, "Bob", 0, T0);

        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(2L, 1L, Entity.KIND_TANK, TANK_XP);

        MatchOutcome outcome = tally.finish(T0 + 60_000);
        assertThat(player(outcome, 100L).kills()).isEqualTo(2);
        assertThat(player(outcome, 100L).deaths()).isEqualTo(1);
        assertThat(player(outcome, 200L).kills()).isEqualTo(1);
        assertThat(player(outcome, 200L).deaths()).isEqualTo(2);
    }

    @Test
    @DisplayName("an assist is counted, and what it paid is score, as a kill's is (01 §7, plan item 65)")
    void assistsAreCounted() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.playerJoined(2L, 200L, "Bob", 0, T0);
        tally.applyAssist(1L, 25);
        tally.applyAssist(1L, 0);                    // its tank gone by the death: counted, nothing paid
        tally.applyAssist(9L, 25);                   // a tag nobody here has
        MatchOutcome outcome = tally.finish(T0 + 60_000);
        assertThat(player(outcome, 100L).assists()).isEqualTo(2);
        assertThat(player(outcome, 100L).score()).isEqualTo(25);
        assertThat(player(outcome, 200L).assists()).isZero();
    }

    @Test
    @DisplayName("a shape is worth score but is not a kill")
    void shapesScoreButDoNotCount() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);

        tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);
        tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);
        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);

        PlayerOutcome ada = player(tally.finish(T0 + 60_000), 100L);
        assertThat(ada.kills()).as("shapes are not kills").isEqualTo(1);
        assertThat(ada.score()).as("score is the experience those kills were worth")
                .isEqualTo(SQUARE_XP + SQUARE_XP + TANK_XP);
    }

    @Test
    @DisplayName("the highest score places first")
    void placementIsBestFirst() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Low", 0, T0);
        tally.playerJoined(2L, 200L, "High", 0, T0);
        tally.playerJoined(3L, 300L, "Middle", 0, T0);

        tally.applyKill(2L, 0L, Entity.KIND_SHAPE, SQUARE_XP);      // High:   10
        tally.applyKill(2L, 1L, Entity.KIND_TANK, TANK_XP);       // High:  110
        tally.applyKill(3L, 0L, Entity.KIND_SHAPE, SQUARE_XP);      // Middle: 10

        MatchOutcome outcome = tally.finish(T0 + 60_000);

        assertThat(player(outcome, 200L).placement()).as("the best score places first").isEqualTo(1);
        assertThat(player(outcome, 300L).placement()).isEqualTo(2);
        assertThat(player(outcome, 100L).placement()).isEqualTo(3);
        assertThat(outcome.players().get(0).playerId())
                .as("the list is ordered, best first").isEqualTo(200L);
        assertThat(outcome.won(player(outcome, 200L))).isTrue();
        assertThat(outcome.won(player(outcome, 100L))).isFalse();
    }

    @Test
    @DisplayName("a duel is placed by kills, not score; equal kills are a draw; and the result says duel")
    void aDuelIsWonByKills() {
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBTESTMATCH00000000000002", T0, com.backend.handoff.MatchMode.DUEL);
        tally.playerJoined(1L, 100L, "Farmer", 0, T0);
        tally.playerJoined(2L, 200L, "Fighter", 1, T0);
        for (int i = 0; i < 30; i++) {
            tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);  // Farmer: 300 score, no kills
        }
        tally.applyKill(2L, 1L, Entity.KIND_TANK, TANK_XP);       // Fighter: one kill, 100 score
        assertThat(tally.mostKills()).isEqualTo(1);

        MatchOutcome won = tally.finish(T0 + 60_000);
        assertThat(won.mode()).isEqualTo(com.backend.handoff.MatchMode.DUEL.id);
        assertThat(won.kind()).isEqualTo(MatchOutcome.KIND_TIMED);
        assertThat(player(won, 200L).placement()).as("the kill wins it").isEqualTo(1);
        assertThat(player(won, 100L).placement()).isEqualTo(2);

        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);       // one each
        MatchOutcome drawn = tally.finish(T0 + 61_000);
        assertThat(player(drawn, 100L).placement()).isEqualTo(1);
        assertThat(player(drawn, 200L).placement()).as("a draw: both first").isEqualTo(1);
    }

    @Test
    @DisplayName("team-vs-team is placed by the teams' kills, every player of a team alike; the kills that end it are a team's")
    void aTeamIsPlacedTogether() {
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBTESTMATCH00000000000003", T0, com.backend.handoff.MatchMode.TVT);
        long[][] teams = {{1L, 101L}, {2L, 102L}, {3L, 103L}, {4L, 201L}, {5L, 202L}, {6L, 203L}};
        for (long[] p : teams) {
            tally.playerJoined(p[0], p[1], "p" + p[1], p[1] < 200 ? 1 : 2, T0);
        }
        // Team 2 has the best single killer, two; team 1 has three kills between two of them.
        tally.applyKill(4L, 1L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(4L, 2L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(1L, 4L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(1L, 5L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(2L, 6L, Entity.KIND_TANK, TANK_XP);
        assertThat(tally.mostKills()).as("a team's, not a player's").isEqualTo(3);

        MatchOutcome won = tally.finish(T0 + 60_000);
        assertThat(won.mode()).isEqualTo(com.backend.handoff.MatchMode.TVT.id);
        for (long id : new long[] {101L, 102L, 103L}) {
            assertThat(player(won, id).placement()).as("team 1, %d, the kill-less too", id).isEqualTo(1);
        }
        for (long id : new long[] {201L, 202L, 203L}) {
            assertThat(player(won, id).placement()).as("team 2, %d", id).isEqualTo(2);
        }
        tally.applyKill(5L, 3L, Entity.KIND_TANK, TANK_XP);          // three each
        MatchOutcome drawn = tally.finish(T0 + 61_000);
        assertThat(drawn.players()).as("a draw: everyone first").allSatisfy(p -> assertThat(p.placement()).isEqualTo(1));
    }

    @Test
    @DisplayName("domination is placed by the dominators each team holds at the end, not by kills (01 §8.7)")
    void dominationIsPlacedByWhatIsHeld() {
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBTESTMATCH00000000000009", T0, com.backend.handoff.MatchMode.DOMINATION);
        long[][] teams = {{1L, 101L}, {2L, 102L}, {3L, 103L}, {4L, 201L}, {5L, 202L}, {6L, 203L}};
        for (long[] p : teams) {
            tally.playerJoined(p[0], p[1], "p" + p[1], p[1] < 200 ? 1 : 2, T0);
        }
        tally.applyKill(4L, 1L, Entity.KIND_TANK, TANK_XP);             // team 2 kills more
        tally.applyKill(5L, 2L, Entity.KIND_TANK, TANK_XP);
        MatchOutcome won = tally.finish(T0 + 60_000, java.util.Map.of(1, 2, 2, 1));
        assertThat(won.mode()).isEqualTo(com.backend.handoff.MatchMode.DOMINATION.id);
        for (long id : new long[] {101L, 102L, 103L}) {
            assertThat(player(won, id).placement()).as("team 1 holds two, %d", id).isEqualTo(1);
        }
        for (long id : new long[] {201L, 202L, 203L}) {
            assertThat(player(won, id).placement()).as("team 2, %d", id).isEqualTo(2);
        }
        MatchOutcome drawn = tally.finish(T0 + 61_000, java.util.Map.of(1, 1, 2, 1));
        assertThat(drawn.players()).as("one each: a draw").allSatisfy(p -> assertThat(p.placement()).isEqualTo(1));
        MatchOutcome none = tally.finish(T0 + 62_000, java.util.Map.of(2, 1));
        assertThat(player(none, 101L).placement()).as("a team holding none has none").isEqualTo(2);
        assertThat(player(none, 201L).placement()).isEqualTo(1);
    }

    @Test
    @DisplayName("ranked free-for-all is placed by score, not kills, equal scores sharing; no kills end it (04 §4)")
    void aFreeForAllIsPlacedByScore() {
        MatchTally tally = new MatchTally("arena-1");
        tally.startMatch("01JBTESTMATCH00000000000004", T0, com.backend.handoff.MatchMode.RFFA);
        for (long p = 1; p <= 4; p++) {
            tally.playerJoined(p, 100 + p, "p" + p, 0, T0);
        }
        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);          // 1: a kill
        for (int i = 0; i < 11; i++) {
            tally.applyKill(3L, 0L, Entity.KIND_SHAPE, SQUARE_XP);    // 3: shapes, more score than a kill
            tally.applyKill(4L, 0L, Entity.KIND_SHAPE, SQUARE_XP);    // 4: the same
        }
        assertThat(com.backend.handoff.MatchMode.RFFA.winKills).as("the clock ends it").isZero();
        MatchOutcome outcome = tally.finish(T0 + 60_000);
        assertThat(outcome.mode()).isEqualTo(3);
        assertThat(player(outcome, 103L).placement()).isEqualTo(1);
        assertThat(player(outcome, 104L).placement()).isEqualTo(1);
        assertThat(player(outcome, 101L).placement()).as("a kill is worth less than eleven squares").isEqualTo(3);
        assertThat(player(outcome, 102L).placement()).isEqualTo(4);
    }

    @Test
    @DisplayName("players on the same score share a placement, and the next one skips")
    void tiesSharePlacement() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "A", 0, T0);
        tally.playerJoined(2L, 200L, "B", 0, T0);
        tally.playerJoined(3L, 300L, "C", 0, T0);

        tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);
        tally.applyKill(2L, 0L, Entity.KIND_SHAPE, SQUARE_XP);      // A and B both on 10, C on 0

        MatchOutcome outcome = tally.finish(T0 + 60_000);

        assertThat(player(outcome, 100L).placement()).isEqualTo(1);
        assertThat(player(outcome, 200L).placement()).isEqualTo(1);
        assertThat(player(outcome, 300L).placement())
                .as("two players tied for first means the next is third").isEqualTo(3);
    }

    @Test
    @DisplayName("a player who left mid-match is still in the result, with their playtime frozen")
    void departedPlayersStillCount() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Stayed", 0, T0);
        tally.playerJoined(2L, 200L, "Left", 0, T0);

        tally.applyKill(2L, 1L, Entity.KIND_TANK, TANK_XP);       // the leaver got a kill first
        tally.playerLeft(2L, T0 + 30_000);
        tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);

        MatchOutcome outcome = tally.finish(T0 + 120_000);

        // Leaving does not undo what they did, and the player they killed keeps the death.
        assertThat(outcome.players()).hasSize(2);
        assertThat(player(outcome, 200L).kills()).isEqualTo(1);
        assertThat(player(outcome, 200L).playtimeSeconds()).isEqualTo(30);
        assertThat(player(outcome, 100L).playtimeSeconds()).isEqualTo(120);
        assertThat(player(outcome, 100L).deaths()).isEqualTo(1);
    }

    @Test
    @DisplayName("a kill by someone who is not in this match changes nothing")
    void unknownTagsAreIgnored() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);

        tally.applyKill(999L, 1L, Entity.KIND_TANK, TANK_XP);     // killer unknown, victim is ours
        tally.applyKill(1L, 888L, Entity.KIND_TANK, TANK_XP);     // killer ours, victim unknown

        MatchOutcome outcome = tally.finish(T0 + 60_000);
        assertThat(outcome.players()).hasSize(1);
        assertThat(player(outcome, 100L).deaths()).isEqualTo(1);
        assertThat(player(outcome, 100L).kills()).isEqualTo(1);
    }

    @Test
    @DisplayName("a match nobody played produces no result at all")
    void emptyMatchProducesNothing() {
        assertThat(started().finish(T0 + 60_000))
                .as("an empty result would be a row in the database for nothing").isNull();
    }

    // ---- continuous rooms -------------------------------------------------------------

    @Test
    @DisplayName("a session records one player, with no placement and no win")
    void sessionHasNoPlacement() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.playerJoined(2L, 200L, "Bob", 0, T0);
        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);
        tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);

        MatchOutcome session = tally.finishOpenMatch(1L, T0 + 90_000);

        assertThat(session.players()).hasSize(1);
        PlayerOutcome ada = session.players().get(0);
        assertThat(ada.playerId()).isEqualTo(100L);
        assertThat(ada.kills()).isEqualTo(1);
        assertThat(ada.score()).isEqualTo(110);
        assertThat(ada.playtimeSeconds()).isEqualTo(90);
        // A continuous room has no field to be placed in; a number here would be invented.
        assertThat(ada.placement()).isZero();
        assertThat(session.won(ada)).isFalse();
        assertThat(session.startedAtMillis()).isEqualTo(T0);
    }

    @Test
    @DisplayName("each session gets its own id, and the player is then forgotten")
    void sessionsAreSeparateRecords() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.playerJoined(2L, 200L, "Bob", 0, T0);

        MatchOutcome one = tally.finishOpenMatch(1L, T0 + 1_000);
        MatchOutcome two = tally.finishOpenMatch(2L, T0 + 2_000);

        assertThat(one.matchUid()).isNotEqualTo(two.matchUid());
        assertThat(tally.has(1L)).as("a finished session is gone, not kept for ever").isFalse();
        assertThat(tally.finishOpenMatch(1L, T0 + 3_000))
                .as("and finishing it twice records nothing").isNull();
    }

    @Test
    @DisplayName("a checkpoint closes the session and keeps the player playing")
    void rollSessionKeepsTheTag() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.applyKill(1L, 2L, Entity.KIND_TANK, TANK_XP);

        MatchOutcome closed = tally.checkpointOpenMatch(1L, T0 + 600_000);

        assertThat(closed.players().get(0).kills()).isEqualTo(1);
        assertThat(tally.has(1L)).as("still here, still playing").isTrue();

        // The same tag on purpose: bullets already in flight carry it, and reissuing it
        // would send their kills to an entry that no longer exists.
        tally.applyKill(1L, 3L, Entity.KIND_TANK, TANK_XP);
        MatchOutcome next = tally.finishOpenMatch(1L, T0 + 700_000);

        assertThat(next.matchUid()).isNotEqualTo(closed.matchUid());
        assertThat(next.players().get(0).kills()).as("the new session starts at zero").isEqualTo(1);
        assertThat(next.players().get(0).playtimeSeconds())
                .as("and its clock starts at the checkpoint").isEqualTo(100);
    }

    @Test
    @DisplayName("checkpointing someone who is not here records nothing")
    void rollingAnUnknownTagIsHarmless() {
        MatchTally tally = started();
        assertThat(tally.checkpointOpenMatch(42L, T0)).isNull();
        assertThat(tally.finishOpenMatch(42L, T0)).isNull();
    }

    @Test
    @DisplayName("starting a new match forgets the previous one")
    void startMatchResets() {
        MatchTally tally = started();
        tally.playerJoined(1L, 100L, "Ada", 0, T0);
        tally.applyKill(1L, 0L, Entity.KIND_SHAPE, SQUARE_XP);

        tally.startMatch("01JBTESTMATCH00000000000002", T0 + 300_000);
        assertThat(tally.isEmpty()).isTrue();

        tally.playerJoined(2L, 100L, "Ada", 0, T0 + 300_000);
        MatchOutcome second = tally.finish(T0 + 360_000);

        assertThat(second.matchUid()).isEqualTo("01JBTESTMATCH00000000000002");
        assertThat(player(second, 100L).score()).as("last match's score does not carry over").isZero();
        List<PlayerOutcome> players = second.players();
        assertThat(players).hasSize(1);
    }
}
