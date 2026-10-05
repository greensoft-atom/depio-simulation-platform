# tools

What the backend is measured and loaded with: the tick benchmark, the headless
bots and their lobby client, three database benchmarks and the soak's judge. None
of them runs in production, and none listens on a port.

## The jar

`mvn install` (or `package`) builds `tools/target/tools-0.1.0-SNAPSHOT-all.jar`,
a shaded jar of the tools and everything they use, the arena's and the
persistence's code included. Its `Main-Class` is `TickBenchmark`; every other
tool is run by class name with `-cp`. Flyway's service files are merged, so the
database benchmarks can migrate. `dependency-reduced-pom.xml` is the shade
plugin's own output.

Run everything below from `backend/`:

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o install                    # builds the jar, with every module's tests
/opt/maven/bin/mvn -o -pl tools -am package      # the jar alone, with the modules it needs
```

| Main class | Measures | Needs |
|---|---|---|
| `TickBenchmark` | a full room's tick, phase by phase, and its snapshots | nothing |
| `BotClient` | the whole stack under headless players | platform, gateway, an arena, j-redis |
| `ApplyBenchmark` | results applied a second, by thread count | MySQL, a `*_test` database |
| `PurgeBenchmark` | retention's purge rate | MySQL, a `*_test` database |
| `RankBenchmark` | a rating board's place, top to bottom | MySQL, a `*_test` database |
| `SoakJudge` | whether a soak's samples grew where they should not | a sampler file |

`LobbyClient` has no main: it is the lobby half of `BotClient`.

## TickBenchmark

```bash
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar \
     [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed]     # defaults: 150 1500 20000 22000 1 0
```

| Argument | Default | Meaning |
|---|---|---|
| tanks | 150 | tanks nobody drives (bots), each named in eight bytes and given a player tag |
| shapes | 1 500 | shapes, topped up as they break |
| ticks | 20 000 | measured ticks, after a warm-up of min(5 000, ticks / 2) |
| mapSize | 22 000 | the square's side; the public arena's is 5 700, where about 12 tanks share a view |
| startLevel | 1 | each tank grown to this level at birth, its points spent and its classes taken as a bot's are: a mature room at once |
| mazeSeed | 0 | a maze's walls from this seed, 0 for none |

**What it does.** One `World` of 16 384 entities, a 200-unit grid and the RNG
seeded 42; every tank on team 0, as the public arena's are. Each tick it steps
the room and records each phase (`tanks`, `bullets`, `shapes`, `hash`,
`collide`, `sweep`) and the total; 15 ticks in 25 it encodes a snapshot for every
tank through the arena's real `SnapshotEncoder`, with a `ClientView` per tank at
the `mobile` budget of 30 and a view of 1 600 units times its class's `fovMul`,
as the arena sizes it, and acknowledges each at once. Tanks and shapes are
topped up outside the timed sections.

**What it prints.** The JVM and machine; the population at the end, the classes
the bots took, traps, drones and minions, shapes by kind; the simulation's p50,
p99, p99.9 and max per phase, and the whole step's p99 against NFR-1a's 2 ms
with `PASS` or `FAIL`; encoding per client and per round;
the mean payload and entities a snapshot, the bandwidth a player at 15 Hz with
and without 71 bytes of packet overhead; the mean tick, a core's share and rooms
a core; and the bytes allocated in the measured sections (the target is none).

**What it does not include.** Connections and events: no `Motion` event, which
every real frame of a living tank carries (about seven bytes, 02 §4), no kill
feed or phrases, no inputs. Use `BotClient` for those.

Measure **before and after on the same machine**, alternating runs: the
development machine is shared and its numbers move between runs. Results are
recorded in `backend/README.md` and the design documents.

## BotClient

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.BotClient \
     [bots] [seconds] [platformUrl] [lobbyUrl]
# defaults: 50 10 http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
```

Headless players that take the path a client takes. For each bot, sixteen at a
time (Argon2 admits eight): log in as `bot<i>`, or register and then log in
when that fails, from its own address in 198.18.0.0/15 (`X-Forwarded-For`);
open the lobby;
`match.request`; then dial the arena the grant names, with TLS when the grant
says so, and `Join`. Every bot then sends ten `Input` packets a second, with
auto-fire and a wandering aim, acknowledging the tick it has reached, a `Ping`
every ten seconds, and a `Respawn` every two seconds, which the arena drops
while the tank lives. At the end each sends `Leave` and waits for the arena to
close first, so its result is published at once (02 §10).

