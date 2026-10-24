package com.skyclone.mod.foraging.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
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
 * THE MOONMARSH (island id {@code galatea}): a misty wetland around a central loch, the plank
 * village of Knotwick, fig uplands, mangroves wading along the shore, and across a bridge the
 * sandstone mesa of the Torrent Canyon where the spiral trees grow (Heart of the Forest IV).
 * Pure: no Bukkit.
 */
public final class MarshGenerator implements IslandGenerator {

    public static final String ID = "galatea";

    @Override public String islandId() { return ID; }

    /** v2: island resources (Velvet Lilac, Sea Lumies, Ruby Veilshroom). v3 (wave 5): the Dimwater Depths under the
     *  loch, honey hives on the Spiral Mesa, the contest host at the Loch Outpost, the mesa renamed. */
    @Override public int version() { return 4; }
    // v4 (wave 5, second pass): the Wyrm's Hollow (ref. Dragon's Lair) off the depths with the jade dragon vendor, a small
    // lava vent for the marsh's one lava sea creature (fishing request 1), fishing.hotspot.* anchors on the loch.

    /** Where the lair was built: {x0, floorY, z0, x1, z1, npcX, npcZ} (interior, floor block y), or null. */
    private int[] lair;

    @Override public Box bounds() { return new Box(-140, 0, -130, 262, 200, 130); }

    private VoxelCanvas c;
    private Layout layout;
    private long seed;
    private Reservations res = new Reservations();
    private Builders.PathResult paths;
    private final List<int[]> trees = new ArrayList<>();
    private final List<int[]> keep = new ArrayList<>();
    private int souls;

