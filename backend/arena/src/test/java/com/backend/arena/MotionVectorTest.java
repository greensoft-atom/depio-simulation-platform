package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.backend.common.PhaseTimer;
import com.backend.protocol.ClientMessage;
import com.backend.sim.Entity;
import com.backend.sim.MazeGenerator;
import com.backend.sim.Room;
import com.backend.sim.Walls;
import com.backend.sim.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The golden vector the client's prediction is held to (02 §9, D-62): a driven tank stepped by the
 * room itself, its direction from the move bits as {@link RoomThread} reads them, each step's
 * position and velocity written as the floats' bits. The client's {@code TankMotion.Step} must
 * make every one of them from the same moves; {@code client/Core.Tests} reads this file.
 *
 * Regenerated only on purpose, with {@code -Dmotion.vector.write=true}: a diff is a change to how
 * a tank moves, which the client's port must follow.
 */
class MotionVectorTest {

    private static final Path VECTOR = Path.of("src/test/resources/motion-2026.txt");

    private static final int U = ClientMessage.MOVE_UP, D = ClientMessage.MOVE_DOWN,
            L = ClientMessage.MOVE_LEFT, R = ClientMessage.MOVE_RIGHT;

    /** Where it starts and what it is driven by: {move, ticks} pairs. */
    private record Case(String name, float map, long mazeSeed, float x, float y, float vx, float vy, int[][] script) { }

    private static final List<Case> CASES = List.of(
            // Into the left edge and the top, then diagonals, a stop, and back into the corner.
            new Case("open", 1_000f, 0L, 45f, 45f, 0f, 0f, new int[][] {
                    {L, 10}, {U, 10}, {R | D, 30}, {0, 10}, {U | R, 20}, {L, 15}, {D, 25}, {U | L, 60}}),
            // Onto seed 2026's first wall from above, along it and round its end, down onto the next two
            // (one wall in two cells, and two walls meeting), and along them.
            new Case("maze", 3_000f, 2026L, 2_350f, 200f, 0f, 0f, new int[][] {
                    {D, 30}, {D | R, 150}, {D, 300}, {L | D, 120}, {U, 30}, {U | L, 40}, {R, 20}}),
            // Knocked hard into the wall: its centre inside, out by the nearest face; the server's
            // velocity in a frame carries knocks like this one, and the replay starts from it.
            new Case("knock", 3_000f, 2026L, 2_250f, 200f, 0f, 90f, new int[][] {
                    {0, 1}, {D, 10}, {R, 10}}));

    @Test
    @DisplayName("the golden vector: the room's steps of a driven tank, which the client's prediction must make bit for bit (D-62)")
    void goldenVector() throws Exception {
        StringBuilder text = new StringBuilder()
                .append("# The room's steps of a driven tank (Room.updateTanks, RoomThread.directionX/Y), for the\n")
                .append("# client's prediction (docs 02 §9, D-62). Written by arena's MotionVectorTest; floats as their bits.\n")
                .append("# case name mapWidth mapHeight mazeSeed accel radius x y vx vy\n")
                .append("# step moveBits x y vx vy (after the step)\n");
        for (Case c : CASES) {
            World world = new World(c.map(), c.map(), 64, 100f, 1L);
            Room room = new Room(world);
            if (c.mazeSeed() != 0L) {
                room.setWalls(new Walls(MazeGenerator.walls(c.mazeSeed()), MazeGenerator.CELL));
            }
            Entity e = room.spawnTank((byte) 0, 1L);
            e.x = c.x();
            e.y = c.y();
            e.vx = c.vx();
            e.vy = c.vy();
            e.playerControlled = true;
            e.wantsFire = false;
            e.aimAngle = 0f;
            float accel = Room.tankAccel(world.tankStats[e.id]);
            text.append("case ").append(c.name()).append(' ').append((int) c.map()).append(' ').append((int) c.map())
                    .append(' ').append(c.mazeSeed()).append(' ').append(bits(accel)).append(' ').append(bits(e.radius))
                    .append(' ').append(bits(e.x)).append(' ').append(bits(e.y))
                    .append(' ').append(bits(e.vx)).append(' ').append(bits(e.vy)).append('\n');
            PhaseTimer timer = Room.newTimer();
            for (int[] part : c.script()) {
                for (int t = 0; t < part[1]; t++) {
                    e.moveX = RoomThread.directionX(part[0]);
                    e.moveY = RoomThread.directionY(part[0]);
                    room.step(timer);
                    text.append("step ").append(part[0]).append(' ').append(bits(e.x)).append(' ').append(bits(e.y))
                            .append(' ').append(bits(e.vx)).append(' ').append(bits(e.vy)).append('\n');
                }
            }
        }
        if (Boolean.getBoolean("motion.vector.write")) {
            Files.writeString(VECTOR, text);
        }
        assertThat(Files.readString(VECTOR)).as("regenerate the vector only on purpose").isEqualTo(text.toString());
    }

    @Test
    @DisplayName("the move bits as the room reads them: a diagonal no faster than straight, opposites cancelling")
    void theMoveBitsAsADirection() {
        assertThat(RoomThread.directionX(R)).isEqualTo(1f);
        assertThat(RoomThread.directionY(R)).isEqualTo(0f);
        assertThat(RoomThread.directionX(U | L)).isEqualTo(-0.70710678f);
        assertThat(RoomThread.directionY(U | L)).isEqualTo(-0.70710678f);
        assertThat(RoomThread.directionX(L | R)).isEqualTo(0f);
        assertThat(RoomThread.directionY(L | R | D)).as("left and right cancel: straight down").isEqualTo(1f);
    }

    private static String bits(float f) {
        return String.format("%08x", Float.floatToRawIntBits(f));
    }
}
