package com.backend.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.backend.common.PhaseTimer;
import com.backend.handoff.MatchMode;
import com.backend.handoff.MatchOutcome;
import com.backend.handoff.Ticket;
import com.backend.handoff.Ulid;
import com.backend.protocol.ClientMessage;
import com.backend.protocol.SnapshotWriter;
import com.backend.protocol.Wire;
import com.backend.sim.Entity;
import com.backend.sim.KillLog;
import com.backend.sim.LevelTable;
import com.backend.sim.PhraseTable;
import com.backend.sim.Room;
import com.backend.sim.Stat;
import com.backend.sim.TankStats;
import com.backend.sim.World;

import io.netty.channel.Channel;
import org.jctools.queues.MpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one room and runs its fixed timestep. Everything that touches world state happens
 * on this thread (docs detailed-design/07 §1).
 *
 * Netty threads reach it through its join, resume and leave queues, the counters the
 * registry allocates by, and the atomic and volatile slots on {@link Connection}. The watchdog
 * reaches it through {@link #abandon} and the concurrent sets {@link #members}, {@link #queued}
 * and {@link #queuedResumes}. The registry's resume map is written here and read there.
 * Nothing else crosses the boundary.
 */
public final class RoomThread implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RoomThread.class);

    private static final int TICK_HZ = 25;
    private static final long TICK_NANOS = 1_000_000_000L / TICK_HZ;

    /** Falling this far behind means catching up tick by tick is hopeless; resync instead. */
    private static final int MAX_LAG_TICKS = 10;

    private static final float VIEW_SIZE = 1600f;
    /** Ten input packets a second while playing (02 §9): a second of none is nobody driving. */
    private static final long STALE_INPUT_NANOS = 1_000_000_000L;
    private static final float DIAGONAL = 0.70710678f;

    /** Long matches are checked for once a second; every tick would be pointless work. */
    private static final int CHECKPOINT_INTERVAL_TICKS = TICK_HZ;

    /**
     * How often experience alone is worth re-sending: once a second.
     *
     * A level or a spent point goes out immediately. Experience moves every time a shape
     * dies, and at fifteen snapshots a second the event would cost more than every entity
     * update put together.
     */
    private static final int STATS_REFRESH_TICKS = TICK_HZ;

    /** Two seconds between one player's phrases (02 §3); one sooner is dropped, never kicked. */
    private static final int PHRASE_GAP_TICKS = 2 * TICK_HZ;
    private static final PhraseTable PHRASES = PhraseTable.defaults();

    private final Room room;
    private final int maxPlayers;
    private final int shapesPerMatch;
    private final MatchRules rules;
    private final MatchTally tally;
    private final java.util.function.Consumer<MatchOutcome> onMatchEnd;
    /** A sandbox's players' holds, kept while each is in it (D-54); null in every other room. */
    private volatile com.backend.handoff.SandboxHolds holds;
    /** Where a made match's first arrival is marked (Q-45); null in an open room and a sandbox. */
    private volatile com.backend.handoff.MatchArrivals arrivals;

    /** Never reused inside this room, so a kill can never be credited to the wrong player. */
    private long nextPlayerTag = 1;

    /**
     * In a timed match, the tag each player has had in it. A reconnect minted a second tag,
     * and the result then listed the player twice: the second copy was dropped by the
     * database's primary key, and everyone below was placed one rank too low (D-11). An open
     * match needs none of this: each connection's session is its own record.
     */
    private final java.util.Map<Long, Long> matchTags = new java.util.HashMap<>();
    private int matchEndsAtTick;

    /** A made match's stage (04 §4). Every other room is always playing. */
    private enum Stage { WAITING, PLAYING, OVER }

    /** A room as an operator sees it (04 §10): read from any thread. */
    public record RoomView(String room, int players, String matchUid, String mode, String stage) { }

    private volatile Stage stage = Stage.PLAYING;       // volatile: an operator's view reads it

    /** Players an operator has taken out, by id, for the room thread to kick (04 §10). */
    private final MpscArrayQueue<Long> removals = new MpscArrayQueue<>(64);
    /** What everyone is kicked with when the room stops: the server's fault, or an operator's close. */
    private volatile int stopReason = Wire.KICK_INTERNAL;
    private int joinWindowEndsAtTick;
    /** Co-op's waves, while a co-op match plays; null in every other room (01 §8.5). */
    private Waves waves;
    /** Co-op: the waves cleared so far that have been paid for (Q-37). */
    private int wavesPaid;
    /** Domination's dominators and clock (01 §8.7); null in every other mode. */
    private Domination domination;
    /** Tag's teams as they change (01 §8.8); null in every other mode. */
    private Tag tagMatch;
    /** A sandbox's powers (01 §8.10); null in any other room. */
    private Sandbox sandbox;
    /** Ticks a sandbox has had nobody in it, connected or with a stay waiting. */
    private int emptyTicks;
    /** The rebate on a respawn in the public arena (01 §7, Q-36): a quarter, up to level 20's. */
    static final int REBATE_DIVISOR = 4;
    static final int REBATE_MAX_LEVEL = 20;
    /** How long a sandbox stays empty before it ends: a minute. */
    static final int EMPTY_END_TICKS = 25 * 60;
    /** Tests only, that cannot wait a minute: {@link #EMPTY_END_TICKS} otherwise. */
    volatile int emptyEndTicks = EMPTY_END_TICKS;
    /** A maze's seed, which the Welcome carries (01 §8.9, D-48); 0 in every other mode. */
    private final long mazeSeed;

    /** A made match's players who have turned up, by player id: its roster as it arrived. */
    private final java.util.Set<Long> arrived = new java.util.HashSet<>();
    /** A made match that is over: the room has closed and the registry drops it. */
    private volatile boolean finished;

    private final MpscArrayQueue<Connection> joins = new MpscArrayQueue<>(1024);

    // ---- stays waiting for their player (02 §10) ----------------------------------------

    /**
     * A stay whose connection was lost without the player leaving. Room thread only.
     *
     * The tank stays in the world, parked and killable, for the rules' grace; then it is taken
     * out and what it had grown into is kept, until the rules' keep runs out and the stay is
     * ended as if the player had left then.
     */
    private static final class Suspended {
        final String secret;
        final Ticket identity;
        final long tag;
        final int matchStartTick;
        final int since;
        int entityId;           // the parked tank, or -1 once it has died or been taken out
        TankStats saved;        // what it had grown into, if it was taken out alive
        boolean died;
        int deathScore;
        String killer;

        Suspended(String secret, Ticket identity, long tag, int matchStartTick, int since, int entityId) {
            this.secret = secret;
            this.identity = identity;
            this.tag = tag;
            this.matchStartTick = matchStartTick;
            this.since = since;
            this.entityId = entityId;
        }
    }

    private final java.util.Map<String, Suspended> suspended = new java.util.LinkedHashMap<>();
    /** Secret to room, shared with the registry, so a resume reaches the room that holds it. */
    private final java.util.Map<String, RoomThread> stays;
    private static final int RESUME_QUEUE = 1024;
    private final MpscArrayQueue<Connection> resumes = new MpscArrayQueue<>(RESUME_QUEUE);
    /**
     * Resumes offered and not yet taken, as {@link #queued} is for joins: whoever removes a
     * connection from it answers it, so one the room will never drain - it failed, hung or
     * stopped - is sent away rather than left waiting in silence.
     */
    private final java.util.Set<Connection> queuedResumes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.security.SecureRandom SECRETS = new java.security.SecureRandom();
    private final AtomicInteger waiting = new AtomicInteger();
    private volatile long resumed;
    private volatile long staysExpired;

    /**
     * At least one place per player the room can hold, and one per resume it can queue, so it
     * can never be full: a leave comes from a connection the room admitted, reserved a place
     * for, or took a resume from, and there are at most that many of those however long the
     * room thread stalls (T-7).
     */
    private final MpscArrayQueue<Connection> leaves;
    private final List<Connection> active = new ArrayList<>();

    /**
     * Mirrors {@code active.size() + suspended.size()} for readers on other threads: a stay
     * waiting for its player keeps its place. Both are room thread only, so a registry
     * allocating a room must not touch them.
     */
    private final AtomicInteger players = new AtomicInteger();

    /**
     * Joins accepted by the allocator but not yet drained by this thread.
     *
     * Without it the allocator over-commits: {@code players} only moves when the room
     * thread drains its queue, up to a tick later, so a burst of connections all see the
     * same empty room and pile into it. Measured with 200 bots against 4 rooms of 60:
     * 54 were admitted then disconnected.
     */
    private final AtomicInteger reserved = new AtomicInteger();

    private final SnapshotEncoder encoder = new SnapshotEncoder();
    private final SnapshotWriter writer = new SnapshotWriter(4096);
    private final PhaseTimer simTimer = Room.newTimer();
    private final PhaseTimer netTimer = new PhaseTimer("encode+write");
    private final PhaseTimer tickTimer = new PhaseTimer();

    /** The current tick, readable from a Netty thread so a Pong can carry it. */
    private final AtomicInteger publishedTick = new AtomicInteger();

    private volatile boolean running = true;

    /**
     * A room closes itself after this many failed ticks within {@link #FAILURE_WINDOW_TICKS}
     * (07 §8). One is a bug worth a log line; three in four seconds is state that cannot be
     * trusted. A window, not a run: a fault that strikes only on one client's rounds, 15 ticks
     * in 25, fails at most two ticks in a row (off, on, off, on, on), and "three in a row"
     * never closed it.
     */
    static final int FAILED_TICKS_TO_CLOSE = 3;
    static final int FAILURE_WINDOW_TICKS = 100;

    /**
     * Loop iterations of the most recent failures, oldest overwritten first. Iterations, not
     * {@code room.tick()}: a failed tick does not advance the room's tick, so measured in it
     * the window stretched with every failure, and "three in four seconds" was not what the
     * code counted.
     */
    private final long[] failureIterations = new long[FAILED_TICKS_TO_CLOSE];
    private int failures;
    private long iterations;
    private volatile boolean failed;

    /** Tests only: runs at the start of every tick, so a test can make a tick fail. */
    volatile Runnable tickHook;

    /** When the loop last finished a tick, good or bad: the watchdog's measure of a hang. */
    private volatile long lastTickEndNanos = System.nanoTime();

    /**
     * Every player admitted and not yet gone, in a set the watchdog can read from its own
     * thread. {@link #active} is the room thread's; when that thread is stuck, this is how
     * the watchdog still finds the players to send back to the lobby.
     */
    private final java.util.Set<Connection> members = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Joins offered and not yet drained, for the same reason: when the room thread is stuck,
     * the watchdog cannot take them from {@link #joins}, which has one consumer, and that is
     * the stuck thread. Whoever removes a connection from here - the drain, the watchdog, or a
     * join that raced the watchdog - is the one who deals with it.
     */
    private final java.util.Set<Connection> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Thread thread;

    private volatile long overruns;     // volatile: read by the metrics thread; one writer
    private long ticks;

    // ---- for metrics: written by this thread only, read by the scrape --------------------

    /** Tick time within the last {@link #METRICS_EVERY_TICKS}, recorded here and read nowhere else. */
    private final org.HdrHistogram.Histogram intervalTicks = new org.HdrHistogram.Histogram(3);
    private static final int METRICS_EVERY_TICKS = 250;          // ten seconds
    private volatile double tickP99Millis;
    private volatile long snapshotsSent;
    private volatile long snapshotsSkipped;
    /** Rounds not sent because the client's link already held a second of them (02 §8). */
    private volatile long snapshotsHeld;
    /** By {@link TrafficProfile#ordinal()}: bytes sent at it, and clients at it now. */
    private final java.util.concurrent.atomic.AtomicLongArray bytesAt =
            new java.util.concurrent.atomic.AtomicLongArray(TrafficProfile.values().length);
    private final java.util.concurrent.atomic.AtomicIntegerArray clientsAt =
            new java.util.concurrent.atomic.AtomicIntegerArray(TrafficProfile.values().length);
    private final int[] counting = new int[TrafficProfile.values().length];
    private volatile long stepsDown;
    private volatile long stepsUp;

    /** For a sandbox's room, before it starts. */
    void holdSandboxes(com.backend.handoff.SandboxHolds holds) {
        this.holds = holds;
    }

    /** For a made match's room, before it starts. */
    void markArrivals(com.backend.handoff.MatchArrivals arrivals) {
        this.arrivals = arrivals;
    }

    /** Keeps a sandbox player's hold, or gives it back; never waits, as nothing on this thread may. */
    private void hold(long playerId, boolean keep) {
        com.backend.handoff.SandboxHolds h = holds;
        if (h == null) {
            return;
        }
        (keep ? h.keep(playerId, rules.matchUid()) : h.release(playerId)).whenComplete((r, e) -> {
            if (e != null) {
                log.warn("sandbox {}: could not {} player {}'s hold: {}", rules.matchUid(),
                        keep ? "keep" : "give back", playerId, e.toString());
            }
        });
    }

    /** Somebody came: a tournament waits for this match's result (Q-45). Never waits, as nothing here may. */
    private void markArrived() {
        com.backend.handoff.MatchArrivals a = arrivals;
        if (a != null) {
            a.mark(rules.matchUid()).whenComplete((r, e) -> {
                if (e != null) {
                    log.warn("match {}: could not mark its arrival: {}", rules.matchUid(), e.toString());
                }
            });
        }
    }

    /** The simulation this thread drives. For tests and diagnostics, not for other threads. */
    Room room() {
        return room;
    }

    public RoomThread(Room room, int maxPlayers, int shapesPerMatch, MatchRules rules,
                      java.util.function.Consumer<MatchOutcome> onMatchEnd,
                      java.util.Map<String, RoomThread> stays) {
        this.room = room;
        this.stays = stays;
        this.maxPlayers = maxPlayers;
        this.leaves = new MpscArrayQueue<>(Math.max(1024, maxPlayers) + RESUME_QUEUE);
        this.shapesPerMatch = shapesPerMatch;
        this.rules = rules;
        this.tally = new MatchTally(rules.arenaName());
        this.onMatchEnd = onMatchEnd;
        if (rules.isMade()) {
            stage = Stage.WAITING;
            joinWindowEndsAtTick = room.tick() + rules.joinWindowTicks();
        } else if (!rules.isOpen()) {
            beginMatch(Ulid.generate());
        }
        // A maze's walls from the start: the Welcome, sent at a join before the match begins, names
        // them, and every spawn keeps clear of them (01 §8.9).
        this.mazeSeed = rules.mode() == MatchMode.MAZE ? mazeSeedOf(rules.matchUid()) : 0L;
        if (mazeSeed != 0) {
            room.setWalls(new com.backend.sim.Walls(com.backend.sim.MazeGenerator.walls(mazeSeed),
                    com.backend.sim.MazeGenerator.CELL));
        }
    }

    /** A maze's seed: the CRC-32 of the match's id, never 0, which means no maze (D-48). */
    static long mazeSeedOf(String matchUid) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(matchUid.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return crc.getValue() == 0 ? 1 : crc.getValue();
    }

    public void start(String name) {
        thread = new Thread(this, name);
        thread.setDaemon(false);
        thread.start();
    }

    /**
     * Asks the room to finish its current tick, publish what every connected player has
     * earned, and stop. Returns at once; {@link #awaitStopped} waits for it.
     *
     * Wakes the thread rather than interrupting it: it parks between ticks, and waking it is
     * all that is needed. The publishing it does on the way out is the point of stopping
     * cleanly, and it should not have to run with an interrupt pending.
     */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** @return true if the room thread has finished, including its final publishing. */
    public boolean awaitStopped(long millis) throws InterruptedException {
        Thread t = thread;
        if (t == null) {
            return true;
        }
        t.join(millis);
        return !t.isAlive();
    }

    public boolean offerJoin(Connection c) {
        queued.add(c);
        if (!joins.offer(c)) {
            queued.remove(c);
            return false;
        }
        if (failed && queued.remove(c)) {
            // Abandoned between the reservation and this offer, after the watchdog had looked:
            // nobody else will ever answer this join.
            c.markClosing();
            Frames.kick(c.channel, Wire.KICK_INTERNAL);
        }
        return true;
    }

    /**
     * A client coming back for a stay this room holds (02 §10). False when the room cannot
     * take it: it has failed, or its queue is full.
     */
    public boolean offerResume(Connection c) {
        if (failed) {
            return false;
        }
        queuedResumes.add(c);
        if (!resumes.offer(c)) {
            queuedResumes.remove(c);
            return false;
        }
        if (failed && queuedResumes.remove(c)) {
            // Given up on between the check and the offer: nobody else will answer it.
            c.markClosing();
            Frames.kick(c.channel, Wire.KICK_INTERNAL);
        }
        return true;
    }

    /** Stays whose player may still come back. Safe from any thread. */
    public int suspendedCount() {
        return waiting.get();
    }

    public long resumedCount() {
        return resumed;
    }

    public long staysExpiredCount() {
        return staysExpired;
    }

    /**
     * Cannot fail: see {@link #leaves}. It used to spin while the queue was full, on a Netty
     * event loop, burning a core for as long as an overrunning room left it full. Sized as it
     * is now, a failure would mean the sizing argument is wrong, which is worth an exception
     * rather than a spin.
     */
    public void offerLeave(Connection c) {
        if (!leaves.offer(c)) {
            throw new IllegalStateException("leave queue full with " + maxPlayers
                    + " players and " + RESUME_QUEUE + " resumes at most: its sizing is wrong");
        }
    }

    /** Safe to call from any thread. */
    public int currentTick() {
        return publishedTick.get();
    }

    /** Safe to call from any thread. */
    public int playerCount() {
        return players.get();
    }

    /**
     * Players plus not-yet-drained joins: what the allocator must reason about.
     *
     * Reads {@code reserved} before {@code players}, and the order is load-bearing. The room
     * thread counts a player before releasing its reservation, so a reader that sees the old
     * reservation sees at least the truth, and one that sees the released reservation also
     * sees the player that replaced it. Read the other way round, the old player count and
     * the new reservation count could be summed: one too few.
     */
    public int committedCount() {
        int r = reserved.get();
        return r + players.get();
    }

    /**
     * Claims a slot for a joining player. Callers that reserve must follow with
     * {@link #offerJoin}, whose drain releases the reservation.
     */
    public boolean tryReserve() {
        if (!accepting()) {
            return false;
        }
        if (reserved.incrementAndGet() + players.get() > maxPlayers) {
            reserved.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Claims a place in a made match's room: only that match's tickets can name it, so there is
     * no capacity to reason about here, and admission counts the players really in the room.
     *
     * Not {@link #tryReserve}: a room counts a player it admits before it releases the place
     * that player held (T-4), so for a moment one player is counted twice. In a room of 150
     * that is noise; in a room of two it filled the room, and the second player of a duel,
     * arriving a millisecond after the first, was refused {@code Kick(2)}: found live, by two
     * headless players joining together.
     */
    public boolean reserveForMatch() {
        if (!accepting()) {
            return false;
        }
        reserved.incrementAndGet();
        return true;
    }

    /**
     * Whether the room takes new players: not failed, and ticking. A room that has not ticked
     * for as long as the watchdog takes to call it stuck is not offered either; it is abandoned
     * at 10 s, and a player sent there meanwhile waited for nothing.
     */
    boolean accepting() {
        return !failed && !finished
                && System.nanoTime() - lastTickEndNanos <= RoomRegistry.STALL_WARN_NANOS;
    }

    /** A made match that is over: closed, published, and not a room to place anyone in. */
    public boolean isFinished() {
        return finished;
    }

    /** The match this room was made for, or null for a room that is not one. */
    public String matchUid() {
        return rules.matchUid();
    }

    /** Releases a reservation whose join never reached the queue. */
    public void releaseReservation() {
        reserved.decrementAndGet();
    }

    public long overruns() {
        return overruns;
    }

    public long ticks() {
        return ticks;
    }

    public PhaseTimer simTimer() {
        return simTimer;
    }

    public PhaseTimer netTimer() {
        return netTimer;
    }

    public PhaseTimer tickTimer() {
        return tickTimer;
    }

    @Override
    public void run() {
        log.info("room thread started");
        long deadline = System.nanoTime();

        while (running) {
            long tickStart = System.nanoTime();
            iterations++;
            try {
                Runnable hook = tickHook;
                if (hook != null) {
                    hook.run();
                }
                tick();
            } catch (RuntimeException e) {
                // One bad tick is logged and survived: ending the room would cost everyone
                // in it their connection for one bug. Several close together mean the state
                // is no longer trustworthy, and the room is closed properly rather than left
                // to limp — or, as before this, to die silently with its players inside.
                log.error("tick {} failed", room.tick(), e);
                if (failedTooOften(iterations)) {
                    fail();
                }
            } catch (Error e) {
                log.error("tick {} failed with an error; closing the room", room.tick(), e);
                fail();
            }
            long tickNanos = System.nanoTime() - tickStart;
            tickTimer.recordTotal(tickNanos);
            lastTickEndNanos = System.nanoTime();
            // The histogram is this thread's; only the number it yields is shared, every ten
            // seconds. The last ten seconds is also the p99 worth alerting on, not one since
            // start-up that a morning of quiet has diluted.
            intervalTicks.recordValue(tickNanos);
            if (iterations % METRICS_EVERY_TICKS == 0) {
                tickP99Millis = intervalTicks.getValueAtPercentile(99.0) / 1e6;
                intervalTicks.reset();
            }

            // The next deadline comes from the previous one, not from "now", so a slow
            // tick does not push every later tick late as well.
            deadline += TICK_NANOS;
            long sleep = deadline - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else if (sleep < -TICK_NANOS * MAX_LAG_TICKS) {
                overruns++;
                deadline = System.nanoTime();
            }
        }
        try {
            finishOnShutdown();
        } catch (RuntimeException e) {
            log.error("could not publish the room's results on the way out", e);
        }
        // After a failure or a plain stop alike: the results are published, and everyone is
        // told to go back through the lobby. A stop used to leave the sockets to be closed
        // bare by the server's shutdown, which a client cannot tell from the network failing.
        sendEveryoneBack();
        log.info("room thread stopped after {} ticks, {} overruns{}", ticks, overruns,
                failed ? ", after failing" : "");
    }

    private void tick() {
        drainRemovals();
        drainLeaves();
        drainResumes();
        drainJoins();
        applyInputs();

        applyUpgrades();
        applySandbox();
        // The simulation's total is the step alone, as the benchmark's is (NFR-1a); the whole
        // tick, snapshots and all, is tickTimer's (NFR-1b). O-3: it was the whole tick.
        long stepStart = System.nanoTime();
        room.step(simTimer);
        simTimer.recordTotal(System.nanoTime() - stepStart);
        ticks++;
        publishedTick.set(room.tick());
        applyLifecycleChanges();
        tally.apply(room.kills());
        if (tagMatch != null) {
            KillLog kills = room.kills();
            for (int i = 0; i < kills.size(); i++) {
                tagMatch.onKill(kills.killerTag(i), kills.victimTag(i), kills.victimKind(i));
            }
        }
        handleDeaths();
        relayKills();
        tendSuspended();
        room.kills().clear();
        applyRespawns();
        publishStats();
        relayPhrases();
        if (rules.isOpen()) {
            if (room.tick() % CHECKPOINT_INTERVAL_TICKS == 0) {
                checkpointLongMatches();
            }
        } else if (rules.isMade()) {
            tendMadeMatch();
        } else if (room.tick() >= matchEndsAtTick) {
            endMatch();
        }

        // Every tick: each client is sent to at its own rate (02 §8).
        netTimer.start(0);
        sendSnapshots();
        netTimer.stop(0);
    }

    /** Records a failure at loop iteration {@code at}; true if it makes three within the window. */
    private boolean failedTooOften(long at) {
        failureIterations[failures % FAILED_TICKS_TO_CLOSE] = at;
        failures++;
        // The oldest of the last three is the slot the next failure will overwrite.
        return failures >= FAILED_TICKS_TO_CLOSE
                && at - failureIterations[failures % FAILED_TICKS_TO_CLOSE] < FAILURE_WINDOW_TICKS;
    }

    /** Stops the room at the end of this tick, and marks it so the registry drops it. */
    private void fail() {
        failed = true;
        running = false;
    }

    /**
     * When the room stops, after a failure or not: everyone still here, and every join still
     * queued, is sent the one kick reason that tells a client to come back through the lobby
     * with backoff - the backoff is what spreads a restarting arena's players over time.
     * Their results have already been published by {@link #finishOnShutdown}. Nobody is left
     * waiting on a room that will never send another frame.
     */
    private void sendEveryoneBack() {
        for (int i = 0; i < active.size(); i++) {
            Frames.kick(active.get(i).channel, stopReason);
        }
        active.clear();
        members.clear();
        clearGauges();
        Connection c;
        while ((c = joins.poll()) != null) {
            reserved.decrementAndGet();
            if (queued.remove(c)) {
                Frames.kick(c.channel, Wire.KICK_INTERNAL);
            }
        }
        while ((c = resumes.poll()) != null) {
            if (queuedResumes.remove(c)) {
                Frames.kick(c.channel, Wire.KICK_INTERNAL);
            }
        }
        forgetStays();
    }

    /** Tests only, and only from {@link #tickHook}: it runs on the room thread, which owns this list. */
    List<Connection> connections() {
        return active;
    }

    /** Tick time p99 over the last ten seconds, in milliseconds. Safe from any thread. */
    public double tickP99Millis() {
        return tickP99Millis;
    }

    public long snapshotsSent() {
        return snapshotsSent;
    }

    public long snapshotsSkipped() {
        return snapshotsSkipped;
    }

    public long snapshotsHeld() {
        return snapshotsHeld;
    }

    /** Snapshot bytes sent at {@code profile}, payload only. Safe from any thread. */
    public long bytesSentAt(TrafficProfile profile) {
        return bytesAt.get(profile.ordinal());
    }

    /** Clients at {@code profile} as of the last tick. Safe from any thread. */
    public int clientsAt(TrafficProfile profile) {
        return clientsAt.get(profile.ordinal());
    }

    public long profileStepsDown() {
        return stepsDown;
    }

    public long profileStepsUp() {
        return stepsUp;
    }

    /** Whether the watchdog should be watching: running, and not already given up on. */
    boolean isTicking() {
        return running && !failed;
    }

    long lastTickEndNanos() {
        return lastTickEndNanos;
    }

    String name() {
        Thread t = thread;
        return t == null ? "room" : t.getName();
    }

    /** What an operator sees of this room. */
    RoomView view() {
        return new RoomView(name(), playerCount(), matchUid(), rules.mode().key,
                rules.isMade() ? stage.name().toLowerCase(java.util.Locale.ROOT) : "open");
    }

    /** An operator's close (04 §10): everyone kicked with {@code reason}, what they are owed published. */
    void close(int reason) {
        stopReason = reason;
        stop();
    }

    /** An operator takes a player out, if they are here: kicked on the next tick (04 §10). */
    void remove(long playerId) {
        if (!removals.offer(playerId)) {
            log.warn("too many removals queued; player {} not taken out", playerId);
        }
    }

    /** Kicks each player an operator took out; the closed socket's leave publishes their stay. */
    private void drainRemovals() {
        Long playerId;
        while ((playerId = removals.poll()) != null) {
            for (int i = 0; i < active.size(); i++) {
                Connection c = active.get(i);
                if (c.identity().playerId() == playerId) {
                    log.info("player {} taken out by an operator", playerId);
                    c.endStay();                // left, not lost: no resume, and the stay published
                    Frames.kick(c.channel, Wire.KICK_REMOVED);
                }
            }
            endWaitingStayOf(playerId);         // one whose socket had dropped, waiting to resume (T-35)
        }
    }

    StackTraceElement[] stack() {
        Thread t = thread;
        return t == null ? new StackTraceElement[0] : t.getStackTrace();
    }

    /**
     * Gives up on a room whose thread has stopped ticking. Called by the watchdog, never by the
     * room thread, which is stuck.
     *
     * Its players are sent back to the lobby with reason 5 - those admitted and those still
     * queued, who used to wait with a spent ticket for a Welcome that never came - new joins
     * are refused, and the registry replaces it. What it cannot do is publish their results: the tally belongs to
     * the stuck thread and cannot be read safely from here, so they lose what they earned
     * since their last checkpoint. If the thread ever returns it finds itself stopped, and
     * publishes then; the results are idempotent, so arriving late does no harm.
     */
    void abandon() {
        failed = true;
        running = false;
        for (Connection c : members) {
            c.markClosing();
            Frames.kick(c.channel, Wire.KICK_INTERNAL);
        }
        for (Connection c : queued) {
            if (queued.remove(c)) {
                c.markClosing();
                Frames.kick(c.channel, Wire.KICK_INTERNAL);
            }
        }
        for (Connection c : queuedResumes) {
            if (queuedResumes.remove(c)) {
                c.markClosing();
                Frames.kick(c.channel, Wire.KICK_INTERNAL);
            }
        }
        forgetStays();
        clearGauges();
    }

    /**
     * Nobody is here any more. The profile counts were the last tick's, and a room that stops
     * ticking stops refreshing them: a failed room showed its clients in the gauge until the
     * registry next dropped something.
     */
    private void clearGauges() {
        players.set(0);
        waiting.set(0);
        for (int p = 0; p < clientsAt.length(); p++) {
            clientsAt.set(p, 0);
        }
    }

    /**
     * Takes this room's resume secrets out of the arena's map. A room that fails, hangs or
     * stops holds no stay a client can come back to, and an entry left behind pointed a resume
     * at it - Kick(5), "try again", for a stay that no longer existed - and kept the room and
     * its world referenced for the life of the process. Safe from any thread.
     */
    private void forgetStays() {
        stays.values().removeIf(r -> r == this);
    }

    /** True once the room has closed itself after failing; the registry stops using it. */
    public boolean hasFailed() {
        return failed;
    }

    /**
     * Publishes what every player still here has earned, on the way out.
     *
     * This is what makes a deploy safe for the people playing through it. Without it, a
     * planned restart dropped everything each connected player had done since their last
     * checkpoint — up to ten minutes each, for everyone online, on every deploy. Measured:
     * two players connected, the server closed, zero results published.
     *
     * Leavers first, so someone who left in the final tick is published once, as a leaver,
     * and not a second time as a player who was still here.
     */
    private void finishOnShutdown() {
        drainLeaves();
        long now = System.currentTimeMillis();
        if (rules.isOpen()) {
            for (int i = 0; i < active.size(); i++) {
                publish(tally.finishOpenMatch(active.get(i).playerTag(), now));
            }
            // A stay waiting for its player is as much a stay as one being played.
            for (Suspended s : suspended.values()) {
                publish(tally.finishOpenMatch(s.tag, now));
            }
        } else if (!rules.isMade() || stage == Stage.PLAYING) {
            // A timed match cut short is still the record of what happened in it. A made one
            // still waiting has nothing to record, and one that is over has recorded it.
            // Said so in the result, so it is paid and not rated where it stood (D-29).
            MatchOutcome outcome = tally.finish(now);
            if (outcome != null) {
                log.info("match {} cut short by shutdown with {} players",
                        outcome.matchUid(), outcome.players().size());
                publish(outcome.cut());
            }
        }
    }

    // ---- the match ------------------------------------------------------------------------

    private void beginMatch(String matchUid) {
        // A stay waiting across a timed match's end was counted in its result, and ends with it.
        for (Suspended s : suspended.values()) {
            stays.remove(s.secret);
        }
        suspended.clear();
        updateCounts();
        tally.startMatch(matchUid, System.currentTimeMillis(), rules.mode());
        matchTags.clear();
        matchEndsAtTick = room.tick() + rules.matchDurationTicks();
    }

    /**
     * A made match's stages (04 §4): waiting for its roster, then playing to a win or the
     * clock, then over.
     */
    private void tendMadeMatch() {
        MatchMode mode = rules.mode();
        if (stage == Stage.WAITING) {
            if (arrived.size() >= mode.roster || room.tick() >= joinWindowEndsAtTick) {
                startMadeMatch();
            }
        } else if (stage == Stage.PLAYING) {
            if (waves != null) {
                if (waves.tick()) {
                    returnTheDead();
                }
                while (wavesPaid < waves.cleared()) {
                    wavesPaid++;
                    payTheWave();
                }
            }
            if (domination != null) {
                domination.tick();
            }
            if (sandbox != null) {
                sandbox.tick();
                emptyTicks = active.isEmpty() && suspended.isEmpty() ? emptyTicks + 1 : 0;
            }
            if (room.tick() >= matchEndsAtTick
                    || (sandbox != null && emptyTicks >= emptyEndTicks)
                    || (mode.winKills > 0 && tally.mostKills() >= mode.winKills)
                    || (waves != null && (waves.over() || wiped()))
                    || (domination != null && domination.won())
                    || (tagMatch != null && tagMatch.oneTeam(playingTags()))) {
                if (waves != null) {
                    log.info("co-op {}: {} waves cleared", rules.matchUid(), waves.cleared());
                }
                if (domination != null) {
                    log.info("domination {}: held {}", rules.matchUid(), domination.held());
                }
                // Domination's teams are placed by what they hold, tag's by their heads at the end
                // (each player by the team they started on), every other mode's by kills.
                finishMadeMatch(tally.finish(System.currentTimeMillis(), domination != null ? domination.held()
                        : tagMatch != null ? tagMatch.heads(playingTags()) : null));
            }
        }
    }

    /** Tag: the players still playing, connected and not leaving (01 §8.8). */
    private java.util.List<Long> playingTags() {
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (!c.isClosing() && c.playerTag() != 0) {
                out.add(c.playerTag());
            }
        }
        return out;
    }

    /** Co-op: none of the team alive at once, which ends it (01 §8.5). */
    private boolean wiped() {
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (!c.isClosing() && c.entityId() >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Co-op: a wave cleared adds to the score of every player of the team still in the match,
     * connected or with a stay waiting (01 §8.5, Q-37); one who left has gone from both.
     */
    private void payTheWave() {
        for (int i = 0; i < active.size(); i++) {
            tally.addScore(active.get(i).playerTag(), Waves.CLEARED_SCORE);
        }
        for (Suspended s : suspended.values()) {
            tally.addScore(s.tag, Waves.CLEARED_SCORE);
        }
    }

    /** Co-op: a wave's start puts every player then dead back into the world, at their team's side. */
    private void returnTheDead() {
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (c.entityId() < 0 && !c.isClosing()) {
                respawn(c);
            }
        }
    }

    /**
     * The whole roster is here, or the join window is over: the world resets, as between timed
     * matches, and everyone starts at level 1 with the clock. Fewer than two sides is a
     * walkover: whoever came wins at once, and moves no rating (04 §4). A side is a player in a
     * duel and a team in a team mode, so two of one team are still one side (01 §8.4). Co-op
     * has one side, the arena's tanks the other, and plays with whoever came (01 §8.5).
     * Nobody at all is no result.
     */
    private void startMadeMatch() {
        if (rules.mode() == MatchMode.TAG) {
            tagMatch = new Tag();                      // before the spawns, which take its teams
        }
        room.resetForNewMatch(shapesPerMatch);
        for (int i = 0; i < active.size(); i++) {
            active.get(i).setEntityId(-1);
        }
        beginMatch(rules.matchUid());
        int playing = 0;
        java.util.Set<Integer> sides = new java.util.HashSet<>();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (!c.isClosing() && spawnFor(c, false)) {
                playing++;
                sides.add(rules.mode().teams() ? c.identity().team() : -1 - i);
            }
        }
        stage = Stage.PLAYING;
        log.info("match {} ({}) starting with {} of {}", rules.matchUid(), rules.mode().key,
                playing, rules.mode().roster);
        if (rules.mode() == MatchMode.COOP) {
            waves = new Waves(room, Waves.DELAY_TICKS);
        }
        if (rules.mode() == MatchMode.DOMINATION) {
            domination = new Domination(room);
        }
        if (rules.mode() == MatchMode.SANDBOX) {
            sandbox = new Sandbox(room);
        }
        if (sides.size() < Math.min(2, rules.mode().roster / rules.mode().teamSize)) {
            finishMadeMatch(tally.finish(System.currentTimeMillis()));
        }
    }

    /**
     * Publishes the result, sends every player back to the lobby with {@code Kick(6)}, and
     * closes the room: the thread stops after this tick, and the registry drops the room.
     */
    private void finishMadeMatch(MatchOutcome outcome) {
        if (outcome != null) {
            log.info("match {} over: {}", outcome.matchUid(), outcome.players().stream()
                    .map(p -> p.playerId() + " placed " + p.placement() + " with " + p.kills() + " kills")
                    .toList());
            publish(outcome);
        }
        stage = Stage.OVER;
        for (int i = 0; i < active.size(); i++) {
            Frames.kick(active.get(i).channel, Wire.KICK_MATCH_OVER);
            hold(active.get(i).identity().playerId(), false);
        }
        for (Suspended s : suspended.values()) {
            hold(s.identity.playerId(), false);
        }
        active.clear();
        members.clear();
        forgetStays();
        updateCounts();
        finished = true;
        running = false;
    }

    /**
     * Checkpoints open matches that have run long.
     *
     * Without it a player who stays for hours is paid for none of it until they leave, and
     * loses the lot if this process dies first. The match is closed and reopened; the player
     * notices nothing, because nothing about the world changes.
     */
    private void checkpointLongMatches() {
        int cutoff = room.tick() - rules.checkpointTicks();
        long now = System.currentTimeMillis();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (c.matchStartTick() > cutoff) {
                continue;
            }
            publish(tally.checkpointOpenMatch(c.playerTag(), now));
            c.setMatchStartTick(room.tick());
        }
    }

    /**
     * Publishing must never take the room down with it: the players are still here. A sandbox
     * publishes nothing, whichever way it ends (01 §8.10).
     */
    private void publish(MatchOutcome outcome) {
        if (outcome == null || rules.mode() == MatchMode.SANDBOX) {
            return;
        }
        try {
            onMatchEnd.accept(outcome);
        } catch (RuntimeException e) {
            log.error("could not publish {}: {}", outcome.matchUid(), e.toString(), e);
        }
    }

    /**
     * Ends the match, publishes the result and starts the next one.
     *
     * Order matters. The result is taken before the world is reset, or the players are
     * already gone from it; and every connection's entity id is cleared immediately after,
     * because the reset recycles those slots and an id kept across it points at whatever
     * lands in that slot next — which, a tick later, is a shape.
     */
    private void endMatch() {
        MatchOutcome outcome = tally.finish(System.currentTimeMillis());
        if (outcome != null) {
            log.info("match {} ended with {} players", outcome.matchUid(), outcome.players().size());
            publish(outcome);
        }

        room.resetForNewMatch(shapesPerMatch);
        for (int i = 0; i < active.size(); i++) {
            active.get(i).setEntityId(-1);
        }
        beginMatch(Ulid.generate());
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (!c.isClosing()) {
                spawnFor(c, false);
            }
        }
    }

    /**
     * Puts a connection into the current match: a fresh tank and a tally entry, continued if
     * this player already has one in a timed match.
     *
     * {@code joining}: a new connection gets a new view. One carried across a match boundary
     * keeps its view, pointed at the new tank, as after a death: a new view restarted the
     * client's tick and camera from zero (P-6), for every player at every match end.
     */
    private boolean spawnFor(Connection c, boolean joining) {
        Ticket who = c.identity();
        long tag = rules.isOpen() ? nextPlayerTag++
                : matchTags.computeIfAbsent(who.playerId(), id -> nextPlayerTag++);
        if (tagMatch != null) {
            tagMatch.join(tag, who.team());            // a first spawn: a comeback is respawn's
        }
        Entity tank = room.spawnTank((byte) who.team(), tag);
        if (tank == null) {
            return false;
        }
        tank.playerControlled = true;
        tank.name = nameOf(who);
        wear(tank, who);
        c.setPlayerTag(tag);
        c.setEntityId(tank.id);
        if (joining) {
            c.setTraffic(new TrafficControl(c.ceiling()));
            c.setView(newView(tank.id, c.traffic().profile()));
        } else {
            c.view().respawnAs(tank.id);
            c.forgetStats();                 // the new tank is level 1 with nothing spent
        }
        c.setMatchStartTick(room.tick());
        tally.playerJoined(tag, who.playerId(), who.displayName(), who.team(),
                System.currentTimeMillis());
        return true;
    }

    // ---- app lifecycle ----------------------------------------------------------------------

    /**
     * Parks a backgrounded tank, and lets a returning one carry on.
     *
     * Coming back keeps the view. The client missed frames while it was away, but it still
     * holds the world as of the last frame it got, and the view records exactly that: the
     * next frame's removes, creates and updates bring it up to date. A new view here used to
     * restart its tick and camera from zero (P-6).
     */
    private void applyLifecycleChanges() {
        World world = room.world();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            boolean now = c.isBackgrounded();
            if (now == c.wasBackgrounded()) {
                continue;
            }
            c.setWasBackgrounded(now);
            int id = c.entityId();
            if (now && id >= 0) {
                // Parked, not removed: the tank stays in the world and stays killable, so
                // backgrounding is not a way to survive a fight.
                Entity e = world.entities[id];
                e.moveX = 0f;
                e.moveY = 0f;
                e.wantsFire = false;
            }
            // Back from the background: nothing to do. The view is kept, as across a death;
            // a new one restarted the client's tick and camera from zero (P-6).
        }
    }

    // ---- death and respawn ----------------------------------------------------------------

    /**
     * Turns a dead tank into a death the client is told about.
     *
     * Reads this tick's kills before they are cleared, only to find who to name. The
     * authority on whether a player is dead is the entity, not the log: anything that kills
     * a tank without being recorded would otherwise leave a player alive on the server and
     * frozen on their own screen.
     */
    private void handleDeaths() {
        World world = room.world();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            int id = c.entityId();
            if (id < 0 || world.entities[id].alive) {
                continue;
            }
            c.events().death(tally.scoreOf(c.playerTag()), killerNameFor(c.playerTag()));
            c.lastLifeXp = world.tankStats[id].xp;      // read before the slot is handed on
            c.setEntityId(-1);              // dead: no input applied, no snapshots sent
        }
    }

    /** The name a player's tank carries in its create (02 §4, D-52). */
    private static byte[] nameOf(Ticket who) {
        return who.displayName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The kill feed (01 §9, Q-34): each tank killed this tick, to its killer; in a made match, to
     * every player in it. Shapes broken are not in it.
     */
    private void relayKills() {
        KillLog kills = room.kills();
        for (int i = 0; i < kills.size(); i++) {
            if (kills.victimKind(i) != com.backend.sim.Entity.KIND_TANK) {
                continue;
            }
            long killer = kills.killerTag(i);
            String killerName = tally.displayNameOf(killer);
            String victimName = tally.displayNameOf(kills.victimTag(i));
            for (int j = 0; j < active.size(); j++) {
                Connection c = active.get(j);
                if (rules.isMade() || c.playerTag() == killer) {        // no player's tag is 0
                    c.events().kill(killerName, victimName);
                }
            }
        }
    }

    /** @return the killer's display name, or null when nobody present gets the credit. */
    private String killerNameFor(long victimTag) {
        KillLog kills = room.kills();
        for (int i = 0; i < kills.size(); i++) {
            if (kills.victimTag(i) == victimTag) {
                return tally.displayNameOf(kills.killerTag(i));
            }
        }
        return null;
    }

    /**
     * Respawns players who asked.
     *
     * A request while alive is dropped rather than honoured: respawning on demand mid-fight
     * would be a way out of every losing fight. The match and the player tag carry over — a
     * death ends a tank, not a match.
     */
    private void applyRespawns() {
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            // In co-op the dead come back at the next wave, and not by asking (01 §8.5).
            if (!c.takeRespawnRequest() || c.entityId() >= 0 || c.isClosing() || waves != null) {
                continue;
            }
            respawn(c);
        }
    }

    // ---- joins and leaves ---------------------------------------------------------------

    private void drainJoins() {
        Connection c;
        while ((c = joins.poll()) != null) {
            if (!queued.remove(c)) {
                reserved.decrementAndGet();
                continue;                       // the watchdog sent this one back already
            }
            admit(c);
            // Released only after admit has counted the player it held a place for (T-4). The
            // other way round, players + reserved dipped by one for a few instructions, and an
            // allocator reading then could reserve a place that did not exist; that join was
            // later kicked ROOM_FULL with its single-use ticket already spent.
            reserved.decrementAndGet();
        }
    }

    /** Turns a reserved join into a player, or refuses it. */
    private void admit(Connection c) {
        if (c.isClosing()) {
            return;
        }
        Ticket who = c.identity();
        if (who == null) {
            // Only a claimed ticket reaches this queue, so a null identity is a bug in
            // the join path rather than a client doing something unexpected.
            log.error("join reached the room with no identity; refusing");
            c.channel.close();
            return;
        }
        // Back through the lobby with a new ticket, having lost the secret: the stay this
        // player already has here ends first - waiting for them, or on a connection the server
        // has not yet noticed is gone - or they would have two tanks in one room.
        endLiveStayOf(who.playerId());
        endWaitingStayOf(who.playerId());
        if (active.size() + suspended.size() >= maxPlayers) {
            Frames.kick(c.channel, Wire.KICK_ROOM_FULL);   // the registry should have prevented this
            return;
        }
        if (!spawnFor(c, true)) {
            Frames.kick(c.channel, Wire.KICK_ROOM_FULL);   // pool exhausted; refuse rather than grow
            return;
        }
        active.add(c);
        members.add(c);
        if (rules.isMade() && arrived.add(who.playerId())) {
            markArrived();
        }
        hold(who.playerId(), true);
        updateCounts();
        sendWelcome(c, room.world().entities[c.entityId()]);
        log.info("player {} joined as entity {} ({} in room)",
                who.playerId(), c.entityId(), active.size());
    }

    private ClientView newView(int entityId, TrafficProfile profile) {
        return new ClientView(entityId, room.world().capacity(), VIEW_SIZE, VIEW_SIZE, profile.budget);
    }

    private void drainLeaves() {
        Connection c;
        while ((c = leaves.poll()) != null) {
            members.remove(c);
            if (c.superseded()) {
                continue;                   // its stay went on with the connection that resumed it
            }
            boolean present = active.remove(c);
            if (present && !c.endsStay() && running && c.resumeSecret() != null) {
                suspend(c);                 // lost, not left: the player may come back (02 §10)
                continue;
            }
            if (present && c.entityId() >= 0) {
                Entity e = room.world().entities[c.entityId()];
                if (e.alive && e.playerControlled) {
                    room.world().kill(e);
                }
            }
            finishStay(c.playerTag());
            if (present) {
                hold(c.identity().playerId(), false);   // gone from the room; those it ended give theirs there
            }
            if (c.resumeSecret() != null) {
                stays.remove(c.resumeSecret());
            }
            updateCounts();
            c.setEntityId(-1);
        }
    }

    private void finishStay(long tag) {
        long now = System.currentTimeMillis();
        if (rules.isOpen()) {
            // Leaving is what ends an open match, so this is the result.
            publish(tally.finishOpenMatch(tag, now));
        } else {
            // The tally keeps them: leaving early does not undo what they did, and the
            // players they killed keep the credit until the match ends.
            tally.playerLeft(tag, now);
        }
    }

    /** {@code players} counts the stays waiting for their player: their places are kept. */
    private void updateCounts() {
        players.set(active.size() + suspended.size());
        waiting.set(suspended.size());
    }

    // ---- lost connections and resumes (02 §10) --------------------------------------------

    /** Parks the tank where it stands, and keeps the stay for the player to resume. */
    private void suspend(Connection c) {
        int id = c.entityId();
        if (id >= 0) {
            Entity e = room.world().entities[id];
            e.moveX = 0f;                   // not driven by the last input it had
            e.moveY = 0f;
            e.wantsFire = false;
        }
        Suspended s = new Suspended(c.resumeSecret(), c.identity(), c.playerTag(),
                c.matchStartTick(), room.tick(), id);
        suspended.put(s.secret, s);
        c.setEntityId(-1);
        tally.playerLeft(s.tag, System.currentTimeMillis());   // the time away is not play
        updateCounts();
        log.info("player {} lost their connection; their stay waits for them",
                s.identity.playerId());
    }

    /**
     * Each tick, for every stay waiting: notes a death, takes the tank out once its grace is
     * over, keeping what it had grown into, and ends the stay once its keep is.
     */
    private void tendSuspended() {
        if (suspended.isEmpty()) {
            return;
        }
        World world = room.world();
        int tick = room.tick();
        var it = suspended.values().iterator();
        while (it.hasNext()) {
            Suspended s = it.next();
            if (s.entityId >= 0 && !world.entities[s.entityId].alive) {
                s.died = true;              // killed while away: the player is told on return
                s.deathScore = tally.scoreOf(s.tag);
                s.killer = killerNameFor(s.tag);
                s.entityId = -1;
            }
            int away = tick - s.since;
            if (s.entityId >= 0 && away >= rules.resumeGraceTicks()) {
                s.saved = new TankStats();
                s.saved.copyFrom(world.tankStats[s.entityId]);
                world.kill(world.entities[s.entityId]);   // taken out, not killed: nothing logged
                s.entityId = -1;
            }
            if (away >= rules.resumeKeepTicks()) {
                it.remove();
                stays.remove(s.secret);
                finishStay(s.tag);
                hold(s.identity.playerId(), false);
                staysExpired++;
                log.info("player {} did not come back; their stay has ended", s.identity.playerId());
            }
        }
        updateCounts();
    }

    /**
     * Ends the stay of a connection this player still has here, as if they had left, and
     * closes it. Marked superseded, so its leave, when it comes, touches nothing: in a timed
     * match the new stay continues the same tally entry, which that leave would stamp as left.
     */
    private void endLiveStayOf(long playerId) {
        for (int i = active.size() - 1; i >= 0; i--) {
            Connection old = active.get(i);
            if (old.identity() == null || old.identity().playerId() != playerId) {
                continue;
            }
            active.remove(i);
            members.remove(old);
            old.supersede();
            if (old.entityId() >= 0) {
                Entity e = room.world().entities[old.entityId()];
                if (e.alive && e.playerControlled) {
                    room.world().kill(e);
                }
            }
            finishStay(old.playerTag());
            if (old.resumeSecret() != null) {
                stays.remove(old.resumeSecret());
            }
            old.setEntityId(-1);
            old.channel.close();
        }
        updateCounts();
    }

    /** Ends the stay waiting for {@code playerId}, if there is one, as if they had left. */
    private void endWaitingStayOf(long playerId) {
        var it = suspended.values().iterator();
        while (it.hasNext()) {
            Suspended s = it.next();
            if (s.identity.playerId() != playerId) {
                continue;
            }
            it.remove();
            stays.remove(s.secret);
            if (s.entityId >= 0 && room.world().entities[s.entityId].alive) {
                room.world().kill(room.world().entities[s.entityId]);
            }
            finishStay(s.tag);
        }
        updateCounts();
    }

    private void drainResumes() {
        Connection c;
        while ((c = resumes.poll()) != null) {
            if (queuedResumes.remove(c)) {
                resume(c);
            }
        }
    }

    /**
     * Puts a returning client back into its stay: the same tank if it is still in the world,
     * the one it had grown into if it was taken out, a new one if it died, told how. A resume
     * may arrive while the server still holds the old connection - the phone knows its wifi
     * has gone long before the server's idle limit does - and then takes it over.
     *
     * A new view, so the first frame creates everything the client is to see; a new secret,
     * so the one just used cannot be used again.
     */
    private void resume(Connection c) {
        if (c.isClosing()) {
            return;
        }
        String secret = c.resuming();
        Suspended s = suspended.remove(secret);
        if (s == null) {
            Connection live = activeWith(secret);
            if (live == null) {
                Frames.kick(c.channel, Wire.KICK_BAD_TICKET);   // ended, or never here
                return;
            }
            active.remove(live);
            members.remove(live);
            live.supersede();
            live.channel.close();
            s = new Suspended(secret, live.identity(), live.playerTag(), live.matchStartTick(),
                    room.tick(), live.entityId());
            live.setEntityId(-1);
        }
        stays.remove(secret);
        Entity tank = s.entityId >= 0
                ? room.world().entities[s.entityId]
                : room.spawnTank((byte) teamNow(s.identity.team(), s.tag), s.tag);
        if (tank == null) {
            finishStay(s.tag);              // the pool is exhausted; refuse rather than grow
            updateCounts();
            Frames.kick(c.channel, Wire.KICK_ROOM_FULL);
            return;
        }
        if (s.entityId < 0) {
            tank.playerControlled = true;
            tank.name = nameOf(s.identity);
            if (s.saved != null) {
                room.world().tankStats[tank.id].copyFrom(s.saved);
            } else {
                wear(tank, s.identity);
            }
        }
        c.identify(s.identity);
        c.setPlayerTag(s.tag);
        tally.playerJoined(s.tag, s.identity.playerId(), s.identity.displayName(),
                s.identity.team(), System.currentTimeMillis());
        c.setMatchStartTick(s.matchStartTick);
        c.setEntityId(tank.id);
        c.setTraffic(new TrafficControl(c.ceiling()));
        c.setView(newView(tank.id, c.traffic().profile()));
        if (s.died) {
            c.events().death(s.deathScore, s.killer);
        }
        active.add(c);
        members.add(c);
        updateCounts();
        sendWelcome(c, tank);
        resumed++;
        log.info("player {} resumed their stay as entity {}", s.identity.playerId(), tank.id);
    }

    private Connection activeWith(String secret) {
        for (int i = 0; i < active.size(); i++) {
            if (secret.equals(active.get(i).resumeSecret())) {
                return active.get(i);
            }
        }
        return null;
    }

    // ---- input ----------------------------------------------------------------------------

    private void applyInputs() {
        World world = room.world();
        long now = System.nanoTime();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            int id = c.entityId();
            if (id < 0) {
                continue;
            }
            Entity e = world.entities[id];
            if (!e.alive) {
                continue;
            }
            if (c.isBackgrounded()) {
                e.moveX = 0f;
                e.moveY = 0f;
                e.wantsFire = false;
                e.attacking = false;
                continue;                  // a parked tank ignores whatever arrives late
            }
            if (now - c.inputAt() > STALE_INPUT_NANOS) {
                // A client that plays sends input ten times a second (02 §9); a second of
                // none is a link that has stalled, and nobody is driving. The last input
                // used to go on driving until the socket was given up on, up to 30 s of a
                // tank running and firing on its own. Before any input, the stored one is
                // nothing held, so this changes nothing then.
                e.moveX = 0f;
                e.moveY = 0f;
                e.wantsFire = false;
                e.attacking = false;
                c.takeFire();              // a tap that old is not a tap to act on now
                continue;
            }
            long packed = c.takeInput();
            int move = ClientMessage.inputMove(packed);
            c.view().inputApplied(ClientMessage.inputSeq(packed));

            e.moveX = directionX(move);
            e.moveY = directionY(move);
            e.aimAngle = ClientMessage.aimToRadians(ClientMessage.inputAim(packed));

            int flags = ClientMessage.inputFlags(packed);
            // Held, as the latest input says: what drones attack by (01 §4).
            e.attacking = (flags & (ClientMessage.FLAG_FIRE | ClientMessage.FLAG_AUTOFIRE)) != 0;
            e.zooming = (flags & ClientMessage.FLAG_ZOOM) != 0;
            // The latch catches a tap that happened between two ticks; autofire is level-triggered.
            if (c.takeFire() || (flags & ClientMessage.FLAG_AUTOFIRE) != 0) {
                e.wantsFire = true;
            }
        }
    }

    /**
     * The move bits as a direction's x: -1, 0 or 1, a diagonal scaled so it is no faster than
     * straight. Here and in {@link #directionY} only, as the client's prediction ports it (D-62).
     */
    static float directionX(int move) {
        float x = axis(move, ClientMessage.MOVE_LEFT, ClientMessage.MOVE_RIGHT);
        return x != 0f && axis(move, ClientMessage.MOVE_UP, ClientMessage.MOVE_DOWN) != 0f ? x * DIAGONAL : x;
    }

    static float directionY(int move) {
        float y = axis(move, ClientMessage.MOVE_UP, ClientMessage.MOVE_DOWN);
        return y != 0f && axis(move, ClientMessage.MOVE_LEFT, ClientMessage.MOVE_RIGHT) != 0f ? y * DIAGONAL : y;
    }

    private static float axis(int move, int negative, int positive) {
        float a = 0f;
        if ((move & negative) != 0) {
            a -= 1f;
        }
        if ((move & positive) != 0) {
            a += 1f;
        }
        return a;
    }

    // ---- output ----------------------------------------------------------------------------

    private void sendSnapshots() {
        World world = room.world();
        int tick = room.tick();
        long now = System.nanoTime();
        java.util.Arrays.fill(counting, 0);
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            if (c.isClosing()) {
                continue;
            }
            TrafficControl traffic = c.traffic();
            counting[traffic.profile().ordinal()]++;
            if (!traffic.due(TICK_HZ)) {
                continue;
            }
            if (c.isBackgrounded()) {
                traffic.idle();
                continue;                   // parked: stop spending the player's battery and data
            }
            int id = c.entityId();
            if (id < 0) {
                // Dead and waiting to respawn. The death event still has to reach them, or
                // the client has no idea why the world stopped.
                traffic.idle();
                flushEventsOnly(c);
                continue;
            }
            Channel ch = c.channel;
            int down = traffic.stepsDown();
            int up = traffic.stepsUp();
            try {
                if (!ch.isWritable()) {
                    // Skipping is the designed behaviour: the newest state supersedes whatever
                    // is queued, so adding to the queue would only add latency.
                    traffic.round(now, false);
                    c.countSkipped();
                    snapshotsSkipped++;
                    continue;
                }
                // Clamped to a tick that has actually happened. The value comes straight off
                // the wire, and an ack of Integer.MAX_VALUE would promote every pending handle
                // at once — defeating the rule that a handle is not reused until its removal
                // is confirmed, and then wedging this client's own baseline for good.
                // Self-inflicted and confined to the sender, but free to send, so it is not
                // left to good faith. Read before the round, even one held back: the
                // acknowledgements are how the controller sees the queue drain.
                int ack = Math.min(c.takeAck(), tick);
                c.view().acknowledge(ack);
                if (ack > 0) {
                    traffic.acknowledged(ack, now);
                }
                if (!traffic.round(now, true)) {
                    c.countSkipped();
                    snapshotsHeld++;
                    continue;
                }
                TrafficProfile profile = traffic.profile();
                if (traffic.takeChange()) {
                    c.view().entityBudget = profile.budget;   // entities drop out, or come in
                }
                // What it sees is its class's: a Sniper further (01 §4).
                float seen = VIEW_SIZE * room.content().classes().get(world.tankStats[id].classId).fovMul();
                c.view().viewWidth = seen;
                c.view().viewHeight = seen;
                // The own tank's motion, for the client that predicts it (02 §9, D-62): added here,
                // to a frame that is being sent, so a held or skipped round leaves none behind.
                Entity self = world.entities[id];
                float accel = Room.tankAccel(world.tankStats[id]);
                if (c.view().motionRuleChanged(accel, self.radius)) {
                    c.events().motionRule(accel, self.radius);
                }
                c.events().motion(c.view().inputTicks(), self.vx, self.vy);
                encoder.encode(world, c.view(), tick, writer, c.events());
                deliver(c);
                traffic.sent(tick, now, writer.length());
                bytesAt.addAndGet(profile.ordinal(), writer.length());
                snapshotsSent++;
            } catch (RuntimeException e) {
                // This client's handle table may be half-updated, so nothing sent to it from
                // now on could be trusted. It is disconnected with the retry-with-backoff
                // reason and rejoins with a fresh view; everyone else carries on.
                log.error("snapshot for player {} failed; disconnecting them",
                        c.identity() == null ? -1 : c.identity().playerId(), e);
                c.endStay();
                c.markClosing();
                Frames.kick(ch, Wire.KICK_INTERNAL);
            } finally {
                stepsDown += traffic.stepsDown() - down;
                stepsUp += traffic.stepsUp() - up;
            }
        }
        for (int p = 0; p < counting.length; p++) {
            clientsAt.lazySet(p, counting[p]);
        }
    }

    /**
     * Sends a snapshot carrying nothing but this client's pending events.
     *
     * A dead player has no view to encode, but still has to be told they died and by whom.
     */
    private void flushEventsOnly(Connection c) {
        if (c.events().count() == 0 || !c.channel.isWritable()) {
            return;
        }
        writer.reset();
        writer.u8(Wire.MSG_SNAPSHOT);
        writer.varint(0);                   // tickDelta
        writer.varint(0);                   // inputSeqDelta
        writer.svarint(0);                  // viewOriginDX
        writer.svarint(0);                  // viewOriginDY
        writer.varint(0);                   // removes
        writer.varint(0);                   // creates
        writer.varint(0);                   // updates
        writer.varint(c.events().count());
        writer.bytes(c.events().array(), c.events().length());
        c.events().clear();
        deliver(c);
    }

    /** Writes the frame in {@link #writer} to a client, and counts it, its bytes too (02 §13). */
    private void deliver(Connection c) {
        Frames.write(c.channel, writer.array(), writer.length());
        c.sent(writer.length());
        c.countSent();
    }

    /** What the player wears, from their ticket, on a tank of theirs just spawned (01 §3, D-37), its skin too (D-70). */
    private void wear(Entity tank, Ticket who) {
        room.world().tankStats[tank.id].setBonus(who.bonusPercents());
        room.world().tankStats[tank.id].skin = who.skin();
    }

    /**
     * The team a player is on now: in tag, the one kills have converted them to (01 §8.8); everywhere else, their
     * ticket's. A respawn, a resume and a team's phrases all go by it; a resume by the ticket's put a converted player
     * back on their first team, hurting their own and counted by the other (M-17).
     */
    private int teamNow(int ticketTeam, long tag) {
        return tagMatch == null ? ticketTeam : tagMatch.teamOf(tag);
    }

    /**
     * A death inside a match, not a new match: the same player continues with the same
     * tally, so the tag is kept and only the tank is replaced.
     */
    private void respawn(Connection c) {
        Entity tank = room.spawnTank((byte) teamNow(c.identity().team(), c.playerTag()), c.playerTag());
        if (tank == null) {
            c.channel.close();
            return;
        }
        tank.playerControlled = true;
        tank.name = nameOf(c.identity());
        wear(tank, c.identity());
        if (rules.isOpen()) {
            // The rebate (01 §7, Q-36): a quarter of the last life's experience, up to level 20's.
            int cap = room.content().levels().xpRequired(REBATE_MAX_LEVEL);
            room.grantExperience(tank, Math.min(c.lastLifeXp / REBATE_DIVISOR, cap));
        }
        c.setEntityId(tank.id);
        // The same view, pointed at the new tank. A new view restarted the client's tick and
        // camera from zero, so everything after a respawn was drawn in the wrong place (P-6).
        c.view().respawnAs(tank.id);
        // The new tank is level 1 with nothing spent, and the client is still showing the
        // build that just died. Forgetting means the next publish states the whole thing.
        c.forgetStats();
    }

    /**
     * Spends the skill points and makes the class choices players have asked for.
     *
     * Before the step rather than after, so a point spent this tick is in force for the
     * shot this tick fires. The other way round it would take effect on the next one, which
     * is invisible in a benchmark and infuriating in a fight.
     */
    private void applyUpgrades() {
        World world = room.world();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            int id = c.entityId();
            int classId = c.takeClassRequest();
            if (classId != Connection.NO_CLASS && id >= 0) {
                room.chooseClass(world.entities[id], classId);   // refused silently (01 §4)
            }
            for (int stat = 0; stat < Stat.COUNT; stat++) {
                int asked = c.takeUpgradeRequests(stat);
                if (asked == 0) {
                    continue;
                }
                if (id < 0 || !world.entities[id].alive) {
                    continue;               // dead: the request dies with the tank
                }
                for (int n = 0; n < asked && room.spendPoint(world.entities[id], stat); n++) {
                    // spendPoint refuses once the points run out or the class's cap is reached
                }
            }
        }
    }

    /** A sandbox's powers asked for, before the step as points are (01 §8.10); dropped in any other room. */
    private void applySandbox() {
        World world = room.world();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            long asked = c.takeSandboxRequest();
            int id = c.entityId();
            if (asked != Connection.NO_SANDBOX && sandbox != null && id >= 0) {    // -1 once dead
                sandbox.apply(world.entities[id], (int) (asked >>> 32), (int) asked);
            }
        }
    }

    /**
     * Passes on what players have said (01 §9, D-32). In a mode with teams, to the speaker's team
     * wherever they are; in one without, to every player whose view holds the speaker's tank, so
     * a speaker with no tank is heard by nobody. An id outside the list takes no turn.
     */
    private void relayPhrases() {
        World world = room.world();
        boolean teams = rules.mode().teams();
        for (int i = 0; i < active.size(); i++) {
            Connection speaker = active.get(i);
            int phrase = speaker.takePhrase();
            if (!PHRASES.contains(phrase) || room.tick() < speaker.nextPhraseTick) {
                continue;
            }
            speaker.nextPhraseTick = room.tick() + PHRASE_GAP_TICKS;
            int id = speaker.entityId();                    // -1 once dead: handleDeaths has run
            Entity tank = id >= 0 ? world.entities[id] : null;
            for (int j = 0; j < active.size(); j++) {
                Connection listener = active.get(j);
                int handle = tank == null ? ClientView.NO_HANDLE : listener.view().heldHandle(tank.id, tank.generation);
                boolean hears = teams
                        ? teamNow(listener.identity().team(), listener.playerTag())
                                == teamNow(speaker.identity().team(), speaker.playerTag())
                        : handle != ClientView.NO_HANDLE;
                if (hears) {
                    listener.events().phrase(handle, phrase, speaker.identity().displayName());
                }
            }
        }
    }

    /** Tells each player what their own progression looks like, when it has changed. */
    private void publishStats() {
        World world = room.world();
        LevelTable levels = room.content().levels();
        for (int i = 0; i < active.size(); i++) {
            Connection c = active.get(i);
            int id = c.entityId();
            if (id < 0 || !world.entities[id].alive) {
                continue;
            }
            TankStats stats = world.tankStats[id];
            int next = stats.level >= levels.maxLevel() ? 0 : levels.xpRequired(stats.level + 1);
            c.publishStats(room.tick(), STATS_REFRESH_TICKS, stats.level, stats.xp, next,
                    stats.unspentPoints, stats.points);
        }
    }

    /** Issues the connection a new resume secret and tells the client, with everything else. */
    private void sendWelcome(Connection c, Entity tank) {
        byte[] raw = new byte[16];
        SECRETS.nextBytes(raw);
        String secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        c.setResumeSecret(secret);
        stays.put(secret, this);
        byte[] ascii = secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        SnapshotWriter w = new SnapshotWriter(96);
        w.u8(Wire.MSG_WELCOME);
        w.u8(Wire.SELF_HANDLE);             // and the encoder guarantees it, see the constant
        w.u8(c.traffic().profile().rate);   // the rate it starts at; tickDelta times each frame
        w.varint((long) room.world().width);
        w.varint((long) room.world().height);
        w.u8(rules.mode().id);              // mode: 0 the public arena, 1 a duel (04 §4)
        w.varint(room.content().classes().version());   // the class table platform serves (D-24)
        w.varint(PHRASES.version());        // the phrase list platform serves (01 §9)
        w.varint(tank.id);
        w.varint(ascii.length);             // what to resume this stay with (02 §10)
        w.bytes(ascii, ascii.length);
        w.varint(mazeSeed);                 // appended: a maze's walls, made from it (D-48); 0 none
        Frames.write(c.channel, w.array(), w.length());
    }
}
