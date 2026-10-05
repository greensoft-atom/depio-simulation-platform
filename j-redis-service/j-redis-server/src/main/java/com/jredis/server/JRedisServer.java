package com.jredis.server;

import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Clock;
import com.jredis.server.core.Engine;
import com.jredis.server.core.FatalHandler;
import com.jredis.server.net.NettyServer;
import com.jredis.server.persist.AofPersistence;
import com.jredis.server.persist.DataDirLock;
import com.jredis.server.persist.DataDirLockedException;
import com.jredis.server.persist.DataLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running server: data loaded, command thread started, listener bound. Used by {@link Main}
 * and by the embedded mode.
 *
 * <p>Start-up order: lock the data directory → load data → start the AOF writer → start the
 * command thread → bind. The port opens only after loading, so no client ever sees a half-loaded
 * dataset.
 */
public final class JRedisServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JRedisServer.class);

    private final ServerConfig config;
    private final Engine engine;
    private final NettyServer net;
    private final DataDirLock lock;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();

    private JRedisServer(ServerConfig config, Engine engine, NettyServer net, DataDirLock lock) {
        this.config = config;
        this.engine = engine;
        this.net = net;
        this.lock = lock;
    }

    /** Production start: TCP listeners, system clock, halt the process on fail-stop. */
    public static JRedisServer start(ServerConfig config)
            throws IOException, DataDirLockedException, DataLoadException, InterruptedException {
        return start(config, Clock.system(), FatalHandlers.haltProcess(), null);
    }

    /**
     * General start.
     *
     * @param localName non-null for embedded mode: listen on Netty's in-VM transport under this
     *                  name instead of TCP
     */
    public static JRedisServer start(ServerConfig config, Clock clock, FatalHandler fatal, String localName)
            throws IOException, DataDirLockedException, DataLoadException, InterruptedException {
        Engine engine = new Engine(config, clock, fatal);
        DataDirLock lock = null;
        if (config.appendonly()) {
            lock = DataDirLock.acquire(Paths.get(config.dir()));
            try {
                engine.persistence(AofPersistence.open(engine, config));
            } catch (IOException | DataLoadException | RuntimeException e) {
                lock.close();
                throw e;
            }
        } else {
            log.info("appendonly no: running without persistence");
        }
        if (config.appendonly()) {
            try {
                engine.replication().loadEpoch(Paths.get(config.dir()));    // docs/16-replication.md §9
            } catch (IOException | RuntimeException e) {
                lock.close();
                throw new DataLoadException("cannot read the epoch in " + config.dir() + ": " + e, e);
            }
        }
        JRedisServer server = new JRedisServer(config, engine, new NettyServer(config, engine), lock);
        engine.addStopListener(server::onEngineStopped);
        engine.start("jredis-cmd");
        try {
            if (localName != null) {
                server.net.startLocal(localName);
            } else {
                server.net.startTcp();
                if (config.port() == 0) {
                    config.port(server.net.port());   // an ephemeral port: report the real one (INFO, CONFIG GET)
                }
            }
        } catch (Throwable e) {                  // Netty throws BindException undeclared: catch everything
            server.stop();
            throw e;
        }
        if (config.replicaofHost() != null) {             // docs/16-replication.md §9: the file decides the role
            engine.submit((Runnable) () -> engine.replication().replicaOf(config.replicaofHost(), config.replicaofPort()));
        }
        log.info("j-redis {} ready ({} keys)", Version.VERSION, engine.db().size());
        return server;
    }

    public Engine engine() {
        return engine;
    }

    public ServerConfig config() {
        return config;
    }

    /** The bound TCP port (useful when configured with port 0), or -1 in embedded mode. */
    public int port() {
        return net.port();
    }

    /** Runs on the command thread once its loop has ended (SHUTDOWN, stop(), or a fail-stop). */
    private void onEngineStopped() {
        Thread t = new Thread(this::releaseResources, "jredis-stopper");
        t.setDaemon(false);
        t.start();
    }

    private void releaseResources() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        if (engine.failed()) {                   // a fail-stop (embedded mode): the command thread did not close the AOF
            try {
                engine.persistence().shutdown();
            } catch (RuntimeException e) {
                log.warn("closing the AOF after the fail-stop failed: {}", e.toString());
            }
        }
        try {
            net.shutdown();
        } finally {
            if (lock != null) {
                lock.close();
            }
            log.info("j-redis stopped");
            stopped.countDown();
        }
    }

    /** Graceful stop from any thread; blocks until finished (at most about a minute). */
    public void stop() {
        if (stopping.compareAndSet(false, true)) {
            net.stopAccepting();
            if (engine.isRunning()) {
                engine.requestShutdown(false, null);
            } else {
                releaseResources();
            }
        }
        try {
            if (!stopped.await(60, TimeUnit.SECONDS)) {
                log.warn("server did not stop within 60 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitTermination() throws InterruptedException {
        stopped.await();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    @Override
    public void close() {
        stop();
    }
}
