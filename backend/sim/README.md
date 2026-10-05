# sim

The simulation of one room: tanks, bullets, traps, drones and shapes on a square
map, stepped at a fixed 25 Hz, with every balance table in code. It has no
networking, no I/O, no clock and no threads of its own: `arena/RoomThread` drives
it for real players and `tools/TickBenchmark` drives it to measure a tick. One
room is touched by one thread, and a step allocates nothing in steady state.

Design: [01 Realtime arena](../../docs/detailed-design/01-arena.md), §2–§8.

## Layout

| Group | Class | What it is for |
|---|---|---|
| The step | `Room` | `step(PhaseTimer)`; spawning (`spawnTank`, `spawnShape`), firing, drones and missiles, collisions, kills, assists, experience; the operations the arena calls: `chooseClass`, `spendPoint`, `assignClass`, `setLevel`, `grantExperience`, `resetForNewMatch`, `setWalls` |
| | `World` | The fixed pool of `Entity` (capacity set at construction), a free-slot stack, one id list per kind, `tankStats[]` beside the pool, the room's `Xorshift`, the `SpatialHash`; `kill` marks, `sweep` recycles at the end of the tick |
| | `Entity` | One pooled object with public primitive fields. Kinds: `KIND_TANK`, `KIND_BULLET` (bullets, traps, drones, minions, missiles), `KIND_SHAPE`. Wire classes: `WIRE_TANK`, `WIRE_PREDICTED`, `WIRE_STATIC`, `WIRE_UNIT` |
| | `SpatialHash` | Uniform grid rebuilt every tick: `head[cell]`, `next[id]`, one cell per entity by its centre; `queryInto` returns candidates in the cells a circle covers |
| | `KillLog` | The tick's kills (killer tag, victim tag, victim kind, experience) and assists, which the room thread drains |
| Progression | `TankStats` | One per slot: level, experience, unspent points, points per stat, class, skin, equipment bonus, the assist ring; effective stats recomputed only when dirty |
| Tables | `Content` | The tables a room is built with, travelling together |
| | `Stat`, `StatTable` | The eight stats (indices are a wire contract) and how each grows, by addition or by proportion |
| | `LevelTable` | Experience per level to 45 and the points each level grants |
| | `ShapeTable` | The four shapes and their spawn weights |
| | `ClassTable` | 52 tank classes with their barrels (`Barrel`, `TankClass`), caps, body, zoom and health multipliers; `json()` and its CRC-32 `version()` |
| | `PhraseTable` | The 16 phrases a player may say; `json()` and `version()` |
| | `Recovery`, `Spawning` | Health's burst after a quiet spell; spawn protection, clearance and attempts |
| Maze | `MazeGenerator` | A maze's walls from a seed, by recursive division |
| | `Walls` | Those walls indexed by cell: `hits` ends a bullet, `pushOut` slides a body |

## One step

`Room.step` advances `tick` and runs six timed phases, in this order (the
`PhaseTimer` names in brackets):

