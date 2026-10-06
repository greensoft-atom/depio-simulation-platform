package com.backend.platform.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.MatchMode;
import com.backend.handoff.LeaderboardStore.Board;
import com.backend.handoff.LeaderboardStore.Entry;
import com.backend.handoff.LeaderboardStore.Neighbourhood;
import com.backend.handoff.StoreUnavailableException;
import com.backend.common.Metrics;
import com.backend.platform.AuthService;
import com.backend.platform.BoostService;
import com.backend.platform.Catalogue;
import com.backend.platform.EquipmentService;
import com.backend.platform.Items;
import com.backend.platform.JoinService;
import com.backend.platform.LoginThrottle;
import com.backend.platform.MatchQueue;
import com.backend.platform.PasswordHasher;
import com.backend.platform.PartyService;
import com.backend.platform.QueueService;
import com.backend.platform.ShopService;
import com.backend.platform.TeamService;
import com.backend.platform.FriendService;
import com.backend.platform.InboxService;
import com.backend.platform.TournamentService;
import com.backend.platform.net.ApiMessages.ConfirmResponse;
import com.backend.platform.net.ApiMessages.ErrorResponse;
import com.backend.platform.net.ApiMessages.OrderRequest;
import com.backend.platform.net.ApiMessages.OrderResponse;
import com.backend.platform.net.ApiMessages.OrderView;
import com.backend.platform.net.ApiMessages.PackView;
import com.backend.platform.net.ApiMessages.PacksResponse;
import com.backend.platform.net.ApiMessages.PassResponse;
import com.backend.platform.net.ApiMessages.PremiumResponse;
import com.backend.platform.net.ApiMessages.RewardView;
import com.backend.platform.net.ApiMessages.SkinView;
import com.backend.platform.net.ApiMessages.TierView;
import com.backend.platform.net.ApiMessages.SimulateRequest;
import com.backend.platform.net.ApiMessages.FoundView;
import com.backend.platform.net.ApiMessages.InventoryResponse;
import com.backend.platform.net.ApiMessages.MyTeamRankResponse;
import com.backend.platform.net.ApiMessages.TeamBoardResponse;
import com.backend.platform.net.ApiMessages.TeamEntry;
import com.backend.platform.net.ApiMessages.ActivateRequest;
import com.backend.platform.net.ApiMessages.AnswerRequest;
import com.backend.platform.net.ApiMessages.BoostView;
import com.backend.platform.net.ApiMessages.BoostsResponse;
import com.backend.platform.net.ApiMessages.PartyRequest;
import com.backend.platform.net.ApiMessages.ItemView;
import com.backend.platform.net.ApiMessages.LeaderboardEntry;
import com.backend.platform.net.ApiMessages.LeaderboardResponse;
import com.backend.platform.net.ApiMessages.LoadoutResponse;
import com.backend.platform.net.ApiMessages.LoginRequest;
import com.backend.platform.net.ApiMessages.LoginResponse;
import com.backend.platform.net.ApiMessages.MatchResponse;
import com.backend.platform.net.ApiMessages.MyRankResponse;
import com.backend.platform.net.ApiMessages.OfferView;
import com.backend.platform.net.ApiMessages.PurchaseRequest;
import com.backend.platform.net.ApiMessages.PurchaseResponse;
import com.backend.platform.net.ApiMessages.RegisterRequest;
import com.backend.platform.net.ApiMessages.RenameRequest;
import com.backend.platform.net.ApiMessages.RegisterResponse;
import com.backend.platform.net.ApiMessages.ShopResponse;
import com.backend.platform.net.ApiMessages.InviteView;
import com.backend.platform.net.ApiMessages.InvitesResponse;
import com.backend.platform.net.ApiMessages.MemberView;
import com.backend.platform.net.ApiMessages.GuestResponse;
import com.backend.platform.net.ApiMessages.InboxRead;
import com.backend.platform.net.ApiMessages.PlayerRequest;
import com.backend.platform.net.ApiMessages.TeamRequest;
import com.backend.platform.net.ApiMessages.TeamView;
import com.backend.platform.net.ApiMessages.WearRequest;
import com.backend.platform.net.ApiMessages.QueueRequest;
import com.backend.platform.net.ApiMessages.QueueResponse;
import com.jredis.client.JRedisClosedException;
import com.jredis.client.JRedisConnectionException;
import com.jredis.client.JRedisServerException;
import com.jredis.client.JRedisTimeoutException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform API (docs detailed-design/04-platform-services.md, {@code net}).
 *
 * <h2>Why the JDK's server</h2>
 *
 * Clients reach it through this machine's nginx (every /v1/ route), and the gateway calls it for
 * what its lobby connections ask (match requests, the queue, parties). The measured
 * bottleneck on its busiest path is Argon2 at ~88 ms per login with eight concurrent
 * permits, which caps a process near 90 logins a second — four orders of magnitude below
 * anything an HTTP layer decides. Netty here would be a second transport to maintain for no
 * measured gain. If that ever stops being true the swap is contained to this class.
 *
 * <h2>Threading</h2>
 *
 * A virtual thread per request, because every handler blocks: on MySQL, on j-redis, and on
 * Argon2. That is what virtual threads are for, and it is why the pool behind them stays
 * small — 10 000 virtual threads waiting on a connection pool of 16 is the correct
 * arrangement, not a problem to solve (06 §7).
 *
 * <h2>Trust</h2>
 *
 * The session token is the credential and is checked here, so the API is safe even if the
 * caller is not the gateway. It still binds to loopback by default: an unauthenticated
 * login endpoint that costs 88 ms of CPU is a denial-of-service lever. Registration and
 * login are counted per address, and logins also per account, by {@link LoginThrottle}, here rather than
 * at nginx, because the edge sees neither the account nor the other two machines.
 *
 * The address is the TCP peer's, unless the peer is on this machine — nginx, in production
 * — in which case it is the last entry of {@code X-Forwarded-For}: see
 * {@link #clientAddress}.
 */
public final class PlatformHttpServer implements AutoCloseable {

    /** The shipped class table, as the arena's rooms run it (D-24). */
    private static final com.backend.sim.ClassTable CLASSES = com.backend.sim.ClassTable.defaults();
    private static final com.backend.sim.PhraseTable PHRASES = com.backend.sim.PhraseTable.defaults();


    private static final Logger log = LoggerFactory.getLogger(PlatformHttpServer.class);

    /** Enough for a username, a display name and a password; anything larger is not one. */
    private static final int MAX_BODY_BYTES = 4 * 1024;

    private static final int DEFAULT_BOARD_LIMIT = 50;

    /**
     * A board is rendered on a phone, and a caller asking for ten thousand rows is either
     * mistaken or probing. One screen's worth, generously, is the whole use.
     */
    private static final int MAX_BOARD_LIMIT = 100;

    /** Rows on each side of the player in an "around me" view: an eleven-row window. */
    private static final int AROUND_RADIUS = 5;

    /** A board read is one or two store round trips; beyond this the store is not answering. */
    private static final long STORE_TIMEOUT_SECONDS = 3;

    private final AuthService auth;
    private final JoinService joins;
    private final LeaderboardStore leaderboards;
    private final LoginThrottle throttle;
    private final ShopService shop;
    private final EquipmentService equipment;
    private final BoostService boosts;
    private final TeamService teams;
    private final InboxService inbox;
    private final FriendService friends;
    private final TournamentService tournaments;
    private final QueueService queues;
    private final PartyService parties;
    private final com.backend.platform.RatingLeaderboards ratingBoards;
    private final com.backend.platform.AchievementService achievements;
    private final com.backend.platform.GoalService goals;
    private final com.backend.platform.PaymentService payments;
    private final com.backend.platform.PassService passes;
    /** The skin table a client draws by, and its version: its content's CRC32, as the class table's (D-70). */
    private final String skinsJson;
    private final long skinsVersion;
    private final HttpServer server;

    /** Every routed response by status; read by the metrics scrape. */
    private final Metrics.LabeledCounter responses = new Metrics.LabeledCounter();

    /** Seconds, the request histogram's bounds: 5 ms to 2.5 s; a login's Argon2 alone is about 0.09. */
    static final double[] LATENCY_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5};
    /** How long each request took to answer, by route (04 §11). */
    private final Metrics.LabeledHistogram latency = new Metrics.LabeledHistogram(LATENCY_BUCKETS);

    /** Every login by what became of it, refusals included. */
    private final Metrics.LabeledCounter logins = new Metrics.LabeledCounter();

    /** Every purchase by what became of it: 04 §11's "purchase failures by cause". */
    private final Metrics.LabeledCounter purchases = new Metrics.LabeledCounter();

    public PlatformHttpServer(AuthService auth, JoinService joins, LeaderboardStore leaderboards,
            LoginThrottle throttle, ShopService shop, EquipmentService equipment, BoostService boosts,
            TeamService teams, TournamentService tournaments, QueueService queues,
            PartyService parties, FriendService friends, InboxService inbox,
            com.backend.platform.RatingLeaderboards ratingBoards, com.backend.platform.AchievementService achievements,
            com.backend.platform.GoalService goals, com.backend.platform.PaymentService payments,
            com.backend.platform.PassService passes, String bindHost, int port)
            throws IOException {
        this.auth = auth;
        this.joins = joins;
        this.leaderboards = leaderboards;
        this.throttle = throttle;
        this.shop = shop;
        this.equipment = equipment;
        this.boosts = boosts;
        this.teams = teams;
        this.tournaments = tournaments;
        this.queues = queues;
        this.parties = parties;
        this.friends = friends;
        this.inbox = inbox;
        this.ratingBoards = ratingBoards;
        this.achievements = achievements;
        this.goals = goals;
        this.payments = payments;
        this.passes = passes;
        List<SkinView> skins = new ArrayList<>();
        for (Items.Skin s : equipment.skins()) {
            skins.add(new SkinView(s.skin(), s.itemId()));
        }
        this.skinsJson = Json.MAPPER.writeValueAsString(skins);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(skinsJson.getBytes(StandardCharsets.UTF_8));
        this.skinsVersion = crc.getValue();
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 128);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // The routes nginx limits, matched exactly (S-18); an id in any path is at most 18 digits, so
        // it always fits a long and a longer one names no route (S-17).
        server.createContext("/v1/accounts", exchange -> exact(exchange, "POST", "/v1/accounts", this::register));
        server.createContext("/v1/accounts/upgrade", exchange -> exact(exchange, "POST", "/v1/accounts/upgrade", this::upgrade));
        server.createContext("/v1/accounts/name", exchange -> exact(exchange, "PUT", "/v1/accounts/name", this::rename));
        server.createContext("/v1/guests", exchange -> exact(exchange, "POST", "/v1/guests", this::createGuest));
        server.createContext("/v1/sessions", this::sessions);
        server.createContext("/v1/match-requests", exchange -> route(exchange, "POST", this::requestMatch));
        server.createContext("/v1/queue", this::queue);
        server.createContext("/v1/sandbox", exchange -> route(exchange, "POST", this::openSandbox));
        server.createContext("/v1/party", this::party);
        server.createContext("/v1/leaderboards", exchange -> route(exchange, "GET", this::leaderboard));
        server.createContext("/v1/seasons", exchange -> route(exchange, "GET", this::seasons));
        server.createContext("/v1/achievements", exchange -> route(exchange, "GET", this::achievements));
        server.createContext("/v1/goals", exchange -> route(exchange, "GET", this::goals));
        server.createContext("/v1/shop", exchange -> route(exchange, "GET", this::shop));
        server.createContext("/v1/inventory", this::inventoryRoutes);
        server.createContext("/v1/purchases", exchange -> route(exchange, "POST", this::purchase));
        server.createContext("/v1/payments", exchange -> guard(exchange, this::payments));
        server.createContext("/v1/pass", exchange -> guard(exchange, this::pass));
        server.createContext("/v1/equipment", exchange -> guard(exchange, this::equipment));
        server.createContext("/v1/boosts", exchange -> guard(exchange, this::boosts));
        server.createContext("/v1/teams", exchange -> guard(exchange, this::teams));
        server.createContext("/v1/team-invites", exchange -> guard(exchange, this::teamInvites));
        server.createContext("/v1/team-applications", exchange -> route(exchange, "GET", this::teamApplications));
        server.createContext("/v1/tournaments", exchange -> guard(exchange, this::tournaments));
        server.createContext("/v1/friends", exchange -> guard(exchange, this::friends));
        server.createContext("/v1/friend-requests", exchange -> guard(exchange, this::friendRequests));
        server.createContext("/v1/blocks", exchange -> guard(exchange, this::blocks));
        server.createContext("/v1/inbox", exchange -> guard(exchange, this::inbox));
        server.createContext("/v1/content/classes", exchange -> route(exchange, "GET", this::classes));
        server.createContext("/v1/content/phrases", exchange -> route(exchange, "GET", this::phrases));
        server.createContext("/v1/content/skins", exchange -> route(exchange, "GET", this::skins));
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
    }

    /** What the platform API reports (04 §11). */
    public void registerMetrics(Metrics m) {
        m.labeledCounter("backend_platform_responses_total", "API responses, by status.",
                "status", responses);
        m.counter("backend_platform_open_tickets_issued_total",
                "Tickets issued for the public arena; with the arenas' joins, how many never arrived.",
                joins::issued);
        m.labeledHistogram("backend_platform_request_seconds",
                "Time to answer an API request, by route: the API's path, not the request's.", "route", latency);
        m.labeledCounter("backend_platform_logins_total",
                "Logins by outcome: ok, invalid_credentials, banned, throttled, busy.", "outcome",
                logins);
        m.labeledCounter("backend_platform_purchases_total", "Purchases by outcome: bought,"
                + " already_bought, insufficient_funds, not_available, level_required, unknown_sku,"
                + " invalid_key, no_session.", "outcome", purchases);
        m.gauge("backend_platform_hasher_line",
                "Requests holding or waiting for a password hash; 168 is full (S-7).",
                auth::hasherLine);
    }

    public int start() {
        server.start();
        int bound = server.getAddress().getPort();
        log.info("platform API on {}:{}", server.getAddress().getHostString(), bound);
        return bound;
    }

    @Override
    public void close() {
        server.stop(1);
    }

    // ---- routes --------------------------------------------------------------------------

    private void sessions(HttpExchange exchange) throws IOException {
        guard(exchange, ex -> {
            if (!ex.getRequestURI().getPath().equals("/v1/sessions")) {
                error(ex, 404, "no_such_route", "use /v1/sessions");
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> login(ex);
                case "DELETE" -> logout(ex);
                default -> methodNotAllowed(ex, "POST, DELETE");
            }
        });
    }

    private void register(HttpExchange exchange) throws IOException, SQLException {
        RegisterRequest request = read(exchange, RegisterRequest.class);
        if (request == null || request.username() == null || request.password() == null) {
            error(exchange, 400, "invalid_body", "username and password are required");
            return;
        }
        AuthService.RegisterResult result;
        try (PasswordHasher.Admission place = auth.admit()) {
            if (refusal(exchange, place, null) != null) {
                return;
            }
            char[] password = request.password().toCharArray();
            result = auth.register(request.username(), request.displayName(), password);
            java.util.Arrays.fill(password, '\0');
        }

        switch (result.outcome()) {
            case OK -> respond(exchange, 201, Json.MAPPER.writeValueAsString(
                    new RegisterResponse(result.playerId())));
            case USERNAME_TAKEN -> error(exchange, 409, "username_taken", "that name is in use");
            case INVALID_USERNAME -> error(exchange, 400, "invalid_username",
                    "3 to 32 characters, letters, digits, underscore or hyphen");
            case INVALID_PASSWORD -> error(exchange, 400, "invalid_password",
                    "8 to 128 characters");
            // The code is stable for a client to branch on; the message says which rule, so
            // the player can be told what to change rather than just "no".
            case INVALID_DISPLAY_NAME -> error(exchange, 400, "invalid_display_name",
                    result.problem());
            default -> error(exchange, 500, "internal", "unhandled outcome");
        }
    }

    private void login(HttpExchange exchange) throws IOException, SQLException {
        LoginRequest request = read(exchange, LoginRequest.class);
        if (request != null && request.guestKey() != null) {
            loginGuest(exchange, request.guestKey());
            return;
        }
        if (request == null || request.username() == null || request.password() == null) {
            error(exchange, 400, "invalid_body", "username and password are required");
            return;
        }
        AuthService.LoginResult result;
        try (PasswordHasher.Admission place = auth.admit()) {
            String refused = refusal(exchange, place, AuthService.usernameKey(request.username()));
            if (refused != null) {
                logins.increment(refused);
                return;
            }
            char[] password = request.password().toCharArray();
            result = auth.login(request.username(), password);
            java.util.Arrays.fill(password, '\0');
        }

        logins.increment(result.outcome().name().toLowerCase(java.util.Locale.ROOT));
        switch (result.outcome()) {
            case OK -> respond(exchange, 200, Json.MAPPER.writeValueAsString(
                    new LoginResponse(result.token(), result.playerId(), result.ttlSeconds())));
            // One answer for "no such user" and "wrong password", as the service decided:
            // telling them apart hands out a list of which usernames exist.
            case INVALID_CREDENTIALS -> error(exchange, 401, "invalid_credentials",
                    "username or password is wrong");
            case BANNED -> error(exchange, 403, "banned", "this account cannot play");
            default -> error(exchange, 500, "internal", "unhandled outcome");
        }
    }

    /**
     * {@code POST /v1/sessions {"guestKey"}}: a guest's login (D-46), throttled per address, and
     * with no place in the hasher's line, since nothing slow is hashed.
     */
    private void loginGuest(HttpExchange exchange, String guestKey) throws IOException, SQLException {
        if (throttled(exchange)) {
            logins.increment("throttled");
            return;
        }
        AuthService.LoginResult result = auth.loginGuest(guestKey);
        logins.increment(result.outcome().name().toLowerCase(java.util.Locale.ROOT));
        switch (result.outcome()) {
            case OK -> respond(exchange, 200, Json.MAPPER.writeValueAsString(
                    new LoginResponse(result.token(), result.playerId(), result.ttlSeconds())));
            case INVALID_CREDENTIALS -> error(exchange, 401, "invalid_credentials", "no guest has that key");
            case BANNED -> error(exchange, 403, "banned", "this account cannot play");
        }
    }

    /** {@code POST /v1/guests}: a guest, made with nothing asked (Q-22), throttled per address as registering is. */
    private void createGuest(HttpExchange exchange) throws IOException, SQLException {
        if (throttled(exchange)) {
            return;
        }
        AuthService.GuestResult guest = auth.createGuest();
        respond(exchange, 201, Json.MAPPER.writeValueAsString(
                new GuestResponse(guest.playerId(), guest.guestKey(), guest.displayName())));
    }

    /** {@code POST /v1/accounts/upgrade}: the session's guest made a full account, the same player (Q-22). */
    private void upgrade(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        RegisterRequest request = read(exchange, RegisterRequest.class);
        if (request == null || request.username() == null || request.password() == null) {
            error(exchange, 400, "invalid_body", "username and password are required");
            return;
        }
        AuthService.UpgradeResult result;
        try (PasswordHasher.Admission place = auth.admit()) {
            if (refusal(exchange, place, null) != null) {
                return;
            }
            char[] password = request.password().toCharArray();
            result = auth.upgrade(token, request.username(), request.displayName(), password);
            java.util.Arrays.fill(password, '\0');
        }
        switch (result.outcome()) {
            case OK -> respond(exchange, 200, "{}");
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_USERNAME -> error(exchange, 400, "invalid_username", "3 to 32 of a-z, A-Z, 0-9, _ and -");
            case INVALID_PASSWORD -> error(exchange, 400, "invalid_password", "8 to 128 characters");
            case INVALID_DISPLAY_NAME -> error(exchange, 400, "invalid_display_name", result.problem());
            case USERNAME_TAKEN -> error(exchange, 409, "username_taken", "that name is in use");
            case NOT_A_GUEST -> error(exchange, 409, "not_a_guest", "this account has a username already");
        }
    }

    /** {@code PUT /v1/accounts/name} {@code {"displayName"}}: the player's own, once in 30 days (04 §1). */
    private void rename(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        RenameRequest request = read(exchange, RenameRequest.class);
        AuthService.RenameResult result = auth.rename(token, request == null ? null : request.displayName());
        switch (result.outcome()) {
            case OK -> {
                try {
                    await(leaderboards.rename(result.playerId(), result.name()));
                } catch (StoreUnavailableException e) {
                    // Renamed all the same: the boards take the name at the player's next result (D-60).
                    log.warn("player {} renamed, the boards not yet: {}", result.playerId(), e.toString());
                }
                respond(exchange, 200, Json.MAPPER.createObjectNode().put("playerId", result.playerId())
                        .put("displayName", result.name()).toString());
            }
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_DISPLAY_NAME -> error(exchange, 400, "invalid_display_name", result.problem());
            case TOO_SOON -> error(exchange, 429, "too_soon", "a display name changes once in 30 days");
        }
    }

    /** The per-address limit alone, for what hashes nothing slow: true once a 429 is answered. */
    private boolean throttled(HttpExchange exchange) throws IOException {
        InetAddress who = clientAddress(exchange.getRemoteAddress().getAddress(),
                exchange.getRequestHeaders().get("X-Forwarded-For"));
        LoginThrottle.Verdict verdict = throttle.attempt(LoginThrottle.addressKey(who), null);
        if (verdict.allowed()) {
            return false;
        }
        exchange.getResponseHeaders().set("Retry-After", Integer.toString(verdict.retryAfterSeconds()));
        error(exchange, 429, "too_many_attempts", "try again in " + verdict.retryAfterSeconds() + " s");
        return true;
    }

    private void logout(HttpExchange exchange) throws IOException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        auth.logout(token);
        // 204 whether or not it existed: a caller asking to end a session that is already
        // gone has got what it wanted, and saying otherwise would confirm which tokens exist.
        respond(exchange, 204, null);
    }

    private void requestMatch(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        JoinService.Grant grant = joins.requestJoin(token);
        switch (grant.outcome()) {
            case OK -> respond(exchange, 200, Json.MAPPER.writeValueAsString(
                    new MatchResponse(grant.arenaHost(), grant.arenaPort(), grant.ticketId(),
                            grant.tls())));
            case NO_SESSION, NO_PROFILE -> error(exchange, 401, "invalid_session",
                    "log in again");
            // 503, not 500: nothing is broken, there is simply nowhere to put this player
            // right now, and the client should retry rather than report a fault.
            case NO_ARENA -> error(exchange, 503, "no_arena", "no arena has room");
            default -> error(exchange, 500, "internal", "unhandled outcome");
        }
    }

    // ---- the queue (04 §4, "The queue") -----------------------------------------------------

    private void queue(HttpExchange exchange) throws IOException {
        guard(exchange, ex -> {
            String token = bearerToken(ex);
            String sub = ex.getRequestURI().getPath().substring("/v1/queue".length());
            if (!sub.isEmpty()) {
                answerMatch(ex, sub, token);
            } else if (!java.util.Set.of("POST", "DELETE", "GET").contains(ex.getRequestMethod())) {
                methodNotAllowed(ex, "POST, DELETE, GET");
            } else if (token == null) {
                error(ex, 401, "no_token", "send Authorization: Bearer <token>");
            } else {
                switch (ex.getRequestMethod()) {
                    case "POST" -> joinQueue(ex, token);
                    case "DELETE" -> leaveQueue(ex, token);
                    default -> queueStatus(ex, token);
                }
            }
        });
    }

    /**
     * {@code POST /v1/queue/accept} or {@code /decline} {@code {"matchUid"}}: the answer to a match
     * found (04 §4, the third slice), answered with where the caller now stands.
     */
    private void answerMatch(HttpExchange ex, String sub, String token) throws IOException {
        if (!sub.equals("/accept") && !sub.equals("/decline")) {
            error(ex, 404, "not_found", "no such path");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            methodNotAllowed(ex, "POST");
            return;
        }
        if (token == null) {
            error(ex, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        AnswerRequest body = read(ex, AnswerRequest.class);
        if (body == null || body.matchUid() == null) {
            error(ex, 400, "invalid_body", "send the matchUid asked about");
            return;
        }
        switch (queues.answer(token, body.matchUid(), sub.equals("/accept"))) {
            case RECORDED -> queueStatus(ex, token);
            case NOT_CONFIRMING -> error(ex, 409, "not_confirming", "not asked about that match, or no longer");
            case NO_SESSION -> error(ex, 401, "invalid_session", "log in again");
        }
    }

    /**
     * {@code /v1/party}: GET the caller's party; POST {@code /invite} {@code {"playerId"}},
     * {@code /accept} {@code {"partyId"}}, {@code /leave}, {@code /kick} {@code {"playerId"}}, and
     * {@code /say} {@code {"phraseId"}} (04 §4, "Parties"; 01 §9). Each answers the caller's party
     * as it now is.
     */
    private void party(HttpExchange exchange) throws IOException {
        guard(exchange, ex -> {
            String action = ex.getRequestURI().getPath().substring("/v1/party".length());
            String method = action.isEmpty() ? "GET" : "POST";
            String token = bearerToken(ex);
            if (!java.util.Set.of("", "/invite", "/accept", "/leave", "/kick", "/say").contains(action)) {
                error(ex, 404, "not_found", "no such path");
            } else if (!method.equals(ex.getRequestMethod())) {
                methodNotAllowed(ex, method);
            } else if (token == null) {
                error(ex, 401, "no_token", "send Authorization: Bearer <token>");
            } else {
                PartyRequest body = action.isEmpty() || action.equals("/leave") ? null : read(ex, PartyRequest.class);
                PartyService.Result result = switch (action) {
                    case "/invite" -> body == null || body.playerId() == null ? null : parties.invite(token, body.playerId());
                    case "/accept" -> body == null || body.partyId() == null ? null : parties.accept(token, body.partyId());
                    case "/kick" -> body == null || body.playerId() == null ? null : parties.kick(token, body.playerId());
                    case "/say" -> body == null || body.phraseId() == null ? null : parties.say(token, body.phraseId());
                    case "/leave" -> parties.leave(token);
                    default -> parties.view(token);
                };
                if (result == null) {
                    error(ex, 400, "invalid_body", "send the playerId, partyId or phraseId the request needs");
                    return;
                }
                switch (result.outcome()) {
                    case OK -> respond(ex, 200, PartyService.json(result.party()).toString());
                    case NO_SESSION -> error(ex, 401, "invalid_session", "log in again");
                    case SELF -> error(ex, 400, "self", "a player cannot invite themself");
                    case IN_PARTY -> error(ex, 409, "in_party", "already in a party");
                    case NOT_LEADER -> error(ex, 409, "not_leader", "only the party's leader may");
                    case PARTY_FULL -> error(ex, 409, "party_full", "a party holds " + com.backend.platform.Parties.MOST);
                    case NOT_IN_LOBBY -> error(ex, 404, "not_in_lobby", "that player is not in the lobby");
                    case NO_INVITATION -> error(ex, 404, "no_invitation", "no invitation to that party, or it has lapsed");
                    case NOT_MEMBER -> error(ex, 404, "not_member", "not a member of a party you lead");
                    case QUEUED -> error(ex, 409, "queued", "the party is queued: it takes no one in until it is out");
                    case UNKNOWN_PHRASE -> error(ex, 400, "unknown_phrase", "no such phrase in the list: GET /v1/content/phrases");
                    case NO_PARTY -> error(ex, 404, "no_party", "not in a party");
                    case TOO_SOON -> error(ex, 429, "too_soon", action.equals("/invite")
                            ? "sixty invitations an hour at most" : "one phrase every two seconds");
                }
            }
        });
    }

    private void joinQueue(HttpExchange exchange, String token) throws IOException, SQLException {
        QueueRequest request = read(exchange, QueueRequest.class);
        String mode = request == null ? null : request.mode();
        switch (queues.join(token, mode)) {
            case QUEUED -> respond(exchange, 200, Json.MAPPER.writeValueAsString(
                    new QueueResponse("queued", mode, 0L, null, null, null)));
            case ALREADY_QUEUED -> error(exchange, 409, "already_queued", "already in a queue");
            case IN_MATCH -> error(exchange, 409, "in_match", "a match is waiting: GET /v1/queue");
            case UNKNOWN_MODE -> error(exchange, 400, "unknown_mode", "no queue for " + mode);
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case IN_PARTY -> error(exchange, 409, "in_party", "the party's leader queues it");
            case PARTY_TOO_BIG -> error(exchange, 400, "party_too_big", "the party is bigger than a team of " + mode);
            case PARTY_CHANGED -> error(exchange, 409, "party_changed", "the party changed meanwhile: try again");
            case QUEUE_LOCKED -> error(exchange, 409, "queue_locked", "a match was declined a moment ago: wait a minute");
            case PARTY_TOO_SMALL -> error(exchange, 400, "party_too_small", "a team match needs a party of three");
            case NOT_ONE_TEAM -> error(exchange, 409, "not_one_team", "every member of the party must be in your team");
            case NOT_ALLOWED -> error(exchange, 403, "not_allowed", "the team's leader or a vice leader queues it");
        }
    }

    /** {@code POST /v1/sandbox}: one opened at once, for the caller or the caller's party (04 §4, the seventh slice). */
    private void openSandbox(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        QueueService.Sandbox opened = queues.openSandbox(token);
        MatchQueue.Grant g = opened.grant();
        switch (opened.outcome()) {
            case OPENED -> respond(exchange, 200, Json.MAPPER.writeValueAsString(
                    new FoundView(g.arenaHost(), g.arenaPort(), g.ticketId(), g.tls(), MatchMode.SANDBOX.key)));
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case IN_PARTY -> error(exchange, 409, "in_party", "the party's leader opens it");
            case ALREADY_QUEUED -> error(exchange, 409, "already_queued", "leave the queue first");
            case IN_MATCH -> error(exchange, 409, "in_match", "a match is waiting: GET /v1/queue");
            case IN_SANDBOX -> error(exchange, 409, "in_sandbox", "a sandbox is held until it ends or is left");
            case NO_ROOM -> error(exchange, 503, "no_room", "no arena has a room free");
        }
    }

    private void leaveQueue(HttpExchange exchange, String token) throws IOException {
        if (!queues.leave(token)) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        // 200 whether or not it was queued: leaving is what the caller wanted, and it has.
        respond(exchange, 200, Json.MAPPER.writeValueAsString(new QueueResponse("none", null, null, null, null, null)));
    }

    /** Where the player stands, and the match if one was made: what a client missing the push asks. */
    private void queueStatus(HttpExchange exchange, String token) throws IOException {
        MatchQueue.Status status = queues.status(token);
        if (status == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        String mode = status.mode() == null ? null : status.mode().key;
        Long waited = status.mode() == null ? null
                : Math.max(0, (queues.now() - status.since()) / 1_000);
        MatchQueue.Grant g = status.grant();
        FoundView grant = g == null ? null
                : new FoundView(g.arenaHost(), g.arenaPort(), g.ticketId(), g.tls(), mode);
        Long secondsLeft = status.matchUid() == null ? null
                : Math.max(0, (status.deadline() - queues.now() + 999) / 1_000);
        respond(exchange, 200, Json.MAPPER.writeValueAsString(
                new QueueResponse(status.state(), mode, waited, grant, status.matchUid(), secondsLeft)));
    }

    /**
     * {@code GET /v1/leaderboards/{board}} and {@code GET /v1/leaderboards/{board}/me}.
     *
     * The top of a board is public: it is the thing players are meant to show each other,
     * and requiring a session to read it would only mean the client had to log in before it
     * could put anything on the title screen. "Where am I" needs one, because it is about
     * a particular player.
     */
    // ---- the shop (04 §8, "The shop, as built") --------------------------------------------

    /** {@code GET /v1/shop}: what is on sale now. The same for everyone, so no session. */
    private void shop(HttpExchange exchange) throws IOException {
        List<OfferView> offers = new ArrayList<>();
        for (Catalogue.Offer o : shop.onSale()) {
            offers.add(new OfferView(o.sku(), o.itemId(), o.price(), o.requiresLevel(),
                    o.availableTo() == null ? null : o.availableTo().toString(),
                    o.currency() == Catalogue.GEMS ? "gems" : "coins"));
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(new ShopResponse(offers)));
    }

    /**
     * {@code GET /v1/content/classes}: the class table a client draws by (D-24), versioned by its
     * hash, the same number the arena sends in {@code Welcome.contentVersion}. A client that names
     * the version it holds is told it has it.
     */
    private void classes(HttpExchange exchange) throws IOException {
        String etag = "\"" + CLASSES.version() + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            respond(exchange, 304, "");
            return;
        }
        respond(exchange, 200, "{\"version\":" + CLASSES.version() + ",\"classes\":" + CLASSES.json() + "}");
    }

    /**
     * {@code GET /v1/content/phrases}: what a player may say (01 §9), served and versioned as the
     * class table is; the version is the one the arena sends in {@code Welcome.phraseListVersion}.
     */
    private void phrases(HttpExchange exchange) throws IOException {
        String etag = "\"" + PHRASES.version() + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            respond(exchange, 304, "");
            return;
        }
        respond(exchange, 200, "{\"version\":" + PHRASES.version() + ",\"phrases\":" + PHRASES.json() + "}");
    }

    /**
     * {@code GET /v1/content/skins}: what each skin number a tank is told with is (04 §8, D-70), served and
     * versioned as the class table is.
     */
    private void skins(HttpExchange exchange) throws IOException {
        String etag = "\"" + skinsVersion + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            respond(exchange, 304, "");
            return;
        }
        respond(exchange, 200, "{\"version\":" + skinsVersion + ",\"skins\":" + skinsJson + "}");
    }

    /** {@code GET /v1/inventory}: the player's coins, gems and items. */
    /** {@code /v1/inventory/{itemId}/level}, an item's raise (04 §8, plan item 67). */
    private static final java.util.regex.Pattern LEVEL_PATH = java.util.regex.Pattern.compile("/v1/inventory/([a-z0-9_]{1,40})/level");

    /** {@code GET /v1/inventory}, and {@code POST /v1/inventory/{itemId}/level}. */
    private void inventoryRoutes(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        java.util.regex.Matcher level = LEVEL_PATH.matcher(path);
        if (path.equals("/v1/inventory")) {
            route(exchange, "GET", this::inventory);
        } else if (level.matches()) {
            route(exchange, "POST", ex -> raise(ex, level.group(1)));
        } else {
            // Through guard, as every other answer, so it is counted (the platform review, 2026-10-04).
            guard(exchange, ex -> error(ex, 404, "no_such_route", "use /v1/inventory or /v1/inventory/{itemId}/level"));
        }
    }

    /** {@code POST /v1/inventory/{itemId}/level} {@code {"key"}}: a level, once a key (04 §8). */
    private void raise(HttpExchange exchange, String itemId) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        PurchaseRequest request = read(exchange, PurchaseRequest.class);
        String key = request == null ? null : request.key();
        // As a purchase's: the ledger compares keys ignoring case and accents, and a short counter repeats (04 §8).
        if (!ShopService.validKey(key)) {
            error(exchange, 400, "invalid_key", "a key of 16 to 48 lowercase letters, digits or hyphens, one a raise");
            return;
        }
        if (!equipment.isEquipment(itemId)) {
            error(exchange, 400, "not_equipment", "only an item that is worn has levels");
            return;
        }
        ShopService.Leveled done = shop.raise(token, itemId, key);
        if (done.result() == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        switch (done.result()) {
            case RAISED, ALREADY -> respond(exchange, 200, Json.MAPPER.createObjectNode().put("itemId", itemId)
                    .put("level", done.level()).put("coins", done.coins()).toString());
            case NOT_HELD -> error(exchange, 404, "not_held", "the player holds none");
            case MAX_LEVEL -> error(exchange, 409, "max_level", "level " + com.backend.persistence.EconomyRepository.MAX_ITEM_LEVEL
                    + " is the top");
            case INSUFFICIENT_FUNDS -> error(exchange, 409, "insufficient_funds", "not enough coins for the next level");
        }
    }

    private void inventory(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        ShopService.Holdings held = shop.holdings(token);
        if (held == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        List<ItemView> items = new ArrayList<>();
        for (var h : held.items()) {
            items.add(new ItemView(h.itemId(), h.qty(), h.level()));
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(
                new InventoryResponse(held.coins(), held.gems(), items)));
    }

    /** {@code POST /v1/purchases}: buys one offer, once per key however often it is sent. */
    private void purchase(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        PurchaseRequest request = read(exchange, PurchaseRequest.class);
        if (request == null || request.sku() == null || request.key() == null) {
            error(exchange, 400, "invalid_body", "sku and key are required");
            return;
        }
        ShopService.Receipt receipt = shop.buy(token, request.sku(), request.key());
        purchases.increment(receipt.result().name().toLowerCase(java.util.Locale.ROOT));
        switch (receipt.result()) {
            case BOUGHT, ALREADY_BOUGHT -> respond(exchange, 200, Json.MAPPER.writeValueAsString(
                    new PurchaseResponse(receipt.result().name().toLowerCase(java.util.Locale.ROOT),
                            receipt.itemId(), receipt.coins(), receipt.held(), receipt.gems())));
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_KEY -> error(exchange, 400, "invalid_key",
                    "16 to 48 lowercase letters, digits or hyphens: a UUID, in lower case,"
                            + " the same for every retry of one purchase");
            case UNKNOWN_SKU -> error(exchange, 404, "unknown_sku", "no such offer");
            case NOT_AVAILABLE -> error(exchange, 409, "not_available", "not on sale now");
            case LEVEL_REQUIRED -> error(exchange, 403, "level_required",
                    "reach level " + receipt.requiredLevel());
            case INSUFFICIENT_FUNDS -> error(exchange, 409, "insufficient_funds",
                    "not enough of the offer's currency, coins or gems");
        }
    }

    // ---- gems for money (04 §8, revenue, D-68) -----------------------------------------------

    private static final java.util.regex.Pattern ORDER_PATH = java.util.regex.Pattern.compile(
            "/v1/payments/([0-9a-f-]{36})(/simulate)?");

    /**
     * {@code GET /v1/payments/packs}, {@code POST /v1/payments}, {@code GET /v1/payments/{orderId}} and
     * {@code POST /v1/payments/{orderId}/simulate}; every one 503 {@code payments_off} while no provider is named.
     */
    private void payments(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        java.util.regex.Matcher one = ORDER_PATH.matcher(path);
        String allowed = path.equals("/v1/payments") ? "POST" : path.equals("/v1/payments/packs") ? "GET"
                : !one.matches() ? null : one.group(2) == null ? "GET" : "POST";
        if (allowed == null) {
            error(exchange, 404, "no_such_route", "use /v1/payments, /v1/payments/packs or /v1/payments/{orderId}");
            return;
        }
        if (!allowed.equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, allowed);
            return;
        }
        if (!payments.on()) {
            error(exchange, 503, "payments_off", "nothing is sold here: no payment provider is named");
            return;
        }
        if (path.equals("/v1/payments/packs")) {
            List<PackView> packs = new ArrayList<>();
            for (com.backend.platform.Packs.Pack p : payments.packs()) {
                packs.add(new PackView(p.productId(), p.gems(), p.priceCents(), com.backend.platform.Packs.CURRENCY));
            }
            respond(exchange, 200, Json.MAPPER.writeValueAsString(new PacksResponse(packs)));
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        com.backend.platform.PaymentService.Answer answer;
        boolean confirm = false;
        if (path.equals("/v1/payments")) {
            OrderRequest request = read(exchange, OrderRequest.class);
            if (request == null || request.productId() == null || request.key() == null) {
                error(exchange, 400, "invalid_body", "productId and key are required");
                return;
            }
            answer = payments.order(token, request.productId(), request.key());
        } else if (one.group(2) == null) {
            answer = payments.get(token, one.group(1));
        } else {
            SimulateRequest request = read(exchange, SimulateRequest.class);
            String outcome = request == null ? null : request.outcome();
            if (!"paid".equals(outcome) && !"declined".equals(outcome)) {
                error(exchange, 400, "invalid_outcome", "the simulated provider's outcome: paid or declined");
                return;
            }
            answer = payments.simulate(token, one.group(1), "paid".equals(outcome));
            confirm = true;
        }
        switch (answer.result()) {
            case OK -> respond(exchange, 200, Json.MAPPER.writeValueAsString(confirm
                    ? new ConfirmResponse(orderView(answer.order()), answer.confirmed(), answer.gems())
                    : new OrderResponse(orderView(answer.order()))));
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_KEY -> error(exchange, 400, "invalid_key",
                    "16 to 36 lowercase letters, digits or hyphens: a UUID, in lower case,"
                            + " the same for every retry of one order");
            case UNKNOWN_PRODUCT -> error(exchange, 404, "unknown_product", "no such pack");
            case REFUND_DEBT -> error(exchange, 409, "refund_debt",
                    "a refunded purchase's gems were spent: support clears the debt before another order");
            case NO_SUCH_ORDER -> error(exchange, 404, "no_such_order", "no such order of the player's");
        }
    }

    // ---- the season pass (04 §8, revenue (b), D-69) -----------------------------------------

    /** Every tier's rewards on both tracks: the same for everyone, all season. */
    private static final List<TierView> TIERS = tiers();

    private static List<TierView> tiers() {
        List<TierView> tiers = new ArrayList<>();
        for (int t = 1; t <= com.backend.persistence.SeasonPass.TIERS; t++) {
            var free = com.backend.persistence.SeasonPass.free(t);
            var premium = com.backend.persistence.SeasonPass.premium(t);
            tiers.add(new TierView(t, new RewardView(free.coins(), free.gems(), free.itemId()),
                    new RewardView(premium.coins(), premium.gems(), premium.itemId())));
        }
        return List.copyOf(tiers);
    }

    private static PassResponse passView(com.backend.persistence.SeasonPassRepository.Pass p) {
        return new PassResponse(p.season(), p.endsAt().toString(), p.points(), p.tier(), p.premium(),
                com.backend.persistence.SeasonPass.PREMIUM_GEMS, com.backend.persistence.SeasonPass.TIER_POINTS, TIERS);
    }

    /** {@code GET /v1/pass}, the player's pass; {@code POST /v1/pass/premium}, its premium track bought. */
    private void pass(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String allowed = path.equals("/v1/pass") ? "GET" : path.equals("/v1/pass/premium") ? "POST" : null;
        if (allowed == null) {
            error(exchange, 404, "no_such_route", "use /v1/pass or /v1/pass/premium");
            return;
        }
        if (!allowed.equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, allowed);
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        if (allowed.equals("GET")) {
            var pass = passes.of(token);
            if (pass == null) {
                error(exchange, 401, "invalid_session", "log in again");
                return;
            }
            respond(exchange, 200, Json.MAPPER.writeValueAsString(passView(pass)));
            return;
        }
        var premium = passes.buyPremium(token);
        if (premium == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        switch (premium.result()) {
            case BOUGHT, ALREADY_BOUGHT -> respond(exchange, 200, Json.MAPPER.writeValueAsString(new PremiumResponse(
                    premium.result().name().toLowerCase(java.util.Locale.ROOT), premium.gems(), passView(premium.pass()))));
            case INSUFFICIENT_FUNDS -> error(exchange, 409, "insufficient_funds",
                    "premium is " + com.backend.persistence.SeasonPass.PREMIUM_GEMS + " gems");
            case SEASON_ENDED -> error(exchange, 409, "season_ended", "the season has ended and is being closed");
        }
    }

    /** An order's state by its name. */
    static final List<String> ORDER_STATES = List.of("pending", "paid", "declined", "refunded", "expired");

    private static OrderView orderView(com.backend.persistence.PaymentRepository.Order o) {
        return new OrderView(o.id(), o.productId(), o.gems(), o.bonus(), o.priceCents(), o.currency(),
                ORDER_STATES.get(o.state()), o.createdAt().toString());
    }

    /**
     * {@code GET /v1/equipment}, and {@code PUT} and {@code DELETE /v1/equipment/{slot}}: what is
     * worn and the bonus it gives, wearing an item held and taking one off (04 §8).
     */
    private void equipment(HttpExchange exchange) throws IOException, SQLException {
        String rest = exchange.getRequestURI().getPath().substring("/v1/equipment".length());
        boolean whole = rest.isEmpty() || rest.equals("/");
        String method = exchange.getRequestMethod();
        if (whole ? !"GET".equals(method) : !"PUT".equals(method) && !"DELETE".equals(method)) {
            methodNotAllowed(exchange, whole ? "GET" : "PUT, DELETE");
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        EquipmentService.Answer answer;
        if (whole) {
            answer = equipment.loadout(token);
        } else if ("PUT".equals(method)) {
            WearRequest request = read(exchange, WearRequest.class);
            if (request == null || request.itemId() == null) {
                error(exchange, 400, "invalid_body", "itemId is required");
                return;
            }
            answer = equipment.wear(token, rest.substring(1), request.itemId());
        } else {
            answer = equipment.takeOff(token, rest.substring(1));
        }
        switch (answer.result()) {
            case OK -> respond(exchange, 200, Json.MAPPER.writeValueAsString(loadoutView(answer.loadout())));
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_SLOT -> error(exchange, 400, "invalid_slot", "one of barrel, armor, core, treads, skin");
            case UNKNOWN_ITEM -> error(exchange, 404, "unknown_item", "no such item");
            case WRONG_SLOT -> error(exchange, 409, "wrong_slot", "it is worn in another slot, or not worn at all, as a boost");
            case NOT_OWNED -> error(exchange, 409, "not_owned", "buy it first");
        }
    }

    /** {@code GET /v1/boosts}: those running; {@code POST}: activates one held, once per key (04 §8). */
    private void boosts(HttpExchange exchange) throws IOException, SQLException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            methodNotAllowed(exchange, "GET, POST");
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        BoostService.Answer answer;
        String result = null;
        if ("GET".equals(method)) {
            answer = boosts.running(token);
        } else {
            ActivateRequest request = read(exchange, ActivateRequest.class);
            if (request == null || request.itemId() == null || request.key() == null) {
                error(exchange, 400, "invalid_body", "itemId and key are required");
                return;
            }
            answer = boosts.activate(token, request.itemId(), request.key());
            result = answer.result().name().toLowerCase(java.util.Locale.ROOT);
        }
        switch (answer.result()) {
            case OK, ACTIVATED, ALREADY_ACTIVATED -> {
                java.util.List<BoostView> running = new java.util.ArrayList<>();
                for (var b : answer.running()) {
                    running.add(new BoostView(Items.boostKindName(b.kind()), b.itemId(), b.percent(),
                            b.endsAt().toString()));
                }
                respond(exchange, 200, Json.MAPPER.writeValueAsString(new BoostsResponse(result, running)));
            }
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_KEY -> error(exchange, 400, "invalid_key",
                    "16 to 48 lowercase letters, digits or hyphens: a UUID, in lower case,"
                            + " the same for every retry of one activation");
            case UNKNOWN_ITEM -> error(exchange, 404, "unknown_item", "no such boost");
            case NOT_OWNED -> error(exchange, 409, "not_owned", "buy it first");
            case OTHER_RUNNING -> error(exchange, 409, "other_running",
                    "a different boost of that kind runs: wait until it ends");
        }
    }

    private static final java.util.regex.Pattern PLAYER_PATH = java.util.regex.Pattern.compile("/(\\d{1,18})");

    /** {@code GET} and {@code POST /v1/friends}, and {@code DELETE /v1/friends/{playerId}} (04 §9). */
    private void friends(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath().substring("/v1/friends".length());
        java.util.regex.Matcher one = PLAYER_PATH.matcher(path);
        String token = social(exchange, path.isEmpty() || path.equals("/") ? "GET, POST" : one.matches() ? "DELETE" : null);
        if (token == null) {
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                FriendService.Lists lists = friends.lists(token);
                if (lists == null) {
                    error(exchange, 401, "invalid_session", "log in again");
                    return;
                }
                var json = Json.MAPPER.createObjectNode();
                var list = json.putArray("friends");
                for (var f : lists.friends()) {
                    list.addObject().put("playerId", f.playerId()).put("name", f.name()).put("online", f.online());
                }
                for (String key : new String[] {"requests", "asked"}) {
                    var requests = json.putArray(key);
                    for (var r : key.equals("requests") ? lists.requests() : lists.asked()) {
                        requests.addObject().put("playerId", r.playerId()).put("name", r.name())
                                .put("expiresAt", r.expiresAt().toString());
                    }
                }
                respond(exchange, 200, Json.MAPPER.writeValueAsString(json));
            }
            case "POST" -> {
                PlayerRequest body = read(exchange, PlayerRequest.class);
                if (body == null || body.playerId() == null) {
                    error(exchange, 400, "invalid_body", "playerId is required");
                    return;
                }
                FriendService.Asked asked = friends.ask(token, body.playerId());
                if (asked.result() == FriendService.Result.OK) {
                    respond(exchange, 200, Json.MAPPER.writeValueAsString(java.util.Map.of("state", asked.state())));
                } else {
                    socialError(exchange, asked.result());
                }
            }
            default -> socialAnswer(exchange, friends.remove(token, Long.parseLong(one.group(1))));
        }
    }

    /** {@code DELETE /v1/friend-requests/{playerId}}: declines theirs, or withdraws one's own. */
    private void friendRequests(HttpExchange exchange) throws IOException, SQLException {
        java.util.regex.Matcher one = PLAYER_PATH.matcher(
                exchange.getRequestURI().getPath().substring("/v1/friend-requests".length()));
        String token = social(exchange, one.matches() ? "DELETE" : null);
        if (token != null) {
            socialAnswer(exchange, friends.dropRequest(token, Long.parseLong(one.group(1))));
        }
    }

    /** {@code GET} and {@code POST /v1/blocks}, and {@code DELETE /v1/blocks/{playerId}}. */
    private void blocks(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath().substring("/v1/blocks".length());
        java.util.regex.Matcher one = PLAYER_PATH.matcher(path);
        String token = social(exchange, path.isEmpty() || path.equals("/") ? "GET, POST" : one.matches() ? "DELETE" : null);
        if (token == null) {
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                var blocked = friends.blocked(token);
                if (blocked == null) {
                    error(exchange, 401, "invalid_session", "log in again");
                    return;
                }
                var json = Json.MAPPER.createObjectNode();
                var list = json.putArray("blocked");
                for (var p : blocked) {
                    list.addObject().put("playerId", p.playerId()).put("name", p.name());
                }
                respond(exchange, 200, Json.MAPPER.writeValueAsString(json));
            }
            case "POST" -> {
                PlayerRequest body = read(exchange, PlayerRequest.class);
                if (body == null || body.playerId() == null) {
                    error(exchange, 400, "invalid_body", "playerId is required");
                    return;
                }
                socialAnswer(exchange, friends.block(token, body.playerId()));
            }
            default -> socialAnswer(exchange, friends.unblock(token, Long.parseLong(one.group(1))));
        }
    }

    static final String[] INBOX_KINDS = {null, "friend_request", "friend_accepted", "team_invite", "tournament_prize",
            "team_application", "season_reward"};

    /** {@code GET /v1/inbox}, and {@code POST /v1/inbox/read} {@code {"upTo"}} (04 §9). */
    private void inbox(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath().substring("/v1/inbox".length());
        String token = social(exchange, path.isEmpty() || path.equals("/") ? "GET" : path.equals("/read") ? "POST" : null);
        if (token == null) {
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            var items = inbox.items(token);
            if (items == null) {
                error(exchange, 401, "invalid_session", "log in again");
                return;
            }
            var json = Json.MAPPER.createObjectNode();
            var list = json.putArray("items");
            for (var item : items) {
                list.addObject().put("id", item.id()).put("kind", INBOX_KINDS[item.kind()]).put("ref", item.ref())
                        .put("at", item.at().toString()).put("read", item.read());
            }
            respond(exchange, 200, Json.MAPPER.writeValueAsString(json));
            return;
        }
        InboxRead body = read(exchange, InboxRead.class);
        if (body == null || body.upTo() == null) {
            error(exchange, 400, "invalid_body", "upTo is required: the newest item's id read");
            return;
        }
        if (inbox.read(token, body.upTo())) {
            respond(exchange, 200, "{}");
        } else {
            error(exchange, 401, "invalid_session", "log in again");
        }
    }

    /** The route's methods checked and its token read: null once an error has been answered. */
    private String social(HttpExchange exchange, String allowed) throws IOException {
        if (allowed == null) {
            error(exchange, 404, "no_such_route", "see docs 04 §9 for the social routes");
            return null;
        }
        if (!java.util.List.of(allowed.split(", ")).contains(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, allowed);
            return null;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
        }
        return token;
    }

    private void socialAnswer(HttpExchange exchange, FriendService.Result result) throws IOException {
        if (result == FriendService.Result.OK) {
            respond(exchange, 200, "{}");
        } else {
            socialError(exchange, result);
        }
    }

    private void socialError(HttpExchange exchange, FriendService.Result result) throws IOException {
        switch (result) {
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case YOURSELF -> error(exchange, 400, "yourself", "not yourself");
            case NO_SUCH_PLAYER -> error(exchange, 404, "no_such_player", "no such player");
            case ALREADY_FRIENDS -> error(exchange, 409, "already_friends", "friends already");
            case ALREADY_ASKED -> error(exchange, 409, "already_asked", "asked already");
            case FRIENDS_FULL -> error(exchange, 409, "friends_full", "one of you has as many friends as there may be");
            case YOU_BLOCKED -> error(exchange, 409, "you_blocked", "you have blocked them: unblock them first");
            case NOT_FRIENDS -> error(exchange, 404, "not_friends", "not friends");
            case NO_REQUEST -> error(exchange, 404, "no_request", "no request between you");
            case BLOCKS_FULL -> error(exchange, 409, "blocks_full", "as many blocked as there may be");
            case NOT_BLOCKED -> error(exchange, 404, "not_blocked", "not blocked");
            case TOO_MANY_ASKED -> error(exchange, 409, "too_many_asked",
                    "as many requests out as there may be: wait for answers, or withdraw one");
            case TOO_SOON -> error(exchange, 429, "too_soon", "twenty requests an hour at most");
        }
    }

    private static final java.util.regex.Pattern MEMBER_PATH = java.util.regex.Pattern.compile("/mine/members/(\\d{1,18})(/role)?");
    /** {@code /v1/teams/{id}}, and with {@code /applications} (Q-49). */
    private static final java.util.regex.Pattern TEAM_PATH = java.util.regex.Pattern.compile("/(\\d{1,18})(/applications)?");
    private static final java.util.regex.Pattern APPLICANT_PATH = java.util.regex.Pattern.compile("/mine/applications/(\\d{1,18})");

    /** {@code /v1/teams…}: teams' first slice (04 §2); which action is the path and the method. */
    private void teams(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath().substring("/v1/teams".length());
        String method = exchange.getRequestMethod();
        java.util.regex.Matcher member = MEMBER_PATH.matcher(path);
        java.util.regex.Matcher one = TEAM_PATH.matcher(path);
        java.util.regex.Matcher applicant = APPLICANT_PATH.matcher(path);
        String allowed = switch (path) {
            case "", "/" -> "GET, POST";
            case "/mine" -> "GET, DELETE";
            case "/mine/invites", "/mine/leave", "/mine/leader" -> "POST";
            case "/mine/name" -> "PUT";
            case "/mine/applications" -> "GET";
            default -> member.matches() ? (member.group(2) == null ? "DELETE" : "POST")
                    : applicant.matches() ? "POST"
                    : one.matches() ? (one.group(2) == null ? "GET" : "POST, DELETE") : null;
        };
        if (allowed == null) {
            error(exchange, 404, "no_such_route", "see docs 04 §2 for the team routes");
            return;
        }
        if (!java.util.List.of(allowed.split(", ")).contains(method)) {
            methodNotAllowed(exchange, allowed);
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        if ((path.isEmpty() || path.equals("/")) && "GET".equals(method)) {
            foundTeams(exchange, teams.search(token, queryParam(exchange, "name")), false);
            return;
        }
        if (one.matches() && one.group(2) == null) {
            foundTeams(exchange, teams.one(token, Long.parseLong(one.group(1))), true);
            return;
        }
        if (path.equals("/mine/applications")) {
            TeamService.Applications listed = teams.applications(token);
            if (listed.result() != TeamService.Result.OK) {
                teamAnswer(exchange, new TeamService.Answer(listed.result(), null, java.util.List.of()));
                return;
            }
            com.fasterxml.jackson.databind.node.ObjectNode out = Json.MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode rows = out.putArray("applications");
            for (var a : listed.applications()) {
                rows.addObject().put("playerId", a.playerId()).put("name", a.name()).put("expiresAt", a.expiresAt().toString());
            }
            respond(exchange, 200, out.toString());
            return;
        }
        if (one.matches()) {                            // /{id}/applications: apply, or withdraw
            long teamId = Long.parseLong(one.group(1));
            TeamService.Answer done = "POST".equals(method) ? teams.apply(token, teamId) : teams.withdrawApplication(token, teamId);
            if (done.result() == TeamService.Result.OK) {
                respond(exchange, 200, "{}");
            } else {
                teamAnswer(exchange, done);
            }
            return;
        }
        TeamRequest body = "POST".equals(method) || "PUT".equals(method) ? read(exchange, TeamRequest.class) : null;
        TeamService.Answer answer;
        if (applicant.matches()) {
            if (body == null || body.accept() == null) {
                error(exchange, 400, "invalid_body", "accept is required: true or false");
                return;
            }
            answer = teams.answerApplication(token, Long.parseLong(applicant.group(1)), body.accept());
        } else if (member.matches()) {
            long playerId = Long.parseLong(member.group(1));
            if (member.group(2) == null) {
                answer = teams.kick(token, playerId);
            } else if (body == null || body.role() == null) {
                error(exchange, 400, "invalid_body", "role is required: vice_leader or member");
                return;
            } else {
                answer = teams.setRole(token, playerId, body.role());
            }
        } else if (path.equals("/mine")) {
            answer = "GET".equals(method) ? teams.mine(token) : teams.disband(token);
        } else if (path.equals("/mine/leave")) {
            answer = teams.leave(token);
        } else if (path.equals("/mine/invites")) {
            if (body == null || body.playerId() == null) {
                error(exchange, 400, "invalid_body", "playerId is required");
                return;
            }
            answer = teams.invite(token, body.playerId());
        } else if (path.equals("/mine/name")) {
            answer = teams.rename(token, body == null ? null : body.name());
        } else if (path.equals("/mine/leader")) {
            if (body == null || body.playerId() == null) {
                error(exchange, 400, "invalid_body", "playerId is required");
                return;
            }
            answer = teams.transfer(token, body.playerId());
        } else {
            answer = teams.create(token, body == null ? null : body.name());
        }
        teamAnswer(exchange, answer);
    }

    /** {@code GET /v1/team-applications}: the player's own, unanswered (Q-49). */
    private void teamApplications(HttpExchange exchange) throws IOException, SQLException {
        if (!exchange.getRequestURI().getPath().equals("/v1/team-applications")) {
            error(exchange, 404, "no_such_route", "use /v1/team-applications");
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        java.util.List<com.backend.persistence.TeamRepository.Applying> mine = teams.applying(token);
        if (mine == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = Json.MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode rows = out.putArray("applications");
        for (var a : mine) {
            rows.addObject().put("teamId", a.teamId()).put("teamName", a.teamName()).put("expiresAt", a.expiresAt().toString());
        }
        respond(exchange, 200, out.toString());
    }

    /** {@code GET /v1/team-invites}: the player's; {@code POST /v1/team-invites/{teamId}} {@code {"accept"}}. */
    private void teamInvites(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath().substring("/v1/team-invites".length());
        boolean list = path.isEmpty() || path.equals("/");
        if (!list && !path.matches("/\\d{1,18}")) {
            error(exchange, 404, "no_such_route", "use /v1/team-invites or /v1/team-invites/{teamId}");
            return;
        }
        String method = exchange.getRequestMethod();
        if (!method.equals(list ? "GET" : "POST")) {
            methodNotAllowed(exchange, list ? "GET" : "POST");
            return;
        }
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        if (list) {
            teamAnswer(exchange, teams.invites(token));
            return;
        }
        TeamRequest body = read(exchange, TeamRequest.class);
        if (body == null || body.accept() == null) {
            error(exchange, 400, "invalid_body", "accept is required: true or false");
            return;
        }
        teamAnswer(exchange, teams.answer(token, Long.parseLong(path.substring(1)), body.accept()));
    }

    /** Teams found (Q-49): a search's, as {@code {"teams": […]}}, or {@code alone}, the one team. */
    private void foundTeams(HttpExchange exchange, TeamService.Search search, boolean alone) throws IOException {
        if (search.result() != TeamService.Result.OK) {
            teamAnswer(exchange, new TeamService.Answer(search.result(), null, java.util.List.of()));
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = Json.MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode rows = out.putArray("teams");
        for (var t : search.teams()) {
            rows.addObject().put("id", t.id()).put("name", t.name()).put("members", t.members()).put("rating", t.rating());
        }
        respond(exchange, 200, alone ? rows.get(0).toString() : out.toString());
    }

    private void teamAnswer(HttpExchange exchange, TeamService.Answer answer) throws IOException {
        switch (answer.result()) {
            case OK -> {
                if (answer.team() != null) {
                    java.util.List<MemberView> members = new java.util.ArrayList<>();
                    for (var m : answer.team().members()) {
                        members.add(new MemberView(m.playerId(), m.name(), switch (m.role()) {
                            case com.backend.persistence.TeamRepository.LEADER -> "leader";
                            case com.backend.persistence.TeamRepository.VICE -> "vice_leader";
                            default -> "member";
                        }));
                    }
                    respond(exchange, 200, Json.MAPPER.writeValueAsString(
                            new TeamView(answer.team().id(), answer.team().name(), members, answer.team().rating(),
                                    answer.team().wins(), answer.team().losses(), answer.team().draws())));
                } else if (answer.invites() != null) {
                    java.util.List<InviteView> invites = new java.util.ArrayList<>();
                    for (var i : answer.invites()) {
                        invites.add(new InviteView(i.teamId(), i.teamName(), i.expiresAt().toString()));
                    }
                    respond(exchange, 200, Json.MAPPER.writeValueAsString(new InvitesResponse(invites)));
                } else {
                    respond(exchange, 200, "{}");              // left, disbanded: nothing to say
                }
            }
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case INVALID_NAME -> error(exchange, 400, "invalid_name", "a team is named as a player is");
            case INVALID_ROLE -> error(exchange, 400, "invalid_role", "vice_leader or member");
            case NAME_TAKEN -> error(exchange, 409, "name_taken", "however it is cased or accented");
            case IN_TEAM -> error(exchange, 409, "in_team", "one team a player");
            case COOLING_DOWN -> error(exchange, 409, "cooling_down", "a day after leaving a team before another");
            case NO_TEAM -> error(exchange, 404, "no_team", "in no team");
            case NOT_ALLOWED -> error(exchange, 403, "not_allowed", "not for this role");
            case NO_SUCH_PLAYER -> error(exchange, 404, "no_such_player", "no player with that code");
            case NO_INVITE -> error(exchange, 404, "no_invite", "no invitation from that team, or it has expired");
            case TEAM_FULL -> error(exchange, 409, "team_full", "the team has no place left");
            case LEADER_WITH_MEMBERS -> error(exchange, 409, "leader_with_members", "hand the team over first");
            case NOT_A_MEMBER -> error(exchange, 404, "not_a_member", "not in this team");
            case TOO_MANY_VICES -> error(exchange, 409, "too_many_vices", "two vice leaders at most");
            case TOO_MANY_INVITED -> error(exchange, 409, "too_many_invited",
                    "as many invitations out as there may be: wait for answers");
            case TOO_SOON -> error(exchange, 429, "too_soon", "twenty invitations an hour at most");
            case RENAMED_RECENTLY -> error(exchange, 429, "too_soon", "a team's name changes once in 30 days");
            case NO_SUCH_TEAM -> error(exchange, 404, "no_such_team", "no team with that id");
            case ALREADY -> error(exchange, 409, "already", "one application a team, a declined one until it lapses");
            case TOO_MANY_APPLIED -> error(exchange, 409, "too_many_applied", "five applications out at once");
            case NO_APPLICATION -> error(exchange, 404, "no_application", "no such application, or it has lapsed");
            case APPLIED_TOO_SOON -> error(exchange, 429, "too_soon", "twenty applications an hour at most");
        }
    }

    private static final String[] TOURNAMENT_STATES = {"registration", "seeded", "running", "finished", "cancelled"};
    private static final String[] MATCH_STATES = {"pending", "ready", "done"};

    /**
     * {@code GET /v1/tournaments}, {@code GET /v1/tournaments/{id}}, {@code POST} and {@code DELETE
     * /v1/tournaments/{id}/entries}: registering and withdrawing, and {@code GET
     * /v1/tournaments/{id}/match}: the player's grant for their match (04 §6).
     */
    private void tournaments(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath().substring("/v1/tournaments".length());
        String method = exchange.getRequestMethod();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d{1,18})(/entries|/match)?").matcher(path);
        boolean list = path.isEmpty() || path.equals("/");
        if (!list && !m.matches()) {
            error(exchange, 404, "no_such_route", "use /v1/tournaments[/{id}[/entries|/match]]");
            return;
        }
        String allowed = list || m.group(2) == null || m.group(2).equals("/match") ? "GET" : "POST, DELETE";
        if (!java.util.List.of(allowed.split(", ")).contains(method)) {
            methodNotAllowed(exchange, allowed);
            return;
        }
        if (!list && "/match".equals(m.group(2))) {
            tournamentMatch(exchange, Long.parseLong(m.group(1)));
            return;
        }
        if (list) {
            java.util.List<com.fasterxml.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
            for (TournamentService.View v : tournaments.open()) {
                out.add(tournamentView(v));
            }
            respond(exchange, 200, Json.MAPPER.writeValueAsString(java.util.Map.of("tournaments", out)));
            return;
        }
        long id = Long.parseLong(m.group(1));
        TournamentService.Answer answer;
        if (m.group(2) == null) {
            answer = tournaments.get(id);
        } else {
            String token = bearerToken(exchange);
            if (token == null) {
                error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
                return;
            }
            answer = "POST".equals(method) ? tournaments.register(token, id) : tournaments.withdraw(token, id);
        }
        switch (answer.result()) {
            case OK -> respond(exchange, 200, Json.MAPPER.writeValueAsString(tournamentView(answer.view())));
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            case NO_SUCH_TOURNAMENT -> error(exchange, 404, "no_such_tournament", "no such tournament");
            case CLOSED -> error(exchange, 409, "closed", "registration is over");
            case TOO_FEW_RATED -> error(exchange, 409, "too_few_rated", "ten rated matches in its mode enter a tournament");
            case FULL -> error(exchange, 409, "full", "no entries left");
            case ALREADY -> error(exchange, 409, "already", "registered already");
            case NOT_REGISTERED -> error(exchange, 409, "not_registered", "not registered, or registration is over");
            case IN_PARTY -> error(exchange, 409, "in_party", "the party's leader registers the team");
            case PARTY_TOO_SMALL -> error(exchange, 400, "party_too_small", "a team enters as a party of three");
            case NOT_ONE_TEAM -> error(exchange, 409, "not_one_team", "every member of the party must be in your team");
            case NOT_ALLOWED -> error(exchange, 403, "not_allowed", "the team's leader or a vice leader acts for it");
        }
    }

    private void tournamentMatch(HttpExchange exchange, long id) throws IOException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        TournamentService.MatchGrant answer = tournaments.match(token, id);
        switch (answer.result()) {
            case OK -> respond(exchange, 200, answer.grant());
            case NO_SESSION -> error(exchange, 401, "invalid_session", "log in again");
            default -> error(exchange, 404, "no_match", "no match waiting for you in it");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode tournamentView(TournamentService.View v) {
        var t = v.tournament();
        var json = Json.MAPPER.createObjectNode().put("id", t.id()).put("name", t.name())
                .put("mode", v.ofTeams() ? "teams" : "duel")
                .put("format", t.format() == com.backend.persistence.TournamentRepository.ROUND_ROBIN
                        ? "round_robin" : "elimination")
                .put("state", TOURNAMENT_STATES[t.state()]).put("maxEntries", t.maxEntries())
                .put("registrationEnds", t.registrationEnds().toString()).put("startsAt", t.startsAt().toString())
                .put("roundMinutes", t.roundMinutes()).put("currentRound", t.currentRound());
        json.putArray("prizes").add(t.prize1()).add(t.prize2()).add(t.prize3());
        var entries = json.putArray("entries");
        for (var e : v.entries()) {
            entries.addObject().put("playerId", e.playerId()).put("name", e.name()).put("seed", e.seed());
        }
        for (var e : v.teams()) {
            var entry = entries.addObject().put("teamId", e.teamId()).put("name", e.name()).put("seed", e.seed());
            var roster = entry.putArray("roster");
            for (var r : e.roster()) {
                roster.addObject().put("playerId", r.playerId()).put("name", r.name());
            }
        }
        // A teams' bracket holds teams' ids where a duel's holds players' (D-44), and says so.
        String a = v.ofTeams() ? "teamA" : "playerA";
        String b = v.ofTeams() ? "teamB" : "playerB";
        String won = v.ofTeams() ? "winnerTeam" : "winner";
        var matches = json.putArray("matches");
        for (var match : v.matches()) {
            var o = matches.addObject().put("round", match.round()).put("slot", match.slot())
                    .put("state", MATCH_STATES[match.state()]);
            if (match.playerA() != null) {
                o.put(a, match.playerA());
            }
            if (match.playerB() != null) {
                o.put(b, match.playerB());
            }
            if (match.winner() != null) {
                o.put(won, match.winner());
            }
        }
        if (t.format() == com.backend.persistence.TournamentRepository.ROUND_ROBIN) {     // 04 §6, by place
            var standings = json.putArray("standings");
            for (var s : v.standings()) {
                standings.addObject().put(v.ofTeams() ? "teamId" : "playerId", s.entry()).put("name", s.name())
                        .put("points", s.points()).put("wins", s.wins()).put("draws", s.draws()).put("losses", s.losses());
            }
        }
        return json;
    }

    private static LoadoutResponse loadoutView(EquipmentService.Loadout loadout) {
        java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < Items.SLOTS; slot++) {
            slots.put(Items.slotName(slot), loadout.slots().get(slot));
        }
        java.util.Map<String, Integer> bonus = new java.util.LinkedHashMap<>();
        for (int stat = 0; stat < com.backend.sim.Stat.COUNT; stat++) {
            if (loadout.bonus()[stat] != 0) {
                bonus.put(Items.statName(stat), (int) loadout.bonus()[stat]);
            }
        }
        return new LoadoutResponse(slots, bonus);
    }

    private void leaderboard(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath()
                .substring("/v1/leaderboards".length());
        String[] parts = path.split("/");
        // "/alltime" splits to ["", "alltime"]; "/alltime/me" to ["", "alltime", "me"].
        if (parts.length < 2 || parts.length > 3
                || (parts.length == 3 && !"me".equals(parts[2]))) {
            error(exchange, 404, "no_such_route", "use /v1/leaderboards/{board}[/me]");
            return;
        }
        Board board = Board.byApiName(parts[1]);
        com.backend.persistence.RatingBoards.Board rated = com.backend.persistence.RatingBoards.Board.byApiName(parts[1]);
        boolean teams = TEAMS_BOARD.equals(parts[1]);
        if (board == null && rated == null && !teams) {
            error(exchange, 404, "unknown_board", "no board called " + parts[1]);
            return;
        }
        // A season (04 §7): a past one's final places, or the one being played; the rating boards' only.
        String seasonParam = queryParam(exchange, "season");
        Integer season = null;
        if (seasonParam != null) {
            try {
                season = Integer.parseInt(seasonParam);
            } catch (NumberFormatException e) {
                error(exchange, 400, "invalid_season", "season is a season's number");
                return;
            }
            if (rated == null && !teams) {
                error(exchange, 404, "no_such_season", "seasons are the rating boards'");
                return;
            }
        }
        if (teams) {
            if (parts.length == 3) {
                myTeam(exchange, season);
            } else {
                topOfTeams(exchange, season);
            }
            return;
        }
        if (board == null) {
            if (parts.length == 3) {
                myRating(exchange, rated, season);
            } else {
                topOfRatingBoard(exchange, rated, season);
            }
            return;
        }
        Instant now = Instant.now();
        if (parts.length == 3) {
            myRank(exchange, board, now);
        } else {
            topOfBoard(exchange, board, now);
        }
    }

    private void topOfBoard(HttpExchange exchange, Board board, Instant now) throws IOException {
        // Clamped rather than rejected: a caller asking for too many has made a judgement
        // about page size, not an error, and the cap is ours to enforce quietly.
        int limit = clamp(queryParam(exchange, "limit"), DEFAULT_BOARD_LIMIT, MAX_BOARD_LIMIT);
        List<Entry> entries = await(leaderboards.top(board, limit, now));
        respond(exchange, 200, Json.MAPPER.writeValueAsString(
                new LeaderboardResponse(board.apiName(), toApi(entries))));
    }

    private void myRank(HttpExchange exchange, Board board, Instant now) throws IOException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        long playerId = auth.playerIdOf(token);
        if (playerId < 0) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        Neighbourhood me = await(leaderboards.around(board, playerId, AROUND_RADIUS, now));
        if (me == null) {
            // Not an error: a new player, or one who has not played today. The client shows
            // "play a match to be ranked", which it cannot do if this is a 200 with nulls.
            error(exchange, 404, "not_ranked", "no score on this board yet");
            return;
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(
                new MyRankResponse(board.apiName(), me.rank() + 1, me.score(),
                        toApi(me.window()))));
    }

    /**
     * A rating board's top (04 §7, Q-40): kept thirty seconds by {@code RatingLeaderboards}; a past
     * season's final places when {@code season} names one.
     */
    private void topOfRatingBoard(HttpExchange exchange, com.backend.persistence.RatingBoards.Board board, Integer season)
            throws IOException, SQLException {
        int limit = clamp(queryParam(exchange, "limit"), DEFAULT_BOARD_LIMIT, MAX_BOARD_LIMIT);
        List<com.backend.persistence.RatingBoards.Row> rows = season == null
                ? ratingBoards.top(board, limit) : ratingBoards.top(season, board, limit);
        if (rows == null) {
            error(exchange, 404, "no_such_season", "no season " + season);
            return;
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(new LeaderboardResponse(board.apiName, ratingRows(rows))));
    }

    /**
     * {@code GET /v1/seasons} (04 §7): the season being played, its number and when it ends, and the
     * seasons before it, newest first.
     */
    private void seasons(HttpExchange exchange) throws IOException, SQLException {
        var json = Json.MAPPER.createObjectNode();
        var past = Json.MAPPER.createArrayNode();
        json.putNull("current");
        for (var s : ratingBoards.seasons(SEASONS_LISTED + 1)) {
            var row = Json.MAPPER.createObjectNode().put("id", s.id()).put("startsAt", s.startsAt().toString())
                    .put("endsAt", s.endsAt().toString());
            if (s.placedAt() == null) {
                json.set("current", row);
            } else {
                past.add(row);
            }
        }
        json.set("past", past);
        respond(exchange, 200, Json.MAPPER.writeValueAsString(json));
    }

    /**
     * {@code GET /v1/achievements} (04 §8, D-64): every achievement, its stat, threshold and gems, the
     * player's progress and whether it is reached.
     */
    private void achievements(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        List<com.backend.platform.AchievementService.Progress> all = achievements.of(token);
        if (all == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        var json = Json.MAPPER.createObjectNode();
        var list = json.putArray("achievements");
        for (var p : all) {
            list.addObject().put("id", p.achievement().id()).put("stat", p.achievement().stat().apiName)
                    .put("threshold", p.achievement().threshold()).put("gems", p.achievement().gems())
                    .put("progress", p.progress()).put("reached", p.reached());
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(json));
    }

    /** The board of teams' name on the wire (04 §7, D-65). */
    private static final String TEAMS_BOARD = "teams";

    /** The board of teams' top, or a past season's; kept thirty seconds, as a player board's. */
    private void topOfTeams(HttpExchange exchange, Integer season) throws IOException, SQLException {
        int limit = clamp(queryParam(exchange, "limit"), DEFAULT_BOARD_LIMIT, MAX_BOARD_LIMIT);
        List<com.backend.persistence.TeamBoards.Row> rows = season == null
                ? ratingBoards.teamTop(limit) : ratingBoards.teamTop(season, limit);
        if (rows == null) {
            error(exchange, 404, "no_such_season", "no season " + season);
            return;
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(new TeamBoardResponse(TEAMS_BOARD, teamRows(rows))));
    }

    /** The caller's team's place, read fresh; in a past season, the team the caller was paid for. */
    private void myTeam(HttpExchange exchange, Integer season) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        long playerId = auth.playerIdOf(token);
        if (playerId < 0) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        com.backend.persistence.TeamBoards.Place mine = season == null
                ? ratingBoards.teamPlace(playerId, AROUND_RADIUS) : ratingBoards.teamPlace(season, playerId, AROUND_RADIUS);
        if (mine == com.backend.platform.RatingLeaderboards.NO_TEAM_SEASON) {
            error(exchange, 404, "no_such_season", "no season " + season);
            return;
        }
        if (mine == com.backend.persistence.TeamBoards.NO_TEAM) {
            error(exchange, 404, "not_in_team", "join or make a team to be on this board");
            return;
        }
        if (mine == null) {
            error(exchange, 404, "not_ranked", "a team is listed after " + com.backend.persistence.RatingBoards.MIN_RATED
                    + " rated team matches");
            return;
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(
                new MyTeamRankResponse(TEAMS_BOARD, mine.rank(), mine.rating(), teamRows(mine.window()))));
    }

    private static List<TeamEntry> teamRows(List<com.backend.persistence.TeamBoards.Row> rows) {
        List<TeamEntry> out = new ArrayList<>(rows.size());
        for (var r : rows) {
            out.add(new TeamEntry(r.rank(), r.teamId(), r.name(), r.rating()));
        }
        return out;
    }

    /**
     * {@code GET /v1/goals} (04 §8, D-66): today's date (UTC), when it ends, its three goals, each its
     * kind, target, coins, progress and whether met, and the set's gems and whether all three are.
     */
    private void goals(HttpExchange exchange) throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        com.backend.platform.GoalService.Today today = goals.of(token);
        if (today == null) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        var json = Json.MAPPER.createObjectNode().put("day", today.day().toString())
                .put("resetsAt", today.resetsAt().toString());
        var list = json.putArray("goals");
        for (var g : today.goals()) {
            list.addObject().put("id", g.goal().id()).put("kind", g.goal().kind().apiName)
                    .put("target", g.goal().target()).put("coins", g.goal().coins())
                    .put("progress", g.progress()).put("done", g.done());
        }
        json.put("setGems", com.backend.persistence.DailyGoals.SET_GEMS).put("setDone", today.setDone());
        respond(exchange, 200, Json.MAPPER.writeValueAsString(json));
    }

    /** The past seasons {@code GET /v1/seasons} lists: two years of them. */
    private static final int SEASONS_LISTED = 12;

    /**
     * A player's own place on a rating board, read fresh; 404 {@code not_ranked} until listed. In a
     * past season, their final place.
     */
    private void myRating(HttpExchange exchange, com.backend.persistence.RatingBoards.Board board, Integer season)
            throws IOException, SQLException {
        String token = bearerToken(exchange);
        if (token == null) {
            error(exchange, 401, "no_token", "send Authorization: Bearer <token>");
            return;
        }
        long playerId = auth.playerIdOf(token);
        if (playerId < 0) {
            error(exchange, 401, "invalid_session", "log in again");
            return;
        }
        com.backend.persistence.RatingBoards.Place me = season == null
                ? ratingBoards.place(board, playerId, AROUND_RADIUS) : ratingBoards.place(season, board, playerId, AROUND_RADIUS);
        if (me == com.backend.platform.RatingLeaderboards.NO_SEASON) {
            error(exchange, 404, "no_such_season", "no season " + season);
            return;
        }
        if (me == null) {
            error(exchange, 404, "not_ranked", "listed after " + com.backend.persistence.RatingBoards.MIN_RATED
                    + " rated matches");
            return;
        }
        respond(exchange, 200, Json.MAPPER.writeValueAsString(
                new MyRankResponse(board.apiName, me.rank(), me.rating(), ratingRows(me.window()))));
    }

    private static List<LeaderboardEntry> ratingRows(List<com.backend.persistence.RatingBoards.Row> rows) {
        List<LeaderboardEntry> out = new ArrayList<>(rows.size());
        for (var r : rows) {
            out.add(new LeaderboardEntry(r.rank(), r.playerId(), r.name(), r.rating()));
        }
        return out;
    }

    private static List<LeaderboardEntry> toApi(List<Entry> entries) {
        List<LeaderboardEntry> rows = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            rows.add(new LeaderboardEntry(e.rank() + 1, e.playerId(), e.name(), e.score()));
        }
        return rows;
    }

    private static int clamp(String raw, int fallback, int max) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.clamp(Integer.parseInt(raw), 1, max);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Answers a registration or login that is turned away before it costs anything, and
     * says why: "busy" or "throttled", or null when it may go ahead.
     *
     * First 503 {@code busy} when the hasher's line is full, then 429 when this address or
     * account has had its share. In that order, so that an attempt turned away for load is
     * never counted: a player retrying through an overload would otherwise use up the
     * account's attempts and be locked out for a quarter hour after the overload ended.
     * Both come after the body is parsed and before any other check on it, so a refusal costs
     * the caller the same whatever the request contained and says nothing about the account.
     *
     * The busy answer's {@code Retry-After} is spread over one to five seconds. A fixed value
     * would bring every turned-away client back in the same second, which is the burst that
     * filled the line.
     */
    private String refusal(HttpExchange exchange, PasswordHasher.Admission place, String account)
            throws IOException {
        if (place == null) {
            exchange.getResponseHeaders().set("Retry-After",
                    Integer.toString(1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(5)));
            error(exchange, 503, "busy", "too many logins at once; try again shortly");
            return "busy";
        }
        InetAddress who = clientAddress(exchange.getRemoteAddress().getAddress(),
                exchange.getRequestHeaders().get("X-Forwarded-For"));
        LoginThrottle.Verdict verdict = throttle.attempt(LoginThrottle.addressKey(who), account);
        if (verdict.allowed()) {
            return null;
        }
        exchange.getResponseHeaders().set("Retry-After",
                Integer.toString(verdict.retryAfterSeconds()));
        error(exchange, 429, "too_many_attempts",
                "try again in " + verdict.retryAfterSeconds() + " s");
        return "throttled";
    }

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /**
     * Starts with a hex digit or a colon and contains a colon. The JDK parses such a string
     * as an IPv6 literal or rejects it, and never looks it up in DNS — which matters, because
     * this string came from a request, and a DNS lookup per login is a lever of its own.
     */
    private static final Pattern IPV6 = Pattern.compile("(?=.*:)[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    /**
     * The address a request is counted under.
     *
     * The TCP peer, unless the peer is on this machine. Then it is nginx, which terminates
     * the client's connection and reports the address it saw as the <em>last</em> entry of
     * {@code X-Forwarded-For} ({@code proxy_add_x_forwarded_for}). Only the last: every
     * earlier entry is whatever the client chose to send. A header from any peer that is not
     * on this machine is ignored for the same reason, and one that does not parse falls back
     * to the peer, which throttles more rather than less.
     *
     * This trusts every local process. That is the deployment the topology describes — nginx
     * on each machine, in front of that machine's processes — and a local process could
     * already do worse than lie about an address.
     */
    static InetAddress clientAddress(InetAddress peer, List<String> forwardedFor) {
        if (!peer.isLoopbackAddress() || forwardedFor == null || forwardedFor.isEmpty()) {
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

    /**
     * Waits for a store call on this request's virtual thread.
     *
     * Blocking is the point: a virtual thread parked on a socket costs a few hundred bytes,
     * and the alternative here would be threading callbacks through a synchronous handler
     * for no gain.
     */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(STORE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new StoreUnavailableException(e);
        }
    }

    // ---- plumbing ------------------------------------------------------------------------

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException, SQLException;
    }

    private void route(HttpExchange exchange, String method, Handler handler) throws IOException {
        guard(exchange, ex -> {
            if (method.equals(ex.getRequestMethod())) {
                handler.handle(ex);
            } else {
                methodNotAllowed(ex, method);
            }
        });
    }

    /**
     * A POST to a route nginx limits, which is matched exactly here: the server matches a context by
     * prefix, so {@code /v1/sessions/} would otherwise reach the handler past nginx's limit (S-18).
     */
    private void exact(HttpExchange exchange, String method, String path, Handler handler) throws IOException {
        route(exchange, method, ex -> {
            if (ex.getRequestURI().getPath().equals(path)) {
                handler.handle(ex);
            } else {
                error(ex, 404, "no_such_route", "use " + path);
            }
        });
    }

    /**
     * Inside {@link #guard}, so it is counted with every other response, and with the header
     * RFC 9110 §15.5.6 requires. It was answered outside both.
     */
    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        error(exchange, 405, "method_not_allowed", "use " + allowed);
    }

    /** Turns anything thrown into a 500 rather than a dropped connection with no explanation. */
    private void guard(HttpExchange exchange, Handler handler) throws IOException {
        long started = System.nanoTime();
        try {
            guarded(exchange, handler);
        } finally {
            int status = exchange.getResponseCode();
            if (status > 0) {
                responses.increment(Integer.toString(status));
            }
            // By the context, /v1/party, never the path, which a client chooses: one series each.
            latency.observe(exchange.getHttpContext().getPath(), (System.nanoTime() - started) / 1e9);
        }
    }

    private void guarded(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.handle(exchange);
        } catch (SQLException e) {
            log.error("database error serving {}: {}", exchange.getRequestURI(), e.toString());
            error(exchange, 503, "storage_unavailable", "try again shortly");
        } catch (BodyTooLarge e) {
            error(exchange, 413, "body_too_large", "at most " + MAX_BODY_BYTES + " bytes");
        } catch (StoreUnavailableException e) {
            // 503 for the same reason a full system is: nothing is wrong with the request,
            // so the client should come back rather than report a fault.
            log.error("store error serving {}: {}", exchange.getRequestURI(), e.toString());
            error(exchange, 503, "storage_unavailable", "try again shortly");
        } catch (RuntimeException e) {
            if (storeUnavailable(e)) {
                log.error("store error serving {}: {}", exchange.getRequestURI(), e.toString());
                error(exchange, 503, "storage_unavailable", "try again shortly");
                return;
            }
            log.error("unhandled error serving {}", exchange.getRequestURI(), e);
            error(exchange, 500, "internal", "unexpected failure");
        }
    }

    /**
     * A store failing on a leased connection, whose calls throw the client's own exceptions
     * (O-12): the store not reached, too slow, the client closed, or a write refused for now, by a
     * primary demoted under the request or too few replicas, alone or as the reason a transaction
     * was discarded. Any other refusal is a request the store could not run: a bug.
     */
    static boolean storeUnavailable(RuntimeException e) {
        if (e instanceof JRedisServerException refused) {
            String reply = String.valueOf(refused.getMessage());
            return reply.startsWith("READONLY") || reply.startsWith("NOREPLICAS")
                    || reply.startsWith("EXECABORT") && (reply.contains("READONLY") || reply.contains("NOREPLICAS"));
        }
        return e instanceof JRedisConnectionException || e instanceof JRedisTimeoutException
                || e instanceof JRedisClosedException;
    }

    /** Thrown by {@link #read}, answered 413 by {@link #guarded}. */
    private static final class BodyTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super(null, null, false, false);
        }
    }

    private static <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            // One byte more than allowed, to tell a body that fits from one that does not. Cut
            // at the limit, the prefix was parsed, and Jackson stops at the end of the first
            // value: an object followed by padding was accepted.
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new BodyTooLarge();
            }
            if (body.length == 0) {
                return null;
            }
            return Json.MAPPER.readValue(body, type);
        } catch (com.fasterxml.jackson.core.JacksonException malformed) {
            return null;
        }
    }

    private static String bearerToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void error(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        respond(exchange, status, Json.MAPPER.writeValueAsString(new ErrorResponse(code, message)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        // A 204 must not declare a body length at all, or a strict client waits for one.
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