    @Override
    public void generate(VoxelCanvas canvas, Layout lay, long sd) {
        c = canvas; layout = lay; seed = sd;
        paths = new Builders.PathResult();
        // The game runs a generator more than once (layout ahead of the build, then the build): reset all run state.
        res = new Reservations(); trees.clear(); keep.clear(); souls = 0; lair = null;

        // ---------------------------------------------------------------- shapes
        Terrain.IslandShape ms = new Terrain.IslandShape(Hash.salt(seed, "marsh"));
        ms.radiusX = 112; ms.radiusZ = 96; ms.baseY = 76; ms.hills = 7; ms.hillScale = 46; ms.cliff = 0.5;
        ms.coastNoise = 0.18; ms.coastScale = 34; ms.undersideDepth = 44; ms.undersideNoise = 0.55; ms.coastDrop = 4;
        Terrain.Heightmap mh = Terrain.island(ms);

        Terrain.IslandShape cs = new Terrain.IslandShape(Hash.salt(seed, "canyon"));
        cs.centerX = 196; cs.centerZ = 20; cs.radiusX = 52; cs.radiusZ = 50; cs.baseY = 98; cs.hills = 6; cs.cliff = 0.85;
        cs.terraceStep = 5; cs.coastNoise = 0.16; cs.undersideDepth = 46; cs.undersideNoise = 0.55;
        Terrain.Heightmap ch = Terrain.island(cs);

        // plots
        Box plaza = Box.of(-82, 0, -46, -60, 0, -26);
        int plazaY = flat(mh, plaza, 6);
        Box housesPlot = Box.of(-100, 0, -70, -54, 0, -47);
        mh.flatten(housesPlot, plazaY, 5);
        Box portalPlot = Box.of(-98, 0, -40, -88, 0, -30);
        mh.flatten(portalPlot, plazaY, 4);
        Box outpost = Box.of(36, 0, 46, 54, 0, 62);
        int outpostY = flat(mh, outpost, 5);
        Box canyonGate = Box.of(150, 0, 10, 162, 0, 26);
        int canyonY = flat(ch, canyonGate, 5);

        // the loch: a basin carved into the heightmap before anything is written
        int level = ms.baseY - 1;
        Noise ln = new Noise(Hash.salt(seed, "loch"));
        int lcx = -6, lcz = 18;
        for (int z = lcz - 50; z <= lcz + 50; z++)
            for (int x = lcx - 60; x <= lcx + 60; x++) {
                if (!mh.has(x, z)) continue;
                double d = Math.hypot((x - lcx) / 46.0, (z - lcz) / 34.0) / (1 + 0.22 * ln.noise2(x / 18.0, z / 18.0));
                if (d > 1.15) continue;
                if (d <= 1.0) {
                    int depth = (int) Math.round(1 + 5 * (1 - d * d));
                    mh.setTop(x, z, level - depth);
                } else {
                    double t = (d - 1.0) / 0.15;   // a soft shore rising back to the land
                    int y = (int) Math.round(level + t * Math.max(0, mh.top(x, z) - level));
                    mh.setTop(x, z, Math.min(mh.top(x, z), Math.max(level + 1, y)));
                }
            }

        // ---------------------------------------------------------------- terrain
        Terrain.SurfaceRules mr = new Terrain.SurfaceRules();
        mr.top = Palette.of("grass_block:8,moss_block:3,mud:2,podzol:1,coarse_dirt:1");
        mr.soil = Palette.of("dirt:4,mud:2,clay:1");
        mr.sandBelow = level - 1;
        mr.sand = Palette.of("mud:4,clay:2,sand:1,gravel:1");
        mr.sandSoil = Palette.of("mud:2,clay:1,dirt:1");
        mr.cliff = Palette.of("moss_block:4,mossy_cobblestone:3,mud:2,rooted_dirt:2,tuff:1");
        mr.underside = Palette.of("stone:5,tuff:3,mud:1,deepslate:2");
        Terrain.fill(c, mh, mr);
        Terrain.fillLevel(c, mh, Box.of(lcx - 60, 0, lcz - 50, lcx + 60, 0, lcz + 50), level, "water");
        int[] deep = depths(mh, level, lcx, lcz);   // wave 5: the Dimwater Depths under the loch

        Terrain.SurfaceRules cr = new Terrain.SurfaceRules();
        cr.top = Palette.of("sand:4,coarse_dirt:2,grass_block:2,red_sand:1");
        cr.soil = Palette.of("sandstone:3,sand:1");
        cr.stone = Palette.of("sandstone:5,smooth_sandstone:1,terracotta:2,stone:2");
        cr.cliff = Palette.of("sandstone:5,terracotta:2,orange_terracotta:2,yellow_terracotta:1,smooth_sandstone:1");
        cr.underside = Palette.of("sandstone:4,terracotta:2,stone:3");
        cr.steepSlope = 2;
        Terrain.fill(c, ch, cr);
        Terrain.pond(c, ch, 206, 34, 7, 3, seed + 3);

        // ---------------------------------------------------------------- Knotwick
        res.reserve("plaza", plaza);
        Palette boards = Palette.of("spruce_planks:5,dark_oak_planks:2,mangrove_planks:2,mud_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) {
                c.set(x, plazaY, z, boards.at(x, plazaY, z));
                Builders.footing(c, x, plazaY, z, Fill.of("mud"), 6);
                for (int a = 1; a <= 4; a++) if (Builders.natural(c.get(x, plazaY + a, z))) c.set(x, plazaY + a, z, "air");
            }
        Builders.well(c, -71, plazaY, -36);
        for (int[] p : new int[][]{{-81, -45}, {-81, -27}, {-61, -45}, {-61, -27}}) Builders.lampPost(c, p[0], plazaY, p[1], "street", "north");
        res.reserve("portal.hub", GenKit.portal(c, layout, "portal.hub", -93, plazaY, -35, 'z',
                Palette.of("mud_bricks:4,mossy_stone_bricks:2,packed_mud:1"), Palette.of("mud_bricks:3,packed_mud:2"), "soul"));

        Builders.House ilse = house(-98, plazaY, -68, 9, 8, "south", "mangrove", Palette.of("mangrove_planks:5,mud_bricks:2"));
        Builders.House thane = house(-85, plazaY, -68, 10, 8, "south", "dark_oak", Palette.of("dark_oak_planks:5,packed_mud:1"));
        Builders.House fen = house(-71, plazaY, -68, 9, 8, "south", "spruce", Palette.of("spruce_planks:4,moss_block:1,mud_bricks:1"));
        Builders.Stall stall = Builders.marketStall(c, -64, plazaY, -46, "south", "green", "lime", seed);

        // the outpost by the loch: a little dock
        res.reserve("outpost", outpost);
        for (int z = outpost.minZ() + 2; z <= outpost.maxZ() - 2; z++)
            for (int x = outpost.minX() + 2; x <= outpost.maxX() - 2; x++) c.set(x, outpostY, z, boards.at(x, outpostY, z));
        Builders.campfire(c, 45, outpostY, 54);
        Builders.lampPost(c, 39, outpostY, 49, "soul", "north");
        Builders.lampPost(c, 51, outpostY, 59, "soul", "north");

        // canyon gate (warp point)
        res.reserve("canyon_gate", canyonGate);
        Palette sandstone = Palette.of("cut_sandstone:3,smooth_sandstone:2,chiseled_sandstone:1");
        for (int z = canyonGate.minZ() + 1; z <= canyonGate.maxZ() - 1; z++)
            for (int x = canyonGate.minX() + 1; x <= canyonGate.maxX() - 1; x++) c.set(x, canyonY, z, sandstone.at(x, canyonY, z));
        for (int[] p : new int[][]{{151, 11}, {151, 25}, {161, 11}, {161, 25}}) {
            for (int a = 1; a <= 4; a++) c.set(p[0], canyonY + a, p[1], "sandstone_wall");
            c.set(p[0], canyonY + 5, p[1], Blocks.lantern(false));
        }

        // ---------------------------------------------------------------- bridge to the canyon
        int[] ha = GenKit.edgePoint(mh, 0, 0, 196, 20, 0.88);
        int[] hb = GenKit.edgePoint(ch, 196, 20, 0, 0, 0.82);
        int ya = ground(ha[0], ha[1]), yb = ground(hb[0], hb[1]);
        Builders.bridge(c, ha[0], ya, ha[1], hb[0], yb, hb[1], 3, Fill.of("mangrove_planks"), "mangrove_fence", "stripped_dark_oak_log", "mangrove_stairs");
        markLine(ha, hb, 4);

        // ---------------------------------------------------------------- paths
        Palette walk = Palette.of("dirt_path:3,mud:1,packed_mud:2,coarse_dirt:1,spruce_planks:1");
        int[] hub = {-56, -20};
        path(new int[][]{{-71, -25}, hub}, walk);
        path(new int[][]{hub, {-30, -40}, {20, -50}, {60, -40}}, walk);                 // fig uplands
        path(new int[][]{hub, {-50, 20}, {-40, 60}, {0, 66}, {40, 60}, {45, 47}}, walk); // west shore round to the outpost
        path(new int[][]{{60, -40}, {80, -10}, {90, 12}, ha}, walk);                     // east to the bridge
        path(new int[][]{{40, 60}, {70, 40}, {90, 12}}, walk);
        path(new int[][]{{ilse.doorStep[0], ilse.doorStep[2] + 1}, {ilse.doorStep[0], -48}}, walk);
        path(new int[][]{{thane.doorStep[0], thane.doorStep[2] + 1}, {thane.doorStep[0], -48}}, walk);
        path(new int[][]{{fen.doorStep[0], fen.doorStep[2] + 1}, {fen.doorStep[0], -48}}, walk);
        Palette sandPath = Palette.of("smooth_sandstone:3,sand:2,cut_sandstone:1");
        path(new int[][]{hb, {162, 18}, {180, 4}, {200, 0}, {215, 22}, {200, 50}, {180, 40}}, sandPath);
        GenKit.lampsAlong(c, new int[][]{hub, {-30, -40}, {20, -50}, {60, -40}, {80, -10}, {90, 12}}, 14, 3, "soul", (x, z) -> res.covers(x, z, 1));
        GenKit.lampsAlong(c, new int[][]{{-50, 20}, {-40, 60}, {0, 66}, {40, 60}}, 14, 3, "soul", (x, z) -> res.covers(x, z, 1));
        GenKit.lampsAlong(c, new int[][]{{162, 18}, {180, 4}, {200, 0}, {215, 22}}, 14, 3, "post", (x, z) -> res.covers(x, z, 1));

        // ---------------------------------------------------------------- the lava vent (v4, before souls and trees)
        int[] vent = lavaVent(mh, level);

        // ---------------------------------------------------------------- fairy souls (before trees)
        fairySouls(mh, ch, plazaY, level);

        // ---------------------------------------------------------------- the giants
        java.util.function.BiPredicate<Integer, Integer> nope = (x, z) -> res.covers(x, z, 7) || paths.near(x, z, 4) || kept(x, z);
        // figs on the dry northern uplands
        plant(mh, Box.of(-50, 0, -100, 110, 0, -16), 14, 0.016, nope, "fig", level);
        plant(mh, Box.of(60, 0, -16, 110, 0, 70), 14, 0.016, nope, "fig", level);
        // mangroves wading along the loch shore and the wet southwest
        plant(mh, Box.of(-70, 0, -20, 50, 0, 60), 10, 0.02, nope, "mangrove", level);
        ruins(mh);
        // spirals on the canyon mesa
        plant(ch, ch.box(), 11, 0.02, (x, z) -> res.covers(x, z, 6) || paths.near(x, z, 4) || kept(x, z), "spiral", level);

        // decoration: small trees (not choppable here), bushes, fireflies, reeds
        Terrain.grassAndFlowers(c, mh, seed, 0.22, 0.02);
        Trees.forest(c, mh, Box.of(-120, 0, 60, 120, 0, 120), seed + 7, 0.01, 7, "oak:2,birch:1,azalea:3", 4,
                (x, z) -> nope.test(x, z) || near(x, z, 7));
        GenKit.sprinkle(c, mh, mh.box(), Hash.salt(seed, "marshplants"), 0.05,
                Palette.of("firefly_bush:2,bush:3,fern:3,moss_carpet:2,leaf_litter[facing=north,segment_amount=3]:2"),
                "grass_block,moss_block,mud,podzol,coarse_dirt", (x, z) -> paths.covers(x, z) || res.covers(x, z, 1));
        // lily pads, reeds and seagrass in the loch
        long ls = Hash.salt(seed, "lily");
        mh.forEach((x, z, top, bottom) -> {
            if (!mh.wet(x, z)) return;
            String at = c.get(x, level, z);
            if (!BlockState.name(at).equals("water")) return;
            double u = Hash.unit(ls, x, z);
            if (u < 0.05 && c.isAir(x, level + 1, z)) c.set(x, level + 1, z, "lily_pad");
            else if (u < 0.2 && top < level - 1) c.set(x, top + 1, z, "seagrass");
        });

        reeds(mh, level);
        List<int[]> hives = hives(ch);   // wave 5: honey hives on the Spiral Mesa
        GenKit.sprinkle(c, ch, ch.box(), Hash.salt(seed, "canyonplants"), 0.04, Palette.of("dead_bush:3,short_dry_grass:4,cactus:1"),
                "sand,red_sand,coarse_dirt", (x, z) -> paths.covers(x, z) || res.covers(x, z, 2) || near(x, z, 3));

        // ---------------------------------------------------------------- island resources (foraging picks these)
        int[] counts = resources(mh, ch, level);

        // ---------------------------------------------------------------- anchors, zones, areas
        layout.standAt("spawn", -72, plazaY, -30, 0f);
        npcOn("npc.hotf_keeper", ilse.doorStep[0] + 2, ilse.doorStep[1], ilse.doorStep[2] + 1, 180f, "mangrove_planks");
        npcOn("npc.marsh_axewright", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 0f, "spruce_planks");
        npcOn("npc.sapwitch", fen.doorStep[0] + 2, fen.doorStep[1], fen.doorStep[2] + 1, 180f, "spruce_planks");
        layout.standAt("warp.canyon", 156, canyonY, 18, -90f);
        npcOn("npc.contest_host", 48, outpostY, 51, 90f, "spruce_planks");
        // v4: the jade dragon in its lair (reached by swimming, so the walk check skips it), loch hotspots, the lava vent
        List<String> walkIgnore = new ArrayList<>(List.of("fishing."));
        if (lair != null) {
            layout.standAt("npc.jade_dragon", lair[5], lair[1], lair[6], Float.intBitsToFloat(lair[7]));
            layout.zone("wyrms_hollow", "Wyrm's Hollow", 'a', new Box(lair[0] - 1, lair[1] - 1, lair[2] - 1, lair[3] + 1, lair[1] + 7, lair[4] + 1), null, Layout.Zone.SAFE);
            layout.data("marsh.lair", lair[0] + "," + lair[1] + "," + lair[2] + "," + lair[3] + "," + lair[4]);
            walkIgnore.add("npc.jade_dragon");
        }
        int hot = hotspots(mh, level, lcx, lcz, deep);
        layout.data("marsh.hotspots", String.valueOf(hot));
        if (vent != null) {
            layout.standAt("fishing.spot.lava", vent[3], vent[4], vent[5], Float.intBitsToFloat(vent[6]));
            layout.area("lava_vent", new Box(vent[0] - 3, vent[1] - 3, vent[2] - 3, vent[0] + 3, vent[1] + 3, vent[2] + 3));
            layout.data("marsh.lava", String.valueOf(vent[7]));
        }
        layout.data("walk.ignore", String.join(",", walkIgnore));
        Box mb = mh.box(), cb = ch.box();
        layout.zone("moonmarsh", "Moonmarsh", '2', new Box(-140, 0, -130, 262, 200, 130), "skill:foraging:12");
        layout.zone("knotwick", "Knotwick", 'b', new Box(-104, plazaY - 4, -74, -52, plazaY + 16, -24), null, Layout.Zone.SAFE);
        layout.zone("dimwater_loch", "Dimwater Loch", '3', new Box(lcx - 52, level - 10, lcz - 40, lcx + 52, level + 20, lcz + 40), null);
        layout.zone("fig_uplands", "Fig Uplands", 'a', new Box(-40, 0, -110, 110, 200, -24), null);
        layout.zone("loch_outpost", "Loch Outpost", 'e', new Box(outpost.minX(), outpostY - 3, outpost.minZ(), outpost.maxX(), outpostY + 10, outpost.maxZ()), null);
        layout.zone("torrent_canyon", "Spiral Mesa", '6', new Box(cb.minX(), 0, cb.minZ(), cb.maxX(), 200, cb.maxZ()), "hotf:4");
        if (deep != null) {
            Box db = new Box(deep[2] - 22, deep[0] - 3, deep[3] - 17, deep[2] + 22, level - 1, deep[3] + 17);
            layout.zone("dimwater_depths", "Dimwater Depths", '1', db, null);
            layout.area("depths_water", db);
            layout.data("marsh.depths_floor", String.valueOf(deep[0]));
            layout.data("marsh.depths_lumies", String.valueOf(deep[4]));
        }
        if (!hives.isEmpty()) {
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
            for (int[] h : hives) {
                x0 = Math.min(x0, h[0]); x1 = Math.max(x1, h[0]); z0 = Math.min(z0, h[2]); z1 = Math.max(z1, h[2]);
                y0 = Math.min(y0, h[1]); y1 = Math.max(y1, h[1]);
            }
            layout.area("honey_hives", new Box(x0 - 2, y0 - 2, z0 - 2, x1 + 2, y1 + 6, z1 + 2));
        }
        layout.data("marsh.hives", String.valueOf(hives.size()));
        // the drowned of the loch walk its shores (low band only, so nothing spawns up in the canopies)
        layout.area("mobs.loch", new Box(lcx - 56, level - 2, lcz - 44, lcx + 56, level + 6, lcz + 44));
        layout.area("forest.marsh", new Box(mb.minX(), mb.minY(), mb.minZ(), mb.maxX(), mb.maxY() + 40, mb.maxZ()));
        layout.area("forest.canyon", new Box(cb.minX(), cb.minY(), cb.minZ(), cb.maxX(), cb.maxY() + 40, cb.maxZ()));
        layout.data("reservations", res.summary());
        layout.data("marsh.trees", String.valueOf(trees.size()));
        layout.data("marsh.souls", String.valueOf(souls));
        layout.data("marsh.lilacs", String.valueOf(counts[0]));
        layout.data("marsh.lumies", String.valueOf(counts[1]));
        layout.data("marsh.veilshrooms", String.valueOf(counts[2]));
    }

