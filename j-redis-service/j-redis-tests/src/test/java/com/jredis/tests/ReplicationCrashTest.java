package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A primary killed with kill -9 under a workload, its replica promoted: every write a WAIT
 * confirmed is there, and a client given both addresses finds the new primary on its own
 * ([16 §14](../../../../../../../docs/16-replication.md), part (f)). Real server processes.
 */
@Tag("slow")
class ReplicationCrashTest {

    @TempDir
    Path dir;

    private final List<Process> processes = new ArrayList<>();

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private Process start(String name, int port, String... extra) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx512m", "-cp", System.getProperty("java.class.path"),
                "com.jredis.server.Main", "--port", Integer.toString(port), "--dir", dir.resolve(name).toString(),
                "--appendfsync", "everysec", "--enable-debug-command", "yes"));
        cmd.addAll(List.of(extra));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(dir.resolve(name + "-" + processes.size() + ".log").toFile()).start();
        processes.add(p);
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return p;
            } catch (IOException e) {
                assertThat(p.isAlive()).as("%s running (see its log in %s)", name, dir).isTrue();
                assertThat(System.currentTimeMillis()).as("%s listening", name).isLessThan(deadline);
                Thread.sleep(50);
            }
        }
    }

    private static JRedisClient direct(int port) {
        return JRedisClient.builder().address("127.0.0.1", port).commandTimeoutMillis(5_000).build().start();
    }

    private static String call(JRedisClient c, String line) {
        return R.render(c.sync().send(R.args(line)));
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void aPrimaryKilledUnderLoadLosesNoWriteThatWaitConfirmed() throws Exception {
        int pPort = freePort();
        int rPort = freePort();
        Process primary = start("p", pPort);
        start("r", rPort, "--replicaof", "127.0.0.1", Integer.toString(pPort));
        try (JRedisClient rc = direct(rPort);
             JRedisClient client = JRedisClient.builder().addresses("127.0.0.1:" + pPort, "127.0.0.1:" + rPort)
                     .commandTimeoutMillis(2_000).reconnectBackoffMillis(50, 500).build().start()) {
            await("the replica in sync", () -> call(rc, "INFO replication").contains("primary_link_status:up"));

            ConcurrentSkipListSet<Long> confirmed = new ConcurrentSkipListSet<>();
            AtomicLong acknowledged = new AtomicLong();
            AtomicLong afterPromotion = new AtomicLong();
            AtomicBoolean promoted = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            Thread writer = new Thread(() -> {
                long i = 0;
                while (!stop.get()) {
                    long k = i++;
                    try {
                        client.sync().set("k:" + k, "v" + k);
                        acknowledged.incrementAndGet();
                        if (promoted.get()) {
                            afterPromotion.incrementAndGet();
                            continue;                           // no replica left to confirm anything
                        }
                        long n = client.sync().send("WAIT", "1", "500").asLong();
                        if (n >= 1) {
                            confirmed.add(k);
                        }
                    } catch (JRedisException e) {
                        // the primary is gone, or the new one not found yet: go on writing
                    }
                }
            });
            writer.start();
            Thread.sleep(3_000);
            primary.destroyForcibly();                          // SIGKILL: no shutdown, no final flush
            primary.waitFor();
            long confirmedAtKill = confirmed.size();
            assertThat(call(rc, "REPLICAOF NO ONE")).isEqualTo("+OK");
            promoted.set(true);
            await("the client writing to the new primary on its own", () -> afterPromotion.get() >= 100);
            stop.set(true);
            writer.join();

            List<Long> lost = new ArrayList<>();
            for (long k : confirmed) {
                String v = call(rc, "GET k:" + k);
                if (!v.equals("v" + k)) {
                    lost.add(k);
                }
            }
            System.out.printf("acknowledged %d, confirmed by WAIT %d (%d before the kill), written after the "
                    + "promotion %d, confirmed and lost %d%n", acknowledged.get(), confirmed.size(), confirmedAtKill,
                    afterPromotion.get(), lost.size());
            assertThat(confirmedAtKill).as("writes confirmed before the kill").isGreaterThan(100);
            assertThat(lost).as("confirmed writes missing on the promoted replica").isEmpty();

            // The old primary, started again as a replica of the new one, follows it: epochs allow it.
            start("p", pPort, "--replicaof", "127.0.0.1", Integer.toString(rPort));
            try (JRedisClient pc = direct(pPort)) {
                await("the old primary following the new", () -> call(pc, "INFO replication").contains("primary_link_status:up")
                        && call(pc, "DEBUG DIGEST").equals(call(rc, "DEBUG DIGEST")));
                assertThat(call(pc, "ROLE")).endsWith(", :1]");
            }
        } finally {
            for (Process p : processes) {
                p.destroyForcibly();
            }
        }
    }
}
