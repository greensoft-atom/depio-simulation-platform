package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;

import java.io.PrintStream;

/**
 * The basics: connect, write, read, and the difference between the async API and the blocking
 * facade.
 *
 * <pre>
 * JRedisClient client = JRedisClient.builder()
 *         .address("127.0.0.1", 6379)
 *         .password("secret")                  // if the server has requirepass
 *         .clientName("orders-service")         // shows up in CLIENT LIST
 *         .build()
 *         .start();                             // connects now
 * ...
 * client.close();                               // at application shutdown
 * </pre>
 *
 * One client per application is the norm: it is thread-safe, pipelines automatically and
 * reconnects by itself.
 */
public final class QuickStart implements Example {

    @Override
    public String name() {
        return "quickstart";
    }

    @Override
    public String summary() {
        return "connect, SET/GET, async vs. blocking calls";
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        // The blocking facade: easiest to read, fine on ordinary worker threads.
        JRedisSync redis = client.sync();
        redis.del("demo:greeting", "demo:visits");

        redis.set("demo:greeting", "hello, j-redis");
        out.println("GET demo:greeting      -> " + redis.get("demo:greeting"));
        out.println("INCR demo:visits       -> " + redis.incr("demo:visits"));
        out.println("INCR demo:visits       -> " + redis.incr("demo:visits"));
        out.println("GET demo:missing       -> " + redis.get("demo:missing"));   // null for a missing key

        // The async API: every method returns a CompletableFuture and never blocks the caller.
        // Use it on event-loop or other latency-critical threads.
        client.get("demo:greeting")
                .thenAccept(v -> out.println("async GET              -> " + v))
                .join();                                  // join() only to keep the example linear

        // Any command, including the J.* extensions, through send():
        out.println("PING via send()        -> " + client.send("PING").join().asString());
    }
}
