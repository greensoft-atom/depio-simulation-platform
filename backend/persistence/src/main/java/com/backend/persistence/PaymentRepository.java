package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Gems for money (docs detailed-design/04-platform-services.md §8, revenue, D-68): an order made for a
 * pack and confirmed by its payment provider, simulated until the owner chooses one (Q-52). An order
 * moves only forward, each step under its row's lock: pending to paid or declined, paid to refunded,
 * pending to expired. A paid order is granted once, through the ledger's one path, a player's first
 * twice; a refund is taken back as far as the balance allows, and the rest is a debt that blocks
 * ordering until an operator clears it.
 */
public final class PaymentRepository {

    public static final int PENDING = 0;
    public static final int PAID = 1;
    public static final int DECLINED = 2;
    public static final int REFUNDED = 3;
    public static final int EXPIRED = 4;

    /** How long an order waits for its provider before retention expires it. */
    public static final Duration PENDING_FOR = Duration.ofDays(1);

    public record Order(String id, long playerId, String productId, int gems, int priceCents, String currency,
                        String provider, int state, int bonus, int debt, Instant createdAt) { }

    /** An order made, or found for a retried ask; none while the player owes a refund's debt. */
    public record Placed(Order order, boolean inDebt) { }

    /** An order after a confirm: whether this call confirmed it, and the player's gems after. */
    public record Confirmed(Order order, boolean now, long gemsBalance) { }

    /** An order after a refund: whether this call refunded it, the gems taken back, and the debt left. */
    public record Refunded(Order order, boolean now, int taken, int debt) { }

    private static final String COLUMNS = "id, player_id, product_id, gems, price_cents, currency, provider, state,"
            + " bonus, debt, created_at";

    private final Tx tx;

    public PaymentRepository(DataSource ds) {
        this.tx = new Tx(ds);
    }

