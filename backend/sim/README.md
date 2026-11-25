# sim

This module is the simulation of one room: tanks, bullets, traps, drones and shapes on a square map, stepped at a
fixed 25 Hz. Every balance table is in code here. The module has no networking, no I/O, no clock and no threads of
its own. Two callers drive it: `arena/RoomThread` for real players, and `tools/TickBenchmark` to measure a tick. One
thread at a time touches a room, and a step allocates nothing once the room is running.

This page lists the classes, describes one step, gives the shipped constants and tables, and says what to know before
changing the module. Terms such as *tick*, *player tag* and *skill point* are defined in the
[glossary](../../docs/glossary.md).

Design: [01 Realtime arena](../../docs/detailed-design/01-arena.md), §2–§8.

## Layout

Every class is in the package `com.backend.sim`.

| Group | Class | What it is for |
|---|---|---|
| The step | `Room` | `step(PhaseTimer)` runs one tick. `Room` also spawns (`spawnTank`, `spawnShape`), fires, moves drones and missiles, and handles collisions, kills, assists and experience. It has the operations the arena calls: `chooseClass`, `spendPoint`, `assignClass`, `setLevel`, `grantExperience`, `resetForNewMatch` and `setWalls` |
| | `World` | The fixed pool of `Entity` objects (its capacity is set when it is built), a stack of free slots, one id list per kind, `tankStats[]` beside the pool, the room's `Xorshift` random number generator and the `SpatialHash`. `kill` marks an entity as dead; `sweep` recycles its slot at the end of the tick |
| | `Entity` | One pooled object with public primitive fields. Its kinds: `KIND_TANK`, `KIND_BULLET` (bullets, traps, drones, minions and missiles) and `KIND_SHAPE`. Its wire classes: `WIRE_TANK`, `WIRE_PREDICTED`, `WIRE_STATIC` and `WIRE_UNIT` |
| | `SpatialHash` | A uniform grid, rebuilt every tick: `head[cell]` and `next[id]`, each entity in one cell by its centre. `queryInto` returns the candidates in the cells a circle covers |
| | `KillLog` | The tick's kills (killer tag, victim tag, victim kind, experience) and assists. The room thread reads and empties it |
| Progression | `TankStats` | One per slot: level, experience, unspent points, points per stat, class, skin, equipment bonus and the assist ring. It recomputes the effective stats only when something has changed |
| Tables | `Content` | The tables a room is built with, kept together |
| | `Stat`, `StatTable` | The eight stats (their indices are part of the wire contract) and how each one grows, by addition or by proportion |
| | `LevelTable` | The experience for each level up to 45, and the points each level grants |
| | `ShapeTable` | The four shapes and their spawn weights |
| | `ClassTable` | The 52 tank classes with their barrels (`Barrel`, `TankClass`), caps, body, zoom and health multipliers. `json()` writes the table; `version()` is its CRC-32 |
| | `PhraseTable` | The 16 phrases a player may say; `json()` and `version()` |
| | `Recovery`, `Spawning` | `Recovery`: the burst of health after a quiet spell. `Spawning`: spawn protection, the clearance from other tanks, and how many places to try |
| Maze | `MazeGenerator` | Makes a maze's walls from a seed, by recursive division |
| | `Walls` | Those walls, indexed by cell. `hits` ends a bullet; `pushOut` slides a body out of a wall |

## One step

`Room.step` advances `tick` and runs six timed phases, in this order. The `PhaseTimer` name of each is in brackets.

1. **Tanks** (`tanks`). For each tank, in order:
   - refresh the effective stats, and keep health in step with maximum health;
   - move: by input for a player's tank, not at all for an anchored dominator, hunting for co-op's tanks, or
     wandering for a bot. The velocity rule is `v = (v + dir × accel) × 0.90`;
   - clamp the tank to the map, then push it out of any wall;
   - regenerate health, and count the reload down;
   - size the body by class; work out the zoom shift, and whether a hiding class is hidden;
   - fire the barrels of the volley in progress. Start a new volley when the reload is done and something asks for
     one: the trigger, or, without it, a drone barrel or a turret.
2. **Bullets** (`bullets`). Drones and minions steer, and minions shoot. Missiles shoot. Everything else flies
   straight; traps slow by a factor of 0.9 each tick. Anything past its lifetime, off the map or touching a wall
   dies.
3. **Shapes** (`shapes`). Shapes drift. A knock wears off while the speed is above 0.15 units a tick. Each shape
   spins for show, then is clamped to the map and kept out of walls.
