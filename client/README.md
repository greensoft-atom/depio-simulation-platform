# client

The Unity app a player installs on iOS and Android. Designed in
[docs/detailed-design/08-client.md](../docs/detailed-design/08-client.md): an
engine-free core that speaks to the server, built and tested here against the
real server, and a thin Unity layer that draws it
([D-19](../docs/architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)).
The diagrams are in [docs/diagrams/06-client.md](../docs/diagrams/06-client.md).

## Projects

| Project | What it is | Target | Depends on | Its README |
|---|---|---|---|---|
| `Core/` | `Backend.Client.Core`: everything that speaks to the server, and what a frame draws, with no engine in it: the wire, the world, the match connection, the own tank's prediction, the API, the lobby, the scene and its clock, the sticks, the account kept, JSON | .NET Standard 2.1, C# 9 | nothing | [Core/README.md](Core/README.md) |
| `Core.Tests/` | NUnit tests of the core, which Unity's test runner also runs: the server's golden vectors, a scripted arena on a local socket, scripted HTTP answers | .NET 8, C# 9 | Core; NUnit 3.14.0, NUnit3TestAdapter 4.6.0, Microsoft.NET.Test.Sdk 17.11.1 | [Core.Tests/README.md](Core.Tests/README.md) |
| `Headless/` | The core driven without Unity against a running stack, a player's path scenario by scenario; what the live drills run | .NET 8, C# 9 | Core | [Headless/README.md](Headless/README.md) |
| `Unity/com.backend.client/` | The Unity layer: a UPM package of thin scripts over the core, and its iOS and Android plugins for the secure store | Unity 2022.3 LTS or 6 LTS | the core, as the library `unity-package.sh` copies in | [Unity/com.backend.client/README.md](Unity/com.backend.client/README.md) |
| `UnityCheck/` | Compiles the package's scripts against stubs of the Unity API, in each platform branch, and its Android plugin against stubs of Android's; nothing is run | .NET Standard 2.1, C# 9 | Core | [UnityCheck/README.md](UnityCheck/README.md) |

`Backend.Client.sln` holds Core, Core.Tests, Headless and UnityCheck. Every
project builds with warnings as errors and nullable reference types off.

```
client/
├── Core/                       Backend.Client.Core: no dependencies
│   ├── Wire.cs                 the protocol's numbers, mirroring com.backend.protocol
│   ├── WireReader.cs           fields and varints over one frame; ProtocolException
│   ├── Snapshot.cs             the snapshot decoder, streaming into a sink
│   ├── Messages.cs             Welcome, Pong, Kick; the client's own messages; stream framing
│   ├── ClientWorld.cs          what the client holds between snapshots
│   ├── Maze.cs                 a maze's walls from its seed, as the server makes them
│   ├── MatchConnection.cs      join, play, lose, resume, leave: 08 §3
│   ├── TankMotion.cs           the room's step of a driven tank, and a maze's walls by cell: 02 §9
│   ├── OwnTank.cs              the own tank, predicted: steps, replay, corrections (08 §4, D-62)
│   ├── ApiClient.cs            the platform API, its endpoints tried in turn, and what it answers
│   ├── LobbyClient.cs          the lobby WebSocket: auth, ping, requests, pushes
│   ├── PartyState.cs           the party as told, the newest kept (04 §4, D-74)
│   ├── Scene.cs                what a frame draws where, at a render tick, and the render clock: 08 §8
│   ├── TouchSticks.cs          two sticks, or keys and a mouse, to an input: 08 §8
│   ├── AccountKeeper.cs        the account kept across launches, in a secure store: 08 §8
│   └── Json.cs                 strict JSON for the API and the lobby, so the core needs no package
├── Core.Tests/                 NUnit, and the vectors the server's tests read, linked, never copied
├── Headless/                   the core driven without Unity against a running stack
├── Unity/com.backend.client/   the Unity layer: thin scripts over the core (08 §8)
├── UnityCheck/                 the scripts compiled against stubs: nothing run
├── unity-package.sh            builds the core into the Unity package
├── unity-check.sh              the check, in every platform branch
└── headless-drill.sh           starts the stack from a release and runs the headless client
```

## Building, testing, packaging, checking

The .NET 8 SDK is at `/opt/dotnet` on the development machine, off the PATH like
the JDK. The test project downloads NUnit from nuget.org the first time; the
core itself has no dependencies.

```bash
cd background/client
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
/opt/dotnet/dotnet build                                          # the four projects, Debug
/opt/dotnet/dotnet test --logger "console;verbosity=normal"       # the core's tests: read "Total tests:"
./unity-package.sh                                                # the core, Release, into the Unity package
./unity-check.sh                                                  # the package compiled against stubs
```

