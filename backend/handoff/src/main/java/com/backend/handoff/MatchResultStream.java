package com.backend.handoff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisServerException;
import com.jredis.client.Streams;
import com.jredis.client.XAddArgs;

/**
 * The stream a match result crosses to reach {@code worker}
 * (docs detailed-design/05-worker-and-events.md, "The move onto streams"; D-33).
 *
 * <h2>One group, one consumer per worker</h2>
 *
 * Workers read {@value #KEY} as the group {@value #GROUP}, each as a consumer named by its id.
 * An entry delivered stays pending for its consumer until acknowledged, after the commit, so a
 * worker that dies mid-apply leaves it there; it re-drives its own pending entries, and any
 * worker claims those idle past {@link #claimIdleMillis}: a retired worker's included, which
 * a list could not do without knowing the worker was dead.
 *
 * <h2>The list stays, as an inbox</h2>
 *
 * {@link MatchResultQueue#KEY} is drained into the stream by every worker, each entry claimed
 * onto the worker's processing list first, added, then removed, so a crash in between leaves
 * it to be added again, which the idempotent apply absorbs. Arenas of the previous release,
 * and an operator putting dead entries back, still push to the list. The dead and deferred
 * lists are the same lists as before.
 */
public final class MatchResultStream {

    public static final String KEY = "s:match-result";
    public static final String GROUP = "rewards";
    /** The one field an entry has: the envelope {@link MatchResultCodec} writes. */
    public static final String FIELD = "e";
    /** How long the stream keeps an entry, read or not (Q-9, recommended). */
    public static final long RETENTION_MILLIS = 24L * 60 * 60 * 1000;
    /** How long an entry is pending, untouched, before any worker may take it over. */
    public static final long DEFAULT_CLAIM_IDLE_MILLIS = 60_000;
    private static final int CLAIM_BATCH = 64;

    /** An entry: its stream ID and its envelope. */
    public record Entry(String id, String payload) { }

    private final JRedisClient client;
    private final String consumer;
    private final MatchResultQueue lists;
    private final long claimIdleMillis;

    /** For a producer: an arena. */
    public MatchResultStream(JRedisClient client) {
        this(client, MatchResultQueue.DEFAULT_WORKER_ID, DEFAULT_CLAIM_IDLE_MILLIS);
    }

    public MatchResultStream(JRedisClient client, String workerId, long claimIdleMillis) {
        this.client = client;
        this.lists = new MatchResultQueue(client, workerId);
        this.consumer = workerId;
        this.claimIdleMillis = claimIdleMillis;
    }

    public String consumer() {
        return consumer;
    }

    /** Producer side: added, and the stream trimmed to {@link #RETENTION_MILLIS}. */
    public String publish(String payload, long nowMillis) {
        String oldest = Math.max(0, nowMillis - RETENTION_MILLIS) + "-0";
        return await(client.xadd(KEY, XAddArgs.minId(oldest).approximately(), "*", FIELD, payload));
    }

    /**
     * Producer side: whether a replica holds everything added so far, waiting up to
     * {@code timeoutMillis} ({@code WAIT 1}, j-redis 16 §8).
     */
    public boolean replicated(long timeoutMillis) {
        return await(client.send("WAIT", "1", Long.toString(timeoutMillis))).asLong() >= 1;
    }

