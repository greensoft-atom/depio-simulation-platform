package com.jredis.client;

import com.jredis.common.BuildVersion;
import com.jredis.common.Bytes;
import com.jredis.common.Reply;
import com.jredis.common.RespWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connection to the server. Requests are matched to replies by position: the server answers
 * each connection in request order, so the pending queue is a FIFO. That queue is touched only on
 * the channel's event loop, so it needs no locks. Requests sent from many threads within one
 * event-loop iteration are flushed together: automatic pipelining.
 */
final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    static final String LIB_VERSION = BuildVersion.VERSION;

    /** Host names are resolved here, never on an event loop (DNS can stall for seconds). */
    private static final Executor RESOLVER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jredis-resolver");
        t.setDaemon(true);
        return t;
    });

    enum Mode { COMMAND, PUBSUB, BLOCKING }

    private enum State { CONNECTING, READY, DISCONNECTED, CLOSED }

    static final int KIND_USER = 0;       // server error completes the future exceptionally
    static final int KIND_RAW = 1;        // complete with the reply as is, errors included
    static final int KIND_TX_PART = 2;    // +OK of MULTI / +QUEUED inside a transaction
    static final int KIND_TX_EXEC = 3;    // the EXEC reply

    /** Shared state of one MULTI … EXEC batch. */
    static final class TxState {
        String firstError;
    }

    static final class Pending {
        final CompletableFuture<Reply> future = new CompletableFuture<>();
        final String command;
        final long startNanos = System.nanoTime();
        final long deadlineNanos;
        final int kind;
        final TxState tx;
        boolean timedOut;

        Pending(String command, long timeoutMillis, int kind, TxState tx) {
            this.command = command;
            this.deadlineNanos = timeoutMillis <= 0 ? 0 : startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            this.kind = kind;
            this.tx = tx;
        }
    }

    /** Receives server pushes (pub/sub mode) and connection-ready notifications. */
    interface Listener {
        void onPush(List<Reply> push);

        void onReady(boolean reconnect);
    }

    private final ClientResources res;
    private final ClientConfig config;
    private final Mode mode;
    private final String label;
    private final Listener listener;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();   // event loop only
    private final Object stateMonitor = new Object();              // awaitReady waits on it
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Channel channel;
    private volatile State state = State.CONNECTING;
    private volatile int generation;                               // +1 on every (re)connect
    private volatile long epoch = -1;                              // the highest seen when it found its primary
    private long reconnectDelay;
    private boolean everReady;
    private boolean authFailed;
    private boolean flushScheduled;
    private int consecutiveTimeouts;
    private ScheduledFuture<?> timeoutTask;

    Connection(ClientResources res, Mode mode, String label, Listener listener) {
        this.res = res;
        this.config = res.config;
        this.mode = mode;
        this.label = label;
        this.listener = listener;
        res.connections.add(this);
    }

    // ================================================================== lifecycle

    /** Starts connecting (once; later calls do nothing) and keeps reconnecting until closed. */
    void connect() {
        if (started.compareAndSet(false, true)) {
            doConnect();
        }
    }

    boolean started() {
        return started.get();
    }

    /**
     * The highest epoch this client had seen when this connection found its primary; -1 for a
     * client given one address. Lower than the client's now: a primary since promoted is known.
     */
    long epoch() {
        return epoch;
    }

    /**
     * Reconnects, finding the primary again, if this connection found its primary under an
     * epoch lower than {@code newer}: another connection of the client has found a newer one,
     * and this one would learn of it only at its next write, refused and lost (D-40).
     */
    private void leaveIfOlderThan(long newer) {
        Channel ch = channel;
        if (ch != null && epoch < newer) {
            log.warn("[{}] a primary at epoch {} was found; looking again", label, newer);
            ch.close();
        }
    }

    /** Changes whenever the connection is re-established: server-side state such as WATCH is then gone. */
    int generation() {
        return generation;
    }

    private void doConnect() {
        synchronized (stateMonitor) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CONNECTING;
            stateMonitor.notifyAll();
        }
        if (config.addresses != null) {                  // several servers: the primary among them
            long known = res.epochSeen.get();
            CompletableFuture.supplyAsync(() -> PrimaryFinder.find(config.addresses, config.password,
                            (int) config.connectTimeoutMillis, res.epochSeen), RESOLVER)
                    .whenComplete((primary, err) -> {
                        if (err != null || primary == null) {
                            scheduleReconnect(err != null ? err : new JRedisConnectionException("no primary among " + config.address()));
                        } else {
                            epoch = res.epochSeen.get();
                            if (epoch > known) {
                                for (Connection other : res.connections) {
                                    other.leaveIfOlderThan(epoch);
                                }
                            }
                            connectTo(new InetSocketAddress(primary.getHostString(), primary.getPort()));
                        }
                    });
            return;
        }
        SocketAddress address = res.address;
        if (address instanceof InetSocketAddress && ((InetSocketAddress) address).isUnresolved()) {
            InetSocketAddress a = (InetSocketAddress) address;
            CompletableFuture.supplyAsync(() -> new InetSocketAddress(a.getHostString(), a.getPort()), RESOLVER)
                    .whenComplete((resolved, err) -> {
                        if (err != null || resolved.isUnresolved()) {
                            scheduleReconnect(err != null ? err : new java.net.UnknownHostException(a.getHostString()));
                        } else {
                            connectTo(resolved);
                        }
                    });
        } else {
            connectTo(address);
        }
    }

    private void connectTo(SocketAddress address) {
        res.bootstrap(new Handler()).connect(address).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                scheduleReconnect(f.cause());
            }
        });
    }

    /** Waits until the connection is ready now (not merely once in the past). */
    boolean awaitReady(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (stateMonitor) {
            while (state != State.READY) {
                long left = deadline - System.nanoTime();
                if (state == State.CLOSED || left <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(stateMonitor, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** CLOSED is final: nothing may bring a closed connection back. */
    private void setState(State s) {
        synchronized (stateMonitor) {
            if (state != State.CLOSED) {
                state = s;
            }
            stateMonitor.notifyAll();
        }
    }

    boolean isReady() {
        return state == State.READY;
    }

    void close() {
        synchronized (stateMonitor) {
            state = State.CLOSED;
            stateMonitor.notifyAll();
        }
        res.connections.remove(this);
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    private void scheduleReconnect(Throwable cause) {
        if (state == State.CLOSED) {
            return;
        }
        setState(State.DISCONNECTED);
        long delay = authFailed ? config.reconnectMaxMillis
                : reconnectDelay == 0 ? config.reconnectMinMillis : Math.min(config.reconnectMaxMillis, reconnectDelay * 2);
        reconnectDelay = delay;
        long jittered = (long) (delay * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
        if (cause != null) {
            if (delay == config.reconnectMinMillis) {
                log.warn("[{}] cannot connect to {}: {}; retrying", label, config.address(), cause.toString());
            } else {
                log.debug("[{}] cannot connect to {}: {}; retrying in {} ms", label, config.address(), cause.toString(), jittered);
            }
        }
        try {
            res.group.next().schedule(this::doConnect, jittered, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // the client's event loops are stopping: nothing to reconnect for
        }
    }

    private List<byte[][]> handshake() {
        List<byte[][]> cmds = new ArrayList<>();
        if (config.password != null && !config.password.isEmpty()) {
            cmds.add(argv("AUTH", "default", config.password));   // also accepted by a server without a password
        }
        if (config.clientName != null && !config.clientName.isEmpty()) {
            cmds.add(argv("CLIENT", "SETNAME", config.clientName + (mode == Mode.COMMAND ? "" : "-" + mode.name().toLowerCase())));
        }
        cmds.add(argv("CLIENT", "SETINFO", "LIB-NAME", "j-redis-client"));
        cmds.add(argv("CLIENT", "SETINFO", "LIB-VER", LIB_VERSION));
        return cmds;
    }

    private static byte[][] argv(String... parts) {
        byte[][] a = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            a[i] = parts[i].getBytes(StandardCharsets.UTF_8);
        }
        return a;
    }

    private void becomeReady(Channel ch) {
        synchronized (stateMonitor) {
            if (state == State.CLOSED) {              // closed while connecting
                ch.close();
                return;
            }
            generation++;
            state = State.READY;
            stateMonitor.notifyAll();
        }
        boolean reconnect = everReady;
        everReady = true;
        authFailed = false;
        reconnectDelay = 0;
        if (reconnect) {
            res.metrics.reconnects.incrementAndGet();
            log.info("[{}] reconnected to {}", label, config.address());
        }
        if (listener != null) {
            listener.onReady(reconnect);
        }
    }

    // ================================================================== sending

    private static String commandName(byte[][] argv) {
        return argv.length == 0 ? "?" : Bytes.upperAscii(argv[0]);
    }

    CompletableFuture<Reply> send(byte[][] argv, long timeoutMillis, int kind) {
        Pending p = new Pending(commandName(argv), timeoutMillis, kind, null);
        dispatch(Collections.singletonList(argv), Collections.singletonList(p));
        return p.future;
    }

    /** MULTI, the commands, EXEC — written in one event-loop task so nothing can interleave. */
    CompletableFuture<Reply> sendTransaction(List<byte[][]> commands, long timeoutMillis) {
        TxState tx = new TxState();
        List<byte[][]> all = new ArrayList<>(commands.size() + 2);
        List<Pending> ps = new ArrayList<>(commands.size() + 2);
        all.add(argv("MULTI"));
        ps.add(new Pending("MULTI", timeoutMillis, KIND_TX_PART, tx));
        for (byte[][] c : commands) {
            all.add(c);
            ps.add(new Pending(commandName(c), timeoutMillis, KIND_TX_PART, tx));
        }
        all.add(argv("EXEC"));
        Pending exec = new Pending("EXEC", timeoutMillis, KIND_TX_EXEC, tx);
        ps.add(exec);
        dispatch(all, ps);
        return exec.future;
    }

    /** Pub/sub commands: their acknowledgements arrive as pushes, so nothing is queued. */
    void sendNoReply(byte[][] argv) {
        Channel ch = channel;
        if (state != State.READY || ch == null) {
            return;                               // re-sent from onReady after reconnecting
        }
        ByteBuf buf = encode(ch, argv);
        runOnLoop(ch, () -> {
            if (ch.isActive()) {
                ch.writeAndFlush(buf, ch.voidPromise());
            } else {
                buf.release();
            }
        });
    }

    private void dispatch(List<byte[][]> commands, List<Pending> ps) {
        Channel ch = channel;
        if (state != State.READY || ch == null) {
            JRedisException e = state == State.CLOSED ? new JRedisClosedException("client closed")
                    : !started.get() ? new JRedisConnectionException("the client was never started: call start() first")
                    : new JRedisConnectionException("not connected to " + config.address());
            res.metrics.failedFast.addAndGet(ps.size());
            for (Pending p : ps) {
                p.future.completeExceptionally(e);
            }
            return;
        }
        List<ByteBuf> bufs = new ArrayList<>(commands.size());
        for (byte[][] c : commands) {
            bufs.add(encode(ch, c));
        }
        runOnLoop(ch, () -> write(ch, ps, bufs));
    }

    private static ByteBuf encode(Channel ch, byte[][] argv) {
        ByteBuf buf = ch.alloc().buffer(RespWriter.commandSize(argv));
        RespWriter.command(buf, argv);
        return buf;
    }

    private static void runOnLoop(Channel ch, Runnable r) {
        EventLoop loop = ch.eventLoop();
        if (loop.inEventLoop()) {
            r.run();
        } else {
            loop.execute(r);
        }
    }

    private void write(Channel ch, List<Pending> ps, List<ByteBuf> bufs) {
        if (ch != channel || !ch.isActive() || state != State.READY) {
            for (ByteBuf b : bufs) {
                b.release();
            }
            JRedisConnectionException e = new JRedisConnectionException("connection to " + config.address() + " lost before the request was sent");
            for (Pending p : ps) {
                p.future.completeExceptionally(e);
            }
            return;
        }
        for (int i = 0; i < ps.size(); i++) {
            pending.add(ps.get(i));
            ch.write(bufs.get(i), ch.voidPromise());
        }
        res.metrics.sent.addAndGet(ps.size());
        if (!flushScheduled) {
            flushScheduled = true;
            ch.eventLoop().execute(() -> {
                flushScheduled = false;
                ch.flush();
            });
        }
    }

    // ================================================================== replies and failures

    private void complete(Pending p, Reply r) {
        switch (p.kind) {
            case KIND_USER:
                if (r.isError()) {
                    res.metrics.serverErrors.incrementAndGet();
                    fail(p, new JRedisServerException(r.asString()));
                } else {
                    succeed(p, r);
                }
                return;
            case KIND_TX_PART:
                if (r.isError() && p.tx.firstError == null) {
                    p.tx.firstError = r.asString();
                }
                succeed(p, r);
                return;
            case KIND_TX_EXEC:
                if (r.isError()) {
                    res.metrics.serverErrors.incrementAndGet();
                    String first = p.tx.firstError;
                    fail(p, new JRedisServerException(r.asString() + (first != null ? " (first rejected command: " + first + ")" : "")));
                } else {
                    succeed(p, r);
                }
                return;
            default:
                succeed(p, r);
        }
    }

    private void succeed(Pending p, Reply r) {
        if (config.callbackExecutor != null) {
            config.callbackExecutor.execute(() -> p.future.complete(r));
        } else {
            p.future.complete(r);
        }
    }

    private void fail(Pending p, Throwable t) {
        if (config.callbackExecutor != null) {
            config.callbackExecutor.execute(() -> p.future.completeExceptionally(t));
        } else {
            p.future.completeExceptionally(t);
        }
    }

    private void failAll(JRedisException e) {
        Pending p;
        while ((p = pending.poll()) != null) {
            if (!p.timedOut) {
                fail(p, e);
            }
        }
    }

    /**
     * Every 10 ms on the event loop: expire overdue requests, detect a stuck connection. The futures
     * are failed only after the scan, because their callbacks may send (and so add to the queue),
     * and nothing may ever escape: an exception would silently cancel this periodic task.
     */
    private void checkTimeouts() {
        try {
            long now = System.nanoTime();
            Pending oldest = pending.peekFirst();
            List<Pending> expired = null;
            for (Pending p : pending) {
                if (p.deadlineNanos == 0 || p.timedOut) {
                    continue;
                }
                if (now - p.deadlineNanos >= 0) {
                    p.timedOut = true;
                    if (p.kind != KIND_TX_PART) {         // a transaction counts once, at its EXEC
                        consecutiveTimeouts++;
                        res.metrics.timeouts.incrementAndGet();
                    }
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(p);
                } else if (mode != Mode.BLOCKING) {
                    break;                                // deadlines are in order on a FIFO connection
                }
            }
            if (expired != null) {
                for (Pending p : expired) {
                    fail(p, new JRedisTimeoutException(p.command + " timed out after "
                            + TimeUnit.NANOSECONDS.toMillis(p.deadlineNanos - p.startNanos) + " ms; the outcome is unknown"));
                }
            }
            if (oldest != null && oldest.timedOut && oldest.deadlineNanos != 0
                    && (consecutiveTimeouts >= 5 || now - oldest.startNanos > TimeUnit.MILLISECONDS.toNanos(3 * config.commandTimeoutMillis))) {
                Channel ch = channel;
                if (ch != null) {
                    log.warn("[{}] connection to {} looks stuck ({} timeouts in a row); reconnecting", label, config.address(), consecutiveTimeouts);
                    ch.close();
                }
            }
        } catch (Throwable t) {
            log.error("[{}] timeout check failed", label, t);
        }
    }

    private static boolean isSubscriptionPush(Reply r) {
        if (r.type() != Reply.Type.ARRAY || r.asList().isEmpty()) {
            return false;
        }
        Reply first = r.asList().get(0);
        if (first.type() != Reply.Type.BULK) {
            return false;
        }
        String kind = first.asString();
        return kind.equals("message") || kind.equals("pmessage") || kind.equals("subscribe")
                || kind.equals("unsubscribe") || kind.equals("psubscribe") || kind.equals("punsubscribe");
    }

    private final class Handler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel ch = ctx.channel();
            Channel current = channel;
            if (state == State.CLOSED || (current != null && current != ch && current.isActive())) {
                ctx.close();                              // closed meanwhile, or a stray second channel
                return;
            }
            channel = ch;
            timeoutTask = ch.eventLoop().scheduleAtFixedRate(Connection.this::checkTimeouts, 10, 10, TimeUnit.MILLISECONDS);
            List<byte[][]> hs = handshake();
            List<CompletableFuture<Reply>> replies = new ArrayList<>();
            for (byte[][] cmd : hs) {
                Pending p = new Pending(commandName(cmd), config.connectTimeoutMillis, KIND_RAW, null);
                pending.add(p);
                ch.write(encode(ch, cmd), ch.voidPromise());
                replies.add(p.future);
            }
            ch.flush();
            CompletableFuture.allOf(replies.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) -> {
                if (err != null) {
                    ch.close();
                    return;
                }
                if (config.password != null && !config.password.isEmpty() && replies.get(0).join().isError()) {
                    authFailed = true;
                    log.error("[{}] authentication to {} failed: {}. Check the password; retrying slowly.",
                            label, config.address(), replies.get(0).join().asString());
                    ch.close();
                    return;
                }
                becomeReady(ch);
            });
            ctx.fireChannelActive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Reply r = (Reply) msg;
            if (mode == Mode.PUBSUB && listener != null && isSubscriptionPush(r)) {
                listener.onPush(r.asList());
                return;
            }
            Pending p = pending.poll();
            if (p == null) {
                log.error("[{}] reply without a pending request from {}; closing the connection", label, config.address());
                ctx.close();
                return;
            }
            consecutiveTimeouts = 0;
            res.metrics.received.incrementAndGet();
            if (p.timedOut) {
                return;                           // the late reply of a request that already timed out
            }
            res.metrics.record(p.command, (System.nanoTime() - p.startNanos) / 1000);
            complete(p, r);
            if (config.addresses != null && r.isError() && r.asString().startsWith("READONLY")) {
                log.warn("[{}] {} is no longer the primary; looking again", label, ctx.channel().remoteAddress());
                ctx.close();                              // demoted: the reconnection finds the primary now
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (ctx.channel() != channel) {               // never became this connection's channel
                ctx.fireChannelInactive();
                return;
            }
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            channel = null;
            boolean closed = state == State.CLOSED;
            failAll(closed ? new JRedisClosedException("client closed")
                    : new JRedisConnectionException("connection to " + config.address() + " lost; the outcome of pending requests is unknown"));
            if (!closed) {
                if (everReady && state == State.READY) {
                    res.metrics.connectionLosses.incrementAndGet();
                    log.warn("[{}] connection to {} lost; reconnecting", label, config.address());
                }
                scheduleReconnect(null);
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("[{}] connection error: {}", label, cause.toString());
            ctx.close();
        }
    }
}
