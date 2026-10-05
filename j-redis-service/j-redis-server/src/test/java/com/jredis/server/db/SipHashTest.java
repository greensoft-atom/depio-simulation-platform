package com.jredis.server.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SipHashTest {

    private static final long K0 = 0x0706050403020100L;   // key bytes 00..0f, little-endian
    private static final long K1 = 0x0f0e0d0c0b0a0908L;

    private static byte[] seq(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    /** Reference vectors from the SipHash paper / reference implementation (SipHash-2-4). */
    @Test
    void sipHash24ReferenceVectors() {
        SipHash h = new SipHash(K0, K1, 2, 4);
        assertThat(h.hash64(seq(0))).isEqualTo(0x726fdb47dd0e0e31L);
        assertThat(h.hash64(seq(15))).isEqualTo(0xa129ca6149be45e5L);
    }

    @Test
    void keyedAndDeterministic() {
        SipHash a = new SipHash(1, 2);
        SipHash b = new SipHash(1, 3);
        byte[] data = "user:42".getBytes();
        assertThat(a.hash64(data)).isEqualTo(new SipHash(1, 2).hash64(data));
        assertThat(a.hash64(data)).isNotEqualTo(b.hash64(data));
        for (int n = 0; n < 40; n++) {             // every tail length
            assertThat(a.hash64(seq(n))).isEqualTo(a.hash64(seq(n)));
        }
    }
}
