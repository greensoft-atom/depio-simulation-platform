package com.jredis.tests;

import org.junit.jupiter.api.Test;

/** Consumer groups ([15 §2–§3](../../../../../../../docs/15-streams.md)): delivery, pending, claims. */
class StreamGroupsTest extends EmbeddedTest {

    private void fill(String key, int n) {
        for (int i = 1; i <= n; i++) {
            call("XADD " + key + " " + i + " f v" + i);
        }
    }

    @Test
    void createAndManage() {
        expectError("XGROUP CREATE s g $", "ERR The XGROUP subcommand requires the key to exist");
        expect("XGROUP CREATE s g $ MKSTREAM", "+OK");
        expect("TYPE s", "+stream");
        expectError("XGROUP CREATE s g 0", "BUSYGROUP Consumer Group name already exists");
        expect("XADD s 5 f v5", "5-0");
        expect("XGROUP CREATE s h $", "+OK");                          // $ is the last ID now
        expect("XINFO GROUPS s", "[[name, g, consumers, :0, pending, :0, last-delivered-id, 0-0, lag, :1], "
                + "[name, h, consumers, :0, pending, :0, last-delivered-id, 5-0, lag, :0]]");
        expect("XGROUP SETID s h 0", "+OK");
        expect("XGROUP SETID s h $", "+OK");
        expectError("XGROUP SETID s nope 0", "NOGROUP No such consumer group 'nope' for key name 's'");
        expect("XGROUP CREATECONSUMER s g c1", ":1");
        expect("XGROUP CREATECONSUMER s g c1", ":0");
        expect("XGROUP DELCONSUMER s g c1", ":0");                     // it had nothing pending
        expect("XGROUP DESTROY s h", ":1");
        expect("XGROUP DESTROY s h", ":0");
        expectError("XGROUP DESTROY missing g", "ERR The XGROUP subcommand requires the key to exist");
        expectError("XGROUP CREATE s x nope", "ERR Invalid stream ID specified as stream command argument");
        expectError("XGROUP NOPE s g", "ERR unknown subcommand");
        expect("SET str x", "+OK");
        expectError("XGROUP CREATE str g $", "WRONGTYPE");
    }

