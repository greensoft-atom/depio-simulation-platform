package com.jredis.client;

import com.jredis.common.Reply;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * A dedicated connection for blocking pops. One blocking command is outstanding at a time; further
 * calls queue behind it. The client-side timeout is the server timeout plus the command timeout;
 * a server timeout of 0 (wait forever) disables it.
 */
public final class JRedisBlocking implements AutoCloseable {

    /** One queued blocking call. */
    private static final class Request<T> {
        final byte[][] argv;
        final long clientTimeoutMillis;
        final Function<Reply, T> conv;
        final CompletableFuture<T> result = new CompletableFuture<>();

        Request(byte[][] argv, long clientTimeoutMillis, Function<Reply, T> conv) {
            this.argv = argv;
            this.clientTimeoutMillis = clientTimeoutMillis;
            this.conv = conv;
        }

        void complete(Reply r, Throwable err) {
            if (err != null) {
                result.completeExceptionally(err);
                return;
            }
            try {
                result.complete(conv.apply(r));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        }
    }

    private final Connection connection;
    private final Executor executor;
    private final long commandTimeoutMillis;
    private final ArrayDeque<Request<?>> queue = new ArrayDeque<>();
    private boolean busy;
    private boolean closed;

    JRedisBlocking(ClientResources res) {
        this.connection = new Connection(res, Connection.Mode.BLOCKING, "blocking", null);
        this.executor = res.group;
        this.commandTimeoutMillis = res.config.commandTimeoutMillis;
        connection.connect();
        if (!res.isOwnThread() && !ThreadGuard.isNonBlocking()) {
            connection.awaitReady(res.config.connectTimeoutMillis);
        }
    }

    /** BLMOVE: the element moved, or null on timeout. */
    public CompletableFuture<String> blmove(String source, String destination, boolean fromLeft, boolean toLeft, double timeoutSeconds) {
        return enqueue(Args.of("BLMOVE", source, destination, fromLeft ? "LEFT" : "RIGHT", toLeft ? "LEFT" : "RIGHT", timeoutSeconds),
                timeoutSeconds, Replies::str);
    }

    /** BLPOP: [key, value], or null on timeout. */
    public CompletableFuture<List<String>> blpop(double timeoutSeconds, String... keys) {
        return enqueue(argv("BLPOP", keys, timeoutSeconds), timeoutSeconds, Replies::strings);
    }

    /** BRPOP: [key, value], or null on timeout. */
    public CompletableFuture<List<String>> brpop(double timeoutSeconds, String... keys) {
        return enqueue(argv("BRPOP", keys, timeoutSeconds), timeoutSeconds, Replies::strings);
    }

    /** BZPOPMIN: [key, member, score], or null on timeout. */
    public CompletableFuture<List<String>> bzpopmin(double timeoutSeconds, String... keys) {
        return enqueue(argv("BZPOPMIN", keys, timeoutSeconds), timeoutSeconds, Replies::strings);
    }

    /** BZPOPMAX: [key, member, score], or null on timeout. */
    public CompletableFuture<List<String>> bzpopmax(double timeoutSeconds, String... keys) {
        return enqueue(argv("BZPOPMAX", keys, timeoutSeconds), timeoutSeconds, Replies::strings);
    }

    /**
     * XREADGROUP … BLOCK: waits up to {@code blockMillis} (0: for ever) for new entries; the entries
     * per stream, or an empty map on timeout.
     */
    public CompletableFuture<java.util.Map<String, List<Streams.Entry>>> xreadgroup(long blockMillis, String group, String consumer,
                                                                                long count, java.util.Map<String, String> streams) {
        return enqueue(Args.of(AsyncCommands.readArgs(group, consumer, count, blockMillis, streams)), blockMillis / 1000.0, Streams::streams);
    }

    /** XREAD … BLOCK: waits up to {@code blockMillis} (0: for ever); the entries per stream, or an empty map. */
    public CompletableFuture<java.util.Map<String, List<Streams.Entry>>> xread(long blockMillis, long count, java.util.Map<String, String> streams) {
        return enqueue(Args.of(AsyncCommands.readArgs(null, null, count, blockMillis, streams)), blockMillis / 1000.0, Streams::streams);
    }

    private static byte[][] argv(String cmd, String[] keys, double timeoutSeconds) {
        Object[] a = new Object[keys.length + 2];
        a[0] = cmd;
        System.arraycopy(keys, 0, a, 1, keys.length);
        a[a.length - 1] = timeoutSeconds;
        return Args.of(a);
    }

    private <T> CompletableFuture<T> enqueue(byte[][] argv, double timeoutSeconds, Function<Reply, T> conv) {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeout must be >= 0");
        }
        long clientTimeout = timeoutSeconds == 0 ? 0 : (long) Math.ceil(timeoutSeconds * 1000) + commandTimeoutMillis;
        Request<T> req = new Request<>(argv, clientTimeout, conv);
        boolean sendNow;
        synchronized (this) {
            if (closed) {
                req.result.completeExceptionally(new JRedisClosedException("client closed"));
                return req.result;
            }
            sendNow = !busy;
            if (sendNow) {
                busy = true;
            } else {
                queue.addLast(req);
            }
        }
        if (sendNow) {
            send(req);
        }
        return req.result;
    }

    private <T> void send(Request<T> req) {
        CompletableFuture<Reply> f;
        try {
            f = connection.send(req.argv, req.clientTimeoutMillis, Connection.KIND_USER);
        } catch (RuntimeException e) {
            f = new CompletableFuture<>();
            f.completeExceptionally(e);
        }
        f.whenComplete((r, err) -> {
            req.complete(r, err);
            sendNext();
        });
    }

    /** The next request goes out on a fresh stack: while disconnected, calls fail at once and must not recurse. */
    private void sendNext() {
        Request<?> next;
        synchronized (this) {
            next = queue.pollFirst();
            if (next == null) {
                busy = false;
                return;
            }
        }
        Request<?> n = next;
        try {
            executor.execute(() -> send(n));
        } catch (RejectedExecutionException stopping) {
            failAll(n, new JRedisClosedException("client closed"));
        }
    }

    private void failAll(Request<?> first, JRedisException e) {
        List<Request<?>> rest;
        synchronized (this) {
            rest = new ArrayList<>(queue);
            queue.clear();
            busy = false;
        }
        if (first != null) {
            first.result.completeExceptionally(e);
        }
        for (Request<?> r : rest) {
            r.result.completeExceptionally(e);
        }
    }

    public boolean isConnected() {
        return connection.isReady();
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        connection.close();
        failAll(null, new JRedisClosedException("client closed"));
    }
}
