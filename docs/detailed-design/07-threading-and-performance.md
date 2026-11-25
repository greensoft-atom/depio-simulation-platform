# 07 — Threading model and performance engineering

This page says which threads an arena runs and what each may touch, how the tick loop avoids garbage, which data
structures it uses, what the tick may cost and how that is measured, and how the JVM, Linux and the stability
safeguards are set up. It is for engineers who change the arena, the simulation or the units, or who read a load
run. Where the code differs from the design, each section says **as built**. The arena is the process that runs
the simulation; a *room* is one simulated world inside it, run by one thread; a *tick* is one step of that world,
every 40 ms. These and other terms are in the [glossary](../glossary.md#1-processes-and-places).

The backend runs on Java 21 with generational ZGC
([D-2](../architecture/03-decision-log.md#d-2--java-21-with-generational-zgc)).

## 1. Thread inventory (arena process)

| Thread | Count | Role | Touches room state? |
|---|---|---|---|
| Netty boss | 1 | accepts connections | no |
| Netty workers | 2 (`ArenaMain` sets it; the design pins them to 2 cores) | TLS, frame decoding, handing input to the room, socket writes | no |
| Room threads | one a room: `MAX_ROOMS`, 4 by default, 12 on the site; made matches' rooms included | simulation and snapshot encoding | **only their own room** |
| j-redis client IO | 1 per store client (2 with `BACKEND_EVENTS_STORE`) | asynchronous commands; callbacks are handed back to the room's queue | no |
| `arena-announce` | 1 | the arena's directory entry every 3 s, and operators' commands | reads the registry |
| `match-result-publisher` | 1 | results to the stream, the spool, and the retry | through its queue |
| `metrics` | 1, when metrics are configured | answers the scrape | reads volatile fields |
| Logback async appender | 1 | writes standard output to the journal; drops lines when its queue is full (§8) | no |
| `room-watchdog` | 1 per registry | detects stalls; abandons a room stuck for 10 s (§8) | reads volatile fields; kicks players through concurrent sets |
| GC threads | the JVM's | | |

Three rules hold for every thread:

1. **Room state is single-writer.** A room's entities have no `synchronized`, no `volatile` fields and no
   `ConcurrentHashMap`. Other threads reach a room through exactly two doors: `Room.inputQueue` (in) and
   `Channel.write` (out).
2. **Callbacks never run simulation logic.** A j-redis reply arrives on the client's IO thread. The callback wraps
   it in a message and offers it to the room's queue. Timers do the same.
3. **Rooms never share objects.** Configuration tables are immutable after load and may be shared. Everything else
   (pools, scratch buffers, the random number generator) belongs to one room.

**The design's input queue.** `InputQueue` is a bounded MPSC (multi-producer, single-consumer) queue:
`org.jctools:jctools-core`'s `MpscArrayQueue`, capacity 4 096. When it is full, the input is dropped, because the
next input replaces it anyway.

**As built there is no input queue.** Each connection holds its latest input in one `AtomicLong`. The room reads it
once a tick, so a newer input simply replaces an older one. A fire press is latched, so a tap between two ticks is
not lost. Joins, resumes, leaves and removals go through `MpscArrayQueue`s, and the allocator reserves places
through two atomic counters.

Three concurrent collections are deliberate exceptions to rule 1 and rule 3:

- the watchdog finds a stuck room's players, queued joins and queued resumes in concurrent sets (§8);
- the registry's `stays` map (resume secret to room,
  [02 §10](02-networking.md#10-session-reconnect-and-app-lifecycle)) is shared by every room, against rule 3. Room
  threads write it and event loops read it.

**Where an arena's cores go** (the load run, 2026-09-30, plan item 14). Its two Netty workers used more CPU than
all its room threads together: 1.15 cores against 0.87, for 1 200 clients, about 27 µs of CPU a message.

**Tried and measured: fewer wakeups gave no gain.** Each snapshot is a `writeAndFlush` from the room thread, which
hands a task to the event loop. The idea was that these wakeups cost the most. So the room wrote each snapshot
without a flush, which Netty queues without waking the loop, and flushed once per loop per round. Three runs each
way, alternated, at 1 200 bots, gave the workers 1.11 cores without the change and 1.12 with it: at that rate the
loops are awake anyway. The change was taken out. It saved nothing, and it made every snapshot wait for its room's
round to end.

**Room threads are platform threads, not virtual threads.** A room thread is CPU-bound and has a deadline. Virtual
threads exist to make blocking cheap, and a room thread must never block. Virtual threads belong where requests
wait on MySQL. **As built**, the platform's API and admin servers run each request on a virtual thread. The worker
does not use them: its result loop is one thread.

## 2. Allocation discipline in the tick loop

**The target is zero garbage per tick in steady state.** Two checks show it:

- `-Xlog:gc`: a room at steady state should see collections minutes apart, not seconds apart;
- the tick benchmark (`tools/TickBenchmark`), which counts the ticks that allocated.

**As measured** (2026-09-26): the simulation and the encoder allocate nothing. The latency histograms do allocate,
a handful of times in a room's life: when a phase takes longer than it ever has, HdrHistogram resizes itself.

ZGC makes a missed allocation cheaper than G1 did, since its pauses do not grow with the heap's size. But it does
not make allocation free: every byte still costs barrier work, and in the end a collection cycle. So the rules
below still apply.

Do:

- Pool `Entity`, `ClientMessage` and `StatModifier`; pool `IntList` scratch buffers per client.
- Loop with `for (int i = 0; i < list.size; i++)` over `IntList.items[i]`. Never use an enhanced `for` on a
  collection: it allocates an `Iterator`.
- Keep per-entity data in primitive fields, and per-kind lists as `int[]`.
- Reuse one `ByteBuf` per client per tick from `PooledByteBufAllocator`. Netty returns it to the pool after the
  write.
- Compute per-class constants (reload ticks, radius, view size) when the class changes, not every tick.
- Use `float` math and `(float) Math.sqrt`. Avoid `Math.hypot`, which is slow. Avoid `Math.atan2` in loops unless
  it is needed: the aim comes from the client.

Don't:

- Box values (`Integer` in maps, `Float` in lists). No `HashMap<Integer, Entity>`: use `Entity[]` indexed by id,
  or `Int2ObjectOpenHashMap`.
- Create lambdas that capture local variables inside `tick()`: each capture allocates. Method references to static
  methods are fine.
- Use `String.format`, string concatenation, or logging with `+` in hot paths. Use `log.debug("… {}", id)` behind
  an `isDebugEnabled()` guard.
- Throw exceptions for flow control. Validation returns booleans.
- Call `System.currentTimeMillis()` per entity. Read the clock once per tick.

Java 21 has `record` and `var`, but **still no value types**. A record is a heap object like any other, so it is
not a way to avoid allocation in the tick loop. Records are right for immutable configuration and message
payloads, and wrong for per-entity state.

If `Entity` objects ever show up in profiles, the way out is struct-of-arrays: keep `float[] x, y, vx, vy, radius,
hp…` in `World` and pass `int id`. Start with objects, which are readable, and switch only if a measurement says
so.

## 3. Data structures

The design's choices, and what the code has today where it was checked:

| Need | Use | As built |
|---|---|---|
| id to entity | `Entity[]` | `World.entities`, a fixed pool |
| dynamic int list | own `IntList { int[] items; int size; }` with `add/removeAtSwap/clear` | `common/IntList` |
| int to int, int to object | fastutil `Int2IntOpenHashMap` / `Int2ObjectOpenHashMap`, or an own open-addressing map (~80 lines) | fastutil is not used |
| spatial index | an intrusive linked grid (`int[] head, next`), rebuilt each tick | `sim/SpatialHash` |
| free slots | an `int[]` stack | `World`'s free-slot stack, low ids handed out first |
| per-client known entities | own `IntIntMap` (id to generation), sized to the view's capacity (~512) | `ClientView`: a `short[]` from entity id to handle, sized to the world (about 32 KB a client), and an `int[]` back |
| random numbers | xorshift128+ in a final class, one per room | `common/Xorshift`, one per world |
| modifiers | arrays bucketed by stat: `StatModifier[8][]` and counts | not built: `sim/StatTable` has two modes of growth and one formula a stat |

**Sorting a scoreboard** (not built: no scoreboard exists in the arena or the simulation): keep tanks in an
`int[]`, and do an insertion sort by score every 25 ticks. With at most 200 tanks this is trivial.

## 4. Tick budget and what to measure

**The design:** per room and per tick, record the nanoseconds of each phase into a ring of 1 024 samples, and
export p50, p99 and the maximum every 10 s.

**As built:**

- The per-phase numbers are cumulative histograms, printed when the arena stops.
- While the arena runs, it exports the whole tick's p99 over the last 10 s, per room:
  `backend_arena_tick_p99_seconds`.
- In the report at the stop, the simulation's total is `room.step` alone, as in the benchmark. It is judged
  against NFR-1a's 2 ms. The whole tick, with snapshots and everything else in it, has a line of its own, judged
  against NFR-1b's 15 ms.
- Until 2026-09-30 the report printed the whole tick as the simulation's total, and so it failed against the
  simulation's budget (O-3).

The phases of the design:

```
drainInputs | tanks | bullets | shapes | grid | collide | deaths | encode | write | total
```

As built: `tanks | bullets | shapes | hash | collide | sweep` (together, the step), then `encode+write`, and the
whole tick.

Targets on a 3 GHz core, for 150 tanks, 1 500 bullets and 1 500 shapes:

| Phase | Target |
|---|---|
| updates + grid | < 0.5 ms |
| collide | < 1.0 ms |
| encode all clients (150 × ~120 entities) | < 2.0 ms |
| total (simulation only) | < 2 ms p99 — **measured 0.55 ms**; a room grown from level 1 with the second class tier, 1.22 ms; **a room of level-45 tanks of the second tier, 2 250 bullets in flight, 2.9 ms: over** ([backend README](../../backend/README.md#what-the-second-tier-costs-2026-09-27), Q-3); the third tier costs the same, held there by D-22 ([backend README](../../backend/README.md#what-the-third-tier-costs-2026-09-28)); the whole tree 13 % more at p50, from the Battleship's drones, which D-22 does not count ([backend README](../../backend/README.md#what-the-rest-of-the-tree-costs-2026-09-28)) |
| snapshot tick (simulation + encode all clients) | < 15 ms p99 — **measured 6.2 ms**, against a 40 ms deadline |

If encoding dominates, there are two ways out:

1. Send far entities less often: bullets every tick, shapes every second tick.
2. Move encoding to a second thread per room, which reads a double-buffered, read-only copy of the positions
   (`float[]` snapshot arrays). That would be the only sanctioned place where another thread reads a room's data,
   and only immutable copies.

**Micro-benchmarks:** the design uses JMH 1.37 (it is in the [java21-offline](../../java21-offline/README.md)
bundle), to bench the collision pass and the snapshot encoder in isolation, with synthetic worlds, before
optimising. **As built** no JMH benchmark is written: `tools/TickBenchmark`, a plain program, measures each phase
of a full room.

**Load test:** a headless bot client (`tools/BotClient`) opens N TCP connections. Each one sends ten input packets
a second and a ping every ten seconds, as a client does, decodes the snapshots, and reports the receive rate and
the tick echo latency. Run 200 bots against one room, and watch the tick histogram and the skipped snapshots.

### The load run on the development machine

**What it is** (designed 2026-09-30, plan item 14). `tools/BotClient` takes the path a client takes: register, log
in, the lobby, a ticket, the arena. It then plays as a client does: ten input packets a second and a ping every ten
seconds ([02 §9](02-networking.md#9-input-prediction-and-reconciliation)), and it reads and counts the snapshots.
Until 2026-10-04 it sent twenty input packets a second and no ping. So the runs recorded below carried twice a
client's upstream packets.

It runs against the release's processes, as the drill starts them (`BOTS=<n> client/headless-drill.sh`), with one
arena of enough rooms of 150. The run goes in steps of rising size. Each step plays for two minutes once every bot
is in. The run stops when a step misses a target or reaches the cap. The cap: this machine is shared, so the run
uses no more than about half its twelve cores. Each step records:

| What | Target | From |
|---|---|---|
| Onboarding: bots a second through register, login and ticket | recorded; the limit is the platform's Argon2, eight at a time | BotClient |
| Each room's tick p99 | NFR-1b, 15 ms | `backend_arena_tick_p99_seconds`, and overruns (`backend_arena_tick_overruns_total`) |
| Snapshots a bot a second | 15 at the mobile profile, none held or skipped | BotClient, `backend_arena_snapshots_skipped_total`, `backend_arena_snapshots_held_total` |
| Bytes a bot a second | NFR-2: 15 MB an hour is 4.2 KB/s | BotClient |
| Bots dropped or kicked | none | BotClient |
| Each process's CPU and memory | recorded, against the cap | `ps` |

What it cannot show: wall-clock latency on a shared machine (Q-3), and what the network between two machines adds.
It finds where one machine's processes give out first, and whether that is the backend's to fix.

**Run on 2026-09-30**, from the release. Every process ran on ZGC, as its unit has it. Each room had 150 players
and 1 500 shapes on a 5 700 map, as `arena.env.example` has them. Each step ran two minutes once every bot was in:

| Bots | Full rooms | In the lobby | Per bot | Arena cores: I/O threads, rooms | All, bots included |
|---|---|---|---|---|---|
| 150 | 1 | 6.6 s | 14.98 snapshots/s, 1.15 KB/s | 0.44: 0.21, 0.13 | 0.9 |
| 600 | 4 | 11.9 s | 14.90, 1.11 KB/s | 1.23: 0.70, 0.45 | 2.2 |
| 1 200 | 8 | 21.0 s | 14.90, 1.11 KB/s | 2.10: 1.15, 0.87 | 3.5 |

At every step, no bot was dropped or kicked, no round was skipped, and no tick overran. A full room's whole tick was
3.0 ms at p50: the simulation 0.5 ms, encoding and writing 2.2 ms. At p99 it was 16 to 25 ms. The p99 moved with the
machine's load between runs, not with the number of bots; the idle room beside them measured 4 to 9 ms. Some runs
held back rounds (up to 0.6 % of them). This came and went with the machine's load, in either build. Each bot
received 1.1 KB/s, a quarter of NFR-2's ceiling, at the density bots make.

**What it found.**

- **The arena's network threads cost more than its rooms**, and three quarters of that is the kernel's. At 600 bots
  its two Netty workers used 0.19 cores of user time and 0.59 of kernel time: sending, receiving and waiting in
  `epoll`. The rooms used 0.53 user and 0.05 kernel. That is about 9 µs of Java and 28 µs of kernel a message.
  Over loopback, the kernel does the receiving side's work in the sender's time too, so this overstates it. The
  figure that counts needs the bots on another machine (Q-3). Fewer wakeups of the loops were tried and gained
  nothing (§1).
- **Two network threads a process fill up first.** At these costs, an arena's two Netty workers would be full near
  2 100 players. That is above the 1 800 an arena is set for (12 rooms), and below the ~7 000 that each process
  would need if seven arenas carried 50 000 players (Q-3,
  [architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model)).
- **The report at shutdown misnamed the whole tick** as the simulation's total (O-3). Fixed.
- **The bot generator costs about a core a thousand bots**, and onboarding is limited by the platform's Argon2,
  eight at a time: 57 bots a second.

The run stopped at 1 200 bots, which used 3.5 of the machine's twelve shared cores. 1 800 bots would reach the cap
of about half of them. Ten thousand bots need Q-3's machines.

### The soak on the development machine

**What it is** (designed 2026-10-01, plan item 47,
[Q-41](../requirements/01-scope-and-nfrs.md#7-open-questions)). Phase 6's soak, 24 hours at the target load, waits
for Q-3's machines. This one asks a question that needs none of them: **does anything grow that should not**,
while the paths players use run for hours? A leak is a slope, and a slope shows at a fraction of the load. For
example, a map entry of 200 bytes left behind per stay is a megabyte an hour here.

- **Who plays.** `BotClient` with stays that end (`BACKEND_BOT_STAYS=<min>:<max>`, in seconds). Each bot logs in
  once, as a client keeps its session a day. Then it asks its lobby for a match, plays a stay of a random length in
  the range, pings its lobby every 30 s as a client does (03 §3), leaves as a player does, and asks again. Every
  fourth stay it also closes its lobby connection and opens another, as an app sent to the background does. With
  300 bots and stays of one to five minutes, about 1.7 stays end a second: some twelve thousand results in two
  hours, each applied, pushed and ranked.
- **Made matches throughout.** The headless client's `duel` scenario runs again every 30 seconds: two new players
  matched and confirming, a rated result, what it paid pushed, and the rating board read.
- **Two hours.** The first hour is an allowance, while the JIT, pools and caches fill. The second hour is judged.
  Every five minutes the run samples:
  - each process's live heap: its live objects' bytes, counted by `jcmd GC.class_histogram` after a collection.
    The heap's "used" figure after a collection moved by ±6 MB between rounds, because ZGC counts whole pages;
  - each process's threads and open files;
  - the store's keys by family (`SCAN`), and the stream's length;
  - MySQL's connections.

**The run passes** when, over the second hour:

| What | Rule |
|---|---|
| Live heap: arena, platform, gateway, worker | The slope, run on for 24 hours, is under a quarter of the mean. The slope is the median of the slopes of every pair of samples (Theil–Sen), because a round taken while a match's room lived swung a least-squares line. |
| Threads and open files, each process | No more at the end than at the start, give or take two |
| The store's key families | Flat, except those that grow by design, each by its own rule: the result stream an entry a result (trimmed at 24 hours, defect D-26); sessions four a duel run (its two new players' sessions and their indexes, a day each); login throttles, at every sample, no more than two for each duel run started in the 15 minutes before it, the window they live |
| The store's heap | Its growth no more than twice what the stream's entries account for, at ~530 bytes each (defect D-26) |
| MySQL's connections | No more at the end than at the start: the pools shrink when idle, and must not grow |
| What players see | Every stay paid once (results applied against stays ended), no bot dropped or kicked, every duel run passed, no `ERROR` in any log |

What it cannot show: a leak too slow to see in two hours, which the 24-hour soak is for, and what the production
machines' memory and kernels do (Q-3).

**Run on 2026-10-01**, from the release (item 46's code): 300 bots with stays of one to five minutes, twice.

The first run (16:44 to 18:45) is not judged. At 17:43, a build of j-redis that ran beside it deleted the
command-line client the sampler reads the store with. From then on, the store's keys and stream came back empty.
The judge threw on the empty value, and would have read a failed scan as every key at 0 (T-31, fixed). What the
first run did show, the second showed too: every stay paid (12 148 of 12 148), 34 duel runs passed, and no error,
kick or lost connection.

The second run (18:54 to 20:56) used j-redis's released jars, which no build touches. **Every rule passed**, 29 of
29:

| What | Over the second hour |
|---|---|
| Live heaps | Arena 43.0 MB on average, −0.003 MB an hour; platform 15.8, +0.042; gateway 14.1, −0.034; worker 12.1, +0.060, the most: 1.4 MB a day, 12 % of its mean |
| Threads and open files | Each process the same at the end as at the start, except the gateway's files, 356 to 354 |
| The store | Every family flat, except by its own rule: sessions +52, 54 allowed; login throttles at most 11; the stream an entry a result at every sample (+4 696 for 4 695). Its heap +2.65 MB for 5 716 entries, about 490 bytes each, against 6.79 MB allowed |
| MySQL's connections | 9 to 10 |
| What players see | 12 156 stays ended and 12 156 paid; 34 duel runs, none failed; no `ERROR`, no kick, no lost connection; 1.1 KB/s down a bot, as in the load run |

By the histograms, the classes that grew most were the store's byte arrays (+2.4 MB, the stream's entries). In
every other process they grew by under 50 KB in the hour.

**The tick, which the soak does not judge, was checked anyway.** The arena's report at its stop gave each full
room's p99 as 73 ms, against NFR-1b's 15 ms, and 68 overruns in the two hours (an overrun is a room more than 10
ticks, 400 ms, behind, which drops the time instead of catching up). The first run gave 59 ms and 48 overruns. That
is three times the load run's 16 to 25 ms above. So a plain load run followed at once: 300 bots for two minutes, no
stays ending, nothing sampled. It gave 50 ms and two overruns. **The p99 follows the hour of the day, not the code.**
With 600 bots, the builds of 2026-09-30 measured 19 to 24 ms at 02:00, and 78 to 108 ms at 18:00 with overruns.
Today's arena, with 150 bots, measured 31 ms at 12:44. The medians did not move in any of those runs: collision
0.23 to 0.25 ms, encoding and writing 2.0 to 2.6 ms. A collision pass of a quarter of a millisecond that takes 9 ms
at p99 is a thread waiting for a core on this shared machine (12 cores, load 5 to 8 that evening, other users' work
included). What the tick costs on a machine of its own is Q-3's question.

**Run again on 2026-10-03** (plan item 69, Q-50), from item 68's release, by the same rules. Every rule passed, 30
of 30: item 62's 29, and the store's tickets, a family this run's samples caught. The arena's biggest grower, as in
item 62's run, was `TankStats`: +29 KB in the judged hour, 300 objects at the start and 4 946 at the end, for 302
tanks. That is the world's pool, not a leak. A slot's stats object is made the first time a tank lands on that slot,
and reset for every tank after (`World.tankStats`, by [§2](#2-allocation-discipline-in-the-tick-loop)'s zero garbage
a tick). So the pool fills towards the number of slots tanks have used, at most 16 384 a room, and the increments
fell from 900 in the first five minutes to 11 in the last. The assists' ring (01 §7) keeps tags and slot numbers,
not references, so it holds nothing alive. The tick in that hour: each full room's p99 15.3 to 15.5 ms, no overruns;
the medians as before (collision 0.25 ms, encoding and writing 2.3 ms).

**And again on 2026-10-03** (plan item 72, Q-51), from item 71's release, with the own tank's two events in every
frame and the season job running each minute. Every rule passed, 29 of 29 (the store's tickets were not caught this
time), every stay was paid (12 136), and there was no error. The arena's biggest grower was the same pool; the
worker's grew under 40 KB in the hour. The tick: each full room's p99 16.9 and 17.2 ms, no overruns; collision's
median 0.27 ms as before; encoding and writing 2.4 ms against 2.3, from the two events a frame. The bots' mean frame
was 81.9 bytes against 75.5, as item 70 measured.

## 5. JVM flags (Temurin 21)

**The shipped units are in `backend/deploy/systemd/`, and they differ from the design's list below.** Every
long-running Java unit runs `/opt/backend/runtime/bin/java -server` with a fixed heap (`-Xms` equal to `-Xmx`),
`-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch`, `--add-opens java.base/java.nio=ALL-UNNAMED`, heap dumps on
`OutOfMemoryError`, and a GC log by `-Xlog:gc*` (5 files of 20 MB). By unit:

| Unit | Heap in the unit (three machines) | Heap on the launch's one machine (D-93) | Other flags |
|---|---|---|---|
| `backend-arena@` | 6 GB | 4 GB | `-Dio.netty.leakDetection.level=disabled`; heap dumps to `/var/log/backend` |
| `backend-gateway` | 3 GB | 2 GB | |
| `backend-platform` | 3 GB | 2 GB | `-Dsun.net.httpserver.maxIdleConnections=1000` |
| `backend-worker@` | 1.5 GB | 1 GB | |
| `backend-store@` | `STORE_HEAP`, 8 GB in the example | 4 GB, `maxmemory` 1600mb | `-Djava.net.preferIPv4Stack=true`, `-Dio.netty.leakDetection.level=disabled` |
| `backend-leaderboard-rebuild` | `-Xmx512m`; run by hand, not long-running | the same | no ZGC flags |

On the one machine, the install guide's step 8 sets these heaps: a drop-in for each unit sets `_JAVA_OPTIONS`,
which the JVM reads after the command line, and the stores' settings files set `STORE_HEAP` and `maxmemory`. On a
machine with under 48 GB, the four process types get `-Xms512m -Xmx1g -XX:-AlwaysPreTouch`, and the stores 1 GB
with `maxmemory` 400mb (D-93, O-60).

So, compared with the design, the arena runs without `-Xss512k`, `MetaspaceSize`, the Netty recycler setting,
`-Dio.netty.allocator.type=pooled` and `preferIPv4Stack`. Its heap dumps go to `/var/log/backend`, not
`/var/lib/backend/dumps`. The platform and the worker have 3 GB and 1.5 GB, not 4 GB. The reasoning below still
holds; the list is the design's.

The design's arena process (6 GB heap):

```
-Xms6g -Xmx6g                          # fixed heap: no resize pauses
-XX:+UseZGC -XX:+ZGenerational         # sub-millisecond pauses, heap-size independent
-XX:+AlwaysPreTouch                    # pay the page-fault cost at start-up, not in play
--add-opens java.base/java.nio=ALL-UNNAMED   # Netty's direct-buffer fast path
-XX:MetaspaceSize=128m
-Xss512k
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/lib/backend/dumps
-Xlog:gc*:file=/var/log/backend/arena-1.gc.log:time,uptime:filecount=5,filesize=20M
-Dio.netty.allocator.type=pooled -Dio.netty.leakDetection.level=disabled
-Dio.netty.recycler.maxCapacityPerThread=0   # we pool our own; avoid Recycler surprises
-Djava.net.preferIPv4Stack=true
```

**The Java 8 flag set does not start this JVM.** `-Xloggc`, `-XX:+PrintGCDetails`, `-XX:+PrintGCDateStamps` and
`-XX:+UseGCLogFileRotation` were all removed in JDK 9. The JVM exits with
`Unrecognized VM option 'PrintGCDateStamps'` and writes no application log at all, so the failure looks like the
application's rather than the flags'. Unified logging, `-Xlog:gc*`, replaces all four.

**Why ZGC and not G1.** A 40 ms tick cannot absorb a G1 pause, and G1's pause target is a goal, not a guarantee.
ZGC's pauses are under a millisecond and do not depend on the heap's size. The cost is roughly 10 % of throughput,
spent on barrier work, and uncompressed object references, which are not free:

**ZGC turns compressed oops off.** So every reference field in a stored object grows from 4 bytes to 8. Memory per
entity rises with it, and the same data set in j-redis is estimated 25–50 % larger
([j-redis docs/06](../../j-redis-service/docs/06-expiry-and-memory.md#6-jvm-heap-sizing)). Budget heap for it,
rather than discovering it under load.

**Several arena processes per machine is now about crash isolation only.** Under G1, a second reason was to keep
heaps small enough to collect quickly. ZGC removes that half of the argument, but not the half that matters: one
fatal error should take down one arena's rooms, not all of them (NFR-8). D-81 had put one arena on each machine,
for want of public ports.
[D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)
brought back the seven arenas, told apart by name behind the front. The launch's one machine runs three (D-93).

**`platform` and `worker`** (4 GB in the design): the same collector flags, plus virtual threads for request
handling (§1). The platform is not latency-critical in the tick's sense, but it shares a machine with arenas, so a
stop-the-world pause there would take CPU from rooms.

**j-redis** has its own flags, documented with the service
([j-redis guide 4](../../j-redis-service/docs/guide/04-run-and-operate.md)).

## 6. Linux and process placement

**As built, each unit's `LimitNOFILE` sets the open-file limit**: 65 536 for the Java processes, 262 144 for
nginx, 10 000 for MySQL. A `limits.d` file does not apply to systemd's services, and none is shipped. **The kernel
settings below are not shipped either**: no file in `deploy/` sets them, and the install guide does not. Both
files are the design's:

```
# /etc/security/limits.d/backend.conf
backend soft nofile 262144
backend hard nofile 262144

# /etc/sysctl.d/90-backend.conf
net.core.somaxconn = 4096
net.core.netdev_max_backlog = 8192
net.ipv4.tcp_max_syn_backlog = 8192
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_tw_reuse = 1
net.ipv4.ip_local_port_range = 10240 65000
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 262144 16777216
net.ipv4.tcp_wmem = 4096 262144 16777216
vm.swappiness = 1
```

**CPU pinning** follows the machine layout in
[architecture/01 §2](../architecture/01-system-topology.md#2-machine-topology). As built, a unit is pinned with
`CPUAffinity=` in a drop-in, not with `taskset`: `systemctl edit backend-arena@a1`, then a `[Service]` section with
`CPUAffinity=` and the cores. The drop-in is the machine's, because the core ranges belong to the machine; the
arenas' and the stores' units say so in their comments. The design's example is machine B, which carries three
arenas (24 cores; check `lscpu` for the real NUMA layout before copying this):

| Process | Cores |
|---|---|
| nginx | 0 |
| gateway | 1-2 |
| platform | 3-4 |
| j-redis `events` | 5-6 |
| j-redis `session` replica | 7 |
| arena-1 | 8-11 |
| arena-2 | 12-15 |
| arena-3 | 16-19 |
| OS and headroom | 20-23 |

The last four cores are left unpinned on purpose. Kernel work, interrupt handling and the occasional backup all
need somewhere to run that is not a room thread's core.

Disable transparent huge pages (`never`), or enable `-XX:+UseTransparentHugePages` only after measuring. Use
`chrt` or `nice -n -5` for arena processes if the machine is shared with batch work. Neither is set by the shipped
units.

## 7. Timing precision

**On an unloaded machine**, `LockSupport.parkNanos` on Linux wakes within about 50–100 µs. The words "on an unloaded
machine" matter more than the figure.

It was measured on the development VM at a load average of 11 on 12 cores. An idle room, doing 0.08 ms of work per
tick, showed a **wall-clock p99 of 11 ms and a maximum of 115 ms**, purely from being scheduled back in after each
park. The same simulation, measured flat out and never yielding, costs 0.55 ms at p99. A room thread that parks
between ticks is at the mercy of the scheduler. So [CPU pinning](#6-linux-and-process-placement) is not a nicety: it
is what makes the tick budget meaningful.

The rules:

- Never accept a wall-clock tick measurement from an oversubscribed machine.
- Do not busy-spin.
- Do not use `Thread.sleep(ms)`: it has 1 ms granularity and coarse timer slack.
- Always compute the next deadline from the previous deadline, not from "now", so drift does not add up.

## 8. Stability safeguards

- **Tick watchdog — built 2026-09-26.** A daemon thread per registry checks each room every 500 ms.
  - A room that has not finished a tick for 2 s is logged once, with the stuck thread's stack. From then on, the
    room is not offered to new players: the allocator makes another room.
  - At 10 s the room is abandoned. Its players, and those whose joins or resumes were still queued, get `Kick(5)`.
    The watchdog finds them through concurrent sets, because the room's own list and queue belong to the stuck
    thread. The registry then replaces the room.
  - Queued joiners and the 2 s cut-off were added on 2026-09-26 ([T-9](../defects.md#4-concurrency)).

  This covers a room that *hangs*, which the exception boundary below cannot see. It has two limits, stated
  rather than hidden. **Its players' results since their last checkpoint are lost**: the tally belongs to the
  stuck thread, and another thread cannot read it safely. And **the thread cannot be stopped from outside**, so it
  is reported, for a restart at a quiet time. If the thread ever returns, it finds itself stopped and publishes
  then; results are idempotent.
- **Exception boundary — built 2026-09-26.** Before, nothing caught anything. One exception ended the room thread
  silently: its players froze with their sockets open, and the registry kept admitting new players to it. Now
  there are three levels:
  - **One client:** an exception while encoding a player's frame disconnects that player with `Kick(5)` (retry
    with backoff), because their handle table may be half-updated. Everyone else carries on.
  - **One tick:** the exception is logged with the tick, and the loop continues. The offending entity is not killed,
    as first designed: nothing identifies it reliably.
  - **The room:** three failed ticks within 100 loop iterations (four seconds) close the room. The window counts
    iterations, not ticks, because a failed tick does not advance the room's tick. Every player's result is
    published as at shutdown. Everyone, and every queued join or resume, gets `Kick(5)`. The registry drops the
    room, so a healthy one takes its place, and the fleet's counters keep what it counted
    (`backend_arena_rooms_failed_total`). The rule is a window, not a run of failures in a row: a fault that strikes
    only on a 15 Hz client's rounds fails at most two ticks in a row.
- **Memory ceiling:** entity capacity, the bullet cap per tank and the shape cap per room are hard limits. A spawn
  beyond them is refused, never grown. **As built there is no cap per tank**: the world's entity pool (16 384
  entities a room) is the only limit on bullets.
- **Slow-client isolation:** snapshots are skipped per client when its socket pushes back
  (`backend_arena_snapshots_skipped_total`). One slow client cannot slow the room.
- **Logging never waits — built 2026-09-26** ([T-10](../defects.md#4-concurrency)). A room thread, an event loop or
  the watchdog that logs puts the line on a queue (Logback's async appender, 8 192 lines, `neverBlock`). One thread
  of its own writes the lines to the journal. A journal that stops reading costs lines, dropped once the queue is
  full, never a tick. Before, it froze the room, the watchdog that should have caught it, and the shutdown that
  should have published its results.
- **Graceful drain.** The design: on SIGTERM the arena stops accepting joins, sets rooms to "closing", lets finite
  matches end (5 minutes at most), and kills endless rooms with a 30 s warning event to clients. **As built** at
  first: the arena withdrew from the directory, stopped every room at once (waiting up to 5 s for each), published
  every player's result, and sent everyone `Kick(5)` (since 2026-09-26,
  [P-22](../defects.md#2-protocol--the-client-contract); before, the sockets closed bare). **Since 2026-09-29 a stop
  drains**: made matches play to their end first (up to eleven minutes; the unit allows 12), and the public rooms
  send their players back to the lobby
  ([01 §8.6](01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7), D-29). There is no warning event.
