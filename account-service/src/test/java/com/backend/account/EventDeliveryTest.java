package com.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.backend.account.db.Database;
import com.backend.account.db.Outbox;
import com.backend.account.db.TestClock;
import com.backend.account.db.TestDatabase;
import com.backend.account.db.Tx;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventDeliveryTest {

    private static Database db;
    private final TestClock clock = new TestClock(Instant.parse("2026-11-26T10:00:00Z"));
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private Outbox outbox;

    @BeforeAll
    static void setUp() {
        db = TestDatabase.open();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() throws Exception {
        TestDatabase.empty(db);
        outbox = new Outbox(db.dataSource());
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** A receiver answering each event with the next status given, the last one for ever. */
    private URI receiver(List<String> bodies, List<String> types, int... statuses) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger n = new AtomicInteger();
        s.createContext("/v1/account-events", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII));
            types.add(ex.getRequestHeaders().getFirst("Content-Type"));
            int i = Math.min(n.getAndIncrement(), statuses.length - 1);
            ex.sendResponseHeaders(statuses[i], -1);
            ex.close();
        });
        s.start();
        servers.add(s);
        return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/v1/account-events");
    }

    private void event(String token) throws Exception {
        new Tx(db.dataSource()).execute(c -> {
            outbox.add(c, com.backend.account.token.TokenIssuer.randomId(), "deleted", token, clock.instant());
            return null;
        });
    }

    @Test
    @DisplayName("an event is posted as application/secevent+jwt, its token the body; 202 takes it")
    void taken() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> types = new CopyOnWriteArrayList<>();
        URI to = receiver(bodies, types, 202);
        event("e.y.j");
        try (EventDelivery d = new EventDelivery(outbox, List.of(to), HttpClient.newHttpClient(), clock)) {
            assertThat(d.deliverDue()).isEqualTo(1);
            assertThat(d.deliverDue()).as("taken once").isZero();
            assertThat(d.delivered()).isEqualTo(1);
        }
        assertThat(bodies).containsExactly("e.y.j");
        assertThat(types).containsExactly("application/secevent+jwt");
        assertThat(outbox.pending()).isZero();
    }

    @Test
    @DisplayName("a receiver that does not take it, then the next in the list; none taking it, again later and later")
    void retried() throws Exception {
        List<String> first = new CopyOnWriteArrayList<>();
        List<String> second = new CopyOnWriteArrayList<>();
        List<String> types = new CopyOnWriteArrayList<>();
        URI down = receiver(first, types, 503);
        URI refusing = receiver(second, types, 400, 400, 202);
        event("e.y.j");
        try (EventDelivery d = new EventDelivery(outbox, List.of(down, refusing), HttpClient.newHttpClient(), clock)) {
            assertThat(d.deliverDue()).isZero();
            assertThat(first).hasSize(1);
            assertThat(second).as("the next in the list tried").hasSize(1);
            assertThat(d.failures()).isEqualTo(1);
            assertThat(d.deliverDue()).as("put off").isZero();
            clock.advance(EventDelivery.backoff(1));
            assertThat(d.deliverDue()).isZero();
            clock.advance(EventDelivery.backoff(2));
            assertThat(d.deliverDue()).isEqualTo(1);
        }
        assertThat(second).hasSize(3);
        assertThat(outbox.pending()).isZero();
    }

    @Test
    @DisplayName("202 alone takes it: a URL that answers 200, as a wrong route might, takes nothing")
    void onlyA202() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        URI wrong = receiver(bodies, new CopyOnWriteArrayList<>(), 200);
        event("e.y.j");
        try (EventDelivery d = new EventDelivery(outbox, List.of(wrong), HttpClient.newHttpClient(), clock)) {
            assertThat(d.deliverDue()).isZero();
        }
        assertThat(bodies).hasSize(1);
        assertThat(outbox.pending()).isEqualTo(1);
    }

    @Test
    @DisplayName("the wait doubles from a second, to at most five minutes")
    void backoff() {
        assertThat(EventDelivery.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(EventDelivery.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(EventDelivery.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(EventDelivery.backoff(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(EventDelivery.backoff(100)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a receiver not reachable at all is a failure like any other, and the event waits")
    void unreachable() throws Exception {
        event("e.y.j");
        try (EventDelivery d = new EventDelivery(outbox, List.of(URI.create("http://127.0.0.1:9/v1/account-events")),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), clock)) {
            assertThat(d.deliverDue()).isZero();
            assertThat(d.failures()).isEqualTo(1);
        }
        assertThat(outbox.pending()).isEqualTo(1);
    }
}
