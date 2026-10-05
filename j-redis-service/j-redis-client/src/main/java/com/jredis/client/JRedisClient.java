package com.jredis.client;

import com.jredis.common.Bytes;
import com.jredis.common.Reply;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * The j-redis client. Thread-safe; create one per process and share it.
 *
 * <pre>
 * JRedisClient client = JRedisClient.builder().address("127.0.0.1", 6379).clientName("orders-service").build().start();
 * client.get("sess:" + token).whenComplete((userId, err) -&gt; worker.post(...));   // non-blocking
 * String v = client.sync().get("k");                                            // blocking (not on latency-critical threads)
 * </pre>
 *
 * Blocking pops use {@link #blocking()}, pub/sub uses {@link #pubSub()}, transactions use
 * {@link #multi()}, and WATCH needs {@link #withLeasedConnection}: each has its own connection so
 * it can never stall or confuse ordinary traffic.
 */
public final class JRedisClient extends AsyncCommands implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JRedisClient.class);

    /** Builder with the defaults from docs/10 §12. */
    public static final class Builder {
        String host = "127.0.0.1";
        int port = 6379;
        String localName;
        String password;
        String clientName;
        EventLoopGroup eventLoopGroup;
        int ioThreads = 1;
        long connectTimeoutMillis = 2000;
        long commandTimeoutMillis = 2000;
        long reconnectMinMillis = 100;
        long reconnectMaxMillis = 5000;
        long pubsubPingMillis = 5000;
        int commandConnections = 1;
        int leasePoolMax = 4;
        Executor callbackExecutor;
        java.util.List<java.net.InetSocketAddress> addresses;

        public Builder address(String host, int port) {
            this.host = host;
            this.port = port;
            this.localName = null;
            this.addresses = null;
            return this;
        }

        /**
         * Several servers, {@code host:port} each: a primary and its replicas. Every connection
         * uses the primary with the highest epoch, and looks again after losing it or being told
         * {@code READONLY}; it never uses a primary whose epoch is lower than one it has seen
         * (docs/16-replication.md §10). With one address, the same as {@link #address}.
         */
        public Builder addresses(String... hostPorts) {
            if (hostPorts.length == 0) {
                throw new IllegalArgumentException("at least one address");
            }
            java.util.List<java.net.InetSocketAddress> list = new java.util.ArrayList<>();
            for (String a : hostPorts) {
                int colon = a.lastIndexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("expected host:port, got '" + a + "'");
                }
                list.add(java.net.InetSocketAddress.createUnresolved(a.substring(0, colon), Integer.parseInt(a.substring(colon + 1))));
            }
            address(list.get(0).getHostString(), list.get(0).getPort());
            this.addresses = list.size() > 1 ? java.util.List.copyOf(list) : null;
            return this;
        }

        /** Connect to an embedded server over Netty's in-VM transport (no socket). */
        public Builder localAddress(String name) {
            this.localName = name;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** Shown in CLIENT LIST; set it, it makes incidents readable. */
        public Builder clientName(String name) {
            this.clientName = name;
            return this;
        }

        /** Share the application's Netty event loops instead of creating one. */
        public Builder eventLoopGroup(EventLoopGroup group) {
            this.eventLoopGroup = group;
            return this;
        }

        public Builder ioThreads(int n) {
            this.ioThreads = n;
            return this;
        }

        public Builder connectTimeoutMillis(long ms) {
            this.connectTimeoutMillis = ms;
            return this;
        }

        public Builder commandTimeoutMillis(long ms) {
            this.commandTimeoutMillis = ms;
            return this;
        }

        /**
         * How often a subscriber's connection is pinged, so that one answering nothing is noticed
         * as a command's is, and the primary looked for again (docs/16-replication.md §10); 0, never.
         */
        public Builder pubsubPingMillis(long ms) {
            this.pubsubPingMillis = ms;
            return this;
        }

        public Builder reconnectBackoffMillis(long min, long max) {
            this.reconnectMinMillis = min;
            this.reconnectMaxMillis = max;
            return this;
        }

        /** Command connections (round-robin); 2 avoids one slow reply delaying the rest. */
        public Builder commandConnections(int n) {
            this.commandConnections = n;
            return this;
        }

        public Builder leasePoolMax(int n) {
            this.leasePoolMax = n;
            return this;
        }

        /** Complete futures on this executor instead of the event loop. */
        public Builder callbackExecutor(Executor executor) {
            this.callbackExecutor = executor;
            return this;
        }

        public JRedisClient build() {
            if (commandConnections < 1 || leasePoolMax < 1 || ioThreads < 1) {
                throw new IllegalArgumentException("connections, lease pool and io threads must be >= 1");
            }
            return new JRedisClient(new ClientConfig(this));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final ClientConfig config;
    private final ClientResources res;
    private final Connection[] connections;
    private final JRedisSync sync;
    private final LeasePool leases;
    private final Object lazyLock = new Object();
    private volatile JRedisPubSub pubSub;
    private volatile JRedisBlocking blocking;
    private volatile boolean closed;

    private JRedisClient(ClientConfig config) {
        this.config = config;
        this.res = new ClientResources(config);
        this.connections = new Connection[config.commandConnections];
        for (int i = 0; i < connections.length; i++) {
            connections[i] = new Connection(res, Connection.Mode.COMMAND, "cmd-" + i, null);
        }
        this.sync = new JRedisSync(this, res, config.commandTimeoutMillis);
        this.leases = new LeasePool(res);
    }

    /**
     * Connects now and waits up to the connect timeout. If the server is not reachable yet, logs a
     * warning and keeps retrying in the background; requests fail fast until connected.
     */
    public JRedisClient start() {
        for (Connection c : connections) {
            c.connect();                          // idempotent: calling start() twice is harmless
        }
        if (res.isOwnThread() || ThreadGuard.isNonBlocking()) {
            return this;                          // may not wait here; requests fail fast until connected
        }
        for (Connection c : connections) {
            if (!c.awaitReady(config.connectTimeoutMillis)) {
                log.warn("not connected to {} yet; retrying in the background", config.address());
                break;
            }
        }
        return this;
    }

    /** Waits until at least one command connection is ready. */
    public boolean awaitConnected(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        for (Connection c : connections) {
            if (c.awaitReady(Math.max(1, deadline - System.currentTimeMillis()))) {
                return true;
            }
        }
        return false;
    }

    public boolean isConnected() {
        for (Connection c : connections) {
            if (c.isReady()) {
                return true;
            }
        }
        return false;
    }

    public ClientMetrics metrics() {
        return res.metrics;
    }

    /** The blocking facade. Never call it on a latency-critical thread or a client event-loop thread. */
    public JRedisSync sync() {
        return sync;
    }

    @Override
    protected CompletableFuture<Reply> execute(byte[][] argv) {
        rejectOnSharedConnection(argv);
        return pick().send(argv, config.commandTimeoutMillis, Connection.KIND_USER);
    }

    /**
     * Each thread is pinned to one command connection, so the commands one thread sends execute in
     * the order it sent them (the server answers each connection in order); threads spread across
     * the connections. A thread moves to another connection only while its own is down.
     */
    private Connection pick() {
        int n = connections.length;
        int home = (int) (Thread.currentThread().threadId() % n);
        for (int i = 0; i < n; i++) {
            Connection c = connections[(home + i) % n];
            if (c.isReady()) {
                return c;
            }
        }
        return connections[home];                 // not connected: fails fast with a clear error
    }

    static void rejectOnSharedConnection(byte[][] argv) {
        if (argv.length == 0) {
            throw new IllegalArgumentException("empty command");
        }
        String name = Bytes.upperAscii(argv[0]);
        switch (name) {
            case "BLPOP": case "BRPOP": case "BLMOVE": case "BRPOPLPUSH": case "BZPOPMIN": case "BZPOPMAX":
                throw new IllegalArgumentException(name + " would stall the shared connection: use client.blocking()");
            case "SUBSCRIBE": case "PSUBSCRIBE": case "UNSUBSCRIBE": case "PUNSUBSCRIBE":
                throw new IllegalArgumentException(name + " changes the connection's mode: use client.pubSub()");
            case "MULTI": case "EXEC": case "DISCARD":
                throw new IllegalArgumentException(name + ": use client.multi() so the transaction is written atomically");
            case "WATCH": case "UNWATCH":
                throw new IllegalArgumentException(name + " needs an exclusive connection: use client.withLeasedConnection()");
            case "QUIT": case "RESET": case "SELECT": case "AUTH": case "HELLO":
                throw new IllegalArgumentException(name + " would change shared connection state");
            case "XREAD": case "XREADGROUP":
                // The options before STREAMS; a group or consumer called "block" is not one.
                for (int i = name.equals("XREAD") ? 1 : 4; i < argv.length; i++) {
                    String a = Bytes.upperAscii(argv[i]);
                    if (a.equals("STREAMS")) {
                        break;
                    }
                    if (a.equals("BLOCK")) {
                        throw new IllegalArgumentException(name + " with BLOCK would stall the shared connection: use client.blocking()");
                    }
                    if (a.equals("COUNT")) {
                        i++;
                    }
                }
                break;
            default:
        }
    }

    /** A transaction on a command connection: MULTI, the commands and EXEC are written atomically. */
    public Transaction multi() {
        return new Transaction(pick(), config.commandTimeoutMillis);
    }

    /** The pub/sub connection (created and connected on first use). */
    public JRedisPubSub pubSub() {
        JRedisPubSub p = pubSub;
        if (p == null) {
            synchronized (lazyLock) {
                checkOpen();
                if (pubSub == null) {
                    pubSub = new JRedisPubSub(res);
                }
                p = pubSub;
            }
        }
        return p;
    }

    /** The connection for blocking pops (created and connected on first use). */
    public JRedisBlocking blocking() {
        JRedisBlocking b = blocking;
        if (b == null) {
            synchronized (lazyLock) {
                checkOpen();
                if (blocking == null) {
                    blocking = new JRedisBlocking(res);
                }
                b = blocking;
            }
        }
        return b;
    }

    /**
     * Runs {@code work} with an exclusive connection (for WATCH + MULTI/EXEC). The connection is
     * reset (UNWATCH) and returned to a small pool afterwards. Blocking; not for latency-critical threads.
     */
    public <T> T withLeasedConnection(Function<LeasedConnection, T> work) {
        checkOpen();
        if (res.isOwnThread()) {
            throw new IllegalStateException("withLeasedConnection blocks: it cannot run on a client event-loop thread");
        }
        if (ThreadGuard.isNonBlocking()) {
            throw new IllegalStateException("withLeasedConnection blocks: it cannot run on a thread marked non-blocking");
        }
        return leases.run(work);
    }

    private void checkOpen() {
        if (closed) {
            throw new JRedisClosedException("client closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Connection c : connections) {
            c.close();
        }
        synchronized (lazyLock) {
            if (pubSub != null) {
                pubSub.close();
            }
            if (blocking != null) {
                blocking.close();
            }
        }
        leases.close();
        res.shutdown();
    }
}
