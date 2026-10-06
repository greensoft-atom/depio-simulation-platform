# Scaling and performance

How each part of the backend grows, how to add capacity to it step by step, what it costs as
measured, and what is still unproven. For operators planning capacity; the design's own
reasoning is in [architecture/01](../architecture/01-system-topology.md) and
[07](../detailed-design/07-threading-and-performance.md).

**Where the numbers come from.** Every measurement here was taken on the shared development
VM (12 cores, Ubuntu 24.04, other users' jobs on it), with every process, the bots, the
stores and MySQL on that one machine, over loopback. **Nothing has been measured on the
production hardware** ([Q-3](../requirements/01-scope-and-nfrs.md#7-open-questions)). CPU costs
carry over to other machines; wall-clock latencies (a tick's p99) are this VM's, and move with
its load. The numbers dated 2026-10-06 were measured for this document, on the current build;
the older ones are cited with their date and source.

## 1. The shape, at a glance

| Part | Holds state | Grows with | Add capacity by | Limit as built | Cost, measured |
|---|---|---|---|---|---|
| `arena` | its rooms (a match lives in one process) | players in battle | rooms per arena, arenas per machine (one port each), machines | about **2 300 players a process**: its two network threads (§2.5) | **0.24 cores a full room** of 150 (0.13 network, 0.10 simulation), level-1 rooms; more as rooms mature (2026-10-06) |
| `gateway` | its lobby connections | players connected | one per machine, behind that machine's nginx | ~17 000 connections a gateway by design; **not measured** | 14 MB live heap in a 300-bot soak (2026-10-01) |
| `platform` | none (the store and MySQL hold it) | logins, API calls | one per machine, behind nginx | password hashing, 8 at a time: **~57 logins a second** a process | 62–88 ms a hash |
| `worker` | none (a consumer group) | match results a second | `backend-worker@<id>` instances | MySQL's commit | **10–16 results a second** a worker (2026-09-30) |
| j-redis `session` | sessions, tickets, the arena directory, queues, parties, boards | everything | a bigger machine (one primary; the replica is for failover) | one command thread; `maxmemory` 3 GB | not measured trustworthily on this VM (§4.6) |
| j-redis `events` | the result stream, a day of it | results a second | a bigger heap | `maxmemory` 3 GB holds a day of results up to **~19 000 players** (O-37) | ~530 bytes a result |
| MySQL | the system of record | results, purchases, logins | a bigger machine (one primary; the replica is for failover and backups) | `max_connections` 151 | 35 ms a result alone; 104 a second from 8 threads |
| nginx | none | TLS for the lobby and the API | one per machine | `worker_connections` 16 384 | |

Match traffic never passes through nginx or the gateway ([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)):
a client connects straight to the arena its grant names.

## 2. The match tier: arenas

### 2.1 How players are placed

- **Each arena announces itself** to the `session` store every 3 seconds: its name, the
  `host:port` clients dial, its players and free places, its rooms and free rooms; an entry
  not renewed for 10 seconds lapses ([04 §3](../detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets)).
- **A seat in the public arena** (`POST /v1/match-requests`): `platform` picks the live arena
  with the **most free places** (`MAX_ROOMS × MAX_PLAYERS − players`), writes a ticket and
  answers that arena's address.
- **A made match** (a duel, a team match, a tournament round, a sandbox): the matcher picks the
  arena with the **most free rooms**, and promises the room in the store so that two matches
  chosen at once do not both take the last one ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
- **Inside an arena**, a public player goes to the **fullest room still below 145** of 150
  (`MAX_PLAYERS − 5`); a new room opens only when every open room is that full, up to
  `MAX_ROOMS`; past that, any room with a place. Rooms fill one after another, so players meet,
  rather than spreading thin.

**The allocator is the load balancer.** There is none in front of the arenas: a room lives in
exactly one process, and only the allocator knows which, so a proxy could not route a player
and would add a hop to the traffic that can least afford one (about 1.1 Gbit/s at full load).

### 2.2 One port per arena, many connections per port

A TCP connection is told apart by both ends' addresses and ports, so one listening port takes
as many connections as the process can serve: the limits are CPU, the network and file
descriptors (the unit's `LimitNOFILE=65536`). Each arena instance has its own port
(`ARENA_PORT`, 9001 in the example) and its own metrics port, and advertises
`ADVERTISE_HOST:ARENA_PORT`; a machine runs several. The design has seven: `a1`, `a2` on A,
`b1` to `b3` on B, `c1`, `c2` on C, each a `backend-arena@<name>` unit with its
`/etc/backend/arena-<name>.env`.

### 2.3 Settings that size an arena

| Setting (`arena-<name>.env`) | Example | What it does |
|---|---|---|
| `ARENA_PORT` | 9001 | the port clients dial; each instance its own |
| `MAX_ROOMS` | 4 | rooms in this process, public and made together; each room a thread |
| `MAX_PLAYERS` | 150 | players a public room |
| `MAP_SIZE`, `SHAPES` | 5 700, 1 500 | the room's world; the density the bandwidth budget assumes |
| `ADVERTISE_HOST` | `a.example.com` | the machine's public name, which a TLS certificate names |
| `BACKEND_METRICS_ADDR` | `127.0.0.1:9110` | each instance its own |
| (unit) heap | `-Xms6g -Xmx6g` | one arena's live heap is ~43 MB at full rooms (2026-10-01): the heap is for headroom and ZGC |

### 2.4 Adding capacity

**More rooms in an arena.** Raise `MAX_ROOMS` and restart the arena (a rolling restart:
[01 §5](01-deploy.md#5-rolling-deployment)). Each full room costs about 0.24 cores
(§4.1); stop well short of the network threads' limit (§2.5).

**Another arena on a machine**, for example `a3` beside `a1` and `a2`:

```console
cp /etc/backend/arena-a1.env /etc/backend/arena-a3.env
sed -i -e 's/^ARENA_PORT=.*/ARENA_PORT=9003/' \
       -e 's/^BACKEND_METRICS_ADDR=.*/BACKEND_METRICS_ADDR=127.0.0.1:9113/' /etc/backend/arena-a3.env
systemctl enable --now backend-arena@a3
firewall-cmd --permanent --add-port=9003/tcp && firewall-cmd --reload
```

Check that it is live: `GET /admin/arenas` lists `arena-a3` with port 9003 within a few
seconds ([api/03](../api/03-admin-api.md)), and the platform starts sending players to it at
once. Pick a metrics port no other process on the machine uses: the examples give the platform
9101, the gateway 9103, the workers 9102 and 9112, the arenas 9110 onwards. A drop-in under
`backend-arena@.service.d/` (the heap on a small machine, a credential) applies to every
instance, the new one included. If the arenas serve TLS, the new one takes the machine's
keystore as the others do ([01 §8](01-deploy.md#8-tls-for-match-traffic)).

**Another machine.** Install it as the [install guide](03-install-guide.md) does, with the
store and MySQL addresses of the existing ones (its "Three machines" section), its arenas
named for it (`d1`, `d2`: **an arena's name must be unique across the fleet**, or two
overwrite each other's directory entry), and `ADVERTISE_HOST` its own public name. Its arenas
register in the same store and are used at once; no other machine changes.

**Taking an arena out.** `systemctl stop backend-arena@a3` drains it: no new player or match
is sent there, the public rooms stop and pay what their players are owed, made matches are
allowed to finish, up to 11 minutes ([01-arena §8.6](../detailed-design/01-arena.md)). Its
entry lapses from the directory; then close its port.

### 2.5 The limit of one arena process

An arena has two network threads (Netty's event loops, fixed in the code) for every
connection's TLS, framing and writes. On 2026-10-06 they used **0.52 cores for 600 players**,
three quarters of it the kernel's; at that rate the two are full near **2 300 players**,
whatever `MAX_ROOMS` says. The 2026-09-30 load run put it near 2 100. So beyond about 15 rooms
of 150, add arenas, not rooms. Raising the thread count is a code change, not made or
measured.

### 2.6 Checked 2026-10-06: two arenas, two ports

Two arenas on one machine, 9021 and 9022, each 2 rooms of 150 (300 places), and the whole
stack from the release. A wave of 200 bots went to one arena; 30 seconds later, a second wave
of 200 went to the other, the emptier: both ended with 200 players, none refused. Scaling out by
port works as designed. The arenas were started by hand; the unit steps of §2.4 follow the
template every arena uses (`check-units-el9.sh` starts `backend-arena@a1` so) but were not run
as written for a second instance.

### 2.7 Known limit: a burst lands on one arena (T-59)

The allocator counts an arena's free places from its last announcement, and a ticket counts
only once its player has joined and the arena has announced again. Requests that arrive
between two announcements all go to the same arena. On 2026-10-06, 400 bots took their
tickets in 20 seconds before any of them joined: all 400 were sent to one arena of 300
places, and **100 were refused (`Kick` 2, no room) while the other arena stood empty**. Real
clients join within a second of their ticket, so the window is about the 3-second
announcement interval; a burst of logins (after a deploy, at an event's start) can still pile
onto one arena. Until it is fixed ([T-59](../defects.md#4-concurrency): count the tickets an
arena has been given and not yet claimed, as D-42 does for made matches' rooms), leave every
arena room to spare, and roll arenas out of service one at a time.

## 3. The lobby, the API, the results, the data

### 3.1 nginx, gateway and platform: one of each per machine

Each machine runs one nginx, one gateway and one platform; clients are given every machine's
public name and try the next when one fails ([01 §4](01-deploy.md#4-nginx)). More machines are
more of all three. The platform holds no state of its own; the gateway holds its lobby
connections, which reconnect elsewhere when it goes.

- **Logins** are the platform's heaviest work: Argon2id at 62 to 88 ms a hash, 8 at a time, 160
  more waiting, then 503 `busy`. Onboarding bots measured **57 logins a second** a process
  (2026-09-30). A storm of 3 000 logins at once was all served in 47 seconds, none given up
  (2026-09-25, S-7). Sessions last a day ± 10 %, so a login herd spreads over hours.
- **The matcher** runs on one platform at a time (a 5-second lease in the store), a round a
  second: 5 000 queued entries of one to three players took **100 to 180 ms** a round
  (2026-09-29). More platforms add API capacity, not matching capacity.
- **The gateway** forwards lobby requests to its own machine's platform. Its capacity is
  designed (~17 000 connections, ~8 KB each idle, a 3 GB heap) and **not measured**.

### 3.2 Workers

Results reach MySQL through the `events` store's stream, read by the `rewards` group: each
`backend-worker@<id>` is a consumer, and more consumers apply more results a second. One
worker drained a backlog at **9.6 to 16.3 results a second**, two at 18 to 27, four at 25 to
36 (2026-09-30); the transaction alone, from `ApplyBenchmark`, **18–19 a second from one
thread, 104 from eight**, bound by the disk's synchronous commit (6 ms here). The design
expects ~170 results a second at 50 000 players: ten or more workers on these figures, which
Q-3 is to settle on the real disks. Watch `backend_worker_queue_depth`: growing for minutes
means add a worker.

### 3.3 MySQL

One primary takes every write; the replica on C is for failover and the backups, not for
reads. It grows by a bigger machine. Mind `max_connections` (151): each platform holds a pool
of 16, each worker 8, plus the backups and whoever investigates; 3 platforms and 13 workers
are 152. Raise it with the processes (`backend.cnf`), with memory to match.

Measured: a result's transaction 35 ms alone (2026-09-30); the retention purge **11 600 rows a
second**, about 21 minutes a day at the design load; a logical restore **24 000 rows a
second**; the binary-log copy 17 ms behind (2026-10-04, 2026-09-26).

### 3.4 j-redis

Two instances, `session` and `events`, each one primary with a replica on another machine for
failover ([D-7](../architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)). Each
executes commands on one thread, so it grows by a faster core and more memory, not by more
instances. **The `events` store keeps a day of results** (~530 bytes each): 1.6 GB at 10 000
players, 7.8 GB at 50 000. The shipped `maxmemory` is 3 GB, of an 8 GB heap: enough to about
**19 000 players** (O-37). Before then, raise its heap and `maxmemory` together
(`store-events.env`, `store-events.conf`, about 40 % of the heap) or shorten the retention.

## 4. Performance: what is measured

### 4.1 An arena under load (2026-10-06)

One arena of 4 rooms, the whole stack from the release, bots on the same machine sending what
a client sends (10 inputs and a ping every 10 s), level-1 rooms on the 5 700 map, load average
1.7 to 6. Each step ran 75 seconds; the arena's CPU is over 30 seconds of it, by thread.

| Bots (rooms) | Arena, all | Network threads | Room threads | Dropped, kicked | Per bot, down |
|---|---|---|---|---|---|
| 150 (1) | 0.36 cores | 0.17 (kernel 0.13) | 0.11 | 0, 0 | 14.89 snapshots/s, 82.5 B, 1.20 KB/s |
| 300 (2) | 0.49 | 0.25 (kernel 0.20) | 0.20 | 0, 0 | 14.90/s, 81.8 B, 1.19 KB/s |
| 600 (4) | 0.96 | 0.52 (kernel 0.41) | 0.39 | 0, 0 | 14.82/s, 83.3 B, 1.21 KB/s |

A full room costs about **0.24 cores**; the 2026-09-30 run, whose bots sent twice the inputs
(T-54), had put it at 0.26. Both are upper bounds: over loopback the kernel does the receiving
end's work in the sender's time too.

### 4.2 A room's CPU, without the network (`TickBenchmark`, 2026-10-06)

150 tanks, 1 500 shapes, 20 000 ticks, generational ZGC, on a quiet machine (load 0.5 to 1.9).

| Room | Simulation p50 / p99 | Encoding a round of 150 p50 | One room | Down a player |
|---|---|---|---|---|
| 5 700 map, a room grown to level 45 (the worst case) | 1.72 / **3.11 ms** | 4.61 ms | 11.6 % of a core, 9 a core | 97 B frames, 8.7 MB/hour with overhead |
| 5 700 map, level 1 | 0.71 / 1.26 ms | 3.25 ms | 6.9 %, 15 a core | 84 B, 8.0 MB/hour |
| 22 000 map (the default), level 1 | 0.59 / 1.19 ms | 1.01 ms | 3.1 %, 32 a core | 30 B |

A mature room at the design density is **over NFR-1a's 2 ms p99** for the simulation: known
since the class tree (2026-09-28: 2.9 ms, then 3.8–4.2), and part of Q-3. Earlier tick figures
are not comparable with these: T-53 changed the benchmark's room on 2026-10-04.

### 4.3 A tick in a running arena, wall clock

The latest two-hour soak, 300 bots (2026-10-04): the whole tick's p99 **18.8 and 19.6 ms**,
one overrun, against NFR-1b's 15 ms and the 40 ms deadline, with the machine at load 12 on 12
cores. Five soaks since 2026-10-01 ranged 15 to 73 ms with the machine's load, not the code's;
each passed its 29 or 30 rules, every stay paid. This VM cannot judge NFR-1b.

### 4.4 Data a player

Measured per connection with 150 bots (2026-10-03, after the own tank's events): 1 242 bytes a
second down on average, p95 ~1 475; today's bots, 1.20 KB/s of payload at 14.9 snapshots a
second. With packet overhead and input that is about 10 to 12 MB an hour, under NFR-2's 15.

### 4.5 Platform, worker, MySQL

| What | Number | When |
|---|---|---|
| Argon2id a password | 62–88 ms | before 2026-09-23 |
| Logins a process, onboarding | ~57 a second | 2026-09-30 |
| 3 000 logins at once | all in 47 s, 0 given up | 2026-09-25 |
| Matcher, 5 000 entries | 100–180 ms a round | 2026-09-29 |
| A rating board's place | 14–18 ms, top to bottom, 200 000 accounts | 2026-10-02 |
| A worker | 9.6–16.3 results a second | 2026-09-30 |
| `ApplyBenchmark` | 18–19 a second from 1 thread, 104 from 8 | 2026-09-30 |
| Retention purge | 11 600 rows a second | 2026-10-04 |
| Restore | 24 000 rows a second | 2026-10-04 |

Not measured: the API's latency by route and the queues' waits (the histograms exist, no
value has been recorded), and the gateway's capacity.

### 4.6 j-redis

Its own benchmark on this VM gave 25 000 to 209 000 commands a second depending on pipelining
and connections, which its README says is the VM's thread wake-ups, not the engine:
"Measure on the production machine before believing any of these." Writing a large key during
a rewrite holds the command thread 20–30 ms (100 000 members) to 120–250 ms (1 000 000).

## 5. What 50 000 players would take, on these numbers

| | At 10 000 (the launch, two machines) | At 50 000 (the design, three) |
|---|---|---|
| Rooms of 150 | 67 | 333 |
| Arena CPU at 0.24 cores a room | ~16 cores | ~80 cores, more as rooms mature |
| Arena processes at ~2 300 players each | 5 | 22, against the design's 7 |
| `events` store, a day of results | 1.6 GB, inside 3 GB | 7.8 GB: raise it (O-37) |
| Results a second | ~34: three or four workers | ~170: ten or more |
| Downstream | ~0.2 Gbit/s | ~1.1 Gbit/s: 10 GbE |

The launch fits these machines on every figure here. 50 000 does not, on the VM's figures:
80 arena cores against 72 in three machines, and three times the arena processes the design
names. Both are upper bounds measured over loopback, and the real network cards should cost
less; whether 50 000 fits is Q-3's to settle on the production hardware, with the bots on
another machine.

## 6. When to add capacity

| Signal | Metric | Act when |
|---|---|---|
| Rooms falling behind | `backend_arena_tick_p99_seconds{room}` | above 0.015 for five minutes: add arenas (or machines) |
| Arenas full | `GET /admin/arenas`: players against `maxPlayers`; `no_arena` and `no_room` refusals | free places fall below a burst's worth (T-59): add an arena |
| Logins turned away | `backend_platform_hasher_line` | at 168: a platform (a machine) more |
| Results behind | `backend_worker_queue_depth` | growing for minutes: add a worker |
| Retention behind | `backend_worker_retention_seconds` | above 1 800 |
| Store memory | j-redis `INFO`: `used_memory` over `maxmemory` | above 80 %: a bigger heap (§3.4) |
| MySQL connections | `Threads_connected` against `max_connections` | above 80 % |
| Data a player | `backend_arena_connection_bytes_per_second{profile}` | p95 drifting toward NFR-2's 15 MB/hour |

No scraper or alert rules ship yet ([runbook §5](02-runbook.md#5-what-to-watch)); these are
what they should say.

## 7. Measuring again

```bash
# A room's CPU (from backend/): the default map; the design density; a mature room
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar 150 1500 20000 5700 1 0
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar 150 1500 20000 5700 45 0

# An arena under bots (from the repository's root), against a running stack: N bots for S seconds,
# each with an address of its own, so the login limit does not apply
java -cp backend/tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.BotClient 600 75 \
    http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
# while it runs, the arena's CPU by thread: /proc/<pid>/task/*/stat over a window (utime, stime)

# Results, purges and boards: ApplyBenchmark, PurgeBenchmark, RankBenchmark (CLAUDE.md's commands)
```

The bots cost about a core a thousand themselves; on one machine they compete with what they
measure, which is why the real figures need them on another machine.
