package com.skyclone.mod.mines.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * A cart line: a chain of 4-connected floor cells {@code {x, floorY, z}} (the rail sits on top of the floor
 * block, at floorY + 1). Every Dwarven Mines station stores the line from the depot (the village station) to
 * itself in the layout ({@code rail.<station>}), so the ride from A to B is A's line walked backwards to where it
 * leaves B's line, then B's line forwards: the track IS the shortest-path tree the generator laid.
 *
 * <p>Pure Java (no Bukkit): the generator writes it, the module rides it, the tests check it.
 */
public final class RailRoute {

    private RailRoute() {}

    /** Directions n, e, s, w (north = -z). */
    public static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};
    public static final String[] NAMES = {"north", "east", "south", "west"};
    private static final String FLAT = "nesw", UP = "NESW", DOWN = "1234";
    /** Height of a cart's wheels above the rail block's bottom. */
    public static final double RAIL_TOP = 0.0625;

    /** Direction index of a step (dx, dz), or -1 if it is not one 4-connected step. */
    public static int dirOf(int dx, int dz) {
        for (int d = 0; d < 4; d++) if (DX[d] == dx && DZ[d] == dz) return d;
        return -1;
    }

    /** "x,y,z:" then one character per step: n/e/s/w flat, N/E/S/W one up, 1/2/3/4 one down. Null if not contiguous. */
    public static String encode(List<int[]> cells) {
        if (cells == null || cells.isEmpty()) return null;
        int[] a = cells.get(0);
        StringBuilder sb = new StringBuilder().append(a[0]).append(',').append(a[1]).append(',').append(a[2]).append(':');
        for (int i = 1; i < cells.size(); i++) {
            int[] p = cells.get(i - 1), q = cells.get(i);
            int d = dirOf(q[0] - p[0], q[2] - p[2]);
            int dy = q[1] - p[1];
            if (d < 0 || Math.abs(dy) > 1) return null;
            sb.append(dy == 0 ? FLAT.charAt(d) : dy > 0 ? UP.charAt(d) : DOWN.charAt(d));
        }
        return sb.toString();
    }

    /** The cells of an encoded line, or an empty list if it does not parse. */
    public static List<int[]> decode(String s) {
        List<int[]> out = new ArrayList<>();
        if (s == null) return out;
        int colon = s.indexOf(':');
        if (colon < 0) return out;
        String[] head = s.substring(0, colon).split(",");
        if (head.length != 3) return out;
        int x, y, z;
        try { x = Integer.parseInt(head[0].trim()); y = Integer.parseInt(head[1].trim()); z = Integer.parseInt(head[2].trim()); }
        catch (NumberFormatException e) { return out; }
        out.add(new int[]{x, y, z});
        for (int i = colon + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            int d = FLAT.indexOf(ch), dy = 0;
            if (d < 0) { d = UP.indexOf(ch); dy = 1; }
            if (d < 0) { d = DOWN.indexOf(ch); dy = -1; }
            if (d < 0) return new ArrayList<>();
            x += DX[d]; z += DZ[d]; y += dy;
            out.add(new int[]{x, y, z});
        }
        return out;
    }

    private static boolean same(int[] a, int[] b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }

    /**
     * The ride from A to B over a tree of lines that all start at the same depot cell: A's line backwards to the
     * last cell it shares with B's, then B's line forwards. Null when the lines do not share their first cell.
     */
    public static List<int[]> join(List<int[]> depotToA, List<int[]> depotToB) {
        if (depotToA == null || depotToB == null || depotToA.isEmpty() || depotToB.isEmpty()) return null;
        if (!same(depotToA.get(0), depotToB.get(0))) return null;
        int k = 0;
        while (k + 1 < depotToA.size() && k + 1 < depotToB.size() && same(depotToA.get(k + 1), depotToB.get(k + 1))) k++;
        List<int[]> out = new ArrayList<>();
        for (int i = depotToA.size() - 1; i >= k; i--) out.add(depotToA.get(i));
        for (int i = k + 1; i < depotToB.size(); i++) out.add(depotToB.get(i));
        return out;
    }

    /** True when every step is one 4-connected cell with at most one block of rise or fall. */
    public static boolean contiguous(List<int[]> cells) {
        if (cells == null || cells.isEmpty()) return false;
        for (int i = 1; i < cells.size(); i++) {
            int[] p = cells.get(i - 1), q = cells.get(i);
            if (dirOf(q[0] - p[0], q[2] - p[2]) < 0 || Math.abs(q[1] - p[1]) > 1) return false;
        }
        return true;
    }

    /**
     * The rail block for cell i of a line: ascending toward a neighbour one higher, else straight or curved
     * between its two neighbours (an end cell runs straight on). Vanilla shape names.
     */
    public static String shape(List<int[]> cells, int i) {
        int[] c = cells.get(i);
        int[] p = i > 0 ? cells.get(i - 1) : null, n = i + 1 < cells.size() ? cells.get(i + 1) : null;
        if (n != null && n[1] == c[1] + 1) return "ascending_" + NAMES[dirOf(n[0] - c[0], n[2] - c[2])];
        if (p != null && p[1] == c[1] + 1) return "ascending_" + NAMES[dirOf(p[0] - c[0], p[2] - c[2])];
        int a = p != null ? dirOf(p[0] - c[0], p[2] - c[2]) : -1, b = n != null ? dirOf(n[0] - c[0], n[2] - c[2]) : -1;
        if (a < 0) a = b < 0 ? 0 : (b + 2) % 4;
        if (b < 0) b = (a + 2) % 4;
        if ((a + 2) % 4 == b) return a % 2 == 0 ? "north_south" : "east_west";
        // a curve joins two perpendicular sides; vanilla names it north/south side first
        int ns = a % 2 == 0 ? a : b, ew = a % 2 == 0 ? b : a;
        return NAMES[ns] + "_" + NAMES[ew];
    }

    /** True when the cell's rail turns (a curve): the cart cuts its corner instead of visiting the centre. */
    public static boolean curve(List<int[]> cells, int i) {
        if (i <= 0 || i + 1 >= cells.size()) return false;
        int[] p = cells.get(i - 1), c = cells.get(i), n = cells.get(i + 1);
        int a = dirOf(c[0] - p[0], c[2] - p[2]), b = dirOf(n[0] - c[0], n[2] - c[2]);
        return a != b;
    }

    /**
     * Where a cart's wheels go along a line, as world points {x, y, z}: every cell centre (curves excepted) and every
     * edge between two cells, with the height a vanilla cart has there (an ascending rail rises one block across
     * its cell, so the edge into a higher cell is already at that cell's height).
     */
    public static List<double[]> waypoints(List<int[]> cells) {
        List<double[]> out = new ArrayList<>();
        if (cells == null || cells.isEmpty()) return out;
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            if (i > 0) {
                int[] p = cells.get(i - 1);
                out.add(new double[]{(p[0] + c[0]) / 2.0 + 0.5, Math.max(p[1], c[1]) + 1 + RAIL_TOP, (p[2] + c[2]) / 2.0 + 0.5});
            }
            if (curve(cells, i)) continue;
            boolean up = i + 1 < cells.size() && cells.get(i + 1)[1] > c[1] || i > 0 && cells.get(i - 1)[1] > c[1];
            out.add(new double[]{c[0] + 0.5, c[1] + 1 + (up ? 0.5 : 0) + RAIL_TOP, c[2] + 0.5});
        }
        return out;
    }

    /** A straight vertical run (the lift cage): from y0 to y1 at (x, z). */
    public static List<double[]> vertical(double x, double y0, double y1, double z) {
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{x, y0, z});
        out.add(new double[]{x, y1, z});
        return out;
    }

    /** Cumulative distance along a polyline: {@code cum[i]} = length from point 0 to point i. */
    public static double[] cumulative(List<double[]> pts) {
        double[] cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            cum[i] = cum[i - 1] + Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
        }
        return cum;
    }

    /** The point {@code s} along a polyline (clamped to its ends), plus the heading {dx, dz} of that segment. */
    public static double[] at(List<double[]> pts, double[] cum, double s) {
        if (pts.isEmpty()) return new double[]{0, 0, 0, 0, 1};
        if (pts.size() == 1 || s <= 0) {
            double[] a = pts.get(0), b = pts.size() > 1 ? pts.get(1) : a;
            return new double[]{a[0], a[1], a[2], b[0] - a[0], b[2] - a[2]};
        }
        int hi = 1;
        while (hi < pts.size() - 1 && cum[hi] < s) hi++;
        double[] a = pts.get(hi - 1), b = pts.get(hi);
        double seg = cum[hi] - cum[hi - 1];
        double t = seg <= 1e-9 ? 1 : Math.max(0, Math.min(1, (s - cum[hi - 1]) / seg));
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, b[0] - a[0], b[2] - a[2]};
    }

    /**
     * Speed for the next tick: accelerate by {@code accel} toward {@code max}, and brake so the cart can stop by the
     * end ({@code left} blocks away). Never below {@code min}, so a ride always finishes.
     */
    public static double nextSpeed(double v, double max, double accel, double left, double min) {
        double nv = Math.min(max, v + accel);
        nv = Math.min(nv, Math.sqrt(2 * accel * Math.max(0, left)) + min);
        return Math.max(min, nv);
    }
}