    @Test
    void readDeliverAcknowledge() {
        fill("s", 5);
        expect("XGROUP CREATE s g 0", "+OK");
        expect("XREADGROUP GROUP g c1 COUNT 2 STREAMS s >",
                "[[s, [[1-0, [f, v1]], [2-0, [f, v2]]]]]");
        clock.advance(100);
        expect("XREADGROUP GROUP g c2 STREAMS s >",
                "[[s, [[3-0, [f, v3]], [4-0, [f, v4]], [5-0, [f, v5]]]]]");
        expect("XREADGROUP GROUP g c2 STREAMS s >", "(nil)");            // nothing new
        expect("XPENDING s g", "[:5, 1-0, 5-0, [[c1, 2], [c2, 3]]]");
        clock.advance(50);
        expect("XPENDING s g - + 10", "[[1-0, c1, :150, :1], [2-0, c1, :150, :1], [3-0, c2, :50, :1], [4-0, c2, :50, :1], [5-0, c2, :50, :1]]");
        expect("XPENDING s g IDLE 100 - + 10", "[[1-0, c1, :150, :1], [2-0, c1, :150, :1]]");
        expect("XPENDING s g - + 10 c2", "[[3-0, c2, :50, :1], [4-0, c2, :50, :1], [5-0, c2, :50, :1]]");
        expect("XACK s g 1 3 9", ":2");
        expect("XACK s g 1", ":0");
        expect("XACK s nope 2", ":0");
        expect("XPENDING s g", "[:3, 2-0, 5-0, [[c1, 1], [c2, 2]]]");

        // A consumer's own history: its pending entries, a deleted one as a nil.
        expect("XDEL s 4", ":1");
        expect("XREADGROUP GROUP g c2 STREAMS s 0", "[[s, [[4-0, (nil)], [5-0, [f, v5]]]]]");
        expect("XREADGROUP GROUP g c2 STREAMS s 4", "[[s, [[5-0, [f, v5]]]]]");       // after the ID given
        expect("XREADGROUP GROUP g c2 COUNT 1 STREAMS s 0", "[[s, [[4-0, (nil)]]]]");
        expect("XREADGROUP GROUP g c3 STREAMS s 0", "[[s, []]]");       // a new consumer, nothing of its own
        expect("XINFO CONSUMERS s g", "[[name, c1, pending, :1, idle, :150, inactive, :150], "
                + "[name, c2, pending, :2, idle, :0, inactive, :50], [name, c3, pending, :0, idle, :0, inactive, :-1]]");
        expect("XINFO GROUPS s", "[[name, g, consumers, :3, pending, :3, last-delivered-id, 5-0, lag, :0]]");
        expect("XADD s 6 f v6", "6-0");
        expect("XADD s 7 f v7", "7-0");
        expect("XINFO GROUPS s", "[[name, g, consumers, :3, pending, :3, last-delivered-id, 5-0, lag, :2]]");
        expect("XREADGROUP GROUP g c1 NOACK STREAMS s >", "[[s, [[6-0, [f, v6]], [7-0, [f, v7]]]]]");
        expect("XPENDING s g", "[:3, 2-0, 5-0, [[c1, 1], [c2, 2]]]");    // NOACK: delivered, not pending
        expect("XGROUP DELCONSUMER s g c2", ":2");
        expect("XPENDING s g", "[:1, 2-0, 2-0, [[c1, 1]]]");

        expectError("XREADGROUP GROUP nope c STREAMS s >", "NOGROUP No such key 's' or consumer group 'nope' in XREADGROUP with GROUP option");
        expectError("XREADGROUP GROUP g c STREAMS missing >", "NOGROUP No such key 'missing' or consumer group 'g' in XREADGROUP with GROUP option");
        expectError("XREADGROUP GROUP g c STREAMS s", "ERR wrong number of arguments for 'xreadgroup' command");
        expectError("XREADGROUP GROUP g c STREAMS s t >", "ERR Unbalanced 'xreadgroup' list of streams");
        expectError("XREADGROUP GROUP g c STREAMS s $", "ERR The $ ID is meaningless in the context of XREADGROUP");
        expectError("XPENDING s nope", "NOGROUP No such key 's' or consumer group 'nope'");
        expectError("XPENDING e g", "NOGROUP No such key 'e' or consumer group 'g'");
    }

    @Test
    void severalStreamsAtOnce() {
        fill("a", 2);
        fill("b", 1);
        expect("XGROUP CREATE a g 0", "+OK");
        expect("XGROUP CREATE b g $", "+OK");
        expect("XREADGROUP GROUP g c COUNT 1 STREAMS a b > >", "[[a, [[1-0, [f, v1]]]]]");   // b had nothing new
        expect("XADD b 2 f w", "2-0");
        expect("XREADGROUP GROUP g c STREAMS a b > >", "[[a, [[2-0, [f, v2]]]], [b, [[2-0, [f, w]]]]]");
    }

