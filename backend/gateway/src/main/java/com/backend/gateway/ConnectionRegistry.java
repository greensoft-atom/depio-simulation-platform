package com.backend.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.handoff.LobbyPush;
import com.jredis.client.JRedisClient;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.DefaultThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who is connected here, and where the rest of the system can find them
 * (docs detailed-design/03-gateway.md §4).
 *
 * The local map is what a push uses to find a socket. The store entry is what another
 * process uses to find the gateway holding it, and it expires on its own — a gateway that
 * dies does not have to be noticed by anything, because its claims simply stop being
 * refreshed. That is the same liveness rule the arena directory uses, for the same reason.
 *
 * <h2>One connection per player</h2>
 *
 * A second connection for the same player displaces the first. Two live lobby connections
 * would each believe they own the player's state, and pushes would arrive at whichever the
 * map happened to hold.
 *
 * <h2>A player moving between gateways (T-6)</h2>
 *
 * A phone that goes from Wi-Fi to mobile data reconnects to whichever gateway it reaches
 * first, while the old gateway still holds a socket it will only find dead later. Nothing
 * the old gateway does then may undo the new registration, and three things did: its
 * cleanup deleted the entry whoever had written it; its refresh re-wrote the entry every 20
 * s for a socket it still believed in, taking the player back; and its shutdown deleted every
 * entry it had ever held. So each registration writes a value of its own,
 * {@code gatewayId#nonce}, and:
 *
 * <ul>
 * <li>cleanup deletes the entry only while it still holds that value (WATCH, compare,
 *     MULTI/DEL), off the event loop, on this registry's own thread;</li>
 * <li>refresh never overwrites: {@code SET NX} restores an entry the store lost, and
 *     {@code EXPIRE} keeps whatever is there alive;</li>
 * <li>shutdown deletes nothing and lets the entries expire, which is the liveness rule
 *     above.</li>
 * </ul>
 *
 * A reader routes a push by the part of the value before {@code #}.
 */
final class ConnectionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    static final int TTL_SECONDS = 60;
    static final int REFRESH_SECONDS = 20;

    private static final String KEY_PREFIX = LobbyPush.CONNECTION_PREFIX;

    /** One socket, and the value its registration wrote. */
    private record Registration(Channel channel, String value) { }

    private final JRedisClient store;
    private final String gatewayId;
    private final Map<Long, Registration> local = new ConcurrentHashMap<>();

    /** Random per process, so a restarted gateway never writes a value its previous life did. */
    private final String processNonce =
            Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
    private final AtomicLong registrations = new AtomicLong();

    /** The push a displaced connection gets; a client does not reconnect after it. */
    static final String SESSION_REPLACED = "{\"t\":\"evt.session.replaced\",\"d\":{}}";
    /** A push that ends the connection once written: every session of the player was revoked. */
    static final String SESSION_REVOKED = "evt.session.revoked";

    private final java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    new DefaultThreadFactory("gateway-registry", true));

    ConnectionRegistry(JRedisClient store, String gatewayId) {
        this.store = store;
        this.gatewayId = gatewayId;
    }

    void start() {
        scheduler.scheduleAtFixedRate(this::refresh, REFRESH_SECONDS, REFRESH_SECONDS,
                TimeUnit.SECONDS);
    }

    /** @return when the store has the registration: until then a push to the player goes nowhere (T-39) */
    CompletableFuture<?> register(long playerId, Channel channel) {
        Registration mine = new Registration(channel,
                gatewayId + "#" + processNonce + "-" + registrations.incrementAndGet());
        Registration previous = local.put(playerId, mine);
        if (previous != null && previous.channel() != channel) {
            log.info("player {} connected again; closing the older connection", playerId);
            // Told why (03 §4), then closed once that is written. A bare close looked like a
            // network fault, and a client reconnects after one: two devices on one account
            // evicted each other for as long as both were on.
            previous.channel()
                    .writeAndFlush(new TextWebSocketFrame(SESSION_REPLACED))
                    .addListener(ChannelFutureListener.CLOSE);
        }
        return store.multi()
                .send("SET", KEY_PREFIX + playerId, mine.value(), "EX", TTL_SECONDS)
                .exec();
    }

    void unregister(long playerId, Channel channel) {
        // Only if it is still ours locally: a reconnect to this gateway that displaced this
        // connection must not lose its entry to the older connection's cleanup.
        Registration current = local.get(playerId);
        if (current != null && current.channel() == channel && local.remove(playerId, current)) {
            scheduler.execute(() -> deleteIfStill(playerId, current.value()));
        }
    }

    /**
     * Deletes the entry only if it still holds {@code value}: if another gateway, or a newer
     * connection here, has written its own since, the entry is theirs. Blocking, which is why
     * it runs on this registry's thread and never on an event loop. A failure is only logged:
     * the entry expires by itself within {@value #TTL_SECONDS} s.
     */
    private void deleteIfStill(long playerId, String value) {
        String key = KEY_PREFIX + playerId;
        try {
            store.withLeasedConnection(conn -> {
                conn.sync().watch(key);
                if (!value.equals(conn.sync().get(key))) {
                    conn.sync().unwatch();
                    return null;
                }
                // Null when the key changed between the read and here: then it is someone
                // else's now, and staying is right.
                conn.sync().exec(conn.multi().send("DEL", key));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("could not remove the registration of player {}: {}", playerId, e.toString());
        }
    }

    /**
     * Hands a push to the player's connection, if the player is connected here (03 §5), on the
     * connection's own loop, where its {@link Pushes} writes it or holds it for a slow client
     * (03 §8). Called on the store client's thread, so nothing waits here.
     *
     * @return false when the player is not connected here: moved to another gateway since the
     *         sender looked them up, or gone
     */
    boolean deliver(LobbyPush.Delivery delivery) {
        Registration r = local.get(delivery.to());
        if (r == null || !r.channel().isActive()) {
            return false;
        }
        Pushes pushes = r.channel().pipeline().get(Pushes.class);
        // Banned (04 §10): told why, then closed once that is written, as a replaced one is.
        boolean revoked = SESSION_REVOKED.equals(delivery.type());
        r.channel().eventLoop().execute(() -> pushes.offer(delivery.message(), revoked));
        return true;
    }

    /** Hands a message for everyone (04 §10) to each connection held here, each on its own loop. */
    int broadcast(LobbyPush.Delivery delivery) {
        return offerAll(delivery.message());
    }

    /**
     * Tells each connection held here to fetch what it would after a reconnect (03 §5): what was
     * published while the subscription was lost is lost, and the lobby socket never dropped.
     */
    int resync() {
        return offerAll(Pushes.RESYNC);
    }

    private int offerAll(String message) {
        int n = 0;
        for (Registration r : local.values()) {
            if (r.channel().isActive()) {
                Pushes pushes = r.channel().pipeline().get(Pushes.class);
                r.channel().eventLoop().execute(() -> pushes.offer(message, false));
                n++;
            }
        }
        return n;
    }

    Channel channelOf(long playerId) {
        Registration r = local.get(playerId);
        return r == null ? null : r.channel();
    }

    int size() {
        return local.size();
    }

    /**
     * Keeps every live connection's entry alive without ever taking an entry back: NX writes
     * only where the store has nothing, EXPIRE extends whatever is there.
     */
    void refresh() {
        // The store's failures are futures failed, not exceptions thrown: read, counted, and said once a round, or a
        // gateway whose registrations lapsed, its players' pushes then lost, said nothing (the gateway review).
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        List<CompletableFuture<?>> sent = new ArrayList<>();
        try {
            for (Map.Entry<Long, Registration> entry : local.entrySet()) {
                if (entry.getValue().channel().isActive()) {
                    String key = KEY_PREFIX + entry.getKey();
                    sent.add(store.multi()
                            .send("SET", key, entry.getValue().value(), "NX", "EX", TTL_SECONDS)
                            .send("EXPIRE", key, TTL_SECONDS)
                            .exec()
                            .whenComplete((replies, e) -> {
                                if (e != null) {
                                    failed.incrementAndGet();
                                }
                            }));
                }
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
        }
        CompletableFuture.allOf(sent.toArray(new CompletableFuture<?>[0])).whenComplete((all, e) -> {
            int n = failed.get();
            if (n > 0) {
                refreshFailures.add(n);
                log.warn("could not refresh {} of {} connection registrations: is the store there?", n, sent.size());
            }
        });
    }

    /** Registrations the refresh could not renew, every round so far: a metric (backend_gateway_registry_refresh_failures_total). */
    long refreshFailures() {
        return refreshFailures.sum();
    }

    private final java.util.concurrent.atomic.LongAdder refreshFailures = new java.util.concurrent.atomic.LongAdder();

    /**
     * Stops refreshing and forgets. Deletes nothing: the players this gateway held are
     * reconnecting elsewhere as it goes, and a delete issued now can land after their new
     * registration. The entries expire by themselves.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        local.clear();
    }
}
