package com.jredis.server.command;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Command lookup by name, case-insensitive, without allocating: an open-addressing table keyed by a
 * case-folded hash of the name bytes. Built once at start-up, read-only afterwards.
 */
public final class CommandTable {

    private final Map<String, CommandSpec> byName = new LinkedHashMap<>();
    private CommandSpec[] slots;
    private byte[][] slotNames;
    private int mask;

    public void register(String name, int arity, int flags, CommandHandler handler) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (byName.put(upper, new CommandSpec(upper, arity, flags, handler)) != null) {
            throw new IllegalStateException("command registered twice: " + upper);
        }
    }

    /**
     * Marks disabled commands and builds the lookup table. A name that is no command is a
     * configuration error: a typo must not silently leave a command enabled.
     */
    public void freeze(Set<String> disabled) {
        for (String d : disabled) {
            String upper = d.toUpperCase(Locale.ROOT);
            CommandSpec spec = byName.get(upper);
            if (spec != null) {
                spec.disabled = true;
            } else if (!upper.equals("DEBUG")) {    // DEBUG exists only with enable-debug-command yes
                throw new IllegalArgumentException("disable-command: unknown command '" + d + "'");
            }
        }
        int size = 64;
        while (size < byName.size() * 4) {
            size <<= 1;
        }
        slots = new CommandSpec[size];
        slotNames = new byte[size][];
        mask = size - 1;
        for (CommandSpec spec : byName.values()) {
            byte[] name = spec.name.getBytes(StandardCharsets.US_ASCII);
            int i = foldedHash(name) & mask;
            while (slots[i] != null) {
                i = (i + 1) & mask;
            }
            slots[i] = spec;
            slotNames[i] = name;
        }
    }

    public CommandSpec lookup(byte[] name) {
        int i = foldedHash(name) & mask;
        while (slots[i] != null) {
            if (equalsFolded(slotNames[i], name)) {
                return slots[i];
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    public CommandSpec get(String name) {
        return byName.get(name.toUpperCase(Locale.ROOT));
    }

    /** Every command, including disabled ones. */
    public Collection<CommandSpec> all() {
        return Collections.unmodifiableCollection(byName.values());
    }

    /** The commands clients can use. */
    public List<CommandSpec> enabled() {
        List<CommandSpec> out = new ArrayList<>();
        for (CommandSpec spec : byName.values()) {
            if (!spec.disabled) {
                out.add(spec);
            }
        }
        return out;
    }

    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    private static int foldedHash(byte[] b) {
        int h = 0;
        for (byte x : b) {
            int c = x;
            if (c >= 'a' && c <= 'z') {
                c -= 32;
            }
            h = h * 31 + c;
        }
        return h ^ (h >>> 16);
    }

    private static boolean equalsFolded(byte[] upper, byte[] candidate) {
        if (upper.length != candidate.length) {
            return false;
        }
        for (int i = 0; i < upper.length; i++) {
            int c = candidate[i];
            if (c >= 'a' && c <= 'z') {
                c -= 32;
            }
            if (c != upper[i]) {
                return false;
            }
        }
        return true;
    }
}