**Check the total, not only the verdict.** `dotnet test` says "Passed!" even when
its host process dies part-way, counting only the tests that ran; an unhandled
exception on any background thread does that. `--logger "console;verbosity=normal"`
prints `Total tests:`, which reads `Unknown` after such a crash.

`unity-package.sh` builds `Core` in Release and copies
`Backend.Client.Core.dll` into `Unity/com.backend.client/Runtime/Plugins/`,
which git ignores: run it before opening the package in Unity, and again
whenever the core changes. `unity-check.sh` compiles against the core's
project, not that copy, so it cannot tell a stale copy. Both take `DOTNET`, and
the check `JAVAC`, from the environment.

## Against the real server

```bash
/opt/dotnet/dotnet build
TMPDIR=<scratchpad> ./headless-drill.sh [scenario ...]           # plaintext
TLS=1 TMPDIR=<scratchpad> ./headless-drill.sh play untrusted      # the arena over TLS
```

It needs the backend's release (`backend/scripts/make-release.sh`), the j-redis
server and command-line jars, a JDK 21, python3 and the development MySQL; the
script says which, and where it looks. It runs the headless client from
`Headless/bin/Debug/net8.0`, built by `dotnet build`, with
`http://127.0.0.1:8093 --lobby ws://127.0.0.1:8094/lobby`, the operator's API
and a way to write the drill's fixtures (see [Headless/README.md](Headless/README.md)).
With no scenario named it runs `play resume badticket lifecycle`.

