package com.backend.arena;

import java.util.Arrays;

import com.backend.protocol.ClientMessage;
import com.backend.protocol.Wire;

/**
 * Everything the server remembers about one client's view of the world.
 *
 * Deltas are computed against the state last <em>sent</em> (D-16): TCP delivers every frame
 * once and in order, so what was sent is what the client will hold. The client's
 * acknowledgement decides two things only: when a removed handle may be reused, and whether
 * the frame that created a handle has arrived.
 *
 * Owned by the room thread. Nothing here is synchronised.
 */
public final class ClientView {

    /** Handle 0 means "no handle", so a create can reference an owner that is not visible. */
    public static final int NO_HANDLE = 0;

    /** The most entities one snapshot section can carry: its count slot is a one-byte varint. */
    public static final int MAX_ENTITY_BUDGET = 127;
    private static final int FIRST_HANDLE = 1;

    /** An input's ticks go out as a byte. */
    private static final int MAX_INPUT_TICKS = 255;

    /**
     * The first handle the shared pool hands out. {@link Wire#SELF_HANDLE} is never in the
     * pool: it belongs to the client's own tank alone. It used to be the pool's first handle,
     * relying on the own tank being encoded first; another tank within four units and on a
     * lower slot tied it on distance, took handle 1, and the player was never sent their own
     * tank at all while the welcome said handle 1 was theirs.
     */
    private static final int FIRST_SHARED_HANDLE = Wire.SELF_HANDLE + 1;

    /**
     * The client's own tank. Changes when the player respawns: the view is kept across a
     * death, because a new view starts from tick 0 and origin 0, and every delta the client
     * then applied — tick, camera, every position — was off by where it had been.
     */
    public int selfId;
    public float viewWidth, viewHeight;
    public int entityBudget;

    /** entity id → handle, or 0. Sized to the world, which costs ~32 KB per client. */
    private final short[] entityHandle;

    private final int[] handleEntity = new int[Wire.MAX_HANDLES];

    /**
     * The generation of the entity each handle was created for. An entity id is a pool slot,
     * and a slot is reused: between two frames — 15 (or 10) in 25 ticks — a bullet
     * can die and a new one take its slot. Matched by id alone, the new bullet inherited the
     * old one's handle and was never created, so the client went on drawing the old bullet
     * along its old path. Measured: wrong by the tenth frame of a busy room.
     */
    private final short[] handleGeneration = new short[Wire.MAX_HANDLES];
    private final int[] handleMark = new int[Wire.MAX_HANDLES];

    private final int[] pendingX = new int[Wire.MAX_HANDLES];
    private final int[] pendingY = new int[Wire.MAX_HANDLES];
    private final int[] pendingAngle = new int[Wire.MAX_HANDLES];
    private final int[] pendingHp = new int[Wire.MAX_HANDLES];
    private final int[] pendingLevel = new int[Wire.MAX_HANDLES];
    private final int[] pendingClass = new int[Wire.MAX_HANDLES];
    private final int[] pendingFlags = new int[Wire.MAX_HANDLES];
    private final int[] pendingTick = new int[Wire.MAX_HANDLES];

    private final int[] createdTick = new int[Wire.MAX_HANDLES];

    private final int[] freeHandles = new int[Wire.MAX_HANDLES];
    private int freeTop;

    /**
     * The seq of the input that drove this client's tank at the last tick it was driven, and
     * the one this client was last told. Their difference is the snapshot's
     * {@code inputSeqDelta}: without it a client cannot tell which of its own inputs the
     * server's position already includes, so it cannot replay the rest on top (P-9).
     */
    private int inputSeqApplied;
    private int inputSeqSent;
    private int inputTicks;
    private float toldAccel = Float.NaN;           // NaN equals nothing: the first is always told
    private float toldRadius = Float.NaN;

    private int lastAckedTick;
    private int lastSentTick;
    private int viewOriginX, viewOriginY;      // quantised, as last sent

    public ClientView(int selfId, int worldCapacity, float viewWidth, float viewHeight, int entityBudget) {
        this.selfId = selfId;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        // Each section's count is written into a one-byte slot, so no section may hold more
        // than 127 entries (P-11). A budget above that used to be accepted, and the first
        // crowded frame threw on the room thread.
        this.entityBudget = Math.min(entityBudget, MAX_ENTITY_BUDGET);
        this.entityHandle = new short[worldCapacity];
        for (int h = Wire.MAX_HANDLES - 1; h >= FIRST_SHARED_HANDLE; h--) {
            freeHandles[freeTop++] = h;        // hand out low handles first
        }
        Arrays.fill(handleEntity, -1);
    }

