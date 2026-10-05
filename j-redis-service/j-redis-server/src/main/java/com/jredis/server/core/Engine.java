package com.jredis.server.core;

import com.jredis.common.Bytes;
import com.jredis.common.RespWriter;
import com.jredis.server.blocking.BlockingRegistry;
import com.jredis.server.blocking.BlockingServer;
import com.jredis.server.command.CommandException;
import com.jredis.server.command.CommandSpec;
import com.jredis.server.command.CommandTable;
import com.jredis.server.command.Commands;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.db.Db;
import com.jredis.server.db.DbHooks;
import com.jredis.server.db.SipHash;
import com.jredis.server.info.SlowLog;
import com.jredis.server.persist.NoPersistence;
import com.jredis.server.persist.Persistence;
import com.jredis.server.pubsub.PubSub;
import com.jredis.server.repl.Backlog;
import com.jredis.server.repl.Replication;
import com.jredis.server.tx.WatchRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The single-threaded core. Owns every piece of data and client state; runs on exactly one thread
 * (the command thread) after start-up, or on the loader thread before it. Other threads interact
 * only through {@link #submit}.
 */
public final class Engine implements DbHooks {

    private static final Logger log = LoggerFactory.getLogger(Engine.class);

    private static final int BATCH_MAX = 1024;
    private static final long BATCH_MAX_NANOS = 1_000_000L;
    private static final byte[] DEL = "DEL".getBytes(StandardCharsets.US_ASCII);

    public static final String OOM_ERROR = "OOM command not allowed when used memory > 'maxmemory'.";
    public static final String MISCONF_ERROR = "MISCONF Errors writing to the AOF file: write commands are "
            + "disabled until the AOF can be written again. Check the server log and the free disk space.";

    private final ServerConfig config;
    private final Clock clock;
    private final Db db;
    private final CommandTable table;
    private final EventQueue queue = new EventQueue();
    private final PubSub pubsub;
    private final BlockingRegistry blocking = new BlockingRegistry();
    private final WatchRegistry watches = new WatchRegistry();
    private final SlowLog slowlog = new SlowLog();
    private final Stats stats = new Stats();
    private final Background background;
    private final ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
    private final LinkedHashMap<Long, Client> clients = new LinkedHashMap<>();
    private final ArrayList<Client> touched = new ArrayList<>();
    private final ArrayDeque<Client> unblocked = new ArrayDeque<>();
    private final long startMillis = System.currentTimeMillis();
    private final String runId = randomRunId();
    private final FatalHandler fatalHandler;

    private Persistence persistence = NoPersistence.INSTANCE;
    private final Effects effects = new Effects(this);
    private Backlog backlog;
    private final Replication replication = new Replication(this);
    private boolean replicating;                   // applying the primary's stream: its effects are logged here too
    private long nextClientId = 1;
    private int execDepth;
    private volatile boolean running;
    private volatile Thread thread;
    private boolean shutdownPending;
    private boolean shutdownSave;
    private final List<Runnable> stopListeners = new ArrayList<>();
    private volatile boolean fatalRaised;
    private boolean activeExpire = true;

    public Engine(ServerConfig config, Clock clock, FatalHandler fatalHandler) {
        this.config = config;
        this.clock = clock;
        this.fatalHandler = fatalHandler;
        this.db = new Db(SipHash.random(), clock::nowMillis);
        this.db.hooks(this);
        this.table = Commands.createTable(config);
        this.pubsub = new PubSub(this);
        this.background = new Background(this);
    }

    // ================================================================== accessors

    public ServerConfig config() { return config; }
    public Clock clock() { return clock; }
    public Db db() { return db; }
    public CommandTable table() { return table; }
    public PubSub pubsub() { return pubsub; }
    public BlockingRegistry blocking() { return blocking; }
    public WatchRegistry watches() { return watches; }
    public SlowLog slowlog() { return slowlog; }
    public Stats stats() { return stats; }
    public Persistence persistence() { return persistence; }
    public Effects effects() { return effects; }
    /** The replication stream's recent bytes, or null while nothing has asked for one. */
    public Backlog backlog() { return backlog; }
    public Replication replication() { return replication; }
    public long startMillis() { return startMillis; }
    public String runId() { return runId; }
    public boolean inExec() { return execDepth > 0; }
    public boolean isRunning() { return running; }

    /** True once a fail-stop was raised (the exit code must then be 4, never 0). */
    public boolean failed() { return fatalRaised; }
    public boolean activeExpire() { return activeExpire; }
    public void activeExpire(boolean enabled) { activeExpire = enabled; }
    public int execDepth() { return execDepth; }

    public Collection<Client> clients() {
        return Collections.unmodifiableCollection(clients.values());
    }

    public void persistence(Persistence p) {
        this.persistence = p;
    }

    /** Starts the replication stream, at offset 0, if it has not started. Command thread. */
    public void startBacklog(int size) {
        if (backlog == null) {
            effects.flush();                       // what came before is not in the stream
            backlog = new Backlog(size);
        }
    }

    public void addStopListener(Runnable r) {
        stopListeners.add(r);
    }

    // ================================================================== threads

    /** Enqueues an event for the command thread. Any thread. */
    public void submit(Object event) {
        queue.offer(event);
    }

    /** Starts the command thread. */
    public Thread start(String threadName) {
        Thread t = new Thread(this::run, threadName);
        t.setDaemon(false);
        running = true;
        thread = t;
        queue.consumer(t);
        t.start();
        return t;
    }

    public boolean onEngineThread() {
        return Thread.currentThread() == thread;
    }

    /** Graceful stop from any thread; {@code onDone} runs on the command thread when finished. */
    public void requestShutdown(boolean save, Runnable onDone) {
        submit((Runnable) () -> {
            shutdownPending = true;
            shutdownSave = save;
            if (onDone != null) {
                stopListeners.add(onDone);
            }
        });
    }

    /** SHUTDOWN command: already on the command thread. */
    public void shutdownFromCommand(boolean save) {
        shutdownPending = true;
        shutdownSave = save;
    }

    private void run() {
        log.info("command thread started");
        try {
            while (running) {
                clock.refresh();
                int executed = 0;
                long deadline = System.nanoTime() + BATCH_MAX_NANOS;
                Object ev;
                while (executed < BATCH_MAX && (ev = queue.poll()) != null) {
                    handle(ev);
                    executed++;
                    if ((executed & 15) == 0 && System.nanoTime() - deadline > 0) {
                        break;
                    }
                }
                endOfBatch();
                if (shutdownPending) {
                    performShutdown();
                    break;
                }
                background.run();
                afterCommands();
                endOfBatch();
                if (executed == 0) {
                    queue.park(background.parkNanos());
                }
            }
        } catch (EngineStoppedException e) {
            log.error("command thread stopped: {}", e.getMessage());
        } catch (Throwable t) {
            try {
                fatal("unexpected error in the command loop", t);
            } catch (EngineStoppedException ignored) {
                // already reported
            }
        } finally {
            running = false;
            replication.shutdown();
            for (Runnable r : stopListeners) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.warn("stop listener failed", e);
                }
            }
            log.info("command thread stopped");
        }
    }

    private void performShutdown() {
        log.info("shutting down (save={})", shutdownSave);
        try {
            if (shutdownSave && persistence.enabled()) {
                persistence.saveBlocking();
            } else {
                persistence.abortRewrite("shutdown");
            }
        } catch (Exception e) {
            log.error("SHUTDOWN SAVE failed; the AOF is still complete", e);
        }
        endOfBatch();
        persistence.shutdown();
        for (Client c : new ArrayList<>(clients.values())) {
            c.out.close();
        }
        running = false;
    }

    /** Stops the process (server) or the engine (embedded) because data may be inconsistent. */
    public void fatal(String reason, Throwable cause) {
        if (fatalRaised) {
            throw new EngineStoppedException(reason);
        }
        fatalRaised = true;
        try {                                    // nothing may keep the handler below from running
            log.error("FATAL: {}. Stopping so that no inconsistent data is served; "
                    + "restarting reloads the last consistent state from the AOF.", reason, cause);
        } catch (Throwable ignored) {
            // logging itself failed (e.g. class loading); the handler still stops the process
        }
        try {
            effects.discardSinceMark();
            persistence.emergencyFlush();
        } catch (Throwable t) {
            try {
                log.error("emergency AOF flush failed", t);
            } catch (Throwable ignored) {
                // as above
            }
        }
        running = false;
        fatalHandler.fatal(reason, cause, 4);
        throw new EngineStoppedException(reason);
    }

    // ================================================================== events

    private void handle(Object ev) {
        if (ev instanceof Events.Command) {
            Events.Command ce = (Events.Command) ev;
            Client c = ce.output.client();
            if (c == null || c.closed || c.closeAfterReply) {
                ce.output.commandDone(argvBytes(ce.argv));
                return;
            }
            c.lastInteractionMillis = clock.nowMillis();
            if (c.isBlocked() || !c.deferred.isEmpty()) {
                c.deferred.addLast(ce.argv);        // keep pipelined order behind the blocked command
                return;
            }
            runCommand(c, ce.argv);
            afterCommands();
        } else if (ev instanceof Events.Connected) {
            ClientOutput out = ((Events.Connected) ev).output;
            Client c = new Client(nextClientId++, out, clock.nowMillis());
            c.authenticated = config.requirepass().isEmpty();
            out.client(c);
            clients.put(c.id, c);
            stats.connectionsReceived++;
        } else if (ev instanceof Events.Closed) {
            Client c = ((Events.Closed) ev).output.client();
            if (c != null) {
                freeClient(c);
            }
        } else if (ev instanceof Events.ProtocolError) {
            Events.ProtocolError pe = (Events.ProtocolError) ev;
            Client c = pe.output.client();
            if (c != null && !c.closed && !c.closeAfterReply) {
                writeError(c, "ERR Protocol error: " + pe.message);
                closeAfterReplies(c);
            }
        } else if (ev instanceof Events.InputClosed) {
            Client c = ((Events.InputClosed) ev).output.client();
            if (c != null && !c.closed && !c.closeAfterReply) {
                closeAfterReplies(c);              // everything it sent has run; a blocked command is abandoned
            }
        } else if (ev instanceof Runnable) {
            ((Runnable) ev).run();
            afterCommands();
        } else {
            throw new IllegalArgumentException("unknown event " + ev);
        }
    }

    private void runCommand(Client c, byte[][] argv) {
        try {
            execute(c, argv);
        } finally {
            c.out.commandDone(argvBytes(argv));
        }
    }

    public static long argvBytes(byte[][] argv) {
        long n = 0;
        for (byte[] a : argv) {
            n += a.length + 16;
        }
        return n;
    }

    /** Serve clients whose keys became ready, then run commands they had pipelined meanwhile. */
    private void afterCommands() {
        while (true) {
            if (blocking.hasReady()) {
                serveReadyKeys();
            }
            Client c = unblocked.poll();
            if (c == null) {
                return;
            }
            while (!c.closed && !c.closeAfterReply && !c.isBlocked() && !c.deferred.isEmpty()) {
                runCommand(c, c.deferred.pollFirst());
                if (blocking.hasReady()) {
                    serveReadyKeys();
                }
            }
        }
    }

    /**
     * Serves waiters of each ready key in arrival order. A waiter for another type (BZPOPMIN on a
     * key that now holds a list) is skipped, not allowed to hold up the ones behind it; serving
     * stops only when the key has nothing left.
     */
    private void serveReadyKeys() {
        for (byte[] key : blocking.drainReady()) {
            for (Client w : blocking.waitersSnapshot(key)) {
                if (!w.isBlocked()) {
                    continue;                                   // served through another key already
                }
                if (BlockingServer.tryServe(this, w, key)) {
                    blocking.unblock(w);
                    w.lastInteractionMillis = clock.nowMillis();
                    unblocked.add(w);
                } else if (db.lookupRead(key) == null && !w.block.readsAStream()) {
                    // Nothing left to serve. Not after a stream reader: a group reader behind it on
                    // a deleted stream is still to be told (15 §6).
                    break;
                }
            }
        }
    }

    /** Ends a block its owner decided is over (WAIT), after it wrote the reply: what it pipelined runs next. */
    public void unblockClient(Client c) {
        blocking.unblock(c);
        c.lastInteractionMillis = clock.nowMillis();
        unblocked.add(c);
    }

    /** Background: reply null to clients whose blocking timeout passed. */
    void expireBlockedClients() {
        for (Client c : blocking.expireTimeouts(clock.nowMillis())) {
            replyBuffer(c).writeBytes(RespWriter.NULL_ARRAY);
            c.lastInteractionMillis = clock.nowMillis();
            unblocked.add(c);
        }
    }

    // ================================================================== dispatch

    /** Validates and runs one command (or queues it inside MULTI). */
    public void execute(Client c, byte[][] argv) {
        if (argv.length == 0) {
            return;
        }
        CommandSpec spec = table.lookup(argv[0]);
        c.commandsProcessed++;
        if (spec == null || spec.disabled) {
            reject(c, null, unknownCommandError(argv));
            return;
        }
        if (!spec.arityOk(argv.length)) {
            reject(c, spec, "ERR wrong number of arguments for '" + spec.name.toLowerCase() + "' command");
            return;
        }
        if (!c.authenticated && !spec.has(CommandSpec.NOAUTH) && !config.requirepass().isEmpty()) {
            reject(c, spec, "NOAUTH Authentication required.");
            return;
        }
        if (spec.has(CommandSpec.WRITE) && replication.isReplica()) {
            reject(c, spec, Replication.READONLY_ERROR);   // only its link writes (docs/16-replication.md §6)
            return;
        }
        if (spec.has(CommandSpec.WRITE) && replication.refusesWrites()) {
            reject(c, spec, "NOREPLICAS Not enough good replicas to write.");   // docs/16-replication.md §8
            return;
        }
        if (c.inPubSubMode() && !spec.has(CommandSpec.PUBSUB)) {
            reject(c, spec, "ERR Can't execute '" + spec.name.toLowerCase() + "': only (P|S)SUBSCRIBE / "
                    + "(P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context");
            return;
        }
        if (c.inMulti && !spec.has(CommandSpec.TX_CONTROL)) {
            if (spec.has(CommandSpec.NO_MULTI)) {
                reject(c, spec, "ERR Command not allowed inside a transaction");
                return;
            }
            if ((spec.has(CommandSpec.WRITE) || spec.name.equals("PING")) && !persistence.writable()) {
                reject(c, spec, MISCONF_ERROR);
                return;
            }
            if (overMaxmemory()) {                  // as Redis: nothing is queued above maxmemory
                reject(c, spec, OOM_ERROR);
                return;
            }
            c.multiFlags |= spec.flags & (CommandSpec.WRITE | CommandSpec.DENYOOM);
            c.multiQueue.add(argv);
            replyBuffer(c).writeBytes(RespWriter.QUEUED);
            return;
        }
        if ((spec.has(CommandSpec.WRITE) || spec.name.equals("PING")) && !persistence.writable()) {
            reject(c, spec, MISCONF_ERROR);         // PING too, as in Redis: health checks must notice
            return;
        }
        if (spec.has(CommandSpec.DENYOOM) && overMaxmemory()) {
            reject(c, spec, OOM_ERROR);
            return;
        }
        call(c, spec, argv);
    }

    private void reject(Client c, CommandSpec spec, String error) {
        writeError(c, error);
        if (c.inMulti) {
            c.multiAborted = true;
        }
        if (spec != null) {
            spec.stat.rejected++;
        }
        stats.rejectedCalls++;
    }

    private static String unknownCommandError(byte[][] argv) {
        StringBuilder sb = new StringBuilder("ERR unknown command '")
                .append(Bytes.printable(argv[0], 128)).append("', with args beginning with: ");
        for (int i = 1; i < argv.length && sb.length() < 256; i++) {
            sb.append('\'').append(Bytes.printable(argv[i], 64)).append("' ");
        }
        return sb.toString();
    }

    public boolean overMaxmemory() {
        long max = config.maxmemory();
        return max > 0 && db.usedMemory() > max;
    }

    /** Runs a handler with statistics, slowlog and the fail-stop rule. */
    private void call(Client c, CommandSpec spec, byte[][] argv) {
        CommandContext ctx = new CommandContext(this, c, argv, spec);
        boolean topLevel = execDepth == 0;
        boolean outerMutated = db.mutated();       // inside EXEC: what the earlier commands changed
        db.resetMutated();                         // judge this command by its own changes
        if (topLevel) {
            effects.markCommandStart();
        }
        long t0 = System.nanoTime();
        try {
            spec.handler.execute(ctx);
        } catch (CommandException e) {
            ctx.error(e.getMessage());
            spec.stat.failed++;
            stats.failedCalls++;
        } catch (EngineStoppedException e) {
            throw e;
        } catch (RuntimeException | Error t) {
            if (db.isLoading()) {
                throw new IllegalStateException("replaying " + spec.name + " from the AOF failed", t);
            }
            if (db.mutated()) {
                fatal("internal error in " + spec.name + " after it started changing data", t);
            }
            stats.internalErrors++;
            log.error("internal error in {} (client id={}); no data was changed", spec.name, c.id, t);
            ctx.error("ERR internal error while executing '" + spec.name.toLowerCase() + "' (see the server log)");
        }
        db.mutated(outerMutated || db.mutated());
        if (db.isLoading()) {
            return;                                // replaying the AOF is not client traffic
        }
        long micros = (System.nanoTime() - t0) / 1000;
        spec.stat.record(micros);
        stats.commandsProcessed++;
        c.lastCommand = spec.name;
        long threshold = config.slowlogLogSlowerThan();
        if (threshold >= 0 && micros >= threshold && !spec.name.equals("EXEC")) {   // its commands are logged
            slowlog.add(argv, micros, clock.nowMillis(), c.out.remoteAddress(), c.name, config.slowlogMaxLen());
        }
    }

    /** EXEC: runs the queued commands back to back, effects wrapped in MULTI/EXEC in the AOF. */
    public void execTransaction(Client c, List<byte[][]> commands) {
        effects.beginTransaction();
        execDepth++;
        try {
            for (byte[][] argv : commands) {
                CommandSpec spec = table.lookup(argv[0]);
                if (spec == null) {
                    writeError(c, unknownCommandError(argv));
                    continue;
                }
                call(c, spec, argv);
            }
        } finally {
            execDepth--;
            effects.endTransaction();
        }
    }

    /** A client whose replies go nowhere, used to replay the AOF. */
    public Client newLoadingClient() {
        LoadingOutput out = new LoadingOutput();
        Client c = new Client(0, out, clock.nowMillis());
        c.authenticated = true;
        out.client(c);
        return c;
    }

    /**
     * Applies one command of the primary's stream as the loader replays a file, nothing deleted on
     * time grounds, but logging its effect to this server's own AOF (docs/16-replication.md §6).
     */
    public void applyReplicated(Client link, byte[][] argv) {
        db.loading(true);
        replicating = true;
        try {
            executeLoading(link, argv);
        } finally {
            replicating = false;
            db.loading(false);
        }
    }

    /** Runs a command during AOF loading: no checks, replies discarded by the caller. */
    public void executeLoading(Client loader, byte[][] argv) {
        CommandSpec spec = table.lookup(argv[0]);
        if (spec == null || !spec.arityOk(argv.length)) {
            throw new IllegalStateException("invalid command in AOF: " + Bytes.printable(argv[0], 64));
        }
        call(loader, spec, argv);
    }

    // ================================================================== DbHooks

    @Override
    public void keyModified(byte[] key) {
        stats.dirty++;
        watches.touch(key);
        blocking.signalReady(key);
    }

    @Override
    public void keyExpired(byte[] key) {
        stats.expiredKeys++;
        propagate(new byte[][]{DEL, key});
    }

    public void propagate(byte[][] effect) {
        if (!db.isLoading() || replicating) {
            effects.propagate(effect);
        }
    }

    // ================================================================== replies & clients

    /** The client's pending reply buffer for this batch (created on first use). */
    public ByteBuf replyBuffer(Client c) {
        ByteBuf b = c.reply;
        if (b == null) {
            b = alloc.buffer(256);
            c.reply = b;
        }
        if (!c.touched) {
            c.touched = true;
            touched.add(c);
        }
        return b;
    }

    public void writeError(Client c, String message) {
        RespWriter.error(replyBuffer(c), message);
    }

    /** Hands the batch's effects on (AOF, backlog), then flushes replies (one write per client). */
    public void endOfBatch() {
        effects.flush();
        persistence.endOfBatch();
        flushTouched();
    }

    private void flushTouched() {
        if (touched.isEmpty()) {
            return;
        }
        long now = clock.nowMillis();
        for (int i = 0; i < touched.size(); i++) {
            Client c = touched.get(i);
            c.touched = false;
            ByteBuf r = c.reply;
            c.reply = null;
            if (c.closed) {
                if (r != null) {
                    r.release();
                }
                continue;
            }
            if (r != null) {
                if (r.isReadable() && c.replica == null) {   // a replica's connection carries the stream alone
                    c.out.write(r);
                } else {
                    r.release();
                }
            }
            if (c.closeAfterReply) {
                c.out.close();
            } else {
                checkOutputLimits(c, now);
            }
        }
        touched.clear();
    }

    void checkOutputLimits(Client c, long now) {
        boolean pubsub = c.inPubSubMode();
        boolean replica = c.replica != null;
        long hard = replica ? config.replicaOutputHardLimit() : pubsub ? config.pubsubOutputHardLimit() : config.normalOutputHardLimit();
        long soft = replica ? config.replicaOutputSoftLimit() : pubsub ? config.pubsubOutputSoftLimit() : config.normalOutputSoftLimit();
        int softSeconds = replica ? config.replicaOutputSoftSeconds()
                : pubsub ? config.pubsubOutputSoftSeconds() : config.normalOutputSoftSeconds();
        if (hard <= 0 && soft <= 0) {
            return;
        }
        long pending = c.out.pendingBytes();
        if (hard > 0 && pending > hard) {
            disconnectForOutput(c, pending, "hard");
        } else if (soft > 0 && pending > soft) {
            if (c.softLimitSinceMillis < 0) {
                c.softLimitSinceMillis = now;
            } else if (now - c.softLimitSinceMillis >= softSeconds * 1000L) {
                disconnectForOutput(c, pending, "soft");
            }
        } else {
            c.softLimitSinceMillis = -1;
        }
    }

    private void disconnectForOutput(Client c, long pending, String which) {
        log.warn("closing client id={} name={} addr={}: {} output limit exceeded ({} bytes pending)",
                c.id, c.name, c.out.remoteAddress(), which, pending);
        stats.outputLimitDisconnects++;
        killClient(c);
    }

    /**
     * Immediate disconnect (CLIENT KILL, limits, idle timeout). The client is detached at once;
     * the rest of its state is freed when the close event arrives.
     */
    public void killClient(Client c) {
        detach(c);
        c.closeAfterReply = true;
        c.out.abort();
    }

    /**
     * A server made a replica: the connections no {@code -READONLY} will reach, a subscriber's
     * and a client's blocked waiting for data, closed, so that each looks for the primary again
     * (D-39). Every other client is told at its first write.
     */
    public void closeListeners() {
        for (Client c : new ArrayList<>(clients.values())) {
            if (c.inPubSubMode() || c.isBlocked()) {
                closeAfterReplies(c);
            }
        }
    }

    /** Disconnect after the replies already produced (QUIT, protocol error, half-close). */
    public void closeAfterReplies(Client c) {
        detach(c);
        c.closeAfterReply = true;
        replyBuffer(c);                            // touched: the end of the batch flushes, then closes
    }

    /**
     * A closing client takes part in nothing any more: it is not served by blocking pops (the
     * element would be lost), gets no published messages, watches nothing, and its queued or
     * deferred commands are dropped.
     */
    private void detach(Client c) {
        blocking.unblock(c);
        pubsub.removeClient(c);
        watches.unwatchAll(c);
        c.deferred.clear();
        c.inMulti = false;
        c.multiQueue.clear();
    }

    private void freeClient(Client c) {
        if (c.closed) {
            return;
        }
        c.closed = true;
        blocking.unblock(c);
        watches.unwatchAll(c);
        pubsub.removeClient(c);
        clients.remove(c.id);
        replication.clientClosed(c);
        if (c.reply != null) {
            c.reply.release();
            c.reply = null;
        }
        c.deferred.clear();
        c.multiQueue.clear();
    }

    /** Cron: close idle clients (config timeout) and apply soft output limits. */
    void cronClients(long now) {
        int timeoutSeconds = config.timeout();
        for (Client c : new ArrayList<>(clients.values())) {
            if (c.closed) {
                continue;
            }
            long lastActive = Math.max(c.lastInteractionMillis, c.out.lastIoMillis());   // transfers count too
            if (timeoutSeconds > 0 && !c.isBlocked() && !c.inPubSubMode() && c.replica == null
                    && now - lastActive > timeoutSeconds * 1000L) {
                log.info("closing idle client id={} addr={}", c.id, c.out.remoteAddress());
                killClient(c);
                continue;
            }
            checkOutputLimits(c, now);
        }
    }

    Background background() {
        return background;
    }

    private static String randomRunId() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(40);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}
