package com.jredis.examples;

import com.jredis.client.JRedisClient;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The async API. Requests sent without waiting are pipelined automatically: thousands of them cost
 * a few round trips. Futures compose with thenCompose/thenApply. Callbacks run on a client I/O
 * thread: never block in them (no join(), no sync()), and hand results to their owner thread.
 */
public final class AsyncPipelining implements Example {

    @Override
    public String name() {
        return "async";
    }

    @Override
    public String summary() {
        return "async API: pipelining, composing futures, callbacks";
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        client.sync().del("async:counter", "sess:async-token", "profile:user:9");

        // 1. Fire 10,000 requests, then wait for all of them once.
        long t0 = System.nanoTime();
        List<CompletableFuture<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            futures.add(client.incr("async:counter"));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        out.println("10,000 pipelined INCRs -> counter=" + client.sync().get("async:counter") + " in " + ms + " ms");
        out.println("replies keep their order: first=" + futures.get(0).join() + ", last=" + futures.get(9_999).join());

        // 2. Compose: resolve a session, then load the profile, without blocking any thread.
        client.sync().set("sess:async-token", "user:9");
        client.sync().set("profile:user:9", "{\"name\":\"Ada\"}");
        CompletableFuture<String> profile = client.get("sess:async-token")
                .thenCompose(userId -> userId == null
                        ? CompletableFuture.completedFuture(null)
                        : client.get("profile:" + userId));
        out.println("composed lookup        -> " + profile.join());

        // 3. Errors travel through the future (here: INCR on a non-number).
        client.sync().set("async:text", "abc");
        String outcome = client.incr("async:text")
                .handle((value, error) -> error == null ? "value " + value : "failed: " + error.getCause().getMessage())
                .join();
        out.println("error handling         -> " + outcome);
        client.sync().del("async:counter", "async:text", "sess:async-token", "profile:user:9");
    }
}
