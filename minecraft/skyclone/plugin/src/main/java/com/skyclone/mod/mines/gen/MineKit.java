package com.skyclone.mod.mines.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * Mine-flavoured building pieces shared by the three mines generators: timber support frames,
 * hanging lanterns, rail runs, crates and TNT piles, dripstone, framed portals and the fairy-soul
 * bookkeeping. Pure Java (no Bukkit), like everything a generator touches.
 */
public final class MineKit {

    private MineKit() {}

    /** A solid, full-topped block you can stand on (not air, plants, liquids or slabs). */
    public static boolean solid(VoxelCanvas c, int x, int y, int z) {
        BlockInfo i = BlockInfo.of(c.get(x, y, z));
        return !i.isAir() && !i.liquid() && i.top16 >= 15;
    }

    /** A chain hanging from the ceiling block above {@code ceilY-1} with a lantern at the end. */
    public static void hangLantern(VoxelCanvas c, int x, int ceilY, int z, int len, boolean soul) {
        if (!c.isAir(x, ceilY - 1, z)) return;
        int y = ceilY - 1;
        for (int i = 0; i < len - 1 && c.isAir(x, y - 1, z); i++, y--) c.set(x, y, z, "iron_chain[axis=y]");
        c.set(x, y, z, soul ? Blocks.soulLantern(true) : Blocks.lantern(true));
    }

    /**
     * A mineshaft support: two log posts {@code half} blocks either side of (x,z) across the tunnel,
     * {@code h} tall, joined by a beam on top. {@code along} is the tunnel's axis ('x' or 'z').
     * {@code y} is the floor block; posts only stand where there is floor under them.
     */
    public static void supportFrame(VoxelCanvas c, int x, int y, int z, char along, int half, int h, String log) {
        int px = along == 'x' ? 0 : 1, pz = along == 'x' ? 1 : 0;
        char beamAxis = along == 'x' ? 'z' : 'x';
        for (int s : new int[]{-half, half}) {
            int bx = x + px * s, bz = z + pz * s;
            int fy = floorBelow(c, bx, y + 1, bz, 4);
            if (fy == Integer.MIN_VALUE) continue;
            for (int yy = fy + 1; yy <= y + h; yy++) if (c.isAir(bx, yy, bz)) c.set(bx, yy, bz, log + "[axis=y]");
        }
        for (int s = -half; s <= half; s++) {
            int bx = x + px * s, bz = z + pz * s;
            if (c.isAir(bx, y + h + 1, bz) || isSoft(c.get(bx, y + h + 1, bz))) c.set(bx, y + h + 1, bz, log + "[axis=" + beamAxis + "]");
        }
    }

    private static boolean isSoft(String b) { return b.startsWith("iron_chain") || b.startsWith("lantern") || b.startsWith("pointed_dripstone"); }

