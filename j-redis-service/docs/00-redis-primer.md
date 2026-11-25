# 00 — Redis primer

This page explains what Redis is, how it works inside, and why it is built the
way it is. It is for engineers who are new to Redis-style data stores; reading
it takes about 20 minutes. j-redis-service copies, simplifies or leaves out
each thing described here on purpose, so this page is the background for the
rest of the design. Sections 1 to 14 describe Redis itself. The last section,
§15, maps each Redis concept to what j-redis-service does, with a link to the
document that explains it.

---

## 1. What Redis is

Redis ("REmote DIctionary Server") is an **in-memory data structure server**.
Clients connect over TCP and send commands. The server keeps one big map from
**keys** to **values**. A key is a binary-safe string: it may hold any bytes.
Each value has a *type*: string, hash, list, set, sorted set, and a few more.

Redis is not a relational database. It has no tables, no queries and no joins.
You work directly on data structures (`LPUSH` onto a list, `ZADD` into a
sorted set), and the documentation gives each operation's time complexity.

Salvatore Sanfilippo created Redis in 2009. It became the default choice for:

- **caching**: hot data kept in RAM with a time to live (TTL), after which it
  disappears
- **sessions**: small records that expire on their own
- **leaderboards**: sorted sets give rank and range queries in O(log n)
- **queues**: lists with blocking pops
- **messaging**: publish/subscribe
- **coordination**: atomic counters, locks, rate limiters

The platform backend uses every one of these. That is why its design needs
something shaped like Redis.

---

## 2. Why it is fast: the execution model

Redis runs every command on one thread, in an event loop:

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

1. **All data is in memory.** A read never touches the disk.
2. **Commands execute on a single thread.** There are no locks and no lock
   contention. Cache lines do not move back and forth between cores, and no
   context switch happens while a command executes. Every command is atomic
   automatically: nothing else can run in the middle of it.
3. **Non-blocking I/O multiplexing.** One thread watches thousands of sockets
   through `epoll` (on Linux; `kqueue` on BSD and macOS) and touches only the
   ones that have data.
4. **Commands are small and predictable.** Most are O(1) or O(log n), so one
   thread can execute hundreds of thousands of them per second.
5. **Pipelining.** A client may send many commands without waiting for the
   replies. The server processes them one after another and replies in order.
   This removes the network round trip for each command, which is usually the
   real cost.

**The price of one thread: one slow command stalls every client.** `KEYS *` on
ten million keys, or deleting a huge set, freezes the server for as long as it
runs. Much of Redis's internal engineering exists to avoid long single
operations: incremental rehashing, lazy freeing, and the cursor-based `SCAN`.

Since Redis 6.0, optional **I/O threads** can read and write sockets in
parallel. Command *execution* is still single-threaded. j-redis-service has the
same split: Netty threads do the I/O, and one thread executes the commands.

---

## 3. The data model

The whole server is one keyspace. Strictly, Redis has 16 numbered databases,
which a client selects with `SELECT`, but almost everyone uses database 0. Each
key holds one value of one type. A command used on a key of the wrong type
fails with `WRONGTYPE Operation against a key holding the wrong kind of value`.

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

A sorted set is two structures that Redis keeps in step:

- a **hash table** from member → score, for an O(1) `ZSCORE`
- a **skip list** ordered by (score, member), for an O(log n) insert, delete,
  rank and range

A skip list is a linked list with extra "express lanes". Each node gets a
random number of levels, from 1 to 32. Higher levels skip more nodes. In each
forward link, Redis stores the number of nodes that the link skips: the
**span**. Adding up the spans along a search path gives a node's **rank** in
O(log n). This one idea makes the question "what is my position on the
leaderboard?" cheap to answer.

---

## 4. Internal encodings

Redis stores small values compactly. When a value grows past a threshold,
Redis converts it to a general structure. The conversion goes one way only.

| Type | Small encoding | Switches to | Default threshold |
|---|---|---|---|
| Hash | listpack (one flat byte array) | hash table | > 128 fields or any value > 64 bytes |
| Sorted set | listpack | skip list + hash table | > 128 members or any member > 64 bytes |
| Set | intset (sorted integer array) or listpack | hash table | > 512 integers |
| List | quicklist of listpacks | (always a quicklist) | node size ~8 KB |
| String | `int` or embedded short string | raw string | 44 bytes |

A small hash in a flat array costs a few dozen bytes. The same data in a hash
table costs hundreds. Most keys in real systems are small, so this matters a
great deal for memory.

---

