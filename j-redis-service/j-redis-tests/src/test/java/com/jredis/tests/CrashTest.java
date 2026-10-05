package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kill -9 of a real server process in the middle of a transactional workload, several times,
 * sometimes during an AOF rewrite. After each restart the data must be a prefix of the history
 * (transactions whole or absent) that includes every write acknowledged more than 2 s before the
 * kill (appendfsync everysec, NFR-4/5).
 */
@Tag("slow")
class CrashTest {

    @TempDir
    Path dir;

    private int port;

    private Process startServer(int round) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process p = new ProcessBuilder(java, "-Xmx512m", "-cp", System.getProperty("java.class.path"),
                "com.jredis.server.Main", "--port", Integer.toString(port), "--dir", dir.resolve("data").toString(),
                "--appendfsync", "everysec")
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("server-" + round + ".log").toFile())
                .start();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return p;
            } catch (IOException e) {
                assertThat(p.isAlive()).as("server process running (see server-%d.log)", round).isTrue();
                assertThat(System.currentTimeMillis()).as("server listening").isLessThan(deadline);
                Thread.sleep(50);
            }
        }
    }

    @Test
    void recoversAPrefixIncludingOlderAcknowledgedWritesAfterKill9() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Random rnd = new Random(99);
        long expectedAtLeast = 0;
        for (int round = 1; round <= 5; round++) {
            Process server = startServer(round);
            JRedisClient client = JRedisClient.builder().address("127.0.0.1", port).commandTimeoutMillis(5_000).build().start();
            long recovered = verifyPrefix(client);
            if (round == 1) {
                for (int b = 0; b < 50; b++) {                // ballast so every rewrite takes a while
                    Object[] hset = new Object[2 + 2 * 1_000];
                    hset[0] = "HSET";
                    hset[1] = "ballast:" + b;
                    for (int f = 0; f < 1_000; f++) {
                        hset[2 + 2 * f] = "f" + f;
                        hset[3 + 2 * f] = "some value " + f;
                    }
                    client.sync().send(hset);
                }
            }
            assertThat(recovered).as("round %d: recovered counter vs older acknowledged writes", round).isGreaterThanOrEqualTo(expectedAtLeast);

            ConcurrentSkipListMap<Long, Long> acked = new ConcurrentSkipListMap<>();   // time -> counter value
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong lastAcked = new AtomicLong(recovered);
            boolean rewriteRound = round % 2 == 0;
            Thread writer = new Thread(() -> {
                long i = recovered;
                while (!stop.get()) {
                    i++;
                    try {
                        List<Reply> r = client.multi().send("SET", "k:" + i, "v" + i).send("INCR", "counter").exec().join();
                        long v = r.get(1).asLong();
                        acked.put(System.nanoTime(), v);
                        lastAcked.set(v);
                        if (rewriteRound && i % 150 == 0) {
                            client.send("BGREWRITEAOF").join();
                        }
                    } catch (RuntimeException e) {
                        return;                              // the server is gone
                    }
                }
            }, "crash-writer");
            writer.start();
            Thread.sleep(1_500 + rnd.nextInt(2_500));
            long killedAt = System.nanoTime();
            server.destroyForcibly();                        // SIGKILL: no shutdown hook, no final flush
            assertThat(server.waitFor(30, TimeUnit.SECONDS)).isTrue();
            stop.set(true);
            writer.join(30_000);
            client.close();
            java.util.Map.Entry<Long, Long> older = acked.floorEntry(killedAt - TimeUnit.SECONDS.toNanos(2));
            expectedAtLeast = older == null ? recovered : older.getValue();
            assertThat(lastAcked.get()).as("round %d made progress", round).isGreaterThan(recovered);
            System.out.printf("round %d: recovered %d, acknowledged up to %d before the kill (%s)%n", round, recovered,
                    lastAcked.get(), rewriteRound ? "rewrite every 150 transactions" : "no rewrite");
        }
        Process last = startServer(99);
        JRedisClient client = JRedisClient.builder().address("127.0.0.1", port).build().start();
        assertThat(verifyPrefix(client)).isGreaterThanOrEqualTo(expectedAtLeast);
        try {
            client.sync().send("SHUTDOWN");                  // like Redis: no reply, the connection just closes
        } catch (com.jredis.client.JRedisConnectionException expected) {
            // the server is going down
        }
        assertThat(last.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(last.exitValue()).isZero();
        client.close();
    }

    /** counter = c means exactly k:1..k:c exist (each transaction whole or absent). */
    private static long verifyPrefix(JRedisClient client) {
        String counter = client.sync().get("counter");
        long c = counter == null ? 0 : Long.parseLong(counter);
        long ballast = 0;
        for (int b = 0; b < 50; b++) {
            ballast += client.sync().exists("ballast:" + b);
        }
        assertThat(ballast).isIn(0L, 50L);
        assertThat(client.sync().dbsize()).as("keys for counter %d", c).isEqualTo((c == 0 ? 0 : c + 1) + ballast);
        if (c > 0) {
            assertThat(client.sync().get("k:" + c)).isEqualTo("v" + c);
            assertThat(client.sync().get("k:1")).isEqualTo("v1");
        }
        assertThat(client.sync().exists("k:" + (c + 1))).isZero();
        return c;
    }
}
