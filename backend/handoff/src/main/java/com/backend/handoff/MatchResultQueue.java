package com.backend.handoff;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.jredis.client.JRedisClient;

/**
 * The lists around {@link MatchResultStream}, the result queue until 2026-09-29
 * (docs detailed-design/05-worker-and-events.md, "The move onto streams"; D-33).
 *
 * <h2>What is left of the list queue</h2>
 *
 * {@link #KEY} is an inbox: what arenas of the list release push, and what an operator puts
 * back from the dead list, which every worker moves into the stream. The dead and deferred
 * lists are where the stream's readers set entries aside. A worker's processing list holds an
 * entry while it moves from the inbox to the stream, and what a list-reading worker had in
 * flight when it stopped.
 *
 * <h2>Why the claim is two lists</h2>
 *
 * {@code BLMOVE} moves an entry onto a processing list in the same operation that takes it
 * off the queue, so there is no instant where the entry exists only inside a worker that
 * might die. A worker that crashes mid-apply leaves its entry on its processing list and
 * finds it there when it comes back. Nothing is lost by a crash; something is applied twice,
 * which MySQL absorbs because the apply is idempotent.
 *
 * <h2>One processing list per worker</h2>
 *
 * There used to be one, shared. A worker starting up re-applied everything on it — and
 * could not tell an entry abandoned by a dead worker from one a live worker was halfway
 * through, so a rolling deploy re-applied other workers' in-flight entries underneath them.
 * Each worker now owns {@code q:match-result:processing:{workerId}}, which is what makes it
 * safe to re-drive that list while running rather than only once at start-up.
 *
 * The id must therefore be <em>stable</em> across restarts of the same worker and
 * <em>unique</em> among workers.
 */
public final class MatchResultQueue {

    public static final String KEY = "q:match-result";
    public static final String DEAD_KEY = "q:match-result:dead";

    /** Entries from a newer producer than this build reads, kept for a newer worker. */
    public static final String DEFERRED_KEY = "q:match-result:deferred";

    /**
     * The single shared processing list of earlier builds. Emptied back onto the queue at
     * start-up by {@link #reclaimLegacy}, so an upgrade does not strand what was in flight.
     */
    public static final String LEGACY_PROCESSING_KEY = "q:match-result:processing";

    /** Used when a deployment runs one worker and does not name it. */
    public static final String DEFAULT_WORKER_ID = "worker-1";

    public static String processingKeyFor(String workerId) {
        return LEGACY_PROCESSING_KEY + ":" + workerId;
    }

    private final JRedisClient client;
    private final String processingKey;

    public MatchResultQueue(JRedisClient client, String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.contains(" ")) {
            throw new IllegalArgumentException("a worker id must be a non-empty word: '" + workerId + "'");
        }
        this.client = client;
        this.processingKey = processingKeyFor(workerId);
    }

    /** An entry from the inbox, now on this worker's processing list, or null if it was empty. */
    public String claimNow() {
        return await(client.lmove(KEY, processingKey, false, true));
    }

    /** Removes an entry from the processing list once it is in the stream. */
    public void ack(String entry) {
        await(client.lrem(processingKey, 1, entry));
    }

    /** Everything on this worker's processing list: claimed and not yet acknowledged. */
    public List<String> abandoned() {
        return await(client.lrange(processingKey, 0, -1));
    }

    /**
     * Returns what an earlier build's shared processing list held to the queue.
     *
     * @return how many entries were moved.
     */
    public int reclaimLegacy() {
        return drainOnto(LEGACY_PROCESSING_KEY);
    }

    /**
     * Returns deferred entries to the queue, so a worker that has been upgraded picks them
     * up. An older worker doing the same simply defers them again, which costs a round trip.
     *
     * @return how many entries were moved.
     */
    public int requeueDeferred() {
        return drainOnto(DEFERRED_KEY);
    }

    private int drainOnto(String source) {
        int moved = 0;
        // One element at a time with an atomic move, so two workers doing this at once each
        // move a disjoint share and nothing is duplicated or dropped.
        while (await(client.lmove(source, KEY, false, true)) != null) {
            moved++;
        }
        return moved;
    }

    public long depth() {
        return await(client.llen(KEY));
    }

    public long deadCount() {
        return await(client.llen(DEAD_KEY));
    }

    public long deferredCount() {
        return await(client.llen(DEFERRED_KEY));
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
