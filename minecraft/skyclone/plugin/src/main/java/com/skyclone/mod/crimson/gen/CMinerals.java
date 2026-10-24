package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * Generator v4: Sulphur Ore (sponge, the reference's look) on the volcano's lower flanks and along the shores of
 * the isle's lava pools - the shore deposits are what the fishing module's "sulphur within a few blocks" trophy
 * fish looks for. The crimson module mines any sponge on the isle into {@code SULPHUR_ORE}.
 * Sponge only ever REPLACES an existing ground block that has air above it, so nothing floats and no surface
 * gains a step.
 */
final class CMinerals {

    private CMinerals() {}

    static final int FLANK_DEPOSITS = 18;

    static void sulphur(CCtx x) {
        VoxelCanvas c = x.c;
        int placed = 0, deposits = 0;
        // ---- flanks: small deposits between the quartz veins ----
        int vx = CPlan.VOLCANO_X, vz = CPlan.VOLCANO_Z;
        long s = Hash.salt(x.seed, "sulphur");
        for (int i = 0; i < 600 && deposits < FLANK_DEPOSITS; i++) {
            double ang = Hash.unit(s, i, 1) * Math.PI * 2;
            double r = 28 + Hash.unit(s, i, 2) * 40;
            int sx = vx + (int) Math.round(Math.cos(ang) * r), sz = vz + (int) Math.round(Math.sin(ang) * r);
            if (x.paths.near(sx, sz, 3) || x.res.covers(sx, sz, 3) || !x.h.has(sx, sz)) continue;
            int n = 0;
            for (int k = 0; k < 3; k++) {
                int dx = k == 0 ? 0 : (int) Math.round(Hash.unit(s, i, 10 + k) * 2 - 1);
                int dz = k == 0 ? 0 : (int) Math.round(Hash.unit(s, i, 20 + k) * 2 - 1);
                if (ore(x, sx + dx, sz + dz)) n++;
            }
            if (n > 0) { deposits++; placed += n; }
        }
        // ---- lava shores: a few ore blocks within reach of each pool's lava ----
        int[][] pools = {{-98, -76, 7}, {-98, 176, 7}, {40, -52, 5}, {-40, 22, 5}};
        for (int[] p : pools) placed += shore(x, p[0], p[1], p[2], 3, Hash.salt(x.seed, "sulphur-shore" + p[0] + "," + p[1]));
        x.layout.data("crimson.sulphur", deposits + "," + placed);
    }

    /** Turn the ground block at (x, z) into sulphur ore when it is plain open ground. */
    private static boolean ore(CCtx x, int bx, int bz) {
        if (!x.h.has(bx, bz) || x.paths.covers(bx, bz)) return false;
        int g = x.ground(bx, bz);
        String b = BlockState.name(x.c.get(bx, g, bz));
        if (!(b.equals("netherrack") || b.equals("blackstone") || b.equals("basalt") || b.equals("red_sand")
                || b.equals("soul_soil") || b.equals("magma_block") || b.equals("crimson_nylium"))) return false;
        if (!x.c.isAir(bx, g + 1, bz)) return false;
        x.c.set(bx, g, bz, "sponge");
        return true;
    }

    /** Up to {@code want} ore blocks on the ring just outside a pool, each within 2 blocks of its lava, spaced apart. */
    private static int shore(CCtx x, int cx, int cz, int radius, int want, long salt) {
        List<int[]> cands = new ArrayList<>();
        for (int dz = -radius - 3; dz <= radius + 3; dz++)
            for (int dx = -radius - 3; dx <= radius + 3; dx++) {
                int bx = cx + dx, bz = cz + dz;
                if (x.res.covers(bx, bz, 1) || x.paths.near(bx, bz, 1) || !x.h.has(bx, bz)) continue;
                int g = x.ground(bx, bz);
                if (BlockState.name(x.c.get(bx, g, bz)).equals("lava")) continue;
                boolean byLava = false;
                for (int d = 0; d < 4 && !byLava; d++)
                    for (int step = 1; step <= 2 && !byLava; step++)
                        for (int dy = -1; dy <= 0 && !byLava; dy++)
                            byLava = BlockState.name(x.c.get(bx + Blocks.DX[d] * step, g + dy, bz + Blocks.DZ[d] * step)).equals("lava");
                if (byLava) cands.add(new int[]{bx, bz});
            }
        cands.sort((a, b) -> Double.compare(Hash.unit(salt, a[0], a[1]), Hash.unit(salt, b[0], b[1])));
        List<int[]> chosen = new ArrayList<>();
        for (int[] cnd : cands) {
            if (chosen.size() >= want) break;
            boolean far = true;
            for (int[] o : chosen) if (Math.abs(o[0] - cnd[0]) + Math.abs(o[1] - cnd[1]) < 4) far = false;
            if (far && ore(x, cnd[0], cnd[1])) chosen.add(cnd);
        }
        return chosen.size();
    }
}
