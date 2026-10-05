package com.backend.arena;

/**
 * Steps one client's traffic down when its link cannot carry it, and back up when it can
 * (02 §8). Owned by the room thread, like the client's view.
 *
 * <h2>What it measures: the queue, not the distance</h2>
 *
 * A link that cannot carry a stream queues it: each snapshot waits behind the last, and the
 * player sees an ever older world. Fewer bytes cure that. They do nothing for a link that is
 * merely far - a 600 ms path with room to spare only gets a worse picture for being stepped
 * down - so the round trip itself is not the signal. The signal is how long the oldest
 * snapshot not yet acknowledged has been out, above the least that link has ever needed (its
 * floor, the shortest acknowledgement of the connection). The floor absorbs distance, the
 * client's acknowledgement interval and the round's own granularity; what is left over is
 * queue.
 *
 * The floor never rises. A floor over a window - two minutes, as first built - rose to meet a
 * queue that never emptied, and on a link slow for the whole session the player's view
 * drifted from two seconds old to six in ten minutes. What a fixed floor gives up is a link
 * whose own delay rises within one connection: a network change that moves the phone's
 * address starts a new connection, and one that keeps it, as a fall from LTE to 3G may, adds
 * less than the 300 ms that counts as queue.
 *
 * Socket writability, which is all the server looked at before, is a late signal: Netty turns
 * unwritable only once the kernel's buffer and 128 KiB of its own are full, which at three
 * kilobytes a second is most of a minute of snapshots. It is kept, as the backstop.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>More than 300 ms queued for half a second: one step down, and no second step within
 *       two seconds, so the first has time to show.</li>
 *   <li>More than a second queued: nothing is added to it. Deltas are measured against the
 *       last frame sent, not acknowledged (D-16), so a held round only makes the next frame
 *       a little larger, never wrong. Here the oldest send's age is not enough: it says how
 *       long the queue was, and while its acknowledgement is awaited every round would add
 *       to it - on a link a fraction of the stream, seconds more. So a frame sent now is
 *       judged by what is ahead of it: the bytes out, at the rate the link has been
 *       delivering them.</li>
 *   <li>Two unwritable rounds in a row: one step down; an unwritable round is never sent.</li>
 *   <li>Ten seconds with at most 200 ms queued and no change: one step up, never above what
 *       the client asked for. A step up is a probe, and one that fails - a step down within
 *       ten seconds of it - costs the player a moment of queue; so each failure doubles the
 *       wait before the next, up to 160 s, and any other step down starts it again at ten.</li>
 * </ul>
 *
 * Nothing is judged before the first acknowledgement, since there is no floor until then;
 * a client that never acknowledges is left at its profile, and only writability applies.
 */
final class TrafficControl {

    private static final long MS = 1_000_000L;
    static final long STEP_DOWN_QUEUE = 300 * MS;
    static final long STEP_DOWN_AFTER = 500 * MS;
    static final long BETWEEN_STEPS_DOWN = 2_000 * MS;
    static final long HOLD_QUEUE = 1_000 * MS;
    static final long HEALTHY_QUEUE = 200 * MS;
    static final long STEP_UP_AFTER = 10_000 * MS;
    static final long LONGEST_STEP_UP_WAIT = 160_000 * MS;

    /** Sends remembered: four seconds at 15 Hz. An older one unacknowledged is a full queue anyway. */
    private static final int IN_FLIGHT = 64;
    private static final long NONE = Long.MIN_VALUE;

    private final TrafficProfile ceiling;
    private TrafficProfile profile;
    private int roundCredit;

    private final int[] sentTick = new int[IN_FLIGHT];
    private final long[] sentAt = new long[IN_FLIGHT];
    private final int[] sentBytes = new int[IN_FLIGHT];
    private int oldest;                  // ring index of the oldest send not yet acknowledged
    private int inFlight;
    private long inFlightBytes;
    private int lastAcked = Integer.MIN_VALUE;

    /** Bytes the client has acknowledged, all told, sampled at each acknowledgement: the link's rate. */
    private static final int RATE_SAMPLES = 64;
    private static final long RATE_OVER = 1_000 * MS;
    private static final long RATE_FORGET = 3_000 * MS;
    private final long[] ackAt = new long[RATE_SAMPLES];
    private final long[] ackTotal = new long[RATE_SAMPLES];
    private int ackNewest = -1;
    private int ackSamples;
    private long ackedBytes;

    /** The least delay any acknowledgement has shown, or {@link #NONE} before the first. */
    private long floor = NONE;

    private long congestedSince = NONE;
    private long healthySince = NONE;
    private long lastChange = NONE;
    private long lastStepDown = NONE;
    private long lastStepUp = NONE;
    private long stepUpWait = STEP_UP_AFTER;
    private int unwritableRounds;
    private int stepsDown;
    private int stepsUp;
    private boolean changed;

    TrafficControl(TrafficProfile ceiling) {
        this.ceiling = ceiling;
        this.profile = ceiling;
    }

    TrafficProfile profile() {
        return profile;
    }

    int stepsDown() {
        return stepsDown;
    }

    int stepsUp() {
        return stepsUp;
    }

    /** Whether the profile changed since the last call: its budget is then the view's to take. */
    boolean takeChange() {
        boolean was = changed;
        changed = false;
        return was;
    }

