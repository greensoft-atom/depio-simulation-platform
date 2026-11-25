# client-simple

A small C# client for the backend, to put in a Unity project:

- **the API**: HTTP calls such as logging in, the shop or the inventory, through `HttpClient`;
- **the lobby**: a WebSocket that stays open between matches;
- **a match**: a TCP socket to the arena.

Copy one folder, set two addresses, `await` the API's calls, and call the lobby's and the match's `Update()` every
frame. It does not replace [`client/`](../client/README.md), the full client with prediction, the world and drawing
([D-111](../docs/architecture/03-decision-log.md#d-111--a-simple-client-beside-the-core-client-simple-three-small-clients-on-net-standard-20-examples-in-unitys-terms),
[D-112](../docs/architecture/03-decision-log.md#d-112--the-simple-clients-api-over-httpclient-awaited-the-backends-root-as-the-apps-certificate-check)).

## Which Unity

Any version from **Unity 2019.4 to Unity 6**, at either **Api Compatibility Level** (Edit → Project Settings →
Player → Other Settings): .NET Standard 2.0 (or 2.1), or .NET 4.x.

The code uses:
- C# 7.3;
- nothing beyond .NET Framework 4.5 and .NET Standard 2.0, which both of Unity's levels contain;
- `HttpClient` with `async` and `await` for the API;
- no package and no DLL.

**At the .NET 4.x level, copy `csc.rsp` into `Assets/`.** Unity does not give a script `System.Net.Http`, where
`HttpClient` lives, at that level; the file's one line, `-r:System.Net.Http.dll`, adds it. Without it, Unity reports
error CS1069 (or CS0234) for `HttpClient`. The .NET Standard levels need nothing.

`Runtime/` does not use `UnityEngine` at all; only the examples do.

## What to copy

| From | To | What it is |
|---|---|---|
| `Runtime/` | `Assets/Backend/Runtime/` | the client, seven files; needed |
| `csc.rsp` | `Assets/csc.rsp` | at the .NET 4.x level only: gives scripts `System.Net.Http` |
| `Examples/` | `Assets/Backend/Examples/` | three example scripts and the JSON classes they use; optional |
| the backend's `ca.pem` | `Assets/Backend/ca.pem.txt` | the backend's own root certificate, renamed `.txt` so Unity takes it as a `TextAsset`; needed for `https://`, `wss://` and matches over TLS ([operations/06](../docs/operations/06-certificates.md)) |

| File in `Runtime/` | What it does |
|---|---|
| `BackendHttp.cs` | the API over `HttpClient`: `GetAsync`, `PostAsync`, `PutAsync`, `DeleteAsync`, each awaited |
| `LobbyClient.cs` | the lobby: connects, sends the token, keeps the connection alive, sends requests and receives pushes |
| `ArenaClient.cs` | a match: joins with a ticket, sends inputs, receives snapshots, resumes, leaves |
| `ArenaMessages.cs` | the match protocol's bytes, its message numbers and kick reasons |
| `WebSocketConnection.cs` | the WebSocket under `LobbyClient` (you do not call it) |
| `TrustRoot.cs` | the certificate check: accepts only certificates issued by the backend's root (you do not call it) |
| `Json.cs` | reads a single field of a JSON object: `Json.GetString(body, "token")` |

## The main thread

The network never stalls a frame, and your code always runs on Unity's main thread, where it may change the scene
and the UI:

- **The API: `await` each call** in an `async` method. Unity runs the code after an `await` on the main thread
  again. Never wait on a call with `.Result` or `.Wait()`: on the main thread that never returns.
- **The lobby and a match: call `Update()` every frame.** Each works on its own thread, and nothing reaches your
  code until you call `Update()`. Each callback then runs inside it.

```csharp
async void Start()
{
    HttpAnswer answer = await http.GetAsync("/v1/health");     // the frames go on while it waits
    Debug.Log(answer.Status);                                  // back on the main thread
}

void Update()
{
    lobby.Update();
    arena.Update();
}
```

## 1. The API: `BackendHttp`

```csharp
var http = new BackendHttp("https://203.0.113.10", rootCertificate.text);   // http:// and no root in development

async void LogIn()
{
    var credentials = new Credentials { username = "ada", password = "secret-123" };
    HttpAnswer answer = await http.PostAsync("/v1/sessions", JsonUtility.ToJson(credentials));
    if (!answer.Ok) { Debug.Log("refused: " + answer.Error); return; }     // "invalid_credentials", "banned"...
    Session session = JsonUtility.FromJson<Session>(answer.Body);
    http.Token = session.token;                                          // every later call is the player's

    HttpAnswer inventory = await http.GetAsync("/v1/inventory");
    Debug.Log(inventory.Body);
}
```

A call never throws. It always ends in one `HttpAnswer`:

| Field | Holds |
|---|---|
| `Ok` | true for a status of 200 to 299 |
| `Status` | the HTTP status; **0 when no answer came**: no network, the certificate refused, or no answer in 15 s |
| `Body` | the JSON text; read it with `JsonUtility.FromJson<T>` (the classes are in `Examples/ApiModels.cs`) |
| `Error` | null when it went well; otherwise the API's `code` (`invalid_session`, `username_taken`...), or what went wrong |

The calls most apps start with; [docs/api/01](../docs/api/01-http-api.md) has every route:

| Call | Body | Answer |
|---|---|---|
| `GET /v1/health` | | `{"status":"ok"}` |
| `POST /v1/guests` | none | `{"playerId","guestKey","displayName"}`: keep `guestKey` on the device, it is the account |
| `POST /v1/sessions` | `{"guestKey"}` or `{"username","password"}` | `{"token","playerId","expiresInSeconds"}`: about a day |
| `POST /v1/accounts` | `{"username","password","displayName"}` | 201 `{"playerId"}`, then log in |
| `GET /v1/inventory` | | `{"coins","gems","items":[...]}` |
| `GET /v1/shop` | | `{"offers":[...]}` |
| `DELETE /v1/sessions` | | 204: logged out |

`invalid_session` on any call means the token is over: log in again.

**Good to know:**
- **An object can be destroyed while its call waits.** For example, the scene changed. After an `await` in a
  `MonoBehaviour`, `if (this == null) return;` stops before touching it.
- **The certificate check for `https://` is the whole app's.** Unity 2019's `HttpClient` has no check of its own to
  set, so a `BackendHttp` made with the root sets .NET's one for the app,
  `ServicePointManager.ServerCertificateValidationCallback`. The backend's address must have a certificate from
  the root; every other site keeps the check it had. If your project sets that callback too, set yours first: one
  set after `BackendHttp` replaces it. The lobby and matches check their own connections and are not affected.

## 2. The lobby: `LobbyClient`

A player connects once logged in and stays connected between matches.

```csharp
var lobby = new LobbyClient("wss://203.0.113.10/lobby", rootCertificate.text);    // ws:// in development
lobby.OnAuthenticated = playerId => lobby.Send("match.request", null, reply => StartMatch(reply.Data));
lobby.OnPush = push => { if (push.Type == "evt.match.found") StartMatch(push.Data); };
lobby.OnClosed = why => Debug.Log("lobby closed: " + why);
lobby.Connect(session.token);
```

| Step | What happens |
|---|---|
| `Connect(token)` | connects, then sends `auth` with the token as the first message |
| `OnAuthenticated(playerId)` | the lobby took the token; send requests from here on |
| `Send(type, data, onReply)` | sends `{"t":type,"id":n,"d":data}`; the reply, `type + ".ok"` or `error`, goes to `onReply` |
| `OnPush(message)` | something the lobby sends unasked: `evt.match.found`, `evt.party.invite`, `evt.notice`... |
| every 25 s | a `ping`, sent by `Update()`; the lobby closes a connection silent for 120 s |
| `OnClosed(why)` | the connection is over, called once |
| `Close()` | closes it; `OnClosed("closed by the client")` follows |

A `LobbyMessage` has `Type`, `Data` (its `d` as JSON text, for `JsonUtility.FromJson`), `IsError`, `ErrorCode`, and
`GetString`, `GetLong` and `GetBool` for a single field.

| Request | `d` | Does |
|---|---|---|
| `ping` | | keeps the connection; `Update()` sends it |
| `match.request` | | a seat in the public arena; the reply is the grant |
| `queue.join` | `{"mode":"duel"}` | queues; `evt.match.ready`, then `evt.match.found` follow |
| `match.accept`, `match.decline` | `{"matchUid"}` | the answer to `evt.match.ready`, within its `seconds` |
| `queue.leave` | | leaves the queue |
| `party.invite`, `party.accept`, `party.leave`, `party.kick`, `party.say` | see [02 §4](../docs/api/02-lobby-websocket.md#4-what-a-client-sends) | the party |

The pushes are in [02 §6](../docs/api/02-lobby-websocket.md#6-pushes). After `OnClosed`:

- **Reconnect after a pause** (2, 4, 8... up to 30 s) and fetch over HTTP what may have been missed.
- **`invalid_session`**: log in again first.
- **After `evt.session.replaced` or `evt.session.revoked`, do not reconnect**: the player connected elsewhere, or
  the account can no longer play.

## 3. A match: `ArenaClient`

A grant from the lobby (`match.request`'s reply, `evt.match.found`) names the arena and holds a ticket for it, good for
60 seconds.

```csharp
var arena = new ArenaClient(rootCertificate.text);
arena.OnWelcome = welcome => Debug.Log("in the match, mode " + welcome.Mode);
arena.OnSnapshot = (bytes, tick) => { /* the world's changes: decode and draw */ };
arena.OnKick = reason => Debug.Log("sent away: " + reason);
arena.OnClosed = why => Debug.Log("match closed: " + why);
arena.Connect(grant.arenaHost, grant.arenaPort, grant.tls, grant.tlsName, grant.ticketId);

// ten times a second, while in the match:
arena.SendInput(ArenaMessages.MoveUp | ArenaMessages.MoveRight, aimRadians, fire ? ArenaMessages.FlagFire : 0);
```

| Step | What happens |
|---|---|
| `Connect(...)` | TCP, TLS when the grant says `tls` (checked for its `tlsName`), then `Join` with the ticket |
| `OnWelcome(welcome)` | in the match: the map's size, the mode, the player's entity, and a secret to resume with |
| `OnSnapshot(bytes, tick)` | 10 to 15 a second: the world's changes ([02-networking §4](../docs/detailed-design/02-networking.md#4-the-snapshot)) |
| `SendInput(move, aim, flags)` | send ten a second, moving or not: each acknowledges the snapshots, and the arena holds them back when none has been acknowledged for a second; the aim is in radians, 0 along +x |
| every 10 s | a `Ping`, sent by `Update()`; `OnPong(ms)` gives the round trip |
| `SetBackground(true/false)` | from `OnApplicationPause`: the arena keeps the stay while the app is away |
| `OnClosed(why)` without `Leave` | the connection was lost: `Resume()` within a minute takes up the same stay |
| `OnKick(reason)` | sent away (below); the connection closes next, and there is nothing to resume |
| `Leave()` | leaves for good; the arena closes the connection |

| Kick | Means | Then |
|---|---|---|
| 1 `KickBadTicket` | the ticket was unknown, used or expired | ask the lobby again |
| 2 `KickRoomFull` | no room had space | queue again |
| 3 `KickProtocolVersion` | the app is older than the server | ask the player to update; never retry |
| 4 `KickRateLimit` | too many messages | a bug of the app's |
| 5 `KickInternal` | the server failed | back to the lobby, try later |
| 6 `KickMatchOver` | the match is over | back to the lobby; not an error |
| 7 `KickRemoved` | an operator closed the room | back to the lobby |

**Not included:** reading a snapshot's contents, predicting the player's own movement, and drawing. The snapshot is
handed over as its bytes, with the server's tick. The full client does this in `client/Core`: `SnapshotReader`,
`ClientWorld` and `OwnTank`, with [02-networking §4](../docs/detailed-design/02-networking.md#4-the-snapshot) as the
format.

## The examples

| Script | Shows |
|---|---|
| `HttpExample` | the API, each step awaited: a guest made once and kept in `PlayerPrefs`, logged in, the shop and the inventory read; `Register` and `LogOut` |
| `LobbyExample` | the lobby: logged in, connected, a seat in the public arena asked for, a duel accepted, pushes handled, reconnecting |
| `ArenaExample` | a match from a grant: inputs ten a second, snapshots counted, pause, resume, kicks, `LeaveMatch` |
| `ApiModels` | the `[Serializable]` classes `JsonUtility` reads and writes |

To try them:
1. Add `LobbyExample` and `ArenaExample` to one object in a scene.
2. Drag the `ArenaExample` onto the `LobbyExample`'s `Arena` field.
3. Set the addresses and the root.
4. Press Play. The Console shows each step.

## Addresses

| Where | API | Lobby | Matches |
|---|---|---|---|
| The site | `https://<the site's address>` | `wss://<the site's address>/lobby` | from the grant, over TLS |
| A run by hand, through nginx ([development/03 §5](../docs/development/03-build-and-run.md#5-running-it-by-hand)) | `https://<address>`, or `http://<address>` | `wss://<address>/lobby`, or `ws://<address>/lobby` | from the grant, over TLS, its root `work/nginx/ca/ca.pem` |
| The same, nothing encrypted (`PLAIN=1`, D-101) | `http://<address>` | `ws://<address>/lobby` | from the grant, plain |

**A player's build connects encrypted only** (D-104). The examples refuse `http://`, `ws://` and a grant without TLS
when the build is not a Development Build. Keep that check in your own code.

## How it was checked

There is no Unity editor on the machine this was written on (it needs a licence), so nothing here ran inside the
editor or on a phone. What did run:

- **Compiled by Unity 2019.4.28f1's own compiler** (C# 7.3, warnings as errors):
  - `Runtime/` against .NET Standard 2.0, .NET 4.x (with `csc.rsp`'s reference; refused without it, as said above),
    .NET Framework 4.5 and .NET Standard 2.1;
  - `Examples/` against Unity's own `UnityEngine` at both of Unity 2019's levels.
- **Offline, on Unity 2019's own Mono**:
  - JSON fields read and written;
  - every match message byte for byte the same as the full client's;
  - the arena's answers read as the full client reads them;
  - frames;
  - the WebSocket's handshake and frames against RFC 6455's own examples;
  - the certificate check against certificates made by the site's own script;
  - the API: a call returns at once and gives up after 15 s;
  - the app's certificate check for every case: the backend's address, another site, a check set before it;
  - a real HTTPS call through `HttpClient` against a test server: the root's certificate taken, another root's
    refused.
- **Live**, by `client/headless-drill.sh SIMPLE=1` against a real backend, through nginx as the site runs it, over
  HTTPS, over HTTP, unencrypted, and straight to the processes:
  - the API over `http://`: health, register, log in, a token used and refused, a guest, a route that does not
    exist, log out;
  - the lobby: connect, `auth`, `ping`, an error, a seat in the public arena, kept open for 125 s by its heartbeat, a
    second connection replacing the first;
  - a match: Join, a Welcome, snapshots while sending inputs, a Pong, the app paused (snapshots stop) and back, a lost
    connection resumed, Leave;
  - with TLS, another root's certificate refused for the lobby and for a match;
  - every lobby and match callback on the thread that calls `Update()`.
- **Each rule broken on purpose**, each caught by the check written for it.

Unity 2019's Mono outside the editor has only its own old TLS, which stops at TLS 1.0. So the API's HTTPS was
checked against a test server speaking 1.0. The lobby and matches over TLS were checked on .NET 8, because nginx
speaks only 1.2 and 1.3. .NET 8's `HttpClient` ignores the app's certificate check that Unity's uses, so the live API
calls are over `http://`. In the editor and on a phone, Unity's own TLS speaks 1.2
([08 §6](../docs/detailed-design/08-client.md#6-testing)).

```bash
client-simple/check.sh                                   # compiled at every level, the offline checks
SIMPLE=1 FRONT=https client/headless-drill.sh play       # live, through nginx over TLS (also: http, plain, no FRONT)
```

`check.sh` needs Unity 2019.4.28f1 in `work/unity`, which `client/unity2019-check.sh` unpacks once.
