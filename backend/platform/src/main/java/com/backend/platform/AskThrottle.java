package com.backend.platform;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import com.backend.handoff.StoreUnavailableException;
import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

/**
 * How often one player may ask others: friend requests, team invitations and applications, each
 * {@value #PER_HOUR} an hour a player (Q-46, S-15); party invitations {@value #PARTY_INVITES_PER_HOUR}, a party
 * being made often to play, its invitations each a push to someone (S-22). Every ask is counted, one withdrawn and
 * made again too, since each is a push and an inbox item to someone.
 *
 * Fixed windows in the store, as {@link LoginThrottle}'s, so the limit holds across every
 * {@code platform}: one {@code INCR} and {@code EXPIRE} in one {@code MULTI}. A fixed window lets
 * through up to twice the limit across its end; over any longer stretch the rate is the limit. A
 * store that does not answer refuses ({@link StoreUnavailableException}, a 503): a limit that
 * fails open is off when it is pushed hardest.
 */
public final class AskThrottle {

    static final int PER_HOUR = 20;
    static final int PARTY_INVITES_PER_HOUR = 60;
    static final long WINDOW_MILLIS = 3_600_000;

    /** What is asked, the key it is counted under ({@code rl:ask:{playerId}:{hour}}, {@code rl:tinv:…}), and how many an hour. */
    public enum Kind {
        FRIEND_REQUEST("rl:ask:", PER_HOUR), TEAM_INVITE("rl:tinv:", PER_HOUR), TEAM_APPLICATION("rl:tapp:", PER_HOUR),
        PARTY_INVITE("rl:pinv:", PARTY_INVITES_PER_HOUR);

        final String prefix;
        final int perHour;

        Kind(String prefix, int perHour) {
            this.prefix = prefix;
            this.perHour = perHour;
        }
    }

    private final JRedisClient store;
    private final LongSupplier clock;

    public AskThrottle(JRedisClient store) {
        this(store, System::currentTimeMillis);
    }

    /** With the clock its windows are counted by, in epoch milliseconds: for tests. */
    public AskThrottle(JRedisClient store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Counts one ask and says whether it may go ahead. */
    public boolean allow(Kind kind, long playerId) {
        String key = kind.prefix + playerId + ":" + clock.getAsLong() / WINDOW_MILLIS;
        List<Reply> replies = await(store.multi()
                .send("INCR", key)
                .send("EXPIRE", key, WINDOW_MILLIS / 1_000)
                .exec());
        if (replies.get(0).isError()) {
            throw new StoreUnavailableException(new IllegalStateException(replies.get(0).asString()));
        }
        return replies.get(0).asLong() <= kind.perHour;
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
