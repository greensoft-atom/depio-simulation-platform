package com.backend.sim;

import java.util.List;

/**
 * The tank classes and their barrels (docs detailed-design/01-arena.md §4, "The barrel model
 * and the first tier").
 *
 * A class is data: what it fires from where, how often and how far it sees. Firing reads it
 * every volley, so a new class is a row here and nothing else in the simulation changes.
 * Code-defined, like the other tables, until content moves to files; the numbers are as
 * unplayed as the rest.
 */
public final class ClassTable {

    public static final int BASIC = 0;
    public static final int TWIN = 1;
    public static final int SNIPER = 2;
    public static final int MACHINE_GUN = 3;
    public static final int FLANK_GUARD = 4;
    public static final int TRIPLE_SHOT = 5;
    public static final int QUAD_TANK = 6;
    public static final int TWIN_FLANK = 7;
    public static final int ASSASSIN = 8;
    public static final int HUNTER = 9;
    public static final int DESTROYER = 10;
    public static final int GUNNER = 11;
    public static final int TRI_ANGLE = 12;
    public static final int TRAPPER = 13;
    public static final int OVERSEER = 14;
    public static final int TRIPLET = 15;
    public static final int PENTA_SHOT = 16;
    public static final int SPREAD_SHOT = 17;
    public static final int OCTO_TANK = 18;
    public static final int TRIPLE_TWIN = 19;
    public static final int RANGER = 20;
    public static final int PREDATOR = 21;
    public static final int STREAMLINER = 22;
    public static final int SPRAYER = 23;
    public static final int ANNIHILATOR = 24;
    public static final int BOOSTER = 25;
    public static final int FIGHTER = 26;
    public static final int OVERLORD = 27;
    public static final int TRI_TRAPPER = 28;
    public static final int MEGA_TRAPPER = 29;
    public static final int GUNNER_TRAPPER = 30;
    public static final int OVERTRAPPER = 31;
    public static final int HYBRID = 32;
    public static final int AUTO_3 = 33;
    public static final int AUTO_5 = 34;
    public static final int AUTO_GUNNER = 35;
    public static final int AUTO_TRAPPER = 36;
    public static final int SMASHER = 37;
    public static final int SPIKE = 38;
    public static final int AUTO_SMASHER = 39;
    public static final int STALKER = 40;
    public static final int MANAGER = 41;
    public static final int LANDMINE = 42;
    public static final int NECROMANCER = 43;
    public static final int FACTORY = 44;
    public static final int BATTLESHIP = 45;
    public static final int ROCKETEER = 46;
    public static final int SKIMMER = 47;
    public static final int MEGA_SMASHER = 48;
    /** Co-op's boss (01 §8.5, "Bosses"): a class no upgrade reaches, which the arena gives. */
    public static final int GUARDIAN = 49;
    /** The Guardian below half its health. */
    public static final int GUARDIAN_ENRAGED = 50;
    /** Domination's dominator (01 §8.7): a class no upgrade reaches, which the arena gives. */
    public static final int DOMINATOR = 51;

    /** The level a class opens at when no player may choose it: past every level. */
    public static final int NEVER = Integer.MAX_VALUE;

    /** The most points any class allows in one stat: a Smasher's 10 (01 §4). */
    public static final int MOST_POINTS = 10;

    /** The caps a class has unless it sets its own: {@link Stat#MAX_POINTS_PER_STAT} in each. */
    public static final List<Integer> DEFAULT_CAPS =
            java.util.Collections.nCopies(Stat.COUNT, Stat.MAX_POINTS_PER_STAT);

    /** A bullet's radius at size 1; a barrel's size multiplies it. */
    public static final float BULLET_RADIUS = 8f;

