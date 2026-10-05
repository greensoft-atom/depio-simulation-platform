package com.jredis.server.persist;

import com.jredis.server.config.ServerConfig;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Owns the incremental AOF file and the manifest. Receives effect bytes and control markers from
 * the command thread in order, so a file switch (ROTATE) lands at an exact point in the stream.
 *
 * <ul>
 *   <li>fsync at most every {@code appendfsync-interval-millis} with {@code everysec};</li>
 *   <li>a failed write is retried every second after truncating back to the last good size;
 *       meanwhile {@link #healthy()} is false and the engine rejects writes with MISCONF;</li>
 *   <li>a failed fsync is fatal: on Linux the kernel may have dropped the dirty pages and cleared
 *       the error, so a retry could "succeed" while data is gone.</li>
 * </ul>
 */
final class AofWriter implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AofWriter.class);

    /** Switch appends to a new incr file of the given generation and list it in the manifest. */
    static final class Rotate {
        final long generation;
        final CompletableFuture<Void> done = new CompletableFuture<>();

        Rotate(long generation) {
            this.generation = generation;
        }
    }

    /** A new base file is complete: make it the base and drop the files it replaces. */
    static final class Commit {
        final long generation;
        final String baseName;
        final CompletableFuture<Void> done = new CompletableFuture<>();

        Commit(long generation, String baseName) {
            this.generation = generation;
            this.baseName = baseName;
        }
    }

    /** fsync now (and optionally close and stop the writer). */
    static final class Barrier {
        final boolean close;
        final CompletableFuture<Void> done = new CompletableFuture<>();

        Barrier(boolean close) {
            this.close = close;
        }
    }

    private static final class FsyncFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FsyncFailed(IOException cause) {
            super(cause);
        }
    }

    private final Path dir;
    private final ServerConfig config;
    private final Consumer<Throwable> onFatal;
    private final MpscUnboundedArrayQueue<Object> queue = new MpscUnboundedArrayQueue<>(1024);
    private final ArrayDeque<Object> retry = new ArrayDeque<>();
    private final AtomicLong written = new AtomicLong();
    private final Thread thread;
    private volatile boolean sleeping;
    private volatile boolean healthy = true;
    private volatile String lastError = "";
    private volatile long lastFsyncMillis = System.currentTimeMillis();
    private volatile long currentSize;
    private volatile long olderIncrBytes;
    private volatile Manifest manifest;

    // writer-thread state
    private FileChannel current;
    private long goodSize;
    private boolean needsTruncate;              // a failed write left bytes after goodSize that must go first
    private boolean dirty;
    private boolean stopping;
    private boolean errorLogged;

    AofWriter(Path dir, Manifest manifest, ServerConfig config, Consumer<Throwable> onFatal) throws IOException {
        this.dir = dir;
        this.manifest = manifest;
        this.config = config;
        this.onFatal = onFatal;
        Path file = dir.resolve(manifest.lastIncr());
        this.current = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        this.goodSize = current.size();
        this.current.position(goodSize);
        this.currentSize = goodSize;
        long older = 0;
        for (String incr : manifest.incrs) {
            if (!incr.equals(manifest.lastIncr())) {
                older += Files.size(dir.resolve(incr));
            }
        }
        this.olderIncrBytes = older;
        this.thread = new Thread(this, "jredis-aof-writer");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void submit(Object item) {
        queue.offer(item);
        if (sleeping) {
            LockSupport.unpark(thread);
        }
    }

    long written() {
        return written.get();
    }

    boolean healthy() {
        return healthy;
    }

    String lastError() {
        return lastError;
    }

    long lastFsyncMillis() {
        return lastFsyncMillis;
    }

    /** Bytes in all incr files currently listed in the manifest. */
    long incrBytes() {
        return olderIncrBytes + currentSize;
    }

    Manifest manifest() {
        return manifest;
    }

    void join(long millis) throws InterruptedException {
        thread.join(millis);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Object item = retry.isEmpty() ? queue.poll() : retry.pollFirst();
                if (item == null) {
                    if (stopping) {
                        return;
                    }
                    maybeFsync();
                    sleeping = true;
                    if (queue.isEmpty()) {
                        LockSupport.parkNanos(this, waitNanos());
                    }
                    sleeping = false;
                    continue;
                }
                handle(item);
                if (stopping && retry.isEmpty() && queue.isEmpty()) {
                    return;
                }
            }
        } catch (FsyncFailed e) {
            healthy = false;
            lastError = "fsync failed: " + e.getCause();
            log.error("FATAL: fsync of the AOF failed; stopping to avoid acknowledging lost writes", e.getCause());
            onFatal.accept(e.getCause());
        } catch (RuntimeException e) {
            healthy = false;
            lastError = e.toString();
            log.error("FATAL: AOF writer failed", e);
            onFatal.accept(e);
        }
    }

    private long waitNanos() {
        if (dirty && config.appendfsync() == ServerConfig.FsyncPolicy.EVERYSEC) {
            long due = lastFsyncMillis + config.appendfsyncIntervalMillis() - System.currentTimeMillis();
            return Math.max(1_000_000L, due * 1_000_000L);
        }
        return 100_000_000L;
    }

    private void handle(Object item) {
        if (item instanceof byte[]) {
            append((byte[]) item);
            maybeFsync();
        } else if (item instanceof Rotate) {
            rotate((Rotate) item);
        } else if (item instanceof Commit) {
            commit((Commit) item);
        } else if (item instanceof Barrier) {
            Barrier b = (Barrier) item;
            if (!retry.isEmpty()) {
                // cannot promise durability while writes are failing; report and keep the barrier waiting
                retry.addLast(b);
                LockSupport.parkNanos(this, 1_000_000_000L);
                return;
            }
            forceNow();
            if (b.close) {
                try {
                    current.close();
                } catch (IOException e) {
                    log.warn("closing the AOF failed", e);
                }
                stopping = true;
            }
            b.done.complete(null);
        }
    }

    private void append(byte[] chunk) {
        try {
            if (needsTruncate) {                  // cut a partial earlier write before writing anything after it
                current.truncate(goodSize);
                current.position(goodSize);
                needsTruncate = false;
            }
            FileUtil.writeFully(current, ByteBuffer.wrap(chunk));
            goodSize += chunk.length;
            currentSize = goodSize;
            written.addAndGet(chunk.length);
            dirty = true;
            if (!healthy) {
                healthy = true;
                errorLogged = false;
                log.info("AOF writes succeed again; write commands are accepted");
            }
        } catch (IOException e) {
            healthy = false;
            lastError = e.toString();
            if (!errorLogged) {
                log.error("writing the AOF failed ({}); write commands are rejected with MISCONF until it succeeds", e.toString());
                errorLogged = true;
            }
            needsTruncate = true;
            try {
                current.truncate(goodSize);
                current.position(goodSize);
                needsTruncate = false;
            } catch (IOException ignored) {
                // needsTruncate stays set: the next attempt truncates first, or fails again
            }
            retry.addFirst(chunk);
            LockSupport.parkNanos(this, 1_000_000_000L);
        }
    }

    private void maybeFsync() {
        if (dirty && config.appendfsync() == ServerConfig.FsyncPolicy.EVERYSEC
                && System.currentTimeMillis() - lastFsyncMillis >= config.appendfsyncIntervalMillis()) {
            forceNow();
        }
    }

    private void forceNow() {
        try {
            current.force(false);
            dirty = false;
            lastFsyncMillis = System.currentTimeMillis();
        } catch (IOException e) {
            throw new FsyncFailed(e);
        }
    }

    private void rotate(Rotate r) {
        String name = Manifest.incrName(r.generation);
        Path file = dir.resolve(name);
        FileChannel next = null;
        Manifest m = null;
        try {
            forceNow();                         // everything before the rotation point is durable
            next = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            next.force(true);
            FileUtil.fsyncDir(dir);
            m = manifest.withIncr(r.generation, name);
            m.write(dir);
            switchTo(next, m);
            r.done.complete(null);
        } catch (IOException e) {
            if (m != null && next != null && manifestOnDiskNames(name)) {
                // the manifest already names the new file (only its directory fsync failed): the file
                // must not be deleted; finish the switch, then stop, because durability is uncertain
                switchTo(next, m);
                r.done.complete(null);
                throw new FsyncFailed(e);
            }
            if (next != null) {
                try {
                    next.close();
                } catch (IOException ignored) {
                    // cleanup
                }
            }
            FileUtil.deleteQuietly(file);
            log.error("could not start AOF file {}: {}", name, e.toString());
            r.done.completeExceptionally(e);
        }
    }

    private void switchTo(FileChannel next, Manifest m) {
        FileChannel old = current;
        olderIncrBytes += currentSize;
        current = next;
        goodSize = 0;
        currentSize = 0;
        needsTruncate = false;
        manifest = m;
        try {
            old.close();                        // after the switch: a failure here is harmless
        } catch (IOException e) {
            log.warn("closing the previous AOF file failed: {}", e.toString());
        }
    }

    private boolean manifestOnDiskNames(String incr) {
        try {
            return Manifest.read(dir.resolve(Manifest.FILE)).incrs.contains(incr);
        } catch (IOException | RuntimeException e) {
            return true;                        // unknown: never delete a file that might be listed
        }
    }

    private void commit(Commit c) {
        try {
            Path tmp = dir.resolve(c.baseName + ".tmp");
            Path fin = dir.resolve(c.baseName);
            Files.move(tmp, fin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            FileUtil.fsyncDir(dir);
            Manifest old = manifest;
            Manifest m = new Manifest(Math.max(c.generation, old.generation), c.baseName, Collections.singletonList(old.lastIncr()));
            m.write(dir);
            manifest = m;
            olderIncrBytes = 0;
            if (old.base != null && !old.base.equals(c.baseName)) {
                FileUtil.deleteQuietly(dir.resolve(old.base));
            }
            for (String incr : old.incrs) {
                if (!incr.equals(m.lastIncr())) {
                    FileUtil.deleteQuietly(dir.resolve(incr));
                }
            }
            c.done.complete(null);
        } catch (IOException e) {
            log.error("committing base file {} failed: {}", c.baseName, e.toString());
            FileUtil.deleteQuietly(dir.resolve(c.baseName + ".tmp"));   // (the renamed file, if any, is cleaned at start-up)
            c.done.completeExceptionally(e);
        }
    }
}
