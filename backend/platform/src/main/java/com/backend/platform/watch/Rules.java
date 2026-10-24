package com.backend.platform.watch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;

/**
 * The runbook's §5 and 01 §7 as rules (04 §12, D-86), each with its threshold and its wait. Change a
 * threshold here and in the runbook together; {@code RulesTest} holds each to its words.
 */
public final class Rules {

    private static final String WATCH = "docs/operations/02-runbook.md#5-what-to-watch";
    private static final String PRIMARY = "docs/operations/02-runbook.md#2-stateful-primary-failure";
    private static final String PROCEDURES = "docs/operations/02-runbook.md#3-per-component-procedures";
    private static final String MACHINE = "docs/operations/02-runbook.md#1-machine-failure";
    private static final String CERTIFICATES = "docs/operations/06-certificates.md#10-when-something-goes-wrong";

    private static final long SECOND = 1000;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private Rules() {
    }

    public static List<Rule> all() {
        List<Rule> r = new ArrayList<>();
        r.add(latest("TargetDown", "up", v -> v == 0, 2 * MINUTE,
                "A process's metrics cannot be read: it is down, or its port is wrong", WATCH));
        r.add(latest("PeerUnreachable", "peer_up", v -> v == 0, 2 * MINUTE,
                "A machine's platform cannot be reached from here: the machine, its platform or the private network is down", MACHINE));
        r.add(latest("StoreConnectionDown", "backend_store_connected", v -> v == 0, MINUTE,
                "A process has lost the session store", PRIMARY));
        r.add(latest("EventsStoreConnectionDown", "backend_events_store_connected", v -> v == 0, MINUTE,
                "A process has lost the events store", PRIMARY));
        r.add(latest("StoreWithoutReplica", "backend_store_replicas", v -> v == 0, MINUTE,
                "The session store's primary has no replica connected; losing it loses what it holds since", PRIMARY));
        r.add(latest("EventsStoreWithoutReplica", "backend_events_store_replicas", v -> v == 0, MINUTE,
                "The events store's primary has no replica connected; losing it loses the results it holds", PRIMARY));
        r.add(latest("StoreReplicaSilent", "backend_store_replica_ack_seconds", v -> v > 30, 0,
                "A session store replica has been silent for over 30 s; it is behind, or gone", PRIMARY));
        r.add(latest("EventsStoreReplicaSilent", "backend_events_store_replica_ack_seconds", v -> v > 30, 0,
                "An events store replica has been silent for over 30 s; it is behind, or gone", PRIMARY));
        r.add(latest("GatewaySubscriptionDown", "backend_gateway_store_subscribed", v -> v == 0, MINUTE,
                "A gateway's push subscription is down; every push to its lobbies is lost", WATCH));
        r.add(increase("PushesUnheard", "backend_platform_pushes_unheard_total", 10 * MINUTE, 0, 0,
                "Pushes are being sent to a gateway nobody hears; its lobbies miss matches found and tournaments called", WATCH));
        r.add(latest("ResultSetAside", "backend_worker_dead_letter_depth", v -> v > 0, 0,
                "A match result was set aside (dead-lettered); a player's pay and rating did not arrive", PROCEDURES));
        r.add(series("ResultsBacklogGrowing", "backend_worker_queue_depth", 10 * MINUTE,
                (h, now) -> h.latest() > 100 && h.covers(now - 10 * MINUTE) && h.latest() > h.valueAt(now - 10 * MINUTE),
                "The result backlog has grown for ten minutes; rewards are silently not arriving", PROCEDURES));
        r.add(increase("WorkerFailing", "backend_worker_failed_total", 5 * MINUTE, 0, 5 * MINUTE,
                "The worker has been failing on results for five minutes; an entry it cannot apply is retried, not set aside", PROCEDURES));
        r.add(increase("ResultTrimmedUnapplied", "backend_worker_trimmed_unapplied_total", HOUR, 0, 0,
                "A result was trimmed from the stream before it was applied: it is lost; its id is in the worker's log", PROCEDURES));
        r.add(increase("ResultsUnreplicated", "backend_arena_results_unreplicated_total", 10 * MINUTE, 0, 10 * MINUTE,
                "Results are not being confirmed by the events replica; until it is back, each lives on one machine", PRIMARY));
        r.add(increase("TournamentsStalling", "backend_worker_tournament_failures_total", 30 * MINUTE, 5, 5 * MINUTE,
                "The tournament scheduler keeps failing; tournaments stop advancing", PROCEDURES));
        r.add(latest("MysqlReplicaLagging", "backend_mysql_replica_lag_seconds", v -> v > 30, 2 * MINUTE,
                "The MySQL replica is more than 30 s behind; a promotion would lose that much", PRIMARY));
        r.add(latest("MysqlReplicaDown", "backend_mysql_replica_up", v -> v == 0, 2 * MINUTE,
                "The MySQL replica cannot be read; a replica that stopped long ago is a primary with no failover", PRIMARY));
        r.add(latest("LedgerMismatch", "backend_worker_ledger_mismatches", v -> v > 0, 0,
                "The nightly ledger check found balances that do not match their ledger rows", PROCEDURES));
        r.add(olderThan("LedgerCheckStale", "backend_worker_ledger_checked_timestamp_seconds", 2 * DAY, 0,
                "The ledger check has not run for two days", PROCEDURES));
        r.add(latest("HasherLineFull", "backend_platform_hasher_line", v -> v >= 168, MINUTE,
                "The password hasher's line is full (168); logins are being turned away as busy", WATCH));
        r.add(latest("TickSlow", "backend_arena_tick_p99_seconds", v -> v > 0.015, 5 * MINUTE,
                "A room's tick p99 has been above 15 ms for five minutes (NFR-1b); at 40 ms ticks overrun", WATCH));
        r.add(series("ArenaCrashLoop", "backend_process_uptime_seconds", 0, labels -> "arena".equals(labels.get("job")),
                (h, now) -> h.falls(HOUR, now) >= 2,
                "An arena has restarted twice within an hour: a crash loop behind systemd's restarts", WATCH));
        r.add(ratio("DiskFilling", "machine_disk_free_bytes", "machine_disk_size_bytes", v -> v < 0.2, 5 * MINUTE,
                "A file system is above 80 % full; a full disk stops MySQL and loses nginx's lines", WATCH));
        r.add(series("DiskFillsWithinAWeek", "machine_disk_free_bytes", HOUR, (h, now) -> {
            double slope = h.slopePerMillis(HOUR, now);
            return !Double.isNaN(slope) && h.latest() + slope * 7 * DAY < 0;
        }, "A file system is filling at a rate that empties it within a week", WATCH));
        r.add(ratio("MemoryLow", "machine_memory_free_bytes", "machine_memory_size_bytes", v -> v < 0.1, 10 * MINUTE,
                "A machine has under 10 % of its memory free; the kernel will kill a process next", WATCH));
        r.add(olderThan("BackupDumpStale", "backend_backup_succeeded_timestamp_seconds", 26 * HOUR, 0,
                labels -> "dump".equals(labels.get("kind")), "No MySQL dump has succeeded for 26 hours", WATCH));
        r.add(olderThan("BackupProofStale", "backend_backup_succeeded_timestamp_seconds", 8 * DAY, 0,
                labels -> "proof".equals(labels.get("kind")), "No restore proof has succeeded for 8 days", WATCH));
        r.add(olderThan("BackupOffsiteStale", "backend_backup_succeeded_timestamp_seconds", 26 * HOUR, 0,
                labels -> "offsite".equals(labels.get("kind")), "Nothing has been copied off the site for 26 hours", WATCH));
        r.add(latest("BackupFailed", "backend_backup_failed", v -> v == 1, HOUR,
                "A backup step's last run failed", WATCH));
        r.add(latest("RestoreTooSlow", "backend_backup_restore_seconds", v -> v > 7200, 0,
                "The last proof's restore took over two hours, half the four-hour objective; physical backups are due", WATCH));
        r.add(series("TableTooBig", "backend_mysql_table_rows", 0, labels -> "match_player".equals(labels.get("table")),
                (h, now) -> h.latest() > 500_000_000,
                "match_player is past 500 million rows; the purge no longer keeps up with growth (06 §9)", WATCH));
        r.add(latest("RetentionSlow", "backend_worker_retention_seconds", v -> v > 1800, 0,
                "A retention run took over 30 minutes", WATCH));
        r.add(new Simple("CertificateExpiring", 0,
                "A certificate expires within 14 days; at expiry every device refuses the site", CERTIFICATES,
                Set.of("backend_arena_tls_certificate_expiry_timestamp_seconds", "backend_gateway_edge_certificate_expiry_timestamp_seconds"),
                (view, now) -> {
                    List<Map<String, String>> out = new ArrayList<>();
                    for (String metric : List.of("backend_arena_tls_certificate_expiry_timestamp_seconds",
                            "backend_gateway_edge_certificate_expiry_timestamp_seconds")) {
                        for (Rule.Series s : view.present(metric)) {
                            if (s.history().latest() - now / 1000.0 < 14 * 86400) {
                                out.add(s.labels());
                            }
                        }
                    }
                    return out;
                }));
        return List.copyOf(r);
    }

