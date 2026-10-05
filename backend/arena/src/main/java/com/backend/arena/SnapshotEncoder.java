package com.backend.arena;

import java.util.Arrays;

import com.backend.common.IntList;
import com.backend.protocol.SnapshotWriter;
import com.backend.protocol.Wire;
import com.backend.sim.Entity;
import com.backend.sim.World;

/**
 * Turns a world plus one client's view into snapshot bytes
 * (docs detailed-design/02-networking.md §4, §7).
 *
 * One instance per room thread, reused for every client: all scratch state lives here, so
 * encoding allocates nothing. It is deliberately not thread-safe.
 */
public final class SnapshotEncoder {

    /**
     * How long a create may go unconfirmed, while the client has acknowledged nothing new,
     * before it is sent again: 0.6 s.
     *
     * Under TCP this fires only for a client that has stopped acknowledging. Under UDP a lost
     * create would otherwise leave an entity that is never drawn. It used to fire every
     * fifteen ticks for anything that moved (see {@link ClientView#createConfirmed}), and, with
     * the create's own wait as its only clock, for everything in view on a link acknowledging
     * more than 0.6 s behind: the far or queued links traffic profiles are there to spare.
     */
    private static final int RECREATE_AFTER_TICKS = 15;

    /**
     * Before a client's first acknowledgement there is no progress to measure, only silence:
     * 2 s, longer than any round trip worth serving, rather than re-send the whole opening
     * burst to a client 0.6 s away.
     */
    private static final int RECREATE_BEFORE_FIRST_ACK_TICKS = 50;

    private final IntList candidates = new IntList(1024);
    private long[] ranked = new long[1024];
    private int rankedCount;

    // Priority classes, ordered: what a player must see first.
    private static final long PRIO_TANK = 0;
    private static final long PRIO_PREDICTED = 1;
    private static final long PRIO_STATIC = 2;

    /** For callers that have no events to deliver, such as the tick benchmark. */
    public int encode(World world, ClientView view, int tick, SnapshotWriter out) {
        return encode(world, view, tick, out, noEvents);
    }

    /** Always empty: every encode clears it, and nothing outside this class writes to it. */
    private final EventBuffer noEvents = new EventBuffer();

    /**
     * Encodes one snapshot, delivering {@code events} and clearing them.
     *
     * @return the entities written to the frame, creates plus updates
     */
    public int encode(World world, ClientView view, int tick, SnapshotWriter out,
                      EventBuffer events) {
        Entity self = world.entities[view.selfId];
        view.noteAcknowledgement(tick);
        out.reset();

        out.u8(Wire.MSG_SNAPSHOT);
        out.varint(Math.max(0, tick - view.lastSentTick()));
        out.varint(view.takeInputSeqDelta());

        // The view's centre: the tank, or ahead of it while a Predator zooms (01 §4).
        int originX = Wire.quantisePos(self.x + self.viewShiftX);
        int originY = Wire.quantisePos(self.y + self.viewShiftY);
        out.svarint(originX - view.viewOriginX());
        out.svarint(originY - view.viewOriginY());

        select(world, self, view);

        // Mark everything still visible, so anything left unmarked is a removal. A handle
        // whose slot now holds a different incarnation is left unmarked too: removed here
        // and the newcomer created below, rather than passed off as the entity it replaced.
        for (int i = 0; i < rankedCount; i++) {
            int entityId = (int) (ranked[i] & 0xFFFFFF);
            int h = view.handleOf(entityId);
            if (h != ClientView.NO_HANDLE && view.holds(h, world.entities[entityId].generation)) {
                view.mark(h, tick);
            }
        }

        writeRemoves(view, tick, out);
        int created = writeCreates(world, view, tick, originX, originY, out, events);
        int updated = writeUpdates(world, view, tick, originX, originY, out);

        out.varint(events.count());
        if (events.length() > 0) {
            out.bytes(events.array(), events.length());
        }
        events.clear();

        view.setViewOrigin(originX, originY);
        view.setLastSentTick(tick);
        return created + updated;
    }

    // ---- interest management ----------------------------------------------------------

