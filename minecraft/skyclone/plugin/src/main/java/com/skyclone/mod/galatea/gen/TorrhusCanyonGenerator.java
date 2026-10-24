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
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * TORRENT CANYON (ref. Torrhus Canyon, research 03 §24): Galatea's second island, a sandstone canyon
 * for Heart of the Forest IV foragers.
 * <ul>
 *   <li><b>Canyon Camp</b> (south rim, spawn): the journal hall, the contest pavilion, the safari
 *       office, the arches home (Hub) and back to the Moonmarsh.</li>
 *   <li><b>Torrent Springs</b> (canyon floor): a deep spring pool with a shallow ring (Spring
 *       Shallows) round a deep middle (Spring Depths) - the main fishing water.</li>
 *   <li><b>Spring Path</b>: the one gentle ramp up the steep north wall, to the <b>Torrent Heights</b>
 *       plateau of spiral trees and honey hives, where the Critter Safari gate stands.</li>
 *   <li><b>Ant's Cave</b> under the south wall (the Queen Ant), the sealed <b>Desert Temple</b> on
 *       the west floor and <b>Hotspot Haven</b>, a palm-ringed pool at the east end.</li>
 * </ul>
 * Pure: gen toolkit only. Preview: {@code bash tools/preview.sh com.skyclone.mod.galatea.gen.TorrhusCanyonGenerator}.
 */
public final class TorrhusCanyonGenerator implements IslandGenerator {

    public static final String ID = "torrhus_canyon";
    static final int FY = 67;               // canyon floor
    static final int W = FY - 1;            // spring water surface
    static final int RIM = 87;              // south rim (camp)
    static final int HEIGHTS = 100;         // north plateau
    static final int RAMP_X = -131;         // centre of the Spring Path ramp
    static final int SPRING_X = 0;
    static final int SPRING_SPOT_X = -8;    // the bank fishing spot (and its wading shelf)
    static final int HAVEN_X = 122;
    static final int TEMPLE_X = -80;
    static final int ANT_X = 52;

    @Override public String islandId() { return ID; }

    @Override public int version() { return 2; }   // 2: wading shelves at the bank fishing spots

    @Override public Box bounds() { return new Box(-200, 0, -175, 200, 175, 175); }

    private Terrain.Heightmap h;
    private int[] depth;
    private boolean[] step;                 // dry steps out of the wading shelves (top = W)
    private long seed;
    private Noise noise;

    static double zc(int x) { return 6 + 18 * Math.sin(x / 62.0); }

    double halfFloor(int x) { return 24 + 5 * noise.noise2(x / 40.0, 3.7); }

    double rampWeight(int x) { return GKit.smooth(1 - Math.abs(x - RAMP_X) / 24.0); }

    private int di(int x, int z) {
        int ix = x - h.minX, iz = z - h.minZ;
        if (ix < 0 || iz < 0 || ix >= h.width || iz >= h.depth) return -1;
        return iz * h.width + ix;
    }

    boolean wet(int x, int z) { int i = di(x, z); return i >= 0 && depth[i] > 0; }

    /** Signed distance (blocks) from the canyon floor edge: <0 = on the floor, >0 north or south of it. */
    int wallDist(int x, int z) { return (int) Math.round(Math.abs(z - zc(x)) - halfFloor(x)); }

