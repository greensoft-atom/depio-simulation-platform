package com.backend.platform;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.backend.handoff.LobbyPush;
import com.backend.handoff.StoreUnavailableException;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AccountRepository.Profile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Parties for a session (docs detailed-design/04-platform-services.md §4, "Parties"; D-25):
 * who may invite whom, and telling every member what changed, through the lobby's push route.
 *
 * An invitee must be in the lobby: the invitation is a push, and a player who is not there
 * would never see it. A name is read from MySQL once, when its player invites or joins, and
 * kept with the party, so a change is told without reading them again.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class PartyService {

    public enum Outcome {
        OK, NO_SESSION, SELF, IN_PARTY, NOT_LEADER, PARTY_FULL, NOT_IN_LOBBY, NO_INVITATION, NOT_MEMBER, QUEUED,
        UNKNOWN_PHRASE, NO_PARTY, TOO_SOON
    }

    /**
     * What happened, and the caller's party as it now is: null for none, or, when this change ended it for
     * the caller, the party it ended with no members, at that change's version (D-74).
     */
    public record Result(Outcome outcome, Parties.Party party) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuthService auth;
    private final AccountRepository accounts;
    private final Parties parties;
    private final LobbyPush pushes;
    private final AskThrottle asks;
    private final com.backend.persistence.FriendRepository friends;
    private final QueueService queues;

    public PartyService(AuthService auth, AccountRepository accounts, Parties parties, LobbyPush pushes,
                        QueueService queues, com.backend.persistence.FriendRepository friends, AskThrottle asks) {
        this.auth = auth;
        this.asks = asks;
        this.accounts = accounts;
        this.parties = parties;
        this.pushes = pushes;
        this.queues = queues;
        this.friends = friends;
    }

    /** The caller's party; a result of NO_SESSION when the session is not one. */
    public Result view(String sessionToken) {
        long me = auth.playerIdOf(sessionToken);
        return me < 0 ? new Result(Outcome.NO_SESSION, null) : new Result(Outcome.OK, parties.of(me));
    }

    /**
     * Invites {@code to}, and tells them if they are in the lobby: {@code evt.party.invite}. Only a
     * friend of theirs is told they are not, since only a friend sees their presence (Q-20, S-16);
     * anyone else is answered as sent, and the invitation lapses unseen.
     */
    public Result invite(String sessionToken, long to) throws SQLException {
        long me = auth.playerIdOf(sessionToken);
        if (me < 0) {
            return new Result(Outcome.NO_SESSION, null);
        }
        // Each invitation is a push to someone: sixty an hour, as friend requests are twenty (S-22).
        if (!asks.allow(AskThrottle.Kind.PARTY_INVITE, me)) {
            return new Result(Outcome.TOO_SOON, parties.of(me));
        }
        boolean here = await(pushes.connected(to));
        if (!here && friends.areFriends(me, to)) {
            return new Result(Outcome.NOT_IN_LOBBY, parties.of(me));
        }
        String name = nameOf(me);
        Parties.Invitation invitation = parties.invite(me, name, to);
        Outcome outcome = switch (invitation.result()) {
            case INVITED -> Outcome.OK;
            case SELF -> Outcome.SELF;
            case IN_PARTY -> Outcome.IN_PARTY;
            case NOT_LEADER -> Outcome.NOT_LEADER;
            case PARTY_FULL -> Outcome.PARTY_FULL;
        };
        // Answered as sent to one who has blocked the inviter, and never delivered: it lapses (Q-20).
        if (outcome == Outcome.OK && here && !friends.blocks(to, me)) {
            pushes.send(to, "evt.party.invite", JSON.createObjectNode()
                    .put("partyId", invitation.partyId()).put("from", me).put("fromName", name));
        }
        return new Result(outcome, parties.of(me));
    }

    /** Joins the party one was invited to, and tells every member: {@code evt.party.update}. */
    public Result accept(String sessionToken, String partyId) throws SQLException {
        long me = auth.playerIdOf(sessionToken);
        if (me < 0) {
            return new Result(Outcome.NO_SESSION, null);
        }
        Outcome outcome = switch (parties.accept(me, nameOf(me), partyId)) {
            case JOINED -> Outcome.OK;
            case NO_INVITATION -> Outcome.NO_INVITATION;
            case IN_PARTY -> Outcome.IN_PARTY;
            case PARTY_FULL -> Outcome.PARTY_FULL;
            case QUEUED -> Outcome.QUEUED;
        };
        Parties.Party party = parties.of(me);
        if (outcome == Outcome.OK) {
            tell(party);
        }
        return new Result(outcome, party);
    }

    /** Leaves the caller's party, telling them and whoever is left. */
    public Result leave(String sessionToken) {
        long me = auth.playerIdOf(sessionToken);
        if (me < 0) {
            return new Result(Outcome.NO_SESSION, null);
        }
        Parties.Change change = parties.leave(me);
        told(change, me);
        return new Result(Outcome.OK, change.before() == null ? null : Parties.ended(change.before()));
    }

    /** The leader removes a member, telling them and whoever is left. */
    public Result kick(String sessionToken, long member) {
        long me = auth.playerIdOf(sessionToken);
        if (me < 0) {
            return new Result(Outcome.NO_SESSION, null);
        }
        Parties.Change change = parties.kick(me, member);
        if (change.before() == null) {
            return new Result(Outcome.NOT_MEMBER, parties.of(me));
        }
        told(change, member);
        return new Result(Outcome.OK, change.after() != null ? change.after() : Parties.ended(change.before()));
    }

    /**
     * Says a phrase to the caller's party (01 §9), pushed to every member as
     * {@code evt.party.said}, the speaker too. One every two seconds; an id not in the list, or
     * a player in no party, takes no turn.
     */
    public Result say(String sessionToken, int phraseId) {
        long me = auth.playerIdOf(sessionToken);
        if (me < 0) {
            return new Result(Outcome.NO_SESSION, null);
        }
        if (!com.backend.sim.PhraseTable.defaults().contains(phraseId)) {
            return new Result(Outcome.UNKNOWN_PHRASE, parties.of(me));
        }
        Parties.Party party = parties.of(me);
        if (party == null) {
            return new Result(Outcome.NO_PARTY, null);
        }
        if (!parties.takeTurnToSay(me)) {
            return new Result(Outcome.TOO_SOON, party);
        }
        ObjectNode said = JSON.createObjectNode().put("from", me)
                .put("name", party.names().getOrDefault(me, "")).put("phraseId", phraseId);
        for (long member : party.members()) {
            pushes.send(member, "evt.party.said", said);
        }
        return new Result(Outcome.OK, party);
    }

    /**
     * The JSON a party is told as, and answered as, with its {@code version}: none is {@code partyId} null and
     * no members, and {@code was} the party a change ended, at that change's version (D-74).
     */
    public static ObjectNode json(Parties.Party party) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode members = out.putNull("partyId").put("leader", 0).putArray("members");
        if (party == null) {
            return out;
        }
        if (party.members().isEmpty()) {
            out.put("was", party.id());
        } else {
            out.put("partyId", party.id()).put("leader", party.leader());
            for (long m : party.members()) {
                members.addObject().put("playerId", m).put("name", party.names().getOrDefault(m, ""));
            }
        }
        return out.put("version", party.version());
    }

    /**
     * Tells the one who went that they are in no party, and the rest what their party now is. A
     * party that changed is not the one queued: out of the queue it goes, and each is told that too.
     */
    private void told(Parties.Change change, long gone) {
        if (change.before() == null) {
            return;
        }
        queues.partyChanged(gone);
        ObjectNode ended = json(Parties.ended(change.before()));
        pushes.send(gone, "evt.party.update", ended);
        if (change.after() != null) {
            tell(change.after());
        } else {
            for (long m : change.before().members()) {
                if (m != gone) {
                    pushes.send(m, "evt.party.update", ended);        // a party of one is no party
                }
            }
        }
    }

    private void tell(Parties.Party party) {
        ObjectNode data = json(party);
        for (long m : party.members()) {
            pushes.send(m, "evt.party.update", data);
        }
    }

    private String nameOf(long playerId) throws SQLException {
        Profile profile = accounts.findProfile(playerId);
        return profile == null ? "" : profile.displayName();
    }

    private static boolean await(CompletableFuture<Boolean> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
