package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.backend.common.Metrics;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrameLimitTest {

    /** What reaches the handlers behind the limit. */
    private static final class Behind extends ChannelInboundHandlerAdapter {
        final List<Object> seen = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            seen.add(msg);
            ReferenceCountUtil.release(msg);
        }
    }

    private static WebSocketFrame[] frames(int n) {
        WebSocketFrame[] out = new WebSocketFrame[n];
        for (int i = 0; i < n; i++) {
            out[i] = i % 2 == 0 ? new TextWebSocketFrame("{\"t\":\"ping\"}") : new BinaryWebSocketFrame();
        }
        return out;
    }

    @Test
    @DisplayName("one refusal for a flood read at once, and nothing of it past the limit")
    void aFloodIsRefusedOnce() {
        Metrics.LabeledCounter errors = new Metrics.LabeledCounter();
        Behind behind = new Behind();
        EmbeddedChannel channel = new EmbeddedChannel(new FrameLimit(errors), behind);

        // Sixty frames in one read. The close did not stop the forty after the limit reaching
        // the handler, and each was refused, logged at INFO and counted again: measured, 40.
        channel.writeInbound((Object[]) frames(60));

        assertThat(behind.seen).as("text and binary alike count").hasSize(FrameLimit.MAX_FRAMES_PER_SECOND);
        assertThat(errors.get("rate_limited")).isEqualTo(1);
        TextWebSocketFrame told = channel.readOutbound();
        assertThat(told.text()).contains("rate_limited");
        told.release();
        // Then the protocol's close, and the connection kept, its input drained, until the client
        // answers: a close with the flood unread risks a reset, which a client can lose the
        // refusal to (P-31).
        CloseWebSocketFrame close = channel.readOutbound();
        assertThat(close.statusCode()).isEqualTo(1008);
        close.release();
        assertThat(channel.isActive()).as("lingering").isTrue();
        channel.writeInbound((Object[]) frames(30));
        assertThat(behind.seen).as("drained, and nothing passed on").hasSize(FrameLimit.MAX_FRAMES_PER_SECOND);
        assertThat(errors.get("rate_limited")).as("not refused again").isEqualTo(1);
        Object more = channel.readOutbound();
        assertThat(more).as("nothing more said").isNull();
        channel.writeInbound(new CloseWebSocketFrame(1000, "bye"));
        assertThat(channel.isActive()).as("the client's close answered by closing").isFalse();
    }

    @Test
    @DisplayName("a refused client that never answers the close is closed after the linger")
    void aSilentClientIsClosedLater() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameLimit(new Metrics.LabeledCounter()), new Behind());
        // The channel's clock is the real one plus what the test advances, unless frozen: a slow
        // thread's millisecond made the linger due a millisecond early (T-24).
        channel.freezeTime();
        channel.writeInbound((Object[]) frames(FrameLimit.MAX_FRAMES_PER_SECOND + 1));
        channel.advanceTimeBy(FrameLimit.LINGER_MILLIS - 1, java.util.concurrent.TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(channel.isActive()).isTrue();
        channel.advanceTimeBy(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(channel.isActive()).isFalse();
        channel.releaseOutbound();
    }

    @Test
    @DisplayName("the limit holds over any one second, not only over fixed ones")
    void theWindowSlides() throws Exception {
        Metrics.LabeledCounter errors = new Metrics.LabeledCounter();
        Behind behind = new Behind();
        EmbeddedChannel channel = new EmbeddedChannel(new FrameLimit(errors), behind);

        channel.writeInbound((Object[]) frames(FrameLimit.MAX_FRAMES_PER_SECOND));
        Thread.sleep(600);
        // Across a fixed window's boundary, another full window would have been let through.
        channel.writeInbound((Object[]) frames(1));
        assertThat(errors.get("rate_limited")).as("the 21st inside a second").isEqualTo(1);
        assertThat(behind.seen).hasSize(FrameLimit.MAX_FRAMES_PER_SECOND);
    }

    @Test
    @DisplayName("a client at the limit's pace is never refused")
    void aSteadyClientIsServed() throws Exception {
        Metrics.LabeledCounter errors = new Metrics.LabeledCounter();
        Behind behind = new Behind();
        EmbeddedChannel channel = new EmbeddedChannel(new FrameLimit(errors), behind);

        channel.writeInbound((Object[]) frames(FrameLimit.MAX_FRAMES_PER_SECOND));
        Thread.sleep(1_050);
        channel.writeInbound((Object[]) frames(FrameLimit.MAX_FRAMES_PER_SECOND));
        assertThat(channel.isActive()).isTrue();
        assertThat(behind.seen).hasSize(2 * FrameLimit.MAX_FRAMES_PER_SECOND);
        channel.close();
    }

    @Test
    @DisplayName("a log names the client as nginx saw it, the last X-Forwarded-For from a local peer only (the gateway review)")
    void aLogNamesTheClient() {
        var nginx = new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 40_000);
        var far = new java.net.InetSocketAddress("203.0.113.9", 40_000);
        org.assertj.core.api.Assertions.assertThat(FrameLimit.clientOf(nginx, "1.2.3.4, 198.51.100.7")).isEqualTo("198.51.100.7");
        org.assertj.core.api.Assertions.assertThat(FrameLimit.clientOf(nginx, "2001:db8::1")).isEqualTo("2001:db8::1");
        org.assertj.core.api.Assertions.assertThat(FrameLimit.clientOf(far, "198.51.100.7")).as("from afar, not trusted")
                .isEqualTo(String.valueOf(far));
        org.assertj.core.api.Assertions.assertThat(FrameLimit.clientOf(nginx, "<script>")).as("not an address")
                .isEqualTo(String.valueOf(nginx));
        org.assertj.core.api.Assertions.assertThat(FrameLimit.clientOf(nginx, null)).isEqualTo(String.valueOf(nginx));
    }
}
