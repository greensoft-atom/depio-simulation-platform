package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisServerException;
import com.jredis.client.JRedisSync;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import com.jredis.server.core.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

/** Base for command tests: an in-memory embedded server on a manual clock, and one client. */
abstract class EmbeddedTest {

    /** 2026-01-01T00:00:00Z. */
    static final long T0 = 1_767_225_600_000L;

    final ManualClock clock = new ManualClock(T0);
    JRedisEmbedded redis;
    JRedisClient client;
    JRedisSync sync;

    @BeforeEach
    void startServer() {
        redis = JRedisEmbedded.start(configure(EmbeddedConfig.inMemory().clock(clock).configure(c -> c.enableDebugCommand(true))));
        client = redis.newClient();
        sync = client.sync();
    }

    /** Override to change the server configuration. */
    EmbeddedConfig configure(EmbeddedConfig config) {
        return config;
    }

    @AfterEach
    void stopServer() {
        if (redis != null) {
            redis.close();
            assertThat(redis.fatalError()).as("fail-stop").isNull();
        }
    }

    /** Runs a redis-cli style command line; returns the reply rendered by {@link R}. */
    String call(String line) {
        try {
            return R.render(sync.send(R.args(line)));
        } catch (JRedisServerException e) {
            return "-" + e.getMessage();
        }
    }

    void expect(String line, String expected) {
        assertThat(call(line)).as(line).isEqualTo(expected);
    }

    void expectError(String line, String prefix) {
        assertThat(call(line)).as(line).startsWith("-" + prefix);
    }

    /** The elements of an array reply, sorted (for commands whose order is unspecified). */
    java.util.List<String> members(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (com.jredis.common.Reply r : sync.send(R.args(line)).asList()) {
            out.add(R.render(r));
        }
        java.util.Collections.sort(out);
        return out;
    }

    void advanceSeconds(long s) {
        clock.advance(s * 1000);
    }
}
