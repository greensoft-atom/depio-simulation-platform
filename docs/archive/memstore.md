# 04 — memstore: the custom redis-like service

> **Superseded (2026-09-21)** by the detailed design in
> [../j-redis-service/](../../j-redis-service/README.md). This document is kept for
> history. Where they differ, j-redis-service wins — notably the wire protocol
> (RESP2), persistence (per-key copy-on-write snapshots; the `SNAPSHOT-CURSOR`
> scheme in §6 is unsound for multi-key commands), and the custom commands in
> §4, most of which become standard commands. The mapping is in
> [j-redis-service/docs/09-title-integration.md](../../j-redis-service/docs/09-integration-patterns.md#1-relation-to-the-memstore-design).

A single-jar, single-node, in-memory data structure server written in Java,
with a Java client library and an **embedded mode** (same API, in-process,
for tests and tools). It deliberately implements a small Redis-shaped subset
plus a few product-specific commands.

## 1. Scope

| Included in v1 | Deliberately excluded |
|---|---|
| STRING, HASH, SET, LIST, ZSET | Clustering, replication, Lua scripting |
| Key TTL (EXPIRE/TTL/PERSIST) | Transactions/MULTI (single-thread + custom atomic commands cover our needs) |
| PUB/SUB with pattern channels | Streams, geo, HyperLogLog, bitmaps |
| Blocking pops (BRPOP, BRPOPLPUSH) | ACLs (bind to loopback; optional shared secret) |
| AOF persistence + periodic snapshot | Fork-based background save |
| Pipelining, request ids | RESP compatibility (own binary protocol; optional text mode for a debug CLI) |
| INFO/metrics, SCAN with cursor | |

## 2. Architecture

```
 clients ──TCP──► Netty IO threads (2) ──decode──► MPSC CommandQueue ──► CommandThread (1)
                                       ◄──encode── response ByteBuf  ◄──┘        │
                                                                                  ▼
                                                                          Dataset (HashMap<Key, Value>)
                                                                          ExpiryIndex, PubSubRegistry,
                                                                          BlockedClients
                                                                                  │ mutations
                                                                                  ▼
                                                                          AofWriter thread (batched, fsync 1 s)
```

Like Redis, **one command thread** owns the dataset. This gives every command
atomicity for free and makes multi-key title commands (claim ticket, leaderboard
around-me) trivial to write correctly. On Java 8 a single thread executes
300–600 k simple commands/s with pipelining, far above what hub + a handful
of arenas generate.

Responses are written back by handing a `ByteBuf` to the originating
`Channel` (`channel.writeAndFlush` is thread-safe; batch flushes per drained
queue chunk with `write` + one `flush` for efficiency).

Blocking commands (`BRPOP`) do not block the command thread: the client is
parked in `BlockedClients[key]`, and the next `LPUSH` on that key pops and
serves it immediately. A timeout wheel wakes expired waiters with a nil reply.

## 3. Data model

```java
abstract class Value { long expireAtMs = -1; abstract byte type(); abstract long memoryEstimate(); }
final class StringValue extends Value { byte[] bytes; }                  // also used for counters (parse on INCR)
final class HashValue   extends Value { HashMap<ByteKey, byte[]> map; }
final class SetValue    extends Value { HashSet<ByteKey> set; }
final class ListValue   extends Value { ArrayDeque<byte[]> deque; }      // LPUSH/RPOP both O(1)
final class ZSetValue   extends Value { HashMap<ByteKey, Double> scores; SkipList ranked; }
```

`ByteKey` wraps `byte[]` with cached hash and proper `equals`. Keys and members
are bytes end to end; the client does UTF-8. `Dataset` is one
`HashMap<ByteKey, Value>` (initial capacity 1 M, load 0.75). For 64 GB total
RAM with ~8 GB for memstore, expect ~20 M small keys.

### Sorted set with rank queries

Leaderboards need `ZRANK`, `ZRANGE by rank`, `ZRANGEBYSCORE` and "around me",
all O(log n). `TreeMap` gives ordered iteration but not rank. Implement a
skip list with span counts (the Redis design):

```java
final class SkipList {
    static final int MAX_LEVEL = 32; static final double P = 0.25;
    static final class Node { double score; ByteKey member; Node[] forward; int[] span; Node backward; }
    Node head; int level = 1; int length;

    void insert(double score, ByteKey m)           // O(log n): walk levels, accumulate rank, splice
    boolean delete(double score, ByteKey m)        // O(log n)
    int  rank(double score, ByteKey m)            // O(log n): sum spans along the search path
    Node byRank(int rank)                          // O(log n)
    Node firstGte(double score)                    // O(log n) for ZRANGEBYSCORE
}
```

Comparison order is `(score, member)`; ties broken by member bytes so the
order is deterministic. `ZINCRBY` = delete + insert. Around-me = `rank(me)`,
then `byRank(rank - k)` and walk forward `2k+1` nodes.

### Expiry

`ExpiryIndex` = a `TreeMap<Long expireAtMs, ArrayList<ByteKey>>` bucketed to
100 ms, plus lazy expiry on every key access. The command thread, between
draining queue chunks and at least every 100 ms, pops buckets `≤ now` and
deletes those keys if their `expireAtMs` still matches (a key re-set with a
new TTL leaves a stale bucket entry that is simply skipped).

### Pub/Sub

`PubSubRegistry`: `HashMap<ByteKey channel, ArrayList<Channel>>` and a list of
pattern subscriptions (glob → compiled matcher). `PUBLISH` writes the message
frame to each subscriber channel on the command thread (cheap: ~µs per
subscriber). Subscriber sockets that are not writable get messages dropped
(pub/sub is fire-and-forget by contract; anything that must not be lost uses
lists).

## 4. Commands

Generic: `PING`, `ECHO`, `DEL k…`, `EXISTS k…`, `EXPIRE k sec`, `PEXPIRE`,
`TTL`, `PERSIST`, `TYPE`, `KEYS glob` (dev only), `SCAN cursor [MATCH] [COUNT]`,
`DBSIZE`, `INFO`, `FLUSHALL` (guarded by config), `SAVE`, `BGREWRITEAOF`.

String: `GET`, `SET k v [EX s] [NX|XX]`, `SETNX`, `MGET`, `MSET`, `INCR`,
`INCRBY`, `DECR`, `GETSET`, `GETDEL`.

Hash: `HSET k f v [f v…]`, `HGET`, `HMGET`, `HGETALL`, `HDEL`, `HEXISTS`,
`HINCRBY`, `HLEN`, `HKEYS`.

List: `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`, `LREM k count v`,
`RPOPLPUSH src dst`, `BRPOP k… timeout`, `BRPOPLPUSH src dst timeout`, `LTRIM`.

Set: `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`, `SCARD`, `SRANDMEMBER`, `SPOP`.

ZSet: `ZADD k [NX|XX] score m…`, `ZINCRBY`, `ZREM`, `ZSCORE`, `ZCARD`,
`ZRANK`, `ZREVRANK`, `ZRANGE k start stop [WITHSCORES]`, `ZREVRANGE`,
`ZRANGEBYSCORE`, `ZREMRANGEBYRANK`, `ZCOUNT`.

Pub/Sub: `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE`, `PUBLISH`.

Product-specific atomic commands (each is ~20 lines on the command thread; this
is the payoff of owning the store):

| Command | Semantics |
|---|---|
| `CLAIM k` | `HGETALL k` + `DEL k` atomically → join ticket single-use guarantee |
| `ZAROUND k member count` | rank of member and the `count` neighbours above/below with scores, one round trip |
| `ZADDMAX k score member` | set score only if greater than current (best-score leaderboards) |
| `HSETEXNX k sec f v…` | create hash only if absent, with TTL (session creation) |
| `LPUSHCAP k cap v` | push and `LTRIM` to `cap` (kill feeds, recent matches) |
| `INCREX k sec` | `INCR` and set TTL if new (rate limiting) |
| `CAS k expected new` | compare-and-set on strings (optimistic locks for room assignment) |

## 5. Wire protocol

Binary, length-prefixed, pipelined. All integers little-endian.

```
Request:  u32 frameLen | u32 reqId | u8 argc | argc × (u32 len, bytes)   ← arg[0] is the command name
Response: u32 frameLen | u32 reqId | u8 kind | payload
  kind: 0 OK (no payload) | 1 ERR (string) | 2 INT (i64) | 3 BULK (u32 len, bytes | len=0xFFFFFFFF nil)
        4 ARRAY (u32 count, then nested kind+payload…) | 5 DOUBLE (f64) | 6 PUSH (pub/sub message: channel, payload)
```

Max frame 16 MiB. `reqId` is client-assigned and echoed, so the client can
pipeline freely and correlate out of order (the server actually answers in
order, but the client must not rely on it because of pub/sub pushes
interleaving). Text debug mode: if the first byte is `*` the connection
speaks a trivial `*argc\r\n$len\r\narg\r\n…` request / `+OK` `-ERR` `:int`
`$bulk` `*array` reply subset, so `nc`/`redis-cli`-style poking works.

Netty pipeline: `LengthFieldBasedFrameDecoder(16 MiB, 0, 4, 0, 4)` →
`RequestDecoder` (allocates one `Command` object with `byte[][] args` — this
is off the command thread so allocation is acceptable) → `CommandQueue.offer`.

## 6. Persistence

**AOF (append-only file).** Every mutating command, after execution, is
appended as its encoded request frame to a `BufferedOutputStream` on the AOF
thread (handoff via an MPSC queue of the already-encoded `byte[]`). The AOF
thread `flush()`es and `fsync`s once per second (`appendfsync everysec`
semantics: worst-case loss = 1 s of writes; acceptable for a cache whose
important data — match results — is also spooled by the producer until acked
by hub after Mongo commit).

**Snapshot.** Java cannot `fork()` for a copy-on-write dump, so the snapshot
is written **by the command thread** in slices to avoid a long stall:

```
SNAPSHOT START  → new file snap.tmp; set dataset.snapshotInProgress=true; record AOF offset
each drain cycle → serialize up to 2 000 keys (iterator over a stable key array captured at start)
                    (~1–3 ms per slice; commands keep flowing between slices)
mutations during snapshot → normal AOF writes continue; keys already serialised are fine
                    (replay of AOF after snapshot reapplies them), keys not yet serialised are
                    serialised in their new state (also fine: AOF replay is idempotent for
                    SET-style ops and the recorded AOF offset marks where replay starts)
SNAPSHOT END    → fsync, rename snap.tmp → snap.rdb, truncate AOF to entries after the recorded
                    offset (write new AOF file, atomic rename)
```

Caveat: non-idempotent ops (`INCR`, `LPUSH`, `ZINCRBY`) replayed over an
already-updated key would double-apply. Solve it the simple way: during a
snapshot, mutations to a **not-yet-serialised** key are applied normally, and
mutations to an **already-serialised** key are recorded to the AOF (as they
are anyway); at load time, replay only AOF entries whose key was serialised
*before* the mutation — tracked by writing a `SNAPSHOT-CURSOR key` marker to
the AOF each slice. Loading = read snapshot, then replay AOF entries, skipping
any entry for a key that appears in the snapshot after that entry's cursor.
Fifty lines, fully deterministic, no pause. Schedule snapshots hourly and
whenever AOF > 512 MiB.

Startup: load `snap.rdb` (if any), replay AOF, then accept connections.
Typical dataset (few hundred MB) loads in seconds.

Snapshot file format: `MEMS` magic, version, then `[u8 type][key][expireAt i64][type-specific body]*`, `EOF` marker, CRC32.

## 7. Client library (`memstore-client`)

```java
MemstoreClient c = MemstoreClient.connect("127.0.0.1", 6400, eventLoopGroup);   // one TCP connection, pipelined
CompletableFuture<byte[]>   f1 = c.get("sess:abc");
CompletableFuture<Long>     f2 = c.zincrby("lb:s1:ffa", 120, "player:42");
CompletableFuture<Map<String,String>> f3 = c.claim("ticket:xyz");
c.subscribe("team:77", (channel, payload) -> ...);                              // pushes on client IO thread
```

- One connection per process is enough (pipelining); use a second for
  blocking pops (`BRPOP` parks the connection) and a third for subscriptions.
- Timeouts (default 2 s) fail the future; the client reconnects with
  exponential backoff (100 ms → 5 s) and re-subscribes channels automatically.
- A `MemstoreSync` wrapper (`get()` → `join()` with timeout) exists for hub
  code paths that are already blocking (Mongo work), never for arena room threads.
- **Embedded mode:** `MemstoreClient.embedded()` returns an instance backed by an
  in-process `Dataset` + the same command implementations, executed on a
  dedicated thread. Used in unit tests and the replay tool; no sockets.

Arena fallback: if memstore is unreachable, `LPUSH` of results goes to a local
spool file (`/var/lib/title/spool/arena-1.jsonl`); the housekeeper replays the
spool when the connection returns. Reads that fail (ticket claim) reject the
join with `Kick(TRY_AGAIN)`.

## 8. Operations

- Config: `memstore.conf` (port, bind, maxmemory, dir, snapshot interval, auth).
- `maxmemory` reached → reject writes with `ERR OOM` (no eviction in v1: our
  keys have explicit TTLs; eviction hides bugs).
- `INFO` reports: keys, expires, memory estimate, ops/s, queue depth, clients,
  AOF size, last snapshot time/duration, slowest command in last minute.
- CLI: `memstore-cli` (jar) speaking the text mode for inspection.
- Tests: command unit tests against embedded mode; persistence tests that
  write → kill → reload → compare; a fuzz test that runs random ops in both
  memstore and a reference `HashMap` model.
