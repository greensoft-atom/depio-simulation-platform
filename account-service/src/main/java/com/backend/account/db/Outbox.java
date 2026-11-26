package com.backend.account.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The events for the backend, kept until taken (10 §3.5). An event is added on the connection of the change it tells
 * of, so a change and its event commit or roll back together. Delivering is claiming: an event due is leased by moving
 * its next attempt on, by a write that names the time it read, so that of two processes reading it one claims it and
 * the other passes over it; a delivery that ends either takes it or puts it off.
 */
public final class Outbox {

    /** An event due: to be delivered by whoever claimed it. */
    public record Due(long id, String jti, String kind, String token, int attempts) { }

    private final DataSource dataSource;

    public Outbox(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** On the caller's connection: in the transaction of the change it tells of. */
    public void add(Connection c, String jti, String kind, String token, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO event_outbox (jti, kind, token, created_at, next_attempt_at)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, jti);
            ps.setString(2, kind);
            ps.setString(3, token);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    /** Up to {@code max} events due, each claimed for {@code lease}: another process passes over them meanwhile. */
    public List<Due> claim(int max, Instant now, Duration lease) throws SQLException {
        List<Due> claimed = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            List<Object[]> due = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, jti, kind, token, attempts, next_attempt_at FROM event_outbox"
                    + " WHERE taken_at IS NULL AND next_attempt_at <= ? ORDER BY id LIMIT ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setInt(2, max);
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        due.add(new Object[] {r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5), r.getTimestamp(6)});
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE event_outbox SET next_attempt_at = ?"
                    + " WHERE id = ? AND next_attempt_at = ? AND taken_at IS NULL")) {
                for (Object[] d : due) {
                    ps.setTimestamp(1, Timestamp.from(now.plus(lease)));
                    ps.setLong(2, (Long) d[0]);
                    ps.setTimestamp(3, (Timestamp) d[5]);
                    if (ps.executeUpdate() == 1) {
                        claimed.add(new Due((Long) d[0], (String) d[1], (String) d[2], (String) d[3], (Integer) d[4]));
                    }
                }
            }
        }
        return claimed;
    }

    public void taken(long id, Instant now) throws SQLException {
        update("UPDATE event_outbox SET taken_at = ? WHERE id = ?", Timestamp.from(now), id);
    }

    public void failed(long id, int attempts, Instant next) throws SQLException {
        update("UPDATE event_outbox SET attempts = ?, next_attempt_at = ? WHERE id = ? AND taken_at IS NULL",
                attempts, Timestamp.from(next), id);
    }

    /** Events not yet taken. */
    public int pending() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM event_outbox WHERE taken_at IS NULL");
             ResultSet r = ps.executeQuery()) {
            r.next();
            return r.getInt(1);
        }
    }

    private void update(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
