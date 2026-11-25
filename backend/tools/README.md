# tools

`tools` holds the programs that measure the backend and put load on it. Developers run them by hand, and the
drill runs some of them (`client/headless-drill.sh`). They are: the tick benchmark, the headless bots and their
lobby client, three database benchmarks, and the judge of a soak (a two-hour run with bots, see the
[glossary](../../docs/glossary.md#9-building-checking-and-running)). None of them runs in production, and none
listens on a port. This page says how to build them, how to run each one, what each prints, and what the
module's tests check.

## The jar

`mvn install` (or `mvn package`) builds `tools/target/tools-0.1.0-SNAPSHOT-all.jar`. It is a shaded jar: the
tools and everything they use, including the code of `arena`, `persistence`, `sim` and `handoff`. Its
`Main-Class` is `TickBenchmark`. You run every other tool by its class name, with `-cp`. The jar merges Flyway's
service files, so the database benchmarks can migrate a database. `dependency-reduced-pom.xml` is written by the
shade plugin; it is not edited by hand.

Run every command on this page from `backend/`:

```bash
. ../dev-env.sh            # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn install                # builds the jar, with every module's tests
mvn -pl tools -am package  # the jar alone, with the modules it needs
```

| Main class | Measures | Needs |
|---|---|---|
| `TickBenchmark` | One full room's tick, phase by phase, and its snapshots | nothing |
| `BotClient` | The whole stack, under headless players | the platform, the gateway, an arena and j-redis |
| `ApplyBenchmark` | Results applied per second, for each thread count | MySQL, a database whose name ends in `_test` |
| `PurgeBenchmark` | How fast retention deletes old matches | MySQL, a database whose name ends in `_test` |
| `RankBenchmark` | The cost of a player's place on a rating board, from the top to the bottom | MySQL, a database whose name ends in `_test` |
| `SoakJudge` | Whether a soak's samples grew where they should not | a file of samples from the drill's sampler |

`LobbyClient` has no `main`: it is the lobby half of `BotClient`.

## TickBenchmark

```bash
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar \
     [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed]     # defaults: 150 1500 20000 22000 1 0
```

| Argument | Default | Meaning |
|---|---|---|
| tanks | 150 | Tanks that nobody drives (bots). Each has an eight-byte name and a player tag, as a player's tank has |
| shapes | 1 500 | Shapes. New ones replace those that break |
| ticks | 20 000 | Measured ticks. A warm-up of min(5 000, ticks / 2) ticks runs first and is not measured |
| mapSize | 22 000 | The side of the square map. The public arena's is 5 700, where about 12 tanks share a view |
| startLevel | 1 | Each tank starts at this level, with its points spent and its classes chosen as a bot chooses them. So the room is a mature one from the first tick |
| mazeSeed | 0 | Builds a maze's walls from this seed; 0 means no walls |

**What it does.**
1. It builds one `World` of 16 384 entities, with a grid of 200-unit cells and the random number generator seeded
   with 42. Every tank is on team 0, as in the public arena.
2. Each tick, it steps the room and records the time of each phase (`tanks`, `bullets`, `shapes`, `hash`,
   `collide`, `sweep`) and of the whole step.
3. On 15 ticks of every 25, it encodes a snapshot for every tank with the arena's real `SnapshotEncoder`. Each tank
   has its own `ClientView` at the `mobile` budget of 30 entities. Its view is 1 600 units times its class's
   `fovMul`, as the arena sizes it. Each snapshot is acknowledged at once.
4. It replaces lost tanks and shapes outside the timed sections, so the room stays full.

**What it prints.**
- The JVM and the machine.
- The population at the end: the classes the bots chose, the traps, drones and minions, and the shapes of each
  kind.
- The simulation's p50, p99, p99.9 and maximum for each phase. Then the whole step's p99 against NFR-1a's budget
  of 2 ms, with `PASS` or `FAIL`.
- The encoding time for each client and for each round of all clients.
- The mean payload and the mean number of entities in a snapshot. The bandwidth of one player at 15 Hz, with and
  without 71 bytes of packet overhead.
- The mean tick, the share of one core that a room uses, and the number of rooms one core can run.
- The bytes allocated in the measured sections. The target is none.

**What it does not include.** It has no connections and no events. So it has no `Motion` event, which every real
frame of a living tank carries (about seven bytes, 02 §4), no kill feed, no phrases and no inputs. Use
`BotClient` to measure those.

Measure **before and after on the same machine**, and alternate the runs. The development machine is shared, and
its numbers move between runs. Results are recorded in `backend/README.md` and in the design documents.

## BotClient

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.BotClient \
     [bots] [seconds] [platformUrl] [lobbyUrl]
# defaults: 50 10 http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
```

The bots are headless players that take the same path as a real client. Sixteen bots are onboarded at a time.
The platform runs at most eight Argon2 hashes at once, so more at a time would only wait in its queue past the
client's timeout. Each bot:
1. Logs in as `bot<i>`. If that fails, it registers and then logs in. Its HTTP calls carry an address of its own
   from 198.18.0.0/15 in `X-Forwarded-For`, so a hundred bots look like a hundred clients to the login throttle.
   The platform believes that header only from loopback or a trusted proxy (`BACKEND_TRUSTED_PROXIES`, D-82). So
   the header counts only when the bots run on the platform's machine, or from an address it trusts.
2. Opens the lobby and sends `match.request`.
3. Dials the arena that the grant names, with TLS when the grant asks for it, and sends `Join` with the ticket.
   The bots dial the grant's host and port directly; they do not take the way through nginx's port 80 that a
   client without TLS takes on the site (`GET /match/<tlsName>`, D-115).
4. Sends ten `Input` packets a second, with auto-fire and a wandering aim. Each input acknowledges the tick the bot
   has reached. It also sends a `Ping` every ten seconds, and a `Respawn` every two seconds; the arena drops the
   `Respawn` while the tank is alive.
5. At the end, sends `Leave` and waits up to two seconds for the arena to close the connection first. So its
   result is published at once (02 §10).

**What it prints.** First, the time the onboarding took. Then, over the window that starts once every bot is in:
the welcomes, disconnects, kicks and the last kick's reason, the lobby failures, the snapshots and bytes received,
the mean frame, the snapshots and KB/s per bot, and the stays ended.

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_BOT_PROFILE` | unset: no profile byte is sent, so the arena serves `mobile` | `saver`, `mobile` or `high`, sent in `Join` after the ticket |
| `BACKEND_BOT_STAYS` | unset: one stay, the whole run | `<min>:<max>` seconds. Each bot leaves after a stay of that length and plays again. Meanwhile it pings its lobby every 30 s, and it closes and reopens its lobby every fourth stay (the soak) |
| `BACKEND_BOT_VIA` | unset | `host:port` to dial instead of the arena, for example a proxy that shapes the link. The certificate is still checked against the name the grant gives |
| `BACKEND_BOT_TRUSTSTORE` | unset: trust any certificate, and print a warning | A PKCS#12 trust store. With it, the bots check the certificate and the host name |
| `BACKEND_BOT_TRUSTSTORE_PASSWORD` | empty | The trust store's password |

**TLS.** The name that the bots send in the handshake, and check when a trust store is set, is the grant's
`tlsName` when the grant has one (an arena behind the front, D-82). Otherwise it is the grant's host.

The drill runs the bots: `BOTS=<n>[:<s>] TMPDIR=<scratchpad> client/headless-drill.sh` (from the repository's
root) is the load run, with `n` bots for `s` seconds (120 by default) once all are in. Add `SOAK=1` for the soak
(07 §4).

## LobbyClient

`LobbyClient` is the lobby flow for `BotClient`, written with the JDK's own HTTP and WebSocket clients:

| Method | Does |
|---|---|
| `registerIfNeeded(user, name, password)` | `POST /v1/accounts`. Both 201 (created) and 409 (the name is taken) count as success |
| `login(user, password)` | `POST /v1/sessions`. Keeps the session token and the player id |
| `openLobby()` | Opens the WebSocket and sends `auth` with the token. Expects `auth.ok` |
| `requestMatch()` | Sends `match.request`. Returns a `Grant`: the arena's host and port, the ticket id, `tls`, and `tlsName` (null when the grant has none) |
| `ping()` | Sends `ping`. A lobby connection must send one at least every 30 s |

Each request carries an `id`, and the client matches each reply by that id. So a push that arrives between a
request and its reply is skipped. Each HTTP request and each wait for a lobby reply is limited to 10 s; opening the
WebSocket waits up to 15 s. `forwardedFor(address)` sets `X-Forwarded-For` on the HTTP calls (not on the
WebSocket). A phone runs the C# client's `client/Core/LobbyClient.cs`, not this class.

## ApplyBenchmark

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     "jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true" \
     backend backend-dev-password [results=400] [threads=1,2,4,8] [mysqld pid]
```

`ApplyBenchmark` measures how fast the worker's own transaction applies results: one player per result, for each
thread count in the list, each thread with its own connection (05 §1, plan item 26). It:
1. drops and migrates the database;
2. registers enough accounts;
3. warms every connection with a first round at the largest thread count;
4. prints, for each thread count, the results per second and the CPU time per result in this process. It also
   prints the CPU time in `mysqld` when you give its pid (MySQL on this machine).

The rate rises with threads because commits made at the same moment share their synchronous disk writes.

## PurgeBenchmark

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.PurgeBenchmark \
     "jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true" \
     backend backend-dev-password [matches=200000] [players a match=4]
```

`PurgeBenchmark` measures how fast retention deletes match history (06 §9, plan item 76 (b)). It:
1. drops and migrates the database;
2. fills `matches` and `match_player` with matches that ended 100 days ago, past the 90 days that are kept;
3. runs the worker's own purge, `MatchResultRepository.purgeMatchesEndedBefore`, in batches of 1 000;
4. prints the matches and the players' rows deleted per second.

The repository's CLAUDE.md runs it with one player a match.

## RankBenchmark

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     "jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true" \
     backend backend-dev-password [accounts=200000] [listed=5000] [reads=21]
```

`RankBenchmark` measures what a player's own place on the duel board costs (defect D-35, plan item 58). It:
1. drops and migrates the database;
2. fills `accounts` accounts, each at the starting rating of 1 200;
3. gives `listed` of them a duel rating and enough rated duels to be listed on the board. The ratings follow a
   normal spread around 1 200 (standard deviation 200, kept between 100 and 3 000), from the seed 58;
4. times `RatingBoards.place` for the top, middle and bottom listed players, and `RatingBoards.top(100)`.

For each, it prints the median and the slowest of `reads` reads, and the index rows MySQL read, taken from the
session's handler counters.

## SoakJudge

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.SoakJudge <samples> <fromSecond>
```

`SoakJudge` judges a soak (07 §4, plan item 47): did anything grow that should not? The samples are lines of
`second<TAB>series<TAB>value`, written by the drill's sampler (`soak.tsv`). It judges only the samples from
`fromSecond` on. The earlier part is an allowance for the JIT compiler, the pools and the caches to fill. It
prints `PASS` or `FAIL` for each rule, and exits 1 if any rule failed.

| Rule | Passes when |
|---|---|
| `heap.<process>` (arena, platform, gateway, worker) | the live heap's slope, continued for a day, would grow it by less than a quarter of its mean |
| `heap.store` | the store's heap grows by no more than twice what the stream's new entries take (530 bytes each), plus 1 MB |
| `threads.<process>`, `fds.<process>` (the four and the store) | the median of the last three samples is at most two above the median of the first three |
| `keys.<family>` | as for threads; `sess` keys may also grow by 4 for each duel run started in the window |
| `keys.rl` | at every sample, there are no more login throttles than two for each duel run in the 15 minutes before, plus two |
| `stream` | the stream gained no more entries than results were published, plus two |
| `mysql.connections` | as for threads |

A rule fails when it has fewer than six samples, or when the sampler could not read a sample. Slopes are
Theil–Sen slopes: the median of the slopes between every pair of samples. So a room made and freed between two
samples does not look like growth.

## Tests

| Test class | Tests | What it covers |
|---|---|---|
| `ApplyBenchmarkTest` | 1 | The benchmarks run only on a database whose name ends in `_test`, and never on a URL that names a primary and its replica |
| `ReadmeTestListsTest` | 1 | Every module's README names each of its test classes, with the number of tests where its row gives one. A test added without its row fails the build (DOC-29) |
| `DocsLinksTest` | 1 | Every relative link in `docs/` (except the research and archive folders) names a file that exists and, with an anchor, a heading in that file, by GitHub's rule for anchors. A link that does not resolve fails the build (DOC-33) |
| `NoLinksTest` | 1 | Nothing in the checkout is a symbolic link (except under `work/` and the projects' build output). A copy that does not keep links leaves text in their place, and mysqld then stops with "file too short" (O-98, D-106) |
| `SoakJudgeTest` | 13 | Each rule passing and failing at its limit; only the judged window counting; unreadable samples, failed scans and too few samples failing; the sampler's line format |

```bash
mvn -pl tools -am test
```

## Design documents

- [07 §4 Tick budget and what to measure](../../docs/detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure):
  the load run and the soak.
- [02 §4 Sizes](../../docs/detailed-design/02-networking.md#sizes) and
  [§8 Traffic profiles](../../docs/detailed-design/02-networking.md#8-traffic-profiles):
  what the bots' bytes are judged against.
- [operations/01 §8](../../docs/operations/01-deploy.md#8-tls-for-match-traffic):
  the bots and TLS.