    /**
     * The Moonmarsh's island resources, planted after everything else so they only take free cells:
     * Velvet Lilac in meadow patches on the dry marsh, Sea Lumies (sea pickles) on the loch floor, Ruby
     * Veilshroom in the shade of the canyon's spiral trees (on a podzol patch so the mushroom survives light).
     * Returns {lilacs, lumies, veilshrooms}.
     */
    private int[] resources(Terrain.Heightmap mh, Terrain.Heightmap ch, int level) {
        int[] n = new int[3];
        Noise meadow = new Noise(Hash.salt(seed, "lilac-meadow"));
        long ls = Hash.salt(seed, "lilac"), ps = Hash.salt(seed, "lumies"), vs = Hash.salt(seed, "veilshroom");
        mh.forEach((x, z, top, bottom) -> {
            if (res.covers(x, z, 2) || paths.near(x, z, 1)) return;
            if (mh.wet(x, z)) {
                if (top >= level - 1 || !Hash.chance(ps, x, z, 0.035)) return;
                if (!BlockState.name(c.get(x, top + 1, z)).equals("water")) return;
                int pickles = 1 + (int) (Hash.unit(ps, x, 7, z) * 4);
                c.set(x, top + 1, z, "sea_pickle[pickles=" + Math.min(4, pickles) + ",waterlogged=true]");
                n[1]++;
                return;
            }
            if (meadow.noise2(x / 22.0, z / 22.0) < 0.3 || !Hash.chance(ls, x, z, 0.10)) return;
            String g = BlockState.name(c.get(x, top, z));
            if (!(g.equals("grass_block") || g.equals("moss_block") || g.equals("podzol"))) return;
            if (!c.isAir(x, top + 1, z) || !c.isAir(x, top + 2, z)) return;
            c.set(x, top + 1, z, "lilac[half=lower]");
            c.set(x, top + 2, z, "lilac[half=upper]");
            n[0]++;
        });
        ch.forEach((x, z, top, bottom) -> {
            if (ch.wet(x, z) || res.covers(x, z, 2) || paths.near(x, z, 1) || !Hash.chance(vs, x, z, 0.12)) return;
            if (!c.isAir(x, top + 1, z)) return;
            boolean shade = false;
            for (int y = top + 2; y <= top + 24 && !shade; y++) if (BlockState.name(c.get(x, y, z)).endsWith("_leaves")) shade = true;
            if (!shade) return;
            String g = BlockState.name(c.get(x, top, z));
            if (g.equals("air") || g.equals("water") || g.endsWith("_log") || g.endsWith("_slab")) return;
            c.set(x, top, z, "podzol");
            c.set(x, top + 1, z, "red_mushroom");
            n[2]++;
        });
        return n;
    }

