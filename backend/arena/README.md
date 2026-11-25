# arena

The arena is the process that runs matches. A player's app connects to it after the platform has given the player a
join ticket. On the site, the connection comes through the front's nginx. The arena does these things:

- It accepts each match connection: raw TCP, each frame prefixed by its length as a varint, with optional TLS.
- It claims the player's join ticket from j-redis and places the player in a room.
- It runs each room on a thread of its own, around the simulation (the `sim` module).
- It sends each client its own delta snapshot, at the rate the client's link can carry.
- It runs the match modes of the rooms the matcher makes.
- It publishes each result to the result stream, through a spool on disk.

The arena never touches MySQL. Everything it shares with other processes goes through j-redis, by way of the
`handoff` module.

This page covers the module's classes, how to run the arena, its settings, ports, store keys and metrics, its limits
and timings, how it serves a connection, its rooms and modes, operational notes and its tests. Words such as *stay*,
*made match*, *front*, *TLS name* and *drain* have a precise meaning here; the
[glossary](../../docs/glossary.md) defines each.

Design: [01 Realtime arena](../../docs/detailed-design/01-arena.md) and
[02 Networking](../../docs/detailed-design/02-networking.md); diagrams in
[the arena and the wire](../../docs/diagrams/02-arena-and-wire.md).

## Layout

Every class is in the package `com.backend.arena`.

| Group | Class | What it is for |
|---|---|---|
| Process | `ArenaMain` | The entry point. It reads the arguments and settings, opens the store clients, and starts the result publisher, TLS, the metrics server, the listener and the announcer. It registers the shutdown hook that drains the arena |
| Network | `ArenaServer` | The Netty server: one boss thread and two worker threads (epoll where available, else NIO), the connection options and the pipeline. `close()` stops the rooms before it closes the connections |
| | `ProxyLine` | Behind the front only (D-105, D-115). It reads what nginx sends before the phone's own bytes: the PROXY line (from 9001) or a `GET /match/<name>` request (from 80). It keeps the phone's address for the logs, answers the request with `101`, and then removes itself from the pipeline |
| | `ArenaTls` | Loads a PKCS#12 keystore with the JDK's own TLS provider, for TLS 1.3 and 1.2. It refuses a store whose certificate has expired or is not yet valid, and a store with no private key. `covers(host)` checks that the certificate names a host |
| | `VarintFrameDecoder` | Splits the byte stream into frames by their LEB128 length. A frame over 8 KiB closes the connection |
| | `MatchFrameHandler` | One per connection, on the connection's event loop. It enforces the deadlines and the rate limit, decodes every client message, claims the ticket and routes a resume |
| | `Frames` | `write` sends a length-prefixed frame. `kick` sends a reason and closes the connection once the kick is written, or after 2 s |
| | `Connection` | The boundary between an event loop and the room thread. It holds the latest input in one atomic `long`, the fire latch, the acknowledgement, the pending point, class, phrase and sandbox requests, the background flag and the resume secret |
| Rooms | `RoomRegistry` | All rooms. `allocate` places a player in the public arena, `allocateMatch` in a made match. It also runs the watchdog, the operators' `closeRoom` and `remove`, `drain` and `close`, the map of resume secrets, and the metric totals |
| | `RoomThread` | One room's thread: the fixed-rate loop, joins, leaves, resumes, input, the step, deaths, the kill feed, phrases, progression, the lifecycle and modes, the snapshot rounds and the `Welcome` |
| | `MatchRules` | A room's rules: its lifecycle (`OPEN`, `TIMED` or `MADE`), match length, checkpoint, resume windows, join window and mode |
| | `MatchTally` | What each player did in the current match, keyed by player tag: kills, deaths, assists, score and time played; the placements; the `MatchOutcome` |
| | `ArenaAnnouncer` | Writes the arena's directory entry every 3 s, and listens for operators' commands |
| Snapshots | `ClientView` | One client's view: the handle table (slot and generation per handle), the state last sent per handle, the acknowledgement, the input echo and `inputTicks`, and the motion rule last told |
| | `SnapshotEncoder` | Chooses and ranks what a client sees, then writes the removes, creates, updates and events. One per room thread; it allocates nothing |
| | `EventBuffer` | A client's pending events, already encoded as `type, length, payload` |
| | `TrafficProfile` | The three traffic profiles: `SAVER` 10 Hz and 20 entities, `MOBILE` 15 Hz and 30, `HIGH` 15 Hz and 60 |
| | `TrafficControl` | Steps a client down a profile when its link queues, and back up when the link clears |
| Modes | `Waves` | Co-op: the hunting tanks in waves, the Guardian, and enraging |
| | `Domination` | Domination: three dominators and the 60 s hold |
| | `Tag` | Tag: each player's team, as kills convert players from one team to the other |
| | `Sandbox` | A sandbox's powers: set a level, summon a Guardian |
| Results | `MatchResultPublisher` | Writes every result to the spool on disk, then pushes it to the result stream. At start it sends what the spool still holds |

