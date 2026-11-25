# 15 — Streams (release 2.1)

This page describes streams, the data type that release 2.1 added. It is for
engineers who use streams from the backend, and for those who change the
stream code. It covers why streams were added, the Redis behaviour that j-redis
keeps, each command and what it writes to the AOF (append-only file), how a
stream is held in memory, how it is persisted, blocking reads, what is left out,
and how each part was proved.

A **stream** is an append-only log of entries. Each entry has an ID and a list
of field-value pairs. **Consumer groups** let several readers share the work of
reading a stream: each group has its own position in the stream, and remembers
which entries each of its consumers has received but not yet acknowledged.

## 1. Why, and how much

The backend's result pipeline needs a durable log that several consumer groups
read independently. It needs a pending list that survives the death of a
consumer, and a cursor that each group owns (the backend's
`docs/detailed-design/05-worker-and-events.md` §1). Before 2.1, streams were
out of scope ([01](01-requirements-and-scope.md)): nothing consumed them.
Release 2.1 adds them: the Redis 7 data type, and the command set a Redis
client would expect, minus what the backend has no use for.

The load is modest. The backend's design rate is about 170 entries a second,
and at launch a fifth of that, with entries of 400 bytes to 1 KB. Correct
behaviour and recovery matter; throughput does not.

## 2. Semantics kept exactly

Where Redis defines a behaviour, j-redis follows it. So code written against
j-redis runs on Redis, and the reverse. For streams, that means:

- **An entry ID is `ms-seq`**: two unsigned 64-bit numbers, ordered by `ms`,
  then by `seq`. `XADD key * …` takes the larger of the clock's milliseconds
  and the last ID's milliseconds, and the next sequence number within it. So
  IDs only grow, even when the clock steps back. An explicit ID must be greater
  than the stream's last ID, and `0-0` is never valid. `ms-*` lets the server
  pick the sequence number.
- **The last ID is remembered** after its entry is deleted or trimmed. So a new
  entry can never reuse an ID that a consumer has seen.
- **A range** is inclusive. `-` and `+` are the two ends, and `(` makes an end
  exclusive. An ID without `-seq` means `seq` 0 at the start of a range, and the
  largest `seq` at the end.
- **Trimming** (`MAXLEN` or `MINID`, on `XADD` or `XTRIM`) removes whole
  entries, oldest first, and ignores consumer groups: a trimmed entry that no
  one has read is gone. The server accepts `~` (approximate), but trims
  exactly, which Redis allows. It accepts `LIMIT` only together with `~`.
- **A consumer group** has a last-delivered ID and a pending-entries list
  (PEL). The PEL holds every entry delivered and not yet acknowledged, with its
  consumer, its last delivery time and its delivery count.
  - Reading with `>` delivers the entries after the last-delivered ID, and
    records them as pending (unless `NOACK` is given).
  - Reading with an explicit ID re-reads the consumer's own pending entries
    after that ID, without moving the group's cursor.
- **`XACK`** removes entries from the PEL. **`XCLAIM`** and **`XAUTOCLAIM`**
  move pending entries that have been idle at least a given time to another
  consumer, and count a delivery. `XAUTOCLAIM` drops from the PEL a pending
  entry whose stream entry has been deleted, and reports it as deleted
  (Redis 7).
- **Blocking reads** (`XREAD BLOCK`, `XREADGROUP BLOCK`) wait for an entry
  after the given position. They are served in the order they arrived, time
  out with a null reply, and never block inside `MULTI`. When a key is deleted
  while a group waits on it, the wait ends with an error, as in Redis.
- **Lag** (`XINFO GROUPS`) is the number of entries after the group's
  last-delivered ID. Redis 7 derives it from counters, and gives up (replies
  null) when entries were deleted in the middle. j-redis counts it exactly,
  which its counted structure makes cheap.

## 3. Commands

The 14 stream commands, with the effect each one writes to the AOF ("—" means
it writes nothing; "*same*" means the command is written as sent):

