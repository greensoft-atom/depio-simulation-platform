package com.backend.platform;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.backend.persistence.EconomyRepository;
import com.backend.persistence.ShopRepository.Offer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An offer's rules (docs detailed-design/04-platform-services.md §8, D-119): what the admin API checks an offer by
 * before it is written, and what puts one on sale. The offers are rows of {@code shop_offer}
 * ({@link com.backend.persistence.ShopRepository}); {@code shop.json} held them until plan item 145.
 *
 * Checked strictly. An unknown field is refused, so that a misspelt {@code requireLevel} is not silently no
 * requirement, and so is {@code stock}, which the design has and this does not build.
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

    /** An offer's fields, and the admin call's reason beside them. */
    private static final Set<String> FIELDS = Set.of("sku", "itemId", "price", "requiresLevel", "availableFrom",
            "availableTo", "currency", "reason");

    /** An offer that breaks a rule, the rule its message. */
    public static final class Invalid extends IllegalArgumentException {
        Invalid(String message) {
            super(message);
        }
    }

    private Catalogue() { }

    /** Whether {@code sku} is one an offer may have: 1 to 64 lowercase letters, digits or _. */
    public static boolean validSku(String sku) {
        return sku != null && sku.length() <= MAX_SKU && ID.matcher(sku).matches();
    }

    /**
     * The offer {@code node} describes for {@code sku}, checked by every rule; its item must be one of
     * {@code items}. A field left out takes its default: level 1, coins, no start, no end.
     *
     * @throws Invalid with the rule broken
     */
    public static Offer offer(String sku, JsonNode node, Items items) {
        if (!validSku(sku)) {
            throw new Invalid("sku must be 1 to " + MAX_SKU + " lowercase letters, digits or _");
        }
        if (node == null || !node.isObject()) {
            throw new Invalid("the offer must be a JSON object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if ("stock".equals(name)) {
                throw new Invalid("stock: limited stock is not built (04 §8); nothing would hold the limit");
            }
            if (!FIELDS.contains(name)) {
                throw new Invalid("an unknown field, " + name);
            }
        }
        JsonNode named = node.get("sku");
        if (named != null && !sku.equals(named.asText())) {
            throw new Invalid("sku " + named.asText() + " is not the path's, " + sku);
        }
        JsonNode item = node.get("itemId");
        if (item == null || !item.isTextual() || item.asText().length() > MAX_ITEM_ID
                || !ID.matcher(item.asText()).matches()) {
            throw new Invalid("itemId must be 1 to " + MAX_ITEM_ID + " lowercase letters, digits or _");
        }
        String itemId = item.asText();
        if (items.find(itemId) == null) {
            throw new Invalid("itemId " + itemId + " is not an item of this release's items.json");
        }
        long price = whole(node, "price", 1, MAX_PRICE, -1);
        int requiresLevel = (int) whole(node, "requiresLevel", 1, MAX_LEVEL, 1);
        Instant from = instant(node, "availableFrom");
        Instant to = instant(node, "availableTo");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new Invalid("availableTo must be after availableFrom");
        }
        JsonNode currency = node.get("currency");                // coins unless said (04 §8, plan item 68)
        int paid = currency == null || currency.isNull() ? EconomyRepository.CURRENCY_COINS : currency(currency.asText());
        if (paid < 0) {
            throw new Invalid("currency must be coins or gems");
        }
        return new Offer(sku, itemId, price, paid, requiresLevel, from, to);
    }

    /** {@code coins} or {@code gems} as the ledger's currency; -1 for anything else. */
    public static int currency(String name) {
        return "coins".equals(name) ? EconomyRepository.CURRENCY_COINS
                : "gems".equals(name) ? EconomyRepository.CURRENCY_GEMS : -1;
    }

    /** The ledger's currency by its name in the API. */
    public static String currencyName(int currency) {
        return currency == EconomyRepository.CURRENCY_GEMS ? "gems" : "coins";
    }

    /** On sale at {@code now}: in its dates, and selling an item of {@code items}, which a release may have dropped. */
    public static boolean onSale(Offer offer, Items items, Instant now) {
        return offer.onSaleAt(now) && items.find(offer.itemId()) != null;
    }

    /** Every offer on sale at {@code now}, in their order. */
    public static List<Offer> onSale(List<Offer> offers, Items items, Instant now) {
        List<Offer> out = new ArrayList<>();
        for (Offer offer : offers) {
            if (onSale(offer, items, now)) {
                out.add(offer);
            }
        }
        return out;
    }

    /** {@code absent} when the field is missing; required when {@code absent} is negative. */
    private static long whole(JsonNode node, String field, long min, long max, long absent) {
        JsonNode value = node.get(field);
        if ((value == null || value.isNull()) && absent >= 0) {
            return absent;
        }
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < min
                || value.asLong() > max) {
            throw new Invalid(field + " must be a whole number from " + min + " to " + max);
        }
        return value.asLong();
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            throw new Invalid(field + " must be an instant such as 2026-10-01T00:00:00Z, not " + value);
        }
    }
}