## Running it

The main class is `com.backend.arena.ArenaMain`. It takes positional arguments (ArenaMain.java:76-90):

| # | Argument | Default | Meaning |
|---|---|---|---|
| 0 | port | 9001 | The port the arena listens on for match connections; 0 picks a free one |
| 1 | map size | 5700 | The public arena's square, in world units |
| 2 | max players | 150 | Players in one public room |
| 3 | shapes | 1500 | Shapes in one public room |
| 4 | max rooms | 4 | Public and made rooms together |
| 5 | store host | 127.0.0.1 | The session store. `BACKEND_STORE_ADDRESSES` overrides this and the port |
| 6 | store port | 6379 | The session store's port |
| 7 | name | `arena-<port>` | The arena's name in the directory. The systemd unit passes its instance name |
| 8 | match seconds | 0 | 0: the continuous public arena. Above 0: timed matches, one after another |
| 9 | spool directory | `/var/lib/backend/spool/<name>` | Where results wait until the store has them. It must survive a restart. The unit passes `/var/lib/backend/arena-<name>/spool` |
| 10 | bind host | 0.0.0.0 | The interface to listen on |
| 11 | advertise host | 127.0.0.1 | The address the platform tells clients to dial |

A number that does not parse refuses the start with exit code 2, which the unit does not restart. Any other failure
at start exits with 1. Some values are fixed in code: a world of 16 384 entities, a grid cell of 200 units, and two
Netty worker threads. When the arena listens on an address other than `127.0.0.1` but advertises a loopback address,
it logs a warning: remote clients cannot reach it.

On a developer's machine, run it from a release (see `backend/README.md`):

```bash
java -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/java.nio=ALL-UNNAMED \
     -cp '<release>/lib/arena/*' com.backend.arena.ArenaMain \
     9001 5700 150 1500 4 127.0.0.1 6380 arena-1 0 ./spool/arena-1 127.0.0.1 127.0.0.1
```

`./dev-backend.sh start arena`, at the repository's root, runs it from the release in the same way: on port 9021,
announcing nginx's 9001 and the TLS name `a1.arena.internal`, as the site's arenas do (D-92). With `PLAIN=1` it
announces no TLS name (D-101).

In production the unit `deploy/systemd/backend-arena@.service` runs one instance per arena. Each instance reads its
settings from `/etc/backend/arena-<name>.env` (example: `deploy/env/arena.env.example`). The file sets the arguments
as `ARENA_PORT`, `MAP_SIZE`, `MAX_PLAYERS`, `SHAPES`, `MAX_ROOMS`, `STORE_HOST`, `STORE_PORT`, `MATCH_SECONDS`,
`ARENA_BIND` and `ADVERTISE_HOST`, and the variables in the next section. The unit runs a 6 GB heap with generational
ZGC and `-Dio.netty.leakDetection.level=disabled`. Its `TimeoutStopSec=12min` leaves time for the drain. See
[operations/01](../../docs/operations/01-deploy.md).

## Configuration

