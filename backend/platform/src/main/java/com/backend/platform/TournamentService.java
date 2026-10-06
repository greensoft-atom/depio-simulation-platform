package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import com.backend.handoff.MatchMode;
import com.backend.handoff.StoreUnavailableException;
import com.backend.handoff.TournamentGrants;
import com.backend.persistence.MatchResultRepository;
import com.backend.persistence.TeamRepository;
import com.backend.persistence.TournamentRepository;

/**
 * Tournaments, as players see them (docs detailed-design/04-platform-services.md §6, Q-15): listed,
 * followed, registered for and withdrawn from. Operators create them (the admin API); worker's
 * scheduler runs them.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class TournamentService {

    public enum Result {
        OK, NO_SESSION, NO_SUCH_TOURNAMENT, CLOSED, TOO_FEW_RATED, FULL, ALREADY, NOT_REGISTERED, NO_MATCH,
        IN_PARTY, PARTY_TOO_SMALL, NOT_ONE_TEAM, NOT_ALLOWED
    }

    /**
     * A tournament with its entries and, once seeded, its bracket: a duels' entries are players', a
     * teams' are in {@code teams} (Q-19); {@code standings}: a round robin's, by place, none for an elimination (04 §6).
     */
    public record View(TournamentRepository.Tournament tournament, List<TournamentRepository.Entry> entries,
                       List<TournamentRepository.Match> matches, List<TournamentRepository.TeamEntry> teams,
                       List<TournamentRepository.Standing> standings) {

        public boolean ofTeams() {
            return tournament.mode() == MatchResultRepository.MODE_TEAMS;
        }
    }

    public record Answer(Result result, View view) {
        static Answer of(Result result) {
            return new Answer(result, null);
        }
    }

    /** A match's grant, the JSON worker's scheduler kept, when the result is OK. */
    public record MatchGrant(Result result, String grant) { }

    private final AuthService auth;
    private final TournamentRepository tournaments;
    private final TournamentGrants grants;
    private final Parties parties;
    private final TeamRepository teams;
    private final Clock clock;

    public TournamentService(AuthService auth, TournamentRepository tournaments, TournamentGrants grants,
                             Parties parties, TeamRepository teams, Clock clock) {
        this.auth = auth;
        this.tournaments = tournaments;
        this.grants = grants;
        this.parties = parties;
        this.teams = teams;
        this.clock = clock;
    }

    /** Those registering, seeded or running. */
    public List<View> open() throws SQLException {
        List<View> out = new java.util.ArrayList<>();
        for (TournamentRepository.Tournament t : tournaments.inStates(TournamentRepository.REGISTRATION,
                TournamentRepository.SEEDED, TournamentRepository.RUNNING)) {
            out.add(view(t, List.of()));
        }
        return out;
    }

    public Answer get(long id) throws SQLException {
        TournamentRepository.Tournament t = tournaments.get(id);
        return t == null ? Answer.of(Result.NO_SUCH_TOURNAMENT) : new Answer(Result.OK, view(t, tournaments.matches(id)));
    }

    private View view(TournamentRepository.Tournament t, List<TournamentRepository.Match> matches) throws SQLException {
        List<TournamentRepository.Standing> standings = t.format() == TournamentRepository.ROUND_ROBIN
                ? tournaments.standings(t.id()) : List.of();
        return t.mode() == MatchResultRepository.MODE_TEAMS
                ? new View(t, List.of(), matches, tournaments.teamEntries(t.id()), standings)
                : new View(t, tournaments.entries(t.id()), matches, List.of(), standings);
    }

    public Answer register(String token, long id) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        TournamentRepository.Tournament t = tournaments.get(id);
        if (t != null && t.mode() == MatchResultRepository.MODE_TEAMS) {
            return registerTeam(p, id);
        }
        TournamentRepository.Registration r = tournaments.register(id, p, clock.instant());
        return r == TournamentRepository.Registration.OK ? get(id) : Answer.of(Result.valueOf(r.name()));
    }

    /**
     * A team's entry (Q-19): the caller leads a party of three of one team, and is its leader or a
     * vice leader, as a team queues; the three are its roster.
     */
    private Answer registerTeam(long p, long id) throws SQLException {
        Parties.Party party = parties.of(p);
        if (party != null && party.leader() != p) {
            return Answer.of(Result.IN_PARTY);
        }
        if (party == null || party.members().size() < MatchMode.TEAMS.teamSize) {
            return Answer.of(Result.PARTY_TOO_SMALL);
        }
        TeamRepository.Side side = teams.sideOf(party.members());
        if (side == null) {
            return Answer.of(Result.NOT_ONE_TEAM);
        }
        if (side.role() == TeamRepository.MEMBER) {
            return Answer.of(Result.NOT_ALLOWED);
        }
        TournamentRepository.Registration r = tournaments.registerTeam(id, side.teamId(), party.members(), clock.instant());
        return r == TournamentRepository.Registration.OK ? get(id) : Answer.of(Result.valueOf(r.name()));
    }

    public Answer withdraw(String token, long id) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        TournamentRepository.Tournament t = tournaments.get(id);
        if (t == null) {
            return Answer.of(Result.NO_SUCH_TOURNAMENT);             // as registering is (P-57)
        }
        if (t.mode() == MatchResultRepository.MODE_TEAMS) {
            TeamRepository.Team team = teams.teamOf(p);
            if (team == null) {
                return Answer.of(Result.NOT_REGISTERED);
            }
            boolean mayAct = team.members().stream().anyMatch(m -> m.playerId() == p && m.role() != TeamRepository.MEMBER);
            if (!mayAct) {
                return Answer.of(Result.NOT_ALLOWED);
            }
            return tournaments.withdrawTeam(id, team.id(), clock.instant()) ? get(id) : Answer.of(Result.NOT_REGISTERED);
        }
        return tournaments.withdraw(id, p, clock.instant()) ? get(id) : Answer.of(Result.NOT_REGISTERED);
    }

    /** The player's grant for their match in it, while its ticket lasts. */
    public MatchGrant match(String token, long id) {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return new MatchGrant(Result.NO_SESSION, null);
        }
        String grant = await(grants.get(id, p));
        return grant == null ? new MatchGrant(Result.NO_MATCH, null) : new MatchGrant(Result.OK, grant);
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
