package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.backend.handoff.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The gateway over a real WebSocket, against a real store.
 *
 * The client is the JDK's, so what is tested is whether an ordinary WebSocket client can
 * talk to it, handshake included. `platform` is a stub here on purpose: this suite is about
 * what the gateway does with an answer, and the real platform's behaviour is covered by its
 * own tests against its own MySQL.
 */
class GatewayServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JRedisEmbedded store;
    private static JRedisClient jredis;
    private static SessionStore sessions;
    private static HttpServer platform;
    private static GatewayServer gateway;
    private static String url;

    /** What the stubbed platform answers next. */
    private static volatile int platformStatus = 200;
    private static volatile String platformBody =
            "{\"arenaHost\":\"10.0.0.7\",\"arenaPort\":9001,\"ticketId\":\"tkt-1\"}";
    private static final AtomicBoolean platformCalled = new AtomicBoolean();
    /** What the stubbed platform's queue was last asked: method, then body. */
    private static final BlockingQueue<String> queueCalls = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> partyCalls = new LinkedBlockingQueue<>();
    private static volatile int queueStatus = 200;

    @BeforeAll
    static void setUp() throws Exception {
        store = JRedisEmbedded.start();
        jredis = store.newClient();
        sessions = new SessionStore(jredis);

        platform = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/v1/match-requests", exchange -> {
            platformCalled.set(true);
            byte[] body = platformBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(platformStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platform.createContext("/v1/queue", exchange -> {
            String sub = exchange.getRequestURI().getPath().substring("/v1/queue".length());
            queueCalls.add((sub.isEmpty() ? "" : sub + " ") + exchange.getRequestMethod() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (queueStatus == 200 ? "{\"state\":\"queued\",\"mode\":\"duel\"}"
                    : "{\"code\":\"already_queued\",\"message\":\"already in a queue\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(queueStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platform.createContext("/v1/party", exchange -> {
            partyCalls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"partyId\":\"p-1\",\"leader\":4711,\"members\":[]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platform.start();

        gateway = new GatewayServer(jredis,
                "http://127.0.0.1:" + platform.getAddress().getPort(), "gateway-test", 2);
        int port = gateway.start("127.0.0.1", 0);
        url = "ws://127.0.0.1:" + port + "/lobby";
    }

    @AfterAll
    static void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (platform != null) {
            platform.stop(0);
        }
        if (store != null) {
            store.close();
        }
    }

    @BeforeEach
    void fresh() {
        jredis.sync().send("FLUSHALL");
        platformStatus = 200;
        platformBody = "{\"arenaHost\":\"10.0.0.7\",\"arenaPort\":9001,\"ticketId\":\"tkt-1\"}";
        platformCalled.set(false);
        queueCalls.clear();
        partyCalls.clear();
        queueStatus = 200;
    }

    // ---- a minimal lobby client ----------------------------------------------------------

    private static final class Lobby implements AutoCloseable {
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private final WebSocket socket;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lobby() throws Exception {
            this(url);
        }

        Lobby(String at) throws Exception {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(at), new WebSocket.Listener() {
                        private final StringBuilder partial = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data,
                                                         boolean last) {
                            partial.append(data);
                            if (last) {
                                inbox.add(partial.toString());
                                partial.setLength(0);
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                            closed.set(true);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            closed.set(true);
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        /** One message in two frames, as a client library may send a long one. */
        void sendInTwo(String json) {
            int half = json.length() / 2;
            socket.sendText(json.substring(0, half), false).join();
            socket.sendText(json.substring(half), true).join();
        }

        void ping() {
            socket.sendPing(java.nio.ByteBuffer.wrap(new byte[] {1})).join();
        }

        void auth(String token, int id) {
            send("{\"t\":\"auth\",\"id\":%d,\"d\":{\"token\":\"%s\"}}".formatted(id, token));
        }

        /** @return the next frame, or null if none arrives in time. */
        JsonNode next() throws Exception {
            String text = inbox.poll(10, TimeUnit.SECONDS);
            return text == null ? null : MAPPER.readTree(text);
        }

        boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private static String issueSession(long playerId) throws Exception {
        return sessions.create(playerId).get(5, TimeUnit.SECONDS);
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    @Timeout(60)
    @DisplayName("a valid session token authenticates the connection")
    void authenticate() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);

            JsonNode reply = lobby.next();
            assertThat(reply.get("t").asText()).isEqualTo("auth.ok");
            assertThat(reply.get("id").asInt()).as("the reply echoes the request id").isEqualTo(1);
            assertThat(reply.path("d").get("playerId").asLong()).isEqualTo(4711L);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("an unknown token is refused and the connection is closed")
    void badToken() throws Exception {
        try (Lobby lobby = new Lobby()) {
            lobby.auth("not-a-real-token", 1);

            JsonNode reply = lobby.next();
            assertThat(reply.get("t").asText()).isEqualTo("error");
            assertThat(reply.path("d").get("code").asText()).isEqualTo("invalid_session");

            // Holding the socket open for a client that cannot authenticate costs a file
            // descriptor for nothing.
            for (int i = 0; i < 50 && !lobby.isClosed(); i++) {
                Thread.sleep(100);
            }
            assertThat(lobby.isClosed()).isTrue();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("nothing but auth works before authenticating")
    void everythingNeedsAuthFirst() throws Exception {
        try (Lobby lobby = new Lobby()) {
            lobby.send("{\"t\":\"match.request\",\"id\":7}");

            JsonNode reply = lobby.next();
            assertThat(reply.get("t").asText()).isEqualTo("error");
            assertThat(reply.get("id").asInt()).isEqualTo(7);
            assertThat(reply.path("d").get("code").asText()).isEqualTo("not_authenticated");
            assertThat(platformCalled).as("and platform is never troubled with it").isFalse();
        }
    }

    /** One labelled series of a histogram, as a scrape renders it: its count. */
    private static long countOf(String family, String route) {
        com.backend.common.Metrics m = new com.backend.common.Metrics();
        gateway.registerMetrics(m);
        String prefix = family + "_count{route=\"" + route + "\"} ";
        for (String line : m.render().split("\n")) {
            if (line.startsWith(prefix)) {
                return Long.parseLong(line.substring(prefix.length()));
            }
        }
        return 0;
    }

    /** A series with no labels, as a scrape renders it. */
    private static double valueOf(GatewayServer g, String name) {
        com.backend.common.Metrics m = new com.backend.common.Metrics();
        g.registerMetrics(m);
        for (String line : m.render().split("\n")) {
            if (line.startsWith(name + " ")) {
                return Double.parseDouble(line.substring(name.length() + 1));
            }
        }
        throw new AssertionError(name + " is not reported");
    }

    @Test
    @Timeout(60)
    @DisplayName("the subscription lost and made again, every lobby here is told to fetch what it missed (03 §5, O-8)")
    void aResubscriptionResyncsTheLobby() throws Exception {
        String token = issueSession(4731);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            assertThat(valueOf(gateway, "backend_gateway_store_subscribed")).isEqualTo(1);
            double before = valueOf(gateway, "backend_gateway_resubscribed_total");
            jredis.sync().send("CLIENT", "KILL", "TYPE", "pubsub");     // as a store's restart, or a demotion, does
            JsonNode got = lobby.next();
            assertThat(got).as("told within 10 s").isNotNull();
            assertThat(got.get("t").asText()).isEqualTo("evt.resync");
            assertThat(valueOf(gateway, "backend_gateway_resubscribed_total")).isEqualTo(before + 1);
            assertThat(valueOf(gateway, "backend_gateway_store_subscribed")).isEqualTo(1);
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("a gateway started while the store could not be reached hears push:all once it can (03 §5, O-7)")
    void pushAllIsHeardThoughTheStoreWasDownAtStart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        JRedisClient late = JRedisClient.builder().address("127.0.0.1", port).reconnectBackoffMillis(50, 200)
                .build().start();
        try (late; GatewayServer g = new GatewayServer(late,
                "http://127.0.0.1:" + platform.getAddress().getPort(), "gateway-late", 1)) {
            int at = g.start("127.0.0.1", 0);                      // its subscriptions unconfirmed
            assertThat(valueOf(g, "backend_gateway_store_subscribed")).isZero();
            com.jredis.server.config.ServerConfig c = new com.jredis.server.config.ServerConfig();
            c.port(port);
            c.appendonly(false);
            c.dir(dir.toString());
            com.jredis.server.JRedisServer server = com.jredis.server.JRedisServer.start(c);
            try {
                for (long until = System.nanoTime() + 10_000_000_000L; !late.isConnected(); Thread.sleep(50)) {
                    assertThat(System.nanoTime()).as("the client reconnects").isLessThan(until);
                }
                String token = new SessionStore(late).create(4741).get(10, TimeUnit.SECONDS);
                try (Lobby lobby = new Lobby("ws://127.0.0.1:" + at + "/lobby")) {
                    lobby.auth(token, 1);
                    assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
                    com.backend.handoff.LobbyPush push = new com.backend.handoff.LobbyPush(late);
                    long deadline = System.nanoTime() + 20_000_000_000L;
                    while (push.broadcast("evt.notice", MAPPER.createObjectNode().put("text", "Back soon"))
                            .get(5, TimeUnit.SECONDS) == 0) {
                        assertThat(System.nanoTime()).as("push:all subscribed").isLessThan(deadline);
                        Thread.sleep(100);
                    }
                    JsonNode got = lobby.next();
                    while (got != null && got.get("t").asText().equals("evt.resync")) {
                        got = lobby.next();
                    }
                    assertThat(got.get("t").asText()).isEqualTo("evt.notice");
                    assertThat(valueOf(g, "backend_gateway_store_subscribed")).isEqualTo(1);
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("each call to platform is timed, by its route: the gateway's latency to platform (03 §10)")
    void platformCallsAreTimed() throws Exception {
        String token = issueSession(4711);
        long before = countOf("backend_gateway_platform_seconds", "/v1/match-requests");
        long queueBefore = countOf("backend_gateway_platform_seconds", "/v1/queue");
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            lobby.send("{\"t\":\"match.request\",\"id\":2}");
            assertThat(lobby.next().get("t").asText()).isEqualTo("match.request.ok");
        }
        assertThat(countOf("backend_gateway_platform_seconds", "/v1/match-requests")).isEqualTo(before + 1);
        assertThat(countOf("backend_gateway_platform_seconds", "/v1/queue")).as("by its own route").isEqualTo(queueBefore);
    }

    @Test
    @Timeout(60)
    @DisplayName("a match request is forwarded and its answer passed back")
    void matchRequest() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");

            lobby.send("{\"t\":\"match.request\",\"id\":2}");

            JsonNode reply = lobby.next();
            assertThat(reply.get("t").asText()).isEqualTo("match.request.ok");
            assertThat(reply.get("id").asInt()).isEqualTo(2);
            assertThat(reply.path("d").get("arenaHost").asText()).isEqualTo("10.0.0.7");
            assertThat(reply.path("d").get("arenaPort").asInt()).isEqualTo(9001);
            assertThat(reply.path("d").get("ticketId").asText()).isEqualTo("tkt-1");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("platform's refusal is passed through, not reinterpreted")
    void platformRefusal() throws Exception {
        String token = issueSession(4711);
        platformStatus = 503;
        platformBody = "{\"code\":\"no_arena\",\"message\":\"no arena has room\"}";

        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");

            lobby.send("{\"t\":\"match.request\",\"id\":3}");

            JsonNode reply = lobby.next();
            assertThat(reply.get("t").asText()).isEqualTo("error");
            // The gateway makes no product decisions: platform said no_arena, so the client
            // hears no_arena rather than something the gateway invented.
            assertThat(reply.path("d").get("code").asText()).isEqualTo("no_arena");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("an unknown message type is answered, not ignored")
    void unknownType() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");

            lobby.send("{\"t\":\"team.invite\",\"id\":9,\"d\":{}}");

            // A newer client talking to an older gateway must get a clear failure rather
            // than a request that never completes.
            JsonNode reply = lobby.next();
            assertThat(reply.get("t").asText()).isEqualTo("error");
            assertThat(reply.get("id").asInt()).isEqualTo(9);
            assertThat(reply.path("d").get("code").asText()).isEqualTo("unknown_type");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("malformed input is answered rather than dropping the connection silently")
    void malformedInput() throws Exception {
        try (Lobby lobby = new Lobby()) {
            lobby.send("{ this is not json");
            assertThat(lobby.next().path("d").get("code").asText()).isEqualTo("bad_json");

            lobby.send("{\"id\":4}");
            assertThat(lobby.next().path("d").get("code").asText()).isEqualTo("no_type");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the connection is registered where other processes can find it")
    void registration() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");

            // gatewayId#nonce: a reader routes by the gateway, and the nonce is what lets
            // this registration's cleanup tell its own entry from a newer one (T-6).
            assertThat(jredis.sync().get("conn:4711").split("#")[0]).isEqualTo("gateway-test");
            assertThat(jredis.sync().ttl("conn:4711"))
                    .as("and it expires on its own if this gateway dies")
                    .isBetween(1L, (long) ConnectionRegistry.TTL_SECONDS);
            assertThat(gateway.connectionCount()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("evt.session.revoked is written, then the connection closed: the player was banned (04 §10)")
    void aRevokedSessionIsClosed() throws Exception {
        String token = issueSession(4711);
        try (JRedisClient elsewhere = store.newClient(); Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            new com.backend.handoff.LobbyPush(elsewhere).send(4711, "evt.session.revoked", null)
                    .get(5, TimeUnit.SECONDS);
            assertThat(lobby.next().get("t").asText()).isEqualTo("evt.session.revoked");
            for (int i = 0; i < 250 && !lobby.isClosed(); i++) {
                Thread.sleep(20);
            }
            assertThat(lobby.isClosed()).as("closed by the gateway").isTrue();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("auth.ok means the player can be reached: their connection's registration has landed first (T-39)")
    void authOkMeansReachable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        com.jredis.server.config.ServerConfig c = new com.jredis.server.config.ServerConfig();
        c.port(port);
        c.appendonly(false);
        c.dir(dir.toString());
        com.jredis.server.JRedisServer server = com.jredis.server.JRedisServer.start(c);
        // The gateway's store holds what it is sent 300 ms: the registration lands well after a
        // push sent the moment auth.ok arrives looks for it, unless auth.ok waited for it.
        try (SlowProxy slow = new SlowProxy(port, 300);
             JRedisClient through = JRedisClient.builder().address("127.0.0.1", slow.port()).build().start();
             JRedisClient direct = JRedisClient.builder().address("127.0.0.1", port).build().start();
             GatewayServer g = new GatewayServer(through, "http://127.0.0.1:" + platform.getAddress().getPort(),
                     "gateway-slow", 1)) {
            int at = g.start("127.0.0.1", 0);
            for (long until = System.nanoTime() + 10_000_000_000L;
                 valueOf(g, "backend_gateway_store_subscribed") == 0; Thread.sleep(50)) {
                assertThat(System.nanoTime()).as("subscribed").isLessThan(until);
            }
            String token = new SessionStore(direct).create(4751).get(10, TimeUnit.SECONDS);
            try (Lobby lobby = new Lobby("ws://127.0.0.1:" + at + "/lobby")) {
                lobby.auth(token, 1);
                assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
                assertThat(new com.backend.handoff.LobbyPush(direct).send(4751, "evt.session.revoked", null)
                        .get(5, TimeUnit.SECONDS)).as("routed: the registration is there").isTrue();
                assertThat(lobby.next().get("t").asText()).isEqualTo("evt.session.revoked");
            }
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a registration the store refuses is answered internal and the connection closed: nothing could reach the player (T-39)")
    void aRefusedRegistrationIsClosed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        com.jredis.server.config.ServerConfig c = new com.jredis.server.config.ServerConfig();
        c.port(port);
        c.appendonly(false);
        c.dir(dir.toString());
        com.jredis.server.JRedisServer server = com.jredis.server.JRedisServer.start(c);
        try (JRedisClient direct = JRedisClient.builder().address("127.0.0.1", port).build().start();
             GatewayServer g = new GatewayServer(direct, "http://127.0.0.1:" + platform.getAddress().getPort(),
                     "gateway-refused", 1)) {
            int at = g.start("127.0.0.1", 0);
            String token = new SessionStore(direct).create(4761).get(10, TimeUnit.SECONDS);
            // From here the store reads but refuses every write, as one short of its replicas does.
            direct.send("CONFIG", "SET", "min-replicas-to-write", "1").get(5, TimeUnit.SECONDS);
            try (Lobby lobby = new Lobby("ws://127.0.0.1:" + at + "/lobby")) {
                lobby.auth(token, 1);
                JsonNode answer = lobby.next();
                assertThat(answer.get("t").asText()).isEqualTo("error");
                assertThat(answer.get("d").get("code").asText()).isEqualTo("internal");
                for (int i = 0; i < 250 && !lobby.isClosed(); i++) {
                    Thread.sleep(20);
                }
                assertThat(lobby.isClosed()).as("closed by the gateway").isTrue();
            }
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a notice to everyone reaches every lobby connection this gateway holds (04 §10)")
    void aNoticeReachesEveryone() throws Exception {
        String ada = issueSession(4721);
        String bob = issueSession(4722);
        try (JRedisClient elsewhere = store.newClient(); Lobby a = new Lobby(); Lobby b = new Lobby()) {
            a.auth(ada, 1);
            assertThat(a.next().get("t").asText()).isEqualTo("auth.ok");
            b.auth(bob, 1);
            assertThat(b.next().get("t").asText()).isEqualTo("auth.ok");
            long heard = new com.backend.handoff.LobbyPush(elsewhere)
                    .broadcast("evt.notice", MAPPER.createObjectNode().put("text", "Back soon")).get(5, TimeUnit.SECONDS);
            assertThat(heard).as("this gateway listens").isPositive();
            for (Lobby l : new Lobby[] {a, b}) {
                JsonNode got = l.next();
                assertThat(got.get("t").asText()).isEqualTo("evt.notice");
                assertThat(got.path("d").get("text").asText()).isEqualTo("Back soon");
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a push from another process reaches the player's socket, found by the registration (03 §5)")
    void pushesReachThePlayer() throws Exception {
        String token = issueSession(4711);
        try (JRedisClient elsewhere = store.newClient(); Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            com.backend.handoff.LobbyPush push = new com.backend.handoff.LobbyPush(elsewhere);

            // As platform will announce a match: by player id, knowing nothing of gateways.
            ObjectNode found = MAPPER.createObjectNode().put("arenaHost", "10.0.0.7").put("arenaPort", 9001);
            assertThat(push.send(4711, "evt.match.found", found).get(5, TimeUnit.SECONDS))
                    .as("this gateway was listening").isTrue();
            JsonNode got = lobby.next();
            assertThat(got.get("t").asText()).isEqualTo("evt.match.found");
            assertThat(got.has("id")).as("a push has no id: it answers nothing").isFalse();
            assertThat(got.path("d").get("arenaPort").asInt()).isEqualTo(9001);

            assertThat(push.send(4712, "evt.match.found", found).get(5, TimeUnit.SECONDS))
                    .as("a player with no lobby connection is not found").isFalse();
            assertThat(push.connected(4711).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(push.connected(4712).get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a connection that cannot take more has its pushes held, and an overflow is told as one evt.resync (03 §8)")
    void aSlowConnectionsPushesAreHeld() throws Exception {
        String token = issueSession(4711);
        com.backend.common.Metrics metrics = new com.backend.common.Metrics();
        gateway.registerMetrics(metrics);
        try (JRedisClient elsewhere = store.newClient(); Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            io.netty.channel.Channel ch = gateway.registry().channelOf(4711);
            // As Netty marks a socket with more than its high-water mark waiting to go out.
            ch.eventLoop().submit(() -> ch.unsafe().outboundBuffer().setUserDefinedWritability(1, false))
                    .get(5, TimeUnit.SECONDS);
            long held = heldSoFar(metrics);
            long drained = drainedSoFar(metrics);
            com.backend.handoff.LobbyPush push = new com.backend.handoff.LobbyPush(elsewhere);
            for (int i = 0; i <= Pushes.RING; i++) {
                push.send(4711, "evt.party.update", MAPPER.createObjectNode().put("n", i)).get(5, TimeUnit.SECONDS);
            }
            for (int i = 0; i < 250 && heldSoFar(metrics) < held + Pushes.RING + 1; i++) {
                Thread.sleep(20);
            }
            assertThat(heldSoFar(metrics)).as("all seventeen held").isEqualTo(held + Pushes.RING + 1);
            ch.eventLoop().submit(() -> ch.unsafe().outboundBuffer().setUserDefinedWritability(1, true))
                    .get(5, TimeUnit.SECONDS);
            assertThat(lobby.next().get("t").asText()).isEqualTo("evt.resync");
            assertThat(drainedSoFar(metrics)).as("the spell timed, as drained (03 §10)").isEqualTo(drained + 1);
            push.send(4711, "evt.party.update", MAPPER.createObjectNode().put("n", 99)).get(5, TimeUnit.SECONDS);
            JsonNode after = lobby.next();
            assertThat(after.path("d").get("n").asInt()).as("the backlog is gone, and the next push goes through").isEqualTo(99);
        }
    }

    private static long drainedSoFar(com.backend.common.Metrics metrics) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("backend_gateway_unwritable_seconds_count\\{end=\"drained\"\\} (\\d+)").matcher(metrics.render());
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static long heldSoFar(com.backend.common.Metrics metrics) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("backend_gateway_pushes_total\\{outcome=\"held\"\\} (\\d+)").matcher(metrics.render());
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    @Test
    @Timeout(60)
    @DisplayName("queue.join and queue.leave are forwarded with the session, and a refusal is passed through")
    void queueRequestsAreForwarded() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");

            lobby.send("{\"t\":\"queue.join\",\"id\":2,\"d\":{\"mode\":\"duel\"}}");
            JsonNode ok = lobby.next();
            assertThat(ok.get("t").asText()).isEqualTo("queue.join.ok");
            assertThat(ok.get("id").asInt()).isEqualTo(2);
            assertThat(ok.path("d").get("state").asText()).isEqualTo("queued");
            String call = queueCalls.poll(5, TimeUnit.SECONDS);
            assertThat(call).startsWith("POST ").contains("\"mode\":\"duel\"").endsWith("Bearer " + token);

            queueStatus = 409;
            lobby.send("{\"t\":\"queue.join\",\"id\":3,\"d\":{\"mode\":\"duel\"}}");
            JsonNode refused = lobby.next();
            assertThat(refused.get("t").asText()).isEqualTo("error");
            assertThat(refused.path("d").get("code").asText()).isEqualTo("already_queued");
            queueCalls.poll(5, TimeUnit.SECONDS);

            queueStatus = 200;
            lobby.send("{\"t\":\"queue.leave\",\"id\":4}");
            assertThat(lobby.next().get("t").asText()).isEqualTo("queue.leave.ok");
            assertThat(queueCalls.poll(5, TimeUnit.SECONDS)).startsWith("DELETE ");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("party.invite, accept, leave and kick are forwarded to their paths with the session and their fields (04 §4)")
    void partyRequestsAreForwarded() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            String[][] cases = {
                    {"party.invite", "{\"playerId\":9}", "POST /v1/party/invite {\"playerId\":9}"},
                    {"party.accept", "{\"partyId\":\"p-1\",\"extra\":1}", "POST /v1/party/accept {\"partyId\":\"p-1\"}"},
                    {"party.kick", "{\"playerId\":9}", "POST /v1/party/kick {\"playerId\":9}"},
                    {"party.leave", "{}", "POST /v1/party/leave {}"},
                    {"party.invite", "{}", "POST /v1/party/invite {}"},
                    {"party.say", "{\"phraseId\":3,\"x\":1}", "POST /v1/party/say {\"phraseId\":3}"},
            };
            int id = 10;
            for (String[] c : cases) {
                lobby.send("{\"t\":\"%s\",\"id\":%d,\"d\":%s}".formatted(c[0], ++id, c[1]));
                JsonNode ok = lobby.next();
                assertThat(ok.get("t").asText()).isEqualTo(c[0] + ".ok");
                assertThat(ok.get("id").asInt()).isEqualTo(id);
                assertThat(ok.path("d").get("partyId").asText()).isEqualTo("p-1");
                assertThat(partyCalls.poll(5, TimeUnit.SECONDS)).isEqualTo(c[2] + " Bearer " + token);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("match.accept and match.decline are forwarded with the session and only the matchUid (04 §4)")
    void answersAreForwarded() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
            lobby.send("{\"t\":\"match.accept\",\"id\":2,\"d\":{\"matchUid\":\"m-1\",\"x\":1}}");
            JsonNode ok = lobby.next();
            assertThat(ok.get("t").asText()).isEqualTo("match.accept.ok");
            assertThat(ok.get("id").asInt()).isEqualTo(2);
            assertThat(queueCalls.poll(5, TimeUnit.SECONDS)).isEqualTo("/accept POST {\"matchUid\":\"m-1\"} Bearer " + token);
            lobby.send("{\"t\":\"match.decline\",\"id\":3,\"d\":{\"matchUid\":\"m-1\"}}");
            assertThat(lobby.next().get("t").asText()).isEqualTo("match.decline.ok");
            assertThat(queueCalls.poll(5, TimeUnit.SECONDS)).isEqualTo("/decline POST {\"matchUid\":\"m-1\"} Bearer " + token);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a second connection for one player displaces the first")
    void oneConnectionPerPlayer() throws Exception {
        String token = issueSession(4711);
        Lobby first = new Lobby();
        first.auth(token, 1);
        assertThat(first.next().get("t").asText()).isEqualTo("auth.ok");

        try (Lobby second = new Lobby()) {
            second.auth(token, 1);
            assertThat(second.next().get("t").asText()).isEqualTo("auth.ok");

            // Two live connections would each believe they owned the player's state, and a
            // push would land on whichever the map happened to hold.
            for (int i = 0; i < 50 && !first.isClosed(); i++) {
                Thread.sleep(100);
            }
            assertThat(first.isClosed()).as("the older one is closed").isTrue();
            assertThat(gateway.connectionCount()).isEqualTo(1);
        } finally {
            first.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a connection that never authenticates is closed")
    void authDeadline() throws Exception {
        try (Lobby lobby = new Lobby()) {
            for (int i = 0; i < 100 && !lobby.isClosed(); i++) {
                Thread.sleep(100);
            }
            assertThat(lobby.isClosed())
                    .as("an unauthenticated socket costs memory and a descriptor").isTrue();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a flood is cut off")
    void rateLimit() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");

            for (int i = 0; i < FrameLimit.MAX_FRAMES_PER_SECOND * 3; i++) {
                if (lobby.isClosed()) {
                    break;
                }
                try {
                    lobby.send("{\"t\":\"ping\",\"id\":%d}".formatted(i));
                } catch (java.util.concurrent.CompletionException closedUnderUs) {
                    // The server closing the connection is the behaviour under test, and it
                    // can land between the isClosed() check above and this send. A send that
                    // finds the output closed is the limiter working, not a failure — this
                    // test used to error on that race about once in every full build.
                    break;
                }
            }

            List<String> codes = new java.util.ArrayList<>();
            for (int i = 0; i < 80; i++) {
                JsonNode frame = lobby.next();
                if (frame == null) {
                    break;
                }
                if ("error".equals(frame.get("t").asText())) {
                    codes.add(frame.path("d").get("code").asText());
                    break;
                }
            }
            assertThat(codes).as("the client is told why").contains("rate_limited");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("pings count towards the limit: they were answered without end")
    void pingsAreLimited() throws Exception {
        try (Lobby lobby = new Lobby()) {
            // Netty answers a ping itself, before the lobby handler: counted there, they never
            // were, and every one cost a pong.
            for (int i = 0; i < FrameLimit.MAX_FRAMES_PER_SECOND * 3 && !lobby.isClosed(); i++) {
                try {
                    lobby.ping();
                } catch (java.util.concurrent.CompletionException closedUnderUs) {
                    break;
                }
            }
            JsonNode frame = lobby.next();
            assertThat(frame).isNotNull();
            assertThat(frame.path("d").get("code").asText()).isEqualTo("rate_limited");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a flood bigger than one read is told why, then closed as the protocol closes, not cut off (P-31)")
    void aFloodIsToldWhy() throws Exception {
        URI u = URI.create(url);
        try (java.net.Socket s = new java.net.Socket(u.getHost(), u.getPort())) {
            s.setSoTimeout(10_000);
            java.io.OutputStream out = s.getOutputStream();
            java.io.InputStream in = s.getInputStream();
            out.write(("GET " + u.getPath() + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + u.getPort()
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="
                    + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                head.append((char) in.read());
            }
            assertThat(head.toString()).startsWith("HTTP/1.1 101");

            // Pings, masked as a client's must be, in one write far bigger than a server read:
            // the refusal comes with most of it still unread on the gateway's side.
            byte[] ping = {(byte) 0x89, (byte) 0x80, 1, 2, 3, 4};
            byte[] burst = new byte[ping.length * 200_000];
            for (int i = 0; i < burst.length; i += ping.length) {
                System.arraycopy(ping, 0, burst, i, ping.length);
            }
            Thread writer = new Thread(() -> {
                try {
                    out.write(burst);
                    out.flush();
                } catch (java.io.IOException stopped) {
                    // the gateway stopped reading: how a flood ends
                }
            });
            writer.start();
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8_192];
            String ended = "a close";
            try {
                for (int n; (n = in.read(buf)) > 0; ) {
                    got.write(buf, 0, n);
                }
            } catch (java.net.SocketException reset) {
                // A reset: what a close with the flood unread sends. It may take with it what the
                // client had received and not yet handed on, the refusal among it; a WebSocket
                // client holding frames for its listener lost them all, in P-31's failures.
                ended = "a reset: " + reset.getMessage();
            }
            writer.join(10_000);
            String received = got.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(received).as("the refusal arrived").contains("rate_limited");
            // A Close frame, 1008 (policy violation), after it: a WebSocket client hands on every
            // message before the close; a bare end of stream is an error, and one client dropped
            // the messages it held on it.
            assertThat(received.substring(received.indexOf("rate_limited"))).as("then a Close frame, 1008")
                    .matches("(?s).*\u0088.\u0003\u00f0.*");
            assertThat(ended).as("how the connection ended").isEqualTo("a close");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a message sent in fragments is read whole")
    void fragmentsAreJoined() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby()) {
            // Its first fragment was read as the whole message, bad_json, and the rest dropped.
            lobby.sendInTwo("{\"t\":\"auth\",\"id\":1,\"d\":{\"token\":\"%s\"}}".formatted(token));
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the lobby path takes a query, and any other path is a 404 at once")
    void pathsAreAnswered() throws Exception {
        String token = issueSession(4711);
        try (Lobby lobby = new Lobby(url + "?v=2")) {
            lobby.auth(token, 1);
            assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
        }
        // Passed along to nothing, it was held unanswered until the idle limit, two minutes.
        String http = url.replace("ws://", "http://").replace("/lobby", "/elsewhere");
        long start = System.nanoTime();
        java.net.http.HttpResponse<String> response = HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create(http))
                        .timeout(java.time.Duration.ofSeconds(10)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    @Timeout(60)
    @DisplayName("platform unreachable is an internal error to retry, not a refusal")
    void platformDownIsNotARefusal() throws Exception {
        try (GatewayServer lonely = new GatewayServer(jredis, "http://127.0.0.1:1", "gateway-lonely", 1)) {
            int port = lonely.start("127.0.0.1", 0);
            String token = issueSession(4712);
            try (Lobby lobby = new Lobby("ws://127.0.0.1:" + port + "/lobby")) {
                lobby.auth(token, 1);
                assertThat(lobby.next().get("t").asText()).isEqualTo("auth.ok");
                lobby.send("{\"t\":\"match.request\",\"id\":2}");
                JsonNode reply = lobby.next();
                // It was upstream_error, "platform refused the request": a refusal the player
                // might act on, for an outage that was ours.
                assertThat(reply.path("d").get("code").asText()).isEqualTo("internal");
            }
        }
    }
}