The arena reads these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_ARENA_TLS_KEYSTORE` | unset: plaintext, with a warning | A PKCS#12 keystore. When it is set, every connection speaks TLS from its first byte. Not for an arena behind the front |
| `BACKEND_ARENA_TLS_PASSWORD_FILE`, `BACKEND_ARENA_TLS_PASSWORD` | required with a keystore, else exit 2 | The keystore's password; `_FILE` wins |
| `BACKEND_ADVERTISE_PORT` | unset: the port it listens on | The port the arena announces, which players dial. Behind the front it is 9001, nginx's port on every machine. Anything but a number from 1 to 65535 is exit 2 (D-82) |
| `BACKEND_ADVERTISE_TLS_NAME` | unset: not behind the front | The arena's TLS name, such as `a1.arena.internal`. When it is set, the arena is behind the front: it announces TLS and this name, speaks plaintext itself, and reads nginx's PROXY line or `/match/` request first (`ProxyLine`; D-82, D-105, D-115). It must be a host name of two labels or more, at most 253 characters, and not an IP address; the arena keeps it in lower case. Anything else, or the name set together with `BACKEND_ARENA_TLS_KEYSTORE`, is exit 2 |
| `BACKEND_METRICS_ADDR` | unset: no metrics server | `host:port` for `GET /metrics`; keep it on loopback. A bad value is exit 2, before the arena binds and announces itself |
| `BACKEND_STORE_ADDRESSES` | unset: the store host and port arguments | `host:port[,host:port]`: the session store and its replica. The client follows a promotion. With two addresses and no events store, the publisher waits for the replica |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | unset: no AUTH, with a warning | The store's password. A store that requires one when none is set is exit 2 |
| `BACKEND_EVENTS_STORE` | unset: the session store | `host:port[,host:port]`: the instance the results go to. Two addresses also make the publisher wait for the replica |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE`, `BACKEND_EVENTS_STORE_PASSWORD` | the store's password | The events store's password, where it differs |
| `BACKEND_RESULT_RETENTION_HOURS` | 24 | How many hours the result stream keeps an entry, from 1 to 168, else exit 2. Each add trims what is older, so set the same value on every arena and worker (D-83) |
| `-Dlogback.configurationFile` | `common/logback.xml`: INFO, queued, to standard output | An operator's own logging configuration (a system property, not a variable) |

