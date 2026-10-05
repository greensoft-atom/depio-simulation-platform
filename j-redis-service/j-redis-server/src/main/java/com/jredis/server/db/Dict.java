package com.jredis.server.db;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Chained hash table with <b>incremental rehashing</b> and a <b>reverse-binary SCAN cursor</b>,
 * following Redis's dict.
 *
 * <ul>
 *   <li>Growing or shrinking never stalls: while rehashing, both tables are live and every
 *       operation moves one bucket; the background scheduler moves more when idle.</li>
 *   <li>{@link #scan} returns every element present for the whole scan at least once, even if the
 *       table grows or shrinks between calls; elements may be returned more than once.</li>
 * </ul>
 *
 * <p>Not thread-safe: owned by the command thread (or the loader before the server starts).
 */
public final class Dict<E extends DictEntry> {

    private static final int INITIAL_SIZE = 4;
    private static final int EMPTY_VISITS = 10;

    private final SipHash hasher;
    private DictEntry[] t0;
    private DictEntry[] t1;
    private int used0;
    private int used1;
    private int rehashIdx = -1;
    private int iterators;

    public Dict(SipHash hasher) {
        this.hasher = hasher;
    }

    public int size() {
        return used0 + used1;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean isRehashing() {
        return rehashIdx >= 0;
    }

    /** Total bucket slots currently allocated (for memory estimates and INFO). */
    public int buckets() {
        return (t0 == null ? 0 : t0.length) + (t1 == null ? 0 : t1.length);
    }

    public int hashOf(byte[] key) {
        return hasher.hash32(key);
    }

    public E get(byte[] key) {
        return get(key, hashOf(key));
    }

    @SuppressWarnings("unchecked")
    public E get(byte[] key, int hash) {
        if (size() == 0) {
            return null;
        }
        if (isRehashing()) {
            rehashStep();
        }
        DictEntry e = find(t0, key, hash);
        if (e == null && t1 != null) {
            e = find(t1, key, hash);
        }
        return (E) e;
    }

    private static DictEntry find(DictEntry[] table, byte[] key, int hash) {
        if (table == null) {
            return null;
        }
        for (DictEntry e = table[hash & (table.length - 1)]; e != null; e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, key)) {
                return e;
            }
        }
        return null;
    }

    /** Inserts an entry whose key is not present. Callers check with {@link #get} first. */
    public void add(E entry) {
        if (isRehashing()) {
            rehashStep();
        }
        if (t0 == null) {
            t0 = new DictEntry[INITIAL_SIZE];
        }
        if (isRehashing()) {
            int idx = entry.hash & (t1.length - 1);
            entry.next = t1[idx];
            t1[idx] = entry;
            used1++;
        } else {
            int idx = entry.hash & (t0.length - 1);
            entry.next = t0[idx];
            t0[idx] = entry;
            used0++;
            if (used0 >= t0.length) {
                startRehash(nextPowerOfTwo((long) used0 * 2));
            }
        }
    }

    public E remove(byte[] key) {
        return remove(key, hashOf(key));
    }

    @SuppressWarnings("unchecked")
    public E remove(byte[] key, int hash) {
        if (size() == 0) {
            return null;
        }
        if (isRehashing()) {
            rehashStep();
        }
        DictEntry e = unlink(t0, key, hash);
        if (e != null) {
            used0--;
        } else if (t1 != null) {
            e = unlink(t1, key, hash);
            if (e != null) {
                used1--;
            }
        }
        if (e != null) {
            e.next = null;
            if (isRehashing() && used0 == 0) {
                finishRehash();
            }
            shrinkIfSparse();
        }
        return (E) e;
    }

    private static DictEntry unlink(DictEntry[] table, byte[] key, int hash) {
        if (table == null) {
            return null;
        }
        int idx = hash & (table.length - 1);
        DictEntry prev = null;
        for (DictEntry e = table[idx]; e != null; prev = e, e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, key)) {
                if (prev == null) {
                    table[idx] = e.next;
                } else {
                    prev.next = e.next;
                }
                return e;
            }
        }
        return null;
    }

    public void clear() {
        t0 = null;
        t1 = null;
        used0 = 0;
        used1 = 0;
        rehashIdx = -1;
    }

    // ------------------------------------------------------------------ rehashing

    private void startRehash(int newSize) {
        if (isRehashing() || (t0 != null && newSize == t0.length)) {
            return;
        }
        if (used0 == 0) {
            t0 = new DictEntry[newSize];
            return;
        }
        t1 = new DictEntry[newSize];
        used1 = 0;
        rehashIdx = 0;
    }

    private void shrinkIfSparse() {
        if (isRehashing() || t0 == null || t0.length <= INITIAL_SIZE) {
            return;
        }
        if ((long) used0 * 10 < t0.length) {
            startRehash(nextPowerOfTwo(Math.max(used0, INITIAL_SIZE)));
        }
    }

    /**
     * Moves one non-empty bucket (visiting at most a few empty ones) from the old table to the new.
     *
     * @return true while rehashing is still in progress
     */
    public boolean rehashStep() {
        if (!isRehashing() || iterators > 0) {
            return isRehashing();
        }
        int emptyVisits = EMPTY_VISITS;
        while (t0[rehashIdx] == null) {
            rehashIdx++;
            if (rehashIdx >= t0.length) {
                finishRehash();
                return false;
            }
            if (--emptyVisits == 0) {
                return true;
            }
        }
        DictEntry e = t0[rehashIdx];
        int mask1 = t1.length - 1;
        while (e != null) {
            DictEntry next = e.next;
            int idx = e.hash & mask1;
            e.next = t1[idx];
            t1[idx] = e;
            used0--;
            used1++;
            e = next;
        }
        t0[rehashIdx] = null;
        rehashIdx++;
        if (used0 == 0) {
            finishRehash();
            return false;
        }
        return true;
    }

    private void finishRehash() {
        t0 = t1;
        used0 = used1;
        t1 = null;
        used1 = 0;
        rehashIdx = -1;
        shrinkIfSparse();          // deletes during a shrink may have left the new table sparse too
    }

    /**
     * Rehashes until done or the budget is used.
     *
     * @return true if rehashing is still in progress
     */
    public boolean rehashFor(long budgetNanos) {
        long start = System.nanoTime();
        while (isRehashing()) {
            for (int i = 0; i < 100 && isRehashing(); i++) {
                rehashStep();
            }
            if (System.nanoTime() - start >= budgetNanos) {    // overflow-safe for any budget
                break;
            }
        }
        return isRehashing();
    }

    static int nextPowerOfTwo(long n) {
        if (n >= (1 << 30)) {
            return 1 << 30;
        }
        int size = INITIAL_SIZE;
        while (size < n) {
            size <<= 1;
        }
        return size;
    }

    // ------------------------------------------------------------------ iteration

    /**
     * One step of the reverse-binary cursor scan. The visitor must not modify this dict.
     *
     * @return the next cursor; 0 when the scan is complete
     */
    @SuppressWarnings("unchecked")
    public long scan(long cursor, Consumer<? super E> visitor) {
        if (size() == 0) {
            return 0;
        }
        iterators++;          // no rehash step may move entries while we visit a bucket
        try {
            long v = cursor;
            if (!isRehashing()) {
                long m0 = t0.length - 1;
                visitBucket(t0[(int) (v & m0)], visitor);
                v |= ~m0;
                v = Long.reverse(v);
                v++;
                v = Long.reverse(v);
            } else {
                DictEntry[] small = t0.length <= t1.length ? t0 : t1;
                DictEntry[] large = small == t0 ? t1 : t0;
                long m0 = small.length - 1;
                long m1 = large.length - 1;
                visitBucket(small[(int) (v & m0)], visitor);
                do {
                    visitBucket(large[(int) (v & m1)], visitor);
                    v |= ~m1;
                    v = Long.reverse(v);
                    v++;
                    v = Long.reverse(v);
                } while ((v & (m0 ^ m1)) != 0);
            }
            return v;
        } finally {
            iterators--;
        }
    }

    @SuppressWarnings("unchecked")
    private void visitBucket(DictEntry e, Consumer<? super E> visitor) {
        while (e != null) {
            DictEntry next = e.next;
            visitor.accept((E) e);
            e = next;
        }
    }

    /** Visits every element. The visitor must not modify this dict. */
    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> visitor) {
        iterators++;
        try {
            forEachIn(t0, visitor);
            forEachIn(t1, visitor);
        } finally {
            iterators--;
        }
    }

    @SuppressWarnings("unchecked")
    private void forEachIn(DictEntry[] table, Consumer<? super E> visitor) {
        if (table == null) {
            return;
        }
        for (DictEntry head : table) {
            for (DictEntry e = head; e != null; e = e.next) {
                visitor.accept((E) e);
            }
        }
    }

    /**
     * A random element: random non-empty bucket, then a random element of its chain. Slightly
     * biased toward elements in short chains, as in Redis.
     */
    @SuppressWarnings("unchecked")
    public E random(Random rnd) {
        if (size() == 0) {
            return null;
        }
        if (isRehashing()) {
            rehashStep();
        }
        DictEntry head;
        if (isRehashing()) {
            int span = t0.length + t1.length - rehashIdx;
            do {
                int idx = rehashIdx + rnd.nextInt(span);
                head = idx >= t0.length ? t1[idx - t0.length] : t0[idx];
            } while (head == null);
        } else {
            do {
                head = t0[rnd.nextInt(t0.length)];
            } while (head == null);
        }
        int chain = 0;
        for (DictEntry e = head; e != null; e = e.next) {
            chain++;
        }
        int pick = rnd.nextInt(chain);
        DictEntry e = head;
        while (pick-- > 0) {
            e = e.next;
        }
        return (E) e;
    }
}
