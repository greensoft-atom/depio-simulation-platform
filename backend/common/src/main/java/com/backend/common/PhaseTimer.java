package com.backend.common;

import java.io.PrintStream;
import java.util.Locale;

import org.HdrHistogram.Histogram;

/**
 * Per-phase nanosecond histograms for one tick loop.
 *
 * Measuring phases separately is the point: a tick that misses its budget is useless
 * information, while "collide is 70 % of it" tells you what to fix. See
 * docs detailed-design/07 §4.
 */
public final class PhaseTimer {

    private final String[] names;
    private final Histogram[] histograms;
    private final long[] started;
    private final Histogram total = new Histogram(3);

    public PhaseTimer(String... names) {
        this.names = names.clone();
        this.histograms = new Histogram[names.length];
        this.started = new long[names.length];
        for (int i = 0; i < names.length; i++) {
            histograms[i] = new Histogram(3);
        }
    }

    public void start(int phase) {
        started[phase] = System.nanoTime();
    }

    public void stop(int phase) {
        histograms[phase].recordValue(System.nanoTime() - started[phase]);
    }

    public void recordTotal(long nanos) {
        total.recordValue(nanos);
    }

    public void reset() {
        for (Histogram h : histograms) {
            h.reset();
        }
        total.reset();
    }

    public double totalP99Millis() {
        return total.getValueAtPercentile(99.0) / 1e6;
    }

    public void report(PrintStream out, String title, double budgetMillis) {
        out.printf(Locale.ROOT, "%n=== %s ===%n", title);
        out.printf(Locale.ROOT, "%-16s %10s %10s %10s %10s%n", "phase", "p50", "p99", "p99.9", "max");
        for (int i = 0; i < names.length; i++) {
            print(out, names[i], histograms[i]);
        }
        out.printf(Locale.ROOT, "%-16s %10s %10s %10s %10s%n", "-".repeat(16), "", "", "", "");
        print(out, "TOTAL", total);

        double p99 = totalP99Millis();
        out.printf(Locale.ROOT, "%nbudget %.1f ms   p99 %.3f ms   %s (%.0f%% of budget)%n",
                budgetMillis, p99, p99 <= budgetMillis ? "PASS" : "FAIL", p99 / budgetMillis * 100);
    }

    /** Like {@link #report} but without the total, for timers whose phases do not sum. */
    public void reportPhasesOnly(PrintStream out, String title) {
        out.printf(Locale.ROOT, "%n=== %s ===%n", title);
        out.printf(Locale.ROOT, "%-16s %10s %10s %10s %10s%n", "phase", "p50", "p99", "p99.9", "max");
        for (int i = 0; i < names.length; i++) {
            print(out, names[i], histograms[i]);
        }
    }

    private static void print(PrintStream out, String name, Histogram h) {
        out.printf(Locale.ROOT, "%-16s %9.3fms %9.3fms %9.3fms %9.3fms%n", name,
                h.getValueAtPercentile(50) / 1e6, h.getValueAtPercentile(99) / 1e6,
                h.getValueAtPercentile(99.9) / 1e6, h.getMaxValue() / 1e6);
    }
}
