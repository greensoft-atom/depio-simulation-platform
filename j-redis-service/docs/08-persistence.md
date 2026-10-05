# 08 — Persistence

## 1. Modes

| `appendonly` | Behaviour |
|---|---|
| `yes` (default) | Everything described here |
| `no` | Pure in-memory; nothing is written; `SAVE`/`BGREWRITEAOF` reply `-ERR persistence is disabled` |

There is one persistence mechanism, not two: a **multi-part append-only file**.
A "snapshot" is simply the base part produced by a rewrite.

## 2. Files and the manifest

```
<dir>/
  manifest              which files make up the dataset, in order
  base.7.jrdb           point-in-time image of the data at the moment generation 7 began
  incr.7.aof            every effect executed after that moment (RESP-encoded)
  LOCK                  held with FileChannel.tryLock while the server runs
```

`manifest` is a small text file, replaced atomically:

```
format 1
generation 7
base base.7.jrdb
incr incr.7.aof
```

All lines are required except `base`. `generation` is the number of the newest
file listed.

It may list **no base** (the first generation) and **several incr files**
(while a rewrite is running, or after one was aborted). Loading applies the base,
then each incr file in order.

**Nothing outside the manifest is ever read.** Leftover files — a half-written
`.tmp`, an old generation after a crash — are deleted after a successful load.

## 3. Logging effects, not commands

