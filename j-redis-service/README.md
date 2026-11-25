# j-redis-service

j-redis is an in-memory data store that speaks the Redis protocol. It is written
in **Java 21** and runs as one Java process. The backend uses it for fast shared
state: sessions, one-time tokens, caches, counters, rankings, queues, locks and
publish/subscribe ("pub/sub") messages. With j-redis, the backend needs no
third-party Redis server, client library or tool.

This page is for engineers who are new to j-redis. It says what j-redis is, how
to build and run it in a few minutes, where each guide and design document is,
how the source tree is laid out, and what has been tested. j-redis comes with
its own Java client library, a command-line client, tools and runnable examples.

**Version 2.2.2** (2026-11-25). This release makes `HELLO` report the server's role,
`primary` or `replica`, as `ROLE` and `INFO` do; it said `master` on every server.
The releases before it:

| Version | Date | What it added |
|---|---|---|
| 2.2.2 | 2026-11-25 | `HELLO` reports the server's role (backend defect D-52) |
| 2.2.1 | 2026-10-01 | Every connection of a client follows the new primary, subscribers and idle ones included |
| 2.2.0 | 2026-09-29 | Replication: a primary and its replicas, with promotion guarded by epochs ([docs/16](docs/16-replication.md)) |
| 2.1.0 | 2026-09-29 | Streams ([docs/15](docs/15-streams.md)) |
| 2.0.0 | 2026-09-23 | The Java 21 release |

