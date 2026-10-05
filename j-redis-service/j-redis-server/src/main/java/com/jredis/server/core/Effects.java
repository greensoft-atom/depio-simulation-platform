package com.jredis.server.core;

import com.jredis.common.RespWriter;
import com.jredis.server.repl.Backlog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The effects of commands, encoded once per batch (docs/08-persistence.md §3,
 * docs/16-replication.md §4, D-35). Each batch's effects become one immutable chunk, handed to the
 * AOF when persistence is on and to the replication backlog when there is one; with neither,
 * nothing is encoded. Command thread only.
 */
public final class Effects {

    private static final byte[] MULTI = "MULTI".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXEC = "EXEC".getBytes(StandardCharsets.US_ASCII);

    private final Engine engine;
    private final ByteBuf batch = Unpooled.buffer(64 * 1024);
    private int commandMark;
    private int txDepth;
    private final List<byte[][]> txBuffer = new ArrayList<>();

    Effects(Engine engine) {
        this.engine = engine;
    }

    private boolean wanted() {
        return engine.persistence().enabled() || engine.backlog() != null;
    }

    /** Logs a command's effect (buffered until the end of a transaction when inside one). */
    public void propagate(byte[][] effect) {
        if (!wanted()) {
            return;
        }
        if (txDepth > 0) {
            txBuffer.add(effect);
        } else {
            RespWriter.command(batch, effect);
        }
    }

    public void beginTransaction() {
        txDepth++;
    }

    public void endTransaction() {
        if (--txDepth == 0 && !txBuffer.isEmpty()) {
            RespWriter.command(batch, new byte[][]{MULTI});
            for (byte[][] e : txBuffer) {
                RespWriter.command(batch, e);
            }
            RespWriter.command(batch, new byte[][]{EXEC});
            txBuffer.clear();
        }
    }

    /** Marks the start of a top-level command, so its effects can be dropped on fail-stop. */
    public void markCommandStart() {
        commandMark = batch.writerIndex();
    }

    /** Drops effects logged since {@link #markCommandStart} (and any open transaction). */
    public void discardSinceMark() {
        batch.writerIndex(Math.min(commandMark, batch.writerIndex()));
        txBuffer.clear();
        txDepth = 0;
    }

    /** Closes the effects so far into one chunk and hands it on: at the end of a batch, or at a snapshot's instant. */
    public void flush() {
        int n = batch.readableBytes();
        if (n > 0) {
            byte[] chunk = new byte[n];
            batch.readBytes(chunk);
            if (engine.persistence().enabled()) {
                engine.persistence().append(chunk);
            }
            Backlog b = engine.backlog();
            if (b != null) {
                b.append(chunk);
                engine.replication().sendToOnline(chunk);
            }
        }
        batch.clear();
        commandMark = 0;
    }
}