The incremental file is replayed after a restart, possibly hours later, so every
record must mean the same thing then as when it was executed.
`propagate()` ([02 §7](02-architecture.md#7-the-db-facade-and-mutation-hooks))
therefore logs a command's **effect** in a deterministic form:

| Executed | Logged |
|---|---|
| `EXPIRE k 60` | `PEXPIREAT k 1767225600000` (absolute) |
| `SET k v EX 60` | `SET k v PXAT 1767225600000` |
| `INCRBYFLOAT k 0.1` | `SET k 3.3 KEEPTTL` (the result, not the arithmetic) |
| `SPOP k` | `SREM k <the member actually popped>` |
| `BLPOP q 5` (served) | `LPOP q` |
| Key expired (lazy or active) | `DEL k` |
| `J.CAS k old new` (succeeded) | `SET k new` |
| `XADD s * f v` (2.1) | `XADD s 1767225600000-0 f v`: the ID the clock gave |
| `XREADGROUP GROUP g c STREAMS s >` (2.1) | per entry, `XCLAIM s g c 0 <id> TIME <ms> RETRYCOUNT 1 FORCE JUSTID`; then `XGROUP SETID s g <last>` ([15 §3](15-streams.md#3-commands), D-34) |
| `DEL missing-key` | nothing — no change, no record |
| `MULTI` … `EXEC` with changes | `MULTI`, the effects, `EXEC` |

The full mapping per command is the *AOF effect* column of [05](05-commands.md).
Records use the RESP request encoding ([03](03-protocol.md)), so the loader
reuses the protocol decoder.

## 4. The AOF writer

```
cmd ──(byte[] chunks + control markers, SPSC queue)──►  aof-writer ──► incr.N.aof
```

- At the end of each batch, `cmd` hands the batch's effect bytes to the queue as
  one chunk ([02 §4](02-architecture.md#4-the-command-thread-loop)).
- `aof-writer` writes chunks in order with `FileChannel.write`, looping until
  each is fully written.
- **`appendfsync everysec`** (default): `FileChannel.force(false)` at most once
  per second, when something was written since the last one.
  **`appendfsync no`**: never force; the OS flushes.
  `always` is deferred to M8 (D-16).
- **Backlog limit:** `cmd` tracks bytes queued but not yet written. Above 64 MB,
  for example when the disk stalls, `cmd` waits until the writer catches up and
  logs a WARN. The alternative — unbounded memory, or acknowledging writes that
  can never be persisted — is worse.
- **Control markers** on the same queue (`ROTATE`, `COMMIT`) keep file switches
  in exact order with the data ([§5.3](#53-the-rewrite-step-by-step)).

### Errors

| Failure | Response |
|---|---|
| `write` fails (e.g. disk full) | Truncate the file back to the last fully written size, keep the unwritten chunks, retry every second. Meanwhile the writer reports *unhealthy* and dispatch rejects write commands with `-MISCONF` ([02 §6](02-architecture.md#6-command-dispatch)); reads continue. |
| `fsync` fails | **Fail-stop.** On Linux a failed fsync can drop dirty pages and clear the error, so a retry may falsely "succeed" while data is gone. Exit, let systemd restart, recover from what is durable. |

## 5. The fork-free snapshot

### 5.1 The problem

Redis snapshots by calling `fork()`: the child sees memory frozen at one
instant (copy-on-write) and writes it out while the parent keeps serving.
The JVM cannot fork. Pausing all clients to write the dataset would take
seconds. We need a **point-in-time image written while commands keep running**.

### 5.2 The idea: per-key copy-on-write

Let **T** be the instant the snapshot starts, between two commands. For each key
that existed at T we must write its value **as of T**, exactly once. Two things
can happen to such a key before it is reached:

- **nothing** → its current value *is* its value at T; write it when the scan
  reaches it;
- **a command is about to change or delete it** → write its current value (still
  its value at T) **just before** the change. This is the *pre-image*.

Keys created after T are never written: their whole history is in the new incr
file.

A per-entry epoch number (`KeyEntry.snapEpoch`) records "already handled":

```java
// the snapshot for generation N starts at time T:
epoch = N;  active = true;              // every existing entry has snapEpoch < N

// Db.add — a key created at any time:
e.snapEpoch = epoch;                    // created after T (if active): never written

// Db.lookupWrite / overwrite / delete — before any change:
if (active && e.snapEpoch < epoch) {    // not yet written, value still as of T
    writeRecord(e);                     // copy its bytes into the base file
    e.snapEpoch = epoch;
}

// background slice — walk the keyspace:
cursor = keyspace.scanStep(cursor, e -> {
    if (e.snapEpoch < epoch) { writeRecord(e); e.snapEpoch = epoch; }
});                                     // cursor back to 0 → every key visited
```

`writeRecord` copies the value's bytes into a chunk at once; the snapshot writer
never holds a reference to a live value.

### 5.3 The rewrite, step by step

Generation `g` is current; the manifest lists `[base.g, incr.g]`.

| # | Thread | Action |
|---|---|---|
| 1 | `cmd` | Between two commands: enqueue `ROTATE(g+1)` on the AOF queue, then `epoch = g+1; active = true`. This instant is **T**. |
| 2 | `aof-writer` | On reaching `ROTATE`: fsync `incr.g`; create and fsync empty `incr.(g+1)`; atomically replace the manifest with `[base.g, incr.g, incr.(g+1)]`; switch appends to `incr.(g+1)`. Effects queued after the marker land in the new file. |
| 3 | `cmd` | Background slices scan the keyspace; pre-images are written as commands touch keys. Records go in chunks to `snapshot-writer`, which writes `base.(g+1).jrdb.tmp`. |
| 4 | `cmd` | Scan cursor returns to 0: write the EOF record and CRC. |
| 5 | `snapshot-writer` | fsync and close the temp file; report done. |
| 6 | `cmd` | Enqueue `COMMIT(g+1)` on the AOF queue. |
| 7 | `aof-writer` | Rename the temp file to `base.(g+1).jrdb`; atomically replace the manifest with `[base.(g+1), incr.(g+1)]`; close and delete `base.g`, `incr.g`, and any older incr files. |

The manifest has a single writer — `aof-writer` — so its updates can never race.

If step 2 fails (for example, disk full), the writer keeps appending to
`incr.g`, deletes the partial new file, and reports failure; `cmd` aborts the
snapshot. The manifest was never changed, so nothing is inconsistent.

### 5.4 Why it is correct

Let S be the set of keys that existed at T, and V(k) the value of k at T.

1. **Every key in S is written.** If k is never modified or deleted after T, it
   is present for the whole scan, and `SCAN` guarantees it is visited
   ([04 §3.5](04-data-structures.md#35-scan-the-reverse-binary-cursor)). If it
   is modified or deleted, every such path goes through `lookupWrite` /
   `overwrite` / `delete` (rule 2 in [02 §7](02-architecture.md#three-rules-for-every-handler)),
   which writes it first.
2. **Each is written at most once.** The epoch is set on first write; `SCAN`
   duplicates and later changes are skipped.
3. **Each is written with V(k).** Before the first change after T, the value is
   still V(k); the pre-image is taken before that change.
4. **No other key is written.** Keys created after T carry the new epoch from
   birth.

So the base file is exactly the dataset at T. `incr.(g+1)` holds exactly the
effects executed after T, in order (step 2 puts the file switch at T in queue
order). Effects are deterministic (§3). Therefore loading base + incr
reproduces the state at any later point that was written — NFR-5
([01](01-requirements-and-scope.md#5-non-functional-requirements)).

Multi-key commands (`RENAME`, `LMOVE`, `SMOVE`, `SINTERSTORE`, `EXEC`…) need no
special handling: each key they touch gets its own pre-image through the same
hooks, before the command changes it.

### 5.5 `FLUSHALL` during a rewrite

Pre-imaging every key would mean writing the whole snapshot synchronously.
Instead, `FLUSHALL` **aborts** the running rewrite: delete the temp file, leave
the manifest at `[base.g, incr.g, incr.(g+1)]`. That list is still a correct
description of the data — the `FLUSHALL` itself is in `incr.(g+1)` — and the
next rewrite, now tiny, collapses it.

### 5.6 Why not the scheme in doc 04

[../../docs/archive/memstore.md §6](../../docs/archive/memstore.md) serialised keys in
their **current** state and tried to repair the mix of times at load, using
per-key `SNAPSHOT-CURSOR` markers to decide which log entries to replay. That
cannot work for commands touching two keys. Take `RPOPLPUSH src dst` executed
after `src` was serialised but before `dst` was:

- `src` in the file is from *before* the command; `dst` is from *after* it;
- replaying the command double-inserts into `dst`; skipping it leaves `src`
  holding an element that was popped.

Neither choice is right. The pre-image approach removes the problem: the base
file is a true image at one instant, and the log is simply replayed after it.

## 6. The big-key caveat

A single key is always written in one piece — by the scan or as a pre-image —
because it must be captured at one instant. For ordinary keys that takes
microseconds. For a very large collection it takes much longer, once per
rewrite. The command thread stalls for that time, so every client's reply
waits.

Measured on 2026-09-21 on the shared development VM, which was heavily loaded.
A dedicated server should do better. The design estimate was 50–100 ms per
million members.

| Key | Time to write it as one record |
|---|---|
| Sorted set, 1,000,000 members | 120–250 ms |
| Sorted set, 100,000 members | 20–30 ms (first write to it during a rewrite, which pays the pre-image) |

The cost is mostly walking a million scattered skip-list nodes. The encoder
itself only appends to a byte array. The value in `INFO persistence` is
`aof_rewrite_longest_record_ms`.

Guidelines:

- Keep collections below about **100,000 elements** when a stall of a few tens
  of milliseconds per rewrite matters. Split rankings by period and category
  (for example weekly, daily) ([09](09-integration-patterns.md#4-key-naming-and-size-guidelines)).
  Several leaderboards of 100k+ members work correctly. Each one adds its own
  short stall once per rewrite; the stalls do not add up into one long stall.
- `INFO persistence` reports the longest single-record write of the last rewrite,
  so a growing key becomes visible before it hurts.
- If a large key becomes unavoidable, chunked copy-on-write for big collections
  is an M8 option.

**Streams are the exception (2.1)**: a stream is held in chunks no holder ever
sees change, so the command thread copies only its chunk list and the writer
thread encodes the entries ([15 §4](15-streams.md#4-the-value-immutable-chunks),
D-33). Measured the same way on 2026-09-29, one run on the loaded VM: 300,000
entries of 400 bytes held the command thread 0 ms; a sorted set of 300,000
members of the same size, 232 ms. A test holds a 200,000-entry stream under
40 ms.

## 7. Crash analysis

The manifest is only ever replaced atomically (write temp, fsync, rename, fsync
directory), so on disk it is always one of its complete versions.

| Crash during | Manifest on disk | Loaded | Result |
|---|---|---|---|
| Normal operation | `[base.g, incr.g]` | both | State up to the last durable write |
| Step 2, before the manifest update | `[base.g, incr.g]` | both | Correct — nothing was appended to `incr.(g+1)` yet; the empty file is cleaned up |
| Steps 2–6 | `[base.g, incr.g, incr.(g+1)]` | base.g + both incr | Correct — the partial `.tmp` is ignored and deleted |
| Step 7, after the manifest update | `[base.(g+1), incr.(g+1)]` | both | Correct — old files are unreferenced and deleted |
| Mid-append to an incr file | any | — | Truncated final record discarded ([§8](#8-start-up-and-recovery)) |

Loss window: with `everysec`, writes acknowledged in the last ~1–2 s before a
power failure or `kill -9` (NFR-4). A clean shutdown fsyncs first and loses
nothing ([02 §13](02-architecture.md#13-start-up-and-shutdown)).

## 8. Start-up and recovery

1. Take `LOCK`; if another process holds it, exit: `data directory in use`.
2. Read `manifest`. Missing and the directory empty → fresh start, generation 1,
   no base. Missing but data files present → refuse to start; do not guess.
3. **Load the base** (if listed): stream it and verify magic, version and the
   CRC32 at the end. Load every key with its TTL, including keys whose TTL has
   already passed ([06 §4](06-expiry-and-memory.md#4-expiry-and-the-other-subsystems)):
   they become invisible at once and active expiry removes them after start-up.
   A bad CRC or format → refuse to start (exit code 3).
4. **Replay each incr file in order** through the command handlers in *loading
   mode*: no replies, no propagation, no lazy expiry, no pub/sub, no blocking.
   `MULTI` … `EXEC` in the file is buffered and applied only at `EXEC`.
5. **Truncated tail:** if the **last** incr file ends in the middle of a record,
   or inside an unfinished `MULTI`, log a WARN with the offset and number of
   bytes dropped, truncate the file there, and continue (`aof-load-truncated yes`,
   the default). Anywhere else — a bad record in the middle, or in an earlier
   file — is corruption: refuse to start and point the operator at
   `j-redis-check-aof`.
6. Delete files not referenced by the manifest.
7. Start `aof-writer` appending to the last incr file, then open the port.

`j-redis-tools check-aof <data-dir>` (server stopped; it takes the `LOCK`)
verifies the manifest, the base file's CRC and every incr file, and reports the
offset of the first bad record. `--fix` cuts only a torn tail of the **last**
incr file, the same thing start-up does with `aof-load-truncated yes`.
Corruption anywhere else is reported with its offset and never cut
automatically: truncating there loses data, so it stays an operator decision.
`j-redis-tools dump <data-dir>` prints the base file's keys (`--values` for
contents).

## 9. Base file format

All integers big-endian; lengths as unsigned LEB128 varints.

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
                      f × field), varint groups
trailer   u8 0xFF   u32 CRC32 of every preceding byte
```

**Version 2** (2.1) adds the stream record and nothing else: a version 1 file
loads as it always did, and a 2.0 server refuses a version 2 file by its
version. A stream record is encoded by the writer thread, from a frozen view,
in its place in the file (D-33).

Sorted sets are written in ascending order so the loader can build the skip list
by appending at the tail — O(1) per member instead of O(log n).

## 10. Rewrite triggers

| Trigger | Condition |
|---|---|
| Automatic | Incr files total ≥ `auto-aof-rewrite-min-size` (64 MB) **and** ≥ `auto-aof-rewrite-percentage` (100 %) of the base size; checked every second |
| `BGREWRITEAOF`, `BGSAVE` | Immediately, unless one is already running |
| `SAVE` | Synchronously: slices run back to back with no commands in between |
| `SHUTDOWN SAVE` | Synchronously before exit |

**Speed.** At roughly 0.5–1 µs per small key and the 25 % background duty cap
([02 §5](02-architecture.md#5-background-work)), a rewrite covers 250,000–500,000
keys per second — the expected data set in well under a second, 10 million keys in
20–40 s. Pre-images are always written immediately; only the *scan* is paced.

## 11. Platform notes

| Topic | Detail |
|---|---|
| Checksum | `java.util.zip.CRC32` (a hardware intrinsic on x86). `CRC32C` only arrived in Java 9. |
| Data fsync | `FileChannel.force(false)` maps to `fdatasync` on Linux, which also persists the file size needed to read appended data back |
| Directory fsync | `FileChannel.open(dir, READ).force(true)` works on Linux. On **Windows** opening a directory throws; skip it there — a weaker guarantee that is acceptable for development |
| Atomic replace | `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`, on both platforms |
| Deleting files | Close first — Windows cannot delete an open file. Retry a few times, since antivirus scanners briefly lock new files. |
| Single instance | `FileChannel.tryLock()` on `LOCK`, so two servers can never share a data directory |

## 12. Backup and restore

**Backup** — while running:

1. `CONFIG SET auto-aof-rewrite-percentage 0` so no automatic rewrite starts
   during the copy.
2. Optionally `BGREWRITEAOF` and wait until `INFO persistence` shows
   `aof_rewrite_in_progress:0` (makes the backup smaller).
3. Copy `manifest` **first**, then every file it lists.
4. Restore the previous `auto-aof-rewrite-percentage`.

This is consistent because files listed in the manifest are deleted only when a
rewrite commits, and step 1 prevents that during the copy. An incr file copied
mid-append merely has a truncated tail, which loading tolerates (§8). Without
step 1, a rewrite committing mid-copy could delete a file the copied manifest
still refers to.

**Restore:** stop the server, replace the data directory, start.
