package com.skyclone.mod.mines.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

/**
 * The Gold Mine: a sunlit open-pit mine on a floating island. A haul road spirals down five
 * terraced benches cut into ore-streaked walls; timber scaffolds and ladders shortcut between
 * benches; a miners' camp on the south rim holds the two smiths; a lapis tunnel and a gold gallery
 * run off into the rock; and at the bottom of the pit an adit leads down into the Deep Caverns.
 *
 * <p>Research 03 §8 (layout, ores, NPCs), §28 (12 fairy souls), §31.8 (gold + timber, scaffolded pit).
 */
public final class GoldMineGenerator implements IslandGenerator {

    public static final int RIM = 86;
    /** Pit centre and elliptical radii (blocks). */
    static final int PCX = 0, PCZ = -6;
    static final double RX = 46, RZ = 40, R = 46;
    /** Bench width and drop. */
    static final int W = 7, H = 6, RINGS = 5;
    static final int BOTTOM = RIM - H * RINGS - H;   // 50

    @Override public String islandId() { return "gold_mine"; }
    @Override public int version() { return 3; }   // 2: arrival moved off the obelisk's axis; 3 (wave 5): the reference's 12 souls
    @Override public Box bounds() { return new Box(-100, 0, -100, 100, 150, 100); }

    // ---- pit geometry ------------------------------------------------------------------------------

    private Noise wob;

    /** Elliptical "radius" of a column around the pit centre, in blocks along x. */
    double rho(int x, int z) {
        double nx = (x - PCX) / RX, nz = (z - PCZ) / RZ;
        double a = Math.atan2(nz, nx);
        double r = Math.sqrt(nx * nx + nz * nz) * R;
        return r * (1 + 0.035 * wob.noise2(Math.cos(a) * 2.2, Math.sin(a) * 2.2));
    }

    static double angle(int x, int z) {
        double a = Math.atan2((z - PCZ) / RZ, (x - PCX) / RX);
        return a < 0 ? a + Math.PI * 2 : a;
    }

    /** Bench index: -1 outside the pit, 0..4 benches, 5 = pit floor. */
    static int ring(double rho) {
        if (rho >= R) return -1;
        int k = (int) Math.floor((R - rho) / W);
        return Math.min(k, RINGS);
    }

    static int benchY(int k) { return k < 0 ? RIM : k >= RINGS ? BOTTOM : RIM - H * (k + 1); }

    /** Haul-road ramp k (0..5) occupies the outer half of bench k between these angles. */
    final double[] rampStart = new double[RINGS + 1], rampLen = new double[RINGS + 1];

    private void planRamps() {
        double a = Math.PI / 2;                              // first ramp leaves the rim due south, by the camp
        for (int k = 0; k <= RINGS; k++) {
            double outer = R - k * W, inner = k >= RINGS ? R - RINGS * W - 4 : R - (k + 1) * W;
            double mid = (outer + inner) / 2 + 1.5;
            double arc = 14;                                 // 6 down over 14 blocks
            rampStart[k] = a;
            rampLen[k] = arc / mid;
            a += rampLen[k] + 0.35;
        }
    }

    /** Ramp height at (x,z) or MIN_VALUE when the column is not on a ramp. */
    int rampY(int x, int z, double rho) {
        int k = ring(rho);
        if (k < 0) return Integer.MIN_VALUE;
        double outer = R - k * W;
        double inner = k >= RINGS ? R - RINGS * W - 4.5 : R - (k + 1) * W;
        double halfLine = (outer + inner) / 2;
        if (rho < halfLine - 0.5) return Integer.MIN_VALUE;   // ramps use the outer half of a bench
        double d = angle(x, z) - rampStart[k];
        while (d < 0) d += Math.PI * 2;
        if (d > rampLen[k]) return Integer.MIN_VALUE;
        double t = d / rampLen[k];
        int from = benchY(k - 1), to = benchY(k);
        return (int) Math.round(from - (from - to) * t);
    }

    // ---- generate ------------------------------------------------------------------------------------

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        wob = new Noise(Hash.salt(seed, "pitwobble"));
        planRamps();
        Noise ridge = new Noise(Hash.salt(seed, "ridge"));
        Noise knollN = new Noise(Hash.salt(seed, "knoll"));

