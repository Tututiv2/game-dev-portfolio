package com.skyclone.mod.hollows.gen;

import com.skyclone.gen.Palette;

/**
 * Carving operations on a {@link Ctx}'s grid: noisy chambers, flat chamber floors, and the
 * walkable tunnels that hold the cave network together.
 *
 * <p>A tunnel is a polyline of FLOOR points {x, floorY, z}. Every segment must climb at most
 * 0.8 blocks per block walked (asserted), so the floor of the walkway steps by at most one block
 * at a time. The walkway (a 3-wide strip) is written as LOCKED rock with locked air above it, so
 * noise caves, other passes and decoration can never cut a hole in it: where a tunnel crosses a
 * chasm or a lava lake, the walkway simply becomes a bridge. Pure Java.
 */
public final class Carve {

    private Carve() {}

    /** Noisy ellipsoid chamber. {@code amp} = wall roughness (0.1-0.25). */
    public static void blob(Ctx x, double cx, double cy, double cz, double rx, double ry, double rz, double amp) {
        Grid g = x.g;
        int x0 = (int) Math.floor(cx - rx * (1 + amp)), x1 = (int) Math.ceil(cx + rx * (1 + amp));
        int y0 = (int) Math.floor(cy - ry * (1 + amp)), y1 = (int) Math.ceil(cy + ry * (1 + amp));
        int z0 = (int) Math.floor(cz - rz * (1 + amp)), z1 = (int) Math.ceil(cz + rz * (1 + amp));
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int xx = x0; xx <= x1; xx++) {
                    double dx = (xx + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry, dz = (z + 0.5 - cz) / rz;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > 1 + amp) continue;
                    double n = x.noise.noise3(xx / 13.0, y / 9.0, z / 13.0);
                    if (d < 1 + amp * n) g.carve(xx, y, z);
                }
    }

    /** Height function for a chamber floor. */
    public interface Floor { int at(int x, int z); }

    /**
     * Give every OPEN column inside the horizontal ellipse a solid floor at {@code floor.at(x,z)}:
     * rock is filled from {@code depth} below up to the floor, the floor block is locked, and the air
     * above it (up to the chamber's existing air) is left as it is.
     */
    public static void floor(Ctx x, int cx, int cz, double rx, double rz, int probeY, int depth, Floor floor) {
        Grid g = x.g;
        for (int z = (int) (cz - rz); z <= cz + rz; z++)
            for (int xx = (int) (cx - rx); xx <= cx + rx; xx++) {
                double dx = (xx + 0.5 - cx) / rx, dz = (z + 0.5 - cz) / rz;
                if (dx * dx + dz * dz > 1) continue;
                if (!g.air(xx, probeY, z)) continue;
                int f = floor.at(xx, z);
                for (int y = f - depth; y < f; y++) g.set(xx, y, z, Grid.ROCK);
                if (!(g.locked(xx, f, z) && g.air(xx, f, z))) g.put(xx, f, z, Grid.ROCK, true);
                for (int y = f + 1; y <= f + 3; y++) if (!g.locked(xx, y, z)) g.put(xx, y, z, Grid.AIR, false);
            }
    }

    /** A tunnel along floor points; see the class comment. */
    public static void tunnel(Ctx x, int[][] pts, double radius, int height, Palette deck, boolean lights) {
        double acc = 0;
        for (int s = 0; s + 1 < pts.length; s++) {
            int[] a = pts[s], b = pts[s + 1];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dy) > len * 0.8 + 0.01)
                throw new IllegalStateException("tunnel segment too steep: " + a[0] + "," + a[1] + "," + a[2] + " -> " + b[0] + "," + b[1] + "," + b[2]);
            int n = Math.max(1, (int) Math.ceil(len * 2));
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                double px = a[0] + dx * t, pz = a[2] + dz * t;
                int fy = (int) Math.round(a[1] + dy * t);
                bore(x, px, fy, pz, radius, height);
                walk(x, px, fy, pz, 1.6, deck);
                acc += len / n;
                if (lights && acc >= 13) {
                    acc = 0;
                    x.lanterns.add(new int[]{(int) Math.floor(px), fy + height, (int) Math.floor(pz)});
                }
            }
        }
    }

    /** Clear a flat-bottomed, round-topped bore around (px, pz) above the floor fy. */
    public static void bore(Ctx x, double px, int fy, double pz, double r, int height) {
        int r0 = (int) Math.ceil(r);
        int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
        for (int dz = -r0; dz <= r0; dz++)
            for (int dx = -r0; dx <= r0; dx++) {
                double d = Math.hypot(cx + dx + 0.5 - px, cz + dz + 0.5 - pz);
                if (d > r) continue;
                int h = height - (int) Math.round(2.2 * (d / r) * (d / r));
                for (int y = fy + 1; y <= fy + h; y++) x.g.carve(cx + dx, y, cz + dz);
            }
    }

    /** Lay a locked walkway strip of half-width {@code wr} at floor fy. */
    public static void walk(Ctx x, double px, int fy, double pz, double wr, Palette deck) {
        Grid g = x.g;
        int r0 = (int) Math.ceil(wr);
        int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
        for (int dz = -r0; dz <= r0; dz++)
            for (int dx = -r0; dx <= r0; dx++) {
                int xx = cx + dx, zz = cz + dz;
                if (Math.hypot(xx + 0.5 - px, zz + 0.5 - pz) > wr) continue;
                if (!g.in(xx, fy, zz) || g.kind(xx, fy, zz) == Grid.BEDROCK) continue;
                if (g.locked(xx, fy, zz) && g.air(xx, fy, zz)) continue;          // a structure's space
                boolean wasWalk = g.locked(xx, fy, zz) && g.solid(xx, fy, zz);
                if (!wasWalk && !g.solid(xx, fy - 1, zz) && !g.solid(xx, fy, zz)) x.bridge.add(Ctx.key(xx, fy, zz));
                g.put(xx, fy, zz, Grid.ROCK, true);
                if (deck != null) x.deck.put(Ctx.key(xx, fy, zz), deck.at(xx, fy, zz));
                for (int y = fy + 1; y <= fy + 3; y++) {
                    if (g.locked(xx, y, zz) && g.solid(xx, y, zz)) continue;
                    g.put(xx, y, zz, Grid.AIR, true);
                }
            }
    }

    /**
     * A small alcove cut into rock beside a walkway: {@code w} wide, 3 tall, {@code depth} deep in
     * direction (dx, dz) from the opening at (x0, z0). Returns the innermost floor cell {x, floorY, z}.
     */
    public static int[] alcove(Ctx x, int x0, int fy, int z0, int dx, int dz, int depth) {
        int[] last = {x0, fy, z0};
        for (int i = 0; i <= depth; i++) {
            int ax = x0 + dx * i, az = z0 + dz * i;
            for (int s = -1; s <= 0; s++) {
                int sx = ax + (dz != 0 ? s : 0), sz = az + (dx != 0 ? s : 0);
                x.g.put(sx, fy, sz, Grid.ROCK, true);
                for (int y = fy + 1; y <= fy + 3; y++) x.g.put(sx, y, sz, Grid.AIR, true);
            }
            last = new int[]{ax, fy, az};
        }
        return last;
    }
}
