package com.backend.platform.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.MatchMode;
import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.SessionStore;
import com.backend.handoff.TicketStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.BoostRepository;
import com.backend.persistence.Database;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.EquipmentRepository;
import com.backend.persistence.TeamRepository;
import com.backend.persistence.TournamentRepository;
import com.backend.platform.AuthService;
import com.backend.platform.BoostService;
import com.backend.platform.Catalogue;
import com.backend.platform.EquipmentService;
import com.backend.platform.Items;
import com.backend.platform.JoinService;
import com.backend.platform.LoginThrottle;
import com.backend.platform.MatchQueue;
import com.backend.platform.PasswordHasher;
import com.backend.platform.QueueService;
import com.backend.platform.ShopService;
import com.backend.platform.TeamService;
import com.backend.platform.TournamentService;

import com.fasterxml.jackson.databind.JsonNode;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The platform API over real HTTP, against a real MySQL and a real j-redis.
 *
 * The client is the JDK's, not a hand-rolled socket: what is being tested here is whether an
 * ordinary HTTP client gets a usable answer, including the status codes it has to branch on.
 */
class PlatformHttpServerTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static JRedisEmbedded store;
    private static JRedisClient jredis;
    private static JoinService joins;
    private static ArenaDirectory directory;
    private static LeaderboardStore leaderboards;
    private static AuthService auth;
    private static PlatformHttpServer server;
    private static HttpClient http;
    private static String base;
    private static ShopService shop;
    private static EconomyRepository economy;

    /** The shop's clock, moved by the tests that need an offer to end. */
    private static final class SettableClock extends java.time.Clock {
        volatile Instant now;

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final SettableClock clock = new SettableClock();

    private static final String CATALOGUE = """
            [ {"sku":"hat","itemId":"cosmetic_hat","price":100},
              {"sku":"crown","itemId":"cosmetic_crown","price":300,"requiresLevel":5},
              {"sku":"festival_hat","itemId":"cosmetic_hat","price":60,
               "availableFrom":"2026-10-01T00:00:00Z","availableTo":"2026-11-01T00:00:00Z"},
              {"sku":"cape","itemId":"cosmetic_cape","price":10,"availableFrom":"2027-01-01T00:00:00Z"},
              {"sku":"gem_boost","itemId":"boost_xp","price":20,"currency":"gems"} ]
            """;

    /** Equipment for the tests: two barrels and a core that all give bullet damage, to reach the cap. */
    private static final String ITEMS = """
            [ {"id":"barrel_steel","type":"EQUIPMENT","slot":"barrel",
               "modifiers":[{"stat":"bullet_damage","percent":8}]},
              {"id":"barrel_big","type":"EQUIPMENT","slot":"barrel",
               "modifiers":[{"stat":"bullet_damage","percent":20}]},
              {"id":"core_hot","type":"EQUIPMENT","slot":"core",
               "modifiers":[{"stat":"bullet_damage","percent":10},{"stat":"reload","percent":8}]},
              {"id":"boost_xp","type":"BOOST","kind":"xp","percent":100,"minutes":60},
              {"id":"boost_xp_half","type":"BOOST","kind":"xp","percent":50,"minutes":30},
              {"id":"skin_test","type":"SKIN","skin":7} ]
            """;

    /** Packs for the tests: two, as 04 §8's first two. */
    private static final String PACKS = """
            [ {"productId":"gems_80","gems":80,"priceCents":99},
              {"productId":"gems_500","gems":500,"priceCents":499} ]
            """;

    private static com.backend.platform.PaymentService payments;
    private static com.backend.platform.PassService passes;
    private static EquipmentService equipment;
    private static BoostService boosts;
    private static TeamService teams;
    private static TournamentService tournaments;

    @BeforeAll
    static void setUp() throws Exception {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        store = JRedisEmbedded.start();
        jredis = store.newClient();

        AccountRepository accounts = new AccountRepository(db.dataSource());
        auth = new AuthService(accounts, new PasswordHasher(), new SessionStore(jredis));
        directory = new ArenaDirectory(jredis);
        equipment = new EquipmentService(auth, new EquipmentRepository(db.dataSource()), Items.read(
                new java.io.ByteArrayInputStream(ITEMS.getBytes(StandardCharsets.UTF_8))));
        joins = new JoinService(auth, accounts, directory, new TicketStore(jredis), equipment);

        leaderboards = new LeaderboardStore(jredis);
        economy = new EconomyRepository(db.dataSource());
        shop = new ShopService(auth, accounts, economy, Catalogue.read(
                new java.io.ByteArrayInputStream(CATALOGUE.getBytes(StandardCharsets.UTF_8))), clock);
        boosts = new BoostService(auth, new BoostRepository(db.dataSource()), Items.read(
                new java.io.ByteArrayInputStream(ITEMS.getBytes(StandardCharsets.UTF_8))), clock);
        teams = new TeamService(auth, new TeamRepository(db.dataSource(), 30), new com.backend.handoff.LobbyPush(jredis), clock,
                new com.backend.platform.AskThrottle(jredis, () -> clock.millis()));
        tournaments = new TournamentService(auth, new TournamentRepository(db.dataSource()),
                new com.backend.handoff.TournamentGrants(jredis),
                new com.backend.platform.Parties(jredis, com.backend.handoff.Ulid::generate),
                new TeamRepository(db.dataSource(), 30), clock);
        payments = new com.backend.platform.PaymentService(auth, new com.backend.persistence.PaymentRepository(db.dataSource()),
                com.backend.platform.Packs.read(new java.io.ByteArrayInputStream(PACKS.getBytes(StandardCharsets.UTF_8))),
                com.backend.platform.PaymentService.SIMULATED, clock);
        passes = new com.backend.platform.PassService(auth, new com.backend.persistence.SeasonPassRepository(db.dataSource()), clock);
        server = serverWith(payments, accounts);
        int port = server.start();
        base = "http://127.0.0.1:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** The server the tests share, or one like it with other payments (D-68). */
    private static PlatformHttpServer serverWith(com.backend.platform.PaymentService payments, AccountRepository accounts)
            throws java.io.IOException {
        // The throttle's windows on the tests' clock, reset before each: a test that ran across a
        // minute's end counted its attempts in two windows (T-40).
        return new PlatformHttpServer(auth, joins, leaderboards, new LoginThrottle(jredis, () -> clock.millis()),
                shop, equipment, boosts, teams, tournaments, queues(auth, jredis, accounts), parties(auth, jredis, accounts),
                new com.backend.platform.FriendService(auth, accounts, new com.backend.persistence.FriendRepository(
                        db.dataSource(), 100, 100), new com.backend.handoff.LobbyPush(jredis), clock,
                        new com.backend.platform.AskThrottle(jredis, () -> clock.millis())),
                new com.backend.platform.InboxService(auth, new com.backend.persistence.InboxRepository(db.dataSource()), clock),
                new com.backend.platform.RatingLeaderboards(new com.backend.persistence.RatingBoards(db.dataSource()),
                        new com.backend.persistence.TeamBoards(db.dataSource()),
                        new com.backend.persistence.SeasonRepository(db.dataSource()), clock, 100),
                new com.backend.platform.AchievementService(auth, new com.backend.persistence.AchievementRepository(db.dataSource())),
                new com.backend.platform.GoalService(auth, new com.backend.persistence.DailyGoalRepository(db.dataSource()), clock),
                payments, passes, "127.0.0.1", 0);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
        if (store != null) {
            store.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
        jredis.sync().send("FLUSHALL");
        clock.now = Instant.parse("2026-10-15T12:00:00Z");
    }

    @Test
    @DisplayName("a store that is down is a 503 to come back later, not a 500 fault")
    void storeDownIsServiceUnavailable() throws Exception {
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        JRedisClient down = JRedisClient.builder().address("127.0.0.1", deadPort)
                .clientName("down").build().start();
        AccountRepository accounts = new AccountRepository(db.dataSource());
        AuthService auth = new AuthService(accounts, new PasswordHasher(), new SessionStore(down));
        JoinService joins = new JoinService(auth, accounts, new ArenaDirectory(down),
                new TicketStore(down), equipment);
        PlatformHttpServer downServer = new PlatformHttpServer(auth, joins,
                new LeaderboardStore(down), new LoginThrottle(down), shop, equipment, boosts, teams, tournaments,
                queues(auth, down, accounts), parties(auth, down, accounts),
                new com.backend.platform.FriendService(auth, accounts, new com.backend.persistence.FriendRepository(
                        db.dataSource(), 100, 100), new com.backend.handoff.LobbyPush(down), clock,
                        new com.backend.platform.AskThrottle(down)),
                new com.backend.platform.InboxService(auth, new com.backend.persistence.InboxRepository(db.dataSource()), clock),
                new com.backend.platform.RatingLeaderboards(new com.backend.persistence.RatingBoards(db.dataSource()),
                        new com.backend.persistence.TeamBoards(db.dataSource()),
                        new com.backend.persistence.SeasonRepository(db.dataSource()), clock, 100),
                new com.backend.platform.AchievementService(auth, new com.backend.persistence.AchievementRepository(db.dataSource())),
                new com.backend.platform.GoalService(auth, new com.backend.persistence.DailyGoalRepository(db.dataSource()), clock),
                payments, passes, "127.0.0.1", 0);
        String downBase = "http://127.0.0.1:" + downServer.start();
        try {
            register("ada");                       // MySQL is shared and healthy; only the store is gone
            java.util.function.Function<HttpRequest.Builder, HttpResponse<String>> call = b -> {
                try {
                    return send(b.header("Content-Type", "application/json").build());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            // Every one of these was a 500 "internal": AuthService and JoinService wrapped
            // the store's failure in an IllegalStateException the handler could not tell
            // from a bug.
            HttpResponse<String> login = call.apply(HttpRequest.newBuilder(
                    URI.create(downBase + "/v1/sessions")).POST(HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}")));
            // A token as minted, 43 characters: any other is refused before the store is asked (S-20).
            String asMinted = "x".repeat(43);
            HttpResponse<String> join = call.apply(HttpRequest.newBuilder(
                    URI.create(downBase + "/v1/match-requests")).header("Authorization", "Bearer " + asMinted)
                    .POST(HttpRequest.BodyPublishers.noBody()));
            HttpResponse<String> logout = call.apply(HttpRequest.newBuilder(
                    URI.create(downBase + "/v1/sessions")).header("Authorization", "Bearer " + asMinted)
                    .DELETE());
            for (HttpResponse<String> r : java.util.List.of(login, join, logout)) {
                assertThat(r.statusCode()).as(r.request().method() + " " + r.uri().getPath())
                        .isEqualTo(503);
                assertThat(body(r).get("code").asText()).isEqualTo("storage_unavailable");
            }
        } finally {
            downServer.close();
            down.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a store failing on a leased connection is 503, as on a waited call; a refusal that is a bug stays 500 (O-12)")
    void aLeasedStoreFailureIs503() throws Exception {
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisServerException(
                "READONLY You can't write against a read only replica."))).as("a primary demoted under the request").isTrue();
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisServerException(
                "NOREPLICAS Not enough good replicas to write."))).isTrue();
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisServerException(
                "EXECABORT Transaction discarded because of previous errors. (first rejected command: READONLY You can't"
                        + " write against a read only replica.)"))).as("a watched change discarded by a demotion").isTrue();
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisServerException(
                "EXECABORT Transaction discarded because of previous errors. (first rejected command: ERR wrong number of"
                        + " arguments for 'set' command)"))).as("discarded by a bug").isFalse();
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisTimeoutException("no answer"))).isTrue();
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisClosedException("client closed"))).isTrue();
        assertThat(PlatformHttpServer.storeUnavailable(new com.jredis.client.JRedisServerException(
                "WRONGTYPE Operation against a key holding the wrong kind of value"))).as("a request it could not run: a bug")
                .isFalse();

        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        JRedisClient down = JRedisClient.builder().address("127.0.0.1", deadPort).clientName("down").build().start();
        AccountRepository accounts = new AccountRepository(db.dataSource());
        // Sessions and pushes healthy; the parties' store, reached by leased connections, gone.
        com.backend.platform.PartyService partiesDown = new com.backend.platform.PartyService(auth, accounts,
                new com.backend.platform.Parties(down, com.backend.handoff.Ulid::generate),
                new com.backend.handoff.LobbyPush(jredis), queues(auth, jredis, accounts),
                new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100),
                new com.backend.platform.AskThrottle(jredis));
        PlatformHttpServer partly = new PlatformHttpServer(auth, joins, leaderboards,
                new LoginThrottle(jredis, () -> clock.millis()), shop, equipment, boosts, teams, tournaments,
                queues(auth, jredis, accounts), partiesDown,
                new com.backend.platform.FriendService(auth, accounts, new com.backend.persistence.FriendRepository(
                        db.dataSource(), 100, 100), new com.backend.handoff.LobbyPush(jredis), clock,
                        new com.backend.platform.AskThrottle(jredis, () -> clock.millis())),
                new com.backend.platform.InboxService(auth, new com.backend.persistence.InboxRepository(db.dataSource()), clock),
                new com.backend.platform.RatingLeaderboards(new com.backend.persistence.RatingBoards(db.dataSource()),
                        new com.backend.persistence.TeamBoards(db.dataSource()),
                        new com.backend.persistence.SeasonRepository(db.dataSource()), clock, 100),
                new com.backend.platform.AchievementService(auth, new com.backend.persistence.AchievementRepository(db.dataSource())),
                new com.backend.platform.GoalService(auth, new com.backend.persistence.DailyGoalRepository(db.dataSource()), clock),
                payments, passes, "127.0.0.1", 0);
        String partlyBase = "http://127.0.0.1:" + partly.start();
        try {
            String ada = register("lease-ada");
            long bobId = auth.playerIdOf(register("lease-bob"));
            HttpResponse<String> invited = send(HttpRequest.newBuilder(URI.create(partlyBase + "/v1/party/invite"))
                    .header("Authorization", "Bearer " + ada).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"playerId\":" + bobId + "}")).build());
            assertThat(invited.statusCode()).as(invited.body()).isEqualTo(503);
            assertThat(body(invited).get("code").asText()).isEqualTo("storage_unavailable");
        } finally {
            partly.close();
            down.close();
        }
    }

    @Test
    @DisplayName("an account gets ten logins a quarter hour, whether it exists or not")
    void loginIsThrottledPerAccount() throws Exception {
        // Not register(), which also logs in: that would be the account's first attempt.
        post("/v1/accounts", "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}", null);
        for (String who : List.of("ada", "nobody")) {
            for (int i = 0; i < 10; i++) {
                HttpResponse<String> r = postFrom("198.51.100." + i, "/v1/sessions",
                        "{\"username\":\"" + who + "\",\"password\":\"wrong-password\"}");
                assertThat(r.statusCode()).as("%s, attempt %d", who, i + 1).isEqualTo(401);
            }
            // From an address not seen before, and in capitals: the account is what is
            // counted. The same for a name nobody has, or the limit would say who exists.
            HttpResponse<String> refused = postFrom("198.51.100.99", "/v1/sessions",
                    "{\"username\":\"" + who.toUpperCase() + "\",\"password\":\"wrong-password\"}");
            assertThat(refused.statusCode()).as(who).isEqualTo(429);
            assertThat(body(refused).get("code").asText()).isEqualTo("too_many_attempts");
            assertThat(Integer.parseInt(refused.headers().firstValue("Retry-After").orElseThrow()))
                    .isBetween(1, 900);
        }
    }

    @Test
    @DisplayName("an address makes thirty guests a minute, as it registers (Q-22)")
    void guestsAreThrottledPerAddress() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(postFrom("203.0.113.21", "/v1/guests", "").statusCode()).as("guest %d", i + 1).isEqualTo(201);
        }
        HttpResponse<String> refused = postFrom("203.0.113.21", "/v1/guests", "");
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(body(refused).get("code").asText()).isEqualTo("too_many_attempts");
        assertThat(postFrom("203.0.113.22", "/v1/guests", "").statusCode()).as("the next address along").isEqualTo(201);
    }

    @Test
    @DisplayName("an address gets thirty registrations and logins a minute, and only its own")
    void registrationIsThrottledPerAddress() throws Exception {
        // Refused for a short password, which costs nothing to check. The throttle counts an
        // attempt before looking at it, so these count all the same.
        String cheap = "{\"username\":\"someone\",\"password\":\"short\"}";
        for (int i = 0; i < 30; i++) {
            assertThat(postFrom("203.0.113.9", "/v1/accounts", cheap).statusCode())
                    .as("attempt %d", i + 1).isEqualTo(400);
        }
        assertThat(postFrom("203.0.113.9", "/v1/accounts", cheap).statusCode()).isEqualTo(429);
        assertThat(postFrom("203.0.113.10", "/v1/accounts", cheap).statusCode())
                .as("the next address along").isEqualTo(400);
    }

    @Test
    @DisplayName("X-Forwarded-For is believed only from this machine, and only its last entry")
    void forwardedForIsTrustedOnlyFromThisMachine() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        InetAddress remote = InetAddress.getByName("192.0.2.50");
        assertThat(PlatformHttpServer.clientAddress(remote, List.of("198.51.100.1")))
                .as("from elsewhere the header is only the client's word").isEqualTo(remote);
        assertThat(PlatformHttpServer.clientAddress(local, List.of("10.9.9.9, 198.51.100.1")))
                .as("the last entry is the one nginx wrote")
                .isEqualTo(InetAddress.getByName("198.51.100.1"));
        assertThat(PlatformHttpServer.clientAddress(local, List.of("10.9.9.9", "198.51.100.2")))
                .isEqualTo(InetAddress.getByName("198.51.100.2"));
        assertThat(PlatformHttpServer.clientAddress(local, List.of("2001:db8::7")))
                .isEqualTo(InetAddress.getByName("2001:db8::7"));
        // Not addresses. None of them may reach DNS, and each falls back to the peer, which
        // throttles more rather than less.
        for (String junk : List.of("example.com", "999.1.1.1", "1.2.3", "fe80::1%eth0", ".:",
                "", "unknown", "::g")) {
            assertThat(PlatformHttpServer.clientAddress(local, List.of(junk)))
                    .as("'%s'", junk).isEqualTo(local);
        }
        assertThat(PlatformHttpServer.clientAddress(local, null)).isEqualTo(local);
    }

    @Test
    @DisplayName("a full line for the hasher is a 503 to come back later, and costs the account nothing")
    void fullHasherLineIsBusyAndNotCounted() throws Exception {
        com.backend.common.Metrics metrics = new com.backend.common.Metrics();
        server.registerMetrics(metrics);
        post("/v1/accounts", "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}", null);
        // Take every place in line, as a login storm would.
        List<com.backend.platform.PasswordHasher.Admission> storm = new java.util.ArrayList<>();
        for (com.backend.platform.PasswordHasher.Admission p = auth.admit(); p != null; p = auth.admit()) {
            storm.add(p);
        }
        try {
            for (int i = 0; i < 12; i++) {
                HttpResponse<String> busy = postFrom("198.51.100.1", "/v1/sessions",
                        "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}");
                assertThat(busy.statusCode()).isEqualTo(503);
                assertThat(body(busy).get("code").asText()).isEqualTo("busy");
                assertThat(Integer.parseInt(busy.headers().firstValue("Retry-After").orElseThrow()))
                        .isBetween(1, 5);
            }
            assertThat(metrics.render()).as("the line, full, as a scrape sees it")
                    .contains("backend_platform_hasher_line 168");
        } finally {
            storm.forEach(com.backend.platform.PasswordHasher.Admission::close);
        }
        // Counted where an operator will look for them: the scrape says why logins failed,
        // and that the line has emptied again.
        assertThat(metrics.render()).contains("backend_platform_logins_total{outcome=\"busy\"} 12")
                .contains("backend_platform_hasher_line 0");
        // Twelve refusals, more than the account's ten attempts, and none of them counted:
        // retrying through an overload must not lock a player out once it is over.
        HttpResponse<String> after = postFrom("198.51.100.1", "/v1/sessions",
                "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}");
        assertThat(after.statusCode()).isEqualTo(200);
    }

    // ---- helpers ---------------------------------------------------------------------

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String json, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return send(b.build());
    }

    /** As nginx would send it, from this machine: the client's address as the last entry. */
    private static HttpResponse<String> postFrom(String address, String path, String json)
            throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", address)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return send(b.build());
    }

    private static HttpResponse<String> put(String path, String json, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private static HttpResponse<String> delete(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + bearer).DELETE().build());
    }

    /** Sets how many of an item the player holds. */
    private static void hold(long playerId, String itemId, int qty) throws Exception {
        try (var c = db.dataSource().getConnection();
             var ps = c.prepareStatement("INSERT INTO inventory_item (player_id, item_id, qty) VALUES (?, ?, ?)"
                     + " ON DUPLICATE KEY UPDATE qty = VALUES(qty)")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemId);
            ps.setInt(3, qty);
            ps.executeUpdate();
        }
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return Json.MAPPER.readTree(response.body());
    }

    /** Gives the player or team named by {@code sql} the rated matches that let it enter a tournament (Q-43). */
    private static void ratedMatches(String sql, long id) throws java.sql.SQLException {
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, com.backend.persistence.RatingBoards.MIN_RATED);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static String register(String username) throws Exception {
        post("/v1/accounts", """
                {"username":"%s","displayName":"%s","password":"hunter2-hunter2"}
                """.formatted(username, username), null);
        HttpResponse<String> login = post("/v1/sessions", """
                {"username":"%s","password":"hunter2-hunter2"}
                """.formatted(username), null);
        return body(login).get("token").asText();
    }

    // ---- parties (04 §4) --------------------------------------------------------------

    /** The next push published for gateway gw-p, as {@code type data}. */
    private static QueueService queues(AuthService auth, JRedisClient store, AccountRepository accounts) {
        return new QueueService(auth, accounts, new MatchQueue(store),
                new com.backend.platform.Parties(store, com.backend.handoff.Ulid::generate),
                new com.backend.handoff.LobbyPush(store), clock::millis, equipment,
                new TeamRepository(db.dataSource(), 30), new ArenaDirectory(store), new TicketStore(store),
                new com.backend.handoff.SandboxHolds(store), new com.backend.handoff.TournamentGrants(store));
    }

    private static com.backend.platform.PartyService parties(AuthService auth, JRedisClient store,
                                                             AccountRepository accounts) {
        return new com.backend.platform.PartyService(auth, accounts,
                new com.backend.platform.Parties(store, com.backend.handoff.Ulid::generate),
                new com.backend.handoff.LobbyPush(store), queues(auth, store, accounts),
                new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100),
                new com.backend.platform.AskThrottle(store));
    }

    private static JsonNode nextPush(java.util.concurrent.LinkedBlockingQueue<String> pushed) throws Exception {
        String raw = pushed.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(raw).as("a push").isNotNull();
        return Json.MAPPER.readTree(raw);
    }

    @Test
    @Timeout(60)
    @DisplayName("a party over the API: an invitation to a player in the lobby, pushed; accepted and told to both; each refusal answered (04 §4)")
    void partiesOverTheApi() throws Exception {
        String ada = register("party-ada");
        String bob = register("party-bob");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-p",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            String inviteBob = "{\"playerId\":" + bobId + "}";
            assertThat(post("/v1/party/invite", inviteBob, ada).statusCode())
                    .as("bob is not in the lobby, and not ada's friend: answered as sent, his presence a friend's to know (S-16)")
                    .isEqualTo(200);
            jredis.sync().set("conn:" + adaId, "gw-p#x-a");
            jredis.sync().set("conn:" + bobId, "gw-p#x-b");

            HttpResponse<String> invited = post("/v1/party/invite", inviteBob, ada);
            assertThat(invited.statusCode()).isEqualTo(200);
            String partyId = body(invited).get("partyId").asText();
            assertThat(body(invited).get("version").asLong()).as("made: its version 1 (D-74)").isEqualTo(1);
            JsonNode invitation = nextPush(pushed);
            assertThat(invitation.get("to").asLong()).isEqualTo(bobId);
            assertThat(invitation.at("/msg/t").asText()).isEqualTo("evt.party.invite");
            assertThat(invitation.at("/msg/d/partyId").asText()).isEqualTo(partyId);
            assertThat(invitation.at("/msg/d/fromName").asText()).isEqualTo("party-ada");
            assertThat(body(post("/v1/party/invite", "{\"playerId\":" + adaId + "}", ada)).get("code").asText())
                    .isEqualTo("self");

            HttpResponse<String> joined = post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", bob);
            assertThat(joined.statusCode()).isEqualTo(200);
            assertThat(body(joined).get("leader").asLong()).isEqualTo(adaId);
            assertThat(body(joined).get("members")).hasSize(2);
            assertThat(body(joined).get("version").asLong()).as("the answer carries the version").isEqualTo(2);
            java.util.Set<Long> told = new java.util.HashSet<>();
            for (int i = 0; i < 2; i++) {
                JsonNode update = nextPush(pushed);
                assertThat(update.at("/msg/t").asText()).isEqualTo("evt.party.update");
                assertThat(update.at("/msg/d/members")).hasSize(2);
                assertThat(update.at("/msg/d/version").asLong()).as("and so does each push").isEqualTo(2);
                told.add(update.get("to").asLong());
            }
            assertThat(told).containsExactlyInAnyOrder(adaId, bobId);
            JsonNode viewed = body(send(HttpRequest.newBuilder(URI.create(base + "/v1/party"))
                    .header("Authorization", "Bearer " + bob).GET().build()));
            assertThat(viewed.get("partyId").asText()).isEqualTo(partyId);
            assertThat(viewed.get("version").asLong()).as("viewed, with its version").isEqualTo(2);

            assertThat(post("/v1/party/kick", "{\"playerId\":" + adaId + "}", bob).statusCode()).as("a member, not the leader")
                    .isEqualTo(404);
            assertThat(post("/v1/party/accept", "{}", bob).statusCode()).as("no partyId").isEqualTo(400);
            assertThat(send(HttpRequest.newBuilder(URI.create(base + "/v1/party/leave"))
                    .header("Authorization", "Bearer " + bob).GET().build()).statusCode()).isEqualTo(405);

            HttpResponse<String> left = post("/v1/party/leave", null, bob);
            assertThat(left.statusCode()).isEqualTo(200);
            assertThat(body(left).get("partyId").isNull()).isTrue();
            assertThat(body(left).get("was").asText()).as("in no party: after the one it ended").isEqualTo(partyId);
            assertThat(body(left).get("version").asLong()).as("at that change's version").isEqualTo(3);
            java.util.Set<Long> gone = new java.util.HashSet<>();
            for (int i = 0; i < 2; i++) {
                JsonNode update = nextPush(pushed);
                assertThat(update.at("/msg/d/members")).as("a party of one is no party").isEmpty();
                assertThat(update.at("/msg/d/was").asText()).isEqualTo(partyId);
                assertThat(update.at("/msg/d/version").asLong()).isEqualTo(3);
                gone.add(update.get("to").asLong());
            }
            assertThat(gone).containsExactlyInAnyOrder(adaId, bobId);

            String again = body(post("/v1/party/invite", inviteBob, ada)).get("partyId").asText();
            nextPush(pushed);                                   // the invitation
            assertThat(post("/v1/party/accept", "{\"partyId\":\"" + again + "\"}", bob).statusCode()).isEqualTo(200);
            nextPush(pushed);                                   // and the two updates
            nextPush(pushed);
            HttpResponse<String> kicked = post("/v1/party/kick", "{\"playerId\":" + bobId + "}", ada);
            assertThat(kicked.statusCode()).isEqualTo(200);
            assertThat(body(kicked).get("partyId").isNull()).as("the leader kicked the last member: no party").isTrue();
            assertThat(body(kicked).get("was").asText()).isEqualTo(again);
            assertThat(body(kicked).get("version").asLong()).isEqualTo(3);

            jredis.sync().set("rl:pinv:" + adaId + ":" + System.currentTimeMillis() / 3_600_000, "60");
            HttpResponse<String> sixtyFirst = post("/v1/party/invite", inviteBob, ada);
            assertThat(sixtyFirst.statusCode()).as("sixty invitations an hour, each a push to someone (S-22)").isEqualTo(429);
            assertThat(body(sixtyFirst).get("code").asText()).isEqualTo("too_soon");
        } finally {
            gateway.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a sandbox over the API: opened at once for a party by its leader, a ticket and a push each; every refusal answered (04 §4, the seventh slice)")
    void aSandboxIsOpened() throws Exception {
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150, false, 0, 4)).get();
        String ada = register("sb-ada");
        String bob = register("sb-bob");
        String cy = register("sb-cy");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-b",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            jredis.sync().set("conn:" + adaId, "gw-b#x-a");
            jredis.sync().set("conn:" + bobId, "gw-b#x-b");
            String partyId = body(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada)).get("partyId").asText();
            assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", bob).statusCode()).isEqualTo(200);
            nextPush(pushed);                                   // the invitation
            nextPush(pushed);                                   // and the two updates
            nextPush(pushed);

            assertThat(post("/v1/sandbox", null, null).statusCode()).isEqualTo(401);
            HttpResponse<String> stranger = post("/v1/sandbox", null, "not-a-session");
            assertThat(stranger.statusCode()).isEqualTo(401);
            assertThat(body(stranger).get("code").asText()).isEqualTo("invalid_session");
            hold(bobId, "barrel_big", 1);
            assertThat(put("/v1/equipment/barrel", "{\"itemId\":\"barrel_big\"}", bob).statusCode()).isEqualTo(200);
            hold(bobId, "skin_test", 1);
            assertThat(put("/v1/equipment/skin", "{\"itemId\":\"skin_test\"}", bob).statusCode()).isEqualTo(200);
            HttpResponse<String> member = post("/v1/sandbox", null, bob);
            assertThat(member.statusCode()).isEqualTo(409);
            assertThat(body(member).get("code").asText()).as("the leader opens it, as the leader queues").isEqualTo("in_party");

            HttpResponse<String> opened = post("/v1/sandbox", null, ada);
            assertThat(opened.statusCode()).isEqualTo(200);
            JsonNode grant = body(opened);
            assertThat(grant.get("arenaHost").asText()).isEqualTo("10.0.0.7");
            assertThat(grant.get("arenaPort").asInt()).isEqualTo(9001);
            assertThat(grant.get("tls").asBoolean()).isFalse();
            assertThat(grant.get("mode").asText()).isEqualTo("sandbox");
            java.util.Map<Long, String> ticketOf = new java.util.HashMap<>();
            for (int i = 0; i < 2; i++) {
                JsonNode push = nextPush(pushed);
                assertThat(push.at("/msg/t").asText()).isEqualTo("evt.match.found");
                assertThat(push.at("/msg/d/mode").asText()).isEqualTo("sandbox");
                ticketOf.put(push.get("to").asLong(), push.at("/msg/d/ticketId").asText());
            }
            assertThat(ticketOf).containsOnlyKeys(adaId, bobId);
            assertThat(ticketOf.get(adaId)).as("the caller's, answered and pushed alike").isEqualTo(grant.get("ticketId").asText());
            TicketStore tickets = new TicketStore(jredis);
            com.backend.handoff.Ticket adaTicket = tickets.claim(ticketOf.get(adaId)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            com.backend.handoff.Ticket bobTicket = tickets.claim(ticketOf.get(bobId)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(adaTicket.mode()).isEqualTo(MatchMode.SANDBOX.id);
            assertThat(bobTicket.matchUid()).as("one room for both").isEqualTo(adaTicket.matchUid());
            assertThat(bobTicket.team()).isZero();
            assertThat(bobTicket.displayName()).isEqualTo("sb-bob");
            assertThat(bobTicket.bonus()).as("what bob wears, as any match's ticket carries")
                    .isEqualTo(com.backend.handoff.Ticket.bonusOf(equipment.bonusOf(bobId))).isNotEmpty();
            assertThat(List.of(bobTicket.skin(), adaTicket.skin())).as("and his skin, hers none (D-70)").containsExactly(7, 0);

            JsonNode status = body(queue("GET", null, bob));
            assertThat(status.get("state").asText()).isEqualTo("matched");
            assertThat(status.path("grant").get("mode").asText()).as("as after a missed push").isEqualTo("sandbox");
            assertThat(status.get("waitedSeconds").asLong()).as("since it was opened, not since 1970").isBetween(0L, 60L);
            HttpResponse<String> again = post("/v1/sandbox", null, ada);
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(body(again).get("code").asText()).isEqualTo("in_match");
            assertThat(queue("POST", "{\"mode\":\"duel\"}", bob).statusCode()).as("and no queue meanwhile").isEqualTo(409);

            assertThat(queue("POST", "{\"mode\":\"sandbox\"}", cy).statusCode()).as("never queued for").isEqualTo(400);
            assertThat(queue("POST", "{\"mode\":\"duel\"}", cy).statusCode()).isEqualTo(200);
            HttpResponse<String> queued = post("/v1/sandbox", null, cy);
            assertThat(queued.statusCode()).isEqualTo(409);
            assertThat(body(queued).get("code").asText()).isEqualTo("already_queued");
            queue("DELETE", null, cy);
            String dee = register("sb-dee");
            new MatchQueue(jredis).join(auth.playerIdOf(dee), MatchMode.DUEL, 1_200, "sb-dee", clock.millis());
            new MatchQueue(jredis).ask(MatchMode.DUEL, com.backend.handoff.Ulid.generate(), List.of(
                    List.of(new MatchQueue.Waiting(auth.playerIdOf(dee), MatchMode.DUEL, clock.millis(), 1_200, "sb-dee"))),
                    clock.millis() + 10_000);
            HttpResponse<String> asked = post("/v1/sandbox", null, dee);
            assertThat(asked.statusCode()).as("being asked about a match").isEqualTo(409);
            assertThat(body(asked).get("code").asText()).isEqualTo("already_queued");

            directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150, false, 4, 4)).get();
            HttpResponse<String> full = post("/v1/sandbox", null, cy);
            assertThat(full.statusCode()).isEqualTo(503);
            assertThat(body(full).get("code").asText()).isEqualTo("no_room");
            assertThat(queue("GET", null, cy).body()).as("nothing recorded for a sandbox not opened").contains("\"none\"");
            assertThat(jredis.sync().get("sbx:" + auth.playerIdOf(cy))).as("nor held").isNull();

            assertThat(send(HttpRequest.newBuilder(URI.create(base + "/v1/sandbox"))
                    .header("Authorization", "Bearer " + cy).GET().build()).statusCode()).isEqualTo(405);
        } finally {
            gateway.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a sandbox is held until it ends: leaving the queue's record frees no second one, and one left before it was joined is given back (S-13, D-54)")
    void aSandboxIsHeldUntilItEnds() throws Exception {
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150, false, 0, 8)).get();
        String ada = register("hold-ada");
        long adaId = auth.playerIdOf(ada);
        TicketStore tickets = new TicketStore(jredis);

        String first = body(post("/v1/sandbox", null, ada)).get("ticketId").asText();
        long held = jredis.sync().send("TTL", "sbx:" + adaId).asLong();
        assertThat(held).as("held for a ticket's life").isBetween(1L, 60L);
        queue("DELETE", null, ada);
        assertThat(tickets.claim(first).get(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("left before it was joined: its ticket revoked").isNull();
        HttpResponse<String> second = post("/v1/sandbox", null, ada);
        assertThat(second.statusCode()).as("and the hold given back").isEqualTo(200);
        assertThat(tickets.claim(body(second).get("ticketId").asText()).get(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("joined, as the arena claims it").isNotNull();
        queue("DELETE", null, ada);
        HttpResponse<String> third = post("/v1/sandbox", null, ada);
        assertThat(third.statusCode()).as("joined, it is held until it ends").isEqualTo(409);
        assertThat(body(third).get("code").asText()).isEqualTo("in_sandbox");
        com.backend.handoff.Ticket duel = com.backend.handoff.Ticket.forMatch(adaId, "hold-ada", 1,
                com.backend.handoff.Ulid.generate(), MatchMode.DUEL.id);
        tickets.issue(duel).get(5, java.util.concurrent.TimeUnit.SECONDS);
        new MatchQueue(jredis).matched(adaId, MatchMode.DUEL, new MatchQueue.Grant("10.0.0.7", 9001, duel.id(), false,
                duel.matchUid()), TicketStore.TTL_SECONDS, System.currentTimeMillis());
        queue("DELETE", null, ada);
        assertThat(jredis.sync().get("sbx:" + adaId)).as("a duel queued for from inside it, left, takes nothing from it")
                .isNotNull();

        String bob = register("hold-bob");
        String cy = register("hold-cy");
        long bobId = auth.playerIdOf(bob);
        long cyId = auth.playerIdOf(cy);
        jredis.sync().set("conn:" + bobId, "gw-h#x-b");
        jredis.sync().set("conn:" + cyId, "gw-h#x-c");
        String partyId = body(post("/v1/party/invite", "{\"playerId\":" + cyId + "}", bob)).get("partyId").asText();
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", cy).statusCode()).isEqualTo(200);
        jredis.sync().set("sbx:" + cyId, "another-match");
        HttpResponse<String> party = post("/v1/sandbox", null, bob);
        assertThat(party.statusCode()).as("a member holds one").isEqualTo(409);
        assertThat(body(party).get("code").asText()).isEqualTo("in_sandbox");
        assertThat(jredis.sync().get("sbx:" + bobId)).as("and nothing is left held for the leader").isNull();
    }

    @Test
    @Timeout(60)
    @DisplayName("a player called to a tournament match is refused a queue and a sandbox while its ticket lives, as one matched (Q-44)")
    void aCalledPlayerIsRefusedAQueueAndASandbox() throws Exception {
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150, false, 0, 8)).get();
        String ada = register("call-ada");
        long adaId = auth.playerIdOf(ada);
        new com.backend.handoff.TournamentGrants(jredis).put(7, adaId, "{}").get(5, java.util.concurrent.TimeUnit.SECONDS);
        HttpResponse<String> queued = queue("POST", "{\"mode\":\"duel\"}", ada);
        assertThat(queued.statusCode()).isEqualTo(409);
        assertThat(body(queued).get("code").asText()).isEqualTo("in_match");
        HttpResponse<String> sandbox = post("/v1/sandbox", null, ada);
        assertThat(sandbox.statusCode()).isEqualTo(409);
        assertThat(body(sandbox).get("code").asText()).isEqualTo("in_match");
        assertThat(jredis.sync().get("sbx:" + adaId)).as("nothing held").isNull();
        jredis.sync().send("DEL", "tcall:" + adaId);                     // the call lapsed with its ticket
        assertThat(queue("POST", "{\"mode\":\"duel\"}", ada).statusCode()).isEqualTo(200);
    }

    @Test
    @Timeout(60)
    @DisplayName("a phrase said to the party is pushed to every member, the speaker too; one every two seconds each, and only from the list (01 §9, 04 §4)")
    void partyPhrases() throws Exception {
        String ada = register("say-ada");
        String bob = register("say-bob");
        String cy = register("say-cy");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-s",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            jredis.sync().set("conn:" + adaId, "gw-s#x-a");
            jredis.sync().set("conn:" + bobId, "gw-s#x-b");
            String partyId = body(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada)).get("partyId").asText();
            assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", bob).statusCode()).isEqualTo(200);
            nextPush(pushed);                                   // the invitation
            nextPush(pushed);                                   // and the two updates
            nextPush(pushed);

            HttpResponse<String> alone = post("/v1/party/say", "{\"phraseId\":3}", cy);
            assertThat(alone.statusCode()).isEqualTo(404);
            assertThat(body(alone).get("code").asText()).isEqualTo("no_party");
            for (int id : new int[] {0, com.backend.sim.PhraseTable.defaults().size() + 1}) {
                HttpResponse<String> unknown = post("/v1/party/say", "{\"phraseId\":" + id + "}", ada);
                assertThat(unknown.statusCode()).isEqualTo(400);
                assertThat(body(unknown).get("code").asText()).isEqualTo("unknown_phrase");
            }
            assertThat(post("/v1/party/say", "{}", ada).statusCode()).as("no phraseId").isEqualTo(400);

            long first = System.nanoTime();
            HttpResponse<String> said = post("/v1/party/say", "{\"phraseId\":3}", ada);
            assertThat(said.statusCode()).as("the unknown ones took no turn").isEqualTo(200);
            assertThat(body(said).get("partyId").asText()).isEqualTo(partyId);
            java.util.Set<Long> told = new java.util.HashSet<>();
            for (int i = 0; i < 2; i++) {
                JsonNode push = nextPush(pushed);
                assertThat(push.at("/msg/t").asText()).isEqualTo("evt.party.said");
                assertThat(push.at("/msg/d/from").asLong()).isEqualTo(adaId);
                assertThat(push.at("/msg/d/name").asText()).isEqualTo("say-ada");
                assertThat(push.at("/msg/d/phraseId").asInt()).isEqualTo(3);
                told.add(push.get("to").asLong());
            }
            assertThat(told).containsExactlyInAnyOrder(adaId, bobId);

            HttpResponse<String> soon = post("/v1/party/say", "{\"phraseId\":4}", ada);
            assertThat(soon.statusCode()).isEqualTo(429);
            assertThat(body(soon).get("code").asText()).isEqualTo("too_soon");
            assertThat(post("/v1/party/say", "{\"phraseId\":5}", bob).statusCode()).as("a turn is each member's own").isEqualTo(200);
            for (int i = 0; i < 2; i++) {
                assertThat(nextPush(pushed).at("/msg/d/from").asLong()).as("bob's, not ada's second").isEqualTo(bobId);
            }
            assertThat(pushed.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();

            Thread.sleep(Math.max(0, 1_500 - (System.nanoTime() - first) / 1_000_000));
            assertThat(post("/v1/party/say", "{\"phraseId\":6}", ada).statusCode()).as("a second and a half on").isEqualTo(429);
            Thread.sleep(Math.max(0, 2_150 - (System.nanoTime() - first) / 1_000_000));
            assertThat(post("/v1/party/say", "{\"phraseId\":6}", ada).statusCode()).as("two seconds on").isEqualTo(200);
        } finally {
            gateway.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a team match is queued by a team's leader or vice leader, with a party of three of the team, at the team's rating (Q-18)")
    void aTeamQueuesForATeamMatch() throws Exception {
        String ada = register("tq-ada");
        String bob = register("tq-bob");
        String cyd = register("tq-cyd");
        String dee = register("tq-dee");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        long cydId = auth.playerIdOf(cyd);
        long deeId = auth.playerIdOf(dee);
        for (long id : List.of(adaId, bobId, cydId, deeId)) {
            jredis.sync().set("conn:" + id, "gw-t#x-" + id);
        }
        TeamRepository teamRows = new TeamRepository(db.dataSource(), 30);
        long tanks = teamRows.create(adaId, "TqTanks", clock.now).teamId();
        for (long id : List.of(bobId, cydId)) {
            teamRows.invite(adaId, id, clock.now);
            teamRows.answer(id, tanks, true, clock.now);
        }
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("UPDATE team SET rating = 1340 WHERE id = ?")) {
            ps.setLong(1, tanks);
            ps.executeUpdate();
        }
        String teams = "{\"mode\":\"teams\"}";

        expectError(queue("POST", teams, ada), 400, "party_too_small");
        String partyId = body(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada)).get("partyId").asText();
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", bob).statusCode()).isEqualTo(200);
        expectError(queue("POST", teams, ada), 400, "party_too_small");
        assertThat(post("/v1/party/invite", "{\"playerId\":" + deeId + "}", ada).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", dee).statusCode()).isEqualTo(200);
        expectError(queue("POST", teams, ada), 409, "not_one_team");

        assertThat(post("/v1/party/leave", null, dee).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/leave", null, ada).statusCode()).isEqualTo(200);   // bob alone: no party
        String bobs = body(post("/v1/party/invite", "{\"playerId\":" + adaId + "}", bob)).get("partyId").asText();
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + bobs + "\"}", ada).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/invite", "{\"playerId\":" + cydId + "}", bob).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + bobs + "\"}", cyd).statusCode()).isEqualTo(200);
        expectError(queue("POST", teams, bob), 403, "not_allowed");

        assertThat(teamRows.setRole(adaId, bobId, TeamRepository.VICE)).isEqualTo(TeamRepository.Outcome.OK);
        assertThat(queue("POST", teams, bob).statusCode()).as("a vice leader").isEqualTo(200);
        assertThat(jredis.sync().hget("mmp:" + bobId, "team")).isEqualTo(Long.toString(tanks));
        for (long id : List.of(adaId, bobId, cydId)) {
            assertThat(jredis.sync().hget("mmp:" + bobId, "r:" + id)).as("each at the team's rating").isEqualTo("1340");
        }
        assertThat(queue("DELETE", null, bob).statusCode()).isEqualTo(200);
    }

    @Test
    @Timeout(60)
    @DisplayName("a party queues together: its leader queues it, for a mode it fits; each member is told; any member leaving takes it out (04 §4)")
    void aPartyQueuesTogether() throws Exception {
        String ada = register("pq-ada");
        String bob = register("pq-bob");
        String cyd = register("pq-cyd");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        long cydId = auth.playerIdOf(cyd);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-q",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (long id : List.of(adaId, bobId, cydId)) {
                jredis.sync().set("conn:" + id, "gw-q#x-" + id);
            }
            String partyId = body(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada)).get("partyId").asText();
            assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", bob).statusCode()).isEqualTo(200);
            assertThat(post("/v1/party/invite", "{\"playerId\":" + cydId + "}", ada).statusCode()).isEqualTo(200);
            for (int i = 0; i < 4; i++) {
                nextPush(pushed);                            // two invitations, and the party told to both
            }

            HttpResponse<String> member = queue("POST", "{\"mode\":\"tvt\"}", bob);
            assertThat(member.statusCode()).isEqualTo(409);
            assertThat(body(member).get("code").asText()).as("a member does not queue").isEqualTo("in_party");
            HttpResponse<String> tooBig = queue("POST", "{\"mode\":\"duel\"}", ada);
            assertThat(tooBig.statusCode()).isEqualTo(400);
            assertThat(body(tooBig).get("code").asText()).isEqualTo("party_too_big");

            try (java.sql.Connection c = db.dataSource().getConnection();
                 java.sql.PreparedStatement ps = c.prepareStatement("UPDATE player SET rating_tvt = 1300 WHERE id = ?")) {
                ps.setLong(1, adaId);
                ps.executeUpdate();
            }
            assertThat(queue("POST", "{\"mode\":\"tvt\"}", ada).statusCode()).isEqualTo(200);
            assertThat(jredis.sync().hget("mmp:" + adaId, "r:" + adaId)).as("each queued by the mode's own rating")
                    .isEqualTo("1300");
            assertThat(jredis.sync().hget("mmp:" + adaId, "n:" + bobId)).isEqualTo("pq-bob");
            JsonNode queued = nextPush(pushed);
            assertThat(queued.get("to").asLong()).as("the member, whom the leader queued").isEqualTo(bobId);
            assertThat(queued.at("/msg/t").asText()).isEqualTo("evt.queue.update");
            assertThat(queued.at("/msg/d/state").asText()).isEqualTo("queued");
            assertThat(queued.at("/msg/d/mode").asText()).isEqualTo("tvt");
            assertThat(body(queue("GET", null, bob)).get("state").asText()).isEqualTo("queued");
            assertThat(body(queue("GET", null, bob)).get("mode").asText()).isEqualTo("tvt");

            // A member leaving the queue takes the party out; the leader is told.
            assertThat(queue("DELETE", null, bob).statusCode()).isEqualTo(200);
            JsonNode out = nextPush(pushed);
            assertThat(out.get("to").asLong()).isEqualTo(adaId);
            assertThat(out.at("/msg/d/state").asText()).isEqualTo("none");
            assertThat(body(queue("GET", null, ada)).get("state").asText()).isEqualTo("none");

            assertThat(queue("POST", "{\"mode\":\"tvt\"}", ada).statusCode()).isEqualTo(200);
            assertThat(nextPush(pushed).get("to").asLong()).isEqualTo(bobId);
            HttpResponse<String> late = post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", cyd);
            assertThat(late.statusCode()).isEqualTo(409);
            assertThat(body(late).get("code").asText()).as("a queued party takes no one in").isEqualTo("queued");

            // A member leaving the party takes it out of the queue; both are told both.
            assertThat(post("/v1/party/leave", null, bob).statusCode()).isEqualTo(200);
            java.util.Set<Long> outOfQueue = new java.util.HashSet<>();
            for (int i = 0; i < 4; i++) {
                JsonNode p = nextPush(pushed);
                if (p.at("/msg/t").asText().equals("evt.queue.update")) {
                    assertThat(p.at("/msg/d/state").asText()).isEqualTo("none");
                    outOfQueue.add(p.get("to").asLong());
                }
            }
            assertThat(outOfQueue).containsExactlyInAnyOrder(adaId, bobId);
            assertThat(body(queue("GET", null, ada)).get("state").asText()).isEqualTo("none");
            assertThat(body(queue("GET", null, bob)).get("state").asText()).isEqualTo("none");
        } finally {
            gateway.close();
        }
    }

    // ---- the queue (04 §4) ------------------------------------------------------------

    private static HttpResponse<String> queue(String method, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/v1/queue"))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return send(b.build());
    }

    @Test
    @Timeout(60)
    @DisplayName("a player joins the duel queue once, can see it and leave it, and the public arena is not queued for")
    void queueing() throws Exception {
        String token = register("ada");
        HttpResponse<String> joined = queue("POST", "{\"mode\":\"duel\"}", token);
        assertThat(joined.statusCode()).isEqualTo(200);
        assertThat(body(joined).get("state").asText()).isEqualTo("queued");
        assertThat(jredis.sync().hget("mmp:" + auth.playerIdOf(token), "rating"))
                .as("paired by the rating MySQL holds").isEqualTo("1200");

        HttpResponse<String> again = queue("POST", "{\"mode\":\"duel\"}", token);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(body(again).get("code").asText()).isEqualTo("already_queued");

        clock.now = clock.now.plusSeconds(7);
        JsonNode status = body(queue("GET", null, token));
        assertThat(status.get("state").asText()).isEqualTo("queued");
        assertThat(status.get("mode").asText()).isEqualTo("duel");
        assertThat(status.get("waitedSeconds").asLong()).isEqualTo(7);
        assertThat(status.has("grant")).as("nothing to go to yet").isFalse();

        HttpResponse<String> left = queue("DELETE", null, token);
        assertThat(left.statusCode()).isEqualTo(200);
        assertThat(body(queue("GET", null, token)).get("state").asText()).isEqualTo("none");
        assertThat(jredis.sync().zcard("mmq:duel")).isZero();
        assertThat(queue("DELETE", null, token).statusCode()).as("leaving twice is fine").isEqualTo(200);

        String rffa = register("ada-rffa");
        assertThat(queue("POST", "{\"mode\":\"rffa\"}", rffa).statusCode()).as("ranked free-for-all").isEqualTo(200);
        assertThat(jredis.sync().hget("mmp:" + auth.playerIdOf(rffa), "rating")).isEqualTo("1200");

        String coop = register("ada-coop");
        assertThat(queue("POST", "{\"mode\":\"coop\"}", coop).statusCode()).as("co-op, unrated").isEqualTo(200);
        assertThat(jredis.sync().hget("mmp:" + auth.playerIdOf(coop), "rating")).as("no rating to match by").isEqualTo("0");

        HttpResponse<String> ffa = queue("POST", "{\"mode\":\"ffa\"}", token);
        assertThat(ffa.statusCode()).isEqualTo(400);
        assertThat(body(ffa).get("code").asText()).isEqualTo("unknown_mode");
        assertThat(queue("POST", "{}", token).statusCode()).isEqualTo(400);
        assertThat(queue("POST", "{\"mode\":\"duel\"}", null).statusCode()).isEqualTo(401);
        assertThat(queue("GET", null, "not-a-token").statusCode()).isEqualTo(401);
        HttpResponse<String> put = queue("PUT", "{}", token);
        assertThat(put.statusCode()).isEqualTo(405);
        assertThat(put.headers().firstValue("Allow")).hasValue("POST, DELETE, GET");
    }

    @Test
    @Timeout(60)
    @DisplayName("a match made while the player was away is there to fetch, grant and all")
    void aMatchIsFetched() throws Exception {
        String token = register("ada");
        long playerId = auth.playerIdOf(token);
        queue("POST", "{\"mode\":\"duel\"}", token);
        // As the matcher records one (04 §4): the push may have gone nowhere.
        new MatchQueue(jredis).matched(playerId, MatchMode.DUEL, new MatchQueue.Grant("10.0.0.7", 9001, "tkt-9", true,
                "01JC0000000000000000000000"), 60, System.currentTimeMillis());

        JsonNode status = body(queue("GET", null, token));
        assertThat(status.get("state").asText()).isEqualTo("matched");
        assertThat(status.path("grant").get("arenaHost").asText()).isEqualTo("10.0.0.7");
        assertThat(status.path("grant").get("ticketId").asText()).isEqualTo("tkt-9");
        assertThat(status.path("grant").get("tls").asBoolean()).isTrue();
        assertThat(status.path("grant").get("mode").asText()).isEqualTo("duel");
        HttpResponse<String> requeue = queue("POST", "{\"mode\":\"duel\"}", token);
        assertThat(requeue.statusCode()).isEqualTo(409);
        assertThat(body(requeue).get("code").asText()).isEqualTo("in_match");
    }

    @Test
    @Timeout(60)
    @DisplayName("a match found is answered over the API: accepted, declined, or refused as not asked; a locked player does not queue (04 §4)")
    void aMatchFoundIsAnswered() throws Exception {
        String ada = register("cf-ada");
        String bob = register("cf-bob");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        String uid = "01JC0000000000000000000009";
        // As the matcher asks (04 §4, the third slice): both taken from the queue and asked.
        new MatchQueue(jredis).join(adaId, com.backend.handoff.MatchMode.DUEL, 1_200, "cf-ada", clock.millis());
        new MatchQueue(jredis).join(bobId, com.backend.handoff.MatchMode.DUEL, 1_200, "cf-bob", clock.millis());
        new MatchQueue(jredis).ask(com.backend.handoff.MatchMode.DUEL, uid, List.of(
                List.of(new MatchQueue.Waiting(adaId, com.backend.handoff.MatchMode.DUEL, clock.millis(), 1_200, "cf-ada")),
                List.of(new MatchQueue.Waiting(bobId, com.backend.handoff.MatchMode.DUEL, clock.millis(), 1_200, "cf-bob"))),
                clock.millis() + 10_000);

        clock.now = clock.now.plusMillis(3_500);
        JsonNode status = body(queue("GET", null, ada));
        assertThat(status.get("state").asText()).isEqualTo("confirming");
        assertThat(status.get("matchUid").asText()).isEqualTo(uid);
        assertThat(status.get("secondsLeft").asLong()).as("6.5 s, rounded up: never 0 while time is left").isEqualTo(7);

        String answer = "{\"matchUid\":\"" + uid + "\"}";
        HttpResponse<String> accepted = answer("accept", answer, ada);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(body(accepted).get("state").asText()).isEqualTo("confirming");
        assertThat(jredis.sync().hget("mmp:" + adaId, "answer")).isEqualTo("accept");
        assertThat(answer("decline", answer, bob).statusCode()).isEqualTo(200);
        assertThat(jredis.sync().hget("mmp:" + bobId, "answer")).isEqualTo("decline");

        HttpResponse<String> other = answer("accept", "{\"matchUid\":\"01JC0000000000000000000008\"}", ada);
        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(body(other).get("code").asText()).isEqualTo("not_confirming");
        assertThat(answer("accept", "{}", ada).statusCode()).isEqualTo(400);
        assertThat(answer("accept", answer, "not-a-token").statusCode()).isEqualTo(401);
        assertThat(answer("maybe", answer, ada).statusCode()).isEqualTo(404);
        assertThat(send(HttpRequest.newBuilder(URI.create(base + "/v1/queue/accept"))
                .header("Authorization", "Bearer " + ada).GET().build()).statusCode()).isEqualTo(405);

        jredis.sync().del("mmp:" + bobId);
        jredis.sync().set("mmlock:" + bobId, "1");
        HttpResponse<String> locked = queue("POST", "{\"mode\":\"duel\"}", bob);
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(body(locked).get("code").asText()).isEqualTo("queue_locked");
    }

    private static HttpResponse<String> answer(String what, String json, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/v1/queue/" + what))
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    // ---- accounts --------------------------------------------------------------------

    @Test
    @Timeout(60)
    @DisplayName("registering returns 201 and the new player id")
    void register() throws Exception {
        HttpResponse<String> response = post("/v1/accounts",
                "{\"username\":\"ada\",\"displayName\":\"Ada\",\"password\":\"hunter2-hunter2\"}", null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("playerId").asLong()).isPositive();
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/json");
    }

    @Test
    @Timeout(60)
    @DisplayName("a taken name is 409, and bad input is 400 with a code to branch on")
    void registrationFailures() throws Exception {
        post("/v1/accounts",
                "{\"username\":\"ada\",\"displayName\":\"Ada\",\"password\":\"hunter2-hunter2\"}", null);

        HttpResponse<String> taken = post("/v1/accounts",
                "{\"username\":\"ada\",\"displayName\":\"Other\",\"password\":\"hunter2-hunter2\"}", null);
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(body(taken).get("code").asText()).isEqualTo("username_taken");

        HttpResponse<String> shortPassword = post("/v1/accounts",
                "{\"username\":\"bob\",\"displayName\":\"Bob\",\"password\":\"short\"}", null);
        assertThat(shortPassword.statusCode()).isEqualTo(400);
        assertThat(body(shortPassword).get("code").asText()).isEqualTo("invalid_password");

        HttpResponse<String> badName = post("/v1/accounts",
                "{\"username\":\"has space\",\"password\":\"hunter2-hunter2\"}", null);
        assertThat(badName.statusCode()).isEqualTo(400);
        assertThat(body(badName).get("code").asText()).isEqualTo("invalid_username");
    }

    // ---- display names: the one piece of text every other player sees ---------------

    private static HttpResponse<String> registerNamed(String username, String displayName)
            throws Exception {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("password", "hunter2-hunter2");
        if (displayName != null) {
            body.put("displayName", displayName);
        }
        return post("/v1/accounts", Json.MAPPER.writeValueAsString(body), null);
    }

    private static String storedName(HttpResponse<String> created) throws Exception {
        long id = body(created).get("playerId").asLong();
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT display_name FROM player WHERE id = ?")) {
            ps.setLong(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a display name that would deceive or break another player's screen is refused")
    void hostileDisplayNamesAreRefused() throws Exception {
        // Each of these was accepted and stored before, and reached the kill feed and the
        // public leaderboard of every other player.
        java.util.Map<String, String> hostile = new java.util.LinkedHashMap<>();
        hostile.put("newline", "Ada\nSYSTEM: you are banned");
        hostile.put("right-to-left override", "Ada\u202Enimda");
        hostile.put("zero-width padding", "A\u200B\u200Bda");
        hostile.put("terminal escape", "Ada\u001b[31m");
        hostile.put("Latin with a Cyrillic a", "Ad\u0430");
        hostile.put("stacked marks", "a\u0301\u0301\u0301\u0301");
        hostile.put("symbols only", "...");
        hostile.put("an emoji", "Ada\uD83D\uDE00");
        hostile.put("staff impersonation", "Admin");
        hostile.put("too long", "a".repeat(17));
        int n = 0;
        for (var entry : hostile.entrySet()) {
            HttpResponse<String> response = registerNamed("hostile" + (n++), entry.getValue());
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(400);
            assertThat(body(response).get("code").asText()).as(entry.getKey())
                    .isEqualTo("invalid_display_name");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("names in any script are accepted, stored in one canonical form")
    void internationalNamesAreAccepted() throws Exception {
        assertThat(storedName(registerNamed("kim", "  김철수  ")))
                .as("trimmed, and Hangul is a name like any other").isEqualTo("김철수");
        assertThat(storedName(registerNamed("yamada", "山田 太郎")))
                .isEqualTo("山田 太郎");
        assertThat(storedName(registerNamed("renee", "Rene\u0301e")))
                .as("a decomposed accent is stored composed, so two spellings are one name")
                .isEqualTo("Ren\u00e9e");
        assertThat(storedName(registerNamed("spaced", "Ada   the   Great")))
                .isEqualTo("Ada the Great");
        // Sixteen characters of a four-byte script is 64 UTF-8 bytes, which is exactly what
        // the kill feed will carry — so any accepted name is always shown, never dropped.
        String sixteenHan = "\u6f22".repeat(16);
        assertThat(storedName(registerNamed("sixteen", sixteenHan))).isEqualTo(sixteenHan);
    }

    @Test
    @Timeout(60)
    @DisplayName("with no display name, the username stands in — cut to fit, never broken")
    void blankNameFallsBackToTheUsername() throws Exception {
        String longUsername = "a_very_long_username_of_32_chars";
        assertThat(longUsername).hasSize(32);

        HttpResponse<String> created = registerNamed(longUsername, "   ");

        assertThat(created.statusCode()).isEqualTo(201);
        // Usernames are ASCII, so cutting one cannot split a character. Display names are
        // not, which is why the old code's substring(0, 32) could produce broken text.
        assertThat(storedName(created)).isEqualTo(longUsername.substring(0, 16));
    }

    @Test
    @Timeout(60)
    @DisplayName("a body that is not JSON is a 400, not a 500")
    void malformedBody() throws Exception {
        HttpResponse<String> broken = post("/v1/accounts", "{ this is not json", null);
        assertThat(broken.statusCode()).isEqualTo(400);

        HttpResponse<String> empty = post("/v1/accounts", null, null);
        assertThat(empty.statusCode()).isEqualTo(400);
    }

    @Test
    @Timeout(60)
    @DisplayName("a body over the limit is a 413, not a prefix of it parsed")
    void oversizedBody() throws Exception {
        // A valid object and then padding. Cut at the limit, the object was read and the
        // account created: Jackson stops at the end of the first value.
        String json = "{\"username\":\"padded\",\"password\":\"hunter2-hunter2\"}" + " ".repeat(5_000);
        HttpResponse<String> response = post("/v1/accounts", json, null);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(body(response).get("code").asText()).isEqualTo("body_too_large");
    }

    @Test
    @Timeout(60)
    @DisplayName("a password holding half a surrogate pair is refused like any bad password")
    void loneSurrogatePassword() throws Exception {
        // Valid JSON for a string that is not valid UTF-16. Hashing it threw, and it was a 500
        // with a stack trace in the log.
        HttpResponse<String> response = post("/v1/accounts",
                "{\"username\":\"lone\",\"password\":\"hunter2-hunter2\\ud800\"}", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("code").asText()).isEqualTo("invalid_password");
    }


    // ---- sessions --------------------------------------------------------------------

    @Test
    @Timeout(60)
    @DisplayName("logging in returns a token and how long it lasts")
    void login() throws Exception {
        post("/v1/accounts",
                "{\"username\":\"ada\",\"displayName\":\"Ada\",\"password\":\"hunter2-hunter2\"}", null);

        HttpResponse<String> response = post("/v1/sessions",
                "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = body(response);
        assertThat(json.get("token").asText()).isNotBlank();
        assertThat(json.get("playerId").asLong()).isPositive();
        // The client needs this to know when to log in again rather than discovering it
        // from a 401 in the middle of something. So it is the session's real lifetime: the
        // store's TTL is spread by ±10 % (04 §1), and this used to be the unspread 86 400,
        // wrong by up to 2.4 hours either way.
        long stored = jredis.sync().ttl("sess:" + json.get("token").asText());
        assertThat(json.get("expiresInSeconds").asLong()).isBetween(stored, stored + 2);
    }

    @Test
    @Timeout(60)
    @DisplayName("a wrong password and an unknown user are the same 401")
    void badCredentialsAreIndistinguishable() throws Exception {
        post("/v1/accounts",
                "{\"username\":\"ada\",\"displayName\":\"Ada\",\"password\":\"hunter2-hunter2\"}", null);

        HttpResponse<String> wrong = post("/v1/sessions",
                "{\"username\":\"ada\",\"password\":\"not-the-password\"}", null);
        HttpResponse<String> unknown = post("/v1/sessions",
                "{\"username\":\"nobody\",\"password\":\"not-the-password\"}", null);

        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        // Identical down to the code, or the API becomes the username oracle the service
        // was careful not to be.
        assertThat(body(wrong).get("code").asText())
                .isEqualTo(body(unknown).get("code").asText())
                .isEqualTo("invalid_credentials");
    }

    @Test
    @Timeout(60)
    @DisplayName("logging out revokes the token")
    void logout() throws Exception {
        String token = register("ada");

        HttpResponse<String> out = send(HttpRequest.newBuilder(URI.create(base + "/v1/sessions"))
                .header("Authorization", "Bearer " + token)
                .DELETE().build());

        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(out.body()).as("204 carries no body").isEmpty();
        assertThat(post("/v1/match-requests", null, token).statusCode())
                .as("the token is dead").isEqualTo(401);
    }

    @Test
    @Timeout(60)
    @DisplayName("no Authorization header is a 401, not a 500")
    void missingToken() throws Exception {
        assertThat(post("/v1/match-requests", null, null).statusCode()).isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder(URI.create(base + "/v1/sessions"))
                .DELETE().build()).statusCode()).isEqualTo(401);
    }

    // ---- match requests ---------------------------------------------------------------

    @Test
    @Timeout(60)
    @DisplayName("a match request returns an arena and a ticket")
    void requestMatch() throws Exception {
        String token = register("ada");
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150))
                .get();

        long issued = joins.issued();
        HttpResponse<String> response = post("/v1/match-requests", null, token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(joins.issued() - issued).as("a ticket issued, counted (04 §11)").isEqualTo(1);
        JsonNode json = body(response);
        assertThat(json.get("arenaHost").asText()).isEqualTo("10.0.0.7");
        assertThat(json.get("arenaPort").asInt()).isEqualTo(9001);
        assertThat(json.get("ticketId").asText()).isNotBlank();
        assertThat(json.get("tls").asBoolean()).as("this arena did not announce TLS").isFalse();
    }

    @Test
    @Timeout(60)
    @DisplayName("an arena that announced TLS is handed out with the instruction to use it")
    void requestMatchWithTls() throws Exception {
        String token = register("ada");
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150, true))
                .get();

        JsonNode json = body(post("/v1/match-requests", null, token));

        // The client decides how to connect from this alone; a missing flag would send a
        // ticket in plaintext to an arena that then refuses to read it.
        assertThat(json.get("tls").isBoolean()).isTrue();
        assertThat(json.get("tls").asBoolean()).isTrue();
    }

    @Test
    @Timeout(60)
    @DisplayName("no arena with room is a 503, because nothing is broken")
    void noArena() throws Exception {
        String token = register("ada");

        HttpResponse<String> response = post("/v1/match-requests", null, token);

        // 503 rather than 500: the client should retry, not report a fault.
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body(response).get("code").asText()).isEqualTo("no_arena");
    }

    @Test
    @Timeout(60)
    @DisplayName("a token that was never issued gets nowhere")
    void unknownToken() throws Exception {
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150)).get();

        HttpResponse<String> response = post("/v1/match-requests", null, "not-a-real-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(body(response).get("code").asText()).isEqualTo("invalid_session");
    }

    // ---- leaderboards ------------------------------------------------------------------

    /** As {@code worker} would, after it had committed the match. */
    private static void rank(long playerId, String name, int score) throws Exception {
        leaderboards.record(playerId, name, score, System.currentTimeMillis()).get();
    }

    @Test
    @Timeout(60)
    @DisplayName("the top of a board reads without a session, best first, ranked from 1")
    void topOfBoard() throws Exception {
        rank(1, "one", 100);
        rank(2, "two", 300);
        rank(3, "three", 200);

        HttpResponse<String> response = get("/v1/leaderboards/alltime", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = body(response);
        assertThat(json.get("board").asText()).isEqualTo("alltime");
        JsonNode entries = json.get("entries");
        assertThat(entries).hasSize(3);
        // Ranks are 1-based on the wire even though the store counts from 0: a player is
        // "#1", and the conversion happens once, here, rather than in every client.
        assertThat(entries.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(entries.get(0).get("name").asText()).isEqualTo("two");
        assertThat(entries.get(0).get("score").asInt()).isEqualTo(300);
        assertThat(entries.get(2).get("rank").asInt()).isEqualTo(3);
    }

    @Test
    @Timeout(60)
    @DisplayName("limit is honoured, and clamped rather than refused")
    void boardLimit() throws Exception {
        for (int i = 1; i <= 12; i++) {
            rank(i, "p" + i, i * 10);
        }

        assertThat(body(get("/v1/leaderboards/alltime?limit=3", null)).get("entries"))
                .hasSize(3);
        assertThat(body(get("/v1/leaderboards/alltime?limit=9999", null)).get("entries"))
                .as("clamped to the cap, not rejected").hasSize(12);
        assertThat(body(get("/v1/leaderboards/alltime?limit=nonsense", null)).get("entries"))
                .as("and a limit that is not a number falls back to the default").hasSize(12);
    }

    @Test
    @Timeout(60)
    @DisplayName("every board is readable, and nothing else is")
    void boardNames() throws Exception {
        rank(1, "one", 100);

        for (String board : new String[] {"alltime", "daily", "weekly"}) {
            assertThat(get("/v1/leaderboards/" + board, null).statusCode())
                    .as(board).isEqualTo(200);
        }
        HttpResponse<String> unknown = get("/v1/leaderboards/yearly", null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(body(unknown).get("code").asText()).isEqualTo("unknown_board");

        assertThat(get("/v1/leaderboards", null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/leaderboards/alltime/neighbours", null).statusCode()).isEqualTo(404);
    }

    @Test
    @Timeout(60)
    @DisplayName("around me needs a session and gives a 1-based rank with its neighbours")
    void aroundMe() throws Exception {
        String token = register("ada");
        long me = body(post("/v1/sessions",
                "{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}", null))
                .get("playerId").asLong();
        rank(me, "Ada", 550);
        for (int i = 1; i <= 8; i++) {
            rank(900_000 + i, "p" + i, i * 100);        // 800, 700 and 600 above; 500 below
        }

        HttpResponse<String> response = get("/v1/leaderboards/alltime/me", token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = body(response);
        assertThat(json.get("rank").asInt()).as("three scored higher, so fourth").isEqualTo(4);
        assertThat(json.get("score").asInt()).isEqualTo(550);
        JsonNode window = json.get("entries");
        assertThat(window).hasSize(9);
        assertThat(window.get(3).get("playerId").asLong()).as("me, in the middle").isEqualTo(me);
        assertThat(window.get(3).get("rank").asInt()).isEqualTo(4);
    }

    @Test
    @Timeout(60)
    @DisplayName("a player with no score is 404 not_ranked, so the client can say why")
    void notRanked() throws Exception {
        String token = register("ada");

        HttpResponse<String> response = get("/v1/leaderboards/alltime/me", token);

        // Not a 200 with nulls: the client shows "play a match to be ranked", which it
        // cannot distinguish from "rank 0" if this succeeds.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).get("code").asText()).isEqualTo("not_ranked");
    }

    @Test
    @Timeout(60)
    @DisplayName("around me without a token is a 401, but the public board still reads")
    void aroundMeNeedsASession() throws Exception {
        rank(1, "one", 100);

        assertThat(get("/v1/leaderboards/alltime/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/v1/leaderboards/alltime/me", "not-a-real-token").statusCode())
                .isEqualTo(401);
        assertThat(get("/v1/leaderboards/alltime", null).statusCode())
                .as("the top of a board is public").isEqualTo(200);
    }

    // ---- shape of the API --------------------------------------------------------------

    @Test
    @Timeout(60)
    @DisplayName("the wrong method is a 405 that says which are allowed, counted like any response")
    void wrongMethod() throws Exception {
        com.backend.common.Metrics metrics = new com.backend.common.Metrics();
        server.registerMetrics(metrics);
        // The count is the server's, over every test in this class: this test's two are the rise.
        long before = count405(metrics.render());
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/v1/accounts"))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(body(response).get("code").asText()).isEqualTo("method_not_allowed");
        // RFC 9110 requires it, and neither this nor the count below was there.
        assertThat(response.headers().firstValue("Allow")).hasValue("POST");

        HttpResponse<String> sessions = send(HttpRequest.newBuilder(URI.create(base + "/v1/sessions"))
                .PUT(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(sessions.statusCode()).isEqualTo(405);
        assertThat(sessions.headers().firstValue("Allow")).hasValue("POST, DELETE");
        // Counted once the response has gone, on the server's thread, so a client holding its
        // answer can read the count before it lands (T-15): wait for it, a little.
        long rise = 0;
        for (int i = 0; i < 250 && rise < 2; i++) {
            rise = count405(metrics.render()) - before;
            if (rise < 2) {
                Thread.sleep(20);
            }
        }
        assertThat(rise).isEqualTo(2);
    }

    @Test
    @Timeout(60)
    @DisplayName("each route's requests are timed, a histogram per route whose p99 the server works out (04 §11)")
    void requestsAreTimed() throws Exception {
        com.backend.common.Metrics metrics = new com.backend.common.Metrics();
        server.registerMetrics(metrics);
        String token = register("timed-ada");                             // /v1/accounts and /v1/sessions
        long before = timed(metrics.render(), "/v1/queue");
        queue("GET", null, token);
        queue("GET", null, token);
        long rise = 0;
        for (int i = 0; i < 250 && rise < 2; i++) {                    // timed as the answer goes (T-15)
            rise = timed(metrics.render(), "/v1/queue") - before;
            if (rise < 2) {
                Thread.sleep(20);
            }
        }
        assertThat(rise).isEqualTo(2);
        send(HttpRequest.newBuilder(URI.create(base + "/v1/leaderboards/alltime?limit=3")).GET().build());
        for (int i = 0; i < 250 && timed(metrics.render(), "/v1/leaderboards") == 0; i++) {
            Thread.sleep(20);
        }
        String rendered = metrics.render();
        assertThat(rendered).as("by the API's route, not the path a client chose")
                .contains("route=\"/v1/leaderboards\"").doesNotContain("/v1/leaderboards/alltime");
        assertThat(rendered).contains("# TYPE backend_platform_request_seconds histogram")
                .contains("backend_platform_request_seconds_bucket{route=\"/v1/queue\",le=\"+Inf\"}")
                .containsPattern("backend_platform_request_seconds_count\\{route=\"/v1/sessions\"\\} [1-9]");
    }

    @Test
    @Timeout(60)
    @DisplayName("an unknown inventory path's 404 is counted, as every answer is (04 §11)")
    void anUnknownInventoryPathIsCounted() throws Exception {
        com.backend.common.Metrics metrics = new com.backend.common.Metrics();
        server.registerMetrics(metrics);
        java.util.regex.Pattern found = java.util.regex.Pattern.compile("backend_platform_responses_total\\{status=\"404\"\\} (\\d+)");
        java.util.function.ToLongFunction<String> count = r -> {
            java.util.regex.Matcher m = found.matcher(r);
            return m.find() ? Long.parseLong(m.group(1)) : 0;
        };
        long before = count.applyAsLong(metrics.render());
        assertThat(send(HttpRequest.newBuilder(URI.create(base + "/v1/inventory/nope/elsewhere")).GET().build()).statusCode())
                .isEqualTo(404);
        long rise = 0;
        for (int i = 0; i < 250 && rise < 1; i++) {                       // counted as the answer goes (T-15)
            rise = count.applyAsLong(metrics.render()) - before;
            if (rise < 1) {
                Thread.sleep(20);
            }
        }
        assertThat(rise).isEqualTo(1);
    }

    private static long timed(String rendered, String route) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("backend_platform_request_seconds_count\\{route=\"" + java.util.regex.Pattern.quote(route) + "\"\\} (\\d+)")
                .matcher(rendered);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static long count405(String rendered) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("backend_platform_responses_total\\{status=\"405\"\\} (\\d+)").matcher(rendered);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    @Test
    @Timeout(60)
    @DisplayName("the class table is served as JSON, versioned by its hash, and not sent again to a client that has it (D-24)")
    void classTable() throws Exception {
        com.backend.sim.ClassTable table = com.backend.sim.ClassTable.defaults();
        URI at = URI.create(base + "/v1/content/classes");
        HttpResponse<String> response = send(HttpRequest.newBuilder(at).GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        String etag = "\"" + table.version() + "\"";
        assertThat(response.headers().firstValue("ETag")).hasValue(etag);
        JsonNode body = body(response);
        assertThat(body.get("version").asLong()).isEqualTo(table.version());
        assertThat(body.get("classes").size()).isEqualTo(table.size());
        assertThat(body.get("classes").get(0).get("name").asText()).isEqualTo("Basic");
        assertThat(body.get("classes").get(com.backend.sim.ClassTable.PREDATOR).get("zoom").asDouble()).isEqualTo(700.0);

        HttpResponse<String> again = send(HttpRequest.newBuilder(at).header("If-None-Match", etag).GET().build());
        assertThat(again.statusCode()).as("the client has it").isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(send(HttpRequest.newBuilder(at).POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode())
                .isEqualTo(405);
    }

    @Test
    @Timeout(60)
    @DisplayName("the phrase list is served as JSON, versioned by its hash, and not sent again to a client that has it (01 §9)")
    void phraseList() throws Exception {
        com.backend.sim.PhraseTable table = com.backend.sim.PhraseTable.defaults();
        URI at = URI.create(base + "/v1/content/phrases");
        HttpResponse<String> response = send(HttpRequest.newBuilder(at).GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        String etag = "\"" + table.version() + "\"";
        assertThat(response.headers().firstValue("ETag")).hasValue(etag);
        JsonNode body = body(response);
        assertThat(body.get("version").asLong()).isEqualTo(table.version());
        assertThat(body.get("phrases").size()).isEqualTo(table.size());
        assertThat(body.get("phrases").get(0).get("id").asInt()).isEqualTo(1);
        assertThat(body.get("phrases").get(0).get("key").asText()).isEqualTo("hello");
        assertThat(body.get("phrases").get(0).get("text").asText()).isEqualTo("Hello!");

        HttpResponse<String> again = send(HttpRequest.newBuilder(at).header("If-None-Match", etag).GET().build());
        assertThat(again.statusCode()).as("the client has it").isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(send(HttpRequest.newBuilder(at).POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode())
                .isEqualTo(405);
    }

    @Test
    @Timeout(60)
    @DisplayName("health answers without touching a database")
    void health() throws Exception {
        // Deliberately not a dependency check: a health endpoint that fails when MySQL
        // blinks takes the process out of rotation for something it could have ridden out.
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/health"))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("status").asText()).isEqualTo("ok");
    }

    @Test
    @Timeout(120)
    @DisplayName("concurrent logins all succeed and each gets its own token")
    void concurrentLogins() throws Exception {
        int callers = 12;                       // more than the hasher's permits
        // Twelve accounts, each from its own address. What is under test is the hasher's
        // queue; one account logging in twelve times at once is what the login throttle
        // exists to refuse.
        for (int i = 0; i < callers; i++) {
            post("/v1/accounts",
                    "{\"username\":\"ada" + i + "\",\"password\":\"hunter2-hunter2\"}", null);
        }
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.Set<String> tokens = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.concurrent.atomic.AtomicInteger failures =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[callers];

        for (int i = 0; i < callers; i++) {
            final int n = i;
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                    HttpResponse<String> r = postFrom("198.51.100." + n, "/v1/sessions",
                            "{\"username\":\"ada" + n + "\",\"password\":\"hunter2-hunter2\"}");
                    if (r.statusCode() == 200) {
                        tokens.add(body(r).get("token").asText());
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(90_000);
        }

        // Argon2 admits eight at a time; the rest queue. Queuing is the designed behaviour,
        // so every caller must still get served rather than time out or be refused.
        assertThat(failures.get()).isZero();
        assertThat(tokens).as("every login is its own session").hasSize(callers);
    }

    // ---- the shop (04 §8, "The shop, as built") ---------------------------------------

    /** A registered, logged-in player given {@code coins}, as a reward would. */
    private static String buyer(String name, long coins) throws Exception {
        String token = register(name);
        long playerId = auth.playerIdOf(token);
        if (coins > 0) {
            economy.credit(playerId, coins, EconomyRepository.REASON_MATCH_REWARD, "test",
                    "test:" + playerId);
        }
        return token;
    }

    private static HttpResponse<String> buy(String token, String sku, String key) throws Exception {
        return post("/v1/purchases", "{\"sku\":\"%s\",\"key\":\"%s\"}".formatted(sku, key), token);
    }

    private static final String KEY_1 = "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a90";
    private static final String KEY_2 = "8e1d4b7a-2c9f-4a35-b6e0-1f7c3d9a5b24";

    @Test
    @Timeout(60)
    @DisplayName("an offer in gems is listed so and bought with gems, the answer giving both balances (04 §8, plan item 68)")
    void anOfferInGems() throws Exception {
        String token = register("gem-buyer");
        try (var c = db.dataSource().getConnection();
             var ps = c.prepareStatement("UPDATE player SET gems = 25 WHERE id = ?")) {
            ps.setLong(1, auth.playerIdOf(token));
            ps.executeUpdate();
        }
        JsonNode offers = body(get("/v1/shop", null)).get("offers");
        assertThat(offers).filteredOn(o -> o.get("sku").asText().equals("gem_boost"))
                .extracting(o -> o.get("currency").asText()).containsExactly("gems");
        assertThat(offers.get(0).get("currency").asText()).as("coins, unless said").isEqualTo("coins");
        HttpResponse<String> bought = buy(token, "gem_boost", "gemkey-0000000000000001");
        assertThat(bought.statusCode()).as(bought.body()).isEqualTo(200);
        assertThat(List.of(body(bought).get("gems").asLong(), body(bought).get("coins").asLong())).containsExactly(5L, 0L);
        expectError(buy(token, "gem_boost", "gemkey-0000000000000002"), 409, "insufficient_funds");
    }

    @Test
    @Timeout(60)
    @DisplayName("the shop lists what is on sale now, with no session needed")
    void shopListsWhatIsOnSale() throws Exception {
        HttpResponse<String> response = get("/v1/shop", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode offers = body(response).get("offers");
        assertThat(offers).extracting(o -> o.get("sku").asText())
                .containsExactly("hat", "crown", "festival_hat", "gem_boost");      // the cape is not on sale yet
        assertThat(offers.get(1).get("requiresLevel").asInt()).isEqualTo(5);
        assertThat(offers.get(2).get("availableTo").asText()).isEqualTo("2026-11-01T00:00:00Z");
    }

    @Test
    @Timeout(60)
    @DisplayName("a purchase charges once however often it is sent, and says what is held")
    void aPurchaseChargesOnce() throws Exception {
        String token = buyer("ada", 500);

        JsonNode first = body(buy(token, "hat", KEY_1));
        assertThat(first.get("result").asText()).isEqualTo("bought");
        assertThat(first.get("itemId").asText()).isEqualTo("cosmetic_hat");
        assertThat(first.get("coins").asLong()).isEqualTo(400);
        assertThat(first.get("held").asInt()).isEqualTo(1);

        HttpResponse<String> again = buy(token, "hat", KEY_1);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again).get("result").asText()).isEqualTo("already_bought");
        assertThat(body(again).get("coins").asLong()).as("charged once").isEqualTo(400);

        assertThat(body(buy(token, "hat", KEY_2)).get("held").asInt()).as("a new tap, a new key").isEqualTo(2);
        JsonNode inventory = body(get("/v1/inventory", token));
        assertThat(inventory.get("coins").asLong()).isEqualTo(300);
        assertThat(inventory.get("gems").asLong()).isZero();
        assertThat(inventory.get("items")).hasSize(1);
        assertThat(inventory.get("items").get(0).get("itemId").asText()).isEqualTo("cosmetic_hat");
        assertThat(inventory.get("items").get(0).get("qty").asInt()).isEqualTo(2);
    }

    @Test
    @Timeout(60)
    @DisplayName("a retry is answered as its first attempt was, even after the offer has ended")
    void aRetryAfterTheOfferEndedIsStillBought() throws Exception {
        String token = buyer("ada", 100);
        assertThat(body(buy(token, "festival_hat", KEY_1)).get("result").asText()).isEqualTo("bought");

        clock.now = Instant.parse("2026-11-01T00:00:00Z");       // the festival is over
        // Checked before the key, this was "not_available": charged once, and told it failed.
        HttpResponse<String> retry = buy(token, "festival_hat", KEY_1);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(body(retry).get("result").asText()).isEqualTo("already_bought");
        // And the key, not the retry's sku, says what was bought.
        assertThat(body(buy(token, "crown", KEY_1)).get("itemId").asText()).isEqualTo("cosmetic_hat");

        HttpResponse<String> late = buy(token, "festival_hat", KEY_2);
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(body(late).get("code").asText()).isEqualTo("not_available");
    }

    @Test
    @Timeout(60)
    @DisplayName("a purchase that is refused says why, and moves nothing")
    void refusalsSayWhy() throws Exception {
        com.backend.common.Metrics metrics = new com.backend.common.Metrics();
        server.registerMetrics(metrics);
        String token = buyer("ada", 50);

        expectError(buy(token, "hat", KEY_1), 409, "insufficient_funds");
        expectError(buy(token, "tiara", KEY_1), 404, "unknown_sku");
        expectError(buy(token, "cape", KEY_1), 409, "not_available");
        HttpResponse<String> level = buy(token, "crown", KEY_1);
        expectError(level, 403, "level_required");
        assertThat(body(level).get("message").asText()).contains("level 5");
        // Keys are compared without regard to case in the ledger: upper case would let two
        // different keys be one. And a short one is a counter, not a transaction id.
        expectError(buy(token, "hat", KEY_1.toUpperCase(java.util.Locale.ROOT)), 400, "invalid_key");
        expectError(buy(token, "hat", "tap-1"), 400, "invalid_key");
        expectError(post("/v1/purchases", "{\"key\":\"" + KEY_1 + "\"}", token), 400, "invalid_body");
        expectError(buy(null, "hat", KEY_1), 401, "no_token");
        expectError(buy("not-a-token", "hat", KEY_1), 401, "invalid_session");
        expectError(get("/v1/inventory", "not-a-token"), 401, "invalid_session");

        JsonNode inventory = body(get("/v1/inventory", token));
        assertThat(inventory.get("coins").asLong()).isEqualTo(50);
        assertThat(inventory.get("items")).isEmpty();
        assertThat(metrics.render())
                .contains("backend_platform_purchases_total{outcome=\"insufficient_funds\"} 1")
                .contains("backend_platform_purchases_total{outcome=\"invalid_key\"} 2");
    }

    @Test
    @Timeout(60)
    @DisplayName("an offer that needs a level is sold once the player has it")
    void aLevelOpensAnOffer() throws Exception {
        String token = buyer("ada", 1_000);
        expectError(buy(token, "crown", KEY_1), 403, "level_required");
        try (var c = db.dataSource().getConnection();
             var ps = c.prepareStatement("UPDATE player SET level = 5 WHERE id = ?")) {
            ps.setLong(1, auth.playerIdOf(token));
            ps.executeUpdate();
        }
        assertThat(body(buy(token, "crown", KEY_1)).get("result").asText()).isEqualTo("bought");
    }

    @Test
    @Timeout(60)
    @DisplayName("a tap sent eight times at once is one purchase")
    void concurrentRetriesAreOnePurchase() throws Exception {
        String token = buyer("ada", 500);
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> all = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            all.add(http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/v1/purchases"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"sku\":\"hat\",\"key\":\"" + KEY_1 + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString()));
        }
        int bought = 0;
        for (var f : all) {
            HttpResponse<String> r = f.get();
            assertThat(r.statusCode()).isEqualTo(200);
            bought += "bought".equals(body(r).get("result").asText()) ? 1 : 0;
        }
        assertThat(bought).isEqualTo(1);
        assertThat(body(get("/v1/inventory", token)).get("coins").asLong()).isEqualTo(400);
    }

    @Test
    @Timeout(60)
    @DisplayName("equipment: an item held is worn in its slot, what it gives capped at 25 % a stat, and taken off")
    void equipment() throws Exception {
        String token = register("ada");
        long ada = auth.playerIdOf(token);
        JsonNode empty = body(get("/v1/equipment", token));
        assertThat(empty.get("slots").size()).as("four that give a bonus, and the skin's").isEqualTo(5);
        assertThat(empty.get("slots").get("barrel").isNull()).isTrue();
        assertThat(empty.get("bonus")).isEmpty();

        expectError(put("/v1/equipment/barrel", "{\"itemId\":\"barrel_big\"}", token), 409, "not_owned");
        hold(ada, "barrel_big", 1);
        hold(ada, "core_hot", 1);
        expectError(put("/v1/equipment/core", "{\"itemId\":\"barrel_big\"}", token), 409, "wrong_slot");
        expectError(put("/v1/equipment/hat", "{\"itemId\":\"barrel_big\"}", token), 400, "invalid_slot");
        expectError(put("/v1/equipment/barrel", "{\"itemId\":\"ghost\"}", token), 404, "unknown_item");
        expectError(put("/v1/equipment/barrel", "{}", token), 400, "invalid_body");
        expectError(delete("/v1/equipment/hat", token), 400, "invalid_slot");

        JsonNode worn = body(put("/v1/equipment/barrel", "{\"itemId\":\"barrel_big\"}", token));
        assertThat(worn.get("slots").get("barrel").asText()).isEqualTo("barrel_big");
        assertThat(worn.get("bonus").get("bullet_damage").asInt()).isEqualTo(20);
        worn = body(put("/v1/equipment/core", "{\"itemId\":\"core_hot\"}", token));
        assertThat(worn.get("bonus").get("bullet_damage").asInt()).as("20 and 10, capped").isEqualTo(25);
        assertThat(worn.get("bonus").get("reload").asInt()).isEqualTo(8);
        assertThat(worn.get("bonus").size()).as("only the stats it touches").isEqualTo(2);
        assertThat(body(get("/v1/equipment", token))).isEqualTo(worn);
        assertThat(equipment.bonusOf(ada)[com.backend.sim.Stat.BULLET_DAMAGE]).as("what a ticket carries")
                .isEqualTo((byte) 25);

        JsonNode off = body(delete("/v1/equipment/barrel", token));
        assertThat(off.get("slots").get("barrel").isNull()).isTrue();
        assertThat(off.get("bonus").get("bullet_damage").asInt()).isEqualTo(10);
        hold(ada, "core_hot", 0);
        assertThat(body(get("/v1/equipment", token)).get("bonus")).as("worn, and held no more: nothing").isEmpty();

        expectError(get("/v1/equipment", null), 401, "no_token");
        expectError(get("/v1/equipment", "not-a-token"), 401, "invalid_session");
        expectError(delete("/v1/equipment/barrel", "not-a-token"), 401, "invalid_session");
        assertThat(post("/v1/equipment", "{}", token).statusCode()).isEqualTo(405);
        assertThat(get("/v1/equipment/barrel", token).statusCode()).isEqualTo(405);
    }

    @Test
    @Timeout(60)
    @DisplayName("an item raised over the API costs its level's price and gives its level's bonus, once a key; none past the coins (04 §8, plan item 67)")
    void itemLevels() throws Exception {
        String token = register("lvl-ada");
        long ada = auth.playerIdOf(token);
        hold(ada, "core_hot", 1);
        try (var c = db.dataSource().getConnection();
             var ps = c.prepareStatement("UPDATE player SET coins = 2000 WHERE id = ?")) {
            ps.setLong(1, ada);
            ps.executeUpdate();
        }
        put("/v1/equipment/core", "{\"itemId\":\"core_hot\"}", token);
        assertThat(body(get("/v1/equipment", token)).at("/bonus/reload").asInt()).as("level 1").isEqualTo(8);

        HttpResponse<String> raised = post("/v1/inventory/core_hot/level", "{\"key\":\"level-raise-0001\"}", token);
        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(200);
        assertThat(List.of(body(raised).get("level").asInt(), body(raised).get("coins").asInt())).containsExactly(2, 1_500);
        JsonNode bonus = body(get("/v1/equipment", token)).get("bonus");
        assertThat(List.of(bonus.get("reload").asInt(), bonus.get("bullet_damage").asInt())).as("× 5 / 4, rounded down")
                .containsExactly(10, 12);
        assertThat(body(post("/v1/inventory/core_hot/level", "{\"key\":\"level-raise-0001\"}", token)).get("coins").asInt())
                .as("a key once").isEqualTo(1_500);
        assertThat(body(post("/v1/inventory/core_hot/level", "{\"key\":\"level-raise-0002\"}", token)).get("level").asInt()).isEqualTo(3);
        expectError(post("/v1/inventory/core_hot/level", "{\"key\":\"level-raise-0003\"}", token), 409, "insufficient_funds");
        assertThat(body(get("/v1/inventory", token)).get("items").get(0).get("level").asInt()).isEqualTo(3);
        expectError(post("/v1/inventory/barrel_big/level", "{\"key\":\"level-raise-0004\"}", token), 404, "not_held");
        expectError(post("/v1/inventory/boost_xp/level", "{\"key\":\"level-raise-0005\"}", token), 400, "not_equipment");
        expectError(post("/v1/inventory/core_hot/level", "{}", token), 400, "invalid_key");
        expectError(post("/v1/inventory/core_hot/level", "{\"key\":\"lv-7\"}", token), 400, "invalid_key");
        expectError(post("/v1/inventory/core_hot/level", "{\"key\":\"Level-Raise-0007\"}", token), 400, "invalid_key");
        expectError(post("/v1/inventory/core_hot/level", "{\"key\":\"level-raise-0006\"}", "not-a-token"), 401, "invalid_session");
    }

    @Test
    @Timeout(60)
    @DisplayName("what a player wears rides in their ticket to the public arena, and in their queue entry (D-37)")
    void theBonusRidesInTheTicket() throws Exception {
        String token = register("ada");
        long ada = auth.playerIdOf(token);
        hold(ada, "barrel_big", 1);
        hold(ada, "core_hot", 1);
        put("/v1/equipment/barrel", "{\"itemId\":\"barrel_big\"}", token);
        put("/v1/equipment/core", "{\"itemId\":\"core_hot\"}", token);
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150)).get();

        String ticketId = body(post("/v1/match-requests", null, token)).get("ticketId").asText();
        com.backend.handoff.Ticket ticket = new TicketStore(jredis).claim(ticketId).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ticket.bonus()).as("bullet damage 20 and 10, capped; reload 8").isEqualTo("5:25,6:8");

        assertThat(post("/v1/queue", "{\"mode\":\"duel\"}", token).statusCode()).isEqualTo(200);
        assertThat(jredis.sync().hget("mmp:" + ada, "bonus")).as("read with the rating, when queued").isEqualTo("5:25,6:8");

        String plain = register("bob");
        String plainTicket = body(post("/v1/match-requests", null, plain)).get("ticketId").asText();
        assertThat(new TicketStore(jredis).claim(plainTicket).get(5, java.util.concurrent.TimeUnit.SECONDS).bonus())
                .as("nothing worn").isEmpty();
    }

    @Test
    @Timeout(60)
    @DisplayName("boosts: one held is activated once per key, one of a kind at a time, and shown running (Q-13)")
    void boosts() throws Exception {
        String token = register("ada");
        long ada = auth.playerIdOf(token);
        expectError(post("/v1/boosts", "{\"itemId\":\"boost_xp\",\"key\":\"" + KEY_1 + "\"}", token), 409, "not_owned");
        hold(ada, "boost_xp", 2);
        hold(ada, "boost_xp_half", 1);
        hold(ada, "barrel_big", 1);
        expectError(post("/v1/boosts", "{\"itemId\":\"ghost\",\"key\":\"" + KEY_1 + "\"}", token), 404, "unknown_item");
        expectError(post("/v1/boosts", "{\"itemId\":\"barrel_big\",\"key\":\"" + KEY_1 + "\"}", token), 404,
                "unknown_item");
        expectError(post("/v1/boosts", "{\"itemId\":\"boost_xp\",\"key\":\"Short\"}", token), 400, "invalid_key");
        expectError(post("/v1/boosts", "{\"itemId\":\"boost_xp\"}", token), 400, "invalid_body");

        JsonNode first = body(post("/v1/boosts", "{\"itemId\":\"boost_xp\",\"key\":\"" + KEY_1 + "\"}", token));
        assertThat(first.get("result").asText()).isEqualTo("activated");
        JsonNode running = first.get("boosts").get(0);
        assertThat(running.get("kind").asText()).isEqualTo("xp");
        assertThat(running.get("itemId").asText()).isEqualTo("boost_xp");
        assertThat(running.get("percent").asInt()).isEqualTo(100);
        assertThat(running.get("endsAt").asText()).isEqualTo("2026-10-15T13:00:00Z");
        JsonNode again = body(post("/v1/boosts", "{\"itemId\":\"boost_xp\",\"key\":\"" + KEY_1 + "\"}", token));
        assertThat(again.get("result").asText()).as("a retry").isEqualTo("already_activated");
        assertThat(body(get("/v1/inventory", token)).get("items")).anyMatch(
                i -> i.get("itemId").asText().equals("boost_xp") && i.get("qty").asInt() == 1);
        expectError(post("/v1/boosts", "{\"itemId\":\"boost_xp_half\",\"key\":\"" + KEY_2 + "\"}", token), 409,
                "other_running");
        assertThat(body(get("/v1/boosts", token)).get("boosts")).hasSize(1);

        expectError(get("/v1/boosts", null), 401, "no_token");
        expectError(get("/v1/boosts", "not-a-token"), 401, "invalid_session");
        assertThat(put("/v1/boosts", "{}", token).statusCode()).isEqualTo(405);
    }

    /** The next {@code n} pushes, by whom each went to; a type other than evt.team.update is skipped over. */
    private static java.util.Map<Long, JsonNode> teamPushes(java.util.concurrent.LinkedBlockingQueue<String> pushed, int n)
            throws Exception {
        java.util.Map<Long, JsonNode> byPlayer = new java.util.HashMap<>();
        while (byPlayer.size() < n) {
            JsonNode push = nextPush(pushed);
            if (push.at("/msg/t").asText().equals("evt.team.update")) {
                assertThat(byPlayer.put(push.get("to").asLong(), push.at("/msg/d/team"))).as("one each").isNull();
            }
        }
        return byPlayer;
    }

    @Test
    @Timeout(60)
    @DisplayName("renaming over the API: a player, once in 30 days, the score boards' name with it; a team, by its leader, every member told (04 §1, plan item 63)")
    void renaming() throws Exception {
        String ada = register("rn-ada");
        String bob = register("rn-bob");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        expectError(put("/v1/accounts/name", "{\"displayName\":\"\"}", ada), 400, "invalid_display_name");
        HttpResponse<String> renamed = put("/v1/accounts/name", "{\"displayName\":\"Ada Lovelace\"}", ada);
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(body(renamed).get("playerId").asLong()).isEqualTo(adaId);
        assertThat(body(renamed).get("displayName").asText()).isEqualTo("Ada Lovelace");
        assertThat(jredis.sync().hget("lb:name", Long.toString(adaId))).as("the score boards' name, at once")
                .isEqualTo("Ada Lovelace");
        expectError(put("/v1/accounts/name", "{\"displayName\":\"Ada Again\"}", ada), 429, "too_soon");
        expectError(put("/v1/accounts/name", "{\"displayName\":\"Nobody\"}", "not-a-token"), 401, "invalid_session");
        assertThat(post("/v1/accounts/name", "{}", ada).statusCode()).isEqualTo(405);

        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-rn",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (long id : new long[] {adaId, bobId}) {
                jredis.sync().set("conn:" + id, "gw-rn#x-" + id);
            }
            long teamId = body(post("/v1/teams", "{\"name\":\"RnTanks\"}", ada)).get("id").asLong();
            post("/v1/teams/mine/invites", "{\"playerId\":" + bobId + "}", ada);
            post("/v1/team-invites/" + teamId, "{\"accept\":true}", bob);
            teamPushes(pushed, 2);
            expectError(put("/v1/teams/mine/name", "{\"name\":\"Bobs\"}", bob), 403, "not_allowed");
            expectError(put("/v1/teams/mine/name", "{\"name\":\"   \"}", ada), 400, "invalid_name");
            post("/v1/teams", "{\"name\":\"RnTaken\"}", register("rn-cyd"));
            expectError(put("/v1/teams/mine/name", "{\"name\":\"rntaken\"}", ada), 409, "name_taken");
            HttpResponse<String> teamRenamed = put("/v1/teams/mine/name", "{\"name\":\"RnTreads\"}", ada);
            assertThat(teamRenamed.statusCode()).as(teamRenamed.body()).isEqualTo(200);
            assertThat(body(teamRenamed).get("name").asText()).isEqualTo("RnTreads");
            java.util.Map<Long, JsonNode> told = teamPushes(pushed, 2);
            assertThat(told.get(bobId).get("name").asText()).as("every member told").isEqualTo("RnTreads");
            expectError(put("/v1/teams/mine/name", "{\"name\":\"RnTurrets\"}", ada), 429, "too_soon");
        } finally {
            gateway.close();
        }
    }

    @Test
    @DisplayName("every kind of inbox item has its name on the wire: a kind written without one failed the whole inbox (P-37)")
    void everyInboxKindHasAName() throws Exception {
        for (java.lang.reflect.Field f : com.backend.persistence.InboxRepository.class.getFields()) {
            if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                int kind = f.getInt(null);
                assertThat(kind).as(f.getName()).isLessThan(PlatformHttpServer.INBOX_KINDS.length);
                assertThat(PlatformHttpServer.INBOX_KINDS[kind]).as(f.getName()).isEqualTo(f.getName().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("team applications over the API: a team found by its name, applied to, its leader told and answering, the applicant joining; one withdrawn (04 §2, Q-49)")
    void teamApplications() throws Exception {
        String ada = register("ap-ada");
        String bob = register("ap-bob");
        String cy = register("ap-cyd");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-ap",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (long id : new long[] {adaId, bobId}) {
                jredis.sync().set("conn:" + id, "gw-ap#x-" + id);
            }
            long teamId = body(post("/v1/teams", "{\"name\":\"ApTanks\"}", ada)).get("id").asLong();
            JsonNode found = body(get("/v1/teams?name=aptan", bob)).get("teams");
            assertThat(found).hasSize(1);
            assertThat(List.of(found.get(0).get("id").asLong(), found.get(0).get("members").asLong(), found.get(0).get("rating").asLong()))
                    .containsExactly(teamId, 1L, 1_200L);
            expectError(get("/v1/teams?name=", bob), 400, "invalid_name");
            assertThat(body(get("/v1/teams/" + teamId, bob)).get("name").asText()).isEqualTo("ApTanks");
            expectError(get("/v1/teams/999999", bob), 404, "no_such_team");

            String applications = "/v1/teams/" + teamId + "/applications";
            assertThat(post(applications, null, bob).statusCode()).isEqualTo(200);
            JsonNode told = nextPush(pushed);
            assertThat(List.of(told.get("to").asLong(), told.at("/msg/t").asText())).as("the leader told")
                    .containsExactly(adaId, "evt.inbox");
            HttpResponse<String> look = get("/v1/inbox", ada);
            assertThat(look.statusCode()).as("the leader's inbox reads (P-37): " + look.body()).isEqualTo(200);
            JsonNode applied = body(look).get("items").get(0);
            assertThat(List.of(applied.get("kind").asText(), applied.get("ref").asLong()))
                    .containsExactly("team_application", bobId);
            expectError(post(applications, null, bob), 409, "already");
            assertThat(body(get("/v1/team-applications", bob)).at("/applications/0/teamName").asText()).isEqualTo("ApTanks");
            assertThat(body(get("/v1/teams/mine/applications", ada)).at("/applications/0/playerId").asLong()).isEqualTo(bobId);
            expectError(get("/v1/teams/mine/applications", bob), 403, "not_allowed");
            HttpResponse<String> accepted = post("/v1/teams/mine/applications/" + bobId, "{\"accept\":true}", ada);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(body(accepted).get("members")).hasSize(2);
            assertThat(teamPushes(pushed, 2)).containsOnlyKeys(adaId, bobId);
            expectError(post("/v1/teams/mine/applications/" + bobId, "{\"accept\":true}", ada), 404, "no_application");

            assertThat(post(applications, null, cy).statusCode()).isEqualTo(200);
            assertThat(delete(applications, cy).statusCode()).isEqualTo(200);
            expectError(delete(applications, cy), 404, "no_application");
            expectError(post("/v1/teams/999999/applications", null, cy), 404, "no_such_team");
            for (int i = 1; i < 20; i++) {                    // twenty an hour, counted apart from invitations
                post(applications, null, cy);
                delete(applications, cy);
            }
            expectError(post(applications, null, cy), 429, "too_soon");
        } finally {
            gateway.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("team events: each member told the team as it now is, and one no longer in it told so (04 §2, Q-35)")
    void teamEventsArePushed() throws Exception {
        String ada = register("tp-ada");
        String bob = register("tp-bob");
        String cy = register("tp-cyd");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        long cyId = auth.playerIdOf(cy);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-t",
                    (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (long id : new long[] {adaId, bobId, cyId}) {
                jredis.sync().set("conn:" + id, "gw-t#x-" + id);
            }
            long teamId = body(post("/v1/teams", "{\"name\":\"Pushed\"}", ada)).get("id").asLong();
            post("/v1/teams/mine/invites", "{\"playerId\":" + bobId + "}", ada);
            post("/v1/team-invites/" + teamId, "{\"accept\":true}", bob);
            java.util.Map<Long, JsonNode> told = teamPushes(pushed, 2);
            assertThat(told).containsOnlyKeys(adaId, bobId);
            assertThat(told.get(adaId)).as("the team as GET /v1/teams/mine gives it").isEqualTo(body(get("/v1/teams/mine", ada)));
            assertThat(told.get(bobId).get("members")).hasSize(2);

            post("/v1/teams/mine/members/" + bobId + "/role", "{\"role\":\"vice_leader\"}", ada);
            told = teamPushes(pushed, 2);
            assertThat(told.get(bobId).get("members").get(1).get("role").asText()).isEqualTo("vice_leader");

            post("/v1/teams/mine/invites", "{\"playerId\":" + cyId + "}", bob);
            post("/v1/team-invites/" + teamId, "{\"accept\":true}", cy);
            assertThat(teamPushes(pushed, 3)).containsOnlyKeys(adaId, bobId, cyId);

            delete("/v1/teams/mine/members/" + cyId, bob);
            told = teamPushes(pushed, 3);
            assertThat(told.get(cyId).isNull()).as("the one kicked: no team").isTrue();
            assertThat(told.get(adaId).get("members")).hasSize(2);
            assertThat(told.get(bobId)).isEqualTo(body(get("/v1/teams/mine", bob)));

            // Refused: nothing changed, so nobody is told anything.
            expectError(delete("/v1/teams/mine/members/" + adaId, bob), 403, "not_allowed");
            expectError(post("/v1/teams/mine/leave", null, ada), 409, "leader_with_members");
            post("/v1/teams/mine/leader", "{\"playerId\":" + bobId + "}", ada);
            told = teamPushes(pushed, 2);
            assertThat(told.get(adaId).get("members").get(0).get("playerId").asLong()).as("bob leads").isEqualTo(bobId);

            post("/v1/teams/mine/leave", null, ada);
            told = teamPushes(pushed, 2);
            assertThat(told.get(adaId).isNull()).as("the one who left").isTrue();
            assertThat(told.get(bobId).get("members")).hasSize(1);

            post("/v1/teams/mine/invites", "{\"playerId\":" + adaId + "}", bob);
            delete("/v1/teams/mine", bob);
            told = teamPushes(pushed, 1);
            assertThat(told.get(bobId).isNull()).as("disbanded").isTrue();
            assertThat(pushed.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)).as("ada, invited but no member, is told nothing more")
                    .satisfiesAnyOf(p -> assertThat(p).isNull(), p -> assertThat(p).doesNotContain("evt.team.update"));

            // A leader alone may leave: the team goes with them, and nobody else is there to tell.
            String dee = register("tp-dee");
            long deeId = auth.playerIdOf(dee);
            jredis.sync().set("conn:" + deeId, "gw-t#x-" + deeId);
            post("/v1/teams", "{\"name\":\"Alone\"}", dee);
            HttpResponse<String> gone = post("/v1/teams/mine/leave", null, dee);
            assertThat(gone.statusCode()).isEqualTo(200);
            assertThat(body(gone)).as("nothing to say: not a list of invitations").isEmpty();
            assertThat(teamPushes(pushed, 1).get(deeId).isNull()).isTrue();
        } finally {
            gateway.close();
        }
    }

    private static void rate(String token, int rating, int rated) throws Exception {
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE player SET rating_duel = ?, rated_duels = ? WHERE id = ?")) {
            ps.setInt(1, rating);
            ps.setInt(2, rated);
            ps.setLong(3, auth.playerIdOf(token));
            ps.executeUpdate();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("rating boards: a mode's players by rating once listed, the top kept thirty seconds, one's own place fresh (04 §7, Q-40)")
    void ratingBoards() throws Exception {
        String ada = register("rb-ada");
        String bob = register("rb-bob");
        String cyd = register("rb-cyd");
        rate(ada, 1_300, 12);
        rate(bob, 1_250, 15);
        rate(cyd, 1_400, 3);                                    // three rated duels: not yet listed
        JsonNode top = body(get("/v1/leaderboards/duel", null));
        assertThat(top.get("board").asText()).isEqualTo("duel");
        assertThat(top.get("entries")).hasSize(2);
        assertThat(top.at("/entries/0/name").asText()).isEqualTo("rb-ada");
        assertThat(top.at("/entries/0/score").asInt()).as("the rating").isEqualTo(1_300);
        assertThat(top.at("/entries/1/rank").asInt()).isEqualTo(2);

        JsonNode me = body(get("/v1/leaderboards/duel/me", bob));
        assertThat(me.get("rank").asInt()).isEqualTo(2);
        assertThat(me.get("score").asInt()).isEqualTo(1_250);
        assertThat(me.get("entries")).hasSize(2);
        expectError(get("/v1/leaderboards/duel/me", cyd), 404, "not_ranked");
        expectError(get("/v1/leaderboards/duel/me", null), 401, "no_token");

        rate(bob, 1_350, 16);
        assertThat(body(get("/v1/leaderboards/duel", null)).at("/entries/0/name").asText())
                .as("the top, kept thirty seconds").isEqualTo("rb-ada");
        assertThat(body(get("/v1/leaderboards/duel/me", bob)).get("rank").asInt()).as("one's own place, fresh").isEqualTo(1);
        clock.now = clock.now.plusSeconds(30);
        assertThat(body(get("/v1/leaderboards/duel", null)).at("/entries/0/name").asText()).isEqualTo("rb-bob");
        assertThat(body(get("/v1/leaderboards/duel?limit=1", null)).get("entries")).hasSize(1);
        assertThat(body(get("/v1/leaderboards/rffa", null)).get("entries")).as("nobody rated there").isEmpty();
        expectError(get("/v1/leaderboards/squads", null), 404, "unknown_board");
    }

    private static void sqlUpdate(String statement, Object... args) throws Exception {
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the board of teams: listed after ten rated team matches, the caller's team's place, and a past season's (04 §7, D-65)")
    void teamBoard() throws Exception {
        String ada = register("tb-ada");
        String bob = register("tb-bob");
        String cyd = register("tb-cyd");
        long alpha = body(post("/v1/teams", "{\"name\":\"TbAlpha\"}", ada)).get("id").asLong();
        long bravo = body(post("/v1/teams", "{\"name\":\"TbBravo\"}", bob)).get("id").asLong();
        sqlUpdate("UPDATE team SET rating = 1600, rated_matches = 12 WHERE id = ?", alpha);
        sqlUpdate("UPDATE team SET rating = 1500, rated_matches = 10 WHERE id = ?", bravo);
        clock.now = clock.now.plusSeconds(120);           // past any top an earlier test left kept

        JsonNode top = body(get("/v1/leaderboards/teams", null));
        assertThat(top.get("board").asText()).isEqualTo("teams");
        assertThat(top.get("entries")).hasSize(2);
        assertThat(List.of(top.at("/entries/0/teamId").asLong(), top.at("/entries/0/score").asLong()))
                .containsExactly(alpha, 1_600L);
        assertThat(top.at("/entries/0/name").asText()).isEqualTo("TbAlpha");
        sqlUpdate("UPDATE team SET rating = 1700 WHERE id = ?", bravo);
        assertThat(body(get("/v1/leaderboards/teams", null)).at("/entries/0/teamId").asLong())
                .as("the top, kept thirty seconds").isEqualTo(alpha);
        clock.now = clock.now.plusSeconds(30);
        assertThat(body(get("/v1/leaderboards/teams", null)).at("/entries/0/teamId").asLong()).isEqualTo(bravo);
        sqlUpdate("UPDATE team SET rating = 1500 WHERE id = ?", bravo);
        clock.now = clock.now.plusSeconds(30);
        JsonNode mine = body(get("/v1/leaderboards/teams/me", bob));
        assertThat(List.of(mine.get("rank").asInt(), mine.get("score").asInt())).containsExactly(2, 1_500);
        assertThat(mine.get("entries")).hasSize(2);
        expectError(get("/v1/leaderboards/teams/me", cyd), 404, "not_in_team");
        expectError(get("/v1/leaderboards/teams/me", null), 401, "no_token");

        // Ada played a rated team match for her team this season; Bob did not, for his.
        sqlUpdate("INSERT INTO matches (match_uid, mode, kind, arena, started_at, ended_at)"
                + " VALUES ('01JBTEAMBOARD0000000000001', 5, 1, 'arena-1', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        sqlUpdate("INSERT INTO match_team (match_id, side, team_id, placement, rating_delta)"
                + " SELECT id, 1, ?, 1, 0 FROM matches WHERE match_uid = '01JBTEAMBOARD0000000000001'", alpha);
        sqlUpdate("INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths, score, xp_gained)"
                + " SELECT id, ?, 1, 1, 0, 0, 0, 0 FROM matches WHERE match_uid = '01JBTEAMBOARD0000000000001'",
                auth.playerIdOf(ada));
        var seasons = new com.backend.persistence.SeasonRepository(db.dataSource());
        Instant end = seasons.current().endsAt();
        assertThat(seasons.place(1, com.backend.persistence.SeasonRepository.endAfter(end), end)).isTrue();
        seasons.resetTeams(1, end);

        JsonNode past = body(get("/v1/leaderboards/teams?season=1", null));
        assertThat(past.get("entries")).extracting(e -> e.get("teamId").asLong()).containsExactly(alpha, bravo);
        JsonNode adas = body(get("/v1/leaderboards/teams/me?season=1", ada));
        assertThat(List.of(adas.get("rank").asInt(), adas.get("score").asInt())).containsExactly(1, 1_600);
        expectError(get("/v1/leaderboards/teams/me?season=1", bob), 404, "not_ranked");     // he did not play for it
        expectError(get("/v1/leaderboards/teams?season=9", null), 404, "no_such_season");
        expectError(get("/v1/leaderboards/teams/me?season=9", ada), 404, "no_such_season");
        clock.now = clock.now.plusSeconds(60);                                            // past the top's thirty seconds
        assertThat(body(get("/v1/leaderboards/teams", null)).get("entries")).as("the new season: none listed yet").isEmpty();
        expectError(get("/v1/leaderboards/teams/me", ada), 404, "not_ranked");
    }

    @Test
    @Timeout(60)
    @DisplayName("today's goals: the three the worker counts towards, their progress, and the set; the day and its end in UTC (04 §8, D-66)")
    void goals() throws Exception {
        String ada = register("dg-ada");
        long adaId = auth.playerIdOf(ada);
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 15);              // the test clock's
        var three = com.backend.persistence.DailyGoals.of(adaId, day);
        sqlUpdate("INSERT INTO daily_goal (player_id, day, goal_id, progress) VALUES (?, ?, ?, ?)",
                adaId, java.sql.Date.valueOf(day), three.get(0).id(), three.get(0).target());
        JsonNode today = body(get("/v1/goals", ada));
        assertThat(List.of(today.get("day").asText(), today.get("resetsAt").asText()))
                .containsExactly("2026-10-15", "2026-10-16T00:00:00Z");
        assertThat(today.get("goals")).extracting(g -> g.get("id").asText())
                .containsExactlyElementsOf(three.stream().map(com.backend.persistence.DailyGoals.Goal::id).toList());
        JsonNode first = today.at("/goals/0");
        assertThat(List.of(first.get("kind").asText(), first.get("target").asLong(), first.get("coins").asLong(),
                first.get("progress").asLong(), first.get("done").asBoolean())).containsExactly(three.get(0).kind().apiName,
                three.get(0).target(), (long) three.get(0).coins(), three.get(0).target(), true);
        assertThat(today.at("/goals/1/progress").asLong()).isZero();
        assertThat(today.at("/goals/1/done").asBoolean()).isFalse();
        assertThat(List.of(today.get("setGems").asInt(), today.get("setDone").asBoolean())).containsExactly(3, false);
        for (int i = 1; i < 3; i++) {
            sqlUpdate("INSERT INTO daily_goal (player_id, day, goal_id, progress) VALUES (?, ?, ?, ?)",
                    adaId, java.sql.Date.valueOf(day), three.get(i).id(), three.get(i).target() + 5);
        }
        assertThat(body(get("/v1/goals", ada)).get("setDone").asBoolean()).as("all three met").isTrue();
        expectError(get("/v1/goals", null), 401, "no_token");
        expectError(get("/v1/goals", "not-a-session"), 401, "invalid_session");
    }

    @Test
    @Timeout(60)
    @DisplayName("achievements: every one, its threshold and gems, the player's progress and whether it is reached (04 §8, D-64)")
    void achievements() throws Exception {
        String ada = register("ac-ada");
        String bob = register("ac-bob");
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("REPLACE INTO player_stat (player_id, matches, wins, kills,"
                     + " deaths, best_score, playtime_s, assists) VALUES (?, 51, 10, 37, 9, 12000, 7200, 4)")) {
            ps.setLong(1, auth.playerIdOf(ada));
            ps.executeUpdate();
        }
        JsonNode all = body(get("/v1/achievements", ada)).get("achievements");
        assertThat(all).hasSize(17);
        JsonNode kills = all.get(0);
        assertThat(List.of(kills.get("id").asText(), kills.get("stat").asText(), kills.get("threshold").asText(),
                kills.get("gems").asText(), kills.get("progress").asText(), kills.get("reached").asText()))
                .containsExactly("kills_100", "kills", "100", "10", "37", "false");
        java.util.Map<String, JsonNode> byId = new java.util.HashMap<>();
        all.forEach(a -> byId.put(a.get("id").asText(), a));
        assertThat(byId.get("matches_50").get("reached").asBoolean()).isTrue();
        assertThat(byId.get("best_score_10000").get("reached").asBoolean()).isTrue();
        assertThat(byId.get("best_score_50000").get("progress").asLong()).isEqualTo(12_000);
        assertThat(byId.get("playtime_10h").get("progress").asLong()).as("in seconds").isEqualTo(7_200);
        assertThat(byId.get("assists_100").get("stat").asText()).isEqualTo("assists");
        assertThat(byId.get("wins_10").get("reached").asBoolean()).as("at the threshold: reached").isTrue();
        assertThat(byId.get("best_score_50000").get("stat").asText()).isEqualTo("bestScore");
        expectError(get("/v1/achievements", "not-a-session"), 401, "invalid_session");
        assertThat(body(get("/v1/achievements", bob)).get("achievements")).as("no stats yet: nothing, and none reached")
                .allSatisfy(a -> assertThat(a.get("progress").asLong()).isZero());
        expectError(get("/v1/achievements", null), 401, "no_token");
    }

    @Test
    @Timeout(60)
    @DisplayName("seasons: the current and the past; a past season's board, and one's own place in it, after its reset (04 §7, D-63)")
    void seasons() throws Exception {
        JsonNode first = body(get("/v1/seasons", null));
        assertThat(first.at("/current/id").asInt()).isEqualTo(1);
        assertThat(first.get("past")).isEmpty();
        String ada = register("se-ada");
        String bob = register("se-bob");
        String cyd = register("se-cyd");
        rate(ada, 1_600, 12);
        rate(bob, 1_500, 10);
        rate(cyd, 1_700, 4);                                    // not listed: no place
        var seasons = new com.backend.persistence.SeasonRepository(db.dataSource());
        Instant end = seasons.current().endsAt();
        assertThat(seasons.place(1, com.backend.persistence.SeasonRepository.endAfter(end), end)).isTrue();
        while (seasons.resetBatch(1, 1_000, end)) {
            // the rest
        }

        JsonNode now = body(get("/v1/seasons", null));
        assertThat(now.at("/current/id").asInt()).isEqualTo(2);
        assertThat(now.at("/current/startsAt").asText()).isEqualTo(end.toString());
        assertThat(now.at("/past/0/id").asInt()).isEqualTo(1);
        assertThat(now.at("/past/0/endsAt").asText()).isEqualTo(end.toString());

        JsonNode past = body(get("/v1/leaderboards/duel?season=1", null));
        assertThat(past.get("board").asText()).isEqualTo("duel");
        assertThat(past.get("entries")).hasSize(2);
        assertThat(List.of(past.at("/entries/0/name").asText(), past.at("/entries/0/score").asText(),
                past.at("/entries/1/rank").asText())).containsExactly("se-ada", "1600", "2");
        JsonNode mine = body(get("/v1/leaderboards/duel/me?season=1", bob));
        assertThat(List.of(mine.get("rank").asInt(), mine.get("score").asInt())).containsExactly(2, 1_500);
        assertThat(mine.get("entries")).hasSize(2);
        expectError(get("/v1/leaderboards/duel/me?season=1", cyd), 404, "not_ranked");
        expectError(get("/v1/leaderboards/duel/me", ada), 404, "not_ranked");     // the new season: none played yet
        // The server is the class's, and keeps a board's top thirty seconds of this clock, which each test sets back.
        clock.now = clock.now.plusSeconds(120);
        assertThat(body(get("/v1/leaderboards/duel?season=2", null)).get("entries")).as("the current season: the board as it stands")
                .isEmpty();
        expectError(get("/v1/leaderboards/duel?season=9", null), 404, "no_such_season");
        expectError(get("/v1/leaderboards/duel/me?season=9", bob), 404, "no_such_season");
        expectError(get("/v1/leaderboards/duel?season=x", null), 400, "invalid_season");
        expectError(get("/v1/leaderboards/alltime?season=1", null), 404, "no_such_season");   // a score board has none
    }

    @Test
    @Timeout(60)
    @DisplayName("teams: created, joined by invitation, ranked, kicked from, handed over and disbanded (04 §2)")
    void teams() throws Exception {
        String ada = register("ada");
        String bob = register("bob");
        String cy = register("cyd");
        long bobId = auth.playerIdOf(bob);
        long cyId = auth.playerIdOf(cy);
        expectError(post("/v1/teams", "{\"name\":\"   \"}", ada), 400, "invalid_name");
        JsonNode made = body(post("/v1/teams", "{\"name\":\"Tanks\"}", ada));
        assertThat(made.get("name").asText()).isEqualTo("Tanks");
        assertThat(made.get("members").get(0).get("role").asText()).isEqualTo("leader");
        long teamId = made.get("id").asLong();
        assertThat(made.get("rating").asInt()).isEqualTo(1_200);
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("UPDATE team SET wins = 3, losses = 1, draws = 2 WHERE id = ?")) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
        }
        JsonNode record = body(get("/v1/teams/mine", ada));
        assertThat(List.of(record.get("wins").asInt(), record.get("losses").asInt(), record.get("draws").asInt()))
                .as("its record as a team (Q-18)").containsExactly(3, 1, 2);
        expectError(post("/v1/teams", "{\"name\":\"tanks\"}", bob), 409, "name_taken");
        expectError(get("/v1/teams/mine", bob), 404, "no_team");

        expectError(post("/v1/teams/mine/invites", "{\"playerId\":999999999}", ada), 404, "no_such_player");
        assertThat(post("/v1/teams/mine/invites", "{\"playerId\":" + bobId + "}", ada).statusCode())
                .isEqualTo(200);
        JsonNode invites = body(get("/v1/team-invites", bob)).get("invites");
        assertThat(invites.get(0).get("teamName").asText()).isEqualTo("Tanks");
        assertThat(invites.get(0).get("teamId").asLong()).isEqualTo(teamId);
        expectError(post("/v1/team-invites/" + teamId, "{\"accept\":true}", cy), 404, "no_invite");
        post("/v1/teams/mine/invites", "{\"playerId\":" + cyId + "}", ada);
        String eve = register("tp-eve");
        long othersId = body(post("/v1/teams", "{\"name\":\"Others\"}", eve)).get("id").asLong();
        post("/v1/teams/mine/invites", "{\"playerId\":" + cyId + "}", eve);
        JsonNode left = body(post("/v1/team-invites/" + teamId, "{\"accept\":false}", cy)).get("invites");
        assertThat(left).as("declined: the invitations that remain").hasSize(1);
        assertThat(left.get(0).get("teamId").asLong()).isEqualTo(othersId);
        expectError(get("/v1/teams/mine", cy), 404, "no_team");
        JsonNode joined = body(post("/v1/team-invites/" + teamId, "{\"accept\":true}", bob));
        assertThat(joined.get("members")).hasSize(2);
        expectError(post("/v1/teams/mine/invites", "{\"playerId\":" + cyId + "}", bob), 403,
                "not_allowed");

        JsonNode promoted = body(post("/v1/teams/mine/members/" + bobId + "/role", "{\"role\":\"vice_leader\"}", ada));
        assertThat(promoted.get("members").get(1).get("role").asText()).isEqualTo("vice_leader");
        post("/v1/teams/mine/invites", "{\"playerId\":" + cyId + "}", bob);
        body(post("/v1/team-invites/" + teamId, "{\"accept\":true}", cy));
        assertThat(delete("/v1/teams/mine/members/" + cyId, bob).statusCode()).as("a vice leader kicks a member")
                .isEqualTo(200);
        expectError(get("/v1/teams/mine", cy), 404, "no_team");

        expectError(post("/v1/teams/mine/leave", null, ada), 409, "leader_with_members");
        assertThat(body(post("/v1/teams/mine/leader", "{\"playerId\":" + bobId + "}", ada)).get("members").get(0)
                .get("playerId").asLong()).as("bob leads, listed first").isEqualTo(bobId);
        assertThat(post("/v1/teams/mine/leave", null, ada).statusCode()).isEqualTo(200);
        expectError(delete("/v1/teams/mine", ada), 404, "no_team");
        assertThat(delete("/v1/teams/mine", bob).statusCode()).isEqualTo(200);
        expectError(get("/v1/teams/mine", bob), 404, "no_team");

        expectError(get("/v1/teams/mine", null), 401, "no_token");
        expectError(get("/v1/team-invites", "not-a-token"), 401, "invalid_session");
        assertThat(put("/v1/teams/mine", "{}", bob).statusCode()).isEqualTo(405);
    }

    @Test
    @Timeout(60)
    @DisplayName("a round robin's view says its format and its standings, by place; an elimination's, its format alone (04 §6, plan item 66)")
    void aRoundRobinsStandings() throws Exception {
        TournamentRepository cups = new TournamentRepository(db.dataSource());
        long league = cups.create("League", 4, clock.now.plusSeconds(3_600), clock.now.plusSeconds(7_200), 5, 300, 200, 100,
                com.backend.persistence.MatchResultRepository.MODE_DUEL, TournamentRepository.ROUND_ROBIN);
        long[] players = new long[3];
        String[] names = {"rv-ada", "rv-bob", "rv-cyd"};
        for (int i = 0; i < 3; i++) {
            players[i] = auth.playerIdOf(register(names[i]));
            ratedMatches("UPDATE player SET rated_duels = ? WHERE id = ?", players[i]);
            assertThat(cups.register(league, players[i], clock.now)).isEqualTo(TournamentRepository.Registration.OK);
        }
        cups.seed(league, cups.get(league).version(), cups.bySeed(league));
        cups.start(league, cups.get(league).version());
        TournamentRepository.Match first = cups.matches(league).get(0);
        cups.ready(league, first.round(), first.slot(), "01JCRRVIEW0000000000000001", clock.now);
        cups.decide(league, first.round(), first.slot(), first.playerA());

        JsonNode view = body(get("/v1/tournaments/" + league, null));
        assertThat(view.get("format").asText()).isEqualTo("round_robin");
        JsonNode standings = view.get("standings");
        assertThat(standings).hasSize(3);
        assertThat(standings.get(0).get("playerId").asLong()).as("the winner first").isEqualTo(first.playerA());
        assertThat(List.of(standings.get(0).get("points").asInt(), standings.get(0).get("wins").asInt(),
                standings.get(0).get("draws").asInt(), standings.get(0).get("losses").asInt())).containsExactly(3, 1, 0, 0);
        assertThat(standings.get(0).get("name").asText()).isNotEmpty();
        assertThat(standings.get(2).get("playerId").asLong()).as("the loser last").isEqualTo(first.playerB());

        long cup = cups.create("Cup", 4, clock.now.plusSeconds(3_600), clock.now.plusSeconds(7_200), 5, 0, 0, 0);
        JsonNode elimination = body(get("/v1/tournaments/" + cup, null));
        assertThat(elimination.get("format").asText()).isEqualTo("elimination");
        assertThat(elimination.has("standings")).isFalse();
    }

    @Test
    @Timeout(60)
    @DisplayName("tournaments: listed, followed, registered for until the deadline and up to the entries, withdrawn from")
    void tournaments() throws Exception {
        long cup = new TournamentRepository(db.dataSource()).create("Cup", 2, clock.now.plusSeconds(3_600),
                clock.now.plusSeconds(7_200), 5, 1_000, 500, 250);
        String ada = register("ada");
        String bob = register("bob");
        String cy = register("cyd");
        String dee = register("dee");
        for (String played : List.of(ada, bob, cy)) {
            ratedMatches("UPDATE player SET rated_duels = ? WHERE id = ?", auth.playerIdOf(played));
        }
        long gone = new TournamentRepository(db.dataSource()).create("Gone", 2, clock.now.plusSeconds(3_600),
                clock.now.plusSeconds(7_200), 5, 0, 0, 0);
        new TournamentRepository(db.dataSource()).cancel(gone, 0);
        JsonNode listed = body(get("/v1/tournaments", null)).get("tournaments");
        assertThat(listed).as("a cancelled one is not listed").hasSize(1);
        assertThat(listed.get(0).get("id").asLong()).isEqualTo(cup);
        assertThat(listed.get(0).get("state").asText()).isEqualTo("registration");

        JsonNode in = body(post("/v1/tournaments/" + cup + "/entries", null, ada));
        assertThat(in.get("entries").get(0).get("name").asText()).isEqualTo("ada");
        expectError(post("/v1/tournaments/" + cup + "/entries", null, ada), 409, "already");
        assertThat(post("/v1/tournaments/" + cup + "/entries", null, bob).statusCode()).isEqualTo(200);
        expectError(post("/v1/tournaments/" + cup + "/entries", null, cy), 409, "full");
        expectError(post("/v1/tournaments/" + cup + "/entries", null, dee), 409, "too_few_rated");   // Q-43
        assertThat(delete("/v1/tournaments/" + cup + "/entries", bob).statusCode()).isEqualTo(200);
        expectError(delete("/v1/tournaments/" + cup + "/entries", bob), 409, "not_registered");
        JsonNode one = body(get("/v1/tournaments/" + cup, null));
        assertThat(one.get("entries")).hasSize(1);
        assertThat(one.get("prizes").get(0).asLong()).isEqualTo(1_000);
        assertThat(one.get("maxEntries").asInt()).isEqualTo(2);
        expectError(get("/v1/tournaments/999999", null), 404, "no_such_tournament");
        assertThat(post("/v1/tournaments", "{}", ada).statusCode()).isEqualTo(405);
        assertThat(delete("/v1/tournaments/" + cup, ada).statusCode()).isEqualTo(405);

        clock.now = clock.now.plusSeconds(3_600);
        expectError(post("/v1/tournaments/" + cup + "/entries", null, cy), 409, "closed");
        expectError(post("/v1/tournaments/" + cup + "/entries", null, null), 401, "no_token");
    }

    @Test
    @Timeout(90)
    @DisplayName("a teams' tournament: a team entered by its leader's party of three, its roster shown; withdrawn by a leader or vice leader (Q-19)")
    void aTeamEntersATournament() throws Exception {
        String ada = register("tt-ada");
        String bob = register("tt-bob");
        String cyd = register("tt-cyd");
        String dee = register("tt-dee");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        long cydId = auth.playerIdOf(cyd);
        long deeId = auth.playerIdOf(dee);
        for (long id : List.of(adaId, bobId, cydId, deeId)) {
            jredis.sync().set("conn:" + id, "gw-tt#x-" + id);
        }
        TeamRepository teamRows = new TeamRepository(db.dataSource(), 30);
        long tanks = teamRows.create(adaId, "TtTanks", clock.now).teamId();
        ratedMatches("UPDATE team SET rated_matches = ? WHERE id = ?", tanks);   // played enough to enter (Q-43)
        for (long id : List.of(bobId, cydId)) {
            teamRows.invite(adaId, id, clock.now);
            teamRows.answer(id, tanks, true, clock.now);
        }
        TournamentRepository cups = new TournamentRepository(db.dataSource());
        long cup = cups.create("Team Cup", 4, clock.now.plusSeconds(3_600), clock.now.plusSeconds(7_200), 5, 300, 200,
                100, com.backend.persistence.MatchResultRepository.MODE_TEAMS);
        String entries = "/v1/tournaments/" + cup + "/entries";

        expectError(post(entries, null, ada), 400, "party_too_small");
        String partyId = body(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada)).get("partyId").asText();
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", bob).statusCode()).isEqualTo(200);
        expectError(post(entries, null, ada), 400, "party_too_small");
        assertThat(post("/v1/party/invite", "{\"playerId\":" + deeId + "}", ada).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", dee).statusCode()).isEqualTo(200);
        expectError(post(entries, null, ada), 409, "not_one_team");
        expectError(post(entries, null, bob), 409, "in_party");
        assertThat(post("/v1/party/leave", null, dee).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/invite", "{\"playerId\":" + cydId + "}", ada).statusCode()).isEqualTo(200);
        assertThat(post("/v1/party/accept", "{\"partyId\":\"" + partyId + "\"}", cyd).statusCode()).isEqualTo(200);

        JsonNode in = body(post(entries, null, ada));
        assertThat(in.get("mode").asText()).isEqualTo("teams");
        JsonNode entry = in.get("entries").get(0);
        assertThat(entry.get("teamId").asLong()).isEqualTo(tanks);
        assertThat(entry.get("name").asText()).isEqualTo("TtTanks");
        assertThat(entry.has("playerId")).as("a team's entry, not a player's").isFalse();
        List<Long> roster = new java.util.ArrayList<>();
        entry.get("roster").forEach(r -> roster.add(r.get("playerId").asLong()));
        assertThat(roster).containsExactlyInAnyOrder(adaId, bobId, cydId);
        expectError(post(entries, null, ada), 409, "already");

        expectError(delete(entries, bob), 403, "not_allowed");
        assertThat(delete(entries, ada).statusCode()).isEqualTo(200);
        assertThat(body(get("/v1/tournaments/" + cup, null)).get("entries")).isEmpty();
        assertThat(teamRows.transfer(adaId, bobId)).isEqualTo(TeamRepository.Outcome.OK);
        expectError(post(entries, null, ada), 403, "not_allowed");      // leads the party, only a member now
        assertThat(teamRows.transfer(bobId, adaId)).isEqualTo(TeamRepository.Outcome.OK);
        assertThat(post(entries, null, ada).statusCode()).isEqualTo(200);

        long[] others = {auth.playerIdOf(register("tt-eve")), auth.playerIdOf(register("tt-fay")),
                auth.playerIdOf(register("tt-gus"))};
        long rams = teamRows.create(others[0], "TtRams", clock.now).teamId();
        ratedMatches("UPDATE team SET rated_matches = ? WHERE id = ?", rams);
        assertThat(cups.registerTeam(cup, rams, java.util.Arrays.stream(others).boxed().toList(), clock.now))
                .isEqualTo(TournamentRepository.Registration.OK);
        cups.seed(cup, 0, cups.teamsBySeed(cup));
        JsonNode bracket = body(get("/v1/tournaments/" + cup, null)).get("matches").get(0);
        assertThat(List.of(bracket.get("teamA").asLong(), bracket.get("teamB").asLong()))
                .containsExactlyInAnyOrder(tanks, rams);
        assertThat(bracket.has("playerA")).isFalse();
    }

    @Test
    @Timeout(60)
    @DisplayName("a party invitation tells whether a player is in the lobby only to their friend; anyone else is answered as sent (S-16, Q-20)")
    void presenceIsAFriendsToKnow() throws Exception {
        String ada = register("pr-ada");
        String bob = register("pr-bob");
        String cyd = register("pr-cyd");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        long cydId = auth.playerIdOf(cyd);
        post("/v1/friends", "{\"playerId\":" + bobId + "}", ada);
        assertThat(body(post("/v1/friends", "{\"playerId\":" + adaId + "}", bob)).get("state").asText()).isEqualTo("friends");
        expectError(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada), 404, "not_in_lobby");
        assertThat(post("/v1/party/invite", "{\"playerId\":" + cydId + "}", ada).statusCode())
                .as("not a friend: as sent, and lapses unseen").isEqualTo(200);
    }

    @Test
    @Timeout(60)
    @DisplayName("asking others is limited: twenty an hour, and so many out at once, each refused with its own code (S-15, Q-46)")
    void askingIsLimited() throws Exception {
        String ada = register("lim-ada");
        String bob = register("lim-bob");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        String askBob = "{\"playerId\":" + bobId + "}";
        long hour = clock.millis() / 3_600_000;
        jredis.sync().set("rl:ask:" + adaId + ":" + hour, "20");
        expectError(post("/v1/friends", askBob, ada), 429, "too_soon");
        jredis.sync().del("rl:ask:" + adaId + ":" + hour);
        com.backend.persistence.FriendRepository rows = new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100);
        AccountRepository accounts = new AccountRepository(db.dataSource());
        for (int i = 0; i < com.backend.persistence.FriendRepository.MAX_ASKED; i++) {
            long other = accounts.register("lim57-" + i, "lim57-" + i, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
            rows.ask(adaId, other, java.time.Instant.ofEpochMilli(clock.millis()));
        }
        expectError(post("/v1/friends", askBob, ada), 409, "too_many_asked");

        long teamId = body(post("/v1/teams", "{\"name\":\"LimTanks\"}", ada)).get("id").asLong();
        jredis.sync().set("rl:tinv:" + adaId + ":" + hour, "20");
        expectError(post("/v1/teams/mine/invites", askBob, ada), 429, "too_soon");
        jredis.sync().del("rl:tinv:" + adaId + ":" + hour);
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO team_invite (team_id, player_id, invited_by, expires_at) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < com.backend.persistence.TeamRepository.MAX_INVITED; i++) {
                ps.setLong(1, teamId);
                ps.setLong(2, 900_000_000L + i);
                ps.setLong(3, adaId);
                ps.setTimestamp(4, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(clock.millis()).plusSeconds(3_600)));
                ps.executeUpdate();
            }
        }
        expectError(post("/v1/teams/mine/invites", askBob, ada), 409, "too_many_invited");
    }

    @Test
    @Timeout(60)
    @DisplayName("friends: asked, accepted by asking back, each told; online while in the lobby; ended; a block answered and ignored (Q-20)")
    void friends() throws Exception {
        String ada = register("fr-ada");
        String bob = register("fr-bob");
        String cyd = register("fr-cyd");
        long adaId = auth.playerIdOf(ada);
        long bobId = auth.playerIdOf(bob);
        long cydId = auth.playerIdOf(cyd);
        java.util.concurrent.LinkedBlockingQueue<String> pushed = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe("push:gw-f", (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8)))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            jredis.sync().set("conn:" + adaId, "gw-f#x-" + adaId);
            jredis.sync().set("conn:" + bobId, "gw-f#x-" + bobId);           // cyd is not in the lobby

            JsonNode asked = body(post("/v1/friends", "{\"playerId\":" + bobId + "}", ada));
            assertThat(asked.get("state").asText()).isEqualTo("asked");
            JsonNode told = nextPush(pushed);
            assertThat(told.get("to").asLong()).isEqualTo(bobId);
            assertThat(told.at("/msg/t").asText()).isEqualTo("evt.friend.request");
            assertThat(told.at("/msg/d/playerId").asLong()).isEqualTo(adaId);
            assertThat(told.at("/msg/d/name").asText()).isEqualTo("fr-ada");
            JsonNode look = nextPush(pushed);
            assertThat(List.of(look.get("to").asLong(), look.at("/msg/t").asText())).as("and its inbox: look")
                    .containsExactly(bobId, "evt.inbox");
            JsonNode item = body(get("/v1/inbox", bob)).get("items").get(0);
            assertThat(List.of(item.get("kind").asText(), item.get("ref").asText(), item.get("read").asText()))
                    .containsExactly("friend_request", Long.toString(adaId), "false");
            assertThat(post("/v1/inbox/read", "{\"upTo\":" + item.get("id").asLong() + "}", bob).statusCode()).isEqualTo(200);
            assertThat(body(get("/v1/inbox", bob)).get("items").get(0).get("read").asBoolean()).isTrue();
            expectError(post("/v1/inbox/read", "{}", bob), 400, "invalid_body");
            expectError(get("/v1/inbox", null), 401, "no_token");
            JsonNode bobs = body(get("/v1/friends", bob));
            assertThat(bobs.get("requests").get(0).get("playerId").asLong()).isEqualTo(adaId);
            assertThat(body(get("/v1/friends", ada)).get("asked").get(0).get("playerId").asLong()).isEqualTo(bobId);

            assertThat(body(post("/v1/friends", "{\"playerId\":" + adaId + "}", bob)).get("state").asText())
                    .as("asking back accepts").isEqualTo("friends");
            JsonNode accepted = nextPush(pushed);
            assertThat(accepted.get("to").asLong()).isEqualTo(adaId);
            assertThat(accepted.at("/msg/t").asText()).isEqualTo("evt.friend.accepted");
            assertThat(accepted.at("/msg/d/playerId").asLong()).isEqualTo(bobId);
            assertThat(nextPush(pushed).at("/msg/t").asText()).isEqualTo("evt.inbox");

            post("/v1/friends", "{\"playerId\":" + cydId + "}", ada);
            post("/v1/friends", "{\"playerId\":" + adaId + "}", cyd);
            nextPush(pushed);                                             // ada told cyd accepted,
            nextPush(pushed);                                             // and to look
            JsonNode list = body(get("/v1/friends", ada)).get("friends");
            java.util.Map<Long, Boolean> online = new java.util.HashMap<>();
            list.forEach(f -> online.put(f.get("playerId").asLong(), f.get("online").asBoolean()));
            assertThat(online).as("online while in the lobby").containsEntry(bobId, true).containsEntry(cydId, false);

            expectError(post("/v1/friends", "{\"playerId\":" + adaId + "}", ada), 400, "yourself");
            expectError(post("/v1/friends", "{\"playerId\":999999999}", ada), 404, "no_such_player");
            expectError(post("/v1/friends", "{\"playerId\":" + bobId + "}", ada), 409, "already_friends");
            assertThat(delete("/v1/friends/" + cydId, ada).statusCode()).isEqualTo(200);
            expectError(delete("/v1/friends/" + cydId, ada), 404, "not_friends");
            post("/v1/friends", "{\"playerId\":" + cydId + "}", ada);
            expectError(post("/v1/friends", "{\"playerId\":" + cydId + "}", ada), 409, "already_asked");
            assertThat(delete("/v1/friend-requests/" + adaId, cyd).statusCode()).as("declined").isEqualTo(200);
            expectError(delete("/v1/friend-requests/" + adaId, cyd), 404, "no_request");

            // A team invitation is in the inbox too, and pushed as one.
            assertThat(post("/v1/teams", "{\"name\":\"FrTanks\"}", ada).statusCode()).isEqualTo(200);
            assertThat(post("/v1/teams/mine/invites", "{\"playerId\":" + bobId + "}", ada).statusCode()).isEqualTo(200);
            JsonNode invited = nextPush(pushed);
            assertThat(List.of(invited.get("to").asLong(), invited.at("/msg/t").asText())).containsExactly(bobId, "evt.inbox");

            // cyd blocks ada: ada's request is answered as asked, and cyd never sees it.
            assertThat(post("/v1/blocks", "{\"playerId\":" + adaId + "}", cyd).statusCode()).isEqualTo(200);
            assertThat(body(post("/v1/friends", "{\"playerId\":" + cydId + "}", ada)).get("state").asText()).isEqualTo("asked");
            assertThat(body(get("/v1/friends", cyd)).get("requests")).isEmpty();
            assertThat(body(get("/v1/blocks", cyd)).get("blocked").get(0).get("playerId").asLong()).isEqualTo(adaId);
            expectError(post("/v1/blocks", "{\"playerId\":" + cydId + "}", cyd), 400, "yourself");
            assertThat(delete("/v1/blocks/" + adaId, cyd).statusCode()).isEqualTo(200);
            expectError(delete("/v1/blocks/" + adaId, cyd), 404, "not_blocked");

            // bob blocks ada: her party invitation is answered as sent, and never reaches him.
            assertThat(post("/v1/blocks", "{\"playerId\":" + adaId + "}", bob).statusCode()).isEqualTo(200);
            assertThat(body(get("/v1/friends", ada)).get("friends")).as("blocking ended the friendship").isEmpty();
            pushed.clear();
            assertThat(body(post("/v1/friends", "{\"playerId\":" + bobId + "}", ada)).get("state").asText()).isEqualTo("asked");
            assertThat(post("/v1/party/invite", "{\"playerId\":" + bobId + "}", ada).statusCode()).isEqualTo(200);
            assertThat(post("/v1/teams/mine/invites", "{\"playerId\":" + bobId + "}", ada).statusCode())
                    .as("answered as sent").isEqualTo(200);
            assertThat(pushed.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).as("none delivered").isNull();
            expectError(get("/v1/friends", null), 401, "no_token");
        } finally {
            gateway.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a guest: made with nothing asked, back in by its key, upgraded to a full account, the same player (Q-22, D-46)")
    void guests() throws Exception {
        HttpResponse<String> made = post("/v1/guests", null, null);
        assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
        JsonNode guest = body(made);
        long id = guest.get("playerId").asLong();
        String key = guest.get("guestKey").asText();
        assertThat(key).as("32 bytes, base64url").hasSize(43);
        assertThat(guest.get("displayName").asText()).matches("Guest\\d{4}");
        String username;
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT username FROM account WHERE id = ?")) {
            ps.setLong(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                username = rs.getString(1);
            }
        }
        assertThat(username).as("a name the rules forbid, so nobody can take or log in by it").startsWith("~");
        expectError(post("/v1/sessions", "{\"username\":\"" + username + "\",\"password\":\"\"}", null),
                401, "invalid_credentials");

        JsonNode in = body(post("/v1/sessions", "{\"guestKey\":\"" + key + "\"}", null));
        assertThat(in.get("playerId").asLong()).isEqualTo(id);
        String token = in.get("token").asText();
        assertThat(get("/v1/inventory", token).statusCode()).as("a session like any").isEqualTo(200);
        expectError(post("/v1/sessions", "{\"guestKey\":\"not-a-key\"}", null), 401, "invalid_credentials");

        expectError(post("/v1/accounts/upgrade", "{\"username\":\"~no\",\"password\":\"hunter2-hunter2\"}", token),
                400, "invalid_username");
        expectError(post("/v1/accounts/upgrade", "{\"username\":\"gu-ada\",\"password\":\"short\"}", token),
                400, "invalid_password");
        expectError(post("/v1/accounts/upgrade", "{\"username\":\"gu-ada\",\"password\":\"hunter2-hunter2\","
                + "\"displayName\":\"" + "x".repeat(40) + "\"}", token), 400, "invalid_display_name");
        register("gu-taken");
        expectError(post("/v1/accounts/upgrade", "{\"username\":\"gu-taken\",\"password\":\"hunter2-hunter2\"}", token),
                409, "username_taken");
        assertThat(post("/v1/accounts/upgrade", "{\"username\":\"gu-ada\",\"password\":\"hunter2-hunter2\","
                + "\"displayName\":\"Adaline\"}", token).statusCode()).isEqualTo(200);
        expectError(post("/v1/accounts/upgrade", "{\"username\":\"gu-ada2\",\"password\":\"hunter2-hunter2\"}", token),
                409, "not_a_guest");
        JsonNode byName = body(post("/v1/sessions", "{\"username\":\"gu-ada\",\"password\":\"hunter2-hunter2\"}", null));
        assertThat(byName.get("playerId").asLong()).as("the same player").isEqualTo(id);
        assertThat(new AccountRepository(db.dataSource()).findProfile(id).displayName()).isEqualTo("Adaline");
        expectError(post("/v1/sessions", "{\"guestKey\":\"" + key + "\"}", null), 401, "invalid_credentials");
        expectError(post("/v1/accounts/upgrade", "{}", null), 401, "no_token");

        String other = body(post("/v1/guests", null, null)).get("guestKey").asText();
        long otherId = body(post("/v1/sessions", "{\"guestKey\":\"" + other + "\"}", null)).get("playerId").asLong();
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("UPDATE account SET status = 2 WHERE id = ?")) {
            ps.setLong(1, otherId);
            ps.executeUpdate();
        }
        expectError(post("/v1/sessions", "{\"guestKey\":\"" + other + "\"}", null), 403, "banned");
    }

    @Test
    @Timeout(60)
    @DisplayName("a tournament match's grant: the player's own, from the scheduler, while it lasts")
    void tournamentMatchGrant() throws Exception {
        String ada = register("ada");
        String bob = register("bob");
        new com.backend.handoff.TournamentGrants(jredis).put(7, auth.playerIdOf(ada),
                "{\"tournamentId\":7,\"ticketId\":\"t-ada\"}").get(5, java.util.concurrent.TimeUnit.SECONDS);

        JsonNode grant = body(get("/v1/tournaments/7/match", ada));
        assertThat(grant.get("ticketId").asText()).isEqualTo("t-ada");
        assertThat(grant.get("tournamentId").asLong()).isEqualTo(7);
        expectError(get("/v1/tournaments/7/match", bob), 404, "no_match");
        expectError(get("/v1/tournaments/8/match", ada), 404, "no_match");
        expectError(get("/v1/tournaments/7/match", null), 401, "no_token");
        expectError(get("/v1/tournaments/7/match", "not-a-token"), 401, "invalid_session");
        assertThat(post("/v1/tournaments/7/match", "{}", ada).statusCode()).isEqualTo(405);
    }

    private static void expectError(HttpResponse<String> response, int status, String code)
            throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(body(response).get("code").asText()).isEqualTo(code);
    }

    @Test
    @Timeout(60)
    @DisplayName("an id past the largest long names nothing, 404 and no trace; the login routes are matched exactly (S-17, S-18)")
    void idsAndRoutesAreExact() throws Exception {
        String huge = "9999999999999999999";
        expectError(get("/v1/tournaments/" + huge, null), 404, "no_such_route");
        expectError(get("/v1/tournaments/" + huge + "/match", null), 404, "no_such_route");
        String ada = register("ex-ada");
        assertThat(delete("/v1/friends/" + huge, ada).statusCode()).isEqualTo(404);
        assertThat(delete("/v1/friend-requests/" + huge, ada).statusCode()).isEqualTo(404);
        assertThat(delete("/v1/blocks/" + huge, ada).statusCode()).isEqualTo(404);
        assertThat(post("/v1/team-invites/" + huge, "{\"accept\":true}", ada).statusCode()).isEqualTo(404);
        assertThat(post("/v1/teams/mine/members/" + huge + "/role", "{\"role\":\"member\"}", ada).statusCode())
                .isEqualTo(404);
        for (String path : List.of("/v1/sessions/", "/v1/sessions/x", "/v1/accounts/", "/v1/guests/",
                "/v1/accounts/upgrade/")) {
            assertThat(post(path, "{\"username\":\"ex-ada\",\"password\":\"hunter2-hunter2\"}", null).statusCode())
                    .as("%s is not the route nginx limits", path).isEqualTo(404);
        }
    }

    // ---- gems for money (04 §8, revenue, D-68) ------------------------------------------

    private static HttpResponse<String> order(String token, String productId, String key) throws Exception {
        return post("/v1/payments", "{\"productId\":\"%s\",\"key\":\"%s\"}".formatted(productId, key), token);
    }

    private static HttpResponse<String> simulate(String token, String orderId, String outcome) throws Exception {
        return post("/v1/payments/" + orderId + "/simulate", "{\"outcome\":\"" + outcome + "\"}", token);
    }

    @Test
    @Timeout(60)
    @DisplayName("gems for money: the packs; an order pending, the same for a key used; the simulated provider's paid grants once, the first purchase twice; declined grants nothing (04 §8, D-68)")
    void gemsForMoney() throws Exception {
        HttpResponse<String> packs = get("/v1/payments/packs", null);
        assertThat(packs.statusCode()).as(packs.body()).isEqualTo(200);
        assertThat(body(packs).get("packs")).hasSize(2);
        assertThat(List.of(body(packs).at("/packs/1/productId").asText(), body(packs).at("/packs/1/gems").asInt(),
                body(packs).at("/packs/1/priceCents").asInt(), body(packs).at("/packs/1/currency").asText()))
                .containsExactly("gems_500", 500, 499, "USD");

        String ada = register("pay-ada");
        String key = "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a91";
        HttpResponse<String> placed = order(ada, "gems_80", key);
        assertThat(placed.statusCode()).as(placed.body()).isEqualTo(200);
        JsonNode o = body(placed).get("order");
        String id = o.get("orderId").asText();
        assertThat(List.of(o.get("productId").asText(), o.get("gems").asInt(), o.get("bonus").asInt(),
                o.get("priceCents").asInt(), o.get("currency").asText(), o.get("state").asText()))
                .containsExactly("gems_80", 80, 0, 99, "USD", "pending");
        assertThat(Instant.parse(o.get("createdAt").asText())).isEqualTo(clock.now);
        assertThat(body(order(ada, "gems_500", key)).at("/order/orderId").asText()).as("a key used: its order").isEqualTo(id);
        assertThat(body(get("/v1/payments/" + id, ada)).at("/order/state").asText()).isEqualTo("pending");

        HttpResponse<String> paid = simulate(ada, id, "paid");
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(200);
        assertThat(List.of(body(paid).get("confirmed").asBoolean(), body(paid).get("gems").asLong(),
                body(paid).at("/order/state").asText(), body(paid).at("/order/bonus").asInt()))
                .containsExactly(true, 160L, "paid", 80);
        HttpResponse<String> again = simulate(ada, id, "declined");
        assertThat(List.of(again.statusCode(), body(again).get("confirmed").asBoolean(), body(again).get("gems").asLong(),
                body(again).at("/order/state").asText())).as("final").containsExactly(200, false, 160L, "paid");

        String second = body(order(ada, "gems_500", "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a92")).at("/order/orderId").asText();
        HttpResponse<String> declined = simulate(ada, second, "declined");
        assertThat(List.of(body(declined).get("confirmed").asBoolean(), body(declined).get("gems").asLong(),
                body(declined).at("/order/state").asText())).containsExactly(true, 160L, "declined");
        assertThat(body(get("/v1/inventory", ada)).get("gems").asLong()).isEqualTo(160);
    }

    @Test
    @Timeout(60)
    @DisplayName("a payment's refusals: no session, a body or key not right, a product or order not known or another's, an outcome not paid or declined, a refund's debt")
    void paymentRefusals() throws Exception {
        String ada = register("payr-ada");
        String bob = register("payr-bob");
        String key = "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a93";
        String id = body(order(ada, "gems_80", key)).at("/order/orderId").asText();

        assertThat(order(null, "gems_80", key).statusCode()).isEqualTo(401);
        assertThat(get("/v1/payments/" + id, null).statusCode()).isEqualTo(401);
        assertThat(simulate(null, id, "paid").statusCode()).isEqualTo(401);
        assertThat(order("not-a-session", "gems_80", key).statusCode()).isEqualTo(401);
        assertThat(get("/v1/payments/" + id, "not-a-session").statusCode()).isEqualTo(401);
        assertThat(simulate("not-a-session", id, "paid").statusCode()).isEqualTo(401);

        HttpResponse<String> empty = post("/v1/payments", "{}", ada);
        assertThat(List.of(empty.statusCode(), body(empty).get("code").asText())).containsExactly(400, "invalid_body");
        for (String bad : List.of("short-key", key.toUpperCase(java.util.Locale.ROOT), key + "a", "3f2c9a1e_7b4d_4c61_9e0a_5d8b2f6c")) {
            HttpResponse<String> r = order(ada, "gems_80", bad);
            assertThat(List.of(r.statusCode(), body(r).get("code").asText())).as(bad).containsExactly(400, "invalid_key");
        }
        assertThat(order(ada, "gems_80", "3f2c9a1e-7b4d-4c").statusCode()).as("16 is enough").isEqualTo(200);
        HttpResponse<String> unknown = order(ada, "gems_9", "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a94");
        assertThat(List.of(unknown.statusCode(), body(unknown).get("code").asText())).containsExactly(404, "unknown_product");

        for (HttpResponse<String> r : List.of(get("/v1/payments/" + id, bob), simulate(bob, id, "paid"),
                get("/v1/payments/00000000-0000-4000-8000-000000000000", ada))) {
            assertThat(List.of(r.statusCode(), body(r).get("code").asText())).as(r.uri().getPath())
                    .containsExactly(404, "no_such_order");
        }
        HttpResponse<String> maybe = simulate(ada, id, "maybe");
        assertThat(List.of(maybe.statusCode(), body(maybe).get("code").asText())).containsExactly(400, "invalid_outcome");
        assertThat(body(get("/v1/payments/" + id, ada)).at("/order/state").asText()).as("bob's call did nothing").isEqualTo("pending");
        assertThat(get("/v1/payments/" + id + "/x", ada).statusCode()).isEqualTo(404);
        assertThat(get("/v1/payments/not-an-order", ada).statusCode()).isEqualTo(404);
        assertThat(delete("/v1/payments", ada).statusCode()).isEqualTo(405);
        assertThat(get("/v1/payments/" + id + "/simulate", ada).statusCode()).isEqualTo(405);
        assertThat(post("/v1/payments/packs", "{}", ada).statusCode()).isEqualTo(405);

        sqlUpdate("UPDATE payment_order SET state = ?, debt = 5 WHERE id = ?", com.backend.persistence.PaymentRepository.REFUNDED, id);
        HttpResponse<String> owed = order(ada, "gems_80", "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a95");
        assertThat(List.of(owed.statusCode(), body(owed).get("code").asText())).containsExactly(409, "refund_debt");
        assertThat(order(ada, "gems_80", key).statusCode()).as("a key used before the debt: its order").isEqualTo(200);
    }

    @Test
    @Timeout(60)
    @DisplayName("no provider named: every payment route answers 503 payments_off, and nothing is sold (D-68)")
    void paymentsOff() throws Exception {
        String ada = register("payoff-ada");
        PlatformHttpServer off = serverWith(new com.backend.platform.PaymentService(auth,
                new com.backend.persistence.PaymentRepository(db.dataSource()), com.backend.platform.Packs.read(
                new java.io.ByteArrayInputStream(PACKS.getBytes(StandardCharsets.UTF_8))), null, clock),
                new AccountRepository(db.dataSource()));
        String offBase = "http://127.0.0.1:" + off.start();
        try {
            String key = "3f2c9a1e-7b4d-4c61-9e0a-5d8b2f6c1a96";
            for (HttpRequest r : List.of(
                    HttpRequest.newBuilder(URI.create(offBase + "/v1/payments/packs")).GET().build(),
                    HttpRequest.newBuilder(URI.create(offBase + "/v1/payments")).header("Authorization", "Bearer " + ada)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"productId\":\"gems_80\",\"key\":\"" + key + "\"}")).build(),
                    HttpRequest.newBuilder(URI.create(offBase + "/v1/payments/00000000-0000-4000-8000-000000000000"))
                            .header("Authorization", "Bearer " + ada).GET().build(),
                    HttpRequest.newBuilder(URI.create(offBase + "/v1/payments/00000000-0000-4000-8000-000000000000/simulate"))
                            .header("Authorization", "Bearer " + ada)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"outcome\":\"paid\"}")).build())) {
                HttpResponse<String> answer = send(r);
                assertThat(List.of(answer.statusCode(), body(answer).get("code").asText())).as(r.uri().getPath())
                        .containsExactly(503, "payments_off");
            }
            assertThat(order(ada, "gems_80", key).statusCode()).as("the same database, a provider named").isEqualTo(200);
        } finally {
            off.close();
        }
    }

    // ---- the season pass (04 §8, revenue (b), D-69) ------------------------------------

    /** Gems given through the ledger, as a reward would: a row and the balance it leaves. */
    private static void giveGems(long playerId, int gems) throws Exception {
        sqlUpdate("INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key)"
                + " SELECT id, 1, ?, gems + ?, 5, 'test', CONCAT('test:', id, ':', UUID()) FROM player WHERE id = ?", gems, gems, playerId);
        sqlUpdate("UPDATE player SET gems = gems + ? WHERE id = ?", gems, playerId);
    }

    @Test
    @Timeout(60)
    @DisplayName("the season pass: the season being played's, its forty tiers listed; premium bought for 500 gems pays the tiers reached, once; refused short of gems or once the season has ended (04 §8, D-69)")
    void seasonPass() throws Exception {
        String ada = register("sp-ada");
        long adaId = auth.playerIdOf(ada);
        HttpResponse<String> got = get("/v1/pass", ada);
        assertThat(got.statusCode()).as(got.body()).isEqualTo(200);
        JsonNode pass = body(got);
        assertThat(List.of(pass.get("season").asInt(), pass.get("points").asLong(), pass.get("tier").asInt(),
                pass.get("premium").asBoolean(), pass.get("premiumGems").asInt(), pass.get("tierPoints").asInt()))
                .containsExactly(1, 0L, 0, false, 500, 250);
        assertThat(pass.get("endsAt").asText()).isEqualTo(
                new com.backend.persistence.SeasonRepository(db.dataSource()).current().endsAt().toString());
        assertThat(pass.get("tiers")).hasSize(40);
        JsonNode first = pass.at("/tiers/0");
        assertThat(List.of(first.get("tier").asInt(), first.at("/free/coins").asLong(), first.at("/free/gems").asInt(),
                first.at("/free/itemId").isNull(), first.at("/premium/gems").asInt(), first.at("/premium/itemId").isNull()))
                .containsExactly(1, 150L, 0, true, 15, true);
        JsonNode fifth = pass.at("/tiers/4");
        assertThat(List.of(fifth.at("/free/coins").asLong(), fifth.at("/free/gems").asInt(), fifth.at("/premium/gems").asInt(),
                fifth.at("/premium/itemId").asText())).containsExactly(0L, 5, 15, "boost_xp_hour");

        HttpResponse<String> poor = post("/v1/pass/premium", null, ada);
        assertThat(List.of(poor.statusCode(), body(poor).get("code").asText())).containsExactly(409, "insufficient_funds");
        sqlUpdate("INSERT INTO season_pass (player_id, season_id, points) VALUES (?, 1, 300)", adaId);
        giveGems(adaId, 1_000);
        HttpResponse<String> bought = post("/v1/pass/premium", null, ada);
        assertThat(bought.statusCode()).as(bought.body()).isEqualTo(200);
        assertThat(List.of(body(bought).get("result").asText(), body(bought).get("gems").asLong(),
                body(bought).at("/pass/premium").asBoolean(), body(bought).at("/pass/tier").asInt()))
                .as("1 000 less 500, and the first premium tier's 15").containsExactly("bought", 515L, true, 1);
        HttpResponse<String> again = post("/v1/pass/premium", null, ada);
        assertThat(List.of(again.statusCode(), body(again).get("result").asText(), body(again).get("gems").asLong()))
                .as("once").containsExactly(200, "already_bought", 515L);
        assertThat(body(get("/v1/pass", ada)).get("premium").asBoolean()).isTrue();

        String bob = register("sp-bob");
        giveGems(auth.playerIdOf(bob), 600);
        sqlUpdate("UPDATE season SET ends_at = ? WHERE id = 1", java.sql.Timestamp.from(clock.now.minusSeconds(3_600)));
        HttpResponse<String> late = post("/v1/pass/premium", null, bob);
        assertThat(List.of(late.statusCode(), body(late).get("code").asText())).containsExactly(409, "season_ended");

        assertThat(get("/v1/pass", null).statusCode()).isEqualTo(401);
        assertThat(get("/v1/pass", "not-a-session").statusCode()).isEqualTo(401);
        assertThat(post("/v1/pass/premium", null, null).statusCode()).isEqualTo(401);
        assertThat(post("/v1/pass/premium", null, "not-a-session").statusCode()).isEqualTo(401);
        assertThat(delete("/v1/pass", ada).statusCode()).isEqualTo(405);
        assertThat(get("/v1/pass/premium", ada).statusCode()).isEqualTo(405);
        assertThat(get("/v1/pass/x", ada).statusCode()).isEqualTo(404);
    }

    // ---- looks: tank skins (04 §8, revenue (c), D-70) ------------------------------------

    @Test
    @Timeout(60)
    @DisplayName("a skin: worn in its own slot, giving nothing, raised by nothing; it rides in the ticket and the queue entry; the skins served by number (04 §8, D-70)")
    void aSkinIsWornAndRidesInTheTicket() throws Exception {
        String token = register("sk-ada");
        long ada = auth.playerIdOf(token);
        expectError(put("/v1/equipment/skin", "{\"itemId\":\"skin_test\"}", token), 409, "not_owned");
        hold(ada, "skin_test", 1);
        HttpResponse<String> worn = put("/v1/equipment/skin", "{\"itemId\":\"skin_test\"}", token);
        assertThat(worn.statusCode()).as(worn.body()).isEqualTo(200);
        assertThat(body(worn).at("/slots/skin").asText()).isEqualTo("skin_test");
        assertThat(body(worn).get("bonus").size()).as("a look gives nothing").isZero();
        expectError(put("/v1/equipment/barrel", "{\"itemId\":\"skin_test\"}", token), 409, "wrong_slot");
        expectError(put("/v1/equipment/skin", "{\"itemId\":\"barrel_big\"}", token), 409, "wrong_slot");
        expectError(post("/v1/inventory/skin_test/level", "{\"key\":\"level-raise-skin-01\"}", token), 400, "not_equipment");
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150)).get();

        String ticketId = body(post("/v1/match-requests", null, token)).get("ticketId").asText();
        com.backend.handoff.Ticket ticket = new TicketStore(jredis).claim(ticketId).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(List.of(ticket.skin(), ticket.bonus())).as("its number, and no bonus").containsExactly(7, "");
        assertThat(post("/v1/queue", "{\"mode\":\"duel\"}", token).statusCode()).isEqualTo(200);
        assertThat(jredis.sync().hget("mmp:" + ada, "skin")).as("read with the bonus, when queued").isEqualTo("7");

        String bob = register("sk-bob");
        long bobId = auth.playerIdOf(bob);
        hold(bobId, "skin_test", 1);
        put("/v1/equipment/skin", "{\"itemId\":\"skin_test\"}", bob);
        hold(bobId, "skin_test", 0);
        String bobTicket = body(post("/v1/match-requests", null, bob)).get("ticketId").asText();
        assertThat(new TicketStore(jredis).claim(bobTicket).get(5, java.util.concurrent.TimeUnit.SECONDS).skin())
                .as("worn and no longer held: none, as a bonus").isZero();
        assertThat(body(delete("/v1/equipment/skin", bob)).at("/slots/skin").isNull()).as("taken off").isTrue();

        HttpResponse<String> skins = get("/v1/content/skins", null);
        assertThat(skins.statusCode()).isEqualTo(200);
        assertThat(body(skins).get("skins")).hasSize(1);
        assertThat(List.of(body(skins).at("/skins/0/skin").asInt(), body(skins).at("/skins/0/itemId").asText()))
                .containsExactly(7, "skin_test");
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update("[{\"skin\":7,\"itemId\":\"skin_test\"}]".getBytes(StandardCharsets.UTF_8));
        assertThat(body(skins).get("version").asLong()).as("its content's CRC32, as the class table's").isEqualTo(crc.getValue());
        String etag = skins.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + body(skins).get("version").asLong() + "\"");
        HttpResponse<String> again = send(HttpRequest.newBuilder(URI.create(base + "/v1/content/skins"))
                .header("If-None-Match", etag).GET().build());
        assertThat(again.statusCode()).as("the table held").isEqualTo(304);
    }
}
