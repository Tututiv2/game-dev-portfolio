package com.skyclone.mod.foraging.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * THE PARK: five floating forests climbing toward the west, joined by plank bridges.
 *
 * <pre>
 *            Spruce Woods (Foraging II, y~84) -- Howling Cave inside it
 *           /                                \
 *   Savanna Woodland (IV, y~100)           Birch Park (I, y~72)  &lt;- spawn + portal to the Hub (east)
 *   /        \                                /
 *  Jungle     Dark Thicket (III, y~90) ------
 *  Island (V, y~112)
 * </pre>
 * Every log standing here is a real tree the foraging module lets players fell (logs regrow).
 * Buildings use planks and STRIPPED logs only, so nothing man-made is choppable. Pure: no Bukkit.
 */
public final class ParkGenerator implements IslandGenerator {

    public static final String ID = "park";

    @Override public String islandId() { return ID; }

    /*
     * /warp howl arrival (wave4-endgame playtest major): the anchor used to stand at (0, -121), inside the
     * Pack Wraith spawn box (x -10..30), so a player landed among 4+ Lv30 wraiths at 1-3 blocks. Same fix
     * class as the hub's crypt_landing and the End's drag_landing: the arrival is a SAFE pad at the west end
     * of the main chamber (by the tunnel mouth), and every wolf spawn area and the Alpha keep ARRIVAL_CLEAR
     * blocks off it, i.e. outside core's 16-block aggro range when you land.
     */
    /** /warp howl lands on this block (x, z): west end of the main cave chamber, facing east into it. */
    public static final int HOWL_X = -6, HOWL_Z = -116;
    /** Cave floor height of the main chamber. */
    public static final int CAVE_FLOOR_Y = 58;
    /** Wolf spawn areas and the Alpha's anchor keep at least this far (horizontal) from the arrival. */
    public static final int ARRIVAL_CLEAR = 18;

    /** The SAFE pad round warp.howl (zone {@code howl_landing}). */
    public static Box howlLanding() {
        return new Box(HOWL_X - 6, CAVE_FLOOR_Y - 2, HOWL_Z - 6, HOWL_X + 6, CAVE_FLOOR_Y + 12, HOWL_Z + 6);
    }

    /**
     * Pure check shared by the self-test and the unit test: null when warp.howl stands in a small SAFE zone
     * and every wolf spawn area ({@code howling_cave*}) and the Alpha are at least ARRIVAL_CLEAR away.
     */
    public static String arrivalProblem(Layout l) {
        Layout.Anchor a = l.anchor("warp.howl");
        if (a == null) return "warp.howl missing";
        StringBuilder bad = new StringBuilder();
        Layout.Zone safe = null;
        long best = Long.MAX_VALUE;
        for (Layout.Zone z : l.zones())
            if (z.box().contains(a.x(), a.y() + 1, a.z()) && z.box().volume() < best) { best = z.box().volume(); safe = z; }
        if (safe == null || safe.flags() == null || !safe.flags().contains(Layout.Zone.SAFE))
            bad.append("warp.howl lands in ").append(safe == null ? "no zone" : safe.id()).append(" (not SAFE); ");
        else if (safe.box().volume() > 40_000) bad.append("SAFE zone ").append(safe.id()).append(" is too big; ");
        for (java.util.Map.Entry<String, Box> e : l.areas().entrySet()) {
            if (!e.getKey().startsWith("howling_cave")) continue;
            Box b = e.getValue();
            // nearest block centre of the box to the anchor
            double nx = Math.max(b.minX() + 0.5, Math.min(b.maxX() + 0.5, a.x()));
            double nz = Math.max(b.minZ() + 0.5, Math.min(b.maxZ() + 0.5, a.z()));
            double d = Math.hypot(nx - a.x(), nz - a.z());
            if (d < ARRIVAL_CLEAR - 1e-6) bad.append("area ").append(e.getKey()).append(" is ").append(Math.round(d)).append(" from warp.howl; ");
            if (safe != null && b.intersects(safe.box())) bad.append("area ").append(e.getKey()).append(" overlaps the landing; ");
        }
        Layout.Anchor alpha = l.anchor("spawn.alpha");
        if (alpha != null && Math.hypot(alpha.x() - a.x(), alpha.z() - a.z()) < ARRIVAL_CLEAR)
            bad.append("spawn.alpha is too close to warp.howl; ");
        return bad.length() == 0 ? null : bad.toString();
    }

    // v2: /warp howl lands on a SAFE pad clear of the wolves (was inside the Pack Wraith spawn box)
    @Override public int version() { return 2; }

    @Override public Box bounds() { return new Box(-320, 0, -200, 205, 215, 196); }

    /** One floating forest. */
    static final class Isle {
        final String id, name;
        final int cx, cz, baseY;
        Terrain.Heightmap h;
        Isle(String id, String name, int cx, int cz, int baseY) { this.id = id; this.name = name; this.cx = cx; this.cz = cz; this.baseY = baseY; }
    }

    private VoxelCanvas c;
    private Layout layout;
    private long seed;
    private Reservations res = new Reservations();
    private Builders.PathResult paths;
    private final List<int[]> keepPoints = new ArrayList<>();   // {x, z, r}: no trees here
    private int souls = 0;

