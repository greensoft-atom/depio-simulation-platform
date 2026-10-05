package com.backend.worker;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.backend.common.Metrics;
import com.backend.handoff.StoreClients;
import com.backend.persistence.Database;
import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The replicas' health, measured (docs architecture/02-availability.md §7, D-58): MySQL's
 * heartbeat stamped on the primary every second, each MySQL replica's lag read every five on a
 * thread of its own, so a replica that hangs delays neither the stamp nor a scrape, and each
 * store's replicas as its primary tells them, at every scrape.
 */
final class ReplicaWatch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplicaWatch.class);

    static final long BEAT_MILLIS = 1_000;
    static final long READ_MILLIS = 5_000;

    private final Database db;
    private final ScheduledExecutorService threads = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "replica-watch");
        t.setDaemon(true);
        return t;
    });
    /** Each MySQL replica's lag at the last read, NaN for one not read. */
    private volatile Map<String, Double> lags = Map.of();
    /** Said once when stamping starts failing, and once when it works again. Stamp thread only. */
    private boolean failing;

    ReplicaWatch(Database db) {
        this.db = db;
    }

    void start() {
        threads.scheduleWithFixedDelay(this::beat, 0, BEAT_MILLIS, TimeUnit.MILLISECONDS);
        threads.scheduleWithFixedDelay(() -> lags = db.replicaLags(), 0, READ_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void beat() {
        try {
            db.heartbeat();
            if (failing) {
                log.info("the heartbeat is stamped again");
                failing = false;
            }
        } catch (Exception e) {
            if (!failing) {
                log.warn("the heartbeat could not be stamped: {}", e.toString());
                failing = true;
            }
        }
    }

    Map<String, Double> lags() {
        return lags;
    }

    void registerMetrics(Metrics m, JRedisClient store, JRedisClient events) {
        m.labeledGauge("backend_mysql_replica_lag_seconds",
                "How far each MySQL replica has applied behind the primary, by the heartbeat; NaN when not read.",
                "host", () -> lags);
        m.labeledGauge("backend_mysql_replica_up", "1 while each MySQL replica's heartbeat can be read.", "host", () -> {
            Map<String, Double> up = new TreeMap<>();
            lags.forEach((host, lag) -> up.put(host, lag.isNaN() ? 0.0 : 1.0));
            return up;
        });
        StoreClients.registerReplicaMetrics(m, store, "store");
        if (events != store) {
            StoreClients.registerReplicaMetrics(m, events, "events_store");
        }
    }

    @Override
    public void close() {
        threads.shutdownNow();
    }
}
