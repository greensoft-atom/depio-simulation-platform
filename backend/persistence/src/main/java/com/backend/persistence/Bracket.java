package com.backend.persistence;

/**
 * A single-elimination bracket's shape (docs detailed-design/04-platform-services.md §6): the
 * next power of two, and the standard order, seed 1 against the lowest, so the top seeds meet last.
 */
public final class Bracket {

    /** The next power of two that holds {@code entries}, at least 2. */
    public static int sizeFor(int entries) {
        int size = 2;
        while (size < entries) {
            size *= 2;
        }
        return size;
    }

    /** The rounds a bracket of {@code size} takes. */
    public static int rounds(int size) {
        return Integer.numberOfTrailingZeros(size);
    }

    /**
     * The seeds in bracket order, first-round pairs next to each other: each seed of the order a
     * size down is followed by the seed that adds up with it to the size plus one.
     */
    public static int[] order(int size) {
        int[] order = {1};
        for (int n = 2; n <= size; n *= 2) {
            int[] next = new int[n];
            for (int i = 0; i < order.length; i++) {
                next[2 * i] = order[i];
                next[2 * i + 1] = n + 1 - order[i];
            }
            order = next;
        }
        return order;
    }

    private Bracket() {
    }
}
