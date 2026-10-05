package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Currency and inventory (docs detailed-design/06 §4).
 *
 * Every balance change goes through {@link #move} and writes a ledger row in the same
 * transaction — including a match's reward, which {@code MatchResultRepository} pays through
 * it inside its own transaction. There is deliberately no second way to move a balance: that
 * is what makes the nightly reconciliation (SUM(delta) against player.coins, and player.gems)
 * mean anything.
 * Until D-13 there was one, a copy in the match path, and the invariant held because the two
 * happened to agree.
 */
public final class EconomyRepository {

    /** Ledger reason codes. */
    public static final int REASON_MATCH_REWARD = 0;
    public static final int REASON_PURCHASE = 1;
    public static final int REASON_REFUND = 2;
    public static final int REASON_TOURNAMENT_PRIZE = 3;
    public static final int REASON_ITEM_LEVEL = 4;
    /** An account level's gems (04 §8, D-61). */
    public static final int REASON_MILESTONE = 5;
    /** A season's place on a rating board (04 §7, D-63). */
    public static final int REASON_SEASON = 6;
    /** An achievement reached (04 §8, D-64). */
    public static final int REASON_ACHIEVEMENT = 7;
    /** A daily goal met, or the day's three (04 §8, D-66). */
    public static final int REASON_DAILY_GOAL = 8;
    /** Gems bought: a paid order's (04 §8, revenue, D-68). */
    public static final int REASON_PAYMENT = 9;
    /** A paid order refunded: its gems taken back. */
    public static final int REASON_PAYMENT_REFUND = 10;
    /** A season pass's tier reached (04 §8, revenue (b), D-69). */
    public static final int REASON_PASS_TIER = 11;

    /** An item's levels, 1 to this (04 §8, plan item 67). */
    public static final int MAX_ITEM_LEVEL = 5;
    /** The first level's price; each after it twice the one before (04 §8): a first cut. */
    static final long LEVEL_BASE_COST = 500;

    /** What raising an item from level {@code from} costs: 500, 1 000, 2 000, 4 000. */
    public static long levelCost(int from) {
        return LEVEL_BASE_COST << (from - 1);
    }

    public enum Raised { RAISED, ALREADY, NOT_HELD, MAX_LEVEL, INSUFFICIENT_FUNDS }

    /** A raise: what it did, the item's level after, and the coins left. */
    public record Raise(Raised outcome, int level, long coins) { }

    /**
     * Raises an item the player holds a level, for {@link #levelCost}, in one transaction, as a
     * purchase: the player locked first, the level read under the lock, the coins moved by the
     * ledger's one path keyed {@code level:{player}:{key}}, then the level raised. A key used
     * before answers {@code ALREADY} with the level it reached.
     */
    public Raise raiseLevel(long playerId, String itemId, String clientKey) throws SQLException {
        if (clientKey == null || clientKey.isEmpty() || clientKey.length() > MAX_PURCHASE_KEY) {
            throw new IllegalArgumentException("a level's key must be 1 to " + MAX_PURCHASE_KEY + " characters");
        }
        String key = "level:" + playerId + ":" + clientKey;
        return tx.execute(c -> {
            long balance = lockAndReadCoins(c, playerId);
            try (PreparedStatement ps = c.prepareStatement("SELECT ref FROM ledger WHERE idem_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String ref = rs.getString(1);       // {itemId}:{level reached}
                        return new Raise(Raised.ALREADY, Integer.parseInt(ref.substring(ref.lastIndexOf(':') + 1)), balance);
                    }
                }
            }
            int level;
            try (PreparedStatement ps = c.prepareStatement("SELECT item_level FROM inventory_item"
                    + " WHERE player_id = ? AND item_id = ? AND qty > 0 FOR UPDATE")) {
                ps.setLong(1, playerId);
                ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Raise(Raised.NOT_HELD, 0, balance);
                    }
                    level = rs.getInt(1);
                }
            }
            if (level >= MAX_ITEM_LEVEL) {
                return new Raise(Raised.MAX_LEVEL, level, balance);
            }
            long cost = levelCost(level);
            if (moveCoins(c, playerId, -cost, REASON_ITEM_LEVEL, itemId + ":" + (level + 1), key) != Outcome.APPLIED) {
                return new Raise(Raised.INSUFFICIENT_FUNDS, level, balance);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE inventory_item SET item_level = item_level + 1"
                    + " WHERE player_id = ? AND item_id = ? AND item_level = ?")) {
                ps.setLong(1, playerId);
                ps.setString(2, itemId);
                ps.setInt(3, level);
                ps.executeUpdate();
            }
            return new Raise(Raised.RAISED, level + 1, balance - cost);
        });
    }

    public static final int CURRENCY_COINS = 0;
    public static final int CURRENCY_GEMS = 1;

    /** Duplicate entry: the only constraint failure that means "this key was used already". */
    private static final int DUPLICATE_ENTRY = 1062;

    /**
     * The longest purchase key a client may send. {@code ledger.idem_key} is 80 characters, and
     * the stored key is {@code buy:{playerId}:{key}}: 4 + 20 + 1 + 48 = 73, with room to spare.
     */
    public static final int MAX_PURCHASE_KEY = 48;

    public enum Outcome {
        APPLIED,
        /** The idempotency key was already used: the first attempt succeeded. */
        ALREADY_APPLIED,
        INSUFFICIENT_FUNDS
    }

    private final Tx tx;

    public EconomyRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /**
     * Buys one item.
     *
     * The unique violation on {@code idem_key} is the <em>expected</em> path for a retry,
     * not an error: a player who taps Buy twice, or whose connection drops mid-request, is
     * charged once.
     */
    public Outcome purchase(long playerId, String itemId, long price, String idemKey)
            throws SQLException {
        return purchase(playerId, itemId, price, idemKey, CURRENCY_COINS);
    }

    /** Buys one item for {@code price} in {@code currency}, coins or gems, by the same one path (04 §8). */
    public Outcome purchase(long playerId, String itemId, long price, String idemKey, int currency)
            throws SQLException {
        if (price < 0) {
            throw new IllegalArgumentException("negative price: " + price);
        }
        if (idemKey == null || idemKey.isEmpty() || idemKey.length() > MAX_PURCHASE_KEY) {
            throw new IllegalArgumentException("purchase key must be 1 to " + MAX_PURCHASE_KEY
                    + " characters");
        }
        String key = purchaseKey(playerId, idemKey);
        return tx.execute(c -> {
            Outcome moved = move(c, playerId, currency, -price, REASON_PURCHASE, itemId, key);
            if (moved == Outcome.APPLIED) {
                grantItem(c, playerId, itemId, 1);
            }
            return moved;
        });
    }

    /**
     * The ledger key of a player's purchase. Per player: the ledger's unique index is global,
     * and a key a client made up is unique only to that client. Stored as given, a second
     * player sending the same key was told ALREADY_APPLIED and got nothing.
     */
    private static String purchaseKey(long playerId, String clientKey) {
        return "buy:" + playerId + ":" + clientKey;
    }

    /** What a purchase key bought: the item, and what it cost. */
    public record Purchase(String itemId, long price) { }

    /**
     * The purchase this player made with {@code clientKey}, or null if the key is unused. A
     * plain read, for answering a retry before anything else is checked (04 §8).
     */
    public Purchase findPurchase(long playerId, String clientKey) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ref, -delta FROM ledger WHERE idem_key = ?")) {
                ps.setString(1, purchaseKey(playerId, clientKey));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Purchase(rs.getString(1), rs.getLong(2)) : null;
                }
            }
        });
    }

    /** A player's balances. */
    public record Wallet(long coins, long gems) { }

    /** @return the player's balances, or null if the player does not exist */
    public Wallet wallet(long playerId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coins, gems FROM player WHERE id = ?")) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Wallet(rs.getLong(1), rs.getLong(2)) : null;
                }
            }
        });
    }

    /** One kind of item a player holds. */
    public record Holding(String itemId, int qty, int level) { }

    /** Everything the player holds, by item id. */
    public List<Holding> inventory(long playerId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id, qty, item_level FROM inventory_item"
                            + " WHERE player_id = ? AND qty > 0 ORDER BY item_id")) {
                ps.setLong(1, playerId);
                List<Holding> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Holding(rs.getString(1), rs.getInt(2), rs.getInt(3)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /**
     * Grants currency, for rewards. The key must be derived from the source, never random, and
     * be unique across every player, as the ledger's index is: include the player, as the
     * match reward's {@code match:{uid}:{playerId}} does.
     */
    public Outcome credit(long playerId, long amount, int reason, String ref, String idemKey)
            throws SQLException {
        return credit(playerId, amount, reason, ref, idemKey, CURRENCY_COINS);
    }

    /** As {@link #credit(long, long, int, String, String)}, in either currency: a tournament's gems (04 §8). */
    public Outcome credit(long playerId, long amount, int reason, String ref, String idemKey, int currency)
            throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("credit must be positive: " + amount);
        }
        return tx.execute(c -> move(c, playerId, currency, amount, reason, ref, idemKey));
    }

    /**
     * The one way a coin balance moves, in the caller's transaction (D-13).
     *
     * <ol>
     * <li>Lock the player's row. Every writer of a balance, and so of a ledger row for it,
     *     takes this lock first; everything below is serialised by it.</li>
     * <li>If this key has already moved the balance, say so — <em>before</em> the funds
     *     check. The other way round, a retried purchase whose first attempt had spent the
     *     balance below the price was told INSUFFICIENT_FUNDS: charged once, as promised,
     *     and told it had failed.</li>
     * <li>Refuse to go below zero.</li>
     * <li>Write the ledger row, then the balance.</li>
     * </ol>
     *
     * The key check is a plain read, not {@code FOR UPDATE}: a locking read of a key that does
     * not exist takes a gap lock, and two transactions that gap-lock then insert nearby keys
     * deadlock — exactly what concurrent match rewards do. The plain read is enough because
     * of step 1: a transaction that wrote this key held this lock, so it committed before this
     * one got it, and before this read. The unique index stays as the backstop, matched on
     * 1062 alone; other integrity failures are real errors and are thrown.
     */
    static Outcome moveCoins(Connection c, long playerId, long delta, int reason, String ref,
                             String idemKey) throws SQLException {
        return move(c, playerId, CURRENCY_COINS, delta, reason, ref, idemKey);
    }

    /** As {@link #moveCoins}, for either balance: coins or gems, each with its ledger rows (04 §8). */
    static Outcome move(Connection c, long playerId, int currency, long delta, int reason, String ref,
                        String idemKey) throws SQLException {
        long balance = lockAndRead(c, playerId, currency);
        if (keyUsed(c, idemKey)) {
            return Outcome.ALREADY_APPLIED;
        }
        long after = balance + delta;
        if (after < 0) {
            return Outcome.INSUFFICIENT_FUNDS;
        }
        try {
            insertLedger(c, playerId, currency, delta, after, reason, ref, idemKey);
        } catch (SQLException e) {
            if (e.getErrorCode() == DUPLICATE_ENTRY) {
                return Outcome.ALREADY_APPLIED;
            }
            throw e;
        }
        updateBalance(c, playerId, currency, delta);
        return Outcome.APPLIED;
    }

    private static boolean keyUsed(Connection c, String idemKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ledger WHERE idem_key = ?")) {
            ps.setString(1, idemKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long coins(long playerId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT coins FROM player WHERE id = ?")) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    /** A player whose coins or gems disagree with the sum of their ledger rows in that currency. */
    public record Mismatch(long playerId, long coins, long coinsLedger, long gems, long gemsLedger) { }

    /** How many players disagree, and the first few of them, by id. */
    public record Reconciliation(int count, List<Mismatch> samples) { }

    /** How many player ids one reconciliation statement covers: each well inside the pool's socket limit (06 §7). */
    static final int RECONCILE_RANGE = 10_000;

    /** Every player whose coins or gems the ledger does not explain (06 §3; 05 §9); see {@link #reconcile(int, int)}. */
    public Reconciliation reconcile(int samples) throws SQLException {
        return reconcile(samples, RECONCILE_RANGE);
    }

    /**
     * Every player whose coins or gems the ledger does not explain, a range of player ids at a time.
     *
     * Each range is one statement, so one consistent snapshot of its players: a balance and its ledger rows are
     * written in one transaction, and a single InnoDB read sees both or neither, so a check running during play
     * cannot report a payment half made. Both currencies in one read of the range's ledger rows, by
     * ix_ledger_player. One statement over the whole ledger, as before, would at full size have run past the pool's
     * 30 s socket limit and never finished, retried every hour (D-44). The count is every mismatch, however few
     * samples are asked for: each range's comes from a window over its whole result.
     */
    Reconciliation reconcile(int samples, int rangeSize) throws SQLException {
        String sql = """
                SELECT p.id, p.coins, COALESCE(l.coins, 0), p.gems, COALESCE(l.gems, 0), COUNT(*) OVER ()
                  FROM player p
                  LEFT JOIN (SELECT player_id,
                                    SUM(IF(currency = %d, delta, 0)) AS coins,
                                    SUM(IF(currency = %d, delta, 0)) AS gems
                               FROM ledger WHERE player_id >= ? AND player_id < ? GROUP BY player_id) l
                    ON l.player_id = p.id
                 WHERE p.id >= ? AND p.id < ? AND (p.coins <> COALESCE(l.coins, 0) OR p.gems <> COALESCE(l.gems, 0))
                 ORDER BY p.id
                 LIMIT ?
                """.formatted(CURRENCY_COINS, CURRENCY_GEMS);
        long top = tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM player");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
        int want = Math.max(1, samples);
        List<Mismatch> found = new ArrayList<>();
        int count = 0;
        for (long from = 0; from <= top; from += rangeSize) {
            long lo = from;
            long hi = from + rangeSize;
            int[] inRange = {0};
            List<Mismatch> page = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, lo);
                    ps.setLong(2, hi);
                    ps.setLong(3, lo);
                    ps.setLong(4, hi);
                    ps.setInt(5, want);
                    List<Mismatch> rows = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            inRange[0] = rs.getInt(6);
                            rows.add(new Mismatch(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                                    rs.getLong(5)));
                        }
                    }
                    return rows;
                }
            });
            count += inRange[0];
            for (Mismatch m : page) {
                if (found.size() < want) {
                    found.add(m);
                }
            }
        }
        return new Reconciliation(count, List.copyOf(found));
    }

    /** Re-derives the balance from the ledger. A mismatch means a balance moved without a row. */
    public long ledgerSum(long playerId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE player_id = ? AND currency = "
                            + CURRENCY_COINS)) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static long lockAndReadCoins(Connection c, long playerId) throws SQLException {
        return lockAndRead(c, playerId, CURRENCY_COINS);
    }

    private static String column(int currency) {
        return currency == CURRENCY_GEMS ? "gems" : "coins";
    }

    private static long lockAndRead(Connection c, long playerId, int currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column(currency) + " FROM player WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("no such player: " + playerId);
                }
                return rs.getLong(1);
            }
        }
    }

    private static void updateBalance(Connection c, long playerId, int currency, long delta) throws SQLException {
        String column = column(currency);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE player SET " + column + " = " + column + " + ? WHERE id = ?")) {
            ps.setLong(1, delta);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    private static void insertLedger(Connection c, long playerId, int currency, long delta, long after,
                                     int reason, String ref, String idemKey) throws SQLException {
        String sql = """
                INSERT INTO ledger
                  (player_id, currency, delta, balance_after, reason, ref, idem_key)
                VALUES (?, %d, ?, ?, ?, ?, ?)
                """.formatted(currency);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, delta);
            ps.setLong(3, after);
            ps.setInt(4, reason);
            ps.setString(5, ref);
            ps.setString(6, idemKey);
            ps.executeUpdate();
        }
    }

    private static void grantItem(Connection c, long playerId, String itemId, int qty)
            throws SQLException {
        String sql = """
                INSERT INTO inventory_item (player_id, item_id, qty) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE qty = qty + VALUES(qty)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemId);
            ps.setInt(3, qty);
            ps.executeUpdate();
        }
    }
}
