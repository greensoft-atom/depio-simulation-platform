package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.handoff.StoreUnavailableException;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two login limits, against a real store, with the clock in the test's hands so a window
 * ends exactly when the test says rather than after a sleep.
 */
class LoginThrottleTest {

    private static JRedisEmbedded store;
    private static JRedisClient client;

    /** Ten seconds into a quarter hour, and so ten seconds into a minute too. */
    private final AtomicLong now =
            new AtomicLong(2_000_000L * LoginThrottle.ACCOUNT_WINDOW_MILLIS + 10_000);
    private LoginThrottle throttle;

    @BeforeAll
    static void setUp() {
        store = JRedisEmbedded.start();
        client = store.newClient();
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @BeforeEach
    void fresh() {
        client.sync().send("FLUSHALL");
        throttle = new LoginThrottle(client, now::get);
    }

    @Test
    @DisplayName("an address gets 30 attempts a minute, then waits for the minute to end")
    void perAddress() {
        for (int i = 0; i < LoginThrottle.ADDRESS_LIMIT; i++) {
            assertThat(throttle.attempt("203.0.113.7", null).allowed()).as("attempt %d", i + 1).isTrue();
        }
        LoginThrottle.Verdict refused = throttle.attempt("203.0.113.7", null);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(50);   // ten seconds into the minute

        assertThat(throttle.attempt("203.0.113.8", null).allowed()).as("a neighbour is its own count").isTrue();

        now.addAndGet(50_000);
        assertThat(throttle.attempt("203.0.113.7", null).allowed()).as("a new minute").isTrue();
    }

    @Test
    @DisplayName("an account gets 10 attempts a quarter hour, from however many addresses")
    void perAccount() {
        for (int i = 0; i < LoginThrottle.ACCOUNT_LIMIT; i++) {
            assertThat(throttle.attempt("198.51.100." + i, "ada").allowed()).isTrue();
        }
        LoginThrottle.Verdict refused = throttle.attempt("198.51.100.200", "ada");
        assertThat(refused.allowed()).as("a fresh address does not help").isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(15 * 60 - 10);

        assertThat(throttle.attempt("198.51.100.200", "bob").allowed()).as("another account").isTrue();

        now.addAndGet(15 * 60 * 1000L - 10_000);
        assertThat(throttle.attempt("198.51.100.200", "ada").allowed()).as("a new quarter hour").isTrue();
    }

    @Test
    @DisplayName("the keys expire, so a flood of addresses does not stay in the store")
    void keysExpire() {
        throttle.attempt("203.0.113.7", "ada");
        long addressTtl = client.sync().ttl("rl:login:addr:203.0.113.7:" + now.get() / LoginThrottle.ADDRESS_WINDOW_MILLIS);
        long accountTtl = client.sync().ttl("rl:login:acct:ada:" + now.get() / LoginThrottle.ACCOUNT_WINDOW_MILLIS);
        assertThat(addressTtl).isBetween(1L, 60L);
        assertThat(accountTtl).isBetween(61L, 900L);
    }

    @Test
    @DisplayName("IPv6 is counted per /64, the block one subscriber is given")
    void ipv6PerSlash64() throws Exception {
        String a = LoginThrottle.addressKey(InetAddress.getByName("2001:db8:1:2::1"));
        String b = LoginThrottle.addressKey(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        String c = LoginThrottle.addressKey(InetAddress.getByName("2001:db8:1:3::1"));
        assertThat(a).isEqualTo(b).isEqualTo("20010db800010002/64");
        assertThat(c).isNotEqualTo(a);
        assertThat(LoginThrottle.addressKey(InetAddress.getByName("::ffff:192.0.2.1")))
                .as("an IPv4-mapped address is the IPv4 address").isEqualTo("192.0.2.1");
    }

    @Test
    @DisplayName("a store that does not answer refuses the attempt rather than waving it through")
    void failsClosed() throws Exception {
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        try (JRedisClient down = JRedisClient.builder().address("127.0.0.1", deadPort)
                .clientName("down").build().start()) {
            LoginThrottle blind = new LoginThrottle(down, now::get);
            assertThatThrownBy(() -> blind.attempt("203.0.113.7", "ada"))
                    .isInstanceOf(StoreUnavailableException.class);
        }
    }
}
