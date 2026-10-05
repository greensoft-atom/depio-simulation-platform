package com.jredis.server.core;

/**
 * Work the command thread does between batches, in slices small enough that a command arriving
 * meanwhile waits at most about one slice: blocked-client timeouts, active expiry, incremental
 * rehash, snapshot progress and once-a-second housekeeping. A duty cycle cap keeps it below a
 * configured share of the thread's time; the snapshot always gets some progress.
 */
final class Background {

    private static final long WINDOW_NANOS = 100_000_000L;
    private static final long EXPIRY_TICK_NANOS = 10_000_000L;
    private static final long IDLE_PARK_NANOS = 10_000_000L;

    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

    private final Engine engine;
    private long windowStart;
    private long windowBusy;
    private long lastExpiryTick;
    private long lastSnapshotSlice;
    private long lastSampleMillis;
    private long lastCronMillis;

    Background(Engine engine) {
        this.engine = engine;
    }

    void run() {
        long start = System.nanoTime();
        if (start - windowStart >= WINDOW_NANOS) {
            if (windowStart != 0) {
                engine.stats().backgroundDutyPercent = 100.0 * windowBusy / (start - windowStart);
            }
            windowStart = start;
            windowBusy = 0;
        }
        long slice = engine.config().backgroundSliceMicros() * 1000L;
        long cap = WINDOW_NANOS * engine.config().backgroundMaxDuty() / 100;
        boolean overCap = windowBusy >= cap;
        long deadline = start + slice;
        long nowMillis = engine.clock().nowMillis();

        engine.expireBlockedClients();

        if (nowMillis - lastSampleMillis >= 100) {
            lastSampleMillis = nowMillis;
            engine.stats().sample(nowMillis);
        }
        if (nowMillis - lastCronMillis >= 1000) {
            lastCronMillis = nowMillis;
            engine.cronClients(nowMillis);
            if (THREADS.isCurrentThreadCpuTimeSupported()) {
                engine.stats().sampleCpu(THREADS.getCurrentThreadCpuTime(), System.nanoTime());
            }
        }
        if (!overCap && engine.activeExpire() && start - lastExpiryTick >= EXPIRY_TICK_NANOS) {
            lastExpiryTick = start;
            engine.db().activeExpire(deadline);
        }
        if (!overCap && engine.db().isRehashing() && System.nanoTime() - deadline < 0) {
            engine.db().rehash(deadline - System.nanoTime());
        }
        boolean snapshotStarved = start - lastSnapshotSlice >= WINDOW_NANOS;
        if (!overCap || snapshotStarved) {
            long d = deadline - System.nanoTime() >= slice / 2 ? deadline : System.nanoTime() + slice / 2;
            engine.persistence().background(d);
            engine.replication().background(d);
            lastSnapshotSlice = System.nanoTime();
        }
        windowBusy += System.nanoTime() - start;
    }

    /**
     * How long the command thread may sleep when no commands are waiting (a command wakes it at
     * once anyway). Short only while background work can actually progress; never past the next
     * blocked-client deadline.
     */
    long parkNanos() {
        long now = System.nanoTime();
        boolean overCap = windowBusy >= WINDOW_NANOS * engine.config().backgroundMaxDuty() / 100;
        boolean expiryDue = engine.activeExpire() && engine.db().hasDueExpiries();   // disabled expiry leaves due work behind
        long park = IDLE_PARK_NANOS;
        if (engine.persistence().canProgress() || engine.replication().canProgress() || engine.db().isRehashing()) {
            park = overCap ? Math.max(WINDOW_NANOS - (now - windowStart), 100_000L) : 50_000L;
        } else if (engine.persistence().busy()) {
            park = 1_000_000L;                                  // waiting for the snapshot writer
        }
        if (expiryDue) {
            long untilTick = Math.max(50_000L, lastExpiryTick + EXPIRY_TICK_NANOS - now);
            park = Math.min(park, overCap ? Math.max(WINDOW_NANOS - (now - windowStart), untilTick) : untilTick);
        }
        long nextTimeout = engine.blocking().nextTimeoutMillis();
        if (nextTimeout != Long.MAX_VALUE) {
            long waitMillis = nextTimeout - engine.clock().nowMillis();
            park = Math.min(park, Math.max(100_000L, waitMillis * 1_000_000L));
        }
        return park;
    }
}
