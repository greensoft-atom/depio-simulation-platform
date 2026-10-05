# 15 — Streams (release 2.1)

## 1. Why, and how much

The backend's result pipeline needs a durable log that several consumer groups
read independently, with a pending list that survives a consumer's death and a
cursor each group owns (backend `docs/detailed-design/05-worker-and-events.md`
§1). Until now streams were out of scope ([01](01-requirements-and-scope.md)):
nothing consumed them. Release 2.1 brings them in, as the Redis 7 data type and
command set a Redis client would expect, less what the backend has no use for.

The load is modest: the backend's design rate is about 170 entries a second,
at launch a fifth of that, entries of 400 bytes to 1 KB. Semantics and
recovery matter; throughput does not.

## 2. Semantics kept exactly

Where Redis defines a behaviour, j-redis follows it, so that code written
against j-redis runs on Redis and the reverse. What that means for streams:

- **An entry ID is `ms-seq`**, two unsigned 64-bit numbers, ordered by `ms` then
  `seq`. `XADD key * …` takes the larger of the clock's milliseconds and the last
  ID's, and the next sequence within it, so IDs only grow even when the clock
  steps back. An explicit ID must be greater than the stream's last; `0-0` is
  never valid. `ms-*` picks the sequence.
- **The last ID is remembered** after its entry is deleted or trimmed, so a new
  entry can never reuse an ID a consumer has seen.
- **A range** is inclusive, `-` and `+` the ends, `(` makes an end exclusive, an
  ID without `-seq` means `seq` 0 at the start and the largest at the end.
- **Trimming** (`MAXLEN` or `MINID`, on `XADD` or `XTRIM`) removes whole oldest
  entries and ignores consumer groups: an unread entry trimmed is gone. `~`
  (approximate) is accepted and trims exactly, which Redis allows; `LIMIT` is
  accepted with `~` only.
- **A consumer group** has a last-delivered ID and a pending-entries list (PEL):
  every entry delivered and not yet acknowledged, with its consumer, its last
  delivery time and its delivery count. `>` delivers entries after the
  last-delivered ID and records them pending (unless `NOACK`); an explicit ID
  re-reads the consumer's own pending entries after it, without moving the
  group's cursor.
- **`XACK`** removes entries from the PEL; `XCLAIM` and `XAUTOCLAIM` move
  pending entries idle at least a given time to another consumer, counting a
  delivery; a pending entry whose stream entry has been deleted is dropped from
  the PEL by `XAUTOCLAIM` and reported as deleted (Redis 7).
- **Blocking reads** (`XREAD BLOCK`, `XREADGROUP BLOCK`) wait for an entry after
  the given position, are served in arrival order, time out with a null reply,
  and never block inside `MULTI`. A key deleted while a group waits on it ends
  that wait with an error, as Redis does.
- **Lag** (`XINFO GROUPS`): entries after the group's last-delivered ID. Redis 7
  derives it from counters and gives up (null) when entries were deleted in the
  middle; here it is counted exactly, which a counted structure makes cheap.

## 3. Commands

