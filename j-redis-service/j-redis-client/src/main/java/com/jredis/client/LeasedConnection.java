package com.jredis.client;

import com.jredis.common.Bytes;
import com.jredis.common.Reply;

import java.util.concurrent.CompletableFuture;

/**
 * An exclusive connection for optimistic transactions:
 *
 * <pre>
 * boolean joined = client.withLeasedConnection(conn -&gt; {
 *     for (int attempt = 0; attempt &lt; 5; attempt++) {
 *         conn.sync().watch("group:" + id);
 *         long members = Long.parseLong(conn.sync().hget("group:" + id, "members"));
 *         if (members &gt;= max) { conn.sync().unwatch(); return false; }
 *         List&lt;Reply&gt; r = conn.sync().exec(conn.multi().send("HINCRBY", "group:" + id, "members", 1));
 *         if (r != null) return true;             // null: the key changed under us, retry
 *     }
 *     throw new IllegalStateException("too much contention");
 * });
 * </pre>
 */
public final class LeasedConnection extends AsyncCommands {

    final Connection connection;
    private final long timeoutMillis;
    private final JRedisSync sync;
    boolean watching;
    private int watchGeneration;                  // the connection generation the WATCH was sent on

    LeasedConnection(Connection connection, ClientResources res) {
        this.connection = connection;
        this.timeoutMillis = res.config.commandTimeoutMillis;
        this.sync = new JRedisSync(this, res, timeoutMillis);
    }

    @Override
    protected CompletableFuture<Reply> execute(byte[][] argv) {
        String name = argv.length == 0 ? "" : Bytes.upperAscii(argv[0]);
        if (name.equals("WATCH")) {
            if (!watching) {
                watchGeneration = connection.generation();
            }
            watching = true;
        } else if (!name.equals("UNWATCH")) {
            JRedisClient.rejectOnSharedConnection(argv);
        }
        return connection.send(argv, timeoutMillis, Connection.KIND_USER);
    }

    public CompletableFuture<Void> watch(String... keys) {
        Object[] a = new Object[keys.length + 1];
        a[0] = "WATCH";
        System.arraycopy(keys, 0, a, 1, keys.length);
        return send(a).thenApply(r -> null);
    }

    public CompletableFuture<Void> unwatch() {
        return send("UNWATCH").thenApply(r -> {
            watching = false;
            return null;
        });
    }

    /**
     * A transaction on this connection. If the connection was re-established after WATCH, the
     * server no longer watches anything, so EXEC fails instead of committing unchecked.
     */
    public Transaction multi() {
        return new Transaction(connection, timeoutMillis, () -> watching && connection.generation() != watchGeneration
                ? new JRedisConnectionException("the connection was re-established after WATCH, so the watch is lost; "
                        + "read again and retry the transaction")
                : null);
    }

    public JRedisSync sync() {
        return sync;
    }
}
