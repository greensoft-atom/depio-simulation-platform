package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs every example against an embedded server, twice, so the examples can never rot. */
class ExamplesTest {

    private static JRedisEmbedded server;
    private static JRedisClient client;

    /** A line each example must print, proving it did what it claims. */
    private static final Map<String, String> EXPECTED = new HashMap<>();

    static {
        EXPECTED.put("quickstart", "INCR demo:visits       -> 2");
        EXPECTED.put("strings", "MGET a b c             -> [1, 2, null]");
        EXPECTED.put("cache", "after invalidation, database lookups: 2");
        EXPECTED.put("sessions", "resolve after logout   -> null");
        EXPECTED.put("leaderboard", "#5 user:carol   120   <- you");
        EXPECTED.put("ratelimit", "fixed window, 5/min    -> ok ok ok ok ok LIMITED LIMITED LIMITED");
        EXPECTED.put("lock", "worker 2 releases      -> false");
        EXPECTED.put("watch", "total=120");
        EXPECTED.put("token", "second redeem          -> null");
        EXPECTED.put("queue", "handled 3 job(s); queue length 0, processing length 0");
        EXPECTED.put("pubsub", "received: group:7 (group:*) <- meeting moved to 15:00");
        EXPECTED.put("registry", "live instances         -> [orders-1, orders-2]");
        EXPECTED.put("async", "first=1, last=10000");
        EXPECTED.put("scan", "keys matching scan:order:*  -> 500");
        EXPECTED.put("errors", "sync:  WRONGTYPE");
    }

    @BeforeAll
    static void start() {
        server = JRedisEmbedded.start(EmbeddedConfig.inMemory());
        client = server.newClient();
    }

    @AfterAll
    static void stop() {
        server.close();
        assertThat(server.fatalError()).isNull();
    }

    @Test
    void everyExampleRunsAndIsRepeatable() throws Exception {
        assertThat(EXPECTED.keySet()).hasSameSizeAs(Examples.ALL);
        for (int round = 0; round < 2; round++) {                   // the second run proves the clean-up
            for (Example e : Examples.ALL) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                e.run(client, new PrintStream(buf, true, "UTF-8"));
                String output = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                assertThat(output).as("output of example '%s'", e.name()).contains(EXPECTED.get(e.name()));
            }
        }
    }
}
