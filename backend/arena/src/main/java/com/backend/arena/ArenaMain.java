package com.backend.arena;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import java.nio.file.Path;

import com.backend.common.Arguments;
import com.backend.common.Logs;
import com.backend.common.Metrics;
import com.backend.common.MetricsServer;
import com.backend.common.RefusedConfiguration;
import com.backend.common.Secrets;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.MatchResultStream;
import com.backend.handoff.StoreClients;
import com.backend.handoff.TicketStore;

import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one arena process.
 *
 * Usage: ArenaMain [port] [mapSize] [maxPlayers] [shapes] [maxRooms] [storeHost] [storePort]
 *                  [name] [matchSeconds] [spoolDir] [bindHost] [advertiseHost]
 *
 * {@code matchSeconds} of 0 — the default — is the public arena's continuous lifecycle:
 * the room never resets and a result covers one player's stay. A positive value runs timed
 * matches instead.
 *
 * {@code bindHost} is the interface to listen on; {@code advertiseHost} is the address
 * platform hands to clients. They differ whenever the arena is behind any kind of address
 * translation, and conflating them is how a client ends up being told to connect to the
 * server's own loopback.
 *
 * The arena refuses to start without the ticket store: it has no way to identify a player
 * otherwise, and a build that silently accepted unidentified joins would be the version
 * someone eventually runs in production.
 */
public final class ArenaMain {

    private static final Logger log = LoggerFactory.getLogger(ArenaMain.class);

