# 00 — Redis primer

What Redis is, how it works internally, and why it is built the way it is.
Everything j-redis-service does is a deliberate copy, simplification, or
omission of something described here, so this is the background for the rest
of the design. The last section maps each Redis concept to our decision.

---

## 1. What Redis is

Redis ("REmote DIctionary Server") is an **in-memory data structure server**.
Clients connect over TCP and send commands; the server keeps a single big map
from **keys** (binary-safe strings) to **values**, where each value has a
*type* — string, hash, list, set, sorted set, and a few more.

It is not a relational database: there are no tables, no queries, no joins.
You operate directly on data structures (`LPUSH` onto a list, `ZADD` into a
sorted set) and each operation has a documented time complexity.

Created by Salvatore Sanfilippo in 2009, it became the default choice for:

- **caching** — hot data kept in RAM with a time-to-live
- **sessions** — small records that expire on their own
- **leaderboards** — sorted sets give rank and range queries in O(log n)
- **queues** — lists with blocking pops
- **messaging** — publish/subscribe
- **coordination** — atomic counters, locks, rate limiters

Every one of those appears in the platform backend, which is why the design needs
something Redis-shaped.

---

## 2. Why it is fast: the execution model

```
   clients ──TCP──►  ┌───────────────────────────────────────────────┐
                     │  event loop (epoll / kqueue)                   │
                     │   1. which sockets are readable?               │
                     │   2. read bytes, parse complete commands       │
                     │   3. EXECUTE each command  ◄── one thread,     │
                     │      against data in RAM       one at a time   │
                     │   4. append reply bytes to the client buffer   │
                     │   5. write buffers to sockets                  │
                     │   6. run timers (expiry, stats, rehash)        │
                     └───────────────────────────────────────────────┘
```

Five properties make this fast:

1. **All data is in memory.** A read never touches disk.
2. **Commands execute on a single thread.** No locks, no lock contention, no
   cache-line ping-pong between cores, no context switches while executing.
   Every command is automatically atomic: nothing else can run in the middle
   of it.
3. **Non-blocking I/O multiplexing.** One thread watches thousands of sockets
   via `epoll` and only touches the ones with data.
4. **Commands are small and predictable.** Most are O(1) or O(log n), so one
   thread can execute hundreds of thousands per second.
5. **Pipelining.** A client may send many commands without waiting for
   replies; the server processes them back to back and replies in order. This
   removes the network round trip per command, which is usually the real cost.

The price of one thread is that **one slow command stalls everyone**. `KEYS *`
on ten million keys, or deleting a huge set, freezes the server for its whole
duration. Much of Redis's internal engineering — incremental rehashing, lazy
freeing, cursor-based `SCAN` — exists to avoid long single operations.

Since Redis 6.0, optional **I/O threads** can read and write sockets in
parallel. Command *execution* is still single-threaded. j-redis-service has the
same split: Netty threads do I/O; one thread executes.

---

## 3. The data model

The whole server is one keyspace (strictly, 16 numbered databases selected with
`SELECT`, but almost everyone uses database 0). Each key holds one value of one
type. Using a command of the wrong type fails with
`WRONGTYPE Operation against a key holding the wrong kind of value`.

| Type | What it holds | Typical commands | Cost | Typical backend use |
|---|---|---|---|---|
| **String** | Bytes, up to 512 MB. Also used as integer or float counters. | `GET` `SET` `INCR` `APPEND` | O(1) | session → user id, counters, locks |
| **Hash** | A map of field → value inside one key | `HSET` `HGET` `HGETALL` `HINCRBY` | O(1) per field | one-time token, object record |
| **List** | Ordered sequence, push/pop at both ends | `LPUSH` `RPOP` `LRANGE` `BLPOP` | O(1) at ends, O(n) in middle | work queues, chat history |
| **Set** | Unordered unique members | `SADD` `SISMEMBER` `SINTER` | O(1) per member | tag indexes, online sets |
| **Sorted set (zset)** | Unique members each with a `double` score, kept ordered by score | `ZADD` `ZRANGE` `ZRANK` `ZINCRBY` | O(log n) | leaderboards, waiting queues, delayed jobs |
| Stream | Append-only log with consumer groups | `XADD` `XREADGROUP` | O(1) append | durable event log |
| Bitmap, HyperLogLog, Geo | Specialised encodings on strings / zsets | `SETBIT` `PFADD` `GEOADD` | varies | analytics |

