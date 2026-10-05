package com.backend.worker;

import com.backend.common.Arguments;
import com.backend.common.Logs;
import com.backend.common.Metrics;
import com.backend.common.MetricsServer;
import com.backend.common.RefusedConfiguration;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.MatchResultQueue;
import com.backend.handoff.MatchResultStream;
import com.backend.handoff.StoreClients;
import com.backend.handoff.TicketStore;
import com.backend.handoff.TournamentGrants;
import com.backend.persistence.Database;
import com.backend.persistence.DatabaseSettings;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.MatchResultRepository;
import com.backend.persistence.TournamentRepository;

import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one worker process.
 *
 * Usage: WorkerMain [storeHost] [storePort] [workerId]
 *
 * The database comes from the environment, never the command line: see
 * {@link DatabaseSettings}.
 *
 * {@code workerId} names this worker as a consumer of the result stream's group. It should be
 * stable across restarts of the same worker, so a restart finds what it had in flight at once
 * rather than after a minute, when any worker takes it over; and unique among workers, or two
 * of them will re-drive each other's entries. A single worker can leave it at the default.
 */
public final class WorkerMain {

    private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

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
        if (args.length > 3 || (args.length == 3 && args[2].startsWith("jdbc:"))) {
            // The old shape: URL, user and password came before the worker id. Refused rather
            // than shifted, because shifting would make an old line's password the worker id
            // — printed in logs and written into a store key.
            System.err.println(DatabaseSettings.NOT_ON_THE_COMMAND_LINE);
            System.exit(2);
        }
        String storeHost = Arguments.text(args, 0, "store host", "127.0.0.1");
        int storePort = Arguments.integer(args, 1, "store port", 6379);
        String workerId = Arguments.text(args, 2, "worker id", MatchResultQueue.DEFAULT_WORKER_ID);
        DatabaseSettings database = DatabaseSettings.fromEnvironment();
        if (database.usesDevelopmentPassword()) {
            log.warn("no BACKEND_DB_PASSWORD_FILE or BACKEND_DB_PASSWORD; using the development"
                    + " database password");
        }

        JRedisClient store = StoreClients.open(storeHost, storePort, "worker");
        // The result queue is on the events instance (D-7); leaderboards stay on the session one.
        JRedisClient events = StoreClients.openEvents(store, "worker-events");
        Database db = new Database(database.url(), database.user(), database.password(), database.poolSizeOr(8));
        db.migrate();

        MatchResultRepository results =
                new MatchResultRepository(db.dataSource(),
                AccountLevels::levelFor, EloRating::deltas, com.backend.persistence.DailyGoals::of);
        MatchResultStream queue = new MatchResultStream(events, workerId, MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS);
        // One for the process, so what it could not deliver is counted in one place (O-6).
        LobbyPush push = new LobbyPush(store);
        MatchResultConsumer consumer = new MatchResultConsumer(queue, results,
                new LeaderboardStore(store), new com.backend.persistence.BoostRepository(db.dataSource())::percentsAt)
                .telling((player, rewards) -> push.send(player, "evt.rewards", rewards));
        Retention retention = new Retention(results,
                new com.backend.persistence.InboxRepository(db.dataSource()),
                new com.backend.persistence.StatsRepository(db.dataSource()),
                // Retention deletes lapsed requests and invitations only: the caps are the platform's (Q-20, Q-46).
                new com.backend.persistence.FriendRepository(db.dataSource(), 100, 100),
                new com.backend.persistence.TeamRepository(db.dataSource(), 30),
                new com.backend.persistence.BoostRepository(db.dataSource()),
                new com.backend.persistence.TournamentRepository(db.dataSource()),
                new com.backend.persistence.DailyGoalRepository(db.dataSource()),
                new com.backend.persistence.PaymentRepository(db.dataSource()),
                new com.backend.persistence.SeasonPassRepository(db.dataSource()),
                new com.backend.persistence.BackupRunRepository(db.dataSource()));
        retention.start();
        LedgerCheck ledgerCheck = new LedgerCheck(new EconomyRepository(db.dataSource()), store, workerId);
        ledgerCheck.start();
        ReplicaWatch replicas = new ReplicaWatch(db);
        replicas.start();
        // Tickets, grants and pushes on the session store, where the platform's are (04 §6).
        TournamentScheduler tournaments = new TournamentScheduler(new TournamentRepository(db.dataSource()),
                new EconomyRepository(db.dataSource()), new ArenaDirectory(store), new TicketStore(store),
                new TournamentGrants(store), new com.backend.handoff.MatchArrivals(store), push,
                new com.backend.persistence.InboxRepository(db.dataSource()), java.time.Clock.systemUTC());
        tournaments.start();
        // Closes a season that has ended: its places, its gems, its reset (04 §7, D-63).
        SeasonKeeper seasons = new SeasonKeeper(new com.backend.persistence.SeasonRepository(db.dataSource()),
                new EconomyRepository(db.dataSource()), new com.backend.persistence.InboxRepository(db.dataSource()),
                push, store, workerId);
        seasons.start();

