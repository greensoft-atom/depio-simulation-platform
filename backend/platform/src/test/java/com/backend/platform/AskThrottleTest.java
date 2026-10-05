package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How often a player may ask others (Q-46, S-15), against a real store, on the test's clock. */
class AskThrottleTest {

    private static JRedisEmbedded store;
    private static JRedisClient client;

    /** Ten minutes into an hour. */
    private final AtomicLong now = new AtomicLong(500_000L * AskThrottle.WINDOW_MILLIS + 600_000);
    private AskThrottle throttle;

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
        throttle = new AskThrottle(client, now::get);
    }

    @Test
    @DisplayName("twenty asks an hour a player, each kind its own; the next hour twenty more")
    void twentyAnHour() {
        for (int i = 0; i < AskThrottle.PER_HOUR; i++) {
            assertThat(throttle.allow(AskThrottle.Kind.FRIEND_REQUEST, 7)).as("ask %d", i + 1).isTrue();
        }
        assertThat(throttle.allow(AskThrottle.Kind.FRIEND_REQUEST, 7)).isFalse();
        assertThat(throttle.allow(AskThrottle.Kind.FRIEND_REQUEST, 8)).as("another player").isTrue();
        assertThat(throttle.allow(AskThrottle.Kind.TEAM_INVITE, 7)).as("the other kind").isTrue();
        assertThat(client.sync().ttl("rl:ask:7:" + now.get() / AskThrottle.WINDOW_MILLIS))
                .as("the count goes with its hour").isBetween(1L, AskThrottle.WINDOW_MILLIS / 1_000);

        now.addAndGet(AskThrottle.WINDOW_MILLIS - 600_000);
        assertThat(throttle.allow(AskThrottle.Kind.FRIEND_REQUEST, 7)).as("the next hour").isTrue();
    }

    @Test
    @DisplayName("party invitations: sixty an hour a player, a party being made often to play (S-22)")
    void partyInvitationsAreCountedToo() {
        for (int i = 0; i < AskThrottle.PARTY_INVITES_PER_HOUR; i++) {
            assertThat(throttle.allow(AskThrottle.Kind.PARTY_INVITE, 7)).as("invitation %d", i + 1).isTrue();
        }
        assertThat(throttle.allow(AskThrottle.Kind.PARTY_INVITE, 7)).isFalse();
        assertThat(throttle.allow(AskThrottle.Kind.FRIEND_REQUEST, 7)).as("its own count").isTrue();
    }
}