        // ---- 1. terrain shape ----
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 84; s.radiusZ = 84; s.baseY = RIM; s.hills = 4; s.coastNoise = 0.10; s.cliff = 0.7;
        s.coastDrop = 3; s.undersideDepth = 92; s.undersideNoise = 0.35;
        Terrain.Heightmap h = Terrain.island(s);
        // ridge to the north, a lapis knoll to the west, flat rim + camp, then the pit itself
        h.forEach((x, z, top, bottom) -> {
            double rr = Math.hypot(x, z) / 84.0;
            double edge = Noise.smoothstep(1.0, 0.82, rr);
            int y = top;
            double north = Noise.smoothstep(-34, -64, z);
            if (north > 0) y += (int) Math.round(north * edge * (13 + 11 * ridge.ridged2(x / 30.0, z / 30.0, 3)));
            double knoll = Noise.smoothstep(26, 8, Math.hypot(x + 64, z + 4));
            if (knoll > 0) y = Math.max(y, (int) Math.round(RIM + knoll * edge * (8 + 3 * knollN.fbm2(x / 9.0, z / 9.0, 2))));
            h.setTop(x, z, y);
        });
        h.forEach((x, z, top, bottom) -> {                  // the camp shelf: flat where it is land, never past the coast
            if (z < 40 || Math.abs(x) > 50) return;
            double inner = Math.max(Math.abs(x) - 38, 0) + Math.max(z - 76, 0) + Math.max(44 - z, 0);
            double k = Noise.smoothstep(7, 0, inner);
            if (k > 0) h.setTop(x, z, (int) Math.round(Noise.lerp(k, top, RIM)));
        });
        h.forEach((x, z, top, bottom) -> {
            double r = rho(x, z);
            if (r >= R && r < R + 7) h.setTop(x, z, RIM);
            else if (r >= R + 7 && r < R + 12) h.setTop(x, z, (int) Math.round(Noise.lerp(Noise.smoothstep(R + 7, R + 12, r), RIM, top)));
        });
        h.forEach((x, z, top, bottom) -> {
            double r = rho(x, z);
            int k = ring(r);
            if (k < 0) return;
            int ry = rampY(x, z, r);
            h.setTop(x, z, ry != Integer.MIN_VALUE ? ry : benchY(k));
        });

        // ---- 2. write terrain ----
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.steepSlope = 3;
        rules.cliff = Palette.of("stone:8,andesite:4,cobblestone:2,coal_ore:1");
        rules.stone = Palette.of("stone:14,andesite:4,tuff:2,granite:1");
        rules.underside = Palette.of("stone:6,andesite:3,tuff:3,cobblestone:2,coarse_dirt:1");
        Terrain.fill(c, h, rules);

        // ---- 3. paint the pit: benches, road, ore-streaked walls ----
        Palette bench = Palette.of("gravel:4,coarse_dirt:3,stone:3,cobblestone:1,andesite:2");
        Palette road = Palette.of("dirt_path:5,gravel:3,coarse_dirt:2");
        Palette oreHigh = Palette.of("stone:30,andesite:7,cobblestone:3,coal_ore:7,iron_ore:5,gold_ore:4,granite:2");
        Palette oreLow = Palette.of("stone:28,andesite:6,tuff:3,gold_ore:9,iron_ore:5,coal_ore:3");
        for (int z = -60; z <= 50; z++)
            for (int x = -60; x <= 60; x++) {
                if (!h.has(x, z)) continue;
                double r = rho(x, z);
                int top = h.top(x, z);
                int k = ring(r);
                if (k >= 0) c.set(x, top, z, rampY(x, z, r) != Integer.MIN_VALUE ? road.at(x, top, z) : bench.at(x, top, z));
                if (r > R + 2) continue;
                int lowest = top;
                for (int d = 0; d < 4; d++) {
                    int nt = h.top(x + Blocks.DX[d], z + Blocks.DZ[d]);
                    if (nt != Terrain.Heightmap.NONE) lowest = Math.min(lowest, nt);
                }
                Palette ore = top >= RIM - 12 ? oreHigh : oreLow;
                for (int y = lowest + 1; y <= top - (k >= 0 ? 1 : 0); y++) c.set(x, y, z, ore.at(x, y, z));
            }
        MineKit.stepify(c, Box.of(-50, 0, -50, 50, 0, 40), h::top,
                (x, z) -> { double r = rho(x, z); return ring(r) >= 0 && rampY(x, z, r) != Integer.MIN_VALUE; }, "cobblestone_stairs");