    /**
     * Called every tick: whether this is one of the client's rounds. Its rate, in ticks, with
     * no floating-point drift: fifteen rounds in every twenty-five ticks at 15 Hz. Each client
     * keeps its own count from when it joined, so a room's encoding is spread over its ticks
     * rather than all of it falling on the same fifteen.
     */
    boolean due(int ticksPerSecond) {
        roundCredit += profile.rate;
        if (roundCredit < ticksPerSecond) {
            return false;
        }
        roundCredit -= ticksPerSecond;
        return true;
    }

    /** A snapshot for {@code tick}, {@code bytes} long, went out at {@code now}. */
    void sent(int tick, long now, int bytes) {
        if (inFlight == IN_FLIGHT) {
            inFlightBytes -= sentBytes[oldest];      // forget the oldest; the queue is full anyway
            oldest = (oldest + 1) % IN_FLIGHT;
            inFlight--;
        }
        int at = (oldest + inFlight) % IN_FLIGHT;
        sentTick[at] = tick;
        sentAt[at] = now;
        sentBytes[at] = bytes;
        inFlight++;
        inFlightBytes += bytes;
    }

    /**
     * The client has applied every snapshot up to {@code tick}, as the server learns at
     * {@code now}. Its delay, if that snapshot is remembered, is a measurement of the link.
     */
    void acknowledged(int tick, long now) {
        if (tick <= lastAcked) {
            return;
        }
        lastAcked = tick;
        while (inFlight > 0 && sentTick[oldest] <= tick) {
            if (sentTick[oldest] == tick) {
                long delay = now - sentAt[oldest];
                floor = floor == NONE ? delay : Math.min(floor, delay);
            }
            ackedBytes += sentBytes[oldest];
            inFlightBytes -= sentBytes[oldest];
            oldest = (oldest + 1) % IN_FLIGHT;
            inFlight--;
        }
        ackNewest = (ackNewest + 1) % RATE_SAMPLES;
        ackAt[ackNewest] = now;
        ackTotal[ackNewest] = ackedBytes;
        ackSamples = Math.min(ackSamples + 1, RATE_SAMPLES);
    }

    /**
     * Nothing will be sent for a while - a death, the app in the background - so what is out
     * stops counting: its acknowledgement will come late, or never, and says nothing of the link.
     */
    void idle() {
        inFlight = 0;
        inFlightBytes = 0;
        ackSamples = 0;
        congestedSince = NONE;
        healthySince = NONE;
        unwritableRounds = 0;
    }

    /**
     * One of this client's rounds, before anything is encoded for it: adjusts the profile,
     * and says whether to send.
     *
     * @return false to hold this round back
     */
    boolean round(long now, boolean writable) {
        if (!writable) {
            healthySince = NONE;
            if (++unwritableRounds >= 2) {
                stepDown(now);
            }
            return false;
        }
        unwritableRounds = 0;
        if (floor == NONE) {
            return true;
        }
        long queued = inFlight == 0 ? 0 : Math.max(0, now - sentAt[oldest] - floor);
        if (queued > STEP_DOWN_QUEUE) {
            healthySince = NONE;
            if (congestedSince == NONE) {
                congestedSince = now;
            } else if (now - congestedSince >= STEP_DOWN_AFTER) {
                stepDown(now);
            }
        } else {
            congestedSince = NONE;
            if (queued > HEALTHY_QUEUE) {
                healthySince = NONE;
            } else if (healthySince == NONE) {
                healthySince = now;
            } else if (profile != ceiling && now - healthySince >= stepUpWait
                    && (lastChange == NONE || now - lastChange >= stepUpWait)) {
                profile = profile.up();
                stepsUp++;
                changed = true;
                lastChange = now;
                lastStepUp = now;
                healthySince = now;
            }
        }
        return queued <= HOLD_QUEUE && ahead(now, floor) <= HOLD_QUEUE;
    }

    /**
     * How long a frame sent now would queue: the bytes already out, at the rate the link has
     * delivered over the last second or more, less the floor. 0 while the rate is not known.
     */
    private long ahead(long now, long floor) {
        if (inFlightBytes == 0) {
            return 0;
        }
        // The oldest sample still worth using, at least RATE_OVER old.
        long since = NONE;
        long total = 0;
        for (int i = 0; i < ackSamples; i++) {
            int at = Math.floorMod(ackNewest - i, RATE_SAMPLES);
            if (now - ackAt[at] > RATE_FORGET) {
                break;
            }
            since = ackAt[at];
            total = ackTotal[at];
        }
        if (since == NONE || now - since < RATE_OVER) {
            return 0;
        }
        double bytesPerNano = (ackedBytes - total) / (double) (now - since);
        if (bytesPerNano <= 0) {
            return Long.MAX_VALUE;       // nothing delivered for a second: nothing more goes in
        }
        return Math.max(0, (long) (inFlightBytes / bytesPerNano) - floor);
    }

    private void stepDown(long now) {
        if (profile == TrafficProfile.SAVER
                || lastStepDown != NONE && now - lastStepDown < BETWEEN_STEPS_DOWN) {
            return;
        }
        boolean probeFailed = lastStepUp != NONE && now - lastStepUp < STEP_UP_AFTER;
        stepUpWait = probeFailed ? Math.min(stepUpWait * 2, LONGEST_STEP_UP_WAIT) : STEP_UP_AFTER;
        profile = profile.down();
        stepsDown++;
        changed = true;
        lastChange = now;
        lastStepDown = now;
        congestedSince = now;           // the next step needs its own half second
        healthySince = NONE;
    }
}
