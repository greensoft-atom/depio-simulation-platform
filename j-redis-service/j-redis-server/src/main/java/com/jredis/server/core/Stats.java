package com.jredis.server.core;

/** Server-wide counters for INFO. Command-thread only unless noted. */
public final class Stats {

    public long commandsProcessed;
    public long connectionsReceived;
    public long expiredKeys;
    public long keyspaceHits;
    public long keyspaceMisses;
    public long internalErrors;
    public long outputLimitDisconnects;
    public long dirty;
    public long rejectedCalls;
    public long failedCalls;
    public long authFailures;
    public long aofLoadErrors;

    /** Written by I/O threads. */
    public final java.util.concurrent.atomic.AtomicLong rejectedConnections = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong netInputBytes = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong netOutputBytes = new java.util.concurrent.atomic.AtomicLong();

    // instantaneous ops/sec: samples every 100 ms, averaged over 16 samples like Redis
    private final long[] opsSamples = new long[16];
    private int sampleIdx;
    private long lastSampleMillis;
    private long lastSampleCommands;

    /** CONFIG RESETSTAT: every counter, and the ops/sec baseline, so the rate never goes negative. */
    public void reset() {
        commandsProcessed = 0;
        connectionsReceived = 0;
        expiredKeys = 0;
        keyspaceHits = 0;
        keyspaceMisses = 0;
        internalErrors = 0;
        outputLimitDisconnects = 0;
        rejectedCalls = 0;
        failedCalls = 0;
        authFailures = 0;
        rejectedConnections.set(0);
        netInputBytes.set(0);
        netOutputBytes.set(0);
        java.util.Arrays.fill(opsSamples, 0);
        sampleIdx = 0;
        lastSampleMillis = 0;
        lastSampleCommands = 0;
    }

    public void sample(long nowMillis) {
        if (lastSampleMillis == 0) {
            lastSampleMillis = nowMillis;
            lastSampleCommands = commandsProcessed;
            return;
        }
        long dt = nowMillis - lastSampleMillis;
        if (dt < 100) {
            return;
        }
        long ops = (commandsProcessed - lastSampleCommands) * 1000 / dt;
        opsSamples[sampleIdx++ % opsSamples.length] = ops;
        lastSampleMillis = nowMillis;
        lastSampleCommands = commandsProcessed;
    }

    // command-thread CPU usage, sampled once a second
    public volatile double cmdThreadCpuPercent;
    public volatile double backgroundDutyPercent;
    private long lastCpuNanos;
    private long lastWallNanos;

    public void sampleCpu(long threadCpuNanos, long wallNanos) {
        if (lastWallNanos != 0 && wallNanos > lastWallNanos && threadCpuNanos >= 0) {
            cmdThreadCpuPercent = 100.0 * (threadCpuNanos - lastCpuNanos) / (wallNanos - lastWallNanos);
        }
        lastCpuNanos = threadCpuNanos;
        lastWallNanos = wallNanos;
    }

    public long instantaneousOpsPerSec() {
        int n = Math.min(sampleIdx, opsSamples.length);
        if (n == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += opsSamples[i];
        }
        return sum / n;
    }
}