        Reservations res = new Reservations();
        MineKit.Souls souls = new MineKit.Souls();

        // ---- 4. the miners' camp on the south rim ----
        Box plaza = Box.of(-12, RIM, 50, 12, RIM, 70);
        res.reserve("plaza", plaza.grow(1));
        Palette paving = Palette.of("stone_bricks:5,cobblestone:2,andesite:2,cracked_stone_bricks:1,gravel:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, RIM, z, paving.at(x, RIM, z));
        // gold obelisk: the camp's landmark, visible from the pit
        // Its plinth (x -2..2, z 56..60) sits on the plaza's centre line, so the arrival (section 14) is offset east
        // of it: on the centre line the player landed facing a stone wall two blocks away.
        for (int x = -2; x <= 2; x++) for (int z = 56; z <= 60; z++) c.set(x, RIM + 1, z, Hash.chance(seed, x, z, 0.3) ? "chiseled_stone_bricks" : "stone_bricks");
        for (int x = -1; x <= 1; x++) for (int z = 57; z <= 59; z++) c.set(x, RIM + 2, z, "polished_andesite");
        for (int y = RIM + 3; y <= RIM + 8; y++) c.set(0, y, 58, y == RIM + 8 ? "gold_block" : "gold_block");
        c.set(0, RIM + 9, 58, Blocks.lantern(false));
        for (int[] q : new int[][]{{-2, 56}, {2, 56}, {-2, 60}, {2, 60}}) c.set(q[0], RIM + 2, q[1], Blocks.lantern(false));

        Builders.House forge = new Builders.House(-32, RIM, 49, 9, 7);
        forge.door = "east"; forge.roof = "spruce"; forge.roofStyle = "gable"; forge.chimney = true; forge.chimneyBlock = "bricks";
        forge.wall = Palette.of("stone_bricks:4,cobblestone:2,stripped_spruce_log[axis=y]:1");
        forge.frame = "spruce_log"; forge.floor = Fill.of("stone_bricks"); forge.seed = seed; forge.doorWood = "spruce";
        if (res.reserve("gold_forge", forge.reserve())) forge.build(c);
        Builders.House smithy = new Builders.House(24, RIM, 49, 9, 7);
        smithy.door = "west"; smithy.roof = "dark_oak"; smithy.chimney = true; smithy.chimneyBlock = "stone_bricks";
        smithy.wall = Palette.of("spruce_planks:4,cobblestone:2,stripped_spruce_log[axis=y]:1");
        smithy.frame = "dark_oak_log"; smithy.seed = seed + 1; smithy.doorWood = "dark_oak";
        if (res.reserve("iron_smithy", smithy.reserve())) smithy.build(c);
        Builders.House cot1 = new Builders.House(-30, RIM, 62, 7, 6);
        cot1.door = "east"; cot1.roof = "spruce"; cot1.wall = Palette.of("oak_planks:4,stripped_oak_log[axis=y]:1"); cot1.seed = seed + 2;
        if (res.reserve("cottage_w", cot1.reserve())) cot1.build(c);
        Builders.House cot2 = new Builders.House(24, RIM, 62, 7, 6);
        cot2.door = "west"; cot2.roof = "dark_oak"; cot2.wall = Palette.of("spruce_planks:4,cobblestone:1"); cot2.seed = seed + 3;
        if (res.reserve("cottage_e", cot2.reserve())) cot2.build(c);
        Builders.House store = new Builders.House(-30, RIM, 72, 9, 5);
        store.door = "north"; store.roof = "spruce"; store.roofStyle = "flat"; store.wall = Palette.of("spruce_planks:3,stripped_spruce_log[axis=y]:1"); store.seed = seed + 4;
        if (res.reserve("store", store.reserve())) store.build(c);

        // forge yard: anvils, a lava trough, a grindstone and a quench barrel
        int fx = forge.doorStep[0] + 2, fz = forge.doorStep[2];
        c.set(fx, RIM + 1, fz - 3, "anvil[facing=north]");
        c.set(fx + 1, RIM + 1, fz - 3, "blast_furnace[facing=east,lit=true]");
        c.set(fx, RIM + 1, fz + 3, "lava_cauldron");
        c.set(fx + 1, RIM + 1, fz + 3, "grindstone[face=floor,facing=north]");
        c.set(smithy.doorStep[0] - 2, RIM + 1, smithy.doorStep[2] - 3, "anvil[facing=south]");
        c.set(smithy.doorStep[0] - 3, RIM + 1, smithy.doorStep[2] - 3, "smithing_table");
        c.set(smithy.doorStep[0] - 2, RIM + 1, smithy.doorStep[2] + 3, "water_cauldron[level=3]");
        layout.standAt("npc.gold_forger", forge.doorStep[0] + 2, RIM, forge.doorStep[2], -90f);
        layout.standAt("npc.iron_forger", smithy.doorStep[0] - 2, RIM, smithy.doorStep[2], 90f);
        souls.add(forge.inside[0], forge.inside[1], forge.inside[2]);
        MineKit.crates(c, -26, RIM, 69, seed);
        MineKit.crates(c, 31, RIM, 72, seed + 7);

        // ---- 5. portal home, south edge of the plaza ----
        res.reserve("portal", Box.of(-4, RIM - 2, 72, 4, RIM + 8, 78));
        for (int x = -4; x <= 4; x++) for (int z = 71; z <= 77; z++) c.set(x, RIM, z, paving.at(x, RIM, z));
        MineKit.portal(c, layout, "hub", 0, RIM, 75, 'x', 3, 4, Palette.of("stripped_spruce_log[axis=y]:3,gold_block:1,stone_bricks:2"), null);
        Builders.lampPost(c, -4, RIM, 74, "post", "north");
        Builders.lampPost(c, 4, RIM, 74, "post", "north");

        // ---- 6. lamps and paths ----
        for (int[] q : new int[][]{{-12, 50}, {12, 50}, {-12, 70}, {12, 70}}) Builders.lampPost(c, q[0], RIM, q[1], "street", q[0] < 0 ? "east" : "west");
        Palette dirtPath = Palette.of("dirt_path:6,gravel:2,coarse_dirt:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{0, 49}, {0, 40}, {0, 36}}, 3, dirtPath, "cobblestone_stairs", null);
        Builders.path(c, new int[][]{{-13, 60}, {-21, 55}}, 2, dirtPath, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{13, 60}, {21, 55}}, 2, dirtPath, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-13, 66}, {-22, 65}}, 2, dirtPath, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{13, 66}, {22, 65}}, 2, dirtPath, "cobblestone_stairs", paths);
        for (int[] q : new int[][]{{-26, 44}, {26, 44}, {-48, 20}, {48, 20}, {-54, -14}, {54, -14}, {-40, -46}, {40, -46}, {0, -52}})
            if (h.has(q[0], q[1])) { int g = h.top(q[0], q[1]); if (g <= RIM + 2 && rho(q[0], q[1]) > R + 1) Builders.lampPost(c, q[0], g, q[1], "post", "north"); }

        // ---- 7. rim fence, crane and the tram line to the rim ----
        for (int z = -55; z <= 45; z++)
            for (int x = -55; x <= 55; x++) {
                double r = rho(x, z);
                if (r < R || r >= R + 1.0) continue;
                if (paths.near(x, z, 1)) continue;
                double d = angle(x, z) - rampStart[0];
                while (d < 0) d += Math.PI * 2;
                if (d < rampLen[0] + 0.12 || d > Math.PI * 2 - 0.12) continue;
                if (h.top(x, z) == RIM && c.isAir(x, RIM + 1, z)) c.set(x, RIM + 1, z, "spruce_fence");
            }
        for (int z = 40; z <= 49; z++) c.set(5, RIM, z, "gravel");
        MineKit.railRun(c, 5, 49, 5, 40, (x, z) -> RIM);
        crane(c, 5, RIM, 38, seed);

        // ---- 8. benches: scaffolds with ladders between benches, rails, lanterns ----
        double[] scafA = {0.25, 1.15, 2.6, 3.7, 4.55, 5.5};
        for (int i = 0; i < scafA.length; i++) {
            int k = i % 4;                                  // wall between bench k and k+1
            int[] sp = scaffold(c, h, k, scafA[i], seed + i);
            if (sp != null && (i == 1 || i == 3 || i == 5)) souls.add(sp[0], sp[1], sp[2]);
        }
        for (int k = 0; k <= RINGS; k++) {
            double rr = k >= RINGS ? 5 : R - k * W - W + 1.5;
            for (double a = 0; a < Math.PI * 2; a += 9.0 / Math.max(6, rr)) {
                int x = PCX + (int) Math.round(Math.cos(a) * rr * RX / R), z = PCZ + (int) Math.round(Math.sin(a) * rr * RZ / R);
                if (!h.has(x, z) || rampY(x, z, rho(x, z)) != Integer.MIN_VALUE) continue;
                int g = h.top(x, z);
                if (g != benchY(k) || !c.isAir(x, g + 1, z)) continue;
                if (Hash.chance(seed, x, z, 0.5)) Builders.lampPost(c, x, g, z, "torch", "north");
                else MineKit.crates(c, x, g, z, seed + x * 31L + z);
            }
        }
        // a straight tram line along the west bench
        MineKit.railRun(c, -35, -16, -35, 4, (x, z) -> h.top(x, z) == benchY(1) ? benchY(1) : Integer.MIN_VALUE);

        // ---- 9. the pit floor: the adit down to the Deep Caverns ----
        int by = BOTTOM;
        int az0 = PCZ - 9;                                  // inside the bottom's north edge
        for (int z = az0 - 12; z <= az0; z++)
            for (int x = -2; x <= 2; x++) {
                c.set(x, by, z, Palette.of("gravel:2,stone_bricks:2,cobblestone:1").at(x, by, z));
                for (int y = by + 1; y <= by + 4; y++) c.set(x, y, z, "air");
                c.set(x, by + 5, z, "stone");
            }
        for (int z = az0 - 10; z <= az0; z += 3) MineKit.supportFrame(c, 0, by, z, 'z', 2, 3, "spruce_log");
        MineKit.railRun(c, 1, az0, 1, az0 - 9, (x, z) -> by);
        MineKit.portal(c, layout, "deep_caverns", 0, by, az0 - 11, 'x', 3, 4, Palette.of("polished_deepslate:3,deepslate_bricks:2,gold_block:1"), null);
        MineKit.hangLantern(c, -1, by + 5, az0 - 2, 2, false);
        MineKit.hangLantern(c, 1, by + 5, az0 - 7, 2, false);
        headframe(c, 0, by, PCZ, seed);
        MineKit.crates(c, -6, by, PCZ + 3, seed + 99);
        MineKit.tntPile(c, 6, by, PCZ + 2, seed);
        souls.add(-7, by, PCZ - 4);

        // ---- 10. the lapis tunnel (west, off bench 1) and its grotto ----
        int ly = benchY(1);                                 // 74
        int lx0 = (int) Math.round(PCX - (R - W) * RX / R);
        Palette lapisWall = Palette.of("stone:20,andesite:4,lapis_ore:7,tuff:2,coal_ore:1");
        tunnel(c, lx0, -8, -62, -4, ly, 5, lapisWall, 'x', seed);
        Box grotto = Box.of(-72, ly, -18, -56, ly + 7, 8);
        chamber(c, grotto, lapisWall, Palette.of("gravel:3,stone:3,cobblestone:2,coarse_dirt:2"), seed);
        MineKit.railRun(c, lx0, -6, -60, -6, (x, z) -> ly);
        for (int x = lx0 - 3; x >= -60; x -= 5) MineKit.supportFrame(c, x, ly, -6, 'x', 3, 4, "spruce_log");
        MineKit.hangLantern(c, -64, ly + 8, -10, 3, false);
        MineKit.hangLantern(c, -64, ly + 8, 2, 3, false);
        MineKit.hangLantern(c, -68, ly + 8, -4, 2, false);
        MineKit.crates(c, -68, ly, 4, seed + 5);
        MineKit.crates(c, -67, ly, -13, seed + 6);
        souls.add(-70, ly, -2);
        souls.add(-48, ly, -6);
        layout.area("ores.lapis_tunnel", Box.of(-74, ly, -19, lx0 + 1, ly + 8, 9));
        layout.area("lapis_grotto", Box.of(-70, ly + 1, -15, -58, ly + 3, 5));
        layout.zone("lapis_tunnel", "Lapis Tunnel", '9', Box.of(-74, ly - 1, -19, lx0, ly + 9, 9), null);

        // ---- 11. the gold gallery (north, off bench 0) ----
        int gy = benchY(0);                                 // 80
        int gz0 = (int) Math.round(PCZ - RZ);
        Palette goldWall = Palette.of("stone:18,andesite:3,gold_ore:10,granite:2,iron_ore:2");
        tunnel(c, -2, gz0, 2, -58, gy, 5, goldWall, 'z', seed + 3);
        Box gallery = Box.of(-11, gy, -70, 11, gy + 8, -56);
        chamber(c, gallery, goldWall, Palette.of("gravel:3,stone:3,coarse_dirt:2,cobblestone:1"), seed + 4);
        MineKit.railRun(c, 0, gz0 - 1, 0, -64, (x, z) -> gy);
        for (int z = gz0 - 3; z >= -60; z -= 5) MineKit.supportFrame(c, 0, gy, z, 'z', 3, 4, "dark_oak_log");
        MineKit.hangLantern(c, -6, gy + 9, -63, 3, false);
        MineKit.hangLantern(c, 6, gy + 9, -63, 3, false);
        MineKit.crates(c, 7, gy, -67, seed + 8);
        souls.add(-8, gy, -66);
        souls.add(9, gy, -58);
        layout.area("ores.gold_gallery", Box.of(-12, gy, -71, 12, gy + 9, gz0 + 1));
        layout.area("gold_gallery", Box.of(-9, gy + 1, -68, 9, gy + 3, -58));
        layout.zone("gold_gallery", "Gold Gallery", '6', Box.of(-12, gy - 1, -71, 12, gy + 10, gz0), null);

        // ---- 12. trees and grass on the open surface ----
        Terrain.grassAndFlowers(c, h, seed, 0.18, 0.02);
        Trees.forest(c, h, h.box(), seed, 0.03, 6, "spruce:5,oak:3,birch:1", 3,
                (x, z) -> rho(x, z) < R + 10 || res.covers(x, z, 4) || paths.near(x, z, 3) || Math.abs(x) < 50 && z > 40 && z < 84);

        // ---- 13. more fairy souls ----
        souls.add(store.inside[0], store.inside[1], store.inside[2]);
        for (int[] q : new int[][]{{-44, 74}, {40, 74}, {-20, PCZ + 14}, {34, PCZ - 6}, {-58, 30}, {-38, -20}, {52, 8}, {-6, PCZ + 4}})
            if (h.has(q[0], q[1])) souls.add(q[0], h.top(q[0], q[1]), q[1]);
        souls.add(cot2.inside[0], cot2.inside[1], cot2.inside[2]);
        souls.write(c, layout, 12);   // wave 5: NEU fairy_souls.json mining_1 = 12

        // ---- 14. what the game needs to know ----
        // Facing north (the pit) with a clear line past the obelisk: the plinth's east face is x 2, so x 6 leaves it
        // at the left edge of the view (a landmark, not a wall) and looks up the tram line to the crane and the pit.
        layout.standAt("spawn", 6, RIM, 64, 180f);
        layout.area("ores.pit", Box.of(-50, BOTTOM, -50, 50, RIM + 1, 38));
        layout.area("pit", Box.of(-44, BOTTOM + 1, -44, 44, RIM - 3, 32));
        layout.area("camp", Box.of(-12, RIM + 1, 50, 12, RIM + 3, 70));
        layout.zone("gold_mine", "Gold Mine", '6', new Box(-100, 0, -100, 100, 150, 100), null);
        layout.zone("camp", "Miners' Camp", 'e', Box.of(-46, RIM - 3, 42, 46, RIM + 20, 84), null, Layout.Zone.SAFE);
        layout.zone("the_pit", "The Pit", '6', Box.of(-48, BOTTOM - 2, -48, 48, RIM - 1, 36), null);
        layout.data("reservations", res.summary());
        layout.data("walk.ignore", "cam.");
    }

    // ---- pieces ----------------------------------------------------------------------------------------

    /** A straight tunnel along one axis, floor at {@code y}, {@code h} tall, walls painted with ore. */
    private static void tunnel(VoxelCanvas c, int x1, int z1, int x2, int z2, int y, int h, Palette wall, char axis, long seed) {
        Box b = Box.of(x1, y, z1, x2, y + h, z2);
        Palette floor = Palette.of("gravel:3,stone:2,coarse_dirt:2,cobblestone:1");
        for (int x = b.minX() - 1; x <= b.maxX() + 1; x++)
            for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++)
                for (int yy = y; yy <= y + h + 1; yy++) {
                    boolean inX = x >= b.minX() && x <= b.maxX(), inZ = z >= b.minZ() && z <= b.maxZ();
                    if (inX && inZ) {
                        if (yy == y) c.set(x, yy, z, floor.at(x, yy, z));
                        else if (yy <= y + h) c.set(x, yy, z, "air");
                        else c.set(x, yy, z, wall.at(x, yy, z));
                    } else if (yy > y && yy <= y + h + 1 && (axis == 'x' ? inX : inZ)) {
                        c.set(x, yy, z, wall.at(x, yy, z));
                    }
                }
    }

    /** An irregular chamber inside {@code b}: floor at b.minY, ceiling vaulted toward b.maxY. */
    private static void chamber(VoxelCanvas c, Box b, Palette wall, Palette floor, long seed) {
        int cx = b.centerX(), cz = b.centerZ();
        double rx = (b.maxX() - b.minX()) / 2.0, rz = (b.maxZ() - b.minZ()) / 2.0;
        int y = b.minY(), hMax = b.maxY() - b.minY();
        for (int x = b.minX() - 1; x <= b.maxX() + 1; x++)
            for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) {
                double nx = (x - cx) / (rx + 0.5), nz = (z - cz) / (rz + 0.5);
                double d = Math.sqrt(nx * nx + nz * nz);
                d += 0.12 * (Hash.unit(seed, x, 1, z) - 0.5);
                if (d > 1.12) continue;
                if (d > 1.0) continue;
                int hh = (int) Math.round(3 + (hMax - 3) * Math.sqrt(Math.max(0, 1 - d * d)));
                c.set(x, y, z, floor.at(x, y, z));
                for (int yy = y + 1; yy <= y + hh; yy++) c.set(x, yy, z, "air");
                c.set(x, y + hh + 1, z, wall.at(x, y + hh + 1, z));
                if (d > 0.82) c.set(x, y + hh, z, wall.at(x, y + hh, z));
            }
    }

    /**
     * A timber scaffold against the wall of bench k at angle a: ladder up the face, posts and a
     * plank landing at the top. Returns the landing's floor block (a soul spot) or null.
     */
    private int[] scaffold(VoxelCanvas c, Terrain.Heightmap h, int k, double a, long seed) {
        double rr = R - (k + 1) * W - 1.0;                  // just inside the wall, on bench k+1
        int x = PCX + (int) Math.round(Math.cos(a) * rr * RX / R), z = PCZ + (int) Math.round(Math.sin(a) * rr * RZ / R);
        int lowY = benchY(k + 1), highY = benchY(k);
        if (!h.has(x, z) || h.top(x, z) != lowY || rampY(x, z, rho(x, z)) != Integer.MIN_VALUE) return null;
        // direction toward the wall (outward from the pit centre)
        int dx = (int) Math.signum(Math.round(Math.cos(a) * 1.4)), dz = (int) Math.signum(Math.round(Math.sin(a) * 1.4));
        String wallDir = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
        int wx = Blocks.dx(wallDir), wz = Blocks.dz(wallDir);
        // walk outward to the cell right in front of the wall
        int lx = x, lz = z;
        for (int i = 0; i < 4; i++) {
            if (h.top(lx + wx, lz + wz) > lowY) break;
            lx += wx; lz += wz;
        }
        if (h.top(lx, lz) != lowY || h.top(lx + wx, lz + wz) < highY) return null;
        String ladderFacing = Blocks.opposite(wallDir);
        for (int y = lowY + 1; y <= highY; y++) c.set(lx, y, lz, Blocks.ladder(ladderFacing));
        // a frame of posts and a landing one block out from the wall, at the upper bench's height
        int px = Blocks.dx(Blocks.cw(wallDir)), pz = Blocks.dz(Blocks.cw(wallDir));
        int ox = lx - wx, oz = lz - wz;
        for (int s = -2; s <= 2; s += 4) {
            int bx = ox + px * s, bz = oz + pz * s;
            if (h.top(bx, bz) != lowY) continue;
            for (int y = lowY + 1; y <= highY + 3; y++) if (c.isAir(bx, y, bz)) c.set(bx, y, bz, y == highY ? "spruce_planks" : "spruce_fence");
        }
        for (int s = -2; s <= 2; s++) {
            int bx = lx + px * s, bz = lz + pz * s;
            if (s != 0 && c.isAir(bx, highY + 1, bz) && h.top(bx, bz) == lowY && c.isAir(bx, highY, bz)) c.set(bx, highY, bz, Blocks.slab("spruce", "top"));
        }
        for (int s = -2; s <= 2; s++) if (c.isAir(ox + px * s, highY + 4, oz + pz * s)) c.set(ox + px * s, highY + 4, oz + pz * s, "spruce_planks");
        MineKit.hangLantern(c, ox, highY + 4, oz, 2, false);
        // the top of the ladder lands on the upper bench: that bench cell is the soul spot
        return new int[]{lx + wx * 2, highY, lz + wz * 2};
    }

    /** A timber headframe (pit-head gear) standing over the pit floor. */
    private static void headframe(VoxelCanvas c, int x, int y, int z, long seed) {
        int top = y + 12;
        for (int[] o : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}})
            for (int yy = y + 1; yy <= top; yy++) c.set(x + o[0], yy, z + o[1], "stripped_spruce_log[axis=y]");
        for (int yy : new int[]{y + 5, top}) {
            for (int d = -3; d <= 3; d++) {
                c.set(x + d, yy, z - 3, "spruce_log[axis=x]");
                c.set(x + d, yy, z + 3, "spruce_log[axis=x]");
                c.set(x - 3, yy, z + d, "spruce_log[axis=z]");
                c.set(x + 3, yy, z + d, "spruce_log[axis=z]");
            }
        }
        // the sheave wheel on top, and the cage hanging into a capped shaft
        for (int d = -2; d <= 2; d++) c.set(x + d, top + 1, z, "spruce_slab[type=bottom]");
        for (int a = 0; a < 16; a++) {
            double t = a * Math.PI / 8;
            int wx = x + (int) Math.round(Math.cos(t) * 2.4), wy = top + 4 + (int) Math.round(Math.sin(t) * 2.4);
            c.set(wx, wy, z, "dark_oak_fence");
        }
        c.set(x, top + 2, z, "dark_oak_fence");
        c.set(x, top + 3, z, "dark_oak_fence");
        c.set(x, top + 4, z, "iron_block");
        for (int yy = y + 3; yy < top + 4; yy++) if (yy > top) continue; else if (c.isAir(x, yy, z)) c.set(x, yy, z, "iron_chain[axis=y]");
        c.set(x, y + 2, z, "iron_bars");
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(x + dx, y, z + dz, dx == 0 && dz == 0 ? "iron_block" : "polished_andesite");
        c.set(x, y + 1, z, "chest[facing=south]");
    }

    /** A timber crane at the pit rim, its jib out over the pit with a chain and a bucket. */
    private static void crane(VoxelCanvas c, int x, int y, int z, long seed) {
        for (int yy = y + 1; yy <= y + 9; yy++) c.set(x, yy, z, "stripped_spruce_log[axis=y]");
        c.set(x - 1, y + 1, z, Blocks.stairs("spruce", "east"));
        c.set(x + 1, y + 1, z, Blocks.stairs("spruce", "west"));
        for (int dz = 2; dz >= -7; dz--) c.set(x, y + 10, z + dz, "spruce_log[axis=z]");
        c.set(x, y + 9, z + 2, "barrel[facing=up,open=false]");
        for (int yy = y + 9; yy >= y + 2; yy--) c.set(x, yy, z - 7, yy == y + 2 ? "cauldron" : "iron_chain[axis=y]");
    }
}
