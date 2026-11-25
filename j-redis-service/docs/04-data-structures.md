# 04 — Data structures

This page describes how the server holds its data in memory: the keyspace hash
table and its `SCAN` cursor, the entry for each key, each value type, the skip
list behind sorted sets, and how the server estimates the memory it uses. It is
for engineers who change the server's data code or want to know the cost of a
command. The classes are in `j-redis-server/src/main/java/com/jredis/server/db/`.

## 1. Overview

```
Db
 └── keyspace: Dict<KeyEntry>
       KeyEntry ── key: byte[]           value by type:
                   type: byte              STRING  → byte[]
                   value: Object           HASH    → HashValue   = Dict<FieldEntry>
                   expireAt: long (-1)     LIST    → ListValue   = growable ring buffer
                   expireBucket: int       SET     → SetValue    = Dict<MemberEntry>
                   expireSlot: int         ZSET    → ZSetValue   = Dict<ZEntry> + SkipList
                   alive: boolean          STREAM  → StreamValue = immutable chunks (2.1)
                   snapEpoch: int
```

The command thread owns everything here. None of it is thread-safe, by design
([02 §2](02-architecture.md#2-threads)).

## 2. Why not `java.util.HashMap`

The server has its own hash table, `Dict`, which follows Redis's design. There
are three reasons, and the first one decides:

1. **Resizing stalls.** `HashMap` doubles its table in one go. At 10 million
   entries, that means allocating a new array and relinking every node in a
   single call. That takes hundreds of milliseconds, and no command runs
   meanwhile. It breaks the p99 ≤ 1 ms target
   ([01](01-requirements-and-scope.md#5-non-functional-requirements)) for every
   client at once.
2. **No stable cursor.** `SCAN` must let a client walk the keyspace across many
   calls while keys are added and removed. `HashMap` exposes no bucket index,
   and a resize reorders everything.
3. **Per-entry overhead.** A `HashMap.Node` plus a separate key wrapper object
   costs more than an entry that holds the key bytes itself.

## 3. `Dict`

`Dict` is a chained hash table that resizes a little at a time and has a cursor
that survives resizing.

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

Table sizes are powers of two, from 4 buckets up to 2³⁰. Entries whose hashes
collide are chained through `next`.

### 3.2 Hashing

`Dict` uses **SipHash-1-2** over the key bytes, with a random 128-bit key that
`SecureRandom` makes at start-up. Redis makes the same choice. Keys include text
that users choose (names, team names). Simpler seeded hashes, such as
MurmurHash3, have known collision attacks that work whatever the seed. An
attacker could use them to turn every lookup into a scan of a long chain. The
random key per process also means that the order of the buckets differs between
runs; nothing may depend on that order.

### 3.3 Growing and shrinking

| Condition | Action | When it is checked |
|---|---|---|
| `used ≥ size` (load factor 1) | Start a rehash into a table of the next power of two ≥ `2 × used` | On insert |
| `used < 10 % of size` and `size > 4` | Start a rehash into the next power of two ≥ `used` (at least 4) | After every delete, and when a rehash finishes; for the keyspace and for every collection alike |

### 3.4 Incremental rehash

While a rehash runs, both tables are live:

- **lookup and delete** search `t0`, then `t1`;
- **insert** goes to `t1` only;
- every lookup, insert, delete and random pick also performs **one rehash
  step**: it moves one non-empty bucket from `t0` to `t1`, visiting at most 10
  empty buckets;
- no rehash step runs while a scan or a full iteration is inside the table;
- the command thread's background work runs rehash steps on the keyspace `Dict`
  until its slice ends ([02 §5](02-architecture.md#5-background-work));
- when `t0` is empty, `t1` becomes `t0`.

The cost of each operation stays O(1). The resize work is spread over many
operations and over idle time. The only large allocation is the new bucket
array: 64 MB for 16M buckets at 4 bytes per reference (8 bytes per reference,
so 128 MB, under ZGC; [§8](#8-memory-accounting)). The JVM allocates it without
touching the existing entries.

### 3.5 `SCAN`: the reverse-binary cursor

The cursor is a bucket index whose bits are incremented **from the most
significant end**. When a table of size 2ⁿ doubles, it splits bucket `i` into
buckets `i` and `i + 2ⁿ`. So walking the indices in reversed-bit order visits
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

The real method is `Dict.scan(cursor, visitor)`.

**Guarantees** (the same as Redis's):

- every element present for the **entire** scan is returned **at least once**;
- an element may be returned **more than once**, so callers must tolerate
  duplicates;
- an element added or removed during the scan may or may not be returned.

**`SCAN cursor [MATCH pattern] [COUNT n] [TYPE t]`** works in two parts:

1. It runs scan steps until at least `n` candidates are collected (default 10,
   at most 1,000,000), or the cursor returns to 0, or `10 × n` steps have run.
2. Then it filters the candidates. It deletes and drops expired keys, then
   applies `TYPE`, then `MATCH`.

So a page can be empty while the cursor is not 0. The client simply continues.
`HSCAN`, `SSCAN` and `ZSCAN` collect candidates the same way inside one collection, and take `MATCH` and `COUNT` but not `TYPE`.

Java has no unsigned `long`. The server sends cursors with
`Long.toUnsignedString` and parses them with `Long.parseUnsignedLong`.

The snapshot walks the keyspace with the same scan step while commands keep
running ([08 §5](08-persistence.md#5-the-fork-free-snapshot)).

### 3.6 Random element

`SPOP`, `SRANDMEMBER`, `HRANDFIELD` and `RANDOMKEY` pick random bucket indices
until one is not empty, then a random element of its chain. This favours
elements in short chains a little. Redis accepts the same bias.

## 4. The keyspace entry

Each key is one `KeyEntry`:

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

A `KeyEntry` object, with the fields it inherits from `DictEntry`, takes 56
bytes with compressed references (G1) and 64 bytes under ZGC
([§8](#8-memory-accounting)). A string value is the `byte[]` itself, stored in
`value` with no wrapper object.

## 5. Value types

### 5.1 String

A string is a `byte[]`. `INCR`, `DECR` and `INCRBY` parse it with the strict
integer parser ([03 §7](03-protocol.md#7-number-formatting)), compute with
overflow checks (`Math.addExact`), and store the decimal result as a new
`byte[]`. The maximum length is `proto-max-bulk-len`, which `APPEND` and
`SETRANGE` also enforce (`-ERR string exceeds maximum allowed size
(proto-max-bulk-len)`). The server does not enforce it while it replays the AOF:
the write was accepted when it happened.

### 5.2 Hash

`HashValue` wraps a `Dict<FieldEntry>`. A `FieldEntry` adds a `byte[] value` to
the field name. Field operations are O(1).

### 5.3 List — `ByteArrayRing`

Redis keeps a list as a linked list of compact blocks. j-redis uses a
**growable ring buffer** instead. In the code, the class is `ListValue`
(`ByteArrayRing` was its name in the design):

```java
final class ListValue {
    byte[][] buf;       // power-of-two capacity, at least 4
    int head, size;
    byte[] get(int i) { return buf[(head + i) & (buf.length - 1)]; }
}
```

| Operation | Cost |
|---|---|
| Push or pop at either end | O(1) amortised |
| `LINDEX`, `LSET` | **O(1)** (Redis: O(n)) |
| `LRANGE` | O(count) |
| `LINSERT`, `LREM`, removing from the middle | O(n): the list is copied into a new array |

The capacity doubles when the buffer is full. It halves when `size < capacity /
4` and the capacity is above 16, so a queue that drained after a spike gives its
memory back.

### 5.4 Set

`SetValue` wraps a `Dict<MemberEntry>`, whose entries carry no value.

| Command | How it works |
|---|---|
| `SINTER` (and `SINTERSTORE`, `SINTERCARD`) | Iterates the smallest set and probes the others. A missing key gives an empty result at once. |
| `SUNION` (and `SUNIONSTORE`) | Adds every member of every set to the result |
| `SDIFF` (and `SDIFFSTORE`) | Iterates the first set and probes the others |

### 5.5 Sorted set

A sorted set keeps two structures in step, as Redis does:

- a `Dict<ZEntry>`: member → skip-list node, for `ZSCORE` and membership in O(1);
- a `SkipList` ordered by (score, member), for everything positional in
  O(log n).

A `ZEntry` points at the member's node, so the score is one lookup away. `ZREM`
removes the member from the `Dict`, then deletes it from the skip list by its
(score, member), in O(log n).

### 5.6 Stream (2.1)

`StreamValue` holds its entries in chunks of up to 256. Each chunk is a view over
parallel arrays of IDs and fields, and no holder ever sees a chunk change. The
stream also keeps the last ID it gave, even after that entry is gone
([15 §4](15-streams.md#4-the-value-immutable-chunks), D-33). An empty stream is
not deleted.

| Operation | Cost |
|---|---|
| `XADD` | O(1), amortised over a chunk's growth |
| `XRANGE`, `XREVRANGE` | O(log n) to the start, then O(count) |
| `XDEL` | O(log n), and a copy of the one chunk |
| Trim | O(entries removed) |
| `COPY` | O(chunks): the copy shares every chunk but the last |

## 6. Skip list with spans

`SkipList` is a direct port of Redis's `zskiplist`:

```java
final class SkipList {
    static final int MAX_LEVEL = 32;
    // p = 1/4: a random 16-bit number below 0xFFFF / 4 adds a level

    static final class Node {
        final byte[] member;
        double score;
        Node backward;          // level-0 predecessor, for reverse iteration
        final Node[] forward;   // forward[i] = next node at level i
        final int[] span;       // span[i]   = how many level-0 steps forward[i] skips
    }

    final Node header = new Node(MAX_LEVEL, 0, null);
    Node tail;
    int length, level = 1;
}
```

### Ordering

Nodes are ordered by `score`, ascending. Ties are ordered by `member`, compared
**byte by byte as unsigned values**. The skip list never stores `NaN`: commands
reject it, and a `ZINCRBY` that would produce `NaN` (∞ + −∞) fails with
`-ERR resulting score is not a number (NaN)`.

### Levels

A new node gets level 1, and each further level with probability ¼, up to 32.
The random numbers come from `ThreadLocalRandom`. The average node has 1.33
levels, so the structure costs little more than a linked list.

### Rank via spans

Each forward pointer records how many nodes it jumps over. A search goes from
the top level down. The sum of the spans of every pointer it follows is the
node's **1-based rank**. That costs O(log n), with no counting. Commands convert
the rank to 0-based.

### Operations

| Operation | Cost | Used by |
|---|---|---|
| `insert(score, member)` | O(log n) | `ZADD` of a new member |
| `delete(score, member)` | O(log n) | `ZREM`, pops |
| `updateScore(currentScore, member, newScore)` | O(log n) | `ZADD` of an existing member, `ZINCRBY` |
| `rank(score, member)` | O(log n) | `ZRANK`, `J.ZAROUND` |
| `byRank(r)` | O(log n) | `ZRANGE` by index, `J.ZAROUND` |
| `firstInRange`, `lastInRange` | O(log n) | `ZRANGE BYSCORE`, `ZCOUNT` |
| `deleteRangeByScore`, `deleteRangeByRank` | O(log n + removed) | `ZREMRANGEBY*` |

`updateScore` first finds the node, in O(log n). If the new score still lies
between the scores of the node's backward and forward neighbours, it changes the
score in place and keeps the node. Otherwise it removes the node and inserts a
new one. Keeping the node is the common case for leaderboards, where scores grow
slowly.

Insert, delete and `updateScore` need `update[]` and `rank[]` scratch arrays of
length 32. The skip list keeps one pair per thread (a `ThreadLocal`), because
several engines may run in one JVM, for example in embedded mode.

## 7. Arguments and scratch buffers

- Arguments arrive as `byte[]` ([03 §4](03-protocol.md#4-the-decoder)). The
  keyspace stores a key as the same array, with no copy.
- Code avoids allocating per command where it can. The command lookup folds
  case while it hashes and compares the name, so it needs no upper-case copy
  ([02 §6](02-architecture.md#the-command-table)). Integer parsing
  (`NumberCodec`) is static and allocates nothing. The skip list reuses its
  scratch arrays ([§6](#6-skip-list-with-spans)).
- Only values that must outlive the command are allocated.

## 8. Memory accounting

`maxmemory` needs a running total that is cheap to keep up to date. So the
server keeps an **estimate**, which each value type adjusts on every change,
instead of measuring the heap.

`MemoryEstimator` assumes a 64-bit HotSpot JVM, with 8-byte alignment. Two JVM
flags decide the sizes, and they are separate flags:

- **compressed class pointers** decide the object header: 12 bytes when on,
  16 when off;
- **compressed oops** (ordinary object pointers) decide a reference field:
  4 bytes when on, 8 when off.

G1 with a heap under about 32 GB has both on: a 12-byte header and 4-byte
references. ZGC, the collector j-redis runs on, turns compressed oops off but
keeps compressed class pointers: a 12-byte header and 8-byte references
([06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing), D-32). The estimator asks
HotSpot for both flags once at start-up, so no configuration is needed.

| Object | G1 (compressed oops) | ZGC |
|---|---|---|
| `byte[]` of length n | 16 + n, rounded up to 8 | the same |
| `KeyEntry` | 56 | 64 |
| `FieldEntry` / `ZEntry` | 32 | 40 |
| `MemberEntry` | 24 | 32 |
| Bucket slot | 4 per bucket; the estimate counts 2 per entry | 8 per bucket; 2 per entry |
| Skip-list node, 1.33 levels on average | ~88 | ~104–112 |
| Stream chunk: object, list slot, two `long[]` and a reference array | ~84, and 20 per slot of capacity (two longs and a reference) | ~104, and 24 per slot |
| Stream entry: its fields' array, and each field | 16 + 4 per field, and each `byte[]` | 16 + 8 per field, and each `byte[]` |
| Stream pending entry: its record, its ID, and a node in the group's list and in its consumer's | ~144 | ~184 |
| Stream consumer: its object, its name twice, its map node and its pending map | ~136, and the name's `byte[]` twice | ~184, and the name's `byte[]` twice |
| Stream group: the same parts as a consumer, plus its map of consumers | ~264, and the name's `byte[]` twice | ~408, and the name's `byte[]` twice |

**Examples:**

- A 20-byte key that holds a 20-byte string costs about 64 (the entry and its
  two bucket slots) + 40 (the key) + 40 (the value) = **144 bytes** under G1,
  and 80 + 40 + 40 = **160 bytes** under ZGC. Redis uses roughly 70–90 bytes for
  the same, so expect about **1.6–2.1 times Redis** for small keys under G1, and
  about 1.8–2.3 times under ZGC (NFR-3 asks for at most 2 times).
- One million leaderboard members with 16-byte ids cost about **160 MB** under
  G1, and about 190–200 MB under ZGC.

`INFO memory` reports the estimate (`used_memory`) and the real JVM figures from
`MemoryMXBean` (`jvm_heap_used`, `jvm_heap_committed`, `jvm_heap_max`). NFR-3
also asks that the estimate stays within ±20 % of the retained heap. That check
is one of the milestone M7 measurements on the production machine, which are
still open ([13](13-implementation-plan.md)).

## 9. Deferred: compact encodings (M8)

Redis stores small hashes and sorted sets as flat byte arrays. For Java the gain
would be large: a 3-field hash costs about 300 bytes as a `Dict`, and could cost
about 80 as a flat `byte[][]` scanned linearly. j-redis does not do this yet
([14](14-decision-log.md), D-11). Milestone M8 is optional and not started.

The plan, used only if the M7 measurements show memory pressure:

- keep a small hash or a small sorted set as a flat array, scanned linearly, up to
  128 entries and 64-byte elements (Redis's defaults);
- convert it, one way, to the general structure when it passes a limit;
- keep the value-type interface the same, so commands do not change.
