package com.jredis.client;

import com.jredis.common.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The typed, non-blocking command API. Every method returns immediately; the future completes on a
 * client event-loop thread (keep callbacks short and hand work to its owner thread). A server
 * error completes the future with {@link JRedisServerException}.
 *
 * <p>Strings are sent as UTF-8; {@link #send} accepts {@code byte[]} for binary-safe values.
 */
public abstract class AsyncCommands {

    /** Sends one command. */
    protected abstract CompletableFuture<Reply> execute(byte[][] argv);

    /** Any command, including the J.* extensions. Arguments: String, byte[], or numbers. */
    public final CompletableFuture<Reply> send(Object... args) {
        return execute(Args.of(args));
    }

    private <T> CompletableFuture<T> call(Function<Reply, T> conv, Object... args) {
        return execute(Args.of(args)).thenApply(conv);
    }

    private static Object[] prepend(Object[] fixed, Object[] rest) {
        Object[] all = new Object[fixed.length + rest.length];
        System.arraycopy(fixed, 0, all, 0, fixed.length);
        System.arraycopy(rest, 0, all, fixed.length, rest.length);
        return all;
    }

    // ------------------------------------------------------------------ strings

    public CompletableFuture<String> get(String key) {
        return call(Replies::str, "GET", key);
    }

    public CompletableFuture<byte[]> getBytes(String key) {
        return call(Reply::asBytes, "GET", key);
    }

    /** SET; completes with true. */
    public CompletableFuture<Boolean> set(String key, Object value) {
        return call(r -> true, "SET", key, value);
    }

    /** SET with options; false when an NX/XX condition prevented the write. */
    public CompletableFuture<Boolean> set(String key, Object value, SetArgs options) {
        List<Object> a = new ArrayList<>();
        a.add("SET");
        a.add(key);
        a.add(value);
        a.addAll(options.args());
        return execute(Args.of(a)).thenApply(r -> !r.isNull());
    }

    public CompletableFuture<String> getdel(String key) {
        return call(Replies::str, "GETDEL", key);
    }

    public CompletableFuture<List<String>> mget(String... keys) {
        return call(Replies::strings, prepend(new Object[]{"MGET"}, keys));
    }

    public CompletableFuture<Void> mset(Map<String, ?> values) {
        List<Object> a = new ArrayList<>();
        a.add("MSET");
        for (Map.Entry<String, ?> e : values.entrySet()) {
            a.add(e.getKey());
            a.add(e.getValue());
        }
        return execute(Args.of(a)).thenApply(Replies::ok);
    }

    public CompletableFuture<Long> incr(String key) {
        return call(Replies::lng, "INCR", key);
    }

    public CompletableFuture<Long> incrBy(String key, long delta) {
        return call(Replies::lng, "INCRBY", key, delta);
    }

    public CompletableFuture<Long> decr(String key) {
        return call(Replies::lng, "DECR", key);
    }

    public CompletableFuture<Long> decrBy(String key, long delta) {
        return call(Replies::lng, "DECRBY", key, delta);
    }

    public CompletableFuture<Double> incrByFloat(String key, double delta) {
        return call(Replies::dbl, "INCRBYFLOAT", key, delta);
    }

    public CompletableFuture<Long> append(String key, Object value) {
        return call(Replies::lng, "APPEND", key, value);
    }

    public CompletableFuture<Long> strlen(String key) {
        return call(Replies::lng, "STRLEN", key);
    }

    // ------------------------------------------------------------------ keys

    public CompletableFuture<Long> del(String... keys) {
        return call(Replies::lng, prepend(new Object[]{"DEL"}, keys));
    }

    public CompletableFuture<Long> exists(String... keys) {
        return call(Replies::lng, prepend(new Object[]{"EXISTS"}, keys));
    }

    public CompletableFuture<Boolean> expire(String key, long seconds) {
        return call(Replies::bool, "EXPIRE", key, seconds);
    }

    public CompletableFuture<Boolean> pexpire(String key, long millis) {
        return call(Replies::bool, "PEXPIRE", key, millis);
    }

    public CompletableFuture<Boolean> persist(String key) {
        return call(Replies::bool, "PERSIST", key);
    }

    /** Seconds to live; -1 without TTL, -2 if the key does not exist. */
    public CompletableFuture<Long> ttl(String key) {
        return call(Replies::lng, "TTL", key);
    }

    public CompletableFuture<Long> pttl(String key) {
        return call(Replies::lng, "PTTL", key);
    }

    public CompletableFuture<String> type(String key) {
        return call(Replies::str, "TYPE", key);
    }

    public CompletableFuture<Void> rename(String from, String to) {
        return call(Replies::ok, "RENAME", from, to);
    }

    /** One SCAN step; start with cursor "0" and continue until {@link ScanResult#finished()}. */
    public CompletableFuture<ScanResult> scan(String cursor, String match, int count) {
        List<Object> a = new ArrayList<>();
        a.add("SCAN");
        a.add(cursor);
        if (match != null) {
            a.add("MATCH");
            a.add(match);
        }
        a.add("COUNT");
        a.add(count);
        return execute(Args.of(a)).thenApply(Replies::scan);
    }

    // ------------------------------------------------------------------ hashes

    public CompletableFuture<Long> hset(String key, String field, Object value) {
        return call(Replies::lng, "HSET", key, field, value);
    }

    public CompletableFuture<Long> hset(String key, Map<String, ?> fields) {
        List<Object> a = new ArrayList<>();
        a.add("HSET");
        a.add(key);
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            a.add(e.getKey());
            a.add(e.getValue());
        }
        return execute(Args.of(a)).thenApply(Replies::lng);
    }

    public CompletableFuture<Boolean> hsetnx(String key, String field, Object value) {
        return call(Replies::bool, "HSETNX", key, field, value);
    }

    public CompletableFuture<String> hget(String key, String field) {
        return call(Replies::str, "HGET", key, field);
    }

    public CompletableFuture<List<String>> hmget(String key, String... fields) {
        return call(Replies::strings, prepend(new Object[]{"HMGET", key}, fields));
    }

    public CompletableFuture<Map<String, String>> hgetall(String key) {
        return call(Replies::map, "HGETALL", key);
    }

    public CompletableFuture<Long> hdel(String key, String... fields) {
        return call(Replies::lng, prepend(new Object[]{"HDEL", key}, fields));
    }

    public CompletableFuture<Long> hlen(String key) {
        return call(Replies::lng, "HLEN", key);
    }

    public CompletableFuture<Boolean> hexists(String key, String field) {
        return call(Replies::bool, "HEXISTS", key, field);
    }

    public CompletableFuture<Long> hincrBy(String key, String field, long delta) {
        return call(Replies::lng, "HINCRBY", key, field, delta);
    }

    public CompletableFuture<List<String>> hkeys(String key) {
        return call(Replies::strings, "HKEYS", key);
    }

    public CompletableFuture<List<String>> hvals(String key) {
        return call(Replies::strings, "HVALS", key);
    }

    // ------------------------------------------------------------------ lists

    public CompletableFuture<Long> lpush(String key, Object... values) {
        return call(Replies::lng, prepend(new Object[]{"LPUSH", key}, values));
    }

    public CompletableFuture<Long> rpush(String key, Object... values) {
        return call(Replies::lng, prepend(new Object[]{"RPUSH", key}, values));
    }

    public CompletableFuture<String> lpop(String key) {
        return call(Replies::str, "LPOP", key);
    }

    public CompletableFuture<String> rpop(String key) {
        return call(Replies::str, "RPOP", key);
    }

    public CompletableFuture<List<String>> lrange(String key, long start, long stop) {
        return call(Replies::strings, "LRANGE", key, start, stop);
    }

    public CompletableFuture<Long> llen(String key) {
        return call(Replies::lng, "LLEN", key);
    }

    public CompletableFuture<Long> lrem(String key, long count, Object value) {
        return call(Replies::lng, "LREM", key, count, value);
    }

    public CompletableFuture<Void> ltrim(String key, long start, long stop) {
        return call(Replies::ok, "LTRIM", key, start, stop);
    }

    public CompletableFuture<String> lindex(String key, long index) {
        return call(Replies::str, "LINDEX", key, index);
    }

    public CompletableFuture<String> lmove(String source, String destination, boolean fromLeft, boolean toLeft) {
        return call(Replies::str, "LMOVE", source, destination, fromLeft ? "LEFT" : "RIGHT", toLeft ? "LEFT" : "RIGHT");
    }

    // ------------------------------------------------------------------ sets

    public CompletableFuture<Long> sadd(String key, Object... members) {
        return call(Replies::lng, prepend(new Object[]{"SADD", key}, members));
    }

    public CompletableFuture<Long> srem(String key, Object... members) {
        return call(Replies::lng, prepend(new Object[]{"SREM", key}, members));
    }

    public CompletableFuture<Set<String>> smembers(String key) {
        return call(Replies::set, "SMEMBERS", key);
    }

    public CompletableFuture<Boolean> sismember(String key, Object member) {
        return call(Replies::bool, "SISMEMBER", key, member);
    }

    public CompletableFuture<Long> scard(String key) {
        return call(Replies::lng, "SCARD", key);
    }

    // ------------------------------------------------------------------ sorted sets

    /** ZADD key [flags] score member, e.g. {@code zadd("lb", 1200, "p42", "GT")}. Returns elements added. */
    public CompletableFuture<Long> zadd(String key, double score, String member, String... flags) {
        for (String f : flags) {
            if (f.equalsIgnoreCase("INCR")) {
                throw new IllegalArgumentException("ZADD ... INCR returns a score, not a count: use zincrby, "
                        + "or send(\"ZADD\", key, \"INCR\", ...) for the flags");
            }
        }
        List<Object> a = new ArrayList<>();
        a.add("ZADD");
        a.add(key);
        for (String f : flags) {
            a.add(f);
        }
        a.add(score);
        a.add(member);
        return execute(Args.of(a)).thenApply(Replies::lng);
    }

    public CompletableFuture<Double> zincrby(String key, double increment, String member) {
        return call(Replies::dbl, "ZINCRBY", key, increment, member);
    }

    public CompletableFuture<Long> zrem(String key, String... members) {
        return call(Replies::lng, prepend(new Object[]{"ZREM", key}, members));
    }

    public CompletableFuture<Double> zscore(String key, String member) {
        return call(Replies::dbl, "ZSCORE", key, member);
    }

    /** 0-based ascending rank, or null. */
    public CompletableFuture<Long> zrank(String key, String member) {
        return call(Replies::lng, "ZRANK", key, member);
    }

    /** 0-based descending rank (leaderboard position), or null. */
    public CompletableFuture<Long> zrevrank(String key, String member) {
        return call(Replies::lng, "ZREVRANK", key, member);
    }

    public CompletableFuture<Long> zcard(String key) {
        return call(Replies::lng, "ZCARD", key);
    }

    public CompletableFuture<Long> zcount(String key, String min, String max) {
        return call(Replies::lng, "ZCOUNT", key, min, max);
    }

    public CompletableFuture<List<String>> zrange(String key, long start, long stop) {
        return call(Replies::strings, "ZRANGE", key, start, stop);
    }

    public CompletableFuture<List<ScoredMember>> zrangeWithScores(String key, long start, long stop) {
        return call(Replies::scored, "ZRANGE", key, start, stop, "WITHSCORES");
    }

    /** Highest scores first: the top of a leaderboard. */
    public CompletableFuture<List<ScoredMember>> zrevrangeWithScores(String key, long start, long stop) {
        return call(Replies::scored, "ZRANGE", key, start, stop, "REV", "WITHSCORES");
    }

    public CompletableFuture<List<String>> zrangeByScore(String key, String min, String max) {
        return call(Replies::strings, "ZRANGEBYSCORE", key, min, max);
    }

    public CompletableFuture<List<ScoredMember>> zpopmin(String key, long count) {
        return call(Replies::scored, "ZPOPMIN", key, count);
    }

    public CompletableFuture<List<ScoredMember>> zpopmax(String key, long count) {
        return call(Replies::scored, "ZPOPMAX", key, count);
    }

    public CompletableFuture<Long> zremrangebyscore(String key, String min, String max) {
        return call(Replies::lng, "ZREMRANGEBYSCORE", key, min, max);
    }

    /**
     * J.ZAROUND: the member's rank and up to {@code count} neighbours on each side, with scores.
     * {@code descending = true} for leaderboards. Null if the key or member does not exist.
     */
    public CompletableFuture<ZAround> zaround(String key, String member, int count, boolean descending) {
        Object[] a = descending
                ? new Object[]{"J.ZAROUND", key, member, count, "REV", "WITHSCORES"}
                : new Object[]{"J.ZAROUND", key, member, count, "WITHSCORES"};
        return execute(Args.of(a)).thenApply(r -> Replies.zaround(r, count));
    }

    // ------------------------------------------------------------------ pub/sub

    /** @return the number of clients that received the message */
    public CompletableFuture<Long> publish(String channel, Object message) {
        return call(Replies::lng, "PUBLISH", channel, message);
    }

    // ------------------------------------------------------------------ extensions

    /** J.CAS: set {@code key} to {@code value} only if it currently equals {@code expected}. TTL removed. */
    public CompletableFuture<Boolean> cas(String key, Object expected, Object value) {
        return call(Replies::bool, "J.CAS", key, expected, value);
    }

    /** J.CAS with a TTL option ({@link SetArgs#px}, {@link SetArgs#ex}, or KEEPTTL). */
    public CompletableFuture<Boolean> cas(String key, Object expected, Object value, SetArgs ttl) {
        List<Object> a = new ArrayList<>();
        a.add("J.CAS");
        a.add(key);
        a.add(expected);
        a.add(value);
        a.addAll(ttl.args());
        return execute(Args.of(a)).thenApply(Replies::bool);
    }

    /** J.CAD: delete {@code key} only if it currently equals {@code expected} (safe lock release). */
    public CompletableFuture<Boolean> cad(String key, Object expected) {
        return call(Replies::bool, "J.CAD", key, expected);
    }

    // ------------------------------------------------------------------ server

    public CompletableFuture<String> ping() {
        return call(Replies::str, "PING");
    }

    public CompletableFuture<Long> dbsize() {
        return call(Replies::lng, "DBSIZE");
    }

    public CompletableFuture<String> info(String section) {
        return section == null ? call(Replies::str, "INFO") : call(Replies::str, "INFO", section);
    }

    public CompletableFuture<Void> flushall() {
        return call(Replies::ok, "FLUSHALL");
    }

    // ------------------------------------------------------------------ streams (docs/15)

    /** XADD key * f v …: the ID the server gave. */
    public CompletableFuture<String> xadd(String key, Object... fieldsAndValues) {
        return xadd(key, XAddArgs.none(), "*", fieldsAndValues);
    }

    /** XADD with options and an ID ({@code *}, {@code ms-*} or explicit): the ID, or null under NOMKSTREAM. */
    public CompletableFuture<String> xadd(String key, XAddArgs args, String id, Object... fieldsAndValues) {
        List<Object> a = new ArrayList<>();
        a.add("XADD");
        a.add(key);
        a.addAll(args.args());
        a.add(id);
        java.util.Collections.addAll(a, fieldsAndValues);
        return call(Replies::str, a.toArray());
    }

    public CompletableFuture<Long> xlen(String key) {
        return call(Replies::lng, "XLEN", key);
    }

    public CompletableFuture<List<Streams.Entry>> xrange(String key, String start, String end) {
        return call(Streams::entries, "XRANGE", key, start, end);
    }

    public CompletableFuture<List<Streams.Entry>> xrange(String key, String start, String end, long count) {
        return call(Streams::entries, "XRANGE", key, start, end, "COUNT", count);
    }

    public CompletableFuture<List<Streams.Entry>> xrevrange(String key, String end, String start, long count) {
        return call(Streams::entries, "XREVRANGE", key, end, start, "COUNT", count);
    }

    public CompletableFuture<Long> xdel(String key, String... ids) {
        return call(Replies::lng, prepend(new Object[]{"XDEL", key}, ids));
    }

    public CompletableFuture<Long> xtrimMaxLen(String key, long maxLen) {
        return call(Replies::lng, "XTRIM", key, "MAXLEN", maxLen);
    }

    public CompletableFuture<Long> xtrimMinId(String key, String minId) {
        return call(Replies::lng, "XTRIM", key, "MINID", minId);
    }

    /** XGROUP CREATE, at {@code id} ({@code $} for the stream's last); MKSTREAM makes the stream if absent. */
    public CompletableFuture<Void> xgroupCreate(String key, String group, String id, boolean mkStream) {
        return mkStream ? call(Replies::ok, "XGROUP", "CREATE", key, group, id, "MKSTREAM")
                : call(Replies::ok, "XGROUP", "CREATE", key, group, id);
    }

    /** XGROUP DESTROY: whether there was such a group. */
    public CompletableFuture<Boolean> xgroupDestroy(String key, String group) {
        return call(Replies::bool, "XGROUP", "DESTROY", key, group);
    }

    static Object[] readArgs(String group, String consumer, long count, long blockMillis, Map<String, String> streams) {
        List<Object> a = new ArrayList<>();
        if (group != null) {
            a.add("XREADGROUP");
            a.add("GROUP");
            a.add(group);
            a.add(consumer);
        } else {
            a.add("XREAD");
        }
        if (count > 0) {
            a.add("COUNT");
            a.add(count);
        }
        if (blockMillis >= 0) {
            a.add("BLOCK");
            a.add(blockMillis);
        }
        Streams.addStreams(a, streams);
        return a.toArray();
    }

    /**
     * XREADGROUP without waiting: for each stream, its id ({@code >} for new entries, an ID for the
     * consumer's own history); the entries per stream, an empty map for none. To wait, client.blocking().
     */
    public CompletableFuture<Map<String, List<Streams.Entry>>> xreadgroup(String group, String consumer, long count, Map<String, String> streams) {
        return call(Streams::streams, readArgs(group, consumer, count, -1, streams));
    }

    /** XREAD without waiting: the entries after each stream's ID; an empty map for none. */
    public CompletableFuture<Map<String, List<Streams.Entry>>> xread(long count, Map<String, String> streams) {
        return call(Streams::streams, readArgs(null, null, count, -1, streams));
    }

    public CompletableFuture<Long> xack(String key, String group, String... ids) {
        return call(Replies::lng, prepend(new Object[]{"XACK", key, group}, ids));
    }

    public CompletableFuture<Streams.Pending> xpending(String key, String group) {
        return call(Streams::pending, "XPENDING", key, group);
    }

    public CompletableFuture<List<Streams.PendingEntry>> xpendingRange(String key, String group, String start, String end, long count) {
        return call(Streams::pendingEntries, "XPENDING", key, group, start, end, count);
    }

    public CompletableFuture<List<Streams.Entry>> xclaim(String key, String group, String consumer, long minIdleMillis, String... ids) {
        return call(Streams::entries, prepend(new Object[]{"XCLAIM", key, group, consumer, minIdleMillis}, ids));
    }

    /** XAUTOCLAIM: pending entries idle at least {@code minIdleMillis}, from {@code start}, at most {@code count}. */
    public CompletableFuture<Streams.AutoClaim> xautoclaim(String key, String group, String consumer, long minIdleMillis, String start, long count) {
        return call(Streams::autoClaim, "XAUTOCLAIM", key, group, consumer, minIdleMillis, start, "COUNT", count);
    }

    public CompletableFuture<List<Streams.Group>> xinfoGroups(String key) {
        return call(Streams::groups, "XINFO", "GROUPS", key);
    }
}
