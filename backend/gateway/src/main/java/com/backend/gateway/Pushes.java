package com.backend.gateway;

import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.backend.common.Metrics;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * A slow client's pushes (docs detailed-design/03-gateway.md §8): held while its connection
 * cannot take more, {@value #RING} at most; past that the backlog is dropped for one
 * {@code evt.resync}; and the connection is closed once it has been unwritable for
 * {@value #UNWRITABLE_CLOSE_SECONDS} s. One per connection, and event loop only:
 * {@link #offer} is called there.
 */
final class Pushes extends ChannelInboundHandlerAdapter {

    static final int RING = 16;
    static final int UNWRITABLE_CLOSE_SECONDS = 30;
    /** What replaces a backlog that overflowed: fetch the truth, as after a reconnect. */
    static final String RESYNC = "{\"t\":\"evt.resync\",\"d\":{}}";
    /** Seconds, the bounds of a spell unwritable: up to the 30 s that close it (03 §10). */
    static final double[] UNWRITABLE_BUCKETS = {0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, UNWRITABLE_CLOSE_SECONDS};

    private final Metrics.LabeledCounter counted;
    private final LongAdder slowClosed;
    private final Metrics.LabeledHistogram unwritable;
    private final ArrayDeque<String> held = new ArrayDeque<>(RING);
    private boolean resync;
    private ScheduledFuture<?> closing;
    /** When this spell unwritable began, by the loop's clock; -1 while the connection keeps up. */
    private long unwritableSince = -1;
    private ChannelHandlerContext ctx;

    Pushes(Metrics.LabeledCounter counted, LongAdder slowClosed, Metrics.LabeledHistogram unwritable) {
        this.counted = counted;
        this.slowClosed = slowClosed;
        this.unwritable = unwritable;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * A push for this connection. {@code closeAfter}, a revocation's: never held, since the
     * session is gone whatever the client hears; written and then closed, or closed at once.
     */
    void offer(String message, boolean closeAfter) {
        boolean writable = ctx.channel().isWritable();
        if (closeAfter) {
            if (writable) {
                ctx.writeAndFlush(new TextWebSocketFrame(message)).addListener(ChannelFutureListener.CLOSE);
            } else {
                ctx.close();
            }
            return;
        }
        if (writable) {
            ctx.writeAndFlush(new TextWebSocketFrame(message));
            return;
        }
        counted.increment("held");
        if (held.size() == RING) {
            held.poll();
            counted.increment("dropped");
            resync = true;
        }
        held.add(message);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            if (closing != null) {
                closing.cancel(false);
                closing = null;
            }
            spellEnded(ctx, "drained");
            if (resync) {
                for (int i = held.size(); i > 0; i--) {
                    counted.increment("dropped");
                }
                held.clear();
                resync = false;
                ctx.write(new TextWebSocketFrame(RESYNC));
            }
            while (!held.isEmpty()) {
                ctx.write(new TextWebSocketFrame(held.poll()));
            }
            ctx.flush();
        } else if (closing == null) {
            unwritableSince = ctx.executor().ticker().nanoTime();
            closing = ctx.executor().schedule(() -> {
                slowClosed.increment();
                ctx.close();
            }, UNWRITABLE_CLOSE_SECONDS, TimeUnit.SECONDS);
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (closing != null) {
            closing.cancel(false);
        }
        spellEnded(ctx, "closed");
        ctx.fireChannelInactive();
    }

    /** A spell unwritable over, timed by the loop's clock (03 §10); nothing if there was none. */
    private void spellEnded(ChannelHandlerContext ctx, String end) {
        if (unwritableSince >= 0) {
            unwritable.observe(end, (ctx.executor().ticker().nanoTime() - unwritableSince) / 1e9);
            unwritableSince = -1;
        }
    }
}
