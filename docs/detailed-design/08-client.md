# 08 — Client

The Unity app a player installs on iOS and Android
([D-1](../architecture/03-decision-log.md#d-1--unity-native-mobile-client-no-browser-build)).
**Status: designed 2026-09-27**, when the owner decided that Claude writes it
([plan](../plan.md), item 3). **Built: steps 1 to 4 of §7**, the wire, the
world, the match connection, the API, the lobby and the own tank's prediction,
and **§8 (a)**, what the Unity layer draws, how it steers and the account kept.
**§8 (b), the Unity package, is written**: compiled against stubs, run nowhere.
The Unity layer was deferred by the owner (2026-09-27), the backend finished
first; **the owner brought it back on 2026-10-04** (Q-51), as scripts: §8, plan
item 77. The own tank's prediction, deferred with it, the owner brought back on
2026-10-03, and it was built that day: step 4, designed in §4 (plan item 70).

The server documents what a client must do; this document does not repeat it.
It decides what the server documents leave open: how the client is layered, which
thread does what, how it recovers, and how it is tested on a machine that has no
Unity.

## 1. Two layers

```
┌─ Unity layer  (Backend.Client.Unity)  ─ Unity 2022.3 LTS or 6 LTS ───────┐
│  scenes, rendering, touch input, app lifecycle, secure key storage,       │
│  the main-thread pump                                                     │
├─ Core  (Backend.Client.Core)  ─ .NET Standard 2.1, C# 9, no dependencies ─┤
│  wire · world · match connection · lobby · API · prediction               │
└───────────────────────────────────────────────────────────────────────────┘
```

**Everything that speaks to the server is in the core, and the core knows
nothing of Unity.** The most expensive bug this project can have is the client
and the server disagreeing about a byte, and the only defence is to run the
client's own code against the real server. With no engine in it, the core
builds and runs with the .NET SDK on the development machine, where the server
runs too: its tests apply the golden vectors field by field, and a headless
client drives the real stack through login, lobby, a match, a lost connection and
a resume ([§6](#6-testing)).

**The Unity layer is thin because it cannot be tested here.** There is no Unity
on the development machine. What it does — draw what the world holds, turn
touches into inputs, tell the core the app went to the background, keep the
account's key in the device's secure store — is kept small enough to review by
reading, and is to be verified in the Unity editor: none of it has run yet
(§8).

**.NET Standard 2.1 and C# 9** because that is what Unity's scripting runtime
compiles, in 2022.3 LTS and in 6 LTS alike. No `record` types in the core: C# 9
records need a runtime type Unity does not ship. The main thread's work on a
frame — decoding, applying, predicting, building the scene — allocates nothing
but the names a frame carries (a new tank's, a kill's, a phrase's speaker's),
for the same reason the server's tick allocates nothing: garbage on a phone
shows up as dropped frames. The receive thread allocates one array for each
frame it hands over (`FrameReader`).

Namespaces follow the system's name ([README](../README.md#naming)):
`Backend.Client.Core`, `Backend.Client.Unity`. The code lives in
[`client/`](../../client/README.md).

## 2. Threads

Unity's API is main-thread only, and the world is single-writer, as a room is on
the server ([07 §1](07-threading-and-performance.md#1-thread-inventory-arena-process)):

| Thread | Does | Touches the world? |
|---|---|---|
| Unity main | applies frames, renders, samples input, sends inputs | **the only writer** |
| A receive thread per match connection; a reading task and a writing task per lobby connection | reads and frames bytes, each whole frame a new array, and queues them | no |
| Timers on the main thread's clock | ping, input cadence, reconnect backoff, the silence that counts as a loss | no |

A frame is decoded where it is applied, on the main thread: a snapshot is about
80 bytes, fifteen times a second, and decoding it off-thread would buy nothing
but a second copy of the world. The receive queue is not bounded
(`MatchConnection`'s is a `ConcurrentQueue`): what it holds is fifteen frames a
second, and a client that falls behind reading is a client whose link the
server is already stepping down ([02 §8](02-networking.md#8-traffic-profiles)).

## 3. The match connection

What the client must do is [02 §3, §6, §9, §10](02-networking.md#3-messages):
the version first in `Join` and `Resume`; `Ping` every 10 s in every state;
`Input` ten times a second whenever the tank is alive and the app is in the
foreground, each message one input that coalesces the two samples since the
last (the latest move and aim, fire OR-ed), carrying the acknowledgement; `Lifecycle` on every
background and return; `Leave` to quit; TLS when the grant says, checked against
the device's trust store and the host the grant names, with nothing pinned
([03 §2](03-gateway.md#2-position-and-shape)). What this document adds:

**A connection that falls silent is lost.** The arena sends fifteen frames a
second: none for 3 s while the app is in the foreground, the match is joined
(`InMatch`) and the own tank alive is taken as a lost connection, and resumed as
any is. A dead tank's player is sent no frames, by design, only its events and
the answers to its pings, every 10 s: dead, the limit is 13 s, or waiting to
respawn, or for co-op's next wave, would be taken for a loss, and the resume would
spawn the tank (defect [P-53](../defects.md#2-protocol--the-client-contract)).
**Dead** is from the Death event until a frame moves the world's tick again, which
only a player with a tank is sent: the arena never removes the own tank, so the
entity cannot say (P-54). `Alive` is false meanwhile: no input goes out, nothing is
predicted, and the HUD offers Respawn. Dialling and
the TLS handshake are bounded too, so an arena's machine that has gone is found
out within the resume's minute, not by the operating system's timeouts, which
run to minutes.

**What the client does on each ending.** `MatchConnection` ends with a
`MatchEnd` for each, or resumes by itself; what follows an end is the layer
above's. **The package's scripts do not do it yet** (§8): `BackendClient`
keeps how the match ended (`LastEnd`) and goes back to the lobby screen, and
the wait after `Kick(2)`, the backoff after `Kick(5)` and the log after
`Kick(4)` are still to be written.

| Ending | `MatchEnd` | Then |
|---|---|---|
| A frame the world cannot apply: an update for a handle it does not hold, a truncated frame | none: the core resumes (`LastProblem` says "desync: …") | a desync, not survivable by guessing: close, and resume as below; a resume starts a fresh view |
| The socket closes or fails, no `Kick`; or the silence above | none while resuming; `ResumeExpired` after the minute; `Unreachable` if it never joined | Resume: dial the same arena with the latest secret, after 0.5 s, 1 s, 2 s, 4 s, 8 s …, until 60 s have passed since the loss; then back to the lobby for a ticket |
| `Kick(1)` ticket or stay unknown | `BadTicket` | back to the lobby for a ticket, at once |
| `Kick(2)` no room | `RoomFull` | ask for another ticket after 2 s; the grant was spent |
| `Kick(3)` protocol version | `ProtocolVersion` | tell the player to update; never retry |
| `Kick(4)` rate limit | `RateLimit` | a client bug: log it with the last messages sent, back to the lobby screen, no loop |
| `Kick(5)` server fault, or a reason this client does not know | `ServerFault` | back through the lobby with backoff: 1 s doubling to 30 s, each wait jittered ±50 % so a restarting arena's players do not return together |
| `Kick(6)` match over | `MatchOver` | a made match has ended ([04 §4](04-platform-services.md#4-matchmaking)): back to the lobby, where its result lands; not an error, nothing to reconnect to |
| `Kick(7)` removed | `Removed` | an operator took the player out, or closed the room ([04 §10](04-platform-services.md#10-admin-api)): back to the lobby, nothing to reconnect to |
| The player quits | `Left` | `Leave`; the connection is closed once the arena has closed it, or after 2 s (02 §10, P-34), and `Dispose` does not cut that short |

**The resume secret is kept in memory**, from the latest `Welcome`, and **on
disk by the app** for a cold resume
([D-51](../architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret)):
written with the grant's host, port and TLS where the account's key is kept,
whenever the app goes to the background, and cleared when the stay ends.
Restarted within the minute, the app makes a `MatchConnection` with those
settings and calls `Resume(secret)` in place of `Join`; it then behaves as after
any lost connection, and `Kick(1)` means the stay is over. The arena always
accepted a `Resume` on any connection; only the core had no way to start one.
**The core's half is built** and drilled (`MatchConnection.Resume`, the
`coldresume` scenario); **the app's half**, writing the secret on the way to the
background and resuming with it on launch, is the layer above's and **not in
the package's scripts yet** (§8).

**Frames are timed by `tickDelta`**, never by the rate in `Welcome`, which is only
where the profile starts ([02 §8](02-networking.md#8-traffic-profiles)). The
traffic profile the client asks for is `mobile` by default and `saver` when the
player turns it on in settings: `MatchSettings.Profile`, whose default, −1,
leaves it out of `Join` and `Resume`, which the server reads as mobile. The
package's scripts have no such setting yet.

## 4. The world, and when things are drawn

The world is the server's `protocol/ClientWorld`, in C#, checked against the same
vectors: handles, creates, world-space update deltas, removes before creates,
events stepped over by their byte length when unknown, predicted entities
carrying their death tick, and nothing cleared on death, respawn or a new match
([02 §5](02-networking.md#5-entity-handles)). `ExpirePredicted` drops one whose
death tick has passed with no remove; nothing calls it today. The world's tick
moves only with a frame, and the server's remove of a bullet comes in the frame
that passes its death, over a connection that loses nothing, so the server's
remove is what ends them.

**Drawing happens a little in the past.** Other tanks are drawn between their
last two samples at `renderTick = newestTick − 2 frames` (133 ms at 15 Hz, 200 at
10), which hides one late frame. Bullets are extrapolated from their create to
the render tick and drawn nowhere after their destroy, which is authoritative
([02 §6](02-networking.md#6-client-side-simulation)); their hit effect is drawn
where the destroy puts them. Shapes are drawn where they are and turned locally.

**A maze's walls are made, never sent** ([01 §8.9](01-arena.md#89-maze-designed-2026-10-01-plan-item-32),
[D-48](../architecture/03-decision-log.md#d-48--a-maze-is-sent-as-a-seed-and-a-predicted-bullet-stops-at-its-walls-by-one-rule-on-both-sides)).
The `Welcome`'s `MazeSeed`, 0 for none, is read when the frame has it; the
connection's new `ClientWorld` makes `Walls` from it with `Maze.Walls`, a port of
the server's generator and its random numbers, held to the server's walls for
one seed by a golden vector the Java side wrote. A predicted bullet's path is
known whole from its create, so the first tick its centre comes within its
radius of a wall, checked after each move as the server checks, is found once:
the frame that reaches it removes the bullet there and reports the removal for
its hit, before the server's destroy, which then finds nothing; between frames
it is drawn no further. Tanks, shapes and units slide along walls on the server
and come in updates, so they need nothing here.

**Events reach the layer above through `MatchConnection.OnEvent`**, every event
of every frame as it is applied: `ClientWorld` keeps the last frame's only, and
one `Poll` applies every frame waiting, several after any stall
([P-33](../defects.md#2-protocol--the-client-contract)). A death, the player's
own progression, kills (`Kill`: `Killer` and `Victim`, [01 §9](01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37)), and phrases ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)):
a phrase with a handle is drawn over that tank, one with handle 0 in the team's
feed by the speaker's name; the words are the list's, from
`ApiClient.Phrases()`, kept until a `Welcome` names another `PhraseListVersion`.
`Say(id)` sends one; the server drops one within two seconds of the last, so
the button can simply be greyed for two seconds.

**A sandbox** ([01 §8.10](01-arena.md#810-sandbox-designed-2026-10-01-plan-item-33)) is
opened by `ApiClient.OpenSandbox`, for the player or, from a party's leader, the
party (a 503 `no_room` is "not now": asked again a few seconds on, a room just freed
is there); each member's lobby is pushed `evt.match.found` as for a match found, and
the connection goes there as to any match. In it, `SetLevel(n)` rebuilds the own
tank at a level from 1 to 45 and `SummonGuardian()` brings co-op's boss; the
level comes back as the player's own progression, the Guardian as a tank of its
class. Elsewhere the arena drops both.

**The player's own tank is predicted** (designed 2026-10-03, plan item 70;
the rule in [02 §9](02-networking.md#predicting-the-own-tank),
[D-62](../architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry)).
It is drawn in the present while everything else is drawn a little in the past,
as realtime clients of this kind do: input to movement is then
no time at all, and what the player sees of others is a frame or two old.

- **`TankMotion.Step`** is the room's step of a driven tank, in C#: the move
  bits to a direction, the acceleration and friction, the map's edge and a
  maze's walls (`WallGrid`, the server's `Walls` with its cells of 300, a wall
  in every cell it covers). It is held to the server's by a trajectory the
  server's own `Room` writes, `backend/arena/src/test/resources/motion-2026.txt`
  (by the arena's `MotionVectorTest`):
  inputs that start, stop, turn and go diagonally, the map's corner, and a
  maze's walls, each step's position and velocity as the floats' bits, which
  the core's test must make bit for bit. Single-precision arithmetic in the
  same order gives the same bits in Java and .NET; the vector is what proves it
  stays so.
- **`ClientWorld` keeps `Motion` and `MotionRule`** as state (`SelfMotion`:
  the input's ticks and the velocity, from this frame or not; the acceleration
  and radius, from the last `MotionRule`), not as events: `OnEvent` never sees
  them. It notes a frame that created the own tank afresh (a respawn).
- **`OwnTank`**, one a `MatchConnection`, does the rest. `Poll` steps it by the
  clock at 25 Hz with the input held, each step stamped with the seq that will
  carry it; a send stamps the steps of its seq with the input actually sent,
  which is what the server will apply. It keeps 64 steps (2.56 s), each with the
  state after it. On a frame it compares, reconciles and replays (02 §9), and
  keeps the correction.
- **What the layer above draws**: `OwnTank.DrawPosition(now, out x, out y)`, the
  predicted position between the last two steps by the time since the last, plus what is
  left of the correction (halved every 50 ms; over 64 units taken at once), and
  `Angle`, the aim held. `Predicting` says whether it is: not while dead,
  parked, joining or before `MotionRule` has come, when the server's tank, at
  its newest sample, is drawn instead.
- **What it measures, for the drill and for a development build**: each
  frame's error, the matched step's position as predicted before the frame
  against the frame's, and each correction (`OnReconciled`), and the frames
  compared and the jumps. Measured live (02 §9): the error's median 0.16 units,
  the wire's rounding, p95 under a unit, no jump.

**Built 2026-10-03** (plan item 70): `TankMotion`, `WallGrid`, `OwnTank`,
`ClientWorld.Motion`, and `MatchConnection.OwnTank`, stepped in `Poll` before
the input goes out.

Not predicted (02 §9): recoil and knocks, corrected a frame late. The own
bullets come from the server's tank, a frame or two behind the drawn one
while it moves; drawing them from the barrel is the Unity layer's to judge.

## 5. The lobby and the API

The API is [04's](04-platform-services.md), over HTTPS; the lobby is
[03 §3](03-gateway.md#3-lobby-protocol), over a WebSocket. Each call's answers are
in the backend's [API table](../../backend/README.md#the-platform-api). What the
client adds:

- **Three endpoints, tried in turn** ([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip)),
  `https://` and `wss://` only. `ApiClient` takes the list: an endpoint that
  cannot be reached, or whose certificate is refused, moves the call to the
  next, never to plain text, and the one that answered is tried first next
  time. `LobbyClient` takes one address. Trying the lobby's endpoints in turn,
  and refusing an `http://` or `ws://` address (the drills use both), are the
  layer above's, and **not in the package's scripts yet** (§8).
- **The account's key lives in the device's secure store** (Keychain, Android
  Keystore): `AccountKeeper` keeps a guest's key and logs in by it on every
  launch (§8). Keeping the session token there too, so that a restarted app
  skips the login, is the layer above's and **not built**: the package holds
  the session in memory. A 401 means log in again.
- **429 and 503 are waits, not errors**: honour `Retry-After`. `ApiResult`
  carries it as `RetryAfterSeconds`; waiting it out before asking again is the
  layer above's, **not in the package's scripts yet**. `ApiClient` gives every
  call 10 s, which a login queued behind Argon2 needs
  ([04 §1](04-platform-services.md#1-auth-and-sessions)).
- **A purchase key is made when Buy is tapped and kept until the answer
  arrives**, so a retry after a lost answer is the same purchase
  ([04 §8](04-platform-services.md#8-economy-shop-inventory-and-equipment)). Kept
  on disk: an app killed mid-purchase that retries with a new key would buy twice.
  `ApiClient.NewPurchaseKey()` makes one; keeping it is the layer above's, and
  the package has no shop screen yet.
- **The lobby socket pings every 30 s**, answered by `ping.ok`: a ping followed
  by 10 s in which nothing at all is heard is a lost connection
  (`LobbyClient.PingAnswerMs`), found before the socket would say so. A message the socket delivers in fragments is
  joined and decoded once whole. On `evt.session.replaced` the lobby does not
  reconnect: the player signed in elsewhere; nor on `evt.session.revoked`, an
  operator's ban or suspension, after which logging in says whether the
  account may play. A match an operator ends is `Kick(7)`, `MatchEnd.Removed`:
  back to the lobby, nothing to reconnect to
  ([03 §4](03-gateway.md#4-connection-lifecycle)). Nor after `invalid_session`,
  which means log in again. Any other loss reconnects after 1 s, doubling to
  30 s, each wait jittered ±50 % so a restarting gateway's players do not
  return together.
- **The queue for timed matches** ([04 §4](04-platform-services.md#4-matchmaking)):
  `queue.join` over the lobby, and the match arrives as the push
  `evt.match.found`, carrying the grant. A client that reconnects its lobby
  while queued asks `GET /v1/queue` (`ApiClient.Queue`), which answers
  `matched` with the grant if the push was missed; the grant lives as long as
  its ticket, 60 s. `evt.resync`, which a gateway sends after dropping pushes it
  could not deliver ([03 §8](03-gateway.md#8-backpressure-and-slow-clients)),
  reaches `OnPush` and asks for the same: the queue, the party and a
  tournament match fetched again. Both are the layer above's, **not in the
  package's scripts yet**.
- **A match found is asked about first**
  ([D-27](../architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made)):
  `evt.match.ready` puts `LobbyClient` in `confirming`, with `Ready` the match;
  `AcceptMatch()` or `DeclineMatch()` answers it within its ten seconds, and a
  refused answer leaves `AnswerRefusal`. The match itself still arrives as
  `evt.match.found`; a match that is off arrives as `evt.queue.update`, queued
  again, or none for whoever declined, who is then refused `queue_locked` for a
  minute. `Ready` is cleared when the match is found (`evt.match.found`), when
  the queue changes (`evt.queue.update`) and when a decline is answered
  (`match.decline.ok`). `JoinQueue` starts a queue afresh, `QueueState` "none"
  and `Ready` cleared, and `TakeGrant()`, which the layer above calls as it
  joins a grant, hands the grant over and gives the queue back to the player,
  "none", so the next queue is not taken for the last match. What to show, and
  whether to accept on the player's behalf, is the Unity layer's.
- **What a match paid** arrives as `evt.rewards` through `OnPush`, after
  `worker` applies the result ([05 §6](05-worker-and-events.md#what-a-match-paid-pushed-designed-2026-10-01-plan-item-43)):
  the place, coins, experience, rating change, the gems of a level milestone or an
  achievement, each achievement reached by id, and each daily goal met with its
  coins (04 §8); `ApiClient.Goals` lists today's three; a client that missed it
  reads its inventory, and `ApiClient.Achievements` lists every achievement with
  the player's progress.
- **A team** ([04 §2](04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39)):
  `LobbyClient.Team` is the team as the last `evt.team.update` gave it, null for
  none or before any came; the calls are `ApiClient`'s (`MyTeam` and the rest).
- **A party** ([04 §4](04-platform-services.md#parties), designed 2026-09-29):
  `LobbyClient` sends `party.invite`, `party.accept`, `party.leave` and
  `party.kick`, and keeps `Party`, the party as the newest answer or
  `evt.party.update` gave it (null for none; `PartyState`, an older state of
  the same party arriving after a newer one not applied, nor a late state of a
  party it saw end, D-74), `Invitation`, the last
  `evt.party.invite`, and `PartyRefusal`, a refused request's code.
  `SayToParty(id)` sends `party.say`; each `evt.party.said` is told to
  `OnPartySaid`, one call each, since several can arrive between polls
  ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)). A member
  does not queue; the leader does, and each member's `QueueState` follows
  `evt.queue.update`, as the leader's follows its own answer. A client whose
  lobby reconnects fetches `GET /v1/party` and `GET /v1/queue` (`ApiClient.Party`
  and `Queue`; the layer above's, not in the package's scripts yet): the pushes are
  notifications, and one missed while away is not sent again. The core reads
  the party's JSON in one place, `ApiClient.ReadParty`, tested with the other
  messages; the rest is driven live: six players, three of them a party,
  queued for team-vs-team, matched into one room with the party on one side.
  A `matched` state is not undone by `evt.queue.update`: once matched, the
  match is the member's own, and pushes from different threads can arrive out
  of order.
- **Tournaments** ([04 §6](04-platform-services.md#6-tournaments), designed
  2026-09-30): `ApiClient.Tournaments()` and `Tournament(id)`, the bracket
  among the rest, need no session; `EnterTournament` and
  `WithdrawFromTournament` do. A match is pushed as `evt.tournament.match`,
  which `LobbyClient` keeps as `TournamentMatch`, the last one pushed, and it
  is joined as any grant. A client whose lobby reconnects asks
  `ApiClient.TournamentMatch(token, id)`, which answers while the ticket lasts,
  60 s. A player who never comes loses by walkover if the other came, and if
  neither did, the higher seed goes through 270 s after the tickets. What to
  show, the bracket and the countdown, is the Unity layer's.
- **Team matches** ([04 §4's sixth slice](04-platform-services.md#the-sixth-slice-team-matches-designed-2026-09-30-plan-item-19),
  designed 2026-09-30): the team's leader or a vice leader, leading a party of
  three of its members, queues it as any queue, `JoinQueue("teams")`; a refusal
  leaves `party_too_small`, `not_one_team` or `not_allowed` in `QueueRefusal`.
  The match is found, asked about and joined as any. `TeamInfo` carries the
  team's `Rating`, `Wins`, `Losses` and `Draws`.
- **Teams' tournaments** ([04 §6's second slice](04-platform-services.md#the-second-slice-teams-tournaments-designed-2026-09-30-plan-item-20),
  designed 2026-09-30): `TournamentInfo.Mode` is `teams`; an entry carries
  `TeamId` and its `Roster`, a match `TeamA`, `TeamB` and `WinnerTeam`. A team's
  leader or a vice leader, leading a party of three of it, calls
  `EnterTournament`, as for a duel; the three are its roster. Each is pushed the
  match as a duel's player is.
- **Friends, blocks and the inbox** ([04 §9](04-platform-services.md#the-social-layers-first-slice-designed-2026-09-30-plan-item-21),
  designed 2026-09-30): `ApiClient.Friends` (each friend's `Online`, and the
  requests either way), `AskFriend` ("asked" or "friends"), `RemoveFriend`,
  `DropFriendRequest`, `Blocks`, `Block`, `Unblock`, `Inbox` and `ReadInbox`.
  The pushes `evt.friend.request`, `evt.friend.accepted` and `evt.inbox` reach
  `LobbyClient.OnPush`; `evt.inbox` means read `Inbox` again. An inbox item is a
  kind and an id, never words: the Unity layer writes them (FR-11).
- **Guests** ([04 §1](04-platform-services.md#1-auth-and-sessions), designed
  2026-09-30): `ApiClient.CreateGuest` answers a `Guest`, its `GuestKey` and
  `DisplayName`; the key goes to the device's secure store beside the session
  token, and `LoginGuest` takes it when the session is gone. `UpgradeAccount`,
  with the guest's session, a username, a password and a display name or none,
  makes it a full account, the same player; from then `Login` by name, and the
  key is refused. Where the key is kept is the Unity layer's.
- **The class table is fetched, not built in**
  ([D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)):
  `ApiClient.Classes()` reads `GET /v1/content/classes` into the core's own
  `ClassTableInfo`, whose version is the one a `Welcome` names in
  `contentVersion`. A client keeps the table it has until a `Welcome` names
  another, then fetches again. What it draws with it is the Unity layer's.
- **JSON is read and written by the core itself**, strictly (RFC 8259, depth
  bounded): the messages are a few flat objects, and a package would be the
  core's first dependency ([D-19](../architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)).

## 6. Testing

| What | How | Where |
|---|---|---|
| The wire and the world | every decoded field of every golden vector; unknown events stepped over; malformed frames refused | core tests, .NET SDK, this machine |
| The maze | the walls for one seed against the server's golden vector (`backend/sim/src/test/resources/maze-2026.txt`); a bullet ending at a wall by the server's rule; live, the `maze` drill | core tests; the headless client |
| Messages the client sends | encoded here, decoded by the real server | the headless client |
| The whole path | a headless client driving the real stack from a release: register, log in, lobby, ticket, join, input, death, respawn, a dropped socket and a resume, `Leave`, the result in MySQL | this machine |
| Prediction | the movement rule bit for bit against the trajectory the server's `Room` wrote (`motion-2026.txt`); stamping, matching, replay, corrections and jumps on a frozen clock; live, the `prediction` drill: each frame's predicted position against the server's | core tests; the headless client |
| What a frame draws, the sticks, the account kept, the party's state (§8 (a), D-74) | the scene at a render tick and the render clock on a frozen clock; the sticks' sectors, dead zone and fire; a first launch and a key refused against a scripted API and a store in memory; older and ended parties' states refused | core tests |
| The lobby client | against a scripted lobby on a loopback WebSocket: the queue "none" again after a match, a declined match forgotten, a message split inside a character read whole, a silent lobby found lost, reconnects spread; and live, the drills | core tests; the headless client |
| The Unity package's scripts | compiled against stubs of the Unity API, for no platform, the editor, iOS and Android; the Android plugin against stubs of Android's | `client/unity-check.sh`, nothing run |
| Rendering, touch, lifecycle, secure storage, device TLS, frame time on a phone | the Unity editor and devices | **not on this machine** |

The tests use NUnit, which Unity's test runner also runs, so the core's tests can
run there unchanged.

## 7. Order of work

1. The core's wire and world, against the vectors. **Built 2026-09-27**: every
   decoded field of every vector, the world against the server's reference down
   to its rounding, eleven deliberate faults each caught.
2. The match connection, and a headless client against the real arena: join,
   input cadence, acknowledgement, ping, lifecycle, every kick, a resume.
   **Built 2026-09-27.** Against the real stack from a release, plaintext and TLS
   ([`client/headless-drill.sh`](../../client/headless-drill.sh)): joined; moved at
   the top speed the server's rule allows, so every input was read; 15 frames a
   second; its own bullets arrived at exactly 10 units a tick, as protocol 2
   carries them, and a class it could not have yet was refused without closing
   anything; a dropped socket resumed within a second to the same tank with a new
   secret; a bad ticket was `Kick(1)`; the background stopped the frames and the
   foreground restarted them; an arena whose certificate was not trusted was
   refused. What the real arena cannot be made to do on demand (each kick
   reason, a broken frame, resume backoff and its minute) is tested against a
   scripted arena on a local socket.
3. The API and the lobby; the headless client goes the whole path. **Built
   2026-09-27.** Log in over the API, authenticate the lobby, ask it for a match,
   play past the reward's minimum, leave, and the worker's reward is in the
   inventory within a third of a second; a second sign-in replaces the first
   lobby connection, which does not come back; a made-up token is refused and not
   retried. Endpoint rotation, refusals and `Retry-After` against a scripted
   HTTP handler. **And the queue** (2026-09-27): two players queue for a duel,
   are matched by the push, play it in the room made for it, and are sent back
   with `Kick(6)`; the result reaches MySQL
   ([04 §4](04-platform-services.md#4-matchmaking); [client/](../../client/README.md)).
   **And parties** (2026-09-29): six players in the lobby; one invites two, who
   accept, and all three are told the party of three; a member's `queue.join`
   is refused `in_party`, and the leader's for a duel `party_too_big`; the
   leader queues it for team-vs-team and both members are told, a member's
   leaving the queue takes it out and the others are told, and it queues again;
   three more queue alone; all six are matched to one room, the party team 1
   and the three alone team 2; the five minutes end it, all six paid, a rated
   team match counted for each, and the party still stands. **And the confirm
   step** (2026-09-29): every queued scenario is asked `evt.match.ready` and
   accepts; in `decline`, one of two declines, the other is told it is queued
   again, nobody is sent to a room, and the one who declined is refused
   `queue_locked`. **And ranked free-for-all** (2026-09-29): eight queue alone,
   are asked and accept, join one room each for themselves, team 0, and play
   the four minutes out; each is paid, and counted a rated free-for-all.
   **And co-op** (2026-09-29): a party of two and one alone are asked as one
   team and accept; team 1 against the arena's waves, which come into view and
   hunt all three down; the wipe ends it, and each is paid. **And tournaments**
   (2026-09-30): an operator creates a duel tournament of four; the four enter,
   are pushed their matches, and one side of each comes, winning by walkover,
   round 1's second match waiting for the arena's one room
   ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen));
   the final a minute on, and the prizes in each wallet. **And team matches**
   (2026-09-30): two teams of three, a party of each; a party led by a member is
   refused `not_allowed` until the team makes them a vice leader; the match is
   played out a team a side, and each team counts it. **And teams'
   tournaments** (2026-09-30): two teams' parties entered as their rosters, the
   final pushed to all six, one roster winning by walkover, each member paid. **And the social layer**
   (2026-09-30): a friend request pushed and in the inbox, accepted by asking
   back, presence online and offline, a block ending a friendship and silently
   dropping the next request, the inbox read. **And guests** (2026-09-30): a
   guest made, in again by its key, paid for a stay, upgraded, in by name as the
   same player with the same coins, and its key refused. **And the operator's
   figures** (2026-09-30, `stats`): a new guest's stay counted as one more player
   active today and one more new, day 1 empty until tomorrow has ended; and two
   new guests who made friends that day counted under `friend` by feature. **And
   co-op's boss** (2026-09-30, in `play`): the class table a client fetches has
   the Guardian, which no level opens. **And domination** (2026-10-01,
   `domination`): six queue alone and are matched three a side; one drives to
   the middle line and sees a neutral dominator; nobody takes one, and the five
   minutes end in a draw, each paid. **And tag** (2026-10-01, `tag`): six matched
   three a side; nobody fires, so nobody goes over, and the five minutes end in a
   draw, each paid. The two share one routine, `Alone`, which the `maze`
   scenario uses too.
4. The own tank, predicted (§4; plan item 70, brought back by the owner on
   2026-10-03). **Built 2026-10-03**: the movement rule bit for bit against the
   room's own trajectory, and live, the `prediction` drill.
5. The Unity layer: scenes, drawing, touch, lifecycle, secure storage. **Written
   2026-10-04** as scripts over the core (§8), compiled against stubs; not run.
6. Phase 0's last question, in Unity: does a bullet overshooting until its late
   destroy look wrong in motion
   ([D-9](../architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client),
   [protocol-spike](../../protocol-spike/README.md))?

Steps 1–4 are verified on this machine; 5 and 6 need Unity.

## 8. The Unity layer, as scripts (designed 2026-10-04, plan item 77)

On the owner's answer to Q-51: "could you make scripts as you can, as possible?
to be used in Unity develop tools and environment". The layer of §1, written,
in two parts
([D-73](../architecture/03-decision-log.md#d-73--the-unity-layer-is-thin-scripts-over-the-built-core-what-can-be-engine-free-moves-into-the-core-first)):

**(a) What can be engine-free moves into the core first, and is tested here.**
Three things a Unity layer would otherwise work out for itself (**built
2026-10-04**, `Scene`, `RenderClock`, `TouchSticks`, `AccountKeeper`):

- **What is drawn where, at a render tick** (§4's rule, not built until now).
  `ClientWorld` keeps, for each tank and unit, its sample before the newest and
  the ticks of both. `RenderClock` turns the newest frame's tick, when it came,
  and the time now into the render tick: the newest tick plus the ticks since it
  came, less two frames' worth (twice the frames' tick delta, averaged, as at
  15 frames a second of 25 ticks the deltas alternate one and two), never past
  the newest and never back. `Scene` is the list a frame draws, reused and allocating nothing:
  for each live entity its kind, handle, position in world units and angle in
  radians at the render tick (a tank or unit between its two samples, the
  shorter way round for the angle; a bullet extrapolated by `PredictedAt`; a
  shape where it is), radius, class, team, skin, health as a fraction, flags and
  name, and whether it is the player's own; the own tank from `OwnTank` while it
  predicts, in the present.
- **Two sticks to an input.** `TouchSticks`: a stick's offset, as a fraction of
  its reach, to the move mask, eight directions by 45° sectors past a dead zone
  of a fifth; the aim stick to the wire's aim, and fire while it is pushed past
  half its reach. The same for a mouse and keys in the editor.
- **Keeping the account across launches.** `ISecureStore`, three calls (get, set,
  delete a string), which the device's keychain or keystore answers in the
  Unity layer and memory in the tests. `AccountKeeper` makes the first launch a
  guest (`POST /v1/guests`), keeps its key there, and signs in by it on every
  launch after; a key refused is forgotten, and a new guest made only if asked.
  Its continuations stay on the caller's context, so in Unity the store is
  touched on the main thread, which `PlayerPrefs` requires.

**(b) The Unity package** (**written 2026-10-04**, compiled against stubs as
below, run nowhere), `client/Unity/com.backend.client`, a UPM package of
thin scripts over the core, which reaches it as the library `dotnet build`
makes (.NET Standard 2.1), copied in by `client/unity-package.sh`:

| Script | Does |
|---|---|
| `BackendClient` | the one object that owns the core's objects; `Update` completes the sign-in, pumps the lobby and the match, takes a grant (`TakeGrant`) and joins it, and builds the scene; a sign-in that fails, or a session that ends (`InvalidSession`), is reported (`Problem`) and signs the player out, so the screen offers to sign in again; `OnApplicationPause` is the lifecycle (02 §10) |
| `WorldView` | draws `Scene`: a pooled `SpriteRenderer` a handle, its sprite by kind, class and skin from a `LookTable` asset |
| `TouchControls` | two sticks where the thumbs land (keys and the mouse in the editor) into `TouchSticks`, then `SetInput` |
| `FollowCamera` | on the own tank, the view's size as the arena's |
| `LobbyScreen`, `Hud` | the least screens to play: play now, a duel's queue, its state, a match found to accept; level and experience, the kill feed, respawn and leave |
| `SecureStores` | the keychain on iOS (a small Objective-C plugin), the keystore on Android (a small Java plugin over `AndroidKeyStore`, no library), `PlayerPrefs` in the editor and any other build (a desktop's), named as not secure |

**Checked here, and not.** The core's additions by its tests. The package's
scripts are compiled on this machine against stubs of the Unity API they use,
once with no platform defined, once for the editor and once each with
`UNITY_IOS` and `UNITY_ANDROID`, so every branch compiles, and the Android
plugin's Java against stubs of the Android API; that catches the C#'s own
mistakes and the core's API misused, and **not** a wrong idea of Unity's API,
which only Unity can. The check compiles against the core's project, not the
library in the package, which `unity-package.sh` must copy in again whenever
the core changes. Nothing here is run in Unity, drawn, touched or put on a
phone: the package's README says so, and what the owner checks first in the
editor.

### Not in the package's scripts yet

What §3 and §5 say a client does, and the core makes possible, but the
package's scripts do not do. Each is the layer above's, on calls the core has:

| What | Where it is specified | What the core gives |
|---|---|---|
| A cold resume: the secret written on the way to the background, a resume on launch | §3, D-51 | `MatchConnection.Resume(secret)`, `Welcome.ResumeSecret` |
| What follows `Kick(2)`, `Kick(4)` and `Kick(5)`: another ticket after 2 s, a log, the jittered backoff | §3 | `MatchConnection.End` |
| The session token kept, so a restart skips the login | §5 | `Session.Token`, `ISecureStore` |
| The lobby's endpoints tried in turn; `http://` and `ws://` refused | §5, D-13 | `LobbyClient` takes one address |
| `Retry-After` honoured | §5 | `ApiResult.RetryAfterSeconds` |
| The queue, the party and a tournament match fetched again after a lobby reconnect or `evt.resync` | §5 | `ApiClient.Queue`, `Party`, `TournamentMatch`; `LobbyClient.OnPush` |
| A purchase key kept on disk until its answer | §5 | `ApiClient.NewPurchaseKey()` |
| The `saver` traffic profile as a setting | §3 | `MatchSettings.Profile` |
