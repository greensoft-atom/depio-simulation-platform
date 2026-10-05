package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.backend.common.RefusedConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogueTest {

    private static Catalogue read(String json) throws Exception {
        return Catalogue.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("offers are read with their defaults, and only those on sale are offered")
    void readsOffers() throws Exception {
        Catalogue c = read("""
                [ {"sku":"hat","itemId":"cosmetic_hat","price":100},
                  {"sku":"crown","itemId":"cosmetic_crown","price":500,"requiresLevel":10,
                   "availableFrom":"2026-10-01T00:00:00Z","availableTo":"2026-11-01T00:00:00Z"} ]
                """);
        assertThat(c.find("hat").requiresLevel()).isEqualTo(1);
        assertThat(c.find("nope")).isNull();
        assertThat(c.onSaleAt(Instant.parse("2026-09-30T23:59:59Z")))
                .extracting(Catalogue.Offer::sku).containsExactly("hat");
        assertThat(c.onSaleAt(Instant.parse("2026-10-01T00:00:00Z")))
                .extracting(Catalogue.Offer::sku).containsExactly("hat", "crown");
        assertThat(c.onSaleAt(Instant.parse("2026-11-01T00:00:00Z")))
                .as("the end is exclusive").extracting(Catalogue.Offer::sku).containsExactly("hat");
    }

    @Test
    @DisplayName("the release's shop sells the five skins for gems: three at 150, two at 400 (04 §8, plan item 75 (c))")
    void theShippedShopSellsTheSkins() {
        assertThat(Catalogue.fromClasspath().onSaleAt(Instant.now())).filteredOn(o -> o.itemId().startsWith("skin_"))
                .extracting(Catalogue.Offer::itemId, Catalogue.Offer::price, Catalogue.Offer::currency)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("skin_crimson", 150L, Catalogue.GEMS),
                        org.assertj.core.groups.Tuple.tuple("skin_azure", 150L, Catalogue.GEMS),
                        org.assertj.core.groups.Tuple.tuple("skin_jade", 150L, Catalogue.GEMS),
                        org.assertj.core.groups.Tuple.tuple("skin_gold", 400L, Catalogue.GEMS),
                        org.assertj.core.groups.Tuple.tuple("skin_carbon", 400L, Catalogue.GEMS));
    }

    @Test
    @DisplayName("the release's catalogue loads")
    void theShippedCatalogueLoads() {
        assertThat(Catalogue.fromClasspath().onSaleAt(Instant.now())).isNotNull();
    }

    @Test
    @DisplayName("the release's shop sells each piece of equipment for coins, at 04 §8's first-cut prices (plan item 68)")
    void theShippedShopSellsTheEquipment() {
        java.util.List<Catalogue.Offer> offers = Catalogue.fromClasspath().onSaleAt(Instant.now());
        assertThat(offers).filteredOn(o -> o.currency() == Catalogue.COINS)
                .extracting(Catalogue.Offer::itemId, Catalogue.Offer::price, Catalogue.Offer::requiresLevel)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("treads_light", 1_200L, 1),
                        org.assertj.core.groups.Tuple.tuple("barrel_steel", 1_500L, 1),
                        org.assertj.core.groups.Tuple.tuple("armor_plate", 1_500L, 1),
                        org.assertj.core.groups.Tuple.tuple("barrel_rifled", 2_000L, 5),
                        org.assertj.core.groups.Tuple.tuple("core_capacitor", 2_000L, 5));
        Items items = Items.fromClasspath();
        assertThat(offers).filteredOn(o -> o.currency() == Catalogue.COINS)
                .allSatisfy(o -> assertThat(items.find(o.itemId()).boost()).as(o.itemId() + " is worn").isNull());
    }

    @Test
    @DisplayName("an offer may be priced in gems: the release's boosts, 20 each (plan item 68)")
    void anOfferInGems() throws Exception {
        assertThat(read("[{\"sku\":\"b\",\"itemId\":\"boost_xp_hour\",\"price\":20,\"currency\":\"gems\"}]")
                .find("b").currency()).isEqualTo(Catalogue.GEMS);
        assertThat(read("[{\"sku\":\"c\",\"itemId\":\"barrel_steel\",\"price\":20}]").find("c").currency())
                .as("coins, unless said").isEqualTo(Catalogue.COINS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> read("[{\"sku\":\"d\",\"itemId\":\"x\",\"price\":20,\"currency\":\"gold\"}]"))
                .hasMessageContaining("currency");
        assertThat(Catalogue.fromClasspath().onSaleAt(Instant.now()))
                .filteredOn(o -> o.currency() == Catalogue.GEMS && !o.itemId().startsWith("skin_"))      // skins: their own test
                .extracting(Catalogue.Offer::itemId, Catalogue.Offer::price)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("boost_xp_hour", 20L),
                        org.assertj.core.groups.Tuple.tuple("boost_coins_hour", 20L));
    }

    @Test
    @DisplayName("a catalogue that breaks a rule stops the start, naming what is wrong")
    void refusesWhatItCannotHonour() {
        // Limited stock is designed and not built: configured, nothing would hold the limit.
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":1,\"stock\":10}]", "stock");
        // A misspelling is not silently "no requirement".
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":1,\"requireLevel\":5}]", "requireLevel");
        // Free is a reward, not a purchase, and a free offer is claimable once per key.
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":0}]", "price");
        refused("[{\"sku\":\"a\",\"itemId\":\"a\"}]", "price");
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":99999999999999999999999}]", "price");
        // Item ids are keys in columns that ignore case: Hat and hat would be one item.
        refused("[{\"sku\":\"a\",\"itemId\":\"Hat\",\"price\":1}]", "itemId");
        refused("[{\"sku\":\"a\",\"itemId\":\"" + "x".repeat(41) + "\",\"price\":1}]", "itemId");
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":1},{\"sku\":\"a\",\"itemId\":\"b\",\"price\":1}]",
                "twice");
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":1,\"requiresLevel\":101}]", "requiresLevel");
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":1,\"availableFrom\":\"2026-11-01T00:00:00Z\","
                + "\"availableTo\":\"2026-10-01T00:00:00Z\"}]", "ends before it starts");
        refused("[{\"sku\":\"a\",\"itemId\":\"a\",\"price\":1,\"availableTo\":\"next week\"}]", "availableTo");
        refused("{\"sku\":\"a\"}", "list");
        refused("[{\"sku\":\"a\",", "not JSON");
    }

    private static void refused(String json, String naming) {
        assertThatThrownBy(() -> read(json)).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining(naming);
    }
}