## 5. Expiry (TTL)

A client can give any key a time to live: `EXPIRE key 60`, or `SET key v EX 60`.
Redis stores the absolute expiry time in milliseconds. It removes expired keys
in two ways:

- **Lazily.** Every command that touches a key first checks whether the key
  has expired. If it has, Redis deletes it and behaves as if it never existed.
  So a client can never read an expired value.
- **Actively.** A background cycle runs 10 times per second by default. It
  samples 20 random keys that have a TTL and deletes the expired ones. If more
  than 25 % of the sample had expired, it repeats at once, within a time
  budget. This frees the memory of keys that nobody reads again.

Redis writes each expired key to the persistence log, and sends it to the
replicas, as an explicit `DEL` command. So every copy agrees on what
disappeared.

---

## 6. Memory limits and eviction

`maxmemory` caps memory use. When the server reaches it, the `maxmemory-policy`
decides what happens. *Eviction* means that the server deletes keys on its own
to make room.

| Policy | Behaviour |
|---|---|
| `noeviction` | Reject writes that would use more memory (`OOM` error). Reads still work. |
| `allkeys-lru` / `volatile-lru` | Evict least-recently-used keys (all keys / only keys with a TTL) |
| `allkeys-lfu` / `volatile-lfu` | Evict least-frequently-used keys |
| `allkeys-random` / `volatile-random` | Evict random keys |
| `volatile-ttl` | Evict the keys closest to expiring |

LRU (least recently used) is **approximated**. Redis does not keep an exact LRU
list for millions of keys. Instead it samples a few keys
(`maxmemory-samples 5`) and evicts the oldest key of the sample. In practice
this is close enough, and it costs almost nothing.

---

## 7. Persistence

Data in RAM disappears when the process stops. Redis offers two ways to keep
it, and you can use both together.

### RDB snapshots

An RDB file (Redis database file) is a compact binary dump of the whole dataset
at one moment. To write it, Redis calls `fork()`. The child process gets a
**copy-on-write** view of the parent's memory, frozen at that instant, and
writes it to disk at its own pace. Meanwhile the parent keeps serving clients.
The operating system copies a memory page only when the parent changes it.

- Small files, fast restart.
- A crash loses everything since the last snapshot.

### AOF (append-only file)

Redis appends every write command to a log. On restart, replaying the log
rebuilds the dataset. The key setting for durability is how often Redis forces
the log to disk with `fsync` (the system call that waits until the data is on
the disk):

| `appendfsync` | Data lost on power failure | Cost |
|---|---|---|
| `always` | nothing acknowledged | an fsync per write batch — slowest |
| `everysec` (default) | up to about 1 second | one fsync per second in the background |
| `no` | whatever the OS had not flushed (often ~30 s) | cheapest |

The log grows forever, so Redis **rewrites** it from time to time. It forks;
the child writes a fresh, minimal representation of the current data; and the
writes that arrived meanwhile are appended afterwards. Since Redis 7.0 this is
**multi-part AOF**: a *base* file (a snapshot), plus *incremental* files, plus
a *manifest* that lists them. So a rewrite never has to rewrite a file in
place.

Two details matter for correctness and are easy to miss:

- **Redis logs a command by its effect, not literally.** It logs `EXPIRE k 60`
  as `PEXPIREAT k <absolute ms>`. Otherwise, replaying the log an hour later
  would give the key a fresh minute. It logs a random command (`SPOP`) as the
  concrete removal that happened.
- A command that changed nothing (deleting a missing key) is not logged.

---

## 8. Atomicity and transactions

- **Every single command is atomic.** This follows from the single thread.
- **`MULTI` … `EXEC`** queues commands and then runs them one after another,
  with nothing in between. There is **no rollback**: if one queued command
  fails at run time, the others still apply. A syntax error found while
  queueing aborts the whole transaction.
- **`WATCH key`** gives optimistic locking. If another client modifies a
  watched key before `EXEC`, the transaction does not run, and `EXEC` returns
  null. The client then retries.
- **Lua scripts** (`EVAL`) and, since 7.0, **Functions** run custom logic
  atomically inside the server. They are the usual answer when `MULTI` cannot
  express "read, decide, then write".

---

## 9. Pub/Sub and Streams

**Pub/Sub** (publish/subscribe): `SUBSCRIBE channel` puts a connection into
subscriber mode. `PUBLISH channel message` delivers the message to every
connection subscribed at that moment. `PSUBSCRIBE news.*` subscribes by a glob
pattern. Pub/Sub is **fire-and-forget**: Redis stores nothing, so a subscriber
that is disconnected misses messages. When a subscriber reads too slowly and
its output buffer passes a limit, Redis **disconnects** it, instead of dropping
messages silently. So the subscriber knows that it missed something.

