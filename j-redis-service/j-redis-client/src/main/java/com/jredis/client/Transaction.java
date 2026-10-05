package com.jredis.client;

import com.jredis.common.Bytes;
import com.jredis.common.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A MULTI … EXEC batch. The whole batch is written in one event-loop task, so no other request on
 * the connection can end up inside it.
 *
 * <pre>
 * client.multi()
 *       .send("HSET", "ticket:" + id, "userId", "42")
 *       .send("EXPIRE", "ticket:" + id, 60)
 *       .exec();          // → [1, 1]
 * </pre>
 */
public final class Transaction {

    private final Connection connection;
    private final long timeoutMillis;
    private final java.util.function.Supplier<JRedisException> precondition;   // null, or why EXEC must not run
    private final List<byte[][]> commands = new ArrayList<>();
    private boolean executed;

    Transaction(Connection connection, long timeoutMillis) {
        this(connection, timeoutMillis, null);
    }

    Transaction(Connection connection, long timeoutMillis, java.util.function.Supplier<JRedisException> precondition) {
        this.connection = connection;
        this.timeoutMillis = timeoutMillis;
        this.precondition = precondition;
    }

    public Transaction send(Object... args) {
        if (executed) {
            throw new IllegalStateException("transaction already executed");
        }
        byte[][] argv = Args.of(args);
        String name = Bytes.upperAscii(argv[0]);
        switch (name) {
            case "MULTI": case "EXEC": case "DISCARD": case "WATCH": case "UNWATCH":
            case "QUIT": case "RESET": case "SELECT": case "AUTH": case "HELLO":          // run at once, not queued,
            case "SUBSCRIBE": case "PSUBSCRIBE": case "UNSUBSCRIBE": case "PUNSUBSCRIBE":  // or change the connection
                throw new IllegalArgumentException(name + " cannot be queued inside a transaction");
            default:
        }
        commands.add(argv);
        return this;
    }

    public int size() {
        return commands.size();
    }

    /**
     * @return the replies in order (a failed command is an error Reply; the others still ran), or
     *         null if the transaction was aborted because a WATCHed key changed
     */
    public CompletableFuture<List<Reply>> exec() {
        if (executed) {
            throw new IllegalStateException("transaction already executed");
        }
        executed = true;
        JRedisException refused = precondition == null ? null : precondition.get();
        if (refused != null) {
            CompletableFuture<List<Reply>> f = new CompletableFuture<>();
            f.completeExceptionally(refused);
            return f;
        }
        return connection.sendTransaction(commands, timeoutMillis).thenApply(r -> r.isNull() ? null : r.asList());
    }
}