### How a sorted set works

A sorted set is two structures kept in step:

- a **hash table** from member → score, for O(1) `ZSCORE`
- a **skip list** ordered by (score, member), for O(log n) insert, delete,
  rank, and range

A skip list is a linked list with extra "express lanes": each node randomly
gets 1..32 levels, and higher levels skip more nodes. Redis stores in each
forward link the number of nodes it skips (the **span**); adding spans along
a search path gives a node's **rank** in O(log n). That single idea is what
makes "what is my position on the leaderboard" cheap.

---

## 4. Internal encodings

Redis stores small values compactly and switches to a general structure when
they grow. The switch is one-way.

| Type | Small encoding | Switches to | Default threshold |
|---|---|---|---|
| Hash | listpack (one flat byte array) | hash table | > 128 fields or any value > 64 bytes |
| Sorted set | listpack | skip list + hash table | > 128 members or any member > 64 bytes |
| Set | intset (sorted integer array) or listpack | hash table | > 512 integers |
| List | quicklist of listpacks | (always a quicklist) | node size ~8 KB |
| String | `int` or embedded short string | raw string | 44 bytes |

A small hash in a flat array costs a few dozen bytes; the same data in a hash
table costs hundreds. Since most keys in real systems are small, this matters
a great deal for memory.

---

## 5. Expiry (TTL)

Any key can be given a time-to-live (`EXPIRE key 60`, or `SET key v EX 60`).
Redis stores the absolute expiry time in milliseconds and removes expired keys
two ways:

- **Lazily** — every command that touches a key first checks whether it has
  expired, and if so deletes it and behaves as if it never existed. A client
  can therefore never read an expired value.
- **Actively** — a background cycle (10 times per second by default) samples 20
  random keys that have a TTL and deletes the expired ones. If more than 25 %
  of the sample was expired, it repeats immediately, within a time budget.
  This reclaims memory for keys nobody reads again.

Expired keys are written to the persistence log and replicas as explicit `DEL`
commands, so everyone agrees on what disappeared.

---

## 6. Memory limits and eviction

`maxmemory` caps memory use. When it is reached, the `maxmemory-policy` decides
what happens:

| Policy | Behaviour |
|---|---|
| `noeviction` | Reject writes that would use more memory (`OOM` error). Reads still work. |
| `allkeys-lru` / `volatile-lru` | Evict least-recently-used keys (all keys / only keys with a TTL) |
| `allkeys-lfu` / `volatile-lfu` | Evict least-frequently-used keys |
| `allkeys-random` / `volatile-random` | Evict random keys |
| `volatile-ttl` | Evict the keys closest to expiring |

LRU is **approximated**: Redis samples a few keys (`maxmemory-samples 5`) and
evicts the oldest of the sample, instead of maintaining an exact LRU list for
millions of keys. It is close enough in practice and costs almost nothing.

---

## 7. Persistence

Data in RAM disappears when the process stops. Redis offers two mechanisms,
usable together.

### RDB snapshots

A compact binary dump of the whole dataset at one moment. Redis calls `fork()`:
the child process gets a **copy-on-write** view of the parent's memory frozen
at that instant and writes it to disk at leisure, while the parent keeps
serving clients. Pages are physically copied only when the parent modifies them.

- Small files, fast restart.
- Everything since the last snapshot is lost on a crash.

### AOF (append-only file)

Every write command is appended to a log. On restart, replaying the log
rebuilds the dataset. How often the log is forced to disk (`fsync`) is the key
durability knob:

| `appendfsync` | Data lost on power failure | Cost |
|---|---|---|
| `always` | nothing acknowledged | an fsync per write batch — slowest |
| `everysec` (default) | up to about 1 second | one fsync per second in the background |
| `no` | whatever the OS had not flushed (often ~30 s) | cheapest |

The log grows forever, so Redis periodically **rewrites** it: it forks, the
child writes a fresh minimal representation of the current data, and new writes
accumulated meanwhile are appended afterwards. Since Redis 7.0 this is
**multi-part AOF**: a *base* file (snapshot) plus *incremental* files plus a
*manifest* listing them, so a rewrite never has to rewrite a file in place.

Two details matter for correctness and are easy to miss:

