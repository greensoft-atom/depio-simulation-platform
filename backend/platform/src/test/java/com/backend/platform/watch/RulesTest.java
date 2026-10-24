package com.backend.platform.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.backend.common.MetricsText;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runbook's §5 as rules (04 §12, D-86): each fires at its threshold and after its wait, and not before,
 * fed rounds of samples 15 s apart, as promtool's rule tests would feed Prometheus. The thresholds here are
 * the runbook's; a change there is a change here.
 */
class RulesTest {

    private static final long STEP = 15_000;
    private static final long T0 = 1_700_000_000_000L;

    private final AlertEngine engine = new AlertEngine(Rules.all());
    private long now = T0;

    /** One round per element, 15 s apart: each is the text the targets would have served. */
    private AlertEngine.Round rounds(String... texts) {
        AlertEngine.Round last = null;
        for (String text : texts) {
            last = engine.round(MetricsText.parse(text), now);
            now += STEP;
        }
        return last;
    }

    /** {@code count} rounds of the same text. */
    private AlertEngine.Round repeat(int count, String text) {
        String[] texts = new String[count];
        java.util.Arrays.fill(texts, text);
        return rounds(texts);
    }

    private List<String> firing() {
        return engine.firing().stream().map(Alert::name).sorted().collect(Collectors.toList());
    }

    private static String up(String job, String instance, int value) {
        return "up{job=\"" + job + "\",instance=\"" + instance + "\"} " + value + "\n";
    }

    @Test
    @DisplayName("TargetDown after two minutes of a failed scrape, with the target's labels; resolved when it answers again")
    void targetDown() {
        repeat(8, up("platform", "127.0.0.1:9101", 1) + up("worker", "127.0.0.1:9102", 0));
        assertThat(firing()).as("1m45s: pending").isEmpty();
        AlertEngine.Round fired = repeat(1, up("platform", "127.0.0.1:9101", 1) + up("worker", "127.0.0.1:9102", 0));
        assertThat(firing()).as("2m0s").containsExactly("TargetDown");
        assertThat(fired.fired()).hasSize(1);
        Alert a = fired.fired().get(0);
        assertThat(a.labels()).containsEntry("job", "worker").containsEntry("instance", "127.0.0.1:9102");
        assertThat(a.summary()).contains("cannot be read");
        assertThat(a.runbook()).startsWith("docs/operations/02-runbook.md#");
        assertThat(a.sinceMillis()).as("since the condition first held").isEqualTo(T0);

        AlertEngine.Round back = rounds(up("platform", "127.0.0.1:9101", 1) + up("worker", "127.0.0.1:9102", 1));
        assertThat(back.resolved()).extracting(Alert::name).containsExactly("TargetDown");
        assertThat(firing()).isEmpty();
    }

    @Test
    @DisplayName("PeerUnreachable after two minutes without a peer's /health; the peer named")
    void peerUnreachable() {
        repeat(9, "peer_up{peer=\"B\"} 0\npeer_up{peer=\"C\"} 1\n");
        assertThat(firing()).containsExactly("PeerUnreachable");
        assertThat(engine.firing().get(0).labels()).containsEntry("peer", "B");
    }

    @Test
    @DisplayName("the store connections, the gateway's subscription and the replicas: a minute at 0; a silent replica at once past 30 s")
    void stores() {
        String text = "backend_store_connected{job=\"platform\",instance=\"127.0.0.1:9101\"} 0\n"
                + "backend_events_store_connected{job=\"arena\",instance=\"127.0.0.1:9110\"} 1\n"
                + "backend_gateway_store_subscribed{job=\"gateway\",instance=\"127.0.0.1:9103\"} 0\n"
                + "backend_store_replicas{job=\"platform\",instance=\"127.0.0.1:9101\"} 0\n"
                + "backend_events_store_replicas{job=\"arena\",instance=\"127.0.0.1:9110\"} 1\n"
                + "backend_store_replica_ack_seconds{job=\"platform\",instance=\"127.0.0.1:9101\"} 30\n"
                + "backend_events_store_replica_ack_seconds{job=\"arena\",instance=\"127.0.0.1:9110\"} 31\n";
        repeat(4, text);
        assertThat(firing()).as("45 s: the minute's wait not over; 31 s silent fires at once")
                .containsExactly("EventsStoreReplicaSilent");
        repeat(2, text);
        assertThat(firing()).containsExactly("EventsStoreReplicaSilent", "GatewaySubscriptionDown",
                "StoreConnectionDown", "StoreWithoutReplica");
    }

