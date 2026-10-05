package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;

import com.backend.common.RefusedConfiguration;
import com.backend.persistence.SeasonPass;
import com.backend.persistence.SeasonPassRepository;

/**
 * The season pass (docs detailed-design/04-platform-services.md §8, revenue (b), D-69): a player's pass in
 * the season being played, and its premium track bought with gems. The tiers are paid where the points
 * are earned, in the worker's result transaction; here only premium pays, the tiers already reached.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class PassService {

    private final AuthService auth;
    private final SeasonPassRepository passes;
    private final Clock clock;

    public PassService(AuthService auth, SeasonPassRepository passes, Clock clock) {
        this.auth = auth;
        this.passes = passes;
        this.clock = clock;
    }

    /** Every boost the tiers name is an item in the table; one that is not stops the start, as an offer's does. */
    public static void checkItems(Items items) {
        for (String id : SeasonPass.items()) {
            if (items.find(id) == null) {
                throw new RefusedConfiguration("the season pass pays " + id + ", which items.json does not have");
            }
        }
    }

    /** @return the player's pass, or null for a session that is not valid */
    public SeasonPassRepository.Pass of(String sessionToken) throws SQLException {
        long player = auth.playerIdOf(sessionToken);
        return player < 0 ? null : passes.of(player);
    }

    /** @return premium's answer, or null for a session that is not valid */
    public SeasonPassRepository.Premium buyPremium(String sessionToken) throws SQLException {
        long player = auth.playerIdOf(sessionToken);
        return player < 0 ? null : passes.buyPremium(player, clock.instant());
    }
}