**Streams** are the durable alternative. A stream is an append-only log. Each
entry has an ID, and consumer groups track what each consumer has processed.

---

## 10. Blocking operations

`BLPOP queue 5` pops from a list. If the list is empty, Redis **parks the
connection** until another client pushes, or until 5 seconds pass. Clients that
wait on the same key are served in the order they arrived. This turns a list
into a work queue with no polling. `BLMOVE` moves the element to another list
atomically. That is the basis of the *reliable queue* pattern: the item stays
in a "processing" list until the worker confirms it.

---

## 11. The protocol: RESP

Redis speaks **RESP** (REdis Serialization Protocol) over TCP. RESP2 has five
types. Each starts with a one-byte marker and ends with `\r\n`:

| Marker | Type | Example |
|---|---|---|
| `+` | simple string | `+OK\r\n` |
| `-` | error | `-ERR unknown command\r\n` |
| `:` | integer | `:42\r\n` |
| `$` | bulk string (length-prefixed, binary-safe) | `$5\r\nhello\r\n` — null is `$-1\r\n` |
| `*` | array | `*2\r\n$3\r\nfoo\r\n:7\r\n` |

A request is an array of bulk strings. A client sends `SET name red` as:

```
*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$3\r\nred\r\n
```

RESP is simple to parse, binary-safe, and still readable in a packet dump.
**RESP3** (Redis 6) adds maps, sets, doubles, booleans and out-of-band *push*
messages. A client opts in with `HELLO 3`.

---

## 12. Scaling: replication, Sentinel, Cluster

Redis has three ways to grow beyond one server:

- **Replication.** Replicas receive the primary's changes asynchronously and
  serve reads. When the primary crashes, the last writes that had not yet
  reached a replica can be lost.
- **Sentinel.** Separate processes monitor the primaries and promote a replica
  when a primary fails.
- **Cluster.** Redis splits the keyspace into 16,384 *hash slots* spread across
  several primaries. `CRC16(key) mod 16384` picks a key's slot. A multi-key
  command works only when all its keys are in the same slot, which a
  `{hash tag}` in the key names can force.

j-redis-service has replication since 2.2: a primary and its replicas, `WAIT`
to confirm that a replica holds a write, and promotion by an operator, guarded
by epochs ([16](16-replication.md)). It has no Sentinel: the backend promotes a
replica with a script that an operator runs (the backend's
[D-8](../../docs/architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)).
It has no Cluster: one primary holds the whole keyspace.

---

## 13. Security

Redis was designed for trusted networks. Its protections, in order of
importance:

1. Bind only to private or loopback interfaces.
2. **Protected mode**: refuse outside connections when no password is set.
3. `requirepass` / `AUTH`.
4. ACL (access control list) users, since Redis 6.
5. TLS (Transport Layer Security, encrypted connections), since Redis 6.
6. Rename or disable dangerous commands, such as `FLUSHALL`, `CONFIG` and
   `DEBUG`.

---

## 14. Versions, licensing, forks

This history explains why alternatives to Redis exist:

- Redis was **BSD-licensed** up to version 7.2.
- In **March 2024**, starting with 7.4, Redis Ltd. moved to a dual
  **RSALv2 / SSPLv1** license. That license is source-available, not open
  source.
- The Linux Foundation forked the last BSD version (7.2.4) as **Valkey**,
  which remains BSD-3-Clause.
- In **May 2025**, from Redis 8.0, **AGPLv3** was added as a third option.

According to Wikipedia at the time of writing, the current releases are
Redis 8.10.2 (September 2026) and Valkey 9.1.2.

j-redis-service is written from the **public documentation of the protocol and
the commands**, not from the Redis source code. It uses the behaviour of the
Redis 7.2 commands as its reference. See
[14-decision-log.md](14-decision-log.md), D-15.

---

## 15. What we copy, simplify, and leave out

The first column is the Redis concept, the second what j-redis-service
(version 2.2.1) does with it, the third where the design explains it.

