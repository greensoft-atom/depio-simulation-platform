package com.jredis.server.db;

/**
 * Base of every collection value. Keeps its own estimated size and, while stored in the keyspace,
 * reports every change to the global {@link MemoryTracker}. A value built before it is stored
 * (e.g. the result of SINTERSTORE) is detached, so nothing is counted twice.
 */
public abstract class TrackedValue {

    private MemoryTracker tracker;
    private long bytes;

    protected TrackedValue(long baseBytes) {
        this.bytes = baseBytes;
    }

    public long bytes() {
        return bytes;
    }

    protected final void adjust(long delta) {
        bytes += delta;
        if (tracker != null) {
            tracker.add(delta);
        }
    }

    void attach(MemoryTracker t) {
        tracker = t;
        t.add(bytes);
    }

    void detach() {
        if (tracker != null) {
            tracker.add(-bytes);
            tracker = null;
        }
    }

    public abstract int size();
}
