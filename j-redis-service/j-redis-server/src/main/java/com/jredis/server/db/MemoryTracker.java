package com.jredis.server.db;

/** Running estimate of the data size, used by maxmemory and INFO. Command-thread only. */
public final class MemoryTracker {

    private long used;

    public long used() {
        return used;
    }

    public void add(long delta) {
        used += delta;
    }

    public void reset() {
        used = 0;
    }
}
