package com.backend.worker;

/**
 * How a rated match moves its players' ratings (docs detailed-design/04-platform-services.md
 * §4): Elo on placements. Each player against each other, a pair's result decided by which
 * placed higher (D-28):
 *
 * <pre>
 * S_ij = 1 placed above j, ½ equal, 0 below         E_ij = 1 / (1 + 10^((R_j − R_i) / 400))
 * ΔR_i = round(K_i × Σ_j≠i (S_ij − E_ij) / (N − 1))  K = 32, then 16 after 30 rated matches
 * </pre>
 *
 * With two players it is a duel's Elo; a team match is two, each team at its players' mean
 * (D-26). Each player has their own K, so a newcomer's rating moves quickly to where it belongs
 * and a settled player's does not swing on one match; the deltas need not cancel. A rule of
 * {@code worker}'s, like the rewards, and applied by the repository to the ratings its
 * transaction has locked, not to the ones the result was written with.
 */
public final class EloRating {

    static final int K_NEW = 32;
    static final int K_SETTLED = 16;
    static final int SETTLED_AFTER = 30;

    public static int[] deltas(int[] ratings, int[] ratedBefore, int[] placements) {
        int n = ratings.length;
        int[] deltas = new int[n];
        for (int self = 0; self < n; self++) {
            double surprise = 0;
            for (int other = 0; other < n; other++) {
                if (other != self) {
                    double score = placements[self] < placements[other] ? 1.0
                            : placements[self] > placements[other] ? 0.0 : 0.5;
                    double expected = 1.0 / (1.0 + Math.pow(10, (ratings[other] - ratings[self]) / 400.0));
                    surprise += score - expected;
                }
            }
            int k = ratedBefore[self] < SETTLED_AFTER ? K_NEW : K_SETTLED;
            deltas[self] = (int) Math.round(k * surprise / (n - 1));
        }
        return deltas;
    }

    private EloRating() {
    }
}