    @Override
    public void generate(VoxelCanvas canvas, Layout lay, long sd) {
        this.c = canvas; this.layout = lay; this.seed = sd;
        paths = new Builders.PathResult();
        // The game runs a generator more than once (layout ahead of the build, then the build): reset all run state.
        res = new Reservations(); keepPoints.clear(); souls = 0;

        Isle birch = new Isle("birch_park", "Birch Park", 120, 0, 72);
        Isle spruce = new Isle("spruce_woods", "Spruce Woods", 20, -122, 84);
        Isle dark = new Isle("dark_thicket", "Dark Thicket", 18, 118, 90);
        Isle sav = new Isle("savanna_woodland", "Savanna Woodland", -98, 8, 100);
        Isle jungle = new Isle("jungle_island", "Jungle Island", -242, -18, 112);

        // ---------------------------------------------------------------- 1. shapes
        birch.h = shape(birch, 62, 56, 6, 0.45, 40, 0, 0.18);
        spruce.h = shape(spruce, 60, 54, 9, 0.55, 58, 0, 0.16);
        dark.h = shape(dark, 62, 56, 5, 0.6, 44, 0, 0.17);
        sav.h = shape(sav, 60, 58, 5, 0.75, 46, 4, 0.17);
        jungle.h = shape(jungle, 58, 54, 8, 0.5, 48, 0, 0.17);

        // plots (flatten before the terrain is written)
        Box plaza = Box.of(138, 0, -11, 156, 0, 11);
        Box portalPlot = Box.of(157, 0, -6, 164, 0, 6);
        int plazaY = flat(birch.h, plaza, 6);
        birch.h.flatten(portalPlot, plazaY, 4);
        Box lodgePlot = Box.of(104, 0, -37, 121, 0, -21);
        int lodgeY = flat(birch.h, lodgePlot, 5);

        Box cabinPlot = Box.of(25, 0, -152, 41, 0, -137);
        int cabinY = flat(spruce.h, cabinPlot, 5);
        Box cavePlot = Box.of(-7, 0, -101, 7, 0, -88);
        int caveTopY = flat(spruce.h, cavePlot, 5);

        Box wardenPlot = Box.of(25, 0, 95, 41, 0, 110);
        int wardenY = flat(dark.h, wardenPlot, 5);
        Box tentPlot = Box.of(-8, 0, 125, 8, 0, 142);
        int tentY = flat(dark.h, tentPlot, 4);

        int plateauY = sav.baseY + 13;
        plateau(sav.h, -114, -20, 12, plateauY, 0.72, 0.69, 26);
        Box emberPlot = Box.of(-90, 0, 24, -70, 0, 44);
        int emberY = flat(sav.h, emberPlot, 5);

        Box elderPlot = Box.of(-257, 0, -48, -241, 0, -32);
        int elderY = flat(jungle.h, elderPlot, 5);
        Box vikingPlot = Box.of(-234, 0, 3, -212, 0, 22);
        int vikingY = flat(jungle.h, vikingPlot, 5);
        Box shamanPlot = Box.of(-279, 0, -16, -263, 0, 0);
        int shamanY = flat(jungle.h, shamanPlot, 4);
        Box jungleWarpPlot = Box.of(-200, 0, -26, -190, 0, -14);
        int jungleWarpY = flat(jungle.h, jungleWarpPlot, 5);

        // ---------------------------------------------------------------- 2. terrain
        Terrain.SurfaceRules br = new Terrain.SurfaceRules();
        br.cliff = Palette.of("stone:6,andesite:3,mossy_cobblestone:2,cobblestone:1");
        Terrain.fill(c, birch.h, br);

        Terrain.SurfaceRules sr = new Terrain.SurfaceRules();
        sr.stone = Palette.of("stone:8,andesite:3,tuff:2,gravel:1");
        sr.cliff = Palette.of("stone:6,andesite:3,tuff:2,packed_ice:1,snow_block:1");
        sr.underside = Palette.of("stone:6,tuff:3,andesite:2,cobblestone:1");
        Terrain.fill(c, spruce.h, sr);

        Terrain.SurfaceRules dr = new Terrain.SurfaceRules();
        dr.top = Palette.of("grass_block:6,podzol:3,moss_block:1,coarse_dirt:1");
        dr.cliff = Palette.of("mossy_cobblestone:4,stone:4,mossy_stone_bricks:1,andesite:2");
        Terrain.fill(c, dark.h, dr);

        Terrain.SurfaceRules vr = new Terrain.SurfaceRules();
        vr.top = Palette.of("grass_block:6,coarse_dirt:3");
        vr.soil = Palette.of("dirt:3,coarse_dirt:1");
        vr.cliff = Palette.of("terracotta:4,orange_terracotta:3,red_terracotta:2,yellow_terracotta:1,white_terracotta:1,brown_terracotta:1");
        vr.stone = Palette.of("terracotta:4,red_sandstone:2,orange_terracotta:2,stone:2");
        vr.underside = Palette.of("terracotta:3,red_sandstone:2,stone:3,orange_terracotta:1");
        vr.steepSlope = 2;
        Terrain.fill(c, sav.h, vr);

        Terrain.SurfaceRules jr = new Terrain.SurfaceRules();
        jr.top = Palette.of("grass_block:7,moss_block:2,podzol:1");
        jr.cliff = Palette.of("mossy_cobblestone:5,stone:3,moss_block:1,mossy_stone_bricks:1");
        Terrain.fill(c, jungle.h, jr);

        // ---------------------------------------------------------------- 3. water
        Terrain.pond(c, birch.h, 112, 22, 8, 3, seed);
        Terrain.pond(c, birch.h, 92, -2, 5, 2, seed + 1);
        int iceLevel = Terrain.pond(c, spruce.h, 46, -108, 7, 3, seed + 2);
        if (iceLevel != Terrain.Heightmap.NONE) freezePond(46, -108, 10, iceLevel);
        Terrain.pond(c, dark.h, -12, 96, 6, 2, seed + 3);
        Terrain.pond(c, jungle.h, -252, 8, 7, 3, seed + 4);

        // ---------------------------------------------------------------- 4. the Howling Cave
        howlingCave(spruce, caveTopY);

        // ---------------------------------------------------------------- 5. buildings
        // Birch Park: plaza, portal, axe trader stall, ranger lodge
        res.reserve("plaza", plaza);
        Palette paving = Palette.of("stone_bricks:5,polished_andesite:3,mossy_stone_bricks:1,cracked_stone_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) {
                double dz = Math.abs(z), dx = Math.abs(x - 147);
                if (dx > 9 || dz > 11) continue;
                if (dx == 9 && dz == 11) continue;
                c.set(x, plazaY, z, paving.at(x, plazaY, z));
                Builders.footing(c, x, plazaY, z, Fill.of("dirt"), 6);
                for (int a = 1; a <= 4; a++) if (Builders.natural(c.get(x, plazaY + a, z))) c.set(x, plazaY + a, z, "air");
            }
        Builders.fountain(c, 146, plazaY, 0, 3, Palette.of("mossy_stone_bricks:2,stone_bricks:3"), seed);
        res.reserve("portal.hub", GenKit.portal(c, layout, "portal.hub", 160, plazaY, 0, 'z',
                Palette.of("mossy_stone_bricks:3,stone_bricks:3,cracked_stone_bricks:1"),
                Palette.of("stone_bricks:3,polished_andesite:2"), "post"));
        Builders.Stall stall = Builders.marketStall(c, 146, plazaY, -8, "south", "lime", "white", seed);
        Builders.bench(c, 140, plazaY, 5, "north", 3, "birch");
        Builders.bench(c, 151, plazaY, 7, "north", 2, "birch");
        Builders.crates(c, 152, plazaY, -10, seed);
        for (int[] p : new int[][]{{139, -10}, {139, 10}, {155, -10}, {155, 10}})
            Builders.lampPost(c, p[0], plazaY, p[1], "street", p[1] < 0 ? "south" : "north");
        Builders.banner(c, 157, plazaY, -4, "lime", 4);
        Builders.banner(c, 157, plazaY, 4, "lime", 4);

        Builders.House lodge = new Builders.House(107, lodgeY, -35, 11, 8);
        lodge.floors = 2; lodge.door = "south"; lodge.roof = "spruce"; lodge.roofStyle = "gable";
        lodge.wall = Palette.of("birch_planks:6,stripped_birch_log[axis=y]:1");
        lodge.frame = "stripped_spruce_log"; lodge.floor = Fill.of("spruce_planks"); lodge.doorWood = "spruce";
        lodge.chimney = true; lodge.chimneyBlock = "cobblestone"; lodge.seed = seed;
        if (res.reserve("ranger_lodge", lodge.reserve())) lodge.build(c);
        Builders.campfire(c, 104, groundAt(104, -18), -18);
        keep(104, -18, 4);

        // Spruce Woods: keeper's cabin
        Builders.House cabin = new Builders.House(28, cabinY, -150, 10, 8);
        cabin.door = "south"; cabin.roof = "spruce"; cabin.roofStyle = "gable";
        cabin.wall = Palette.of("spruce_planks:5,stripped_spruce_log[axis=y]:1");
        cabin.frame = "stripped_dark_oak_log"; cabin.doorWood = "spruce"; cabin.chimney = true; cabin.seed = seed + 5;
        if (res.reserve("keeper_cabin", cabin.reserve())) cabin.build(c);
        Builders.crates(c, 40, groundAt(40, -139), -139, seed + 6);
        Builders.campfire(c, 22, groundAt(22, -134), -134);
        keep(22, -134, 4);

        // Dark Thicket: warden's house and the strategist's tent
        Builders.House warden = new Builders.House(28, wardenY, 97, 10, 8);
        warden.door = "west"; warden.roof = "dark_oak"; warden.roofStyle = "hip";
        warden.wall = Palette.of("dark_oak_planks:5,stripped_dark_oak_log[axis=y]:1,mossy_cobblestone:1");
        warden.frame = "stripped_dark_oak_log"; warden.doorWood = "dark_oak"; warden.seed = seed + 7;
        if (res.reserve("warden_house", warden.reserve())) warden.build(c);
        Box tentBox = GenKit.tent(c, 0, tentY, 128, "north", "red", "white", 6);
        res.reserve("strategist_tent", tentBox);
        Builders.lampPost(c, -5, tentY, 126, "soul", "north");
        Builders.lampPost(c, 5, tentY, 126, "soul", "north");
        Builders.banner(c, 4, tentY, 124, "red", 8);

        // Savanna: Lyra's plateau (harp ring) and the Ember Trials
        harpRing(-114, plateauY, -20);
        res.reserve("lyras_plateau", Box.of(-124, 0, -30, -104, 0, -10));
        res.reserve("ember_trials", emberPlot);
        emberTrials(-80, emberY, 34);

        // Jungle Island: elder's hut, the norseman's longhouse, the shaman's hut, warp clearing
        Builders.House elder = new Builders.House(-254, elderY, -45, 10, 8);
        elder.door = "east"; elder.roof = "jungle"; elder.roofStyle = "hip";
        elder.wall = Palette.of("jungle_planks:5,stripped_jungle_log[axis=y]:1,bamboo_planks:1");
        elder.frame = "stripped_jungle_log"; elder.doorWood = "jungle"; elder.seed = seed + 8;
        if (res.reserve("elder_hut", elder.reserve())) elder.build(c);

        Builders.House longhouse = new Builders.House(-231, vikingY, 6, 14, 8);
        longhouse.door = "north"; longhouse.roof = "dark_oak"; longhouse.roofStyle = "gable";
        longhouse.wall = Palette.of("spruce_planks:5,stripped_spruce_log[axis=y]:2");
        longhouse.frame = "stripped_dark_oak_log"; longhouse.doorWood = "spruce"; longhouse.seed = seed + 9;
        if (res.reserve("longhouse", longhouse.reserve())) longhouse.build(c);
        Builders.crates(c, -214, groundAt(-214, 4), 4, seed + 10);
        Builders.banner(c, -233, groundAt(-233, 4), 4, "blue", 8);

        Builders.House shaman = new Builders.House(-277, shamanY, -14, 8, 8);
        shaman.door = "east"; shaman.roof = "acacia"; shaman.roofStyle = "hip";
        shaman.wall = Palette.of("mud_bricks:4,packed_mud:2,stripped_acacia_log[axis=y]:1");
        shaman.frame = "stripped_acacia_log"; shaman.doorWood = "acacia"; shaman.furnish = true; shaman.seed = seed + 11;
        if (res.reserve("shaman_hut", shaman.reserve())) shaman.build(c);

        res.reserve("jungle_warp", jungleWarpPlot);
        Palette jungleStone = Palette.of("mossy_stone_bricks:3,stone_bricks:2,mossy_cobblestone:2,cracked_stone_bricks:1");
        for (int z = jungleWarpPlot.minZ() + 1; z <= jungleWarpPlot.maxZ() - 1; z++)
            for (int x = jungleWarpPlot.minX() + 1; x <= jungleWarpPlot.maxX() - 1; x++) c.set(x, jungleWarpY, z, jungleStone.at(x, jungleWarpY, z));
        for (int[] p : new int[][]{{-200, -25}, {-200, -15}, {-190, -25}, {-190, -15}}) Builders.lampPost(c, p[0], jungleWarpY, p[1], "post", "north");

        // ---------------------------------------------------------------- 6. bridges
        int[][] bs = bridge(birch, spruce, "spruce_planks", "spruce_fence");
        int[][] bd = bridge(birch, dark, "dark_oak_planks", "dark_oak_fence");
        int[][] sv = bridge(spruce, sav, "spruce_planks", "spruce_fence");
        int[][] dv = bridge(dark, sav, "dark_oak_planks", "dark_oak_fence");
        int[][] vj = bridge(sav, jungle, "jungle_planks", "jungle_fence");

        // ---------------------------------------------------------------- 7. paths
        Palette bPath = Palette.of("dirt_path:6,coarse_dirt:2,gravel:1");
        int[] bHub = {118, 2};
        walk(new int[][]{{137, 0}, {128, 1}, bHub}, bPath, "birch_slab");
        walk(new int[][]{bHub, {lodge.doorStep[0], lodge.doorStep[2] + 4}, {lodge.doorStep[0], lodge.doorStep[2] + 1}}, bPath, "birch_slab");
        walk(new int[][]{bHub, mid(bHub, bs[0], 4), bs[0]}, bPath, "birch_slab");
        walk(new int[][]{bHub, mid(bHub, bd[0], -5), bd[0]}, bPath, "birch_slab");
        walk(new int[][]{{146, -3}, {stall.customer()[0], stall.customer()[2]}}, bPath, "birch_slab");

        Palette sPath = Palette.of("dirt_path:4,gravel:3,coarse_dirt:2");
        int[] sHub = {14, -110};
        walk(new int[][]{bs[1], mid(bs[1], sHub, 3), sHub}, sPath, "spruce_slab");
        walk(new int[][]{sHub, {12, -92}, {4, -88}, {0, -88}, {0, -91}}, sPath, "spruce_slab");
        walk(new int[][]{sHub, {cabin.doorStep[0], cabin.doorStep[2] + 5}, {cabin.doorStep[0], cabin.doorStep[2] + 1}}, sPath, "spruce_slab");
        walk(new int[][]{sHub, mid(sHub, sv[0], -4), sv[0]}, sPath, "spruce_slab");

        Palette dPath = Palette.of("dirt_path:4,coarse_dirt:3,podzol:1,moss_block:1");
        int[] dHub = {14, 108};
        walk(new int[][]{bd[1], mid(bd[1], dHub, 3), dHub}, dPath, "dark_oak_slab");
        walk(new int[][]{dHub, {warden.doorStep[0] - 5, warden.doorStep[2]}, {warden.doorStep[0] - 1, warden.doorStep[2]}}, dPath, "dark_oak_slab");
        walk(new int[][]{dHub, {2, 118}, {0, 125}}, dPath, "dark_oak_slab");
        walk(new int[][]{dHub, mid(dHub, dv[0], 4), dv[0]}, dPath, "dark_oak_slab");

        Palette vPath = Palette.of("coarse_dirt:4,dirt_path:3,red_sand:1");
        int[] vHub = {-92, 4};
        walk(new int[][]{sv[1], mid(sv[1], vHub, 3), vHub}, vPath, "acacia_slab");
        walk(new int[][]{dv[1], mid(dv[1], vHub, -3), vHub}, vPath, "acacia_slab");
        walk(new int[][]{vHub, {-90, -2}, {-98, -8}}, vPath, "acacia_slab");      // foot of the plateau ramp
        walk(new int[][]{vHub, {-84, 18}, {-80, 27}}, vPath, "acacia_slab");
        walk(new int[][]{vHub, mid(vHub, vj[0], 4), vj[0]}, vPath, "acacia_slab");

        Palette jPath = Palette.of("dirt_path:4,moss_block:2,coarse_dirt:2,mossy_cobblestone:1");
        int[] jHub = {-232, -16};
        walk(new int[][]{vj[1], {-195, -20}, jHub}, jPath, "jungle_slab");
        walk(new int[][]{jHub, {elder.doorStep[0] + 5, elder.doorStep[2]}, {elder.doorStep[0] + 1, elder.doorStep[2]}}, jPath, "jungle_slab");
        walk(new int[][]{jHub, {longhouse.doorStep[0], longhouse.doorStep[2] - 5}, {longhouse.doorStep[0], longhouse.doorStep[2] - 1}}, jPath, "jungle_slab");
        walk(new int[][]{jHub, {shaman.doorStep[0] + 6, shaman.doorStep[2]}, {shaman.doorStep[0] + 1, shaman.doorStep[2]}}, jPath, "jungle_slab");
        walk(new int[][]{jHub, {-246, -2}, {-250, -2}}, jPath, "jungle_slab");

        // lamps along the main routes
        java.util.function.BiPredicate<Integer, Integer> lampKeep = (x, z) -> res.covers(x, z, 1);
        GenKit.lampsAlong(c, new int[][]{bHub, bs[0]}, 14, 3, "post", lampKeep);
        GenKit.lampsAlong(c, new int[][]{bHub, bd[0]}, 14, 3, "post", lampKeep);
        GenKit.lampsAlong(c, new int[][]{{137, 0}, bHub}, 12, 3, "post", lampKeep);
        GenKit.lampsAlong(c, new int[][]{bs[1], sHub, sv[0]}, 14, 3, "post", lampKeep);
        GenKit.lampsAlong(c, new int[][]{bd[1], dHub, dv[0]}, 13, 3, "soul", lampKeep);
        GenKit.lampsAlong(c, new int[][]{sv[1], vHub, vj[0]}, 15, 3, "street", lampKeep);
        GenKit.lampsAlong(c, new int[][]{vj[1], jHub}, 13, 3, "post", lampKeep);

        // ---------------------------------------------------------------- 8. fairy souls (before trees, which keep off them)
        fairySouls(birch, spruce, dark, sav, jungle, plazaY, plateauY, emberY);

        // ---------------------------------------------------------------- 9. plants and trees
        java.util.function.BiPredicate<Integer, Integer> treeKeep = (x, z) -> res.covers(x, z, 4) || paths.near(x, z, 3) || kept(x, z);
        Terrain.grassAndFlowers(c, birch.h, seed, 0.25, 0.06);
        Trees.forest(c, birch.h, birch.h.box(), seed, 0.05, 5, "birch:8,oak:2,big_oak:1", 3, treeKeep);
        Terrain.grassAndFlowers(c, spruce.h, seed + 1, 0.12, 0.01);
        Trees.forest(c, spruce.h, spruce.h.box(), seed + 1, 0.06, 5, "spruce:5,pine:3,mega_spruce:1", 3, treeKeep);
        Terrain.grassAndFlowers(c, dark.h, seed + 2, 0.2, 0.01);
        Trees.forest(c, dark.h, dark.h.box(), seed + 2, 0.085, 6, "dark_oak:12,big_oak:1", 3, treeKeep);
        Terrain.grassAndFlowers(c, sav.h, seed + 3, 0.35, 0.01);
        Trees.forest(c, sav.h, sav.h.box(), seed + 3, 0.025, 8, "acacia:6,oak:1", 3, treeKeep);
        Terrain.grassAndFlowers(c, jungle.h, seed + 4, 0.3, 0.02);
        Trees.forest(c, jungle.h, jungle.h.box(), seed + 4, 0.06, 6, "jungle:5,oak:1", 3, treeKeep);

        GenKit.sprinkle(c, dark.h, dark.h.box(), Hash.salt(seed, "shrooms"), 0.03, Palette.of("red_mushroom:1,brown_mushroom:2,fern:3"),
                "grass_block,podzol,moss_block,coarse_dirt", (x, z) -> paths.covers(x, z));
        GenKit.sprinkle(c, sav.h, sav.h.box(), Hash.salt(seed, "tall"), 0.05, Palette.of("tall_grass:3,dead_bush:1"),
                "grass_block,coarse_dirt", (x, z) -> paths.covers(x, z));
        GenKit.sprinkle(c, jungle.h, jungle.h.box(), Hash.salt(seed, "ferns"), 0.08, Palette.of("fern:3,large_fern:2,melon:1"),
                "grass_block,moss_block,podzol", (x, z) -> paths.covers(x, z) || res.covers(x, z, 1));
        GenKit.sprinkle(c, birch.h, birch.h.box(), Hash.salt(seed, "lilac"), 0.012, Palette.of("lilac:2,rose_bush:1,peony:1"),
                "grass_block", (x, z) -> paths.covers(x, z) || res.covers(x, z, 1));
        cocoa(jungle);
        snow(spruce);

        // ---------------------------------------------------------------- 9. anchors
        layout.standAt("spawn", 152, plazaY, 0, 90f);
        npcOn("npc.axe_trader", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 0f, "spruce_planks");
        npcOn("npc.park_ranger", lodge.doorStep[0] + 2, lodge.doorStep[1], lodge.doorStep[2] + 2, 180f, "birch_planks");
        npcOn("npc.spruce_keeper", cabin.doorStep[0] + 2, cabin.doorStep[1], cabin.doorStep[2] + 2, 180f, "spruce_planks");
        npcOn("npc.thicket_warden", warden.doorStep[0] - 2, warden.doorStep[1], warden.doorStep[2] + 2, -90f, "dark_oak_planks");
        npcOn("npc.strategist", 0, tentY, 124, 180f, "spruce_planks");
        npcOn("npc.harpist", -114, plateauY, -23, 0f, "smooth_sandstone");
        npcOn("npc.ember_trials", -80, emberY, 39, 180f, "stone_bricks");
        npcOn("npc.jungle_elder", elder.doorStep[0] + 2, elder.doorStep[1], elder.doorStep[2] + 2, 90f, "jungle_planks");
        npcOn("npc.merry_norseman", longhouse.doorStep[0] + 3, longhouse.doorStep[1], longhouse.doorStep[2] - 2, 0f, "spruce_planks");
        npcOn("npc.shaman", shaman.doorStep[0] + 2, shaman.doorStep[1], shaman.doorStep[2] - 2, 90f, "mud_bricks");
        layout.standAt("warp.jungle", -195, jungleWarpY, -20, 90f);

        // ---------------------------------------------------------------- 10. zones & areas
        layout.zone("park", "The Park", 'a', new Box(-320, 0, -200, 205, 215, 196), null);
        zone(birch, null);
        zone(spruce, "skill:foraging:2");
        zone(dark, "skill:foraging:3");
        zone(sav, "skill:foraging:4");
        zone(jungle, "skill:foraging:5");
        layout.zone("park_spawn", "Birch Park", 'a', new Box(136, plazaY - 3, -13, 166, plazaY + 12, 13), null, Layout.Zone.SAFE);
        layout.zone("lyras_plateau", "Lyra's Plateau", 'd', new Box(-125, plateauY - 2, -31, -103, plateauY + 12, -9), null);
        layout.zone("ember_trials", "Ember Trials", '6', new Box(emberPlot.minX(), emberY - 3, emberPlot.minZ(), emberPlot.maxX(), emberY + 10, emberPlot.maxZ()), null);
        layout.zone("norse_camp", "Norse Camp", '9', new Box(vikingPlot.minX(), vikingY - 3, vikingPlot.minZ(), vikingPlot.maxX(), vikingY + 14, vikingPlot.maxZ()), null);
        for (Isle i : new Isle[]{birch, spruce, dark, sav, jungle}) {
            Box b = i.h.box();
            layout.area("forest." + i.id, new Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY() + 40, b.maxZ()));
        }


