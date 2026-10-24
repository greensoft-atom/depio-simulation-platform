package com.backend.platform.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.backend.common.Metrics;
import com.backend.common.MetricsText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The watch as a whole (04 §12, D-86), against a real j-redis and scripted targets on loopback: a round reads
 * the targets, the machine and the peers, the rules fire, the alerts reach the store, the webhook and back,
 * once for the site; a silence keeps them unsent; a dead webhook is tried again.
 */
class WatchTest {

    /** A target: serves whatever text it is given at /metrics, and /health. */
    private static final class Target implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<String> body = new AtomicReference<>("");
        /** How long it takes to answer /metrics: a slow target. */
        final AtomicLong delayMillis = new AtomicLong();

        Target() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/metrics", ex -> {
                try {
                    Thread.sleep(delayMillis.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                answer(ex, 200, body.get());
            });
            server.createContext("/health", ex -> answer(ex, 200, "{\"status\":\"ok\"}"));
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** The webhook's receiver: every body posted, in order. */
    private static final class Receiver implements AutoCloseable {
        final HttpServer server;
        final LinkedBlockingQueue<String> posted = new LinkedBlockingQueue<>();

        Receiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", ex -> {
                posted.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                answer(ex, 200, "ok");
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        }

        JsonNode next() throws Exception {
            String body = posted.poll(5, TimeUnit.SECONDS);
            assertThat(body).as("a notification").isNotNull();
            return JSON.readTree(body);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void answer(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JRedisEmbedded storeServer;
    private static JRedisClient store;
    private static final long T0 = 1_700_000_000_000L;
    private final AtomicLong now = new AtomicLong(T0);
    private final Metrics metrics = new Metrics();
    private Watch watch;

    @BeforeAll
    static void startStore() {
        storeServer = JRedisEmbedded.start();
        store = storeServer.newClient();
    }

    @AfterAll
    static void stopStore() {
        storeServer.close();
    }

    @BeforeEach
    void fresh() {
        store.sync().send("FLUSHALL");
    }

    @AfterEach
    void stop() {
        if (watch != null) {
            watch.close();
        }
    }

    private Watch watch(String machine, URI webhook, long repeatMillis, List<WatchSettings.Target> targets, List<WatchSettings.Peer> peers) {
        return watch(machine, webhook, repeatMillis, targets, peers, metrics);
    }

    private Watch watch(String machine, URI webhook, long repeatMillis, List<WatchSettings.Target> targets, List<WatchSettings.Peer> peers,
                        Metrics own) {
        WatchSettings s = new WatchSettings(targets, peers, List.of(Path.of("/")), machine, webhook, repeatMillis);
        Watch w = new Watch(s, store, now::get, HttpClient.newHttpClient());
        w.registerMetrics(own);
        return w;
    }

    private void rounds(Watch w, int count) {
        for (int i = 0; i < count; i++) {
            w.round();
            now.addAndGet(15_000);
        }
    }

    private static List<String> names(List<Alert> alerts) {
        return alerts.stream().map(Alert::name).sorted().toList();
    }

    private double metric(String name, String... labels) {
        return metric(metrics, name, labels);
    }

    private static double metric(Metrics m, String name, String... labels) {
        for (MetricsText.Sample s : MetricsText.parse(m.render())) {
            if (s.name().equals(name) && s.labels().entrySet().containsAll(Map.of(labels[0], labels[1]).entrySet())) {
                return s.value();
            }
        }
        return 0;
    }

    @Test
    @Timeout(60)
    @DisplayName("a round reads a target, the rule fires with the job, the instance and the machine, the alert is stored, sent and logged; back to normal, resolved and sent")
    void aTargetsAlertEndToEnd() throws Exception {
        try (Target worker = new Target(); Receiver hook = new Receiver()) {
            worker.body.set("backend_worker_dead_letter_depth 1\nbackend_worker_queue_depth 3\n");
            watch = watch("A", hook.uri(), 3_600_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", worker.port())), List.of());
            AlertEngine.Round r = watch.round();
            assertThat(names(r.fired())).containsExactly("ResultSetAside");
            Alert a = r.fired().get(0);
            assertThat(a.labels()).containsEntry("job", "worker").containsEntry("instance", "127.0.0.1:" + worker.port())
                    .containsEntry("machine", "A");
            assertThat(watch.firing()).hasSize(1);

            List<AlertStore.Stored> stored = new AlertStore(store).list();
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).alert().name()).isEqualTo("ResultSetAside");
            assertThat(stored.get(0).alert().labels()).containsEntry("machine", "A");
            assertThat(stored.get(0).silenced()).isFalse();

            JsonNode sent = hook.next();
            assertThat(sent.get("status").asText()).isEqualTo("firing");
            assertThat(sent.get("machine").asText()).isEqualTo("A");
            assertThat(sent.get("alerts")).hasSize(1);
            assertThat(sent.at("/alerts/0/name").asText()).isEqualTo("ResultSetAside");
            assertThat(sent.at("/alerts/0/labels/job").asText()).isEqualTo("worker");
            assertThat(sent.at("/alerts/0/summary").asText()).contains("set aside");
            assertThat(sent.at("/alerts/0/runbook").asText()).startsWith("docs/operations/02-runbook.md#");
            assertThat(sent.at("/alerts/0/since").asText()).isEqualTo(java.time.Instant.ofEpochMilli(T0).toString());
            assertThat(metric("backend_watch_notifications_total", "outcome", "sent")).isEqualTo(1);
            assertThat(metric("backend_watch_scrapes_total", "outcome", "ok")).isEqualTo(1);
            assertThat(metric("backend_watch_alerts_firing", "machine", "A")).isEqualTo(1);

            worker.body.set("backend_worker_dead_letter_depth 0\nbackend_worker_queue_depth 3\n");
            now.addAndGet(15_000);
            r = watch.round();
            assertThat(names(r.resolved())).containsExactly("ResultSetAside");
            assertThat(watch.firing()).isEmpty();
            assertThat(new AlertStore(store).list()).as("gone from the store at once").isEmpty();
            JsonNode resolved = hook.next();
            assertThat(resolved.get("status").asText()).isEqualTo("resolved");
            assertThat(resolved.at("/alerts/0/name").asText()).isEqualTo("ResultSetAside");
            assertThat(hook.posted).isEmpty();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a target that does not answer is up 0, TargetDown after two minutes; a peer's /health likewise, PeerUnreachable, raised by two machines and sent once")
    void downAndPeers() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        try (Target peerC = new Target(); Receiver hook = new Receiver()) {
            List<WatchSettings.Peer> peers = List.of(new WatchSettings.Peer("B", "127.0.0.1", closed), new WatchSettings.Peer("C", "127.0.0.1", peerC.port()));
            watch = watch("A", hook.uri(), 3_600_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", closed)), peers);
            Metrics othersMetrics = new Metrics();
            Watch other = watch("D", hook.uri(), 3_600_000L, List.of(), peers, othersMetrics);
            try {
                for (int i = 0; i < 8; i++) {                   // the two machines' rounds, on one clock
                    watch.round();
                    other.round();
                    now.addAndGet(15_000);
                }
                assertThat(watch.firing()).as("1m45s: pending").isEmpty();
                assertThat(metric("backend_watch_scrapes_total", "outcome", "failed")).isEqualTo(8);
                watch.round();
                other.round();
                assertThat(names(watch.firing())).containsExactly("PeerUnreachable", "TargetDown");
                assertThat(names(other.firing())).containsExactly("PeerUnreachable");
                Alert peer = watch.firing().stream().filter(a -> a.name().equals("PeerUnreachable")).findFirst().get();
                assertThat(peer.labels()).as("a peer's alert carries no machine: the site's, not this machine's").containsOnlyKeys("peer")
                        .containsEntry("peer", "B");

                List<AlertStore.Stored> stored = new AlertStore(store).list();
                assertThat(stored).extracting(s -> s.alert().name()).containsExactlyInAnyOrder("PeerUnreachable", "TargetDown");
                JsonNode post = hook.next();                   // A's one POST carries both; D sends nothing
                List<String> sentNames = new java.util.ArrayList<>();
                for (JsonNode al : post.get("alerts")) {
                    sentNames.add(al.get("name").asText());
                }
                assertThat(sentNames).as("PeerUnreachable once for the site, TargetDown once for A")
                        .containsExactlyInAnyOrder("PeerUnreachable", "TargetDown");
                assertThat(hook.posted.poll(500, TimeUnit.MILLISECONDS)).as("D's copy of PeerUnreachable not sent again").isNull();
                assertThat(metric(othersMetrics, "backend_watch_notifications_total", "outcome", "duplicate")).isEqualTo(1);
            } finally {
                other.close();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a silence keeps an alert listed and unsent; lifted, it is sent; a firing alert is sent again after the repeat")
    void silencesAndRepeat() throws Exception {
        try (Target worker = new Target(); Receiver hook = new Receiver()) {
            worker.body.set("backend_worker_dead_letter_depth 1\n");
            AlertStore alerts = new AlertStore(store);
            alerts.silence("ResultSetAside", 1, "maintenance", now.get());
            watch = watch("A", hook.uri(), 1_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", worker.port())), List.of());
            watch.round();
            assertThat(watch.firing()).hasSize(1);
            assertThat(alerts.list().get(0).silenced()).isTrue();
            assertThat(hook.posted.poll(500, TimeUnit.MILLISECONDS)).as("silenced: not sent").isNull();
            assertThat(metric("backend_watch_notifications_total", "outcome", "silenced")).isEqualTo(1);
            assertThat(alerts.silences()).extracting(AlertStore.Silence::alertname).containsExactly("ResultSetAside");
            assertThat(alerts.silences().get(0).reason()).isEqualTo("maintenance");

            assertThat(alerts.unsilence("ResultSetAside")).isTrue();
            assertThat(alerts.unsilence("ResultSetAside")).isFalse();
            now.addAndGet(15_000);
            watch.round();
            assertThat(hook.next().get("status").asText()).isEqualTo("firing");

            Thread.sleep(1_100);                                        // past the repeat of one second
            now.addAndGet(15_000);
            watch.round();
            assertThat(hook.next().get("status").asText()).as("sent again after the repeat").isEqualTo("firing");
            now.addAndGet(15_000);
            watch.round();
            assertThat(hook.posted.poll(300, TimeUnit.MILLISECONDS)).as("and not before the next repeat").isNull();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a webhook that does not answer: the failure counted, the alert still stored, and sent on the next round once it answers")
    void aDeadWebhook() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        try (Target worker = new Target()) {
            worker.body.set("backend_worker_dead_letter_depth 1\n");
            URI dead = URI.create("http://127.0.0.1:" + closed + "/hook");
            watch = watch("A", dead, 3_600_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", worker.port())), List.of());
            watch.round();
            assertThat(watch.firing()).hasSize(1);
            assertThat(new AlertStore(store).list()).hasSize(1);
            assertThat(metric("backend_watch_notifications_total", "outcome", "failed")).isEqualTo(1);

            try (Receiver hook = new Receiver()) {
                watch.close();
                watch = watch("A", hook.uri(), 3_600_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", worker.port())), List.of());
                now.addAndGet(15_000);
                watch.round();
                assertThat(hook.next().get("status").asText()).as("the claim released by the failure: sent now").isEqualTo("firing");
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("each round writes what the machine saw to the store for a minute, and every machine's view is listed newest first (item 102)")
    void theViewOfEachMachine() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        try (Target worker = new Target()) {
            worker.body.set("backend_worker_dead_letter_depth 1\n");
            watch = watch("A", null, 3_600_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", worker.port()),
                    new WatchSettings.Target("arena", "127.0.0.1", closed)), List.of(new WatchSettings.Peer("B", "127.0.0.1", worker.port())));
            watch.round();
            Watch other = watch("B", null, 3_600_000L, List.of(), List.of(), new Metrics());
            try {
                now.addAndGet(15_000);
                other.round();
            } finally {
                other.close();
            }
            List<AlertStore.View> views = new AlertStore(store).views();
            assertThat(views).extracting(AlertStore.View::machine).as("newest first").containsExactly("B", "A");
            AlertStore.View a = views.get(1);
            assertThat(a).isEqualTo(watch.lastView());
            assertThat(a.atMillis()).isEqualTo(T0);
            assertThat(a.targets()).containsExactly(new AlertStore.View.TargetUp("worker", "127.0.0.1:" + worker.port(), true),
                    new AlertStore.View.TargetUp("arena", "127.0.0.1:" + closed, false));
            assertThat(a.peers()).containsExactly(new AlertStore.View.PeerUp("B", true));
            assertThat(a.disks()).extracting(AlertStore.View.Disk::mount).containsExactly("/");
            assertThat(a.disks().get(0).sizeBytes()).isGreaterThanOrEqualTo(a.disks().get(0).freeBytes()).isPositive();
            assertThat(a.memorySizeBytes()).isGreaterThanOrEqualTo(a.memoryFreeBytes()).isPositive();
            assertThat(a.cores()).isGreaterThanOrEqualTo(1);
            assertThat(a.alertsFiring()).isEqualTo(1);
            assertThat(a.roundSeconds()).isBetween(0.0, 10.0);
            assertThat(store.sync().ttl("watch:A")).as("a minute, then gone with its watch").isBetween(1L, 60L);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the targets are read in parallel: four that each take a second are read in about one, not four (item 102)")
    void targetsAreReadInParallel() throws Exception {
        try (Target t1 = new Target(); Target t2 = new Target(); Target t3 = new Target(); Target t4 = new Target()) {
            List<WatchSettings.Target> targets = new java.util.ArrayList<>();
            for (Target t : List.of(t1, t2, t3, t4)) {
                t.delayMillis.set(1_000);
                t.body.set("backend_worker_queue_depth 1\n");
                targets.add(new WatchSettings.Target("worker", "127.0.0.1", t.port()));
            }
            watch = watch("A", null, 3_600_000L, targets, List.of());
            long started = System.nanoTime();
            watch.round();
            double seconds = (System.nanoTime() - started) / 1e9;
            assertThat(seconds).as("in parallel, about a second; one after another, four").isLessThan(2.5);
            assertThat(watch.lastView().targets()).hasSize(4).allMatch(AlertStore.View.TargetUp::up);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("without a webhook, alerts are stored and listed and nothing is sent; the machine's own disks and memory are read")
    void noWebhookAndTheMachine() throws Exception {
        try (Target worker = new Target()) {
            worker.body.set("backend_worker_dead_letter_depth 1\n");
            watch = watch("A", null, 3_600_000L, List.of(new WatchSettings.Target("worker", "127.0.0.1", worker.port())), List.of());
            watch.round();
            assertThat(new AlertStore(store).list()).hasSize(1);
            assertThat(metric("backend_watch_notifications_total", "outcome", "sent")).isZero();
            List<MetricsText.Sample> machine = watch.lastMachineSamples();
            assertThat(machine).extracting(MetricsText.Sample::name).contains("machine_disk_free_bytes", "machine_disk_size_bytes",
                    "machine_memory_free_bytes", "machine_memory_size_bytes", "machine_load_1m", "machine_cores");
            for (MetricsText.Sample s : machine) {
                if (s.name().equals("machine_disk_free_bytes")) {
                    assertThat(s.labels()).containsEntry("mount", "/");
                    assertThat(s.value()).isPositive();
                }
                if (s.name().equals("machine_cores")) {
                    assertThat(s.value()).isGreaterThanOrEqualTo(1);
                }
            }
            assertThat(watch.seriesKept()).as("the rules' series alone are kept").isLessThan(20);
        }
    }
}
