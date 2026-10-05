package com.jredis.server.db;

import java.security.SecureRandom;

/**
 * SipHash-1-2 with a random 128-bit key, the hash Redis uses for its dictionaries. Keys include
 * user-chosen text, and a keyed cryptographic hash stops anyone from crafting collisions that turn
 * lookups into long chain scans (hash flooding).
 */
public final class SipHash {

    private final long k0;
    private final long k1;
    private final int compressionRounds;
    private final int finalizationRounds;

    public SipHash(long k0, long k1) {
        this(k0, k1, 1, 2);
    }

    /** Package-private: tests check the implementation against the SipHash-2-4 reference vector. */
    SipHash(long k0, long k1, int compressionRounds, int finalizationRounds) {
        this.k0 = k0;
        this.k1 = k1;
        this.compressionRounds = compressionRounds;
        this.finalizationRounds = finalizationRounds;
    }

    public static SipHash random() {
        SecureRandom r = new SecureRandom();
        return new SipHash(r.nextLong(), r.nextLong());
    }

    public int hash32(byte[] data) {
        long h = hash64(data);
        return (int) (h ^ (h >>> 32));
    }

    public long hash64(byte[] data) {
        long v0 = k0 ^ 0x736f6d6570736575L;
        long v1 = k1 ^ 0x646f72616e646f6dL;
        long v2 = k0 ^ 0x6c7967656e657261L;
        long v3 = k1 ^ 0x7465646279746573L;
        int len = data.length;
        int end = len - (len % 8);
        for (int i = 0; i < end; i += 8) {
            long m = (data[i] & 0xffL)
                    | (data[i + 1] & 0xffL) << 8
                    | (data[i + 2] & 0xffL) << 16
                    | (data[i + 3] & 0xffL) << 24
                    | (data[i + 4] & 0xffL) << 32
                    | (data[i + 5] & 0xffL) << 40
                    | (data[i + 6] & 0xffL) << 48
                    | (data[i + 7] & 0xffL) << 56;
            v3 ^= m;
            for (int r = 0; r < compressionRounds; r++) {
                v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
                v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
                v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
                v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
            }
            v0 ^= m;
        }
        long b = ((long) len & 0xff) << 56;
        for (int i = end, shift = 0; i < len; i++, shift += 8) {
            b |= (data[i] & 0xffL) << shift;
        }
        v3 ^= b;
        for (int r = 0; r < compressionRounds; r++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        v0 ^= b;
        v2 ^= 0xff;
        for (int r = 0; r < finalizationRounds; r++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }
}