1. **Tanks** (`tanks`): refresh effective stats; keep health in step with
   maximum health; move by input (a player's), stand still (an anchored
   dominator), hunt (co-op's tanks) or wander (a bot); `v = (v + dir × accel) × 0.90`;
   clamp to the map, then out of any wall; regenerate; count the reload down;
   size the body by class; work out the zoom shift and whether a hiding class is
   hidden; fire the barrels of the volley in progress, and start a volley when
   the reload is out and the trigger (or, without it, a drone barrel or a
   turret) asks.
2. **Bullets** (`bullets`): drones and minions steer and minions shoot;
   missiles shoot; everything else flies straight, traps slowing by 0.9 a tick;
   anything past its lifetime, off the map or touching a wall dies.
3. **Shapes** (`shapes`): drift, a knock wearing off above 0.15 units a tick,
   a cosmetic spin, clamp, walls.
4. **Hash** (`hash`): clear the grid and insert every tank, bullet and shape.
5. **Collide** (`collide`): each bullet against what is near it, then each
   tank's body against tanks and shapes (see
   [the diagram](../../docs/diagrams/02-arena-and-wire.md#collision-broadphase)).
6. **Sweep** (`sweep`): recycle the slots of everything that died this tick.

Kills are recorded in `KillLog`, never scored here: the arena's tally turns them
into score.

## Constants and tables

The shipped values; each table's comments say where its numbers came from.
Changing one is a balance change, and balance is recorded in the design doc.

| `Room` constant | Value | Meaning |
|---|---|---|
| `TANK_RADIUS`, `TANK_MASS` | 30, 10 | a tank's body at size 1 |
| `TANK_ACCEL`, `TANK_FRICTION` | 0.16, 0.90 | top speed about 1.44 units a tick at base |
| `BULLET_SPEED`, `BULLET_MASS` | 10, 1 | times the bullet-speed stat and the barrel's multiplier, rounded to half a unit |
| `TRAP_FRICTION` | 0.9 | a trap slides ten times its launch speed |
| `DRONE_ACCEL`, `DRONE_FRICTION` | 0.5, 0.9 | 4.5 units a tick at most |
| `DRONE_REACH`, `DRONE_ORBIT` | 300, 100 | along the aim while attacking; circling otherwise |
| `TURRET_REACH` | 400 | how far a turret looks |
| `HUNT_CLOSE`, `HUNT_FIRE` | 400, 600 | a hunting tank closes to, and fires within |
| `SHAPE_DRIFT_MAX`, `SHAPE_FRICTION` | 0.15, 0.9 | drift kept; a knock wears off |
| `CONTACT_DAMAGE_PER_TICK` | 1/25 | body damage is per second of contact |
| `TANK_KILL_XP_SHARE`, `TANK_KILL_XP_FLOOR` | 0.25, 10 | a tank kill's experience |
| `ATTACKERS`, `ASSIST_TICKS`, `ASSIST_XP_SHARE` | 3, 125, 0.25 | the assist ring |

| Table | Shipped |
|---|---|
| `StatTable` | regen 0.0003 of max health a tick ×(1 + 0.6·p); max health 50 + 2·level + 20·p; body damage 20 + 6·p; bullet speed 1 + 0.15·p; penetration 8 + 4·p; bullet damage 7 + 3·p; reload 8 ticks ×(1 − 0.08·p); movement 1 + 0.07·p |
| `LevelTable` | 45 levels; level 2 at 4, 10 at 160, 30 at 5 300, 45 at 23 000, geometric between; one point a level to 28, then every third level: 33 in all, 7 at most in a stat (`Stat.MAX_POINTS_PER_STAT`) unless the class says otherwise |
| `ShapeTable` | square r18 hp10 xp10 weight 600; triangle r22 hp30 xp25 weight 300; pentagon r32 hp100 xp130 weight 95; alpha pentagon r80 hp3 000 xp3 000 weight 5 |
| `Recovery` | after 750 quiet ticks (30 s), 1 % of maximum health a tick on top of the stat |
| `Spawning` | 75 ticks of protection, 500 units from any tank, 20 attempts, else the best of them |
| `ClassTable` | 0 Basic; 1–4 at level 15; 5–14, 33 (Auto 3) and 37 (Smasher) at 30; the rest of 15–48 at 45; 49 Guardian, 50 Guardian enraged, 51 Dominator, which no level opens. Ids are appended, never moved. No class keeps more than 75 bullets or 120 traps alive at full reload (D-22), which `ClassTest` holds |
| `PhraseTable` | ids 1 to 16, never reused |
| `MazeGenerator` | 10 × 10 cells of 300 units, walls 40 thick, a fifth of the 81 removed: 65 |

A barrel's `kind` is what it launches and the unit subtype it is sent as:
`BULLET` 0, `TRAP` 1, `DRONE` 2, `MINION` 3, `ROCKET` 4, `SKIMMER` 5; `CONVERT` 6
launches nothing (the Necromancer's squares become its drones) and never
reaches the wire. A `Barrel` refuses a delay outside [0, 1), a bullet lifetime
outside 1–255 ticks (it travels in a byte) and a radius over 255 units.

`ClassTable.json()` and `PhraseTable.json()` are written by hand in a fixed
order, so the same table is always the same bytes; their CRC-32 is the
`Welcome`'s `contentVersion` and `phraseListVersion`, and `platform` serves the
same JSON at `GET /v1/content/classes` and `GET /v1/content/phrases` with it as
the ETag (D-24). `healthMul` is not in the class JSON: health travels as a
fraction of the maximum.

## Things to know before changing it

- **Single-threaded, allocation-free.** Nothing here is synchronised, and a
  step must allocate nothing: `AllocationTest` runs a room of level-45 bots of
  every class and fails on a byte. Loops over `List`s use indices, not
  iterators (M-14).
- **Slots are reused at once.** `World.sweep` puts freed slots on top of a
  stack and `spawn` takes from the top, so the slot a tank died in is usually
  the next thing spawned. Anything that refers to an entity across ticks keeps
  its slot *and* `generation` (a bullet's `ownerId` and `ownerGeneration`, the
  assist ring), and credit goes by `playerTag`, a per-room counter that never
  repeats; bots share tag 0.
- **Teams.** Team 0 is no team. Two entities of one non-zero team never hurt
  each other (`Room.sameTeam`); team 1 spawns in the map's left third and team
  2 in its right.
- **Bots spend their own points.** A tank nobody drives takes a random class at
  each tier open to it and spends its points as it earns them, evenly, by its
  class's caps (`Room.credit`). The arena gives a boss or a dominator its class
  with `assignClass` before its experience, so their points follow the boss
  caps (no movement speed).
- **The RNG** is the room's own `Xorshift`. A spread is drawn only for a barrel
  that has one, so adding a spread-less barrel leaves every other draw as it
  was. The arena seeds rooms from the clock; the benchmark uses 42.

## Build and test

From `backend/` (background/CLAUDE.md):

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o install                    # everything
/opt/maven/bin/mvn -o -pl sim -am test           # this module and common
```

What a change costs a tick is measured with `TickBenchmark`, before and after on
the same machine ([tools](../tools/README.md#tickbenchmark)).

## Tests

| Test class | Tests | What it covers |
|---|---|---|
| `AllocationTest` | 1 | A room of level-45 bots of every class allocates nothing, tick after tick |
| `AssistTest` | 7 | The assist ring: five seconds, three players, a quarter of the kill, the killer not paid twice |
| `BodyContactTest` | 8 | Body damage per second of contact, both ways; tanks cannot drive through shapes; mutual kills |
| `ClassTest` | 58 | Every tier's barrels, volleys and delays, recoil, sizes, traps, drones, turrets, caps, hiding, minions, missiles, zoom, body size, the D-22 budget over the whole table |
| `ContentTest` | 11 | The level curve's anchors and monotony, 33 points, the stat and shape tables and what they refuse |
| `DominationTest` | 7 | The Dominator class; anchoring; capture by a player's bullet or body and by nobody else |
| `EquipmentBonusTest` | 3 | The equipment bonus and the skin: applied, kept through levels and a resume, gone with a new slot |
| `HuntTest` | 6 | A hunting tank's closing, firing and choice of quarry |
| `KillAttributionTest` | 9 | Credit by tag and generation when a shooter's slot has been reused |
| `MazeGeneratorTest` | 4 | Determinism, 81 and 65 walls, every cell reachable, the golden vector `maze-2026.txt` the C# client reads |
| `PhraseTableTest` | 4 | The shipped sixteen, `contains`, fixed JSON bytes and CRC-32, lists refused |
| `ProgressionTest` | 14 | Experience over several levels, the top level, spending points and caps, dirty recomputation |
| `RecoveryTest` | 3 | The quiet half minute and what restarts it |
| `RoomTest` | 8 | Slot reuse and id lists, the pool refusing to grow, the spatial hash |
| `SetLevelTest` | 5 | The sandbox's level power |
| `SpawnTest` | 7 | Spawn clearance, never inside a shape, protection to the tick |
| `TeamTest` | 5 | Team rules for bullets, bodies and traps; team 0 as no team |
| `WallsTest` | 9 | Bodies stopped and sliding, drones stopped, bullets ended, by walls |

`Fixtures` is a helper: content with no spawn protection, for tests that are
not about arriving.

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
