package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Terrain for the Crimson Isle: shape, volcano, chamber, tunnel, pools, district surfaces and flora. */
final class CTerrain {

    private CTerrain() {}

    // ---- 1. shape (numbers only) -------------------------------------------------------------

    static void shape(CCtx x) {
        Terrain.IslandShape s = new Terrain.IslandShape(x.seed);
        s.radiusX = CPlan.RADIUS_X; s.radiusZ = CPlan.RADIUS_Z; s.baseY = CPlan.BASE_Y;
        s.hills = 7; s.hillScale = 52; s.coastNoise = 0.12; s.coastScale = 64; s.cliff = 0.62; s.coastDrop = 3;
        s.undersideDepth = 52; s.undersideNoise = 0.5;
        Terrain.Heightmap h = Terrain.island(s);
        x.h = h;

        Noise dune = new Noise(x.salt("dunes"));
        Noise marsh = new Noise(x.salt("marsh"));
        Noise cone = new Noise(x.salt("cone"));
        double R = CPlan.VOLCANO_R, H = CPlan.VOLCANO_H, CR = CPlan.CRATER_R;
        x.rimY = CPlan.BASE_Y + 4 + (int) Math.round(H * Math.pow(1 - CR / R, 1.35));
        x.lavaLevel = x.rimY - 5;
        final int rimY = x.rimY;
        List<int[]> edits = new ArrayList<>();
        h.forEach((cx, cz, top, bottom) -> {
            int t = top, b = bottom;
            CPlan.District d = x.district(cx, cz);
            if (d == CPlan.District.DESERT) t += (int) Math.round(3.5 * Math.max(0, dune.ridged2(cx / 24.0, cz / 38.0, 3) - 0.35));
            if (d == CPlan.District.MARSH) t -= (int) Math.round(2.0 * Math.max(0, marsh.fbm2(cx / 18.0, cz / 18.0, 3)));
            double vd = CPlan.volcanoDist(cx, cz);
            if (vd < R) {
                double k = 1 - vd / R;
                double add = H * Math.pow(k, 1.35) + 3 * k * cone.fbm2(cx / 11.0, cz / 11.0, 3) + 4 * Math.min(1, k * 4);
                if (vd < CR) {
                    double f = vd / CR;
                    t = rimY - 9 + (int) Math.round(f * f * 6);
                } else t = Math.max(t, CPlan.BASE_Y + (int) Math.round(add));
                if (vd < CPlan.CHAMBER_R + 14) b = Math.min(b, CPlan.CHAMBER_FLOOR - 10);
                else b = Math.min(b, t - 30);
            }
            edits.add(new int[]{cx, cz, t, b});
        });
        for (int[] e : edits) h.set(e[0], e[1], e[2], e[3]);

        // ---- plots: flatten before anything is written ----
        for (Box p : CPlan.PLOTS) {
            int y = h.average(p);
            if (y == Terrain.Heightmap.NONE) y = CPlan.BASE_Y;
            if (p == CPlan.STRONGHOLD) y = Math.max(y, CPlan.BASE_Y + 2);
            if (p == CPlan.TUNNEL_MOUTH) y = Math.max(CPlan.BASE_Y, Math.min(y, CPlan.BASE_Y + 3));
            int blend = p == CPlan.DRAKEHOLD || p == CPlan.CARMINE || p == CPlan.STRONGHOLD ? 12 : 8;
            h.flatten(p, y, blend);
            x.plotY.put(p, y);
        }
    }

    // ---- 2. write the ground, then carve -----------------------------------------------------

    static void fill(CCtx x) {
        VoxelCanvas c = x.c;
        Terrain.Heightmap h = x.h;
        Terrain.SurfaceRules r = new Terrain.SurfaceRules();
        r.top = Fill.of("netherrack");
        r.soil = Fill.of("netherrack");
        r.soilDepth = 3;
        r.stone = Palette.of("netherrack:12,blackstone:3,basalt[axis=y]:1,netherrack:2");
        r.steepSlope = 3;
        r.cliff = Palette.of("blackstone:5,basalt[axis=y]:3,netherrack:3");
        r.underside = Palette.of("netherrack:6,blackstone:3,basalt[axis=y]:2,magma_block:1");
        r.undersideDepth = 7;
        Terrain.fill(c, h, r);

        crater(x);
        chamber(x);
        tunnel(x);

        // ---- pools ----
        Palette lavaBed = Palette.of("blackstone:3,basalt[axis=y]:2,magma_block:1");
        x.layout.data("crimson.pool.hut", String.valueOf(CKit.pool(c, h, -98, -76, 6.5, 3, x.seed, "lava", lavaBed)));
        x.layout.data("crimson.pool.desert", String.valueOf(CKit.pool(c, h, -98, 176, 6, 3, x.seed, "lava", lavaBed)));
        CKit.pool(c, h, 40, -52, 4.5, 2, x.seed, "lava", lavaBed);          // fields, beside the volcano road
        CKit.pool(c, h, -40, 22, 4, 2, x.seed, "lava", lavaBed);
        Palette mud = Palette.of("mud:4,clay:1,soul_soil:1");
        int[][] ponds = {{-150, -150}, {-178, -110}, {-120, -92}, {-200, -150}, {-140, -180}};
        for (int[] p : ponds) CKit.pool(c, h, p[0], p[1], 5 + Math.abs(p[0] % 3), 2, x.seed, "water", mud);
    }

