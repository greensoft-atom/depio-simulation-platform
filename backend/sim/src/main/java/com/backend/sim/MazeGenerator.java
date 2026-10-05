package com.backend.sim;

import java.util.ArrayList;
import java.util.List;

import com.backend.common.Xorshift;
import com.backend.sim.Walls.Wall;

/**
 * The maze (docs detailed-design/01-arena.md §8.9, Q-31, D-48): ten cells a side, cut by
 * recursive division from a seed, then a fifth of the walls taken out so that it has loops.
 *
 * The client makes the same walls from the same seed (D-48), so the order the generator draws its
 * numbers in is part of the protocol: a chamber is split before its two halves, the first half
 * first; a split draws its orientation (only when the chamber is square), its line, then its gap;
 * the walls are listed horizontal ones first, by row then column, then vertical ones, by column
 * then row; and the ones taken out are the first of a partial shuffle of that list.
 */
public final class MazeGenerator {

    public static final int CELLS = 10;
    public static final float CELL = 300f;
    public static final float THICKNESS = 40f;
    /** One wall in this many is taken out after the division. */
    static final int BRAID_DIVISOR = 5;

    public static List<Wall> walls(long seed) {
        Xorshift rng = new Xorshift(seed);
        // across[x][y]: a wall on the top edge of cell (x, y); down[x][y]: on its left edge.
        boolean[][] across = new boolean[CELLS][CELLS];
        boolean[][] down = new boolean[CELLS][CELLS];
        divide(rng, across, down, 0, 0, CELLS, CELLS);

        List<int[]> segments = new ArrayList<>();             // {0 across | 1 down, x, y}
        for (int y = 0; y < CELLS; y++) {
            for (int x = 0; x < CELLS; x++) {
                if (across[x][y]) {
                    segments.add(new int[] {0, x, y});
                }
            }
        }
        for (int x = 0; x < CELLS; x++) {
            for (int y = 0; y < CELLS; y++) {
                if (down[x][y]) {
                    segments.add(new int[] {1, x, y});
                }
            }
        }
        int n = segments.size();
        int removed = n / BRAID_DIVISOR;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        for (int i = 0; i < removed; i++) {
            int j = i + rng.nextInt(n - i);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        boolean[] gone = new boolean[n];
        for (int i = 0; i < removed; i++) {
            gone[order[i]] = true;
        }

        float half = THICKNESS / 2;
        List<Wall> out = new ArrayList<>(n - removed);
        for (int i = 0; i < n; i++) {
            if (gone[i]) {
                continue;
            }
            int[] s = segments.get(i);
            float x = s[1] * CELL;
            float y = s[2] * CELL;
            out.add(s[0] == 0
                    ? new Wall(x - half, y - half, x + CELL + half, y + half)
                    : new Wall(x - half, y - half, x + half, y + CELL + half));
        }
        return List.copyOf(out);
    }

    /** Splits the chamber of {@code w} by {@code h} cells at (x, y) until every chamber is a cell. */
    private static void divide(Xorshift rng, boolean[][] across, boolean[][] down, int x, int y, int w, int h) {
        if (w < 2 && h < 2) {
            return;
        }
        boolean vertical = w > h || (w == h && rng.nextInt(2) == 0);
        if (vertical) {
            int k = 1 + rng.nextInt(w - 1);
            int gap = rng.nextInt(h);
            for (int j = 0; j < h; j++) {
                if (j != gap) {
                    down[x + k][y + j] = true;
                }
            }
            divide(rng, across, down, x, y, k, h);
            divide(rng, across, down, x + k, y, w - k, h);
        } else {
            int k = 1 + rng.nextInt(h - 1);
            int gap = rng.nextInt(w);
            for (int i = 0; i < w; i++) {
                if (i != gap) {
                    across[x + i][y + k] = true;
                }
            }
            divide(rng, across, down, x, y, w, k);
            divide(rng, across, down, x, y + k, w, h - k);
        }
    }

    private MazeGenerator() {
    }
}
