package com.jredis.server.persist;

import java.util.concurrent.CompletableFuture;

/**
 * Where a {@link Snapshot}'s bytes go, on a thread of its own: a base file for a rewrite, or a
 * replica's connection for a full sync (docs/16-replication.md §5.2). The snapshot hands it the
 * base format's bytes in order, ending with the EOF record; the writer adds the CRC trailer.
 */
public interface SnapshotWriter {

    /** Encoded records, from the command thread. */
    void submit(byte[] chunk);

    /**
     * A record to encode on the writer's thread, in its place among the chunks (D-33). Not
     * counted in {@link #written()}: that paces the command thread's chunks, and a deferred
     * record holds no bytes until it is encoded.
     */
    void submitDeferred(Deferred d);

    /** Everything is submitted: add the trailer and complete {@link #done()}. */
    void finish();

    /** Stop and discard; {@link #done()} is cancelled. */
    void abort();

    /** Bytes of submitted chunks written so far. */
    long written();

    /** Completes with the bytes written once finished, exceptionally if writing failed. */
    CompletableFuture<Long> done();

    /** A record this writer encodes itself. */
    interface Deferred {
        void encode(ByteSink sink, Runnable checkpoint);
    }
}
