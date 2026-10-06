package com.backend.arena;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import com.backend.handoff.MatchMode;
import com.backend.handoff.MatchOutcome;
import com.backend.sim.Room;
import com.backend.sim.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rooms hosted by this arena process, one thread each.
 *
 * Several rooms per process rather than several processes per room: a room is a single
 * thread against a deadline, and threads are cheap while processes are not. Crash
 * isolation is still handled by running several arena *processes* per machine
 * (NFR-8) — this registry is about using the cores inside one of them.
 *
 * **Allocation here is a fallback, not the real policy.** In the finished system
 * `platform` decides which room a player joins and says so in the join ticket, because it
 * is the only component that can see every arena
 * ([D-5](../../../../../../../../docs/architecture/03-decision-log.md)). The arena needs its own
 * answer only for direct joins with no allocator in front.
 */
public final class RoomRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);

    /** A room finishes its tick in 40 ms; this is for one that is badly behind. */
    private static final long STOP_WAIT_MILLIS = 5_000;

    /** Guarded by this. */
    private boolean closed;

    /** Stop filling a room a little before capacity, so a party is not split at the door. */
    private static final int JOIN_HEADROOM = 5;

    private final List<RoomThread> rooms = new CopyOnWriteArrayList<>();
    private final float mapSize;
    private final int worldCapacity;
    private final int maxPlayersPerRoom;
    private final int shapesPerRoom;
    private final int maxRooms;
    private final int joinCeiling;
    private final MatchRules rules;
    private final java.util.function.Consumer<MatchOutcome> onMatchEnd;

    /** The room each made match is played in, by its match id (D-20). Guarded by this. */
    private final Map<String, RoomThread> byMatch = new java.util.HashMap<>();

    /**
     * Matches played here that are over, the most recent ten thousand: a ticket for one that
     * arrives late must not start it again in a new room. Guarded by this.
     */
    private final Map<String, Boolean> ended = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 10_000;
        }
    };

    /** Tests only: shortens a made match, which would otherwise take three minutes to play. */
    volatile java.util.function.UnaryOperator<MatchRules> adjustMade = r -> r;
    /** Where a sandbox's players' holds are kept (D-54); none, and a sandbox keeps nothing. */
    private volatile com.backend.handoff.SandboxHolds sandboxHolds;
    /** How long a player an operator took out is refused a join: a ticket's life (T-35). */
    volatile long removedMillis = com.backend.handoff.TicketStore.TTL_SECONDS * 1_000L;
    private final java.util.Map<Long, Long> removedAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** Where a made match's first arrival is marked (Q-45); none, and nothing is. */
    private volatile com.backend.handoff.MatchArrivals arrivals;

    /** Bytes a second, the bounds of a connection's own rate: around NFR-2's ~4 200 combined. */
    static final double[] RATE_BOUNDS = {250, 500, 1_000, 1_500, 2_000, 3_000, 4_000, 6_000, 10_000};
    /** Each connection's own snapshot bytes a second, by the profile it asked for (02 §13, NFR-2). */
    final com.backend.common.Metrics.LabeledHistogram connectionRates =
            new com.backend.common.Metrics.LabeledHistogram(RATE_BOUNDS);
    /** Shorter, a connection is mostly its join's burst of creates, and is not counted. Tests shorten it. */
    volatile long rateMinMillis = 60_000;

    /**
     * A connection's end, however it came: its own snapshot bytes over its time from its first
     * frame, if that time is the minimum. Called on its event loop.
     */
    void connectionEnded(Connection c) {
        long first = c.firstFrameNanos();
        if (first == 0) {
            return;                                     // never sent a frame: never in a room
        }
        long millis = (System.nanoTime() - first) / 1_000_000;
        if (millis >= rateMinMillis) {
            connectionRates.observe(c.ceiling().label(), c.bytesSent() * 1_000.0 / millis);
        }
    }

    public RoomRegistry(float mapSize, int worldCapacity, int maxPlayersPerRoom,
                        int shapesPerRoom, int maxRooms) {
        this(mapSize, worldCapacity, maxPlayersPerRoom, shapesPerRoom, maxRooms,
                MatchRules.open("arena"), outcome -> { });
    }

    public RoomRegistry(float mapSize, int worldCapacity, int maxPlayersPerRoom,
                        int shapesPerRoom, int maxRooms, MatchRules rules,
                        java.util.function.Consumer<MatchOutcome> onMatchEnd) {
        this.rules = rules;
        this.onMatchEnd = onMatchEnd;
        this.mapSize = mapSize;
        this.worldCapacity = worldCapacity;
        this.maxPlayersPerRoom = maxPlayersPerRoom;
        this.shapesPerRoom = shapesPerRoom;
        this.maxRooms = maxRooms;
        // Headroom has to scale: a flat 5 would make a 4-player room reject everybody and
        // open a new room per player.
        this.joinCeiling = maxPlayersPerRoom - Math.min(JOIN_HEADROOM, maxPlayersPerRoom / 4);
        watchdog.scheduleAtFixedRate(this::watch, WATCH_EVERY_MILLIS, WATCH_EVERY_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    // ---- the watchdog (07 §8) ---------------------------------------------------------

    /** A room that has not finished a tick for this long is logged, with its thread's stack. */
    static final long STALL_WARN_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** ...and for this long, given up on: its players are sent back to the lobby. */
    static final long STALL_ABANDON_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final long WATCH_EVERY_MILLIS = 500;

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "room-watchdog");
        t.setDaemon(true);
        return t;
    });

    /**
     * Which room holds the stay a resume secret belongs to (02 §10), so a client coming back
     * on a new connection reaches its own tank. Written by the room threads, read by Netty's.
     */
    private final Map<String, RoomThread> stays = new ConcurrentHashMap<>();

    /** The room holding the stay {@code secret} resumes, or null if there is none. */
    public RoomThread roomFor(String secret) {
        return stays.get(secret);
    }

    /** The tick end each room was last warned about, so one stall is logged once. */
    private final Map<RoomThread, Long> warnedAt = new ConcurrentHashMap<>();

    /**
     * Finds rooms that have stopped ticking: an infinite loop or a stuck lock, which the
     * exception boundary cannot see because nothing is thrown. A stall of 2 s is logged with
     * the stack of the stuck thread — the one piece of evidence that says where it is stuck.
     * One of 10 s is abandoned ({@link RoomThread#abandon}): the players go back to the lobby
     * and the room is replaced. The thread itself cannot be stopped from outside; it is
     * reported, and the process should be restarted at a quiet time.
     */
    private void watch() {
        try {
            synchronized (this) {
                dropDone();                     // so the announcement counts the rooms there are
            }
            long now = System.nanoTime();
            for (RoomThread r : rooms) {
                if (!r.isTicking()) {
                    continue;                   // stopping, stopped or already given up on
                }
                long lastEnd = r.lastTickEndNanos();
                long stalled = now - lastEnd;
                if (stalled > STALL_ABANDON_NANOS) {
                    log.error("{} has not ticked for {} ms; abandoning it. Its thread cannot be"
                                    + " stopped: restart this process at a quiet time", r.name(),
                            TimeUnit.NANOSECONDS.toMillis(stalled));
                    r.abandon();
                } else if (stalled > STALL_WARN_NANOS && !Long.valueOf(lastEnd).equals(warnedAt.get(r))) {
                    warnedAt.put(r, lastEnd);
                    StringBuilder where = new StringBuilder();
                    for (StackTraceElement frame : r.stack()) {
                        where.append("\n\tat ").append(frame);
                    }
                    log.warn("{} has not ticked for {} ms; it is at:{}", r.name(),
                            TimeUnit.NANOSECONDS.toMillis(stalled), where);
                }
            }
        } catch (RuntimeException e) {
            log.error("room watchdog failed", e);   // a scheduled task that throws stops for good
        }
    }

    public List<RoomThread> rooms() {
        return rooms;
    }

    /** A count kept by each room, which the arena reports summed over them. */
    public enum Counter {
        TICK_OVERRUNS(RoomThread::overruns),
        SNAPSHOTS_SENT(RoomThread::snapshotsSent),
        SNAPSHOTS_SKIPPED(RoomThread::snapshotsSkipped),
        SNAPSHOTS_HELD(RoomThread::snapshotsHeld),
        RESUMES(RoomThread::resumedCount),
        STAYS_EXPIRED(RoomThread::staysExpiredCount),
        PROFILE_STEPS_DOWN(RoomThread::profileStepsDown),
        PROFILE_STEPS_UP(RoomThread::profileStepsUp);

        private final ToLongFunction<RoomThread> read;

        Counter(ToLongFunction<RoomThread> read) {
            this.read = read;
        }
    }

    /*
     * What the dropped rooms had counted. Summed over the rooms there are, a counter fell when a
     * failed room was dropped, and a counter that falls reads as a process restart to whatever
     * scrapes it: the increase it computes is wrong. Guarded by this, as the drop is, so no read
     * sees a room neither among the rooms nor among the retired.
     */
    private long droppedRooms;
    private final long[] retired = new long[Counter.values().length];
    private final long[] retiredBytes = new long[TrafficProfile.values().length];

    /** Rooms that failed or were abandoned, ever: dropped ones plus any not dropped yet. */
    public synchronized long failedRooms() {
        long waiting = 0;
        for (RoomThread r : rooms) {
            if (r.hasFailed()) {
                waiting++;
            }
        }
        return droppedRooms + waiting;
    }

    /** {@code counter} over every room this process has run, dropped ones included. */
    public synchronized long total(Counter counter) {
        long n = retired[counter.ordinal()];
        for (RoomThread r : rooms) {
            n += counter.read.applyAsLong(r);
        }
        return n;
    }

    /** Snapshot bytes sent at {@code profile} by every room this process has run. */
    public synchronized long bytesSentAt(TrafficProfile profile) {
        long n = retiredBytes[profile.ordinal()];
        for (RoomThread r : rooms) {
            n += r.bytesSentAt(profile);
        }
        return n;
    }

    /** Guarded by this, and called with {@code r} just removed from the rooms. */
    private void retire(RoomThread r, boolean failed) {
        if (failed) {
            droppedRooms++;
        }
        for (Counter c : Counter.values()) {
            retired[c.ordinal()] += c.read.applyAsLong(r);
        }
        for (TrafficProfile p : TrafficProfile.values()) {
            retiredBytes[p.ordinal()] += r.bytesSentAt(p);
        }
        warnedAt.remove(r);
    }

    public int roomCount() {
        return rooms.size();
    }

    public int maxRooms() {
        return maxRooms;
    }

    /** Every seat this arena could fill, which is what the directory advertises. */
    public int capacity() {
        return maxPlayersPerRoom * maxRooms;
    }

    public int totalPlayers() {
        int n = 0;
        for (RoomThread r : rooms) {
            n += r.playerCount();
        }
        return n;
    }

    /**
     * Players for whom a place was sought since the last announcement, placed or refused: the
     * platform promised each a seat here, which the announcement drops (D-79).
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> seated = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Notes a player once a place has been sought for them, so a placed one's reservation is counted with it. */
    public void seated(long playerId) {
        seated.add(playerId);
    }

    /** The players noted since the last call. */
    public List<Long> takeSeated() {
        List<Long> taken = new java.util.ArrayList<>();
        for (Long player; (player = seated.poll()) != null; ) {
            taken.add(player);
        }
        return taken;
    }

    /** Players and the places reserved for joins not yet in their rooms: what an announcement counts (D-79). */
    public int committedPlayers() {
        int n = 0;
        for (RoomThread r : rooms) {
            n += r.committedCount();
        }
        return n;
    }

    private int roomsCreated;                   // guarded by this

    /**
     * Picks a room for a joining player, creating one if necessary.
     *
     * Fills the fullest room that still has headroom, rather than spreading players
     * evenly. An empty arena is dull, so concentrating players is the
     * better failure mode: the worst case is one busy room and one empty one, instead of
     * several half-empty ones where nobody meets anybody.
     *
     * @return a room, or null when every room is full and no more may be created
     */
    public synchronized RoomThread allocate() {
        if (closed || draining) {
            // A ticket claim can land after shutdown has begun. Creating a room then made a
            // thread nobody would ever stop, and a non-daemon one, so the process never exited.
            return null;
        }
        dropDone();
        RoomThread best = null;
        int bestCount = -1;
        for (RoomThread r : rooms) {
            if (!r.accepting() || r.matchUid() != null) {
                // Stuck: chosen here, it refused the player a new room would take. Or made
                // for one match, which takes nobody else.
                continue;
            }
            // Committed, not current: a burst of joins is still in flight in the queues.
            int n = r.committedCount();
            // Below the ceiling, not at it: at it, a room kept filling to one past the headroom,
            // and a room too small for any headroom was chosen full and refused the player.
            if (n < joinCeiling && n > bestCount) {
                best = r;
                bestCount = n;
            }
        }
        if (best == null && rooms.size() < maxRooms) {
            best = create();
        }
        if (best == null) {
            // Every room is at its ceiling. Take any room with real capacity left rather
            // than refusing a player over the join headroom, which is a preference.
            for (RoomThread r : rooms) {
                if (r.matchUid() == null && r.tryReserve()) {
                    return r;
                }
            }
            return null;
        }
        return best.tryReserve() ? best : null;
    }

    /**
     * The room for a place in a match the matcher made (04 §4; D-20): the one its first ticket
     * made, or a new one if this is the first. Null when there is no room for it — every room
     * is in use, or the match is already over here — and the player is sent back to queue.
     */
    public synchronized RoomThread allocateMatch(String matchUid, MatchMode mode) {
        if (closed || mode == null || !mode.made()) {
            return null;
        }
        dropDone();
        RoomThread r = byMatch.get(matchUid);
        if (r == null) {
            if (draining || ended.containsKey(matchUid) || rooms.size() >= maxRooms) {
                return null;
            }
            r = createMade(matchUid, mode);
        }
        return r.reserveForMatch() ? r : null;
    }

    /**
     * Drops a room that closed itself after failing, which has already sent its players back
     * to the lobby, so nobody is placed in it and a healthy room can take its place; and a made
     * match's room once the match is over. Guarded by this.
     */
    private void dropDone() {
        for (RoomThread r : rooms) {
            if (r.hasFailed() && rooms.remove(r)) {
                retire(r, true);
                if (r.matchUid() != null) {
                    byMatch.remove(r.matchUid());
                    ended.put(r.matchUid(), Boolean.TRUE);
                }
                log.warn("dropped a failed room; {} rooms now", rooms.size());
            } else if (r.isFinished() && rooms.remove(r)) {
                retire(r, false);
                byMatch.remove(r.matchUid());
                ended.put(r.matchUid(), Boolean.TRUE);
            }
        }
    }

    /** Keeps each sandbox player's hold while they are in its room (D-54). Before the first join. */
    public void holdSandboxes(com.backend.handoff.SandboxHolds holds) {
        this.sandboxHolds = holds;
    }

    /** Marks each made match's first arrival, for a tournament to wait for its result (Q-45). */
    public void markArrivals(com.backend.handoff.MatchArrivals arrivals) {
        this.arrivals = arrivals;
    }

    private RoomThread createMade(String matchUid, MatchMode mode) {
        World world = new World(mode.mapSize, mode.mapSize, worldCapacity, 200f,
                System.nanoTime() ^ rooms.size());
        Room room = new Room(world);
        for (int i = 0; i < mode.shapes; i++) {
            room.spawnShape();
        }
        RoomThread rt = new RoomThread(room, mode.places(), mode.shapes,
                adjustMade.apply(rules.made(mode, matchUid)), onMatchEnd, stays);
        if (mode == MatchMode.SANDBOX) {
            rt.holdSandboxes(sandboxHolds);
        } else {
            rt.markArrivals(arrivals);
        }
        int index = ++roomsCreated;
        rt.start("room-" + index);
        rooms.add(rt);
        byMatch.put(matchUid, rt);
        log.info("created room-{} for {} {} ({} rooms now)", index, mode.key, matchUid, rooms.size());
        return rt;
    }

    private RoomThread create() {
        World world = new World(mapSize, mapSize, worldCapacity, 200f,
                System.nanoTime() ^ rooms.size());
        Room room = new Room(world);
        for (int i = 0; i < shapesPerRoom; i++) {
            room.spawnShape();
        }
        RoomThread rt = new RoomThread(room, maxPlayersPerRoom, shapesPerRoom, rules, onMatchEnd, stays);
        // Counted, never reused: named by how many rooms there were, one replacing a failed
        // room took a living room's name, and the tick metric, keyed by name, lost one.
        int index = ++roomsCreated;
        rt.start("room-" + index);
        rooms.add(rt);
        log.info("created room-{} ({} rooms now)", index, rooms.size());
        return rt;
    }

    /** Starts one room up front, so an arena is never empty of threads when the first client arrives. */
    public void startInitialRoom() {
        synchronized (this) {
            if (rooms.isEmpty()) {
                create();
            }
        }
    }

    /** Every room as an operator sees it (04 §10). */
    public synchronized List<RoomThread.RoomView> views() {
        dropDone();
        return rooms.stream().map(RoomThread::view).toList();
    }

    /**
     * An operator closes a room (04 §10): taken out of the registry at once, so nobody else is
     * placed in it, and stopped; its players are kicked with {@code reason} as it goes.
     *
     * @return false when no room has that name
     */
    public synchronized boolean closeRoom(String name, int reason) {
        for (RoomThread r : rooms) {
            if (r.name().equals(name)) {
                rooms.remove(r);
                if (r.matchUid() != null) {
                    byMatch.remove(r.matchUid());
                    ended.put(r.matchUid(), Boolean.TRUE);
                }
                r.close(reason);
                return true;
            }
        }
        return false;
    }

    /** An operator takes a player out of whichever room they are in (04 §10). */
    public synchronized void remove(long playerId, boolean banned) {
        if (banned) {
            removedAt.put(playerId, System.currentTimeMillis());   // an operator's few a day; a restart forgets them
        }
        for (RoomThread r : rooms) {
            r.remove(playerId);
        }
    }

    /**
     * Whether an operator took this player out within a ticket's life: a ticket issued before a
     * ban, its player found in no room, would otherwise join after it (T-35).
     */
    public boolean removed(long playerId) {
        Long at = removedAt.get(playerId);
        return at != null && at + removedMillis > System.currentTimeMillis();
    }

    /** Draining (01 §8.6): no new room, and no new player in a public one. Guarded by this. */
    private boolean draining;

    /**
     * Drains before a stop (01 §8.6; D-29): no new room is made, nor a public room joined; the
     * public rooms stop now, publishing what their players are owed and sending them back to the
     * lobby, since they have no end to wait for; the made rooms play on to their own ends. A
     * player of one may still come back to it.
     *
     * @return true when every made room has ended; false when {@code millis} ran out first, and
     *         {@link #close} then cuts short what is left
     */
    public boolean drain(long millis) throws InterruptedException {
        List<RoomThread> open;
        synchronized (this) {
            draining = true;
            open = rooms.stream().filter(r -> r.matchUid() == null).toList();
        }
        for (RoomThread r : open) {
            r.stop();
        }
        for (RoomThread r : open) {
            r.awaitStopped(STOP_WAIT_MILLIS);
        }
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            synchronized (this) {
                dropDone();
                if (rooms.stream().noneMatch(r -> r.matchUid() != null)) {
                    return true;
                }
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(DRAIN_POLL_MILLIS);
        }
    }

    private static final long DRAIN_POLL_MILLIS = 200;

    @Override
    public void close() {
        List<RoomThread> snapshot;
        synchronized (this) {
            if (closed) {
                return;                     // idempotent: a second close is a no-op
            }
            closed = true;
            snapshot = List.copyOf(rooms);
        }
        watchdog.shutdownNow();
        // Every room is told first and waited for second, so they finish in parallel rather
        // than one after another.
        for (RoomThread r : snapshot) {
            r.stop();
        }
        for (RoomThread r : snapshot) {
            try {
                if (!r.awaitStopped(STOP_WAIT_MILLIS)) {
                    log.warn("a room did not stop within {} ms; its players' last results may"
                            + " not have been published", STOP_WAIT_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