The arena opens the store through `handoff/StoreClients` and reads secrets through `common/Secrets`. Their rules are
in [operations/01 §2](../../docs/operations/01-deploy.md#2-conventions).

### Ports

| Listener | Default | Protocol |
|---|---|---|
| Match connections | `0.0.0.0:9001` | Raw TCP, varint-framed binary ([02 §1](../../docs/detailed-design/02-networking.md#1-transport)); TLS 1.3 or 1.2 when a keystore is set. Options: `TCP_NODELAY`, `SO_KEEPALIVE`, write water marks of 32 and 128 KiB, a backlog of 1 024. Behind the front, a private port: 9011, 9012 or 9013 on the machine's private address (127.0.0.1 on the launch's one machine), reached only by nginx ([operations/05 §3](../../docs/operations/05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)) |
| Metrics | none; `127.0.0.1:9110` in the example env file, `127.0.0.1:9197` in the drill | HTTP `GET /metrics`, Prometheus text format 0.0.4 |

The arena connects out to two places: the session store (tickets, the directory, sandbox holds, match arrivals and
the operators' channel) and the events store (the result stream).

### Behind the front

On the site, no player reaches an arena directly. The front's nginx takes each match connection and passes it to the
arena on the arena's private port. The arena is behind the front when `BACKEND_ADVERTISE_TLS_NAME` is set. nginx then
sends one of two things before the phone's own bytes, and `ProxyLine` reads it:

| Route | What nginx sends first | What the arena does |
|---|---|---|
| 9001, with TLS (D-82, D-105) | nginx ends the phone's TLS and sends version 1 of the PROXY protocol's line, such as `PROXY TCP4 198.51.100.7 10.0.0.1 51234 9001\r\n`: 107 bytes at most, with `TCP4`, `TCP6` or `UNKNOWN` | Keeps the phone's address and port (`198.51.100.7:51234`, `[2001:db8::7]:51234`, or `unknown`) |
| 80, without TLS (D-115) | The client's request for its arena by name, passed on by nginx: `GET /match/<the grant's tlsName> HTTP/1.1`, then nginx's own headers to the blank line, 1 KiB at most. The phone's address is in its one `X-Forwarded-For` header | Keeps the phone's address, without a port (nginx's HTTP proxy does not pass one), and answers `HTTP/1.1 101 Switching Protocols` with `Upgrade: backend-match` |

In both cases `ProxyLine` then removes itself from the pipeline. What follows is the match protocol, as on any other
connection. A log line that names the connection's address then names the phone's, not nginx's: for example
`player N joined from 198.51.100.7`.

The arena closes the connection, and counts it as `proxy_line` in `backend_arena_connections_dropped_total`, when
the first bytes are anything else. Examples: a frame, any other HTTP request, another path or method, HTTP/1.0, a
path with no name or with a slash in it, no `X-Forwarded-For` or two of them, an address that is not one, a line in
lower case, a PROXY line past 107 bytes, or a request past 1 KiB. Nothing is answered before the request's end has
come. The arena checks the name's form only: nginx has already chosen the arena by that name. The list of names is
`backend-arenas.conf`, which both of nginx's routes read
([operations/11 §10.3](../../docs/operations/11-phone-to-backend-routes.md#103-a-match-to-an-arena)).

### What it keeps in the store

| Key | What |
|---|---|
| `ticket:<id>` | A join ticket. The platform writes it; the arena claims it on `Join` (reads and deletes it in one step). It lives 60 s |
| `arena:<name>`, set `arenas` | The arena's directory entry: address, port, players, capacity, `tls`, `tlsName`, rooms, the most rooms and the room list. Refreshed every 3 s; it expires after 10 s |
| `rooms:promised:<name>` | The platform's promises of a room here. The arena's announcement settles them |
| `seats:promised:<name>` | The platform's promises of a public seat, by player. The first announcement that counts the player drops that player's promise (D-79) |
| channel `arena-admin:<name>` | Operators' commands: `{"cmd":"close","room":…}` and `{"cmd":"kick","player":…,"ban":…}` ([04 §10](../../docs/detailed-design/04-platform-services.md#10-admin-api)) |
| `sbx:…`, `marr:…` | A sandbox player's hold, and a made match's first arrival |
| stream `s:match-result` | The results, in field `e`, for the workers' group `rewards` |

### Metrics

| Name | Type | What |
|---|---|---|
| `backend_arena_rooms` | gauge | Rooms running |
| `backend_arena_players` | gauge | Players in rooms, including stays waiting for a resume |
| `backend_arena_stays_waiting` | gauge | Stays whose connection was lost, waiting for their player |
| `backend_arena_clients{profile}` | gauge | Clients, by the traffic profile they are at now |
| `backend_arena_tick_p99_seconds{room}` | gauge | Each room's whole-tick p99 over the last 10 s; the budget is 0.040 |
| `backend_arena_tls_certificate_expiry_timestamp_seconds` | gauge | With TLS only: when the certificate clients are shown expires, in Unix seconds |
| `backend_arena_connection_bytes_per_second{profile}` | histogram | Each connection's own snapshot bytes per second, observed when it ends, if it lasted a minute; by the profile it asked for |
| `backend_arena_joins_total{outcome}` | counter | How each join with a ticket ended: `joined`, `bad_ticket`, `no_room`, `claim_failed`, `removed` |
| `backend_arena_connections_dropped_total{reason}` | counter | Connections the arena closed without the client asking: `join_deadline`, `idle`, `stalled`, `tls_handshake`, and behind the front `proxy_line` (D-105, D-115) |
| `backend_arena_snapshot_bytes_total{profile}` | counter | Snapshot payload bytes, by the profile they were sent at |
| `backend_arena_profile_steps_total{direction}` | counter | Profile steps: `down` when a link queued, `up` back towards what the client asked for |
| `backend_arena_snapshots_sent_total`, `…_skipped_total`, `…_held_total` | counter | Snapshots sent; skipped because the socket was not writable; held because the link already had a second of them queued |
| `backend_arena_tick_overruns_total` | counter | Times a room fell more than 10 ticks behind and dropped the time instead of catching up |
| `backend_arena_rooms_failed_total` | counter | Rooms closed after repeated faults, or abandoned as hung |
| `backend_arena_resumes_total`, `backend_arena_stays_expired_total` | counter | Stays resumed; stays ended without a resume after the minute |
| `backend_arena_results_published_total`, `…_spooled_total`, `…_unreplicated_total`, `backend_arena_spool_failures_total` | counter | The result path: handed to the store; written to the spool; not confirmed by the events store's replica within 100 ms; failed writes to the spool |
| `backend_store_*`, `backend_events_store_*` | | The store clients': `timeouts_total`, `failed_fast_total`, `reconnects_total`, `server_errors_total`, `connected` |
| `backend_jvm_heap_used_bytes`, `backend_jvm_threads`, `backend_jvm_gc_seconds_total`, `backend_process_uptime_seconds` | | The process |

## Limits and timings

| What | Value | Where |
|---|---|---|
| Frame size | 8 KiB; a larger frame closes the connection | `ArenaServer.MAX_FRAME` |
| `Join` or `Resume` after connecting, a TLS handshake included | 10 s | `MatchFrameHandler.Limits` |
| A whole frame at least every | 30 s | `Limits`, `IdleStateHandler` |
| Unwritable at most | 5 s, then closed without a kick | `Limits` |
| Client frames | 60 a second, in a bucket of 330; past it, `Kick(4)` | `MatchFrameHandler` |
| Ticket or resume secret | ASCII, 1 to 64 bytes | `MatchFrameHandler` |
| A kick's close | Once the kick is written, or after 2 s | `Frames.KICK_GRACE_MILLIS` |
| Behind the front, what nginx sends first | A PROXY line of 107 bytes at most; a `/match/` request of 1 KiB at most | `ProxyLine.MAX_LINE`, `MAX_REQUEST` |
| Input silent for | 1 s parks the tank | `RoomThread.STALE_INPUT_NANOS` |
| A player's phrases | One per 2 s; a sooner one is dropped | `PHRASE_GAP_TICKS` |
| `Stats` event for experience alone | Once a second; for a level or a point, at once | `STATS_REFRESH_TICKS` |
| Open match checkpoint | Every 10 min | `MatchRules.DEFAULT_CHECKPOINT_TICKS` |
| A lost connection's tank | Parked 10 s, then taken out; the stay is kept 60 s | `MatchRules` resume grace and keep |
| Respawn in the public arena | A quarter of the last life's experience, up to level 20's | `REBATE_DIVISOR`, `REBATE_MAX_LEVEL` |
| Catching up | Up to 10 ticks behind, run back to back; further behind, counted as an overrun | `MAX_LAG_TICKS` |
| A failing room | 3 failed ticks within 100 loop iterations close it; an `Error` closes it at once | `FAILED_TICKS_TO_CLOSE` |
| Watchdog | Every 500 ms. A room 2 s without a tick is logged with its stack; at 10 s it is abandoned | `RoomRegistry` |
| Public room filling | Up to `max players − min(5, max players / 4)`, then any room with space | `RoomRegistry.allocate` |
| A create sent again | When it is unconfirmed after 15 ticks and the acknowledgement has stalled 15 ticks (50 before the client's first acknowledgement) | `SnapshotEncoder` |
| View | 1 600 units × the class's `fovMul`, centred ahead while a Predator zooms | `RoomThread.sendSnapshots` |
| Matches remembered as ended | The last 10 000 | `RoomRegistry` |
| A removed player refused | 60 s, a ticket's life | `RoomRegistry.removedMillis` |
| Drain | Up to 11 min | `ArenaMain.DRAIN_MILLIS` |
| Result push | Retried every 2 s; on stop, 5 s of delivery; the replica wait is 100 ms | `MatchResultPublisher` |

## How a connection is served

1. **Connect.** The pipeline is `SslHandler` (only with a keystore), `VarintFrameDecoder`, `IdleStateHandler` and
   `MatchFrameHandler`. Behind the front, `ProxyLine` comes first and there is no `SslHandler`, since nginx has ended
   any TLS ([Behind the front](#behind-the-front)). The join deadline starts.
2. **`Join`.** The handler reads the protocol version first; another version gets `Kick(3)`. Then it reads the
   ticket id and an optional profile byte. It claims the ticket asynchronously, and the rest of the join runs back
   on the connection's event loop. A store failure is `Kick(5)`, no ticket is `Kick(1)`, and a player an operator
   has just removed gets `Kick(7)`.
3. **A room.** A ticket for a made match goes to that match's room, which its first ticket creates (D-20). Any other
   ticket goes to the fullest public room that still has space, or to a new room while there are fewer than
   `max rooms`. No room is `Kick(2)`.
4. **Admitted**, on the room thread. The room ends any older stay of the same player, spawns a tank, makes a
   `ClientView` and a `TrafficControl`, and sends a `Welcome` with a fresh resume secret.
5. **Playing.** Each input lands in the connection's atomic slot. The event loop answers a `Ping` itself. On each
   tick the room reads the latest input and sends the snapshot when the client's round is due.
6. **Leaving.** `Leave` ends the stay at once and publishes an open match's result. A connection lost without
   `Leave` waits for a `Resume`
   ([02 §10](../../docs/detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)).

The sequence diagrams for the join and the resume, the room's stages and the snapshot encoder are in
[the diagrams](../../docs/diagrams/02-arena-and-wire.md).

### Kick reasons

| Reason | When the arena sends it |
|---|---|
| 1 bad ticket | The ticket is unknown, used, expired or malformed; or the resume secret is unknown, or its stay has ended |
| 2 room full | No room; a join queue is full; the world's pool is exhausted; or the ticket is for a match that is already over here |
| 3 protocol version | A `Join` or `Resume` of another protocol version |
| 4 rate limit | More client frames than the bucket allows |
| 5 internal | The store could not claim the ticket; a frame could not be encoded; or the room failed, hung or stopped |
| 6 match over | A made match ended |
| 7 removed | An operator closed the room or took the player out |

## Rooms and modes

Each room has one of three lifecycles:

| Lifecycle | Rooms | Result |
|---|---|---|
| `OPEN` | The public arena. The arena makes these rooms itself as players arrive | One per stay, written when the stay ends, and every 10 min while it lasts |
| `TIMED` | The rooms of an arena started with match seconds above 0 | One per match, covering every player in it |
| `MADE` | One room per match the matcher made, created by the match's first ticket | One per match; the room closes after it |

A made room waits up to its join window for its roster, then resets the world and plays. If fewer than two sides
are there at the start, the match is a walkover. The modes come from `handoff/MatchMode`:

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

Made rooms wait 30 s for their roster; a sandbox starts at once. In tag, a player keeps the team that kills
converted them to, across a respawn and a resume, and a team's phrases follow the player to it. The arena gives a
co-op boss and a dominator their class before their experience, so their points follow the boss caps.

## Operational notes

- **Stopping drains**
  ([01 §8.6](../../docs/detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)).
  On SIGTERM the arena first withdraws from the directory. It then stops its public rooms: their players' results
  are published, and each player is sent `Kick(5)`. Made matches play on to their ends, for up to eleven minutes.
  After that the arena stops what is left, marks a cut-short match's result `cutShort`, and flushes the spool. The
  log says `drained in N s` or `the drain ran out`. The unit's `TimeoutStopSec` is 12 min.
- **TLS** ([operations/01 §8](../../docs/operations/01-deploy.md#8-tls-for-match-traffic)). With a keystore, the
  arena refuses to start (exit 2) in these cases: the certificate does not name the advertise host; the certificate
  has expired or is not yet valid; the password is wrong; the store has no private key. It logs a warning when the
  certificate expires within 14 days. It reads the keystore once, so a renewed certificate is served from the next
  restart. Without a keystore, the arena logs that tickets cross the network in plaintext. On the site, nginx ends
  TLS and the arena has no keystore ([Behind the front](#behind-the-front)).
- **Resume lives in memory.** The resume secrets and the stays waiting for them belong to the arena, not to the
  store. An arena restart ends every stay and publishes what each was owed. A later `Resume` gets `Kick(1)`.
- **Results are delivered at least once.** The publisher writes each result to the spool (`<matchUid>.json`, through
  a temporary file and an atomic rename) before it pushes the result, and deletes the file after. The workers'
  apply is idempotent. At start, files left by a previous run are sent first. `LOST match <id>` in the log means a
  result could not even be written to disk during a stop.
- **A hung or failing room** is replaced. Its players are sent `Kick(5)` and come back through the lobby. A hung
  thread cannot be stopped; the log says to restart the process at a quiet time.
- **Operators** close a room or take a player out through the platform's admin API. The arena hears the command on
  its channel and kicks with reason 7. A banned player's ticket issued before the ban is refused for a ticket's
  life.
- **Logging** is INFO, queued, to standard output and the journal. A stuck journal drops lines rather than stall a
  room. Behind the front, a line that names a connection's address names the phone's, not nginx's. Debug lines
  include every connection closed before it joined.

## Build, run and test

From `backend/` (see the repository's CLAUDE.md):

```bash
. ../dev-env.sh         # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn install             # everything
mvn -pl arena -am test  # this module and what it depends on
```

The tests start a real embedded j-redis and real sockets; they need no MySQL. To make a release for the drills, from
`backend/`:

```bash
scripts/make-release.sh
```

Then run the drills from the repository's root:

```bash
TMPDIR=<scratchpad> client/headless-drill.sh play resume coldresume duel tag maze sandbox
FRONT=http TMPDIR=<scratchpad> client/headless-drill.sh play duel   # through nginx's 80: matches by /match/ (D-115)
DRAIN_AFTER=20 TMPDIR=<scratchpad> client/headless-drill.sh duel     # stop the arena mid-duel: it drains
BOTS=300:120 TMPDIR=<scratchpad> client/headless-drill.sh            # the load run
```

The drill runs the arena on 9011, with its metrics on 9197, against j-redis on 6390. Its scenarios are listed in
the repository's CLAUDE.md. Its client runs on Unity 2019's Mono, whose own TLS stops at 1.0, so the drill is in
clear alone: `TLS=1`, the arena serving TLS from its own keystore, is refused, and `untrusted` with it (D-116). That
TLS is `ArenaTlsTest`'s and `ArenaServerTest`'s. Measure what a change costs the tick or the payload with
`tools/TickBenchmark` ([tools](../tools/README.md#tickbenchmark)).

## Tests

20 classes, 239 tests.

| Test class | Tests | What it covers |
|---|---|---|
| `ArenaServerTest` | 102 | End to end over a plain socket, with the clients pinging on their own clock while they wait (T-62): joins and tickets; leave and resume; every lifecycle and mode; deaths and respawns; the rebate; phrases; the kill feed; profiles; failures; the watchdog; drain and shutdown; operators; the sandbox; the motion events. Behind the front through 9001: a join and a drop logged with the phone's address from nginx's line, and a connection without the line closed (D-105). Behind the front through 80, a match without TLS: nginx's request answered with 101, then a join logged with the phone's address (D-115) |
| `SnapshotEncoderTest` | 24 | The entity budget and priority; handle 1 for the own tank; handles not reused before their removal is acknowledged; predicted entities never updated; creates sent again only to a client that stopped acknowledging; levels; classes; a team changed in place (P-60); radii; traps as units; the protection and hidden flags; zoom; skins; a mobile frame inside the byte budget |
| `MatchTallyTest` | 18 | One entry across a leave and a return; kills, assists and score; placements by score, by kills, by team and by dominators held; shared placements; open sessions and checkpoints; a match nobody played |
| `MatchResultPublisherTest` | 12 | The spool with the store down and back; a disk that refuses writes; closing with and without the store; a result after close; the stream trimmed to a day; the replica wait; the replay order |
| `TrafficControlTest` | 12 | Stepping down and up through a simulated link: far but roomy links left alone, slow ones stepped down and kept fresh, probes backing off, the ceiling held |
| `ProxyLineTest` | 11 | What nginx sends first. From 9001, the PROXY line (D-105): read, its address kept, the phone's bytes passed on after it, and the reader removed; a line in pieces waited for; IPv4, IPv6 and `UNKNOWN`; anything else first refused, closed and counted (a frame, HTTP, a field short or over, a port or an address that is not one, IPv6 given as IPv4, no CR, lower case); a line past 107 bytes; the address a log names. From 80, a match's request (D-115): read to its blank line, answered with 101, the phone's address taken from `X-Forwarded-For`, the bytes after it passed on; a request in pieces, nothing answered before its end; IPv4 and IPv6, the header's name in any case; refused with nothing answered: another path or method, HTTP/1.0, no name, a name with a slash, no address or two, an address that is not one, lower case; a request past 1 KiB |
| `SnapshotRoundTripTest` | 10 | The real encoder's bytes decoded by `protocol`'s reader: every position agrees, at 15 frames in 25 ticks, across gaps, respawns and input wrap-around, in a busy room, with names and the own handle |
| `MatchFrameHandlerTest` | 9 | The claim finished on the connection's own thread; store failures; the rate limit's backlog and flood; the join deadline; the unwritable spell; a kick closing a connection it cannot be written to; a client of protocol 1 told to update; a player noted for the announcement once a place is sought (D-79) |
| `ArenaTlsTest` | 8 | Keystores loaded and refused; certificate names covered; names given as bytes skipped |
| `WavesTest` | 6 | Co-op's waves, the Guardian, enraging, the tenth wave |
| `RoomRegistryTest` | 5 | Room placement; a match's room made by its first ticket; the announced capacity; room names; places reserved counted with those taken (D-79) |
| `ArenaMainTest` | 5 | `BACKEND_ADVERTISE_PORT` and `BACKEND_ADVERTISE_TLS_NAME` read; each of these refuses the start: a port that is not one, a name a handshake cannot carry, and a name set beside the arena's own keystore (D-82); a port argument out of range, its own or the store's (O-101) |
| `ArenaAnnouncerTest` | 3 | Announcements carry the rooms; operators' commands reach the rooms; a store that is not up at the start does not end it: the operator's channel is made, and the arena announced, once the store is up (O-62); behind the front, the public address, port and TLS name announced, and TLS announced though the arena speaks none (D-82) |
| `ClientViewTest` | 3 | A handle stands for one incarnation; `inputTicks`; when a motion rule is told |
| `RoomTallyIntegrationTest` | 3 | Kills and assists from a real room reach the result |
| `DominationTest` | 3 | Dominators placed and captured; the 60 s hold |
| `MotionVectorTest` | 2 | The golden vector `motion-2026.txt`, which the client's prediction must reproduce bit for bit; the move bits as a direction |
| `TagTest` | 2 | Conversions; one team left; head counts |
| `ConnectionTest` | 1 | A burst of point requests is held to 10, the most any class allows in one stat (a Smasher's) |
| `RoomThreadTimersTest` | 1 | The simulation timed apart from the whole tick |

`MotionVectorTest` rewrites its vector only with `-Dmotion.vector.write=true`. A change to the vector is a change to
how a tank moves, and the C# client must follow it.

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
- [04 §3 Arena registry](../../docs/detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets),
  [§4 Matchmaking](../../docs/detailed-design/04-platform-services.md#4-matchmaking).
- [operations/01 Deploy](../../docs/operations/01-deploy.md),
  the arenas' ports and names in
  [operations/05 §3](../../docs/operations/05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names),
  [operations/11 Phone-to-backend routes](../../docs/operations/11-phone-to-backend-routes.md).
