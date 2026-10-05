package com.jredis.server.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A stream's chunks ([15 §4](../../../../../../../../docs/15-streams.md), D-33). */
class StreamValueTest {

    private static byte[][] fields(String value) {
        return new byte[][] {"v".getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8)};
    }

    private static void add(StreamValue s, long from, long to, String tag) {
        for (long i = from; i <= to; i++) {
            s.append(i, 0, fields(tag + i));
        }
    }

    private static List<String> all(StreamValue s, boolean rev) {
        List<String> out = new ArrayList<>();
        s.range(StreamId.MIN, StreamId.MAX, rev, -1, (ms, seq, f) -> {
            out.add(ms + ":" + new String(f[1], StandardCharsets.UTF_8));
            return true;
        });
        return out;
    }

    private static List<String> expected(String tag, long from, long to) {
        List<String> out = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            out.add(i + ":" + tag + i);
        }
        return out;
    }

    @Test
    void rangesCrossChunks() {
        StreamValue s = new StreamValue();
        add(s, 1, 1_000, "a");
        assertThat(s.size()).isEqualTo(1_000);
        assertThat(all(s, false)).isEqualTo(expected("a", 1, 1_000));
        List<String> rev = all(s, true);
        assertThat(rev.get(0)).isEqualTo("1000:a1000");
        assertThat(rev).hasSize(1_000);
        List<String> some = new ArrayList<>();
        s.range(new StreamId(250, 0), new StreamId(260, 0), false, 5, (ms, seq, f) -> some.add(Long.toString(ms)) || true);
        assertThat(some).containsExactly("250", "251", "252", "253", "254");
    }

    @Test
    void aCopyAndItsSourceNeverAppendIntoTheSameArrays() {
        // The tail is the one chunk that grows in place; two streams sharing it would overwrite
        // each other's entries.
        StreamValue s = new StreamValue();
        add(s, 1, 300, "a");                               // a full chunk and a growing tail
        StreamValue d = s.copy();
        add(s, 301, 700, "s");
        add(d, 301, 700, "d");
        List<String> ss = expected("a", 1, 300);
        ss.addAll(expected("s", 301, 700));
        List<String> dd = expected("a", 1, 300);
        dd.addAll(expected("d", 301, 700));
        assertThat(all(s, false)).isEqualTo(ss);
        assertThat(all(d, false)).isEqualTo(dd);

        // And after the source loses its whole tail, so that a shared chunk is last again.
        StreamValue e = d.copy();
        for (long i = 513; i <= 700; i++) {
            assertThat(d.delete(i, 0)).isTrue();
        }
        add(d, 800, 810, "x");
        add(e, 800, 810, "y");
        List<String> ee = expected("a", 1, 300);
        ee.addAll(expected("d", 301, 700));
        ee.addAll(expected("y", 800, 810));
        assertThat(all(e, false)).isEqualTo(ee);
        List<String> d2 = expected("a", 1, 300);
        d2.addAll(expected("d", 301, 512));
        d2.addAll(expected("x", 800, 810));
        assertThat(all(d, false)).isEqualTo(d2);
    }

    @Test
    void deletesAndTrimsKeepOrder() {
        StreamValue s = new StreamValue();
        add(s, 1, 600, "a");
        assertThat(s.delete(256, 0)).isTrue();
        assertThat(s.delete(256, 0)).isFalse();
        assertThat(s.delete(9_999, 0)).isFalse();
        assertThat(s.trimMaxLen(500, -1)).isEqualTo(99);
        assertThat(all(s, false).get(0)).isEqualTo("100:a100");
        assertThat(s.trimMinId(new StreamId(300, 0), -1)).isEqualTo(199);   // 100..299, but 256
        assertThat(all(s, false).get(0)).isEqualTo("300:a300");
        assertThat(s.trimMaxLen(0, 10)).as("a limit caps it").isEqualTo(10);
        assertThat(s.size()).isEqualTo(291);
        add(s, 601, 603, "b");
        assertThat(all(s, true).get(0)).isEqualTo("603:b603");
    }

    @Test
    void aFrozenViewKeepsItsEntries() {
        // What a rewrite encodes on another thread while the stream keeps changing (D-33).
        StreamValue s = new StreamValue();
        add(s, 1, 600, "a");
        s.delete(3, 0);
        StreamValue.Frozen f = s.freeze();
        add(s, 601, 900, "b");
        s.delete(10, 0);
        s.delete(599, 0);
        s.delete(600, 0);                                  // the last chunk the view holds, changed
        s.trimMaxLen(200, -1);
        List<String> seen = new ArrayList<>();
        f.forEach((ms, seq, fields) -> seen.add(ms + ":" + new String(fields[1], StandardCharsets.UTF_8)));
        List<String> was = expected("a", 1, 600);
        was.remove("3:a3");
        assertThat(seen).isEqualTo(was);
        assertThat(f.size()).isEqualTo(599);
        assertThat(f.lastMs()).isEqualTo(600);
    }

    @Test
    void groupsAreCountedAndGiveTheirEstimateBack() {
        StreamValue s = new StreamValue();
        add(s, 1, 10, "a");
        long before = s.bytes();
        StreamGroup g = s.createGroup("g".getBytes(StandardCharsets.UTF_8), 0, 0);
        long withGroup = s.bytes();
        assertThat(withGroup).isGreaterThan(before);
        StreamGroup.Consumer c1 = s.consumer(g, "c1".getBytes(StandardCharsets.UTF_8), 0, new boolean[1]);
        long withConsumer = s.bytes();
        for (long i = 1; i <= 5; i++) {
            s.deliver(g, c1, new StreamId(i, 0), 0, 1);
        }
        long withPending = s.bytes();
        assertThat(withPending).isGreaterThan(withConsumer);
        s.ack(g, new StreamId(1, 0));
        s.ack(g, new StreamId(2, 0));
        StreamGroup.Consumer c2 = s.consumer(g, "c2".getBytes(StandardCharsets.UTF_8), 0, new boolean[1]);
        s.deliver(g, c2, new StreamId(3, 0), 0, 2);                  // moved: still one pending entry
        s.deliver(g, c2, new StreamId(1, 0), 0, 1);
        assertThat(s.deleteConsumer(g, "c1".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);
        s.ack(g, new StreamId(3, 0));
        s.ack(g, new StreamId(1, 0));
        assertThat(s.deleteConsumer(g, "c2".getBytes(StandardCharsets.UTF_8))).isZero();
        assertThat(s.bytes()).as("the group alone").isEqualTo(withGroup);
        s.consumer(g, "c3".getBytes(StandardCharsets.UTF_8), 0, new boolean[1]);
        s.deliver(g, g.consumer("c3".getBytes(StandardCharsets.UTF_8)), new StreamId(9, 0), 0, 1);
        assertThat(s.destroyGroup("g".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(s.bytes()).as("everything given back").isEqualTo(before);
    }

    @Test
    void theEstimateComesBackToAnEmptyStreams() {
        long empty = new StreamValue().bytes();
        StreamValue s = new StreamValue();
        add(s, 1, 1_000, "entry-");
        long full = s.bytes();
        assertThat(full - empty).as("1000 entries of 2 fields").isGreaterThan(1_000L * (MemoryEstimator.byteArray(1) + MemoryEstimator.byteArray(10)));
        StreamValue c = s.copy();
        assertThat(c.bytes()).as("a copy counts as much").isEqualTo(full);
        for (long i = 1; i <= 1_000; i += 3) {
            s.delete(i, 0);
        }
        s.trimMaxLen(0, -1);
        assertThat(s.size()).isZero();
        assertThat(s.bytes()).isEqualTo(empty);
        c.trimMinId(StreamId.MAX, -1);
        assertThat(c.bytes()).isEqualTo(empty);
    }
}
