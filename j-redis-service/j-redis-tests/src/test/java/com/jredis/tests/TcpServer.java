package com.jredis.tests;

import com.jredis.server.JRedisServer;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Clock;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** A real TCP server on an ephemeral port (in memory unless configured otherwise). */
final class TcpServer implements AutoCloseable {

    final JRedisServer server;
    final ServerConfig config;
    final AtomicReference<String> fatal;

    private TcpServer(JRedisServer server, ServerConfig config, AtomicReference<String> fatal) {
        this.server = server;
        this.config = config;
        this.fatal = fatal;
    }

    static TcpServer start(Consumer<ServerConfig> customize) {
        ServerConfig c = new ServerConfig();
        c.port(0);
        c.appendonly(false);
        c.ioThreads(2);
        customize.accept(c);
        return start(c);
    }

    static TcpServer start(ServerConfig c) {
        return start(c, Clock.system());
    }

    static TcpServer start(ServerConfig c, Clock clock) {
        AtomicReference<String> fatal = new AtomicReference<>();
        try {
            JRedisServer s = JRedisServer.start(c, clock, (reason, cause, code) -> fatal.compareAndSet(null, reason), null);
            return new TcpServer(s, c, fatal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    int port() {
        return server.port();
    }

    RawClient connect() {
        return new RawClient(port());
    }

    @Override
    public void close() {
        server.stop();
    }
}
