package com.backend.arena;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.handoff.Ticket;
import com.backend.protocol.ClientMessage;
import com.backend.sim.ClassTable;
import com.backend.sim.Stat;

import io.netty.channel.Channel;

/**
 * One client's connection. The boundary between Netty threads and the room thread.
 *
 * Netty threads write what the client sends - its latest input, fire latch, acknowledgement,
 * respawn and upgrade requests, lifecycle - and the flags the room reads; the room thread
 * reads them. Inputs are deliberately not a queue: they coalesce per tick anyway, so a queue
 * would allocate ten times a second per client (02 §9) to deliver data the room would
 * immediately throw away. A single atomic long publishes a whole input as a unit.
 */
public final class Connection {

    public final Channel channel;

    /** Set by the room thread when the join is processed; -1 until then. */
    private volatile int entityId = -1;
    private volatile boolean closing;

    /**
     * Who this connection is, from the claimed join ticket; null until the claim resolves.
     *
     * Written on the channel's event loop when the ticket claim completes (T-1), or by the room
     * thread when it takes a resume; read on the room thread, and on the event loop for
     * logging. The reference is volatile and the ticket is immutable, so there is no
     * half-published identity — and no per-field reasoning about which write the room thread
     * might see.
     */
    private volatile Ticket identity;

    /** What the client asked for in its Join or Resume, set on the event loop before it is offered. */
    private volatile TrafficProfile ceiling = TrafficProfile.MOBILE;

    /**
     * The stay ends with this connection, rather than waiting for the player to resume it
     * (02 §10): they left, were kicked, or the arena is stopping.
     */
    private volatile boolean endsStay;
    /** Another connection resumed this one's stay; nothing about it is this one's to end. */
    private volatile boolean superseded;
    /** What the client may resume this stay with, as its Welcome told it. Room thread only. */
    private String resumeSecret;
    /** What the client asked to resume with, set before it is offered to the room. */
    private volatile String resuming;

    private ClientView view;                       // room thread only
    private TrafficControl traffic;                // room thread only
    private long playerTag;                        // room thread only
    private int matchStartTick;                  // room thread only
    private final EventBuffer events = new EventBuffer();   // room thread only
    private boolean wasBackgrounded;               // room thread only, for edge detection

    private final AtomicLong latestInput = new AtomicLong();
    private volatile long inputAt;

    /** Skill points asked for and not yet spent, one counter per stat. */
    private final AtomicIntegerArray pendingUpgrades = new AtomicIntegerArray(Stat.COUNT);

    /** The class last asked for and not yet taken, or {@link #NO_CLASS}. */
    private final AtomicInteger pendingClass = new AtomicInteger(NO_CLASS);
    static final int NO_CLASS = -1;

    /** The phrase last said and not yet passed on, or {@link #NO_PHRASE}, which no list holds. */
    private final AtomicInteger pendingPhrase = new AtomicInteger(NO_PHRASE);
    static final int NO_PHRASE = 0;

    /** The sandbox's request last made and not yet taken, action and value in one, or {@link #NO_SANDBOX}. */
    private final AtomicLong pendingSandbox = new AtomicLong(NO_SANDBOX);
    static final long NO_SANDBOX = 0;              // action 0 is none

    /** Snapshot bytes written to it, and when the first went: written by the room thread, read as it closes. */
    private volatile long bytesSent;
    private volatile long firstFrameNanos;

    /** The room thread's, as each frame goes out (02 §13). */
    void sent(int bytes) {
        if (firstFrameNanos == 0) {
            firstFrameNanos = System.nanoTime();
        }
        bytesSent += bytes;                             // one writer: the room thread
    }

    long bytesSent() {
        return bytesSent;
    }

    long firstFrameNanos() {
        return firstFrameNanos;
    }

    /** The experience the player's last life reached, for the rebate on respawn (01 §7). Room thread only. */
    int lastLifeXp;

    /** The first tick this player may be heard again (01 §9). Room thread only. */
    int nextPhraseTick;

