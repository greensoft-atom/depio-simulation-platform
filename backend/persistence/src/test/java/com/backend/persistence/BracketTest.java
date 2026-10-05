package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A single-elimination bracket's shape (docs 04 §6, "The first slice: duels"). */
class BracketTest {

    @Test
    @DisplayName("the next power of two holds the entries; its rounds are its log")
    void sizes() {
        assertThat(Bracket.sizeFor(2)).isEqualTo(2);
        assertThat(Bracket.sizeFor(3)).isEqualTo(4);
        assertThat(Bracket.sizeFor(5)).isEqualTo(8);
        assertThat(Bracket.sizeFor(32)).isEqualTo(32);
        assertThat(Bracket.rounds(2)).isEqualTo(1);
        assertThat(Bracket.rounds(8)).isEqualTo(3);
        assertThat(Bracket.rounds(32)).isEqualTo(5);
    }

    @Test
    @DisplayName("seed 1 meets the lowest first, and the top seeds are kept apart until the end")
    void theStandardOrder() {
        assertThat(Bracket.order(2)).containsExactly(1, 2);
        assertThat(Bracket.order(4)).containsExactly(1, 4, 2, 3);
        assertThat(Bracket.order(8)).containsExactly(1, 8, 4, 5, 2, 7, 3, 6);
        for (int size : new int[] {2, 4, 8, 16, 32}) {
            int[] o = Bracket.order(size);
            for (int i = 0; i < size; i += 2) {
                assertThat(o[i] + o[i + 1]).as("a first-round pair in " + size).isEqualTo(size + 1);
            }
            if (size >= 4) {
                assertThat(half(o, 1)).as("1 and 2 in different halves").isNotEqualTo(half(o, 2));
            }
            if (size >= 8) {
                assertThat(java.util.stream.IntStream.rangeClosed(1, 4).map(s -> quarter(o, s)).distinct().count())
                        .as("1 to 4 in different quarters").isEqualTo(4);
            }
        }
    }

    private static int indexOf(int[] o, int seed) {
        for (int i = 0; i < o.length; i++) {
            if (o[i] == seed) {
                return i;
            }
        }
        throw new AssertionError(seed);
    }

    private static int half(int[] o, int seed) {
        return indexOf(o, seed) / (o.length / 2);
    }

    private static int quarter(int[] o, int seed) {
        return indexOf(o, seed) / (o.length / 4);
    }
}
