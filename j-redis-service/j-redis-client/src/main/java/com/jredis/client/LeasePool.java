package com.jredis.client;

import java.util.ArrayDeque;
import java.util.function.Function;

/** A small pool of exclusive connections for WATCH transactions. */
final class LeasePool {

    private final ClientResources res;
    private final ArrayDeque<LeasedConnection> idle = new ArrayDeque<>();
    private int created;
    private boolean closed;

    LeasePool(ClientResources res) {
        this.res = res;
    }

    <T> T run(Function<LeasedConnection, T> work) {
        LeasedConnection lease = acquire();
        boolean healthy = false;
        try {
            T result = work.apply(lease);
            healthy = lease.connection.isReady();
            return result;
        } finally {
            try {
                if (healthy && lease.watching) {
                    lease.sync().unwatch();
                }
            } catch (RuntimeException e) {
                healthy = false;                  // cannot reset it: close it instead of reusing it
            } finally {
                release(lease, healthy);
            }
        }
    }

    private LeasedConnection acquire() {
        long deadline = System.currentTimeMillis() + res.config.commandTimeoutMillis;
        synchronized (this) {
            while (true) {
                if (closed) {
                    throw new JRedisClosedException("client closed");
                }
                LeasedConnection c = idle.pollFirst();
                if (c != null && c.connection.epoch() < res.epochSeen.get()) {
                    // Idle, it heard nothing of the promotion another connection found (D-40).
                    c.connection.close();
                    created--;
                    continue;
                }
                if (c != null) {
                    return c;
                }
                if (created < res.config.leasePoolMax) {
                    created++;
                    break;
                }
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new JRedisTimeoutException("no leased connection available within " + res.config.commandTimeoutMillis + " ms");
                }
                try {
                    wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JRedisException("interrupted while waiting for a leased connection");
                }
            }
        }
        Connection conn = new Connection(res, Connection.Mode.COMMAND, "lease", null);
        conn.connect();
        if (!conn.awaitReady(res.config.connectTimeoutMillis)) {
            conn.close();
            synchronized (this) {
                created--;
                notifyAll();
            }
            throw new JRedisConnectionException("cannot open a leased connection to " + res.config.address());
        }
        return new LeasedConnection(conn, res);
    }

    private synchronized void release(LeasedConnection c, boolean healthy) {
        if (healthy && !closed) {
            idle.addLast(c);
        } else {
            c.connection.close();
            created--;
        }
        notifyAll();
    }

    synchronized void close() {
        closed = true;
        for (LeasedConnection c : idle) {
            c.connection.close();
        }
        idle.clear();
        notifyAll();
    }
}
