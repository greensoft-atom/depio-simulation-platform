package com.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    @DisplayName("renders the Prometheus text format: help, type, series, sorted, labels escaped")
    void format() {
        Metrics m = new Metrics();
        AtomicLong applied = new AtomicLong(7);
        m.counter("backend_b_total", "Things counted.", applied::get);
        m.gauge("backend_a_ratio", "A fraction.", () -> 0.25);
        Metrics.LabeledCounter outcomes = m.labeledCounter("backend_c_total", "By outcome.", "outcome");
        outcomes.increment("ok");
        outcomes.increment("ok");
        outcomes.increment("say \"no\"\n");
        m.labeledGauge("backend_d", "By room.", "room", () -> Map.of("room-1", 3.0));
        m.labeledCounter("backend_e_total", "Read from elsewhere.", "profile",
                () -> Map.of("saver", 5.0, "high", 1.0));

        assertThat(m.render()).isEqualTo("""
                # HELP backend_a_ratio A fraction.
                # TYPE backend_a_ratio gauge
                backend_a_ratio 0.25
                # HELP backend_b_total Things counted.
                # TYPE backend_b_total counter
                backend_b_total 7
                # HELP backend_c_total By outcome.
                # TYPE backend_c_total counter
                backend_c_total{outcome="ok"} 2
                backend_c_total{outcome="say \\"no\\"\\n"} 1
                # HELP backend_d By room.
                # TYPE backend_d gauge
                backend_d{room="room-1"} 3
                # HELP backend_e_total Read from elsewhere.
                # TYPE backend_e_total counter
                backend_e_total{profile="high"} 1
                backend_e_total{profile="saver"} 5
                """);
    }

    @Test
    @DisplayName("a histogram: cumulative buckets up to +Inf, a sum and a count, for each label value")
    void histogram() {
        Metrics m = new Metrics();
        Metrics.LabeledHistogram waits = m.labeledHistogram("backend_wait_seconds", "Waited.", "mode", 1, 10);
        waits.observe("duel", 0.5);
        waits.observe("duel", 1);
        waits.observe("duel", 4);
        waits.observe("duel", 30);
        waits.observe("tvt", 2.5);
        assertThat(waits.count("duel")).isEqualTo(4);
        assertThat(m.render()).isEqualTo("""
                # HELP backend_wait_seconds Waited.
                # TYPE backend_wait_seconds histogram
                backend_wait_seconds_bucket{mode="duel",le="1"} 2
                backend_wait_seconds_bucket{mode="duel",le="10"} 3
                backend_wait_seconds_bucket{mode="duel",le="+Inf"} 4
                backend_wait_seconds_sum{mode="duel"} 35.5
                backend_wait_seconds_count{mode="duel"} 4
                backend_wait_seconds_bucket{mode="tvt",le="1"} 0
                backend_wait_seconds_bucket{mode="tvt",le="10"} 1
                backend_wait_seconds_bucket{mode="tvt",le="+Inf"} 1
                backend_wait_seconds_sum{mode="tvt"} 2.5
                backend_wait_seconds_count{mode="tvt"} 1
                """);
    }

    @Test
    @DisplayName("a supplier that throws costs its own family, not the scrape")
    void brokenSupplierIsSkipped() {
        Metrics m = new Metrics();
        m.gauge("backend_broken", "Throws.", () -> {
            throw new IllegalStateException("store down");
        });
        m.gauge("backend_fine", "Does not.", () -> 1);
        assertThat(m.render()).doesNotContain("backend_broken").contains("backend_fine 1");
    }

    @Test
    @DisplayName("served at /metrics when an address is configured, and not at all when it is not")
    void server() throws Exception {
        Metrics m = new Metrics();
        m.gauge("backend_up", "Up.", () -> 1);
        assertThat(MetricsServer.startIfConfigured(m, Map.of())).isNull();
        try (MetricsServer s = MetricsServer.startIfConfigured(m, Map.of("BACKEND_METRICS_ADDR", "127.0.0.1:0"))) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + s.port() + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain; version=0.0.4");
            assertThat(r.body()).contains("backend_up 1");
            assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/other")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
    }
}
