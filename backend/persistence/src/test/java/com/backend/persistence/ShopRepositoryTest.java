package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.backend.persistence.ShopRepository.Offer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shop's offers in the database and what a player bought (docs 04 §8, D-119, plan item 145), against a real MySQL. */
class ShopRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL", DatabaseSettings.TEST_URL);
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final int COINS = EconomyRepository.CURRENCY_COINS;
    private static final int GEMS = EconomyRepository.CURRENCY_GEMS;

    private static Database db;
    private static ShopRepository shop;
    private static AdminRepository admin;
    private static EconomyRepository economy;
    private static AccountRepository accounts;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        shop = new ShopRepository(db.dataSource());
        admin = new AdminRepository(db.dataSource());
        economy = new EconomyRepository(db.dataSource());
        accounts = new AccountRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    private static List<String> audit() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT action, target, request, outcome FROM admin_audit ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("the migration seeds the release's twelve offers, in the order shop.json listed them")
    void theReleasesOffers() throws Exception {
        List<Offer> offers = shop.offers();
        assertThat(offers).extracting(Offer::sku).containsExactly("treads_light", "barrel_steel", "armor_plate",
                "barrel_rifled", "core_capacitor", "boost_xp_hour", "boost_coins_hour", "skin_crimson", "skin_azure",
                "skin_jade", "skin_gold", "skin_carbon");
        assertThat(offers.get(3)).isEqualTo(new Offer("barrel_rifled", "barrel_rifled", 2_000, COINS, 5, null, null));
        assertThat(offers.get(10)).isEqualTo(new Offer("skin_gold", "skin_gold", 400, GEMS, 1, null, null));
        assertThat(shop.offer("skin_gold")).isEqualTo(offers.get(10));
        assertThat(shop.offer("skin_none")).isNull();
    }

    @Test
    @DisplayName("an offer is made, replaced whole in its place, and removed, each audited with its outcome in its transaction")
    void anOfferEdited() throws Exception {
        Instant from = Instant.parse("2026-12-01T00:00:00.250Z");
        Instant to = Instant.parse("2027-01-01T00:00:00Z");
        Offer sale = new Offer("skin_gold_sale", "skin_gold", 300, GEMS, 3, from, to);
        assertThat(admin.putOffer(sale, "{\"reason\":\"december\"}")).isEqualTo(ShopRepository.Saved.CREATED);
        assertThat(shop.offer("skin_gold_sale")).as("its times to the millisecond").isEqualTo(sale);
        assertThat(shop.offers()).extracting(Offer::sku).endsWith("skin_carbon", "skin_gold_sale");

        Offer cheaper = new Offer("barrel_steel", "barrel_steel", 1_200, COINS, 1, null, to);
        assertThat(admin.putOffer(cheaper, "r2")).isEqualTo(ShopRepository.Saved.REPLACED);
        assertThat(shop.offer("barrel_steel")).isEqualTo(cheaper);
        assertThat(shop.offers().get(1)).as("in its place").isEqualTo(cheaper);
        Offer plain = new Offer("barrel_steel", "barrel_steel", 1_500, COINS, 1, null, null);
        admin.putOffer(plain, "r3");
        assertThat(shop.offer("barrel_steel")).as("replaced whole: its end gone").isEqualTo(plain);

        assertThat(admin.removeOffer("skin_gold_sale", "r4")).isTrue();
        assertThat(admin.removeOffer("skin_gold_sale", "r5")).isFalse();
        assertThat(shop.offer("skin_gold_sale")).isNull();
        assertThat(shop.offers()).hasSize(12);
        assertThat(audit()).containsExactly(
                "shop-offer skin_gold_sale {\"reason\":\"december\"} created",
                "shop-offer barrel_steel r2 replaced",
                "shop-offer barrel_steel r3 replaced",
                "shop-offer-remove skin_gold_sale r4 removed",
                "shop-offer-remove skin_gold_sale r5 no_such_offer");
    }

    @Test
    @DisplayName("what a player bought, newest first, a page at a time: no one else's, no other row, from its own index")
    void whatWasBought() throws Exception {
        long ada = accounts.createForSubject("ada", "ada");
        long bob = accounts.createForSubject("bob", "bob");
        economy.credit(ada, 1_000, EconomyRepository.REASON_MATCH_REWARD, "test", "test:ada");
        economy.credit(bob, 1_000, EconomyRepository.REASON_MATCH_REWARD, "test", "test:bob");
        economy.credit(ada, 50, EconomyRepository.REASON_MILESTONE, "m", "m:ada", GEMS);
        economy.purchase(ada, "barrel_steel", 100, "k1", COINS, true);
        economy.purchase(bob, "armor_plate", 100, "k1", COINS, true);
        economy.purchase(ada, "boost_xp_hour", 20, "k2", GEMS, false);
        economy.raiseLevel(ada, "barrel_steel", "k3");
        economy.purchase(ada, "skin_jade", 300, "k4", COINS, true);

        List<EconomyRepository.Bought> all = economy.purchases(ada, Long.MAX_VALUE, 50);
        assertThat(all).extracting(EconomyRepository.Bought::ref).containsExactly("skin_jade", "boost_xp_hour", "barrel_steel");
        assertThat(all.get(1).price()).isEqualTo(20);
        assertThat(all.get(1).currency()).isEqualTo(GEMS);
        assertThat(all.get(0).at()).isNotNull();
        List<EconomyRepository.Bought> first = economy.purchases(ada, Long.MAX_VALUE, 2);
        assertThat(first).hasSize(2);
        assertThat(economy.purchases(ada, first.get(1).id(), 2)).extracting(EconomyRepository.Bought::ref)
                .containsExactly("barrel_steel");
        assertThat(economy.purchases(ada, all.get(2).id(), 2)).isEmpty();

        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("EXPLAIN SELECT id FROM ledger WHERE player_id = ? AND reason = 1"
                     + " AND id < ? ORDER BY id DESC LIMIT 50")) {
            ps.setLong(1, ada);
            ps.setLong(2, Long.MAX_VALUE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString("key")).as("read from its own index").isEqualTo("ix_ledger_player_reason");
            }
        }
    }
}