    /**
     * Makes the group, reading from the start, if it is not there: at start-up, and again when
     * the stream was lost with it, a store restored empty or the key deleted.
     */
    public void ensureGroup() {
        try {
            await(client.xgroupCreate(KEY, GROUP, "0", true));
        } catch (IllegalStateException e) {
            if (!refused(e, "BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static boolean refused(IllegalStateException e, String code) {
        return e.getCause() instanceof JRedisServerException s && s.getMessage().startsWith(code);
    }

    /**
     * The next entry for this worker, now pending for it, or null if none came in time, or if
     * the group was gone, in which case it is made again.
     */
    public Entry claim(double timeoutSeconds) {
        Map<String, List<Streams.Entry>> got;
        try {
            got = await(client.blocking().xreadgroup(
                    (long) (timeoutSeconds * 1000), GROUP, consumer, 1, Map.of(KEY, ">")));
        } catch (IllegalStateException e) {
            if (!refused(e, "NOGROUP")) {
                throw e;
            }
            ensureGroup();
            return null;
        }
        List<Streams.Entry> es = got.get(KEY);
        return es == null || es.isEmpty() ? null : entry(es.get(0));
    }

    private static Entry entry(Streams.Entry e) {
        return new Entry(e.id(), e.fields().get(FIELD));
    }

    /** Done with, after its effect is committed. */
    public void ack(Entry e) {
        await(client.xack(KEY, GROUP, e.id()));
    }

    /**
     * Set aside for a person, on the dead list, and no longer pending. Pushed, then acknowledged,
     * as two commands: a transaction looks atomic and is not, since EXEC runs every command even
     * when one is refused, so a refused push would still be acknowledged and the entry be
     * nowhere. A crash between the two leaves it on the list and pending, and a redelivery sets
     * it aside again, which costs a duplicate of the evidence and loses nothing.
     */
    public void deadLetter(Entry e) {
        await(client.lpush(MatchResultQueue.DEAD_KEY, e.payload()));
        ack(e);
    }

    /** Kept for a newer worker, on the deferred list, and no longer pending. */
    public void defer(Entry e) {
        await(client.lpush(MatchResultQueue.DEFERRED_KEY, e.payload()));
        ack(e);
    }

    /**
     * This worker's pending entries, oldest first: delivered, not yet acknowledged. One trimmed
     * away since the last {@link #claimIdle} comes back without fields and is left out: the next
     * {@code claimIdle} reports it.
     */
    public List<Entry> abandoned() {
        Map<String, List<Streams.Entry>> got = await(client.xreadgroup(GROUP, consumer, 0, Map.of(KEY, "0")));
        List<Entry> out = new ArrayList<>();
        for (Streams.Entry e : got.getOrDefault(KEY, List.of())) {
            if (e.fields() != null) {
                out.add(entry(e));
            }
        }
        return out;
    }

    /**
     * Takes over entries any consumer has held untouched past the idle time, this one's own
     * included; they are then among {@link #abandoned()}.
     *
     * @return the IDs of pending entries found trimmed away before anyone applied them, which
     *         the store drops from the pending list however long they were idle
     */
    public List<String> claimIdle() {
        Streams.AutoClaim c = await(client.xautoclaim(KEY, GROUP, consumer, claimIdleMillis, "0", CLAIM_BATCH));
        return c.deleted();
    }

    /**
     * Moves the inbox into the stream, and what this worker's and the old shared processing
     * lists still hold, returning deferred entries first when {@code withDeferred}.
     *
     * @return how many entries were moved
     */
    public int drainInbox(boolean withDeferred) {
        if (withDeferred) {
            lists.requeueDeferred();                    // deferred list → inbox
        }
        lists.reclaimLegacy();                          // old shared processing list → inbox
        int moved = 0;
        for (String payload : lists.abandoned()) {      // this worker's, left by the list reader
            publish(payload, System.currentTimeMillis());
            lists.ack(payload);
            moved++;
        }
        String payload;
        while ((payload = lists.claimNow()) != null) {  // inbox → processing list → stream
            publish(payload, System.currentTimeMillis());
            lists.ack(payload);
            moved++;
        }
        return moved;
    }

    /** Entries not yet delivered to the group, and those still in the inbox. */
    public long depth() {
        long lag = 0;
        for (Streams.Group g : await(client.xinfoGroups(KEY))) {
            if (g.name().equals(GROUP)) {
                lag = g.lag();
            }
        }
        return lag + lists.depth();
    }

    /** Delivered to a worker and not acknowledged. */
    public long pendingCount() {
        return await(client.xpending(KEY, GROUP)).count();
    }

    public long deadCount() {
        return lists.deadCount();
    }

    public long deferredCount() {
        return lists.deferredCount();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
