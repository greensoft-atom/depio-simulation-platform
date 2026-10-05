# Diagrams: the arena and the wire

How an arena runs its rooms and what crosses the match connection, drawn from
the code in `backend/arena`, `backend/sim` and `backend/protocol`. The design is
in [01 Realtime arena](../detailed-design/01-arena.md) and
[02 Networking](../detailed-design/02-networking.md); the modules' own pages are
[arena](../../backend/arena/README.md), [sim](../../backend/sim/README.md),
[protocol](../../backend/protocol/README.md) and
[tools](../../backend/tools/README.md).

## Room lifecycle

`RoomThread` holds a room's stage; `RoomRegistry` creates rooms, drops the
finished and failed ones, and its watchdog abandons a hung one. A public room
(`MatchRules.OPEN`) and a timed one stay in `Playing`; only a made match's room
waits for a roster and ends ([01 §8.1](../detailed-design/01-arena.md#81-the-mode-decides-the-lifecycle)).

```mermaid
stateDiagram-v2
    state "Waiting for its roster" as Waiting
    state "Playing" as Playing
    state "Over, result published" as Over
    state "Stopped" as Stopped
    state "Failed or abandoned" as Failed
    [*] --> Waiting : a made match's first ticket
    [*] --> Playing : a public or timed room is created
    Waiting --> Playing : the roster arrived or 30 s passed, the world reset
    Playing --> Playing : a timed match ends and the next begins
    Playing --> Over : walkover, clock, kills, waves, holds, one team, an empty sandbox
    Over --> [*] : players sent Kick 6, the registry drops the room
    Waiting --> Stopped : operator close or shutdown
    Playing --> Stopped : operator close, drain or shutdown
    Stopped --> [*] : results published, players kicked
    Waiting --> Failed : 3 failed ticks in 100, or 10 s without a tick
    Playing --> Failed : 3 failed ticks in 100, or 10 s without a tick
    Failed --> [*] : players sent Kick 5, the room replaced
```

## The tick loop

`RoomThread.run` keeps a 40 ms deadline and calls `RoomThread.tick`, which wraps
`Room.step`. The order is fixed: input before the step, everything that reads
the step's kills after it, the snapshots last
([01 §1](../detailed-design/01-arena.md#tick-loop)).

```mermaid
flowchart TD
    Park["park until the next 40 ms deadline"] --> Drain["drain operators' removals, leaves, resumes, joins"]
    Drain --> Inputs["apply inputs<br/>none for 1 s or backgrounded parks the tank"]
    Inputs --> Requests["spend points, choose classes, sandbox powers"]
    Requests --> Step["Room.step<br/>tanks, bullets, shapes, hash, collide, sweep"]
    Step --> Tally["MatchTally takes the kills, Tag its conversions"]
    Tally --> Deaths["deaths become Death events, the kill feed"]
    Deaths --> Stays["stays waiting for a resume"]
    Stays --> After["respawns, Stats, phrases"]
    After --> Life{"lifecycle"}
    Life -->|"open"| Check["checkpoint long stays, once a second"]
    Life -->|"made"| Made["the match's stage and its mode"]
    Life -->|"timed"| Timed["end the match on the clock"]
    Check --> Rounds["snapshot round for every client due"]
    Made --> Rounds
    Timed --> Rounds
    Rounds --> Late{"more than 10 ticks behind"}
    Late -->|"yes"| Overrun["count an overrun, reset the deadline"]
    Late -->|"no"| Park
    Overrun --> Park
    Step -.->|"throws"| Fault{"3 failures in 100 iterations"}
    Fault -->|"yes"| Close["fail the room: publish, Kick 5"]
    Fault -->|"no"| Park
```

## Join, with the ticket claim and the Welcome

`MatchFrameHandler` reads the `Join` on the connection's event loop and claims
the ticket from j-redis without blocking it; `RoomRegistry` reserves a place, and
the room thread admits the player
([02 §3](../detailed-design/02-networking.md#3-messages),
[04 §3](../detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets)).

```mermaid
sequenceDiagram
    participant C as client
    participant H as MatchFrameHandler
    participant S as store
    participant G as RoomRegistry
    participant R as RoomThread
    C->>H: TCP connect, then TLS when the grant says so
    Note over H: Join or Resume due within 10 s
    C->>H: Join with version, ticket, optional profile
    alt another protocol version
        H-->>C: Kick 3
    end
    H->>S: claim ticket, read and delete in one transaction
    S-->>H: the ticket, or nothing
    alt the store failed
        H-->>C: Kick 5
    else no such ticket
        H-->>C: Kick 1
    else player just removed by an operator
        H-->>C: Kick 7
    end
    H->>G: allocateMatch for a match ticket, else allocate
    G-->>H: a room with a place reserved, or none
    alt no room
        H-->>C: Kick 2
    end
    H->>R: offerJoin
    R->>R: admit, end any older stay of the player, spawn a tank, new view and traffic control
    R-->>C: Welcome with own handle 1, rate, map, mode, versions, resume secret, maze seed
    loop every round due at the client's profile
        R-->>C: Snapshot
        C->>H: Input with the tick acknowledged, ten a second
        C->>H: Ping every 10 s, answered at once with Pong
    end
```

## Resume after a lost connection

A connection that closes without `Leave` leaves its stay waiting. `RoomThread`
parks the tank, takes it out after 10 s keeping what it had grown into, and
ends the stay after 60 s; a `Resume` with the latest secret takes it back
([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)).

```mermaid
sequenceDiagram
    participant C as client
    participant H as MatchFrameHandler
    participant G as RoomRegistry
    participant R as RoomThread
    Note over C,H: the old connection is lost without Leave
    H->>R: offerLeave for the old connection
    R->>R: suspend, park the tank, pause the stay's play time
    loop every tick while it waits
        R->>R: at 10 s take the tank out and keep its stats, at 60 s end the stay and publish it
    end
    C->>H: new connection, Resume with version, secret, optional profile
    H->>G: roomFor the secret
    alt no stay holds that secret
        H-->>C: Kick 1, back to the lobby for a ticket
    end
    H->>R: offerResume
    alt the server still holds a live connection with that secret
        R->>R: take it over and close it
    end
    R->>R: the parked tank, or one grown from the kept stats, or a new one if it was killed
    Note over R: in tag a new tank is on the team the player was converted to
    R-->>C: Welcome with a new secret, the old one spent
    R-->>C: first frames create everything in view, a Death event if killed while away
```

## Snapshot encoding

`RoomThread.sendSnapshots` decides whether a client's round goes out
(`TrafficControl`), and `SnapshotEncoder` writes it against that client's
`ClientView`: deltas against what was last sent, handles freed only once the
client acknowledges their removal
([02 §4, §5, §7, §8](../detailed-design/02-networking.md#4-the-snapshot)).

```mermaid
flowchart TD
    Due{"client's round due<br/>15 or 10 in 25 ticks"} -->|"no"| Skip["nothing this tick"]
    Due -->|"yes"| Bg{"backgrounded"}
    Bg -->|"yes"| Idle["send nothing, forget what is in flight"]
    Bg -->|"no"| Dead{"tank dead"}
    Dead -->|"yes"| Events["a frame of events only"]
    Dead -->|"no"| Writable{"socket writable"}
    Writable -->|"no"| Skipped["skip and count, two in a row step down"]
    Writable -->|"yes"| Ack["read the ack, at most this tick<br/>ClientView.acknowledge frees confirmed removes"]
    Ack --> Queue{"TrafficControl.round<br/>more than 1 s queued"}
    Queue -->|"yes"| Held["hold the round"]
    Queue -->|"no"| Size["budget from the profile<br/>view 1600 times the class's fovMul"]
    Size --> Motion["Motion, and MotionRule when changed"]
    Motion --> Select["select: grid query, view rectangle, no hidden tank but its own"]
    Select --> Rank["rank tanks, then bullets and units, then shapes<br/>by distance from the tank, cut to the budget"]
    Rank --> Mark["mark handles still held for the same generation"]
    Mark --> Removes["removes: every unmarked handle<br/>pending until acknowledged"]
    Removes --> Creates["creates: a new handle each, handle 1 for the own tank<br/>owner byte only for an owner of that generation"]
    Creates --> Stuck{"a create unconfirmed 15 ticks<br/>and the ack stalled 15, or 50 before the first"}
    Stuck -->|"yes"| Again["send that create again"]
    Stuck -->|"no"| Updates["updates: compare quantised absolute state<br/>write only the fields that changed"]
    Again --> Updates
    Updates --> Tail["events, then the view origin and last sent tick"]
    Tail --> Write["Frames.write, count the bytes"]
```

## Collision broadphase

`Room.rebuildHash` files every entity in one 200-unit cell by its centre, so
each query reaches the largest radius anything has. `Room.collide` settles each
bullet's hits first, then each tank's body contacts, every pair once
([01 §6](../detailed-design/01-arena.md#6-spatial-hash-and-collisions)).

```mermaid
flowchart TD
    Hash["clear the grid, insert tanks, bullets and shapes by centre"] --> EachB["next live bullet"]
    EachB --> QB["query its radius plus the largest radius"]
    QB --> FB{"itself, dead, its own shooter,<br/>same team, or a pair seen from the other side"}
    FB -->|"yes"| NextB["next candidate"]
    FB -->|"no"| OB{"circles overlap"}
    OB -->|"no"| NextB
    OB -->|"yes"| HitB["target loses the bullet's damage unless protected<br/>bullet loses the target's body damage<br/>knock the target unless it is a bullet"]
    HitB --> DeadT{"target's health gone"}
    DeadT -->|"yes"| KillT["kill, or capture a dominator<br/>experience, assists, kill log, replace a shape"]
    DeadT -->|"no"| DeadB{"bullet's health gone"}
    KillT --> DeadB
    DeadB -->|"yes, the bullet dies"| EachB
    DeadB -->|"no"| NextB
    NextB -->|"another candidate"| FB
    NextB -->|"none left"| EachB
    EachB -->|"no bullet left"| EachT["next live tank"]
    EachT --> QT["query tanks and shapes around it"]
    QT --> FT{"a tank with a lower id, or no overlap"}
    FT -->|"yes"| NextT["next candidate"]
    FT -->|"no"| Knock["knock both apart"]
    Knock --> Team{"same team, or either protected"}
    Team -->|"yes"| NextT
    Team -->|"no"| Body["each takes a 25th of the other's body damage<br/>a body kill is settled as a bullet kill"]
    Body --> NextT
    NextT -->|"another candidate"| FT
    NextT -->|"none left"| EachT
    EachT -->|"no tank left"| Sweep["the step's sweep recycles what died"]
```

## A made match, from grant to published result

The matcher in `platform` makes the match and its tickets; the arena makes the
match's room from its first ticket (`RoomRegistry.allocateMatch`, D-20),
`RoomThread` plays it, and `MatchResultPublisher` hands the result to the
workers through the stream `s:match-result` ([04 §4](../detailed-design/04-platform-services.md#4-matchmaking),
[01 §8](../detailed-design/01-arena.md#8-match-modes)).

```mermaid
sequenceDiagram
    participant C as client
    participant P as platform
    participant S as store
    participant A as arena
    participant W as worker
    C->>P: queue for a mode over the lobby connection
    P->>P: the matcher fills a roster and picks an arena with a free room
    P->>S: one ticket per player, with the match id, mode and team
    P-->>C: grant with arena host, port, ticket, tls
    C->>A: Join with the ticket
    A->>S: claim the ticket
    A->>A: allocateMatch makes the room on the first ticket
    A->>S: mark the match's first arrival
    A-->>C: Welcome with the mode
    Note over A: waits up to 30 s for the roster, players free to move
    A->>A: startMadeMatch resets the world and spawns everyone
    loop until the clock or a win
        A-->>C: Snapshots, events
        C->>A: Inputs
    end
    A->>A: MatchTally.finish places the players
    A->>A: the result written to the spool on disk
    A->>S: add the result to the result stream, then delete the spool file
    A-->>C: Kick 6, the match is over
    W->>S: read the stream as group rewards
    W->>W: apply it once, to ratings, coins, experience and boards
    C->>P: back in the lobby, where the result lands
```

## Draining an arena

`ArenaMain`'s shutdown hook stops an arena in this order: no new players, the
public rooms ended at once, the made matches played out, then everything
published ([01 §8.6](../detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)).

```mermaid
flowchart TD
    Term["SIGTERM"] --> Withdraw["withdraw from the directory, stop the metrics server"]
    Withdraw --> Refuse["new public joins and new matches refused with Kick 2"]
    Refuse --> Public["stop public rooms: results published, players sent Kick 5"]
    Public --> WaitP["wait up to 5 s for each"]
    WaitP --> Poll{"any made room left"}
    Poll -->|"no"| Done["drained in N s"]
    Poll -->|"yes"| Deadline{"11 minutes passed"}
    Deadline -->|"no"| Sleep["poll again in 200 ms<br/>made matches play on, their players may resume"]
    Sleep --> Poll
    Deadline -->|"yes"| RanOut["the drain ran out"]
    Done --> CloseS["close the listener, stop every room left<br/>a match cut short is published as cutShort"]
    RanOut --> CloseS
    CloseS --> Flush["let every queued kick out"]
    Flush --> Pub["publisher: spool what is in memory, deliver for up to 5 s"]
    Pub --> Last["report the rooms' timings, close the stores, flush the log"]
```

## The wire, message by message

Every frame is `varint length, payload` of at most 8 KiB, the payload's first
byte its type; little-endian except the two big-endian `u32`s. Constants are in
`protocol/Wire` and `protocol/ClientMessage`, mirrored in `client/Core/Wire.cs`;
protocol version 4 ([02 §2–§4](../detailed-design/02-networking.md#2-primitives)).

| Direction | Id | Message | Payload | When |
|---|---|---|---|---|
| client → arena | 1 | `Join` | `u8 version, string ticket, [u8 profile]` | once, within 10 s of connecting |
| | 2 | `Input` | `varint seq, varint ackTick, u8 move, u16 aim, u8 flags` | ten a second while alive and in the foreground |
| | 3 | `UpgradeStat` | `u8 stat` | on tap |
| | 4 | `ChooseClass` | `u8 classId` | on tap |
| | 5 | `Respawn` | none | on tap, while dead |
| | 6 | `Phrase` | `varint phraseId` | at most one per 2 s |
| | 7 | `Ping` | `u32 clientTimeMs`, big-endian | every 10 s in every state |
| | 8 | `Lifecycle` | `u8 state`, 0 foreground, 1 background | on a change |
| | 9 | `Leave` | none | once, then wait for the arena's close |
| | 10 | `Resume` | `u8 version, string secret, [u8 profile]` | in place of `Join` after a lost connection |
| | 11 | `Sandbox` | `u8 action, varint value` | in a sandbox only |
| arena → client | 1 | `Welcome` | `u8 selfHandle, u8 snapshotHz, varint mapW, mapH, u8 mode, varint contentVersion, phraseListVersion, selfEntityId, string resumeSecret, varint mazeSeed` | after a join or a resume |
| | 2 | `Snapshot` | `tickDelta, inputSeqDelta, origin delta`, removes, creates, updates, events | 15 or 10 a second |
| | 3 | `Pong` | `u32 clientTimeMs`, big-endian, `varint serverTick` | for each `Ping` |
| | 4 | `Kick` | `u8 reason` | then the arena closes |

Every event is `u8 type, varint length, payload`, so a client steps over a type
it does not know. A `name` below is `u8 length` and its UTF-8 bytes, empty for
nobody or past 64 bytes.

| Snapshot event | Id | Payload | To |
|---|---|---|---|
| `Death` | 1 | `varint score, name killer` | the player who died |
| `Stats` | 2 | `varint level, xp, xpForNext, u8 unspent, u8[8] points` | the player, on a change, experience alone at most once a second |
| `Phrase` | 3 | `u8 handle, varint phraseId, name speaker` | the speaker's team, or those whose view holds the speaker |
| `Kill` | 4 | `name killer, name victim` | the killer; in a made match everyone |
| `Motion` | 5 | `u8 inputTicks, svarint vx, vy` | the player, every frame while alive |
| `MotionRule` | 6 | `f32 accel, f32 radius`, big-endian bits | the player, when either changes |
| `Skin` | 7 | `u8 handle, u8 skin` | with the create of a tank that has a skin |

| Kick | Meaning | Client's answer |
|---|---|---|
| 1 | ticket or resume secret unknown, used or expired | back to the lobby for a ticket |
| 2 | no room | queue again |
| 3 | protocol version | update the app, never retry |
| 4 | rate limit | a client bug, log it |
| 5 | the server's fault, or the arena stopping | retry with backoff |
| 6 | the made match is over | back to the lobby |
| 7 | removed by an operator | back to the lobby |