        layout.data("reservations", res.summary());
        layout.data("park.souls", String.valueOf(souls));
    }

    // ===================================================================================== pieces

    private Terrain.Heightmap shape(Isle i, double rx, double rz, double hills, double cliff, double under, int terrace, double coast) {
        Terrain.IslandShape s = new Terrain.IslandShape(Hash.salt(seed, i.id));
        s.centerX = i.cx; s.centerZ = i.cz; s.radiusX = rx; s.radiusZ = rz; s.baseY = i.baseY;
        s.hills = hills; s.hillScale = 42; s.cliff = cliff; s.undersideDepth = under; s.undersideNoise = 0.55;
        s.coastNoise = coast; s.coastScale = 30; s.terraceStep = terrace; s.coastDrop = 5;
        return Terrain.island(s);
    }

    private static int flat(Terrain.Heightmap h, Box plot, int blend) {
        int y = h.average(plot);
        h.flatten(plot, y, blend);
        return y;
    }

    /** A flat-topped mesa of radius r at height py, with a ramp running out along (dx, dz). */
    private static void plateau(Terrain.Heightmap h, int cx, int cz, int r, int py, double dx, double dz, int rampLen) {
        double l = Math.hypot(dx, dz); dx /= l; dz /= l;
        for (int z = cz - r - 2; z <= cz + r + 2; z++)
            for (int x = cx - r - 2; x <= cx + r + 2; x++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > r + 0.5 || !h.has(x, z)) continue;
                h.setTop(x, z, Math.max(h.top(x, z), py));
            }
        double sx = cx + dx * (r - 1), sz = cz + dz * (r - 1);
        for (int t = 0; t <= rampLen; t++) {
            double px = sx + dx * t, pz = sz + dz * t;
            int hy = py - (t + 1) / 2;
            for (int oz = -3; oz <= 3; oz++)
                for (int ox = -3; ox <= 3; ox++) {
                    int x = (int) Math.round(px + ox), z = (int) Math.round(pz + oz);
                    double side = Math.abs((x - px) * -dz + (z - pz) * dx);
                    if (side > 2.2 || !h.has(x, z)) continue;
                    if (h.top(x, z) < hy) h.setTop(x, z, hy);
                }
        }
    }

    private void freezePond(int cx, int cz, int r, int level) {
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++)
                if (BlockState.name(c.get(x, level, z)).equals("water")) c.set(x, level, z, "ice");
    }

    private void howlingCave(Isle s, int entY) {
        Palette rock = Palette.of("stone:6,tuff:3,andesite:2,cobblestone:2,mossy_cobblestone:1");
        Palette floor = Palette.of("gravel:3,coarse_dirt:3,stone:3,moss_block:1,podzol:1");
        int floorY = CAVE_FLOOR_Y;
        // the arch on the surface
        Palette arch = Palette.of("mossy_stone_bricks:3,cracked_stone_bricks:2,stone_bricks:2,mossy_cobblestone:1");
        for (int x = -4; x <= 4; x++)
            for (int a = 1; a <= 6; a++) {
                boolean post = Math.abs(x) >= 3;
                if (post || a >= 5 || (a == 4 && Math.abs(x) == 2)) c.set(x, entY + a, -93, arch.at(x, entY + a, -93));
            }
        for (int x = -3; x <= 3; x++) c.set(x, entY + 7, -93, Blocks.slab("mossy_stone_brick", "bottom"));
        c.set(-3, entY + 7, -93, Blocks.soulLantern(false));
        c.set(3, entY + 7, -93, Blocks.soulLantern(false));
        res.reserve("cave_arch", Box.of(-6, 0, -95, 6, 0, -91));
        for (int[] k : new int[][]{{0, -96}, {-6, -100}, {-12, -103}, {-16, -110}}) keep(k[0], k[1], 5);
        // chambers first, then the tunnels that open into them (tunnel walls never fill a chamber)
        Box main = GenKit.chamber(c, 10, floorY, -122, 24, 16, 11, floor, rock, Hash.salt(seed, "cave"));
        Box den = GenKit.chamber(c, 15, floorY - 1, -146, 10, 8, 8, floor, rock, Hash.salt(seed, "den"));
        List<Box> keepOpen = List.of(main.grow(1), den.grow(1));
        GenKit.tunnel(c, s.h, new int[][]{{0, -94}, {0, -98}, {-14, -104}, {-18, -118}, {-8, -126}, {2, -124}},
                entY, floorY, 2.0, 5, floor, rock, keepOpen);
        GenKit.tunnel(c, s.h, new int[][]{{14, -130}, {15, -140}}, floorY, floorY - 1, 2.0, 5, floor, rock, keepOpen);
        // dress the cave: soul lanterns, lichen, bones, webs
        long ds = Hash.salt(seed, "cave-dress");
        dressCave(main, floorY, ds);
        dressCave(den, floorY - 1, ds + 1);
        for (int[] p : new int[][]{{-4, -120}, {10, -110}, {24, -124}, {12, -133}, {4, -128}, {18, -116}})
            lantern(p[0], p[1], floorY);
        for (int[] p : new int[][]{{15, -146}, {9, -144}, {21, -148}}) lantern(p[0], p[1], floorY - 1);
        // the Pack Wraiths keep to the east of the chamber: their box starts ARRIVAL_CLEAR east of the landing
        layout.area("howling_cave", new Box(HOWL_X + ARRIVAL_CLEAR, floorY + 1, -134, 30, floorY + 3, -110));
        layout.area("howling_cave.deep", new Box(8, floorY, -152, 22, floorY + 2, -140));
        layout.standAt("spawn.alpha", 15, floorY - 1, -147, 0f);
        GenKit.npcSpot(c, 15, floorY - 1, -147, "coarse_dirt");
        // the landing: a level 5x5 pad (after the dressing, so no cobweb or bone sits on it), lit, facing east
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                c.set(HOWL_X + dx, floorY, HOWL_Z + dz, floor.at(HOWL_X + dx, floorY, HOWL_Z + dz));
                for (int y = floorY + 1; y <= floorY + 3; y++) c.set(HOWL_X + dx, y, HOWL_Z + dz, "air");
            }
        GenKit.npcSpot(c, HOWL_X, floorY, HOWL_Z, "gravel");
        lantern(HOWL_X - 3, HOWL_Z - 3, floorY);
        layout.standAt("warp.howl", HOWL_X, floorY, HOWL_Z, -90f);
        layout.zone("howling_cave", "Howling Cave", 'c', new Box(-22, floorY - 2, -158, 36, floorY + 14, -104), null);
        layout.zone("howl_landing", "Cave Mouth", 'c', howlLanding(), null, Layout.Zone.SAFE);
    }

    private void dressCave(Box b, int floorY, long ds) {
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int y = floorY; y <= floorY + 2; y++) {
                    if (!c.isAir(x, y + 1, z) || c.isAir(x, y, z)) continue;
                    double u = Hash.unit(ds, x, y, z);
                    if (u < 0.015) c.set(x, y + 1, z, "cobweb");
                    else if (u < 0.03) c.set(x, y + 1, z, "bone_block[axis=" + (u < 0.022 ? "x" : "z") + "]");
                    else if (u < 0.045) c.set(x, y + 1, z, "brown_mushroom");
                    break;
                }
                // ceiling lichen
                for (int y = floorY + 12; y >= floorY + 3; y--) {
                    if (c.isAir(x, y, z) && !c.isAir(x, y + 1, z) && Hash.unit(ds ^ 77, x, y, z) < 0.12) {
                        c.set(x, y, z, "glow_lichen[up=true,down=false,north=false,south=false,east=false,west=false,waterlogged=false]");
                        break;
                    }
                }
            }
    }

    private void lantern(int x, int z, int floorY) {
        for (int y = floorY; y <= floorY + 3; y++) {
            if (!c.isAir(x, y, z) && c.isAir(x, y + 1, z)) {
                c.set(x, y + 1, z, "polished_blackstone_wall");
                c.set(x, y + 2, z, Blocks.soulLantern(false));
                return;
            }
        }
    }

    /** Lyra's harp ring: a sandstone dais with note blocks, gold posts and flowers. */
    private void harpRing(int cx, int y, int cz) {
        Palette dais = Palette.of("smooth_sandstone:4,cut_sandstone:2,chiseled_sandstone:1");
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.hypot(dx, dz);
                if (d > 6.4) continue;
                int x = cx + dx, z = cz + dz;
                c.set(x, y, z, d > 5.4 ? "cut_sandstone" : dais.at(x, y, z));
                for (int a = 1; a <= 4; a++) if (Builders.natural(c.get(x, y + a, z))) c.set(x, y + a, z, "air");
            }
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int x = cx + (int) Math.round(Math.cos(a) * 5), z = cz + (int) Math.round(Math.sin(a) * 5);
            c.set(x, y + 1, z, "note_block");
            if (k % 2 == 0) { c.set(x, y + 2, z, "gold_block"); c.set(x, y + 3, z, Blocks.lantern(false)); }
            else c.set(x, y + 2, z, "potted_allium");
        }
        c.set(cx, y + 1, cz, "chiseled_sandstone");
        c.set(cx, y + 2, cz, "gold_block");
        c.set(cx, y + 3, cz, "end_rod[facing=up]");
    }

    /** The Ember Trials: a fire pit ringed by stone, log benches and braziers. */
    private void emberTrials(int cx, int y, int cz) {
        Palette ring = Palette.of("stone_bricks:3,cracked_stone_bricks:1,polished_blackstone_bricks:1");
        for (int dz = -7; dz <= 7; dz++)
            for (int dx = -7; dx <= 7; dx++) {
                double d = Math.hypot(dx, dz);
                if (d > 7.4) continue;
                int x = cx + dx, z = cz + dz;
                c.set(x, y, z, d < 2.5 ? "magma_block" : d > 6.4 ? "polished_blackstone_bricks" : ring.at(x, y, z));
                for (int a = 1; a <= 4; a++) if (Builders.natural(c.get(x, y + a, z))) c.set(x, y + a, z, "air");
            }
        c.set(cx, y, cz, "netherrack");
        c.set(cx, y + 1, cz, "fire");
        for (int d = 0; d < 4; d++) {
            int x = cx + Blocks.DX[d] * 4, z = cz + Blocks.DZ[d] * 4;
            c.set(x, y + 1, z, "stripped_acacia_log[axis=" + (Blocks.DX[d] == 0 ? 'x' : 'z') + "]");
            int bx = cx + (Blocks.DX[d] + Blocks.DZ[d]) * 5, bz = cz + (Blocks.DZ[d] - Blocks.DX[d]) * 5;
            c.set(bx, y + 1, bz, "polished_blackstone_wall");
            c.set(bx, y + 2, bz, Blocks.campfire(true, "north"));
        }
    }

    /** A bridge between two isles: heads inland on each, plank deck, railings. Returns {headA, headB}. */
    private int[][] bridge(Isle a, Isle b, String deck, String fence) {
        int[] pa = GenKit.edgePoint(a.h, a.cx, a.cz, b.cx, b.cz, 0.86);
        int[] pb = GenKit.edgePoint(b.h, b.cx, b.cz, a.cx, a.cz, 0.86);
        int ya = groundAt(pa[0], pa[1]), yb = groundAt(pb[0], pb[1]);
        Builders.bridge(c, pa[0], ya, pa[1], pb[0], yb, pb[1], 3, Fill.of(deck),
                fence, "stripped_" + deck.replace("_planks", "_log"), deck.replace("planks", "stairs"));
        // pillars of rope (chains) hanging under the middle for looks
        int mx = (pa[0] + pb[0]) / 2, mz = (pa[1] + pb[1]) / 2;
        int my = (ya + yb) / 2;
        for (int k = 1; k <= 4; k++) if (c.isAir(mx, my - k - 1, mz)) c.set(mx, my - k - 1, mz, "iron_chain[axis=y]");
        res.reserve("bridge_head_" + a.id + "_" + b.id, Box.of(pa[0] - 2, 0, pa[1] - 2, pa[0] + 2, 0, pa[1] + 2));
        res.reserve("bridge_head_" + b.id + "_" + a.id, Box.of(pb[0] - 2, 0, pb[1] - 2, pb[0] + 2, 0, pb[1] + 2));
        markBridge(pa, pb);
        return new int[][]{pa, pb};
    }

    /** Remember a bridge line so trees keep off the deck where it crosses land. */
    private void markBridge(int[] pa, int[] pb) {
        int n = (int) Math.ceil(Math.hypot(pb[0] - pa[0], pb[1] - pa[1]));
        for (int i = 0; i <= n; i += 3) {
            double t = (double) i / Math.max(1, n);
            keep((int) Math.round(pa[0] + (pb[0] - pa[0]) * t), (int) Math.round(pa[1] + (pb[1] - pa[1]) * t), 4);
        }
    }

    private void walk(int[][] pts, Fill surface, String step) {
        Builders.path(c, pts, 3, surface, step, paths);
    }

    private static int[] mid(int[] a, int[] b, int wobble) {
        int mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2;
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double l = Math.max(1, Math.hypot(dx, dz));
        return new int[]{(int) Math.round(mx - dz / l * wobble), (int) Math.round(mz + dx / l * wobble)};
    }

    private void keep(int x, int z, int r) { keepPoints.add(new int[]{x, z, r}); }

    private boolean kept(int x, int z) {
        for (int[] k : keepPoints) if (Math.abs(k[0] - x) <= k[2] && Math.abs(k[1] - z) <= k[2]) return true;
        return false;
    }

    private int groundAt(int x, int z) {
        int g = GenKit.ground(c, x, z);
        return g == Integer.MIN_VALUE ? 70 : g;
    }

    private void npcOn(String name, int x, int y, int z, float yaw, String block) {
        GenKit.npcSpot(c, x, y, z, block);
        layout.standAt(name, x, y, z, yaw);
        keep(x, z, 3);
    }

    private void zone(Isle i, String req) {
        Box b = i.h.box();
        layout.zone(i.id, i.name, 'a', new Box(b.minX(), 0, b.minZ(), b.maxX(), 215, b.maxZ()), req);
    }

    /** Cocoa pods on jungle trunks. */
    private void cocoa(Isle j) {
        Box b = j.h.box();
        long s = Hash.salt(seed, "cocoa");
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int y = j.baseY; y <= j.baseY + 30; y++) {
                    if (!BlockState.name(c.get(x, y, z)).equals("jungle_log")) continue;
                    if (!Hash.chance(s, x, y, z, 0.05)) continue;
                    for (int d = 0; d < 4; d++) {
                        int nx = x + Blocks.DX[d], nz = z + Blocks.DZ[d];
                        if (!c.isAir(nx, y, nz)) continue;
                        c.set(nx, y, nz, "cocoa[age=2,facing=" + Blocks.opposite(Blocks.DIRS[d]) + "]");
                        break;
                    }
                }
    }

    /** Snow layers on Spruce Woods' grass. */
    private void snow(Isle s) {
        long sd = Hash.salt(seed, "snow");
        s.h.forEach((x, z, top, bottom) -> {
            int g = GenKit.groundBelow(c, x, z, top + 1);
            if (g == Integer.MIN_VALUE) return;
            String n = BlockState.name(c.get(x, g, z));
            if (!n.equals("grass_block")) return;
            if (paths.covers(x, z)) return;
            if (!c.isAir(x, g + 1, z) && !BlockState.name(c.get(x, g + 1, z)).equals("short_grass")) return;
            if (!Hash.chance(sd, x, z, 0.8)) return;
            c.set(x, g, z, "grass_block[snowy=true]");
            c.set(x, g + 1, z, "snow[layers=1]");
        });
    }

    // ===================================================================================== fairy souls

    private void soul(int x, int z, int r) {
        int[] p = GenKit.standNear(c, x, z, r, null);
        if (p == null) return;
        souls++;
        layout.standAt("fairy_soul." + souls, p[0], p[1], p[2], 0f);
        keep(p[0], p[2], 2);
    }

    private void caveSoul(int x, int z, int fromY) {
        int[] p = GenKit.standNear(c, x, z, 4, null, fromY);
        if (p == null) return;
        souls++;
        layout.standAt("fairy_soul." + souls, p[0], p[1], p[2], 0f);
    }

    private void soulAt(int x, int y, int z) {
        keep(x, z, 3);
        souls++;
        layout.standAt("fairy_soul." + souls, x, y, z, 0f);
    }

    private void fairySouls(Isle birch, Isle spruce, Isle dark, Isle sav, Isle jungle, int plazaY, int plateauY, int emberY) {
        Palette mossy = Palette.of("mossy_cobblestone:3,stone:3,andesite:2,moss_block:1");
        Palette sandRock = Palette.of("terracotta:3,orange_terracotta:2,red_sandstone:1");
        // Birch Park (4): on a boulder, behind the lodge, the pond shore, the east cliff edge
        int[] b1 = GenKit.boulder(c, 96, 26, mossy, "north");
        if (b1 != null) soulAt(b1[0], b1[1], b1[2]);
        soul(112, -38, 3);
        soul(124, 22, 3);
        soul(birch.cx + 10, birch.cz + 45, 4);
        // Spruce Woods (5): two in the cave, the den, behind the cabin, the frozen pond
        caveSoul(-2, -132, 62);   // v2: was (-6, -118), which is now the /warp howl landing pad
        caveSoul(30, -126, 62);
        caveSoul(21, -150, 61);
        soul(33, -154, 3);
        soul(52, -101, 3);
        // Dark Thicket (4): behind the tent, a boulder, the warden's back yard, the south rim
        soul(0, 145, 3);
        int[] d2 = GenKit.boulder(c, 40, 132, mossy, "west");
        if (d2 != null) soulAt(d2[0], d2[1], d2[2]);
        soul(44, 104, 3);
        soul(dark.cx - 30, dark.cz + 35, 4);
        // Savanna (4): plateau rim, under the plateau, the trials, the far west
        soul(-120, -14, 2);
        soul(-128, -30, 4);
        soul(-72, 42, 3);
        int[] v4 = GenKit.boulder(c, -70, -20, sandRock, "south");
        if (v4 != null) soulAt(v4[0], v4[1], v4[2]);
        // Jungle Island (4): behind the shaman, the pool, the longhouse back, the north rim
        soul(-282, -6, 3);
        soul(-262, 12, 3);
        soul(-226, 25, 3);
        soul(jungle.cx + 5, jungle.cz - 40, 4);
    }
}
