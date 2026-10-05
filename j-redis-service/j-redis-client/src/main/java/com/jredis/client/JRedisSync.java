package com.jredis.client;

import com.jredis.common.Reply;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking facade: each method waits for the reply (at most the command timeout). Two guards turn
 * misuse into immediate errors instead of stalls: calling on a thread marked with
 * {@link ThreadGuard#markNonBlocking()}, or on one of the client's own event-loop threads (which
 * must process the very reply being waited for — a guaranteed deadlock).
 */
public final class JRedisSync {

    private final AsyncCommands async;
    private final ClientResources res;
    private final long timeoutMillis;

    JRedisSync(AsyncCommands async, ClientResources res, long timeoutMillis) {
        this.async = async;
        this.res = res;
        this.timeoutMillis = timeoutMillis;
    }

    <T> T await(CompletableFuture<T> f) {
        if (ThreadGuard.isNonBlocking()) {
            throw new IllegalStateException("blocking j-redis call on a thread marked non-blocking; use the async API");
        }
        if (res.isOwnThread()) {
            throw new IllegalStateException("blocking j-redis call on a client event-loop thread would deadlock; use the async API");
        }
        try {
            return f.get(timeoutMillis + 1000, TimeUnit.MILLISECONDS);   // the request has its own timeout
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            throw new JRedisException("request failed", c);
        } catch (TimeoutException e) {
            throw new JRedisTimeoutException("no reply within " + timeoutMillis + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JRedisException("interrupted while waiting for a reply");
        }
    }

    public Reply send(Object... args) { return await(async.send(args)); }

    // strings
    public String get(String key) { return await(async.get(key)); }
    public byte[] getBytes(String key) { return await(async.getBytes(key)); }
    public boolean set(String key, Object value) { return await(async.set(key, value)); }
    public boolean set(String key, Object value, SetArgs options) { return await(async.set(key, value, options)); }
    public String getdel(String key) { return await(async.getdel(key)); }
    public List<String> mget(String... keys) { return await(async.mget(keys)); }
    public void mset(Map<String, ?> values) { await(async.mset(values)); }
    public long incr(String key) { return await(async.incr(key)); }
    public long incrBy(String key, long delta) { return await(async.incrBy(key, delta)); }
    public long decr(String key) { return await(async.decr(key)); }
    public long decrBy(String key, long delta) { return await(async.decrBy(key, delta)); }
    public double incrByFloat(String key, double delta) { return await(async.incrByFloat(key, delta)); }
    public long append(String key, Object value) { return await(async.append(key, value)); }
    public long strlen(String key) { return await(async.strlen(key)); }

    // keys
    public long del(String... keys) { return await(async.del(keys)); }
    public long exists(String... keys) { return await(async.exists(keys)); }
    public boolean expire(String key, long seconds) { return await(async.expire(key, seconds)); }
    public boolean pexpire(String key, long millis) { return await(async.pexpire(key, millis)); }
    public boolean persist(String key) { return await(async.persist(key)); }
    public long ttl(String key) { return await(async.ttl(key)); }
    public long pttl(String key) { return await(async.pttl(key)); }
    public String type(String key) { return await(async.type(key)); }
    public void rename(String from, String to) { await(async.rename(from, to)); }
    public ScanResult scan(String cursor, String match, int count) { return await(async.scan(cursor, match, count)); }

    // hashes
    public long hset(String key, String field, Object value) { return await(async.hset(key, field, value)); }
    public long hset(String key, Map<String, ?> fields) { return await(async.hset(key, fields)); }
    public boolean hsetnx(String key, String field, Object value) { return await(async.hsetnx(key, field, value)); }
    public String hget(String key, String field) { return await(async.hget(key, field)); }
    public List<String> hmget(String key, String... fields) { return await(async.hmget(key, fields)); }
    public Map<String, String> hgetall(String key) { return await(async.hgetall(key)); }
    public long hdel(String key, String... fields) { return await(async.hdel(key, fields)); }
    public long hlen(String key) { return await(async.hlen(key)); }
    public boolean hexists(String key, String field) { return await(async.hexists(key, field)); }
    public long hincrBy(String key, String field, long delta) { return await(async.hincrBy(key, field, delta)); }
    public List<String> hkeys(String key) { return await(async.hkeys(key)); }
    public List<String> hvals(String key) { return await(async.hvals(key)); }

    // lists
    public long lpush(String key, Object... values) { return await(async.lpush(key, values)); }
    public long rpush(String key, Object... values) { return await(async.rpush(key, values)); }
    public String lpop(String key) { return await(async.lpop(key)); }
    public String rpop(String key) { return await(async.rpop(key)); }
    public List<String> lrange(String key, long start, long stop) { return await(async.lrange(key, start, stop)); }
    public long llen(String key) { return await(async.llen(key)); }
    public long lrem(String key, long count, Object value) { return await(async.lrem(key, count, value)); }
    public void ltrim(String key, long start, long stop) { await(async.ltrim(key, start, stop)); }
    public String lindex(String key, long index) { return await(async.lindex(key, index)); }
    public String lmove(String source, String destination, boolean fromLeft, boolean toLeft) { return await(async.lmove(source, destination, fromLeft, toLeft)); }

    // sets
    public long sadd(String key, Object... members) { return await(async.sadd(key, members)); }
    public long srem(String key, Object... members) { return await(async.srem(key, members)); }
    public Set<String> smembers(String key) { return await(async.smembers(key)); }
    public boolean sismember(String key, Object member) { return await(async.sismember(key, member)); }
    public long scard(String key) { return await(async.scard(key)); }

    // sorted sets
    public long zadd(String key, double score, String member, String... flags) { return await(async.zadd(key, score, member, flags)); }
    public double zincrby(String key, double increment, String member) { return await(async.zincrby(key, increment, member)); }
    public long zrem(String key, String... members) { return await(async.zrem(key, members)); }
    public Double zscore(String key, String member) { return await(async.zscore(key, member)); }
    public Long zrank(String key, String member) { return await(async.zrank(key, member)); }
    public Long zrevrank(String key, String member) { return await(async.zrevrank(key, member)); }
    public long zcard(String key) { return await(async.zcard(key)); }
    public long zcount(String key, String min, String max) { return await(async.zcount(key, min, max)); }
    public List<String> zrange(String key, long start, long stop) { return await(async.zrange(key, start, stop)); }
    public List<ScoredMember> zrangeWithScores(String key, long start, long stop) { return await(async.zrangeWithScores(key, start, stop)); }
    public List<ScoredMember> zrevrangeWithScores(String key, long start, long stop) { return await(async.zrevrangeWithScores(key, start, stop)); }
    public List<String> zrangeByScore(String key, String min, String max) { return await(async.zrangeByScore(key, min, max)); }
    public List<ScoredMember> zpopmin(String key, long count) { return await(async.zpopmin(key, count)); }
    public List<ScoredMember> zpopmax(String key, long count) { return await(async.zpopmax(key, count)); }
    public long zremrangebyscore(String key, String min, String max) { return await(async.zremrangebyscore(key, min, max)); }
    public ZAround zaround(String key, String member, int count, boolean descending) { return await(async.zaround(key, member, count, descending)); }

    // pub/sub, extensions, server
    public long publish(String channel, Object message) { return await(async.publish(channel, message)); }
    public boolean cas(String key, Object expected, Object value) { return await(async.cas(key, expected, value)); }
    public boolean cas(String key, Object expected, Object value, SetArgs ttl) { return await(async.cas(key, expected, value, ttl)); }
    public boolean cad(String key, Object expected) { return await(async.cad(key, expected)); }
    public String ping() { return await(async.ping()); }
    public long dbsize() { return await(async.dbsize()); }
    public String info(String section) { return await(async.info(section)); }
    public void flushall() { await(async.flushall()); }

    // transactions
    /** Runs a transaction built with {@code multi()}; null if a WATCHed key changed. */
    public List<Reply> exec(Transaction tx) { return await(tx.exec()); }

    /** WATCH (leased connections only). */
    public void watch(String... keys) {
        if (!(async instanceof LeasedConnection)) {
            throw new IllegalStateException("WATCH needs client.withLeasedConnection()");
        }
        await(((LeasedConnection) async).watch(keys));
    }

    public void unwatch() {
        if (async instanceof LeasedConnection) {
            await(((LeasedConnection) async).unwatch());
        }
    }

    // streams (docs/15)
    public String xadd(String key, Object... fieldsAndValues) { return await(async.xadd(key, fieldsAndValues)); }
    public String xadd(String key, XAddArgs args, String id, Object... fieldsAndValues) { return await(async.xadd(key, args, id, fieldsAndValues)); }
    public long xlen(String key) { return await(async.xlen(key)); }
    public List<Streams.Entry> xrange(String key, String start, String end) { return await(async.xrange(key, start, end)); }
    public List<Streams.Entry> xrange(String key, String start, String end, long count) { return await(async.xrange(key, start, end, count)); }
    public List<Streams.Entry> xrevrange(String key, String end, String start, long count) { return await(async.xrevrange(key, end, start, count)); }
    public long xdel(String key, String... ids) { return await(async.xdel(key, ids)); }
    public long xtrimMaxLen(String key, long maxLen) { return await(async.xtrimMaxLen(key, maxLen)); }
    public long xtrimMinId(String key, String minId) { return await(async.xtrimMinId(key, minId)); }
    public void xgroupCreate(String key, String group, String id, boolean mkStream) { await(async.xgroupCreate(key, group, id, mkStream)); }
    public boolean xgroupDestroy(String key, String group) { return await(async.xgroupDestroy(key, group)); }
    public Map<String, List<Streams.Entry>> xreadgroup(String group, String consumer, long count, Map<String, String> streams) { return await(async.xreadgroup(group, consumer, count, streams)); }
    public Map<String, List<Streams.Entry>> xread(long count, Map<String, String> streams) { return await(async.xread(count, streams)); }
    public long xack(String key, String group, String... ids) { return await(async.xack(key, group, ids)); }
    public Streams.Pending xpending(String key, String group) { return await(async.xpending(key, group)); }
    public List<Streams.PendingEntry> xpendingRange(String key, String group, String start, String end, long count) { return await(async.xpendingRange(key, group, start, end, count)); }
    public List<Streams.Entry> xclaim(String key, String group, String consumer, long minIdleMillis, String... ids) { return await(async.xclaim(key, group, consumer, minIdleMillis, ids)); }
    public Streams.AutoClaim xautoclaim(String key, String group, String consumer, long minIdleMillis, String start, long count) { return await(async.xautoclaim(key, group, consumer, minIdleMillis, start, count)); }
    public List<Streams.Group> xinfoGroups(String key) { return await(async.xinfoGroups(key)); }
}
