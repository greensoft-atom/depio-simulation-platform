package com.jredis.server.blocking;

/** Why and until when a client is blocked. */
public final class BlockState {

    public static final int BLPOP = 1;
    public static final int BRPOP = 2;
    public static final int BLMOVE = 3;
    public static final int BZPOPMIN = 4;
    public static final int BZPOPMAX = 5;
    public static final int XREAD = 6;
    public static final int XREADGROUP = 7;
    /** WAIT: no keys; its replication decides when it ends (docs/16-replication.md §8). */
    public static final int WAIT = 8;

    public final int op;
    public final byte[][] keys;
    /** Absolute wall-clock deadline in ms; 0 = wait forever. */
    public final long timeoutAtMillis;
    /** BLMOVE only. */
    public final byte[] destination;
    public final boolean fromLeft;
    public final boolean toLeft;
    /** XREAD: per key, the ID after which it waits. */
    public final com.jredis.server.db.StreamId[] after;
    /** XREADGROUP: its group and consumer, and whether deliveries go unacknowledged. */
    public final byte[] group;
    public final byte[] consumer;
    public final boolean noack;
    /** XREAD and XREADGROUP: at most this many entries (negative: all). */
    public final long count;

    public BlockState(int op, byte[][] keys, long timeoutAtMillis, byte[] destination, boolean fromLeft, boolean toLeft) {
        this(op, keys, timeoutAtMillis, destination, fromLeft, toLeft, null, null, null, false, -1);
    }

    private BlockState(int op, byte[][] keys, long timeoutAtMillis, byte[] destination, boolean fromLeft, boolean toLeft,
                       com.jredis.server.db.StreamId[] after, byte[] group, byte[] consumer, boolean noack, long count) {
        this.op = op;
        this.keys = keys;
        this.timeoutAtMillis = timeoutAtMillis;
        this.destination = destination;
        this.fromLeft = fromLeft;
        this.toLeft = toLeft;
        this.after = after;
        this.group = group;
        this.consumer = consumer;
        this.noack = noack;
        this.count = count;
    }

    /** A client in WAIT: blocked, so what it pipelined waits, but on no key and with no timeout here. */
    public static BlockState waitForReplicas() {
        return new BlockState(WAIT, new byte[0][], 0, null, false, false);
    }

    public static BlockState xread(byte[][] keys, long timeoutAtMillis, com.jredis.server.db.StreamId[] after, long count) {
        return new BlockState(XREAD, keys, timeoutAtMillis, null, false, false, after, null, null, false, count);
    }

    public static BlockState xreadgroup(byte[][] keys, long timeoutAtMillis, byte[] group, byte[] consumer, boolean noack, long count) {
        return new BlockState(XREADGROUP, keys, timeoutAtMillis, null, false, false, null, group, consumer, noack, count);
    }

    /** A stream reader: a key it waits on may be missing and still serve it later, or tell it why not. */
    public boolean readsAStream() {
        return op == XREAD || op == XREADGROUP;
    }
}
