package com.backend.arena;

import com.backend.protocol.Wire;

/**
 * How much a client is sent (02 §8): how often, and how many entities at most. Ordered from
 * the least to the most, so stepping down is {@code ordinal() - 1}.
 *
 * The rates divide the room's 25 ticks evenly enough to need no clock of their own: 15 is
 * every tick where fifteen twenty-fifths have accumulated, 10 every tick where ten have.
 */
public enum TrafficProfile {

    /** Chosen in settings by a player who is paying for data; never raised by the server. */
    SAVER(10, 20),
    /** The default. */
    MOBILE(15, 30),
    /** A client that asked for more, and whose link can take it. */
    HIGH(15, 60);

    /** Snapshots a second. */
    public final int rate;
    /** Entities at most in one snapshot. */
    public final int budget;

    TrafficProfile(int rate, int budget) {
        this.rate = rate;
        this.budget = budget;
    }

    /** From the optional byte in Join: absent or unknown means {@link #MOBILE}. */
    public static TrafficProfile fromWire(int value) {
        return switch (value) {
            case Wire.PROFILE_HIGH -> HIGH;
            case Wire.PROFILE_SAVER -> SAVER;
            default -> MOBILE;
        };
    }

    TrafficProfile down() {
        return this == SAVER ? SAVER : values()[ordinal() - 1];
    }

    TrafficProfile up() {
        return this == HIGH ? HIGH : values()[ordinal() + 1];
    }

    /** Lower case, for metric labels. */
    String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
