package com.skyclone.mod.rift.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;
import com.skyclone.gen.util.Pack;
import com.skyclone.mod.rift.RiftCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * The Rift: a warped mirror of the main world on one big island, ~490 x 470 blocks.
 *
 * <pre>
 *            West Village        Dread Acres          The Mountaintop
 *       (Looking-Glass Halls        (farm)              (spiral road)
 *            underground)
 *        Black Lagoon      Wildwood Thicket (spawn)    Village Plaza   Living Cave (hill)
 *        (Leeches Lair)    Cradle, Tower, Great Tree
 *                          south woods        Colosseum     Grimhollow Chateau
 * </pre>
 * Every district is a zone with the research 19.1 gate as its requirement ({@code rift:gate:<id>});
 * the module resolves those. PURE: no Bukkit imports anywhere under this package.
 */
public final class RiftGenerator implements IslandGenerator {

    public static final String ID = RiftCatalog.ISLAND;

    @Override public String islandId() { return ID; }

    @Override public int version() { return 3; }   // 2: gallery exhibits + curator, Split or Steal host (wave 3); 3: riddle props, cat pieces, eyes (wave 5)

    @Override public Box bounds() { return new Box(-275, 8, -265, 275, 210, 265); }

    // district geometry (x, z)
    static final int[] CRADLE = {-25, 25};
    static final int[] TOWER = {-60, -30};
    static final int[] TREE = {-55, 65};
    static final int[] GALLERY = {2, 48};
    static final int[] POND = {10, -28};
    static final int[] CIRCLE = {28, 14};
    static final int[] LAGOON = {-145, 95};
    static final int[] HUT = {-125, 68};
    static final int[] LAIR = {-160, 128};
    static final int[] WEST = {-140, -85};
    static final int[] MIRROR_HOUSE = {-110, -104};
    static final int[] DREAD = {-5, -128};
    static final int[] PLAZA = {100, -20};
    static final int[] MOUNTAIN = {115, -118};
    static final int[] LIVING = {175, 45};
    static final int[] COLOSSEUM = {55, 145};
    static final int[] CHATEAU = {125, 120};

    static final int MOUNTAIN_R = 50, MOUNTAIN_H = 56;
    static final int LIVING_R = 34, LIVING_H = 26;
    static final int LAIR_R = 24, LAIR_H = 17;

