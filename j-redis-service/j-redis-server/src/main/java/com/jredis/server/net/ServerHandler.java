package com.jredis.server.net;

import com.jredis.common.RespProtocolException;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Engine;
import com.jredis.server.core.Events;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.DecoderException;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Bridges one channel to the engine. Runs on a Netty I/O thread and never touches data. */
final class ServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);
    static final AttributeKey<ServerConnection> CONNECTION = AttributeKey.valueOf("jredis.connection");

    private final Engine engine;
    private final ServerConfig config;
    private final AtomicInteger connections;
    private ServerConnection conn;
    private boolean counted;

    ServerHandler(Engine engine, AtomicInteger connections) {
        this.engine = engine;
        this.config = engine.config();
        this.connections = connections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        counted = true;
        if (connections.incrementAndGet() > config.maxclients()) {
            engine.stats().rejectedConnections.incrementAndGet();
            reject(ctx, "-ERR max number of clients reached\r\n");
            return;
        }
        if (config.protectedMode() && config.requirepass().isEmpty() && !isLoopback(ctx.channel().remoteAddress())) {
            engine.stats().rejectedConnections.incrementAndGet();
            reject(ctx, "-DENIED j-redis is running in protected mode: no password is set and the connection does "
                    + "not come from the loopback interface. Set requirepass, bind only to trusted interfaces, or "
                    + "disable protected-mode if you know what you are doing.\r\n");
            return;
        }
        conn = new ServerConnection(ctx.channel(), engine.stats(), engine.clock());
        ctx.channel().attr(CONNECTION).set(conn);
        engine.submit(new Events.Connected(conn));
        ctx.fireChannelActive();
    }

    private static void reject(ChannelHandlerContext ctx, String message) {
        ctx.channel().config().setAutoRead(false);
        ctx.writeAndFlush(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)).addListener(ChannelFutureListener.CLOSE);
    }

    static boolean isLoopback(SocketAddress a) {
        if (a instanceof LocalAddress) {
            return true;
        }
        return a instanceof InetSocketAddress && ((InetSocketAddress) a).getAddress() != null
                && ((InetSocketAddress) a).getAddress().isLoopbackAddress();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (conn == null) {
            return;
        }
        byte[][] argv = (byte[][]) msg;
        conn.onQueued(Engine.argvBytes(argv));
        engine.submit(new Events.Command(conn, argv));
    }

    /** The client shut down its sending side (half-close): reply to what it sent, then close. */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof ChannelInputShutdownEvent && conn != null) {
            engine.submit(new Events.InputClosed(conn));
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (counted) {
            connections.decrementAndGet();
            counted = false;
        }
        if (conn != null) {
            engine.submit(new Events.Closed(conn));
            conn = null;
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof DecoderException && cause.getCause() instanceof RespProtocolException) {
            if (conn != null) {                                 // the decoder now drains and drops the rest
                engine.submit(new Events.ProtocolError(conn, cause.getCause().getMessage()));
            } else {
                ctx.close();
            }
            return;
        }
        if (cause instanceof IOException) {
            log.debug("connection error from {}: {}", ctx.channel().remoteAddress(), cause.toString());
        } else {
            log.warn("unexpected error on connection from {}", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }

    /** Counts inbound bytes for INFO before they reach the decoder. */
    static final class InboundCounter extends ChannelInboundHandlerAdapter {
        private final Engine engine;

        InboundCounter(Engine engine) {
            this.engine = engine;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                engine.stats().netInputBytes.addAndGet(((ByteBuf) msg).readableBytes());
                ServerConnection conn = ctx.channel().attr(CONNECTION).get();
                if (conn != null) {
                    conn.touchIo();                     // the idle timeout counts transfers, not just whole commands
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}
