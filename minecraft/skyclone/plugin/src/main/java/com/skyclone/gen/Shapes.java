package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

/**
 * Solid geometry. Every method takes a {@link Fill} (a block or a {@link Palette}); the String
 * overloads are shorthand for {@code Fill.of(block)}. Radii are doubles, so r = 2.5 gives a
 * rounder small sphere than r = 2. Centre coordinates are block coordinates.
 */
public final class Shapes {

    private Shapes() {}

    // ---- boxes ----------------------------------------------------------------------------

    public static void box(VoxelCanvas c, Box b, Fill f) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) c.set(x, y, z, f.at(x, y, z));
    }

    public static void box(VoxelCanvas c, Box b, String block) { box(c, b, Fill.of(block)); }

    /** Walls, floor and ceiling; inside left untouched (use {@link #hollowBox} to also clear it). */
    public static void boxShell(VoxelCanvas c, Box b, Fill f) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    boolean edge = x == b.minX() || x == b.maxX() || y == b.minY() || y == b.maxY() || z == b.minZ() || z == b.maxZ();
                    if (edge) c.set(x, y, z, f.at(x, y, z));
                }
    }

    public static void hollowBox(VoxelCanvas c, Box b, Fill f) {
        boxShell(c, b, f);
        if (b.maxX() - b.minX() >= 2 && b.maxY() - b.minY() >= 2 && b.maxZ() - b.minZ() >= 2)
            box(c, new Box(b.minX() + 1, b.minY() + 1, b.minZ() + 1, b.maxX() - 1, b.maxY() - 1, b.maxZ() - 1), Fill.of("air"));
    }

    /** Only the four vertical walls (no floor/ceiling). */
    public static void walls(VoxelCanvas c, Box b, Fill f) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++)
                    if (x == b.minX() || x == b.maxX() || z == b.minZ() || z == b.maxZ()) c.set(x, y, z, f.at(x, y, z));
    }

    /** Replace only blocks that are currently air (or anything, if onlyAir is false) inside a box. */
    public static void replaceAir(VoxelCanvas c, Box b, Fill f) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) if (c.isAir(x, y, z)) c.set(x, y, z, f.at(x, y, z));
    }

    /** Replace every non-air block in a box whose name matches {@code fromName} (exact block name, no states). */
    public static int replace(VoxelCanvas c, Box b, String fromName, Fill f) {
        int n = 0;
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++)
                    if (BlockState.name(c.get(x, y, z)).equals(fromName)) { c.set(x, y, z, f.at(x, y, z)); n++; }
        return n;
    }

    // ---- round things ---------------------------------------------------------------------

    /** Ellipsoid; {@code shell} > 0 makes it hollow with that wall thickness (inside not cleared). */
    public static void ellipsoid(VoxelCanvas c, double cx, double cy, double cz, double rx, double ry, double rz, Fill f, double shell) {
        int x0 = (int) Math.floor(cx - rx), x1 = (int) Math.ceil(cx + rx);
        int y0 = (int) Math.floor(cy - ry), y1 = (int) Math.ceil(cy + ry);
        int z0 = (int) Math.floor(cz - rz), z1 = (int) Math.ceil(cz + rz);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 1) continue;
                    if (shell > 0) {
                        double ix = (x - cx) / Math.max(0.01, rx - shell), iy = (y - cy) / Math.max(0.01, ry - shell), iz = (z - cz) / Math.max(0.01, rz - shell);
                        if (ix * ix + iy * iy + iz * iz < 1) continue;
                    }
                    c.set(x, y, z, f.at(x, y, z));
                }
    }

    public static void sphere(VoxelCanvas c, double cx, double cy, double cz, double r, Fill f) { ellipsoid(c, cx, cy, cz, r, r, r, f, 0); }

    public static void sphere(VoxelCanvas c, double cx, double cy, double cz, double r, String block) { sphere(c, cx, cy, cz, r, Fill.of(block)); }

    public static void hollowSphere(VoxelCanvas c, double cx, double cy, double cz, double r, double thickness, Fill f) {
        ellipsoid(c, cx, cy, cz, r, r, r, f, thickness);
    }

    /** Upper half of a sphere (y >= cy). {@code shell} > 0 = hollow. */
    public static void dome(VoxelCanvas c, double cx, double cy, double cz, double r, Fill f, double shell) {
        int x0 = (int) Math.floor(cx - r), x1 = (int) Math.ceil(cx + r);
        int z0 = (int) Math.floor(cz - r), z1 = (int) Math.ceil(cz + r);
        int y0 = (int) Math.round(cy), y1 = (int) Math.ceil(cy + r);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > r) continue;
                    if (shell > 0 && d < r - shell) continue;
                    c.set(x, y, z, f.at(x, y, z));
                }
    }

    /** Vertical cylinder from y0 up {@code height} blocks. {@code shell} > 0 = hollow tube (inside untouched). */
    public static void cylinder(VoxelCanvas c, double cx, int y0, double cz, double r, int height, Fill f, double shell) {
        int x0 = (int) Math.floor(cx - r), x1 = (int) Math.ceil(cx + r);
        int z0 = (int) Math.floor(cz - r), z1 = (int) Math.ceil(cz + r);
        double r2 = r * r, ri = Math.max(0, r - shell), ri2 = ri * ri;
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                double dx = x - cx, dz = z - cz, d2 = dx * dx + dz * dz;
                if (d2 > r2) continue;
                if (shell > 0 && d2 < ri2) continue;
                for (int y = y0; y < y0 + height; y++) c.set(x, y, z, f.at(x, y, z));
            }
    }

    public static void cylinder(VoxelCanvas c, double cx, int y0, double cz, double r, int height, String block) {
        cylinder(c, cx, y0, cz, r, height, Fill.of(block), 0);
    }

    /** Flat filled circle at one y. */
    public static void disk(VoxelCanvas c, double cx, int y, double cz, double r, Fill f) { cylinder(c, cx, y, cz, r, 1, f, 0); }

    public static void disk(VoxelCanvas c, double cx, int y, double cz, double r, String block) { disk(c, cx, y, cz, r, Fill.of(block)); }

    /** Ring (annulus) at one y. */
    public static void ring(VoxelCanvas c, double cx, int y, double cz, double r, double thickness, Fill f) {
        cylinder(c, cx, y, cz, r, 1, f, thickness);
    }

    /** Cone with its base at y0 and apex {@code height} above (negative height points down). */
    public static void cone(VoxelCanvas c, double cx, int y0, double cz, double r, int height, Fill f) {
        int h = Math.abs(height), dir = height >= 0 ? 1 : -1;
        for (int i = 0; i < h; i++) {
            double rr = r * (1 - (double) i / h);
            if (rr < 0.5) rr = 0.5;
            disk(c, cx, y0 + i * dir, cz, rr, f);
        }
    }

    /** Horizontal torus around a vertical axis: ring radius R, tube radius r. */
    public static void torus(VoxelCanvas c, double cx, double cy, double cz, double R, double r, Fill f) {
        int x0 = (int) Math.floor(cx - R - r), x1 = (int) Math.ceil(cx + R + r);
        int z0 = (int) Math.floor(cz - R - r), z1 = (int) Math.ceil(cz + R + r);
        int y0 = (int) Math.floor(cy - r), y1 = (int) Math.ceil(cy + r);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    double dx = x - cx, dz = z - cz, dy = y - cy;
                    double q = Math.sqrt(dx * dx + dz * dz) - R;
                    if (q * q + dy * dy <= r * r) c.set(x, y, z, f.at(x, y, z));
                }
    }

    // ---- lines & arches -------------------------------------------------------------------

    /** Thick 3D line: spheres of {@code radius} swept from p1 to p2 (radius 0 = 1-block line). */
    public static void line(VoxelCanvas c, double x1, double y1, double z1, double x2, double y2, double z2, double radius, Fill f) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len * 2));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + dx * t, y = y1 + dy * t, z = z1 + dz * t;
            if (radius <= 0.01) {
                int bx = (int) Math.round(x), by = (int) Math.round(y), bz = (int) Math.round(z);
                c.set(bx, by, bz, f.at(bx, by, bz));
            } else sphere(c, x, y, z, radius, f);
        }
    }

    public static void line(VoxelCanvas c, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        line(c, x1, y1, z1, x2, y2, z2, 0, Fill.of(block));
    }

    /**
     * A semicircular (or elliptical, if {@code height} != half the span) arch standing on the
     * ground at y = baseY between (x1, z1) and (x2, z2). {@code thickness} is the ring thickness,
     * {@code depth} how many blocks deep the arch is (perpendicular to its span).
     */
    public static void arch(VoxelCanvas c, int x1, int z1, int x2, int z2, int baseY, int height, int thickness, int depth, Fill f) {
        double mx = (x1 + x2) / 2.0, mz = (z1 + z2) / 2.0;
        double ux = x2 - x1, uz = z2 - z1;
        double span = Math.sqrt(ux * ux + uz * uz);
        if (span < 1) return;
        ux /= span; uz /= span;
        double px = -uz, pz = ux;                 // perpendicular (depth direction)
        double a = span / 2.0, b = height;
        int steps = (int) Math.ceil(Math.PI * Math.max(a, b) * 3);
        for (int i = 0; i <= steps; i++) {
            double t = Math.PI * i / steps;
            for (double tk = 0; tk < thickness; tk += 0.5) {
                double ra = a - tk, rb = b - tk;
                if (ra < 0.5 || rb < 0.5) continue;
                double along = Math.cos(t) * ra, up = Math.sin(t) * rb;
                for (int d = 0; d < depth; d++) {
                    double off = d - (depth - 1) / 2.0;
                    int x = (int) Math.round(mx + ux * along + px * off);
                    int z = (int) Math.round(mz + uz * along + pz * off);
                    int y = baseY + (int) Math.round(up);
                    c.set(x, y, z, f.at(x, y, z));
                }
            }
        }
        // legs down to the base so the arch stands on something
        for (int d = 0; d < depth; d++) {
            double off = d - (depth - 1) / 2.0;
            for (int s = -1; s <= 1; s += 2)
                for (double tk = 0; tk < thickness; tk += 0.5) {
                    int x = (int) Math.round(mx + ux * s * (a - tk) + px * off);
                    int z = (int) Math.round(mz + uz * s * (a - tk) + pz * off);
                    c.set(x, baseY, z, f.at(x, baseY, z));
                }
        }
    }

    // ---- prisms ---------------------------------------------------------------------------

    /**
     * Vertical prism over a polygon (x,z vertices in order). {@code hollow}: only the outline
     * (walls one block thick).
     */
    public static void prism(VoxelCanvas c, int[][] polyXZ, int y0, int y1, Fill f, boolean hollow) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] p : polyXZ) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]); }
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                if (!inside(polyXZ, x + 0.5, z + 0.5) && !onEdge(polyXZ, x, z)) continue;
                if (hollow && !onEdge(polyXZ, x, z)) {
                    boolean border = false;
                    for (int d = 0; d < 4 && !border; d++)
                        if (!inside(polyXZ, x + Blocks.DX[d] + 0.5, z + Blocks.DZ[d] + 0.5) && !onEdge(polyXZ, x + Blocks.DX[d], z + Blocks.DZ[d])) border = true;
                    if (!border) continue;
                }
                for (int y = y0; y <= y1; y++) c.set(x, y, z, f.at(x, y, z));
            }
    }

    /** Even-odd point in polygon. */
    public static boolean inside(int[][] poly, double x, double z) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0] + 0.5, zi = poly[i][1] + 0.5, xj = poly[j][0] + 0.5, zj = poly[j][1] + 0.5;
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    private static boolean onEdge(int[][] poly, int x, int z) {
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            int ax = poly[j][0], az = poly[j][1], bx = poly[i][0], bz = poly[i][1];
            int steps = Math.max(Math.abs(bx - ax), Math.abs(bz - az));
            for (int s = 0; s <= steps; s++) {
                double t = steps == 0 ? 0 : (double) s / steps;
                if ((int) Math.round(ax + (bx - ax) * t) == x && (int) Math.round(az + (bz - az) * t) == z) return true;
            }
        }
        return false;
    }
}
