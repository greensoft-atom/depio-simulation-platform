package com.backend.handoff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which arenas are alive and how full they are
 * (docs detailed-design/04-platform-services.md §3).
 *
 * <h2>Liveness is a TTL, not a health check</h2>
 *
 * An arena writes its own entry every {@value #REFRESH_SECONDS} seconds with a
 * {@value #TTL_SECONDS}-second expiry. If the process dies, freezes, loses the network or is
 * simply too busy to heartbeat, the entry expires on its own and it stops receiving players.
 * Nothing has to notice the failure or agree that it happened, which is the property that
 * matters when the thing that failed may be the observer.
 *
 * The cost is bounded staleness: an arena can be dead for up to {@value #TTL_SECONDS}
 * seconds and still be handed a player. That player is refused a connection and re-queues,
 * which is the cheapest possible failure here.
 */
public final class ArenaDirectory {

    private static final Logger log = LoggerFactory.getLogger(ArenaDirectory.class);

    public static final int TTL_SECONDS = 10;
    public static final int REFRESH_SECONDS = 3;

    private static final String KEY_PREFIX = "arena:";
    private static final String INDEX_KEY = "arenas";
    private static final String PROMISED_PREFIX = "rooms:promised:";
    private static final String SEATS_PREFIX = "seats:promised:";
    /** A promise lasts as long as the tickets naming its match, or its seat: the room is made at the first claim. */
    private static final long PROMISE_MILLIS = java.util.concurrent.TimeUnit.SECONDS.toMillis(TicketStore.TTL_SECONDS);

    /**
     * Where an arena is, how full, and whether it speaks TLS — which a client has to know
     * before it opens the connection, and learns from its grant. And how many rooms it runs of
     * how many it may: a match's room is one more (04 §4).
     */
    public record Endpoint(String name, String host, int port, int players, int maxPlayers,
                           boolean tls, int rooms, int maxRooms) {
        public Endpoint(String name, String host, int port, int players, int maxPlayers) {
            this(name, host, port, players, maxPlayers, false, 0, 0);
        }

        public Endpoint(String name, String host, int port, int players, int maxPlayers,
                        boolean tls) {
            this(name, host, port, players, maxPlayers, tls, 0, 0);
        }

        public int free() {
            return maxPlayers - players;
        }

        public int freeRooms() {
            return maxRooms - rooms;
        }
    }

    private final JRedisClient client;

    public ArenaDirectory(JRedisClient client) {
        this.client = client;
    }

    /** Called by an arena on its own schedule. Re-adding to the index is deliberate: it is how a restarted arena reappears. */
    public CompletableFuture<Void> announce(Endpoint endpoint) {
        return announce(endpoint, null);
    }

    /** The same, with the arena's rooms as JSON, for an operator (04 §10). */
    public CompletableFuture<Void> announce(Endpoint endpoint, String roomList) {
        return announce(endpoint, roomList, List.of());
    }

    /**
     * The same, with the matches whose rooms are counted in it: their promises are dropped in
     * the same write, since each room counts itself from then on (D-42).
     */
    public CompletableFuture<Void> announce(Endpoint endpoint, String roomList, java.util.Collection<String> openMatches) {
        return announce(endpoint, roomList, openMatches, List.of());
    }

    /** The same, with the players counted in it whose seats were promised: dropped in the same write (D-79). */
    public CompletableFuture<Void> announce(Endpoint endpoint, String roomList, java.util.Collection<String> openMatches,
                                            java.util.Collection<Long> seated) {
        String key = KEY_PREFIX + endpoint.name();
        java.util.List<Object> fields = new java.util.ArrayList<>(java.util.List.of("HSET", key,
                "host", endpoint.host(),
                "port", Integer.toString(endpoint.port()),
                "players", Integer.toString(endpoint.players()),
                "maxPlayers", Integer.toString(endpoint.maxPlayers()),
                "tls", endpoint.tls() ? "1" : "0",
                "rooms", Integer.toString(endpoint.rooms()),
                "maxRooms", Integer.toString(endpoint.maxRooms())));
        if (roomList != null) {
            fields.addAll(java.util.List.of("roomList", roomList));
        }
        var write = client.multi()
                .send(fields.toArray())
                .send("EXPIRE", key, TTL_SECONDS)
                .send("SADD", INDEX_KEY, endpoint.name());
        if (!openMatches.isEmpty()) {
            List<Object> zrem = new ArrayList<>(List.of("ZREM", PROMISED_PREFIX + endpoint.name()));
            zrem.addAll(openMatches);
            write.send(zrem.toArray());
        }
        if (!seated.isEmpty()) {
            List<Object> zrem = new ArrayList<>(List.of("ZREM", SEATS_PREFIX + endpoint.name()));
            for (long player : seated) {
                zrem.add(Long.toString(player));
            }
            write.send(zrem.toArray());
        }
        return write.exec().thenAccept(replies -> { });
    }

    /** An arena's rooms as it last announced them, JSON; null when it has not, or is gone. */
    public String roomList(String arena) {
        return await(client.hget(KEY_PREFIX + arena, "roomList"));
    }

    /** The channel an arena hears an operator's commands on (04 §10). */
    public static String commandChannel(String arena) {
        return "arena-admin:" + arena;
    }

    /** Publishes an operator's command to an arena. @return how many heard it: 0 for an arena not listening */
    public CompletableFuture<Long> command(String arena, com.fasterxml.jackson.databind.JsonNode command) {
        return client.publish(commandChannel(arena), command.toString());
    }

    /** An arena hears its operator's commands, each a JSON text, on the store's own thread. */
    public CompletableFuture<Void> listen(String arena, java.util.function.Consumer<String> onCommand) {
        return client.pubSub().subscribe(commandChannel(arena),
                (channel, message) -> onCommand.accept(new String(message, java.nio.charset.StandardCharsets.UTF_8)));
    }

    public void stopListening(String arena) {
        client.pubSub().unsubscribe(commandChannel(arena));
    }

    /** Removes an arena immediately, for a clean shutdown rather than waiting out the TTL. */
    public CompletableFuture<Void> withdraw(String name) {
        return client.multi()
                .send("DEL", KEY_PREFIX + name)
                .send("SREM", INDEX_KEY, name)
                .exec()
                .thenAccept(replies -> { });
    }

    /**
     * @return every arena whose entry has not expired. Index members whose entry is gone are
     *         removed as they are found, so a crashed arena leaves nothing behind.
     */
    public List<Endpoint> live() {
        Set<String> names = await(client.smembers(INDEX_KEY));
        List<Endpoint> alive = new ArrayList<>(names.size());
        for (String name : names) {
            Map<String, String> fields = await(client.hgetall(KEY_PREFIX + name));
            if (fields == null || fields.isEmpty()) {
                client.srem(INDEX_KEY, name);            // expired: tidy the index
                continue;
            }
            try {
                alive.add(new Endpoint(name,
                        fields.get("host"),
                        Integer.parseInt(fields.get("port")),
                        Integer.parseInt(fields.get("players")),
                        Integer.parseInt(fields.get("maxPlayers")),
                        // Absent from an arena older than the field: it did not speak TLS.
                        "1".equals(fields.get("tls")),
                        // Absent from an arena older than matches: it has no room for one.
                        intOr(fields.get("rooms"), 0),
                        intOr(fields.get("maxRooms"), 0)));
            } catch (RuntimeException malformed) {
                log.warn("ignoring malformed arena entry {}: {}", name, malformed.toString());
            }
        }
        return alive;
    }

    /**
     * @return the arena with the most free capacity, or null if none has any.
     *
     * Free capacity rather than lowest ratio: the arenas are identical, so absolute room is
     * what decides whether the next hundred players fit. Less the seats promised and not yet
     * counted by the arena (D-79): counted only as announced, every 3 s, a burst of requests all
     * went to one arena (T-59).
     */
    public Endpoint pick() {
        long now = System.currentTimeMillis();
        Endpoint best = null;
        int bestFree = 0;
        for (Endpoint e : live()) {
            int free = e.free() - (int) (long) await(client.zcount(SEATS_PREFIX + e.name(), Long.toString(now), "+inf"));
            if (free > 0 && (best == null || free > bestFree)) {
                best = e;
                bestFree = free;
            }
        }
        return best;
    }

    /**
     * Promises a place on an arena to a player sent there (D-79), until the arena announces them
     * or the ticket lapses. By player, not ticket: a ticket's id is a credential, and a player
     * who asks twice holds one place.
     */
    public void promiseSeat(String arena, long playerId) {
        long now = System.currentTimeMillis();
        String key = SEATS_PREFIX + arena;
        await(client.multi()
                .send("ZREMRANGEBYSCORE", key, "-inf", Long.toString(now))
                .send("ZADD", key, Long.toString(now + PROMISE_MILLIS), Long.toString(playerId))
                .send("PEXPIRE", key, Long.toString(PROMISE_MILLIS))
                .exec());
    }

    /**
     * Chooses the arena for a match's room (04 §4) and promises the room to it (D-42).
     *
     * An arena's free rooms are counted less its promises still running, which the arena drops
     * as it announces each match's room; the most free rooms wins, between equals the most free
     * places. The promise is written, and the arena counted
     * again: if another chooser's promise has taken the room meanwhile, this one is given back
     * and the match waits, rather than being refused at the door. A room can still be taken
     * otherwise between an announcement and a claim, by the arena's own open rooms, say: that
     * match's players are refused (D-20).
     *
     * @return the arena, or null if none has a room free
     */
    public Endpoint reserveForMatch(String matchUid) {
        long now = System.currentTimeMillis();
        Endpoint best = null;
        int bestFree = 0;
        for (Endpoint e : live()) {
            int free = e.freeRooms() - promised(e.name(), now);
            if (free > 0 && (best == null || free > bestFree || (free == bestFree && e.free() > best.free()))) {
                best = e;
                bestFree = free;
            }
        }
        if (best == null) {
            return null;
        }
        // The count read and the promise written under WATCH, as one change: written first and counted after, two
        // choosers of the last room each counted the other's promise and both gave it back, neither match made that
        // round (the gateway review, 2026-10-04). Now one keeps it, and the other, its write overtaken, counts that
        // arena again: a room left, it promises one; none, its match waits a round.
        Endpoint chosen = best;
        String key = PROMISED_PREFIX + chosen.name();
        for (int attempt = 0; attempt < 5; attempt++) {
            Boolean kept = client.withLeasedConnection(conn -> {
                conn.sync().watch(key);
                long running = conn.sync().zcount(key, Long.toString(now), "+inf");
                if (chosen.freeRooms() - running <= 0) {
                    conn.sync().unwatch();
                    return false;
                }
                var tx = conn.multi()
                        .send("ZADD", key, Long.toString(now + PROMISE_MILLIS), matchUid)
                        .send("PEXPIRE", key, Long.toString(PROMISE_MILLIS));
                return conn.sync().exec(tx) == null ? null : true;
            });
            if (kept != null) {
                return kept ? chosen : null;
            }
        }
        return null;                                        // overtaken five times: the match waits a round
    }

    /** Gives a promise back: its match was made by another, or not at all. */
    public void release(String arena, String matchUid) {
        await(client.zrem(PROMISED_PREFIX + arena, matchUid));
    }

    /** Promises to this arena still running: rooms chosen for matches that it has not announced. */
    private int promised(String arena, long now) {
        String key = PROMISED_PREFIX + arena;
        await(client.zremrangebyscore(key, "-inf", Long.toString(now)));
        return (int) (long) await(client.zcard(key));
    }

    private static int intOr(String value, int absent) {
        return value == null ? absent : Integer.parseInt(value);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
