package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import com.backend.persistence.BoostRepository;
import com.backend.persistence.BoostRepository.Running;

/**
 * Activating boosts (docs detailed-design/04-platform-services.md §8, "Boosts", Q-13): one held
 * is taken and runs, once per key, one of a kind at a time. What a boost does to a match is
 * worker's (D-38).
 *
 * Blocking, and meant for a virtual thread.
 */
public final class BoostService {

    /** The client's key for one tap of Activate: as a purchase's, a lowercase UUID fits. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9-]{16,48}");

    public enum Result {
        OK, ACTIVATED, ALREADY_ACTIVATED, NO_SESSION, INVALID_KEY, UNKNOWN_ITEM, NOT_OWNED, OTHER_RUNNING
    }

    /** {@code running}: the player's boosts running, for every result a session reached. */
    public record Answer(Result result, List<Running> running) {
        static Answer of(Result result) {
            return new Answer(result, List.of());
        }
    }

    private final AuthService auth;
    private final BoostRepository repository;
    private final Items items;
    private final Clock clock;

    public BoostService(AuthService auth, BoostRepository repository, Items items, Clock clock) {
        this.auth = auth;
        this.repository = repository;
        this.items = items;
        this.clock = clock;
    }

    public Answer running(String sessionToken) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        return new Answer(Result.OK, repository.running(playerId, clock.instant()));
    }

    public Answer activate(String sessionToken, String itemId, String key) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Answer.of(Result.NO_SESSION);
        }
        if (!KEY.matcher(key).matches()) {
            return Answer.of(Result.INVALID_KEY);
        }
        Items.Item item = items.find(itemId);
        if (item == null || item.boost() == null) {
            return Answer.of(Result.UNKNOWN_ITEM);
        }
        Items.Boost boost = item.boost();
        BoostRepository.Outcome outcome = repository.activate(playerId, itemId, boost.kind(), boost.percent(),
                boost.minutes(), key, clock.instant());
        Result result = switch (outcome) {
            case ACTIVATED -> Result.ACTIVATED;
            case ALREADY_ACTIVATED -> Result.ALREADY_ACTIVATED;
            case NOT_OWNED -> Result.NOT_OWNED;
            case OTHER_RUNNING -> Result.OTHER_RUNNING;
        };
        return new Answer(result, repository.running(playerId, clock.instant()));
    }
}
