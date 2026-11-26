package com.backend.account;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.account.db.Outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The events, delivered until taken (10 §3.5): RFC 8935's push, {@code POST} with the signed event as its body,
 * {@code Content-Type: application/secevent+jwt}, taken by a 202. Each receiver of {@code ACCOUNT_EVENTS_URL} is tried
 * in turn; when none takes it, again after 1, 2, 4 … seconds, at most {@link #MOST} apart, for as long as it takes. A
 * 400 is a receiver refusing the event itself, a setting to fix: logged as an error each time, and still retried, since
 * an event is a deletion the backend has to do.
 *
 * <p>One pass a second, and at once when a change adds one ({@link #wake}). Every pass claims what it delivers, for
 * {@link #LEASE}, so two processes do not deliver the same event at once; were they to, the backend takes an event
 * twice as once.
 */
public final class EventDelivery implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventDelivery.class);

    static final Duration MOST = Duration.ofMinutes(5);
    static final Duration LEASE = Duration.ofSeconds(60);
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int BATCH = 20;

    private final Outbox outbox;
    private final List<URI> receivers;
    private final HttpClient http;
    private final Clock clock;
    private final Semaphore woken = new Semaphore(0);
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Thread loop;
    private volatile boolean closed;

    public EventDelivery(Outbox outbox, List<URI> receivers, HttpClient http, Clock clock) {
        this.outbox = outbox;
        this.receivers = List.copyOf(receivers);
        this.http = http;
        this.clock = clock;
    }

    /** One pass: the events due, claimed and delivered. @return how many were taken */
    public int deliverDue() throws SQLException {
        int taken = 0;
        for (Outbox.Due due : outbox.claim(BATCH, clock.instant(), LEASE)) {
            if (deliver(due)) {
                outbox.taken(due.id(), clock.instant());
                delivered.incrementAndGet();
                taken++;
            } else {
                int attempts = due.attempts() + 1;
                Instant next = clock.instant().plus(backoff(attempts));
                outbox.failed(due.id(), attempts, next);
                failures.incrementAndGet();
                log.warn("event {} ({}) not taken, attempt {}: again at {}", due.jti(), due.kind(), attempts, next);
            }
        }
        return taken;
    }

    /** 1 s after the first failure, doubling, at most {@link #MOST}. */
    static Duration backoff(int attempts) {
        if (attempts >= 20) {
            return MOST;
        }
        Duration d = Duration.ofSeconds(1L << (attempts - 1));
        return d.compareTo(MOST) > 0 ? MOST : d;
    }

    public void start() {
        loop = Thread.ofVirtual().name("event-delivery").start(() -> {
            while (!closed) {
                try {
                    deliverDue();
                } catch (SQLException | RuntimeException e) {
                    log.warn("delivering events: {}", e.toString());
                }
                try {
                    woken.tryAcquire(1, TimeUnit.SECONDS);
                    woken.drainPermits();
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /** An event was added: deliver now, not at the next pass. */
    public void wake() {
        woken.release();
    }

    public long delivered() {
        return delivered.get();
    }

    public long failures() {
        return failures.get();
    }

    private boolean deliver(Outbox.Due due) {
        for (URI to : receivers) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(to).timeout(TIMEOUT)
                        .header("Content-Type", "application/secevent+jwt").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(due.token())).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 202) {
                    log.info("event {} ({}) taken by {}", due.jti(), due.kind(), to);
                    return true;
                }
                if (r.statusCode() == 400) {
                    log.error("event {} ({}) refused by {}: {}", due.jti(), due.kind(), to, r.body());
                } else {
                    log.warn("event {} ({}) not taken by {}: {}", due.jti(), due.kind(), to, r.statusCode());
                }
            } catch (IOException e) {
                log.warn("event {} ({}) not delivered to {}: {}", due.jti(), due.kind(), to, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
    }
}
