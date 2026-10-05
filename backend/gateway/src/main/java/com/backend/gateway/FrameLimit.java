package com.backend.gateway;

import com.backend.common.Metrics;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At most {@value #MAX_FRAMES_PER_SECOND} WebSocket frames in any one second, of every kind
 * (03 §6). One per connection.
 *
 * In front of the WebSocket protocol handler, so it sees what that handler deals with itself:
 * pings, which it answers with a pong, and the fragments of a message, as well as whole text
 * and binary frames. Behind it, the limit counted whole text messages only, and a client could
 * send pings and binary frames without end.
 *
 * Exact over any window, not over fixed ones: the arrival of each of the last
 * {@value #MAX_FRAMES_PER_SECOND} frames is kept, and a frame is refused when the one that
 * many before it came less than a second ago. A fixed one-second window let twice the limit
 * through across its boundary.
 *
 * Once it has refused, it drops the rest of what was already read: a close does not stop the
 * frames decoded from the same read reaching the handlers, and each of them went on to be
 * refused, logged and counted again.
 *
 * <b>It closes as the protocol does</b> (P-31): the refusal, then a Close frame, 1008; then the
 * connection is kept, what arrives read and dropped, until the client's own Close, or for
 * {@value #LINGER_MILLIS} ms. Closed at once, with the flood unread, the socket could end in a
 * reset, and a bare end of stream is an error to a WebSocket client: one dropped the messages it
 * had not yet handed on, the refusal among them.
 */
final class FrameLimit extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FrameLimit.class);

    /** Lobby traffic is taps, not a stream. Anything above this is a broken or hostile client. */
    static final int MAX_FRAMES_PER_SECOND = 20;

    private static final long WINDOW_NANOS = 1_000_000_000L;

    /** How long a refused connection waits for the client's Close, its input drained. */
    static final long LINGER_MILLIS = 2_000;

    /** The rate_limited error, as LobbyHandler would write it: the connection is not told twice. */
    private static final String REFUSAL =
            "{\"t\":\"error\",\"d\":{\"code\":\"rate_limited\",\"message\":\"too many messages\"}}";

    /** The client's address, as nginx saw it, kept on the channel at the upgrade for the logs (the gateway review). */
    static final io.netty.util.AttributeKey<String> CLIENT = io.netty.util.AttributeKey.valueOf("backend.client");

    /**
     * Who to name in a log: the peer, unless it is on this machine, nginx, which reports the address it saw as the
     * last entry of X-Forwarded-For; only the last, every earlier one being the client's to choose. For the logs only:
     * nothing is decided by it here.
     */
    static String clientOf(java.net.SocketAddress peer, String forwardedFor) {
        if (peer instanceof java.net.InetSocketAddress at && at.getAddress() != null && at.getAddress().isLoopbackAddress()
                && forwardedFor != null) {
            String last = forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).trim();
            if (!last.isEmpty() && last.length() <= 45 && last.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':')) {
                return last;
            }
        }
        return String.valueOf(peer);
    }

    /** The name a log gives the channel's client. */
    static String clientOf(io.netty.channel.Channel channel) {
        String kept = channel.attr(CLIENT).get();
        return kept != null ? kept : String.valueOf(channel.remoteAddress());
    }

    private final long[] arrivals = new long[MAX_FRAMES_PER_SECOND];
    private int next;
    private long seen;
    private boolean refused;
    private final Metrics.LabeledCounter errors;

    FrameLimit(Metrics.LabeledCounter errors) {
        this.errors = errors;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof WebSocketFrame)) {
            if (msg instanceof io.netty.handler.codec.http.HttpRequest upgrade) {
                ctx.channel().attr(CLIENT).set(clientOf(ctx.channel().remoteAddress(),
                        upgrade.headers().get("X-Forwarded-For")));
            }
            ctx.fireChannelRead(msg);           // the HTTP upgrade, before any frame
            return;
        }
        if (refused) {
            boolean answered = msg instanceof CloseWebSocketFrame;
            ReferenceCountUtil.release(msg);
            if (answered) {
                ctx.close();                    // the client's close: the handshake is done
            }
            return;
        }
        long now = System.nanoTime();
        if (seen >= MAX_FRAMES_PER_SECOND && now - arrivals[next] < WINDOW_NANOS) {
            refused = true;
            ReferenceCountUtil.release(msg);
            errors.increment("rate_limited");
            log.info("rate limit exceeded, closing {}", clientOf(ctx.channel()));
            // From the channel's tail, through the WebSocket protocol handler, which records the Close: written past
            // it, straight to the encoder, replies and pushes still went out after the Close, and a later close sent
            // a second one (the gateway review, 2026-10-04; RFC 6455 §5.5.1).
            ctx.channel().write(new TextWebSocketFrame(REFUSAL));
            ctx.channel().writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.POLICY_VIOLATION, "too many messages"));
            ctx.executor().schedule(() -> ctx.close(), LINGER_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        arrivals[next] = now;
        next = (next + 1) % MAX_FRAMES_PER_SECOND;
        seen++;
        ctx.fireChannelRead(msg);
    }
}
