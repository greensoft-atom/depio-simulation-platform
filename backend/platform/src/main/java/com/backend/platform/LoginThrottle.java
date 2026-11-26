package com.backend.platform;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import com.backend.handoff.StoreUnavailableException;
import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

/**
 * How many guests one address may make, and log in, a minute (S-2).
 *
 * <p><b>Per address, 30 a minute.</b> Since D-117 the passwords, and the limits that guard them, are the account
 * service's: the per-account limit, and the per-address one's share of Argon2, went with them (10 §3.3). What stays here
 * is the guests' (D-46): making one, and logging in by its key, each counted against its address, so that one machine
 * cannot make guests in bulk or try keys at speed. It is generous on purpose: mobile carriers put many subscribers behind
 * one public IPv4 address (CGNAT). IPv6 is counted per /64, because that is what one subscriber is given: counted per
 * address, one attacker would have 2^64 of them.
 *
 * <h2>How</h2>
 *
 * Fixed windows in j-redis, so the limit holds across every {@code platform} process: one {@code INCR} and
 * {@code EXPIRE} in one {@code MULTI}. A fixed window lets through up to twice the limit across a window boundary; over
 * any longer stretch the rate is the limit. If the store does not answer, the attempt is refused
 * ({@link StoreUnavailableException}, a 503): a login needs the store for its session anyway, and a throttle that fails
 * open is off whenever it is attacked hardest.
 */
public final class LoginThrottle {

    static final int ADDRESS_LIMIT = 30;
    static final long ADDRESS_WINDOW_MILLIS = 60_000;

    private static final String ADDRESS_PREFIX = "rl:login:addr:";

    /** Allowed, or refused with how long until the window that refused it ends. */
    public record Verdict(boolean allowed, int retryAfterSeconds) { }

    private static final Verdict ALLOWED = new Verdict(true, 0);

    private final JRedisClient client;
    private final LongSupplier clock;

    public LoginThrottle(JRedisClient client) {
        this(client, System::currentTimeMillis);
    }

    /** With the clock its windows are counted by, in epoch milliseconds: for tests. */
    public LoginThrottle(JRedisClient client, LongSupplier clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * Counts one attempt and says whether it may go ahead.
     *
     * @param address from {@link #addressKey}
     */
    public Verdict attempt(String address) {
        long now = clock.getAsLong();
        long addressWindow = now / ADDRESS_WINDOW_MILLIS;
        String addressKey = ADDRESS_PREFIX + address + ":" + addressWindow;
        List<Reply> replies = await(client.multi()
                .send("INCR", addressKey)
                .send("EXPIRE", addressKey, seconds(ADDRESS_WINDOW_MILLIS))
                .exec());
        if (count(replies.get(0)) > ADDRESS_LIMIT) {
            return new Verdict(false, secondsUntil((addressWindow + 1) * ADDRESS_WINDOW_MILLIS, now));
        }
        return ALLOWED;
    }

    /**
     * The key an address is counted under: an IPv4 address as itself, an IPv6 address by its
     * /64, the block one subscriber is given.
     */
    public static String addressKey(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        byte[] b = address.getAddress();
        return HexFormat.of().formatHex(b, 0, 8) + "/64";
    }

    private static long count(Reply reply) {
        if (reply.isError()) {
            // INCR on a key holding something else: not a throttle decision anyone can make.
            throw new StoreUnavailableException(new IllegalStateException(reply.asString()));
        }
        return reply.asLong();
    }

    private static int seconds(long millis) {
        return (int) (millis / 1000);
    }

    private static int secondsUntil(long endMillis, long now) {
        return (int) Math.max(1, (endMillis - now + 999) / 1000);
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
