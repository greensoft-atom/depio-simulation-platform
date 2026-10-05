package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Elo on placements: a duel's, and a field's, each player against each other (04 §4; D-28). */
class EloRatingTest {

    @Test
    @DisplayName("equals: a win is worth half of K, and a draw nothing")
    void equals() {
        assertThat(EloRating.deltas(new int[] {1_200, 1_200}, new int[] {0, 0}, new int[] {1, 2}))
                .containsExactly(16, -16);
        assertThat(EloRating.deltas(new int[] {1_200, 1_200}, new int[] {0, 0}, new int[] {1, 1}))
                .containsExactly(0, 0);
    }

    @Test
    @DisplayName("an upset moves more than the expected result, and a draw moves the favourite down")
    void expectation() {
        // 1 200 against 1 400: expected 0.24 and 0.76.
        assertThat(EloRating.deltas(new int[] {1_200, 1_400}, new int[] {0, 0}, new int[] {1, 2}))
                .containsExactly(24, -24);
        assertThat(EloRating.deltas(new int[] {1_200, 1_400}, new int[] {0, 0}, new int[] {2, 1}))
                .containsExactly(-8, 8);
        assertThat(EloRating.deltas(new int[] {1_200, 1_400}, new int[] {0, 0}, new int[] {1, 1}))
                .containsExactly(8, -8);
    }

    @Test
    @DisplayName("K falls from 32 to 16 after 30 rated matches, each player by their own count")
    void settledPlayersMoveLess() {
        assertThat(EloRating.deltas(new int[] {1_200, 1_200}, new int[] {29, 30}, new int[] {1, 2}))
                .containsExactly(16, -8);
    }

    @Test
    @DisplayName("a field of eight, equal: the first gains half of K, the last loses it, the rest in proportion")
    void aFieldOfEight() {
        int[] equal = {1_200, 1_200, 1_200, 1_200, 1_200, 1_200, 1_200, 1_200};
        // Fourth: above four, below three, ½ expected of each: +0.5 of seven pairs, 32 × 0.5 / 7.
        assertThat(EloRating.deltas(equal, new int[8], new int[] {1, 2, 3, 4, 5, 6, 7, 8}))
                .containsExactly(16, 11, 7, 2, -2, -7, -11, -16);
    }

    @Test
    @DisplayName("in a field, equal placings share, and a strong field costs less to lose to")
    void aFieldSharesAndWeighs() {
        assertThat(EloRating.deltas(new int[] {1_200, 1_200, 1_200}, new int[3], new int[] {1, 1, 3}))
                .containsExactly(8, 8, -16);
        // Last among two of 1 400: expected 0.24 of each, so 32 × −0.24, not 32 × −0.5.
        assertThat(EloRating.deltas(new int[] {1_200, 1_400, 1_400}, new int[3], new int[] {3, 1, 1}))
                .containsExactly(-8, 4, 4);
    }
}
