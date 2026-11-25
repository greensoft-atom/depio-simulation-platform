# 08 — Persistence

This page explains how j-redis keeps its data on disk and gets it back after a
restart or a crash. It is for engineers who change the persistence code, and
for operators who want to know what a crash can lose and how to back up. It
covers the files and the manifest, what is logged, the AOF (append-only file)
writer, the fork-free snapshot and why it is correct, large keys, crashes,
start-up and recovery, the base file format, when a rewrite starts, platform
details, and backup and restore.

In this page, `cmd` is the command thread (`jredis-cmd`), `aof-writer` is the
AOF writer thread (`jredis-aof-writer`) and `snapshot-writer` is the thread that
writes the base file (`jredis-snapshot-writer`)
([02 §2](02-architecture.md#2-threads)).

## 1. Modes

The `appendonly` directive turns persistence on or off:

| `appendonly` | Behaviour |
|---|---|
| `yes` (default) | Everything this page describes |
| `no` | Purely in memory: nothing is written. `SAVE` and `BGREWRITEAOF` reply `-ERR persistence is disabled (appendonly no)` |

There is one persistence mechanism, not two: a **multi-part append-only file**.
A "snapshot" is simply the base part that a rewrite produces.

## 2. Files and the manifest

The data directory (`dir`) holds these files:

```
<dir>/
  manifest              which files make up the dataset, in order
  base.7.jrdb           point-in-time image of the data at the moment generation 7 began
  incr.7.aof            every effect executed after that moment (RESP-encoded)
  LOCK                  held with FileChannel.tryLock while the server runs
  replication           the replication epoch (2.2), replaced atomically as the manifest is
```

The `replication` file holds the epoch that guards promotions
([16 §9](16-replication.md#9-promotion-and-epochs)). The rest of this page
is about the other four.

`manifest` is a small text file. The server replaces it atomically:

```
format 1
generation 7
base base.7.jrdb
incr incr.7.aof
```

Every line is required except `base`: the server refuses a manifest without a
generation and at least one incr file. `generation` is the number of the
newest file listed.

The manifest may list **no base** (in the first generation) and **several incr
files** (while a rewrite is running, or after one was aborted). Loading applies
the base, then each incr file in order.

**The server never reads a file that the manifest does not list.** After a
successful load, it deletes leftover files, such as a half-written `.tmp` file
or an old generation left by a crash.

## 3. Logging effects, not commands

The server replays the incr file after a restart, possibly hours later. So
every record must mean the same thing then as when the command ran. For this
reason `propagate()` ([02 §7](02-architecture.md#7-the-db-facade-and-mutation-hooks))
logs the **effect** of a command, in a deterministic form:

| Executed | Logged |
|---|---|
| `EXPIRE k 60` | `PEXPIREAT k 1767225600000` (absolute time) |
| `SET k v EX 60` | `SET k v PXAT 1767225600000` |
| `INCRBYFLOAT k 0.1` | `SET k 3.3 KEEPTTL` (the result, not the arithmetic) |
| `SPOP k` | `SREM k <the member actually popped>` |
| `BLPOP q 5` (served) | `LPOP q` |
| A key expired (lazily or actively) | `DEL k` |
| `J.CAS k old new` (succeeded) | `SET k new` |
| `XADD s * f v` (2.1) | `XADD s 1767225600000-0 f v`: the ID the clock gave |
| `XREADGROUP GROUP g c STREAMS s >` (2.1) | For each entry, `XCLAIM s g c 0 <id> TIME <ms> RETRYCOUNT 1 FORCE JUSTID`; then `XGROUP SETID s g <last>` ([15 §3](15-streams.md#3-commands), D-34) |
| `DEL missing-key` | Nothing: no change, so no record |
| `MULTI` … `EXEC` with changes | `MULTI`, the effects, `EXEC` |

The full mapping for each command is the *AOF effect* column of
[05](05-commands.md). Records use the RESP (REdis Serialization Protocol)
request encoding ([03](03-protocol.md)), so the loader reuses the protocol
decoder. The same effect bytes also go to the replicas
([16 §4](16-replication.md#4-the-stream-of-changes)).

## 4. The AOF writer

```
cmd ──(byte[] chunks + control markers, MPSC queue)──►  aof-writer ──► incr.N.aof
```

The queue is a JCTools MPSC (multi-producer, single-consumer) queue.

- **Hand-over.** At the end of each batch, `cmd` hands the batch's effect bytes
  to the queue as one chunk ([02 §4](02-architecture.md#4-the-command-thread-loop)).
- **Writing.** `aof-writer` writes the chunks in order with
  `FileChannel.write`, and loops until each chunk is fully written.
- **Forcing to disk (fsync).** The `appendfsync` directive chooses when:
  - **`appendfsync everysec`** (the default): the writer calls
    `FileChannel.force(false)` at most once per `appendfsync-interval-millis`
    (1000 ms by default), and only when something was written since the last
    call.
  - **`appendfsync no`**: the writer never forces; the operating system
    flushes when it chooses.
  - `always` is deferred to M8 (D-16).
- **Backlog limit.** `cmd` tracks the bytes queued but not yet written. When
  more than 64 MB is waiting, for example because the disk stalls, `cmd` logs a
  WARN and stops running commands until the backlog is under 32 MB. The
  alternatives are worse: unbounded memory, or acknowledging writes that can
  never be persisted.
- **Control markers** (`ROTATE`, `COMMIT`) travel on the same queue. They keep
  each file switch in exact order with the data
  ([§5.3](#53-the-rewrite-step-by-step)).

### Errors

| Failure | Response |
|---|---|
| `write` fails (for example, the disk is full) | The writer truncates the file back to the last fully written size, keeps the unwritten chunks, and retries every second. Meanwhile it reports itself *unhealthy*, and dispatch refuses write commands (and `PING`) with `-MISCONF` ([02 §6](02-architecture.md#6-command-dispatch)). Reads continue. |
| `fsync` fails | **Fail-stop.** On Linux, a failed fsync can drop the dirty pages and clear the error, so a retry may falsely "succeed" while data is gone. The server exits with code 4; systemd restarts it, and it recovers from what is durable. |

## 5. The fork-free snapshot

A **snapshot** here is a base file that holds the whole dataset as it was at
one instant. j-redis writes it without stopping clients and without `fork()`.
Release 2.2 uses the same snapshot for a full sync to a replica, with the
records sent to the replica's connection instead of a file
([16 §5.2](16-replication.md#52-the-primarys-side-a-snapshot-sent-to-the-link)).

### 5.1 The problem

Redis takes a snapshot by calling `fork()`. The child process sees memory
frozen at one instant (copy-on-write) and writes it out while the parent keeps
serving clients. The JVM (Java Virtual Machine) cannot fork. Pausing all
clients to write the dataset would take seconds. So j-redis needs a
**point-in-time image that is written while commands keep running**.

### 5.2 The idea: per-key copy-on-write

Let **T** be the instant at which the snapshot starts, between two commands.
For each key that existed at T, the server must write the key's value **as of
T**, exactly once. Before the background scan reaches such a key, one of two
things happens to it:

- **Nothing.** Then its current value *is* its value at T. The server writes it
  when the scan reaches it.
- **A command is about to change or delete it.** Then the server writes its
  current value, which is still its value at T, **just before** the change.
  This copy is the *pre-image*.

Keys created after T are never written to the base file: their whole history is
in the new incr file.

A number on each entry, the epoch (`KeyEntry.snapEpoch`), records "already
handled":

```java
// the snapshot for generation N starts at time T:
epoch = N;  active = true;              // every existing entry has snapEpoch < N

// Db.add — a key created at any time:
e.snapEpoch = epoch;                    // created after T (if active): never written

// Db.lookupWrite / prepareWrite / setValue / delete — before any change:
if (active && e.snapEpoch < epoch) {    // not yet written, value still as of T
    writeRecord(e);                     // copy its bytes into the base file
    e.snapEpoch = epoch;
}

// background slice — walk the keyspace:
cursor = keyspace.scanStep(cursor, e -> {
    if (e.snapEpoch < epoch) { writeRecord(e); e.snapEpoch = epoch; }
});                                     // cursor back to 0 → every key visited
```

`writeRecord` copies the value's bytes into a chunk at once. The snapshot
writer never holds a reference to a live value.

### 5.3 The rewrite, step by step

A **rewrite** replaces the current base and incr files with a new base file. At
the start, generation `g` is current, and the manifest lists `[base.g, incr.g]`.

| # | Thread | Action |
|---|---|---|
| 1 | `cmd` | Between two commands, it puts `ROTATE(g+1)` on the AOF queue, then sets `epoch = g+1; active = true`. This instant is **T**. |
| 2 | `aof-writer` | When it reaches `ROTATE`, it fsyncs `incr.g`, creates and fsyncs an empty `incr.(g+1)`, atomically replaces the manifest with `[base.g, incr.g, incr.(g+1)]`, and switches its appends to `incr.(g+1)`. Effects queued after the marker land in the new file. |
| 3 | `cmd` | Background slices scan the keyspace, and pre-images are written as commands touch keys. The records go in chunks to `snapshot-writer`, which writes `base.(g+1).jrdb.tmp`. |
| 4 | `cmd` | When the scan cursor returns to 0, it writes the end-of-file record and the CRC (cyclic redundancy check). |
| 5 | `snapshot-writer` | It fsyncs and closes the temporary file, and reports that it is done. |
| 6 | `cmd` | It puts `COMMIT(g+1)` on the AOF queue. |
| 7 | `aof-writer` | It renames the temporary file to `base.(g+1).jrdb`, atomically replaces the manifest with `[base.(g+1), incr.(g+1)]`, and closes and deletes `base.g`, `incr.g` and any older incr files. |

The manifest has a single writer, `aof-writer`, so two updates of it can never
race.

If step 2 fails (for example, because the disk is full), the writer keeps
appending to `incr.g`, deletes the partial new file and reports the failure.
`cmd` then aborts the snapshot. The manifest was never changed, so nothing is
inconsistent.

### 5.4 Why it is correct

Let S be the set of keys that existed at T, and V(k) the value of key k at T.

1. **Every key in S is written.** If k is never changed or deleted after T, it
   is present for the whole scan, and `SCAN` guarantees that the scan visits it
   ([04 §3.5](04-data-structures.md#35-scan-the-reverse-binary-cursor)). If k
   is changed or deleted, every such path goes through `lookupWrite`,
   `prepareWrite`, `setValue` or `delete` (rule 2 in
   [02 §7](02-architecture.md#three-rules-for-every-handler)), and that path
   writes k first.
2. **Each key is written at most once.** The epoch is set at the first write,
   so the scan skips duplicates that `SCAN` returns, and later changes skip the
   key too.
3. **Each key is written with V(k).** Before the first change after T, the
   value is still V(k), and the pre-image is taken before that change.
4. **No other key is written.** A key created after T carries the new epoch
   from its creation.

So the base file is exactly the dataset at T. `incr.(g+1)` holds exactly the
effects executed after T, in order, because step 2 puts the file switch at T in
queue order. The effects are deterministic (§3). Therefore loading the base and
then the incr file reproduces the state at any later point that was written.
This is requirement NFR-5
([01](01-requirements-and-scope.md#5-non-functional-requirements)).

Commands that touch several keys (`RENAME`, `LMOVE`, `SMOVE`, `SINTERSTORE`,
`EXEC`…) need no special handling. Each key they touch gets its own pre-image
through the same hooks, before the command changes it.

### 5.5 `FLUSHALL` during a rewrite

Taking a pre-image of every key would mean writing the whole snapshot
synchronously. Instead, `FLUSHALL` **aborts** the running rewrite: the server
deletes the temporary file and leaves the manifest at
`[base.g, incr.g, incr.(g+1)]`. That list still describes the data correctly,
because the `FLUSHALL` itself is in `incr.(g+1)`. The next rewrite, now tiny,
collapses the list. `FLUSHALL` also aborts a running full sync to a replica;
the replica then asks again and gets a small image.

### 5.6 Why not the scheme in doc 04

The earlier design ([../../docs/archive/memstore.md §6](../../docs/archive/memstore.md))
wrote keys in their **current** state and tried to repair the mix of times at
load. It used `SNAPSHOT-CURSOR` markers on each key to decide which log entries
to replay. That cannot work for commands that touch two keys. Take
`RPOPLPUSH src dst`, run after `src` was written but before `dst` was:

- `src` in the file is from *before* the command, but `dst` is from *after* it;
- replaying the command inserts the element into `dst` twice, and skipping it
  leaves `src` holding an element that was popped.

Neither choice is right. The pre-image approach removes the problem: the base
file is a true image at one instant, and the server simply replays the log
after it.

## 6. The big-key caveat

The server always writes a single key in one piece, by the scan or as a
pre-image, because it must capture the key at one instant. For ordinary keys
that takes microseconds. For a very large collection it takes much longer, once
per rewrite. The command thread stalls for that time, so every client's reply
waits.

These times were measured on 2026-09-21 on the shared development VM, which was
heavily loaded. A dedicated server should do better. The design estimate was
50–100 ms per million members.

| Key | Time to write it as one record |
|---|---|
| Sorted set, 1,000,000 members | 120–250 ms |
| Sorted set, 100,000 members | 20–30 ms (the first write to it during a rewrite, which pays for the pre-image) |

Most of the cost is walking a million skip-list nodes scattered in memory. The
encoder itself only appends to a byte array. `INFO persistence` reports the
time as `aof_rewrite_longest_record_ms`.

Guidelines:

- Keep collections below about **100,000 elements** when a stall of a few tens
  of milliseconds per rewrite matters. Split rankings by period and category,
  for example weekly and daily
  ([09](09-integration-patterns.md#4-key-naming-and-size-guidelines)).
  Several leaderboards of more than 100,000 members work correctly. Each one
  adds its own short stall once per rewrite; the stalls do not add up into one
  long stall.
- `INFO persistence` reports the longest single-record write of the last
  rewrite, so a growing key becomes visible before it hurts.
- If a large key becomes unavoidable, chunked copy-on-write for big
  collections is an M8 option.

**Streams are the exception (2.1).** The server holds a stream in chunks that
never change once written, so the command thread copies only the stream's list
of chunks, and the writer thread encodes the entries
([15 §4](15-streams.md#4-the-value-immutable-chunks), D-33). It was measured the
same way on 2026-09-29, in one run on the loaded VM: a stream of 300,000
entries of 400 bytes held the command thread for 0 ms, and a sorted set of
300,000 members of the same size held it for 232 ms. A test checks that a
stream of 200,000 entries holds the command thread for less than 40 ms.

## 7. Crash analysis

The server only ever replaces the manifest atomically: it writes a temporary
file, fsyncs it, renames it, and fsyncs the directory. So the manifest on disk
is always one of its complete versions.

| Crash during | Manifest on disk | Loaded | Result |
|---|---|---|---|
| Normal operation | `[base.g, incr.g]` | Both | The state up to the last durable write |
| Step 2, before the manifest update | `[base.g, incr.g]` | Both | Correct: nothing was appended to `incr.(g+1)` yet, and the empty file is cleaned up |
| Steps 2–6 | `[base.g, incr.g, incr.(g+1)]` | `base.g` and both incr files | Correct: the partial `.tmp` file is ignored and deleted |
| Step 7, after the manifest update | `[base.(g+1), incr.(g+1)]` | Both | Correct: the old files are no longer listed, and are deleted |
| The middle of an append to an incr file | Any | — | The cut final record is discarded ([§8](#8-start-up-and-recovery)) |

**What a crash can lose.** With `everysec`, a power failure or a `kill -9` can
lose the writes acknowledged in the last 1–2 s or so (NFR-4). A clean shutdown
fsyncs first and loses nothing
([02 §13](02-architecture.md#13-start-up-and-shutdown)).

## 8. Start-up and recovery

At start-up, with `appendonly yes`, the server takes these steps:

1. It takes `LOCK`. If another process holds it, the server exits with code 2
   and logs `data directory <dir> is in use by another process`.
2. It reads `manifest`.
   - If there is no manifest and the directory is empty, it starts fresh:
     generation 1, no base.
   - If there is no manifest but data files are present, it refuses to start
     (exit code 3). It does not guess.
   - If the manifest lists a file that is missing, it refuses to start (exit
     code 3).
3. **It loads the base**, if one is listed. It streams the file and checks the
   magic bytes, the version and the CRC32 at the end. It loads every key with
   its TTL, including keys whose TTL has already passed
   ([06 §4](06-expiry-and-memory.md#4-expiry-and-the-other-subsystems)): they
   are invisible at once, and active expiry removes them after start-up. A bad
   CRC or format makes it refuse to start (exit code 3).
4. **It replays each incr file in order** through the command handlers, in
   *loading mode*: no replies, no propagation, no lazy expiry, no pub/sub, no
   blocking. It buffers a `MULTI` … `EXEC` in the file and applies it only at
   `EXEC`.
5. **Cut-off end of the file.** The **last** incr file may end in the middle
   of a record, or inside an unfinished `MULTI`. Then, with
   `aof-load-truncated yes` (the default), the server logs a WARN with the
   offset and the number of bytes dropped, truncates the file there, and
   continues. Anything else is corruption: a bad record in the middle of a
   file, or in an earlier file. Then the server refuses to start (exit code 3)
   and points the operator at `j-redis-tools check-aof`.
6. It deletes the files that the manifest does not list.
7. It starts `aof-writer`, appending to the last incr file.
8. It reads the replication epoch from `<dir>/replication` (2.2,
   [16 §9](16-replication.md#9-promotion-and-epochs)), starts the command
   thread and opens the port. If `replicaof` is configured, the server then
   becomes a replica of that primary
   ([02 §13](02-architecture.md#13-start-up-and-shutdown)).

**The tools.**

| Command | What it does |
|---|---|
| `j-redis-tools check-aof <data-dir>` | Checks the manifest, the base file's CRC and every incr file, and reports the offset of the first bad record. Run it with the server stopped: it takes the `LOCK`, so it refuses while a server holds the directory. |
| `j-redis-tools check-aof --fix <data-dir>` | Cuts only a torn end of the **last** incr file: the same thing start-up does with `aof-load-truncated yes`. It reports corruption anywhere else with its offset and never cuts it. Truncating there loses data, so that stays an operator's decision. |
| `j-redis-tools dump <data-dir>` | Prints the keys in the base file; `--values` prints their contents too |

## 9. Base file format

All integers are big-endian. Lengths and counts are unsigned LEB128 varints
(variable-length integers).

```
header    "JRDB"  u16 version=2  u16 flags=0  i64 createdAtMillis (= T)
records   repeated:
            u8  opcode       0x01 STRING  0x02 HASH  0x03 LIST  0x04 SET  0x05 ZSET
                             0x06 STREAM (version 2)
            u8  flags        bit 0 = has TTL
            i64 expireAt     only if bit 0
            key              varint length, bytes
            body
              STRING  varint length, bytes
              HASH    varint n, n × (field, value)
              LIST    varint n, n × element, head to tail
              SET     varint n, n × member
              ZSET    varint n, n × (member, f64 score), ascending score order
              STREAM  u64 lastMs, u64 lastSeq (the last ID given, which may be past
                      the last entry), varint n, n × (u64 ms, u64 seq, varint f,
                      f × field), varint g, g × group
                      group: name, u64 lastMs, u64 lastSeq,
                             varint c, c × (consumer name, i64 seenTime, i64 activeTime),
                             varint p, p × (u64 ms, u64 seq, varint consumer, i64 time, varint count)
trailer   u8 0xFF   u32 CRC32 of every preceding byte (the 0xFF included)
```

**Version 2** (2.1) adds the stream record and nothing else. A version 1 file
loads as it always did, and a 2.0 server refuses a version 2 file because of
its version. The writer thread encodes a stream record from a frozen view, in
its place in the file (D-33).

The server writes sorted sets in ascending order, so that the loader can build
the skip list by appending at the tail: O(1) per member instead of O(log n).

## 10. Rewrite triggers

| Trigger | Condition |
|---|---|
| Automatic | The incr files together reach `auto-aof-rewrite-min-size` (64 MB by default) **and** `auto-aof-rewrite-percentage` (100 % by default) of the base size. With no base yet, the size alone decides. The server checks once a second; `auto-aof-rewrite-percentage 0` turns automatic rewrites off. After a failed rewrite, automatic ones wait 1 s, doubling up to 1 hour, so a full disk does not start a new rewrite every second. |
| `BGREWRITEAOF`, `BGSAVE` | At once. If a rewrite is already running, they reply `-ERR Background append only file rewriting already in progress`. `BGSAVE SCHEDULE` instead replies `+Background saving scheduled` and runs another rewrite when the current one ends. |
| `SAVE` | Synchronously: the slices run back to back, with no commands in between |
| `SHUTDOWN SAVE` | Synchronously, before the server exits |

**Speed.** A small key costs roughly 0.5–1 µs, and background work may use at
most 25 % of the command thread (`background-max-duty`,
[02 §5](02-architecture.md#5-background-work)). So a rewrite covers
250,000–500,000 keys per second: the expected data set in well under a second,
and 10 million keys in 20–40 s. Pre-images are always written at once; only
the *scan* is paced.

## 11. Platform notes

| Topic | Detail |
|---|---|
| Checksum | `java.util.zip.CRC32`, a hardware intrinsic on x86. `CRC32C` only arrived in Java 9. |
| Data fsync | `FileChannel.force(false)` maps to `fdatasync` on Linux, which also persists the file size needed to read appended data back |
| Directory fsync | `FileChannel.open(dir, READ).force(true)` works on Linux. On **Windows**, opening a directory throws, so the server skips this step there. That is a weaker guarantee, acceptable for development |
| Atomic replace | `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`, on both platforms |
| Deleting files | Close the file first, because Windows cannot delete an open file. Retry a few times, because antivirus scanners briefly lock new files. |
| Single instance | `FileChannel.tryLock()` on `LOCK`, so two servers can never share a data directory |

## 12. Backup and restore

**Backup**, while the server runs:

1. Run `CONFIG SET auto-aof-rewrite-percentage 0`, so that no automatic rewrite
   starts during the copy.
2. Optionally, run `BGREWRITEAOF` and wait until `INFO persistence` shows
   `aof_rewrite_in_progress:0`. This makes the backup smaller.
3. Copy `manifest` **first**, then every file it lists.
4. Set `auto-aof-rewrite-percentage` back to its previous value.

This copy is consistent. The server deletes a file listed in the manifest only
when a rewrite commits, and step 1 prevents a commit during the copy. An incr
file copied in the middle of an append only has a cut-off end, which loading
tolerates (§8). Without step 1, a rewrite that committed during the copy could
delete a file that the copied manifest still lists.

**Restore:**

1. Stop the server.
2. Replace the data directory with the backup.
3. Start the server.
