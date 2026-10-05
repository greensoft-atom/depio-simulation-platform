package com.backend.arena;

import java.net.InetSocketAddress;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.backend.common.Metrics;
import com.backend.handoff.MatchOutcome;
import com.backend.handoff.TicketStore;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The arena's TCP front end: raw framed TCP, no WebSocket and no HTTP upgrade, because
 * there is no browser client (D-1).
 *
 * Hosts several rooms, one thread each; which room a join lands in is the registry's
 * business, not this class's.
 */
public final class ArenaServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArenaServer.class);

    private static final int MAX_FRAME = 8 * 1024;
    private static final int WRITE_LOW_WATER = 32 * 1024;
    private static final int WRITE_HIGH_WATER = 128 * 1024;

    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final RoomRegistry registry;
    private final TicketStore tickets;
    private Channel serverChannel;
    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Null for plaintext: development, and tests that are not about TLS. */
    private ArenaTls tls;

    private MatchFrameHandler.Limits limits = MatchFrameHandler.Limits.DEFAULT;

    /** Connections the arena closed without the client asking, by reason; for the metrics. */
    private final Metrics.LabeledCounter dropped = new Metrics.LabeledCounter();
    /** How each join ended: joined, bad_ticket, no_room, claim_failed (04 §11). */
    private final Metrics.LabeledCounter joins = new Metrics.LabeledCounter();

    /**
     * Every connection speaks TLS, from its first byte (S-6). Call before {@link #start}. A
     * client that is not speaking TLS is dropped by the handshake before its bytes reach
     * the frame decoder.
     */
    public void useTls(ArenaTls tls) {
        this.tls = tls;
    }

    /** Whether clients must connect with TLS: what the directory tells platform, and platform the client. */
    public boolean tls() {
        return tls != null;
    }

    /** When the certificate clients are shown expires; null without TLS. */
    public Instant certificateExpiry() {
        return tls == null ? null : tls.expires();
    }

    /** Shorter limits, for tests that would otherwise wait half a minute. Call before {@link #start}. */
    void limits(MatchFrameHandler.Limits limits) {
        this.limits = limits;
    }

    public Metrics.LabeledCounter joins() {
        return joins;
    }

    public Metrics.LabeledCounter dropped() {
        return dropped;
    }

    public ArenaServer(TicketStore tickets, float mapSize, int capacity, int maxPlayers, int shapes,
                       int ioThreads, int maxRooms) {
        this(tickets, mapSize, capacity, maxPlayers, shapes, ioThreads, maxRooms,
                MatchRules.open("arena"), outcome -> { });
    }

    public ArenaServer(TicketStore tickets, float mapSize, int capacity, int maxPlayers, int shapes,
                       int ioThreads, int maxRooms, MatchRules rules,
                       Consumer<MatchOutcome> onMatchEnd) {
        this.tickets = tickets;
        this.registry = new RoomRegistry(mapSize, capacity, maxPlayers, shapes, maxRooms,
                rules, onMatchEnd);

        boolean epoll = Epoll.isAvailable();
        this.boss = epoll
                ? new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workers = epoll
                ? new MultiThreadIoEventLoopGroup(ioThreads, EpollIoHandler.newFactory())
                : new MultiThreadIoEventLoopGroup(ioThreads, NioIoHandler.newFactory());
        log.info("transport: {}", epoll ? "epoll" : "nio");
    }

    public RoomRegistry registry() {
        return registry;
    }

    /** Binds to loopback. Used by tests, which should not expose a port on a shared machine. */
    public int start(int port) throws InterruptedException {
        return start("127.0.0.1", port);
    }

    /**
     * @param bindHost the interface to listen on; {@code 0.0.0.0} for every one of them
     * @return the bound port, which matters when port 0 was requested
     */
    public int start(String bindHost, int port) throws InterruptedException {
        registry.startInitialRoom();

        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, workers)
                .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)      // Nagle would add up to 40 ms
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(WRITE_LOW_WATER, WRITE_HIGH_WATER))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (tls != null) {
                            ch.pipeline().addLast(tls.context().newHandler(ch.alloc()));
                        }
                        ch.pipeline()
                                .addLast(new VarintFrameDecoder(MAX_FRAME))
                                // After the decoder, so only whole frames count as activity:
                                // a client sending a frame a byte at a time is idle.
                                .addLast(new IdleStateHandler(limits.readerIdleMillis(), 0, 0,
                                        TimeUnit.MILLISECONDS))
                                .addLast(new MatchFrameHandler(registry, tickets, limits, dropped, joins));
                    }
                });

        serverChannel = b.bind(new InetSocketAddress(bindHost, port)).sync().channel();
        int bound = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("arena listening on {}:{} ({})", bindHost, bound, tls == null ? "plaintext" : "TLS");
        return bound;
    }

    /**
     * Stops accepting, lets every room publish what its players are owed, then drops the
     * connections — in that order. Rooms still write snapshots during their last tick, so the
     * connections have to outlive them; and nothing new may arrive while they finish.
     *
     * Returns once the rooms have published, so a caller closing the publisher next is not
     * racing a room that has not finished. Safe to call twice: the second call does nothing.
     * It used to go round again, and submitting to event loops already shutting down threw.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        registry.close();
        // Each room has just queued a kick for each of its players, on that player's event
        // loop (P-22). Shutting a loop down closes all its channels *before* it runs what is
        // queued (SingleThreadIoEventLoop: prepareToDestroy, then runAllTasks), so a kick
        // still queued when the shutdown began was dropped, and the player saw a bare close:
        // seen once, in a full build under load. A task submitted now runs after every kick,
        // so waiting for it lets them all out first. By construction, not by timing.
        for (EventExecutor loop : workers) {
            loop.submit(() -> { }).awaitUninterruptibly(1_000);
        }
        boss.shutdownGracefully();
        workers.shutdownGracefully();
    }
}
