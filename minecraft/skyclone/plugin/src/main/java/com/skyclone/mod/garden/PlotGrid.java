package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;

/**
 * The Garden's 5x5 plot grid, in template coordinates (an instance adds its origin). Pure Java:
 * the generator, the runtime listeners and the unit tests all agree on where a plot is because
 * they all ask this class.
 *
 * <pre>
 *   col 0..4 west to east, row 0..4 north to south, (2,2) is the barn.
 *   A plot is SIZE x SIZE blocks; its outer BORDER blocks on every side are the shared path,
 *   so two neighbouring plots are separated by a 2*BORDER wide path.
 * </pre>
 */
public final class PlotGrid {

    private PlotGrid() {}

    public static final int SIZE = 96;
    public static final int N = 5;
    public static final int BORDER = 2;
    public static final int GROUND_Y = 70;
    /** Westmost/northmost block of the whole garden. */
    public static final int MIN = -(SIZE * N) / 2;          // -240
    public static final int MAX = MIN + SIZE * N - 1;        // 239

    public static int minX(int col) { return MIN + col * SIZE; }
    public static int minZ(int row) { return MIN + row * SIZE; }
    public static int centerX(int col) { return minX(col) + SIZE / 2; }
    public static int centerZ(int row) { return minZ(row) + SIZE / 2; }

    /** Column (0..4) of a template x, or -1 outside the garden. */
    public static int colOf(double x) {
        if (x < MIN || x >= MAX + 1) return -1;
        return (int) Math.floor((x - MIN) / SIZE);
    }

    public static int rowOf(double z) { return colOf(z); }

    /** The buildable inside of a plot (border path excluded), feet-space y range. */
    public static Box inner(int col, int row) {
        int x0 = minX(col) + BORDER, z0 = minZ(row) + BORDER;
        return new Box(x0, GROUND_Y - 8, z0, x0 + SIZE - 1 - 2 * BORDER, GROUND_Y + 60, z0 + SIZE - 1 - 2 * BORDER);
    }

    /** The whole cell including its share of the path. */
    public static Box cell(int col, int row) {
        return new Box(minX(col), GROUND_Y - 8, minZ(row), minX(col) + SIZE - 1, GROUND_Y + 60, minZ(row) + SIZE - 1);
    }

    /** Is template (x, z) inside the buildable part of plot (col,row)? */
    public static boolean insideInner(double x, double z, int col, int row) {
        Box b = inner(col, row);
        return b.containsXZ(x, z);
    }

    /** Is template (x, z) on the path network (a plot border or the outer rim)? */
    public static boolean onPath(int x, int z) {
        int col = colOf(x), row = rowOf(z);
        if (col < 0 || row < 0) return false;
        int lx = x - minX(col), lz = z - minZ(row);
        return lx < BORDER || lz < BORDER || lx >= SIZE - BORDER || lz >= SIZE - BORDER;
    }

    public static boolean isBarn(int col, int row) { return col == 2 && row == 2; }
}
