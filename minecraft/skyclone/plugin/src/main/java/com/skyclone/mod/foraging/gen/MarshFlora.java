package com.skyclone.mod.foraging.gen;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

import java.util.Random;

/**
 * The Moonmarsh's three harvestable giants (pure). Their trunks are the blocks the foraging module
 * reads as Moonmarsh wood: fig = stripped spruce, mangrove = mangrove log, spiral = stripped mangrove.
 * Every log touches the previous one by a face, so a tree is one connected piece.
 */
public final class MarshFlora {

    private MarshFlora() {}

    public static final String FIG = "stripped_spruce_log", FIG_WOOD = "stripped_spruce_wood";
    public static final String MANGROVE = "mangrove_log";
    public static final String SPIRAL = "stripped_mangrove_log";
    /** The spiral's pale corkscrew: cut first (beige -> red, research 07 §4.4). */
    public static final String SPIRAL_BARK = "stripped_birch_log";

    static void log(VoxelCanvas c, int x, int y, int z, String id, char axis) {
        String cur = c.get(x, y, z);
        BlockInfo i = BlockInfo.of(cur);
        String n = BlockState.name(cur);
        if (i.isAir() || i.top16 == 0 || i.liquid() || n.endsWith("_leaves") || n.equals("mangrove_roots"))
            c.set(x, y, z, id + "[axis=" + axis + "]");
    }

    static void leaf(VoxelCanvas c, int x, int y, int z, String leaves) {
        if (c.isAir(x, y, z)) c.set(x, y, z, leaves);
    }

    /** Logs from a to b, one axis at a time (vertical first) so every block shares a face. */
    static void connect(VoxelCanvas c, int ax, int ay, int az, int bx, int by, int bz, String id) {
        int x = ax, y = ay, z = az;
        while (y != by) { y += Integer.signum(by - y); log(c, x, y, z, id, 'y'); }
        while (x != bx) { x += Integer.signum(bx - x); log(c, x, y, z, id, 'x'); }
        while (z != bz) { z += Integer.signum(bz - z); log(c, x, y, z, id, 'z'); }
    }

