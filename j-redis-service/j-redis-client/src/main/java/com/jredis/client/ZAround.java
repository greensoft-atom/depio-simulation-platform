package com.jredis.client;

import java.util.List;

/** Result of J.ZAROUND: a member's 0-based rank and the window of neighbours around it. */
public final class ZAround {

    public final long rank;
    /** 0-based rank of {@code window.get(0)} in the same order. */
    public final long firstRank;
    public final List<ScoredMember> window;

    public ZAround(long rank, long firstRank, List<ScoredMember> window) {
        this.rank = rank;
        this.firstRank = firstRank;
        this.window = window;
    }
}
