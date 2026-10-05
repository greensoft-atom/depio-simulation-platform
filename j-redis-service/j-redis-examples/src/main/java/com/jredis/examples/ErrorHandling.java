package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisConnectionException;
import com.jredis.client.JRedisException;
import com.jredis.client.JRedisServerException;
import com.jredis.client.JRedisTimeoutException;

import java.io.PrintStream;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * What can fail and how to react.
 * <ul>
 * <li>{@link JRedisServerException}: the server rejected the command (wrong type, syntax, OOM...).
 *     A bug or bad input; retrying does not help. {@code prefix()} gives WRONGTYPE, ERR, OOM...</li>
 * <li>{@link JRedisTimeoutException}: no reply in time. The command may or may not have run.</li>
 * <li>{@link JRedisConnectionException}: not connected, or the connection dropped. The outcome of
 *     a write that was in flight is unknown; the client reconnects by itself.</li>
 * </ul>
 * The library never retries on its own. Retry only operations that are safe to repeat.
 */
public final class ErrorHandling implements Example {

    @Override
    public String name() {
        return "errors";
    }

    @Override
    public String summary() {
        return "server errors, timeouts, connection loss, safe retries";
    }

    /** Retries an idempotent operation (a read, SET of a fixed value, DEL...) on transient failures. */
    static <T> T withRetry(int attempts, Supplier<T> operation) {
        JRedisException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return operation.get();
            } catch (JRedisTimeoutException | JRedisConnectionException e) {
                last = e;                                   // transient: try again after a pause
                try {
                    Thread.sleep(100L << i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        client.sync().del("err:list");
        client.sync().rpush("err:list", "a");

        // Sync API: the exception is thrown directly.
        try {
            client.sync().incr("err:list");
        } catch (JRedisServerException e) {
            out.println("sync:  " + e.prefix() + " -> " + e.getMessage());
        }

        // Async API: the future completes exceptionally; join() wraps it in CompletionException.
        try {
            client.incr("err:list").join();
        } catch (CompletionException e) {
            out.println("async: " + e.getCause().getClass().getSimpleName() + " -> " + e.getCause().getMessage());
        }

        // Stateful commands are refused on the shared connection, with a hint.
        try {
            client.send("BLPOP", "err:list", 0);
        } catch (IllegalArgumentException e) {
            out.println("guard: " + e.getMessage());
        }

        // A retry wrapper for idempotent operations.
        out.println("retry:  LRANGE -> " + withRetry(3, () -> client.sync().lrange("err:list", 0, -1)));
        client.sync().del("err:list");
    }
}
