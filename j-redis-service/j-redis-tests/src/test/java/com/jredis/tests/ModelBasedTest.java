package com.jredis.tests;

import com.jredis.client.JRedisServerException;
import com.jredis.common.Reply;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Random command streams against the server and the {@link Model}, reply by reply, over a small
 * keyspace so that type errors, overwrites and expiry collide constantly. Every 500 commands the
 * whole keyspace is compared. Size: {@code -Djredis.model.ops=N} (default 100,000 per seed).
 */
class ModelBasedTest extends EmbeddedTest {

    private static final String[] VALUES = {"a", "b", "c", "", "1", "2", "10", "-3", "07", "99", "x y", "9223372036854775806"};
    private static final String[] MEMBERS = {"m1", "m2", "m3", "m4", "m5", "m6"};

    private Random rnd;

    private String key() {
        return "k" + rnd.nextInt(8);
    }

    private String val() {
        return VALUES[rnd.nextInt(VALUES.length)];
    }

    private String member() {
        return MEMBERS[rnd.nextInt(MEMBERS.length)];
    }

    private String idx(int bound) {
        return Integer.toString(rnd.nextInt(2 * bound + 1) - bound);
    }

    /** An explicit stream ID a few milliseconds either side of the clock. */
    private String sid() {
        return (clock.nowMillis() + rnd.nextInt(7) - 3) + "-" + rnd.nextInt(3);
    }

    private String score() {
        return rnd.nextInt(4) == 0 ? (rnd.nextInt(20) - 5) + ".5" : Integer.toString(rnd.nextInt(20) - 5);
    }

    private String[] next() {
        String k = key();
        switch (rnd.nextInt(55)) {
            case 0: return new String[] {"SET", k, val()};
            case 1: return new String[] {"SET", k, val(), "PX", Integer.toString(1 + rnd.nextInt(3_000))};
            case 2: return new String[] {"SET", k, val(), rnd.nextBoolean() ? "NX" : "XX"};
            case 3: return new String[] {"SET", k, val(), "KEEPTTL"};
            case 4: return new String[] {"GET", k};
            case 5: return new String[] {"GETDEL", k};
            case 6: return new String[] {"DEL", k, key()};
            case 7: return new String[] {"EXISTS", k};
            case 8: return new String[] {"TYPE", k};
            case 9: return new String[] {"INCRBY", k, idx(5)};
            case 10: return new String[] {"APPEND", k, val()};
            case 11: return new String[] {"STRLEN", k};
            case 12: return new String[] {"PEXPIRE", k, Integer.toString(rnd.nextInt(4_000) - 200)};
            case 13: return new String[] {"PERSIST", k};
            case 14: return new String[] {rnd.nextBoolean() ? "TTL" : "PTTL", k};
            case 15: return new String[] {"RENAME", k, key()};
            case 16: return new String[] {"HSET", k, member(), val()};
            case 17: return new String[] {"HSET", k, member(), val(), member(), val()};
            case 18: return new String[] {"HGET", k, member()};
            case 19: return new String[] {"HDEL", k, member(), member()};
            case 20: return new String[] {"HLEN", k};
            case 21: return new String[] {"HINCRBY", k, member(), idx(3)};
            case 22: return new String[] {"HGETALL", k};
            case 23: return new String[] {"LPUSH", k, val(), val()};
            case 24: return new String[] {"RPUSH", k, val()};
            case 25: return rnd.nextBoolean() ? new String[] {"LPOP", k} : new String[] {"RPOP", k, Integer.toString(1 + rnd.nextInt(3))};
            case 26: return new String[] {"LRANGE", k, idx(5), idx(5)};
            case 27: return new String[] {"LLEN", k};
            case 28: return new String[] {"LINDEX", k, idx(4)};
            case 29: return new String[] {"LSET", k, idx(4), val()};
            case 30: return new String[] {"LREM", k, idx(2), val()};
            case 31: return new String[] {"SADD", k, member(), member()};
            case 32: return new String[] {"SREM", k, member()};
            case 33: return new String[] {"SCARD", k};
            case 34: return new String[] {"SISMEMBER", k, member()};
            case 35: return new String[] {"SMEMBERS", k};
            case 36: return new String[] {rnd.nextBoolean() ? "SINTERSTORE" : "SUNIONSTORE", k, key(), key()};
            case 37: return new String[] {"ZADD", k, score(), member(), score(), member()};
            case 38: return new String[] {"ZINCRBY", k, score(), member()};
            case 39: return new String[] {"ZREM", k, member()};
            case 40: return new String[] {"ZSCORE", k, member()};
            case 41: return new String[] {rnd.nextBoolean() ? "ZRANK" : "ZREVRANK", k, member()};
            case 42: return rnd.nextBoolean() ? new String[] {"ZRANGE", k, idx(4), idx(4)} : new String[] {"ZRANGE", k, idx(4), idx(4), "WITHSCORES"};
            case 43: return rnd.nextBoolean() ? new String[] {"ZPOPMIN", k} : new String[] {"ZPOPMIN", k, Integer.toString(1 + rnd.nextInt(3))};
            case 44: return rnd.nextBoolean() ? new String[] {"J.ZAROUND", k, member(), Integer.toString(rnd.nextInt(3))}
                    : new String[] {"J.ZAROUND", k, member(), Integer.toString(rnd.nextInt(3)), "REV"};
            case 46: return new String[] {"XADD", k, "*", "f", val()};
            case 47: return new String[] {"XADD", k, sid(), "f", val()};
            case 48: return new String[] {"XADD", k, (clock.nowMillis() + rnd.nextInt(5) - 2) + "-*", "f", val(), "g", val()};
            case 49: return new String[] {"XADD", k, "MAXLEN", Integer.toString(rnd.nextInt(5)), "*", "f", val()};
            case 50: return new String[] {"XDEL", k, sid(), sid()};
            case 51: return rnd.nextBoolean() ? new String[] {"XTRIM", k, "MAXLEN", Integer.toString(rnd.nextInt(6))}
                    : new String[] {"XTRIM", k, "MINID", sid()};
            case 52: return rnd.nextBoolean() ? new String[] {"XRANGE", k, "-", "+"}
                    : new String[] {"XRANGE", k, sid(), rnd.nextBoolean() ? "+" : Long.toString(clock.nowMillis() + rnd.nextInt(7) - 3), "COUNT", Integer.toString(rnd.nextInt(4))};
            case 53: return new String[] {"XREVRANGE", k, "+", "-", "COUNT", Integer.toString(1 + rnd.nextInt(3))};
            case 54: return new String[] {"XLEN", k};
            default: return new String[] {"ZCARD", k};
        }
    }