    private static void crater(CCtx x) {
        VoxelCanvas c = x.c;
        Terrain.Heightmap h = x.h;
        int r = (int) Math.ceil(CPlan.CRATER_R);
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                int cx = CPlan.VOLCANO_X + dx, cz = CPlan.VOLCANO_Z + dz;
                if (CPlan.volcanoDist(cx, cz) >= CPlan.CRATER_R) continue;
                int t = h.top(cx, cz);
                if (t == Terrain.Heightmap.NONE) continue;
                c.set(cx, t, cz, Hash.unit(x.seed, cx, t, cz) < 0.3 ? "magma_block" : "blackstone");
                if (t >= x.lavaLevel) continue;
                for (int y = t + 1; y <= x.lavaLevel; y++) c.set(cx, y, cz, "lava");
                h.setWet(cx, cz, true);
            }
        x.layout.data("crimson.crater", x.rimY + "," + x.lavaLevel);
    }

    private static void chamber(CCtx x) {
        VoxelCanvas c = x.c;
        Noise n = new Noise(x.salt("chamber"));
        double R = CPlan.CHAMBER_R;
        int F = CPlan.CHAMBER_FLOOR;
        Palette floor = Palette.of("blackstone:5,polished_blackstone:2,basalt[axis=y]:2");
        Palette shell = Palette.of("blackstone:4,basalt[axis=y]:3,magma_block:1");
        int ir = (int) Math.ceil(R) + 1;
        for (int dz = -ir; dz <= ir; dz++)
            for (int dx = -ir; dx <= ir; dx++) {
                int cx = CPlan.VOLCANO_X + dx, cz = CPlan.VOLCANO_Z + dz;
                double d = Math.sqrt(dx * dx + dz * dz) / (R * (1 + 0.12 * n.noise2(cx / 9.0, cz / 9.0)));
                if (d > 1) continue;
                int ceil = F + 2 + (int) Math.round((9 + 4 * n.noise2(cx / 7.0 + 30, cz / 7.0)) * Math.sqrt(Math.max(0, 1 - d * d)));
                c.set(cx, F, cz, floor.at(cx, F, cz));
                for (int y = F - 3; y < F; y++) c.set(cx, y, cz, shell.at(cx, y, cz));
                for (int y = F + 1; y <= ceil; y++) c.set(cx, y, cz, "air");
                c.set(cx, ceil + 1, cz, Hash.unit(x.seed, cx, 77, cz) < 0.06 ? "shroomlight" : shell.at(cx, ceil + 1, cz));
                if (Hash.unit(x.seed, cx, 78, cz) < 0.02 && ceil - F > 5) c.set(cx, ceil, cz, "glowstone");
            }
        // arena: a ring of polished blackstone with braziers
        for (int dz = -9; dz <= 9; dz++)
            for (int dx = -9; dx <= 9; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 9.4) continue;
                int cx = CPlan.VOLCANO_X + dx, cz = CPlan.VOLCANO_Z + dz;
                c.set(cx, F, cz, d > 8.4 ? "chiseled_polished_blackstone" : (((dx + dz) & 1) == 0 ? "polished_blackstone" : "polished_blackstone_bricks"));
            }
        for (int a = 0; a < 6; a++) {
            double ang = a * Math.PI / 3;
            int bx = CPlan.VOLCANO_X + (int) Math.round(Math.cos(ang) * 13), bz = CPlan.VOLCANO_Z + (int) Math.round(Math.sin(ang) * 13);
            CKit.brazier(c, bx, F, bz, false);
        }
        // enclosed lava moats in the chamber's edge: one block deep pits, surrounded by floor
        for (int dz = -ir; dz <= ir; dz++)
            for (int dx = -ir; dx <= ir; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 17 || d > 21) continue;
                int cx = CPlan.VOLCANO_X + dx, cz = CPlan.VOLCANO_Z + dz;
                if (Math.abs(dx) <= 4 || Math.abs(dz) <= 4) continue;           // keep four walkways
                if (n.noise2(cx / 6.0, cz / 6.0) < 0.1) continue;
                boolean ok = true;
                for (int k = 0; k < 4 && ok; k++) if (!CKit.solid(c, cx + Blocks.DX[k], F, cz + Blocks.DZ[k]) && !BlockState.name(c.get(cx + Blocks.DX[k], F, cz + Blocks.DZ[k])).equals("lava")) ok = false;
                if (ok && CKit.solid(c, cx, F - 1, cz)) c.set(cx, F, cz, "lava");
            }
        x.layout.standAt("boss.magma_boss", CPlan.VOLCANO_X, F, CPlan.VOLCANO_Z, 180f);
        x.layout.standAt("crimson.chamber_center", CPlan.VOLCANO_X + 4, F, CPlan.VOLCANO_Z + 4, 180f);
        Box zone = Box.of(CPlan.VOLCANO_X - ir, F - 2, CPlan.VOLCANO_Z - ir, CPlan.VOLCANO_X + ir, F + 16, CPlan.VOLCANO_Z + ir);
        x.layout.zone("magma_chamber", "Magma Chamber", 'c', zone, null);
        x.soul(CPlan.VOLCANO_X - 22, F, CPlan.VOLCANO_Z + 2);
    }

    private static void tunnel(CCtx x) {
        VoxelCanvas c = x.c;
        int mouthY = x.y(CPlan.TUNNEL_MOUTH);
        int L = CPlan.TUNNEL_Z_START - CPlan.TUNNEL_Z_END;
        int F = CPlan.CHAMBER_FLOOR;
        int[] fy = new int[L + 1];
        for (int i = 0; i <= L; i++) fy[i] = (int) Math.round(mouthY + (F - mouthY) * Math.min(1.0, i / (double) (L - 4)));
        Palette wall = CKit.BLACK_BRICK;
        for (int i = 0; i <= L + 4; i++) {
            int z = CPlan.TUNNEL_Z_START - i;
            int f = fy[Math.min(i, L)];
            for (int tx = CPlan.TUNNEL_X0 - 1; tx <= CPlan.TUNNEL_X1 + 1; tx++) {
                boolean side = tx < CPlan.TUNNEL_X0 || tx > CPlan.TUNNEL_X1;
                c.set(tx, f, z, side ? wall.at(tx, f, z) : (i % 7 == 3 ? "polished_blackstone_bricks" : "polished_blackstone"));
                for (int y = f + 1; y <= f + 5; y++) {
                    if (side) { if (y <= f + 4) c.set(tx, y, z, wall.at(tx, y, z)); else c.set(tx, y, z, wall.at(tx, y, z)); }
                    else c.set(tx, y, z, y == f + 5 ? wall.at(tx, y, z) : "air");
                }
                CKit.support(c, tx, f, z, Fill.of("blackstone"));
            }
            if (i % 8 == 4) c.set((CPlan.TUNNEL_X0 + CPlan.TUNNEL_X1) / 2, f + 4, z, "soul_lantern[hanging=true,waterlogged=false]");
            // stairs where the floor rises toward the mouth (south)
            if (i > 0 && i <= L && fy[i] < fy[i - 1]) {
                for (int tx = CPlan.TUNNEL_X0; tx <= CPlan.TUNNEL_X1; tx++)
                    c.set(tx, fy[i] + 1, z, Blocks.stairs("polished_blackstone_brick", "south"));
            }
        }
        // an arch at the mouth, open to the south
        int z0 = CPlan.TUNNEL_Z_START + 1;
        for (int tx = CPlan.TUNNEL_X0 - 2; tx <= CPlan.TUNNEL_X1 + 2; tx++) {
            boolean post = tx < CPlan.TUNNEL_X0 || tx > CPlan.TUNNEL_X1;
            for (int y = mouthY + 1; y <= mouthY + 7; y++) {
                if (post || y >= mouthY + 6) c.set(tx, y, z0, y == mouthY + 7 ? "chiseled_polished_blackstone" : wall.at(tx, y, z0));
                else c.set(tx, y, z0, "air");
            }
            c.set(tx, mouthY, z0, "polished_blackstone");
            CKit.support(c, tx, mouthY, z0, Fill.of("blackstone"));
        }
        c.set(CPlan.TUNNEL_X0 - 3, mouthY + 1, z0, "air");
        CKit.brazier(c, CPlan.TUNNEL_X0 - 3, mouthY, z0 + 1, true);
        CKit.brazier(c, CPlan.TUNNEL_X1 + 3, mouthY, z0 + 1, true);
        x.stops.put("tunnel", new int[]{(CPlan.TUNNEL_X0 + CPlan.TUNNEL_X1) / 2, mouthY, z0 + 2});
        x.res.reserve("tunnel_mouth", Box.of(CPlan.TUNNEL_X0 - 4, mouthY - 10, z0 - 2, CPlan.TUNNEL_X1 + 4, mouthY + 9, z0 + 2));
    }

    // ---- 3. surfaces by district (after structures and roads) ---------------------------------

    static final Map<CPlan.District, Palette> SURFACE = new EnumMap<>(CPlan.District.class);
    static {
        SURFACE.put(CPlan.District.FIELDS, Palette.of("crimson_nylium:8,netherrack:2,soul_soil:1"));
        SURFACE.put(CPlan.District.VOLCANO, Palette.of("blackstone:4,basalt[axis=y]:3,smooth_basalt:2,netherrack:2"));
        SURFACE.put(CPlan.District.MARSH, Palette.of("mycelium:7,mud:2,podzol:1,soul_soil:1"));
        SURFACE.put(CPlan.District.SKULL, Palette.of("soul_soil:6,netherrack:2,gravel:1"));
        SURFACE.put(CPlan.District.DRAKEHOLD, Palette.of("netherrack:4,coarse_dirt:3,crimson_nylium:2,gravel:1"));
        SURFACE.put(CPlan.District.CARMINE, Palette.of("crimson_nylium:7,netherrack:2,red_sand:1"));
        SURFACE.put(CPlan.District.STRONGHOLD, Palette.of("soul_soil:4,netherrack:3,blackstone:2,gravel:1"));
        SURFACE.put(CPlan.District.DESERT, Palette.of("red_sand:12,red_sandstone:1"));
        SURFACE.put(CPlan.District.TOMB, Palette.of("red_sand:5,soul_soil:3,netherrack:2"));
        SURFACE.put(CPlan.District.DUKEDOM, Palette.of("netherrack:4,gravel:2,coarse_dirt:2,blackstone:1"));
        SURFACE.put(CPlan.District.COURTYARD, Palette.of("crimson_nylium:6,netherrack:2"));
        SURFACE.put(CPlan.District.ASHFANG, Palette.of("blackstone:4,basalt[axis=y]:2,netherrack:3,soul_soil:1"));
        SURFACE.put(CPlan.District.DOJO, Palette.of("crimson_nylium:4,netherrack:3,coarse_dirt:1"));
    }

    static void repaint(CCtx x) {
        VoxelCanvas c = x.c;
        x.h.forEach((cx, cz, top, bottom) -> {
            if (x.paths.covers(cx, cz)) return;
            int y = top;
            String b = BlockState.name(c.get(cx, y, cz));
            if (!b.equals("netherrack")) return;
            if (!c.isAir(cx, y + 1, cz) && !BlockState.name(c.get(cx, y + 1, cz)).equals("short_grass")) {
                // something stands on it (a building footing): leave it
                String above = BlockState.name(c.get(cx, y + 1, cz));
                if (!above.endsWith("roots") && !above.endsWith("fungus")) return;
            }
            CPlan.District d = x.district(cx, cz);
            Palette p = SURFACE.get(d);
            c.set(cx, y, cz, p.at(cx, y, cz));
            if (d == CPlan.District.DESERT) {
                c.set(cx, y - 1, cz, "red_sand");
                c.set(cx, y - 2, cz, "red_sandstone");
            }
        });
    }

    // ---- 4. flora ---------------------------------------------------------------------------------

    static void flora(CCtx x) {
        VoxelCanvas c = x.c;
        Terrain.Heightmap h = x.h;
        long s = x.salt("flora");
        List<int[]> trunks = new ArrayList<>();
        h.forEach((cx, cz, top, bottom) -> {
            if (h.wet(cx, cz)) return;
            if (x.res.covers(cx, cz, 2) || x.paths.near(cx, cz, 1)) return;
            String g = BlockState.name(c.get(cx, top, cz));
            if (!c.isAir(cx, top + 1, cz)) return;
            CPlan.District d = x.district(cx, cz);
            double u = Hash.unit(s, cx, cz);
            boolean bigOk = h.slope(cx, cz) <= 1 && h.inland(cx, cz, 4) && !x.res.covers(cx, cz, 7) && !x.paths.near(cx, cz, 5);
            switch (d) {
                case FIELDS, CARMINE, COURTYARD, DOJO -> {
                    if (!g.equals("crimson_nylium")) return;
                    double dens = d == CPlan.District.FIELDS ? 0.007 : 0.004;
                    if (bigOk && u < dens && far(trunks, cx, cz, 10)) {
                        CKit.fungus(c, cx, top, cz, Hash.hash(s, cx, top, cz), 0.9 + Hash.unit(s, cx, 9, cz) * 0.6, false);
                        trunks.add(new int[]{cx, cz});
                    } else if (u < 0.13) c.set(cx, top + 1, cz, "crimson_roots");
                    else if (u < 0.15) c.set(cx, top + 1, cz, "crimson_fungus");
                }
                case MARSH -> {
                    if (!(g.equals("mycelium") || g.equals("podzol") || g.equals("mud"))) return;
                    if (bigOk && u < 0.011 && far(trunks, cx, cz, 11)) {
                        double v = Hash.unit(s, cx, 11, cz);
                        if (v < 0.4) CKit.redMushroom(c, cx, top, cz, Hash.hash(s, cx, top, cz));
                        else if (v < 0.75) Trees.brownMushroom(c, cx, top, cz, Hash.hash(s, cx, top, cz), 1.3);
                        else CKit.fungus(c, cx, top, cz, Hash.hash(s, cx, top, cz), 1.1, true);
                        trunks.add(new int[]{cx, cz});
                    } else if (u < 0.05) c.set(cx, top + 1, cz, Hash.unit(s, cx, 4, cz) < 0.5 ? "red_mushroom" : "brown_mushroom");
                    else if (u < 0.09 && g.equals("mycelium")) c.set(cx, top + 1, cz, "short_grass");
                }
                case DESERT, TOMB -> {
                    if (!g.equals("red_sand")) return;
                    if (u < 0.025) c.set(cx, top + 1, cz, "dead_bush");
                    else if (u < 0.029 && bigOk) {
                        int hgt = 2 + (int) (Hash.unit(s, cx, 6, cz) * 2);
                        boolean clear = true;
                        for (int k = 0; k < 4; k++) if (!c.isAir(cx + Blocks.DX[k], top + 1, cz + Blocks.DZ[k])) clear = false;
                        if (clear) for (int i = 1; i <= hgt; i++) c.set(cx, top + i, cz, "cactus");
                    }
                }
                case DRAKEHOLD, DUKEDOM, STRONGHOLD, SKULL, ASHFANG -> {
                    if (g.equals("crimson_nylium") && u < 0.10) c.set(cx, top + 1, cz, "crimson_roots");
                    else if ((g.equals("soul_soil") || g.equals("netherrack")) && u < 0.006 && bigOk && d != CPlan.District.STRONGHOLD) {
                        CKit.fungus(c, cx, top, cz, Hash.hash(s, cx, top, cz), 0.7, false);
                        trunks.add(new int[]{cx, cz});
                    }
                }
                case VOLCANO -> {
                    if (u < 0.004 && bigOk && h.slope(cx, cz) <= 2) {
                        int hgt = 3 + (int) (Hash.unit(s, cx, 6, cz) * 5);
                        for (int i = 1; i <= hgt; i++) c.set(cx, top + i, cz, "basalt[axis=y]");
                    }
                }
            }
        });
        // fossils in the desert: bone arcs half buried
        int[][] fossils = {{-120, 120}, {-70, 185}, {-135, 175}};
        for (int[] f : fossils) {
            int g = x.ground(f[0], f[1]);
            for (int i = -4; i <= 4; i++) {
                int hh = (int) Math.round(Math.sqrt(Math.max(0, 16 - i * i)));
                for (int k = 0; k <= hh; k++) if (k == hh || k == 0) c.set(f[0] + i, g + k, f[1], "bone_block[axis=y]");
                if (i % 2 == 0) for (int k = 1; k < hh; k++) c.set(f[0] + i, g + k, f[1], "bone_block[axis=y]");
            }
            x.soul(f[0] + 5, x.ground(f[0] + 5, f[1] + 1), f[1] + 1);
        }
    }

    private static boolean far(List<int[]> pts, int x, int z, int min) {
        for (int[] p : pts) if (Math.abs(p[0] - x) < min && Math.abs(p[1] - z) < min) return false;
        return true;
    }
}
