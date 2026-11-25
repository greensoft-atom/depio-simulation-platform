# 08 — Client

This document is the design of the client: the Unity app that a player installs on Android. The app comes from the
owner's own copy, not from a store
([D-1](../architecture/03-decision-log.md#d-1--unity-native-mobile-client-no-browser-build),
[C-10](../requirements/01-scope-and-nfrs.md#3-constraints)); the iOS parts below stay in the code, unused. It is for
the client's developers and reviewers. It covers how the client is layered (§1), which thread does what (§2), the
match connection and how it recovers (§3), the world and how it is drawn (§4), the lobby and the API (§5), how the
client is tested on a machine without the Unity editor (§6), the order of the work (§7), the Unity layer as scripts
(§8), and the simple clients for the UI team (§9). New words are explained in the [glossary](../glossary.md).

The server's documents say what a client must do, and this document does not repeat them. It decides what they leave
open: how the client is layered, which thread does what, how it recovers, and how it is tested on a machine that has
no Unity editor. The routes and messages themselves are in [docs/api](../api/README.md).

**Status.**

- **Designed on 2026-09-27**, when the owner decided that Claude writes the client ([plan](../plan.md), item 3).
- **Built**: steps 1 to 4 of §7 (the wire, the world, the match connection, the API, the lobby, and the prediction of
  the player's own tank), and **§8 (a)**: what the Unity layer draws, how it steers, and how the account is kept.
- **Written: §8 (b), the Unity package.** It is compiled against stubs, and since 2026-11-05 also by Unity
  2019.4.28f1's own compiler against Unity's own `UnityEngine` assemblies (D-108). It has not run in the Unity editor
  or on a phone.
- **History.** The owner deferred the Unity layer on 2026-09-27, so that the backend was finished first. **The owner
  brought it back on 2026-10-04** (Q-51), as scripts: §8, plan item 77. The prediction of the own tank was deferred
  with it. The owner brought that back on 2026-10-03, and it was built that day: step 4, designed in §4 (plan item
  70).
- **The simple clients**, `client-simple/` and `client-simple-no-tls/`, were added on 2026-11-24 and 2026-11-25 for
  the UI team (§9).
- **On .NET Framework 4.5 alone since 2026-11-25**
  ([D-116](../architecture/03-decision-log.md#d-116--the-client-on-net-framework-45-alone-built-and-run-here-without-net-8-and-driven-in-clear)):
  every client project, with no .NET 8 anywhere. Here the C# is compiled by Unity 2019.4's compiler and run on its
  Mono, apart from `vs2019-check.sh`, which builds with Visual Studio 2019's own MSBuild. The C# client is driven in
  clear alone (§1, §6).

## 1. Two layers

```
┌─ Unity layer  (Backend.Client.Unity)  ─ Unity 2019.4 and later, .NET 4.x ─┐
│  scenes, rendering, touch input, app lifecycle, secure key storage,       │
│  the main-thread pump                                                     │
├─ Core (Backend.Client.Core) ─ .NET Framework 4.5, C# 7.3, no dependencies ┤
│  wire · world · match connection · lobby · API · prediction               │
└───────────────────────────────────────────────────────────────────────────┘
```

**Everything that speaks to the server is in the core, and the core knows nothing of Unity.** The most expensive bug
this project can have is a client and a server that disagree about a byte. The only defence is to run the client's
own code against the real server. The core has no engine in it, so it builds and runs on the development machine,
where the server runs too: compiled by Unity 2019.4's compiler and run on its Mono, with no .NET SDK (D-116). Its
tests apply the golden vectors field by field: snapshot frames, each with what it decodes to, written by the protocol
spike's independent codec, which the server's decoder must read too ([glossary](../glossary.md)). And a headless
client, one with no screen, drives the real stack through a login, the lobby, a match, a lost connection and a resume
([§6](#6-testing)).

**The Unity layer is thin because it cannot be tested here.** The development machine has no Unity editor (the editor
needs a licence). It does have Unity 2019.4.28f1's compiler, assemblies and Mono runtime, in `work/unity` (D-108), so
the scripts are compiled there, but never run. The layer does four things: it draws what the world holds, turns
touches into inputs, tells the core when the app goes to the background, and keeps the account's key in the device's
secure store. It is kept small enough to review by reading. It is to be checked in the Unity editor: none of it has
run yet (§8).

**.NET Framework 4.5 and C# 7.3**, because that is what the owner's team builds with: .NET Framework 4.5, Visual
Studio 2019 and Unity 2019.4
([D-116](../architecture/03-decision-log.md#d-116--the-client-on-net-framework-45-alone-built-and-run-here-without-net-8-and-driven-in-clear)).

- **Every client project targets 4.5 alone**: the core, its tests, the headless driver and UnityCheck
  (`Backend.Client.sln`), and both simple clients. None targets .NET Standard or .NET 8.
- **Unity 2019 takes the core's library at its .NET 4.x API level alone.** A project at .NET Standard 2.0 switches to
  .NET 4.x (Player settings, Api Compatibility Level).
- **No API above 4.5.** The root's RSA key is read through `PublicKey.Key` and checked by
  `RSACryptoServiceProvider.VerifyData(…, "SHA256", …)`, because `GetRSAPublicKey` and `HashAlgorithmName` are 4.6's.
  Empty arrays are the core's own, because `Array.Empty` is 4.6's. The maze has no tuples, which 4.5 lacks.
- **No package in the app.** The core takes none. Its tests and the headless driver take `System.ValueTuple`, for
  their tuples, which ships in no app.

Before D-116 the core was .NET Standard 2.0
([D-108](../architecture/03-decision-log.md#d-108--the-client-for-unity-20194-and-net-framework-c-73-a-certificate-check-of-its-own-an-rsa-root)),
and the tests and the driver were .NET Framework 4.7.1 and .NET 8 (D-109). Before D-108 the core was .NET Standard
2.1 and C# 9, for Unity 2022.3 and 6 alone. Neither C# 7.3 nor .NET Framework 4.5 has `ReadOnlySpan<T>`, so a frame
is an array segment: an array with an offset and a count. `client/unity2019-check.sh` compiles the core with Unity
2019.4.28f1's own compiler.

**The main thread allocates almost nothing.** Its work on a frame (decoding, applying, predicting and building the
scene) allocates nothing but the names that a frame carries: a new tank's name, a kill's names, a phrase speaker's
name. The reason is the one for which the server's tick allocates nothing: on a phone, garbage shows up as dropped
frames. The receive thread allocates one array for each frame it hands over (`FrameReader`).

Namespaces follow the system's name ([README](../README.md#naming)): `Backend.Client.Core` and
`Backend.Client.Unity`. The code is in [`client/`](../../client/README.md).

## 2. Threads

Unity's API may be called from the main thread only. The world has a single writer, as a room has on the server
([07 §1](07-threading-and-performance.md#1-thread-inventory-arena-process)):

| Thread | Does | Touches the world? |
|---|---|---|
| Unity's main thread | applies frames, renders, samples input, sends inputs | **the only writer** |
| A receive thread for each match connection; a reading task and a writing task for each lobby connection | reads bytes and cuts them into frames, each whole frame a new array, and queues them | no |
| Timers on the main thread's clock | the ping, the input cadence, the reconnect backoff, and the silence that counts as a loss | no |

**A frame is decoded where it is applied**, on the main thread. A snapshot is about 80 bytes, fifteen times a second.
Decoding it on another thread would buy nothing but a second copy of the world. The receive queue is not bounded
(`MatchConnection`'s is a `ConcurrentQueue`). It holds fifteen frames a second, and when a client falls behind in
reading, the server is already stepping that client's link down to fewer frames
([02 §8](02-networking.md#8-traffic-profiles)).

## 3. The match connection

What the client must do is in [02 §3, §6, §9, §10](02-networking.md#3-messages):

- the protocol version first, in `Join` and in `Resume`;
- `Ping` every 10 seconds, in every state;
- `Input` ten times a second, whenever the tank is alive and the app is in the foreground. Each `Input` message is one
  input that joins the two samples taken since the last message: the latest move and aim, and fire if either sample
  fired. It carries the acknowledgement of the frames received;
- `Lifecycle` every time the app goes to the background and every time it returns;
- `Leave` to quit;
- TLS when the grant asks for it, checked against the name that the grant gives (`tlsName`, or else the host), with
  nothing pinned ([03 §2](03-gateway.md#2-position-and-shape)).

Behind the site's front
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)),
the grant's host is the site's address, and its `tlsName` is the arena's name. The TLS handshake sends that name, and
the front's nginx routes the connection by it. The client checks the certificate against the backend's own root
certificate, which the app ships (`TrustRoot`,
[operations/06 §5](../operations/06-certificates.md#5-in-the-app)), in place of the device's certificate store. The
client makes this check itself, for the name the handshake sends, and does not ask the runtime for its own verdict
([D-108](../architecture/03-decision-log.md#d-108--the-client-for-unity-20194-and-net-framework-c-73-a-certificate-check-of-its-own-an-rsa-root)).
The certificate must be issued by the root, be signed with RSA and SHA-256, be valid now, and carry the name in its
subjectAltName. A client in clear reaches the same arena through port 80 instead, as below.

**Through the site's 80, in clear**
([D-116](../architecture/03-decision-log.md#d-116--the-client-on-net-framework-45-alone-built-and-run-here-without-net-8-and-driven-in-clear)).
The site's 9001 speaks TLS alone. So a client whose API is in clear takes the site's grant through port 80, as
`client-simple-no-tls` does (D-115, §9):

- **When.** `MatchSettings.SiteFor(api, tls, tlsName)` gives the site to use. That is the API's address when the API
  is `http://` and the grant asks for TLS by an arena's name, as the site's grants do. Otherwise it gives null, and
  the grant's own host and port are dialled, with TLS when the grant asks for it. `BackendClient` and the headless
  driver set `MatchSettings.Site` from it.
- **How.** With `Site` set, the connection dials the site and sends `GET /match/<TlsName> HTTP/1.1`, with `Host`,
  `Connection: Upgrade` and `Upgrade: backend-match`. It reads the answer to its blank line, 4 KiB at most. On
  `HTTP/1.1 101` it plays the match in clear on that connection, with no TLS of its own.
- **When it fails.** Any other answer fails the connection, and `LastProblem` says "the site refused the match:
  <status>". A `Site` with no TLS name is never dialled: "a match through the site needs the arena's name, the grant's
  tlsName". Before any `Welcome`, either one ends the match `Unreachable`, as an arena that cannot be reached does.

What this document adds:

**A connection that falls silent is lost.** The arena sends fifteen frames a second. When no frame comes for
3 seconds, the client takes the connection as lost, and resumes it as after any loss. This rule holds while the app is
in the foreground, the match is joined (`InMatch`) and the own tank is alive.

A dead tank's player is sent no frames, by design: only its events, and the answers to its pings, which go every 10
seconds. So while the tank is dead, the limit is 13 seconds. With the 3-second limit, a player waiting to respawn, or
waiting for co-op's next wave, would be taken for a loss, and the resume would spawn the tank (defect
[P-53](../defects.md#2-protocol--the-client-contract)). **Dead** lasts from the Death event until a frame moves the
world's tick again, and only a player with a tank is sent such a frame. The arena never removes the own tank, so the
entity cannot say whether the tank is dead (P-54). Meanwhile `Alive` is false: no input goes out, nothing is
predicted, and the HUD offers Respawn.

Dialling and the TLS handshake are bounded too: 5 seconds for both together (`ConnectMs`), and 10 seconds for a `Join`
or a `Resume` to get its `Welcome` (`JoinMs`). So the client finds out that an arena's machine has gone within the
resume's minute, not by the operating system's timeouts, which run to minutes.

**What the client does on each ending.** `MatchConnection` ends with a `MatchEnd` value for each ending, or it resumes
by itself. What follows an end is the job of the layer above. **The package's scripts do not do it yet** (§8):
`BackendClient` keeps how the match ended (`LastEnd`) and goes back to the lobby screen. The wait after `Kick(2)`, the
backoff after `Kick(5)` and the log after `Kick(4)` are still to be written.

| Ending | `MatchEnd` | Then |
|---|---|---|
| A frame the world cannot apply: an update for a handle it does not hold, or a truncated frame | none: the core resumes, and `LastProblem` says "desync: …" | a desync, which guessing cannot survive: close, and resume as below. A resume starts a fresh view |
| The socket closes or fails without a `Kick`; or the silence above | none while resuming; `ResumeExpired` after the minute; `Unreachable` if the first connection was never made | Resume: dial the same arena with the latest secret after 0.5 s, 1 s, 2 s, 4 s and 8 s, then every 8 s, until 60 s have passed since the loss. Then back to the lobby for a ticket |
| `Kick(1)`: the ticket or the stay is unknown | `BadTicket` | back to the lobby for a ticket, at once |
| `Kick(2)`: no room | `RoomFull` | ask for another ticket after 2 s; the grant was spent |
| `Kick(3)`: the protocol version | `ProtocolVersion` | tell the player to update the app; never retry |
| `Kick(4)`: the rate limit | `RateLimit` | a bug in the client: log it with the last messages sent, and go back to the lobby screen. No loop |
| `Kick(5)`: a server fault, or a reason this client does not know | `ServerFault` | back through the lobby with a backoff: 1 s, doubling up to 30 s, each wait jittered by ±50 % so that a restarting arena's players do not all return together |
| `Kick(6)`: the match is over | `MatchOver` | a made match has ended ([04 §4](04-platform-services.md#4-matchmaking)): back to the lobby, where its result lands. Not an error; nothing to reconnect to |
| `Kick(7)`: removed | `Removed` | an operator took the player out, or closed the room ([04 §10](04-platform-services.md#10-admin-api)): back to the lobby; nothing to reconnect to |
| The player quits | `Left` | send `Leave`. The connection is closed once the arena has closed it, or after 2 s (02 §10, P-34); `Dispose` does not cut that short |

**The resume secret is kept in memory**, from the latest `Welcome`, **and on disk by the app**, for a cold resume
([D-51](../architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret)). In a
cold resume, an app that was killed and then restarted takes up its stay again. The app writes the secret, with the
grant's host, port and TLS, where it keeps the account's key, every time the app goes to the background. It clears
the secret when the stay ends. When the app restarts within the minute, it makes a `MatchConnection` with those
settings and calls `Resume(secret)` in place of `Join`. From then on it behaves as after any lost connection, and
`Kick(1)` means that the stay is over. The arena always accepted a `Resume` on any connection; only the core had no
way to start one.

- **The core's half is built** and drilled: `MatchConnection.Resume`, and the drill's `coldresume` scenario.
- **The app's half**, writing the secret on the way to the background and resuming with it on launch, is the layer
  above's, and **not in the package's scripts yet** (§8).

**Frames are timed by `tickDelta`**, never by the rate in `Welcome`, which is only where the traffic profile starts
([02 §8](02-networking.md#8-traffic-profiles)). The client asks for the traffic profile `mobile` by default, and
`saver` when the player turns that on in the settings. The setting is `MatchSettings.Profile`. Its default, −1, leaves
the profile out of `Join` and `Resume`, and the server reads that as `mobile`. The package's scripts have no such
setting yet.

## 4. The world, and when things are drawn

**The world is the server's `protocol/ClientWorld`, written in C#**, and checked against the same vectors
([02 §5](02-networking.md#5-entity-handles)). It applies:

- handles and creates;
- updates, as deltas in world space;
- removes, before creates;
- unknown events, stepped over by their byte length;
- predicted entities, which carry their death tick.

It clears nothing on a death, a respawn or a new match. `ExpirePredicted` drops a predicted entity whose death tick
has passed with no remove; nothing calls it today. The world's tick moves only with a frame. The server's remove of a
bullet comes in the frame that passes the bullet's death, over a connection that loses nothing, so the server's remove
is what ends a bullet.

**Drawing happens a little in the past.** Other tanks are drawn between their last two samples, at
`renderTick = newestTick − 2 frames`: 133 ms at 15 frames a second, and 200 ms at 10. That hides one late frame.
Bullets are extrapolated from their create to the render tick, and drawn nowhere after their destroy, which is
authoritative ([02 §6](02-networking.md#6-client-side-simulation)). A bullet's hit effect is drawn where the destroy
puts the bullet. Shapes are drawn where they are, and turned locally.

**A maze's walls are made on the client, never sent**
([01 §8.9](01-arena.md#89-maze-designed-2026-10-01-plan-item-32),
[D-48](../architecture/03-decision-log.md#d-48--a-maze-is-sent-as-a-seed-and-a-predicted-bullet-stops-at-its-walls-by-one-rule-on-both-sides)).
The `Welcome`'s `MazeSeed` (0 for none) is read when the frame has it. The connection's new `ClientWorld` makes `Walls`
from the seed with `Maze.Walls`, a port of the server's generator and its random numbers. A golden vector that the
Java side wrote holds it to the server's walls for one seed.

A predicted bullet's path is known whole from its create. So the client finds, once, the first tick at which the
bullet's centre comes within its radius of a wall, checked after each move as the server checks. The frame that
reaches that tick removes the bullet there, and reports the removal for its hit effect, before the server's destroy
arrives (the destroy then finds nothing). Between frames, the bullet is drawn no further. Tanks, shapes and units
slide along walls on the server and come in updates, so they need nothing here.

**Events reach the layer above through `MatchConnection.OnEvent`**: every event of every frame, as it is applied.
`ClientWorld` keeps the last frame's events only, and one `Poll` applies every frame waiting, several after any stall
([P-33](../defects.md#2-protocol--the-client-contract)). The events are:

- a death;
- the player's own progression;
- kills (`Kill`, with `Killer` and `Victim`, [01 §9](01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37));
- phrases ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)). A phrase with a handle is drawn over that
  tank. A phrase with handle 0 is drawn in the team's feed, by the speaker's name. The words are the list's, from
  `ApiClient.Phrases()`, kept until a `Welcome` names another `PhraseListVersion`. `Say(id)` sends a phrase. The
  server drops a phrase sent within two seconds of the last one, so the button can simply be greyed out for two
  seconds.

**A sandbox** ([01 §8.10](01-arena.md#810-sandbox-designed-2026-10-01-plan-item-33)) is a private room. It is opened
by `ApiClient.OpenSandbox`, for the player, or, when a party's leader calls it, for the party. A 503 `no_room` means
"not now": asked again a few seconds later, a room just freed is there. Each member's lobby is pushed
`evt.match.found`, as for a match found, and the connection goes there as to any match. In a sandbox, `SetLevel(n)`
rebuilds the own tank at a level from 1 to 45, and `SummonGuardian()` brings co-op's boss. The level comes back as the
player's own progression event, and the Guardian as a tank of its class. Outside a sandbox, the arena drops both.

**The player's own tank is predicted** (designed 2026-10-03, plan item 70; the rule is in
[02 §9](02-networking.md#predicting-the-own-tank),
[D-62](../architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry)).
The own tank is drawn in the present, while everything else is drawn a little in the past, as realtime clients of
this kind do. Then the time from an input to the tank's movement is zero, and what the player sees of others is a
frame or two old.

- **`TankMotion.Step`** is the room's step of a driven tank, in C#: the move bits turned into a direction, the
  acceleration and friction, the map's edge, and a maze's walls (`WallGrid`: the server's `Walls`, with cells of 300
  units, and a wall in every cell it covers). A trajectory that the server's own `Room` writes holds it to the
  server's step: `backend/arena/src/test/resources/motion-2026.txt`, written by the arena's `MotionVectorTest`. The
  trajectory has inputs that start, stop, turn and go diagonally, the map's corner, and a maze's walls. It gives each
  step's position and velocity as the bits of the floats, and the core's test must make them bit for bit.
  Single-precision arithmetic in the same order gives the same bits in Java and in .NET; the vector is what proves
  that it stays so.
- **`ClientWorld` keeps `Motion` and `MotionRule` as state**, not as events, so `OnEvent` never sees them.
  `SelfMotion` holds the input's ticks and the velocity (from this frame, or from an earlier one), and the
  acceleration and radius from the last `MotionRule`. The world also notes a frame that created the own tank afresh
  (a respawn).
- **`OwnTank`**, one for each `MatchConnection`, does the rest. `Poll` steps it by the clock at 25 Hz with the input
  held. Each step is stamped with the sequence number (seq) of the input message that will carry it. When an input is
  sent, the steps of its seq are stamped with the input actually sent, which is what the server will apply. `OwnTank`
  keeps 64 steps (2.56 s), each with the state after it. On each frame it compares, reconciles and replays (02 §9),
  and keeps the correction.
- **What the layer above draws**: `OwnTank.DrawPosition(now, out x, out y)` is the predicted position between the
  last two steps, by the time since the last step, plus what is left of the correction. The correction is halved
  every 50 ms; a correction over 64 units is taken at once. `Angle` is the aim held. `Predicting` says whether the
  tank is predicted now. It is not while the tank is dead, parked or joining, or before `MotionRule` has come; then
  the server's tank is drawn instead, at its newest sample.
- **What it measures, for the drill and for a development build**: each frame's error (the matched step's position,
  as predicted before the frame, against the frame's position), each correction (`OnReconciled`), the frames compared
  and the jumps. Measured live (02 §9): the error's median was 0.16 units, which is the wire's rounding; the 95th
  percentile was under a unit; there was no jump.

**Built 2026-10-03** (plan item 70): `TankMotion`, `WallGrid`, `OwnTank`, `ClientWorld.Motion`, and
`MatchConnection.OwnTank`, which `Poll` steps before the input goes out.

**Not predicted** (02 §9): recoil and knocks, which are corrected a frame late. The own bullets come from the server's
tank, a frame or two behind the drawn tank while it moves. Whether to draw them from the drawn barrel instead is for
the Unity layer to judge.

## 5. The lobby and the API

The API is [04's](04-platform-services.md), over HTTPS or plain HTTP. The lobby is
[03 §3](03-gateway.md#3-lobby-protocol), over a WebSocket, `wss://` or `ws://`. Each call's answers are in the
backend's [API table](../../backend/README.md#the-platform-api), and with examples in [docs/api](../api/README.md).
What the client adds is below.

### Connecting: endpoints, TLS and keys

- **The endpoints, tried in turn**
  ([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip)). Since D-82 the list
  has one endpoint, the site's address, for example `https://203.0.113.10`. `ApiClient` takes the list. When an
  endpoint cannot be reached, or its certificate is refused, the call moves to the next endpoint in the list. The
  client never changes an `https://` endpoint to `http://` by itself. The endpoint that answered is tried first next
  time. One exception: a `POST` that timed out is not sent to another endpoint, because it may have been done (a
  guest made, a team applied to). The call then answers `timeout`; a `POST` with a key is safe to send again with its
  key. `LobbyClient` takes one address. Trying the lobby's endpoints in turn is the layer above's, and **not in the
  package's scripts yet** (§8).
- **TLS and the backend's root.** The backend's root (`TrustRoot`) is trusted for both the API and the lobby, and each
  connection checks its own host. The API goes through `HttpWebRequest` (`TrustRoot.HttpHandler`, one certificate
  callback for each request). The lobby goes through the core's own WebSocket, over `SslStream`
  ([D-108](../architecture/03-decision-log.md#d-108--the-client-for-unity-20194-and-net-framework-c-73-a-certificate-check-of-its-own-an-rsa-root)).
  The core does not use the usual classes: Unity 2019's `HttpClientHandler` certificate callback is not implemented,
  and .NET Framework 4.5's `ClientWebSocket` has no callback at all. When the Unity package is given no root, it uses
  the platform's own `HttpClient` and the device's own certificate store, for a backend that a public certificate
  authority certifies.
- **TLS 1.2 asked for by name** (D-116). An assembly built for .NET Framework 4.5 gets TLS 1.0 alone by default, on a
  socket and on a request, and nginx refuses TLS 1.0. So the lobby's and the match's sockets ask for TLS 1.2, 1.1 and
  1.0 (`TlsVersions.Client`). `ApiClient`'s constructor adds TLS 1.2 and 1.1 to
  `ServicePointManager.SecurityProtocol`, for the whole process, and takes nothing away. The server chooses among
  them.
- **Any build connects the way it is set up**
  ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).
  A build given `https://`, `wss://` and the root is encrypted. A build given `http://` and `ws://` is in clear. This
  holds for a players' build too: D-104's refusal of plain connections in a players' build is gone. A grant's TLS is
  the backend's choice, and the site's grants ask for TLS. With an `https://` API the full client plays them over TLS
  on port 9001. With an `http://` API it asks the site's port 80 for the arena by name, and plays in clear (§3,
  D-116).
- **The account's key lives in the device's secure store** (the Keychain on iOS, the Android Keystore).
  `AccountKeeper` keeps a guest's key, and logs in by it on every launch (§8). Keeping the session token there too, so
  that a restarted app skips the login, is the layer above's, and **not built**: the package holds the session in
  memory. A 401 means: log in again.
- **429 and 503 are waits, not errors**: honour `Retry-After`. `ApiResult` carries it as `RetryAfterSeconds`. Waiting
  it out before asking again is the layer above's, and **not in the package's scripts yet**. `ApiClient` gives every
  call 10 seconds, which a login queued behind Argon2, the password hash, needs
  ([04 §1](04-platform-services.md#1-auth-and-sessions)).
- **A purchase key is made when Buy is tapped, and kept until the answer arrives**, so that a retry after a lost
  answer is the same purchase ([04 §8](04-platform-services.md#8-economy-shop-inventory-and-equipment)). Keep it on
  disk: an app killed in the middle of a purchase that retries with a new key would buy twice.
  `ApiClient.NewPurchaseKey()` makes a key. Keeping it is the layer above's, and the package has no shop screen yet.

### The lobby connection

- **The lobby socket pings every 30 seconds**, and the gateway answers `ping.ok`. A ping followed by 10 seconds in
  which nothing at all is heard is a lost connection (`LobbyClient.PingAnswerMs`). So the client finds a lost
  connection before the socket would say so. A message that the socket delivers in fragments is joined, and decoded
  once it is whole.
- **When the lobby does not reconnect.** On `evt.session.replaced` the lobby does not reconnect: the player signed in
  elsewhere. Nor on `evt.session.revoked`: an operator banned or suspended the account, or the account was deleted
  (D-84). Logging in afterwards says whether the account may play. Nor after `invalid_session`, which means: log in
  again. (A match that an operator ends is `Kick(7)`, `MatchEnd.Removed`: back to the lobby, with nothing to reconnect
  to; [03 §4](03-gateway.md#4-connection-lifecycle).) Any other loss reconnects after 1 second, doubling up to 30
  seconds, each wait jittered by ±50 % so that a restarting gateway's players do not all return together.

### Queues and matches

- **The queue for timed matches** ([04 §4](04-platform-services.md#4-matchmaking)). The client sends `queue.join` over
  the lobby, and the match arrives as the push `evt.match.found`, which carries the grant. A client that reconnects its
  lobby while queued asks `GET /v1/queue` (`ApiClient.Queue`). That answers `matched`, with the grant, if the push was
  missed. The grant lives as long as its ticket: 60 seconds. A gateway sends `evt.resync` after it dropped pushes that
  it could not deliver ([03 §8](03-gateway.md#8-backpressure-and-slow-clients)). `evt.resync` reaches `OnPush`, and it
  asks for the same: fetch the queue, the party and a tournament match again. Both fetches are the layer above's, and
  **not in the package's scripts yet**.
- **A match found is asked about first**
  ([D-27](../architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made)).
  `evt.match.ready` puts `LobbyClient` in `confirming`, with `Ready` holding the match. `AcceptMatch()` or
  `DeclineMatch()` answers it within its ten seconds; a refused answer leaves its code in `AnswerRefusal`. The match
  itself still arrives as `evt.match.found`. A match that is called off arrives as `evt.queue.update`: queued again,
  or "none" for whoever declined, who is then refused `queue_locked` for a minute.
  - `Ready` is cleared when the match is found (`evt.match.found`), when the queue changes (`evt.queue.update`), and
    when a decline is answered (`match.decline.ok`).
  - `JoinQueue` starts a queue afresh: `QueueState` "none", and `Ready` cleared.
  - `TakeGrant()`, which the layer above calls as it joins a grant, hands the grant over and gives the queue back to
    the player as "none". So the next queue is not taken for the last match.
  - What to show, and whether to accept on the player's behalf, is the Unity layer's choice.
- **What a match paid** arrives as `evt.rewards` through `OnPush`, after `worker` applies the result
  ([05 §6](05-worker-and-events.md#what-a-match-paid-pushed-designed-2026-10-01-plan-item-43)). It gives the place,
  the coins, the experience, the rating change, the gems of a level milestone or of an achievement, each achievement
  reached by id, and each daily goal met with its coins (04 §8). `ApiClient.Goals` lists today's three goals. A client
  that missed the push reads its inventory, and `ApiClient.Achievements` lists every achievement with the player's
  progress.

### Parties, teams and tournaments

- **A team** ([04 §2](04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39)).
  `LobbyClient.Team` is the team as the last `evt.team.update` gave it: null for none, or before any came. The calls
  are `ApiClient`'s (`MyTeam` and the rest).
- **A party** ([04 §4](04-platform-services.md#parties), designed 2026-09-29). `LobbyClient` sends `party.invite`,
  `party.accept`, `party.leave` and `party.kick`. It keeps:
  - `Party`: the party, as the newest answer or `evt.party.update` gave it; null for none. `PartyState` decides which
    state is the newest: an older state of the same party that arrives after a newer one is not applied, and neither
    is a late state of a party it saw end (D-74);
  - `Invitation`: the last `evt.party.invite`;
  - `PartyRefusal`: the code of a refused request.

  `SayToParty(id)` sends `party.say`. Each `evt.party.said` is told to `OnPartySaid`, one call each, since several can
  arrive between polls ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)).

  A member does not queue; the leader does. Each member's `QueueState` follows `evt.queue.update`, and the leader's
  follows its own answer. **A join's answer is applied only while nothing has changed the queue since the join was
  sent** ([T-61](../defects.md#4-concurrency)). An `evt.queue.update` that arrives first, or a `LeaveQueue`, makes the
  answer late, and the answer is dropped. Here is why. The platform tells the members before it answers the leader.
  So when a member leaves at once, the party is taken out of the queue and the leader is told, while the leader's
  answer may still be on its way. Applied last, that answer would show the leader queued in no queue, waiting for a
  match that cannot come. The other order can happen too: a push already on its way when the player asked shows
  "none" while the party is queued. Then the next tap is refused `already_queued` (`QueueRefusal`), which says so, and
  a match found arrives anyway.

  A `matched` state is not undone by `evt.queue.update`: once matched, the match is the member's own, and pushes from
  different threads can arrive out of order.

  A client whose lobby reconnects fetches `GET /v1/party` and `GET /v1/queue` (`ApiClient.Party` and `Queue`; the
  layer above's, not in the package's scripts yet). The pushes are notifications, and one missed while away is not
  sent again.

  The core reads the party's JSON in one place, `ApiClient.ReadParty`, which is tested with the other messages. The
  rest is driven live: six players, three of them a party, queue for team against team and are matched into one
  room, with the party on one side.
- **Tournaments** ([04 §6](04-platform-services.md#6-tournaments), designed 2026-09-30). `ApiClient.Tournaments()`
  and `Tournament(id)`, the bracket among the rest, need no session; `EnterTournament` and `WithdrawFromTournament`
  do. A tournament match is pushed as `evt.tournament.match`. `LobbyClient` keeps the last one pushed as
  `TournamentMatch`, and it is joined as any grant is. A client whose lobby reconnects asks
  `ApiClient.TournamentMatch(token, id)`, which answers while the ticket lasts: 60 seconds. A player who never comes
  loses by walkover if the other player came. If neither came, the higher seed goes through 270 seconds after the
  tickets. What to show, the bracket and the countdown, is the Unity layer's.
- **Team matches**
  ([04 §4's sixth slice](04-platform-services.md#the-sixth-slice-team-matches-designed-2026-09-30-plan-item-19),
  designed 2026-09-30). The team's leader or a vice leader, leading a party of three of the team's members, queues
  the party as any queue: `JoinQueue("teams")`. A refusal leaves `party_too_small`, `not_one_team` or `not_allowed` in
  `QueueRefusal`. The match is found, asked about and joined as any match. `TeamInfo` carries the team's `Rating`,
  `Wins`, `Losses` and `Draws`.
- **Teams' tournaments**
  ([04 §6's second slice](04-platform-services.md#the-second-slice-teams-tournaments-designed-2026-09-30-plan-item-20),
  designed 2026-09-30). `TournamentInfo.Mode` is `teams`. An entry carries `TeamId` and its `Roster`, and a match
  carries `TeamA`, `TeamB` and `WinnerTeam`. A team's leader or a vice leader, leading a party of three of the team,
  calls `EnterTournament`, as for a duel; the three are its roster. Each of them is pushed the match, as a duel's
  player is.

### Friends, guests and content

- **Friends, blocks and the inbox**
  ([04 §9](04-platform-services.md#the-social-layers-first-slice-designed-2026-09-30-plan-item-21), designed
  2026-09-30). The calls are `ApiClient.Friends` (each friend's `Online`, and the requests both ways), `AskFriend`
  (which answers "asked" or "friends"), `RemoveFriend`, `DropFriendRequest`, `Blocks`, `Block`, `Unblock`, `Inbox`
  and `ReadInbox`. The pushes `evt.friend.request`, `evt.friend.accepted` and `evt.inbox` reach `LobbyClient.OnPush`;
  `evt.inbox` means: read `Inbox` again. An inbox item is a kind and an id, never words: the Unity layer writes the
  words (FR-11).
- **Guests** ([04 §1](04-platform-services.md#1-auth-and-sessions), designed 2026-09-30). `ApiClient.CreateGuest`
  answers a `Guest`, with its `GuestKey` and `DisplayName`. The key goes to the device's secure store (`AccountKeeper`
  over `ISecureStore`, which the package's `SecureStores` implements, §8), and `LoginGuest` takes it on every launch.
  The session token is held in memory only (above). `UpgradeAccount`, called with the guest's session, a username, a
  password, and a display name or none, makes the guest a full account, the same player. From then on the player logs
  in by name with `Login`, and the key is refused.
- **The class table is fetched, not built in**
  ([D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)).
  `ApiClient.Classes()` reads `GET /v1/content/classes` into the core's own `ClassTableInfo`, whose version is the one
  a `Welcome` names in `contentVersion`. A client keeps the table it has until a `Welcome` names another version, then
  fetches it again. What it draws with the table is the Unity layer's.
- **JSON is read and written by the core itself**, strictly (RFC 8259, with the depth bounded). The messages are a few
  flat objects, and a JSON package would be the core's first dependency
  ([D-19](../architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)).

## 6. Testing

| What | How | Where |
|---|---|---|
| The wire and the world | every decoded field of every golden vector; unknown events stepped over; malformed frames refused | the core's tests, compiled by Unity 2019.4's compiler and run on its Mono, on this machine (`client/core-tests.sh`, D-116) |
| The maze | the walls for one seed against the server's golden vector (`backend/sim/src/test/resources/maze-2026.txt`); a bullet ending at a wall by the server's rule; live, the `maze` drill | the core's tests; the headless client |
| The messages the client sends | encoded here, decoded by the real server | the headless client |
| The whole path | a headless client driving the real stack from a release: register, log in, the lobby, a ticket, join, input, a death, a respawn, a dropped socket and a resume, `Leave`, and the result in MySQL. In clear alone, on Unity 2019's Mono (D-116): straight to the processes, through nginx run plain (`FRONT=plain`), and through nginx's 80 as the site's (`FRONT=http`), its matches by `/match/` and the arena's name. `TLS=1`, `FRONT=https` and the `untrusted` scenario are refused | this machine (`client/headless-drill.sh`); `check-ports-el9.sh` and `check-two-machines-el9.sh` through the site's 80, in their containers |
| A match through the site's 80 (§3, D-116) | against a stand-in for nginx's 80 on a loopback socket: the request exact, and the match played on that connection once the `101` is read; a site that answers anything else, and a site without the arena's name, each ending the match `Unreachable`, saying why; when `SiteFor` takes the site and when not. Live, the drills through `FRONT=http` | the core's tests (`MatchConnectionTests`); the headless client |
| Prediction | the movement rule bit for bit against the trajectory that the server's `Room` wrote (`motion-2026.txt`); stamping, matching, replay, corrections and jumps on a frozen clock; live, the `prediction` drill: each frame's predicted position against the server's | the core's tests; the headless client |
| What a frame draws, the sticks, the account kept, the party's state (§8 (a), D-74) | the scene at a render tick, and the render clock, on a frozen clock; the sticks' sectors, dead zone and fire; a first launch, and a key refused, against a scripted API and a store in memory; older and ended parties' states refused | the core's tests |
| The lobby client | against a scripted lobby on a loopback WebSocket: the queue "none" again after a match, a declined match forgotten, a message split inside a character read whole, a silent lobby found lost, reconnects spread out; and live, the drills | the core's tests; the headless client |
| The tests' own list | `Core.Tests/README.md`'s rows compared with the test assembly by reflection, as the server's READMEs are checked by its build (DOC-29, DOC-31) | `ReadmeTestListTests`, in every run of the client's tests |
| The Unity package's scripts | compiled against stubs of the Unity API, for no platform, for the editor, for iOS and for Android; the Android plugin compiled against stubs of Android's API | `client/unity-check.sh`; nothing run |
| The core in Visual Studio 2019 (D-108, D-116) | built with warnings as errors by Visual Studio 2019's own MSBuild and Roslyn (16.11, and 16.7), from Microsoft's .NET 5 and .NET Core 3.1 SDK images, used as Visual Studio 2019's build tools alone: the core's project (.NET Framework 4.5, against Microsoft's 4.5 reference assemblies); a 4.5 app that references it; Unity 2019.4's project, as Visual Studio 2019 compiles it (4.7.1, Unity's .NET 4.x level, `UnityEngine`, the reference of the package's `csc.rsp`, each platform branch); and `Backend.Client.sln`, the client's one solution, Visual Studio 2019's, with its four projects, every one .NET Framework 4.5 (D-109, D-116). A C# 8 operator, and an API that .NET Framework lacks, each failed every build (D-108) | `client/vs2019-check.sh`; not inside Visual Studio, and nothing run on .NET Framework itself |
| The core and the scripts in Unity 2019.4 (D-108, D-113, D-116) | compiled by Unity 2019.4.28f1's compiler (Roslyn 2.9.1, C# 7.3, warnings as errors): the core for .NET Framework 4.5, against Mono's 4.5 reference assemblies (`client/unity-env.sh`); the scripts against Unity's own `UnityEngine` assemblies at Unity's .NET 4.x level (.NET Framework 4.7.1's references, what Unity gives a script there), each platform branch, with that level's default references and the package's `csc.rsp` as Unity reads it, and refused without it. Then the core run on Unity's Mono 5.11: certificates made by `private-ca.sh` checked, an EC root refused with the reason, JSON under a decimal comma, and the API, the lobby's `wss://` and a match, each through TLS to a server there, accepted with the root's certificate and refused with another's. And the core's 191 tests, compiled by Unity's compiler for .NET Framework 4.5, run there by NUnitLite with `float32` (`client/core-tests.sh`; D-109, P-65) | `client/unity2019-check.sh`, `client/core-tests.sh`; not the editor (a licence), not Unity's own TLS (the editor's and the player's; this is Mono's own, at TLS 1.0), not a phone |
| The simple client, `client-simple/` (§9, D-111, D-112, D-116) | compiled by Unity 2019.4.28f1's compiler at C# 7.3: `Runtime/` for .NET Framework 4.5, and at Unity's .NET 4.x level (with `csc.rsp`'s `System.Net.Http`, and refused without it); `Examples/` against Unity's `UnityEngine` at that level. Offline, on Unity's Mono: JSON; every match message byte for byte as the core writes it; the arena's answers read as the core reads them; frames; the WebSocket against RFC 6455's examples; the certificate check; the API's calls and the app's certificate check; HTTPS through `HttpClient`; and the lobby's `wss://` and a match's TLS (its grant's name checked) against `openssl s_server` at TLS 1.0. Live, against the drill's stack, in clear alone: straight to the processes and through nginx run plain (`FRONT=plain`), the API at `http://`, the lobby and a match, and every lobby and match callback run on the thread that calls `Update()`. Not through `FRONT=http`, whose grants ask for TLS; `client-simple-no-tls` is driven there (§9). Until D-116 its TLS ways ran live on .NET 8 | `client-simple/check.sh`; `headless-drill.sh SIMPLE=1`; on Unity's Mono alone, whose own TLS here stops at 1.0: `check.sh live` refuses a root or a `wss://` lobby |
| The C# client through nginx's TLS (443 and 9001); the name its TLS handshake sends, as a server sees it; the TLS 1.2 it asks for, on .NET Framework itself | not checked here since D-116. Unity's Mono outside the editor speaks TLS 1.0 alone, and nginx 1.2 and 1.3; only .NET 8 showed a server the handshake's name; and there is no .NET Framework on Linux. nginx's TLS stays checked by `openssl s_client` (`check-ports-el9.sh`, `check-two-machines-el9.sh`), and the core's TLS between its own two ends, at TLS 1.0 | **the Unity editor and a phone**; Windows, for .NET Framework |
| Rendering, touch, the app's lifecycle, secure storage, the device's TLS, frame time on a phone | the Unity editor and devices | **not on this machine** |

The tests use NUnit, which Unity's test runner also runs, so the core's tests can run there unchanged.

## 7. Order of work

1. **The core's wire and world, against the vectors.** **Built 2026-09-27**: every decoded field of every vector, and
   the world against the server's reference down to its rounding. Eleven deliberate faults were each caught.
2. **The match connection, and a headless client against the real arena**: join, the input cadence, the
   acknowledgement, the ping, the lifecycle, every kick, a resume. **Built 2026-09-27.** It ran against the real stack
   from a release, in plain text and over TLS ([`client/headless-drill.sh`](../../client/headless-drill.sh)):
   - it joined, and moved at the top speed that the server's rule allows, so every input was read;
   - it received 15 frames a second;
   - its own bullets arrived at exactly 10 units a tick, as protocol 2 carries them;
   - a class it could not have yet was refused, without closing anything;
   - a dropped socket resumed within a second, to the same tank, with a new secret;
   - a bad ticket got `Kick(1)`;
   - the background stopped the frames, and the foreground restarted them;
   - an arena whose certificate was not trusted was refused.

   What the real arena cannot be made to do on demand (each kick reason, a broken frame, the resume backoff and its
   minute) is tested against a scripted arena on a local socket. Since D-116 (2026-11-25) the drills run the client in
   clear alone, on Unity 2019's Mono: the TLS run and the untrusted arena are refused (§6).
3. **The API and the lobby; the headless client goes the whole path.** **Built 2026-09-27.** It logs in over the API,
   authenticates the lobby, asks it for a match, plays past the reward's minimum and leaves; the worker's reward is in
   the inventory within a third of a second. A second sign-in replaces the first lobby connection, which does not come
   back. A made-up token is refused, and not retried. Endpoint rotation, refusals and `Retry-After` are tested against
   a scripted HTTP handler. Then, scenario by scenario:
   - **The queue** (2026-09-27): two players queue for a duel, are matched by the push, play it in the room made for
     it, and are sent back with `Kick(6)`; the result reaches MySQL
     ([04 §4](04-platform-services.md#4-matchmaking); [client/](../../client/README.md)).
   - **Parties** (2026-09-29): six players are in the lobby. One invites two, who accept, and all three are told the
     party of three. A member's `queue.join` is refused `in_party`, and the leader's for a duel `party_too_big`. The
     leader queues the party for team against team, and both members are told. A member's leaving the queue takes the
     party out, the others are told, and it queues again. Three more players queue alone. All six are matched into
     one room: the party is team 1, and the three alone team 2. The five minutes end the match, all six are paid, a
     rated team match is counted for each, and the party still stands.
   - **The confirm step** (2026-09-29): every queued scenario is asked `evt.match.ready` and accepts. In `decline`,
     one of two declines; the other is told it is queued again; nobody is sent to a room; and the one who declined is
     refused `queue_locked`.
   - **Ranked free-for-all** (2026-09-29): eight queue alone, are asked and accept, join one room each for
     themselves (team 0), and play the four minutes out. Each is paid, and counted a rated free-for-all.
   - **Co-op** (2026-09-29): a party of two and one player alone are asked as one team, and accept. Team 1 plays
     against the arena's waves, which come into view and hunt all three down. The wipe ends the match, and each is
     paid.
   - **Tournaments** (2026-09-30): an operator creates a duel tournament of four. The four enter and are pushed their
     matches. One side of each match comes, and wins by walkover; round 1's second match waits for the arena's one
     room ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
     The final comes a minute later, and the prizes are in each wallet.
   - **Team matches** (2026-09-30): two teams of three, a party for each. A party led by a plain member is refused
     `not_allowed` until the team makes them a vice leader. The match is played out, a team on each side, and each
     team counts it.
   - **Teams' tournaments** (2026-09-30): two teams' parties are entered as their rosters. The final is pushed to all
     six, one roster wins by walkover, and each member is paid.
   - **The social layer** (2026-09-30): a friend request is pushed and is in the inbox; it is accepted by asking back;
     presence shows online and offline; a block ends a friendship and silently drops the next request; the inbox is
     read.
   - **Guests** (2026-09-30): a guest is made, comes in again by its key, is paid for a stay, is upgraded, and comes in
     by name as the same player with the same coins; its key is then refused.
   - **The operator's figures** (2026-09-30, `stats`): a new guest's stay counts as one more player active today and
     one more new player, with day 1 empty until tomorrow has ended; and two new guests who made friends that day are
     counted under `friend`, by feature.
   - **Co-op's boss** (2026-09-30, in `play`): the class table that a client fetches has the Guardian, which no level
     opens.
   - **Domination** (2026-10-01, `domination`): six queue alone, and are matched three a side. One drives to the
     middle line and sees a neutral dominator. Nobody takes one, and the five minutes end in a draw; each is paid.
   - **Tag** (2026-10-01, `tag`): six are matched three a side. Nobody fires, so nobody goes over to the other team,
     and the five minutes end in a draw; each is paid.

   The domination and tag scenarios share one routine, `Alone`, which the `maze` scenario uses too.
4. **The own tank, predicted** (§4; plan item 70, brought back by the owner on 2026-10-03). **Built 2026-10-03**: the
   movement rule bit for bit against the room's own trajectory, and live, the `prediction` drill.
5. **The Unity layer**: scenes, drawing, touch, the lifecycle, secure storage. **Written 2026-10-04** as scripts over
   the core (§8), compiled against stubs, and since 2026-11-05 by Unity 2019.4's compiler too (D-108); not run.
6. **Phase 0's last question, in Unity**: does a bullet that overshoots until its late destroy look wrong in motion
   ([D-9](../architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client),
   [protocol-spike](../../protocol-spike/README.md))?

Steps 1 to 4 are verified on this machine; steps 5 and 6 need Unity.

## 8. The Unity layer, as scripts (designed 2026-10-04, plan item 77)

The owner's answer to Q-51 was: "could you make scripts as you can, as possible? to be used in Unity develop tools and
environment". This is the layer of §1, written, in two parts
([D-73](../architecture/03-decision-log.md#d-73--the-unity-layer-is-thin-scripts-over-the-built-core-what-can-be-engine-free-moves-into-the-core-first)):

**(a) What can be engine-free moves into the core first, and is tested here.** These are three things that a Unity
layer would otherwise work out for itself. **Built 2026-10-04**: `Scene`, `RenderClock`, `TouchSticks` and
`AccountKeeper`.

- **What is drawn where, at a render tick** (§4's rule, which was not built until then). `ClientWorld` keeps, for
  each tank and unit, its sample before the newest one, and the ticks of both.
  - `RenderClock` turns three things into the render tick: the newest frame's tick, when that frame came, and the time
    now. The render tick is the newest tick, plus the ticks since it came, less two frames' worth. Two frames' worth is
    twice the frames' tick delta, averaged, because at 15 frames a second of 25 ticks the deltas alternate between one
    and two. The render tick never passes the newest tick, and never goes back.
  - `Scene` is the list that a frame draws. It is reused, and allocates nothing. For each live entity it has: its
    kind and handle; its position in world units and its angle in radians, at the render tick; its radius, class,
    team and skin; its health as a fraction; its flags and name; and whether it is the player's own. A bullet's or a
    unit's radius comes on the wire; a tank's is its class's, as the server sizes it: 30 units times the class's
    `bodySize`, from the class table the scene is given (`Scene.Classes`, `ClassTableInfo.TankRadius`), and 30 for a
    class the table does not have or before there is a table (P-67). A tank or a unit
    is placed between its two samples, turning the shorter way round for the angle. A bullet is extrapolated by
    `PredictedAt`. A shape is where it is. The own tank comes from `OwnTank` while it predicts, in the present.
- **Two sticks to an input.** `TouchSticks` turns a stick's offset, as a fraction of its reach, into the move mask:
  eight directions, by 45° sectors, past a dead zone of a fifth of the reach. It turns the aim stick into the wire's
  aim, and fires while the aim stick is pushed past half its reach. It does the same for a mouse and keys in the
  editor.
- **Keeping the account across launches.** `ISecureStore` has three calls: get, set and delete a string. In the Unity
  layer the device's keychain or keystore answers them; in the tests, memory does. `AccountKeeper` makes the first
  launch a guest (`POST /v1/guests`), keeps the guest's key in the store, and signs in by it on every launch after. A
  key that is refused is forgotten, and a new guest is made only if asked. Its continuations stay on the caller's
  context, so in Unity the store is touched on the main thread, which `PlayerPrefs` requires.

**(b) The Unity package** (**written 2026-10-04**; compiled as below, and run nowhere). It is
`client/Unity/com.backend.client`, a UPM package (Unity's package format) of thin scripts over the core. The scripts
reach the core as its library, which `client/unity-package.sh` compiles with Unity 2019.4's compiler for .NET
Framework 4.5 and copies in (D-116). Unity 2019 takes that library at its .NET 4.x API level alone. The package's
`Runtime/csc.rsp`, beside the assembly definition, gives the scripts `System.Net.Http`, which Unity does not reference
by default at that level (D-113):

| Script | Does |
|---|---|
| `BackendClient` | the one object that owns the core's objects. Its `Update` completes the sign-in, pumps the lobby and the match, takes a grant (`TakeGrant`) and joins it, fetches the class table when a match's Welcome names one it does not hold (the scene's tank sizes, P-67), and builds the scene. A sign-in that fails, or a session that ends (`InvalidSession`), is reported (`Problem`) and signs the player out, so the screen offers to sign in again. `OnApplicationPause` is the lifecycle (02 §10) |
| `WorldView` | draws `Scene`: a pooled `SpriteRenderer` for each handle, sized by the item's radius, its sprite chosen by kind, class and skin from a `LookTable` asset |
| `TouchControls` | two sticks where the thumbs land (keys and the mouse in the editor), fed into `TouchSticks`, then `SetInput` |
| `FollowCamera` | follows the own tank, with the view's size as the arena's |
| `LobbyScreen`, `Hud` | the least screens to play. The lobby screen: play now, a duel's queue and its state, a match found to accept, and the account deleted after a second ask (D-84). The HUD: level and experience, the kill feed, respawn and leave |
| `SecureStores` | the keychain on iOS (a small Objective-C plugin), the keystore on Android (a small Java plugin over `AndroidKeyStore`, with no library), and `PlayerPrefs` in the editor and any other build (a desktop's), which it names as not secure |

**Checked here, and not.** The core's additions are checked by its tests. The package's scripts are compiled on this
machine in two ways:

- **Against stubs** of the Unity API they use (`client/unity-check.sh`), by Unity 2019.4's compiler against .NET
  Framework 4.5's references (D-116): once with no platform defined, once for the editor, and once each with
  `UNITY_IOS` and `UNITY_ANDROID`, so every branch compiles. The Android plugin's Java is compiled against stubs of
  the Android API.
- **By Unity 2019.4.28f1's own compiler, against Unity's own `UnityEngine` assemblies** (`client/unity2019-check.sh`,
  D-108; §6).

These catch the C#'s own mistakes, the core's API misused, and a call that Unity's API does not have. They do **not**
catch Unity behaving differently from what the scripts expect, which only the editor or a phone can show. The stub
check compiles the core from its sources, not the library in the package, so `unity-package.sh` must copy the
library in again whenever the core changes. Nothing here is run in Unity, drawn, touched or put on a phone. The
package's README says so, and says what the owner checks first in the editor.

### Not in the package's scripts yet

These are things that §3 and §5 say a client does, and that the core makes possible, but that the package's scripts
do not do yet. Each is the layer above's, on calls the core has:

| What | Where it is specified | What the core gives |
|---|---|---|
| A cold resume: the secret written on the way to the background, and a resume on launch | §3, D-51 | `MatchConnection.Resume(secret)`, `Welcome.ResumeSecret` |
| What follows `Kick(2)`, `Kick(4)` and `Kick(5)`: another ticket after 2 s, a log, the jittered backoff | §3 | `MatchConnection.End` |
| The session token kept, so that a restart skips the login | §5 | `Session.Token`, `ISecureStore` |
| The lobby's endpoints tried in turn | §5, D-13 | `LobbyClient` takes one address |
| `Retry-After` honoured | §5 | `ApiResult.RetryAfterSeconds` |
| The queue, the party and a tournament match fetched again after a lobby reconnect or `evt.resync` | §5 | `ApiClient.Queue`, `Party`, `TournamentMatch`; `LobbyClient.OnPush` |
| A purchase key kept on disk until its answer | §5 | `ApiClient.NewPurchaseKey()` |
| The `saver` traffic profile as a setting | §3 | `MatchSettings.Profile` |

## 9. The simple client (designed 2026-11-24, plan item 132)

`client-simple/`
([D-111](../architecture/03-decision-log.md#d-111--a-simple-client-beside-the-core-client-simple-three-small-clients-on-net-standard-20-examples-in-unitys-terms);
its API, [D-112](../architecture/03-decision-log.md#d-112--the-simple-clients-api-over-httpclient-awaited-the-backends-root-as-the-apps-certificate-check))
is for a UI team that wants to read the client in an hour and paste it into a project. It sits beside the full
client, and does not replace it. Its README is the guide. In short, it has these parts:

| Part | What | Over |
|---|---|---|
| `BackendHttp` | the API: `GetAsync`, `PostAsync`, `PutAsync` and `DeleteAsync`, each awaited, each giving an `HttpAnswer` (the status, the body, and the API's error code) | `HttpClient`, with the backend's root as the app's certificate check (`ServicePointManager`'s), the only check that Unity 2019's `HttpClient` calls |
| `LobbyClient` | the lobby: `Connect(token)` sends `auth` first, and `OnAuthenticated` follows; `Send(type, data, onReply)` matches the reply by its id; pushes go to `OnPush`; a ping goes every 25 s; `OnClosed` is called once | `WebSocketConnection`, the client side of RFC 6455 (the WebSocket protocol) over `TcpClient` and `SslStream` |
| `ArenaClient` | a match: `Connect` with a grant sends `Join`; the callbacks are `OnWelcome`, `OnSnapshot(bytes, tick)`, `OnPong`, `OnKick` and `OnClosed`; `SendInput` acknowledges the last tick; a `Ping` goes every 10 s; and `SetBackground`, `Resume` and `Leave` | `TcpClient`, and `SslStream` with the grant's `tlsName`; `ArenaMessages`' frames |
| `TrustRoot`, `Json` | the core's certificate check, without its `HttpClient` handler; and reading one field of a JSON object | |

**The main thread.** The API's calls are awaited, and Unity runs the code after an `await` on its main thread. The
lobby and a match each work on their own thread, and queue what happens. `Update()`, called from a script's `Update`,
runs every callback on the main thread. There is no coroutine, and no `UnityEngine` in `Runtime/`, so the same code
runs here on Unity's Mono, outside the editor.

- Both trees target .NET Framework 4.5 and are compiled at Unity's .NET 4.x level, the one a project uses (D-116).
- Everything is checked on Unity's Mono. The API's HTTPS, the lobby's `wss://` and a match's TLS are checked offline,
  against `openssl s_server` at TLS 1.0. Live, the simple clients run in clear alone: Mono's own TLS stops at 1.0, and
  nginx takes 1.2 and 1.3. Until D-116 the TLS ways ran live on .NET 8.
- The lobby keeps its own WebSocket. At Unity's API levels, .NET's `ClientWebSocket` has no certificate check to
  set; and on Unity 2019's Mono it ignores the app's check too, and refuses the backend's certificate (a probe,
  2026-11-25).

**What it leaves to the core.** A snapshot is handed over as its bytes, with the server's tick. Decoding it,
predicting the own tank and drawing are §3's and §4's work, in `SnapshotDecoder`, `ClientWorld` and `OwnTank`. A resume
is one call, `Resume()`, which the example retries five times within the minute. The core's cold resume,
`Retry-After`, the lobby's endpoints in turn and a purchase key kept on disk are not in it.

**Its examples** are three scripts (`HttpExample`, `LobbyExample` and `ArenaExample`), and the `[Serializable]`
classes that `JsonUtility` maps (`ApiModels.cs`). They connect the way they are set up, in any build (D-115).

**Without TLS: `client-simple-no-tls/`**
([D-114](../architecture/03-decision-log.md#d-114--a-simple-client-without-tls-client-simple-no-tls-for-a-backend-run-plain)).
It is the same three clients with no TLS at all, in six files (no `TrustRoot`). It is for a backend run plain
(`PLAIN=1`, D-101) and for the site's port 80 (D-115), in any build.

- **A grant without TLS** (a backend run plain) is joined with `Connect(host, port, ticketId)`.
- **A grant of the site's** asks for TLS. This client's `ArenaClient.ConnectThroughSite(apiUrl, tlsName, ticketId)`
  takes it to the API's own address, `http://<address>`, instead. It asks nginx for the arena by name
  (`GET /match/<tlsName>`, with `Connection: Upgrade` and `Upgrade: backend-match`), reads the `101`, and then goes on
  as `Connect` does.
- **A grant with TLS and no name** (an arena with TLS of its own, a drill's) it cannot reach, and it says so.

`ArenaMessages` and `Json` are `client-simple`'s, byte for byte. `client-simple/check.sh` checks this client too
(`NO_TLS=1`): the check program's TLS checks are left out, and a stand-in for nginx answers the request, and refuses
it. `SIMPLE=1` drills it live, straight to the processes and through nginx's port 80 (`FRONT=http`, `FRONT=plain`),
with its match going through `/match/` where the grant asks for TLS. `client-simple` keeps port 9001 with TLS. The
full client takes `/match/` too, when its API is in clear (§3, D-116).

**Three clients to keep in step.** A protocol version, a message or a field changes in both simple clients and in the
core. `headless-drill.sh SIMPLE=1` runs the simple clients against the drill's own stack, so a change that breaks them
fails there.
