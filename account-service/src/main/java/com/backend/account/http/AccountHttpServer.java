package com.backend.account.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.backend.account.AccountService;
import com.backend.account.Metrics;
import com.backend.account.TrustedProxies;
import com.backend.account.db.Throttle;
import com.backend.account.token.SigningKeys;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The account service's routes (10 §3.2), on the JDK's server and virtual threads, as the platform's are.
 *
 * <ul>
 * <li>JSON in and out, at most {@value #MAX_BODY_BYTES} bytes a body; names in RFC 6749's style,
 *     {@code refresh_token}; an error {@code {"error", "error_description"}} (RFC 6749 §5.2).</li>
 * <li>Every route answers its own path alone; any other path is 404 {@code no_such_route}, another method 405 with
 *     {@code Allow} (D-107's rules, the platform's).</li>
 * <li>Every answer {@code Cache-Control: no-store} and {@code Pragma: no-cache} (RFC 6749 §5.1); the key set alone is
 *     cached, five minutes.</li>
 * <li>A bearer refused is 401 {@code invalid_token} with {@code WWW-Authenticate: Bearer} (RFC 6750 §3); a limit 429
 *     and the line full 503, each with {@code Retry-After}; MySQL not answering 503 {@code unavailable}.</li>
 * <li>The client's address is nginx's last {@code X-Forwarded-For} entry when nginx is the peer, by
 *     {@code ACCOUNT_TRUSTED_PROXIES}, else the peer's: the platform's rule (04 §1).</li>
 * </ul>
 */
public final class AccountHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AccountHttpServer.class);

    static final int MAX_BODY_BYTES = 4 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("(?=.*:)[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    record Credentials(String username, String password) { }

    record Refresh(String refreshToken) { }

    record PasswordChange(String currentPassword, String newPassword) { }

    record Proof(String password) { }

    record TokenAnswer(String accessToken, String tokenType, long expiresIn, String refreshToken, String accountId) { }

    record ErrorAnswer(String error, String errorDescription) { }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException, SQLException;
    }

    private final AccountService service;
    private final TrustedProxies proxies;
    private final Metrics metrics;
    private final HttpServer server;
    private final byte[] keySet;

    public AccountHttpServer(AccountService service, SigningKeys keys, TrustedProxies proxies, Metrics metrics,
                             String bindHost, int port) throws IOException {
        this.service = service;
        this.proxies = proxies;
        this.metrics = metrics;
        this.keySet = keys.publicSet().toString().getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 128);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        route("/v1/accounts", "POST", this::signUp);
        route("/v1/accounts/me", "DELETE", this::delete);
        route("/v1/accounts/me/password", "PUT", this::changePassword);
        server.createContext("/v1/sessions", ex -> guard(ex, "/v1/sessions", e -> {
            if (!exact(e, "/v1/sessions")) {
                return;
            }
            switch (e.getRequestMethod()) {
                case "POST" -> logIn(e);
                case "DELETE" -> logOut(e);
                default -> methodNotAllowed(e, "POST, DELETE");
            }
        }));
        route("/v1/sessions/refresh", "POST", this::refresh);
        route("/.well-known/jwks.json", "GET", this::keySet);
        route("/v1/health", "GET", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/", ex -> guard(ex, "/", e -> error(e, 404, "no_such_route", "no route has this path")));
    }

    public int start() {
        server.start();
        int bound = server.getAddress().getPort();
        log.info("account service API on {}:{}", server.getAddress().getHostString(), bound);
        return bound;
    }

    @Override
    public void close() {
        server.stop(1);
    }

    // ---- routes ---------------------------------------------------------------------------------------------------

    private void signUp(HttpExchange ex) throws IOException, SQLException {
        Credentials body = read(ex, Credentials.class);
        if (body == null || body.username() == null || body.password() == null) {
            error(ex, 400, "invalid_request", "send {\"username\", \"password\"}");
            return;
        }
        answer(ex, service.signUp(address(ex), body.username(), body.password().toCharArray()), 201);
    }

    private void logIn(HttpExchange ex) throws IOException, SQLException {
        Credentials body = read(ex, Credentials.class);
        if (body == null || body.username() == null || body.password() == null) {
            error(ex, 400, "invalid_request", "send {\"username\", \"password\"}");
            return;
        }
        answer(ex, service.logIn(address(ex), body.username(), body.password().toCharArray()), 200);
    }

    private void refresh(HttpExchange ex) throws IOException, SQLException {
        Refresh body = read(ex, Refresh.class);
        if (body == null || body.refreshToken() == null) {
            error(ex, 400, "invalid_request", "send {\"refresh_token\"}");
            return;
        }
        answer(ex, service.refresh(body.refreshToken()), 200);
    }

    /** 204 whatever the token: a logout tells nobody whether a token was ever one. */
    private void logOut(HttpExchange ex) throws IOException, SQLException {
        Refresh body = read(ex, Refresh.class);
        if (body == null || body.refreshToken() == null) {
            error(ex, 400, "invalid_request", "send {\"refresh_token\"}");
            return;
        }
        service.logOut(body.refreshToken());
        respond(ex, 204, null);
    }

    private void changePassword(HttpExchange ex) throws IOException, SQLException {
        String bearer = bearer(ex);
        if (bearer == null) {
            noBearer(ex);
            return;
        }
        PasswordChange body = read(ex, PasswordChange.class);
        if (body == null || body.currentPassword() == null || body.newPassword() == null) {
            error(ex, 400, "invalid_request", "send {\"current_password\", \"new_password\"}");
            return;
        }
        answer(ex, service.changePassword(address(ex), bearer, body.currentPassword().toCharArray(),
                body.newPassword().toCharArray()), 200);
    }

    private void delete(HttpExchange ex) throws IOException, SQLException {
        String bearer = bearer(ex);
        if (bearer == null) {
            noBearer(ex);
            return;
        }
        Proof body = read(ex, Proof.class);
        if (body == null || body.password() == null) {
            error(ex, 400, "invalid_request", "send {\"password\"}");
            return;
        }
        answer(ex, service.delete(address(ex), bearer, body.password().toCharArray()), 204);
    }

    private void keySet(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, keySet.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(keySet);
        }
        ex.close();
    }

    /** The service's outcome as HTTP: {@code ok} the status a success has, with a token answer or none. */
    private static void answer(HttpExchange ex, AccountService.Result r, int ok) throws IOException {
        switch (r.outcome()) {
            case OK -> {
                if (r.tokens() == null) {
                    respond(ex, ok, null);
                } else {
                    AccountService.Tokens t = r.tokens();
                    respond(ex, ok, JSON.writeValueAsString(new TokenAnswer(t.accessToken(), "Bearer", t.expiresIn(),
                            t.refreshToken(), t.accountId())));
                }
            }
            case INVALID_USERNAME -> error(ex, 400, "invalid_username", "3 to 32 of A-Z, a-z, 0-9, _ and -");
            case INVALID_PASSWORD -> error(ex, 400, "invalid_password", "8 to 128 characters");
            case USERNAME_TAKEN -> error(ex, 409, "username_taken", "another account has this name");
            case INVALID_CREDENTIALS -> error(ex, 401, "invalid_credentials", "the username or the password is not right");
            case INVALID_GRANT -> error(ex, 400, "invalid_grant", "the refresh token is spent, revoked or ended: log in again");
            case INVALID_TOKEN -> {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                error(ex, 401, "invalid_token", "the access token is not one this service takes: refresh it or log in again");
            }
            case TOO_MANY_ATTEMPTS -> {
                ex.getResponseHeaders().set("Retry-After", Integer.toString(r.retryAfterSeconds()));
                error(ex, 429, "too_many_attempts", "too many attempts: come back after Retry-After seconds");
            }
            case BUSY -> {
                ex.getResponseHeaders().set("Retry-After", Integer.toString(r.retryAfterSeconds()));
                error(ex, 503, "busy", "too many logins at once: come back after Retry-After seconds");
            }
        }
    }

    // ---- the server's own ----------------------------------------------------------------------------------------

    private void route(String path, String method, Handler handler) {
        server.createContext(path, ex -> guard(ex, path, e -> {
            if (!exact(e, path)) {
                return;
            }
            if (!e.getRequestMethod().equals(method)) {
                methodNotAllowed(e, method);
                return;
            }
            handler.handle(e);
        }));
    }

    /** The server matches a context by its prefix: {@code /v1/sessions/} would otherwise reach {@code /v1/sessions}. */
    private static boolean exact(HttpExchange ex, String path) throws IOException {
        if (ex.getRequestURI().getPath().equals(path)) {
            return true;
        }
        error(ex, 404, "no_such_route", "no route has this path");
        return false;
    }

    private static void methodNotAllowed(HttpExchange ex, String allowed) throws IOException {
        ex.getResponseHeaders().set("Allow", allowed);
        error(ex, 405, "method_not_allowed", "use " + allowed);
    }

    private static void noBearer(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        error(ex, 401, "invalid_token", "send Authorization: Bearer <access token>");
    }

    /** Anything thrown answered, never a dropped connection; every answer counted by its route. */
    private void guard(HttpExchange ex, String route, Handler handler) throws IOException {
        try {
            handler.handle(ex);
        } catch (SQLException e) {
            log.error("database error serving {}: {}", ex.getRequestURI().getPath(), e.toString());
            error(ex, 503, "unavailable", "try again shortly");
        } catch (BodyTooLarge e) {
            error(ex, 413, "body_too_large", "at most " + MAX_BODY_BYTES + " bytes");
        } catch (RuntimeException e) {
            log.error("unhandled error serving {}", ex.getRequestURI().getPath(), e);
            error(ex, 500, "internal", "unexpected failure");
        } finally {
            int status = ex.getResponseCode();
            if (status > 0) {
                metrics.response(route, ex.getRequestMethod(), status);
            }
        }
    }

    private static final class BodyTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super(null, null, false, false);
        }
    }

    /** The body as {@code type}, or null when it is empty or not that JSON. One byte past the limit is too large. */
    private static <T> T read(HttpExchange ex, Class<T> type) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new BodyTooLarge();
            }
            return body.length == 0 ? null : JSON.readValue(body, type);
        } catch (JacksonException malformed) {
            return null;
        }
    }

    private static String bearer(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private String address(HttpExchange ex) {
        return Throttle.addressKey(clientAddress(ex.getRemoteAddress().getAddress(),
                ex.getRequestHeaders().get("X-Forwarded-For"), proxies));
    }

    /**
     * The TCP peer, unless the peer is nginx, on this machine or named by {@code ACCOUNT_TRUSTED_PROXIES}: then the
     * last entry of {@code X-Forwarded-For}, the one nginx wrote ({@code proxy_add_x_forwarded_for}); every earlier
     * one is the client's to choose. One that does not parse falls back to the peer, which throttles more, not less.
     */
    static InetAddress clientAddress(InetAddress peer, List<String> forwardedFor, TrustedProxies proxies) {
        if (!proxies.trusts(peer) || forwardedFor == null || forwardedFor.isEmpty()) {
            return peer;
        }
        String last = forwardedFor.get(forwardedFor.size() - 1);
        InetAddress parsed = ipLiteral(last.substring(last.lastIndexOf(',') + 1).trim());
        return parsed == null ? peer : parsed;
    }

    private static InetAddress ipLiteral(String s) {
        try {
            Matcher v4 = IPV4.matcher(s);
            if (v4.matches()) {
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(v4.group(i + 1));
                    if (octet > 255) {
                        return null;
                    }
                    b[i] = (byte) octet;
                }
                return InetAddress.getByAddress(b);
            }
            return IPV6.matcher(s).matches() ? InetAddress.getByName(s) : null;
        } catch (UnknownHostException notAnAddress) {
            return null;
        }
    }

    private static void error(HttpExchange ex, int status, String code, String description) throws IOException {
        respond(ex, status, JSON.writeValueAsString(new ErrorAnswer(code, description)));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        if (bytes.length > 0) {
            ex.getResponseHeaders().set("Content-Type", "application/json");
        }
        // A 204 declares no body length at all, or a strict client waits for one.
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
