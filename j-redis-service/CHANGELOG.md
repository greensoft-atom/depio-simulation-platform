# Changelog

All notable changes. Versions follow `MAJOR.MINOR.PATCH`
([docs/guide/07-upgrade-guide.md](docs/guide/07-upgrade-guide.md#71-versions)).
A change to a data format is always marked **Format**.

## 2.2.1 — 2026-10-01

A PATCH release: every connection of a client now follows the primary: a
subscriber, which a demoted server closes and the client pings, and any
connection told nothing, once another of the client's has found the new primary
([docs/16 §10](docs/16-replication.md#10-clients-following-the-primary), D-39,
D-40). Nothing changes in a data format or a command's reply.

### Fixed

- A subscriber stayed on a demoted primary, and heard nothing published on the
  new one, until its process restarted: its connection is sent no replies, so
  it never saw `-READONLY`, and `REPLICAOF` closed only the replicas' sessions.
  A server made a replica now also closes every subscriber's connection, and
  every client's blocked waiting for data, which could otherwise be served by a
  replicated write; each looks for the primary again (backend defect O-6).
- A subscriber whose primary's machine was lost waited for TCP keepalive,
  about two hours, since it sent nothing that could time out. The client now
  pings a subscriber's connection every `pubsubPingMillis` (5 s), and one
  unanswered for three command timeouts is closed as a stuck command connection
  is: noticed within about 11 s at the defaults.
- A connection that had been told nothing stayed on a demoted primary after
  another connection of the same client had found the new one, and its next
  writes were refused and lost: a command connection's, and an idle leased
  connection's, the kind `WATCH` needs (`EXECABORT`). A connection whose search
  for the primary raises the epoch its client has seen now closes the client's
  other connections found under a lower one, which look again at once; and an
  idle lease with a lower epoch is closed rather than lent (D-40).

### Added

- `JRedisClient.Builder.pubsubPingMillis(long)`; 0 turns the pings off.

## 2.2.0 — 2026-09-29

Adds **replication** ([docs/16](docs/16-replication.md)), which the backend's
stores need to survive the loss of a machine: a primary and its replicas, a
full sync that never pauses the primary, the stream of changes after it,
continuing after a short drop, `WAIT`, promotion by an operator guarded by
epochs, and a client that follows the primary. A MINOR release: 2.1 data and
clients keep working, and going back to 2.1 needs no backup (one new file,
ignored by 2.1).

### Added

- `DEBUG DIGEST`: a digest of the whole dataset, equal for equal data whatever
  its order in memory, for tests and for comparing a replica with its primary.
- **Replication, the full sync and the stream** ([docs/16](docs/16-replication.md)
  §5, §6): `REPLICAOF host port` and `replicaof` in the configuration,
  `REPLICAOF NO ONE`, `ROLE`, `INFO replication`, the link's `PSYNC` and
  `REPLCONF`, `primaryauth`, `repl-backlog-size`, and the `replica` class of
  `client-output-buffer-limit`. A primary sends a snapshot's records straight to
  the replica's connection, then the effects from the backlog; a replica lands
  the image on disk, makes it its base, loads it, applies the stream through its
  own AOF, refuses writes (`READONLY`) and deletes no key on its own clock.
  Replicas show `S` in `CLIENT LIST`.
- **Continuing after a drop, acknowledgements, `WAIT`** (docs/16 §7, §8): a
  replica that loses its link asks to continue its history, and the primary
  answers `+CONTINUE` from the backlog when it still holds it. Replicas
  acknowledge every second and when asked (`REPLCONF ACK`, `GETACK`);
  `WAIT numreplicas timeout`; `PING`s on a quiet link; `repl-timeout` drops a
  silent link on either side; `repl-ping-replica-period`.
- **The client follows the primary** (docs/16 §10): `addresses("host:port", …)`
  asks each server `ROLE` and uses the primary with the highest epoch, looks
  again after a lost connection or a `READONLY`, and never uses a primary below
  the highest epoch it has seen. With one address, as before.
- **Epochs and promotion** (docs/16 §9, D-37): `REPLICAOF NO ONE` raises the
  epoch and records it in `<dir>/replication` before accepting a write; a
  replica sends its epoch in the handshake and an older primary refuses it
  (`-EPOCH`), leaving its data as it was; a replica takes its primary's epoch at
  each sync. `min-replicas-to-write` and `min-replicas-max-lag` make a primary
  refuse writes (`-NOREPLICAS`) without enough replicas that acknowledged in
  time.
- Snapshots are numbered by the dataset, no longer by the AOF generation: a
  sync takes one without starting a generation.
- The effects of each batch are encoded once, whether or not the AOF is on, and
  handed to the AOF and to a replication backlog, a ring of the stream's recent
  bytes with its offset (D-35). No replica uses it yet; the AOF's bytes are
  unchanged. With the AOF off and a backlog wanted, the encoding costs the
  command thread about 0.8 µs a small command (measured, D-35).

### Fixed

- A delivery replayed from the AOF or a primary's stream keeps its logged time.
  `XCLAIM`'s cap of a future `TIME` at the server's clock applied to it too, so
  a replica whose clock ran behind recorded its own time, and its pending
  entries differed from the primary's (backend defect D-28). `XPENDING` without
  `IDLE` no longer filters on idle time, as in Redis: it hid a delivery ahead
  of the clock.
- `DEBUG DIGEST-VALUE key…`: one key's digest, to find the key where a replica
  and its primary differ.
- A test now holds the fail-stop rule that a command failing half-way leaves
  nothing in the AOF; no test had since 1.0 (backend defect T-18).

## 2.1.0 — 2026-09-29

Adds **streams** ([docs/15](docs/15-streams.md)), which the backend's result
pipeline needs: the type, consumer groups and blocking reads, in the server and
the client. A MINOR release: 2.0 clients and data keep working, but the data
format moves on (below), so going back to 2.0 needs a backup.

### Added

- The stream type (`TYPE` `stream`) and `XADD`, `XLEN`, `XRANGE`, `XREVRANGE`,
  `XDEL`, `XTRIM`, as Redis 7 has them: IDs from the clock that never go back,
  `ms-*`, `NOMKSTREAM`, `MAXLEN`/`MINID` trims with `=`, `~` and `LIMIT`
  (approximate trims are exact here). `COPY` and `SCAN TYPE` take streams.
  Held in immutable chunks of up to 256 (D-33).
- **Format**: base format version 2, with a stream record. A version 1 base
  loads as before; a 2.0 server refuses a version 2 base, so there is no going
  back to 2.0 once 2.1 has rewritten a data directory. A stream is encoded by
  the writer thread from a frozen view: 300,000 entries held the command thread
  0 ms in a rewrite, where a sorted set of as many held it 232 ms.
- `XADD *` is logged with the ID the clock gave.
- Consumer groups: `XGROUP` (`CREATE [MKSTREAM]`, `SETID`, `DESTROY`,
  `CREATECONSUMER`, `DELCONSUMER`), `XREADGROUP`, `XACK`,
  `XPENDING`, `XCLAIM`, `XAUTOCLAIM`, `XINFO STREAM|GROUPS|CONSUMERS` with an
  exact `lag`. Deliveries are logged as `XCLAIM` with their time and count
  (D-34), and groups are kept in a base.
- `XREAD`, and `BLOCK` for `XREAD` and `XREADGROUP`: a reader is served only
  by an entry after its position; a group reader whose stream is deleted or
  group destroyed is told at once, as in Redis 7.
- Client: typed stream methods (`xadd` with `XAddArgs`, `xrange`,
  `xreadgroup`, `xack`, `xpending`, `xclaim`, `xautoclaim`, `xinfoGroups`, …),
  their answers as the records of `Streams`; `client.blocking().xread` and
  `.xreadgroup`; `XREAD`/`XREADGROUP` with `BLOCK` refused on the shared
  connection. The CLI's `--bigkeys` counts a stream's entries.

## 2.0.0 — 2026-09-23

Moves the service to **Java 21**. No data format change: 2.0.0 reads a 1.0.0
data directory as it is, and 1.0.0 clients keep working. It is a MAJOR release
because 1.0.0's Java 8 runtime cannot execute it.

### Breaking

- **Requires JDK 21 or newer** (was Java 8). On RHEL 9:
  `dnf install java-21-openjdk-devel`. Java 8 is not packaged in RHEL 9.
- **The systemd unit from 1.0.0 will not start a JDK 21 JVM.** It passes
  `-Xloggc`, `-XX:+PrintGCDetails`, `-XX:+PrintGCDateStamps` and
  `-XX:+UseGCLogFileRotation`, all removed in JDK 9+; the JVM exits with
  `Unrecognized VM option 'PrintGCDateStamps'`. Take the new
  `systemd/j-redis.service`, which uses ZGC and `-Xlog:gc*`.
- **`maxmemory` needs raising by about a third.** The default GC is now
  generational ZGC, which turns compressed oops off, so every reference in a
  stored object grows from 4 to 8 bytes. The *same* dataset now estimates
  larger: **+25 % per key, +40 % per hash field or sorted-set member, +50 % per
  set member**. A dataset that estimated 3 GB under Java 8 estimates roughly
  3.8–4.2 GB here. Raise `maxmemory` accordingly or writes will start failing
  with `-OOM` on a dataset that used to fit ([docs/06](docs/06-expiry-and-memory.md)).

### Changed

- Build targets `maven.compiler.release 21` instead of `source`/`target 1.8`;
  `release` checks against the real Java 21 API.
- Launchers and the systemd unit pass `--add-opens java.base/java.nio=ALL-UNNAMED`.
  Without it Netty logs `direct buffer constructor: unavailable` and falls back
  to a slower path. It sits outside `JREDIS_JAVA_OPTS` so overriding the JVM
  options cannot drop it.
- Default JVM options are `-XX:+UseZGC -XX:+ZGenerational`, in development as
  well as production, so the memory estimate behaves the same in both.
- Dependencies are unchanged (Netty 4.1.122, JCTools 3.3.0, SLF4J 2.0.17,
  Logback 1.3.15, JUnit 5.14.4), verified to compile and pass on JDK 21.
- Builds offline from the new `java21-offline` bundle.

### Fixed

- **`MemoryEstimator` over-counted every object under ZGC.** Header size was
  derived from `UseCompressedOops`, but the header is 8 bytes of mark word plus
  the *class* pointer, which ZGC keeps compressed. ZGC therefore has a 12-byte
  header with 8-byte references, a combination the old code could not express:
  it assumed 16 bytes and over-estimated by 10–17 % depending on the type.
  `maxmemory` failed safe — writes were refused early rather than late — but
  the number was wrong. The two flags are now detected independently.
- `Thread.getId()`, deprecated in JDK 19, replaced with `threadId()`, restoring
  the zero-compiler-warning build.
- The `watch` example (`OptimisticLocking`) gave up after 10 retries, which four
  threads contending on two keys exhaust routinely. The exception escaped its
  thread as an uncaught stack trace, and the summary then reported "after 80
  concurrent transfers" when fewer had run. The retry budget is now 200, and a
  thread that still gives up is counted and reported instead of crashing. The
  money invariant always held; only the output was wrong and noisy. Pre-existing
  in 1.0.0, found while verifying this release.

## 1.0.0 — 2026-09-21

First release.

### Server
- RESP2 server on Netty 4.1 (epoll on Linux, NIO elsewhere), default port
  6379. One command thread owns all data, and backpressure is applied per
  connection.
- 151 commands: strings, keys and TTLs, hashes, lists, sets, sorted sets,
  pub/sub, blocking pops, `MULTI`/`EXEC`/`WATCH`, server administration, and
  the extensions `J.ZAROUND`, `J.CAS` and `J.CAD` ([docs/05](docs/05-commands.md)).
- Persistence: a multi-part append-only file (manifest + base + incr) with
  fork-free, per-key copy-on-write rewrites. `appendfsync everysec` with a
  configurable interval (`appendfsync-interval-millis`).
- **Format:** manifest `format 1`; base file `JRDB` version 1; AOF records are
  RESP commands.
- Operations: `INFO`, `SLOWLOG`, `CONFIG GET/SET`, `CLIENT`, protected mode,
  `requirepass`, `disable-command`, `maxmemory` (no eviction), `maxclients`,
  idle `timeout`, and exit codes for systemd.

### Client and tools
- `j-redis-client`: an async API with automatic pipelining, a blocking facade,
  transactions, leased connections for `WATCH`, pub/sub with resubscription,
  blocking pops, reconnects with backoff, timeouts, and metrics.
- `j-redis-embedded` (the real server in-process, for tests), `j-redis-cli`,
  `j-redis-tools` (`benchmark`, `check-aof`, `dump`), and `j-redis-examples`
  (15 runnable, tested examples).
- A distribution for Linux and Windows (`scripts/make-dist.sh`,
  `scripts/make-dist.ps1`), sample configs and a systemd unit.

### Fixed before release (review of 2026-09-21)
An in-depth review of every area found and fixed, each with a regression test:

- **Data safety**
  - A server that could not restart after `FLUSHDB` when `FLUSHALL` was disabled.
  - A huge `SPOP` that made the AOF unloadable.
  - Replay dropping `APPEND`/`SETRANGE` data after `proto-max-bulk-len` was lowered.
  - A corrupt base that could be committed after a failure while encoding a key.
  - A rewrite stuck forever after a base-writer failure (`SAVE` hung the server).
  - A ROTATE failure that deleted the incr file the manifest names.
  - Mid-file AOF corruption after a failed truncate.
  - A failed `DEBUG RELOAD` that kept serving a partial dataset.
  - A data-directory lock lost to a second attempt in the same JVM.
- **Correctness**
  - A killed blocked client that could still be served; the element was lost.
  - A waiter of another type blocking the others on the same key.
  - `EXEC` running writes under OOM or MISCONF.
  - Read-only commands inside `EXEC` triggering a fail-stop.
  - `WATCH` on an already-expired key, and `FLUSHALL` aborting a `WATCH` on a missing key.
  - `CLIENT KILL` without a filter, or with `TYPE master`, killing everyone.
  - `CONFIG SET` applying half of its changes, and splitting values on spaces.
  - `INCRBYFLOAT` giving `0.30000000000000004`.
  - `RANDOMKEY` returning nil while live keys exist.
  - `LMOVE`/`SMOVE` error order, `SET`/`GETEX` repeated options, and a `SETRANGE` overflow.
- **Robustness**
  - Protocol errors reported repeatedly, and connections reset.
  - Replies lost for half-closed connections.
  - Length headers that wrapped around.
  - 4 MB allocated per connection for a 10-byte header.
  - The idle timeout cutting off slow transfers.
  - Unbounded error lines.
  - Deleted keys with a TTL kept in memory until their expiry.
  - Unkeyed hashing in the server's registries.
  - Idle CPU spinning, and a per-JVM shared clock.
- **Client**
  - A callback that could silently disable all later timeouts.
  - `close()` from a callback deadlocking.
  - A `WATCH` lost in a reconnect letting `EXEC` commit unchecked.
  - A second `start()` mixing up replies.
  - `close()` during a connect leaking a connection.
  - The blocking-pop queue wedging after a connection loss.
  - The benchmark hanging when the server went away.
  - `check-aof --fix` on a single file ignoring the lock.
