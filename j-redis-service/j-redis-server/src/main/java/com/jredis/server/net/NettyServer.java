package com.jredis.server.net;

import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Engine;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts connections and runs the per-connection pipeline:
 * {@code InboundCounter → RequestDecoder → ServerHandler}. Uses the native epoll transport on
 * Linux, NIO elsewhere (Windows has no native Netty transport), and Netty's in-VM transport for
 * embedded mode.
 */
public final class NettyServer {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final ServerConfig config;
    private final Engine engine;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Channel> listeners = new ArrayList<>();
    private EventLoopGroup boss;
    private EventLoopGroup workers;

    public NettyServer(ServerConfig config, Engine engine) {
        this.config = config;
        this.engine = engine;
    }

    private ChannelInitializer<Channel> initializer() {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast("count", new ServerHandler.InboundCounter(engine));
                ch.pipeline().addLast("decode", new RequestDecoder(config));
                ch.pipeline().addLast("engine", new ServerHandler(engine, connections));
            }
        };
    }

    /** Binds TCP listeners on every configured address. */
    public void startTcp() throws InterruptedException {
        boolean epoll = Epoll.isAvailable();
        Class<? extends ServerChannel> channelClass;
        if (epoll) {
            boss = new EpollEventLoopGroup(1, new DefaultThreadFactory("jredis-accept"));
            workers = new EpollEventLoopGroup(config.ioThreads(), new DefaultThreadFactory("jredis-io"));
            channelClass = EpollServerSocketChannel.class;
        } else {
            boss = new NioEventLoopGroup(1, new DefaultThreadFactory("jredis-accept"));
            workers = new NioEventLoopGroup(config.ioThreads(), new DefaultThreadFactory("jredis-io"));
            channelClass = NioServerSocketChannel.class;
        }
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, workers)
                .channel(channelClass)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)   // "printf 'PING\r\n' | nc -N" gets its reply
                .childOption(ChannelOption.SO_KEEPALIVE, config.tcpKeepalive() > 0)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(initializer());
        if (epoll && config.tcpKeepalive() > 0) {
            b.childOption(EpollChannelOption.TCP_KEEPIDLE, config.tcpKeepalive());
            b.childOption(EpollChannelOption.TCP_KEEPINTVL, Math.max(1, config.tcpKeepalive() / 3));
            b.childOption(EpollChannelOption.TCP_KEEPCNT, 3);
        }
        for (String addr : config.bind()) {
            String host = addr.equals("*") ? "0.0.0.0" : addr;
            Channel ch = b.bind(new InetSocketAddress(host, config.port())).sync().channel();
            listeners.add(ch);
            log.info("listening on {}:{} ({} transport)", host, port(ch), epoll ? "epoll" : "nio");
        }
    }

    /** Embedded mode: an in-VM listener; clients connect with the same name and no socket. */
    public void startLocal(String name) throws InterruptedException {
        boss = new DefaultEventLoopGroup(1, new DefaultThreadFactory("jredis-local-accept"));
        workers = new DefaultEventLoopGroup(Math.max(1, config.ioThreads()), new DefaultThreadFactory("jredis-local-io"));
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, workers)
                .channel(LocalServerChannel.class)
                .childHandler(initializer());
        listeners.add(b.bind(new LocalAddress(name)).sync().channel());
    }

    /** The first listener's actual port (useful with port 0), or -1 in local mode. */
    public int port() {
        return listeners.isEmpty() ? -1 : port(listeners.get(0));
    }

    private static int port(Channel ch) {
        return ch.localAddress() instanceof InetSocketAddress ? ((InetSocketAddress) ch.localAddress()).getPort() : -1;
    }

    /** Stops accepting new connections (existing ones stay open until the engine closes them). */
    public void stopAccepting() {
        for (Channel ch : listeners) {
            ch.close().syncUninterruptibly();
        }
        listeners.clear();
    }

    public void shutdown() {
        stopAccepting();
        if (boss != null) {
            boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