4. **Hash** (`hash`). Clear the grid, then insert every tank, bullet and shape.
5. **Collide** (`collide`). Each bullet is tested against what is near it. Then each tank's body is tested against
   tanks and shapes (see [the diagram](../../docs/diagrams/02-arena-and-wire.md#collision-broadphase)).
6. **Sweep** (`sweep`). Recycle the slots of everything that died this tick.

The step records kills in `KillLog` and never scores them. The arena's tally (`arena/MatchTally`) turns kills into
score.

## Constants and tables

These are the shipped values. Each table's comments in the code say where its numbers came from. Changing a value
is a balance change, and balance changes are recorded in the design document.

| `Room` constant | Value | Meaning |
|---|---|---|
| `TANK_RADIUS`, `TANK_MASS` | 30, 10 | A tank's body at size 1 |
| `TANK_ACCEL`, `TANK_FRICTION` | 0.16, 0.90 | A base tank's top speed is about 1.44 units a tick |
| `BULLET_SPEED`, `BULLET_MASS` | 10, 1 | The speed is multiplied by the bullet-speed stat and the barrel's multiplier, then rounded to half a unit |
| `TRAP_FRICTION` | 0.9 | A trap slides, in all, ten times its launch speed |
| `DRONE_ACCEL`, `DRONE_FRICTION` | 0.5, 0.9 | A drone moves 4.5 units a tick at most |
| `DRONE_REACH`, `DRONE_ORBIT` | 300, 100 | How far along the aim a drone goes while attacking; how far it circles otherwise |
| `TURRET_REACH` | 400 | How far a turret looks |
| `HUNT_CLOSE`, `HUNT_FIRE` | 400, 600 | A hunting tank closes to this distance, and fires within that one |
| `SHAPE_DRIFT_MAX`, `SHAPE_FRICTION` | 0.15, 0.9 | The drift speed kept; how a knock wears off |
| `CONTACT_DAMAGE_PER_TICK` | 1/25 | Body damage is counted per second of contact |
| `TANK_KILL_XP_SHARE`, `TANK_KILL_XP_FLOOR` | 0.25, 10 | A tank kill's experience: a quarter of what the victim had earned, and at least 10 |
| `ATTACKERS`, `ASSIST_TICKS`, `ASSIST_XP_SHARE` | 3, 125, 0.25 | The assist ring: it remembers 3 attackers; a hit within 125 ticks (5 s) of the death counts; an assist pays a quarter of what the kill paid |

| Table | Shipped |
|---|---|
| `StatTable` | Regen: 0.0003 of max health a tick × (1 + 0.6·p). Max health: 50 + 2·level + 20·p. Body damage: 20 + 6·p. Bullet speed: 1 + 0.15·p. Penetration: 8 + 4·p. Bullet damage: 7 + 3·p. Reload: 8 ticks × (1 − 0.08·p). Movement: 1 + 0.07·p. (p is the points spent in the stat) |
| `LevelTable` | 45 levels. Level 2 at 4 experience, 10 at 160, 30 at 5 300, 45 at 23 000, geometric between. One point a level up to 28, then one every third level: 33 in all. At most 7 in one stat (`Stat.MAX_POINTS_PER_STAT`) unless the class says otherwise |
| `ShapeTable` | Square: radius 18, health 10, experience 10, weight 600. Triangle: r22, hp30, xp25, weight 300. Pentagon: r32, hp100, xp130, weight 95. Alpha pentagon: r80, hp3 000, xp3 000, weight 5 |
| `Recovery` | After 750 quiet ticks (30 s), 1 % of maximum health a tick, on top of the stat |
| `Spawning` | 75 ticks of protection; 500 units from any tank; 20 attempts, else the best of them |
| `ClassTable` | 0 is Basic. 1–4 open at level 15. 5–14, 33 (Auto 3) and 37 (Smasher) open at 30. The rest of 15–48 open at 45. 49 Guardian, 50 Guardian enraged and 51 Dominator open at no level. Ids are only ever added at the end, never moved. No class keeps more than 75 bullets or 120 traps alive at full reload (D-22); `ClassTest` checks this |
| `PhraseTable` | Ids 1 to 16, never reused |
| `MazeGenerator` | 10 × 10 cells of 300 units, walls 40 thick. A fifth of the division's 81 walls is taken out, which leaves 65 |

A barrel's `kind` says what it launches, and the unit subtype the arena sends it as: `BULLET` 0, `TRAP` 1, `DRONE` 2,
`MINION` 3, `ROCKET` 4, `SKIMMER` 5. `CONVERT` 6 launches nothing (the Necromancer's squares become its drones) and
never reaches the wire. A `Barrel` refuses a delay outside [0, 1), a bullet lifetime outside 1–255 ticks (it travels
in a byte) and a radius over 255 units.

`ClassTable.json()` and `PhraseTable.json()` are written by hand in a fixed order, so the same table always gives the
same bytes. Their CRC-32 is the `Welcome`'s `contentVersion` and `phraseListVersion`. The `platform` serves the same
JSON at `GET /v1/content/classes` and `GET /v1/content/phrases`, with the CRC-32 as the ETag (D-24). `healthMul` is
not in the class JSON, because health travels as a fraction of the maximum.

## Things to know before changing it

- **Single-threaded, allocation-free.** Nothing here is synchronised, and a step must allocate nothing.
  `AllocationTest` runs a room of level-45 bots of every class and fails if a step allocates a single byte. Loops
  over a `List` use indices, not iterators (M-14).
- **Slots are reused at once.** `World.sweep` puts freed slots on top of a stack, and `spawn` takes from the top. So
  the slot a tank died in is usually the next thing spawned. Anything that refers to an entity across ticks keeps
  its slot *and* its `generation`: a bullet's `ownerId` and `ownerGeneration`, and the assist ring. Credit goes by
  `playerTag`, a counter per room that never repeats; bots share tag 0.
- **Teams.** Team 0 means no team. Two entities of the same non-zero team never hurt each other (`Room.sameTeam`).
  Team 1 spawns in the map's left third, team 2 in its right third.
- **Bots spend their own points.** A tank nobody drives takes a random class at each tier open to it. It spends its
  points as it earns them, evenly, within its class's caps (`Room.credit`). The arena gives a boss or a dominator its
  class with `assignClass` before its experience, so their points follow the boss caps (no movement speed).
- **The random number generator** is the room's own `Xorshift`. A spread is drawn only for a barrel that has one, so
  adding a barrel without spread leaves every other draw as it was. The arena seeds each room from the clock; the
  benchmark uses the seed 42.

## Build and test

From `backend/` (see the repository's CLAUDE.md):

```bash
. ../dev-env.sh       # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn install           # everything
mvn -pl sim -am test  # this module and common
```

Measure what a change costs a tick with `TickBenchmark`, before and after, on the same machine
([tools](../tools/README.md#tickbenchmark)).

## Tests

18 classes, 170 tests.

| Test class | Tests | What it covers |
|---|---|---|
| `AllocationTest` | 1 | A room of level-45 bots of every class allocates nothing, tick after tick |
| `AssistTest` | 7 | The assist ring: five seconds, three players, a quarter of the kill, the killer not paid twice |
| `BodyContactTest` | 8 | Body damage per second of contact, both ways; tanks cannot drive through shapes; mutual kills |
| `ClassTest` | 58 | Every tier's barrels, volleys and delays; recoil; sizes; traps; drones; turrets; caps; hiding; minions; missiles; zoom; body size; the D-22 budget over the whole table |
| `ContentTest` | 11 | The level curve's anchors, and that it only rises; 33 points; the stat and shape tables, and what they refuse |
| `DominationTest` | 7 | The Dominator class; anchoring; capture by a player's bullet or body, and by nothing else |
| `EquipmentBonusTest` | 3 | The equipment bonus and the skin: applied, kept through levels and a resume, gone with a new slot |
| `HuntTest` | 6 | A hunting tank's closing in, firing and choice of target |
| `KillAttributionTest` | 9 | Credit by tag and generation when a shooter's slot has been reused |
| `MazeGeneratorTest` | 4 | The same seed gives the same maze; 81 and 65 walls; every cell reachable; the golden vector `maze-2026.txt` that the C# client reads |
| `PhraseTableTest` | 4 | The shipped sixteen; `contains`; fixed JSON bytes and CRC-32; lists that are refused |
| `ProgressionTest` | 15 | Experience over several levels; the top level; experience saturating rather than wrapping (M-6); spending points and caps; recomputing only when something changed |
| `RecoveryTest` | 3 | The quiet half minute, and what restarts it |
| `RoomTest` | 8 | Slot reuse and id lists; the pool refusing to grow; the spatial hash |
| `SetLevelTest` | 5 | The sandbox's level power |
| `SpawnTest` | 7 | Spawn clearance; never inside a shape; protection to the tick |
| `TeamTest` | 5 | Team rules for bullets, bodies and traps; team 0 as no team |
| `WallsTest` | 9 | Bodies stopped and sliding, drones stopped and bullets ended, by walls |

`Fixtures` is a helper, not a test: content with no spawn protection, for tests that are not about arriving.

## Design documents

- [01 §1 Room model and tick loop](../../docs/detailed-design/01-arena.md#1-room-model),
  [§3 Stats](../../docs/detailed-design/01-arena.md#3-stats-and-modifier-pipeline),
  [§4 Levelling and the tank tree](../../docs/detailed-design/01-arena.md#4-levelling-xp-tank-tree),
  [§5 Movement](../../docs/detailed-design/01-arena.md#5-movement-and-physics),
  [§6 Collisions](../../docs/detailed-design/01-arena.md#6-spatial-hash-and-collisions),
  [§7 Deaths and respawn](../../docs/detailed-design/01-arena.md#7-deaths-respawn-spectate),
  [§8 Modes](../../docs/detailed-design/01-arena.md#8-match-modes).
- [07 Threading and performance](../../docs/detailed-design/07-threading-and-performance.md):
  the single-writer rule and allocation discipline.
- [Diagrams: the arena and the wire](../../docs/diagrams/02-arena-and-wire.md).
