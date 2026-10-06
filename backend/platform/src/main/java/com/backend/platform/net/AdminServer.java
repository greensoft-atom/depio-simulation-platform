package com.backend.platform.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import com.backend.common.RefusedConfiguration;
import com.backend.common.Secrets;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.SessionStore;
import com.backend.handoff.StoreUnavailableException;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AdminRepository;
import com.backend.persistence.TournamentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin API (docs detailed-design/04-platform-services.md §10, backend/platform/README.md): the arenas and
 * their rooms, a room closed, a player kicked, banned, suspended or lifted, a refund's debt cleared; the
 * players' figures by day; a notice to every lobby; a tournament made; the seasons listed and one ended; a
 * payment refunded. For an operator, over SSH. The arenas are told on their channels.
 *
 * <ul>
 * <li>Its own listener, {@code BACKEND_ADMIN_ADDR}, and only when named; a loopback address, or
 *     it refuses to start.</li>
 * <li>A shared secret, {@code BACKEND_ADMIN_TOKEN_FILE} (or {@code BACKEND_ADMIN_TOKEN}), sent as
 *     {@code Authorization: Bearer}, compared in constant time; named address, no secret: it
 *     refuses to start.</li>
 * <li>Every call audited in MySQL, refusals included (D-30).</li>
 * </ul>
 */
