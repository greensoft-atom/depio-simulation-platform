package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.SessionStore;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The two ways an asynchronous session lookup could leave the registry wrong.
 *
 * An embedded channel runs its queued tasks only when the test says so, so the lookup can be
 * made to finish at exactly the point the race needs: after both frames, or after the close.
 */
class LobbyHandlerRaceTest {

    private JRedisEmbedded store;
    private JRedisClient client;
    private SessionStore sessions;
    private ConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        store = JRedisEmbedded.start();
        client = store.newClient();
        sessions = new SessionStore(client);
        registry = new ConnectionRegistry(client, "gw-test");
    }

    @AfterEach
    void tearDown() {
        registry.close();
        store.close();
    }

    private EmbeddedChannel lobby() {
        return new EmbeddedChannel(new LobbyHandler(sessions,
                new PlatformClient("http://127.0.0.1:1", Duration.ofSeconds(1)), registry));
    }

    private static TextWebSocketFrame auth(String token, int id) {
        return new TextWebSocketFrame("{\"t\":\"auth\",\"id\":" + id + ",\"d\":{\"token\":\"" + token + "\"}}");
    }

    /** Lets the lookups finish on the store's thread, then runs what they queued here. */
    private static void settle(EmbeddedChannel channel) throws InterruptedException {
        Thread.sleep(300);
        channel.runPendingTasks();
    }

    @Test
    @Timeout(20)
    @DisplayName("two auth frames cannot make one connection two players")
    void secondAuthWhileTheFirstIsInFlight() throws Exception {
        String ada = sessions.create(1).get(5, TimeUnit.SECONDS);
        String bob = sessions.create(2).get(5, TimeUnit.SECONDS);
        EmbeddedChannel channel = lobby();

        // Both frames are read before either lookup's answer can run: one writeInbound call
        // reads both and only then runs queued tasks. Two calls made this a race the test
        // itself could lose, and it passed on the broken code once for that reason.
        channel.writeInbound(auth(ada, 1), auth(bob, 2));
        settle(channel);

        assertThat(registry.channelOf(1)).isSameAs(channel);
        assertThat(registry.channelOf(2)).as("one connection, one player").isNull();

        channel.close();
        settle(channel);
        assertThat(registry.size()).as("nothing left behind once it closes").isZero();
    }

    @Test
    @Timeout(20)
    @DisplayName("a connection that closes while its lookup is in flight is never registered")
    void closedBeforeTheLookupAnswers() throws Exception {
        String ada = sessions.create(1).get(5, TimeUnit.SECONDS);
        EmbeddedChannel channel = lobby();

        channel.writeInbound(auth(ada, 1));
        channel.close();                      // the phone lost signal
        settle(channel);

        // Registered after its own cleanup had run, it would stay in the map, and its conn:
        // entry would route this player's pushes to a dead socket for a minute.
        assertThat(registry.size()).isZero();
        assertThat(client.sync().exists("conn:1")).isZero();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a lobby connection silent for the idle limit is closed, and told why")
    void silentConnectionIsClosed() {
        EmbeddedChannel channel = lobby();
        // The pipeline's IdleStateHandler only raises this; something has to act on it. Nothing
        // did, so a socket connected straight to a gateway was never closed, and nginx's own
        // timeout (sized on the premise that the gateway decides) was the only one.
        channel.pipeline().fireUserEventTriggered(
                io.netty.handler.timeout.IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        Object told = channel.readOutbound();
        org.assertj.core.api.Assertions.assertThat(told)
                .isInstanceOf(io.netty.handler.codec.http.websocketx.CloseWebSocketFrame.class);
        org.assertj.core.api.Assertions.assertThat(
                ((io.netty.handler.codec.http.websocketx.CloseWebSocketFrame) told).reasonText()).isEqualTo("idle");
        ((io.netty.handler.codec.http.websocketx.CloseWebSocketFrame) told).release();
        org.assertj.core.api.Assertions.assertThat(channel.isActive()).isFalse();
    }
}
