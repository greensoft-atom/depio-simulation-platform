# client

This folder holds the full C# client of the backend: the code that the Android app, built in Unity, uses to talk to
the servers. It is for client developers who change that code or build the app from it. This page lists the
projects, says how to build, test and check them, how to run them against the real backend, what is built so far,
and what the live drills found.

A player installs the app on Android from the owner's own copy, not from a store (C-10). The design is in
[docs/detailed-design/08-client.md](../docs/detailed-design/08-client.md), and its diagrams are in
[docs/diagrams/06-client.md](../docs/diagrams/06-client.md). The client has two layers
([D-19](../docs/architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)):

- the **core**, `Backend.Client.Core`: a library with no engine in it, which does all the talking to the servers.
  It is built and tested on this machine against the real servers;
- the **Unity layer**: thin scripts that connect the core to Unity and draw what it holds.

Two words used on every page: **TLS** (Transport Layer Security) is the encryption that `https://` and `wss://` use;
the **API** (application programming interface) is the platform's HTTP routes, `/v1/...`. Every other term is in
the [glossary](../docs/glossary.md).

## Which client to use

There are three clients. They speak the same protocol; a protocol change is made in all three.

| Folder | What it is | Who it is for |
|---|---|---|
| `client/`, this folder | The full client: the core (with prediction of the player's own tank, the world and what a frame draws), the Unity package, the tests and the headless driver | client developers, and the app |
| [`client-simple/`](../client-simple/README.md) (D-111) | A smaller client: the API (over `HttpClient`, D-112), the lobby and a match, in seven files of C# 7.3 that use only .NET Framework 4.5's API (D-116); three example scripts. No prediction, no world, no drawing | a UI team that reads the code and pastes it into its own Unity project |
| [`client-simple-no-tls/`](../client-simple-no-tls/README.md) (D-114, D-115) | `client-simple` without TLS, in six files: for the site's port 80, and for a backend run plain | the same team, where TLS cannot be used |

## Projects

| Project | What it is | Target | Depends on | Its README |
|---|---|---|---|---|
| `Core/` | `Backend.Client.Core`: everything that speaks to the server, and what a frame draws, with no engine in it. It holds the wire format, the world, the match connection, the prediction of the player's own tank, the API, the lobby, the scene and its clock, the touch sticks, the account kept on the device, and JSON | .NET Framework 4.5, C# 7.3 (for Unity 2019.4 at its .NET 4.x API level, and Visual Studio 2019; D-116) | nothing | [Core/README.md](Core/README.md) |
| `Core.Tests/` | NUnit tests of the core: the server's golden vectors, a scripted arena on a local socket, scripted HTTP answers. They are C# 7.3, so Unity 2019.4's compiler builds them: `core-tests.sh` compiles them with it and runs them on Unity's Mono by NUnitLite. NUnit is what Unity's Test Runner uses, but no Unity editor has run them here | .NET Framework 4.5, C# 7.3 (D-116) | Core; NUnit 3.14.0, NUnit3TestAdapter 3.17.0, Microsoft.NET.Test.Sdk 16.11.0, System.ValueTuple 4.5.0, Microsoft.NETFramework.ReferenceAssemblies 1.0.3 | [Core.Tests/README.md](Core.Tests/README.md) |
| `Headless/` | The core driven without Unity against a running backend, scenario by scenario, as a player would use it. The live drills run it | .NET Framework 4.5, C# 7.3 (D-116) | Core; System.ValueTuple 4.5.0, Microsoft.NETFramework.ReferenceAssemblies 1.0.3 | [Headless/README.md](Headless/README.md) |
| `Unity/com.backend.client/` | The Unity layer: a UPM (Unity Package Manager) package of thin scripts over the core, and its iOS and Android plugins for the secure store | Unity 2019.4 LTS and later, at the .NET 4.x API level (D-116) | the core, as the library that `unity-package.sh` copies in | [Unity/com.backend.client/README.md](Unity/com.backend.client/README.md) |
| `UnityCheck/` | Compiles the package's scripts against stubs of the Unity API, once for each platform branch, and its Android plugin against stubs of Android's API. Nothing is run | .NET Framework 4.5, C# 7.3 | Core | [UnityCheck/README.md](UnityCheck/README.md) |
| `UnityRuntimeCheck/` | The core on Unity 2019.4's own Mono: certificates made by `private-ca.sh` checked, JSON read and written under a locale that writes a decimal comma, and the API, the lobby and a match each through TLS (`Program.cs`, which `unity2019-check.sh` builds and runs). Also `RunTests.cs`, which runs Core.Tests there by NUnitLite (`core-tests.sh`) | .NET Framework 4.5, C# 7.3, Unity 2019.4.28f1's compiler | Core | this table, and [08 §6](../docs/detailed-design/08-client.md#6-testing) |

**The solution.** `Backend.Client.sln` is the client's one solution. It is a **Visual Studio 2019** solution (D-108,
D-109), and it holds all four projects (Core, Core.Tests, Headless, UnityCheck). Each is C# 7.3 on .NET Framework 4.5
alone (D-116).

- Visual Studio 2019 builds the solution with a .NET SDK of 5.0 or older. VS 2019 16.11 installs the 5.0 SDK; an SDK
  of 6 or later asks for MSBuild 17.
- VS 2019 builds Core.Tests and Headless, and it runs the tests in its Test Explorer.
- No project targets .NET 8 or any modern .NET, and nothing here runs on it (D-116). Every C# build here is Unity
  2019.4.28f1's compiler (Roslyn 2.9.1) against Mono's .NET Framework 4.5 reference assemblies, and every run is
  Unity's Mono 5.11. The tests run with `core-tests.sh`, and the drills build the driver for each run. Only
  `vs2019-check.sh` uses .NET SDK images, as VS 2019's build tools.
- A .NET Framework 4.5 project, or a later one, can reference the core's project, or take its source files as they
  are. `vs2019-check.sh` builds a 4.5 app that references the project; the scripts here compile the source files.
- Every project builds with warnings as errors and with nullable reference types off.

```
client/
├── Backend.Client.sln          Visual Studio 2019's solution: the four projects below
├── Core/                       Backend.Client.Core: no dependencies
│   ├── Wire.cs                 the protocol's numbers, mirroring com.backend.protocol
│   ├── WireReader.cs           fields and varints over one frame; ProtocolException
│   ├── Snapshot.cs             the snapshot decoder, streaming into a sink
│   ├── Messages.cs             Welcome, Pong, Kick; the client's own messages; stream framing
│   ├── ClientWorld.cs          what the client holds between snapshots
│   ├── Maze.cs                 a maze's walls from its seed, made as the server makes them
│   ├── MatchConnection.cs      join, play, lose, resume, leave: 08 §3; a match through the site's 80 (D-116)
│   ├── TankMotion.cs           the room's step of a driven tank, and a maze's walls by cell: 02 §9
│   ├── OwnTank.cs              the player's own tank, predicted: steps, replay, corrections (08 §4, D-62)
│   ├── ApiClient.cs            the platform API, its endpoints tried in turn, and what it answers
│   ├── LobbyClient.cs          the lobby WebSocket: auth, ping, requests, pushes
│   ├── WebSocketLink.cs        the lobby's WebSocket itself, RFC 6455's client over TCP or TLS (D-108)
│   ├── TrustRoot.cs            the backend's root certificate, which the client checks by itself (D-82, D-108)
│   ├── TlsVersions.cs          TLS 1.2 and 1.1 asked for by name, as .NET Framework 4.5 offers 1.0 alone (D-116)
│   ├── WebRequestTransport.cs  the API's requests through HttpWebRequest, each checked for its host (D-108)
│   ├── PartyState.cs           the party as told, the newest state kept (04 §4, D-74)
│   ├── Scene.cs                what a frame draws where, at a render tick, and the render clock: 08 §8
│   ├── TouchSticks.cs          two sticks, or keys and a mouse, to an input: 08 §8
│   ├── AccountClient.cs        the account service: sign up, log in, refresh, log out, a password, a deletion (D-117)
│   ├── AccountKeeper.cs        what signs the device in, a refresh token or a guest's key, in a secure store: 10 §5.1
│   └── Json.cs                 strict JSON for the API and the lobby, so the core needs no package
├── Core.Tests/                 NUnit, and the vectors the server's tests read, linked, never copied
│   └── Pki/                    the tests' certificates, made by Pki/make.sh
├── Headless/                   the core driven without Unity against a running backend
├── Unity/com.backend.client/   the Unity layer: thin scripts over the core (08 §8)
├── UnityCheck/                 the scripts compiled against stubs: nothing run
├── UnityRuntimeCheck/          what unity2019-check.sh and core-tests.sh run on Unity's Mono
├── unity-env.sh                sourced by the scripts: Unity 2019.4.28f1's compiler and Mono, fetched once (D-116)
├── core-tests.sh               the core's tests, compiled by Unity's compiler and run on its Mono
├── unity-package.sh            builds the core into the Unity package
├── unity-check.sh              the check against stubs, in every platform branch
├── unity2019-check.sh          the core and scripts by Unity 2019.4.28f1's compiler, the core and its tests on its Mono
├── vs2019-check.sh             the core built by Visual Studio 2019's MSBuild and Roslyn, for .NET Framework 4.5
└── headless-drill.sh           starts the backend from a release and runs the headless driver against it
```

## Building, testing, packaging, checking

Run these commands from `client/`. No .NET 8, nor any modern .NET, is used here (D-116). Every build is Unity
2019.4.28f1's compiler (Roslyn 2.9.1, C# 7.3) against Mono's .NET Framework 4.5 reference assemblies, and every run
is Unity's Mono 5.11. Both come from Unity's Linux editor, which `unity-env.sh` downloads once into `work/unity`
(1.8 GB, its SHA-256 checked); each script below sources it. The core itself has no package dependencies. The tests
and the driver take their packages from nuget.org once, into `work/unity/nuget`, each SHA-256 checked: NUnit and
NUnitLite 3.14.0, and System.ValueTuple 4.5.0 (their tuples, which .NET Framework 4.5 does not have).

```bash
cd client
./core-tests.sh                                   # the core's tests: read "Test Count:"; TESTS="class =~ MatchConnectionTests" for some
./unity-package.sh                                # the core, optimised, into the Unity package
./unity-check.sh                                  # the package compiled against stubs
./unity2019-check.sh                              # Unity 2019.4.28f1's compiler and Mono (D-108; csc.rsp, D-113), then core-tests.sh
./vs2019-check.sh                                 # Visual Studio 2019's MSBuild and Roslyn, in docker (D-108)
. ./unity-env.sh && build_headless ../work/headless   # the driver and the core, by hand; the drills build their own
```

**Check the count, not only the verdict.** `core-tests.sh` prints NUnitLite's summary, `Test Count: 196, Passed: 196,
Failed: 0, …`, and says `CORE TESTS PASSED` only when none failed. A run that dies part of the way through prints no
summary; the script then says so, and fails.

What each script does, and what it needs:

| Script | What it does | What it needs |
|---|---|---|
| `unity-env.sh` | Sourced, not run. Fetches Unity's editor once, and sets `MONO`, `CSC`, `FX45` (the 4.5 references) and the helpers the other scripts use: `fx45_refs`, `nuget_lib`, `valuetuple_lib` and `build_headless` | curl and the network once; 1.8 GB in `work/unity`. `UNITY_DATA` names another copy of the editor's `Editor/Data` |
| `core-tests.sh` | Compiles the core and its tests for .NET Framework 4.5 with Unity's compiler, warnings as errors, and runs them on Unity's Mono by NUnitLite, with `float32` as Unity's Mono has it (P-65). `TESTS=<an NUnit where-expression>` runs some of them | Unity's editor in `work/unity`; NUnit, NUnitLite and System.ValueTuple, from nuget.org once |
| `unity-package.sh` | Compiles the core's sources for .NET Framework 4.5 with Unity's compiler, optimised, into `Unity/com.backend.client/Runtime/Plugins/Backend.Client.Core.dll`. Git ignores that copy | Unity's editor in `work/unity` |
| `unity-check.sh` | Compiles the core, then the package's scripts against it and stubs of the Unity API, for no platform, the editor, iOS and Android; and the Android plugin's Java against stubs of Android's API. Nothing is run ([UnityCheck](UnityCheck/README.md)) | Unity's editor in `work/unity`, and `javac` (`JAVAC`) |
| `unity2019-check.sh` | Compiles the core for .NET Framework 4.5 with Unity 2019.4.28f1's own compiler, and the package's scripts against Unity's own `UnityEngine` at its .NET 4.x API level, the one that takes the core, in each platform branch, with the package's `csc.rsp` as Unity reads it; checks that the scripts are refused without it. Then runs the core (`UnityRuntimeCheck`) on Unity's own Mono 5.11, and the core's tests (`core-tests.sh`) | Unity 2019.4.28f1's Linux editor in `work/unity`; NUnit, NUnitLite and System.ValueTuple, from nuget.org once |
| `vs2019-check.sh` | Builds with Visual Studio 2019's MSBuild and Roslyn, from Microsoft's SDK images, used as VS 2019's build tools alone: 16.11 (the .NET 5 SDK) and 16.7 (the .NET Core 3.1 SDK). It builds the core's project for .NET Framework 4.5; a 4.5 app that references it; Unity 2019.4's project as VS 2019 compiles it (.NET Framework 4.7.1, as Unity generates it, with `csc.rsp`'s `System.Net.Http`), in each platform branch; and `Backend.Client.sln`. Nothing is run on .NET Framework itself | docker; the network once, for the images and the packages; Unity 2019.4.28f1's editor in `work/unity` |

`unity-check.sh` takes `JAVAC`; by default it uses the `javac` on the PATH, and otherwise `/opt/jdk21/bin/javac`.

**The library in the package.** Run `unity-package.sh` before you open the package in Unity, and again whenever the
core changes. `unity-check.sh` compiles the core's sources itself, not that copy, so it cannot tell when the copy is
stale.

**The API level.** Unity 2019 takes the core's library at its .NET 4.x API level alone (Player settings, Api
Compatibility Level). At .NET Standard 2.0 it cannot (D-116).

**`csc.rsp`.** The package's `Runtime/csc.rsp` gives the package's assembly a reference to `System.Net.Http`. Unity
does not reference that assembly by default at the .NET 4.x API level (D-113). `unity2019-check.sh` compiles the
scripts as Unity does: with that file they compile, and without it they are refused.

## Against the real server

A **drill** starts the whole backend from a release, on ports of its own, and runs the headless driver against it.
Each path a player takes is a **scenario** ([glossary](../docs/glossary.md#9-building-checking-and-running)).
`headless-drill.sh` does it all and stops every process on the way out.

```bash
TMPDIR=<scratchpad> ./headless-drill.sh [scenario ...]                 # straight to the processes, in clear
FRONT=http TMPDIR=<scratchpad> ./headless-drill.sh play lobby duel     # through nginx's 80, a match by the arena's name
SIMPLE=1 FRONT=plain TMPDIR=<scratchpad> ./headless-drill.sh play      # through nginx run plain; the simple clients' live checks first
```

**In clear alone.** The drill runs the C# client on Unity 2019's Mono. Outside the editor, Mono's own TLS stops at
1.0, and nginx and an arena's own TLS speak 1.2 and 1.3. So the drill drives the client in clear: `TLS=1`,
`FRONT=https` and the scenario `untrusted` are refused (D-116). The core's TLS is tested by `core-tests.sh`, between
its own two ends, at TLS 1.0.

**What it needs.** The script says what it is missing, and where it looked.

- The backend's release (`backend/scripts/make-release.sh`). A release carries its own Java runtime, j-redis and
  MySQL, and the drill takes them from it. For an older release without them: a JDK 21 (`/opt/jdk21`), j-redis
  built, and `/usr/sbin/mysqld`.
- Unity 2019.4.28f1's editor in `work/unity`, which `unity-env.sh` fetches once. The drill builds the headless driver
  with its compiler for each run, and runs it on its Mono.
- python3.
- The development MySQL (`dev-mysql.sh`, 127.0.0.1:3314). The drill starts it when it is not running, and stops it
  after. The drill uses the databases `backend_dev` and `account_dev`, which a run by hand uses too, so do not run the
  two at once.
- The account service built (`dev-account.sh build`, D-117): the drill runs it from `account-service/target` on 8097,
  with a signing key of its own, until the release carries it (plan item 142 (f)).
- With `NEWMAN=1`: `npx`, which runs newman.

**What it runs.** The headless driver, built for the run (`build_headless`, in `unity-env.sh`) and run on Unity's
Mono with `float32`. It is given the API at `http://127.0.0.1:8093`, `--account http://127.0.0.1:8097` and `--lobby
ws://127.0.0.1:8094/lobby` (with `FRONT`, nginx's addresses on 8095 for the API and the lobby; the account service is
reached straight until the site routes it). It also gives the driver the
operator's API and a way to write the drill's fixtures (see [Headless/README.md](Headless/README.md)). With no
scenario named, it runs `play resume badticket lifecycle`.

