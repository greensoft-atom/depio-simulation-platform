package com.jredis.client;

import java.util.List;

/** One page of a SCAN: the next cursor ("0" when finished) and the keys found. */
public final class ScanResult {

    public final String cursor;
    public final List<String> keys;

    public ScanResult(String cursor, List<String> keys) {
        this.cursor = cursor;
        this.keys = keys;
    }

    public boolean finished() {
        return "0".equals(cursor);
    }
}
