package com.jredis.server.command;

import org.HdrHistogram.Histogram;

/** Per-command counters and a latency histogram (microseconds), created on first use. */
public final class CommandStat {

    public long calls;
    public long micros;
    public long rejected;
    public long failed;
    private Histogram histogram;

    public void record(long durationMicros) {
        calls++;
        micros += durationMicros;
        if (histogram == null) {
            histogram = new Histogram(3_600_000_000L, 2);
        }
        histogram.recordValue(Math.min(Math.max(durationMicros, 0), 3_600_000_000L));
    }

    public long percentile(double p) {
        return histogram == null ? 0 : histogram.getValueAtPercentile(p);
    }

    public void reset() {
        calls = 0;
        micros = 0;
        rejected = 0;
        failed = 0;
        histogram = null;
    }
}
