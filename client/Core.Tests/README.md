# Core.Tests

The core's tests ([08 §6](../../docs/detailed-design/08-client.md#6-testing)):
NUnit on .NET 8, C# 9 as the core is, so Unity's test runner can run them
unchanged. Packages: NUnit 3.14.0, NUnit3TestAdapter 4.6.0,
Microsoft.NET.Test.Sdk 17.11.1; a project reference to `Core`.

```bash
cd background/client
DOTNET_NOLOGO=1 /opt/dotnet/dotnet test --logger "console;verbosity=normal"   # read "Total tests:"
```

`dotnet test` says "Passed!" even when its host dies part-way, counting only
the tests that ran; `Total tests:` reads `Unknown` after such a crash.

## The contract files, linked

Three files the server's own tests write or read are linked into the output,
never copied, so the two sides are held to the same bytes:

| File | Written by | What it holds |
|---|---|---|
| `protocol-spike/vectors/vectors.txt` | the protocol's golden vectors | snapshot frames as hex and every field they decode to: six cases |
| `backend/sim/src/test/resources/maze-2026.txt` | the server's maze generator (D-48) | one seed's walls |
| `backend/arena/src/test/resources/motion-2026.txt` | the arena's `MotionVectorTest`, from the room's own steps (D-62) | a driven tank's trajectory as the floats' bits: an open map's corner, a maze, a knock |

## Scripted servers

| Helper | In | What it is |
|---|---|---|
| `FakeArena` | `MatchConnectionTests.cs` | An arena on a loopback socket that sends what a test scripts (a Welcome, frames, a kick, a close) and records what the client sent: what the real arena cannot be made to do on demand |
| `ManualClock` | `MatchConnectionTests.cs` | An `IClock` moved by hand: backoff, cadence and the minute without waiting |
| `FrameBuilder` | `ClientWorldTests.cs` | Builds snapshot frames field by field |
| `Hosts` | `ApiClientTests.cs` | An `HttpMessageHandler` that answers by host, with hosts that cannot be reached: endpoint rotation without a network |
| `ScriptedLobby` | `LobbyClientTests.cs` | A lobby on a loopback WebSocket whose replies and pushes the test writes: the client's queue and reconnects driven where the real lobby cannot be made to go on demand |
| `MemorySecureStore` | the core | The store `AccountKeeper` is tested against |

## The tests, by file

`[Test]` counts the methods; a parameterised method runs once a row.

| File | `[Test]` | Parameterised | What it covers |
|---|---|---|---|
| `GoldenVectorTests.cs` | 3 | 2, once for each case in `vectors.txt` | the vector file has its six cases; every decoded field of every case, `motion` included; a frame cut at any byte refused, not misread; trailing bytes refused; the world applies every case that starts from nothing, and refuses one from mid-stream |
| `ClientWorldTests.cs` | 23 | | a create placed in world space and updates accumulating; every update field; a bullet extrapolated from its create, expiring at its lifetime, landing where the server's reference client puts it, created late; a maze's wall ending a bullet (its radius, after a move, on its last tick, between frames, created past it, a remove after it, no maze); a remove freeing its handle; units; an update for a handle not held is a desync; events read, an unknown one stepped over, kills and phrases; the seq echo wrapping at 24 bits; motion and skins as state, not events; Java's rounding |
| `MatchConnectionTests.cs` | 26 | 1, eight rows | each kick's `MatchEnd` (1 to 7, and a reason unknown); `Leave` waiting for the arena's close, and closing after its wait; an arena not reached is not retried; a lost connection resumed with the latest secret and a fresh world; a cold resume, its stay over, and no arena for the minute; a desync resumed; resume backoff and its minute; input ten a second with the acknowledgement and the latched fire; the own tank stepped at once; every frame's events told; a phrase; a sandbox's powers, and not before the Welcome; a ping every 10 s in every state, and the round trip; a maze's Welcome giving the world its walls; a silent arena lost in 3 s, a dead player's only when its pings go unanswered, a backgrounded one never; dead from the Death to the next tick, though the own tank is never removed; each frame told with its removals; a dial, a handshake and a Join each bounded; a credential that cannot be sent refused before dialling |
| `MessageTests.cs` | 6 | | `Join` with the version first; `Input`'s acknowledgement and little-endian aim; the ping clock big-endian; the server's messages read; frames split however the bytes arrive; a broken stream refused |
| `MazeTests.cs` | 3 | | the walls the server's for the same seed; a seed of the arena's size; a circle within its radius of a wall |
| `TankMotionTests.cs` | 2 | 1, three rows | the vector's three cases; every step the room's, bit for bit; the move bits as the room reads them |
| `OwnTankTests.cs` | 10 | | stepping at the room's rate by the clock; a frame matched to its step and the steps after replayed; a correction halving every 50 ms; a jump taken at once; nothing replayed without the echoed steps; an echo of no ticks; a send stamping its steps; not predicting while dead or without a rule; a respawn starting afresh; a stall stepping no more than it keeps |
| `SceneTests.cs` | 6 | | a tank between its last two samples, never beyond; the shorter way round; each kind by its own rule; the own tank where it is predicted; the list reused; the render tick two frames behind, never ahead, never back |
| `TouchSticksTests.cs` | 3 | | eight directions past the dead zone; aim and fire past half the reach; keys and a mouse |
| `PartyStateTests.cs` | 6 | | an older state of the same party not applied; none kept against an older state of the party it ended; another party, or none unnamed, applied; a state with no version applied as it comes; a late state of a party left not applied in the next one, even one left without its end |
| `JsonTests.cs` | 3 | 1, fifteen rows | the API's answers read; escapes and names in any script surviving a round trip; what is not JSON refused; nesting bounded |
| `ApiClientTests.cs` | 35 | | endpoints tried in turn and the one that answered tried first; a refusal an answer, not a reason to try elsewhere, with its `Retry-After`; none answering is unreachable; a POST that timed out not sent again elsewhere, a read that did; a success that is not JSON not taken for one; every call's route, method, body and bearer, and its answer read: guests and renames, the class table, the phrase list, the skin table, the shop in coins and gems, purchases, item levels, gems for money and its 503, the season pass, equipment, boosts, teams, applications, tournaments of both kinds and a round robin, the queue, a sandbox, a match asked about, a party, social calls, achievements, goals, seasons and boards; `AccountKeeper`'s first launch, a key refused, and the store touched only on the thread that signed in, as Unity's main thread requires |
| `LobbyClientTests.cs` | 5 | | after a match the queue is "none" again and a new join is queued; a declined match forgotten; a message split inside a character read whole; a lobby that stops answering found lost and dialled again; reconnects spread, not in step |

The lobby client is also driven by the headless drills against a real gateway
([../Headless/README.md](../Headless/README.md)).