See [What was verified](#what-was-verified) below and [CHANGELOG.md](CHANGELOG.md).

---

## At a glance

| | |
|---|---|
| Runtime | Java 21 or newer, in one JVM (Java Virtual Machine) process. The build targets Java 21, and a check confirms that every jar holds Java 21 bytecode. The garbage collector is Generational ZGC by default. |
| Protocol | RESP2 (version 2 of the REdis Serialization Protocol, the Redis wire protocol) over TCP. The default port is **6379**. |
| Data types | String, Hash, List, Set, Sorted Set and Stream (since 2.1). Any key can have a TTL (time to live): the key is deleted when its time runs out. |
| Features | 170 commands, with the behaviour of Redis 7.2. They include pub/sub, blocking pops, streams with consumer groups ([docs/15](docs/15-streams.md)), replication with `WAIT` and scripted promotion ([docs/16](docs/16-replication.md)), transactions with `MULTI`/`EXEC`/`WATCH`, and `SCAN`. Three commands are j-redis's own extensions: `J.ZAROUND` returns "my rank and my neighbours" in a ranking, and `J.CAS`/`J.CAD` (compare-and-set, compare-and-delete) make locks safe. The 170 include `DEBUG`, which exists only when `enable-debug-command yes` is set. |
| Execution | Netty I/O threads read and parse the requests. **One command thread** owns all the data and runs every command, one after another. Because only one thread touches the data, the data needs no locks. |
| Persistence | An append-only file (AOF) that the server compacts in the background, without `fork()`. The server forces the file to disk (fsync) once a second by default; the interval is configurable. A clean stop loses nothing. |
| Clients | `j-redis-client`, the Java client library: an asynchronous and a blocking API, automatic pipelining, reconnection, and following a promoted primary. Also `j-redis-cli`, the command-line client, and an embedded mode that runs the real server inside a test. |
| Platforms | Linux for production; a systemd unit is included. Windows and Linux for development. |
| Dependencies | Netty 4.1 (4.1.122.Final in j-redis's own build), SLF4J and Logback for logging, JCTools, and HdrHistogram. All of them are in the offline bundle [`../java21-offline`](../java21-offline/). The backend's own build puts j-redis on the backend's Netty 4.2 (`backend/pom.xml`). |

## Quick start

These four steps build j-redis, start a server and talk to it.

**Inside the backend's repository**, you can skip steps 1 and 2. In a shell
that has sourced `dev-env.sh`, `./dev-jredis.sh build` builds and tests j-redis,
and `./dev-jredis.sh start` runs it on 127.0.0.1:6380, with its data in
`work/run/store`
([build and run](../docs/development/03-build-and-run.md)).

**1. Build.** You need JDK 21 and Maven 3.9. To build without internet access,
set up the offline repository first ([guide 2](docs/guide/02-offline-repository.md)).

```bash
cd j-redis-service
mvn clean install                      # about 3 minutes, tests included
scripts/make-dist.sh --skip-build      # Windows: powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -SkipBuild
```

**2. Run** the server from the distribution in `target/dist/j-redis-2.2.2/`:

```bash
bin/j-redis-server conf/j-redis-dev.conf          # Windows: bin\j-redis-server.cmd conf\j-redis-dev.conf
```

**3. Use** the server from a second terminal:

```bash
bin/j-redis-cli ping                               # PONG
bin/j-redis-cli set greeting "hello"               # OK
bin/j-redis-examples -p 6379                       # 15 runnable examples against this server
```

Without `-p`, `bin/j-redis-examples` runs the examples against an embedded
server of its own. `bin/j-redis-examples --list` lists them.

**4. Use it from Java.** `JRedisClient`'s own methods are asynchronous and
return a `CompletableFuture`; `sync()` gives the blocking versions.

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

The guides in [`docs/guide/`](docs/guide/) give practical, step-by-step
instructions.

| # | Guide | Read it when you want to… |
|---|---|---|
| 1 | [Set up a development machine](docs/guide/01-setup.md) | install JDK 21 and Maven on Windows or Linux, and configure an IDE |
| 2 | [Build with the java21-offline repository](docs/guide/02-offline-repository.md) | build without internet access, using the offline bundle |
| 3 | [Build, test and package](docs/guide/03-build-and-test.md) | compile, run the tests, check the Java 21 guarantee, build the distribution |
| 4 | [Run and operate the server](docs/guide/04-run-and-operate.md) | configure, secure, deploy with systemd, back up, monitor, use the CLI and the tools, run a replica and promote it |
| 5 | [Use it from Java (client guide)](docs/guide/05-client-guide.md) | write application code: every data type, transactions, locks, queues, pub/sub, errors, testing |
| 6 | [Develop the service itself](docs/guide/06-developer-guide.md) | find your way around the code, add a command, write tests, debug |
| 7 | [Upgrade](docs/guide/07-upgrade-guide.md) | upgrade an installation, bump the version, update a dependency or the JDK |

## Examples

[`j-redis-examples`](j-redis-examples/src/main/java/com/jredis/examples/) has
one class for each of 15 topics:

1. quick start
2. strings and counters
3. cache-aside
4. sessions
5. leaderboards
6. rate limiting
7. distributed locks
8. optimistic locking with `WATCH`
9. one-time tokens
10. a reliable work queue
11. pub/sub
12. a service registry
13. asynchronous pipelining
14. `SCAN`
15. error handling

Every test run of the build runs all 15. The client guide describes each one in
[guide 5 §5.15](docs/guide/05-client-guide.md#515-the-runnable-examples).

## Design documents

The design documents explain how j-redis works and why. If Redis is new to you,
read the primer first.

| # | Document | What it covers |
|---|---|---|
| 00 | [Redis primer](docs/00-redis-primer.md) | What Redis is, how it works inside, why it is fast |
| 01 | [Requirements and scope](docs/01-requirements-and-scope.md) | Goals, non-goals, constraints, numeric targets |
| 02 | [Architecture](docs/02-architecture.md) | Threads, the life of a request, the command loop, backpressure |
| 03 | [Protocol](docs/03-protocol.md) | RESP2 framing, the parser, limits, number formats, error conventions |
| 04 | [Data structures](docs/04-data-structures.md) | The keyspace `Dict`, `SCAN`, the value types, the skip list, memory accounting |
| 05 | [Commands](docs/05-commands.md) | Every command, how it is logged for persistence, where it differs from Redis |
| 06 | [Expiry and memory](docs/06-expiry-and-memory.md) | TTLs, active expiry, `maxmemory`, heap sizing |
| 07 | [Pub/Sub, blocking, transactions](docs/07-pubsub-blocking-transactions.md) | The features that change a client's state |
| 08 | [Persistence](docs/08-persistence.md) | The files, the fork-free snapshot and why it is correct, recovery, backups |
| 09 | [Integration patterns](docs/09-integration-patterns.md) | Extension commands, recipes, key naming, connections per process |
| 10 | [Client library](docs/10-client-library.md) | How `j-redis-client` works inside |
| 11 | [Operations and security](docs/11-operations-and-security.md) | Every configuration directive, the `INFO` fields, the runbook, security |
| 12 | [Testing strategy](docs/12-testing-strategy.md) | The reference model, the crash tests, what is implemented |
| 13 | [Implementation plan](docs/13-implementation-plan.md) | Modules, milestones, status (a record) |
| 14 | [Decision log](docs/14-decision-log.md) | Every significant choice and why (a record) |
| 15 | [Streams](docs/15-streams.md) | Release 2.1: the stream type, consumer groups, blocking reads, how a stream is stored and persisted |
| 16 | [Replication](docs/16-replication.md) | Release 2.2: a primary and its replicas, the full sync, the stream of changes, continuing after a dropped link, `WAIT`, promotion and epochs, clients following the primary |

j-redis replaces the "memstore" part of the earlier backend design
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

This table lists each check that was run on j-redis and its result. The numbers
are from the 2.2.1 build unless a row says otherwise.

| Check | Result |
|---|---|
| Tests | **229 tests pass** with `mvn clean install -Pfull` on Temurin 21.0.12.1 (2.2.2, 2026-11-25). They are 21 codec and number tests, 35 server unit tests, 1 client test, 5 example tests and 167 integration tests. The integration tests cover the commands, the protocol over TCP, persistence, the client library (embedded and over TCP), streams, replication between real servers over TCP, every connection of a client following a promotion and a demotion, the model-based test, the review regressions, and two `kill -9` crash tests. Without `-Pfull`, the build skips the two crash tests (they are tagged `slow`) and runs 227. The compiler gives zero warnings. Earlier totals: 138 in 2.0.0, 171 in 2.1.0, 223 in 2.2.0. |
| Review | Six independent reviews (protocol, data structures, commands, persistence, engine, client) found more than 60 defects, mostly in failure paths and edge cases. All were fixed, each with a regression test. See [CHANGELOG.md](CHANGELOG.md) and decisions D-26 to D-30. |
| Model-based test | 300,000 random commands, over 3 seeds, give the same reply, one by one, as a simple reference model. The test compares the whole keyspace every 500 commands. |
| Snapshot consistency | The server writes a base file in slow slices while writes continue. The file equals the state at the moment the rewrite started. |
| Replication (2.2) | A random workload of 24 kinds of command, 12,000 commands in all, runs during and after a full sync. The primary and the replica end with the same `DEBUG DIGEST`. In a second test, a primary process is killed with `kill -9` under a workload that `WAIT`s for its replica, and the replica is promoted: 377 writes were confirmed before the kill, and none was lost. A client that had been given both addresses wrote 109 more writes to the new primary on its own. The old primary, restarted as a replica, followed the new one to the same digest. Every part was mutation-checked ([16 §14](docs/16-replication.md#14-parts-and-how-each-is-proved)). A smoke test from the distribution ran a primary and a replica, `WAIT`, the `READONLY` refusal on the replica, and a promotion with its epoch recorded. |
| Crash test | A server process was killed 5 times with `kill -9` under a transactional workload, rewrites included. Every transaction was either whole or absent. A kill lost at most the last few milliseconds of acknowledged writes (0 to 8 transactions per kill so far). That is within the documented bound of 2 s (D-22). |
| Offline build | The build passes all tests with the `java21-offline` bundle as a read-only mirror, from an empty local repository, with 0 downloads from anywhere else ([guide 2](docs/guide/02-offline-repository.md)). |
| Java 21 | Every class in all 11 built jars, bundled dependencies included, has class-file major version 65 (Java 21) or lower. |
| Memory estimate | Checked against both garbage collectors. G1 uses compressed object pointers ("compressed oops"); ZGC does not, but keeps compressed class pointers. The estimate adapts to the collector: 64 bytes per key under G1, 80 bytes under ZGC. See [06 §6](docs/06-expiry-and-memory.md#6-jvm-heap-sizing). |
| Engine throughput | **Not trustworthy on this machine.** The 1.0.0 figure of 505,000 `SET`/s could not be reproduced. It was measured again under ZGC on the shared development VM (load average about 8 on 12 cores). One connection gives 25,000 `SET`/s at pipeline depth 64, 108,000 at depth 512, and 180,000 at depth 8192 (p50 latency 30 ms). Four connections at depth 512 give 209,000. Here, the time threads take to wake up limits throughput, not the engine. Measure on the production machine before you rely on any of these numbers. |
| Latency | Not yet measured in a meaningful way. The development VM is shared and heavily loaded, and a thread wake-up there costs about 0.3 ms. Measure on the production machine ([guide 4 §4.10](docs/guide/04-run-and-operate.md#410-the-tools)). |
| Big keys during a rewrite | Writing one large key stalls the command thread, once per rewrite: about 20–30 ms for a sorted set of 100,000 members, and 120–250 ms for one of a million ([08 §6](docs/08-persistence.md#6-the-big-key-caveat)). A stream does not stall it (2.1). |

**Not verified yet:**

- the Windows `.cmd` and `.ps1` scripts: they were written for Windows, but no
  Windows machine was available to test them;
- the production performance targets;
- the 24-hour soak test.
