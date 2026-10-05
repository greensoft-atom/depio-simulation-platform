package com.backend.sim;

import java.util.Arrays;

import com.backend.common.IntList;

/**
 * Uniform grid over the map, rebuilt from scratch every tick.
 *
 * Intrusive singly-linked lists: {@code head[cell]} is the first entity id in that cell and
 * {@code next[id]} is the one after it. Two int arrays, no per-cell collections, no
 * allocation after construction — rebuilding is a fill plus one write per entity, which is
 * cheaper than incrementally maintaining buckets when almost everything moves every tick.
 */
public final class SpatialHash {

    private final float cellSize;
    private final int cols, rows;
    private final int[] head;
    private final int[] next;

    public SpatialHash(float mapWidth, float mapHeight, float cellSize, int entityCapacity) {
        this.cellSize = cellSize;
        this.cols = Math.max(1, (int) Math.ceil(mapWidth / cellSize));
        this.rows = Math.max(1, (int) Math.ceil(mapHeight / cellSize));
        this.head = new int[cols * rows];
        this.next = new int[entityCapacity];
        Arrays.fill(head, -1);
    }

    public int cellCount() {
        return head.length;
    }

    public void clear() {
        Arrays.fill(head, -1);
    }

    private int col(float x) {
        int c = (int) (x / cellSize);
        return c < 0 ? 0 : (c >= cols ? cols - 1 : c);
    }

    private int row(float y) {
        int r = (int) (y / cellSize);
        return r < 0 ? 0 : (r >= rows ? rows - 1 : r);
    }

    public void insert(int id, float x, float y) {
        int cell = row(y) * cols + col(x);
        next[id] = head[cell];
        head[cell] = id;
    }

    /**
     * Collects entity ids in the cells overlapping the circle into {@code out}, which is
     * cleared first and is expected to be a reused scratch buffer.
     *
     * Returns candidates, not hits: the caller still does the circle test. Splitting it this
     * way keeps the grid ignorant of what a collision means.
     */
    public void queryInto(float x, float y, float radius, IntList out) {
        out.clear();
        int c0 = col(x - radius), c1 = col(x + radius);
        int r0 = row(y - radius), r1 = row(y + radius);
        for (int r = r0; r <= r1; r++) {
            int base = r * cols;
            for (int c = c0; c <= c1; c++) {
                for (int id = head[base + c]; id != -1; id = next[id]) {
                    out.add(id);
                }
            }
        }
    }
}
