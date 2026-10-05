package com.jredis.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** XREAD, and XREAD and XREADGROUP with BLOCK ([15 §6](../../../../../../../docs/15-streams.md)). */
class StreamBlockingTest {

    private TcpServer server;
    private final List<RawClient> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        server = TcpServer.start(c -> { });
    }

    @AfterEach
    void stop() {
        clients.forEach(RawClient::close);
        server.close();
        assertThat(server.fatal.get()).isNull();
    }

    private RawClient connect() {
        RawClient c = server.connect();
        clients.add(c);
        return c;
    }

    private static void awaitBlocked(RawClient observer, int n) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!observer.call("INFO clients").contains("blocked_clients:" + n + "\r\n")) {
            assertThat(System.currentTimeMillis()).as("waiting for %d blocked clients", n).isLessThan(deadline);
            Thread.yield();
        }
    }

    @Test
    void xreadWithoutBlock() {
        RawClient c = connect();
        assertThat(c.call("XADD s 1 f a")).isEqualTo("1-0");
        assertThat(c.call("XADD s 2 f b")).isEqualTo("2-0");
        assertThat(c.call("XREAD STREAMS s 0")).isEqualTo("[[s, [[1-0, [f, a]], [2-0, [f, b]]]]]");
        assertThat(c.call("XREAD COUNT 1 STREAMS s missing 1 0")).isEqualTo("[[s, [[2-0, [f, b]]]]]");
        assertThat(c.call("XREAD STREAMS s $")).isEqualTo("(nil)");
        assertThat(c.call("XREAD STREAMS s >")).startsWith("-ERR The > ID can be specified only when calling XREADGROUP");
        assertThat(c.call("XREAD STREAMS s t 0")).startsWith("-ERR Unbalanced 'xread' list of streams");
        assertThat(c.call("XREAD BLOCK -1 STREAMS s 0")).startsWith("-ERR timeout is negative");
        assertThat(c.call("SET str x")).isEqualTo("+OK");
        assertThat(c.call("XREAD STREAMS str 0")).startsWith("-WRONGTYPE");
    }

    @Test
    void xreadWaitsForTheNextEntry() {
        RawClient w = connect();
        RawClient p = connect();
        assertThat(p.call("XADD s 1 f a")).isEqualTo("1-0");
        assertThat(p.call("XADD s 2 f a")).isEqualTo("2-0");
        w.send("XREAD BLOCK 0 STREAMS a s $ $");                                 // each waits after its own last ID
        awaitBlocked(p, 1);
        assertThat(p.call("XDEL s 1")).isEqualTo(":1");                         // a change, and not a new entry
        awaitBlocked(p, 1);
        assertThat(p.call("XADD s 3 f b")).isEqualTo("3-0");
        assertThat(R.render(w.read())).isEqualTo("[[s, [[3-0, [f, b]]]]]");    // only the stream that had one
        w.send("XREAD BLOCK 0 STREAMS new 0");                                    // a stream not there yet
        awaitBlocked(p, 1);
        assertThat(p.call("XADD new 7 f c")).isEqualTo("7-0");
        assertThat(R.render(w.read())).isEqualTo("[[new, [[7-0, [f, c]]]]]");
    }

    @Test
    void groupReadersAreServedInArrivalOrderOneEntryEach() {
        RawClient w1 = connect();
        RawClient w2 = connect();
        RawClient p = connect();
        assertThat(p.call("XGROUP CREATE s g $ MKSTREAM")).isEqualTo("+OK");
        w1.send("XREADGROUP GROUP g c1 COUNT 1 BLOCK 0 STREAMS s >");
        awaitBlocked(p, 1);
        w2.send("XREADGROUP GROUP g c2 COUNT 1 BLOCK 0 STREAMS s >");
        awaitBlocked(p, 2);
        assertThat(p.call("XADD s 1 f a")).isEqualTo("1-0");
        assertThat(R.render(w1.read())).isEqualTo("[[s, [[1-0, [f, a]]]]]");
        awaitBlocked(p, 1);                                                       // the second saw nothing left
        assertThat(p.call("XACK s g 1")).isEqualTo(":1");                         // a change, and not a new entry
        awaitBlocked(p, 1);
        assertThat(p.call("XADD s 2 f b")).isEqualTo("2-0");
        assertThat(R.render(w2.read())).isEqualTo("[[s, [[2-0, [f, b]]]]]");
        assertThat(p.call("XPENDING s g")).isEqualTo("[:1, 2-0, 2-0, [[c2, 1]]]");
        assertThat(p.call("XINFO GROUPS s")).isEqualTo("[[name, g, consumers, :2, pending, :1, last-delivered-id, 2-0, lag, :0]]");

        w1.send("XREADGROUP GROUP g c1 COUNT 1 BLOCK 0 STREAMS s >");             // two arrive at once: one for it
        awaitBlocked(p, 1);
        assertThat(p.call("MULTI")).isEqualTo("+OK");
        p.call("XADD s 3 f c");
        p.call("XADD s 4 f d");
        assertThat(p.call("EXEC")).isEqualTo("[3-0, 4-0]");
        assertThat(R.render(w1.read())).isEqualTo("[[s, [[3-0, [f, c]]]]]");
        assertThat(p.call("XINFO GROUPS s")).isEqualTo("[[name, g, consumers, :2, pending, :2, last-delivered-id, 3-0, lag, :1]]");
    }

    @Test
    void timeoutsTransactionsAndHistory() {
        RawClient c = connect();
        assertThat(c.call("XGROUP CREATE s g $ MKSTREAM")).isEqualTo("+OK");
        long t0 = System.nanoTime();
        assertThat(c.call("XREAD BLOCK 300 STREAMS s $")).isEqualTo("(nil)");
        assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(250L, 1_500L);
        assertThat(c.call("XREADGROUP GROUP g c BLOCK 300 STREAMS s >")).isEqualTo("(nil)");
        assertThat(c.call("MULTI")).isEqualTo("+OK");
        assertThat(c.call("XREAD BLOCK 0 STREAMS s $")).isEqualTo("+QUEUED");
        assertThat(c.call("XREADGROUP GROUP g c BLOCK 0 STREAMS s >")).isEqualTo("+QUEUED");
        assertThat(c.call("EXEC")).isEqualTo("[(nil), (nil)]");                // never blocks inside a transaction
        assertThat(c.call("XREADGROUP GROUP g c BLOCK 0 STREAMS s 0")).isEqualTo("[[s, []]]");   // history: at once
    }

    @Test
    void aGroupReaderIsToldItsStreamOrGroupIsGone() {
        RawClient w1 = connect();
        RawClient w2 = connect();
        RawClient p = connect();
        assertThat(p.call("XGROUP CREATE s g $ MKSTREAM")).isEqualTo("+OK");
        w1.send("XREAD BLOCK 0 STREAMS s $");
        awaitBlocked(p, 1);
        w2.send("XREADGROUP GROUP g c BLOCK 0 STREAMS s >");
        awaitBlocked(p, 2);
        assertThat(p.call("DEL s")).isEqualTo(":1");
        assertThat(R.render(w2.read())).isEqualTo("-UNBLOCKED the stream key no longer exists");
        awaitBlocked(p, 1);                                                       // a plain reader waits on
        assertThat(p.call("XADD s 5 f v")).isEqualTo("5-0");
        assertThat(R.render(w1.read())).isEqualTo("[[s, [[5-0, [f, v]]]]]");

        assertThat(p.call("XGROUP CREATE s h $")).isEqualTo("+OK");
        w2.send("XREADGROUP GROUP h c BLOCK 0 STREAMS s >");
        awaitBlocked(p, 1);
        assertThat(p.call("XGROUP DESTROY s h")).isEqualTo(":1");
        assertThat(R.render(w2.read())).isEqualTo("-NOGROUP the consumer group this client was blocked on no longer exists");
    }
}
