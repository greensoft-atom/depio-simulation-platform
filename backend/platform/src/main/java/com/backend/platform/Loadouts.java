package com.backend.platform;

import java.sql.SQLException;

/** What a player wears, as the bonus a ticket carries to the arena (04 §8, D-37). */
@FunctionalInterface
public interface Loadouts {

    /** A whole percent a stat, indexed as sim's {@code Stat}, capped. */
    byte[] bonusOf(long playerId) throws SQLException;

    /** The number of the skin the player wears and holds, 0 for none (D-70); none for a loadout without skins. */
    default int skinOf(long playerId) throws SQLException {
        return 0;
    }
}
