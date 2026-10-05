# backend

Implementation of the realtime arena backend
([design docs](../docs/README.md)). Java 21 and Maven, built offline: Temurin 21 and Maven 3.9,
in `/opt/jdk21` and `/opt/maven` on the development machine and committed in
[`../vendor/`](../vendor/VERSIONS.md); every library from [`java21-offline`](../java21-offline/README.md)
(its `repository/` has the shape of `~/.m2/repository`), except j-redis 2.2.1, which is installed
into the local repository from [`../j-redis-service`](../j-redis-service/README.md) first.
[`../build-offline.sh`](../build-offline.sh) builds it all, the release included, from the
repository alone ([09](../docs/detailed-design/09-release-and-packaging.md)).

**Status (2026-10-04).** Phases 1 to 5 of the [plan](../docs/plan.md#3-phases) are built and
drilled on the development machine: the public arena and the timed modes; matchmaking with
parties and a confirm step; accounts, guests and sessions; teams, tournaments, friends and the
inbox; score and rating boards with seasons; the economy, with equipment, item levels, boosts,
gems for money through a simulated provider, the season pass and skins; replicated stores and
MySQL with scripted failover, rehearsed under load; backups proved by restoring them. What waits
is measurement on the production machines (Q-1, Q-3): the load test to 10 000 (Phase 4), and Phase
6's soak at target load and capacity with a machine lost.
**1 020 tests (2026-10-04).** The client, the engine-free C# core, its headless driver and the Unity
scripts, is in [client/](../client/README.md).

## Modules

Each module has its own README with its classes, configuration and tests.

| Module | What it is | Main classes |
|---|---|---|
| [common](common/README.md) | what every process shares | `Metrics`, `MetricsServer`, `Secrets`, `Arguments`, `RefusedConfiguration`, `Logs`, `PhaseTimer`, `IntList`, `Xorshift` |
| [protocol](protocol/README.md) | the match protocol's bytes, and its reference client | `Wire`, `WireReader`, `SnapshotWriter`, `SnapshotReader`, `ClientWorld`, `ClientMessage` |
| [sim](sim/README.md) | the tick loop and the content tables; no networking, no IO | `World`, `Room`, `Entity`, `SpatialHash`, `Content`, `StatTable`, `LevelTable`, `ShapeTable`, `ClassTable`, `PhraseTable`, `MazeGenerator` |
| [arena](arena/README.md) | the match server: rooms, connections, snapshots, results | `ArenaMain`, `ArenaServer`, `RoomRegistry`, `RoomThread`, `ClientView`, `SnapshotEncoder`, `MatchFrameHandler`, `MatchTally`, `MatchResultPublisher`, `ArenaAnnouncer` |
| [handoff](handoff/README.md) | what the processes pass each other through the store | `Ticket`, `TicketStore`, `SessionStore`, `ArenaDirectory`, `LeaderboardStore`, `LobbyPush`, `StoreClients`, `MatchMode`, `MatchResultCodec`, `MatchResultQueue`, `MatchResultStream`, `SandboxHolds`, `TournamentGrants` |
| [persistence](persistence/README.md) | MySQL: the schema, the pool, every transaction | `Database`, `DatabaseSettings`, `Tx`, `AccountRepository`, `EconomyRepository`, `MatchResultRepository`, `TeamRepository`, `TournamentRepository`, `RatingBoards`, `SeasonRepository`, `PaymentRepository` |
| [platform](platform/README.md) | the HTTP API, the matcher and the admin API | `PlatformMain`, `PlatformHttpServer`, `AdminServer`, `AuthService`, `PasswordHasher`, `LoginThrottle`, `JoinService`, `QueueService`, `Matchmaker`, `PartyService`, `ShopService`, `PaymentService` |
| [worker](worker/README.md) | results into MySQL, and the scheduled jobs | `WorkerMain`, `MatchResultConsumer`, `RewardRules`, `EloRating`, `AccountLevels`, `TournamentScheduler`, `SeasonKeeper`, `Retention`, `LedgerCheck`, `LeaderboardRebuild` |
| [gateway](gateway/README.md) | the client's lobby connection, JSON over WebSocket | `GatewayMain`, `GatewayServer`, `LobbyHandler`, `ConnectionRegistry`, `PlatformClient`, `Pushes`, `FrameLimit`, `EdgeCertificate` |
| [tools](tools/README.md) | benchmarks, bots and the soak's judge | `TickBenchmark`, `BotClient`, `LobbyClient`, `ApplyBenchmark`, `PurgeBenchmark`, `RankBenchmark`, `SoakJudge` |
| [scripts](scripts/README.md) | the release and its check, backups, restores and promotions | `make-release.sh`, `check-release-el9.sh`, `backup-*.sh`, `restore-*.sh`, `promote-store.sh`, `promote-mysql.sh` |
| [deploy](deploy/README.md) | what a machine is given | systemd units, env examples, nginx, MySQL configuration |

`handoff` exists so that `arena` can use the ticket without inheriting a
database driver. `arena` is specified never to touch MySQL, and a module graph
enforces that better than a sentence does: of this repository's modules `arena` depends on
`handoff`, `sim` and `protocol` only, so the release's `lib/arena` holds none of MySQL, Flyway,
HikariCP or Bouncy Castle.

The schema is two Flyway migrations,
[`V1__schema.sql`](persistence/src/main/resources/db/migration/V1__schema.sql) and
[`V2__seed.sql`](persistence/src/main/resources/db/migration/V2__seed.sql), squashed on 2026-10-04
from the 34 before them
([D-75](../docs/architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
Flyway runs at every `platform` and `worker` start.

## Commands

Build and test everything, offline (the persistence and platform tests need the [MySQL](#mysql) below):

```bash
cd backend && export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH && /opt/maven/bin/mvn -o install
# one module and what it needs:  /opt/maven/bin/mvn -o -pl platform -am install
```

`com.jredis:*:2.2.1` is not in the offline bundle: `cd ../j-redis-service && mvn install -DskipTests`
once, first (`make-release.sh` and `build-offline.sh` do it themselves).

Or everything from the repository alone, with no network and nothing installed but bash,
coreutils, findutils, sed, tar and gzip: the committed JDK and Maven, `java21-offline` as the only
repository and a local repository of its own; j-redis, then the backend with its tests, then the
release
([09 §3](../docs/detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)):

```bash
./build-offline.sh                  # from the repository's root; SKIP_TESTS=1 skips the tests
```

A release, in `target/release/backend-<version>/` and a `.tar.gz`: one `lib/` a process type, the
Java 21 runtime they run on (linked by `jlink` from `vendor/jdk-21`), j-redis, MySQL 8.4 and nginx
(from `vendor/`), the units, their configuration, the env examples and the scripts
([deploy README](deploy/README.md#the-release)). It builds j-redis first. `check-release-el9.sh`
then runs it in RHEL 9's own userspace with nothing installed (it needs Docker):

```bash
cd backend && MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh
scripts/check-release-el9.sh [release-dir]       # then, in backend/; default target/release/backend-*/
```

The live drill: the whole stack from a release, on the release's own runtime and j-redis, on its
own ports (j-redis 6390, platform 8093, gateway 8094, arena 9011, platform's metrics 9195 and admin
API 9196), driven by the client's
headless driver through named scenarios: by default `play`, `resume`, `badticket` and
`lifecycle`; others, such as `duel`, `party`, `sandbox`, `tournament`, `purchase` and `pass`, by
name (`client/Headless/Program.cs` has every one):

```bash
TMPDIR=<scratch> client/headless-drill.sh [scenario ...]        # from the repository's root
TLS=1 TMPDIR=<scratch> client/headless-drill.sh play untrusted  # the arena over TLS
TMPDIR=<scratch> backend/scripts/backup-drill.sh                # the backups' own drill; RELEASE=<release> on its MySQL 8.4
```

Benchmarks, from `backend/` with the tools jar built:

```bash
# tick cost: [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed], defaults 150 1500 20000 22000 1 0
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar
# results a second by thread count; wipes the database it is given, which must end in _test
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 400 1,2,4,8
# retention's purge rate: [matches] [players a match], defaults 200000 4; wipes as above
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.PurgeBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 200000 1
# a rating board's place: [accounts] [listed] [reads], defaults 200000 5000 21; wipes as above
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 200000 5000 21
```

The client's own tests: `cd client && DOTNET_NOLOGO=1 /opt/dotnet/dotnet test`. Running each
process by hand is [below](#running-it).

## Login, and what it costs

Register → login → ticket → play runs end to end in `PlatformToArenaTest`,
against a real MySQL and a real j-redis, with a plain `Socket` speaking the
real wire format. Every part of that chain had passing tests before the chain
itself had ever run once.

Passwords are Argon2id (Bouncy Castle — pure Java, nothing to install).
**Measured on this VM: 88 ms per hash** at m=19 MiB, t=2, p=1. That number is
the tightest CPU limit in the system:

| | |
|---|---|
| Cost per password login | 88 ms, and 19 MiB held for the duration |
| Concurrent hashes allowed | 8 — Argon2 is memory-hard, so an unbounded burst is an `OutOfMemoryError`, not a queue |
| Sustained password logins | **~90/s per process**, using 8 cores |
| Cost per session resume | one `HGET` |

Which is fine, because a session lasts a day and the common path is a resume —
and is *not* fine if 50 000 players log in inside a minute. Two consequences
are already in the code: session TTLs carry ±10 % jitter, so a launch window
does not reproduce itself as a login herd a day later; and the stored hash
carries its own parameters, so the cost can be raised later without
invalidating every password at once.

## The join ticket

An arena has no database connection and never gets one, so a player's identity
arrives in a short-lived j-redis key that `platform` writes and the arena
consumes: `MULTI · HSET ticket:{id} … · EXPIRE 60 · EXEC`, claimed with
`MULTI · HGETALL · DEL · EXEC`.

The transaction is the point. `HGETALL` then `DEL` as two commands is **not**
single-use — two connections replaying one ticket both read before either
deletes, and both join. The first concurrency test here passed against exactly
that bug, because sixteen threads started one at a time on a loaded machine
never actually overlapped. Submitting all sixteen claims before awaiting any of
them reports 16 winners against the racy version and 1 against the transaction.

The claim is a network round trip, so the arena does not wait for it on a Netty
thread; the reply is handed back to the connection's event loop, and the join
continues there (T-1: continuing on the store's thread stalled every other store
operation behind a room being built). A
refused join gets `Kick` with a reason
([02 §3](../docs/detailed-design/02-networking.md#3-messages)) before the socket
closes, because "bad ticket", "no room" and "old client" need three different
responses from the client.

There is no ticket-free join path, not even for development. A bypass flag is a
production hole waiting for the day someone ships with it set.

## MySQL

The persistence tests run against a **real MySQL** (8.4 LTS in production, the release's own; 8.0
on the development machine), not a fake, because
everything worth testing there is transactional — `FOR UPDATE` ordering,
a plain insert whose duplicate-key error is the idempotency check, and the
`CHECK` constraint as the last line of defence. (It was `INSERT IGNORE` until
2026-09-25, which turned a missing player and an out-of-range value into
silent "already applied" and silent clamping — see
[06 §4](../docs/detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter).) A fake reproduces none of it, so tests against one would pass while
production broke.

```bash
apt-get install -y mysql-server            # 8.0.46 on Ubuntu 24.04
mysql -e "CREATE DATABASE backend_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
          CREATE USER 'backend'@'localhost' IDENTIFIED BY 'backend-dev-password';
          GRANT ALL ON backend_test.* TO 'backend'@'localhost';"
```

Override with `JDBC_URL`, `DB_USER` and `DB_PASSWORD`. Flyway migrates the
schema before each test class. On 2026-10-05 they were also run against a server from the
release's MySQL 8.4 ([09 §7](../docs/detailed-design/09-release-and-packaging.md#7-how-it-is-verified)).

What those tests actually establish:

| Test | Result |
|---|---|
| 12 concurrent purchases, balance 1 000, price 200 | **exactly 5 applied**, balance 0, never negative |
| 8 concurrent deliveries of one match result | **exactly 1 applied** |
| 8 concurrent *different* matches sharing two players | **all 8 applied, no deadlock** — failed 3 runs of 3 before the players were locked up front |
| Same result delivered 3× | credited once |
| Ledger re-derived vs stored balance | equal to the coin |
| Direct `UPDATE` that would overdraw | rejected by `CHECK` |

## Running it

Every bot takes the path a client takes: register, log in, open the lobby, ask
for a match, then connect to the arena it was given. So a run needs the whole
chain: [j-redis](../j-redis-service/README.md), [MySQL](#mysql) (`platform` and
`worker` use its development defaults unless `BACKEND_DB_*` says otherwise),
and one of each process. On one machine:

```bash
java -jar j-redis-server-2.2.1-all.jar --port 6380

# platform: bind host, port, store host, store port
java -cp '<release>/lib/platform/*' com.backend.platform.PlatformMain 127.0.0.1 8080 127.0.0.1 6380
# worker: store host, store port, worker id
java -cp '<release>/lib/worker/*' com.backend.worker.WorkerMain 127.0.0.1 6380 worker-1
# gateway: bind host, port, platform URL, store host, store port, gateway id
java -cp '<release>/lib/gateway/*' com.backend.gateway.GatewayMain \
     127.0.0.1 8081 http://127.0.0.1:8080 127.0.0.1 6380 gateway-1
# arena: port, map size, max players, shapes, rooms, store host, store port, name,
#        match seconds (0: continuous), spool directory, bind host, advertised host
java -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/java.nio=ALL-UNNAMED \
     -cp '<release>/lib/arena/*' com.backend.arena.ArenaMain \
     9001 5700 150 1500 4 127.0.0.1 6380 arena-1 0 ./spool/arena-1 127.0.0.1 127.0.0.1

# headless load: bots, seconds, platform URL, lobby URL
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.BotClient \
     120 15 http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
```

`<release>` is what `scripts/make-release.sh` builds,
`target/release/backend-<version>`; the processes can run on its own runtime,
`<release>/runtime/bin/java`, and the store's server is `<release>/jredis/lib/j-redis-server.jar`.
The arena serves plaintext unless given a
keystore ([operations/01 §8](../docs/operations/01-deploy.md#8-tls-for-match-traffic)),
and the bots follow whatever the grant says.

### End to end, 120 bots for 15 s

Measured before the bots went through the lobby: every bot claimed a real ticket
from j-redis first.

```
welcomes 120   disconnects 0   kicks 0
snapshots 27531   bytes 1804521
mean frame 65.5 B   per bot 15.30 snapshots/s, 0.98 KB/s (payload only)
```

Exactly the 15 Hz target, no disconnects, and a mean frame of 65.5 bytes against
the encoder benchmark's 68.3 — the wire format behaves over a real socket as it
does in isolation. `DBSIZE` was 0 afterwards: all 120 tickets were claimed and
deleted, none left behind. With packet overhead that is **2.0 KB/s, 7.1 MB/hour** per
player, comfortably inside NFR-2.

## The wire, and how it is held to account

Until 2026-09-25 **nothing decoded what the server sends.** `SnapshotEncoderTest`
asserted on encoder-side state — byte lengths, entity counts, handle tables — and
never parsed a byte it produced. `BotClient` read the frame type and one varint
so it could acknowledge, then discarded the rest. And the golden vectors in
[protocol-spike](../protocol-spike/README.md) are generated by a *second,
independently written* codec, so they validated an encoder the server does not
run.

Three tests now meet in the middle:

| | |
|---|---|
| `arena/SnapshotRoundTripTest` | drives a real `World` and `ClientView` through the real `SnapshotEncoder` for hundreds of ticks and asserts every entity the client holds sits **exactly** where the server has it |
| `protocol/GoldenVectorTest` | the same decoder reads the golden vectors, so the two encoders cannot drift apart without a build failing |
| `protocol/WireFormatTest` | every encoding pinned at its boundaries — varint widths, zigzag extremes, quantisation conventions |

`protocol.SnapshotReader` decodes a frame; `protocol.ClientWorld` accumulates one
into a handle table and world-space positions. That pair is the reference a Unity
developer ports, and it is deliberately written to be read rather than to be fast.

**Five defects were found by running it**, each confirmed by watching the test
fail before the fix:

- **The delta baseline.** The server measured position deltas from the last
  *acknowledged* frame while the client accumulated onto its current value. Those
  agree only while exactly one snapshot is in flight; at 15 Hz with any real
  latency there are two, so every tank sat a snapshot's worth of travel from where
  the server had it, and the error grew for as long as an acknowledgement was
  outstanding. Deltas are now measured against the last frame **sent**, in world
  space, which is correct because match traffic is TCP
  ([D-16](../docs/architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)).
  Acknowledgement keeps its other job: a handle is not reused until its removal is
  confirmed.
- **The client could not find itself.** The welcome said "you are handle 1" while
  the encoder *excluded* the client's own tank from its own snapshot, so handle 1
  belonged to the nearest opponent — and a client had no way to learn its own
  health at all. Self is now included and reliably holds `Wire.SELF_HANDLE`,
  because it is a tank at distance zero and therefore ranks first.
- **Bullet speed was always index 0** against a one-entry table, while the
  simulation scales bullet speed from 10 to 20.5 units a tick. A maxed bullet
  ended its life ~790 units from where the client drew it. There was then one
  table, `Wire.BULLET_SPEEDS`, in one unit, used by both sides; since protocol 2
  (2026-09-27) the create carries the speed itself, in half units a tick, which
  the simulation fires at, because a class's barrels make more speeds than the
  table held.
- **Bullets were being knocked sideways** by collisions they survived — 31 of 299,
  measured. A predicted entity that can be shoved is not predictable, and
  prediction is what buys about four fifths of the bandwidth plan. They are no
  longer knocked.
- **Two coordinate frames under one field.** Scenery was pinned to the view origin
  at create time and never updated, so it travelled with the camera. The client
  now holds world space, converting once on the create — and static entities do
  receive updates, because bullets knock them. They cost nothing while still.

Also: a living tank on a sliver of health encoded as `0` and read as dead, and the
acknowledgement tick was taken from the wire unclamped.

**Found then, fixed since:** a respawn reset the client's view, so the next frame
carried a tick delta of the whole match and a full-map origin jump (P-6); and
`lastProcessedInputSeq` was hardcoded to 0, so client-side input reconciliation
could not be built against the server (P-9). Both are closed in the
[defect register](../docs/defects.md).

## The platform API

`platform` is reachable over HTTP, behind each machine's nginx (`location /v1/`), so a client
logs in, queues, buys and reads boards without being inside the JVM. Every route, its auth, its
request, its answer and every refusal with its status, and the admin API beside it, are in the
[platform README](platform/README.md#the-public-api); the design is
[04](../docs/detailed-design/04-platform-services.md). In short:

| Routes | For |
|---|---|
| `/v1/accounts`, `/v1/guests`, `/v1/sessions`, `/v1/accounts/upgrade`, `/v1/accounts/name` | registering, guests, logging in and out, renaming; a password check is throttled per address and per account before Argon2 runs, 429 and 503 `busy` with `Retry-After` |
| `/v1/match-requests`, `/v1/queue`, `/v1/queue/accept`, `/v1/queue/decline`, `/v1/sandbox`, `/v1/party/*` | a seat in the public arena; the queue for timed matches (`GET /v1/queue`: `none`, `queued`, `confirming` or `matched`); a sandbox (409 `in_sandbox` while one is held); parties |
| `/v1/leaderboards/*`, `/v1/seasons`, `/v1/achievements`, `/v1/goals` | score and rating boards, the board of teams, past seasons, progress |
| `/v1/shop`, `/v1/inventory`, `/v1/purchases`, `/v1/equipment`, `/v1/boosts`, `/v1/payments`, `/v1/pass`, `/v1/content/*` | the economy, gems for money (503 `payments_off` unless the simulated provider is named), the season pass, the content tables |
| `/v1/teams`, `/v1/team-invites`, `/v1/team-applications`, `/v1/tournaments`, `/v1/friends`, `/v1/friend-requests`, `/v1/blocks`, `/v1/inbox` | teams, tournaments, friends, blocks and the inbox |

503 rather than 500 for a full system or a store that does not answer: nothing is wrong with the
request, so the client comes back rather than reports a fault. A wrong password and an unknown
user return the same 401 with the same code, or the API would be the username oracle the service
was careful not to be. `BotClient` sends each bot its own address from 198.18.0.0/15, the range
reserved for benchmarking, as `X-Forwarded-For`, so a local load run is many clients, not one
throttled address.

## The lobby

`gateway` terminates the client's lobby connection: JSON over WebSocket at
`/lobby`, in deliberate contrast to the hand-packed binary of the match
protocol. The reasoning is inverted — this traffic is low volume and changes
constantly, so legibility beats bytes.

```
{"t":"auth","id":1,"d":{"token":"…"}}   ->  {"t":"auth.ok","id":1,"d":{"playerId":1}}
{"t":"match.request","id":2}            ->  {"t":"match.request.ok","id":2,"d":{"arenaHost":…}}
{"t":"queue.join","id":3,"d":{"mode":"duel"}} -> {"t":"queue.join.ok","id":3,"d":{"state":"queued",…}}
                                        and later, pushed: {"t":"evt.match.found","d":{"arenaHost":…,"mode":"duel"}}
{"t":"nonsense","id":4}                 ->  {"t":"error","id":4,"d":{"code":"unknown_type"}}
```

It holds connections and makes no product decisions. A session lookup is one
`HGET` against the store, so it does that itself; everything else it asks
`platform` and passes the answer through without reinterpreting it. It has no
database driver and no way to get one — the module graph says so.

Three things that are enforced rather than hoped for: a connection that does
not authenticate within five seconds is closed, a second connection for a
player displaces the first, and an unknown message type gets an error rather
than silence — so a newer client meeting an older gateway sees a clear failure
instead of a request that never completes.

**Pushes reach a player from any process** (2026-09-27): `handoff/LobbyPush`
looks up `conn:{playerId}`, which says which gateway holds the player, and
publishes on that gateway's channel, `push:{gatewayId}`; each gateway listens on
its own. At most once: what must not be missed can also be fetched, as a match
found can with `GET /v1/queue`
([03 §5](../docs/detailed-design/03-gateway.md#5-push-routing)).

## The result pipeline

The public arena never ends and never resets, so what gets recorded is a
player's **open match** — arrival to departure — with no placement and no winner,
because a continuous room has no field to be placed in
([D-15](../docs/architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches)).
Long open matches are checkpointed every ten minutes, or a player who stays for
hours is paid for none of it and loses it all if the process dies. Timed
matches — one result for everybody, real placements — are what the queued modes
and tournaments play ([04 §4](../docs/detailed-design/04-platform-services.md#4-matchmaking)).

Either way the result has to reach MySQL without being lost and without making
a room wait. The arena spools the result to a file
*before* touching the network and deletes it only once the queue has accepted
it, so every crash and every outage leaves the result somewhere; a restart
replays what is still on disk. The result goes onto the stream
`s:match-result`; each worker reads it as a consumer of the group `rewards`,
applies it in one idempotent transaction, and acknowledges **after** the commit
— a crash before the ack redelivers, which is harmless, while a crash after it
would lose currency silently. The list it replaced, `q:match-result`, stays as
an inbox the workers drain into the stream
([D-33](../docs/architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)).

The arena reports facts only — kills, deaths, score, placement, playtime. It
does not decide what they are worth. `RewardRules` in `worker` does, in one
place: an arena that computed rewards would have to be redeployed for a balance
change, and mid-rollout two arenas would pay differently for the same match.

Verified as four separate processes — arena, j-redis, worker, MySQL — with ten
bots on a continuous arena:

```
arena:  0 matches ended on a clock    worker: 10 open matches applied
spool:  empty                         ledger: re-derives player.coins exactly
        10 open-match records, 1 player each, placement 0, wins 0
```

The same stack under timed rules produced the opposite shape — four matches of
ten players each — and one useful accident: the bots left three seconds into
the last one, which therefore has ten `match_player` rows and **zero xp, zero
coins and no ledger entries at all**. Under the five-second minimum. The
history records they were there; they are not paid for it, because otherwise
quitting early would be profitable.

## Leaderboards

One metric — the **best score in a single match** — over three windows:
`lb:score:alltime`, `lb:score:day:{yyyy-MM-dd}` and `lb:score:week:{yyyy-Www}`,
all UTC. All-time alone would be a board no new player can ever enter; the
short windows are where an ordinary player appears.

**The write is `ZADD … GT`, never `ZINCRBY`,** and that choice is the whole
design. A best score is a maximum, and a maximum is idempotent *and*
commutative: the same match applied twice, or two matches in either order,
leaves the same board. The queue feeding this is at-least-once **by design** —
`worker` commits to MySQL and only then acknowledges — so redelivery is normal
traffic, not an edge case, and an increment would double-count a score every
time a worker died in that window.

It is also cheaper than the obvious alternative. Writing an absolute total is
idempotent too, but only if you first read the total back out of `player_stat`,
which is a `SELECT` per player per match. `GT` needs no read: the store already
holds the previous maximum and keeps whichever is larger.

Two consequences that look wrong until you think about the crash:

- The board write runs on **every** delivery, including ones MySQL recognises
  as duplicates. Gating it on "this result is new" would make a crash between
  the commit and the board write lose that ranking permanently — the redelivery
  would find the rows already there and skip. The repeat *is* the recovery, and
  there is a test that fails if you add the gate.
- A board failure does **not** hold up the queue. The entry is acknowledged
  either way, and the miss is counted as `unranked`. This pipeline pays
  players; the board is a projection of what it already wrote.

Reads are `platform`'s, and `platform` never writes. Around-me is one command —
j-redis's `J.ZAROUND` returns the rank and the window together, where
`ZREVRANK` then `ZRANGE` would be two round trips against a board other players
are writing to in between, leaving a rank that disagrees with the window it
labels. Names come from an `lb:name` hash, written by `worker` from the name
MySQL holds when it applies a result, and by a rename
([D-60](../docs/architecture/03-decision-log.md#d-60--a-boards-name-is-the-one-the-database-holds-when-a-result-is-applied)),
so rendering a board is one extra round trip rather than N.

Ranks are 0-based in the store and 1-based on the wire: a player is "#1", and
the conversion happens once, at the API, rather than in every client. Tied
players are ordered by member — the player id as a *string* — so the order is
arbitrary but identical on every read.

**What "best" measures, exactly.** A public-arena stay is checkpointed every
ten minutes and a checkpoint starts a fresh tally, so the unit being ranked is
at most ten minutes of play. The board measures the **best ten-minute stretch**,
not the highest score a tank ever carried. Defensible — everyone is measured
over the same bounded unit — but not what a player who peaked at 5 000 over an
hour will expect, and worth deciding with a real player in front of it. Ranking
the peak live score instead needs the simulation to track a per-life maximum,
which it does not.

Verified live across five processes — j-redis, arena, platform, gateway,
worker — with forty bots taking the real client path:

```
lobby:   40 of 40 registered, logged in and got a ticket in 4.9 s
arena:   welcomes 40   disconnects 0   kicks 0   lobby failures 0
worker:  40 matches applied            queue 0   spool empty
boards:  lb:score:alltime, lb:score:day:2026-09-23, lb:score:week:2026-W39, lb:name
         40 members on each, ranked from 1, names resolved
around:  rank 16 of 40, five rows either side, the player in the middle
         a player who has never scored → 404 not_ranked
```

**Not built:** a totals board. Rating boards, the board of teams and their seasons
are built ([04 §7](../docs/detailed-design/04-platform-services.md#seasons-designed-2026-10-03-plan-item-71-a)). j-redis keeps an
AOF, so a restart is fine; after a loss of its disk,
`systemctl start backend-leaderboard-rebuild` rebuilds the boards from MySQL
(`worker/LeaderboardRebuild`, [05 §8](../docs/detailed-design/05-worker-and-events.md)).

## Content, levels and stat upgrades

Balance is **data, not constants in the collision loop**: `Content` bundles a
`StatTable`, a `LevelTable` and a `ShapeTable`, and one instance serves every
room in an arena. They travel together because they are not independent — what
a shape is worth only means something against the curve that spends it.

**Eight stats**, 45 levels, **33 skill points** capped at 7 in any one stat, so
four stats can be maxed and a fifth cannot. That cap is what makes a build a
choice rather than a checklist. The table has two growth modes and no modifier
language:

```
ADD    value = base + perLevel·level + perPoint·points
SCALE  value = base · (1 + perPoint·points)
```

`RELOAD` is why `SCALE` exists: fewer ticks between shots is better, so its
per-point value is negative, and a flat subtraction reaches zero and then goes
through it.

**Four shape kinds**, because the spread of them *is* the early pacing curve.
One shape worth one amount gives a progression where every minute is the last
one:

| Shape | Radius | Health | Experience | Share of spawns |
|---|---|---|---|---|
| Square | 18 | 10 | 10 | 60 % |
| Triangle | 22 | 30 | 25 | 30 % |
| Pentagon | 32 | 100 | 130 | 9.5 % |
| Alpha pentagon | 80 | 3 000 | 3 000 | 0.5 % |

**Score is experience.** The number that levels a tank is the number that adds
to the match score and therefore the leaderboard — scoring separately would
give a player two scores, and the first time they disagreed the leaderboard
would be reported as broken. They are still different *quantities*: a tank's
experience resets on death, the match score does not. Score is everything
earned; level is what was kept.

`UpgradeStat` now reaches the simulation. It is applied **before** the step, so
a point spent this tick is in force for the shot this tick fires; the other way
round it would land a tick late, which is invisible in a benchmark and
infuriating in a fight. `EVT_STATS` tells a player their own level, experience
and points — at once on a level or a spent point, throttled to once a second
for experience alone, because experience moves every time a shape dies and
streaming it at 15 Hz would spend 8 % of the whole downstream budget animating
a progress bar.

Two things that had to be right and are easy to get wrong:

- **Experience is credited by slot *and generation*.** A bullet outlives its
  shooter routinely, and by the time it lands the slot may hold a different
  tank. `playerTag` cannot settle this — every bot shares tag 0 — so the
  shooter's generation is recorded at firing and checked on impact. Without it
  a bot inherits the experience of whoever last sat in its seat.
- **Collision reach is the largest radius, not a tank's.** The grid files an
  entity by its centre, so a query must span the *other* entity's radius too.
  An alpha pentagon is nearly three tank radii, and a bullet grazing one would
  have passed straight through it. Both have tests that fail if the guard is
  removed.

**Built since this section was first written:** tank classes and the barrel
model, 49 classes (the tick benchmark below measures them); equipment, which
reaches the arena as a capped percent a stat in the ticket
([D-37](../docs/architecture/03-decision-log.md#d-37--equipment-reaches-the-arena-as-a-capped-percentage-a-stat-in-the-ticket));
boosts, which `worker` applies to a match's rewards
([D-38](../docs/architecture/03-decision-log.md#d-38--a-boost-raises-a-matchs-rewards-in-worker-if-the-match-ended-while-it-ran)).
**Not built:** in-match effects, and loading the tables from JSON — they are
code-defined defaults, and the class and phrase tables reach the client from
`platform` (`/v1/content/*`).

**Every number here is a first cut that has never been played.** The curve hits
the design's four anchors by construction and the shape values are diep.io's,
because starting from numbers known to produce a playable match beats inventing
them. "Defensible" is not "tuned".

## Bots, and the Java stand-in for the client

The client is C# ([client/](../client/README.md), built with `/opt/dotnet`;
there is no Unity on this machine). The load tools stay Java: `LobbyClient` is
the lobby flow — register, log in, hold a WebSocket, ask for a match — written
with nothing but the JDK's HTTP and WebSocket clients.

`BotClient` uses it, so **every bot takes the path a real client takes**:
register, log in, lobby, match request, then the arena platform named. It used
to write tickets straight into the store, which meant a load run never touched
`platform` or `gateway` at all and measured a path no client will ever use.

```
60 bots, four processes, 25 s
lobby:     60 of 60 registered, logged in and got a ticket in 3.3 s (56 ms each)
arena:     welcomes 60   disconnects 0   kicks 0   lobby failures 0
published: 60            worker applied: 60        spool: empty
```

It first ran 40 of 60 in 21.6 s with 20 timeouts, which looked like the server
struggling. It was not: probes showed platform serving 60 concurrent
registrations in 2.3 s and 35 logins a second with no failures. The harness was
giving each of sixty bots its own `HttpClient`, all constructed in one burst
before anything throttled. One shared HTTP stack, and trying login before
register, took it to 60 of 60 in 3.3 s.

## The tick benchmark

Answers Q-3 — the per-phase tick cost on real hardware, rather than derived on
paper.

```bash
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar \
     [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed]   # defaults: 150 1500 20000 22000 1 0
```

`startLevel` grows every tank to that level as it spawns, with its points spent
and its classes taken as a bot takes them: a mature room, which a run of minutes
from level 1 never reaches, since tanks die and are replaced at level 1.
`mazeSeed`, when not 0, gives the room that seed's maze (01 §8.9), whose walls
cover 3 000 units a side: `8 120 20000 3000 1 2026` is a maze match's room.

It simulates a full room and encodes a snapshot for every client at 15 Hz, so
it measures both halves of a real tick.

### Measured on the development VM (12 cores, load average ~8)

Two scenarios, because map density turns out to matter more than anything else.

**Dense — 5 700-unit map, so ~12 tanks are visible per client.** This is the
density the bandwidth budget assumes.

| Phase | p50 | p99 |
|---|---|---|
| simulation total | 0.215 – 0.223 ms | 0.78 – 1.48 ms (see below) |
| encode, per 150-client round | 2.1 – 2.2 ms | 5.5 – 6.1 ms |

**p99 on this VM cannot be compared across days, even for the same binary.** The
build that measured 0.57 – 0.66 ms p99 on 2026-09-23 measured 0.92 – 1.48 ms on
2026-09-25, rebuilt from the same commit and run interleaved with the current
code — while its p50 stayed at 0.215 – 0.219 ms. Load average was *lower* on the
second day (3.3 against ~8), so it is not simply a busier machine; it is the
tail latency of a shared VM. p50 is the number to compare builds with; only the
production hardware can answer Q-3. The A/B also showed no difference between
the current code and the two before it.

- **Simulation: p99 under 1.5 ms at worst** against NFR-1a's 2 ms — a thinner
  margin than it was, for environmental rather than code reasons.
- **The simulation and the encoder allocate nothing** once running. Measured
  properly only since 2026-09-26: before, the tool could not read the counter
  and printed 0.0 whatever happened. In a 20 000-tick run, 6 ticks and 5 of
  12 000 encoding rounds allocated, and a flight recording shows all of it is
  the latency histograms resizing themselves when a phase takes longer than it
  ever has (HdrHistogram's auto-resize). A room does that a handful of times in
  its life.
- Mean payload **85.3 bytes**, 13.7 entities per snapshot →
  **2.29 KB/s, 8.1 MB/hour** per player with packet overhead, against NFR-2's 15.

The traffic rose from 78.0 bytes after the protocol fixes of 2026-09-25 and was
not re-measured at the time. The client's own tank is now in its own snapshot and
updated every frame, which is the price of a client knowing its own position and
health; and a static entity knocked by a bullet now sends its position. Shape
*drift* was the obvious suspect and was measured by turning it off: 0.6 bytes a
snapshot. It is not the cost.

### What content and progression cost

Measured against the same benchmark before the change, so the comparison is
like for like:

| | Before | After |
|---|---|---|
| simulation p50 | 0.236 ms | **0.211 ms** |
| simulation p99 | 0.551 ms | 0.57 – 0.66 ms |
| mean payload | 68.3 B | **78.0 B** (+14 %) |
| entities per snapshot | 10.9 | **11.9** |
| per player | 2.04 KB/s, 7.2 MB/h | **2.18 KB/s, 7.7 MB/h** |
| allocated per tick | not measured | not measured |

The allocation row read 0.0 B on both sides: the tool printed that whatever
happened, as found on 2026-09-26. See above for what it measures now.

The traffic rise is real and deterministic, not noise: shapes now come in four
sizes and an alpha pentagon has nearly three times a tank's radius, so more of
them fall inside a view rectangle. Still inside NFR-2's 15 MB/hour, with the
margin down from 52 % to 49 %.

Bots spend their skill points as they earn them, which is why this measures
anything at all. Reload is a stat and bullet count is what a tick costs, so
bots that banked their points would have left the benchmark measuring level-1
fire rates and reporting a number the real thing would never hit.

### What classes cost (2026-09-27)

The dense benchmark, the build before tank classes against the build with them,
run interleaved twice (before, after, before, after) on the same VM:

| | Before | After |
|---|---|---|
| bullets in flight, steady state | 606 | **830** (+37 %) |
| simulation p50 | 0.336 – 0.348 ms | **0.458 – 0.460 ms** |
| simulation p99 | 0.80 – 0.91 ms | **0.98 – 0.99 ms**, half of NFR-1a's 2 ms |
| encode round p50 | 2.50 – 2.55 ms | 2.81 – 2.82 ms |
| mean payload | 77.8 B | 79.0 B |
| steady-state allocation | none | none |

The cost is the point: bots take a class at level 15 as they spend their points,
and Twins, Machine Guns and Flank Guards fire more bullets than Basic, which is
what a room of players choosing classes will do. The payload hardly moves,
because a bullet is one create for its whole life (D-9). This day's p50 is not
comparable with the 0.215 ms above, measured on another day: only the
interleaved pair is.

### What the second tier costs (2026-09-27)

The dense benchmark from level 45 (`startLevel`), so that every bot has taken a
class of each tier: the build before the second tier against the build with it,
interleaved twice, both with the shape mix held steady (M-12):

| | Before | After |
|---|---|---|
| bullets in flight | 1 193 | **2 247**, of which 221 traps and 54 drones |
| simulation p50 | 0.79 – 0.83 ms | **1.64 – 1.68 ms** |
| simulation p99 | 1.50 – 1.84 ms | **2.90 – 2.93 ms: over NFR-1a's 2 ms** |
| encode round p50 | 3.23 – 3.29 ms | 4.34 ms |
| mean payload | 82.3 B, 7.9 MB/hour | 92.0 B, 8.4 MB/hour |

A room grown from level 1 over the same 13 minutes, where most tanks are still
Basic: **0.63 ms p50, 1.22 ms p99**. The cost is the collision pass, and it
follows the bullets: Quad Tanks, Twin Flanks and Gunners fire four a volley, and
a trap lies in the air for 24 s. **The mature room is over budget on this VM**,
with every bot firing without pause, which players do not; it is recorded
against Q-3, to be settled on the production hardware, with the levers it has:
fewer players a room, the multi-barrel classes' reloads, and the grid.

Two things tried, and not kept. **Reaching less far** for the collision query
(40 units past a bullet's radius instead of 80, the alpha pentagon's, and the
few larger shapes checked one by one) made the tick **twice as slow**: there were
69 alpha pentagons in the room, not the seven the table's weights give, and
every bullet walked the list. That is how M-12 was found. **A grid of 100-unit
cells** instead of 200 cut the collision pass by a fifth in the mature room, but
made encoding a fifth slower in every room, since each view walks four times the
cells: a loss where it matters most.

### What the third tier costs (2026-09-28)

The same dense benchmark from level 45, the second tier's build against the
third's, interleaved twice. Bots now take a class of all three tiers, 30 of the
45 at level 45, the Smasher line among them.

| | Second tier | Third tier |
|---|---|---|
| bullets in flight | 2 247, of which 221 traps and 54 drones | **2 193**, of which 867 traps, 21 drones and 12 minions |
| simulation p50 | 1.69 – 1.72 ms | 1.63 – 1.75 ms |
| simulation p99 | 3.83 – 4.16 ms | 3.93 – 4.29 ms |
| collision pass p50 | 1.48 – 1.51 ms | 1.39 – 1.50 ms |
| encode round p50 | 4.44 – 4.49 ms | 4.38 – 4.44 ms |
| mean payload | 92.0 B, 8.4 MB/hour | 87.4 B, 8.2 MB/hour |
| ticks that allocated | 3 – 4 of 20 000 | 4 – 6 of 20 000 |

**The third tier costs what the second did**, within the noise, which is what
D-22 was for: no class keeps more alive than the second tier's most, so the
worst case did not move. Traps quadrupled, since the Trapper's line is five
classes now, and bullets fell a little, since a quarter of the bots took the
Smasher's road and fire nothing. The payload fell with them.

**The p99 is the VM's, not the build's.** The second tier's own build measured
2.90 – 2.93 ms yesterday and 3.83 – 4.16 ms today, on the same inputs, which
are deterministic: the same 2 247 bullets both days. The worst case stays over
NFR-1a's 2 ms on this machine either way; Q-3 is where that is settled. A room
grown from level 1 with the whole tree: **0.70 ms p50, 1.77 ms p99**, 7.8 MB an
hour.

**The first run found M-14.** The third tier's build allocated in **every tick,
20 000 of 20 000**, about 460 bytes each, in the mature room and the one grown
from level 1 alike: each time any tank broke a square, the room asked the tank's
class for a Necromancer's convert barrel through an iterator. The numbers above
are after the fix; before it, the tick cost the same, and the collector would
have paid the rest. Only this benchmark was looking, so a test holds the rule now
(`AllocationTest`).

### What the rest of the tree costs (2026-09-28)

The same benchmark, the third tier's build against the whole tree's (49 classes:
the Battleship, the Rocketeer, the Skimmer and the Mega Smasher added, traps
knocked, the Predator's zoom), interleaved twice.

| | Third tier | The whole tree |
|---|---|---|
| in the bullet list | 2 193: 867 traps, 21 drones, 12 minions | 2 187: 775 traps, **118 drones**, 7 minions |
| simulation p50 | 1.76 – 1.77 ms | **1.99 – 2.00 ms** |
| collision pass p50 | 1.51 ms | 1.67 – 1.68 ms |
| simulation p99 | 4.40 – 5.67 ms | 5.36 – 5.46 ms |
| mean payload | 87.4 B, 8.2 MB/hour | 93.5 B, 8.5 MB/hour |
| ticks that allocated | 5 – 7 of 20 000 | 5 – 6 of 20 000 |

**The whole tree costs 13 % more at p50, and the drones are why.** D-22 bounds
what a class keeps alive in bullets and traps; drones it leaves to each class's
cap, and the Battleship's is 16. With five Battleships among the 150 bots,
drones went from 21 to 118 while traps fell by about as many, so the list is the
same length and dearer: a drone steers every tick and gathers where its tank
attacks, and a trap lies still. The payload rose with them, the drones being
updated every frame; 8.5 MB an hour is well inside the budget.

**Whether that stands is a balance question, and open**: the Battleship keeping
eight, as the Overseer does, or D-22 counting drones as it counts bullets. The
p99 is the VM's again: the third tier's own build ranged 4.40 – 5.67 ms within
the hour. A room grown from level 1 with the whole tree: **0.72 ms p50, 2.04 ms
p99**, 7.9 MB an hour.

**Sparse — 22 000-unit map, the size in the arena design.**

| | Value |
|---|---|
| simulation p99 | 0.436 ms |
| encode round p50 | 0.565 ms |
| mean payload | 15.7 bytes |
| per player | 1.27 KB/s, 4.5 MB/hour |

### What the measurements changed

**NFR-1 was unmeasurable as written.** It said "room tick ≤ 4 ms p99" without
saying whether encoding counted. Simulation alone is 0.55 ms; a tick that also
encodes for 150 clients is 6.2 ms. The requirement is now split into NFR-1a
(simulation) and NFR-1b (snapshot tick), both measured and both passing.

**Traffic is about half the estimate.** The design budgeted ~122 bytes of
payload; measured is 68.3. Deltas compress better than the hand calculation
assumed, and D-9 means shapes and bullets are created once and never updated.
Treat 68 as a floor, though: names and events are not implemented yet, and
both add bytes.

**The map size and the traffic budget contradict each other (Q-4).** A
1 600-unit view over a 22 000-unit map covers 0.53 % of it, so an evenly spread
room of 150 tanks puts **0.8 tanks** in view — not the 12 the budget assumes.
Either the map is far smaller than 22 000 units, or players cluster hard enough
to make the average meaningless. Until that is settled, the dense numbers are
the ones to plan with, because they are the pessimistic case.

### Wall-clock latency on this machine is scheduler-bound, not code-bound

The in-process benchmark and the running server measure different things, and
the gap is large enough to be worth stating plainly.

| | p50 | p99 |
|---|---|---|
| Benchmark: CPU cost per tick | 0.20 ms | 0.55 ms |
| Running server, 120 bots: wall-clock per tick | 0.19 ms | **38 ms** |
| Running server, **zero clients**: wall-clock per tick | 0.08 ms | **11 ms** |

The third row is the decisive one. With no clients, no encoding and no network
traffic, a room doing 0.08 ms of work still shows an 11 ms p99 and a 115 ms
maximum. **That is thread scheduling, not the simulation.**

The mechanism: the benchmark runs flat out and never yields, so it is never
descheduled. A real room thread parks for ~39 ms between ticks and has to be
scheduled back in every time — on a development VM at load average 11 of 12
cores, with a second JVM running 120 bots beside it. `overruns` stayed at 0
throughout, so the deadline logic held; the thread simply woke late.

**What this means:** the CPU-cost numbers are trustworthy and portable. The
wall-clock numbers are a property of this VM and cannot validate NFR-1a or
NFR-1b. Both need re-measuring on the production machine with `taskset`
pinning and cores that are not oversubscribed — which is exactly why
[07 §6](../docs/detailed-design/07-threading-and-performance.md) prescribes
pinning, now with evidence rather than as a precaution.

### Three caveats

**The tail is the VM, not the algorithm.** Maxima reach 46 ms on a machine with
load average 8 on 12 cores. Re-measure on a quiet machine.

**An earlier version of this benchmark was wrong in a way that flattered it.**
It spawned 150 tanks and never respawned them, so the room thinned to 106 and
the collision pass did proportionally less work.

**And the simulation had a degenerate bug the encoder exposed.** Tanks were
created without an initial heading, so every one of them started at angle 0 and
accelerated identically — the whole room moved in lockstep, relative positions
never changed, and the encoder correctly sent nothing. Found because an encoder
test expected updates and got empty snapshots. Fixing it cut the simulation's
p99.9 from 3.4 ms to 1.1 ms.

## The apply benchmark

Answers the other half of Q-3 for `worker`: how many results a second MySQL
takes, from one thread and from several at once (05 §1, plan item 26). Each
thread applies one-player results through the worker's own transaction on a
connection of its own; commits made at once share their synchronous writes, so
the answer is a rate for each thread count, and the thread count a worker
process, or the number of processes, should have.

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     <jdbc url> <user> <password> [results] [threads,...] [mysqld pid]   # defaults: 400 1,2,4,8
```

It drops everything in the database and migrates it afresh, so it refuses any
URL but one naming a single database that ends in `_test`. Given the pid of a
mysqld on the same machine, it adds that process's CPU a result.

Measured on the development machine (2026-09-30): 18–19 results a second from
one thread, 27–34 from two, 66–69 from four, 104 from eight; MySQL's CPU a
result, most of it the kernel's in the synchronous writes, falls as the threads
rise, since one write serves every commit waiting for it.

## The rank benchmark

What a player's own place on a rating board costs (04 §7, plan item 58, D-35):
the duel board's listed players among many accounts never rated, as
`RatingBoards.place` reads them. For a listed player at the top, the middle and
the bottom it prints the median and slowest of the reads, and the index rows
MySQL read for one (the session's handler reads on the connection held for it);
the same for the top hundred.

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     <jdbc url> <user> <password> [accounts] [listed] [reads]   # defaults: 200000 5000 21
```

It drops everything in the database and fills it, so it refuses any URL but one
naming a single database that ends in `_test`. Measured on the development
machine (2026-10-02), 200 000 accounts, 5 000 listed: before V20, the middle and
the bottom 209 and 200 ms, about 200 000 rows each; after, 14 and 18 ms, 5 021
and 10 016 rows.

## Design rules this code follows

| Rule | Where |
|---|---|
| Single writer per room; no locks, no concurrent collections | [07 §1](../docs/detailed-design/07-threading-and-performance.md) |
| Zero allocation in the tick loop | [07 §2](../docs/detailed-design/07-threading-and-performance.md) |
| Pool is a hard ceiling; spawns are refused, never grown | [07 §8](../docs/detailed-design/07-threading-and-performance.md) |
| Dead slots recycled only at end of tick | `World.sweep` |
| Every entity carries a wire class | [01 §2](../docs/detailed-design/01-arena.md), D-9 |
| Per-phase histograms, not just a total | [07 §4](../docs/detailed-design/07-threading-and-performance.md) |
| Deltas against the last frame sent, in world space | [D-16](../docs/architecture/03-decision-log.md), `ClientView` |
| Handles not reused until the remove is acknowledged | [02 §5](../docs/detailed-design/02-networking.md), `ClientView` |
| Rank then truncate, so tanks crowd out distant shapes | [02 §7](../docs/detailed-design/02-networking.md), `SnapshotEncoder` |