    /**
     * Ranks candidates and truncates to the budget.
     *
     * Ranking then truncating, rather than shrinking the view rectangle, is what keeps a
     * crowded fight readable: the player loses distant shapes, not the tank shooting at
     * them (02-networking §7).
     */
    private void select(World world, Entity self, ClientView view) {
        float halfW = view.viewWidth * 0.5f;
        float halfH = view.viewHeight * 0.5f;
        float radius = (float) Math.sqrt(halfW * halfW + halfH * halfH);
        float centreX = self.x + self.viewShiftX;
        float centreY = self.y + self.viewShiftY;
        world.hash.queryInto(centreX, centreY, radius, candidates);

        if (ranked.length < candidates.size) {
            ranked = new long[Integer.highestOneBit(candidates.size) << 1];
        }
        rankedCount = 0;

        for (int i = 0; i < candidates.size; i++) {
            int id = candidates.items[i];
            Entity e = world.entities[id];
            if (!e.alive) {
                continue;
            }
            // The client's own tank is included, not skipped. It is what the client renders
            // itself as, and the only source of its own health. It holds Wire.SELF_HANDLE
            // because that handle is reserved for it, not because it sorts first: another
            // tank within four units ties it on distance.
            //
            // Excluding it meant the welcome's promise that "you are handle 1" pointed at
            // whichever opponent happened to be nearest.
            
            if (e.hidden && e != self) {
                continue;                                // not sent, so not seen (D-23)
            }
            if (Math.abs(e.x - centreX) > halfW || Math.abs(e.y - centreY) > halfH) {
                continue;                                // in a queried cell, outside the rect
            }
            // Ranked by nearness to the tank, not to the view's centre: zoomed, what is beside
            // it still comes before what is far ahead.
            float dx = e.x - self.x, dy = e.y - self.y;
            long prio = switch (e.wireClass) {
                case Entity.WIRE_TANK -> PRIO_TANK;
                // Things that hurt before scenery: a trap or a drone ranks with the bullets.
                case Entity.WIRE_PREDICTED, Entity.WIRE_UNIT -> PRIO_PREDICTED;
                default -> PRIO_STATIC;
            };
            int dist = (int) (dx * dx + dy * dy) >>> 4;  // 24 bits is ample for a view rect
            if (dist > 0xFFFFFF) {
                dist = 0xFFFFFF;
            }
            // Sorting ascending puts tanks first, then nearest within each class.
            ranked[rankedCount++] = (prio << 56) | ((long) dist << 24) | id;
        }

        Arrays.sort(ranked, 0, rankedCount);
        if (rankedCount > view.entityBudget) {
            rankedCount = view.entityBudget;
        }
    }

    // ---- sections ----------------------------------------------------------------------

