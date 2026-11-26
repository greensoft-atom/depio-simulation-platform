package com.backend.account.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

/**
 * What is no longer needed, purged (10 §3.6), in batches so that no statement holds locks for long: refresh tokens a
 * day past their end, their family's end or their revocation (a token used within its overlap is still live, and an
 * ended one is refused by its times, so a day is margin, not a rule); the limits' windows an hour old; events a week
 * after they were taken. An event not taken is kept however old: it is a deletion the backend has not yet done.
 */
public final class Housekeeping {

    static final Duration TOKEN_MARGIN = Duration.ofDays(1);
    static final Duration WINDOW_AGE = Duration.ofHours(1);
    static final Duration EVENT_AGE = Duration.ofDays(7);
    private static final int BATCH = 1000;

    /** What one run purged. */
    public record Purged(int tokens, int windows, int events) { }

    private final DataSource dataSource;

    public Housekeeping(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Purged run(Instant now) throws SQLException {
        Timestamp tokenCut = Timestamp.from(now.minus(TOKEN_MARGIN));
        int tokens = purge("DELETE FROM refresh_token WHERE expires_at < ? OR family_expires_at < ? OR revoked_at < ? LIMIT "
                + BATCH, tokenCut, tokenCut, tokenCut);
        int windows = purge("DELETE FROM attempt WHERE window_start < ? LIMIT " + BATCH, now.minus(WINDOW_AGE).getEpochSecond());
        int events = purge("DELETE FROM event_outbox WHERE taken_at < ? LIMIT " + BATCH, Timestamp.from(now.minus(EVENT_AGE)));
        return new Purged(tokens, windows, events);
    }

    /** One statement again until it deletes less than a batch. */
    private int purge(String sql, Object... args) throws SQLException {
        int total = 0;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            int n;
            do {
                n = ps.executeUpdate();
                total += n;
            } while (n == BATCH);
        }
        return total;
    }
}
