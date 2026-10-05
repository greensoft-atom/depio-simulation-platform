package com.backend.arena;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchResultCodec;
import com.backend.handoff.MatchResultStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gets a finished match out of the arena and into the queue, without losing it and without
 * making a room wait (docs development/01-code-patterns.md §2.8).
 *
 * <h2>The spool directory is the queue</h2>
 *
 * Every result is written to a file before the network is touched, and the file is deleted
 * only once the queue has accepted it. Every crash and every outage therefore leaves the
 * result somewhere: on disk, or in the queue. The cost is at-least-once delivery — a push
 * that succeeded but whose file could not be deleted is sent twice — which is exactly what
 * the idempotent apply on the other end is for.
 *
 * <h2>Everything reaches the disk first, not just the next thing</h2>
 *
 * This used to spool one result, then push it, then spool the next — and the push retried
 * for as long as the store was down. So during an outage the first result was on disk and
 * every result after it waited in memory behind that one push, for as long as the outage
 * lasted. That is precisely the case the spool exists for, and an arena killed during it lost
 * the whole backlog. Measured: five results into a publisher with the store down, one file.
 *
 * Now each pass of the loop writes <em>everything</em> waiting in memory to disk before it
 * tries the network at all, and a failing push waits for new results rather than for time,
 * so a result is on disk within milliseconds of arriving whatever the store is doing.
 *
 * <h2>Shutdown drains; it does not abandon</h2>
 *
 * {@link #close} used to set the running flag and then interrupt the thread. The interrupt
 * made the blocked {@code poll} throw, and the loop returned — before the drain that its own
 * loop condition promised. Measured: five results, close, one file. It no longer interrupts:
 * every wait in the loop is bounded, so the thread notices within {@value #RETRY_DELAY_MILLIS}
 * ms, writes what remains, and stops. A result published after that — a room finishing a
 * player's match a moment late — is written straight to disk by the caller.
 *
 * <h2>Not on the room thread</h2>
 *
 * A room has 40 ms per tick and pushing is network I/O, so the room thread only hands the
 * outcome over. The in-memory queue is unbounded: an offer that could fail would mean
 * choosing, on the room thread, between blocking a match and dropping a result.
 */
public final class MatchResultPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MatchResultPublisher.class);

    /** How long a failing push waits before trying again, while still accepting new results. */
    static final int RETRY_DELAY_MILLIS = 2_000;

    /** How long the loop waits for work when there is nothing to push. */
    private static final int IDLE_WAIT_MILLIS = 500;

    /** How long shutdown spends delivering the backlog before leaving the rest on disk. */
    private static final long SHUTDOWN_DELIVERY_MILLIS = 5_000;

    /**
     * How long {@link #close} waits for the loop before writing the remainder itself. Longer
     * than the delivery budget, so a healthy shutdown is never cut short by its own timeout.
     */
    private static final long CLOSE_WAIT_MILLIS = SHUTDOWN_DELIVERY_MILLIS + 5_000;

    private final MatchResultStream queue;
    private final Path spoolDir;
    private final LinkedBlockingDeque<MatchOutcome> pending = new LinkedBlockingDeque<>();

    /**
     * Spool files not yet accepted by the queue, oldest first.
     *
     * Kept in memory so the loop does not list the directory on every pass — draining a
     * backlog of a hundred thousand files that way is quadratic. Touched only by the
     * publisher thread, and by {@link #start} before that thread exists.
     */
    private final ArrayDeque<Path> toPush = new ArrayDeque<>();

    private final Thread thread;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong spooled = new AtomicLong();
    private final AtomicLong replayed = new AtomicLong();
    private final AtomicLong spoolFailures = new AtomicLong();
    private final AtomicLong unreplicated = new AtomicLong();
    private final boolean waitForReplica;

    /** How long a result waits for the events replica before the spool file goes anyway (Q-11). */
    static final long REPLICA_WAIT_MILLIS = 100;

    private volatile boolean running = true;
    private volatile boolean closed;

    public MatchResultPublisher(MatchResultStream queue, Path spoolDir) {
        this(queue, spoolDir, false);
    }

    /**
     * @param waitForReplica the events store has a replica: after adding a result, wait a moment
     *                       for it to hold it, and count those it did not confirm (Q-11)
     */
    public MatchResultPublisher(MatchResultStream queue, Path spoolDir, boolean waitForReplica) {
        this.queue = queue;
        this.spoolDir = spoolDir;
        this.waitForReplica = waitForReplica;
        try {
            Files.createDirectories(spoolDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the result spool " + spoolDir, e);
        }
        this.thread = new Thread(this::run, "match-result-publisher");
        // A daemon: safety comes from the disk, not from this thread staying alive. A push
        // wedged on a store that never answers must not keep the process from exiting once
        // close() has written everything down.
        this.thread.setDaemon(true);
    }

    /**
     * Sends whatever a previous run left on disk, then starts accepting new results.
     *
     * The backlog goes first and synchronously, so an old result reaches the queue before a
     * new one and a worker sees a player's matches in the order they were played. If the store
     * is down, whatever cannot be sent now waits in line ahead of anything new.
     */
    public void start() {
        List<Path> backlog = spooledFiles();
        if (!backlog.isEmpty()) {
            log.info("replaying {} spooled match results", backlog.size());
        }
        boolean storeUp = true;
        for (Path file : backlog) {
            if (storeUp && push(file)) {
                replayed.incrementAndGet();
            } else {
                storeUp = false;            // keep the order; the thread retries the rest
                toPush.addLast(file);
            }
        }
        thread.start();
    }

    /**
     * Called on a room thread. Does no network I/O and never blocks.
     *
     * After {@link #close}, writes the result to disk on the caller's thread instead: there
     * is no longer anyone to hand it to, and the next start sends it.
     */
    public void publish(MatchOutcome outcome) {
        pending.addLast(outcome);
        // Checked after the add, not before. Checking first leaves a window: read "not
        // closed", lose the processor, and add the result after close() has finished
        // draining — to a queue nobody will ever read again. Checking after, one of two
        // things is true: close() had not drained yet and will see this result, or it had,
        // in which case the deque's lock guarantees this thread now sees closed == true and
        // writes the result down itself.
        if (closed) {
            drainPendingToDisk();
        }
    }

    public long publishedCount() {
        return published.get();
    }

    public long spooledCount() {
        return spooled.get();
    }

    public long replayedCount() {
        return replayed.get();
    }

    /**
     * Attempts to write a result to disk that failed. The result is kept in memory and tried
     * again every {@value #RETRY_DELAY_MILLIS} ms, so a disk that stays full adds one each time.
     */
    public long spoolFailureCount() {
        return spoolFailures.get();
    }

    /** Results added to the store that its replica had not confirmed within the wait (Q-11). */
    public long unreplicatedCount() {
        return unreplicated.get();
    }

    // ---- the publisher thread ---------------------------------------------------------

    private void run() {
        while (running) {
            if (!spoolPending()) {
                // The refused result is back at the front of the line, where a wait for work
                // finds it at once: without this the loop spun, an error logged and a failure
                // counted on every pass - seven thousand a second, measured.
                pause(RETRY_DELAY_MILLIS);
            }
            Path next = toPush.peekFirst();
            if (next == null) {
                waitForWork(IDLE_WAIT_MILLIS);
                continue;
            }
            if (push(next)) {
                toPush.pollFirst();
                continue;
            }
            // The store is down. Wait — but for new results, not merely for time, so that
            // one arriving now is on disk now rather than after the outage.
            waitForWork(RETRY_DELAY_MILLIS);
        }
        drainOnShutdown();
    }

    /**
     * On the way out: write down anything still in memory, then deliver the backlog if the
     * store will take it.
     *
     * Delivering matters as much as writing down. The first version of this stopped as soon
     * as everything was on disk, so a deploy with forty players connected left 39 results in
     * the spool — measured in the restart drill. Safe, and replayed on the next start; but
     * an arena being retired has no next start, and those results would wait on its disk for
     * somebody to notice. The disk is the fallback for when the store is down, not the normal
     * route out of every restart.
     *
     * Bounded, and it stops at the first refused push: if the store is down there is no
     * point spending the rest of the shutdown finding that out again for every file.
     */
    private void drainOnShutdown() {
        spoolPending();
        long deadline = System.currentTimeMillis() + SHUTDOWN_DELIVERY_MILLIS;
        while (!toPush.isEmpty() && System.currentTimeMillis() < deadline) {
            if (!push(toPush.peekFirst())) {
                break;                      // the store is down; the disk keeps the rest
            }
            toPush.pollFirst();
        }
        if (!toPush.isEmpty()) {
            log.warn("stopping with {} results on disk in {}; the next start sends them",
                    toPush.size(), spoolDir);
        }
    }

    /**
     * Writes everything waiting in memory to disk, oldest first.
     *
     * @return false if the disk refused one, which then waits at the front of the line
     */
    private boolean spoolPending() {
        MatchOutcome outcome;
        while ((outcome = pending.pollFirst()) != null) {
            Path file = writeSpool(outcome);
            if (file == null) {
                // The disk refused it. Keep it — in memory, at the front, to try again — rather
                // than drop it, which is what this used to do. A full disk is an alarm, and a
                // result is not the price of raising one.
                spoolFailures.incrementAndGet();
                pending.addFirst(outcome);
                return false;
            }
            toPush.addLast(file);
        }
        return true;
    }

    /** Sleeps, whatever arrives. Bounded, as every wait in the loop is. */
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Swallowed, for the reason given in waitForWork.
        }
    }

    /** Sleeps until a result arrives or the time is up, and puts the result back in line. */
    private void waitForWork(long millis) {
        try {
            MatchOutcome arrived = pending.pollFirst(millis, TimeUnit.MILLISECONDS);
            if (arrived != null) {
                pending.addFirst(arrived);
            }
        } catch (InterruptedException e) {
            // Deliberately swallowed. Nothing interrupts this thread on purpose, and if
            // something does, carrying on is right: the loop's only exit is the running flag,
            // and leaving early on an interrupt is exactly how results used to be lost.
        }
    }

    /**
     * Writes the entry to disk, atomically: a temporary file then a rename, so a crash
     * halfway through leaves either nothing or a complete entry, never half of one that
     * would be replayed as garbage on the next start.
     *
     * @return the file, or null if the disk refused it.
     */
    private Path writeSpool(MatchOutcome outcome) {
        Path target = spoolDir.resolve(outcome.matchUid() + ".json");
        Path temp = spoolDir.resolve(outcome.matchUid() + ".tmp");
        try {
            Files.writeString(temp, MatchResultCodec.encode(outcome), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            spooled.incrementAndGet();
            return target;
        } catch (IOException | RuntimeException e) {
            log.error("could not spool match {}: {}", outcome.matchUid(), e.toString());
            return null;
        }
    }

    /**
     * One attempt to hand a spooled result to the queue.
     *
     * @return true if the file is dealt with — sent, or unreadable and left on disk for a
     *         human — and false if the store refused it and it should be tried again.
     */
    private boolean push(Path file) {
        String entry;
        try {
            entry = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Not retried here, because re-reading a file the disk will not give back would
            // wedge every result queued behind it. It stays on disk for the next start.
            log.error("could not read spooled match {}, leaving it for the next start: {}",
                    file.getFileName(), e.toString());
            return true;
        }
        try {
            queue.publish(entry, System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("could not publish {}, will retry: {}", file.getFileName(), e.toString());
            return false;
        }
        published.incrementAndGet();
        if (waitForReplica && !replicated()) {
            // On the primary alone: lost only if its machine is, before the stream reaches the
            // replica. Let go all the same: keeping it would push it again, and every result after
            // it, for as long as the replica is down (Q-11). The count shows the window is open.
            unreplicated.incrementAndGet();
        }
        deleteQuietly(file);
        return true;
    }

    private boolean replicated() {
        try {
            return queue.replicated(REPLICA_WAIT_MILLIS);
        } catch (RuntimeException e) {
            return false;                        // added, but the store went before it could say
        }
    }

    /** Spool files on disk, oldest first: the names are ULIDs, which sort by time. */
    private List<Path> spooledFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(spoolDir, "*.json")) {
            entries.forEach(files::add);
        } catch (IOException e) {
            log.error("could not read the spool directory {}: {}", spoolDir, e.toString());
        }
        files.sort(null);
        return files;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // The entry is published; a leftover file only means it is sent twice, which
            // the consumer is built to absorb.
            log.warn("published but could not remove {}: {}", file.getFileName(), e.toString());
        }
    }

    /**
     * Stops, having written every result it was given to disk and handed the store what it
     * would take within {@value #SHUTDOWN_DELIVERY_MILLIS} ms. What it did not take stays on
     * disk; the next start sends it. What cannot be written at all is logged by match id, because a loss that is not
     * named is a loss nobody can reconcile afterwards.
     */
    @Override
    public void close() {
        running = false;
        try {
            thread.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closed = true;
        // Normally empty: the loop's last pass wrote it. Not empty if the loop is wedged in a
        // push that will not return, in which case this thread writes the rest.
        drainPendingToDisk();
    }

    /** Writes whatever is waiting to disk from the calling thread. Safe from any thread. */
    private void drainPendingToDisk() {
        MatchOutcome outcome;
        while ((outcome = pending.pollFirst()) != null) {
            if (writeSpool(outcome) == null) {
                log.error("LOST match {}: could not be spooled during shutdown", outcome.matchUid());
            }
        }
    }
}
