package com.jredis.examples;

import com.jredis.client.JRedisBlocking;
import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A reliable work queue. Producers LPUSH; a consumer moves each item atomically to a "processing"
 * list with BLMOVE (waiting while the queue is empty), handles it, then removes it with LREM. An
 * item leaves "processing" only after it was handled, so a consumer crash loses nothing: on start-up
 * the consumer handles whatever is left in "processing" first. Handling must therefore be
 * idempotent (for example keyed by the job id).
 */
public final class WorkQueue implements Example {

    private static final String QUEUE = "q:jobs";
    private static final String PROCESSING = "q:jobs:processing";

    @Override
    public String name() {
        return "queue";
    }

    @Override
    public String summary() {
        return "reliable work queue with BLMOVE and a processing list";
    }

    private void handle(String job, PrintStream out) {
        out.println("  handled " + job);
    }

    /** Consumer start-up: finish items a previous consumer took but never confirmed. */
    void recover(JRedisSync redis, PrintStream out) {
        List<String> leftovers = redis.lrange(PROCESSING, 0, -1);
        for (String job : leftovers) {
            handle(job, out);
            redis.lrem(PROCESSING, 1, job);
        }
        out.println("recovered " + leftovers.size() + " unfinished job(s)");
    }

    /** Takes one job (waiting up to 1 s), handles it and confirms it. False if the queue stayed empty. */
    boolean consumeOne(JRedisClient client, PrintStream out) throws Exception {
        JRedisBlocking blocking = client.blocking();       // blocking pops get their own connection
        String job = blocking.blmove(QUEUE, PROCESSING, false, true, 1.0)   // RIGHT (oldest) -> LEFT
                .get(10, TimeUnit.SECONDS);
        if (job == null) {
            return false;                                   // timed out: nothing to do
        }
        handle(job, out);
        client.sync().lrem(PROCESSING, 1, job);             // confirm: done
        return true;
    }

    @Override
    public void run(JRedisClient client, PrintStream out) throws Exception {
        JRedisSync redis = client.sync();
        redis.del(QUEUE, PROCESSING);

        // Pretend a previous consumer died while handling job-0.
        redis.lpush(PROCESSING, "{\"id\":\"job-0\"}");
        recover(redis, out);

        for (int i = 1; i <= 3; i++) {
            redis.lpush(QUEUE, "{\"id\":\"job-" + i + "\"}");              // producer
        }
        int handled = 0;
        while (consumeOne(client, out)) {
            handled++;
        }
        out.println("handled " + handled + " job(s); queue length " + redis.llen(QUEUE)
                + ", processing length " + redis.llen(PROCESSING));
    }
}
