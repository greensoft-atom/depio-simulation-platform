package com.jredis.server.net;

import com.jredis.server.core.Client;
import com.jredis.server.core.ClientOutput;
import com.jredis.server.core.Clock;
import com.jredis.server.core.Stats;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One network connection. Backpressure: when more than {@link #HIGH_COMMANDS} commands (or
 * {@link #HIGH_BYTES}) are queued but not yet executed, the socket stops being read and TCP flow
 * control slows the sender; reading resumes below the low-water marks. These atomics are the only
 * state shared between an I/O thread and the command thread.
 */
final class ServerConnection implements ClientOutput {

    static final int HIGH_COMMANDS = 10_000;
    static final int LOW_COMMANDS = 1_000;
    static final long HIGH_BYTES = 64L * 1024 * 1024;
    static final long LOW_BYTES = 8L * 1024 * 1024;

    private final Channel channel;
    private final Stats stats;
    private final String remote;
    private final String local;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong inFlightBytes = new AtomicLong();
    private final AtomicLong pendingOut = new AtomicLong();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final Clock clock;
    private volatile long lastIoMillis;
    private Client client;                       // command thread only

    ServerConnection(Channel channel, Stats stats, Clock clock) {
        this.channel = channel;
        this.stats = stats;
        this.clock = clock;
        this.lastIoMillis = clock.nowMillis();
        this.remote = format(channel.remoteAddress());
        this.local = format(channel.localAddress());
    }

    static String format(SocketAddress a) {
        if (a instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) a;
            String host = i.getAddress() != null ? i.getAddress().getHostAddress() : i.getHostString();
            return host + ":" + i.getPort();
        }
        return String.valueOf(a);
    }

    /** I/O thread: a decoded command is about to be queued. */
    void onQueued(long bytes) {
        int n = inFlight.incrementAndGet();
        long b = inFlightBytes.addAndGet(bytes);
        if ((n > HIGH_COMMANDS || b > HIGH_BYTES) && paused.compareAndSet(false, true)) {
            channel.config().setAutoRead(false);
            if (inFlight.get() < LOW_COMMANDS && inFlightBytes.get() < LOW_BYTES) {
                resume();                        // the command thread caught up meanwhile
            }
        }
    }

    @Override
    public void commandDone(long bytes) {
        int n = inFlight.decrementAndGet();
        long b = inFlightBytes.addAndGet(-bytes);
        if (paused.get() && n < LOW_COMMANDS && b < LOW_BYTES) {
            channel.eventLoop().execute(this::resume);
        }
    }

    private void resume() {
        if (paused.compareAndSet(true, false)) {
            channel.config().setAutoRead(true);
        }
    }

    @Override
    public void write(ByteBuf replies) {
        final int n = replies.readableBytes();
        pendingOut.addAndGet(n);
        stats.netOutputBytes.addAndGet(n);
        channel.writeAndFlush(replies).addListener(f -> {
            pendingOut.addAndGet(-n);
            lastIoMillis = clock.nowMillis();
        });
    }

    /** I/O thread: bytes arrived. */
    void touchIo() {
        lastIoMillis = clock.nowMillis();
    }

    @Override
    public long lastIoMillis() {
        return lastIoMillis;
    }

    @Override
    public void close() {
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void abort() {
        channel.close();
    }

    @Override
    public long pendingBytes() {
        return pendingOut.get();
    }

    @Override
    public String remoteAddress() {
        return remote;
    }

    @Override
    public String localAddress() {
        return local;
    }

    @Override
    public Client client() {
        return client;
    }

    @Override
    public void client(Client c) {
        this.client = c;
    }
}
