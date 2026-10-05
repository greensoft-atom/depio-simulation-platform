package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import com.jredis.server.core.ManualClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DEBUG DIGEST}: the same data gives the same digest whatever its order in memory, and any
 * difference a replica could have gives another ([16 §12](../../../../../../../docs/16-replication.md)).
 */
class DebugDigestTest extends EmbeddedTest {

    private static final String ZERO = "0".repeat(40);

    private static String digest(JRedisSync s) {
        return R.render(s.send(R.args("DEBUG DIGEST")));
    }

    /** One of each type, TTLs and a stream with a group, a consumer and pending entries. */
    private static void build(JRedisSync s, boolean reversed) {
        String[] lines = {
            "SET str v", "SET ttl w PXAT " + (T0 + 60_000),
            "HSET h a 1 b 2 c 3", "SADD s x y z", "RPUSH l a b c", "ZADD z 1 m 2 n 2 o",
            "XADD st 1-1 f 1", "XADD st 2-1 f 2", "XADD st 3-1 f 3",
            "XGROUP CREATE st g 0", "XGROUP CREATE st h $",
            "XREADGROUP GROUP g c1 COUNT 2 STREAMS st >", "XGROUP CREATECONSUMER st g c2",
        };
        if (reversed) {                 // the collections filled the other way round, where order is not state
            s.send(R.args("SADD s z y x"));
            s.send(R.args("HSET h c 3 b 2 a 1"));
        }
        for (String line : lines) {
            s.send(R.args(line));
        }
    }

    @Test
    void emptyIsZero() {
        expect("DEBUG DIGEST", ZERO);
        call("SET a 1");
        call("DEL a");
        expect("DEBUG DIGEST", ZERO);
    }

    @Test
    void theSameDataGivesTheSameDigestInAnyOrder() {
        build(sync, false);
        String here = digest(sync);
        assertThat(here).hasSize(40).isNotEqualTo(ZERO);
        // Another server: another hash seed, so another order in every hash table.
        try (JRedisEmbedded other = JRedisEmbedded.start(EmbeddedConfig.inMemory().clock(new ManualClock(T0))
                .configure(c -> c.enableDebugCommand(true)));
             JRedisClient c = other.newClient()) {
            build(c.sync(), true);
            assertThat(digest(c.sync())).isEqualTo(here);
        }
    }

    @Test
    void anyDifferenceChangesItAndUndoingItRestoresIt() {
        build(sync, false);
        String d0 = digest(sync);
        String[][] changes = {
            {"SET str V", "SET str v"},
            {"PEXPIREAT str " + (T0 + 5_000), "PERSIST str"},
            {"PEXPIREAT ttl " + (T0 + 60_001), "PEXPIREAT ttl " + (T0 + 60_000)},
            {"HSET h a 9", "HSET h a 1"},
            {"HSET h d 4", "HDEL h d"},
            {"SADD s w", "SREM s w"},
            {"RPUSH l d", "RPOP l"},
            {"LSET l 0 c", "LSET l 0 a"},
            {"ZADD z 3 m", "ZADD z 1 m"},
            {"ZADD z 1.5 m", "ZADD z 1 m"},                      // a score that keeps the order
            {"ZADD z 1 p", "ZREM z p"},
            {"RENAME str str2", "RENAME str2 str"},
            {"XGROUP CREATECONSUMER st g c3", "XGROUP DELCONSUMER st g c3"},
            {"XCLAIM st g c9 0 1-1 TIME " + (T0 + 7) + " RETRYCOUNT 5 FORCE JUSTID",
             "XCLAIM st g c1 0 1-1 TIME " + T0 + " RETRYCOUNT 1 FORCE JUSTID", "XGROUP DELCONSUMER st g c9"},
            // One part of a pending entry at a time: its count, its time, its owner.
            {"XCLAIM st g c1 0 1-1 TIME " + T0 + " RETRYCOUNT 5 FORCE JUSTID",
             "XCLAIM st g c1 0 1-1 TIME " + T0 + " RETRYCOUNT 1 FORCE JUSTID"},
            {"XCLAIM st g c1 0 1-1 TIME " + (T0 - 7) + " RETRYCOUNT 1 FORCE JUSTID",   // a time ahead is capped at now
             "XCLAIM st g c1 0 1-1 TIME " + T0 + " RETRYCOUNT 1 FORCE JUSTID"},
            {"XCLAIM st g c2 0 1-1 TIME " + T0 + " RETRYCOUNT 1 FORCE JUSTID",
             "XCLAIM st g c1 0 1-1 TIME " + T0 + " RETRYCOUNT 1 FORCE JUSTID"},
        };
        for (String[] c : changes) {
            call(c[0]);
            assertThat(digest(sync)).as(c[0]).isNotEqualTo(d0);
            for (int i = 1; i < c.length; i++) {
                call(c[i]);
            }
            assertThat(digest(sync)).as("undone: " + c[1]).isEqualTo(d0);
        }
        // Changes that cannot be undone, each still seen: the stream's last ID moving on with no
        // entry left to show it, a string for a list of the same bytes, an entry's removal, the
        // group's last ID moving on, an acknowledgement.
        String before = digest(sync);
        call("XADD st 9-9 f 9");
        call("XDEL st 9-9");
        assertThat(digest(sync)).as("the same entries, a later last ID").isNotEqualTo(before);
        String[] lasting = {"DEL l", "RPUSH l abc", "XDEL st 3-1", "XGROUP SETID st h 1-1", "XACK st g 2-1"};
        before = digest(sync);
        for (String c : lasting) {
            call(c);
            String after = digest(sync);
            assertThat(after).as(c).isNotEqualTo(before);
            before = after;
        }
    }

    @Test
    void oneKeysDigestFindsTheKeyThatDiffers() {
        build(sync, false);
        assertThat(call("DEBUG DIGEST-VALUE missing")).isEqualTo("[" + ZERO + "]");
        String h = call("DEBUG DIGEST-VALUE h");
        String[] both = call("DEBUG DIGEST-VALUE h str").replaceAll("^\\[|\\]$", "").split(", ");
        assertThat(both).hasSize(2);
        assertThat("[" + both[0] + "]").isEqualTo(h);
        call("HSET h a 9");
        assertThat(call("DEBUG DIGEST-VALUE h")).isNotEqualTo(h);
        assertThat(call("DEBUG DIGEST-VALUE str")).isEqualTo("[" + both[1] + "]");
    }

    @Test
    void aConsumersSeenTimeIsNotState() {
        // Never logged (D-34), so a replica's differs from its primary's: the digest leaves it out.
        build(sync, false);
        call("XREADGROUP GROUP h ch STREAMS st >");           // the group is at the end: ch made, nothing read
        String d0 = digest(sync);
        clock.advance(10_000);
        call("XREADGROUP GROUP h ch STREAMS st >");           // seen again, and nothing else changes
        assertThat(digest(sync)).isEqualTo(d0);
    }
}
