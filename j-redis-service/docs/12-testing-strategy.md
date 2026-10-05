# 12 — Testing strategy

## 1. The oracle problem

The usual way to test a Redis-compatible server is to run the same commands
against real Redis and compare. That is ruled out: no third-party Redis
software is used anywhere, including tests (C-3, D-14).

So correctness rests on two things:

- **the Redis 7.2 command documentation** as the written specification
  ([01 §7](01-requirements-and-scope.md#7-compatibility-target));
- **our own reference model** — a deliberately naive, obviously-correct
  implementation of the same semantics — as the executable oracle (§4).

Everything else in this document either feeds the model comparison or tests
what the model cannot: persistence, concurrency, performance.

## 2. Layers

| Layer | What | Runs in | When |
|---|---|---|---|
| Unit | Each command's documented behaviour and edge cases | embedded mode | every build |
| Property | `Dict`, `SkipList`, `ByteArrayRing`, glob matcher, number parsing, RESP codec | in-process | every build |
| Model-based | Random command streams vs the reference model | embedded mode | every build (1M ops); nightly (100M) |
| Persistence | Replay determinism, snapshot consistency, crash, truncation, fault injection | child JVMs | full build |
| Concurrency | Ordering, fairness, backpressure, client library behaviour | TCP | full build |
| Performance | JMH microbenchmarks, `j-redis-benchmark` | production box | M3, M7, before releases |
| Soak | 24 h mixed load with rewrites | production box | M7, before releases |

## 3. Unit tests

Written against `JRedisEmbedded` ([10 §10](10-client-library.md#10-embedded-mode))
with a manual clock, in a compact style:

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

Each command gets tests for its documented edge cases — negative indexes,
empty results, wrong type, overflow, infinities — **and** its AOF effect from
the table in [05](05-commands.md).

## 4. Model-based testing

### The reference model

`ModelStore` implements every supported command in the most straightforward way
possible: a `TreeMap<String, Object>` holding `HashMap`, `LinkedList`,
`HashSet`, and a `TreeSet` of (score, member) for sorted sets. No rehashing, no
skip list, no expiry buckets, no persistence — nothing clever enough to be
wrong in the same way as the server.

### The harness

1. A seeded generator produces random commands, weighted by family, over a
   **small key space** (about 20 keys) so collisions, type errors and
   overwrites happen constantly. Arguments include edge values: empty strings,
   negative indexes, `inf` scores, huge counts.
2. Each command runs against the embedded server and the model with the same
   manual clock; **replies must be equal**.
3. Every 1,000 commands the full state is compared (server via `SCAN` + type
   reads, versus the model).
4. On a mismatch the seed and command log are printed and a minimiser removes
   commands while the failure persists, leaving a short reproducer.

The model grows with each milestone (M2 strings/keys/expiry, M3 collections,
M5 transactions).

## 5. Property tests

| Subject | Properties |
|---|---|
| `Dict` | After random insert/delete with resizes forced at random points: size correct, every key findable, no duplicates |
| `SCAN` | Every key present for the whole scan is returned, while another actor adds and removes other keys and forces growth and shrink ([04 §3.5](04-data-structures.md#35-scan-the-reverse-binary-cursor)) |
| `SkipList` | `rank`, `byRank`, range queries equal those of a sorted `ArrayList`; level-0 spans sum to `length` |
| `ByteArrayRing` | Equals an `ArrayList` under random operations, including growth and shrink |
| Glob matcher | Equals a regex translation on random inputs; `*a*a*a*a*b` against 10,000 `a`s finishes in < 50 ms |
| Numbers | Parse/format round trip; the reject lists in [03 §7](03-protocol.md#7-number-formatting) are rejected |
| RESP codec | Round trip; decoding the same bytes fed one byte at a time gives the same result |

## 6. Protocol robustness

- Fuzz the decoder with random and mutated byte streams: nothing escapes the
  handler; the server answers `-ERR Protocol error` and closes the connection;
  other clients are unaffected.
- Every limit in [03 §4](03-protocol.md#limits) is exceeded once and rejected.

## 7. Persistence

| Test | Method | Pass criterion |
|---|---|---|
| Replay determinism | Random workload; then load the AOF into a fresh instance | State identical to the live one |
| Snapshot consistency | Workload rich in multi-key commands (`RENAME`, `LMOVE`, `SMOVE`, `SINTERSTORE`, `MULTI`) while a rewrite runs with tiny slices | Base file equals the model's state captured at T; base + incr equals the live state |
| Crash matrix | Server in a child JVM; driver records every acknowledged write; `kill -9` at random moments, including inside each rewrite step (via test-only pause points); restart | Recovered state equals the model after **some prefix** of the commands, and that prefix includes every write acknowledged more than 2 s before the kill (NFR-4, NFR-5) |
| Truncation | Truncate the last incr file at every byte offset within its last records | Starts with a WARN; state is a prefix |
| Corruption | Flip bytes in the middle of an incr or base file | Refuses to start, exit code 3 |
| Fault injection | A test `FileChannel` wrapper fails `write` or `force` on demand | `write` failure → `-MISCONF`, recovers when writes succeed; `force` failure → exit 4 |
| Load time | 1 GB of data | ≤ 30 s (NFR-6) |

## 8. Concurrency and the client library

- Many clients pipelining in parallel: each client's replies are in its own
  request order.
- Blocking: FIFO fairness across waiters; timeout accuracy within ±20 ms;
  disconnect while blocked cleans up.
- `WATCH`: invalidated by writes from other clients, by expiry, by `FLUSHALL`.
- Pub/Sub: per-subscriber ordering; a subscriber that stops reading is
  disconnected at the limit.
- Backpressure: a client that sends a million commands without reading its
  replies does not grow server memory without bound.
- Client library: server restart under load → every in-flight request fails
  with `JRedisConnectionException`, none completes twice, the client reconnects
  and resubscribes; concurrent senders never land inside another thread's
  transaction; sync facade guards throw on event-loop and marked threads.
- **The same client test suite runs twice: embedded and over TCP.**

## 9. Performance

| Benchmark | Target |
|---|---|
| JMH: `Dict` get/put, SipHash, RESP decode/encode, skip-list insert/rank | Tracked for regressions |
| JMH: `J.ZAROUND` on 1M members | p99 < 50 µs |
| `j-redis-benchmark -c 50 -t get,set -d 32` | ≥ 100k ops/s, p99 ≤ 1 ms (NFR-1, NFR-2) |
| `j-redis-benchmark -c 50 -P 64 -t get,set` | ≥ 500k ops/s |
| Grow the keyspace to 10M keys under load | No command > 1 ms during rehash |
| Rewrite of 10M keys under load | p99.9 ≤ 2 ms, big keys aside |
| GC log over 1 h at target load, 8 GB heap | No pause > 50 ms (NFR-7) |

## 10. Soak

24 hours at about 20k ops/s of realistic backend traffic (the recipes in
[09 §3](09-integration-patterns.md#3-recipes)) with automatic rewrites. Pass:
heap flat after warm-up, p99 stable, no restarts, no Netty buffer leaks (run
the first hour with `-Dio.netty.leakDetection.level=paranoid`).

## 11. Continuous integration

Offline, using the bundle:

```bash
mvn -o -Dmaven.repo.local=/opt/java21-offline/repository verify            # fast tier
mvn -o -Dmaven.repo.local=/opt/java21-offline/repository verify -Pfull     # + crash, fuzz
```

| Tier | Contents | Budget |
|---|---|---|
| fast | unit, property, model 1M ops | < 5 min |
| full | + persistence matrix, fuzzing, concurrency, model 10M ops | < 45 min |
| nightly | + model 100M ops, 1 h soak-lite | overnight |

Run the fast tier on both Linux and Windows.

## 12. What is implemented (2026-09-21)

| Suite | Module / class | Covers |
|---|---|---|
| Codec and number property tests | `j-redis-common`: `RespParsersTest`, `NumberCodecTest`, `GlobTest`, `ArgSplitterTest` | §5: RESP byte-at-a-time decoding, number reject lists, glob against pathological patterns |
| Structure property tests | `j-redis-server`: `DictTest`, `SkipListTest`, `SipHashTest`, `ListValueTest` | §5: `Dict` against a `HashSet` with forced resizes; the **SCAN guarantee** while the table grows and shrinks; skip list against a sorted list (ranks, ranges, spans, 100k members); SipHash-2-4 reference vectors; ring-buffer list against `ArrayList` |
| Command tests | `j-redis-tests`: `StringKeyCommandsTest`, `CollectionCommandsTest` | §3 on a manual clock: options, edge cases, errors, TTLs, `J.ZAROUND`/`J.CAS`/`J.CAD`, a 120k-member leaderboard with exact ranks |
| Model-based | `ModelBasedTest` + `Model` | §4: 3 seeds × 100,000 commands, pipelined in random batches, clock jumps between batches, full keyspace compared every 500 commands |
| Protocol | `ProtocolTest` (real TCP, raw socket) | §6 and §8: inline commands, 20k pipelined, protocol errors close only that connection, limits, `MULTI`/`EXEC`/`WATCH`, FIFO blocking, pub/sub order, `AUTH`, `maxclients` |
| Persistence | `PersistenceTest` | §7: restart equivalence; the **base file equals the state at the moment BGREWRITEAOF ran** while writes continue; torn tail at 130 offsets; corrupt base and incr refuse to start; `DEBUG RELOAD`, `SAVE`; TTLs across restart; the data-directory lock |
| Client library | `ClientLibraryTest` (embedded **and** TCP) | §8: per-thread order with several connections, transactions from 8 threads never interleave, late replies of timed-out requests dropped, `WATCH` on leased connections, blocking and pub/sub connections, guards, reconnect and resubscribe after a server restart, wrong password |
| Review regressions | `ServerRegressionTest`, `CommandRegressionTest`, `ClientRegressionTest`, and additions to `PersistenceTest` and the server unit tests | One test per defect found by the review (D-26…D-30): killed or blocked clients, protocol errors, half-close, EXEC under OOM, WATCH on expired keys, CLIENT KILL filters, CONFIG SET atomicity, INCRBYFLOAT, SPOP batching, replay of disabled commands and of APPEND under a lowered limit, a dying base writer, BGSAVE SCHEDULE, the JVM-wide lock, the client timeout checker, close from a callback, lost WATCH, the blocking queue after a connection loss |
| Examples | `j-redis-examples`: `ExamplesTest`, `EmbeddedTestingExampleTest` | Every documented example runs, twice, against an embedded server |
| Crash (`@Tag("slow")`, `-Pfull`) | `CrashTest` | §7 crash matrix, reduced: a child-JVM server killed with `kill -9` five times under a transactional workload, rewrites included; prefix and "acknowledged more than 2 s before the kill" checked after each restart |

Not yet implemented: the fault-injection `FileChannel` (MISCONF, fsync
failure), decoder fuzzing, the 1 GB load-time test, JMH (D-25), and the soak.
They need either a test seam in `AofWriter` or the production box.