| Command | AOF effect | Notes |
|---|---|---|
| `XADD key [NOMKSTREAM] [MAXLEN\|MINID [=\|~] t [LIMIT n]] *\|id f v [f v …]` | `XADD key <id> [MAXLEN\|MINID = t] f v …` | The ID the clock gave is logged, and an approximate trim as the exact one it was |
| `XLEN key` | — | |
| `XRANGE` / `XREVRANGE key start end [COUNT n]` | — | |
| `XDEL key id…` | *same* | |
| `XTRIM key MAXLEN\|MINID [=\|~] t [LIMIT n]` | `XTRIM key MAXLEN\|MINID = t` | |
| `XREAD [COUNT n] [BLOCK ms] STREAMS key… id…` | — | `$` is the stream's last ID when the read starts |
| `XGROUP CREATE key g id\|$ [MKSTREAM]` | `XGROUP CREATE key g <id> [MKSTREAM]` | |
| `XGROUP SETID key g id\|$` | `XGROUP SETID key g <id>` | |
| `XGROUP DESTROY key g`, `CREATECONSUMER key g c`, `DELCONSUMER key g c` | *same* | `DELCONSUMER` drops the consumer's pending entries, as Redis does |
| `XREADGROUP GROUP g c [COUNT n] [BLOCK ms] [NOACK] STREAMS key… id…` | per entry delivered, `XCLAIM key g c 0 <id> TIME <ms> RETRYCOUNT <n> FORCE JUSTID` (none with `NOACK`); then `XGROUP SETID key g <last>`; a consumer it made, `XGROUP CREATECONSUMER` first | Several effects go in one `MULTI`, as `SPOP`'s do ([05](05-commands.md)) |
| `XACK key g id…` | *same* | |
| `XPENDING key g [[IDLE ms] start end count [c]]` | — | The summary form and the extended one |
| `XCLAIM key g c min-idle id… [IDLE ms] [TIME ms] [RETRYCOUNT n] [FORCE] [JUSTID] [LASTID id]` | one `XCLAIM … TIME RETRYCOUNT FORCE JUSTID` per entry claimed; `XGROUP SETID` if `LASTID` moved the group; `XACK` for a pending entry whose stream entry is gone | A consumer it names is made, as in Redis |
| `XAUTOCLAIM key g c min-idle start [COUNT n] [JUSTID]` | the `XCLAIM`s it made, and `XACK` for pending entries whose stream entry is gone | Replies with the next cursor, the claimed entries and the deleted IDs (Redis 7) |
| `XINFO STREAM key`, `XINFO GROUPS key`, `XINFO CONSUMERS key g` | — | `GROUPS` includes `lag`, counted exactly (§2). The fields are Redis 7.2's less the ones left out (§7): `STREAM` gives length, `radix-tree-keys` and `radix-tree-nodes` (both the chunk count), last-generated-id, groups, first and last entry; `CONSUMERS` gives name, pending, idle and inactive, by name |

