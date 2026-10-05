package com.backend.platform;

import java.util.concurrent.CountDownLatch;

import com.backend.common.Arguments;
import com.backend.common.Logs;
import com.backend.common.Metrics;
import com.backend.common.MetricsServer;
import com.backend.common.RefusedConfiguration;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.SessionStore;
import com.backend.handoff.StoreClients;
import com.backend.handoff.TicketStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AdminRepository;
import com.backend.persistence.Database;
import com.backend.persistence.DatabaseSettings;
import com.backend.persistence.EconomyRepository;
import com.backend.platform.net.AdminServer;
import com.backend.platform.net.PlatformHttpServer;

import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one platform process.
 *
 * Usage: PlatformMain [bindHost] [port] [storeHost] [storePort]
 *
 * The database comes from the environment, never the command line: see
 * {@link DatabaseSettings}.
 *
 * Binds to loopback by default. The login endpoint costs ~88 ms of CPU per call by design,
 * which makes it a denial-of-service lever for anyone who can reach it, so it listens where
 * only this machine's nginx and gateway reach it; attempts are counted by LoginThrottle before
 * Argon2 runs.
 */
public final class PlatformMain {

    private static final Logger log = LoggerFactory.getLogger(PlatformMain.class);

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
        if (args.length > 4) {
            // The old shape, which ended with the password. Refused rather than read, so a
            // launch line nobody updated fails here instead of running with its password
            // on show to every local user.
            System.err.println(DatabaseSettings.NOT_ON_THE_COMMAND_LINE);
            System.exit(2);
        }
        String bindHost = Arguments.text(args, 0, "bind address", "127.0.0.1");
        int port = Arguments.integer(args, 1, "port", 8080);
        String storeHost = Arguments.text(args, 2, "store host", "127.0.0.1");
        int storePort = Arguments.integer(args, 3, "store port", 6379);
        DatabaseSettings database = DatabaseSettings.fromEnvironment();
        if (database.usesDevelopmentPassword()) {
            log.warn("no BACKEND_DB_PASSWORD_FILE or BACKEND_DB_PASSWORD; using the development"
                    + " database password");
        }

        JRedisClient store = StoreClients.open(storeHost, storePort, "platform");
        Database db = new Database(database.url(), database.user(), database.password(), database.poolSizeOr(16));
        db.migrate();

        AccountRepository accounts = new AccountRepository(db.dataSource());
        AuthService auth = new AuthService(accounts, new PasswordHasher(), new SessionStore(store));
        Items items = Items.fromClasspath();          // read before binding, as the catalogue: a bad one stops the start
        EquipmentService equipment = new EquipmentService(auth,
                new com.backend.persistence.EquipmentRepository(db.dataSource()), items);
        JoinService joins = new JoinService(auth, accounts, new ArenaDirectory(store),
                new TicketStore(store), equipment);

        // Read before binding: a catalogue that breaks a rule stops the start (exit 2).
        Catalogue catalogue = Catalogue.fromClasspath();
        catalogue.checkItems(items);                   // an offer for no item stops the start (04 §8)
        ShopService shop = new ShopService(auth, accounts, new EconomyRepository(db.dataSource()),
                catalogue, java.time.Clock.systemUTC());
        log.info("shop: {} offers on sale now", shop.onSale().size());
        // Gems for money (D-68): the packs read before binding, as the catalogue; the provider named, or none.
        com.backend.persistence.PaymentRepository orders = new com.backend.persistence.PaymentRepository(db.dataSource());
        PaymentService payments = new PaymentService(auth, orders, Packs.fromClasspath(),
                PaymentService.providerFrom(System.getenv()), java.time.Clock.systemUTC());
        log.info("payments: {}", payments.on() ? "the simulated provider, which grants gems to whoever asks" : "off");
        PassService.checkItems(items);                 // a pass naming an item the table has not stops the start (D-69)
        PassService passes = new PassService(auth, new com.backend.persistence.SeasonPassRepository(db.dataSource()),
                java.time.Clock.systemUTC());

