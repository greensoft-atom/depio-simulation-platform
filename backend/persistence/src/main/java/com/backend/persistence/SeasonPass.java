package com.backend.persistence;

import java.util.List;

/**
 * The season pass's table (docs detailed-design/04-platform-services.md §8, revenue (b), D-69): forty tiers
 * of 250 points, each paying its free reward to everyone and its premium one to whoever bought premium.
 * First cuts of the balance the owner left to Claude (Q-48), their reasons in 04 §8. The premium track
 * never pays coins (D-67).
 */
public final class SeasonPass {

    private SeasonPass() {
    }

    public static final int TIERS = 40;
    public static final int TIER_POINTS = 250;
    /** The premium track's price: the {@code gems_500} pack. */
    public static final int PREMIUM_GEMS = 500;
    public static final int POINTS_A_RESULT = 10;
    public static final int POINTS_A_GOAL = 50;

    static final String XP_BOOST = "boost_xp_hour";
    static final String COINS_BOOST = "boost_coins_hour";

    /** A tier's reward on one track: coins, gems, and an item or null. */
    public record Reward(long coins, int gems, String itemId) { }

    /** 150 coins a tier, but every fifth's 5 gems. */
    public static Reward free(int tier) {
        return tier % 5 == 0 ? new Reward(0, 5, null) : new Reward(150, 0, null);
    }

    /** 15 gems a tier, and every fifth a boost: xp at 5, 15, 25 and 35, coins at 10, 20, 30 and 40. */
    public static Reward premium(int tier) {
        return new Reward(0, 15, tier % 5 != 0 ? null : tier % 10 == 5 ? XP_BOOST : COINS_BOOST);
    }

    /** The tier so many points reach: 0 before the first, 40 at most. */
    public static int tierOf(long points) {
        return (int) Math.min(TIERS, points / TIER_POINTS);
    }

    /** Every item the tiers name, which the platform's item table must have. */
    public static List<String> items() {
        return List.of(XP_BOOST, COINS_BOOST);
    }
}
