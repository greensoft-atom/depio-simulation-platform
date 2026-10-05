package com.backend.sim;

/**
 * Content for tests that are not about arriving: a tank can be hurt, and may shoot, the tick
 * it spawns. Spawn protection (01 §7) is {@link SpawnTest}'s business; everywhere else it would
 * only make a test wait three seconds before the thing it tests can happen.
 */
final class Fixtures {

    static final Spawning NO_PROTECTION = new Spawning(0, Spawning.defaults().clearance(),
            Spawning.defaults().attempts());

    static final Content UNPROTECTED = new Content(StatTable.defaults(), LevelTable.defaults(),
            ShapeTable.defaults(), Recovery.defaults(), NO_PROTECTION);

    private Fixtures() {
    }
}