    /**
     * What the client was last told about its own progression, so an unchanged tick sends
     * nothing. Room thread only, which is why none of it is volatile.
     */
    private int sentLevel = -1;
    private int sentXp = -1;
    private int sentUnspent = -1;
    private final byte[] sentPoints = new byte[Stat.COUNT];
    private int lastStatsTick = Integer.MIN_VALUE;
    private final AtomicInteger ackTick = new AtomicInteger();

    /**
     * Sticky fire bit. A tap between two ticks must not be lost, so any input that carries
     * FIRE latches it here and the room clears it when the shot is taken.
     */
    private final AtomicInteger fireLatch = new AtomicInteger();

    /** Set from a Netty thread when the client asks to respawn; consumed by the room thread. */
    private final java.util.concurrent.atomic.AtomicBoolean respawnRequested =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The app is in the background. Written from a Netty thread, read by the room thread.
     *
     * A backgrounded phone has already lost its socket buffer to the OS in most cases, and
     * carries on being charged for data it cannot render. The tank is parked and snapshots
     * stop until it comes back.
     */
    private volatile boolean backgrounded;

    private long snapshotsSent;
    private long snapshotsSkipped;

    public Connection(Channel channel) {
        this.channel = channel;
    }

    public int entityId() {
        return entityId;
    }

    /** The simulation's player tag for the current match. Room thread only. */
    long playerTag() {
        return playerTag;
    }

    void setPlayerTag(long tag) {
        this.playerTag = tag;
    }

    /** When this player's current recorded match began, in room ticks. */
    int matchStartTick() {
        return matchStartTick;
    }

    void setMatchStartTick(int tick) {
        this.matchStartTick = tick;
    }

    void setEntityId(int id) {
        this.entityId = id;
    }

    public Ticket identity() {
        return identity;
    }

    void identify(Ticket ticket) {
        this.identity = ticket;
    }

    public boolean isClosing() {
        return closing;
    }

    public void markClosing() {
        closing = true;
    }

    ClientView view() {
        return view;
    }

    void setView(ClientView view) {
        this.view = view;
    }

    TrafficProfile ceiling() {
        return ceiling;
    }

    void endStay() {
        endsStay = true;
    }

    boolean endsStay() {
        return endsStay;
    }

    void supersede() {
        superseded = true;
        endsStay = true;
    }

    boolean superseded() {
        return superseded;
    }

    String resumeSecret() {
        return resumeSecret;
    }

    void setResumeSecret(String secret) {
        this.resumeSecret = secret;
    }

    String resuming() {
        return resuming;
    }

    void setResuming(String secret) {
        this.resuming = secret;
    }

    void setCeiling(TrafficProfile ceiling) {
        this.ceiling = ceiling;
    }

    TrafficControl traffic() {
        return traffic;
    }

    void setTraffic(TrafficControl traffic) {
        this.traffic = traffic;
    }

    /** Called on a Netty thread. Ignored by the room thread while the tank is alive. */
    public void requestRespawn() {
        respawnRequested.set(true);
    }

    /**
     * Asks for a skill point in {@code stat}. Called on a Netty thread.
     *
     * A count per stat rather than a queue, because a player spending several points taps
     * several times in a second and the order between two different stats does not matter —
     * only how many of each. Coalescing them means nothing to allocate and nothing to drop.
     *
     * Clamped because the counter is the one thing here a client controls the size of, and
     * more requests than a tank could ever honour is either a broken client or a probe.
     */
    public void requestUpgrade(int stat) {
        if (!Stat.isValid(stat)) {
            return;                         // not a stat, and not worth a disconnection
        }
        pendingUpgrades.accumulateAndGet(stat, 1,
                (current, one) -> Math.min(current + one, ClassTable.MOST_POINTS));
    }

    /** Takes and clears the pending requests. Room thread only. */
    int takeUpgradeRequests(int stat) {
        return pendingUpgrades.getAndSet(stat, 0);
    }

    /**
     * Asks for a tank class (01 §4). Called on a Netty thread. One slot, not a queue: two taps
     * in a tick can at most make one choice, so the later wins, and whether it is allowed is
     * the room's to decide.
     */
    public void requestClass(int classId) {
        pendingClass.set(classId);
    }