    // ===================================================================================== wave 5

    /**
     * The Dimwater Depths (ref. Murkwater Depths, "an underwater zone below the Loch"): a round sinkhole in the loch
     * floor drops into a drowned cavern lit by sea lanterns, with kelp, a sunken ring of pillars and Sea Lumies on
     * its bed. The cavern keeps at least seven blocks of rock above the island's underside everywhere, so it never
     * opens into the sky below. Returns {floorY, ceilingY, centreX, centreZ, lumies}, or null when the island is too
     * thin there (nothing is carved then).
     */
    private int[] depths(Terrain.Heightmap mh, int level, int lcx, int lcz) {
        int cx = lcx + 6, cz = lcz + 2;
        double rx = 19, rz = 14;
        int minBottom = Integer.MAX_VALUE;
        for (int z = (int) (cz - rz - 3); z <= cz + rz + 3; z++)
            for (int x = (int) (cx - rx - 3); x <= cx + rx + 3; x++) {
                if (!mh.has(x, z)) return null;
                minBottom = Math.min(minBottom, mh.bottom(x, z));
            }
        int floor = Math.max(minBottom + 7, level - 42);
        int ceil = Math.min(floor + 13, level - 13);
        if (ceil - floor < 7) return null;
        Noise wob = new Noise(Hash.salt(seed, "depths"));
        long ds = Hash.salt(seed, "depths-deco");
        Palette bed = Palette.of("mud:3,gravel:2,clay:2,sand:1");
        java.util.Set<Long> water = new java.util.HashSet<>();
        // the cavern: a squashed bowl, highest in the middle
        for (int z = (int) (cz - rz - 2); z <= cz + rz + 2; z++)
            for (int x = (int) (cx - rx - 2); x <= cx + rx + 2; x++) {
                double e = Math.hypot((x - cx) / rx, (z - cz) / rz) * (1 + 0.14 * wob.noise2(x / 9.0, z / 9.0));
                if (e > 1.0) continue;
                int fl = floor + (int) Math.round(3 * e * e);
                int roof = (int) Math.round(ceil - (ceil - floor - 6) * e * e);
                for (int y = fl; y <= roof; y++) { c.set(x, y, z, "water"); water.add(pack(x, y, z)); }
                c.set(x, fl - 1, z, bed.at(x, fl - 1, z));
            }
        // the sinkhole from the loch floor down into the roof (radius 4)
        for (int z = cz - 5; z <= cz + 5; z++)
            for (int x = cx - 5; x <= cx + 5; x++) {
                if (Math.hypot(x - cx, z - cz) > 4.3) continue;
                int top = mh.top(x, z);
                for (int y = ceil - 2; y <= Math.min(top, level - 1); y++) { c.set(x, y, z, "water"); water.add(pack(x, y, z)); }
            }
        // v4: the tunnel + shaft to the Wyrm's Hollow join the water before the walls are lined
        int[] lairPlan = planLair(mh, water, cx, cz, rx, rz, floor, ceil);
        // walls: every solid cell touching the water becomes drowned stone (sea lanterns here and there)
        Palette wall = Palette.of("dark_prismarine:3,prismarine:3,tuff:2,deepslate:2,mossy_cobblestone:1");
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (long k : water) {
            int x = (int) (k >> 40), y = (int) ((k >> 20) & 0xFFFFF) - 512, z = (int) (k & 0xFFFFF) - 524288;
            for (int[] d : nb) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (water.contains(pack(nx, ny, nz))) continue;
                String at = BlockState.name(c.get(nx, ny, nz));
                if (at.equals("water") || at.equals("air")) continue;   // the loch itself; never seal open air
                if (ny >= level - 7 && d[1] >= 0) continue;              // leave the loch bed round the shaft alone
                boolean lamp = d[1] != -1 && Hash.chance(ds, nx, ny, nz, 0.035);
                c.set(nx, ny, nz, lamp ? "sea_lantern" : wall.at(nx, ny, nz));
            }
        }
        // the bed: kelp, seagrass, Sea Lumies (picked like the loch's), and a sunken ring of pillars
        int lumies = 0;
        for (int z = (int) (cz - rz); z <= cz + rz; z++)
            for (int x = (int) (cx - rx); x <= cx + rx; x++) {
                int y = bedY(water, x, z, floor);
                if (y < 0 || Math.hypot(x - cx, z - cz) < 5.5) continue;
                double u = Hash.unit(ds, x, z);
                if (u < 0.05) {
                    c.set(x, y, z, "sea_pickle[pickles=" + (1 + Hash.range(ds, x, 3, z, 0, 3)) + ",waterlogged=true]");
                    lumies++;
                } else if (u < 0.13) {
                    int h = 2 + Hash.range(ds, x, 9, z, 0, 5);
                    for (int a = 0; a < h; a++) if (!water.contains(pack(x, y + a + 1, z))) { h = a; break; }
                    for (int a = 0; a < h; a++) c.set(x, y + a, z, a == h - 1 ? "kelp" : "kelp_plant");
                } else if (u < 0.30) c.set(x, y, z, "seagrass");
            }
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            int px = cx + (int) Math.round(Math.cos(a) * 11), pz = cz + (int) Math.round(Math.sin(a) * 8);
            int y = bedY(water, px, pz, floor);
            if (y < 0) continue;
            int h = 3 + (i % 3);
            for (int k = 0; k < h; k++) if (water.contains(pack(px, y + k + 1, pz))) c.set(px, y + k, pz, k == h - 1 ? "chiseled_stone_bricks" : "mossy_stone_bricks");
        }
        keep.add(new int[]{cx, cz, 9});
        if (lairPlan != null) buildLair(lairPlan);
        return new int[]{floor, ceil, cx, cz, lumies};
    }

    /**
     * The Wyrm's Hollow (ref. the Dragon's Lair, "an underwater lair" where the Jade Dragon sells its pet): from the
     * depths cavern a drowned tunnel runs out through the rock, a shaft rises from its end, and you surface through a
     * hole in the floor of a dry green-lit chamber with a hoard at the back. Tries the four directions and takes the
     * first whose whole chamber AND tunnel sit in solid rock (never the loch, never the island's underside). The
     * tunnel/shaft cells are added to {@code water}; returns the chamber plan for {@link #buildLair}, or null.
     * Plan: {dx, dz, cx, cz, edge, floor, F} (direction, cavern centre, edge distance, cavern floor, chamber floor y).
     */
    private int[] planLair(Terrain.Heightmap mh, java.util.Set<Long> water, int cx, int cz, double rx, double rz, int floor, int ceil) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        StringBuilder why = new StringBuilder();
        for (int[] d : dirs) {
            int dx = d[0], dz = d[1];
            int edge = (int) Math.round(Math.abs(dx) * rx + Math.abs(dz) * rz);
            // the chamber floor: as low as the island's underside allows (5 rock under it), never above the roof rule
            int F = floor + 9, lowTop = Integer.MAX_VALUE;
            boolean ok = true;
            for (int u = edge + 5; u <= edge + 21 && ok; u++)
                for (int v = -7; v <= 7; v++) {
                    int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                    if (!mh.has(x, z)) { ok = false; break; }
                    F = Math.max(F, mh.bottom(x, z) + 6);
                    lowTop = Math.min(lowTop, mh.top(x, z));
                }
            if (!ok || F + 8 >= lowTop) { why.append(dx).append(',').append(dz).append(ok ? " thin " + F + "/" + lowTop : " edge").append("; "); continue; }
            // chamber interior u edge+6..edge+20, v -6..6, y F+1..F+5; shell one block round it (y F..F+6): all rock now
            for (int u = edge + 5; u <= edge + 21 && ok; u++)
                for (int v = -7; v <= 7 && ok; v++) {
                    int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                    for (int y = F - 1; y <= F + 7 && ok; y++) if (!solidRock(x, y, z)) ok = false;
                }
            if (!ok) { why.append(dx).append(',').append(dz).append(" hollow; "); continue; }
            // the tunnel leaves the cavern high under its roof and climbs 1 a block, 3 wide and 3 high, to the foot of the shaft
            int start = ceil - 5;
            for (int u = edge - 4; u <= edge + 9 && ok; u++)
                for (int v = -2; v <= 2 && ok; v++) {
                    int ty = tunnelY(u, edge, start, F);
                    int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                    if (!mh.has(x, z) || ty - 1 <= mh.bottom(x, z) + 2 || ty + 3 >= mh.top(x, z) - 3) {
                        why.append(dx).append(',').append(dz).append(" tunnel@").append(u - edge).append(" y").append(ty)
                                .append(" b").append(mh.has(x, z) ? mh.bottom(x, z) : -1).append(" t").append(mh.has(x, z) ? mh.top(x, z) : -1).append("; ");
                        ok = false; break;
                    }
                    for (int y = ty - 1; y <= ty + 3 && ok; y++)
                        if (!water.contains(pack(x, y, z)) && !solidRock(x, y, z)) {
                            ok = false;
                            why.append(dx).append(',').append(dz).append(" open@").append(u - edge).append(" ").append(c.get(x, y, z)).append("; ");
                        }
                }
            if (!ok) continue;
            // carve: the climbing tunnel, then the shaft up through the chamber floor
            for (int u = edge - 7; u <= edge + 9; u++)
                for (int v = -1; v <= 1; v++) {
                    int ty = tunnelY(u, edge, start, F);
                    for (int y = ty; y <= ty + 2; y++) {
                        int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                        c.set(x, y, z, "water"); water.add(pack(x, y, z));
                    }
                }
            for (int u = edge + 7; u <= edge + 9; u++)
                for (int v = -1; v <= 1; v++)
                    for (int y = tunnelY(u, edge, start, F); y <= F; y++) {
                        int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                        c.set(x, y, z, "water"); water.add(pack(x, y, z));
                    }
            return new int[]{dx, dz, cx, cz, edge, floor, F};
        }
        layout.data("marsh.lair_failed", why.toString());
        return null;
    }

    /** Bottom of the climbing tunnel at step {@code u}: just under the cavern roof, then up 1 a block, at most F - 3. */
    private static int tunnelY(int u, int edge, int start, int F) {
        return Math.min(F - 3, start + Math.max(0, u - (edge - 6)));
    }

    /** A full solid block that is not liquid or part of a tree: what the lair may be cut into. */
    private boolean solidRock(int x, int y, int z) {
        String b = c.get(x, y, z);
        String n = BlockState.name(b);
        if (c.isAir(x, y, z) || n.equals("water") || n.equals("lava") || n.endsWith("_log") || n.endsWith("_leaves")) return false;
        return BlockInfo.of(b).top16 >= 12;   // mud is 14/16 tall: still rock for this purpose
    }

    private void buildLair(int[] plan) {
        int dx = plan[0], dz = plan[1], cx = plan[2], cz = plan[3], edge = plan[4], F = plan[6];
        long ls = Hash.salt(seed, "wyrm");
        Palette shell = Palette.of("dark_prismarine:4,prismarine_bricks:3,oxidized_cut_copper:1,mossy_stone_bricks:2");
        Palette floorP = Palette.of("polished_deepslate:3,deepslate_tiles:2,oxidized_copper:1,mossy_stone_bricks:1");
        Palette hoard = Palette.of("gold_block:3,raw_gold_block:3,emerald_block:1,raw_copper_block:1");
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int u = edge + 5; u <= edge + 21; u++)
            for (int v = -7; v <= 7; v++) {
                int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                boolean inside = u >= edge + 6 && u <= edge + 20 && v >= -6 && v <= 6;
                boolean shaft = u >= edge + 7 && u <= edge + 9 && v >= -1 && v <= 1;
                if (inside) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
                for (int y = F; y <= F + 6; y++) {
                    if (y == F && shaft) continue;                                   // the water hole you surface through
                    boolean corner = Math.abs(v) == 6 && (y == F + 5) || (u == edge + 6 || u == edge + 20) && y == F + 5 && Math.abs(v) >= 5;
                    if (inside && y > F && y < F + 6 && !corner) c.set(x, y, z, "air");
                    else if (y == F) c.set(x, y, z, floorP.at(x, y, z));
                    else c.set(x, y, z, shell.at(x, y, z));
                }
                // green light from above, every fourth cell
                if (inside && Math.floorMod(u, 4) == 0 && Math.floorMod(v, 4) == 2) c.set(x, F + 6, z, "verdant_froglight");
            }
        // a rim of mossy slabs round the hole so it reads as a pool, lanterns by the walls
        for (int u = edge + 6; u <= edge + 10; u++)
            for (int v = -2; v <= 2; v++) {
                boolean ring = u == edge + 6 || u == edge + 10 || Math.abs(v) == 2;
                int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                if (ring && c.isAir(x, F + 1, z) && Hash.chance(ls, x, z, 0.5)) c.set(x, F + 1, z, "mossy_stone_brick_slab[type=bottom,waterlogged=false]");
            }
        // the hoard: heaps of gold and jade along the back wall and the side corners, the dragon in front of it
        for (int v = -6; v <= 6; v++)
            for (int u = edge + 17; u <= edge + 20; u++) {
                if (Math.abs(v) <= 1 && u <= edge + 18) continue;                    // the dragon's spot stays clear
                int h = (int) Math.round(2.6 - Math.abs(v) * 0.15 - (edge + 20 - u) * 0.7 + Hash.unit(ls, u, v) * 1.2);
                int x = cx + u * dx - v * dz, z = cz + u * dz + v * dx;
                for (int y = F + 1; y <= F + Math.min(3, h); y++) c.set(x, y, z, hoard.at(x, y, z));
            }
        for (int[] uv : new int[][]{{edge + 12, -6}, {edge + 12, 6}, {edge + 16, -6}, {edge + 16, 6}}) {
            int x = cx + uv[0] * dx - uv[1] * dz, z = cz + uv[0] * dz + uv[1] * dx;
            c.set(x, F + 1, z, "mossy_stone_bricks");
            c.set(x, F + 2, z, Blocks.lantern(false));
        }
        int nu = edge + 17;
        int npcX = cx + nu * dx, npcZ = cz + nu * dz;
        c.set(npcX, F, npcZ, "chiseled_stone_bricks");
        c.set(npcX, F + 1, npcZ, "air"); c.set(npcX, F + 2, npcZ, "air");
        // facing back toward the hole: yaw of (-dx, -dz) in Minecraft terms (south = 0, west = 90)
        float yaw = dx == 1 ? 90f : dx == -1 ? -90f : dz == 1 ? 180f : 0f;
        lair = new int[]{x0, F, z0, x1, z1, npcX, npcZ, Float.floatToIntBits(yaw)};
    }

    /** Three {@code fishing.hotspot.N} anchors on open loch water away from the sinkhole. Returns how many. */
    private int hotspots(Terrain.Heightmap mh, int level, int lcx, int lcz, int[] deep) {
        int n = 0;
        int[][] want = {{lcx - 30, lcz - 8}, {lcx + 30, lcz + 10}, {lcx - 8, lcz + 24}, {lcx + 18, lcz - 22}};
        for (int[] w : want) {
            if (n >= 3) break;
            int[] hit = null;
            for (int r = 0; r <= 8 && hit == null; r++)
                for (int a = -r; a <= r && hit == null; a++)
                    for (int b = -r; b <= r && hit == null; b++) {
                        int x = w[0] + a, z = w[1] + b;
                        if (!mh.has(x, z) || !mh.wet(x, z) || mh.top(x, z) > level - 3) continue;
                        if (deep != null && Math.hypot(x - deep[2], z - deep[3]) < 9) continue;
                        boolean open = true;
                        for (int k = -2; k <= 2 && open; k++)
                            for (int j = -2; j <= 2 && open; j++)
                                if (!BlockState.name(c.get(x + k, level, z + j)).equals("water")) open = false;
                        if (open) hit = new int[]{x, z};
                    }
            if (hit == null) continue;
            n++;
            layout.standAt("fishing.hotspot." + n, hit[0], level, hit[1], 0f);
        }
        return n;
    }

    /**
     * A small lava vent on the dry marsh (fishing's request: the Moonmarsh's one lava sea creature needs lava): a
     * two-deep pool of lava in a ring of basalt and blackstone, magma underneath, nothing that burns nearby (it is
     * reserved before trees are planted). Returns {x, y, z, standX, standY, standZ, yaw bits, lava cells} or null.
     */
    private int[] lavaVent(Terrain.Heightmap mh, int level) {
        // candidates on a 6-block grid over the dry marsh, nearest the north-east fig uplands first (deterministic)
        List<int[]> want = new ArrayList<>();
        for (int z = -96; z <= 96; z += 6) for (int x = -112; x <= 112; x += 6) want.add(new int[]{x, z});
        want.sort(java.util.Comparator.comparingDouble(w -> Math.hypot(w[0] - 40, w[1] + 70)));
        int[] fails = new int[3];
        for (int[] w : want) {
            int x0 = w[0], z0 = w[1];
            boolean ok = mh.has(x0, z0);
            for (int a = -6; a <= 6 && ok; a++)
                for (int b = -6; b <= 6 && ok; b++) {
                    int x = x0 + a, z = z0 + b;
                    if (!mh.has(x, z) || mh.wet(x, z) || !mh.inland(x, z, 3)) { ok = false; fails[0]++; }
                    else if (res.covers(x, z, 1) || paths.near(x, z, 1) || kept(x, z)) { ok = false; fails[1]++; }
                    else if (Math.abs(mh.top(x, z) - mh.top(x0, z0)) > 4 || mh.top(x, z) <= level + 1) { ok = false; fails[2]++; }
                }
            if (!ok) continue;
            int g = mh.top(x0, z0);
            long vs = Hash.salt(seed, "vent");
            Palette rim = Palette.of("basalt[axis=y]:3,blackstone:3,smooth_basalt:2,magma_block:1,tuff:1");
            int lava = 0;
            for (int a = -6; a <= 6; a++)
                for (int b = -6; b <= 6; b++) {
                    int x = x0 + a, z = z0 + b;
                    double d = Math.hypot(a, b) * (1 + 0.12 * (Hash.unit(vs, x, z) - 0.5));
                    if (d > 5.6) continue;
                    if (d <= 2.3) {
                        c.set(x, g, z, "lava"); c.set(x, g - 1, z, "lava"); c.set(x, g - 2, z, "magma_block"); c.set(x, g - 3, z, "blackstone");
                        lava += 2;
                    } else {
                        for (int y = g - 3; y <= g; y++) c.set(x, y, z, d <= 3.8 ? rim.at(x, y, z) : (y == g ? (Hash.chance(vs, x, z, 0.4) ? "coarse_dirt" : "blackstone") : "stone"));
                    }
                    for (int y = g + 1; y <= g + 4; y++) if (!c.isAir(x, y, z)) c.set(x, y, z, "air");
                }
            // three crooked basalt spires lean over the pool
            for (int i = 0; i < 3; i++) {
                double ang = i * 2.1 + Hash.unit(vs, i, 1) * 0.6;
                int sx = x0 + (int) Math.round(Math.cos(ang) * 4.4), sz = z0 + (int) Math.round(Math.sin(ang) * 4.4);
                int h = 2 + Hash.range(vs, sx, 2, sz, 0, 3);
                for (int y = g + 1; y <= g + h; y++) c.set(sx, y, sz, "basalt[axis=y]");
            }
            res.reserve("lava_vent", Box.of(x0 - 6, 0, z0 - 6, x0 + 6, 0, z0 + 6));
            keep.add(new int[]{x0, z0, 7});
            // stand on the rim, south of the pool, facing it
            int sx = x0, sz = z0 + 4;
            for (int y = g + 1; y <= g + 2; y++) c.set(sx, y, sz, "air");
            return new int[]{x0, g, z0, sx, g, sz, Float.floatToIntBits(180f), lava};
        }
        layout.data("marsh.vent_failed", "land " + fails[0] + ", taken " + fails[1] + ", slope " + fails[2]);
        return null;
    }

    /** The lowest water cell of a cavern column (its bed), or -1. */
    private static int bedY(java.util.Set<Long> water, int x, int z, int floor) {
        for (int y = floor - 1; y <= floor + 4; y++) if (water.contains(pack(x, y, z)) && !water.contains(pack(x, y - 1, z))) return y;
        return -1;
    }

    private static long pack(int x, int y, int z) { return ((long) x << 40) | ((long) (y + 512) << 20) | (z + 524288L); }

    /** Up to six honey hives on posts along the Spiral Mesa's path (research 03: hives on the canyon heights). */
    private List<int[]> hives(Terrain.Heightmap ch) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : new int[][]{{188, 12}, {198, -6}, {211, 8}, {222, 32}, {206, 48}, {188, 46}, {176, 28}, {214, 40}}) {
            if (out.size() >= 6) break;
            int[] g = null;
            for (int r = 0; r <= 4 && g == null; r++)
                for (int dz = -r; dz <= r && g == null; dz++)
                    for (int dx = -r; dx <= r && g == null; dx++) {
                        int x = p[0] + dx, z = p[1] + dz;
                        if (!ch.has(x, z) || ch.wet(x, z) || res.covers(x, z, 2) || paths.near(x, z, 1) || near(x, z, 3) || kept(x, z)) continue;
                        int y = GenKit.ground(c, x, z);
                        if (y == Integer.MIN_VALUE || y < ch.top(x, z) - 1) continue;
                        boolean free = true;
                        for (int a = 1; a <= 5; a++) if (!c.isAir(x, y + a, z) && !Builders.natural(c.get(x, y + a, z))) free = false;
                        if (free) g = new int[]{x, y, z};
                    }
            if (g == null) continue;
            c.set(g[0], g[1] + 1, g[2], "stripped_oak_log[axis=y]");
            c.set(g[0], g[1] + 2, g[2], "stripped_oak_log[axis=y]");
            c.set(g[0], g[1] + 3, g[2], "bee_nest[facing=south,honey_level=5]");
            c.set(g[0], g[1] + 4, g[2], Blocks.slab("oak", "bottom"));
            c.set(g[0], g[1] + 5, g[2], "air");
            keep.add(new int[]{g[0], g[2], 2});
            out.add(g);
        }
        return out;
    }

    // ===================================================================================== helpers

    private static int flat(Terrain.Heightmap h, Box plot, int blend) {
        int y = h.average(plot);
        h.flatten(plot, y, blend);
        return y;
    }

    private Builders.House house(int x, int y, int z, int w, int d, String door, String roof, Fill wall) {
        Builders.House h = new Builders.House(x, y, z, w, d);
        h.door = door; h.roof = roof; h.roofStyle = "gable"; h.wall = wall; h.frame = "stripped_dark_oak_log";
        h.doorWood = roof.equals("mangrove") ? "mangrove" : roof; h.seed = seed + x * 31L + z; h.chimney = roof.equals("spruce");
        if (res.reserve("house" + x, h.reserve())) h.build(c);
        return h;
    }

    private int ground(int x, int z) {
        int g = GenKit.ground(c, x, z);
        return g == Integer.MIN_VALUE ? 76 : g;
    }

    private void path(int[][] pts, Fill f) { Builders.path(c, pts, 3, f, "mangrove_slab", paths); }

    private void markLine(int[] a, int[] b, int r) {
        int n = (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]));
        for (int i = 0; i <= n; i += 3) {
            double t = (double) i / Math.max(1, n);
            keep.add(new int[]{(int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t), r});
        }
    }

    private boolean kept(int x, int z) {
        for (int[] k : keep) if (Math.abs(k[0] - x) <= k[2] && Math.abs(k[1] - z) <= k[2]) return true;
        return false;
    }

    private boolean near(int x, int z, int r) {
        for (int[] t : trees) if (Math.abs(t[0] - x) <= r && Math.abs(t[1] - z) <= r) return true;
        return false;
    }

    private void npcOn(String name, int x, int y, int z, float yaw, String block) {
        GenKit.npcSpot(c, x, y, z, block);
        layout.standAt(name, x, y, z, yaw);
        keep.add(new int[]{x, z, 3});
    }

    /** Plant giants of one kind over an area, spaced, on suitable ground (wet only for mangroves). */
    private void plant(Terrain.Heightmap h, Box area, int spacing, double density, java.util.function.BiPredicate<Integer, Integer> keepOut,
                       String kind, int level) {
        long s = Hash.salt(seed, "giant-" + kind);
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!Hash.chance(s, x, z, density) || !h.has(x, z)) continue;
                if (keepOut.test(x, z) || near(x, z, spacing)) continue;
                if (!h.inland(x, z, 6) || h.slope(x, z) > 2) continue;
                int t = h.top(x, z);
                boolean wet = h.wet(x, z);
                if (kind.equals("mangrove")) {
                    if (!(wet ? t >= level - 2 : t <= level + 2)) continue;   // shallow water or the muddy shore
                } else if (wet) continue;
                String gnd = BlockState.name(c.get(x, t, z));
                if (!wet && !(gnd.equals("grass_block") || gnd.equals("moss_block") || gnd.equals("mud") || gnd.equals("podzol")
                        || gnd.equals("coarse_dirt") || gnd.equals("sand") || gnd.equals("red_sand"))) continue;
                switch (kind) {
                    case "fig" -> MarshFlora.fig(c, x, t, z, Hash.hash(s, x, t, z));
                    case "mangrove" -> MarshFlora.mangrove(c, x, t, z, Hash.hash(s, x, t, z));
                    default -> MarshFlora.spiral(c, x, t, z, Hash.hash(s, x, t, z));
                }
                trees.add(new int[]{x, z});
            }
    }

    /** Broken stone circles in the south reaches (the marsh's old ruins). */
    private void ruins(Terrain.Heightmap h) {
        Palette stone = Palette.of("mossy_stone_bricks:4,cracked_stone_bricks:3,stone_bricks:2,mossy_cobblestone:1");
        int[][] at = {{-70, 62}, {10, 84}, {86, 52}};
        String[] names = {"west", "south", "east"};
        int ri = 0;
        for (int[] r : at) {
            String rname = names[ri++];
            if (!h.has(r[0], r[1])) continue;
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                int x = r[0] + (int) Math.round(Math.cos(a) * 5), z = r[1] + (int) Math.round(Math.sin(a) * 5);
                int g = GenKit.ground(c, x, z);
                if (g == Integer.MIN_VALUE || res.covers(x, z, 0) || paths.near(x, z, 1)) continue;
                int hgt = 1 + (int) (Hash.unit(seed, x, 4, z) * 4);
                for (int i = 1; i <= hgt; i++) c.set(x, g + i, z, stone.at(x, g + i, z));
                if (hgt >= 3) c.set(x, g + hgt + 1, z, "mossy_stone_brick_slab[type=bottom,waterlogged=false]");
            }
            int g = GenKit.ground(c, r[0], r[1]);
            if (g != Integer.MIN_VALUE) {
                c.set(r[0], g + 1, r[1], "chiseled_stone_bricks"); c.set(r[0], g + 2, r[1], Blocks.lantern(false));
                // the Chill haunt the old ruins (foraging's spawns.json: mobs.ruins.<west|south|east>)
                layout.area("mobs.ruins." + rname, new Box(r[0] - 10, g - 3, r[1] - 10, r[0] + 10, g + 5, r[1] + 10));
            }
            keep.add(new int[]{r[0], r[1], 7});
        }
    }

    /** Sugar cane along dry shore cells next to water. */
    private void reeds(Terrain.Heightmap h, int level) {
        long s = Hash.salt(seed, "reeds");
        h.forEach((x, z, top, bottom) -> {
            if (h.wet(x, z) || top != level || !Hash.chance(s, x, z, 0.25)) return;
            boolean water = false;
            for (int d = 0; d < 4; d++) if (BlockState.name(c.get(x + Blocks.DX[d], level, z + Blocks.DZ[d])).equals("water")) water = true;
            if (!water || !c.isAir(x, top + 1, z)) return;
            String g = BlockState.name(c.get(x, top, z));
            if (!(g.equals("grass_block") || g.equals("mud") || g.equals("sand") || g.equals("moss_block") || g.equals("dirt"))) return;
            if (g.equals("moss_block") || g.equals("mud")) c.set(x, top, z, "dirt");
            int hgt = 1 + (int) (Hash.unit(s, x, 5, z) * 3);
            for (int i = 1; i <= hgt; i++) if (c.isAir(x, top + i, z)) c.set(x, top + i, z, "sugar_cane");
        });
    }

    private void soul(int x, int z, int r) {
        int[] p = GenKit.standNear(c, x, z, r, null);
        if (p == null) return;
        souls++;
        layout.standAt("fairy_soul." + souls, p[0], p[1], p[2], 0f);
        keep.add(new int[]{p[0], p[2], 2});
    }

    private void fairySouls(Terrain.Heightmap mh, Terrain.Heightmap ch, int plazaY, int level) {
        Palette rock = Palette.of("mossy_cobblestone:3,mud_bricks:2,tuff:2,moss_block:1");
        int[] b = GenKit.boulder(c, -20, -70, rock, "south");
        if (b != null) { souls++; layout.standAt("fairy_soul." + souls, b[0], b[1], b[2], 0f); keep.add(new int[]{b[0], b[2], 3}); }
        soul(-104, -60, 3);      // behind the keeper's house
        soul(-50, -78, 4);
        soul(30, -80, 4);
        soul(85, -60, 4);
        soul(-88, 30, 4);        // the west reach
        soul(-30, 75, 4);        // south shore
        soul(60, 75, 4);
        soul(95, 30, 4);         // by the bridge
        soul(52, 52, 3);         // behind the outpost
        int[] cbould = GenKit.boulder(c, 225, 5, Palette.of("sandstone:3,terracotta:2,orange_terracotta:1"), "west");
        if (cbould != null) { souls++; layout.standAt("fairy_soul." + souls, cbould[0], cbould[1], cbould[2], 0f); keep.add(new int[]{cbould[0], cbould[2], 3}); }
        soul(186, 52, 4);
        soul(230, 40, 4);
        soul(172, -12, 4);
    }
}
