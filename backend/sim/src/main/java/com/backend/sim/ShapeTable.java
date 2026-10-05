package com.backend.sim;

import com.backend.common.Xorshift;

/**
 * The neutral scenery a room is filled with (docs detailed-design/01-arena.md §4).
 *
 * <h2>Why variety is not decoration</h2>
 *
 * Shapes are where nearly all early experience comes from, so the spread of them *is* the
 * early pacing curve. One shape worth one amount gives a flat progression where every
 * minute is the last one; a rare shape worth three hundred squares gives a reason to cross
 * the map, and a reason to fight over what you find there.
 */
public final class ShapeTable {

    /**
     * One kind of shape.
     *
     * {@code id} goes on the wire as the static entity's subtype, so the client knows what
     * to draw. It is a contract: append kinds, never renumber them.
     */
    public record Type(byte id, String name, float radius, float mass, float hp,
                       float bodyDamage, int xp, int weight) { }

    /** The shape a Necromancer makes its drones of (01 §4, "The third tier"). */
    public static final byte SQUARE = 0;

    private final Type[] types;
    private final int totalWeight;
    /** Experience by wire id, so the collision pass does not scan the table per kill. */
    private final int[] xpById;

    public ShapeTable(Type[] types) {
        if (types.length == 0) {
            throw new IllegalArgumentException("a room needs at least one kind of shape");
        }
        this.types = types.clone();
        int sum = 0;
        for (Type t : this.types) {
            if (t.weight() < 0) {
                throw new IllegalArgumentException("negative weight for " + t.name());
            }
            sum += t.weight();
        }
        if (sum <= 0) {
            throw new IllegalArgumentException("every shape weight is zero, so none can spawn");
        }
        this.totalWeight = sum;

        int highest = 0;
        for (Type t : this.types) {
            if (t.id() < 0) {
                throw new IllegalArgumentException("shape ids are unsigned on the wire: " + t.name());
            }
            highest = Math.max(highest, t.id());
        }
        this.xpById = new int[highest + 1];
        for (Type t : this.types) {
            xpById[t.id()] = t.xp();
        }
    }

    /** What a shape of this wire id is worth. 0 for an id no table entry claims. */
    public int xpOf(byte id) {
        return id >= 0 && id < xpById.length ? xpById[id] : 0;
    }

    public int size() {
        return types.length;
    }

    public Type get(int index) {
        return types[index];
    }

    /** @return the type with this wire id, or null. */
    public Type byId(byte id) {
        for (Type t : types) {
            if (t.id() == id) {
                return t;
            }
        }
        return null;
    }

    /** Picks a kind in proportion to its weight. */
    public Type pick(Xorshift rng) {
        int roll = rng.nextInt(totalWeight);
        for (Type t : types) {
            roll -= t.weight();
            if (roll < 0) {
                return t;
            }
        }
        return types[types.length - 1];          // unreachable: the draw is below the total
    }

    /**
     * The shipped population.
     *
     * The experience values are diep.io's, because they are known to produce a playable
     * curve and inventing four numbers here would not be. The weights are not: they say a
     * room is mostly squares, that a pentagon is a find, and that an alpha pentagon is worth
     * telling somebody about. One in two hundred shapes is an alpha, and killing it alone at
     * three thousand experience takes a level-1 tank to level 26 — which is
     * either the best thing in the mode or badly overtuned, and only play will say which.
     */
    public static ShapeTable defaults() {
        return new ShapeTable(new Type[] {
                new Type((byte) 0, "square", 18f, 3f, 10f, 8f, 10, 600),
                new Type((byte) 1, "triangle", 22f, 5f, 30f, 12f, 25, 300),
                new Type((byte) 2, "pentagon", 32f, 12f, 100f, 16f, 130, 95),
                new Type((byte) 3, "alpha pentagon", 80f, 40f, 3_000f, 20f, 3_000, 5),
        });
    }
}