    @Override
    public void generate(VoxelCanvas canvas, Layout layout, long seed) {
        RiftCtx g = new RiftCtx(canvas, layout, seed);

        // ---- 1. heightmap ---------------------------------------------------------------------
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 245; s.radiusZ = 235; s.baseY = RiftCtx.BASE;
        s.hills = 5; s.hillScale = 64; s.coastNoise = 0.08; s.coastScale = 50;
        s.cliff = 0.75; s.coastDrop = 3; s.undersideDepth = 44; s.undersideNoise = 0.4;
        g.h = Terrain.island(s);

        // causeways the lagoon must leave dry (the roads in and out of it)
        g.dryLines.add(new int[][]{{-60, 40}, {-90, 58}, {-116, 74}});
        g.dryLines.add(new int[][]{{-116, 74}, {-138, 100}, {-148, 112}});
        g.dryLines.add(new int[][]{{-116, 74}, {-130, 80}, {-152, 70}, {-170, 72}});
        g.dryLines.add(new int[][]{{-121, 68}, {-116, 74}});
        lagoon(g);
        bump(g, MOUNTAIN[0], MOUNTAIN[1], MOUNTAIN_R, MOUNTAIN_H, 0.0, 9, "mountain");
        bump(g, LIVING[0], LIVING[1], LIVING_R, LIVING_H, 0.42, 3, "living");
        bump(g, LAIR[0], LAIR[1], LAIR_R, LAIR_H, 0.45, 2, "lair");
        RiftBuild.flattenPlots(g);

        // ---- 2. terrain -------------------------------------------------------------------------
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.top = Palette.of("grass_block:7,moss_block:2,podzol:2,pale_moss_block:1");
        rules.soil = Palette.of("dirt:5,rooted_dirt:1,coarse_dirt:1");
        rules.stone = Palette.of("stone:8,tuff:3,deepslate:2,andesite:2");
        rules.cliff = Palette.of("stone:5,tuff:3,deepslate:2,calcite:1,cobbled_deepslate:1");
        rules.underside = Palette.of("deepslate:5,tuff:3,blackstone:2,crying_obsidian:1,amethyst_block:1");
        rules.snowAbove = 116;
        rules.steepSlope = 3;
        Terrain.fill(canvas, g.h, rules);
        repaint(g);
        lagoonWater(g);
        Terrain.pond(canvas, g.h, POND[0], POND[1], 9, 4, seed);

        // ---- 3. districts (additive structures; each carves its own interiors) ------------------
        RiftBuild.cradle(g);
        RiftBuild.wyld(g);
        RiftBuild.lagoon(g);
        RiftBuild.westVillage(g);
        RiftBuild.mirrorverse(g);
        RiftBuild.dreadfarm(g);
        RiftBuild.plaza(g);
        RiftBuild.livingCave(g);
        RiftBuild.colosseum(g);
        RiftBuild.chateau(g);
        RiftBuild.mountain(g);

        // ---- 4. roads ---------------------------------------------------------------------------
        roads(g);
        RiftBuild.afterRoads(g);

        // ---- 5. growth --------------------------------------------------------------------------
        flora(g);

        // ---- 6. souls nobody placed yet, mote orbs, zones ----------------------------------------
        g.looseMode = true;
        RiftBuild.looseSouls(g);
        RiftProps.place(g);
        motes(g);
        zones(g);
        layout.data("walk.ignore", "soul.,mote.,egg.,heart.,charm.supreme.top,mirror.parkour.end,race.,fairy_soul.,living.heart.home,riddle.,cat.,eye.");
        layout.data("reservations", g.res.summary());
    }

    // =========================================================================================
    // heightmap passes
    // =========================================================================================

    /** The Black Lagoon: flatten the district low and sink noise-shaped pools below the water line. */
    private static void lagoon(RiftCtx g) {
        Noise n = new Noise(Hash.salt(g.seed, "lagoon"));
        int r = 64;
        for (int z = LAGOON[1] - r; z <= LAGOON[1] + r; z++)
            for (int x = LAGOON[0] - r; x <= LAGOON[0] + r; x++) {
                if (!g.h.has(x, z)) continue;
                double d = Math.hypot(x - LAGOON[0], z - LAGOON[1]);
                if (d > r) continue;
                int top = g.h.top(x, z);
                double blend = Noise.smoothstep(r, r - 12, d);           // 1 inside, 0 at the rim
                int low = RiftCtx.WATER + 1;
                int ny = (int) Math.round(top + (low - top) * blend);
                double v = n.fbm2(x / 16.0, z / 16.0, 3);
                boolean pool = v > 0.02 && d < r - 10 && g.h.inland(x, z, 6) && g.distToDry(x, z) > 4.5;
                if (pool) ny = RiftCtx.WATER - 1 - (int) Math.min(3, Math.round((v - 0.02) * 9));
                g.h.setTop(x, z, ny);
            }
    }

