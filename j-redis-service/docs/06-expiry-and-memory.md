# 06 — Expiry and memory

## 1. How a TTL is stored

`KeyEntry.expireAt` holds an **absolute** Unix time in milliseconds, or `-1`.
Relative forms (`EXPIRE k 60`, `SET k v EX 60`) are converted to absolute time
using the batch clock ([02 §12](02-architecture.md#12-clock)) when executed.

| Operation | Effect on TTL |
|---|---|
| `EXPIRE` family, `SET … EX/PX/EXAT/PXAT`, `SETEX`, `GETEX` | sets it |
| `PERSIST`, `GETEX … PERSIST` | removes it |
| `SET` without `KEEPTTL`, `GETSET`, `J.CAS` without `KEEPTTL` | removes it (value replaced) |
| Modifying a value in place (`HSET`, `LPUSH`, `INCR`, `APPEND`…) | keeps it |
| `RENAME`, `COPY` | moves / copies it with the value |
| `EXPIRE` with a time already in the past | deletes the key |

## 2. Lazy expiry

`Db.lookupRead` and `Db.lookupWrite` check the entry first:

```java
if (e.expireAt != -1 && e.expireAt <= clock.nowMillis()) {
    delete(e);                  // pre-image for a running snapshot, unregister
    propagate("DEL", e.key);    // replay and every reader must agree it is gone
    signalModified(e.key);      // invalidates WATCH
    return null;                // the command sees "no such key"
}
```

A client can therefore **never** read an expired value, however far active
expiry has fallen behind.

## 3. Active expiry

Lazy expiry alone would leave keys nobody reads again in memory forever. Active
expiry reclaims them.

### 3.1 Time buckets

Instead of Redis's random sampling we use **deterministic one-second buckets**.
They reclaim memory predictably and make tests reproducible.

```java
final class ExpiryBuckets {
    TreeMap<Integer, ArrayList<KeyEntry>> buckets;   // bucket → entries registered there
}
// bucket number = seconds since 2024-01-01T00:00Z, stored in KeyEntry.expireBucket (int)
```

An `int` of seconds from 2024 lasts until 2092. Expiry times beyond that are
clamped to the last bucket; lazy expiry still handles reads correctly.

### 3.2 One registration per key

Refreshing a TTL is common — sessions and presence keys are extended over and
over. If every refresh added a registration, a key refreshed every second with a
one-day TTL would accumulate 86,400 stale entries. So:

- **Setting a TTL** registers the entry only if it is not registered yet, or
  if the new bucket is **earlier** than the current one. The old registration
  is then cleared. Extending a TTL does nothing to the buckets.
- **Deleting a key or removing its TTL** clears its registration at once. The
  entry remembers its bucket and its slot in it (`expireBucket`,
  `expireSlot`), so this is O(log buckets). A deleted key, and its value, is
  never kept reachable until its bucket comes due. That matters for long TTLs
  with high churn, such as day-long sessions deleted at logout.
- **Processing a due bucket**, for each non-empty slot:
  - if the key has expired, expire it as in §2;
  - otherwise the TTL was extended: **re-register** it at its current bucket.

Each key has at most one registration, and `registrations()` counts exactly
the occupied slots.

### 3.3 Scheduling

Every 10 ms the background scheduler processes buckets **strictly earlier than
the current second**, in slices of at most 250 µs
([02 §5](02-architecture.md#5-background-work)). An interrupted bucket resumes
where it stopped. Reclamation lag is therefore about one second plus queueing,
which only affects *memory*, never what clients read.

## 4. Expiry and the other subsystems

| Subsystem | What an expiry does |
|---|---|
| AOF | Writes `DEL key`, so replay deletes it at the same point in history |
| `WATCH` | Marks watchers dirty, their `EXEC` aborts ([07 §3](07-pubsub-blocking-transactions.md#3-transactions)) |
| Snapshot | Deletion takes the pre-image first ([08 §5](08-persistence.md#5-the-fork-free-snapshot)) |
| Blocked clients | Nothing: a missing list is not a ready list |
| AOF loading | Replay never deletes a key because of the clock: no lazy or active expiry, and a `PEXPIREAT` in the past only sets the TTL. Replay must reproduce the history exactly. Deleting early would change the outcome of later logged commands on that key, such as a `PERSIST` that happened while it was still alive. |
| Base-file loading | Every key is loaded with its TTL, even one already in the past, for the same reason |
| After loading | Keys whose TTL has passed are invisible at once (lazy expiry) and are removed by active expiry, which logs their `DEL` as usual |

## 5. `maxmemory`

| Directive | Default | Meaning |
|---|---|---|
| `maxmemory` | `0` (no limit) | Limit on the **estimated** data size ([04 §8](04-data-structures.md#8-memory-accounting)) |
| `maxmemory-policy` | `noeviction` | The only policy in v1 |

Before running a `DENYOOM` command, dispatch compares the estimate with
`maxmemory`; over the limit it replies
`-OOM command not allowed when used memory > 'maxmemory'.` The check happens
before execution, so the command that crosses the limit succeeds and the next
one fails, as in Redis.

**`DENYOOM` commands** are those that can create a key or add bytes: the `SET`
family, `APPEND`, `SETRANGE`, `INCR` family (can create), `MSET`, `HSET` family,
`LPUSH`/`RPUSH` family, `LINSERT`, `LSET`, `SADD`, the `*STORE` commands,
`ZADD`, `ZINCRBY`, `COPY`, `J.CAS`. Commands that only remove or read — `DEL`,
pops, `EXPIRE` — are always allowed, so an operator can always free memory.

Why only `noeviction`: silently deleting a one-time token or session to make
room would surface as a mysterious application bug. A hard, visible error is better; set
`maxmemory` well above the expected data size and alert on the ratio
([11](11-operations-and-security.md#8-runbook)).

## 6. JVM heap sizing

| Setting | Recommendation | Why |
|---|---|---|
| `-Xms` = `-Xmx` | e.g. `-Xms8g -Xmx8g` | No resize pauses; memory committed at start |
| Heap ÷ `maxmemory` | **2–3×** | GC headroom, reply buffers, snapshot chunks, rehash tables |
| Collector | **generational ZGC**, `-XX:+UseZGC -XX:+ZGenerational` | Sub-millisecond pauses whatever the heap size (D-31) |
| Pre-touch | `-XX:+AlwaysPreTouch` | Page faults at start-up, not during play |
| Netty fast path | `--add-opens java.base/java.nio=ALL-UNNAMED` | Without it Netty cannot use its direct-buffer constructor and falls back to a slower cleaner |

For the expected data set (well under 1 GB) an **8 GB heap with `maxmemory 4gb`**
is generous. The full flag set is in
[11 §4](11-operations-and-security.md#4-running-on-linux).

### ZGC makes every stored object bigger

ZGC needs 64-bit coloured pointers, so it **turns compressed oops off**. Every
reference field inside a stored object grows from 4 bytes to 8. It does keep
*compressed class pointers*, so the object header stays 12 bytes — the two are
separate flags, and treating them as one was a real bug (D-32).

Measured on Temurin 21:

| | G1 (compressed oops) | ZGC | change |
|---|---|---|---|
| per key (`KEY_ENTRY`) | 64 B | 80 B | +25 % |
| per hash field | 40 B | 56 B | +40 % |
| per set member | 32 B | 48 B | +50 % |
| per sorted-set member | 40 B | 56 B | +40 % |
| container | 104 B | 144 B | +38 % |

So the same dataset reports roughly a third more `used_memory` than it did
under Java 8, and `maxmemory` has to be raised to match
([guide 7 §7.1b](guide/07-upgrade-guide.md#71b-upgrading-100--200-the-java-21-move)).
The **≤ 31 GB heap rule no longer applies**: it existed to keep compressed oops,
which ZGC gives up anyway, so there is no cliff at 32 GB.

The estimate adapts automatically — `MemoryEstimator` asks HotSpot for both
flags at start-up — so no configuration expresses this. Only the resulting
number changes.

### What the GC sees

- **Long-lived:** millions of entries, keys, values, skip-list nodes. ZGC marks
  them concurrently; cost grows with the object count, which is why
  [04](04-data-structures.md) avoids wrapper objects.
- **Short-lived:** request `argv` arrays and temporary objects per command. They
  die young and are cheap to collect. Reply buffers come from Netty's pooled
  allocator and are not garbage.

NFR-7 (no pause > 50 ms at 8 GB) is verified in M7 with GC logging enabled.

## 7. Deferred: eviction (M8)

If a later need arises for cache-style data, add approximate LRU as Redis does:
a 24-bit last-access clock in `KeyEntry`, sample 5 keys, keep a pool of the 16
best candidates, evict until under the limit before running a `DENYOOM` command.
Policies `allkeys-lru` and `volatile-lru`.
