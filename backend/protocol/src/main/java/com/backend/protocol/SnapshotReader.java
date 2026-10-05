package com.backend.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes one snapshot frame (docs detailed-design/02-networking.md §4).
 *
 * <h2>Why this exists</h2>
 *
 * Until it did, <em>nothing decoded what the server sends</em>. The golden vectors were
 * produced by a second, hand-written codec; the arena's own tests asserted on encoder-side
 * state — byte lengths and handle tables — and never parsed a byte. Six defects in the
 * format survived in that gap, including one that placed every tank wrongly for every
 * player. This class closes it: pair it with {@link ClientWorld} and the server's output has
 * to mean something.
 *
 * <h2>Stateless on purpose</h2>
 *
 * A frame is decoded into exactly what it says, with no memory of the last one. Applying it
 * — accumulating positions, holding a handle table, expiring predicted entities — is
 * {@link ClientWorld}'s job. Splitting them means the format can be tested without a world,
 * and the world can be tested without bytes.
 *
 * <h2>Reads every field the format defines</h2>
 *
 * Including the update field the arena does not currently send, TEAM (CLASS it does, on a promotion). A
 * decoder that only handled what today's encoder emits would desynchronise the first time one was switched
 * on, and the mask exists precisely so that it can be.
 */
public final class SnapshotReader {

