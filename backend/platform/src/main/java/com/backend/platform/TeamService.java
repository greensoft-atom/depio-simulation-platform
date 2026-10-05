package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import com.backend.persistence.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Teams' first slice (docs detailed-design/04-platform-services.md §2, Q-14): the session and a
 * name's rules checked here, everything else in one transaction each in {@link TeamRepository}.
 * Each change of who is in a team, or in which role, is pushed to its members as
 * {@code evt.team.update}, and to one no longer in it with no team (04 §2, Q-35).
 *
 * Blocking, and meant for a virtual thread.
 */
public final class TeamService {

    /** The repository's outcomes, and what this layer adds. */
    public enum Result {
        OK, NO_SESSION, INVALID_NAME, INVALID_ROLE, NAME_TAKEN, IN_TEAM, COOLING_DOWN, NO_TEAM, NOT_ALLOWED,
        NO_SUCH_PLAYER, NO_INVITE, TEAM_FULL, LEADER_WITH_MEMBERS, NOT_A_MEMBER, TOO_MANY_VICES, TOO_MANY_INVITED,
        TOO_SOON, RENAMED_RECENTLY, NO_SUCH_TEAM, ALREADY, TOO_MANY_APPLIED, NO_APPLICATION
    }

    /** Teams found, or why none were looked for. */
    public record Search(Result result, List<TeamRepository.Found> teams) { }

    /** A team's applications, as its leader or a vice leader sees them, or why not. */
    public record Applications(Result result, List<TeamRepository.Application> applications) { }

    /** {@code team}: the player's team as it stands after the action, when it has one. */
    /** {@code team} for an answer that is a team, {@code invites} for one that is a list of them; neither: {@code {}}. */
    public record Answer(Result result, TeamRepository.Team team, List<TeamRepository.Invite> invites) {
        static Answer of(Result result) {
            return new Answer(result, null, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuthService auth;
    private final TeamRepository teams;
    private final com.backend.handoff.LobbyPush push;
    private final Clock clock;

    private final AskThrottle throttle;

    public TeamService(AuthService auth, TeamRepository teams, com.backend.handoff.LobbyPush push, Clock clock,
                       AskThrottle throttle) {
        this.auth = auth;
        this.teams = teams;
        this.push = push;
        this.clock = clock;
        this.throttle = throttle;
    }

    public Answer create(String token, String name) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        DisplayName.Result checked = DisplayName.check(name == null ? "" : name);
        if (!checked.ok()) {
            return Answer.of(Result.INVALID_NAME);
        }
        return after(p, teams.create(p, checked.name(), clock.instant()).outcome());
    }

    public Answer mine(String token) throws SQLException {
        long p = auth.playerIdOf(token);
        return p < 0 ? Answer.of(Result.NO_SESSION) : after(p, TeamRepository.Outcome.OK);
    }

    /**
     * An invitation, in the invitee's inbox and pushed as {@code evt.inbox}; one they have blocked,
     * answered alike. {@value AskThrottle#PER_HOUR} an hour a player at most (Q-46).
     */
    public Answer invite(String token, long playerId) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        if (!throttle.allow(AskThrottle.Kind.TEAM_INVITE, p)) {
            return Answer.of(Result.TOO_SOON);
        }
        TeamRepository.Outcome outcome = teams.invite(p, playerId, clock.instant());
        if (outcome == TeamRepository.Outcome.OK) {
            push.send(playerId, "evt.inbox", null);
        }
        return after(p, outcome == TeamRepository.Outcome.IGNORED ? TeamRepository.Outcome.OK : outcome);
    }

    public Answer invites(String token) throws SQLException {
        long p = auth.playerIdOf(token);
        return p < 0 ? Answer.of(Result.NO_SESSION) : new Answer(Result.OK, null, teams.invitesOf(p, clock.instant()));
    }

    public Answer answer(String token, long teamId, boolean accept) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        TeamRepository.Outcome outcome = teams.answer(p, teamId, accept, clock.instant());
        if (accept) {
            return told(after(p, outcome));
        }
        // Declined: the invitations that remain, as the list asked for says (the platform review, 2026-10-04).
        return outcome == TeamRepository.Outcome.OK
                ? new Answer(Result.OK, null, teams.invitesOf(p, clock.instant())) : Answer.of(Result.valueOf(outcome.name()));
    }

