package com.jredis.server.core;

import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when told to; for deterministic tests. */
public final class ManualClock implements Clock {

    private final AtomicLong now;

    public ManualClock(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    @Override
    public void refresh() {
        // time moves only through advance()/set()
    }

    public void advance(long millis) {
        now.addAndGet(millis);
    }

    public void set(long millis) {
        now.set(millis);
    }
}
