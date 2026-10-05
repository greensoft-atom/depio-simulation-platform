package com.jredis.server.repl;

import com.jredis.common.Bytes;
import com.jredis.common.RespWriter;
import com.jredis.server.blocking.BlockState;
import com.jredis.server.core.Client;
import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.persist.BaseFormat;
import com.jredis.server.persist.DataLoadException;
import com.jredis.server.persist.Snapshot;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Replication, on the command thread (docs/16-replication.md). As a primary: the replicas that
 * asked, the full sync's snapshot sent to them, and the stream after it. As a replica: the link to
 * its primary, the image it lands and loads, and the stream it applies.
 */
public final class Replication {

    private static final Logger log = LoggerFactory.getLogger(Replication.class);
    public static final String READONLY_ERROR = "READONLY You can't write against a read only replica.";
    private static final long SYNC_STALL_MILLIS = 60_000;
    private static final long RECONNECT_MILLIS = 1_000;
    private static final byte[] PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GETACK = "*3\r\n$8\r\nREPLCONF\r\n$6\r\nGETACK\r\n$1\r\n*\r\n".getBytes(StandardCharsets.US_ASCII);

    /** A client in WAIT: until {@code wanted} replicas have acknowledged {@code target}, or the deadline. */
    private record Waiter(Client client, long target, long wanted, long deadline) { }

    private final Engine engine;
    private String replid = randomId();
    private long epoch;
    /** Where the epoch is kept, or null: with {@code appendonly no} it lives as long as the process (§9). */
    private Path epochDir;

    // primary side
    private final List<ReplicaSession> replicas = new ArrayList<>();
    private Snapshot sync;
    private SnapshotLinkWriter syncWriter;
    private List<ReplicaSession> syncSessions;
    private long fullSyncs;
    private long partialSyncs;
    private long lastPingMillis;
    private final List<Waiter> waiters = new ArrayList<>();

    // replica side
    private String primaryHost;
    private int primaryPort;
    private PrimaryLink link;
    private String linkState = "none";
    private long nextConnectMillis;
    private long appliedOffset;
    private Client linkClient;
    private List<byte[][]> linkTx;
    private long linkTxBytes;
    private long applyErrors;
    private boolean hasHistory;                  // it holds the primary's history up to appliedOffset
    private String lastSyncError = "";
    private EventLoopGroup linkGroup;

    public Replication(Engine engine) {
        this.engine = engine;
    }

    public boolean isReplica() {
        return primaryHost != null;
    }

    private static String randomId() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(40);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    /** At start-up, with persistence on: the epoch recorded in the data directory. */
    public void loadEpoch(Path dir) throws IOException {
        epochDir = dir;
        epoch = EpochFile.read(dir);
    }

    private void recordEpoch(long e) throws IOException {
        if (epochDir != null) {
            EpochFile.write(epochDir, e);
        }
        epoch = e;
    }

    /** {@code REPLCONF epoch}: a replica newer than this primary must not take its history (§9, D-37). */
    public void checkEpoch(long replicaEpoch) {
        if (replicaEpoch > epoch) {
            throw new com.jredis.server.command.CommandException("EPOCH this primary's epoch " + epoch
                    + " is lower than the replica's " + replicaEpoch + ": it was demoted, and must not be followed");
        }
    }

    /** At a sync: the primary's epoch becomes this replica's. */
    private void adoptEpoch(long primaryEpoch) {
        if (primaryEpoch > epoch) {
            try {
                recordEpoch(primaryEpoch);
            } catch (IOException e) {
                // Kept in memory: it only ever rises, and the next sync records it again.
                epoch = primaryEpoch;
                log.error("could not record epoch {}: {}", primaryEpoch, e.toString());
            }
        }
    }