    // ---- the shapes a rule takes ----------------------------------------------------------------------

    private interface Condition {
        boolean holds(History h, long nowMillis);
    }

    private interface Evaluator {
        List<Map<String, String>> evaluate(Rule.View view, long nowMillis);
    }

    /** The latest value of each series of {@code metric} against a test. */
    private static Rule latest(String name, String metric, DoublePredicate test, long forMillis, String summary, String runbook) {
        return series(name, metric, forMillis, (h, now) -> test.test(h.latest()), summary, runbook);
    }

    /** A counter's rise over a window above {@code more}. */
    private static Rule increase(String name, String metric, long window, double more, long forMillis, String summary, String runbook) {
        return series(name, metric, forMillis, (h, now) -> h.increase(window, now) > more, summary, runbook);
    }

    /** A timestamp in seconds more than {@code age} old. */
    private static Rule olderThan(String name, String metric, long age, long forMillis, String summary, String runbook) {
        return olderThan(name, metric, age, forMillis, labels -> true, summary, runbook);
    }

    private static Rule olderThan(String name, String metric, long age, long forMillis, Predicate<Map<String, String>> which,
                                  String summary, String runbook) {
        return series(name, metric, forMillis, which, (h, now) -> now / 1000.0 - h.latest() > age / 1000.0, summary, runbook);
    }

