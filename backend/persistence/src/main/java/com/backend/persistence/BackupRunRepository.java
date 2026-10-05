package com.backend.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

/**
 * What the backups' steps did (docs detailed-design/06-persistence-mysql.md §10, D-71): the dump, the weekly
 * restore proof and the copy off the site each record a row as the backup account, from their scripts;
 * this reads them for the workers' metrics, and retention deletes the old.
 */
public final class BackupRunRepository {

    public static final int DUMP = 1;
    public static final int PROOF = 2;
    public static final int OFFSITE = 3;

    /** How long a run's row is kept. */
    public static final Duration KEPT = Duration.ofDays(90);

    /**
     * A kind's last success, null for none; whether its last run failed; and the seconds its last success
     * recorded, a proof's restore and replay (D-72), null for none.
     */
    public record Latest(Instant succeeded, boolean failed, Double seconds) { }

    private final Tx tx;

    public BackupRunRepository(DataSource ds) {
        this.tx = new Tx(ds);
    }

    /** Each kind that has run: its last success, and whether its last run failed. */
    public Map<Integer, Latest> latest() throws SQLException {
        return tx.execute(c -> {
            Map<Integer, Latest> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT r.kind,"
                    + " (SELECT MAX(s.finished_at) FROM backup_run s WHERE s.kind = r.kind AND s.ok),"
                    + " (SELECT NOT l.ok FROM backup_run l WHERE l.kind = r.kind ORDER BY l.finished_at DESC, l.id DESC LIMIT 1),"
                    + " (SELECT t.seconds FROM backup_run t WHERE t.kind = r.kind AND t.ok ORDER BY t.finished_at DESC, t.id DESC LIMIT 1)"
                    + " FROM (SELECT DISTINCT kind FROM backup_run) r");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp succeeded = rs.getTimestamp(2);
                    double seconds = rs.getDouble(4);
                    Double took = rs.wasNull() ? null : seconds;          // asked at once: it tells of the last read
                    out.put(rs.getInt(1), new Latest(succeeded == null ? null : succeeded.toInstant(), rs.getBoolean(3), took));
                }
            }
            return out;
        });
    }

    /** Retention: rows finished before {@code before}, {@code batch} at a time. @return how many */
    public int purgeBefore(Instant before, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int deleted = tx.execute(c -> {
                // Each kind's newest success is kept, however old: deleted, the alert that asks how long since one would
                // read none and say nothing, for a step that stopped recording at all (D-48). Wrapped twice: MySQL
                // reads no table a DELETE writes in its own subquery.
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM backup_run WHERE finished_at < ? AND id NOT IN"
                        + " (SELECT id FROM (SELECT MAX(id) AS id FROM backup_run WHERE ok GROUP BY kind) newest) LIMIT ?")) {
                    ps.setTimestamp(1, Timestamp.from(before));
                    ps.setInt(2, batch);
                    return ps.executeUpdate();
                }
            });
            total += deleted;
            if (deleted < batch) {
                return total;
            }
        }
    }
}