    boolean north(int x, int z) { return z < zc(x); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        this.seed = seed;
        this.noise = new Noise(Hash.salt(seed, "canyon"));
        // ------------------------------------------------------------ 1. shape and the canyon cut
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 178; s.radiusZ = 150; s.baseY = HEIGHTS;
        s.hills = 3; s.hillScale = 44; s.cliff = 0.92; s.coastDrop = 2;
        s.coastNoise = 0.1; s.coastScale = 46; s.undersideDepth = 64; s.undersideNoise = 0.55;
        h = Terrain.island(s);
        h.forEach((x, z, top, bottom) -> {
            double bump = (top - HEIGHTS);
            double d = Math.abs(z - zc(x)) - halfFloor(x);
            int ny;
            if (d <= 0) ny = FY + (noise.noise2(x / 9.0, z / 9.0) > 0.45 ? 1 : 0);
            else if (north(x, z)) {
                double steep = Math.floor(33 * GKit.clamp(d / 15.0, 0, 1) / 3.0) * 3;
                double gentle = 33 * GKit.smooth(d / 56.0);
                double g = rampWeight(x);
                ny = (int) Math.round(FY + steep * (1 - g) + gentle * g + bump * GKit.clamp(d / 30.0, 0, 1));
            } else {
                double rise = (RIM - FY) * GKit.smooth(d / 40.0);
                ny = (int) Math.round(FY + rise + bump * 0.6 * GKit.clamp((d - 20) / 30.0, 0, 1));
            }
            h.setTop(x, z, ny);
        });
        Box camp = Box.of(-64, 0, 70, 24, 0, 120);
        h.flatten(camp, RIM, 10);

        // ------------------------------------------------------------ 2. the springs and the haven
        depth = new int[h.width * h.depth];
        double sz = zc(SPRING_X), hz = zc(HAVEN_X);
        h.forEach((x, z, top, bottom) -> {
            if (!h.inland(x, z, 10)) return;
            double e = sq((x - SPRING_X) / 50.0) + sq((z - sz) / (17.0 * (1 + 0.1 * noise.noise2(x / 12.0, 1.3))));
            int d = 0;
            if (e < 1) d = e > 0.55 ? 4 : 4 + (int) Math.round(22 * GKit.smooth((0.55 - e) / 0.55));
            double eh = Math.hypot(x - HAVEN_X, z - hz) / (11.0 * (1 + 0.12 * noise.noise2(x / 5.0, z / 5.0)));
            if (eh < 1) d = Math.max(d, 1 + (int) Math.round(4 * (1 - eh * eh)));
            if (d > 0) depth[di(x, z)] = d;
        });
        // wading shelves in front of both bank spots: knockback lands you ankle-deep, never on the
        // bottom of the 4-deep ring (playtest: three drownings at the Springs spot, see GKit.wadingShelf)
        int bankZ = (int) Math.round(sz + 19);
        GKit.wadingShelf(h, depth, SPRING_SPOT_X, bankZ, 10, 1);
        GKit.wadingShelf(h, depth, HAVEN_X - 14, (int) hz, 6, 1);
        step = new boolean[depth.length];
        GKit.beachStep(h, depth, step, SPRING_SPOT_X, bankZ, 10);
        GKit.beachStep(h, depth, step, HAVEN_X - 14, (int) hz, 6);
        h.forEach((x, z, top, bottom) -> { int i = di(x, z); if (depth[i] > 0) h.setTop(x, z, W - depth[i]); else if (step[i]) h.setTop(x, z, W); });
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z) || step[di(x, z)]) return;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                if (wet(x + dx, z + dz) && top < W + 1) { h.setTop(x, z, W + 1); return; }
        });
        GKit.easeAroundSteps(h, step, W + 1);

        // ------------------------------------------------------------ 3. paint and pour
        paint(c);
        h.forEach((x, z, top, bottom) -> {
            if (!wet(x, z)) return;
            for (int y = top + 1; y <= W; y++) c.set(x, y, z, "water");
            h.setWet(x, z, true);
        });

        Reservations res = new Reservations();
        // ------------------------------------------------------------ 4. Canyon Camp
        Box plaza = Box.of(-34, 0, 80, -6, 0, 100);
        res.reserve("plaza", plaza);
        Palette paving = Palette.of("smooth_sandstone:4,cut_sandstone:3,sandstone:2,terracotta:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++) for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, RIM, z, paving.at(x, RIM, z));
        Builders.well(c, -20, RIM, 90);

        Builders.House hall = new Builders.House(-58, RIM, 76, 13, 9);
        hall.door = "east"; hall.roof = "acacia"; hall.roofStyle = "hip"; hall.seed = seed;
        hall.wall = Palette.of("smooth_sandstone:3,cut_sandstone:2,orange_terracotta:1");
        hall.frame = "stripped_acacia_log"; hall.foundation = Palette.of("sandstone:3,terracotta:1");
        hall.floor = Fill.of("acacia_planks"); hall.doorWood = "acacia";
        boolean hallOk = res.reserve("journal_hall", hall.reserve());
        if (hallOk) hall.build(c);

        Builders.House office = new Builders.House(-31, RIM, 105, 11, 8);
        office.door = "north"; office.roof = "jungle"; office.roofStyle = "gable"; office.seed = seed + 2;
        office.wall = Palette.of("bamboo_planks:3,cut_sandstone:2");
        office.frame = "jungle_log"; office.doorWood = "jungle";
        boolean officeOk = res.reserve("safari_office", office.reserve());
        if (officeOk) office.build(c);

        int[] pavilion = GKit.gazebo(c, 6, RIM, 90, "acacia", Palette.of("cut_red_sandstone:3,smooth_red_sandstone:1"));
        res.reserve("pavilion", Box.of(2, 0, 86, 10, 0, 94));

        res.reserve("arches", Box.of(-6, 0, 106, 22, 0, 118));
        Palette pad = Palette.of("cut_sandstone:3,smooth_sandstone:2,chiseled_sandstone:1");
        for (int x = -6; x <= 22; x++) for (int z = 104; z <= 118; z++) c.set(x, RIM, z, pad.at(x, RIM, z));
        GKit.portalArch(c, layout, "hub", 2, RIM, 114, false, Palette.of("cut_sandstone:3,chiseled_sandstone:1"), "orange");
        GKit.portalArch(c, layout, "moonmarsh", 15, RIM, 114, false, Palette.of("mossy_stone_bricks:3,mud_bricks:1"), "green");
        for (int[] lp : new int[][]{{-34, 80}, {-6, 80}, {-34, 100}, {-6, 100}, {-3, 108}, {20, 108}})
            Builders.lampPost(c, lp[0], RIM, lp[1], "street", lp[0] < -20 ? "east" : "west");
        Builders.bench(c, -26, RIM, 82, "north", 3, "acacia");
        Builders.bench(c, -16, RIM, 82, "north", 3, "acacia");
        Builders.crates(c, -9, RIM, 96, seed);
        Builders.campfire(c, -28, RIM, 95);
        // an overlook rail on the canyon side of the camp
        for (int x = -40; x <= 10; x++) {
            int z = 70;
            int g = h.top(x, z);
            if (g != RIM || !c.isAir(x, g + 1, z) || Math.abs(x + 20) <= 2) continue;
            c.set(x, g + 1, z, "acacia_fence");
        }

        // ------------------------------------------------------------ 5. the Desert Temple (sealed)
        int tz = (int) Math.round(zc(TEMPLE_X));
        int[] templeTop = temple(c, TEMPLE_X, FY, tz);
        res.reserve("temple", Box.of(TEMPLE_X - 11, 0, tz - 11, TEMPLE_X + 11, 0, tz + 11));

        // ------------------------------------------------------------ 6. Ant's Cave
        int az = (int) Math.round(zc(ANT_X) + halfFloor(ANT_X)) - 2;
        Palette antFloor = Palette.of("coarse_dirt:3,rooted_dirt:2,packed_mud:1,gravel:1");
        List<int[]> antCells = GKit.tunnel(c, new int[][]{{ANT_X, FY, az}, {ANT_X + 2, FY - 3, az + 12}, {ANT_X + 4, FY - 7, az + 24}, {ANT_X + 4, FY - 9, az + 32}}, 2.2, 4, antFloor);
        int chX = ANT_X + 4, chZ = az + 44, chY = FY - 9;
        GKit.chamber(c, chX, chY, chZ, 14, 12, 8, antFloor);
        for (int i = 3; i < antCells.size(); i += 12) {
            int[] f = antCells.get(i);
            if (c.isAir(f[0], f[1] + 3, f[2])) c.set(f[0], f[1] + 3, f[2], Blocks.lantern(true));
            c.set(f[0], f[1] + 4, f[2], "shroomlight");
        }
        for (int[] p : new int[][]{{-8, -6}, {8, -5}, {-9, 5}, {7, 6}, {0, -9}}) {
            int x = chX + p[0], z = chZ + p[1];
            c.set(x, chY + 1, z, "honeycomb_block");
            c.set(x, chY + 2, z, "shroomlight");
        }
        int[] antNook = antCells.isEmpty() ? null : antCells.get(antCells.size() / 2);

        // ------------------------------------------------------------ 7. the Heights: spiral grove, hives, safari gate
        List<int[]> helix = new ArrayList<>();
        long hs = Hash.salt(seed, "helix");
        for (int z = -146; z <= -48; z++)
            for (int x = -110; x <= 40; x++) {
                if (!Hash.chance(hs, x, z, 0.012)) continue;
                if (!h.has(x, z) || !h.inland(x, z, 6) || wallDist(x, z) < 22 || !north(x, z) || h.slope(x, z) > 1) continue;
                boolean close = false;
                for (int[] p : helix) if (Math.abs(p[0] - x) < 11 && Math.abs(p[2] - z) < 11) { close = true; break; }
                if (close) continue;
                spiralTree(c, x, h.top(x, z), z, Hash.unit(hs, x, 5, z));
                helix.add(new int[]{x, h.top(x, z), z});
            }
        for (int[] p : helix) res.reserve("helix" + p[0] + "_" + p[2], Box.of(p[0] - 4, 0, p[2] - 4, p[0] + 4, 0, p[2] + 4));

        List<int[]> hives = new ArrayList<>();
        for (int[] p : new int[][]{{58, -96}, {66, -104}, {74, -94}, {62, -84}, {78, -108}, {70, -82}}) {
            int[] g = GKit.groundNear(h, p[0], p[1], 5, 4);
            if (g == null || res.covers(g[0], g[2], 2)) continue;
            c.set(g[0], g[1] + 1, g[2], "stripped_oak_log[axis=y]");
            c.set(g[0], g[1] + 2, g[2], "stripped_oak_log[axis=y]");
            c.set(g[0], g[1] + 3, g[2], "bee_nest[facing=south,honey_level=5]");
            c.set(g[0], g[1] + 4, g[2], Blocks.slab("oak", "bottom"));
            res.reserve("hive" + hives.size(), Box.of(g[0] - 1, 0, g[2] - 1, g[0] + 1, 0, g[2] + 1));
            hives.add(g);
        }
        int[] gate = GKit.groundNear(h, 118, -92, 10, 8);
        if (gate != null) safariGate(c, gate[0], gate[1], gate[2]);
        if (gate != null) res.reserve("safari_gate", Box.of(gate[0] - 7, 0, gate[2] - 8, gate[0] + 7, 0, gate[2] + 2));

        // ------------------------------------------------------------ 8. paths
        Palette walk = Palette.of("dirt_path:4,coarse_dirt:2,red_sand:1,smooth_sandstone:1");
        int floorZ = (int) Math.round(zc(-6));
        Builders.PathResult paths = Builders.path(c, new int[][]{{-20, 80}, {-20, 66}, {-14, 52}, {-8, (int) (sz + 24)}, {-8, (int) (sz + 19)}}, 3, walk, "sandstone_slab", null);
        if (hallOk) Builders.path(c, new int[][]{{-34, 84}, {hall.doorStep[0] + 1, hall.doorStep[2]}}, 3, walk, "sandstone_slab", paths);
        if (officeOk) Builders.path(c, new int[][]{{office.doorStep[0], 100}, {office.doorStep[0], office.doorStep[2] - 1}}, 3, walk, "sandstone_slab", paths);
        Builders.path(c, new int[][]{{-6, 90}, {pavilion[0] - 4, 90}}, 3, walk, "sandstone_slab", paths);
        Builders.path(c, new int[][]{{-20, 100}, {-20, 104}, {8, 106}}, 3, walk, "sandstone_slab", paths);
        // along the canyon floor, west to the temple and the ramp, east to the ants and the haven
        int fw = (int) Math.round(zc(-60));
        Builders.path(c, new int[][]{{-8, (int) (sz + 22)}, {-56, fw + 18}, {TEMPLE_X + 14, tz + 4}, {-108, (int) zc(-108) + 4}, {RAMP_X + 8, (int) zc(RAMP_X + 8)}}, 3, walk, "sandstone_slab", paths);
        Builders.path(c, new int[][]{{-8, (int) (sz + 22)}, {30, (int) zc(30) + 18}, {ANT_X, az - 2}}, 3, walk, "sandstone_slab", paths);
        Builders.path(c, new int[][]{{30, (int) zc(30) + 18}, {78, (int) zc(78) + 10}, {HAVEN_X - 15, (int) hz}}, 3, walk, "sandstone_slab", paths);
        // the Spring Path: the gentle ramp up the north wall
        Builders.path(c, new int[][]{{RAMP_X + 8, (int) zc(RAMP_X + 8)}, {RAMP_X, (int) zc(RAMP_X) - 30}, {RAMP_X + 2, (int) zc(RAMP_X) - 62},
                {-100, -96}, {-60, -110}, {-20, -100}, {20, -98}, {56, -90}}, 3, walk, "sandstone_slab", paths);
        if (gate != null) Builders.path(c, new int[][]{{56, -90}, {90, -86}, {gate[0], gate[2] + 3}}, 3, walk, "sandstone_slab", paths);

        // ------------------------------------------------------------ 9. plants
        long ps = Hash.salt(seed, "palms");
        List<int[]> palms = new ArrayList<>();
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z) || !Hash.chance(ps, x, z, 0.08)) return;
            boolean nearHaven = Math.hypot(x - HAVEN_X, z - hz) < 20, nearSpring = sq((x - SPRING_X) / 58.0) + sq((z - sz) / 24.0) < 1;
            if (!(nearHaven || nearSpring) || top != W + 1 && top != FY || res.covers(x, z, 3) || paths.near(x, z, 2)) return;
            for (int[] p : palms) if (Math.abs(p[0] - x) < 7 && Math.abs(p[1] - z) < 7) return;
            if (!c.isAir(x, top + 1, z)) return;
            Trees.palm(c, x, top, z, seed, 0.9 + Hash.unit(ps, x, 2, z) * 0.4);
            palms.add(new int[]{x, z});
        });
        Trees.forest(c, h, Box.of(-170, 0, -150, 170, 0, -40), seed, 0.008, 9, "acacia:4,oak:1,dead:1", 6,
                (x, z) -> res.covers(x, z, 7) || paths.near(x, z, 3) || wallDist(x, z) < 12 || !north(x, z));
        Trees.forest(c, h, Box.of(-170, 0, 40, 170, 0, 150), seed + 9, 0.006, 10, "acacia:3,dead:2", 6,
                (x, z) -> res.covers(x, z, 7) || paths.near(x, z, 3) || wallDist(x, z) < 26 || north(x, z) || camp.grow(4).containsXZ(x, z));
        cover(c, res, paths);
        int[][] hotspots = {{HAVEN_X - 3, (int) hz - 2}, {HAVEN_X + 4, (int) hz + 3}};
        springPlants(c, hotspots);
        for (int[] p : new int[][]{{-12, 56}, {-30, (int) sz + 22}, {-70, fw + 14}, {-112, (int) zc(-112) + 2}, {RAMP_X + 4, (int) zc(RAMP_X) - 40},
                {-96, -100}, {-40, -108}, {10, -100}, {40, (int) zc(40) + 14}, {90, (int) zc(90) + 8}}) {
            int x = p[0] + 3, z = p[1];
            int g = h.top(x, z);
            if (g == Terrain.Heightmap.NONE || wet(x, z) || paths.covers(x, z) || !c.isAir(x, g + 1, z)) continue;
            Builders.lampPost(c, x, g, z, "post", "north");
        }

        // ------------------------------------------------------------ 10. anchors
        layout.standAt("spawn", -20, RIM, 85, 180f);
        layout.standAt("fishing.spot.springs", SPRING_SPOT_X, h.top(SPRING_SPOT_X, bankZ), bankZ, 180f);
        int[] havenSpot = GKit.groundNear(h, HAVEN_X - 14, (int) hz, 5, 2);
        if (havenSpot != null) layout.standAt("fishing.spot.haven", havenSpot[0], havenSpot[1], havenSpot[2], -90f);
        for (int i = 0; i < hotspots.length; i++) layout.standAt("fishing.hotspot." + (i + 1), hotspots[i][0], W - 1, hotspots[i][1], 0f);
        layout.data("walk.ignore", "fishing.hotspot.,tree.helix.");
        layout.standAt("warp.springs", -8, h.top(-8, bankZ + 2), bankZ + 2, 180f);
        if (gate != null) layout.standAt("warp.safari", gate[0], h.top(gate[0], gate[2] + 3), gate[2] + 3, 180f);

        if (hallOk) layout.standAt("npc.calla", hall.doorStep[0] - 2, hall.doorStep[1], hall.doorStep[2], -90f);
        layout.standAt("npc.odalys", pavilion[0] - 1, pavilion[1], pavilion[2] - 1, 90f);
        if (officeOk) layout.standAt("npc.safari_clerk", office.doorStep[0], office.doorStep[1], office.doorStep[2] + 2, 180f);
        layout.standAt("npc.ant_foreman", chX + 3, chY, chZ - 3, 0f);
        if (!hives.isEmpty()) { int[] g = GKit.groundNear(h, hives.get(0)[0] + 2, hives.get(0)[2] + 2, 4, 3); if (g != null) layout.standAt("npc.beekeeper", g[0], g[1], g[2], 0f); }
        int[] hunter = GKit.groundNear(h, -96, -92, 8, 4);
        if (hunter != null) layout.standAt("npc.hunter", hunter[0], hunter[1], hunter[2], 90f);
        int[] forager = GKit.groundNear(h, -36, -104, 8, 4);
        if (forager != null) layout.standAt("npc.forager", forager[0], forager[1], forager[2], 0f);
        layout.standAt("npc.diver", -4, h.top(-4, bankZ), bankZ, 180f);
        if (gate != null) layout.standAt("npc.safari_receptionist", gate[0] - 3, h.top(gate[0] - 3, gate[2] + 2), gate[2] + 2, 180f);

        for (int i = 0; i < helix.size(); i++) layout.anchor("tree.helix." + (i + 1), helix.get(i)[0] + 0.5, helix.get(i)[1] + 1, helix.get(i)[2] + 0.5, 0f);

        List<int[]> souls = new ArrayList<>();
        if (templeTop != null) souls.add(templeTop);                                       // top of the temple
        if (havenSpot != null) { int[] g = GKit.groundNear(h, HAVEN_X + 14, (int) hz + 6, 6, 2); if (g != null) souls.add(g); }
        souls.add(new int[]{chX - 6, chY, chZ + 6});                                         // the ant chamber
        if (antNook != null) souls.add(antNook);                                             // the ant tunnel
        if (!helix.isEmpty()) souls.add(GKit.groundNear(h, helix.get(0)[0] + 3, helix.get(0)[2] + 3, 5, 2));
        if (!hives.isEmpty()) souls.add(GKit.groundNear(h, hives.get(hives.size() - 1)[0] + 2, hives.get(hives.size() - 1)[2], 4, 2));
        if (gate != null) souls.add(GKit.groundNear(h, gate[0] + 5, gate[2] + 4, 5, 2));
        souls.add(GKit.groundNear(h, RAMP_X - 6, (int) zc(RAMP_X) - 40, 6, 2));                 // halfway up the ramp
        souls.add(GKit.groundNear(h, 60, 74, 8, 3));                                         // the east rim
        if (hallOk) souls.add(GKit.groundNear(h, hall.x - 4, hall.z + 4, 6, 2));             // behind the journal hall
        souls.add(GKit.groundNear(h, 100, (int) zc(100) + 4, 6, 2));                           // the east floor
        souls.add(GKit.groundNear(h, -150, (int) zc(-150) + 8, 8, 2));                         // the far west floor
        int k = 0;
        for (int[] p : souls) if (p != null) layout.standAt("fairy_soul." + (++k), p[0], p[1], p[2], 0f);

        // ------------------------------------------------------------ 11. zones and areas
        Box isle = h.box();
        layout.zone(ID, "Torrent Canyon", '6', new Box(isle.minX(), 0, isle.minZ(), isle.maxX(), 255, isle.maxZ()), null);
        layout.zone("torrhus_canyon_camp", "Canyon Camp", 'e', Box.of(camp.minX(), RIM - 8, camp.minZ(), camp.maxX(), RIM + 40, camp.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("torrhus_canyon_springs", "Torrent Springs", 'b', Box.of(SPRING_X - 60, W - 30, (int) sz - 26, SPRING_X + 60, W + 14, (int) sz + 26), null);
        layout.zone("torrhus_canyon_shallows", "Spring Shallows", '3', Box.of(SPRING_X - 51, W - 5, (int) sz - 18, SPRING_X + 51, W, (int) sz + 18), null);
        layout.zone("torrhus_canyon_depths", "Spring Depths", '1', Box.of(SPRING_X - 38, W - 28, (int) sz - 13, SPRING_X + 38, W - 6, (int) sz + 13), null);
        layout.zone("torrhus_canyon_path", "Spring Path", 'a', Box.of(RAMP_X - 26, FY - 2, (int) zc(RAMP_X) - 70, RAMP_X + 26, HEIGHTS + 4, (int) zc(RAMP_X) - 20), null);
        layout.zone("torrhus_canyon_heights", "Torrent Heights", '2', Box.of(isle.minX(), HEIGHTS - 8, isle.minZ(), isle.maxX(), 175, -40), null);
        layout.zone("torrhus_canyon_ants", "Ant's Cave", '8', Box.of(ANT_X - 16, chY - 3, az + 2, ANT_X + 22, FY + 6, chZ + 14), null);
        layout.zone("torrhus_canyon_temple", "Desert Temple", 'e', Box.of(TEMPLE_X - 13, FY - 2, tz - 13, TEMPLE_X + 13, FY + 20, tz + 13), null);
        layout.zone("torrhus_canyon_haven", "Hotspot Haven", 'd', Box.of(HAVEN_X - 22, W - 8, (int) hz - 22, HAVEN_X + 22, FY + 20, (int) hz + 22), null);
        if (gate != null) layout.zone("torrhus_canyon_safari", "Critter Safari Entrance", 'a', Box.of(gate[0] - 10, gate[1] - 4, gate[2] - 10, gate[0] + 10, gate[1] + 12, gate[2] + 6), null);

        layout.area("springs_water", Box.of(SPRING_X - 46, W, (int) sz - 14, SPRING_X + 46, W, (int) sz + 14));
        layout.area("haven_water", Box.of(HAVEN_X - 9, W, (int) hz - 9, HAVEN_X + 9, W, (int) hz + 9));
        layout.area("mobs.parched.west", Box.of(-160, FY, fw - 30, -96, FY + 4, fw + 30));
        layout.area("mobs.parched.east", Box.of(64, FY, (int) zc(80) - 18, 104, FY + 4, (int) zc(80) + 18));
        layout.area("mobs.grizzly", Box.of(-170, HEIGHTS - 6, -150, -110, HEIGHTS + 14, -80));
        layout.area("mobs.ants", Box.of(chX - 11, chY + 1, chZ - 9, chX + 11, chY + 3, chZ + 9));
        layout.area("helix_grove", Box.of(-110, HEIGHTS - 10, -146, 40, HEIGHTS + 30, -48));
        layout.area("honey_hives", Box.of(50, HEIGHTS - 10, -116, 86, HEIGHTS + 20, -76));
        layout.area("temple", Box.of(TEMPLE_X - 11, FY, tz - 11, TEMPLE_X + 11, FY + 14, tz + 11));
        layout.data("reservations", res.summary());
    }

    // ================================================================================ pieces

    /** A stepped sandstone temple, its door sealed with chiselled stone. Returns the top block to stand on. */
    private int[] temple(VoxelCanvas c, int cx, int y, int cz) {
        Palette stone = Palette.of("sandstone:4,cut_sandstone:3,chiseled_sandstone:1,smooth_sandstone:2");
        int top = y;
        for (int layer = 0; layer < 9; layer++) {
            int r = 10 - layer;
            int ly = y + layer;
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                c.set(cx + dx, ly, cz + dz, stone.at(cx + dx, ly, cz + dz));
                if (layer == 0) Builders.footing(c, cx + dx, ly, cz + dz, Fill.of("sandstone"), 8);
                for (int a = 1; a <= 3; a++) if (layer == 0 && Builders.natural(c.get(cx + dx, ly + a, cz + dz)) && Math.max(Math.abs(dx), Math.abs(dz)) == r) c.set(cx + dx, ly + a, cz + dz, "air");
            }
            top = ly;
        }
        // the sealed doorway on the canyon-facing side, and the glyph band above it
        for (int dx = -1; dx <= 1; dx++) for (int a = 1; a <= 3; a++) c.set(cx + dx, y + a, cz + 9, "chiseled_red_sandstone");
        c.set(cx, y + 4, cz + 8, "chiseled_sandstone");
        c.set(cx - 2, y + 2, cz + 8, Blocks.wallTorch("south"));
        c.set(cx + 2, y + 2, cz + 8, Blocks.wallTorch("south"));
        c.set(cx, top + 1, cz, "air");
        c.set(cx + 1, top + 1, cz + 1, Blocks.lantern(false));
        return new int[]{cx, top, cz};
    }

    /** A spiral tree: a red core trunk wrapped in a pale corkscrew, with a pink crown. */
    private void spiralTree(VoxelCanvas c, int x, int y, int z, double u) {
        int ht = 10 + (int) (u * 6);
        for (int i = 1; i <= ht; i++) c.set(x, y + i, z, "stripped_mangrove_log[axis=y]");
        for (int i = 1; i <= ht - 1; i++) {
            double a = i * 0.9 + u * 6;
            int dx = (int) Math.round(Math.cos(a)), dz = (int) Math.round(Math.sin(a));
            if (dx == 0 && dz == 0) continue;
            c.set(x + dx, y + i, z + dz, "stripped_birch_log[axis=y]");
        }
        String leaves = Blocks.leaves("cherry");
        int cy = y + ht;
        for (int dy = -1; dy <= 2; dy++) {
            int r = dy == 2 ? 1 : dy == -1 ? 2 : 3;
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r) continue;
                if (c.isAir(x + dx, cy + dy, z + dz)) c.set(x + dx, cy + dy, z + dz, leaves);
            }
        }
        c.set(x, cy + 1, z, "stripped_mangrove_log[axis=y]");
    }

    /** The Critter Safari gate: two jungle-log towers with a sign beam and a closed fence across. */
    private void safariGate(VoxelCanvas c, int x, int y, int z) {
        for (int dz = -6; dz <= 1; dz++) for (int dx = -6; dx <= 6; dx++) {
            c.set(x + dx, y, z + dz, "coarse_dirt");
            Builders.footing(c, x + dx, y, z + dz, Fill.of("dirt"), 10);
            for (int a = 1; a <= 7; a++) if (Builders.natural(c.get(x + dx, y + a, z + dz))) c.set(x + dx, y + a, z + dz, "air");
        }
        for (int side : new int[]{-4, 4}) {
            for (int a = 1; a <= 7; a++) {
                c.set(x + side, y + a, z - 2, "jungle_log[axis=y]");
                c.set(x + side, y + a, z - 3, "jungle_log[axis=y]");
            }
            c.set(x + side, y + 8, z - 2, Blocks.lantern(false));
        }
        for (int dx = -5; dx <= 5; dx++) c.set(x + dx, y + 7, z - 2, "jungle_log[axis=x]");
        for (int dx = -3; dx <= 3; dx++) c.set(x + dx, y + 6, z - 3, "orange_wool");
        for (int dx = -3; dx <= 3; dx++) { c.set(x + dx, y + 1, z - 3, "spruce_fence"); c.set(x + dx, y + 2, z - 3, "spruce_fence"); }
        Builders.wallBanner(c, x - 4, y + 5, z - 1, "lime", "south");
        Builders.wallBanner(c, x + 4, y + 5, z - 1, "lime", "south");
    }

    private static double sq(double v) { return v * v; }

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
        Palette floor = Palette.of("red_sand:5,sand:3,coarse_dirt:1,smooth_red_sandstone:1");
        Palette bank = Palette.of("sand:4,moss_block:1,grass_block:2");
        Palette bed = Palette.of("sand:4,clay:2,gravel:2,prismarine:1");
        Palette deep = Palette.of("prismarine:2,dark_prismarine:1,gravel:2,clay:1,tuff:1");
        Palette heights = Palette.of("grass_block:8,coarse_dirt:2,podzol:1,moss_block:1");
        Palette rim = Palette.of("grass_block:6,coarse_dirt:3,red_sand:1,podzol:1");
        Palette strata = Palette.of("terracotta:2,orange_terracotta:2,red_sandstone:3,smooth_red_sandstone:1");
        Palette band2 = Palette.of("sandstone:3,cut_sandstone:1,smooth_sandstone:1");
        Palette band3 = Palette.of("brown_terracotta:2,red_terracotta:1,terracotta:2");
        Palette under = Palette.of("sandstone:4,red_sandstone:3,terracotta:2,tuff:2,stone:2");
        h.forEach((x, z, top, bottom) -> {
            boolean wetCol = wet(x, z);
            int slope = wetCol ? 0 : drySlope(x, z);
            double d = Math.abs(z - zc(x)) - halfFloor(x);
            String surface;
            if (wetCol) surface = (W - top >= 8 ? deep : bed).at(x, top, z);
            else if (slope >= 2 && d > 0) surface = null;
            else if (d <= 0) surface = top == W + 1 || top == W ? bank.at(x, top, z) : floor.at(x, top, z);
            else if (north(x, z)) surface = heights.at(x, top, z);
            else surface = rim.at(x, top, z);
            for (int y = bottom; y <= top; y++) {
                int dd = top - y;
                String b;
                if (y - bottom < 6 && dd > 3) b = under.at(x, y, z);
                else if (dd == 0 && surface != null) b = surface;
                else {
                    int band = Math.floorMod(y / 3 + (int) Math.round(noise.noise2(x / 50.0, z / 50.0) * 2), 3);
                    b = (band == 0 ? strata : band == 1 ? band2 : band3).at(x, y, z);
                    if (dd <= 1 && surface != null && !wetCol && d > 0) b = "dirt";
                }
                c.set(x, y, z, b);
            }
        });
    }

    private void cover(VoxelCanvas c, Reservations res, Builders.PathResult paths) {
        long s = Hash.salt(seed, "cover");
        Palette floorPlants = Palette.of("dead_bush:6,short_dry_grass:8,tall_dry_grass:3");
        Palette green = Palette.of("short_grass:16,fern:3,bush:3,wildflowers[facing=north,flower_amount=2]:2,leaf_litter:2,firefly_bush:1,red_mushroom:1");
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z) || paths.covers(x, z) || res.covers(x, z, 0) || !c.isAir(x, top + 1, z)) return;
            String g = BlockState.name(c.get(x, top, z));
            double u = Hash.unit(s, x, z);
            if (g.equals("red_sand") || g.equals("sand") && top == FY) {
                if (u < 0.006 && c.isAir(x, top + 3, z) && Hash.unit(s, x + 1, z) > 0.5) {
                    int ht = 2 + (int) (Hash.unit(s, x, 4, z) * 2);
                    boolean ok = true;
                    for (int d = 0; d < 4; d++) if (!c.isAir(x + Blocks.DX[d], top + 1, z + Blocks.DZ[d])) ok = false;
                    if (ok) { for (int i = 1; i <= ht; i++) c.set(x, top + i, z, "cactus"); return; }
                }
                if (u < 0.08) c.set(x, top + 1, z, floorPlants.pick(s, x, top, z));
                return;
            }
            if (g.equals("grass_block") || g.equals("coarse_dirt") || g.equals("podzol")) {
                if (u < 0.3) c.set(x, top + 1, z, green.pick(s, x, top, z));
            }
        });
    }

    private void springPlants(VoxelCanvas c, int[][] hotspots) {
        long s = Hash.salt(seed, "spring-plants");
        h.forEach((x, z, top, bottom) -> {
            if (!wet(x, z)) return;
            int d = W - top;
            boolean clear = true;
            for (int[] hs : hotspots) if (Math.abs(hs[0] - x) <= 4 && Math.abs(hs[1] - z) <= 4) { clear = false; break; }
            double u = Hash.unit(s, x, z);
            if (clear && d <= 4 && u < 0.05 && c.isAir(x, W + 1, z)) c.set(x, W + 1, z, "lily_pad");
            if (d >= 2 && BlockState.name(c.get(x, top + 1, z)).equals("water")) {
                double v = Hash.unit(s, x, 3, z);
                if (v < 0.25) c.set(x, top + 1, z, "seagrass");
                else if (d >= 8 && v < 0.3) c.set(x, top + 1, z, "sea_pickle[pickles=4,waterlogged=true]");
            }
        });
    }
}
