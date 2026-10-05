package com.backend.arena;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.backend.sim.Entity;

/**
 * Tag's conversions (docs detailed-design/01-arena.md §8.8, Q-30): each player's team for the
 * match, starting from their ticket's; a player killed by a player of the other team goes over
 * to it. The tally keeps the team each started on, by which they are placed.
 *
 * Room thread only.
 */
final class Tag {

    /** Player tag to the team they are on now. */
    private final Map<Long, Integer> teams = new HashMap<>();

    /** A player in the match, on their ticket's team; one already here keeps where they went. */
    void join(long playerTag, int team) {
        teams.putIfAbsent(playerTag, team);
    }

    /** The team a player is on now; 0 for nobody in the match. */
    int teamOf(long playerTag) {
        return teams.getOrDefault(playerTag, 0);
    }

    /** A kill from the room's log: a player's tank killed by a player of the other team converts. */
    void onKill(long killerTag, long victimTag, byte victimKind) {
        if (victimKind != Entity.KIND_TANK) {
            return;
        }
        Integer killer = teams.get(killerTag);
        Integer victim = teams.get(victimTag);
        if (killer != null && victim != null && !killer.equals(victim)) {
            teams.put(victimTag, killer);
        }
    }

    /** Every player still playing is on one team: which ends it at once. */
    boolean oneTeam(Collection<Long> playing) {
        return heads(playing).size() == 1;
    }

    /** How many of those still playing each team has now: the teams' points at the end. */
    Map<Integer, Integer> heads(Collection<Long> playing) {
        Map<Integer, Integer> out = new HashMap<>();
        for (long p : playing) {
            Integer team = teams.get(p);
            if (team != null) {
                out.merge(team, 1, Integer::sum);
            }
        }
        return out;
    }
}
