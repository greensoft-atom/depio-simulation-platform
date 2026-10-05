# 01 — Code patterns and project structure

Conventions for writing the services. The architecture is in
[architecture/01](../architecture/01-system-topology.md); this is how it is laid
out in code.

## 1. Maven modules

One module per concern, and one deployable per process type
([D-4](../architecture/03-decision-log.md#d-4--split-by-workload-shape-not-by-business-domain)).

```
backend/
  pom.xml          parent: release 21, dependency and plugin versions
  common/          IntList, Xorshift, PhaseTimer, Metrics/MetricsServer, Secrets, Logs,
                   RefusedConfiguration
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
                   Tag, Sandbox), ArenaMain
  gateway/         the lobby WebSocket: auth handshake, pushes, rate limiting
  platform/        auth and guests, sessions, joins, the matcher and parties, teams,
                   tournaments, boards, shop, items, payments, social, goals, the pass;
                   the HTTP API and the admin API on the JDK's server
  worker/          result consumer, rewards, the tournament clock, seasons, retention,
                   leaderboard rebuild, ledger check, replica and backup watches
  persistence/     MySQL repositories, Flyway migrations (the baseline, D-75), HikariCP,
                   the pool's primary by epoch (PrimaryDataSource)
  tools/           TickBenchmark, BotClient (load test), LobbyClient, SoakJudge, and the
                   Apply, Purge and Rank benchmarks
```

**As built.** The design had a `content/` module of JSON files; the
simulation's content is code in `sim` (`Content`, `ClassTable`) until
something needs to change it without a release (Q-27). `platform`'s items,
shop and packs are JSON, read at start. `platform`'s modules (04) are built
as service classes, not packages of their own: `rooms` as the arena directory
and the tickets, the arena making rooms itself (D-20), and `notify` as the inbox
and `LobbyPush`.

`sim` having no IO dependency is the key structural decision: the whole
simulation runs headless in unit tests and in the tick benchmark.

`protocol` is shared with the client only through the **golden vectors**
([protocol-spike](../../protocol-spike/README.md)), not through a shared
artefact — the client is C#, so the contract is bytes, not types.

The data store is a **dependency, not a module**: `j-redis-client` from
[j-redis-service](../../j-redis-service/README.md), which is its own project
with its own release cycle.

### Dependencies

All from the offline bundle [`../../java21-offline/`](../../java21-offline/),
all verified Java 21 bytecode or older.

| Artifact | Version | Used by |
|---|---|---|
| io.netty (buffer, codec, common, handler, transport) | 4.2.18.Final | arena, gateway, tools |
| io.netty:netty-codec-http | 4.2.18.Final | gateway (WebSocket) |
| io.netty:netty-transport-classes-epoll, and netty-transport-native-epoll classifier `linux-x86_64` | 4.2.18.Final | arena (the classifier jar carries the `.so`) |
| com.jredis:j-redis-client, j-redis-common | 2.2.1 | handoff, platform, worker, tools; arena and gateway through handoff |
| com.jredis:j-redis-embedded | 2.2.1 | tests |
| com.mysql:mysql-connector-j | 26.7.0 | persistence |
| com.zaxxer:HikariCP | 7.1.0 | persistence |
| org.flywaydb:flyway-core, flyway-mysql | 13.7.0 | persistence |
| com.fasterxml.jackson.core:jackson-databind | 2.22.3 | handoff, platform, worker, gateway, tools |
| org.bouncycastle:bcprov-jdk18on | 1.86 | platform (Argon2id) |
| org.hdrhistogram:HdrHistogram | 2.2.2 | common (tick percentiles), tools |
| org.slf4j:slf4j-api / ch.qos.logback:logback-classic | 2.0.20 / 1.6.3 | all |
| org.jctools:jctools-core | 4.0.7 | arena (join, resume and leave queues) |
| org.junit.jupiter:junit-jupiter / org.assertj:assertj-core | 6.1.3 / 3.27.7 | tests |

**In the bundle and not used:** netty-tcnative (the arena's TLS is the JDK's
provider; nginx terminates it for the gateway and platform), Micrometer
(metrics are written by hand, `common/Metrics`), Caffeine, fastutil and JMH.

The Java 8 pins are gone: JCTools is on 4.x, Logback on 1.6.x, JUnit on 6.x.
One trap survives the move — **`netty-all` is still unusable as a dependency**,
because that artifact contains no classes. Depend on the modules above.

## 2. Patterns

### 2.1 Single-writer + message passing

Every mutable aggregate (a room, the j-redis dataset) has one owning thread.
Everything else sends messages:

```java
// arena: from Netty IO thread
room.post(msg);                          // MPSC offer, drop if full

// arena: j-redis callback on client IO thread
client.claim(ticketId).whenComplete((map, err) -> room.post(Msg.joinResolved(conn, map, err)));

// room thread
void drainInputs() { Msg m; while ((m = queue.poll()) != null) { handle(m); pool.free(m); } }
```

No `synchronized`, no `volatile` on domain state. If you find yourself
wanting a lock inside `sim`, the design is wrong.

**As built:** there is no `Msg` type and no `room.post`. The Netty threads hand
a room `Connection`s through JCTools MPSC queues (joins, resumes, leaves,
`RoomThread`), and the latest input through an atomic slot on the connection
(`Connection.latestInput`, a packed `long`), which the room reads each tick. The
sketch above is the shape, not the code.

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

Pools are per-thread (one per room, one per Netty worker) so they need no
synchronization. A pooled object crossing threads (Msg from IO thread → room)
is freed by the receiver into the receiver's pool — pools drift a little but
stay bounded by construction.

**As built:** the entities are the pool: `World` holds a fixed array of
`Entity` and a free-slot stack, and a slot's generation tells a reused slot
from the entity before it. There is no generic `Pool` and no pooled message,
since the queues carry connections and the input is a `long`; a test holds the
tick to zero allocation in steady state ([07 §2](../detailed-design/07-threading-and-performance.md#2-allocation-discipline-in-the-tick-loop)).

### 2.3 Data-driven tables

Balance and rules live in tables of immutable rows with their derived values
worked out once, never in the logic that reads them; a bad table fails start-up,
never a match.

**As built:** the simulation's tables are code in `sim` — `Content`
(`StatTable`, `LevelTable`, `ShapeTable`, recovery and spawning) and
`ClassTable`, a class a row of barrels, reload, view and body — built once,
their invariants held by tests (the level table's anchors, D-22's limit on what
a class keeps alive). `platform`'s tables are JSON read at start (`Items`,
`Catalogue`, `Packs`), every field checked, a table that breaks a rule stopping
`platform` with exit 2. The first design's `TankTable`, `TankDef` and
`ConfigValidator` do not exist.

### 2.4 Strategy for match modes

`MatchMode` (see [01-arena.md](../detailed-design/01-arena.md) §8) is the only
polymorphic call inside the tick loop and it is invoked a handful of times per
tick plus once per collision pair (`canDamage`). Keep implementations
allocation-free and branch-cheap. Mode parameters (map size, base size,
dominator count, time limit) come from `modes/{mode}.json`.

**As built:** `handoff/MatchMode` is an enum, one definition every process
reads: each mode's roster, map size, shapes, length, kills to win, join window,
rating and team size, in code. The room's lifecycle is `MatchRules` (open,
timed or made), and the rules a mode adds are classes the room calls at fixed
points (`Waves`, `Domination`, `Tag`, `Sandbox`), not a polymorphic interface;
the interface in 01 §8.2 is the design. There is no `modes/` directory.

### 2.5 Modifier pipeline (stats)

One representation (`StatModifier`) for equipment, boosts, class bonuses and
temporary effects; one `recompute()`; `statsDirty` flag. Sources are tagged
so removal by source is O(n) over a tiny array.

**As built:** not built, there being one source: equipment's bonus, a whole
percent a stat from the ticket (`TankStats.setBonus`, D-37), applied by
`TankStats.refresh` after the class and the points, behind a dirty flag. Boosts
raise a match's rewards in `worker`, never a stat (D-38).

### 2.6 Dirty flags and per-client diffs

Entities set bits in `dirtyMask` when a rarely-changing wire field changes;
the encoder clears them after the last client of the tick is encoded. Position
is diffed implicitly by "moved this tick".

**As built:** there is no `dirtyMask`. Each client's `ClientView` remembers what
it was last sent, and the encoder sends a tank's fields that differ from it, by
an update mask, measured against the last frame sent
([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)).

### 2.7 Ticket / handoff

Cross-process handoffs (platform → arena) carry all data needed downstream inside a
short-lived, single-use j-redis key, claimed atomically. The
receiver never reads the source of truth. This is the pattern for join
tickets, the match a ticket names (D-20) and a tournament match's grant
(`tgrant:`). An operator's commands to an arena go on its pub/sub channel
instead (`arena-admin:{name}`, 04 §10).

**Atomically means a transaction, not `J.CAD`.** An earlier draft of this
section named `J.CAD`, which is compare-and-delete on a *string* and cannot
return a hash. The handoff payload is a hash, so the claim is
`MULTI · HGETALL · DEL · EXEC`: one round trip, and the pair is indivisible.
Reading then deleting as two commands is not single-use — two connections
replaying one ticket can both read before either deletes, and both are
admitted. That version was written and it passed a naive concurrency test, so
the test was rewritten to submit every claim before awaiting any of them; it
then reported all sixteen as winners. See `handoff/TicketStore`.

### 2.8 Outbox / spool + idempotent consumer

Producers of must-not-lose events (arena results) write to a local spool file
**before** attempting the network push and delete the spool entry on ack.
Consumers dedupe by id (`matches.match_uid` is unique, [06 §4](../detailed-design/06-persistence-mysql.md)). Together this gives
at-least-once delivery with exactly-once effect.

### 2.9 Conditional single-document updates instead of transactions

Team roles, wallet, tournament state: express invariants as filter conditions
in `updateOne`; treat `modifiedCount == 0` as "precondition failed" and return
a typed error. Reconciler jobs repair denormalised caches.

**Superseded by MySQL ([D-3](../architecture/03-decision-log.md#d-3--mysql-is-the-system-of-record-mongodb-is-cancelled)).**
This was the MongoDB design's. Invariants are held by transactions (`Tx`,
retried on a deadlock): rows locked in one order (players lowest id first, a
team before its players), unique keys as idempotency (the ledger's
`idem_key`, `match_player`'s key), and a typed refusal when a precondition
fails ([06](../detailed-design/06-persistence-mysql.md) §4 and §6). A
conditional `UPDATE … WHERE` still guards a state change (§2.10).

### 2.10 State machines as data + guarded transitions

Tournaments, matches, rooms: an enum `state` field, a table of allowed
transitions, and a single `transition(entity, from, to, mutation)` helper that
enforces `from` in the filter. Log every transition with ids.

**As built:** no generic helper. A state is a numbered constant
(`TournamentRepository.REGISTRATION` to `CANCELLED`), and each transition is a
statement whose `WHERE` names the state it leaves, so one that finds nothing to
change is refused.

### 2.11 Event bus (in-process)

`EventBus` in platform with typed listeners (`onMatchApplied`, `onTeamChanged`),
dispatched synchronously on the caller's worker thread. No async magic; if a
listener must do slow work, it submits to the worker pool itself. In the
arena, room events are a plain list drained by `broadcast()`.

**As built:** no event bus. `platform`'s services call one another directly;
what reaches a player is published through `LobbyPush` on the store's channels
(`push:{gatewayId}`, `push:all`), by `platform` and `worker` alike. In the
arena a room's events are its `EventBuffer`, carried in the next snapshot.

### 2.12 Deterministic simulation and replays

`Room` accepts a `Clock` and `Rng`; tests use a fixed tick counter and seeded
RNG. `InputLog` records `(tick, playerId, bytes)`; the replay tool feeds it
into a headless room and asserts state hashes per tick match the recorded ones.

**As built:** the world's RNG is seeded (`World`, `Xorshift`), and tests drive
a room tick by tick. `InputLog` and the replay tool are not built
([01 §10](../detailed-design/01-arena.md#10-determinism-and-replays)).

## 3. Coding rules

- **No streams, no `Optional`, no boxing** in `sim`, `protocol` or any
  per-tick path. They are fine in `platform` request handlers, where clarity
  beats nanoseconds.
- **Records are not a way to avoid allocation.** Java 21 still has no value
  types, so a record is a heap object. Use them for immutable configuration and
  message payloads; not for per-entity state
  ([07 §2](../detailed-design/07-threading-and-performance.md#2-allocation-discipline-in-the-tick-loop)).
- **Virtual threads in `platform` and `worker`, platform threads in `arena`.**
  A room thread is CPU-bound against a deadline and must never block, so
  virtual threads buy it nothing. Request handlers that wait on MySQL are
  exactly what they are for.
- `final` classes and fields by default; package-private where possible.
- Prefer `int` ids over object references across ticks — entities are recycled,
  and `(generation << 16) | slot` detects a stale reference.
- Explicit `ByteBuf` lifecycle: whoever allocates releases, except when handed
  to `Channel.write`, where Netty releases.
- Every catch logs with context ids (`roomId`, `playerId`, `tick`) and either
  recovers or escalates. Never an empty catch.
- Each `platform` module is a service class (`AuthService`, `TeamService`, …)
  over its repositories, not an interface: a second implementation was never
  needed. Tests use the **embedded j-redis**
  (`j-redis-embedded`, the real server in-process) and a local MySQL with
  Flyway-migrated schema — not an in-memory fake, because the transactional
  behaviour in
  [06 §4](../detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter)
  is the thing most worth testing and a fake will not reproduce it.

## 4. Error handling and logging

- Log levels: `ERROR` = needs a human; `WARN` = self-healed anomaly (dropped
  inputs, skipped snapshots, retry); `INFO` = lifecycle (room created, match
  ended, player joined/left); `DEBUG` off in production.
- Structured key=value suffixes: `room=ffa-7 tick=120031 player=5f3… event=kill`.
  Logback async appender with an 8 192-entry queue, `neverBlock=true`.
- Metrics in the Prometheus text format at `/metrics`, on the loopback address
  in `BACKEND_METRICS_ADDR` ([operations/01 §7](../operations/01-deploy.md#7-installing-a-machine)).
  Alert on: tick p99 > 15 ms (NFR-1b), overrun count rising,
  spool size > 0 for > 60 s, j-redis queue depth > 10 k, results backlog > 100.

## 5. Testing strategy

| Layer | Test |
|---|---|
| sim | unit tests for physics, collision pairs, XP/level math, each mode's win condition; property test: N random ticks never NaN/out-of-bounds |
| protocol | round-trip encode/decode for every message; fuzz decoder with random bytes (must never throw past the handler) |
| j-redis | already covered by its own suite, 229 tests at 2.2.1; see [j-redis docs/12](../../j-redis-service/docs/12-testing-strategy.md) |
| arena | integration: start arena + embedded j-redis, connect 50 bot clients, assert snapshots arrive and tick stays < budget |
| platform | module tests with embedded j-redis and a Flyway-migrated local MySQL; result idempotency (apply twice → one reward) |
| end-to-end | the live drill (`client/headless-drill.sh`): the C# headless client against the whole stack from a release, every scenario (44 at plan item 78), plaintext and TLS; and the soak, two hours of bots |
| client core | NUnit (148 tests at plan item 78): every golden vector field by field, a scripted arena for what the real one cannot be made to do, the own tank's steps against a trajectory the server wrote |
