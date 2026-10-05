package com.jredis.server.db;

/**
 * A key in the keyspace: its type, value, TTL and bookkeeping. 56 bytes with compressed references.
 *
 * <p>Value by type: STRING → {@code byte[]}; HASH → {@link HashValue}; LIST → {@link ListValue};
 * SET → {@link SetValue}; ZSET → {@link ZSetValue}; STREAM → {@link StreamValue}.
 */
public final class KeyEntry extends DictEntry {

    public static final byte STRING = 0;
    public static final byte HASH = 1;
    public static final byte LIST = 2;
    public static final byte SET = 3;
    public static final byte ZSET = 4;
    public static final byte STREAM = 5;

    static final int NOT_REGISTERED = Integer.MIN_VALUE;

    byte type;
    boolean alive = true;
    Object value;
    long expireAt = -1;
    int expireBucket = NOT_REGISTERED;   // the ExpiryBuckets bucket holding this entry, if any
    int expireSlot;                      // its index in that bucket, for O(1) unregistration
    int snapEpoch;

    KeyEntry(byte[] key, int hash, byte type, Object value) {
        super(key, hash);
        this.type = type;
        this.value = value;
    }

    public byte type() {
        return type;
    }

    public Object value() {
        return value;
    }

    public byte[] stringValue() {
        return (byte[]) value;
    }

    public HashValue hash() {
        return (HashValue) value;
    }

    public ListValue list() {
        return (ListValue) value;
    }

    public SetValue set() {
        return (SetValue) value;
    }

    public ZSetValue zset() {
        return (ZSetValue) value;
    }

    public StreamValue stream() {
        return (StreamValue) value;
    }

    /** Absolute expiry in epoch milliseconds, or -1. */
    public long expireAt() {
        return expireAt;
    }

    public boolean isAlive() {
        return alive;
    }

    public static String typeName(byte type) {
        switch (type) {
            case STRING: return "string";
            case HASH: return "hash";
            case LIST: return "list";
            case SET: return "set";
            case ZSET: return "zset";
            case STREAM: return "stream";
            default: return "none";
        }
    }
}
