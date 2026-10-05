package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;
import com.jredis.client.SetArgs;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strings, counters and TTLs: SET options, MSET/MGET, INCR family, EXPIRE/TTL/PERSIST. */
public final class StringsAndCounters implements Example {

    @Override
    public String name() {
        return "strings";
    }

    @Override
    public String summary() {
        return "SET options (NX/XX/EX), MSET/MGET, counters, TTLs";
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        JRedisSync redis = client.sync();
        redis.del("str:config", "str:a", "str:b", "str:c", "str:counter", "str:price", "str:temp");

        // SET NX: only if absent (returns false when the key exists). SET XX: only if present.
        out.println("SET NX (new)           -> " + redis.set("str:config", "v1", SetArgs.nx()));
        out.println("SET NX (exists)        -> " + redis.set("str:config", "v2", SetArgs.nx()));
        out.println("SET XX (exists)        -> " + redis.set("str:config", "v3", SetArgs.xx()));
        out.println("GET                    -> " + redis.get("str:config"));

        // Several keys in one round trip.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("str:a", "1");
        values.put("str:b", "2");
        redis.mset(values);
        out.println("MGET a b c             -> " + redis.mget("str:a", "str:b", "str:c"));   // c is null

        // Counters are atomic: safe from any number of threads and processes.
        out.println("INCR                   -> " + redis.incr("str:counter"));
        out.println("INCRBY 10              -> " + redis.incrBy("str:counter", 10));
        out.println("DECR                   -> " + redis.decr("str:counter"));
        out.println("INCRBYFLOAT 0.25       -> " + redis.incrByFloat("str:price", 0.25));

        // TTLs: the key disappears by itself.
        redis.set("str:temp", "short-lived", SetArgs.ex(60));
        out.println("TTL                    -> " + redis.ttl("str:temp") + " s");
        out.println("PERSIST                -> " + redis.persist("str:temp"));
        out.println("TTL after PERSIST      -> " + redis.ttl("str:temp") + " (-1 = no TTL)");
        out.println("EXPIRE 30              -> " + redis.expire("str:temp", 30));
        out.println("PTTL                   -> " + redis.pttl("str:temp") + " ms");
        out.println("TTL of a missing key   -> " + redis.ttl("str:nothing") + " (-2 = no such key)");
    }
}
