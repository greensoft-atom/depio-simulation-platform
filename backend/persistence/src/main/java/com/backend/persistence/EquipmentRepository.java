package com.backend.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * What a player wears (docs detailed-design/04-platform-services.md §8, "Equipment"): a row a
 * worn slot in {@code equipment} (V1). Whether the item is held is {@code inventory_item}'s.
 */
public final class EquipmentRepository {

    /** A worn slot: its item, whether the player still holds one, and its level, 1 to 5, as held (04 §8, plan item 67). */
    public record Worn(int slot, String itemId, boolean owned, int level) {

        public Worn(int slot, String itemId, boolean owned) {
            this(slot, itemId, owned, 1);
        }
    }

    private final Tx tx;

    public EquipmentRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /** Every worn slot, by slot. */
    public List<Worn> worn(long playerId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT e.slot, e.item_id, COALESCE(i.qty, 0) > 0, COALESCE(i.item_level, 1)
                      FROM equipment e
                      LEFT JOIN inventory_item i ON i.player_id = e.player_id AND i.item_id = e.item_id
                     WHERE e.player_id = ?
                     ORDER BY e.slot""")) {
                ps.setLong(1, playerId);
                List<Worn> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Worn(rs.getInt(1), rs.getString(2), rs.getBoolean(3), rs.getInt(4)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /**
     * Wears the item in the slot, replacing what was there, if the player holds one.
     *
     * @return false, and nothing changed, when the player holds none
     */
    public boolean wear(long playerId, int slot, String itemId) throws SQLException {
        return tx.execute(c -> {
            // The player first, as a purchase locks them, then what they hold (D-37, 06 §6).
            try (PreparedStatement lock = c.prepareStatement("SELECT id FROM player WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, playerId);
                lock.executeQuery().close();
            }
            try (PreparedStatement held = c.prepareStatement(
                    "SELECT qty FROM inventory_item WHERE player_id = ? AND item_id = ? FOR SHARE")) {
                held.setLong(1, playerId);
                held.setString(2, itemId);
                try (ResultSet rs = held.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) <= 0) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO equipment (player_id, slot, item_id) VALUES (?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE item_id = VALUES(item_id)")) {
                ps.setLong(1, playerId);
                ps.setInt(2, slot);
                ps.setString(3, itemId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Empties the slot; an empty one stays empty. */
    public void takeOff(long playerId, int slot) throws SQLException {
        tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM equipment WHERE player_id = ? AND slot = ?")) {
                ps.setLong(1, playerId);
                ps.setInt(2, slot);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
