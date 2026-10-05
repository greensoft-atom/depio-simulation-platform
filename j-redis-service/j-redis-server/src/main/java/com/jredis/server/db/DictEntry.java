package com.jredis.server.db;

/** An element of a {@link Dict}: the key bytes, their cached hash, and the bucket chain link. */
public abstract class DictEntry {

    public final byte[] key;
    final int hash;
    DictEntry next;

    protected DictEntry(byte[] key, int hash) {
        this.key = key;
        this.hash = hash;
    }
}
