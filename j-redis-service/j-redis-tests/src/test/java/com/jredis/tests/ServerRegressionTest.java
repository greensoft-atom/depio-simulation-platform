package com.jredis.tests;

import com.jredis.common.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Engine and protocol behaviour found by review; each test failed before its fix. */
class ServerRegressionTest {

    private TcpServer server;
    private final List<RawClient> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        server = TcpServer.start(c -> c.enableDebugCommand(true));
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
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.yield();
        }
    }

    /** A killed client that was blocked must not receive (and lose) a pushed element. */
    @Test
    void aKilledBlockedClientIsNotServed() {
        RawClient waiter = connect();
        RawClient admin = connect();
        String id = waiter.call("CLIENT ID").substring(1);
        waiter.send("BLPOP q 0");
        awaitBlocked(admin, 1);
        admin.send("CLIENT KILL ID " + id);
        admin.send("LPUSH q precious");
        admin.send("LLEN q");
        assertThat(R.render(admin.read())).isEqualTo(":1");
        assertThat(R.render(admin.read())).isEqualTo(":1");
        assertThat(R.render(admin.read())).isEqualTo(":1");           // the element is still there
        assertThat(admin.call("CLIENT KILL ID " + id)).isEqualTo(":0");        // already gone: nothing killed
    }

    /** A waiter for another type must not hold up the waiters behind it. */
    @Test
    void aWaiterOfAnotherTypeIsSkipped() {
        RawClient z = connect();
        RawClient l = connect();
        RawClient p = connect();
        z.send("BZPOPMIN k 0");
        awaitBlocked(p, 1);
        l.send("BLPOP k 0");
        awaitBlocked(p, 2);
        assertThat(p.call("LPUSH k v")).isEqualTo(":1");
        assertThat(R.render(l.read())).isEqualTo("[k, v]");
        assertThat(p.call("EXISTS k")).isEqualTo(":0");
    }

    /** One malformed frame gives exactly one error, and the connection then closes cleanly. */
    @Test
    void aProtocolErrorIsReportedOnceAndTheInputIsDrained() throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", server.port()), 2_000);
            s.setSoTimeout(5_000);
            OutputStream out = s.getOutputStream();
            out.write("PING\r\n*x\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < 4; i++) {                              // more garbage while it closes
                Thread.sleep(20);
                try {
                    out.write("*y\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (java.io.IOException closedAlready) {
                    break;
                }
            }
            String all = readToEof(s.getInputStream());
            assertThat(all).startsWith("+PONG\r\n-ERR Protocol error");
            assertThat(all.split("-ERR", -1)).hasSize(2);                // exactly one error
        }
    }

    /** A client that half-closes after sending still gets its replies (nc -N style). */
    @Test
    void aHalfClosedClientGetsItsReplies() throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", server.port()), 2_000);
            s.setSoTimeout(5_000);
            s.getOutputStream().write("SET hc v\r\nGET hc\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            s.shutdownOutput();
            assertThat(readToEof(s.getInputStream())).isEqualTo("+OK\r\n$1\r\nv\r\n");
        }
    }

    private static String readToEof(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    @Test
    void lengthHeadersMustNotWrapOrHaveLeadingZeros() {
        RawClient a = connect();
        a.writeRaw("*18446744073709551620\r\n");
        assertThat(R.render(a.read())).startsWith("-ERR Protocol error");
        RawClient b = connect();
        b.writeRaw("*1\r\n$003\r\nabc\r\n");
        assertThat(R.render(b.read())).startsWith("-ERR Protocol error");
    }

    /** EXEC re-checks OOM for its queued commands, as Redis does. */
    @Test
    void execIsAbortedWhenMemoryRanOutAfterQueueing() {
        RawClient a = connect();
        RawClient admin = connect();
        assertThat(a.call("SET filler " + "x")).isEqualTo("+OK");
        assertThat(a.call("MULTI")).isEqualTo("+OK");
        assertThat(a.call("SET k v")).isEqualTo("+QUEUED");
        assertThat(admin.call("CONFIG SET maxmemory 1")).isEqualTo("+OK");
        assertThat(a.call("EXEC")).startsWith("-EXECABORT Transaction discarded because of: OOM");
        assertThat(admin.call("CONFIG SET maxmemory 0")).isEqualTo("+OK");
        assertThat(a.call("EXISTS k")).isEqualTo(":0");
    }

    @Test
    void watchSurvivesAnAlreadyExpiredKeyAndAFlushOfMissingKeys() throws Exception {
        RawClient a = connect();
        RawClient b = connect();
        assertThat(a.call("DEBUG SET-ACTIVE-EXPIRE 0")).isEqualTo("+OK");
        assertThat(a.call("SET s v PX 1")).isEqualTo("+OK");
        Thread.sleep(50);
        assertThat(a.call("WATCH s")).isEqualTo("+OK");
        assertThat(a.call("MULTI")).isEqualTo("+OK");
        assertThat(a.call("PING")).isEqualTo("+QUEUED");
        assertThat(a.call("EXEC")).isEqualTo("[+PONG]");
        assertThat(a.call("WATCH nokey")).isEqualTo("+OK");
        assertThat(b.call("FLUSHALL")).isEqualTo("+OK");
        assertThat(a.call("MULTI")).isEqualTo("+OK");
        assertThat(a.call("PING")).isEqualTo("+QUEUED");
        assertThat(a.call("EXEC")).isEqualTo("[+PONG]");
    }

    @Test
    void clientKillFiltersAreValidated() {
        RawClient a = connect();
        RawClient b = connect();
        assertThat(a.call("CLIENT KILL")).startsWith("-ERR wrong number of arguments");
        assertThat(a.call("CLIENT KILL TYPE master")).isEqualTo(":0");
        assertThat(a.call("CLIENT KILL TYPE replica")).isEqualTo(":0");
        assertThat(a.call("CLIENT KILL TYPE bogus")).startsWith("-ERR Unknown client type");
        assertThat(b.call("PING")).isEqualTo("+PONG");                    // nobody was killed
    }

    @Test
    void pubsubNumpatCountsDistinctPatterns() {
        RawClient s1 = connect();
        RawClient s2 = connect();
        RawClient p = connect();
        assertThat(s1.call("PSUBSCRIBE news.*")).isEqualTo("[psubscribe, news.*, :1]");
        assertThat(s2.call("PSUBSCRIBE news.*")).isEqualTo("[psubscribe, news.*, :1]");
        assertThat(p.call("PUBSUB NUMPAT")).isEqualTo(":1");
    }

    @Test
    void configSetIsAllOrNothingAndKeepsSpaces() {
        RawClient a = connect();
        assertThat(a.call("CONFIG SET maxmemory 5mb timeout abc")).startsWith("-ERR");
        assertThat(a.call("CONFIG GET maxmemory")).isEqualTo("[maxmemory, 0]");
        assertThat(a.call("CONFIG SET requirepass \"two words\"")).isEqualTo("+OK");
        RawClient b = connect();
        assertThat(b.call("AUTH \"two words\"")).isEqualTo("+OK");
        assertThat(a.call("CONFIG SET requirepass \"\"")).isEqualTo("+OK");
    }

    @Test
    void clientsConnectedBeforeRemovingThePasswordAreLetIn() {
        server.close();
        server = TcpServer.start(c -> c.requirepass("s3cret"));
        RawClient u = connect();
        RawClient admin = connect();
        assertThat(u.call("GET k")).startsWith("-NOAUTH");
        assertThat(admin.call("AUTH s3cret")).isEqualTo("+OK");
        assertThat(admin.call("CONFIG SET requirepass \"\"")).isEqualTo("+OK");
        assertThat(u.call("GET k")).isEqualTo("(nil)");
    }

    @Test
    void authDefaultUserWithoutAPasswordIsAccepted() {
        RawClient a = connect();
        assertThat(a.call("AUTH default anything")).isEqualTo("+OK");
        assertThat(a.call("AUTH someone anything")).startsWith("-WRONGPASS");
        assertThat(a.call("AUTH anything")).startsWith("-ERR AUTH <password> called without any password");
    }

    @Test
    void errorsEchoingHugeArgumentsAreCut() {
        RawClient a = connect();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 70_000; i++) {
            big.append('a');
        }
        Reply r;
        a.send("CLIENT " + big);
        r = a.read();
        assertThat(r.isError()).isTrue();
        assertThat(r.asString().length()).isLessThan(1_100);
        assertThat(a.call("PING")).isEqualTo("+PONG");
    }

    @Test
    void commandInfoWithoutNamesListsEverything() {
        RawClient a = connect();
        assertThat(a.call("COMMAND INFO").length()).isGreaterThan(1_000);
        assertThat(a.call("COMMAND COUNT")).isEqualTo(":170");            // all of docs/05, DEBUG included (enabled here), 2.1's streams, 2.2's replication
    }
}
