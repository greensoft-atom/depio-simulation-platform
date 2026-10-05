package com.backend.handoff;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jredis.client.JRedisClient;

/**
 * A message to a player's lobby connection, from any process (docs detailed-design/03-gateway.md
 * §5): which gateway holds the connection is looked up, the message is published on that
 * gateway's channel, and the gateway writes it to the socket.
 *
 * <h2>At most once</h2>
 *
 * A player with no lobby connection, or one who moves to another gateway between the lookup
 * and the publish, gets nothing, and nobody is told. So nothing a client must not miss travels
 * only this way: it can also be fetched (03 §7). A match found is announced here and can be
 * asked for with {@code GET /v1/queue}.
 *
 * <h2>One channel per gateway</h2>
 *
 * Each gateway subscribes to one channel, its own, not one per player: fifty thousand
 * subscriptions would be resubscribed in a storm every time a gateway restarted. The cost is
 * the lookup, one {@code GET} a push.
 */
public final class LobbyPush {

    /**
     * {@code conn:{playerId}} holds {@code gatewayId#registration}, written by the gateway that
     * holds the connection and expiring when it stops refreshing (03 §4).
     */
    public static final String CONNECTION_PREFIX = "conn:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JRedisClient store;
    private final java.util.concurrent.atomic.LongAdder unheard = new java.util.concurrent.atomic.LongAdder();

    public LobbyPush(JRedisClient store) {
        this.store = store;
    }

    /** The channel every gateway also listens on: a message there is for everyone it holds (04 §10). */
    public static final String ALL_CHANNEL = "push:all";

    /** The channel a gateway listens on. */
    public static String channelOf(String gatewayId) {
        return "push:" + gatewayId;
    }

    /** The gateway a registration names: the part before {@code #}. */
    public static String gatewayOf(String registration) {
        int hash = registration.indexOf('#');
        return hash < 0 ? registration : registration.substring(0, hash);
    }

    /**
     * Pushes {@code {"t": type, "d": data}} to the player's lobby connection.
     *
     * @return whether a gateway was listening on the channel of the one that holds the player.
     *         True is not delivery: that gateway may find the socket gone. False is certainly
     *         not.
     */
    public CompletableFuture<Boolean> send(long playerId, String type, JsonNode data) {
        return store.get(CONNECTION_PREFIX + playerId).thenCompose(registration -> {
            if (registration == null) {
                return CompletableFuture.completedFuture(false);
            }
            return store
                    .publish(channelOf(gatewayOf(registration)), envelope(playerId, type, data))
                    .thenApply(listeners -> {
                        if (listeners == 0) {
                            unheard.increment();
                        }
                        return listeners > 0;
                    });
        });
    }

    /**
     * Pushes to a player registered on a gateway whose channel nobody heard: a gateway gone
     * within its registration's minute, or a subscription left on a store that is no longer the
     * primary (defect O-6). Counted for each process's metrics.
     */
    public long unheard() {
        return unheard.sum();
    }

    /**
     * Pushes {@code {"t": type, "d": data}} to everyone in the lobby, on every gateway: an
     * operator's notice (04 §10). At most once, as every push.
     *
     * @return how many gateways were listening
     */
    public CompletableFuture<Long> broadcast(String type, JsonNode data) {
        return store.publish(ALL_CHANNEL, envelope(0, type, data));
    }

    /** Whether the player has a lobby connection anywhere, as of its last refresh. */
    public CompletableFuture<Boolean> connected(long playerId) {
        return store.exists(CONNECTION_PREFIX + playerId).thenApply(n -> n > 0);
    }

    /** Whether each of the players has a lobby connection, in their order, in one read (S-15). */
    public CompletableFuture<java.util.List<Boolean>> connected(java.util.List<Long> players) {
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }
        return store.mget(players.stream().map(p -> CONNECTION_PREFIX + p).toArray(String[]::new))
                .thenApply(registrations -> registrations.stream().map(java.util.Objects::nonNull).toList());
    }

    /** What travels on the channel: {@code {"to": playerId, "msg": {"t": type, "d": data}}}. */
    static String envelope(long playerId, String type, JsonNode data) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("t", type);
        msg.set("d", data == null ? JSON.createObjectNode() : data);
        ObjectNode env = JSON.createObjectNode();
        env.put("to", playerId);
        env.set("msg", msg);
        return env.toString();
    }

    /** A message as a gateway receives it: whom it is for, and the text to write to them. */
    public record Delivery(long to, String type, String message) {

        /** @return the delivery, or null for anything that is not one. */
        public static Delivery parse(byte[] published) {
            try {
                JsonNode env = JSON.readTree(new String(published, StandardCharsets.UTF_8));
                JsonNode to = env == null ? null : env.get("to");
                JsonNode msg = env == null ? null : env.get("msg");
                if (to == null || !to.canConvertToLong() || msg == null || !msg.isObject()
                        || !msg.path("t").isTextual()) {
                    return null;
                }
                return new Delivery(to.asLong(), msg.path("t").asText(), msg.toString());
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }
}
