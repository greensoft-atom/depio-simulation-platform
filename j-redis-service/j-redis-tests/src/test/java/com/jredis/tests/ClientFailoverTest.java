package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisConnectionException;
import com.jredis.client.JRedisException;
import com.jredis.client.JRedisServerException;
import com.jredis.common.Reply;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A client given several addresses uses the primary with the highest epoch, follows a promotion
 * on its own, and never goes back to a primary whose epoch is lower than one it has seen
 * ([16 §10](../../../../../../../docs/16-replication.md)).
 */
class ClientFailoverTest {

    private final List<AutoCloseable> open = new ArrayList<>();

    @TempDir
    Path scratch;

    @AfterEach
    void closeAll() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    private TcpServer server() throws Exception {
        ServerConfig c = new ServerConfig();
        c.port(0);
        c.appendonly(false);
        c.ioThreads(2);
        c.dir(Files.createTempDirectory(scratch, "server").toString());
        TcpServer s = TcpServer.start(c, Clock.system());
        open.add(s);
        return s;
    }

    private JRedisClient direct(TcpServer s) {
        JRedisClient c = JRedisClient.builder().address("127.0.0.1", s.port()).build().start();
        open.add(c);
        return c;
    }

    /** A client told of every server, in this order. */
    private JRedisClient following(TcpServer... servers) {
        String[] addresses = new String[servers.length];
        for (int i = 0; i < servers.length; i++) {
            addresses[i] = "127.0.0.1:" + servers[i].port();
        }
        JRedisClient c = JRedisClient.builder().addresses(addresses).commandTimeoutMillis(1_000)
                .reconnectBackoffMillis(50, 500).build().start();
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

    /** Until it succeeds, or 20 s: what a caller does while the primary changes. */
    private static <T> T eventually(Callable<T> action) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            try {
                return action.call();
            } catch (JRedisException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    /** A primary and its replica, in sync. */
    private void pair(TcpServer p, TcpServer r) throws Exception {
        JRedisClient pc = direct(p);
        JRedisClient rc = direct(r);
        call(pc, "SET seed 1");
        call(rc, "REPLICAOF 127.0.0.1 " + p.port());
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!call(rc, "INFO replication").contains("primary_link_status:up") || !call(rc, "GET seed").equals("1")) {
            assertThat(System.nanoTime()).as("in sync").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void theClientFindsThePrimaryWhicheverAddressComesFirst() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = following(r, p);                       // the replica listed first
        assertThat(eventually(() -> c.sync().set("a", "1"))).isTrue();
        assertThat(call(direct(p), "GET a")).isEqualTo("1");
    }

    @Test
    void afterAPromotionTheClientFollowsOnItsOwn() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = following(p, r);
        assertThat(eventually(() -> c.sync().set("before", "1"))).isTrue();
        p.close();                                              // the primary's machine is gone
        call(direct(r), "REPLICAOF NO ONE");                    // the operator promotes the replica
        assertThat(eventually(() -> c.sync().set("after", "1"))).isTrue();
        assertThat(call(direct(r), "GET after")).isEqualTo("1");
    }

    @Test
    void aDemotedPrimaryIsLeftAtItsFirstReadonly() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = following(p, r);
        assertThat(eventually(() -> c.sync().set("x", "1"))).isTrue();
        call(direct(r), "REPLICAOF NO ONE");                    // promoted, while the client still uses p
        call(direct(p), "REPLICAOF 127.0.0.1 " + r.port());     // then p is demoted
        assertThat(eventually(() -> c.sync().set("y", "2"))).isTrue();
        assertThat(call(direct(r), "GET y")).isEqualTo("2");
    }