    @Test
    @DisplayName("PushesUnheard when the counter rose within ten minutes, and not once ten minutes have passed since")
    void pushesUnheard() {
        repeat(20, "backend_platform_pushes_unheard_total{job=\"platform\",instance=\"127.0.0.1:9101\"} 5\n");
        assertThat(firing()).isEmpty();
        repeat(1, "backend_platform_pushes_unheard_total{job=\"platform\",instance=\"127.0.0.1:9101\"} 6\n");
        assertThat(firing()).containsExactly("PushesUnheard");
        repeat(42, "backend_platform_pushes_unheard_total{job=\"platform\",instance=\"127.0.0.1:9101\"} 6\n");
        assertThat(firing()).as("10m30s later, nothing rose in the window").isEmpty();
    }

    @Test
    @DisplayName("the worker: a result set aside at once; a backlog that grew for ten minutes; failures for five; a trim within the hour")
    void worker() {
        String w = "{job=\"worker\",instance=\"127.0.0.1:9102\"}";
        repeat(1, "backend_worker_dead_letter_depth" + w + " 0\n");
        assertThat(firing()).isEmpty();
        repeat(1, "backend_worker_dead_letter_depth" + w + " 1\n");
        assertThat(firing()).containsExactly("ResultSetAside");

        AlertEngine growing = new AlertEngine(Rules.all());
        long t = T0;
        for (int i = 0; i < 81; i++) {
            growing.round(MetricsText.parse("backend_worker_queue_depth" + w + " " + (150 + 10 * i) + "\n"
                    + "backend_worker_queue_depth{job=\"worker\",instance=\"127.0.0.1:9112\"} 150\n"
                    + "backend_worker_failed_total" + w + " " + i + "\n"
                    + "backend_worker_trimmed_unapplied_total" + w + " " + (i >= 8 ? 1 : 0) + "\n"), t);
            t += STEP;
            if (i == 40) {
                assertThat(names(growing)).as("10 min: the backlog's wait not over; the trim at once, the failures after five")
                        .containsExactly("ResultTrimmedUnapplied", "WorkerFailing");
            }
        }
        assertThat(names(growing)).containsExactly("ResultTrimmedUnapplied", "ResultsBacklogGrowing", "WorkerFailing");
        assertThat(growing.firing().stream().filter(a -> a.name().equals("ResultsBacklogGrowing")).findFirst().get().labels())
                .as("the growing one, not the steady one").containsEntry("instance", "127.0.0.1:9102");
    }

    @Test
    @DisplayName("ResultsUnreplicated after ten minutes rising; TournamentsStalling past five failures in 30 min, for five")
    void unreplicatedAndTournaments() {
        for (int i = 0; i < 60; i++) {
            rounds("backend_arena_results_unreplicated_total{job=\"arena\",instance=\"127.0.0.1:9110\"} " + i + "\n"
                    + "backend_worker_tournament_failures_total{job=\"worker\",instance=\"127.0.0.1:9102\"} " + (i / 4) + "\n");
            if (i == 39) {
                assertThat(firing()).as("9m45s: rising since 15 s, the ten minutes not over").doesNotContain("ResultsUnreplicated");
            }
            if (i == 41) {
                assertThat(firing()).as("10m15s").contains("ResultsUnreplicated");
            }
        }
        assertThat(firing()).containsExactly("ResultsUnreplicated", "TournamentsStalling");
    }

    @Test
    @DisplayName("the MySQL replica: lagging past 30 s for two minutes; down for two minutes")
    void mysqlReplica() {
        String p = "{job=\"platform\",instance=\"127.0.0.1:9101\"}";
        repeat(9, "backend_mysql_replica_lag_seconds" + p + " 30\nbackend_mysql_replica_up" + p + " 1\n");
        assertThat(firing()).as("30 s is not past 30").isEmpty();
        repeat(8, "backend_mysql_replica_lag_seconds" + p + " 31\nbackend_mysql_replica_up" + p + " 0\n");
        assertThat(firing()).as("1m45s").isEmpty();
        repeat(1, "backend_mysql_replica_lag_seconds" + p + " 31\nbackend_mysql_replica_up" + p + " 0\n");
        assertThat(firing()).containsExactly("MysqlReplicaDown", "MysqlReplicaLagging");
    }

