package com.backend.sim;

import com.backend.common.IntList;
import com.backend.common.PhaseTimer;

/**
 * One battle room: the fixed-timestep simulation, with no networking and no IO.
 *
 * Running headless is the point. The whole simulation is exercised by unit tests, the
 * replay tool and the tick benchmark without a socket in sight
 * (docs development/01 §1).
 *
 * <h2>Balance lives in {@link Content}, not here</h2>
 *
 * What remains as a constant below is either geometry that no stat touches yet, or a base
 * that a stat multiplies. Most of what a designer would want to tune is in a table, because the
 * alternative is that tuning the pace of the mode means editing the collision loop; kill
 * experience, friction and the shapes' drift are still constants here. What a tank fires, and
 * how long its bullets live, is its class's ({@link ClassTable}).
 */
public final class Room {

    public static final int PHASE_TANKS = 0;
    public static final int PHASE_BULLETS = 1;
    public static final int PHASE_SHAPES = 2;
    public static final int PHASE_HASH = 3;
    public static final int PHASE_COLLIDE = 4;
    public static final int PHASE_SWEEP = 5;

    public static PhaseTimer newTimer() {
        return new PhaseTimer("tanks", "bullets", "shapes", "hash", "collide", "sweep");
    }

    // Geometry and bases. A stat scales the ones a stat has an opinion about.
    static final float TANK_RADIUS = 30f, TANK_MASS = 10f;
    static final float TANK_ACCEL = 0.16f, TANK_FRICTION = 0.90f;
    /** A hunting tank closes to this far from its quarry, and fires from this far (01 §8.5). */
    static final float HUNT_CLOSE = 400f, HUNT_FIRE = 600f;
    static final float BULLET_MASS = 1f, BULLET_SPEED = 10f;
    /** A trap slows by this each tick: laid at v, it slides 10 v and stops (01 §4). */
    static final float TRAP_FRICTION = 0.9f;

    /**
     * A drone steers: 0.5 a tick toward its point, slowed by 0.9, so 4.5 units a tick at most.
     * Its point is {@link #DRONE_REACH} along its owner's aim while the trigger is held (D-21),
     * and otherwise one circling the owner at {@link #DRONE_ORBIT}.
     */
    static final float DRONE_ACCEL = 0.5f, DRONE_FRICTION = 0.9f;
    static final float DRONE_REACH = 300f, DRONE_ORBIT = 100f;

    /** How far a turret looks for something to shoot, from its tank's centre (01 §4, "Turrets"). */
    static final float TURRET_REACH = 400f;

    /** The fastest a shape drifts on its own; anything faster is a knock, and wears off. */
    static final float SHAPE_DRIFT_MAX = 0.15f;
    static final float SHAPE_FRICTION = 0.9f;

    /**
     * Body damage is dealt per second of contact, and the room ticks 25 times a second.
     * Per second rather than per tick so the stat means the same thing whatever the tick
     * rate, and so that the number in the table reads as what a player feels: a base tank
     * breaks a square (10 health) in half a second and pays about four for it.
     */
    static final float CONTACT_DAMAGE_PER_TICK = 1f / 25f;

    /**
     * What killing a tank is worth to the killer, as a share of what the victim had earned.
     *
     * A share rather than a flat amount so that hunting is worth more than farming when
     * there is something worth hunting, and worth less when there is not. The floor stops a
     * fresh spawn being worth nothing at all, which would make spawn-camping pointless —
     * and pointless is not the same as unrewarding, since it would still deny the victim.
     */
    static final float TANK_KILL_XP_SHARE = 0.25f;
    static final int TANK_KILL_XP_FLOOR = 10;

    /**
     * Assists (01 §7, plan item 65): the players a tank remembers hurting it, three, a two-on-one
     * with one to spare; a hit within five seconds of its death, a fight's length, counts; an
     * assist pays a quarter of what the kill paid, on top of the killer's, so finishing is still
     * the reason to play on while the work before it is paid too. First cuts, each one constant.
     */
    static final int ATTACKERS = 3;
    static final int ASSIST_TICKS = 125;
    static final float ASSIST_XP_SHARE = 0.25f;

    private final World world;
    private final Content content;
    private final IntList scratch = new IntList(256);
    private final KillLog kills = new KillLog();
    /** A maze's walls (01 §8.9); null in every room without one. */
    private Walls walls;

    /**
     * The largest radius anything can have, which is how far a collision query must reach.
     *
     * The grid files an entity by its centre, so a query has to span the other entity's
     * radius as well as its own. Using the tank radius here was correct only while every
     * shape was smaller than a tank; an alpha pentagon is nearly three times one, and a
     * bullet would have passed straight through the middle of it.
     */
    private final float maxRadius;

    private int tick;

    public Room(World world) {
        this(world, Content.defaults());
    }

    public Room(World world, Content content) {
        this.world = world;
        this.content = content;
        this.maxRadius = Math.max(TANK_RADIUS * content.classes().largestBody(), content.maxShapeRadius());
    }

    public World world() {
        return world;
    }

    public Content content() {
        return content;
    }

    public int tick() {
        return tick;
    }

    /** Gives this room a maze's walls, or takes them away with null (01 §8.9). */
    public void setWalls(Walls walls) {
        this.walls = walls;
    }

    public Walls walls() {
        return walls;
    }

    /** This tick's kills. The room thread drains and clears it; the simulation only fills it. */
    public KillLog kills() {
        return kills;
    }

    /** Advances one fixed timestep. Allocates nothing in steady state. */
    public void step(PhaseTimer t) {
        tick++;

        t.start(PHASE_TANKS);
        updateTanks();
        t.stop(PHASE_TANKS);

        t.start(PHASE_BULLETS);
        updateBullets();
        t.stop(PHASE_BULLETS);

        t.start(PHASE_SHAPES);
        updateShapes();
        t.stop(PHASE_SHAPES);

        t.start(PHASE_HASH);
        rebuildHash();
        t.stop(PHASE_HASH);

        t.start(PHASE_COLLIDE);
        collide();
        t.stop(PHASE_COLLIDE);

        t.start(PHASE_SWEEP);
        world.sweep();
        t.stop(PHASE_SWEEP);
    }

    // ---- phases -------------------------------------------------------------------

