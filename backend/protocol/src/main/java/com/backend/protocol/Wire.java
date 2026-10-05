package com.backend.protocol;

/**
 * Wire constants for the match protocol
 * (docs detailed-design/02-networking.md §3–§4).
 *
 * These values are a contract with the C# client. Changing one without regenerating the
 * golden vectors in protocol-spike/ is how the two sides drift apart.
 */
public final class Wire {

    /** Current protocol version, sent in Join and checked before anything is parsed. */
    public static final int VERSION = 4;

    // ---- server → client message types ------------------------------------------------
    public static final int MSG_WELCOME = 1;
    public static final int MSG_SNAPSHOT = 2;
    public static final int MSG_PONG = 3;
    public static final int MSG_KICK = 4;

    // ---- traffic profiles (02 §8) -------------------------------------------------------
    // An optional u8 at the end of Join or Resume: the most this client wants to be sent. Absent,
    // or a value this server does not know, means mobile, so a client from a later release
    // is served rather than refused. The server steps a client down from it when its link
    // cannot keep up, and back up to it, never above.
    public static final int PROFILE_MOBILE = 0;
    public static final int PROFILE_HIGH = 1;
    public static final int PROFILE_SAVER = 2;

    // ---- kick reasons -------------------------------------------------------------------
    // The client has to tell these apart to behave sensibly: a bad ticket means go back to
    // the lobby for a new one, a full room means re-queue, and a version mismatch means the
    // app needs updating. Without a reason all three look like a dropped connection, and a
    // phone on a flaky link would retry the one case that can never succeed.
    public static final int KICK_BAD_TICKET = 1;
    public static final int KICK_ROOM_FULL = 2;
    public static final int KICK_PROTOCOL_VERSION = 3;
    public static final int KICK_RATE_LIMIT = 4;
    public static final int KICK_INTERNAL = 5;
    /**
     * A match the matcher made is over (docs 04 §4): back to the lobby, where its result lands,
     * as after {@code Leave}. Not an error, and not a reason to reconnect.
     */
    public static final int KICK_MATCH_OVER = 6;
    /**
     * Removed by an operator: the room was closed, or the player taken out (docs 04 §10). Back to
     * the lobby. An older client reads a reason it does not know as 5, which ends in the same place.
     */
    public static final int KICK_REMOVED = 7;

    // ---- events inside a snapshot -------------------------------------------------------
    // Deaths and the like ride in the snapshot's event section rather than as their own
    // messages: at ~71 bytes of packet overhead, a second packet costs more than the event
    // it carries (docs detailed-design/02-networking.md §3).

    // Every event is {@code u8 type, varint byteLength, payload}. The length is what lets a
    // client skip a type it has never heard of; without it, the first event added after a
    // release desynchronises every older client mid-snapshot, and mobile clients cannot be
    // forced onto a new build at once.

    /** Payload: {@code varint score, u8 killerNameLength, bytes killerName}. Empty name = nobody. */
    public static final int EVT_DEATH = 1;

    /**
     * This client's own progression.
     *
     * Payload: {@code varint level, varint xp, varint xpForNextLevel, u8 unspentPoints,
     * u8[8] pointsPerStat}. Sent only to the player it describes, and only when it changes —
     * a level, a spent point, or at most once a second while experience is moving. Streaming
     * it every snapshot would cost about 225 B/s, some 8 % of a mobile client's downstream
     * budget (02 §4), to animate a progress bar.
     *
     * {@code xpForNextLevel} is 0 at the top of the curve, which is how a client knows to
     * draw a full bar rather than divide by zero.
     */
    public static final int EVT_STATS = 2;

    /**
     * Someone this client may hear said a phrase (01 §9, D-32): its team in a mode with teams,
     * otherwise a tank in its view.
     *
     * Payload: {@code u8 handle, varint phraseId, u8 nameLength, bytes name}. The handle is the
     * speaker's in this client's view, 0 when it holds none (a teammate out of sight, or a
     * speaker with no tank); the name is there because the snapshot never carries one.
     */
    public static final int EVT_PHRASE = 3;

    /**
     * A tank killed (01 §9, Q-34): to its killer in every room, and in a made match to every
     * player in it.
     *
     * Payload: {@code u8 nameLength, bytes killer, u8 nameLength, bytes victim}; a name is empty
     * for a tank that is nobody's, the arena's own or a player gone, and for a killer that is not
     * a tank's.
     */
    public static final int EVT_KILL = 4;

    /**
     * The own tank's motion, in every frame while it lives (02 §9, D-62): {@code u8 inputTicks}, the
     * ticks the input this frame echoes has driven it through this frame's tick, at most 255; then
     * {@code svarint vx, vy}, its velocity after that tick in 1/{@link #VELOCITY_SCALE} of a world
     * unit a tick. What lets a client replay its own inputs from where the server had it.
     */
    public static final int EVT_MOTION = 5;

    /**
     * The own tank's motion rule (02 §9, D-62): {@code f32 accel, f32 radius}, the server's own
     * floats, each as its bits in a big-endian u32. Sent when either is not what the view was last
     * told, so in a view's first frame.
     */
    public static final int EVT_MOTION_RULE = 6;

    /**
     * A tank's skin (04 §8, D-70): {@code u8 handle, u8 skin}, in the frame with the tank's create, for a
     * tank with a skin only. How it is drawn, nothing else; no new protocol version, as an older client
     * steps over an event.
     */
    public static final int EVT_SKIN = 7;

