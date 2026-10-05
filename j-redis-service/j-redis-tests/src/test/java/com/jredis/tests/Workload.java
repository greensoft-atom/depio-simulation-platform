package com.jredis.tests;

import com.jredis.client.JRedisClient;
import com.jredis.common.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * A write-heavy mix over every type, including the multi-key commands that matter for snapshot
 * consistency (RENAME, LMOVE, SMOVE, SINTERSTORE) and MULTI/EXEC. Errors (wrong type) are part of
 * the mix and simply ignored.
 */
final class Workload {

    private final Random rnd;
    private final int keySpace;
    private long streamIds;                  // explicit, growing: so XDEL and MINID find entries

    Workload(long seed, int keySpace) {
        this.rnd = new Random(seed);
        this.keySpace = keySpace;
    }

    private String key(String prefix) {
        return prefix + rnd.nextInt(keySpace);
    }

    Object[] next() {
        switch (rnd.nextInt(23)) {
            case 0: return new Object[] {"SET", key("s:"), "v" + rnd.nextInt(1_000)};
            case 1: return new Object[] {"SET", key("s:"), "t" + rnd.nextInt(1_000), "EX", 1_000 + rnd.nextInt(1_000)};
            case 2: return new Object[] {"INCRBY", key("n:"), rnd.nextInt(100)};
            case 3: return new Object[] {"HSET", key("h:"), "f" + rnd.nextInt(20), "v" + rnd.nextInt(100)};
            case 4: return new Object[] {"HDEL", key("h:"), "f" + rnd.nextInt(20)};
            case 5: return new Object[] {"RPUSH", key("l:"), "e" + rnd.nextInt(100), "e" + rnd.nextInt(100)};
            case 6: return new Object[] {"LPOP", key("l:")};
            case 7: return new Object[] {"LMOVE", key("l:"), key("l:"), "LEFT", "RIGHT"};
            case 8: return new Object[] {"SADD", key("set:"), "m" + rnd.nextInt(50), "m" + rnd.nextInt(50)};
            case 9: return new Object[] {"SMOVE", key("set:"), key("set:"), "m" + rnd.nextInt(50)};
            case 10: return new Object[] {"SINTERSTORE", key("set:"), key("set:"), key("set:")};
            case 11: return new Object[] {"ZADD", key("z:"), rnd.nextInt(10_000) / 10.0, "p" + rnd.nextInt(200)};
            case 12: return new Object[] {"ZINCRBY", key("z:"), rnd.nextInt(50), "p" + rnd.nextInt(200)};
            case 13: return new Object[] {"RENAME", key("s:"), key("s:")};
            case 14: return new Object[] {"DEL", key("s:"), key("h:")};
            case 16: return new Object[] {"XADD", key("x:"), "MAXLEN", 40, ++streamIds + "-" + rnd.nextInt(3), "f", "v" + rnd.nextInt(100)};
            case 17: return new Object[] {"XDEL", key("x:"), (streamIds - rnd.nextInt(60)) + "-" + rnd.nextInt(3)};
            case 18: return new Object[] {"XTRIM", key("x:"), "MINID", streamIds - 50 - rnd.nextInt(100)};
            case 19: return new Object[] {"XGROUP", "CREATE", key("x:"), "g" + rnd.nextInt(2), rnd.nextBoolean() ? "0" : "$", "MKSTREAM"};
            case 20: return new Object[] {"XREADGROUP", "GROUP", "g" + rnd.nextInt(2), "c" + rnd.nextInt(3), "COUNT", 1 + rnd.nextInt(5), "STREAMS", key("x:"), ">"};
            case 21: return new Object[] {"XACK", key("x:"), "g" + rnd.nextInt(2), (streamIds - rnd.nextInt(60)) + "-" + rnd.nextInt(3)};
            case 22: return new Object[] {"XAUTOCLAIM", key("x:"), "g" + rnd.nextInt(2), "c" + rnd.nextInt(3), 0, 0, "COUNT", 1 + rnd.nextInt(4)};
            default: return new Object[] {"EXPIRE", key("n:"), 500 + rnd.nextInt(500)};
        }
    }

    /** Runs {@code n} commands, pipelined in batches; one in 20 batches is a MULTI/EXEC transaction. */
    void run(JRedisClient client, int n) {
        int done = 0;
        while (done < n) {
            int batch = Math.min(n - done, 1 + rnd.nextInt(100));
            if (rnd.nextInt(20) == 0) {
                com.jredis.client.Transaction tx = client.multi();
                for (int i = 0; i < Math.min(batch, 10); i++) {
                    tx.send(next());
                }
                tx.exec().join();
                done += Math.min(batch, 10);
                continue;
            }
            List<CompletableFuture<Reply>> fs = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                fs.add(client.send(next()).exceptionally(t -> null));
            }
            CompletableFuture.allOf(fs.toArray(new CompletableFuture<?>[0])).join();
            done += batch;
        }
    }
}
