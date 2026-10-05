package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;
import com.jredis.client.SetArgs;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cache-aside: read from j-redis, fall back to the slow source on a miss, store the result with a
 * TTL, and delete the cache entry when the source changes.
 */
public final class CacheAside implements Example {

    private static final int TTL_SECONDS = 300;

    /** Stands in for a database or a remote service. */
    private final AtomicInteger slowLookups = new AtomicInteger();

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public String summary() {
        return "cache-aside with TTL and invalidation";
    }

    private String loadProfileFromDatabase(String userId) {
        slowLookups.incrementAndGet();
        return "{\"id\":\"" + userId + "\",\"name\":\"User " + userId + "\"}";
    }

    /** The pattern itself. */
    String profile(JRedisSync redis, String userId) {
        String key = "cache:profile:" + userId;
        String cached = redis.get(key);
        if (cached != null) {
            return cached;                                     // hit
        }
        String fresh = loadProfileFromDatabase(userId);        // miss: go to the source
        redis.set(key, fresh, SetArgs.ex(TTL_SECONDS));        // and remember it for 5 minutes
        return fresh;
    }

    /** Call after changing the source, so the next read reloads it. */
    void invalidate(JRedisSync redis, String userId) {
        redis.del("cache:profile:" + userId);
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        JRedisSync redis = client.sync();
        redis.del("cache:profile:42");
        slowLookups.set(0);

        profile(redis, "42");                                   // miss
        profile(redis, "42");                                   // hit
        profile(redis, "42");                                   // hit
        out.println("3 reads, database lookups: " + slowLookups.get());
        out.println("cached value: " + redis.get("cache:profile:42") + " (TTL " + redis.ttl("cache:profile:42") + " s)");

        invalidate(redis, "42");                                // the profile changed
        profile(redis, "42");                                   // miss again
        out.println("after invalidation, database lookups: " + slowLookups.get());
    }
}
