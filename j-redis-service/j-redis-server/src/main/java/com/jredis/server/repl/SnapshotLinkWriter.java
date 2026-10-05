package com.jredis.server.repl;

import com.jredis.server.persist.ByteSink;
import com.jredis.server.persist.SnapshotWriter;
import io.netty.buffer.Unpooled;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32;

/**
 * Sends one full sync's image to the replicas sharing it, on a thread of its own
 * (docs/16-replication.md §5.2): the {@code +FULLRESYNC} line and the end mark's announcement, the
 * base format's bytes as the snapshot hands them over, their CRC, and the end mark. Paced by the
 * slowest connection: while one has more than {@link #PACE} bytes unsent the writer waits, and the
 * scan waits for the writer through {@link #written()}.
 */
final class SnapshotLinkWriter implements SnapshotWriter, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotLinkWriter.class);
    private static final long PACE = 4L * 1024 * 1024;
    private static final int DEFERRED_FLUSH = 256 * 1024;
    private static final Object FINISH = new Object();
    private static final Object ABORT = new Object();

    private final List<ReplicaSession> targets;
    private final byte[] header;
    private final byte[] mark;
    private final long stallMillis;
    private final MpscUnboundedArrayQueue<Object> queue = new MpscUnboundedArrayQueue<>(256);
    private final CompletableFuture<Long> done = new CompletableFuture<>();
    private final AtomicLong written = new AtomicLong();
    private final Thread thread;
    private volatile boolean sleeping;
    private long sent;

    /**
     * @param stallMillis how long a connection may take no bytes before it is given up
     */
    SnapshotLinkWriter(List<ReplicaSession> targets, byte[] header, byte[] mark, long stallMillis) {
        this.targets = targets;
        this.header = header;
        this.mark = mark;
        this.stallMillis = stallMillis;
        this.thread = new Thread(this, "jredis-sync-writer");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
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

    @Override
    public long written() {
        return written.get();
    }

    @Override
    public CompletableFuture<Long> done() {
        return done;
    }

    private void offer(Object o) {
        queue.offer(o);
        if (sleeping) {
            LockSupport.unpark(thread);
        }
    }

    /** Hands bytes to every connection still taking them, first waiting for any that is behind. */
    private void send(byte[] b) {
        for (ReplicaSession t : targets) {
            if (t.dropped || t.failed) {
                continue;
            }
            long since = System.currentTimeMillis();
            while (t.client.out.pendingBytes() > PACE && !t.dropped) {
                if (System.currentTimeMillis() - since > stallMillis) {
                    log.warn("replica {} took no bytes for {} ms during its full sync: given up", t.client.out.remoteAddress(), stallMillis);
                    t.failed = true;
                    break;
                }
                LockSupport.parkNanos(1_000_000L);
            }
            if (!t.failed && !t.dropped) {
                t.client.out.write(Unpooled.wrappedBuffer(b));
            }
        }
        sent += b.length;
    }

    private void sendImage(CRC32 crc, byte[] chunk) {
        crc.update(chunk, 0, chunk.length);
        send(chunk);
    }

    @Override
    public void run() {
        CRC32 crc = new CRC32();
        try {
            send(header);
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
                    done.cancel(false);
                    return;
                }
                if (item == FINISH) {
                    send(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
                    send(mark);
                    done.complete(sent);
                    return;
                }
                if (item instanceof Deferred d) {
                    ByteSink sink = new ByteSink();
                    d.encode(sink, () -> {
                        if (sink.size() >= DEFERRED_FLUSH) {
                            sendImage(crc, sink.take());
                        }
                    });
                    sendImage(crc, sink.take());
                    continue;
                }
                byte[] chunk = (byte[]) item;
                sendImage(crc, chunk);
                written.addAndGet(chunk.length);
            }
        } catch (RuntimeException e) {
            log.error("sending a full sync failed", e);
            done.completeExceptionally(e);
        }
    }
}
