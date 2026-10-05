package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;

import java.io.PrintStream;
import java.util.UUID;

/**
 * A lock shared by many processes. Acquire with SET NX PX and a random token; renew and release
 * only while the token is still ours, with the J.CAS and J.CAD extension commands, so a process
 * whose lock expired can never release or extend somebody else's lock.
 */
public final class DistributedLock implements Example {

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String summary() {
        return "safe distributed lock: acquire, renew, release (J.CAS / J.CAD)";
    }

    /** A small reusable lock handle. */
    public static final class Lock {
        private final JRedisClient client;
        private final String key;
        private final String token = UUID.randomUUID().toString();
        private final long leaseMillis;

        public Lock(JRedisClient client, String name, long leaseMillis) {
            this.client = client;
            this.key = "lock:" + name;
            this.leaseMillis = leaseMillis;
        }

        /** True if acquired; the lease expires by itself if the holder dies. */
        public boolean tryAcquire() {
            return client.sync().set(key, token, SetArgs.nx().andPx(leaseMillis));
        }

        /** Extends the lease, only if the lock is still ours. */
        public boolean renew() {
            return client.sync().cas(key, token, token, SetArgs.px(leaseMillis));
        }

        /** Releases the lock, only if it is still ours. */
        public boolean release() {
            return client.sync().cad(key, token);
        }
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        client.sync().del("lock:nightly-report");
        Lock worker1 = new Lock(client, "nightly-report", 30_000);
        Lock worker2 = new Lock(client, "nightly-report", 30_000);

        out.println("worker 1 acquires      -> " + worker1.tryAcquire());
        out.println("worker 2 acquires      -> " + worker2.tryAcquire() + " (held by worker 1)");
        out.println("worker 1 renews        -> " + worker1.renew());
        out.println("worker 2 releases      -> " + worker2.release() + " (not its lock: nothing happens)");
        out.println("worker 1 releases      -> " + worker1.release());
        out.println("worker 2 acquires      -> " + worker2.tryAcquire());
        worker2.release();

        // Typical use:
        Lock lock = new Lock(client, "nightly-report", 30_000);
        if (lock.tryAcquire()) {
            try {
                out.println("doing the exclusive work...");
            } finally {
                lock.release();
            }
        }
    }
}
