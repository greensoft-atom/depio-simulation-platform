package com.backend.arena;

import com.backend.handoff.MatchMode;

/**
 * How a room decides that something worth recording has finished
 * ([D-15](../../../../../../../../docs/architecture/03-decision-log.md)).
 *
 * <h2>Two lifecycles, because they are two products</h2>
 *
 * The public arena is {@link Lifecycle#OPEN}: it never stops and it never resets, and
 * what gets recorded is a player's <em>session</em> — from the moment they arrive to the
 * moment they leave. Resetting everyone every few minutes would throw away the one thing
 * that arena is for, which is uninterrupted play and a tank you grow.
 *
 * Ranked, duel, team and tournament modes are timed: a fixed roster, a defined end, and one
 * result for everybody. A rating cannot move on a contest players can leave and rejoin, so
 * this lifecycle is not optional for them. {@link Lifecycle#MADE} is that for a match the
 * matcher made (04 §4): the room exists for the one match, named by its tickets (D-20).
 * {@link Lifecycle#TIMED} is a room that runs timed matches back to back with whoever is
 * there, which is how the arena first ran and what an arena configured with a match length
 * still does.
 */
public record MatchRules(Lifecycle lifecycle, int matchDurationTicks, int checkpointTicks,
                         String arenaName, int resumeGraceTicks, int resumeKeepTicks,
                         MatchMode mode, String matchUid, int joinWindowTicks) {

    public enum Lifecycle {
        /** Never ends. A result covers one player's stay, written when they leave. */
        OPEN,
        /** Ends on the clock, and the next begins. One result covers everybody who was there. */
        TIMED,
        /**
         * One match the matcher made: waits for its roster, plays to a win or the clock, is
         * published, and the room closes (04 §4).
         */
        MADE
    }

    /**
     * Ten minutes at 25 Hz.
     *
     * An open room has to check in on long matches, or a player who stays for six
     * hours is paid nothing for six hours and loses the lot if the process dies. Ten minutes
     * also keeps the event rate inside the budget the design assumes: at 50 000 players it
     * is about 83 results a second, against the ~170/s
     * ([05 §1](../../../../../../../../docs/detailed-design/05-worker-and-events.md)) the stream
     * was sized for.
     */
    public static final int DEFAULT_CHECKPOINT_TICKS = 25 * 60 * 10;

    /**
     * After a lost connection (02 §10): the tank stays in the world, parked and killable, for
     * ten seconds, so that leaving is never a way out of a fight; then it is taken out, and
     * what it had grown into is kept for the player until a minute has passed - long enough
     * for a phone to walk out of wifi onto a cellular network, or ride a lift.
     */
    public static final int DEFAULT_RESUME_GRACE_TICKS = 25 * 10;
    public static final int DEFAULT_RESUME_KEEP_TICKS = 25 * 60;

    public MatchRules {
        if (resumeGraceTicks < 0 || resumeKeepTicks < resumeGraceTicks) {
            throw new IllegalArgumentException("a stay is kept at least as long as its tank");
        }
        if (lifecycle != Lifecycle.OPEN && matchDurationTicks <= 0) {
            throw new IllegalArgumentException("a timed match needs a duration");
        }
        if ((lifecycle == Lifecycle.MADE) != (matchUid != null)
                || (lifecycle == Lifecycle.MADE && (mode == null || !mode.made()))) {
            throw new IllegalArgumentException("a made match, and only one, has a match id and a made mode");
        }
        if (lifecycle == Lifecycle.OPEN && checkpointTicks <= 0) {
            throw new IllegalArgumentException("an open room needs a checkpoint interval");
        }
    }

    public static MatchRules open(String arenaName) {
        return open(arenaName, DEFAULT_CHECKPOINT_TICKS);
    }

    public static MatchRules open(String arenaName, int checkpointTicks) {
        return new MatchRules(Lifecycle.OPEN, 0, checkpointTicks, arenaName,
                DEFAULT_RESUME_GRACE_TICKS, DEFAULT_RESUME_KEEP_TICKS, MatchMode.FFA, null, 0);
    }

    public static MatchRules timed(String arenaName, int matchDurationTicks) {
        return new MatchRules(Lifecycle.TIMED, matchDurationTicks, 0, arenaName,
                DEFAULT_RESUME_GRACE_TICKS, DEFAULT_RESUME_KEEP_TICKS, MatchMode.FFA, null, 0);
    }

    /** The one match {@code matchUid}, of {@code mode}, with this arena's resume windows. */
    public MatchRules made(MatchMode mode, String matchUid) {
        return new MatchRules(Lifecycle.MADE, mode.durationSeconds * TICKS_PER_SECOND, 0, arenaName,
                resumeGraceTicks, resumeKeepTicks, mode, matchUid,
                mode.joinWindowSeconds * TICKS_PER_SECOND);
    }

    /** The same rules with other resume windows, for tests that cannot wait a minute. */
    public MatchRules withResume(int graceTicks, int keepTicks) {
        return new MatchRules(lifecycle, matchDurationTicks, checkpointTicks, arenaName, graceTicks,
                keepTicks, mode, matchUid, joinWindowTicks);
    }

    /** The same rules with another match length, for tests that cannot wait three minutes. */
    public MatchRules withDuration(int ticks) {
        return new MatchRules(lifecycle, ticks, checkpointTicks, arenaName, resumeGraceTicks,
                resumeKeepTicks, mode, matchUid, joinWindowTicks);
    }

    /** The same rules with another join window, for tests that cannot wait thirty seconds. */
    public MatchRules withJoinWindow(int ticks) {
        return new MatchRules(lifecycle, matchDurationTicks, checkpointTicks, arenaName,
                resumeGraceTicks, resumeKeepTicks, mode, matchUid, ticks);
    }

    public boolean isMade() {
        return lifecycle == Lifecycle.MADE;
    }

    private static final int TICKS_PER_SECOND = 25;

    public boolean isOpen() {
        return lifecycle == Lifecycle.OPEN;
    }
}
