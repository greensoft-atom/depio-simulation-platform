package com.backend.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * A maze's walls (docs detailed-design/01-arena.md §8.9, D-48): axis-aligned rectangles, indexed
 * by the cells they cover, so that a collision looks only at the walls of the cells an entity
 * touches. Immutable once made; one room's.
 */
public final class Walls {

    /** A wall, as its rectangle's corners. */
    public record Wall(float minX, float minY, float maxX, float maxY) { }

    private final List<Wall> walls;
    private final float cell;
    private final int columns;
    private final int rows;
    private final List<List<Wall>> byCell;

    public Walls(List<Wall> walls, float cell) {
        this.walls = List.copyOf(walls);
        this.cell = cell;
        float right = 0f;
        float bottom = 0f;
        for (Wall w : walls) {
            right = Math.max(right, w.maxX());
            bottom = Math.max(bottom, w.maxY());
        }
        this.columns = (int) (right / cell) + 1;
        this.rows = (int) (bottom / cell) + 1;
        this.byCell = new ArrayList<>(columns * rows);
        for (int i = 0; i < columns * rows; i++) {
            byCell.add(new ArrayList<>());
        }
        for (Wall w : walls) {
            for (int cx = column(w.minX()); cx <= column(w.maxX()); cx++) {
                for (int cy = row(w.minY()); cy <= row(w.maxY()); cy++) {
                    byCell.get(cy * columns + cx).add(w);
                }
            }
        }
    }

    public List<Wall> all() {
        return walls;
    }

    private int column(float x) {
        return Math.max(0, Math.min(columns - 1, (int) Math.floor(x / cell)));
    }

    private int row(float y) {
        return Math.max(0, Math.min(rows - 1, (int) Math.floor(y / cell)));
    }

    /**
     * Whether a circle touches a wall: its centre within its radius of a wall's rectangle. The
     * rule a bullet ends by, on the server and on the client alike (D-48).
     */
    public boolean hits(float x, float y, float r) {
        for (int cx = column(x - r); cx <= column(x + r); cx++) {
            for (int cy = row(y - r); cy <= row(y + r); cy++) {
                for (Wall w : byCell.get(cy * columns + cx)) {
                    float dx = x - Math.max(w.minX(), Math.min(x, w.maxX()));
                    float dy = y - Math.max(w.minY(), Math.min(y, w.maxY()));
                    if (dx * dx + dy * dy <= r * r) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Moves an entity out of every wall it overlaps, by the shorter way, and drops its velocity
     * into the wall, so that it slides along it (01 §8.9).
     */
    void pushOut(Entity e) {
        float r = e.radius;
        for (int cx = column(e.x - r); cx <= column(e.x + r); cx++) {
            for (int cy = row(e.y - r); cy <= row(e.y + r); cy++) {
                for (Wall w : byCell.get(cy * columns + cx)) {
                    pushOut(e, w);
                }
            }
        }
    }

    private static void pushOut(Entity e, Wall w) {
        float r = e.radius;
        float nearX = Math.max(w.minX(), Math.min(e.x, w.maxX()));
        float nearY = Math.max(w.minY(), Math.min(e.y, w.maxY()));
        float dx = e.x - nearX;
        float dy = e.y - nearY;
        float d2 = dx * dx + dy * dy;
        if (d2 > 0f) {
            if (d2 >= r * r) {
                return;                                        // not touching
            }
            float d = (float) Math.sqrt(d2);
            float nx = dx / d;
            float ny = dy / d;
            e.x += nx * (r - d);
            e.y += ny * (r - d);
            float into = e.vx * nx + e.vy * ny;
            if (into < 0f) {
                e.vx -= into * nx;
                e.vy -= into * ny;
            }
            return;
        }
        // The centre inside: out through the nearest face.
        float left = e.x - w.minX();
        float right = w.maxX() - e.x;
        float up = e.y - w.minY();
        float down = w.maxY() - e.y;
        float least = Math.min(Math.min(left, right), Math.min(up, down));
        if (least == left) {
            e.x = w.minX() - r;
            e.vx = Math.min(e.vx, 0f);
        } else if (least == right) {
            e.x = w.maxX() + r;
            e.vx = Math.max(e.vx, 0f);
        } else if (least == up) {
            e.y = w.minY() - r;
            e.vy = Math.min(e.vy, 0f);
        } else {
            e.y = w.maxY() + r;
            e.vy = Math.max(e.vy, 0f);
        }
    }
}
