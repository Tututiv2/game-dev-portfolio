package com.skyclone.mod.combatzones.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Small pure helpers shared by the two combat-zone generators (no Bukkit anywhere): road
 * carving into a heightmap before the terrain is written, framed portals, tunnels and
 * caverns carved into solid rock, and spawn-point scattering.
 */
public final class Kit {

    private Kit() {}

    /** Ground block y at (x,z) searching from {@code from} down, or {@code fallback}. */
    public static int ground(VoxelCanvas c, int x, int z, int from, int fallback) {
        int g = Builders.ground(c, x, z, from, 0);
        return g == Integer.MIN_VALUE ? fallback : g;
    }

    /** One road sample: position on the plan and the height the road should sit at. */
    public record RoadPoint(double x, double z, double y) {}

    /**
     * Press a road into the heightmap: columns within {@code halfWidth} take the road's height,
     * columns within a further {@code blend} blocks ease back to the natural ground. Run this
     * BEFORE {@code Terrain.fill} so the terrain is written already graded.
     */
    public static void gradeRoad(Terrain.Heightmap h, List<RoadPoint> pts, double halfWidth, double blend) {
        int r = (int) Math.ceil(halfWidth + blend) + 1;
        java.util.Map<Long, double[]> best = new java.util.HashMap<>();
        for (RoadPoint p : pts) {
            int px = (int) Math.floor(p.x() + 0.5), pz = (int) Math.floor(p.z() + 0.5);
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    int x = px + dx, z = pz + dz;
                    if (!h.has(x, z)) continue;
                    double d = Math.hypot(x - p.x(), z - p.z());
                    double w = d <= halfWidth ? 1.0 : 1.0 - Noise.smoothstep(halfWidth, halfWidth + blend, d);
                    if (w <= 0) continue;
                    long k = ((long) x << 32) ^ (z & 0xffffffffL);
                    double[] cur = best.get(k);
                    if (cur == null || w > cur[0] || (w == cur[0] && d < cur[2])) best.put(k, new double[]{w, p.y(), d});
                }
        }
        for (var e : best.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey().longValue();
            double[] v = e.getValue();
            int orig = h.top(x, z);
            h.setTop(x, z, (int) Math.round(orig + (v[1] - orig) * v[0]));
        }
    }

    /** Polyline points for a path (x,z) from road samples, every {@code step} samples. */
    public static int[][] polyline(List<RoadPoint> pts, int step) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += step) out.add(new int[]{(int) Math.round(pts.get(i).x()), (int) Math.round(pts.get(i).z())});
        RoadPoint last = pts.get(pts.size() - 1);
        out.add(new int[]{(int) Math.round(last.x()), (int) Math.round(last.z())});
        return out.toArray(new int[0][]);
    }

    /** Add a smooth bump (positive) or dip (negative) of {@code amount} blocks with radius {@code r}. */
    public static void bump(Terrain.Heightmap h, double cx, double cz, double r, double amount) {
        int ri = (int) Math.ceil(r);
        for (int z = (int) cz - ri; z <= (int) cz + ri; z++)
            for (int x = (int) cx - ri; x <= (int) cx + ri; x++) {
                if (!h.has(x, z)) continue;
                double d = Math.hypot(x - cx, z - cz) / r;
                if (d >= 1) continue;
                double f = 1 - Noise.smoothstep(0, 1, d);
                h.setTop(x, z, (int) Math.round(h.top(x, z) + amount * f));
            }
    }

    /**
     * A framed portal: a {@code w}-wide, 4-tall opening of {@code inner} in a frame of {@code frame},
     * standing on ground level {@code y} (the frame's feet are at y+1), across the X axis when
     * {@code alongX} (the player walks through it north/south), else across Z. Registers the
     * area {@code name}. Returns the box of the opening.
     */
    public static Box portal(VoxelCanvas c, Layout layout, String name, int cx, int y, int cz, boolean alongX, int w,
                             Fill frame, String inner, Fill base) {
        int half = w / 2;
        int ux = alongX ? 1 : 0, uz = alongX ? 0 : 1;
        int fx = alongX ? 0 : 1, fz = alongX ? 1 : 0;
        // base pad (5 deep) so the frame never floats and you can walk up to it
        for (int a = -half - 2; a <= half + 2; a++)
            for (int b = -2; b <= 2; b++) {
                int x = cx + ux * a + fx * b, z = cz + uz * a + fz * b;
                c.set(x, y, z, base.at(x, y, z));
                Builders.footing(c, x, y, z, base, 14);
                for (int k = 1; k <= 6; k++) if (!BlockInfo.of(c.get(x, y + k, z)).isAir() && Builders.natural(c.get(x, y + k, z))) c.set(x, y + k, z, "air");
            }
        for (int a = -half - 1; a <= half + 1; a++)
            for (int k = 1; k <= 5; k++) {
                int x = cx + ux * a, z = cz + uz * a;
                boolean edge = a == -half - 1 || a == half + 1 || k == 5;
                c.set(x, y + k, z, edge ? frame.at(x, y + k, z) : inner + "[axis=" + (alongX ? "x" : "z") + "]");
            }
        // corner caps
        for (int a : new int[]{-half - 1, half + 1}) {
            int x = cx + ux * a, z = cz + uz * a;
            c.set(x, y + 6, z, frame.at(x, y + 6, z));
        }
        Box box = Box.of(cx - ux * half, y + 1, cz - uz * half, cx + ux * half, y + 4, cz + uz * half);
        layout.area(name, box);
        return box;
    }

    /**
     * Carve a walkable tunnel along {x, floorY, z} points: {@code half} blocks either side of the
     * centre line, an arched ceiling {@code height} blocks above the floor, and a floor of
     * {@code floor}. Floors may change one block per block walked.
     */
    public static void tunnel(VoxelCanvas c, double[][] pts, double half, int height, Fill floor) {
        for (int i = 0; i + 1 < pts.length; i++) {
            double[] a = pts[i], b = pts[i + 1];
            double len = Math.hypot(b[0] - a[0], b[2] - a[2]);
            int n = Math.max(1, (int) Math.ceil(len * 2));
            for (int s = 0; s <= n; s++) {
                double t = (double) s / n;
                double x = a[0] + (b[0] - a[0]) * t, z = a[2] + (b[2] - a[2]) * t;
                int fy = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int hr = (int) Math.ceil(half);
                for (int dz = -hr; dz <= hr; dz++)
                    for (int dx = -hr; dx <= hr; dx++) {
                        double d = Math.hypot(dx, dz);
                        if (d > half + 0.3) continue;
                        int bx = (int) Math.floor(x + 0.5) + dx, bz = (int) Math.floor(z + 0.5) + dz;
                        int hh = height - (int) Math.round(Math.max(0, d - half * 0.45) * 0.9);
                        c.set(bx, fy, bz, floor.at(bx, fy, bz));
                        for (int k = 1; k <= Math.max(3, hh); k++) c.set(bx, fy + k, bz, "air");
                    }
            }
        }
    }

    /** A domed cavern carved into solid rock: flat floor of {@code floor} at {@code fy}, dome {@code height} high. */
    public static void cavern(VoxelCanvas c, int cx, int fy, int cz, double r, int height, Fill floor, long seed) {
        Noise n = new Noise(Hash.salt(seed, "cavern" + cx + "," + cz));
        int ri = (int) Math.ceil(r * 1.25) + 1;
        for (int z = cz - ri; z <= cz + ri; z++)
            for (int x = cx - ri; x <= cx + ri; x++) {
                double wob = r * (1 + 0.18 * n.noise2(x / 7.0, z / 7.0));
                double d = Math.hypot(x - cx, z - cz) / wob;
                if (d > 1) continue;
                int hh = (int) Math.round(3 + (height - 3) * Math.sqrt(Math.max(0, 1 - d * d)));
                c.set(x, fy, z, floor.at(x, fy, z));
                for (int k = 1; k <= hh; k++) c.set(x, fy + k, z, "air");
            }
    }

    /** Is (x,z) clear for scattering: on land, not near a path, not inside a reservation? */
    public interface Keepout { boolean blocked(int x, int z); }

    /**
     * Deterministically pick up to {@code count} standable ground spots in {@code area} whose
     * ground height lies in [{@code minY}, {@code maxY}], at least {@code spacing} apart.
     * Returns {x, groundY, z}.
     */
    public static List<int[]> spots(VoxelCanvas c, Terrain.Heightmap h, Box area, int minY, int maxY, int count, int spacing,
                                    long seed, Keepout keep) {
        List<int[]> out = new ArrayList<>();
        java.util.Random r = Hash.random(seed, "spots" + area + minY);
        int tries = count * 60;
        while (out.size() < count && tries-- > 0) {
            int x = area.minX() + r.nextInt(area.maxX() - area.minX() + 1);
            int z = area.minZ() + r.nextInt(area.maxZ() - area.minZ() + 1);
            if (!h.has(x, z) || (keep != null && keep.blocked(x, z))) continue;
            int g = Builders.ground(c, x, z, maxY + 12, minY - 12);
            if (g == Integer.MIN_VALUE || g < minY || g > maxY) continue;
            if (!c.isAir(x, g + 1, z) || !c.isAir(x, g + 2, z)) continue;
            boolean close = false;
            for (int[] o : out) if (Math.abs(o[0] - x) < spacing && Math.abs(o[2] - z) < spacing) { close = true; break; }
            if (close) continue;
            out.add(new int[]{x, g, z});
        }
        return out;
    }


    /**
     * A jagged rock spike that never floats: every column inside {@code radius} is built from its
     * own ground up to a height that tapers from {@code topY} at the centre.
     */
    public static void spike(VoxelCanvas c, int cx, int cz, double radius, int topY, Fill f) {
        int ri = (int) Math.ceil(radius);
        int base = Builders.ground(c, cx, cz, topY + 10, topY - 80);
        if (base == Integer.MIN_VALUE) return;
        for (int z = cz - ri; z <= cz + ri; z++)
            for (int x = cx - ri; x <= cx + ri; x++) {
                double d = Math.hypot(x - cx, z - cz) / radius;
                if (d > 1) continue;
                int g = Builders.ground(c, x, z, topY + 10, topY - 80);
                if (g == Integer.MIN_VALUE) continue;
                int t = base + (int) Math.round((topY - base) * Math.pow(1 - d, 1.3));
                for (int y = g + 1; y <= t; y++) c.set(x, y, z, f.at(x, y, z));
            }
    }

    /** Hang a lantern from a short chain under the block at (x, ceilY, z) (the ceiling block itself). */
    public static void hangingLantern(VoxelCanvas c, int x, int ceilY, int z, int chain, boolean soul) {
        for (int k = 1; k <= chain; k++) c.set(x, ceilY - k, z, "iron_chain[axis=y]");
        c.set(x, ceilY - chain - 1, z, (soul ? "soul_lantern" : "lantern") + "[hanging=true,waterlogged=false]");
    }

    /** Remove lone specks: a non-air block with air above, below and on all four sides. */
    public static void despeck(VoxelCanvas c, Box b) {
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    if (c.isAir(x, y, z)) continue;
                    if (c.isAir(x, y - 1, z) && c.isAir(x, y + 1, z) && c.isAir(x + 1, y, z) && c.isAir(x - 1, y, z)
                            && c.isAir(x, y, z + 1) && c.isAir(x, y, z - 1)) {
                        String n = c.get(x, y, z);
                        if (n.startsWith("end_rod") || n.contains("lantern") || n.startsWith("cobweb") || n.startsWith("chorus")) continue;
                        c.set(x, y, z, "air");
                    }
                }
    }

    /** Palette helper. */
    public static Palette p(String spec) { return Palette.of(spec); }
}
