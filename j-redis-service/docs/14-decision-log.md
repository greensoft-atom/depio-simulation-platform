# 14 — Decision log

Each entry: the context, the decision, and what follows from it. "Supersedes"
names the part of [../../docs/archive/memstore.md](../../docs/archive/memstore.md) it
replaces. All entries are **accepted** as of 2026-09-21. D-18 to D-25 were
made during implementation (2026-09-21), each because a test or a measurement
showed the need.

---

### D-1 — RESP2 as the wire protocol
**Supersedes:** doc 04 §5 (custom binary protocol with request ids).

**Context.** We write both server and client, so the format is our choice.
Doc 04 proposed a custom binary protocol.

**Decision.** Use RESP2.

**Why.** A complete, proven specification we do not have to design, document,
or debug. Simple, binary-safe, readable in captures and over `telnet`. The AOF
reuses the same encoding. Loopback parsing cost differs negligibly from a custom
binary format; system calls dominate.

**Consequences.** No request ids: replies are correlated by order, so blocking
commands and pub/sub need their own connections, and a timed-out request stays
in the FIFO until its reply arrives ([10 §4](10-client-library.md#4-correlating-replies)).
Speaking RESP does not mean using third-party software (D-14).

---

### D-2 — One command thread; Netty for I/O

**Decision.** All data is owned and mutated by a single thread; Netty threads
only parse and write bytes.

**Why.** Atomicity of every command without locks; the model that made Redis
fast and simple. The estimated load (~5k ops/s) is 20× below what one thread
should sustain.

**Consequences.** One slow command delays everyone, so every long operation —
rehash, expiry, snapshot — is sliced ([02 §5](02-architecture.md#5-background-work)),
and big keys matter ([08 §6](08-persistence.md#6-the-big-key-caveat)).

---

### D-3 — Own hash table with incremental rehash and SipHash

**Decision.** `Dict`, not `java.util.HashMap`.

**Why.** `HashMap` resizes in one step — hundreds of milliseconds at 10M keys —
and offers no stable cursor for `SCAN`. SipHash with a random key resists
hash-flooding through user-chosen key text.

**Consequences.** More code to own and test; covered by property tests from M2.

---

### D-4 — Multi-part AOF as the only persistence mechanism
**Supersedes:** doc 04 §6 (AOF plus a separate snapshot file).

**Decision.** A base file, incremental files, and a manifest; a snapshot is
simply the base produced by a rewrite.

**Why.** One mechanism instead of two; atomic manifest replacement makes every
crash point recoverable ([08 §7](08-persistence.md#7-crash-analysis)).

---

### D-5 — Fork-free point-in-time snapshots by per-key copy-on-write
**Supersedes:** doc 04 §6 (`SNAPSHOT-CURSOR` markers).

**Context.** The JVM cannot `fork()`. Doc 04's scheme serialised keys in their
current state and tried to reconcile at load time, which is unsound for
multi-key commands ([08 §5.6](08-persistence.md#56-why-not-the-scheme-in-doc-04)).

**Decision.** Take a pre-image of each key just before its first change after
T; scan the rest; mark progress with an epoch per entry.

**Consequences.** The base is a true image at T and loading is plain replay.
Correctness depends on every write going through the `Db` hooks (D-2's
structure makes that enforceable). One large key is written in one piece.

---

### D-6 — Log effects, not commands

**Decision.** The AOF records deterministic effects: absolute expiry times,
results of float arithmetic, concrete members removed by random pops, `DEL` for
expirations.

**Why.** A replay hours later must reproduce exactly what happened.

---

### D-7 — Transactions yes, scripting no

**Decision.** Implement `MULTI`/`EXEC`/`DISCARD`/`WATCH`. No Lua, no Functions.

**Why.** `MULTI`/`EXEC` is cheap given D-2 and removes four of doc 04's custom
commands. A scripting engine is a large, security-sensitive dependency, and the
backend needs only three read-decide-write operations, provided as commands (D-8).

---

### D-8 — Standard commands first; `J.` extensions only where necessary
**Supersedes:** doc 04 §4 (seven custom commands).

**Decision.** Replace `CLAIM`, `ZADDMAX`, `HSETEXNX`, `LPUSHCAP`, `INCREX` with
standard commands and options (`ZADD GT`, `EXPIRE NX`, `SET NX EX`) and
`MULTI`/`EXEC`. Keep `J.ZAROUND`, `J.CAS`, `J.CAD` — standard Redis cannot do
these atomically in one round trip without Lua.

**Why.** Standard semantics are specified by the Redis documentation, not by us.
The `J.` prefix can never collide with a future Redis command name.

---

### D-9 — Disconnect slow subscribers
**Supersedes:** doc 04 §3 ("messages dropped").

**Decision.** A subscriber over its output limit is disconnected, not silently
skipped.

**Why.** Silent gaps are undetectable; a disconnect is visible, and the client
library reports the reconnect so the application can resynchronise.

---

### D-10 — `noeviction` only

**Decision.** Over `maxmemory`, writes fail with `-OOM`; nothing is evicted.

**Why.** Silently evicting a token or session becomes a mysterious application bug.
LRU is deferred to M8.

---

### D-11 — On-heap storage, no compact encodings in v1

**Decision.** Keys and values are ordinary Java objects; no off-heap memory, no
flat small-value encodings.

**Why.** Simplest correct design; the expected data set is well under 1 GB. Costs
about 1.5–2× Redis's memory. Compact encodings are an M8 option if measurement
shows a need.

---

### D-12 — Fail-stop after a partial mutation

**Decision.** An unexpected exception after a command started changing data
exits the process (code 4); before any change, it only fails the command.

**Why.** A half-applied change can corrupt a structure and poison every later
answer. The failing command was never logged, so restarting and replaying
yields the exact prior state ([02 §11](02-architecture.md#11-failure-handling-inside-a-command)).

---

### D-13 — Default port 6379
**Supersedes:** doc 04's port 6400.

**Decision.** Default to 6379; configurable.

**Why.** The conventional port for this protocol. Set `port 6400` to keep the
old value; no third-party Redis runs on the machines to collide with.

---

### D-14 — Our own client and tools only

**Decision.** No third-party Redis server, client library, or tool is used
anywhere — production, development, or testing. We build `j-redis-client`,
`j-redis-cli`, and `j-redis-benchmark`.

**Consequences.** No differential testing against real Redis. Correctness rests
on the documented Redis 7.2 semantics plus our own reference model
([12 §1](12-testing-strategy.md#1-the-oracle-problem)).

---

### D-15 — Clean-room implementation; Redis 7.2 semantics as reference

**Decision.** Implement from the public documentation of the protocol and
commands. Do not port Redis source code. Use Redis 7.2 behaviour as the
reference.

**Why.** 7.2 is the last BSD-licensed release and the base of Valkey, with
stable, widely documented behaviour. Later Redis source is under
RSALv2/SSPLv1/AGPLv3 ([00 §14](00-redis-primer.md#14-versions-licensing-forks)),
and working from documentation keeps the licensing question simple. Whether
anything more is needed is a question for legal counsel, not this document.

---

### D-16 — `appendfsync everysec` by default; `always` deferred

**Decision.** `everysec` and `no` in v1; `always` (with group commit) in M8.

**Why.** Durable business data lives in MongoDB. What j-redis holds is either
rebuildable or tolerates losing about a second, and producers of important results
spool must-not-lose data locally until acknowledged.

---

### D-17 — Deterministic time buckets for active expiry

**Decision.** One-second buckets with one registration per key, instead of
Redis's random sampling ([06 §3](06-expiry-and-memory.md#3-active-expiry)).

**Why.** Predictable memory reclamation and reproducible tests. Clients never
see the difference: lazy expiry guarantees an expired key is never read.

---

### D-18 — Resolved open questions

**Decision** (by the project owner):

| Question | Answer |
|---|---|
| Java packages | `com.jredis.server`, `com.jredis.client`, plus `common`, `embedded`, `cli`, `tools` |
| Port | 6379, configurable (D-13) |
| Largest leaderboard | Several with 100k+ members. They are supported; the rewrite stall per key is measured in [08 §6](08-persistence.md#6-the-big-key-caveat). |
| Persist on every write? | No. Persist periodically, and make the period configurable: `appendfsync everysec` with `appendfsync-interval-millis` (default 1000) |
| Team | One developer |
| Java level | Java 8 only: source, target and every dependency (bytecode major ≤ 52 is verified on the built jars). **Superseded by D-31** (2026-09-23): Java 21, major ≤ 65. |

---

### D-19 — One command connection per calling thread

**Context.** With `commandConnections > 1`, sending each command to the next
connection in turn broke per-thread order. A thread's `INCR` #2 could run
before its `INCR` #1. The client test that found it runs 8 threads × 5,000
commands.

**Decision.** Each calling thread is pinned to one connection (thread id modulo
the count). It uses another connection only while its own is down.

**Why.** Code that sends `SET k` and then `EXPIRE k` without waiting must see
them run in that order. Spreading threads, not commands, keeps the load balanced
for services with many worker threads.

---

### D-20 — Registries use `java.util.HashMap`

**Decision.** Pub/Sub channels, blocked keys and `WATCH`ed keys live in
`HashMap<ByteKey, …>`, not in our `Dict`.

**Why.** They are small, never persisted and never `SCAN`ned. They are also
never large enough for a resize to pause the command thread noticeably.
`Dict`'s incremental rehash and reverse-binary cursor exist for the keyspace
only. `ByteKey` caches the hash of the key bytes.

---

### D-21 — Embedded mode over Netty's in-VM transport

**Supersedes:** the `ClientOutput`-queue design first sketched for
[10 §10](10-client-library.md#10-embedded-mode).

**Decision.** The embedded server listens on a `LocalServerChannel`, and its
client connects with `LocalChannel`. Both sides run the normal pipelines.

**Why.** Embedded tests then exercise the real protocol code, backpressure and
reply ordering, not a parallel path. Netty ships the transport, so it cost no
new code.

---

### D-22 — Replies are sent once the effect is queued for the AOF writer

**Decision.** A write is acknowledged when its effect is handed to the AOF
writer thread, before the `write(2)`. The writer writes continuously and
fsyncs every `appendfsync-interval-millis`.

**Why.** Waiting for the writer thread on every write would add a thread
hand-off to each write's latency. NFR-4 already allows losing up to about 2 s
of acknowledged writes on `kill -9` or power loss.

**Measured.** Runs of 5 × `kill -9` under a transactional workload lost 0 to 8
acknowledged transactions per kill (the last few milliseconds), and never split
a transaction. Redis writes
before replying, so a *process* crash of Redis loses nothing acknowledged. Ours
can lose the last few milliseconds. `appendfsync always` with group commit
(M8) would close this gap.

---

### D-23 — Expiry boundary: expired at `now >= expireAt`

**Decision.** Keep the rule from [06 §2](06-expiry-and-memory.md#2-lazy-expiry).
Redis 7.2 uses `now > expireAt`, so the two differ by one millisecond at the
boundary. This is listed in [05 §3](05-commands.md#3-deviations-from-redis-72).

**Why.** "A 10 s TTL is gone at 10 s" is easier to reason about, and no client
can observe a 1 ms difference reliably.

---

### D-24 — Tools ship as one program

**Decision.** `j-redis-tools benchmark | check-aof | dump` instead of three
programs; other documents still call them `j-redis-benchmark`,
`j-redis-check-aof` and `j-redis-dump`.

**Why.** One jar, one launcher, and the same client code for all three.

---

### D-25 — JMH microbenchmarks deferred

**Decision.** The microbenchmarks in [12 §9](12-testing-strategy.md#9-performance)
wait for the production box. Until then the property tests guard correctness,
and `j-redis-tools benchmark` plus the rewrite's `aof_rewrite_longest_record_ms`
give end-to-end numbers.

**Why.** Microbenchmark numbers from the shared development VM (load average
about 9 on 12 cores) would mislead more than they inform. JMH 1.37 is in the
offline bundle when needed.

---

## Decisions from the review (2026-09-21)

Six reviewers each went through one part of the code: protocol, data
structures, commands, persistence, engine and client. These decisions came out
of their findings. Every fixed defect has a regression test
([12 §12](12-testing-strategy.md#12-what-is-implemented-2026-09-21)).

### D-26 — Disabled commands stay replayable

**Decision.** `disable-command` marks a command instead of removing it.
Clients get "unknown command"; the AOF loader may still replay it. A name that
is not a command is a start-up error.

**Why.** Other commands log it as their effect: `FLUSHDB` logs `FLUSHALL`,
`RPOPLPUSH` logs `LMOVE`, expiry logs `DEL`. With the shipped production config
(`disable-command FLUSHALL`), one `FLUSHDB` made the next start fail. A typo
must not silently leave a dangerous command enabled.

### D-27 — A closing client is detached at once

**Decision.** When a client is killed, sends `QUIT`, has a protocol error, or
half-closes its connection, it leaves the blocking queues, its subscriptions
and its watches immediately. Its queued and deferred commands are dropped. The
rest is freed when the channel's close event arrives.

**Why.** Otherwise a `BLPOP` of a killed client could still be served. The
element would be popped, logged, and written to a dead socket: lost.

### D-28 — The AOF writer and rewrite fail safe

**Decisions.**
- Any failure while encoding a snapshot record aborts the rewrite.
- A dead base writer aborts it in any state.
- `SAVE` refuses at once while the AOF cannot be written.
- A failed truncate-back blocks writing until it succeeds.
- After the manifest names a new incr file, that file is never deleted.
- A failed `DEBUG RELOAD` is a fail-stop.
- Automatic rewrites back off (1 s doubling to 1 h) after a failure.

**Why.** The review found paths where a half-written base could be committed,
where a rewrite stayed "running" forever, and where a failure on a full disk
recurred every second.

### D-29 — `INCRBYFLOAT` uses exact decimal arithmetic

**Decision.** Add the two values as `BigDecimal` and print 17 fractional digits
with trailing zeros removed ([03 §7](03-protocol.md#7-number-formatting)).

**Why.** Redis computes in `long double`, so `0.1 + 0.2` is `0.3` there. Binary
`double` gave `0.30000000000000004`, which users would see as a bug.

### D-30 — Client connections: one lifecycle, no surprises

**Decisions.**
- `start()` is idempotent, and CLOSED is final.
- A lost `WATCH` fails the `EXEC`.
- Timeout checks cannot be cancelled by callbacks.
- `close()` works from a callback.
- DNS is resolved off the event loop.

**Why.** Each was a way for a caller to get a hang, a leak, a reply meant for
somebody else, or a transaction that committed unchecked.


## Decisions from the Java 21 migration (2026-09-23)

### D-31 — Java 21 replaces Java 8, and ZGC replaces G1

**Decision.** From 2.0.0 the service requires JDK 21. The default collector is
generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) in development as well as
production, and launchers pass `--add-opens java.base/java.nio=ALL-UNNAMED`
outside the overridable `JREDIS_JAVA_OPTS`.

**Why.** Java 8 was never a goal in itself; it came from the original target
machine. That machine is now RHEL 9, which ships OpenJDK 17 and 21 but **not**
Java 8. Java 8 also pinned every dependency to its final release with no
security fixes.

ZGC matters more than the language version. A 40 ms tick budget in the
surrounding backend cannot absorb a G1 pause, and ZGC's pauses are
sub-millisecond regardless of heap size. That removes the reason to keep heaps
small, though several processes are still run per machine for **crash
isolation**, which no collector helps with.

ZGC is the default in development too, deliberately. It turns compressed oops
off, which changes every object size, so a G1 development box and a ZGC
production box would disagree about when `maxmemory` is reached — exactly the
kind of difference that is discovered in production.

**Cost, measured.** With 8-byte references the same dataset estimates 25 % more
per key, 40 % per hash field or sorted-set member and 50 % per set member.
`maxmemory` must be raised by about a third on upgrade. Roughly 10 % of
throughput also goes to ZGC's barriers; for this workload that is a good trade
for the pause guarantee.

### D-32 — Header size follows compressed class pointers, not oops

**Decision.** `MemoryEstimator` detects `UseCompressedOops` and
`UseCompressedClassPointers` independently: the header is 12 bytes when class
pointers are compressed and 16 when they are not, while a reference field is 4
bytes when oops are compressed and 8 when they are not.

**Why.** The two were treated as one flag, which is true under G1 (both on or
both off) but false under ZGC, which disables compressed oops while keeping
compressed class pointers. The old code assumed a 16-byte header there and
over-estimated by 10–17 % depending on the type. Measured on Temurin 21:
allocating 5 M instances of `class C { int a; }` costs ~15.9 bytes under G1 and
~15.5 under ZGC — a 12-byte header in both cases.

It failed safe, refusing writes earlier than necessary rather than admitting
too much data, which is why 1.0.0 was not in danger. But an estimate that is
wrong in a knowable way is worth fixing.

### D-33 — A stream is held in immutable chunks, and encoded off the command thread

**Decision.** A stream keeps its entries in chunks of up to 256, views over
arrays that no holder ever sees change: appends go past the last chunk's end, a
delete or a cut replaces a chunk. A rewrite takes a stream as a frozen view (the
chunk list, the last chunk's length, a copy of the groups' pending lists) and
hands it to the base file's writer thread, in its place in the queue, to encode
there ([15 §4](15-streams.md#4-the-value-immutable-chunks)).

**Why.** Every other type is encoded on the command thread when a rewrite
reaches it or before its first change, which stalls every client for as long as
the key takes: 20–30 ms per 100 000 sorted-set members, measured (08 §6). The
backend's result stream holds millions of entries and is written to all the
time: a `TreeMap` of entries would have paid seconds at every rewrite. Chunks
are also what Redis does (listpacks in a radix tree), and they cost less memory
than a tree node per entry.

**Cost.** More code than a `TreeMap`: a delete copies up to 256 entries' worth
of references, the lookup is a search over chunks and then within one, and the
writer thread gains a second kind of item to write. The model-based test holds
the chunks to a plain ordered map's answers.

### D-34 — Stream deliveries are logged as `XCLAIM`, with their time

**Decision.** An `XREADGROUP` or `XAUTOCLAIM` that delivers entries logs, for
each, `XCLAIM key group consumer 0 id TIME <ms> RETRYCOUNT <n> FORCE JUSTID
LASTID <last>`; several go in one `MULTI`. `XADD *` logs the ID it gave, and a
group created at `$` the ID `$` meant.

**Why.** Replay runs with the clock frozen at the moment loading began, so a
command that reads the time must log what it read, or every pending entry would
come back delivered "now" and `XAUTOCLAIM`'s idle test would be wrong after
every restart. `XCLAIM` with those options says exactly what a delivery did, in
Redis's own vocabulary, which is also what Redis propagates to its replicas and
its AOF. No private effect command is needed.

**Cost.** One effect per entry delivered, a hundred or so bytes each: at the
backend's design rate, about 20 KB a second more in the AOF. A consumer's seen
time is not logged, so after a restart `XINFO CONSUMERS` counts idle time from
the restart; a base keeps it exactly.

**Refined while building (2026-09-29).** A read logs the group's new last ID
once, as `XGROUP SETID`, rather than as `LASTID` on each `XCLAIM`: one form
covers a `NOACK` read too, which logs no `XCLAIM`. A consumer a command makes
is logged as `XGROUP CREATECONSUMER`, and a pending entry dropped because its
stream entry is gone as `XACK`.

## Decisions from designing replication (2026-09-29)

### D-35 — Replication sends the AOF's effects, and the replica pulls

**Decision.** The stream a primary sends its replicas is the AOF's effect
records, the same bytes, encoded once per batch into an immutable chunk that the
AOF writer, the backlog and every replica's connection share. A replica opens
the connection, asks with `PSYNC`, and applies what arrives through the command
handlers, as the loader replays a file. Offsets count the stream's bytes
([16 §4](16-replication.md#4-the-stream-of-changes)).

**Why.** The effects were made deterministic so that a file replayed hours later
means what it meant (08 §3): `SPOP` as the `SREM` of what it took, times made
absolute, deliveries with their time (D-34). That is exactly the property a
replica needs, and it already holds for every command, with the model-based and
crash tests behind it. A second encoding for replication would be a second
thing to keep right. Pulling, as Redis does, means the primary needs no
configuration to have a replica, and a replica decides when to reconnect.

**Cost.** Effects are now encoded even with `appendonly no`, once a replica has
asked for the backlog. Measured on 2026-09-29 on the loaded development VM,
300,000 small `SET`s, three rounds each: with the AOF off, the backlog raised
the command thread's CPU time from about 660 to 910 ms, some 0.8 µs a command,
a third more, which is the encoding an AOF server already pays; with the AOF on,
no difference beyond the noise (about 1,030 against 930 ms). Two records travel on the link that are not
effects (`PING`, `REPLCONF GETACK`), so the link's offset is not the AOF's
length, and nothing may assume it is.

### D-36 — A full sync is a snapshot sent to the link, landed on the replica's disk and adopted as its base

**Decision.** For a full sync the primary takes the fork-free snapshot a rewrite
takes and sends its records to the replica's connection, framed by an end mark;
the effects after its instant wait in the backlog until the image is sent.
The replica writes the image to its data directory, checks its CRC, makes it the
base of a new generation of its own files, then loads it
([16 §5](16-replication.md#5-the-full-sync)).

**Why.** The snapshot is already a consistent image taken while commands keep
running, paced by its consumer, with streams encoded off the command thread
(D-33): the primary gains a destination, not a mechanism. Sending it straight
to the connection keeps the primary's disk out of it. Landing it on the
replica's disk, rather than in memory, means a replica never holds two datasets
at once, and adopting the file as its base means the replica's own files are
right the moment the sync is, with no rewrite after it.

**Cost.** One snapshot at a time: a replica that asks during a rewrite waits for
it, and a rewrite waits for a sync. The replica's command thread blocks while it
loads, as at start-up. A write rate that outruns the transfer makes the backlog
lose the image's instant, and the sync starts again; the backlog's size is what
an operator raises. Snapshots are numbered by the dataset, not the AOF's
generation, since a sync takes one without starting a generation.

### D-37 — Epochs fence promotions; the store does not elect

**Decision.** Every server keeps an epoch in its data directory. `REPLICAOF NO
ONE` increments it, and persists it, before accepting a write. A replica
refuses to sync from a primary whose epoch is lower than its own, and takes its
primary's epoch at each sync. The client library remembers the highest epoch it
has seen and never uses a primary with a lower one
([16 §9](16-replication.md#9-promotion-and-epochs)).

**Why.** Promotion is scripted and human-triggered (the backend's D-8, which
explains why nothing elects automatically). The dangerous mistakes are then
procedural: the promoted server pointed back at the old primary, or restarted
with a configuration file that still names it, which a full sync would answer by
replacing the newer data with the older; and an old primary that comes back
still believing it is one. A number that only promotions raise turns the first
into a refusal and the second into a server nobody writes to. It is the
"replication epoch" the backend's availability design asked for.

**Cost.** A small file beside the manifest, written and synced on every
promotion. It cannot stop two primaries whose clients cannot see each other;
fencing the old primary stays a step of the procedure, and
`min-replicas-to-write` bounds the damage if it is missed. With `appendonly no`
the epoch lives only as long as the process.

### D-38 — Replicas are read-only, unchained, and never expire keys themselves

**Decision.** A replica refuses every write from a client (`-READONLY`); only
its link writes. It has no replicas of its own. It hides a key whose time has
passed from reads but deletes nothing until the primary's `DEL` arrives, and
runs no active expiry. The role at start-up is the configuration file's
([16 §6](16-replication.md#6-applying-the-stream-on-a-replica)).

**Why.** A write accepted by a replica is lost at its next sync, silently: there
is no case in the backend for it. One replica per store is the backend's
topology, so chains would be code for nothing. Deleting on the replica's own
clock would make its data depend on two clocks, and the effects it writes to its
AOF would stop being the primary's. The configuration decides the role because
there is no `CONFIG REWRITE`; D-37 makes a stale file fail safe.

**Cost.** Reads on a replica may briefly see a key that has expired on the
primary but whose `DEL` has not arrived, hidden only on lookup: `SCAN` can list
it. A promotion must also edit the configuration file.

## Decisions from the backend's second audit (2026-10-01)

### D-39 — A demoted server closes its listeners, and a subscriber pings

**Decision.** `REPLICAOF host port` closes, besides its replicas' sessions, the
connections no `-READONLY` will reach: every subscriber's, and every client's
blocked waiting for data. The client sends `PING` on a subscriber's connection
every `pubsubPingMillis` (5 s by default) as an ordinary request, so a
subscriber's connection that answers nothing is closed by the rule that
already closes a stuck command connection, and the primary looked for again
([16 §10](16-replication.md#10-clients-following-the-primary)).

**Why.** A client looks for the primary when a connection drops or a write is
answered `-READONLY`, and a subscriber's connection has neither: it is sent no
replies, and it writes nothing. After a planned handover every subscriber
stayed on the demoted server, where nothing published on the new primary
arrives, until its process was restarted; after a lost machine, until TCP
keepalive gave up. The backend's pushes, its notices and its operators' kicks
all travel that way, and none of them arrived (backend defect O-6). A client
blocked on the demoted server waits for data that is now written elsewhere, or
worse, is served by a replicated write; Redis closes its blocked clients on
demotion for that reason. For subscribers Redis replicates `PUBLISH`; here
pub/sub does not cross to a replica (16 §13), and closing the connection makes
the subscriber find the primary by the one path that is already tested. Other
clients keep their connections: their first write is told `-READONLY`, as
before, and closing them too broke every test that makes a fresh server a
replica and goes on using its client. A periodic `PING` is the only way a
connection that sends nothing can learn it is dead.

**Cost.** A reconnect for each subscriber and blocked reader of the demoted
server, an operator's step done once. What is published between the demotion
and a subscriber's reconnect is lost, as on any drop. One `PING` every five
seconds per subscriber's connection.

### D-40 — A connection found under an older epoch does not stay

**Decision.** A connection records the highest epoch its client had seen when it
found its primary. When one connection's search raises that epoch, every other
connection of the client with a lower one closes and looks for the primary
again, at once; and the pool of leased connections, those `WATCH` needs, closes
an idle lease with a lower epoch rather than lend it
([16 §10](16-replication.md#10-clients-following-the-primary)).

**Why.** A connection learns of a handover when it is dropped or a write is
told `-READONLY`, and that write is lost: its caller gets the error. After a
planned handover the demoted server closed the backend gateway's subscriber
(D-39), which found the new primary at once, and three seconds later the
gateway's command connection, still on the demoted server, had its first writes
refused: two players' lobby registrations, so that the matcher dropped them as
having no lobby. A lease waits in its pool and hears nothing: eight seconds after
`platform`'s command connection had found the new primary, its idle leases were
refused at `EXEC` (`EXECABORT`), and two queue joins failed. The epoch is what
every promotion raises and every server reports, so a connection that finds a
higher one says, without a round trip, that every connection found under a lower
one is on a server no longer the newest primary.

**Cost.** One reconnect for each of the client's connections after a promotion
it learns of, and the requests in flight on them failed, as a drop fails them.
The first connection to learn still learns by a drop or a `-READONLY`; what it
was sending is lost, as before.
