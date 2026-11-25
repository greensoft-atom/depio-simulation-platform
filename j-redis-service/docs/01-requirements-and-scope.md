# 01 — Requirements and scope

This page says what j-redis must do and what it deliberately does not do. It is
for anyone who designs, builds, tests or reviews j-redis, and for backend
engineers who want to know what they can rely on. It covers the purpose, the
constraints, the users and their load, the functional and non-functional
requirements, what is out of scope, and the compatibility target.

## 1. Purpose

j-redis gives the platform backend a shared, fast, in-memory data store for
short-lived data and for coordination between processes. The backend gets this
without installing or depending on any third-party Redis server, client library
or tool.

Durable business data (accounts, orders, balances, history) stays in the
database, MySQL. j-redis holds only state that one of these is true for:

- the backend can rebuild it; or
- the backend can tolerate losing the last second or so of it.

## 2. Constraints

| # | Constraint | Consequence |
|---|---|---|
| C-1 | **Java 21** bytecode and runtime. Releases up to and including 1.0.0 were Java 8. | j-redis runs on generational ZGC (the Z Garbage Collector), so garbage-collection pauses no longer limit the design. ZGC turns compressed object pointers ("compressed oops") off, which makes every stored object larger ([06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing)). |
| C-2 | **Offline** build and run | Every dependency comes from [../../java21-offline/](../../java21-offline/) and nowhere else. |
| C-3 | **No third-party Redis** server, client library or tool, anywhere: not in production, development or testing | j-redis has its own client (`j-redis-client`), its own command-line client, the CLI (`j-redis-cli`), and its own benchmark (`j-redis-tools benchmark`, called `j-redis-benchmark` in older text; [14](14-decision-log.md), D-24). Tests check correctness against our own reference model, not against a real Redis. |
| C-4 | Production runs on Linux (RHEL 9.x). The backend's design is three machines with 24 cores and 64 GB of memory each; each store shares its machine with other backend processes. The site launches on one machine that holds every role, both stores included (the backend's C-11, D-93). From 2.2, the backend can run each store as a primary with a replica on another machine. | Replication exists since 2.2 ([16](16-replication.md)). There is no cluster and no automatic failover. |
| C-5 | Development happens on Windows too | Everything must run on Windows. Only the fsync of a directory differs ([08](08-persistence.md#11-platform-notes)). |
| C-6 | Minimal dependencies | Runtime: Netty 4.1.122, SLF4J 2.0.17 with Logback 1.3.15, JCTools 3.3.0, HdrHistogram 2.2.2. Tests: JUnit 5.14.4 and AssertJ 3.27.7. JMH 1.37 is in the offline bundle, but no module uses it yet ([14](14-decision-log.md), D-25). |

The backend's own constraints, with C-11, are in its
[scope and requirements](../../docs/requirements/01-scope-and-nfrs.md).

## 3. Who uses it

Three kinds of program connect to j-redis:

| Client | How it connects | Traffic |
|---|---|---|
| **API and coordinator services** | The blocking ("sync") facade on worker threads and the asynchronous API elsewhere; one pub/sub connection; one blocking connection for work queues | Most of the traffic |
| **Latency-critical services** (several instances) | The asynchronous API only, because their event-loop and session threads must never block; one pub/sub connection for commands | Tokens, heartbeats, result pushes |
| **Tools** | `j-redis-cli` and `j-redis-tools benchmark` | Operators and tests |

### Expected load

This is the estimate made from the backend design for the first deployment,
when j-redis was designed. It assumed a few thousand users online at once,
about 2,000 of them in active sessions. The backend now sizes for up to about
5,000 players at launch on one machine, and 50,000 on three machines
([backend capacity targets](../../docs/requirements/01-scope-and-nfrs.md#5-capacity-targets)).
The table below has not been redone for those numbers.

| Source | Estimate |
|---|---|
| Presence refresh (every 30 s per online user) | ~200 ops/s |
| API request rate limiting (2 operations per request) | ~4,000 ops/s |
| One-time tokens, resume keys, service heartbeats | ~100 ops/s |
| Result pushes, leaderboards, events | ~500 ops/s |
| Leaderboard reads, waiting-queue scans | ~500 ops/s |
| **Total** | **~5,000 ops/s**, bursts to maybe 20,000 |

The throughput target is 100,000 operations per second (ops/s), which gives more
than 20 times the estimated load as headroom. The data set is small: well under
1 GB, mostly leaderboards.

## 4. Functional requirements

| ID | Requirement | Doc |
|---|---|---|
| FR-1 | Accept TCP connections that speak **RESP2** (version 2 of the REdis Serialization Protocol). Support pipelining. Support inline commands, for debugging by hand. | [03](03-protocol.md) |
| FR-2 | Data types **String, Hash, List, Set, Sorted Set**, and **Stream** from 2.1, with the commands listed in [05](05-commands.md), following Redis 7.2 semantics | [04](04-data-structures.md), [05](05-commands.md), [15](15-streams.md) |
| FR-3 | A **TTL** (time to live) on any key: millisecond precision, absolute and relative forms, and the `NX`/`XX`/`GT`/`LT` conditions | [06](06-expiry-and-memory.md) |
| FR-4 | **Pub/Sub** (publish and subscribe) with channels and glob patterns | [07](07-pubsub-blocking-transactions.md) |
| FR-5 | **Blocking pops** on lists and sorted sets, with timeouts and first-in, first-out (FIFO) fairness. From 2.1, blocking stream reads (`XREAD` and `XREADGROUP` with `BLOCK`) too. | [07](07-pubsub-blocking-transactions.md), [15](15-streams.md) |
| FR-6 | **Transactions**: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH` | [07](07-pubsub-blocking-transactions.md) |
| FR-7 | **Persistence**: survive a restart and a crash, and recover automatically | [08](08-persistence.md) |
| FR-8 | The extension commands `J.ZAROUND`, `J.CAS`, `J.CAD` | [09](09-integration-patterns.md) |
| FR-9 | Introspection: `INFO`, `SLOWLOG`, `CLIENT LIST`, `DBSIZE`, and a subset of `CONFIG GET` and `CONFIG SET` | [11](11-operations-and-security.md) |
| FR-10 | Password authentication and protected mode | [11](11-operations-and-security.md) |
| FR-11 | `j-redis-client`: an asynchronous API, a blocking facade, pub/sub, blocking commands, transactions, automatic reconnect, and from 2.2 following a promoted primary | [10](10-client-library.md) |
| FR-12 | **Embedded mode**: the same engine inside the calling process, with no socket, for tests and tools | [10](10-client-library.md) |
| FR-13 | Tools: `j-redis-cli`, and `j-redis-tools` with its `benchmark`, `check-aof` and `dump` commands (older text calls them `j-redis-benchmark`, `j-redis-check-aof` and `j-redis-dump`; [14](14-decision-log.md), D-24) | [11](11-operations-and-security.md) |
| FR-14 | **Replication** (2.2): a primary and its replicas, `WAIT`, and promotion by a script, fenced by epochs | [16](16-replication.md) |

## 5. Non-functional requirements

Each target below is to be measured on the production machine
([C-4](#2-constraints)) in milestone M7 ([13](13-implementation-plan.md)).
Those measurements are still open: M7's code is built, but the development VM is
too loaded to measure latency in a meaningful way.

| ID | Requirement | Target |
|---|---|---|
| NFR-1 | Server-side latency of O(1) and O(log n) commands at 100k ops/s | **p99 ≤ 1 ms**, p99.9 ≤ 2 ms, *including* while the server rehashes, expires keys and takes snapshots. Big keys are an exception: [08 §6](08-persistence.md#6-the-big-key-caveat). |
| NFR-2 | Throughput of `GET`/`SET` with 32-byte values | **≥ 100k ops/s** with 50 clients that do not pipeline; **≥ 500k ops/s** with a pipeline depth of 64 |
| NFR-3 | Memory | At most 2 times what Redis uses for small keys. The `INFO` estimate is within ±20 % of the retained heap. |
| NFR-4 | Durability with `appendfsync everysec` | At most 2 s of acknowledged writes lost on `kill -9` or a power loss; **0 lost on a clean shutdown** |
| NFR-5 | Consistency after any crash | The recovered data equals the state after **some prefix** of the executed commands. It is never a state that did not exist. |
| NFR-6 | Recovery time | At most 30 s to load 1 GB of data |
| NFR-7 | Garbage collection | No pause over 50 ms with an 8 GB heap under the target load |
| NFR-8 | Robustness | Malformed input never crashes the server. Limits per client stop one client from using up the memory. |
| NFR-9 | Portability | The same behaviour on Linux and Windows, except the fsync of a directory |

## 6. Out of scope

j-redis explicitly does **not** build the features below. Each can be revisited
later; the backend design needs none of them.

| Left out | Why, or what to use instead |
|---|---|
| Sentinel, Cluster, automatic failover | A person starts a promotion, and a script carries it out (the backend's [D-8](../../docs/architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)). Replication itself is built since 2.2 ([16](16-replication.md)). |
| Lua scripting, Functions | Extension commands and `MULTI`/`EXEC` cover what the backend needs ([14](14-decision-log.md), D-7) |
| Bitmaps, HyperLogLog, Geo | The backend does not use them |
| RESP3 | Our own client does not need it. `HELLO 3` replies with a `NOPROTO` error. |
| Multiple databases | `SELECT 0` is accepted and any other number is rejected. Use key prefixes instead. |
| ACL users | One password, `requirepass` |
| TLS | Not built. j-redis traffic stays on the loopback interface or the private network. |
| Eviction policies other than `noeviction` | Deferred to milestone M8, which is optional and not started |
| Compact encodings for small values | Deferred to M8, and only if measurements show memory pressure |
| `appendfsync always` | Deferred to M8. The durable data lives in MySQL. |
| `KEYS` in production | Built for development. The production configuration (`dist/conf/j-redis.conf`) disables it. |
| `double` text that is byte for byte the same as Redis's in every case | Values round-trip exactly. The text matches Redis for integers and common decimals ([03 §7](03-protocol.md#7-number-formatting)). |

### Built since this list was first written

Two features started on this list and are now built:

| Feature | Status |
|---|---|
| Streams | Not in 1.0 or 2.0. **Built in 2.1**, because the backend's result pipeline needs them ([15](15-streams.md)). |
| Replication | **Built in 2.2**: a primary and its replicas ([16](16-replication.md)). Failover stays scripted, as the first row of the table above says. |

## 7. Compatibility target

For every supported command, **the behaviour that Redis 7.2 documents** is the
specification. That covers arguments, return values, edge cases and error
prefixes. Redis 7.2 is the last release under the BSD licence, and Valkey is
based on it, so its behaviour is stable and widely documented. The places where
j-redis differs are listed in [05 §3](05-commands.md#3-deviations-from-redis-72).

The Redis command reference is the behavioural specification. The development
machines are offline (C-2), so keep an offline copy of it.
