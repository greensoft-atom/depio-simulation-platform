package com.backend.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.backend.common.RefusedConfiguration;
import com.backend.sim.Stat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What an item does (docs detailed-design/04-platform-services.md §8, "Equipment" and "Boosts"):
 * equipment, worn in one of four slots and giving a whole percent in one to three stats; or a
 * boost, activated, raising a match's experience or coins by a percent for some minutes.
 *
 * Read once, at start, from {@code items.json} on the classpath, and checked as strictly as the
 * shop's catalogue: a table that breaks a rule stops the process, an unknown field included.
 * Only {@code platform} reads it; the arena is given the resolved bonus in the ticket (D-37).
 */
public final class Items {

    public static final int BARREL = 0;
    public static final int ARMOR = 1;
    public static final int CORE = 2;
    public static final int TREADS = 3;
    /** The skin's slot, after the four that give a bonus (04 §8, D-70). */
    public static final int SKIN = 4;
    public static final int SLOTS = 5;

    /** A skin's number on the wire, its event's {@code u8}. */
    static final int MAX_SKIN = 255;

    /** A skin's number on the wire, and its item. */
    public record Skin(int skin, String itemId) { }

    /** The most one item, and all of them together, give a stat (04 §8). */
    public static final int MAX_PERCENT = 25;
    static final int MAX_MODIFIERS = 3;

    /** A boost's kinds (Q-13). */
    public static final int XP = 0;
    public static final int COINS = 1;
    static final int MAX_BOOST_PERCENT = 100;
    static final int MAX_BOOST_MINUTES = 1_440;
    private static final String[] BOOST_KINDS = {"xp", "coins"};

    private static final String[] SLOT_NAMES = {"barrel", "armor", "core", "treads", "skin"};
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Set<String> FIELDS = Set.of("id", "type", "slot", "modifiers");
    private static final Set<String> BOOST_FIELDS = Set.of("id", "type", "kind", "percent", "minutes");
    private static final Set<String> SKIN_FIELDS = Set.of("id", "type", "skin");
    private static final Set<String> MODIFIER_FIELDS = Set.of("stat", "percent");

    /** A boost's kind, percent and minutes. */
    public record Boost(int kind, int percent, int minutes) { }

    /**
     * One item. Equipment: its slot, and a whole percent a stat, indexed as {@link Stat}; {@code boost}
     * null. A boost: {@code boost}, slot -1, and no stats.
     */
    public record Item(String id, int slot, byte[] percents, Boost boost, int skin) {
        public Item(String id, int slot, byte[] percents, Boost boost) {
            this(id, slot, percents, boost, 0);
        }
    }

    private final Map<String, Item> byId;

    private Items(Map<String, Item> byId) {
        this.byId = byId;
    }

    /** {@code items.json} from the classpath. */
    public static Items fromClasspath() {
        try (InputStream in = Items.class.getResourceAsStream("/items.json")) {
            if (in == null) {
                throw new RefusedConfiguration("no items.json on the classpath");
            }
            return read(in);
        } catch (IOException e) {
            throw new RefusedConfiguration("cannot read items.json: " + e.getMessage(), e);
        }
    }

    /** An item table from any stream: tests, and a tool checking a file before a release. */
    public static Items read(InputStream in) throws IOException {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new RefusedConfiguration("items.json is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new RefusedConfiguration("items.json must be a list of items");
        }
        Map<String, Item> byId = new LinkedHashMap<>();
        for (int i = 0; i < root.size(); i++) {
            Item item = item(root.get(i), "item " + (i + 1));
            if (byId.putIfAbsent(item.id(), item) != null) {
                throw new RefusedConfiguration("items.json: id " + item.id() + " appears twice");
            }
            if (item.skin() != 0 && byId.values().stream().filter(other -> other.skin() == item.skin()).count() > 1) {
                throw new RefusedConfiguration("items.json: skin " + item.skin() + " appears twice");
            }
        }
        return new Items(java.util.Collections.unmodifiableMap(byId));
    }

    /** The item, or null. */
    public Item find(String id) {
        return byId.get(id);
    }

    public Collection<Item> all() {
        return byId.values();
    }

    /** Every skin, by its number. */
    public List<Skin> skins() {
        List<Skin> skins = new java.util.ArrayList<>();
        for (Item item : byId.values()) {
            if (item.skin() != 0) {
                skins.add(new Skin(item.skin(), item.id()));
            }
        }
        skins.sort(java.util.Comparator.comparingInt(Skin::skin));
        return skins;
    }

    /** A slot by its name, or -1. */
    public static int slotOf(String name) {
        return List.of(SLOT_NAMES).indexOf(name);
    }

    public static String slotName(int slot) {
        return SLOT_NAMES[slot];
    }