    /**
     * One barrel.
     *
     * @param angle   from the tank's aim, in radians: 0 ahead, π behind
     * @param side    sideways from the barrel's line through the tank's centre, in world units:
     *                positive is a quarter turn anticlockwise of it, in world coordinates
     * @param delay   when in the reload it fires, as a fraction: 0 at once, 0.5 half way
     * @param speedMul, damageMul, penetrationMul  on what the stats give the bullet
     * @param lifetimeTicks how long its bullet flies, or its trap lies, or its drone lasts: a drone
     *                barrel's may be {@link #NO_EXPIRY}
     * @param spread  a random angle up to this either side of its line, in radians
     * @param sizeMul on {@link #BULLET_RADIUS}
     * @param recoil  how hard a shot pushes the tank back along the barrel's line, in world
     *                units a tick
     * @param kind    what it fires: {@link #BULLET}, {@link #TRAP}, {@link #DRONE},
     *                {@link #MINION}, {@link #ROCKET} or {@link #SKIMMER}; or {@link #CONVERT},
     *                which fires nothing. What it fires is sent as a unit of the same subtype.
     * @param arc     0 for a barrel that fires along its line; for a turret, which aims itself, how
     *                far either side of its line it looks, in radians, up to π for all round
     */
    public record Barrel(float angle, float side, float delay, float speedMul, float damageMul,
                         float penetrationMul, int lifetimeTicks, float spread, float sizeMul,
                         float recoil, int kind, float arc) {

        public static final int BULLET = 0;
        public static final int TRAP = 1;
        public static final int DRONE = 2;
        /** A drone that shoots: the Factory's (01 §4, "Minions"). */
        public static final int MINION = 3;
        /** A missile that flies straight and fires a trail behind it: the Rocketeer's (01 §4). */
        public static final int ROCKET = 4;
        /** A missile that spins and fires either side of it: the Skimmer's (01 §4). */
        public static final int SKIMMER = 5;
        /** Launches nothing: the squares its tank breaks become drones, made as this barrel would make one. */
        public static final int CONVERT = 6;

        /** A drone's lifetime that never runs out: it lasts until used up or its tank is gone. */
        public static final int NO_EXPIRY = Integer.MAX_VALUE;

        /** A bullet barrel of its own size and with no recoil, as every first-tier barrel is. */
        public Barrel(float angle, float side, float delay, float speedMul, float damageMul,
                      float penetrationMul, int lifetimeTicks, float spread) {
            this(angle, side, delay, speedMul, damageMul, penetrationMul, lifetimeTicks, spread,
                    1f, 0f, BULLET);
        }

        /** A barrel that fires along its line, as every barrel before the turrets does. */
        public Barrel(float angle, float side, float delay, float speedMul, float damageMul,
                      float penetrationMul, int lifetimeTicks, float spread, float sizeMul,
                      float recoil, int kind) {
            this(angle, side, delay, speedMul, damageMul, penetrationMul, lifetimeTicks, spread,
                    sizeMul, recoil, kind, 0f);
        }

        public Barrel {
            if (delay < 0f || delay >= 1f) {
                throw new IllegalArgumentException("a barrel's delay is a fraction of the reload: " + delay);
            }
            if (lifetimeTicks <= 0 || (kind == BULLET && lifetimeTicks > 255)) {
                // A bullet's create carries it in a byte (02 §4); a trap's or a drone's is not
                // sent, since they are updated rather than extrapolated.
                throw new IllegalArgumentException("a bullet's lifetime is 1 to 255 ticks: " + lifetimeTicks);
            }
            if (!(speedMul > 0f && damageMul > 0f && penetrationMul > 0f && spread >= 0f)) {
                throw new IllegalArgumentException("a barrel's multipliers are positive and its spread is not negative");
            }
            if (!(sizeMul > 0f && BULLET_RADIUS * sizeMul <= 255f && recoil >= 0f)) {
                // The radius travels in a byte (02 §4).
                throw new IllegalArgumentException("a barrel's size keeps its radius to 1..255 units, and its recoil is not negative");
            }
            if (kind < BULLET || kind > CONVERT) {
                throw new IllegalArgumentException("a barrel fires a bullet, a trap, a drone or a minion, or converts: " + kind);
            }
            if (!(arc >= 0f && arc <= (float) Math.PI) || (arc > 0f && kind != BULLET)) {
                throw new IllegalArgumentException("a turret fires bullets, and looks at most all round: " + arc);
            }
        }

        /** Whether it aims itself (01 §4, "Turrets"). */
        public boolean turret() {
            return arc > 0f;
        }

        /** A turret: a bullet barrel that aims itself at what is within {@code arc} of its line. */
        static Barrel turret(float angle, float arc) {
            return new Barrel(angle, 0f, 0f, 1f, 1f, 1f, 75, 0f, 1f, 0f, BULLET, arc);
        }

        static Barrel ahead() {
            return new Barrel(0f, 0f, 0f, 1f, 1f, 1f, 75, 0f);
        }

        /** This barrel, firing at another size. */
        Barrel sized(float mul) {
            return new Barrel(angle, side, delay, speedMul, damageMul, penetrationMul, lifetimeTicks,
                    spread, mul, recoil, kind, arc);
        }

        /** This barrel, pushing its tank back. */
        Barrel recoiling(float push) {
            return new Barrel(angle, side, delay, speedMul, damageMul, penetrationMul, lifetimeTicks,
                    spread, sizeMul, push, kind, arc);
        }
    }

    /**
     * What a minion fires, every two of its tank's reloads, along its tank's aim (01 §4,
     * "Minions"): small and weak, on its tank's stats.
     */
    public static final Barrel MINION_SHOT = new Barrel(0f, 0f, 0f, 1f, 0.4f, 1f, 60, 0f).sized(0.6f);

    /**
     * What a missile fires as it flies, on its tank's stats (01 §4, "The rest of the tree"): a
     * Rocketeer's one straight back every {@link #ROCKET_INTERVAL} ticks, a trail; a Skimmer's two,
     * either side of its facing, every {@link #SKIMMER_INTERVAL}, as it turns {@link #SKIMMER_SPIN}
     * a tick.
     */
    public static final Barrel ROCKET_SHOT = new Barrel(0f, 0f, 0f, 1f, 0.3f, 1f, 15, 0f).sized(0.5f);
    public static final Barrel SKIMMER_SHOT = new Barrel(0f, 0f, 0f, 1f, 0.3f, 1f, 25, 0f).sized(0.5f);
    public static final int ROCKET_INTERVAL = 3;
    public static final int SKIMMER_INTERVAL = 4;
    public static final float SKIMMER_SPIN = 0.2f;

