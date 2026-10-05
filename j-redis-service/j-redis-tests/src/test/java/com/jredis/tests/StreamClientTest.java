package com.jredis.tests;

import com.jredis.client.Streams;
import com.jredis.client.XAddArgs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client library's streams ([10](../../../../../../../docs/10-client-library.md), [15](../../../../../../../docs/15-streams.md)). */
class StreamClientTest extends EmbeddedTest {

    @Test
    void entriesAndTrims() {
        assertThat(sync.xadd("s", "f", "a")).isEqualTo(T0 + "-0");
        assertThat(sync.xadd("s", XAddArgs.none(), (T0 + 5) + "-*", "f", "b", "g", "c")).isEqualTo((T0 + 5) + "-0");
        assertThat(sync.xlen("s")).isEqualTo(2);
        List<Streams.Entry> all = sync.xrange("s", "-", "+");
        assertThat(all).extracting(Streams.Entry::id).containsExactly(T0 + "-0", (T0 + 5) + "-0");
        assertThat(all.get(1).fields()).containsExactly(Map.entry("f", "b"), Map.entry("g", "c"));   // in order
        assertThat(sync.xrange("s", "-", "+", 1)).hasSize(1);
        assertThat(sync.xrevrange("s", "+", "-", 1).get(0).id()).isEqualTo((T0 + 5) + "-0");
        assertThat(sync.xadd("none", XAddArgs.none().noMkStream(), "*", "f", "x")).isNull();
        assertThat(sync.xadd("s", XAddArgs.maxLen(2), "*", "f", "d")).isEqualTo((T0 + 5) + "-1");
        assertThat(sync.xlen("s")).isEqualTo(2);
        assertThat(sync.xadd("s", XAddArgs.minId((T0 + 5) + "-1").approximately(), "*", "f", "e")).isEqualTo((T0 + 5) + "-2");
        assertThat(sync.xlen("s")).isEqualTo(2);
        assertThat(sync.xdel("s", (T0 + 5) + "-1", "1-0")).isEqualTo(1);
        assertThat(sync.xtrimMaxLen("s", 0)).isEqualTo(1);
        assertThat(sync.xtrimMinId("s", "0")).isZero();
        assertThat(sync.xrange("s", "-", "+")).isEmpty();
    }

    @Test
    void groups() {
        sync.xgroupCreate("s", "g", "$", true);
        String a = sync.xadd("s", "n", "1");
        String b = sync.xadd("s", "n", "2");
        String c = sync.xadd("s", "n", "3");
        Map<String, List<Streams.Entry>> got = sync.xreadgroup("g", "c1", 2, Map.of("s", ">"));
        assertThat(got.get("s")).extracting(Streams.Entry::id).containsExactly(a, b);
        assertThat(sync.xreadgroup("g", "c1", 10, Map.of("s", ">")).get("s")).extracting(Streams.Entry::id).containsExactly(c);
        assertThat(sync.xreadgroup("g", "c1", 10, Map.of("s", ">"))).isEmpty();
        assertThat(sync.xreadgroup("g", "c1", 10, Map.of("s", "0")).get("s")).hasSize(3);           // its history

        Streams.Pending p = sync.xpending("s", "g");
        assertThat(p.count()).isEqualTo(3);
        assertThat(p.first()).isEqualTo(a);
        assertThat(p.last()).isEqualTo(c);
        assertThat(p.consumers()).containsExactly(Map.entry("c1", 3L));
        assertThat(sync.xpendingRange("s", "g", "-", "+", 10))
                .containsExactly(new Streams.PendingEntry(a, "c1", 0, 1), new Streams.PendingEntry(b, "c1", 0, 1), new Streams.PendingEntry(c, "c1", 0, 1));
        assertThat(sync.xack("s", "g", a, "1-0")).isEqualTo(1);

        clock.advance(1_000);
        Streams.AutoClaim ac = sync.xautoclaim("s", "g", "c2", 500, "0", 1);
        assertThat(ac.claimed()).extracting(Streams.Entry::id).containsExactly(b);
        assertThat(ac.next()).isEqualTo(c);
        assertThat(ac.deleted()).isEmpty();
        assertThat(sync.xclaim("s", "g", "c3", 0, c)).extracting(Streams.Entry::id).containsExactly(c);
        sync.xadd("s", "n", "4");                                           // one not yet delivered
        assertThat(sync.xinfoGroups("s")).containsExactly(new Streams.Group("g", 3, 2, c, 1));
        assertThat(sync.xgroupDestroy("s", "g")).isTrue();
        assertThat(sync.xgroupDestroy("s", "g")).isFalse();
        assertThatThrownBy(() -> sync.xpending("s", "h")).hasMessageContaining("NOGROUP");
    }

    @Test
    void blockingReadsTakeTheirOwnConnection() throws Exception {
        sync.xgroupCreate("s", "g", "$", true);
        CompletableFuture<Map<String, List<Streams.Entry>>> waiting =
                client.blocking().xreadgroup(5_000, "g", "w", 10, Map.of("s", ">"));
        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        assertThat(sync.ping()).isEqualTo("PONG");                        // the shared connection is not stalled
        String id = sync.xadd("s", "f", "v");
        assertThat(waiting.get(5, TimeUnit.SECONDS).get("s")).extracting(Streams.Entry::id).containsExactly(id);

        CompletableFuture<Map<String, List<Streams.Entry>>> quiet = client.blocking().xread(200, 10, Map.of("s", "$"));
        Thread.sleep(100);
        clock.advance(300);                                                 // the server's clock decides the timeout
        assertThat(quiet.get(5, TimeUnit.SECONDS)).isEmpty();

        assertThatThrownBy(() -> client.send("XREAD", "BLOCK", 0, "STREAMS", "s", "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client.blocking()");
        assertThatThrownBy(() -> client.send("xreadgroup", "GROUP", "g", "c", "block", 0, "STREAMS", "s", ">"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client.blocking()");
        assertThat(client.send("XREAD", "STREAMS", "s", "0").get(5, TimeUnit.SECONDS).asList()).hasSize(1);   // no BLOCK: fine
        assertThat(client.send("XREADGROUP", "GROUP", "g", "block", "STREAMS", "s", "0").get(5, TimeUnit.SECONDS).isError())
                .as("a consumer called block is a name, not the option").isFalse();
    }
}
