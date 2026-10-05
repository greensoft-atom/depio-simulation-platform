package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import com.jredis.server.core.Engine;
import com.jredis.server.core.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replication stream is the AOF's effects, the same bytes, whether or not the AOF is on
 * ([16 §4](../../../../../../../docs/16-replication.md), D-35).
 */
class EffectStreamTest {

    private static final long T0 = 1_767_225_600_000L;

    /** Runs {@code f} on the command thread, which owns the backlog. */
    private static <T> T onEngine(JRedisEmbedded r, Function<Engine, T> f) throws Exception {
        CompletableFuture<T> out = new CompletableFuture<>();
        r.engine().submit((Runnable) () -> out.complete(f.apply(r.engine())));
        return out.get(10, TimeUnit.SECONDS);
    }

    private static String stream(JRedisEmbedded r) throws Exception {
        return onEngine(r, e -> new String(e.backlog().from(e.backlog().firstOffset()), StandardCharsets.UTF_8));
    }

    private static void workload(JRedisClient c) throws Exception {
        for (String line : new String[] {"SET a 1", "EXPIRE a 60", "INCRBYFLOAT f 0.1", "SADD s x", "SPOP s", "DEL nothing"}) {
            c.sync().send(R.args(line));
        }
        c.multi().send("INCR", "n").send("RPUSH", "l", "v").exec().get(10, TimeUnit.SECONDS);
        for (String line : new String[] {"XADD st * f 1", "XGROUP CREATE st g 0", "XREADGROUP GROUP g c STREAMS st >"}) {
            c.sync().send(R.args(line));
        }
    }

    @Test
    void theBacklogHoldsExactlyWhatTheAofHolds(@TempDir Path dir) throws Exception {
        String inBacklog;
        try (JRedisEmbedded r = JRedisEmbedded.start(EmbeddedConfig.persistentAt(dir).clock(new ManualClock(T0)));
             JRedisClient c = r.newClient()) {
            onEngine(r, e -> {
                e.startBacklog(1 << 20);
                return null;
            });
            workload(c);
            inBacklog = stream(r);
        }
        String inAof = Files.readString(dir.resolve("incr.1.aof"), StandardCharsets.UTF_8);
        assertThat(inBacklog).isNotEmpty().isEqualTo(inAof);
        assertThat(inBacklog).contains("PEXPIREAT").contains("MULTI").contains("XCLAIM")
                .doesNotContain("nothing");
    }

    @Test
    void whatCameBeforeTheBacklogIsNotInIt(@TempDir Path dir) throws Exception {
        // In one event, so in one batch: a command, then the backlog. Its effect is encoded (the AOF
        // wants it) but belongs to before the stream; a replica would otherwise apply it twice.
        try (JRedisEmbedded r = JRedisEmbedded.start(EmbeddedConfig.persistentAt(dir).clock(new ManualClock(T0)));
             JRedisClient c = r.newClient()) {
            onEngine(r, e -> {
                e.executeLoading(e.newLoadingClient(), new byte[][]{
                    "SET".getBytes(StandardCharsets.US_ASCII), "before".getBytes(StandardCharsets.US_ASCII),
                    "1".getBytes(StandardCharsets.US_ASCII)});
                e.startBacklog(1 << 20);
                return null;
            });
            c.sync().send(R.args("SET after 1"));
            assertThat(stream(r)).isEqualTo("*3\r\n$3\r\nSET\r\n$5\r\nafter\r\n$1\r\n1\r\n");
        }
    }

    @Test
    void aLoggedDeliveryKeepsItsTimeEvenAheadOfTheClock() throws Exception {
        // A replica's clock may run behind its primary's: a delivery logged at the primary's time must
        // not be moved to the replica's, as a client's XCLAIM TIME in the future is (backend D-28).
        try (JRedisEmbedded r = JRedisEmbedded.start(EmbeddedConfig.inMemory().clock(new ManualClock(T0)));
             JRedisClient c = r.newClient()) {
            c.sync().send(R.args("XADD s 1-1 f v"));
            c.sync().send(R.args("XGROUP CREATE s g 0"));
            onEngine(r, e -> {
                e.applyReplicated(e.newLoadingClient(), R.bytes("XCLAIM s g c 0 1-1 TIME " + (T0 + 5_000) + " RETRYCOUNT 1 FORCE JUSTID"));
                return null;
            });
            assertThat(R.render(c.sync().send(R.args("XPENDING s g - + 10")))).isEqualTo("[[1-1, c, :-5000, :1]]");
            assertThat(R.render(c.sync().send(R.args("XCLAIM s g c 0 1-1 TIME " + (T0 + 5_000) + " RETRYCOUNT 1 FORCE JUSTID"))))
                    .as("a client's is still capped at now").isEqualTo("[1-1]");
            assertThat(R.render(c.sync().send(R.args("XPENDING s g - + 10")))).isEqualTo("[[1-1, c, :0, :1]]");
        }
    }

    @Test
    void withoutTheAofTheEffectsStillReachTheBacklog() throws Exception {
        try (JRedisEmbedded r = JRedisEmbedded.start(EmbeddedConfig.inMemory().clock(new ManualClock(T0)));
             JRedisClient c = r.newClient()) {
            c.sync().send(R.args("SET before 1"));
            onEngine(r, e -> {
                e.startBacklog(1 << 20);
                return null;
            });
            long start = onEngine(r, e -> e.backlog().offset());
            assertThat(start).isZero();
            c.sync().send(R.args("SET a 1"));
            assertThat(stream(r)).as("from its start, and only what came after")
                    .isEqualTo("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n");
            c.multi().send("INCR", "n").exec().get(10, TimeUnit.SECONDS);
            assertThat(stream(r)).endsWith("*1\r\n$5\r\nMULTI\r\n*2\r\n$4\r\nINCR\r\n$1\r\nn\r\n*1\r\n$4\r\nEXEC\r\n");
            long end = onEngine(r, e -> e.backlog().offset());
            assertThat(end).isEqualTo(stream(r).length());
        }
    }
}
