package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A player who moves between gateways — a phone going from Wi-Fi to mobile data lands on
 * whichever of the three it reaches — must stay findable at the new one. The old gateway
 * notices its half-dead socket late, and nothing it does then may undo the new registration.
 */
class ConnectionRegistryTest {

    private static final long ADA = 7;

    private JRedisEmbedded store;
    private JRedisClient client;
    private ConnectionRegistry g1;
    private ConnectionRegistry g2;

    @BeforeEach
    void setUp() {
        store = JRedisEmbedded.start();
        client = store.newClient();
        g1 = new ConnectionRegistry(client, "g1");
        g2 = new ConnectionRegistry(client, "g2");
    }

    @AfterEach
    void tearDown() {
        g1.close();
        g2.close();
        store.close();
    }

    private String entry() {
        return client.sync().get("conn:" + ADA);
    }

    /** Waits for anything the registry does in the background to have happened. */
    private static void settle() throws InterruptedException {
        Thread.sleep(400);
    }

    @Test
    @Timeout(20)
    @DisplayName("a refresh the store fails is counted and said, not lost in a future nobody reads (the gateway review)")
    void aFailedRefreshIsCounted() throws Exception {
        g1.register(ADA, new EmbeddedChannel(), "a-session").get(5, java.util.concurrent.TimeUnit.SECONDS);
        g1.refresh();
        settle();
        org.assertj.core.api.Assertions.assertThat(g1.refreshFailures()).as("refreshed").isZero();
        store.close();                                        // the store gone
        g1.refresh();
        long deadline = System.currentTimeMillis() + 10_000;
        while (g1.refreshFailures() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        org.assertj.core.api.Assertions.assertThat(g1.refreshFailures()).isEqualTo(1);
        store = JRedisEmbedded.start();                       // for tearDown
    }

    @Test
    @Timeout(20)
    @DisplayName("the old gateway's late cleanup leaves the new gateway's registration alone")
    void lateCleanupElsewhere() throws Exception {
        EmbeddedChannel old = new EmbeddedChannel();
        g1.register(ADA, old, "a-session");
        g2.register(ADA, new EmbeddedChannel(), "a-session");
        String atG2 = entry();
        assertThat(atG2).startsWith("g2");

        g1.unregister(ADA, old);              // g1 finally notices the dead socket
        settle();

        assertThat(entry()).isEqualTo(atG2);
    }

    @Test
    @Timeout(20)
    @DisplayName("a refresh from a gateway still holding a half-dead socket does not take the player back")
    void refreshDoesNotReclaim() throws Exception {
        g1.register(ADA, new EmbeddedChannel(), "a-session");   // looks alive to g1: nothing has told it otherwise
        g2.register(ADA, new EmbeddedChannel(), "a-session");
        String atG2 = entry();

        g1.refresh();
        settle();

        assertThat(entry()).isEqualTo(atG2);
    }

    @Test
    @Timeout(20)
    @DisplayName("a gateway shutting down does not erase players who have moved on")
    void shutdownLeavesOthersAlone() throws Exception {
        g1.register(ADA, new EmbeddedChannel(), "a-session");
        g2.register(ADA, new EmbeddedChannel(), "a-session");
        String atG2 = entry();

        g1.close();
        settle();

        assertThat(entry()).isEqualTo(atG2);
    }

    @Test
    @Timeout(20)
    @DisplayName("a player's own disconnect still removes their registration")
    void ownCleanupStillWorks() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        g1.register(ADA, ch, "a-session");
        assertThat(entry()).startsWith("g1");

        g1.unregister(ADA, ch);
        settle();

        assertThat(entry()).isNull();
        assertThat(g1.size()).isZero();
    }

    @Test
    @Timeout(20)
    @DisplayName("an entry the store lost is restored by the next refresh")
    void refreshRestoresALostEntry() throws Exception {
        g1.register(ADA, new EmbeddedChannel(), "a-session");
        client.sync().del("conn:" + ADA);

        g1.refresh();
        settle();

        assertThat(entry()).startsWith("g1");
    }

    @Test
    @Timeout(20)
    @DisplayName("a second login on one gateway tells the first connection why it is closed")
    void replacedConnectionIsTold() throws Exception {
        EmbeddedChannel first = new EmbeddedChannel();
        g1.register(ADA, first, "a-session");
        g1.register(ADA, new EmbeddedChannel(), "a-session");

        // A bare close looked like a network fault: the first device reconnected with its
        // still-valid token, displaced the second, which did the same - two devices evicting
        // each other for as long as both were on.
        io.netty.handler.codec.http.websocketx.TextWebSocketFrame told = first.readOutbound();
        org.assertj.core.api.Assertions.assertThat(told).as("a reason, not a bare close").isNotNull();
        org.assertj.core.api.Assertions.assertThat(told.text()).contains("\"t\":\"evt.session.replaced\"");
        told.release();
        org.assertj.core.api.Assertions.assertThat(first.isActive()).isFalse();
    }