    /** Takes and clears the pending choice, or {@link #NO_CLASS}. Room thread only. */
    int takeClassRequest() {
        return pendingClass.getAndSet(NO_CLASS);
    }

    /**
     * Says a phrase (01 §9). Called on a Netty thread. One slot, as for a class: whether it may
     * be said, and who hears it, is the room's to decide.
     */
    public void requestPhrase(int phraseId) {
        pendingPhrase.set(phraseId);
    }

    /** Takes and clears the pending phrase, or {@link #NO_PHRASE}. Room thread only. */
    int takePhrase() {
        return pendingPhrase.getAndSet(NO_PHRASE);
    }

    /**
     * A sandbox's power (01 §8.10). Called on a Netty thread. One slot, as for a class: whether
     * this room is a sandbox, and what it allows, is the room's to decide.
     */
    public void requestSandbox(int action, int value) {
        pendingSandbox.set((long) action << 32 | (value & 0xFFFFFFFFL));
    }

    /** Takes and clears the pending request, or {@link #NO_SANDBOX}. Room thread only. */
    long takeSandboxRequest() {
        return pendingSandbox.getAndSet(NO_SANDBOX);
    }

    boolean takeRespawnRequest() {
        return respawnRequested.compareAndSet(true, false);
    }

    /** Called on a Netty thread. */
    public void setBackgrounded(boolean value) {
        this.backgrounded = value;
    }

    public boolean isBackgrounded() {
        return backgrounded;
    }

    boolean wasBackgrounded() {
        return wasBackgrounded;
    }

    void setWasBackgrounded(boolean value) {
        this.wasBackgrounded = value;
    }

    EventBuffer events() {
        return events;
    }

    /**
     * Queues a progression event if anything the client can see has changed.
     *
     * Experience alone moves every time a shape dies, so on its own it is throttled to once
     * a second; a level or a spent point goes out at once, because those are the ones a
     * player is waiting to see. Sending the whole thing every snapshot would be about 225 B/s,
     * some 8 % of a mobile client's downstream budget (02 §4), spent animating a bar.
     *
     * Room thread only.
     */
    void publishStats(int tick, int ticksBetweenRefreshes,
                      int level, int xp, int xpForNextLevel, int unspentPoints, byte[] points) {
        boolean structural = level != sentLevel || unspentPoints != sentUnspent
                || !java.util.Arrays.equals(points, sentPoints);
        boolean due = xp != sentXp && tick - lastStatsTick >= ticksBetweenRefreshes;
        if (!structural && !due) {
            return;
        }
        events.stats(level, xp, xpForNextLevel, unspentPoints, points);
        sentLevel = level;
        sentXp = xp;
        sentUnspent = unspentPoints;
        System.arraycopy(points, 0, sentPoints, 0, points.length);
        lastStatsTick = tick;
    }

    /** Forgets what the client has been told, so the next publish sends everything. */
    void forgetStats() {
        sentLevel = -1;
        sentXp = -1;
        sentUnspent = -1;
        java.util.Arrays.fill(sentPoints, (byte) -1);
        lastStatsTick = Integer.MIN_VALUE;
    }

    /** Called on a Netty thread. */
    public void offerInput(int seq, int aim, int moveMask, int flags, int ack) {
        latestInput.set(ClientMessage.packInput(seq, aim, moveMask, flags));
        inputAt = System.nanoTime();
        if ((flags & ClientMessage.FLAG_FIRE) != 0) {
            fireLatch.lazySet(1);
        }
        // Acks can arrive out of order if the client coalesces; keep the highest.
        ackTick.accumulateAndGet(ack, Math::max);
    }

    long takeInput() {
        return latestInput.get();
    }

    /** When the latest input arrived, by {@link System#nanoTime}; 0 before any. */
    long inputAt() {
        return inputAt;
    }

    boolean takeFire() {
        return fireLatch.getAndSet(0) != 0;
    }

    int takeAck() {
        return ackTick.get();
    }

    void countSent() {
        snapshotsSent++;
    }

    void countSkipped() {
        snapshotsSkipped++;
    }

    public long snapshotsSent() {
        return snapshotsSent;
    }

    public long snapshotsSkipped() {
        return snapshotsSkipped;
    }
}
