package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.ScanResult;

import java.io.PrintStream;
import java.util.List;

/**
 * Iterating over keys without blocking the server: SCAN returns a page and a cursor; loop until
 * the cursor is back to "0". Never use KEYS in application code (it walks the whole keyspace in
 * one command). SCAN may return a key more than once, so make the per-key work idempotent.
 */
public final class ScanKeys implements Example {

    @Override
    public String name() {
        return "scan";
    }

    @Override
    public String summary() {
        return "SCAN with MATCH/COUNT, deleting keys by pattern";
    }

    /** Deletes every key matching {@code pattern}, one SCAN page at a time. */
    long deleteByPattern(JRedisClient client, String pattern) {
        long deleted = 0;
        String cursor = "0";
        do {
            ScanResult page = client.sync().scan(cursor, pattern, 500);
            List<String> keys = page.keys;
            if (!keys.isEmpty()) {
                deleted += client.sync().send(unlinkArgs(keys)).asLong();
            }
            cursor = page.cursor;
        } while (!cursor.equals("0"));
        return deleted;
    }

    private static Object[] unlinkArgs(List<String> keys) {
        Object[] a = new Object[keys.size() + 1];
        a[0] = "UNLINK";
        for (int i = 0; i < keys.size(); i++) {
            a[i + 1] = keys.get(i);
        }
        return a;
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        deleteByPattern(client, "scan:*");
        for (int i = 0; i < 1_000; i++) {
            client.set((i % 2 == 0 ? "scan:order:" : "scan:invoice:") + i, "x");   // pipelined
        }
        long orders = 0;
        String cursor = "0";
        do {
            ScanResult page = client.sync().scan(cursor, "scan:order:*", 200);
            orders += page.keys.size();
            cursor = page.cursor;
        } while (!cursor.equals("0"));
        out.println("keys matching scan:order:*  -> " + orders);
        out.println("deleted scan:invoice:*      -> " + deleteByPattern(client, "scan:invoice:*"));
        out.println("deleted scan:*              -> " + deleteByPattern(client, "scan:*"));
    }
}
