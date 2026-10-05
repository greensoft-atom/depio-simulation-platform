# 13 — Implementation plan

## Status — 2026-09-21

M0–M6 are built, and M7 is built apart from the production-box measurements.
The whole project builds offline from `java21-offline/repository`, and every jar
is Java 21 bytecode.

| Milestone | State |
|---|---|
| M0–M3 | Done: all 151 commands of [05](05-commands.md), the model-based test agrees on 300,000 random commands |
| M4 | Done: AOF, fork-free rewrite, recovery, `check-aof`, `dump`. Snapshot consistency, torn-tail and `kill -9` tests pass. The big-key cost is measured in [08 §6](08-persistence.md#6-the-big-key-caveat). |
| M5 | Done: Pub/Sub, blocking pops, `MULTI`/`EXEC`/`WATCH` |
| M6 | Done: client library, sync facade, leased connections, embedded mode (D-21) |
| M7 | Built: `INFO`, `SLOWLOG`, `CONFIG`, `CLIENT`, limits, protected mode, `disable-command`, distribution with systemd unit and Windows launchers. **Open:** the NFR measurements and the 24 h soak on the production box ([12 §9–10](12-testing-strategy.md#9-performance)). The development VM is too loaded to measure latency meaningfully (README). |
| M8 | Not started, by design |
| M9 | **Done: streams, release 2.1.0, 2026-09-29** ([15](15-streams.md)), parts (a) to (e) |


## 1. Modules

```
j-redis-service/
├── pom.xml                         parent: Java 21, versions, pinned plugins
├── j-redis-common/                 RespDecoder, RespEncoder, Reply, strict number codec, ByteUtils
├── j-redis-server/                 everything in 02–08 and 11; Main
├── j-redis-client/                 JRedisClient, sync facade, pub/sub, blocking, transactions (10)
├── j-redis-embedded/               JRedisEmbedded: server engine + client, no socket (10 §10)
├── j-redis-cli/                    j-redis-cli
├── j-redis-tools/                  j-redis-benchmark, j-redis-check-aof, j-redis-dump
└── j-redis-tests/                  reference model, model-based, persistence, crash, concurrency, JMH
```

Embedded mode is its own module so that the client jar used by services never
drags in the server.

### Dependencies

All from the offline bundle ([../../java21-offline/](../../java21-offline/)).

| Module | Depends on |
|---|---|
| common | `netty-buffer`, `netty-codec` 4.1.122.Final |
| server | common, `netty-handler`, `netty-transport-native-epoll` (`linux-x86_64`, used only when `Epoll.isAvailable()`), `slf4j-api` 2.0.17, `jctools-core` 3.3.0, `HdrHistogram` 2.2.2; runtime `logback-classic` 1.3.15 |
| client | common, `netty-handler`, `slf4j-api`, `HdrHistogram` |
| embedded | server, client |
| cli | client |
| tools | client, server (the checker and dumper reuse persistence code) |
| tests | everything; `junit-jupiter` 5.14.4, `assertj-core` 3.27.7, `jmh-core` + `jmh-generator-annprocess` 1.37 |

### Build

- Java 21: `maven.compiler.release` = `21`.
- Pin **every** plugin version in the parent `<pluginManagement>`: `clean`
  3.2.0, `resources` 3.3.1, `compiler` 3.13.0, `surefire` 3.2.5, `jar` 3.4.1,
  `install` 3.1.2, `deploy` 3.1.2, `shade` 3.5.1 — the versions in the bundle.
  An unpinned plugin falls back to Maven's default version, which may not be
  in the bundle.
- Surefire resolves `junit-platform-launcher` at test time; the bundle already
  contains 1.14.4 to match JUnit 5.14.4.
- `shade` produces runnable jars for the server, CLI, and tools.
- Build offline: `mvn -o -Dmaven.repo.local=/opt/java21-offline/repository verify`.

## 2. Milestones

Estimates are for one experienced Java developer. Each milestone ends only when
its **exit criteria** pass.

| # | Milestone | Estimate |
|---|---|---|
| M0 | Skeleton | 3 days |
| M1 | Protocol, command loop, minimal client and CLI | 1.5 weeks |
| M2 | Keyspace, strings, expiry | 2 weeks |
| M3 | Hashes, lists, sets, sorted sets | 2.5 weeks |
| M4 | Persistence | 3 weeks |
| M5 | Pub/Sub, blocking, transactions | 2 weeks |
| M6 | Complete client library, embedded mode | 1.5 weeks |
| M7 | Operations, hardening, NFR verification | 2 weeks |
| | **Total M0–M7** | **about 15 weeks** |
| M8 | Optional, driven by measurements | — |

### M0 — Skeleton

**Build:** parent POM and modules; `logback.xml`; `ServerConfig` +
`ConfigLoader` (Redis-style syntax, sizes, command-line overrides); `Main` with
the exit codes of [11 §3](11-operations-and-security.md#3-start-up-and-shutdown);
CI script.

**Exit criteria:** `mvn -o verify` passes offline on Linux and Windows; the
server jar starts, logs its configuration, and exits cleanly on Ctrl+C.

### M1 — Protocol, command loop, minimal client and CLI

**Build:** RESP decoder and encoder with limits ([03](03-protocol.md));
`NettyServer`, `Connection`, event queue, `CommandThread` with batching and
parking ([02 §4](02-architecture.md#4-the-command-thread-loop)); backpressure;
reply batching; command table and dispatch checks 1–5; `PING`, `ECHO`, `QUIT`,
`HELLO`, `AUTH`, `SELECT`, `CLIENT ID/SETNAME/GETNAME/SETINFO`, `COMMAND`,
`SHUTDOWN` (no persistence yet); protocol-error handling. **Client:** a
connection with the pending FIFO, pipelining, and `send()`. **CLI:** a REPL.

**Exit criteria:**
- decoder fuzzing, 1M random inputs, no exception escapes;
- 10,000 pipelined `PING`s answered in order;
- 1,000 simultaneous connections;
- a client that never reads its replies cannot grow server memory without bound;
- `j-redis-cli` works on Linux and Windows.

### M2 — Keyspace, strings, expiry

**Build:** SipHash-1-2; `Dict` with incremental rehash and `SCAN`
([04 §3](04-data-structures.md#3-dict)); `KeyEntry`; the `Db` facade with all
hooks (snapshot hooks as no-ops until M4); key and string commands; the
`EXPIRE` family; lazy and active expiry ([06](06-expiry-and-memory.md)); strict
number parsing and formatting; `J.CAS`, `J.CAD`; `INFO server/keyspace`;
reference model v1 and the model-based harness ([12 §4](12-testing-strategy.md#4-model-based-testing)).

**Exit criteria:**
- model-based test, 10M operations, no divergence;
- `Dict` and `SCAN` property tests pass;
- growing to 10M keys under load: no command takes longer than 1 ms.

### M3 — Hashes, lists, sets, sorted sets

**Build:** `HashValue`, `ByteArrayRing`, `SetValue`, `ZSetValue` and the skip
list with spans ([04 §6](04-data-structures.md#6-skip-list-with-spans)); every
non-blocking collection command in [05](05-commands.md); `J.ZAROUND`; memory
estimator; model extended.

**Exit criteria:**
- model-based test across all types, 10M operations;
- skip-list property tests;
- `J.ZAROUND` on 1M members p99 < 50 µs (JMH);
- memory estimate within ±20 % of measured heap (first check).

### M4 — Persistence

**Build:** effect propagation in every write handler; `AofWriter` (fsync
policies, backlog limit, `MISCONF`, fail-stop on fsync failure); manifest;
loader (base + incr, truncated tail); base-file writer and reader;
`SnapshotController` (epochs, pre-images, scan slices, `ROTATE`/`COMMIT`)
([08](08-persistence.md)); rewrite triggers; `SAVE`, `BGREWRITEAOF`, `BGSAVE`,
`LASTSAVE`, `SHUTDOWN SAVE`; `LOCK`; `j-redis-check-aof`, `j-redis-dump`;
`DEBUG RELOAD`.

**Exit criteria:** every persistence test in [12 §7](12-testing-strategy.md#7-persistence),
including 1,000 crash-matrix iterations with no inconsistency. Measure the
time to write a 1M-member sorted set and record it in [08 §6](08-persistence.md#6-the-big-key-caveat).

### M5 — Pub/Sub, blocking, transactions

**Build:** everything in [07](07-pubsub-blocking-transactions.md) — pub/sub
registries, glob matcher, output limits, blocking registry with ready keys,
timeouts and deferred commands, `MULTI`/`EXEC`/`DISCARD`/`WATCH`/`UNWATCH`,
`RESET`, `MULTI`-wrapped AOF effects; model extended with transactions.

**Exit criteria:** the concurrency tests in [12 §8](12-testing-strategy.md#8-concurrency-and-the-client-library)
for these features; the pattern-DoS test; a transaction cut off by a crash
mid-AOF is discarded on load.

### M6 — Complete client library, embedded mode

**Build:** everything in [10](10-client-library.md) — typed API for the
commands in [09](09-integration-patterns.md), sync facade with guards, pub/sub with
resubscribe, blocking connection, atomic transaction writes, leased connections,
timeouts and stuck detection, reconnect and handshake, metrics; the
`ClientOutput` seam in the server and the `j-redis-embedded` module.

**Exit criteria:** the client-library tests in [12 §8](12-testing-strategy.md#8-concurrency-and-the-client-library)
pass both embedded and over TCP.

### M7 — Operations, hardening, verification

**Build:** full `INFO` and `commandstats`; `SLOWLOG`; `CONFIG GET/SET`;
`CLIENT LIST/INFO/KILL`; protected mode; `disable-command`; `maxmemory` with
`noeviction`; `maxclients`; idle `timeout`; systemd unit and `run.cmd`
([11](11-operations-and-security.md)); `j-redis-benchmark`.

**Exit criteria:** every NFR in [01 §5](01-requirements-and-scope.md#5-non-functional-requirements)
measured on the production box — performance targets in [12 §9](12-testing-strategy.md#9-performance)
and the 24 h soak in [12 §10](12-testing-strategy.md#10-soak).

### M9 — Streams (2.1)

The backend's result pipeline needs a log several consumer groups read, with a
pending list that outlives a consumer's crash. The design, the commands and how
each part is proved are in [15](15-streams.md); the storage and the logging of
deliveries are D-33 and D-34.

### M10 — Replication (2.2) — done 2026-09-29

The backend's stores need a replica on another machine to promote when a
machine is lost. The design, the parts and how each is proved are in
[16](16-replication.md); the decisions are D-35 to D-38. Released as 2.2.0;
2.2.1 (D-39) made a subscriber follow the primary too.

### M8 — Optional

Only when a measurement or a new requirement asks for it: compact encodings
([04 §9](04-data-structures.md#9-deferred-compact-encodings-m8)), LRU eviction
([06 §7](06-expiry-and-memory.md#7-deferred-eviction-m8)), `appendfsync always`
with group commit, TLS, `MONITOR`, keyspace notifications, `ZUNIONSTORE` and the
lex commands, chunked copy-on-write for big keys.

## 3. Order and parallel work

```
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─► M5 ─► M7
        │                        │
        └── client (M1 part) ────┴─► M6 ──────► (M7)
```

**Two developers**, about 10 weeks: developer A takes the server path M1–M5;
developer B takes the client, CLI, `j-redis-benchmark`, the reference model and
the test harnesses, then M6. Both finish M7 together.

## 4. When services can start using it

| Need | Available after |
|---|---|
| Unit tests of service code against the real engine (embedded, in memory) | M3 + the M1 client — about **week 6** |
| Full service integration: tokens, work queues, leaderboards, pub/sub | M5 + M6 — about **week 11** |
| Production deployment with persistence | M4 and M7 — about **week 15** |

**Schedule note:** the backend's implementation guide allotted **2 weeks** to
memstore. With the scope in this design, 15 weeks for
one developer (10 for two) is the realistic figure. Plan dependent service
milestones against the "week 11" line above.

## 5. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| A write path bypasses the `Db` hooks | Silent data loss after a restart; `WATCH` misses | `Db` is the only write path; snapshot-consistency test and crash matrix in CI; review checklist ([02 §7](02-architecture.md#three-rules-for-every-handler)) |
| Big keys stall the command thread during rewrites | Latency spikes | Size guidelines ([09 §4](09-integration-patterns.md#4-key-naming-and-size-guidelines)); `aof_rewrite_longest_record_ms`; M8 option |
| GC pauses | Latency | Moderate heap, few objects per key, verified in M7 |
| Subtle `Dict`/`SCAN` bugs | Wrong results | Property and model-based tests from M2 |
| Scope creep toward "all of Redis" | Schedule | [05](05-commands.md) is a closed list; additions need a decision-log entry |
| One command thread saturates | Throughput ceiling | 20× headroom over the estimated load ([01 §3](01-requirements-and-scope.md#expected-load)); measured in M7 |
| Estimates are wrong | Schedule | Exit criteria per milestone; the earliest-integration table above |

## 6. Decisions (resolved)

All of these were answered on 2026-09-21; see [14 D-18](14-decision-log.md#d-18--resolved-open-questions).

| Question | Answer |
|---|---|
| Root Java package | `com.jredis.*` (`server`, `client`, `common`, `embedded`, `cli`, `tools`) |
| Port: 6379 or keep memstore's 6400? | 6379, configurable |
| Largest expected leaderboard | Several with 100k+ members ([08 §6](08-persistence.md#6-the-big-key-caveat)) |
| Does anything need `appendfsync always`? | No: periodic fsync, with the period configurable (`appendfsync-interval-millis`) |
| One or two developers? | One |
