package com.backend.tools;

import java.util.Locale;

import com.backend.arena.ClientView;
import com.backend.arena.SnapshotEncoder;
import com.backend.common.PhaseTimer;
import com.backend.protocol.SnapshotWriter;
import com.backend.sim.Entity;
import com.backend.sim.MazeGenerator;
import com.backend.sim.Room;
import com.backend.sim.Walls;
import com.backend.sim.World;

/**
 * Measures the per-phase tick cost of a full room, so NFR-1a and NFR-1b can be checked on
 * real hardware instead of derived on paper (docs requirements §4, Q-3).
 *
 * Run it on the production machine:
 *   java -XX:+UseZGC -XX:+ZGenerational -jar tools-0.1.0-SNAPSHOT-all.jar
 *        [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed]
 */
public final class TickBenchmark {

    private static final float CELL = 200f;
    private static final double BUDGET_MS = 2.0;     // NFR-1a: the simulation tick, p99
    private static final int TICK_HZ = 25;
    private static final int SNAPSHOT_HZ = 15;       // D-10: tanks drive the send rate
    private static final int VIEW_SIZE = 1600;       // world units across, per client
    private static final int ENTITY_BUDGET = 30;     // the "mobile" traffic profile

    public static void main(String[] args) {
        int tanks = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        int shapes = args.length > 1 ? Integer.parseInt(args[1]) : 1500;
        int ticks = args.length > 2 ? Integer.parseInt(args[2]) : 20_000;
        float map = args.length > 3 ? Float.parseFloat(args[3]) : 22_000f;
        // A room of tanks already grown, each with its points spent and its classes taken as a
        // bot takes them: what a mature room costs, which a run of minutes never reaches.
        int startLevel = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        // A maze's walls (01 §8.9), 0 for none: what its collisions cost.
        long mazeSeed = args.length > 5 ? Long.parseLong(args[5]) : 0L;
        int warmup = Math.min(5_000, ticks / 2);

        World world = new World(map, map, 16_384, CELL, 42L);
        Room room = new Room(world);
        if (mazeSeed != 0) {
            room.setWalls(new Walls(MazeGenerator.walls(mazeSeed), MazeGenerator.CELL));
        }
        int startXp = room.content().levels().xpRequired(startLevel);
        for (int i = 0; i < tanks; i++) {
            // Team 0, as the public arena's: on two teams half the tanks were teammates, whose bullets pass through
            // each other unspent and who spawn in one third of the map (the arena review, 2026-10-04).
            grown(room, room.spawnTank((byte) 0, ++playerTags), startXp);
        }
        for (int i = 0; i < shapes; i++) {
            room.spawnShape();
        }

        System.out.printf(Locale.ROOT,
                "java %s / %s%ncores %d   map %.0f   cell %.0f   grid cells %d   maze seed %d%n"
                + "tanks %d (from level %d)   shapes %d   warmup %d ticks   measured %d ticks (%.0f s of play)%n"
                + "view %d units, budget %d entities -> view covers %.2f%% of the map,"
                + " so ~%.1f tanks visible if spread evenly%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), map, CELL, world.hash.cellCount(), mazeSeed,
                tanks, startLevel, shapes, warmup, ticks, ticks / (double) TICK_HZ,
                VIEW_SIZE, ENTITY_BUDGET,
                (double) VIEW_SIZE * VIEW_SIZE / (map * map) * 100,
                tanks * ((double) VIEW_SIZE * VIEW_SIZE / (map * map)));

        PhaseTimer timer = Room.newTimer();
        for (int i = 0; i < warmup; i++) {
            long t0 = System.nanoTime();
            room.step(timer);
            timer.recordTotal(System.nanoTime() - t0);
            topUp(room, tanks, shapes, startXp);
        }
        long bulletsAfterWarmup = world.bullets.size;
        timer.reset();

        // One view per player, exactly as a real room holds.
        ClientView[] views = new ClientView[world.capacity()];
        SnapshotEncoder encoder = new SnapshotEncoder();
        SnapshotWriter writer = new SnapshotWriter(4096);
        PhaseTimer encodeTimer = new PhaseTimer("encode/client", "encode/round");

        // Allocation in the two measured sections only: the harness's own, a view for each tank
        // that respawns as a new entity, is what a real room does once a join, not once a tick.
        // Counted as well as summed: a structure growing to a size it has not needed before
        // allocates once, and an average cannot tell that from a little every tick.
        long simAllocated = 0, encodeAllocated = 0, rounds = 0, ticksAllocating = 0, roundsAllocating = 0;
        long wall = System.nanoTime();
        long snapshots = 0, totalBytes = 0, totalEntities = 0;
        int accumulator = 0;

        for (int i = 0; i < ticks; i++) {
            long a0 = allocatedBytes();
            long t0 = System.nanoTime();
            room.step(timer);
            timer.recordTotal(System.nanoTime() - t0);
            long simDelta = allocatedBytes() - a0;
            simAllocated += simDelta;
            ticksAllocating += simDelta > 0 ? 1 : 0;

            // 15 snapshot rounds per 25 ticks, without floating point drift.
            accumulator += SNAPSHOT_HZ;
            if (accumulator >= TICK_HZ) {
                accumulator -= TICK_HZ;
                for (int k = 0; k < world.tanks.size; k++) {
                    int id = world.tanks.items[k];
                    if (views[id] == null || views[id].selfId != id) {
                        views[id] = new ClientView(id, world.capacity(), VIEW_SIZE, VIEW_SIZE, ENTITY_BUDGET);
                    }
                }
                long a1 = allocatedBytes();
                encodeTimer.start(1);
                for (int k = 0; k < world.tanks.size; k++) {
                    int id = world.tanks.items[k];
                    ClientView v = views[id];
                    // Each view its class's, as the arena sizes it (RoomThread.sendSnapshots): a Ranger sees 1.6 times
                    // as far, so 2.56 times the area.
                    float seen = VIEW_SIZE * room.content().classes().get(world.tankStats[id].classId).fovMul();
                    v.viewWidth = seen;
                    v.viewHeight = seen;
                    encodeTimer.start(0);
                    totalEntities += encoder.encode(world, v, room.tick(), writer);
                    encodeTimer.stop(0);
                    totalBytes += writer.length();
                    snapshots++;
                    v.acknowledge(room.tick());   // TCP: the ack arrives before the next round
                }
                encodeTimer.stop(1);
                encodeTimer.recordTotal(0);
                long encodeDelta = allocatedBytes() - a1;
                encodeAllocated += encodeDelta;
                roundsAllocating += encodeDelta > 0 ? 1 : 0;
                rounds++;
            }
            topUp(room, tanks, shapes, startXp);   // outside the timed section
        }
        wall = System.nanoTime() - wall;

        System.out.printf(Locale.ROOT,
                "%nsteady state: %d live entities (%d tanks, %d bullets, %d shapes)%n",
                world.liveCount(), world.tanks.size, world.bullets.size, world.shapes.size);
        System.out.printf(Locale.ROOT, "bullets after warmup: %d%n", bulletsAfterWarmup);
        int[] byClass = new int[room.content().classes().size()];
        int traps = 0;
        int drones = 0;
        int minions = 0;
        for (int k = 0; k < world.tanks.size; k++) {
            byClass[world.tankStats[world.tanks.items[k]].classId]++;
        }
        for (int k = 0; k < world.bullets.size; k++) {
            byte kind = world.entities[world.bullets.items[k]].subtype;
            traps += kind == com.backend.sim.ClassTable.Barrel.TRAP ? 1 : 0;
            drones += kind == com.backend.sim.ClassTable.Barrel.DRONE ? 1 : 0;
            minions += kind == com.backend.sim.ClassTable.Barrel.MINION ? 1 : 0;
        }
        int[] byShape = new int[room.content().shapes().size()];
        for (int k = 0; k < world.shapes.size; k++) {
            byShape[world.entities[world.shapes.items[k]].subtype]++;
        }
        System.out.printf(Locale.ROOT, "classes at the end: %s; of the bullets, %d traps, %d drones and"
                + " %d minions; shapes by kind %s%n", java.util.Arrays.toString(byClass), traps, drones,
                minions, java.util.Arrays.toString(byShape));

        timer.report(System.out, "simulation tick cost", BUDGET_MS);
        encodeTimer.reportPhasesOnly(System.out,
                "snapshot encoding (per client, and per round of all " + tanks + " clients)");

        double bytesPerSnapshot = totalBytes / (double) snapshots;
        System.out.printf(Locale.ROOT,
                "%nsnapshots %d   mean payload %.1f bytes   mean entities %.1f%n",
                snapshots, bytesPerSnapshot, totalEntities / (double) snapshots);
        System.out.printf(Locale.ROOT,
                "per player: %.2f KB/s down at %d Hz (payload only, before ~71 B/packet overhead)%n",
                bytesPerSnapshot * SNAPSHOT_HZ / 1024.0, SNAPSHOT_HZ);
        System.out.printf(Locale.ROOT,
                "           %.2f KB/s with overhead  =  %.1f MB/hour%n",
                (bytesPerSnapshot + 71) * SNAPSHOT_HZ / 1024.0,
                (bytesPerSnapshot + 71) * SNAPSHOT_HZ * 3600 / 1_048_576.0);

        double perTickMs = wall / 1e6 / ticks;
        System.out.printf(Locale.ROOT,
                "%nmean %.3f ms/tick   one room uses %.1f%% of a core at %d Hz%n",
                perTickMs, perTickMs / (1000.0 / TICK_HZ) * 100, TICK_HZ);
        if (allocatedBytes() >= 0) {
            System.out.printf(Locale.ROOT, "allocated: simulation %d bytes in %d of %d ticks,"
                    + " encoding %d bytes in %d of %d rounds (target: none in steady state)%n",
                    simAllocated, ticksAllocating, ticks, encodeAllocated, roundsAllocating, rounds);
        }
        System.out.printf(Locale.ROOT, "rooms per core at %d Hz: %.0f%n",
                TICK_HZ, (1000.0 / TICK_HZ) / perTickMs);
    }

