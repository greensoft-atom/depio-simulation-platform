# arena

The arena process: it accepts players' match connections, raw TCP framed by a
varint length and optionally TLS, claims their join tickets from j-redis, places
them in rooms, and runs each room on a thread of its own around the simulation
(`sim`). It sends every client its own delta snapshot at the rate its link can
carry, runs the modes a matcher makes rooms for, and publishes each result
through a disk spool to the result stream. It never touches MySQL; everything it
shares with other processes goes through j-redis (`handoff`).

Design: [01 Realtime arena](../../docs/detailed-design/01-arena.md) and
[02 Networking](../../docs/detailed-design/02-networking.md); diagrams in
[the arena and the wire](../../docs/diagrams/02-arena-and-wire.md).

## Layout

| Group | Class | What it is for |
|---|---|---|
| Process | `ArenaMain` | Reads the arguments, opens the store clients, starts the publisher, TLS, the listener, the announcer and the metrics; registers the stop that drains |
| Network | `ArenaServer` | Netty: one boss thread, two worker threads (epoll where available, else NIO), child options and the pipeline; `close()` stops rooms before connections |
| | `ArenaTls` | Loads a PKCS#12 keystore with the JDK's provider, TLS 1.3 and 1.2; refuses expired, not yet valid or key-less stores; `covers(host)` checks the certificate's names |
| | `VarintFrameDecoder` | Splits the stream on a LEB128 length; a frame over 8 KiB closes the connection |
| | `MatchFrameHandler` | One per connection, on its event loop: deadlines, rate limit, decoding every client message, the ticket claim, routing a resume |
| | `Frames` | `write` a length-prefixed frame; `kick` with a reason, closing once written or after 2 s |
| | `Connection` | The boundary between an event loop and the room thread: the latest input in one atomic `long`, the fire latch, the acknowledgement, pending points, class, phrase and sandbox requests, background flag, resume secret |
| Rooms | `RoomRegistry` | The rooms: `allocate` for the public arena, `allocateMatch` for made matches, the watchdog, operator `closeRoom`/`remove`, `drain` and `close`, the resume-secret map, metric totals |
| | `RoomThread` | One room's thread: the fixed-rate loop, joins, leaves, resumes, input, the step, deaths, the kill feed, phrases, progression, the lifecycle and modes, snapshot rounds, the `Welcome` |
| | `MatchRules` | A room's lifecycle (`OPEN`, `TIMED`, `MADE`), match length, checkpoint, resume windows, join window, mode |
| | `MatchTally` | What each player did in the current match, keyed by player tag: kills, deaths, assists, score, time played; placements; the `MatchOutcome` |
| | `ArenaAnnouncer` | Writes the arena's directory entry every 3 s and hears operators' commands |
| Snapshots | `ClientView` | One client's handle table (slot and generation per handle), the state last sent per handle, acknowledgement, the input echo and `inputTicks`, the motion rule last told |
| | `SnapshotEncoder` | Selects and ranks what a client sees, then writes removes, creates, updates and events; one per room thread, allocation-free |
| | `EventBuffer` | A client's pending events, pre-encoded as `type, length, payload` |
| | `TrafficProfile` | `SAVER` 10 Hz / 20 entities, `MOBILE` 15 / 30, `HIGH` 15 / 60 |
| | `TrafficControl` | Steps a client down when its link queues and back up when it clears |
| Modes | `Waves` | Co-op's hunting tanks in waves, the Guardian, enraging |
| | `Domination` | Three dominators and the 60 s hold |
| | `Tag` | Each player's team as kills convert them |
| | `Sandbox` | A sandbox's powers: a level, a Guardian |
| Results | `MatchResultPublisher` | Spools every result to disk, then pushes it to the result stream; replays the spool at start |

## Running it

`com.backend.arena.ArenaMain`, positional arguments (ArenaMain.java:76-89):