    @Test
    @DisplayName("the ledger: a mismatch at once; a check older than two days")
    void ledger() {
        String w = "{job=\"worker\",instance=\"127.0.0.1:9102\"}";
        long checked = T0 / 1000 - 47 * 3600;
        repeat(1, "backend_worker_ledger_mismatches" + w + " 0\nbackend_worker_ledger_checked_timestamp_seconds" + w + " " + checked + "\n");
        assertThat(firing()).isEmpty();
        now = T0 + 2 * 3600_000L;
        repeat(1, "backend_worker_ledger_mismatches" + w + " 2\nbackend_worker_ledger_checked_timestamp_seconds" + w + " " + checked + "\n");
        assertThat(firing()).containsExactly("LedgerCheckStale", "LedgerMismatch");
    }

    @Test
    @DisplayName("HasherLineFull at 168 for a minute, not at 167; TickSlow above 15 ms for five minutes, by room")
    void hasherAndTick() {
        String p = "{job=\"platform\",instance=\"127.0.0.1:9101\"}";
        String tick = "backend_arena_tick_p99_seconds{job=\"arena\",instance=\"127.0.0.1:9110\",room=\"";
        repeat(20, "backend_platform_hasher_line" + p + " 167\n" + tick + "1\"} 0.015\n" + tick + "2\"} 0.016\n");
        assertThat(firing()).as("4m45s").isEmpty();
        repeat(1, "backend_platform_hasher_line" + p + " 167\n" + tick + "1\"} 0.015\n" + tick + "2\"} 0.016\n");
        assertThat(firing()).as("5 min at 0.016 fires; 167 and 0.015 never").containsExactly("TickSlow");
        assertThat(engine.firing().get(0).labels()).containsEntry("room", "2");
        repeat(5, "backend_platform_hasher_line" + p + " 168\n" + tick + "1\"} 0.015\n" + tick + "2\"} 0.016\n");
        assertThat(firing()).as("a minute at 168").containsExactly("HasherLineFull", "TickSlow");
        repeat(1, "backend_platform_hasher_line" + p + " 168\n");
        assertThat(firing()).as("the arena's series gone from the round: its alert resolves").containsExactly("HasherLineFull");
    }

    @Test
    @DisplayName("ArenaCrashLoop once an arena's uptime fell twice within an hour; a platform's falls count for nothing")
    void crashLoop() {
        String a = "backend_process_uptime_seconds{job=\"arena\",instance=\"127.0.0.1:9110\"} ";
        String p = "backend_process_uptime_seconds{job=\"platform\",instance=\"127.0.0.1:9101\"} ";
        rounds(a + "100\n" + p + "100\n", a + "115\n" + p + "5\n", a + "5\n" + p + "20\n", a + "20\n" + p + "5\n");
        assertThat(firing()).as("one fall").isEmpty();
        rounds(a + "35\n" + p + "20\n", a + "5\n" + p + "35\n");
        assertThat(firing()).containsExactly("ArenaCrashLoop");
    }

    @Test
    @DisplayName("the machine: a file system under 20 % free for five minutes; one filling within a week, over an hour; memory under 10 % for ten")
    void machine() {
        String disks = "machine_disk_free_bytes{mount=\"/var\"} 19\nmachine_disk_size_bytes{mount=\"/var\"} 100\n"
                + "machine_disk_free_bytes{mount=\"/\"} 21\nmachine_disk_size_bytes{mount=\"/\"} 100\n"
                + "machine_memory_free_bytes 9\nmachine_memory_size_bytes 100\n";
        repeat(20, disks);
        assertThat(firing()).as("4m45s").isEmpty();
        repeat(1, disks);
        assertThat(firing()).as("5 min: the disk; the memory's ten not over").containsExactly("DiskFilling");
        assertThat(engine.firing().get(0).labels()).containsEntry("mount", "/var");
        repeat(20, disks);
        assertThat(firing()).as("10 min").containsExactly("DiskFilling", "MemoryLow");

        AlertEngine filling = new AlertEngine(Rules.all());
        long t = T0;
        double free = 1e12;                                         // a quarter of 4 TB free, losing a third of it a day:
        for (int i = 0; i <= 480; i++) {                            // empty in three days, and still above 20 % two hours on
            filling.round(MetricsText.parse("machine_disk_free_bytes{mount=\"/var/lib\"} " + (long) free
                    + "\nmachine_disk_size_bytes{mount=\"/var/lib\"} 4000000000000\n"
                    + "machine_disk_free_bytes{mount=\"/\"} 1000000000000\nmachine_disk_size_bytes{mount=\"/\"} 4000000000000\n"), t);
            t += STEP;
            free -= 1e12 / 3 / 86400 * 15;
            if (i == 120) {
                assertThat(names(filling)).as("30 min: the hour's wait not over").isEmpty();
            }
        }
        assertThat(names(filling)).containsExactly("DiskFillsWithinAWeek");
        assertThat(filling.firing().get(0).labels()).containsEntry("mount", "/var/lib");
    }

