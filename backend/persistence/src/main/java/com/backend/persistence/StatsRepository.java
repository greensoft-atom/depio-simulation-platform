package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Whether players come back (05 §11, Q-24, D-47): counted when asked, from the rows the result
 * transaction writes, a player a day in {@code player_day} and the first day on the player.
 */
public final class StatsRepository {

    /** How long a day's activity is kept: a day's new players and the 30 days after, for 60 days back. */
    public static final int KEPT_DAYS = 90;

    /** The most days a read covers: a day-30 figure is first known 31 days back. */
    public static final int MAX_DAYS = 60;

    private static final int[] LATER = {1, 7, 30};

    /** A day-N figure is null until the day N days on has ended. */
    public record Day(LocalDate day, long active, long newPlayers, Long d1, Long d7, Long d30) { }

    /** What a day's new players may have done on that first day (Q-26), in the order they are told. */
    public static final List<String> FEATURES = List.of("queued", "bought", "boosted", "tournament", "friend", "team");

    /** Of a day's new players, those who used a feature that day, and those of them back N days on. */
    public record Used(long players, Long d1, Long d7, Long d30) { }

    public record FeatureDay(LocalDate day, long newPlayers, Map<String, Used> features) { }

    private final Tx tx;

    /**
     * Of the accounts made on a day, UTC, how many went how far by now (05 §11, plan item 76 (c)): played, came
     * back within the week after their first day, reached level 5, played rated, bought in the shop, paid money.
     */
    public record Funnel(LocalDate day, long registered, long guests, long played, long returned, long level5, long rated,
                         long bought, long paid) { }

    /** All players; the guests never upgraded; and those of them idle 90 days (Q-22). */
    public record Guests(long players, long guests, long inactive) { }

    /** Idle this long, a guest is counted inactive; {@code player_day} is kept as long. */
    static final int IDLE_DAYS = 90;

