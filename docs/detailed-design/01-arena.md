# 01 — Realtime Arena (rooms, simulation, match rules)

The arena process hosts battle rooms. Everything in this document runs inside a
single room thread; see [02-networking.md](02-networking.md) for how bytes get
in and out and [07-threading-and-performance.md](07-threading-and-performance.md)
for the thread model.

## 1. Room model

```
ArenaProcess
 ├── RoomRegistry            (roomId → Room; heartbeat to j-redis every 2 s)
 ├── Room "ffa-7"  ── RoomThread (1 dedicated thread, fixed timestep 25 Hz)
 │     ├── World             (entities, spatial hash, map bounds, walls)
 │     ├── MatchMode          (FFA | Teams2 | Teams4 | Tag | Maze | Domination | Sandbox | Mvm)
 │     ├── PlayerSessions    (connection ↔ tank; view rect; last input seq)
 │     ├── InputQueue        (MPSC, filled by Netty IO threads)
 │     ├── SnapshotEncoder   (per-client delta encoder, pooled buffers)
 │     └── RoomEvents        (kill feed, level-ups, mode events → clients + event stream)
 ├── Room "teams2-3" ── RoomThread
 └── ...
```

**As built** the tree is `RoomRegistry` → `RoomThread` → `sim.Room` (`World`, the
spatial hash, the step), with `MatchRules` and `MatchTally` in place of
`MatchMode`, one `Connection` and `ClientView` per player in place of
`PlayerSessions`, and the latest input in an atomic slot on the connection
rather than a queue ([07 §1](07-threading-and-performance.md#1-thread-inventory-arena-process)).
The directory entry is refreshed every 3 s, not 2. Walls (§8.9) and the modes
of §8 are built. Not built: room creation by `platform`: the arena makes a
match's room from the first of its tickets (D-20), and its public rooms itself
as players arrive ([D-5](../architecture/03-decision-log.md)).

A room is created by `platform` (see the rooms module in [04-platform-services.md](04-platform-services.md))
via a j-redis pub/sub command channel, or lazily by the arena for public FFA when
capacity allows. A room lives as long as its mode says (FFA: forever; ranked /
tournament: until result; sandbox: as designed, until empty for 5 min). **As
built** a made match's room closes when its match ends, and a sandbox's at 20
minutes or after a minute with nobody in it (§8.10).

### Tick loop

```java
public final class RoomThread extends Thread {
    static final long TICK_NANOS = 40_000_000L; // 25 Hz
    private final Room room;

    public void run() {
        long next = System.nanoTime();
        while (room.isAlive()) {
            long now = System.nanoTime();
            if (now < next) { LockSupport.parkNanos(next - now); continue; }
            int catchUp = 0;
            while (now >= next && catchUp < 2) {   // never spiral: at most 2 ticks per wakeup
                room.tick();
                next += TICK_NANOS;
                catchUp++;
            }
            if (now >= next) {                      // still behind → drop time, log once
                room.metrics.tickOverrun++;
                next = now + TICK_NANOS;
            }
        }
        room.shutdown();
    }
}
```

**As built, the catch-up rule differs** (checked 2026-09-26): a room less than
10 ticks (400 ms) behind runs its missed ticks back to back, sending the
snapshots they fall due for in a burst; beyond that it drops the time and counts
one overrun (`backend_arena_tick_overruns_total`). The two-tick cap above was not
built. How far behind a room runs short of that shows in
`backend_arena_tick_p99_seconds`.

`Room.tick()` order matters and is fixed:

```
1. drainInputs()          apply every queued client message (join, input, upgrade, chat…)
2. mode.preTick()         mode-specific spawns (dominators, MvM waves)
3. spawnShapes()          keep shape population at target density
4. updateTanks()          movement from input, regen, reload timers, auto-fire
5. updateBullets()        movement, lifetime, despawn
6. updateShapes()         drift, rotation, regen
7. rebuildSpatialHash()   clear + insert all live entities (cheap: it's arrays)
8. collide()              broadphase via grid, narrowphase circles, apply damage & knockback
9. resolveDeaths()        XP transfer, kill feed, mode.onKill, schedule respawn
10. mode.postTick()       win conditions, scores, tag conversions
11. broadcast()           build & send per-client snapshots + events (every tick)
12. housekeeping()        every N ticks: room heartbeat to j-redis, stats, idle checks
```

**As built**, `Room.step` runs tanks, bullets, shapes (drift and knockback only:
shapes do not regenerate), the spatial hash and the collisions, with deaths
resolved inside the collisions, then recycles the slots of what died. The room
thread around it (`RoomThread.tick`), in order:

```
before the step   operators' removals, leaves, resumes, joins; input; points and
                  classes asked for; a sandbox's powers
the step          Room.step
after the step    a tank just backgrounded parked; the tally takes the tick's
                  kills (and tag its conversions);
                  deaths; the kill feed; stays waiting for a resume; respawns;
                  progression (Stats); phrases
the lifecycle     open: a checkpoint once a second; made: the match's stage and
                  its mode (waves, dominators, the sandbox, tag); timed: the
                  match's end
last              each client's snapshot round, at its own rate
```

There is no `MatchMode` interface (§8.2): the modes are `MatchRules`,
`MatchTally` and the helpers `Waves`, `Domination`, `Tag` and `Sandbox`, called
from the room thread ([02 §8](02-networking.md#8-traffic-profiles) for the
rounds).

## 2. Entities

Use plain pooled objects with primitive fields and an `int id`. No interfaces on
the hot path, no `instanceof` chains. Every entity is one of a small closed set
of *kinds*:

```java
public final class Entity {
    // identity
    public int   id;            // slot index in World.entities (stable while alive)
    public byte  kind;          // TANK, BULLET, SHAPE, WALL, DOMINATOR, DRONE, TRAP
    public short generation;    // bumps on reuse; detects stale references
    public byte  wireClass;     // TANK | PREDICTED | STATIC — decides client behaviour (D-9)
    public byte  team;          // 0 = none; FFA uses ownerId for "team"
    public int   ownerId;       // for bullets/drones: tank id that fired; -1 otherwise

    // physics (world units, fixed-point on wire, float in memory)
    public float x, y, vx, vy, radius, angle;
    public float mass;          // knockback; shapes heavier than bullets
    public boolean pushable;

    // combat
    public float hp, maxHp, regenPerTick, damage;   // damage applied to *others* on contact
    public int   lastHitTick, lifetimeTicks;         // bullets: ticks remaining
    public int   protectedUntilTick;                 // spawn protection

    // kind-specific payload (union style: only one used)
    public TankState  tank;     // null unless kind == TANK
    public ShapeType  shape;    // null unless kind == SHAPE

    // bookkeeping
    public boolean alive;
    public int dirtyMask;       // which wire fields changed this tick (see 02-networking)
}
```

`World` holds `Entity[] entities` (capacity fixed at room creation, e.g. 8 192),
a free-list of slot indices, and typed index lists (`IntArrayList tanks,
bullets, shapes`) so update passes iterate only what they need.

```java
public final class World {
    public final Entity[] entities;
    private final int[] freeSlots; private int freeTop;
    public final IntList tanks = new IntList(256), bullets = new IntList(4096), shapes = new IntList(2048);
    public final SpatialHash grid;
    public float width, height;      // map bounds
    public int tick;

    public Entity spawn(byte kind) {
        int slot = freeSlots[--freeTop];       // as built, null on exhaustion → room refuses more spawns
        Entity e = entities[slot];
        e.reset(); e.id = slot; e.kind = kind; e.generation++; e.alive = true;
        listFor(kind).add(slot);
        return e;
    }
    public void kill(Entity e) { e.alive = false; pendingRemoval.add(e.id); } // removed after collide()
}
```

### TankState

```java
public final class TankState {
    public int   playerId;            // -1 for bots
    public String name;               // display; sent once on create
    public int   level, xp, score;    // score == total xp in diep terms
    public byte  colorId;
    public byte  tankClassId;         // index into TankTable
    public final byte[] statPoints = new byte[8];   // 0..7 each
    public byte  unspentPoints;
    public EffectiveStats eff = new EffectiveStats();     // recomputed when dirty
    public boolean statsDirty = true;
    public Barrel[] barrels;          // from TankTable; cooldown per barrel
    public int[]   barrelCooldown;
    public float aimAngle; public byte moveMask; public boolean firing, autoFire, autoSpin;
    public int   lastInputSeq;        // echoed in snapshot for client prediction
    public int   respawnAtTick = -1;
    public float viewW, viewH;        // FOV grows with level / class
    public final ModifierList modifiers = new ModifierList();  // equipment/boosts/effects
}
```

### Wire classification (D-9)

Every entity carries a `wireClass` that decides what the client does with it,
and it is the **only** thing the client uses to choose behaviour:

As built (`sim/Entity.WIRE_*`, protocol 4):

| `wireClass` | What has it | Client behaviour |
|---|---|---|
| `TANK` (0) | every tank: players', bots, co-op's hunters and Guardian, dominators | interpolated between streamed updates |
| `PREDICTED` (1) | bullets, a minion's and a missile's shots included | extrapolated from its create; never updated |
| `STATIC` (2) | shapes | a position update when it moves; rotates locally |
| `UNIT` (3) | traps, drones, minions and missiles (subtypes 1 to 5) | updated as a tank is: `POS`, `ANGLE`, `HP` |

The design put drones under `TANK` and traps under `PREDICTED`, and walls under
`STATIC`. Neither a trap, which slows to a stop and is knocked, nor a drone,
which steers, can be extrapolated, so protocol 3 gave them a class of their own
([02 §6](02-networking.md#entities-that-are-not-predictable)); walls are not
entities at all and are never sent, a maze's being made from its seed (§8.9).
Keeping the decision in one byte
means an entity type can be reclassified server-side without a client release,
which matters because "is this projectile predictable enough" is a balance
question that will change
([02-networking §6](02-networking.md#6-client-side-simulation)).

The simulation runs at **25 Hz** while each client is sent snapshots at its
traffic profile's rate, **15 Hz**, or 10 at `saver`
([D-10](../architecture/03-decision-log.md#d-10--tanks-drive-the-snapshot-rate-at-15-hz),
[02 §8](02-networking.md#8-traffic-profiles)), counted from when it joined. Any
tick encodes for some clients and not others, so the encoder must not assume
one snapshot per tick.

## 3. Stats and modifier pipeline

**Built**, minus the modifier pipeline — see the end of this section for what is
deliberately absent and why. `Stat`, `StatTable`, `TankStats` and `Content` in
`sim`; `Room` applies them.

The eight upgradeable stats plus a few derived ones:

| Index | Stat | Base (diep-like) | Per point | Notes |
|---|---|---|---|---|
| 0 | HEALTH_REGEN | 0.03 % maxHp/tick + burst regen after 30 s no damage | ×(1 + 0.6·p) | the burst built 2026-09-26: after 30 s without losing health to a bullet or a body, a further 1 % of maxHp a tick, a full heal in about four seconds (`sim/Recovery`, a table like the others; the rate is a starting point, the design named none) |
| 1 | MAX_HEALTH | 50 + 2·level | +20 | |
| 2 | BODY_DAMAGE | 20 | +6 | applied on tank-vs-anything contact: **per second of contact** against tanks and shapes, and per hit against a bullet ([§6](#6-spatial-hash-and-collisions)). Until 2026-09-26 only the bullet part existed ([M-8](../defects.md#3a-simulation)) |
| 3 | BULLET_SPEED | class base (e.g. 1.0) | +0.15 | multiplies barrel speed |
| 4 | BULLET_PENETRATION | class base (e.g. 8 hp) | +4 (a guess, as built) | bullet's hp; bullet dies when hp ≤ 0 |
| 5 | BULLET_DAMAGE | class base (e.g. 7) | +3 | |
| 6 | RELOAD | class base cooldown ticks | ×(1 − 0.08·p) | |
| 7 | MOVEMENT_SPEED | ×1.0 | +0.07 | multiplies the acceleration; top speed about 1.44 units/tick at base |

Exact numbers are balance data: `StatTable.defaults()` holds them, in code; loading
them from `stats.json` is not built ([plan](../plan.md#1-status)).

All stat sources (level, skill points, tank class, equipment, boosts, in-match
effects) are folded through one pipeline into `EffectiveStats`, recomputed only
when something changes (dirty flag), never per tick:

```java
public final class StatModifier {
    public byte  stat;        // 0..7 or derived stat ids
    public byte  op;          // ADD_FLAT, ADD_PERCENT, MUL
    public float value;
    public int   source;      // EQUIPMENT_SLOT_x, BOOST_id, EFFECT_id — for removal & UI
    public int   expiresTick; // Integer.MAX_VALUE for permanent
}

void recompute(TankState t, int level) {
    for (int s = 0; s < 8; s++) {
        float base = StatTable.base(s, level, t.tankClassId) + StatTable.perPoint(s) * t.statPoints[s];
        float flat = 0, pct = 0, mul = 1;
        for (StatModifier m : t.modifiers.forStat(s)) {      // ModifierList is stat-bucketed arrays
            if (m.op == ADD_FLAT) flat += m.value;
            else if (m.op == ADD_PERCENT) pct += m.value;
            else mul *= m.value;
        }
        t.eff.values[s] = (base + flat) * (1 + pct) * mul;
    }
    t.eff.deriveCaches();   // e.g. reload ticks per barrel, regen per tick, view size
    t.statsDirty = false;
}
```

Order is fixed: flat adds, then summed percentages, then multipliers. Boosts and
equipment become `ADD_PERCENT`/`ADD_FLAT` modifiers with permanent expiry;
in-match effects (e.g. "Adrenaline: +25 % reload for 10 s", "Slowed") are the
same objects with a real `expiresTick`. `ModifierList.expire(tick)` runs once
per second and sets `statsDirty`.

### What was built, and what was not

`StatTable` has **two growth modes, not a modifier language**:

```
ADD    value = base + perLevel·level + perPoint·points
SCALE  value = base · (1 + perPoint·points)
```

Six stats add and two scale. `RELOAD` is why `SCALE` exists: fewer ticks
between shots is better, so its per-point value is negative, and a flat
subtraction reaches zero and then passes through it.

**`StatModifier` and `ModifierList` are not built.** Equipment, boosts and
in-match effects are all Phase 5, so the pipeline above has nothing to fold in
and would be an abstraction with exactly one implementation — the empty one.
The hook point is `TankStats.refresh`, between the table and the effective
value, and it is the only place that has to change.

### Equipment's bonus (designed 2026-09-30, plan item 15)

**Built 2026-09-30.** Built for what exists, equipment, and no more: a bonus a stat, a whole percent,
on `TankStats`, which `refresh` applies after the table, `value × (1 + b/100)`,
and for `RELOAD`, whose ticks fall as it improves, `value ÷ (1 + b/100)`: +8 %
reload is 8 % more shots. It is part of the dirty state, so a tick pays nothing
for it. The arena sets it from the player's ticket each time their tank spawns
(04 §8); a resume keeps it (`copyFrom`); a bot has none; a death, a level or a
point spent leaves it as it is. The modifier list sketched above, with its
operations, sources and expiry, waits for a second kind of source, boosts or
in-match effects, rather than being built for one.

`TankStats` lives **beside** the entity pool rather than as a field on `Entity`,
in `World.tankStats[]` indexed by slot. Bullets outnumber tanks by an order of
magnitude, and a dozen progression fields on every bullet would push the object
the hottest loop walks out of its cache line to carry state it can never use.
One is created the first time a slot holds a tank and reset thereafter, so a
room that has run for an hour allocates none.

**Progression is per life; score is per match.** Dying resets level, experience
and points. The player's match score — kept by the arena's tally, not the
simulation — keeps counting across lives. Two different questions: "how strong
am I now" and "how well has this stay gone". Answering both with one number
means either death costs nothing, or the leaderboard forgets everything before
a player's last mistake.

## 4. Levelling, XP, tank tree

**Levelling, experience and the tank tree are built** (the tree since
2026-09-27, below): `UpgradeStat` and `ChooseClass` both reach the simulation,
through `Room.spendPoint` and `Room.chooseClass`, and a request the tank cannot
honour is refused silently.

The curve (`sim/LevelTable`) hits four anchors exactly, level 2 at 4
experience, 10 at 160, 30 at 5 300 and 45 at 23 000, and interpolates
geometrically between them. No single power law passes through all four: one
fitted to the first two gives about 1 985 at level 30, undershooting it by a
factor of nearly three. The anchors are the claim;
whether the *pace* is right is a question for somebody who has played a match,
and the table exists so that answering it is a data change rather than a
release.

Shapes are where nearly all early experience comes from, so the spread of them
is the early pacing curve:

| Shape | Radius | Health | Experience | Share of spawns |
|---|---|---|---|---|
| Square | 18 | 10 | 10 | 60 % |
| Triangle | 22 | 30 | 25 | 30 % |
| Pentagon | 32 | 100 | 130 | 9.5 % |
| Alpha pentagon | 80 | 3 000 | 3 000 | 0.5 % |

One shape in two hundred is an alpha. Killing one alone takes a level-1 tank
to level 26, which is either the best thing in the mode or badly overtuned,
and only play will say which.

A tank kill is worth **a quarter of what the victim had earned**, with a floor
of 10 so a fresh spawn is not worth nothing. A share rather than a flat amount
so hunting beats farming when there is something worth hunting, and does not
when there is not.

Bots spend their points as they earn them. That is not cosmetic: reload is a
stat and bullet count is what a tick costs, so a load measurement taken from
bots that banked their points would understate the real thing.

### The barrel model and the first tier (designed 2026-09-27, plan item 5)

**Built 2026-09-27**, as below: `sim/ClassTable`, the volleys in `sim/Room`, the
choice through the arena, and the class and exact bullet speed on the wire.

A class is a row in a content table, as the stats and levels are, not code:
`ClassTable`, code-defined like the others until content moves to files.

| Field | Meaning |
|---|---|
| `id` | u8 on the wire; 0 is Basic, what every tank starts and respawns as |
| `opensAt` | the level at which it can be chosen: 15, 30, 45 |
| `parent` | the class it upgrades from |
| `fovMul` | the player's view, 1 600 units × this |
| `reloadMul` | on the reload the stat gives |
| `barrels` | one or more, below |

A **barrel** has an angle from the aim, a sideways offset from the tank's
centre line, a **delay** as a fraction of the reload, and the bullet it fires,
as multipliers on what the stats give: speed, damage, penetration; and a
spread, a random angle either side of its line.

**A volley** starts when the reload has run out and the trigger is held, and
each barrel fires once in it, at its delay: a Twin's second barrel half a
reload after the first. A volley once started completes; letting go of the
trigger stops the next, not this one. A delay is rounded to whole ticks of the
reload the volley began with, so it is at most that reload, and a volley has
always finished when the next may begin.

**Choosing** is `ChooseClass(classId)` (02 §3), accepted when the class's parent
is the tank's current class and the tank has reached its level, and otherwise
refused silently, as a spent point beyond the cap is: whether it is allowed
depends on the tank, and a client a release ahead should see a request that does
nothing, not a closed socket. It takes effect before the tick's step, as a point
does, and drops a volley in progress, whose remaining barrels were the old
class's. A death returns the tank to Basic, as it returns it to level 1; a
resume keeps the class. Bots choose at random among what is open to them, so
that a load measurement sees the bullets real players' classes fire. A spread is
drawn from the room's random sequence only for a barrel that has one, so the
others leave that sequence as it was.

**The first tier**, at level 15, numbers as unplayed as the rest:

| Class | Barrels | Bullet | Reload | View |
|---|---|---|---|---|
| Basic | 1 ahead | as the stats give | × 1 | × 1 |
| Twin | 2 ahead, 20 units apart, the second at half a reload | damage × 0.65 | × 1 | × 1 |
| Sniper | 1 ahead | speed × 1.5, lifetime 90 ticks | × 1.5 | × 1.2 |
| Machine Gun | 1 ahead | damage × 0.7, spread ± 0.17 rad | × 0.5 | × 1 |
| Flank Guard | 1 ahead, 1 behind | as the stats give | × 1 | × 1 |

**What it changes on the wire** (02 §4, protocol version 2): a tank's create
carries its class, and an update carries it again when it changes; and a
bullet's speed is carried exactly. The stats and a barrel's multiplier make
speeds the old eight-entry table could not hold, and a client extrapolating a
bullet at the wrong speed draws it in the wrong place for its whole life. The
simulation now fires every bullet at a speed rounded to half a unit a tick, and
the create carries that speed in half units, so there is nothing left to round.

**Not built** in this slice: tiers 30 and 45, drones and traps (which are not
predictable, D-9), recoil, and the client drawing barrels, which needs the class
table on the device, versioned by `contentVersion` in `Welcome`. All built since
but the drawing, which is the Unity layer's: the tiers, traps, drones and recoil
below, and the class table served to clients (D-24).

### The second tier: sizes, recoil, traps and drones (designed 2026-09-27, plan item 5)

The first tier was rows in a table. The second changes three things the first
did not, and they come before the rest of the tree because they are what could
invalidate the wire and its budget: **bullets of more than one size**, **a tank
pushed by its own shots**, and **two projectiles a client cannot extrapolate from
a create** — a trap slows to a stop, a drone is steered.

**The table grows.** A class may come from **more than one parent** (Quad Tank and
Twin Flank from Twin or Flank Guard); each parent is still a class before it. A
barrel gains a **kind** (bullet, trap or drone), a **size** (× the bullet radius
of 8) and a **recoil**: how hard the shot pushes the tank back along the barrel's
line, in world units a tick, 0 unless the table says so, so no class so far
moves differently. A class gains **maxDrones**.

**Traps** are laid at the barrel's speed and slowed by 0.9 a tick, so a trap
slides ten times its launch speed in units and stops. It lives 600 ticks (24 s),
and hurts and is used up as a bullet is. It is not knocked.

**Drones.** Each drone barrel launches one per reload while its tank has fewer
than `maxDrones`. A drone steers toward a point: with the trigger held (always,
for a bot) the point **300 units along its owner's aim**; otherwise a point
circling the owner at 100 units. It accelerates 0.5 a tick toward it with
friction 0.9, a top speed of 4.5 units a tick. It hurts and is used up as a
bullet is, and otherwise lives as long as its owner stays the tank it was: a
death, a class change or a leave takes its drones with it.

**A drone goes a reach along the aim, not to a point**
([D-21](../architecture/03-decision-log.md#d-21--drones-go-a-fixed-reach-along-the-aim)):
`Input` carries a direction, and a phone's stick gives a direction far more
naturally than a point. An aim distance could be added to `Input` later, a byte,
and nothing else would change.

**What it changes on the wire: protocol 3** ([02 §4](02-networking.md#4-the-snapshot)):

- **A predicted create gains `u8 radius`**, in world units. A Destroyer's bullet
  is twice a Basic's, and a client drawing every bullet at 8 draws it wrong for
  its whole life, the error D-9 exists to avoid.
- **A new kind, `UNIT`**, for traps and drones: `pos x, y, angle, hp, u8 subtype
  (1 trap, 2 drone), u8 team, handle owner`, updated as a tank is (`POS`,
  `ANGLE`, `HP`), since neither can be extrapolated. **D-9 said this would be
  needed**: "entity classes that are not predictable (steering drones) need a
  flag and keep receiving updates". A trap costs updates while it slides,
  about two seconds until its steps are smaller than a position's quarter unit,
  and nothing once still; a drone costs one every frame, since it
  never stops: eight drones cost about what eight tanks do. Units rank with
  bullets, after tanks and before scenery: things that hurt come first.

**The second tier, at level 30**, numbers as unplayed as the first's:

| Class | From | Barrels |
|---|---|---|
| Triple Shot | Twin | 3 ahead, at 0 and ±0.5 rad; damage × 0.7 |
| Quad Tank | Twin, Flank Guard | 4, a quarter turn apart |
| Twin Flank | Twin, Flank Guard | a Twin ahead and one behind |
| Assassin | Sniper | 1; speed × 1.8, lifetime 110; reload × 2; view × 1.4 |
| Hunter | Sniper | 2 on one line, the second a fifth of the reload later and size × 0.75; speed × 1.5, damage × 0.75; reload and view as Sniper's |
| Overseer | Sniper | 2 drone barrels, at ±90°, the second half a reload later; launched at speed × 0.5, size × 1.25, damage × 0.7, penetration × 2; 8 drones at most; reload × 1.5; view × 1.1 |
| Trapper | Sniper | 1 trap barrel: laid at speed × 0.8, size × 1.5, penetration × 2; reload and view as Sniper's |
| Destroyer | Machine Gun | 1; size × 2, damage × 3, penetration × 2, speed × 0.7; reload × 4; recoil 1.5 |
| Gunner | Machine Gun | 4 small, 5 and 10 units either side; size × 0.6, damage × 0.5; a quarter of the reload apart |
| Tri-Angle | Flank Guard | 1 ahead; 2 behind at ±150°, damage × 0.5, recoil 0.5 each: thrust |

**Part (a) is built, 2026-09-27**: the table's fields and two parents, recoil,
bullet sizes, protocol 3 on both sides and in the golden vectors (a new case,
`second_tier`), and the eight bullet classes; bots take them past level 30.
**Part (b) is built**: traps and the Trapper, a trap sent as a unit and
followed by the reference client exactly while it slides. **Part (c) is
built**: drones and the Overseer, which keeps its drones out whether or not the
trigger is held, as the table says. Whether the trigger is held is its own
flag on the tank, `attacking`, set from the latest input's fire flags: the
flag a shot fires by is a latch that each shot clears, and says nothing about
the tick after. **Part (d) is measured**: in a room of 150 level-45 tanks of the
second tier, all firing, the bullets double to 2 250 and the simulation's p99
reaches 2.9 ms on the development VM, over NFR-1a's 2 ms; a room grown from
level 1 is 1.2 ms. The payload rises from 82 to 92 bytes, 8.4 MB an hour. Open,
against Q-3 ([backend README](../../backend/README.md#what-the-second-tier-costs-2026-09-27)).

**Built in this order**, each part with its tests: (a) the table's new fields,
more than one parent, recoil and bullet size, protocol 3 on both sides with the
golden vectors, and the tier's bullet classes; (b) traps; (c) drones; (d) bots
taking the tier, and what it costs the tick and the budget, measured.

**Not in this slice**: tier 45 (the Overlord, Necromancer, the Factory's
minions, auto turrets, and the Smasher line, which has no barrels at all),
knocking traps, and the client drawing any of it. All but the drawing were built
in the next two slices, [the third tier](#the-third-tier-turrets-bodies-hiding-and-minions-designed-2026-09-28-plan-item-5)
and [the rest of the tree](#the-rest-of-the-tree-designed-2026-09-28-plan-item-5).

### The third tier: turrets, bodies, hiding and minions (designed 2026-09-28, plan item 5)

Most of the tier at level 45 is rows. Five things are not, and they are what
this section is about: **a budget the rows must fit**, **a unit's size on the
wire**, **barrels that aim themselves**, **classes with no barrels**, and
**tanks the others cannot see**. Then two kinds of drone: squares made into
drones, and minions that shoot.

**The rows must fit a budget**
([D-22](../architecture/03-decision-log.md#d-22--the-third-tier-adds-kinds-of-projectile-not-more-of-them)).
The second tier's worst case is already over NFR-1a on the development VM
(Q-3), and what a tick costs is what is alive: an Octo Tank at diep's reload
keeps twice a Quad Tank's bullets in the air. So **no class keeps more alive
than the most any class of the second tier does**: at full reload (seven
points, 3.52 ticks, rounded as the room rounds it for the class), **75 bullets**
(a Quad Tank's, a Twin Flank's, a Gunner's) and **120 traps** (a Trapper's).
Bullets are counted from every barrel, turret and minion; each barrel keeps its
lifetime divided by the reload alive. A class with more barrels reloads
slower, which is how the original balances most of them anyway. A test over
the table holds it, so a row that breaks it fails the build.

**A unit's create carries its radius: protocol 4** ([02 §4](02-networking.md#4-the-snapshot)).
Protocol 3 gave a bullet's create its radius and left a unit's out, on the
reasoning that a client can look a trap up by its owner's class. It cannot: a
trap outlives its owner, whose handle is then gone, and this tier's Mega
Trapper lays traps of another size and the Necromancer's drones are squares.
One byte at the end of a unit's create, as a predicted one has.

**What fires without the trigger.** A volley starts when the reload has run
out and something in it is due: every barrel when the trigger is held (always,
for a bot), and otherwise only those that fire by themselves, drone barrels and
turrets. Until now a volley without the trigger fired a whole class, which was
right only while the one class that fired without it had nothing but drone
barrels; a Hybrid would have fired its Destroyer barrel on its own. A class
whose barrels are all ordinary starts no volley without the trigger, as now,
and a class with no barrels starts none at all. **A volley begun without the
trigger takes in the rest of the class if the trigger is pulled during it**:
the drones start a volley every reload whether or not one launches, so without
this a Hybrid's first shot would wait out up to a reload that its drones began
and nothing used. Each barrel still fires once a volley at most.

**Turrets.** A barrel may **aim itself**: it has an arc, a half-angle either side
of its line, and at its turn in a volley it fires at the nearest target within
400 units of the tank and inside its arc, from the tank's centre, straight at
it. Tanks come before shapes; not itself, not its team (when it has one), not a
tank in its spawn protection, and not a hidden one (below). With no target it
does not fire, and its turn passes. An Auto 3's three turrets each watch a third
of the circle; a class with one turret watches all of it. **A turret's aim is
not sent**: its shots are ordinary bullets, carrying their own heading, and a
client draws a turret pointing where it last fired.

**Classes with no barrels: the Smasher line.** A class may have **no barrels**;
it fights with its body. A class may also set its own **caps on skill points**:
the Smasher's are 10 in health regeneration, maximum health, body damage and
movement speed, and 0 in the four that make bullets, which is 40 points of room
for the 33 a tank earns. Spending checks the class's cap; points already spent
in a stat a new class caps lower stay where they are, as they do in the
original, and do nothing. Bots spend by the caps of the class they are. A class
may multiply the **body damage** its stat gives (the Spike's, × 1.5). The
Smasher opens at level 30 from Basic, as in the original, so the second tier
gains it now.

**Hidden tanks**
([D-23](../architecture/03-decision-log.md#d-23--a-hidden-tank-is-not-sent)).
A class may **hide**: a tank of it that has neither moved nor held the trigger
for 50 ticks (2 s) is hidden, until it does either. A hidden tank is **left out
of every other player's snapshot**, exactly as if it were out of their view, so
they are sent a remove and later a create. Its bullets and drones are not
hidden. Its own player is told by a new tank flag, `TANK_FLAG_HIDDEN` (2). The
server does not send an alpha and trust the client to fade it: a position every
client receives is a position a modified client draws. Bots move all the time
and never hide. Turrets do not aim at a hidden tank; bullets still hit one.

**Squares made into drones: the Necromancer.** A barrel kind that launches
nothing, **convert**: a square its tank breaks, by a drone or its body, becomes
one of its drones where the square was, while the tank has fewer than its
`maxDrones`, made as the barrel would make a drone. The square is still paid for
and still replaced, so the shapes keep their number.

**Minions: the Factory.** A barrel kind **minion**: a drone that shoots. It
steers as a drone does and, while its tank attacks, fires a bullet along its
tank's aim every two of its tank's reloads (size × 0.6, damage × 0.4, lifetime
60 ticks). Its bullets are its tank's, for every rule that asks whose a bullet
is. It is a unit of subtype 3, and counts against `maxDrones` as a drone does.

**The third tier**, numbers as unplayed as the others'. Ids are in the order
they are built, which is the only order the table needs: a parent before its
children. A row's reload and view are its first parent's unless it says
otherwise.

| Id | Class | From | Barrels, and what else |
|---|---|---|---|
| 15 | Triplet | Triple Shot | 3 ahead, 10 apart, the outer two at half a reload; damage × 0.6 |
| 16 | Penta Shot | Triple Shot | 5 at 0, ± 0.35, ± 0.7 rad, the inner pair a third of a reload later, the outer two thirds; damage × 0.55; reload × 1.3 |
| 17 | Spread Shot | Triple Shot | 11, at 0 and ± 0.25 rad steps to ± 1.25, each step a sixth of a reload later; damage × 0.5; reload × 3.2 |
| 18 | Octo Tank | Quad Tank | 8, an eighth of a turn apart, every other at half a reload; reload × 2.3 |
| 19 | Triple Twin | Twin Flank | 3 Twins, a third of a turn apart; damage × 0.6; reload × 1.6 |
| 20 | Ranger | Assassin | 1; speed × 2, lifetime 130; reload × 2; view × 1.6 |
| 21 | Predator | Hunter | 3 on one line, at 0, 0.15 and 0.3 of a reload, sizes 1, 0.75, 0.55; speed × 1.5, damage × 0.75, lifetime 90; reload × 1.5; view × 1.4 |
| 22 | Streamliner | Hunter | 5 on one line, a fifth of a reload apart; size × 0.6, damage × 0.4, speed × 1.5, lifetime 90; reload × 1.6 |
| 23 | Sprayer | Machine Gun | a Machine Gun's barrel, and a small one (size × 0.6, damage × 0.3, spread ± 0.1) at half a reload; reload × 0.5 |
| 24 | Annihilator | Destroyer | 1; size × 2.6, damage × 4, penetration × 2.5, speed × 0.7; reload × 4; recoil 2.5 |
| 25 | Booster | Tri-Angle | 1 ahead; 4 behind, at ± 150° and ± 135°, the second pair at half a reload; damage × 0.5 and recoil 0.5 each; reload × 1.3 |
| 26 | Fighter | Tri-Angle | 1 ahead; 2 at ± 90°, damage × 0.8; 2 behind at ± 150°, damage × 0.5, recoil 0.5; reload × 1.3 |
| 27 | Overlord | Overseer | 4 drone barrels, a quarter turn apart, as the Overseer's; 8 drones |
| 28 | Tri-Trapper | Trapper | 3 trap barrels, a third of a turn apart; traps last 400 ticks; reload × 2.9 |
| 29 | Mega Trapper | Trapper | 1 trap barrel; size × 2.5, damage × 1.5, penetration × 3; reload × 2.2 |
| 30 | Gunner Trapper | Gunner, Trapper | 2 small ahead (size × 0.6, damage × 0.5, 5 either side, the second at half a reload); a Trapper's trap barrel behind; reload × 1.5 |
| 31 | Overtrapper | Trapper, Overseer | a Trapper's trap barrel; 2 drone barrels at ± 135°; 2 drones; reload × 1.5 |
| 32 | Hybrid | Destroyer | a Destroyer's barrel; 1 drone barrel behind; 2 drones |
| 33 | Auto 3 | Flank Guard, **at level 30** | 3 turrets, a third of a turn apart, each watching ± 60°; reload × 1 |
| 34 | Auto 5 | Auto 3 | 5 turrets, a fifth of a turn apart, each watching ± 36°; reload × 1.3 |
| 35 | Auto Gunner | Gunner | a Gunner's 4, and a turret watching all round; reload × 1.4 |
| 36 | Auto Trapper | Trapper | a Trapper's trap barrel, and a turret watching all round; reload × 1.5 |
| 37 | Smasher | Basic, **at level 30** | none; caps 10 in regeneration, health, body damage and movement, 0 in the rest |
| 38 | Spike | Smasher | none; the Smasher's caps; body damage × 1.5 |
| 39 | Auto Smasher | Smasher | a turret watching all round; caps 10 in the body's four, 7 in the rest |
| 40 | Stalker | Assassin | an Assassin's barrel, reload and view; hides |
| 41 | Manager | Overseer | 1 drone barrel ahead, as the Overseer's; 8 drones; hides |
| 42 | Landmine | Smasher | none; the Smasher's caps; hides |
| 43 | Necromancer | Overseer | 1 convert barrel (size × 1.5, damage × 0.7, penetration × 2); 12 drones |
| 44 | Factory | Overseer | 1 minion barrel (size × 1.5, damage × 0.7, penetration × 2); 6 minions |

Every row keeps within the budget, and the tightest are exact: an Octo Tank
reloads in 8 ticks and keeps 8 × 75 / 8 = 75 bullets out. The Necromancer keeps
12 drones rather than the original's twenty-odd because a drone costs on the
wire what a tank does (02 §7).

**Built in this order**, each part with its tests, and bots take each class as
it arrives: (a) protocol 4, the unit's radius, on both sides and in the golden
vectors; (b) what fires without the trigger, the budget test, and the rows that
need nothing new (15–32); (c) turrets (33–36); (d) no barrels, caps and body
damage (37–39); (e) hiding (40–42); (f) the Necromancer and the Factory (43,
44); (g) what the tier costs the tick and the budget, measured.

**Part (a) is built, 2026-09-28**: protocol 4 on the server, both reference
readers, the C# client and the golden vectors (`second_tier`, 52 bytes), with
the missing radius registered as [P-30](../defects.md#2-protocol--the-client-contract).
**Part (b) is built**: the eighteen rows that need nothing new (15–32), a
volley without the trigger firing only drone barrels, and joining one when the
trigger is pulled during it. Building it found that joining at the very tick
the volley ended fired the class twice in that tick; it joins only while the
volley has reload left. The budget is a test over the table, and it pins the
second tier's most as the 75 and 120 it is measured against. Twelve deliberate
faults, each caught, two of them only after a test was added for each; and one
found the class tests' timeout could not stop a busy loop
([M-13](../defects.md#3a-simulation)). **Part (c) is built**: turrets and
the four classes that carry them (33–36). A turret looks once a volley, from the
hash as of the last step; a class of turrets alone watching nothing costs a
query a reload. **Part (d) is built**: classes with no barrels, a class's own
caps on points and its body damage; the Smasher, the Spike and the Auto Smasher
(37–39). The arena spends a point through the room, which asks the class for its
cap, and holds a burst of requests to 10 rather than 7. A bot now takes its
class before it spends its points, or a bot that became a Smasher had spent them
on bullets it cannot fire. The client learns a class's caps from the class
table, as its barrels: fetched from `platform` and kept by the content version
(D-24, `ApiClient.ClassTableInfo`). **Part (e) is
built**: hiding, and the Stalker, the Manager and the Landmine (40–42). The
encoder leaves a hidden tank out of every view but its own player's, whose tank
carries the new flag; turrets pass it by; a tank arriving counts as seen, so one
put back into a long-running room does not vanish on its first tick. **Part (f)
is built**: the Necromancer and the Factory (43, 44). A projectile is launched
from a tank or from a minion by one routine, so a minion's bullets are its
tank's by the same rule as a barrel's; a square's conversion asks for its
breaker by the rule that pays for a kill. Found by the tests, and a balance
matter rather than a defect: a drone has 16 health and a square's body does 8
to what breaks it, so a drone breaks two squares and is gone. A Necromancer's
drones are worn out by the thing that makes them. **Part (g) is measured**
([backend README](../../backend/README.md#what-the-third-tier-costs-2026-09-28)):
the tier costs what the second did, 2 193 bullets in flight against 2 247 and
the same tick within the noise, as D-22 meant; the payload fell to 87 bytes.
The first run found the tick allocating every time
([M-14](../defects.md#3a-simulation)), fixed, and a test holds it now.

**Not in this slice**: the Mega Smasher (a bigger body, and every rule that
takes a tank's radius to be 30 would have to ask its class), the Battleship
(drones with a lifetime and no cap), the Skimmer and the Rocketeer (projectiles
that fire: a minion's mechanism, and the one kind the budget would have to
bound per projectile), the Predator's zoom (no input for it), knocking traps,
and the client drawing any of it. All but the drawing were built in
[the rest of the tree](#the-rest-of-the-tree-designed-2026-09-28-plan-item-5).

### The rest of the tree (designed 2026-09-28, plan item 5)

What the third tier left, in the order it is built. Each part is a mechanism,
and each is small; the last makes the table reach the device, which every class
before it has been waiting for.

**(a) Traps are knocked.** A trap is pushed by what it touches, a tank, a shape,
a bullet or another trap, as a tank is, and slides it off with its own
friction. It is a unit, updated rather than extrapolated (D-9), so a knock costs
its updates and not a client's prediction. A bullet is still never knocked.

**(b) Drones with a lifetime: the Battleship.** A drone barrel may give its
drones a lifetime; the ones so far live until used up or their tank is gone,
which the table now says outright instead of with a placeholder lifetime of 1.
The **Battleship** (45, from Overseer or Twin Flank) launches small drones from
four barrels, two either side (size × 0.7, damage × 0.4, penetration × 1), each
living 150 ticks, 16 out at most. Its drones are steered as any drone is.

**(c) Projectiles that fire: the Rocketeer and the Skimmer.** A barrel may launch
a **missile**: a unit (subtype 4 on the wire, since it cannot be told from a
bullet by a create) that flies straight at its launch speed for its lifetime and
fires as it goes, its shots its tank's bullets, as a minion's are. The
**Rocketeer's** (46, from Destroyer) fires one small bullet straight back every
3 ticks, a trail living 15; the **Skimmer's** (47, from Destroyer) turns 0.2 rad
a tick and fires two, either side of its facing, every 4 ticks, living 25. D-22
counts a missile's shots: its lifetime over the reload is how many are out, each
keeping its shots' lifetime over their interval alive. Both keep under 75.

| Class | Barrel | Missile | Reload |
|---|---|---|---|
| Rocketeer | 1 ahead | speed × 0.8, size × 1.4, damage × 1, penetration × 3, 75 ticks; shots size × 0.5, damage × 0.3 | × 4 |
| Skimmer | 1 ahead | speed × 0.6, size × 1.6, damage × 1, penetration × 3, 75 ticks; shots size × 0.5, damage × 0.3 | × 4.5 |

**(d) A body of its own size: the Mega Smasher.** A class may make its tank's
body bigger. The body follows the class every tick, rather than only when it is
chosen, so a tank put back into the world (02 §10) and one that dies and returns
as Basic are the right size without a rule of their own. The client knows a
tank's size from its class, as it knows its barrels. The **Mega Smasher** (48,
from Smasher): body × 1.3, body damage × 1.25, the Smasher's caps. A collision
query reaches the largest body any class has, not a tank's 30.

**(e) The Predator's zoom.** A new input flag, `FLAG_ZOOM` (8): while it is held,
a class with a zoom has its view centred that far ahead along its aim rather
than on the tank. The **Predator** zooms 700 units. The simulation works out the
shift each tick and the encoder centres the view there; the snapshot's view
origin already moves with the view, so the frame's format does not change. A
flag, not a distance: the stick gives a direction, as it does for drones (D-21).

**(f) The class table reaches the device**
([D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)).
`Welcome.contentVersion` has been a constant 1 through three tiers of classes; a
device holding the second tier's table would have drawn the third's wrongly and
never known. It becomes a hash of the class table, and `platform` serves the
table itself, as JSON, at `GET /v1/content/classes`, with that hash as its
version and its `ETag`. Everything a client needs to draw a tank and its shots
and to offer its choices: names, parents, levels, barrels, sizes, caps, drones,
zoom. The C# core reads it into a table of its own; drawing it is the Unity
layer's, which stays deferred.

**(g)** What the rest of the tree costs, measured as before.

**Not in this slice**: nothing further of the tree. The client drawing it is
the Unity layer, deferred with it.

**Part (a) is built, 2026-09-28**, tested whichever of a trap and what hits it
comes first in the room's list, since a pair of projectiles is settled from one
side only, and the first draft of the tests had tried only one. Found building
it, a balance matter: a trap has 16 health and a tank's body does 20, so a trap
a tank meets is used up in that tick, knock and all. The knock is felt by traps
struck by bullets and other traps, and by sturdier ones: a Mega Trapper's, or a
trapper's with points in penetration. **Part (b) is built**: a drone barrel's
lifetime is honoured, the lasting ones say so with `NO_EXPIRY` rather than a
placeholder 1, and the Battleship (45). The placeholder had also let an older
test's bound on bullets count a drone barrel as next to nothing; drones are
bounded by their class's cap, and it says so now. **Part (c) is built**: the
Rocketeer and the Skimmer (46, 47), their missiles units of subtypes 4 and 5, so
that a client can tell the two apart without an owner that may be gone. A
barrel's kind is the subtype of what it launches; the internal convert kind
moved from 4 to 6 to keep that true, which nothing outside the simulation ever
saw. A missile whose tank is gone flies on, silent. **Part (d) is built**: a
class's body size, and the Mega Smasher (48). Tested where it matters: sized only
when chosen, a tank put back into the world came back at 30, and a collision
query that reached a tank's 30 missed a bullet grazing a Mega Smasher filed a
grid cell away, which a room without alpha pentagons shows. **Part (e) is
built**: `FLAG_ZOOM` and the Predator's zoom. The view is selected around its
centre but ranked by nearness to the tank, so a zoomed player short of budget
keeps what is beside it before what is far ahead; a deliberate fault ranking
from the centre survived until a test said so. **Part (f) is built**: the
simulation writes the class table as JSON itself, in a fixed order, and its
version is a CRC-32 of those bytes, so the arena's `Welcome` and `platform`'s
`GET /v1/content/classes` cannot disagree; the C# core reads it, and the live
drill checks that the table `platform` serves is the one the `Welcome` names.
**Part (g) is measured**
([backend README](../../backend/README.md#what-the-rest-of-the-tree-costs-2026-09-28)):
the whole tree costs 13 % more at p50 than the third tier, and the drones are
why: D-22 bounds bullets and traps, and leaves drones to each class's cap, which
the Battleship's 16 make the largest. Whether the Battleship keeps eight, as the
Overseer does, or D-22 counts drones, is a balance question and open.

### The original design, for reference


- XP thresholds: `xpTable[level]` from `levels.json` (diep: L2 = 4, L10 ≈ 160,
  L30 ≈ 5 300, L45 ≈ 23 000). Level 45 max, 33 skill points total (1 per level
  from 2–28 then every third level, tunable).
- XP sources: shapes (square 10, triangle 25, pentagon 130, alpha pentagon 3 000),
  tank kills (fraction of victim score, floor at victim level), mode bonuses.
- On level up: `unspentPoints++` per table, `statsDirty = true`, event to client.
- Class upgrades at 15 / 30 / 45: client sends `ChooseClass{classId}`; server
  validates `TankTable.parentOf(classId) == current && level >= tier`. Class
  change swaps `barrels` (count, angle offsets, size, delay, bullet stat
  multipliers), body radius, FOV multiplier, and may change entity kind
  behaviour (drone controllers, trap layers).

`tanks.json` excerpt:

```json
{ "id": 5, "name": "Twin", "tier": 15, "parent": 0, "fovMul": 1.0,
  "barrels": [
    { "angle": 0, "offsetX": 0, "offsetY": -13, "width": 0.42, "length": 0.95, "delay": 0.0,
      "bullet": { "speedMul": 1.0, "damageMul": 0.65, "penMul": 1.0, "reloadMul": 1.0, "lifeTicks": 75 } },
    { "angle": 0, "offsetX": 0, "offsetY":  13, "width": 0.42, "length": 0.95, "delay": 0.5, "bullet": { "...": "..." } }
  ] }
```

## 5. Movement and physics

Fixed timestep, no substeps, semi-implicit Euler, all float:

```java
void updateTank(Entity e, TankState t) {
    float ax = 0, ay = 0;
    if ((t.moveMask & UP) != 0) ay -= 1; if ((t.moveMask & DOWN) != 0) ay += 1;
    if ((t.moveMask & LEFT) != 0) ax -= 1; if ((t.moveMask & RIGHT) != 0) ax += 1;
    if (ax != 0 && ay != 0) { ax *= 0.7071f; ay *= 0.7071f; }
    float accel = t.eff.moveSpeed * ACCEL;         // e.g. 0.16 of top speed per tick
    e.vx = (e.vx + ax * accel) * FRICTION;         // FRICTION ≈ 0.9 → asymptotic top speed
    e.vy = (e.vy + ay * accel) * FRICTION;
    e.x += e.vx; e.y += e.vy;
    clampToMap(e);                                 // or mode.clamp (bases, maze walls)
    e.angle = t.aimAngle;
}
```

Bullets: `x += vx`, `lifetimeTicks--`, die at 0 or when hp ≤ 0. Shapes drift
slowly with random heading and rotate for cosmetics. Knockback is a velocity
impulse proportional to `other.mass / (this.mass + other.mass)`. **A knock wears
off:** a shape moving faster than its 0.15 units a tick of drift loses a tenth
of its speed each tick (built 2026-09-26; before, a shape kept every push it
ever took).

Map: FFA/Teams 2D square (**as built**, 5 700 × 5 700 units, `MAP_SIZE`, which
puts ~12 of 150 tanks in a view; no bases, no walls), 4 corner bases for team
modes, Maze adds an axis-aligned wall grid (as designed, static `WALL` entities
inserted into the spatial hash once; tanks resolve against walls with AABB
push-out; bullets die on wall contact). **As built** a made match's room is its
mode's square (`MatchMode.mapSize`, 2 000 to 3 500 units) with no bases, teams
arriving on their own thirds (§8.3); and a maze's walls are not entities and not
in the spatial hash: `sim/Walls` indexes them by 300-unit cell, a body is pushed
out of a wall the shorter way, and a bullet whose centre comes within its
radius of one ends there (§8.9).

## 6. Spatial hash and collisions

Cell size = largest common entity diameter (~ 2 × alpha pentagon radius is too
big; use ~ 200 units and let big entities span several cells).

```java
public final class SpatialHash {
    final int cols, rows; final float cell, invCell;
    final int[] head;         // cellIndex → first entity id in cell, -1 if empty
    final int[] next;         // entityId → next entity id in same cell (intrusive list)
    final int[] cellOf;       // entityId → cellIndex (for entities that fit one cell; big ones inserted in all overlapped cells via a side list)
    public void clear() { Arrays.fill(head, -1); }
    public void insert(Entity e) { /* compute cell range, push into each cell's intrusive list */ }
    public void query(float minX, float minY, float maxX, float maxY, IntList out) { /* iterate cells */ }
}
```

Rebuilding every tick (clear + insert all) is simpler and cheaper than
incremental updates for ~5 000 entities: it is two tight array loops.

Collision pass — iterate cells, test pairs within the cell and the 3 forward
neighbours (right, down, down-right, down-left) so each pair is tested once:

```java
void collide() {
    for each cell c:
      for a in c:
        for b after a in c:  test(a, b)
        for b in c.right, c.down, c.downLeft, c.downRight: test(a, b)
}
boolean canInteract(Entity a, Entity b) {
    if (a.ownerId == b.id || b.ownerId == a.id) return false;          // own bullets
    if (a.kind == BULLET && b.kind == BULLET && a.ownerId == b.ownerId) return false;
    if (!mode.canDamage(a, b)) return false;                             // teams, bases, spawn protection
    return true;
}
void test(Entity a, Entity b) {
    float dx = b.x - a.x, dy = b.y - a.y, r = a.radius + b.radius;
    if (dx*dx + dy*dy >= r*r) return;
    if (!canInteract(a, b)) return;
    // diep rule: both take the other's damage simultaneously
    float da = b.damage, db = a.damage;
    a.hp -= da; b.hp -= db;
    a.lastHitTick = b.lastHitTick = world.tick;
    creditDamage(a, b, da); creditDamage(b, a, db);   // for XP share & assists
    knockback(a, b);
    if (a.hp <= 0) world.kill(a); if (b.hp <= 0) world.kill(b);
}
```

Bullet "penetration" is literally the bullet's hp: a bullet with hp 20 passing
through a square (damage 8) survives with hp 12 and continues.

Damage crediting: keep a tiny ring (last 3 attackers with tick) on tanks and
shapes so kill XP/assists go to the right players even when the finishing blow
is a body hit.

**As built** (bodies since 2026-09-26, [M-8](../defects.md#3a-simulation)): not a
cell walk but two passes over the spatial hash. **Bullets** query around
themselves and hit tanks, shapes and other shooters' bullets, per hit: the
target loses the bullet's damage, the bullet loses the target's body damage (a
tank's is its stat), and only non-bullets are knocked. **Tanks** then query
around themselves for other tanks and shapes, each pair once: both are knocked,
and **body damage is dealt per second of contact**, each losing the other's body
damage divided by 25 every tick they overlap, worked out before either is
applied. At base values a tank breaks a square in half a second for about four
health, and two base tanks pushing each other last about two and a half seconds.
A body kill is settled like a bullet kill: the other tank is paid and credited,
if it has health left itself; two tanks that finish each other off both die,
and neither is paid. A tank a shape kills is logged with nobody to credit, so
its death is counted; a broken shape is replaced. Spawn protection is built
(§7). Team rules were not built with it (bullets and bodies alike hurt everyone,
which is the free-for-all the public arena is), and have been since
([§8.3](#83-team-rules-designed-2026-09-28-plan-item-6)). The attacker ring
was built later, with assists (plan item 65, `TankStats.attackerTags`). Measured at the shipped density (150 tanks, 1 500
shapes): the collision phase went from 0.15 to about 0.18 ms, the whole tick
from 1.55–1.61 to 1.64–1.74 ms on a loaded machine, still with nothing
allocated.

## 7. Deaths, respawn, spectate

- Tank death: `mode.onKill(victim, killer)`; killer gets XP; victim's client
  gets `Death{killerName, score, level}`; entity slot freed; the **connection**
  stays attached for spectating (camera follows killer) until `Respawn`.
- Respawn: level resets per mode (FFA: back to level 1, but *score-based
  rebate*: respawn at level derived from a fraction of previous score, capped,
  like diep's "keep some level" behaviour — tunable), spawn point from mode
  (random safe cell not within 500 units of an enemy for FFA; team base for
  team modes), 3 s spawn protection (`protectedUntilTick`, cannot shoot).
- Leave/disconnect: tank stays alive for a grace period (10 s) with
  `moveMask = 0`, so a reconnect resumes (see networking doc). After grace:
  tank removed, partial result emitted.

**As built** (2026-09-26, `sim/Spawning`, a content table): a tank arrives at a
random point with no tank within 500 units and no shape on top of it, trying 20;
if none is clear of both, the best of them, clear of shapes first and then
farthest from the nearest tank. Any tank counts, since the public arena is
free-for-all; team bases come with team modes. For 3 s it cannot be hurt and
cannot hurt: bullets that hit it are spent on it, bodies still push but neither
side takes damage, and it cannot shoot. A held trigger fires the moment
protection ends. Clients see it as `TANK_FLAG_PROTECTED` in the tank's `FLAGS`
([02 §4](02-networking.md#4-the-snapshot)). The disconnect grace is built as
resume ([02 §10](02-networking.md#10-session-reconnect-and-app-lifecycle)): 10 s
parked in the world, then out of it with its progress kept for a minute.

**Assists** (designed 2026-10-02, plan item 65, a first cut of the balance the
owner left to Claude, Q-48). Only the last hit was credited: a tank worn down by
one player and finished by another paid the second alone, and a team mode's
fights, two on one, paid one of the two. Now each tank keeps a ring of the last
three players who hurt it, by bullet or by body, each with the tick of their
last hit (`TankStats.attackerTags`, `attackerTicks`; a hit from a player already in
it moves their tick on; a lethal hit is the kill, and takes no assist's place). When it dies, every player in its ring other than the
killer who hurt it within the last **five seconds** (125 ticks) is credited an
**assist**: a quarter of the experience the kill paid (`ASSIST_XP_SHARE`), on
top of the killer's, while they live, and one assist counted, in the match's
tally, its result (`assists`, beside `kills`), `match_player` and `player_stat`
(`V1__schema.sql`, [D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)). Why these numbers: five seconds is a fight's length here, a tank's health
lasting a few seconds under fire, and not so long that a hit before a chase
counts; three is a two-on-one with one to spare; a quarter keeps the killer's
part the reason to finish, while paying the one who did the work before. A
shape's or a bot's hit is not an assist: nobody is credited for it. Each one
constant. The kill feed is as it was: it names the killer.

**The rebate** (designed 2026-10-01, plan item 41,
[Q-36](../requirements/01-scope-and-nfrs.md#7-open-questions)): in the public
arena a tank respawned after a death starts with a quarter of the experience its
last life had reached, up to what level 20 needs (`RoomThread.REBATE_DIVISOR`,
`REBATE_MAX_LEVEL`), and its points to spend; the life's experience is read from
the dead tank's stats as the death is handled. In a made match a respawn starts
at level 1. A resume is not a respawn: it gets back what the stay had. The score
that pays, the stay's, keeps adding up across deaths; a client shows the life's
progression from `Stats`. **Built 2026-10-01**: `Connection.lastLifeXp`, read
as the death is handled; `RoomThread.respawn` granting the rebate.

## 8. Match modes

### 8.1 The mode decides the lifecycle

There are two, and they are not interchangeable
([D-15](../architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches)).

| | Open — public arena (FFA, tag, domination) | Timed — duel, ranked, team, tournament |
|---|---|---|
| Room | Never resets | Reset per match |
| Ends | Never | Clock, or a win condition |
| Recorded unit | One player's **open match**: arrival to departure | One **timed match**, everybody in it |
| Placement / winner | None — 0 and false, not invented | Real |
| Result written | When the stay ends (leave, kick, a minute unresumed, arena stop), and every 10 min while it lasts | When the match ends |

**Implemented** (`arena/MatchRules`, `arena/MatchTally`). The public arena is
the default. **The duel is the first timed mode (2026-09-27)**, played in a
room made for the one match by its first ticket
([D-20](../architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command),
[04 §4](04-platform-services.md#the-first-slice-a-ranked-duel-end-to-end-designed-2026-09-27-plan-item-6)):
the lifecycle `MADE`. The room waits for its roster, at most 30 s, with the
tanks free to move and farm; then the world resets and the match plays, placed
by kills, until one side has three or three minutes pass; then the result is
published, each player is sent `Kick(6)` and the room closes. Fewer than two at
the start is a walkover, published with the one player who came. A stay lost
while waiting ends at the start, as one across a timed match's end does, so a
player who drops then cannot come back to that match. The registry retires the
room when it is over, not counting it as failed, and remembers the match, so a
ticket for it that arrives late is refused `Kick(2)` rather than starting it
again. `TIMED`, a room that runs timed matches back to back with whoever is
there, is how the arena first ran and what an arena given a match length
still does.

**An open match is not a life.** A player dies every half-minute or so, and a
result per life would be roughly 1 700 events a second at 50 000 players
against the ~170/s this design is sized for
([05 §1](05-worker-and-events.md)). Deaths accumulate inside an open match
instead, which is also what `match_player.deaths` being plural implies.

**Kills and deaths do not balance, and should not.** Across a room the totals
satisfy *kills ≤ deaths*. A player's bullets outlive them: when someone leaves,
their record is published and closed, but their shots stay in the air, and one
that lands afterwards still kills its victim. The death is counted, the kill is
not, because crediting it would mean writing to a record already sent. Measured
over 120 recorded matches: 281 kills against 282 deaths.

**Long open matches are checkpointed** every ten minutes: the match is closed,
written, and a fresh one started for the same player. Without it a player who
stays for six hours is paid for none of it until they leave, and loses all of
it if the process dies first. The player's simulation tag is deliberately
unchanged across a checkpoint — bullets already in flight carry it, and
reissuing it would send their kills to a record that no longer exists.

**Decided with the rebate** ([§7](#7-deaths-respawn-spectate), Q-36): the score
that pays accumulates across the open match, as it did; what a client shows
after a death is the life's progression, from `Stats`.

### 8.2 The interface

**Not built.** A room's lifecycle is `MatchRules` (open, timed or made, §8.1),
its mode `handoff/MatchMode`'s rules, and its
record `MatchTally`; this interface is the design for the modes to come.

```java
public interface MatchMode {
    void init(Room room);                          // map, walls, bases, dominators
    void preTick(); void postTick();
    byte assignTeam(TankState t);                  // 0 for FFA
    boolean canDamage(Entity a, Entity b);         // team rules, base protection, dominator ownership
    void spawnPoint(TankState t, float[] outXY);
    void onKill(Entity victim, Entity killer);
    void onLeave(TankState t);
    boolean isFinished(); MatchResult buildResult();   // null for endless modes
    void encodeModeState(ByteBuf buf);            // extra per-snapshot mode data (scores, dominator owners)
}
```

| Mode | Rules in code |
|---|---|
| **FFA** | `assignTeam → 0`; `canDamage` true unless spawn-protected; endless; per-room score board = top 10 by score. |
| **2 Teams / 4 Teams** | Team = smallest team at join (or party's team). Bases: corner squares where enemy tanks take heavy damage and enemy bullets die; base drones (AI) patrol. `canDamage` false for same team. |
| **Tag** | Two teams; `onKill(victim)` → victim respawns on killer's team. Finished when one team has everyone; result = winning team + per-player conversions. |
| **Maze** | FFA rules + wall grid generated from seed (recursive division); walls are static entities; spawn points must be in open cells. |
| **Domination** | 4 teams + N dominator entities (huge hp turrets, team-owned). Dominators shoot enemies; when hp hits 0 the *last damaging team* captures it and it restores to full hp. Win when a team holds all dominators for T seconds, or by capture count at time limit. |
| **Sandbox** | Private room (code), host can set level/class/stats freely, spawn shapes/bosses, invite via team; no results published. |
| **MvM (PvE)** | Co-op team vs AI monster waves and bosses (boss = entity with scripted `BossBrain`, multiple barrels, phases). Wave table from `mvm.json`; result = waves cleared, boss kills, team score. Used for team challenges and tournaments. |
| **PvP ranked / duel / team-vs-team** | Small maps (2–10 players), fixed level 45 start, time limit or first-to-N kills; result includes rating deltas computed by `platform`. |

Bots: `BotBrain` runs on the room thread with a cheap state machine (roam →
farm nearest shape → engage weaker tank → flee). Used to fill rooms and for
MvM monsters.

### 8.3 Team rules (designed 2026-09-28, plan item 6)

What a team mode needs from the simulation, whatever the mode: the first is
team-vs-team ([04 §4](04-platform-services.md#the-second-slice-parties-and-team-vs-team-designed-2026-09-28-plan-item-6)).
**Team 0 is no team**, as the public arena's tickets and every bot already are, so
nothing changes for them; a team mode numbers its teams from 1.

- **A tank's own team does it no harm.** A bullet, trap, drone, minion or missile
  passes through a tank of its own team, and through the team's other
  projectiles, untouched and not used up; it is not knocked, so a bullet's path
  stays what the client extrapolated (D-9). Two tanks of a team touching are
  pushed apart and neither is hurt. Shapes belong to no team and are anyone's.
  Turrets already pass their own team by.
- **Each team arrives on its own side**: team 1 in the left third of the map,
  team 2 in the right, anywhere free of shapes, as spawning already looks
  (01 §7). A room made for a match places them so; the public arena, all team
  0, spawns as it does.

**Built 2026-09-28** (`Room`: one rule, `sameTeam`, asked by the projectile pass
and the body pass, and a team's side in `placeSafely`). A tank's team is now set
before it is placed, which had been after, when nothing asked. Six deliberate
faults, each caught, among them team 0 counted as a team, which fifteen tests of
the public arena's rules catch.

### 8.4 Team-vs-team

A timed mode on the `MADE` lifecycle, as the duel is: two teams of three, the
roster six. The room waits up to 30 s for its roster; then the world resets and
the match plays for 5 minutes or until a team has 10 kills of the other's
tanks, then the team with more, else a draw. Every player of the winning team is
placed 1 and of the losing team 2; a draw places everyone 1. **A team nobody of
which arrived loses by walkover**, unrated and unpaid as a duel's is; a team
short of players plays short, and its result counts.

**Built 2026-09-28** (`MatchMode.TVT`, with a team size; `MatchTally`,
`RoomThread`). A player is placed as their team is, one more than the teams
ahead of it: placed as players are, the losing team of three came fourth, which
the first run of the test showed. A walkover is fewer than two *sides* at the
start, a side being a player in a duel and a team here, so two of one team who
came are still a walkover. `platform` refuses to queue a mode with teams until
the matcher fills teams.

### 8.5 Co-op waves (designed 2026-09-29, plan item 6)

A timed mode on the `MADE` lifecycle: one team of three, team 1, against the
arena's own tanks, team 2, which come in waves
([04 §4](04-platform-services.md#the-fifth-slice-co-op-waves-designed-2026-09-29-plan-item-6);
its numbers are [Q-7](../requirements/01-scope-and-nfrs.md#7-open-questions)).

**A hunting tank** is a tank nobody drives that, unlike the benchmark's
wanderers, has a quarry: each tick it turns to the nearest tank of another
team that a player drives, moves toward it while further than 400 units, and
holds its trigger while nearer than 600. With none in reach it stands and
waits. The team rules (§8.3) keep a wave from harming itself.

**The waves** belong to the room's thread, as the match's clock does: 5 s after
the match starts, and 5 s after the last tank of a wave dies, the next wave's
2 + *w* tanks spawn in the right third at level 5*w*, grown as the benchmark
grows a bot, its points spent and its classes taken. At each wave's start
every player then dead is put back into the world, at their team's side; a
respawn asked for during a wave is not granted. The match ends when the tenth
wave is cleared, when no player of the team is alive, or at ten minutes, and
every player is placed first. Its result is a team's, unrated.

**The hunting tank is built** (2026-09-29; `Entity.hunts`, `Room.quarry`). It
holds its trigger only for a quarry in reach, where the benchmark's tanks fire
at will; with no quarry it stands still and holds fire. A slot a hunter died
in forgets it on reuse. Only the benchmark's and the tests' rooms have tanks
nobody drives, and none of them hunts, so the public tick gains one field test
per such tank; not measured, being below what this VM can resolve.

**The waves are built** (2026-09-29; `arena/Waves`, `RoomThread`). A wave's tanks
are grown to their level at once through the experience a level needs, which
spends their points and takes their classes as the benchmark's bots do; the
level is capped by the level table, so a cap of 45 in the code is the table's
own and its mutant equivalent. A co-op match with one player plays: the
walkover counts fewer sides than the lesser of two and the mode's own. A wipe
is no player of the team with a tank; a player who left is not counted.

**A wave cleared pays** (designed 2026-10-01, plan item 41,
[Q-37](../requirements/01-scope-and-nfrs.md#7-open-questions)): 100 to the score
of every player of the team still in the match, connected or with a stay
waiting, as each wave is cleared, the tenth included (`RoomThread`, seeing
`Waves.cleared()` rise; `MatchTally.addScore`). Score is paid as any match's
is, so the reward is coins and experience with nothing new in the result.
Tiers by account level wait for co-op to be played (Q-37).

**Bosses** (designed 2026-09-30, plan item 29,
[Q-28](../requirements/01-scope-and-nfrs.md#7-open-questions)). Waves 5 and 10
bring one boss each, instead of their hunting tanks: a hunting tank of team 2,
given a class no player can choose and then grown to level 45 as a wave's
tanks are. The class comes first so that the points the growth spends follow
the boss's own caps, 7 at most in each stat and none in movement speed: grown
first, a bot's points went by whatever classes it drew on the way, about 4 into
speed always and, one boss in seven or so, none into its bullets.
**A boss is a class**, as every tank's look and weapons are, so a
client draws it from the class table it fetches (D-24) and the protocol does
not change:

| Class | Body | Barrels | Health | Reload | Bullets live |
|---|---|---|---|---|---|
| Guardian | 2.5 × a tank's | eight round it, as the Octo Tank's | × 12 | as the Octo Tank's | as a tank's |
| Guardian, enraged | the same | the same | the same | twice as fast | half as long |

A class nobody can choose has Basic for a parent, as every class but Basic
must, and opens past the last level, so no upgrade reaches it; the arena gives
it with `Room.assignClass`, which sets a tank's class without the upgrade
rules. **A class's health multiplier** is new, 1 for every class but these:
the health a tank's level and points give, times it. **The enraged form**: each
tick of a boss wave, a Guardian below half its health becomes the enraged one;
its health does not change, being the same multiple. Its bullets living half as
long keep what it has alive within D-22's budget. A boss kill scores and pays as
any tank of level 45 does; the result does not name bosses.

**Built 2026-09-30** (`ClassTable.GUARDIAN`, `GUARDIAN_ENRAGED`, `NEVER`,
`TankClass.healthMul`; `Room.assignClass`; `arena/Waves`). The multiplier is
applied where a tank's health keeps step with its stats each tick, as a class's
other multipliers are applied where they are used; a tank spawns as Basic, so
the spawn needs none. `assignClass` drops no volley, unlike a choice: it is
given to a tank just spawned, or swaps the Guardian's barrels for the same
ones. Its cost on the public tick, one lookup a tank a tick, is below what the
benchmark resolves: simulation p50 0.378 and 0.381 ms before, 0.378 and 0.377
after, alternated.

### 8.6 Draining an arena (designed 2026-09-29, plan item 7)

A stop used to be immediate: every room published what its players were owed
and the process ended within `TimeoutStopSec`, 45 s. Since matches are made
([§8.4](#84-team-vs-team), [§8.5](#85-co-op-waves-designed-2026-09-29-plan-item-6)),
that cuts short a rated duel a minute in, and publishes it as a result that
moves both ratings. **A stop now drains**
([D-29](../architecture/03-decision-log.md#d-29--an-arena-drains-before-it-stops-and-a-match-cut-short-is-not-rated)):

1. The arena **withdraws from the directory** at once, as a stop already did;
   `platform` sends it no new player and no new match. (A "draining" mark was
   designed first, and dropped: nothing picks a withdrawn arena, so it would
   have said nothing more.)
2. Its public rooms send their players back to the lobby, with their results
   published, as a stop always did: an open room has no end to wait for.
3. Its made rooms play on to their own end, the clock or the win; a player of
   one who lost their connection may still come back to it. A ticket for a new
   match, sent in the seconds the directory was stale, is refused, and its
   players go back to the queue (D-20).
4. When no made room is left, or after **eleven minutes**, the longest mode's
   ten and its join window, the stop goes on as before.

**A match cut short is recorded and paid, and not rated**: the drain's time ran
out, or the process was killed. Its result says so, `cutShort`, a field an older
worker ignores and a newer one reads; the players' placements at that moment
were not a finish. The unit's `TimeoutStopSec` becomes twelve minutes.

**Built 2026-09-29** (`RoomRegistry.drain`, `ArenaMain`'s stop, `MatchOutcome.cutShort`,
the worker's rule). Drilled live from the release: an arena stopped sixteen
seconds into a duel kept playing it to its three minutes, sent both players
back with `Kick(6)`, had the duel rated as any other, logged `drained in 163 s`,
and exited.

### 8.7 Domination (designed 2026-10-01, plan item 30)

A timed mode on the `MADE` lifecycle
([Q-29](../requirements/01-scope-and-nfrs.md#7-open-questions)): two teams of
three, team 1 and team 2, on their sides as team-vs-team's are (§8.4), and three
**dominators** on the map's middle line, a quarter, a half and three quarters of
the way down it, neutral at the start.

**A dominator is a tank of the arena's own**, as co-op's hunters are: level 45,
of the class Dominator, which no player can choose (opened by no level, as a
boss's): one turret that looks all round, twice a tank's body, eight times its
health. It is given the class before it is grown to level 45, as a boss is
(§8.5), so its points follow the class's caps, the boss's: 7 at most in each
stat and none in movement speed, so every dominator fires as hard as the next.
It is **anchored**: it drives nowhere, its velocity is kept at nothing,
and contact knocks others off it without moving it. Team 0, no team, while
neutral, so it shoots both teams (§8.3); a team's once captured, so it shoots
the other's and not its own, and its own bullets do not hurt it.

**It is captured, never killed.** A blow that would kill it, a bullet's or a
body's, from a tank a player drives makes it that tank's team's, at full health;
the kill is logged and paid as any tank kill of level 45, so the capturer's team
counts the kill. A blow from anything else, another dominator's bullet or a
shape's body, leaves it as it was, at full health: dominators do not take each
other, and a team does not win without playing. For the same reason a
dominator's turret does not aim at another dominator; a player's turret does.

**The clock.** Each tick the room counts the dominators each team holds. A team
holding all three for 60 s wins at once; at five minutes the team holding more
wins, else a draw. Teams are placed by dominators held at the end, not by kills
(`MatchTally` takes the teams' points from the mode). Unrated; paid as any match.

**Built 2026-10-01** (`Entity.anchored`, `captures`; `Room`'s capture in both
kinds of death and the turret rule; `ClassTable.DOMINATOR`, 51;
`MatchMode.DOMINATION`, 6; `arena/Domination`; `RoomThread`;
`MatchTally.finish` with the teams' points). An anchored tank's velocity is
zeroed before it moves, so no knock can move it, without touching the push
itself. The platform and the worker needed nothing: the queue and the matcher
take every mode `MatchMode` has, and an unrated mode moves no rating.

### 8.8 Tag (designed 2026-10-01, plan item 31)

A timed mode on the `MADE` lifecycle
([Q-30](../requirements/01-scope-and-nfrs.md#7-open-questions)): two teams of
three, team 1 and team 2, on their sides as team-vs-team's are (§8.4). **A kill
converts**: a player killed by a player of the other team goes over to it, and
respawns, when they ask, on that team's side, as that team. The room keeps each
player's team for the match, starting from the ticket's; a spawn and a respawn
take it, and a resumed stay keeps the tank it had. A stay whose tank is gone
when its player comes back (it died while they were away, or was taken out
after the ten seconds, 02 §10) gets its new tank on the team the player is on
now, as a respawn does, not the ticket's. A team's phrases (§9) go to the team
each player is on now too. A kill by a teammate cannot happen (§8.3), and a
death to a shape converts nobody.

**It ends** at once when every player still playing, connected and not leaving,
is on one team, else at five minutes. The team with more players at the end wins,
else a draw. **Each player is placed by the team they started on**: the tally
keeps the starting team, and takes the final head count of each team as its
points, as domination's holds are taken (§8.7). A player's conversions are their
kills. Unrated; paid as any match.

**Built 2026-10-01** (`MatchMode.TAG`, 7; `arena/Tag`; `RoomThread`: the teams
registered at a first spawn, the conversions from each tick's kill log, a
respawn on the team a player is on now, the end, and the heads as the tally's
points). Only `respawn` needs a player's present team: a first spawn is on the
ticket's, and a ticket is used once. Three server-level tests: a kill that
leaves one team ends it at once; a converted player comes back on the other team,
and the clock places the side with more players first; and a match whose kills
are tied one each is placed by heads. Their hooks read the stage from the teams,
not from a death seen: a respawn asked for can follow a death within one tick.

### 8.9 Maze (designed 2026-10-01, plan item 32)

A timed mode on the `MADE` lifecycle
([Q-31](../requirements/01-scope-and-nfrs.md#7-open-questions)): eight each for
themselves, as ranked free-for-all's are (§8.1), unrated, four minutes, on a map
of 3 000 units whose walls make a maze.

**The maze** is made from a seed, the hash of the match's id: ten cells a side,
300 units each, cut by recursive division (each chamber split by a wall across
it with one gap, at a cell's edge chosen by the generator, until every chamber is
a cell), then a fifth of the inner wall segments, one cell long each, removed.
Each wall is an axis-aligned rectangle 40 units thick, centred on a cell's edge;
the map's edge needs none. `sim/MazeGenerator` makes the list, `sim/Walls`
indexes it by cell for the collisions, which only look at the walls of the cells
an entity touches.

**What walls do.** After its move, a tank, a shape, a drone or a minion that
overlaps a wall is pushed out of it along the shorter way, and its velocity into
the wall dropped, so it slides; a bullet, a trap or a missile whose centre comes
within its radius of a wall ends there, as at the map's edge. Walls hide nothing:
the view is unchanged. A spawn is placed only where a tank's body clears every
wall. Turrets and drones do not see through walls any less than through air.

**On the wire** ([D-48](../architecture/03-decision-log.md#d-48--a-maze-is-sent-as-a-seed-and-a-predicted-bullet-stops-at-its-walls-by-one-rule-on-both-sides)):
the Welcome gains `mazeSeed`, a varint appended after the resume secret, 0 for no
maze; a client that does not know it reads no further. The client makes the
walls from it with the same generator, and ends a predicted bullet at a wall by
the same rule.

**Built 2026-10-01** (`sim/MazeGenerator`, `sim/Walls`, `Room`; `MatchMode.MAZE`,
8; `RoomThread`'s seed, the CRC-32 of the match's id, and the Welcome's; the
client's `Maze`, `Welcome.MazeSeed` and `ClientWorld`). Ten cells a side make 81
wall segments; a fifth taken out leaves 65. The order the generator draws its
numbers in is pinned by a golden vector of one seed's walls, written by the Java
side and read by both sides' tests; the C# port matched it the first time it
ran. Measured: a maze match's room costs less a tick with its walls than without
(bullets end at them), its shapes' phase a little more for pushing them out, and
nothing is allocated per tick; the public arena, which has no walls, is
unchanged ([plan item 32](../plan.md#32-maze--done-2026-10-01)).

### 8.10 Sandbox (designed 2026-10-01, plan item 33)

A private room on the `MADE` lifecycle
([Q-32](../requirements/01-scope-and-nfrs.md#7-open-questions),
[D-49](../architecture/03-decision-log.md#d-49--a-sandbox-is-a-made-room-that-publishes-nothing-opened-by-a-request-its-powers-one-message)):
opened by a player for themselves, or by a party's leader for the party
([04 §4](04-platform-services.md#the-seventh-slice-the-sandbox-designed-2026-10-01-plan-item-33)),
and nobody else's. `MatchMode.SANDBOX`, 9, has no roster: its room plays from
the first tick, and a player whose ticket comes later is spawned as they
arrive; it holds a party's most, three. Each for themselves, team 0, on 2 000
units with 60 shapes; no bots.

**It ends** at 20 minutes, or once nobody has been in it, connected or with a
stay waiting for them, for 60 s; each player still there gets `Kick(6)`, as at
any match's end. **Nothing is published**: the room finishes with no outcome, so
there is no match row, no pay, experience, rating, leaderboard entry or day's
activity.

**What a player can do**, each for their own tank, with the `Sandbox` message
([02 §3](02-networking.md#3-messages)):

| Action | Value | What it does |
|---|---|---|
| 1, level | 1 to 45 | rebuilds the tank at that level where it stands: Basic, every point for that level unspent, full health; what the player wears stays |
| 2, Guardian | 0 | summons a Guardian, co-op's boss (§8.5), into the room, hunting the players and enraged below half its health as there; refused while one is alive |

A value out of range, an action unknown, a tank that is dead, or any room but a
sandbox: the message is dropped and the connection kept. Classes are chosen by
the usual tree, and points spent as anywhere: a level of 45 opens every class a
choice at a time.

**Built 2026-10-01** (`MatchMode.SANDBOX`, `made()` and `places()`;
`Room.setLevel`; `arena/Sandbox`; `RoomThread`: the requests before the step,
the empty count, the end; `publish` dropping a sandbox's outcome whichever way
it ends, an operator's close and a shutdown included). A made room took its
capacity from the roster, which a sandbox has none of: `places()` gives it a
party's three. Where `MatchRules` and the registry asked whether a mode was
queued, meaning made, they ask that now. Co-op's Guardian is summoned and
enraged by the same code (`Waves`).

## 9. In-room events

Non-positional events are batched per tick and sent in the same frame as the
snapshot: kill feed entries, level-up, class-available, mode score changes,
phrases (a fixed list, never free text: FR-11, [D-14](../architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)), effect
applied/expired, respawn timers. **As built**: deaths with their killer, the
player's own progression ([02 §4](02-networking.md#4-the-snapshot)), phrases and
the kill feed (below); the rest are not built.

### The kill feed (designed 2026-10-01, plan item 37)

On [Q-34](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. **One event, `Kill`**: the killer's name and the victim's, for a
tank killed; a name is empty for a tank that is nobody's, the arena's own or a
player gone, and for a killer that is not a tank's, a shape. A shape broken is
not in it.

| Room | Who is told |
|---|---|
| the public arena | the killer only: whom they destroyed |
| a made match | every player in it, the killer and the victim included: the match's feed |

The victim is told of their own death by `Death`, as before; in a match they
see it in the feed too, as everyone does. Read from the tick's kill log, as
`Death`'s killer is, after the step; a player's name is the tally's, which the
log's tags look up. **Not events**: a level gained and a class come within reach
are in `Stats` and the class table the client holds; a mode's score, effects and
respawn timers have no source the client lacks, or none yet.

**Built 2026-10-01** (`Wire.EVT_KILL`, 4; `EventBuffer.kill`;
`RoomThread.relayKills`, after the deaths are handled and before the tick's
kill log is cleared; the client's `MatchEvent.Victim`). Tested through the
arena: a sandbox's kill told to both players, a public kill to its killer only
and to neither victim nor bystander, a shape broken told to nobody.

### Phrases (designed 2026-09-29, plan item 8)

FR-11 and [D-14](../architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text): a player speaks by choosing a phrase from a fixed list; the
wire carries its id. Phase 3's exit names fixed-phrase chat, and it was the
one part of it not built: the arena read `Phrase` and dropped it, and
`Welcome.phraseListVersion` was a constant 1.

**The list** is content, like the class table, and reaches the device the same
way ([D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)): `sim/PhraseTable`, served by `platform` at
`GET /v1/content/phrases` as JSON (`id`, `key`, `text`), versioned by a CRC-32
of that JSON, which is also `Welcome.phraseListVersion` and the ETag. The `key`
is what a translation table on the device is keyed by; `text` is the English.
Ids start at 1 and are never reused: a removed phrase leaves a gap, so an old
client never shows a new phrase's id as its old one. Sixteen to start, on
recommendation
([Q-8](../requirements/01-scope-and-nfrs.md#7-open-questions)).

**Who hears it** ([D-32](../architecture/03-decision-log.md#d-32--a-phrase-is-heard-by-the-speakers-team-or-by-those-who-see-them)). In a mode with teams (team-vs-team, co-op, the team match, domination and
tag: every mode whose sides are more than one player, `MatchMode.teams()`), the
speaker's team, wherever they are, the speaker included, and nobody else: a call to attack is for the side making it.
In tag that is the team the speaker is on now, and each listener's team is the
one they are on now (§8.8), not their tickets'. In a
mode without, everyone whose view holds the speaker's tank, the speaker
included: a phrase is a bubble over a tank, and a room of 150 each hearing
everyone would send every client 75 phrases a second at their most, about
1 KB/s of events, a third of `mobile`'s budget ([02 §8](02-networking.md#8-traffic-profiles)), and unreadable. A player with no tank in the
world (dead, before a respawn) is heard by their team, and in a mode without
teams by nobody.

**The event**, `EVT_PHRASE` (3): `u8 handle` (the speaker's handle in this
client's view, 0 when it holds none: a teammate out of sight), `varint
phraseId`, `u8 nameLength` and the speaker's name, which the snapshot never
carries ([02 §4](02-networking.md#4-the-snapshot)); a name over 64 bytes goes empty, as a killer's
does. An older client steps over the event by its length; the protocol stays
at 4.

**Refused silently**, never kicked: an id outside the list (a client with a
newer list), and a second phrase within two seconds ([02 §3](02-networking.md#3-messages)'s rate). A tap
repeated is not a protocol error.

Parts: (a) the table, served, and its version in `Welcome`; (b) the arena:
the rate, the audience, the event; (c) the client's decoding and sending, and
a drill. Party phrases in the lobby are a second slice.

**Built 2026-09-29**, all three. The audience is found through
`ClientView.heldHandle`, which names an entity only by the handle given to its
own incarnation: a listener sent no snapshots, backgrounded, still holds the
handle of the slot's earlier occupant, and would otherwise be told a phrase
over whatever that was (tested by giving a tank a new incarnation while a
listener is in the background). Building the client found
[P-33](../defects.md#2-protocol--the-client-contract): a frame's events could go
unseen, fixed by `MatchConnection.OnEvent`.

**Party phrases, built 2026-09-29**, the second slice: a member says a phrase
to their party in the lobby, `party.say`, pushed to every member as
`evt.party.said` ([04 §4](04-platform-services.md#parties)), at the same rate.

Events with persistence value (tank kill, match end, dominator capture, tournament
match end) are also pushed to the j-redis `events` stream, drained by `worker` for
stats and achievements; they carry `roomId, tick, matchId` for dedupe. **Not
built**: only the match result leaves the arena, through the result queue.

## 10. Determinism and replays

**Not built**: there is no input log. The room's RNG is seeded, which is the
part kept from day one.

The room RNG is a seeded `SplittableRandom`-style xorshift owned by the room.
With inputs logged per tick (`inputs.log`: tick, playerId, message bytes) the
whole match is replayable headlessly for debugging, balance analysis and
automated tests. Keep this from day one; it makes "bullet went through the
wall" bugs reproducible.
