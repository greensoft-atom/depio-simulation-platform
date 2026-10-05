package com.backend.platform;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.MatchMode;
import com.backend.handoff.SandboxHolds;
import com.backend.handoff.StoreUnavailableException;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.handoff.TournamentGrants;
import com.backend.handoff.Ulid;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AccountRepository.Profile;
import com.backend.persistence.TeamRepository;

/**
 * Joining, leaving and asking about a queue, for a session
 * (docs detailed-design/04-platform-services.md §4, "The queue").
 *
 * The player's name and the mode's rating are read from MySQL here, once, when they queue, so the
 * matcher matches and writes tickets from the store alone. The rating a result moves is not this
 * one: {@code worker} reads it again, under lock, when the result arrives.
 *
 * A party is queued by its leader, whole, as one entry, and taken out whole by any member's
 * leaving (04 §4, "Parties"). Only the leader asked, so the others are told:
 * {@code evt.queue.update}.
 *
 * A sandbox is opened here too, matched at once without a queue (04 §4, the seventh slice; D-49):
 * a player is in a queue or a match, not both.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class QueueService {

    public enum Outcome {
        QUEUED, ALREADY_QUEUED, IN_MATCH, UNKNOWN_MODE, NO_SESSION, IN_PARTY, PARTY_TOO_BIG, PARTY_CHANGED,
        QUEUE_LOCKED, PARTY_TOO_SMALL, NOT_ONE_TEAM, NOT_ALLOWED
    }

    public enum Answered { RECORDED, NOT_CONFIRMING, NO_SESSION }

    public enum Opened { OPENED, NO_SESSION, IN_PARTY, ALREADY_QUEUED, IN_MATCH, IN_SANDBOX, NO_ROOM }

    /** What opening a sandbox came to, and the caller's grant when it was opened. */
    public record Sandbox(Opened outcome, MatchQueue.Grant grant) { }

    private final AuthService auth;
    private final AccountRepository accounts;
    private final MatchQueue queue;
    private final Parties parties;
    private final LobbyPush push;
    private final LongSupplier clock;
    private final Loadouts loadouts;
    private final TeamRepository teams;
    private final ArenaDirectory arenas;
    private final TicketStore tickets;
    private final SandboxHolds holds;
    private final TournamentGrants grants;

    public QueueService(AuthService auth, AccountRepository accounts, MatchQueue queue, Parties parties,
                        LobbyPush push, LongSupplier clock, Loadouts loadouts, TeamRepository teams,
                        ArenaDirectory arenas, TicketStore tickets, SandboxHolds holds,
                        TournamentGrants grants) {
        this.auth = auth;
        this.accounts = accounts;
        this.queue = queue;
        this.parties = parties;
        this.push = push;
        this.clock = clock;
        this.loadouts = loadouts;
        this.teams = teams;
        this.arenas = arenas;
        this.tickets = tickets;
        this.holds = holds;
        this.grants = grants;
    }

    public Outcome join(String sessionToken, String modeKey) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Outcome.NO_SESSION;
        }
        MatchMode mode = modeKey == null ? null : MatchMode.ofKey(modeKey);
        if (mode == null || !mode.queued()) {
            // The public arena is not queued for: it is a seat at once (/v1/match-requests).
            return Outcome.UNKNOWN_MODE;
        }
        Parties.Party party = parties.of(playerId);
        if (party != null && party.leader() != playerId) {
            return Outcome.IN_PARTY;
        }
        // The leader first: the entry is theirs, and a party's leader is its first member.
        List<Long> ids = party == null ? List.of(playerId) : party.members();
        if (ids.size() > mode.teamSize) {
            return Outcome.PARTY_TOO_BIG;
        }
        // A team match (Q-18): a whole side of one team, queued by its leader or a vice leader,
        // every member at the team's rating.
        TeamRepository.Side side = null;
        if (mode == MatchMode.TEAMS) {
            if (ids.size() < mode.teamSize) {
                return Outcome.PARTY_TOO_SMALL;
            }
            side = teams.sideOf(ids);
            if (side == null) {
                return Outcome.NOT_ONE_TEAM;
            }
            if (side.role() == TeamRepository.MEMBER) {
                return Outcome.NOT_ALLOWED;
            }
        }
        if (called(ids)) {
            return Outcome.IN_MATCH;                 // a tournament's call is a match's grant (Q-44)
        }
        List<MatchQueue.Member> members = new ArrayList<>(ids.size());
        for (long id : ids) {
            Profile profile = accounts.findProfile(id);
            int rating = side != null ? side.rating()
                    : mode.rated ? accounts.rating(id, mode.id) : 0;          // unrated: nothing to match by
            if (profile == null || rating < 0) {
                return Outcome.NO_SESSION;           // an account went under a live token, or a party
            }
            // What they wear, read with the rating: the matcher writes tickets from the store (D-37).
            members.add(new MatchQueue.Member(id, rating, profile.displayName(),
                    com.backend.handoff.Ticket.bonusOf(loadouts.bonusOf(id)), loadouts.skinOf(id)));
        }
        Outcome outcome = switch (queue.join(members, mode, party == null ? null : party.id(),
                side == null ? 0 : side.teamId(), clock.getAsLong())) {
            case QUEUED -> Outcome.QUEUED;
            case ALREADY_QUEUED -> Outcome.ALREADY_QUEUED;
            case IN_MATCH -> Outcome.IN_MATCH;
            case PARTY_CHANGED -> Outcome.PARTY_CHANGED;
            case LOCKED -> Outcome.QUEUE_LOCKED;
        };
        if (outcome == Outcome.QUEUED) {
            for (long id : ids) {
                if (id != playerId) {
                    push.send(id, "evt.queue.update", Matchmaker.queueUpdate(mode));
                }
            }
        }
        return outcome;
    }

    /**
     * Opens a sandbox for the caller, or for the caller's party when they lead one (04 §4, the
     * seventh slice; D-49): a room promised by an arena, a ticket each, every one recorded matched
     * and told {@code evt.match.found}, the caller too, as the matcher does once a match is
     * accepted, without the ask: the one asking is the one going, and the leader speaks for the
     * party as in the queue.
     */
    public Sandbox openSandbox(String sessionToken) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return new Sandbox(Opened.NO_SESSION, null);
        }
        Parties.Party party = parties.of(playerId);
        if (party != null && party.leader() != playerId) {
            return new Sandbox(Opened.IN_PARTY, null);
        }
        List<Long> ids = party == null ? List.of(playerId) : party.members();
        for (long id : ids) {
            String state = queue.status(id).state();
            if (MatchQueue.MATCHED.equals(state)) {
                return new Sandbox(Opened.IN_MATCH, null);
            }
            if (MatchQueue.QUEUED.equals(state) || MatchQueue.CONFIRMING.equals(state)) {
                return new Sandbox(Opened.ALREADY_QUEUED, null);
            }
        }
        List<MatchQueue.Member> members = new ArrayList<>(ids.size());
        for (long id : ids) {
            members.add(new MatchQueue.Member(id, 0, accounts.findProfile(id).displayName(),
                    Ticket.bonusOf(loadouts.bonusOf(id)), loadouts.skinOf(id)));
        }
        if (called(ids)) {
            return new Sandbox(Opened.IN_MATCH, null);
        }
        MatchMode mode = MatchMode.SANDBOX;
        String matchUid = Ulid.generate();
        // One sandbox a player, until it ends (D-54): every member's hold, or none.
        List<Long> held = new ArrayList<>(ids.size());
        for (long id : ids) {
            if (!await(holds.take(id, matchUid))) {
                giveBack(held);
                return new Sandbox(Opened.IN_SANDBOX, null);
            }
            held.add(id);
        }
        ArenaDirectory.Endpoint arena = arenas.reserveForMatch(matchUid);
        if (arena == null) {
            giveBack(held);
            return new Sandbox(Opened.NO_ROOM, null);
        }
        MatchQueue.Grant mine = null;
        for (MatchQueue.Member m : members) {
            Ticket ticket = Ticket.forMatch(m.playerId(), m.name(), 0, matchUid, mode.id, m.bonus(), m.skin());
            await(tickets.issue(ticket));
            MatchQueue.Grant grant = new MatchQueue.Grant(arena.host(), arena.port(), ticket.id(), arena.tls(), matchUid);
            queue.matched(m.playerId(), mode, grant, TicketStore.TTL_SECONDS, now());
            push.send(m.playerId(), "evt.match.found", Matchmaker.foundMessage(mode, grant));
            if (m.playerId() == playerId) {
                mine = grant;
            }
        }
        return new Sandbox(Opened.OPENED, mine);
    }

    /**
     * Takes the caller, and their party, out of the queue. A sandbox not yet joined is given back:
     * its ticket revoked, and the hold with it only if the revoke took it, so that a ticket
     * claimed meanwhile still finds its hold (D-54). @return false when the session is not one
     */
    public boolean leave(String sessionToken) {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return false;
        }
        MatchQueue.Status was = queue.status(playerId);
        List<Long> out = queue.leave(playerId);
        if (MatchQueue.MATCHED.equals(was.state()) && was.mode() == MatchMode.SANDBOX
                && await(tickets.revoke(was.grant().ticketId()))) {
            giveBack(List.of(playerId));
        }
        tellOut(out, playerId);
        return true;
    }

    private boolean called(List<Long> players) {
        for (long id : players) {
            if (await(grants.called(id))) {
                return true;
            }
        }
        return false;
    }

    private void giveBack(List<Long> players) {
        for (long id : players) {
            await(holds.release(id));
        }
    }

    /** Accepts, or declines, the match the caller is asked about (04 §4, the third slice). */
    public Answered answer(String sessionToken, String matchUid, boolean accept) {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Answered.NO_SESSION;
        }
        return queue.answer(playerId, matchUid, accept) == MatchQueue.Answer.RECORDED
                ? Answered.RECORDED : Answered.NOT_CONFIRMING;
    }

    /** A party changed: the entry {@code playerId} was queued in, if queued, is out, and its players are told. */
    public void partyChanged(long playerId) {
        tellOut(queue.unqueue(playerId), -1);
    }

    private void tellOut(List<Long> out, long asked) {
        for (long id : out) {
            if (id != asked) {
                push.send(id, "evt.queue.update", Matchmaker.queueUpdate(null));
            }
        }
    }

    /** @return the player's place, or null when the session is not one */
    public MatchQueue.Status status(String sessionToken) {
        long playerId = auth.playerIdOf(sessionToken);
        return playerId < 0 ? null : queue.status(playerId);
    }

    public long now() {
        return clock.getAsLong();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