    /** A boost's kind by its name, or -1. */
    public static int boostKindOf(String name) {
        return List.of(BOOST_KINDS).indexOf(name);
    }

    public static String boostKindName(int kind) {
        return BOOST_KINDS[kind];
    }

    /** A stat's name in the table and the API: its name, words joined by {@code _}. */
    public static String statName(int stat) {
        return Stat.name(stat).replace(' ', '_');
    }

    private static Item item(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new RefusedConfiguration("items.json: " + where + " is not an object");
        }
        String type = node.path("type").asText("");
        if (!"EQUIPMENT".equals(type) && !"BOOST".equals(type) && !"SKIN".equals(type)) {
            throw new RefusedConfiguration("items.json: " + where
                    + ": type must be EQUIPMENT, BOOST or SKIN, the types built (04 §8)");
        }
        unknownFields(node, "BOOST".equals(type) ? BOOST_FIELDS : "SKIN".equals(type) ? SKIN_FIELDS : FIELDS, where);
        JsonNode id = node.get("id");
        if (id == null || !id.isTextual() || id.asText().length() > Catalogue.MAX_ITEM_ID
                || !ID.matcher(id.asText()).matches()) {
            throw new RefusedConfiguration("items.json: " + where + ": id must be 1 to "
                    + Catalogue.MAX_ITEM_ID + " lowercase letters, digits or _");
        }
        where = "item " + id.asText();
        if ("BOOST".equals(type)) {
            return boost(id.asText(), node, where);
        }
        if ("SKIN".equals(type)) {
            // How a tank is drawn, and nothing else: its own slot, no stats (D-70).
            return new Item(id.asText(), SKIN, new byte[Stat.COUNT], null, whole(node, "skin", MAX_SKIN, where));
        }
        int slot = slotOf(node.path("slot").asText(""));
        if (slot < 0 || slot == SKIN) {
            throw new RefusedConfiguration("items.json: " + where + ": slot must be one of "
                    + String.join(", ", List.of(SLOT_NAMES).subList(0, SKIN)));
        }
        JsonNode modifiers = node.get("modifiers");
        if (modifiers == null || !modifiers.isArray() || modifiers.isEmpty()
                || modifiers.size() > MAX_MODIFIERS) {
            throw new RefusedConfiguration("items.json: " + where + ": modifiers must list 1 to "
                    + MAX_MODIFIERS + " stats");
        }
        byte[] percents = new byte[Stat.COUNT];
        for (JsonNode m : modifiers) {
            if (!m.isObject()) {
                throw new RefusedConfiguration("items.json: " + where + ": a modifier is not an object");
            }
            unknownFields(m, MODIFIER_FIELDS, where);
            int stat = statOf(m.path("stat").asText(""));
            if (stat < 0) {
                throw new RefusedConfiguration("items.json: " + where + ": stat must be one of "
                        + String.join(", ", statNames()));
            }
            JsonNode percent = m.get("percent");
            if (percent == null || !percent.isIntegralNumber() || percent.asLong() < 1
                    || percent.asLong() > MAX_PERCENT) {
                throw new RefusedConfiguration("items.json: " + where + ": percent must be a whole"
                        + " number from 1 to " + MAX_PERCENT);
            }
            if (percents[stat] != 0) {
                throw new RefusedConfiguration("items.json: " + where + ": " + statName(stat)
                        + " appears twice");
            }
            percents[stat] = (byte) percent.asInt();
        }
        return new Item(id.asText(), slot, percents, null);
    }

    private static Item boost(String id, JsonNode node, String where) {
        int kind = boostKindOf(node.path("kind").asText(""));
        if (kind < 0) {
            throw new RefusedConfiguration("items.json: " + where + ": kind must be one of "
                    + String.join(", ", BOOST_KINDS));
        }
        int percent = whole(node, "percent", MAX_BOOST_PERCENT, where);
        int minutes = whole(node, "minutes", MAX_BOOST_MINUTES, where);
        return new Item(id, -1, new byte[Stat.COUNT], new Boost(kind, percent, minutes));
    }

    private static int whole(JsonNode node, String field, int max, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 1 || value.asLong() > max) {
            throw new RefusedConfiguration("items.json: " + where + ": " + field
                    + " must be a whole number from 1 to " + max);
        }
        return value.asInt();
    }

    private static void unknownFields(JsonNode node, Set<String> known, String where) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new RefusedConfiguration("items.json: " + where + " has an unknown field, " + name);
            }
        }
    }

    private static int statOf(String name) {
        return statNames().indexOf(name);
    }

    private static List<String> statNames() {
        List<String> names = new ArrayList<>(Stat.COUNT);
        for (int s = 0; s < Stat.COUNT; s++) {
            names.add(statName(s));
        }
        return names;
    }
}
