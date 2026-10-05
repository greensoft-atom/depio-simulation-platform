package com.backend.handoff;

import java.security.SecureRandom;

/**
 * ULID: 48 bits of millisecond timestamp then 80 bits of randomness, Crockford base32,
 * 26 characters (docs detailed-design/05-worker-and-events.md §2).
 *
 * Chosen over a random UUID because it sorts by creation time. The identifier is a unique
 * key in MySQL, and a time-ordered key appends to the right-hand edge of the B-tree instead
 * of writing into a random page every time — which is the difference between an index that
 * stays in the buffer pool and one that does not.
 */
public final class Ulid {

    /** Crockford base32: no I, L, O or U, so a value read aloud cannot be transcribed wrong. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate() {
        return generate(System.currentTimeMillis());
    }

    static String generate(long epochMillis) {
        char[] out = new char[26];

        // 10 characters of timestamp, most significant first.
        long time = epochMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        // 16 characters of randomness, from 80 bits.
        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);
        long high = 0;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (entropy[i] & 0xFFL);
        }
        long low = 0;
        for (int i = 5; i < 10; i++) {
            low = (low << 8) | (entropy[i] & 0xFFL);
        }
        for (int i = 7; i >= 0; i--) {
            out[10 + i] = ALPHABET[(int) (high & 0x1F)];
            high >>>= 5;
        }
        for (int i = 7; i >= 0; i--) {
            out[18 + i] = ALPHABET[(int) (low & 0x1F)];
            low >>>= 5;
        }
        return new String(out);
    }

    private Ulid() {
    }
}
