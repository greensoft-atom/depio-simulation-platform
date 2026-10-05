# Protocol spike

Phase 0 material for the match protocol
([docs/detailed-design/02-networking.md](../docs/detailed-design/02-networking.md)).
It exists to answer two questions before any production code is written:

1. **Is the snapshot format unambiguous and round-trippable?** Answered — yes.
2. **Does client-side extrapolation of bullets look acceptable?**
   ([D-9](../docs/architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client))
   Half answered: the numbers are in, the visual judgement is not.

```
protocol-spike/
├── java/SnapshotCodec.java     reference encoder + decoder + vector generator (runs, verified)
├── csharp/SnapshotReader.cs    client decoder + extrapolator (compiled and run 2026-09-27)
├── vectors/vectors.txt         golden vectors: the contract between the two
└── Spike drift analysis        see “Extrapolation drift” below
```

## Running the Java side

```bash
export JAVA_HOME=/opt/jdk21
$JAVA_HOME/bin/javac -Xlint:all -d java java/SnapshotCodec.java
$JAVA_HOME/bin/java -cp java SnapshotCodec vectors/vectors.txt
```

```
minimal               9 bytes   round-trip OK
join_burst           56 bytes   round-trip OK
realistic_mobile    122 bytes   round-trip OK
progression          33 bytes   round-trip OK
second_tier          52 bytes   round-trip OK
```

**122 bytes is a measurement, not an estimate** (119 before protocol 3 gave each
bullet's create its radius). The hand-calculated figure in the design is 125. The budget figures in
[requirements](../docs/requirements/01-scope-and-nfrs.md) and
[architecture](../docs/architecture/01-system-topology.md) follow the measured
number.

## The golden vectors are the contract

The encoder is Java and the decoder is C#. Nothing except these vectors stops
the two drifting, and "the client and server disagree about byte 43" is the
most expensive class of bug on this project.

`vectors/vectors.txt` holds, for each case, the exact bytes as hex and every
decoded field. **Both** sides must be tested against it: the Java suite in
Phase 1, and the Unity test runner as soon as the client exists.

Five cases, chosen for what they break:

| Case | Why |
|---|---|
| `minimal` | An empty snapshot — every count zero. Catches decoders that assume at least one section. |
| `join_burst` | Positions with the sign bit set (8191, −8192), a multi-byte UTF-8 name (`éà中文`), tanks and a static entity. Catches byte-length-versus-character-count bugs. |
| `realistic_mobile` | 12 tank updates with mixed field masks, three predicted creates, three removes, one event. The shape of an actual frame. |
| `progression` | No entities, two events: the player's own stats and a death with a killer's name. Catches event framing: type, byte length, then the payload. |
| `second_tier` | Protocols 3 and 4: a bullet of radius 16, a trap and a drone as units with their radius, and a unit's update. Catches a decoder that reads a predicted create or a unit without its radius, or does not know the unit kind. |

When the format changes, regenerate the file and treat any diff as deliberate.

## Extrapolation drift (D-9, numerical half)

Simulated over a full 75-tick bullet life, worst case across all firing angles.
Screen scale: a phone showing 1 600 world units across 1 080 px.

| Velocity encoding | Bytes | Max drift |
|---|---|---|
| `i8` at 1.0 unit/tick | 2 | **33 px** — unusable |
| `i8` at 0.25 unit/tick | 2 | 8.6 px |
| `i16` at 0.0625 | 4 | 2.3 px |
| **`u16` heading + `u8` speed** | **3** | **0.3 px** |

The first draft of the protocol used `i8` per axis. The spike caught it before
any client code existed, and the fix is smaller on the wire as well as more
accurate, because a bullet's speed is exact rather than a measurement — only the
heading needs quantising. It was a table index when this was measured; since
protocol 2 (2026-09-27) it is the speed in half units a tick, which the
simulation fires at, and the vectors carry realistic ones (29 is 14.5 units a
tick, 61 a Sniper's 30.5).

**The error that matters is late destroy, not quantisation.** A bullet keeps
flying until the destroy event arrives, RTT/2 late: 8 px at 100 ms, 17 px at
200 ms. That dwarfs every encoding choice, which is why precision beyond the
polar scheme buys nothing, and why the mitigation is visual — draw the hit
effect where the destroy says it happened, and never client-predict hits.

## What still needs a human

The C# in `csharp/` was written on a machine with **no C# toolchain**. On
2026-09-27, with the .NET 8 SDK installed, it **compiled without a change and
applied all four vectors**: tick, input sequence, view origin and every field
of every create as the vectors state, in world space; a harness fed altered
expectations failed on each. What its state cannot show — removes, update
deltas, event payloads — is checked field by field by the client's own decoder
([client/](../client/README.md)), which grew from this one.

The remaining Phase 0 work:

1. ~~Drop `SnapshotReader.cs` into a Unity project and make it compile.~~
   Compiled with the .NET SDK (netstandard-compatible code); Unity itself is
   not on this machine.
2. ~~Write the loader for `vectors/vectors.txt` and assert every decoded field.~~
   Done, as above.
3. Render extrapolated bullets against a simulated server feed with adjustable
   RTT, jitter and loss, and **look at it**. The question is whether the
   late-destroy overshoot reads as wrong in motion. If it does, D-9 needs
   revisiting and the bandwidth plan with it.

Step 3 is the one that cannot be done here, and it is the one that matters.
