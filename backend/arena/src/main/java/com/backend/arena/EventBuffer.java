package com.backend.arena;

import java.nio.charset.StandardCharsets;

import com.backend.protocol.SnapshotWriter;
import com.backend.protocol.Wire;

/**
 * Events waiting to go out in one client's next snapshot.
 *
 * Pre-encoded as bytes rather than kept as objects, because the encoder's job is to splice
 * them in and nothing between here and the socket needs to read them back. One buffer per
 * connection, written and cleared on the room thread only.
 *
 * <h2>Framing: type, byte length, payload</h2>
 *
 * The length is what lets a client skip an event it has never heard of. Without it, the
 * first event type added after a release desynchronises every older client mid-snapshot —
 * and on mobile there is no forcing everyone onto the new build at once. The payload is
 * bytes rather than a list of numbers so that it can carry a name.
 */
final class EventBuffer {

    private final SnapshotWriter out = new SnapshotWriter(128);
    private final SnapshotWriter payload = new SnapshotWriter(96);
    private int count;

    /** @param killerName may be null or empty when nobody gets the credit. */
    void death(int score, String killerName) {
        payload.reset();
        payload.varint(score);
        name(killerName);
        emit(Wire.EVT_DEATH);
    }

    /**
     * A phrase this client may hear (01 §9): the speaker's handle in its view, 0 when it holds
     * none, and the speaker's name, which the snapshot never carries.
     */
    void phrase(int handle, int phraseId, String speakerName) {
        payload.reset();
        payload.u8(handle);
        payload.varint(phraseId);
        name(speakerName);
        emit(Wire.EVT_PHRASE);
    }

    /** A tank killed (01 §9): either name null or empty for one that is nobody's. */
    void kill(String killerName, String victimName) {
        payload.reset();
        name(killerName);
        name(victimName);
        emit(Wire.EVT_KILL);
    }

    private void name(String text) {
        byte[] name = text == null || text.isEmpty() ? null : text.getBytes(StandardCharsets.UTF_8);
        if (name == null || name.length > Wire.MAX_NAME_BYTES) {
            // Dropped rather than truncated: cutting UTF-8 at a byte boundary can split a
            // codepoint, and the client would render a broken character instead of a name.
            payload.u8(0);
        } else {
            payload.u8(name.length);
            payload.bytes(name, name.length);
        }
    }

    /**
     * This client's own level, experience and skill points.
     *
     * The points array goes out whole rather than as a delta. It is eight bytes, it is sent
     * rarely, and a client that misses one delta would show a wrong build until the next
     * level — which is exactly the kind of drift that is impossible to reproduce later.
     */
    void stats(int level, int xp, int xpForNextLevel, int unspentPoints, byte[] pointsPerStat) {
        payload.reset();
        payload.varint(level);
        payload.varint(xp);
        payload.varint(xpForNextLevel);
        payload.u8(unspentPoints);
        payload.bytes(pointsPerStat, pointsPerStat.length);
        emit(Wire.EVT_STATS);
    }

    /** The own tank's motion (02 §9, D-62): the ticks the echoed input has driven it, and its velocity. */
    void motion(int inputTicks, float vx, float vy) {
        payload.reset();
        payload.u8(inputTicks);
        payload.svarint(Wire.quantiseVelocity(vx));
        payload.svarint(Wire.quantiseVelocity(vy));
        emit(Wire.EVT_MOTION);
    }

    /** The own tank's motion rule (02 §9, D-62): its acceleration and radius, the room's own floats. */
    void motionRule(float accel, float radius) {
        payload.reset();
        payload.f32(accel);
        payload.f32(radius);
        emit(Wire.EVT_MOTION_RULE);
    }

    /** A tank's skin, with its create (04 §8, D-70): its handle in this view, and the skin's number. */
    void skin(int handle, int skin) {
        payload.reset();
        payload.u8(handle);
        payload.u8(skin);
        emit(Wire.EVT_SKIN);
    }

    private void emit(int type) {
        out.u8(type);
        out.varint(payload.length());
        out.bytes(payload.array(), payload.length());
        count++;
    }

    int count() {
        return count;
    }

    byte[] array() {
        return out.array();
    }

    int length() {
        return out.length();
    }

    void clear() {
        out.reset();
        count = 0;
    }
}
