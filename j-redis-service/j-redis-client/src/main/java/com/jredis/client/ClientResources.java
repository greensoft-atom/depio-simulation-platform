package com.jredis.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

/** What all connections of one client share: event loops, channel type, address, metrics. */
final class ClientResources {

    final ClientConfig config;
    final EventLoopGroup group;
    final boolean ownsGroup;
    final Class<? extends Channel> channelClass;
    final SocketAddress address;
    final ClientMetrics metrics = new ClientMetrics();
    /** The highest epoch any server has shown this client: no primary below it is used (D-37). */
    final java.util.concurrent.atomic.AtomicLong epochSeen = new java.util.concurrent.atomic.AtomicLong(-1);
    /** Every connection of the client not yet closed, so that one finding a newer primary can tell the rest (D-40). */
    final java.util.Set<Connection> connections = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @SuppressWarnings("unchecked")
    ClientResources(ClientConfig c) {
        this.config = c;
        if (c.localName != null) {
            this.address = new LocalAddress(c.localName);
            this.channelClass = LocalChannel.class;
            this.ownsGroup = c.eventLoopGroup == null;
            this.group = ownsGroup ? new DefaultEventLoopGroup(c.ioThreads, new DefaultThreadFactory("jredis-client")) : c.eventLoopGroup;
            return;
        }
        this.address = InetSocketAddress.createUnresolved(c.host, c.port);
        if (c.eventLoopGroup != null) {
            this.group = c.eventLoopGroup;
            this.ownsGroup = false;
            Class<? extends Channel> cls = NioSocketChannel.class;
            if (group.getClass().getName().contains("Epoll")) {
                try {
                    cls = (Class<? extends Channel>) Class.forName("io.netty.channel.epoll.EpollSocketChannel");
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("an epoll event loop group was given but EpollSocketChannel is not on the classpath", e);
                }
            }
            this.channelClass = cls;
        } else {
            this.group = new NioEventLoopGroup(c.ioThreads, new DefaultThreadFactory("jredis-client"));
            this.ownsGroup = true;
            this.channelClass = NioSocketChannel.class;
        }
    }

    Bootstrap bootstrap(ChannelHandler handler) {
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(channelClass)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.connectTimeoutMillis)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast("decode", new ReplyDecoder());
                        ch.pipeline().addLast("client", handler);
                    }
                });
        if (config.localName == null) {
            b.option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.SO_KEEPALIVE, true);
        }
        return b;
    }

    /** True if the calling thread is one of this client's event-loop threads. */
    boolean isOwnThread() {
        for (EventExecutor e : group) {
            if (e.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    void shutdown() {
        if (ownsGroup) {
            io.netty.util.concurrent.Future<?> done = group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            if (!isOwnThread()) {                 // closing from a callback must not wait for its own thread
                done.syncUninterruptibly();
            }
        }
    }
}
