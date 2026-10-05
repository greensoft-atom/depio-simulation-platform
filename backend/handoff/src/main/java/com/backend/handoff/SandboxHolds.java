package com.backend.handoff;

import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;

/**
 * The sandbox a player holds, {@code sbx:{playerId}} naming its match (D-54): {@code platform}
 * takes it when it opens one, for a ticket's life; the arena keeps it for the room's while the
 * player is in it, and gives it back when they leave or the room ends. One class, as
 * {@link TicketStore} is, so the two processes cannot disagree on the key.
 */
public final class SandboxHolds {

    /** A room's whole life (MatchMode.SANDBOX, twenty minutes) and a minute. */
    public static final int ROOM_SECONDS = 1_260;

    private static final String KEY_PREFIX = "sbx:";

    private final JRedisClient client;

    public SandboxHolds(JRedisClient client) {
        this.client = client;
    }

    static String key(long playerId) {
        return KEY_PREFIX + playerId;
    }

    /** Takes the hold for a ticket's life. @return false when the player holds one already */
    public CompletableFuture<Boolean> take(long playerId, String matchUid) {
        return client.set(key(playerId), matchUid, SetArgs.nx().andEx(TicketStore.TTL_SECONDS));
    }

    /** Keeps it for the room's whole life: the player is in it. */
    public CompletableFuture<Boolean> keep(long playerId, String matchUid) {
        return client.set(key(playerId), matchUid, SetArgs.ex(ROOM_SECONDS));
    }

    /** Gives it back. */
    public CompletableFuture<Long> release(long playerId) {
        return client.del(key(playerId));
    }
}
