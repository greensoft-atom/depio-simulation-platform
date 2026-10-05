package com.backend.common;

/**
 * A main's positional arguments, read so that a bad one refuses to start
 * ({@link RefusedConfiguration}, exit 2) rather than failing (exit 1).
 *
 * The units pass {@code ${VAR}}, which systemd turns into an empty argument when the variable
 * is missing from the env file. Parsed with {@code Integer.parseInt}, that threw, the process
 * exited 1, and {@code Restart=on-failure} started it again every two seconds, for ever.
 */
public final class Arguments {

    private Arguments() {
    }

    /** The whole number at {@code index}, or {@code absent} if there are fewer arguments. */
    public static int integer(String[] args, int index, String name, int absent) {
        if (args.length <= index) {
            return absent;
        }
        try {
            return Integer.parseInt(args[index].trim());
        } catch (NumberFormatException e) {
            throw new RefusedConfiguration(name + " must be a whole number, not '" + args[index] + "'");
        }
    }

    /**
     * The text at {@code index}, trimmed, or {@code absent} if there are fewer arguments; present and empty, refused: an
     * empty host was bound as loopback, announced as "", or dialled and refused with exit 1 (the arena review, 2026-10-04).
     */
    public static String text(String[] args, int index, String name, String absent) {
        if (args.length <= index) {
            return absent;
        }
        String value = args[index].trim();
        if (value.isEmpty()) {
            throw new RefusedConfiguration(name + " is empty: is it missing from the env file?");
        }
        return value;
    }

    /** The number at {@code index}, or {@code absent} if there are fewer arguments. */
    public static float decimal(String[] args, int index, String name, float absent) {
        if (args.length <= index) {
            return absent;
        }
        try {
            float value = Float.parseFloat(args[index].trim());
            if (Float.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException e) {
            // refused below
        }
        throw new RefusedConfiguration(name + " must be a number, not '" + args[index] + "'");
    }
}
