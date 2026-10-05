package com.jredis.server.db;

import com.jredis.common.Bytes;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Skip list ordered by (score, member) with <b>spans</b>: each forward link records how many
 * level-0 steps it jumps, so summing spans along a search path gives a node's rank in O(log n).
 * A direct port of Redis's zskiplist.
 *
 * <p>Ranks here are 1-based, as in Redis's internals; commands convert to 0-based.
 */
public final class SkipList {

    static final int MAX_LEVEL = 32;
    private static final int P_THRESHOLD = 0xFFFF / 4;   // p = 1/4

    public static final class Node {
        final byte[] member;
        double score;
        Node backward;
        final Node[] forward;
        final int[] span;

        Node(int level, double score, byte[] member) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new int[level];
        }

        public byte[] member() {
            return member;
        }

        public double score() {
            return score;
        }

        public Node next() {
            return forward[0];
        }

        public Node prev() {
            return backward;
        }

        int level() {
            return forward.length;
        }
    }

    /** Scratch arrays for insert/delete; per thread because several engines may run in one JVM. */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        final Node[] update = new Node[MAX_LEVEL];
        final int[] rank = new int[MAX_LEVEL];
    }

    private final Node header = new Node(MAX_LEVEL, 0, null);
    private Node tail;
    private int length;
    private int level = 1;

    public int length() {
        return length;
    }

    public Node first() {
        return header.forward[0];
    }

    public Node last() {
        return tail;
    }

    private static boolean less(Node n, double score, byte[] member) {
        return n.score < score || (n.score == score && Bytes.compare(n.member, member) < 0);
    }

    private static int randomLevel() {
        int lvl = 1;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while ((r.nextInt() & 0xFFFF) < P_THRESHOLD && lvl < MAX_LEVEL) {
            lvl++;
        }
        return lvl;
    }

    /** Inserts a member that is not present (the caller's dictionary guarantees that). */
    public Node insert(double score, byte[] member) {
        Scratch s = SCRATCH.get();
        Node[] update = s.update;
        int[] rank = s.rank;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = i == level - 1 ? 0 : rank[i + 1];
            while (x.forward[i] != null && less(x.forward[i], score, member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                rank[i] = 0;
                update[i] = header;
                header.span[i] = length;
            }
            level = lvl;
        }
        x = new Node(lvl, score, member);
        for (int i = 0; i < lvl; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        for (int i = lvl; i < level; i++) {
            update[i].span[i]++;
        }
        x.backward = update[0] == header ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        } else {
            tail = x;
        }
        length++;
        clear(update);
        return x;
    }

    private void deleteNode(Node x, Node[] update) {
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            } else {
                update[i].span[i] -= 1;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--;
        }
        length--;
    }

    private Node findUpdate(double score, byte[] member, Node[] update) {
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && less(x.forward[i], score, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        return x.forward[0];
    }

    public boolean delete(double score, byte[] member) {
        Node[] update = SCRATCH.get().update;
        Node x = findUpdate(score, member, update);
        boolean found = x != null && x.score == score && Bytes.equal(x.member, member);
        if (found) {
            deleteNode(x, update);
        }
        clear(update);
        return found;
    }

    /**
     * Changes the score of an existing member. Keeps the node in place when the order does not
     * change (the common case for leaderboards); otherwise removes and re-inserts it.
     *
     * @return the node now holding the member (may be a new node)
     */
    public Node updateScore(double currentScore, byte[] member, double newScore) {
        Node[] update = SCRATCH.get().update;
        Node x = findUpdate(currentScore, member, update);
        if (x == null || x.score != currentScore || !Bytes.equal(x.member, member)) {
            clear(update);
            throw new IllegalStateException("skip list out of sync with its dictionary");
        }
        if ((x.backward == null || x.backward.score < newScore)
                && (x.forward[0] == null || x.forward[0].score > newScore)) {
            x.score = newScore;
            clear(update);
            return x;
        }
        deleteNode(x, update);
        clear(update);
        return insert(newScore, x.member);
    }

    /** 1-based rank of the member, or 0 if absent. */
    public long rank(double score, byte[] member) {
        Node x = header;
        long rank = 0;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null
                    && (x.forward[i].score < score
                    || (x.forward[i].score == score && Bytes.compare(x.forward[i].member, member) <= 0))) {
                rank += x.span[i];
                x = x.forward[i];
            }
            if (x != header && x.score == score && Bytes.equal(x.member, member)) {
                return rank;
            }
        }
        return 0;
    }

    /** Node at 1-based rank, or null. */
    public Node byRank(long rank) {
        if (rank < 1 || rank > length) {
            return null;
        }
        Node x = header;
        long traversed = 0;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= rank) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == rank) {
                return x;
            }
        }
        return null;
    }

    public boolean isInRange(ScoreRange r) {
        if (r.isEmpty() || tail == null || !r.gteMin(tail.score)) {
            return false;
        }
        Node first = header.forward[0];
        return first != null && r.lteMax(first.score);
    }

    /** First node whose score is inside the range, or null. */
    public Node firstInRange(ScoreRange r) {
        if (!isInRange(r)) {
            return null;
        }
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && !r.gteMin(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        return x != null && r.lteMax(x.score) ? x : null;
    }

    /** Last node whose score is inside the range, or null. */
    public Node lastInRange(ScoreRange r) {
        if (!isInRange(r)) {
            return null;
        }
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && r.lteMax(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        return x != header && r.gteMin(x.score) ? x : null;
    }

    /** Removes every node whose score is in range, also from the dictionary via the callback. */
    public int deleteRangeByScore(ScoreRange r, java.util.function.Consumer<Node> onRemove) {
        Node[] update = SCRATCH.get().update;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && !r.gteMin(x.forward[i].score)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        int removed = 0;
        while (x != null && r.lteMax(x.score)) {
            Node next = x.forward[0];
            deleteNode(x, update);
            onRemove.accept(x);
            removed++;
            x = next;
        }
        clear(update);
        return removed;
    }

    /** Removes nodes with 1-based ranks in [start, end]. */
    public int deleteRangeByRank(long start, long end, java.util.function.Consumer<Node> onRemove) {
        Node[] update = SCRATCH.get().update;
        Node x = header;
        long traversed = 0;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] < start) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }
        traversed++;
        x = x.forward[0];
        int removed = 0;
        while (x != null && traversed <= end) {
            Node next = x.forward[0];
            deleteNode(x, update);
            onRemove.accept(x);
            removed++;
            traversed++;
            x = next;
        }
        clear(update);
        return removed;
    }

    /** Verifies spans and links; used by tests. Returns null if consistent, else a description. */
    String checkInvariants() {
        long count = 0;
        Node prev = null;
        for (Node x = header.forward[0]; x != null; x = x.forward[0]) {
            count++;
            if (x.backward != prev) {
                return "bad backward link at element " + count;
            }
            if (prev != null && !less(prev, x.score, x.member)) {
                return "order violated at element " + count;
            }
            prev = x;
        }
        if (count != length) {
            return "length " + length + " but " + count + " nodes";
        }
        if (tail != prev) {
            return "tail mismatch";
        }
        for (int i = 0; i < level; i++) {
            long pos = 0;
            Node x = header;
            while (x != null) {
                Node f = x.forward[i];
                long steps = 0;
                Node walk = x;
                while (walk != f) {
                    walk = walk.forward[0];
                    steps++;
                    if (walk == null && f != null) {
                        return "level " + i + " link skips past end";
                    }
                }
                if (f != null && x.span[i] != steps) {
                    return "level " + i + " span " + x.span[i] + " but " + steps + " steps at pos " + pos;
                }
                pos += steps;
                x = f;
            }
        }
        return null;
    }

    private static void clear(Node[] update) {
        java.util.Arrays.fill(update, null);   // do not retain nodes of other lists
    }
}
