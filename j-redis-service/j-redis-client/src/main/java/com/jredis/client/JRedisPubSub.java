package com.jredis.client;

import com.jredis.common.Reply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A dedicated subscriber connection. Listeners run on the client's event loop: keep them short and
 * hand work to its owner thread. Subscriptions are remembered and re-issued after a reconnect, then
 * {@link #onReconnect} fires — messages published while disconnected are lost (pub/sub is
 * at-most-once), so use it to resynchronise.
 */
public final class JRedisPubSub implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JRedisPubSub.class);

    public interface MessageListener {
        void onMessage(String channel, byte[] message);
    }

    public interface PatternListener {
        void onMessage(String pattern, String channel, byte[] message);
    }

    private final ClientResources res;
    private final Connection connection;
    private final Map<String, CopyOnWriteArrayList<MessageListener>> channels = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PatternListener>> patterns = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingAcks = new ConcurrentHashMap<>();
    private volatile Runnable reconnectHook;
    private final java.util.concurrent.ScheduledFuture<?> pinging;

    JRedisPubSub(ClientResources res) {
        this.res = res;
        this.connection = new Connection(res, Connection.Mode.PUBSUB, "pubsub", new Connection.Listener() {
            @Override
            public void onPush(List<Reply> push) {
                dispatch(push);
            }

            @Override
            public void onReady(boolean reconnect) {
                resubscribe(reconnect);
            }
        });
        connection.connect();
        long every = res.config.pubsubPingMillis;
        this.pinging = every <= 0 ? null
                : res.group.next().scheduleAtFixedRate(this::ping, every, every, TimeUnit.MILLISECONDS);
    }

    /**
     * A request like any other, so a connection that leaves it unanswered for three timeouts is
     * closed as a stuck command connection is, and the primary looked for (D-39): a subscriber
     * is sent no replies otherwise, and a lost machine resets nothing.
     */
    private void ping() {
        if (connection.isReady()) {
            connection.send(Args.of(new Object[] {"PING"}), res.config.commandTimeoutMillis, Connection.KIND_RAW);
        }
    }

    /** Subscribes; the future completes when the server confirms. */
    // Each map change and its command are made together under one lock, so the server's
    // subscriptions always end up matching the maps, even when threads race on one channel.

    public CompletableFuture<Void> subscribe(String channel, MessageListener listener) {
        synchronized (this) {
            channels.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(listener);
            CompletableFuture<Void> ack = pendingAck("s:" + channel);
            connection.sendNoReply(Args.of("SUBSCRIBE", channel));
            return ack;
        }
    }

    public CompletableFuture<Void> psubscribe(String pattern, PatternListener listener) {
        synchronized (this) {
            patterns.computeIfAbsent(pattern, p -> new CopyOnWriteArrayList<>()).add(listener);
            CompletableFuture<Void> ack = pendingAck("p:" + pattern);
            connection.sendNoReply(Args.of("PSUBSCRIBE", pattern));
            return ack;
        }
    }

    public synchronized void unsubscribe(String channel) {
        if (channels.remove(channel) != null) {
            connection.sendNoReply(Args.of("UNSUBSCRIBE", channel));
        }
    }

    public synchronized void punsubscribe(String pattern) {
        if (patterns.remove(pattern) != null) {
            connection.sendNoReply(Args.of("PUNSUBSCRIBE", pattern));
        }
    }

    /** Runs after every reconnect, once subscriptions are re-issued. */
    public void onReconnect(Runnable hook) {
        this.reconnectHook = hook;
    }

    public boolean isConnected() {
        return connection.isReady();
    }

    private CompletableFuture<Void> pendingAck(String key) {
        CompletableFuture<Void> f = pendingAcks.computeIfAbsent(key, k -> new CompletableFuture<>());
        res.group.next().schedule(() -> {
            if (!f.isDone()) {
                pendingAcks.remove(key, f);
                f.completeExceptionally(new JRedisTimeoutException("no confirmation for " + key.substring(2)
                        + " yet; the subscription stays registered and is retried on reconnect"));
            }
        }, Math.max(res.config.commandTimeoutMillis, res.config.connectTimeoutMillis), TimeUnit.MILLISECONDS);
        return f;
    }

    private void resubscribe(boolean reconnect) {
        synchronized (this) {
            resendSubscriptions();
        }
        Runnable hook = reconnectHook;
        if (reconnect && hook != null) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.warn("pub/sub reconnect hook failed", e);
            }
        }
    }

    private void resendSubscriptions() {
        if (!channels.isEmpty()) {
            List<Object> a = new ArrayList<>();
            a.add("SUBSCRIBE");
            a.addAll(channels.keySet());
            connection.sendNoReply(Args.of(a));
        }
        if (!patterns.isEmpty()) {
            List<Object> a = new ArrayList<>();
            a.add("PSUBSCRIBE");
            a.addAll(patterns.keySet());
            connection.sendNoReply(Args.of(a));
        }
    }

    private void dispatch(List<Reply> push) {
        String kind = push.get(0).asString();
        switch (kind) {
            case "message": {
                String channel = push.get(1).asString();
                byte[] payload = push.get(2).asBytes();
                List<MessageListener> ls = channels.get(channel);
                if (ls != null) {
                    for (MessageListener l : ls) {
                        try {
                            l.onMessage(channel, payload);
                        } catch (RuntimeException e) {
                            log.warn("listener for channel {} failed", channel, e);
                        }
                    }
                }
                return;
            }
            case "pmessage": {
                String pattern = push.get(1).asString();
                String channel = push.get(2).asString();
                byte[] payload = push.get(3).asBytes();
                List<PatternListener> ls = patterns.get(pattern);
                if (ls != null) {
                    for (PatternListener l : ls) {
                        try {
                            l.onMessage(pattern, channel, payload);
                        } catch (RuntimeException e) {
                            log.warn("listener for pattern {} failed", pattern, e);
                        }
                    }
                }
                return;
            }
            case "subscribe":
                complete("s:" + push.get(1).asString());
                return;
            case "psubscribe":
                complete("p:" + push.get(1).asString());
                return;
            default:
                // unsubscribe confirmations need no action
        }
    }

    private void complete(String key) {
        CompletableFuture<Void> f = pendingAcks.remove(key);
        if (f != null) {
            f.complete(null);
        }
    }

    @Override
    public void close() {
        if (pinging != null) {
            pinging.cancel(false);
        }
        connection.close();
        JRedisClosedException closedEx = new JRedisClosedException("client closed");
        for (CompletableFuture<Void> ack : pendingAcks.values()) {
            ack.completeExceptionally(closedEx);
        }
        pendingAcks.clear();
    }
}