    private void updateTanks() {
        StatTable table = content.stats();
        IntList ids = world.tanks;
        for (int i = 0; i < ids.size; i++) {
            Entity e = world.entities[ids.items[i]];
            if (!e.alive) {
                // Killed from outside the step — a player leaving — and still in the list
                // until this tick's sweep. Without this it moved, regenerated and fired once
                // more, a bullet carrying the tag of a player whose result was already sent.
                continue;
            }
            TankStats stats = world.tankStats[e.id];
            stats.refresh(table);
            applyMaxHealth(e, stats, content.classes());

            float ax;
            float ay;
            // A tank nobody drives fires at will, as the benchmark's do; a hunting one at its quarry.
            boolean fires = true;
            if (e.playerControlled) {
                ax = e.moveX;
                ay = e.moveY;
                e.angle = e.aimAngle;
            } else if (e.anchored) {
                // A dominator: nowhere to go, and whatever a knock gave it is taken back first.
                ax = 0f;
                ay = 0f;
                e.vx = 0f;
                e.vy = 0f;
            } else if (e.hunts) {
                ax = 0f;
                ay = 0f;
                fires = false;
                Entity quarry = quarry(e);
                if (quarry != null) {
                    float dx = quarry.x - e.x;
                    float dy = quarry.y - e.y;
                    float d2 = dx * dx + dy * dy;
                    e.angle = (float) Math.atan2(dy, dx);
                    if (d2 > HUNT_CLOSE * HUNT_CLOSE) {
                        ax = (float) Math.cos(e.angle);
                        ay = (float) Math.sin(e.angle);
                    }
                    fires = d2 < HUNT_FIRE * HUNT_FIRE;
                }
            } else {
                // Stands in for player input: a wandering heading, re-rolled occasionally.
                if ((tick + e.id) % 17 == 0) {
                    e.angle = world.rng.nextFloat(0f, (float) (Math.PI * 2));
                }
                ax = (float) Math.cos(e.angle);
                ay = (float) Math.sin(e.angle);
            }
            float accel = tankAccel(stats);
            e.vx = (e.vx + ax * accel) * TANK_FRICTION;
            e.vy = (e.vy + ay * accel) * TANK_FRICTION;
            e.x += e.vx;
            e.y += e.vy;
            clampToMap(e);
            if (walls != null) {
                walls.pushOut(e);
            }

            if (e.hp < e.maxHp) {
                // Slowly always; fast once left alone long enough (01 §3). The slow part was
                // all there was, so a tank that escaped needed over a minute whatever it did.
                float rate = stats.value(Stat.HEALTH_REGEN);
                Recovery recovery = content.recovery();
                if (tick - e.lastDamagedTick >= recovery.quietTicks()) {
                    rate += recovery.burstPerTick();
                }
                e.hp = Math.min(e.maxHp, e.hp + e.maxHp * rate);
            }
            // Reload always advances; a player only shoots when they ask to.
            if (e.reloadTicks > 0) {
                e.reloadTicks--;
            }
            ClassTable.TankClass tankClass = content.classes().get(stats.classId);
            // Its body is its class's, every tick: chosen, put back into the world (02 §10) or
            // returned to Basic by a death, it is the right size without a rule of its own.
            e.radius = TANK_RADIUS * tankClass.bodySize();
            // Its player's view: ahead by its class's zoom while zoom is held (01 §4).
            float zoom = e.playerControlled && e.zooming ? tankClass.zoom() : 0f;
            e.viewShiftX = (float) Math.cos(e.angle) * zoom;
            e.viewShiftY = (float) Math.sin(e.angle) * zoom;
            // Hidden after standing still, the trigger let go, if its class hides (D-23). Being
            // pushed is not moving. A bot never stands still, so never hides.
            if (!e.playerControlled || e.moveX != 0f || e.moveY != 0f || e.attacking || e.wantsFire) {
                e.revealedTick = tick;
            }
            e.hidden = tankClass.hidesAfterTicks() > 0 && tick - e.revealedTick >= tankClass.hidesAfterTicks();
            boolean trigger = e.playerControlled ? e.wantsFire : fires;
            if (trigger && e.selfVolley && e.reloadTicks > 0) {
                // The drones began this volley, and the trigger is pulled during it: the rest of
                // the class joins it now rather than waiting out a reload nothing used (01 §4).
                // During it: at its end the next volley starts below, with every barrel, and
                // joining this one too fired them twice in a tick.
                e.volleyPending |= tankClass.allBarrels() & ~tankClass.selfFiring();
                e.selfVolley = false;
                e.wantsFire = false;
            }
            // The last volley's barrels first: every one is due by the time the reload runs out.
            fireDueBarrels(e, stats, tankClass);
            // Not while protected; a held trigger fires the moment protection ends. Without the
            // trigger only what fires by itself does: a class's drones stay out whether or not
            // the trigger is held, and its other barrels wait for it (01 §4).
            int due = trigger ? tankClass.allBarrels() : tankClass.selfFiring();
            if (e.reloadTicks <= 0 && due != 0 && !e.protectedAt(tick)) {
                int reload = reloadTicks(stats, tankClass);
                e.reloadTicks = reload;
                e.wantsFire = false;
                e.volleyPending = due;
                e.selfVolley = !trigger;
                e.volleyStartTick = tick;
                e.volleyReload = reload;
                fireDueBarrels(e, stats, tankClass);
            }
        }
    }

    /**
     * What a tank's input direction is multiplied by each tick: the one place it is worked out, for
     * the step and for the client that predicts it (02 §9, D-62). As of the last refresh.
     */
    public static float tankAccel(TankStats stats) {
        return TANK_ACCEL * stats.value(Stat.MOVEMENT_SPEED);
    }

    private static int reloadTicks(TankStats stats, ClassTable.TankClass tankClass) {
        return reloadTicks(stats.value(Stat.RELOAD), tankClass.reloadMul());
    }

    /** At least one tick: spawnTank staggers the first shot with nextInt(reloadTicks), which throws on zero. */
    static int reloadTicks(float reload, float reloadMul) {
        return Math.max(1, Math.round(reload * reloadMul));
    }

    /**
     * Fires each barrel of the volley in progress whose delay has come (01 §4): a Twin's second
     * barrel half the volley's reload after its first. A delay of a fraction under one is at
     * most the whole reload, so a volley has always finished when the next may begin.
     */
    private void fireDueBarrels(Entity tank, TankStats stats, ClassTable.TankClass tankClass) {
        if (tank.volleyPending == 0) {
            return;
        }
        int elapsed = tick - tank.volleyStartTick;
        for (int i = 0; i < tankClass.barrels().size(); i++) {
            ClassTable.Barrel barrel = tankClass.barrels().get(i);
            if ((tank.volleyPending & (1 << i)) != 0
                    && Math.round(barrel.delay() * tank.volleyReload) <= elapsed) {
                tank.volleyPending &= ~(1 << i);
                fire(tank, stats, barrel);
            }
        }
    }

    /**
     * Keeps a tank's health pool in step with its stats.
     *
     * The increase is added to current health rather than only to the ceiling. Levelling
     * otherwise makes a tank *worse* the moment it happens — same health, larger bar — which
     * is the opposite of what the player just earned.
     */
    private static void applyMaxHealth(Entity e, TankStats stats, ClassTable classes) {
        // Times its class's multiplier: 1 but for a boss (01 §8.5).
        float max = stats.value(Stat.MAX_HEALTH) * classes.get(stats.classId).healthMul();
        if (max != e.maxHp) {
            if (max > e.maxHp) {
                e.hp += max - e.maxHp;
            }
            e.maxHp = max;
            e.hp = Math.min(e.hp, max);
        }
    }