    /** What the gateway's subscription hears on its channel, as GatewayServer listens. */
    private java.util.concurrent.LinkedBlockingQueue<byte[]> listen(String gateway) throws Exception {
        java.util.concurrent.LinkedBlockingQueue<byte[]> heard = new java.util.concurrent.LinkedBlockingQueue<>();
        client.pubSub().subscribe(com.backend.handoff.LobbyPush.channelOf(gateway), (ch, message) -> heard.add(message))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        return heard;
    }

    @Test
    @Timeout(20)
    @DisplayName("a second login through another gateway tells the first connection there why it is closed (T-60)")
    void replacedAcrossGateways() throws Exception {
        java.util.concurrent.LinkedBlockingQueue<byte[]> toG1 = listen("g1");
        EmbeddedChannel first = new EmbeddedChannel();
        g1.register(ADA, first, "a-session").get(5, java.util.concurrent.TimeUnit.SECONDS);
        g2.register(ADA, new EmbeddedChannel(), "a-session").get(5, java.util.concurrent.TimeUnit.SECONDS);

        byte[] heard = toG1.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(heard).as("g1 told its connection was replaced").isNotNull();
        assertThat(g1.deliver(com.backend.handoff.LobbyPush.Delivery.parse(heard))).isTrue();
        first.runPendingTasks();
        io.netty.handler.codec.http.websocketx.TextWebSocketFrame told = first.readOutbound();
        assertThat(told).as("a reason, not a bare close").isNotNull();
        assertThat(told.text()).isEqualTo(ConnectionRegistry.SESSION_REPLACED);
        told.release();
        assertThat(first.isActive()).isFalse();
        assertThat(entry()).as("the newest registration stays").startsWith("g2#");
    }

    @Test
    @Timeout(20)
    @DisplayName("a replacement told late leaves a newer connection at the old gateway alone (T-60)")
    void aLateReplacementSparesANewerConnection() throws Exception {
        java.util.concurrent.LinkedBlockingQueue<byte[]> toG1 = listen("g1");
        g1.register(ADA, new EmbeddedChannel(), "a-session").get(5, java.util.concurrent.TimeUnit.SECONDS);
        g2.register(ADA, new EmbeddedChannel(), "a-session").get(5, java.util.concurrent.TimeUnit.SECONDS);
        byte[] heard = toG1.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(heard).isNotNull();
        // Before the message is heard, the player comes back to g1: a connection newer than the one replaced.
        EmbeddedChannel newer = new EmbeddedChannel();
        g1.register(ADA, newer, "a-session").get(5, java.util.concurrent.TimeUnit.SECONDS);
        g1.deliver(com.backend.handoff.LobbyPush.Delivery.parse(heard));
        newer.runPendingTasks();
        assertThat((Object) newer.readOutbound()).as("told nothing").isNull();
        assertThat(newer.isActive()).as("still open").isTrue();
        assertThat(toG1.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                .as("a login on the gateway that already holds the player tells it nothing over the store").isNull();
    }
    @Test
    @Timeout(20)
    @DisplayName("a revocation whose session cannot be looked up closes the connection, as the push asks (T-71)")
    void aRevocationUncheckedCloses() throws Exception {
        io.netty.channel.DefaultEventLoopGroup loop = new io.netty.channel.DefaultEventLoopGroup(1);
        try {
            io.netty.channel.local.LocalAddress at = new io.netty.channel.local.LocalAddress("t71-" + System.nanoTime());
            new io.netty.bootstrap.ServerBootstrap().group(loop).channel(io.netty.channel.local.LocalServerChannel.class)
                    .childHandler(new io.netty.channel.ChannelInboundHandlerAdapter()).bind(at).sync();
            io.netty.channel.Channel ch = new io.netty.bootstrap.Bootstrap().group(loop)
                    .channel(io.netty.channel.local.LocalChannel.class)
                    .handler(new Pushes(new com.backend.common.Metrics.LabeledCounter(), new java.util.concurrent.atomic.LongAdder(),
                            new com.backend.common.Metrics.LabeledHistogram(Pushes.UNWRITABLE_BUCKETS)))
                    .connect(at).sync().channel();
            String session = new com.backend.handoff.SessionStore(client).create(ADA).get(5, java.util.concurrent.TimeUnit.SECONDS);
            g1.register(ADA, ch, session).get(5, java.util.concurrent.TimeUnit.SECONDS);
            store.close();                                    // the store gone: the session cannot be looked up
            assertThat(g1.deliver(new com.backend.handoff.LobbyPush.Delivery(ADA, ConnectionRegistry.SESSION_REVOKED,
                    "{\"t\":\"evt.session.revoked\",\"d\":{\"reason\":\"signed_out\"}}"))).isTrue();
            assertThat(ch.closeFuture().await(15, java.util.concurrent.TimeUnit.SECONDS)).as("closed").isTrue();
            store = JRedisEmbedded.start();                   // for tearDown
        } finally {
            loop.shutdownGracefully().sync();
        }
    }
}
