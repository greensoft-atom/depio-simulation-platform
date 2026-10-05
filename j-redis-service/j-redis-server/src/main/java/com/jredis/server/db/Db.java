package com.jredis.server.db;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The keyspace and the only write path into it. Every command goes through these methods, which is
 * where the cross-cutting rules live:
 *
 * <ul>
 *   <li><b>lazy expiry</b> on every lookup, so an expired key is never visible;</li>
 *   <li><b>snapshot pre-image</b> before the first change to a key while a snapshot runs;</li>
 *   <li><b>memory accounting</b> for maxmemory and INFO;</li>
 *   <li><b>modification signals</b> for WATCH and blocked clients.</li>
 * </ul>
 *
 * <p>Command-thread only (or the loader before the server starts).
 */
public final class Db {

    private final SipHash hasher;
    private final LongSupplier clock;
    private final MemoryTracker memory = new MemoryTracker();
    private final ExpiryBuckets expiry = new ExpiryBuckets();
    private final Random random = new Random();
    private Dict<KeyEntry> keys;
    private DbHooks hooks;
    private boolean loading;
    private boolean replica;
    private boolean mutated;
    private long expires;

    // snapshot state
    private int epoch;
    private boolean snapshotActive;
    private SnapshotSink snapshotSink;

    public Db(SipHash hasher, LongSupplier clock) {
        this.hasher = hasher;
        this.clock = clock;
        this.keys = new Dict<>(hasher);
    }

    public void hooks(DbHooks hooks) {
        this.hooks = hooks;
    }

    public SipHash hasher() {
        return hasher;
    }

    /** For tests in this package. */
    ExpiryBuckets expiryBuckets() {
        return expiry;
    }

    public Random random() {
        return random;
    }

    /**
     * On a replica, a key whose time has passed is hidden from reads but not deleted, and active
     * expiry does not run: the primary's {@code DEL} decides (docs/16-replication.md §6, D-38).
     */
    public void replica(boolean replica) {
        this.replica = replica;
    }

    /** During loading, nothing is deleted on time grounds: replay must see exactly what the original run saw. */
    public void loading(boolean loading) {
        this.loading = loading;
    }

    public boolean isLoading() {
        return loading;
    }

    public long now() {
        return clock.getAsLong();
    }

    public int size() {
        return keys.size();
    }

    public long expiresCount() {
        return expires;
    }

    public long usedMemory() {
        return memory.used();
    }

    public boolean isRehashing() {
        return keys.isRehashing();
    }

    // ------------------------------------------------------------------ mutation flag (fail-stop support)

    public void resetMutated() {
        mutated = false;
    }

    /** True once the current command has started changing data. */
    /** Restores the flag of an enclosing command (EXEC) after a nested one ran. */
    public void mutated(boolean value) {
        mutated = value;
    }

    public boolean mutated() {
        return mutated;
    }

    // ------------------------------------------------------------------ lookups

    /** The entry, after lazy expiry; null if absent. */
    public KeyEntry lookupRead(byte[] key) {
        KeyEntry e = keys.get(key);
        if (e == null) {
            return null;
        }
        if (expireIfNeeded(e)) {
            return null;
        }
        return e;
    }

    /** Like {@link #lookupRead} but announces an upcoming change (snapshot pre-image, mutation flag). */
    public KeyEntry lookupWrite(byte[] key) {
        KeyEntry e = lookupRead(key);
        if (e != null) {
            prepareWrite(e);
        }
        return e;
    }

    /** Must be called before changing an existing entry's value in place. */
    public void prepareWrite(KeyEntry e) {
        preImage(e);                         // may fail (e.g. out of memory): nothing has changed yet
        mutated = true;
    }

    /** The entry without expiry side effects (introspection only). */
    public KeyEntry peek(byte[] key) {
        return keys.get(key);
    }

    public boolean isExpired(KeyEntry e) {
        return e.expireAt >= 0 && e.expireAt <= clock.getAsLong();
    }

    private boolean expireIfNeeded(KeyEntry e) {
        if (loading || e.expireAt < 0 || e.expireAt > clock.getAsLong()) {
            return false;
        }
        if (!replica) {
            expireEntry(e);
        }
        return true;
    }

    /** Removes an expired entry and reports it. Not a command mutation: the change is complete and logged. */
    void expireEntry(KeyEntry e) {
        preImage(e);
        removeEntry(e);
        hooks.keyExpired(e.key);
        hooks.keyModified(e.key);
    }

    // ------------------------------------------------------------------ writes

    /** Creates a key that does not exist. */
    public KeyEntry add(byte[] key, byte type, Object value) {
        mutated = true;
        int h = keys.hashOf(key);
        KeyEntry e = new KeyEntry(key, h, type, value);
        e.snapEpoch = epoch;                 // born after the snapshot instant: never written to it
        keys.add(e);
        memory.add(MemoryEstimator.KEY_ENTRY + MemoryEstimator.byteArray(key.length));
        chargeValue(type, value);
        hooks.keyModified(key);
        return e;
    }

    /**
     * Stores {@code value} under {@code key}, replacing whatever was there (any type), as SET and
     * the STORE commands do.
     */
    public KeyEntry setValue(byte[] key, byte type, Object value, boolean keepTtl) {
        KeyEntry e = lookupRead(key);
        if (e == null) {
            return add(key, type, value);
        }
        prepareWrite(e);
        releaseValue(e.type, e.value);
        e.type = type;
        e.value = value;
        chargeValue(type, value);
        if (!keepTtl) {
            removeExpireInternal(e);
        }
        hooks.keyModified(key);
        return e;
    }

