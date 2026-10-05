package com.backend.sim;

/**
 * One simulated object. Pooled and reused: {@link World} owns a fixed array of these and
 * hands out slot indices, so the tick loop never allocates.
 *
 * Fields are primitives and public on purpose. This is the hot data of the whole system,
 * and accessor calls plus boxing are exactly what docs detailed-design/07 §2 forbids.
 */
public final class Entity {

    // ---- kinds ----------------------------------------------------------------------
    public static final byte KIND_TANK = 0;
    public static final byte KIND_BULLET = 1;
    public static final byte KIND_SHAPE = 2;

    // ---- wire classification (docs detailed-design/01, D-9) -------------------------
    public static final byte WIRE_TANK = 0;       // streamed, client interpolates
    public static final byte WIRE_PREDICTED = 1;  // create/destroy only, client extrapolates
    public static final byte WIRE_STATIC = 2;     // shapes: created, updated as they drift, destroyed
    public static final byte WIRE_UNIT = 3;       // traps and drones: not predictable, updated as a tank is

    // identity
    public int id;                 // slot index in World.entities, stable while alive
    public short generation;       // bumps on reuse, so a stale id is detectable
    public byte kind;
    public byte wireClass;
    /** Which variety within the kind: for a shape, its {@link ShapeTable.Type#id()}. */
    public byte subtype;
    public byte team;
    public int ownerId = -1;       // bullets: the tank that fired; otherwise -1

    /**
     * The shooter's {@link #generation} at the moment of firing, so the slot can be checked
     * before anything is credited to whoever holds it now.
     *
     * {@link #playerTag} answers "which player gets the kill" and cannot answer this one:
     * every bot shares tag 0, so a bot's bullet landing after its shooter died would credit
     * experience to whichever bot inherited the slot. A generation is per slot and bumps on
     * every reuse, so it distinguishes them.
     */
    public short ownerGeneration;
    public boolean alive;

    /**
     * Which player this belongs to, or 0 for none. Tanks get one when a player spawns;
     * bullets copy their shooter's at the moment they are fired.
     *
     * Deliberately not {@link #ownerId}, which is a pool slot and gets reused. A bullet
     * outlives its shooter routinely — it is in flight when they die — and by the time it
     * lands, that slot may hold someone else's tank. Crediting the kill by slot would hand
     * it to whoever inherited the slot. Tags come from a counter that never repeats within
     * a room, so a stale tag matches nobody instead of matching the wrong player.
     */
    public long playerTag;
    /**
     * Its player's display name, UTF-8, for a tank's create (02 §4, D-52); null for a tank nobody
     * plays. Set by the arena with the tank; forgotten with the slot.
     */
    public byte[] name;

    // physics
    public float x, y, vx, vy, radius, angle, mass;

    /**
     * Player intent, written by the room thread from the client's last input. Kept as a
     * decoded direction rather than a wire bitmask so the simulation does not depend on
     * the protocol module.
     */
    public boolean playerControlled;
    public float moveX, moveY;     // unit-ish direction, already normalised for diagonals
    public float aimAngle;
    public boolean wantsFire;
    /**
     * Whether the trigger is held, as the latest input says: what a drone attacks by (01 §4).
     * Not {@link #wantsFire}, which a shot clears and so says nothing about the next tick.
     */
    public boolean attacking;
    /** Whether zoom is held, as the latest input says (01 §4, "The Predator's zoom"). */
    public boolean zooming;
    /**
     * A tank nobody drives that hunts (01 §8.5, co-op's waves): it goes for the nearest tank of
     * another team a player drives, where the benchmark's wander.
     */
    public boolean hunts;

    /** A tank that drives nowhere and no knock moves: a dominator (01 §8.7). */
    public boolean anchored;

    /** A tank a player's lethal blow captures for their team instead of killing (01 §8.7). */
    public boolean captures;
    /**
     * Tanks only: how far from the tank its player's view is centred, worked out each tick from
     * its class's zoom while zoom is held; 0 otherwise. The encoder centres the view there.
     */
    public float viewShiftX, viewShiftY;

    // combat
    public float hp, maxHp, damage;
    public int lifetimeTicks;      // bullets only; -1 means no expiry
    public int reloadTicks;        // tanks only
    /**
     * Tanks only: the volley in progress (01 §4). The barrels still to fire, one bit each in
     * the order of the class's list; the tick it began; and the reload it began with, which
     * a barrel's delay is a fraction of. Zero pending is no volley.
     */
    public int volleyPending, volleyStartTick, volleyReload;
    /** Tanks only: the volley in progress was begun without the trigger, by drones (01 §4). */
    public boolean selfVolley;
    /**
     * Tanks only: the last tick it moved or held the trigger, and whether, its class hiding, it
     * has been still long enough since to be left out of everyone else's view (D-23).
     */
    public int revealedTick;
    public boolean hidden;
    public int lastDamagedTick;    // tanks only: when it last lost health to anything but itself
    public int protectedUntilTick; // tanks only: safe, and harmless, before this tick (01 §7)

    /** A tank still in its spawn protection: it takes no damage, deals none and cannot shoot. */
    public boolean protectedAt(int tick) {
        return tick < protectedUntilTick;
    }

    void reset() {
        generation++;
        subtype = 0;
        ownerId = -1;
        ownerGeneration = 0;
        playerTag = 0;
        name = null;
        team = 0;                   // a shape landing in a tank's old slot is on no team
        vx = vy = angle = 0f;
        protectedUntilTick = 0;
        playerControlled = false;
        moveX = moveY = aimAngle = 0f;
        wantsFire = false;
        attacking = false;
        zooming = false;
        hunts = false;
        anchored = false;
        captures = false;
        viewShiftX = viewShiftY = 0f;
        lifetimeTicks = -1;
        reloadTicks = 0;
        volleyPending = volleyStartTick = volleyReload = 0;
        selfVolley = false;
        revealedTick = 0;
        hidden = false;
        alive = false;
    }
}
