package com.jredis.server.persist;

import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32;

/**
 * Writes the chunks of one base file on its own thread, computing the CRC as it goes, so disk I/O
 * never runs on the command thread. One instance per rewrite.
 */
final class SnapshotFileWriter implements SnapshotWriter, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileWriter.class);
    private static final Object FINISH = new Object();
    private static final Object ABORT = new Object();

    private final Path tmpFile;
    private final MpscUnboundedArrayQueue<Object> queue = new MpscUnboundedArrayQueue<>(256);
    private final CompletableFuture<Long> done = new CompletableFuture<>();
    private final AtomicLong written = new AtomicLong();
    private final Thread thread;
    private volatile boolean sleeping;

    private SnapshotFileWriter(Path tmpFile) {
        this.tmpFile = tmpFile;
        this.thread = new Thread(this, "jredis-snapshot-writer");
        this.thread.setDaemon(true);
    }

    static SnapshotFileWriter start(Path tmpFile) {
        SnapshotFileWriter w = new SnapshotFileWriter(tmpFile);
        w.thread.start();
        return w;
    }

    /** Completes with the file size once the file is complete and fsynced. */
    @Override
    public CompletableFuture<Long> done() {
        return done;
    }

    @Override
    public long written() {
        return written.get();
    }

    @Override
    public void submit(byte[] chunk) {
        offer(chunk);
    }

    @Override
    public void submitDeferred(Deferred d) {
        offer(d);
    }

    @Override
    public void finish() {
        offer(FINISH);
    }

    @Override
    public void abort() {
        offer(ABORT);
    }

    private void offer(Object o) {
        queue.offer(o);
        if (sleeping) {
            LockSupport.unpark(thread);
        }
    }

    private static final int DEFERRED_FLUSH = 256 * 1024;

    private static void writeChunk(FileChannel ch, CRC32 crc, byte[] chunk) {
        crc.update(chunk, 0, chunk.length);
        try {
            FileUtil.writeFully(ch, ByteBuffer.wrap(chunk));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void run() {
        CRC32 crc = new CRC32();
        FileChannel ch = null;
        try {
            ch = FileChannel.open(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            while (true) {
                Object item = queue.poll();
                if (item == null) {
                    sleeping = true;
                    if (queue.isEmpty()) {
                        LockSupport.parkNanos(this, 50_000_000L);
                    }
                    sleeping = false;
                    continue;
                }
                if (item == ABORT) {
                    ch.close();
                    ch = null;
                    FileUtil.deleteQuietly(tmpFile);
                    done.cancel(false);
                    return;
                }
                if (item == FINISH) {
                    ByteBuffer trailer = ByteBuffer.allocate(4);
                    trailer.putInt((int) crc.getValue());
                    trailer.flip();
                    FileUtil.writeFully(ch, trailer);
                    ch.force(true);
                    long size = ch.size();
                    ch.close();
                    ch = null;
                    done.complete(size);
                    return;
                }
                if (item instanceof Deferred d) {
                    FileChannel out = ch;
                    ByteSink sink = new ByteSink();
                    d.encode(sink, () -> {
                        if (sink.size() >= DEFERRED_FLUSH) {
                            writeChunk(out, crc, sink.take());
                        }
                    });
                    writeChunk(out, crc, sink.take());
                    continue;
                }
                byte[] chunk = (byte[]) item;
                writeChunk(ch, crc, chunk);
                written.addAndGet(chunk.length);
            }
        } catch (IOException | RuntimeException e) {
            log.error("writing base file {} failed", tmpFile, e);
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // already failing
                }
            }
            FileUtil.deleteQuietly(tmpFile);
            done.completeExceptionally(e);
        }
    }
}