    /** Replaces the value of a string entry obtained through {@link #lookupWrite}. */
    public void replaceString(KeyEntry e, byte[] value) {
        preImage(e);                         // may fail (e.g. out of memory): nothing has changed yet
        mutated = true;
        memory.add(MemoryEstimator.byteArray(value.length) - MemoryEstimator.byteArray(((byte[]) e.value).length));
        e.value = value;
        hooks.keyModified(e.key);
    }

    /** @return true if the key existed */
    public boolean delete(byte[] key) {
        KeyEntry e = lookupRead(key);
        if (e == null) {
            return false;
        }
        deleteEntry(e);
        return true;
    }

    /** Deletes a live entry (e.g. a collection that became empty). */
    public void deleteEntry(KeyEntry e) {
        preImage(e);                         // may fail (e.g. out of memory): nothing has changed yet
        mutated = true;
        removeEntry(e);
        hooks.keyModified(e.key);
    }

    private void removeEntry(KeyEntry e) {
        keys.remove(e.key, e.hash);
        e.alive = false;
        if (e.expireAt >= 0) {
            expires--;
        }
        expiry.unregister(e);
        memory.add(-(MemoryEstimator.KEY_ENTRY + MemoryEstimator.byteArray(e.key.length)));
        releaseValue(e.type, e.value);
        e.value = null;                      // nothing may keep a deleted value reachable
    }

    private void chargeValue(byte type, Object value) {
        if (type == KeyEntry.STRING) {
            memory.add(MemoryEstimator.byteArray(((byte[]) value).length));
        } else {
            ((TrackedValue) value).attach(memory);
        }
    }

    private void releaseValue(byte type, Object value) {
        if (type == KeyEntry.STRING) {
            memory.add(-MemoryEstimator.byteArray(((byte[]) value).length));
        } else {
            ((TrackedValue) value).detach();
        }
    }

    /** Removes every key. O(1): the old table is left to the garbage collector. */
    public void flushAll() {
        mutated = true;
        keys = new Dict<>(hasher);
        memory.reset();
        expiry.clear();
        expires = 0;
    }

    // ------------------------------------------------------------------ TTL

    /** Sets an absolute expiry (epoch ms). The caller handles "already in the past" (delete instead). */
    public void setExpire(KeyEntry e, long whenMillis) {
        preImage(e);                         // may fail (e.g. out of memory): nothing has changed yet
        mutated = true;                         // the TTL is part of the snapshot image
        if (e.expireAt < 0) {
            expires++;
        }
        e.expireAt = whenMillis;
        int b = ExpiryBuckets.bucketOf(whenMillis);
        if (e.expireBucket == KeyEntry.NOT_REGISTERED || b < e.expireBucket) {
            expiry.unregister(e);            // a later bucket's registration would only be skipped
            expiry.register(e, b);
        }
        hooks.keyModified(e.key);
    }

    /** @return true if a TTL was removed */
    public boolean persist(KeyEntry e) {
        if (e.expireAt < 0) {
            return false;
        }
        preImage(e);                         // may fail (e.g. out of memory): nothing has changed yet
        mutated = true;
        removeExpireInternal(e);
        hooks.keyModified(e.key);
        return true;
    }

    private void removeExpireInternal(KeyEntry e) {
        if (e.expireAt >= 0) {
            e.expireAt = -1;
            expires--;
            expiry.unregister(e);
        }
    }

    /** Background: expire due keys until the deadline. */
    public int activeExpire(long deadlineNanos) {
        if (loading || replica) {
            return 0;
        }
        return expiry.process(clock.getAsLong(), deadlineNanos, this);
    }

    public boolean hasDueExpiries() {
        return expiry.hasDueWork(clock.getAsLong());
    }

    public void signalModified(byte[] key) {
        hooks.keyModified(key);
    }

    // ------------------------------------------------------------------ iteration

    /** One SCAN step over the keyspace. The visitor must not modify the keyspace. */
    public long scan(long cursor, Consumer<KeyEntry> visitor) {
        return keys.scan(cursor, visitor);
    }

    /** Visits every key. The visitor must not modify the keyspace. */
    public void forEach(Consumer<KeyEntry> visitor) {
        keys.forEach(visitor);
    }

    public KeyEntry randomEntry() {
        return keys.random(random);
    }

    /** Background: continue an incremental resize of the keyspace table. */
    public boolean rehash(long budgetNanos) {
        return keys.rehashFor(budgetNanos);
    }

    // ------------------------------------------------------------------ snapshot support

    /**
     * The number of the latest snapshot, stamped on entries created from now on. Counted here, not
     * taken from the AOF's generation: a replica's full sync takes a snapshot too, without starting
     * a generation, and shared numbers would let the next rewrite skip what the sync had marked
     * (docs/16-replication.md §5.2).
     */
    public int epoch() {
        return epoch;
    }

    /**
     * Starts a point-in-time snapshot, numbered one past the last: every existing entry (with a
     * lower number) will be handed to the sink exactly once, either by {@link #snapshotStep} or
     * just before its first change.
     */
    public void startSnapshot(SnapshotSink sink) {
        this.epoch++;
        this.snapshotSink = sink;
        this.snapshotActive = true;
    }

    public void endSnapshot() {
        snapshotActive = false;
        snapshotSink = null;
    }

    public boolean snapshotActive() {
        return snapshotActive;
    }

    /** Continues the snapshot scan by one step. @return the next cursor; 0 when done */
    public long snapshotStep(long cursor) {
        return keys.scan(cursor, e -> {
            if (e.snapEpoch < epoch) {
                e.snapEpoch = epoch;
                snapshotSink.write(e);
            }
        });
    }

    private void preImage(KeyEntry e) {
        if (snapshotActive && e.snapEpoch < epoch) {
            e.snapEpoch = epoch;
            snapshotSink.write(e);
        }
    }
}
