package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.util.List;

import com.backend.persistence.Database;
import com.backend.persistence.DatabaseSettings;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.ShopRepository;
import com.backend.persistence.ShopRepository.Offer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An offer's rules, as the admin API checks them before it writes one, and the release's offers (04 §8, D-119). */
class CatalogueTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Items ITEMS = Items.fromClasspath();
    private static final int COINS = EconomyRepository.CURRENCY_COINS;
    private static final int GEMS = EconomyRepository.CURRENCY_GEMS;

    private static Offer offer(String sku, String json) throws Exception {
        return Catalogue.offer(sku, JSON.readTree(json), ITEMS);
    }

    @Test
    @DisplayName("an offer is read with its defaults; one is on sale in its dates, the end exclusive, and only while the release has its item")
    void readsAnOffer() throws Exception {
        assertThat(offer("plain", "{\"itemId\":\"barrel_steel\",\"price\":100,\"reason\":\"why\"}"))
                .isEqualTo(new Offer("plain", "barrel_steel", 100, COINS, 1, null, null));
        Offer dated = offer("dated", "{\"sku\":\"dated\",\"itemId\":\"skin_gold\",\"price\":500,\"currency\":\"gems\","
                + "\"requiresLevel\":10,\"availableFrom\":\"2026-10-01T00:00:00Z\",\"availableTo\":\"2026-11-01T00:00:00Z\"}");
        assertThat(dated).isEqualTo(new Offer("dated", "skin_gold", 500, GEMS, 10,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")));
        assertThat(offer("few", "{\"itemId\":\"skin_gold\",\"price\":500,\"stock\":1000000}").stock())
                .as("a limit, up to a million (D-121)").isEqualTo(1_000_000);
        assertThat(offer("few", "{\"itemId\":\"skin_gold\",\"price\":500,\"stock\":null}").stock()).isNull();
        Offer gone = new Offer("gone", "dropped_item", 5, COINS, 1, null, null);
        List<Offer> all = List.of(offer("plain", "{\"itemId\":\"barrel_steel\",\"price\":100}"), dated, gone);
        assertThat(Catalogue.onSale(all, ITEMS, Instant.parse("2026-09-30T23:59:59Z"))).extracting(Offer::sku)
                .containsExactly("plain");
        assertThat(Catalogue.onSale(all, ITEMS, Instant.parse("2026-10-01T00:00:00Z"))).extracting(Offer::sku)
                .containsExactly("plain", "dated");
        assertThat(Catalogue.onSale(all, ITEMS, Instant.parse("2026-11-01T00:00:00Z"))).as("the end is exclusive")
                .extracting(Offer::sku).containsExactly("plain");
    }

    @Test
    @DisplayName("an offer that breaks a rule is refused, naming what is wrong")
    void refusesWhatItCannotHonour() {
        // A limit is 1 to 1 000 000, a whole number (D-121).
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"stock\":0}", "stock");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"stock\":1000001}", "stock");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"stock\":\"10\"}", "stock");
        // A misspelling is not silently "no requirement".
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"requireLevel\":5}", "requireLevel");
        // Free is a reward, not a purchase, and a free offer is claimable once per key.
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":0}", "price");
        refused("a", "{\"itemId\":\"barrel_steel\"}", "price");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":99999999999999999999999}", "price");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":\"100\"}", "price");
        // Item ids are keys in columns that ignore case: Hat and hat would be one item.
        refused("a", "{\"itemId\":\"Hat\",\"price\":1}", "itemId");
        refused("a", "{\"itemId\":\"" + "x".repeat(41) + "\",\"price\":1}", "itemId");
        refused("a", "{\"price\":1}", "itemId");
        refused("a", "{\"itemId\":\"ghost\",\"price\":1}", "items.json");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"requiresLevel\":101}", "requiresLevel");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"availableFrom\":\"2026-11-01T00:00:00Z\","
                + "\"availableTo\":\"2026-10-01T00:00:00Z\"}", "after availableFrom");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"availableTo\":\"next week\"}", "availableTo");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"currency\":\"gold\"}", "currency");
        refused("a", "{\"itemId\":\"barrel_steel\",\"price\":1,\"currency\":1}", "currency");
        refused("a", "{\"sku\":\"b\",\"itemId\":\"barrel_steel\",\"price\":1}", "path");
        refused("A", "{\"itemId\":\"barrel_steel\",\"price\":1}", "sku");
        refused("x".repeat(65), "{\"itemId\":\"barrel_steel\",\"price\":1}", "sku");
        refused("a", "[]", "object");
    }

    @Test
    @DisplayName("the release's offers, as V4 seeds them: each by the admin's rules and of the release's items; the equipment for coins, worn, the boosts at 20 gems, the skins at 150 and 400 (plan items 68, 75 (c))")
    void theReleasesOffers() throws Exception {
        try (Database db = new Database(System.getenv().getOrDefault("JDBC_URL", DatabaseSettings.TEST_URL),
                System.getenv().getOrDefault("DB_USER", "backend"),
                System.getenv().getOrDefault("DB_PASSWORD", "backend-dev-password"), 2)) {
            db.resetForTests();
            List<Offer> offers = new ShopRepository(db.dataSource()).offers();
            for (Offer o : offers) {
                JsonNode asWritten = JSON.createObjectNode().put("itemId", o.itemId()).put("price", o.price())
                        .put("currency", Catalogue.currencyName(o.currency())).put("requiresLevel", o.requiresLevel());
                assertThat(Catalogue.offer(o.sku(), asWritten, ITEMS)).as(o.sku()).isEqualTo(o);
            }
            assertThat(Catalogue.onSale(offers, ITEMS, Instant.now())).as("every one on sale").hasSize(12);
            assertThat(offers).filteredOn(o -> o.currency() == COINS)
                    .extracting(Offer::itemId, Offer::price, Offer::requiresLevel)
                    .containsExactly(tuple("treads_light", 1_200L, 1), tuple("barrel_steel", 1_500L, 1),
                            tuple("armor_plate", 1_500L, 1), tuple("barrel_rifled", 2_000L, 5),
                            tuple("core_capacitor", 2_000L, 5));
            assertThat(offers).filteredOn(o -> o.currency() == COINS)
                    .allSatisfy(o -> assertThat(ITEMS.find(o.itemId()).boost()).as(o.itemId() + " is worn").isNull());
            assertThat(offers).filteredOn(o -> o.currency() == GEMS).extracting(Offer::itemId, Offer::price)
                    .containsExactly(tuple("boost_xp_hour", 20L), tuple("boost_coins_hour", 20L),
                            tuple("skin_crimson", 150L), tuple("skin_azure", 150L), tuple("skin_jade", 150L),
                            tuple("skin_gold", 400L), tuple("skin_carbon", 400L));
        }
    }

    private static void refused(String sku, String json, String naming) {
        assertThatThrownBy(() -> offer(sku, json)).as(json).isInstanceOf(Catalogue.Invalid.class)
                .hasMessageContaining(naming);
    }
}
