package com.backend.platform;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.backend.common.RefusedConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the shop sells (docs detailed-design/04-platform-services.md §8, "The shop, as built").
 *
 * Read once, at start, from {@code shop.json} on the classpath: it ships in the release, so
 * every instance sells the same things at the same prices. Checked strictly, and a catalogue
 * that breaks a rule stops the process rather than selling something wrong. That includes an
 * unknown field, so that a misspelt {@code requireLevel} is not silently no requirement, and
 * {@code stock}, which the design has and this does not build.
 */
public final class Catalogue {

    /** The account level cap, {@code AccountLevels.MAX_LEVEL} in worker. */
    static final int MAX_LEVEL = 100;
    static final long MAX_PRICE = 1_000_000_000L;
    /** {@code inventory_item.item_id} is 40 characters. */
    static final int MAX_ITEM_ID = 40;
    static final int MAX_SKU = 64;

    /**
     * Lower case only: item ids are keys in columns that compare without regard to case, where
     * two ids differing only in case would be one item.
     */
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    private static final Set<String> FIELDS =
            Set.of("sku", "itemId", "price", "requiresLevel", "availableFrom", "availableTo", "currency");

    /** An offer's currency (04 §8). */
    public static final int COINS = 0;
    public static final int GEMS = 1;

    /** One thing on sale. {@code availableFrom} and {@code availableTo} may be null: open ended. */
    public record Offer(String sku, String itemId, long price, int requiresLevel,
                        Instant availableFrom, Instant availableTo, int currency) {

        public Offer(String sku, String itemId, long price, int requiresLevel, Instant availableFrom, Instant availableTo) {
            this(sku, itemId, price, requiresLevel, availableFrom, availableTo, COINS);
        }

        /** On sale at {@code now}: from its start, inclusive, to its end, exclusive. */
        public boolean onSaleAt(Instant now) {
            return (availableFrom == null || !now.isBefore(availableFrom))
                    && (availableTo == null || now.isBefore(availableTo));
        }
    }

    private final Map<String, Offer> bySku;

    private Catalogue(Map<String, Offer> bySku) {
        this.bySku = bySku;
    }

    /** {@code shop.json} from the classpath. */
    public static Catalogue fromClasspath() {
        try (InputStream in = Catalogue.class.getResourceAsStream("/shop.json")) {
            if (in == null) {
                throw new RefusedConfiguration("no shop.json on the classpath");
            }
            return read(in);
        } catch (IOException e) {
            throw new RefusedConfiguration("cannot read shop.json: " + e.getMessage(), e);
        }
    }

    /** A catalogue from any stream: tests, and a tool checking a file before a release. */
    public static Catalogue read(InputStream in) throws IOException {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new RefusedConfiguration("shop.json is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new RefusedConfiguration("shop.json must be a list of offers");
        }
        Map<String, Offer> bySku = new LinkedHashMap<>();
        for (int i = 0; i < root.size(); i++) {
            Offer offer = offer(root.get(i), "offer " + (i + 1));
            if (bySku.putIfAbsent(offer.sku(), offer) != null) {
                throw new RefusedConfiguration("shop.json: sku " + offer.sku() + " appears twice");
            }
        }
        return new Catalogue(java.util.Collections.unmodifiableMap(bySku));   // keeps the order
    }

    private static Offer offer(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new RefusedConfiguration("shop.json: " + where + " is not an object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if ("stock".equals(name)) {
                throw new RefusedConfiguration("shop.json: " + where + " sets stock, and limited"
                        + " stock is not built (04 §8); nothing would hold the limit");
            }
            if (!FIELDS.contains(name)) {
                throw new RefusedConfiguration("shop.json: " + where + " has an unknown field, "
                        + name);
            }
        }
        String sku = id(node, "sku", MAX_SKU, where);
        where = "sku " + sku;
        String itemId = id(node, "itemId", MAX_ITEM_ID, where);
        long price = whole(node, "price", 1, MAX_PRICE, -1, where);
        int requiresLevel = (int) whole(node, "requiresLevel", 1, MAX_LEVEL, 1, where);
        Instant from = instant(node, "availableFrom", where);
        Instant to = instant(node, "availableTo", where);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new RefusedConfiguration("shop.json: " + where
                    + " ends before it starts, or as it does");
        }
        JsonNode named = node.get("currency");                // coins unless said (04 §8, plan item 68)
        int currency = named == null ? COINS
                : named.isTextual() && named.asText().equals("coins") ? COINS
                : named.isTextual() && named.asText().equals("gems") ? GEMS : -1;
        if (currency < 0) {
            throw new RefusedConfiguration("shop.json: " + where + ": currency must be coins or gems");
        }
        return new Offer(sku, itemId, price, requiresLevel, from, to, currency);
    }

    private static String id(JsonNode node, String field, int maxLength, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().length() > maxLength
                || !ID.matcher(value.asText()).matches()) {
            throw new RefusedConfiguration("shop.json: " + where + ": " + field + " must be 1 to "
                    + maxLength + " lowercase letters, digits or _");
        }
        return value.asText();
    }

    /** {@code absent} when the field is missing; required when {@code absent} is negative. */
    private static long whole(JsonNode node, String field, long min, long max, long absent,
                              String where) {
        JsonNode value = node.get(field);
        if (value == null && absent >= 0) {
            return absent;
        }
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < min
                || value.asLong() > max) {
            throw new RefusedConfiguration("shop.json: " + where + ": " + field
                    + " must be a whole number from " + min + " to " + max);
        }
        return value.asLong();
    }

    private static Instant instant(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            throw new RefusedConfiguration("shop.json: " + where + ": " + field
                    + " must be an instant such as 2026-10-01T00:00:00Z, not " + value);
        }
    }

    /** Every offer names an item in the table; one that does not stops the start (04 §8). */
    public void checkItems(Items items) {
        for (Offer offer : bySku.values()) {
            if (items.find(offer.itemId()) == null) {
                throw new RefusedConfiguration("shop.json: sku " + offer.sku() + " sells "
                        + offer.itemId() + ", which items.json does not have");
            }
        }
    }

    /** @return the offer, or null if no offer has that sku */
    public Offer find(String sku) {
        return bySku.get(sku);
    }

    /** Every offer on sale at {@code now}, in the order the catalogue lists them. */
    public List<Offer> onSaleAt(Instant now) {
        List<Offer> out = new ArrayList<>();
        for (Offer offer : bySku.values()) {
            if (offer.onSaleAt(now)) {
                out.add(offer);
            }
        }
        return out;
    }
}