    /** A create: an entity the client has not seen before, in full. */
    public record Create(int handle, int kind, int x, int y,
                         int angle, int hp, int classId, int team, int level, String name,
                         int heading, int speed, int spawnTickOffset, int lifetimeTicks,
                         int ownerHandle,
                         int subtype, int spawnAngle, int radius) {

        static Create tank(int handle, int x, int y, int angle, int hp, int classId,
                           int team, int level, String name) {
            return new Create(handle, Wire.KIND_TANK, x, y, angle, hp, classId, team, level,
                    name, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        static Create predicted(int handle, int x, int y, int heading, int speed,
                                int spawnTickOffset, int lifetimeTicks, int ownerHandle, int radius) {
            return new Create(handle, Wire.KIND_PREDICTED, x, y, 0, 0, 0, 0, 0, "",
                    heading, speed, spawnTickOffset, lifetimeTicks, ownerHandle, 0, 0, radius);
        }

        static Create statik(int handle, int x, int y, int subtype, int spawnAngle) {
            return new Create(handle, Wire.KIND_STATIC, x, y, 0, 0, 0, 0, 0, "",
                    0, 0, 0, 0, 0, subtype, spawnAngle, 0);
        }

        static Create unit(int handle, int x, int y, int angle, int hp, int subtype, int team,
                           int ownerHandle, int radius) {
            return new Create(handle, Wire.KIND_UNIT, x, y, angle, hp, 0, team, 0, "",
                    0, 0, 0, 0, ownerHandle, subtype, 0, radius);
        }
    }

    /**
     * An update: only the fields whose bits are set in {@code mask} are present.
     *
     * {@code dx}/{@code dy} are a <em>delta</em>; everything else is absolute. Which
     * baseline the delta is against is the format's single most important detail — see
     * {@link ClientWorld}.
     */
    public record Update(int handle, int mask, long dx, long dy,
                         int angle, int hp, int level, int classId, int team, int flags) {

        public boolean has(int field) {
            return (mask & field) != 0;
        }
    }

    /** An event, kept as bytes so an unknown type can be carried past without being read. */
    public record Event(int type, byte[] payload) { }

    /** {@link Wire#EVT_MOTION}'s fields, as sent: the velocity in 1/{@link Wire#VELOCITY_SCALE} of a unit a tick. */
    public record Motion(int inputTicks, int vx, int vy) { }

    /** {@link Wire#EVT_MOTION_RULE}'s fields: the server's own floats. */
    public record MotionRule(float accel, float radius) { }

    public static Motion motion(Event e) {
        WireReader r = new WireReader(e.payload());
        return new Motion(r.u8(), (int) r.svarint(), (int) r.svarint());
    }

    /** {@link Wire#EVT_SKIN}'s fields: the tank's handle in this view, and its skin's number. */
    public record Skin(int handle, int skin) { }

    public static Skin skin(Event e) {
        WireReader r = new WireReader(e.payload());
        return new Skin(r.u8(), r.u8());
    }

    public static MotionRule motionRule(Event e) {
        WireReader r = new WireReader(e.payload());
        return new MotionRule(Float.intBitsToFloat(r.u32()), Float.intBitsToFloat(r.u32()));
    }

    /** One decoded frame. */
    public record Frame(long tickDelta, long inputSeqDelta, long originDX, long originDY,
                        int[] removes, List<Create> creates, List<Update> updates,
                        List<Event> events) { }

    private final WireReader in = new WireReader();

    /** @throws IllegalStateException if the frame is truncated, mistyped or has trailing bytes. */
    public Frame decode(byte[] frame, int offset, int length) {
        in.reset(frame, offset, length);

        int type = in.u8();
        if (type != Wire.MSG_SNAPSHOT) {
            throw new IllegalStateException("not a snapshot: message type " + type);
        }
        long tickDelta = in.varint();
        long inputSeqDelta = in.varint();
        long originDX = in.svarint();
        long originDY = in.svarint();

        int[] removes = new int[in.lengthOf(in.varint())];
        for (int i = 0; i < removes.length; i++) {
            removes[i] = in.u8();
        }

        int createCount = in.lengthOf(in.varint());
        List<Create> creates = new ArrayList<>(createCount);
        for (int i = 0; i < createCount; i++) {
            creates.add(readCreate());
        }

        int updateCount = in.lengthOf(in.varint());
        List<Update> updates = new ArrayList<>(updateCount);
        for (int i = 0; i < updateCount; i++) {
            updates.add(readUpdate());
        }

        int eventCount = in.lengthOf(in.varint());
        List<Event> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            int eventType = in.u8();
            // A byte length, not a count of values. It is what lets this loop step over a
            // type it has never heard of, and reading it as anything else desynchronises
            // every frame that carries an event.
            int payloadBytes = in.lengthOf(in.varint());
            byte[] payload = new byte[payloadBytes];
            for (int b = 0; b < payloadBytes; b++) {
                payload[b] = (byte) in.u8();
            }
            events.add(new Event(eventType, payload));
        }

        if (!in.done()) {
            // Not pedantry: trailing bytes mean this decoder and the encoder disagree about
            // some field's width, and the next frame would be read from the wrong place.
            throw new IllegalStateException("trailing bytes: read " + (in.position() - offset)
                    + " of " + length);
        }
        return new Frame(tickDelta, inputSeqDelta, originDX, originDY,
                removes, creates, updates, events);
    }

    public Frame decode(byte[] frame) {
        return decode(frame, 0, frame.length);
    }

    private Create readCreate() {
        int handle = in.u8();
        int kind = in.u8();
        int x = in.i16();
        int y = in.i16();
        return switch (kind) {
            case Wire.KIND_TANK -> Create.tank(handle, x, y,
                    in.u8(), in.u8(), in.u8(), in.u8(), in.u8(), in.str());
            case Wire.KIND_PREDICTED -> Create.predicted(handle, x, y,
                    in.u16(), in.u8(), in.u8(), in.u8(), in.u8(), in.u8());
            case Wire.KIND_STATIC -> Create.statik(handle, x, y, in.u8(), in.u8());
            case Wire.KIND_UNIT -> Create.unit(handle, x, y,
                    in.u8(), in.u8(), in.u8(), in.u8(), in.u8(), in.u8());
            default -> throw new IllegalStateException("unknown entity kind " + kind);
        };
    }

    private Update readUpdate() {
        int handle = in.u8();
        int mask = in.u8();
        long dx = 0;
        long dy = 0;
        if ((mask & Wire.F_POS) != 0) {
            dx = in.svarint();
            dy = in.svarint();
        }
        int angle = (mask & Wire.F_ANGLE) != 0 ? in.u8() : 0;
        int hp = (mask & Wire.F_HP) != 0 ? in.u8() : 0;
        int level = (mask & Wire.F_LEVEL) != 0 ? in.u8() : 0;
        int classId = (mask & Wire.F_CLASS) != 0 ? in.u8() : 0;
        int team = (mask & Wire.F_TEAM) != 0 ? in.u8() : 0;
        int flags = (mask & Wire.F_FLAGS) != 0 ? in.u8() : 0;
        return new Update(handle, mask, dx, dy, angle, hp, level, classId, team, flags);
    }
}
