# Core: Backend.Client.Core

Everything the client says to the server, and what a frame draws, with no
engine in it ([08 §1](../../docs/detailed-design/08-client.md#1-two-layers)).
.NET Standard 2.1 and C# 9, which is what Unity's scripting runtime compiles;
no package references, so it drops into a Unity project as one library
(`../unity-package.sh`) and runs with the .NET SDK here, where its tests and the
headless drills drive it against the real server. No `record` types: C# 9
records need a runtime type Unity does not ship.

Its structure, and the lifecycles below, are drawn in
[docs/diagrams/06-client.md](../../docs/diagrams/06-client.md).

## Threads

One thread owns the core: the layer above's main thread, which calls each
`Poll` once a frame ([08 §2](../../docs/detailed-design/08-client.md#2-threads)).

| Thread | Does |
|---|---|
| The main thread | `MatchConnection.Poll` and `LobbyClient.Poll`: frames applied, events and pushes told, timers run, messages sent; the scene built; every call below unless said otherwise |
| A match connection's receive thread (`match-connection`), one per attempt | connects, shakes hands, reads and frames bytes into a queue |
| A lobby connection's two tasks | one reads whole messages into a queue, one writes what `Send` queued |
| The thread pool | `ApiClient`'s calls complete there; `AccountKeeper`'s continuations stay on the caller's context, so the secure store is touched on the thread that called `SignIn` |

Nothing the main thread reads is written by another thread except those
queues and a connection's own flags (connected, closed, the failure). Events (`MatchConnection.OnEvent`, `LobbyClient.OnPush`,
`OnPartySaid`, `OwnTank.OnReconciled`) are raised inside `Poll`.

## Public API

### Wire and snapshot decoding

| Type | What it is for |
|---|---|
| `Wire` | The protocol's numbers, mirroring `com.backend.protocol.Wire` and `ClientMessage`: `Version` 4, message ids, kick reasons 1 to 7, move and flag bits, entity kinds, update masks, event ids 1 to 7, `PosScale` 4, `VelocityScale` 256, `TickHz` 25, `SelfHandle` 1; `SpeedOf`, `AimToRadians`, `QuantiseAim`, `SeqNewer` (24-bit), `JavaRound` (half up, as Java rounds) |
| `WireReader` | A `ref struct` over one frame's bytes: little-endian fields, LEB128 varints, zigzag, strings; allocates nothing but the strings it returns |
| `ProtocolException` | The server sent something this client cannot read: a desync, answered by a resume |
| `SnapshotDecoder` | Decodes one snapshot frame into an `ISnapshotSink`, in the frame's order; refuses unknown kinds and trailing bytes |
| `ISnapshotSink` | What the decoder streams into: header, removes, creates of four kinds, updates, events |
| `SnapshotUpdate` | One update; only the fields whose bits are in `Mask` were sent |
| `Welcome`, `Pong` | The first frame after a `Join` or `Resume` (handle, rate, map size, mode, content and phrase list versions, entity id, resume secret, maze seed), and a ping's answer |
| `ServerMessages` | Reads `Welcome`, `Pong` and `Kick` |
| `ClientMessages` | Writes the client's messages, each framed, into one reused 128-byte buffer: `Join`, `Resume`, `Input`, `UpgradeStat`, `ChooseClass`, `Respawn`, `Phrase`, `Sandbox`, `Ping`, `Lifecycle`, `Leave` |
| `FrameReader` | Splits the byte stream into frames (a varint length, at most 64 KiB), each handed on as a new array |

### The world

| Type | What it is for |
|---|---|
| `ClientWorld` | What the client holds between snapshots: 256 handles, world-space positions, predicted bullets extrapolated from their create and ended at a maze's wall, the input seq echoed, the own tank's motion; `Apply(frame)`, `this[handle]`, `EventCount`/`EventAt`, `Removals`, `Walls`, `ServerTick` |
| `Entity` | One handle's entity, reused: kind, position, angle, health, level, class, team, flags, name, skin, the sample before the newest; a predicted entity's heading, speed, owner and death tick; `PredictedAt(tick)` |
| `MatchEvent` | One event of the last frame, reused: a death, the own progression, a phrase or a kill |
| `SelfMotion` | The own tank's `Motion` and `MotionRule`, kept as state for the prediction, never told as events |
| `Removal` | An entity the last frame removed, or a wall ended, and where: a hit to draw |
| `Wall`, `Maze` | A maze's wall, and the server's maze generator in C#: the walls from the `Welcome`'s seed |

### The match connection

| Type | What it is for |
|---|---|
| `MatchConnection` | One player's connection to one arena: `Join(ticket)` or `Resume(secret)`, `Poll()` once a frame, `SetInput`, `SetBackgrounded`, `Respawn`, `UpgradeStat`, `ChooseClass`, `Say`, `SetLevel`, `SummonGuardian`, `Leave`; `State`, `End`, `World`, `OwnTank`, `Welcome`, `Alive` (false from a Death to the respawn), `RoundTripMs`, `LastProblem`, `OnEvent`, `OnFrame` (after each frame, while the world holds its removals) |
| `MatchSettings` | Where and how to dial, from the grant: `Host`, `Port`, `Tls`, `CertificateCheck` (null: the device's trust store), `Profile` (−1: left out, read as mobile) |
| `MatchState` | `Connecting`, `Joining`, `InMatch`, `Lost`, `Ended` |
| `MatchEnd` | Why it ended, each with its own next step ([08 §3](../../docs/detailed-design/08-client.md#3-the-match-connection)): `Left`, `BadTicket`, `RoomFull`, `ProtocolVersion`, `RateLimit`, `ServerFault`, `Unreachable`, `ResumeExpired`, `MatchOver`, `Removed` |
| `IClock`, `SystemClock` | Milliseconds that never go backwards; a test's can be moved by hand |

### The own tank, predicted

| Type | What it is for |
|---|---|
| `OwnTank` | The own tank stepped at 25 Hz by the clock, each step stamped with the seq that will carry it; on each frame matched, put where the server has it, replayed, the correction drawn away (halving every 50 ms; over 64 units taken at once): `Predicting`, `DrawPosition(now, out x, out y)`, `Angle`, `Compared`, `Jumps`, `OnReconciled` |
| `TankMotion`, `MotionState` | The room's step of a driven tank, in single precision and the server's order, so the bits agree |
| `WallGrid` | A maze's walls by the 300-unit cells they cover, and the push out of them |

### The API

| Type | What it is for |
|---|---|
| `ApiClient` | The platform API over HTTPS, its endpoints tried in turn, every call given 10 s; one method a route, each answering an `ApiResult`. The routes are in the backend's [API table](../../backend/README.md#the-platform-api) |
| `ApiResult<T>` | `Ok` and `Value`; or `Status`, the server's `Code` and `Message`, and `RetryAfterSeconds`; `Unreachable` when no endpoint answered |
| What the calls answer | `Session`, `Guest`, `MatchGrant`, `QueueStatus`, `PartyInfo`, `PartyMember`, `MatchReady`, `PartyInvitation`, `PartyPhrase`, `ClassTableInfo`, `ClassInfo`, `BarrelInfo`, `PhraseListInfo`, `PhraseInfo`, `SkinTable`, `Offer`, `Holdings`, `Holding`, `ItemLevel`, `Receipt`, `Pack`, `PaymentOrder`, `PaymentResult`, `PassInfo`, `PassTier`, `PassReward`, `PremiumAnswer`, `Loadout`, `ActiveBoost`, `BoostsAnswer`, `TeamInfo`, `TeamMemberInfo`, `Renamed`, `TeamFound`, `TeamApplicant`, `TeamInvite`, `TournamentInfo`, `TournamentEntry`, `RosterPlayer`, `BracketMatch`, `TournamentStanding`, `TournamentGrant`, `PlayerRef`, `FriendInfo`, `FriendRequest`, `FriendsInfo`, `InboxItem`, `RankRow`, `AchievementInfo`, `GoalInfo`, `GoalsInfo`, `SeasonInfo`, `SeasonsInfo` |

`ApiClient`'s calls, by area: `Register`, `Login`, `CreateGuest`, `LoginGuest`,
`UpgradeAccount`, `Logout`, `Rename`; `RequestMatch`, `JoinQueue`,
`LeaveQueue`, `Queue`, `OpenSandbox`, `Party`; `Classes`, `Phrases`, `Skins`;
`Shop`, `Inventory`, `RaiseItemLevel`, `Purchase`, `Packs`, `PlaceOrder`,
`GetOrder`, `SimulatePayment`, `SeasonPass`, `BuyPremium`, `Equipment`, `Wear`,
`TakeOff`, `Boosts`, `ActivateBoost`; `CreateTeam`, `MyTeam`, `RenameTeam`,
`InviteToTeam`, `TeamInvites`, `AcceptTeamInvite`, `DeclineTeamInvite`,
`FindTeams`, `ApplyToTeam`, `WithdrawApplication`, `MyApplications`,
`TeamApplications`, `AnswerApplication`, `LeaveTeam`, `KickFromTeam`,
`SetTeamRole`, `HandOverTeam`, `DisbandTeam`; `Tournaments`, `Tournament`,
`EnterTournament`, `WithdrawFromTournament`, `TournamentMatch`; `Friends`,
`AskFriend`, `RemoveFriend`, `DropFriendRequest`, `Blocks`, `Block`, `Unblock`,
`Inbox`, `ReadInbox`; `Leaderboard`, `AroundMe`, `Achievements`, `Goals`,
`Seasons`; and `NewPurchaseKey()`, a key for one tap of Buy.

### The lobby

| Type | What it is for |
|---|---|
| `LobbyClient` | The lobby WebSocket ([03 §3](../../docs/detailed-design/03-gateway.md#3-lobby-protocol)): `Connect(token)`, `Poll()` once a frame; `RequestMatch`, `JoinQueue`, `LeaveQueue`, `AcceptMatch`, `DeclineMatch`, `InviteToParty`, `AcceptInvitation`, `LeaveParty`, `KickFromParty`, `SayToParty`; what it was told: `Grant` (taken by `TakeGrant()` to join it), `QueueState`, `Ready`, `Party`, `Team`, `Invitation`, `TournamentMatch`, the refusals by request, `OnPush`, `OnPartySaid` |
| `LobbyState` | `Connecting`, `Ready`, `Reconnecting`, `Replaced`, `InvalidSession`, `Closed` |
| `PartyState` | The party as the newest state told it, by its version (D-74): an older state of the same party, or a late one of a party it saw end, is not applied |

### What a frame draws, and the sticks

| Type | What it is for |
|---|---|
| `Scene` | The list a frame draws, reused: `Build(world, renderTick)`, or with the own tank where its prediction puts it; `Items` |
| `DrawItem` | One thing to draw: handle, kind, position in world units (y grows down), angle in radians, radius, class or subtype, team, skin, level, flags, health from 0 to 1, name, and whether it is the player's own |
| `RenderClock` | The render tick: the newest frame's tick plus the time since it came, less twice the frames' averaged tick delta, never past the newest and never back; `Frame(tick, now)`, `RenderTick(now)` |
| `TouchSticks` | A stick's offset, as a fraction of its reach with y up, to the move mask (eight sectors past a dead zone of 0.2) and to the wire's aim; fire past 0.5 of the reach; keys to a mask; a mouse's aim at a world point |

### The account, and JSON

| Type | What it is for |
|---|---|
| `AccountKeeper` | `SignIn()`: the first launch makes a guest and keeps its key (`backend.guestKey`) in the store; every launch after logs in by it; a key refused (401) is forgotten, and a new guest is made only when `SignIn` is called again |
| `ISecureStore`, `MemorySecureStore` | Where the key survives restarts: three calls, `Get`, `Set`, `Delete`; the device's keychain or keystore in the Unity layer, memory in the tests |
| `JsonValue` | Strict JSON (RFC 8259, nesting at most 32 deep) for the API's and the lobby's small messages: `Parse`, `ToString`, members by name or index, `AsString`, `AsLong`, `AsInt`, `AsFloat`, `AsBool` |

## Using it from a layer above

A sketch of one player's client in whatever drives the frames: log in, the
lobby, a match requested, the connection made and polled, the scene drawn.
`BackendClient` in the Unity package is this, wired to Unity.

```csharp
using System;
using System.Threading.Tasks;
using Backend.Client.Core;

public sealed class Player
{
    private readonly IClock _clock = new SystemClock();
    private readonly ApiClient _api = new ApiClient(new[]
        { "https://a.example.com", "https://b.example.com", "https://c.example.com" });
    private readonly Scene _scene = new Scene();
    private LobbyClient _lobby;
    private MatchConnection _match;
    private ClientWorld _world;
    private RenderClock _render;
    private long _applied = -1;

    // 1. Log in: the device's guest, made on the first launch, its key kept in the store.
    public async Task<bool> SignIn(ISecureStore store)
    {
        ApiResult<Session> session = await new AccountKeeper(_api, store).SignIn();
        if (!session.Ok) return false;                 // session.Status, Code, RetryAfterSeconds
        // 2. The lobby, authenticated with the session's token.
        _lobby = new LobbyClient(new Uri("wss://a.example.com/lobby"), _clock);
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
                { Host = grant.ArenaHost, Port = grant.ArenaPort, Tls = grant.Tls }, _clock);
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

What the layer above still owns: what follows each `MatchEnd`, the queue's
screens (`Ready`, `AcceptMatch`, `DeclineMatch`), refetching the queue and the
party after the lobby reconnects, and the rest of
[08 §8's list](../../docs/detailed-design/08-client.md#not-in-the-packages-scripts-yet).

## Numbers

| What | Value | Where |
|---|---|---|
| Ping to the arena | every 10 s, in every state | `MatchConnection.PingEveryMs` |
| Input to the arena | ten a second, alive and in the foreground; fire latched until sent | `MatchConnection.InputEveryMs` |
| A silent match connection | no frame for 3 s, alive in the match and in the foreground: lost; dead, 13 s, a ping's answer being all the arena sends | `MatchConnection.SilenceMs`, `PingEveryMs` |
| A dial and its TLS handshake | 5 s, then lost (a secret known) or unreachable | `MatchConnection.ConnectMs` |
| A `Join` or `Resume` waiting for its Welcome | 10 s, the arena's own deadline; then as a dial | `MatchConnection.JoinMs` |
| Resume | after 0.5, 1, 2, 4, 8, 8 … s, for 60 s from the loss | `MatchConnection.ResumeForMs` |
| `Leave`'s wait for the arena to close | 2 s | `MatchConnection.LeaveLingerMs` |
| Ping to the lobby | every 30 s; a ping followed by 10 s with nothing heard is a loss | `LobbyClient.PingEveryMs`, `PingAnswerMs` |
| The lobby's reconnect | 1 s doubling to 30 s, each wait jittered ±50 %; reset on `auth.ok` | `LobbyClient` |
| An API call | 10 s, then the next endpoint | `ApiClient` |
| Steps kept, and a step | 64 (2.56 s); 40 ms, at 25 Hz | `OwnTank.Kept`, `StepMs` |
| A correction | halved every 50 ms; over 64 units taken at once | `OwnTank.CorrectionHalfLifeMs`, `JumpUnits` |
| Sticks | dead zone 0.2, fire at 0.5, of the reach | `TouchSticks.DeadZone`, `FireAt` |
| A frame | at most 64 KiB | `FrameReader.MaxFrame` |
| JSON nesting | at most 32 deep | `JsonValue` |
