package com.jredis.server.persist;

import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Multi-part AOF persistence, command-thread side.
 *
 * <p>Effects of each batch are encoded into one buffer and handed to the {@link AofWriter}. A
 * rewrite takes a <b>fork-free point-in-time snapshot</b>: at instant T it enqueues a ROTATE marker
 * (so every later effect goes to a new incr file) and starts a snapshot in the {@link Db}; every key
 * that existed at T is written to the new base file exactly once — by the background scan, or just
 * before its first change (pre-image). The base is therefore exactly the data at T, and base + new
 * incr reproduces the live state. See docs/08-persistence.md.
 */
public final class AofPersistence implements Persistence {

    private static final Logger log = LoggerFactory.getLogger(AofPersistence.class);
    private static final long MAX_AOF_BACKLOG = 64L * 1024 * 1024;

    private enum State { IDLE, SCANNING, FINISHING, COMMITTING }

    private final Engine engine;
    private final ServerConfig config;
    private final Path dir;
    private final AofWriter writer;
    private final long loadingTimeMillis;

    // effects
    private long enqueued;
    private boolean backlogWarned;

    // rewrite
    private long generation;
    private State state = State.IDLE;
    private long rewriteGen;
    private Snapshot snapshot;
    private SnapshotFileWriter snapWriter;
    private AofWriter.Rotate rotate;
    private AofWriter.Commit commit;
    private long rewriteStartMillis;
    private long lastLongestRecordMillis;
    private String lastRewriteStatus = "ok";
    private long lastRewriteSeconds = -1;
    private long lastSaveSeconds;
    private long lastTriggerCheck;
    private long baseSize;

    private AofPersistence(Engine engine, ServerConfig config, Path dir, AofWriter writer, Manifest m, long loadingTimeMillis) {
        this.engine = engine;
        this.config = config;
        this.dir = dir;
        this.writer = writer;
        this.generation = m.generation;
        this.loadingTimeMillis = loadingTimeMillis;
        this.lastSaveSeconds = System.currentTimeMillis() / 1000;
        try {
            this.baseSize = m.base == null ? 0 : Files.size(dir.resolve(m.base));
            if (m.base != null) {
                this.lastSaveSeconds = Files.getLastModifiedTime(dir.resolve(m.base)).toMillis() / 1000;
            }
        } catch (IOException e) {
            this.baseSize = 0;
        }
    }

