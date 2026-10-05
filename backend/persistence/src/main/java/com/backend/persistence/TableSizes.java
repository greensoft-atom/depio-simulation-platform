package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

/**
 * The tables that grow, and InnoDB's estimate of their rows (docs detailed-design/06-persistence-mysql.md §9,
 * D-72): what every worker reports, so that a growth trigger is an alert rather than a surprise.
 */
public final class TableSizes {

    /** The tables watched: the ledger kept for ever, the history and activity kept for days, the players. */
    public static final List<String> WATCHED = List.of("ledger", "match_player", "matches", "player_day", "inbox", "player");

    private final DataSource ds;

    public TableSizes(DataSource ds) {
        this.ds = ds;
    }

    /** Each watched table's rows, an estimate. Read fresh: MySQL keeps them a day by default. */
    public Map<String, Long> rows() throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement fresh = c.prepareStatement("SET SESSION information_schema_stats_expiry = 0")) {
                fresh.execute();
            }
            Map<String, Long> out = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT table_name, table_rows FROM information_schema.tables"
                    + " WHERE table_schema = DATABASE() AND table_name IN (" + "?,".repeat(WATCHED.size() - 1) + "?)")) {
                for (int i = 0; i < WATCHED.size(); i++) {
                    ps.setString(i + 1, WATCHED.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return out;
        }
    }
}
