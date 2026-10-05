package com.backend.sim;

/**
 * Every balance table a room needs, in one object.
 *
 * <h2>Why they travel together</h2>
 *
 * They are not independent. The experience a shape is worth only means something against
 * the curve that spends it, and a stat's per-point value only means something against the
 * number of points the curve hands out. Passing them separately would let a room be built
 * with a level curve from one balance pass and shapes from another, which is a bug nobody
 * would see until the pacing felt wrong.
 *
 * <h2>Immutable</h2>
 *
 * Each room builds its own ({@link #defaults()}, from {@code Room(World)}); nothing here changes
 * while a room is running: a balance change is a new build and a restart, not a mutation under a
 * live tick.
 */
public record Content(StatTable stats, LevelTable levels, ShapeTable shapes, Recovery recovery,
                      Spawning spawning, ClassTable classes) {

    /** The shipped recovery and spawning with other tables: what a test that tunes one wants. */
    public Content(StatTable stats, LevelTable levels, ShapeTable shapes) {
        this(stats, levels, shapes, Recovery.defaults(), Spawning.defaults());
    }

    /** The shipped classes with other tables. */
    public Content(StatTable stats, LevelTable levels, ShapeTable shapes, Recovery recovery,
                   Spawning spawning) {
        this(stats, levels, shapes, recovery, spawning, ClassTable.defaults());
    }

    /** The shipped balance. See each table for where its numbers came from. */
    public static Content defaults() {
        return new Content(StatTable.defaults(), LevelTable.defaults(), ShapeTable.defaults(),
                Recovery.defaults(), Spawning.defaults(), ClassTable.defaults());
    }

    /** The largest radius a shape can have; with a tank's radius, it sets the collision query reach. */
    public float maxShapeRadius() {
        float max = 0f;
        for (int i = 0; i < shapes.size(); i++) {
            max = Math.max(max, shapes.get(i).radius());
        }
        return max;
    }
}
