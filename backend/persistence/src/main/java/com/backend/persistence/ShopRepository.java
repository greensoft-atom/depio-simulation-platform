package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The shop's offers (docs 04 §8, D-119): rows of {@code shop_offer}, which the admin API writes through
 * {@link AdminRepository}, audited in the same transaction, and every platform reads at each listing and each
 * purchase. Their rules are the platform's {@code Catalogue}'s, checked before a write; this stores and reads.
 */
public final class ShopRepository {

    /**
     * One thing on sale, its {@code currency} as the ledger's ({@link EconomyRepository#CURRENCY_COINS} or
     * {@link EconomyRepository#CURRENCY_GEMS}); {@code availableFrom} and {@code availableTo} may be null, open ended.
     * {@code stock} is how many may be sold in all, null for no limit, and {@code sold} the purchases counted while it
     * has one (D-121).
     */
    public record Offer(String sku, String itemId, long price, int currency, int requiresLevel,
                        Instant availableFrom, Instant availableTo, Integer stock, int sold) {

        /** An offer with no limit. */
        public Offer(String sku, String itemId, long price, int currency, int requiresLevel,
                     Instant availableFrom, Instant availableTo) {
            this(sku, itemId, price, currency, requiresLevel, availableFrom, availableTo, null, 0);
        }

        /** What is left of a limited offer, never below 0; -1 for one with no limit. */
        public int left() {
            return stock == null ? -1 : Math.max(0, stock - sold);
        }

        /** On sale at {@code now}: from its start, inclusive, to its end, exclusive. */
        public boolean onSaleAt(Instant now) {
            return (availableFrom == null || !now.isBefore(availableFrom))
                    && (availableTo == null || now.isBefore(availableTo));
        }
    }

    public enum Saved { CREATED, REPLACED }

    private static final String COLUMNS =
            "sku, item_id, price, currency, requires_level, available_from, available_to, stock, sold";

    private final Tx tx;

    public ShopRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /** Every offer, on sale or not, in the order they were added. */
    public List<Offer> offers() throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM shop_offer ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                List<Offer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(offer(rs));
                }
                return List.copyOf(out);
            }
        });
    }

    /** @return the offer with that sku, or null */
    public Offer offer(String sku) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM shop_offer WHERE sku = ?")) {
                ps.setString(1, sku);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? offer(rs) : null;
                }
            }
        });
    }

    /** The offer made, or replaced whole in its place, inside the caller's transaction. */
    static Saved put(Connection c, Offer o) throws SQLException {
        boolean existed;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM shop_offer WHERE sku = ? FOR UPDATE")) {
            ps.setString(1, o.sku());
            try (ResultSet rs = ps.executeQuery()) {
                existed = rs.next();
            }
        }
        // sold is not written: a replace keeps the sales counted (D-121).
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_offer (sku, item_id, price, currency,"
                + " requires_level, available_from, available_to, stock"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS n ON DUPLICATE KEY UPDATE item_id = n.item_id, price = n.price,"
                + " currency = n.currency, requires_level = n.requires_level, available_from = n.available_from,"
                + " available_to = n.available_to, stock = n.stock")) {
            ps.setString(1, o.sku());
            ps.setString(2, o.itemId());
            ps.setLong(3, o.price());
            ps.setInt(4, o.currency());
            ps.setInt(5, o.requiresLevel());
            instant(ps, 6, o.availableFrom());
            instant(ps, 7, o.availableTo());
            if (o.stock() == null) {
                ps.setNull(8, Types.INTEGER);
            } else {
                ps.setInt(8, o.stock());
            }
            ps.executeUpdate();
        }
        return existed ? Saved.REPLACED : Saved.CREATED;
    }

    /** The offer removed, inside the caller's transaction; false when there was none. */
    static boolean remove(Connection c, String sku) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM shop_offer WHERE sku = ?")) {
            ps.setString(1, sku);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Whether a limited offer has one left, its row locked for the rest of the caller's transaction (D-121); false for
     * an offer gone since it was read.
     */
    static boolean inStock(Connection c, String sku) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT stock, sold FROM shop_offer WHERE sku = ? FOR UPDATE")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                int stock = rs.getInt(1);
                return rs.wasNull() || rs.getInt(2) < stock;
            }
        }
    }

    /** A sale of a limited offer counted, inside the transaction that took its price; its row locked by inStock. */
    static void sell(Connection c, String sku) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE shop_offer SET sold = sold + 1 WHERE sku = ? AND stock IS NOT NULL")) {
            ps.setString(1, sku);
            ps.executeUpdate();
        }
    }

    private static Offer offer(ResultSet rs) throws SQLException {
        Timestamp from = rs.getTimestamp(6);
        Timestamp to = rs.getTimestamp(7);
        int stock = rs.getInt(8);
        Integer limit = rs.wasNull() ? null : stock;
        return new Offer(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), limit, rs.getInt(9));
    }

    private static void instant(PreparedStatement ps, int index, Instant at) throws SQLException {
        if (at == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(at));
        }
    }
}