| Redis concept | j-redis-service | Where |
|---|---|---|
| Single-threaded command execution | **Copied** — one command thread; Netty threads do I/O | [02](02-architecture.md) |
| RESP2 protocol | **Copied** as the wire format; our own client and CLI speak it | [03](03-protocol.md) |
| RESP3 | Left out (`HELLO 3` is answered with `NOPROTO`) | [03](03-protocol.md) |
| Own hash table with incremental rehash, `SCAN` cursor | **Copied** — needed to avoid resize stalls of hundreds of milliseconds in Java | [04](04-data-structures.md) |
| String, Hash, List, Set, Sorted Set | **Copied** | [04](04-data-structures.md), [05](05-commands.md) |
| Stream, with consumer groups and blocking reads | **Copied** in 2.1; held in immutable chunks | [15](15-streams.md) |
| Compact small encodings | **Deferred** to M8, until a measurement or a new requirement asks for them | [04](04-data-structures.md) |
| Bitmaps, HyperLogLog, Geo | Left out: the backend does not use them | [01](01-requirements-and-scope.md) |
| 16 databases (`SELECT`) | Only database 0: `SELECT` of any other number fails with `ERR DB index is out of range` | [05](05-commands.md) |
| Lazy + active expiry | **Copied**, with deterministic time buckets instead of random sampling | [06](06-expiry-and-memory.md) |
| `maxmemory` | **Copied** with `noeviction` only; LRU deferred to M8 | [06](06-expiry-and-memory.md) |
| Pub/Sub incl. patterns, slow-subscriber disconnect | **Copied** | [07](07-pubsub-blocking-transactions.md) |
| Blocking list and zset pops | **Copied**; streams add blocking `XREAD` and `XREADGROUP` | [07](07-pubsub-blocking-transactions.md), [15](15-streams.md) |
| `MULTI`/`EXEC`/`WATCH` | **Copied** | [07](07-pubsub-blocking-transactions.md) |
| Lua scripts, Functions | Left out; 3 extension commands cover the backend's needs | [09](09-integration-patterns.md) |
| Multi-part AOF with effects logging | **Copied**; `appendfsync` is `everysec` or `no`, and `always` is deferred to M8 | [08](08-persistence.md) |
| RDB via `fork()` | **Replaced** — Java cannot fork; we take point-in-time snapshots with per-key copy-on-write | [08](08-persistence.md) |
| Replication | **Copied** in 2.2: a primary and its replicas, `WAIT`, promotion by an operator guarded by epochs | [16](16-replication.md) |
| Sentinel, Cluster, automatic failover | Left out: promotion is scripted and started by a person | [01](01-requirements-and-scope.md) |
| `requirepass`, protected mode, disabled commands | **Copied** | [11](11-operations-and-security.md) |
| ACL users, TLS | Left out: one `requirepass`; loopback or private network only | [11](11-operations-and-security.md) |
| `redis-cli`, `redis-benchmark`, `redis-check-aof`, client libraries | **Replaced** by our own `j-redis-cli`, `j-redis-tools` (`benchmark`, `check-aof`, `dump`) and `j-redis-client` | [10](10-client-library.md), [11](11-operations-and-security.md) |

### What is harder in Java than in C

Redis is written in C. Some of its techniques have no direct equivalent in
Java:

| Issue | Why | Our answer |
|---|---|---|
| No `fork()` | The JVM (Java virtual machine) cannot fork a copy-on-write child | Per-key copy-on-write snapshots on the command thread ([08](08-persistence.md)) |
| Garbage collection pauses | A large, long-lived heap of millions of objects | Generational ZGC (the Z Garbage Collector), whose pauses stay under a millisecond whatever the heap size; few objects per key; a fixed-size heap ([06](06-expiry-and-memory.md)) |
| Per-object overhead | Each Java object has a 12-byte header, aligned to 8 bytes; under ZGC each reference is 8 bytes, not 4 | Store keys and small values as bare `byte[]`; expect roughly 1.5–2× Redis's memory for small keys ([04](04-data-structures.md), [06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing)) |
| `java.util.HashMap` resizes all at once | Growing a map of 10 million entries stalls for hundreds of ms | Own `Dict` with incremental rehash ([04](04-data-structures.md)) |
| `CRC32C` | Available only since Java 9 | The base file's checksum is `java.util.zip.CRC32`, chosen under the old Java 8 constraint and kept for format compatibility ([08](08-persistence.md)) |

---

## Sources

- [Redis — Wikipedia](https://en.wikipedia.org/wiki/Redis)
- [Valkey — Wikipedia](https://en.wikipedia.org/wiki/Valkey)
- [Redis licenses — redis.io](https://redis.io/legal/licenses/)
- [The Redis License Has Changed — Percona](https://www.percona.com/blog/the-redis-license-has-changed-what-you-need-to-know/)