| # | Argument | Default | Meaning |
|---|---|---|---|
| 0 | port | 9001 | match listener port; 0 picks one |
| 1 | map size | 5700 | the public arena's square, in world units |
| 2 | max players | 150 | per public room |
| 3 | shapes | 1500 | per public room |
| 4 | max rooms | 4 | public and made rooms together |
| 5 | store host | 127.0.0.1 | session store; `BACKEND_STORE_ADDRESSES` overrides it |
| 6 | store port | 6379 | |
| 7 | name | `arena-<port>` | the directory name; the unit passes the instance name |
| 8 | match seconds | 0 | 0: the continuous public arena; more: timed matches back to back |
| 9 | spool directory | `/var/lib/backend/spool/<name>` | where results wait for the store; must survive restarts |
| 10 | bind host | 0.0.0.0 | the interface to listen on |
| 11 | advertise host | 127.0.0.1 | what platform tells clients to dial |

A number that does not parse refuses to start (exit 2, which the unit does not
restart); any other failure to start exits 1. Fixed in code: a world of 16 384
entities, a grid cell of 200 units, two Netty worker threads.

On a developer's machine, from a release (`backend/README.md`):

```bash
java -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/java.nio=ALL-UNNAMED \
     -cp '<release>/lib/arena/*' com.backend.arena.ArenaMain \
     9001 5700 150 1500 4 127.0.0.1 6380 arena-1 0 ./spool/arena-1 127.0.0.1 127.0.0.1
```

In production it is `deploy/systemd/backend-arena@.service`, one instance per
arena, with its settings in `/etc/backend/arena-<name>.env`
(`deploy/env/arena.env.example`): `ARENA_PORT`, `MAP_SIZE`, `MAX_PLAYERS`,
`SHAPES`, `MAX_ROOMS`, `STORE_HOST`, `STORE_PORT`, `MATCH_SECONDS`,
`ARENA_BIND`, `ADVERTISE_HOST`. The unit runs a 6 GB heap with generational
ZGC, `-Dio.netty.leakDetection.level=disabled`, and `TimeoutStopSec=12min` for
the drain. See [operations/01](../../docs/operations/01-deploy.md).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_ARENA_TLS_KEYSTORE` | unset: plaintext, with a warning | PKCS#12 keystore; every connection then speaks TLS from its first byte |
| `BACKEND_ARENA_TLS_PASSWORD_FILE`, `BACKEND_ARENA_TLS_PASSWORD` | required with a keystore | the keystore's password; `_FILE` wins |
| `BACKEND_METRICS_ADDR` | unset: no metrics server | `host:port` for `GET /metrics`; keep it on loopback |
| `BACKEND_STORE_ADDRESSES` | unset: store host and port | `host:port[,host:port]`, the session store and its replica; the client follows a promotion. With two and no events store, the publisher waits for the replica |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | unset: no AUTH, with a warning | the store's password |
| `BACKEND_EVENTS_STORE` | unset: the session store | `host:port[,host:port]`, the instance the results go to; two addresses also make the publisher wait for the replica |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE`, `BACKEND_EVENTS_STORE_PASSWORD` | the store's password | |
| `-Dlogback.configurationFile` | `common/logback.xml`: INFO, queued, to standard output | an operator's own logging configuration |