**The scenarios**, in the driver's order: `play prediction equip boost team tournament teammatch teamcup rename apply
roundrobin levels gems purchase pass skin milestone season achievements goals social guest stats domination tag maze
sandbox resume coldresume badticket lifecycle untrusted lobby replaced badsession duel walkover party decline rffa
coop banned notice erase account removed phrase`. `untrusted` needs a match over TLS and a second root, which the drill
cannot give the client on Unity's Mono: it refuses that scenario (D-116). What each scenario checks, and what it
needs, is in [Headless/README.md](Headless/README.md#scenarios).

**The modes.** You choose them with environment variables:

| Variable | Default | What it does |
|---|---|---|
| `TLS=1` | 0 | Refused (D-116): the client on Unity's Mono cannot speak the arena's TLS 1.2. It was the arena over TLS, with a test certificate made for the run |
| `FRONT=http` or `plain` | unset | The release's nginx in front of the drill's processes, set up as `dev-nginx.sh` sets it up, on ports of its own: 8095 for 80, 8096 for 443, 9095 for 9001 (D-107). `http`: nginx with TLS on 8096 and 9095, as the site runs it, and the client through 8095 alone: the API and the lobby in clear, and each match through 8095 by `/match/` and the arena's name, `a1.arena.internal` (D-115, D-116). `plain`: nginx without TLS (D-101), the API and the lobby on 8095 and matches plain on 9095. `FRONT=https` is refused (D-116) |
| `SIMPLE=1` | 0 | Before the scenarios, the live check of [`client-simple`](../client-simple/README.md) (D-111): the API, the lobby and a match, on Unity's Mono. It goes straight to the processes, or through nginx with `FRONT=plain`; not with `FRONT=http`, whose grants ask for TLS. Then, in every mode, the live check of [`client-simple-no-tls`](../client-simple-no-tls/README.md): straight, or through nginx's 80 with `FRONT`, its match through `/match/` when the grant asks for TLS (D-114, D-115) |
| `NEWMAN=1` | 0 | First, on the fresh backend, the Postman collection (`docs/api/backend.postman_collection.json`) through the same API address, run by newman |
| `DRAIN_AFTER=<s>` | unset | Stops the arena `s` seconds in (SIGTERM), so it drains (01 §8.6) |
| `FAILOVER=1` or `demote` | 0 | The store with a replica (6392). The drill runs the scenarios. Then it kills the primary and `promote-store.sh` promotes the replica (`1`), or it demotes the old primary and keeps it alive (`demote`). It looks for every process's subscription on the new primary, and runs the scenarios again (D-34, O-6). `demote` runs the scenarios only, not the bots |
| `MYSQL_FAILOVER=1`, `demote` or `freeze` | 0 | Two MySQL servers of the drill's own (3307 and 3308, with GTIDs, the replica 30 s behind). The drill runs the scenarios. Then the primary is killed (`1`), stopped by SIGSTOP with platform's first answer timed (`freeze`, O-9), or handed over and fenced (`demote`); `promote-mysql.sh` promotes the replica; the scenarios run again. Then the old primary is rebuilt by clone and handed back to, and the scenarios run a third time (D-35). `freeze` runs the scenarios only, not the bots |
| `BOTS=<n>[:<s>]` | s = 120 | The load run (07 §4): `n` bots of `tools/BotClient` play for `s` seconds in place of the scenarios. It reports each process's cores, the arena's threads and numbers, and NFR-2 per connection. `ARENA_OPTS` adds to the arena's JVM options |
| `BOTS` with `FAILOVER=1`, or with `MYSQL_FAILOVER=1` or `demote` | | The failover under load (architecture/02 §6): the primary is killed or handed over halfway through, and the replica promoted 20 s after the bots leave. It checks that every stay is paid once, and times how long a probe was refused |
| `BOTS` with `SOAK=1` | 0 | The soak (07 §4): stays of `STAYS` seconds (60:300) are played again and again, the duel scenario runs every 30 s, every process is sampled every `SAMPLE_EVERY` s (300), and `tools/SoakJudge` judges from `JUDGE_FROM` s (by default half the run) |
| `BOTS` with `WORKERS=<k>` | unset | The workers' rate: `k` workers are paused while the bots play, then drain their stays together, timed |
| `TAKEOVER=1` | 0 | The worker is stopped with the results in flight, then killed, and a second worker takes them over (05) |
| `WATCH=1` | 0 | The platform watches the four processes and sends alerts to a receiver of the drill's (9194). After the scenarios the drill kills the worker; `TargetDown` must be listed by the admin API and sent within three minutes (04 §12, D-86). Works with `BOTS` too. Not with `TAKEOVER` |
| `IDLEGUEST=1` | 0 | A guest is made and aged 181 days in the database. A second worker (its metrics on 9193) erases it by retention a minute after it starts (D-87) |
| `RELEASE`, `JREDIS_JAR`, `JREDIS_CLI_JAR`, `JAVA`, `MYSQLD_BIN`, `UNITY_DATA`, `TOOLS_JAR` | the release under `backend/target/release`, and its own j-redis, runtime and MySQL (for a release without them: j-redis's build, `/opt/jdk21/bin/java` and `/usr/sbin/mysqld`); Unity's editor in `work/unity`; the tools' jar | Where things are |
| `MYSQL_PORT` | 3314 | The development MySQL that the drill's backend uses, and that the drill starts (`dev-mysql.sh`) |

**Ports.**

| Port | What |
|---|---|
| 6390, 6392 | j-redis; its replica with `FAILOVER` |
| 8093, 8094, 9011 | platform, the gateway, the arena |
| 8097 | the account service (D-117) |
| 9195, 9196 | platform's metrics, platform's admin API |
| 9197, 9198, 9199 | the metrics of the arena, the gateway and the worker |
| 3314 | the development MySQL (`dev-mysql.sh`), started when it is not running |
| 3307, 3308 | the drill's own MySQL servers, with `MYSQL_FAILOVER` |
| 9193 | a second worker's metrics, with `IDLEGUEST` |
| 9194 | the alerts' receiver, with `WATCH` |
| 8095, 8096, 9095 | nginx in front, with `FRONT`: for 80, 443 and 9001 |

Before anything starts, the drill tries every port that the run's modes use (`check-bind.sh`), and names one that is
in use or that a policy of the machine refuses. Logs go to a directory under `TMPDIR`, which the script names first.
After the run it prints the matcher's metrics, the gateway's latency to platform, the arena's log, and the
database's view of the last match of each mode.

## Built so far

08 §7, steps 1 to 4, and §8 are built:

- the wire, the world, the match connection (through the site's 80 too, D-116), the API and the lobby;
- the prediction of the player's own tank. Its movement rule matches, bit for bit, a trajectory that the server's own
  room wrote (`backend/arena/src/test/resources/motion-2026.txt`);
- what the Unity layer draws at a render tick, the sticks turned into an input, and the account kept across launches;
- the Unity package's scripts. They are compiled against stubs and by Unity 2019.4.28f1's own compiler, but not yet
  run in Unity.

How it is checked:

- **The decoder** against every decoded field of every [golden vector](../protocol-spike/vectors/vectors.txt), the
  same file that the server's tests read.
- **The world** against the server's reference client, down to its rounding: Java rounds half up and .NET rounds
  half to even, and a bullet at heading 400, seventeen ticks on, is where the two differ.
- **The connection** against a scripted arena: every kick, a desync, the resume backoff and its minute, the input
  cadence and the pings; and through a scripted front's 80. Against the real arena by the drill, in clear: straight,
  through nginx's 80 by the arena's name, and through nginx run plain (D-116).
- **The API and the lobby** along the whole path in the drill: log in, the lobby's match request, a match, and the
  worker's reward read back from the inventory.

What 08 says a client does, and the package's scripts do not do yet, is listed in
[08 §8](../docs/detailed-design/08-client.md#not-in-the-packages-scripts-yet): a cold resume's part in the app,
what follows `Kick(2)`, `Kick(4)` and `Kick(5)`, the lobby's endpoints tried in turn, `Retry-After`, and fetching
the queue and the party again after the lobby reconnects.

## Drill records

What the live drills showed, by date. Two of them found a defect on their first run (T-13 and T-16), which the
[defect register](../docs/defects.md#4-concurrency) records.

**The duel, 2026-09-27.** Two players in the lobby queued for a duel. `evt.match.found` matched them within a
second, and `GET /v1/queue` found the match as well. Both joined the room made for it, a millisecond apart. The
three minutes ended it in a draw: `Kick(6)` to both, and the result in MySQL with each placed first and a rated duel
counted. With only one of the two going, the join window ended it after 30 s as a walkover, recorded unrated and
unpaid. The drill prints the database's view of the last duels. Its first run found T-13: the room let the first
player in and refused the second ([defects](../docs/defects.md#4-concurrency)).

**Parties and team-vs-team, 2026-09-29.** Six players were in the lobby. One invited two, who accepted, and each of
the three was told about the party of three. A member's queue join was refused `in_party`, and the leader's join for
a duel `party_too_big`. The leader queued the party for team-vs-team, and both members were told. One member left the
queue, which took the party out and told the other two. The leader queued it again, and three more players queued
alone. All six were matched to one arena and joined its room within 3 ms: the party on team 1, the three alone on
team 2. Nobody fired. The five minutes ended it at 299 s, a draw, with `Kick(6)` to all six. Each was paid for taking
part and counted a rated team match. No rating moved, because a draw between equal teams moves none. The party
outlived the match. The drill prints the database's view of the last team match, by team.

**The confirm step, 2026-09-29.** Every scenario that queues is now asked `evt.match.ready` and accepts before its
match is made. The duel, the walkover and the six of the party all passed this way. In `decline`, one of two
players declined. The other was told it was queued again, and nobody was sent to a room. `GET /v1/queue` had said
`confirming` with the time left, and the player who declined was refused `queue_locked`. Its first run found T-16:
the six of the party accepted together and overtook one another, and the sixth was refused
([defects](../docs/defects.md#4-concurrency)).

**Ranked free-for-all, 2026-09-29.** Eight players queued alone, were asked about one match, accepted, and joined
its room. Each was on team 0, and the Welcome said mode 3. Nobody fired. The four minutes ended it at 239 s: all
eight had the same score, so all were placed first, with `Kick(6)` to each. Each was paid for taking part and counted
one rated free-for-all. No rating moved, because a draw among equals moves none. The drill prints the last
free-for-all's view.

**Co-op, 2026-09-29.** A party of two and one player alone queued for co-op. They were asked about one match as one
team, accepted, and joined its room, each on team 1, the Welcome saying mode 4. They drove toward where the waves
come, without firing. The first wave came into view, and its hunting tanks killed all three, 24 s after it was seen
and 33 s into the match. The wipe ended it. Each was placed first with one death and paid for taking part, and no
rating moved.

**Phrases, 2026-09-29** (docs 01 §9). In the public arena, the phrase list that platform served had the version
that the Welcome named: sixteen phrases. "Hello!", once said, came back to its speaker by their own handle and name.
A second phrase said at once was dropped without a kick, and one said two seconds later was heard. In `coop`, a call
for help from one of the three reached all three, by name, before the first wave came. Before the queue, the party
of two said "Good luck!" in the lobby. Both heard it, the third player did not, and a second phrase said at once was
refused `too_soon`.

**Accounts at the account service, 2026-11-26** (D-117, plan item 142 (e)). The drill started the account service
on 8097 beside the release, and every player the driver made was an account signed up there, its access token
exchanged for a session. All 46 scenarios passed, `account` among them: a refresh, the spent token taken again in its
overlap, a logout ending its family, and a password changed from a third device, which ended the two older sessions
and closed the lobby on one, `signed_out`, while a lobby opened on the session made after the change stayed open
(T-71). `erase` deleted an account at the account service, its event erasing the player and closing the other
device's lobby; `guest` linked a guest through `AccountKeeper`, and a second launch signed in by the refresh token as
the same player. Both simple clients' live checks and the Postman collection (145 requests, 159 checks) passed, and
again through nginx in both its ways (`FRONT=http`, `FRONT=plain`) with `play lobby duel account erase guest banned`.
Sixty bots signed up at the account service and played through the store's failover, each stay paid.
