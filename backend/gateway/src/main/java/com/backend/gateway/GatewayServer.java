package com.backend.gateway;

import java.net.InetSocketAddress;
import java.time.Duration;

import com.backend.common.Metrics;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.SessionStore;

import com.jredis.client.JRedisClient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lobby front end (docs detailed-design/03-gateway.md).
 *
 * WebSocket rather than the arena's raw TCP, because this traffic has to survive whatever a
 * phone is behind — captive portals, corporate proxies, and an nginx in front terminating
 * TLS. Match traffic pays none of that cost because it never comes this way
 * ([D-5](../../../../../../../../docs/architecture/03-decision-log.md)).
 *
 * It is stateless in the way that matters: losing this process costs its clients a
 * reconnect, not data. That is why it has no failover procedure.
 */
public final class GatewayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    /** A lobby message is a tap. Anything larger than this is not one. */
    private static final int MAX_FRAME_BYTES = 16 * 1024;

    /**
     * A silent socket is either gone or idle; either way it stops costing us after this.
     * {@link LobbyHandler} closes it; clients send {@code ping} at least every 30 s (03 §3).
     */
    private static final int READER_IDLE_SECONDS = 120;

    /**
     * {@code /lobby}, with a query or a path below it too. Matched exactly, {@code /lobby?v=2}
     * was not a WebSocket request, and nothing answered it.
     */
    private static final WebSocketServerProtocolConfig LOBBY = WebSocketServerProtocolConfig.newBuilder()
            .websocketPath("/lobby")
            .checkStartsWith(true)
            .allowExtensions(true)
            .maxFramePayloadLength(MAX_FRAME_BYTES)
            .build();

    /**
     * Any other request: answered 404 and closed. It used to be passed along to nothing, and
     * the connection sat unanswered until the idle limit closed it two minutes later.
     */
    private static final class NotFound extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(),
                    HttpResponseStatus.NOT_FOUND);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final SessionStore sessions;
    private final PlatformClient platform;
    private final ConnectionRegistry registry;
    private final JRedisClient store;
    private final java.util.concurrent.atomic.LongAdder resubscribed = new java.util.concurrent.atomic.LongAdder();
    private final String gatewayId;
    private Channel serverChannel;

    public GatewayServer(JRedisClient store, String platformBaseUrl, String gatewayId,
                         int ioThreads) {
        this.store = store;
        this.gatewayId = gatewayId;
        this.sessions = new SessionStore(store);
        this.platform = new PlatformClient(platformBaseUrl, Duration.ofSeconds(5));
        this.registry = new ConnectionRegistry(store, gatewayId);
        this.boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workers = new MultiThreadIoEventLoopGroup(ioThreads, NioIoHandler.newFactory());
    }

    public int start(String bindHost, int port) throws InterruptedException {
        registry.start();
        listenForPushes();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_FRAME_BYTES))
                                .addLast(new IdleStateHandler(READER_IDLE_SECONDS, 0, 0))
                                // Before the protocol handler, which answers pings itself.
                                .addLast(new FrameLimit(errors))
                                .addLast(new WebSocketServerProtocolHandler(LOBBY))
                                // A message may come in fragments; its first was read as the
                                // whole of it (bad_json) and the rest dropped.
                                .addLast(new WebSocketFrameAggregator(MAX_FRAME_BYTES))
                                .addLast(new Pushes(pushes, slowClosed, unwritable))
                                .addLast(new LobbyHandler(sessions, platform, registry, errors,
                                        authenticated))
                                .addLast(new NotFound());
                    }
                });
        serverChannel = b.bind(new InetSocketAddress(bindHost, port)).sync().channel();
        int bound = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("gateway listening on {}:{}/lobby", bindHost, bound);
        return bound;
    }

    /**
     * Subscribes to this gateway's own push channel and to {@code push:all} (03 §5), both before
     * either is waited for, so each is remembered and made again on reconnect whether or not the
     * store answers now (O-7). Not waited for past a few seconds: a push is at most once anyway.
     * After every reconnect, each lobby here is told to fetch what it missed (O-8).
     */
    private void listenForPushes() throws InterruptedException {
        String channel = LobbyPush.channelOf(gatewayId);
        com.jredis.client.JRedisPubSub pubSub = store.pubSub();
        pubSub.onReconnect(() -> {
            resubscribed.increment();
            log.info("the push channels were subscribed again; {} lobby connections told to fetch",
                    registry.resync());
        });
        java.util.concurrent.CompletableFuture<Void> own = pubSub.subscribe(channel, (ch, message) -> {
            LobbyPush.Delivery delivery = LobbyPush.Delivery.parse(message);
            if (delivery == null) {
                pushes.increment("malformed");
            } else {
                pushes.increment(registry.deliver(delivery) ? "delivered" : "not_here");
            }
        });
        java.util.concurrent.CompletableFuture<Void> all = pubSub.subscribe(LobbyPush.ALL_CHANNEL, (ch, message) -> {
            LobbyPush.Delivery delivery = LobbyPush.Delivery.parse(message);
            if (delivery == null) {
                pushes.increment("malformed");
            } else {
                registry.broadcast(delivery);
                pushes.increment("broadcast");
            }
        });
        try {
            java.util.concurrent.CompletableFuture.allOf(own, all).get(5, java.util.concurrent.TimeUnit.SECONDS);
            log.info("listening for pushes on {} and {}", channel, LobbyPush.ALL_CHANNEL);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            log.warn("the push channels {} and {} are not confirmed yet; they are made again on reconnect: {}",
                    channel, LobbyPush.ALL_CHANNEL, e.toString());
        }
    }

    /** Tests only: who is connected here. */
    ConnectionRegistry registry() {
        return registry;
    }

    /** Connections held right now. */
    public int connectionCount() {
        return registry.size();
    }

    private final Metrics.LabeledCounter errors = new Metrics.LabeledCounter();
    private final Metrics.LabeledCounter pushes = new Metrics.LabeledCounter();
    /** Connections closed for being unwritable for 30 s (03 §8). */
    private final java.util.concurrent.atomic.LongAdder slowClosed = new java.util.concurrent.atomic.LongAdder();
    /** Each spell a lobby connection could take no more, by how it ended (03 §10). */
    private final Metrics.LabeledHistogram unwritable = new Metrics.LabeledHistogram(Pushes.UNWRITABLE_BUCKETS);
    private final java.util.concurrent.atomic.LongAdder authenticated =
            new java.util.concurrent.atomic.LongAdder();

    /** What the gateway reports (03 §10). */
    public void registerMetrics(Metrics m) {
        m.gauge("backend_gateway_connections", "Lobby connections held.", this::connectionCount);
        m.counter("backend_gateway_authenticated_total", "Successful lobby authentications.",
                authenticated::sum);
        m.counter("backend_gateway_registry_refresh_failures_total",
                "Registrations (conn:{playerId}) a 20 s refresh could not renew: lapsed, their players' pushes are lost.",
                registry::refreshFailures);
        m.labeledCounter("backend_gateway_errors_total",
                "Errors sent to clients, by code: invalid_session, rate_limited, internal, ...",
                "code", errors);
        m.labeledCounter("backend_gateway_pushes_total",
                "Pushes received for this gateway, by outcome: delivered, not_here, malformed, broadcast; and of"
                + " those delivered, held for a slow connection and dropped from an overflowing one.",
                "outcome", pushes);
        m.labeledHistogram("backend_gateway_platform_seconds",
                "Each call to platform, from its send to its answer or failure, by the API's path (03 §10).",
                "route", platform.latency);
        m.labeledHistogram("backend_gateway_unwritable_seconds",
                "Each spell a lobby connection could take no more, to its draining or its close (03 §10).",
                "end", unwritable);
        m.counter("backend_gateway_slow_closed_total",
                "Lobby connections closed for being unwritable for 30 s (03 §8).", slowClosed::sum);
        m.gauge("backend_gateway_store_subscribed",
                "1 while the store's subscriber connection is up: every push arrives on it (03 §5).",
                () -> store.pubSub().isConnected() ? 1 : 0);
        m.counter("backend_gateway_resubscribed_total",
                "Times the subscriptions were made again after the subscriber's connection was lost (03 §5).",
                resubscribed::sum);
    }

    @Override
    public void close() {
        registry.close();
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        boss.shutdownGracefully();
        workers.shutdownGracefully();
    }
}