    public int lastAckedTick() {
        return lastAckedTick;
    }

    public int lastSentTick() {
        return lastSentTick;
    }

    void setLastSentTick(int tick) {
        lastSentTick = tick;
    }

    public int handleOf(int entityId) {
        return entityHandle[entityId];
    }

    public int entityOf(int handle) {
        return handleEntity[handle];
    }

    /**
     * The handle this client knows an entity by, or {@link #NO_HANDLE}: not a handle it holds for
     * the slot's earlier occupant, which a client sent no snapshots, backgrounded, keeps (P-13).
     */
    int heldHandle(int entityId, short generation) {
        int h = entityHandle[entityId];
        return h != NO_HANDLE && holds(h, generation) ? h : NO_HANDLE;
    }

    /** Whether {@code h} still stands for this incarnation of the entity in its slot. */
    boolean holds(int h, short generation) {
        return handleGeneration[h] == generation;
    }

    int allocateHandle(int entityId, short generation) {
        if (freeTop == 0) {
            return NO_HANDLE;                  // budget should prevent this
        }
        return assign(freeHandles[--freeTop], entityId, generation);
    }

    /**
     * {@link Wire#SELF_HANDLE} for the client's own tank, always.
     *
     * After a respawn the handle is still held by the tank that died — released this frame,
     * or earlier and not yet confirmed. Taking it back at once is safe although other handles
     * wait for the client's confirmation: the remove and this create travel in the same
     * frame, the client applies removes first, and so there is no moment at which it could
     * mistake one tank for the other.
     */
    int allocateSelfHandle(int entityId, short generation) {
        int h = Wire.SELF_HANDLE;
        if (handleEntity[h] >= 0) {
            return NO_HANDLE;                  // only the own tank is given it, and it has none
        }
        return assign(h, entityId, generation);
    }

    private int assign(int h, int entityId, short generation) {
        handleEntity[h] = entityId;
        handleGeneration[h] = generation;
        entityHandle[entityId] = (short) h;
        pendingTick[h] = 0;                    // also cancels a pending release of this handle
        return h;
    }

    /** Records the seq of the input the room applied this tick. Room thread only. */
    void inputApplied(int seq) {
        int applied = seq & ClientMessage.INPUT_SEQ_MASK;
        inputTicks = applied == inputSeqApplied ? Math.min(MAX_INPUT_TICKS, inputTicks + 1) : 1;
        inputSeqApplied = applied;
    }

    /**
     * How far the applied seq has moved since the client was last told, as it is written;
     * and from now on, what the client has been told.
     *
     * Modulo 2^24, so it is never negative: the seq wraps, and a client that sends seqs out
     * of order gets a large forward step it can undo itself, rather than a negative number
     * in an unsigned field — which a varint writes as ten bytes, if at all.
     */
    int takeInputSeqDelta() {
        int delta = (inputSeqApplied - inputSeqSent) & ClientMessage.INPUT_SEQ_MASK;
        inputSeqSent = inputSeqApplied;
        return delta;
    }

    /**
     * The ticks the input last applied has driven the tank, through the last tick: where in the
     * input's span a frame's tick fell, which the echo alone does not say (02 §9, D-62). 0 before
     * any input; a byte on the wire.
     */
    int inputTicks() {
        return inputTicks;
    }

    /**
     * Whether the own tank's motion rule is not what this view was last told, and from now on,
     * what it has been told. True for a view's first, as nothing has been told yet.
     */
    boolean motionRuleChanged(float accel, float radius) {
        if (accel == toldAccel && radius == toldRadius) {
            return false;
        }
        toldAccel = accel;
        toldRadius = radius;
        return true;
    }

    /** The player's tank died and a new one took its place. Everything else carries over. */
    public void respawnAs(int entityId) {
        selfId = entityId;
    }

