# backend

This is the implementation of the realtime arena backend, in Java 21 and Maven. The design is in the
[design docs](../docs/README.md). This page is the backend's front page, for developers new to the project. It
covers: what each module is, the commands that build, test, release and drill it, how to run each process by hand,
and the main mechanisms with what was measured about them (login, the join ticket, MySQL, the API, the lobby, the
result pipeline, leaderboards, content, bots and the benchmarks). Each module's own README has its classes, its
configuration and its tests. Terms such as arena, platform, gateway, worker, front, store, ticket and stay are
defined in the [glossary](../docs/glossary.md).

**What it is built with.** The build is offline. Temurin 21 and Maven 3.9 are committed in
[`../vendor/`](../vendor/VERSIONS.md), and [`../dev-env.sh`](../dev-env.sh) puts them on the `PATH`. Every library
comes from [`java21-offline`](../java21-offline/README.md), whose `repository/` has the shape of `~/.m2/repository`.
The one exception is j-redis 2.2.1, the store: it is built from [`../j-redis-service`](../j-redis-service/README.md)
and installed into the local repository first. [`../build-offline.sh`](../build-offline.sh) builds all of it, the
release included, from the repository alone ([09](../docs/detailed-design/09-release-and-packaging.md)).

**Status (2026-11-25).** Plan items 1 to 137 are done, except item 7, which stays open by design: what is left of it
waits on Q-3's hardware ([plan](../docs/plan.md)). Phases 1 to 5 of the [plan](../docs/plan.md#3-phases) are built
and drilled on the development machine:

- the public arena and the timed modes; matchmaking with parties and a confirm step;
- accounts, guests and sessions; teams, tournaments, friends and the inbox; score and rating boards with seasons;
- the economy: equipment, item levels, boosts, gems for money through a simulated provider, the season pass and
  skins;
- replicated stores and MySQL with scripted failover, rehearsed under load; backups proved by restoring them;
- a release that carries its own Java runtime, j-redis, MySQL 8.4 and nginx, checked on RHEL 9 and RHEL 8;
- the site behind one public address, with nginx on the front ending TLS on 443 and 9001
  ([D-82](../docs/architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)),
  and serving 80 in clear for a client without TLS, matches included
  ([D-115](../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included));
- the launch on one machine for up to about 5 000 players
  ([D-93](../docs/architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)),
  the steps to add a second machine as its standby (D-96), and three machines kept as the design for 50 000.

What waits is measurement on the production machines (Q-1, Q-3): the load test to 10 000 (Phase 4), and Phase 6's
soak at target load and its capacity with a machine lost. Nothing has run on the production hardware yet.

**Tests.** The modules' sources declare 1 150 tests (counted on 2026-11-25; a parameterized test counts once, so
Maven's report is higher). Each module's README lists its test classes with their counts, and the build checks those
lists against the sources (`ReadmeTestListsTest`, DOC-29). The client (the engine-free C# core, its headless driver
and the Unity scripts) is in [client/](../client/README.md), with its own tests.

## Modules

Each module has its own README with its classes, configuration and tests.

| Module | What it is | Main classes |
|---|---|---|
| [common](common/README.md) | what every process shares | `Metrics`, `MetricsServer`, `Secrets`, `Arguments`, `RefusedConfiguration`, `TrustedProxies`, `Logs`, `PhaseTimer`, `IntList`, `Xorshift` |
| [protocol](protocol/README.md) | the match protocol's bytes, and its reference client | `Wire`, `WireReader`, `SnapshotWriter`, `SnapshotReader`, `ClientWorld`, `ClientMessage` |
| [sim](sim/README.md) | the tick loop and the content tables; no networking, no IO | `World`, `Room`, `Entity`, `SpatialHash`, `Content`, `StatTable`, `LevelTable`, `ShapeTable`, `ClassTable`, `PhraseTable`, `MazeGenerator` |
| [arena](arena/README.md) | the match server: rooms, connections, snapshots, results | `ArenaMain`, `ArenaServer`, `RoomRegistry`, `RoomThread`, `ClientView`, `SnapshotEncoder`, `MatchFrameHandler`, `ProxyLine`, `MatchTally`, `MatchResultPublisher`, `ArenaAnnouncer` |
| [handoff](handoff/README.md) | what the processes pass each other through the store | `Ticket`, `TicketStore`, `SessionStore`, `ArenaDirectory`, `LeaderboardStore`, `LobbyPush`, `StoreClients`, `MatchMode`, `MatchResultCodec`, `MatchResultQueue`, `MatchResultStream`, `SandboxHolds`, `TournamentGrants` |
| [persistence](persistence/README.md) | MySQL: the schema, the pool, every transaction | `Database`, `DatabaseSettings`, `Tx`, `AccountRepository`, `EconomyRepository`, `MatchResultRepository`, `TeamRepository`, `TournamentRepository`, `RatingBoards`, `SeasonRepository`, `PaymentRepository` |
| [platform](platform/README.md) | the HTTP API, the matcher, the admin API and the watch (monitoring and alerts) | `PlatformMain`, `PlatformHttpServer`, `AdminServer`, `AuthService`, `PasswordHasher`, `LoginThrottle`, `JoinService`, `QueueService`, `Matchmaker`, `PartyService`, `ShopService`, `PaymentService`, `watch/Watch` |
| [worker](worker/README.md) | results into MySQL, and the scheduled jobs | `WorkerMain`, `MatchResultConsumer`, `RewardRules`, `EloRating`, `AccountLevels`, `TournamentScheduler`, `SeasonKeeper`, `Retention`, `LedgerCheck`, `LeaderboardRebuild` |
| [gateway](gateway/README.md) | the client's lobby connection, JSON over WebSocket | `GatewayMain`, `GatewayServer`, `LobbyHandler`, `ConnectionRegistry`, `PlatformClient`, `Pushes`, `FrameLimit`, `EdgeCertificate` |
| [tools](tools/README.md) | benchmarks, bots, the soak's judge, and checks over the repository: the READMEs' test lists, the documents' links, no symbolic links | `TickBenchmark`, `BotClient`, `LobbyClient`, `ApplyBenchmark`, `PurgeBenchmark`, `RankBenchmark`, `SoakJudge` |
| [crypto-examples](crypto-examples/README.md) | examples of signing, encryption, hashing, X.509 and TLS, each tested; for copying, never in the release | `Aead`, `Signatures`, `RsaEncryption`, `SharedSecrets`, `Pem`, `AnyPem`, `Certificates`, `CertificateAuthority`, `KeyStores`, `Tls`, `RuntimeProbe` |
| [scripts](scripts/README.md) | the release and its checks on RHEL 9, the port checks, backups, restores, promotions, the certificate and nginx's logs | `make-release.sh`, `check-*.sh`, `backup-*.sh`, `restore-*.sh`, `promote-store.sh`, `promote-mysql.sh`, `private-ca.sh`, `install-certificate.sh`, `rotate-nginx-logs.sh` |
| [deploy](deploy/README.md) | what a machine is given | systemd units, env examples, nginx, MySQL configuration; the catalogues of settings, ports and timers |

