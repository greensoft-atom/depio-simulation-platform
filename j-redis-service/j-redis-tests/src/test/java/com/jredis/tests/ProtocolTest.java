package com.jredis.tests;

import com.jredis.common.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Wire-level behaviour over real TCP, driven by a plain socket. */
class ProtocolTest {

    private TcpServer server;
    private final List<RawClient> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        server = TcpServer.start(c -> { });
    }

    @AfterEach
    void stop() {
        clients.forEach(RawClient::close);
        server.close();
        assertThat(server.fatal.get()).isNull();
    }

    /** Waits until the server reports {@code n} blocked clients (commands arrive on different I/O threads). */
    private static void awaitBlocked(RawClient observer, int n) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!observer.call("INFO clients").contains("blocked_clients:" + n + "\r\n")) {
            assertThat(System.currentTimeMillis()).as("waiting for %d blocked clients", n).isLessThan(deadline);
            Thread.yield();
        }
    }

    private RawClient connect() {
        RawClient c = server.connect();
        clients.add(c);
        return c;
    }

    @Test
    void inlineCommands() {
        RawClient c = connect();
        c.writeRaw("PING\r\nSET greeting \"hello world\"\r\nGET greeting\r\n");
        assertThat(R.render(c.read())).isEqualTo("+PONG");
        assertThat(R.render(c.read())).isEqualTo("+OK");
        assertThat(R.render(c.read())).isEqualTo("hello world");
    }

    @Test
    void pipelinedRepliesArriveInOrder() {
        RawClient c = connect();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("*2\r\n$4\r\nINCR\r\n$1\r\nn\r\n");
        }
        c.writeRaw(sb.toString());
        for (int i = 1; i <= 20_000; i++) {
            assertThat(c.read().asLong()).isEqualTo(i);
        }
    }

    @Test
    void protocolErrorClosesOnlyThatConnection() {
        RawClient bad = connect();
        RawClient good = connect();
        bad.writeRaw("*1\r\n$abc\r\n");
        assertThat(R.render(bad.read())).startsWith("-ERR Protocol error");
        assertThat(bad.closedByServer()).isTrue();
        assertThat(good.call("PING")).isEqualTo("+PONG");
    }

    @Test
    void transactions() {
        RawClient c = connect();
        assertThat(c.call("MULTI")).isEqualTo("+OK");
        assertThat(c.call("SET k 1")).isEqualTo("+QUEUED");
        assertThat(c.call("INCR k")).isEqualTo("+QUEUED");
        assertThat(c.call("EXEC")).isEqualTo("[+OK, :2]");

        assertThat(c.call("MULTI")).isEqualTo("+OK");
        assertThat(c.call("SET s x")).isEqualTo("+QUEUED");
        assertThat(c.call("INCR s")).isEqualTo("+QUEUED");     // fails at run time; the rest still runs
        assertThat(c.call("SET t y")).isEqualTo("+QUEUED");
        assertThat(c.call("EXEC")).isEqualTo("[+OK, -ERR value is not an integer or out of range, +OK]");

        assertThat(c.call("MULTI")).isEqualTo("+OK");
        assertThat(c.call("SET k")).startsWith("-ERR wrong number of arguments");
        assertThat(c.call("INCR k")).isEqualTo("+QUEUED");
        assertThat(c.call("EXEC")).startsWith("-EXECABORT");
        assertThat(c.call("GET k")).isEqualTo("2");             // nothing ran

        assertThat(c.call("MULTI")).isEqualTo("+OK");
        assertThat(c.call("MULTI")).isEqualTo("-ERR MULTI calls can not be nested");
        assertThat(c.call("BGSAVE")).isEqualTo("-ERR Command not allowed inside a transaction");
        assertThat(c.call("BGREWRITEAOF")).isEqualTo("-ERR Command not allowed inside a transaction");
        assertThat(c.call("DISCARD")).isEqualTo("+OK");
        assertThat(c.call("EXEC")).isEqualTo("-ERR EXEC without MULTI");
        assertThat(c.call("DISCARD")).isEqualTo("-ERR DISCARD without MULTI");
    }

    @Test
    void watchAbortsWhenAnotherClientWrites() {
        RawClient a = connect();
        RawClient b = connect();
        assertThat(a.call("SET balance 100")).isEqualTo("+OK");
        assertThat(a.call("WATCH balance")).isEqualTo("+OK");
        assertThat(b.call("INCRBY balance 5")).isEqualTo(":105");
        assertThat(a.call("MULTI")).isEqualTo("+OK");
        assertThat(a.call("DECRBY balance 10")).isEqualTo("+QUEUED");
        assertThat(a.call("EXEC")).isEqualTo("(nil)");
        assertThat(a.call("GET balance")).isEqualTo("105");
        // not invalidated when the watched key is untouched
        assertThat(a.call("WATCH balance")).isEqualTo("+OK");
        assertThat(b.call("SET other 1")).isEqualTo("+OK");
        assertThat(a.call("MULTI")).isEqualTo("+OK");
        assertThat(a.call("DECRBY balance 10")).isEqualTo("+QUEUED");
        assertThat(a.call("EXEC")).isEqualTo("[:95]");
        // FLUSHALL invalidates
        assertThat(a.call("WATCH balance")).isEqualTo("+OK");
        assertThat(b.call("FLUSHALL")).isEqualTo("+OK");
        assertThat(a.call("MULTI")).isEqualTo("+OK");
        assertThat(a.call("SET balance 1")).isEqualTo("+QUEUED");
        assertThat(a.call("EXEC")).isEqualTo("(nil)");
    }

    @Test
    void blockingPopIsServedByALaterPushInArrivalOrder() {
        RawClient w1 = connect();
        RawClient w2 = connect();
        RawClient p = connect();
        w1.send("BLPOP q 0");
        awaitBlocked(p, 1);
        w2.send("BLPOP q 0");
        awaitBlocked(p, 2);
        assertThat(p.call("RPUSH q a b c")).isEqualTo(":3");
        assertThat(R.render(w1.read())).isEqualTo("[q, a]");
        assertThat(R.render(w2.read())).isEqualTo("[q, b]");
        assertThat(p.call("LRANGE q 0 -1")).isEqualTo("[c]");
    }

    @Test
    void blockingTimeoutsAndVariants() {
        RawClient c = connect();
        RawClient p = connect();
        long t0 = System.nanoTime();
        assertThat(c.call("BLPOP none 0.3")).isEqualTo("(nil)");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isBetween(250L, 1_500L);
        assertThat(c.call("MULTI")).isEqualTo("+OK");
        assertThat(c.call("BLPOP none 0")).isEqualTo("+QUEUED");
        assertThat(c.call("EXEC")).isEqualTo("[(nil)]");              // never blocks inside a transaction
        c.send("BLMOVE src dst LEFT RIGHT 0");
        assertThat(p.call("RPUSH src x")).isEqualTo(":1");
        assertThat(R.render(c.read())).isEqualTo("x");
        assertThat(p.call("LRANGE dst 0 -1")).isEqualTo("[x]");
        c.send("BZPOPMIN z1 z2 0");
        assertThat(p.call("ZADD z2 5 m")).isEqualTo(":1");
        assertThat(R.render(c.read())).isEqualTo("[z2, m, 5]");
    }

    @Test
    void aDisconnectedWaiterConsumesNothing() throws Exception {
        RawClient w = connect();
        RawClient p = connect();
        w.send("BLPOP q 0");
        awaitBlocked(p, 1);
        w.close();
        clients.remove(w);
        awaitBlocked(p, 0);
        assertThat(p.call("RPUSH q a")).isEqualTo(":1");
        assertThat(p.call("LLEN q")).isEqualTo(":1");
    }

    @Test
    void publishSubscribe() {
        RawClient s = connect();
        RawClient p = connect();
        assertThat(s.call("SUBSCRIBE chat")).isEqualTo("[subscribe, chat, :1]");
        s.send("PSUBSCRIBE news.*");
        assertThat(R.render(s.read())).isEqualTo("[psubscribe, news.*, :2]");
        assertThat(p.call("PUBLISH chat hello")).isEqualTo(":1");
        assertThat(R.render(s.read())).isEqualTo("[message, chat, hello]");
        assertThat(p.call("PUBLISH news.7 hi")).isEqualTo(":1");
        assertThat(R.render(s.read())).isEqualTo("[pmessage, news.*, news.7, hi]");
        assertThat(p.call("PUBLISH nobody x")).isEqualTo(":0");
        assertThat(p.call("PUBSUB NUMSUB chat nobody")).isEqualTo("[chat, :1, nobody, :0]");
        assertThat(p.call("PUBSUB NUMPAT")).isEqualTo(":1");
        assertThat(s.call("GET x")).startsWith("-ERR Can't execute 'get'");
        assertThat(s.call("PING")).isEqualTo("[pong, ]");
        assertThat(s.call("UNSUBSCRIBE chat")).isEqualTo("[unsubscribe, chat, :1]");
        assertThat(s.call("PUNSUBSCRIBE")).isEqualTo("[punsubscribe, news.*, :0]");
        assertThat(s.call("GET x")).isEqualTo("(nil)");          // back in normal mode
    }

    @Test
    void messagesToOneSubscriberKeepTheirOrder() {
        RawClient s = connect();
        RawClient p = connect();
        assertThat(s.call("SUBSCRIBE ch")).isEqualTo("[subscribe, ch, :1]");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            sb.append("PUBLISH ch ").append(i).append("\r\n");
        }
        p.writeRaw(sb.toString());
        for (int i = 0; i < 5_000; i++) {
            Reply r = s.read();
            assertThat(r.asList().get(2).asString()).isEqualTo(Integer.toString(i));
        }
    }

    @Test
    void clientAndServerCommands() {
        RawClient c = connect();
        assertThat(c.call("CLIENT SETNAME web-1")).isEqualTo("+OK");
        assertThat(c.call("CLIENT GETNAME")).isEqualTo("web-1");
        assertThat(c.call("CLIENT LIST")).contains("name=web-1");
        assertThat(c.call("CLIENT SETNAME \"bad name\"")).startsWith("-ERR Client names cannot contain spaces");
        assertThat(c.call("CONFIG GET maxclients")).isEqualTo("[maxclients, 1000]");
        assertThat(c.call("CONFIG SET slowlog-log-slower-than 0")).isEqualTo("+OK");
        assertThat(c.call("SET k v")).isEqualTo("+OK");
        assertThat(c.call("SLOWLOG LEN")).isNotEqualTo(":0");
        assertThat(c.call("SLOWLOG RESET")).isEqualTo("+OK");
        assertThat(c.call("CONFIG SET no-such-option 1")).startsWith("-ERR");
        assertThat(c.call("INFO server")).contains("# Server").contains("tcp_port:" + server.port());
        assertThat(c.call("INFO keyspace")).contains("db0:keys=1");
        assertThat(c.call("ECHO hi")).isEqualTo("hi");
        assertThat(c.call("SELECT 0")).isEqualTo("+OK");
        assertThat(c.call("SELECT 1")).isEqualTo("-ERR DB index is out of range");
        assertThat(c.call("HELLO 3")).startsWith("-NOPROTO");
        assertThat(c.call("QUIT")).isEqualTo("+OK");
        assertThat(c.closedByServer()).isTrue();
    }

    @Test
    void maxClientsIsEnforced() {
        RawClient admin = connect();
        assertThat(admin.call("CONFIG SET maxclients 2")).isEqualTo("+OK");
        RawClient second = connect();
        assertThat(second.call("PING")).isEqualTo("+PONG");
        RawClient third = connect();
        assertThat(R.render(third.read())).isEqualTo("-ERR max number of clients reached");
        assertThat(third.closedByServer()).isTrue();
    }

    @Test
    void oversizedBulkIsAProtocolError() {
        server.close();
        server = TcpServer.start(c -> c.protoMaxBulkLen(1024));
        RawClient c = connect();
        c.writeRaw("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5000\r\n");
        assertThat(R.render(c.read())).startsWith("-ERR Protocol error");
        assertThat(c.closedByServer()).isTrue();
    }

    @Test
    void authentication() {
        server.close();
        server = TcpServer.start(c -> c.requirepass("s3cret"));
        RawClient c = connect();
        assertThat(c.call("GET k")).startsWith("-NOAUTH");
        assertThat(c.call("PING")).startsWith("-NOAUTH");
        assertThat(c.call("AUTH wrong")).startsWith("-WRONGPASS");
        assertThat(c.call("AUTH s3cret")).isEqualTo("+OK");
        assertThat(c.call("GET k")).isEqualTo("(nil)");
    }
}