    @Test
    void claims() {
        fill("s", 4);
        expect("XGROUP CREATE s g 0", "+OK");
        expect("XREADGROUP GROUP g c1 STREAMS s >", "[[s, [[1-0, [f, v1]], [2-0, [f, v2]], [3-0, [f, v3]], [4-0, [f, v4]]]]]");
        clock.advance(1_000);
        expect("XCLAIM s g c2 5000 1", "[]");                            // not idle long enough
        expect("XCLAIM s g c2 500 1 2 99", "[[1-0, [f, v1]], [2-0, [f, v2]]]");
        expect("XPENDING s g - + 10", "[[1-0, c2, :0, :2], [2-0, c2, :0, :2], [3-0, c1, :1000, :1], [4-0, c1, :1000, :1]]");
        expect("XCLAIM s g c3 0 3 JUSTID", "[3-0]");                     // JUSTID: no delivery counted
        expect("XCLAIM s g c3 0 4 IDLE 300 RETRYCOUNT 7 JUSTID", "[4-0]");
        expect("XPENDING s g - + 10 c3", "[[3-0, c3, :0, :1], [4-0, c3, :300, :7]]");
        expect("XCLAIM s g c3 0 1 TIME " + (T0 + 900), "[[1-0, [f, v1]]]");
        expect("XPENDING s g - + 1", "[[1-0, c3, :100, :3]]");
        expect("XADD s 5 f v5", "5-0");
        expect("XCLAIM s g c4 0 5", "[]");                               // never delivered
        expect("XCLAIM s g c4 0 5 FORCE LASTID 5", "[[5-0, [f, v5]]]");
        expect("XINFO GROUPS s", "[[name, g, consumers, :4, pending, :5, last-delivered-id, 5-0, lag, :0]]");
        expect("XDEL s 2", ":1");
        expect("XCLAIM s g c4 0 2", "[]");                               // gone from the stream: dropped
        expect("XPENDING s g", "[:4, 1-0, 5-0, [[c3, 3], [c4, 1]]]");
        expectError("XCLAIM s nope c 0 1", "NOGROUP No such key 's' or consumer group 'nope'");
        expectError("XCLAIM s g c x 1", "ERR Invalid min-idle-time argument for XCLAIM");
    }

    @Test
    void autoClaim() {
        fill("s", 6);
        expect("XGROUP CREATE s g 0", "+OK");
        expect("XREADGROUP GROUP g c1 STREAMS s >", "[[s, [[1-0, [f, v1]], [2-0, [f, v2]], [3-0, [f, v3]], [4-0, [f, v4]], [5-0, [f, v5]], [6-0, [f, v6]]]]]");
        clock.advance(60_000);
        expect("XDEL s 2", ":1");
        // Two claimed, the deleted one reported and dropped, and the cursor where the scan stopped.
        expect("XAUTOCLAIM s g c2 60000 0 COUNT 2", "[4-0, [[1-0, [f, v1]], [3-0, [f, v3]]], [2-0]]");
        expect("XAUTOCLAIM s g c2 60000 0 COUNT 10 JUSTID", "[0-0, [4-0, 5-0, 6-0], []]");
        expect("XPENDING s g", "[:5, 1-0, 6-0, [[c2, 5]]]");
        expect("XAUTOCLAIM s g c3 60000 0", "[0-0, [], []]");           // just claimed: not idle
        expectError("XAUTOCLAIM s nope c 0 0", "NOGROUP No such key 's' or consumer group 'nope'");
    }

    @Test
    void infoStream() {
        expectError("XINFO STREAM missing", "ERR no such key");
        fill("s", 3);
        expect("XGROUP CREATE s g 0", "+OK");
        expect("XINFO STREAM s", "[length, :3, radix-tree-keys, :1, radix-tree-nodes, :1, last-generated-id, 3-0, groups, :1, "
                + "first-entry, [1-0, [f, v1]], last-entry, [3-0, [f, v3]]]");
        expect("XTRIM s MAXLEN 0", ":3");
        expect("XINFO STREAM s", "[length, :0, radix-tree-keys, :0, radix-tree-nodes, :0, last-generated-id, 3-0, groups, :1, "
                + "first-entry, (nil), last-entry, (nil)]");
    }

    @Test
    void aCopyTakesItsGroups() {
        fill("s", 2);
        expect("XGROUP CREATE s g 0", "+OK");
        expect("XREADGROUP GROUP g c STREAMS s >", "[[s, [[1-0, [f, v1]], [2-0, [f, v2]]]]]");
        expect("COPY s d", ":1");
        expect("XACK s g 1", ":1");
        expect("XPENDING d g", "[:2, 1-0, 2-0, [[c, 2]]]");
        expect("XPENDING s g", "[:1, 2-0, 2-0, [[c, 1]]]");
    }
}
