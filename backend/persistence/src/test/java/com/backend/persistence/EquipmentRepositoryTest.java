package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.backend.persistence.EquipmentRepository.Worn;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a player wears (docs 04 §8, "Equipment"), against a real MySQL as the rest of this module. */
class EquipmentRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static EquipmentRepository equipment;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        equipment = new EquipmentRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void freshSchema() {
        db.resetForTests();
    }

    private static long createPlayer(String name) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (username, password_hash) VALUES (?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setBytes(2, new byte[] {1, 2, 3});
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player (id, public_code, display_name) VALUES (?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, String.format("P%011d", id));
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return id;
        }
    }

    /** Sets how many of an item the player holds, 0 included. */
    private static void hold(long playerId, String itemId, int qty) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO inventory_item (player_id, item_id, qty) VALUES (?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE qty = VALUES(qty)")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemId);
            ps.setInt(3, qty);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("what is worn carries its level, for the bonus (04 §8, plan item 67)")
    void wornCarriesItsLevel() throws Exception {
        long ada = createPlayer("ada");
        hold(ada, "barrel_steel", 1);
        assertThat(equipment.wear(ada, 0, "barrel_steel")).isTrue();
        assertThat(equipment.worn(ada)).containsExactly(new Worn(0, "barrel_steel", true, 1));
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE inventory_item SET item_level = 4 WHERE player_id = ?")) {
            ps.setLong(1, ada);
            ps.executeUpdate();
        }
        assertThat(equipment.worn(ada)).containsExactly(new Worn(0, "barrel_steel", true, 4));
    }

    @Test
    @DisplayName("wearing locks the player first, as a purchase does, then reads what they hold (D-37, 06 §6)")
    void wearingLocksThePlayerFirst() throws Exception {
        long ada = createPlayer("ada");
        hold(ada, "barrel_steel", 1);
        RecordingDataSource recorded = new RecordingDataSource(db.dataSource());
        assertThat(new EquipmentRepository(recorded.dataSource).wear(ada, 0, "barrel_steel")).isTrue();
        assertThat(recorded.executed.get(0)).as("the player's row").startsWith("SELECT id FROM player WHERE id = ? FOR UPDATE");
        assertThat(recorded.executed.get(1)).as("then what they hold").contains("FROM inventory_item");
    }

    @Test
    @DisplayName("an item is worn only if held; a slot holds one, replaced by the next; wearing it again changes nothing")
    void wearingNeedsTheItem() throws Exception {
        long ada = createPlayer("ada");
        assertThat(equipment.worn(ada)).isEmpty();
        assertThat(equipment.wear(ada, 0, "barrel_steel")).as("not held").isFalse();
        assertThat(equipment.worn(ada)).isEmpty();

        hold(ada, "barrel_steel", 1);
        hold(ada, "barrel_rifled", 1);
        hold(ada, "core_capacitor", 1);
        assertThat(equipment.wear(ada, 0, "barrel_steel")).isTrue();
        assertThat(equipment.wear(ada, 0, "barrel_steel")).as("the same, again").isTrue();
        assertThat(equipment.wear(ada, 2, "core_capacitor")).isTrue();
        assertThat(equipment.worn(ada)).containsExactly(new Worn(0, "barrel_steel", true),
                new Worn(2, "core_capacitor", true));

        assertThat(equipment.wear(ada, 0, "barrel_rifled")).isTrue();
        assertThat(equipment.worn(ada)).as("replaced").containsExactly(new Worn(0, "barrel_rifled", true),
                new Worn(2, "core_capacitor", true));

        long bob = createPlayer("bob");
        assertThat(equipment.wear(bob, 0, "barrel_steel")).as("held by someone else").isFalse();
    }

    @Test
    @DisplayName("a slot taken off is empty; an item worn and no longer held is shown as not owned")
    void takingOffAndLosing() throws Exception {
        long ada = createPlayer("ada");
        hold(ada, "armor_plate", 1);
        hold(ada, "treads_light", 1);
        equipment.wear(ada, 1, "armor_plate");
        equipment.wear(ada, 3, "treads_light");

        equipment.takeOff(ada, 1);
        equipment.takeOff(ada, 1);                          // twice is the same as once
        assertThat(equipment.worn(ada)).containsExactly(new Worn(3, "treads_light", true));

        hold(ada, "treads_light", 0);
        assertThat(equipment.worn(ada)).containsExactly(new Worn(3, "treads_light", false));
        assertThat(equipment.wear(ada, 3, "treads_light")).as("none held any more").isFalse();
    }
}
