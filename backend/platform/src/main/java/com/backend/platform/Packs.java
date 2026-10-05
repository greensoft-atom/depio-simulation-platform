package com.backend.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
 * The packs of gems sold for money (docs detailed-design/04-platform-services.md §8, revenue, D-67).
 *
 * Read once, at start, from {@code packs.json} on the classpath, and checked as strictly as the shop's
 * {@link Catalogue}: a pack that breaks a rule stops the process rather than selling something wrong.
 * Priced in US dollars, in cents.
 */
public final class Packs {

    /** Every pack is priced in it. */
    public static final String CURRENCY = "USD";
    static final int MAX = 100_000;
    /** {@code payment_order.product_id} is 64 characters. */
    static final int MAX_PRODUCT_ID = 64;

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1," + MAX_PRODUCT_ID + "}");
    private static final Set<String> FIELDS = Set.of("productId", "gems", "priceCents");

    public record Pack(String productId, int gems, int priceCents) { }

    private final Map<String, Pack> byId;

    private Packs(Map<String, Pack> byId) {
        this.byId = byId;
    }

    /** {@code packs.json} from the classpath. */
    public static Packs fromClasspath() {
        try (InputStream in = Packs.class.getResourceAsStream("/packs.json")) {
            if (in == null) {
                throw new RefusedConfiguration("no packs.json on the classpath");
            }
            return read(in);
        } catch (IOException e) {
            throw new RefusedConfiguration("cannot read packs.json: " + e.getMessage(), e);
        }
    }

    public static Packs read(InputStream in) throws IOException {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new RefusedConfiguration("packs.json is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new RefusedConfiguration("packs.json must be a list of packs");
        }
        Map<String, Pack> byId = new LinkedHashMap<>();
        for (int i = 0; i < root.size(); i++) {
            Pack pack = pack(root.get(i), "pack " + (i + 1));
            if (byId.putIfAbsent(pack.productId(), pack) != null) {
                throw new RefusedConfiguration("packs.json: " + pack.productId() + " appears twice");
            }
        }
        return new Packs(Collections.unmodifiableMap(byId));
    }

    private static Pack pack(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new RefusedConfiguration("packs.json: " + where + " is not an object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                throw new RefusedConfiguration("packs.json: " + where + " has an unknown field, " + name);
            }
        }
        JsonNode id = node.get("productId");
        if (id == null || !id.isTextual() || !ID.matcher(id.asText()).matches()) {
            throw new RefusedConfiguration("packs.json: " + where + ": productId must be 1 to " + MAX_PRODUCT_ID
                    + " lowercase letters, digits or _");
        }
        where = id.asText();
        return new Pack(id.asText(), whole(node, "gems", where), whole(node, "priceCents", where));
    }

    private static int whole(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1
                || value.asInt() > MAX) {
            throw new RefusedConfiguration("packs.json: " + where + ": " + field + " must be a whole number from 1 to "
                    + MAX);
        }
        return value.asInt();
    }

    /** Every pack, in the order the file lists them. */
    public List<Pack> all() {
        return new ArrayList<>(byId.values());
    }

    /** @return the pack, or null */
    public Pack find(String productId) {
        return byId.get(productId);
    }
}
