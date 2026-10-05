package com.backend.common;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * A process's metrics, rendered in the Prometheus text format (0.0.4).
 *
 * Dependency-free on purpose: the format is a few lines of text, and Micrometer, although the
 * offline bundle carries it, would be a dependency for nothing. Each metric is a supplier read at scrape time, so most of them are
 * views of counters the process already keeps rather than new state to maintain.
 *
 * A supplier runs on the scrape thread. It must read only what is safe to read from another
 * thread: an atomic, a volatile, a concurrent collection. The room thread's own structures
 * are published into volatiles by the room thread, never read from here.
 */
public final class Metrics {

    private enum Type { COUNTER, GAUGE, HISTOGRAM }

    private record Family(String help, Type type, Supplier<Map<String, Double>> series) { }

    /** Sorted, so a scrape is stable and diffable. */
    private final Map<String, Family> families = new ConcurrentSkipListMap<>();

    public void counter(String name, String help, DoubleSupplier value) {
        families.put(name, new Family(help, Type.COUNTER, () -> Map.of("", value.getAsDouble())));
    }

    public void gauge(String name, String help, DoubleSupplier value) {
        families.put(name, new Family(help, Type.GAUGE, () -> Map.of("", value.getAsDouble())));
    }

    /** Counters that differ by one label, such as the outcome of a request. */
    public LabeledCounter labeledCounter(String name, String help, String label) {
        LabeledCounter c = new LabeledCounter();
        families.put(name, new Family(help, Type.COUNTER, () -> c.series(label)));
        return c;
    }

    /** Registers a counter a component already made, so it can count before metrics exist. */
    public void labeledCounter(String name, String help, String label, LabeledCounter existing) {
        families.put(name, new Family(help, Type.COUNTER, () -> existing.series(label)));
    }

    /** Counters another component keeps, read together and told apart by one label. */
    public void labeledCounter(String name, String help, String label,
                               Supplier<Map<String, Double>> values) {
        families.put(name, new Family(help, Type.COUNTER, labeled(label, values)));
    }

    /**
     * Observations told apart by one label, counted into fixed buckets: a Prometheus histogram,
     * whose quantiles the server works out from them (p50, p95).
     */
    public LabeledHistogram labeledHistogram(String name, String help, String label, double... bounds) {
        LabeledHistogram h = new LabeledHistogram(bounds);
        families.put(name, new Family(help, Type.HISTOGRAM, () -> h.series(label)));
        return h;
    }

    /** Registers a histogram a component already made, so it can observe before metrics exist. */
    public void labeledHistogram(String name, String help, String label, LabeledHistogram existing) {
        families.put(name, new Family(help, Type.HISTOGRAM, () -> existing.series(label)));
    }

    /** Gauges read together and told apart by one label, such as a room's name. */
    public void labeledGauge(String name, String help, String label,
                             Supplier<Map<String, Double>> values) {
        families.put(name, new Family(help, Type.GAUGE, labeled(label, values)));
    }

    private static Supplier<Map<String, Double>> labeled(String label, Supplier<Map<String, Double>> values) {
        return () -> {
            Map<String, Double> out = new TreeMap<>();
            values.get().forEach((k, v) -> out.put(label + "=\"" + escape(k) + "\"", v));
            return out;
        };
    }

