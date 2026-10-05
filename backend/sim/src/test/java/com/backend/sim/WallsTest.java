package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.backend.common.PhaseTimer;
import com.backend.sim.Walls.Wall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What walls do (01 §8.9, Q-31, D-48). */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WallsTest {

    private static final PhaseTimer TIMER = Room.newTimer();
    /** One wall down the map, from x = 600 to 640. */
    private static final Walls ONE = new Walls(List.of(new Wall(600f, 0f, 640f, 2_000f)), MazeGenerator.CELL);

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    private static Room room(Walls walls) {
        Room r = new Room(new World(2_000f, 2_000f, 512, 100f, 9L), Fixtures.UNPROTECTED);
        r.setWalls(walls);
        return r;
    }

    private static Entity driver(Room r, float x, float y) {
        Entity t = r.spawnTank((byte) 0, 1L);
        t.x = x;
        t.y = y;
        t.playerControlled = true;
        return t;
    }

    @Test
    @DisplayName("a tank driving into a wall stops at its face")
    void stops() {
        Room r = room(ONE);
        Entity t = driver(r, 500f, 1_000f);
        t.moveX = 1f;
        for (int i = 0; i < 300; i++) {
            run(r, 1);
            assertThat(t.x + t.radius).as("tick %d", i).isLessThanOrEqualTo(600.01f);
        }
        assertThat(t.x + t.radius).as("at the face").isCloseTo(600f, org.assertj.core.data.Offset.offset(1f));
        assertThat(t.vx).as("its speed into the wall dropped").isLessThanOrEqualTo(0.01f);
    }

    @Test
    @DisplayName("drones sent beyond a wall stop at it")
    void drones() {
        Room r = room(ONE);
        Entity t = driver(r, 450f, 1_000f);
        r.assignClass(t, ClassTable.OVERSEER);
        t.aimAngle = 0f;
        t.attacking = true;                                  // the drones go for the aim, beyond the wall
        run(r, 300);
        World w = r.world();
        int drones = 0;
        for (int b = 0; b < w.bullets.size; b++) {
            Entity e = w.entities[w.bullets.items[b]];
            if (e.alive && e.subtype == ClassTable.Barrel.DRONE) {
                drones++;
                assertThat(e.x + e.radius).as("drone at %s", e.x).isLessThanOrEqualTo(600.01f);
            }
        }
        assertThat(drones).as("some were out").isPositive();
    }

    @Test
    @DisplayName("driving into it at a slant, it slides along it")
    void slides() {
        Room r = room(ONE);
        Entity t = driver(r, 500f, 500f);
        t.moveX = 0.7f;
        t.moveY = 0.7f;
        run(r, 200);
        assertThat(t.x + t.radius).as("held at the face").isLessThanOrEqualTo(600.01f);
        assertThat(t.y).as("and down the wall").isGreaterThan(650f);
        assertThat(t.vy).as("still going, at speed").isGreaterThan(0.5f);
    }

    @Test
    @DisplayName("a bullet ends at the wall it reaches")
    void bulletsEnd() {
        Room r = room(ONE);
        Entity t = driver(r, 400f, 1_000f);
        t.aimAngle = 0f;
        t.wantsFire = true;
        t.reloadTicks = 0;
        for (int i = 0; i < 120; i++) {
            run(r, 1);
            World w = r.world();
            for (int b = 0; b < w.bullets.size; b++) {
                Entity e = w.entities[w.bullets.items[b]];
                assertThat(e.x - e.radius).as("no bullet beyond the wall's near face").isLessThan(600f);
            }
        }
    }

    @Test
    @DisplayName("a shape inside a wall is pushed out of it")
    void shapesOut() {
        Room r = room(ONE);
        Entity s = r.spawnShape();
        s.x = 615f;
        s.y = 1_000f;
        s.vx = 0f;
        s.vy = 0f;
        run(r, 1);
        assertThat(s.x + s.radius).as("out, touching at most, the shorter way: left").isLessThanOrEqualTo(600.01f);
    }

    @Test
    @DisplayName("a tank whose centre is inside a wall leaves it by the shorter way")
    void deep() {
        Room r = room(ONE);
        Entity t = driver(r, 635f, 1_000f);
        run(r, 1);
        assertThat(t.x - t.radius).as("out to the right, nearer").isGreaterThanOrEqualTo(639.99f);
    }

    @Test
    @DisplayName("in a maze, nobody spawns in a wall")
    void spawns() {
        Room r = new Room(new World(3_000f, 3_000f, 2_048, 100f, 7L), Fixtures.UNPROTECTED);
        Walls maze = new Walls(MazeGenerator.walls(7L), MazeGenerator.CELL);
        r.setWalls(maze);
        for (int i = 0; i < 200; i++) {
            Entity t = r.spawnTank((byte) 0);
            assertThat(t).isNotNull();
            assertThat(maze.hits(t.x, t.y, t.radius)).as("tank %d at %s,%s", i, t.x, t.y).isFalse();
            r.world().kill(t);
        }
    }

    @Test
    @DisplayName("with no clear place found, a spawn is still put out of the walls")
    void spawnsWithNoClearPlace() {
        Content oneTry = new Content(StatTable.defaults(), LevelTable.defaults(), ShapeTable.defaults(),
                Recovery.defaults(), new Spawning(0, 500f, 1));   // one attempt: a wall is often all it finds
        Room r = new Room(new World(3_000f, 3_000f, 2_048, 100f, 7L), oneTry);
        Walls maze = new Walls(MazeGenerator.walls(7L), MazeGenerator.CELL);
        r.setWalls(maze);
        for (int i = 0; i < 200; i++) {
            Entity t = r.spawnTank((byte) 0);
            assertThat(maze.hits(t.x, t.y, t.radius - 0.01f)).as("tank %d at %s,%s: touching at most", i, t.x, t.y).isFalse();
            r.world().kill(t);
        }
    }

    @Test
    @DisplayName("a circle hits a wall when its centre is within its radius of the rectangle, and not otherwise")
    void hits() {
        assertThat(ONE.hits(590f, 1_000f, 10f)).isTrue();
        assertThat(ONE.hits(589f, 1_000f, 10f)).isFalse();
        assertThat(ONE.hits(650f, 1_000f, 10f)).isTrue();
        assertThat(ONE.hits(651f, 1_000f, 10f)).isFalse();
        assertThat(ONE.hits(620f, 2_005f, 10f)).as("past its end, within reach").isTrue();
        assertThat(ONE.hits(620f, 2_011f, 10f)).isFalse();
    }
}