| Command | AOF effect | Notes |
|---|---|---|
| `XADD key [NOMKSTREAM] [MAXLEN\|MINID [=\|~] t [LIMIT n]] *\|id f v [f v …]` | `XADD key <id> [MAXLEN\|MINID = t] f v …` | The AOF holds the ID the clock gave, and an approximate trim as the exact trim it was |
| `XLEN key` | — | |
| `XRANGE` / `XREVRANGE key start end [COUNT n]` | — | |
| `XDEL key id…` | *same* | |
| `XTRIM key MAXLEN\|MINID [=\|~] t [LIMIT n]` | `XTRIM key MAXLEN\|MINID = t` | |
| `XREAD [COUNT n] [BLOCK ms] STREAMS key… id…` | — | `$` means the stream's last ID at the moment the read starts |
| `XGROUP CREATE key g id\|$ [MKSTREAM]` | `XGROUP CREATE key g <id> [MKSTREAM]` | |
| `XGROUP SETID key g id\|$` | `XGROUP SETID key g <id>` | |
| `XGROUP DESTROY key g`, `CREATECONSUMER key g c`, `DELCONSUMER key g c` | *same* | `DELCONSUMER` drops the consumer's pending entries, as Redis does |
| `XREADGROUP GROUP g c [COUNT n] [BLOCK ms] [NOACK] STREAMS key… id…` | For each entry delivered, `XCLAIM key g c 0 <id> TIME <ms> RETRYCOUNT <n> FORCE JUSTID` (none with `NOACK`); then `XGROUP SETID key g <last>`. If the read created the consumer, `XGROUP CREATECONSUMER` comes first | Several effects go in one `MULTI`, as `SPOP`'s do ([05](05-commands.md)) |
| `XACK key g id…` | *same* | |
| `XPENDING key g [[IDLE ms] start end count [c]]` | — | Both the summary form and the extended form |
| `XCLAIM key g c min-idle id… [IDLE ms] [TIME ms] [RETRYCOUNT n] [FORCE] [JUSTID] [LASTID id]` | One `XCLAIM … TIME RETRYCOUNT FORCE JUSTID` for each entry claimed; `XGROUP SETID` if `LASTID` moved the group; `XACK` for a pending entry whose stream entry is gone | If the consumer it names does not exist, the server creates it, as in Redis |
| `XAUTOCLAIM key g c min-idle start [COUNT n] [JUSTID]` | The `XCLAIM`s it made, and `XACK` for pending entries whose stream entry is gone | Replies with the next cursor, the claimed entries and the deleted IDs (Redis 7) |
| `XINFO STREAM key`, `XINFO GROUPS key`, `XINFO CONSUMERS key g` | — | `GROUPS` includes `lag`, counted exactly (§2). The fields are those of Redis 7.2, minus the ones left out (§7). `STREAM` gives `length`, `radix-tree-keys` and `radix-tree-nodes` (both are the number of chunks), `last-generated-id`, `groups`, and the first and last entry. `CONSUMERS` gives `name`, `pending`, `idle` and `inactive`, ordered by name |

