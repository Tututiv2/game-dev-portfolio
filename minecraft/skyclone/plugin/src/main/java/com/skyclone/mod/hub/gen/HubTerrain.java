package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * The hub's ground: one big floating island with rolling hills, the Mountain to the north, a hill
 * under the Ruins, a thick crust under the Graveyard (the Crypts live in it), then every district
 * plot flattened BEFORE anything is written so buildings sit on level ground.
 */
final class HubTerrain {

    private HubTerrain() {}

    static Terrain.Heightmap shape(HubCtx g) {
        Terrain.IslandShape s = new Terrain.IslandShape(g.seed);
        s.radiusX = ISLAND_R; s.radiusZ = ISLAND_R - 6;
        s.baseY = 70; s.hills = 9; s.hillScale = 70;
        s.coastNoise = 0.06; s.coastScale = 80; s.cliff = 0.7; s.coastDrop = 6;
        s.undersideDepth = 24; s.undersideNoise = 0.55;
        Terrain.Heightmap h = Terrain.island(s);

        Noise ridge = new Noise(Hash.salt(g.seed, "hub-mountain"));
        Noise warp = new Noise(Hash.salt(g.seed, "hub-mountain-warp"));
        Noise roll = new Noise(Hash.salt(g.seed, "hub-roll"));
        h.forEach((x, z, top, bottom) -> {
            double add = 0;
            // the Mountain: a lumpy cone with ridged detail, sharper near the top
            double dx = x - MX, dz = z - MZ;
            double d = Math.sqrt(dx * dx + dz * dz) * (1 + 0.16 * warp.fbm2(x / 40.0, z / 40.0, 3));
            if (d < MR) {
                double t = 1 - d / MR;
                double sm = Noise.smoothstep(0, 1, t);
                add += MPEAK * Math.pow(sm, 1.25) + 9 * t * ridge.ridged2(x / 26.0, z / 26.0, 4);
            }
            // the Ruins hill
            double rd = Math.hypot(x - RUINS_X, z - RUINS_Z);
            if (rd < RUINS_R + 18) add += RUINS_HILL * Noise.smoothstep(0, 1, 1 - rd / (RUINS_R + 18));
            // wilderness: rougher ground in the south-west
            if (x < -60 && z > 90) add += 5 * Math.max(0, roll.fbm2(x / 28.0, z / 28.0, 3));
            int nt = (int) Math.round(top + add);
            int nb = bottom;
            // a thick crust under the graveyard for the crypts, and under the mountain for the coal mine
            if (GRAVEYARD.grow(12).containsXZ(x, z)) nb = Math.min(nb, 34);
            if (MINING.grow(8).containsXZ(x, z) || Math.hypot(x - 76, z - 172) < 30) nb = Math.min(nb, 40);
            h.set(x, z, nt, nb);
        });

        // ---- plots ----
        g.V = clampY(h.average(VILLAGE.grow(-20)), 71, 74);
        flat(h, VILLAGE, g.V, 16);
        g.portalY = clampY(h.average(PORTALS), g.V - 1, g.V + 3);
        flat(h, PORTALS, g.portalY, 12);
        g.miningY = clampY(h.average(Box.of(MINING.minX(), 0, MINING.maxZ() - 10, MINING.maxX(), 0, MINING.maxZ())), g.V, g.V + 6);
        flat(h, MINING, g.miningY, 10);
        g.graveY = clampY(h.average(GRAVEYARD), 68, 76);
        flat(h, GRAVEYARD, g.graveY, 12);
        g.farmY = clampY(h.average(FARM.grow(-20)), 68, 74);
        flat(h, FARM, g.farmY, 14);
        g.colY = clampY(h.average(COLOSSEUM), 70, 80);
        flat(h, COLOSSEUM, g.colY, 10);
        g.campY = h.average(CAMP);
        flat(h, CAMP, g.campY, 10);
        g.wizardY = h.average(WIZARD);
        flat(h, WIZARD, g.wizardY, 8);
        g.daY = h.average(DARK_AUCTION);
        flat(h, DARK_AUCTION, g.daY, 8);
        g.witchY = h.average(WITCH);
        flat(h, WITCH, g.witchY, 8);
        g.outpostY = clampY(h.average(OUTPOST), 70, 74);
        flat(h, OUTPOST, g.outpostY, 8);
        Box ruinsPlot = Box.of(RUINS_X - 16, 0, RUINS_Z - 12, RUINS_X + 16, 0, RUINS_Z + 12);
        g.ruinsY = h.average(ruinsPlot);
        flat(h, ruinsPlot, g.ruinsY, 9);
        flat(h, Box.of(RUINS_X + 18, 0, RUINS_Z - 20, RUINS_X + 30, 0, RUINS_Z - 8), g.ruinsY, 6);
        g.castleY = h.average(CASTLE);
        flat(h, CASTLE, g.castleY, 10);
        g.carnivalY = h.average(CARNIVAL);
        flat(h, CARNIVAL, g.carnivalY, 8);
        g.summitY = h.average(SUMMIT) + 1;
        flat(h, SUMMIT, g.summitY, 9);
        return h;
    }

    static int clampY(int y, int lo, int hi) { return Math.max(lo, Math.min(hi, y)); }

    /** Like Heightmap.flatten but never creates land past the coast. */
    static void flat(Terrain.Heightmap h, Box area, int y, int blend) {
        for (int z = area.minZ() - blend; z <= area.maxZ() + blend; z++)
            for (int x = area.minX() - blend; x <= area.maxX() + blend; x++) {
                if (!h.has(x, z)) continue;
                int top = h.top(x, z);
                int dx = Math.max(0, Math.max(area.minX() - x, x - area.maxX()));
                int dz = Math.max(0, Math.max(area.minZ() - z, z - area.maxZ()));
                double d = Math.sqrt(dx * dx + dz * dz);
                int ny;
                if (d == 0) ny = y;
                else if (d > blend) continue;
                else ny = (int) Math.round(y + (top - y) * Noise.smoothstep(0, blend, d));
                h.set(x, z, ny, Math.min(h.bottom(x, z), ny - 3));
            }
    }

    /** Surface rules: snow on the peak, rock on steep slopes, sand at the lake. */
    static Terrain.SurfaceRules rules() {
        Terrain.SurfaceRules r = new Terrain.SurfaceRules();
        r.snowAbove = 152;
        r.snow = Palette.of("snow_block:6,packed_ice:1");
        r.steepSlope = 3;
        r.cliff = Palette.of("stone:8,andesite:4,cobblestone:2,mossy_cobblestone:1,tuff:1");
        r.underside = Palette.of("stone:6,andesite:3,cobblestone:2,tuff:2,dirt:1,deepslate:1");
        r.undersideDepth = 6;
        return r;
    }
}
