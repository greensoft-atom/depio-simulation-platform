package com.jredis.embedded;

import com.jredis.client.JRedisClient;
import com.jredis.server.JRedisServer;
import com.jredis.server.core.Engine;
import com.jredis.server.persist.DataDirLockedException;
import com.jredis.server.persist.DataLoadException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The real server engine and the real client in one process, connected over Netty's in-VM
 * transport: no socket, identical code paths, identical replies. For tests and tools.
 *
 * <pre>
 * try (JRedisEmbedded redis = JRedisEmbedded.start(EmbeddedConfig.inMemory())) {
 *     JRedisClient client = redis.newClient();
 *     client.sync().set("k", "v");
 * }
 * </pre>
 */
public final class JRedisEmbedded implements AutoCloseable {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final JRedisServer server;
    private final String localName;
    private final List<JRedisClient> clients = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> fatal;

    private JRedisEmbedded(JRedisServer server, String localName, AtomicReference<String> fatal) {
        this.server = server;
        this.localName = localName;
        this.fatal = fatal;
    }

    public static JRedisEmbedded start() {
        return start(EmbeddedConfig.inMemory());
    }

    public static JRedisEmbedded start(EmbeddedConfig config) {
        String name = "jredis-embedded-" + SEQUENCE.incrementAndGet();
        AtomicReference<String> fatal = new AtomicReference<>();
        try {
            JRedisServer server = JRedisServer.start(config.server, config.clock,
                    (reason, cause, exitCode) -> fatal.compareAndSet(null, reason), name);
            return new JRedisEmbedded(server, name, fatal);
        } catch (IOException | DataDirLockedException | DataLoadException e) {
            throw new IllegalStateException("embedded j-redis failed to start: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting embedded j-redis", e);
        }
    }

    /** A connected client; closed together with this server. */
    public JRedisClient newClient() {
        return newClient(b -> { });
    }

    public JRedisClient newClient(Consumer<JRedisClient.Builder> customize) {
        JRedisClient.Builder b = JRedisClient.builder().localAddress(localName).clientName("embedded");
        customize.accept(b);
        b.localAddress(localName);
        JRedisClient c = b.build().start();
        clients.add(c);
        return c;
    }

    public String localName() {
        return localName;
    }

    public JRedisServer server() {
        return server;
    }

    /** The engine, for white-box tests. Touch it only through {@link Engine#submit}. */
    public Engine engine() {
        return server.engine();
    }

    /** The fail-stop reason if the engine stopped itself, otherwise null. */
    public String fatalError() {
        return fatal.get();
    }

    @Override
    public void close() {
        for (JRedisClient c : clients) {
            c.close();
        }
        server.stop();
    }
}
