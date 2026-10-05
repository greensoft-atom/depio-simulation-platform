package com.backend.arena;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.backend.handoff.MatchMode;
import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchOutcome.PlayerOutcome;
import com.backend.handoff.Ulid;
import com.backend.sim.Entity;
import com.backend.sim.KillLog;

/**
 * What each player did during the current match.
 *
 * <h2>Why it is not kept on the connection</h2>
 *
 * Players leave in the middle of matches, and a player who leaves has still played: their
 * kills are real, their deaths are real, and the people they killed deserve to keep the
 * credit. If the tally lived on {@link Connection} it would be collected along with the
 * socket, and the match result would quietly describe only the players who happened to
 * still be connected when the clock ran out.
 *
 * Keyed by the simulation's player tag, which is never given to two players inside a room,
 * so nobody inherits someone else's entry. A player keeps their tag, and so continues their
 * entry, across a resume and across a rejoin inside a timed match (D-11); a new stay in an
 * open room gets a new tag and a fresh entry.
 *
 * Room thread only.
 */
final class MatchTally {

    private static final class Entry {
        /** This player's own result id. Unused by a timed match, which has one for everybody. */
        final String matchUid = Ulid.generate();
        final long playerId;
        final String displayName;
        final int team;
        final long joinedAtMillis;      // first join: an open match's start

        int kills;
        int deaths;
        int assists;                    // 01 §7
        int score;
        long leftAtMillis;              // 0 while connected
        long stintStartMillis;          // when the current connection began
        long playedBeforeMillis;        // earlier connections: lost and resumed, or left and back

        Entry(long playerId, String displayName, int team, long joinedAtMillis) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.team = team;
            this.joinedAtMillis = joinedAtMillis;
            this.stintStartMillis = joinedAtMillis;
        }

