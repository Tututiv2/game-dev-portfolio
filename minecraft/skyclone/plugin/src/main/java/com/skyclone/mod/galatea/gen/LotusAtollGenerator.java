package com.skyclone.mod.galatea.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * THE LOTUS ATOLL (research 03 §21): the second fishing island, a ring of tropical land round a
 * lily-choked lagoon, reached by ship from the Hub's Fishing Outpost at Fishing X.
 * <ul>
 *   <li><b>Atoll Landing</b> (south): the research station, the field lab, a bait stall, the two
 *       portal arches (Hub, Bayou), and a long pier out to the shrine islet in the lagoon.</li>
 *   <li><b>The Lagoon</b> (centre): clear water over sand and coral; lily pads everywhere, densest in
 *       the west bloom where the Exploding Frogs live.</li>
 *   <li><b>Lotus Highlands</b> (north): a broad hill with a flat crown, a lookout ring on top and a
 *       launch pad at its foot.</li>
 *   <li><b>Lotus Eater's Cave</b>: a tunnel into the hill's east flank down to a glowing chamber with
 *       the pool the Frogcoins are thrown into.</li>
 *   <li><b>Shellback Tunnel</b> (east, ref. Tewtil Tunnel) through a rock ridge to Tidepool Cove.</li>
 * </ul>
 * Pure: gen toolkit only. Preview: {@code bash tools/preview.sh com.skyclone.mod.galatea.gen.LotusAtollGenerator}.
 */
public final class LotusAtollGenerator implements IslandGenerator {

    public static final String ID = "lotus_atoll";
    static final int W = 64;                // water surface
    static final int Y = 68;                // the town terrace
    static final int LAG_X = 0, LAG_Z = 2;
    static final double LAG_RX = 62, LAG_RZ = 50;
    static final int HILL_X = 4, HILL_Z = -100;
    static final double HILL_R = 58, HILL_H = 28;
    static final int RIDGE_X = 100;
    static final int ISLET_X = 0, ISLET_Z = 14;
    static final int POOL_X = 124, POOL_Z = 6;
    static final int CAVE_X = 12, CAVE_Z = -94, CAVE_FLOOR = 62;
    static final String RAIL = "birch_slab[type=top,waterlogged=false]";

    @Override public String islandId() { return ID; }

    @Override public int version() { return 2; }   // 2: islet beach + wading shelves at the bank fishing spots

    @Override public Box bounds() { return new Box(-175, 0, -170, 175, 170, 170); }

    private Terrain.Heightmap h;
    private int[] depth;
    private long seed;

    private int di(int x, int z) {
        int ix = x - h.minX, iz = z - h.minZ;
        if (ix < 0 || iz < 0 || ix >= h.width || iz >= h.depth) return -1;
        return iz * h.width + ix;
    }

    boolean wet(int x, int z) { int i = di(x, z); return i >= 0 && depth[i] > 0; }

    static double hillBonus(int x, int z) {
        double d = Math.hypot(x - HILL_X, (z - HILL_Z) * 1.1);
        if (d >= HILL_R) return 0;
        double b = HILL_H * GKit.smooth(1 - d / HILL_R);
        return b > 22 ? 22 + (b - 22) * 0.3 : b;
    }

    static double ridgeBonus(int x, int z) {
        double d = Math.abs(x - RIDGE_X - 5 * Math.sin(z / 13.0) - 2 * Math.sin(z / 5.0 + 1)) / 9.0;
        if (d >= 1 || Math.abs(z) > 50) return 0;
        return 15 * GKit.smooth(1 - d) * GKit.smooth((50 - Math.abs(z)) / 18.0);
    }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        this.seed = seed;
        // ------------------------------------------------------------ 1. shape
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 142; s.radiusZ = 138; s.baseY = Y;
        s.hills = 2.5; s.hillScale = 36; s.cliff = 0.8; s.coastDrop = 2;
        s.coastNoise = 0.09; s.coastScale = 40; s.undersideDepth = 46; s.undersideNoise = 0.5;
        h = Terrain.island(s);
        h.forEach((x, z, top, bottom) -> {
            int add = (int) Math.round(hillBonus(x, z) + ridgeBonus(x, z));
            if (add > 0) h.setTop(x, z, top + add);
        });
        Box town = Box.of(-44, 0, 70, 36, 0, 120);
        h.flatten(town, Y, 8);

