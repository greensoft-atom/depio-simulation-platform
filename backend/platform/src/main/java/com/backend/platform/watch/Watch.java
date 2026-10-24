package com.backend.platform.watch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.backend.common.Metrics;
import com.backend.common.MetricsText;
import com.backend.common.MetricsText.Sample;
import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The watch (04 §12, D-86): every 15 s, on a thread of its own, reads this machine's processes' metrics, this
 * machine's disks and memory, and the other machines' platforms, hands the samples to the rules, and sees
 * to what fires: the store, the journal, the webhook. One in every platform process.
 */
public final class Watch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Watch.class);

    public static final long ROUND_MILLIS = 15_000;
    /** A target or a peer that does not answer within this is down for the round. */
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    /** A resolution is sent once; this is how long the store remembers it was. */
    private static final long RESOLVED_SENT_MILLIS = 3_600_000L;

    private final WatchSettings settings;
    private final AlertEngine engine = new AlertEngine(Rules.all());
    private final AlertStore alerts;
    private final Notifier notifier;
    private final LongSupplier clock;
    private final HttpClient http;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "watch");
        t.setDaemon(true);
        return t;
    });
    private final Metrics.LabeledCounter scrapes = new Metrics.LabeledCounter();
    private final Metrics.LabeledCounter notifications = new Metrics.LabeledCounter();
    private volatile List<Sample> lastMachineSamples = List.of();
    private volatile double lastRoundSeconds;
    private volatile AlertStore.View lastView;

    public Watch(WatchSettings settings, JRedisClient store, LongSupplier clock, HttpClient http) {
        this.settings = settings;
        this.alerts = new AlertStore(store);
        this.notifier = settings.webhook() == null ? null : new Notifier(settings.webhook(), http);
        this.clock = clock;
        this.http = http;
    }

    /** The watch's own account of itself (04 §12), read by the peers as any metric. */
    public void registerMetrics(Metrics m) {
        m.labeledCounter("backend_watch_scrapes_total", "Reads of a target's metrics, by outcome: ok or failed.", "outcome", scrapes);
        m.labeledCounter("backend_watch_notifications_total",
                "Notifications, by outcome: sent, failed (the webhook did not take it), duplicate (another machine sent it), silenced.",
                "outcome", notifications);
        m.labeledGauge("backend_watch_alerts_firing", "Alerts firing now, raised by this machine's watch.", "machine",
                () -> Map.of(settings.machine(), (double) engine.firing().size()));
        m.gauge("backend_watch_round_seconds", "How long the last round took.", () -> lastRoundSeconds);
    }

    /** Rounds every 15 s from now on, the first after 15 s; a round that throws is logged and the next runs. */
    public void start() {
        thread.scheduleWithFixedDelay(this::safeRound, ROUND_MILLIS, ROUND_MILLIS, TimeUnit.MILLISECONDS);
        log.info("the watch reads {} target(s), {} peer(s), {} path(s); alerts {}", settings.targets().size(),
                settings.peers().size(), settings.disks().size(),
                settings.webhook() == null ? "listed and logged, sent nowhere (BACKEND_ALERT_WEBHOOK_URL unset)" : "sent to the webhook");
    }

    private void safeRound() {
        try {
            round();
        } catch (RuntimeException e) {
            log.error("a watch round failed; the next runs in {} s", ROUND_MILLIS / 1000, e);
        }
    }

    /** One round, now: read, evaluate, store, log, send. */
    public AlertEngine.Round round() {
        long started = System.nanoTime();
        long now = clock.getAsLong();
        // The targets and the peers read at once, a virtual thread each: a round is bounded by the timeout,
        // not by how many are down (item 102). The executor's close waits for every read.
        List<Future<String>> targetReads = new ArrayList<>();
        List<Future<String>> peerReads = new ArrayList<>();
        try (ExecutorService reads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (WatchSettings.Target t : settings.targets()) {
                targetReads.add(reads.submit(() -> get("http://" + t.host() + ":" + t.port() + "/metrics")));
            }
            for (WatchSettings.Peer p : settings.peers()) {
                peerReads.add(reads.submit(() -> get("http://" + p.host() + ":" + p.port() + "/health")));
            }
        }
        List<Sample> samples = new ArrayList<>();
        List<AlertStore.View.TargetUp> targetUps = new ArrayList<>();
        for (int i = 0; i < settings.targets().size(); i++) {
            WatchSettings.Target t = settings.targets().get(i);
            String instance = t.host() + ":" + t.port();
            Map<String, String> base = Map.of("job", t.job(), "instance", instance, "machine", settings.machine());
            String text = done(targetReads.get(i));
            targetUps.add(new AlertStore.View.TargetUp(t.job(), instance, text != null));
            if (text == null) {
                scrapes.increment("failed");
                samples.add(new Sample("up", base, 0));
                continue;
            }
            scrapes.increment("ok");
            samples.add(new Sample("up", base, 1));
            for (Sample s : MetricsText.parse(text)) {
                Map<String, String> labels = new TreeMap<>(s.labels());
                base.forEach(labels::putIfAbsent);
                samples.add(new Sample(s.name(), labels, s.value()));
            }
        }
        List<Sample> machine = MachineProbe.read(settings.disks(), settings.machine());
        lastMachineSamples = machine;
        samples.addAll(machine);
        List<AlertStore.View.PeerUp> peerUps = new ArrayList<>();
        for (int i = 0; i < settings.peers().size(); i++) {
            WatchSettings.Peer p = settings.peers().get(i);
            // No machine label: the peer's state is the site's, and the two machines that see it raise one alert.
            boolean up = done(peerReads.get(i)) != null;
            peerUps.add(new AlertStore.View.PeerUp(p.name(), up));
            samples.add(new Sample("peer_up", Map.of("peer", p.name()), up ? 1 : 0));
        }

        AlertEngine.Round round = engine.round(samples, now);
        for (Alert a : round.fired()) {
            log.warn("ALERT firing {} {}: {} ({})", a.name(), a.labels(), a.summary(), a.runbook());
        }
        for (Alert a : round.resolved()) {
            log.warn("ALERT resolved {} {}", a.name(), a.labels());
        }
        alerts.publish(engine.firing(), round.resolved());
        notify(round);
        double seconds = (System.nanoTime() - started) / 1e9;
        AlertStore.View view = view(now, seconds, targetUps, peerUps, machine);
        alerts.publishView(view);
        lastView = view;
        lastRoundSeconds = seconds;
        return round;
    }

    /** The round as the admin API shows it (04 §12): what was read, and the machine's own figures from its samples. */
    private AlertStore.View view(long now, double seconds, List<AlertStore.View.TargetUp> targets,
                                 List<AlertStore.View.PeerUp> peers, List<Sample> machine) {
        Map<String, long[]> disks = new TreeMap<>();
        long memoryFree = 0;
        long memorySize = 0;
        double load = -1;
        int cores = 0;
        for (Sample s : machine) {
            switch (s.name()) {
                case "machine_disk_free_bytes" -> disks.computeIfAbsent(s.labels().get("mount"), k -> new long[2])[0] = (long) s.value();
                case "machine_disk_size_bytes" -> disks.computeIfAbsent(s.labels().get("mount"), k -> new long[2])[1] = (long) s.value();
                case "machine_memory_free_bytes" -> memoryFree = (long) s.value();
                case "machine_memory_size_bytes" -> memorySize = (long) s.value();
                case "machine_load_1m" -> load = s.value();
                case "machine_cores" -> cores = (int) s.value();
                default -> { }
            }
        }
        List<AlertStore.View.Disk> diskList = new ArrayList<>();
        disks.forEach((mount, v) -> diskList.add(new AlertStore.View.Disk(mount, v[0], v[1])));
        return new AlertStore.View(settings.machine(), now, seconds, targets, peers, diskList, memoryFree, memorySize, load, cores,
                engine.firing().size());
    }

    /** A read's result, or null for one that failed. */
    private static String done(Future<String> read) {
        try {
            return read.get();
        } catch (ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void notify(AlertEngine.Round round) {
        if (notifier == null) {
            return;
        }
        Set<String> silenced = alerts.silencedNames();
        List<Alert> firing = new ArrayList<>();
        for (Alert a : engine.firing()) {
            if (silenced.contains(a.name())) {
                if (round.fired().contains(a)) {
                    notifications.increment("silenced");
                }
            } else if (alerts.claimSend(a.fingerprint(), "firing", settings.repeatMillis())) {
                firing.add(a);                                    // new, or its repeat due
            } else if (round.fired().contains(a)) {
                notifications.increment("duplicate");             // another machine's watch sent this one
            }
        }
        List<Alert> resolved = new ArrayList<>();
        for (Alert a : round.resolved()) {
            alerts.releaseSend(a.fingerprint(), "firing");        // fires again later: sent as new
            if (!silenced.contains(a.name()) && alerts.claimSend(a.fingerprint(), "resolved", RESOLVED_SENT_MILLIS)) {
                resolved.add(a);
            }
        }
        deliver("firing", firing);
        deliver("resolved", resolved);
    }

    private void deliver(String status, List<Alert> list) {
        if (list.isEmpty()) {
            return;
        }
        if (notifier.send(status, settings.machine(), list)) {
            notifications.increment("sent");
        } else {
            notifications.increment("failed");
            for (Alert a : list) {
                alerts.releaseSend(a.fingerprint(), status);      // so the next round, or another machine, tries again
            }
        }
    }

    /** The body of a 200 within the timeout, or null. */
    private String get(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public List<Alert> firing() {
        return engine.firing();
    }

    /** What the last round saw, as the store holds it; null before the first round. */
    public AlertStore.View lastView() {
        return lastView;
    }

    /** The machine's own samples from the last round, for the tests. */
    public List<Sample> lastMachineSamples() {
        return lastMachineSamples;
    }

    /** How many series' histories the engine keeps: the watch's memory, for the tests to report. */
    public int seriesKept() {
        return engine.seriesKept();
    }

    @Override
    public void close() {
        thread.shutdownNow();
    }
}
