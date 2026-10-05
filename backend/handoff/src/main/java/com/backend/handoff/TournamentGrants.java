package com.backend.handoff;

import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;

/**
 * A tournament match's grant for a player, kept as long as the ticket it names (04 §6): worker's
 * scheduler writes it, the platform's {@code GET /v1/tournaments/{id}/match} reads it. One class,
 * as {@link TicketStore} is, so the two processes cannot disagree on the key.
 */
public final class TournamentGrants {

    private static final String KEY_PREFIX = "tgrant:";
    /** The player called, by any tournament: what the queue and the sandbox read (Q-44). */
    private static final String CALL_PREFIX = "tcall:";

    private final JRedisClient client;

    public TournamentGrants(JRedisClient client) {
        this.client = client;
    }

    static String key(long tournamentId, long playerId) {
        return KEY_PREFIX + tournamentId + ":" + playerId;
    }

    /** The grant, JSON, and the player called, both for {@link TicketStore#TTL_SECONDS}. */
    public CompletableFuture<Boolean> put(long tournamentId, long playerId, String grant) {
        return client.multi()
                .send("SET", key(tournamentId, playerId), grant, "EX", TicketStore.TTL_SECONDS)
                .send("SET", CALL_PREFIX + playerId, tournamentId, "EX", TicketStore.TTL_SECONDS)
                .exec()
                .thenApply(replies -> replies != null && replies.stream().noneMatch(com.jredis.common.Reply::isError));
    }

    /** Whether the player has been called to a tournament match whose ticket still lives (Q-44). */
    public CompletableFuture<Boolean> called(long playerId) {
        return client.exists(CALL_PREFIX + playerId).thenApply(n -> n > 0);
    }

    /** The grant, or null before there is one and once it has expired. */
    public CompletableFuture<String> get(long tournamentId, long playerId) {
        return client.get(key(tournamentId, playerId));
    }
}
