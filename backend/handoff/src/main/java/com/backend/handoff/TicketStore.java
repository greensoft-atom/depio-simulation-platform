package com.backend.handoff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

/**
 * Writes and claims join tickets in j-redis (docs development/01-code-patterns.md §2.7).
 *
 * Both halves of the handoff live in one class on purpose. They are a contract between two
 * processes that are deployed independently, and two copies of the field names would drift
 * in a way nothing detects until a player cannot join.
 *
 * <h2>Why the claim is a transaction</h2>
 *
 * {@code HGETALL} followed by {@code DEL} is not single-use. Two connections replaying one
 * ticket can both read the hash before either deletes it, and both join. {@code MULTI} makes
 * the pair indivisible: the second claim's {@code HGETALL} sees nothing, so exactly one
 * caller can ever be handed the ticket. The {@code DEL} reply is not even read — its purpose
 * is to close the window, not to report anything.
 */
public final class TicketStore {

    /** Long enough to survive a slow client hop from the lobby (docs 04 §3). */
    public static final int TTL_SECONDS = 60;

    private static final String KEY_PREFIX = "ticket:";

    private final JRedisClient client;

    public TicketStore(JRedisClient client) {
        this.client = client;
    }

    /** Writes the ticket with its expiry. The pair is one transaction so no ticket outlives its TTL. */
    public CompletableFuture<Void> issue(Ticket ticket) {
        String key = KEY_PREFIX + ticket.id();
        Object[] hset = hsetArgs(key, ticket.fields());
        return client.multi()
                .send(hset)
                .send("EXPIRE", key, TTL_SECONDS)
                .exec()
                .thenAccept(TicketStore::failOnError);
    }

    /**
     * Claims a ticket, consuming it.
     *
     * @return the ticket, or null when the id is unknown, already used, expired or
     *         malformed. The caller cannot tell those apart, and should not: to an
     *         unauthenticated connection they are all "no".
     */
    public CompletableFuture<Ticket> claim(String ticketId) {
        if (ticketId == null || ticketId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String key = KEY_PREFIX + ticketId;
        return client.multi()
                .send("HGETALL", key)
                .send("DEL", key)
                .exec()
                .thenApply(replies -> {
                    failOnError(replies);
                    return Ticket.fromFields(ticketId, toMap(replies.get(0)));
                });
    }

    /** Revokes a ticket. @return true when it was there to revoke: not yet claimed, nor expired */
    public CompletableFuture<Boolean> revoke(String ticketId) {
        return client.del(KEY_PREFIX + ticketId).thenApply(n -> n == 1);
    }

    private static Object[] hsetArgs(String key, Map<String, String> fields) {
        Object[] args = new Object[2 + fields.size() * 2];
        args[0] = "HSET";
        args[1] = key;
        int i = 2;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            args[i++] = e.getKey();
            args[i++] = e.getValue();
        }
        return args;
    }

    /** HGETALL replies as a flat field, value, field, value array. */
    private static Map<String, String> toMap(Reply reply) {
        if (reply == null || reply.isNull() || reply.type() != Reply.Type.ARRAY) {
            return Map.of();
        }
        List<Reply> flat = reply.asList();
        Map<String, String> map = new HashMap<>(flat.size());
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            map.put(flat.get(i).asString(), flat.get(i + 1).asString());
        }
        return map;
    }

    /**
     * A queued command can fail on its own inside EXEC while the rest still run. Silently
     * treating that as "no ticket" would turn a store-side fault into a stream of joins
     * refused for no stated reason, so it is raised instead.
     */
    private static void failOnError(List<Reply> replies) {
        if (replies == null) {
            throw new IllegalStateException("transaction aborted");
        }
        for (Reply r : replies) {
            if (r.isError()) {
                throw new IllegalStateException("j-redis: " + r.asString());
            }
        }
    }
}