    /** True while writes must be refused: fewer good replicas than {@code min-replicas-to-write} (§8). */
    public boolean refusesWrites() {
        int wanted = engine.config().minReplicasToWrite();
        if (wanted <= 0 || isReplica()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long lag = engine.config().minReplicasMaxLag() * 1000L;
        int good = 0;
        for (ReplicaSession s : replicas) {
            if (s.state == ReplicaSession.State.ONLINE && now - s.lastAckMillis <= lag) {
                good++;
            }
        }
        return good < wanted;
    }

    private long offset() {
        return engine.backlog() == null ? 0 : engine.backlog().offset();
    }

    // ================================================================== primary side

    /**
     * {@code PSYNC}: the connection becomes a replica. It continues from the backlog when the
     * history it names is this one and still held (§7), otherwise the next snapshot answers (§5.2).
     */
    public void psync(Client c, String asked, long from) {
        if (isReplica()) {
            engine.writeError(c, "ERR a replica cannot have replicas of its own");
            return;
        }
        if (c.replica != null) {
            engine.writeError(c, "ERR this connection is already a replica");
            return;
        }
        engine.startBacklog((int) engine.config().replBacklogSize());
        if (c.reply != null) {                    // replies to what came before on this connection go first:
            if (c.reply.isReadable()) {           // from here on it carries the image and the stream alone
                c.out.write(c.reply);
            } else {
                c.reply.release();
            }
            c.reply = null;
        }
        ReplicaSession s = new ReplicaSession(c, c.replListeningPort);
        s.lastAckMillis = System.currentTimeMillis();
        c.replica = s;
        replicas.add(s);
        com.jredis.server.repl.Backlog b = engine.backlog();
        if (asked.equals(replid) && from >= b.firstOffset() && from <= b.offset()) {
            byte[] rest = b.from(from);
            c.out.write(Unpooled.wrappedBuffer(("+CONTINUE " + replid + " " + epoch + "\r\n").getBytes(StandardCharsets.US_ASCII)));
            if (rest.length > 0) {
                c.out.write(Unpooled.wrappedBuffer(rest));
            }
            s.state = ReplicaSession.State.ONLINE;
            s.ackOffset = from;
            partialSyncs++;
            log.info("replica {} continues from offset {} ({} bytes from the backlog)", c.out.remoteAddress(), from, rest.length);
            return;
        }
        log.info("replica {} asked for a full sync", c.out.remoteAddress());
        maybeStartSync();
    }

    /** {@code REPLCONF ACK}: the replica has applied this much (§8). */
    public void acknowledged(Client c, long offset) {
        ReplicaSession s = c.replica;
        if (s == null) {
            return;
        }
        s.ackOffset = Math.max(s.ackOffset, offset);
        s.lastAckMillis = System.currentTimeMillis();
        checkWaiters();
    }

    /** {@code WAIT}: replies at once if enough replicas hold the stream so far, else blocks the client (§8). */
    public void waitFor(Client c, long wanted, long timeoutMillis) {
        if (isReplica()) {
            engine.writeError(c, "ERR WAIT cannot be used with replica instances");
            return;
        }
        engine.effects().flush();                 // this client's writes in the batch are in the stream
        long target = offset();
        long have = acknowledged(target);
        if (have >= wanted) {
            RespWriter.integer(engine.replyBuffer(c), have);
            return;
        }
        long deadline = timeoutMillis == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;
        engine.blocking().block(c, BlockState.waitForReplicas());
        waiters.add(new Waiter(c, target, wanted, deadline));
        stream(GETACK);                           // so the replicas say where they are now, not in a second
    }

    private long acknowledged(long target) {
        long n = 0;
        for (ReplicaSession s : replicas) {
            if (s.state == ReplicaSession.State.ONLINE && s.ackOffset >= target) {
                n++;
            }
        }
        return n;
    }

    private void checkWaiters() {
        if (waiters.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (java.util.Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
            Waiter w = it.next();
            long have = acknowledged(w.target());
            if (have >= w.wanted() || now >= w.deadline()) {
                it.remove();
                RespWriter.integer(engine.replyBuffer(w.client()), have);
                engine.unblockClient(w.client());
            }
        }
    }

    /** A record of the stream that is no effect (PING, GETACK): the backlog and the replicas online, not the AOF. */
    private void stream(byte[] record) {
        if (engine.backlog() == null) {
            return;
        }
        engine.effects().flush();                 // after what came before it
        engine.backlog().append(record);
        sendToOnline(record);
    }

    /** Starts a snapshot for every replica waiting, unless one already runs (§5.2). */
    private void maybeStartSync() {
        if (sync != null || engine.db().snapshotActive()) {
            return;
        }
        List<ReplicaSession> waiting = new ArrayList<>();
        for (ReplicaSession s : replicas) {
            if (s.state == ReplicaSession.State.WAIT_SNAPSHOT) {
                waiting.add(s);
            }
        }
        if (waiting.isEmpty()) {
            return;
        }
        engine.effects().flush();                // the instant falls between two chunks
        long at = offset();
        String mark = randomId();
        byte[] header = ("+FULLRESYNC " + replid + " " + at + " " + epoch + "\r\n$EOF:" + mark + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        for (ReplicaSession s : waiting) {
            s.state = ReplicaSession.State.SENDING;
            s.syncOffset = at;
        }
        syncSessions = waiting;
        syncWriter = new SnapshotLinkWriter(waiting, header, mark.getBytes(StandardCharsets.US_ASCII), SYNC_STALL_MILLIS);
        sync = Snapshot.start(engine, syncWriter, this::abortSync);
        syncWriter.start();
        fullSyncs++;
        log.info("full sync of {} replica(s) from offset {} ({} keys)", waiting.size(), at, engine.db().size());
    }

    /** End of a batch: the chunk, already in the backlog, to every replica online. */
    public void sendToOnline(byte[] chunk) {
        for (ReplicaSession s : replicas) {
            if (s.state == ReplicaSession.State.ONLINE) {
                s.client.out.write(Unpooled.wrappedBuffer(chunk));
            }
        }
    }

    private void finishSync() {
        boolean ok = !syncWriter.done().isCompletedExceptionally() && !syncWriter.done().isCancelled();
        for (ReplicaSession s : syncSessions) {
            if (s.dropped) {
                continue;
            }
            if (!ok || s.failed) {
                engine.killClient(s.client);
            } else {
                online(s);
            }
        }
        sync = null;
        syncWriter = null;
        syncSessions = null;
    }

    /** The image is sent: what the backlog holds from its instant on, then the live stream. */
    private void online(ReplicaSession s) {
        byte[] rest = engine.backlog().from(s.syncOffset);
        if (rest == null) {
            log.warn("replica {} dropped: the backlog no longer holds offset {}, the writes outran its full sync "
                    + "(raise repl-backlog-size)", s.client.out.remoteAddress(), s.syncOffset);
            engine.killClient(s.client);
            return;
        }
        if (rest.length > 0) {
            s.client.out.write(Unpooled.wrappedBuffer(rest));
        }
        s.state = ReplicaSession.State.ONLINE;
        log.info("replica {} online at offset {}", s.client.out.remoteAddress(), engine.backlog().offset());
    }

    /** Drops the snapshot being sent, and the replicas waiting for it: they will ask again. */
    public void abortSync(String reason) {
        if (sync == null) {
            return;
        }
        sync.abort();
        for (ReplicaSession s : syncSessions) {
            if (!s.dropped) {
                engine.killClient(s.client);
            }
        }
        sync = null;
        syncWriter = null;
        syncSessions = null;
        log.warn("full sync aborted: {}", reason);
    }

    /** A connection closed: if it was a replica, it is no longer one. */
    public void clientClosed(Client c) {
        waiters.removeIf(w -> w.client() == c);
        ReplicaSession s = c.replica;
        if (s == null) {
            return;
        }
        s.dropped = true;
        replicas.remove(s);
        log.info("replica {} disconnected", c.out.remoteAddress());
        if (syncSessions != null && syncSessions.stream().allMatch(x -> x.dropped || x.failed)) {
            abortSync("no replica left to send it to");
        }
    }

    // ================================================================== background

    /** Between batches: the snapshot's progress, a finished image, a reconnection due. */
    public void background(long deadlineNanos) {
        if (sync != null) {
            if (!sync.scanned()) {
                sync.step(deadlineNanos);
            }
            if (syncWriter.done().isDone()) {
                finishSync();
            }
        }
        maybeStartSync();
        connectIfDue();
        long now = System.currentTimeMillis();
        long timeout = engine.config().replTimeout() * 1000L;
        if (!replicas.isEmpty() && now - lastPingMillis >= engine.config().replPingReplicaPeriod() * 1000L) {
            lastPingMillis = now;
            stream(PING);                         // so a replica tells a quiet primary from a dead link
        }
        for (ReplicaSession s : new ArrayList<>(replicas)) {
            if (s.state == ReplicaSession.State.ONLINE && now - s.lastAckMillis > timeout) {
                log.warn("replica {} dropped: no acknowledgement for {} s", s.client.out.remoteAddress(), timeout / 1000);
                engine.killClient(s.client);
            }
        }
        if (link != null && now - link.lastReadMillis() > timeout) {
            log.warn("link to the primary {} dropped: nothing from it for {} s", link.address(), timeout / 1000);
            link.close();
        }
        checkWaiters();
    }

    /** True while there is work the background could do right now. */
    public boolean canProgress() {
        return sync != null && (sync.canProgress() || syncWriter.done().isDone());
    }

    // ================================================================== replica side

    /** {@code REPLICAOF host port}. @return false if it already follows that primary */
    public boolean replicaOf(String host, int port) {
        if (isReplica() && host.equals(primaryHost) && port == primaryPort) {
            return false;
        }
        abortSync("this server is becoming a replica");
        for (ReplicaSession s : new ArrayList<>(replicas)) {
            engine.killClient(s.client);
        }
        closeLink();
        primaryHost = host;
        primaryPort = port;
        engine.db().replica(true);
        linkTx = null;
        linkState = "connect";
        nextConnectMillis = 0;
        log.info("replica of {}:{}", host, port);
        connectIfDue();
        return true;
    }

    /**
     * {@code REPLICAOF NO ONE}: a primary again, with a history of its own and the next epoch, which
     * is on disk before any write is taken (§9).
     */
    public void promote() throws IOException {
        if (!isReplica()) {
            return;
        }
        recordEpoch(epoch + 1);                  // on failure it stays a replica, and says why
        closeLink();
        log.info("no longer a replica of {}:{}: a primary now", primaryHost, primaryPort);
        primaryHost = null;
        engine.db().replica(false);
        linkTx = null;
        linkState = "none";
        replid = randomId();
        hasHistory = false;
    }

    private void connectIfDue() {
        if (!isReplica() || link != null || engine.clock().nowMillis() < nextConnectMillis) {
            return;
        }
        Path dir = Paths.get(engine.config().dir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            lastSyncError = "cannot create " + dir + ": " + e;
            nextConnectMillis = engine.clock().nowMillis() + RECONNECT_MILLIS;
            return;
        }
        link = new PrimaryLink(engine, this, primaryHost, primaryPort, dir, engine.config().primaryauth(), engine.config().port(),
                epoch, hasHistory ? replid : null, appliedOffset);
        linkState = "connecting";
        link.connect(linkGroup());
    }

    private EventLoopGroup linkGroup() {
        if (linkGroup == null) {
            linkGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("jredis-repl-link", true));
        }
        return linkGroup;
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
        }
    }

    void linkFailed(PrimaryLink l, String reason) {
        if (l != link) {
            return;
        }
        link = null;
        linkTx = null;
        linkState = "connect";
        lastSyncError = reason;
        nextConnectMillis = engine.clock().nowMillis() + RECONNECT_MILLIS;
        log.warn("link to the primary {} lost: {}; trying again in {} ms", l.address(), reason, RECONNECT_MILLIS);
    }

    void discardImage(Path image) {
        try {
            Files.deleteIfExists(image);
        } catch (IOException e) {
            log.warn("could not delete {}: {}", image, e.toString());
        }
    }

    /** The image is on disk and its CRC was sent: it becomes the data (§5.3). */
    void imageReceived(PrimaryLink l, Path image, String primaryReplid, long offset, long primaryEpoch) {
        if (l != link) {
            discardImage(image);
            return;
        }
        long t0 = System.nanoTime();
        Path base;
        try {
            base = engine.persistence().installBase(image);
        } catch (IOException | RuntimeException e) {
            log.error("the image from the primary could not be made this server's base: {}", e.toString());
            discardImage(image);
            l.close();
            return;
        }
        engine.watches().touchExisting(engine.db());
        Db db = engine.db();
        db.flushAll();
        db.loading(true);
        long keys;
        try {
            keys = BaseFormat.read(base, db.hasher(), (type, key, expireAt, value) -> {
                KeyEntry e = db.add(key, type, value);
                if (expireAt >= 0) {
                    db.setExpire(e, expireAt);
                }
            });
        } catch (IOException | DataLoadException | RuntimeException e) {
            // Only part of it is in memory now; the files hold it whole: stop, and a restart loads them.
            engine.fatal("loading the image from the primary failed after clearing the data", e);
            return;
        } finally {
            db.loading(false);
        }
        if (!engine.persistence().enabled()) {
            discardImage(base);
        }
        replid = primaryReplid;
        appliedOffset = offset;
        hasHistory = true;
        adoptEpoch(primaryEpoch);
        l.appliedOffset(offset);
        linkState = "connected";
        lastSyncError = "";
        log.info("full sync from {} done: {} keys in {} ms, at offset {}", l.address(), keys,
                (System.nanoTime() - t0) / 1_000_000, offset);
        l.startStream();
    }

    /**
     * The primary continues this replica's history (§7): what follows is the stream from its offset.
     * Its epoch is the one taken at the full sync: a promotion starts a new history.
     */
    void continued(PrimaryLink l, String id) {
        if (l != link) {
            return;
        }
        linkState = "connected";
        lastSyncError = "";
        log.info("continuing the primary's history {} from offset {}", id, appliedOffset);
    }

    /** Records of the stream, in order (§6). */
    void apply(PrimaryLink l, List<PrimaryLink.Record> batch) {
        if (l != link) {
            return;
        }
        long bytes = 0;
        for (PrimaryLink.Record r : batch) {
            bytes += r.bytes();
            byte[][] argv = r.argv();
            if (argv.length == 0) {
                appliedOffset += r.bytes();
            } else if (linkTx != null) {
                if (Bytes.isKeyword(argv[0], "EXEC")) {
                    applyTransaction(linkTx);
                    appliedOffset += linkTxBytes + r.bytes();
                    linkTx = null;
                } else {
                    linkTx.add(argv);
                    linkTxBytes += r.bytes();
                }
            } else if (Bytes.isKeyword(argv[0], "MULTI")) {
                linkTx = new ArrayList<>();
                linkTxBytes = r.bytes();
            } else if (Bytes.isKeyword(argv[0], "PING")) {
                appliedOffset += r.bytes();
            } else if (Bytes.isKeyword(argv[0], "REPLCONF")) {
                appliedOffset += r.bytes();
                if (argv.length > 1 && Bytes.isKeyword(argv[1], "GETACK")) {
                    l.appliedOffset(appliedOffset);
                    l.acknowledge();             // at once: a WAIT on the primary is waiting for it
                }
            } else {
                applyOne(argv);
                appliedOffset += r.bytes();
            }
        }
        l.appliedOffset(appliedOffset);
        l.applied(bytes);
    }

    private void applyTransaction(List<byte[][]> commands) {
        engine.effects().beginTransaction();
        try {
            for (byte[][] argv : commands) {
                applyOne(argv);
            }
        } finally {
            engine.effects().endTransaction();
        }
    }

    private void applyOne(byte[][] argv) {
        if (linkClient == null) {
            linkClient = engine.newLoadingClient();
        }
        try {
            engine.applyReplicated(linkClient, argv);
        } catch (RuntimeException e) {
            // It would serve data the primary does not hold (§6): stop, restart, sync again.
            engine.fatal("applying " + Bytes.printable(argv[0], 32) + " from the primary's stream failed", e);
        }
        if (linkClient.reply != null) {
            if (linkClient.reply.isReadable() && linkClient.reply.getByte(linkClient.reply.readerIndex()) == '-') {
                applyErrors++;
                if (applyErrors <= 10) {
                    log.error("{} from the primary's stream was refused: {}", Bytes.printable(argv[0], 32),
                            linkClient.reply.toString(StandardCharsets.UTF_8).trim());
                }
            }
            linkClient.reply.clear();
        }
    }

    // ================================================================== ROLE, INFO, shutdown

    public void role(CommandContext ctx) {
        if (!isReplica()) {
            ctx.arrayHeader(4);
            ctx.bulk("primary");
            ctx.integer(offset());
            ctx.arrayHeader(replicas.size());
            for (ReplicaSession s : replicas) {
                ctx.arrayHeader(3);
                ctx.bulk(s.host());
                ctx.bulk(Integer.toString(s.listeningPort));
                ctx.bulk(Long.toString(s.ackOffset));
            }
            ctx.integer(epoch);
        } else {
            ctx.arrayHeader(6);
            ctx.bulk("replica");
            ctx.bulk(primaryHost);
            ctx.integer(primaryPort);
            ctx.bulk(linkState);
            ctx.integer(appliedOffset);
            ctx.integer(epoch);
        }
    }

    public void appendInfo(StringBuilder sb) {
        line(sb, "role", isReplica() ? "replica" : "primary");
        line(sb, "replication_epoch", epoch);
        line(sb, "replid", replid);
        if (isReplica()) {
            line(sb, "primary_host", primaryHost);
            line(sb, "primary_port", primaryPort);
            line(sb, "primary_link_status", "connected".equals(linkState) ? "up" : "down");
            line(sb, "primary_link_state", linkState);
            line(sb, "primary_last_io_seconds_ago", link == null ? -1 : (System.currentTimeMillis() - link.lastReadMillis()) / 1000);
            line(sb, "primary_sync_in_progress", link != null && !"connected".equals(linkState) ? 1 : 0);
            line(sb, "replica_applied_offset", appliedOffset);
            line(sb, "replica_apply_errors", applyErrors);
            line(sb, "last_sync_error", lastSyncError);
        } else {
            line(sb, "repl_offset", offset());
            line(sb, "connected_replicas", replicas.size());
            for (int i = 0; i < replicas.size(); i++) {
                ReplicaSession s = replicas.get(i);
                sb.append("replica").append(i).append(":ip=").append(s.host()).append(",port=").append(s.listeningPort)
                        .append(",state=").append(s.state.name().toLowerCase()).append(",offset=").append(s.ackOffset)
                        .append(",lag=").append((System.currentTimeMillis() - s.lastAckMillis) / 1000).append("\r\n");
            }
            line(sb, "full_syncs", fullSyncs);
            line(sb, "partial_syncs", partialSyncs);
            line(sb, "waiting_clients", waiters.size());
            line(sb, "repl_backlog_active", engine.backlog() == null ? 0 : 1);
            line(sb, "repl_backlog_size", engine.config().replBacklogSize());
            line(sb, "repl_backlog_first_offset", engine.backlog() == null ? 0 : engine.backlog().firstOffset());
        }
    }

    private static void line(StringBuilder sb, String k, Object v) {
        sb.append(k).append(':').append(v).append("\r\n");
    }

    /** The engine stopped: the link and its thread go. */
    public void shutdown() {
        closeLink();
        if (sync != null) {
            sync.abort();
            sync = null;
        }
        if (linkGroup != null) {
            linkGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
