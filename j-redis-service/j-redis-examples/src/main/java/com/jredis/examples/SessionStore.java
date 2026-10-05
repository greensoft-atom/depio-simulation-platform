package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;
import com.jredis.client.SetArgs;
import com.jredis.common.Reply;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sessions: a random token maps to the user id with a TTL. Refresh the TTL only when less than half
 * of it remains (halves the write volume for active users). Session attributes live in a hash with
 * the same TTL, created atomically with MULTI.
 */
public final class SessionStore implements Example {

    private static final long SESSION_SECONDS = 24 * 3600;

    @Override
    public String name() {
        return "sessions";
    }

    @Override
    public String summary() {
        return "session tokens with sliding expiry, attributes in a hash";
    }

    /** Creates a session and returns its token. NX makes a token collision impossible to miss. */
    String create(JRedisClient client, String userId, Map<String, String> attributes) {
        String token = UUID.randomUUID().toString();
        List<Reply> r = client.multi()
                .send("SET", "sess:" + token, userId, "NX", "EX", SESSION_SECONDS)
                .send(hsetArgs("sess:" + token + ":attrs", attributes))
                .send("EXPIRE", "sess:" + token + ":attrs", SESSION_SECONDS)
                .exec().join();
        if (r.get(0).isNull()) {
            throw new IllegalStateException("token collision");
        }
        return token;
    }

    /** HSET key f1 v1 f2 v2 ... as one argument array. */
    private static Object[] hsetArgs(String key, Map<String, String> attributes) {
        Object[] a = new Object[2 + 2 * attributes.size()];
        a[0] = "HSET";
        a[1] = key;
        int i = 2;
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            a[i++] = e.getKey();
            a[i++] = e.getValue();
        }
        return a;
    }

    /** Resolves a token to a user id, refreshing the TTL when it is getting low. Null if unknown. */
    String resolve(JRedisSync redis, String token) {
        String userId = redis.get("sess:" + token);
        if (userId != null && redis.ttl("sess:" + token) < SESSION_SECONDS / 2) {
            redis.expire("sess:" + token, SESSION_SECONDS);
            redis.expire("sess:" + token + ":attrs", SESSION_SECONDS);
        }
        return userId;
    }

    void logout(JRedisSync redis, String token) {
        redis.del("sess:" + token, "sess:" + token + ":attrs");
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        JRedisSync redis = client.sync();
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("locale", "en");
        attrs.put("role", "member");
        String token = create(client, "user:42", attrs);
        out.println("created session        -> " + token);
        out.println("resolve                -> " + resolve(redis, token));
        out.println("attributes             -> " + redis.hgetall("sess:" + token + ":attrs"));
        out.println("TTL                    -> " + redis.ttl("sess:" + token) + " s");
        logout(redis, token);
        out.println("resolve after logout   -> " + resolve(redis, token));
        // The simplest possible session, if no attributes are needed:
        out.println("SET NX EX (one line)   -> " + redis.set("sess:simple", "user:7", SetArgs.nx().andEx(SESSION_SECONDS)));
        redis.del("sess:simple");
    }
}