- **Commands are logged by their effect, not literally.** `EXPIRE k 60` is
  logged as `PEXPIREAT k <absolute ms>`; otherwise replaying the log an hour
  later would give the key a fresh minute. Random commands (`SPOP`) are logged
  as the concrete removal that happened.
- A command that changed nothing (deleting a missing key) is not logged.

---

## 8. Atomicity and transactions

- **Every single command is atomic** — a consequence of the single thread.
- **`MULTI` … `EXEC`** queues commands and runs them back to back with nothing
  in between. There is **no rollback**: if one queued command fails at runtime,
  the others still apply. Syntax errors detected while queueing abort the whole
  transaction.
- **`WATCH key`** gives optimistic locking: if any watched key is modified by
  someone else before `EXEC`, the transaction is not executed and `EXEC`
  returns null. The client retries.
- **Lua scripts** (`EVAL`) and, since 7.0, **Functions** run custom logic
  atomically inside the server — the usual answer when `MULTI` cannot express
  "read, decide, then write".

---

## 9. Pub/Sub and Streams

**Pub/Sub**: `SUBSCRIBE channel` puts a connection into subscriber mode;
`PUBLISH channel message` delivers to everyone currently subscribed.
`PSUBSCRIBE news.*` subscribes by glob pattern. It is **fire-and-forget**:
nothing is stored, and a subscriber that is disconnected misses messages. A
subscriber that reads too slowly is **disconnected** when its output buffer
exceeds a limit, rather than having messages silently dropped — so it knows.

**Streams** are the durable alternative: an append-only log with IDs and
consumer groups that track what each consumer has processed.

---

## 10. Blocking operations

`BLPOP queue 5` pops from a list, or, if the list is empty, **parks the
connection** until another client pushes or 5 seconds pass. Waiters on the same
key are served in first-come order. This turns a list into a work queue with no
polling. `BLMOVE` atomically moves the element to another list, which is the
basis of the *reliable queue* pattern: the item stays in a "processing" list
until the worker confirms it.

---

## 11. The protocol: RESP

Redis speaks **RESP** (REdis Serialization Protocol) over TCP. RESP2 has five
types, each starting with a one-byte marker and ending in `\r\n`:

| Marker | Type | Example |
|---|---|---|
| `+` | simple string | `+OK\r\n` |
| `-` | error | `-ERR unknown command\r\n` |
| `:` | integer | `:42\r\n` |
| `$` | bulk string (length-prefixed, binary-safe) | `$5\r\nhello\r\n` — null is `$-1\r\n` |
| `*` | array | `*2\r\n$3\r\nfoo\r\n:7\r\n` |

A request is an array of bulk strings. `SET name red` is sent as:

```
*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$3\r\nred\r\n
```

It is simple to parse, binary-safe, and still readable in a packet dump.
**RESP3** (Redis 6) adds maps, sets, doubles, booleans and out-of-band *push*
messages; clients opt in with `HELLO 3`.

---

## 12. Scaling: replication, Sentinel, Cluster

- **Replication** — replicas stream the primary's changes asynchronously and
  serve reads. A primary crash can lose the last writes that had not reached
  a replica.
- **Sentinel** — separate processes monitor primaries and promote a replica on
  failure.
- **Cluster** — the keyspace is split into 16,384 *hash slots* spread across
  primaries; `CRC16(key) mod 16384` picks the slot. Multi-key commands work only
  when all keys share a slot (forced with `{hash tags}`).

The platform backend runs on one machine, so none of these apply to it.

---

## 13. Security

Redis was designed for trusted networks. Protections, in order of importance:
bind only to private or loopback interfaces; **protected mode** (refuses
outside connections when no password is set); `requirepass` / `AUTH`; ACL users
(Redis 6); TLS (Redis 6); renaming or disabling dangerous commands such as
`FLUSHALL`, `CONFIG`, and `DEBUG`.

---

## 14. Versions, licensing, forks

This history explains why alternatives exist:

- Redis was **BSD-licensed** until version 7.2.
- In **March 2024**, starting with 7.4, Redis Ltd. moved to a dual
  **RSALv2 / SSPLv1** license — source-available, not open source.
- The Linux Foundation forked the last BSD version (7.2.4) as **Valkey**,
  which remains BSD-3-Clause.