    /**
     * One class.
     *
     * @param opensAt   the level at which it can be chosen; 1 for Basic, which is never chosen
     * @param parents   the classes it upgrades from: none for Basic, and some classes have two
     * @param maxDrones how many drones its drone barrels keep out at once
     * @param caps      the most points it allows in each stat, in {@link Stat} order
     * @param bodyDamageMul on the body damage its stat gives
     * @param hidesAfterTicks how long a tank of it must stand still, the trigger let go, to be
     *                  hidden from everyone else (D-23); 0 for a class that does not hide
     * @param bodySize  on a tank's radius of 30: a Mega Smasher's is bigger
     * @param zoom      how far ahead its view moves while its player holds zoom; 0 for none
     */
    public record TankClass(int id, String name, int opensAt, List<Integer> parents, float fovMul,
                            float reloadMul, int maxDrones, List<Barrel> barrels, List<Integer> caps,
                            float bodyDamageMul, int hidesAfterTicks, float bodySize, float zoom,
                            float healthMul) {

        /** A class with one parent, or none for -1, and no drones. */
        public TankClass(int id, String name, int opensAt, int parent, float fovMul, float reloadMul,
                         List<Barrel> barrels) {
            this(id, name, opensAt, parent < 0 ? List.of() : List.of(parent), fovMul, reloadMul, 0, barrels);
        }

        /** A class with the default caps and the body damage its stat gives, which does not hide. */
        public TankClass(int id, String name, int opensAt, List<Integer> parents, float fovMul,
                         float reloadMul, int maxDrones, List<Barrel> barrels) {
            this(id, name, opensAt, parents, fovMul, reloadMul, maxDrones, barrels, DEFAULT_CAPS, 1f, 0);
        }

        /** A class with a body of the usual size. */
        public TankClass(int id, String name, int opensAt, List<Integer> parents, float fovMul,
                         float reloadMul, int maxDrones, List<Barrel> barrels, List<Integer> caps,
                         float bodyDamageMul, int hidesAfterTicks) {
            this(id, name, opensAt, parents, fovMul, reloadMul, maxDrones, barrels, caps, bodyDamageMul,
                    hidesAfterTicks, 1f);
        }

        /** A class whose view does not zoom. */
        public TankClass(int id, String name, int opensAt, List<Integer> parents, float fovMul,
                         float reloadMul, int maxDrones, List<Barrel> barrels, List<Integer> caps,
                         float bodyDamageMul, int hidesAfterTicks, float bodySize) {
            this(id, name, opensAt, parents, fovMul, reloadMul, maxDrones, barrels, caps, bodyDamageMul,
                    hidesAfterTicks, bodySize, 0f);
        }

        /** A class with the health its level and points give: every class but a boss's. */
        public TankClass(int id, String name, int opensAt, List<Integer> parents, float fovMul,
                         float reloadMul, int maxDrones, List<Barrel> barrels, List<Integer> caps,
                         float bodyDamageMul, int hidesAfterTicks, float bodySize, float zoom) {
            this(id, name, opensAt, parents, fovMul, reloadMul, maxDrones, barrels, caps, bodyDamageMul,
                    hidesAfterTicks, bodySize, zoom, 1f);
        }

        public TankClass {
            parents = List.copyOf(parents);
            barrels = List.copyOf(barrels);
            caps = List.copyOf(caps);
            if (maxDrones < 0 || hidesAfterTicks < 0) {
                throw new IllegalArgumentException(name + ": a negative number of drones or ticks");
            }
            if (barrels.size() > 32) {
                // A volley's progress is one bit a barrel in an int. None is a Smasher's.
                throw new IllegalArgumentException(name + ": at most 32 barrels");
            }
            if (!(fovMul > 0f && reloadMul > 0f && bodyDamageMul > 0f && bodySize > 0f && zoom >= 0f && healthMul > 0f)) {
                throw new IllegalArgumentException(name + ": its multipliers are positive");
            }
            if (caps.size() != Stat.COUNT || caps.stream().anyMatch(c -> c < 0 || c > MOST_POINTS)) {
                throw new IllegalArgumentException(name + ": a cap for each stat, 0 to " + MOST_POINTS);
            }
        }

        /** The most points this class allows in {@code stat}. */
        public int cap(int stat) {
            return caps.get(stat);
        }

        /** Every barrel, one bit each in the order of the list: what a volley with the trigger fires. */
        public int allBarrels() {
            return barrels.isEmpty() ? 0 : -1 >>> (32 - barrels.size());
        }

        /** The barrels that fire without the trigger: drone and minion barrels, and turrets (01 §4, "The third tier"). */
        public int selfFiring() {
            int mask = 0;
            for (int i = 0; i < barrels.size(); i++) {
                int kind = barrels.get(i).kind();
                if (kind == Barrel.DRONE || kind == Barrel.MINION || barrels.get(i).turret()) {
                    mask |= 1 << i;
                }
            }
            return mask;
        }

        /**
         * Its convert barrel, or null: whether the squares it breaks become its drones. Asked each
         * time any tank breaks a square, so by index: an iterator a call allocated every tick (M-14).
         */
        public Barrel converter() {
            for (int i = 0; i < barrels.size(); i++) {
                if (barrels.get(i).kind() == Barrel.CONVERT) {
                    return barrels.get(i);
                }
            }
            return null;
        }
    }