    /** A velocity on the wire is in 1/256 of a world unit a tick. */
    public static final int VELOCITY_SCALE = 256;

    /** A name longer than this is dropped rather than truncated, which could split a codepoint. */
    public static final int MAX_NAME_BYTES = 64;

    // ---- entity kinds on the wire (not simulation kinds) -------------------------------
    public static final int KIND_TANK = 0;
    public static final int KIND_PREDICTED = 1;
    public static final int KIND_STATIC = 2;
    /**
     * What a client cannot extrapolate and is not a tank: traps and drones (01 §4, "The second
     * tier"). Created with {@code pos, angle, hp, u8 subtype, u8 team, handle owner, u8 radius}
     * and updated as a tank is. Protocol 3; the radius since protocol 4, since a trap outlives the
     * owner its size could otherwise be looked up by.
     */
    public static final int KIND_UNIT = 3;
    public static final int UNIT_TRAP = 1;
    public static final int UNIT_DRONE = 2;
    /** A drone that shoots: the Factory's (01 §4). Its bullets are ordinary predicted creates. */
    public static final int UNIT_MINION = 3;
    /** Missiles that fire as they fly (01 §4): a Rocketeer's, and a Skimmer's, which turns. */
    public static final int UNIT_ROCKET = 4;
    public static final int UNIT_SKIMMER = 5;

    // ---- update field mask --------------------------------------------------------------
    public static final int F_POS = 1;
    public static final int F_ANGLE = 2;
    public static final int F_HP = 4;
    public static final int F_LEVEL = 8;
    public static final int F_CLASS = 16;
    public static final int F_TEAM = 32;
    public static final int F_FLAGS = 64;

    /**
     * A tank's {@code FLAGS} bit: in its spawn protection (01 §7), so it cannot be hurt and
     * cannot shoot. A create has no flags field, so a tank that arrives protected is told so
     * in the next frame's update, and another update clears it.
     */
    public static final int TANK_FLAG_PROTECTED = 1;

    /**
     * A tank's {@code FLAGS} bit: hidden (01 §4, D-23). Only its own player is told, since
     * everyone else is not sent the tank at all while it lasts.
     */
    public static final int TANK_FLAG_HIDDEN = 2;

    /** Positions travel as fixed point: world units × 4, so 0.25 of a unit. */
    public static final float POS_SCALE = 4f;

    /**
     * A predicted create's {@code speed}: how fast the bullet flies, in **half units a tick**.
     *
     * Exact, because a predicted entity is never updated: the client extrapolates it from a
     * heading and this, for its whole life, so a speed off by any amount draws it in the wrong
     * place. The simulation fires every bullet at a speed rounded to half a unit a tick, so
     * the byte holds it with nothing left to round, to 127.5 units a tick.
     *
     * Version 1 carried an index into an eight-entry table of the speeds the bullet-speed stat
     * could give; a class's barrel multiplies those, which a table cannot hold (01 §4).
     */
    public static int speedCode(float unitsPerTick) {
        return Math.max(0, Math.min(255, Math.round(unitsPerTick * 2f)));
    }

    /** @return the speed a code means, in world units a tick. */
    public static float speedOf(int code) {
        return code / 2f;
    }

    /** Handles are a byte: 256 values, 255 usable, since handle 0 is reserved. */
    public static final int MAX_HANDLES = 256;

    /**
     * The handle a client's own tank always has, and what {@code Welcome.yourHandle} carries.
     *
     * Guaranteed rather than assumed: the server reserves this handle for the client's own
     * tank and gives it to nothing else. (It used to rely on the own tank being encoded first,
     * which a tank within four units on a lower slot could beat.) Handle 0 is reserved to
     * mean "no handle".
     *
     * This used to be a literal 1 in the welcome while the encoder *excluded* the client's
     * own tank from its snapshot, so handle 1 belonged to the nearest other player. A client
     * looking itself up found an opponent, and had no way to learn its own health at all.
     */
    public static final int SELF_HANDLE = 1;

    public static int quantiseVelocity(float unitsPerTick) {
        return Math.round(unitsPerTick * VELOCITY_SCALE);
    }

    public static int quantisePos(float worldUnits) {
        return Math.round(worldUnits * POS_SCALE);
    }

    /** Heading as u16: the full turn in 65 536 steps, which is 0.3 px of drift over a bullet's life. */
    public static int quantiseHeading(float radians) {
        double normalised = (radians + Math.PI) / (2 * Math.PI);
        int steps = (int) Math.round(normalised * 65_536.0);
        return steps & 0xFFFF;
    }

    /** Angle as u8, used for tank facing where a degree and a half is invisible. */
    public static int quantiseAngle(float radians) {
        double normalised = (radians + Math.PI) / (2 * Math.PI);
        return (int) Math.round(normalised * 256.0) & 0xFF;
    }

    /**
     * Health as a u8 fraction of maximum.
     *
     * <h2>Zero is reserved for dead</h2>
     *
     * Any positive health encodes as at least 1. A tank on a sliver — 1 of 1 000, which
     * rounds to 0.255 — is still alive and still shooting, and a client drawing an empty bar
     * on it would be showing something that is not true. The cost is that 1 means "almost
     * nothing left" rather than exactly 1/255th, which is what a health bar wants anyway.
     */
    public static int quantiseHp(float hp, float maxHp) {
        if (maxHp <= 0f || hp <= 0f) {
            return 0;
        }
        int v = Math.round(hp / maxHp * 255f);
        return v <= 0 ? 1 : Math.min(v, 255);
    }

    private Wire() {
    }
}
