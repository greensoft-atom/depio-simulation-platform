# 07 — Threading model and performance engineering

Java 21 with generational ZGC
([D-2](../architecture/03-decision-log.md#d-2--java-21-with-generational-zgc)).

## 1. Thread inventory (arena process)

| Thread | Count | Role | Touches room state? |
|---|---|---|---|
| Netty boss | 1 | accept | no |
| Netty workers | 2 (pin to 2 cores) | TLS, frame decode, `InputQueue.offer`, socket writes | no |
| Room threads | 4–6 per process | simulation + snapshot encoding | **only their own room** |
| j-redis client IO | 1 (Netty, shared) | async commands; callbacks re-enqueued to the room queue | no |
| Housekeeper | 1 | heartbeats, metrics dump, idle room reaping, spool retry | via messages only |
| Logback async appender | 1 | disk | no |
| Room watchdog | 1 per registry | stall detection; abandons a room stuck for 10 s (§8) | reads volatiles; kicks through concurrent sets |
| GC threads | JVM | | |

Rules:

1. **Room state is single-writer.** No `synchronized`, no `volatile` fields on
   entities, no `ConcurrentHashMap` inside a room. Cross-thread contact happens
   through exactly two doors: `Room.inputQueue` (in) and `Channel.write` (out).
2. **Callbacks never run simulation logic.** A j-redis reply arrives on the client
   IO thread; the callback wraps it in a message and offers it to the room's
   queue. Same for timers.
3. **Rooms never share objects.** Config tables are immutable after load and
   may be shared; everything else (pools, scratch buffers, RNG) is per room.

`InputQueue` is a bounded MPSC queue: `org.jctools:jctools-core`
`MpscArrayQueue`, capacity 4 096. When it is full the input is dropped, because
the next one supersedes it anyway. **As built there is no input queue**: each
connection holds its latest input in one `AtomicLong`, which the room reads once
a tick, so a newer input simply replaces an older one; a fire press is latched
so a tap between two ticks is not lost. Joins, resumes and leaves go through
`MpscArrayQueue`s, and the allocator reserves places through two atomic
counters. Three concurrent collections are deliberate exceptions to rule 1:
the watchdog finds a stuck room's players, queued joins and queued resumes in
concurrent sets (§8); and the registry's `stays` map (resume secret → room,
[02 §10](02-networking.md#10-session-reconnect-and-app-lifecycle)), shared by
every room against rule 3, is written by room threads and read by event loops.

**Where an arena's cores go** (the load run, 2026-09-30, plan item 14): its two
Netty workers used more than all its room threads together, 1.15 cores against
0.87 for 1 200 clients, about 27 µs of CPU a message. **Tried and measured: no
gain from fewer wakeups.** Each snapshot is a `writeAndFlush` from the room
thread, a task handed to the event loop, and the idea was that the wakeups cost
the most: the room wrote each snapshot without a flush, which Netty queues
without waking the loop, and flushed once per loop per round. Three runs each
way, alternated at 1 200 bots, gave the workers 1.11 cores without it and 1.12
with it: at that rate the loops are awake anyway. It was taken out: it saved
nothing and made every snapshot wait for its room's round to end.

**Room threads are platform threads, not virtual threads.** A room thread is
CPU-bound and pinned to a deadline; virtual threads exist to make blocking
cheap, and a room thread must never block. Virtual threads belong in
`platform` and `worker`, where requests wait on MySQL.

## 2. Allocation discipline in the tick loop

Target: **zero garbage per tick in steady state.** Verify with
`-Xlog:gc` — a room at steady state should see collections minutes apart, not
seconds — and with the tick benchmark, which counts the ticks that allocated.
**As measured** (2026-09-26): the simulation and the encoder allocate nothing;
the latency histograms do, a handful of times in a room's life, when a phase
takes longer than it ever has and HdrHistogram resizes itself.

ZGC makes a missed allocation cheaper than G1 did, since its pauses do not grow
with heap size, but it does not make allocation free: every byte still costs
barrier work and eventually a cycle. The discipline below is unchanged.

Do:
- Pool `Entity`, `ClientMessage`, `StatModifier`; pool `IntList` scratch buffers per client.
- Use `for (int i = 0; i < list.size; i++)` over `IntList.items[i]`; never enhanced-for on collections (allocates an `Iterator`).
- Keep per-entity data in primitive fields; per-kind lists as `int[]`.
- Reuse one `ByteBuf` per client per tick from `PooledByteBufAllocator` (returned to the pool by Netty after write).
- Precompute per-class constants (reload ticks, radius, view size) at class change, not per tick.
- Use `float` math and `(float) Math.sqrt`; avoid `Math.hypot` (slow); avoid `Math.atan2` in loops unless needed (aim comes from client).

Don't:
- Box (`Integer` in maps, `Float` in lists). No `HashMap<Integer, Entity>` — use `Entity[]` indexed by id or `Int2ObjectOpenHashMap`.
- Create lambdas that capture locals inside `tick()` (each capture allocates). Method references to static methods are fine.
- Use `String.format`, string concatenation, or logging with `+` in hot paths; use `log.debug("… {}", id)` with `isDebugEnabled()` guards.
- Throw exceptions for flow control. Validation returns booleans.
- Call `System.currentTimeMillis()` per entity; read the clock once per tick.

Java 21 has `record` and `var`, but **still no value types** — a record is a
heap object like any other, so it is not a way to avoid allocation in the tick
loop. Records are right for immutable configuration and message payloads,
wrong for per-entity state.

If `Entity` objects ever show up in profiles, the escape hatch is
struct-of-arrays: keep `float[] x, y, vx, vy, radius, hp…` in `World` and pass
`int id`. Start with objects, which are readable, and switch only if
measurement says so.

## 3. Data structures

| Need | Use |
|---|---|
| id → entity | `Entity[]` |
| dynamic int list | own `IntList { int[] items; int size; }` with `add/removeAtSwap/clear` |
| int → int / int → object | fastutil `Int2IntOpenHashMap` / `Int2ObjectOpenHashMap`, or own open-addressing map (~80 lines) |
| spatial | intrusive-linked grid (`int[] head, next`) rebuilt each tick |
| free slots | `int[]` stack |
| per-client known entities | own `IntIntMap` (id → generation) sized to view capacity (~512) |
| RNG | xorshift128+ in a final class, one per room |
| modifiers | arrays bucketed by stat: `StatModifier[8][]` + counts |

Sorting scoreboard: keep tanks in an `int[]` and do an insertion sort by score
every 25 ticks (n ≤ 200 → trivial).

## 4. Tick budget and what to measure

Per room, per tick, record nanoseconds for each phase into a ring of 1 024
samples and export p50/p99/max every 10 s. **As built**, the per-phase numbers
are cumulative histograms printed when the arena stops; what is exported while
it runs is the whole tick's p99 over the last 10 s
(`backend_arena_tick_p99_seconds`). In that report the simulation's total is
`room.step` alone, as the benchmark's is, judged against NFR-1a's 2 ms; the
whole tick, snapshots and everything else in it, has a line of its own against
NFR-1b's 15 ms. Until 2026-09-30 the whole tick was printed as the
simulation's total and failed against the simulation's budget (O-3). The phases:

```
drainInputs | tanks | bullets | shapes | grid | collide | deaths | encode | write | total
```

Targets on a 3 GHz core, 150 tanks + 1 500 bullets + 1 500 shapes:

| Phase | Target |
|---|---|
| updates + grid | < 0.5 ms |
| collide | < 1.0 ms |
| encode all clients (150 × ~120 entities) | < 2.0 ms |
| total (simulation only) | < 2 ms p99 — **measured 0.55 ms**; a room grown from level 1 with the second class tier, 1.22 ms; **a room of level-45 tanks of the second tier, 2 250 bullets in flight, 2.9 ms: over** ([backend README](../../backend/README.md#what-the-second-tier-costs-2026-09-27), Q-3); the third tier costs the same, held there by D-22 ([backend README](../../backend/README.md#what-the-third-tier-costs-2026-09-28)); the whole tree 13 % more at p50, from the Battleship's drones, which D-22 does not count ([backend README](../../backend/README.md#what-the-rest-of-the-tree-costs-2026-09-28)) |
| snapshot tick (simulation + encode all clients) | < 15 ms p99 — **measured 6.2 ms**, against a 40 ms deadline |

If `encode` dominates: (a) reduce snapshot rate for far entities (send
bullets every tick, shapes every 2nd tick), (b) move encoding to a second
thread per room using a double-buffered read-only copy of positions (`float[]`
snapshot arrays) — the only sanctioned place where a room's data is read by
another thread, and only immutable copies.

Micro-benchmarks: JMH 1.37 (in the [java21-offline](../../java21-offline/README.md)
bundle). Bench the collision pass and the snapshot encoder in isolation with
synthetic worlds before optimising.

Load test: headless bot client (`tools/botclient`) that opens N TCP
connections, sends random inputs at 30 Hz, decodes snapshots, and reports
receive rate and tick echo latency. Run 200 bots against one room; watch the
tick histogram and `skippedSnapshots`.

**The load run on the development machine** (designed 2026-09-30, plan item
14). `tools/BotClient` takes the path a client takes (register, log in, the
lobby, a ticket, the arena) and then plays as a client does: ten input packets
a second and a ping every ten seconds ([02 §9](02-networking.md#9-input-prediction-and-reconciliation)),
snapshots read and counted. Until 2026-10-04 it sent twenty input packets a
second and no ping, so the runs recorded below carried twice a client's
upstream packets. It runs against the release's processes as the drill starts them
(`BOTS=<n> client/headless-drill.sh`), with one arena of enough rooms of 150.
Steps of rising size, each playing two minutes once every bot is in, until a
step misses a target or reaches the cap: this machine is shared, so no more
than about half its twelve cores. Each step records:

| What | Target | From |
|---|---|---|
| Onboarding: bots a second through register, login and ticket | recorded; the limit is platform's Argon2, eight at a time | BotClient |
| Each room's tick p99 | NFR-1b, 15 ms | `backend_arena_tick_p99_seconds`, overruns |
| Snapshots a bot a second | 15 at the mobile profile, none held or skipped | BotClient, `snapshots_skipped_total`, `snapshots_held_total` |
| Bytes a bot a second | NFR-2: 15 MB/hour is 4.2 KB/s | BotClient |
| Bots dropped or kicked | none | BotClient |
| Each process's CPU and memory | recorded, against the cap | `ps` |

What it cannot show: wall-clock latency on a shared machine (Q-3), and what the
network between two machines adds. It finds where one machine's processes give
out first, and whether that is the backend's to fix.

**Run on 2026-09-30**, from the release, every process on ZGC as its unit has
it, 150 players and 1 500 shapes a room on a 5 700 map as `arena.env.example`
has them, two minutes a step once every bot was in:

| Bots | Full rooms | In the lobby | Per bot | Arena cores: I/O threads, rooms | All, bots included |
|---|---|---|---|---|---|
| 150 | 1 | 6.6 s | 14.98 snapshots/s, 1.15 KB/s | 0.44: 0.21, 0.13 | 0.9 |
| 600 | 4 | 11.9 s | 14.90, 1.11 KB/s | 1.23: 0.70, 0.45 | 2.2 |
| 1 200 | 8 | 21.0 s | 14.90, 1.11 KB/s | 2.10: 1.15, 0.87 | 3.5 |

At every step no bot was dropped or kicked, no round skipped and no tick
overran. A full room's whole tick was 3.0 ms at p50 (the simulation 0.5, encoding
and writing 2.2) and 16 to 25 ms at p99, which moved with the machine's load
between runs rather than with the number of bots; the idle room beside them
measured 4 to 9 ms. What held back rounds in some runs (up to 0.6 % of them)
came and went with the machine's load, in either build. Bytes a bot are 1.1
KB/s, a quarter of NFR-2's ceiling, at the density bots make.

**What it found.**

- **The arena's network threads cost more than its rooms**, and three quarters
  of that is the kernel's: at 600 bots its two Netty workers used 0.19 cores of
  user time and 0.59 of kernel time, sending, receiving and waiting in `epoll`,
  while the rooms were 0.53 user and 0.05 kernel. About 9 µs of Java and 28 µs
  of kernel a message. Over loopback the kernel does the receiving side's work
  in the sender's time too, so this overstates it; the figure that counts needs
  the bots on another machine (Q-3). Fewer wakeups of the loops were tried and
  gained nothing (§1).
- **Two workers a process fill up first.** At these costs an arena's two Netty
  workers would be full near 2 100 players, against ~7 000 in one arena process
  in the capacity model (Q-3, [architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model)).
- **The report at shutdown misnamed the whole tick** as the simulation's total
  (O-3), fixed.
- **The generator costs about a core a thousand bots**, and onboarding is
  platform's Argon2 at eight at a time: 57 bots a second.

It stopped at 1 200 bots, 3.5 of the machine's twelve shared cores: 1 800
would reach the cap of about half of them. Ten thousand needs Q-3's machines.

**The soak on the development machine** (designed 2026-10-01, plan item 47,
[Q-41](../requirements/01-scope-and-nfrs.md#7-open-questions)). Phase 6's soak,
24 hours at target load, waits on Q-3's machines. This one asks what needs
none of them: **does anything grow that should not** while the paths players
use run for hours. A leak is a slope, and a slope shows at a fraction of the
load: a map entry of 200 bytes left behind per stay is a megabyte an hour here.

- **Who plays.** `BotClient` with stays that end (`BACKEND_BOT_STAYS=<min>:<max>`
  seconds): each bot logs in once, as a client keeps its session a day, then
  asks its lobby for a match, plays a stay of a random length in the range,
  pinging its lobby every 30 s as a client does (03 §3), leaves as a player
  does, and asks again; every fourth stay it also closes its lobby connection
  and opens another, as an app sent to the background does.
  300 bots, stays of one to five minutes: about 1.7 stays ending a second, some
  twelve thousand results in two hours, each applied, pushed and ranked.
- **Made matches throughout.** The headless client's `duel` scenario, again
  every 30 seconds: two new players matched and confirming, a rated result, what
  it paid pushed, the rating board read.
- **Two hours**, the first an allowance (the JIT, pools and caches filling), the
  second judged. Sampled every five minutes: each process's live heap (its live
  objects' bytes, counted by `jcmd GC.class_histogram` after a collection; the
  heap's "used" after one moved by ±6 MB between rounds, ZGC counting whole
  pages), threads and open files; the store's keys by family (`SCAN`) and the
  stream's length; MySQL's connections.

**Passed** when, over the second hour:

| What | Rule |
|---|---|
| Live heap: arena, platform, gateway, worker | The slope, run on for 24 hours, under a quarter of the mean; the median of every pair of samples' slopes (Theil–Sen), since a round taken while a match's room lived swung a least-squares line |
| Threads and open files, each process | No more at the end than at its start, give or take two |
| The store's key families | Flat, but for those that grow by design, each by its own rule: the result stream an entry a result (trimmed at 24 hours, D-26); sessions four a duel run, its two new players' sessions and their indexes (a day each); login throttles, at every sample, no more than two a duel run started in the 15 minutes before it, the window they live |
| The store's heap | Its growth no more than twice what the stream's entries account for, at ~530 bytes each (D-26) |
| MySQL's connections | No more at the end than at the start: the pools shrink when idle, and must not grow |
| What players see | Every stay paid once (results applied against stays ended), no bot dropped or kicked, every duel run passed, no `ERROR` in any log |

What it cannot show: a leak too slow for two hours, which the 24-hour soak is
for, and what the production machines' memory and kernels do (Q-3).

**Run on 2026-10-01**, from the release (item 46's code), 300 bots with stays of
one to five minutes, twice.

The first (16:44 to 18:45) is not judged. At 17:43 a build of j-redis run beside
it deleted the command-line client the sampler reads the store with, and from
then on the store's keys and stream came back empty; the judge threw on the
empty value, and would have read a failed scan as every key at 0 (T-31, fixed).
What it did show, the second showed too: every stay paid (12 148 of 12 148), 34
duel runs passed, no error, kick or lost connection.

The second (18:54 to 20:56), from j-redis's released jars, which no build
touches: **every rule passed**, 29 of 29.

| What | Over the second hour |
|---|---|
| Live heaps | Arena 43.0 MB on average, −0.003 MB an hour; platform 15.8, +0.042; gateway 14.1, −0.034; worker 12.1, +0.060, the most: 1.4 MB a day, 12 % of its mean |
| Threads and open files | Each process the same at the end as at the start, but the gateway's files, 356 to 354 |
| The store | Every family flat but by its own rule: sessions +52, 54 allowed; login throttles at most 11; the stream an entry a result at every sample (+4 696 for 4 695). Its heap +2.65 MB for 5 716 entries, about 490 bytes each, against 6.79 MB allowed |
| MySQL's connections | 9 to 10 |
| What players see | 12 156 stays ended and 12 156 paid; 34 duel runs, none failed; no `ERROR`, no kick, no lost connection; 1.1 KB/s down a bot, as in the load run |

The classes that grew most, by the histograms, were the store's byte arrays
(+2.4 MB, the stream's entries), and under 50 KB in the hour in every other
process.

**The tick, which the soak does not judge, was checked anyway:** the arena's
report at its stop gave each full room's p99 as 73 ms against NFR-1b's 15, and
68 overruns (a room more than 400 ms behind) in the two hours; the first run,
59 ms and 48. That is three times the load run's 16 to 25 ms above, so a plain
load run followed at once, 300 bots for two minutes, no stays ending, nothing
sampled: 50 ms and two overruns. It follows the hour, not the code. With 600
bots, the builds of 2026-09-30 measured 19 to 24 ms at 02:00 and 78 to 108 ms
at 18:00, with overruns; today's arena, with 150, 31 ms at 12:44. The medians did not
move in any of those runs: collision 0.23 to 0.25 ms, encoding and writing 2.0
to 2.6 ms. A collision pass of a quarter of a millisecond that takes 9 ms at
p99 is a thread waiting for a core on this shared machine (12 cores, load 5 to
8 that evening, other users' work included). What the tick costs on a machine
of its own is Q-3's.

**Run again on 2026-10-03** (plan item 69, Q-50), from item 68's release, by the
same rules: every rule passed, 30 of 30 (item 62's 29, and the store's tickets,
a family this run's samples caught). The arena's biggest grower, as in item 62's run, was
`TankStats` (+29 KB in the judged hour; 300 objects at the start, 4 946 at the
end, for 302 tanks). That is the world's pool, not a leak: a slot's stats are
made the first time a tank lands on it and reset for every tank after
(`World.tankStats`, by [§2](#2-allocation-discipline-in-the-tick-loop)'s zero
garbage a tick), so the pool fills towards the slots tanks have used, 16 384 a room at most, and the increments
fell from 900 in the first five minutes to 11 in the last. The assists' ring
(01 §7) keeps tags and slot numbers, not references, so it holds nothing alive.
The tick on that hour: each full room's p99 15.3 to 15.5 ms, no overruns; the
medians as before (collision 0.25 ms, encoding and writing 2.3 ms).

**And on 2026-10-03 again** (plan item 72, Q-51), from item 71's release, with
the own tank's two events in every frame and the season job running each
minute: every rule passed, 29 of 29 (the store's tickets not caught this time),
every stay paid (12 136), no error. The arena's biggest grower the same pool;
the worker's under 40 KB in the hour. The tick: each full room's p99 16.9 and
17.2 ms, no overruns; collision's median 0.27 ms as before, encoding and writing
2.4 ms against 2.3, the two events a frame; the bots' mean frame 81.9 bytes
against 75.5, as item 70 measured.

## 5. JVM flags (Temurin 21)

**What the shipped units use is in `backend/deploy/systemd/`**, and differs from
this list: the arena runs without `-Xss512k`, `MetaspaceSize`, the Netty
recycler setting and `preferIPv4Stack`, and `platform` and `worker` have 3 GB
and 1.5 GB, not 4. The reasoning below still holds; the list is the design's.

Arena process (6 GB heap):

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

**The Java 8 flag set will not start this JVM.** `-Xloggc`,
`-XX:+PrintGCDetails`, `-XX:+PrintGCDateStamps` and `-XX:+UseGCLogFileRotation`
were all removed in JDK 9+; the JVM exits with
`Unrecognized VM option 'PrintGCDateStamps'` and writes no application log at
all, so the failure looks like the application rather than the flags. Unified
`-Xlog:gc*` replaces the lot.

**Why ZGC and not G1.** A 40 ms tick cannot absorb a G1 pause, and G1's pause
target is a goal rather than a guarantee. ZGC's pauses are sub-millisecond and
independent of heap size. The trade is roughly 10 % of throughput to barrier
work, and uncompressed object references — which is not free:

**ZGC turns compressed oops off**, so every reference field in a stored object
grows from 4 bytes to 8. Per-entity memory rises accordingly, and the same
dataset in j-redis estimates 25–50 % larger
([j-redis docs/06](../../j-redis-service/docs/06-expiry-and-memory.md#6-jvm-heap-sizing)).
Budget heap for it rather than discovering it under load.

**Several arena processes per machine is now about crash isolation only.**
Under G1 the reason was also to keep heaps small enough to collect quickly;
ZGC removes that half of the argument, but not the half that matters — one
fatal error should take down one arena's rooms, not all of them (NFR-8).

`platform` and `worker` (4 GB): the same collector flags, plus virtual threads
for request handling. `platform` is not latency-critical in the tick sense, but
it shares a machine with arenas, so a stop-the-world pause there would steal
CPU from rooms.

j-redis: its own flags, documented with the service
([j-redis guide 4](../../j-redis-service/docs/guide/04-run-and-operate.md)).

## 6. Linux and process placement

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

CPU pinning follows the machine layout in
[architecture/01 §2](../architecture/01-system-topology.md#2-machine-topology).
For machine B, which carries three arenas (24 cores; check `lscpu` for the real
NUMA layout before copying this):

| Process | `taskset -c` |
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

Leaving the last four cores unpinned is deliberate: kernel work, interrupt
handling and the occasional backup all need somewhere to run that is not a room
thread's core.

Disable transparent huge pages (`never`), or enable
`-XX:+UseTransparentHugePages` only after measuring. Use `chrt`/`nice -n -5`
for arena processes if the machine is shared with batch work.

## 7. Timing precision

`LockSupport.parkNanos` on Linux wakes within ~50–100 µs **on an unloaded
machine**. That qualifier turns out to matter more than the figure.

Measured on the development VM at load average 11 of 12 cores: an idle room
doing 0.08 ms of work per tick showed a **wall-clock p99 of 11 ms and a maximum
of 115 ms**, purely from being scheduled back in after each park. The same
simulation measured flat-out, never yielding, costs 0.55 ms p99. A room thread
that parks between ticks is at the mercy of the scheduler, so
[CPU pinning](#6-linux-and-process-placement) is not a nicety — it is what
makes the tick budget meaningful. Never accept a wall-clock tick measurement
from an oversubscribed machine. Do not busy-spin. Do not use `Thread.sleep(ms)` (1 ms granularity
and coarse timer slack). Always compute the next deadline from the previous
deadline, not from "now", so drift does not accumulate.

## 8. Stability safeguards

- **Tick watchdog — built 2026-09-26.** A daemon thread per registry checks
  each room every 500 ms. A room that has not finished a tick for 2 s is logged
  once, with the stuck thread's stack, and from then on the room is not offered
  to new players: the allocator makes another. At 10 s it is abandoned: its
  players, and those whose joins or resumes were still queued, get `Kick(5)` (found through
  concurrent sets, since the room's own list and queue belong to the stuck
  thread), and the registry replaces it. Queued joiners and the 2 s cut-off were
  added 2026-09-26 ([T-9](../defects.md#4-concurrency)). This
  covers a room that *hangs*, which the boundary below cannot see. Two limits,
  stated rather than hidden. **Its players' results since their last checkpoint
  are lost**: the tally belongs to the stuck thread and cannot be read safely
  from another. And **the thread cannot be stopped from outside**, so it is
  reported for a restart at a quiet time. If it ever returns, it finds itself
  stopped and publishes then; results are idempotent.
- **Exception boundary — built 2026-09-26.** Before, nothing caught anything:
  one exception ended the room thread silently, its players froze with their
  sockets open, and the registry kept admitting new ones to it. Now, at three
  levels:
  - **One client:** an exception while encoding a player's frame disconnects
    that player with `Kick(5)` (retry with backoff), because their handle table
    may be half-updated. Everyone else carries on.
  - **One tick:** logged with the tick, and the loop continues. The offending
    entity is not killed as first designed: nothing identifies it reliably.
  - **The room:** three failed ticks within 100 loop iterations (four
    seconds; iterations, because a failed tick does not advance the room's
    tick) close it. Every player's
    result is published as on shutdown, everyone and every queued join or
    resume gets `Kick(5)`, and the registry drops the room, so a healthy one
    takes its place; the fleet's counters keep what it counted. It is a
    window, not a run: a fault that strikes only on a 15 Hz client's rounds
    fails at most two ticks in a row.
- **Memory ceiling:** entity capacity, bullet cap per tank, shape cap per room
  are hard limits; spawns beyond them are refused, never grown. **As built there
  is no cap per tank**: the world's entity pool is the only limit on bullets.
- **Slow-client isolation:** snapshots are skipped per client on backpressure;
  one slow client cannot slow the room.
- **Logging never waits — built 2026-09-26** ([T-10](../defects.md#4-concurrency)).
  A room thread, an event loop or the watchdog that logs puts the line on a
  queue; one thread of its own writes to the journal. A journal that stops
  reading costs lines, dropped once the queue is full, never a tick: before,
  it froze the room, the watchdog that should have caught it, and the shutdown
  that should have published its results.
- **Graceful drain:** on SIGTERM the arena stops accepting joins, sets rooms
  to "closing", lets finite matches end (max 5 min), and kills endless rooms
  with a 30 s warning event to clients. **As built**: it withdraws from the
  directory, stops every room at once (waiting up to 5 s each), publishes every
  player's result, and sends everyone `Kick(5)` (since 2026-09-26,
  [P-22](../defects.md#2-protocol--the-client-contract); before, the sockets
  closed bare). Since 2026-09-29 a stop drains: made matches play to their end
  first, and the public rooms send their players back
  ([01 §8.6](01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7),
  D-29); there is no warning event.
