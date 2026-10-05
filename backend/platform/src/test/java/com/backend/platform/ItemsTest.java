package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.backend.common.RefusedConfiguration;
import com.backend.sim.Stat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What an item does: the equipment table (docs 04 §8, "Equipment"). */
class ItemsTest {

    private static Items read(String json) throws Exception {
        return Items.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String item(String fields) {
        return "[{\"id\":\"a\",\"type\":\"EQUIPMENT\",\"slot\":\"barrel\","
                + "\"modifiers\":[{\"stat\":\"bullet_damage\",\"percent\":8}]" + fields + "}]";
    }

    @Test
    @DisplayName("items are read with their slot and what they give, a whole percent a stat")
    void readsItems() throws Exception {
        Items items = read("""
                [ {"id":"barrel_steel","type":"EQUIPMENT","slot":"barrel",
                   "modifiers":[{"stat":"bullet_damage","percent":8}]},
                  {"id":"core_twin","type":"EQUIPMENT","slot":"core",
                   "modifiers":[{"stat":"reload","percent":5},{"stat":"bullet_speed","percent":10}]} ]
                """);
        Items.Item steel = items.find("barrel_steel");
        assertThat(steel.slot()).isEqualTo(Items.BARREL);
        assertThat(steel.percents()[Stat.BULLET_DAMAGE]).isEqualTo((byte) 8);
        assertThat(steel.percents()[Stat.RELOAD]).isZero();
        Items.Item twin = items.find("core_twin");
        assertThat(twin.slot()).isEqualTo(Items.CORE);
        assertThat(twin.percents()[Stat.RELOAD]).isEqualTo((byte) 5);
        assertThat(twin.percents()[Stat.BULLET_SPEED]).isEqualTo((byte) 10);
        assertThat(items.find("nope")).isNull();
        assertThat(Items.slotOf("treads")).isEqualTo(Items.TREADS);
        assertThat(Items.slotOf("hat")).isNegative();
        assertThat(Items.slotName(Items.ARMOR)).isEqualTo("armor");
    }

    @Test
    @DisplayName("a boost item: its kind, percent and minutes; no slot, no stats")
    void readsBoosts() throws Exception {
        Items items = read("""
                [ {"id":"boost_xp","type":"BOOST","kind":"xp","percent":100,"minutes":60},
                  {"id":"boost_coins","type":"BOOST","kind":"coins","percent":50,"minutes":1440} ]
                """);
        Items.Boost xp = items.find("boost_xp").boost();
        assertThat((Object) xp).isEqualTo(new Items.Boost(Items.XP, 100, 60));
        assertThat(items.find("boost_xp").slot()).as("not worn").isNegative();
        assertThat(items.find("boost_coins").boost()).isEqualTo(new Items.Boost(Items.COINS, 50, 1_440));
        assertThat(Items.boostKindOf("coins")).isEqualTo(Items.COINS);
        assertThat(Items.boostKindName(Items.XP)).isEqualTo("xp");
    }

    @Test
    @DisplayName("the release's items load, at least one for every slot")
    void theShippedItemsLoad() {
        Items items = Items.fromClasspath();
        for (int slot = 0; slot < Items.SLOTS; slot++) {
            final int s = slot;
            assertThat(items.all()).as(Items.slotName(slot)).anyMatch(i -> i.slot() == s);
        }
        assertThat(items.all()).as("and a boost of each kind").anyMatch(i -> i.boost() != null && i.boost().kind() == Items.XP)
                .anyMatch(i -> i.boost() != null && i.boost().kind() == Items.COINS);
    }

    @Test
    @DisplayName("an item table that breaks a rule stops the start, naming what is wrong")
    void refusesWhatItCannotHonour() {
        refused("{}", "list");
        refused(item(",\"colour\":\"red\""), "colour");
        refused(item("").replace("EQUIPMENT", "HAT"), "type");
        refused(item("").replace("\"barrel\"", "\"hat\""), "slot");
        refused(item("").replace("bullet_damage", "luck"), "stat");
        refused(item("").replace("\"percent\":8", "\"percent\":0"), "percent");
        refused(item("").replace("\"percent\":8", "\"percent\":26"), "percent");
        refused(item("").replace("[{\"stat\":\"bullet_damage\",\"percent\":8}]", "[]"), "modifiers");
        refused(item("").replace("{\"stat\":\"bullet_damage\",\"percent\":8}",
                "{\"stat\":\"reload\",\"percent\":1},{\"stat\":\"max_health\",\"percent\":1},"
                        + "{\"stat\":\"body_damage\",\"percent\":1},{\"stat\":\"bullet_speed\",\"percent\":1}"),
                "modifiers");
        refused(item("").replace("{\"stat\":\"bullet_damage\",\"percent\":8}",
                "{\"stat\":\"reload\",\"percent\":1},{\"stat\":\"reload\",\"percent\":2}"), "twice");
        refused(item("").replace("\"id\":\"a\"", "\"id\":\"Hat\""), "id");
        refused(item("").replace("\"id\":\"a\"", "\"id\":\"" + "x".repeat(41) + "\""), "id");
        refused("[" + item("").substring(1, item("").length() - 1) + "," + item("").substring(1), "twice");
        refused(boost("\"kind\":\"luck\""), "kind");
        refused(boost("\"percent\":0"), "percent");
        refused(boost("\"percent\":101"), "percent");
        refused(boost("\"minutes\":0"), "minutes");
        refused(boost("\"minutes\":1441"), "minutes");
        refused(boost("\"slot\":\"barrel\""), "slot");         // a boost is not worn
        refused(item(",\"minutes\":60"), "minutes");                 // nor equipment timed
    }

    @Test
    @DisplayName("a skin: its number on the wire, worn in a slot of its own; no stats, no boost; each number once (04 §8, D-70)")
    void readsSkins() throws Exception {
        Items items = read("[{\"id\":\"skin_max\",\"type\":\"SKIN\",\"skin\":255},"
                + "{\"id\":\"skin_red\",\"type\":\"SKIN\",\"skin\":1}]");
        Items.Item red = items.find("skin_red");
        assertThat(java.util.List.of(red.slot(), red.skin())).containsExactly(Items.SKIN, 1);
        assertThat(red.percents()).containsOnly((byte) 0);
        assertThat(red.boost()).isNull();
        assertThat(java.util.List.of(Items.slotName(Items.SKIN), Items.slotOf("skin"))).containsExactly("skin", Items.SKIN);
        assertThat(items.skins()).as("by number").containsExactly(new Items.Skin(1, "skin_red"), new Items.Skin(255, "skin_max"));
        assertThat(read(item("")).find("a").skin()).as("equipment has none").isZero();

        refused("[{\"id\":\"s\",\"type\":\"SKIN\",\"skin\":0}]", "skin");
        refused("[{\"id\":\"s\",\"type\":\"SKIN\",\"skin\":256}]", "skin");
        refused("[{\"id\":\"s\",\"type\":\"SKIN\"}]", "skin");
        refused("[{\"id\":\"s\",\"type\":\"SKIN\",\"skin\":\"1\"}]", "skin");
        refused("[{\"id\":\"s\",\"type\":\"SKIN\",\"skin\":1},{\"id\":\"t\",\"type\":\"SKIN\",\"skin\":1}]", "twice");
        refused("[{\"id\":\"s\",\"type\":\"SKIN\",\"skin\":1,\"modifiers\":[]}]", "modifiers");
        refused(item("").replace("\"barrel\"", "\"skin\""), "slot");          // equipment never in the skin's slot
    }

    @Test
    @DisplayName("the release's skins: five, numbered 1 to 5")
    void theShippedSkins() {
        assertThat(Items.fromClasspath().skins()).containsExactly(new Items.Skin(1, "skin_crimson"),
                new Items.Skin(2, "skin_azure"), new Items.Skin(3, "skin_jade"), new Items.Skin(4, "skin_gold"),
                new Items.Skin(5, "skin_carbon"));
    }

    /** A boost with one field replaced, or added. */
    private static String boost(String field) {
        String name = field.substring(0, field.indexOf(':'));
        String base = "{\"id\":\"b\",\"type\":\"BOOST\",\"kind\":\"xp\",\"percent\":100,\"minutes\":60}";
        String replaced = base.replaceAll(java.util.regex.Pattern.quote(name) + ":[^,}]+", java.util.regex.Matcher.quoteReplacement(field));
        return "[" + (replaced.equals(base) ? base.substring(0, base.length() - 1) + "," + field + "}" : replaced) + "]";
    }

    @Test
    @DisplayName("a shop offer for an item the table lacks stops the start; the release's pass")
    void offersNameItems() throws Exception {
        Items items = read(item(""));
        Catalogue ghost = Catalogue.read(new ByteArrayInputStream(
                "[{\"sku\":\"g\",\"itemId\":\"ghost\",\"price\":1}]".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> ghost.checkItems(items)).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("ghost");
        Catalogue real = Catalogue.read(new ByteArrayInputStream(
                "[{\"sku\":\"g\",\"itemId\":\"a\",\"price\":1}]".getBytes(StandardCharsets.UTF_8)));
        real.checkItems(items);
        Catalogue.fromClasspath().checkItems(Items.fromClasspath());
    }

    private static void refused(String json, String naming) {
        assertThatThrownBy(() -> read(json)).as(json).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining(naming);
    }
}
