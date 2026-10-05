package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisServerException;
import com.jredis.common.Reply;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Clock;
import com.jredis.server.core.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A primary and its replica, two real servers over TCP: the full sync, the stream after it, and
 * the replica's rules ([16 §5, §6](../../../../../../../docs/16-replication.md)).
 */
class ReplicationTest {

    private final List<AutoCloseable> open = new ArrayList<>();

    @TempDir
    Path scratch;

    @AfterEach
    void closeAll() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    private TcpServer server(Clock clock, Consumer<ServerConfig> customize) {
        ServerConfig c = new ServerConfig();
        c.port(0);
        c.appendonly(false);
        c.ioThreads(2);
        c.enableDebugCommand(true);
        try {
            c.dir(Files.createTempDirectory(scratch, "server").toString());   // where an image lands
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        customize.accept(c);
        TcpServer s = TcpServer.start(c, clock);
        open.add(() -> {
            s.close();
            assertThat(s.fatal.get()).as("fail-stop").isNull();
        });
        return s;
    }

    private TcpServer server() {
        return server(Clock.system(), c -> { });
    }

    private JRedisClient client(TcpServer s) {
        JRedisClient c = JRedisClient.builder().address("127.0.0.1", s.port()).build().start();
        open.add(c);
        return c;
    }

    private static String call(JRedisClient c, String line) {
        try {
            return R.render(c.sync().send(R.args(line)));
        } catch (JRedisServerException e) {
            return "-" + e.getMessage();
        }
    }

    private static String digest(JRedisClient c) {
        return call(c, "DEBUG DIGEST");
    }

    /** Until the replica holds exactly what the primary holds, and says its link is up. */
    private static void awaitInSync(JRedisClient primary, JRedisClient replica) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            if (call(replica, "INFO replication").contains("primary_link_status:up") && digest(primary).equals(digest(replica))) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not in sync after 20 s: " + call(replica, "INFO replication"));
            }
            Thread.sleep(20);
        }
    }

    /** Every type, TTLs, a stream with groups and pending entries, and one big key. */
    private static void load(JRedisClient c, int n) {
        for (int i = 0; i < n; i++) {
            call(c, "SET s:" + i + " v" + i);
        }
        call(c, "SET ttl v PX 600000");
        call(c, "HSET h a 1 b 2");
        call(c, "RPUSH l a b c");
        call(c, "SADD set x y");
        call(c, "ZADD z 1 m 2 n");
        for (int i = 0; i < 300; i++) {
            call(c, "XADD st * f " + i);
            call(c, "ZADD big " + i + " m" + i);
        }
        call(c, "XGROUP CREATE st g 0");
        call(c, "XREADGROUP GROUP g c COUNT 7 STREAMS st >");
    }

    @Test
    void aFullSyncCopiesEveryType() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        load(pc, 1_000);
        call(rc, "SET mine 1");                               // what the replica held is replaced
        assertThat(call(rc, "REPLICAOF 127.0.0.1 " + p.port())).isEqualTo("+OK");
        awaitInSync(pc, rc);
        assertThat(call(rc, "EXISTS mine")).isEqualTo(":0");
        assertThat(Long.parseLong(call(rc, "PTTL ttl").substring(1))).isBetween(1L, 600_000L);
        assertThat(call(rc, "XPENDING st g")).startsWith("[:7,");
        assertThat(call(rc, "ROLE")).startsWith("[replica, 127.0.0.1, :" + p.port() + ", connected,");
        assertThat(call(pc, "ROLE")).startsWith("[primary,");
    }

    @Test
    void writesDuringAndAfterTheSyncArriveOnce() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        load(pc, 20_000);
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            JRedisClient w = JRedisClient.builder().address("127.0.0.1", p.port()).build().start();
            Random rnd = new Random(7);
            int i = 0;
            while (!stop.get()) {
                call(w, "INCR counter");
                call(w, "SET s:" + rnd.nextInt(20_000) + " w" + i++);       // keys the snapshot may not have reached
                call(w, "RPUSH log " + i);
                w.multi().send("INCR", "tx").send("XADD", "st", "*", "f", "tx" + i).exec().join();
            }
            w.close();
        });
        writer.start();
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        Thread.sleep(1_500);
        stop.set(true);
        writer.join();
        awaitInSync(pc, rc);
        assertThat(call(rc, "GET counter")).isEqualTo(call(pc, "GET counter"));
        assertThat(call(rc, "LLEN log")).isEqualTo(call(pc, "LLEN log"));
    }

    /** One random command of many kinds, on a small set of keys so they collide. */
    private static String randomCommand(Random rnd, int i) {
        String k = "k" + rnd.nextInt(40);
        String m = "m" + rnd.nextInt(10);
        switch (rnd.nextInt(24)) {
            case 0: return "SET " + k + " v" + i;
            case 1: return "DEL " + k;
            case 2: return "EXPIRE " + k + " " + (1 + rnd.nextInt(600));
            case 3: return "INCRBYFLOAT n" + rnd.nextInt(3) + " 0.5";
            case 4: return "HSET h" + rnd.nextInt(5) + " " + m + " " + i;
            case 5: return "HDEL h" + rnd.nextInt(5) + " " + m;
            case 6: return "LPUSH l" + rnd.nextInt(5) + " " + i;
            case 7: return "RPOP l" + rnd.nextInt(5);
            case 8: return "LMOVE l" + rnd.nextInt(5) + " l" + rnd.nextInt(5) + " LEFT RIGHT";
            case 9: return "SADD s" + rnd.nextInt(5) + " " + m;
            case 10: return "SPOP s" + rnd.nextInt(5);
            case 11: return "SREM s" + rnd.nextInt(5) + " " + m;
            case 12: return "ZADD z" + rnd.nextInt(5) + " " + rnd.nextInt(100) + " " + m;
            case 13: return "ZINCRBY z" + rnd.nextInt(5) + " 1.5 " + m;
            case 14: return "ZPOPMIN z" + rnd.nextInt(5);
            case 15: return "XADD x" + rnd.nextInt(3) + " MAXLEN ~ 50 * f " + i;
            case 16: return "XREADGROUP GROUP g c" + rnd.nextInt(3) + " COUNT 2 STREAMS x" + rnd.nextInt(3) + " >";
            case 17: return "XACK x" + rnd.nextInt(3) + " g 0-1";
            case 18: return "XAUTOCLAIM x" + rnd.nextInt(3) + " g c" + rnd.nextInt(3) + " 0 0 COUNT 3";
            case 19: return "RENAME " + k + " k" + rnd.nextInt(40);
            case 20: return "PERSIST " + k;
            case 21: return "APPEND " + k + " x";
            case 22: return "GETDEL " + k;
            default: return "SETRANGE " + k + " 2 yy";
        }
    }

    @Test
    void aRandomWorkloadEndsTheSameOnBoth() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        for (int s = 0; s < 3; s++) {
            call(pc, "XGROUP CREATE x" + s + " g $ MKSTREAM");
        }
        Random rnd = new Random(20260929);
        for (int i = 0; i < 3_000; i++) {
            call(pc, randomCommand(rnd, i));
        }
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        for (int i = 3_000; i < 12_000; i++) {                  // during the sync, and after it
            call(pc, randomCommand(rnd, i));
            if (i % 50 == 0) {
                pc.multi().send("INCR", "tx").send("LPUSH", "l0", "tx" + i).send("SPOP", "s1").exec().join();
            }
        }
        awaitInSync(pc, rc);
    }

    @Test
    void aReplicaRefusesWritesAndServesReads() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        assertThat(call(rc, "SET a 2")).isEqualTo("-READONLY You can't write against a read only replica.");
        assertThat(call(rc, "DEL a")).startsWith("-READONLY");
        try (RawClient raw = r.connect()) {                     // the client library keeps blocking calls off the shared connection
            assertThat(raw.call("BLPOP q 0.1")).startsWith("-READONLY");
        }
        assertThat(call(rc, "GET a")).isEqualTo("1");
        assertThat(call(rc, "CONFIG GET maxmemory")).startsWith("[maxmemory");        // not a write
    }

    @Test
    void aReplicaHidesExpiredKeysButDeletesNothingUntilThePrimarySaysSo() throws Exception {
        TcpServer p = server();
        ManualClock clock = new ManualClock(System.currentTimeMillis());
        TcpServer r = server(clock, c -> { });
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET k v PX 600000");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        clock.advance(3_600_000);                               // the replica's clock says it has expired
        assertThat(call(rc, "GET k")).as("hidden").isEqualTo("(nil)");
        assertThat(call(rc, "EXISTS k")).isEqualTo(":0");
        Thread.sleep(300);                                      // active expiry would have run by now
        assertThat(call(rc, "DBSIZE")).as("but not deleted").isEqualTo(":1");
        call(pc, "DEL k");
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!call(rc, "DBSIZE").equals(":0") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(call(rc, "DBSIZE")).isEqualTo(":0");
    }

    @Test
    void aReaderOnTheReplicaIsWokenByTheStream() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "XADD st 1-1 f 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        var read = rc.blocking().xread(5_000, 10, java.util.Map.of("st", "$"));
        Thread.sleep(200);
        call(pc, "XADD st 2-1 f 2");
        assertThat(read.get(5, java.util.concurrent.TimeUnit.SECONDS).get("st")).hasSize(1);
    }

    @Test
    void theReplicasFilesHoldWhatItHolds(@TempDir Path dir) throws Exception {
        TcpServer p = server();
        TcpServer r = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(rc, "SET mine 1");                                 // in the replica's files before the sync
        load(pc, 2_000);
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        call(pc, "SET after 1");                                // applied from the stream, into its own AOF
        pc.multi().send("INCR", "t").send("INCR", "t").exec().join();
        awaitInSync(pc, rc);
        StringBuilder aofs = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().startsWith("incr.")).toList()) {
                aofs.append(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertThat(aofs.toString()).as("a transaction stays one").contains("*1\r\n$5\r\nMULTI\r\n");
        String expected = digest(pc);
        assertThat(Files.readString(dir.resolve("manifest"))).contains("base base.");
        r.close();                                              // closed again at the end: harmless
        awaitInfo(pc, "connected_replicas:0");
        TcpServer again = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        assertThat(digest(client(again))).as("restarted alone, from its own files").isEqualTo(expected);
    }

    @Test
    void aRewriteAfterASyncStillWritesEveryKey(@TempDir Path dir) throws Exception {
        // A sync takes a snapshot too: its marks must not make the next rewrite skip keys.
        TcpServer p = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        load(pc, 2_000);
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        call(pc, "SAVE");
        String expected = digest(pc);
        p.close();
        TcpServer again = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        assertThat(digest(client(again))).isEqualTo(expected);
    }
    /** Until {@code field:value} shows in the server's INFO replication, or fails after 20 s. */
    private static void awaitInfo(JRedisClient c, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!call(c, "INFO replication").contains(expected)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no '" + expected + "' after 20 s: " + call(c, "INFO replication"));
            }
            Thread.sleep(20);
        }
    }

    /** Pipelined, without waiting for replies, so the server's batches are full. */
    private static void loadBig(JRedisClient c, int keys, int size) throws Exception {
        String v = "v".repeat(size);
        List<java.util.concurrent.CompletableFuture<?>> fs = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            fs.add(c.set("big:" + i, v));
            if (fs.size() == 500) {
                fs.get(499).get(30, java.util.concurrent.TimeUnit.SECONDS);
                fs.clear();
            }
        }
        for (var f : fs) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void aSecondReplicaSyncingWhileWritesPourInAppliesEachOnce() throws Exception {
        // The first replica made the backlog; the second's PSYNC may share a batch with writes,
        // whose effects belong to before its image and must not reach it again after.
        TcpServer p = server();
        TcpServer r1 = server();
        TcpServer r2 = server();
        JRedisClient pc = client(p);
        JRedisClient rc1 = client(r1);
        JRedisClient rc2 = client(r2);
        loadBig(pc, 2_000, 100);
        call(rc1, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc1);
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            JRedisClient w = JRedisClient.builder().address("127.0.0.1", p.port()).build().start();
            List<java.util.concurrent.CompletableFuture<?>> fs = new ArrayList<>();
            while (!stop.get()) {
                for (int i = 0; i < 200; i++) {
                    fs.add(w.incr("counter"));
                }
                fs.get(fs.size() - 1).join();
                fs.clear();
            }
            w.close();
        });
        writer.start();
        Thread.sleep(200);
        call(rc2, "REPLICAOF 127.0.0.1 " + p.port());
        Thread.sleep(1_500);
        stop.set(true);
        writer.join();
        awaitInSync(pc, rc2);
        awaitInSync(pc, rc1);
    }

    @Test
    void aStalledSyncHoldsOffRewritesAndFlushallEndsIt() throws Exception {
        // A replica that asks and then reads nothing: its image stops in the buffers, the scan waits
        // for its writer, and the snapshot stays open.
        TcpServer p = server(Clock.system(), c -> {
            c.appendonly(true);
            c.autoAofRewritePercentage(0);
        });
        JRedisClient pc = client(p);
        loadBig(pc, 10_000, 10_000);                            // 100 MB: more than the scan may run ahead
        try (RawClient fake = p.connect()) {
            fake.send("PSYNC ? -1");
            awaitInfo(pc, "state=sending");
            Thread.sleep(500);
            assertThat(call(pc, "BGREWRITEAOF")).startsWith("-ERR a replica's full sync is taking a snapshot");
            assertThat(call(pc, "FLUSHALL")).isEqualTo("+OK");
            awaitInfo(pc, "connected_replicas:0");              // dropped: it will ask again, for a small image
        }
        assertThat(call(pc, "BGREWRITEAOF")).isEqualTo("+Background append only file rewriting started");
    }

    @Test
    void aReplicaPastItsOutputLimitIsDropped() throws Exception {
        TcpServer p = server();
        JRedisClient pc = client(p);
        loadBig(pc, 2_000, 10_000);                             // 20 MB, more than the buffers take
        assertThat(call(pc, "CONFIG SET client-output-buffer-limit \"replica 1mb 0 0\"")).isEqualTo("+OK");
        try (RawClient fake = p.connect()) {
            fake.send("PSYNC ? -1");
            awaitInfo(pc, "full_syncs:1");
            awaitInfo(pc, "connected_replicas:0");              // its 4 MB unread pass the limit
        }
    }

    @Test
    void anIdleReplicaIsNotTimedOut() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "CONFIG SET timeout 2");
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        for (int i = 0; i < 10; i++) {                          // nothing written: the link is quiet
            Thread.sleep(500);
            call(pc, "PING");                                   // (this client is not a replica: it must speak)
        }
        assertThat(call(pc, "INFO replication")).contains("full_syncs:1").contains("connected_replicas:1");
    }

    /** Cuts the primary's connection to its replica, found by the S flag. */
    private static void cutTheLink(JRedisClient primary) {
        String line = java.util.Arrays.stream(call(primary, "CLIENT LIST").split("\n"))
                .filter(l -> l.contains("flags=S")).findFirst().orElseThrow();
        String id = line.substring(3, line.indexOf(' '));
        assertThat(call(primary, "CLIENT KILL ID " + id)).isEqualTo(":1");
    }

    @Test
    void aReplicaReconnectsAfterItsLinkIsCut() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        cutTheLink(pc);
        call(pc, "SET b 2");                                    // while the link is down
        awaitInSync(pc, rc);
        assertThat(call(pc, "INFO replication")).as("continued from the backlog, no second image")
                .contains("full_syncs:1").contains("partial_syncs:1");
    }

    @Test
    void writesOutrunningAFullSyncDropTheReplicaUntilItCatchesUp() throws Exception {
        TcpServer p = server(Clock.system(), c -> c.replBacklogSize(16 * 1024));
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        loadBig(pc, 5_000, 2_000);                              // 10 MB of image
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            JRedisClient w = JRedisClient.builder().address("127.0.0.1", p.port()).build().start();
            String v = "x".repeat(1_000);
            List<java.util.concurrent.CompletableFuture<?>> fs = new ArrayList<>();
            int i = 0;
            while (!stop.get()) {                               // pipelined: far more than 16 KB during the transfer
                for (int n = 0; n < 100; n++) {
                    fs.add(w.set("w:" + (i++ % 100), v));
                }
                fs.get(fs.size() - 1).join();
                fs.clear();
            }
            w.close();
        });
        writer.start();
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInfo(pc, "full_syncs:2");                          // the first was dropped at its end
        stop.set(true);
        writer.join();
        awaitInSync(pc, rc);
    }

    @Test
    void aPrimaryTurnedReplicaDropsItsOwnReplicas() throws Exception {
        TcpServer a = server();
        TcpServer b = server();
        TcpServer c = server();
        JRedisClient ac = client(a);
        JRedisClient bc = client(b);
        JRedisClient cc = client(c);
        call(bc, "REPLICAOF 127.0.0.1 " + a.port());
        awaitInSync(ac, bc);
        call(ac, "REPLICAOF 127.0.0.1 " + c.port());          // a is a replica now: b may not follow it
        awaitInfo(bc, "primary_link_status:down");
        awaitInfo(bc, "last_sync_error:the primary refused PSYNC: ERR a replica cannot have replicas");
        try (RawClient raw = a.connect()) {
            assertThat(raw.call("PSYNC ? -1")).isEqualTo("-ERR a replica cannot have replicas of its own");
        }
    }

    @Test
    void aHalfReceivedImageIsClearedAtStart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("sync-123.jrdb.tmp"), "half an image");
        server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        assertThat(dir.resolve("sync-123.jrdb.tmp")).doesNotExist();
    }

    @Test
    void pastTheBacklogALinkCutSyncsInFull() throws Exception {
        TcpServer p = server(Clock.system(), c -> c.replBacklogSize(16 * 1024));
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        cutTheLink(pc);
        String v = "x".repeat(1_000);
        for (int i = 0; i < 40; i++) {                          // 40 KB while it is away: more than the backlog holds
            pc.set("k" + i, v).join();
        }
        awaitInSync(pc, rc);
        assertThat(call(pc, "INFO replication")).contains("full_syncs:2").contains("partial_syncs:0");
    }

    @Test
    void anUnknownHistoryGetsAFullSync() throws Exception {
        TcpServer p = server();
        JRedisClient pc = client(p);
        call(pc, "SET a 1");
        try (RawClient raw = p.connect()) {
            raw.send("PSYNC 0123456789012345678901234567890123456789 0");
            assertThat(R.render(raw.read())).startsWith("+FULLRESYNC ");
        }
    }

    @Test
    void bothSidesAgreeOnTheOffset() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        for (int i = 0; i < 500; i++) {
            call(pc, "INCR n");
        }
        awaitInSync(pc, rc);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            String primary = call(pc, "ROLE");                  // [primary, :offset, [[host, port, acked]], :epoch]
            long offset = Long.parseLong(primary.substring(primary.indexOf(":") + 1, primary.indexOf(",", primary.indexOf(":"))));
            String acked = primary.substring(primary.indexOf("[[") + 2, primary.indexOf("]]")).split(", ")[2];
            String replica = call(rc, "ROLE");                 // [replica, host, :port, state, :applied, :epoch]
            String applied = replica.split(", ")[4].substring(1);
            if (acked.equals(Long.toString(offset)) && applied.equals(Long.toString(offset))) {
                break;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("offsets differ: " + primary + " / " + replica);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void waitCountsTheReplicasThatHoldTheWrite() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        call(pc, "SET x 1");
        assertThat(call(pc, "WAIT 1 5000")).isEqualTo(":1");
        long w0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {                          // asked at once, not left to the next second's ACK
            call(pc, "INCR w");
            assertThat(call(pc, "WAIT 1 5000")).isEqualTo(":1");
        }
        assertThat((System.nanoTime() - w0) / 1_000_000).as("ten WAITs").isLessThan(3_000);
        long t0 = System.nanoTime();
        assertThat(call(pc, "WAIT 2 300")).as("only one there: the timeout ends it").isEqualTo(":1");
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(250);
        assertThat(call(pc, "WAIT 0 0")).isEqualTo(":1");
        assertThat(call(rc, "WAIT 1 100")).isEqualTo("-ERR WAIT cannot be used with replica instances");
        try (RawClient raw = p.connect()) {                     // what it pipelined behind a WAIT waits for it
            raw.send("WAIT 2 200");
            raw.send("PING");
            assertThat(R.render(raw.read())).isEqualTo(":1");
            assertThat(R.render(raw.read())).isEqualTo("+PONG");
        }
        try (RawClient raw = p.connect()) {                     // for ever, unless it goes
            raw.send("WAIT 2 0");
            awaitInfo(pc, "waiting_clients:1");
        }
        awaitInfo(pc, "waiting_clients:0");
    }

    @Test
    void aReplicaThatNeverAcknowledgesIsDropped() throws Exception {
        TcpServer p = server();
        JRedisClient pc = client(p);
        call(pc, "SET a 1");
        call(pc, "CONFIG SET repl-timeout 2");
        try (RawClient fake = p.connect()) {
            fake.send("PSYNC ? -1");                            // a small image: it is online at once, and silent
            awaitInfo(pc, "state=online");
            assertThat(call(pc, "WAIT 1 200")).as("it never says where it is").isEqualTo(":0");
            awaitInfo(pc, "connected_replicas:0");
        }
    }

    @Test
    void aPrimaryThatSaysNothingIsLeftAndTriedAgain() throws Exception {
        TcpServer r = server();
        JRedisClient rc = client(r);
        call(rc, "CONFIG SET repl-timeout 1");
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            List<java.net.Socket> held = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        held.add(silent.accept());               // and never a byte back
                        accepted.incrementAndGet();
                    }
                } catch (java.io.IOException closed) {
                    // the test is over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            call(rc, "REPLICAOF 127.0.0.1 " + silent.getLocalPort());
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (accepted.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(accepted.get()).as("dropped after a second of silence, and tried again").isGreaterThanOrEqualTo(2);
            for (java.net.Socket s : held) {
                s.close();
            }
        }
    }

    @Test
    void pingsKeepAQuietLinkUp() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "CONFIG SET repl-ping-replica-period 1");
        call(rc, "CONFIG SET repl-timeout 2");
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        Thread.sleep(5_000);                                    // nothing written; only PINGs cross
        assertThat(call(pc, "INFO replication")).contains("full_syncs:1").contains("partial_syncs:0")
                .contains("connected_replicas:1");
        assertThat(call(rc, "INFO replication")).contains("primary_link_status:up");
    }

    /** The epoch, the last element of ROLE. */
    private static long epoch(JRedisClient c) {
        String role = call(c, "ROLE");
        return Long.parseLong(role.substring(role.lastIndexOf(":") + 1, role.length() - 1));
    }

    @Test
    void aPromotionRaisesTheEpochAndKeepsItAcrossARestart(@TempDir Path dir) throws Exception {
        TcpServer p = server();
        TcpServer r = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        assertThat(epoch(rc)).isZero();
        assertThat(call(rc, "REPLICAOF NO ONE")).isEqualTo("+OK");
        assertThat(epoch(rc)).isEqualTo(1);
        assertThat(call(rc, "SET b 2")).as("a primary now").isEqualTo("+OK");
        r.close();
        TcpServer again = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        assertThat(epoch(client(again))).as("from its data directory").isEqualTo(1);
    }

    @Test
    void aPromotedServerRefusesToFollowTheOlderPrimary() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        call(rc, "REPLICAOF NO ONE");
        call(rc, "SET newer 1");                                // written after the promotion
        String before = digest(rc);
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());            // the mistake: back to the old one
        awaitInfo(rc, "last_sync_error:the primary refused REPLCONF: EPOCH");
        Thread.sleep(1_500);                                    // it retries, and is refused again
        assertThat(digest(rc)).as("its newer data untouched").isEqualTo(before);
        assertThat(call(rc, "INFO replication")).contains("primary_link_status:down");
        try (RawClient raw = p.connect()) {
            assertThat(raw.call("REPLCONF epoch 7")).startsWith("-EPOCH ");
        }
    }

    @Test
    void theOldPrimaryDemotedFollowsTheNewAndTakesItsEpoch(@TempDir Path dir) throws Exception {
        TcpServer p = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "SET a 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInSync(pc, rc);
        call(rc, "REPLICAOF NO ONE");
        call(rc, "SET after 1");
        call(pc, "REPLICAOF 127.0.0.1 " + r.port());            // demoted: follows the promoted one
        awaitInSync(rc, pc);
        assertThat(epoch(pc)).isEqualTo(1);
        assertThat(call(pc, "GET after")).isEqualTo("1");
        p.close();
        TcpServer again = server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        assertThat(epoch(client(again))).as("the epoch it took is on its disk").isEqualTo(1);
    }

    @Test
    void aPrimaryWithTooFewGoodReplicasRefusesWrites() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        JRedisClient pc = client(p);
        JRedisClient rc = client(r);
        call(pc, "CONFIG SET min-replicas-to-write 1");
        assertThat(call(pc, "SET a 1")).isEqualTo("-NOREPLICAS Not enough good replicas to write.");
        assertThat(call(pc, "GET a")).as("reads go on").isEqualTo("(nil)");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        awaitInfo(pc, "state=online");
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!call(pc, "SET a 1").equals("+OK") && System.nanoTime() < deadline) {
            Thread.sleep(50);                                    // once it has acknowledged
        }
        assertThat(call(pc, "SET a 1")).isEqualTo("+OK");
        r.close();
        awaitInfo(pc, "connected_replicas:0");
        assertThat(call(pc, "SET a 2")).startsWith("-NOREPLICAS");
    }

    @Test
    void aReplicaThatStoppedAcknowledgingIsNotAGoodOne() throws Exception {
        TcpServer p = server();
        JRedisClient pc = client(p);
        call(pc, "CONFIG SET min-replicas-to-write 1");
        call(pc, "CONFIG SET min-replicas-max-lag 1");
        try (RawClient fake = p.connect()) {
            fake.send("PSYNC ? -1");                            // online, connected, and silent
            awaitInfo(pc, "state=online");
            Thread.sleep(1_500);
            assertThat(call(pc, "INFO replication")).contains("connected_replicas:1");
            assertThat(call(pc, "SET a 1")).as("connected, but past the lag").startsWith("-NOREPLICAS");
        }
    }

    @Test
    void aHalfWrittenEpochIsClearedAtStart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("replication.tmp"), "format 1\nepo");
        server(Clock.system(), c -> {
            c.appendonly(true);
            c.dir(dir.toString());
        });
        assertThat(dir.resolve("replication.tmp")).doesNotExist();
    }
}
