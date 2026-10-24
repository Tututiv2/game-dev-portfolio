package com.skyclone.mod.galatea.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Small pure building pieces shared by the two Galatea-update islands (no Bukkit). Everything here
 * writes into a {@link VoxelCanvas}, so {@code tools/preview.sh} renders exactly what the game builds.
 */
final class GKit {

    private GKit() {}

    static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * A walkable tunnel along a polyline of FLOOR points {x, y, z}: every column within
     * {@code half} of the line gets a solid floor block at the interpolated floor height and air
     * above it ({@code height} high in the middle, one lower at the edge so it reads as a vault).
     * The floor never climbs more than one block per block walked when the points do not.
     * Returns the floor cells it wrote, for placing lights.
     */
    static List<int[]> tunnel(VoxelCanvas c, int[][] pts, double half, int height, Fill floor) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < pts.length; i++) {
            int[] a = pts[i], b = pts[i + 1];
            double len = Math.hypot(b[0] - a[0], b[2] - a[2]);
            int n = Math.max(1, (int) Math.ceil(len * 2));
            for (int s = 0; s <= n; s++) {
                double t = (double) s / n;
                double x = a[0] + (b[0] - a[0]) * t, z = a[2] + (b[2] - a[2]) * t;
                int fy = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int r = (int) Math.ceil(half);
                for (int dz = -r; dz <= r; dz++)
                    for (int dx = -r; dx <= r; dx++) {
                        double d = Math.hypot(dx, dz);
                        if (d > half + 0.01) continue;
                        int bx = (int) Math.floor(x + 0.5) + dx, bz = (int) Math.floor(z + 0.5) + dz;
                        int top = d > half - 1 ? height - 1 : height;
                        String cur = c.get(bx, fy, bz);
                        if (c.isAir(bx, fy, bz) || BlockInfo.of(cur).liquid() || !BlockInfo.of(cur).cube) c.set(bx, fy, bz, floor.at(bx, fy, bz));
                        else if (Builders.natural(cur)) c.set(bx, fy, bz, floor.at(bx, fy, bz));
                        for (int y = fy + 1; y <= fy + top; y++) c.set(bx, y, bz, "air");
                        if (dx == 0 && dz == 0) out.add(new int[]{bx, fy, bz});
                    }
            }
        }
        return out;
    }

    /** A hollow dome-shaped room with a flat floor at {@code floorY}. */
    static void chamber(VoxelCanvas c, int cx, int floorY, int cz, double rx, double rz, int height, Fill floor) {
        int ix = (int) Math.ceil(rx), iz = (int) Math.ceil(rz);
        for (int dz = -iz; dz <= iz; dz++)
            for (int dx = -ix; dx <= ix; dx++) {
                double e = (dx * dx) / (rx * rx) + (dz * dz) / (rz * rz);
                if (e > 1) continue;
                int top = (int) Math.round(height * Math.sqrt(1 - e * 0.85));
                int x = cx + dx, z = cz + dz;
                c.set(x, floorY, z, floor.at(x, floorY, z));
                for (int y = floorY + 1; y <= floorY + Math.max(3, top); y++) c.set(x, y, z, "air");
            }
    }

    /**
     * A wading shelf in front of a fishing spot on a bank, applied to a water-depth map before the
     * heightmap is cut ({@code depth[iz * h.width + ix]}, 0 = dry). Every wet column within
     * {@code flat} blocks of (cx, cz) becomes 1 deep - a player standing in it keeps his head out of
     * the water - and beyond that the bed falls {@code fall} blocks per block back to the pool's own
     * depth (a shelf edge, so the deep water stays where it was). Playtest (wave 5): the Torrent
     * Springs spot was a one-block bank over a 4-deep ring, a sea creature's knockback put the player
     * on the bottom and a TestPilot client, which never swims up, drowned there three times. A sea
     * creature now fights you on the shelf. Only lowers depths, never makes water.
     */
    static void wadingShelf(Terrain.Heightmap h, int[] depth, int cx, int cz, double flat, int fall) {
        int r = (int) Math.ceil(flat) + 30;
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++) {
                int ix = x - h.minX, iz = z - h.minZ;
                if (ix < 0 || iz < 0 || ix >= h.width || iz >= h.depth) continue;
                int i = iz * h.width + ix;
                if (depth[i] <= 0) continue;
                double d = Math.hypot(x - cx, z - cz);
                int cap = d <= flat ? 1 : 1 + fall * (int) Math.ceil(d - flat);
                if (depth[i] > cap) depth[i] = cap;
            }
    }

    /**
     * The way back out of a {@link #wadingShelf}: every shelf column within {@code flat} of (cx, cz)
     * that touches dry land becomes a dry step ({@code depth} 0, {@code step} true). The generator
     * sets those columns' top to the water-surface block and keeps its bank-raising pass off them, so
     * shelf bed -> step -> bank is two one-block steps instead of a two-block lip a walker cannot
     * climb without swimming.
     */
    static void beachStep(Terrain.Heightmap h, int[] depth, boolean[] step, int cx, int cz, double flat) {
        int r = (int) Math.ceil(flat);
        List<Integer> mark = new ArrayList<>();
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++) {
                if (Math.hypot(x - cx, z - cz) > flat) continue;
                int i = idx(h, x, z);
                if (i < 0 || depth[i] <= 0) continue;
                for (int d = 0; d < 4; d++) {
                    int n = idx(h, x + Blocks.DX[d], z + Blocks.DZ[d]);
                    if (n >= 0 && depth[n] <= 0 && h.top(x + Blocks.DX[d], z + Blocks.DZ[d]) != Terrain.Heightmap.NONE) { mark.add(i); break; }
                }
            }
        for (int i : mark) { depth[i] = 0; step[i] = true; }
    }

    /**
     * After the bank pass: a dry column touching a {@link #beachStep} that stands one block too high
     * (top {@code bank + 1}) comes down to {@code bank}, so the step is never a two-block climb from
     * the land side either. Lowers by one block only, so it cannot cut a hole in a hillside.
     */
    static void easeAroundSteps(Terrain.Heightmap h, boolean[] step, int bank) {
        List<int[]> lower = new ArrayList<>();
        h.forEach((x, z, top, bottom) -> {
            int i = idx(h, x, z);
            if (step[i] || top != bank + 1) return;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int n = idx(h, x + dx, z + dz);
                if (n >= 0 && step[n]) { lower.add(new int[]{x, z}); return; }
            }
        });
        for (int[] p : lower) h.setTop(p[0], p[1], bank);
    }

    private static int idx(Terrain.Heightmap h, int x, int z) {
        int ix = x - h.minX, iz = z - h.minZ;
        return ix < 0 || iz < 0 || ix >= h.width || iz >= h.depth ? -1 : iz * h.width + ix;
    }

    /** First dry, gentle land column near (x, z), searching outward; {x, groundY, z} or null. */
    static int[] groundNear(Terrain.Heightmap h, int x, int z, int maxR, int margin) {
        for (int r = 0; r <= maxR; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int xx = x + dx, zz = z + dz;
                    if (!h.has(xx, zz) || h.wet(xx, zz) || !h.inland(xx, zz, margin) || h.slope(xx, zz) > 1) continue;
                    return new int[]{xx, h.top(xx, zz), zz};
                }
        return null;
    }

    /**
     * A portal arch on ground level {@code y}: the opening is 3 wide and 4 tall, in the plane
     * x = const ({@code alongZ}) or z = const, centred on (cx, cz). Registers {@code portal.<warp>}.
     */
    static void portalArch(VoxelCanvas c, Layout layout, String warp, int cx, int y, int cz, boolean alongZ, Fill frame, String banner) {
        for (int k = -2; k <= 2; k++) {
            int x = alongZ ? cx : cx + k, z = alongZ ? cz + k : cz;
            for (int h = 1; h <= 5; h++) {
                boolean edge = Math.abs(k) == 2 || h == 5;
                c.set(x, y + h, z, edge ? frame.at(x, y + h, z) : (alongZ ? "nether_portal[axis=z]" : "nether_portal[axis=x]"));
            }
            c.set(x, y, z, frame.at(x, y, z));
        }
        c.set(cx, y + 6, cz, "stone_brick_wall");
        c.set(cx, y + 7, cz, Blocks.lantern(false));
        if (banner != null) {
            String face = alongZ ? "east" : "south";
            int bx = alongZ ? cx + 1 : cx - 2, bz = alongZ ? cz - 2 : cz + 1;
            int bx2 = alongZ ? cx + 1 : cx + 2, bz2 = alongZ ? cz + 2 : cz + 1;
            Builders.wallBanner(c, bx, y + 4, bz, banner, face);
            Builders.wallBanner(c, bx2, y + 4, bz2, banner, face);
        }
        Box b = alongZ ? Box.of(cx, y + 1, cz - 1, cx, y + 4, cz + 1) : Box.of(cx - 1, y + 1, cz, cx + 1, y + 4, cz);
        layout.area("portal." + warp, b);
    }

    /** An open-sided pavilion on ground block (x, y, z): four posts, a slab roof ring and a lantern. */
    static int[] gazebo(VoxelCanvas c, int x, int y, int z, String wood, Fill floor) {
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                if (Math.abs(dx) == 3 && Math.abs(dz) == 3) continue;
                c.set(x + dx, y, z + dz, floor.at(x + dx, y, z + dz));
                Builders.footing(c, x + dx, y, z + dz, Fill.of(wood + "_planks"), 6);
                for (int a = 1; a <= 5; a++) if (Builders.natural(c.get(x + dx, y + a, z + dz))) c.set(x + dx, y + a, z + dz, "air");
            }
        for (int[] p : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}})
            for (int h = 1; h <= 3; h++) c.set(x + p[0], y + h, z + p[1], wood + "_fence");
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                int m = Math.max(Math.abs(dx), Math.abs(dz));
                if (m == 3) c.set(x + dx, y + 4, z + dz, Blocks.stairs(wood, dz == -3 ? "south" : dz == 3 ? "north" : dx == -3 ? "east" : "west"));
                else if (m == 2) c.set(x + dx, y + 4, z + dz, wood + "_planks");
                else c.set(x + dx, y + 5, z + dz, Blocks.slab(wood, "bottom"));
            }
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) c.set(x + dx, y + 4, z + dz, wood + "_planks");
        c.set(x, y + 3, z, Blocks.lantern(true));
        return new int[]{x + 1, y, z + 1};
    }

    /** Lantern on a short post, standing on ground block (x, y, z). */
    static void postLantern(VoxelCanvas c, int x, int y, int z, String fence, boolean soul) {
        c.set(x, y + 1, z, fence);
        c.set(x, y + 2, z, fence);
        c.set(x, y + 3, z, soul ? Blocks.soulLantern(false) : Blocks.lantern(false));
    }

    static Palette p(String spec) { return Palette.of(spec); }
}
