# 06 — Expiry and memory

This page explains how j-redis deletes keys whose time has run out, how it
limits memory, and how to size the Java heap. It is for engineers who change
the expiry or memory code, and for operators who choose `maxmemory` and the JVM
(Java Virtual Machine) options. It covers how a TTL (time to live) is stored,
lazy and active expiry, how expiry interacts with the other parts of the
server, the `maxmemory` limit, heap sizing under ZGC, and the eviction that is
deferred to milestone M8.

## 1. How a TTL is stored

`KeyEntry.expireAt` holds an **absolute** Unix time in milliseconds, or `-1`
when the key has no TTL. When the server runs a relative form (`EXPIRE k 60`,
`SET k v EX 60`), it converts the time to an absolute one with the clock of the
current batch ([02 §12](02-architecture.md#12-clock)).

| Operation | Effect on the TTL |
|---|---|
| The `EXPIRE` family, `SET … EX/PX/EXAT/PXAT`, `SETEX`, `GETEX` | Sets it |
| `PERSIST`, `GETEX … PERSIST` | Removes it |
| `SET` without `KEEPTTL`, `GETSET`, `J.CAS` without `KEEPTTL` | Removes it, because the value is replaced |
| Changing a value in place (`HSET`, `LPUSH`, `INCR`, `APPEND`…) | Keeps it |
| `RENAME`, `COPY` | Moves or copies it with the value |
| `EXPIRE` with a time already in the past | Deletes the key |

## 2. Lazy expiry

**Lazy expiry** means that a key is checked when a command touches it. Every
lookup (`Db.lookupRead`, and `Db.lookupWrite`, which calls it) first checks the
entry's TTL (`Db.expireIfNeeded`):

```java
if (loading || e.expireAt < 0 || e.expireAt > clock.getAsLong()) {
    return false;               // no TTL, not yet due, or the AOF is being loaded (§4)
}
if (!replica) {
    expireEntry(e);             // pre-image for a running snapshot, remove the entry;
}                               // keyExpired logs DEL key, keyModified invalidates WATCH
return true;                    // the command sees "no such key"
```

So a client can **never** read an expired value, however far active expiry has
fallen behind. A key is expired once `now >= expireAt`
([05 §3](05-commands.md#3-deviations-from-redis-72)).

On a replica, an expired key also reads as missing, but the replica does not
delete it. The replica deletes a key only when the primary's `DEL` arrives over
the replication link ([16](16-replication.md)).

## 3. Active expiry

**Active expiry** deletes expired keys that no command reads. Without it, lazy
expiry alone would keep such keys in memory forever.

### 3.1 Time buckets

Redis samples keys at random. j-redis instead uses **deterministic one-second
buckets**. They reclaim memory predictably and make tests reproducible.

```java
final class ExpiryBuckets {
    TreeMap<Integer, ArrayList<KeyEntry>> buckets;   // bucket → entries registered there
}
// bucket number = seconds since 2024-01-01T00:00Z, stored in KeyEntry.expireBucket (int)
```

An `int` of seconds from 2024 lasts until 2092. The server puts an expiry time
beyond that in the last bucket. Lazy expiry still handles reads of such a key
correctly.

### 3.2 One registration per key

Applications refresh TTLs often: they extend sessions and presence keys again
and again. If every refresh added a registration, a key with a one-day TTL that
is refreshed every second would collect 86,400 stale entries. So the rules are:

- **Setting a TTL** registers the entry only if it is not registered yet, or if
  the new bucket is **earlier** than the current one. In that case the server
  clears the old registration. Extending a TTL does nothing to the buckets.
- **Deleting a key or removing its TTL** clears its registration at once. The
  entry remembers its bucket and its slot in it (`expireBucket`,
  `expireSlot`), so clearing it costs O(log buckets). The buckets never keep a
  deleted key, or its value, reachable until its bucket comes due. That matters
  for long TTLs with high churn, such as day-long sessions deleted at logout.
- **Processing a due bucket**: for each slot that is not empty,
  - if the key has expired, the server expires it as in §2;
  - otherwise the TTL was extended, and the server **re-registers** the key in
    its current bucket.

Each key has at most one registration, and `registrations()` counts exactly
the occupied slots.

### 3.3 Scheduling

The command thread runs active expiry between batches of commands
([02 §5](02-architecture.md#5-background-work)):

1. Every 10 ms, it processes the buckets **strictly earlier than the current
   second**.
2. Each run lasts at most one slice: `background-slice-micros`, 250 µs by
   default.
3. If a run stops in the middle of a bucket, the next run continues where it
   stopped.

So the server reclaims an expired key about one second after it expired, plus
any queueing. This delay affects only *memory*, never what clients read. A
replica runs no active expiry, and `DEBUG SET-ACTIVE-EXPIRE 0` turns it off for
tests.

## 4. Expiry and the other subsystems

| Subsystem | What an expiry does |
|---|---|
| AOF (append-only file) | The server writes `DEL key`, so a replay deletes the key at the same point in history. The same `DEL` goes to the replicas. |
| `WATCH` | Marks the watching clients dirty, so their `EXEC` aborts ([07 §3](07-pubsub-blocking-transactions.md#3-transactions)) |
| Snapshot | The deletion first saves the key's pre-image ([08 §5](08-persistence.md#5-the-fork-free-snapshot)) |
| Blocked clients | Nothing: a missing list is not a list with data ready |
| AOF loading | A replay never deletes a key because of the clock. There is no lazy or active expiry, and a `PEXPIREAT` in the past only sets the TTL. The replay must reproduce the history exactly. Deleting a key early would change the result of later logged commands on that key, such as a `PERSIST` that ran while the key was still alive. |
| Base-file loading | Every key is loaded with its TTL, even a TTL already in the past, for the same reason |
| After loading | Keys whose TTL has passed are invisible at once (lazy expiry). Active expiry then removes them and logs their `DEL` as usual |

## 5. `maxmemory`

| Directive | Default | Meaning |
|---|---|---|
| `maxmemory` | `0` (no limit) | The limit on the **estimated** size of the data ([04 §8](04-data-structures.md#8-memory-accounting)) |
| `maxmemory-policy` | `noeviction` | The only policy; any other value is a configuration error |

**The check.** Before the server runs a `DENYOOM` command, it compares the
estimate with `maxmemory`. Above the limit, it replies
`-OOM command not allowed when used memory > 'maxmemory'.` The check runs
before the command, so the command that crosses the limit succeeds and the next
one fails, as in Redis.

**Inside a transaction**, the server checks before it queues each command.
Above the limit it queues nothing: it refuses any command, `DENYOOM` or not,
with the same `-OOM` error, and the transaction is aborted. At `EXEC`, a
transaction that holds a `DENYOOM` command is discarded with `EXECABORT` if the
estimate is then above the limit.

**`DENYOOM` commands** are the commands that can create a key or add bytes:

| Group | Commands |
|---|---|
| Strings | `SET`, `SETNX`, `SETEX`, `PSETEX`, `GETSET`, `MSET`, `MSETNX`, `APPEND`, `SETRANGE`, and the `INCR` family (`INCR`, `DECR`, `INCRBY`, `DECRBY`, `INCRBYFLOAT`), which can create a key |
| Hashes | `HSET`, `HMSET`, `HSETNX`, `HINCRBY`, `HINCRBYFLOAT` |
| Lists | `LPUSH`, `RPUSH`, `LPUSHX`, `RPUSHX`, `LINSERT`, `LSET`, `LMOVE`, `RPOPLPUSH`, `BLMOVE`, `BRPOPLPUSH` |
| Sets | `SADD`, `SINTERSTORE`, `SUNIONSTORE`, `SDIFFSTORE` |
| Sorted sets | `ZADD`, `ZINCRBY` |
| Streams | `XADD`, `XGROUP` |
| Other | `COPY`, `J.CAS` |

Commands that only remove or read data, such as `DEL`, the pops and `EXPIRE`,
are always allowed. So an operator can always free memory.

**Why only `noeviction`.** If the server silently deleted a one-time token or a
session to make room, the application would see a mysterious bug. A hard,
visible error is better. Set `maxmemory` well above the expected data size, and
alert on the ratio of used memory to `maxmemory`
([11](11-operations-and-security.md#8-runbook)).

## 6. JVM heap sizing

| Setting | Recommendation | Why |
|---|---|---|
| `-Xms` = `-Xmx` | For example `-Xms8g -Xmx8g` | No pauses to resize the heap; the memory is committed at start |
| Heap ÷ `maxmemory` | **2–3×** | Headroom for the garbage collector (GC), reply buffers, snapshot chunks and rehash tables |
| Collector | **Generational ZGC**: `-XX:+UseZGC -XX:+ZGenerational` | Pauses under a millisecond, whatever the heap size (D-31) |
| Pre-touch | `-XX:+AlwaysPreTouch` | The JVM touches every heap page at start-up, so page faults happen then and not under load |
| Netty fast path | `--add-opens java.base/java.nio=ALL-UNNAMED` | Without it, Netty cannot use its direct-buffer constructor and falls back to a slower cleaner |

For the expected data set (well under 1 GB), an **8 GB heap with
`maxmemory 3gb`** is generous. That is about 40 % of the heap, as in the shipped
`dist/conf/j-redis.conf`. The shipped systemd unit (`dist/systemd/j-redis.service`)
starts the JVM with `-Xms8g -Xmx8g` and the flags above. The full flag set is
in [11 §4](11-operations-and-security.md#4-running-on-linux).

### ZGC makes every stored object bigger

ZGC needs 64-bit coloured pointers, so it **turns compressed oops off**
("compressed oops" are 4-byte object references). Every reference field inside
a stored object grows from 4 bytes to 8. ZGC does keep *compressed class
pointers*, so the object header stays 12 bytes. These are two separate JVM
flags. Treating them as one was a real bug (D-32).

On Temurin 21, the memory estimate uses these sizes (`MemoryEstimator`):

| | G1 (compressed oops) | ZGC | Change |
|---|---|---|---|
| Per key (`KEY_ENTRY`) | 64 B | 80 B | +25 % |
| Per hash field | 40 B | 56 B | +40 % |
| Per set member | 32 B | 48 B | +50 % |
| Per sorted-set member | 40 B | 56 B | +40 % |
| Container | 104 B | 144 B | +38 % |

So the same dataset reports roughly a third more `used_memory` than it did
under Java 8, and `maxmemory` must be raised to match
([guide 7 §7.1b](guide/07-upgrade-guide.md#71b-upgrading-100--200-the-java-21-move)).

The **"heap at most 31 GB" rule no longer applies.** The rule existed to keep
compressed oops, which ZGC gives up anyway. So there is no cliff at 32 GB.

The estimate adapts by itself: `MemoryEstimator` asks HotSpot, the JVM, for
both flags at start-up. No configuration setting expresses this; only the
resulting number changes.

### What the GC sees

- **Long-lived objects:** millions of entries, keys, values and skip-list
  nodes. ZGC marks them while the application runs. The cost grows with the
  number of objects, which is why [04](04-data-structures.md) avoids wrapper
  objects.
- **Short-lived objects:** the `argv` arrays of requests and the temporary
  objects of each command. They die young and are cheap to collect. Reply
  buffers come from Netty's pooled allocator and are not garbage.

NFR-7 requires no GC pause over 50 ms with an 8 GB heap
([01](01-requirements-and-scope.md)). Milestone M7 is to verify it with GC
logging on; that measurement has not been made yet
([13](13-implementation-plan.md)).

## 7. Deferred: eviction (M8)

**Eviction** means deleting keys to make room. j-redis does not evict. If a
later need for cache-style data arises, M8 would add approximate LRU (least
recently used) eviction, as Redis does:

1. Keep a 24-bit last-access clock in `KeyEntry`.
2. Before a `DENYOOM` command runs above the limit, sample 5 keys.
3. Keep a pool of the 16 best candidates.
4. Evict until the estimate is under the limit.

The policies would be `allkeys-lru` and `volatile-lru`. M8 is optional and has
not started ([13](13-implementation-plan.md)).
