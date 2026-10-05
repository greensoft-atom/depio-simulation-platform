# 02 — Networking: transport, protocol and snapshots

How a client and an arena talk to each other, and how the mobile data budget
(NFR-2) is met. This document fixes the wire format, so it blocks the Phase 0
client spike and all of Phase 1
([plan §7](../plan.md#7-documentation-order)).

Everything here applies to the **match connection** only: client ↔ arena,
direct, never through nginx or the gateway
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).
The lobby protocol between client and `platform` is ordinary JSON over HTTPS
and is specified with the gateway.

## 1. Transport

**Raw TCP with TLS terminated in Netty.** There is no WebSocket framing and no
HTTP upgrade, because there is no browser client
([D-1](../architecture/03-decision-log.md#d-1--unity-native-mobile-client-no-browser-build)).
Unity's `System.Net.Sockets` speaks this directly on iOS and Android.

```
event loop, boss    (1 thread)  accept
event loops, workers (2 threads) TLS, frame decode → Connection
RoomThreads          (N threads) simulation; encode and write snapshots
```

Pipeline per connection, as built:

```
SslHandler                          TLS 1.3 or 1.2, the JDK's provider; only with a keystore
  → VarintFrameDecoder(max 8 KiB)
  → IdleStateHandler(reader 30 s)   after the decoder: only whole frames are activity
  → MatchFrameHandler
```

**TLS is built** (2026-09-26, [S-6](../defects.md#5-security-and-input)); what it
waits for is a certificate, whose CA and domain are not chosen yet: the machine's
own, which its nginx serves too, since `arenaHost` is the machine's one name
([operations/01 §11](../operations/01-deploy.md#11-the-certificate)). An arena
given a keystore serves TLS on every connection from the first byte, and says so
in the directory; platform's grant then carries `"tls": true`. **The client
connects with TLS exactly when the grant says so, and verifies the certificate
against `arenaHost`**: trusting any certificate would make the encryption
decorative. A plaintext client that reaches a TLS arena is dropped by the
handshake before its `Join` is read, so its ticket is not spent. The arena
refuses to start when its certificate does not name the host it advertises,
since every client would then refuse it
([operations/01 §8](../operations/01-deploy.md#8-tls-for-match-traffic)).

The JDK's provider, not the `netty-tcnative-boringssl-static` this section first
named: that is a native library per platform, absent from the offline bundle,
and bought for speed nobody has measured a need for. **TLS 1.2 is accepted as
well as 1.3.** Android before 10 has no 1.3 in its platform, and Unity's own TLS
layer has historically gone no further than 1.2; which the client ends up on is
for whoever builds it to confirm. One record per frame (each frame is one
`writeAndFlush`) costs 22 bytes on 1.3 (RFC 8446 §5.2) and 29 on 1.2 with
AES-GCM (RFC 5288), both inside the per-packet overhead the bandwidth budget in
§4 assumes.

**A connection that does nothing useful is closed**
([S-9](../defects.md#5-security-and-input)):

- **`Join` (or `Resume`) within 10 s** of connecting, the TLS handshake included. Without this
  a connection that pinged and never joined kept its socket for ever.
- **A whole frame at least every 30 s.** A client in the foreground sends 20
  inputs a second, and **every client sends `Ping` every 10 s in every state,
  dead or alive**, so this is three missed pings. A frame sent a byte at a time
  is not activity.
- **Unwritable for at most 5 s** straight ([§11](#11-backpressure)).

Each is counted in `backend_arena_connections_dropped_total{reason}`, as
`join_deadline`, `idle`, `stalled` or `tls_handshake` (a connection still
handshaking at the deadline counts as the last). A backgrounded client is
silent, so it is dropped after 30 s: see [§10](#10-session-reconnect-and-app-lifecycle).

Socket options: `TCP_NODELAY=true` (Nagle would add up to 40 ms to a 15 Hz
stream), `SO_KEEPALIVE=true`, `WRITE_BUFFER_WATER_MARK(32 KiB, 128 KiB)`,
`PooledByteBufAllocator`.

The event loops are Netty's `MultiThreadIoEventLoopGroup` on epoll, or NIO
where epoll is not available. `MatchFrameHandler` never touches simulation
state. It applies a per-connection rate limit (60 frames a second, with a
burst of 330 for a stalled uplink's backlog; else `Kick(4)`) and decodes each
message. An `Input` is packed into one atomic `long` on the connection, where
the room reads the latest; skill points go into a per-stat counter. A `Join` is
claimed from the ticket store and then queued to its room, and a `Resume` goes
straight to its room's queue. A `Ping` is answered on the spot.
`VarintFrameDecoder` enforces the frame size. Decoding an `Input` allocates
nothing: the server runs 150 of these per room.

### Framing

```
[varint length][payload]
```

A varint length costs one byte for anything under 128 bytes, which is almost
every message. Frames above 8 KiB are a protocol error and close the
connection.

### Why TCP now, and what keeps UDP open

On cellular, 1–3 % packet loss turns TCP head-of-line blocking into visible
200–600 ms freezes. That is a real problem and UDP is the real answer, but a
custom reliability layer is a multi-month sinkhole and should be driven by
telemetry rather than speculation
([D-11](../architecture/03-decision-log.md#d-11--ack-based-delta-baseline-so-the-transport-stays-swappable)).

**Deltas are measured against the last snapshot the server *sent*, in world
space, and the client accumulates them**
([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)).
That is correct because TCP delivers every frame exactly once and in order.

This document previously specified the opposite — deltas against the last
*acknowledged* frame — and the server implemented it while sending no indication
of which frame that was, so the frame could not be decoded correctly at all. A
UDP transport would have to reinstate that scheme properly, with the baseline
tick on the wire and the baseline state kept per entity.

Acknowledgement still exists and still matters: a handle is not reused until its
removal has been confirmed ([§5](#5-entity-handles)). It simply has nothing to do
with positions.

## 2. Primitives

| Type | Encoding |
|---|---|
| `u8`, `i16`, `u16`, `i32` | fixed width, little-endian |
| `u32` | fixed width, **big-endian**, in two places: `clientTimeMs` in `Ping` and `Pong`, which the server echoes without reading, so a client that writes and reads it the same way is right either way; and the two floats of the `MotionRule` event (§4), each an IEEE 754 float's bits, which the client reads (`SnapshotWriter.f32`). Anything that ever has to *read* one on the server must know this (nothing does today; §8 deliberately does not use the round trip) |
| `varint` | LEB128 unsigned |
| `svarint` | zigzag then LEB128 |
| `handle` | `u8` — a per-client entity slot ([§5](#5-entity-handles)) |
| `pos` | `i16`, world units × 4, relative to the snapshot's view origin |
| `dpos` | `svarint`, world units × 4, **world-space** delta from the last value sent to this client |
| `angle` | `u8` = (angle + π) / 2π × 256 — the same offset as `heading`, so ±π is 0 and forward is 128 |
| `hp` | `u8` = hp / maxHp × 255, and at least 1 while alive: 0 is reserved for dead |
| `string` | `varint` length + UTF-8. Used in `Join`, `Resume`, `Welcome` and player names. The server reads a ticket or a resume secret as ASCII, 64 bytes at most. |

There is no free-text field anywhere in the match protocol
([D-14](../architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)).

## 3. Messages

### Client → server

| id | Message | Payload | Rate |
|---|---|---|---|
| 1 | `Join` | **`u8 protocolVersion` first**, then `string ticketId`, then optionally `u8 profile` (§8) | once |
| 2 | `Input` | `varint seq`, `varint ackTick`, `u8 moveMask`, `u16 aim`, `u8 flags` | 10 packets a second, each coalescing the two input samples since the last (latest move and aim, fire OR-ed), **whenever alive and in the foreground, idle or not** (§9) |
| 3 | `UpgradeStat` | `u8 statIndex` | on tap |
| 4 | `ChooseClass` | `u8 classId` | on tap; one the tank may not have yet is refused silently ([01 §4](01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)) |
| 5 | `Respawn` | — | on tap |
| 6 | `Phrase` | `varint phraseId`, an id in the phrase list ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) | ≤ 1 per 2 s; a second sooner, or an id outside the list, is dropped, never kicked |
| 7 | `Ping` | `u32 clientTimeMs` | every 10 s, in every state: a connection silent for 30 s is closed (§1) |
| 8 | `Lifecycle` | `u8 state` (0 = foregrounded, 1 = backgrounded) | on app state change |
| 9 | `Leave` | — | once; ends the stay at once, where a lost connection waits for a resume (§10); then the client waits for the arena's close before its own |
| 10 | `Resume` | `u8 protocolVersion`, `string resumeSecret`, optionally `u8 profile` | in place of `Join`, after a lost connection (§10) |
| 11 | `Sandbox` | `u8 action` (1 a level, 2 a Guardian), `varint value` | in a sandbox only, whose powers it carries ([01 §8.10](01-arena.md#810-sandbox-designed-2026-10-01-plan-item-33)); dropped anywhere else, and when out of range. Sent only to an arena that made a sandbox, so it needs no new protocol version |

**An `Input`'s `flags`** are bits: `FLAG_FIRE` (1), a tap or the trigger held;
`FLAG_AUTOFIRE` (2); `FLAG_AUTOSPIN` (4); and `FLAG_ZOOM` (8, since 2026-09-28),
held: a class with a zoom, the Predator, has its view centred 700 units ahead
along its aim for as long as it is held ([01 §4](01-arena.md#the-rest-of-the-tree-designed-2026-09-28-plan-item-5)).
Nothing about the frame changes: its view origin is the view's centre, wherever
that is. A bit a server does not know is ignored. **`FLAG_AUTOSPIN` is defined
and not built**: the room reads `FIRE`, `AUTOFIRE` and `ZOOM` only
(`RoomThread.applyInputs`), so a client that sets it gets no spin, and the C#
client does not send it.

**`contentVersion` is the class table's version** (since 2026-09-28,
[D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)):
a CRC-32 of the table's JSON, which `platform` serves at `GET /v1/content/classes`
with the same number. It was a constant 1 through three tiers of classes. A
client whose table has another version fetches it before drawing anything a
class makes.

**The version comes first, and the order matters.** The server reads
`protocolVersion` before anything else so that a mismatch is refused rather than
mis-parsed. This table used to list the ticket first — a client written from it
read the ticket's 22-byte length as the version, failed the check, and was sent
`KICK_PROTOCOL_VERSION`, the one reason this document tells a client never to
retry.

**The version is 4** since 2026-09-28: a unit's create carries its radius too
(§4; [01 §4](01-arena.md#the-third-tier-turrets-bodies-hiding-and-minions-designed-2026-09-28-plan-item-5)).
Version 3 gave a predicted create its radius and added the kind `UNIT`, for
traps and drones ([01 §4](01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)).
Version 2 made a predicted create's speed the speed itself, in half units a
tick, where version 1 carried an index into a table of eight. A client of an
earlier version is sent `KICK_PROTOCOL_VERSION`: read by it, every create after
the first bullet would be misread.

**`profile`** is the most this client wants sent (§8): 0 mobile, 1 high,
2 saver. It is optional because clients built before it send nothing, and those
get mobile; so does a value the server does not know.
**`viewportAspect` is not implemented** and is not read. The view rectangle is
1 600 units square, times the tank's class's view (a Sniper's is 1 920,
[01 §4](01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)),
whatever the device. Sizing it to the device is worth
doing — a phone in portrait sees far less world than a tablet — but nothing does
it today.

**`ackTick` rides on `Input`.** It is the tick of the most recent snapshot the
client has fully applied. It no longer decides the delta baseline
([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent));
what it still decides is handle reuse ([§5](#5-entity-handles)). Putting it in
`Input` costs nothing, since `Input` is already the most frequent upstream
message.

### Server → client

| id | Message | Payload |
|---|---|---|
| 1 | `Welcome` | `handle yourHandle` (always `Wire.SELF_HANDLE`), `u8 snapshotHz` (the rate it starts at; it changes with the profile, §8, so frames are timed by `tickDelta`), `varint mapW, mapH`, `u8 mode` (0 the public arena, 1 a duel, 2 team-vs-team, 3 ranked free-for-all, 4 co-op, 5 a team match, 6 domination, 7 tag, 8 maze, 9 a sandbox: `handoff/MatchMode`), `varint contentVersion`, `varint phraseListVersion` (the phrase list's hash, as `contentVersion` is the class table's), `varint selfEntityId`, `string resumeSecret` (§10), `varint mazeSeed` (0 for none; the walls are made from it, [D-48](../architecture/03-decision-log.md#d-48--a-maze-is-sent-as-a-seed-and-a-predicted-bullet-stops-at-its-walls-by-one-rule-on-both-sides); a client reads it when the frame has it) |
| 2 | `Snapshot` | [§4](#4-the-snapshot) |
| 3 | `Pong` | `u32 clientTimeMs`, `varint serverTick` |
| 4 | `Kick` | `u8 reason` |

`Pong` echoes the client's own `clientTimeMs` back unchanged rather than
re-stamping it: the client measures the round trip against its own clock, and
the two clocks are not related to each other.

**Kick reasons.** A bare disconnect tells the client nothing, and the three
common rejections need three different responses — so the reason is sent before
the socket closes, and the close waits for the write.

| reason | Meaning | What the client does |
|---|---|---|
| 1 | Ticket unknown, used, expired or malformed | back to the lobby for a new ticket |
| 2 | No room has space | re-queue; the ticket is already spent |
| 3 | Protocol version mismatch | prompt to update the app; never retry |
| 4 | Rate limit exceeded | a client bug: log it, do not loop |
| 5 | Server-side fault | retry with backoff |
| 6 | The match is over: a match the matcher made (04 §4) has ended and its room closed | back to the lobby, as after `Leave`; not an error, and nothing to reconnect to |
| 7 | Removed by an operator: the room was closed, or the player taken out ([04 §10](04-platform-services.md#the-second-slice-rooms-designed-2026-09-29-plan-item-7)) | back to the lobby; nothing to reconnect to. An older client reads it as 5, which leads to the same place |

Reason 5 is also what a player gets when their frame could not be encoded, or
when their room closed itself after repeated faults ([07 §8](07-threading-and-performance.md#8-stability-safeguards)).
Either way the answer is the same: back through the lobby, with backoff.

**An empty frame closes the connection.** A zero byte where a frame length
belongs means the stream's framing is already broken, and closing is safer
than guessing where the next frame starts. This is deliberate, like the close
on an unknown message type.

Reason 5 exists so a store outage does not look like reason 1: sending a player
to fetch another ticket from a store that is down produces a retry storm
against the component that is already failing.

Deaths, kills, level-ups, phrase messages and mode events are **not** separate
messages. They ride inside the snapshot's event section, so a tick costs one
packet rather than several — at 71 bytes of IP + TCP + TLS overhead per packet,
a second packet costs more than the event it carries.

## 4. The snapshot

Sent at the client's profile rate (§8): 15 Hz for `mobile` and `high`, 10 Hz for
`saver`, while the simulation runs at 25 Hz
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

varint  eventCount
        [ u8 type, varint payloadBytes, payload ]*
```

**A create's `pos` is relative to this frame's view origin** so that it fits an
`i16` on any map size; **an update's `dpos` is world space**. A client that holds
world space throughout — converting once, on the create — needs no other rule,
and scenery then stays where it was put as the camera moves over it.

**The client's own tank is in its own snapshot**, at `Wire.SELF_HANDLE` (1),
which is what `Welcome.yourHandle` carries. The server reserves that handle for
it: until 2026-09-26 it was merely the first one handed out, and another tank
within four units on a lower slot could take it, leaving a joining player
without their own tank ([P-15](../defects.md#2-protocol--the-client-contract)). It is the only place a client learns
its own health. There is no self-only trailer; an earlier draft of this document
specified one and nothing ever wrote it. Progression reaches the client as
`EVT_STATS` instead.

**A tank's `LEVEL` is updated when it changes** (since 2026-09-26; before, an
opponent's level was only the one it had when it came into view). **A shape's
updates never carry `ANGLE`**: the client turns it from its create, as §6 says,
and sending the server's cosmetic spin was a fifth of a calm frame
([P-16](../defects.md#2-protocol--the-client-contract)).

**A tank's `FLAGS` carries `TANK_FLAG_PROTECTED` (1)** while it is in its 3 s of
spawn protection ([01 §7](01-arena.md#7-deaths-respawn-spectate)): it cannot be
hurt and cannot shoot, and the client should show it, or a player whose shots
do nothing, or whose own fire button does nothing, is left guessing. A create
has no flags field, so a tank that arrives protected is told so in the next
frame's update, 67 ms later (100 ms at `saver`), and another update clears it. The player's own
tank gets it too: its fire input is ignored until then, and a held trigger
fires the moment protection ends.

**`TANK_FLAG_HIDDEN` (2)** is set on the player's own tank while it is hidden
([D-23](../architecture/03-decision-log.md#d-23--a-hidden-tank-is-not-sent)): a
Stalker, Manager or Landmine still, the trigger let go, for two seconds. Nobody
else sees the flag, because nobody else is sent the tank: it leaves their frames
with a remove, as if it had left their view, and comes back with a create when
it moves or shoots. Its bullets and drones are sent as ever.

**A create can arrive for a handle the client already holds**: when the client
has not acknowledged the frame that carried the first one within 15 ticks, and
its acknowledgement has not moved for 15 ticks either (50 before its first
acknowledgement, so the opening burst is not sent twice to a far client), the
server sends it again (`SnapshotEncoder.writeCreates`). Treat it as a
replacement. A client that acknowledges as it should never sees one; until
2026-09-26 one that did saw a create for every moving entity every 15 ticks.

**A create's `owner`** (a predicted entity's or a unit's) is the handle this
client holds for the tank that fired it, that tank as it was when it fired: 0
once that tank is gone, dead or left, even when its pool slot now holds
something else this client can see, and 0 while it is out of this view. A
handle stands for one incarnation of a slot (§5), so a slot's next occupant is
never named as the owner of what the last one fired.

**A predicted create's `speed` is in half units a tick**, exactly: 20 is 10 units
a tick. The simulation fires every bullet at a whole number of half units, so
there is nothing to round, and a client extrapolates from it for the bullet's
whole life (D-9). **Its `radius`** is in world units: 8 for most, 16 for a
Destroyer's, 4.8 rounded to 5 for a Gunner's.

**A `UNIT`** is a trap (subtype 1), a drone (2), a minion (3, a drone that
shoots: its bullets are ordinary predicted creates) or a missile, a Rocketeer's
(4) or a Skimmer's (5), which fires as it flies, with its owner's handle and,
since protocol 4, its **radius**, in world units as a predicted create's is: 12
for a Trapper's trap, 10 for an Overseer's drone. Protocol 3 left it out on the
reasoning that a client could look a unit's size up by its owner's class; it
cannot, because a trap outlives its owner, whose handle is then gone
([P-30](../defects.md#2-protocol--the-client-contract)). Neither can be extrapolated, a trap because it slows to a stop and a drone
because it is steered, so a unit is updated as a tank is: `POS`, `ANGLE`,
`HP` when they change ([01 §4](01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)). It ranks with the
bullets for the entity budget.

**A tank's `classId`** is its class
([01 §4](01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)):
0 Basic, 1 Twin, 2 Sniper, 3 Machine Gun, 4 Flank Guard; at level 30, 5 Triple
Shot, 6 Quad Tank, 7 Twin Flank, 8 Assassin, 9 Hunter, 10 Destroyer, 11 Gunner,
12 Tri-Angle, 13 Trapper, 14 Overseer; at level 45, 15 Triplet, 16 Penta Shot,
17 Spread Shot, 18 Octo Tank, 19 Triple Twin, 20 Ranger, 21 Predator,
22 Streamliner, 23 Sprayer, 24 Annihilator, 25 Booster, 26 Fighter, 27 Overlord,
28 Tri-Trapper, 29 Mega Trapper, 30 Gunner Trapper, 31 Overtrapper, 32 Hybrid,
33 Auto 3 (at level 30), 34 Auto 5, 35 Auto Gunner, 36 Auto Trapper, 37 Smasher
(at level 30, from Basic), 38 Spike, 39 Auto Smasher, 40 Stalker, 41 Manager,
42 Landmine, 43 Necromancer, 44 Factory, 45 Battleship, 46 Rocketeer,
47 Skimmer, 48 Mega Smasher; and three that no player can choose, which the arena
gives its own tanks: 49 Guardian and 50 Guardian, enraged, co-op's boss
([01 §8.5](01-arena.md#85-co-op-waves-designed-2026-09-29-plan-item-6)), and
51 Dominator ([01 §8.7](01-arena.md#87-domination-designed-2026-10-01-plan-item-30)).
A tank's size is not sent: it is its class's, as its barrels are.
Ids are appended, never moved. **A turret's aim is not sent**: its shots are
bullets with their own heading, and a client draws a turret pointing where it
last fired. The create carries it,
and an update's `CLASS` carries a change, once.

**`string name` on a tank create is its player's display name** (since
2026-10-01, plan item 38,
[D-52](../architecture/03-decision-log.md#d-52--a-tanks-name-travels-in-its-create)):
empty for a tank nobody plays, the arena's own, and for a name past 64 bytes,
which is dropped rather than cut through a character, as an event's is.
Measured with `TickBenchmark`, every tank named in eight bytes, two runs each
way: no change at the design density (28.9 bytes a snapshot), and +0.3 bytes,
114.7 to 115.0, with twelve tanks to a view on a 5 700-unit map. It was a
placeholder, always empty, "names arrive with the join", and nothing sent them
([P-35](../defects.md#2-protocol--the-client-contract)). `classId` was a
placeholder until 2026-09-27, always 0. `inputSeqDelta`
was the other until 2026-09-26 ([P-9](../defects.md#2-protocol--the-client-contract));
it is now filled in, see §9.

### Event types

| type | Name | Payload | Sent to |
|---|---|---|---|
| 1 | `Death` | `varint score`, `u8 nameLength`, UTF-8 killer name (length 0 = nobody) | the player who died |
| 2 | `Stats` | see `EVT_STATS` under "Sizes" below | the player it describes |
| 3 | `Phrase` | `u8 handle` (the speaker's in this client's view; 0 = none), `varint phraseId`, `u8 nameLength`, UTF-8 speaker's name | in a mode with teams the speaker's team (in tag, the team the speaker is on now); otherwise those whose view holds the speaker ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) |
| 4 | `Kill` | `u8 nameLength`, UTF-8 killer's name, `u8 nameLength`, UTF-8 victim's name (length 0: nobody's) | its killer, in every room; in a made match every player ([01 §9](01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37)) |
| 5 | `Motion` | `u8 inputTicks` (the ticks the input this frame echoes has driven the tank, through this frame's tick; at most 255), `svarint vx, vy` (its velocity after that tick, in 1/256 world unit a tick) | the player whose tank it is, in every frame while it lives (§9, [D-62](../architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry)) |
| 6 | `MotionRule` | `f32 accel` (what the input's direction is multiplied by each tick), `f32 radius`; each an IEEE 754 float's bits as a big-endian `u32`, the server's own value | the same player, when either is not what its view was last told: so in a view's first frame, and after a spawn, a level, a point spent or a class chosen (§9) |
| 7 | `Skin` | `u8 handle` (a tank created in this frame), `u8 skin` (its number in the skin table, `GET /v1/content/skins`) | each client the tank is created for, in the frame with the create, for a tank with a skin only ([04 §8](04-platform-services.md#revenue-designed-2026-10-04-plan-item-75), D-70) |

**Every event carries its byte length**, so a client can step over a type it
has never heard of. Without it the first event type added after a release
desynchronises every older client in the middle of a snapshot, and a mobile
client cannot be forced onto a new build the day it ships. A name longer than
64 bytes is dropped rather than truncated, because cutting UTF-8 at a byte
boundary splits a codepoint and renders as a broken character.

**Implemented:** `Death`, `Stats`, since 2026-09-29 `Phrase`
([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)), and since
2026-10-01 `Kill`, the kill feed
([01 §9](01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37)), and
since 2026-10-03 `Motion` and `MotionRule`, for the own tank's prediction (§9;
plan item 70), and since 2026-10-04 `Skin`, a tank's look, with its create
(plan item 75 (c), D-70). Mode events are designed and not built.

Order matters: **removes first**, so handles are freed before creates allocate
them; then creates, so updates can refer to them.

### Sizes

| Item | Bytes | Notes |
|---|---|---|
| Header | ~6 | four small varints |
| Tank update | ~6 | handle 1, mask 1, dx 1–2, dy 1–2, angle 1, hp occasionally |
| Predicted create | ~12 | position, heading, speed, spawn offset, lifetime, owner |
| Remove | 1 | just the handle |
| Event | 3–6 | type plus a couple of fields |

A typical snapshot for a mobile client — 12 visible tanks, three bullets
created, three expired, one event:

```
6 + (12 × 6) + (3 × 13) + (3 × 1) + 5  ≈ 125 bytes by hand
```

**Measured: 122 bytes.** The reference encoder in
[`protocol-spike/`](../../protocol-spike/README.md) encodes exactly this frame
and reports 122, because several varints are shorter than the worst case
assumed above. The budget below uses the measured figure.

It was 119 until protocol 3 gave each bullet's create its radius, a byte a
bullet, since bullets now come in sizes
([01 §4](01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)).
It was 117 until the event gained a byte-length prefix. Two bytes on a frame
that carries an event is what it costs a client to be able to **skip an event
type it has never heard of**; without it, the first new event type added after
a release desynchronises every older client mid-snapshot, and a mobile client
cannot be forced onto a new build the day it ships.

Plus ~71 bytes of IP + TCP + TLS + framing overhead per packet:

| | Value |
|---|---|
| Down | 193 B × 15 Hz = **2.90 KB/s** |
| Up | 87 B × 10 packets/s = **0.87 KB/s** |
| **Total** | **~3.8 KB/s ≈ 13.6 MB/hour** |

Within NFR-2's 15 MB/hour, with about 9 % headroom. Note that this frame
carries an event; most do not, because an event is only sent to the player it
concerns.

**Event types, and what each costs.** There are seven.

| Type | Payload | Sent when |
|---|---|---|
| `EVT_DEATH` | `varint score, u8 nameLength, bytes killerName` | Once, to the player who died |
| `EVT_STATS` | `varint level, varint xp, varint xpForNext, u8 unspentPoints, u8[8] points` | To one player, when their own progression changes |
| `EVT_PHRASE` | `u8 handle, varint phraseId, u8 nameLength, bytes name` | When someone the client may hear speaks: its team, or a tank in its view |
| `EVT_KILL` | `u8 nameLength, bytes killer, u8 nameLength, bytes victim` | A tank killed: to its killer; in a made match, to every player |
| `EVT_MOTION` | `u8 inputTicks, svarint vx, svarint vy` | Every frame, to the player whose tank lives |
| `EVT_MOTION_RULE` | `f32 accel, f32 radius` | To the same player, when either changes |
| `EVT_SKIN` | `u8 handle, u8 skin` | With the create of a tank that has a skin, to each client it is created for |

`EVT_SKIN` is four bytes framed, once for each create of a tank with a skin: a
tank coming into view, not a frame's steady cost.

`EVT_MOTION` is about seven bytes framed for a moving tank: the type, the
length, the input's ticks, and each velocity a two-byte varint from a quarter of
a unit a tick to 32 (one byte below that, three above, as a hard knock may be).
Every frame, so about 105 B/s at 15 Hz, some 4 % of `mobile`'s budget; it is
what lets the own tank move at once (§9). **Measured** (2026-10-03, plan item 70,
150 bots for two minutes, back to back with the release before it): the bots'
mean frame 77.0 bytes before, 83.2 after, 6.2 more, as a still or slow tank's
velocity takes a byte; each connection's mean 1 148 B/s before, 1 242 after, 94
more (8 %); p50 and p95 in the same buckets as before (1 250, 1 475). Encoding
and writing a snapshot round's median 2.2 ms before and 2.1 after. `EVT_MOTION_RULE` is ten bytes, sent at
a spawn, a level, a point or a class, a few times a life.
`EVT_KILL` is about 20 bytes framed with two eight-byte names. In the public
arena only a killer gets one, a few a minute at most; in a made match every
player gets every kill, of two to eight players. Not measured with
`TickBenchmark`, whose rooms have no connections and so no events.
`EVT_PHRASE` is about 13 bytes framed with an eight-byte name. A speaker may
say one every two seconds, so the twelve tanks of a view all speaking at once
cost about 80 B/s, 3 % of `mobile`; a phrase is rarely said, and far less is
usual.

`EVT_STATS` is about 15 bytes framed. It goes out **immediately** on a level or
a spent point, and at most **once a second** while only experience is moving.
The throttle is not fussiness: experience changes every time a shape dies, and
streaming it at 15 Hz would cost roughly 225 B/s — about 8 % of the entire
downstream budget — to animate a progress bar. A client can interpolate the bar
between updates; it cannot invent a level. That margin has narrowed twice as the design sharpened; if it reaches zero, the entity budget gives before the send rate does, because the send rate is what players feel. Note what dominates: at
this payload size **packet overhead is 37 % of downstream traffic**, which is
why the send rate matters more than shaving bytes off individual fields.

## 5. Entity handles

Each client has its own slot table mapping a `u8` handle to a server entity id.
The wire never carries a 32-bit entity id.

With an entity budget of 30–60 and 256 slots there is ample room, and the
saving is large: three bytes per entity per tick, which at 12 tanks and 15 Hz
is 540 B/s per client — a fifth of the entire downstream budget.

**A handle is not reused until its remove has been acknowledged.** Otherwise a
client that missed the remove would apply updates meant for a new entity to the
old one. The server keeps a per-client free list and a pending-remove set.
(Deltas are send-based since [D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent);
handle reuse is what the ack still decides.)

**A handle stands for one entity, not one pool slot.** Entity ids are slots,
and slots are reused. Frames go out on 15 (or 10) of every 25 ticks, so between two of
them a bullet can die and a new one take its slot. The server therefore keys a
handle by slot *and* generation: a slot that now holds a different entity is a
remove and a create in that frame, never an update. Matched by slot alone, the
new bullet was never created and the client kept drawing the old one along its
old path. Measured: wrong by the tenth frame of a busy room
(`SnapshotRoundTripTest.productionCadence`).

**The client keeps its world across a death, a respawn and a background.**
While a player is dead or backgrounded the server sends events at most, which
move no tick, and keeps that client's handle table. When the player is back,
frames resume from where they stopped: the tick and camera deltas count from
the last frame sent, and everything that changed meanwhile arrives as ordinary
removes, creates and updates. After a respawn, that first frame removes handle
1 and creates the new tank at handle 1. That is the one exception to waiting
for the ack, safe because the remove and the create are in the same frame and
removes are applied first. The same holds across a timed match's boundary:
every entity is removed and the new match's created, in ordinary frames.
**A client must not clear its world on death, respawn or a new match.** The server used to start a new table here, and the client's tick
and camera jumped by the whole elapsed match
([P-6](../defects.md#2-protocol--the-client-contract)).

## 6. Client-side simulation

The largest bandwidth decision in the system
([D-9](../architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client)).
A client sees roughly 12 tanks, 45 bullets and 45 shapes. Bullets are
deterministic between events, so they are never updated, only created and
destroyed; a shape is updated only when its quantised position changes.

| Kind | Streamed? | Client behaviour |
|---|---|---|
| `TANK` | yes, 15 Hz | interpolate between the last two snapshots |
| `PREDICTED` (bullets; as built nothing else, since traps, drones and missiles are `UNIT`s) | create and destroy only | extrapolate from position, velocity and spawn tick; despawn at `lifetimeTicks` if no destroy arrives |
| `STATIC` (shapes) | create, destroy, **and position updates when they move** — shapes drift slowly and are knocked by bullets, and the drift alone was measured at 0.6 bytes a snapshot | rotate locally from `spawnAngle` at any rate — nothing collides with a shape's facing, so the two sides need not agree |

### The contract the client must honour

1. **Extrapolate, do not simulate physics.** A `PREDICTED` entity moves in a
   straight line at constant velocity. It does not collide on the client; the
   server decides what it hits and sends the destroy.
2. **Destroy is authoritative.** When a destroy arrives, remove the entity at
   once, wherever the client had drawn it. A small visual pop is acceptable and
   is the same class of artefact as interpolation delay.
3. **Expire on time.** If no destroy arrives by `lifetimeTicks`, remove it
   anyway. This bounds the damage from a lost event.

### Entities that are not predictable

Steering drones change direction, so extrapolation diverges. They were to be
marked `TANK`-kind, streamed at a reduced rate, rather than given a third
behaviour; as built (protocol 4) traps, drones, minions and missiles are
`UNIT`s, a kind of their own, updated as tanks are (§4). **The kind byte is the only thing that decides client
behaviour**, which keeps the client simple and lets the server reclassify an
entity type without a client release.

### Corrections

**Not built**: the encoder sends no update for a `PREDICTED` entity, and no
entity lives long enough to need one yet. As designed: long-lived `PREDICTED`
entities receive a correction update at most once per second: a position re-sync costing ~5 bytes. Short-lived bullets never live
long enough to need one. This bounds accumulated divergence without a
per-tick cost.

### Measured: how wrong does extrapolation get?

Simulated over a full 75-tick bullet life, worst case across all firing angles,
at three bullet speeds. Screen scale is a phone showing 1 600 world units
across 1 080 px (0.675 px per unit).

| Velocity encoding | Bytes | Max drift |
|---|---|---|
| `i8` at 1.0 unit/tick | 2 | **33 px** — unusable |
| `i8` at 0.25 unit/tick | 2 | 8.6 px, and caps speed at ±31.7 units/tick |
| `i16` at 0.0625 | 4 | 2.3 px |
| **`u16` angle + `u8` speed** | **3** | **0.3 px** |

Polar wins on both axes: one byte smaller than the Cartesian `i16` and seven
times more accurate. The reason is that a bullet's speed is a discrete value,
so it travels exact rather than as a quantised measurement, leaving only the
heading to encode — and 16 bits of heading over 750 units of travel is a
fraction of a pixel. It was an index into the stat table's eight speeds when
this was measured; since protocol 2 it is the speed in half units a tick, which
the simulation fires at, because a class's barrels make more speeds than a
table held.

**Quantisation is not the dominant error. Late destroy is.** A bullet keeps
flying on the client until the destroy event arrives, which is RTT/2 late:

| RTT | Overshoot at 10 units/tick |
|---|---|
| 50 ms | 4 px |
| 100 ms | 8 px |
| 200 ms | 17 px |
| 400 ms | 34 px |

At realistic mobile latency the overshoot is one to two orders of magnitude
larger than any encoding error, so precision beyond the polar scheme buys
nothing. Two consequences for the client:

- **Draw the hit effect at the bullet's position at the remove frame's tick**,
  extrapolated to it, not where the bullet was last drawn: a remove carries only
  a handle. The explosion is what the player looks at, and it should be as
  truthful as the frame allows even when the projectile was not.
- **Do not client-predict hits.** Hiding a bullet early on a guessed collision
  trades a visible overshoot for an invisible-bullet bug, which is worse: a
  bullet that vanishes and then damages nobody reads as a lost shot.

Clock skew is third-order: a 20 ms error in the client's tick estimate moves
an extrapolated bullet 3.4 px at 10 units/tick, which the `Ping`/`Pong`
exchange keeps well inside.

**Status:** the numerical half of the Phase 0 spike is done and D-9 survives
it. The perceptual half — whether the late-destroy overshoot *looks* wrong in
motion — still needs a human with the Unity spike.

## 7. Interest management

Per client, per snapshot, on the room thread:

1. **View rectangle** = tank position ± half of (viewW, viewH). **As built**,
   1 600 units square times the tank's class's view, `fovMul` (a Sniper's
   1 920, a Ranger's 2 560; `RoomThread.sendSnapshots`), centred 700 units ahead
   along the aim while a Predator holds zoom (§3): no margin and no declared
   aspect.
2. **Query the spatial hash** for entity ids inside it: a circle of the
   rectangle's half diagonal, then the rectangle itself (`SnapshotEncoder.select`).
   A hidden tank is left out of every view but its own player's.
3. **Rank by priority**, not by distance alone:
   `other tanks > bullets threatening me > bullets > shapes`. **As built, three
   classes**: tanks; then bullets and units (traps, drones, minions, missiles);
   then shapes; each by distance from the tank, not from the view's centre, so
   a zoomed player keeps what is beside it. There is no "threatening" class, so
   a near harmless bullet can take the place of a far incoming one when the
   budget is full.
4. **Truncate to the profile's entity budget** ([§8](#8-traffic-profiles)).
5. **Diff against the client's handle table** to produce removes, creates and
   updates.

Ranking then truncating, rather than shrinking the view rectangle, is what
keeps a crowded fight readable: the player loses distant shapes, not the tank
shooting at them.

## 8. Traffic profiles

**Built 2026-09-26** (`arena/TrafficProfile`, `TrafficControl`;
[D-17](../architecture/03-decision-log.md#d-17--a-client-is-stepped-down-on-queueing-not-on-round-trip-time-or-writability)).
Three profiles, and a controller per client that moves between them:

| Profile | Snapshot rate | Entity budget | Down, at design density |
|---|---|---|---|
| `high` | 15 Hz | 60 | ~4.3 KB/s |
| `mobile` (default) | 15 Hz | 30 | ~2.9 KB/s |
| `saver` | 10 Hz | 20 | ~1.6 KB/s |

**The client says the most it wants**, in an optional byte after the ticket in
`Join` (§3): `Wire.PROFILE_MOBILE` 0, `PROFILE_HIGH` 1, `PROFILE_SAVER` 2.
Absent, or a value the server does not know, is `mobile`, so a client built
before this, or after it, is served rather than refused. It starts there, is
stepped down when its link cannot carry it, and back up, **never above what it
asked for**: a player who chose `saver` in settings stays there. The Welcome
carries the rate it starts at; the rate can change after, so **the client
times frames by `tickDelta`, never by the Welcome's rate**. A step down removes
the entities beyond the smaller budget in the next frame, a step up creates
them; nothing else changes.

**What steps a client down is its queue, not its distance.** A link that
cannot carry the stream queues it, each snapshot behind the last, and the
player sees an ever older world; fewer bytes cure that. A link that is merely
far does not, so the round trip is not the signal, and neither is socket
writability, which Netty reports only once the kernel's buffer and 128 KiB of
its own are full: most of a minute of snapshots at these rates. The signal is
how long the oldest snapshot not yet acknowledged has been out, above the
shortest acknowledgement of that connection (its floor, which absorbs distance,
the client's acknowledgement interval and the server's round). **The floor never
rises**: a two-minute window, as first built, rose to meet a queue that never
emptied, and on a link slow all session the view drifted from two seconds old
to six in ten minutes. A network change that moves the phone's address starts
a new connection; one that keeps it, such as LTE falling back to 3G, adds less
than the 300 ms that counts as queue. Per client, at each of its rounds:

- **More than 300 ms queued for half a second**: one step down; no second step
  within 2 s, so the first has time to show.
- **More than a second queued**: nothing is added. The age of the oldest send is
  not enough here, since it says how long the queue *was*: a frame sent now is
  judged by the bytes already out at the rate the link has been delivering
  them. Deltas are against the last frame sent
  ([D-16](../architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)),
  so a held round only makes the next frame larger. Counted in
  `backend_arena_snapshots_held_total`.
- **Two unwritable rounds in a row**: one step down. An unwritable round is
  never sent, as before (§11).
- **Ten seconds with at most 200 ms queued**: one step up. A step up is a probe:
  one that fails, a step down within ten seconds, doubles the wait before the
  next, up to 160 s, so a link that stays slow is not given a hitch every ten
  seconds; any other step down starts the wait at ten again.
- **Nothing is judged before the first acknowledgement**, since there is no floor;
  a client that never acknowledges is left at its profile. A death or the app
  in the background forgets what is in flight, so the silence is not taken for
  a queue.

**This depends on the client acknowledging** at 10 Hz whenever it is alive and
in the foreground, idle or not (§9): acknowledgements ride on `Input`, and one
that stops is indistinguishable from a link that has stopped.

**Measured.** In simulation (`TrafficControlTest`, a delay each way and a
downstream capacity): links with room to spare, 60 ms or 600 ms round trips,
are never touched; a link at 1.8 KB/s, below mobile's 2.25 and above saver's
1.2, is at `saver` within 2.7 s, the player's view at most 0.8 s old over ten
minutes, with six probes of `mobile` in that time; at 1.0 and 0.6 KB/s, below
even `saver`, held rounds keep it to 1.2 and 1.4 s. Against the
release, 20 bots each behind a shaped link of 900 bytes a second (the bots'
open map sends ~1.1 KB/s at `mobile`): before, the view each player saw was
**20.5 s old after a minute and growing**, and the arena never noticed; after,
it stayed **under 1 s** for two minutes, 19 of the 20 at `saver`, each trying
`mobile` again less often. Behind a 600 ms round trip with room to spare,
nothing moved; nor on a healthy link. Bots asking for `saver` got 10 frames a
second at 0.6 KB/s, and asking for `high` 15 at 2.1 KB/s, 141-byte frames
against `mobile`'s 75. **One transient remains:** before the first acknowledgement
there is no floor and before a second of them no rate, so about 1.6 s of the
starting profile goes out regardless; on a link slower than `saver` it drains
at the link's pace, once, at join.

**Not built: the hard bytes-per-second cap** the design also named, shrinking
the entity budget in a crowded fight to protect a data bill. The budget already
bounds a frame (under 400 B at 30 entities, `SnapshotEncoderTest`), and a second
loop moving the budget would churn creates, which cost more than the updates
they replace. And the budget holds without it: 150 bots in one room at design
density, every one firing, cost **1.17 KB/s of payload each, ~2.24 KB/s on the
wire**, against `mobile`'s 2.85 (plan, measurements).
`backend_arena_snapshot_bytes_total{profile}` and `backend_arena_clients{profile}`
say what real players cost; decide from those, at a real fight's density.

## 9. Input, prediction and reconciliation

- The client sends `Input` at up to 20 Hz, **two coalesced inputs per packet**,
  so ten packets per second. Upstream radio transmission costs more battery
  than reception, and halving the packet count halves that cost.
- **Those ten packets go out whenever the tank is alive and the app is in the
  foreground, even when nothing changed.** The acknowledgement rides on them,
  and the server reads a client that stops acknowledging as a link that has
  stopped: after a second it sends that client less, then nothing more until
  the acknowledgements catch up (§8). And a tank whose input has been silent
  for a second is parked, as a backgrounded one is, until input comes again:
  the last input no longer drives it on its own
  ([P-23](../defects.md#2-protocol--the-client-contract)). Dead or backgrounded,
  the client sends none and nothing is judged.
- The room coalesces further per tick, keeping the last input but OR-ing the
  fire bit so a tap between ticks is never lost.
- `seq` is echoed as `lastProcessedInputSeq` so the client can replay
  unacknowledged inputs onto its predicted tank. Other tanks are rendered
  1–2 snapshots in the past.
- **What the echo means, exactly.** Inputs coalesce, so the server does not
  apply every input; it applies whichever is latest when a tick starts.
  `lastProcessedInputSeq` in the snapshot for tick T is the seq of the input
  that drove the tank during tick T, and the tank's position in that snapshot
  includes it. The client discards buffered inputs up to and including that
  seq, sets its tank to the server's position, and re-applies the rest. An
  input the server skipped is superseded by a later one, which is how the
  server treated it too. Before any input arrives the value is 0.
- **`seq` is 24 bits and wraps.** The client's counter runs modulo 2^24; the
  snapshot carries the advance modulo 2^24, so it is never negative, and the
  client adds it modulo 2^24. To ask "is a newer than b", use
  `((a - b) & 0xFFFFFF)` in `1 .. 2^23 - 1`, never `a > b`. At 20 Hz the counter
  wraps after about nine days; an open match on a server that is never restarted
  can get there.
- The echo survives death, respawn and background, like the rest of the view
  (§5): while the tank is dead or parked no input drives it, and the value holds
  still.
- **Where in the input's span the tick fell** (since 2026-10-03, plan item 70,
  [D-62](../architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry)).
  Inputs go at 10 Hz and the room ticks at 25, so one input drives two or
  three ticks, and the echo alone does not say which of them a frame's tick
  was. `Motion`'s `inputTicks` does: the ticks the echoed input has driven the
  tank, this one included, counted by the view as `takeInput` hands the room
  the same seq again, back to 1 at a new one, at most 255; unchanged while the
  tank is parked or its input stale, as nothing drives it then.

### Predicting the own tank

**Designed 2026-10-03, plan item 70.** The client steps its own tank at the
room's 25 Hz from the moment the player moves, by the rule the room steps it by
(`Room.updateTanks`):

```
v = (v + direction × accel) × 0.90       direction: the move bits, a diagonal × 0.70710678
p = p + v
the map's edge: p held at radius from it, and v across it reversed and halved
a maze's walls: p pushed out the shorter way, v into the wall dropped (01 §8.9)
```

`accel` and `radius` are `MotionRule`'s; the map's size is the `Welcome`'s, the
walls the maze seed's. **Each step is stamped with the seq of the input that
will carry it**: the input the player holds now goes out with the next seq,
and the steps taken while it is held are that seq's. (A change of mind between
two sends is stepped locally but never sent; the server's frames correct it
like a knock.) On each frame:

1. The echoed seq `s` and `Motion`'s `inputTicks` `n` name the step that
   matches the frame's tick: the first step stamped `s`, plus `n − 1`.
2. The tank is put there as the server has it: its position from the frame,
   its velocity from `Motion`.
3. The steps after it are stepped again, each with the input it was stamped
   with, to the present.
4. The present moved by that is a **correction**. It is drawn away: the tank is
   drawn where it is predicted plus what is left of the correction, which halves
   every 50 ms. One of more than 64 units is not a correction but a jump (a
   respawn, a resume, a teleport), and is taken at once.

A frame whose echo is older than the steps kept (two seconds), or before any
input, has nothing to replay: the tank is put where the server says and
predicted on from there. Dead, parked or backgrounded, the client predicts
nothing and draws the server's tank; a new tank, a resume or a new match starts
the steps afresh.

**Not predicted:** recoil, and every knock (tanks, shapes, bullets, a
dominator's push). The next frame's velocity carries each in, about a frame
late. **The aim** is the player's own, drawn as they aim: the room sets a
driven tank's angle to its input's every tick, so there is nothing to correct.

What it is measured by: the step that matches each frame, as it was predicted
before the frame came, against the frame's position. **Measured** (2026-10-03,
plan item 70 (c), the `prediction` drill against a release on this machine): a
walk of twelve legs that turns, stops and goes diagonally, 108 frames, 106
matched (the two not, before the first input), no jump; the error's median
0.16 units, the wire's rounding of a position to a quarter unit, p95 0.92, the
largest 1.40; the same walk firing, the median 0.13 and the largest 1.60, so a
Basic's recoil is too small to show. What is left is at a change of direction:
the server applies an input from when it arrives until the next does, and the
client's steps stamped with it are counted from the send before, so the two can
differ by a tick of the old direction, about a unit, drawn away in 50 ms. The
drawn tank was 3.5 units ahead of the newest sample at the median, about
100 ms at top speed: the lag hidden on a link with no delay of its own, mostly
the input's 100 ms between sends.
- The server is authoritative for position, health and score. Aim angle and
  movement intent are trusted; speed is not. Anything numeric is clamped, and
  `UpgradeStat` is rejected when no points are unspent — refused on the room
  thread, silently, because whether a point is available depends on the tank and
  a client whose interface is a release ahead should see a request that does
  nothing rather than a closed socket.

## 10. Session, reconnect and app lifecycle

The lobby session token is not the arena join ticket. The ticket is single-use:
the arena reads and deletes it, so a second use finds nothing.

**Resume is built** (2026-09-26, `RoomThread`, FR-4). A phone walking out of wifi
onto a cellular network, into a lift or a tunnel, loses its connection without a
word; its player comes back to the same stay:

- **Every Welcome carries a resume secret**, 128 random bits, as a string after
  the other fields (§3). A client that loses its connection dials the same arena
  and sends `Resume` (id 10) with it in place of `Join`: `u8 protocolVersion`,
  `string resumeSecret`, optionally `u8 profile` (§8). It gets a Welcome, with a
  **new** secret, since the one just used is spent, and a fresh view: the first
  frames create everything it is to see, as after a join.
- **The tank stays where it was for 10 s, parked and killable**, as a
  backgrounded one is: no input drives it, so leaving is never a way out of a
  fight. Resumed within that, the player has the same tank.
- **Then it leaves the world, and what it had grown into waits until a minute
  has passed** (60 s, not 15: an ordinary lift ride is longer than 15). Resumed
  in that time, the player gets a tank of the same level and points, somewhere
  safe. The stay's place in the room is kept throughout, so a returning player
  always fits.
- **Killed while away**, the player comes back to a new tank, told who by in the
  first frame's events. The stay, its kills and its score go on either way.
- **Nobody back after a minute**: the stay ends as if they had left then, and is
  published. A resume after that is `Kick(1)`, back to the lobby for a ticket.
- **Time away is not play.** The stay's play time stops when its connection is
  lost and starts again when it is resumed, so a minute in a lift does not count
  towards a reward's minimum.
- **The phone usually knows before the server does**: its wifi is gone, while
  the server's socket waits for the 30 s idle limit (§1). A resume takes over a
  connection the server still holds, and closes it.
- **Only a lost connection waits.** `Leave`, a kick and a stopping arena end the
  stay at once. So does a new ticket for the same player in the same room: the
  app lost its secret and came back through the lobby, and one player must not
  have two tanks.

**Kept in the arena's memory, not the store.** The design wrote
`resume:{playerId}` into j-redis. The tank lives in one arena's memory, so a
resume has to reach that arena anyway, and the arena that dies takes the tank
with it; the store would add a failure mode and no reach. What it would add is
a **cold resume**: an app killed and restarted has lost its secret, and the lobby
could hand it back.

**A cold resume is the device's**
([D-51](../architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret),
plan item 36). The app keeps the latest Welcome's secret with the arena's
address from its grant, on disk where it keeps the session token, written when
it goes to the background, since the OS may kill it after without a word, and
cleared when the stay ends. Restarted within the minute, a new connection to
that arena sends `Resume` with it (`MatchConnection.Resume`), and the stay goes
on as after any lost connection; a secret is spent once used, so a second
restart needs the newer one. Older than a minute, or answered `Kick(1)`, it
goes back through the lobby as before, which ends the waiting stay. **A stay waiting across a timed match's end** is
counted in that match's result and ends with it.

**Backgrounding is explicit.** iOS suspends an app within seconds and kills the
socket. A client that sends `Lifecycle(backgrounded)` has its tank parked and
its snapshots stopped immediately — which saves the player's battery and data,
and distinguishes a deliberate background from a crash. A suspended app sends
nothing, so the arena drops the connection after 30 s of silence (§1); that is
a lost connection, and the stay waits a further minute for a resume.

**What the client does**, for whoever builds it: keep the secret from the latest
Welcome, in memory (on disk, if it wants a restart to resume); on a lost
connection, dial the same arena and `Resume`; on `Kick(1)`, go back to the lobby
for a ticket; to quit a match, send `Leave`, or its tank waits a minute for a
player who is not coming. **Then wait for the arena to close the connection**,
reading on, up to 2 s, and close only after it (2026-09-30,
[P-34](../defects.md#2-protocol--the-client-contract)): the arena closes as soon as it
reads the `Leave`. A socket closed with frames still unread is reset, not
closed, and the arena, always writing, meets the reset on its next write and
closes before it has read the `Leave`; the stay then waits its minute as if the
connection had been lost. `MatchConnection.Leave` and `tools/BotClient` do so.

## 11. Backpressure

Before encoding, check `channel.isWritable()`. If false, skip this client's
snapshot and count it. Events are never dropped: they are held in the
connection's pending-event buffer and carried in the event section of the next
snapshot that goes out. **A connection that stays unwritable for five seconds straight is closed**
(built 2026-09-26, [S-9](../defects.md#5-security-and-input)), without a kick:
the kick would only queue behind everything that is not draining. A kick sent
for any other reason closes the connection once it is written, or after 2 s if
it cannot be — a client that has stopped reading used to keep a kicked
connection open for as long as it liked.

## 12. Protocol versioning and the two-language contract

The encoder is Java and the decoder is C#. "The client and server drifted" is
the bug class that will cost the most time, so it is designed against:

- `Join` carries a `protocolVersion`. A mismatch is rejected at join with a
  clear reason, never a mis-parsed frame.
- `contentVersion` and `phraseListVersion` in `Welcome` let the client check
  that its tables match the server's before it renders anything from them.
- **Golden test vectors** are checked into the repository: encoded byte arrays
  with their expected decoded values, exercised by the Java test suite *and*
  the Unity test runner. This costs an afternoon and catches drift at build
  time rather than in production.

The snapshot codec is hand-written on both sides — one message type,
performance-critical, structurally stable. The lobby protocol is JSON and may
use generated code; it has many message types, evolves constantly, and carries
negligible volume.

## 13. What to measure

The numbers above are derived. These instruments replace them with observations
in Phase 2:

| Metric | Why |
|---|---|
| Bytes/s per connection, p50 and p95, by profile | NFR-2. The entity mix in a real fight will not match the estimate. **Built as totals**: `snapshot_bytes_total{profile}` with `clients{profile}` gives bytes per client per profile. **Per connection** (designed 2026-10-01, plan item 42): `backend_arena_connection_bytes_per_second{profile}`, a histogram of each connection's own average, its snapshot bytes over its time connected, observed when it ends, labelled by the profile the client asked for; only connections of a minute or more, as a shorter one is mostly its join's burst of creates. Its quantiles are NFR-2's p50 and p95 for the server's half of the traffic. **Built 2026-10-01**: measured with 150 bots for two minutes at the design density, mean 1 144 B/s, p50 about 1 250 and p95 about 1 475 (both inside the 1 000–1 500 bucket, so coarse), against the bots' own count of 1.12 KB/s; with ~71 B a packet at 15 Hz and the input up, p95 is about 2.9 KB/s, 10.4 MB an hour, under NFR-2's 15 |
| Snapshot payload size histogram | Detects content changes quietly inflating the budget |
| Entities sent per snapshot, by kind | Validates the priority ranking |
| Skipped snapshots and profile step-downs per client | Whether adaptation is firing, and how often. **Built for the arena as a whole**: `snapshots_skipped_total`, `snapshots_held_total`, `profile_steps_total{direction}` |
| Encode time per client per tick | Feeds NFR-1b |
| Corrections sent per second | Whether client-side extrapolation is diverging |
| RTT and inferred loss, by platform | The evidence that decides whether UDP is ever needed |
| Connections dropped, by reason (**built**) | A rise in `idle` or `stalled` is the network; in `tls_handshake`, above the steady trickle from scanners, clients refusing the certificate. Neither shows anywhere else on the server |