    private void fire(Entity tank, TankStats stats, ClassTable.Barrel barrel) {
        if (barrel.kind() == ClassTable.Barrel.CONVERT) {
            return;                      // it launches nothing: squares become its drones (convert)
        }
        if ((barrel.kind() == ClassTable.Barrel.DRONE || barrel.kind() == ClassTable.Barrel.MINION)
                && dronesOf(tank) >= content.classes().get(stats.classId).maxDrones()) {
            return;                      // as many out as the class keeps: the barrel waits
        }
        float line = tank.angle + barrel.angle();
        if (barrel.turret()) {
            Entity target = turretTarget(tank, line, barrel.arc());
            if (target == null) {
                return;                  // nothing in its part of the circle: its turn passes
            }
            line = (float) Math.atan2(target.y - tank.y, target.x - tank.x);
        }
        if (launch(tank, tank, line, stats, barrel) == null) {
            return;                      // pool exhausted: refuse, never grow
        }
        // The shot pushes back along the barrel's line: a Destroyer staggers, and a Tri-Angle's
        // two rear barrels drive it forward.
        tank.vx -= (float) Math.cos(line) * barrel.recoil();
        tank.vy -= (float) Math.sin(line) * barrel.recoil();
    }

    /**
     * A projectile from {@code from} along {@code line}, as {@code barrel} makes it, and
     * {@code owner}'s for every rule that asks whose it is: from a tank's barrel, or from a
     * minion, which shoots for its tank (01 §4).
     *
     * @return it, or null when the pool is exhausted
     */
    private Entity launch(Entity owner, Entity from, float line, TankStats stats, ClassTable.Barrel barrel) {
        Entity b = world.spawn(Entity.KIND_BULLET);
        if (b == null) {
            return null;
        }
        float ca = (float) Math.cos(line), sa = (float) Math.sin(line);
        float radius = ClassTable.BULLET_RADIUS * barrel.sizeMul();
        float reach = from.radius + radius + 1f;
        b.x = from.x + ca * reach - sa * barrel.side();
        b.y = from.y + sa * reach + ca * barrel.side();
        // Drawn only for a barrel with a spread, so the others leave the room's random
        // sequence as it was.
        float heading = barrel.spread() > 0f
                ? line + world.rng.nextFloat(-barrel.spread(), barrel.spread())
                : line;
        float speed = bulletSpeed(BULLET_SPEED * stats.value(Stat.BULLET_SPEED) * barrel.speedMul());
        b.vx = (float) Math.cos(heading) * speed;
        b.vy = (float) Math.sin(heading) * speed;
        b.radius = radius;
        b.mass = BULLET_MASS;
        b.hp = stats.value(Stat.BULLET_PENETRATION) * barrel.penetrationMul();
        b.maxHp = b.hp;
        b.damage = stats.value(Stat.BULLET_DAMAGE) * barrel.damageMul();
        b.ownerId = owner.id;
        b.ownerGeneration = owner.generation;
        b.playerTag = owner.playerTag;     // copied now: the shooter may not outlive the shot
        b.team = owner.team;
        b.lifetimeTicks = barrel.lifetimeTicks();
        if (barrel.kind() != ClassTable.Barrel.BULLET) {
            // A trap slows to a stop, and a drone or a minion is steered, none of which a client
            // can extrapolate from a create: each is sent as a unit, and updated (02 §4). A
            // square made into a drone is a drone.
            b.subtype = (byte) (barrel.kind() == ClassTable.Barrel.CONVERT ? ClassTable.Barrel.DRONE : barrel.kind());
            b.wireClass = Entity.WIRE_UNIT;
            b.angle = line;
        }
        if ((b.subtype == ClassTable.Barrel.DRONE || b.subtype == ClassTable.Barrel.MINION)
                && barrel.lifetimeTicks() == ClassTable.Barrel.NO_EXPIRY) {
            b.lifetimeTicks = -1;        // it lasts until used up or its owner is gone
        }
        return b;
    }

    /**
     * What a turret on {@code tank} shoots at (01 §4, "Turrets"): the nearest thing within
     * {@link #TURRET_REACH} of the tank and {@code arc} either side of {@code line}, a tank before
     * a shape; not the tank itself, its team when it has one, a tank in its spawn protection, or
     * a hidden one.
     *
     * The hash is as of the last step, which a tank crosses in under two units, so the query
     * reaches a tank's width further and each is measured where it is now.
     */
    private Entity turretTarget(Entity tank, float line, float arc) {
        world.hash.queryInto(tank.x, tank.y, TURRET_REACH + TANK_RADIUS, scratch);
        Entity tankFound = null;
        Entity shapeFound = null;
        float tankD2 = Float.MAX_VALUE;
        float shapeD2 = Float.MAX_VALUE;
        for (int j = 0; j < scratch.size; j++) {
            Entity o = world.entities[scratch.items[j]];
            if (!o.alive || o == tank || o.kind == Entity.KIND_BULLET) {
                continue;
            }
            if (o.kind == Entity.KIND_TANK
                    && ((tank.team != 0 && o.team == tank.team) || o.protectedAt(tick) || o.hidden
                        || (tank.anchored && o.anchored))) {
                continue;                // nor one dominator at another (01 §8.7)
            }
            float dx = o.x - tank.x;
            float dy = o.y - tank.y;
            float d2 = dx * dx + dy * dy;
            if (d2 > TURRET_REACH * TURRET_REACH
                    || (o.kind == Entity.KIND_TANK ? d2 >= tankD2 : d2 >= shapeD2)) {
                continue;
            }
            if (arc < (float) Math.PI
                    && Math.abs(Math.IEEEremainder(Math.atan2(dy, dx) - line, 2 * Math.PI)) > arc) {
                continue;
            }
            if (o.kind == Entity.KIND_TANK) {
                tankFound = o;
                tankD2 = d2;
            } else {
                shapeFound = o;
                shapeD2 = d2;
            }
        }
        return tankFound != null ? tankFound : shapeFound;
    }

