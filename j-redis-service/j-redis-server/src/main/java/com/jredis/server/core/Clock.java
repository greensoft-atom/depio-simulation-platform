package com.jredis.server.core;

/**
 * Wall-clock time for TTLs, cached per batch so every command in a batch sees the same instant.
 * TTLs use wall-clock milliseconds because they must survive restarts as absolute times.
 */
public interface Clock {

    long nowMillis();

    /** Re-reads the underlying time source; called by the command loop once per batch. */
    void refresh();

    /** A new system clock. Each engine needs its own: the cached instant belongs to its batch. */
    static Clock system() {
        return new Clock() {
            private volatile long now = System.currentTimeMillis();

            @Override
            public long nowMillis() {
                return now;
            }

            @Override
            public void refresh() {
                now = System.currentTimeMillis();
            }
        };
    }
}
