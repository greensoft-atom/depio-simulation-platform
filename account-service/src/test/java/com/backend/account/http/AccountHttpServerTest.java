package com.backend.account.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;

import com.backend.account.AccountService;
import com.backend.account.Metrics;
import com.backend.account.PasswordHashers;
import com.backend.account.TrustedProxies;
import com.backend.account.db.Database;
import com.backend.account.db.Outbox;
import com.backend.account.db.TestClock;
import com.backend.account.db.TestDatabase;
import com.backend.account.db.Throttle;
import com.backend.account.token.BearerCheck;
import com.backend.account.token.SigningKeys;
import com.backend.account.token.TestKeys;
import com.backend.account.token.TokenIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountHttpServerTest {

    private static final String ISSUER = "http://127.0.0.1:8083";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Database db;
    private final SigningKeys keys = SigningKeys.from(TestKeys.newPair().privateKey(), null);
    private final HttpClient http = HttpClient.newHttpClient();
    private final Metrics metrics = new Metrics();
    private AccountHttpServer server;
    private String base;

    @BeforeAll
    static void setUp() {
        db = TestDatabase.open();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void start() throws Exception {
        TestDatabase.empty(db);
        // A clock that stands still: the limits' windows are fixed minutes, and 30 attempts across a minute's edge
        // would be counted in two.
        Clock clock = new TestClock(Instant.now());
        AccountService service = new AccountService(db.dataSource(), PasswordHashers.cheap(),
                new Throttle(db.dataSource(), clock), new TokenIssuer(keys, ISSUER, "backend"),
                new BearerCheck(keys, ISSUER), new Outbox(db.dataSource()), () -> { }, clock);
        server = new AccountHttpServer(service, keys, TrustedProxies.LOOPBACK, metrics, "127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    private static void error(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json(r).get("error").asText()).isEqualTo(code);
        assertThat(json(r).get("error_description").asText()).isNotBlank();
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    @DisplayName("sign up: 201 and a token answer in RFC 6749's names, never cached; the name taken, 409; bad input, 400")
    void signUp() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/accounts", "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode t = json(r);
        assertThat(t.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(t.get("expires_in").asInt()).isEqualTo(900);
        assertThat(t.get("access_token").asText()).contains(".");
        assertThat(t.get("refresh_token").asText()).hasSize(43);
        assertThat(t.get("account_id").asText()).hasSize(22);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(r.headers().firstValue("Pragma")).hasValue("no-cache");
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/json");
        error(send("POST", "/v1/accounts", "{\"username\":\"ADA\",\"password\":\"hunter2-hunter2\"}"), 409, "username_taken");
        error(send("POST", "/v1/accounts", "{\"username\":\"a b\",\"password\":\"hunter2-hunter2\"}"), 400, "invalid_username");
        error(send("POST", "/v1/accounts", "{\"username\":\"bob\",\"password\":\"short\"}"), 400, "invalid_password");
        error(send("POST", "/v1/accounts", "not json"), 400, "invalid_request");
        error(send("POST", "/v1/accounts", null), 400, "invalid_request");
    }

    @Test
    @DisplayName("log in, refresh, log out: the refresh token's family spent; a wrong password 401, a spent grant 400")
    void sessions() throws Exception {
        send("POST", "/v1/accounts", "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}");
        error(send("POST", "/v1/sessions", "{\"username\":\"ada\",\"password\":\"wrong-password\"}"), 401, "invalid_credentials");
        HttpResponse<String> in = send("POST", "/v1/sessions", "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}");
        assertThat(in.statusCode()).isEqualTo(200);
        String refresh = json(in).get("refresh_token").asText();
        HttpResponse<String> r = send("POST", "/v1/sessions/refresh", "{\"refresh_token\":\"" + refresh + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String next = json(r).get("refresh_token").asText();
        assertThat(send("DELETE", "/v1/sessions", "{\"refresh_token\":\"" + next + "\"}").statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/v1/sessions", "{\"refresh_token\":\"never-was\"}").statusCode()).as("always").isEqualTo(204);
        error(send("POST", "/v1/sessions/refresh", "{\"refresh_token\":\"" + next + "\"}"), 400, "invalid_grant");
    }

    @Test
    @DisplayName("a password changed and an account deleted by the bearer token and the password; no token, 401 with its header")
    void bearerRoutes() throws Exception {
        String access = json(send("POST", "/v1/accounts", "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}"))
                .get("access_token").asText();
        HttpResponse<String> none = send("PUT", "/v1/accounts/me/password", "{\"current_password\":\"hunter2-hunter2\",\"new_password\":\"a-new-password\"}");
        error(none, 401, "invalid_token");
        assertThat(none.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
        HttpResponse<String> changed = send("PUT", "/v1/accounts/me/password",
                "{\"current_password\":\"hunter2-hunter2\",\"new_password\":\"a-new-password\"}", "Authorization", "Bearer " + access);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        String fresh = json(changed).get("access_token").asText();
        error(send("DELETE", "/v1/accounts/me", "{\"password\":\"hunter2-hunter2\"}", "Authorization", "Bearer " + fresh),
                401, "invalid_credentials");
        assertThat(send("DELETE", "/v1/accounts/me", "{\"password\":\"a-new-password\"}", "Authorization", "Bearer " + fresh)
                .statusCode()).isEqualTo(204);
        error(send("POST", "/v1/sessions", "{\"username\":\"ada\",\"password\":\"a-new-password\"}"), 401, "invalid_credentials");
    }

    @Test
    @DisplayName("the key set: the public keys, cached five minutes; the health; every other path 404, another method 405")
    void routes() throws Exception {
        HttpResponse<String> jwks = send("GET", "/.well-known/jwks.json", null);
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(json(jwks).get("keys").get(0).get("kid").asText()).isEqualTo(keys.current().getKeyID());
        assertThat(jwks.body()).doesNotContain("\"d\"");
        assertThat(jwks.headers().firstValue("Cache-Control")).hasValue("public, max-age=300");
        assertThat(send("GET", "/v1/health", null).statusCode()).isEqualTo(200);
        error(send("POST", "/v1/sessions/", "{}"), 404, "no_such_route");
        error(send("GET", "/v1/nothing", null), 404, "no_such_route");
        error(send("GET", "/", null), 404, "no_such_route");
        HttpResponse<String> wrong = send("GET", "/v1/accounts", null);
        error(wrong, 405, "method_not_allowed");
        assertThat(wrong.headers().firstValue("Allow")).hasValue("POST");
        error(send("POST", "/v1/sessions", "{\"username\":\"" + "x".repeat(5000) + "\"}"), 413, "body_too_large");
    }

    @Test
    @DisplayName("nginx's address for the client, its last X-Forwarded-For entry, counted by the limits; 429 says when to come back")
    void limits() throws Exception {
        // A name each, so the address's limit is the one met, not an account's.
        for (int i = 0; i < 30; i++) {
            send("POST", "/v1/sessions", "{\"username\":\"nobody" + i + "\",\"password\":\"hunter2-hunter2\"}",
                    "X-Forwarded-For", "192.0.2.1, 198.51.100.7");
        }
        HttpResponse<String> r = send("POST", "/v1/sessions", "{\"username\":\"nobody30\",\"password\":\"hunter2-hunter2\"}",
                "X-Forwarded-For", "198.51.100.7");
        error(r, 429, "too_many_attempts");
        assertThat(r.headers().firstValue("Retry-After").map(Integer::parseInt)).hasValueSatisfying(s -> assertThat(s).isBetween(1, 60));
        assertThat(send("POST", "/v1/sessions", "{\"username\":\"nobody31\",\"password\":\"hunter2-hunter2\"}",
                "X-Forwarded-For", "198.51.100.8").statusCode()).as("another address").isEqualTo(401);
        assertThat(metrics.scrape()).contains("account_requests_total{route=\"/v1/sessions\",method=\"POST\",status=\"429\"} 1");
    }
}
