package com.backend.handoff;

import java.util.List;

/**
 * What an arena reports when a match ends (docs detailed-design/05-worker-and-events.md §2).
 *
 * <h2>Facts, not rewards</h2>
 *
 * The arena reports what happened: who played, what they killed, how long they were there.
 * It does not say how much XP or currency that is worth. Those are product decisions that
 * change with balance patches and events, and an arena that computed them would have to be
 * redeployed — and would disagree with every other arena during the rollout. {@code worker}
 * turns these facts into rewards, in one place, against one version of the rules.
 *
 * This record is a wire contract between two processes that are deployed separately, so a
 * field may be added but not repurposed, and the envelope carries a version.
 *
 * {@code kind} says which lifecycle produced this
 * ([D-15](../../../../../../../../docs/architecture/03-decision-log.md)). Without it a reader
 * cannot tell a session from a match except by guessing from the placement, and a query
 * that means "my ranked matches" would be a heuristic over data that should state the fact.
 */
public record MatchOutcome(String matchUid, int kind, int mode, String arena,
                           long startedAtMillis, long endedAtMillis,
                           List<PlayerOutcome> players, boolean cutShort) {

    /**
     * One player's stay in an open arena: no roster, no placement, no winner.
     *
     * Not called a session: that word is taken by the authenticated lobby session, and one
     * word for two unrelated lifetimes invites the assumption that ending one ends the other.
     */
    public static final int KIND_OPEN = 0;

    /** A contest with a roster, a start and an end, and one result for everybody in it. */
    public static final int KIND_TIMED = 1;

    /** {@code assists}: hits shortly before another's kill (01 §7); none from an arena before them. */
    public record PlayerOutcome(long playerId, String displayName, int team, int placement,
                                int kills, int deaths, int score, long playtimeSeconds, int assists) {

        public PlayerOutcome(long playerId, String displayName, int team, int placement,
                             int kills, int deaths, int score, long playtimeSeconds) {
            this(playerId, displayName, team, placement, kills, deaths, score, playtimeSeconds, 0);
        }
    }

    public MatchOutcome {
        players = List.copyOf(players);
    }

    /**
     * A match that ended as matches do. {@code cutShort} is one a stop ended first, which the
     * drain could not wait out (D-29): recorded and paid, not rated. Absent from a result an
     * older arena wrote, it reads false, which is what that arena meant.
     */
    public MatchOutcome(String matchUid, int kind, int mode, String arena, long startedAtMillis,
                        long endedAtMillis, List<PlayerOutcome> players) {
        this(matchUid, kind, mode, arena, startedAtMillis, endedAtMillis, players, false);
    }

    /** The same match, cut short by a stop (D-29). */
    public MatchOutcome cut() {
        return new MatchOutcome(matchUid, kind, mode, arena, startedAtMillis, endedAtMillis, players, true);
    }

    /** The winner is the best placement, which is 1; several players can share it. */
    public boolean won(PlayerOutcome player) {
        return player.placement() == 1;
    }
}
