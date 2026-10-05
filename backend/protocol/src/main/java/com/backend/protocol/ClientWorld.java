package com.backend.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * What a client holds between snapshots (docs detailed-design/02-networking.md §4–§6).
 *
 * This is the reference implementation of the receiving half of the match protocol: a handle
 * table, absolute positions accumulated from deltas, and predicted entities that carry their
 * own death tick, for the caller to expire with {@link #expirePredicted} between frames. The
 * Unity client is a port of this, so it is written to be read rather than to be fast.
 *
 * <h2>Deltas accumulate, because the transport is ordered</h2>
 *
 * A position update carries the change since <em>the last snapshot the server sent</em>, and
 * this applies it to the value it already holds. That is only correct on a transport that
 * delivers every frame exactly once and in order — which raw TCP does
 * ([D-5](../../../../../../../../docs/architecture/03-decision-log.md)).
 *
 * The alternative, deltas against the last frame the client <em>acknowledged</em>, survives
 * loss and would be needed on UDP. It also requires the snapshot to say which baseline it is
 * against, and the client to keep that baseline per entity. The server used to compute
 * deltas that way while sending no baseline tick, so the frame was not decodable: the client
 * accumulated a delta measured from somewhere else and every entity drifted by the whole
 * un-acknowledged run. Positions are all a client has; this is the half of the protocol
 * least able to afford an ambiguity.
 *
 * <h2>World space, not view space</h2>
 *
 * A create carries a position relative to the view origin, so that it fits in an {@code i16}
 * however large the map is. Every update after it carries a world-space delta. This holds
 * world space throughout, converting once on the create: an entity that receives no updates —
 * a shape sitting still — would otherwise keep its create-time offset while the origin moved
 * beneath it, and appear glued to the camera.
 *
 * <h2>Not a renderer</h2>
 *
 * Positions are quantised: 1/{@link Wire#POS_SCALE} of a world unit. Interpolating between two
 * snapshots for display is the client's business and deliberately absent.
 */
public final class ClientWorld {

    /** One entity as the client understands it. Mutable; there is one per handle, reused. */
    public static final class Entity {
        public boolean alive;
        public int kind;
        /** Quantised world position — 1/{@link Wire#POS_SCALE} of a unit, absolute. */
        public int x, y;
        public int angle, hp, level, classId, team, flags;
        public String name = "";
        /** Predicted entities only: heading, speed in half units a tick, and when it dies. */
        public int heading, speed;
        /** Predicted entities and units: their radius in world units (protocols 3 and 4). */
        public int radius;
        /** Predicted entities and units: whose they are. */
        public int ownerHandle;
        public int deathTick;

        /**
         * Where and when a predicted entity started.
         *
         * Its position is recomputed from here every frame rather than nudged along each
         * tick. Nudging rounds once per tick and the error accumulates: measured at over a
         * world unit after twelve ticks, which for a bullet is the difference between a hit
         * and a miss on screen. From a fixed origin there is exactly one rounding, ever.
         */
        int baseX, baseY;
        long baseTick;

        void clear() {
            alive = false;
            name = "";
            x = y = angle = hp = level = classId = team = flags = 0;
            heading = speed = radius = ownerHandle = 0;
            deathTick = 0;
            baseX = baseY = 0;
            baseTick = 0;
        }
    }

    private final Entity[] entities = new Entity[Wire.MAX_HANDLES];
    private final SnapshotReader reader = new SnapshotReader();

    private long serverTick;
    private long lastProcessedInputSeq;
    private int originX, originY;

    /** Every event from the last frame applied, in order. */
    private final List<SnapshotReader.Event> events = new ArrayList<>();

    public ClientWorld() {
        for (int h = 0; h < entities.length; h++) {
            entities[h] = new Entity();
        }
    }

    public long serverTick() {
        return serverTick;
    }

    public long lastProcessedInputSeq() {
        return lastProcessedInputSeq;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    /** @return the entity behind a handle. Check {@link Entity#alive} before trusting it. */
    public Entity entity(int handle) {
        return entities[handle];
    }

    public int aliveCount() {
        int n = 0;
        for (Entity e : entities) {
            if (e.alive) {
                n++;
            }
        }
        return n;
    }

    public List<SnapshotReader.Event> events() {
        return events;
    }

    /** Applies one frame. @return the tick it advanced to. */
    public long apply(byte[] frame, int offset, int length) {
        SnapshotReader.Frame f = reader.decode(frame, offset, length);
        events.clear();

        serverTick += f.tickDelta();
        // Modulo 2^24, as the server computes it: the delta is never negative, and the seq
        // wraps rather than growing.
        lastProcessedInputSeq = (lastProcessedInputSeq + f.inputSeqDelta()) & ClientMessage.INPUT_SEQ_MASK;
        originX += (int) f.originDX();
        originY += (int) f.originDY();

        // Removes first: the server frees a handle only once its removal is acknowledged, so
        // a handle can be removed and re-created in the same frame without ambiguity.
        for (int handle : f.removes()) {
            entities[handle].clear();
        }

        for (SnapshotReader.Create c : f.creates()) {
            Entity e = entities[c.handle()];
            e.clear();
            e.alive = true;
            e.kind = c.kind();
            // The wire carries positions relative to this frame's view origin; everything
            // here is world space. Converting once, on the way in, is what lets a static
            // entity stay where it was put — see the note on updates below.
            e.x = c.x() + originX;
            e.y = c.y() + originY;
            switch (c.kind()) {
                case Wire.KIND_TANK -> {
                    e.angle = c.angle();
                    e.hp = c.hp();
                    e.classId = c.classId();
                    e.team = c.team();
                    e.level = c.level();
                    e.name = c.name();
                }
                case Wire.KIND_PREDICTED -> {
                    e.heading = c.heading();
                    e.speed = c.speed();
                    e.radius = c.radius();
                    e.ownerHandle = c.ownerHandle();
                    // pos is where it was spawnTickOffset ticks before this frame, and its
                    // lifetime counts from then (02 §4). This server always sends 0, but the
                    // field is defined, and the C# reader honours it.
                    e.baseX = e.x;
                    e.baseY = e.y;
                    e.baseTick = serverTick - c.spawnTickOffset();
                    // Nothing updates a predicted entity, so this is what hides it when its
                    // time is up, through expirePredicted, before the server's remove arrives.
                    e.deathTick = (int) e.baseTick + c.lifetimeTicks();
                }
                case Wire.KIND_UNIT -> {
                    // A trap or a drone, updated as a tank is (01 §4); its subtype where a
                    // shape's is.
                    e.angle = c.angle();
                    e.hp = c.hp();
                    e.classId = c.subtype();
                    e.team = c.team();
                    e.ownerHandle = c.ownerHandle();
                    e.radius = c.radius();
                }
                default -> {
                    e.classId = c.subtype();
                    e.angle = c.spawnAngle();
                }
            }
        }

        for (SnapshotReader.Update u : f.updates()) {
            Entity e = entities[u.handle()];
            if (!e.alive) {
                // An update for a handle this client does not hold means the two sides
                // disagree about the handle table, which is not survivable by guessing.
                throw new IllegalStateException("update for handle " + u.handle()
                        + ", which is not alive here");
            }
            if (u.has(Wire.F_POS)) {
                // A world-space delta: the create was relative to the view origin, every
                // update since is not. The camera moving changes nothing here, which is why
                // scenery stays where it was put.
                e.x += (int) u.dx();
                e.y += (int) u.dy();
            }
            if (u.has(Wire.F_ANGLE)) {
                e.angle = u.angle();
            }
            if (u.has(Wire.F_HP)) {
                e.hp = u.hp();
            }
            if (u.has(Wire.F_LEVEL)) {
                e.level = u.level();
            }
            if (u.has(Wire.F_CLASS)) {
                e.classId = u.classId();
            }
            if (u.has(Wire.F_TEAM)) {
                e.team = u.team();
            }
            if (u.has(Wire.F_FLAGS)) {
                e.flags = u.flags();
            }
        }

        // Last, so a create in this frame starts exactly where it was placed and everything
        // older has advanced to the same tick.
        extrapolatePredicted();

        events.addAll(f.events());
        return serverTick;
    }

    public long apply(byte[] frame) {
        return apply(frame, 0, frame.length);
    }

    /**
     * Places every predicted entity where its heading and speed say it should be by now.
     *
     * Recomputed from the create rather than stepped forward, so the only rounding is this
     * one. Straight lines only: anything that could turn or be knocked is not predictable and
     * is classed as a tank instead, which is what the wire class decides (D-9).
     */
    private void extrapolatePredicted() {
        for (Entity e : entities) {
            if (!e.alive || e.kind != Wire.KIND_PREDICTED) {
                continue;
            }
            float radians = ClientMessage.aimToRadians(e.heading);
            float travelled = Wire.speedOf(e.speed) * Wire.POS_SCALE
                    * (serverTick - e.baseTick);
            e.x = e.baseX + Math.round((float) Math.cos(radians) * travelled);
            e.y = e.baseY + Math.round((float) Math.sin(radians) * travelled);
        }
    }

    /** Drops predicted entities whose lifetime has run out. */
    public void expirePredicted() {
        for (Entity e : entities) {
            if (e.alive && e.kind == Wire.KIND_PREDICTED && serverTick >= e.deathTick) {
                e.clear();
            }
        }
    }
}