    static void blob(VoxelCanvas c, double cx, double cy, double cz, double rx, double ry, double rz, Palette leaves, long seed) {
        for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++)
            for (int z = (int) Math.floor(cz - rz); z <= (int) Math.ceil(cz + rz); z++)
                for (int x = (int) Math.floor(cx - rx); x <= (int) Math.ceil(cx + rx); x++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 1) continue;
                    if (d > 0.6 && Hash.unit(seed, x, y, z) < (d - 0.6) * 1.8) continue;
                    leaf(c, x, y, z, leaves.at(x, y, z));
                }
    }

    /** A broad fig: 2x2 trunk, buttress roots, three or four limbs under a wide canopy. ~60-110 logs. */
    public static void fig(VoxelCanvas c, int x, int y, int z, long seed) {
        Random r = new Random(Hash.hash(seed, x, y, z));
        Palette leaves = Palette.of("azalea_leaves[persistent=true]:5,flowering_azalea_leaves[persistent=true]:1,jungle_leaves[persistent=true]:2");
        int h = 9 + r.nextInt(5);
        for (int i = 1; i <= h; i++)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) log(c, x + dx, y + i, z + dz, FIG, 'y');
        // buttress roots along the ground
        int[][] feet = {{-1, 0}, {2, 1}, {0, 2}, {1, -1}};
        for (int[] f : feet) {
            log(c, x + f[0], y + 1, z + f[1], FIG_WOOD, 'y');
            if (r.nextBoolean()) log(c, x + f[0], y + 2, z + f[1], FIG_WOOD, 'y');
        }
        int limbs = 3 + r.nextInt(2);
        for (int k = 0; k < limbs; k++) {
            double a = (k + r.nextDouble() * 0.6) * Math.PI * 2 / limbs;
            int len = 4 + r.nextInt(3);
            int sy = y + h - 3 + r.nextInt(3);
            int sx = x + (Math.cos(a) > 0 ? 1 : 0), sz = z + (Math.sin(a) > 0 ? 1 : 0);
            int ex = sx + (int) Math.round(Math.cos(a) * len), ez = sz + (int) Math.round(Math.sin(a) * len), ey = sy + 2 + r.nextInt(2);
            connect(c, sx, sy, sz, ex, ey, ez, FIG);
            blob(c, ex, ey + 1.5, ez, 3.4, 2.2, 3.4, leaves, seed + k);
        }
        blob(c, x + 0.5, y + h + 2, z + 0.5, 4.6, 2.8, 4.6, leaves, seed ^ 0x55);
    }

    /** A mangrove on stilt roots, rising out of shallow water or mud. ~20-40 logs. */
    public static void mangrove(VoxelCanvas c, int x, int y, int z, long seed) {
        Random r = new Random(Hash.hash(seed, x, y, z));
        Palette leaves = Palette.of("mangrove_leaves[persistent=true,waterlogged=false]:6");
        int base = y + 3;
        // stilt roots: a ring of root legs from the bed up to under the trunk
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int ad = Math.abs(dx) + Math.abs(dz);
                if (ad > 3 || (ad == 3 && r.nextBoolean())) continue;
                int top = ad <= 1 ? base - 1 : base - 2;
                for (int yy = y + 1; yy <= top; yy++) {
                    String cur = c.get(x + dx, yy, z + dz);
                    boolean wet = BlockState.name(cur).equals("water");
                    if (c.isAir(x + dx, yy, z + dz) || wet)
                        c.set(x + dx, yy, z + dz, wet ? "mangrove_roots[waterlogged=true]" : "mangrove_roots[waterlogged=false]");
                }
            }
        int h = 6 + r.nextInt(4);
        for (int i = 0; i < h; i++) log(c, x, base + i, z, MANGROVE, 'y');
        int top = base + h - 1;
        for (int k = 0; k < 2 + r.nextInt(2); k++) {
            String[] dirs = {"x+", "x-", "z+", "z-"};
            String d = dirs[r.nextInt(4)];
            int bx = x, bz = z, by = top - 1 - r.nextInt(2);
            int len = 2 + r.nextInt(2);
            int ex = bx + (d.equals("x+") ? len : d.equals("x-") ? -len : 0), ez = bz + (d.equals("z+") ? len : d.equals("z-") ? -len : 0);
            connect(c, bx, by, bz, ex, by + 1, ez, MANGROVE);
            blob(c, ex, by + 2.5, ez, 2.6, 1.8, 2.6, leaves, seed + k);
        }
        blob(c, x, top + 2, z, 3.8, 2.4, 3.8, leaves, seed ^ 0x77);
        for (int i = 0; i < 6; i++) {
            int px = x + r.nextInt(7) - 3, pz = z + r.nextInt(7) - 3;
            for (int py = top + 1; py >= top - 2; py--)
                if (BlockState.name(c.get(px, py, pz)).equals("mangrove_leaves") && c.isAir(px, py - 1, pz)) {
                    c.set(px, py - 1, pz, "mangrove_propagule[hanging=true,age=4,stage=0,waterlogged=false]");
                    break;
                }
        }
    }

    /** A spiral tree: a corkscrew of pale wood around a core, under a pink crown. ~35-55 logs. */
    public static void spiral(VoxelCanvas c, int x, int y, int z, long seed) {
        Random r = new Random(Hash.hash(seed, x, y, z));
        Palette leaves = Palette.of("cherry_leaves[persistent=true]:5,flowering_azalea_leaves[persistent=true]:1");
        int h = 12 + r.nextInt(6);
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, SPIRAL, 'y');
        double phase = r.nextDouble() * Math.PI * 2;
        int px = x, py = y + 1, pz = z;
        for (int i = 1; i <= h + 1; i++) {
            double a = phase + i * 0.55;
            int nx = x + (int) Math.round(Math.cos(a) * 1.6), nz = z + (int) Math.round(Math.sin(a) * 1.6), ny = y + i;
            connect(c, px, py, pz, nx, ny, nz, SPIRAL_BARK);
            px = nx; py = ny; pz = nz;
        }
        blob(c, x, y + h + 2.5, z, 4.2, 2.6, 4.2, leaves, seed);
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++) {
                if (dx * dx + dz * dz > 16 || Hash.unit(seed, x + dx, 9, z + dz) > 0.35) continue;
                if (c.isAir(x + dx, y + 1, z + dz) && BlockInfo.of(c.get(x + dx, y, z + dz)).top16 >= 15)
                    c.set(x + dx, y + 1, z + dz, "pink_petals[facing=north,flower_amount=" + (1 + (int) (Hash.unit(seed, x + dx, 3, z + dz) * 4)) + "]");
            }
    }
}
