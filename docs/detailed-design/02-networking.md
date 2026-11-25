# 02 — Networking: transport, protocol and snapshots

This document defines how a client and an arena talk to each other during a match: the transport, the format of
every message on the wire, the snapshot, and how the client draws what it is sent. It also shows how the mobile data
budget is met: NFR-2 allows a player at most 15 MB an hour
([requirements §4](../requirements/01-scope-and-nfrs.md#4-non-functional-requirements)). It is for engineers who work
on the arena's network code (`backend/arena`, `backend/protocol`) or on one of the three clients: the full client's
core (`client/Core`), `client-simple/` and `client-simple-no-tls/`. Words such as *stay*, *grant*, *handle*, *TLS
name* and *traffic profile* are defined in the [glossary](../glossary.md#6-on-the-wire). This document fixes the wire
format, so it blocked the Phase 0 client spike and all of Phase 1
([plan §7](../plan.md#7-documentation-order)).

**Scope: the match connection only**, between a client and an arena. It never passes through the gateway
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)). On the site
it does pass through the front's nginx, which D-82 changed in part from D-5. nginx ends the client's TLS and hands the
bytes on, unread, to the arena that the grant's `tlsName` names
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)).
Before those bytes, nginx sends one line of its own with the phone's address
([D-105](../architecture/03-decision-log.md#d-105--an-arena-behind-the-front-knows-the-phones-address-nginxs-proxy-line-read-once)).
Nothing else in this document changes because of nginx. The lobby protocol between the client and `platform` is
ordinary JSON over HTTPS (or HTTP on the site's port 80, for a client without TLS, D-115). It is specified with the
gateway ([03 §3](03-gateway.md#3-lobby-protocol)). The routes from a phone to each process, nginx's files included,
are in [operations/11](../operations/11-phone-to-backend-routes.md).

## 1. Transport

**Match traffic is raw TCP, with TLS or without it.** On the site, the front's nginx ends the TLS (D-82). An arena
that is reached directly ends the TLS itself, in Netty. There is no WebSocket framing, because there is no browser
client ([D-1](../architecture/03-decision-log.md#d-1--unity-native-mobile-client-no-browser-build)). A client without
TLS reaches its arena through the site's port 80 instead. It sends one HTTP upgrade request that names the arena, and
then the same raw frames
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included),
below). Unity's `System.Net.Sockets` speaks this protocol directly on Android
([C-10](../requirements/01-scope-and-nfrs.md#3-constraints)).

### The ways to reach an arena

There are four ways in. The grant from `platform` (its `tls`, `tlsName`, `arenaHost` and `arenaPort` fields) decides
which one a client takes.

| Way | Used by | The client dials | TLS ends at | The arena reads first |
|---|---|---|---|---|
| **The site, port 9001, TLS** (D-82) | the full client, `client-simple/` | the site's public address and 9001 (`arenaHost`, `arenaPort`); its TLS handshake sends `tlsName` | the front's nginx | nginx's PROXY line (D-105) |
| **The site, port 80, no TLS** (D-115) | `client-simple-no-tls/` | the API's own address and 80; it asks for `GET /match/<tlsName>` | nowhere: no TLS | nginx's request for the match, answered `101` |
| **An arena with TLS of its own** (S-6) | a drill, an arena given a keystore | `arenaHost` and `arenaPort` | the arena, in Netty | the TLS handshake |
| **A development run without TLS** (`PLAIN=1`, [D-101](../architecture/03-decision-log.md#d-101--a-plain-run-by-hand-goes-through-nginx-as-the-site-does-80-and-9001-without-tls)) | any client, given a grant without TLS | nginx's 9001 in plaintext, which passes it to the run's one arena | nowhere: no TLS | nothing: the arena has no TLS name, so it reads no line |

The site launches on one machine
([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)).
Its three arenas (`a1` to `a3`, on 9011, 9012 and 9013) sit behind that machine's nginx on loopback. Three machines
stay the design for 50 000 players, and nothing in this document changes between the two.

### Threads and the pipeline

```
event loop, boss    (1 thread)  accept
event loops, workers (2 threads) TLS, frame decode → Connection
RoomThreads          (N threads) simulation; encode and write snapshots
```

Each connection has this pipeline of Netty handlers, as built (`ArenaServer`):

```
ProxyLine                           nginx's PROXY line (D-105) or 80's request for a match (D-115), the phone's
                                    address; behind the front alone, then removed
SslHandler                          TLS 1.3 or 1.2, the JDK's provider; only with a keystore
  → VarintFrameDecoder(max 8 KiB)
  → IdleStateHandler(reader 30 s)   after the decoder: only whole frames are activity
  → MatchFrameHandler
```

An arena is *behind the front* when it is given `BACKEND_ADVERTISE_TLS_NAME`. Only then does the pipeline start with
`ProxyLine`. The settings that decide the way in are these (`ArenaMain`):

| Setting | What it does |
|---|---|
| `BACKEND_ADVERTISE_TLS_NAME` | The arena's name in the TLS handshake, such as `a1.arena.internal`. It puts the arena behind the front: the arena reads the PROXY line or the request first, and announces TLS in the directory, although it speaks plaintext to nginx. It must be a host name of two labels or more. With `BACKEND_ARENA_TLS_KEYSTORE` as well, the arena refuses to start. |
| `BACKEND_ADVERTISE_PORT` | The port the arena announces, the one players dial: 9001 behind the front, where the arena itself listens on a private port (9011, 9012, 9013). Unset, the arena announces the port it listens on. |
| `BACKEND_ARENA_TLS_KEYSTORE` and `BACKEND_ARENA_TLS_PASSWORD` (or `BACKEND_ARENA_TLS_PASSWORD_FILE`) | TLS of the arena's own, on every connection. Unset, the arena speaks plaintext and logs a warning that join tickets cross the network in clear. |

### On the site: TLS ends at the front's nginx, port 9001

**On the site, the front's nginx ends TLS**
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)).
The client dials the site's public address on port 9001 (`arenaHost` and `arenaPort` in the grant). Its TLS handshake
sends the grant's `tlsName` as the server name (SNI, Server Name Indication). nginx routes the connection by that
name. The client checks the certificate against the same name and against the backend's own root
([operations/06](../operations/06-certificates.md)). nginx accepts TLS 1.2 and 1.3 on 9001 (`backend-stream.conf`).
The arena behind nginx speaks plaintext.

**nginx begins each connection with one line**, version 1 of the PROXY protocol:
`PROXY TCP4 <source address> <destination address> <source port> <destination port>\r\n`, for example
`PROXY TCP4 198.51.100.7 10.0.0.1 51234 9001\r\n`. The destination is the front's own address and port, where nginx
took the connection (seen on the release's nginx, 2026-11-25), not the arena's. The arena uses only the source address
and port, which are the phone's. An arena with a TLS name reads this line first (`ProxyLine`). It keeps the phone's address on the connection
for its logs: `player 123 joined from 198.51.100.7:51234`, and every other log line that names a connection. Then
`ProxyLine` takes itself out of the pipeline, so it never touches a frame after the line.

A connection that begins any other way, or whose line is longer than the protocol's 107 bytes, is closed and counted
as `proxy_line`. Only nginx reaches an arena behind the front, so anything else is not a client. An arena with no TLS
name reads no line. The arena's own TLS, described below, is for an arena reached directly.

### Without TLS: a match through port 80

**A match without TLS goes through port 80**
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).
Port 9001 tells the arenas apart by the name in the TLS handshake. A client without TLS has no handshake, so it cannot
send that name. Instead it dials the API's own address on port 80 and asks for the arena by the grant's `tlsName`:

```
GET /match/a1.arena.internal HTTP/1.1
Host: <the site's address>
Connection: Upgrade
Upgrade: backend-match
```

What happens next, in order:

1. **nginx** maps the name to the arena by the same list that 9001 uses (`backend-arenas.conf`). A name that is not in
   the list gets `404`. For a known name, nginx passes the request on to the arena. It sets `X-Forwarded-For` to the
   phone's address and sends none of the phone's own headers.
2. **The arena's `ProxyLine`** accepts either the PROXY line or this request as the first thing on a connection. For
   the request it reads the request line, `GET /match/<name> HTTP/1.1`, and the headers up to the blank line: 1 KiB at
   most. It takes the phone's address from the one `X-Forwarded-For` header.
3. **The arena answers** `HTTP/1.1 101 Switching Protocols`, with `Connection: Upgrade` and `Upgrade: backend-match`.
   nginx passes the answer back to the client. `ProxyLine` then steps out of the pipeline, as it does after the line.
4. **The frames follow, as on 9001.** The client sends `Join` once it has read the `101`.

Anything else as the first bytes is closed and counted as `proxy_line`, as before. The log names the phone's address
without a port (`player 123 joined from 198.51.100.7`), because nginx's HTTP proxy sends no PROXY line. The grant does
not change: the client without TLS reads its `tlsName` as the arena's name. nginx waits up to 60 s for data on this
path (`proxy_read_timeout`), longer than the arena's own 30 s limit, so the arena is the one that decides a connection
is dead, as on 9001. nginx answers `/match/` on 443 too, but no client uses it there.

### An arena with TLS of its own

**TLS in the arena is built** (2026-09-26, [S-6](../defects.md#5-security-and-input)). An arena given a keystore
serves TLS on every connection from the first byte. It says so in the directory, and the grant from `platform` then
carries `"tls": true`. An arena behind the front announces TLS too, through its TLS name.

**The client connects with TLS exactly when the grant says so.** It verifies the certificate against `tlsName`, or
against `arenaHost` when the grant has no `tlsName`. Trusting any certificate would make the encryption decorative.
A plaintext client that reaches a TLS arena is dropped by the handshake before its `Join` is read, so its ticket is not
spent. The arena refuses to start when its certificate does not name the host it advertises, since every client would
then refuse it ([operations/01 §8](../operations/01-deploy.md#8-tls-for-match-traffic)).

**The arena uses the JDK's TLS provider**, not the `netty-tcnative-boringssl-static` that this section first named.
That library is native code, one build for each platform. It is not in the offline bundle, and it buys speed that
nobody has measured a need for.

**TLS 1.2 is accepted as well as 1.3.** Android before version 10 has no TLS 1.3 in its platform. Unity's own TLS
layer has historically gone no further than 1.2. Which version the client ends up on is for whoever builds it to
confirm: Unity's TLS, in the editor and on a phone, is not checked here
([D-108](../architecture/03-decision-log.md#d-108--the-client-for-unity-20194-and-net-framework-c-73-a-certificate-check-of-its-own-an-rsa-root)).
Each frame is one `writeAndFlush`, so it is one TLS record. A record costs 22 bytes on TLS 1.3 (RFC 8446 §5.2) and 29
bytes on TLS 1.2 with AES-GCM (RFC 5288). Both fit inside the per-packet overhead that the bandwidth budget in §4
assumes.

### Closing connections that do nothing useful

**The arena closes a connection that does nothing useful** ([S-9](../defects.md#5-security-and-input)). There are
three limits:

| Limit | Why | Counted as |
|---|---|---|
| **`Join` (or `Resume`) within 10 s** of connecting, the TLS handshake included | Without it, a connection that pinged and never joined kept its socket for ever. | `join_deadline`; `tls_handshake` if the connection is still in its handshake at the deadline |
| **A whole frame at least every 30 s** | A client in the foreground sends ten `Input` frames a second, and **every client sends `Ping` every 10 s in every state, dead or alive**. So 30 s is three missed pings. A frame sent one byte at a time is not activity. | `idle` |
| **Unwritable for at most 5 s** in a row ([§11](#11-backpressure)) | Nothing sent to the client is draining. | `stalled` |

Each close is counted in `backend_arena_connections_dropped_total{reason}`. The reasons are `join_deadline`, `idle`,
`stalled` and `tls_handshake` (still in the handshake at the deadline, or a handshake that failed). Behind the front
there is also `proxy_line`, for a connection that did not begin with nginx's line or request (D-105, D-115). A client in the background is silent, so the arena drops it after 30 s:
see [§10](#10-session-reconnect-and-app-lifecycle).

### Socket options and the frame handler

The socket options are `TCP_NODELAY=true` (Nagle's algorithm would add up to 40 ms to a 15 Hz stream),
`SO_KEEPALIVE=true`, `WRITE_BUFFER_WATER_MARK(32 KiB, 128 KiB)` and the `PooledByteBufAllocator`. The listening socket
has `SO_REUSEADDR` and a backlog of 1 024.

The event loops are Netty's `MultiThreadIoEventLoopGroup` on epoll, or on NIO where epoll is not available.

**`MatchFrameHandler` never touches simulation state.** It does four things:

- **It limits the rate** of each connection: 60 frames a second, with a burst of 330 for the backlog of an uplink that
  stalled and then recovered. Past that, it ends the stay and sends `Kick(4)`. 330 is the 303 frames of 30 silent
  seconds (ten `Input`s a second and a `Ping` every ten seconds) with a tenth to spare.
- **It decodes each message.** An `Input` is packed into one atomic `long` on the connection, and the room reads the
  latest one. A skill point asked for goes into a counter for its stat. Decoding an `Input` allocates nothing: the
  server decodes the inputs of 150 tanks per room.
- **It hands joins to the rooms.** A `Join` is claimed from the ticket store and then queued to its room. A `Resume`
  goes straight to its room's queue.
- **It answers a `Ping` at once**, on the network thread, so the round trip does not include a tick.

`VarintFrameDecoder` enforces the frame size. An unknown message type closes the connection. So does a second `Join`
or `Resume` on one connection. An `Input` that arrives before the join is ignored, and the connection is kept.

### Framing

```
[varint length][payload]
```

A varint length costs one byte for anything under 128 bytes, which is almost every message. A frame above 8 KiB is a
protocol error and closes the connection.

### Why TCP now, and what keeps UDP open

On a cellular network, 1–3 % packet loss turns TCP's head-of-line blocking into visible freezes of 200–600 ms. That is
a real problem, and UDP is the real answer. But a custom reliability layer is a multi-month sinkhole. It should be
driven by measurements from real players, not by speculation
([D-11](../architecture/03-decision-log.md#d-11--ack-based-delta-baseline-so-the-transport-stays-swappable)).

**Deltas are measured against the last snapshot the server *sent*, in world space, and the client adds them up**
([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)). That is correct
because TCP delivers every frame exactly once and in order.

This document once specified the opposite: deltas against the last *acknowledged* frame. The server implemented that
but sent nothing to say which frame it was, so no client could decode a frame correctly. A UDP transport would have to
bring that scheme back properly, with the baseline tick on the wire and the baseline state kept for each entity.

Acknowledgement still exists and still matters: a handle is not reused until its removal has been confirmed
([§5](#5-entity-handles)). It has nothing to do with positions.

## 2. Primitives

Every message is built from these types.

| Type | Encoding |
|---|---|
| `u8`, `i16`, `u16`, `i32` | fixed width, little-endian |
| `u32` | fixed width, **big-endian**. It is used in two places. (1) `clientTimeMs` in `Ping` and `Pong`: the server echoes it without reading it, so a client that writes and reads it the same way is right either way. (2) The two floats of the `MotionRule` event (§4): each is an IEEE 754 float's bits, which the client reads (`SnapshotWriter.f32`). Any server code that ever has to *read* a `u32` must know this. None does today; §8 deliberately does not use the round trip. |
| `varint` | LEB128, unsigned |
| `svarint` | zigzag, then LEB128 |
| `handle` | `u8`: a per-client entity slot ([§5](#5-entity-handles)). 0 means "no handle". |
| `pos` | `i16`, world units × 4, relative to the snapshot's view origin |
| `dpos` | `svarint`, world units × 4, a **world-space** delta from the last value sent to this client |
| `angle` | `u8` = (angle + π) / 2π × 256. The offset is the same as `heading`'s, so ±π is 0 and forward (angle 0) is 128. |
| `heading`, `aim` | `u16` = (angle + π) / 2π × 65 536: a full turn in 65 536 steps. The server reads an `Input`'s aim back as aim / 65 536 × 2π − π. |
| `hp` | `u8` = hp / maxHp × 255, and at least 1 while alive: 0 is reserved for dead |
| `string` | `varint` length, then UTF-8. Used in `Join`, `Resume`, `Welcome` and player names. The server reads a ticket or a resume secret as ASCII, 64 bytes at most. |

There is no free-text field anywhere in the match protocol
([D-14](../architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)).

## 3. Messages

Every frame's payload starts with a `u8` message id. The ids are `ClientMessage` and `Wire` in `backend/protocol`.

### Client → server

| id | Message | Payload | Rate |
|---|---|---|---|
| 1 | `Join` | **`u8 protocolVersion` first**, then `string ticketId`, then optionally `u8 profile` (§8) | once |
| 2 | `Input` | `varint seq`, `varint ackTick`, `u8 moveMask`, `u16 aim`, `u8 flags` | 10 packets a second, each one coalescing the two input samples since the last (the latest move and aim, the fire bits OR-ed), **whenever alive and in the foreground, idle or not** (§9) |
| 3 | `UpgradeStat` | `u8 statIndex` | on tap |
| 4 | `ChooseClass` | `u8 classId` | on tap; a class the tank may not have yet is refused silently ([01 §4](01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)) |
| 5 | `Respawn` | — | on tap |
| 6 | `Phrase` | `varint phraseId`, an id in the phrase list ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) | at most 1 per 2 s; one sooner, or an id outside the list, is dropped, never kicked |
| 7 | `Ping` | `u32 clientTimeMs` | every 10 s, in every state: a connection silent for 30 s is closed (§1) |
| 8 | `Lifecycle` | `u8 state` (0 = foregrounded, 1 = backgrounded) | when the app's state changes |
| 9 | `Leave` | — | once; it ends the stay at once, where a lost connection waits for a resume (§10). The client then waits for the arena to close the connection before it closes its own. |
| 10 | `Resume` | `u8 protocolVersion`, `string resumeSecret`, optionally `u8 profile` | in place of `Join`, after a lost connection (§10) |
| 11 | `Sandbox` | `u8 action` (1 a level, 2 a Guardian), `varint value` | in a sandbox only, whose powers it carries ([01 §8.10](01-arena.md#810-sandbox-designed-2026-10-01-plan-item-33)); dropped anywhere else, and when out of range. Only an arena that made a sandbox is sent it, so it needed no new protocol version. |

**An `Input`'s `moveMask` and `flags`** are bits:

| Field | Bit | Name | Meaning |
|---|---|---|---|
| `moveMask` | 1, 2, 4, 8 | `MOVE_UP`, `MOVE_DOWN`, `MOVE_LEFT`, `MOVE_RIGHT` | the direction held; a diagonal is scaled by 0.70710678 so that it is no faster than a straight line |
| `flags` | 1 | `FLAG_FIRE` | a tap, or the trigger held |
| `flags` | 2 | `FLAG_AUTOFIRE` | fire on every reload |
| `flags` | 4 | `FLAG_AUTOSPIN` | **defined and not built** (below) |
| `flags` | 8 | `FLAG_ZOOM` | held (since 2026-09-28): a class with a zoom, the Predator, has its view centred 700 units ahead along its aim for as long as it is held ([01 §4](01-arena.md#the-rest-of-the-tree-designed-2026-09-28-plan-item-5)) |

Zoom does not change the frame's format: the view origin is the view's centre, wherever that is. A server ignores a
bit it does not know. **`FLAG_AUTOSPIN` is defined and not built.** The room reads only `FIRE`, `AUTOFIRE` and `ZOOM`
(`RoomThread.applyInputs`). A client that sets it gets no spin, and the C# client does not send it.

**`contentVersion` is the class table's version** (since 2026-09-28,
[D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)).
It is a CRC-32 of the table's JSON. `platform` serves the table at `GET /v1/content/classes` with the same number. The
version was a constant 1 through three tiers of classes. A client whose table has another version fetches the table
before it draws anything that a class makes.

**The version comes first, and the order matters.** The server reads `protocolVersion` before anything else, so that a
mismatch is refused rather than mis-parsed. This table used to list the ticket first. A client written from it sent
the ticket's length (22) where the version belongs. It failed the check and was sent `KICK_PROTOCOL_VERSION`, the one
reason that this document tells a client never to retry.

**The protocol version is 4** (`Wire.VERSION`, since 2026-09-28):

| Version | What it changed |
|---|---|
| 4 | A unit's create carries its radius too (§4; [01 §4](01-arena.md#the-third-tier-turrets-bodies-hiding-and-minions-designed-2026-09-28-plan-item-5)). |
| 3 | A predicted create carries its radius, and the kind `UNIT` was added, for traps and drones ([01 §4](01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)). |
| 2 | A predicted create carries the speed itself, in half units a tick. |
| 1 | A predicted create carried an index into a table of eight speeds. |

A client of an earlier version is sent `KICK_PROTOCOL_VERSION`. Reading a newer frame, it would misread every create
after the first bullet.

**`profile`** is the most that this client wants to be sent (§8): 0 mobile, 1 high, 2 saver. It is optional because
clients built before it send nothing, and they get mobile. So does a value the server does not know.

**`viewportAspect` is not implemented**, and the server reads no such field. The view rectangle is 1 600 units square,
times the view of the tank's class (a Sniper's is 1 920,
[01 §4](01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)), whatever the device. Sizing
the view to the device is worth doing, because a phone in portrait sees far less of the world than a tablet. Nothing
does it today.

**`ackTick` rides on `Input`.** It is the tick of the most recent snapshot that the client has fully applied. It no
longer decides the delta baseline
([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)). What it still
decides is handle reuse ([§5](#5-entity-handles)). Putting it in `Input` costs nothing, since `Input` is already the
most frequent message from the client.

### Server → client

| id | Message | Payload |
|---|---|---|
| 1 | `Welcome` | the fields below, in this order |
| 2 | `Snapshot` | [§4](#4-the-snapshot) |
| 3 | `Pong` | `u32 clientTimeMs`, `varint serverTick` (0 before the connection has joined a room) |
| 4 | `Kick` | `u8 reason` |

**The `Welcome`'s fields**, in order (`RoomThread.sendWelcome`):

| Field | Meaning |
|---|---|
| `handle yourHandle` | always `Wire.SELF_HANDLE` (1) |
| `u8 snapshotHz` | the rate it starts at. The rate changes with the profile (§8), so a client times frames by `tickDelta`. |
| `varint mapW, mapH` | the map's size, in world units |
| `u8 mode` | 0 the public arena, 1 a duel, 2 team-vs-team, 3 ranked free-for-all, 4 co-op, 5 a team match, 6 domination, 7 tag, 8 maze, 9 a sandbox (`handoff/MatchMode`) |
| `varint contentVersion` | the class table's hash (above) |
| `varint phraseListVersion` | the phrase list's hash, as `contentVersion` is the class table's |
| `varint selfEntityId` | the own tank's entity id on the server |
| `string resumeSecret` | 128 random bits as 22 characters of base64url, for a resume (§10) |
| `varint mazeSeed` | 0 for none. A maze's walls are made from it ([D-48](../architecture/03-decision-log.md#d-48--a-maze-is-sent-as-a-seed-and-a-predicted-bullet-stops-at-its-walls-by-one-rule-on-both-sides)). It was appended last, so a client reads it when the frame has it. |

`Pong` echoes the client's own `clientTimeMs` unchanged; it does not stamp a new time. The client measures the round
trip against its own clock, and the two clocks are not related to each other.

**Kick reasons.** A bare disconnect tells the client nothing, and the three common refusals each need a different
response. So the server sends the reason before it closes the socket, and the close waits for the write: up to 2 s
(§11).

| reason | Meaning | What the client does |
|---|---|---|
| 1 | The ticket is unknown, used, expired or malformed; or a `Resume` names a stay that is unknown or over | back to the lobby for a new ticket |
| 2 | No room has space (or the room's join queue is full) | queue again; the ticket is already spent |
| 3 | The protocol version does not match | prompt the player to update the app; never retry |
| 4 | The rate limit was exceeded | a client bug: log it, do not loop |
| 5 | A fault on the server's side | retry with backoff |
| 6 | The match is over: a match the matcher made (04 §4) has ended, and its room has closed | back to the lobby, as after `Leave`. Not an error, and there is nothing to reconnect to. |
| 7 | Removed by an operator: the room was closed, or the player was taken out ([04 §10](04-platform-services.md#the-second-slice-rooms-designed-2026-09-29-plan-item-7)); a join refused just after that is told the same | back to the lobby; there is nothing to reconnect to. An older client reads it as 5, which leads to the same place. |

Reason 5 is also what a player gets in these cases: their frame could not be encoded; their room closed itself after
repeated faults ([07 §8](07-threading-and-performance.md#8-stability-safeguards)); the ticket store could not be
reached when the join was claimed; or a room could not take a resume. In every case the answer is the same: back
through the lobby, with backoff.

Reason 5 exists so that a store outage does not look like reason 1. Sending a player to fetch another ticket from a
store that is down produces a storm of retries against the component that is already failing.

**An empty frame closes the connection.** A zero where a frame length belongs means that the stream's framing is
already broken. Closing is safer than guessing where the next frame starts. This is deliberate, like the close on an
unknown message type.

Deaths, kills, level-ups, phrase messages and mode events are **not** separate messages. They ride inside the
snapshot's event section, so a tick costs one packet rather than several. At 71 bytes of IP, TCP and TLS overhead per
packet, a second packet costs more than the event it carries.

## 4. The snapshot

The arena sends each client a snapshot at the rate of its profile (§8): 15 Hz for `mobile` and `high`, 10 Hz for
`saver`. The simulation runs at 25 Hz
([D-10](../architecture/03-decision-log.md#d-10--tanks-drive-the-snapshot-rate-at-15-hz)).

```
u8      type = 2
varint  tickDelta            ticks since the previous snapshot to this client
varint  inputSeqDelta        advance of lastProcessedInputSeq, modulo 2^24 (§9)
svarint viewOriginDX, DY     movement of the view origin since the last snapshot

varint  removeCount
        [ handle ]*

varint  createCount
        [ handle, u8 kind,
          kind == TANK:      pos x, y, angle, hp, u8 classId, u8 team, u8 level, string name
          kind == PREDICTED: pos x, y, u16 heading, u8 speed, u8 spawnTickOffset, u8 lifetimeTicks, handle owner, u8 radius
          kind == STATIC:    pos x, y, u8 subtype, u8 spawnAngle
          kind == UNIT:      pos x, y, angle, hp, u8 subtype (1 trap, 2 drone, 3 minion, 4 rocket, 5 skimmer), u8 team, handle owner, u8 radius ]*
        (PREDICTED: pos is where it was spawnTickOffset ticks before this frame's tick,
         and lifetimeTicks counts from then. This server always sends 0: pos is where it is
         now, and lifetimeTicks what remains.)

varint  updateCount
        [ handle, u8 fieldMask,
          POS(dpos dx, dy) | ANGLE(u8) | HP(u8) | LEVEL(u8) | CLASS(u8) | TEAM(u8) | FLAGS(u8) ]*
        (TEAM when a team changes in place, a dominator captured: P-60)

varint  eventCount
        [ u8 type, varint payloadBytes, payload ]*
```

The `kind` byte is 0 `TANK`, 1 `PREDICTED`, 2 `STATIC` and 3 `UNIT`. An update's fields follow its `fieldMask` in the
order shown, each present when its bit is set:

| Bit | Field | Value |
|---|---|---|
| 1 | `POS` | `dpos dx, dy` |
| 2 | `ANGLE` | `u8` |
| 4 | `HP` | `u8` |
| 8 | `LEVEL` | `u8` |
| 16 | `CLASS` | `u8` |
| 32 | `TEAM` | `u8` |
| 64 | `FLAGS` | `u8`: `TANK_FLAG_PROTECTED` (1), `TANK_FLAG_HIDDEN` (2) |

**A create's `pos` is relative to this frame's view origin**, so that it fits an `i16` on any map size. **An update's
`dpos` is in world space.** A client that keeps world space throughout, and converts once on the create, needs no
other rule. Scenery then stays where it was put as the camera moves over it.

**The client's own tank is in its own snapshot**, at `Wire.SELF_HANDLE` (1), which is what `Welcome.yourHandle`
carries. The server reserves that handle for the own tank. Until 2026-09-26 it was only the first handle handed out,
and another tank within four units on a lower slot could take it. A joining player was then left without their own
tank ([P-15](../defects.md#2-protocol--the-client-contract)). The snapshot is the only place a client learns its own
health. There is no trailer for the own tank: an earlier draft of this document specified one, and nothing ever wrote
it. Progression reaches the client as `EVT_STATS` instead.

**A tank's `LEVEL` is updated when it changes** (since 2026-09-26). Before that, a client knew an opponent's level only
as it was when the tank came into view. **A shape's updates never carry `ANGLE`.** The client turns the shape from its
create, as §6 says. Sending the server's cosmetic spin was a fifth of a calm frame
([P-16](../defects.md#2-protocol--the-client-contract)).

**A tank's `FLAGS` carries `TANK_FLAG_PROTECTED` (1)** while it is in its 3 s of spawn protection
([01 §7](01-arena.md#7-deaths-respawn-spectate)). In that time it cannot be hurt and cannot shoot. The client should
show this. Otherwise a player whose shots do nothing, or whose own fire button does nothing, is left guessing. A create
has no flags field. So a tank that arrives protected is told so in the next frame's update, 67 ms later (100 ms at
`saver`), and another update clears the flag. The player's own tank gets the flag too. Its fire input is ignored until
protection ends, and a held trigger fires the moment it does.

**`TANK_FLAG_HIDDEN` (2)** is set on the player's own tank while it is hidden
([D-23](../architecture/03-decision-log.md#d-23--a-hidden-tank-is-not-sent)): a Stalker, Manager or Landmine that has
stood still, with the trigger let go, for two seconds. Nobody else sees the flag, because nobody else is sent the tank.
The tank leaves their frames with a remove, as if it had left their view. It comes back with a create when it moves or
shoots. Its bullets and drones are sent as usual.

**A create can arrive for a handle the client already holds.** The server sends a create again
(`SnapshotEncoder.writeCreates`) when both of these are true: the client has not acknowledged the frame that carried
the first create within 15 ticks, and the client's acknowledgement has not moved for 15 ticks either (50 ticks before
its first acknowledgement, so that the opening burst is not sent twice to a distant client). Treat the second create
as a replacement. A client that acknowledges as it should never sees one. Until 2026-09-26, one that did saw a create
for every moving entity every 15 ticks.

**A create's `owner`** (on a predicted entity or a unit) is the handle this client holds for the tank that fired it,
as that tank was when it fired. It is 0 once that tank is gone (dead or left), even when its pool slot now holds
something else this client can see. It is also 0 while the tank is out of this client's view. A handle stands for one
incarnation of a slot (§5), so the next occupant of a slot is never named as the owner of what the last one fired.

**A predicted create's `speed` is in half units a tick**, exactly: 20 is 10 units a tick. The simulation fires every
bullet at a whole number of half units, so nothing is rounded, and a client extrapolates from it for the bullet's
whole life (D-9). **Its `radius`** is in world units: 8 for most bullets, 16 for a Destroyer's, 4.8 rounded to 5 for a
Gunner's.

**A `UNIT`** is one of these, by `subtype`: a trap (1); a drone (2); a minion (3), a drone that shoots, whose bullets
are ordinary predicted creates; or a missile that fires as it flies, a Rocketeer's (4) or a Skimmer's (5). Its create
carries its owner's handle and, since protocol 4, its **radius**, in world units as a predicted create's is: 12 for a
Trapper's trap, 10 for an Overseer's drone. Protocol 3 left the radius out. The reasoning was that a client could look
a unit's size up by its owner's class. It cannot, because a trap outlives its owner, whose handle is then gone
([P-30](../defects.md#2-protocol--the-client-contract)). Neither a trap nor a drone can be extrapolated: a trap slows
to a stop, and a drone is steered. So a unit is updated as a tank is, with `POS`, `ANGLE` and `HP` when they change
([01 §4](01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)). A unit ranks
with the bullets for the entity budget.

**A tank's `classId`** is its class
([01 §4](01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)). Ids are appended and never
moved:

| Ids | Classes |
|---|---|
| 0 | Basic |
| 1–4, at level 15 | 1 Twin, 2 Sniper, 3 Machine Gun, 4 Flank Guard |
| 5–14, at level 30 | 5 Triple Shot, 6 Quad Tank, 7 Twin Flank, 8 Assassin, 9 Hunter, 10 Destroyer, 11 Gunner, 12 Tri-Angle, 13 Trapper, 14 Overseer |
| 15–48, at level 45 (except where noted) | 15 Triplet, 16 Penta Shot, 17 Spread Shot, 18 Octo Tank, 19 Triple Twin, 20 Ranger, 21 Predator, 22 Streamliner, 23 Sprayer, 24 Annihilator, 25 Booster, 26 Fighter, 27 Overlord, 28 Tri-Trapper, 29 Mega Trapper, 30 Gunner Trapper, 31 Overtrapper, 32 Hybrid, 33 Auto 3 (at level 30), 34 Auto 5, 35 Auto Gunner, 36 Auto Trapper, 37 Smasher (at level 30, from Basic), 38 Spike, 39 Auto Smasher, 40 Stalker, 41 Manager, 42 Landmine, 43 Necromancer, 44 Factory, 45 Battleship, 46 Rocketeer, 47 Skimmer, 48 Mega Smasher |
| 49–51, no player can choose them | the arena gives them to its own tanks: 49 Guardian and 50 Guardian, enraged, co-op's boss ([01 §8.5](01-arena.md#85-co-op-waves-designed-2026-09-29-plan-item-6)); 51 Dominator ([01 §8.7](01-arena.md#87-domination-designed-2026-10-01-plan-item-30)) |

A tank's size is not sent: it is its class's, as its barrels are. **A turret's aim is not sent.** Its shots are
bullets with their own heading, and a client draws a turret pointing where it last fired. The create carries the
class, and an update's `CLASS` carries a change, once.

**`string name` on a tank's create is its player's display name** (since 2026-10-01, plan item 38,
[D-52](../architecture/03-decision-log.md#d-52--a-tanks-name-travels-in-its-create)). It is empty for a tank that
nobody plays (the arena's own), and for a name over 64 bytes, which is dropped rather than cut through a character, as
an event's name is. Measured with `TickBenchmark`, every tank named in eight bytes, two runs each way: no change at the
design density (28.9 bytes a snapshot), and +0.3 bytes (114.7 to 115.0) with twelve tanks to a view on a 5 700-unit
map. Before that, the name was a placeholder, always empty ("names arrive with the join"), and nothing sent them
([P-35](../defects.md#2-protocol--the-client-contract)). `classId` was also a placeholder, always 0, until 2026-09-27.
`inputSeqDelta` was the third, until 2026-09-26 ([P-9](../defects.md#2-protocol--the-client-contract)); it is now
filled in, see §9.

### Event types

| type | Name | Payload | Sent to |
|---|---|---|---|
| 1 | `Death` | `varint score`, `u8 nameLength`, UTF-8 killer name (length 0 = nobody) | the player who died |
| 2 | `Stats` | see `EVT_STATS` under "Sizes" below | the player it describes |
| 3 | `Phrase` | `u8 handle` (the speaker's in this client's view; 0 = none), `varint phraseId`, `u8 nameLength`, UTF-8 speaker's name | in a mode with teams, the speaker's team (in tag, the team the speaker is on now); otherwise those whose view holds the speaker ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) |
| 4 | `Kill` | `u8 nameLength`, UTF-8 killer's name, `u8 nameLength`, UTF-8 victim's name (length 0: nobody's) | its killer, in every room; in a made match, every player ([01 §9](01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37)) |
| 5 | `Motion` | `u8 inputTicks` (the ticks that the input this frame echoes has driven the tank, through this frame's tick; at most 255), `svarint vx, vy` (its velocity after that tick, in 1/256 world unit a tick) | the player whose tank it is, in every frame while it lives (§9, [D-62](../architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry)) |
| 6 | `MotionRule` | `f32 accel` (what the input's direction is multiplied by each tick), `f32 radius`; each an IEEE 754 float's bits as a big-endian `u32`, the server's own value | the same player, when either value differs from what its view was last told: so in a view's first frame, and after a spawn, a level, a point spent or a class chosen (§9) |
| 7 | `Skin` | `u8 handle` (a tank created in this frame), `u8 skin` (its number in the skin table, `GET /v1/content/skins`) | each client the tank is created for, in the frame with the create, for a tank with a skin only ([04 §8](04-platform-services.md#revenue-designed-2026-10-04-plan-item-75), [D-70](../architecture/03-decision-log.md#d-70--a-tanks-skin-travels-in-its-own-ticket-field-and-is-told-by-an-event-beside-the-tanks-create)) |

**Every event carries its byte length**, so a client can step over a type it has never heard of. Without the length,
the first event type added after a release would desynchronise every older client in the middle of a snapshot. And a
mobile client cannot be forced onto a new build on the day it ships. A name longer than 64 bytes is dropped rather
than truncated, because cutting UTF-8 at a byte boundary splits a code point and shows a broken character.

**Built**, in order: `Death` and `Stats`; `Phrase` since 2026-09-29
([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)); `Kill`, the kill feed, since 2026-10-01
([01 §9](01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37)); `Motion` and `MotionRule`, for the prediction of
the own tank, since 2026-10-03 (§9; plan item 70); and `Skin`, a tank's look sent with its create, since 2026-10-04
(plan item 75 (c), D-70). **Mode events are designed and not built.**

**The order of the sections matters.** Removes come first, so that handles are freed before creates allocate them.
Creates come next, so that updates can refer to them.

### Sizes

| Item | Bytes | Notes |
|---|---|---|
| Header | ~6 | four small varints |
| Tank update | ~6 | handle 1, mask 1, dx 1–2, dy 1–2, angle 1, hp occasionally |
| Predicted create | ~13 | handle, kind, position, heading, speed, spawn offset, lifetime, owner, radius |
| Remove | 1 | just the handle |
| Event | 3–6 | type plus a couple of fields |

A typical snapshot for a mobile client has 12 visible tanks, three bullets created, three expired and one event:

```
6 + (12 × 6) + (3 × 13) + (3 × 1) + 5  ≈ 125 bytes by hand
```

**Measured: 122 bytes.** The reference encoder in [`protocol-spike/`](../../protocol-spike/README.md) encodes exactly
this frame (the golden vector `realistic_mobile`) and reports 122, because several varints are shorter than the worst
case assumed above. The budget below uses the measured figure.

The frame was 119 bytes until protocol 3 gave each bullet's create its radius: one byte a bullet, since bullets now
come in sizes ([01 §4](01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)).
It was 117 bytes until the event gained its byte-length prefix. Those two bytes, on a frame that carries an event, are
what it costs a client to be able to **skip an event type it has never heard of** (see "Every event carries its byte
length" above).

Each packet adds about 71 bytes of IP, TCP, TLS and framing overhead:

| | Value |
|---|---|
| Down | 193 B × 15 Hz = **2.90 KB/s** |
| Up | 87 B × 10 packets/s = **0.87 KB/s** |
| **Total** | **~3.8 KB/s ≈ 13.6 MB/hour** |

That is within NFR-2's 15 MB/hour, with about 9 % headroom. This frame carries an event, and most frames do not,
because an event is sent only to the player it concerns.

**Event types, and what each costs.** There are seven.

| Type | Payload | Sent when |
|---|---|---|
| `EVT_DEATH` | `varint score, u8 nameLength, bytes killerName` | once, to the player who died |
| `EVT_STATS` | `varint level, varint xp, varint xpForNext, u8 unspentPoints, u8[8] points` | to one player, when their own progression changes |
| `EVT_PHRASE` | `u8 handle, varint phraseId, u8 nameLength, bytes name` | when someone the client may hear speaks: its team, or a tank in its view |
| `EVT_KILL` | `u8 nameLength, bytes killer, u8 nameLength, bytes victim` | a tank killed: to its killer; in a made match, to every player |
| `EVT_MOTION` | `u8 inputTicks, svarint vx, svarint vy` | every frame, to the player whose tank lives |
| `EVT_MOTION_RULE` | `f32 accel, f32 radius` | to the same player, when either changes |
| `EVT_SKIN` | `u8 handle, u8 skin` | with the create of a tank that has a skin, to each client it is created for |

What each event costs, framed (type, length and payload):

- **`EVT_SKIN`** is four bytes, once for each create of a tank with a skin. That is a tank coming into view, not a
  steady cost per frame.
- **`EVT_MOTION`** is about seven bytes for a moving tank: the type, the length, the input's ticks, and each velocity
  as a two-byte varint. A velocity takes two bytes from a quarter of a unit a tick to 32 units; one byte below that,
  and three above, as after a hard knock. It is in every frame, so it costs about 105 B/s at 15 Hz, some 4 % of
  `mobile`'s budget. It is what lets the own tank move at once (§9).
  **Measured** (2026-10-03, plan item 70, 150 bots for two minutes, back to back with the release before it): the bots'
  mean frame went from 77.0 bytes to 83.2, 6.2 more, as a still or slow tank's velocity takes a byte. Each
  connection's mean went from 1 148 B/s to 1 242, 94 more (8 %). p50 and p95 stayed in the same histogram buckets as
  before (1 250, 1 475). Encoding and writing a snapshot round took a median of 2.2 ms before and 2.1 ms after.
- **`EVT_MOTION_RULE`** is ten bytes, sent at a spawn, a level, a point or a class: a few times a life.
- **`EVT_KILL`** is about 20 bytes with two eight-byte names. In the public arena only the killer gets one, a few a
  minute at most. In a made match every player gets every kill, with two to eight players. Not measured with
  `TickBenchmark`, whose rooms have no connections and so no events.
- **`EVT_PHRASE`** is about 13 bytes with an eight-byte name. A speaker may say one every two seconds. So the twelve
  tanks of a view all speaking at once cost about 80 B/s, 3 % of `mobile`. A phrase is rarely said, and far less is
  usual.
- **`EVT_STATS`** is about 15 bytes. It goes out **at once** on a level or a spent point, and at most **once a
  second** while only experience is moving. The limit has a reason: experience changes every time a shape dies.
  Streaming it at 15 Hz would cost roughly 225 B/s, about 8 % of the entire downstream budget, to animate a progress
  bar. A client can interpolate the bar between updates; it cannot invent a level.

The headroom has narrowed twice as the design became more precise. If it reaches zero, the entity budget gives way
before the send rate does, because players feel the send rate. Note what dominates: at this payload size, **packet
overhead is 37 % of downstream traffic**. That is why the send rate matters more than shaving bytes off single fields.

## 5. Entity handles

Each client has its own slot table, which maps a `u8` handle to a server entity id. The wire never carries a 32-bit
entity id. Handle 0 means "no handle", so 255 handles are usable.

With an entity budget of 30–60 and 256 slots there is plenty of room, and the saving is large: three bytes per entity
per tick. At 12 tanks and 15 Hz that is 540 B/s per client, a fifth of the entire downstream budget.

**A handle is not reused until its remove has been acknowledged.** Otherwise a client that missed the remove would
apply updates meant for a new entity to the old one. The server keeps a free list and a set of pending removes for
each client. Deltas are based on what was sent since
[D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent), so handle reuse is
the one thing the acknowledgement still decides.

**A handle stands for one entity, not one pool slot.** Entity ids are slots, and slots are reused. Frames go out on 15
(or 10) of every 25 ticks, so between two frames a bullet can die and a new one take its slot. The server therefore
keys a handle by slot *and* generation. A slot that now holds a different entity is sent as a remove and a create in
the same frame, never as an update. When handles were matched by slot alone, the new bullet was never created, and the
client kept drawing the old one along its old path. Measured: wrong by the tenth frame of a busy room
(`SnapshotRoundTripTest.productionCadence`).

**The client keeps its world across a death, a respawn and a time in the background.** While a player is dead or in
the background, the server sends events at most. Events move no tick, and the server keeps that client's handle table.
When the player is back, frames resume from where they stopped: the tick and camera deltas count from the last frame
sent, and everything that changed in the meantime arrives as ordinary removes, creates and updates.

After a respawn, that first frame removes handle 1 and creates the new tank at handle 1. That is the one exception to
waiting for the acknowledgement. It is safe because the remove and the create are in the same frame, and removes are
applied first. The same holds across the boundary between two timed matches: every entity is removed and the new
match's entities are created, in ordinary frames.

**A client must not clear its world on a death, a respawn or a new match.** The server used to start a new table at
these moments, and the client's tick and camera jumped by the whole time that had passed
([P-6](../defects.md#2-protocol--the-client-contract)).

## 6. Client-side simulation

This is the largest bandwidth decision in the system
([D-9](../architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client)). A client sees
roughly 12 tanks, 45 bullets and 45 shapes. Bullets are deterministic between events, so they are never updated, only
created and destroyed. A shape is updated only when its quantised position changes.

| Kind | Streamed? | Client behaviour |
|---|---|---|
| `TANK` | yes, 15 Hz | interpolate between the last two snapshots |
| `PREDICTED` (bullets; as built, nothing else, since traps, drones and missiles are `UNIT`s) | create and destroy only | extrapolate from position, velocity and spawn tick; despawn at `lifetimeTicks` if no destroy arrives |
| `STATIC` (shapes) | create, destroy, **and position updates when they move**: shapes drift slowly and are knocked by bullets, and the drift alone was measured at 0.6 bytes a snapshot | rotate locally from `spawnAngle`, at any rate: nothing collides with a shape's facing, so the two sides need not agree |
| `UNIT` (traps, drones, minions, missiles) | yes, as a tank: `POS`, `ANGLE`, `HP` when they change | interpolate as a tank (below) |

### The contract the client must honour

1. **Extrapolate; do not simulate physics.** A `PREDICTED` entity moves in a straight line at a constant velocity. It
   does not collide on the client. The server decides what it hits and sends the destroy.
2. **A destroy is authoritative.** When a destroy arrives, remove the entity at once, wherever the client had drawn
   it. A small visual pop is acceptable. It is the same kind of artefact as interpolation delay.
3. **Expire on time.** If no destroy arrives by `lifetimeTicks`, remove the entity anyway. This bounds the damage
   from a lost event.

### Entities that are not predictable

Steering drones change direction, so extrapolating them diverges. The design was to mark them `TANK` and stream them
at a reduced rate, rather than give them a third behaviour. As built (protocol 4), traps, drones, minions and missiles
are `UNIT`s, a kind of their own, updated as tanks are (§4). **The kind byte is the only thing that decides the
client's behaviour.** That keeps the client simple, and it lets the server move an entity type to another kind
without a client release.

### Corrections

**Not built.** The encoder sends no update for a `PREDICTED` entity, and no predicted entity lives long enough to need
one yet. As designed: a long-lived `PREDICTED` entity receives a correction update at most once a second, a position
re-sync of about 5 bytes. Short-lived bullets never live long enough to need one. This bounds the divergence that
builds up, without a cost on every tick.

### Measured: how wrong does extrapolation get?

This was simulated over a full bullet life of 75 ticks, taking the worst case across all firing angles, at three
bullet speeds. The screen scale is a phone showing 1 600 world units across 1 080 pixels (0.675 px per unit).

| Velocity encoding | Bytes | Max drift |
|---|---|---|
| `i8` at 1.0 unit/tick | 2 | **33 px**: unusable |
| `i8` at 0.25 unit/tick | 2 | 8.6 px, and it caps speed at ±31.7 units/tick |
| `i16` at 0.0625 | 4 | 2.3 px |
| **`u16` angle + `u8` speed** | **3** | **0.3 px** |

The polar encoding wins on both counts: one byte smaller than the Cartesian `i16`, and seven times more accurate. The
reason is that a bullet's speed is a discrete value. It travels exactly, not as a quantised measurement, so only the
heading has to be encoded, and 16 bits of heading over 750 units of travel is a fraction of a pixel. When this was
measured, the speed was an index into the stat table's eight speeds. Since protocol 2 it is the speed in half units a
tick, at which the simulation fires, because a class's barrels make more speeds than a table could hold.

**Quantisation is not the main error. A late destroy is.** A bullet keeps flying on the client until the destroy
arrives, which is half a round trip (RTT/2) late:

| RTT | Overshoot at 10 units/tick |
|---|---|
| 50 ms | 4 px |
| 100 ms | 8 px |
| 200 ms | 17 px |
| 400 ms | 34 px |

At realistic mobile latency the overshoot is one to two orders of magnitude larger than any encoding error, so more
precision than the polar scheme gives buys nothing. Two consequences for the client:

- **Draw the hit effect where the bullet is at the remove frame's tick**, extrapolated to that tick, not where the
  bullet was last drawn: a remove carries only a handle. The player looks at the explosion, and it should be as true
  as the frame allows, even when the projectile was not.
- **Do not predict hits on the client.** Hiding a bullet early, on a guessed collision, trades a visible overshoot for
  an invisible-bullet bug, which is worse: a bullet that vanishes and then damages nobody reads as a lost shot.

Clock skew is a third-order error. A 20 ms error in the client's estimate of the tick moves an extrapolated bullet
3.4 px at 10 units/tick, and the `Ping`/`Pong` exchange keeps the error well inside that.

**Status:** the numerical half of the Phase 0 spike is done, and D-9 survives it. The perceptual half, whether the
late-destroy overshoot *looks* wrong in motion, still needs a person with the Unity spike.

## 7. Interest management

*Interest management* decides which entities each client is sent. It runs for each client, for each snapshot, on the
room thread:

1. **The view rectangle** is the tank's position ± half of (viewW, viewH). **As built**, it is 1 600 units square times
   the view of the tank's class, `fovMul` (a Sniper's is 1 920, a Ranger's 2 560; `RoomThread.sendSnapshots`). It is
   centred 700 units ahead along the aim while a Predator holds zoom (§3). There is no margin and no declared aspect.
2. **Query the spatial hash** for the entity ids inside it: first a circle of the rectangle's half diagonal, then the
   rectangle itself (`SnapshotEncoder.select`). A hidden tank is left out of every view but its own player's.
3. **Rank by priority**, not by distance alone. The design was
   `other tanks > bullets threatening me > bullets > shapes`. **As built, there are three classes**: tanks; then
   bullets and units (traps, drones, minions, missiles); then shapes. Within each class, entities are ranked by their
   distance from the tank, not from the view's centre, so a zoomed player keeps what is beside it. There is no
   "threatening" class. So when the budget is full, a harmless bullet nearby can take the place of an incoming bullet
   further away.
4. **Truncate to the profile's entity budget** ([§8](#8-traffic-profiles)).
5. **Diff against the client's handle table**, which produces the removes, creates and updates.

Ranking and then truncating, rather than shrinking the view rectangle, keeps a crowded fight readable: the player
loses distant shapes, not the tank that is shooting at them.

## 8. Traffic profiles

**Built 2026-09-26** (`arena/TrafficProfile`, `TrafficControl`;
[D-17](../architecture/03-decision-log.md#d-17--a-client-is-stepped-down-on-queueing-not-on-round-trip-time-or-writability)).
A *traffic profile* sets how often a client is sent a snapshot, and how many entities a snapshot holds at most. There
are three profiles, and a controller for each client moves it between them:

| Profile | Snapshot rate | Entity budget | Down, at design density |
|---|---|---|---|
| `high` | 15 Hz | 60 | ~4.3 KB/s |
| `mobile` (default) | 15 Hz | 30 | ~2.9 KB/s |
| `saver` | 10 Hz | 20 | ~1.6 KB/s |

**The client says the most it wants**, in an optional byte after the ticket in `Join` (§3): `Wire.PROFILE_MOBILE` 0,
`PROFILE_HIGH` 1, `PROFILE_SAVER` 2. If the byte is absent, or holds a value the server does not know, the profile is
`mobile`. So a client built before this, or after it, is served rather than refused. The client starts at that
profile. The server steps it down when its link cannot carry it, and back up, **never above what it asked for**: a
player who chose `saver` in settings stays there.

The `Welcome` carries the rate the client starts at. The rate can change later, so **the client times frames by
`tickDelta`, never by the `Welcome`'s rate**. A step down removes the entities beyond the smaller budget in the next
frame; a step up creates them. Nothing else changes.

**What steps a client down is its queue, not its distance.** A link that cannot carry the stream queues it: each
snapshot waits behind the last, and the player sees an ever older world. Fewer bytes cure that. A link that is only
far away does not queue, so the round trip is not the signal. Neither is socket writability: Netty reports a socket
unwritable only once the kernel's buffer and 128 KiB of its own are full, which is most of a minute of snapshots at
these rates.

The signal is **how long the oldest snapshot not yet acknowledged has been out**, above the shortest acknowledgement
the connection has had. That shortest time is the connection's *floor*. It absorbs the distance, the client's
acknowledgement interval and the server's round. **The floor never rises.** A floor over a two-minute window, as first
built, rose to meet a queue that never emptied. On a link that was slow all session, the view then drifted from two
seconds old to six in ten minutes. A network change that moves the phone's address starts a new connection. One that
keeps the address, such as LTE falling back to 3G, adds less than the 300 ms that counts as queue.

The controller judges each client at each of its rounds:

| Condition | What happens |
|---|---|
| **More than 300 ms queued for half a second** | One step down. No second step within 2 s, so that the first has time to show. |
| **More than a second queued** | Nothing is added: the round is held. The age of the oldest send is not enough here, because it says how long the queue *was*. So a frame sent now is judged by the bytes already out, at the rate the link has been delivering them. Deltas are against the last frame sent ([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)), so a held round only makes the next frame larger. Counted in `backend_arena_snapshots_held_total`. |
| **Two unwritable rounds in a row** | One step down. An unwritable round is never sent, as before (§11). |
| **Ten seconds with at most 200 ms queued** | One step up. A step up is a probe. A probe that fails (a step down within ten seconds of it) doubles the wait before the next one, up to 160 s, so a link that stays slow is not given a hitch every ten seconds. Any other step down starts the wait at ten seconds again. |
| **Before the first acknowledgement** | Nothing is judged, since there is no floor yet. A client that never acknowledges is left at its profile. A death, or the app going to the background, forgets what is in flight, so the silence is not taken for a queue. |

**This depends on the client acknowledging** at 10 Hz whenever it is alive and in the foreground, idle or not (§9).
Acknowledgements ride on `Input`, and an `Input` stream that stops looks the same as a link that has stopped.

**Measured.** In simulation (`TrafficControlTest`, with a delay each way and a downstream capacity):

- Links with room to spare, at 60 ms or 600 ms round trips, are never touched.
- A link at 1.8 KB/s, below mobile's 2.25 and above saver's 1.2, is at `saver` within 2.7 s. The player's view is at
  most 0.8 s old over ten minutes, with six probes of `mobile` in that time.
- At 1.0 and 0.6 KB/s, below even `saver`, held rounds keep the view's age to 1.2 and 1.4 s.

Against the release, 20 bots each behind a shaped link of 900 bytes a second (the bots' open map sends ~1.1 KB/s at
`mobile`):

- Before: the view each player saw was **20.5 s old after a minute, and growing**, and the arena never noticed.
- After: it stayed **under 1 s** for two minutes, with 19 of the 20 at `saver`, each trying `mobile` again less often.
- Behind a 600 ms round trip with room to spare, nothing moved; nor on a healthy link.
- Bots asking for `saver` got 10 frames a second at 0.6 KB/s. Bots asking for `high` got 15 at 2.1 KB/s, with 141-byte
  frames against `mobile`'s 75.

**One transient remains.** Before the first acknowledgement there is no floor, and before a second acknowledgement
there is no rate. So about 1.6 s of the starting profile goes out regardless. On a link slower than `saver`, that
drains at the link's pace, once, at the join.

**Not built: the hard cap in bytes a second** that the design also named. It would shrink the entity budget in a
crowded fight to protect a player's data bill. The budget already bounds a frame (under 400 B at 30 entities,
`SnapshotEncoderTest`). A second loop moving the budget would churn creates, which cost more than the updates they
replace. And the budget holds without it: 150 bots in one room at design density, every one firing, cost **1.17 KB/s
of payload each, ~2.24 KB/s on the wire**, against `mobile`'s 2.85 (plan, measurements).
`backend_arena_snapshot_bytes_total{profile}` and `backend_arena_clients{profile}` say what real players cost. Decide
from those, at a real fight's density.

## 9. Input, prediction and reconciliation

- **The client sends one `Input` every 100 ms**, ten a second (`InputEveryMs`), with the movement and aim held at
  that moment and a fire tap latched since the last one, so a tap between two sends is not lost. Sending costs a
  phone's radio more battery than receiving, so the client sends no more often than that.
- **Those ten packets go out whenever the tank is alive and the app is in the foreground, even when nothing changed.**
  The acknowledgement rides on them, and the server reads a client that stops acknowledging as a link that has
  stopped: after a second it sends that client less, then nothing more until the acknowledgements catch up (§8). Also,
  a tank whose input has been silent for a second is parked, as a backgrounded one is, until input comes again. The
  last input no longer drives the tank on its own ([P-23](../defects.md#2-protocol--the-client-contract)). Dead or in
  the background, the client sends no input, and nothing is judged.
- **The room coalesces further, each tick.** It keeps the last input, but ORs the fire bit, so a tap between two ticks
  is never lost.
- **`seq` is echoed as `lastProcessedInputSeq`**, so the client can replay its unacknowledged inputs onto its
  predicted tank. Other tanks are drawn 1–2 snapshots in the past.
- **What the echo means, exactly.** Inputs coalesce, so the server does not apply every input. It applies whichever
  input is the latest when a tick starts. `lastProcessedInputSeq` in the snapshot for tick T is the seq of the input
  that drove the tank during tick T, and the tank's position in that snapshot includes it. The client discards its
  buffered inputs up to and including that seq, sets its tank to the server's position, and applies the rest again.
  An input the server skipped is replaced by a later one, which is how the server treated it too. Before any input
  arrives, the value is 0.
- **`seq` is 24 bits and wraps.** The client's counter runs modulo 2^24. The snapshot carries the advance modulo 2^24,
  so it is never negative, and the client adds it modulo 2^24. To ask "is a newer than b", use
  `((a - b) & 0xFFFFFF)` in `1 .. 2^23 - 1`, never `a > b`. At ten packets a second the counter wraps after about
  nineteen days. An open match on a server that is never restarted can get there.
- **The echo survives a death, a respawn and the background**, like the rest of the view (§5). While the tank is dead
  or parked, no input drives it, and the value holds still.
- **Where in the input's span the tick fell** (since 2026-10-03, plan item 70,
  [D-62](../architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry)).
  Inputs go at 10 Hz and the room ticks at 25 Hz, so one input drives two or three ticks. The echo alone does not say
  which of them a frame's tick was. `Motion`'s `inputTicks` does: it is the number of ticks the echoed input has driven
  the tank, this one included. The view counts it each time `takeInput` hands the room the same seq again, and resets
  it to 1 at a new seq; it stops at 255. It does not change while the tank is parked or its input is stale, as nothing
  drives the tank then.
- **The server is authoritative for position, health and score.** Aim angle and movement intent are trusted; speed is
  not. Anything numeric is clamped. `UpgradeStat` is refused when no points are unspent. The refusal is made on the
  room thread, silently: whether a point is available depends on the tank, and a client whose interface is a release
  ahead should see a request that does nothing, not a closed socket.

### Predicting the own tank

**Designed 2026-10-03, plan item 70.** The client steps its own tank at the room's 25 Hz from the moment the player
moves. It uses the rule the room steps the tank by (`Room.updateTanks`):

```
v = (v + direction × accel) × 0.90       direction: the move bits, a diagonal × 0.70710678
p = p + v
the map's edge: p held at radius from it, and v across it reversed and halved
a maze's walls: p pushed out the shorter way, v into the wall dropped (01 §8.9)
```

`accel` and `radius` come from `MotionRule`. The map's size comes from the `Welcome`, and the walls from the maze
seed. **Each step is stamped with the seq of the input that will carry it.** The input the player holds now goes out
with the next seq, and the steps taken while it is held belong to that seq. A change of mind between two sends is
stepped locally but never sent; the server's frames correct it like a knock. On each frame, the client does this
(`OwnTank` in the C# core):

1. The echoed seq `s` and `Motion`'s `inputTicks` `n` name the step that matches the frame's tick: the first step
   stamped `s`, plus `n − 1`.
2. The tank is put there as the server has it: its position from the frame, its velocity from `Motion`.
3. The steps after it are stepped again, each with the input it was stamped with, up to the present.
4. How far that moves the present is a **correction**. The correction is drawn away: the tank is drawn where it is
   predicted, plus what is left of the correction, which halves every 50 ms. A correction of more than 64 units is not
   a correction but a jump (a respawn, a resume, a teleport), and it is taken at once.

The client keeps 64 steps (2.56 s). A frame whose echo is older than the steps kept, or a frame before any input, has
nothing to replay: the tank is put where the server says and predicted on from there. Dead, parked or in the
background, the client predicts nothing and draws the server's tank. A new tank, a resume or a new match starts the
steps afresh.

**Not predicted:** recoil, and every knock (from tanks, shapes, bullets, or a dominator's push). The next frame's
velocity brings each in, about a frame late. **The aim** is the player's own, drawn as they aim. The room sets a driven
tank's angle to its input's every tick, so there is nothing to correct.

**How it is measured:** for each frame, the step that matches it, as it was predicted before the frame came, against
the frame's position. **Measured** (2026-10-03, plan item 70 (c), the `prediction` drill against a release on this
machine):

- A walk of twelve legs that turns, stops and goes diagonally: 108 frames, 106 matched (the two that did not came
  before the first input), and no jump.
- The error: median 0.16 units, which is the wire's rounding of a position to a quarter unit; p95 0.92; the largest
  1.40.
- The same walk while firing: median 0.13 and the largest 1.60. So a Basic's recoil is too small to show.

What is left of the error is at a change of direction. The server applies an input from when it arrives until the next
one arrives. The client's steps stamped with that input are counted from the send before. So the two can differ by a
tick of the old direction, about a unit, which is drawn away in 50 ms. The drawn tank was 3.5 units ahead of the
newest sample at the median, about 100 ms at top speed. That is the lag hidden on a link with no delay of its own,
mostly the input's 100 ms between sends.

## 10. Session, reconnect and app lifecycle

The lobby's session token is not the arena's join ticket. The ticket can be used once: the arena reads it and deletes
it, so a second use finds nothing.

**Resume is built** (2026-09-26, `RoomThread`, FR-4). A phone that walks out of wifi onto a cellular network, into a
lift or into a tunnel loses its connection without a word. Its player comes back to the same *stay* (one player's time
in one room):

- **Every `Welcome` carries a resume secret**, 128 random bits, as a string after the other fields and before the maze
  seed (§3). A client that loses its connection dials the same arena and sends `Resume` (id 10) with the secret, in
  place of `Join`: `u8 protocolVersion`, `string resumeSecret`, optionally `u8 profile` (§8). It gets a `Welcome` with
  a **new** secret, since the one just used is spent, and a fresh view: the first frames create everything it is to
  see, as after a join.
- **The tank stays where it was for 10 s, parked and killable**, as a backgrounded tank is. No input drives it, so
  leaving is never a way out of a fight. A player who resumes within that time has the same tank.
- **Then the tank leaves the world, and what it had grown into waits until a minute has passed** (60 s, not 15: an
  ordinary lift ride is longer than 15 s). A player who resumes in that time gets a tank of the same level and points,
  somewhere safe. The stay's place in the room is kept throughout, so a returning player always fits.
- **Killed while away**, the player comes back to a new tank, and is told who killed it in the first frame's events.
  The stay, its kills and its score go on either way.
- **Nobody back after a minute**: the stay ends as if the player had left then, and is published. A resume after that
  gets `Kick(1)`: back to the lobby for a ticket.
- **Time away is not play.** The stay's play time stops when its connection is lost and starts again when it is
  resumed. So a minute in a lift does not count towards a reward's minimum.
- **The phone usually knows before the server does.** Its wifi is gone, while the server's socket waits for the 30 s
  idle limit (§1). A resume takes over a connection that the server still holds, and closes it. (The C# core's
  `MatchConnection` takes 3 s without a frame, alive in a match and in the foreground, as a loss. It then tries to
  resume after waiting 0.5, 1, 2 and 4 s, and then every 8 s, until a minute has passed since the loss;
  [08](08-client.md).)
- **Only a lost connection waits.** `Leave`, a kick and a stopping arena end the stay at once. So does a new ticket
  for the same player in the same room: the app lost its secret and came back through the lobby, and one player must
  not have two tanks.

**The stay is kept in the arena's memory, not in the store.** The design wrote `resume:{playerId}` into j-redis. But
the tank lives in one arena's memory, so a resume has to reach that arena anyway, and an arena that dies takes the tank
with it. The store would add a failure mode and no reach. What it would add is a **cold resume**: an app that was killed
and restarted has lost its secret, and the lobby could hand it back.

**A cold resume is the device's**
([D-51](../architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret), plan
item 36). The app keeps the latest `Welcome`'s secret, with the arena's address from its grant, on disk where it keeps
the session token. It writes them when it goes to the background, since the OS may kill it after that without a word,
and clears them when the stay ends. Restarted within the minute, the app opens a new connection to that arena and
sends `Resume` with the secret (`MatchConnection.Resume`), and the stay goes on as after any lost connection. A secret
is spent once used, so a second restart needs the newer one. If the secret is older than a minute, or the arena
answers `Kick(1)`, the app goes back through the lobby as before, which ends the waiting stay. **A stay that is waiting
when a timed match ends** is counted in that match's result and ends with it.

**Going to the background is explicit.** A mobile OS may suspend an app in the background and close its socket; iOS
does so within seconds (the app is for Android alone, C-10). A client that sends `Lifecycle(backgrounded)` has its tank
parked and its snapshots stopped at once. That saves the player's battery and data, and it tells a deliberate move to
the background apart from a crash. A suspended app sends nothing, so the arena drops the connection after 30 s of
silence (§1). That is a lost connection, and the stay waits a further minute for a resume.

**What the client does**, for whoever builds one:

1. Keep the secret from the latest `Welcome` in memory (and on disk, if a restart should resume).
2. On a lost connection, dial the same arena and send `Resume`.
3. On `Kick(1)`, go back to the lobby for a ticket.
4. To quit a match, send `Leave`. Otherwise its tank waits a minute for a player who is not coming.
5. **Then wait for the arena to close the connection**, reading on, for up to 2 s, and close only after it
   (2026-09-30, [P-34](../defects.md#2-protocol--the-client-contract)). The arena closes as soon as it reads the
   `Leave`. A socket closed with frames still unread is reset, not closed. The arena, always writing, meets the reset
   on its next write and closes before it has read the `Leave`, and the stay then waits its minute as if the
   connection had been lost. `MatchConnection.Leave` and `tools/BotClient` both wait.

## 11. Backpressure

**Before encoding, the arena checks `channel.isWritable()`.** If it is false, the arena skips this client's snapshot
and counts it (`backend_arena_snapshots_skipped_total`). Events are never dropped: they are held in the connection's
pending-event buffer, and carried in the event section of the next snapshot that goes out.

**A connection that stays unwritable for five seconds in a row is closed** (built 2026-09-26,
[S-9](../defects.md#5-security-and-input)), without a kick: the kick would only queue behind everything else that is
not draining. A kick sent for any other reason closes the connection once it is written, or after 2 s if it cannot be
written (`Frames.KICK_GRACE_MILLIS`). A client that had stopped reading used to keep a kicked connection open for as
long as it liked.

## 12. Protocol versioning and the two-language contract

The encoder is Java and the decoders are C#. "The client and the server drifted apart" is the kind of bug that will
cost the most time, so the protocol is designed against it:

- **`Join` carries a `protocolVersion`.** A mismatch is refused at the join with a clear reason, never a mis-parsed
  frame.
- **`contentVersion` and `phraseListVersion` in `Welcome`** let the client check that its tables match the server's
  before it draws anything from them.
- **Golden test vectors** are checked into the repository (`protocol-spike/vectors/vectors.txt`): encoded byte arrays
  with their expected decoded values. The Java tests (`GoldenVectorTest` and others) and the C# core's tests
  (`Core.Tests`, `GoldenVectorTests`, run by `dotnet test`) both read them. This cost an afternoon, and it catches
  drift at build time rather than in production.
- **A change to the protocol is made in all three clients**: the full client's core, `client-simple/` and
  `client-simple-no-tls/`. The two simple clients share `ArenaMessages.cs` byte for byte, and their check fails when
  they differ ([D-111](../architecture/03-decision-log.md#d-111--a-simple-client-beside-the-core-client-simple-three-small-clients-on-net-standard-20-examples-in-unitys-terms),
  [D-114](../architecture/03-decision-log.md#d-114--a-simple-client-without-tls-client-simple-no-tls-for-a-backend-run-plain)).

The snapshot codec is written by hand on both sides: it is one message type, performance-critical and structurally
stable. The lobby protocol is JSON and may use generated code: it has many message types, changes constantly, and
carries very little volume.

## 13. What to measure

The numbers above are derived. These instruments replace them with observations in Phase 2. The arena's metrics are
named `backend_arena_*`.

| Metric | Why |
|---|---|
| Bytes/s per connection, p50 and p95, by profile | NFR-2. The entity mix in a real fight will not match the estimate. **Built as totals**: `backend_arena_snapshot_bytes_total{profile}` with `backend_arena_clients{profile}` gives bytes per client per profile. **Built per connection** (designed 2026-10-01, plan item 42): `backend_arena_connection_bytes_per_second{profile}`, a histogram of each connection's own average (its snapshot bytes over its time connected), observed when the connection ends and labelled by the profile the client asked for. Only connections of a minute or more count, as a shorter one is mostly its join's burst of creates. Its quantiles are NFR-2's p50 and p95 for the server's half of the traffic. **Built 2026-10-01**: measured with 150 bots for two minutes at the design density: mean 1 144 B/s, p50 about 1 250 and p95 about 1 475 (both inside the 1 000–1 500 bucket, so coarse), against the bots' own count of 1.12 KB/s. With ~71 B a packet at 15 Hz and the input up, p95 is about 2.9 KB/s, 10.4 MB an hour, under NFR-2's 15. |
| Snapshot payload size histogram | Detects content changes that quietly inflate the budget. |
| Entities sent per snapshot, by kind | Validates the priority ranking. |
| Skipped snapshots and profile step-downs per client | Whether adaptation is firing, and how often. **Built for the arena as a whole**: `backend_arena_snapshots_skipped_total`, `backend_arena_snapshots_held_total`, `backend_arena_profile_steps_total{direction}`. |
| Encode time per client per tick | Feeds NFR-1b. |
| Corrections sent per second | Whether client-side extrapolation is diverging. |
| RTT and inferred loss, by platform | The evidence that decides whether UDP is ever needed. |
| Connections dropped, by reason (**built**: `backend_arena_connections_dropped_total{reason}`) | A rise in `idle` or `stalled` is the network. A rise in `tls_handshake`, above the steady trickle from scanners, is clients refusing the certificate. Any `proxy_line` is something other than nginx reaching an arena behind the front, or nginx without `proxy_protocol on` (D-105). None of these shows anywhere else on the server. |