    private void writeRemoves(ClientView view, int tick, SnapshotWriter out) {
        int slot = out.reserveCount();
        int n = 0;
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            if (view.inUse(h) && !view.isMarked(h, tick)) {
                out.u8(h);
                view.releaseHandle(h, tick);
                n++;
            }
        }
        out.patchCount(slot, n);
    }

    private int writeCreates(World world, ClientView view, int tick,
                             int originX, int originY, SnapshotWriter out, EventBuffer events) {
        int slot = out.reserveCount();
        int n = 0;
        for (int i = 0; i < rankedCount; i++) {
            int id = (int) (ranked[i] & 0xFFFFFF);
            int h = view.handleOf(id);
            if (h != ClientView.NO_HANDLE) {
                boolean stuck = !view.createConfirmed(h)
                        && tick - view.createdTick(h) >= RECREATE_AFTER_TICKS
                        && view.ticksSinceAcknowledgementMoved(tick) >= (view.lastAckedTick() > 0
                                ? RECREATE_AFTER_TICKS : RECREATE_BEFORE_FIRST_ACK_TICKS);
                if (!stuck) {
                    continue;
                }
                // Fall through and send the create again.
            } else {
                short generation = world.entities[id].generation;
                h = id == view.selfId
                        ? view.allocateSelfHandle(id, generation)
                        : view.allocateHandle(id, generation);
                if (h == ClientView.NO_HANDLE) {
                    break;                               // out of handles; the rest wait a tick
                }
            }
            view.setCreatedTick(h, tick);
            Entity e = world.entities[id];
            int qx = Wire.quantisePos(e.x) - originX;
            int qy = Wire.quantisePos(e.y) - originY;
            int angle = Wire.quantiseAngle(e.angle);
            int hp = Wire.quantiseHp(e.hp, e.maxHp);
            int level = levelOf(world, e);
            int classId = classOf(world, e);

            out.u8(h);
            out.u8(wireKind(e));
            out.i16(qx);
            out.i16(qy);
            switch (e.wireClass) {
                case Entity.WIRE_TANK -> {
                    out.u8(angle);
                    out.u8(hp);
                    out.u8(classId);
                    out.u8(e.team);
                    out.u8(level);
                    // Its player's name (D-52); none for a tank nobody plays, or past the
                    // limit, dropped rather than cut through a character.
                    if (e.name == null || e.name.length > Wire.MAX_NAME_BYTES) {
                        out.varint(0);
                    } else {
                        out.varint(e.name.length);
                        out.bytes(e.name, e.name.length);
                    }
                    // Its skin, told with its create, for a tank with one only (D-70).
                    int skin = world.tankStats[e.id].skin;
                    if (skin != 0) {
                        events.skin(h, skin);
                    }
                }
                case Entity.WIRE_PREDICTED -> {
                    out.u16(Wire.quantiseHeading((float) Math.atan2(e.vy, e.vx)));
                    out.u8(speedCode(e));
                    out.u8(0);                           // spawnTickOffset
                    out.u8(Math.min(255, Math.max(0, e.lifetimeTicks)));
                    // The owner of that generation: by slot alone, a dead shooter's bullet named whatever took its
                    // slot next, the viewer's own respawned tank among them (P-50).
                    int owner = e.ownerId >= 0 ? view.heldHandle(e.ownerId, e.ownerGeneration) : ClientView.NO_HANDLE;
                    out.u8(owner);
                    out.u8(Math.min(255, Math.round(e.radius)));   // radius: bullets come in sizes (01 §4)
                }
                case Entity.WIRE_UNIT -> {
                    out.u8(angle);
                    out.u8(hp);
                    out.u8(e.subtype);                   // 1 a trap, 2 a drone
                    out.u8(e.team);
                    out.u8(e.ownerId >= 0 ? view.heldHandle(e.ownerId, e.ownerGeneration) : ClientView.NO_HANDLE);
                    out.u8(Math.min(255, Math.round(e.radius)));   // radius: its owner may be gone (protocol 4)
                }
                default -> {
                    out.u8(e.subtype);                   // which shape, so the client can draw it
                    out.u8(angle);                       // spawnAngle: the client turns it from here (02 §6)
                }
            }
            // The create carried qx/qy relative to the view origin, so that it fits an
            // i16 on any map size. Remember absolute: that is the space every later
            // delta for this handle is measured in.
            // Flags as 0: the create has no field for them, so a tank that has any is sent them
            // in the next frame's update.
            view.recordPending(h, Wire.quantisePos(e.x), Wire.quantisePos(e.y),
                    angle, hp, level, classId, 0, tick);
            view.mark(h, tick);
            n++;
        }
        out.patchCount(slot, n);
        return n;
    }

    private int writeUpdates(World world, ClientView view, int tick,
                             int originX, int originY, SnapshotWriter out) {
        int slot = out.reserveCount();
        int n = 0;
        for (int i = 0; i < rankedCount; i++) {
            int id = (int) (ranked[i] & 0xFFFFFF);
            int h = view.handleOf(id);
            if (h == ClientView.NO_HANDLE || view.sentTick(h) == 0 || view.sentTick(h) == tick) {
                continue;                                // never sent, or written this frame
            }
            Entity e = world.entities[id];

            // A predicted entity is never updated — the client extrapolates it from its
            // create along a fixed heading (D-9). Static entities are a different case: shapes
            // drift slowly and bullets knock them, and a client that was never told would hold
            // them a growing distance from where the server has them. An update is sent only
            // when the quantised position actually changes, so slow drift is cheap: measured
            // at 0.6 bytes a snapshot by turning it off.
            if (e.wireClass == Entity.WIRE_PREDICTED) {
                continue;
            }

            // Absolute, not origin-relative. An entity whose *relative* position is
            // unchanged has still moved in the world if the camera moved, and comparing
            // relative positions drops exactly that difference — silently, and for good,
            // because the next comparison is against a value that was never sent. Absolute
            // is also cheaper: scenery that sits still sends nothing, however far the
            // player travels past it.
            int qx = Wire.quantisePos(e.x);
            int qy = Wire.quantisePos(e.y);
            int angle = Wire.quantiseAngle(e.angle);
            int hp = Wire.quantiseHp(e.hp, e.maxHp);
            int level = levelOf(world, e);
            int classId = classOf(world, e);
            int flags = flagsOf(e, tick);

            int mask = 0;
            if (qx != view.sentX(h) || qy != view.sentY(h)) {
                mask |= Wire.F_POS;
            }
            // Not for a shape: the client turns it from its create, and nothing depends on
            // the two agreeing (02 §6). Sending the server's cosmetic spin was a fifth of every
            // frame for a client with shapes in view and nobody firing.
            if (angle != view.sentAngle(h) && e.wireClass != Entity.WIRE_STATIC) {
                mask |= Wire.F_ANGLE;
            }
            if (hp != view.sentHp(h)) {
                mask |= Wire.F_HP;
            }
            // A tank's level was sent in its create and never again, so every opponent's level
            // stayed whatever it was when they came into view.
            if (level != view.sentLevel(h)) {
                mask |= Wire.F_LEVEL;
            }
            if (classId != view.sentClass(h)) {
                mask |= Wire.F_CLASS;
            }
            if (flags != view.sentFlags(h)) {
                mask |= Wire.F_FLAGS;
            }
            if (mask == 0) {
                continue;                                // nothing the client does not know
            }

            out.u8(h);
            out.u8(mask);
            if ((mask & Wire.F_POS) != 0) {
                out.svarint(qx - view.sentX(h));
                out.svarint(qy - view.sentY(h));
            }
            if ((mask & Wire.F_ANGLE) != 0) {
                out.u8(angle);
            }
            if ((mask & Wire.F_HP) != 0) {
                out.u8(hp);
            }
            if ((mask & Wire.F_LEVEL) != 0) {
                out.u8(level);
            }
            if ((mask & Wire.F_CLASS) != 0) {
                out.u8(classId);
            }
            if ((mask & Wire.F_FLAGS) != 0) {           // after TEAM, never sent
                out.u8(flags);
            }
            view.recordPending(h, qx, qy, angle, hp, level, classId, flags, tick);
            n++;
        }
        out.patchCount(slot, n);
        return n;
    }

    private static int wireKind(Entity e) {
        return switch (e.wireClass) {
            case Entity.WIRE_TANK -> Wire.KIND_TANK;
            case Entity.WIRE_PREDICTED -> Wire.KIND_PREDICTED;
            case Entity.WIRE_UNIT -> Wire.KIND_UNIT;
            default -> Wire.KIND_STATIC;
        };
    }

    /** Exact: the simulation fired it at a whole number of half units a tick (01 §4). */
    private static int speedCode(Entity e) {
        return Wire.speedCode((float) Math.sqrt(e.vx * e.vx + e.vy * e.vy));
    }

    /** A tank's flags: whether it is in its spawn protection, and hidden. 0 for anything else. */
    private static int flagsOf(Entity e, int tick) {
        if (e.wireClass != Entity.WIRE_TANK) {
            return 0;
        }
        return (e.protectedAt(tick) ? Wire.TANK_FLAG_PROTECTED : 0) | (e.hidden ? Wire.TANK_FLAG_HIDDEN : 0);
    }

    /** What the wire carries as a tank's level; 0 for anything else, which has none. */
    private static int levelOf(World world, Entity e) {
        return e.wireClass == Entity.WIRE_TANK ? Math.min(255, world.tankStats[e.id].level) : 0;
    }

    /** A tank's class (01 §4), which its create carries and an update carries when it changes. */
    private static int classOf(World world, Entity e) {
        return e.wireClass == Entity.WIRE_TANK ? world.tankStats[e.id].classId : 0;
    }
}
