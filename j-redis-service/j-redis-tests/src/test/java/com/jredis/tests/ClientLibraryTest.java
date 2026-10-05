package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisConnectionException;
import com.jredis.client.JRedisPubSub;
import com.jredis.client.JRedisTimeoutException;
import com.jredis.client.ThreadGuard;
import com.jredis.client.Transaction;
import com.jredis.common.Reply;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client library against a real server; most tests run twice: embedded and over TCP. */
class ClientLibraryTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeables.get(i).close();
        }
    }

    private <T extends AutoCloseable> T track(T c) {
        closeables.add(c);
        return c;
    }

    /** A server of the given kind and a started client connected to it. */
    private JRedisClient connect(String transport, Consumer<JRedisClient.Builder> tune) {
        JRedisClient.Builder b = JRedisClient.builder();
        if (transport.equals("embedded")) {
            JRedisEmbedded e = track(JRedisEmbedded.start(EmbeddedConfig.inMemory().configure(c -> c.enableDebugCommand(true))));
            b.localAddress(e.localName());
        } else {
            TcpServer s = track(TcpServer.start(c -> c.enableDebugCommand(true)));
            b.address("127.0.0.1", s.port());
        }
        tune.accept(b);
        JRedisClient client = track(b.build().start());
        assertThat(client.awaitConnected(5_000)).isTrue();
        return client;
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "tcp"})
    void concurrentSendersEachSeeTheirOwnOrder(String transport) throws Exception {
        JRedisClient client = connect(transport, b -> b.commandConnections(2));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                String key = "counter:" + t;
                jobs.add(pool.submit(() -> {
                    List<CompletableFuture<Long>> fs = new ArrayList<>();
                    for (int i = 0; i < 5_000; i++) {
                        fs.add(client.incr(key));
                    }
                    for (int i = 0; i < fs.size(); i++) {
                        assertThat(fs.get(i).join()).isEqualTo(i + 1L);
                    }
                }));
            }
            for (Future<?> j : jobs) {
                j.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "tcp"})
    void transactionsFromManyThreadsNeverInterleave(String transport) throws Exception {
        JRedisClient client = connect(transport, b -> { });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                jobs.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        client.incr("noise");                          // plain traffic between transactions
                        Transaction tx = client.multi().send("INCR", "x").send("INCR", "x").send("INCR", "x");
                        List<Reply> r = tx.exec().join();
                        assertThat(r.get(1).asLong()).isEqualTo(r.get(0).asLong() + 1);
                        assertThat(r.get(2).asLong()).isEqualTo(r.get(0).asLong() + 2);
                    }
                }));
            }
            for (Future<?> j : jobs) {
                j.get(60, TimeUnit.SECONDS);
            }
            assertThat(client.sync().get("x")).isEqualTo(Integer.toString(8 * 200 * 3));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "tcp"})
    void aTimedOutRequestDoesNotShiftLaterReplies(String transport) throws Exception {
        JRedisClient client = connect(transport, b -> b.commandTimeoutMillis(200));
        client.sync().set("k", "value");
        client.sync().set("other", "other-value");
        CompletableFuture<Reply> slow = client.send("DEBUG", "SLEEP", "0.5");
        CompletableFuture<String> queued = client.get("other");          // stuck behind the sleep, times out too
        assertThatThrownBy(slow::join).hasCauseInstanceOf(JRedisTimeoutException.class);
        assertThatThrownBy(queued::join).hasCauseInstanceOf(JRedisTimeoutException.class);
        Thread.sleep(700);                                               // the server wakes; the late replies arrive
        // late replies belong to the timed-out requests and are dropped, never handed to later requests
        for (int i = 0; i < 50; i++) {
            assertThat(client.sync().get("k")).isEqualTo("value");
        }
        assertThat(client.metrics().timeouts.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "tcp"})
    void watchOnALeasedConnection(String transport) {
        JRedisClient client = connect(transport, b -> { });
        JRedisClient other = client;                                    // plain commands use the shared connection
        client.sync().set("balance", "100");
        List<Reply> aborted = client.withLeasedConnection(lc -> {
            lc.sync().watch("balance");
            other.sync().incrBy("balance", 1);
            return lc.sync().exec(lc.multi().send("DECRBY", "balance", 10));
        });
        assertThat(aborted).isNull();
        List<Reply> ok = client.withLeasedConnection(lc -> {
            lc.sync().watch("balance");
            return lc.sync().exec(lc.multi().send("DECRBY", "balance", 10));
        });
        assertThat(ok).hasSize(1);
        assertThat(ok.get(0).asLong()).isEqualTo(91);
        // the lease is reset: a WATCH left behind by an exception does not leak into the next user
        assertThatThrownBy(() -> client.withLeasedConnection(lc -> {
            lc.sync().watch("balance");
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        other.sync().incr("balance");
        List<Reply> fresh = client.withLeasedConnection(lc -> lc.sync().exec(lc.multi().send("GET", "balance")));
        assertThat(fresh).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "tcp"})
    void blockingPopsUseTheirOwnConnection(String transport) throws Exception {
        JRedisClient client = connect(transport, b -> { });
        CompletableFuture<List<String>> waiting = client.blocking().blpop(5, "jobs");
        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        assertThat(client.sync().ping()).isEqualTo("PONG");            // the shared connection is not stalled
        client.sync().rpush("jobs", "job-1");
        assertThat(waiting.get(5, TimeUnit.SECONDS)).containsExactly("jobs", "job-1");
        assertThat(client.blocking().blpop(0.2, "empty").get(5, TimeUnit.SECONDS)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "tcp"})
    void publishSubscribe(String transport) throws Exception {
        JRedisClient client = connect(transport, b -> { });
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        JRedisPubSub ps = client.pubSub();
        ps.subscribe("chat", (ch, msg) -> got.add(ch + ":" + new String(msg))).get(5, TimeUnit.SECONDS);
        ps.psubscribe("news.*", (p, ch, msg) -> got.add(p + "|" + ch + ":" + new String(msg))).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().publish("chat", "hello")).isEqualTo(1);
        assertThat(client.sync().publish("news.9", "hi")).isEqualTo(1);
        assertThat(got.poll(5, TimeUnit.SECONDS)).isEqualTo("chat:hello");
        assertThat(got.poll(5, TimeUnit.SECONDS)).isEqualTo("news.*|news.9:hi");
        ps.unsubscribe("chat");
        long deadline = System.currentTimeMillis() + 5_000;
        while (client.sync().publish("chat", "x") != 0) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void guardsCatchMisuse() throws Exception {
        JRedisClient client = connect("embedded", b -> { });
        assertThatThrownBy(() -> client.send("BLPOP", "q", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client.blocking()");
        assertThatThrownBy(() -> client.send("SUBSCRIBE", "c")).hasMessageContaining("client.pubSub()");
        assertThatThrownBy(() -> client.send("MULTI")).hasMessageContaining("client.multi()");
        assertThatThrownBy(() -> client.send("WATCH", "k")).hasMessageContaining("withLeasedConnection");
        assertThatThrownBy(() -> client.multi().send("MULTI")).isInstanceOf(IllegalArgumentException.class);
        // the blocking facade refuses to run on a client I/O thread (it would deadlock)
        // A reply that arrives before thenAccept is attached runs the callback on this thread,
        // where the call is rightly allowed: tried again until it ran on the I/O thread.
        AtomicReference<Throwable> onEventLoop = new AtomicReference<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>(Thread.currentThread());
        for (int i = 0; i < 100 && ranOn.get() == Thread.currentThread(); i++) {
            client.ping().thenAccept(p -> {
                ranOn.set(Thread.currentThread());
                try {
                    client.sync().ping();
                } catch (Throwable t) {
                    onEventLoop.set(t);
                }
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(ranOn.get()).as("the callback ran on the client's I/O thread").isNotSameAs(Thread.currentThread());
        assertThat(onEventLoop.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("event-loop");
        // and on threads the application marked as non-blocking (latency-critical worker threads)
        ThreadGuard.markNonBlocking();
        try {
            assertThatThrownBy(() -> client.sync().ping()).isInstanceOf(IllegalStateException.class);
            assertThat(client.ping().get(5, TimeUnit.SECONDS)).isEqualTo("PONG");  // async is fine
        } finally {
            ThreadGuard.unmark();
        }
    }

    /** Server restart: pending requests fail (never complete twice), the client reconnects and resubscribes. */
    @Test
    void reconnectsAndResubscribesAfterAServerRestart() throws Exception {
        TcpServer first = TcpServer.start(c -> { });
        int port = first.port();
        JRedisClient client = track(JRedisClient.builder().address("127.0.0.1", port)
                .reconnectBackoffMillis(50, 200).build().start());
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        CountDownLatch resubscribed = new CountDownLatch(1);
        JRedisPubSub ps = client.pubSub();
        ps.onReconnect(resubscribed::countDown);
        ps.subscribe("events", (ch, msg) -> got.add(new String(msg))).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().set("k", "v")).isTrue();

        List<CompletableFuture<Long>> inFlight = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            inFlight.add(client.incr("n"));
        }
        first.close();
        int ok = 0;
        int failed = 0;
        for (CompletableFuture<Long> f : inFlight) {
            try {
                f.join();
                ok++;
            } catch (CompletionException e) {
                assertThat(e.getCause()).isInstanceOf(JRedisConnectionException.class);
                failed++;
            }
        }
        assertThat(ok + failed).isEqualTo(1_000);
        assertThatThrownBy(() -> client.sync().get("k")).isInstanceOf(JRedisConnectionException.class);

        TcpServer second = track(TcpServer.start(c -> c.port(port)));
        assertThat(client.awaitConnected(10_000)).isTrue();
        assertThat(resubscribed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(client.sync().get("k")).isNull();                   // a new, empty server
        assertThat(client.sync().set("k", "v2")).isTrue();
        long deadline = System.currentTimeMillis() + 5_000;
        while (client.sync().publish("events", "after-restart") == 0) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(20);
        }
        assertThat(got.poll(5, TimeUnit.SECONDS)).isEqualTo("after-restart");
        assertThat(second.fatal.get()).isNull();
    }

    @Test
    void wrongPasswordIsReportedAndCommandsFail() {
        TcpServer s = track(TcpServer.start(c -> c.requirepass("right")));
        JRedisClient bad = track(JRedisClient.builder().address("127.0.0.1", s.port()).password("wrong")
                .connectTimeoutMillis(500).build().start());
        assertThat(bad.awaitConnected(500)).isFalse();
        assertThatThrownBy(() -> bad.sync().ping()).isInstanceOf(JRedisConnectionException.class);
        JRedisClient good = track(JRedisClient.builder().address("127.0.0.1", s.port()).password("right").build().start());
        assertThat(good.awaitConnected(5_000)).isTrue();
        assertThat(good.sync().ping()).isEqualTo("PONG");
    }
}
