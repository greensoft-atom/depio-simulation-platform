# 06 — Client diagrams

The client's core and its Unity layer, drawn from the code in
[`client/`](../../client/README.md). The design is
[08-client](../detailed-design/08-client.md); the protocol the core speaks is
[02-networking](../detailed-design/02-networking.md), the lobby's
[03-gateway §3](../detailed-design/03-gateway.md#3-lobby-protocol).

## 1. The core's structure

The core's public types, in four groups; every one is in `Backend.Client.Core`
([client/Core/README.md](../../client/Core/README.md)). Only the members that
explain the shape are shown.

### 1.1 The wire and the world

`Wire.cs`, `WireReader.cs`, `Snapshot.cs`, `Messages.cs`, `ClientWorld.cs` and
`Maze.cs`. The decoder streams a frame into a sink; `ClientWorld` is the sink
the connection uses, and keeps the 256 handles between frames.

```mermaid
classDiagram
    class Wire {
        +int Version
        +int SelfHandle
        +float PosScale
        +int TickHz
        +AimToRadians(aim) float
        +QuantiseAim(radians) int
        +SeqNewer(a, b) bool
    }
    class WireReader {
        +U8() int
        +U16() int
        +Varint() long
        +SVarint() long
        +Str() string
    }
    class SnapshotDecoder {
        +Decode(frame, sink)
    }
    class ISnapshotSink {
        +Header(tickDelta, inputSeqDelta, originDx, originDy)
        +Remove(handle)
        +CreateTank()
        +CreatePredicted()
        +CreateStatic()
        +CreateUnit()
        +Update(update)
        +Event(type, payload)
    }
    class ClientWorld {
        +long ServerTick
        +int LastProcessedInputSeq
        +bool SelfCreated
        +Apply(frame) long
        +EventAt(i) MatchEvent
        +ExpirePredicted()
    }
    class Entity {
        +int Kind
        +int X
        +int Y
        +int Angle
        +PredictedAt(tick, x, y)
    }
    class MatchEvent {
        +int Type
        +string Killer
        +string Victim
    }
    class SelfMotion {
        +bool HasRule
        +float Accel
        +float Radius
        +int InputTicks
    }
    class Removal {
        +int Handle
        +int X
        +int Y
    }
    class Maze {
        +Walls(seed)
    }
    class Wall {
        +Hits(x, y, r) bool
    }
    class ServerMessages {
        +ReadWelcome(frame) Welcome
        +ReadPong(frame) Pong
        +ReadKick(frame) int
    }
    class ClientMessages {
        +Join(ticketId, profile)
        +Resume(secret, profile)
        +Input(seq, ackTick, move, aim, flags)
        +Ping(clientTimeMs)
        +Leave()
    }
    class FrameReader {
        +Feed(bytes, onFrame)
    }
    class ProtocolException
    SnapshotDecoder ..> ISnapshotSink : streams into
    SnapshotDecoder ..> WireReader : reads with
    SnapshotDecoder ..> ProtocolException : throws
    ClientWorld ..|> ISnapshotSink
    ClientWorld "1" *-- "256" Entity : handles
    ClientWorld *-- SelfMotion : Motion
    ClientWorld *-- MatchEvent : the last frame's events
    ClientWorld *-- Removal : the last frame's removals
    ClientWorld *-- Wall : a maze's walls
    Maze ..> Wall : makes from the seed
    ServerMessages ..> WireReader : reads with
```

### 1.2 The match connection and the own tank

`MatchConnection.cs`, `OwnTank.cs` and `TankMotion.cs`. A connection makes a
new `ClientWorld` with every `Welcome` and keeps one `OwnTank`, stepped by the
room's own rule.

```mermaid
classDiagram
    class MatchConnection {
        +MatchState State
        +MatchEnd End
        +ClientWorld World
        +OwnTank OwnTank
        +bool Alive
        +long RoundTripMs
        +string LastProblem
        +Join(ticketId)
        +Resume(secret)
        +SetInput(move, aim, fire, flags)
        +SetBackgrounded(backgrounded)
        +Poll()
        +Leave()
        +Dispose()
    }
    class MatchSettings {
        +string Host
        +int Port
        +bool Tls
        +int Profile
    }
    class MatchState {
        Connecting
        Joining
        InMatch
        Lost
        Ended
    }
    class MatchEnd {
        Left
        BadTicket
        RoomFull
        ProtocolVersion
        RateLimit
        ServerFault
        Unreachable
        ResumeExpired
        MatchOver
        Removed
    }
    class IClock {
        +long NowMs
    }
    class SystemClock
    class OwnTank {
        +bool Predicting
        +float Angle
        +long Compared
        +int Jumps
        +OnFrame(world, mapWidth, mapHeight, now)
        +Advance(now, move, aim, pendingSeq)
        +Sent(seq, move)
        +DrawPosition(now, x, y)
    }
    class TankMotion {
        +Step(state, move, accel, radius, width, height, walls)
    }
    class MotionState {
        +float X
        +float Y
        +float Vx
        +float Vy
    }
    class WallGrid {
        +PushOut(state, radius)
    }
    MatchConnection --> MatchSettings : dials by
    MatchConnection --> IClock : timed by
    SystemClock ..|> IClock
    MatchConnection *-- ClientWorld : a new one each Welcome
    MatchConnection *-- OwnTank
    MatchConnection --> MatchState
    MatchConnection --> MatchEnd
    MatchConnection ..> ClientMessages : writes with
    MatchConnection ..> FrameReader : reads with
    OwnTank ..> TankMotion : steps by
    OwnTank ..> WallGrid : a maze's walls
    TankMotion ..> MotionState
```

### 1.3 The lobby, the API and the account

`LobbyClient.cs`, `PartyState.cs`, `ApiClient.cs`, `AccountKeeper.cs` and
`Json.cs`. The lobby keeps what it was told; the API answers each call with an
`ApiResult`; the account keeper signs in by the key a secure store keeps.

```mermaid
classDiagram
    class LobbyClient {
        +LobbyState State
        +long PlayerId
        +MatchGrant Grant
        +string QueueState
        +MatchReady Ready
        +PartyInfo Party
        +TeamInfo Team
        +TournamentGrant TournamentMatch
        +Connect(token)
        +RequestMatch()
        +JoinQueue(mode)
        +AcceptMatch()
        +DeclineMatch()
        +InviteToParty(playerId)
        +TakeGrant() MatchGrant
        +Poll()
        +Dispose()
    }
    class LobbyState {
        Connecting
        Ready
        Reconnecting
        Replaced
        InvalidSession
        Closed
    }
    class PartyState {
        +PartyInfo Party
        +Apply(state) bool
    }
    class ApiClient {
        +string Current
        +Login(username, password)
        +CreateGuest()
        +LoginGuest(guestKey)
        +RequestMatch(token)
        +Queue(token)
        +Party(token)
        +Classes()
        +NewPurchaseKey() string
    }
    class ApiResult~T~ {
        +bool Ok
        +T Value
        +int Status
        +string Code
        +int RetryAfterSeconds
    }
    class AccountKeeper {
        +SignIn()
    }
    class ISecureStore {
        +Get(key) string
        +Set(key, value)
        +Delete(key)
    }
    class MemorySecureStore
    class JsonValue {
        +Parse(text) JsonValue
        +string AsString
        +long AsLong
    }
    class Session {
        +string Token
        +long PlayerId
    }
    class MatchGrant {
        +string ArenaHost
        +int ArenaPort
        +string TicketId
        +bool Tls
    }
    LobbyClient *-- PartyState
    LobbyClient --> LobbyState
    LobbyClient ..> JsonValue : messages
    LobbyClient --> MatchGrant : Grant
    PartyState ..> ApiClient : ReadParty
    ApiClient ..> ApiResult : answers
    ApiClient ..> JsonValue : bodies
    ApiClient ..> Session : a login answers
    AccountKeeper --> ApiClient : makes and logs in a guest
    AccountKeeper --> ISecureStore : keeps the guest key
    MemorySecureStore ..|> ISecureStore
```

### 1.4 What a frame draws, and the sticks

`Scene.cs` and `TouchSticks.cs`. The layer above passes the render clock's tick
and the own tank's drawn position to `Scene.Build`; the sticks become the
arguments of `MatchConnection.SetInput`.

```mermaid
classDiagram
    class Scene {
        +Items
        +Build(world, renderTick)
        +Build(world, renderTick, selfPredicted, x, y, angle)
    }
    class DrawItem {
        +int Handle
        +int Kind
        +float X
        +float Y
        +float Angle
        +float Radius
        +int ClassId
        +int Subtype
        +int Team
        +int Skin
        +float Health
        +string Name
        +bool Self
    }
    class RenderClock {
        +Frame(tick, now)
        +RenderTick(now) double
    }
    class TouchSticks {
        +float DeadZone
        +float FireAt
        +MoveMask(x, y) int
        +Aim(x, y) int
        +Fires(x, y) bool
        +AimAt(fromX, fromY, toX, toY) int
    }
    Scene *-- DrawItem : reused frame to frame
    Scene ..> ClientWorld : reads
```

## 2. MatchConnection's lifecycle

`MatchConnection.Poll` moves the state; the receive thread only connects,
reads and queues ([08 §3](../detailed-design/08-client.md#3-the-match-connection)).
`End` says why it ended, and what follows is the layer above's.

```mermaid
stateDiagram-v2
    [*] --> Connecting : Join or Resume, a thread dials
    Connecting --> Joining : connected, Join or Resume sent
    Connecting --> Lost : closed or not connected in time, a secret known
    Connecting --> Ended : closed before any Welcome, Unreachable
    Joining --> InMatch : Welcome, a new world
    Joining --> Lost : closed, or no Welcome in 10 s, a secret known
    Joining --> Ended : a Kick, or closed or timed out with no secret
    InMatch --> Lost : closed, a desync, or no frame for 3 s alive, 13 s dead
    InMatch --> Ended : a Kick, or Leave
    Lost --> Connecting : the wait is over, 0.5 then 1 2 4 8 s
    Lost --> Ended : 60 s since the loss, ResumeExpired
    Lost --> Ended : Leave
    Ended --> [*]
```

## 3. LobbyClient's lifecycle

`LobbyClient.Poll` moves the state; a reading task and a writing task serve the
socket ([08 §5](../detailed-design/08-client.md#5-the-lobby-and-the-api)). The
wait before each reconnect starts at 1 s, doubles to 30 s at most, is jittered
±50 %, and goes back to 1 s on `auth.ok`.

```mermaid
stateDiagram-v2
    [*] --> Closed
    Closed --> Connecting : Connect, the auth queued first
    Connecting --> Ready : auth.ok, the wait back to 1 s
    Connecting --> InvalidSession : invalid_session answered to the auth
    Connecting --> Reconnecting : the socket closed
    Ready --> Reconnecting : the socket closed, or a ping then 10 s with nothing heard
    Reconnecting --> Connecting : the wait is over, the next one doubled
    Ready --> Replaced : evt.session.replaced
    Ready --> InvalidSession : evt.session.revoked
    Connecting --> Closed : Dispose
    Ready --> Closed : Dispose
    Reconnecting --> Closed : Dispose
    Replaced --> [*]
    InvalidSession --> [*]
    Closed --> [*]
```

## 4. Join and resume, from the client's side

`MatchConnection` and its receive thread against an arena
([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)):
a join, the frames and inputs of a match, a lost connection resumed, and a
`Leave` that waits for the arena to close first.

```mermaid
sequenceDiagram
    participant L as layer above
    participant M as MatchConnection
    participant T as receive thread
    participant A as arena
    L->>M: Join with the grant's ticket
    M->>T: a new attempt dials
    T->>A: TCP connect, then TLS when the grant says
    L->>M: Poll, once a frame
    M->>A: Join, version 4 first, the ticket, the profile
    A-->>T: Welcome with the resume secret and the maze seed
    T-->>M: queued frames, applied in Poll
    loop every frame
        A-->>T: Snapshot, fifteen a second
        L->>M: SetInput, then Poll
        M->>A: Input every 100 ms with the ack, Ping every 10 s
    end
    Note over T,A: the socket lost, or no frame for 3 s (13 s while dead)
    M->>M: Lost, the first wait 0.5 s
    M->>T: a new attempt dials the same arena
    M->>A: Resume, version 4 first, the latest secret
    alt the stay is waiting
        A-->>T: Welcome with a new secret
        T-->>M: a fresh view, the same tank
    else the stay is over
        A-->>T: Kick 1
        T-->>M: Ended, BadTicket, back to the lobby
    end
    L->>M: Leave
    M->>A: Leave
    A-->>T: the arena closes
    T->>T: the socket closed, or after 2 s regardless
```

## 5. The own tank: prediction and reconciliation

`OwnTank`, stepped from `MatchConnection.Poll` and reconciled with each frame
([08 §4](../detailed-design/08-client.md#4-the-world-and-when-things-are-drawn),
D-62). `TankMotion.Step` is each step.

```mermaid
flowchart TD
    P["MatchConnection.Poll"] --> AL{"Alive and in the foreground?"}
    AL -->|"no"| STOP["Stop: nothing predicted, the server's newest sample drawn"]
    AL -->|"yes"| ADV["Advance: one step each 40 ms by the clock, with the input held, stamped with the next seq"]
    ADV --> DUE{"An input due, every 100 ms?"}
    DUE -->|"yes"| SENT["Input sent: its seq's steps take the move sent"]
    F["A snapshot applied"] --> SELF{"Own tank alive, a MotionRule held, a Motion in the frame?"}
    SELF -->|"no"| SRV["Not predicted: drawn at the newest sample"]
    SELF -->|"yes"| NEW{"Not predicting yet, or created afresh?"}
    NEW -->|"yes"| BEGIN["Begin: steps cleared, the present the server's"]
    NEW -->|"no"| MATCH["The step that matches: the first stamped with the echoed seq, plus the input's ticks, less one"]
    MATCH --> FOUND{"Among the 64 kept?"}
    FOUND -->|"yes"| REPLAY["Error measured, the step put where the server has it, the steps after it stepped again"]
    FOUND -->|"no"| RESET["The present put where the server has it"]
    REPLAY --> MOVED{"The present moved more than 64 units?"}
    RESET --> MOVED
    MOVED -->|"yes"| JUMP["A jump: taken at once"]
    MOVED -->|"no"| CORR["A correction: drawn away, halving every 50 ms"]
    JUMP --> DRAW["DrawPosition: between the last two steps by the time since, plus what is left of the correction"]
    CORR --> DRAW
```

## 6. The render clock and the scene

`RenderClock` and `Scene`, called by the layer above once a frame
([08 §8](../detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77)):
everything but the own tank is drawn a little in the past, between samples the
world already has.

```mermaid
flowchart TD
    A["A snapshot applied: SnapshotsApplied moved"] --> B["RenderClock.Frame: the newest tick and when it came, the tick delta averaged, seven eighths old and one eighth new"]
    C["Each frame: RenderClock.RenderTick now"] --> D["The newest tick, plus the ticks since it came at 25 a second, less twice the averaged delta"]
    B --> D
    D --> E["Never past the newest, never back"]
    E --> F["Scene.Build at the render tick"]
    F --> G{"Each live handle: what is it?"}
    G -->|"the own tank, predicted"| H["Where OwnTank.DrawPosition puts it, in the present"]
    G -->|"the own tank not predicted, or a shape"| I["Where it is: its newest sample"]
    G -->|"a bullet"| J["Extrapolated from its create by PredictedAt, never past its wall"]
    G -->|"another tank, or a unit"| K["Between its two samples, the angle the shorter way round"]
    H --> L["A DrawItem, reused: world units, radians, radius, class or subtype, team, skin, health, name"]
    I --> L
    J --> L
    K --> L
```

## 7. AccountKeeper's sign-in

`AccountKeeper.SignIn` over `ApiClient` and an `ISecureStore`
([08 §8](../detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77)).
Its continuations stay on the caller's context, so in Unity the store is
touched on the main thread.

```mermaid
flowchart TD
    S["AccountKeeper.SignIn"] --> HAS{"A key under backend.guestKey?"}
    HAS -->|"no, a first launch"| MAKE["CreateGuest: POST /v1/guests"]
    MAKE --> MADE{"Made?"}
    MADE -->|"no"| REFUSED["Its refusal answered: status, code, Retry-After"]
    MADE -->|"yes"| KEEP["The key kept: Set"]
    KEEP --> LOGIN["LoginGuest: POST /v1/sessions with the key"]
    HAS -->|"yes"| LOGIN
    LOGIN --> ANS{"The answer"}
    ANS -->|"200"| SESSION["A session: its token for the lobby"]
    ANS -->|"401"| FORGET["The key refused and forgotten: Delete. A new guest only when SignIn is called again"]
    ANS -->|"another refusal, or none"| KEPT["The key kept, the refusal answered"]
```

## 8. PartyState's rule

`PartyState.Apply`, called by `LobbyClient` for each party answer and each
`evt.party.update` (D-74): answers and pushes come by different paths, so a
state can arrive after a newer one.

```mermaid
flowchart TD
    IN["A party state: a party request's answer, or evt.party.update"] --> N["The party it names: partyId, or was, the party a change ended"]
    N --> V{"A version?"}
    V -->|"no, from a platform before D-74"| APPLY["Applied: Party read by ApiClient.ReadParty, null for none"]
    V -->|"yes"| ENDED{"Names a party it saw end?"}
    ENDED -->|"yes"| LATE["Not applied: a late state of an ended party"]
    ENDED -->|"no"| SAME{"Names the party the last applied state named?"}
    SAME -->|"no"| APPLY
    SAME -->|"yes"| NEWER{"Newer than the last applied?"}
    NEWER -->|"yes"| APPLY
    NEWER -->|"no"| OLD["Not applied: an older state of the same party"]
    APPLY --> REM["The party named and the version remembered, and a party that ended remembered as ended"]
```

## 9. The Unity layer's wiring

The package's scripts over the core
([client/Unity/com.backend.client](../../client/Unity/com.backend.client/README.md)):
`BackendClient` owns the core's objects and pumps them in `Update`; the
others read what it built or feed it.

```mermaid
flowchart LR
    subgraph U["Unity layer: Backend.Client.Unity"]
        LS["LobbyScreen"]
        HUD["Hud"]
        TC["TouchControls"]
        BC["BackendClient"]
        WV["WorldView"]
        FC["FollowCamera"]
        LT["LookTable asset"]
        SS["SecureStores"]
    end
    subgraph C["Core: Backend.Client.Core.dll"]
        AK["AccountKeeper"]
        API["ApiClient"]
        LC["LobbyClient"]
        MC["MatchConnection"]
        RC["RenderClock"]
        SC["Scene"]
        TS["TouchSticks"]
    end
    subgraph K["Where the key is kept"]
        KC["iOS keychain: BackendKeychain.mm"]
        KS["Android keystore: SecureStore.java"]
        PP["PlayerPrefs: the editor and other builds"]
    end
    LS -->|"SignIn, PlayNow, Queue"| BC
    LS -->|"AcceptMatch, DeclineMatch, LeaveQueue"| LC
    BC -->|"SignIn"| AK
    AK --> API
    AK --> SS
    SS --> KC
    SS --> KS
    SS --> PP
    BC -->|"Update: Poll, TakeGrant"| LC
    BC -->|"a grant taken: Join, then Poll"| MC
    BC -->|"OnApplicationPause: SetBackgrounded"| MC
    BC -->|"Frame, RenderTick"| RC
    BC -->|"Build each frame"| SC
    TC --> TS
    TC -->|"SetInput"| MC
    HUD -->|"OnEvent, Respawn, Leave"| MC
    WV -->|"LateUpdate: Items"| SC
    WV --> LT
    FC -->|"LateUpdate: the own tank"| SC
```