**The scenarios**, in the driver's order: `play prediction equip boost team
tournament teammatch teamcup rename apply roundrobin levels gems purchase pass
skin milestone season achievements goals social guest stats domination tag maze
sandbox resume coldresume badticket lifecycle lobby replaced badsession duel
walkover party decline rffa coop banned notice removed phrase`, and
`untrusted`, with `TLS=1`. What each checks, and what it needs, is in
[Headless/README.md](Headless/README.md#scenarios).

**The modes**, set in the environment:

| Variable | Default | What it does |
|---|---|---|
| `TLS=1` | 0 | The arena serves a test certificate naming 127.0.0.1, made for the run; the client trusts only it, and `untrusted` a different one. The lobby and the API stay plaintext |
| `DRAIN_AFTER=<s>` | unset | Stops the arena `s` seconds in (SIGTERM): it drains (01 §8.6) |
| `FAILOVER=1` or `demote` | 0 | The store with a replica (6392): the scenarios, then the primary killed and the replica promoted by `promote-store.sh` (1), or the old primary demoted and alive (demote); every process's subscription looked for on the new primary; the scenarios again (D-34, O-6). `demote` runs the scenarios only |
| `MYSQL_FAILOVER=1`, `demote` or `freeze` | 0 | Two MySQL servers of the drill's own (3307, 3308, GTIDs, the replica 30 s behind): the scenarios; the primary killed (1), stopped by SIGSTOP with platform's first answer timed (freeze, O-9), or handed over and fenced (demote); the replica promoted by `promote-mysql.sh`; the scenarios again; the old primary rebuilt by clone, handed back, and the scenarios a third time (D-35). `freeze` runs the scenarios only |
| `BOTS=<n>[:<s>]` | s = 120 | The load run (07 §4): `n` bots of `tools/BotClient` play `s` seconds in place of the scenarios; each process's cores, the arena's threads and numbers, NFR-2 per connection. `ARENA_OPTS` adds to the arena's JVM options |
| `BOTS` with `FAILOVER=1` or `MYSQL_FAILOVER=1`/`demote` | | The failover under load (architecture/02 §6): the primary killed or handed over halfway through, the replica promoted 20 s after the bots leave, every stay paid once, a probe's refusals timed |
| `BOTS` with `SOAK=1` | 0 | The soak (07 §4): stays of `STAYS` seconds (60:300) played again, the duel scenario every 30 s, every process sampled every `SAMPLE_EVERY` s (300), `tools/SoakJudge` judging from `JUDGE_FROM` s (half the run) |
| `BOTS` with `WORKERS=<k>` | unset | The workers' rate: `k` workers paused while the bots play, then draining their stays together, timed |
| `TAKEOVER=1` | 0 | The worker stopped with the results in flight, killed, and a second worker taking them over (05) |
| `RELEASE`, `JREDIS_JAR`, `JREDIS_CLI_JAR`, `JAVA`, `DOTNET`, `TOOLS_JAR` | the release under `backend/target/release`, j-redis 2.2.1's jars, `/opt/jdk21/bin/java`, `/opt/dotnet/dotnet`, the tools' jar | Where things are |

Ports: 6390 j-redis (6392 its replica), 8093 platform, 8094 gateway, 9011 the
arena, 9195 platform's metrics, 9196 platform's admin API, 9197 the arena's
metrics, 9198 the gateway's, 9199 the worker's; MySQL 3306, and 3307 and 3308
for `MYSQL_FAILOVER`. Logs go to a directory under `TMPDIR`, which the script
names first. After the run it prints the matcher's metrics, the gateway's
latency to platform, the arena's log, and the database's view of the last
match of each mode.

## Built so far

08 §7, steps 1 to 4, and §8: the wire, the world, the match connection, the
API and the lobby; the own tank's prediction, its movement rule held bit for
bit to a trajectory the server's own room wrote
(`backend/arena/src/test/resources/motion-2026.txt`); what the Unity layer
draws at a render tick, the sticks to an input and the account kept across
launches; and the Unity package's scripts, compiled against stubs and not yet
run in Unity. The decoder is checked against every decoded field of every
[golden vector](../protocol-spike/vectors/vectors.txt), the same file the
server's tests read; the world against the server's reference client, down to
its rounding (Java rounds half up, .NET to even, and a bullet at heading 400
seventeen ticks on is where the two differ). The connection is checked against
a scripted arena for every kick, a desync, resume backoff and its minute, input
cadence and pings, and against the real arena by the drill, plaintext and TLS.
The API and the lobby go the whole path in the drill: log in, the lobby's match
request, a match, and the worker's reward read back from the inventory.

What 08 says a client does and the package's scripts do not do yet (a cold
resume's app half, what follows `Kick(2)`, `Kick(4)` and `Kick(5)`, the lobby's
endpoints in turn, `Retry-After`, refetching after a lobby reconnect) is listed
in [08 §8](../docs/detailed-design/08-client.md#not-in-the-packages-scripts-yet).

## Drill records

**The duel, 2026-09-27**: two players in the lobby queued for a duel, were
matched by `evt.match.found` within a second, and found the match by
`GET /v1/queue` as well; both joined the room made for it a millisecond apart,
and the three minutes ended it in a draw, `Kick(6)` to both, the result in
MySQL with each placed first and a rated duel counted. With only one of the two
going, the join window ended it after 30 s as a walkover, recorded unrated and
unpaid. The drill prints the database's view of the last duels. Its first run
found T-13: the second player was refused as the room let the first in
([defects](../docs/defects.md#4-concurrency)).

**Parties and team-vs-team, 2026-09-29**: six players in the lobby. One invited
two, who accepted, and each of the three was told the party of three. A
member's queue join was refused `in_party`, and the leader's for a duel
`party_too_big`. The leader queued the party for team-vs-team, and both members
were told; one member left the queue, which took the party out and told the
other two; the leader queued it again, and three more queued alone. All six
were matched to one arena, joined its room within 3 ms, and found the party on
team 1 and the three alone on team 2. Nobody fired: the five minutes ended it at
299 s, a draw, `Kick(6)` to all six, each paid for taking part and counted a
rated team match with no rating moved, as a draw between equal teams moves
none; the party outlived the match. The drill prints the database's view of the
last team match, by team.

**The confirm step, 2026-09-29**: every scenario that queues is now asked
`evt.match.ready` and accepts before its match is made: the duel, the walkover
and the six of the party all passed so. In `decline`, one of two declined: the
other was told it was queued again, nobody was sent to a room, `GET /v1/queue`
had said `confirming` with the time left, and the one who declined was refused
`queue_locked`. Its first run found T-16: the six of the party accepting
together overtook one another, and the sixth was refused
([defects](../docs/defects.md#4-concurrency)).

**Ranked free-for-all, 2026-09-29**: eight players queued alone, were asked
about one match and accepted, and joined its room, each team 0, the Welcome
saying mode 3. Nobody fired: the four minutes ended it at 239 s, all eight on
the same score and so all placed first, `Kick(6)` to each; each was paid for
taking part and counted one rated free-for-all, no rating moved, as a draw
among equals moves none. The drill prints the last free-for-all's view.

**Co-op, 2026-09-29**: a party of two and one player alone queued for co-op, were
asked about one match as one team, accepted, and joined its room, each team 1,
the Welcome saying mode 4. They drove toward where the waves come, without
firing: the first wave came into view, and its hunting tanks killed all three,
24 s after it was seen, 33 s into the match. The wipe ended it; each was placed
first with one death, paid for taking part, and no rating moved.

**Phrases, 2026-09-29** (docs 01 §9): in the public arena, the phrase list
platform served had the version the Welcome named, sixteen phrases; "Hello!"
said came back to its speaker by their own handle and name, a second said at
once was dropped without a kick, and one two seconds on was heard. In `coop`,
a call for help from one of the three reached all three, by name, before the
first wave came. Before the queue, the party of two said "Good luck!" in the
lobby: both heard it, the third player did not, and a second at once was
refused `too_soon`.
