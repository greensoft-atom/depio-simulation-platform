package com.backend.protocol;

/**
 * Client → server message types and the packing used to hand an input from a Netty thread
 * to a room thread (docs detailed-design/02-networking.md §3).
 *
 * Inputs coalesce per tick anyway — the room keeps the last one — so they are not queued.
 * A whole input fits in a {@code long}, which one atomic write publishes as a unit. That
 * matters: writing move, aim and sequence as separate fields would let a room thread read
 * the aim from one packet and the movement from another.
 */
public final class ClientMessage {

    public static final int JOIN = 1;
    public static final int INPUT = 2;
    public static final int UPGRADE_STAT = 3;
    public static final int CHOOSE_CLASS = 4;
    public static final int RESPAWN = 5;
    public static final int PHRASE = 6;
    public static final int PING = 7;
    public static final int LIFECYCLE = 8;
    public static final int LEAVE = 9;
    /**
     * In place of Join, after a lost connection: {@code u8 protocolVersion, string resumeSecret,
     * [u8 profile]}, the secret from the last Welcome (02 §10).
     */
    public static final int RESUME = 10;
    /** A sandbox's power (01 §8.10): {@code u8 action}, {@code varint value}; dropped in any other room. */
    public static final int SANDBOX = 11;
    /** Rebuilds the player's tank at the level the value names, 1 to 45. */
    public static final int SANDBOX_LEVEL = 1;
    /** Summons a Guardian into the room, while none is alive; the value is 0. */
    public static final int SANDBOX_GUARDIAN = 2;

    // Lifecycle states, for a phone that has been backgrounded.
    public static final int LIFECYCLE_FOREGROUND = 0;
    public static final int LIFECYCLE_BACKGROUND = 1;

    // Input flag bits.
    public static final int FLAG_FIRE = 1;
    public static final int FLAG_AUTOFIRE = 2;
    public static final int FLAG_AUTOSPIN = 4;
    /** Held: a class with a zoom sees ahead along its aim (01 §4). */
    public static final int FLAG_ZOOM = 8;

    // Movement bits.
    public static final int MOVE_UP = 1;
    public static final int MOVE_DOWN = 2;
    public static final int MOVE_LEFT = 4;
    public static final int MOVE_RIGHT = 8;

    /**
     * An input's {@code seq} is a 24-bit counter that wraps: at 20 inputs a second that is
     * nine days, which an open match on a server that is never restarted can reach.
     * Everything that compares or subtracts seqs does it modulo 2^24.
     */
    public static final int INPUT_SEQ_MASK = 0xFFFFFF;

    /** Layout: seq(24) | aim(16) | move(8) | flags(8). Fits in 56 bits, so one atomic long. */
    public static long packInput(int seq, int aim, int moveMask, int flags) {
        return ((long) (seq & INPUT_SEQ_MASK) << 32)
                | ((long) (aim & 0xFFFF) << 16)
                | ((long) (moveMask & 0xFF) << 8)
                | (flags & 0xFF);
    }

    public static int inputSeq(long packed) {
        return (int) ((packed >>> 32) & INPUT_SEQ_MASK);
    }

    public static int inputAim(long packed) {
        return (int) ((packed >>> 16) & 0xFFFF);
    }

    public static int inputMove(long packed) {
        return (int) ((packed >>> 8) & 0xFF);
    }

    public static int inputFlags(long packed) {
        return (int) (packed & 0xFF);
    }

    /** Aim on the wire is u16 over a full turn; this returns radians in [-π, π). */
    public static float aimToRadians(int aim) {
        return (float) (aim / 65_536.0 * 2 * Math.PI - Math.PI);
    }

    private ClientMessage() {
    }
}
