package com.backend.handoff;

import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;

/**
 * Whether anyone came to a made match, {@code marr:{matchUid}} (04 §6, Q-45): the arena marks it
 * at the first arrival, and the tournament's scheduler reads it when no result has come. One class,
 * as {@link TicketStore} is, so the two processes cannot disagree on the key.
 */
public final class MatchArrivals {

    /** Longer than any match is waited for (30 minutes). */
    static final int TTL_SECONDS = 3_600;

    private static final String KEY_PREFIX = "marr:";

    private final JRedisClient client;

    public MatchArrivals(JRedisClient client) {
        this.client = client;
    }

    /** Somebody came. */
    public CompletableFuture<Boolean> mark(String matchUid) {
        return client.set(KEY_PREFIX + matchUid, "1", com.jredis.client.SetArgs.ex(TTL_SECONDS));
    }

    /** Whether anybody came, as the arena marked it. */
    public CompletableFuture<Boolean> arrived(String matchUid) {
        return client.exists(KEY_PREFIX + matchUid).thenApply(n -> n > 0);
    }
}
