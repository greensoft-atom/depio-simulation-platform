package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import com.jredis.server.core.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How to test your own code that uses j-redis: start the real server in-process (no socket, no
 * installation), use a {@link ManualClock} to make TTLs deterministic, and give the code under test
 * an ordinary {@link JRedisClient}. Copy this pattern into your service's tests.
 */
class EmbeddedTestingExampleTest {

    /** The code under test: something an application would really contain. */
    static final class LoginAttempts {
        private final JRedisClient redis;

        LoginAttempts(JRedisClient redis) {
            this.redis = redis;
        }

        /** Records a failed login; true when the account must be locked (5 failures in 15 minutes). */
        boolean recordFailure(String user) {
            String key = "login-failures:" + user;
            long failures = redis.sync().incr(key);
            if (failures == 1) {
                redis.sync().expire(key, 15 * 60);
            }
            return failures >= 5;
        }

        void recordSuccess(String user) {
            redis.sync().del("login-failures:" + user);
        }
    }

    private final ManualClock clock = new ManualClock(1_767_225_600_000L);   // 2026-01-01T00:00:00Z
    private JRedisEmbedded server;
    private LoginAttempts attempts;

    @BeforeEach
    void startServer() {
        server = JRedisEmbedded.start(EmbeddedConfig.inMemory().clock(clock));
        attempts = new LoginAttempts(server.newClient());
    }

    @AfterEach
    void stopServer() {
        server.close();                                   // also closes the clients it created
    }

    @Test
    void locksAfterFiveFailures() {
        for (int i = 0; i < 4; i++) {
            assertThat(attempts.recordFailure("alice")).isFalse();
        }
        assertThat(attempts.recordFailure("alice")).isTrue();
    }

    @Test
    void failuresExpireAfterFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            attempts.recordFailure("bob");
        }
        clock.advance(15 * 60 * 1000 + 1);               // no sleeping: time is under the test's control
        assertThat(attempts.recordFailure("bob")).isFalse();
    }

    @Test
    void successResetsTheCounter() {
        for (int i = 0; i < 4; i++) {
            attempts.recordFailure("carol");
        }
        attempts.recordSuccess("carol");
        assertThat(attempts.recordFailure("carol")).isFalse();
    }

    @Test
    void theEmbeddedServerIsTheRealOne() {
        JRedisClient client = server.newClient();
        assertThat(client.sync().set("k", "v", SetArgs.px(500))).isTrue();
        clock.advance(499);
        assertThat(client.sync().get("k")).isEqualTo("v");
        clock.advance(1);
        assertThat(client.sync().get("k")).isNull();      // expired exactly at its TTL
    }
}