        // ------------------------------------------------------------ 2. water
        depth = new int[h.width * h.depth];
        Noise n = new Noise(Hash.salt(seed, "atoll-water"));
        h.forEach((x, z, top, bottom) -> {
            if (!h.inland(x, z, 8) || town.grow(2).containsXZ(x, z)) return;
            int d = 0;
            double wob = 1 + 0.17 * n.fbm2(x / 30.0, z / 30.0, 3) + 0.05 * n.noise2(x / 9.0, z / 9.0);
            double dl = Math.sqrt(sq((x - LAG_X) / LAG_RX) + sq((z - LAG_Z) / LAG_RZ)) / wob;
            if (dl < 1) d = 2 + (int) Math.round(12 * (1 - dl * dl));
            double dp = Math.hypot(x - POOL_X, z - POOL_Z) / (7.5 * (1 + 0.1 * n.noise2(x / 6.0, z / 6.0)));
            if (dp < 1) d = Math.max(d, 1 + (int) Math.round(3 * (1 - dp * dp)));
            if (Math.hypot(x - ISLET_X, z - ISLET_Z) <= 8.2) d = 0;   // the islet, its beach and a step
            if (d > 0) depth[di(x, z)] = d;
        });
        // wading shelves at the bank spots (the islet's sandbar, the cove's near half): knockback
        // lands you ankle-deep with a one-block step back up, never in water over your head (playtest:
        // TestPilot clients drowned at Torrent Springs' bank spot, see GKit.wadingShelf)
        GKit.wadingShelf(h, depth, ISLET_X, ISLET_Z, 11, 3);
        GKit.wadingShelf(h, depth, POOL_X - 9, POOL_Z, 5, 1);
        boolean[] step = new boolean[depth.length];
        GKit.beachStep(h, depth, step, POOL_X - 9, POOL_Z, 5);
        h.forEach((x, z, top, bottom) -> { int i = di(x, z); if (depth[i] > 0) h.setTop(x, z, W - depth[i]); else if (step[i]) h.setTop(x, z, W); });
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z) || step[di(x, z)]) return;
            double near = 99;
            for (int dz = -6; dz <= 6; dz++) for (int dx = -6; dx <= 6; dx++) if (wet(x + dx, z + dz)) near = Math.min(near, Math.hypot(dx, dz));
            if (near > 6) return;
            int cap = W + 1 + (int) (near / 2.2);
            if (top < W + 1) h.setTop(x, z, W + 1);
            else if (top > cap + 1 && hillBonus(x, z) + ridgeBonus(x, z) < 1) h.setTop(x, z, cap);
        });
        GKit.easeAroundSteps(h, step, W + 1);
        // the islet (W+2), a beach (W+1) and a step at the water line (W): shelf -> step -> beach -> islet
        // is three one-block steps, so a player knocked off the shrine walks back up
        for (int dz = -8; dz <= 8; dz++) for (int dx = -8; dx <= 8; dx++) {
            int r2 = dx * dx + dz * dz;
            if (h.has(ISLET_X + dx, ISLET_Z + dz) && r2 <= 67)
                h.setTop(ISLET_X + dx, ISLET_Z + dz, r2 <= 27 ? W + 2 : r2 <= 51 ? W + 1 : W);
        }

        // ------------------------------------------------------------ 3. paint + pour
        paint(c);
        h.forEach((x, z, top, bottom) -> {
            if (!wet(x, z)) return;
            for (int y = top + 1; y <= W; y++) c.set(x, y, z, "water");
            h.setWet(x, z, true);
        });

        // ------------------------------------------------------------ 4. the town
        Reservations res = new Reservations();
        Box plaza = Box.of(-12, 0, 88, 12, 0, 104);
        res.reserve("plaza", plaza);
        Palette paving = Palette.of("smooth_sandstone:5,sandstone:3,cut_sandstone:2,prismarine_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, Y, z, paving.at(x, Y, z));
        Builders.fountain(c, 0, Y, 96, 3, Palette.of("prismarine_bricks:3,dark_prismarine:1"), seed);

        Builders.House station = new Builders.House(-40, Y, 76, 13, 10);
        station.floors = 2; station.door = "south"; station.roof = "acacia"; station.roofStyle = "hip"; station.seed = seed;
        station.wall = Palette.of("white_terracotta:3,smooth_sandstone:2,birch_planks:1");
        station.frame = "stripped_birch_log"; station.foundation = Palette.of("prismarine_bricks:2,sandstone:2");
        station.floor = Fill.of("birch_planks"); station.doorWood = "birch"; station.chimney = false;
        boolean stationOk = res.reserve("research_station", station.reserve());
        if (stationOk) station.build(c);

        Builders.House lab = new Builders.House(18, Y, 72, 11, 8);
        lab.door = "south"; lab.roof = "jungle"; lab.roofStyle = "gable"; lab.seed = seed + 3;
        lab.wall = Palette.of("birch_planks:3,calcite:1,stripped_birch_log[axis=y]:1");
        lab.frame = "jungle_log"; lab.doorWood = "jungle";
        boolean labOk = res.reserve("field_lab", lab.reserve());
        if (labOk) lab.build(c);

        Builders.House cottage = new Builders.House(20, Y, 106, 9, 7);
        cottage.door = "west"; cottage.roof = "mangrove"; cottage.roofStyle = "gable"; cottage.seed = seed + 5;
        cottage.wall = Palette.of("bamboo_planks:3,stripped_bamboo_block[axis=y]:1");
        cottage.frame = "jungle_log"; cottage.doorWood = "bamboo";
        if (res.reserve("cottage", cottage.reserve())) cottage.build(c);

        Builders.Stall stall = null;
        if (res.reserve("frog_stall", Box.of(-27, Y - 12, 105, -21, Y + 4, 110)))
            stall = Builders.marketStall(c, -24, Y, 108, "north", "lime", "pink", seed);

        // the two arches home, side by side on the south edge of the landing
        res.reserve("portals", Box.of(-14, 0, 110, 14, 0, 118));
        Palette pad = Palette.of("prismarine_bricks:3,dark_prismarine:1,smooth_sandstone:2");
        for (int x = -14; x <= 14; x++) for (int z = 108; z <= 118; z++) c.set(x, Y, z, pad.at(x, Y, z));
        Fill frame = Palette.of("prismarine_bricks:3,dark_prismarine:1");
        GKit.portalArch(c, layout, "hub", -7, Y, 116, false, frame, "cyan");
        GKit.portalArch(c, layout, "backwater_bayou", 7, Y, 116, false, Palette.of("mossy_stone_bricks:3,mud_bricks:1"), "green");
        Builders.lampPost(c, -11, Y, 112, "street", "east");
        Builders.lampPost(c, 11, Y, 112, "street", "west");
        for (int[] lp : new int[][]{{-12, 88}, {12, 88}, {-12, 104}, {12, 104}})
            Builders.lampPost(c, lp[0], Y, lp[1], "street", lp[0] < 0 ? "east" : "west");
        Builders.bench(c, -6, Y, 101, "north", 3, "birch");
        Builders.bench(c, 4, Y, 101, "north", 3, "birch");
        Builders.crates(c, 14, Y, 92, seed);
        c.set(-14, Y + 1, 94, "barrel[facing=up,open=false]");
        c.set(-14, Y + 1, 95, "barrel[facing=up,open=false]");
        c.set(-14, Y + 2, 94, "decorated_pot");

        // ------------------------------------------------------------ 5. pier to the shrine islet
        int shoreZ = 60;
        for (int z = 86; z > ISLET_Z; z--) if (wet(0, z)) { shoreZ = z + 1; break; }
        int deck = W + 2;
        Palette planks = Palette.of("birch_planks:4,stripped_birch_wood[axis=y]:1,bamboo_planks:1");
        Builders.bridge(c, 0, Math.max(W + 1, h.top(0, shoreZ)), shoreZ, 0, deck, ISLET_Z + 5, 5, planks, RAIL, "birch_log", "birch_stairs");
        int midZ = (shoreZ + ISLET_Z + 5) / 2;
        for (int x = -6; x <= 6; x++)
            for (int z = midZ - 5; z <= midZ + 5; z++) {
                c.set(x, deck, z, planks.at(x, deck, z));
                for (int a = 1; a <= 4; a++) c.set(x, deck + a, z, "air");
                boolean zEdge = z == midZ - 5 || z == midZ + 5, xEdge = x == -6 || x == 6;
                boolean opening = zEdge && Math.abs(x) <= 2;
                if ((zEdge || xEdge) && !opening) c.set(x, deck + 1, z, RAIL);
                if (zEdge && xEdge) for (int y = deck - 1; y > W - 16 && !isSolidBed(c, x, y, z); y--) c.set(x, y, z, "stripped_birch_log[axis=y]");
            }
        c.set(5, deck + 1, midZ + 4, "barrel[facing=up,open=false]");
        res.reserve("pier", Box.of(-7, 0, ISLET_Z + 7, 7, 0, shoreZ + 1));
        int[] gazeboSoul = GKit.gazebo(c, ISLET_X, W + 2, ISLET_Z, "cherry", Palette.of("cherry_planks:3,pink_terracotta:1"));
        res.reserve("islet", Box.of(ISLET_X - 6, 0, ISLET_Z - 6, ISLET_X + 6, 0, ISLET_Z + 6));

        // ------------------------------------------------------------ 6. the cave and the tunnel
        int[] mouth = GKit.groundNear(h, 44, -64, 10, 3);
        if (mouth == null) mouth = new int[]{44, h.top(44, -64), -64};
        Palette caveFloor = Palette.of("moss_block:3,mossy_cobblestone:2,clay:1,rooted_dirt:1");
        List<int[]> caveCells = GKit.tunnel(c, new int[][]{{mouth[0], mouth[1], mouth[2]}, {34, Math.max(CAVE_FLOOR, mouth[1] - 4), -74},
                {24, CAVE_FLOOR + 1, -84}, {CAVE_X + 4, CAVE_FLOOR, CAVE_Z + 2}}, 2.2, 4, caveFloor);
        GKit.chamber(c, CAVE_X, CAVE_FLOOR, CAVE_Z, 13, 11, 8, caveFloor);
        int poolX = CAVE_X - 4, poolZ = CAVE_Z - 3;
        for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++) {
            double d = Math.hypot(dx, dz);
            if (d > 4.3) continue;
            int x = poolX + dx, z = poolZ + dz;
            int dep = d < 2.5 ? 3 : 2;
            c.set(x, CAVE_FLOOR - dep - 1, z, "clay");
            for (int y = CAVE_FLOOR - dep; y <= CAVE_FLOOR; y++) c.set(x, y, z, "water");
        }
        for (int dz = -12; dz <= 12; dz++) for (int dx = -14; dx <= 14; dx++) {   // light from the ceiling
            int x = CAVE_X + dx, z = CAVE_Z + dz;
            if (!Hash.chance(Hash.salt(seed, "cave-light"), x, z, 0.06)) continue;
            int ceil = -1;
            for (int y = CAVE_FLOOR + 2; y <= CAVE_FLOOR + 12; y++) if (!c.isAir(x, y, z)) { ceil = y; break; }
            if (ceil < 0 || !c.isAir(x, ceil - 1, z)) continue;
            c.set(x, ceil, z, Hash.chance(seed, x, 7, z, 0.5) ? "verdant_froglight[axis=y]" : "pearlescent_froglight[axis=y]");
            if (Hash.chance(seed, x, 9, z, 0.4) && c.isAir(x, ceil - 2, z)) c.set(x, ceil - 1, z, "spore_blossom");
        }
        for (int i = 6; i < caveCells.size(); i += 14) {
            int[] f = caveCells.get(i);
            c.set(f[0], f[1] + 4, f[2], "verdant_froglight[axis=y]");
        }
        int ledgeX = CAVE_X + 6, ledgeZ = CAVE_Z - 6;
        c.set(ledgeX, CAVE_FLOOR, ledgeZ, "mossy_cobblestone");
        Builders.flowerPot(c, CAVE_X + 9, CAVE_FLOOR + 1, CAVE_Z + 2, "blue_orchid");

        int tw = RIDGE_X - 18, te = RIDGE_X + 18;
        int twY = Math.max(W + 1, h.top(tw, 0)), teY = Math.max(W + 1, h.top(te, 0));
        Palette tunnelFloor = Palette.of("gravel:2,tuff:2,coarse_dirt:1,cobblestone:1");
        List<int[]> tcells = GKit.tunnel(c, new int[][]{{tw, twY, 0}, {RIDGE_X - 6, Math.min(twY, teY) + 1, 0}, {RIDGE_X + 6, Math.min(twY, teY) + 1, 0}, {te, teY, 0}}, 1.6, 4, tunnelFloor);
        for (int i = 4; i < tcells.size(); i += 10) {
            int[] f = tcells.get(i);
            if (c.isAir(f[0], f[1] + 3, f[2]) && !c.isAir(f[0], f[1] + 5, f[2])) c.set(f[0], f[1] + 4, f[2], "iron_chain[axis=y]");
            if (c.isAir(f[0], f[1] + 3, f[2])) c.set(f[0], f[1] + 3, f[2], Blocks.lantern(true));
        }
        int[] tunnelMid = tcells.isEmpty() ? null : tcells.get(tcells.size() / 2);

        // ------------------------------------------------------------ 7. highlands crown and the launch pad
        int crownY = h.top(HILL_X, HILL_Z);
        Palette ring = Palette.of("mossy_stone_bricks:3,stone_bricks:2,cracked_stone_bricks:1");
        for (int a = 0; a < 12; a++) {
            double ang = a * Math.PI / 6;
            int x = HILL_X + (int) Math.round(Math.cos(ang) * 9), z = HILL_Z + (int) Math.round(Math.sin(ang) * 9);
            int g = h.top(x, z);
            if (g == Terrain.Heightmap.NONE) continue;
            int ht = a % 3 == 0 ? 4 : 2;
            for (int y = g + 1; y <= g + ht; y++) c.set(x, y, z, ring.at(x, y, z));
            if (a % 3 == 0) c.set(x, g + ht + 1, z, Blocks.lantern(false));
        }
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) c.set(HILL_X + dx, crownY, HILL_Z + dz, (dx == 0 && dz == 0) ? "chiseled_stone_bricks" : "mossy_stone_bricks");
        c.set(HILL_X, crownY + 1, HILL_Z, "pink_petals[facing=north,flower_amount=4]");
        res.reserve("crown", Box.of(HILL_X - 10, 0, HILL_Z - 10, HILL_X + 10, 0, HILL_Z + 10));
        int[] launch = GKit.groundNear(h, -44, -50, 12, 3);
        if (launch != null) {
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                int x = launch[0] + dx, z = launch[2] + dz;
                c.set(x, launch[1], z, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? "prismarine_bricks" : "slime_block");
                for (int a = 1; a <= 3; a++) if (Builders.natural(c.get(x, launch[1] + a, z))) c.set(x, launch[1] + a, z, "air");
            }
            res.reserve("launch_pad", Box.of(launch[0] - 3, 0, launch[2] - 3, launch[0] + 3, 0, launch[2] + 3));
        }

        // ------------------------------------------------------------ 8. paths
        Palette walk = Palette.of("sand:3,smooth_sandstone:2,dirt_path:3,coarse_dirt:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{-12, 94}, {station.doorStep[0], 94}, {station.doorStep[0], station.doorStep[2] + 1}}, 3, walk, "birch_slab", null);
        Builders.path(c, new int[][]{{lab.doorStep[0], lab.doorStep[2] + 1}, {lab.doorStep[0], 88}}, 3, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{0, 88}, {0, shoreZ + 1}}, 3, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{-12, 92}, {-30, 94}, {-50, 94}, {-62, 74}, {-82, 40}, {-86, -6}, {-70, -42}, {-52, -54},
                launch != null ? new int[]{launch[0], launch[2] + 3} : new int[]{-44, -47}}, 3, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{-52, -54}, {-36, -78}, {-16, -96}, {HILL_X - 3, HILL_Z}}, 3, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{12, 92}, {48, 80}, {72, 50}, {tw - 6, 8}, {tw, 0}}, 3, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{72, 50}, {80, 10}, {72, -30}, {56, -52}, {mouth[0] + 2, mouth[2] + 2}}, 3, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{te, 0}, {te + 6, 2}, {POOL_X - 9, POOL_Z}}, 3, walk, "birch_slab", paths);
        if (cottage.doorStep != null) Builders.path(c, new int[][]{{cottage.doorStep[0] - 1, cottage.doorStep[2]}, {12, 100}}, 2, walk, "birch_slab", paths);
        Builders.path(c, new int[][]{{0, 104}, {0, 110}}, 3, walk, "birch_slab", paths);

        // ------------------------------------------------------------ 9. plants
        Box isle = h.box();
        Trees.forest(c, h, isle, seed, 0.016, 6, "jungle:2,azalea:3,cherry:2,oak:1", 4,
                (x, z) -> res.covers(x, z, 6) || paths.near(x, z, 3) || nearWater(x, z, 3) || town.grow(4).containsXZ(x, z)
                        || Math.abs(x - RIDGE_X) < 12 && Math.abs(z) < 52 || h.top(x, z) <= W + 2);
        long ps = Hash.salt(seed, "palms");
        List<int[]> palms = new ArrayList<>();
        h.forEach((x, z, top, bottom) -> {
            if (!Hash.chance(ps, x, z, 0.02)) return;
            if (wet(x, z) || top > W + 3 || !h.inland(x, z, 3) || res.covers(x, z, 4) || paths.near(x, z, 2)) return;
            for (int[] p : palms) if (Math.abs(p[0] - x) < 6 && Math.abs(p[1] - z) < 6) return;
            if (!c.isAir(x, top + 1, z)) return;
            Trees.palm(c, x, top, z, seed, 0.9 + Hash.unit(ps, x, 3, z) * 0.4);
            palms.add(new int[]{x, z});
        });
        groundCover(c, res, paths);
        int[][] hotspots = {{28, -6}, {-30, 16}};
        waterPlants(c, hotspots, shoreZ);

        // lamps along the long paths
        for (int[] p : new int[][]{{-40, 84}, {-70, 60}, {-84, 18}, {-80, -26}, {-60, -50}, {-30, -86}, {34, 84}, {62, 64}, {78, 28}, {78, -12}, {64, -44}, {te + 4, 4}}) {
            int x = p[0] + 3, z = p[1];
            int g = h.top(x, z);
            if (g == Terrain.Heightmap.NONE || wet(x, z) || paths.covers(x, z) || !c.isAir(x, g + 1, z)) continue;
            Builders.lampPost(c, x, g, z, "post", "north");
        }

        // ------------------------------------------------------------ 10. anchors
        layout.standAt("spawn", 0, Y, 100, 180f);
        layout.standAt("fishing.spot.pier", 0, deck, midZ - 4, 180f);
        layout.standAt("fishing.spot.islet", ISLET_X - 3, W + 2, ISLET_Z - 3, 180f);
        int[] poolSpot = GKit.groundNear(h, POOL_X - 9, POOL_Z, 6, 1);
        if (poolSpot != null) layout.standAt("fishing.spot.cove", poolSpot[0], poolSpot[1], poolSpot[2], -90f);
        for (int i = 0; i < hotspots.length; i++) layout.standAt("fishing.hotspot." + (i + 1), hotspots[i][0], W - 1, hotspots[i][1], 0f);
        layout.data("walk.ignore", "fishing.hotspot.");

        if (stationOk) layout.standAt("npc.ribery", station.doorStep[0], station.doorStep[1], station.doorStep[2] - 2, 0f);
        if (labOk) layout.standAt("npc.researcher_calder", lab.inside[0], lab.inside[1], lab.inside[2], 0f);
        layout.standAt("npc.researcher_brisa", -5, Y, 92, 180f);
        if (stall != null) layout.standAt("npc.atoll_vendor", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 180f);
        layout.standAt("npc.atoll_captain", 0, Y, 111, 180f);
        layout.standAt("npc.researcher_juniper", 3, deck, midZ + 2, 180f);
        layout.standAt("npc.researcher_peregrin", HILL_X + 3, crownY, HILL_Z + 2, 0f);
        layout.standAt("npc.researcher_tobiah", mouth[0] + 3, h.top(mouth[0] + 3, mouth[2] + 3), mouth[2] + 3, 90f);
        layout.standAt("npc.lotus_eater", poolX + 5, CAVE_FLOOR, poolZ + 1, 90f);
        layout.standAt("npc.wishing_pool", poolX + 5, CAVE_FLOOR, poolZ - 2, 90f);
        if (poolSpot != null) layout.standAt("npc.researcher_marisol", poolSpot[0] - 2, h.top(poolSpot[0] - 2, poolSpot[2] + 2), poolSpot[2] + 2, -90f);
        int[] wrenna = GKit.groundNear(h, -76, 60, 8, 3);
        if (wrenna != null) layout.standAt("npc.researcher_wrenna", wrenna[0], wrenna[1], wrenna[2], 90f);
        if (launch != null) {
            int[] lnpc = GKit.groundNear(h, launch[0] + 4, launch[2] + 3, 4, 2);
            if (lnpc != null) layout.standAt("npc.launcher", lnpc[0], lnpc[1], lnpc[2], 180f);
            layout.standAt("warp.launch_pad", launch[0], launch[1], launch[2], 180f);
        }
        layout.standAt("warp.highlands", HILL_X, crownY, HILL_Z + 3, 180f);
        layout.standAt("warp.cave", mouth[0], mouth[1], mouth[2], 135f);

        List<int[]> souls = new ArrayList<>();
        souls.add(new int[]{HILL_X - 1, crownY, HILL_Z - 1});                          // the crown of the highlands
        souls.add(new int[]{ledgeX, CAVE_FLOOR, ledgeZ});                                // the cave ledge
        if (tunnelMid != null) souls.add(tunnelMid);                                     // halfway through the tunnel
        souls.add(gazeboSoul);                                                           // the shrine islet
        int[] coveRock = GKit.groundNear(h, POOL_X + 6, POOL_Z + 6, 8, 1);
        if (coveRock != null) souls.add(coveRock);                                       // past the tide pool
        int[] behind = GKit.groundNear(h, -48, 100, 8, 2);
        if (behind != null) souls.add(behind);                                           // behind the research station
        for (int i = 0; i < souls.size(); i++) layout.standAt("fairy_soul." + (i + 1), souls.get(i)[0], souls.get(i)[1], souls.get(i)[2], 0f);

        // ------------------------------------------------------------ 11. zones and areas
        layout.zone(ID, "Lotus Atoll", 'd', new Box(isle.minX(), 0, isle.minZ(), isle.maxX(), 255, isle.maxZ()), null);
        layout.zone("lotus_atoll_landing", "Atoll Landing", 'e', Box.of(town.minX(), Y - 8, town.minZ(), town.maxX(), Y + 40, town.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("lotus_atoll_lagoon", "The Lagoon", 'b', Box.of(LAG_X - 66, W - 16, LAG_Z - 56, LAG_X + 66, W + 20, LAG_Z + 54), null);
        layout.zone("lotus_atoll_highlands", "Lotus Highlands", 'a', Box.of(HILL_X - 34, Y + 12, HILL_Z - 34, HILL_X + 34, 160, HILL_Z + 34), null);
        layout.zone("lotus_atoll_cave", "Lotus Eater's Cave", '2', Box.of(CAVE_X - 15, CAVE_FLOOR - 5, CAVE_Z - 13, Math.max(mouth[0] - 2, CAVE_X + 15), CAVE_FLOOR + 12, Math.max(mouth[2] - 3, CAVE_Z + 13)), null);
        layout.zone("lotus_atoll_tunnel", "Shellback Tunnel", '7', Box.of(tw + 1, Math.min(twY, teY) - 2, -3, te - 1, Math.max(twY, teY) + 6, 3), null);
        layout.zone("lotus_atoll_cove", "Tidepool Cove", '3', Box.of(te, W - 8, POOL_Z - 16, isle.maxX(), W + 30, POOL_Z + 16), null);
        layout.area("lagoon_water", Box.of(LAG_X - 50, W, LAG_Z - 40, LAG_X + 50, W, LAG_Z + 40));
        layout.area("lily_bloom", Box.of(LAG_X - 60, W, LAG_Z - 30, LAG_X - 20, W + 1, LAG_Z + 30));
        layout.area("cave_pool", Box.of(poolX - 4, CAVE_FLOOR - 3, poolZ - 4, poolX + 4, CAVE_FLOOR + 2, poolZ + 4));
        layout.area("town", new Box(town.minX(), Y + 1, town.minZ(), town.maxX(), Y + 4, town.maxZ()));
        layout.data("reservations", res.summary());
    }

    // ================================================================================ helpers

    private static double sq(double v) { return v * v; }

    private boolean isSolidBed(VoxelCanvas c, int x, int y, int z) {
        String b = BlockState.name(c.get(x, y, z));
        return !c.isAir(x, y, z) && !b.equals("water") && !b.equals("seagrass") && !b.equals("tall_seagrass") && !b.equals("lily_pad");
    }

    private boolean nearWater(int x, int z, int r) {
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) if (wet(x + dx, z + dz)) return true;
        return false;
    }

    private int drySlope(int x, int z) {
        int t = h.top(x, z), m = 0;
        for (int d = 0; d < 4; d++) {
            int nx = x + Blocks.DX[d], nz = z + Blocks.DZ[d];
            if (!h.has(nx, nz)) return 99;
            if (wet(nx, nz)) continue;
            m = Math.max(m, Math.abs(h.top(nx, nz) - t));
        }
        return m;
    }

    private void paint(VoxelCanvas c) {
        Palette bed = Palette.of("sand:6,clay:1,gravel:1");
        Palette deepBed = Palette.of("sand:5,clay:2,gravel:1,brain_coral_block:1,tube_coral_block:1,horn_coral_block:1,fire_coral_block:1");
        Palette beach = Palette.of("sand:8,smooth_sandstone:1");
        Palette grass = Palette.of("grass_block:12,coarse_dirt:1,rooted_dirt:1,moss_block:1");
        Palette high = Palette.of("grass_block:9,moss_block:3,podzol:1,coarse_dirt:1");
        Palette cliff = Palette.of("stone:4,andesite:2,tuff:2,calcite:1,mossy_cobblestone:1");
        Palette stone = Palette.of("stone:10,andesite:3,tuff:2,diorite:1");
        Palette under = Palette.of("stone:6,tuff:3,dripstone_block:2,andesite:2,sandstone:1");
        h.forEach((x, z, top, bottom) -> {
            boolean wetCol = wet(x, z);
            int slope = wetCol ? 0 : drySlope(x, z);
            boolean sandy = !wetCol && (top <= W + 3 || !h.inland(x, z, 3) && top <= Y + 1);
            boolean hill = hillBonus(x, z) > 6;
            String surface;
            if (wetCol) surface = (W - top >= 7 ? deepBed : bed).at(x, top, z);
            else if (slope >= 3) surface = cliff.at(x, top, z);
            else if (sandy) surface = beach.at(x, top, z);
            else surface = (hill ? high : grass).at(x, top, z);
            for (int y = bottom; y <= top; y++) {
                int d = top - y;
                String b;
                if (y - bottom < 6 && d > 3) b = under.at(x, y, z);
                else if (d == 0) b = surface;
                else if (d <= 3) b = wetCol || sandy ? (d <= 2 ? "sand" : "sandstone") : slope >= 3 ? cliff.at(x, y, z) : "dirt";
                else b = stone.at(x, y, z);
                c.set(x, y, z, b);
            }
        });
    }

    private void groundCover(VoxelCanvas c, Reservations res, Builders.PathResult paths) {
        long s = Hash.salt(seed, "cover");
        Palette plants = Palette.of("short_grass:22,fern:4,pink_petals[facing=north,flower_amount=4]:6,allium:2,pink_tulip:2,lily_of_the_valley:2,bush:3,firefly_bush:1,wildflowers[facing=east,flower_amount=3]:2");
        Palette high = Palette.of("short_grass:16,fern:6,pink_petals[facing=south,flower_amount=3]:5,azure_bluet:2,moss_carpet:4,bush:2");
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z) || paths.covers(x, z) || res.covers(x, z, 0)) return;
            if (!c.isAir(x, top + 1, z)) return;
            String ground = BlockState.name(c.get(x, top, z));
            if (ground.equals("sand")) {
                if (top == W + 1 && nearWater(x, z, 1) && Hash.unit(s, x, 5, z) < 0.12) {
                    int hgt = 2 + (int) (Hash.unit(s, x, 9, z) * 2);
                    for (int i = 1; i <= hgt; i++) c.set(x, top + i, z, "sugar_cane");
                }
                return;
            }
            if (!(ground.equals("grass_block") || ground.equals("moss_block") || ground.equals("coarse_dirt") || ground.equals("podzol") || ground.equals("rooted_dirt"))) return;
            double u = Hash.unit(s, x, z);
            boolean hill = hillBonus(x, z) > 6;
            if (u < 0.025 && c.isAir(x, top + 2, z)) {
                c.set(x, top + 1, z, "peony[half=lower]");
                c.set(x, top + 2, z, "peony[half=upper]");
                return;
            }
            if (u >= (hill ? 0.40 : 0.32)) return;
            c.set(x, top + 1, z, (hill ? high : plants).pick(s, x, top, z));
        });
    }

    private void waterPlants(VoxelCanvas c, int[][] hotspots, int shoreZ) {
        long s = Hash.salt(seed, "water-plants");
        h.forEach((x, z, top, bottom) -> {
            if (!wet(x, z)) return;
            int d = W - top;
            boolean clear = true;
            for (int[] hs : hotspots) if (Math.abs(hs[0] - x) <= 5 && Math.abs(hs[1] - z) <= 5) { clear = false; break; }
            boolean pier = Math.abs(x) <= 8 && z >= ISLET_Z - 2 && z <= shoreZ + 2;
            boolean bloom = x < LAG_X - 20 && Math.abs(z - LAG_Z) < 32;
            double u = Hash.unit(s, x, z);
            double pads = bloom ? 0.36 : 0.15;
            if (clear && !pier && d <= 8 && u < pads && c.isAir(x, W + 1, z)) c.set(x, W + 1, z, "lily_pad");
            if (d >= 2 && BlockState.name(c.get(x, top + 1, z)).equals("water")) {
                double v = Hash.unit(s, x, 3, z);
                if (v < 0.22) c.set(x, top + 1, z, "seagrass");
                else if (v < 0.26) c.set(x, top + 1, z, "sea_pickle[pickles=3,waterlogged=true]");
                else if (d >= 4 && v < 0.31 && BlockState.name(c.get(x, top + 2, z)).equals("water")) {
                    c.set(x, top + 1, z, "tall_seagrass[half=lower]");
                    c.set(x, top + 2, z, "tall_seagrass[half=upper]");
                } else if (d >= 6 && v < 0.36) {
                    String[] corals = {"tube_coral", "brain_coral", "horn_coral", "fire_coral", "bubble_coral"};
                    c.set(x, top + 1, z, corals[(int) (Hash.unit(s, x, 11, z) * corals.length)] + "[waterlogged=true]");
                }
            }
        });
    }
}
