# j-redis-service

A self-hosted, Redis-compatible, in-memory data store written in **Java 21**.
It gives backend services fast shared state (sessions, one-time tokens,
caches, counters, rankings, queues, locks, pub/sub) without installing any
third-party Redis server, client library or tool. It comes with its own Java
client, a command-line client, tools and runnable examples.

**Version 2.2.1** (2026-10-01): every connection of a client follows the
primary after a handover, subscribers and idle ones included. 2.2.0 (2026-09-29) added replication, a
primary and its replicas with promotion guarded by epochs ([docs/16](docs/16-replication.md)). 2.1.0
(2026-09-29) added streams ([docs/15](docs/15-streams.md)); 2.0.0 (2026-09-23)
was the Java 21 release. See [What was verified](#what-was-verified) and [CHANGELOG.md](CHANGELOG.md).

---

## At a glance

| | |
|---|---|
| Runtime | Java 21 or newer, one JVM process. The build targets Java 21, and every jar is verified to be Java 21 bytecode. Generational ZGC by default. |
| Protocol | RESP2 (the Redis wire protocol) over TCP, port **6379** by default |
| Data types | String, Hash, List, Set, Sorted Set, Stream (2.1); a TTL on any key |
| Features | 170 commands with Redis 7.2 semantics: pub/sub, blocking pops, streams with consumer groups ([docs/15](docs/15-streams.md)), replication with `WAIT` and scripted promotion ([docs/16](docs/16-replication.md)), `MULTI`/`EXEC`/`WATCH`, `SCAN`, and 3 extensions (`J.ZAROUND` for "my rank and neighbours", `J.CAS`/`J.CAD` for safe locks) |
| Execution | Netty I/O threads parse requests; **one command thread** owns all data and runs every command in order, so there are no locks on data |
| Persistence | Append-only file with background compaction (no `fork()`); fsync every second (configurable); a clean stop loses nothing |
| Clients | `j-redis-client` (async + blocking API, automatic pipelining, reconnects, following a promoted primary), `j-redis-cli`, and an embedded mode for tests |
| Platforms | Linux for production (systemd unit included); Windows and Linux for development |
| Dependencies | Netty 4.1, SLF4J/Logback, JCTools, HdrHistogram, all in the offline bundle [`../java21-offline`](../java21-offline/) |

## Quick start

**1. Build** (JDK 21 and Maven 3.9; offline setup in [guide 2](docs/guide/02-offline-repository.md)):

```bash
cd j-redis-service
mvn clean install                      # about 3 minutes including tests
scripts/make-dist.sh --skip-build      # Windows: powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -SkipBuild
```

**2. Run** the server from the distribution in `target/dist/j-redis-2.2.1/`:

```bash
bin/j-redis-server conf/j-redis-dev.conf          # Windows: bin\j-redis-server.cmd conf\j-redis-dev.conf
```

**3. Use** it, in a second terminal:

```bash
bin/j-redis-cli ping                               # PONG
bin/j-redis-cli set greeting "hello"               # OK
bin/j-redis-examples -p 6379                       # 15 runnable examples against this server
```

**4. From Java:**

```java
JRedisClient redis = JRedisClient.builder()
        .address("127.0.0.1", 6379)
        .clientName("orders-service")
        .build()
        .start();

redis.sync().set("user:42:name", "Ada");
redis.zincrby("lb:weekly:points", 10, "user:42");                 // async: CompletableFuture
Long rank = redis.sync().zrevrank("lb:weekly:points", "user:42");  // blocking facade

redis.close();
```

## Guides

Practical, step-by-step documentation in [`docs/guide/`](docs/guide/):

| # | Guide | For when you want to… |
|---|---|---|
| 1 | [Set up a development machine](docs/guide/01-setup.md) | install JDK 21 and Maven on Windows or Linux, configure an IDE |
| 2 | [Build with the java21-offline repository](docs/guide/02-offline-repository.md) | build without internet access, using the offline bundle |
| 3 | [Build, test and package](docs/guide/03-build-and-test.md) | compile, run tests, check the Java 21 guarantee, build the distribution |
| 4 | [Run and operate the server](docs/guide/04-run-and-operate.md) | configure, secure, deploy with systemd, back up, monitor, use the CLI and tools, run a replica and promote it |
| 5 | [Use it from Java (client guide)](docs/guide/05-client-guide.md) | write application code: every data type, transactions, locks, queues, pub/sub, errors, testing |
| 6 | [Develop the service itself](docs/guide/06-developer-guide.md) | find your way around the code, add a command, write tests, debug |
| 7 | [Upgrade](docs/guide/07-upgrade-guide.md) | upgrade an installation, bump the version, update a dependency or the JDK |

## Examples

[`j-redis-examples`](j-redis-examples/src/main/java/com/jredis/examples/) has one
class per topic: quick start, strings and counters, cache-aside, sessions,
leaderboards, rate limiting, distributed locks, optimistic locking with
`WATCH`, one-time tokens, a reliable work queue, pub/sub, a service registry,
async pipelining, `SCAN`, and error handling. The build runs all of them on
every test run. They are described in
[guide 5 §5.15](docs/guide/05-client-guide.md#515-the-runnable-examples).

## Design documents

How and why it works. Read the primer first if Redis is new to you.

| # | Document | What it covers |
|---|---|---|
| 00 | [Redis primer](docs/00-redis-primer.md) | What Redis is, how it works inside, why it is fast |
| 01 | [Requirements and scope](docs/01-requirements-and-scope.md) | Goals, non-goals, constraints, numeric targets |
| 02 | [Architecture](docs/02-architecture.md) | Threads, request lifecycle, the command loop, backpressure |
| 03 | [Protocol](docs/03-protocol.md) | RESP2 framing, parser, limits, number formats, error conventions |
| 04 | [Data structures](docs/04-data-structures.md) | The keyspace `Dict`, `SCAN`, value types, the skip list, memory accounting |
| 05 | [Commands](docs/05-commands.md) | Every command, how it is logged for persistence, deviations from Redis |
| 06 | [Expiry and memory](docs/06-expiry-and-memory.md) | TTLs, active expiry, `maxmemory`, heap sizing |
| 07 | [Pub/Sub, blocking, transactions](docs/07-pubsub-blocking-transactions.md) | The features that change a client's state |
| 08 | [Persistence](docs/08-persistence.md) | Files, the fork-free snapshot and why it is correct, recovery, backups |
| 09 | [Integration patterns](docs/09-integration-patterns.md) | Extension commands, recipes, key naming, connections per process |
| 10 | [Client library](docs/10-client-library.md) | How `j-redis-client` works inside |
| 11 | [Operations and security](docs/11-operations-and-security.md) | All directives, `INFO` fields, runbook, security |
| 12 | [Testing strategy](docs/12-testing-strategy.md) | Reference model, crash tests, what is implemented |
| 13 | [Implementation plan](docs/13-implementation-plan.md) | Modules, milestones, status |
| 14 | [Decision log](docs/14-decision-log.md) | Every significant choice and why |
| 15 | [Streams](docs/15-streams.md) | Release 2.1: the stream type, consumer groups, blocking reads, how a stream is stored and persisted |
| 16 | [Replication](docs/16-replication.md) | Release 2.2: a primary and its replicas, the full sync, the stream of changes, continuing after a drop, `WAIT`, promotion and epochs, clients following the primary |

It replaces the "memstore" part of the earlier backend design
([../docs/archive/memstore.md](../docs/archive/memstore.md)).

## Repository layout

```
j-redis-service/
├── pom.xml                  parent: Java 21, pinned dependency and plugin versions (the only place the version is set)
├── j-redis-common/          RESP codec, replies, strict number and glob handling, build version
├── j-redis-server/          the server (main class com.jredis.server.Main)
├── j-redis-client/          the Java client library
├── j-redis-embedded/        the real server in-process, for tests
├── j-redis-cli/             command-line client
├── j-redis-tools/           benchmark, check-aof, dump
├── j-redis-examples/        runnable examples (tested in every build)
├── j-redis-tests/           integration, model-based, persistence, regression and crash tests
├── dist/                    launchers (sh + cmd), sample configs, systemd unit
├── scripts/                 make-dist.sh / .ps1, set-version.sh / .ps1
├── docs/                    design documents; docs/guide/ has the guides
└── CHANGELOG.md
```

## What was verified

| Check | Result |
|---|---|
| Tests | **229 tests pass** with `mvn clean install -Pfull` on Temurin 21.0.12.1 (2.2.1, 2026-10-01): 21 codec and number tests, 35 server unit tests, 1 client test, 5 example tests, and 167 integration tests. The integration tests cover commands, the protocol over TCP, persistence, the client library (embedded and TCP), streams, replication between real servers over TCP, a client's every connection following a promotion and a demotion, the model-based test, the review regressions, and two `kill -9` crash tests. Zero compiler warnings. (138 in 2.0.0, 171 in 2.1.0, 223 in 2.2.0.) |
| Review | Six independent reviews (protocol, data structures, commands, persistence, engine, client) found 60+ defects, mostly in failure and edge paths. All were fixed, each with a regression test; see [CHANGELOG.md](CHANGELOG.md) and decisions D-26 to D-30 |
| Model-based test | 300,000 random commands over 3 seeds agree reply-for-reply with a naive reference model; the full keyspace is compared every 500 commands |
| Snapshot consistency | A base file written in slow slices while writes continue equals the state at the moment the rewrite started |
| Replication (2.2) | A random workload of 24 kinds of command, 12,000 commands, during and after a full sync: primary and replica end with the same `DEBUG DIGEST`. `kill -9` of a primary process under a workload that `WAIT`s for its replica, then the replica promoted: 377 writes confirmed before the kill, none lost; a client given both addresses wrote 109 more to the new primary on its own; the old primary, restarted as a replica, followed the new one to the same digest. Every part mutation-checked ([16 §14](docs/16-replication.md#14-parts-and-how-each-is-proved)). Smoke-tested from the distribution: a primary and a replica, `WAIT`, `READONLY`, a promotion with its epoch recorded. |
| Crash test | 5 × `kill -9` of a server process under a transactional workload, rewrites included: every transaction whole or absent. A kill lost at most the last few milliseconds of acknowledged writes (0 to 8 transactions per kill so far), within the documented 2 s bound (D-22). |
| Offline build | Builds and passes all tests from the `java21-offline` bundle as a read-only mirror, from an empty local repository, with 0 downloads from anywhere else ([guide 2](docs/guide/02-offline-repository.md)) |
| Java 21 | Every class in all 11 built jars, bundled dependencies included, is major version ≤ 65 |
| Memory estimate | Verified against both collectors: G1 reports compressed oops, ZGC does not but keeps compressed class pointers. Sizes adapt (per key 64 B under G1, 80 B under ZGC) — see [06 §6](docs/06-expiry-and-memory.md#6-jvm-heap-sizing) |
| Engine throughput | **Not trustworthy on this machine, and the 1.0.0 figure of 505,000 `SET`/s could not be reproduced.** Re-measured under ZGC on the shared development VM (load average ~8 of 12 cores): one connection gives 25k/s at pipeline depth 64, 108k at 512, 180k at 8192 (p50 30 ms); four connections at depth 512 give 209k. Throughput here is dominated by thread wake-up latency, not the engine. Measure on the production machine before believing any of these. |
| Latency | Not measured meaningfully yet. The development VM is shared and heavily loaded, and thread wake-ups there cost about 0.3 ms. Measure on the production machine ([guide 4 §4.10](docs/guide/04-run-and-operate.md#410-the-tools)). |
| Big keys during a rewrite | About 20–30 ms per 100k-member sorted set, 120–250 ms per million ([08 §6](docs/08-persistence.md#6-the-big-key-caveat)) |

**Not verified yet:** the Windows `.cmd`/`.ps1` scripts (written for Windows,
but no Windows machine was available), the production performance targets,
and the 24-hour soak test.
