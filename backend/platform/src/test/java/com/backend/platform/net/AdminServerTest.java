package com.backend.platform.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.backend.common.RefusedConfiguration;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.SessionStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AdminRepository;
import com.backend.persistence.Database;
import com.backend.platform.AuthService;
import com.backend.platform.PasswordHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The admin API's first slice (04 §10): on loopback, behind a secret, every call audited (D-30). */
@Timeout(60)
class AdminServerTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "backend-dev-password");
    private static final String SECRET = "an-operator-secret-of-some-length";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Database db;
    private static JRedisEmbedded store;
    private static JRedisClient jredis;
    private static AuthService auth;
    private static SessionStore sessions;
    private static AdminServer admin;
    private static String base;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    @BeforeAll
    static void start() throws Exception {
        db = new Database(URL, USER, PASSWORD, 4);
        db.resetForTests();
        store = JRedisEmbedded.start();
        jredis = store.newClient();
        sessions = new SessionStore(jredis);
        auth = new AuthService(new AccountRepository(db.dataSource()), new PasswordHasher(), sessions);
        Path secret = Files.writeString(dir.resolve("admin-token"), SECRET + "\n");
        admin = AdminServer.startIfConfigured(Map.of("BACKEND_ADMIN_ADDR", "127.0.0.1:0",
                        "BACKEND_ADMIN_TOKEN_FILE", secret.toString()),
                new ArenaDirectory(jredis), new AdminRepository(db.dataSource()), sessions, new LobbyPush(jredis),
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()),
                new com.backend.persistence.SeasonRepository(db.dataSource()), java.time.Clock.systemUTC());
        base = "http://127.0.0.1:" + admin.port();
    }

    @AfterAll
    static void stop() {
        if (admin != null) {
            admin.close();
        }
        jredis.close();
        store.close();
        db.close();
    }

    private static HttpResponse<String> call(String method, String path, String body, String secret) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (secret != null) {
            b.header("Authorization", "Bearer " + secret);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long register(String name) throws Exception {
        return auth.register(name, name, "hunter2-hunter2".toCharArray()).playerId();
    }

    /** The audit's rows for a target, oldest first: action and outcome. */
    private static List<String> audited(String target) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT action, outcome FROM admin_audit WHERE target <=> ? ORDER BY id")) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " " + rs.getString(2));
                }
            }
        }
        return rows;
    }

    @Test
    @DisplayName("a refusal with a long path is audited, its target cut to the column; not answered 503 and lost (D-30)")
    void aLongRefusedPathIsAudited() throws Exception {
        String path = "/admin/" + "x".repeat(200);
        HttpResponse<String> refused = call("GET", path, null, "not-the-secret");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(401);
        assertThat(audited(path.substring(0, 128))).containsExactly("refused unauthorised");
    }

    @Test
    @DisplayName("each call's method is checked, the seasons list's and the tournaments' too; the seasons list audited (04 §10)")
    void everyCallChecksItsMethod() throws Exception {
        assertThat(call("POST", "/admin/seasons", "{}", SECRET).statusCode()).isEqualTo(405);
        assertThat(call("GET", "/admin/tournaments", null, SECRET).statusCode()).isEqualTo(405);
        assertThat(call("GET", "/admin/seasons", null, SECRET).statusCode()).isEqualTo(200);
        assertThat(audited("seasons")).contains("seasons ok");
    }

    @Test
    @DisplayName("a suspension's end is in the future: one in the past is refused, not taken for a kick (04 §10)")
    void aSuspensionEndsInTheFuture() throws Exception {
        long id = register("adm-past");
        HttpResponse<String> past = call("POST", "/admin/players/" + id + "/ban",
                "{\"reason\":\"a typo of a year\",\"until\":\"2025-10-01T00:00:00Z\"}", SECRET);
        assertThat(past.statusCode()).as(past.body()).isEqualTo(400);
        assertThat(JSON.readTree(past.body()).get("code").asText()).isEqualTo("bad_until");
        assertThat(audited(Long.toString(id))).containsExactly("ban refused_bad_until");
    }

    @Test
    @DisplayName("a body past 4 KB is not read: refused as a call without a reason, whatever it starts with (S-19)")
    void aBodyPastItsLimitIsNotRead() throws Exception {
        String body = "{\"reason\":\"long\"}" + " ".repeat(5_000);       // cut at 4 KB, it would still parse
        HttpResponse<String> answer = call("POST", "/admin/seasons/end", body, SECRET);
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(400);
        assertThat(JSON.readTree(answer.body()).get("code").asText()).isEqualTo("no_reason");
    }

    @Test
    @DisplayName("a ban whose sessions cannot be ended is said so, 503 sessions_not_ended: recorded, and the call can be made again (04 §10)")
    void aBanWithTheStoreDownSaysSo() throws Exception {
        long id = register("adm-down");
        JRedisEmbedded gone = JRedisEmbedded.start();
        JRedisClient dead = gone.newClient();
        gone.close();
        Path secret = Files.writeString(dir.resolve("admin-token-2"), SECRET + "\n");
        try (AdminServer down = AdminServer.startIfConfigured(Map.of("BACKEND_ADMIN_ADDR", "127.0.0.1:0",
                        "BACKEND_ADMIN_TOKEN_FILE", secret.toString()),
                new ArenaDirectory(dead), new AdminRepository(db.dataSource()), new SessionStore(dead), new LobbyPush(dead),
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()),
                new com.backend.persistence.SeasonRepository(db.dataSource()), java.time.Clock.systemUTC())) {
            HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + down.port()
                            + "/admin/players/" + id + "/ban")).header("Authorization", "Bearer " + SECRET)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"cheating\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(503);
            assertThat(JSON.readTree(answer.body()).get("code").asText()).isEqualTo("sessions_not_ended");
        } finally {
            dead.close();
        }
        assertThat(audited(Long.toString(id))).as("the ban recorded").containsExactly("ban banned");
    }

    /** An admin listener whose arenas, and sessions unless given others, are in a store that has gone. */
    private static AdminServer withStoreGone(JRedisClient dead, SessionStore sessionStore, String file) throws Exception {
        Path secret = Files.writeString(dir.resolve(file), SECRET + "\n");
        return AdminServer.startIfConfigured(Map.of("BACKEND_ADMIN_ADDR", "127.0.0.1:0",
                        "BACKEND_ADMIN_TOKEN_FILE", secret.toString()),
                new ArenaDirectory(dead), new AdminRepository(db.dataSource()), sessionStore, new LobbyPush(dead),
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()),
                new com.backend.persistence.SeasonRepository(db.dataSource()), java.time.Clock.systemUTC());
    }

    private static HttpResponse<String> callOn(AdminServer server, String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer " + SECRET)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a call whose store does not answer is 503 storage_unavailable, to be made again; not 500 with the exception's text (O-36)")
    void aStoreThatFailsIs503() throws Exception {
        long id = register("adm-store-gone");
        JRedisEmbedded gone = JRedisEmbedded.start();
        JRedisClient dead = gone.newClient();
        gone.close();
        try (AdminServer down = withStoreGone(dead, new SessionStore(dead), "admin-token-store")) {
            for (String[] c : new String[][] {
                    {"GET", "/admin/arenas", null},
                    {"GET", "/admin/rooms", null},
                    {"POST", "/admin/players/" + id + "/kick", "{\"reason\":\"test\"}"},
                    {"POST", "/admin/notice", "{\"text\":\"hello\",\"reason\":\"test\"}"},
                    {"POST", "/admin/rooms/arena-1/room-1/close", "{\"reason\":\"test\"}"}}) {
                HttpResponse<String> answer = callOn(down, c[0], c[1], c[2]);
                assertThat(answer.statusCode()).as(c[1] + ": " + answer.body()).isEqualTo(503);
                assertThat(JSON.readTree(answer.body()).get("code").asText()).isEqualTo("storage_unavailable");
                assertThat(JSON.readTree(answer.body()).get("message").asText())
                        .isEqualTo("the store did not answer: call again");
            }
        } finally {
            dead.close();
        }
    }

    @Test
    @DisplayName("a ban whose sessions are ended but whose player cannot be taken out of the arenas says what was done, 503 not_taken_out (O-36)")
    void aBanNotTakenOutSaysWhatWasDone() throws Exception {
        long id = register("adm-not-out");
        String token = auth.login("adm-not-out", "hunter2-hunter2".toCharArray()).token();
        JRedisEmbedded gone = JRedisEmbedded.start();
        JRedisClient dead = gone.newClient();
        gone.close();
        try (AdminServer down = withStoreGone(dead, sessions, "admin-token-not-out")) {      // the sessions' store is up
            HttpResponse<String> answer = callOn(down, "POST", "/admin/players/" + id + "/ban", "{\"reason\":\"cheating\"}");
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(503);
            assertThat(JSON.readTree(answer.body()).get("code").asText()).isEqualTo("not_taken_out");
        } finally {
            dead.close();
        }
        assertThat(auth.playerIdOf(token)).as("the sessions ended").isNegative();
        assertThat(audited(Long.toString(id))).as("the ban recorded").containsExactly("ban banned");
    }

    @Test
    @DisplayName("an operator ends the current season now, for a drill or to bring the calendar into line; the call audited (04 §7)")
    void endsTheSeason() throws Exception {
        HttpResponse<String> listed = call("GET", "/admin/seasons", null, SECRET);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(listed.body()).at("/seasons/0/id").asInt()).isEqualTo(1);
        assertThat(json.readTree(listed.body()).at("/seasons/0/placedAt").isNull()).isTrue();

        assertThat(call("POST", "/admin/seasons/end", "{}", SECRET).statusCode()).as("no reason").isEqualTo(400);
        java.time.Instant before = java.time.Instant.now();
        HttpResponse<String> ended = call("POST", "/admin/seasons/end", "{\"reason\":\"a drill\"}", SECRET);
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        var answer = json.readTree(ended.body());
        assertThat(answer.get("season").asInt()).isEqualTo(1);
        assertThat(java.time.Instant.parse(answer.get("endsAt").asText())).isBetween(before.minusSeconds(1), java.time.Instant.now());
        assertThat(new com.backend.persistence.SeasonRepository(db.dataSource()).current().endsAt())
                .isEqualTo(java.time.Instant.parse(answer.get("endsAt").asText()));
        assertThat(audited("season 1")).containsExactly("season refused_no_reason", "season ended");
        HttpResponse<String> again = call("POST", "/admin/seasons/end", "{\"reason\":\"again\"}", SECRET);
        assertThat(again.statusCode()).as("ended already, the worker closing it").isEqualTo(409);
        assertThat(call("GET", "/admin/seasons/end", null, SECRET).statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("an operator creates a tournament, each field checked, the call audited (04 §6)")
    void createsATournament() throws Exception {
        String ok = "{\"reason\":\"the first cup\",\"name\":\"Autumn Cup\",\"maxEntries\":8,"
                + "\"registrationEnds\":\"2099-01-01T00:00:00Z\",\"startsAt\":\"2099-01-01T01:00:00Z\","
                + "\"roundMinutes\":5,\"prizes\":[1000,500,250]}";
        HttpResponse<String> made = call("POST", "/admin/tournaments", ok, SECRET);
        assertThat(made.statusCode()).as(made.body()).isEqualTo(200);
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(made.body()).get("id").asLong();
        var t = new com.backend.persistence.TournamentRepository(db.dataSource()).get(id);
        assertThat(t.name()).isEqualTo("Autumn Cup");
        assertThat(t.state()).isEqualTo(com.backend.persistence.TournamentRepository.REGISTRATION);
        assertThat(List.of(t.maxEntries(), t.roundMinutes())).containsExactly(8, 5);
        assertThat(List.of(t.prize1(), t.prize2(), t.prize3())).containsExactly(1_000L, 500L, 250L);
        assertThat(t.startsAt()).isEqualTo(java.time.Instant.parse("2099-01-01T01:00:00Z"));
        assertThat(audited("tournament " + id)).containsExactly("tournament created");
        assertThat(t.mode()).as("duels, unless said").isEqualTo(com.backend.persistence.MatchResultRepository.MODE_DUEL);
        HttpResponse<String> teams = call("POST", "/admin/tournaments", ok.replace("\"roundMinutes\":5",
                "\"roundMinutes\":5,\"mode\":\"teams\""), SECRET);
        assertThat(teams.statusCode()).as(teams.body()).isEqualTo(200);
        long teamsId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(teams.body()).get("id").asLong();
        assertThat(new com.backend.persistence.TournamentRepository(db.dataSource()).get(teamsId).mode())
                .as("a teams' tournament (Q-19)").isEqualTo(com.backend.persistence.MatchResultRepository.MODE_TEAMS);
        assertThat(t.format()).as("an elimination, unless said").isEqualTo(com.backend.persistence.TournamentRepository.ELIMINATION);
        HttpResponse<String> league = call("POST", "/admin/tournaments", ok.replace("\"roundMinutes\":5",
                "\"roundMinutes\":5,\"format\":\"round_robin\""), SECRET);
        assertThat(league.statusCode()).as(league.body()).isEqualTo(200);
        long leagueId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(league.body()).get("id").asLong();
        assertThat(new com.backend.persistence.TournamentRepository(db.dataSource()).get(leagueId).format())
                .as("a round robin (04 §6, plan item 66)").isEqualTo(com.backend.persistence.TournamentRepository.ROUND_ROBIN);

        for (String bad : List.of(ok.replace("\"maxEntries\":8", "\"maxEntries\":1"),
                ok.replace("\"maxEntries\":8", "\"maxEntries\":33"),
                ok.replace("\"startsAt\":\"2099-01-01T01:00:00Z\"", "\"startsAt\":\"2098-12-31T23:00:00Z\""),
                ok.replace("\"registrationEnds\":\"2099-01-01T00:00:00Z\"", "\"registrationEnds\":\"2020-01-01T00:00:00Z\""),
                ok.replace("\"roundMinutes\":5", "\"roundMinutes\":0"),
                ok.replace("[1000,500,250]", "[1000,500]"),
                ok.replace("[1000,500,250]", "[1000,-1,250]"),
                ok.replace("\"name\":\"Autumn Cup\"", "\"name\":\"  \""),
                ok.replace("\"roundMinutes\":5", "\"roundMinutes\":5,\"mode\":\"rffa\""),
                ok.replace("\"roundMinutes\":5", "\"roundMinutes\":5,\"format\":\"swiss\""),
                ok.replace("\"maxEntries\":8", "\"maxEntries\":9,\"format\":\"round_robin\""))) {
            HttpResponse<String> refused = call("POST", "/admin/tournaments", bad, SECRET);
            assertThat(refused.statusCode()).as(bad).isEqualTo(400);
            assertThat(refused.body()).contains("invalid_tournament");
        }
        assertThat(call("POST", "/admin/tournaments", ok.replace("\"reason\":\"the first cup\",", ""), SECRET).body())
                .contains("no_reason");
    }

    private static int status(long playerId) throws Exception {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM account WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("a notice goes once to every gateway, for every player in the lobby; each refusal answered, the call audited (04 §10)")
    void aNoticeIsBroadcast() throws Exception {
        java.util.concurrent.LinkedBlockingQueue<String> heard = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe(LobbyPush.ALL_CHANNEL,
                    (ch, msg) -> heard.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            HttpResponse<String> sent = call("POST", "/admin/notice",
                    "{\"text\":\"Back in five minutes\",\"reason\":\"a deploy\"}", SECRET);
            assertThat(sent.statusCode()).isEqualTo(202);
            assertThat(JSON.readTree(sent.body()).get("gateways").asLong()).isEqualTo(1);
            JsonNode env = JSON.readTree(heard.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(env.get("to").asLong()).as("to everyone").isZero();
            assertThat(env.at("/msg/t").asText()).isEqualTo("evt.notice");
            assertThat(env.at("/msg/d/text").asText()).isEqualTo("Back in five minutes");
            assertThat(audited(null)).contains("notice sent");

            for (String bad : new String[] {"{\"reason\":\"x\"}", "{\"text\":\"  \",\"reason\":\"x\"}",
                    "{\"text\":\"" + "a".repeat(201) + "\",\"reason\":\"x\"}"}) {
                HttpResponse<String> refused = call("POST", "/admin/notice", bad, SECRET);
                assertThat(refused.statusCode()).as(bad).isEqualTo(400);
                assertThat(JSON.readTree(refused.body()).get("code").asText()).isEqualTo("invalid_text");
            }
            HttpResponse<String> noReason = call("POST", "/admin/notice", "{\"text\":\"hello\"}", SECRET);
            assertThat(JSON.readTree(noReason.body()).get("code").asText()).isEqualTo("no_reason");
            assertThat(call("GET", "/admin/notice", null, SECRET).statusCode()).isEqualTo(405);
            assertThat(heard.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).as("nothing refused went out").isNull();
            assertThat(call("POST", "/admin/notice", "{\"text\":\"" + "a".repeat(200) + "\",\"reason\":\"x\"}", SECRET)
                    .statusCode()).as("two hundred is allowed").isEqualTo(202);
        } finally {
            gateway.close();
        }
    }

    @Test
    @DisplayName("without the secret, or with another, nothing is done, and the attempt is audited")
    void theSecretIsRequired() throws Exception {
        assertThat(call("GET", "/admin/arenas", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/admin/arenas", null, SECRET + "x").statusCode()).isEqualTo(401);
        assertThat(audited("/admin/arenas")).contains("refused unauthorised");
    }

    @Test
    @DisplayName("the arenas as the directory has them")
    void arenasAreListed() throws Exception {
        new ArenaDirectory(jredis).announce(new ArenaDirectory.Endpoint("arena-9", "10.0.0.9", 9001, 12, 150, true, 2, 4))
                .get(5, TimeUnit.SECONDS);
        HttpResponse<String> r = call("GET", "/admin/arenas", null, SECRET);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode arena = null;
        for (JsonNode a : JSON.readTree(r.body())) {
            if ("arena-9".equals(a.get("name").asText())) {
                arena = a;
            }
        }
        assertThat(arena).isNotNull();
        assertThat(arena.get("players").asInt()).isEqualTo(12);
        assertThat(arena.get("rooms").asInt()).isEqualTo(2);
        assertThat(arena.get("tls").asBoolean()).isTrue();
        assertThat(audited(null)).contains("arenas ok");
    }

    @Test
    @DisplayName("whether players come back, by day, today so far first, the days asked for checked (05 §11, Q-24)")
    void stats() throws Exception {
        long ada = new AccountRepository(db.dataSource()).register("st-ada", "st-ada",
                "$argon2id$fake".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        try (java.sql.Connection c = db.dataSource().getConnection()) {
            try (java.sql.PreparedStatement ps = c.prepareStatement("UPDATE player SET first_played_on = ? WHERE id = ?")) {
                ps.setString(1, today);
                ps.setLong(2, ada);
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO player_day (day, player_id) VALUES (?, ?)")) {
                ps.setString(1, today);
                ps.setLong(2, ada);
                ps.executeUpdate();
            }
        }
        HttpResponse<String> r = call("GET", "/admin/stats?days=2", null, SECRET);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode days = JSON.readTree(r.body()).get("days");
        assertThat(days).hasSize(2);
        assertThat(days.get(0).get("day").asText()).isEqualTo(today);
        assertThat(days.get(0).get("active").asLong()).isEqualTo(1);
        assertThat(days.get(0).get("newPlayers").asLong()).isEqualTo(1);
        assertThat(days.get(0).get("d1").isNull()).as("tomorrow has not ended").isTrue();
        assertThat(days.get(1).get("active").asLong()).isZero();
        assertThat(days.get(1).get("d1").isNull()).as("yesterday's day 1 is today").isTrue();
        assertThat(JSON.readTree(call("GET", "/admin/stats", null, SECRET).body()).get("days")).as("14 by default").hasSize(14);
        for (String bad : List.of("0", "61", "x")) {
            HttpResponse<String> refused = call("GET", "/admin/stats?days=" + bad, null, SECRET);
            assertThat(refused.statusCode()).as(bad).isEqualTo(400);
            assertThat(JSON.readTree(refused.body()).get("code").asText()).isEqualTo("invalid_days");
        }
        assertThat(audited(null)).contains("stats ok");
    }

    @Test
    @DisplayName("the funnel by the day accounts were made, and the guests measured; each read audited (05 §11, plan item 76 (c))")
    void funnelAndGuests() throws Exception {
        long ada = register("funnel-ada");
        HttpResponse<String> funnel = call("GET", "/admin/stats/funnel?days=3", null, SECRET);
        assertThat(funnel.statusCode()).as(funnel.body()).isEqualTo(200);
        JsonNode days = JSON.readTree(funnel.body()).get("days");
        assertThat(days).hasSize(3);
        JsonNode today = days.get(0);
        assertThat(today.get("day").asText()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        assertThat(today.get("registered").asLong()).as("ada among today's").isPositive();
        var counted = new com.backend.persistence.StatsRepository(db.dataSource())
                .funnel(java.time.LocalDate.now(java.time.ZoneOffset.UTC), 1).get(0);
        assertThat(List.of(today.get("registered").asLong(), today.get("guests").asLong(), today.get("played").asLong(),
                today.get("returned").asLong(), today.get("level5").asLong(), today.get("rated").asLong(),
                today.get("bought").asLong(), today.get("paid").asLong())).as("each step as counted, by its name")
                .containsExactly(counted.registered(), counted.guests(), counted.played(), counted.returned(), counted.level5(),
                        counted.rated(), counted.bought(), counted.paid());
        JsonNode named = AdminServer.funnelDay(new com.backend.persistence.StatsRepository.Funnel(
                java.time.LocalDate.parse("2026-10-04"), 1, 2, 3, 4, 5, 6, 7, 8));
        assertThat(List.of(named.get("registered").asLong(), named.get("guests").asLong(), named.get("played").asLong(),
                named.get("returned").asLong(), named.get("level5").asLong(), named.get("rated").asLong(),
                named.get("bought").asLong(), named.get("paid").asLong())).as("each step under its own name")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(call("GET", "/admin/stats/funnel?days=61", null, SECRET).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/admin/stats/funnel", "{}", SECRET).statusCode()).isEqualTo(405);
        assertThat(audited("funnel")).contains("stats ok");

        HttpResponse<String> guests = call("GET", "/admin/stats/guests", null, SECRET);
        assertThat(guests.statusCode()).as(guests.body()).isEqualTo(200);
        JsonNode g = JSON.readTree(guests.body());
        assertThat(g.get("players").asLong()).isPositive();
        assertThat(g.get("guests").asLong()).isNotNegative();
        assertThat(g.get("inactive").asLong()).isNotNegative();
        assertThat(call("POST", "/admin/stats/guests", "{}", SECRET).statusCode()).isEqualTo(405);
        assertThat(audited("guests")).contains("stats ok");
        assertThat(ada).isPositive();
    }

    @Test
    @DisplayName("each day's new players by what they did that first day (05 §11, Q-26)")
    void statsByFeature() throws Exception {
        long bob = new AccountRepository(db.dataSource()).register("sf-bob", "sf-bob",
                "$argon2id$fake".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        try (java.sql.Connection c = db.dataSource().getConnection()) {
            // bob: new two days ago, when he bought something, and back yesterday
            java.time.LocalDate first = today.minusDays(2);
            try (java.sql.PreparedStatement ps = c.prepareStatement("UPDATE player SET first_played_on = ? WHERE id = ?")) {
                ps.setObject(1, first);
                ps.setLong(2, bob);
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO player_day (day, player_id) VALUES (?, ?), (?, ?)")) {
                ps.setObject(1, first);
                ps.setLong(2, bob);
                ps.setObject(3, today.minusDays(1));
                ps.setLong(4, bob);
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO ledger (player_id, currency, delta,"
                    + " balance_after, reason, ref, idem_key, created_at) VALUES (?, 0, -5, 0, 1, 'x', 'sf-buy', ?)")) {
                ps.setLong(1, bob);
                ps.setObject(2, first.atTime(12, 0));
                ps.executeUpdate();
            }
        }
        HttpResponse<String> r = call("GET", "/admin/stats/features?days=3", null, SECRET);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode days = JSON.readTree(r.body()).get("days");
        assertThat(days).hasSize(3);
        assertThat(days.get(0).get("day").asText()).as("newest first").isEqualTo(today.toString());
        JsonNode day = days.get(2);
        assertThat(day.get("day").asText()).isEqualTo(today.minusDays(2).toString());
        assertThat(day.get("newPlayers").asLong()).isEqualTo(1);
        java.util.List<String> names = new java.util.ArrayList<>();
        day.get("features").fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("queued", "bought", "boosted", "tournament", "friend", "team");
        JsonNode bought = day.get("features").get("bought");
        assertThat(bought.get("players").asLong()).isEqualTo(1);
        assertThat(bought.get("d1").asLong()).as("back yesterday, day 1").isEqualTo(1);
        assertThat(bought.get("d7").isNull()).as("day 7 has not ended").isTrue();
        assertThat(day.get("features").get("team").get("players").asLong()).isZero();
        HttpResponse<String> refused = call("GET", "/admin/stats/features?days=61", null, SECRET);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(refused.body()).get("code").asText()).isEqualTo("invalid_days");
        assertThat(audited("features")).contains("stats ok");
    }

    @Test
    @DisplayName("a ban ends every session, closes the lobby with evt.session.revoked, refuses the next login, and is audited")
    void aBanEndsEverything() throws Exception {
        long id = register("ban-ada");
        String one = sessions.create(id).get(5, TimeUnit.SECONDS);
        String two = sessions.create(id).get(5, TimeUnit.SECONDS);
        LinkedBlockingQueue<String> pushed = new LinkedBlockingQueue<>();
        try (JRedisClient gateway = store.newClient()) {
            gateway.pubSub().subscribe("push:gw-a", (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8)))
                    .get(5, TimeUnit.SECONDS);
            jredis.sync().set("conn:" + id, "gw-a#x-1");

            assertThat(call("POST", "/admin/players/" + id + "/ban", "{}", SECRET).statusCode())
                    .as("a reason is required").isEqualTo(400);
            HttpResponse<String> r = call("POST", "/admin/players/" + id + "/ban", "{\"reason\":\"cheating\"}", SECRET);
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(r.body());
            assertThat(body.get("status").asText()).isEqualTo("banned");
            assertThat(body.get("sessionsEnded").asInt()).isEqualTo(2);
            assertThat(status(id)).isEqualTo(2);
            assertThat(sessions.playerIdOf(one).get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
            assertThat(sessions.playerIdOf(two).get(5, TimeUnit.SECONDS)).isEqualTo(-1L);
            JsonNode push = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
            assertThat(push.at("/msg/t").asText()).isEqualTo("evt.session.revoked");
        }
        assertThat(auth.login("ban-ada", "hunter2-hunter2".toCharArray()).outcome()).isEqualTo(AuthService.Login.BANNED);
        assertThat(audited(Long.toString(id))).containsExactly("ban refused_no_reason", "ban banned");
    }

    @Test
    @DisplayName("a suspension lasts until its date, an unban lifts either, and a player who does not exist is a 404")
    void suspendAndLift() throws Exception {
        long id = register("sus-bob");
        HttpResponse<String> r = call("POST", "/admin/players/" + id + "/ban",
                "{\"reason\":\"abuse\",\"until\":\"2099-01-01T00:00:00Z\"}", SECRET);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("status").asText()).isEqualTo("suspended");
        assertThat(status(id)).isEqualTo(1);
        assertThat(auth.login("sus-bob", "hunter2-hunter2".toCharArray()).outcome()).isEqualTo(AuthService.Login.BANNED);
        assertThat(call("POST", "/admin/players/" + id + "/ban", "{\"reason\":\"x\",\"until\":\"soon\"}", SECRET)
                .statusCode()).as("a date that is not one").isEqualTo(400);

        assertThat(call("POST", "/admin/players/" + id + "/unban", "{\"reason\":\"appeal\"}", SECRET).statusCode()).isEqualTo(200);
        assertThat(status(id)).isZero();
        assertThat(auth.login("sus-bob", "hunter2-hunter2".toCharArray()).outcome()).isEqualTo(AuthService.Login.OK);
        assertThat(call("POST", "/admin/players/999999/ban", "{\"reason\":\"x\"}", SECRET).statusCode()).isEqualTo(404);
        assertThat(audited("999999")).containsExactly("ban no_such_player");
        assertThat(audited(Long.toString(id))).containsExactly("ban suspended", "ban refused_bad_until", "unban active");
        assertThat(call("GET", "/admin/players/" + id + "/ban", null, SECRET).statusCode()).isEqualTo(405);

        long lapsed = register("sus-cyd");
        assertThat(call("POST", "/admin/players/" + lapsed + "/ban", "{\"reason\":\"old\",\"until\":\"2000-01-01T00:00:00Z\"}",
                SECRET).statusCode()).as("a date already past: refused, not a kick by another name").isEqualTo(400);
        try (Connection c = db.dataSource().getConnection();             // one that has run its course
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE account SET status = 1, banned_until = NOW(3) - INTERVAL 1 DAY WHERE id = ?")) {
            ps.setLong(1, lapsed);
            ps.executeUpdate();
        }
        assertThat(auth.login("sus-cyd", "hunter2-hunter2".toCharArray()).outcome()).as("its date has passed")
                .isEqualTo(AuthService.Login.OK);
        assertThat(call("POST", "/admin/players/" + id + "/delete", "{\"reason\":\"x\"}", SECRET).statusCode()).isEqualTo(404);
    }

    /** What an arena named {@code name} hears on its command channel. */
    private static LinkedBlockingQueue<String> hear(JRedisClient listener, String name) throws Exception {
        LinkedBlockingQueue<String> heard = new LinkedBlockingQueue<>();
        listener.pubSub().subscribe(ArenaDirectory.commandChannel(name),
                (ch, msg) -> heard.add(new String(msg, StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        return heard;
    }

    @Test
    @DisplayName("the rooms as each arena announced them; a room closed and a player kicked by the arenas' channels (the second slice)")
    void rooms() throws Exception {
        ArenaDirectory directory = new ArenaDirectory(jredis);
        directory.announce(new ArenaDirectory.Endpoint("arena-r1", "10.0.0.1", 9001, 3, 150, false, 1, 4),
                "[{\"room\":\"room-2\",\"players\":3,\"mode\":\"duel\",\"stage\":\"playing\",\"matchUid\":\"01M\"}]")
                .get(5, TimeUnit.SECONDS);
        directory.announce(new ArenaDirectory.Endpoint("arena-r2", "10.0.0.2", 9001, 0, 150, false, 0, 4), "[]")
                .get(5, TimeUnit.SECONDS);
        try (JRedisClient listener = store.newClient()) {
            LinkedBlockingQueue<String> r1 = hear(listener, "arena-r1");
            LinkedBlockingQueue<String> r2 = hear(listener, "arena-r2");

            JsonNode rooms = JSON.readTree(call("GET", "/admin/rooms", null, SECRET).body());
            JsonNode room = null;
            for (JsonNode r : rooms) {
                if ("arena-r1".equals(r.get("arena").asText())) {
                    room = r;
                }
            }
            assertThat(room).isNotNull();
            assertThat(room.get("room").asText()).isEqualTo("room-2");
            assertThat(room.get("stage").asText()).isEqualTo("playing");

            HttpResponse<String> closed = call("POST", "/admin/rooms/arena-r1/room-2/close", "{\"reason\":\"stuck\"}", SECRET);
            assertThat(closed.statusCode()).isEqualTo(202);
            assertThat(JSON.readTree(closed.body()).get("heard").asInt()).isEqualTo(1);
            JsonNode close = JSON.readTree(r1.poll(5, TimeUnit.SECONDS));
            assertThat(close.get("cmd").asText()).isEqualTo("close");
            assertThat(close.get("room").asText()).isEqualTo("room-2");
            assertThat(call("POST", "/admin/rooms/arena-nope/room-2/close", "{\"reason\":\"x\"}", SECRET).statusCode())
                    .isEqualTo(404);
            assertThat(call("POST", "/admin/rooms/arena-r1/room-2/close", "{}", SECRET).statusCode()).isEqualTo(400);

            long id = register("kick-dee");
            HttpResponse<String> kicked = call("POST", "/admin/players/" + id + "/kick", "{\"reason\":\"afk\"}", SECRET);
            assertThat(kicked.statusCode()).isEqualTo(202);
            for (LinkedBlockingQueue<String> heard : List.of(r1, r2)) {
                JsonNode kick = JSON.readTree(heard.poll(5, TimeUnit.SECONDS));
                assertThat(kick.get("cmd").asText()).isEqualTo("kick");
                assertThat(kick.get("player").asLong()).isEqualTo(id);
                assertThat(kick.has("ban")).as("a kick is not remembered by the arena").isFalse();
            }
            assertThat(status(id)).as("a kick is not a ban").isZero();
            assertThat(audited("arena-r1/room-2")).containsExactly("close sent", "close refused_no_reason");
            assertThat(audited(Long.toString(id))).containsExactly("kick sent");

            // A ban takes them out of a match too.
            call("POST", "/admin/players/" + id + "/ban", "{\"reason\":\"cheating\"}", SECRET);
            JsonNode banned = JSON.readTree(r1.poll(5, TimeUnit.SECONDS));
            assertThat(banned.get("cmd").asText()).isEqualTo("kick");
            assertThat(banned.get("ban").asBoolean()).as("a ban's is: its tickets refused for their minute (T-35)").isTrue();
        }
    }

    @Test
    @DisplayName("started only when named; named, it needs its secret and a loopback address")
    void configuration() {
        assertThat(AdminServer.startIfConfigured(Map.of(), null, null, null, null, null, null, null, null)).isNull();
        assertThatThrownBy(() -> AdminServer.startIfConfigured(Map.of("BACKEND_ADMIN_ADDR", "127.0.0.1:0"),
                null, null, null, null, null, null, null, null)).isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_ADMIN_TOKEN");
        assertThatThrownBy(() -> AdminServer.startIfConfigured(Map.of("BACKEND_ADMIN_ADDR", "0.0.0.0:0",
                "BACKEND_ADMIN_TOKEN", SECRET), null, null, null, null, null, null, null, null))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("loopback");
    }

    @Test
    @DisplayName("a tournament's body is read as every call's, 4 KB at most; a name or a notice with a control or direction character is refused (S-19)")
    void adminTextIsChecked() throws Exception {
        String ok = "{\"reason\":\"checks\",\"name\":\"Plain Cup\",\"maxEntries\":8,"
                + "\"registrationEnds\":\"2099-01-01T00:00:00Z\",\"startsAt\":\"2099-01-01T01:00:00Z\","
                + "\"roundMinutes\":5,\"prizes\":[1000,500,250]}";
        HttpResponse<String> big = call("POST", "/admin/tournaments",
                ok.replace("{\"reason\"", "{\"padding\":\"" + "x".repeat(5_000) + "\",\"reason\""), SECRET);
        assertThat(big.statusCode()).as("past 4 KB, not read: " + big.body()).isEqualTo(400);
        for (String name : List.of("Cup\\u202E", "Cup\\u0007", "Cup\\u2028x")) {
            HttpResponse<String> refused = call("POST", "/admin/tournaments", ok.replace("Plain Cup", name), SECRET);
            assertThat(refused.statusCode()).as(name).isEqualTo(400);
            assertThat(refused.body()).contains("invalid_tournament");
        }
        for (String text : List.of("hi\\u0007", "hi\\u202Ethere", "a\\u2028b", "a\\u200Fb")) {
            HttpResponse<String> refused = call("POST", "/admin/notice", "{\"text\":\"" + text + "\",\"reason\":\"x\"}", SECRET);
            assertThat(refused.statusCode()).as(text).isEqualTo(400);
            assertThat(JSON.readTree(refused.body()).get("code").asText()).isEqualTo("invalid_text");
        }
        assertThat(call("POST", "/admin/tournaments", ok, SECRET).statusCode()).as("and a plain one is made").isEqualTo(200);
    }

    @Test
    @DisplayName("a notice is audited before it is sent: an audit that fails sends nothing (S-19, D-30)")
    void aNoticeIsAuditedFirst() throws Exception {
        javax.sql.DataSource gone = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class}, (p, m, a) -> {
                    if (m.getName().equals("getConnection")) {
                        throw new java.sql.SQLException("the database is gone");
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        Path secret = Files.writeString(dir.resolve("admin-token-gone"), SECRET + "\n");
        AdminServer down = AdminServer.startIfConfigured(Map.of("BACKEND_ADMIN_ADDR", "127.0.0.1:0",
                        "BACKEND_ADMIN_TOKEN_FILE", secret.toString()),
                new ArenaDirectory(jredis), new AdminRepository(gone), sessions, new LobbyPush(jredis),
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()),
                new com.backend.persistence.SeasonRepository(db.dataSource()), java.time.Clock.systemUTC());
        java.util.concurrent.LinkedBlockingQueue<String> heard = new java.util.concurrent.LinkedBlockingQueue<>();
        JRedisClient gateway = store.newClient();
        try {
            gateway.pubSub().subscribe(LobbyPush.ALL_CHANNEL,
                    (ch, msg) -> heard.add(new String(msg, StandardCharsets.UTF_8))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            HttpResponse<String> failed = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + down.port()
                            + "/admin/notice")).header("Authorization", "Bearer " + SECRET)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"unaudited\",\"reason\":\"x\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(failed.statusCode()).as(failed.body()).isGreaterThanOrEqualTo(500);
            assertThat(heard.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)).as("nothing sent unaudited").isNull();
        } finally {
            gateway.close();
            down.close();
        }
    }

    @Test
    @DisplayName("an operator refunds a paid order once, its gems taken back as far as the balance allows, the rest a debt, and clears a debt; each call audited (04 §8, D-68)")
    void refundsAndDebt() throws Exception {
        long id = register("refund-ada");
        var payments = new com.backend.persistence.PaymentRepository(db.dataSource());
        java.time.Instant now = java.time.Instant.now();
        var paid = payments.place(id, "00000000-0000-4000-8000-0000000000a1", "gems_80", 80, 99, "USD", "simulated", now).order();
        var pending = payments.place(id, "00000000-0000-4000-8000-0000000000a2", "gems_80", 80, 99, "USD", "simulated", now).order();
        payments.confirm(paid.id(), true, now);                                           // 80, and the first's 80
        new com.backend.persistence.EconomyRepository(db.dataSource()).purchase(id, "boost_xp_hour", 150,
                "refund-ada-spends-150", com.backend.persistence.EconomyRepository.CURRENCY_GEMS);   // 10 left
        String path = "/admin/payments/" + paid.id() + "/refund";

        assertThat(call("POST", path, "{}", SECRET).statusCode()).as("no reason").isEqualTo(400);
        HttpResponse<String> refunded = call("POST", path, "{\"reason\":\"chargeback\"}", SECRET);
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(200);
        JsonNode r = JSON.readTree(refunded.body());
        assertThat(List.of(r.get("orderId").asText(), r.get("playerId").asLong(), r.get("taken").asInt(), r.get("debt").asInt()))
                .containsExactly(paid.id(), id, 10, 150);
        HttpResponse<String> again = call("POST", path, "{\"reason\":\"chargeback\"}", SECRET);
        assertThat(List.of(again.statusCode(), JSON.readTree(again.body()).get("code").asText())).containsExactly(409, "not_paid");
        HttpResponse<String> notYet = call("POST", "/admin/payments/" + pending.id() + "/refund", "{\"reason\":\"x\"}", SECRET);
        assertThat(List.of(notYet.statusCode(), JSON.readTree(notYet.body()).get("code").asText())).containsExactly(409, "not_paid");
        HttpResponse<String> none = call("POST", "/admin/payments/00000000-0000-4000-8000-000000000000/refund",
                "{\"reason\":\"x\"}", SECRET);
        assertThat(List.of(none.statusCode(), JSON.readTree(none.body()).get("code").asText())).containsExactly(404, "no_such_order");
        assertThat(call("GET", path, null, SECRET).statusCode()).isEqualTo(405);
        assertThat(call("POST", "/admin/payments/" + paid.id(), "{\"reason\":\"x\"}", SECRET).statusCode()).isEqualTo(404);
        assertThat(audited(paid.id())).containsExactly("refund refused_no_reason", "refund refunded", "refund refused_not_paid");
        assertThat(payments.debtOf(id)).isEqualTo(150);

        String debtPath = "/admin/players/" + id + "/refund-debt";
        assertThat(call("POST", debtPath, "{}", SECRET).statusCode()).as("no reason").isEqualTo(400);
        HttpResponse<String> cleared = call("POST", debtPath, "{\"reason\":\"support: an honest mistake\"}", SECRET);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(List.of(JSON.readTree(cleared.body()).get("playerId").asLong(), JSON.readTree(cleared.body()).get("cleared").asLong()))
                .containsExactly(id, 150L);
        assertThat(JSON.readTree(call("POST", debtPath, "{\"reason\":\"again\"}", SECRET).body()).get("cleared").asLong()).isZero();
        assertThat(payments.debtOf(id)).isZero();
        assertThat(audited(Long.toString(id))).containsExactly("refund-debt refused_no_reason", "refund-debt cleared",
                "refund-debt cleared");
    }
}