    /** Highest solid block at or below {@code fromY} (looking at most {@code max} down), or MIN_VALUE. */
    public static int floorBelow(VoxelCanvas c, int x, int fromY, int z, int max) {
        for (int y = fromY; y >= fromY - max; y--) if (solid(c, x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    /** First non-air block at or above {@code fromY} (looking at most {@code max} up), or MIN_VALUE. */
    public static int ceilAbove(VoxelCanvas c, int x, int fromY, int z, int max) {
        for (int y = fromY; y <= fromY + max; y++) if (!c.isAir(x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    /**
     * Straight rails from (x1,z1) to (x2,z2) along one axis, laid on the floor reported by
     * {@code floorAt}. Rails only go on flat runs (they skip a cell whose floor differs from the
     * previous one by more than one) and only into air.
     */
    public static int railRun(VoxelCanvas c, int x1, int z1, int x2, int z2, IntBinaryOperator floorAt) {
        boolean alongX = z1 == z2;
        String shape = alongX ? "east_west" : "north_south";
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        int sx = Integer.signum(x2 - x1), sz = Integer.signum(z2 - z1);
        int placed = 0, last = Integer.MIN_VALUE;
        for (int i = 0; i <= n; i++) {
            int x = x1 + sx * i, z = z1 + sz * i;
            int f = floorAt.applyAsInt(x, z);
            if (f == Integer.MIN_VALUE) { last = Integer.MIN_VALUE; continue; }
            if (last != Integer.MIN_VALUE && Math.abs(f - last) > 0) { last = f; continue; }
            last = f;
            if (!solid(c, x, f, z) || !c.isAir(x, f + 1, z)) continue;
            c.set(x, f + 1, z, (i % 9 == 4 ? "powered_rail[powered=false,shape=" : "rail[shape=") + shape + "]");
            placed++;
        }
        return placed;
    }

    /** Barrels, crates (planks + trapdoor lids) and a sack of gravel on floor block (x,y,z). */
    public static void crates(VoxelCanvas c, int x, int y, int z, long seed) {
        Palette p = Palette.of("barrel[facing=up,open=false]:3,spruce_planks:2,stripped_spruce_log[axis=y]:1,composter:1");
        int n = 2 + (int) (Hash.unit(seed, x, y, z) * 3);
        int[][] off = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 0}};
        for (int i = 0; i < n; i++) {
            int bx = x + off[i][0], bz = z + off[i][1];
            if (!solid(c, bx, y, bz) || !c.isAir(bx, y + 1, bz)) continue;
            c.set(bx, y + 1, bz, p.pick(seed + i, bx, y, bz));
            if (i == 0 && c.isAir(bx, y + 2, bz)) c.set(bx, y + 2, bz, "barrel[facing=up,open=false]");
        }
    }

    /** A little stack of TNT with a spruce lid (Gunpowder Mines flavour). */
    public static void tntPile(VoxelCanvas c, int x, int y, int z, long seed) {
        int[][] off = {{0, 0}, {1, 0}, {0, 1}};
        for (int[] o : off) {
            int bx = x + o[0], bz = z + o[1];
            if (!solid(c, bx, y, bz) || !c.isAir(bx, y + 1, bz)) continue;
            c.set(bx, y + 1, bz, "tnt");
        }
        if (c.isAir(x, y + 2, z) && "tnt".equals(c.get(x, y + 1, z))) c.set(x, y + 2, z, Blocks.slab("spruce", "bottom"));
    }

    /** Hanging dripstone (and a few stone "teeth") from the ceiling of a cave, in {@code area}. */
    public static void stalactites(VoxelCanvas c, CaveGrid g, Box area, long seed, double chance, String tooth) {
        long s = Hash.salt(seed, "stalactite");
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!g.open(x, z) || !Hash.chance(s, x, z, chance)) continue;
                int cy = g.ceil(x, z), fy = g.floor(x, z);
                if (cy - fy < 7) continue;
                int len = 1 + (int) (Hash.unit(s, x, 3, z) * Math.min(5, (cy - fy) / 3));
                if (tooth != null && Hash.unit(s, x, 9, z) < 0.5) {
                    for (int i = 1; i <= len && c.isAir(x, cy - i, z); i++) c.set(x, cy - i, z, tooth);
                } else {
                    for (int i = 1; i <= len && c.isAir(x, cy - i, z); i++) {
                        String th = i == len ? "tip" : i == len - 1 ? "frustum" : "middle";
                        c.set(x, cy - i, z, "pointed_dripstone[thickness=" + th + ",vertical_direction=down,waterlogged=false]");
                    }
                }
            }
    }

    /** Dripstone/stone stumps rising from the floor (never on paths: pass a keep-out). */
    public static void stalagmites(VoxelCanvas c, CaveGrid g, Box area, long seed, double chance, String block, java.util.function.BiPredicate<Integer, Integer> keepOut) {
        long s = Hash.salt(seed, "stalagmite");
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!g.open(x, z) || !Hash.chance(s, x, z, chance)) continue;
                if (keepOut != null && keepOut.test(x, z)) continue;
                int fy = g.floor(x, z), cy = g.ceil(x, z);
                if (cy - fy < 6 || !solid(c, x, fy, z) || !c.isAir(x, fy + 1, z)) continue;
                int len = 1 + (int) (Hash.unit(s, x, 5, z) * 3);
                for (int i = 1; i <= len && c.isAir(x, fy + i, z) && fy + i < cy - 3; i++) {
                    if (block != null) { c.set(x, fy + i, z, block); continue; }
                    String th = i == len ? "tip" : i == len - 1 ? "frustum" : "middle";
                    c.set(x, fy + i, z, "pointed_dripstone[thickness=" + th + ",vertical_direction=up,waterlogged=false]");
                }
            }
    }

