package com.backend.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Everything a client does before it reaches an arena: sign in at the account service and
 * take its token for a session, hold a lobby connection, and ask for a match.
 *
 * <h2>Why this exists in Java</h2>
 *
 * It came first, before the C# core, as the stand-in for the client; the client now has its own,
 * client/Core's LobbyClient and ApiClient, which the drills drive. This one is the load tools':
 * BotClient onboards its bots through it. It uses nothing but the JDK's HTTP and WebSocket
 * clients: no framework, and the message shapes visible in one place.
 *
 * It substitutes for the real client everywhere except "does it feel right", which is the
 * one thing it cannot answer.
 *
 * <h2>Not for thousands at once</h2>
 *
 * The JDK's WebSocket is convenient rather than cheap. It is right for the lobby, where a
 * client sends a handful of messages a minute. The arena connection, which is 20 messages a
 * second for the life of a match, stays on Netty.
 */
public final class LobbyClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Where platform said to go, and the credential to get in with. {@code tls}: the arena
     * must be dialled with TLS, which the grant says and the client obeys.
     */
    /** {@code tlsName}: the name the TLS handshake gives behind the front (D-82); null for the host. */
    public record Grant(String arenaHost, int arenaPort, String ticketId, boolean tls, String tlsName) { }

    public static final class LobbyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LobbyException(String message) {
            super(message);
        }
    }

    private final HttpClient http;
    private final String platformUrl;
    private final String lobbyUrl;

    private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean lobbyClosed = new AtomicBoolean();

    private String token;
    private long playerId = -1;
    private String forwardedFor;
    private WebSocket lobby;
    private int nextRequestId = 1;

    public LobbyClient(String platformUrl, String lobbyUrl) {
        this(platformUrl, lobbyUrl, newHttpClient());
    }

    /**
     * Shares one HTTP stack across many clients, which is what a harness wants and what a
     * real client has anyway.
     *
     * A {@link HttpClient} owns selector threads and an executor. Giving each of sixty bots
     * its own, all constructed in one burst, produced request timeouts that looked like the
     * server struggling — while the server was serving sixty concurrent registrations in
     * 2.3 seconds without a single failure.
     */
    public LobbyClient(String platformUrl, String lobbyUrl, HttpClient shared) {
        this.platformUrl = platformUrl;
        this.lobbyUrl = lobbyUrl;
        this.http = shared;
    }

    /**
     * Tells platform this client's address the way nginx does, as {@code X-Forwarded-For}.
     *
     * Platform and the account service believe the header only from a peer on their own machine
     * or one named in {@code BACKEND_TRUSTED_PROXIES} and {@code ACCOUNT_TRUSTED_PROXIES} (the
     * front's nginx, D-82), so this works for a local load run and nowhere else. There it makes a
     * hundred bots a hundred clients, rather than one address the account service's limit stops at
     * thirty attempts a minute.
     */
    public LobbyClient forwardedFor(String address) {
        this.forwardedFor = address;
        return this;
    }

    private String[] forwardedHeader() {
        return forwardedFor == null ? new String[0] : new String[] {"X-Forwarded-For", forwardedFor};
    }

    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String token() {
        return token;
    }

    public long playerId() {
        return playerId;
    }

    // ---- HTTP: account and session ------------------------------------------------------

    /**
     * Signs in as a player does (D-117, docs/detailed-design/10-accounts.md §2): logs in at the account service, or
     * signs up there when the name has no account, and takes the access token to the platform for a session once.
     *
     * Login first: a load run restarted against the same databases would otherwise pay a sign-up's hash per bot to
     * be told the name is taken. A sign-up refused as taken, another run's bot made in between, logs in after all.
     */
    public void signIn(String accountUrl, String username, String password) throws Exception {
        String credentials = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("username", username).put("password", password));
        HttpResponse<String> answer = post(accountUrl + "/v1/sessions", credentials);
        if (answer.statusCode() == 401) {
            answer = post(accountUrl + "/v1/accounts", credentials);
            if (answer.statusCode() == 409) {
                answer = post(accountUrl + "/v1/sessions", credentials);
            }
        }
        if (answer.statusCode() != 200 && answer.statusCode() != 201) {
            throw new LobbyException("the account service: " + answer.statusCode() + " " + answer.body());
        }
        String access = MAPPER.readTree(answer.body()).get("access_token").asText();
        HttpResponse<String> session = post(platformUrl + "/v1/sessions",
                MAPPER.writeValueAsString(MAPPER.createObjectNode().put("accessToken", access)));
        if (session.statusCode() != 200) {
            throw new LobbyException("the exchange: " + session.statusCode() + " " + session.body());
        }
        JsonNode body = MAPPER.readTree(session.body());
        token = body.get("token").asText();
        playerId = body.get("playerId").asLong();
    }

    private HttpResponse<String> post(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .headers(forwardedHeader())
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ---- WebSocket: the lobby connection -------------------------------------------------

    /** Opens the lobby connection and authenticates it. */
    public void openLobby() throws Exception {
        lobby = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(lobbyUrl), new Listener())
                .get(15, TimeUnit.SECONDS);

        JsonNode reply = request("auth", MAPPER.createObjectNode().put("token", token));
        if (!"auth.ok".equals(reply.get("t").asText())) {
            throw new LobbyException("auth refused: " + reply);
        }
    }

    /** Asks for somewhere to play. */
    public Grant requestMatch() throws Exception {
        JsonNode reply = request("match.request", null);
        if (!"match.request.ok".equals(reply.get("t").asText())) {
            throw new LobbyException("no match: " + reply.path("d").path("code").asText());
        }
        JsonNode d = reply.path("d");
        return new Grant(d.get("arenaHost").asText(), d.get("arenaPort").asInt(),
                d.get("ticketId").asText(), d.path("tls").asBoolean(false),
                d.hasNonNull("tlsName") ? d.get("tlsName").asText() : null);
    }

    /**
     * Sends a request and waits for the reply that carries its id.
     *
     * Replies are matched by id rather than by arrival order, because a server push can
     * land between a request and its answer at any time.
     */
    private JsonNode request(String type, JsonNode data) throws Exception {
        int id = nextRequestId++;
        var frame = MAPPER.createObjectNode();
        frame.put("t", type);
        frame.put("id", id);
        frame.set("d", data == null ? MAPPER.createObjectNode() : data);
        lobby.sendText(frame.toString(), true).get(10, TimeUnit.SECONDS);

        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode reply = inbox.poll(2, TimeUnit.SECONDS);
            if (reply == null) {
                continue;
            }
            JsonNode replyId = reply.get("id");
            if (replyId != null && replyId.asInt() == id) {
                return reply;
            }
            // A push, or an answer to something else. Dropped here because this client has
            // nothing to do with pushes yet; a real one would dispatch them.
        }
        throw new LobbyException("no reply to " + type + " within " + TIMEOUT);
    }

    /** What a client sends at least every 30 s, so the gateway does not take it for gone (03 §3). */
    public void ping() throws Exception {
        request("ping", null);
    }

    public boolean isLobbyClosed() {
        return lobbyClosed.get();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    inbox.add(MAPPER.readTree(partial.toString()));
                } catch (Exception malformed) {
                    // A frame this client cannot read is not a reason to stop reading.
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            lobbyClosed.set(true);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            lobbyClosed.set(true);
        }
    }

    @Override
    public void close() {
        if (lobby != null) {
            lobby.abort();
        }
    }
}