    /** Renders a server reply; unordered replies (SMEMBERS, HGETALL) are normalised by sorting. */
    private static String render(String[] cmd, Reply r, Throwable failure) {
        if (failure != null) {
            Throwable c = failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure;
            if (c instanceof JRedisServerException) {
                return "-" + c.getMessage();
            }
            throw new AssertionError("request failed: " + Arrays.toString(cmd), c);
        }
        if (cmd[0].equals("SMEMBERS") || cmd[0].equals("HGETALL")) {
            List<String> items = new ArrayList<>();
            List<Reply> l = r.asList();
            boolean pairs = cmd[0].equals("HGETALL");
            for (int i = 0; i < l.size(); i += pairs ? 2 : 1) {
                items.add(pairs ? R.render(l.get(i)) + "=" + R.render(l.get(i + 1)) : R.render(l.get(i)));
            }
            Collections.sort(items);
            return "[" + String.join(", ", items) + "]";
        }
        return R.render(r);
    }

    @Test
    void serverAgreesWithTheModel() {
        int ops = Integer.getInteger("jredis.model.ops", 100_000);
        for (long seed = 1; seed <= 3; seed++) {
            sync.flushall();
            run(seed, ops);
        }
    }

    /**
     * Commands go out in pipelined batches on the client's single connection, so they execute in
     * order; the clock only moves between batches, exactly as in the model.
     */
    private void run(long seed, int ops) {
        rnd = new Random(seed);
        Model model = new Model(clock.nowMillis());
        Deque<String> recent = new ArrayDeque<>();
        int done = 0;
        int nextDump = 500;
        while (done < ops) {
            if (rnd.nextInt(4) == 0) {
                long step = rnd.nextInt(2_500);
                clock.advance(step);
                model.now += step;
                recent.add("(clock +" + step + " ms)");
            }
            int n = 1 + rnd.nextInt(64);
            List<String[]> cmds = new ArrayList<>(n);
            List<String> expected = new ArrayList<>(n);
            List<CompletableFuture<Reply>> replies = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                String[] cmd = next();
                cmds.add(cmd);
                expected.add(model.exec(cmd));
                replies.add(client.send((Object[]) cmd));
            }
            for (int j = 0; j < n; j++) {
                String[] cmd = cmds.get(j);
                CompletableFuture<Reply> f = replies.get(j);
                String actual;
                try {
                    actual = render(cmd, f.join(), null);
                } catch (java.util.concurrent.CompletionException e) {
                    actual = render(cmd, null, e);
                }
                recent.add(Arrays.toString(cmd) + " -> " + actual);
                if (recent.size() > 30) {
                    recent.removeFirst();
                }
                if (!expected.get(j).equals(actual)) {
                    fail("seed " + seed + ", op " + (done + j) + ": " + Arrays.toString(cmd) + "\n  model:  " + expected.get(j)
                            + "\n  server: " + actual + "\nlast commands:\n  " + String.join("\n  ", recent));
                }
            }
            done += n;
            if (done >= nextDump) {
                nextDump += 500;
                assertThat(StateDump.of(client)).as("seed %d, keyspace after op %d", seed, done).isEqualTo(model.dump());
            }
        }
    }
}
