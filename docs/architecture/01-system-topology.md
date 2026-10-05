# System topology

How the backend is shaped: what processes exist, what runs where, how traffic
flows, and how the capacity budget is derived.

What it must achieve is in
[requirements](../requirements/01-scope-and-nfrs.md). Why each choice was made
is in the [decision log](03-decision-log.md). What happens when parts fail is
in [availability](02-availability.md).

## 1. Four process types

Split by **workload shape**, not by business domain.

A JVM already uses every core it is given — the arena runs one thread per room,
so 24 cores are busy without any service decomposition. Splitting by domain
(auth service, inventory service, guild service, each behind a network hop)
buys independent deployment and team boundaries. With one developer and three
machines neither applies, while the costs — latency, service discovery,
distributed transactions, tracing — are permanent ([D-4](03-decision-log.md#d-4--split-by-workload-shape-not-by-business-domain)).

| Process | Shape | Owns | Scales on | Instances |
|---|---|---|---|---|
| `arena` | CPU-bound, latency-critical, stateful | live rooms; never touches MySQL | players in battle | 7 (2–3 per machine) |
| `gateway` | IO-bound, many idle connections, thin | client lobby connections, auth handshake, routing | concurrent players | 3 (1 per machine) |
| `platform` | request/response, DB-bound | all platform modules | lobby traffic | 3 (1 per machine) |
| `worker` | throughput-bound, async | results, rewards, tournaments, analytics | event rate | 2, or as many as the result rate needs ([05 §1](../detailed-design/05-worker-and-events.md#1-why-a-stream-and-not-a-queue): about 10 results a second each on the development machine) |

Module boundaries **inside** `platform` stay strict (auth, player, team, rooms,
matchmaking, tournament, leaderboard, economy, items, social, notify —
see [detailed-design/04](../detailed-design/04-platform-services.md)). Splitting
`economy` into its own process later must be a build-file change, not a
rewrite. Split a module out when it demonstrably needs its own scaling or
failure profile — not on principle.

Several JVMs per machine remain right even with ZGC, for **crash isolation**:
one fatal error takes down one arena's rooms, not all of them (NFR-8).

## 2. Machine topology

```
                      ┌─ internet ─┐
                      │  10 GbE    │
       ┌──────────────┴────────────┴──────────────┐
       │                                          │
   match: client ──raw TCP+TLS──► arena           │  lobby: client ──WSS /lobby──► nginx ──► gateway ──► platform
   (direct, never proxied)                        │  API:   client ──HTTPS /v1/──► nginx ──► platform
```

| | Machine A | Machine B | Machine C |
|---|---|---|---|
| nginx | 1c / 1G | 1c / 1G | 1c / 1G |
| gateway | 2c / 4G | 2c / 4G | 2c / 4G |
| platform | 2c / 4G | 2c / 4G | 2c / 4G |
| worker | — | — | ×2, 4c / 4G |
| arena | ×2, 8c / 16G | ×3, 12c / 24G | ×2, 8c / 16G |
| MySQL | **primary** 6c / 20G | — | replica 3c / 20G |
| j-redis `session` | **primary** 2c / 8G | replica 1c / 8G | — |
| j-redis `events` | — | **primary** 2c / 8G | replica 1c / 8G |
| **allocated** | 21c / 53G | 20c / 49G | 21c / 57G |

Two j-redis instances, same binary, different ports and data directories:
`session` (sessions, tickets, presence, matchmaking queues, leaderboards) and
`events` (durable streams). Separate instances so an event backlog can never
evict a session, and so the two fail independently
([D-7](03-decision-log.md#d-7--two-j-redis-instances-not-one)).

Allocations are ceilings, not steady-state usage: the arena's 28 allocated
cores carry a ~25-core load at full occupancy.

**Open: the launch on two machines.** The launch target is 5 000–10 000
players on two machines with replication (the scope decision of 2026-09-23,
[requirements §5](../requirements/01-scope-and-nfrs.md#5-capacity-targets)),
and this layout is for three. No document yet says what runs where on two:
in particular where MySQL's replica, the `events` store's replica and the two
workers go, which this table puts on C. It is to be settled with the owner's
machines (Q-3), before the first install.

## 3. Capacity model

Everything follows from **bytes per player per second**. How that budget is
achieved — client-side simulation of bullets and shapes, tank-driven send rate,
per-client entity budgets — is in
[detailed-design/02-networking](../detailed-design/02-networking.md).

### Per player, in battle

| Direction | Rate | Content | Bytes |
|---|---|---|---|
| Down | 15 Hz | ~12 visible tanks × 6 B, header, create/destroy events | 122 B payload (the reference frame, [02 §4](../detailed-design/02-networking.md#4-the-snapshot)) + ~71 B packet overhead; 79 B measured at design density |
| Up | 10 packets/s | 2 coalesced inputs per packet | ~16 B payload + ~71 B overhead |
| | | **Total** | **~3.7 KB/s ≈ 13.4 MB/hour budgeted; ~3.1 KB/s ≈ 11 MB/hour measured** |

At these payload sizes the per-packet overhead (IP + TCP + TLS + framing ≈ 71 B)
is 37 % of downstream traffic, which is why the send *rate* matters more than
shaving bytes off each message.

### System totals

| In battle | Rooms @150 | Egress | Ingress | Arena cores |
|---|---|---|---|---|
| 100 % (50 000) | 333 | 1.1 Gbit/s | 0.35 Gbit/s | ~25 |
| 70 % (35 000) | 233 | 0.77 Gbit/s | 0.25 Gbit/s | ~17 |
| 50 % (25 000) | 167 | 0.55 Gbit/s | 0.18 Gbit/s | ~12 |

### Core budget

| | arena | gateway | platform | worker | j-redis | MySQL | nginx | OS | total |
|---|---|---|---|---|---|---|---|---|---|
| Cores | 25 | 6 | 6 | 3 | 4 | 6 | 2 | 6 | **58 of 72** |

Arena cores are simulation plus snapshot encoding plus socket writes (~750 000/s
at full occupancy). Encoding is measured on the development VM at 2.1–2.2 ms p50
per round of 150 clients ([tick benchmark](../../backend/README.md#the-tick-benchmark)):
15 × 2.15 ≈ 32 ms per room per second, ~10.7 cores across 333 rooms, four times
the ~0.5 ms first estimated. The 14 spare cores are the margin for bursts, GC
and measurement error — not capacity to sell.

**Socket I/O, measured on the development machine (2026-09-30), is the
largest part and is not in the 25 cores.** In the load run
([07 §4](../detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure))
a full room cost 0.26 cores in all, 0.11 of it its room thread and 0.15 the
network threads' share, three quarters of that the kernel's; 333 rooms at that
rate are ~87 cores, more than the three machines have. It is an upper bound: the
bots were on the same machine, over loopback, where the kernel also does the
receiving side's work, on a shared virtual machine. And one process's two Netty
workers would be full near 2 100 players, not the ~7 000 above. Whether the model
holds is Q-3's to settle, with the bots on another machine and the production
NICs; until then the 50 000 is unproven, and the arena's shape with it.

**Simulation is measured and much cheaper than assumed.** The estimate here was
25 Hz × ~2 ms = 50 ms per room per second. The
[tick benchmark](../../backend/README.md#the-tick-benchmark) measures
**0.217 ms per tick, 5.4 ms per room per second** — roughly ten times less,
or ~1.8 cores across 333 rooms instead of ~17. That does not shrink the arena
budget by the same factor: encoding costs about six times the simulation, and
socket writes, measured since (above), cost more than both. The simulation is
not the constraint; the encoder is, and after it the writes.

**Provision 10 GbE.** 1.1 Gbit/s sustained on a 1 Gbit uplink leaves nothing for
bursts and would run at 85 % of line rate at peak.

## 4. Client flow end to end

```
1. Launch → nginx (HTTPS, one of 3 endpoints from the client's list: a.<domain>, b.<domain>, c.<domain>)
             login → platform verifies against MySQL → session in j-redis:
               HSET sess:{token} playerId …, EXPIRE a day ±10 %
           then the lobby, wss://…/lobby → nginx → gateway, authenticated with that token
2. Lobby: profile, team, shop, inventory, tournaments   (gateway → platform → MySQL / j-redis)
3. "Play now" → platform picks an arena from the directory (the arena places the player
                 in one of its rooms itself; the queued modes are matched and confirmed
                 first, 04 §4, and their room made by the first ticket, D-20)
                 writes the join ticket, loadout already resolved:
                   HSET ticket:{id} playerId, name, team, match, mode, bonus, skin   EXPIRE 60
                 replies { arenaHost, arenaPort, ticketId, tls }
4. Client opens a *direct* TCP connection to that arena, TLS when the grant says, and sends Join{ticketId}
   arena: MULTI · HGETALL ticket · DEL · EXEC (single use) → validate → spawn tank
5. In match: input ≤ 20 Hz coalesced; snapshots at the traffic profile's rate (15 Hz, 10 at saver):
             tanks updated every frame, bullets created once and extrapolated client-side,
             shapes updated only when they move
6. Match end: arena XADD s:match-result MINID ~ <a day ago> * e {json} on the events store
              (spooled to local disk first)
              worker XREADGROUP GROUP rewards <workerId> → MySQL transaction (xp, currency, stats)
                                        → ZADD ... GT leaderboards → XACK
              (a list until 2026-09-29; the list q:match-result stays as an inbox, 05)
7. Client returns to the lobby; worker pushes what the result paid, evt.rewards, through the
   gateway that holds the player's lobby connection (05 §6), and the wallet and inventory
   are read over the API
```

## 5. Data plane and control plane

The rule that matters most at 50 000 players:

| Path | Transport | Through nginx? | Through gateway? |
|---|---|---|---|
| Match traffic (client ↔ arena) | raw TCP + TLS in Netty | **no** | **no** |
| Lobby (client ↔ gateway ↔ platform) | WebSocket over TLS, JSON, `/lobby` | yes | yes |
| API: accounts, login, match requests, leaderboards, the shop (client ↔ platform) | HTTPS, JSON, `/v1/` | yes | **no** |
| Domain events (arena/platform → worker) | j-redis `events` streams | no | no |
| Commands (platform → arena) | j-redis pub/sub, `arena-admin:{name}`: an operator's kick and room close (04 §10) | no | no |
| System of record | MySQL | no | no |

Proxying 1.1 Gbit/s of match traffic through a gateway would double internal network
load and add a hop to the one path that cannot afford it. The matchmaker hands
out a specific arena address: **the allocator is the load balancer for
match traffic, not nginx** ([D-5](03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).

No VIP and no keepalived: the client ships with all three edge endpoints and
retries the next on failure. Simpler than VRRP, and better on mobile where the
client is already reconnecting constantly.

## 6. Technology choices

Rationale for each is in the [decision log](03-decision-log.md).

| Concern | Choice |
|---|---|
| Language | Java 21 (Temurin), on the release's own runtime, made by `jlink` ([D-77](03-decision-log.md#d-77--everything-the-backend-builds-and-runs-with-is-in-the-repository-and-in-the-release), [09 §4.1](../detailed-design/09-release-and-packaging.md#41-the-runtime)) |
| GC | Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) |
| Network | Netty 4.2, native epoll, raw TCP; TLS from the JDK's provider, not `netty-tcnative` (a native library per platform, for speed nobody has measured a need for: [02 §1](../detailed-design/02-networking.md#1-transport)) |
| Wire format | Hand-written binary; deltas against the last frame sent, in world space ([D-16](03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent), which superseded D-11's ack-based baseline) |
| Database | MySQL 8.4 LTS, the release's own (D-77, Q-56) + `mysql-connector-j`, HikariCP, Flyway |
| Cache / coordination | [j-redis-service](../../j-redis-service/README.md), two instances |
| Events | j-redis streams (since j-redis 2.1, 2026-09-29; D-33) |
| Leader election, singleton jobs | Leases in the `session` store, `SET NX` with an expiry: the matcher (`mm:leader`), the ledger check, a season's close. Each job is safe to repeat, so a lease needs no fencing token ([02 §5](02-availability.md#5-prerequisites-still-missing)). No cluster membership library: JGroups and Apache Ratis, in the offline bundle, are not used ([D-12](03-decision-log.md#d-12--embedded-libraries-yes-installed-daemons-no)) |
| Metrics | Prometheus text format on a loopback HTTP endpoint, written by hand (`common/Metrics`) because the format is a few lines; the offline bundle's Micrometer and Prometheus registry are not used. Tick times through HdrHistogram ([operations/01 §7](../operations/01-deploy.md#7-installing-a-machine)) |
| Config tables | The simulation's balance (stats, levels, shapes, classes, modes) is code, in `sim` and `handoff/MatchMode`: loading it from JSON is not built, and not wanted yet (Q-27). `platform`'s tables are JSON read at boot with Jackson and checked strictly: `items.json`, `shop.json`, `packs.json` |
| Logging | SLF4J + Logback, async appender: the thread that logs never waits for the journal ([T-10](../defects.md#4-concurrency)) |
| Build | Maven multi-module; the release is one `lib/<process>/` of plain jars per process type, so the MySQL driver stays off an arena ([operations/01 §7](../operations/01-deploy.md#7-installing-a-machine)); only `tools` is a shaded jar. Offline, from [`java21-offline`](../../java21-offline/README.md), with the JDK and Maven committed in `vendor/` (`build-offline.sh`, [09 §3](../detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)) |
| Process management | systemd, CPU pinning |

Every third-party choice is an **embedded jar**, never an installed service.
MySQL and nginx are the only third-party daemons on the machines (C-6); j-redis,
our own, runs beside them (C-7). The release carries all three, and the Java
runtime, instead of the operating system's packages, and runs them from
`/opt/backend` (D-77).

## 7. Core principles

1. **Single writer per state.** A room's state is touched only by its room
   thread; j-redis's dataset only by its command thread. Everything else goes
   through queues. No locks in hot paths.
2. **Arena is authoritative and never blocks.** No database, no disk, no
   synchronous RPC on a room thread.
3. **Latest state beats history.** Snapshots are droppable; a backed-up client
   is skipped this tick, not queued.
4. **Zero allocation in the tick loop** — on the server *and* in the Unity
   client, where GC shows up as frame hitches.
5. **Idempotent side effects.** Every result, purchase and reward carries an
   id; consumers dedupe. Crashes become retries, not corruption.
6. **Data-driven balance.** Tanks, shapes, the XP curve and mode rules live in
   tables of data, not in the logic that reads them: in code for the simulation
   (`sim/Content`, `ClassTable`, `MatchMode`, Q-27), in JSON for `platform`'s
   items, shop and packs.
7. **Fail small.** Room crash → room restarts. Arena crash → its rooms only.
   j-redis restart → clients reconnect with backoff; arenas spool to disk.
8. **Every byte on the wire costs a player money.** Mobile data is the budget
   that drives the protocol, not server bandwidth.