        // One for the process, so what it could not deliver is counted in one place (O-6).
        LobbyPush push = new LobbyPush(store);
        MatchQueue queue = new MatchQueue(store);
        Parties party = new Parties(store, com.backend.handoff.Ulid::generate);
        com.backend.persistence.TeamRepository teams = new com.backend.persistence.TeamRepository(db.dataSource(), 30);
        QueueService queues = new QueueService(auth, accounts, queue, party, push,
                System::currentTimeMillis, equipment, teams, new ArenaDirectory(store), new TicketStore(store),
                new com.backend.handoff.SandboxHolds(store), new com.backend.handoff.TournamentGrants(store));
        // Q-20's caps: a hundred friends, a hundred blocked.
        com.backend.persistence.FriendRepository friendRows =
                new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100);
        // Q-46's hourly limits on asking others: friend requests, team invitations and applications; party invitations.
        AskThrottle asking = new AskThrottle(store);
        PartyService parties = new PartyService(auth, accounts, party, push, queues, friendRows, asking);
        PlatformHttpServer server = new PlatformHttpServer(auth, joins,
                new LeaderboardStore(store), new LoginThrottle(store), shop, equipment,
                new BoostService(auth, new com.backend.persistence.BoostRepository(db.dataSource()), items,
                        java.time.Clock.systemUTC()),
                new TeamService(auth, teams, push, java.time.Clock.systemUTC(), asking),
                new TournamentService(auth, new com.backend.persistence.TournamentRepository(db.dataSource()),
                        new com.backend.handoff.TournamentGrants(store), party, teams, java.time.Clock.systemUTC()),
                queues, parties,
                new FriendService(auth, accounts, friendRows, push, java.time.Clock.systemUTC(), asking),
                new InboxService(auth, new com.backend.persistence.InboxRepository(db.dataSource()),
                        java.time.Clock.systemUTC()),
                new RatingLeaderboards(new com.backend.persistence.RatingBoards(db.dataSource()),
                        new com.backend.persistence.TeamBoards(db.dataSource()),
                        new com.backend.persistence.SeasonRepository(db.dataSource()), java.time.Clock.systemUTC(), 100),
                new AchievementService(auth, new com.backend.persistence.AchievementRepository(db.dataSource())),
                new GoalService(auth, new com.backend.persistence.DailyGoalRepository(db.dataSource()), java.time.Clock.systemUTC()),
                payments, passes, bindHost, port);
        // The listeners' settings first, metrics' and the admin API's: a bad one is refused (exit 2) before the
        // public API serves anything, not after (the platform review, 2026-10-04).
        Metrics metrics = new Metrics();
        metrics.jvm();
        StoreClients.registerMetrics(metrics, store);
        MetricsServer metricsServer = MetricsServer.startIfConfigured(metrics, System.getenv());
        // The operator's API (04 §10), on loopback behind its secret, when named.
        AdminServer adminServer = AdminServer.startIfConfigured(System.getenv(), new ArenaDirectory(store),
                new AdminRepository(db.dataSource()), new SessionStore(store), push,
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()),
                new com.backend.persistence.SeasonRepository(db.dataSource()), java.time.Clock.systemUTC());
        int bound = server.start();

        // Every platform runs one; whichever holds the lease matches (04 §4).
        Matchmaker matchmaker = new Matchmaker(store, queue, new ArenaDirectory(store),
                new TicketStore(store), push, new com.backend.handoff.TournamentGrants(store),
                // Unique across machines: every platform binds the same loopback address and port, and two machines
                // with the same pid both held the lease (the platform review, 2026-10-04).
                "platform@" + bindHost + ":" + bound + "#" + ProcessHandle.current().pid() + "-"
                        + com.backend.handoff.Ulid.generate(),
                System::currentTimeMillis);
        matchmaker.start();
        System.out.printf("platform on %s:%d, store %s:%d, %s%n", bindHost, bound, storeHost,
                storePort, database);

        server.registerMetrics(metrics);
        metrics.counter("backend_platform_pushes_unheard_total",
                "Pushes to a player registered on a gateway whose channel nobody heard (03 §5).", push::unheard);
        matchmaker.registerMetrics(metrics);

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (metricsServer != null) {
                metricsServer.close();
            }
            if (adminServer != null) {
                adminServer.close();
            }
            matchmaker.close();
            server.close();
            db.close();
            store.close();
            Logs.flush();                    // last: the lines above are queued, and the JVM halts next
            done.countDown();
        }));
        done.await();
    }

    private PlatformMain() {
    }
}