    /**
     * How long a stop waits for made matches to end (01 §8.6): the longest mode's ten minutes and
     * its join window. The unit's TimeoutStopSec is a minute more.
     */
    static final long DRAIN_MILLIS = 11 * 60 * 1_000L;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (RefusedConfiguration refused) {
            // 2: restarting cannot fix a setting, so the units do not restart on it.
            Logs.flush();
            System.err.println("refusing to start: " + refused.getMessage());
            System.exit(2);
        } catch (Throwable failed) {
            // Exit, rather than let main end by throwing. The store client's threads are not
            // daemons, so a main that only threw left a process that looked alive and served
            // nothing — a wrong database password did exactly that — and systemd restarts
            // only a process that has ended.
            Logs.flush();
            failed.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        int port = Arguments.integer(args, 0, "port", 9001);
        float map = Arguments.decimal(args, 1, "map size", 5700f);
        int maxPlayers = Arguments.integer(args, 2, "max players", 150);
        int shapes = Arguments.integer(args, 3, "shapes", 1500);
        int maxRooms = Arguments.integer(args, 4, "max rooms", 4);
        String storeHost = Arguments.text(args, 5, "store host", "127.0.0.1");
        int storePort = Arguments.integer(args, 6, "store port", 6379);
        String name = Arguments.text(args, 7, "arena name", "arena-" + port);
        // 0 means the public arena's continuous lifecycle (D-15); a positive value runs the public
        // rooms as timed matches, as a drill or a load run may. Queued and tournament matches are
        // made rooms (MatchRules.made), whatever this says.
        int matchSeconds = Arguments.integer(args, 8, "match seconds", 0);
        String spoolDir = Arguments.text(args, 9, "spool directory", "/var/lib/backend/spool/" + name);
        String bindHost = Arguments.text(args, 10, "bind address", "0.0.0.0");
        String advertiseHost = Arguments.text(args, 11, "advertised host", "127.0.0.1");

        JRedisClient store = StoreClients.open(storeHost, storePort, "arena");
        // Results go to the events instance (D-7); the same client when there is only one.
        JRedisClient events = StoreClients.openEvents(store, "arena-events");

        MatchResultPublisher publisher = new MatchResultPublisher(
                new MatchResultStream(events), Path.of(spoolDir), StoreClients.eventsReplicated());
        publisher.start();

        MatchRules rules = matchSeconds > 0
                ? MatchRules.timed(name, matchSeconds * 25)
                : MatchRules.open(name);

        ArenaServer server = new ArenaServer(new TicketStore(store), map, 16_384, maxPlayers,
                shapes, 2, maxRooms, rules, publisher::publish);
        server.registry().holdSandboxes(new com.backend.handoff.SandboxHolds(store));
        server.registry().markArrivals(new com.backend.handoff.MatchArrivals(store));
        // TLS when a keystore is configured (S-6). Without one, join tickets cross the network
        // in plaintext, where anyone on the path can use them first: fine on a developer's
        // machine, not in front of players.
        String keystore = System.getenv("BACKEND_ARENA_TLS_KEYSTORE");
        if (keystore != null && !keystore.isBlank()) {
            Secrets.Secret keystorePassword = Secrets.read(System.getenv(), "BACKEND_ARENA_TLS_PASSWORD");
            if (keystorePassword == null) {
                throw new RefusedConfiguration("BACKEND_ARENA_TLS_KEYSTORE is set but"
                        + " BACKEND_ARENA_TLS_PASSWORD_FILE is not");
            }
            ArenaTls tls = ArenaTls.fromKeystore(Path.of(keystore), keystorePassword.value().toCharArray());
            if (!tls.covers(advertiseHost)) {
                throw new RefusedConfiguration("the arena's certificate does not name "
                        + advertiseHost + ", the host clients are told to dial: every client"
                        + " would refuse the connection");
            }
            server.useTls(tls);
            if (tls.expires().isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(14)))) {
                log.warn("the arena's certificate expires at {}", tls.expires());
            }
        } else {
            log.warn("no BACKEND_ARENA_TLS_KEYSTORE: match connections are plaintext, and so are"
                    + " the join tickets on them");
        }
        // The metrics' settings before the arena binds and announces itself: refused after, the process exited with
        // its directory entry live for ten seconds, platform sending players to an arena gone (the arena review).
        MetricsServer metrics = MetricsServer.startIfConfigured(
                metricsFor(server, publisher, store, events), System.getenv());
        int bound = server.start(bindHost, port);
        if (!bindHost.equals("127.0.0.1") && isLoopback(advertiseHost)) {
            // Listening everywhere but telling clients to connect to themselves: the arena
            // looks healthy, announces itself, and every join attempt fails somewhere else.
            log.warn("listening on {} but advertising {}: remote clients cannot reach this arena",
                    bindHost, advertiseHost);
        }

        // Announced after binding, never before: the entry means "accepting connections",
        // and publishing it earlier would send players to a socket that is not open yet.
        ArenaAnnouncer announcer = new ArenaAnnouncer(new ArenaDirectory(store), name,
                advertiseHost, bound, server.registry(), server.tls());
        announcer.start();

        // Logged, not printed: players are already being served, and a console write would wait
        // for a journal that has stopped reading, with the shutdown hook below not yet in place.
        log.info(String.format(Locale.ROOT,
                "arena %s on %d, map %.0f, maxPlayers/room %d, shapes/room %d, maxRooms %d, "
                        + "%s, store %s:%d, spool %s, bind %s, advertise %s",
                name, bound, map, maxPlayers, shapes, maxRooms,
                matchSeconds > 0 ? "timed " + matchSeconds + "s" : "continuous",
                storeHost, storePort, spoolDir, bindHost, advertiseHost));

        CountDownLatch done = new CountDownLatch(1);
        // The order is the whole point of this hook, and each step depends on the one before.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            int playersAtShutdown = server.registry().totalPlayers();

            // 1. Withdraw from the directory, so platform stops sending players here.
            announcer.close();
            if (metrics != null) {
                metrics.close();
            }

            // 2. Drain (01 §8.6; D-29): the public rooms' players back to the lobby with what they
            //    are owed, and the made matches played to their ends, for up to eleven minutes.
            //    Before this, a stop cut every match short, and rated where it stood.
            try {
                long started = System.nanoTime();
                if (server.registry().drain(DRAIN_MILLIS)) {
                    log.info("drained in {} s: every made match ended", (System.nanoTime() - started) / 1_000_000_000L);
                } else {
                    // The made matches still on: the public rooms, stopped by the drain, are counted until they go.
                    log.warn("the drain ran out: {} made matches will be cut short",
                            server.registry().rooms().stream().filter(r -> r.matchUid() != null).count());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 3. Stop accepting, let every room left publish what its players are owed, connected
            //    or waiting for a resume, and wait for them. Before this, a deploy dropped up to
            //    ten minutes of every connected player's progress.
            server.close();

            // 4. Write everything the rooms just published to disk, and push what it can. Before
            //    anything else, and nothing here writes to the console: a journal that had
            //    stopped reading held the timing report that used to come first, until systemd
            //    killed the process, and the results with it.
            publisher.close();
            log.info("results published {} (replayed {}), spooled {}, spool failures {}",
                    publisher.publishedCount(), publisher.replayedCount(),
                    publisher.spooledCount(), publisher.spoolFailureCount());

            // 5. Only now read the rooms' timers. They were read while the rooms were still
            //    ticking, from a histogram that is not thread-safe and resizes itself.
            try {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                PrintStream report = new PrintStream(text, true, StandardCharsets.UTF_8);
                reportRooms(report, server.registry().rooms());
                report.printf(Locale.ROOT, "rooms %d   players at shutdown %d",
                        server.registry().roomCount(), playersAtShutdown);
                log.info("at shutdown:\n{}", text.toString(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                log.warn("could not report the rooms' timings: {}", e.toString());
            }

            if (events != store) {
                events.close();
            }
            store.close();
            // 6. Last: what the steps above logged is still queued, and the JVM halts next.
            Logs.flush();
            done.countDown();
        }));
        done.await();
    }

    private static java.util.Map<String, Double> byProfile(
            java.util.function.ToLongFunction<TrafficProfile> value) {
        java.util.Map<String, Double> out = new java.util.TreeMap<>();
        for (TrafficProfile p : TrafficProfile.values()) {
            out.put(p.label(), (double) value.applyAsLong(p));
        }
        return out;
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }

    private ArenaMain() {
    }

    /**
     * Each room's timings, once the rooms have stopped: the simulation against NFR-1a's 2 ms, the
     * snapshots, and the whole tick against NFR-1b's 15 ms (07 §4). O-3: the whole tick was
     * printed as the simulation's total, and failed against its budget.
     */
    static void reportRooms(PrintStream report, java.util.List<RoomThread> rooms) {
        int i = 0;
        for (RoomThread rt : rooms) {
            i++;
            rt.simTimer().report(report, "room-" + i + " simulation tick", 2.0);
            rt.netTimer().reportPhasesOnly(report,
                    "room-" + i + " encode + write, per snapshot round");
            rt.tickTimer().report(report, "room-" + i + " whole tick: simulation, snapshots and the rest", 15.0);
            report.printf(Locale.ROOT, "room-%d: ticks %d   overruns %d%n",
                    i, rt.ticks(), rt.overruns());
        }
    }

    /**
     * What an arena reports (01-arena, 02-networking and 07 list what matters). Everything
     * here is safe to read from the scrape's thread: the rooms publish their numbers into
     * volatiles, and the rest are atomics.
     */
    private static Metrics metricsFor(ArenaServer server, MatchResultPublisher publisher,
                                      JRedisClient store, JRedisClient events) {
        RoomRegistry rooms = server.registry();
        Metrics m = new Metrics();
        m.jvm();
        StoreClients.registerMetrics(m, store);
        if (events != store) {
            StoreClients.registerMetrics(m, events, "events_store");
        }
        m.gauge("backend_arena_rooms", "Rooms running.", rooms::roomCount);
        m.labeledHistogram("backend_arena_connection_bytes_per_second",
                "Each connection's own snapshot bytes a second, as it ends, if it lasted a minute: by"
                        + " the profile it asked for (NFR-2).", "profile", rooms.connectionRates);
        m.labeledCounter("backend_arena_joins_total",
                "How each join with a ticket ended: joined, bad_ticket, no_room, claim_failed, removed; with"
                        + " platform's tickets issued, how many never arrived.", "outcome", server.joins());
        m.labeledCounter("backend_arena_connections_dropped_total",
                "Connections the arena closed without the client asking: no join in time, idle,"
                        + " stalled, or a TLS handshake that failed or had not finished by the"
                        + " join deadline.", "reason", server.dropped());
        if (server.tls()) {
            // Read once at start: a renewed certificate is served from the next restart, and
            // this is how the renewal is not left to memory.
            m.gauge("backend_arena_tls_certificate_expiry_timestamp_seconds",
                    "When the certificate clients are shown expires, in Unix seconds.",
                    () -> server.certificateExpiry().getEpochSecond());
        }
        m.gauge("backend_arena_players", "Players in rooms, including stays waiting for a resume"
                + " (backend_arena_stays_waiting).", rooms::totalPlayers);
        m.counter("backend_arena_rooms_failed_total",
                "Rooms that closed themselves after repeated faults, or hung and were abandoned.",
                rooms::failedRooms);
        m.labeledGauge("backend_arena_tick_p99_seconds",
                "Tick time p99 over the last ten seconds; the budget is 0.040.", "room", () -> {
                    java.util.Map<String, Double> out = new java.util.TreeMap<>();
                    for (RoomThread r : rooms.rooms()) {
                        out.put(r.name(), r.tickP99Millis() / 1000.0);
                    }
                    return out;
                });
        m.counter("backend_arena_tick_overruns_total",
                "Times a room fell more than 10 ticks (400 ms) behind and dropped the time instead"
                        + " of catching up; less far behind, it catches up tick by tick.",
                () -> rooms.total(RoomRegistry.Counter.TICK_OVERRUNS));
        m.counter("backend_arena_snapshots_sent_total", "Snapshots written to clients.",
                () -> rooms.total(RoomRegistry.Counter.SNAPSHOTS_SENT));
        m.counter("backend_arena_snapshots_skipped_total",
                "Snapshots skipped because the client's socket was not writable.",
                () -> rooms.total(RoomRegistry.Counter.SNAPSHOTS_SKIPPED));
        // Lost connections (02 §10): stays waiting for their player, and how they ended.
        m.gauge("backend_arena_stays_waiting",
                "Stays whose connection was lost, waiting for their player to resume them.",
                () -> rooms.rooms().stream().mapToInt(RoomThread::suspendedCount).sum());
        m.counter("backend_arena_resumes_total", "Players who came back to a stay after a lost connection.",
                () -> rooms.total(RoomRegistry.Counter.RESUMES));
        m.counter("backend_arena_stays_expired_total",
                "Stays ended because nobody came back for them within the minute.",
                () -> rooms.total(RoomRegistry.Counter.STAYS_EXPIRED));
        // Traffic profiles (02 §8): what each client is sent, and how often a link forced less.
        m.counter("backend_arena_snapshots_held_total",
                "Snapshots not sent because the client's link already held a second of them.",
                () -> rooms.total(RoomRegistry.Counter.SNAPSHOTS_HELD));
        m.labeledCounter("backend_arena_snapshot_bytes_total",
                "Snapshot bytes sent, payload only, by the traffic profile they were sent at.",
                "profile", () -> byProfile(rooms::bytesSentAt));
        m.labeledGauge("backend_arena_clients", "Clients by the traffic profile they are at now.",
                "profile", () -> byProfile(
                        p -> rooms.rooms().stream().mapToLong(r -> r.clientsAt(p)).sum()));
        m.labeledCounter("backend_arena_profile_steps_total",
                "Clients moved to a smaller traffic profile because their link queued (down), or"
                        + " back towards what they asked for (up).", "direction",
                () -> java.util.Map.of(
                        "down", (double) rooms.total(RoomRegistry.Counter.PROFILE_STEPS_DOWN),
                        "up", (double) rooms.total(RoomRegistry.Counter.PROFILE_STEPS_UP)));
        m.counter("backend_arena_results_published_total", "Match results handed to the store.",
                publisher::publishedCount);
        m.counter("backend_arena_results_spooled_total",
                "Match results written to the local spool before sending.", publisher::spooledCount);
        m.counter("backend_arena_results_unreplicated_total",
                "Match results the events store's replica had not confirmed within 100 ms (Q-11).",
                publisher::unreplicatedCount);
        m.counter("backend_arena_spool_failures_total",
                "Failed attempts to write a result to the spool; the result is kept in memory"
                        + " and tried again every two seconds.", publisher::spoolFailureCount);
        return m;
    }
}
