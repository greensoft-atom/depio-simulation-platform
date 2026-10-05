package com.jredis.client;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Client-side counters and per-command latency histograms (microseconds). Thread-safe. */
public final class ClientMetrics {

    public final AtomicLong sent = new AtomicLong();
    public final AtomicLong received = new AtomicLong();
    public final AtomicLong serverErrors = new AtomicLong();
    public final AtomicLong timeouts = new AtomicLong();
    public final AtomicLong connectionLosses = new AtomicLong();
    public final AtomicLong reconnects = new AtomicLong();
    public final AtomicLong failedFast = new AtomicLong();

    private final Map<String, ConcurrentHistogram> latency = new ConcurrentHashMap<>();

    void record(String command, long micros) {
        latency.computeIfAbsent(command, c -> new ConcurrentHistogram(60_000_000L, 2))
                .recordValue(Math.min(Math.max(micros, 0), 60_000_000L));
    }

    /** Latency histograms by command name (a live view; copy before heavy analysis). */
    public Map<String, ? extends Histogram> latency() {
        return Collections.unmodifiableMap(latency);
    }

    @Override
    public String toString() {
        return "sent=" + sent + " received=" + received + " serverErrors=" + serverErrors + " timeouts=" + timeouts
                + " connectionLosses=" + connectionLosses + " reconnects=" + reconnects + " failedFast=" + failedFast;
    }
}
