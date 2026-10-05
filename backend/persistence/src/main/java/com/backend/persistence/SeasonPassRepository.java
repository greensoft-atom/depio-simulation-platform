package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The season pass (docs detailed-design/04-platform-services.md §8, revenue (b); 06, "The season pass";
 * D-69): a player's points in a season, and each track paid to its mark. A tier is paid as the points
 * cross it, by whatever crosses it: a result, in its transaction ({@link #earn}), or premium bought
 * ({@link #buyPremium}); each tier once, its mark moved in the same transaction, under the player's lock.
 */
public final class SeasonPassRepository {

    /** Seasons a pass is kept: the one being played and the five before it, a year. */
    public static final int KEPT_SEASONS = 6;

    /** A player's pass in the season being played; none yet is no points and no premium. */
    public record Pass(int season, Instant endsAt, long points, boolean premium) {
        public int tier() {
            return SeasonPass.tierOf(points);
        }
    }

    /** What a result's points did: so many earned, the tier after, and what the tiers crossed paid. */
    public record Earned(int points, int tier, long coins, int gems, List<String> items) { }

    public enum Bought { BOUGHT, ALREADY_BOUGHT, INSUFFICIENT_FUNDS, SEASON_ENDED }

    /** Premium's answer: {@code gems} the balance after; {@code pass} as it is after. */
    public record Premium(Bought result, long gems, Pass pass) { }

    private record Season(int id, Instant endsAt) { }

    private record Row(long points, boolean premium, int freePaid, int premiumPaid) {
        static final Row NONE = new Row(0, false, 0, 0);
    }

    private final Tx tx;

    public SeasonPassRepository(DataSource ds) {
        this.tx = new Tx(ds);
    }

    public Pass of(long player) throws SQLException {
        return tx.execute(c -> {
            Season season = current(c);
            Row row = row(c, player, season.id(), false);
            return new Pass(season.id(), season.endsAt(), row.points(), row.premium());
        });
    }

    /**
     * The premium track for the season being played: 500 gems through the ledger, keyed by the season and
     * the player, and every premium tier already reached paid at once. Bought once; refused short of gems,
     * or once the season's end has passed and it is being closed.
     */
    public Premium buyPremium(long player, Instant now) throws SQLException {
        return tx.execute(c -> {
            lockPlayer(c, player);
            Season season = current(c);
            Row row = row(c, player, season.id(), true);
            if (row.premium()) {
                return premium(c, Bought.ALREADY_BOUGHT, player, season, row);
            }
            if (!now.isBefore(season.endsAt())) {
                return premium(c, Bought.SEASON_ENDED, player, season, row);
            }
            if (EconomyRepository.move(c, player, EconomyRepository.CURRENCY_GEMS, -SeasonPass.PREMIUM_GEMS,
                    EconomyRepository.REASON_PURCHASE, "pass:" + season.id(), "pass:" + season.id() + ":" + player)
                    == EconomyRepository.Outcome.INSUFFICIENT_FUNDS) {
                return premium(c, Bought.INSUFFICIENT_FUNDS, player, season, row);
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO season_pass (player_id, season_id, premium)"
                    + " VALUES (?, ?, TRUE) ON DUPLICATE KEY UPDATE premium = TRUE")) {
                ps.setLong(1, player);
                ps.setInt(2, season.id());
                ps.executeUpdate();
            }
            Row bought = new Row(row.points(), true, row.freePaid(), row.premiumPaid());
            pay(c, player, season.id(), bought, 0);
            return premium(c, Bought.BOUGHT, player, season, bought);
        });
    }

    private static Premium premium(Connection c, Bought result, long player, Season season, Row row) throws SQLException {
        long gems;
        try (PreparedStatement ps = c.prepareStatement("SELECT gems FROM player WHERE id = ?")) {
            ps.setLong(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                gems = rs.next() ? rs.getLong(1) : 0;
            }
        }
        return new Premium(result, gems, new Pass(season.id(), season.endsAt(), row.points(), row.premium()));
    }

    /** The season being played: the newest not yet placed, as the boards count it (D-63). */
    static int seasonBeingPlayed(Connection c) throws SQLException {
        return current(c).id();
    }

    /**
     * A result's points, in its transaction, the player's row locked by it: the tiers they cross paid on
     * each track the pass has (D-69).
     */
    static Earned earn(Connection c, long player, int season, int points) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO season_pass (player_id, season_id, points)"
                + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE points = points + VALUES(points)")) {
            ps.setLong(1, player);
            ps.setInt(2, season);
            ps.setInt(3, points);
            ps.executeUpdate();
        }
        return pay(c, player, season, row(c, player, season, true), points);
    }

    /** Pays each tier between each track's mark and the tier reached, and moves the marks. */
    private static Earned pay(Connection c, long player, int season, Row row, int earned) throws SQLException {
        int tier = SeasonPass.tierOf(row.points());
        long coins = 0;
        int gems = 0;
        List<String> items = new ArrayList<>();
        for (int t = row.freePaid() + 1; t <= tier; t++) {
            SeasonPass.Reward r = SeasonPass.free(t);
            grant(c, player, season, "free", t, r);
            coins += r.coins();
            gems += r.gems();
            if (r.itemId() != null) {
                items.add(r.itemId());
            }
        }
        int premiumPaid = row.premiumPaid();
        if (row.premium()) {
            for (int t = row.premiumPaid() + 1; t <= tier; t++) {
                SeasonPass.Reward r = SeasonPass.premium(t);
                grant(c, player, season, "premium", t, r);
                coins += r.coins();
                gems += r.gems();
                if (r.itemId() != null) {
                    items.add(r.itemId());
                }
            }
            premiumPaid = Math.max(premiumPaid, tier);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE season_pass SET free_paid = ?, premium_paid = ? WHERE player_id = ? AND season_id = ?")) {
            ps.setInt(1, Math.max(row.freePaid(), tier));
            ps.setInt(2, premiumPaid);
            ps.setLong(3, player);
            ps.setInt(4, season);
            ps.executeUpdate();
        }
        return new Earned(earned, tier, coins, gems, items);
    }

    /** A tier's reward on a track: coins or gems through the ledger, keyed by season, track, tier and player; an item. */
    private static void grant(Connection c, long player, int season, String track, int tier, SeasonPass.Reward r)
            throws SQLException {
        String key = "pass:" + season + ":" + track + ":" + tier + ":" + player;
        if (r.coins() > 0) {
            EconomyRepository.move(c, player, EconomyRepository.CURRENCY_COINS, r.coins(),
                    EconomyRepository.REASON_PASS_TIER, "pass:" + season, key);
        }
        if (r.gems() > 0) {
            EconomyRepository.move(c, player, EconomyRepository.CURRENCY_GEMS, r.gems(),
                    EconomyRepository.REASON_PASS_TIER, "pass:" + season, key);
        }
        if (r.itemId() != null) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO inventory_item (player_id, item_id, qty)"
                    + " VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE qty = qty + 1")) {
                ps.setLong(1, player);
                ps.setString(2, r.itemId());
                ps.executeUpdate();
            }
        }
    }

    /** Retention: passes of seasons before the six kept, {@code batch} rows at a time. @return how many */
    public int purge(int batch) throws SQLException {
        int keptFrom = tx.execute(SeasonPassRepository::seasonBeingPlayed) - (KEPT_SEASONS - 1);
        int total = 0;
        while (true) {
            int deleted = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM season_pass WHERE season_id < ? LIMIT ?")) {
                    ps.setInt(1, keptFrom);
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

    private static Season current(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, ends_at FROM season WHERE placed_at IS NULL ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                // Said, not read off an empty row as the driver's S1000, which the consumer takes for a passing failure
                // and retries for ever (D-50). V2__seed.sql makes season 1; each close makes the next.
                throw new IllegalStateException("no season is being played: the season table has no open season");
            }
            return new Season(rs.getInt(1), rs.getTimestamp(2).toInstant());
        }
    }

    private static Row row(Connection c, long player, int season, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT points, premium, free_paid, premium_paid FROM season_pass"
                + " WHERE player_id = ? AND season_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, player);
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getLong(1), rs.getBoolean(2), rs.getInt(3), rs.getInt(4)) : Row.NONE;
            }
        }
    }

    private static void lockPlayer(Connection c, long player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM player WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, player);
            ps.executeQuery().close();
        }
    }
}
