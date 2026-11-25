# client-simple-no-tls

[`client-simple`](../client-simple/README.md) without TLS, to put in a Unity project:

- **the API**: HTTP calls such as logging in, the shop or the inventory, through `HttpClient`, at `http://`;
- **the lobby**: a WebSocket that stays open between matches, at `ws://`;
- **a match**: a plain TCP socket to the arena, through the site's 80 by the arena's name, or straight to a backend
  run plain.

There is no certificate, no root to ship and no TLS code at all
([D-114](../docs/architecture/03-decision-log.md#d-114--a-simple-client-without-tls-client-simple-no-tls-for-a-backend-run-plain)).
The calls, the callbacks and the examples are `client-simple`'s otherwise.

## What it can reach

| Backend | API | Lobby | Matches | This client |
|---|---|---|---|---|
| The site, on its 80 ([D-115](../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) | `http://<the site's address>` | `ws://<the site's address>/lobby` | through 80 by the arena's name, `ConnectThroughSite` | **yes** |
| A run by hand, the TLS way: its 80 is the site's | `http://<address>` | `ws://<address>/lobby` | through 80 by the arena's name, `ConnectThroughSite` | **yes** |
| Run plain by hand, through nginx (`PLAIN=1`, [development/03 §5](../docs/development/03-build-and-run.md#5-running-it-by-hand)) | `http://<address>` | `ws://<address>/lobby` | from the grant, plain, on 9001, `Connect` | **yes** |
| The processes straight, without nginx (a drill's way) | `http://<address>:<platform's port>` | `ws://<address>:<gateway's port>/lobby` | from the grant, plain, `Connect` | **yes** |

**A match.** The site's grants ask for TLS (`tls` true) and name the arena (`tlsName`): its 9001 speaks TLS alone, so
`ArenaClient.ConnectThroughSite(apiUrl, grant.tlsName, grant.ticketId)` dials the API's own address, asks nginx for
the arena by that name (`GET /match/<tlsName>`), and plays once nginx has answered 101. A grant with `tls` false,
from a backend run plain, goes straight: `ArenaClient.Connect(grant.arenaHost, grant.arenaPort, grant.ticketId)`.
`ArenaExample.Play(grant, apiUrl)` picks the one the grant needs.

To run a backend plain, give every command `PLAIN=1`:

```bash
export PLAIN=1 PUBLIC_ADDRESS=192.168.1.20      # the address the app dials
./run-local.sh start                            # MySQL, j-redis, the backend, then nginx plain on 80 and 9001
```

**Everything crosses the network in clear**: passwords, session tokens and match tickets, readable and usable by
anyone on the way. Use it on a network you trust.

**Any build**, a build for players too: the owner lets a players' build connect without TLS (Q-64,
[D-115](../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).

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
error CS1069 (or CS0234) for `HttpClient`. The .NET Standard levels need nothing, and the file does no harm there.
If you put `Runtime/` in an assembly definition of its own, put `csc.rsp` beside the `.asmdef` instead. The lobby and
matches need nothing of the kind: they use `System.dll` alone.

`Runtime/` does not use `UnityEngine` at all; only the examples do.

## What to copy

| From | To | What it is |
|---|---|---|
| `Runtime/` | `Assets/Backend/Runtime/` | the client, six files; needed |
| `csc.rsp` | `Assets/csc.rsp` | for the .NET 4.x level: gives scripts `System.Net.Http`; harmless at .NET Standard |
| `Examples/` | `Assets/Backend/Examples/` | three example scripts and the JSON classes they use; optional |

| File in `Runtime/` | What it does |
|---|---|
| `BackendHttp.cs` | the API over `HttpClient`: `GetAsync`, `PostAsync`, `PutAsync`, `DeleteAsync`, each awaited; `http://` only |
| `LobbyClient.cs` | the lobby: connects, sends the token, keeps the connection alive, sends requests and receives pushes; `ws://` only |
| `ArenaClient.cs` | a match: joins with a ticket, sends inputs, receives snapshots, resumes, leaves; plain TCP |
| `ArenaMessages.cs` | the match protocol's bytes, its message numbers and kick reasons (`client-simple`'s, byte for byte) |
| `WebSocketConnection.cs` | the WebSocket under `LobbyClient` (you do not call it) |
| `Json.cs` | reads a single field of a JSON object: `Json.GetString(body, "token")` (`client-simple`'s, byte for byte) |

Use this folder or `client-simple`'s, not both: they have the same namespace and class names.

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
var http = new BackendHttp("http://192.168.1.20");             // an https:// address is refused: no TLS here

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
| `Status` | the HTTP status; **0 when no answer came**: no network, or no answer in 15 s |
| `Body` | the JSON text; read it with `JsonUtility.FromJson<T>` (the classes are in `Examples/ApiModels.cs`) |
| `Error` | null when it went well; otherwise the API's `code` (`invalid_session`, `username_taken`...), or what went wrong |

The calls most apps start with are in [client-simple's README](../client-simple/README.md#1-the-api-backendhttp), and
[docs/api/01](../docs/api/01-http-api.md) has every route. `invalid_session` on any call means the token is over:
log in again. An object can be destroyed while its call waits (the scene changed): after an `await` in a
`MonoBehaviour`, `if (this == null) return;` stops before touching it.

## 2. The lobby: `LobbyClient`

```csharp
var lobby = new LobbyClient("ws://192.168.1.20/lobby");         // a wss:// address is refused: no TLS here
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

The requests, the pushes and what to do after `OnClosed` are in
[client-simple's README](../client-simple/README.md#2-the-lobby-lobbyclient), the same here.

## 3. A match: `ArenaClient`

A grant from the lobby (`match.request`'s reply, `evt.match.found`) names the arena and holds a ticket for it, good for
60 seconds. The site's grants have `tls` true and a `tlsName`: take them through the site's 80 with
`ConnectThroughSite`. A backend run plain gives grants with `tls` false: `Connect` goes straight.

```csharp
var arena = new ArenaClient();
arena.OnWelcome = welcome => Debug.Log("in the match, mode " + welcome.Mode);
arena.OnSnapshot = (bytes, tick) => { /* the world's changes: decode and draw */ };
arena.OnKick = reason => Debug.Log("sent away: " + reason);
arena.OnClosed = why => Debug.Log("match closed: " + why);
if (grant.tls) arena.ConnectThroughSite(apiUrl, grant.tlsName, grant.ticketId);   // the site's, through its 80
else arena.Connect(grant.arenaHost, grant.arenaPort, grant.ticketId);            // a backend run plain

// ten times a second, while in the match:
arena.SendInput(ArenaMessages.MoveUp | ArenaMessages.MoveRight, aimRadians, fire ? ArenaMessages.FlagFire : 0);
```

| Step | What happens |
|---|---|
| `ConnectThroughSite(apiUrl, tlsName, ticketId)` | TCP to the API's address, `GET /match/<tlsName>` to nginx, its answer read: 101, then `Join` with the ticket. Another answer (a 404 for a name nginx has not) ends it, `OnClosed` saying which |
| `Connect(host, port, ticketId)` | TCP, then `Join` with the ticket |
| `OnWelcome(welcome)` | in the match: the map's size, the mode, the player's entity, and a secret to resume with |
| `OnSnapshot(bytes, tick)` | 10 to 15 a second: the world's changes ([02-networking §4](../docs/detailed-design/02-networking.md#4-the-snapshot)) |
| `SendInput(move, aim, flags)` | send ten a second, moving or not: each acknowledges the snapshots, and the arena holds them back when none has been acknowledged for a second; the aim is in radians, 0 along +x |
| every 10 s | a `Ping`, sent by `Update()`; `OnPong(ms)` gives the round trip |
| `SetBackground(true/false)` | from `OnApplicationPause`: the arena keeps the stay while the app is away |
| `OnClosed(why)` without `Leave` | the connection was lost: `Resume()` within a minute takes up the same stay |
| `OnKick(reason)` | sent away (the reasons are in [client-simple's README](../client-simple/README.md#3-a-match-arenaclient)); the connection closes next, and there is nothing to resume |
| `Leave()` | leaves for good; the arena closes the connection |

**Not included,** as in `client-simple`: reading a snapshot's contents, predicting the player's own movement, and
drawing. The snapshot is handed over as its bytes, with the server's tick.

## The examples

| Script | Shows |
|---|---|
| `HttpExample` | the API, each step awaited: a guest made once and kept in `PlayerPrefs`, logged in, the shop and the inventory read; `Register` and `LogOut` |
| `LobbyExample` | the lobby: logged in, connected, a seat in the public arena asked for, a duel accepted, pushes handled, reconnecting |
| `ArenaExample` | a match from a grant, through the site's 80 or straight as the grant needs: inputs ten a second, snapshots counted, pause, resume, kicks, `LeaveMatch` |
| `ApiModels` | the `[Serializable]` classes `JsonUtility` reads and writes |

To try them:
1. Add `LobbyExample` and `ArenaExample` to one object in a scene.
2. Drag the `ArenaExample` onto the `LobbyExample`'s `Arena` field.
3. Set the two addresses: the site's in clear (`http://`, `ws://`), or a backend run plain's.
4. Press Play. The Console shows each step.

## When TLS becomes possible

Copy `client-simple/Runtime/` in place of this `Runtime/`. The names are the same; what changes:

| Here | In `client-simple` |
|---|---|
| `new BackendHttp(url)` | `new BackendHttp(url, rootCertificate.text)`, `https://` |
| `new LobbyClient(url)` | `new LobbyClient(url, rootCertificate.text)`, `wss://` |
| `new ArenaClient()` | `new ArenaClient(rootCertificate.text)` |
| `arena.Connect(host, port, ticketId)`, `arena.ConnectThroughSite(apiUrl, tlsName, ticketId)` | `arena.Connect(host, port, grant.tls, grant.tlsName, ticketId)`, on 9001 with TLS |

The root is the backend's `ca.pem`, renamed `ca.pem.txt` for Unity
([client-simple's README](../client-simple/README.md#what-to-copy)).

## How it was checked

The same check as `client-simple`'s, over this folder (`check.sh`, which runs `../client-simple/check.sh` with
`NO_TLS=1`). Nothing ran inside the Unity editor or on a phone.

- **Compiled by Unity 2019.4.28f1's own compiler** (C# 7.3, warnings as errors):
  - `Runtime/` against .NET Standard 2.0, .NET 4.x (with `csc.rsp`'s reference; refused without it), .NET Framework
    4.5 and .NET Standard 2.1;
  - `Examples/` against Unity's own `UnityEngine` at both of Unity 2019's levels;
  - `ArenaMessages.cs` and `Json.cs` compared with `client-simple`'s, byte for byte, and `Runtime/` searched for any
    TLS type, of which there is none.
- **Offline, on Unity 2019's own Mono:** `client-simple`'s checks but its TLS ones: JSON, every match message byte
  for byte the full client's, the arena's answers, frames, the WebSocket's parts, the API's calls (back at once,
  given up after 15 s), and an `https://` or `wss://` address refused; a match through the site's 80 against a
  stand-in for nginx: the request exact, `Join` once the 101 is read, nginx's 404 said in `OnClosed`, an address not
  `http://` and a grant without a name refused. 52 checks.
- **Live**, by `client/headless-drill.sh SIMPLE=1` against a real backend, on Unity 2019's Mono: through nginx's 80
  in its TLS way, the site's, the match through `/match/` by the grant's name; through nginx run plain; and straight
  to the processes: the API, the lobby (kept open 125 s by its heartbeat, replaced by a second connection), a match
  (snapshots while sending inputs, a Pong, the app paused and back, a lost connection resumed, Leave), every lobby
  and match callback on the thread that calls `Update()`.

```bash
client-simple-no-tls/check.sh                              # compiled at every level, the offline checks
SIMPLE=1 FRONT=https client/headless-drill.sh play         # live through nginx's 80, as the site's (also: http, plain, no FRONT)
```

`check.sh` needs Unity 2019.4.28f1 in `work/unity`, which `client/unity2019-check.sh` unpacks once.