    private final TankClass[] classes;
    private final String json;
    private final long version;

    public ClassTable(List<TankClass> classes) {
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("a room needs Basic, class 0");
        }
        this.classes = new TankClass[classes.size()];
        for (TankClass c : classes) {
            if (c.id() < 0 || c.id() >= this.classes.length || this.classes[c.id()] != null) {
                throw new IllegalArgumentException("class ids are 0 to n-1, each once: " + c.id());
            }
            this.classes[c.id()] = c;
        }
        if (!this.classes[BASIC].parents().isEmpty()) {
            throw new IllegalArgumentException("Basic has no parent");
        }
        for (TankClass c : this.classes) {
            if (c.id() != BASIC && c.parents().isEmpty()) {
                throw new IllegalArgumentException(c.name() + ": every class but Basic has a parent");
            }
            // Before it, so that following parents always ends, at Basic.
            for (int parent : c.parents()) {
                if (parent < 0 || parent >= c.id()) {
                    throw new IllegalArgumentException(c.name() + ": its parent must be a class before it, not " + parent);
                }
            }
        }
        this.json = toJson(this.classes);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.version = crc.getValue();
    }

    public int size() {
        return classes.length;
    }

    /**
     * The table as JSON, as {@code platform} serves it to a client (D-24): what a client needs to
     * draw a tank and what it fires, and to offer its choices. Written by hand in a fixed order, so
     * that the same table is always the same bytes, and their hash a version nobody can forget to
     * bump. A drone's lifetime of {@link Barrel#NO_EXPIRY} is written as the number it is.
     */
    public String json() {
        return json;
    }

    /** A CRC-32 of {@link #json()}: the Welcome's {@code contentVersion}, and {@code platform}'s ETag. */
    public long version() {
        return version;
    }

    private static String toJson(TankClass[] classes) {
        StringBuilder s = new StringBuilder("[");
        for (TankClass c : classes) {
            if (s.length() > 1) {
                s.append(',');
            }
            s.append("{\"id\":").append(c.id())
                    .append(",\"name\":\"").append(c.name()).append('"')
                    .append(",\"opensAt\":").append(c.opensAt())
                    .append(",\"parents\":").append(c.parents().toString().replace(" ", ""))
                    .append(",\"fov\":").append(c.fovMul())
                    .append(",\"reload\":").append(c.reloadMul())
                    .append(",\"maxDrones\":").append(c.maxDrones())
                    .append(",\"caps\":").append(c.caps().toString().replace(" ", ""))
                    .append(",\"bodyDamage\":").append(c.bodyDamageMul())
                    .append(",\"hidesAfter\":").append(c.hidesAfterTicks())
                    .append(",\"bodySize\":").append(c.bodySize())
                    .append(",\"zoom\":").append(c.zoom())
                    .append(",\"barrels\":[");
            for (int i = 0; i < c.barrels().size(); i++) {
                Barrel b = c.barrels().get(i);
                s.append(i > 0 ? "," : "")
                        .append("{\"angle\":").append(b.angle())
                        .append(",\"side\":").append(b.side())
                        .append(",\"delay\":").append(b.delay())
                        .append(",\"speed\":").append(b.speedMul())
                        .append(",\"damage\":").append(b.damageMul())
                        .append(",\"penetration\":").append(b.penetrationMul())
                        .append(",\"lifetime\":").append(b.lifetimeTicks())
                        .append(",\"spread\":").append(b.spread())
                        .append(",\"size\":").append(b.sizeMul())
                        .append(",\"recoil\":").append(b.recoil())
                        .append(",\"kind\":").append(b.kind())
                        .append(",\"arc\":").append(b.arc())
                        .append('}');
            }
            s.append("]}");
        }
        return s.append(']').toString();
    }

    /** The largest body any class has, on a tank's 30: how far a collision query must reach for one. */
    public float largestBody() {
        float most = 0f;
        for (TankClass c : classes) {
            most = Math.max(most, c.bodySize());
        }
        return most;
    }

    /** @return the class, or Basic for an id that is not one: a slot never holds a bad id. */
    public TankClass get(int id) {
        return id >= 0 && id < classes.length ? classes[id] : classes[BASIC];
    }

    /** Whether a tank of {@code current} class at {@code level} may become {@code wanted}. */
    public boolean mayChoose(int current, int level, int wanted) {
        if (wanted <= BASIC || wanted >= classes.length) {
            return false;
        }
        TankClass c = classes[wanted];
        return c.parents().contains(current) && level >= c.opensAt();
    }

    /**
     * The shipped classes: Basic, the first tier at level 15, the second at 30 and the third at 45
     * (01 §4). No class keeps more alive at full reload than the second tier's most (D-22).
     */
    public static ClassTable defaults() {
        float quarter = (float) (Math.PI / 2);
        Barrel ahead = Barrel.ahead();
        Barrel behind = new Barrel(2 * quarter, 0f, 0f, 1f, 1f, 1f, 75, 0f);
        List<Barrel> twin = List.of(
                new Barrel(0f, 10f, 0f, 1f, 0.65f, 1f, 75, 0f),
                new Barrel(0f, -10f, 0.5f, 1f, 0.65f, 1f, 75, 0f));
        List<Barrel> twinBehind = List.of(
                new Barrel(2 * quarter, 10f, 0f, 1f, 0.65f, 1f, 75, 0f),
                new Barrel(2 * quarter, -10f, 0.5f, 1f, 0.65f, 1f, 75, 0f));
        List<Barrel> twinFlank = new java.util.ArrayList<>(twin);
        twinFlank.addAll(twinBehind);
        float backward = (float) (Math.PI * 150 / 180);
        Barrel trap = new Barrel(0f, 0f, 0f, 0.8f, 1f, 2f, 600, 0f, 1.5f, 0f, Barrel.TRAP);
        Barrel destroyer = new Barrel(0f, 0f, 0f, 0.7f, 3f, 2f, 75, 0f).sized(2f).recoiling(1.5f);
        List<TankClass> rows = new java.util.ArrayList<>(List.of(
                new TankClass(BASIC, "Basic", 1, -1, 1f, 1f, List.of(ahead)),
                new TankClass(TWIN, "Twin", 15, BASIC, 1f, 1f, twin),
                new TankClass(SNIPER, "Sniper", 15, BASIC, 1.2f, 1.5f, List.of(
                        new Barrel(0f, 0f, 0f, 1.5f, 1f, 1f, 90, 0f))),
                new TankClass(MACHINE_GUN, "Machine Gun", 15, BASIC, 1f, 0.5f, List.of(
                        new Barrel(0f, 0f, 0f, 1f, 0.7f, 1f, 75, 0.17f))),
                new TankClass(FLANK_GUARD, "Flank Guard", 15, BASIC, 1f, 1f, List.of(ahead, behind)),

                new TankClass(TRIPLE_SHOT, "Triple Shot", 30, TWIN, 1f, 1f, List.of(
                        new Barrel(0f, 0f, 0f, 1f, 0.7f, 1f, 75, 0f),
                        new Barrel(0.5f, 0f, 0f, 1f, 0.7f, 1f, 75, 0f),
                        new Barrel(-0.5f, 0f, 0f, 1f, 0.7f, 1f, 75, 0f))),
                new TankClass(QUAD_TANK, "Quad Tank", 30, List.of(TWIN, FLANK_GUARD), 1f, 1f, 0, List.of(
                        ahead,
                        new Barrel(quarter, 0f, 0f, 1f, 1f, 1f, 75, 0f),
                        behind,
                        new Barrel(-quarter, 0f, 0f, 1f, 1f, 1f, 75, 0f))),
                new TankClass(TWIN_FLANK, "Twin Flank", 30, List.of(TWIN, FLANK_GUARD), 1f, 1f, 0, twinFlank),
                new TankClass(ASSASSIN, "Assassin", 30, SNIPER, 1.4f, 2f, List.of(
                        new Barrel(0f, 0f, 0f, 1.8f, 1f, 1f, 110, 0f))),
                new TankClass(HUNTER, "Hunter", 30, SNIPER, 1.2f, 1.5f, List.of(
                        new Barrel(0f, 0f, 0f, 1.5f, 0.75f, 1f, 90, 0f),
                        new Barrel(0f, 0f, 0.2f, 1.5f, 0.75f, 1f, 90, 0f).sized(0.75f))),
                new TankClass(DESTROYER, "Destroyer", 30, MACHINE_GUN, 1f, 4f, List.of(destroyer)),
                new TankClass(GUNNER, "Gunner", 30, MACHINE_GUN, 1f, 1f, List.of(
                        new Barrel(0f, 5f, 0f, 1f, 0.5f, 1f, 75, 0f).sized(0.6f),
                        new Barrel(0f, -5f, 0.25f, 1f, 0.5f, 1f, 75, 0f).sized(0.6f),
                        new Barrel(0f, 10f, 0.5f, 1f, 0.5f, 1f, 75, 0f).sized(0.6f),
                        new Barrel(0f, -10f, 0.75f, 1f, 0.5f, 1f, 75, 0f).sized(0.6f))),
                new TankClass(TRI_ANGLE, "Tri-Angle", 30, FLANK_GUARD, 1f, 1f, List.of(
                        ahead,
                        new Barrel(backward, 0f, 0f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f),
                        new Barrel(-backward, 0f, 0f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f))),
                // Laid at 8 units a tick, a trap slides 80 and waits 24 s; a sturdy one.
                new TankClass(TRAPPER, "Trapper", 30, SNIPER, 1.2f, 1.5f, List.of(trap)),
                // Two drone barrels, eight drones: launched slow, steered after (Room).
                new TankClass(OVERSEER, "Overseer", 30, List.of(SNIPER), 1.1f, 1.5f, 8, List.of(
                        drone(quarter, 0f), drone(-quarter, 0.5f)))));
        rows.addAll(thirdTier(rows));
        return new ClassTable(rows);
    }

    /** An Overseer's drone barrel: launched slow, steered after (Room), lasting until used up. */
    private static Barrel drone(float angle, float delay) {
        return new Barrel(angle, 0f, delay, 0.5f, 0.7f, 2f, Barrel.NO_EXPIRY, 0f, 1.25f, 0f, Barrel.DRONE);
    }

    /** A Battleship's drone barrel: small drones that last 150 ticks (01 §4, "The rest of the tree"). */
    private static Barrel shortDrone(float angle, float side, float delay) {
        return new Barrel(angle, side, delay, 0.5f, 0.4f, 1f, 150, 0f, 0.7f, 0f, Barrel.DRONE);
    }

    /**
     * The third tier, at level 45, and the classes at 30 that came with it (01 §4, "The third
     * tier"), in the order they were built: ids go on the wire, so a row is appended, never moved.
     * A row's reload and view are its first parent's unless it says otherwise. More barrels
     * reload slower: none keeps more than 75 bullets or 120 traps alive at full reload (D-22),
     * which {@code ClassTest} holds.
     *
     * @param earlier the classes before, by id: a row that has its parent's barrels takes them there
     */
    private static List<TankClass> thirdTier(List<TankClass> earlier) {
        float quarter = (float) (Math.PI / 2);
        float third = (float) (Math.PI * 2 / 3);
        float backward = (float) (Math.PI * 150 / 180);
        float all = (float) Math.PI;
        // Its body is all a Smasher has: 10 in the body's four stats, none in the bullets'.
        List<Integer> smasher = List.of(10, 10, 10, 0, 0, 0, 0, 10);
        // A boss is slow: no points in speed.
        List<Integer> boss = new java.util.ArrayList<>(DEFAULT_CAPS);
        boss.set(Stat.MOVEMENT_SPEED, 0);
        int hides = 50;
        Barrel trap = earlier.get(TRAPPER).barrels().get(0);
        Barrel destroyer = earlier.get(DESTROYER).barrels().get(0);
        List<Barrel> autoFive = new java.util.ArrayList<>();
        for (int k = 0; k < 5; k++) {
            autoFive.add(Barrel.turret((float) (Math.PI * 2 / 5) * k, (float) (Math.PI / 5)));
        }
        List<Barrel> autoGunner = new java.util.ArrayList<>(earlier.get(GUNNER).barrels());
        autoGunner.add(Barrel.turret(0f, all));
        List<Barrel> spread = new java.util.ArrayList<>(List.of(new Barrel(0f, 0f, 0f, 1f, 0.5f, 1f, 75, 0f)));
        for (int k = 1; k <= 5; k++) {
            spread.add(new Barrel(0.25f * k, 0f, k / 6f, 1f, 0.5f, 1f, 75, 0f));
            spread.add(new Barrel(-0.25f * k, 0f, k / 6f, 1f, 0.5f, 1f, 75, 0f));
        }
        List<Barrel> octo = new java.util.ArrayList<>();
        for (int k = 0; k < 8; k++) {
            octo.add(new Barrel(quarter / 2 * k, 0f, (k % 2) * 0.5f, 1f, 1f, 1f, 75, 0f));
        }
        List<Barrel> tripleTwin = new java.util.ArrayList<>();
        for (float a : new float[] {0f, third, -third}) {
            tripleTwin.add(new Barrel(a, 10f, 0f, 1f, 0.6f, 1f, 75, 0f));
            tripleTwin.add(new Barrel(a, -10f, 0.5f, 1f, 0.6f, 1f, 75, 0f));
        }
        List<Barrel> streamliner = new java.util.ArrayList<>();
        for (int k = 0; k < 5; k++) {
            streamliner.add(new Barrel(0f, 0f, k / 5f, 1.5f, 0.4f, 1f, 90, 0f).sized(0.6f));
        }
        float wide = (float) (Math.PI * 135 / 180);
        Barrel ahead = Barrel.ahead();
        return List.of(
                new TankClass(TRIPLET, "Triplet", 45, TRIPLE_SHOT, 1f, 1f, List.of(
                        new Barrel(0f, 0f, 0f, 1f, 0.6f, 1f, 75, 0f),
                        new Barrel(0f, 10f, 0.5f, 1f, 0.6f, 1f, 75, 0f),
                        new Barrel(0f, -10f, 0.5f, 1f, 0.6f, 1f, 75, 0f))),
                new TankClass(PENTA_SHOT, "Penta Shot", 45, TRIPLE_SHOT, 1f, 1.3f, List.of(
                        new Barrel(0f, 0f, 0f, 1f, 0.55f, 1f, 75, 0f),
                        new Barrel(0.35f, 0f, 1 / 3f, 1f, 0.55f, 1f, 75, 0f),
                        new Barrel(-0.35f, 0f, 1 / 3f, 1f, 0.55f, 1f, 75, 0f),
                        new Barrel(0.7f, 0f, 2 / 3f, 1f, 0.55f, 1f, 75, 0f),
                        new Barrel(-0.7f, 0f, 2 / 3f, 1f, 0.55f, 1f, 75, 0f))),
                new TankClass(SPREAD_SHOT, "Spread Shot", 45, TRIPLE_SHOT, 1f, 3.2f, spread),
                new TankClass(OCTO_TANK, "Octo Tank", 45, QUAD_TANK, 1f, OCTO_RELOAD, octo),
                new TankClass(TRIPLE_TWIN, "Triple Twin", 45, TWIN_FLANK, 1f, 1.6f, tripleTwin),
                new TankClass(RANGER, "Ranger", 45, ASSASSIN, 1.6f, 2f, List.of(
                        new Barrel(0f, 0f, 0f, 2f, 1f, 1f, 130, 0f))),
                // Zooms: its view 700 units ahead while its player holds zoom (01 §4).
                new TankClass(PREDATOR, "Predator", 45, List.of(HUNTER), 1.4f, 1.5f, 0, List.of(
                        new Barrel(0f, 0f, 0f, 1.5f, 0.75f, 1f, 90, 0f),
                        new Barrel(0f, 0f, 0.15f, 1.5f, 0.75f, 1f, 90, 0f).sized(0.75f),
                        new Barrel(0f, 0f, 0.3f, 1.5f, 0.75f, 1f, 90, 0f).sized(0.55f)),
                        DEFAULT_CAPS, 1f, 0, 1f, 700f),
                new TankClass(STREAMLINER, "Streamliner", 45, HUNTER, 1.2f, 1.6f, streamliner),
                new TankClass(SPRAYER, "Sprayer", 45, MACHINE_GUN, 1f, 0.5f, List.of(
                        new Barrel(0f, 0f, 0f, 1f, 0.7f, 1f, 75, 0.17f),
                        new Barrel(0f, 0f, 0.5f, 1f, 0.3f, 1f, 75, 0.1f).sized(0.6f))),
                new TankClass(ANNIHILATOR, "Annihilator", 45, DESTROYER, 1f, 4f, List.of(
                        new Barrel(0f, 0f, 0f, 0.7f, 4f, 2.5f, 75, 0f).sized(2.6f).recoiling(2.5f))),
                new TankClass(BOOSTER, "Booster", 45, TRI_ANGLE, 1f, 1.3f, List.of(
                        ahead,
                        new Barrel(backward, 0f, 0f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f),
                        new Barrel(-backward, 0f, 0f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f),
                        new Barrel(wide, 0f, 0.5f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f),
                        new Barrel(-wide, 0f, 0.5f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f))),
                new TankClass(FIGHTER, "Fighter", 45, TRI_ANGLE, 1f, 1.3f, List.of(
                        ahead,
                        new Barrel(quarter, 0f, 0f, 1f, 0.8f, 1f, 75, 0f),
                        new Barrel(-quarter, 0f, 0f, 1f, 0.8f, 1f, 75, 0f),
                        new Barrel(backward, 0f, 0f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f),
                        new Barrel(-backward, 0f, 0f, 1f, 0.5f, 1f, 75, 0f).recoiling(0.5f))),
                new TankClass(OVERLORD, "Overlord", 45, List.of(OVERSEER), 1.1f, 1.5f, 8, List.of(
                        drone(0f, 0f), drone(quarter, 0.5f), drone(2 * quarter, 0f), drone(-quarter, 0.5f))),
                new TankClass(TRI_TRAPPER, "Tri-Trapper", 45, TRAPPER, 1.2f, 2.9f, List.of(
                        new Barrel(0f, 0f, 0f, 0.8f, 1f, 2f, 400, 0f, 1.5f, 0f, Barrel.TRAP),
                        new Barrel(third, 0f, 0f, 0.8f, 1f, 2f, 400, 0f, 1.5f, 0f, Barrel.TRAP),
                        new Barrel(-third, 0f, 0f, 0.8f, 1f, 2f, 400, 0f, 1.5f, 0f, Barrel.TRAP))),
                new TankClass(MEGA_TRAPPER, "Mega Trapper", 45, TRAPPER, 1.2f, 2.2f, List.of(
                        new Barrel(0f, 0f, 0f, 0.8f, 1.5f, 3f, 600, 0f, 2.5f, 0f, Barrel.TRAP))),
                new TankClass(GUNNER_TRAPPER, "Gunner Trapper", 45, List.of(GUNNER, TRAPPER), 1f, 1.5f, 0, List.of(
                        new Barrel(0f, 5f, 0f, 1f, 0.5f, 1f, 75, 0f).sized(0.6f),
                        new Barrel(0f, -5f, 0.5f, 1f, 0.5f, 1f, 75, 0f).sized(0.6f),
                        new Barrel(2 * quarter, 0f, 0f, 0.8f, 1f, 2f, 600, 0f, 1.5f, 0f, Barrel.TRAP))),
                new TankClass(OVERTRAPPER, "Overtrapper", 45, List.of(TRAPPER, OVERSEER), 1.2f, 1.5f, 2, List.of(
                        trap, drone(wide, 0f), drone(-wide, 0.5f))),
                new TankClass(HYBRID, "Hybrid", 45, List.of(DESTROYER), 1f, 4f, 2, List.of(
                        destroyer, drone(2 * quarter, 0f))),
                // Turrets: each watches its own part of the circle, and fires without the trigger.
                new TankClass(AUTO_3, "Auto 3", 30, FLANK_GUARD, 1f, 1f, List.of(
                        Barrel.turret(0f, third / 2), Barrel.turret(third, third / 2),
                        Barrel.turret(-third, third / 2))),
                new TankClass(AUTO_5, "Auto 5", 45, AUTO_3, 1f, 1.3f, autoFive),
                new TankClass(AUTO_GUNNER, "Auto Gunner", 45, GUNNER, 1f, 1.4f, autoGunner),
                new TankClass(AUTO_TRAPPER, "Auto Trapper", 45, TRAPPER, 1.2f, 1.5f, List.of(
                        trap, Barrel.turret(0f, all))),
                // No barrels: the body is the weapon.
                new TankClass(SMASHER, "Smasher", 30, List.of(BASIC), 1f, 1f, 0, List.of(), smasher, 1f, 0),
                new TankClass(SPIKE, "Spike", 45, List.of(SMASHER), 1f, 1f, 0, List.of(), smasher, 1.5f, 0),
                new TankClass(AUTO_SMASHER, "Auto Smasher", 45, List.of(SMASHER), 1f, 1f, 0,
                        List.of(Barrel.turret(0f, all)), List.of(10, 10, 10, 7, 7, 7, 7, 10), 1f, 0),
                // Hidden after two seconds standing still, the trigger let go (D-23).
                new TankClass(STALKER, "Stalker", 45, List.of(ASSASSIN), 1.4f, 2f, 0,
                        earlier.get(ASSASSIN).barrels(), DEFAULT_CAPS, 1f, hides),
                new TankClass(MANAGER, "Manager", 45, List.of(OVERSEER), 1.1f, 1.5f, 8,
                        List.of(drone(0f, 0f)), DEFAULT_CAPS, 1f, hides),
                new TankClass(LANDMINE, "Landmine", 45, List.of(SMASHER), 1f, 1f, 0, List.of(), smasher, 1f, hides),
                // Twelve drones, all of them squares it broke; it launches none (01 §4).
                new TankClass(NECROMANCER, "Necromancer", 45, List.of(OVERSEER), 1.1f, 1.5f, 12, List.of(
                        new Barrel(0f, 0f, 0f, 0.5f, 0.7f, 2f, Barrel.NO_EXPIRY, 0f, 1.5f, 0f, Barrel.CONVERT))),
                // Six minions: drones that shoot along its aim.
                new TankClass(FACTORY, "Factory", 45, List.of(OVERSEER), 1.1f, 1.5f, 6, List.of(
                        new Barrel(0f, 0f, 0f, 0.5f, 0.7f, 2f, Barrel.NO_EXPIRY, 0f, 1.5f, 0f, Barrel.MINION))),
                // Sixteen small drones at most, from two barrels either side, each lasting 150 ticks.
                new TankClass(BATTLESHIP, "Battleship", 45, List.of(OVERSEER, TWIN_FLANK), 1.1f, 1.5f, 16, List.of(
                        shortDrone(quarter, 10f, 0f), shortDrone(quarter, -10f, 0.5f),
                        shortDrone(-quarter, 10f, 0.25f), shortDrone(-quarter, -10f, 0.75f))),
                // Missiles that fire as they fly; D-22 counts their shots.
                new TankClass(ROCKETEER, "Rocketeer", 45, DESTROYER, 1f, 4f, List.of(
                        new Barrel(0f, 0f, 0f, 0.8f, 1f, 3f, 75, 0f, 1.4f, 0f, Barrel.ROCKET))),
                new TankClass(SKIMMER, "Skimmer", 45, DESTROYER, 1f, 4.5f, List.of(
                        new Barrel(0f, 0f, 0f, 0.6f, 1f, 3f, 75, 0f, 1.6f, 0f, Barrel.SKIMMER))),
                // A bigger body, which hits harder: the Smasher's caps.
                new TankClass(MEGA_SMASHER, "Mega Smasher", 45, List.of(SMASHER), 1f, 1f, 0, List.of(), smasher,
                        1.25f, 0, 1.3f),
                // Co-op's boss and its enraged form (01 §8.5, Q-28): opened by no level, given by the
                // arena. The enraged fires twice as fast with bullets living half as long, so that it
                // keeps no more alive than the Guardian does (D-22).
                new TankClass(GUARDIAN, "Guardian", NEVER, List.of(BASIC), 1f, OCTO_RELOAD, 0, octo, boss, 1f, 0,
                        2.5f, 0f, 12f),
                new TankClass(GUARDIAN_ENRAGED, "Guardian, enraged", NEVER, List.of(BASIC), 1f, OCTO_RELOAD / 2, 0,
                        octo.stream().map(b -> new Barrel(b.angle(), b.side(), b.delay(), b.speedMul(), b.damageMul(),
                                b.penetrationMul(), b.lifetimeTicks() / 2, b.spread(), b.sizeMul(), b.recoil(),
                                b.kind(), b.arc())).toList(),
                        boss, 1f, 0, 2.5f, 0f, 12f),
                // Domination's dominator (01 §8.7, Q-29): one turret that looks all round.
                new TankClass(DOMINATOR, "Dominator", NEVER, List.of(BASIC), 1f, 1f, 0,
                        List.of(Barrel.turret(0f, (float) Math.PI)), boss, 1f, 0, 2f, 0f, 8f));
    }

    /** The Octo Tank's reload, which the Guardian shares. */
    private static final float OCTO_RELOAD = 2.3f;
}
