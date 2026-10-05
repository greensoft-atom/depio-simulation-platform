package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.List;

import com.backend.sim.Walls.Wall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The maze's generator (01 §8.9, Q-31, D-48). */
class MazeGeneratorTest {

    @Test
    @DisplayName("the same seed makes the same walls, and another seed others")
    void deterministic() {
        assertThat(MazeGenerator.walls(42L)).isEqualTo(MazeGenerator.walls(42L));
        assertThat(MazeGenerator.walls(42L)).isNotEqualTo(MazeGenerator.walls(43L));
    }

    @Test
    @DisplayName("recursive division leaves 81 walls a cell long on ten cells a side; a fifth taken out leaves 65")
    void counts() {
        for (long seed = 1; seed <= 50; seed++) {
            List<Wall> walls = MazeGenerator.walls(seed);
            assertThat(walls).as("seed %d", seed).hasSize(65);
            for (Wall w : walls) {
                float width = w.maxX() - w.minX();
                float height = w.maxY() - w.minY();
                assertThat(new float[] {Math.min(width, height), Math.max(width, height)}).as("one cell long, 40 thick")
                        .containsExactly(MazeGenerator.THICKNESS, MazeGenerator.CELL + MazeGenerator.THICKNESS);
            }
        }
    }

    @Test
    @DisplayName("every cell can be reached from every other: no chamber is shut")
    void connected() {
        for (long seed = 1; seed <= 50; seed++) {
            List<Wall> walls = MazeGenerator.walls(seed);
            int n = MazeGenerator.CELLS;
            boolean[][] seen = new boolean[n][n];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            queue.add(new int[] {0, 0});
            seen[0][0] = true;
            int reached = 1;
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] s : steps) {
                    int x = c[0] + s[0];
                    int y = c[1] + s[1];
                    if (x < 0 || y < 0 || x >= n || y >= n || seen[x][y] || blocked(walls, c[0], c[1], x, y)) {
                        continue;
                    }
                    seen[x][y] = true;
                    reached++;
                    queue.add(new int[] {x, y});
                }
            }
            assertThat(reached).as("seed %d", seed).isEqualTo(n * n);
        }
    }

    /** A wall across the edge between two neighbouring cells: one covering the edge's midpoint. */
    private static boolean blocked(List<Wall> walls, int x0, int y0, int x1, int y1) {
        float c = MazeGenerator.CELL;
        float mx = x0 == x1 ? (x0 + 0.5f) * c : Math.max(x0, x1) * c;
        float my = y0 == y1 ? (y0 + 0.5f) * c : Math.max(y0, y1) * c;
        for (Wall w : walls) {
            if (mx >= w.minX() && mx <= w.maxX() && my >= w.minY() && my <= w.maxY()) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the golden vector: seed 2026's walls, which the client's generator must make too (D-48)")
    void goldenVector() throws Exception {
        List<Wall> walls = MazeGenerator.walls(2026L);
        StringBuilder text = new StringBuilder("# MazeGenerator.walls(2026): minX minY maxX maxY, one wall a line\n");
        for (Wall w : walls) {
            text.append((int) w.minX()).append(' ').append((int) w.minY()).append(' ')
                    .append((int) w.maxX()).append(' ').append((int) w.maxY()).append('\n');
        }
        java.nio.file.Path vector = java.nio.file.Path.of("src/test/resources/maze-2026.txt");
        assertThat(java.nio.file.Files.readString(vector)).as("regenerate the vector only on purpose").isEqualTo(text.toString());
    }
}
