package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisPubSub;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Publish/subscribe for live notifications. Subscriptions use a dedicated connection
 * ({@code client.pubSub()}) and are re-issued automatically after a reconnect. Delivery is
 * at-most-once: a subscriber that is disconnected misses messages, so anything that must not be
 * lost goes through a list (see the queue example) instead.
 */
public final class PubSubNotifications implements Example {

    @Override
    public String name() {
        return "pubsub";
    }

    @Override
    public String summary() {
        return "publish/subscribe: channels and patterns";
    }

    @Override
    public void run(JRedisClient client, PrintStream out) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        JRedisPubSub pubsub = client.pubSub();

        // Listeners run on a client I/O thread: keep them short and never block in them.
        pubsub.subscribe("user:42", (channel, message) ->
                received.add(channel + " <- " + new String(message, StandardCharsets.UTF_8)))
                .get(5, TimeUnit.SECONDS);                                    // wait for the confirmation
        pubsub.psubscribe("group:*", (pattern, channel, message) ->
                received.add(channel + " (" + pattern + ") <- " + new String(message, StandardCharsets.UTF_8)))
                .get(5, TimeUnit.SECONDS);
        pubsub.onReconnect(() -> received.add("(reconnected: re-read any state that may have changed)"));

        // Publishing goes through the normal command connection; the reply is the receiver count.
        long n = client.sync().publish("user:42", "your order has shipped");
        client.sync().publish("group:7", "meeting moved to 15:00");
        client.sync().publish("nobody-listens", "lost");                     // 0 receivers, dropped
        out.println("PUBLISH user:42 reached " + n + " subscriber(s)");

        for (int i = 0; i < 2; i++) {
            out.println("  received: " + received.poll(5, TimeUnit.SECONDS));
        }
        pubsub.unsubscribe("user:42");
        pubsub.punsubscribe("group:*");
    }
}