    /**
     * Frees a handle. It is not reusable until the removal is acknowledged: a client that
     * has not yet seen the remove would apply a new entity's updates to the old one.
     */
    void releaseHandle(int handle, int tick) {
        int entityId = handleEntity[handle];
        if (entityId >= 0) {
            entityHandle[entityId] = NO_HANDLE;
        }
        handleEntity[handle] = -1;
        pendingTick[handle] = tick;
        pendingX[handle] = Integer.MIN_VALUE;  // marks "pending release"
    }

    int createdTick(int handle) {
        return createdTick[handle];
    }

    void setCreatedTick(int handle, int tick) {
        createdTick[handle] = tick;
    }

    void mark(int handle, int tick) {
        handleMark[handle] = tick;
    }

    boolean isMarked(int handle, int tick) {
        return handleMark[handle] == tick;
    }

    boolean inUse(int handle) {
        return handleEntity[handle] >= 0;
    }

    // ---- baseline ---------------------------------------------------------------------

    /**
     * The state last *sent* for a handle, which is what a position delta is measured from:
     * TCP delivers every frame exactly once and in order, so what was sent is what the client
     * will hold. The acknowledgement ({@link #acknowledge}) decides only when a removed handle
     * may be reused and whether a create has arrived.
     */
    int sentX(int h) {
        return pendingX[h];
    }

    int sentY(int h) {
        return pendingY[h];
    }

    int sentAngle(int h) {
        return pendingAngle[h];
    }

    int sentHp(int h) {
        return pendingHp[h];
    }

    /** A tank's level as last sent; 0 for anything that is not a tank. */
    int sentLevel(int h) {
        return pendingLevel[h];
    }

    /** A tank's class as last sent; 0, Basic, for anything that is not a tank. */
    int sentClass(int h) {
        return pendingClass[h];
    }

    /** A tank's flags as last sent; 0 after its create, which has no field for them. */
    int sentFlags(int h) {
        return pendingFlags[h];
    }

    /** The tick a handle's state was last written, or 0 if it has never been sent. */
    int sentTick(int h) {
        return pendingTick[h];
    }

    /**
     * Whether the client has confirmed the frame that created {@code h}. Only that: this used
     * to wait for an ack at or past the handle's latest write, which for anything changing in
     * consecutive frames never came, and its create was sent again every fifteen ticks.
     */
    boolean createConfirmed(int h) {
        return lastAckedTick >= createdTick[h];
    }

    /** The acknowledged tick when last noted, and the frame's tick at which it last moved. */
    private int ackNoted = Integer.MIN_VALUE;
    private int ackMovedAt;

    /**
     * Notes, once a frame, whether the acknowledgement has moved since the last frame: how a
     * frame tells a client that acknowledges slowly from one that has stopped.
     */
    void noteAcknowledgement(int tick) {
        if (lastAckedTick != ackNoted) {
            ackNoted = lastAckedTick;
            ackMovedAt = tick;
        }
    }

    /** Frames' ticks since the acknowledgement last moved. */
    int ticksSinceAcknowledgementMoved(int tick) {
        return tick - ackMovedAt;
    }

    void recordPending(int h, int x, int y, int angle, int hp, int level, int classId, int flags,
                       int tick) {
        pendingX[h] = x;
        pendingY[h] = y;
        pendingAngle[h] = angle;
        pendingHp[h] = hp;
        pendingLevel[h] = level;
        pendingClass[h] = classId;
        pendingFlags[h] = flags;
        pendingTick[h] = tick;
    }

    /** Records what the client has confirmed, and releases handles whose removal it has seen. */
    public void acknowledge(int tick) {
        if (tick <= lastAckedTick) {
            return;                            // duplicate or reordered ack
        }
        lastAckedTick = tick;
        for (int h = FIRST_HANDLE; h < Wire.MAX_HANDLES; h++) {
            if (pendingTick[h] == 0 || pendingTick[h] > tick) {
                continue;
            }
            if (pendingX[h] == Integer.MIN_VALUE && handleEntity[h] < 0) {
                if (h != Wire.SELF_HANDLE) {
                    freeHandles[freeTop++] = h; // the remove is confirmed; reuse is now safe
                }
                pendingTick[h] = 0;
            }
        }
    }

    int viewOriginX() {
        return viewOriginX;
    }

    int viewOriginY() {
        return viewOriginY;
    }

    void setViewOrigin(int x, int y) {
        viewOriginX = x;
        viewOriginY = y;
    }
}
