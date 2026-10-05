package com.jredis.server.core;

import com.jredis.server.db.SipHash;

import java.util.Arrays;

/**
 * A byte[] usable as a key in the registries (channels, watched keys, blocked keys). Hashed with a
 * randomly keyed SipHash, like the keyspace: names can contain user-chosen text, and with
 * Arrays.hashCode anyone could craft thousands of colliding names and slow every lookup down.
 */
public final class ByteKey {

    private static final SipHash HASHER = SipHash.random();

    public final byte[] bytes;
    private final int hash;

    public ByteKey(byte[] bytes) {
        this.bytes = bytes;
        this.hash = HASHER.hash32(bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ByteKey && Arrays.equals(bytes, ((ByteKey) o).bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