    @Test
    void theClientsConnectionsFollowWhatOneOfThemFound() throws Exception {
        // The demoted server closes the subscriber, which finds the new primary. The command
        // connection has written nothing since, so nothing has told it -READONLY: it follows at
        // once, rather than at its first write, which would be refused and lost (D-40).
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = following(p, r);
        assertThat(eventually(() -> c.sync().set("x", "1"))).isTrue();
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        c.pubSub().subscribe("news", (ch, m) -> got.add(new String(m, StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        call(direct(r), "REPLICAOF NO ONE");
        call(direct(p), "REPLICAOF 127.0.0.1 " + r.port());
        heard(direct(r), got, "after");
        String role = "";
        for (long until = System.nanoTime() + 5_000_000_000L; !role.equals("primary"); Thread.sleep(20)) {
            assertThat(System.nanoTime()).as("the command connection on r, without a write").isLessThan(until);
            try {
                role = c.sync().send("ROLE").asList().get(0).asString();
            } catch (JRedisException reconnecting) {
                role = "";
            }
        }
        assertThat(c.sync().set("y", "2")).as("the first write after, taken").isTrue();
    }

    @Test
    void anIdleLeaseIsNotLentOnceTheClientKnowsOfAPromotion() throws Exception {
        // A lease waits in its pool and hears nothing. Once any connection of the client has found the
        // new primary, a lease opened under the old one is closed rather than lent (D-40).
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = JRedisClient.builder().addresses("127.0.0.1:" + p.port(), "127.0.0.1:" + r.port())
                .commandTimeoutMillis(1_000).reconnectBackoffMillis(50, 500).leasePoolMax(1).build().start();
        open.add(c);                                            // a pool of one: a lease closed gives back its place
        assertThat(eventually(() -> c.sync().set("x", "1"))).isTrue();
        long first = c.withLeasedConnection(l -> l.sync().send("CLIENT", "ID").asLong());
        long again = c.withLeasedConnection(l -> l.sync().send("CLIENT", "ID").asLong());
        assertThat(again).as("lent again while nothing changed").isEqualTo(first);
        call(direct(r), "REPLICAOF NO ONE");
        call(direct(p), "REPLICAOF 127.0.0.1 " + r.port());
        assertThat(eventually(() -> c.sync().set("x", "2"))).as("the client learns of r").isTrue();
        List<Reply> done = c.withLeasedConnection(l -> {
            l.sync().watch("y");
            return l.sync().exec(l.multi().send("SET", "y", "2"));
        });
        assertThat(done).as("committed the first time").isNotNull();
        assertThat(call(direct(r), "GET y")).isEqualTo("2");
    }

    /** Publishes on {@code to} until someone hears it, or 20 s: what a publisher sees while a subscriber moves. */
    private static void heard(JRedisClient to, BlockingQueue<String> got, String message) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (to.sync().publish("news", message) == 0) {
            assertThat(System.nanoTime()).as("a subscriber on the primary").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(got.poll(5, TimeUnit.SECONDS)).isEqualTo(message);
    }

    @Test
    void aSubscriberFollowsADemotion() throws Exception {
        // A subscriber is sent no replies, so it never sees -READONLY: the demoted server closes it (D-39).
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = following(p, r);
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        c.pubSub().subscribe("news", (ch, m) -> got.add(new String(m, StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        heard(direct(p), got, "before");
        assertThat(call(direct(r), "REPLICAOF NO ONE")).isEqualTo("+OK");          // the planned handover
        assertThat(call(direct(p), "REPLICAOF 127.0.0.1 " + r.port())).as("answered before it closes").isEqualTo("+OK");
        heard(direct(r), got, "after");
    }

    @Test
    void aBlockedReaderFollowsADemotion() throws Exception {
        // Waiting, it writes nothing that could be told -READONLY: the demoted server closes it (D-39).
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        JRedisClient c = following(p, r);
        assertThat(eventually(() -> c.sync().set("x", "1"))).isTrue();
        CompletableFuture<List<String>> waiting = c.blocking().blpop(0, "jobs");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!call(direct(p), "CLIENT LIST").contains("cmd=blpop")) {
            assertThat(System.nanoTime()).as("blocked on p").isLessThan(deadline);
            Thread.sleep(20);
        }
        assertThat(call(direct(r), "REPLICAOF NO ONE")).isEqualTo("+OK");
        assertThat(call(direct(p), "REPLICAOF 127.0.0.1 " + r.port())).isEqualTo("+OK");
        assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS)).as("told its connection was lost")
                .hasCauseInstanceOf(JRedisConnectionException.class);
        assertThat(call(direct(r), "RPUSH jobs one")).isEqualTo(":1");
        List<String> got = null;
        for (long until = System.nanoTime() + 20_000_000_000L; got == null; Thread.sleep(100)) {
            try {
                got = c.blocking().blpop(1, "jobs").get(5, TimeUnit.SECONDS);    // asked again, as a caller does
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(System.nanoTime()).as("the reader finds the new primary").isLessThan(until);
            }
        }
        assertThat(got).containsExactly("jobs", "one");
    }

    @Test
    void aSubscriberWithNoServerPingsNothing() throws Exception {
        // Pinged only while connected: a ping refused for want of a connection would count as a failure.
        int port;
        try (java.net.ServerSocket gone = new java.net.ServerSocket(0)) {
            port = gone.getLocalPort();
        }
        JRedisClient c = JRedisClient.builder().address("127.0.0.1", port).pubsubPingMillis(20)
                .reconnectBackoffMillis(50, 100).build().start();
        open.add(c);
        c.pubSub();
        Thread.sleep(500);
        assertThat(c.metrics().failedFast.get()).isZero();
    }

    @Test
    void aSubscriberLeavesAPrimaryThatFallsSilent() throws Exception {
        // A lost machine resets nothing, and a subscriber sends nothing that could time out: it pings (D-39).
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        SilentLink link = SilentLink.to(p.port());
        open.add(link);
        JRedisClient c = JRedisClient.builder().addresses("127.0.0.1:" + link.port(), "127.0.0.1:" + r.port())
                .commandTimeoutMillis(1_000).reconnectBackoffMillis(50, 500).build().start();
        open.add(c);
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        c.pubSub().subscribe("news", (ch, m) -> got.add(new String(m, StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        heard(direct(p), got, "before");
        link.silence();                                         // p's machine is gone
        assertThat(call(direct(r), "REPLICAOF NO ONE")).isEqualTo("+OK");
        heard(direct(r), got, "after");
    }

    @Test
    void neverBackToAPrimaryWithALowerEpoch() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        call(direct(r), "REPLICAOF NO ONE");                    // two primaries: p at epoch 0, r at 1
        JRedisClient c = following(p, r);
        assertThat(eventually(() -> c.sync().set("z", "1"))).isTrue();
        assertThat(call(direct(r), "GET z")).as("the higher epoch").isEqualTo("1");
        assertThat(call(direct(p), "EXISTS z")).isEqualTo(":0");
        r.close();                                              // only the older primary is left
        Thread.sleep(500);
        assertThatThrownBy(() -> c.sync().set("w", "1")).isInstanceOf(JRedisException.class);
        Thread.sleep(1_500);
        assertThat(call(direct(p), "EXISTS w")).as("never written to the older primary").isEqualTo(":0");
    }

    @Test
    void aReplicasEpochRulesOutAnOlderPrimary() throws Exception {
        // p was never demoted; r was promoted over it, and q follows r. A client told only of p and q
        // sees an epoch-1 replica: a primary at epoch 1 exists, so p, at 0, is stale.
        TcpServer p = server();
        TcpServer r = server();
        TcpServer q = server();
        pair(p, r);
        call(direct(r), "REPLICAOF NO ONE");
        pair(r, q);
        JRedisClient c = following(p, q);
        Thread.sleep(500);
        assertThatThrownBy(() -> c.sync().set("stale", "1")).isInstanceOf(JRedisException.class);
        assertThat(call(direct(p), "EXISTS stale")).isEqualTo(":0");
    }

    @Test
    void withAPasswordTheProbeAuthenticates() throws Exception {
        TcpServer p = server();
        TcpServer r = server();
        pair(p, r);
        for (TcpServer s : new TcpServer[] {p, r}) {
            JRedisClient d = direct(s);
            call(d, "CONFIG SET primaryauth secret");
            call(d, "CONFIG SET requirepass secret");
        }
        JRedisClient c = JRedisClient.builder().addresses("127.0.0.1:" + r.port(), "127.0.0.1:" + p.port())
                .password("secret").commandTimeoutMillis(1_000).build().start();
        open.add(c);
        assertThat(eventually(() -> c.sync().set("a", "1"))).isTrue();
    }

    @Test
    void oneAddressBehavesAsBefore() throws Exception {
        TcpServer p = server();
        JRedisClient c = JRedisClient.builder().addresses("127.0.0.1:" + p.port()).build().start();
        open.add(c);
        assertThat(c.sync().set("a", "1")).isTrue();
    }
}
