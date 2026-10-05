# 01 — Requirements and scope

## 1. Purpose

Provide the platform backend with a shared, fast, in-memory data store for
short-lived and coordination data, without installing or depending on any
third-party Redis server, client library or tool.

Durable business data (accounts, orders, balances, history) stays in the
database (MySQL). j-redis-service holds state that either can be rebuilt or
can tolerate losing the last second or so.

## 2. Constraints

| # | Constraint | Consequence |
|---|---|---|
| C-1 | **Java 21** bytecode and runtime (was Java 8 through 1.0.0) | Generational ZGC, so pause time no longer constrains the design. ZGC turns compressed oops off, which makes every stored object larger ([06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing)). |
| C-2 | **Offline** build and run | Dependencies come only from [../../java21-offline/](../../java21-offline/) |
| C-3 | **No third-party Redis** server, client library, or tool, anywhere — in production, development, or testing | Own client (`j-redis-client`), CLI (`j-redis-cli`), and benchmark (`j-redis-benchmark`). Correctness is checked against our own reference model, not against a real Redis. |
| C-4 | Production is one Linux box, 24 cores, 64 GB RAM, **per store**. From 2.2, the backend runs each store as a primary with a replica on another machine (its three-machine topology) | Replication from 2.2 ([16](16-replication.md)); no cluster, no automatic failover |
| C-5 | Development happens on Windows too | Everything must run on Windows; only directory-fsync differs ([08](08-persistence.md#11-platform-notes)) |
| C-6 | Minimal dependencies | Runtime: Netty 4.1.122, SLF4J 2.0.17 + Logback 1.3.15, JCTools 3.3.0, HdrHistogram 2.2.2. Tests: JUnit 5.14.4, AssertJ 3.27.7, JMH 1.37. |

## 3. Who uses it

| Client | How | Traffic |
|---|---|---|
| **API / coordinator services** | sync facade on worker threads, async elsewhere; one pub/sub connection; one blocking connection for work queues | Most of the traffic |
| **Latency-critical services** (several instances) | async only, because their event-loop and session threads never block; one pub/sub connection for commands | Tokens, heartbeats, result pushes |
| **tools** | `j-redis-cli`, `j-redis-benchmark` | Operators and tests |

### Expected load

Derived from the backend design for the first deployment: a few thousand
concurrent users, about 2,000 of them in active sessions.

| Source | Estimate |
|---|---|
| Presence refresh (every 30 s per online user) | ~200 ops/s |
| API request rate limiting (2 ops per request) | ~4,000 ops/s |
| One-time tokens, resume keys, service heartbeats | ~100 ops/s |
| Result pushes, leaderboards, events | ~500 ops/s |
| Leaderboard reads, waiting-queue scans | ~500 ops/s |
| **Total** | **~5,000 ops/s**, bursts to maybe 20,000 |

The throughput target of 100,000 ops/s gives more than 20× headroom. The data
set is small: well under 1 GB, dominated by leaderboards.

## 4. Functional requirements

| ID | Requirement | Doc |
|---|---|---|
| FR-1 | Accept TCP connections speaking **RESP2**; support pipelining; support inline commands for manual debugging | [03](03-protocol.md) |
| FR-2 | Data types **String, Hash, List, Set, Sorted Set** with the commands listed in [05](05-commands.md), following Redis 7.2 semantics | [04](04-data-structures.md), [05](05-commands.md) |
| FR-3 | **TTL** on any key, millisecond precision, absolute and relative forms, `NX`/`XX`/`GT`/`LT` conditions | [06](06-expiry-and-memory.md) |
| FR-4 | **Pub/Sub** with channels and glob patterns | [07](07-pubsub-blocking-transactions.md) |
| FR-5 | **Blocking pops** on lists and sorted sets with timeouts, FIFO fairness | [07](07-pubsub-blocking-transactions.md) |
| FR-6 | **Transactions**: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH` | [07](07-pubsub-blocking-transactions.md) |
| FR-7 | **Persistence**: survive restart and crash; recover automatically | [08](08-persistence.md) |
| FR-8 | Extension commands `J.ZAROUND`, `J.CAS`, `J.CAD` | [09](09-integration-patterns.md) |
| FR-9 | Introspection: `INFO`, `SLOWLOG`, `CLIENT LIST`, `DBSIZE`, `CONFIG GET/SET` subset | [11](11-operations-and-security.md) |
| FR-10 | Password authentication and protected mode | [11](11-operations-and-security.md) |
| FR-11 | `j-redis-client`: async API, sync facade, pub/sub, blocking, transactions, automatic reconnect | [10](10-client-library.md) |
| FR-12 | **Embedded mode**: the same engine in-process with no socket, for tests and tools | [10](10-client-library.md) |
| FR-13 | Tools: `j-redis-cli`, `j-redis-benchmark`, `j-redis-check-aof`, `j-redis-dump` | [11](11-operations-and-security.md) |

## 5. Non-functional requirements

All numbers are measured on the production box ([C-4](#2-constraints)) and
verified in milestone M7 ([13](13-implementation-plan.md)).

| ID | Requirement | Target |
|---|---|---|
| NFR-1 | Server-side latency of O(1)/O(log n) commands at 100k ops/s | **p99 ≤ 1 ms**, p99.9 ≤ 2 ms, *including* while rehashing, expiring and snapshotting (big-key exception: [08 §6](08-persistence.md#6-the-big-key-caveat)) |
| NFR-2 | Throughput, `GET`/`SET` with 32-byte values | **≥ 100k ops/s** with 50 unpipelined clients; **≥ 500k ops/s** with pipeline depth 64 |
| NFR-3 | Memory | ≤ 2× Redis for small keys; `INFO` estimate within ±20 % of retained heap |
| NFR-4 | Durability with `appendfsync everysec` | ≤ 2 s of acknowledged writes lost on `kill -9` or power loss; **0 lost on clean shutdown** |
| NFR-5 | Consistency after any crash | Recovered data equals the state after **some prefix** of the executed commands — never a state that did not exist |
| NFR-6 | Recovery time | ≤ 30 s to load 1 GB of data |
| NFR-7 | GC | No pause > 50 ms at an 8 GB heap under target load |
| NFR-8 | Robustness | Malformed input never crashes the server; per-client limits stop one client exhausting memory |
| NFR-9 | Portability | Identical behaviour on Linux and Windows except directory fsync |

## 6. Out of scope

Explicitly **not** built. Each can be revisited later; none is needed by the
backend design.

| Left out | Why / alternative |
|---|---|
| Sentinel, Cluster, automatic failover | Promotion is scripted and human-triggered (the backend's D-8). **Replication is in scope from 2.2** ([16](16-replication.md)) |
| Lua scripting, Functions | Extension commands and `MULTI`/`EXEC` cover the backend's needs ([14](14-decision-log.md), D-7) |
| Bitmaps, HyperLogLog, Geo | Not used by the backend |
| Streams | Out of 1.0 and 2.0; **in scope from 2.1**, which the backend's result pipeline needs ([15](15-streams.md)) |
| RESP3 | Our own client does not need it; `HELLO 3` → `NOPROTO` |
| Multiple databases | `SELECT 0` accepted, anything else rejected; use key prefixes |
| ACL users | Single `requirepass` |
| TLS | Loopback / private network only in v1 |
| Eviction policies except `noeviction` | Deferred to M8 |
| Compact small-value encodings | Deferred to M8, after memory is measured |
| `appendfsync always` | Deferred to M8; durable data lives in MongoDB |
| `KEYS` in production | Implemented for development, disabled by default in production config |
| Byte-identical `double` formatting with Redis in every case | Values round-trip exactly; formatting matches Redis for integers and common decimals ([03 §7](03-protocol.md#7-number-formatting)) |

## 7. Compatibility target

For every supported command, **the behaviour documented for Redis 7.2** is the
specification: arguments, return values, edge cases, error prefixes. 7.2 is the
last BSD-licensed release and the base of Valkey, so its behaviour is stable and
widely documented. Deviations are listed in [05 §3](05-commands.md#3-deviations-from-redis-72).

The Redis command reference is the behavioural spec. Save an offline copy before
development starts, since the development machines are offline (C-2).
