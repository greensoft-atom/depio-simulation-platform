package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.backend.persistence.EconomyRepository.Outcome;
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

    /** A player of the account service's with {@code coins}, as a reward would give them. */
    private static long buyer(String name, long coins) throws SQLException {
        long id = accounts.createForSubject(name, name);
        economy.credit(id, coins, EconomyRepository.REASON_MATCH_REWARD, "test", "test:" + name);
        return id;
    }

    @Test
    @DisplayName("an offer's limit is written and read; a replace keeps what was sold, and what is left is never below 0 (D-121)")
    void anOffersLimit() throws Exception {
        Offer limited = new Offer("jade_three", "skin_jade", 100, COINS, 1, null, null, 3, 0);
        admin.putOffer(limited, "r1");
        assertThat(shop.offer("jade_three")).isEqualTo(limited);
        assertThat(shop.offer("jade_three").left()).isEqualTo(3);
        long ada = buyer("ada", 1_000);
        assertThat(economy.purchase(ada, "skin_jade", 100, "k1", COINS, true, "jade_three")).isEqualTo(Outcome.APPLIED);
        assertThat(shop.offer("jade_three").sold()).as("counted in the purchase").isEqualTo(1);
        long bob = buyer("bob", 1_000);
        assertThat(economy.purchase(bob, "skin_jade", 100, "k2", COINS, true, "jade_three")).isEqualTo(Outcome.APPLIED);

        admin.putOffer(new Offer("jade_three", "skin_jade", 80, COINS, 1, null, null, 5, 0), "r2");
        assertThat(shop.offer("jade_three")).as("replaced whole, its sales kept")
                .isEqualTo(new Offer("jade_three", "skin_jade", 80, COINS, 1, null, null, 5, 2));
        admin.putOffer(new Offer("jade_three", "skin_jade", 80, COINS, 1, null, null, 1, 0), "r3");
        assertThat(shop.offer("jade_three").left()).as("a limit below what was sold, 2 of 1, leaves none").isZero();
        admin.putOffer(new Offer("jade_three", "skin_jade", 80, COINS, 1, null, null), "r4");
        assertThat(List.of(shop.offer("jade_three").stock() == null, shop.offer("jade_three").sold(), shop.offer("jade_three").left()))
                .as("no limit: its count kept, nothing to hold").containsExactly(true, 2, -1);
        assertThat(shop.offers().get(0).stock()).as("the release's, unlimited").isNull();
    }

    @Test
    @DisplayName("a limited offer sells what it has and no more: eight buyers at once for three (D-121)")
    void eightBuyersForThree() throws Exception {
        admin.putOffer(new Offer("boost_three", "boost_xp_hour", 10, COINS, 1, null, null, 3, 0), "r");
        long[] buyers = new long[8];
        for (int i = 0; i < buyers.length; i++) {
            buyers[i] = buyer("b" + i, 100);
        }
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Outcome>> all = new ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(buyers.length)) {
            for (long b : buyers) {
                all.add(pool.submit(() -> {
                    go.await();
                    return economy.purchase(b, "boost_xp_hour", 10, "tap-" + b, COINS, false, "boost_three");
                }));
            }
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var f : all) {
                outcomes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(o -> o == Outcome.APPLIED).hasSize(3);
            assertThat(outcomes).filteredOn(o -> o == Outcome.SOLD_OUT).hasSize(5);
        }
        assertThat(shop.offer("boost_three").sold()).isEqualTo(3);
        long charged = 0;
        for (long b : buyers) {
            charged += 100 - economy.coins(b);
        }
        assertThat(charged).as("three charged, ten each").isEqualTo(30);
    }

    @Test
    @DisplayName("a refusal takes no stock, for want of funds or an item held; a retry of the last one sold is still applied (D-121)")
    void refusalsTakeNoStock() throws Exception {
        admin.putOffer(new Offer("jade_two", "skin_jade", 100, COINS, 1, null, null, 2, 0), "r");
        long poor = buyer("poor", 50);
        long ada = buyer("ada", 1_000);
        long bob = buyer("bob", 1_000);
        long cy = buyer("cy", 1_000);
        assertThat(economy.purchase(poor, "skin_jade", 100, "p1", COINS, true, "jade_two")).isEqualTo(Outcome.INSUFFICIENT_FUNDS);
        assertThat(economy.purchase(ada, "skin_jade", 100, "a1", COINS, true, "jade_two")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.purchase(ada, "skin_jade", 100, "a2", COINS, true, "jade_two")).isEqualTo(Outcome.ALREADY_OWNED);
        assertThat(shop.offer("jade_two").sold()).as("one sold; neither refusal took one").isEqualTo(1);
        assertThat(economy.purchase(bob, "skin_jade", 100, "b1", COINS, true, "jade_two")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.purchase(bob, "skin_jade", 100, "b1", COINS, true, "jade_two")).as("the key before the stock")
                .isEqualTo(Outcome.ALREADY_APPLIED);
        assertThat(economy.purchase(cy, "skin_jade", 100, "c1", COINS, true, "jade_two")).isEqualTo(Outcome.SOLD_OUT);
        assertThat(List.of(economy.coins(cy), (long) shop.offer("jade_two").sold())).as("nothing moved for the last")
                .containsExactly(1_000L, 2L);

        admin.putOffer(new Offer("boost_two", "boost_xp_hour", 10, COINS, 1, null, null, 2, 0), "r");
        assertThat(List.of(economy.purchase(cy, "boost_xp_hour", 10, "c2", COINS, false, "boost_two"),
                economy.purchase(cy, "boost_xp_hour", 10, "c3", COINS, false, "boost_two")))
                .as("a boost, not held once: one player may take both").containsExactly(Outcome.APPLIED, Outcome.APPLIED);

        // Its limit removed by the operator while a purchase was between its read and its transaction: sold, not counted.
        admin.putOffer(new Offer("boost_two", "boost_xp_hour", 10, COINS, 1, null, null), "r");
        long sold = shop.offer("boost_two").sold();
        assertThat(economy.purchase(cy, "boost_xp_hour", 10, "c4", COINS, false, "boost_two")).isEqualTo(Outcome.APPLIED);
        assertThat(shop.offer("boost_two").sold()).as("counted only while limited").isEqualTo(sold);
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
