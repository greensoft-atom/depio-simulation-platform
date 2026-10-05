# 08 — Implementation guide, milestones, deployment

Each milestone lists what to build and a concrete verification. Do not start
the next milestone until the verification passes; every later milestone leans
on the earlier ones being solid.

## Milestone 0 — Skeleton (1 week)

Build: parent POM, modules, Logback config, `common` (IntList, IntIntMap,
xorshift RNG, Config loader, Metrics), shade plugin producing
`arena.jar`, `hub.jar`, `memstore.jar`, `tools.jar`.
Verify: `mvn -q package` on Java 8 produces the four jars; `java -jar memstore.jar --help` runs.

## Milestone 1 — memstore (2 weeks)

Build: dataset + STRING/HASH/LIST/SET commands, TTL, Netty front-end, binary
protocol, client with pipelining, embedded mode, AOF. Then ZSET with skip list,
pub/sub, blocking pops, snapshot, product-specific commands, CLI, INFO.
Verify:
- Fuzz test: 1 M random ops, memstore == reference model.
- `kill -9` during writes → restart → dataset equals model except ≤ 1 s of tail.
- Bench: ≥ 300 k `SET`/s pipelined from one client on loopback; `ZAROUND` on a
  1 M-member zset < 50 µs.

## Milestone 2 — Simulation core (2 weeks)

Build: `World`, `Entity`, `SpatialHash`, tanks/bullets/shapes, collisions with
diep damage rules, XP/levels/skill points, class tree from `tanks.json`,
modifier pipeline, FFA mode, bots.
Verify:
- Unit tests for collision pairs, penetration, XP transfer, level-up thresholds.
- Headless bench: 150 bots + 1 500 bullets + 1 500 shapes, 10 000 ticks:
  p99 tick < 3 ms, zero allocation after warm-up (`-verbose:gc` shows no young GCs during the run).
- Replay determinism: same seed + input log → identical state hash every tick.

## Milestone 3 — Arena networking (2 weeks)

Build: Netty WS server, message codecs, `Room`/`RoomThread`, input queue,
snapshot encoder with interest management, backpressure, ping/pong, resume,
arena registry heartbeat, spool.
Verify:
- Bot client × 200 on one room: every bot receives ≥ 24 snapshots/s, no
  `skippedSnapshots` on LAN, room tick p99 < 5 ms including encode.
- Pull the network cable on a bot (kill the socket): tank persists 10 s,
  resume works, known-set rebuild produces a correct full snapshot.
- Protocol fuzz: random bytes from a client never crash a worker or a room.

## Milestone 4 — Hub core (2 weeks)

Build: lobby WS + JSON protocol, auth/sessions, player profile + cache, room
manager + tickets, public matchmaking (FFA/Teams), results drainer +
`ResultApplier`, leaderboards.
Verify: end-to-end script: register → login → "play FFA" → ticket → arena join
→ die → result in `matches`, `players.stats` updated, `lb:*` updated. Apply the
same result twice → no double reward.

## Milestone 5 — Modes (2 weeks)

Build: Teams 2/4 with bases, Tag, Maze, Domination, Sandbox with room codes,
MvM waves/bosses, duel/ranked/tvt with ratings.
Verify: per-mode unit tests for win conditions; 4-team room with 100 bots runs
1 h without error; domination capture flow visible in snapshots.

## Milestone 6 — Teams, economy, items (2 weeks)

Build: team module (roles/invites/applications/transfer/disband, team chat),
wallet + ledger + shop + inventory, equipment slots, boosts, effects wired to
tickets and to the arena effect system.
Verify: permission matrix tests; concurrent purchase test (100 parallel buys
with 1 affordable) → exactly one success; equipped item changes the modifier
list in the ticket and the effective stat in the room.

## Milestone 7 — Tournaments and polish (2 weeks)

Build: tournament state machine, brackets, scheduler, walkovers, rewards,
inbox, admin API, metrics endpoint, graceful drain, watchdogs.
Verify: 16-entry single-elim runs to completion with bots, including one
no-show (walkover) and one hub restart mid-round (state resumes correctly).

## Milestone 8 — Load, soak, ops (1–2 weeks)

- 3 arenas × 4 rooms × 150 bots for 12 h: tick p99 stable, heap flat, no
  spool growth, memstore ops/s and memory as predicted.
- Chaos: kill memstore (arenas spool, hub reconnects, nothing lost), kill an
  arena (hub drops its rooms, tournament matches rescheduled), kill hub
  (arenas unaffected; results wait in the queue).
- Runbook written for each failure above.

## Deployment layout

```
/opt/title/
  bin/  arena.jar hub.jar memstore.jar tools.jar
  conf/ arena-1.conf arena-2.conf arena-3.conf hub.conf memstore.conf logback.xml content/*.json
/var/lib/title/ memstore/ (snap.rdb, aof.log) spool/ dumps/
/var/log/title/  (logrotate daily, 14 days)
```

systemd unit (arena-1; others analogous):

```ini
[Unit]
Description=Arena 1
After=network.target memstore.service
Wants=memstore.service

[Service]
User=title
WorkingDirectory=/opt/title
ExecStart=/usr/bin/taskset -c 9-13 /usr/lib/jvm/java-8/bin/java @/opt/title/conf/arena.jvmopts -jar bin/arena.jar conf/arena-1.conf
Restart=always
RestartSec=3
LimitNOFILE=262144
KillSignal=SIGTERM
TimeoutStopSec=330
Environment=JAVA_TOOL_OPTIONS=

[Install]
WantedBy=multi-user.target
```

(Java 8 does not support `@argfile` on the command line — put the flags inline
or in an `EnvironmentFile=` with `$JVM_OPTS`.)

Start order: mongod → memstore → hub → arenas. Front: nginx terminating TLS
on 443 with `location /lobby` → hub :8080 and `location /a1|/a2|/a3` → arena
:9001–9003 (WebSocket upgrade headers, `proxy_read_timeout 3600`).

## Configuration files

`arena-1.conf` (simple `key=value`):

```
name=arena-1
ws.port=9001
memstore.host=127.0.0.1
memstore.port=6400
rooms.max=6
room.entityCapacity=8192
room.maxPlayers=200
tick.hz=25
spool.dir=/var/lib/title/spool
content.dir=/opt/title/conf/content
```

Config reload (`SIGHUP` or admin command) re-reads product data tables and applies
them to **new** rooms only; running rooms keep their tables for consistency.

## Operations checklist

- Dashboards: tick histograms per room, players per room, memstore ops/s and
  queue depth, results backlog length, Mongo op latency, GC pause histogram
  per process, spool sizes.
- Daily: `mongodump`, memstore snapshot copy, log rotation, disk usage.
- Weekly: restart arenas one at a time during low traffic (graceful drain),
  review `WARN` counts.
- Incident runbooks: memstore down, arena down, hub down, Mongo slow,
  results backlog, suspicious player (ban via admin API, kick via arena cmd).

## What to build first if time is short

memstore → sim → arena networking → hub with FFA only → leaderboard. That is
a playable, scalable title. Teams, shop, tournaments and extra modes are
additive modules on top of stable foundations; they do not change the core.