    /** An order for a pack, pending; the same one for the same client key. */
    public Placed place(long player, String clientKey, String product, int gems, int priceCents, String currency,
                        String provider, Instant now) throws SQLException {
        return tx.execute(c -> {
            lockPlayer(c, player);
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM payment_order"
                    + " WHERE player_id = ? AND client_key = ?")) {
                ps.setLong(1, player);
                ps.setString(2, clientKey);
                Order known = one(ps);
                if (known != null) {
                    return new Placed(known, false);
                }
            }
            if (debtOf(c, player) > 0) {
                return new Placed(null, true);
            }
            String id = UUID.randomUUID().toString();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO payment_order (id, player_id, client_key, product_id,"
                    + " gems, price_cents, currency, provider, state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setLong(2, player);
                ps.setString(3, clientKey);
                ps.setString(4, product);
                ps.setInt(5, gems);
                ps.setInt(6, priceCents);
                ps.setString(7, currency);
                ps.setString(8, provider);
                ps.setInt(9, PENDING);
                ps.setTimestamp(10, Timestamp.from(now));
                ps.executeUpdate();
            }
            return new Placed(new Order(id, player, product, gems, priceCents, currency, provider, PENDING, 0, 0,
                    now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)), false);
        });
    }

    /** @return the order, or null */
    public Order get(String id) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM payment_order WHERE id = ?")) {
                ps.setString(1, id);
                return one(ps);
            }
        });
    }

    /**
     * The provider's word on a pending order (D-68): paid grants its gems (reason 9, keyed by the order)
     * and, on the player's first paid order, as many again: keyed by the player, and ledger rows are never
     * deleted, so once ever. Declined grants nothing. Final: a confirm of an order no longer pending
     * answers what it is.
     *
     * @return null for no such order
     */
    public Confirmed confirm(String id, boolean paid, Instant now) throws SQLException {
        return tx.execute(c -> {
            Order o = lockOrder(c, id);
            if (o == null) {
                return null;
            }
            if (o.state() != PENDING) {
                return new Confirmed(o, false, gemsOf(c, o.playerId()));
            }
            lockPlayer(c, o.playerId());
            int bonus = 0;
            if (paid) {
                String key = "payment:" + id;
                EconomyRepository.move(c, o.playerId(), EconomyRepository.CURRENCY_GEMS, o.gems(),
                        EconomyRepository.REASON_PAYMENT, key, key);
                if (EconomyRepository.move(c, o.playerId(), EconomyRepository.CURRENCY_GEMS, o.gems(),
                        EconomyRepository.REASON_PAYMENT, key, "payment:first:" + o.playerId())
                        == EconomyRepository.Outcome.APPLIED) {
                    bonus = o.gems();
                }
            }
            int state = paid ? PAID : DECLINED;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE payment_order SET state = ?, bonus = ?, confirmed_at = ? WHERE id = ?")) {
                ps.setInt(1, state);
                ps.setInt(2, bonus);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setString(4, id);
                ps.executeUpdate();
            }
            Order after = new Order(o.id(), o.playerId(), o.productId(), o.gems(), o.priceCents(), o.currency(),
                    o.provider(), state, bonus, 0, o.createdAt());
            return new Confirmed(after, true, gemsOf(c, o.playerId()));
        });
    }

    /**
     * A paid order refunded, once: its gems and its bonus taken back as far as the balance allows (reason
     * 10); what could not be taken is its debt. In the caller's transaction, which audits it
     * ({@link AdminRepository#refund}, D-30).
     *
     * @return null for no such order; not {@code now} for one that is not paid
     */
    static Refunded refund(Connection c, String id, Instant now) throws SQLException {
        Order o = lockOrder(c, id);
        if (o == null) {
            return null;
        }
        if (o.state() != PAID) {
            return new Refunded(o, false, 0, o.debt());
        }
        lockPlayer(c, o.playerId());
        int total = o.gems() + o.bonus();
        int taken = (int) Math.min(total, gemsOf(c, o.playerId()));
        if (taken > 0) {
            EconomyRepository.move(c, o.playerId(), EconomyRepository.CURRENCY_GEMS, -taken,
                    EconomyRepository.REASON_PAYMENT_REFUND, "payment:" + id, "refund:" + id);
        }
        int debt = total - taken;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment_order SET state = ?, debt = ?, refunded_at = ? WHERE id = ?")) {
            ps.setInt(1, REFUNDED);
            ps.setInt(2, debt);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setString(4, id);
            ps.executeUpdate();
        }
        Order after = new Order(o.id(), o.playerId(), o.productId(), o.gems(), o.priceCents(), o.currency(),
                o.provider(), REFUNDED, o.bonus(), debt, o.createdAt());
        return new Refunded(after, true, taken, debt);
    }

    /** What the player owes for refunds: while above 0, no new order. */
    public long debtOf(long player) throws SQLException {
        return tx.execute(c -> debtOf(c, player));
    }

    /** An operator clears a player's debt, in the caller's transaction ({@link AdminRepository#clearDebt}). @return the debt cleared */
    static long clearDebt(Connection c, long player) throws SQLException {
        lockPlayer(c, player);
        long owed = debtOf(c, player);
        try (PreparedStatement ps = c.prepareStatement("UPDATE payment_order SET debt = 0 WHERE player_id = ? AND debt > 0")) {
            ps.setLong(1, player);
            ps.executeUpdate();
        }
        return owed;
    }

    /** Retention: orders pending since before {@code before} expire, {@code batch} at a time. @return how many */
    public int expirePending(Instant before, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int expired = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE payment_order SET state = ? WHERE state = ?"
                        + " AND created_at < ? LIMIT ?")) {
                    ps.setInt(1, EXPIRED);
                    ps.setInt(2, PENDING);
                    ps.setTimestamp(3, Timestamp.from(before));
                    ps.setInt(4, batch);
                    return ps.executeUpdate();
                }
            });
            total += expired;
            if (expired < batch) {
                return total;
            }
        }
    }

    private static long debtOf(Connection c, long player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(debt), 0) FROM payment_order WHERE player_id = ?")) {
            ps.setLong(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Order lockOrder(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM payment_order WHERE id = ? FOR UPDATE")) {
            ps.setString(1, id);
            return one(ps);
        }
    }

    private static void lockPlayer(Connection c, long player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM player WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, player);
            ps.executeQuery().close();
        }
    }

    private static long gemsOf(Connection c, long player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT gems FROM player WHERE id = ?")) {
            ps.setLong(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static Order one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new Order(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getString(6),
                    rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getTimestamp(11).toInstant());
        }
    }
}