    /** One series of {@code numerator} over the series of {@code denominator} with the same labels. */
    private static Rule ratio(String name, String numerator, String denominator, DoublePredicate test, long forMillis,
                              String summary, String runbook) {
        return new Simple(name, forMillis, summary, runbook, Set.of(numerator, denominator), (view, now) -> {
            List<Map<String, String>> out = new ArrayList<>();
            for (Rule.Series n : view.present(numerator)) {
                for (Rule.Series d : view.present(denominator)) {
                    if (d.labels().equals(n.labels()) && d.history().latest() > 0
                            && test.test(n.history().latest() / d.history().latest())) {
                        out.add(n.labels());
                    }
                }
            }
            return out;
        });
    }

    private static Rule series(String name, String metric, long forMillis, Condition condition, String summary, String runbook) {
        return series(name, metric, forMillis, labels -> true, condition, summary, runbook);
    }

    private static Rule series(String name, String metric, long forMillis, Predicate<Map<String, String>> which,
                               Condition condition, String summary, String runbook) {
        return new Simple(name, forMillis, summary, runbook, Set.of(metric), (view, now) -> {
            List<Map<String, String>> out = new ArrayList<>();
            for (Rule.Series s : view.present(metric)) {
                if (which.test(s.labels()) && condition.holds(s.history(), now)) {
                    out.add(s.labels());
                }
            }
            return out;
        });
    }

    private record Simple(String name, long forMillis, String summary, String runbook, Set<String> reads, Evaluator evaluator)
            implements Rule {
        @Override
        public List<Map<String, String>> evaluate(View view, long nowMillis) {
            return evaluator.evaluate(view, nowMillis);
        }
    }
}
