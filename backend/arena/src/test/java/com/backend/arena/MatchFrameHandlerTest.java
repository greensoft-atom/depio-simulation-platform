package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.backend.common.Metrics;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.protocol.ClientMessage;
import com.backend.protocol.Wire;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MatchFrameHandlerTest {

    @Test
    @Timeout(30)
    @DisplayName("a ticket claim is finished on the connection's own thread, never on the store's")
    void claimContinuesOnTheConnectionsThread() throws Exception {
        JRedisEmbedded store = JRedisEmbedded.start();
        RoomRegistry registry = new RoomRegistry(1_000f, 2_048, 10, 10, 1);
        registry.startInitialRoom();
        try {
            JRedisClient client = store.newClient();
            TicketStore tickets = new TicketStore(client);
            Ticket ticket = Ticket.forPlayer(42, "ada", 0);
            tickets.issue(ticket).get(5, TimeUnit.SECONDS);

            // An embedded channel's loop runs only when the test says so, which makes "on
            // which thread did the join continue" something a test can see.
            EmbeddedChannel channel = new EmbeddedChannel(new MatchFrameHandler(registry, tickets));
            channel.writeInbound(Unpooled.wrappedBuffer(join(ticket.id())));

            // The claim's reply arrives on the store client's thread: once the ticket is gone,
            // the claim has completed there.
            waitUntil(() -> client.sync().exists("ticket:" + ticket.id()) == 0);
            Thread.sleep(300);
            // Continued on the store's thread, the join would already have allocated a room
            // and joined it — holding the one thread every other store call in the process
            // waits on, for as long as allocating takes (7-20 ms, 100 ms for a first room).
            assertThat(registry.totalPlayers())
                    .as("nothing may happen until the connection's own thread runs it").isZero();

            channel.runPendingTasks();
            waitUntil(() -> registry.totalPlayers() == 1);
        } finally {
            registry.close();
            store.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a claim the store could not answer is refused as the arena's fault, and counted apart (04 §11)")
    void aFailedClaimIsCounted() throws Exception {
        JRedisEmbedded store = JRedisEmbedded.start();
        JRedisClient client = store.newClient();
        TicketStore tickets = new TicketStore(client);
        client.close();                                   // every claim now fails
        store.close();
        com.backend.common.Metrics.LabeledCounter joins = new com.backend.common.Metrics.LabeledCounter();
        EmbeddedChannel channel = new EmbeddedChannel(new MatchFrameHandler(null, tickets,
                MatchFrameHandler.Limits.DEFAULT, new com.backend.common.Metrics.LabeledCounter(), joins));
        channel.writeInbound(Unpooled.wrappedBuffer(join(Ticket.newId())));
        for (int i = 0; i < 250 && joins.get("claim_failed") == 0; i++) {
            channel.runPendingTasks();
            Thread.sleep(20);
        }
        assertThat(joins.get("claim_failed")).isEqualTo(1);
        assertThat(joins.get("bad_ticket")).as("not the client's fault").isZero();
    }

    private static byte[] ping() {
        return new byte[] {(byte) ClientMessage.PING, 0, 0, 0, 1};
    }

    @Test
    @Timeout(30)
    @DisplayName("the backlog of a stalled uplink, arriving at once, is taken rather than kicked")
    void aStalledUplinksBacklogIsTaken() {
        EmbeddedChannel c = new EmbeddedChannel(new MatchFrameHandler(null, null));
        // A phone whose uplink stalled for most of the 30 s the arena waits: ten Input
        // packets a second and a Ping every ten, queued in its socket and delivered together
        // when the link comes back. Counted in one fixed second they were a flood: Kick(4),
        // "a client bug", its stay ended with no resume, for the lift ride §10 is built for.
        for (int i = 0; i < 300; i++) {
            c.writeInbound(Unpooled.wrappedBuffer(ping()));
        }
        assertThat(c.isActive()).as("still connected").isTrue();
        c.finishAndReleaseAll();
    }

    @Test
    @Timeout(30)
    @DisplayName("a flood is still kicked")
    void aFloodIsKicked() {
        EmbeddedChannel c = new EmbeddedChannel(new MatchFrameHandler(null, null));
        for (int i = 0; i < 2_000 && c.isActive(); i++) {
            c.writeInbound(Unpooled.wrappedBuffer(ping()));
        }
        assertThat(c.isActive()).isFalse();
        c.finishAndReleaseAll();
    }

    @Test
    @Timeout(30)
    @DisplayName("a connection that has not sent Join by the deadline is closed, and one that has is not")
    void joinDeadline() throws Exception {
        MatchFrameHandler.Limits limits = new MatchFrameHandler.Limits(1_000, 60_000, 60_000);
        Metrics.LabeledCounter dropped = new Metrics.LabeledCounter();

        EmbeddedChannel silent = new EmbeddedChannel(new MatchFrameHandler(null, null, limits, dropped, new com.backend.common.Metrics.LabeledCounter()));
        silent.freezeTime();                                    // the test's clock alone (T-24)
        silent.advanceTimeBy(999, TimeUnit.MILLISECONDS);
        silent.runScheduledPendingTasks();
        assertThat(silent.isActive()).as("not yet").isTrue();
        silent.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        silent.runScheduledPendingTasks();
        assertThat(silent.isActive()).isFalse();
        assertThat(dropped.get("join_deadline")).isEqualTo(1);

        JRedisEmbedded store = JRedisEmbedded.start();
        RoomRegistry registry = new RoomRegistry(1_000f, 2_048, 10, 10, 1);
        registry.startInitialRoom();
        try {
            TicketStore tickets = new TicketStore(store.newClient());
            Ticket ticket = Ticket.forPlayer(7, "ada", 0);
            tickets.issue(ticket).get(5, TimeUnit.SECONDS);
            EmbeddedChannel joined = new EmbeddedChannel(new MatchFrameHandler(registry, tickets, limits, dropped, new com.backend.common.Metrics.LabeledCounter()));
            joined.writeInbound(Unpooled.wrappedBuffer(join(ticket.id())));
            waitUntil(() -> {
                joined.runPendingTasks();
                return registry.totalPlayers() == 1;
            });

            joined.advanceTimeBy(5, TimeUnit.SECONDS);
            joined.runScheduledPendingTasks();
            assertThat(joined.isActive()).as("it joined in time").isTrue();
            assertThat(dropped.get("join_deadline")).isEqualTo(1);
        } finally {
            registry.close();
            store.close();
        }
    }

    @Test
    @DisplayName("only an unbroken unwritable spell as long as the limit closes a connection")
    void stalledConnection() {
        MatchFrameHandler.Limits limits = new MatchFrameHandler.Limits(60_000, 60_000, 5_000);
        Metrics.LabeledCounter dropped = new Metrics.LabeledCounter();
        EmbeddedChannel ch = new EmbeddedChannel(new MatchFrameHandler(null, null, limits, dropped, new com.backend.common.Metrics.LabeledCounter()));

        unwritable(ch, true);                                   // spell one, from t = 0
        ch.advanceTimeBy(4_000, TimeUnit.MILLISECONDS);
        unwritable(ch, false);                                  // drained at t = 4.0
        ch.advanceTimeBy(500, TimeUnit.MILLISECONDS);
        unwritable(ch, true);                                   // spell two, from t = 4.5
        ch.advanceTimeBy(1_000, TimeUnit.MILLISECONDS);
        ch.runScheduledPendingTasks();                          // spell one's check, at t = 5.0
        assertThat(ch.isActive()).as("five seconds since the first spell began, not of one spell").isTrue();

        ch.advanceTimeBy(3_900, TimeUnit.MILLISECONDS);         // t = 9.4
        ch.runScheduledPendingTasks();
        assertThat(ch.isActive()).isTrue();
        ch.advanceTimeBy(200, TimeUnit.MILLISECONDS);           // t = 9.6: spell two is 5.1 s old
        ch.runScheduledPendingTasks();
        assertThat(ch.isActive()).isFalse();
        assertThat(dropped.get("stalled")).isEqualTo(1);
    }

    /** The change is announced as a task on the channel's loop, so the loop is run once. */
    private static void unwritable(EmbeddedChannel ch, boolean unwritable) {
        ch.unsafe().outboundBuffer().setUserDefinedWritability(1, !unwritable);
        ch.runPendingTasks();
        assertThat(ch.isWritable()).isEqualTo(!unwritable);
    }

    @Test
    @DisplayName("a kick closes the connection even when the reason can never be written")
    void kickClosesAStalledPeer() {
        EmbeddedChannel healthy = new EmbeddedChannel();
        Frames.kick(healthy, Wire.KICK_RATE_LIMIT);
        ByteBuf sent = healthy.readOutbound();
        assertThat(new byte[] {sent.readByte(), sent.readByte(), sent.readByte()})
                .containsExactly(2, Wire.MSG_KICK, Wire.KICK_RATE_LIMIT);
        sent.release();
        assertThat(healthy.isActive()).as("closed as soon as the reason is written").isFalse();

        // A write that never completes: a client that has stopped reading, with every buffer
        // between here and it full.
        EmbeddedChannel stalled = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
            }
        });
        Frames.kick(stalled, Wire.KICK_RATE_LIMIT);
        stalled.runPendingTasks();
        assertThat(stalled.isActive()).as("still waiting for the reason to go out").isTrue();
        stalled.advanceTimeBy(Frames.KICK_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        stalled.runScheduledPendingTasks();
        assertThat(stalled.isActive()).isFalse();
    }

    @Test
    @DisplayName("a client of protocol 1 is told to update, joining or resuming, before its ticket is read")
    void anOldProtocolIsRefused() {
        // Version 2 carries a bullet's speed exactly, where 1 carried a table index (02 §4):
        // read by an old client, every bullet would fly at the wrong speed.
        for (int type : new int[] {ClientMessage.JOIN, ClientMessage.RESUME}) {
            EmbeddedChannel c = new EmbeddedChannel(new MatchFrameHandler(null, null));
            c.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) type, 1, 3, 'a', 'b', 'c'}));
            ByteBuf sent = c.readOutbound();
            assertThat(new byte[] {sent.readByte(), sent.readByte(), sent.readByte()})
                    .containsExactly(2, Wire.MSG_KICK, Wire.KICK_PROTOCOL_VERSION);
            sent.release();
            assertThat(c.isActive()).isFalse();
            c.finishAndReleaseAll();
        }
    }

    private static byte[] join(String ticketId) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(ClientMessage.JOIN);
        b.write(Wire.VERSION);
        byte[] id = ticketId.getBytes(StandardCharsets.US_ASCII);
        b.write(id.length);
        b.write(id, 0, id.length);
        return b.toByteArray();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.sleep(20);
        }
    }
}
