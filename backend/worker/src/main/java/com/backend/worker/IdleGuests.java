package com.backend.worker;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.handoff.LeaderboardStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.TeamRepository;

/**
 * Retention's last step (D-87, 04 §1): the guests nobody has played for {@link #IDLE_DAYS} days, erased as a player's
 * own deletion erases them. A guest at a time, the store first: a store that fails stops the step with nothing erased,
 * and the next day's run finds the guest still a guest. Then out of its team, whatever its role, and the erasure. No
 * session to end (a session lives a day) and no lobby to tell.
 */
final class IdleGuests {

    static final int IDLE_DAYS = 180;

    private final AccountRepository accounts;
    private final TeamRepository teams;
    private final LeaderboardStore boards;
    private final int batch;
    private final AtomicLong erased = new AtomicLong();

    IdleGuests(AccountRepository accounts, TeamRepository teams, LeaderboardStore boards, int batch) {
        this.accounts = accounts;
        this.teams = teams;
        this.boards = boards;
        this.batch = batch;
    }

    /** @return how many guests this run erased; a store or MySQL failure stops it where it is */
    int erase(Instant now) throws SQLException {
        Instant since = now.minus(IDLE_DAYS, ChronoUnit.DAYS);
        int count = 0;
        while (true) {
            // An erased guest is a guest no more, so each round's candidates are the ones still to do.
            List<Long> ids = accounts.idleGuests(since, batch);
            for (long id : ids) {
                boards.remove(id).join();
                teams.erase(id, now);
                if (accounts.erase(id) == AccountRepository.Erased.ERASED) {
                    count++;
                    erased.incrementAndGet();
                }
            }
            if (ids.size() < batch) {
                return count;
            }
        }
    }

    /** Guests erased by every run so far; read by the metrics scrape. */
    long erasedTotal() {
        return erased.get();
    }
}
