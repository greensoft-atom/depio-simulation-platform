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
import com.jredis.client.Transaction;
import com.jredis.common.Reply;

/**
 * How many password checks one address, and one account, may ask for (S-2).
 *
 * <h2>Two limits, because they stop different things</h2>
 *
 * <b>Per address, 30 a minute, counting every registration and login.</b> This bounds what
 * one machine can spend of the most expensive thing the system does — Argon2 at about 88 ms
 * of CPU — and how fast it can guess. It is generous on purpose: mobile carriers put many
 * subscribers behind one public IPv4 address (CGNAT), and 30 a minute is still 43 000
 * password logins a day from one address, in a system where the common path is a session
 * resume that costs no password check at all. IPv6 is counted per /64, because that is what
 * one subscriber is given: counted per address, one attacker would have 2^64 of them.
 *
 * <b>Per account, 10 per quarter hour, counting every login that names it.</b> Bounds
 * guessing against one account from any number of addresses, which a per-address limit
 * cannot. Every attempt counts, not only failures, so the count is taken before the
 * password is checked and concurrent attempts cannot all slip under it together. A
 * username that does not exist is counted exactly like one that does, or the limit would
 * say which usernames exist.
 *
 * <h2>What it does not do</h2>
 *
 * It cannot stop credential stuffing from a large botnet — each address and each account
 * tried a few times stays under both limits. That needs breached-password checks or a
 * second factor, not a counter. And the account limit can be used to lock someone out:
 * ten wrong guesses a quarter hour keep an account from logging in with its password. A
 * player already holding a session is unaffected, because resuming one never reaches here.
 *
 * <h2>How</h2>
 *
 * Fixed windows in j-redis, so the limits hold across every {@code platform} process: one
 * {@code INCR} and {@code EXPIRE} per key, both keys in one {@code MULTI}, so one round
 * trip. A fixed window lets through up to twice the limit across a window boundary; over
 * any longer stretch the rate is the limit. If the store does not answer, the attempt is
 * refused ({@link StoreUnavailableException}, a 503): a login needs the store for its
 * session anyway, and a throttle that fails open is off whenever it is attacked hardest.
 */
public final class LoginThrottle {

    static final int ADDRESS_LIMIT = 30;
    static final long ADDRESS_WINDOW_MILLIS = 60_000;
    static final int ACCOUNT_LIMIT = 10;
    static final long ACCOUNT_WINDOW_MILLIS = 15 * 60_000;

    private static final String ADDRESS_PREFIX = "rl:login:addr:";
    private static final String ACCOUNT_PREFIX = "rl:login:acct:";

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
     * @param account the canonical username a login names, from
     *                {@link AuthService#usernameKey}; null for a registration, or for a
     *                login whose username could not name any account
     */
    public Verdict attempt(String address, String account) {
        long now = clock.getAsLong();
        long addressWindow = now / ADDRESS_WINDOW_MILLIS;
        long accountWindow = now / ACCOUNT_WINDOW_MILLIS;
        String addressKey = ADDRESS_PREFIX + address + ":" + addressWindow;
        Transaction tx = client.multi()
                .send("INCR", addressKey)
                .send("EXPIRE", addressKey, seconds(ADDRESS_WINDOW_MILLIS));
        if (account != null) {
            String accountKey = ACCOUNT_PREFIX + account + ":" + accountWindow;
            tx.send("INCR", accountKey)
              .send("EXPIRE", accountKey, seconds(ACCOUNT_WINDOW_MILLIS));
        }
        List<Reply> replies = await(tx.exec());

        int retryAfter = 0;
        if (count(replies.get(0)) > ADDRESS_LIMIT) {
            retryAfter = secondsUntil((addressWindow + 1) * ADDRESS_WINDOW_MILLIS, now);
        }
        if (account != null && count(replies.get(2)) > ACCOUNT_LIMIT) {
            retryAfter = Math.max(retryAfter,
                    secondsUntil((accountWindow + 1) * ACCOUNT_WINDOW_MILLIS, now));
        }
        return retryAfter == 0 ? ALLOWED : new Verdict(false, retryAfter);
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
