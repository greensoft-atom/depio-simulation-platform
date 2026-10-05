package com.jredis.client;

/** A sorted-set member with its score. */
public final class ScoredMember {

    public final String member;
    public final double score;

    public ScoredMember(String member, double score) {
        this.member = member;
        this.score = score;
    }

    @Override
    public String toString() {
        return member + "=" + score;
    }
}