    /** The funnel by the day accounts were made, newest first, {@code today} so far included. */
    public List<Funnel> funnel(LocalDate today, int days) throws SQLException {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("1 to " + MAX_DAYS + " days, not " + days);
        }
        LocalDate first = today.minusDays(days - 1L);
        return tx.execute(c -> {
            Map<LocalDate, Funnel> counted = new HashMap<>();
            // An account's created_at is UTC, as the application's sessions write it (Database: +00:00).
            try (PreparedStatement ps = c.prepareStatement("SELECT DATE(a.created_at), COUNT(*),"
                    + " SUM(a.guest_key_hash IS NOT NULL),"
                    + " SUM(p.first_played_on IS NOT NULL),"
                    // Seven point lookups on player_day's key, not a range of every player those days.
                    + " SUM(p.first_played_on IS NOT NULL AND EXISTS (SELECT 1 FROM player_day x WHERE x.player_id = p.id"
                    + "   AND x.day IN (p.first_played_on + INTERVAL 1 DAY, p.first_played_on + INTERVAL 2 DAY,"
                    + "   p.first_played_on + INTERVAL 3 DAY, p.first_played_on + INTERVAL 4 DAY, p.first_played_on + INTERVAL 5 DAY,"
                    + "   p.first_played_on + INTERVAL 6 DAY, p.first_played_on + INTERVAL 7 DAY))),"
                    + " SUM(p.level >= 5),"
                    + " SUM(p.rated_duels + p.rated_rffas + p.rated_tvts > 0),"
                    + " SUM(EXISTS (SELECT 1 FROM ledger l WHERE l.player_id = p.id AND l.reason = ?)),"
                    + " SUM(EXISTS (SELECT 1 FROM payment_order o WHERE o.player_id = p.id AND o.state IN (?, ?)))"
                    + " FROM account a JOIN player p ON p.id = a.id"
                    + " WHERE a.created_at >= ? AND a.created_at < ? GROUP BY DATE(a.created_at)")) {
                ps.setInt(1, EconomyRepository.REASON_PURCHASE);
                ps.setInt(2, PaymentRepository.PAID);
                ps.setInt(3, PaymentRepository.REFUNDED);
                ps.setString(4, first + " 00:00:00");
                ps.setString(5, today.plusDays(1) + " 00:00:00");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate day = rs.getObject(1, LocalDate.class);
                        counted.put(day, new Funnel(day, rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)));
                    }
                }
            }
            List<Funnel> out = new ArrayList<>(days);
            for (LocalDate d = today; !d.isBefore(first); d = d.minusDays(1)) {
                out.add(counted.getOrDefault(d, new Funnel(d, 0, 0, 0, 0, 0, 0, 0, 0)));
            }
            return out;
        });
    }

    /** The guests, measured: the count their deletion waits on (Q-22, 05 §11). */
    public Guests guests(LocalDate today) throws SQLException {
        String idle = today.minusDays(IDLE_DAYS).toString();
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT (SELECT COUNT(*) FROM player),"
                    + " (SELECT COUNT(*) FROM account WHERE guest_key_hash IS NOT NULL),"
                    // One pass over the days kept, set against the guests: not a lookup of every day per guest.
                    + " (SELECT COUNT(*) FROM account a LEFT JOIN (SELECT DISTINCT player_id FROM player_day WHERE day >= ?) d"
                    + "   ON d.player_id = a.id WHERE a.guest_key_hash IS NOT NULL AND a.created_at < ?"
                    + "   AND (a.last_login_at IS NULL OR a.last_login_at < ?) AND d.player_id IS NULL)")) {
                ps.setString(1, idle);
                ps.setString(2, idle + " 00:00:00");
                ps.setString(3, idle + " 00:00:00");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Guests(rs.getLong(1), rs.getLong(2), rs.getLong(3));
                }
            }
        });
    }

    public StatsRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /** The {@code days} days up to {@code today}, today so far included, newest first. */
    public List<Day> daily(LocalDate today, int days) throws SQLException {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be 1 to " + MAX_DAYS + ", not " + days);
        }
        LocalDate from = today.minusDays(days - 1);
        return tx.execute(c -> {
            Map<LocalDate, Long> active = counts(c, "SELECT day, COUNT(*) FROM player_day"
                    + " WHERE day BETWEEN ? AND ? GROUP BY day", from, today);
            Map<LocalDate, Long> fresh = counts(c, "SELECT first_played_on, COUNT(*) FROM player"
                    + " WHERE first_played_on BETWEEN ? AND ? GROUP BY first_played_on", from, today);
            List<Map<LocalDate, Long>> back = new ArrayList<>();
            for (int later : LATER) {
                back.add(counts(c, "SELECT p.first_played_on, COUNT(*) FROM player p"
                        + " JOIN player_day d ON d.day = p.first_played_on + INTERVAL " + later + " DAY"
                        + " AND d.player_id = p.id"
                        + " WHERE p.first_played_on BETWEEN ? AND ? GROUP BY p.first_played_on", from, today));
            }
            List<Day> out = new ArrayList<>(days);
            for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(1)) {
                Long[] came = new Long[LATER.length];
                for (int i = 0; i < LATER.length; i++) {
                    came[i] = cameBack(d, i, today, back.get(i));
                }
                out.add(new Day(d, active.getOrDefault(d, 0L), fresh.getOrDefault(d, 0L), came[0], came[1], came[2]));
            }
            return List.copyOf(out);
        });
    }

    /** The {@code days} days up to {@code today}, newest first, each day's new players by feature. */
    public List<FeatureDay> byFeature(LocalDate today, int days) throws SQLException {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be 1 to " + MAX_DAYS + ", not " + days);
        }
        LocalDate from = today.minusDays(days - 1);
        return tx.execute(c -> {
            Map<LocalDate, Long> fresh = counts(c, "SELECT first_played_on, COUNT(*) FROM player"
                    + " WHERE first_played_on BETWEEN ? AND ? GROUP BY first_played_on", from, today);
            Map<String, List<Map<LocalDate, Long>>> byFeature = new HashMap<>();
            for (String feature : FEATURES) {
                String cohort = "SELECT p.first_played_on, COUNT(*) FROM player p"
                        + " WHERE p.first_played_on BETWEEN ? AND ? AND " + usedOnDayOne(feature);
                List<Map<LocalDate, Long>> counted = new ArrayList<>();
                counted.add(counts(c, cohort + " GROUP BY p.first_played_on", from, today));
                for (int later : LATER) {
                    counted.add(counts(c, cohort + " AND EXISTS (SELECT 1 FROM player_day d WHERE d.day ="
                            + " p.first_played_on + INTERVAL " + later + " DAY AND d.player_id = p.id)"
                            + " GROUP BY p.first_played_on", from, today));
                }
                byFeature.put(feature, counted);
            }
            List<FeatureDay> out = new ArrayList<>(days);
            for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(1)) {
                Map<String, Used> features = new java.util.LinkedHashMap<>();
                for (String feature : FEATURES) {
                    List<Map<LocalDate, Long>> counted = byFeature.get(feature);
                    features.put(feature, new Used(counted.get(0).getOrDefault(d, 0L),
                            cameBack(d, 0, today, counted.get(1)), cameBack(d, 1, today, counted.get(2)),
                            cameBack(d, 2, today, counted.get(3))));
                }
                out.add(new FeatureDay(d, fresh.getOrDefault(d, 0L), java.util.Collections.unmodifiableMap(features)));
            }
            return List.copyOf(out);
        });
    }

    /** Of day {@code d}'s new players counted, those back LATER[i] days on; null until that day has ended. */
    private static Long cameBack(LocalDate d, int i, LocalDate today, Map<LocalDate, Long> back) {
        return d.plusDays(LATER[i]).isBefore(today) ? back.getOrDefault(d, 0L) : null;
    }

    /** A row of the feature's on the player's first day, UTC (Q-26), for the player {@code p}. */
    private static String usedOnDayOne(String feature) {
        return switch (feature) {
            case "queued" -> "EXISTS (SELECT 1 FROM match_player mp JOIN matches m ON m.id = mp.match_id"
                    + " WHERE mp.player_id = p.id AND m.mode <> 0" + onDayOne("m.ended_at") + ")";
            case "bought" -> "EXISTS (SELECT 1 FROM ledger l WHERE l.player_id = p.id AND l.reason = "
                    + EconomyRepository.REASON_PURCHASE + onDayOne("l.created_at") + ")";
            case "boosted" -> "EXISTS (SELECT 1 FROM boost b WHERE b.player_id = p.id" + onDayOne("b.started_at") + ")";
            case "tournament" -> "(EXISTS (SELECT 1 FROM tournament_entry e WHERE e.player_id = p.id"
                    + onDayOne("e.registered_at") + ") OR EXISTS (SELECT 1 FROM tournament_roster r"
                    + " JOIN tournament_team_entry te ON te.tournament_id = r.tournament_id AND te.team_id = r.team_id"
                    + " WHERE r.player_id = p.id" + onDayOne("te.registered_at") + "))";
            case "friend" -> "EXISTS (SELECT 1 FROM friend f WHERE f.player_id = p.id" + onDayOne("f.since") + ")";
            case "team" -> "EXISTS (SELECT 1 FROM team_member tm WHERE tm.player_id = p.id" + onDayOne("tm.joined_at") + ")";
            default -> throw new IllegalArgumentException("no such feature: " + feature);
        };
    }

    private static String onDayOne(String column) {
        return " AND " + column + " >= p.first_played_on AND " + column + " < p.first_played_on + INTERVAL 1 DAY";
    }

    private static Map<LocalDate, Long> counts(Connection c, String sql, LocalDate from, LocalDate to)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, from);
            ps.setObject(2, to);
            Map<LocalDate, Long> out = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getObject(1, LocalDate.class), rs.getLong(2));
                }
            }
            return out;
        }
    }

    /**
     * Retention: deletes the days before {@code cutoff}, {@code batch} rows at a time. The first
     * day a player played stays on the player. @return how many rows
     */
    public int purgeActivityBefore(LocalDate cutoff, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_day WHERE day < ? LIMIT ?")) {
                    ps.setObject(1, cutoff);
                    ps.setInt(2, batch);
                    return ps.executeUpdate();
                }
            });
            total += removed;
            if (removed < batch) {
                return total;
            }
        }
    }
}
