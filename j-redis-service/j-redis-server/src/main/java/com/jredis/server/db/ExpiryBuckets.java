package com.jredis.server.db;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Active expiry with deterministic one-second buckets. Each key has at most one registration:
 * extending a TTL leaves the existing (earlier) registration in place, and when that bucket comes
 * due the key is simply re-registered at its current expiry. Deleting a key, removing its TTL or
 * moving it to an earlier bucket clears its slot at once ({@link #unregister}), so a deleted key
 * is never kept reachable until its bucket comes due. Lazy expiry on access guarantees that
 * clients never read an expired key; this class only reclaims memory.
 */
public final class ExpiryBuckets {

    /** Buckets count seconds from 2024-01-01T00:00:00Z, which fits an int until 2092. */
    static final long BASE_SECONDS = 1_704_067_200L;

    private final TreeMap<Integer, ArrayList<KeyEntry>> buckets = new TreeMap<>();
    private int resumeBucket = Integer.MIN_VALUE;
    private int resumeIndex;
    private long registrations;

    static int bucketOf(long expireAtMillis) {
        long s = Math.floorDiv(expireAtMillis, 1000L) - BASE_SECONDS;
        if (s < 0) {
            return 0;
        }
        return s > Integer.MAX_VALUE - 1 ? Integer.MAX_VALUE - 1 : (int) s;
    }

    void register(KeyEntry e, int bucket) {
        ArrayList<KeyEntry> list = buckets.computeIfAbsent(bucket, b -> new ArrayList<>());
        e.expireBucket = bucket;
        e.expireSlot = list.size();
        list.add(e);
        registrations++;
    }

    /** Clears the entry's registration, if it has one. O(log buckets). */
    void unregister(KeyEntry e) {
        if (e.expireBucket == KeyEntry.NOT_REGISTERED) {
            return;
        }
        ArrayList<KeyEntry> list = buckets.get(e.expireBucket);
        if (list != null && e.expireSlot < list.size() && list.get(e.expireSlot) == e) {
            list.set(e.expireSlot, null);
            registrations--;
        }
        e.expireBucket = KeyEntry.NOT_REGISTERED;
    }

    public long registrations() {
        return registrations;
    }

    public void clear() {
        buckets.clear();
        registrations = 0;
        resumeBucket = Integer.MIN_VALUE;
        resumeIndex = 0;
    }

    /** True if some bucket strictly before the current second is waiting. */
    public boolean hasDueWork(long nowMillis) {
        return !buckets.isEmpty() && buckets.firstKey() < bucketOf(nowMillis);
    }

    /**
     * Processes due buckets until done or the deadline passes.
     *
     * @return number of keys expired
     */
    int process(long nowMillis, long deadlineNanos, Db db) {
        int nowBucket = bucketOf(nowMillis);
        int expired = 0;
        int sinceCheck = 0;
        while (!buckets.isEmpty()) {
            Map.Entry<Integer, ArrayList<KeyEntry>> first = buckets.firstEntry();
            int bucket = first.getKey();
            if (bucket >= nowBucket) {
                break;
            }
            ArrayList<KeyEntry> list = first.getValue();
            int i = bucket == resumeBucket ? resumeIndex : 0;
            for (; i < list.size(); i++) {
                KeyEntry e = list.get(i);
                if (e != null) {
                    list.set(i, null);
                    registrations--;
                    e.expireBucket = KeyEntry.NOT_REGISTERED;
                    if (e.alive && e.expireAt >= 0) {
                        if (e.expireAt <= nowMillis) {
                            db.expireEntry(e);
                            expired++;
                        } else {
                            register(e, bucketOf(e.expireAt));    // TTL was extended: a later bucket
                        }
                    }
                }
                if (++sinceCheck >= 64) {
                    sinceCheck = 0;
                    if (System.nanoTime() - deadlineNanos >= 0) {
                        resumeBucket = bucket;
                        resumeIndex = i + 1;
                        return expired;
                    }
                }
            }
            buckets.pollFirstEntry();
            if (bucket == resumeBucket) {             // keep another bucket's resume point
                resumeBucket = Integer.MIN_VALUE;
                resumeIndex = 0;
            }
        }
        return expired;
    }
}
