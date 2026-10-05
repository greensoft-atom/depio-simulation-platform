# 09 — Study note: the iohao framework (third party)

The framework published by the iohao organisation ([github.com/iohao](https://github.com/iohao)),
called here by its author's name: the project does not use the word its own
name contains. Source studied: its main repository (version 21.34 on `main` at
the time of reading), its documentation site under iohao.github.io, the
`light-room` widget, the thread executor sources, the broker cluster sources,
and its examples repository.
This note records what the framework actually does, verified against source
where possible, and what it means for our own design.

> **Three premises have changed since this was written** (noted 2026-09-23).
> It was written against Java 8, MongoDB and a process then called `hub`. The
> project is now on **Java 21**
> ([D-2](../architecture/03-decision-log.md)), on **MySQL**
> ([D-3](../architecture/03-decision-log.md#d-3--mysql-is-the-system-of-record-mongodb-is-cancelled)),
> and that process is **`platform`**.
>
> That inverts the verdict. §7 rejected iohao's JDK 21 requirement as
> disqualifying, and that reason no longer exists — **[§8](#8-what-the-java-21-move-changes)
> is now the section that applies, not §7's first line.** Whether to use it is
> an open question, not a settled no, and re-evaluating it is its own piece of
> work. Everything here about what the framework *does* was verified against
> source and still stands; the vocabulary has been brought up to date.

## 1. What it is

A Java server framework for long-connection applications (titles, IoT). One
codebase serves TCP, WebSocket and UDP clients. Business logic is written as
"actions" in annotated controllers, addressed by a `cmd / subCmd` pair. It
ships a three-role distributed topology that clusters without external
middleware.

Marketing claims worth translating:

| Claim | What it means in the code |
|---|---|
| "lock-free" | Requests for one `userId` always run on the same single-threaded executor, so per-user state needs no locks. Cross-user state is handled by "domain events" (a Disruptor ring buffer) or by you. It is *not* lock-free data structures. |
| "dependency-free" | No Redis / MQ / ZooKeeper / database required for clustering. It **does** depend on libraries: Netty, SOFABolt (Alipay RPC), LMAX Disruptor, jprotobuf + protobuf-java, scalecube-cluster (gossip), JCTools, Lombok. |
| "decentralized cluster" | Brokers gossip membership with scalecube; every broker knows every logic/external server; no master node. |
| "11.52 M business ops/s single thread" | A micro-benchmark of the action dispatch pipeline, not a simulation number. |

**It requires JDK 21.** The source uses sealed classes, records, `instanceof`
patterns and virtual threads, and the project policy (changelog) is to track
the newest JDK LTS; there has never been a Java 8 line. That was recorded here
as the disqualifying fact. **It no longer disqualifies anything** — the project
moved to Java 21, and the offline bundle for iohao 21.34 is already built.

## 2. Architecture

```
clients ──TCP/WS/UDP──► External server(s)  ──SOFABolt──►  Broker cluster  ──SOFABolt──►  Logic server(s)
                        (connections,                      (routing, LB,                  (actions, business
                         UserSessions,                      gossip membership)             state, pushes)
                         userId binding)
```

- **External server** (`external/external-netty`): Netty pipeline per
  protocol; `UserSessions` keeps `channel ↔ userId`; a login action on a logic
  server calls back `settingUserId` to bind the session. It exposes
  `broadcast(msg)`, `getUserSession(userId)`, online count, and hooks
  (`UserHook` for online/offline).
- **Broker** (`net-bolt/bolt-broker-server`): a SOFABolt `RpcServer`.
  Logic and external servers connect *to* it as Bolt clients and register
  their `tag` and the list of `cmdMerge` values they handle. Routing table
  (`LogicBrokerClientLoadBalanced`): `cmdMerge → BrokerClientRegion(tag)` and
  `tag → region`; within a region the request is load-balanced across logic
  servers of the same tag (default round-robin; a "strict" region variant
  exists). Requests carry `userId`, `cmdMerge`, trace id; responses are routed
  back to the originating external server, which finds the user's channel.
- **Broker cluster** (`bolt/broker/cluster/BrokerClusterManager`): uses
  `io.scalecube.cluster` (SWIM-style gossip over TCP). Config is a list of
  seed addresses plus a gossip port; each broker publishes its metadata; when
  membership changes, a `BrokerClusterMessage` listing all brokers is pushed
  one-way to every connected logic and external server so they can connect to
  all brokers. Brokers are stateless; no consensus is involved.
- **Logic server** (`net-bolt/bolt-client` + `common/common-core`): extends
  `AbstractBrokerClientStartup`, builds a `BarSkeleton` (the action
  framework: scanning `@ActionController` classes, parameter parsing, codec,
  in/out interceptors like `DebugInOut`, `TraceIdInOut`, `ThreadMonitorInOut`,
  `StatActionInOut`), and connects to the broker.
- **Same-process affinity**: if external, broker and logic servers run in the
  same JVM (`run-one` module), Bolt calls short-circuit through memory.
- **Distributed event bus** (`EventBusMessageBrokerProcessor`): publish a
  typed event; it is fanned out through brokers to subscribed logic servers
  in other processes or machines. At-most-once, no persistence.
- **Inter-server calls**: `invokeModuleMessage` (request/response to a logic
  server of another tag through the broker), `invokeExternalModuleContext`
  (ask external servers, e.g. "is user online"), broadcast contexts
  (`BroadcastContext`, `BroadcastOrderContext` for ordered pushes).

## 3. Threading model (verified in source)

`common-micro-kit/.../concurrent/executor`:

```java
abstract sealed class AbstractThreadExecutorRegion implements ThreadExecutorRegion {
    final ThreadExecutor[] threadExecutors;               // each = ThreadPoolExecutor(1,1, LinkedBlockingQueue)
}
final class UserThreadExecutorRegion extends AbstractThreadExecutorRegion {
    UserThreadExecutorRegion() { super("User", RuntimeKit.availableProcessors2n); executorLength = availableProcessors2n - 1; }
    public ThreadExecutor getThreadExecutor(long userId) { return threadExecutors[(int) (userId & executorLength)]; }
}
```

So:

- The number of user executors is the largest power of two ≤ CPU count
  (12 cores → 8 executors). Mapping is `userId & (n − 1)`. Many users share
  one thread; one user never moves between threads, also across re-login.
- Each executor is a single thread with an **unbounded** `LinkedBlockingQueue`.
  A slow action blocks every other user hashed to that thread; there is no
  backpressure, only a queue-depth monitor plugin.
- `SimpleThreadExecutorRegion`: same shape, `availableProcessors` threads, for
  CPU tasks keyed by any index (docs suggest keying by `roomId`).
- `UserVirtualThreadExecutorRegion`: same hashing, but each slot is a virtual
  thread executor; actions annotated `@VirtualThread` go here so DB calls do
  not stall the user threads. Since iohao 21, inter-server blocking calls
  use virtual threads too.
- The selection hook `RequestMessageClientProcessorHook.processLogic` decides
  which executor an incoming request runs on; the docs show overriding it to
  pick `getSimpleThreadExecutor(roomId)` for room titles.

Ordering guarantee: per user, FIFO. There is **no** guarantee about the order
between two users' actions touching the same room unless you route both to the
room's executor.

## 4. Domain events

`widget/light-domain-event`: one LMAX `Disruptor<EventDisruptor>` per event
class (topic), consumers registered via annotations, published through
`DomainEventPublish`. Purpose: move multi-player mutations onto one consumer
thread per topic so they are serialized without locks, and decouple producers
from side effects. It is in-process only; the distributed event bus is the
cross-process counterpart.

## 5. The room widget (`widget/light-room`)

Interfaces, not a simulation:

- `Room`: `Map<Long, Player> playerMap`, `playerSeatMap`, `roomId`,
  `spaceSize`, creator, robot/real player split, `ifPlayerExist`,
  `ofRangeBroadcast()` (build a broadcast to all player ids in the room via
  `RangeBroadcaster`).
- `RoomService`: `roomMap`, `userRoomMap`, add/remove room and players.
- `MatchFlowService = MatchFixedService (createRoom, createPlayer, enterRoom, quitRoom, dissolveRoom, ready)
  + MatchStartService (startMatchVerify, startMatch)`.
- `OperationHandler { processVerify(ctx); process(ctx); }` with an
  `OperationFactory` mapping an operation code to a handler: the pattern for
  "player does X in room" actions.

This is a **lobby / turn-based / seat-based** room model (card titles, matches
with seats, ready flags). There is no tick loop, no world state, no snapshot
system; `fxglSimpleDemo` in the examples shows position sync by forwarding
each player's move action as a broadcast, which is fine for a handful of
players and not a model for a 150-player physics arena.

## 6. Things worth borrowing

| iohao idea | Where it lands in our design |
|---|---|
| Split "connection holder" from "logic holder", with a broker in between, so logic servers scale independently | Our platform/arena split already does this for the meta layer vs realtime. We deliberately do **not** put a broker between client and arena: an extra hop costs latency on every input/snapshot and buys nothing on one box. |
| Deterministic executor per key (`userId & mask`) | Platform request handling: route each player's lobby requests to `workers[playerId & 7]` so per-player meta state never needs locks. Cheap to add to platform's request handling. Rooms already have a dedicated thread (a stronger form of the same idea). |
| `@VirtualThread`-style separation of blocking work | **Now available to us too.** `platform` handles requests on virtual threads and blocks on MySQL there; the rule that survives is that neither a Netty IO thread nor a room thread ever touches the database. |
| `cmd/subCmd` routing + generated client SDKs + "code is the debug doc" console output | Our lobby JSON protocol could adopt a numeric `cmd` scheme and a small generator for TypeScript/C# message classes from `protocol` definitions. Low priority; note it in the implementation guide as a later tool. |
| Trace id carried through every hop (`TraceIdInOut`) | Put a `traceId` in the join ticket and in result messages so a player's path (platform → arena → results → MySQL) can be grepped across logs. |
| Action in/out interceptors (timing, stats, thread monitor) | Same as our per-phase tick histograms and platform request timing; keep it as a plugin list in `platform.net`. |
| Domain events (single consumer thread per topic) | Our platform event handling is synchronous; if a listener becomes hot (achievements, stats aggregation), give that topic its own single-consumer queue rather than a lock. |
| Robot / stress-test module | Matches our `tools/botclient`; iohao's shows the value of shipping the bot with the framework from milestone 3. |

## 7. Things not to borrow (for this project)

- ~~**JDK 21 requirement.**~~ No longer a reason: the project runs on Java 21.
- **Broker + gossip cluster.** Designed for many machines; on one self-hosted
  box it adds SOFABolt, scalecube and a routing hop with no benefit. Our
  store registry (`arena:*` hashes with TTL) is the single-box equivalent of
  service discovery — and is now built (`ArenaDirectory`).
- **Unbounded per-thread queues.** Our rooms use bounded MPSC queues and drop
  stale inputs; platform's request handling should also be bounded with a rejection
  path, not a `LinkedBlockingQueue`.
- **Protobuf/jprotobuf as the realtime wire format.** For 25 Hz delta
  snapshots we want hand-packed fixed-point fields; protobuf varints and
  field tags would roughly double bytes and allocate on decode.
- **Room widget's `Map<Long, Player>` model** for the arena. Fine for the
  platform's matchmaking parties and sandbox room codes; not for the world.

## 8. What the Java 21 move changes

This was written as a hypothetical and is now the live question.

iohao would be a reasonable base for the **platform side** (auth, teams,
shop, tournaments as actions; its distributed event bus instead of store
pub/sub; external servers holding lobby connections). The **arena** would
still be a custom room-thread simulation, connected either directly to clients
(as in our design) or as a logic server behind iohao's external server with
the `roomId`-keyed executor hook and a custom tick scheduler. The realtime
design (01–03) would be unchanged; 04 and 05 would shrink.

**What has been built since changes the trade.** `platform` already has
accounts, Argon2id logins, sessions and the join handoff, and `worker` already
has the result pipeline — all tested against real stores. Adopting a framework
now means replacing working, tested code, not filling an empty space. The
honest case for it would have to come from what is *still missing* — teams,
tournaments, matchmaking, the lobby transport — rather than from what it would
have saved six weeks ago.

## 9. Offline jars

The full 21.34 artifact closure (123 jars, all dependencies, sources jars,
verified against Maven Central) was bundled in the repository until
2026-09-26. It was removed then: nothing builds against it, and its Maven
coordinates carry the word the project does not use, which a bundle cannot
rename. It is in the history: `git ls-tree 04847d8 background/` shows its directory,
and `git checkout 04847d8 -- <that directory>` restores it if the framework is
ever reconsidered. It requires JDK 21.

## 10. Sources

- Repository and README: the framework's main repository, under https://github.com/iohao
- Docs: the documentation site under https://iohao.github.io, English pages
  `docs/overall/thread_executor`, `docs/overall/broker_intro` and
  `docs/overall/logic_intro`
- Examples: the examples repository under https://github.com/iohao (its room
  example, its FXGL demo and its simple example)
- Source files read: `common/common-micro-kit/.../concurrent/executor/*.java`,
  `net-bolt/bolt-broker-server/.../cluster/BrokerClusterManager.java`,
  `.../balanced/LogicBrokerClientLoadBalanced.java`,
  `external/external-core/.../session/UserSessions.java`,
  `widget/light-room/.../`: the room, its two room services and `RoomBroadcastEnhance`,
  `widget/light-domain-event/.../DisruptorManager.java`, root `pom.xml`.
- Sibling project by the same author using Aeron instead of Bolt: https://github.com/iohao/ionet
