# 04 — Data structures

## 1. Overview

```
Db
 └── keyspace: Dict<KeyEntry>
       KeyEntry ── key: byte[]           value by type:
                   type: byte              STRING  → byte[]
                   value: Object           HASH    → HashValue  = Dict<FieldEntry>
                   expireAt: long (-1)     LIST    → ListValue  = ByteArrayRing
                   expireBucket: int       SET     → SetValue   = Dict<MemberEntry>
                   alive: boolean          ZSET    → ZSetValue  = Dict<ZEntry> + SkipList
                   snapEpoch: int
```

Everything here is owned by the command thread and is not thread-safe by
design ([02 §2](02-architecture.md#2-threads)).

## 2. Why not `java.util.HashMap`

Three reasons, the first decisive:

1. **Resizing stalls.** `HashMap` doubles its table in one go. At 10 million
   entries that means allocating a new array and relinking every node in a
   single call — hundreds of milliseconds during which no command runs. That
   breaks the p99 ≤ 1 ms target ([01](01-requirements-and-scope.md#5-non-functional-requirements))
   for every client at once.
2. **No stable cursor.** `SCAN` must let a client walk the keyspace across many
   calls while keys are added and removed. `HashMap` exposes no bucket index,
   and a resize reorders everything.
3. **Per-entry overhead.** `HashMap.Node` plus a separate key wrapper object
   costs more than an entry that holds the key bytes itself.

So the server has its own `Dict`, following Redis's design.

## 3. `Dict`

### 3.1 Layout

```java
abstract class DictEntry {
    final byte[] key;
    final int hash;         // cached; never recomputed
    DictEntry next;         // chain within a bucket
}

final class Dict<E extends DictEntry> {
    DictEntry[] t0, t1;     // t1 != null only while rehashing
    int used0, used1;
    int rehashIdx = -1;     // next t0 bucket to move; -1 when not rehashing
}
```

Tables are powers of two, minimum 4 buckets. Collisions chain through `next`.

### 3.2 Hashing

**SipHash-1-2** over the key bytes, with a random 128-bit key generated at
start-up — the same choice as Redis. Keys include user-chosen text (names,
team names), and simpler seeded hashes such as MurmurHash3 have known
seed-independent collision attacks, which an attacker could use to turn every
lookup into a long chain scan. The per-process random key also means bucket
order differs between runs, which nothing may depend on.

### 3.3 Growing and shrinking

| Condition | Action | Checked |
|---|---|---|
| `used ≥ size` (load factor 1) | start rehash into a table of the next power of two ≥ `2 × used` | on insert |
| `used < 10 % of size` and `size > 4` | start rehash into the next power of two ≥ `used` | background slice (keyspace); after deletes (collections) |

### 3.4 Incremental rehash

While rehashing, both tables are live:

- **lookup / delete** search `t0` (if the bucket index ≥ `rehashIdx`), then `t1`;
- **insert** goes to `t1` only;
- every lookup, insert, or delete also performs **one rehash step**: move one
  non-empty bucket from `t0` to `t1`, visiting at most 10 empty buckets;
- the background scheduler runs rehash steps on the keyspace `Dict` for up to
  its slice budget ([02 §5](02-architecture.md#5-background-work));
- when `t0` is empty, `t1` becomes `t0`.

Cost per operation stays O(1); the resize work is spread over many operations
and idle time. The only large allocation is the new bucket array — 64 MB for
16M buckets at 4 bytes per reference — which the JVM allocates without touching
existing entries.

### 3.5 `SCAN`: the reverse-binary cursor

The cursor is a bucket index whose bits are incremented **from the most
significant end**. Because a table of size 2ⁿ splits bucket `i` into buckets
`i` and `i + 2ⁿ` when it doubles, walking indices in reversed-bit order visits
every "family" of buckets exactly once, even if the table grows or shrinks
between calls.

```java
long scanStep(long v, Visitor fn) {
    if (!rehashing()) {
        long m0 = t0.length - 1;
        visitBucket(t0, v & m0, fn);
        v |= ~m0;  v = Long.reverse(v);  v++;  v = Long.reverse(v);
    } else {
        DictEntry[] small = smaller(t0, t1), large = larger(t0, t1);
        long m0 = small.length - 1, m1 = large.length - 1;
        visitBucket(small, v & m0, fn);
        do {                                   // every large-table bucket that
            visitBucket(large, v & m1, fn);    // expands from the small one
            v |= ~m1;  v = Long.reverse(v);  v++;  v = Long.reverse(v);
        } while ((v & (m0 ^ m1)) != 0);
    }
    return v;                                  // 0 means the scan is complete
}
```

**Guarantees** (the same as Redis's):

- every element present for the **entire** scan is returned **at least once**;
- an element may be returned **more than once** — callers must tolerate
  duplicates;
- elements added or removed during the scan may or may not be returned.

**`SCAN cursor [MATCH pattern] [COUNT n] [TYPE t]`:** run `scanStep` until at
least `n` (default 10) candidates are collected, the cursor returns to 0, or
`10 × n` buckets have been visited. Then drop expired keys (deleting them),
apply `TYPE`, then `MATCH`. A page can be empty while the cursor is non-zero;
the client simply continues.

Java has no unsigned `long`: cursors are sent with `Long.toUnsignedString` and
parsed with `Long.parseUnsignedLong`.

The snapshot uses the same `scanStep` to walk the keyspace while commands keep
running ([08 §5](08-persistence.md#5-the-fork-free-snapshot)).

### 3.6 Random element

`SPOP`, `SRANDMEMBER`, `HRANDFIELD`, `RANDOMKEY`: pick random bucket indices
until one is non-empty, then a random element of its chain. This is slightly
biased toward elements in short chains; Redis accepts the same bias.

## 4. The keyspace entry

```java
final class KeyEntry extends DictEntry {
    byte type;          // STRING, HASH, LIST, SET, ZSET, STREAM (2.1)
    boolean alive;      // false once deleted
    Object value;       // cleared on delete, so nothing keeps a deleted value reachable
    long expireAt = -1; // absolute epoch ms; -1 = no TTL
    int expireBucket;   // active-expiry registration: its bucket …
    int expireSlot;     // … and index, so deleting the key clears it in O(1) (06 §3)
    int snapEpoch;      // snapshot bookkeeping, see 08 §5
}
```

56 bytes per entry with compressed references
([§8](#8-memory-accounting)). Small strings live directly as the `value`
`byte[]` — no wrapper object.

## 5. Value types

### 5.1 String

A `byte[]`. `INCR`, `DECR`, `INCRBY` parse it with the strict integer parser
([03 §7](03-protocol.md#7-number-formatting)), compute with overflow checks
(`Math.addExact`), and store the decimal result as a new `byte[]`. Maximum
length is `proto-max-bulk-len`, also enforced for `APPEND` and `SETRANGE`.

### 5.2 Hash

`HashValue` wraps a `Dict<FieldEntry>`; `FieldEntry` adds `byte[] value`.
Field operations are O(1).

### 5.3 List — `ByteArrayRing`

Redis uses a linked list of compact blocks; we use a **growable ring buffer**:

```java
final class ByteArrayRing {
    byte[][] buf;       // power-of-two capacity
    int head, size;
    byte[] get(int i) { return buf[(head + i) & (buf.length - 1)]; }
}
```

| Operation | Cost |
|---|---|
| push / pop at either end | O(1) amortised |
| `LINDEX`, `LSET` | **O(1)** (Redis: O(n)) |
| `LRANGE` | O(count) |
| `LINSERT`, `LREM`, remove from middle | O(n), shifting the shorter side |

Capacity doubles when full and halves when `size < capacity / 4` (above 16), so
a queue that drained after a spike gives its memory back.

### 5.4 Set

`SetValue` wraps a `Dict<MemberEntry>` whose entries carry no value.
`SINTER`, `SUNION`, `SDIFF` iterate the smallest set and probe the others.

### 5.5 Sorted set

Two structures kept in step, as in Redis:

- `Dict<ZEntry>`: member → `SkipList.Node` — `ZSCORE` and membership in O(1);
- `SkipList` ordered by (score, member) — everything positional in O(log n).

`ZEntry` points at the node, so `ZREM` finds the node through the `Dict` and
deletes it from the skip list without a second search.

### 5.6 Stream (2.1)

`StreamValue` holds its entries in chunks of up to 256, each a view over
parallel arrays of IDs and fields that no holder ever sees change, and keeps
the last ID it gave after that entry is gone
([15 §4](15-streams.md#4-the-value-immutable-chunks), D-33). An empty stream is
not deleted.

| Operation | Cost |
|---|---|
| `XADD` | O(1), amortised over a chunk's growth |
| `XRANGE`, `XREVRANGE` | O(log n) to the start, then O(count) |
| `XDEL` | O(log n), and a copy of the one chunk |
| Trim | O(entries removed) |
| `COPY` | O(chunks): the chunks are shared, but the last |

## 6. Skip list with spans

```java
final class SkipList {
    static final int MAX_LEVEL = 32;
    static final double P = 0.25;

    static final class Node {
        final byte[] member;
        double score;
        Node backward;          // level-0 predecessor, for reverse iteration
        final Node[] forward;   // forward[i] = next node at level i
        final int[] span;       // span[i]   = how many level-0 steps forward[i] skips
    }

    final Node header = new Node(null, 0, MAX_LEVEL);
    Node tail;
    int length, level = 1;
}
```

### Ordering

By `score` ascending; ties by `member` compared as **unsigned bytes**
lexicographically. `NaN` is never stored: commands reject it, and `ZINCRBY`
that would produce `NaN` (∞ + −∞) fails with `-ERR resulting score is not a number (NaN)`.

### Levels

A new node gets level 1, and each further level with probability ¼, capped at
32. The random source is a per-server xorshift generator. The average node has
1.33 levels, so the structure costs little more than a linked list.

### Rank via spans

Each forward pointer records how many nodes it jumps. Searching for a node
from the top level down, summing the spans of every pointer followed gives its
**1-based rank** — O(log n), no counting.

### Operations

| Operation | Cost | Used by |
|---|---|---|
| `insert(score, member)` | O(log n) | `ZADD` new member |
| `delete(score, member)` | O(log n) | `ZREM`, pops |
| `updateScore(node, newScore)` | O(1) if order unchanged, else O(log n) | `ZADD` existing, `ZINCRBY` |
| `rank(score, member)` | O(log n) | `ZRANK`, `J.ZAROUND` |
| `byRank(r)` | O(log n) | `ZRANGE` by index, `J.ZAROUND` |
| `firstInRange / lastInRange` | O(log n) | `ZRANGE BYSCORE`, `ZCOUNT` |
| `deleteRangeByScore / ByRank` | O(log n + removed) | `ZREMRANGEBY*` |

`updateScore` keeps the node in place when the new score still lies between its
backward and forward neighbours — the common case for leaderboards, where
scores grow slowly.

Insert and delete need `update[]` and `rank[]` scratch arrays of length 32.
They are fields reused across calls, which is safe because only `cmd` uses them.

## 7. Arguments and scratch buffers

- Arguments arrive as `byte[]` ([03 §4](03-protocol.md#4-the-decoder)); keys are
  stored as the same arrays with no copy.
- Handlers reuse per-thread scratch objects (upper-case buffer, number parser,
  skip-list update arrays) instead of allocating per command.
- Only values that must outlive the command are allocated.

## 8. Memory accounting

`maxmemory` needs a running total that is cheap to maintain, so the server keeps
an **estimate**, adjusted on every mutation by each value type, rather than
measuring the heap.

Constants below assume a 64-bit JVM with **compressed references** (heap under
about 32 GB): 12-byte object header, 4-byte references, 8-byte alignment. The
estimator detects compressed references at start-up and switches constants if
the heap is larger.

| Object | Size |
|---|---|
| `byte[]` of length n | 16 + n, rounded up to 8 |
| `KeyEntry` | 48 |
| `FieldEntry` / `ZEntry` | 32 |
| `MemberEntry` | 24 |
| Bucket slot | 4 per bucket (1–2 buckets per entry) |
| Skip-list node, 1.33 levels on average | ~88 |
| Stream chunk: object, list slot, two `long[]` and a reference array | ~80, and 20 per slot of capacity (two longs and a reference) |
| Stream entry: its fields' array and each field | 16 + 4 per field, and each `byte[]` |
| Stream pending entry: its record, its ID, and a node in the group's list and its consumer's | ~144 |
| Stream group or consumer: its object, its name twice, its map node and pending map | ~140, and the name's length twice |

**Example:** a 20-byte key holding a 20-byte string costs about
48 + 40 + 40 + 6 ≈ **134 bytes**. Redis uses roughly 70–90 bytes for the same,
so expect **1.5–2× Redis** for small keys (NFR-3). One million leaderboard
members with 16-byte ids cost about **160 MB**.

`INFO memory` reports the estimate (`used_memory`) and the real JVM figures
(`jvm_heap_used`, `jvm_heap_committed`, `jvm_heap_max`) from `MemoryMXBean`.
Milestone M7 checks that the estimate stays within ±20 % of retained heap.

## 9. Deferred: compact encodings (M8)

Redis stores small hashes and sorted sets as flat byte arrays. For Java the
gain is large: a 3-field hash costs about 300 bytes as a `Dict` and could cost
about 80 as a flat `byte[][]` scanned linearly.

Plan, only if M7 measurements show memory pressure:

- small hash / small zset as a flat array, linear scan, capped at 128 entries
  and 64-byte elements (Redis's defaults);
- one-way conversion to the general structure when a limit is exceeded;
- the value-type interface stays the same, so commands do not change.
