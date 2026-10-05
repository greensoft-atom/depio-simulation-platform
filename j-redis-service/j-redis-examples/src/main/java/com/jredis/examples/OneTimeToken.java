package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A token that one service issues and exactly one redeemer may consume (sign-in links, checkout
 * hand-offs, connection tickets). Issue: HSET + EXPIRE in one transaction, so the token never
 * exists without its TTL. Redeem: HGETALL + DEL in one transaction, so two redeemers can never both
 * succeed.
 */
public final class OneTimeToken implements Example {

    @Override
    public String name() {
        return "token";
    }

    @Override
    public String summary() {
        return "one-time tokens: issue with TTL, redeem exactly once";
    }

    String issue(JRedisClient client, String userId, String target, int ttlSeconds) {
        String id = UUID.randomUUID().toString();
        client.multi()
                .send("HSET", "token:" + id, "userId", userId, "target", target)
                .send("EXPIRE", "token:" + id, ttlSeconds)
                .exec().join();
        return id;
    }

    /** The token's fields, or null if it is unknown, expired or already redeemed. */
    Map<String, String> redeem(JRedisClient client, String id) {
        List<Reply> r = client.multi()
                .send("HGETALL", "token:" + id)
                .send("DEL", "token:" + id)
                .exec().join();
        if (r.get(1).asLong() == 0) {
            return null;                              // nothing was deleted: not ours to use
        }
        List<Reply> flat = r.get(0).asList();
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < flat.size(); i += 2) {
            fields.put(flat.get(i).asString(), flat.get(i + 1).asString());
        }
        return fields;
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        String id = issue(client, "user:42", "checkout:9001", 60);
        out.println("issued                 -> " + id);
        out.println("first redeem           -> " + redeem(client, id));
        out.println("second redeem          -> " + redeem(client, id) + " (already used)");
        out.println("unknown token          -> " + redeem(client, "no-such-token"));
    }
}