    @Test
    @DisplayName("the backups: a dump older than 26 h, a proof older than 8 days, a step failed for an hour, a restore over two hours")
    void backups() {
        String w = "{job=\"worker\",instance=\"127.0.0.1:9102\",kind=\"";
        long succeeded = T0 / 1000 - 25 * 3600;
        String text = "backend_backup_succeeded_timestamp_seconds" + w + "dump\"} " + succeeded + "\n"
                + "backend_backup_succeeded_timestamp_seconds" + w + "proof\"} " + (T0 / 1000 - 7 * 86400) + "\n"
                + "backend_backup_failed" + w + "offsite\"} 1\n"
                + "backend_backup_restore_seconds{job=\"worker\",instance=\"127.0.0.1:9102\"} 7200\n";
        repeat(1, text);
        assertThat(firing()).isEmpty();
        now = T0 + 2 * 3600_000L;
        repeat(1, text.replace("} 7200", "} 7201"));
        assertThat(firing()).containsExactly("BackupDumpStale", "BackupFailed", "RestoreTooSlow");
        now = T0 + 2 * 86400_000L;
        repeat(1, text);
        assertThat(firing()).containsExactly("BackupDumpStale", "BackupFailed", "BackupProofStale");
    }

    @Test
    @DisplayName("growth: match_player past 500 million rows; a retention run over 1 800 s")
    void growth() {
        String w = "{job=\"worker\",instance=\"127.0.0.1:9102\"";
        repeat(1, "backend_mysql_table_rows" + w + ",table=\"match_player\"} 500000000\nbackend_worker_retention_seconds" + w + "} 1800\n");
        assertThat(firing()).isEmpty();
        repeat(1, "backend_mysql_table_rows" + w + ",table=\"match_player\"} 500000001\nbackend_worker_retention_seconds" + w + "} 1801\n");
        assertThat(firing()).containsExactly("RetentionSlow", "TableTooBig");
    }

    @Test
    @DisplayName("CertificateExpiring within 14 days, an arena's or the gateway's; a certificate it cannot read (0) fires too")
    void certificates() {
        long fifteenDays = T0 / 1000 + 15 * 86400;
        String text = "backend_arena_tls_certificate_expiry_timestamp_seconds{job=\"arena\",instance=\"127.0.0.1:9110\"} " + fifteenDays + "\n"
                + "backend_gateway_edge_certificate_expiry_timestamp_seconds{job=\"gateway\",instance=\"127.0.0.1:9103\"} " + fifteenDays + "\n";
        repeat(1, text);
        assertThat(firing()).isEmpty();
        now = T0 + 2 * 86400_000L;
        repeat(1, text);
        assertThat(firing()).containsExactly("CertificateExpiring", "CertificateExpiring");
        assertThat(engine.firing()).extracting(a -> a.labels().get("job")).containsExactlyInAnyOrder("arena", "gateway");
        rounds("backend_arena_tls_certificate_expiry_timestamp_seconds{job=\"arena\",instance=\"127.0.0.1:9110\"} 0\n");
        assertThat(firing()).as("unreadable reads as 0: expiring").containsExactly("CertificateExpiring");
    }

    @Test
    @DisplayName("every rule names a runbook section, and no two share a name")
    void everyRuleIsDocumented() {
        List<Rule> all = Rules.all();
        assertThat(all).hasSizeGreaterThanOrEqualTo(30);
        assertThat(all.stream().map(Rule::name).distinct().count()).isEqualTo(all.size());
        for (Rule r : all) {
            assertThat(r.runbook()).as(r.name()).matches("docs/operations/0[0-9]-[a-z-]+\\.md#[a-z0-9-]+");
            assertThat(r.summary()).as(r.name()).isNotBlank();
        }
    }

    private static List<String> names(AlertEngine e) {
        List<String> out = new ArrayList<>();
        for (Alert a : e.firing()) {
            out.add(a.name());
        }
        java.util.Collections.sort(out);
        return out;
    }
}
