package com.backend.worker;

import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchOutcome.PlayerOutcome;

/**
 * What a match is worth (docs detailed-design/04-platform-services.md §5).
 *
 * These rules live in {@code worker} and nowhere else, on purpose. An arena that computed
 * rewards would have to be redeployed to change balance, and during the rollout the arenas
 * still running the old rules would pay differently for the same match — a difference
 * players notice and nobody can explain afterwards. The arena reports facts; this turns
 * them into currency, in one place, against one version of the rules.
 *
 * The numbers are deliberately plain. They are the first version of a balance decision, not
 * a model, and they are here so that changing them is one obvious edit with tests attached.
 */
public final class RewardRules {

    static final int XP_PER_SCORE = 1;
    static final int XP_PER_KILL = 5;
    static final int COINS_PER_TEN_SCORE = 1;
    static final int COINS_FOR_WINNING = 50;
    static final int COINS_FOR_TAKING_PART = 10;

    /** Below this, a player was barely present; paying for it would make quitting profitable (and it counts for nothing, D-45). */
    static final long MIN_PLAYTIME_SECONDS = com.backend.persistence.MatchResultRepository.COUNTED_FROM_SECONDS;

    public record Reward(int xp, long coins, int ratingDelta) { }

    /** Kinds of a boost, as {@code percents} indexes them (D-38). */
    static final int XP = 0;
    static final int COINS = 1;

    /**
     * @param percents the boosts running for this player when the match ended, a percent a kind
     *                 (D-38): experience and coins raised by them, rounded down; never a rating
     */
    public static Reward forPlayer(MatchOutcome match, PlayerOutcome player, int[] percents) {
        if (player.playtimeSeconds() < MIN_PLAYTIME_SECONDS) {
            return new Reward(0, 0, 0);
        }
        int xp = player.score() * XP_PER_SCORE + player.kills() * XP_PER_KILL;
        long coins = (long) player.score() / 10 * COINS_PER_TEN_SCORE
                + (match.won(player) ? COINS_FOR_WINNING : COINS_FOR_TAKING_PART);
        xp = (int) ((long) xp * (100 + percents[XP]) / 100);
        coins = coins * (100 + percents[COINS]) / 100;
        // No rating here: a rated mode's is worked out from the ratings the repository has
        // locked when it applies the result (EloRating), not from this one match's facts.
        // Free-for-all moves none: a rating that moved on an eight-player scramble would be
        // mostly noise.
        return new Reward(xp, coins, 0);
    }

    private RewardRules() {
    }
}