The store is read through `handoff/StoreClients` and secrets through
`common/Secrets`, whose rules are in [operations/01 §2](../../docs/operations/01-deploy.md#2-conventions).

### Ports

| Listener | Default | Protocol |
|---|---|---|
| Match connections | `0.0.0.0:9001` | raw TCP, varint-framed binary ([02 §1](../../docs/detailed-design/02-networking.md#1-transport)); TLS 1.3 or 1.2 when a keystore is set; `TCP_NODELAY`, `SO_KEEPALIVE`, write water marks 32/128 KiB, backlog 1 024 |
| Metrics | none; `127.0.0.1:9110` in the example env file, `127.0.0.1:9197` in the drill | HTTP `GET /metrics`, Prometheus text 0.0.4 |

Outbound: the session store (tickets, the directory, sandbox holds, match
arrivals, operators' channel) and the events store (the result stream).

### What it keeps in the store

| Key | What |
|---|---|
| `ticket:<id>` | a join ticket, written by platform, claimed (read and deleted at once) on `Join`; lives 60 s |
| `arena:<name>`, set `arenas` | the directory entry: address, players, capacity, `tls`, rooms and room list; refreshed every 3 s, expires after 10 s |
| `rooms:promised:<name>` | platform's promises of a room; the announcement settles them |
| `seats:promised:<name>` | platform's promises of a public seat, by player; the announcement that first counts the player drops theirs (D-79) |
| channel `arena-admin:<name>` | operators' commands: `{"cmd":"close","room":…}` and `{"cmd":"kick","player":…,"ban":…}` ([04 §10](../../docs/detailed-design/04-platform-services.md#10-admin-api)) |
| `sbx:…`, `marr:…` | a sandbox player's hold, and a made match's first arrival |
| stream `s:match-result` | results, field `e`, for the workers' group `rewards` |

### Metrics

| Name | Type | What |
|---|---|---|
| `backend_arena_rooms` | gauge | rooms running |
| `backend_arena_players` | gauge | players in rooms, stays waiting for a resume included |
| `backend_arena_stays_waiting` | gauge | stays whose connection was lost, waiting for their player |
| `backend_arena_clients{profile}` | gauge | clients by the traffic profile they are at now |
| `backend_arena_tick_p99_seconds{room}` | gauge | each room's whole-tick p99 over the last 10 s; the budget is 0.040 |
| `backend_arena_tls_certificate_expiry_timestamp_seconds` | gauge | with TLS only: the earliest expiry in the chain served |
| `backend_arena_connection_bytes_per_second{profile}` | histogram | each connection's own snapshot bytes a second, observed as it ends, if it lasted a minute; by the profile it asked for |
| `backend_arena_joins_total{outcome}` | counter | `joined`, `bad_ticket`, `no_room`, `claim_failed`, `removed` |
| `backend_arena_connections_dropped_total{reason}` | counter | `join_deadline`, `idle`, `stalled`, `tls_handshake` |
| `backend_arena_snapshot_bytes_total{profile}` | counter | snapshot payload bytes, by the profile sent at |
| `backend_arena_profile_steps_total{direction}` | counter | `down`, `up` |
| `backend_arena_snapshots_sent_total`, `…_skipped_total`, `…_held_total` | counter | sent; skipped as unwritable; held as the link already had a second queued |
| `backend_arena_tick_overruns_total` | counter | times a room fell more than 10 ticks behind and dropped the time |
| `backend_arena_rooms_failed_total` | counter | rooms closed after repeated faults, or abandoned as hung |
| `backend_arena_resumes_total`, `backend_arena_stays_expired_total` | counter | stays resumed; stays ended unresumed after the minute |
| `backend_arena_results_published_total`, `…_spooled_total`, `…_unreplicated_total`, `backend_arena_spool_failures_total` | counter | the result path |
| `backend_store_*`, `backend_events_store_*` | | the store client's `timeouts_total`, `failed_fast_total`, `reconnects_total`, `server_errors_total`, `connected` |
| `backend_jvm_heap_used_bytes`, `backend_jvm_threads`, `backend_jvm_gc_seconds_total`, `backend_process_uptime_seconds` | | the process |

## Limits and timings

| What | Value | Where |
|---|---|---|
| Frame size | 8 KiB; larger closes the connection | `ArenaServer.MAX_FRAME` |
| `Join` or `Resume` after connecting, TLS included | 10 s | `MatchFrameHandler.Limits` |
| A whole frame at least every | 30 s | `Limits`, `IdleStateHandler` |
| Unwritable at most | 5 s, then closed without a kick | `Limits` |
| Client frames | 60 a second, a bucket of 330; past it `Kick(4)` | `MatchFrameHandler` |
| Ticket or resume secret | ASCII, 1 to 64 bytes | `MatchFrameHandler` |
| A kick's close | once written, or after 2 s | `Frames.KICK_GRACE_MILLIS` |
| Input silent for | 1 s parks the tank | `RoomThread.STALE_INPUT_NANOS` |
| A player's phrases | one per 2 s; sooner, dropped | `PHRASE_GAP_TICKS` |
| `Stats` for experience alone | once a second; a level or a point at once | `STATS_REFRESH_TICKS` |
| Open match checkpoint | every 10 min | `MatchRules.DEFAULT_CHECKPOINT_TICKS` |
| A lost connection's tank | parked 10 s, then taken out; the stay kept 60 s | `MatchRules` resume grace and keep |
| Respawn in the public arena | a quarter of the last life's experience, up to level 20's | `REBATE_DIVISOR`, `REBATE_MAX_LEVEL` |
| Catching up | up to 10 ticks behind, run back to back; more, counted as an overrun | `MAX_LAG_TICKS` |
| A failing room | 3 failed ticks within 100 loop iterations close it; an `Error` at once | `FAILED_TICKS_TO_CLOSE` |
| Watchdog | every 500 ms; a room 2 s without a tick is logged with its stack, 10 s abandoned | `RoomRegistry` |
| Public room filling | to `max players − min(5, max players / 4)`, then any room with space | `RoomRegistry.allocate` |
| A create sent again | unconfirmed 15 ticks with the acknowledgement stalled 15 (50 before the first) | `SnapshotEncoder` |
| View | 1 600 units × the class's `fovMul`, centred ahead while a Predator zooms | `RoomThread.sendSnapshots` |
| Matches remembered as ended | the last 10 000 | `RoomRegistry` |
| A removed player refused | 60 s, a ticket's life | `RoomRegistry.removedMillis` |
| Drain | up to 11 min | `ArenaMain.DRAIN_MILLIS` |
| Result push | retried every 2 s; on stop, 5 s of delivery; replica wait 100 ms | `MatchResultPublisher` |

## How a connection is served

1. **Connect.** The pipeline is `SslHandler` (with a keystore),
   `VarintFrameDecoder`, `IdleStateHandler`, `MatchFrameHandler`. The join
   deadline starts.
2. **`Join`.** The protocol version first (else `Kick(3)`), then the ticket id
   and an optional profile byte. The ticket is claimed asynchronously and the
   rest runs back on the connection's event loop: a store failure is `Kick(5)`,
   no ticket `Kick(1)`, a player an operator just removed `Kick(7)`.
3. **A room.** A ticket for a made match goes to that match's room, made by its
   first ticket (D-20); any other to the fullest public room with room left, or
   a new one up to `max rooms`. No room is `Kick(2)`.
4. **Admitted** on the room thread: any older stay of the same player is ended,
   a tank spawned, a `ClientView` and `TrafficControl` made, and a `Welcome`
   sent with a fresh resume secret.
5. **Playing.** Inputs land in the connection's atomic slot, a `Ping` is
   answered on the event loop, and each tick the room reads the latest input
   and sends the snapshot when the client's round is due.
6. **Leaving.** `Leave` ends the stay at once and publishes an open match's
   result. A connection lost without it waits for a `Resume`
   ([02 §10](../../docs/detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)).

The sequence diagrams for the join and the resume, the room's stages and the
snapshot encoder are in [the diagrams](../../docs/diagrams/02-arena-and-wire.md).

### Kick reasons

| Reason | When the arena sends it |
|---|---|
| 1 bad ticket | ticket unknown, used, expired or malformed; resume secret unknown or its stay ended |
| 2 room full | no room; a join queue full; the world's pool exhausted; a ticket for a match already over here |
| 3 protocol version | `Join` or `Resume` of another version |
| 4 rate limit | more client frames than the bucket allows |
| 5 internal | the store could not claim the ticket; a frame could not be encoded; the room failed, hung or stopped |
| 6 match over | a made match ended |
| 7 removed | an operator closed the room or took the player out |

## Rooms and modes

| Lifecycle | Rooms | Result |
|---|---|---|
| `OPEN` | the public arena, made by the arena itself as players arrive | one per stay, written when it ends and every 10 min while it lasts |
| `TIMED` | an arena started with match seconds above 0 | one per match, every player in it |
| `MADE` | one room per match the matcher made, by its first ticket | one per match; the room closes after it |

A made room waits up to its join window for its roster, then resets the world
and plays; fewer than two sides at the start is a walkover. The modes come from
`handoff/MatchMode`:

| Id | Key | Roster | Map | Shapes | Length | Ends early at | Rated | Team size |
|---|---|---|---|---|---|---|---|---|
| 0 | `ffa` | open | command line | command line | none | | no | |
| 1 | `duel` | 2 | 2 000 | 40 | 180 s | 3 kills | yes | 1 |
| 2 | `tvt` | 6 | 3 000 | 80 | 300 s | 10 team kills | yes | 3 |
| 3 | `rffa` | 8 | 3 500 | 120 | 240 s | | yes | 1 |
| 4 | `coop` | 3 | 3 000 | 60 | 600 s | ten waves cleared, or a wipe | no | 3 |
| 5 | `teams` | 6 | 3 000 | 80 | 300 s | 10 team kills | yes | 3 |
| 6 | `domination` | 6 | 3 000 | 80 | 300 s | every dominator held 60 s | no | 3 |
| 7 | `tag` | 6 | 3 000 | 80 | 300 s | one team holds everyone | no | 3 |
| 8 | `maze` | 8 | 3 000 | 120 | 240 s | | no | 1 |
| 9 | `sandbox` | none (3 places) | 2 000 | 60 | 1 200 s | a minute empty | publishes nothing | 1 |

Made rooms wait 30 s for their roster; a sandbox starts at once. In tag, a
player keeps the team kills converted them to across a respawn and a resume,
and a team's phrases follow it. A co-op boss and a dominator are given their
class before their experience, so their points follow the boss caps.

## Operational notes

- **Stopping drains** ([01 §8.6](../../docs/detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)).
  On SIGTERM the arena withdraws from the directory, stops its public rooms
  (their players' results published, each player sent `Kick(5)`), lets made
  matches play to their ends for up to eleven minutes, then stops what is left,
  marking a cut-short match's result `cutShort`, and flushes the spool. The log
  says `drained in N s` or `the drain ran out`. `TimeoutStopSec` is 12 min.
- **TLS** ([operations/01 §8](../../docs/operations/01-deploy.md#8-tls-for-match-traffic)).
  With a keystore, the arena refuses to start (exit 2) unless the certificate
  names the advertise host, and on an expired or not yet valid certificate, a
  wrong password or a store with no private key. The keystore is read once:
  a renewed certificate is served from the next restart. Without one the
  arena logs that tickets cross the network in plaintext.
- **Resume lives in memory.** The secrets and the stays waiting for them are
  the arena's, not the store's: an arena restart ends every stay, publishing
  what each was owed, and a later `Resume` is `Kick(1)`.
- **Results are at least once.** Each result is written to the spool
  (`<matchUid>.json`, through a temporary file and an atomic rename) before it is
  pushed, and deleted after; the workers' apply is idempotent. Files left by a
  previous run are sent first at start. `LOST match <id>` in the log means one
  could not even be written to disk during a stop.
- **A hung or failing room** is replaced: its players are sent `Kick(5)` and
  come back through the lobby. A hung thread cannot be stopped; the log says to
  restart the process at a quiet time.
- **Operators** close a room or take a player out through platform's admin API;
  the arena hears it on its channel and kicks with reason 7. A banned player's
  ticket issued before the ban is refused for a ticket's life.
- **Logging** is INFO, queued, to standard output and the journal; a stuck
  journal drops lines rather than stall a room. Debug lines include every
  connection closed before joining.

## Build, run and test

From `backend/` (the repository's CLAUDE.md):

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o install                    # everything
/opt/maven/bin/mvn -o -pl arena -am test         # this module and what it depends on
```

The tests start a real embedded j-redis and real sockets; no MySQL. A release
for drills:

```bash
MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh
TMPDIR=<scratchpad> client/headless-drill.sh play resume coldresume duel tag maze sandbox
TLS=1 TMPDIR=<scratchpad> client/headless-drill.sh play untrusted
DRAIN_AFTER=20 TMPDIR=<scratchpad> client/headless-drill.sh duel     # stop the arena mid-duel: it drains
BOTS=300:120 TMPDIR=<scratchpad> client/headless-drill.sh            # the load run
```

The drill runs the arena on 9011 with its metrics on 9197, against j-redis on
6390. Its scenarios are listed in the repository's CLAUDE.md. What a change costs the
tick or the payload is measured with `tools/TickBenchmark`
([tools](../tools/README.md#tickbenchmark)).

## Tests

18 classes, 216 tests.

| Test class | Tests | What it covers |
|---|---|---|
| `ArenaServerTest` | 99 | End to end over a plain socket: joins and tickets, leave and resume, every lifecycle and mode, deaths and respawns, the rebate, phrases, the kill feed, profiles, failures, the watchdog, drain and shutdown, operators, the sandbox, the motion events |
| `SnapshotEncoderTest` | 23 | The entity budget and priority, handle 1 for the own tank, handles not reused before their removal is acknowledged, predicted entities never updated, creates re-sent only to a client that stopped acknowledging, levels, classes, radii, traps as units, the protection and hidden flags, zoom, skins, a mobile frame inside the byte budget |
| `MatchTallyTest` | 18 | One entry across a leave and a return, kills, assists, score, placements by score, by kills, by team and by dominators held, shared placements, open sessions and checkpoints, a match nobody played |
| `MatchResultPublisherTest` | 12 | The spool with the store down and back, a refusing disk, closing with and without the store, a result after close, the stream trimmed to a day, the replica wait, replay order |
| `TrafficControlTest` | 12 | Stepping down and up through a simulated link: far but roomy links left alone, slow ones stepped down and kept fresh, probes backing off, the ceiling held |
| `SnapshotRoundTripTest` | 10 | The real encoder's bytes decoded by `protocol`'s reader: every position agrees, at 15 frames in 25 ticks, across gaps, respawns and input wrap-around, in a busy room, with names and the own handle |
| `ArenaTlsTest` | 8 | Keystores loaded and refused, certificate names covered, names given as bytes skipped |
| `MatchFrameHandlerTest` | 8 | The claim finished on the connection's thread, store failures, the rate limit's backlog and flood, the join deadline, the unwritable spell |
| `WavesTest` | 6 | Co-op's waves, the Guardian, enraging, the tenth wave |
| `RoomRegistryTest` | 4 | Room placement, a match's room made by its first ticket, the announced capacity, room names |
| `ClientViewTest` | 3 | A handle stands for one incarnation; `inputTicks`; when a motion rule is told |
| `RoomTallyIntegrationTest` | 3 | Kills and assists from a real room reach the result |
| `DominationTest` | 3 | Dominators placed and captured; the 60 s hold |
| `MotionVectorTest` | 2 | The golden vector `motion-2026.txt` the client's prediction must reproduce bit for bit; the move bits as a direction |
| `TagTest` | 2 | Conversions; one team; head counts |
| `ArenaAnnouncerTest` | 1 | Announcements carry the rooms; operators' commands reach them |
| `ConnectionTest` | 1 | A burst of point requests is held to 10 |
| `RoomThreadTimersTest` | 1 | The simulation timed apart from the whole tick |

`MotionVectorTest` rewrites its vector only with `-Dmotion.vector.write=true`; a
change there is a change to how a tank moves, which the C# client must follow.

## Design documents

- [01 §1 Room model and the tick](../../docs/detailed-design/01-arena.md#1-room-model),
  [§7 Deaths, respawn and assists](../../docs/detailed-design/01-arena.md#7-deaths-respawn-spectate),
  [§8 Modes and lifecycles](../../docs/detailed-design/01-arena.md#8-match-modes),
  [§9 Events, the kill feed, phrases](../../docs/detailed-design/01-arena.md#9-in-room-events).
- [02 §1 Transport](../../docs/detailed-design/02-networking.md#1-transport),
  [§3 Messages](../../docs/detailed-design/02-networking.md#3-messages),
  [§4 The snapshot](../../docs/detailed-design/02-networking.md#4-the-snapshot),
  [§5 Handles](../../docs/detailed-design/02-networking.md#5-entity-handles),
  [§7 Interest management](../../docs/detailed-design/02-networking.md#7-interest-management),
  [§8 Traffic profiles](../../docs/detailed-design/02-networking.md#8-traffic-profiles),
  [§10 Resume](../../docs/detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle),
  [§11 Backpressure](../../docs/detailed-design/02-networking.md#11-backpressure).
- [07 Threading](../../docs/detailed-design/07-threading-and-performance.md#1-thread-inventory-arena-process)
  and [stability safeguards](../../docs/detailed-design/07-threading-and-performance.md#8-stability-safeguards).
- [04 §3 Arena registry and tickets](../../docs/detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets),
  [§4 Matchmaking](../../docs/detailed-design/04-platform-services.md#4-matchmaking).
- [operations/01 Deploy](../../docs/operations/01-deploy.md).
