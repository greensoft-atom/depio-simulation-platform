package com.jredis.server.core;

import com.jredis.server.blocking.BlockState;
import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the engine knows about one connection. Owned by the command thread: no other thread
 * reads or writes these fields.
 */
public final class Client {

    public final long id;
    public final ClientOutput out;
    public final long createdAtMillis;

    public String name = "";
    public String libName = "";
    public String libVersion = "";
    public boolean authenticated;
    public long lastInteractionMillis;
    public long commandsProcessed;
    public String lastCommand = "NULL";

    /** Pending reply bytes for the current batch; handed to {@link #out} at the end of the batch. */
    public ByteBuf reply;
    public boolean touched;
    public boolean closeAfterReply;               // closing: see Engine.closeAfterReplies / killClient
    public boolean closed;

    // transactions
    public boolean inMulti;
    public boolean multiAborted;
    public int multiFlags;                        // WRITE/DENYOOM of the queued commands, checked again at EXEC
    public boolean dirtyCas;
    public final ArrayList<byte[][]> multiQueue = new ArrayList<>();
    public final List<byte[]> watchedKeys = new ArrayList<>();

    // blocking
    public BlockState block;
    public final ArrayDeque<byte[][]> deferred = new ArrayDeque<>();

    // pub/sub
    public final Set<ByteKey> channels = new LinkedHashSet<>();
    public final Set<ByteKey> patterns = new LinkedHashSet<>();

    // output limits
    public long softLimitSinceMillis = -1;

    // replication: the port a replica says it listens on, and, once it asked to sync, its state
    public int replListeningPort;
    public com.jredis.server.repl.ReplicaSession replica;

    public Client(long id, ClientOutput out, long nowMillis) {
        this.id = id;
        this.out = out;
        this.createdAtMillis = nowMillis;
        this.lastInteractionMillis = nowMillis;
    }

    public boolean isBlocked() {
        return block != null;
    }

    public boolean inPubSubMode() {
        return !channels.isEmpty() || !patterns.isEmpty();
    }

    public int subscriptionCount() {
        return channels.size() + patterns.size();
    }
}