    /**
     * Holds the population at its target. Without this the room thins out as tanks are
     * killed, and the benchmark measures an emptying room rather than a full one — which
     * understates the collision pass, the only phase that matters.
     */
    private static void topUp(Room room, int tanks, int shapes, int startXp) {
        World w = room.world();
        while (w.tanks.size < tanks) {
            Entity tank = room.spawnTank((byte) 0, ++playerTags);
            if (tank == null) {
                break;
            }
            grown(room, tank, startXp);
        }
        while (w.shapes.size < shapes) {
            if (room.spawnShape() == null) {
                break;
            }
        }
    }

    /**
     * Each tank's player tag, as a player's tank carries one: what a kill is logged by and, from
     * plan item 65, an assist is credited by. Untagged, the room skipped that work, which a real
     * one, all players, never does.
     */
    private static long playerTags;

    /** What every tank here is called: eight bytes, as a player's tank carries a name (D-52). */
    private static final byte[] NAME = "player01".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** A new tank, named as a player's is, and given the experience of the start level, which a bot spends at once. */
    private static void grown(Room room, Entity tank, int xp) {
        if (tank != null) {
            tank.name = NAME;
        }
        if (tank != null && xp > 0) {
            room.grantExperience(tank, xp);
        }
    }

    private static final java.lang.management.ThreadMXBean THREADS =
            java.lang.management.ManagementFactory.getThreadMXBean();

    /**
     * This thread's allocation so far; -1 when the JVM cannot say. Through the exported
     * interface: called reflectively on the bean's class, which is in a package jdk.management
     * does not export, it threw every time, the -1s cancelled out, and every run reported
     * 0.0 bytes per tick.
     */
    private static long allocatedBytes() {
        return THREADS instanceof com.sun.management.ThreadMXBean hotSpot
                ? hotSpot.getCurrentThreadAllocatedBytes() : -1;
    }

    private TickBenchmark() {
    }
}
