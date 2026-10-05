package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sessions against a real store.
 *
 * Lives here because two processes read these keys: {@code platform} writes one at login and
 * {@code gateway} checks one on every lobby connection.
 */
class SessionStoreTest {

    private static JRedisEmbedded server;
    private static JRedisClient client;
    private static SessionStore sessions;

    @BeforeAll
    static void setUp() {
        server = JRedisEmbedded.start();
        client = server.newClient();
        sessions = new SessionStore(client);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void clean() {
        client.sync().send("FLUSHALL");
    }

    @Test
    @DisplayName("a created session resolves back to its player")
    void createThenResolve() throws Exception {
        String token = sessions.create(4711).get(5, TimeUnit.SECONDS);

        assertThat(token).isNotBlank();
        assertThat(sessions.playerIdOf(token).get(5, TimeUnit.SECONDS)).isEqualTo(4711L);
        assertThat(sessions.fields(token).get(5, TimeUnit.SECONDS)).containsKey("playerId");
    }

    @Test
    @DisplayName("an unknown, empty or null token is nobody")
    void unknownTokens() throws Exception {
        assertThat(sessions.playerIdOf("not-a-token").get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(sessions.playerIdOf("").get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(sessions.playerIdOf(null).get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("a corrupt record is nobody, rather than an exception on every request")
    void corruptRecord() throws Exception {
        client.sync().hset("sess:broken", "playerId", "not-a-number");

        assertThat(sessions.playerIdOf("broken").get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("revoking ends it immediately")
    void revoke() throws Exception {
        String token = sessions.create(1).get(5, TimeUnit.SECONDS);

        assertThat(sessions.revoke(token).get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sessions.playerIdOf(token).get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(sessions.revoke(token).get(5, TimeUnit.SECONDS))
                .as("revoking twice is not an error").isFalse();
    }

    @Test
    @DisplayName("a player's sessions are revoked together, and nobody else's (04 §10)")
    void revokeAll() throws Exception {
        String first = sessions.create(7).get(5, TimeUnit.SECONDS);
        String second = sessions.create(7).get(5, TimeUnit.SECONDS);
        String other = sessions.create(8).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().ttl("sess:of:7")).as("kept as long as the longest session")
                .isGreaterThanOrEqualTo((long) (SessionStore.TTL_SECONDS * 1.1) - 5);

        assertThat(sessions.revokeAll(7).get(5, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(sessions.playerIdOf(first).get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(sessions.playerIdOf(second).get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(sessions.playerIdOf(other).get(5, TimeUnit.SECONDS)).isEqualTo(8L);
        assertThat(client.sync().exists("sess:of:7")).isZero();
        assertThat(sessions.revokeAll(7).get(5, TimeUnit.SECONDS)).as("none left").isZero();

        String logout = sessions.create(9).get(5, TimeUnit.SECONDS);
        sessions.revoke(logout).get(5, TimeUnit.SECONDS);
        assertThat(sessions.revokeAll(9).get(5, TimeUnit.SECONDS)).as("one ended already is not counted").isZero();
    }

    @Test
    @DisplayName("a token not as minted names no key: of:{id} neither ends a player's index nor reads it (S-20)")
    void aTokenNotAsMintedNamesNoKey() throws Exception {
        String live = sessions.create(42).get(5, TimeUnit.SECONDS);
        assertThat(live).matches("[A-Za-z0-9_-]{43}");

        assertThat(sessions.revoke("of:42").get(5, TimeUnit.SECONDS)).as("a logout with it").isFalse();
        assertThat(client.sync().exists("sess:of:42")).as("the index is whole").isEqualTo(1L);
        assertThat(sessions.playerIdOf("of:42").get(5, TimeUnit.SECONDS)).as("nor read as a session").isEqualTo(-1L);
        assertThat(sessions.playerIdOf(live + "x").get(5, TimeUnit.SECONDS)).as("too long").isEqualTo(-1L);
        assertThat(sessions.playerIdOf(live.substring(1)).get(5, TimeUnit.SECONDS)).as("too short").isEqualTo(-1L);
        assertThat(sessions.revokeAll(42).get(5, TimeUnit.SECONDS)).as("so a ban still ends it").isEqualTo(1);
    }

    @Test
    @DisplayName("tokens are unguessable and never repeat")
    void tokensAreUnique() throws Exception {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            tokens.add(sessions.create(1).get(5, TimeUnit.SECONDS));
        }

        // The token is the only thing between a stranger and an account for a day.
        assertThat(tokens).hasSize(200);
        assertThat(tokens).allSatisfy(t -> assertThat(t.length()).isGreaterThanOrEqualTo(43));
    }

    @Test
    @DisplayName("lifetimes are spread, so a launch does not become a login herd a day later")
    void ttlIsJittered() throws Exception {
        Set<Long> ttls = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            ttls.add(client.sync().ttl("sess:" + sessions.create(1).get(5, TimeUnit.SECONDS)));
        }

        assertThat(ttls).hasSizeGreaterThan(1);
        assertThat(ttls).allSatisfy(ttl -> assertThat(ttl)
                .isBetween((long) (SessionStore.TTL_SECONDS * 0.89),
                        (long) (SessionStore.TTL_SECONDS * 1.11)));
    }
}
