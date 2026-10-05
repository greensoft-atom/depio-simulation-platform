package com.backend.worker;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.backend.persistence.EconomyRepository;
import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks, once a day across the fleet, that every player's coins and gems are what their ledger
 * says (05 §9, 06 §3). Until this, the only thing that ever compared them was the monthly restore
 * drill, so a balance that moved without a ledger row went unseen for up to a month.
 *
 * <h2>Once a day, not once per worker</h2>
 *
 * Every worker tries each hour to take a 23-hour lock in the store; whoever gets it runs the
 * check. The check only reads, so the lock needs no fencing token: two runs would cost a scan,
 * not correctness. Retention runs everywhere instead, but its deletes are cheap once done;
 * this is a full read of the ledger every time.
 *
 * <h2>One result for the fleet</h2>
 *
 * The result is written to the store, and every worker's metrics read it back, so an alert
 * sees the fleet's latest check whichever worker ran it, rather than one stale number per
 * worker. A check that fails hands the lock back, so a database blip costs an hour, not a day.
 */
final class LedgerCheck implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LedgerCheck.class);

    static final String LOCK_KEY = "job:ledger-check";
    static final String RESULT_KEY = "ledger:check:last";
    static final long LOCK_SECONDS = TimeUnit.HOURS.toSeconds(23);

    /** Another worker has today's check. */
    static final int NOT_RUN = -1;
    /** The check could not be made; the lock was handed back. */
    static final int FAILED = -2;

    private static final int SAMPLES = 20;

    private final EconomyRepository economy;
    private final JRedisClient store;
    private final String workerId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ledger-check");
        t.setDaemon(true);
        return t;
    });

    LedgerCheck(EconomyRepository economy, JRedisClient store, String workerId) {
        this.economy = economy;
        this.store = store;
        this.workerId = workerId;
    }

    /** Five minutes after start, after start-up recovery and retention; then every hour. */
    void start() {
        scheduler.scheduleWithFixedDelay(this::runIfDue, 5, 60, TimeUnit.MINUTES);
    }

    /** @return the players whose coins or gems differ from their ledger, or {@link #NOT_RUN}, {@link #FAILED} */
    int runIfDue() {
        try {
            if (!store.sync().set(LOCK_KEY, workerId, SetArgs.nx().andEx(LOCK_SECONDS))) {
                return NOT_RUN;
            }
        } catch (RuntimeException storeDown) {
            log.warn("ledger check: cannot reach the store, will try again: {}", storeDown.toString());
            return FAILED;
        }
        long started = System.nanoTime();
        try {
            EconomyRepository.Reconciliation r = economy.reconcile(SAMPLES);
            store.sync().set(RESULT_KEY, r.count() + " " + Instant.now().getEpochSecond());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (r.count() == 0) {
                log.info("ledger check: every balance matches its ledger ({} ms)", millis);
            } else {
                log.error("ledger check: {} players' balances differ from their ledger, for example {}"
                        + " ({} ms)", r.count(), r.samples(), millis);
            }
            return r.count();
        } catch (Exception e) {
            log.warn("ledger check failed, handing the day back: {}", e.toString());
            try {
                if (workerId.equals(store.sync().get(LOCK_KEY))) {
                    store.sync().del(LOCK_KEY);
                }
            } catch (RuntimeException storeDown) {
                // It expires on its own; the day is lost, and the next one is checked.
            }
            return FAILED;
        }
    }

    /**
     * The fleet's latest check, as {@code {mismatches, epochSeconds}}, or null if there has
     * been none. Read by the metrics at each scrape.
     */
    long[] latest() {
        String value = store.sync().get(RESULT_KEY);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(" ");
        return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
