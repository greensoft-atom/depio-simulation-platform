# 12 — Testing strategy

This page explains how j-redis is tested, and why it is tested that way. It is
for engineers who write or review tests, and for anyone who wants to know how
far the server's behaviour has been checked. It covers the test oracle, the
layers of tests, unit, model-based, property, protocol, persistence,
concurrency, performance and soak tests, continuous integration, and, in §12,
the tests that exist today. Sections 1 to 11 describe the strategy; §12 says
what is built.

## 1. The oracle problem

A test **oracle** is what decides whether an answer is right. The usual way to
test a Redis-compatible server is to run the same commands against a real
Redis and compare. That is ruled out: no third-party Redis software is used
anywhere, tests included (C-3, D-14).

So correctness rests on two things:

- **the Redis 7.2 command documentation**, as the written specification
  ([01 §7](01-requirements-and-scope.md#7-compatibility-target));
- **j-redis's own reference model**, as the executable oracle (§4). The model
  is a deliberately simple implementation of the same semantics, simple enough
  to be obviously correct.

Everything else on this page either feeds the comparison with the model, or
tests what the model cannot: persistence, concurrency and performance.

## 2. Layers

| Layer | What | Runs in | When |
|---|---|---|---|
| Unit | Each command's documented behaviour and edge cases | Embedded mode | Every build |
| Property | `Dict`, `SkipList`, `ListValue` (the ring-buffer list, called `ByteArrayRing` in the first design), the glob matcher, number parsing, the RESP codec | In the test process | Every build |
| Model-based | Random command streams compared with the reference model | Embedded mode | Every build: 3 seeds × 100,000 commands (planned: 1M commands per build, 100M nightly) |
| Persistence | Replay determinism, snapshot consistency, crashes, truncation, fault injection | Child JVMs (Java Virtual Machines) | Full build |
| Concurrency | Ordering, fairness, backpressure, the client library's behaviour | TCP | Full build |
| Performance | JMH microbenchmarks (not built, D-25), `j-redis-tools benchmark` | The production machine | M3, M7, before releases |
| Soak | 24 h of mixed load with rewrites | The production machine | M7, before releases |

## 3. Unit tests

Unit tests run against `JRedisEmbedded`
([10 §10](10-client-library.md#10-embedded-mode)) with a manual clock. This
sketch shows the compact style the design asks for:

```java
@Test void setWithExpiryThenTtl() {
    exec("SET k v EX 10").isOk();
    clock.advanceSeconds(3);
    exec("TTL k").isInteger(7);
    clock.advanceSeconds(8);
    exec("GET k").isNull();
    aof().endsWith("SET k v PXAT " + (start + 10_000), "DEL k");   // effects, 08 §3
}
```

Each command gets tests for its documented edge cases (negative indexes, empty
results, wrong type, overflow, infinities) **and** for its AOF (append-only
file) effect, from the table in [05](05-commands.md).

## 4. Model-based testing

### The reference model

The reference model (`Model`, in `j-redis-tests`) implements every supported
command in the most straightforward way possible. It keeps the keys in a
`TreeMap`, uses plain Java collections for the values, sorts on demand, and
expires keys lazily when they are read. It has no rehashing, no skip list, no
expiry buckets and no persistence: nothing clever enough to be wrong in the
same way as the server. The first design called it `ModelStore`.

### The harness

`ModelBasedTest` runs these steps:

1. A generator with a fixed seed produces random commands, weighted by family.
   It uses a **small key space**, 8 keys (`k0` to `k7`), so that collisions,
   type errors and overwrites happen all the time. The arguments include edge
   values: empty strings, negative indexes, `inf` scores, huge counts.
2. Each command runs against the embedded server and against the model, with
   the same manual clock. The commands are pipelined in random batches, and the
   clock jumps between batches. **The replies must be equal.**
3. Every 500 commands, the test compares the full state: the server's (read
   with `SCAN` and type-specific reads) against the model's.
4. On a mismatch, the test fails with the seed, the command's number, the
   command, and the two replies.

It runs 3 seeds × 100,000 commands; `-Djredis.model.ops=N` changes the number
per seed. The design also has a minimiser, which would remove commands while
the failure persists and leave a short reproducer. It is not built.

The model grew with each milestone: strings, keys and expiry in M2, the
collections in M3, transactions in M5, and streams in 2.1.

## 5. Property tests

A **property test** checks that a rule holds for many random inputs.

| Subject | Properties |
|---|---|
| `Dict` | After random inserts and deletes, with resizes forced at random points: the size is correct, every key can be found, and there are no duplicates |
| `SCAN` | Every key present for the whole scan is returned, while another actor adds and removes other keys and forces the table to grow and shrink ([04 §3.5](04-data-structures.md#35-scan-the-reverse-binary-cursor)) |
| `SkipList` | `rank`, `byRank` and range queries equal those of a sorted `ArrayList`; the spans at level 0 add up to `length` |
| `ListValue` (`ByteArrayRing` in the first design) | Equals an `ArrayList` under random operations, including growth and shrinking |
| Glob matcher | Equals a translation to a regular expression on random inputs; `*a*a*a*a*b` against 10,000 `a`s finishes in under 50 ms |
| Numbers | Parsing and formatting round-trip; the reject lists in [03 §7](03-protocol.md#7-number-formatting) are rejected |
| RESP codec | Round trip; decoding the same bytes fed one byte at a time gives the same result |

## 6. Protocol robustness

- Fuzz the decoder with random and mutated byte streams. Nothing may escape the
  handler: the server answers `-ERR Protocol error` and closes the connection,
  and other clients are not affected.
- Exceed every limit in [03 §4](03-protocol.md#limits) once, and check that the
  server rejects it.

## 7. Persistence

| Test | Method | Pass criterion |
|---|---|---|
| Replay determinism | Run a random workload, then load the AOF into a fresh instance | The state is identical to the live one |
| Snapshot consistency | Run a workload rich in multi-key commands (`RENAME`, `LMOVE`, `SMOVE`, `SINTERSTORE`, `MULTI`) while a rewrite runs with tiny slices | The base file equals the model's state captured at T; the base plus the incr file equals the live state |
| Crash matrix | Run the server in a child JVM. A driver records every acknowledged write. Kill the server with `kill -9` at random moments, including inside each rewrite step (through test-only pause points), and restart it | The recovered state equals the model after **some prefix** of the commands, and that prefix includes every write acknowledged more than 2 s before the kill (NFR-4, NFR-5) |
| Truncation | Cut the last incr file at every byte offset within its last records | The server starts with a WARN; the state is a prefix |
| Corruption | Flip bytes in the middle of an incr file or a base file | The server refuses to start, with exit code 3 |
| Fault injection | A test `FileChannel` wrapper fails `write` or `force` on demand | A `write` failure gives `-MISCONF`, and the server recovers when writes succeed again; a `force` failure gives exit code 4 |
| Load time | 1 GB of data | 30 s or less (NFR-6) |

## 8. Concurrency and the client library

- Many clients pipeline in parallel: each client's replies are in its own
  request order.
- Blocking: waiting clients are served first come, first served (FIFO); a
  timeout is accurate within ±20 ms; a client that disconnects while blocked
  is cleaned up.
- `WATCH`: writes from other clients, expiry and `FLUSHALL` invalidate it.
- Pub/sub: each subscriber gets messages in order; a subscriber that stops
  reading is disconnected at the limit.
- Backpressure: a client that sends a million commands without reading its
  replies does not make server memory grow without limit.
- The client library: when the server restarts under load, every request in
  flight fails with `JRedisConnectionException`, none completes twice, and the
  client reconnects and subscribes again. Concurrent senders never land inside
  another thread's transaction. The sync facade's guards throw on event-loop
  threads and on marked threads.
- **The same client test suite runs twice: embedded and over TCP.**

## 9. Performance

These are the targets. They are measured on the production machine; none has
been measured there yet ([13](13-implementation-plan.md)).

| Benchmark | Target |
|---|---|
| JMH: `Dict` get and put, SipHash, RESP decoding and encoding, skip-list insert and rank | Tracked for regressions (JMH is not used yet, D-25) |
| JMH: `J.ZAROUND` on 1M members | p99 under 50 µs |
| `j-redis-tools benchmark -c 50 -t get,set -d 32` | At least 100,000 ops/s, p99 of 1 ms or less (NFR-1, NFR-2) |
| `j-redis-tools benchmark -c 50 -P 64 -t get,set` | At least 500,000 ops/s |
| Grow the keyspace to 10M keys under load | No command over 1 ms during a rehash |
| Rewrite of 10M keys under load | p99.9 of 2 ms or less, big keys aside |
| GC log over 1 h at the target load, 8 GB heap | No pause over 50 ms (NFR-7) |

## 10. Soak

A **soak test** runs for a long time to find slow leaks. The plan: 24 hours at
about 20,000 ops/s of realistic backend traffic (the recipes in
[09 §3](09-integration-patterns.md#3-recipes)), with automatic rewrites. It
passes if the heap stays flat after warm-up, the p99 is stable, nothing
restarts, and Netty leaks no buffers. Run the first hour with
`-Dio.netty.leakDetection.level=paranoid`. It has not been run yet.

## 11. Continuous integration

The build runs offline, from the `java21-offline` bundle. With the bundle used
as the local repository (method 2 of
[guide 2](guide/02-offline-repository.md)), the commands are:

```bash
mvn -o -Dmaven.repo.local=<path>/java21-offline/repository verify            # fast tier
mvn -o -Dmaven.repo.local=<path>/java21-offline/repository verify -Pfull     # + crash tests
```

Inside the backend's repository, `./dev-jredis.sh build` builds and tests
j-redis from the bundle instead ([guide 3](guide/03-build-and-test.md)).
Without `-Pfull`, the build skips the tests tagged `slow` (`CrashTest` and
`ReplicationCrashTest`).

The planned tiers:

| Tier | Contents | Budget |
|---|---|---|
| fast | Unit, property, model 1M ops | Under 5 min |
| full | Also the persistence matrix, fuzzing, concurrency, model 10M ops | Under 45 min |
| nightly | Also model 100M ops, a 1 h "soak-lite" | Overnight |

Run the fast tier on both Linux and Windows. (The Windows scripts have not been
checked on Windows yet; see the README.)

## 12. What is implemented (2026-09-21)

This table lists the test classes as they are in 2.2.1 (2026-10-01). The
section's title keeps its first date so that links to it keep working. In all,
229 tests pass with `-Pfull`: 21 in `j-redis-common`, 35 in `j-redis-server`,
1 in `j-redis-client`, 5 in `j-redis-examples` and 167 in `j-redis-tests`.

| Suite | Module / class | Covers |
|---|---|---|
| Codec and number property tests | `j-redis-common`: `RespParsersTest`, `NumberCodecTest`, `GlobTest`, `ArgSplitterTest` | §5: RESP decoding byte by byte, the number reject lists, the glob matcher against pathological patterns, the splitting of a line into arguments (used for inline commands, configuration lines and the CLI) |
| Structure property tests | `j-redis-server`: `DictTest`, `SkipListTest`, `SipHashTest`, `ListValueTest` | §5: `Dict` against a `HashSet` with forced resizes; the **SCAN guarantee** while the table grows and shrinks; the skip list against a sorted list (ranks, ranges, spans, 100,000 members); the SipHash-2-4 reference vectors (the server hashes with SipHash-1-2); the ring-buffer list against `ArrayList` |
| Server unit tests | `j-redis-server`: `DbExpiryTest`, `MemoryEstimatorTest`, `FailStopEffectsTest` | Expiry registrations (a deleted key with a long TTL is not kept reachable); the memory estimate's two independent JVM flags ([06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing)); a fail-stop keeps the effects of completed commands and drops those of the failing one |
| Stream and replication unit tests | `j-redis-server`: `StreamValueTest`, `BacklogTest`, `EndMarkTest`, `FullSyncInstantTest` | A stream's chunks ([15 §4](15-streams.md#4-the-value-immutable-chunks)); the backlog ring a replica continues from; the end of an image found wherever the reads split it; a full sync's instant falls between two chunks of effects ([16 §5.2](16-replication.md#52-the-primarys-side-a-snapshot-sent-to-the-link)) |
| Client unit test | `j-redis-client`: `XAddArgsTest` | The `XADD` trim options the client builds (`XAddArgs`) are sent as given |
| Command tests | `j-redis-tests`: `StringKeyCommandsTest`, `CollectionCommandsTest` | §3 on a manual clock: options, edge cases, errors, TTLs, `J.ZAROUND`/`J.CAS`/`J.CAD`, a leaderboard of 120,000 members with exact ranks |
| Stream tests (2.1) | `j-redis-tests`: `StreamCommandsTest`, `StreamGroupsTest`, `StreamBlockingTest`, `StreamClientTest` | Entries, IDs, ranges and trims; consumer groups (delivery, pending, claims); `XREAD`, and `XREAD` and `XREADGROUP` with `BLOCK`; the client library's stream methods ([15](15-streams.md)) |
| Model-based | `ModelBasedTest` + `Model` | §4: 3 seeds × 100,000 commands, pipelined in random batches, clock jumps between batches, the full keyspace compared every 500 commands; streams included |
| Protocol | `ProtocolTest` (real TCP, raw socket) | §6 and §8: inline commands, 20,000 pipelined commands, protocol errors close only that connection, limits, `MULTI`/`EXEC`/`WATCH`, FIFO blocking, pub/sub order, `AUTH`, `maxclients` |
| Persistence | `PersistenceTest` | §7: restart equivalence; the **base file equals the state at the moment `BGREWRITEAOF` ran** while writes continue; a torn end at 130 offsets; a corrupt base or incr file refuses to start; `DEBUG RELOAD`, `SAVE`; TTLs across a restart; the data-directory lock |
| Client library | `ClientLibraryTest` (embedded **and** TCP) | §8: per-thread order with several connections, transactions from 8 threads never interleave, late replies of timed-out requests are dropped, `WATCH` on leased connections, blocking and pub/sub connections, the guards, reconnecting and subscribing again after a server restart, a wrong password |
| Replication (2.2) | `ReplicationTest`, `EffectStreamTest`, `DebugDigestTest`, `ClientFailoverTest` | Two real servers over TCP: the full sync, the stream after it and the replica's rules; the replication stream is the AOF's effects, the same bytes, with or without the AOF; `DEBUG DIGEST` is the same for the same data whatever its order in memory; a client given several addresses uses the primary with the highest epoch, follows a promotion on its own, and never goes back to a lower epoch ([16](16-replication.md)) |
| Review regressions | `ServerRegressionTest`, `CommandRegressionTest`, `ClientRegressionTest`, and additions to `PersistenceTest` and the server unit tests | One test per defect that the reviews found (D-26…D-30): killed or blocked clients, protocol errors, half-close, `EXEC` under OOM, `WATCH` on expired keys, `CLIENT KILL` filters, `CONFIG SET` atomicity, `INCRBYFLOAT`, `SPOP` batching, replay of disabled commands and of `APPEND` under a lowered limit, a dying base writer, `BGSAVE SCHEDULE`, the JVM-wide lock, the client's timeout checker, a close from a callback, a lost `WATCH`, the blocking queue after a connection loss |
| Examples | `j-redis-examples`: `ExamplesTest`, `EmbeddedTestingExampleTest` | Every documented example runs, twice, against an embedded server; how to test your own code with the embedded server |
| Crash (`@Tag("slow")`, `-Pfull`) | `CrashTest` | §7's crash matrix, reduced: a server in a child JVM killed with `kill -9` five times under a transactional workload, rewrites included; the prefix and "acknowledged more than 2 s before the kill" checked after each restart |
| Replication crash (`@Tag("slow")`, `-Pfull`, 2.2) | `ReplicationCrashTest` | A primary process killed with `kill -9` under a workload, and its replica promoted: every write that a `WAIT` confirmed is there, and a client given both addresses finds the new primary on its own ([16 §14](16-replication.md#14-parts-and-how-each-is-proved), part (f)) |

`j-redis-tests` also holds helpers that are not tests: `EmbeddedTest`,
`TcpServer`, `RawClient`, `R`, `StateDump`, `BaseFileDump`, `SilentLink` and
`Workload`.

**Not yet implemented:** the fault-injection `FileChannel` (`MISCONF`, an fsync
failure), decoder fuzzing, the 1 GB load-time test, JMH (D-25), the minimiser
(§4) and the soak. They need either a test seam in `AofWriter` or the
production machine.