**Why deliveries are logged as `XCLAIM`.** A replay runs with the clock frozen
at the moment loading began. So a command that reads the time must log the
time it read. A delivery records when it happened and how many times the entry
has been delivered. `XCLAIM … TIME RETRYCOUNT FORCE JUSTID LASTID` says exactly
that, in Redis's own vocabulary, and it is also what Redis propagates
([14 D-34](14-decision-log.md#d-34--stream-deliveries-are-logged-as-xclaim-with-their-time)).

A consumer's seen time is not logged. After a restart, `XINFO CONSUMERS` counts
a consumer's idle time from the restart. Every pending entry keeps its exact
delivery time, and that is what `XAUTOCLAIM` uses to decide.

## 4. The value: immutable chunks

A stream is a `StreamValue`, of type `KeyEntry.STREAM` (5); `TYPE` replies
`stream`. The server holds its entries in **chunks of up to 256 entries**
(`StreamValue.CHUNK`). Each chunk is a view over parallel arrays: the IDs'
milliseconds and sequence numbers as `long[]`, and each entry's fields as a
`byte[][]`. The stream keeps its chunks in order, the last ID it ever gave, and
its consumer groups.

**A chunk never changes once another holder can see it.** An append writes only
past the end of the last chunk, which is the only part that grows. A delete or
a trim that cuts into a chunk replaces the chunk with a new view; a trimmed
head shares the old arrays from a later offset. So a snapshot of the stream is
the list of chunk references plus the last chunk's current length:
O(entries / 256), however large the stream is
([14 D-33](14-decision-log.md#d-33--a-stream-is-held-in-immutable-chunks-and-encoded-off-the-command-thread)).

**Why that matters here.** A rewrite writes every key on the command thread,
either when its scan reaches the key, or as a pre-image before the key's first
change ([08 §5](08-persistence.md#5-the-fork-free-snapshot)). A big key stalls
every client for as long as that takes: 20–30 ms per 100,000 sorted-set
members, as measured ([08 §6](08-persistence.md#6-the-big-key-caveat)). The
backend's result stream holds millions of entries and is written to all the
time, so it would pay that at every rewrite, in seconds. Instead, the server
hands a stream's record to the base file's writer thread as a frozen view, in
its place in the queue, and the writer thread encodes it there. The command
thread copies only the chunk list and the groups' pending lists.

**Lookups.** A lookup is a binary search over the chunks' first IDs, then a
search within the chunk. So `XRANGE`, `XREADGROUP >` and a group's lag find
their place in O(log n). The lag then adds up the counts of the whole chunks
after that place.

**Consumer groups** are kept in the order they were created. Each group has:

- its last-delivered ID;
- its pending-entries list, ordered by ID, with each entry's consumer, last
  delivery time and delivery count;
- its consumers, in the order they were created, each with its seen time and
  its own pending IDs.

**An empty stream stays.** The server deletes other collections when they
become empty ([guide 06 §6.3](guide/06-developer-guide.md#63-the-rules)). It
keeps an empty stream, as Redis does, because the stream's last ID and its
groups outlive its entries.

**Memory** is estimated per chunk (its arrays), per entry (its fields), per
group, per pending entry and per consumer, with the rest of the accounting
([04 §8](04-data-structures.md#8-memory-accounting)). A backend result of 400
bytes costs about 100 bytes more in the stream: its IDs, its references and the
headers of two arrays.

## 5. Persistence

The base file format moves to **version 2**, which adds a stream record with
opcode `0x06`. The record holds the last ID, the entries (ID, field count,
fields), then each group: its name, its last-delivered ID, its consumers with
their pending IDs, and each pending entry's delivery time and count
([08 §9](08-persistence.md#9-base-file-format)).

Version 1 files still load. A 2.0 server refuses a version 2 file, as it would
refuse a stream command in the AOF anyway. So once a stream exists, there is
no way back from 2.1 to 2.0 ([guide 07](guide/07-upgrade-guide.md)).

## 6. Blocking

`XREAD BLOCK` and `XREADGROUP BLOCK` wait as the list pops do
([07 §2](07-pubsub-blocking-transactions.md)):

- they are served in the order they arrived;
- the timeout is in milliseconds, and `BLOCK 0` waits forever;
- they time out with a null reply.

Every write to a key wakes its waiting readers, `XACK` included. So the server
serves a waiting reader only when an entry after the reader's position exists.
A reader waiting in a group whose stream is deleted, or whose group is
destroyed, gets an error at once, as Redis 7 answers it. It is not left waiting
for an entry that cannot come.

## 7. Left out, on purpose

| Left out | Why |
|---|---|
| `XSETID`, `XINFO STREAM FULL`, `ENTRIESREAD`, `entries-read` in `XINFO` | The backend has no use for them; the plain forms are there |
| Consumer-group state for replicas | No longer left out: replication arrived in 2.2, and a replica receives the same effects as the AOF, consumer groups included ([16 §4](16-replication.md#4-the-stream-of-changes)) |
| Sharing field names across entries | Redis's listpacks share field names between entries. j-redis waits for a measured need first (M8's rule) |

## 8. Parts, and how each is proved

Release 2.1 was built in five parts. All five were built on 2026-09-29, and
2.1.0 was released that day.

| Part | What | Proved by |
|---|---|---|
| (a) | The type, `XADD`, `XLEN`, `XRANGE`, `XREVRANGE`, `XDEL`, `XTRIM`; `TYPE`, `COPY`, `SCAN TYPE`, memory | Command tests; the model-based test, against a plain ordered map. **Built 2026-09-29** |
| (b) | Persistence: format 2, the effects, the record encoded off the command thread | Tests of restart, rewrite under writes, a torn end and loading version 1; the longest record's time on the command thread. **Built 2026-09-29**: 300,000 entries held the command thread for 0 ms, and a sorted set of as many members for 232 ms |
| (c) | Groups: `XGROUP`, `XREADGROUP`, `XACK`, `XPENDING`, `XCLAIM`, `XAUTOCLAIM`, `XINFO` | Command tests; the model; replayed state equal to the live state, delivery times included. **Built 2026-09-29**. Proved by command tests, the rewrite-under-writes workload with groups, and a replay under a moved clock; not by the model-based test, which has no groups |
| (d) | Blocking `XREAD` and `XREADGROUP` | Blocking tests, including a deleted stream and a destroyed group. **Built 2026-09-29**, together with `XREAD` itself |
| (e) | The client library, the CLI's big keys, the dump tool; 2.1.0 | Client tests; the offline build; a release smoke test. **Built 2026-09-29; 2.1.0 released** |