        MetricsServer metricsServer = MetricsServer.startIfConfigured(
                metricsFor(consumer, queue, retention, ledgerCheck, tournaments, push, store, events, replicas,
                        new com.backend.persistence.BackupRunRepository(db.dataSource()),
                        new com.backend.persistence.TableSizes(db.dataSource())), System.getenv());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (metricsServer != null) {
                metricsServer.close();
            }
            retention.close();
            ledgerCheck.close();
            replicas.close();
            tournaments.close();
            seasons.close();
            consumer.stop();
            // Logged, not printed: a journal that has stopped reading would hold the hook here.
            log.info("applied {}, duplicates {}, dead-lettered {}, deferred {}, "
                            + "failed {}, unranked {}, unapplicable players {}",
                    consumer.appliedCount(), consumer.duplicateCount(),
                    consumer.deadLetteredCount(), consumer.deferredCount(),
                    consumer.failedCount(), consumer.leaderboardFailureCount(),
                    consumer.unapplicablePlayerCount());
            Logs.flush();                    // last: the lines above are queued, and the JVM halts next
        }));

        // Before anything new: whatever a previous worker claimed and never finished.
        consumer.recoverAbandoned();
        consumer.run();

        db.close();
        if (events != store) {
            events.close();
        }
        store.close();
    }

    /**
     * What a worker reports (05 §9). The depths are store reads at scrape time, bounded by the
     * client's 2 s timeout; a family whose read fails is left out of that scrape.
     */
    private static Metrics metricsFor(MatchResultConsumer consumer, MatchResultStream queue,
                                      Retention retention, LedgerCheck ledgerCheck,
                                      TournamentScheduler tournaments, LobbyPush push, JRedisClient store,
                                      JRedisClient events, ReplicaWatch replicas,
                                      com.backend.persistence.BackupRunRepository backups,
                                      com.backend.persistence.TableSizes tables) {
        Metrics m = new Metrics();
        m.jvm();
        StoreClients.registerMetrics(m, store);
        if (events != store) {
            StoreClients.registerMetrics(m, events, "events_store");
        }
        replicas.registerMetrics(m, store, events);
        m.counter("backend_worker_applied_total", "Results applied.", consumer::appliedCount);
        m.counter("backend_worker_pushes_unheard_total",
                "Pushes to a player registered on a gateway whose channel nobody heard (03 §5).", push::unheard);
        m.counter("backend_worker_duplicates_total", "Redelivered results recognised and skipped.",
                consumer::duplicateCount);
        m.counter("backend_worker_dead_lettered_total", "Results set aside as unreadable or refused.",
                consumer::deadLetteredCount);
        m.counter("backend_worker_deferred_total", "Results from a newer producer, set aside.",
                consumer::deferredCount);
        m.counter("backend_worker_failed_total", "Attempts that failed and will be retried.",
                consumer::failedCount);
        m.counter("backend_worker_unranked_total", "Applied results the leaderboards missed.",
                consumer::leaderboardFailureCount);
        m.gauge("backend_worker_queue_depth",
                "Results not yet delivered to a worker: the group's lag, and the inbox.", queue::depth);
        m.gauge("backend_worker_pending", "Results delivered to a worker and not yet acknowledged.",
                queue::pendingCount);
        m.counter("backend_worker_trimmed_unapplied_total",
                "Results trimmed from the stream while pending, before anyone applied them: lost.",
                consumer::trimmedUnappliedCount);
        m.gauge("backend_worker_dead_letter_depth",
                "Results set aside for a person. Alert on anything above zero.", queue::deadCount);
        m.gauge("backend_worker_deferred_depth", "Results waiting for a newer worker.",
                queue::deferredCount);
        m.counter("backend_worker_retention_deleted_total", "Matches deleted by retention.",
                retention::deletedTotal);
        m.counter("backend_worker_tournament_failures_total",
                "Tournament ticks, or one tournament's step, that failed and will be tried again in 5 s."
                        + " Alert on a steady rise: every tournament waits on it.",
                tournaments::failedCount);
        // The fleet's latest check, read from the store, so every worker reports the same one.
        m.gauge("backend_worker_ledger_mismatches",
                "Players whose coins their ledger does not explain, at the fleet's last daily check."
                        + " Alert on anything above zero.", () -> {
                    long[] last = ledgerCheck.latest();
                    return last == null ? Double.NaN : last[0];
                });
        // What the backups' scripts recorded, read at each scrape (06 §10, D-71).
        m.labeledGauge("backend_backup_succeeded_timestamp_seconds",
                "When each backup step (dump, proof, offsite) last succeeded; NaN for never. Alert: a dump over 26 h"
                        + " ago, a proof over 8 days ago, and once a target is named, an offsite copy over 26 h ago.",
                "kind", () -> BackupWatch.succeeded(latestBackups(backups)));
        m.labeledGauge("backend_backup_failed", "1 when the last run of a backup step failed. Alert on 1.",
                "kind", () -> BackupWatch.failed(latestBackups(backups)));
        // The growth triggers (06 §9, D-72): what a restore would take, what the tables hold, what a purge takes.
        m.gauge("backend_backup_restore_seconds",
                "How long the last restore proof's restore and replay took. Alert past 7 200: half the 4-hour objective,"
                        + " when physical backups are due (D-72).", () -> BackupWatch.restoreSeconds(latestBackups(backups)));
        m.labeledGauge("backend_mysql_table_rows",
                "InnoDB's estimate of the growing tables' rows. Alert when match_player passes 500 million (D-72).",
                "table", () -> {
                    try {
                        java.util.Map<String, Double> out = new java.util.TreeMap<>();
                        tables.rows().forEach((t, n) -> out.put(t, (double) n));
                        return out;
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException("table sizes not read: " + e, e);
                    }
                });
        m.gauge("backend_worker_retention_seconds",
                "How long this worker's last retention run took; NaN before one. Alert past 1 800 (D-72).",
                retention::lastRunSeconds);
        m.gauge("backend_worker_ledger_checked_timestamp_seconds",
                "When the fleet last checked the ledger. Alert when it is more than two days ago.",
                () -> {
                    long[] last = ledgerCheck.latest();
                    return last == null ? Double.NaN : last[1];
                });
        return m;
    }

    /** The backups' latest runs; a database not answering leaves the families out of that scrape. */
    private static java.util.Map<Integer, com.backend.persistence.BackupRunRepository.Latest> latestBackups(
            com.backend.persistence.BackupRunRepository backups) {
        try {
            return backups.latest();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("backup runs not read: " + e, e);
        }
    }

    private WorkerMain() {
    }
}