public final class AdminServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 4_096;
    private static final int MAX_REASON = 200;

    private final HttpServer server;
    private final byte[] secret;
    private final ArenaDirectory arenas;
    private final AdminRepository admin;
    private final SessionStore sessions;
    private final LobbyPush push;
    private final TournamentRepository tournaments;
    private final com.backend.persistence.StatsRepository stats;
    private final com.backend.persistence.SeasonRepository seasons;
    private final Clock clock;

    private AdminServer(HttpServer server, byte[] secret, ArenaDirectory arenas, AdminRepository admin,
                        SessionStore sessions, LobbyPush push, TournamentRepository tournaments,
                        com.backend.persistence.StatsRepository stats, com.backend.persistence.SeasonRepository seasons,
                        Clock clock) {
        this.server = server;
        this.secret = secret;
        this.arenas = arenas;
        this.admin = admin;
        this.sessions = sessions;
        this.push = push;
        this.tournaments = tournaments;
        this.stats = stats;
        this.seasons = seasons;
        this.clock = clock;
    }

    /** Started when {@code BACKEND_ADMIN_ADDR} names an address; null when it does not. */
    public static AdminServer startIfConfigured(Map<String, String> env, ArenaDirectory arenas,
                                                AdminRepository admin, SessionStore sessions, LobbyPush push,
                                                TournamentRepository tournaments,
                                                com.backend.persistence.StatsRepository stats,
                                                com.backend.persistence.SeasonRepository seasons, Clock clock) {
        String addr = env.get("BACKEND_ADMIN_ADDR");
        if (addr == null || addr.isBlank()) {
            return null;
        }
        Secrets.Secret token = Secrets.read(env, "BACKEND_ADMIN_TOKEN");
        if (token == null || token.value().isBlank()) {
            throw new RefusedConfiguration("BACKEND_ADMIN_ADDR is named but BACKEND_ADMIN_TOKEN_FILE is not:"
                    + " the admin API does not start without its secret");
        }
        int colon = addr.lastIndexOf(':');
        int port;
        InetAddress host;
        try {
            port = Integer.parseInt(addr.substring(colon + 1));
            host = InetAddress.getByName(addr.substring(0, colon));
        } catch (RuntimeException | IOException e) {
            throw new RefusedConfiguration("BACKEND_ADMIN_ADDR must be host:port, not " + addr);
        }
        if (!host.isLoopbackAddress()) {
            throw new RefusedConfiguration("BACKEND_ADMIN_ADDR must be a loopback address, reached over SSH,"
                    + " not " + addr);
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 16);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            AdminServer started = new AdminServer(server, token.value().getBytes(StandardCharsets.UTF_8),
                    arenas, admin, sessions, push, tournaments, stats, seasons, clock);
            server.createContext("/admin/", started::handle);
            server.start();
            log.info("admin API on {}", server.getAddress());
            return started;
        } catch (IOException e) {
            throw new RefusedConfiguration("cannot serve the admin API on " + addr + ": " + e, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (!authorised(ex)) {
                admin.audit("refused", path, null, "unauthorised");
                answer(ex, 401, error("unauthorised", "send Authorization: Bearer <the admin secret>"));
            } else if (path.equals("/admin/arenas")) {
                arenas(ex);
            } else if (path.equals("/admin/rooms")) {
                rooms(ex);
            } else if (path.startsWith("/admin/rooms/")) {
                closeRoom(ex, path.substring("/admin/rooms/".length()).split("/"));
            } else if (path.startsWith("/admin/players/")) {
                player(ex, path.substring("/admin/players/".length()).split("/"));
            } else if (path.equals("/admin/stats")) {
                stats(ex);
            } else if (path.equals("/admin/stats/features")) {
                statsByFeature(ex);
            } else if (path.equals("/admin/stats/funnel")) {
                funnel(ex);
            } else if (path.equals("/admin/stats/guests")) {
                guests(ex);
            } else if (path.equals("/admin/notice")) {
                notice(ex);
            } else if (path.equals("/admin/tournaments")) {
                createTournament(ex);
            } else if (path.equals("/admin/seasons")) {
                listSeasons(ex);
            } else if (path.equals("/admin/seasons/end")) {
                endSeason(ex);
            } else if (path.startsWith("/admin/payments/")) {
                refund(ex, path.substring("/admin/payments/".length()).split("/"));
            } else {
                answer(ex, 404, error("not_found", "no such call"));
            }
        } catch (SQLException e) {
            log.error("admin call {} failed: {}", path, e.toString());
            answer(ex, 503, error("storage_unavailable", "the database did not answer; nothing was done"));
        } catch (RuntimeException e) {
            if (storeDown(e)) {
                // As the database's, and as the player API answers it (04 §10, O-36): each call is safe to make again.
                log.error("admin call {}: the store did not answer: {}", path, e.toString());
                answer(ex, 503, error("storage_unavailable", "the store did not answer: call again"));
                return;
            }
            log.error("admin call {} failed", path, e);
            answer(ex, 500, error("internal", e.toString()));
        }
    }

    /** The store not answering (O-36), by the directory's own wait or by a call joined here, as the player API judges it. */
    static boolean storeDown(RuntimeException e) {
        RuntimeException cause = e instanceof java.util.concurrent.CompletionException
                && e.getCause() instanceof RuntimeException joined ? joined : e;
        return cause instanceof StoreUnavailableException || PlatformHttpServer.storeUnavailable(cause);
    }

    private boolean authorised(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        byte[] given = header == null || !header.startsWith("Bearer ") ? new byte[0]
                : header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, secret);
    }

    /** {@code GET /admin/stats?days=N}: whether players come back, by day (05 §11, Q-24). */
    private void stats(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        int days = daysAsked(ex);
        if (days == 0) {
            return;
        }
        ArrayNode out = JSON.createArrayNode();
        for (com.backend.persistence.StatsRepository.Day d
                : stats.daily(java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), days)) {
            ObjectNode day = out.addObject().put("day", d.day().toString()).put("active", d.active())
                    .put("newPlayers", d.newPlayers());
            day.put("d1", d.d1());
            day.put("d7", d.d7());
            day.put("d30", d.d30());
        }
        admin.audit("stats", null, null, "ok");
        ObjectNode body = JSON.createObjectNode();
        body.set("days", out);
        answer(ex, 200, body);
    }

    /** {@code GET /admin/stats/funnel?days=N}: of the accounts made each day, how far they went by now (05 §11, item 76 (c)). */
    private void funnel(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        int days = daysAsked(ex);
        if (days == 0) {
            return;
        }
        ArrayNode out = JSON.createArrayNode();
        for (com.backend.persistence.StatsRepository.Funnel f
                : stats.funnel(java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), days)) {
            out.add(funnelDay(f));
        }
        admin.audit("stats", "funnel", null, "ok");
        ObjectNode body = JSON.createObjectNode();
        body.set("days", out);
        answer(ex, 200, body);
    }

    /** A funnel's day, each step by its name. */
    static ObjectNode funnelDay(com.backend.persistence.StatsRepository.Funnel f) {
        return JSON.createObjectNode().put("day", f.day().toString()).put("registered", f.registered())
                .put("guests", f.guests()).put("played", f.played()).put("returned", f.returned())
                .put("level5", f.level5()).put("rated", f.rated()).put("bought", f.bought()).put("paid", f.paid());
    }

    /** {@code GET /admin/stats/guests}: the guests never upgraded, and those idle 90 days (05 §11, Q-22). */
    private void guests(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        var g = stats.guests(java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        admin.audit("stats", "guests", null, "ok");
        answer(ex, 200, JSON.createObjectNode().put("players", g.players()).put("guests", g.guests())
                .put("inactive", g.inactive()));
    }

    /** {@code GET /admin/stats/features?days=N}: each day's new players by what they did that day (05 §11, Q-26). */
    private void statsByFeature(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        int days = daysAsked(ex);
        if (days == 0) {
            return;
        }
        ArrayNode out = JSON.createArrayNode();
        for (com.backend.persistence.StatsRepository.FeatureDay d
                : stats.byFeature(java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), days)) {
            ObjectNode day = out.addObject().put("day", d.day().toString()).put("newPlayers", d.newPlayers());
            ObjectNode features = day.putObject("features");
            d.features().forEach((name, used) -> {
                ObjectNode f = features.putObject(name).put("players", used.players());
                f.put("d1", used.d1());
                f.put("d7", used.d7());
                f.put("d30", used.d30());
            });
        }
        admin.audit("stats", "features", null, "ok");
        ObjectNode body = JSON.createObjectNode();
        body.set("days", out);
        answer(ex, 200, body);
    }

    /** The days a stats call asks for, 14 if it names none; 0 once a 400 has been answered. */
    private static int daysAsked(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        int days = 14;
        if (query != null && query.startsWith("days=")) {
            try {
                days = Integer.parseInt(query.substring("days=".length()));
            } catch (NumberFormatException e) {
                days = 0;
            }
        }
        if (days < 1 || days > com.backend.persistence.StatsRepository.MAX_DAYS) {
            answer(ex, 400, error("invalid_days", "days is 1 to " + com.backend.persistence.StatsRepository.MAX_DAYS));
            return 0;
        }
        return days;
    }

    private void arenas(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        ArrayNode out = JSON.createArrayNode();
        for (ArenaDirectory.Endpoint e : arenas.live()) {
            out.addObject().put("name", e.name()).put("host", e.host()).put("port", e.port())
                    .put("players", e.players()).put("maxPlayers", e.maxPlayers()).put("tls", e.tls())
                    .put("rooms", e.rooms()).put("maxRooms", e.maxRooms());
        }
        admin.audit("arenas", null, null, "ok");
        answer(ex, 200, out);
    }

    /** Every live arena's rooms, as each last announced them (the second slice). */
    private void rooms(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        ArrayNode out = JSON.createArrayNode();
        for (ArenaDirectory.Endpoint e : arenas.live()) {
            JsonNode rooms = parse(String.valueOf(arenas.roomList(e.name())));
            for (JsonNode r : rooms) {
                out.addObject().put("arena", e.name()).setAll((ObjectNode) r);
            }
        }
        admin.audit("rooms", null, null, "ok");
        answer(ex, 200, out);
    }

    /** {@code /admin/rooms/{arena}/{room}/close} {@code {"reason"}}: told to that arena on its channel. */
    private void closeRoom(HttpExchange ex, String[] parts) throws IOException, SQLException {
        if (parts.length != 3 || !parts[2].equals("close")) {
            answer(ex, 404, error("not_found", "no such call"));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            answer(ex, 405, error("method_not_allowed", "use POST"));
            return;
        }
        String arena = parts[0];
        String target = arena + "/" + parts[1];
        String body = read(ex);
        if (!hasReason(ex, "close", target, body)) {
            return;
        }
        if (arenas.live().stream().noneMatch(e -> e.name().equals(arena))) {
            admin.audit("close", target, body, "no_such_arena");
            answer(ex, 404, error("no_such_arena", "no live arena " + arena));
            return;
        }
        long heard = arenas.command(arena, JSON.createObjectNode().put("cmd", "close").put("room", parts[1])).join();
        admin.audit("close", target, body, heard > 0 ? "sent" : "not_heard");
        answer(ex, 202, JSON.createObjectNode().put("heard", heard));
    }

    /**
     * A kick to every live arena: a player is in one at most, and which is not known here. A ban's
     * says so, and the arena refuses that player's tickets for their minute (T-35).
     *
     * @return how many heard
     */
    private long kickEverywhere(long playerId, boolean ban) {
        long heard = 0;
        for (ArenaDirectory.Endpoint e : arenas.live()) {
            var cmd = JSON.createObjectNode().put("cmd", "kick").put("player", playerId);
            if (ban) {
                cmd.put("ban", true);
            }
            heard += arenas.command(e.name(), cmd).join();
        }
        return heard;
    }

    /**
     * {@code POST /admin/tournaments}: a tournament (04 §6, Q-15), {@code {"reason", "name", "maxEntries",
     * "registrationEnds", "startsAt", "roundMinutes", "prizes": [first, second, third], "mode", "format"}},
     * the mode {@code duel}, the default, or {@code teams} (Q-19), the format {@code elimination}, the
     * default, or {@code round_robin}.
     */
    private void createTournament(HttpExchange ex) throws IOException, SQLException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            answer(ex, 405, error("method_not_allowed", "use POST"));
            return;
        }
        String body = read(ex);                          // 4 KB at most, as every call's (S-19)
        if (!hasReason(ex, "tournament", null, body)) {
            return;
        }
        JsonNode j = parse(body);
        String name = j.path("name").asText("").strip();
        int max = j.path("maxEntries").asInt(0);
        Instant ends = instant(j.path("registrationEnds").asText(""));
        Instant starts = instant(j.path("startsAt").asText(""));
        int minutes = j.path("roundMinutes").asInt(0);
        JsonNode prizes = j.path("prizes");
        String modeKey = j.path("mode").asText("duel");
        int mode = modeKey.equals("duel") ? com.backend.persistence.MatchResultRepository.MODE_DUEL
                : modeKey.equals("teams") ? com.backend.persistence.MatchResultRepository.MODE_TEAMS : -1;
        String formatKey = j.path("format").asText("elimination");     // 04 §6, plan item 66
        int format = formatKey.equals("elimination") ? com.backend.persistence.TournamentRepository.ELIMINATION
                : formatKey.equals("round_robin") ? com.backend.persistence.TournamentRepository.ROUND_ROBIN : -1;
        String problem = name.isEmpty() || name.length() > 64 || !plain(name)
                ? "a name of 1 to 64 characters, none a control or direction character"
                : max < 2 || max > 32 ? "maxEntries from 2 to 32"
                : ends == null || !ends.isAfter(clock.instant()) ? "registrationEnds, an instant still ahead"
                : starts == null || starts.isBefore(ends) ? "startsAt, an instant no earlier than registrationEnds"
                : minutes < 1 || minutes > 60 ? "roundMinutes from 1 to 60"
                : !prizes.isArray() || prizes.size() != 3 || !wholeCoins(prizes) ? "prizes, three whole numbers of coins"
                : mode < 0 ? "mode, duel or teams"
                : format < 0 ? "format, elimination or round_robin"
                : format == com.backend.persistence.TournamentRepository.ROUND_ROBIN && max > 8
                        ? "a round robin's maxEntries from 2 to 8: seven rounds at most"
                : null;
        if (problem != null) {
            admin.audit("tournament", null, body, "refused_invalid");
            answer(ex, 400, error("invalid_tournament", problem));
            return;
        }
        long id = tournaments.create(name, max, ends, starts, minutes, prizes.get(0).asLong(), prizes.get(1).asLong(),
                prizes.get(2).asLong(), mode, format);
        admin.audit("tournament", "tournament " + id, body, "created");
        answer(ex, 200, JSON.createObjectNode().put("id", id));
    }

    /**
     * Text shown to every client as it is: none of it a control, format (the direction marks and
     * overrides among them) or line separator character (S-19).
     */
    static boolean plain(String text) {
        return text.codePoints().noneMatch(cp -> switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        });
    }

    private static Instant instant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static boolean wholeCoins(JsonNode prizes) {
        for (JsonNode p : prizes) {
            if (!p.isIntegralNumber() || p.asLong() < 0 || p.asLong() > 1_000_000_000L) {
                return false;
            }
        }
        return true;
    }

    /** A reason is required, as every call's is audited: refused and audited without one. */
    private boolean hasReason(HttpExchange ex, String action, String target, String body) throws IOException, SQLException {
        String reason = parse(body).path("reason").asText("").strip();
        if (!reason.isEmpty() && reason.length() <= MAX_REASON) {
            return true;
        }
        admin.audit(action, target, body, "refused_no_reason");
        answer(ex, 400, error("no_reason", "say why, in at most " + MAX_REASON + " characters: it is audited"));
        return false;
    }

    /** A notice's longest text: a line a phone shows whole. */
    static final int MAX_NOTICE = 200;

    /**
     * {@code POST /admin/notice} {@code {"text", "reason"}}: pushed as {@code evt.notice} to every
     * player in the lobby, on every gateway (04 §10). Answered with how many gateways heard.
     */
    private void notice(HttpExchange ex) throws IOException, SQLException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            answer(ex, 405, error("method_not_allowed", "use POST"));
            return;
        }
        String body = read(ex);
        String text = parse(body).path("text").asText("").strip();
        if (text.isEmpty() || text.length() > MAX_NOTICE || !plain(text)) {
            admin.audit("notice", null, body, "refused_invalid_text");
            answer(ex, 400, error("invalid_text", "a notice is 1 to " + MAX_NOTICE
                    + " characters, none a control or direction character"));
            return;
        }
        if (!hasReason(ex, "notice", null, body)) {
            return;
        }
        // Audited first: a database failing now sends nothing, rather than a notice nobody recorded (D-30).
        admin.audit("notice", null, body, "sent");
        long heard = push.broadcast("evt.notice", JSON.createObjectNode().put("text", text)).join();
        answer(ex, 202, JSON.createObjectNode().put("gateways", heard));
    }

    /** {@code GET /admin/seasons}: every season, newest first, and how far its close has gone (04 §7, D-63). */
    private void listSeasons(HttpExchange ex) throws IOException, SQLException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            answer(ex, 405, error("method_not_allowed", "use GET"));
            return;
        }
        admin.audit("seasons", "seasons", null, "ok");    // reads are audited as every call is (D-30)
        var json = JSON.createObjectNode();
        var list = json.putArray("seasons");
        for (var s : seasons.recent(100)) {
            var row = list.addObject().put("id", s.id()).put("startsAt", s.startsAt().toString())
                    .put("endsAt", s.endsAt().toString());
            row.put("placedAt", s.placedAt() == null ? null : s.placedAt().toString());
            row.put("paidAt", s.paidAt() == null ? null : s.paidAt().toString());
            row.put("resetAt", s.resetAt() == null ? null : s.resetAt().toString());
        }
        answer(ex, 200, json);
    }

    /**
     * {@code POST /admin/seasons/end} {@code {"reason"}}: the current season ends now, for a drill or to
     * bring the calendar into line (04 §7); the worker's season job closes it within the minute, and
     * the next runs to the next boundary. 409 when it has ended already and is being closed.
     */
    private void endSeason(HttpExchange ex) throws IOException, SQLException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            answer(ex, 405, error("method_not_allowed", "use POST"));
            return;
        }
        String body = read(ex);
        var current = seasons.current();
        String target = current == null ? null : "season " + current.id();
        if (!hasReason(ex, "season", target, body)) {
            return;
        }
        var ended = seasons.endNow(clock.instant());
        if (ended == null) {
            admin.audit("season", target, body, "refused_already_ended");
            answer(ex, 409, error("already_ended", "the season has ended; the worker is closing it"));
            return;
        }
        admin.audit("season", "season " + ended.id(), body, "ended");
        answer(ex, 200, JSON.createObjectNode().put("season", ended.id()).put("endsAt", ended.endsAt().toString()));
    }

    /**
     * {@code POST /admin/payments/{orderId}/refund} {@code {"reason"}}: a paid order refunded, once, its gems
     * taken back as far as the balance allows and the rest its debt (04 §8, D-68).
     */
    private void refund(HttpExchange ex, String[] parts) throws IOException, SQLException {
        if (parts.length != 2 || !parts[1].equals("refund")) {
            answer(ex, 404, error("not_found", "no such call"));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            answer(ex, 405, error("method_not_allowed", "use POST"));
            return;
        }
        String body = read(ex);
        String target = parts[0];
        if (!hasReason(ex, "refund", target, body)) {
            return;
        }
        var refunded = admin.refund(target, clock.instant(), body);           // audited in its transaction (D-30)
        if (refunded == null) {
            answer(ex, 404, error("no_such_order", "no order " + target));
            return;
        }
        if (!refunded.now()) {
            answer(ex, 409, error("not_paid", "the order is not paid: pending, declined, expired, or refunded already"));
            return;
        }
        log.info("admin refund of order {} for player {}: {} gems taken back, {} owed", target,
                refunded.order().playerId(), refunded.taken(), refunded.debt());
        answer(ex, 200, JSON.createObjectNode().put("orderId", target).put("playerId", refunded.order().playerId())
                .put("taken", refunded.taken()).put("debt", refunded.debt()));
    }

    /** {@code /admin/players/{id}/ban}, {@code /unban}, {@code /kick} or {@code /refund-debt}, POSTed with {@code {"reason", "until"}}. */
    private void player(HttpExchange ex, String[] parts) throws IOException, SQLException {
        long id;
        try {
            id = parts.length == 2 ? Long.parseLong(parts[0]) : -1;
        } catch (NumberFormatException e) {
            id = -1;
        }
        String action = parts.length == 2 ? parts[1] : "";
        if (id <= 0 || !(action.equals("ban") || action.equals("unban") || action.equals("kick")
                || action.equals("refund-debt"))) {
            answer(ex, 404, error("not_found", "no such call"));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            answer(ex, 405, error("method_not_allowed", "use POST"));
            return;
        }
        String body = read(ex);
        JsonNode json = parse(body);
        String target = Long.toString(id);
        if (!hasReason(ex, action, target, body)) {
            return;
        }
        String reason = json.path("reason").asText("").strip();
        if (action.equals("refund-debt")) {
            long cleared = admin.clearDebt(id, body);                         // audited in its transaction (D-30)
            log.info("admin cleared player {}'s refund debt of {} gems ({})", id, cleared, reason);
            answer(ex, 200, JSON.createObjectNode().put("playerId", id).put("cleared", cleared));
            return;
        }
        if (action.equals("kick")) {
            long heard = kickEverywhere(id, false);
            admin.audit("kick", target, body, "sent");
            log.info("admin kick player {} ({}): {} arenas heard", id, reason, heard);
            answer(ex, 202, JSON.createObjectNode().put("playerId", id).put("arenas", heard));
            return;
        }
        Long until = null;
        if (action.equals("ban") && json.hasNonNull("until")) {
            try {
                until = Instant.parse(json.get("until").asText()).toEpochMilli();
            } catch (DateTimeParseException e) {
                until = Long.MIN_VALUE;
            }
            // In the future: one past, a year's typo, was taken as a suspension already over, a kick by another name.
            if (until <= clock.millis()) {
                admin.audit(action, target, body, "refused_bad_until");
                answer(ex, 400, error("bad_until", "until is an ISO-8601 instant in the future, as 2026-10-01T00:00:00Z"));
                return;
            }
        }
        AccountRepository.Status status = action.equals("unban") ? AccountRepository.Status.ACTIVE
                : until == null ? AccountRepository.Status.BANNED : AccountRepository.Status.SUSPENDED;
        String outcome = status.name().toLowerCase(Locale.ROOT);
        if (admin.setStatus(id, status, until, action, body, outcome) == AdminRepository.Change.NO_SUCH_PLAYER) {
            answer(ex, 404, error("no_such_player", "no account " + id));
            return;
        }
        int ended = 0;
        if (status != AccountRepository.Status.ACTIVE) {
            // Every session at once, and the lobby told; a match in progress plays on (04 §10).
            try {
                ended = sessions.revokeAll(id).join();
            } catch (RuntimeException storeDown) {
                // The ban is recorded and stands at the next login; sessions already open would live on, a day, so the
                // operator is told, and calling again ends them (04 §10).
                log.warn("admin {} player {}: recorded, but its sessions could not be ended: {}", action, id, storeDown.toString());
                answer(ex, 503, error("sessions_not_ended", "the " + outcome + " is recorded, but the store did not answer:"
                        + " the player's sessions are not ended; call again"));
                return;
            }
            push.send(id, "evt.session.revoked", JSON.createObjectNode().put("reason",
                    status == AccountRepository.Status.BANNED ? "banned" : "suspended"));
            try {
                kickEverywhere(id, true);            // and out of any match, rather than playing it out
            } catch (RuntimeException e) {
                if (!storeDown(e)) {
                    throw e;
                }
                // Done but this: said so, where a 500 left the operator unsure the ban had worked (O-36).
                log.warn("admin {} player {}: recorded, sessions ended, but not taken out of the arenas: {}", action, id,
                        e.toString());
                answer(ex, 503, error("not_taken_out", "the " + outcome + " is recorded and " + ended + " sessions ended,"
                        + " but the store did not answer: the player may play on in a match; call again"));
                return;
            }
        }
        log.info("admin {} player {}: {} ({})", action, id, outcome, reason);
        ObjectNode out = JSON.createObjectNode().put("playerId", id).put("status", outcome)
                .put("sessionsEnded", ended);
        if (until != null) {
            out.put("until", Instant.ofEpochMilli(until).toString());
        }
        answer(ex, 200, out);
    }

    private static String read(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            // Past the limit, not read at all: cut and parsed, what was left could still be a call (S-19).
            byte[] body = in.readNBytes(MAX_BODY + 1);
            return body.length > MAX_BODY ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = body.isBlank() ? null : JSON.readTree(body);
            return node == null ? JSON.createObjectNode() : node;
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static ObjectNode error(String code, String message) {
        return JSON.createObjectNode().put("code", code).put("message", message);
    }

    private static void answer(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
