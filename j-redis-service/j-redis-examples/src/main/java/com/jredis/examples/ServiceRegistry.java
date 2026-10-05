package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * Service discovery with heartbeats. Each instance writes its details to a hash with a short TTL
 * and its heartbeat time into a sorted set, every few seconds. Callers drop entries whose heartbeat
 * is too old and read the rest. A crashed instance disappears by itself.
 */
public final class ServiceRegistry implements Example {

    private static final String INDEX = "instances:orders";
    private static final long DEAD_AFTER_MILLIS = 10_000;

    @Override
    public String name() {
        return "registry";
    }

    @Override
    public String summary() {
        return "service registry with heartbeats and automatic cleanup";
    }

    /** Called by each instance every ~3 s. */
    void heartbeat(JRedisClient client, String instance, String host, int port, int load, long nowMillis) {
        client.multi()
                .send("HSET", "instance:" + instance, "host", host, "port", port, "load", load)
                .send("EXPIRE", "instance:" + instance, 10)
                .send("ZADD", INDEX, nowMillis, instance)
                .exec().join();
    }

    /** The live instances. */
    List<String> liveInstances(JRedisSync redis, long nowMillis) {
        redis.zremrangebyscore(INDEX, "-inf", "(" + (nowMillis - DEAD_AFTER_MILLIS));
        return redis.zrange(INDEX, 0, -1);
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        JRedisSync redis = client.sync();
        redis.del(INDEX, "instance:orders-1", "instance:orders-2", "instance:orders-3");
        long now = System.currentTimeMillis();
        heartbeat(client, "orders-1", "10.0.0.5", 9001, 12, now);
        heartbeat(client, "orders-2", "10.0.0.6", 9001, 40, now);
        heartbeat(client, "orders-3", "10.0.0.7", 9001, 7, now - 60_000);   // stopped a minute ago

        List<String> live = liveInstances(redis, now);
        out.println("live instances         -> " + live);
        for (String name : live) {
            Map<String, String> details = redis.hgetall("instance:" + name);
            out.println("  " + name + " " + details);
        }
    }
}