    /**
     * Opens the data directory: reads (or creates) the manifest, loads the data into the engine,
     * removes leftovers, and starts the writer. Runs before the command thread starts.
     */
    public static AofPersistence open(Engine engine, ServerConfig config) throws IOException, DataLoadException {
        Path dir = Paths.get(config.dir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path mf = dir.resolve(Manifest.FILE);
        Manifest m;
        if (Files.exists(mf)) {
            try {
                m = Manifest.read(mf);
            } catch (IOException | RuntimeException e) {
                throw new DataLoadException("cannot read " + mf + ": " + e.getMessage(), e);
            }
            for (String f : m.incrs) {
                if (!Files.exists(dir.resolve(f))) {
                    throw new DataLoadException("manifest lists " + f + " but the file is missing in " + dir);
                }
            }
            if (m.base != null && !Files.exists(dir.resolve(m.base))) {
                throw new DataLoadException("manifest lists " + m.base + " but the file is missing in " + dir);
            }
        } else {
            if (hasDataFiles(dir)) {
                throw new DataLoadException("no manifest in " + dir + " but data files are present; refusing to guess. "
                        + "Restore the manifest or move the files away.");
            }
            m = new Manifest(1, null, Collections.singletonList(Manifest.incrName(1)));
            Path first = dir.resolve(m.lastIncr());
            if (!Files.exists(first)) {
                Files.createFile(first);
            }
            FileUtil.fsyncDir(dir);
            m.write(dir);
            log.info("initialised an empty data directory {}", dir);
        }
        long t0 = System.currentTimeMillis();
        Loader.Result r = Loader.load(dir, m, engine, config.aofLoadTruncated());
        long took = System.currentTimeMillis() - t0;
        log.info("loaded {} keys from {} ({} from the base, {} commands replayed{}) in {} ms",
                engine.db().size(), dir, r.baseKeys, r.commands, r.errors > 0 ? ", " + r.errors + " replay errors" : "", took);
        deleteLeftovers(dir, m);
        AofWriter w = new AofWriter(dir, m, config, t -> engine.submit((Runnable) () -> engine.fatal("the AOF writer failed", t)));
        w.start();
        return new AofPersistence(engine, config, dir, w, m, took);
    }

    private static boolean hasDataFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                boolean emptyFirstIncr = n.equals(Manifest.incrName(1)) && Files.size(p) == 0;
                if (n.matches("base\\.\\d+\\.jrdb") || (n.matches("incr\\.\\d+\\.aof") && !emptyFirstIncr)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteLeftovers(Path dir, Manifest m) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (m.isLeftover(p.getFileName().toString())) {
                    log.info("removing leftover file {}", p.getFileName());
                    FileUtil.deleteQuietly(p);
                }
            }
        }
    }

    // ================================================================== effects

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void append(byte[] chunk) {
        enqueued += chunk.length;
        writer.submit(chunk);
    }

    @Override
    public void endOfBatch() {
        if (writer.healthy() && enqueued - writer.written() > MAX_AOF_BACKLOG) {
            if (!backlogWarned) {
                log.warn("AOF writer is {} MB behind (slow disk?); pausing commands until it catches up",
                        (enqueued - writer.written()) / (1024 * 1024));
                backlogWarned = true;
            }
            while (writer.healthy() && enqueued - writer.written() > MAX_AOF_BACKLOG / 2) {
                LockSupport.parkNanos(1_000_000L);
            }
        } else {
            backlogWarned = false;
        }
    }

    private void flushEffects() {
        engine.effects().flush();
    }

    @Override
    public boolean writable() {
        return writer.healthy();
    }

    // ================================================================== rewrite

    @Override
    public boolean busy() {
        return state != State.IDLE;
    }

    @Override
    public boolean canProgress() {
        return state == State.SCANNING && snapshot.canProgress();
    }

    @Override
    public boolean rewriteInProgress() {
        return state != State.IDLE;
    }

    @Override
    public String startRewrite() {
        if (state != State.IDLE) {
            return "ERR Background append only file rewriting already in progress";
        }
        if (engine.db().snapshotActive()) {       // one snapshot at a time (docs/16-replication.md §5.2)
            return "ERR a replica's full sync is taking a snapshot; try again when it has finished";
        }
        rewriteGen = ++generation;
        flushEffects();                          // every effect before T goes to the old file
        rotate = new AofWriter.Rotate(rewriteGen);
        writer.submit(rotate);
        snapWriter = SnapshotFileWriter.start(dir.resolve(Manifest.baseName(rewriteGen) + ".tmp"));
        rewriteStartMillis = System.currentTimeMillis();
        commit = null;
        snapshot = Snapshot.start(engine, snapWriter, this::abortRewrite);
        state = State.SCANNING;
        log.info("AOF rewrite started (generation {}, {} keys)", rewriteGen, engine.db().size());
        return null;
    }

    @Override
    public void background(long deadlineNanos) {
        if (state == State.SCANNING && snapshot.step(deadlineNanos)) {
            state = State.FINISHING;
        }
        pollRewrite();
        long now = System.currentTimeMillis();
        if (state == State.IDLE && now - lastTriggerCheck >= 1000) {
            lastTriggerCheck = now;
            maybeAutoRewrite();
        }
    }

    private void pollRewrite() {
        if (state == State.IDLE) {
            return;
        }
        if (rotate.done.isCompletedExceptionally()) {
            abortRewrite("could not start a new incr file");
            return;
        }
        CompletableFuture<Long> fileDone = snapWriter.done();
        if (state == State.SCANNING && fileDone.isDone()) {       // the base writer died mid-scan
            abortRewrite("writing the base file failed");
            return;
        }
        if (state == State.FINISHING && fileDone.isDone() && rotate.done.isDone()) {
            if (fileDone.isCompletedExceptionally() || fileDone.isCancelled()) {
                abortRewrite("writing the base file failed");
                return;
            }
            if (!writer.healthy()) {                                // the COMMIT would queue behind failing writes
                abortRewrite("the AOF cannot be written at the moment");
                return;
            }
            commit = new AofWriter.Commit(rewriteGen, Manifest.baseName(rewriteGen));
            writer.submit(commit);
            state = State.COMMITTING;
        }
        if (state == State.COMMITTING && commit.done.isDone()) {
            if (commit.done.isCompletedExceptionally()) {
                lastRewriteStatus = "err";
                backOffAutoRewrite();
                log.error("AOF rewrite generation {} could not be committed; the previous files remain valid", rewriteGen);
            } else {
                autoRewriteBackoffMillis = 0;
                baseSize = fileDone.join();
                lastRewriteStatus = "ok";
                lastSaveSeconds = System.currentTimeMillis() / 1000;
                lastRewriteSeconds = (System.currentTimeMillis() - rewriteStartMillis) / 1000;
                lastLongestRecordMillis = snapshot.longestRecordNanos() / 1_000_000;
                log.info("AOF rewrite generation {} done: base {} bytes in {} ms (longest single record {} ms)",
                        rewriteGen, baseSize, System.currentTimeMillis() - rewriteStartMillis, lastLongestRecordMillis);
            }
            state = State.IDLE;
            if (rewriteScheduled) {                              // BGSAVE SCHEDULE while this one ran
                rewriteScheduled = false;
                startRewrite();
            }
        }
    }

    // 3: after a failed rewrite, automatic ones wait 1 s, doubling to 1 h, so a full disk does not
    // start (and rotate the AOF for) a new rewrite every second
    private long autoRewriteBackoffMillis;
    private long nextAutoRewriteMillis;
    private boolean rewriteScheduled;

    private void backOffAutoRewrite() {
        autoRewriteBackoffMillis = Math.min(3_600_000L, Math.max(1_000L, autoRewriteBackoffMillis * 2));
        nextAutoRewriteMillis = System.currentTimeMillis() + autoRewriteBackoffMillis;
    }

    /** BGSAVE SCHEDULE during a rewrite: run another one when it ends. */
    @Override
    public void scheduleRewrite() {
        rewriteScheduled = true;
    }

    private void maybeAutoRewrite() {
        int pct = config.autoAofRewritePercentage();
        if (pct <= 0 || System.currentTimeMillis() < nextAutoRewriteMillis) {
            return;
        }
        long incr = writer.incrBytes();
        if (incr < config.autoAofRewriteMinSize()) {
            return;
        }
        if (baseSize > 0 && incr * 100 < baseSize * pct) {
            return;
        }
        log.info("starting automatic AOF rewrite: incr files {} bytes, base {} bytes", incr, baseSize);
        startRewrite();
    }

    @Override
    public void abortRewrite(String reason) {
        if (state != State.SCANNING && state != State.FINISHING) {
            return;                              // idle, or committing (which is always safe to finish)
        }
        snapshot.abort();
        Path tmp = dir.resolve(Manifest.baseName(rewriteGen) + ".tmp");
        snapWriter.done().whenComplete((size, err) -> FileUtil.deleteQuietly(tmp));   // also when it had finished
        state = State.IDLE;
        lastRewriteStatus = "aborted";
        rewriteScheduled = false;
        backOffAutoRewrite();
        log.warn("AOF rewrite generation {} aborted: {}. The current files remain valid.", rewriteGen, reason);
    }

    @Override
    public void saveBlocking() throws Exception {
        if (state != State.IDLE) {
            throw new IllegalStateException("ERR Background save already in progress");
        }
        if (!writer.healthy()) {
            throw new IOException("the AOF cannot be written at the moment (" + writer.lastError() + ")");
        }
        String refused = startRewrite();
        if (refused != null) {
            throw new IllegalStateException(refused);
        }
        try {
            snapshot.runToEnd();
        } catch (IOException e) {                                // the base writer died
            abortRewrite("writing the base file failed");
            throw new IOException("writing the base file failed (see the server log)", e);
        }
        state = State.FINISHING;
        try {
            rotate.done.get(60, TimeUnit.SECONDS);
            snapWriter.done().get(10, TimeUnit.MINUTES);
        } catch (ExecutionException | TimeoutException e) {
            abortRewrite("synchronous save failed: " + e);
            throw new IOException("save failed: " + e, e);
        }
        pollRewrite();                           // submits the commit
        try {
            commit.done.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            pollRewrite();
            throw new IOException("save failed while committing: " + e, e);
        }
        pollRewrite();
    }

    @Override
    public Path installBase(Path received) throws IOException {
        abortRewrite("a full sync from the primary replaces the data");
        try {
            if (state == State.COMMITTING) {                  // always safe to finish: let it
                commit.done.get(60, TimeUnit.SECONDS);
                pollRewrite();
            }
            flushEffects();
            long gen = ++generation;
            Path tmp = dir.resolve(Manifest.baseName(gen) + ".tmp");
            java.nio.file.Files.move(received, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // As a rewrite: a new incr file from here, then the base committed. Until the commit the
            // manifest names the old files and an empty incr, so a crash leaves the old data.
            AofWriter.Rotate r = new AofWriter.Rotate(gen);
            writer.submit(r);
            r.done.get(60, TimeUnit.SECONDS);
            AofWriter.Commit c = new AofWriter.Commit(gen, Manifest.baseName(gen));
            writer.submit(c);
            c.done.get(60, TimeUnit.SECONDS);
            baseSize = Files.size(dir.resolve(Manifest.baseName(gen)));
            lastSaveSeconds = System.currentTimeMillis() / 1000;
            return dir.resolve(Manifest.baseName(gen));
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("installing the image as the base failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while installing the image", e);
        }
    }

    @Override
    public long lastSaveSeconds() {
        return lastSaveSeconds;
    }

    @Override
    public void debugReload() throws Exception {
        saveBlocking();
        flushEffects();
        AofWriter.Barrier b = new AofWriter.Barrier(false);
        writer.submit(b);
        b.done.get(60, TimeUnit.SECONDS);          // everything on disk before re-reading it
        Db db = engine.db();
        db.flushAll();
        try {
            Loader.load(dir, writer.manifest(), engine, false);
        } catch (Throwable t) {                  // only part of the data is in memory now: stop; a restart reloads it all
            engine.fatal("DEBUG RELOAD failed after clearing the data", t);
        }
    }

    // ================================================================== shutdown

    @Override
    public void shutdown() {
        abortRewrite("shutdown");
        flushEffects();
        AofWriter.Barrier b = new AofWriter.Barrier(true);
        writer.submit(b);
        try {
            b.done.get(30, TimeUnit.SECONDS);
            writer.join(5000);
        } catch (Exception e) {
            log.error("final AOF fsync did not complete: {}", e.toString());
        }
    }

    @Override
    public void emergencyFlush() {
        flushEffects();
        AofWriter.Barrier b = new AofWriter.Barrier(false);
        writer.submit(b);
        try {
            b.done.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("emergency AOF fsync did not complete: {}", e.toString());
        }
    }

    // ================================================================== info

    @Override
    public void appendInfo(StringBuilder sb) {
        Manifest m = writer.manifest();
        line(sb, "loading", 0);
        line(sb, "loading_time_ms", loadingTimeMillis);
        line(sb, "aof_enabled", 1);
        line(sb, "aof_dir", dir);
        line(sb, "aof_fsync", config.appendfsync().name().toLowerCase());
        line(sb, "aof_fsync_interval_ms", config.appendfsyncIntervalMillis());
        line(sb, "aof_generation", m.generation);
        line(sb, "aof_base_file", m.base == null ? "" : m.base);
        line(sb, "aof_base_size", baseSize);
        line(sb, "aof_incr_files", m.incrs.size());
        line(sb, "aof_incr_size", writer.incrBytes());
        line(sb, "aof_pending_bytes", Math.max(0, enqueued - writer.written()));
        line(sb, "aof_last_write_status", writer.healthy() ? "ok" : "err");
        line(sb, "aof_last_write_error", writer.healthy() ? "" : writer.lastError());
        line(sb, "aof_last_fsync_age_ms", Math.max(0, System.currentTimeMillis() - writer.lastFsyncMillis()));
        line(sb, "aof_rewrite_in_progress", state == State.IDLE ? 0 : 1);
        line(sb, "aof_rewrite_state", state.name().toLowerCase());
        line(sb, "aof_last_rewrite_status", lastRewriteStatus);
        line(sb, "aof_last_rewrite_time_sec", lastRewriteSeconds);
        line(sb, "aof_rewrite_longest_record_ms", state == State.IDLE ? lastLongestRecordMillis : snapshot.longestRecordNanos() / 1_000_000);
        line(sb, "lastsave", lastSaveSeconds);
    }

    private static void line(StringBuilder sb, String k, Object v) {
        sb.append(k).append(':').append(v).append("\r\n");
    }
}