    /**
     * The drones and minions a tank has out: counted, not kept, so no drone's death has to
     * report it.
     */
    private int dronesOf(Entity tank) {
        int n = 0;
        IntList ids = world.bullets;
        for (int i = 0; i < ids.size; i++) {
            Entity d = world.entities[ids.items[i]];
            if (d.alive && (d.subtype == ClassTable.Barrel.DRONE || d.subtype == ClassTable.Barrel.MINION)
                    && isShooterOf(tank, d)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Steers a drone toward its point (01 §4). A drone whose owner is no longer the tank that
     * launched it, or no longer keeps drones, goes with it: a death, a leave or a class change.
     */
    private void steerDrone(Entity d) {
        Entity owner = world.entities[d.ownerId];
        if (!owner.alive || owner.kind != Entity.KIND_TANK || owner.generation != d.ownerGeneration
                || content.classes().get(world.tankStats[owner.id].classId).maxDrones() == 0
                || (d.lifetimeTicks > 0 && --d.lifetimeTicks == 0)) {
            world.kill(d);               // or its time is up: a Battleship's drones last 150 ticks
            return;
        }
        float tx;
        float ty;
        if (!owner.playerControlled || owner.attacking) {
            tx = owner.x + (float) Math.cos(owner.angle) * DRONE_REACH;
            ty = owner.y + (float) Math.sin(owner.angle) * DRONE_REACH;
        } else {
            // Around the owner, each drone at its own place on the circle, by its slot.
            float around = tick * 0.05f + d.id * 0.8f;
            tx = owner.x + (float) Math.cos(around) * DRONE_ORBIT;
            ty = owner.y + (float) Math.sin(around) * DRONE_ORBIT;
        }
        float dx = tx - d.x;
        float dy = ty - d.y;
        float d2 = dx * dx + dy * dy;
        if (d2 > 1f) {
            float inv = DRONE_ACCEL / (float) Math.sqrt(d2);
            d.vx += dx * inv;
            d.vy += dy * inv;
        }
        d.vx *= DRONE_FRICTION;
        d.vy *= DRONE_FRICTION;
        d.x = Math.max(d.radius, Math.min(world.width - d.radius, d.x + d.vx));
        d.y = Math.max(d.radius, Math.min(world.height - d.radius, d.y + d.vy));
        if (walls != null) {
            walls.pushOut(d);
        }
        if (d.vx * d.vx + d.vy * d.vy > 1e-4f) {
            d.angle = (float) Math.atan2(d.vy, d.vx);
        }
    }

    /**
     * A minion fires along its tank's aim every two of its tank's reloads while its tank attacks
     * (01 §4, "Minions"): always, for a bot's. Its reload runs down whether or not it may fire, as
     * a tank's does.
     */
    private void minionShoots(Entity minion) {
        if (minion.reloadTicks > 0) {
            minion.reloadTicks--;
        }
        Entity owner = world.entities[minion.ownerId];        // steerDrone has checked it is the same
        if (minion.reloadTicks > 0 || (owner.playerControlled && !owner.attacking)) {
            return;
        }
        TankStats stats = world.tankStats[owner.id];
        minion.reloadTicks = 2 * reloadTicks(stats, content.classes().get(stats.classId));
        launch(owner, minion, owner.angle, stats, ClassTable.MINION_SHOT);
    }

    /**
     * A missile fires as it flies (01 §4, "The rest of the tree"): a Rocketeer's straight back, a
     * Skimmer's either side of its facing as it turns. Its shots are its tank's, on its tank's
     * stats; once its tank is gone it flies on silent, having nothing to shoot for.
     */
    private void missileShoots(Entity missile) {
        boolean skimmer = missile.subtype == ClassTable.Barrel.SKIMMER;
        if (skimmer) {
            missile.angle += ClassTable.SKIMMER_SPIN;
        }
        Entity owner = shooterOf(missile);
        if (owner == null
                || ++missile.reloadTicks < (skimmer ? ClassTable.SKIMMER_INTERVAL : ClassTable.ROCKET_INTERVAL)) {
            return;
        }
        missile.reloadTicks = 0;
        TankStats stats = world.tankStats[owner.id];
        if (skimmer) {
            float side = (float) (Math.PI / 2);
            launch(owner, missile, missile.angle + side, stats, ClassTable.SKIMMER_SHOT);
            launch(owner, missile, missile.angle - side, stats, ClassTable.SKIMMER_SHOT);
        } else {
            launch(owner, missile, missile.angle + (float) Math.PI, stats, ClassTable.ROCKET_SHOT);
        }
    }

    /**
     * A bullet's speed, rounded to half a unit a tick and at most 127.5: what a byte of the
     * create carries exactly (02 §4). A client draws a bullet from its create alone for its
     * whole life, so the speed it is told has to be the speed it has, not the nearest of a few.
     */
    static float bulletSpeed(float unitsPerTick) {
        return Math.min(255, Math.round(unitsPerTick * 2f)) / 2f;
    }

    /**
     * Gives one of the arena's own tanks a class, whatever the upgrade rules say: a boss's, which
     * no upgrade reaches (01 §8.5). Given to a tank just spawned, or the enraged form for the
     * Guardian, whose barrels are the same, so no volley needs dropping as a choice drops one.
     *
     * @return whether it changed: not for a tank that is not alive
     */
    public boolean assignClass(Entity tank, int classId) {
        if (!tank.alive) {
            return false;
        }
        world.tankStats[tank.id].classId = classId;
        return true;
    }

    /**
     * Rebuilds a tank at a level where it stands: the sandbox's power (01 §8.10). Basic, every
     * point that level gives unspent, full health; what it wears stays, and a volley under way,
     * its old class's, is dropped.
     *
     * @return whether it was rebuilt: not a tank that is not alive, nor a level outside the table
     */
    public boolean setLevel(Entity tank, int level) {
        if (!tank.alive || tank.kind != Entity.KIND_TANK || level < 1 || level > content.levels().maxLevel()) {
            return false;
        }
        TankStats stats = world.tankStats[tank.id];
        stats.restart();
        stats.addXp(content.levels().xpRequired(level), content.levels());
        stats.refresh(content.stats());
        applyMaxHealth(tank, stats, content.classes());
        tank.hp = tank.maxHp;
        tank.volleyPending = 0;
        tank.selfVolley = false;
        return true;
    }

    /**
     * Makes a tank the class it asked for, if it may be (01 §4): the class's parent is what
     * it is now, and it has reached the class's level. Anything else is refused, and nothing
     * changes; a request that cannot be honoured is not worth a closed connection.
     *
     * A volley in progress is dropped: its remaining barrels belonged to the old class.
     *
     * @return whether it changed
     */
    public boolean chooseClass(Entity tank, int classId) {
        if (!tank.alive || tank.kind != Entity.KIND_TANK) {
            return false;
        }
        TankStats stats = world.tankStats[tank.id];
        if (!content.classes().mayChoose(stats.classId, stats.level, classId)) {
            return false;
        }
        stats.classId = classId;
        tank.volleyPending = 0;
        tank.selfVolley = false;
        return true;
    }

    private void updateBullets() {
        IntList ids = world.bullets;
        for (int i = 0; i < ids.size; i++) {
            Entity e = world.entities[ids.items[i]];
            if (!e.alive) {
                continue;
            }
            if (e.subtype == ClassTable.Barrel.DRONE || e.subtype == ClassTable.Barrel.MINION) {
                steerDrone(e);
                if (e.alive && e.subtype == ClassTable.Barrel.MINION) {
                    minionShoots(e);
                }
                continue;
            }
            if (e.subtype == ClassTable.Barrel.ROCKET || e.subtype == ClassTable.Barrel.SKIMMER) {
                missileShoots(e);
            }
            e.x += e.vx;
            e.y += e.vy;
            if (e.subtype == ClassTable.Barrel.TRAP) {
                e.vx *= TRAP_FRICTION;
                e.vy *= TRAP_FRICTION;
            }
            if (--e.lifetimeTicks <= 0
                    || e.x < 0 || e.y < 0 || e.x > world.width || e.y > world.height
                    || (walls != null && walls.hits(e.x, e.y, e.radius))) {
                world.kill(e);                 // a wall ends it as the map's edge does (01 §8.9, D-48)
            }
        }
    }

    private void updateShapes() {
        IntList ids = world.shapes;
        for (int i = 0; i < ids.size; i++) {
            Entity e = world.entities[ids.items[i]];
            if (!e.alive) {
                continue;
            }
            e.x += e.vx;
            e.y += e.vy;
            // A knock wears off; the slow drift a shape spawns with does not. There was no
            // friction at all, so every push a shape ever took, it kept.
            if (e.vx * e.vx + e.vy * e.vy > SHAPE_DRIFT_MAX * SHAPE_DRIFT_MAX) {
                e.vx *= SHAPE_FRICTION;
                e.vy *= SHAPE_FRICTION;
            }
            e.angle += 0.01f;            // cosmetic spin; the client animates this locally
            clampToMap(e);
            if (walls != null) {
                walls.pushOut(e);
            }
        }
    }

    private void rebuildHash() {
        SpatialHash h = world.hash;
        h.clear();
        insertAll(h, world.tanks);
        insertAll(h, world.bullets);
        insertAll(h, world.shapes);
    }

    private void insertAll(SpatialHash h, IntList ids) {
        for (int i = 0; i < ids.size; i++) {
            Entity e = world.entities[ids.items[i]];
            h.insert(e.id, e.x, e.y);
        }
    }


    /**
     * Bullets drive the collision pass, and tanks then handle what their bodies touch: other
     * tanks, and shapes. Each unordered pair is therefore considered once, without a visited
     * set.
     */
    private void collide() {
        IntList bullets = world.bullets;
        for (int i = 0; i < bullets.size; i++) {
            Entity b = world.entities[bullets.items[i]];
            if (!b.alive) {
                continue;
            }
            world.hash.queryInto(b.x, b.y, b.radius + maxRadius, scratch);
            for (int j = 0; j < scratch.size; j++) {
                Entity o = world.entities[scratch.items[j]];
                if (o == b || !o.alive || isShooterOf(o, b)) {
                    continue;
                }
                if (o.kind == Entity.KIND_BULLET && (sameShooter(o, b) || o.id < b.id)) {
                    continue;            // same shooter, or already handled from the other side
                }
                if (sameTeam(o, b)) {
                    continue;            // a team's own do it no harm, and are not spent on it (01 §8.3)
                }
                if (!overlaps(b, o)) {
                    continue;
                }
                // A protected tank absorbs the bullet unhurt: solid, not a ghost.
                boolean hurts = !o.protectedAt(tick);
                if (hurts) {
                    o.hp -= b.damage;
                }
                b.hp -= contactDamage(o);
                if (hurts && o.kind == Entity.KIND_TANK) {
                    o.lastDamagedTick = tick;
                    if (o.hp > 0f) {             // a lethal hit is the kill, and takes no assist's place
                        hurtBy(o, b.ownerId, b.ownerGeneration, b.playerTag);
                    }
                }
                if (o.kind != Entity.KIND_BULLET || o.subtype == ClassTable.Barrel.TRAP) {
                    // A bullet is never pushed. It is classed on the wire as *predictable*,
                    // which buys about four fifths of the bandwidth plan (D-9) and costs the
                    // client one create instead of an update every snapshot — and a bullet
                    // that can be shoved sideways is not predictable. Measured before this
                    // guard: 31 of 299 bullets had their velocity changed in flight by a
                    // collision they survived, and every one of them drifted away from where
                    // the client had drawn it. Being nudged by another bullet is not a
                    // behaviour anything asked for; straight flight is. A trap is a unit,
                    // updated rather than extrapolated, so it is pushed as a tank is (01 §4).
                    knockback(o, b);
                }
                if (b.subtype == ClassTable.Barrel.TRAP) {
                    knockback(b, o);
                }
                if (o.hp <= 0f) {
                    int xp = experienceFor(o);
                    assists(o, b.playerTag, xp);
                    if (o.captures) {
                        capture(o, b.playerTag, b.team);
                    } else {
                        world.kill(o);
                    }
                    if (o.kind != Entity.KIND_BULLET) {
                        awardExperience(b, xp);
                        if (b.playerTag != 0 || o.playerTag != 0) {
                            kills.record(b.playerTag, o.playerTag, o.kind, xp);
                        }
                    }
                    if (o.kind == Entity.KIND_SHAPE) {
                        convert(shooterOf(b), o);
                        replaceShape(o);  // keep the population, and its mix, steady
                    }
                }
                if (b.hp <= 0f) {
                    world.kill(b);
                    break;
                }
            }
        }

        // Bodies. Tanks used to push each other apart here and nothing more, and were never
        // tested against shapes at all, so they drove through them and body damage bought
        // nothing but armour against bullets (M-8). Free-for-all, like bullets: team rules
        // for both arrive with match modes.
        IntList tanks = world.tanks;
        for (int i = 0; i < tanks.size; i++) {
            Entity a = world.entities[tanks.items[i]];
            if (!a.alive) {
                continue;
            }
            world.hash.queryInto(a.x, a.y, a.radius + maxRadius, scratch);
            for (int j = 0; j < scratch.size && a.alive; j++) {
                Entity o = world.entities[scratch.items[j]];
                if (o == a || !o.alive || o.kind == Entity.KIND_BULLET) {
                    continue;                // bullets were settled above
                }
                if (o.kind == Entity.KIND_TANK && o.id < a.id) {
                    continue;                // this pair was settled from the other side
                }
                if (!overlaps(a, o)) {
                    continue;
                }
                knockback(o, a);
                knockback(a, o);
                if (!sameTeam(a, o)) {
                    bodyContact(a, o);       // teammates are pushed apart, and neither is hurt (01 §8.3)
                }
            }
        }
    }

    /**
     * One tick of a tank touching another body: each takes the other's body damage for a
     * twenty-fifth of a second, both worked out before either is applied, so the order the
     * pair was found in decides nothing.
     */
    private void bodyContact(Entity tank, Entity other) {
        if (tank.protectedAt(tick) || other.protectedAt(tick)) {
            return;                          // pushed, above, but neither side hurt (01 §7)
        }
        float toOther = bodyDamage(tank) * CONTACT_DAMAGE_PER_TICK;
        float toTank = contactDamage(other) * CONTACT_DAMAGE_PER_TICK;
        other.hp -= toOther;
        tank.hp -= toTank;
        tank.lastDamagedTick = tick;
        if (other.kind == Entity.KIND_TANK) {
            other.lastDamagedTick = tick;
            if (tank.hp > 0f) {              // a lethal touch is the kill, and takes no assist's place
                hurtBy(tank, other.id, other.generation, other.playerTag);
            }
            if (other.hp > 0f) {
                hurtBy(other, tank.id, tank.generation, tank.playerTag);
            }
        }
        if (other.hp <= 0f) {
            bodyKill(tank, other);
        }
        if (tank.hp <= 0f) {
            bodyKill(other.kind == Entity.KIND_TANK ? other : null, tank);
        }
    }

    /**
     * Settles a death by contact the way a bullet kill is settled: the killer is paid while it
     * lives, the kill is logged whenever a player is involved (the tally counts deaths from
     * the log, so a tank a shape killed is logged too, with nobody to credit), and a broken
     * shape is replaced.
     *
     * "Lives" means health left, not only not yet removed: a killer taking lethal damage in the
     * same contact is still {@code alive} until its own death is settled, and paying it first
     * let the levels refill its health, so the pair's slot order decided who survived.
     */
    private void bodyKill(Entity killer, Entity victim) {
        int xp = experienceFor(victim);
        assists(victim, killer == null ? 0L : killer.playerTag, xp);
        if (victim.captures) {
            capture(victim, killer == null ? 0L : killer.playerTag, killer == null ? 0 : killer.team);
        } else {
            world.kill(victim);
        }
        long killerTag = killer == null ? 0L : killer.playerTag;
        if (killer != null && killer.alive && killer.hp > 0f) {
            credit(killer, xp);
        }
        if (killerTag != 0 || victim.playerTag != 0) {
            kills.record(killerTag, victim.playerTag, victim.kind, xp);
        }
        if (victim.kind == Entity.KIND_SHAPE) {
            convert(killer, victim);
            replaceShape(victim);
        }
    }

    /**
     * Makes a square that a Necromancer broke, by a drone or its body, into one of its drones
     * where the square was, while it has room for one (01 §4): made as its convert barrel would
     * make a drone, at rest. The square is still paid for and replaced; this is a drone more.
     */
    private void convert(Entity tank, Entity shape) {
        if (tank == null || !tank.alive || shape.subtype != ShapeTable.SQUARE) {
            return;
        }
        TankStats stats = world.tankStats[tank.id];
        ClassTable.TankClass tankClass = content.classes().get(stats.classId);
        ClassTable.Barrel converter = tankClass.converter();
        if (converter == null || dronesOf(tank) >= tankClass.maxDrones()) {
            return;
        }
        Entity drone = launch(tank, shape, 0f, stats, converter);
        if (drone != null) {
            drone.x = shape.x;
            drone.y = shape.y;
            drone.vx = 0f;
            drone.vy = 0f;
        }
    }

    /**
     * How much of a bullet is used up hitting this.
     *
     * A tank's is its body damage, which is a stat, so an armoured build absorbs shots that
     * would pass through a glass one. Everything else carries its own.
     */
    private float contactDamage(Entity o) {
        if (o.kind == Entity.KIND_TANK) {
            return bodyDamage(o);
        }
        return o.damage;
    }

    /** A tank's body damage: its stat, times its class's (a Spike's is half as much again). */
    private float bodyDamage(Entity tank) {
        TankStats stats = world.tankStats[tank.id];
        return stats.value(Stat.BODY_DAMAGE) * content.classes().get(stats.classId).bodyDamageMul();
    }

    /** What killing this is worth. Shapes are worth their kind; tanks, a share of their life. */
    private int experienceFor(Entity victim) {
        if (victim.kind == Entity.KIND_SHAPE) {
            return content.shapes().xpOf(victim.subtype);
        }
        if (victim.kind == Entity.KIND_TANK) {
            TankStats v = world.tankStats[victim.id];
            return Math.max(TANK_KILL_XP_FLOOR, (int) (v.xp * TANK_KILL_XP_SHARE));
        }
        return 0;
    }

    /**
     * Credits a kill to whoever fired the bullet, if they are still the same tank.
     *
     * The slot alone is not enough. A bullet outlives its shooter routinely, and by the time
     * it lands the slot may hold a different tank — so the generation recorded at firing has
     * to match, or the experience goes to whoever inherited the seat.
     */
    private void awardExperience(Entity bullet, int xp) {
        Entity shooter = shooterOf(bullet);
        if (xp > 0 && shooter != null) {
            credit(shooter, xp);
        }
    }

    /**
     * A player's hit on a tank, remembered for assists (01 §7): their place in its ring moved on,
     * or the oldest place taken. A hit by no player, a bot's or a shape's, is not remembered.
     */
    private void hurtBy(Entity tank, int attackerId, short attackerGeneration, long attackerTag) {
        if (tank.kind != Entity.KIND_TANK || attackerTag == 0L || attackerTag == tank.playerTag) {
            return;
        }
        TankStats ring = world.tankStats[tank.id];
        int place = -1;
        int oldest = 0;
        for (int i = 0; i < ATTACKERS; i++) {
            if (ring.attackerTags[i] == attackerTag) {
                place = i;
                break;
            }
            if (ring.attackerTags[i] == 0L
                    || ring.attackerTags[oldest] != 0L && ring.attackerTicks[i] < ring.attackerTicks[oldest]) {
                oldest = i;
            }
        }
        if (place < 0) {
            place = oldest;
        }
        ring.attackerTags[place] = attackerTag;
        ring.attackerIds[place] = attackerId;
        ring.attackerGenerations[place] = attackerGeneration;
        ring.attackerTicks[place] = tick;
    }

    /**
     * A tank's death pays those in its ring who hurt it within {@link #ASSIST_TICKS}, the killer
     * apart: a share of the kill while their tank lives, and an assist logged either way.
     */
    private void assists(Entity victim, long killerTag, int xp) {
        if (victim.kind != Entity.KIND_TANK) {
            return;
        }
        TankStats ring = world.tankStats[victim.id];
        int share = Math.round(xp * ASSIST_XP_SHARE);
        for (int i = 0; i < ATTACKERS; i++) {
            long tag = ring.attackerTags[i];
            if (tag == 0L || tag == killerTag || tick - ring.attackerTicks[i] > ASSIST_TICKS) {
                continue;
            }
            Entity helper = world.entities[ring.attackerIds[i]];
            boolean paid = helper.alive && helper.kind == Entity.KIND_TANK && helper.hp > 0f
                    && helper.generation == ring.attackerGenerations[i];
            if (paid) {
                credit(helper, share);
            }
            kills.recordAssist(tag, victim.playerTag, paid ? share : 0);
        }
    }

    /** The living tank that fired {@code bullet}, if it is still the same tank; otherwise null. */
    private Entity shooterOf(Entity bullet) {
        if (bullet.ownerId < 0) {
            return null;
        }
        Entity shooter = world.entities[bullet.ownerId];
        return shooter.alive && shooter.kind == Entity.KIND_TANK && shooter.generation == bullet.ownerGeneration
                ? shooter : null;
    }

    /**
     * Pays a living tank experience from outside a kill: a mode's bonus (01 §3), or a
     * benchmark's room of tanks already grown. A bot spends it as it would what it earned.
     */
    public void grantExperience(Entity tank, int xp) {
        if (tank.alive && tank.kind == Entity.KIND_TANK) {
            credit(tank, xp);
        }
    }

    /** Pays a living tank for a kill, and levels it if that is enough. */
    private void credit(Entity tank, int xp) {
        if (xp <= 0) {
            return;
        }
        TankStats stats = world.tankStats[tank.id];
        if (stats.addXp(xp, content.levels()) > 0) {
            stats.refresh(content.stats());
            applyMaxHealth(tank, stats, content.classes());
            if (!tank.playerControlled) {
                // The class first, so the points go where its caps allow: a bot that became a
                // Smasher having spent them spent them on bullets it cannot fire.
                chooseClassAutomatically(tank, stats);
                spendPointsAutomatically(tank, stats);
            }
        }
    }

    /**
     * Spends a bot's skill points as soon as it earns them, spread evenly: each on the stat,
     * not yet capped, that has the fewest.
     *
     * Not cosmetic. Reload is a stat, and bullet count is what a tick actually costs, so a
     * room of high-level tanks that never upgraded would fire at level-1 rates and the load
     * measurement taken from it would be an underestimate of the real thing. Spent in index
     * order, as it was, a point a level all went to the first stats, and reload got none for
     * forty levels.
     */
    private void spendPointsAutomatically(Entity tank, TankStats stats) {
        ClassTable.TankClass tankClass = content.classes().get(stats.classId);
        while (stats.unspentPoints > 0) {
            int pick = -1;
            for (int s = 0; s < Stat.COUNT; s++) {
                if (stats.points[s] < tankClass.cap(s)
                        && (pick < 0 || stats.points[s] < stats.points[pick])) {
                    pick = s;
                }
            }
            if (pick < 0 || !spendPoint(tank, pick)) {
                return;                     // every stat capped: the rest stay banked
            }
        }
    }

    /**
     * Spends one of a tank's points on {@code stat}, up to what its class allows there (01 §4).
     *
     * @return false when it has none to spend, the stat is at its class's cap, or it is not a stat
     */
    public boolean spendPoint(Entity tank, int stat) {
        TankStats stats = world.tankStats[tank.id];
        return Stat.isValid(stat) && stats.spendPoint(stat, content.classes().get(stats.classId).cap(stat));
    }

    /**
     * Takes each class a bot has reached, at random among those open to it, a tier at a
     * time. For the same reason as its points: a room of bots firing only Basic's single
     * barrel would measure a lighter load than players choosing Twins and Machine Guns.
     *
     * Bounded, not until nothing is open: each choice is a class after the last, since a
     * parent comes before its children, so there are never more rounds than classes. A loop
     * that trusted {@link ClassTable#mayChoose} to run dry hung the room when it did not.
     */
    private void chooseClassAutomatically(Entity tank, TankStats stats) {
        ClassTable classes = content.classes();
        for (int round = 0; round < classes.size(); round++) {
            int open = 0;
            for (int c = 0; c < classes.size(); c++) {
                if (classes.mayChoose(stats.classId, stats.level, c)) {
                    open++;
                }
            }
            if (open == 0) {
                return;
            }
            int pick = world.rng.nextInt(open);
            int chosen = 0;
            for (int c = 0; c < classes.size(); c++) {
                if (classes.mayChoose(stats.classId, stats.level, c) && pick-- == 0) {
                    chosen = c;
                    break;
                }
            }
            if (!chooseClass(tank, chosen)) {
                return;
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------

    /**
     * Whether {@code tank} is the entity that fired {@code bullet}.
     *
     * Slot <em>and</em> generation, never the slot alone. A bullet outlives its shooter
     * routinely, and the slot is reused at once — usually by a shape, because a destroyed
     * shape is replaced immediately. Comparing slots only made every bullet in flight pass
     * straight through whatever took its dead shooter's place, while the experience credit,
     * further down this file, already compared the generation. One identity rule, used
     * everywhere it is needed.
     */
    private static boolean isShooterOf(Entity tank, Entity bullet) {
        return tank.id == bullet.ownerId && tank.generation == bullet.ownerGeneration;
    }

    /** Whether two things are of one team: team 0 is no team, so never (01 §8.3). */
    private static boolean sameTeam(Entity a, Entity b) {
        return a.team != 0 && a.team == b.team;
    }

    /** Whether two bullets were fired by the same tank — not merely from the same slot. */
    private static boolean sameShooter(Entity a, Entity b) {
        return a.ownerId == b.ownerId && a.ownerGeneration == b.ownerGeneration;
    }

    private static boolean overlaps(Entity a, Entity b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        float r = a.radius + b.radius;
        return dx * dx + dy * dy <= r * r;
    }

    /**
     * A dominator's lethal blow (01 §8.7): from a player's tank or bullet, which carries the
     * player's tag and team, it becomes that team's; from anything else it stays as it was. Either way at full health: it
     * is captured, never killed, and the blow is paid and logged as a kill by the caller.
     */
    private static void capture(Entity dominator, long byTag, byte byTeam) {
        if (byTag != 0) {
            dominator.team = byTeam;
        }
        dominator.hp = dominator.maxHp;
    }

    /** Pushes {@code target} away from {@code source}, scaled by their masses. */
    private static void knockback(Entity target, Entity source) {
        float dx = target.x - source.x, dy = target.y - source.y;
        float d2 = dx * dx + dy * dy;
        if (d2 < 1e-4f) {
            return;                      // exactly co-located: no meaningful direction
        }
        float inv = 1f / (float) Math.sqrt(d2);
        float force = source.mass / (source.mass + target.mass) * 0.5f;
        target.vx += dx * inv * force;
        target.vy += dy * inv * force;
    }

    private void clampToMap(Entity e) {
        if (e.x < e.radius) {
            e.x = e.radius;
            e.vx = -e.vx * 0.5f;
        } else if (e.x > world.width - e.radius) {
            e.x = world.width - e.radius;
            e.vx = -e.vx * 0.5f;
        }
        if (e.y < e.radius) {
            e.y = e.radius;
            e.vy = -e.vy * 0.5f;
        } else if (e.y > world.height - e.radius) {
            e.y = world.height - e.radius;
            e.vy = -e.vy * 0.5f;
        }
    }

    /** The nearest tank of another team that a player drives, for a hunting tank; null for none. */
    private Entity quarry(Entity hunter) {
        Entity nearest = null;
        float best = Float.MAX_VALUE;
        IntList ids = world.tanks;
        for (int i = 0; i < ids.size; i++) {
            Entity t = world.entities[ids.items[i]];
            if (t.alive && t.playerControlled && t.team != hunter.team) {
                float dx = t.x - hunter.x;
                float dy = t.y - hunter.y;
                float d2 = dx * dx + dy * dy;
                if (d2 < best) {
                    best = d2;
                    nearest = t;
                }
            }
        }
        return nearest;
    }

    // ---- population ---------------------------------------------------------------

    /** A tank belonging to nobody: the benchmark's filler and the bots in tests. */
    public Entity spawnTank(byte team) {
        return spawnTank(team, 0L);
    }

    public Entity spawnTank(byte team, long playerTag) {
        Entity e = world.spawn(Entity.KIND_TANK);
        if (e == null) {
            return null;
        }
        TankStats stats = world.tankStats[e.id];
        stats.refresh(content.stats());

        e.radius = TANK_RADIUS;
        e.team = team;                               // before it is placed: a team has its side
        placeSafely(e);
        // + 1: a step advances the tick before it runs, so the first step this tank lives
        // through is tick + 1, and without it protection lasted one step short of its length.
        e.protectedUntilTick = tick + 1 + content.spawning().protectionTicks();
        e.mass = TANK_MASS;
        e.maxHp = stats.value(Stat.MAX_HEALTH);
        e.hp = e.maxHp;
        e.damage = stats.value(Stat.BODY_DAMAGE);
        // A random initial heading. Without it every tank starts at angle 0 and
        // accelerates identically, so the whole room moves in lockstep — which makes
        // collisions unrepresentative and leaves relative positions constant.
        e.angle = world.rng.nextFloat(0f, (float) (Math.PI * 2));
        // Stagger the first volley. A new tank is Basic.
        e.reloadTicks = 1 + world.rng.nextInt(reloadTicks(stats, content.classes().get(stats.classId)));
        e.lastDamagedTick = tick;                    // quiet since it arrived, not since tick 0
        e.revealedTick = tick;                       // seen arriving: not hidden on its first tick
        e.playerTag = playerTag;
        return e;
    }

    /**
     * Puts a new tank where nothing is on top of it and no tank is within the table's
     * clearance (01 §7). Arriving anywhere used to mean, with body damage, arriving inside an
     * alpha pentagon or against a tank ten levels up. When no tried point is clear of both, the
     * best of them: clear of shapes first, then farthest from the nearest tank.
     *
     * The best so far is kept in fields rather than returned, because tanks respawn in steady
     * state and a tick allocates nothing.
     */
    private void placeSafely(Entity e) {
        Spawning rules = content.spawning();
        // Each team on its own side: team 1 the left third, team 2 the right (01 §8.3).
        float third = world.width / 3f;
        float left = e.team == 2 ? 2 * third : TANK_RADIUS;
        float right = e.team == 1 ? third : world.width - TANK_RADIUS;
        float bestX = 0f;
        float bestY = 0f;
        float bestNearest = -1f;
        boolean bestClear = false;
        for (int i = 0; i < rules.attempts(); i++) {
            float x = world.rng.nextFloat(left, right);
            float y = world.rng.nextFloat(TANK_RADIUS, world.height - TANK_RADIUS);
            boolean clear = !touchesShape(x, y) && (walls == null || !walls.hits(x, y, TANK_RADIUS));
            float nearest = nearestOtherTank(x, y, e);
            if (clear && nearest >= rules.clearance()) {
                e.x = x;
                e.y = y;
                return;
            }
            if ((clear && !bestClear) || (clear == bestClear && nearest > bestNearest)) {
                bestX = x;
                bestY = y;
                bestNearest = nearest;
                bestClear = clear;
            }
        }
        e.x = bestX;
        e.y = bestY;
        if (walls != null) {
            walls.pushOut(e);            // no clear place found: out of any wall all the same
        }
    }

    /** Whether a tank here would overlap a shape. The hash is as of the last step. */
    private boolean touchesShape(float x, float y) {
        world.hash.queryInto(x, y, TANK_RADIUS + maxRadius, scratch);
        for (int j = 0; j < scratch.size; j++) {
            Entity s = world.entities[scratch.items[j]];
            if (s.alive && s.kind == Entity.KIND_SHAPE) {
                float dx = s.x - x, dy = s.y - y, r = s.radius + TANK_RADIUS;
                if (dx * dx + dy * dy <= r * r) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Distance to the nearest living tank other than {@code self}; infinite if there is none. */
    private float nearestOtherTank(float x, float y, Entity self) {
        float best = Float.MAX_VALUE;
        IntList tanks = world.tanks;
        for (int i = 0; i < tanks.size; i++) {
            Entity t = world.entities[tanks.items[i]];
            if (t != self && t.alive) {
                float dx = t.x - x, dy = t.y - y;
                best = Math.min(best, dx * dx + dy * dy);
            }
        }
        return best == Float.MAX_VALUE ? best : (float) Math.sqrt(best);
    }

    /**
     * Clears the world and repopulates it for the next match.
     *
     * Every entity dies, including the players' tanks. Anything holding an entity id across
     * this call is holding a slot that will be recycled — the caller has to forget its ids
     * before the next tick, or it will read someone else's entity out of the same slot.
     */
    public void resetForNewMatch(int shapeCount) {
        killAll(world.tanks);
        killAll(world.bullets);
        killAll(world.shapes);
        world.sweep();
        kills.clear();
        for (int i = 0; i < shapeCount; i++) {
            spawnShape();
        }
    }

    private void killAll(IntList ids) {
        for (int i = 0; i < ids.size; i++) {
            world.kill(world.entities[ids.items[i]]);
        }
    }

    /** Spawns one shape, its kind drawn in proportion to the table's weights. */
    public Entity spawnShape() {
        return spawnShape(content.shapes().pick(world.rng));
    }

    /**
     * Replaces a destroyed shape with one of its kind, somewhere else, so the room keeps the
     * mix its weights drew (M-12). Drawn afresh by weight, the mix drifted toward what is
     * rarely destroyed: after 13 minutes of bots, 105 alpha pentagons in 1 500 shapes where
     * the table says one in two hundred.
     */
    private void replaceShape(Entity destroyed) {
        spawnShape(content.shapes().byId(destroyed.subtype));
    }

    private Entity spawnShape(ShapeTable.Type type) {
        Entity e = world.spawn(Entity.KIND_SHAPE);
        if (e == null) {
            return null;
        }
        e.subtype = type.id();
        e.x = world.rng.nextFloat(type.radius(), world.width - type.radius());
        e.y = world.rng.nextFloat(type.radius(), world.height - type.radius());
        e.vx = world.rng.nextFloat(-0.1f, 0.1f);
        e.vy = world.rng.nextFloat(-0.1f, 0.1f);
        e.radius = type.radius();
        e.mass = type.mass();
        e.hp = type.hp();
        e.maxHp = type.hp();
        e.damage = type.bodyDamage();
        return e;
    }
}
