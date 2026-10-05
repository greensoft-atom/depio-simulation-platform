# protocol

The match protocol as code: the numbers both sides agree on, the byte writer the
arena encodes snapshots into, and a reference decoder and client world that the
C# client (`client/Core`) is a port of. Plain Java, no dependencies, no I/O.

The format itself is specified in
[02-networking §2–§5](../../docs/detailed-design/02-networking.md#2-primitives);
this page says where it lives in code and how it is held to account.

## Layout

| Class | What it is for |
|---|---|
| `Wire` | Server→client constants: `VERSION` (4), message ids, traffic profiles, kick reasons, event types, entity kinds and unit subtypes, update field bits, tank flags; the scales (`POS_SCALE` 4, `VELOCITY_SCALE` 256), `MAX_NAME_BYTES` (64), `MAX_HANDLES` (256), `SELF_HANDLE` (1); the quantisers for position, heading, angle, health, velocity and bullet speed |
| `ClientMessage` | Client→server ids, sandbox actions, lifecycle states, input flag and move bits, `INPUT_SEQ_MASK` (24 bits); `packInput` and its readers, which put a whole input in one `long` so a room thread reads it as a unit; `aimToRadians` |
| `SnapshotWriter` | Little-endian writer over a reusable, growing buffer: `u8`, `u16`, `i16`, big-endian `u32` and `f32`, LEB128 `varint`, zigzag `svarint`, `bytes`; `reserveCount`/`patchCount` for a section count written after the section (one byte, at most 127) |
| `WireReader` | The writer's mirror, for `SnapshotReader` and for tests |
| `SnapshotReader` | Decodes one `Snapshot` frame into a `Frame` of `Create`, `Update` and `Event` records, with no memory of earlier frames; payload readers for `Motion`, `MotionRule` and `Skin`; refuses a frame with bytes left over |
| `ClientWorld` | The receiving half as a client holds it: a handle table of 256, positions in world space accumulated from deltas, predicted entities placed from their create each frame and expired by their lifetime |

## The wire at a glance

Every frame is `varint length, payload`; the payload's first byte is its type.
Little-endian unless said otherwise.

| Direction | Id | Message | Payload |
|---|---|---|---|
| client → arena | 1 | `Join` | `u8 version, string ticket, [u8 profile]` |
| | 2 | `Input` | `varint seq, varint ackTick, u8 move, u16 aim, u8 flags` |
| | 3 | `UpgradeStat` | `u8 stat` |
| | 4 | `ChooseClass` | `u8 classId` |
| | 5 | `Respawn` | none |
| | 6 | `Phrase` | `varint phraseId` |
| | 7 | `Ping` | `u32 clientTimeMs` (big-endian) |
| | 8 | `Lifecycle` | `u8 state` (0 foreground, 1 background) |
| | 9 | `Leave` | none |
| | 10 | `Resume` | `u8 version, string secret, [u8 profile]` |
| | 11 | `Sandbox` | `u8 action, varint value` |
| arena → client | 1 | `Welcome` | `u8 selfHandle, u8 snapshotHz, varint mapW, mapH, u8 mode, varint contentVersion, phraseListVersion, selfEntityId, string resumeSecret, varint mazeSeed` |
| | 2 | `Snapshot` | header, removes, creates, updates, events ([02 §4](../../docs/detailed-design/02-networking.md#4-the-snapshot)) |
| | 3 | `Pong` | `u32 clientTimeMs, varint serverTick` |
| | 4 | `Kick` | `u8 reason` (1 bad ticket, 2 room full, 3 protocol version, 4 rate limit, 5 internal, 6 match over, 7 removed) |

| Snapshot part | Values |
|---|---|
| Entity kinds | 0 `TANK`, 1 `PREDICTED` (bullets), 2 `STATIC` (shapes), 3 `UNIT` (1 trap, 2 drone, 3 minion, 4 rocket, 5 skimmer) |
| Update mask | `POS` 1, `ANGLE` 2, `HP` 4, `LEVEL` 8, `CLASS` 16, `TEAM` 32 (defined, never sent), `FLAGS` 64 |
| Tank flags | 1 protected, 2 hidden |
| Events | 1 `Death`, 2 `Stats`, 3 `Phrase`, 4 `Kill`, 5 `Motion`, 6 `MotionRule`, 7 `Skin`, each `u8 type, varint length, payload` |

## Configuration and entry points

None: the module has no main class, reads no environment variable or system
property, and opens no port.

## Build and test

From `backend/`, with the offline repository (background/CLAUDE.md):

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o install                    # everything
/opt/maven/bin/mvn -o -pl protocol test          # this module alone
```

| Test class | Tests | What it covers |
|---|---|---|
| `WireFormatTest` | 14 | Varint widths at every boundary, a negative varint's ten bytes, zigzag extremes, the quantisation conventions, a varint wider than the reader allows refused |
| `GoldenVectorTest` | 9 | The six golden frames (`minimal`, `join_burst`, `realistic_mobile`, `progression`, `second_tier`, `motion`) decoded by the production reader with nothing left over, and their decoded values, the 122-byte mobile frame among them |

The arena's `SnapshotRoundTripTest` drives the real encoder through
`SnapshotReader` and `ClientWorld` for hundreds of ticks, so the server's own
bytes are decoded too ([arena](../arena/README.md#tests)).

## Changing the format

The two languages drift apart silently unless each change goes through all of
these ([02 §12](../../docs/detailed-design/02-networking.md#12-protocol-versioning-and-the-two-language-contract)):

1. **A layout change bumps `Wire.VERSION`**, and `client/Core/Wire.cs` with it:
   a client of another version is sent `Kick(3)` before anything is parsed. An
   appended event type, or a field appended at the end of `Welcome`, needs no
   bump, since an older client steps over an event by its length and stops
   reading a `Welcome` early.
2. **Every constant is mirrored** in `client/Core/Wire.cs`, under the same
   meaning. They agree today, value for value.
3. **The golden vectors** live in `protocol-spike/vectors/vectors.txt`,
   produced by `protocol-spike/java/SnapshotCodec.java`; the C# tests read that
   file (`client/Core.Tests/GoldenVectorTests.cs`), and `GoldenVectorTest` holds
   the same bytes copied in as hex, deliberately, so a moved file fails a test
   instead of skipping it.
4. **The arena's encoder** (`arena/SnapshotEncoder`, `EventBuffer`) writes it,
   `SnapshotReader` and `ClientWorld` read it, and the C# `Snapshot.cs` and
   `ClientWorld.cs` port the readers.

Two other golden vectors bind the client to the server's arithmetic rather than
its bytes: `sim/src/test/resources/maze-2026.txt` (a maze's walls) and
`arena/src/test/resources/motion-2026.txt` (a driven tank's steps).

## Notes

- `SnapshotReader` and `WireReader` are reference code for tests: the arena
  never decodes client input with them (`MatchFrameHandler` reads its own
  frames). They trust length fields more than the C# reader does, which checks
  every length against the frame (`client/Core/WireReader.LengthOf`).
- `ClientWorld` is written to be read, not to be fast; interpolation and
  drawing are the client's.

## Design documents

- [02 Networking](../../docs/detailed-design/02-networking.md): primitives (§2),
  messages (§3), the snapshot (§4), handles (§5), client-side simulation (§6),
  prediction (§9), versioning (§12).
- [Diagrams: the arena and the wire](../../docs/diagrams/02-arena-and-wire.md).
