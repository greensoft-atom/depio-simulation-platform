package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.backend.handoff.LobbyPush;
import com.backend.handoff.StoreUnavailableException;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.FriendRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Friends, blocks and presence, for a session (docs detailed-design/04-platform-services.md §9,
 * Q-20). A friend is online while their lobby connection is registered, read with the list.
 * Requests and acceptances are pushed to a player online; a request from someone the other has
 * blocked is answered as asked, and nobody is told.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class FriendService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Result {
        OK, NO_SESSION, YOURSELF, NO_SUCH_PLAYER, ALREADY_FRIENDS, ALREADY_ASKED, FRIENDS_FULL, YOU_BLOCKED,
        NOT_FRIENDS, NO_REQUEST, BLOCKS_FULL, NOT_BLOCKED, TOO_MANY_ASKED, TOO_SOON
    }

    public record Friend(long playerId, String name, boolean online) { }

    public record Lists(List<Friend> friends, List<FriendRepository.Request> requests,
                        List<FriendRepository.Request> asked) { }

    /** An ask's answer: {@code state} is {@code asked} or {@code friends} when the result is OK. */
    public record Asked(Result result, String state) { }

    private final AuthService auth;
    private final AccountRepository accounts;
    private final FriendRepository friends;
    private final LobbyPush push;
    private final Clock clock;
    private final AskThrottle throttle;

    public FriendService(AuthService auth, AccountRepository accounts, FriendRepository friends, LobbyPush push,
                         Clock clock, AskThrottle throttle) {
        this.auth = auth;
        this.accounts = accounts;
        this.friends = friends;
        this.push = push;
        this.clock = clock;
        this.throttle = throttle;
    }

    /** @return the player's friends, requests to them and their own; null when the session is not one */
    public Lists lists(String token) throws SQLException {
        long me = auth.playerIdOf(token);
        if (me < 0) {
            return null;
        }
        List<FriendRepository.Person> people = friends.friendsOf(me);
        // Every friend's presence in one read, not one a friend (S-15).
        List<Boolean> online = await(push.connected(people.stream().map(FriendRepository.Person::playerId).toList()));
        List<Friend> out = new ArrayList<>(people.size());
        for (int i = 0; i < people.size(); i++) {
            out.add(new Friend(people.get(i).playerId(), people.get(i).name(), online.get(i)));
        }
        return new Lists(out, friends.requestsTo(me, clock.instant()), friends.askedBy(me, clock.instant()));
    }

    /** Asks, {@value AskThrottle#PER_HOUR} times an hour at most, every ask counted (Q-46). */
    public Asked ask(String token, long playerId) throws SQLException {
        long me = auth.playerIdOf(token);
        if (me < 0) {
            return new Asked(Result.NO_SESSION, null);
        }
        if (!throttle.allow(AskThrottle.Kind.FRIEND_REQUEST, me)) {
            return new Asked(Result.TOO_SOON, null);
        }
        FriendRepository.Asked asked = friends.ask(me, playerId, clock.instant());
        switch (asked) {
            case ASKED -> tell(playerId, "evt.friend.request", me);
            case FRIENDS -> tell(playerId, "evt.friend.accepted", me);
            default -> { }
        }
        if (asked == FriendRepository.Asked.ASKED || asked == FriendRepository.Asked.FRIENDS) {
            push.send(playerId, "evt.inbox", null);         // and it is in their inbox: look
        }
        return switch (asked) {
            case ASKED, IGNORED -> new Asked(Result.OK, "asked");
            case FRIENDS -> new Asked(Result.OK, "friends");
            default -> new Asked(Result.valueOf(asked.name()), null);
        };
    }

    public Result remove(String token, long playerId) throws SQLException {
        long me = auth.playerIdOf(token);
        return me < 0 ? Result.NO_SESSION : friends.remove(me, playerId) ? Result.OK : Result.NOT_FRIENDS;
    }

    public Result dropRequest(String token, long playerId) throws SQLException {
        long me = auth.playerIdOf(token);
        return me < 0 ? Result.NO_SESSION : friends.dropRequest(me, playerId) ? Result.OK : Result.NO_REQUEST;
    }

    /** @return those the player has blocked; null when the session is not one */
    public List<FriendRepository.Person> blocked(String token) throws SQLException {
        long me = auth.playerIdOf(token);
        return me < 0 ? null : friends.blockedBy(me);
    }

    public Result block(String token, long playerId) throws SQLException {
        long me = auth.playerIdOf(token);
        if (me < 0) {
            return Result.NO_SESSION;
        }
        FriendRepository.Blocked b = friends.block(me, playerId, clock.instant());
        return b == FriendRepository.Blocked.BLOCKED ? Result.OK : Result.valueOf(b.name());
    }

    public Result unblock(String token, long playerId) throws SQLException {
        long me = auth.playerIdOf(token);
        return me < 0 ? Result.NO_SESSION : friends.unblock(me, playerId) ? Result.OK : Result.NOT_BLOCKED;
    }

    /** Tells {@code to}, if online, what {@code from} did, by id and name. */
    private void tell(long to, String type, long from) throws SQLException {
        AccountRepository.Profile profile = accounts.findProfile(from);
        push.send(to, type, JSON.createObjectNode().put("playerId", from)
                .put("name", profile == null ? null : profile.displayName()));
    }

    private static <T> T await(CompletableFuture<T> future) {
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
