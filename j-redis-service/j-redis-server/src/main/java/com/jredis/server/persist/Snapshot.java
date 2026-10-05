package com.jredis.server.persist;

import com.jredis.common.Bytes;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.StreamValue;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/**
 * One point-in-time image of the dataset (docs/08-persistence.md §5): every key that existed at
 * its instant is encoded exactly once, by the background scan or as a pre-image just before its
 * first change, and handed to a {@link SnapshotWriter} in order, a stream from a frozen view to be
 * encoded on the writer's thread (D-33). A rewrite writes one to a base file; a full sync, to a
 * replica's connection (docs/16-replication.md §5.2). Command thread, apart from the writer.
 */
public final class Snapshot {

    /** How far the command thread may run ahead of the writer. */
    private static final long MAX_BACKLOG = 64L * 1024 * 1024;
    private static final int SINK_FLUSH = 256 * 1024;

    private final Engine engine;
    private final SnapshotWriter writer;
    private final Consumer<String> onFailure;
    private final ByteSink sink = new ByteSink();
    private long enqueued;
    private long cursor;
    private long longestRecordNanos;
    private boolean scanned;

    private Snapshot(Engine engine, SnapshotWriter writer, Consumer<String> onFailure) {
        this.engine = engine;
        this.writer = writer;
        this.onFailure = onFailure;
    }

    /**
     * Takes the instant: from here on, a key about to change is written first. The caller has
     * already closed the effects so far into a chunk, so the instant falls between two.
     *
     * @param onFailure told why, when encoding a key fails (the image would be incomplete)
     */
    public static Snapshot start(Engine engine, SnapshotWriter writer, Consumer<String> onFailure) {
        Snapshot s = new Snapshot(engine, writer, onFailure);
        BaseFormat.writeHeader(s.sink, engine.clock().nowMillis());
        engine.db().startSnapshot(s::writeRecord);
        return s;
    }

    private void writeRecord(KeyEntry e) {
        long t0 = System.nanoTime();
        try {
            if (e.type() == KeyEntry.STREAM) {
                // Big and written to all the time: encoded on the writer thread from a view that
                // cannot change, in its place in the file (D-33). Here, only the chunk list is copied.
                BaseFormat.writeRecordHead(sink, e);
                flushSink();
                StreamValue.Frozen f = e.stream().freeze();
                writer.submitDeferred((s, checkpoint) -> BaseFormat.writeStreamBody(s, f, checkpoint));
            } else {
                BaseFormat.writeRecord(sink, e);
            }
        } catch (Throwable t) {                  // e.g. out of memory on a huge key: the image would be incomplete
            onFailure.accept("encoding key " + Bytes.printable(e.key, 64) + " failed: " + t);
            throw t;
        }
        long dt = System.nanoTime() - t0;
        if (dt > longestRecordNanos) {
            longestRecordNanos = dt;
        }
        if (sink.size() >= SINK_FLUSH) {
            flushSink();
        }
    }

    private void flushSink() {
        if (sink.size() > 0) {
            byte[] chunk = sink.take();
            enqueued += chunk.length;
            writer.submit(chunk);
        }
    }

    /** Whether the scan may go on now: not finished, and the writer not too far behind. */
    public boolean canProgress() {
        return !scanned && enqueued - writer.written() < MAX_BACKLOG;
    }

    /**
     * Scans until the deadline, unless the writer is too far behind.
     *
     * @return true once every key is written and the writer told to finish
     */
    public boolean step(long deadlineNanos) {
        if (!canProgress()) {
            return scanned;
        }
        Db db = engine.db();
        do {
            cursor = db.snapshotStep(cursor);
        } while (cursor != 0 && System.nanoTime() - deadlineNanos < 0);
        if (cursor == 0) {
            finishScan();
        }
        return scanned;
    }

    /** Scans to the end on this thread, waiting whenever the writer falls behind (SAVE). */
    public void runToEnd() throws IOException {
        Db db = engine.db();
        while (!scanned) {
            while (enqueued - writer.written() > MAX_BACKLOG) {
                if (writer.done().isDone()) {
                    throw new IOException("the snapshot's writer failed (see the server log)");
                }
                LockSupport.parkNanos(1_000_000L);
            }
            cursor = db.snapshotStep(cursor);
            if (cursor == 0) {
                finishScan();
            }
        }
    }

    private void finishScan() {
        engine.db().endSnapshot();
        BaseFormat.writeEof(sink);
        flushSink();
        writer.finish();
        scanned = true;
    }

    /** Stops taking keys and tells the writer to discard what it has. */
    public void abort() {
        if (!scanned) {
            engine.db().endSnapshot();
        }
        sink.reset();
        writer.abort();
    }

    public boolean scanned() {
        return scanned;
    }

    public SnapshotWriter writer() {
        return writer;
    }

    public long longestRecordNanos() {
        return longestRecordNanos;
    }
}
