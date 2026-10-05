package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

import java.io.PrintStream;
import java.util.List;
import java.util.UUID;

/**
 * Rate limiting. Fixed window: one counter per user and window, with a TTL so old windows vanish.
 * Sliding window: a sorted set of request timestamps, trimmed on every call. Both are a single
 * atomic MULTI round trip.
 */
public final class RateLimiter implements Example {

    @Override
    public String name() {
        return "ratelimit";
    }

    @Override
    public String summary() {
        return "fixed-window and sliding-window rate limiting";
    }

    /** At most {@code limit} requests per {@code windowSeconds}. */
    boolean allowFixedWindow(JRedisClient client, String userId, int limit, int windowSeconds) {
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = "rl:" + userId + ":" + window;
        List<Reply> r = client.multi()
                .send("INCR", key)
                .send("EXPIRE", key, windowSeconds + 1, "NX")        // only on the first request
                .exec().join();
        return r.get(0).asLong() <= limit;
    }

    /** At most {@code limit} requests in any rolling {@code windowMillis}. */
    boolean allowSlidingWindow(JRedisClient client, String userId, int limit, long windowMillis) {
        long now = System.currentTimeMillis();
        String key = "rls:" + userId;
        List<Reply> r = client.multi()
                .send("ZREMRANGEBYSCORE", key, "-inf", "(" + (now - windowMillis))   // forget old requests
                .send("ZADD", key, now, now + ":" + UUID.randomUUID())              // this request
                .send("ZCARD", key)                                                  // requests in the window
                .send("PEXPIRE", key, windowMillis)                                  // idle keys disappear
                .exec().join();
        return r.get(2).asLong() <= limit;
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        long window = System.currentTimeMillis() / 1000 / 60;
        client.sync().del("rl:user:42:" + window, "rls:user:42");
        StringBuilder fixed = new StringBuilder();
        StringBuilder sliding = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            fixed.append(allowFixedWindow(client, "user:42", 5, 60) ? "ok " : "LIMITED ");
            sliding.append(allowSlidingWindow(client, "user:42", 5, 60_000) ? "ok " : "LIMITED ");
        }
        out.println("fixed window, 5/min    -> " + fixed.toString().trim());
        out.println("sliding window, 5/min  -> " + sliding.toString().trim());
        out.println("(a rejected request still counts in the sliding window; remove its entry if it should not)");
        client.sync().del("rl:user:42:" + window, "rls:user:42");
    }
}
