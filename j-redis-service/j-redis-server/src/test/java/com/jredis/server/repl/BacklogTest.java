package com.jredis.server.repl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The ring of recent stream bytes a replica continues from ([16 §7](../../../../../../../../docs/16-replication.md)). */
class BacklogTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.US_ASCII);
    }

    @Test
    void holdsTheLastBytesAndCountsThemAll() {
        Backlog log = new Backlog(16);
        assertThat(log.offset()).isZero();
        assertThat(log.firstOffset()).isZero();
        assertThat(s(log.from(0))).isEmpty();

        log.append(b("0123456789"));
        assertThat(log.offset()).isEqualTo(10);
        assertThat(log.firstOffset()).isZero();
        assertThat(s(log.from(0))).isEqualTo("0123456789");
        assertThat(s(log.from(7))).isEqualTo("789");
        assertThat(s(log.from(10))).as("caught up: nothing to send").isEmpty();
        assertThat(log.from(11)).as("ahead of the stream").isNull();

        log.append(b("abcdefghij"));                    // wraps: 20 written, 16 held
        assertThat(log.offset()).isEqualTo(20);
        assertThat(log.firstOffset()).isEqualTo(4);
        assertThat(log.from(3)).as("no longer held").isNull();
        assertThat(s(log.from(4))).isEqualTo("456789abcdefghij");
        assertThat(s(log.from(12))).isEqualTo("cdefghij");
    }

    @Test
    void aChunkLargerThanTheRingKeepsItsEnd() {
        Backlog log = new Backlog(8);
        log.append(b("xy"));
        log.append(b("ABCDEFGHIJKLMNOP"));             // 16 bytes into 8
        assertThat(log.offset()).isEqualTo(18);
        assertThat(log.firstOffset()).isEqualTo(10);
        assertThat(s(log.from(10))).isEqualTo("IJKLMNOP");
        log.append(b("q"));
        assertThat(s(log.from(11))).isEqualTo("JKLMNOPq");
    }

    @Test
    void aPartOfAnArray() {
        Backlog log = new Backlog(8);
        log.append(b("--abc--"), 2, 3);
        assertThat(s(log.from(0))).isEqualTo("abc");
    }
}