    /**
     * Raise a hill or a mountain. {@code plateau} 0 = a peak, larger = a flat-topped mound (the
     * fraction of the radius where it reaches full height). Ridged noise roughens the flanks.
     */
    private static void bump(RiftCtx g, int cx, int cz, int r, int height, double plateau, int rough, String salt) {
        Noise n = new Noise(Hash.salt(g.seed, salt));
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++) {
                if (!g.h.has(x, z)) continue;
                double d = Math.hypot(x - cx, z - cz) / r;
                if (d >= 1) continue;
                double t = 1 - d;
                double shape = plateau <= 0 ? Math.pow(t, 1.25) : Noise.smoothstep(0, plateau, t);
                double add = height * shape + rough * n.fbm2(x / 13.0, z / 13.0, 3) * Math.min(1, t * 2.5);
                int top = g.h.top(x, z) + (int) Math.round(Math.max(0, add));
                g.h.set(x, z, top, g.h.bottom(x, z));
            }
    }

    /** District-specific surfaces on top of the default rules (only where the default top was laid). */
    private static void repaint(RiftCtx g) {
        Palette lagoonTop = Palette.of("mud:5,moss_block:3,muddy_mangrove_roots:1,grass_block:2");
        Palette dreadTop = Palette.of("coarse_dirt:4,podzol:3,rooted_dirt:2,grass_block:1");
        Palette chateauTop = Palette.of("podzol:3,coarse_dirt:2,soul_soil:1,grass_block:2,moss_block:1");
        Palette rockTop = Palette.of("stone:4,calcite:2,tuff:2,andesite:1,gravel:1");
        g.h.forEach((x, z, top, bottom) -> {
            String b = BlockState.name(g.c.get(x, top, z));
            if (!(b.equals("grass_block") || b.equals("moss_block") || b.equals("podzol") || b.equals("pale_moss_block"))) return;
            Palette p = null;
            if (Math.hypot(x - LAGOON[0], z - LAGOON[1]) < 60) p = lagoonTop;
            else if (Math.hypot(x - DREAD[0], z - DREAD[1]) < 62) p = dreadTop;
            else if (Math.hypot(x - CHATEAU[0], z - CHATEAU[1]) < 46) p = chateauTop;
            else if (top >= 92 && Math.hypot(x - MOUNTAIN[0], z - MOUNTAIN[1]) < MOUNTAIN_R) p = rockTop;
            if (p != null) g.c.set(x, top, z, p.at(x, top, z));
        });
    }

    /** Fill the lagoon's sunken pools: only columns well inside the district and away from the coast. */
    private static void lagoonWater(RiftCtx g) {
        for (int z = LAGOON[1] - 64; z <= LAGOON[1] + 64; z++)
            for (int x = LAGOON[0] - 64; x <= LAGOON[0] + 64; x++) {
                if (!g.h.has(x, z) || Math.hypot(x - LAGOON[0], z - LAGOON[1]) > 56 || !g.h.inland(x, z, 6)) continue;
                int t = g.h.top(x, z);
                if (t >= RiftCtx.WATER) continue;
                for (int y = t + 1; y <= RiftCtx.WATER; y++) g.c.set(x, y, z, "water");
                g.h.setWet(x, z, true);
            }
    }

    // =========================================================================================
    // roads
    // =========================================================================================

    private static final Palette ROAD = Palette.of("dirt_path:5,coarse_dirt:2,gravel:1,rooted_dirt:1");
    private static final Palette STONE_ROAD = Palette.of("stone_bricks:4,mossy_stone_bricks:2,cracked_stone_bricks:1,tuff_bricks:1");

    private static void road(RiftCtx g, int[][] pts, Fill surface) {
        g.paths = Builders.path(g.c, pts, 3, surface, "cobblestone_stairs", g.paths);
    }

    private static void roads(RiftCtx g) {
        int[] c = CRADLE;
        // the Cradle's spokes
        road(g, new int[][]{{c[0] - 10, c[1] + 2}, {-48, 24}, {-60, 40}, {-90, 58}, {-116, 74}}, ROAD);   // to the lagoon
        road(g, new int[][]{{-116, 74}, {-138, 100}, {-148, 112}}, ROAD);                               // lagoon to the Lair
        road(g, new int[][]{{-116, 74}, {-130, 80}, {-152, 70}, {-170, 72}}, ROAD);                     // lagoon west shore
        road(g, new int[][]{{c[0] - 8, c[1] - 9}, {-40, 0}, {-50, -24}}, ROAD);                         // to the Wizard Tower
        road(g, new int[][]{{-50, -24}, {-46, -44}, {-80, -56}, {-108, -72}, {-125, -82}}, ROAD);       // tower to West Village (around the tower)
        road(g, new int[][]{{c[0] + 8, c[1] - 8}, {-12, -10}, {-8, -60}, {-6, -96}}, ROAD);             // to Dread Acres
        road(g, new int[][]{{-6, -96}, {0, -110}, {8, -119}}, ROAD);                                    // to the barn
        road(g, new int[][]{{c[0] + 10, c[1] - 2}, {8, 12}, {42, -2}, {72, -14}}, ROAD);               // to the Plaza
        road(g, new int[][]{{c[0] - 2, c[1] + 10}, {-30, 48}, {-42, 58}}, ROAD);                        // to the Great Tree
        road(g, new int[][]{{c[0] + 8, c[1] + 8}, {-4, 40}, {GALLERY[0] - 9, GALLERY[1]}}, ROAD);       // to the Gallery
        road(g, new int[][]{{-4, 40}, {6, 80}, {28, 108}, {COLOSSEUM[0], COLOSSEUM[1] - 34}}, ROAD);    // to the Colosseum
        road(g, new int[][]{{COLOSSEUM[0] + 34, COLOSSEUM[1]}, {92, 132}, {CHATEAU[0] - 27, CHATEAU[1]}}, STONE_ROAD); // to the Chateau
        road(g, new int[][]{{117, -2}, {130, -2}, {146, 10}, {LIVING[0] - LIVING_R - 3, LIVING[1]}}, ROAD); // Plaza to the Living Cave
        road(g, new int[][]{{-6, -96}, {-60, -96}, {-88, -92}, {-125, -86}}, ROAD);                     // Dread Acres to West Village
        road(g, new int[][]{{-125, -86}, {-122, -98}, {-118, -104}}, ROAD);                             // to the Mirror House
        road(g, new int[][]{{-6, -96}, {20, -100}, {50, -82}, {78, -52}, {84, -40}, {84, -34}}, ROAD);  // Dread Acres to the Plaza
        mountainRoad(g);
    }

    /** A spiral road up the mountain: two and a quarter turns from the foot to the summit. */
    private static void mountainRoad(RiftCtx g) {
        int cx = MOUNTAIN[0], cz = MOUNTAIN[1];
        double a0 = Math.atan2(-44 - cz, 84 - cx);
        int n = 72;
        List<int[]> pts = new ArrayList<>();
        pts.add(new int[]{84, -44});
        pts.add(new int[]{(int) Math.round(cx + Math.cos(a0) * 54), (int) Math.round(cz + Math.sin(a0) * 54)});
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double a = a0 - t * Math.PI * 2 * 2.25;
            double r = 48 - t * 42;
            pts.add(new int[]{(int) Math.round(cx + Math.cos(a) * r), (int) Math.round(cz + Math.sin(a) * r)});
        }
        pts.add(new int[]{cx + 2, cz + 2});
        g.paths = Builders.path(g.c, pts.toArray(new int[0][]), 3, Palette.of("gravel:3,cobblestone:2,andesite:2,dirt_path:2"), "cobblestone_stairs", g.paths);
        int[] last = pts.get(pts.size() - 1);
        g.l.data("mountain.road.end", last[0] + "," + last[1]);
    }

    // =========================================================================================
    // flora
    // =========================================================================================

    private static void flora(RiftCtx g) {
        Terrain.grassAndFlowers(g.c, g.h, g.seed, 0.16, 0.02);
        Terrain.scatter(g.c, g.h, g.seed, 0.02, Palette.of("bush:3,firefly_bush:2,closed_eyeblossom:2,open_eyeblossom:1,fern:2"), "moss_block");
        Terrain.scatter(g.c, g.h, g.seed + 1, 0.025, Palette.of("short_dry_grass:3,tall_dry_grass:1,dead_bush:1"), "coarse_dirt");
        Terrain.scatter(g.c, g.h, g.seed + 2, 0.04, Palette.of("moss_carpet:3,short_grass:2"), "mud");
        Terrain.scatter(g.c, g.h, g.seed + 3, 0.03, Palette.of("pale_moss_carpet:2,short_grass:2"), "pale_moss_block");

        // the thicket: pale oaks, dark oaks, dead trees
        java.util.function.BiPredicate<Integer, Integer> keep = (x, z) -> g.blocked(x, z, 5) || farFromCradleZone(x, z);
        Trees.forest(g.c, g.h, Box.of(-75, 0, -65, 44, 0, 200), g.seed, 0.028, 7, "dark_oak:3,birch:2,dead:1,azalea:2", 4, keep);
        paleOaks(g, Box.of(-75, 0, -65, 44, 0, 200), 0.006, 9);
        // the lagoon: dead trees and mangrove-ish oaks on the dry bits
        Trees.forest(g.c, g.h, Box.of(-215, 0, 30, -76, 0, 200), g.seed + 7, 0.02, 7, "dead:3,oak:1,azalea:1", 5,
                (x, z) -> g.blocked(x, z, 5));
        // west village edges and the mountain foot: spruce and pine
        Trees.forest(g.c, g.h, Box.of(-220, 0, -200, -76, 0, -20), g.seed + 11, 0.012, 9, "spruce:2,dark_oak:2,dead:1", 5,
                (x, z) -> g.blocked(x, z, 6));
        Trees.forest(g.c, g.h, Box.of(60, 0, -175, 175, 0, -66), g.seed + 13, 0.01, 9, "pine:3,spruce:2,dead:1", 5,
                (x, z) -> g.blocked(x, z, 5) || g.h.top(x, z) > 108);
        Trees.forest(g.c, g.h, Box.of(99, 0, 91, 220, 0, 220), g.seed + 17, 0.012, 8, "dead:3,dark_oak:1", 5,
                (x, z) -> g.blocked(x, z, 6));
        Trees.forest(g.c, g.h, Box.of(45, 0, 21, 129, 0, 90), g.seed + 19, 0.02, 7, "dark_oak:2,birch:2,azalea:1", 5,
                (x, z) -> g.blocked(x, z, 5));
    }

    private static boolean farFromCradleZone(int x, int z) { return false; }

    /** Pale oaks: a thick pale trunk, a heavy canopy, and moss hanging under it. */
    private static void paleOaks(RiftCtx g, Box area, double density, int spacing) {
        long s = Hash.salt(g.seed, "paleoak");
        List<int[]> planted = new ArrayList<>();
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!Hash.chance(s, x, z, density)) continue;
                if (!g.h.has(x, z) || g.h.wet(x, z) || g.h.slope(x, z) > 1 || !g.h.inland(x, z, 5)) continue;
                if (g.blocked(x, z, 7)) continue;
                boolean near = false;
                for (int[] p : planted) if (Math.abs(p[0] - x) < spacing && Math.abs(p[1] - z) < spacing) { near = true; break; }
                if (near) continue;
                int t = g.h.top(x, z);
                String gb = BlockState.name(g.c.get(x, t, z));
                if (!(gb.equals("grass_block") || gb.equals("moss_block") || gb.equals("podzol") || gb.equals("pale_moss_block"))) continue;
                paleOak(g, x, t, z, Hash.hash(s, x, t, z));
                planted.add(new int[]{x, z});
            }
    }

    static void paleOak(RiftCtx g, int x, int y, int z, long seed) {
        java.util.Random r = new java.util.Random(seed);
        int hgt = 7 + r.nextInt(4);
        for (int dy = 1; dy <= hgt; dy++)
            for (int dx = 0; dx <= 1; dx++)
                for (int dz = 0; dz <= 1; dz++) g.c.set(x + dx, y + dy, z + dz, "pale_oak_log[axis=y]");
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) Builders.footing(g.c, x + dx, y + 1, z + dz, Fill.of("dirt"), 4);
        int cy = y + hgt;
        for (int dy = -2; dy <= 3; dy++) {
            int rad = dy <= 0 ? 4 : (dy == 1 ? 4 : (dy == 2 ? 3 : 2));
            for (int dz = -rad; dz <= rad + 1; dz++)
                for (int dx = -rad; dx <= rad + 1; dx++) {
                    double d = Math.hypot(dx - 0.5, dz - 0.5);
                    if (d > rad + 0.3) continue;
                    if (Hash.unit(seed, dx, dy, dz) < 0.12 && d > rad - 1) continue;
                    int bx = x + dx, by = cy + dy, bz = z + dz;
                    if (g.c.isAir(bx, by, bz)) g.c.set(bx, by, bz, "pale_oak_leaves[persistent=true]");
                }
        }
        // hanging moss under the canopy edge
        for (int dz = -4; dz <= 5; dz++)
            for (int dx = -4; dx <= 5; dx++) {
                if (Hash.unit(seed, dx, 99, dz) > 0.22) continue;
                int bx = x + dx, bz = z + dz;
                int by = cy - 3;
                if (!BlockState.name(g.c.get(bx, by + 1, bz)).equals("pale_oak_leaves") || !g.c.isAir(bx, by, bz)) continue;
                int len = 1 + (int) (Hash.unit(seed, dx, 7, dz) * 3);
                for (int k = 0; k < len && g.c.isAir(bx, by - k, bz); k++)
                    g.c.set(bx, by - k, bz, k == len - 1 ? "pale_hanging_moss[tip=true]" : "pale_hanging_moss[tip=false]");
            }
    }

    // =========================================================================================
    // mote orbs
    // =========================================================================================

    /** Mote orbs along the roads (always walkable) and a few off them, spread out. */
    private static void motes(RiftCtx g) {
        List<int[]> spots = new ArrayList<>();
        long s = Hash.salt(g.seed, "motes");
        List<long[]> cells = new ArrayList<>();
        g.paths.cells.forEach((k, y) -> cells.add(new long[]{k, y}));
        cells.sort((a, b) -> Long.compare(Hash.hash(s, (int) (a[0] >> 32), (int) a[0]), Hash.hash(s, (int) (b[0] >> 32), (int) b[0])));
        for (long[] cell : cells) {
            if (spots.size() >= RiftCatalog.MOTE_ORBS) break;
            int x = (int) (cell[0] >> 32), z = (int) cell[0];
            boolean near = false;
            for (int[] p : spots) if (Math.abs(p[0] - x) < 18 && Math.abs(p[2] - z) < 18) { near = true; break; }
            if (near) continue;
            int y = g.ground(x, z);
            if (!g.c.isAir(x, y + 1, z) || !g.c.isAir(x, y + 2, z)) continue;
            spots.add(new int[]{x, y, z});
        }
        int i = 0;
        for (int[] p : spots) g.l.standAt("mote." + (++i), p[0], p[1], p[2], 0f);
        g.l.data("motes.count", String.valueOf(i));
    }

    // =========================================================================================
    // zones
    // =========================================================================================

    private static void zone(RiftCtx g, String id, int x1, int z1, int x2, int z2, int y1, int y2, String... flags) {
        RiftCatalog.Area a = RiftCatalog.area(id);
        String[] f = new String[flags.length + 1];
        System.arraycopy(flags, 0, f, 0, flags.length);
        f[flags.length] = Layout.Zone.NO_FALL;
        g.l.zone(id, a.name(), a.color(), Box.of(x1, y1, z1, x2, y2, z2), a.requirementKey(), f);
    }

    private static void zones(RiftCtx g) {
        zone(g, "west_village", -275, -265, -76, -20, 0, 255);
        zone(g, "black_lagoon", -275, -19, -76, 265, 0, 255);
        zone(g, "dreadfarm", -75, -265, 74, -66, 0, 255);
        zone(g, "mountaintop", 75, -265, 275, -66, 0, 255);
        zone(g, "wyld_woods", -75, -65, 44, 90, 0, 255);
        zone(g, "wyld_woods", -75, 91, 19, 265, 0, 255);
        zone(g, "village_plaza", 45, -65, 275, 20, 0, 255);
        zone(g, "living_cave", 130, 21, 275, 90, 0, 255);
        zone(g, "colosseum", 20, 91, 98, 265, 0, 255);
        zone(g, "chateau", 99, 91, 275, 265, 0, 255);
        g.l.zone("riftway", "The Riftway", '7', Box.of(45, 0, 21, 129, 255, 90), null, Layout.Zone.NO_FALL);
        RiftBuild.subZones(g);
    }

    /** Pack helper kept here so the preview's sourcepath compile pulls it in. */
    static long col(int x, int z) { return Pack.column(x, z); }
}
