package com.jredis.server.db;

import java.util.Random;
import java.util.function.Consumer;

/**
 * A sorted set: a dictionary member → skip-list node (O(1) score lookup) plus the skip list
 * ordered by (score, member) for everything positional.
 */
public final class ZSetValue extends TrackedValue {

    private final Dict<ZEntry> dict;
    private final SkipList list = new SkipList();

    public ZSetValue(SipHash hasher) {
        super(MemoryEstimator.CONTAINER + MemoryEstimator.skipListNode(SkipList.MAX_LEVEL));
        this.dict = new Dict<>(hasher);
    }

    @Override
    public int size() {
        return dict.size();
    }

    public SkipList list() {
        return list;
    }

    public ZEntry find(byte[] member) {
        return dict.get(member);
    }

    /** Score of the member, or null. */
    public Double score(byte[] member) {
        ZEntry e = dict.get(member);
        return e == null ? null : e.node.score;
    }

    private static long cost(SkipList.Node n) {
        return MemoryEstimator.ZSET_ENTRY + MemoryEstimator.skipListNode(n.level()) + MemoryEstimator.byteArray(n.member.length);
    }

    /** Adds a member that is not present. */
    public void insert(byte[] member, double score) {
        int h = dict.hashOf(member);
        SkipList.Node node = list.insert(score, member);
        dict.add(new ZEntry(member, h, node));
        adjust(cost(node));
    }

    /** Changes the score of an existing member. */
    public void updateScore(ZEntry e, double newScore) {
        if (e.node.score == newScore) {
            return;
        }
        long before = MemoryEstimator.skipListNode(e.node.level());
        e.node = list.updateScore(e.node.score, e.key, newScore);
        adjust(MemoryEstimator.skipListNode(e.node.level()) - before);
    }

    public boolean remove(byte[] member) {
        ZEntry e = dict.remove(member);
        if (e == null) {
            return false;
        }
        list.delete(e.node.score, e.key);
        adjust(-cost(e.node));
        return true;
    }

    /** 0-based rank in ascending (or descending when {@code reverse}) order, or -1. */
    public long rank(byte[] member, boolean reverse) {
        ZEntry e = dict.get(member);
        if (e == null) {
            return -1;
        }
        long r = list.rank(e.node.score, e.key);
        return reverse ? list.length() - r : r - 1;
    }

    public int deleteRangeByScore(ScoreRange range) {
        return list.deleteRangeByScore(range, this::forget);
    }

    /** 0-based inclusive ranks, already normalised. */
    public int deleteRangeByRank(long start, long end) {
        return list.deleteRangeByRank(start + 1, end + 1, this::forget);
    }

    private void forget(SkipList.Node n) {
        dict.remove(n.member);
        adjust(-cost(n));
    }

    public long scan(long cursor, Consumer<ZEntry> visitor) {
        return dict.scan(cursor, visitor);
    }

    public ZEntry random(Random rnd) {
        return dict.random(rnd);
    }

    /** A detached deep copy. */
    public ZSetValue copy(SipHash hasher) {
        ZSetValue c = new ZSetValue(hasher);
        for (SkipList.Node n = list.first(); n != null; n = n.next()) {
            c.insert(n.member, n.score);
        }
        return c;
    }
}
