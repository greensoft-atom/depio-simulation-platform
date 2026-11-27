# 01 — Code patterns and project structure

This page is for a developer writing code for the backend's services. It says how the code is laid out in Maven
modules (§1), which patterns the code follows (§2), the coding rules (§3), how errors are handled and logged (§4),
and how each layer is tested (§5). The architecture itself is in
[architecture/01](../architecture/01-system-topology.md); this page is how that architecture is laid out in code.

Most patterns in §2 were written before the code. Each one ends with an **As built** note that says where the code
differs from the first design. Trust the note where the two disagree.

## 1. Maven modules

There is one module per concern, and one deployable per process type
([D-4](../architecture/03-decision-log.md#d-4--split-by-workload-shape-not-by-business-domain)).

```
backend/
  pom.xml          parent: release 21, dependency and plugin versions
  common/          IntList, Xorshift, PhaseTimer, Metrics/MetricsServer, Secrets, Logs,
                   RefusedConfiguration, TrustedProxies
  protocol/        Wire, SnapshotWriter/Reader, ClientWorld: the byte format and its
                   reference client (shared by arena, bot client and the golden-vector tests)
  sim/             World, Entity, SpatialHash, Room, collision, stats, bots, and Content:
                   the balance tables, as code — NO Netty, NO IO
  handoff/         what processes share through j-redis: Ticket, TicketStore, SessionStore,
                   ArenaDirectory, LeaderboardStore, LobbyPush, MatchMode, MatchOutcome,
                   MatchResultStream (and the lists beside it, MatchResultQueue),
                   TournamentGrants, MatchArrivals, SandboxHolds, StoreClients
  arena/           RoomThread, RoomRegistry, ArenaServer (Netty TCP), SnapshotEncoder,
                   MatchResultPublisher (the spool), the modes' rules (Waves, Domination,
                   Tag, Sandbox), ProxyLine (what the front's nginx sends first), ArenaTls,
                   ArenaMain
  gateway/         the lobby WebSocket: auth handshake, pushes, rate limiting
  platform/        auth and guests, sessions, joins, the matcher and parties, teams,
                   tournaments, boards, shop, items, payments, social, goals, the pass;
                   the HTTP API and the admin API on the JDK's server; the watch
                   (monitoring, watch/)
  worker/          result consumer, rewards, the tournament clock, seasons, retention,
                   leaderboard rebuild, ledger check, replica and backup watches
  persistence/     MySQL repositories, Flyway migrations (the baseline, D-75), HikariCP,
                   the pool's primary by epoch (PrimaryDataSource)
  tools/           TickBenchmark, BotClient (load test), LobbyClient, SoakJudge, and the
                   Apply, Purge and Rank benchmarks
  crypto-examples/ tested examples of signing, encryption, hashing, X.509 and TLS, to copy
                   from; no module depends on it and no release carries it (04-crypto.md)
```

**As built.** The design had a `content/` module of JSON files. The simulation's content is code in `sim` instead
(`Content`, `ClassTable`), until something needs to change it without a release (Q-27). `platform`'s items, shop
and packs are JSON files, read at start. `platform`'s modules (04) are built as service classes, not as packages
of their own: `rooms` is the arena directory and the tickets (the arena makes its rooms itself, D-20), and
`notify` is the inbox and `LobbyPush`.

**The key structural decision is that `sim` has no IO dependency.** So the whole simulation runs headless, in unit
tests and in the tick benchmark.

**`protocol` is shared with the client only through the golden vectors**
([protocol-spike](../../protocol-spike/README.md)), not through a shared artefact. The client is C#, so the
contract is bytes, not types.

**The data store is a dependency, not a module**: `j-redis-client`, from
[j-redis-service](../../j-redis-service/README.md). j-redis is a project of its own, with its own release cycle.

### Dependencies

Every library comes from the offline bundle [`../../java21-offline/`](../../java21-offline/), and every one was
checked to be Java 21 bytecode or older. The parent `pom.xml` pins the versions.

| Artifact | Version | Used by |
|---|---|---|
| io.netty (buffer, codec, common, handler, transport) | 4.2.18.Final | arena, gateway, tools |
| io.netty:netty-codec-http | 4.2.18.Final | gateway (WebSocket) |
| io.netty:netty-transport-classes-epoll, and netty-transport-native-epoll classifier `linux-x86_64` | 4.2.18.Final | arena (the classifier jar carries the `.so`) |
| com.jredis:j-redis-client, j-redis-common | 2.2.2 | handoff, platform, worker, tools; arena and gateway through handoff |
| com.jredis:j-redis-embedded | 2.2.2 | tests |
| com.mysql:mysql-connector-j | 26.7.0 | persistence |
| com.zaxxer:HikariCP | 7.1.0 | persistence |
| org.flywaydb:flyway-core, flyway-mysql | 13.7.0 | persistence |
| com.fasterxml.jackson.core:jackson-databind | 2.22.3 | handoff, platform, worker, gateway, tools |
| org.bouncycastle:bcprov-jdk18on | 1.86 | the account service (Argon2id, D-117; the platform's until then), crypto-examples |
| com.nimbusds:nimbus-jose-jwt | 10.10 | platform (the account service's tokens checked, D-117), the account service |
| org.bouncycastle:bcpkix-jdk18on | 1.86 | crypto-examples |
| org.hdrhistogram:HdrHistogram | 2.2.2 | common (tick percentiles), tools |
| org.slf4j:slf4j-api / ch.qos.logback:logback-classic | 2.0.20 / 1.6.3 | all |
| org.jctools:jctools-core | 4.0.7 | arena (join, resume and leave queues) |
| org.junit.jupiter:junit-jupiter / org.assertj:assertj-core | 6.1.3 / 3.27.7 | tests |

**In the bundle and not used:**
- netty-tcnative: the arena's own TLS is the JDK's provider, and nginx ends TLS for the gateway and the platform;
- Micrometer: metrics are written by hand (`common/Metrics`);
- Caffeine, fastutil and JMH.

**The Java 8 pins are gone**: JCTools is on 4.x, Logback on 1.6.x, JUnit on 6.x. One trap survives the move:
**`netty-all` is still unusable as a dependency**, because that artifact contains no classes. Depend on the modules
above.

## 2. Patterns

### 2.1 Single-writer + message passing

Every mutable aggregate (a room, the j-redis dataset) has one owning thread. Every other thread sends it messages:

```java
// arena: from Netty IO thread
room.post(msg);                          // MPSC offer, drop if full

// arena: j-redis callback on client IO thread
client.claim(ticketId).whenComplete((map, err) -> room.post(Msg.joinResolved(conn, map, err)));

// room thread
void drainInputs() { Msg m; while ((m = queue.poll()) != null) { handle(m); pool.free(m); } }
```

No `synchronized`, and no `volatile` on domain state. If you find yourself wanting a lock inside `sim`, the design
is wrong.

**As built:** there is no `Msg` type and no `room.post`. The Netty threads hand a room its `Connection`s through
JCTools MPSC (multi-producer, single-consumer) queues: joins, resumes and leaves (`RoomThread`). The latest input
goes through an atomic slot on the connection (`Connection.latestInput`, a packed `long`), which the room reads
each tick. The sketch above shows the shape, not the code.

### 2.2 Pooled messages and entities

```java
public final class Msg {
    public byte type; public Connection conn; public int seq; public byte moveMask; public short aim; public byte flags;
    public Object payload;   // rare: Join map, chat string
    void reset() { type = 0; conn = null; payload = null; }
}
public final class Pool<T> {
    private final Object[] items; private int top; private final Supplier<T> factory;
    public T get() { return top == 0 ? factory.get() : (T) items[--top]; }
    public void free(T t) { if (top < items.length) items[top++] = t; }
}
```

Pools are per thread (one per room, one per Netty worker), so they need no synchronization. A pooled object that
crosses threads (a `Msg` from an IO thread to a room) is freed by the receiver into the receiver's pool. The pools
drift a little, but stay bounded by construction.

**As built:** the entities are the pool. `World` holds a fixed array of `Entity` and a stack of free slots, and a
slot's generation tells a reused slot from the entity that held it before. There is no generic `Pool` and no pooled
message, because the queues carry connections and the input is a `long`. A test holds the tick to zero allocation
in steady state ([07 §2](../detailed-design/07-threading-and-performance.md#2-allocation-discipline-in-the-tick-loop)).

### 2.3 Data-driven tables

Balance and rules live in tables of immutable rows, whose derived values are worked out once, never in the logic
that reads them. A bad table fails at start-up, never during a match.

**As built:**
- The simulation's tables are code in `sim`: `Content` (`StatTable`, `LevelTable`, `ShapeTable`, recovery and
  spawning) and `ClassTable`, where each class is a row of barrels, reload, view and body. They are built once, and
  tests hold their invariants (the level table's anchors, and D-22's limit on what a class keeps alive).
- `platform`'s tables are JSON files read at start (`Items`, `Packs`). Every field is checked, and a
  table that breaks a rule stops `platform` with exit code 2. The shop's offers are the database's (D-119):
  `Catalogue` holds their rules, which the admin API checks before it writes one.
- The first design's `TankTable`, `TankDef` and `ConfigValidator` do not exist.

### 2.4 Strategy for match modes

`MatchMode` (see [01-arena.md](../detailed-design/01-arena.md) §8) is the only polymorphic call inside the tick
loop. It is called a handful of times per tick, plus once per collision pair (`canDamage`). Keep implementations
free of allocation and cheap to branch. The mode's parameters (map size, base size, dominator count, time limit)
come from `modes/{mode}.json`.

**As built:** `handoff/MatchMode` is an enum, one definition that every process reads. Each mode's roster, map
size, shapes, length, kills to win, join window, rating and team size are in code. The room's lifecycle is
`MatchRules` (open, timed or made). The rules a mode adds are classes that the room calls at fixed points
(`Waves`, `Domination`, `Tag`, `Sandbox`), not a polymorphic interface; the interface in 01 §8.2 is the design.
There is no `modes/` directory.

### 2.5 Modifier pipeline (stats)

One representation (`StatModifier`) for equipment, boosts, class bonuses and temporary effects; one `recompute()`;
a `statsDirty` flag. Sources are tagged, so removing everything from one source is O(n) over a tiny array.

**As built:** not built, because there is only one source: equipment's bonus. It is a whole percent per stat,
carried in the ticket (`TankStats.setBonus`, D-37), and `TankStats.refresh` applies it after the class and the
points, behind a dirty flag. Boosts raise a match's rewards in `worker`, and never a stat (D-38).

### 2.6 Dirty flags and per-client diffs

Entities set bits in `dirtyMask` when a wire field that rarely changes does change. The encoder clears the bits
after the last client of the tick is encoded. Position is diffed implicitly, by "moved this tick".

**As built:** there is no `dirtyMask`. Each client's `ClientView` remembers what it was last sent. The encoder sends
the fields of a tank that differ from that, marked by an update mask, measured against the last frame sent
([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)).

### 2.7 Ticket / handoff

A handoff between processes (the platform to an arena) carries all the data the receiver needs inside a
short-lived, single-use j-redis key, which the receiver claims atomically. The receiver never reads the source of
truth. This is the pattern for join tickets, for the match a ticket names (D-20), and for a tournament match's
grant (`tgrant:`). An operator's commands to an arena go on the arena's pub/sub channel instead
(`arena-admin:{name}`, 04 §10).

**"Atomically" means a transaction, not `J.CAD`.** An earlier draft of this section named `J.CAD`, which is
compare-and-delete on a *string* and cannot return a hash. The handoff's payload is a hash, so the claim is
`MULTI · HGETALL · DEL · EXEC`: one round trip, and the read and the delete cannot be split. Reading and then
deleting as two commands is not single-use: two connections replaying one ticket can both read before either
deletes, and both are admitted. That version was written, and it passed a naive concurrency test. So the test was
rewritten to submit every claim before awaiting any of them, and it then reported all sixteen claims as winners.
See `handoff/TicketStore`.

### 2.8 Outbox / spool + idempotent consumer

A producer of events that must not be lost (an arena's results) writes each event to a local spool file **before**
it tries the network push, and deletes the spool entry when the push is acknowledged. The consumer removes
duplicates by id (`matches.match_uid` is unique, [06 §4](../detailed-design/06-persistence-mysql.md)). Together
these give at-least-once delivery with an exactly-once effect.

### 2.9 Conditional single-document updates instead of transactions

Team roles, the wallet, a tournament's state: express each invariant as a filter condition in `updateOne`. Treat
`modifiedCount == 0` as "precondition failed", and return a typed error. Reconciler jobs repair denormalised
caches.

**Superseded by MySQL ([D-3](../architecture/03-decision-log.md#d-3--mysql-is-the-system-of-record-mongodb-is-cancelled)).**
This was the MongoDB design's pattern. Today transactions hold the invariants (`Tx`, retried on a deadlock):
- rows are locked in one order (players lowest id first, a team before its players);
- unique keys give idempotency (the ledger's `idem_key`, `match_player`'s key);
- a typed refusal is returned when a precondition fails
  ([06](../detailed-design/06-persistence-mysql.md) §4 and §6).

A conditional `UPDATE … WHERE` still guards a change of state (§2.10).

### 2.10 State machines as data + guarded transitions

Tournaments, matches, rooms: an enum `state` field, a table of allowed transitions, and a single
`transition(entity, from, to, mutation)` helper that enforces `from` in the filter. Log every transition with its
ids.

**As built:** there is no generic helper. A state is a numbered constant (`TournamentRepository.REGISTRATION` to
`CANCELLED`). Each transition is a statement whose `WHERE` names the state it leaves, so a transition that finds
nothing to change is refused.

### 2.11 Event bus (in-process)

An `EventBus` in the platform with typed listeners (`onMatchApplied`, `onTeamChanged`), dispatched synchronously on
the caller's worker thread. No async magic: a listener that must do slow work submits it to the worker pool
itself. In the arena, room events are a plain list drained by `broadcast()`.

**As built:** there is no event bus. `platform`'s services call one another directly. What reaches a player is
published through `LobbyPush` on the store's channels (`push:{gatewayId}`, `push:all`), by `platform` and `worker`
alike. In the arena, a room's events are its `EventBuffer`, carried in the next snapshot.

### 2.12 Deterministic simulation and replays

`Room` takes a `Clock` and an `Rng`; tests use a fixed tick counter and a seeded random generator. `InputLog`
records `(tick, playerId, bytes)`. The replay tool feeds the log into a headless room and asserts that the state
hash of each tick matches the recorded one.

**As built:** the world's random generator is seeded (`World`, `Xorshift`), and tests drive a room tick by tick.
`InputLog` and the replay tool are not built
([01 §10](../detailed-design/01-arena.md#10-determinism-and-replays)).

## 3. Coding rules

- **No streams, no `Optional`, no boxing** in `sim`, in `protocol`, or on any path run every tick. They are fine in
  `platform`'s request handlers, where clarity beats nanoseconds.
- **Records are not a way to avoid allocation.** Java 21 still has no value types, so a record is a heap object.
  Use records for immutable configuration and message payloads, not for per-entity state
  ([07 §2](../detailed-design/07-threading-and-performance.md#2-allocation-discipline-in-the-tick-loop)).
- **Virtual threads in `platform` and `worker`, platform threads in `arena`.** A room thread is bound by the CPU,
  works against a deadline, and must never block, so virtual threads buy it nothing. Request handlers that wait on
  MySQL are exactly what virtual threads are for.
- Make classes and fields `final` by default, and package-private where possible.
- Prefer `int` ids to object references across ticks. Entities are recycled, and `(generation << 16) | slot`
  detects a stale reference.
- Keep the `ByteBuf` lifecycle explicit: whoever allocates a buffer releases it, except when it is handed to
  `Channel.write`, where Netty releases it.
- Every `catch` logs with context ids (`roomId`, `playerId`, `tick`) and either recovers or escalates. Never write
  an empty `catch`.
- Each `platform` module is a service class (`AuthService`, `TeamService`, …) over its repositories, not an
  interface: a second implementation was never needed.
- **Tests use the real stores, not fakes**: the **embedded j-redis** (`j-redis-embedded`, the real server
  in-process) and a local MySQL with the schema migrated by Flyway. Not an in-memory fake, because the
  transactional behaviour in
  [06 §4](../detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter) is the thing most worth
  testing, and a fake will not reproduce it.

## 4. Error handling and logging

- **Log levels:**

  | Level | Means |
  |---|---|
  | `ERROR` | needs a human |
  | `WARN` | an anomaly that healed itself (dropped inputs, skipped snapshots, a retry) |
  | `INFO` | lifecycle: a room created, a match ended, a player joined or left |
  | `DEBUG` | off in production |

- **Structured `key=value` suffixes**: `room=ffa-7 tick=120031 player=5f3… event=kill`. Logback writes through an
  async appender with an 8 192-entry queue and `neverBlock=true` (`common/src/main/resources/logback.xml`), so
  logging never blocks a thread.
- **Metrics** are served in the Prometheus text format at `/metrics`, on the loopback address set in
  `BACKEND_METRICS_ADDR` ([operations/01 §7](../operations/01-deploy.md#7-installing-a-machine)). No Prometheus
  server reads them: the platform's own watch does (D-86).
- **What is alerted on.** The first design named these alerts: tick p99 over 15 ms (NFR-1b), the overrun count
  rising, the spool's size above 0 for more than 60 s, the j-redis queue's depth above 10 k, the results backlog
  above 100. **As built**, the alerts are the watch's rules, in `platform/watch/Rules.java`
  ([operations/08](../operations/08-monitoring.md), D-86). For example, `TickSlow` fires when a room's tick p99
  stays above 15 ms for five minutes, and `ResultsBacklogGrowing` when the results backlog is above 100 and has
  grown for ten minutes.

## 5. Testing strategy

| Layer | Test |
|---|---|
| sim | unit tests for physics, collision pairs, the experience and level math, and each mode's win condition; a property test: N random ticks never give NaN or a position out of bounds |
| protocol | a round trip of encode and decode for every message; the decoder fuzzed with random bytes (it must never throw past the handler) |
| j-redis | covered by its own suite, 229 tests at 2.2.2; see [j-redis docs/12](../../j-redis-service/docs/12-testing-strategy.md) |
| arena | integration: an arena and an embedded j-redis started, 50 bot clients connected; the test asserts that snapshots arrive and that the tick stays within its budget |
| platform | module tests with an embedded j-redis and a local MySQL migrated by Flyway; result idempotency (a result applied twice gives one reward) |
| end-to-end | the live drill (`client/headless-drill.sh`): the C# headless client against the whole stack from a release, every scenario (44 at plan item 78; 45 today). It ran in clear and over TLS until D-116; since then it runs in clear alone, on Unity 2019's Mono, whose own TLS stops at 1.0, and `untrusted` is refused. And the soak, two hours of bots |
| client core | NUnit (148 tests at plan item 78, 185 at plan item 137, 191 at plan item 140, 196 at plan item 141), compiled for .NET Framework 4.5 and run on Unity 2019's Mono (`client/core-tests.sh`, D-116): every golden vector checked field by field, a scripted arena for what the real one cannot be made to do, and the own tank's steps against a trajectory the server wrote |
