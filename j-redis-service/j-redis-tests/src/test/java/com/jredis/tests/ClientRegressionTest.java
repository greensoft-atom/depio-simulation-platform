package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisConnectionException;
import com.jredis.client.JRedisTimeoutException;
import com.jredis.client.ThreadGuard;
import com.jredis.common.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client library behaviour found by review; each test failed before its fix. */
class ClientRegressionTest {

    private TcpServer server;
    private final List<JRedisClient> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        server = TcpServer.start(c -> c.enableDebugCommand(true));
    }

    @AfterEach
    void stop() {
        clients.forEach(JRedisClient::close);
        server.close();
    }

    private JRedisClient client(Consumer<JRedisClient.Builder> tune) {
        JRedisClient.Builder b = JRedisClient.builder().address("127.0.0.1", server.port()).reconnectBackoffMillis(50, 200);
        tune.accept(b);
        JRedisClient c = b.build();
        clients.add(c);
        return c;
    }

    private static long connectionsNamed(JRedisClient admin, String name) {
        String list = admin.sync().send("CLIENT", "LIST").asString();
        long n = 0;
        for (String line : list.split("\n")) {
            if (line.contains(" name=" + name + " ")) {
                n++;
            }
        }
        return n;
    }

    /** A callback that sends while a timeout fires used to cancel the timeout checker for good. */
    @Test
    void aCallbackThatSendsDuringATimeoutKeepsTimeoutsWorking() throws Exception {
        JRedisClient c = client(b -> b.commandTimeoutMillis(300)).start();
        CompletableFuture<Reply> slow = c.send("DEBUG", "SLEEP", "1");
        slow.whenComplete((r, e) -> c.send("PING"));                   // sends from inside the timeout
        c.send("GET", "a");
        c.send("GET", "b");
        assertThatThrownBy(slow::join).hasCauseInstanceOf(JRedisTimeoutException.class);
        Thread.sleep(1_500);                                           // the server wakes up again
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> c.send("DEBUG", "SLEEP", "2").join()).hasCauseInstanceOf(JRedisTimeoutException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1_500);
    }

    @Test
    void closingFromACallbackDoesNotHang() throws Exception {
        JRedisClient c = client(b -> { }).start();
        CountDownLatch closed = new CountDownLatch(1);
        c.ping().whenComplete((r, e) -> {
            c.close();
            closed.countDown();
        });
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void startingTwiceOpensOneConnection() {
        JRedisClient c = client(b -> b.clientName("twice")).start();
        c.start();
        JRedisClient admin = client(b -> { }).start();
        assertThat(connectionsNamed(admin, "twice")).isEqualTo(1);
        for (int i = 0; i < 50; i++) {
            assertThat(c.sync().incr("n")).isEqualTo(i + 1L);
        }
    }

    @Test
    void aClientThatWasNeverStartedSaysSo() {
        JRedisClient c = client(b -> { });
        assertThatThrownBy(() -> c.sync().ping()).isInstanceOf(JRedisConnectionException.class)
                .hasMessageContaining("start()");
    }

    /** A WATCH lost in a reconnect must not let EXEC commit unchecked. */
    @Test
    void aWatchLostInAReconnectFailsTheTransaction() {
        JRedisClient c = client(b -> b.clientName("app")).start();
        JRedisClient admin = client(b -> b.clientName("admin")).start();
        c.sync().set("wk", "1");
        assertThatThrownBy(() -> c.withLeasedConnection(conn -> {
            conn.sync().watch("wk");
            admin.sync().send("CLIENT", "KILL", "TYPE", "normal", "SKIPME", "yes");
            long deadline = System.currentTimeMillis() + 5_000;
            while (true) {                                             // wait for the lease to reconnect
                try {
                    conn.sync().ping();
                    break;
                } catch (JRedisConnectionException notYet) {
                    assertThat(System.currentTimeMillis()).isLessThan(deadline);
                }
            }
            admin.sync().set("wk", "changed");
            return conn.sync().exec(conn.multi().send("SET", "wk", "lease-write"));
        })).isInstanceOf(JRedisConnectionException.class).hasMessageContaining("WATCH");
        assertThat(admin.sync().get("wk")).isEqualTo("changed");
    }

    /**
     * Queued blocking calls used to wedge the queue for good after one connection loss: while
     * disconnected they failed at once, each inside the previous one's callback, until the stack
     * overflowed.
     */
    @Test
    void theBlockingQueueRecoversAfterAConnectionLoss() throws Exception {
        int port = server.port();
        JRedisClient c = client(b -> { }).start();
        CompletableFuture<List<String>> first = c.blocking().blpop(0, "nothing");     // waits forever
        List<CompletableFuture<List<String>>> queued = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            queued.add(c.blocking().blpop(0, "nothing"));
        }
        server.close();                                                // everything queued now fails fast
        assertThatThrownBy(first::join).hasCauseInstanceOf(JRedisConnectionException.class);
        CompletableFuture.allOf(queued.toArray(new CompletableFuture<?>[0])).handle((v, e) -> null).get(30, TimeUnit.SECONDS);
        for (CompletableFuture<List<String>> f : queued) {
            assertThat(f).isCompletedExceptionally();
        }
        server = TcpServer.start(cfg -> cfg.port(port));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!c.blocking().isConnected()) {
            assertThat(System.currentTimeMillis()).as("reconnected").isLessThan(deadline);
            Thread.sleep(20);
        }
        assertThat(c.awaitConnected(10_000)).isTrue();                 // the command connection too
        CompletableFuture<List<String>> next = c.blocking().blpop(5, "jobs");
        c.sync().rpush("jobs", "job-1");
        assertThat(next.get(10, TimeUnit.SECONDS)).containsExactly("jobs", "job-1");
    }

    @Test
    void misuseIsRefusedClearly() {
        JRedisClient c = client(b -> b.leasePoolMax(1)).start();
        assertThatThrownBy(() -> c.zadd("z", 1, "m", "INCR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.multi().send("RESET")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.multi().send("QUIT")).isInstanceOf(IllegalArgumentException.class);
        ThreadGuard.markNonBlocking();
        try {
            assertThatThrownBy(() -> c.withLeasedConnection(conn -> "x")).isInstanceOf(IllegalStateException.class);
        } finally {
            ThreadGuard.unmark();
        }
        String pong = c.withLeasedConnection(conn -> conn.sync().ping());
        assertThat(pong).isEqualTo("PONG");                            // the pool is intact
    }
}
