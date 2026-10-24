package com.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.common.MetricsText.Sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reader of what {@link Metrics#render} writes, for the watch (04 §12, D-86). */
class MetricsTextTest {

    @Test
    @DisplayName("what render() writes is read back whole: every family, labels unescaped, a histogram's buckets, sum and count")
    void roundTrip() {
        Metrics m = new Metrics();
        AtomicLong applied = new AtomicLong(7);
        m.counter("backend_b_total", "Things counted.", applied::get);
        m.gauge("backend_a_ratio", "A fraction.", () -> 0.25);
        Metrics.LabeledCounter outcomes = m.labeledCounter("backend_c_total", "By outcome.", "outcome");
        outcomes.increment("ok");
        outcomes.increment("say \"no\"\n");
        m.labeledGauge("backend_d", "By room.", "room", () -> Map.of("room-1", 3.0));
        Metrics.LabeledHistogram h = m.labeledHistogram("backend_h_seconds", "By route.", "route", 0.1, 1);
        h.observe("/v1/x", 0.05);
        h.observe("/v1/x", 5);

        List<Sample> samples = MetricsText.parse(m.render());

        assertThat(samples).contains(
                new Sample("backend_b_total", Map.of(), 7),
                new Sample("backend_a_ratio", Map.of(), 0.25),
                new Sample("backend_c_total", Map.of("outcome", "ok"), 1),
                new Sample("backend_c_total", Map.of("outcome", "say \"no\"\n"), 1),
                new Sample("backend_d", Map.of("room", "room-1"), 3),
                new Sample("backend_h_seconds_bucket", Map.of("route", "/v1/x", "le", "0.1"), 1),
                new Sample("backend_h_seconds_bucket", Map.of("route", "/v1/x", "le", "1"), 1),
                new Sample("backend_h_seconds_bucket", Map.of("route", "/v1/x", "le", "+Inf"), 2),
                new Sample("backend_h_seconds_sum", Map.of("route", "/v1/x"), 5.05),
                new Sample("backend_h_seconds_count", Map.of("route", "/v1/x"), 2));
        assertThat(samples).as("no comment or blank line becomes a sample").allSatisfy(s -> assertThat(s.name()).startsWith("backend_"));
    }

    @Test
    @DisplayName("every value the format allows: NaN, the infinities, exponents, negatives, and a timestamp after the value ignored")
    void values() {
        List<Sample> samples = MetricsText.parse("""
                a NaN
                b +Inf
                c -Inf
                d 1.5e+09
                e -3
                f 42 1700000000000
                g{} 0
                """);
        assertThat(samples).extracting(Sample::name).containsExactly("a", "b", "c", "d", "e", "f", "g");
        assertThat(samples.get(0).value()).isNaN();
        assertThat(samples.get(1).value()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(samples.get(2).value()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(samples.get(3).value()).isEqualTo(1.5e9);
        assertThat(samples.get(4).value()).isEqualTo(-3);
        assertThat(samples.get(5).value()).isEqualTo(42);
        assertThat(samples.get(6).labels()).isEmpty();
    }

    @Test
    @DisplayName("a label value may hold commas, braces, quotes and spaces; labels come back sorted by name")
    void labels() {
        List<Sample> samples = MetricsText.parse("""
                a{z="x,y}z", m="it says \\"hi\\" {here}", a="1 2"} 1
                """);
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).labels()).containsExactly(
                Map.entry("a", "1 2"), Map.entry("m", "it says \"hi\" {here}"), Map.entry("z", "x,y}z"));
    }

    @Test
    @DisplayName("a line it cannot read is skipped, and the lines around it are kept")
    void malformedLinesAreSkipped() {
        List<Sample> samples = MetricsText.parse("""
                good_before 1
                garbage line here
                unterminated{a="x 2
                not_a_number abc
                no_value
                good_after{k="v"} 3
                """);
        assertThat(samples).containsExactly(
                new Sample("good_before", Map.of(), 1),
                new Sample("good_after", Map.of("k", "v"), 3));
    }

    @Test
    @DisplayName("nothing, or comments alone, read as no samples")
    void nothing() {
        assertThat(MetricsText.parse("")).isEmpty();
        assertThat(MetricsText.parse("# HELP a b\n# TYPE a gauge\n\n")).isEmpty();
    }
}