    public Answer leave(String token) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        TeamRepository.Team before = teams.teamOf(p);
        TeamRepository.Outcome outcome = teams.leave(p, clock.instant());
        if (outcome == TeamRepository.Outcome.OK) {
            tell(p, null);
            for (TeamRepository.Member m : before.members()) {
                if (m.playerId() != p) {
                    tellAll(teams.teamOf(m.playerId()));     // the team as it is now, through one still in it
                    break;
                }
            }
        }
        return Answer.of(Result.valueOf(outcome.name()));
    }

    public Answer kick(String token, long playerId) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        Answer answer = after(p, teams.kick(p, playerId, clock.instant()));
        if (answer.result() == Result.OK) {
            tell(playerId, null);
        }
        return told(answer);
    }

    /** {@code role}: {@code vice_leader} or {@code member}. */
    public Answer setRole(String token, long playerId, String role) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        int to = "vice_leader".equals(role) ? TeamRepository.VICE : "member".equals(role) ? TeamRepository.MEMBER : -1;
        if (to < 0) {
            return Answer.of(Result.INVALID_ROLE);
        }
        return told(after(p, teams.setRole(p, playerId, to)));
    }

    /** The leader renames the team, by a display name's rules, once in 30 days; every member told (04 §1). */
    public Answer rename(String token, String name) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        DisplayName.Result checked = DisplayName.check(name == null ? "" : name);
        if (!checked.ok()) {
            return Answer.of(Result.INVALID_NAME);
        }
        return told(after(p, teams.rename(p, checked.name(), clock.instant())));
    }

    /** Teams whose name starts so, 1 to 16 characters, the 20 first by name (Q-49). */
    public Search search(String token, String start) throws SQLException {
        if (auth.playerIdOf(token) < 0) {
            return new Search(Result.NO_SESSION, List.of());
        }
        if (start == null || start.isBlank() || start.codePointCount(0, start.length()) > DisplayName.MAX_CODE_POINTS) {
            return new Search(Result.INVALID_NAME, List.of());
        }
        return new Search(Result.OK, teams.find(start.strip()));
    }

    /** One team as anyone may see it. */
    public Search one(String token, long teamId) throws SQLException {
        if (auth.playerIdOf(token) < 0) {
            return new Search(Result.NO_SESSION, List.of());
        }
        TeamRepository.Found found = teams.find(teamId);
        return found == null ? new Search(Result.NO_SUCH_TEAM, List.of()) : new Search(Result.OK, List.of(found));
    }

    /**
     * Asks a team to take the player (Q-49), its leader and vice leaders told by {@code evt.inbox};
     * {@value AskThrottle#PER_HOUR} an hour a player. To a leader who blocked them, answered alike.
     */
    public Answer apply(String token, long teamId) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        if (!throttle.allow(AskThrottle.Kind.TEAM_APPLICATION, p)) {
            return Answer.of(Result.TOO_SOON);
        }
        TeamRepository.Applied applied = teams.apply(p, teamId, clock.instant());
        for (long answerer : applied.told()) {
            push.send(answerer, "evt.inbox", null);
        }
        return Answer.of(applied.outcome() == TeamRepository.Outcome.IGNORED ? Result.OK : Result.valueOf(applied.outcome().name()));
    }

    public Answer withdrawApplication(String token, long teamId) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        return Answer.of(teams.withdrawApplication(p, teamId) ? Result.OK : Result.NO_APPLICATION);
    }

    /** The player's own applications. */
    public List<TeamRepository.Applying> applying(String token) throws SQLException {
        long p = auth.playerIdOf(token);
        return p < 0 ? null : teams.applicationsOf(p, clock.instant());
    }

    /** The applications to the caller's team, for its leader or a vice leader. */
    public Applications applications(String token) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return new Applications(Result.NO_SESSION, List.of());
        }
        List<TeamRepository.Application> listed = teams.applicationsTo(p, clock.instant());
        return listed == null ? new Applications(Result.NOT_ALLOWED, List.of()) : new Applications(Result.OK, listed);
    }

    /** The leader or a vice leader answers an application; accepted, every member is told. */
    public Answer answerApplication(String token, long playerId, boolean accept) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        TeamRepository.Outcome outcome = teams.answerApplication(p, playerId, accept, clock.instant());
        return accept ? told(after(p, outcome)) : after(p, outcome);
    }

    public Answer transfer(String token, long playerId) throws SQLException {
        long p = auth.playerIdOf(token);
        return p < 0 ? Answer.of(Result.NO_SESSION) : told(after(p, teams.transfer(p, playerId)));
    }

    public Answer disband(String token) throws SQLException {
        long p = auth.playerIdOf(token);
        if (p < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        TeamRepository.Team before = teams.teamOf(p);
        TeamRepository.Outcome outcome = teams.disband(p, clock.instant());
        if (outcome == TeamRepository.Outcome.OK) {
            for (TeamRepository.Member m : before.members()) {
                tell(m.playerId(), null);
            }
        }
        return Answer.of(Result.valueOf(outcome.name()));
    }

    /** An answer with a team: every member is told it (04 §2). */
    private Answer told(Answer answer) {
        if (answer.result() == Result.OK) {
            tellAll(answer.team());
        }
        return answer;
    }

    private void tellAll(TeamRepository.Team team) {
        for (TeamRepository.Member m : team.members()) {
            tell(m.playerId(), team);
        }
    }

    /** {@code evt.team.update}: the team as {@code GET /v1/teams/mine} gives it, or none. */
    private void tell(long playerId, TeamRepository.Team team) {
        ObjectNode data = JSON.createObjectNode();
        data.set("team", team == null ? data.nullNode() : json(team));
        push.send(playerId, "evt.team.update", data);
    }

    /** The team as {@code GET /v1/teams/mine} answers it (04 §2). */
    static ObjectNode json(TeamRepository.Team team) {
        ObjectNode out = JSON.createObjectNode().put("id", team.id()).put("name", team.name());
        ArrayNode members = out.putArray("members");
        for (TeamRepository.Member m : team.members()) {
            members.addObject().put("playerId", m.playerId()).put("name", m.name()).put("role", switch (m.role()) {
                case TeamRepository.LEADER -> "leader";
                case TeamRepository.VICE -> "vice_leader";
                default -> "member";
            });
        }
        return out.put("rating", team.rating()).put("wins", team.wins()).put("losses", team.losses())
                .put("draws", team.draws());
    }

    /** The outcome, and on OK the player's team as it now stands; none is NO_TEAM. */
    private Answer after(long playerId, TeamRepository.Outcome outcome) throws SQLException {
        if (outcome != TeamRepository.Outcome.OK) {
            return Answer.of(Result.valueOf(outcome.name()));
        }
        TeamRepository.Team team = teams.teamOf(playerId);
        return team == null ? Answer.of(Result.NO_TEAM) : new Answer(Result.OK, team, List.of());
    }
}