**Why deliveries are logged as `XCLAIM`.** Replay runs with the clock frozen at
the moment loading began: a command that reads the time must log what it read.
A delivery records when it happened and how many times the entry has been
delivered, and `XCLAIM … TIME RETRYCOUNT FORCE JUSTID LASTID` says exactly that
in Redis's own vocabulary, which is also what Redis propagates
([14 D-34](14-decision-log.md#d-34--stream-deliveries-are-logged-as-xclaim-with-their-time)).
A consumer's seen time is not logged: after a restart `XINFO CONSUMERS` counts a
consumer's idle time from the restart, while every pending entry keeps its exact
delivery time, which is what `XAUTOCLAIM` decides by.

## 4. The value: immutable chunks

A stream is `StreamValue`, type `KeyEntry.STREAM` (5), `TYPE` `stream`. Its
entries are held in **chunks of up to 256**, each a view over parallel arrays:
the IDs' milliseconds and sequences as `long[]`, and each entry's fields as a
`byte[][]`. The stream keeps the chunks in order, the last ID ever given, and
its consumer groups.

**A chunk is never changed once another holder can see it.** Appends write only
past the end of the last chunk, which is the one part that grows; a delete or a
trim that cuts into a chunk replaces it with a new view (a trimmed head shares
the old arrays from a later offset). So a snapshot of the stream is the list of
chunk references and the last chunk's current length, O(entries / 256), however
large the stream is ([14 D-33](14-decision-log.md#d-33--a-stream-is-held-in-immutable-chunks-and-encoded-off-the-command-thread)).

**Why that matters here.** A rewrite serialises every key on the command thread,
either as its scan reaches it or as a pre-image before its first change
([08 §5](08-persistence.md#5-the-fork-free-snapshot)), and a big key stalls every client for as long as
that takes: 20–30 ms per 100 000 sorted-set members, measured
([08 §6](08-persistence.md#6-the-big-key-caveat)). The backend's result stream
holds millions of entries and is written to all the time, so it would pay that
at every rewrite, in seconds. Instead a stream's record is handed to the base
file's writer thread as a frozen view, in its place in the queue, and encoded
there; the command thread copies the chunk list and the groups' pending lists,
nothing else.

A lookup is a binary search over the chunks' first IDs, then within a chunk:
`XRANGE`, `XREADGROUP >` and a group's lag are O(log n) to find their place, and
lag then adds up whole chunks' counts after it.

**Consumer groups** are held in creation order. Each has its last-delivered ID,
its pending-entries list ordered by ID (consumer, last delivery time, delivery
count), and its consumers in creation order, each with its seen time and its own
pending IDs.

**An empty stream stays.** Other collections are deleted when emptied
([guide 06 §6.3](guide/06-developer-guide.md)); a stream is kept, as in Redis,
because its last ID and its groups outlive its entries.

**Memory** is estimated per chunk (its arrays), per entry (its fields), per
group, per pending entry and per consumer, with the rest of the accounting
([04 §8](04-data-structures.md#8-memory-accounting)). A backend result of 400 bytes costs about
100 more in the stream: its IDs, its references and two arrays' headers.

## 5. Persistence

The base format goes to **version 2** with a stream record, opcode `0x06`: the
last ID, the entries (ID, field count, fields), then each group (name,
last-delivered ID, consumers with their pending IDs, and each pending entry's
delivery time and count). Version 1 files still load; a 2.0 server refuses a
version 2 file, as it would refuse a stream command in the AOF anyway, so there
is no way back from 2.1 once a stream exists ([guide 07](guide/07-upgrade-guide.md)).

## 6. Blocking

`XREAD BLOCK` and `XREADGROUP BLOCK` wait as the list pops do
([07 §2](07-pubsub-blocking-transactions.md)), in arrival order, with the
timeout in milliseconds and `BLOCK 0` for ever, and time out with a null reply.
Every write to a key wakes its waiters, including `XACK`, so a waiter is served
only when an entry after its position exists. A waiter in a group whose stream
is deleted or whose group is destroyed is answered with an error at once, as
Redis 7 answers it, rather than left waiting for an entry that cannot come.

## 7. Left out, on purpose

| Left out | Why |
|---|---|
| `XSETID`, `XINFO STREAM FULL`, `ENTRIESREAD`, `entries-read` in `XINFO` | No use in the backend; the plain forms are there |
| Consumer-group state for replicas | Replication is 2.2 |
| Field-name compression across entries | Redis's listpacks share field names; a measured need first (M8's rule) |

## 8. Parts, and how each is proved

| Part | What | Proved by |
|---|---|---|
| (a) | The type, `XADD`, `XLEN`, `XRANGE`, `XREVRANGE`, `XDEL`, `XTRIM`; `TYPE`, `COPY`, `SCAN TYPE`, memory | Command tests; the model-based test against a plain ordered map. **Built 2026-09-29** |
| (b) | Persistence: format 2, the effects, the record encoded off the command thread | Restart, rewrite-under-writes, torn-tail and version 1 load tests; the longest record's time on the command thread. **Built 2026-09-29**: 300,000 entries held the command thread 0 ms, a sorted set of as many 232 ms |
| (c) | Groups: `XGROUP`, `XREADGROUP`, `XACK`, `XPENDING`, `XCLAIM`, `XAUTOCLAIM`, `XINFO` | Command tests; the model; replayed state equal to live, delivery times included. **Built 2026-09-29**, proved by command tests, the rewrite-under-writes workload with groups, and a replay under a moved clock; not by the model-based test, which has no groups |
| (d) | Blocking `XREAD` and `XREADGROUP` | Blocking tests, including a deleted stream and a destroyed group. **Built 2026-09-29**, with `XREAD` itself |
| (e) | The client library, the CLI's big keys, the dump tool; 2.1.0 | Client tests; the offline build; a release smoke test. **Built 2026-09-29; 2.1.0 released** |