It prints the onboarding time, then, over the window once every bot is in:
welcomes, disconnects, kicks and the last reason, lobby failures, snapshots and
bytes, the mean frame, snapshots and KB/s a bot, and the stays ended.

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_BOT_PROFILE` | unset: no profile byte, so mobile | `saver`, `mobile` or `high`, sent after the ticket |
| `BACKEND_BOT_STAYS` | unset: one stay, the run | `<min>:<max>` seconds: each bot leaves and plays again, its lobby pinged every 30 s and reopened every fourth stay (the soak) |
| `BACKEND_BOT_VIA` | unset | `host:port` to dial instead of the arena, such as a proxy that shapes the link; the certificate is still checked against the granted host |
| `BACKEND_BOT_TRUSTSTORE` | unset: trust any certificate, with a warning | a PKCS#12 trust store; with it the bots check the certificate and the host name |
| `BACKEND_BOT_TRUSTSTORE_PASSWORD` | empty | its password |

The drill runs it: `BOTS=<n>[:<s>] TMPDIR=<scratchpad> client/headless-drill.sh`
for the load run, with `SOAK=1` for the soak (07 §4).

## LobbyClient

The lobby flow in plain JDK HTTP and WebSocket, for `BotClient`:

| Method | Does |
|---|---|
| `registerIfNeeded(user, name, password)` | `POST /v1/accounts`; 201 or 409 (taken) both pass |
| `login(user, password)` | `POST /v1/sessions`; keeps the token and player id |
| `openLobby()` | opens the WebSocket and sends `auth` with the token; expects `auth.ok` |
| `requestMatch()` | `match.request`; returns a `Grant`: arena host and port, ticket id, `tls` |
| `ping()` | `ping`, which a lobby connection needs at least every 30 s |

Requests carry an `id` and replies are matched by it, so a push arriving between
a request and its reply is passed over. Every wait is bounded at 10 s.
`forwardedFor(address)` sets `X-Forwarded-For`. The C# client's
`client/Core/LobbyClient.cs` is the one a device runs.

## ApplyBenchmark

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" \
     backend backend-dev-password [results=400] [threads=1,2,4,8] [mysqld pid]
```

How fast the worker's own transaction applies results, one player each, from
each thread count in the list, every thread on its own connection (05 §1, plan
item 26). It drops and migrates the database, registers enough accounts, warms
every connection with a first round at the largest count, then prints for each
count the results a second and the CPU a result in this process, and in
`mysqld` when its pid is given. Commits made at once share their synchronous
writes, which is why the rate rises with threads.

## PurgeBenchmark

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.PurgeBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" \
     backend backend-dev-password [matches=200000] [players a match=4]
```

How fast retention deletes match history (06 §9, plan item 76 (b)). It drops and
migrates the database, fills `matches` and `match_player` with matches that
ended 100 days ago, past the 90 days kept, then runs the worker's own purge,
`MatchResultRepository.purgeMatchesEndedBefore` in batches of 1 000, and prints
matches and players' rows deleted a second. The repository's CLAUDE.md runs it with
one player a match.

## RankBenchmark

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" \
     backend backend-dev-password [accounts=200000] [listed=5000] [reads=21]
```

What a player's own place on the duel board costs (defect D-35, plan item 58).
It drops and migrates the database, fills `accounts` accounts at the starting
1 200, gives `listed` of them a duel rating (a normal spread, seed 58) and enough
rated duels to be listed, then times `RatingBoards.place` for the top, middle
and bottom listed players and `RatingBoards.top(100)`: the median and slowest of
`reads`, and the index rows MySQL read, from the session's handler counters.

## SoakJudge

```bash
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.SoakJudge <samples> <fromSecond>
```

Judges a soak (07 §4, plan item 47): does anything grow that should not. The
samples are lines of `second<TAB>series<TAB>value` written by the drill's
sampler (`soak.tsv`); only those from `fromSecond` on are judged, the first part
being the JIT's, the pools' and the caches' allowance. It prints `PASS` or `FAIL`
a rule and exits 1 if any failed.

| Rule | Passes when |
|---|---|
| `heap.<process>` (arena, platform, gateway, worker) | the live heap's slope, run on for a day, would grow it by less than a quarter of its mean |
| `heap.store` | the store's heap grows by no more than twice what the stream's new entries take (530 bytes each), plus 1 MB |
| `threads.<process>`, `fds.<process>` (the four and the store) | the median of the last three samples is at most two above the first three's |
| `keys.<family>` | the same, and `sess` keys may also grow by 4 for each duel run the window started |
| `keys.rl` | at every sample, no more login throttles than two for each duel run in the 15 minutes before, plus two |
| `stream` | the stream gained no more entries than results were published, plus two |
| `mysql.connections` | as for threads |

A rule with fewer than six samples, or any sample the sampler could not read,
fails. Slopes are Theil–Sen (the median of every pair's slope), so a room made
and freed between two rounds does not read as growth.

## Tests

| Test class | Tests | What it covers |
|---|---|---|
| `ApplyBenchmarkTest` | 1 | The benchmarks run only on a database named `*_test`, and never on a primary-and-replica URL |
| `SoakJudgeTest` | 13 | Each rule passing and failing at its edge; only the judged window counting; unreadable samples, failed scans and too few samples failing; the sampler's line format |

```bash
/opt/maven/bin/mvn -o -pl tools -am test
```

## Design documents

- [07 §4 Tick budget and what to measure](../../docs/detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure):
  the load run and the soak.
- [02 §4 Sizes](../../docs/detailed-design/02-networking.md#sizes) and
  [§8 Traffic profiles](../../docs/detailed-design/02-networking.md#8-traffic-profiles):
  what the bots' bytes are judged against.
- [operations/01 §8](../../docs/operations/01-deploy.md#8-tls-for-match-traffic):
  the bots and TLS.
