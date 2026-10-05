package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import com.backend.persistence.InboxRepository;

/**
 * The inbox, for a session (docs detailed-design/04-platform-services.md §9, Q-20): its newest
 * items, and marking them read. Blocking, and meant for a virtual thread.
 */
public final class InboxService {

    private final AuthService auth;
    private final InboxRepository inbox;
    private final Clock clock;

    public InboxService(AuthService auth, InboxRepository inbox, Clock clock) {
        this.auth = auth;
        this.inbox = inbox;
        this.clock = clock;
    }

    /** @return the player's newest items; null when the session is not one */
    public List<InboxRepository.Item> items(String token) throws SQLException {
        long me = auth.playerIdOf(token);
        return me < 0 ? null : inbox.itemsOf(me);
    }

    /** Marks read the player's items up to {@code upTo}. @return false when the session is not one */
    public boolean read(String token, long upTo) throws SQLException {
        long me = auth.playerIdOf(token);
        if (me < 0) {
            return false;
        }
        inbox.markRead(me, upTo, clock.instant());
        return true;
    }
}