    /**
     * Put a stair on every one-block rise inside {@code area} where both cells are "road" (as told
     * by {@code road}), so a ramp reads as a stair-cut haul road instead of a heap of steps.
     * {@code heightAt} gives the floor block y. Facing = the direction you walk to go up.
     */
    public static void stepify(VoxelCanvas c, Box area, IntBinaryOperator heightAt, java.util.function.BiPredicate<Integer, Integer> road, String stairs) {
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!road.test(x, z)) continue;
                int h = heightAt.applyAsInt(x, z);
                if (h == Integer.MIN_VALUE) continue;
                for (int d = 0; d < 4; d++) {
                    int nx = x + Blocks.DX[d], nz = z + Blocks.DZ[d];
                    if (!road.test(nx, nz)) continue;
                    int nh = heightAt.applyAsInt(nx, nz);
                    if (nh != h + 1) continue;
                    // (x,z) is one lower than its neighbour: a stair here facing the neighbour
                    if (c.isAir(x, h + 1, z) && c.isAir(x, h + 2, z) && solid(c, x, h, z)) {
                        c.set(x, h + 1, z, Blocks.stairs(stairs, Blocks.DIRS[d]));
                        break;
                    }
                }
            }
    }

    /**
     * A framed walk-in portal: an opening {@code width} x {@code height} whose plane runs along
     * {@code axis} ('x' = players walk through it north/south), standing on floor block y, with a
     * frame drawn from {@code frame}. Registers area {@code portal.<warpId>} over the opening.
     */
    public static Box portal(VoxelCanvas c, Layout layout, String warpId, int x, int y, int z, char axis, int width, int height, Fill frame, String fill) {
        int ux = axis == 'x' ? 1 : 0, uz = axis == 'x' ? 0 : 1;
        int half = width / 2;
        for (int u = -half - 1; u <= half + 1; u++)
            for (int h = 0; h <= height + 1; h++) {
                int bx = x + ux * u, bz = z + uz * u, by = y + h;
                boolean edge = u == -half - 1 || u == half + 1 || h == 0 || h == height + 1;
                if (edge) c.set(bx, by, bz, frame.at(bx, by, bz));
                else c.set(bx, by, bz, fill != null ? fill : "nether_portal[axis=" + axis + "]");
            }
        // floor under the frame continues the walkway on both sides
        for (int u = -half; u <= half; u++)
            for (int s = -1; s <= 1; s += 2) {
                int bx = x + ux * u + uz * s, bz = z + uz * u + ux * s;
                if (!solid(c, bx, y, bz)) c.set(bx, y, bz, frame.at(bx, y, bz));
            }
        Box b = Box.of(x - ux * half, y + 1, z - uz * half, x + ux * half, y + height, z + uz * half);
        layout.area("portal." + warpId, b);
        return b;
    }

    /**
     * A timber work platform on floor block (x,y,z): a 3x3 plank deck {@code h} up on four log
     * posts, with a ladder up the west side and a lantern. Returns the deck block (stand on it).
     */
    public static int[] platform(VoxelCanvas c, int x, int y, int z, int h, String wood) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    int fy = floorBelow(c, x + dx, y, z + dz, 3);
                    if (fy == Integer.MIN_VALUE) fy = y;
                    for (int yy = fy + 1; yy < y + h; yy++) c.set(x + dx, yy, z + dz, wood + "_log[axis=y]");
                } else if (dx == 0 && dz == 0) {
                    for (int yy = y + 1; yy < y + h - 1; yy++) if (c.isAir(x, yy, z)) c.set(x, yy, z, "air");
                }
                c.set(x + dx, y + h, z + dz, wood + "_planks");
            }
        for (int yy = y + 1; yy <= y + h; yy++) c.set(x - 2, yy, z + 1, ladder("west"));
        if (!solid(c, x - 2, y, z + 1)) c.set(x - 2, y, z + 1, wood + "_planks");
        c.set(x + 1, y + h + 1, z - 1, Blocks.lantern(false));
        c.set(x - 1, y + h + 1, z - 1, wood + "_fence");
        c.set(x + 1, y + h + 1, z + 1, "barrel[facing=up,open=false]");
        return new int[]{x, y + h, z};
    }

    private static String ladder(String facing) { return Blocks.ladder(facing); }

    /** A rectangular loop of rails at floor y around (cx,cz), half-size {@code r}, curved corners. */
    public static void railLoop(VoxelCanvas c, int cx, int y, int cz, int r) {
        for (int i = -r + 1; i <= r - 1; i++) {
            putRail(c, cx + i, y, cz - r, "east_west");
            putRail(c, cx + i, y, cz + r, "east_west");
            putRail(c, cx - r, y, cz + i, "north_south");
            putRail(c, cx + r, y, cz + i, "north_south");
        }
        putRail(c, cx + r, y, cz + r, "north_west");
        putRail(c, cx + r, y, cz - r, "south_west");
        putRail(c, cx - r, y, cz - r, "south_east");
        putRail(c, cx - r, y, cz + r, "north_east");
    }

    private static void putRail(VoxelCanvas c, int x, int y, int z, String shape) {
        if (!solid(c, x, y, z)) c.set(x, y, z, "polished_andesite");
        c.set(x, y + 1, z, "rail[shape=" + shape + "]");
        if (!c.isAir(x, y + 2, z)) c.set(x, y + 2, z, "air");
    }

    /** Clear a box to air (carving pass). */
    public static void air(VoxelCanvas c, Box b) { c.fill(b, "air"); }

    // ---- fairy souls -------------------------------------------------------------------------

    /** Collects fairy soul spots (ground blocks) and names them fairy_soul.1..N at the end. */
    public static final class Souls {
        private final List<int[]> spots = new ArrayList<>();
        public void add(int x, int groundY, int z) {
            for (int[] s : spots) if (Math.abs(s[0] - x) < 4 && Math.abs(s[2] - z) < 4 && Math.abs(s[1] - groundY) < 4) return;
            spots.add(new int[]{x, groundY, z});
        }
        public int size() { return spots.size(); }
        public void write(VoxelCanvas c, Layout layout) { write(c, layout, Integer.MAX_VALUE); }

        /**
         * Name the valid spots fairy_soul.1..N, keeping at most {@code max} (wave 5: the reference's exact count per
         * island). Extra spots are dropped crowded-first (the one closest to another soul goes), so the survivors stay
         * spread over the whole island. Deterministic.
         */
        public void write(VoxelCanvas c, Layout layout, int max) {
            List<int[]> ok = new ArrayList<>();
            for (int[] s : spots)
                if (solid(c, s[0], s[1], s[2]) && c.isAir(s[0], s[1] + 1, s[2]) && c.isAir(s[0], s[1] + 2, s[2])) ok.add(s);
            while (ok.size() > max) {
                int worst = -1;
                double worstD = Double.MAX_VALUE;
                for (int i = 0; i < ok.size(); i++) {
                    double nd = Double.MAX_VALUE;
                    for (int j = 0; j < ok.size(); j++) {
                        if (i == j) continue;
                        int[] a = ok.get(i), b = ok.get(j);
                        nd = Math.min(nd, Math.hypot(Math.hypot(a[0] - b[0], a[2] - b[2]), a[1] - b[1]));
                    }
                    if (nd <= worstD) { worstD = nd; worst = i; }
                }
                ok.remove(worst);
            }
            int n = 0;
            for (int[] s : ok) layout.standAt("fairy_soul." + (++n), s[0], s[1], s[2], 0f);
        }
    }
}