**Why `handoff` exists.** `handoff` lets `arena` use the join ticket without inheriting a database driver. The
design says `arena` never touches MySQL, and a module graph enforces that better than a sentence does. Of this
repository's modules, `arena` depends directly on `handoff`, `sim` and `protocol` only (and on `common` through
them). So the release's `lib/arena` holds none of MySQL's driver, Flyway, HikariCP or Bouncy Castle.

**The schema** is two Flyway migrations,
[`V1__schema.sql`](persistence/src/main/resources/db/migration/V1__schema.sql) and
[`V2__seed.sql`](persistence/src/main/resources/db/migration/V2__seed.sql). They were squashed on 2026-10-04 from the
34 migrations before them
([D-75](../docs/architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
From V3 on, a schema change is a new migration, and an applied migration is never edited. Flyway runs at every
`platform` and `worker` start.

## Commands

**Build and test everything, offline.** Run this in a shell that sourced `dev-env.sh` from the repository's root
([build and run](../docs/development/03-build-and-run.md)). The persistence, worker and platform tests need the
development [MySQL](#mysql) below:

```bash
. ./dev-env.sh && ./dev-mysql.sh start && cd backend && mvn install
# one module and what it needs:  mvn -pl platform -am install
```

`com.jredis:*:2.2.1` is not in the offline bundle. Run `cd ../j-redis-service && mvn install -DskipTests` once, first
(`make-release.sh`, `build-offline.sh` and `dev-jredis.sh build` do it themselves).

**Or everything from the repository alone**, with no network and nothing installed but bash, coreutils, findutils,
sed, tar and gzip. `build-offline.sh` uses the committed JDK and Maven, `java21-offline` as the only repository, and a
local repository of its own in `work/`. It builds j-redis, then the backend with its tests, then the release
([09 §3](../docs/detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)):

```bash
./build-offline.sh                  # from the repository's root; SKIP_TESTS=1 skips the tests
```

Everything a build, a test or a run by hand writes goes in `work/` inside the checkout (D-80).

**A release.** `scripts/make-release.sh` builds j-redis first, then writes `target/release/backend-<version>/` and a
`.tar.gz`. The release holds one `lib/` per process type, the Java 21 runtime they run on (linked by `jlink` from
`vendor/jdk-21`), j-redis, MySQL 8.4 and nginx (from `vendor/`), the units, their configuration, the env examples
and the scripts ([deploy README](deploy/README.md#the-release)). `check-release-el9.sh` then runs the release in
RHEL 9's own userspace with nothing installed (it needs Docker). The other checks on RHEL 9 (the units, the install
guide, the ports and firewall, two machines, SELinux) are in [scripts/README](scripts/README.md).

```bash
cd backend && scripts/make-release.sh               # in a shell that sourced dev-env.sh
scripts/check-release-el9.sh [release-dir]          # then, in backend/; default target/release/backend-*/
```

**The live drill.** It runs the whole stack from a release, on the release's own runtime and j-redis, on ports of its
own: j-redis 6390, platform 8093, gateway 8094, arena 9011, platform's metrics 9195 and admin API 9196. The client's
headless driver drives it through named scenarios. By default it runs `play`, `resume`, `badticket` and `lifecycle`;
the others, such as `duel`, `party`, `sandbox`, `tournament`, `purchase` and `pass`, by name
(`client/Headless/Program.cs` has every one). Modes such as `FAILOVER=1`, `MYSQL_FAILOVER=1`, `BOTS=`, `WATCH=1`,
`IDLEGUEST=1`, `SIMPLE=1` and `FRONT=` are in the drill's header and in
[build and run §7](../docs/development/03-build-and-run.md#7-the-drills):

```bash
TMPDIR=<scratch> client/headless-drill.sh [scenario ...]        # from the repository's root
TLS=1 TMPDIR=<scratch> client/headless-drill.sh play untrusted  # the arena over TLS
TMPDIR=<scratch> backend/scripts/backup-drill.sh                # the backups' own drill; RELEASE=<release> on its MySQL 8.4
```

**Benchmarks.** Run them from `backend/` with the tools jar built. The database ones use the development MySQL
(`./dev-mysql.sh start`), and each one wipes the database it is given, which must have a name ending in `_test`:

```bash
# tick cost: [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed], defaults 150 1500 20000 22000 1 0
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar
# results a second by thread count: [results] [threads,...], defaults 400 1,2,4,8
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     "jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 400 1,2,4,8
# retention's purge rate: [matches] [players a match], defaults 200000 4; this example gives 1 player a match
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.PurgeBenchmark \
     "jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 200000 1
# a rating board's place: [accounts] [listed] [reads], defaults 200000 5000 21
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     "jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 200000 5000 21
```

**The client's own tests** need the .NET 8 SDK, which is not in the repository (`/opt/dotnet` on the development VM):
`cd client && DOTNET_NOLOGO=1 dotnet test Core.Tests -f net8.0`. The client's checks with Unity 2019.4's compiler and
Visual Studio 2019's MSBuild are in [client/](../client/README.md). Running each process by hand is
[below](#running-it).

## Login, and what it costs

The whole chain, register, then log in, then get a ticket, then play, runs end to end in `PlatformToArenaTest`. It
runs against a real MySQL and a real j-redis, with a plain `Socket` that speaks the real wire format. Every part of
that chain had passing tests before the chain itself had ever run once.

Passwords are hashed with Argon2id, by Bouncy Castle (pure Java, nothing to install). The parameters are OWASP's
minimum: m=19 MiB, t=2, p=1. **Measured on the development VM: 88 ms per hash.** That number is the tightest CPU
limit in the system:

| | |
|---|---|
| Cost per password login | 88 ms, and 19 MiB held for the duration |
| Concurrent hashes allowed | 8. Argon2 is memory-hard, so an unbounded burst would be an `OutOfMemoryError`, not a queue. Up to 160 more logins wait their turn |
| Sustained password logins | **about 90 a second per process**, using 8 cores |
| Cost per session resume | one `HGET` |

That is fine for normal use, because a session lasts a day and the common path is a resume. It is *not* fine if
50 000 players log in inside one minute. Two consequences are already in the code:

- A session's lifetime has ±10 % jitter, so a launch window does not repeat itself as a login herd one day later.
- The stored hash carries its own parameters, so the cost can be raised later without invalidating every password at
  once.

## The join ticket

An arena has no database connection and never gets one. So a player's identity reaches the arena in a short-lived
j-redis key: `platform` writes it and the arena consumes it. The write is
`MULTI · HSET ticket:{id} … · EXPIRE 60 · EXEC`, and the claim is `MULTI · HGETALL · DEL · EXEC`.

**The transaction is the point.** `HGETALL` and then `DEL`, as two separate commands, are **not** single-use: two
connections that replay one ticket can both read it before either deletes it, and both join. The first concurrency
test here passed against exactly that bug. Its sixteen threads started one at a time on a loaded machine, so they
never actually overlapped. When all sixteen claims are submitted before any is awaited, the racy version reports 16
winners and the transaction reports 1.

The claim is a network round trip, so the arena does not wait for it on a Netty thread. The reply is handed back to
the connection's event loop, and the join continues there. (T-1: continuing on the store's thread stalled every other
store operation behind a room being built.)

A refused join gets a `Kick` message with a reason
([02 §3](../docs/detailed-design/02-networking.md#3-messages)) before the socket closes. "Bad ticket", "no room" and
"old client" each need a different response from the client.

There is no join path without a ticket, not even for development. A bypass flag is a production hole that waits for
the day someone ships with it set.

## MySQL

The persistence tests run against a **real MySQL**, not a fake: 8.4 LTS, `vendor/`'s, which is also what the release
carries for production. Everything worth testing there is transactional: `FOR UPDATE` ordering, a plain insert whose
duplicate-key error is the idempotency check, and the `CHECK` constraint as the last line of defence. A fake
reproduces none of it, so tests against a fake would pass while production broke. (The idempotency check was
`INSERT IGNORE` until 2026-09-25. That turned a missing player and an out-of-range value into a silent "already
applied" and a silent clamp: see
[06 §4](../docs/detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter).)

```bash
./dev-mysql.sh start      # from the repository's root: vendor/'s 8.4 on 127.0.0.1:3314, its files in work/mysql
```

That is the development MySQL ([build and run §3](../docs/development/03-build-and-run.md#3-the-tests)). Its first
start makes the databases `backend_test` and `backend_dev` and the user `backend` / `backend-dev-password`, and
`dev-env.sh` points the tests' `JDBC_URL` at `backend_test`. Without `JDBC_URL` the tests go to the same database
(`DatabaseSettings.TEST_URL`, O-40), never to whatever answers on 3306. `DB_USER` and `DB_PASSWORD` override the
user and the password. Flyway migrates the schema before each test class. On 2026-10-05 the tests were also run
against a server from the release's MySQL 8.4
([09 §7](../docs/detailed-design/09-release-and-packaging.md#7-how-it-is-verified)).

What those tests actually establish:

| Test | Result |
|---|---|
| 12 concurrent purchases, balance 1 000, price 200 | **exactly 5 applied**, balance 0, never negative |
| 8 concurrent deliveries of one match result | **exactly 1 applied** |
| 8 concurrent *different* matches sharing two players | **all 8 applied, no deadlock**. It failed 3 runs of 3 before the players were locked up front |
| Same result delivered 3 times | credited once |
| Ledger re-derived against the stored balance | equal, to the coin |
| A direct `UPDATE` that would overdraw | rejected by `CHECK` |

## Running it

Every bot takes the path a client takes: register, log in, open the lobby, ask for a match, then connect to the arena
it was given. So a run needs the whole chain: [j-redis](../j-redis-service/README.md), [MySQL](#mysql), and one of
each process. `platform` and `worker` use the development MySQL by default, unless `BACKEND_DB_*` says otherwise.

**The scripted way** is a script per service (D-92), run from the repository's root. `./run-local.sh start` starts,
in this order, `dev-mysql.sh` (3314), `dev-jredis.sh` (6380), `dev-backend.sh` (the platform on 8080, the gateway on
8081, a worker, and the arena on 9021) and `dev-nginx.sh` (80, 443 and 9001; 80 and 443 need root).
`./run-local.sh stop` stops nginx, the backend and j-redis, and leaves MySQL running. `DEBUG=1` opens debugger ports
5005 to 5009. This is [build and run §5](../docs/development/03-build-and-run.md#5-running-it-by-hand).

**By hand**, on one machine, with no nginx in front:

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

`<release>` is what `scripts/make-release.sh` builds, `target/release/backend-<version>`. The processes can run on
the release's own runtime, `<release>/runtime/bin/java`, and the store's server is
`<release>/jredis/lib/j-redis-server.jar`. Every argument has a default in the code; the
[deploy README](deploy/README.md#passed-as-arguments-by-the-units) lists them.

**TLS.** An arena run this way serves plaintext unless it is given a keystore
([operations/01 §8](../docs/operations/01-deploy.md#8-tls-for-match-traffic)), and the bots follow whatever the
grant says. On the site, the arena itself always speaks plaintext: the front's nginx ends TLS on 9001 and passes
each connection to the arena by its TLS name (D-82), or, for a client without TLS, passes a match that asks for its
arena by name on 80 (D-115). The arena then reads what nginx sends first, a PROXY line or that request, for the
phone's address (`ProxyLine`, D-105).

### End to end, 120 bots for 15 s

This was measured before the bots went through the lobby: every bot claimed a real ticket from j-redis first.

```
welcomes 120   disconnects 0   kicks 0
snapshots 27531   bytes 1804521
mean frame 65.5 B   per bot 15.30 snapshots/s, 0.98 KB/s (payload only)
```

That is exactly the 15 Hz target, with no disconnects. The mean frame, 65.5 bytes, is close to the encoder
benchmark's 68.3: the wire format behaves over a real socket as it does in isolation. `DBSIZE` was 0 afterwards: all
120 tickets were claimed and deleted, none left behind. With packet overhead, that is **2.0 KB/s, 7.1 MB/hour** per
player, well inside NFR-2.

## The wire, and how it is held to account

Until 2026-09-25 **nothing decoded what the server sends.**

- `SnapshotEncoderTest` asserted on encoder-side state (byte lengths, entity counts, handle tables) and never parsed a
  byte it produced.
- `BotClient` read the frame type and one varint, so that it could acknowledge, and discarded the rest.
- The golden vectors in [protocol-spike](../protocol-spike/README.md) are generated by a *second, independently
  written* codec, so they validated an encoder the server does not run.

Three tests now meet in the middle:

| | |
|---|---|
| `arena/SnapshotRoundTripTest` | drives a real `World` and `ClientView` through the real `SnapshotEncoder` for hundreds of ticks, and asserts that every entity the client holds sits **exactly** where the server has it |
| `protocol/GoldenVectorTest` | the same decoder reads the golden vectors, so the two encoders cannot drift apart without a build failing |
| `protocol/WireFormatTest` | every encoding pinned at its boundaries: varint widths, zigzag extremes, quantisation conventions |

`protocol.SnapshotReader` decodes a frame; `protocol.ClientWorld` accumulates frames into a handle table and
world-space positions. That pair is the reference a Unity developer ports. It is written on purpose to be read, not
to be fast.

**Five defects were found by running it.** Each was confirmed by watching the test fail before the fix:

- **The delta baseline.** The server measured position deltas from the last *acknowledged* frame, while the client
  added them to its current value. Those two agree only while exactly one snapshot is in flight. At 15 Hz with any
  real latency there are two, so every tank sat one snapshot's worth of travel from where the server had it, and the
  error grew for as long as an acknowledgement was outstanding. Deltas are now measured against the last frame
  **sent**, in world space. That is correct because match traffic is TCP
  ([D-16](../docs/architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)).
  Acknowledgement keeps its other job: a handle is not reused until its removal is confirmed.
- **The client could not find itself.** The welcome said "you are handle 1", while the encoder *excluded* the
  client's own tank from its own snapshot. So handle 1 belonged to the nearest opponent, and a client had no way to
  learn its own health at all. The client's own tank is now included, and it always holds `Wire.SELF_HANDLE`: it is a
  tank at distance zero, so it ranks first.
- **Bullet speed was always index 0**, against a one-entry table, while the simulation scales bullet speed from 10 to
  20.5 units a tick. A bullet at full speed ended its life about 790 units from where the client drew it. The fix was
  first one table, `Wire.BULLET_SPEEDS`, in one unit, used by both sides. Since protocol 2 (2026-09-27) the create
  message carries the speed itself, in half units a tick, which the simulation fires at, because a class's barrels
  make more speeds than the table held.
- **Bullets were being knocked sideways** by collisions they survived: 31 of 299, measured. A predicted entity that
  can be pushed is not predictable, and prediction is what saves about four fifths of the bandwidth plan. Bullets are
  no longer knocked.
- **Two coordinate frames under one field.** Scenery was pinned to the view origin when it was created and never
  updated, so it travelled with the camera. The client now holds world space and converts once, on the create. Static
  entities do receive updates, because bullets knock them; they cost nothing while still.

Also: a living tank on a sliver of health was encoded as `0` and read as dead, and the acknowledgement tick was taken
from the wire without clamping.

**Found then, fixed since:** a respawn reset the client's view, so the next frame carried a tick delta of the whole
match and a full-map origin jump (P-6); and `lastProcessedInputSeq` was hardcoded to 0, so client-side input
reconciliation could not be built against the server (P-9). Both are closed in the
[defect register](../docs/defects.md).

## The platform API

`platform` serves the HTTP API, so that a client logs in, queues, buys and reads boards without being inside the JVM.
On the site the client reaches it through the front's nginx, at `/v1/`: on 443 over TLS, or on 80 in clear for a
client without TLS (D-115). nginx passes each request to one of the machines' platforms. Every route, its
authentication, its request, its answer and every refusal with its status, and the admin API beside it, are in the
[platform README](platform/README.md#the-public-api); the design is
[04](../docs/detailed-design/04-platform-services.md); every route through nginx, one by one, is
[operations/11 §10](../docs/operations/11-phone-to-backend-routes.md#10-every-route-one-by-one). In short:

| Routes | For |
|---|---|
| `/v1/accounts`, `/v1/guests`, `/v1/sessions`, `/v1/accounts/upgrade`, `/v1/accounts/name` | registering, guests, logging in and out, renaming, and deleting one's own account (D-84). A password check is throttled per address and per account before Argon2 runs, answered 429 and 503 `busy` with `Retry-After` |
| `/v1/match-requests`, `/v1/queue`, `/v1/queue/accept`, `/v1/queue/decline`, `/v1/sandbox`, `/v1/party/*` | a seat in the public arena; the queue for timed matches (`GET /v1/queue` answers `none`, `queued`, `confirming` or `matched`); a sandbox (409 `in_sandbox` while one is held); parties |
| `/v1/leaderboards/*`, `/v1/seasons`, `/v1/achievements`, `/v1/goals` | score and rating boards, the board of teams, past seasons, progress |
| `/v1/shop`, `/v1/inventory`, `/v1/purchases`, `/v1/equipment`, `/v1/boosts`, `/v1/payments`, `/v1/pass`, `/v1/content/*` | the economy, gems for money (503 `payments_off` unless the simulated provider is named), the season pass, the content tables |
| `/v1/teams`, `/v1/team-invites`, `/v1/team-applications`, `/v1/tournaments`, `/v1/friends`, `/v1/friend-requests`, `/v1/blocks`, `/v1/inbox` | teams, tournaments, friends, blocks and the inbox |
| `/v1/health` | the API's health through nginx, `200 {"status":"ok"}` (D-107). `/health`, outside `/v1/`, is for this machine's monitoring over loopback, and nginx answers it 404 |

A path that no route has gets the API's JSON 404, `no_such_route` (D-107).

**Why 503 and not 500** for a full system or a store that does not answer: nothing is wrong with the request, so the
client should come back later rather than report a fault.

**One answer for two failures.** A wrong password and an unknown user return the same 401 with the same code.
Otherwise the API would tell an attacker which usernames exist.

**Bots and the login limits.** `BotClient` gives each bot its own address from 198.18.0.0/15, the range reserved for
benchmarking (RFC 2544), and sends it as `X-Forwarded-For`. So a local load run looks like many clients, not one
throttled address.

## The lobby

`gateway` ends the client's lobby connection: JSON over WebSocket at `/lobby`, reached through the front's nginx as
`wss://<address>/lobby` on 443 or `ws://<address>/lobby` on 80. That is a deliberate contrast with the hand-packed
binary of the match protocol, and the reasoning is the opposite: this traffic is low in volume and changes often, so
legibility is worth more than bytes.

```
{"t":"auth","id":1,"d":{"token":"…"}}   ->  {"t":"auth.ok","id":1,"d":{"playerId":1}}
{"t":"match.request","id":2}            ->  {"t":"match.request.ok","id":2,"d":{"arenaHost":…}}
{"t":"queue.join","id":3,"d":{"mode":"duel"}} -> {"t":"queue.join.ok","id":3,"d":{"state":"queued",…}}
                                        and later, pushed: {"t":"evt.match.found","d":{"arenaHost":…,"mode":"duel"}}
{"t":"nonsense","id":4}                 ->  {"t":"error","id":4,"d":{"code":"unknown_type"}}
```

The gateway holds connections and makes no product decisions. A session lookup is one `HGET` against the store, so
it does that itself. For everything else it asks `platform`, and passes the answer through without reinterpreting
it. It has no database driver and no way to get one: the module graph says so.

Three rules are enforced, not hoped for:

- a connection that does not authenticate within five seconds is closed;
- a second connection for a player replaces the first;
- an unknown message type gets an error, not silence. So a newer client that meets an older gateway sees a clear
  failure, not a request that never completes.

**Pushes reach a player from any process** (2026-09-27). `handoff/LobbyPush` looks up `conn:{playerId}`, which says
which gateway holds the player, and publishes on that gateway's channel, `push:{gatewayId}`. Each gateway listens on
its own channel. A push is delivered at most once, so anything that must not be missed can also be fetched: a match
found, for example, with `GET /v1/queue` ([03 §5](../docs/detailed-design/03-gateway.md#5-push-routing)).

## The result pipeline

The public arena never ends and never resets. So what gets recorded is a player's **open match**, from arrival to
departure, with no placement and no winner: a continuous room has no field to be placed in
([D-15](../docs/architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches)).
A long open match is checkpointed every ten minutes. Otherwise a player who stays for hours would be paid for none of
it, and would lose it all if the process died. **Timed matches**, with one result for everybody and real placements,
are what the queued modes and tournaments play ([04 §4](../docs/detailed-design/04-platform-services.md#4-matchmaking)).

Either way, the result has to reach MySQL without being lost and without making a room wait:

1. The arena spools the result to a file *before* it touches the network, and deletes the file only once the store
   has accepted the result. So every crash and every outage leaves the result somewhere, and a restart replays what is
   still on disk.
2. The result goes onto the stream `s:match-result`. Each worker reads the stream as a consumer of the group
   `rewards`, and applies each result in one idempotent transaction.
3. The worker acknowledges **after** the commit. A crash before the acknowledgement redelivers the result, which is
   harmless. A crash after an early acknowledgement would lose currency silently.

The list the stream replaced, `q:match-result`, stays as an inbox that the workers drain into the stream
([D-33](../docs/architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)).

The arena reports facts only: kills, deaths, score, placement, play time. It does not decide what they are worth.
`RewardRules` in `worker` does, in one place. An arena that computed rewards would have to be redeployed for a
balance change, and during a rollout two arenas would pay differently for the same match.

It was verified as four separate processes (arena, j-redis, worker, MySQL), with ten bots on a continuous arena:

```
arena:  0 matches ended on a clock    worker: 10 open matches applied
spool:  empty                         ledger: re-derives player.coins exactly
        10 open-match records, 1 player each, placement 0, wins 0
```

The same stack under timed rules produced the opposite shape: four matches of ten players each. It also produced one
useful accident. The bots left three seconds into the last match, so that match has ten `match_player` rows and
**zero xp, zero coins and no ledger entries at all**: it was under the five-second minimum. The history records that
the players were there; they are not paid for it, because otherwise quitting early would be profitable.

## Leaderboards

The score boards rank one metric, the **best score in a single match**, over three windows, all in UTC:
`lb:score:alltime`, `lb:score:day:{yyyy-MM-dd}` and `lb:score:week:{yyyy-Www}`. An all-time board alone would be one
that no new player can ever enter; the short windows are where an ordinary player appears.

**The write is `ZADD … GT`, never `ZINCRBY`,** and that choice is the whole design. A best score is a maximum, and a
maximum is idempotent *and* commutative: the same match applied twice, or two matches in either order, leave the same
board. The queue that feeds the board is at-least-once **by design** (`worker` commits to MySQL and only then
acknowledges), so a redelivery is normal traffic, not an edge case. An increment would count a score twice every time
a worker died in that window.

It is also cheaper than the obvious alternative. Writing an absolute total is idempotent too, but only if the total is
first read back from `player_stat`, which is a `SELECT` per player per match. `GT` needs no read: the store already
holds the previous maximum and keeps whichever is larger.

Two consequences look wrong until you think about the crash:

- The board write runs on **every** delivery, including the ones MySQL recognises as duplicates. If the write ran
  only for a result that is new, a crash between the commit and the board write would lose that ranking for good: the
  redelivery would find the rows already there and skip. The repeat *is* the recovery, and a test fails if that gate
  is added.
- A board failure does **not** hold up the queue. The entry is acknowledged either way, and the miss is counted as
  `unranked`. This pipeline pays players; the board is a projection of what it already wrote.

**Reads are `platform`'s, and `platform` never writes.** "Around me" is one command: j-redis's `J.ZAROUND` returns the
rank and the window together. `ZREVRANK` then `ZRANGE` would be two round trips against a board that other players
write to in between, and the rank could disagree with the window it labels. Names come from an `lb:name` hash, which
`worker` writes from the name MySQL holds when it applies a result, and which a rename writes too
([D-60](../docs/architecture/03-decision-log.md#d-60--a-boards-name-is-the-one-the-database-holds-when-a-result-is-applied)).
So rendering a board costs one extra round trip, not one per name.

Ranks are 0-based in the store and 1-based on the wire: a player is "#1", and the conversion happens once, at the
API, not in every client. Tied players are ordered by member, the player id as a *string*. The order is arbitrary,
but identical on every read.

**What "best" measures, exactly.** A stay in the public arena is checkpointed every ten minutes, and each checkpoint
starts a fresh tally. So the unit being ranked is at most ten minutes of play: the board measures the **best
ten-minute stretch**, not the highest score a tank ever carried. That is defensible, since everyone is measured over
the same bounded unit. But it is not what a player who peaked at 5 000 over an hour will expect, and it is worth
deciding with a real player in front of it. Ranking the peak live score instead would need the simulation to track a
maximum per life, which it does not.

It was verified live across five processes (j-redis, arena, platform, gateway, worker), with forty bots taking the
real client path:

```
lobby:   40 of 40 registered, logged in and got a ticket in 4.9 s
arena:   welcomes 40   disconnects 0   kicks 0   lobby failures 0
worker:  40 matches applied            queue 0   spool empty
boards:  lb:score:alltime, lb:score:day:2026-09-23, lb:score:week:2026-W39, lb:name
         40 members on each, ranked from 1, names resolved
around:  rank 16 of 40, five rows either side, the player in the middle
         a player who has never scored → 404 not_ranked
```

**Not built:** a totals board. Rating boards, the board of teams and their seasons are built
([04 §7](../docs/detailed-design/04-platform-services.md#seasons-designed-2026-10-03-plan-item-71-a)).

**After a store loss.** j-redis keeps an append-only file (AOF), so a restart loses nothing. After a loss of its disk,
`systemctl start backend-leaderboard-rebuild` rebuilds the boards from MySQL (`worker/LeaderboardRebuild`,
[05 §8](../docs/detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)).

## Content, levels and stat upgrades

Balance is **data, not constants in the collision loop**. `Content` bundles a `StatTable`, a `LevelTable` and a
`ShapeTable`, and one instance serves every room in an arena. They travel together because they are not independent:
what a shape is worth only means something against the curve that spends it.

**Eight stats, 45 levels, 33 skill points**, with at most 7 points in any one stat. So four stats can be maxed and a
fifth cannot, and that cap is what makes a build a choice rather than a checklist. The table has two growth modes and
no modifier language:

```
ADD    value = base + perLevel·level + perPoint·points
SCALE  value = base · (1 + perPoint·points)
```

`RELOAD` is why `SCALE` exists. Fewer ticks between shots is better, so its value per point is negative, and a flat
subtraction would reach zero and then go through it.

**Four shape kinds**, because their spread *is* the early pacing curve. With one shape worth one amount, every minute
of progression would feel like the last one:

| Shape | Radius | Health | Experience | Share of spawns |
|---|---|---|---|---|
| Square | 18 | 10 | 10 | 60 % |
| Triangle | 22 | 30 | 25 | 30 % |
| Pentagon | 32 | 100 | 130 | 9.5 % |
| Alpha pentagon | 80 | 3 000 | 3 000 | 0.5 % |

**Score is experience.** The number that levels a tank is the number that adds to the match score, and so to the
leaderboard. Scoring them separately would give a player two scores, and the first time they disagreed the
leaderboard would be reported as broken. They are still different *quantities*: a tank's experience resets on death,
and the match score does not. Score is everything earned; level is what was kept.

**`UpgradeStat` reaches the simulation before the step.** A point spent this tick is in force for the shot this tick
fires. The other way round it would land a tick late, which is invisible in a benchmark and infuriating in a fight.
`EVT_STATS` tells a player their own level, experience and points: at once on a new level or a spent point, and at
most once a second for experience alone. Experience moves every time a shape dies, and streaming it at 15 Hz would
spend 8 % of the whole downstream budget on animating a progress bar.

Two things had to be right and are easy to get wrong:

- **Experience is credited by slot *and generation*.** A bullet often outlives its shooter, and by the time it lands
  the slot may hold a different tank. `playerTag` cannot settle this, because every bot shares tag 0. So the
  shooter's generation is recorded when it fires and checked on impact. Without it, a bot inherits the experience of
  whoever last sat in its seat.
- **Collision reach is the largest radius, not a tank's.** The grid files an entity by its centre, so a query must
  also span the *other* entity's radius. An alpha pentagon is nearly three tank radii, and a bullet grazing one would
  have passed straight through it.

Both have tests that fail if the guard is removed.

**Built since this section was first written:**

- tank classes and the barrel model: 49 classes a player can take (the tick benchmark below measures them), and three
  that only the arena gives, which no player can choose: co-op's Guardian, its enraged form, and Domination's
  dominator;
- equipment, which reaches the arena in the ticket as a capped percentage per stat
  ([D-37](../docs/architecture/03-decision-log.md#d-37--equipment-reaches-the-arena-as-a-capped-percentage-a-stat-in-the-ticket));
- boosts, which `worker` applies to a match's rewards
  ([D-38](../docs/architecture/03-decision-log.md#d-38--a-boost-raises-a-matchs-rewards-in-worker-if-the-match-ended-while-it-ran)).

**Not built:** in-match effects, and loading the tables from JSON. The tables are defaults defined in code. The class,
phrase and skin tables reach the client from `platform` (`/v1/content/*`).

**Every number here is a first cut that has never been played.** The curve meets the design's four anchors by
construction, and the shape values are diep.io's: starting from numbers known to produce a playable match beats
inventing them. "Defensible" is not "tuned".

## Bots, and the Java stand-in for the client

The client is C# ([client/](../client/README.md)), built with `/opt/dotnet`. This machine has no Unity editor (it
needs a licence); Unity 2019.4.28f1's compiler, assemblies and Mono are in `work/unity`, and the client's
`unity2019-check.sh` uses them. The load tools stay Java. `LobbyClient` is the lobby flow (register, log in, hold a
WebSocket, ask for a match), written with nothing but the JDK's HTTP and WebSocket clients.

`BotClient` uses it, so **every bot takes the path a real client takes**: register, log in, lobby, match request,
then the arena that platform named. It used to write tickets straight into the store. That meant a load run never
touched `platform` or `gateway` at all, and measured a path no client will ever use.

```
60 bots, four processes, 25 s
lobby:     60 of 60 registered, logged in and got a ticket in 3.3 s (56 ms each)
arena:     welcomes 60   disconnects 0   kicks 0   lobby failures 0
published: 60            worker applied: 60        spool: empty
```

The first run got 40 of 60 in 21.6 s, with 20 timeouts, which looked like the server struggling. It was not. Probes
showed platform serving 60 concurrent registrations in 2.3 s and 35 logins a second with no failures. The harness gave
each of the sixty bots its own `HttpClient`, all made in one burst before anything throttled them. One shared HTTP
stack, and trying login before register, took it to 60 of 60 in 3.3 s.

## The tick benchmark

This benchmark answers Q-3: the cost of each phase of a tick on real hardware, measured rather than derived on paper.

```bash
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar \
     [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed]   # defaults: 150 1500 20000 22000 1 0
```

- `startLevel` grows every tank to that level as it spawns, with its points spent and its classes taken as a bot
  takes them. That gives a mature room, which a run of minutes from level 1 never reaches, since tanks die and are
  replaced at level 1.
- `mazeSeed`, when it is not 0, gives the room that seed's maze (01 §8.9), whose walls cover 3 000 units a side:
  `8 120 20000 3000 1 2026` is a maze match's room.

It simulates a full room and encodes a snapshot for every client at 15 Hz, so it measures both halves of a real tick.

### Measured on the development VM (12 cores, load average ~8)

There are two scenarios, because map density turns out to matter more than anything else.

**Dense: a 5 700-unit map, so about 12 tanks are visible per client.** This is the density the bandwidth budget
assumes.

| Phase | p50 | p99 |
|---|---|---|
| simulation total | 0.215 – 0.223 ms | 0.78 – 1.48 ms (see below) |
| encode, per 150-client round | 2.1 – 2.2 ms | 5.5 – 6.1 ms |

**p99 on this VM cannot be compared across days, even for the same binary.** The build that measured 0.57 – 0.66 ms
p99 on 2026-09-23 measured 0.92 – 1.48 ms on 2026-09-25. It was rebuilt from the same commit and run interleaved with
the current code, and its p50 stayed at 0.215 – 0.219 ms. The load average was *lower* on the second day (3.3,
against about 8), so it is not simply a busier machine: it is the tail latency of a shared VM. p50 is the number to
compare builds with; only the production hardware can answer Q-3. The A/B run also showed no difference between the
current code and the two builds before it.

- **Simulation: p99 under 1.5 ms at worst**, against NFR-1a's 2 ms. That margin is thinner than it was, for reasons of
  the environment rather than the code.
- **The simulation and the encoder allocate nothing** once running. This has been measured properly only since
  2026-09-26: before that, the tool could not read the counter and printed 0.0 whatever happened. In a 20 000-tick
  run, 6 ticks and 5 of 12 000 encoding rounds allocated. A flight recording shows all of it is the latency
  histograms resizing themselves when a phase takes longer than it ever has (HdrHistogram's auto-resize). A room does
  that a handful of times in its life.
- Mean payload **85.3 bytes**, 13.7 entities per snapshot, so **2.29 KB/s, 8.1 MB/hour** per player with packet
  overhead, against NFR-2's 15 MB/hour.

The traffic rose from 78.0 bytes after the protocol fixes of 2026-09-25, and was not re-measured at the time. The
client's own tank is now in its own snapshot and updated every frame: that is the price of a client knowing its own
position and health. A static entity knocked by a bullet now sends its position too. Shape *drift* was the obvious
suspect, and was measured by turning it off: 0.6 bytes a snapshot. It is not the cost.

**Sparse: a 22 000-unit map, the size in the arena design.**

| | Value |
|---|---|
| simulation p99 | 0.436 ms |
| encode round p50 | 0.565 ms |
| mean payload | 15.7 bytes |
| per player | 1.27 KB/s, 4.5 MB/hour |

### What content and progression cost

Measured against the same benchmark before the change, so the comparison is like for like:

| | Before | After |
|---|---|---|
| simulation p50 | 0.236 ms | **0.211 ms** |
| simulation p99 | 0.551 ms | 0.57 – 0.66 ms |
| mean payload | 68.3 B | **78.0 B** (+14 %) |
| entities per snapshot | 10.9 | **11.9** |
| per player | 2.04 KB/s, 7.2 MB/h | **2.18 KB/s, 7.7 MB/h** |
| allocated per tick | not measured | not measured |

The allocation row read 0.0 B on both sides, because the tool printed that whatever happened, as found on
2026-09-26. See above for what it measures now.

The traffic rise is real and deterministic, not noise. Shapes now come in four sizes, and an alpha pentagon has nearly
three times a tank's radius, so more shapes fall inside a view rectangle. It is still inside NFR-2's 15 MB/hour, with
the margin down from 52 % to 49 %.

Bots spend their skill points as they earn them, which is why this measures anything at all. Reload is a stat, and
the number of bullets is what a tick costs. Bots that saved their points would have left the benchmark measuring
level-1 fire rates, and reporting a number the real thing would never reach.

### What classes cost (2026-09-27)

The dense benchmark: the build before tank classes against the build with them, run interleaved twice (before,
after, before, after) on the same VM.

| | Before | After |
|---|---|---|
| bullets in flight, steady state | 606 | **830** (+37 %) |
| simulation p50 | 0.336 – 0.348 ms | **0.458 – 0.460 ms** |
| simulation p99 | 0.80 – 0.91 ms | **0.98 – 0.99 ms**, half of NFR-1a's 2 ms |
| encode round p50 | 2.50 – 2.55 ms | 2.81 – 2.82 ms |
| mean payload | 77.8 B | 79.0 B |
| steady-state allocation | none | none |

The cost is the point. Bots take a class at level 15 as they spend their points, and Twins, Machine Guns and Flank
Guards fire more bullets than Basic: that is what a room of players choosing classes will do. The payload hardly
moves, because a bullet is one create for its whole life (D-9). This day's p50 is not comparable with the 0.215 ms
above, which was measured on another day: only the interleaved pair is.

### What the second tier costs (2026-09-27)

The dense benchmark from level 45 (`startLevel`), so that every bot has taken a class of each tier: the build before
the second tier against the build with it, interleaved twice, both with the shape mix held steady (M-12).

| | Before | After |
|---|---|---|
| bullets in flight | 1 193 | **2 247**, of which 221 traps and 54 drones |
| simulation p50 | 0.79 – 0.83 ms | **1.64 – 1.68 ms** |
| simulation p99 | 1.50 – 1.84 ms | **2.90 – 2.93 ms: over NFR-1a's 2 ms** |
| encode round p50 | 3.23 – 3.29 ms | 4.34 ms |
| mean payload | 82.3 B, 7.9 MB/hour | 92.0 B, 8.4 MB/hour |

A room grown from level 1 over the same 13 minutes, where most tanks are still Basic: **0.63 ms p50, 1.22 ms p99**.
The cost is the collision pass, and it follows the bullets: Quad Tanks, Twin Flanks and Gunners fire four a volley,
and a trap stays in the air for 24 s. **The mature room is over budget on this VM**, with every bot firing without
pause, which players do not do. This is recorded against Q-3, to be settled on the production hardware, with the
levers it has: fewer players a room, the reloads of the classes with many barrels, and the grid.

Two things were tried, and not kept:

- **Reaching less far** for the collision query (40 units past a bullet's radius instead of 80, the alpha pentagon's,
  with the few larger shapes checked one by one) made the tick **twice as slow**. There were 69 alpha pentagons in
  the room, not the seven the table's weights give, and every bullet walked the list. That is how M-12 was found.
- **A grid of 100-unit cells** instead of 200 cut the collision pass by a fifth in the mature room, but made encoding
  a fifth slower in every room, since each view walks four times the cells: a loss where it matters most.

### What the third tier costs (2026-09-28)

The same dense benchmark from level 45: the second tier's build against the third's, interleaved twice. Bots now take
a class of all three tiers, 30 of the 45 at level 45, the Smasher line among them.

| | Second tier | Third tier |
|---|---|---|
| bullets in flight | 2 247, of which 221 traps and 54 drones | **2 193**, of which 867 traps, 21 drones and 12 minions |
| simulation p50 | 1.69 – 1.72 ms | 1.63 – 1.75 ms |
| simulation p99 | 3.83 – 4.16 ms | 3.93 – 4.29 ms |
| collision pass p50 | 1.48 – 1.51 ms | 1.39 – 1.50 ms |
| encode round p50 | 4.44 – 4.49 ms | 4.38 – 4.44 ms |
| mean payload | 92.0 B, 8.4 MB/hour | 87.4 B, 8.2 MB/hour |
| ticks that allocated | 3 – 4 of 20 000 | 4 – 6 of 20 000 |

**The third tier costs what the second did**, within the noise, which is what D-22 was for: no class keeps more alive
than the second tier's most, so the worst case did not move. Traps quadrupled, since the Trapper's line is now five
classes. Bullets fell a little, since a quarter of the bots took the Smasher's road and fire nothing, and the payload
fell with them.

**The p99 is the VM's, not the build's.** The second tier's own build measured 2.90 – 2.93 ms the day before and
3.83 – 4.16 ms on this day, on the same inputs, which are deterministic: the same 2 247 bullets both days. The worst
case stays over NFR-1a's 2 ms on this machine either way; Q-3 is where that is settled. A room grown from level 1
with the whole tree: **0.70 ms p50, 1.77 ms p99**, 7.8 MB an hour.

**The first run found M-14.** The third tier's build allocated in **every tick, 20 000 of 20 000**, about 460 bytes
each, in the mature room and in the one grown from level 1 alike. Each time any tank broke a square, the room asked
the tank's class for a Necromancer's convert barrel through an iterator. The numbers above are after the fix. Before
it, the tick cost the same, and the collector would have paid the rest. Only this benchmark was looking, so a test
now holds the rule (`AllocationTest`).

### What the rest of the tree costs (2026-09-28)

The same benchmark: the third tier's build against the whole tree's (49 classes: the Battleship, the Rocketeer, the
Skimmer and the Mega Smasher added, traps knocked, the Predator's zoom), interleaved twice.

| | Third tier | The whole tree |
|---|---|---|
| in the bullet list | 2 193: 867 traps, 21 drones, 12 minions | 2 187: 775 traps, **118 drones**, 7 minions |
| simulation p50 | 1.76 – 1.77 ms | **1.99 – 2.00 ms** |
| collision pass p50 | 1.51 ms | 1.67 – 1.68 ms |
| simulation p99 | 4.40 – 5.67 ms | 5.36 – 5.46 ms |
| mean payload | 87.4 B, 8.2 MB/hour | 93.5 B, 8.5 MB/hour |
| ticks that allocated | 5 – 7 of 20 000 | 5 – 6 of 20 000 |

**The whole tree costs 13 % more at p50, and the drones are why.** D-22 bounds what a class keeps alive in bullets
and traps; it leaves drones to each class's cap, and the Battleship's cap is 16. With five Battleships among the 150
bots, drones went from 21 to 118, while traps fell by about as many. So the list is the same length, but each entry
costs more: a drone steers every tick and gathers where its tank attacks, and a trap lies still. The payload rose with
them, since drones are updated every frame; 8.5 MB an hour is well inside the budget.

**Whether that stands is a balance question, and it is open.** The choices are the Battleship keeping eight drones,
as the Overseer does, or D-22 counting drones as it counts bullets. The p99 is the VM's again: the third tier's own
build ranged from 4.40 to 5.67 ms within the hour. A room grown from level 1 with the whole tree: **0.72 ms p50,
2.04 ms p99**, 7.9 MB an hour.

### What the measurements changed

**NFR-1 could not be measured as written.** It said "room tick ≤ 4 ms p99" without saying whether encoding counted.
The simulation alone is 0.55 ms; a tick that also encodes for 150 clients is 6.2 ms. The requirement is now split into
NFR-1a (simulation) and NFR-1b (snapshot tick), both measured and both passing.

**Traffic is about half the estimate.** The design budgeted about 122 bytes of payload; the measurement was 68.3.
Deltas compress better than the hand calculation assumed, and D-9 means shapes and bullets are created once and never
updated. Treat 68 as a floor, though: names and events were not implemented then, and both add bytes.

**The map size and the traffic budget contradict each other (Q-4).** A 1 600-unit view over a 22 000-unit map covers
0.53 % of it, so an evenly spread room of 150 tanks puts **0.8 tanks** in view, not the 12 the budget assumes. Either
the map is far smaller than 22 000 units, or players cluster hard enough to make the average meaningless. Until that
is settled, the dense numbers are the ones to plan with, because they are the pessimistic case.

### Wall-clock latency on this machine is scheduler-bound, not code-bound

The in-process benchmark and the running server measure different things, and the gap is large enough to be worth
stating plainly.

| | p50 | p99 |
|---|---|---|
| Benchmark: CPU cost per tick | 0.20 ms | 0.55 ms |
| Running server, 120 bots: wall-clock per tick | 0.19 ms | **38 ms** |
| Running server, **zero clients**: wall-clock per tick | 0.08 ms | **11 ms** |

The third row decides it. With no clients, no encoding and no network traffic, a room doing 0.08 ms of work still
shows an 11 ms p99 and a 115 ms maximum. **That is thread scheduling, not the simulation.**

The mechanism: the benchmark runs flat out and never yields, so it is never descheduled. A real room thread parks for
about 39 ms between ticks, and has to be scheduled back in every time. On this development VM that was at a load
average of 11 on 12 cores, with a second JVM running 120 bots beside it. `overruns` stayed at 0 throughout, so the
deadline logic held: the thread simply woke late.

**What this means:** the CPU-cost numbers are trustworthy and portable. The wall-clock numbers are a property of this
VM, and cannot validate NFR-1a or NFR-1b. Both need measuring again on the production machine, with `taskset` pinning
and cores that are not oversubscribed. That is exactly why
[07 §6](../docs/detailed-design/07-threading-and-performance.md) prescribes pinning, now with evidence rather than as
a precaution.

### Three caveats

**The tail is the VM, not the algorithm.** Maxima reach 46 ms on a machine with a load average of 8 on 12 cores.
Measure again on a quiet machine.

**An earlier version of this benchmark was wrong in a way that flattered it.** It spawned 150 tanks and never
respawned them, so the room thinned to 106 and the collision pass did proportionally less work.

**And the simulation had a degenerate bug, which the encoder exposed.** Tanks were created without an initial
heading, so every one started at angle 0 and accelerated identically. The whole room moved in lockstep, relative
positions never changed, and the encoder correctly sent nothing. It was found because an encoder test expected
updates and got empty snapshots. Fixing it cut the simulation's p99.9 from 3.4 ms to 1.1 ms.

## The apply benchmark

This benchmark answers the other half of Q-3, for `worker`: how many results a second MySQL takes, from one thread
and from several at once (05 §1, plan item 26). Each thread applies one-player results through the worker's own
transaction, on a connection of its own. Commits made at the same moment share their synchronous writes, so the
answer is a rate for each thread count. It tells how many threads a worker process should have, or how many
processes.

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     <jdbc url> <user> <password> [results] [threads,...] [mysqld pid]   # defaults: 400 1,2,4,8
```

It drops everything in the database and migrates it afresh, so it refuses any URL but one that names a single
database ending in `_test`. Given the pid of a mysqld on the same machine, it also reports that process's CPU per
result.

Measured on the development machine (2026-09-30): 18–19 results a second from one thread, 27–34 from two, 66–69 from
four, 104 from eight. MySQL's CPU per result, most of it the kernel's work in the synchronous writes, falls as the
threads rise, since one write serves every commit waiting for it.

## The rank benchmark

This benchmark measures what a player's own place on a rating board costs (04 §7, plan item 58, D-35): the duel
board's listed players among many accounts that were never rated, read as `RatingBoards.place` reads them. For a
listed player at the top, the middle and the bottom, it prints the median and the slowest of the reads, and the
index rows MySQL read for one (the session's handler reads, on the connection held for it). It prints the same for
the top hundred.

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     <jdbc url> <user> <password> [accounts] [listed] [reads]   # defaults: 200000 5000 21
```

It drops everything in the database and fills it, so it refuses any URL but one that names a single database ending
in `_test`. Measured on the development machine (2026-10-02), with 200 000 accounts and 5 000 listed. Before
migration V20 (an index of the listed players alone, D-57; since squashed into V1, D-75), the middle and the bottom
took 209 and 200 ms, reading about 200 000 rows each. After it: 14 and 18 ms, reading 5 021 and 10 016 rows.

## Design rules this code follows

| Rule | Where |
|---|---|
| Single writer per room; no locks, no concurrent collections | [07 §1](../docs/detailed-design/07-threading-and-performance.md) |
| Zero allocation in the tick loop | [07 §2](../docs/detailed-design/07-threading-and-performance.md) |
| The pool is a hard ceiling; spawns are refused, never grown | [07 §8](../docs/detailed-design/07-threading-and-performance.md) |
| Dead slots recycled only at the end of a tick | `World.sweep` |
| Every entity carries a wire class | [01 §2](../docs/detailed-design/01-arena.md), D-9 |
| Histograms per phase, not just a total | [07 §4](../docs/detailed-design/07-threading-and-performance.md) |
| Deltas against the last frame sent, in world space | [D-16](../docs/architecture/03-decision-log.md), `ClientView` |
| Handles not reused until the removal is acknowledged | [02 §5](../docs/detailed-design/02-networking.md), `ClientView` |
| Rank, then truncate, so tanks crowd out distant shapes | [02 §7](../docs/detailed-design/02-networking.md), `SnapshotEncoder` |
