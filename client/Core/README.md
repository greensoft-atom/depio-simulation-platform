# Core: Backend.Client.Core

This is the client's core: one library that does everything the client says to the server, and works out what a
frame draws, with no engine in it ([08 §1](../../docs/detailed-design/08-client.md#1-two-layers)). It is for
developers who write the layer above it, such as the Unity scripts, or who change the core itself. This page covers
where the core runs, its threads, its public types, a sketch of a client built on it, and its numbers.

**Where it runs.**

- It is .NET Standard 2.0 and C# 7.3 (D-108). Unity 2019.4 compiles it at either API compatibility level, and so do
  Unity 2022.3 and Unity 6. Visual Studio 2019 builds it for .NET Framework 4.7.1.
- `../unity2019-check.sh` compiles it with Unity 2019.4.28f1's own compiler and runs it on Unity's Mono.
- It has no package references, so it goes into a Unity project as one library (`../unity-package.sh` builds and
  copies it).
- On this machine the .NET SDK runs it: its tests and the headless drills drive it against the real server.
- It uses no spans, which .NET Standard 2.0 does not have: a frame is an `ArraySegment<byte>`.

**TLS** (Transport Layer Security). The site's certificates come from the backend's own certificate authority (CA),
not from a public one (D-82). So the core checks a server's certificate itself, against the backend's root
certificate (`TrustRoot`, D-108), the same way on every runtime:

- the API goes through `HttpWebRequest`, under `HttpClient`, and each request is checked for its own host;
- the lobby uses a WebSocket of the core's own over `SslStream`;
- a match uses `SslStream`, checked for the grant's TLS name.

`HttpClient` is in the assembly `System.Net.Http`. A Unity project references it by default at the .NET Standard
2.0 level, but not at the .NET 4.x level: there, the package's `csc.rsp` adds it (D-113).

The core's structure, and the lifecycles below, are drawn in
[docs/diagrams/06-client.md](../../docs/diagrams/06-client.md).

## Threads

One thread owns the core: the main thread of the layer above, which calls each `Poll` once a frame
([08 §2](../../docs/detailed-design/08-client.md#2-threads)).

| Thread | What it does |
|---|---|
| The main thread | Calls `MatchConnection.Poll` and `LobbyClient.Poll`. Inside them, frames are applied, events and pushes are told, timers run and messages are sent. It builds the scene, and it makes every call below unless the table says otherwise |
| A match connection's receive thread (named `match-connection`), one per attempt | Connects, does the handshakes, reads bytes, splits them into frames and puts the frames in a queue |
| A lobby connection's two tasks | One reads whole messages into a queue; the other writes what `Send` queued |
| The thread pool | `ApiClient`'s calls complete there. `AccountKeeper`'s continuations stay on the caller's context, so the secure store is touched on the thread that called `SignIn` |

No other thread writes anything that the main thread reads, except those queues and a connection's own flags
(connected, closed, the failure). The events (`MatchConnection.OnEvent`, `LobbyClient.OnPush`, `OnPartySaid`,
`OwnTank.OnReconciled`) are raised inside `Poll`, on the main thread.

## Public API

### Wire and snapshot decoding

| Type | What it is for |
|---|---|
| `Wire` | The protocol's numbers, mirroring the server's `com.backend.protocol.Wire` and `ClientMessage`: `Version` 4, the message ids, kick reasons 1 to 7, move and flag bits, entity kinds, update masks, event ids 1 to 7, `PosScale` 4, `VelocityScale` 256, `TickHz` 25, `SelfHandle` 1. Also `SpeedOf`, `AimToRadians`, `QuantiseAim`, `SeqNewer` (24-bit) and `JavaRound` (half up, as Java rounds) |
| `WireReader` | A `ref struct` over one frame's bytes: little-endian fields, LEB128 varints, zigzag, strings. It allocates nothing but the strings it returns |
| `ProtocolException` | The server sent something this client cannot read: a desync, answered by a resume |
| `SnapshotDecoder` | Decodes one snapshot frame into an `ISnapshotSink`, in the frame's order. It refuses unknown kinds and trailing bytes |
| `ISnapshotSink` | What the decoder streams into: the header, removes, creates of four kinds, updates, events |
| `SnapshotUpdate` | One update. Only the fields whose bits are in `Mask` were sent |
| `Welcome`, `Pong` | The first frame after a `Join` or `Resume` (handle, rate, map size, mode, the content and phrase list versions, entity id, resume secret, maze seed), and a ping's answer |
| `ServerMessages` | Reads `Welcome`, `Pong` and `Kick` |
| `ClientMessages` | Writes the client's messages, each framed, into one reused 128-byte buffer: `Join`, `Resume`, `Input`, `UpgradeStat`, `ChooseClass`, `Respawn`, `Phrase`, `Sandbox`, `Ping`, `Lifecycle`, `Leave` |
| `FrameReader` | Splits the byte stream into frames (a varint length, at most 64 KiB), and hands each on as a new array |

### The world

| Type | What it is for |
|---|---|
| `ClientWorld` | What the client holds between snapshots: 256 handles; positions in world space; predicted bullets, extrapolated from their create and ended at a maze's wall; the input seq the server echoed; the own tank's motion. Members: `Apply(frame)`, `this[handle]`, `EventCount` and `EventAt`, `Removals`, `Walls`, `ServerTick` |
| `Entity` | One handle's entity, reused: kind, position, angle, health, level, class, team, flags, name, skin, and the sample before the newest; for a predicted entity, its heading, speed, owner and death tick; `PredictedAt(tick)` |
| `MatchEvent` | One event of the last frame, reused: a death, the player's own progression, a phrase or a kill |
| `SelfMotion` | The own tank's `Motion` and `MotionRule`, kept as state for the prediction, never told as events |
| `Removal` | An entity that the last frame removed, or a wall ended, and where: a hit to draw |
| `Wall`, `Maze` | A maze's wall; and the server's maze generator in C#, which makes the walls from the `Welcome`'s seed |

### The match connection

| Type | What it is for |
|---|---|
| `MatchConnection` | One player's connection to one arena, made with `new MatchConnection(settings, clock)`. Calls: `Join(ticket)` or `Resume(secret)`; `Poll()` once a frame; `SetInput`, `SetBackgrounded`, `Respawn`, `UpgradeStat`, `ChooseClass`, `Say`, `SetLevel`, `SummonGuardian`, `Leave`, `Dispose`. State: `State`, `End`, `World`, `OwnTank`, `Welcome`, `Alive` (false from a death until the respawn), `SnapshotsApplied`, `Resumes`, `RoundTripMs`, `LastProblem`. Callbacks: `OnEvent`, and `OnFrame` (after each frame, while the world still holds its removals) |
| `MatchSettings` | Where and how to dial, taken from the grant: `Host`, `Port`, `Tls`, `TlsName` (the name the handshake sends; null means `Host`), `Trust` (the backend's root, checked for the name the handshake sends; null means the runtime's own check), `Profile` (−1 leaves it out, which the arena reads as mobile) |
| `MatchState` | `Connecting`, `Joining`, `InMatch`, `Lost`, `Ended` |
| `MatchEnd` | Why it ended, each with its own next step ([08 §3](../../docs/detailed-design/08-client.md#3-the-match-connection)): `None` while it runs; `Left`, `BadTicket`, `RoomFull`, `ProtocolVersion`, `RateLimit`, `ServerFault`, `Unreachable`, `ResumeExpired`, `MatchOver`, `Removed` |
| `IClock`, `SystemClock` | Milliseconds that never go backwards. A test's clock can be moved by hand |

### The own tank, predicted

The **own tank** is the player's own tank, which the client predicts so that it moves at once, before the server
answers.

| Type | What it is for |
|---|---|
| `OwnTank` | Steps the own tank at 25 Hz by the clock, and stamps each step with the seq that will carry it. On each frame it matches the frame to a step, puts the tank where the server has it, replays the later steps, and draws the correction away (halved every 50 ms; over 64 units, taken at once). Members: `Predicting`, `DrawPosition(now, out x, out y)`, `Angle`, `Compared`, `Jumps`, `OnReconciled` |
| `TankMotion`, `MotionState` | The room's step of a driven tank, in single precision and in the server's order, so the bits agree |
| `WallGrid` | A maze's walls by the 300-unit cells they cover, and the push out of them |

### The API

| Type | What it is for |
|---|---|
| `ApiClient` | The platform API over HTTPS. Made with `new ApiClient(endpoints, handler, timeout)`: the endpoints are tried in turn; the handler is `TrustRoot.HttpHandler()` for the backend's root (null: .NET's default handler, with the runtime's own certificate check); the timeout is 10 s unless given. One method for each route, each answering an `ApiResult`. `Current` is the endpoint that the last call reached. The routes are in the backend's [API table](../../backend/README.md#the-platform-api) |
| `ApiResult<T>` | `Ok` and `Value`; or `Status`, the server's `Code` and `Message`, and `RetryAfterSeconds`. `Unreachable` (status 0) when no endpoint answered |
| What the calls answer | `Session`, `Guest`, `MatchGrant`, `QueueStatus`, `PartyInfo`, `PartyMember`, `MatchReady`, `PartyInvitation`, `PartyPhrase`, `ClassTableInfo`, `ClassInfo`, `BarrelInfo`, `PhraseListInfo`, `PhraseInfo`, `SkinTable`, `Offer`, `Holdings`, `Holding`, `ItemLevel`, `Receipt`, `Pack`, `PaymentOrder`, `PaymentResult`, `PassInfo`, `PassTier`, `PassReward`, `PremiumAnswer`, `Loadout`, `ActiveBoost`, `BoostsAnswer`, `TeamInfo`, `TeamMemberInfo`, `Renamed`, `TeamFound`, `TeamApplicant`, `TeamInvite`, `TournamentInfo`, `TournamentEntry`, `RosterPlayer`, `BracketMatch`, `TournamentStanding`, `TournamentGrant`, `PlayerRef`, `FriendInfo`, `FriendRequest`, `FriendsInfo`, `InboxItem`, `RankRow`, `AchievementInfo`, `GoalInfo`, `GoalsInfo`, `SeasonInfo`, `SeasonsInfo` |

A `MatchGrant`, which a match request or the lobby gives, holds `ArenaHost`, `ArenaPort`, `TicketId`, `Tls`,
`TlsName` (the arena's name behind the front; null means `ArenaHost`) and `Mode` (null for a seat in the public
arena).

`ApiClient`'s calls, by area:

| Area | Calls |
|---|---|
| Accounts | `Register`, `Login`, `CreateGuest`, `LoginGuest`, `UpgradeAccount`, `Logout`, `DeleteAccount`, `Rename` |
| Matches | `RequestMatch`, `JoinQueue`, `LeaveQueue`, `Queue`, `OpenSandbox`, `Party` |
| Content | `Classes`, `Phrases`, `Skins` |
| The economy | `Shop`, `Inventory`, `RaiseItemLevel`, `Purchase`, `Packs`, `PlaceOrder`, `GetOrder`, `SimulatePayment`, `SeasonPass`, `BuyPremium`, `Equipment`, `Wear`, `TakeOff`, `Boosts`, `ActivateBoost`; and `NewPurchaseKey()`, a key for one tap of Buy |
| Teams | `CreateTeam`, `MyTeam`, `RenameTeam`, `InviteToTeam`, `TeamInvites`, `AcceptTeamInvite`, `DeclineTeamInvite`, `FindTeams`, `ApplyToTeam`, `WithdrawApplication`, `MyApplications`, `TeamApplications`, `AnswerApplication`, `LeaveTeam`, `KickFromTeam`, `SetTeamRole`, `HandOverTeam`, `DisbandTeam` |
| Tournaments | `Tournaments`, `Tournament`, `EnterTournament`, `WithdrawFromTournament`, `TournamentMatch` |
| Friends and the inbox | `Friends`, `AskFriend`, `RemoveFriend`, `DropFriendRequest`, `Blocks`, `Block`, `Unblock`, `Inbox`, `ReadInbox` |
| Boards and progress | `Leaderboard`, `AroundMe`, `Achievements`, `Goals`, `Seasons` |

### The lobby

| Type | What it is for |
|---|---|
| `LobbyClient` | The lobby WebSocket ([03 §3](../../docs/detailed-design/03-gateway.md#3-lobby-protocol)), made with `new LobbyClient(uri, clock, trust)` (the trust is the backend's root; null means the runtime's own check). Calls: `Connect(token)`, then `Poll()` once a frame; `RequestMatch`, `JoinQueue`, `LeaveQueue`, `AcceptMatch`, `DeclineMatch`, `InviteToParty`, `AcceptInvitation`, `LeaveParty`, `KickFromParty`, `SayToParty`; `Close`, `Dispose`. What it was told: `State`, `PlayerId`, `Grant` (taken by `TakeGrant()` to join it), `QueueState`, `Ready`, `Party`, `Team`, `Invitation`, `TournamentMatch`, `LastError`, and the refusals by request (`GrantRefusal`, `QueueRefusal`, `AnswerRefusal`, `PartyRefusal`). Events: `OnPush`, `OnPartySaid` |
| `LobbyState` | `Connecting`, `Ready`, `Reconnecting`, `Replaced`, `InvalidSession`, `Closed` |
| `PartyState` | The party as the newest state told it, by its version (D-74). An older state of the same party, or a late one of a party it saw end, is not applied |

### TLS: the backend's root

| Type | What it is for |
|---|---|
| `TrustRoot` | The backend's root certificate, which the client trusts alone for the API, the lobby and matches (D-82). Made with `new TrustRoot(certificate)` or `TrustRoot.FromPem(text)` (`ca.pem` from `private-ca.sh`); a root whose key is not RSA is refused with an `ArgumentException` that says why. `Accepts(certificate, host)`: the root issued it (RSA PKCS#1, SHA-256), it is valid now, and its subjectAltName holds the host, an IP address or a DNS name. `CheckFor(host)`: that check for one `SslStream`. `HttpHandler()`: the handler for `ApiClient`, which checks each request for its own host. Nothing is pinned, so the server's certificate can be renewed without a new release of the app |

### What a frame draws, and the sticks

| Type | What it is for |
|---|---|
| `Scene` | The list a frame draws, reused: `Build(world, renderTick)`, or with the own tank where its prediction puts it; `Items` |
| `DrawItem` | One thing to draw: handle, kind, position in world units (y grows down), angle in radians, radius, class or subtype, team, skin, level, flags, health from 0 to 1, name, and whether it is the player's own |
| `RenderClock` | The render tick: the newest frame's tick, plus the time since that frame came, less twice the frames' averaged tick delta; never past the newest frame and never back. `Frame(tick, now)`, `RenderTick(now)` |
| `TouchSticks` | A stick's offset (a fraction of its reach, y up) to the move mask, in eight sectors past a dead zone of 0.2, and to the wire's aim; fire past 0.5 of the reach. Also keys to a mask, and a mouse's aim at a world point |

### The account, and JSON

| Type | What it is for |
|---|---|
| `AccountKeeper` | `SignIn()`: on the first launch it makes a guest and keeps the guest's key in the store (`backend.guestKey`); on every launch after, it logs in by that key. A key that the server refuses (401) is forgotten, and a new guest is made only when `SignIn` is called again. `DeleteAccount(token, password)` deletes the account (D-84) and forgets a guest's key with it |
| `ISecureStore`, `MemorySecureStore` | Where the key survives restarts: three calls, `Get`, `Set`, `Delete`. In the Unity layer it is the device's keychain or keystore; in the tests, memory |
| `JsonValue` | Strict JSON (RFC 8259, nested at most 32 deep) for the small messages of the API and the lobby: `Parse`, `ToString`, members by name or index, `AsString`, `AsLong`, `AsInt`, `AsFloat`, `AsBool` |

## Using it from a layer above

A sketch of one player's client, in whatever code drives the frames. It logs in, connects to the lobby, asks for a
match, makes and polls the connection, and draws the scene. `BackendClient` in the Unity package is this, wired to
Unity.

It uses the site's one address (here `203.0.113.10`) and the backend's root, as the site needs (D-82). The core
dials what it is given, in any build (D-115): for an API and a lobby in clear, give `http://` and `ws://` addresses.
A grant from the site still asks for TLS on 9001, so a match there still needs the root. A backend run plain
(`PLAIN=1`, D-101) gives grants without TLS.

```csharp
using System;
using System.Threading.Tasks;
using Backend.Client.Core;

public sealed class Player
{
    private readonly IClock _clock = new SystemClock();
    private readonly TrustRoot _root;
    private readonly ApiClient _api;
    private readonly Scene _scene = new Scene();
    private LobbyClient _lobby;
    private MatchConnection _match;
    private ClientWorld _world;
    private RenderClock _render;
    private long _applied = -1;

    // caPem: the backend's root, ca.pem's text (operations/06), which every connection is checked against.
    public Player(string caPem)
    {
        _root = TrustRoot.FromPem(caPem);
        _api = new ApiClient(new[] { "https://203.0.113.10" }, _root.HttpHandler());
    }

    // 1. Log in: the device's guest, made on the first launch, its key kept in the store.
    public async Task<bool> SignIn(ISecureStore store)
    {
        ApiResult<Session> session = await new AccountKeeper(_api, store).SignIn();
        if (!session.Ok) return false;                 // session.Status, Code, RetryAfterSeconds
        // 2. The lobby, authenticated with the session's token.
        _lobby = new LobbyClient(new Uri("wss://203.0.113.10/lobby"), _clock, _root);
        _lobby.Connect(session.Value.Token);
        return true;
    }

    // 3. A seat in the public arena; the grant lands in _lobby.Grant. A queue: _lobby.JoinQueue("duel").
    public void PlayNow()
    {
        if (_lobby != null && _lobby.State == LobbyState.Ready) _lobby.RequestMatch();
    }

    public void Paused(bool paused) => _match?.SetBackgrounded(paused);

    public void Quit() => _match?.Leave();

    // Once a frame, on the main thread: the sticks' offsets as fractions of their reach, y up.
    public void Frame(float moveX, float moveY, float aimX, float aimY)
    {
        _lobby?.Poll();
        // 4. Connect: a grant, answered to match.request or pushed as evt.match.found. Taken, so the
        //    lobby's queue is "none" again for the next match.
        if (_match == null && _lobby?.Grant != null)
        {
            MatchGrant grant = _lobby.TakeGrant();
            _match = new MatchConnection(new MatchSettings
            {
                Host = grant.ArenaHost, Port = grant.ArenaPort, Tls = grant.Tls, TlsName = grant.TlsName, Trust = _root,
            }, _clock);
            _match.OnEvent = e =>                      // every event of every frame; copy what is kept
            {
                if (e.Type == Wire.EvtKill) Console.WriteLine($"{e.Killer} > {e.Victim}");
            };
            _match.Join(grant.TicketId);
        }
        if (_match == null) return;

        // 5. Poll: the input held, then frames applied, pings, inputs and resumes as they fall due.
        _match.SetInput(TouchSticks.MoveMask(moveX, moveY), TouchSticks.Aim(aimX, aimY),
                        TouchSticks.Fires(aimX, aimY), 0);
        _match.Poll();

        // 6. Draw: a render clock for each world (a resume makes a new one), the scene at its tick.
        if (!ReferenceEquals(_match.World, _world))
        {
            _world = _match.World;
            _render = new RenderClock();
            _applied = -1;
        }
        if (_match.SnapshotsApplied != _applied)
        {
            _applied = _match.SnapshotsApplied;
            _render.Frame(_match.World.ServerTick, _clock.NowMs);
        }
        double tick = _render.RenderTick(_clock.NowMs);
        if (!double.IsNaN(tick))
        {
            OwnTank own = _match.OwnTank;
            float x = 0, y = 0;
            if (own.Predicting) own.DrawPosition(_clock.NowMs, out x, out y);
            _scene.Build(_match.World, tick, own.Predicting, x, y, own.Angle);
            foreach (DrawItem d in _scene.Items)
            {
                // Draw d.Kind at (d.X, d.Y), turned d.Angle, d.Radius across, coloured by d.Team.
            }
        }

        // 7. Ended: _match.End says what follows (08 §3); a lost connection is resumed without ending.
        if (_match.State == MatchState.Ended)
        {
            _match.Dispose();
            _match = null;
        }
    }
}
```

The layer above still owns these:

- what follows each `MatchEnd`;
- the queue's screens (`Ready`, `AcceptMatch`, `DeclineMatch`);
- fetching the queue and the party again after the lobby reconnects;
- the rest of [08 §8's list](../../docs/detailed-design/08-client.md#not-in-the-packages-scripts-yet).

## Numbers

| What | Value | Where in the code |
|---|---|---|
| Ping to the arena | every 10 s, in every state | `MatchConnection.PingEveryMs` |
| Input to the arena | ten a second, while alive and in the foreground; fire is latched until it is sent | `MatchConnection.InputEveryMs` |
| A silent match connection | no frame for 3 s, while alive in the match and in the foreground: lost. While dead, 13 s, as a ping's answer is all the arena sends | `MatchConnection.SilenceMs`, `PingEveryMs` |
| A dial and its TLS handshake | 5 s; then lost (when a resume secret is known) or unreachable | `MatchConnection.ConnectMs` |
| A `Join` or `Resume` waiting for its Welcome | 10 s, the arena's own deadline; then as for a dial | `MatchConnection.JoinMs` |
| Resume | tried after 0.5, 1, 2, 4, 8, 8 … s, for 60 s from the loss | `MatchConnection.ResumeForMs` |
| `Leave`'s wait for the arena to close | 2 s | `MatchConnection.LeaveLingerMs` |
| Ping to the lobby | every 30 s; a ping followed by 10 s with nothing heard is a loss | `LobbyClient.PingEveryMs`, `PingAnswerMs` |
| The lobby's reconnect | 1 s, doubling up to 30 s, each wait jittered by ±50 %; reset on `auth.ok` | `LobbyClient` |
| An API call | 10 s, then the next endpoint | `ApiClient` |
| Steps kept, and one step | 64 steps (2.56 s); 40 ms a step, at 25 Hz | `OwnTank.Kept`, `StepMs` |
| A correction | halved every 50 ms; over 64 units, taken at once | `OwnTank.CorrectionHalfLifeMs`, `JumpUnits` |
| Sticks | dead zone 0.2 and fire at 0.5, as fractions of the reach | `TouchSticks.DeadZone`, `FireAt` |
| A frame | at most 64 KiB | `FrameReader.MaxFrame` |
| JSON nesting | at most 32 deep | `JsonValue` |
