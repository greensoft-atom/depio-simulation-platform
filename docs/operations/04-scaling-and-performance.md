# Scaling and performance

This page is for operators who plan capacity. It says how each part of the backend grows, how to add capacity to
each part step by step, what each part costs as measured, and what is still unproven. It also says which signals
tell you that it is time to grow (§6). The design's own reasoning is in
[architecture/01](../architecture/01-system-topology.md) and [07](../detailed-design/07-threading-and-performance.md);
the words arena, room, store and worker are defined in the [glossary](../glossary.md#1-processes-and-places).

**Where the numbers come from.** Every measurement here was taken on the shared development VM (12 cores, Ubuntu
24.04, with other users' jobs on it). Every process, the bots, the stores and MySQL ran on that one machine, over
loopback. **Nothing has been measured on the production hardware**
([Q-3](../requirements/01-scope-and-nfrs.md#7-open-questions)). CPU costs carry over to other machines. Wall-clock
latencies, such as a tick's p99, are this VM's, and they move with its load. The numbers dated 2026-10-06 were
measured for this page, on the build of that day; the older ones are cited with their date and source.

## 1. The shape, at a glance

| Part | Holds state | Grows with | Add capacity by | Limit as built | Cost, measured |
|---|---|---|---|---|---|
| `arena` | its rooms (a match lives in one process) | players in battle | rooms per arena, arenas per machine (a private port and a name each, behind the front: [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)), machines | about **2 300 players a process**: its two network threads (§2.5) | **0.24 cores a full room** of 150 (0.13 network, 0.10 simulation), level-1 rooms; more as rooms mature (2026-10-06) |
| `gateway` | its lobby connections | players connected | one per machine, all three behind the front's nginx, which gives each new connection to the gateway holding the fewest | ~17 000 connections a gateway by design; **not measured** | 14 MB live heap in a 300-bot soak (2026-10-01) |
| `platform` | none (the store and MySQL hold it) | logins, API calls | one per machine, all three behind the front's nginx | **not measured** since D-117: a login is an ES256 signature checked, the password hashing being the account service's | about 2 ms of a core a token taken, an upper figure (2026-11-26, [backend/README](../../backend/README.md#login-and-what-it-costs)) |
| account service (D-117) | none (its MySQL database holds it) | password logins and sign-ups | one per machine, all three behind the front's nginx (`/account/v1/`) | password hashing, 8 at a time: **~57 logins a second** a process, measured in the platform before the hashing moved, the same code | 62–88 ms a hash |
| `worker` | none (a consumer group) | match results a second | `backend-worker@<id>` instances | MySQL's commit | **10–16 results a second** a worker (2026-09-30) |
| j-redis `session` | sessions, tickets, the arena directory, queues, parties, boards | everything | a bigger machine (one primary; its replica, with two machines or more, is for failover) | one command thread; `maxmemory` 3 GB | not measured trustworthily on this VM (§4.6) |
| j-redis `events` | the result stream, a day of it | results a second | a bigger heap | `maxmemory` 3 GB holds a day of results up to **~19 000 players** (O-37) | ~530 bytes a result |
| MySQL | the system of record | results, purchases, logins | a bigger machine (one primary; its replica, with two machines or more, is for failover and backups) | `max_connections` 151 | 35 ms a result alone; 104 a second from 8 threads |
| nginx | none | the API, the lobby and match traffic, all of it on the front: TLS on 443 and 9001, in clear on 80 (D-115) | one, the front's; every machine's set up the same, so another can take over | `worker_connections` 16 384 a worker, two for each connection it passes on; 262 144 descriptors | **not measured**: TLS for every match byte, about 1.5 Gbit/s each way at 50 000 in battle (D-82) |

Match traffic never passes through the gateway. On the site it passes through the front's nginx, which ends its TLS
and hands the bytes on, unread, to the arena that the grant's `tlsName` names
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway), as
[D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)
revised it). A client without TLS sends its match through nginx's 80 instead, by the same name (`/match/<name>`,
[D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).

## 2. The match tier: arenas

### 2.1 How players are placed

- **Each arena announces itself** to the `session` store every 3 seconds: its name, the `host:port` clients dial, its
  players and free places, and its rooms and free rooms. An entry not renewed for 10 seconds lapses
  ([04 §3](../detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets)).
- **A seat in the public arena** (`POST /v1/match-requests`): the platform picks the live arena with the **most free
  places** (`MAX_ROOMS × MAX_PLAYERS − players`), less the seats it has promised there in the last minute that the
  arena has not counted yet. It promises one more seat, writes a ticket, and answers with that arena's address.
  Without the promises, every request between two announcements went to the same arena (§2.7,
  [D-79](../architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)).
- **A made match** (a duel, a team match, a tournament round, a sandbox): the matcher picks the arena with the **most
  free rooms**, and promises the room in the store, so that two matches chosen at once do not both take the last one
  ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
- **Inside an arena**, a public player goes to the **fullest room still below 145** of 150 (`MAX_PLAYERS − 5`). A new
  room opens only when every open room is that full, up to `MAX_ROOMS`; past that, the player goes to any room with a
  place. So rooms fill one after another, and players meet, rather than spreading thin.

**The allocator is the load balancer.** No load balancer stands in front of the arenas. A room lives in exactly one
process, and only the allocator knows which one, so a proxy could not route a player; it would also add a hop to the
traffic that can least afford one (about 1.1 Gbit/s at full load). The front's nginx does not choose an arena either:
it only follows the name the grant gave.

### 2.2 One port per arena, many connections per port

A TCP connection is told apart by both ends' addresses and ports, so one listening port takes as many connections
as the process can serve. The limits are CPU, the network and file descriptors (the unit's `LimitNOFILE=65536`).

Each arena instance has its own port (`ARENA_PORT`, 9011 for a machine's first) on the machine's private address,
and its own metrics port on `127.0.0.1`. On the launch's one machine the arena's port is on `127.0.0.1` too (03 §8).
Behind the front
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)),
every arena announces the site's address and 9001, and a name of its own, `BACKEND_ADVERTISE_TLS_NAME`. The front's
nginx passes each connection to the arena by that name.

The design has seven arenas: `a1` and `a2` on A, `b1` to `b3` on B, `c1` and `c2` on C. The launch's one machine has
three, `a1` to `a3`. Each is a `backend-arena@<name>` unit with its own `/etc/backend/arena-<name>.env`
([05 §3](05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)).

### 2.3 Settings that size an arena

| Setting (`arena-<name>.env`) | Example | What it does |
|---|---|---|
| `ARENA_PORT` | 9011 | the private port the front's nginx reaches it on; each instance its own |
| `MAX_ROOMS` | 12 | rooms in this process, public and made together; each room a thread. 12 of 150 are 1 800 players, short of the network threads' lower measured limit, 2 100 (§2.5) |
| `MAX_PLAYERS` | 150 | players a public room |
| `MAP_SIZE`, `SHAPES` | 5 700, 1 500 | the room's world; the density the bandwidth budget assumes |
| `ADVERTISE_HOST`, `BACKEND_ADVERTISE_PORT` | `203.0.113.10`, 9001 | the site's public address and port, what clients dial |
| `BACKEND_ADVERTISE_TLS_NAME` | `a1.arena.internal` | the name the client gives, which nginx routes by and the certificate names |
| `BACKEND_METRICS_ADDR` | `127.0.0.1:9110` | each instance its own |
| (unit) heap | `-Xms6g -Xmx6g` | one arena's live heap is ~43 MB at full rooms (2026-10-01): the heap is for headroom and ZGC. On the launch's one machine, 4 GB, by a drop-in (03 §8) |

### 2.4 Adding capacity

**More rooms in an arena.** Raise `MAX_ROOMS` and restart the arena (a rolling restart:
[01 §5](01-deploy.md#5-rolling-deployment)). Each full room costs about 0.24 cores (§4.1). Stop well short of the
network threads' limit (§2.5).

**Another arena on a machine**, for example `a3` beside `a1` and `a2` on A of the three-machine layout. It needs no
public port: players reach it through 9001, or through 80 without TLS, by its name. Five steps, in this order:

1. Its settings, on A, and its private port in A's `backend` zone:

   ```console
   cp /etc/backend/arena-a1.env /etc/backend/arena-a3.env
   sed -i -e 's/^ARENA_PORT=.*/ARENA_PORT=9013/' \
          -e 's/^BACKEND_ADVERTISE_TLS_NAME=.*/BACKEND_ADVERTISE_TLS_NAME=a3.arena.internal/' \
          -e 's/^BACKEND_METRICS_ADDR=.*/BACKEND_METRICS_ADDR=127.0.0.1:9113/' /etc/backend/arena-a3.env
   firewall-cmd --permanent --zone=backend --add-port=9013/tcp && firewall-cmd --reload
   ```

2. Its line in nginx's list of arenas, `backend-arenas.conf`, on every machine, then a reload. Both of nginx's maps,
   9001's and 80's, read that list (D-115):

   ```console
   echo 'a3.arena.internal  10.0.0.1:9013;' >> /etc/backend/nginx/backend-arenas.conf
   systemctl reload backend-nginx
   ```

3. The site's certificate, issued again with the new name (`private-ca.sh names` reads the list) and installed on
   every machine ([06 §3, §4](06-certificates.md#3-issuing-the-sites-certificate)). Until then, a client with TLS
   refuses the new arena.
4. The arena started: `systemctl enable --now backend-arena@a3`.
5. Its metrics in A's watch: `arena=127.0.0.1:9113` added to `BACKEND_WATCH_TARGETS` in A's `platform.env`, then
   `systemctl restart backend-platform`.

On the launch's one machine, which has `a1` to `a3` already, the steps are the same with these differences: the
arena listens on `127.0.0.1` (03 §8), its line in the list names `127.0.0.1` and its port, and the firewall needs no
change, since loopback is not filtered (05 §1).

**Check that it is live**: `GET /admin/arenas` lists `a3` within a few seconds ([api/03](../api/03-admin-api.md)),
and the platform starts sending players to it at once.

- **Pick a metrics port that no other process on the machine uses.** The examples give the platform 9101, the
  gateway 9103, the workers 9102 and 9112, and the arenas 9110 onwards, numbered by the arena's number on its machine
  ([05 §3](05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)).
- **A drop-in under `backend-arena@.service.d/`** (the heap on a small machine, for example) applies to every
  instance, the new one included.

**Another machine.** Install it as the [install guide](03-install-guide.md) does, with the store and MySQL addresses
of the existing machines (the guide's "Three machines" section). Then:
1. name its arenas for it (`d1`, for example). **An arena's name must be unique across all the machines**, or two
   arenas overwrite each other's entry in the arena directory;
2. add each of its arenas' TLS names to every machine's `backend-arenas.conf`, and to the site's certificate (05 §3,
   06 §7);
3. add the machine's address as a source in every machine's `backend` zone (05 §5);
4. add its platform and gateway to every machine's `backend-upstreams.conf`, then reload nginx;
5. add its address to `BACKEND_WATCH_PEERS`, and to every `BACKEND_TRUSTED_PROXIES` if it may ever be the front.

Its arenas register in the same store, and are used at once.

**Taking an arena out.** `systemctl stop backend-arena@a2` drains it: no new player or match is sent there, the
public rooms stop and pay what their players are owed, and made matches are allowed to finish, for up to 11 minutes
([01-arena §8.6](../detailed-design/01-arena.md)). Its entry lapses from the arena directory. Then take its line out
of `backend-arenas.conf` on every machine, and its port out of the `backend` zone.

### 2.5 The limit of one arena process

An arena has two network threads (Netty's event loops; the number is fixed in the code). They do every connection's
TLS, framing and writes. On 2026-10-06 they used **0.52 cores for 600 players**, three quarters of it in the kernel.
At that rate the two threads are full near **2 300 players**, whatever `MAX_ROOMS` says; the 2026-09-30 load run put
the limit near 2 100. So beyond about 15 rooms of 150, add arenas, not rooms. Raising the thread count is a code
change, which has not been made or measured. Behind the front the arena speaks plaintext, as nginx does the TLS, so
its threads only frame and write; how much that saves was not measured.

### 2.6 Checked 2026-10-06: two arenas, two ports

The check ran two arenas on one machine, on 9021 and 9022, each with 2 rooms of 150 (300 places), and the whole
stack from the release. A wave of 200 bots went to one arena; 30 seconds later, a second wave of 200 went to the
other, the emptier one. Both ended with 200 players, and none was refused. Scaling out by port works as designed. The
arenas were started by hand. The unit steps of §2.4 follow the template every arena uses (`check-units-el9.sh` starts
`backend-arena@a1` that way), but they were not run as written for a second instance.

### 2.7 A burst of seats, spread (T-59, fixed 2026-10-06)

The allocator used to count an arena's free places only as the arena announced them, and a ticket counted only once
its player had joined and the arena had announced again. So all the requests between two announcements went to the
same arena. On 2026-10-06, 400 bots took their tickets in 20 seconds, before any of them joined: **all 400 were sent
to one arena of 300 places, and 100 were refused (`Kick` 2, no room) while the other arena stood empty.**

Now each seat given is promised to its arena in the store, until the arena counts the player or a minute passes
([D-79](../architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)).
The same run, on the release with the fix:

| | Before (item 84) | After (item 85) |
|---|---|---|
| Players per arena | 300 and 0 | **200 and 200** |
| Welcomed | 300 of 400 | **400 of 400** |
| Refused, no room | 100 | **0** |

The seats promised were read every few seconds during that run. They rose to 166 and 180 while the tickets were
given, reached 200 in each arena, and fell to 0 with the announcement that counted each arena's 200 players.

What it means for an operator:
- **A ticket holds its place for up to a minute** if its player never comes. A burst of abandoned requests can make
  the arenas look full for that minute. The platform then answers 503 `no_arena` at once, where before it sent the
  players on, to be refused at the arena's door.
- **Upgrade the arenas before the platform.** An arena of an older release does not drop promises, so it looks
  fuller than it is, and is sent fewer players until the promises lapse.

## 3. The lobby, the API, the results, the data

### 3.1 nginx, gateway and platform: one of each per machine

Each machine runs one nginx, one gateway and one platform. Clients hold the site's one public address. The front's
nginx passes each request and each lobby connection to the platforms and gateways of all three machines, and passes
over one that fails ([01 §4](01-deploy.md#4-nginx), D-82). More machines means more of all three. The platform holds
no state of its own. The gateway holds its lobby connections, which reconnect to another gateway when it goes.

- **Logins** were the platform's heaviest work: an Argon2id hash takes 62 to 88 ms, 8 run at a time, and 160 more may
  wait; past that, the answer is 503 `busy`. Onboarding bots measured **57 logins a second** a process
  (2026-09-30). A storm of 3 000 logins at once was all served in 47 seconds, and none gave up (2026-09-25, S-7).
  Sessions last a day ± 10 %, so a herd of logins spreads out over hours. **Since D-117** the account service hashes
  the passwords, by the same policy and line ([10 §3.3](../detailed-design/10-accounts.md#33-passwords-and-limits));
  these figures were measured on the platform before the move, and the account service's own are not measured yet.
  The platform's part of a login is a signature check and a lookup.
- **The matcher** runs on one platform at a time (it holds a 5-second lease in the store), one round a second.
  5 000 queued entries of one to three players took **100 to 180 ms** a round (2026-09-29). More platforms add API
  capacity, not matching capacity.
- **The gateway** forwards lobby requests to its own machine's platform. Its capacity is designed (about 17 000
  connections, about 8 KB each when idle, a 3 GB heap) and **not measured**.

### 3.2 Workers

Results reach MySQL through the `events` store's stream, which the consumer group `rewards` reads. Each
`backend-worker@<id>` is one consumer, and more consumers apply more results a second. Measured on 2026-09-30:

| Workers | Results a second, draining a backlog |
|---|---|
| 1 | **9.6 to 16.3** |
| 2 | 18 to 27 |
| 4 | 25 to 36 |

The transaction alone, from `ApplyBenchmark`, ran **18–19 a second from one thread, and 104 from eight**, bound by the
disk's synchronous commit (6 ms here). The design expects about 170 results a second at 50 000 players: ten or more
workers on these figures, which Q-3 is to settle on the real disks. Watch `backend_worker_queue_depth`: if it grows
for minutes, add a worker (the watch's `ResultsBacklogGrowing`, §6).

### 3.3 MySQL

One primary takes every write. The replica (on C with three machines, on B with two; none on the launch's one
machine) is for failover and the backups, not for reads. MySQL grows by a bigger machine. Mind `max_connections`
(151): each platform holds a pool of 16 connections and each worker 8 (`BACKEND_DB_POOL_SIZE`), plus the backups and
whoever investigates. So 3 platforms and 13 workers would need 152. Raise it with the processes (in `backend.cnf`),
with memory to match.

Measured: a result's transaction alone, 35 ms (2026-09-30); the retention purge, **11 600 rows a second**, about 21
minutes a day at the design load; a logical restore, **24 000 rows a second**; the binary-log copy, 17 ms behind
(2026-10-04, 2026-09-26).

### 3.4 j-redis

There are two instances, `session` and `events`. With more than one machine, each is one primary with a replica on
another machine, for failover ([D-7](../architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)). Each
runs commands on one thread, so it grows by a faster core and more memory, not by more instances.

**The `events` store keeps a day of results**, about 530 bytes each: 1.6 GB at 10 000 players, 7.8 GB at 50 000. The
shipped `maxmemory` is 3 GB, of an 8 GB heap: enough for about **19 000 players**, and its 80 % mark comes at about
15 000 (O-37). Read the store's memory by hand: no watch rule reads it (§6). Both numbers are settings
([D-83](../architecture/03-decision-log.md#d-83--the-result-streams-retention-is-a-setting-and-the-events-store-is-sized-by-a-table)):

- **the size**: `STORE_HEAP` in `/etc/backend/store-events.env`, and `maxmemory` in `/etc/backend/store-events.conf`
  at about 40 % of the heap. With three machines, on B (the primary) and on C (its replica);
- **the hours kept**: `BACKEND_RESULT_RETENTION_HOURS`, from 1 to 168, 24 by default, in every `arena-<name>.env` and
  in `worker.env`. Use the same value everywhere: every add to the stream trims what is older, so the shortest value
  wins. Each arena and worker logs the value when it starts: `the result stream kept 24 h`.

**What a number of players needs**: about 160 MB of results a day for every 1 000 players, with a fifth to spare
under the 80 % mark, and a heap of 2.5 times `maxmemory` for ZGC. This is arithmetic from the measured size of a
result, not a measurement of the production load:

| Players | Hours kept | Results held | `maxmemory` | `STORE_HEAP` | Fits B and C at 64 GB |
|---|---|---|---|---|---|
| 5 000 | 24 | 0.8 GB | `1600mb`, the launch's one machine (D-93) | `4g` | one machine at 64 GB: yes |
| 10 000 | 24 | 1.6 GB | `3gb`, as shipped | `8g`, as shipped | yes |
| 15 000 | 24 | 2.4 GB | `3gb`: its 80 % mark | `8g` | yes |
| 20 000 | 24 | 3.2 GB | `4gb` | `10g` | yes, closely |
| 30 000 | 24 | 4.8 GB | `6gb` | `15g` | no |
| 50 000 | 24 | 7.8 GB | `10gb` | `25g` | no: 96 GB would |
| 25 000 | 12 | 2.0 GB | `3gb` | `8g` | yes |
| 50 000 | 6 | 2.0 GB | `3gb` | `8g` | yes |

Why "fits" is close at 20 000: on C the arenas (12 GB), the platform and the gateway (6 GB), two workers (3 GB),
MySQL's replica (about 20 GB) and the backups' scratch server (about 5 GB) take some 46 GB before the `events`
replica, and each JVM uses more memory than its heap. Which way to go past 20 000 players, more memory or fewer hours,
is the owner's decision: [Q-58](../requirements/01-scope-and-nfrs.md#7-open-questions).

**The hours are also how long the workers or MySQL may be down before results are lost.** Results trimmed before a
worker applied them are counted by `backend_worker_trimmed_unapplied_total`. With 6 hours of retention, an outage of
6 hours costs results.

**Changing them:**
1. **The size**: with three machines, change the replica's files first and run
   `systemctl restart backend-store@events` on C, then do the same on B. While B's store restarts, the arenas keep
   their results in their spools, and send them when it is back. On the launch's one machine there is no replica:
   change both files there and restart its `events` store.
2. **The hours**: put the same value in every `arena-<name>.env` and in `worker.env`. Then restart the arenas one at
   a time ([runbook §6](02-runbook.md#6-routine)), and the workers. A shorter value takes effect for the whole stream
   at the first restarted process's next add. A longer value takes effect only once every process has it, and what
   was trimmed meanwhile does not come back.

## 4. Performance: what is measured

### 4.1 An arena under load (2026-10-06)

One arena of 4 rooms ran with the whole stack from the release. Bots on the same machine sent what a client sends:
10 inputs a second, and a ping every 10 s. The rooms were level 1, on the 5 700 map, and the machine's load average
was 1.7 to 6. Each step ran for 75 seconds; the arena's CPU, by thread, was measured over 30 seconds of it.

| Bots (rooms) | Arena, all | Network threads | Room threads | Dropped, kicked | Per bot, down |
|---|---|---|---|---|---|
| 150 (1) | 0.36 cores | 0.17 (kernel 0.13) | 0.11 | 0, 0 | 14.89 snapshots/s, 82.5 B, 1.20 KB/s |
| 300 (2) | 0.49 | 0.25 (kernel 0.20) | 0.20 | 0, 0 | 14.90/s, 81.8 B, 1.19 KB/s |
| 600 (4) | 0.96 | 0.52 (kernel 0.41) | 0.39 | 0, 0 | 14.82/s, 83.3 B, 1.21 KB/s |

A full room costs about **0.24 cores**. The 2026-09-30 run, whose bots sent twice the inputs (T-54), had put it at
0.26. Both are upper bounds: over loopback, the kernel does the receiving end's work in the sender's time too.

### 4.2 A room's CPU, without the network (`TickBenchmark`, 2026-10-06)

150 tanks, 1 500 shapes, 20 000 ticks, generational ZGC, on a quiet machine (load 0.5 to 1.9).

| Room | Simulation p50 / p99 | Encoding a round of 150 p50 | One room | Down a player |
|---|---|---|---|---|
| 5 700 map, a room grown to level 45 (the worst case) | 1.72 / **3.11 ms** | 4.61 ms | 11.6 % of a core, 9 a core | 97 B frames, 8.7 MB/hour with overhead |
| 5 700 map, level 1 | 0.71 / 1.26 ms | 3.25 ms | 6.9 %, 15 a core | 84 B, 8.0 MB/hour |
| 22 000 map (the default), level 1 | 0.59 / 1.19 ms | 1.01 ms | 3.1 %, 32 a core | 30 B |

A mature room at the design density is **over NFR-1a's 2 ms p99** for the simulation. That has been known since the
class tree was added (2026-09-28: 2.9 ms, then 3.8–4.2), and it is part of Q-3. Earlier tick figures cannot be
compared with these: T-53 changed the benchmark's room on 2026-10-04.

### 4.3 A tick in a running arena, wall clock

The latest two-hour soak, with 300 bots (2026-10-04), gave a whole tick's p99 of **18.8 and 19.6 ms**, with one
overrun, against NFR-1b's 15 ms and the 40 ms deadline; the machine was at load 12 on 12 cores. Five soaks since
2026-10-01 ranged from 15 to 73 ms, moving with the machine's load, not with the code. Each passed its 29 or 30
rules, and every stay was paid. This VM cannot judge NFR-1b.

### 4.4 Data a player

Measured per connection with 150 bots (2026-10-03, after the own tank's events were added): 1 242 bytes a second
down on average, p95 about 1 475. The bots of 2026-10-06 (§4.1) received 1.20 KB/s of payload at 14.9 snapshots a
second. With packet overhead and the input sent up, that is about 10 to 12 MB an hour, under NFR-2's 15.

### 4.5 Platform, worker, MySQL

| What | Number | When |
|---|---|---|
| Argon2id a password (the platform's, before D-117) | 62–88 ms | before 2026-09-23 |
| Logins a process, onboarding (the platform's, before D-117) | ~57 a second | 2026-09-30 |
| 3 000 logins at once (the platform's, before D-117) | all in 47 s, 0 given up | 2026-09-25 |
| Matcher, 5 000 entries | 100–180 ms a round | 2026-09-29 |
| A rating board's place | 14–18 ms, top to bottom, 200 000 accounts | 2026-10-02 |
| A worker | 9.6–16.3 results a second | 2026-09-30 |
| `ApplyBenchmark` | 18–19 a second from 1 thread, 104 from 8 | 2026-09-30 |
| Retention purge | 11 600 rows a second | 2026-10-04 |
| Restore | 24 000 rows a second | 2026-10-04 |

Not measured: the API's latency by route and the queues' waits (the histograms exist, but no value has been
recorded), and the gateway's capacity.

### 4.6 j-redis

j-redis's own benchmark on this VM gave 25 000 to 209 000 commands a second, depending on pipelining and
connections. Its README says the spread is the VM's thread wake-ups, not the engine: "Measure on the production
machine before believing any of these." Writing a large key during a rewrite of the store's log holds the command
thread for 20–30 ms (100 000 members) to 120–250 ms (1 000 000).

## 5. What 50 000 players would take, on these numbers

| | At 5 000 (the launch, one machine) | At 10 000 | At 50 000 (the design, three) |
|---|---|---|---|
| Rooms of 150 | 34 | 67 | 333 |
| Arena CPU at 0.24 cores a room | ~8 cores | ~16 cores | ~80 cores, more as rooms mature |
| Arena processes at ~2 300 players each | 3, set for 1 800 each (5 400 places) | 5 | 22, against the design's 7 |
| `events` store, a day of results | 0.8 GB, inside one machine's 1.6 GB | 1.6 GB, inside 3 GB | 7.8 GB: raise it (O-37) |
| Results a second | ~17: two workers | ~34: three or four workers | ~170: ten or more |
| Downstream | ~0.1 Gbit/s | ~0.2 Gbit/s | ~1.1 Gbit/s: 10 GbE |

The launch fits its one machine, 32 cores and 64 GB
([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)), on
every figure here, and 10 000 players fit three machines. 50 000 does not fit, on the VM's figures: 80 arena cores
against 72 in three machines, and three times the arena processes the design names. Both are upper bounds measured
over loopback, and real network cards should cost less. Whether 50 000 fits is for Q-3 to settle on the production
hardware, with the bots on another machine.

## 6. When to add capacity

**On the launch's one machine**, the first answer to most of the signals below is the second machine (plan item
115): first made the standby ([10](10-two-machines.md)), then given arenas of its own
([10 §10](10-two-machines.md#10-arenas-on-b), D-97). The one machine carries:
- 5 400 players in battle: its three arenas of 1 800 places each;
- 18 to 27 results a second: its two workers, as measured (§3.2);
- 1.6 GB of `maxmemory` in each store (03 §8).

Past about 4 000 players in battle, or when the site needs a failover, it is time for the second machine.

| Signal | Metric | Act when | The watch's rule |
|---|---|---|---|
| Rooms falling behind | `backend_arena_tick_p99_seconds{room}` | above 0.015 for five minutes: add arenas (or machines) | `TickSlow` |
| Arenas full | `GET /admin/arenas`: players against `maxPlayers`; `no_arena` and `no_room` refusals | free places fall below a burst's worth (a place is held a minute for each ticket given, §2.7): add an arena | none: read by hand |
| Logins turned away | `account_hasher_line`, the account service's (D-117) | at 168: one more account service (a machine more) | `HasherLineFull` |
| Results behind | `backend_worker_queue_depth` | growing for minutes: add a worker | `ResultsBacklogGrowing`: over 100 and growing for ten minutes |
| Retention behind | `backend_worker_retention_seconds` | above 1 800 | `RetentionSlow` |
| Store memory | j-redis `INFO`: `used_memory` against `maxmemory` | above 80 %: a bigger heap (§3.4) | none: read by hand |
| MySQL connections | `Threads_connected` against `max_connections` | above 80 % | none: read by hand |
| Data a player | `backend_arena_connection_bytes_per_second{profile}` | p95 drifting toward NFR-2's 15 MB an hour | none: read by hand |

The watch is the backend's own monitoring, in every platform (D-86): its rules are the runbook's §5, in code
([08 §3](08-monitoring.md#3-the-alerts)). Four of the signals above have a rule of the watch, which raises an alert
by itself; read the other four by hand, at the thresholds the table gives.

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

The ports in the bots' command are a run by hand's (the platform on 8080, the gateway on 8081: 05 §8). Each tool and
its arguments are described in [backend/tools/README](../../backend/tools/README.md). The bots themselves cost about
one core per thousand bots. On one machine they compete with what they measure, which is why the real figures need
the bots on another machine.
