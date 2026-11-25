# protocol

This module is the match protocol in code: the binary format a client and an arena use for a match. It holds three
things:

- the numbers both sides agree on (message ids, kick reasons, event types, field bits, scales);
- the byte writer the arena encodes its snapshots into;
- a reference decoder and a reference client world. The C# client's core (`client/Core`) is a port of these.

It is plain Java, with no dependencies and no I/O. The arena uses it to write frames, the tests use it to read them,
and the C# client is held to it through shared golden vectors.

The format itself is specified in
[02-networking §2–§5](../../docs/detailed-design/02-networking.md#2-primitives). This page says where the format
lives in code, gives an overview of it, and explains how the two languages are kept in step. Terms such as *handle*,
*varint* and *golden vector* are defined in the [glossary](../../docs/glossary.md).

## Layout

Every class is in the package `com.backend.protocol`.

| Class | What it is for |
|---|---|
| `Wire` | The constants for what the arena sends: `VERSION` (4), message ids, traffic profiles, kick reasons, event types, entity kinds and unit subtypes, update field bits and tank flags. The scales: `POS_SCALE` (4) and `VELOCITY_SCALE` (256). The limits: `MAX_NAME_BYTES` (64), `MAX_HANDLES` (256) and `SELF_HANDLE` (1). The quantisers for position, heading, angle, health, velocity and bullet speed |
| `ClientMessage` | The constants for what a client sends: message ids, sandbox actions, lifecycle states, input flag bits and move bits, and `INPUT_SEQ_MASK` (24 bits). `packInput` and its readers put a whole input in one `long`, so a room thread reads it as one unit. `aimToRadians` turns an aim back into an angle |
| `SnapshotWriter` | A little-endian writer over a reusable buffer that grows when needed. It writes `u8`, `u16`, `i16`, big-endian `u32` and `f32`, LEB128 `varint`, zigzag `svarint` and `bytes`. `reserveCount` and `patchCount` write a section's count after the section is built; the count takes one byte, so it is at most 127 |
| `WireReader` | The writer's mirror, used by `SnapshotReader` and by tests. `lengthOf` refuses a length or a count that the rest of the frame could not hold (P-51) |
| `SnapshotReader` | Decodes one `Snapshot` frame into a `Frame` of `Create`, `Update` and `Event` records. It keeps no memory of earlier frames. It has payload readers for the `Motion`, `MotionRule` and `Skin` events, and it refuses a frame with bytes left over |
| `ClientWorld` | The receiving side as a client holds it: a handle table of 256, positions in world space built up from the deltas, and predicted entities placed from their create on each frame and expired when their lifetime ends |

## The wire at a glance

Every frame is `varint length, payload`. The payload's first byte is the message type. Values are little-endian
unless the table says otherwise.

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

A `string` is a varint byte length followed by that many bytes. The optional profile byte at the end of `Join` and
`Resume` is the most the client wants to be sent: 0 mobile, 1 high, 2 saver. Absent, or a value the arena does not
know, means mobile.

| Snapshot part | Values |
|---|---|
| Entity kinds | 0 `TANK`, 1 `PREDICTED` (bullets), 2 `STATIC` (shapes), 3 `UNIT` (1 trap, 2 drone, 3 minion, 4 rocket, 5 skimmer) |
| Update mask | `POS` 1, `ANGLE` 2, `HP` 4, `LEVEL` 8, `CLASS` 16, `TEAM` 32 (sent when a team changes in place, as for a dominator captured, P-60), `FLAGS` 64 |
| Tank flags | 1 protected, 2 hidden |
| Events | 1 `Death`, 2 `Stats`, 3 `Phrase`, 4 `Kill`, 5 `Motion`, 6 `MotionRule`, 7 `Skin`; each is `u8 type, varint length, payload` |

## Configuration and entry points

None. The module has no main class, reads no environment variable or system property, and opens no port.

## Build and test

From `backend/`, with the offline repository (see the repository's CLAUDE.md):

```bash
. ../dev-env.sh        # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn install            # everything
mvn -pl protocol test  # this module alone
```

| Test class | Tests | What it covers |
|---|---|---|
| `WireFormatTest` | 14 | Varint widths at every boundary; a negative varint's ten bytes; zigzag extremes; the quantisation conventions for position, heading, angle and health; a varint wider than the reader allows, refused; a length or count past the frame, refused (P-51); a truncated frame, refused; strings with multi-byte characters; the one-byte count slot; an input packed into one `long` |
| `GoldenVectorTest` | 9 | The six golden frames (`minimal`, `join_burst`, `realistic_mobile`, `progression`, `second_tier`, `motion`) decoded by the production reader with nothing left over, and their decoded values, among them the 122-byte mobile frame; a bullet's `spawnTickOffset` honoured; a corrupted vector refused |

The arena's `SnapshotRoundTripTest` also drives the real encoder through `SnapshotReader` and `ClientWorld` for
hundreds of ticks, so the server's own bytes are decoded too ([arena](../arena/README.md#tests)).

## Changing the format

The Java and C# sides drift apart without anyone noticing unless every change goes through all of these steps
([02 §12](../../docs/detailed-design/02-networking.md#12-protocol-versioning-and-the-two-language-contract)):

1. **A layout change raises `Wire.VERSION`**, and `client/Core/Wire.cs` with it. The arena sends a client of
   another version `Kick(3)` before it parses anything else. Two kinds of change need no new version: an event type
   added at the end, and a field added at the end of `Welcome`. An older client skips an unknown event by its
   length, and stops reading a `Welcome` early.
2. **Every constant is mirrored** in `client/Core/Wire.cs`, with the same meaning. Today they agree, value for value.
3. **The golden vectors** live in `protocol-spike/vectors/vectors.txt`. `protocol-spike/java/SnapshotCodec.java`
   produces them. The C# tests read that file (`client/Core.Tests/GoldenVectorTests.cs`). `GoldenVectorTest` holds
   the same bytes copied in as hex, on purpose: if the file moves, a test fails instead of being skipped.
4. **The arena's encoder** (`arena/SnapshotEncoder` and `EventBuffer`) writes the format. `SnapshotReader` and
   `ClientWorld` read it. The C# `Snapshot.cs` and `ClientWorld.cs` are ports of these readers.
5. **The simple clients** carry the version, the message ids and the frames they send and read in
   `client-simple/Runtime/ArenaMessages.cs`. `client-simple-no-tls/Runtime/ArenaMessages.cs` is the same file,
   byte for byte (D-111, D-114). A protocol change is made in all three clients.

Two more golden vectors tie the client to the server's arithmetic rather than to its bytes:
`sim/src/test/resources/maze-2026.txt` (a maze's walls) and `arena/src/test/resources/motion-2026.txt` (the steps of
a driven tank).

## Notes

- `SnapshotReader` and `WireReader` are reference code for tests. The arena never decodes client input with them:
  `MatchFrameHandler` reads its own frames. Like the C# reader (`client/Core/WireReader.LengthOf`), `WireReader`
  checks every length and count it reads against what is left of the frame (`lengthOf`, P-51).
- `ClientWorld` is written to be read, not to be fast. Interpolation and drawing are the client's job.

## Design documents

- [02 Networking](../../docs/detailed-design/02-networking.md): primitives (§2), messages (§3), the snapshot (§4),
  handles (§5), client-side simulation (§6), prediction (§9), versioning (§12).
- [Diagrams: the arena and the wire](../../docs/diagrams/02-arena-and-wire.md).
