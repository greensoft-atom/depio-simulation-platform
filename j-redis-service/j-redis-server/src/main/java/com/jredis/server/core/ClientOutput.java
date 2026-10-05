package com.jredis.server.core;

import io.netty.buffer.ByteBuf;

/**
 * The transport side of a client, as seen by the engine. The network implementation writes to a
 * Netty channel; the loader's implementation discards replies.
 */
public interface ClientOutput {

    /** Sends a buffer of reply bytes; takes ownership of the buffer. */
    void write(ByteBuf replies);

    /** Closes the connection after pending writes have been flushed (QUIT, protocol errors). */
    void close();

    /** Closes the connection immediately (CLIENT KILL, output limits, idle timeout). */
    void abort();

    /** Bytes handed to the transport but not yet written to the socket. */
    long pendingBytes();

    /**
     * Called by the engine after executing one command from this client, with the command's size,
     * so the transport can resume reading if it paused for backpressure.
     */
    void commandDone(long bytes);

    /** Wall-clock time of the last byte read from or written to the connection (0 if unknown). */
    default long lastIoMillis() {
        return 0;
    }

    String remoteAddress();

    String localAddress();

    /** The engine's state for this connection. Engine-thread only. */
    Client client();

    void client(Client c);
}
