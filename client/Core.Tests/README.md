# Core.Tests

These are the tests of the client's core, `Backend.Client.Core`
([08 §6](../../docs/detailed-design/08-client.md#6-testing)). They are for developers who change the core: run them
before and after every change. This page says how to run them, which files they share with the server's tests, which
scripted servers they use, and what each test file covers.

**What they are.** NUnit tests in C# 7.3 on .NET Framework 4.5 (D-116), 196 in all: 158 `[Test]` methods, and 38
runs of 5 parameterised methods.

| Where they run | How |
|---|---|
| .NET Framework 4.5 | Visual Studio 2019 builds them and runs them in its Test Explorer |
| Unity 2019.4's own Mono | this machine: `../core-tests.sh` compiles them for .NET Framework 4.5 with Unity's compiler and runs them there by NUnitLite, with `float32` as Unity's Mono has it (D-109, P-65, D-116). `../unity2019-check.sh` runs it too |

Nothing here runs them on .NET 8, or any modern .NET (D-116). So two things are not checked here: the name that a TLS
handshake gives, as a server sees it (only .NET 8's API showed it), and TLS 1.2 on .NET Framework itself. Unity's
Mono outside the editor speaks TLS 1.0, so the tests' TLS runs at 1.0.

**Their certificates** are files in `Pki/`, made once by `Pki/make.sh` with openssl. They are RSA, signed as
`private-ca.sh` signs, and they are test keys only, nothing of the site's. The tests cannot make them at run time:
.NET Framework 4.5 and Unity 2019 have no `CertificateRequest`.

**Packages:** NUnit 3.14.0, NUnit3TestAdapter 3.17.0 and Microsoft.NET.Test.Sdk 16.11.0 (the adapter and the test
SDK at the last versions that take 4.5), System.ValueTuple 4.5.0 (the tests' tuples, which .NET Framework 4.5 does
not have), and Microsoft.NETFramework.ReferenceAssemblies 1.0.3 (.NET Framework 4.5's reference assemblies, for a
machine without its targeting pack); and a project reference to `Core`. `core-tests.sh` does not use the project. It compiles the files
itself, with NUnit, NUnitLite 3.14.0 and System.ValueTuple from nuget.org, fetched once into `work/unity/nuget`, each
SHA-256 checked.

```bash
cd client
./core-tests.sh                                          # all of them: read "Test Count:"
TESTS="class =~ MatchConnectionTests" ./core-tests.sh    # some, by an NUnit where-expression
```

**Read the count.** `core-tests.sh` prints NUnitLite's summary, `Test Count: 196, Passed: 196, Failed: 0, …`, and
says `CORE TESTS PASSED` only when none failed. A run that dies part of the way through prints no summary; the
script then says so, and fails.

## The contract files, linked

Three files that the server's own tests write or read are linked into the test output, never copied. So the client
and the server are held to the same bytes:

| File | Written by | What it holds |
|---|---|---|
| `protocol-spike/vectors/vectors.txt` | the protocol's golden vectors | snapshot frames as hex, and every field they decode to: six cases |
| `backend/sim/src/test/resources/maze-2026.txt` | the server's maze generator (D-48) | one seed's walls |
| `backend/arena/src/test/resources/motion-2026.txt` | the arena's `MotionVectorTest`, from the room's own steps (D-62) | a driven tank's trajectory, as the floats' bits: an open map's corner, a maze, a knock |

## Scripted servers

The tests do not need a running backend. These helpers play the server's part, or help build what a server sends:

| Helper | In | What it is |
|---|---|---|
| `FakeArena` | `MatchConnectionTests.cs` | An arena on a loopback socket. It sends what a test scripts (a Welcome, frames, a kick, a close) and records what the client sent: things the real arena cannot be made to do on demand |
| `SiteFront` | `MatchConnectionTests.cs` | The front's 80 as nginx is for a match (D-115): it reads the request to its blank line, keeps it, answers it, and on a 101 passes bytes between the client and the arena both ways |
| `ManualClock` | `MatchConnectionTests.cs` | An `IClock` that the test moves by hand, to test backoff, cadence and the resume minute without waiting |
| `FrameBuilder` | `ClientWorldTests.cs` | Builds snapshot frames field by field |
| `Hosts` | `ApiClientTests.cs` | An `HttpMessageHandler` that answers by host, with some hosts that cannot be reached: endpoint rotation without a network |
| `ScriptedLobby`, `ServerSocket` | `LobbyClientTests.cs` | A lobby on a loopback WebSocket of the tests' own (Mono, and .NET Framework off Windows, have no WebSocket server). The test writes its replies, pushes, fragments and close, to drive the client's queue and reconnects where the real lobby cannot be made to go on demand |
| `WebSocketServer` | `TrustRootTests.cs` | The server's side of a WebSocket: it answers the opening handshake (with the `Sec-WebSocket-Accept` it is given) and reads the client's masked frames |
| `TestPki` | `TrustRootTests.cs` | The certificates in `Pki/`, and the test servers' keys with them |
| `FloatBits` | `TankMotionTests.cs` | A float's bits and back, which .NET Framework's `BitConverter` does not have |
| `MemorySecureStore` | the core | The store that `AccountKeeper` is tested against |

## The tests, by file

The `[Test]` column counts the methods marked `[Test]`. The Parameterised column counts the parameterised methods
first; each such method runs once for each of its rows. `ReadmeTestListTests` holds this table to the code (DOC-31):
there must be a row for each file with tests, with its counts right, and no row for a file that is gone. So when you
add, remove or rename a test, update this table in the same change.

| File | `[Test]` | Parameterised | What it covers |
|---|---|---|---|
| `GoldenVectorTests.cs` | 3 | 2, once for each case in `vectors.txt` | the vector file has its six cases; every decoded field of every case, `motion` included; a frame cut at any byte is refused, not misread; trailing bytes are refused; the world applies every case that starts from nothing, and refuses one from mid-stream |
| `ClientWorldTests.cs` | 23 | | a create placed in world space, and updates adding up; every update field; a bullet extrapolated from its create, expiring at its lifetime, landing where the server's reference client puts it, and created late; a maze's wall ending a bullet (by its radius, after a move, on its last tick, between frames, created past it, a remove after it, no maze); a remove freeing its handle; units; an update for a handle not held is a desync; events read, an unknown one stepped over, kills and phrases; the seq echo wrapping at 24 bits; motion and skins as state, not events; Java's rounding |
| `MatchConnectionTests.cs` | 30 | 1, eight rows | a match through the site's 80 by the arena's name, when it is taken, a site that does not open the tunnel, a site without the arena's name (D-116); each kick's `MatchEnd` (1 to 7, and an unknown reason); `Leave` waiting for the arena's close, and closing after its wait; an arena not reached is not tried again; a lost connection resumed with the latest secret and a fresh world; a cold resume, its stay over, and no arena for the minute; a desync resumed; resume backoff and its minute; input ten a second, with the acknowledgement and the latched fire; the own tank stepped at once; every frame's events told; a phrase; a sandbox's powers, and not before the Welcome; a ping every 10 s in every state, and the round trip; a maze's Welcome giving the world its walls; a silent arena lost in 3 s, a dead player's connection only when its pings go unanswered, a backgrounded one never; dead from the Death to the next tick, though the own tank is never removed; each frame told with its removals; a dial, a handshake and a Join each with a time limit; a credential that cannot be sent refused before dialling |
| `MessageTests.cs` | 6 | | `Join` with the version first; `Input`'s acknowledgement and little-endian aim; the ping's clock big-endian; the server's messages read; frames split however the bytes arrive; a broken stream refused |
| `MazeTests.cs` | 3 | | the same walls as the server's for the same seed; a seed of the arena's size; a circle within its radius of a wall |
| `MazeShotTests.cs` | 5 | | the headless driver's maze shot, its file compiled in (T-66): the trigger held, every input carrying fire; the aim taken again from where the tank is made again, as a made match's start places every tank anew; a wall too near, behind another or beyond a bullet's reach no target; the first of the tanks with a wall in reach fires; a bullet ending at a wall seen in any frame of a poll, in the shooter's world alone |
| `TankMotionTests.cs` | 2 | 1, three rows | the vector's three cases; every step the same as the room's, bit for bit; the move bits as the room reads them |
| `OwnTankTests.cs` | 10 | | stepping at the room's rate by the clock; a frame matched to its step, and the steps after it replayed; a correction halving every 50 ms; a jump taken at once; nothing replayed without the echoed steps; an echo of no ticks; a send stamping its steps; no prediction while dead or without a rule; a respawn starting afresh; a stall stepping no more than it keeps |
| `SceneTests.cs` | 7 | | a tank between its last two samples, never beyond; a tank at its class's size, 30 times its `bodySize` (P-67); the shorter way round; each kind by its own rule; the own tank where it is predicted; the list reused; the render tick two frames behind, never ahead, never back |
| `TouchSticksTests.cs` | 3 | | eight directions past the dead zone; aim, and fire past half the reach; keys and a mouse |
| `PartyStateTests.cs` | 6 | | an older state of the same party not applied; no state kept against an older state of the party it ended; another party, or a "no party" state that names none, applied; a state with no version applied as it comes; a late state of a party left not applied in the next party, even one left without its end |
| `JsonTests.cs` | 3 | 1, fifteen rows | the API's answers read; escapes, and names in any script, surviving a round trip; what is not JSON refused; nesting limited |
| `ApiClientTests.cs` | 37 | | endpoints tried in turn, and the one that answered tried first next time; a refusal is an answer, not a reason to try elsewhere, with its `Retry-After`; none answering is unreachable; a POST that timed out is not sent again elsewhere, a read that timed out is; a success that is not JSON is not taken for one; every call's route, method, body and bearer, and its answer read: guests and renames, the class table, the phrase list, the skin table, the shop in coins and gems, purchases, item levels, gems for money and its 503, the season pass, equipment, boosts, teams, applications, tournaments of both kinds and a round robin, the queue, a sandbox, a match asked about, a party, social calls, achievements, goals, seasons and boards; `AccountKeeper`'s first launch, a key refused, the store touched only on the thread that signed in (as Unity's main thread requires), and an account deleted, with a guest's key forgotten with it (D-84) |
| `LobbyClientTests.cs` | 10 | | after a match the queue is "none" again and a new join is queued; a join's answer that comes late, after a queue push or after a leave, dropped (T-61); a declined match forgotten; a message split inside a character read whole; a lobby that stops answering found lost and dialled again; reconnects spread out, not in step; the core's own WebSocket (D-108): an answer with a wrong `Sec-WebSocket-Accept` refused, and a ping answered with a masked pong carrying its payload; a join refused after a queue push is still the join's refusal, and one left before its refusal is not (T-67) |
| `TrustRootTests.cs` | 9 | | TLS 1.2 asked for by name, which .NET Framework 4.5 does not give by default (D-116); the backend's root (D-82), checked by the client itself (D-108): a certificate it signed accepted for each name it holds (DNS, IPv4, IPv6); refused when from another root, for another name, expired, not yet valid, signed with SHA-384, altered, or missing; years past 2029 read as written; a root of another key type refused, saying why; the root read from its PEM; a match checking the arena's certificate for the grant's TLS name, and refused without the name (which name the handshake gives, a server sees on .NET alone, not run here: D-116); the API through `HttpWebRequest` and the lobby over `wss://`, each checked for its own host |
| `ReadmeTestListTests.cs` | 1 | | this table held to the code (DOC-31): a row for each file with tests, naming it, with its `[Test]` and parameterised methods counted, and no row for a file that is gone |

The headless drills also drive the lobby client against a real gateway
([../Headless/README.md](../Headless/README.md)).