- In **May 2025**, from Redis 8.0, **AGPLv3** was added as a third option.

According to Wikipedia at the time of writing, the current releases are
Redis 8.10.2 (September 2026) and Valkey 9.1.2.

j-redis-service is written from the **public documentation of the protocol and
commands**, not from Redis source code, and uses Redis 7.2 command behaviour as
its reference. See [14-decision-log.md](14-decision-log.md), D-15.

---

## 15. What we copy, simplify, and leave out

| Redis concept | j-redis-service | Where |
|---|---|---|
| Single-threaded command execution | **Copied** — one command thread; Netty threads do I/O | [02](02-architecture.md) |
| RESP2 protocol | **Copied** as the wire format; our own client and CLI speak it | [03](03-protocol.md) |
| RESP3 | Left out of v1 (`HELLO 3` answered with `NOPROTO`) | [03](03-protocol.md) |
| Own hash table with incremental rehash, `SCAN` cursor | **Copied** — needed to avoid multi-hundred-ms resize stalls in Java | [04](04-data-structures.md) |
| String, Hash, List, Set, Sorted Set | **Copied** | [04](04-data-structures.md), [05](05-commands.md) |
| Compact small encodings | **Deferred** to M8, after memory is measured | [04](04-data-structures.md) |
| Streams, Bitmaps, HyperLogLog, Geo | Left out | [01](01-requirements-and-scope.md) |
| Lazy + active expiry | **Copied**, with deterministic time buckets instead of random sampling | [06](06-expiry-and-memory.md) |
| `maxmemory` | **Copied** with `noeviction` only; LRU deferred to M8 | [06](06-expiry-and-memory.md) |
| Pub/Sub incl. patterns, slow-subscriber disconnect | **Copied** | [07](07-pubsub-blocking-transactions.md) |
| Blocking list and zset pops | **Copied** | [07](07-pubsub-blocking-transactions.md) |
| `MULTI`/`EXEC`/`WATCH` | **Copied** | [07](07-pubsub-blocking-transactions.md) |
| Lua scripts, Functions | Left out; 3 extension commands cover the backend's needs | [09](09-integration-patterns.md) |
| Multi-part AOF with effects logging | **Copied** | [08](08-persistence.md) |
| RDB via `fork()` | **Replaced** — Java cannot fork; we take point-in-time snapshots with per-key copy-on-write | [08](08-persistence.md) |
| Replication, Sentinel, Cluster | Left out | [01](01-requirements-and-scope.md) |
| `requirepass`, protected mode, disabled commands | **Copied** | [11](11-operations-and-security.md) |
| ACL users, TLS | Left out of v1 | [11](11-operations-and-security.md) |
| `redis-cli`, `redis-benchmark`, client libraries | **Replaced** by our own `j-redis-cli`, `j-redis-benchmark`, `j-redis-client` | [10](10-client-library.md), [11](11-operations-and-security.md) |

### What is harder in Java than in C

| Issue | Why | Our answer |
|---|---|---|
| No `fork()` | The JVM cannot fork a copy-on-write child | Per-key copy-on-write snapshots on the command thread ([08](08-persistence.md)) |
| Garbage collection pauses | A large, long-lived heap of millions of objects | Moderate heap, G1, few objects per key, fixed-size heap ([06](06-expiry-and-memory.md)) |
| Per-object overhead | Each Java object has a 12-byte header, aligned to 8 bytes | Store keys and small values as bare `byte[]`; roughly 1.5–2× Redis's memory for small keys ([04](04-data-structures.md)) |
| `java.util.HashMap` resizes all at once | Growing a 10M-entry map stalls for hundreds of ms | Own `Dict` with incremental rehash ([04](04-data-structures.md)) |
| `CRC32C` | Available since Java 9 | The AOF uses `java.util.zip.CRC32`, chosen under the old Java 8 constraint and kept for format compatibility ([08](08-persistence.md)) |

---

## Sources

- [Redis — Wikipedia](https://en.wikipedia.org/wiki/Redis)
- [Valkey — Wikipedia](https://en.wikipedia.org/wiki/Valkey)
- [Redis licenses — redis.io](https://redis.io/legal/licenses/)
- [The Redis License Has Changed — Percona](https://www.percona.com/blog/the-redis-license-has-changed-what-you-need-to-know/)
