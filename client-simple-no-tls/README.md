# client-simple-no-tls

[`client-simple`](../client-simple/README.md) without TLS (Transport Layer Security, the encryption that `https://`
and `wss://` use), to put into a Unity project. It is for a UI team whose app must connect where TLS cannot be used.
It has three parts:

- **the API**: HTTP calls such as logging in, the shop or the inventory, through `HttpClient`, at `http://`;
- **the lobby**: a WebSocket that stays open between matches, at `ws://`;
- **a match**: a plain TCP socket to the arena, either through the site's port 80 by the arena's name, or straight to
  a backend run plain.

There is no certificate, no root to ship, and no TLS code at all
([D-114](../docs/architecture/03-decision-log.md#d-114--a-simple-client-without-tls-client-simple-no-tls-for-a-backend-run-plain)).
Otherwise the calls, the callbacks and the examples are `client-simple`'s. This page says what it can reach, which
Unity versions it works with, what to copy, how to use each part, how to move to TLS later, and how it was checked.

All the classes are in the namespace `Backend.Client.Simple`, as in `client-simple`; the examples are in
`Backend.Client.Simple.Examples`.

## What it can reach

| Backend | API | Lobby | Matches | This client |
|---|---|---|---|---|
| The site, on its 80 ([D-115](../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) | `http://<the site's address>` | `ws://<the site's address>/lobby` | through 80 by the arena's name, `ConnectThroughSite` | **yes** |
| A run by hand, the TLS way (`dev-nginx.sh` without `PLAIN=1`): its 80 is the site's | `http://<address>` | `ws://<address>/lobby` | through 80 by the arena's name, `ConnectThroughSite` | **yes** |
| Run plain by hand, through nginx (`PLAIN=1`, [development/03 §5](../docs/development/03-build-and-run.md#5-running-it-by-hand)) | `http://<address>` | `ws://<address>/lobby` | from the grant, plain, on 9001, `Connect` | **yes** |
| The processes straight, without nginx (a drill's way) | `http://<address>:<platform's port>` | `ws://<address>:<gateway's port>/lobby` | from the grant, plain, `Connect` | **yes** |

**How a match connects.** A **grant** is what the lobby hands a player to go to a match: the arena's address, a
ticket, `tls`, and `tlsName`, the arena's name.

- **A grant from the site** has `tls` true and names the arena in `tlsName`. The site's 9001 speaks TLS alone, so this
  client cannot use it. Instead, `ArenaClient.ConnectThroughSite(apiUrl, grant.tlsName, grant.ticketId)` dials the
  API's own address. It asks nginx for the arena by that name (`GET /match/<tlsName>`), and plays once nginx has
  answered 101.
- **A grant from a backend run plain** has `tls` false. It goes straight to the arena:
  `ArenaClient.Connect(grant.arenaHost, grant.arenaPort, grant.ticketId)`.
- `ArenaExample.Play(grant, apiUrl)` picks the call that the grant needs.

**To run a backend plain**, give every command `PLAIN=1`:

```bash
export PLAIN=1 PUBLIC_ADDRESS=192.168.1.20      # the address the app dials
./run-local.sh start                            # MySQL, j-redis, the backend, then nginx plain on 80 and 9001
```

**Everything crosses the network in clear**: passwords, session tokens and match tickets. Anyone on the way can read
them and use them. Use it on a network you trust.

**Any build may use it**, a build for players too: the owner lets a players' build connect without TLS (Q-64,
[D-115](../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).

## Which Unity

It works in any version from **Unity 2019.4 to Unity 6**, at the **.NET 4.x** Api Compatibility Level (Edit →
Project Settings → Player → Other Settings; from Unity 2021.2 the level is called .NET Framework). It targets .NET
Framework 4.5, and is checked at the .NET 4.x level alone
([D-116](../docs/architecture/03-decision-log.md#d-116--the-client-on-net-framework-45-alone-built-and-run-here-without-net-8-and-driven-in-clear)):
the .NET Standard 2.0 and 2.1 levels are no longer checked, so use .NET 4.x.

The code uses:

- C# 7.3;
- nothing beyond .NET Framework 4.5, which Unity's .NET 4.x level contains;
- `HttpClient` with `async` and `await` for the API;
- no package and no DLL.

**Copy `csc.rsp` into `Assets/`.** At the .NET 4.x level Unity does not give a script `System.Net.Http`, the
assembly where `HttpClient` is. The file's one line, `-r:System.Net.Http.dll`, adds it. Without it, Unity reports
error CS1069 (or CS0234) for `HttpClient`. If you put `Runtime/` in an assembly definition of its own, put `csc.rsp`
beside the `.asmdef` file instead. The lobby and matches need nothing of the kind: they use `System.dll` alone.

`Runtime/` does not use `UnityEngine` at all; only the examples do.

## What to copy

1. Copy `Runtime/` to `Assets/Backend/Runtime/`. This is needed.
2. Copy `csc.rsp` to `Assets/csc.rsp`. This is needed too.
3. If you want the examples, copy `Examples/` to `Assets/Backend/Examples/`.

| From | To | What it is |
|---|---|---|
| `Runtime/` | `Assets/Backend/Runtime/` | the client, six files; needed |
| `csc.rsp` | `Assets/csc.rsp` | gives scripts `System.Net.Http`, which the .NET 4.x level leaves out; needed |
| `Examples/` | `Assets/Backend/Examples/` | three example scripts and the JSON classes they use; optional |

| File in `Runtime/` | What it does |
|---|---|
| `BackendHttp.cs` | the API over `HttpClient`: `GetAsync`, `PostAsync`, `PutAsync`, `DeleteAsync`, each awaited; `http://` only |
| `LobbyClient.cs` | the lobby: connects, sends the token, keeps the connection alive, sends requests and receives pushes; `ws://` only |
| `ArenaClient.cs` | a match: joins with a ticket, sends inputs, receives snapshots, resumes, leaves; plain TCP |
| `ArenaMessages.cs` | the match protocol's bytes, its message numbers and kick reasons (`client-simple`'s, byte for byte) |
| `WebSocketConnection.cs` | the WebSocket under `LobbyClient` (you do not call it) |
| `Json.cs` | reads one field of a JSON object: `Json.GetString(body, "token")` (`client-simple`'s, byte for byte) |

**Use this folder or `client-simple`'s, not both**: they have the same namespace and the same class names.

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
        http = new BackendHttp("http://192.168.1.20");
        lobby = new LobbyClient("ws://192.168.1.20/lobby");
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

Make one `BackendHttp` with the API's `http://` address, and one with the account service's (the site's `/account`,
D-117). Log in at the account service, take its access token to the API once for a session, and set `Token`. Every
call after that is made as the player. An `https://` address is refused: the constructor throws an `ArgumentException`, because this client has
no TLS.

```csharp
using Backend.Client.Simple;
using Backend.Client.Simple.Examples;     // Credentials, AccountTokens, TokenLogin, Session: Examples/ApiModels.cs
using UnityEngine;

public class LogInExample : MonoBehaviour
{
    private BackendHttp accounts, http;

    private void Awake()
    {
        accounts = new BackendHttp("http://192.168.1.20/account");    // an https:// address throws: no TLS here
        http = new BackendHttp("http://192.168.1.20");
    }

    public async void LogIn()
    {
        var credentials = new Credentials { username = "ada", password = "secret-123" };
        HttpAnswer answer = await accounts.PostAsync("/v1/sessions", JsonUtility.ToJson(credentials));
        if (this == null) return;                                            // destroyed while it waited
        if (!answer.Ok) { Debug.Log("refused: " + answer.Error); return; }   // "invalid_credentials", "too_many_attempts"...
        AccountTokens tokens = JsonUtility.FromJson<AccountTokens>(answer.Body);
        PlayerPrefs.SetString("refreshToken", tokens.refresh_token);         // signs the device in again (the app: its secure store)
        answer = await http.PostAsync("/v1/sessions", JsonUtility.ToJson(new TokenLogin { accessToken = tokens.access_token }));
        if (this == null) return;
        if (!answer.Ok) { Debug.Log("no session: " + answer.Error); return; } // "banned"...
        Session session = JsonUtility.FromJson<Session>(answer.Body);
        http.Token = session.token;                                          // every later call is the player's

        HttpAnswer inventory = await http.GetAsync("/v1/inventory");
        Debug.Log(inventory.Body);
    }
}
```

A call never throws. It always ends in one `HttpAnswer`:

| Field | Holds |
|---|---|
| `Ok` | true for a status of 200 to 299 |
| `Status` | the HTTP status; **0 when no answer came**: no network, or no answer in 15 s |
| `Body` | the JSON text, or "" when there is none (a 204). Read it with `JsonUtility.FromJson<T>` (the classes are in `Examples/ApiModels.cs`) |
| `Error` | null when it went well. Otherwise the API's `code` (`invalid_session`, `banned`...) or the account service's `error` (`username_taken`, `invalid_grant`...); `HTTP <status>`, such as `HTTP 429`, for an answer without a code; or what went wrong when no answer came |

`Token`, the calls and their bodies are as in `client-simple`. The calls most apps start with are in
[client-simple's README](../client-simple/README.md#1-the-api-backendhttp), and
[docs/api/01](../docs/api/01-http-api.md) has every route.

- `invalid_session` on any call means that the token is no longer valid: log in again.
- An object can be destroyed while its call waits (the scene changed). After an `await` in a `MonoBehaviour`,
  `if (this == null) return;` stops before the code touches it.

## 2. The lobby: `LobbyClient`

A player connects to the lobby once logged in, and stays connected between matches. A `wss://` address is refused:
the constructor throws an `ArgumentException`.

```csharp
using Backend.Client.Simple;
using UnityEngine;

public class LobbyConnectExample : MonoBehaviour
{
    private LobbyClient lobby;

    // token: the session's token from POST /v1/sessions (section 1)
    public void ConnectLobby(string token)
    {
        lobby = new LobbyClient("ws://192.168.1.20/lobby");
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
| `Connect(token)` | connects on a thread of its own, then sends `auth` with the token as the first message |
| `OnAuthenticated(playerId)` | the lobby took the token; send requests from here on |
| `Send(type, data, onReply)` | sends `{"t":type,"id":n,"d":data}`; the reply (`type + ".ok"`, or `error`) goes to `onReply`. It returns false, and sends nothing, when the lobby is not connected |
| `OnPush(message)` | something the lobby sends without being asked: `evt.match.found`, `evt.party.invite`, `evt.notice`... |
| every 25 s | `Update()` sends a `ping`; the lobby closes a connection that is silent for 120 s |
| `OnClosed(why)` | the connection is over; called once. When the lobby refused the token, `why` starts with `invalid_session` |
| `Close()` | closes it; `OnClosed("closed by the client")` follows in the next `Update()` |

The requests, the pushes, and what to do after `OnClosed` are the same as in `client-simple`: see
[client-simple's README](../client-simple/README.md#2-the-lobby-lobbyclient).

## 3. A match: `ArenaClient`

A grant from the lobby (the reply to `match.request`, or `evt.match.found`) names the arena and holds a ticket for
it, good for 60 seconds. Choose the call by the grant:

- the site's grants have `tls` true and a `tlsName`: take them through the site's 80 with `ConnectThroughSite`;
- a backend run plain gives grants with `tls` false: `Connect` goes straight to the arena.

```csharp
using Backend.Client.Simple;
using Backend.Client.Simple.Examples;     // Grant: Examples/ApiModels.cs
using UnityEngine;

public class MatchExample : MonoBehaviour
{
    public string apiUrl = "http://192.168.1.20";   // the API's address: a grant from the site is reached through it
    private ArenaClient arena;
    private float nextInputAt;

    public void StartMatch(string grantJson)
    {
        Grant grant = JsonUtility.FromJson<Grant>(grantJson);
        arena = new ArenaClient();
        arena.OnWelcome = welcome => Debug.Log("in the match, mode " + welcome.Mode);
        arena.OnSnapshot = (bytes, tick) => { /* the world's changes: decode and draw */ };
        arena.OnKick = reason => Debug.Log("sent away: " + reason);
        arena.OnClosed = why => Debug.Log("match closed: " + why);
        if (grant.tls) arena.ConnectThroughSite(apiUrl, grant.tlsName, grant.ticketId);   // the site's, through its 80
        else arena.Connect(grant.arenaHost, grant.arenaPort, grant.ticketId);            // a backend run plain
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
| `ConnectThroughSite(apiUrl, tlsName, ticketId)` | TCP to the API's address (given up after 10 s). It sends nginx `GET /match/<tlsName> HTTP/1.1` with `Host`, `Connection: Upgrade` and `Upgrade: backend-match`, and reads the answer: on `101`, it sends `Join` with the ticket. Any other answer (a 404 for a name that nginx does not have) ends it, and `OnClosed` says which. It throws an `ArgumentException` at once for an address that is not `http://`, or a grant without an arena's name (an arena with TLS of its own, which this client cannot reach) |
| `Connect(host, port, ticketId)` | TCP to the grant's `arenaHost` and `arenaPort` (given up after 10 s), then `Join` with the ticket |
| `OnWelcome(welcome)` | in the match: the map's size, the mode, the player's entity, and a secret to resume with |
| `OnSnapshot(bytes, tick)` | 10 to 15 a second: the world's changes ([02-networking §4](../docs/detailed-design/02-networking.md#4-the-snapshot)) |
| `SendInput(move, aim, flags)` | send it ten times a second, moving or not. Each input acknowledges the snapshots, and the arena holds them back when none has been acknowledged for a second. The aim is in radians, 0 along +x |
| every 10 s | `Update()` sends a `Ping`; `OnPong(ms)` gives the round trip |
| `SetBackground(true/false)` | call it from `OnApplicationPause`: the arena keeps the stay while the app is away |
| `OnClosed(why)` without `Leave` | the connection was lost (or 30 s passed with nothing received): `Resume()` within a minute takes up the same stay, the same way it connected |
| `OnKick(reason)` | the player was sent away (the reasons are in [client-simple's README](../client-simple/README.md#3-a-match-arenaclient)); the connection closes next, and there is nothing to resume |
| `Leave()` | leaves for good; the arena closes the connection |

The other members (`Welcome`, `ServerTick`, `CanResume`, `Profile`, `UpgradeStat`, `ChooseClass`, `Respawn`,
`SayPhrase`, `Close()`) are as in `client-simple`.

**Not included,** as in `client-simple`: reading a snapshot's contents, predicting the player's own movement, and
drawing. The snapshot is handed over as its bytes, with the server's tick.

## The examples

| Script | Shows |
|---|---|
| `HttpExample` | the API, each step awaited: the device's account signed in again by its refresh token, or a guest made once and kept in `PlayerPrefs`; the shop and the inventory read; `SignUp` and `LogIn` at the account service, a guest signed in linked to the account; `LogOut` at both |
| `LobbyExample` | the lobby: logged in, connected, a seat in the public arena asked for, a duel accepted, pushes handled, reconnecting |
| `ArenaExample` | a match from a grant, through the site's 80 or straight, as the grant needs (`Play(grant, apiUrl)`): inputs ten a second, snapshots counted, pause, resume, kicks, `LeaveMatch` |
| `ApiModels` | the `[Serializable]` classes that `JsonUtility` reads and writes |

They connect the way they are set up, in any build (D-115).

To try them:

1. Add `LobbyExample` and `ArenaExample` to one object in a scene.
2. Drag the `ArenaExample` onto the `LobbyExample`'s **Arena** field.
3. Set the two addresses on `LobbyExample`, **Api Url** and **Lobby Url**: the site's in clear (`http://`, `ws://`),
   or those of a backend run plain. `LobbyExample` hands its **Api Url** to `ArenaExample` with each grant.
4. Press Play. The Console shows each step.

## When TLS becomes possible

Copy `client-simple/Runtime/` in place of this `Runtime/`. The names are the same. What changes:

| Here | In `client-simple` |
|---|---|
| `new BackendHttp(url)` | `new BackendHttp(url, rootCertificate.text)`, `https://` |
| `new LobbyClient(url)` | `new LobbyClient(url, rootCertificate.text)`, `wss://` |
| `new ArenaClient()` | `new ArenaClient(rootCertificate.text)` |
| `arena.Connect(host, port, ticketId)`, `arena.ConnectThroughSite(apiUrl, tlsName, ticketId)` | `arena.Connect(host, port, grant.tls, grant.tlsName, ticketId)`, on 9001 with TLS |

The root is the backend's `ca.pem`, renamed `ca.pem.txt` for Unity
([client-simple's README](../client-simple/README.md#what-to-copy)). Copy `client-simple/Examples/` too: its examples
take the root, and its `ArenaExample.Play(grant)` takes no API address.

## How it was checked

It is checked by the same check as `client-simple`, run over this folder: `check.sh` runs `../client-simple/check.sh`
with `NO_TLS=1`. Nothing ran inside the Unity editor or on a phone.

- **Compiled by Unity 2019.4.28f1's own compiler** (C# 7.3, warnings as errors):
  - `Runtime/` for .NET Framework 4.5, what it targets, and at Unity's .NET 4.x level (with `csc.rsp`'s reference;
    refused without it);
  - `Examples/` against Unity's own `UnityEngine`, at the .NET 4.x level;
  - `ArenaMessages.cs` and `Json.cs` compared with `client-simple`'s, byte for byte; and `Runtime/` searched for any
    TLS type (`System.Net.Security`, `SslStream`, `TrustRoot`, `X509`, `ServicePointManager`), of which there is none.
- **Offline, on Unity 2019's own Mono** (52 checks): `client-simple`'s checks without its TLS ones. That is: JSON;
  every match message byte for byte the full client's; the arena's answers; frames; the WebSocket's parts; the API's
  calls (they return at once, and give up after 15 s); and an `https://` or `wss://` address refused. Also a match
  through the site's 80, against a stand-in for nginx: the request exact; `Join` once the 101 is read; nginx's 404
  said in `OnClosed`; an address that is not `http://`, and a grant without a name, refused.
- **Live**, by `SIMPLE=1 client/headless-drill.sh play` against a real backend, on Unity 2019's Mono:
  - through nginx's 80 in its TLS way, as the site's (`FRONT=http`), the match through `/match/` by the grant's
    name;
  - through nginx run plain (`FRONT=plain`);
  - straight to the processes (no `FRONT`).

  Each way: the account service, reached straight until the site routes it (D-117), and the API; the lobby (kept open 125 s by its heartbeat, then replaced by a second connection); a match
  (snapshots while sending inputs, a Pong, the app paused and back, a lost connection resumed, `Leave`); and every
  lobby and match callback on the thread that calls `Update()`.

```bash
client-simple-no-tls/check.sh                              # compiled for 4.5 and at .NET 4.x, the offline checks
SIMPLE=1 FRONT=http client/headless-drill.sh play          # live through nginx's 80, as the site's (also: plain, no FRONT)
```

`check.sh` takes Unity 2019.4.28f1 from `work/unity`, which `client/unity-env.sh` fetches once (1.8 GB).