    /** The JVM's own: heap, GC and threads, which every process has. */
    public void jvm() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        gauge("backend_jvm_heap_used_bytes", "Heap in use.",
                () -> memory.getHeapMemoryUsage().getUsed());
        gauge("backend_jvm_threads", "Live threads.",
                () -> ManagementFactory.getThreadMXBean().getThreadCount());
        counter("backend_jvm_gc_seconds_total", "Time spent in garbage collection.", () -> {
            long millis = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                millis += Math.max(0, gc.getCollectionTime());
            }
            return millis / 1000.0;
        });
        gauge("backend_process_uptime_seconds", "Seconds since the JVM started.",
                () -> ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
    }

    /** The text a scrape receives. A supplier that throws costs its own family, not the scrape. */
    public String render() {
        StringBuilder out = new StringBuilder(4096);
        for (Map.Entry<String, Family> e : families.entrySet()) {
            Map<String, Double> series;
            try {
                series = e.getValue().series().get();
            } catch (RuntimeException failed) {
                continue;
            }
            String name = e.getKey();
            out.append("# HELP ").append(name).append(' ').append(e.getValue().help()).append('\n');
            out.append("# TYPE ").append(name).append(' ')
                    .append(e.getValue().type().name().toLowerCase(Locale.ROOT)).append('\n');
            for (Map.Entry<String, Double> s : series.entrySet()) {
                String labels = s.getKey();
                out.append(name);
                if (e.getValue().type() == Type.HISTOGRAM) {
                    // A histogram's series are its buckets, sum and count: the suffix comes first.
                    int tab = labels.indexOf('\t');
                    out.append(labels, 0, tab);
                    labels = labels.substring(tab + 1);
                }
                if (!labels.isEmpty()) {
                    out.append('{').append(labels).append('}');
                }
                out.append(' ').append(number(s.getValue())).append('\n');
            }
        }
        return out.toString();
    }

    private static String number(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /** Label values may hold anything; the format escapes backslash, quote and newline. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * A histogram per label value, created on first use, over fixed upper bounds; safe from any
     * thread. A scrape during an observation may see its count before its bucket, which the
     * format's readers tolerate.
     */
    public static final class LabeledHistogram {
        private final double[] bounds;
        private final Map<String, Series> byValue = new ConcurrentHashMap<>();

        private static final class Series {
            final LongAdder[] buckets;
            final DoubleAdder sum = new DoubleAdder();
            final LongAdder count = new LongAdder();

            Series(int n) {
                buckets = new LongAdder[n];
                for (int i = 0; i < n; i++) {
                    buckets[i] = new LongAdder();
                }
            }
        }

        public LabeledHistogram(double... bounds) {
            this.bounds = bounds.clone();
        }

        public void observe(String value, double x) {
            Series s = byValue.computeIfAbsent(value, v -> new Series(bounds.length));
            for (int i = 0; i < bounds.length; i++) {
                if (x <= bounds[i]) {
                    s.buckets[i].increment();
                    break;
                }
            }
            s.sum.add(x);
            s.count.increment();
        }

        public long count(String value) {
            Series s = byValue.get(value);
            return s == null ? 0 : s.count.sum();
        }

        /** In the format's order: each value's buckets, cumulative, then its sum and its count. */
        Map<String, Double> series(String label) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String value : new TreeMap<>(byValue).keySet()) {
                Series s = byValue.get(value);
                String own = label + "=\"" + escape(value) + "\"";
                long cumulative = 0;
                for (int i = 0; i < bounds.length; i++) {
                    cumulative += s.buckets[i].sum();
                    out.put("_bucket\t" + own + ",le=\"" + number(bounds[i]) + "\"", (double) cumulative);
                }
                out.put("_bucket\t" + own + ",le=\"+Inf\"", (double) s.count.sum());
                out.put("_sum\t" + own, s.sum.sum());
                out.put("_count\t" + own, (double) s.count.sum());
            }
            return out;
        }
    }

    /** A counter per label value, created on first use. Safe from any thread. */
    public static final class LabeledCounter {
        private final Map<String, LongAdder> byValue = new ConcurrentHashMap<>();

        public void increment(String value) {
            byValue.computeIfAbsent(value, v -> new LongAdder()).increment();
        }

        public long get(String value) {
            LongAdder a = byValue.get(value);
            return a == null ? 0 : a.sum();
        }

        Map<String, Double> series(String label) {
            Map<String, Double> out = new TreeMap<>();
            byValue.forEach((k, v) -> out.put(label + "=\"" + escape(k) + "\"", (double) v.sum()));
            return out;
        }
    }
}