        /** Time actually present: the gap while disconnected is not play. */
        long playedMillis(long matchEndedAtMillis) {
            long current = leftAtMillis > 0 ? 0 : Math.max(0, matchEndedAtMillis - stintStartMillis);
            return playedBeforeMillis + current;
        }
    }

    private final Map<Long, Entry> byTag = new HashMap<>();
    private final String arenaName;
    private long startedAtMillis;
    private String matchUid;
    /** The public arena's, until a match of another mode starts. */
    private MatchMode mode = MatchMode.FFA;

    MatchTally(String arenaName) {
        this.arenaName = arenaName;
    }

    void startMatch(String matchUid, long nowMillis) {
        startMatch(matchUid, nowMillis, MatchMode.FFA);
    }

    void startMatch(String matchUid, long nowMillis, MatchMode mode) {
        byTag.clear();
        this.matchUid = matchUid;
        this.startedAtMillis = nowMillis;
        this.mode = mode;
    }

    /**
     * The most kills anyone has in this match: what ends a mode won by kills. In a mode with
     * teams, the most any team has between its players (01 §8.4).
     */
    int mostKills() {
        Map<Integer, Integer> byTeam = new HashMap<>();
        int most = 0;
        for (Entry e : byTag.values()) {
            int kills = mode.teams() ? byTeam.merge(e.team, e.kills, Integer::sum) : e.kills;
            most = Math.max(most, kills);
        }
        return most;
    }

    String matchUid() {
        return matchUid;
    }

    /** A new entry; or, for a tag already here, the same player back again (D-11). */
    void playerJoined(long playerTag, long playerId, String displayName, int team, long nowMillis) {
        Entry e = byTag.get(playerTag);
        if (e == null) {
            byTag.put(playerTag, new Entry(playerId, displayName, team, nowMillis));
        } else if (e.leftAtMillis > 0) {
            e.leftAtMillis = 0;
            e.stintStartMillis = nowMillis;
        }
    }

    void playerLeft(long playerTag, long nowMillis) {
        Entry e = byTag.get(playerTag);
        if (e != null && e.leftAtMillis == 0) {
            e.leftAtMillis = nowMillis;
            e.playedBeforeMillis += Math.max(0, nowMillis - e.stintStartMillis);
        }
    }

    /** Applies one tick's kills. Tags that belong to nobody here are ignored, not created. */
    void apply(KillLog kills) {
        for (int i = 0; i < kills.size(); i++) {
            applyKill(kills.killerTag(i), kills.victimTag(i), kills.victimKind(i), kills.xp(i));
        }
        for (int i = 0; i < kills.assists(); i++) {
            applyAssist(kills.assisterTag(i), kills.assistXp(i));
        }
    }

    /**
     * Applies one kill.
     *
     * <h2>Kills and deaths do not have to balance</h2>
     *
     * Across a room the totals satisfy <em>kills ≤ deaths</em>, not equality. A player's
     * bullets outlive them: when someone leaves, their tally entry goes but their shots stay
     * in the air, and a shot that lands afterwards still kills its victim. The death is
     * counted because the victim is still here; the kill is not, because the shooter is not.
     *
     * Measured over 120 recorded matches: 281 kills against 282 deaths. That gap is the
     * feature, not a miscount — crediting a departed player would mean writing to a record
     * that has already been published.
     *
     * <h2>Score is experience</h2>
     *
     * {@code xp} is what the simulation says the kill was worth, which is the same number
     * that levelled the killer's tank. Scoring here with a table of its own would give the
     * player two scores — the one on their screen and the one on the leaderboard — and the
     * first time those disagreed it would be reported as the leaderboard being wrong.
     *
     * The two are still not the same *quantity*: a tank's experience resets when it dies,
     * while this keeps counting for as long as the player stays. Score is everything they
     * have earned; level is what they have kept.
     */
    void applyKill(long killerTag, long victimTag, byte victimKind, int xp) {
        Entry killer = killerTag == 0 ? null : byTag.get(killerTag);
        if (killer != null) {
            if (victimKind == Entity.KIND_TANK) {
                killer.kills++;
            }
            killer.score += xp;
        }
        if (victimKind == Entity.KIND_TANK && victimTag != 0) {
            Entry victim = byTag.get(victimTag);
            if (victim != null) {
                victim.deaths++;
            }
        }
    }

    /** An assist (01 §7): counted, and what it paid is score, as a kill's experience is. */
    void applyAssist(long assisterTag, int xp) {
        Entry assister = assisterTag == 0 ? null : byTag.get(assisterTag);
        if (assister != null) {
            assister.assists++;
            assister.score += xp;
        }
    }

    boolean isEmpty() {
        return byTag.isEmpty();
    }

    boolean has(long playerTag) {
        return byTag.containsKey(playerTag);
    }

    /** Score that comes of no kill: a wave cleared in co-op (01 §8.5, Q-37). A tag not here is ignored. */
    void addScore(long playerTag, int score) {
        Entry e = byTag.get(playerTag);
        if (e != null) {
            e.score += score;
        }
    }

    int scoreOf(long playerTag) {
        Entry e = byTag.get(playerTag);
        return e == null ? 0 : e.score;
    }

    /** @return the display name, or null for a tag that is not a player here. */
    String displayNameOf(long playerTag) {
        Entry e = playerTag == 0 ? null : byTag.get(playerTag);
        return e == null ? null : e.displayName;
    }

    // ---- open rooms -----------------------------------------------------------------------

    /**
     * Ends one player's open match and forgets them.
     *
     * An open room has no round and no field to be placed in, so placement is 0 and
     * nobody won. Inventing a placement out of whoever happened to be connected would be a
     * number that looks meaningful and is not.
     *
     * @return the finished match, or null if this tag has no entry: tag 0, from a connection
     *         the room never admitted, or a stay already finished.
     */
    MatchOutcome finishOpenMatch(long playerTag, long nowMillis) {
        Entry e = byTag.remove(playerTag);
        if (e == null) {
            return null;
        }
        PlayerOutcome player = new PlayerOutcome(e.playerId, e.displayName, e.team, 0,
                e.kills, e.deaths, e.score, e.playedMillis(nowMillis) / 1000, e.assists);
        return new MatchOutcome(e.matchUid, MatchOutcome.KIND_OPEN, MatchMode.FFA.id, arenaName,
                e.joinedAtMillis, nowMillis, List.of(player));
    }

    /**
     * Closes a long open match and starts a fresh one for the same player.
     *
     * The player tag is deliberately unchanged: bullets already in flight carry it, and
     * reissuing it here would send their kills to an entry that no longer exists.
     */
    MatchOutcome checkpointOpenMatch(long playerTag, long nowMillis) {
        Entry old = byTag.get(playerTag);
        if (old == null) {
            return null;
        }
        MatchOutcome closed = finishOpenMatch(playerTag, nowMillis);
        byTag.put(playerTag, new Entry(old.playerId, old.displayName, old.team, nowMillis));
        return closed;
    }

    /**
     * @return the finished match, or null if nobody played it.
     *
     * Placement is by score, highest first, and ties share the lower number — two players on
     * 300 are both second, and the next is fourth. Ranking ties arbitrarily would mean the
     * same match produced different results depending on map iteration order. A mode won by
     * kills is placed by kills instead: a duel's winner is who reached three, or had more at
     * the whistle, not who farmed more shapes, and equal kills are a draw (04 §4).
     */
    MatchOutcome finish(long nowMillis) {
        return finish(nowMillis, null);
    }

    /**
     * As {@link #finish(long)}, the teams placed by {@code teamPoints} instead of their kills when
     * given: domination's, the dominators each team holds (01 §8.7). A team not in it has none.
     */
    MatchOutcome finish(long nowMillis, Map<Integer, Integer> teamPoints) {
        if (byTag.isEmpty()) {
            return null;
        }
        // In a mode with teams, a player is placed as their team is, by its kills: every player
        // of the winning team is first, the kill-less too, and equal teams are a draw (01 §8.4).
        Map<Integer, Integer> teamKills = new HashMap<>();
        for (Entry e : byTag.values()) {
            teamKills.merge(e.team, e.kills, Integer::sum);
        }
        if (teamPoints != null) {
            teamKills.replaceAll((team, kills) -> teamPoints.getOrDefault(team, 0));
        }
        java.util.function.ToIntFunction<Entry> rank = mode.winKills > 0 ? e -> e.kills : e -> e.score;
        List<Entry> ordered = new ArrayList<>(byTag.values());
        // Each key is reversed on its own. Chaining .reversed() after thenComparing would
        // reverse everything built so far instead, which sorted the winner into last place
        // and was caught only because the test asserted an actual placement.
        ordered.sort(Comparator.comparingInt(rank).reversed()
                .thenComparing(Comparator.comparingInt((Entry e) -> e.kills).reversed())
                .thenComparingLong(e -> e.playerId));

        List<PlayerOutcome> players = new ArrayList<>(ordered.size());
        int placement = 0;
        int index = 0;
        Integer previous = null;
        for (Entry e : ordered) {
            index++;
            if (mode.teams()) {
                // A team's place, not the player's: one more than the teams ahead of it. Placed
                // as players are, a losing team of three came fourth.
                int ahead = 0;
                for (int kills : teamKills.values()) {
                    ahead += kills > teamKills.get(e.team) ? 1 : 0;
                }
                placement = 1 + ahead;
            } else if (previous == null || rank.applyAsInt(e) != previous) {
                placement = index;
                previous = rank.applyAsInt(e);
            }
            players.add(new PlayerOutcome(e.playerId, e.displayName, e.team, placement,
                    e.kills, e.deaths, e.score, e.playedMillis(nowMillis) / 1000, e.assists));
        }
        return new MatchOutcome(matchUid, MatchOutcome.KIND_TIMED, mode.id, arenaName,
                startedAtMillis, nowMillis, players);
    }
}
