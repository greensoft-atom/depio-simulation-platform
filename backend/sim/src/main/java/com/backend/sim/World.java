package com.backend.sim;

import com.backend.common.IntList;
import com.backend.common.Xorshift;

/**
 * Entity storage for one room: a fixed pool of {@link Entity} objects, a free-slot stack,
 * and one id list per kind so update passes iterate only what they need.
 *
 * Touched by exactly one thread — the room's. Nothing here is synchronised, and adding a
 * lock would be a design error rather than a safety improvement (docs
 * detailed-design/07 §1).
 */
public final class World {

    public final float width, height;
    public final Entity[] entities;
    public final IntList tanks, bullets, shapes;
    public final SpatialHash hash;
    public final Xorshift rng;

    /**
     * Progression for whichever entity currently occupies each slot, or null for a slot that
     * has never held a tank.
     *
     * Beside the pool rather than inside {@link Entity} because bullets outnumber tanks by
     * an order of magnitude, and a dozen extra fields on every bullet would push the object
     * that the hottest loop walks out of its cache line to carry state it can never use.
     * Created on first use and reset thereafter, so a running room allocates none.
     */
    public final TankStats[] tankStats;

    private final int[] freeSlots;
    private int freeTop;

    /** Entities that died this tick; their slots are recycled at the end of it. */
    private final IntList dying;

    public World(float width, float height, int capacity, float cellSize, long seed) {
        this.width = width;
        this.height = height;
        this.entities = new Entity[capacity];
        this.tankStats = new TankStats[capacity];
        this.freeSlots = new int[capacity];
        this.tanks = new IntList(256);
        this.bullets = new IntList(4096);
        this.shapes = new IntList(2048);
        this.dying = new IntList(512);
        this.hash = new SpatialHash(width, height, cellSize, capacity);
        this.rng = new Xorshift(seed);

        for (int i = 0; i < capacity; i++) {
            Entity e = new Entity();
            e.id = i;
            entities[i] = e;
            freeSlots[i] = capacity - 1 - i;    // hand out low ids first
        }
        freeTop = capacity;
    }

    public int capacity() {
        return entities.length;
    }

    public int liveCount() {
        return entities.length - freeTop;
    }

    /**
     * @return the new entity, or null when the pool is exhausted. Refusing to spawn is the
     *         designed behaviour: capacity is a hard ceiling, never grown (07 §8).
     */
    public Entity spawn(byte kind) {
        if (freeTop == 0) {
            return null;
        }
        Entity e = entities[freeSlots[--freeTop]];
        e.kind = kind;
        e.alive = true;
        e.ownerId = -1;
        e.playerTag = 0;
        e.vx = e.vy = e.angle = 0f;
        e.lifetimeTicks = -1;
        e.reloadTicks = 0;
        e.subtype = 0;
        switch (kind) {
            case Entity.KIND_TANK -> {
                e.wireClass = Entity.WIRE_TANK;
                if (tankStats[e.id] == null) {
                    tankStats[e.id] = new TankStats();
                }
                tankStats[e.id].reset();
                tanks.add(e.id);
            }
            case Entity.KIND_BULLET -> {
                e.wireClass = Entity.WIRE_PREDICTED;
                bullets.add(e.id);
            }
            case Entity.KIND_SHAPE -> {
                e.wireClass = Entity.WIRE_STATIC;
                shapes.add(e.id);
            }
            default -> throw new IllegalArgumentException("kind " + kind);
        }
        return e;
    }

    /** Marks an entity dead. Its slot is not reusable until {@link #sweep()} runs. */
    public void kill(Entity e) {
        if (e.alive) {
            e.alive = false;
            dying.add(e.id);
        }
    }

    /**
     * Recycles slots of everything killed this tick.
     *
     * Deferred to the end of the tick on purpose: freeing a slot mid-pass would let it be
     * reused by a later spawn in the same tick, and a still-running loop holding that id
     * would silently operate on a different entity.
     */
    public void sweep() {
        if (dying.isEmpty()) {
            return;
        }
        compact(tanks);
        compact(bullets);
        compact(shapes);
        for (int i = 0; i < dying.size; i++) {
            Entity e = entities[dying.items[i]];
            e.reset();
            freeSlots[freeTop++] = e.id;
        }
        dying.clear();
    }

    private void compact(IntList list) {
        for (int i = 0; i < list.size; ) {
            if (entities[list.items[i]].alive) {
                i++;
            } else {
                list.removeAtSwap(i);
            }
        }
    }
}
