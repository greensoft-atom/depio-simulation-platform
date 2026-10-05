package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.backend.common.RefusedConfiguration;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Against a store that demands a password, as production j-redis does. */
class StoreClientsTest {

    private static JRedisEmbedded store;

    @BeforeAll
    static void start() {
        store = JRedisEmbedded.start(EmbeddedConfig.inMemory().configure(c -> c.requirepass("s3cret")));
    }

    @AfterAll
    static void stop() {
        store.close();
    }

    @Test
    @Timeout(20)
    @DisplayName("the password is sent, and the store answers")
    void withThePassword() throws Exception {
        // The embedded store is in-process, not TCP, so the check open() makes is exercised on
        // a client built here; open() itself is exercised over TCP by the test below.
        try (JRedisClient c = store.newClient(b -> b.password("s3cret"))) {
            StoreClients.refuseIfUnauthenticated(c, "embedded");
            assertThat(c.ping().get(2, TimeUnit.SECONDS)).isEqualTo("PONG");
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("no password for a store that wants one is refused at start-up, not one request at a time")
    void withoutAPasswordIsRefused() {
        // Every process connected like this before: deployed next to a production store, each
        // would have started and then failed every request with NOAUTH.
        JRedisClient c = store.newClient();
        assertThatThrownBy(() -> StoreClients.refuseIfUnauthenticated(c, "embedded"))
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("BACKEND_STORE_PASSWORD_FILE");
    }

    @Test
    @Timeout(20)
    @DisplayName("with no events store named, results share the session store's client")
    void eventsDefaultToTheSessionStore() {
        JRedisClient session = store.newClient(b -> b.password("s3cret"));
        try {
            assertThat(StoreClients.openEvents(session, "test", Map.of())).isSameAs(session);
        } finally {
            session.close();
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("a named events store gets its own client, and a malformed address is refused")
    void eventsStoreNamed() throws Exception {
        JRedisClient session = store.newClient(b -> b.password("s3cret"));
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        try (JRedisClient events = StoreClients.openEvents(session, "test",
                Map.of("BACKEND_EVENTS_STORE", "127.0.0.1:" + deadPort))) {
            assertThat(events).isNotSameAs(session);
        } finally {
            session.close();
        }
        assertThatThrownBy(() -> StoreClients.openEvents(session, "test",
                Map.of("BACKEND_EVENTS_STORE", "no-port-here")))
                .isInstanceOf(RefusedConfiguration.class);
    }

    /** A real server over TCP; its data directory, where a replica lands an image, in {@code dir}. */
    private static com.jredis.server.JRedisServer tcpServer(java.nio.file.Path dir) throws Exception {
        com.jredis.server.config.ServerConfig c = new com.jredis.server.config.ServerConfig();
        c.port(0);
        c.bind(java.util.List.of("127.0.0.1"));
        c.appendonly(false);
        c.dir(dir.toString());
        c.requirepass("s3cret");
        c.primaryauth("s3cret");
        return com.jredis.server.JRedisServer.start(c, com.jredis.server.core.Clock.system(),
                (reason, cause, code) -> { }, null);
    }

    private static String send(JRedisClient c, String... args) {
        return c.sync().send((Object[]) args).toString();
    }

    /** Until the call succeeds, or 20 s: what happens while the primary changes. */
    private static <T> T eventually(java.util.concurrent.Callable<T> call) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            try {
                return call.call();
            } catch (com.jredis.client.JRedisException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("given both addresses of a store, a process uses the primary whichever comes first, and follows a promotion")
    void bothAddressesFollowThePrimary(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        com.jredis.server.JRedisServer primary = tcpServer(java.nio.file.Files.createDirectory(dir.resolve("p")));
        com.jredis.server.JRedisServer replica = tcpServer(java.nio.file.Files.createDirectory(dir.resolve("r")));
        try (JRedisClient p = JRedisClient.builder().address("127.0.0.1", primary.port()).password("s3cret").build().start();
             JRedisClient r = JRedisClient.builder().address("127.0.0.1", replica.port()).password("s3cret").build().start()) {
            send(r, "REPLICAOF", "127.0.0.1", Integer.toString(primary.port()));
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!send(r, "INFO", "replication").contains("primary_link_status:up")) {
                assertThat(System.nanoTime()).as("replica in sync").isLessThan(deadline);
                Thread.sleep(50);
            }
            String both = "127.0.0.1:" + replica.port() + ",127.0.0.1:" + primary.port();
            Map<String, String> env = Map.of("BACKEND_STORE_PASSWORD", "s3cret", "BACKEND_STORE_ADDRESSES", both,
                    "BACKEND_EVENTS_STORE", both);
            JRedisClient c = StoreClients.open("the-command-line-host", 1, "test", env);
            try (c; JRedisClient events = StoreClients.openEvents(c, "test-events", env)) {
                assertThat(eventually(() -> c.sync().set("a", "1"))).isTrue();
                assertThat(eventually(() -> events.sync().set("e", "1"))).isTrue();
                assertThat(p.sync().get("a")).as("on the primary").isEqualTo("1");
                assertThat(p.sync().get("e")).isEqualTo("1");
                primary.stop();                                  // its machine is lost
                send(r, "REPLICAOF", "NO", "ONE");               // and the operator promotes the replica
                assertThat(eventually(() -> c.sync().set("b", "2"))).isTrue();
                assertThat(eventually(() -> events.sync().set("f", "2"))).isTrue();
                assertThat(r.sync().get("b")).isEqualTo("2");
                assertThat(r.sync().get("f")).isEqualTo("2");
            }
        } finally {
            primary.stop();
            replica.stop();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("both addresses without the password are refused at start-up too, a dead one listed first")
    void aListWithoutThePasswordIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        com.jredis.server.JRedisServer server = tcpServer(dir);
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        try {
            assertThatThrownBy(() -> StoreClients.open("h", 1, "test", Map.of("BACKEND_STORE_ADDRESSES",
                    "127.0.0.1:" + deadPort + ",127.0.0.1:" + server.port())))
                    .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("requires a password");
        } finally {
            server.stop();
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("an address list as people write it, spaces after the commas, is read; an empty host is refused (the gateway review)")
    void addressesAreTrimmed() {
        assertThat(StoreClients.addresses(" 10.0.0.1:6379, 10.0.0.2:6379 ", "BACKEND_STORE_ADDRESSES"))
                .containsExactly("10.0.0.1:6379", "10.0.0.2:6379");
        assertThatThrownBy(() -> StoreClients.addresses(" :6379", "BACKEND_STORE_ADDRESSES"))
                .isInstanceOf(RefusedConfiguration.class);
    }

    @Test
    @Timeout(20)
    @DisplayName("the events store's missing password names the events store's setting (the gateway review)")
    void theEventsStoresRefusalNamesItsSetting() {
        JRedisClient c = store.newClient();
        assertThatThrownBy(() -> StoreClients.refuseIfUnauthenticated(c, "embedded", "BACKEND_EVENTS_STORE_PASSWORD"))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_EVENTS_STORE_PASSWORD_FILE");
    }

    @Test
    @Timeout(20)
    @DisplayName("the events store may be named by both its addresses, and a malformed one is refused")
    void anEventsStoreByBothAddresses() throws Exception {
        JRedisClient session = store.newClient(b -> b.password("s3cret"));
        try (JRedisClient events = StoreClients.openEvents(session, "test",
                Map.of("BACKEND_EVENTS_STORE", "127.0.0.1:1,127.0.0.1:2"))) {
            assertThat(events).isNotSameAs(session);
        } finally {
            session.close();
        }
        for (String bad : new String[] {"127.0.0.1:1,no-port", "127.0.0.1:1,", ",127.0.0.1:1", "127.0.0.1:x",
                "127.0.0.1:1,127.0.0.1:0", "127.0.0.1:70000"}) {
            assertThatThrownBy(() -> StoreClients.openEvents(session, "test", Map.of("BACKEND_EVENTS_STORE", bad)))
                    .as(bad).isInstanceOf(RefusedConfiguration.class);
            assertThatThrownBy(() -> StoreClients.open("h", 1, "test", Map.of("BACKEND_STORE_ADDRESSES", bad)))
                    .as(bad).isInstanceOf(RefusedConfiguration.class);
        }
    }

    @Test
    @DisplayName("results are replicated when the store that holds them was named with its replica")
    void whetherResultsAreReplicated() {
        assertThat(StoreClients.eventsReplicated(Map.of())).isFalse();
        assertThat(StoreClients.eventsReplicated(Map.of("BACKEND_EVENTS_STORE", "a:1"))).isFalse();
        assertThat(StoreClients.eventsReplicated(Map.of("BACKEND_EVENTS_STORE", "a:1,b:1"))).isTrue();
        assertThat(StoreClients.eventsReplicated(Map.of("BACKEND_STORE_ADDRESSES", "a:1,b:1")))
                .as("no events store: results share the session store, replicated").isTrue();
        assertThat(StoreClients.eventsReplicated(Map.of("BACKEND_STORE_ADDRESSES", "a:1,b:1", "BACKEND_EVENTS_STORE", "c:1")))
                .as("an events store of its own, alone").isFalse();
        assertThat(StoreClients.eventsReplicated(Map.of("BACKEND_STORE_ADDRESSES", "a:1"))).isFalse();
    }

    @Test
    @Timeout(20)
    @DisplayName("a store that is not there yet is not a reason to refuse: it may be starting")
    void unreachableIsNotRefused() throws Exception {
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        try (JRedisClient c = StoreClients.open("127.0.0.1", deadPort, "test",
                Map.of("BACKEND_STORE_PASSWORD", "s3cret"))) {
            assertThat(c).isNotNull();
        }
    }

    @Test
    @DisplayName("a primary's replicas, as its INFO says: how many, the slowest's bytes unacknowledged, the longest silence (D-58)")
    void replicasFromInfo() {
        String info = "# Replication\r\nrole:primary\r\nreplication_epoch:2\r\nreplid:abc\r\nrepl_offset:5000\r\n"
                + "connected_replicas:2\r\nreplica0:ip=10.0.0.3,port=6379,state=online,offset=4200,lag=7\r\n"
                + "replica1:ip=10.0.0.2,port=6379,state=online,offset=4990,lag=1\r\nfull_syncs:2\r\n";   // the slowest first
        assertThat(StoreClients.replicas(info)).isEqualTo(new StoreClients.Replicas(2, 800, 7));
        assertThat(StoreClients.replicas("# Replication\r\nrole:primary\r\nrepl_offset:5000\r\nconnected_replicas:0\r\n"))
                .as("none").isEqualTo(new StoreClients.Replicas(0, 0, 0));
    }

    @Test
    @Timeout(60)
    @DisplayName("against a primary and its replica: one connected and acknowledging; stopped, none (D-58)")
    void replicasOfARealPrimary(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        com.jredis.server.JRedisServer primary = tcpServer(java.nio.file.Files.createDirectory(dir.resolve("p")));
        com.jredis.server.JRedisServer replica = tcpServer(java.nio.file.Files.createDirectory(dir.resolve("r")));
        try (JRedisClient p = JRedisClient.builder().address("127.0.0.1", primary.port()).password("s3cret").build().start();
             JRedisClient r = JRedisClient.builder().address("127.0.0.1", replica.port()).password("s3cret").build().start()) {
            assertThat(StoreClients.replicas(p).connected()).as("none yet").isZero();
            send(r, "REPLICAOF", "127.0.0.1", Integer.toString(primary.port()));
            p.sync().set("k", "v");
            long deadline = System.nanoTime() + 20_000_000_000L;
            StoreClients.Replicas seen = StoreClients.replicas(p);
            while (seen.connected() != 1 || seen.behindBytes() != 0) {
                assertThat(System.nanoTime()).as("connected and caught up: " + seen).isLessThan(deadline);
                Thread.sleep(50);
                seen = StoreClients.replicas(p);
            }
            assertThat(seen.ackSeconds()).isLessThan(3);
            replica.stop();
            while (StoreClients.replicas(p).connected() != 0) {
                assertThat(System.nanoTime()).as("the replica gone").isLessThan(deadline + 20_000_000_000L);
                Thread.sleep(50);
            }
        } finally {
            primary.stop();
            replica.stop();
        }
    }
}
