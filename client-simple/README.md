# client-simple

A small C# client for the backend, to put into a Unity project. It is for a UI team that wants to read the client in
about an hour and copy it into its own project. It does not replace [`client/`](../client/README.md), the full
client with prediction, the world and drawing
([D-111](../docs/architecture/03-decision-log.md#d-111--a-simple-client-beside-the-core-client-simple-three-small-clients-on-net-standard-20-examples-in-unitys-terms),
[D-112](../docs/architecture/03-decision-log.md#d-112--the-simple-clients-api-over-httpclient-awaited-the-backends-root-as-the-apps-certificate-check)).
It has three parts:

- **the API**: HTTP calls such as logging in, the shop or the inventory, through `HttpClient`;
- **the lobby**: a WebSocket that stays open between matches;
- **a match**: a TCP socket to the arena.

This page says which Unity versions it works with, what to copy where, how threads work, how to use each of the
three parts, how to try the examples, which addresses to use, and how it was checked. **TLS** (Transport Layer
Security) is the encryption that `https://` and `wss://` use; other terms are in the
[glossary](../docs/glossary.md).

**In short:**

1. Copy `Runtime/` into your project. At the .NET 4.x level, also copy `csc.rsp`. For TLS, also copy the backend's
   `ca.pem`, renamed `ca.pem.txt`.
2. Set two addresses: the API's and the lobby's.
3. `await` the API's calls.
4. Call the lobby's and the match's `Update()` every frame.

All the classes are in the namespace `Backend.Client.Simple`; the examples are in `Backend.Client.Simple.Examples`.

This client always takes a match from the site over TLS. Where TLS cannot be used at all, use
[`client-simple-no-tls/`](../client-simple-no-tls/README.md) instead: the same calls with no certificate at all, and
a site's match through port 80 (D-114, D-115).

## Which Unity

It works in any version from **Unity 2019.4 to Unity 6**, at either **Api Compatibility Level** (Edit → Project
Settings → Player → Other Settings): .NET Standard 2.0 (or 2.1), or .NET 4.x.

The code uses:

- C# 7.3;
- nothing beyond .NET Framework 4.5 and .NET Standard 2.0, which both of Unity's levels contain;
- `HttpClient` with `async` and `await` for the API;
- no package and no DLL.

**At the .NET 4.x level, copy `csc.rsp` into `Assets/`.** At that level Unity does not give a script
`System.Net.Http`, the assembly where `HttpClient` is. The file's one line, `-r:System.Net.Http.dll`, adds it.
Without it, Unity reports error CS1069 (or CS0234) for `HttpClient`. The .NET Standard levels need nothing, and the
file does no harm there, so it can stay if you change levels. If you put `Runtime/` in an assembly definition of its
own, put `csc.rsp` beside the `.asmdef` file instead: Unity 2019.4 reads one there, for that assembly alone (D-113).

`Runtime/` does not use `UnityEngine` at all; only the examples do.

## What to copy

1. Copy `Runtime/` to `Assets/Backend/Runtime/`. This is needed.
2. At the .NET 4.x level, copy `csc.rsp` to `Assets/csc.rsp`.
3. If you want the examples, copy `Examples/` to `Assets/Backend/Examples/`.
4. For `https://`, `wss://` or a match over TLS, copy the backend's root certificate, `ca.pem`, to
   `Assets/Backend/ca.pem.txt`. The `.txt` ending makes Unity import it as a `TextAsset`. A match from the site always
   uses TLS, so the app needs the root even when the API and the lobby are in clear.

| From | To | What it is |
|---|---|---|
| `Runtime/` | `Assets/Backend/Runtime/` | the client, seven files; needed |
| `csc.rsp` | `Assets/csc.rsp` | for the .NET 4.x level: gives scripts `System.Net.Http`; harmless at .NET Standard |
| `Examples/` | `Assets/Backend/Examples/` | three example scripts and the JSON classes they use; optional |
| the backend's `ca.pem` | `Assets/Backend/ca.pem.txt` | the backend's own root certificate, renamed `.txt` so that Unity takes it as a `TextAsset`; needed for `https://`, `wss://` and matches over TLS ([operations/06](../docs/operations/06-certificates.md)) |

| File in `Runtime/` | What it does |
|---|---|
| `BackendHttp.cs` | the API over `HttpClient`: `GetAsync`, `PostAsync`, `PutAsync`, `DeleteAsync`, each awaited |
| `LobbyClient.cs` | the lobby: connects, sends the token, keeps the connection alive, sends requests and receives pushes |
| `ArenaClient.cs` | a match: joins with a ticket, sends inputs, receives snapshots, resumes, leaves |
| `ArenaMessages.cs` | the match protocol's bytes, its message numbers and kick reasons |
| `WebSocketConnection.cs` | the WebSocket under `LobbyClient` (you do not call it) |
| `TrustRoot.cs` | the certificate check: it accepts only certificates issued by the backend's root (you do not call it) |
| `Json.cs` | reads one field of a JSON object: `Json.GetString(body, "token")` |

The JSON classes that the examples use (`Credentials`, `Session`, `Grant` and the others) are in
`Examples/ApiModels.cs`. If you do not copy `Examples/`, write your own `[Serializable]` classes like them.

## The main thread

The network never stalls a frame, and your code always runs on Unity's main thread, where it may change the scene
and the UI:

- **The API: `await` each call** in an `async` method. Unity runs the code after an `await` on the main thread again.
  Never wait for a call with `.Result` or `.Wait()`: on the main thread, that never returns.
- **The lobby and a match: call `Update()` every frame.** Each works on a thread of its own, and nothing reaches your
  code until you call `Update()`. Each callback then runs inside `Update()`, on the main thread.

```csharp
using Backend.Client.Simple;
using UnityEngine;

public class MainThreadExample : MonoBehaviour
{
    private BackendHttp http;
    private LobbyClient lobby;

    private async void Start()
    {
        http = new BackendHttp("http://127.0.0.1");
        lobby = new LobbyClient("ws://127.0.0.1/lobby");
        lobby.OnClosed = why => Debug.Log("lobby closed: " + why);   // runs inside lobby.Update()

        HttpAnswer answer = await http.GetAsync("/v1/health");       // the frames go on while it waits
        Debug.Log(answer.Status);                                    // back on the main thread
    }

    private void Update()
    {
        lobby?.Update();        // the lobby's callbacks run in here; call an ArenaClient's Update() the same way
    }
}
```

## 1. The API: `BackendHttp`

Make one `BackendHttp` with the site's address and the root, log in, and set `Token`. Every call after that is made
as the player.

```csharp
using Backend.Client.Simple;
using Backend.Client.Simple.Examples;     // Credentials, Session: Examples/ApiModels.cs
using UnityEngine;

public class LogInExample : MonoBehaviour
{
    public TextAsset rootCertificate;     // ca.pem.txt
    private BackendHttp http;

    private void Awake()
    {
        http = new BackendHttp("https://203.0.113.10", rootCertificate.text);   // in clear: "http://..." and no root
    }

    public async void LogIn()
    {
        var credentials = new Credentials { username = "ada", password = "secret-123" };
        HttpAnswer answer = await http.PostAsync("/v1/sessions", JsonUtility.ToJson(credentials));
        if (this == null) return;                                            // destroyed while it waited
        if (!answer.Ok) { Debug.Log("refused: " + answer.Error); return; }   // "invalid_credentials", "banned"...
        Session session = JsonUtility.FromJson<Session>(answer.Body);
        http.Token = session.token;                                          // every later call is the player's

        HttpAnswer inventory = await http.GetAsync("/v1/inventory");
        Debug.Log(inventory.Body);
    }
}
```

| Member | What it does |
|---|---|
| `new BackendHttp(baseUrl, caPem)` | `baseUrl` is the site's address, as `https://203.0.113.10`. `caPem` is the text of `ca.pem`, for an `https://` address; leave it out (null) for `http://` |
| `Token` | the session's token. Once set, every call sends it as `Authorization: Bearer`; null sends none |
| `GetAsync(path)`, `PostAsync(path, json)`, `PutAsync(path, json)`, `DeleteAsync(path, json = null)` | one call each. `json` is the body, or null for none (`DELETE /v1/accounts` takes a body) |
| `SendAsync(method, path, json)` | any other HTTP method |

A call never throws. It always ends in one `HttpAnswer`:

| Field | Holds |
|---|---|
| `Ok` | true for a status of 200 to 299 |
| `Status` | the HTTP status; **0 when no answer came**: no network, the certificate refused, or no answer in 15 s |
| `Body` | the JSON text, or "" when there is none (a 204). Read it with `JsonUtility.FromJson<T>` (the classes are in `Examples/ApiModels.cs`) |
| `Error` | null when it went well. Otherwise the API's `code` (`invalid_session`, `username_taken`...); `HTTP <status>`, such as `HTTP 429`, for an answer without a code (nginx's own 429 is not JSON); or what went wrong when no answer came |

The calls most apps start with are below; [docs/api/01](../docs/api/01-http-api.md) has every route:

| Call | Body | Answer |
|---|---|---|
| `GET /v1/health` | | `{"status":"ok"}` |
| `POST /v1/guests` | none | `{"playerId","guestKey","displayName"}`: keep `guestKey` on the device, it is the account |
| `POST /v1/sessions` | `{"guestKey"}` or `{"username","password"}` | `{"token","playerId","expiresInSeconds"}`: about a day |
| `POST /v1/accounts` | `{"username","password","displayName"}` | 201 `{"playerId"}`, then log in |
| `GET /v1/inventory` | | `{"coins","gems","items":[...]}` |
| `GET /v1/shop` | | `{"offers":[...]}` |
| `DELETE /v1/sessions` | | 204: logged out |

`invalid_session` on any call means that the token is no longer valid: log in again.

**Good to know:**

- **An object can be destroyed while its call waits**, for example when the scene changes. After an `await` in a
  `MonoBehaviour`, `if (this == null) return;` stops before the code touches it.
- **The certificate check for `https://` is the whole app's.** Unity 2019's `HttpClient` has no check of its own that
  you can set. So a `BackendHttp` made with the root sets .NET's check for the whole app,
  `ServicePointManager.ServerCertificateValidationCallback`:
  - a request to the backend's address must have a certificate from the root;
  - a request to any other site keeps the check it had before.
  - If your project sets that callback too, set yours first: a callback set after `BackendHttp` replaces it.
  - The lobby and matches check their own connections, and are not affected.

## 2. The lobby: `LobbyClient`

A player connects to the lobby once logged in, and stays connected between matches.

```csharp
using Backend.Client.Simple;
using UnityEngine;

public class LobbyConnectExample : MonoBehaviour
{
    public TextAsset rootCertificate;     // ca.pem.txt
    private LobbyClient lobby;

    // token: the session's token from POST /v1/sessions (section 1)
    public void ConnectLobby(string token)
    {
        lobby = new LobbyClient("wss://203.0.113.10/lobby", rootCertificate.text);   // in clear: "ws://..." and no root
        lobby.OnAuthenticated = playerId =>
            lobby.Send("match.request", null, reply => { if (!reply.IsError) StartMatch(reply.Data); });
        lobby.OnPush = push => { if (push.Type == "evt.match.found") StartMatch(push.Data); };
        lobby.OnClosed = why => Debug.Log("lobby closed: " + why);
        lobby.Connect(token);
    }

    private void Update() => lobby?.Update();          // every callback above runs in here

    private void StartMatch(string grantJson) { /* section 3 */ }
}
```

| Step | What happens |
|---|---|
| `new LobbyClient(url, caPem)` | `url` is the lobby's address, as `wss://203.0.113.10/lobby`. `caPem` is the text of `ca.pem`, for `wss://`; leave it out (null) for `ws://` |
| `Connect(token)` | connects on a thread of its own, then sends `auth` with the token as the first message |
| `OnAuthenticated(playerId)` | the lobby took the token; send requests from here on |
| `Send(type, data, onReply)` | sends `{"t":type,"id":n,"d":data}`; the reply (`type + ".ok"`, or `error`) goes to `onReply`. `data` is JSON text or null. It returns false, and sends nothing, when the lobby is not connected |
| `OnPush(message)` | something the lobby sends without being asked: `evt.match.found`, `evt.party.invite`, `evt.notice`... |
| every 25 s | `Update()` sends a `ping` (`HeartbeatSeconds`); the lobby closes a connection that is silent for 120 s |
| `OnClosed(why)` | the connection is over; called once. When the lobby refused the token, `why` starts with `invalid_session` |
| `Close()` | closes it; `OnClosed("closed by the client")` follows in the next `Update()` |

A `LobbyMessage` has `Type`, `Data` (its `d` as JSON text, for `JsonUtility.FromJson`), `IsError`, `ErrorCode`, and
`GetString`, `GetLong` and `GetBool` to read one field. `IsAuthenticated` says whether the lobby has taken the token.

**Why not .NET's `ClientWebSocket`:** on Unity 2019 it cannot connect to the backend's `wss://`.

- It has no certificate check of its own that you can set, at either API level.
- On Unity 2019's runtime it ignores the app's check that `HttpClient` uses. It refuses the backend's certificate,
  because no public authority issued it (this was tried here).

So the lobby has a small WebSocket of its own, `WebSocketConnection`, over `TcpClient` and `SslStream`. Its check on
each connection does work, as a match's does. Both use only `System.dll`, which every API level references, so they
need no `csc.rsp`.

| Request | `d` | What it does |
|---|---|---|
| `ping` | | keeps the connection; `Update()` sends it |
| `match.request` | | a seat in the public arena; the reply is the grant |
| `queue.join` | `{"mode":"duel"}` | queues; `evt.match.ready`, then `evt.match.found`, follow |
| `match.accept`, `match.decline` | `{"matchUid"}` | the answer to `evt.match.ready`, within its `seconds` |
| `queue.leave` | | leaves the queue |
| `party.invite`, `party.accept`, `party.leave`, `party.kick`, `party.say` | see [02 §4](../docs/api/02-lobby-websocket.md#4-what-a-client-sends) | the party |

The pushes are in [02 §6](../docs/api/02-lobby-websocket.md#6-pushes). After `OnClosed`:

- **Reconnect after a pause** (2, 4, 8... up to 30 s), and fetch over HTTP what may have been missed.
- **After `invalid_session`**: log in again first.
- **After `evt.session.replaced` or `evt.session.revoked`, do not reconnect**: the player connected elsewhere, or the
  account can no longer play.

## 3. A match: `ArenaClient`

A **grant** from the lobby (the reply to `match.request`, or `evt.match.found`) names the arena and holds a
**ticket** for it, good for 60 seconds. `ArenaClient` connects with it, joins, and then sends inputs and receives
snapshots.

```csharp
using Backend.Client.Simple;
using Backend.Client.Simple.Examples;     // Grant: Examples/ApiModels.cs
using UnityEngine;

public class MatchExample : MonoBehaviour
{
    public TextAsset rootCertificate;     // ca.pem.txt: a match from the site always uses TLS
    private ArenaClient arena;
    private float nextInputAt;

    public void StartMatch(string grantJson)
    {
        Grant grant = JsonUtility.FromJson<Grant>(grantJson);
        arena = new ArenaClient(rootCertificate.text);
        arena.OnWelcome = welcome => Debug.Log("in the match, mode " + welcome.Mode);
        arena.OnSnapshot = (bytes, tick) => { /* the world's changes: decode and draw */ };
        arena.OnKick = reason => Debug.Log("sent away: " + reason);
        arena.OnClosed = why => Debug.Log("match closed: " + why);
        arena.Connect(grant.arenaHost, grant.arenaPort, grant.tls, grant.tlsName, grant.ticketId);
    }

    private void Update()
    {
        if (arena == null) return;
        arena.Update();                                              // every callback above runs in here
        if (arena.Welcome != null && Time.unscaledTime >= nextInputAt)
        {
            nextInputAt = Time.unscaledTime + 0.1f;                  // ten times a second, while in the match
            arena.SendInput(ArenaMessages.MoveUp | ArenaMessages.MoveRight, 0f, 0);
        }
    }

    private void OnApplicationPause(bool paused) => arena?.SetBackground(paused);
}
```

| Step | What happens |
|---|---|
| `new ArenaClient(caPem)` | `caPem` is the text of `ca.pem`, for a match over TLS; leave it out (null) for a grant without TLS |
| `Connect(host, port, tls, tlsName, ticketId)` | TCP to the grant's `arenaHost` and `arenaPort` (given up after 10 s); TLS when the grant says `tls`, checked for its `tlsName`; then `Join` with the ticket |
| `OnWelcome(welcome)` | in the match: the map's size, the mode, the player's entity, and a secret to resume with |
| `OnSnapshot(bytes, tick)` | 10 to 15 a second: the world's changes ([02-networking §4](../docs/detailed-design/02-networking.md#4-the-snapshot)) |
| `SendInput(move, aim, flags)` | send it ten times a second, moving or not. Each input acknowledges the snapshots, and the arena holds them back when none has been acknowledged for a second. The aim is in radians, 0 along +x |
| every 10 s | `Update()` sends a `Ping`; `OnPong(ms)` gives the round trip |
| `SetBackground(true/false)` | call it from `OnApplicationPause`: the arena keeps the stay while the app is away |
| `OnClosed(why)` without `Leave` | the connection was lost (or 30 s passed with nothing received): `Resume()` within a minute takes up the same stay |
| `OnKick(reason)` | the player was sent away (see the table below); the connection closes next, and there is nothing to resume |
| `Leave()` | leaves for good; the arena closes the connection |

Other members: `Welcome` (the last Welcome, null before the first), `ServerTick`, `CanResume` (joined, and not left
or kicked), `Profile` (`ArenaMessages.ProfileMobile` by default, or `ProfileHigh`, `ProfileSaver`), `UpgradeStat`,
`ChooseClass`, `Respawn`, `SayPhrase` (at most one every 2 s), and `Close()` (drops the connection without `Leave`:
the stay waits a minute for a `Resume`).

| Kick | Means | Then |
|---|---|---|
| 1 `KickBadTicket` | the ticket was unknown, used or expired | ask the lobby again |
| 2 `KickRoomFull` | no room had space | queue again |
| 3 `KickProtocolVersion` | the app is older than the server | ask the player to update; never retry |
| 4 `KickRateLimit` | too many messages | a bug in the app: log it, do not loop |
| 5 `KickInternal` | the server failed | back to the lobby; try later |
| 6 `KickMatchOver` | the match is over | back to the lobby; not an error |
| 7 `KickRemoved` | an operator closed the room, or took the player out | back to the lobby |

**Not included:** reading a snapshot's contents, predicting the player's own movement, and drawing. The snapshot is
handed over as its bytes, with the server's tick. The full client does these in `client/Core`: `SnapshotDecoder`,
`ClientWorld` and `OwnTank`, with [02-networking §4](../docs/detailed-design/02-networking.md#4-the-snapshot) as the
format.

## The examples

| Script | Shows |
|---|---|
| `HttpExample` | the API, each step awaited: a guest made once and kept in `PlayerPrefs`, logged in, the shop and the inventory read; `Register` and `LogOut` |
| `LobbyExample` | the lobby: logged in, connected, a seat in the public arena asked for, a duel accepted, pushes handled, reconnecting |
| `ArenaExample` | a match from a grant: inputs ten a second, snapshots counted, pause, resume, kicks, `LeaveMatch` |
| `ApiModels` | the `[Serializable]` classes that `JsonUtility` reads and writes |

They connect the way they are set up, in any build (D-115).

To try them:

1. Add `LobbyExample` and `ArenaExample` to one object in a scene.
2. Drag the `ArenaExample` onto the `LobbyExample`'s **Arena** field.
3. Set the addresses on `LobbyExample`: **Api Url** and **Lobby Url**.
4. Set **Root Certificate** to `ca.pem.txt` on `LobbyExample` (for `https://` and `wss://`) and on `ArenaExample`
   (for a match over TLS).
5. Press Play. The Console shows each step.

`HttpExample` works alone: add it to an object, set **Api Url** (and **Root Certificate** for `https://`), and press
Play.

## Addresses

| Where | API | Lobby | Matches |
|---|---|---|---|
| The site | `https://<the site's address>`, or `http://<the site's address>` in clear (D-115) | `wss://<the site's address>/lobby`, or `ws://…/lobby` in clear | from the grant, over TLS on 9001 |
| A run by hand, through nginx ([development/03 §5](../docs/development/03-build-and-run.md#5-running-it-by-hand)) | `https://<address>`, or `http://<address>` | `wss://<address>/lobby`, or `ws://<address>/lobby` | from the grant, over TLS; its root is `work/nginx/ca/ca.pem` |
| The same, nothing encrypted (`PLAIN=1`, D-101) | `http://<address>` | `ws://<address>/lobby` | from the grant, plain |
| The processes straight, without nginx (a drill's way) | `http://<address>:<platform's port>` | `ws://<address>:<gateway's port>/lobby` | from the grant: plain, or TLS when the arena has its own |

**Any build connects the way it is set up**
([D-115](../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)):
with `https://`, `wss://` and the root, or with `http://` and `ws://`, a build for players too. Without TLS,
passwords, session tokens and match tickets cross the network in clear.

## How it was checked

There is no Unity editor on the machine where this was written (it needs a licence), so nothing here ran inside the
editor or on a phone. This is what did run:

- **Compiled by Unity 2019.4.28f1's own compiler** (C# 7.3, warnings as errors):
  - `Runtime/` against .NET Standard 2.0, .NET 4.x (with `csc.rsp`'s reference; refused without it, as said above),
    .NET Framework 4.5 and .NET Standard 2.1;
  - `Examples/` against Unity's own `UnityEngine`, at both of Unity 2019's levels.
- **Offline, on Unity 2019's own Mono** (70 checks):
  - JSON fields read and written;
  - every match message byte for byte the same as the full client's;
  - the arena's answers read as the full client reads them;
  - frames;
  - the WebSocket's handshake and frames, against RFC 6455's own examples;
  - the certificate check, against certificates made by the site's own script;
  - the API: a call returns at once, and gives up after 15 s;
  - the app's certificate check in every case: the backend's address, another site, a check set before it;
  - a real HTTPS call through `HttpClient` against a test server: the root's certificate taken, another root's
    refused;
  - the lobby's `wss://` and a match's TLS against the same test server: the root's certificate taken, another root's
    refused, and, for a match, a certificate without the grant's TLS name refused.
- **Live**, by `SIMPLE=1 client/headless-drill.sh play` against a real backend: through nginx as the site runs it
  (`FRONT=https`, `FRONT=http`), through nginx with nothing encrypted (`FRONT=plain`), and straight to the processes
  (no `FRONT`):
  - the API over `http://`: health, register (and the same name refused), log in, a token used and refused, a
    display name changed, a guest, a route that does not exist, log out;
  - the lobby: connect, `auth`, `ping`, an error, a seat in the public arena, kept open for 125 s by its heartbeat, a
    second connection replacing the first;
  - a match: `Join`, a Welcome, snapshots while sending inputs, a Pong, the app paused (snapshots stop) and back, a
    lost connection resumed, `Leave`;
  - with TLS, another root's certificate refused for the lobby and for a match;
  - every lobby and match callback on the thread that calls `Update()`.
- **Each rule broken on purpose**, and each break caught by the check written for it.

**Which TLS was checked where:**

- Unity 2019's Mono, outside the editor, has only its own old TLS, which stops at TLS 1.0. So on it, the API's
  HTTPS, the lobby's `wss://` and a match's TLS were checked against a test server that speaks TLS 1.0.
- nginx speaks only TLS 1.2 and 1.3, so through nginx the lobby and matches over TLS ran on .NET 8.
- .NET 8's `HttpClient` ignores the app's certificate check that Unity's uses, so the live API calls are over
  `http://`.
- In the editor and on a phone, Unity's own TLS speaks TLS 1.2
  ([08 §6](../docs/detailed-design/08-client.md#6-testing)).

```bash
client-simple/check.sh                                   # compiled at every level, the offline checks
SIMPLE=1 FRONT=https client/headless-drill.sh play       # live, through nginx over TLS (also: http, plain, no FRONT)
```

`check.sh` needs Unity 2019.4.28f1 in `work/unity`, which `client/unity2019-check.sh` unpacks once.
