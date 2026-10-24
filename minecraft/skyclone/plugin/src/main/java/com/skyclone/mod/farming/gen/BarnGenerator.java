package com.skyclone.mod.farming.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * THE BARN (research 03 section 5): rolling farmland around a red barn and a windmill landmark. Wheat, carrot
 * and potato fields, a pumpkin patch, a melon patch, sugar cane rows by the mill pond, a cocoa grove on a
 * jungle-log wall, a flower meadow, five animal pens, and the fairgrounds where the Harvest Fair host,
 * Harvesta and the farmhand stand. Portal back to the hub in the south, to the Mushroom Desert in the north.
 *
 * <pre>
 *            N  (portal.desert)
 *   wheat field        |      chicken coop   pig pen    rabbit hutch
 *   cocoa grove  mill  |      BARN    cow pasture   sheep fold
 *  --------------------+---------------------------------------
 *   carrots   potatoes |      pumpkins      melons
 *   meadow  fairground | spawn  cane rows    mill pond
 *            S  (portal.hub)
 * </pre>
 */
public final class BarnGenerator implements IslandGenerator {

    @Override public String islandId() { return "barn"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-150, 10, -150, 150, 150, 150); }

    private VoxelCanvas c;
    private Terrain.Heightmap h;
    private Layout layout;
    private Reservations res;
    private final List<int[]> souls = new ArrayList<>();

    @Override
    public void generate(VoxelCanvas canvas, Layout layout, long seed) {
        this.c = canvas;
        this.layout = layout;
        this.res = new Reservations();
        souls.clear();

        // ---- 1. terrain ------------------------------------------------------------------------------
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 128; s.radiusZ = 122; s.baseY = 70;
        s.hills = 4; s.hillScale = 60; s.cliff = 0.7; s.coastNoise = 0.12; s.coastScale = 40;
        s.undersideDepth = 34; s.coastDrop = 3;
        h = Terrain.island(s);

        // plots (x0, z0, x1, z1) - flattened BEFORE the terrain is written
        Box plaza = Box.of(-11, 0, 72, 11, 0, 92);
        Box portalS = Box.of(-7, 0, 94, 7, 0, 106);
        Box portalN = Box.of(-7, 0, -110, 7, 0, -98);
        Box wheat = Box.of(-94, 0, -84, -42, 0, -50);
        Box cocoa = Box.of(-100, 0, -36, -44, 0, -24);
        Box mill = Box.of(-38, 0, -44, -14, 0, -18);
        Box barnPlot = Box.of(10, 0, -50, 36, 0, -12);
        Box cows = Box.of(44, 0, -50, 66, 0, -26);
        Box sheep = Box.of(74, 0, -50, 100, 0, -24);
        Box chickens = Box.of(12, 0, -86, 32, 0, -62);
        Box pigs = Box.of(42, 0, -88, 64, 0, -64);
        Box rabbits = Box.of(74, 0, -86, 94, 0, -64);
        Box carrots = Box.of(-98, 0, 12, -58, 0, 42);
        Box potatoes = Box.of(-50, 0, 12, -14, 0, 42);
        Box meadow = Box.of(-94, 0, 54, -64, 0, 76);
        Box fair = Box.of(-56, 0, 54, -16, 0, 86);
        Box pumpkins = Box.of(14, 0, 12, 44, 0, 40);
        Box melons = Box.of(54, 0, 12, 86, 0, 40);
        Box cane = Box.of(14, 0, 54, 46, 0, 80);
        Box pond = Box.of(56, 0, 52, 92, 0, 86);

        int y0 = 71;                                  // the farmland plateau
        for (Box b : List.of(plaza, portalS, wheat, cocoa, mill, barnPlot, cows, sheep, chickens, pigs, rabbits,
                carrots, potatoes, meadow, fair, pumpkins, melons, cane, pond))
            h.flatten(b, y0, 6);
        h.flatten(portalN, y0, 6);

        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.soil = Palette.of("dirt:6,coarse_dirt:1");
        Terrain.fill(c, h, rules);

        // ---- 2. the mill pond (carve before anything stands near it) --------------------------------
        int pondLevel = Terrain.pond(c, h, 74, 70, 11, 3, seed);

        // ---- 3. fields ----------------------------------------------------------------------------------
        Box wheatF = field(wheat, "wheat", "wheat[age=7]", 9);
        layout.zone("barn.wheat", "Wheat Fields", 'e', zoneBox(wheat, y0), null);
        Box carrotF = field(carrots, "carrots", "carrots[age=7]", 9);
        layout.zone("barn.carrots", "Carrot Field", '6', zoneBox(carrots, y0), null);
        Box potatoF = field(potatoes, "potatoes", "potatoes[age=7]", 9);
        layout.zone("barn.potatoes", "Potato Field", 'e', zoneBox(potatoes, y0), null);
        res.reserve("pumpkins", grow(pumpkins, 1));
        FarmKit.fruitPatch(c, pumpkins.minX(), pumpkins.minZ(), pumpkins.maxX(), pumpkins.maxZ(), y0, "pumpkin");
        layout.zone("barn.pumpkins", "Pumpkin Patch", '6', zoneBox(pumpkins, y0), null);
        res.reserve("melons", grow(melons, 1));
        FarmKit.fruitPatch(c, melons.minX(), melons.minZ(), melons.maxX(), melons.maxZ(), y0, "melon");
        layout.zone("barn.melons", "Melon Patch", 'a', zoneBox(melons, y0), null);
        res.reserve("cane", grow(cane, 1));
        FarmKit.caneRows(c, cane.minX(), cane.minZ(), cane.maxX(), cane.maxZ(), y0, "sand");
        layout.zone("barn.cane", "Cane Rows", 'a', zoneBox(cane, y0), null);
        res.reserve("meadow", grow(meadow, 1));
        FarmKit.flowerMeadow(c, meadow.minX(), meadow.minZ(), meadow.maxX(), meadow.maxZ(), y0);
        layout.zone("barn.meadow", "Flower Meadow", 'd', zoneBox(meadow, y0), null);
        res.reserve("cocoa", cocoa);
        FarmKit.cocoaWall(c, cocoa.minX() + 2, cocoa.maxX() - 2, -30, y0, 4);
        FarmKit.cocoaWall(c, cocoa.minX() + 2, cocoa.maxX() - 2, -25, y0, 4);
        layout.zone("barn.cocoa", "Cocoa Grove", '6', zoneBox(cocoa, y0), null);
        souls.add(new int[]{cocoa.minX() + 1, y0, -28});

        // ---- 4. the windmill ------------------------------------------------------------------------------
        res.reserve("windmill", mill);
        paveRect(mill, y0, Palette.of("cobblestone:3,gravel:2,coarse_dirt:2,mossy_cobblestone:1"));
        Builders.Tower tower = FarmKit.windmill(c, -26, y0, -31, "south", "north");
        layout.standAt("npc.windmill_operator", tower.inside[0], tower.inside[1], tower.inside[2], 180f);
        layout.standAt("npc.farm_guide", tower.doorStep[0] + 3, y0, tower.doorStep[2] + 2, 180f);
        souls.add(new int[]{-26 + 1, y0 + 6, -31 + 1});                          // second floor (ladder inside)
        layout.zone("barn.windmill", "Windmill", 'f', Box.of(mill.minX(), y0 - 3, mill.minZ(), mill.maxX(), y0 + 40, mill.maxZ()), null);
        FarmKit.hayPile(c, -34, y0, -22, "east");
        Builders.crates(c, -18, y0, -40, seed);

        // ---- 5. the barn and the barnyard -------------------------------------------------------------------
        res.reserve("barnyard", barnPlot);
        paveRect(Box.of(barnPlot.minX(), 0, -30, barnPlot.maxX(), 0, barnPlot.maxZ()), y0, Palette.of("coarse_dirt:3,dirt_path:3,gravel:1,dirt:2"));
        Builders.House barn = new Builders.House(14, y0, -50, 21, 15);
        barn.floors = 2; barn.floorHeight = 5; barn.door = "south";
        barn.wall = Palette.of("red_terracotta:6,red_concrete:1,stripped_mangrove_log[axis=y]:1");
        barn.frame = "stripped_birch_log"; barn.roof = "dark_oak"; barn.roofStyle = "gable";
        barn.foundation = Palette.of("stone_bricks:3,cobblestone:2"); barn.floor = Palette.of("spruce_planks:4,hay_block[axis=x]:1");
        barn.window = "spruce_trapdoor[facing=south,half=bottom,open=true]"; barn.doorWood = "spruce"; barn.seed = seed;
        barn.build(c);
        souls.add(new int[]{14 + 21 - 3, y0 + 5, -50 + 15 - 3});              // hay loft
        layout.zone("barn.barn", "Red Barn", 'c', Box.of(14, y0 - 2, -50, 34, barn.peakY, -36), null);
        int[] hay = FarmKit.hayPile(c, 30, y0, -22, "west");
        souls.add(hay);
        FarmKit.hayPile(c, 16, y0, -20, "east");
        Builders.well(c, 24, y0, -18);
        layout.zone("barn.yard", "Barnyard", 'e', zoneBox(Box.of(barnPlot.minX(), 0, -34, barnPlot.maxX(), 0, barnPlot.maxZ()), y0), null);

        // ---- 6. pens --------------------------------------------------------------------------------------------
        pen("cow", "Cow Pasture", cows, y0, "west", seed);
        pen("sheep", "Sheep Fold", sheep, y0, "west", seed);
        pen("chicken", "Chicken Coop", chickens, y0, "south", seed);
        pen("pig", "Pig Pen", pigs, y0, "south", seed);
        pen("rabbit", "Rabbit Hutch", rabbits, y0, "south", seed);
        // a little coop hut inside the chicken run
        for (int x = 14; x <= 18; x++) for (int z = -84; z <= -81; z++) {
            c.set(x, y0 + 1, z, x == 14 || x == 18 || z == -84 ? "oak_planks" : "hay_block[axis=y]");
            c.set(x, y0 + 2, z, Blocks.slab("spruce", "bottom"));
        }
        souls.add(new int[]{28, y0, -66});

        // ---- 7. spawn plaza, fairgrounds and portals ---------------------------------------------------------
        res.reserve("plaza", plaza);
        paveRect(plaza, y0, Palette.of("stone_bricks:5,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1"));
        Builders.fountain(c, 0, y0, 82, 3, Palette.of("stone_bricks:4,mossy_stone_bricks:1"), seed);
        for (int[] p : new int[][]{{-10, 73}, {10, 73}, {-10, 91}, {10, 91}}) Builders.lampPost(c, p[0], y0, p[1], "post", "north");
        Builders.bench(c, -8, y0, 78, "east", 3, "spruce");
        Builders.bench(c, 8, y0, 86, "west", 3, "spruce");
        layout.standAt("spawn", 0, y0, 90, 180f);
        layout.zone("barn.plaza", "Barn Square", 'b', zoneBox(plaza, y0), null, Layout.Zone.SAFE);

        res.reserve("portal.hub", portalS);
        Box hubPortal = FarmKit.portal(c, 0, y0, 101, true, Palette.of("oak_log[axis=y]:3,stripped_oak_log[axis=y]:1"), "nether_portal");
        layout.area("portal.hub", hubPortal);
        c.set(-3, y0 + 6, 101, Blocks.lantern(false));
        c.set(3, y0 + 6, 101, Blocks.lantern(false));
        souls.add(new int[]{5, y0, 104});

        res.reserve("portal.desert", portalN);
        Box desertPortal = FarmKit.portal(c, 0, y0, -104, true, Palette.of("cut_red_sandstone:3,chiseled_red_sandstone:1"), "nether_portal");
        layout.area("portal.desert", desertPortal);
        souls.add(new int[]{-5, y0, -108});

        res.reserve("fairgrounds", fair);
        fairgrounds(fair, y0, seed);

        // ---- 7b. farmhouse, grain silo, scarecrows ---------------------------------------------------------------
        Box houseLot = Box.of(-38, 0, -90, -24, 0, -76);
        Builders.House farmhouse = new Builders.House(-36, y0, -88, 11, 9);
        farmhouse.door = "east"; farmhouse.floors = 2; farmhouse.roof = "spruce"; farmhouse.roofStyle = "gable"; farmhouse.chimney = true;
        farmhouse.wall = Palette.of("oak_planks:5,stripped_oak_log[axis=y]:1"); farmhouse.seed = seed;
        if (res.reserve("farmhouse", farmhouse.reserve())) {
            paveRect(houseLot, y0, Palette.of("grass_block:4,coarse_dirt:1"));
            farmhouse.build(c);
            souls.add(new int[]{farmhouse.inside[0], farmhouse.inside[1] + 4, farmhouse.inside[2] + 1});
        }
        Builders.Tower silo = new Builders.Tower(-14, y0, -58);
        silo.radius = 3; silo.height = 15; silo.top = "cone"; silo.door = "south";
        silo.wall = Palette.of("polished_andesite:3,stone_bricks:1");
        silo.roofFill = Palette.of("weathered_cut_copper:2,oxidized_cut_copper:1,cut_copper:1");
        if (res.reserve("silo", silo.reserve())) silo.build(c);
        for (int[] sc : new int[][]{{-38, -46}, {-60, 46}, {-10, 46}, {48, 44}, {90, 8}, {-102, -46}}) {
            int g = Builders.ground(c, sc[0], sc[1], y0 + 8, y0 - 8);
            if (g != Integer.MIN_VALUE && !res.covers(sc[0], sc[1], 1) && "grass_block".equals(com.skyclone.gen.BlockState.name(c.get(sc[0], g, sc[1]))))
                FarmKit.scarecrow(c, sc[0], g, sc[1], "south");
        }

        // ---- 8. roads ---------------------------------------------------------------------------------------------
        Fill road = Palette.of("dirt_path:6,coarse_dirt:2,gravel:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{0, 94}, {0, 60}, {0, 0}, {0, -60}, {0, -98}}, 5, road, "spruce_stairs", null);
        Builders.path(c, new int[][]{{-106, 0}, {-60, 0}, {0, 0}, {60, 0}, {106, 0}}, 5, road, "spruce_stairs", paths);
        // spurs
        int[][][] spurs = {
                {{-2, -68}, {-40, -68}},                               // wheat
                {{-2, -30}, {-14, -30}},                               // windmill
                {{-2, -26}, {-44, -26}},                               // cocoa grove
                {{2, -30}, {10, -30}},                                 // barnyard
                {{2, -38}, {40, -38}, {43, -38}},                      // cow gate
                {{2, -92}, {70, -92}, {70, -38}, {73, -38}},           // back lane: coop, pigs, rabbits, sheep
                {{22, -92}, {22, -87}}, {{53, -92}, {53, -89}}, {{85, -92}, {85, -89}},
                {{-2, 27}, {-12, 27}},                                 // potatoes
                {{-52, 4}, {-52, 50}, {-60, 50}},                      // carrots/meadow lane
                {{2, 26}, {12, 26}},                                   // pumpkins
                {{50, 4}, {50, 26}, {52, 26}},                         // melons
                {{2, 66}, {12, 66}},                                   // cane
                {{48, 66}, {58, 66}},                                  // pond
                {{-12, 80}, {-16, 80}},                                // fairgrounds
                {{-2, -84}, {-23, -84}},                               // farmhouse
                {{-2, -53}, {-14, -53}},                               // silo
        };
        for (int[][] sp : spurs) Builders.path(c, sp, 3, road, "spruce_stairs", paths);
        FarmKit.lampsAlong(c, new int[][]{{0, 70}, {0, -96}}, 14, 3, "street");
        FarmKit.lampsAlong(c, new int[][]{{-104, 0}, {104, 0}}, 14, 3, "street");
        FarmKit.lampsAlong(c, new int[][]{{2, -92}, {70, -92}, {70, -38}}, 14, 2, "post");

        // ---- 9. the pond: dock and reeds --------------------------------------------------------------------------
        if (pondLevel != Terrain.Heightmap.NONE) {
            for (int x = 61; x <= 66; x++) for (int z = 69; z <= 71; z++) {
                c.set(x, pondLevel + 1, z, "spruce_planks");
                if (x > 62 && (z == 69 || z == 71) && x % 2 == 0) c.set(x, pondLevel, z, "spruce_log[axis=y]");
            }
            c.set(66, pondLevel + 2, 69, "spruce_fence");
            c.set(66, pondLevel + 2, 71, "spruce_fence");
            c.set(66, pondLevel + 3, 69, Blocks.lantern(false));
            souls.add(new int[]{65, pondLevel + 1, 70});
            layout.zone("barn.pond", "Mill Pond", '3', Box.of(pond.minX(), pondLevel - 6, pond.minZ(), pond.maxX(), pondLevel + 12, pond.maxZ()), null);
            // the dock joins the bank
            for (int x = 56; x <= 60; x++) for (int z = 69; z <= 71; z++) {
                int g = Builders.ground(c, x, z, y0 + 3, y0 - 8);
                if (g == Integer.MIN_VALUE || g < pondLevel) c.set(x, pondLevel + 1, z, "spruce_planks");
            }
        }

        // ---- 10. greenery -----------------------------------------------------------------------------------------
        Terrain.grassAndFlowers(c, h, seed, 0.25, 0.03);
        Trees.forest(c, h, h.box(), seed, 0.03, 6, "oak:5,birch:3,big_oak:1", 4,
                (x, z) -> res.covers(x, z, 5) || paths.near(x, z, 4) || nearField(x, z));
        // a few big landmark oaks along the west edge
        for (int[] t : new int[][]{{-112, 22}, {-108, -12}, {108, 52}, {112, 18}}) {
            int g = h.top(t[0], t[1]);
            if (g != Terrain.Heightmap.NONE && !h.wet(t[0], t[1])) Trees.grow(c, "big_oak", t[0], g, t[1], seed ^ t[0], 1.15);
        }
        soulOnGround(-112, 26);
        soulOnGround(102, 84);
        soulOnGround(104, -36);
        soulOnGround(meadow.maxX() + 1, meadow.maxZ() + 1);

        // ---- 11. layout facts -----------------------------------------------------------------------------------------
        Box isl = h.box();
        layout.zone("barn", "The Barn", 'e', new Box(isl.minX(), 0, isl.minZ(), isl.maxX(), 255, isl.maxZ()), null);
        layout.area("fields", Box.of(-100, y0 - 1, -90, 100, y0 + 4, 90));
        layout.data("reservations", res.summary());
        FarmKit.souls(c, layout, souls);
    }

    // ---------------------------------------------------------------------------------------------------------

    private Box field(Box plot, String name, String crop, int water) {
        res.reserve(name, grow(plot, 1));
        return FarmKit.cropField(c, plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ(), 71, crop, water);
    }

    private boolean nearField(int x, int z) { return false; }

    private static Box grow(Box b, int n) { return Box.of(b.minX() - n, b.minY(), b.minZ() - n, b.maxX() + n, b.maxY(), b.maxZ() + n); }

    private static Box zoneBox(Box plot, int y) { return Box.of(plot.minX(), y - 3, plot.minZ(), plot.maxX(), y + 20, plot.maxZ()); }

    private void paveRect(Box b, int y, Fill f) {
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                c.set(x, y, z, f.at(x, y, z));
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                for (int k = 1; k <= 5; k++) if (Builders.natural(c.get(x, y + k, z))) c.set(x, y + k, z, "air");
            }
    }

    private void pen(String kind, String display, Box b, int y, String gate, long seed) {
        res.reserve("pen." + kind, grow(b, 1));
        Box inner = FarmKit.pen(c, b.minX(), b.minZ(), b.maxX(), b.maxZ(), y, "oak_fence", "oak", gate, "grass_block", seed ^ kind.hashCode());
        layout.area("pen." + kind, inner);
        layout.zone("barn.pen." + kind, display, 'a', zoneBox(b, y), null);
    }

    private void fairgrounds(Box f, int y, long seed) {
        paveRect(f, y, Palette.of("grass_block:6,coarse_dirt:2,dirt_path:1"));
        // stage for the Harvest Fair host
        int sx0 = -46, sx1 = -34, sz0 = 58, sz1 = 64;
        for (int x = sx0; x <= sx1; x++) for (int z = sz0; z <= sz1; z++) {
            c.set(x, y + 1, z, x == sx0 || x == sx1 || z == sz0 ? "stripped_spruce_log[axis=x]" : "spruce_planks");
        }
        for (int x = sx0 + 1; x <= sx1 - 1; x++) c.set(x, y + 1, sz1 + 1, Blocks.stairs("spruce_stairs", "north"));
        for (int[] p : new int[][]{{sx0, sz0}, {sx1, sz0}}) {
            for (int k = 2; k <= 5; k++) c.set(p[0], y + k, p[1], "spruce_fence");
            c.set(p[0], y + 6, p[1], Blocks.lantern(false));
        }
        for (int x = sx0; x <= sx1; x++) c.set(x, y + 6, sz0, x % 2 == 0 ? "yellow_wool" : "orange_wool");
        for (int x = sx0 + 2; x <= sx1 - 2; x += 3) c.set(x, y + 5, sz0 + 1, Blocks.wallBanner(x % 2 == 0 ? "yellow" : "orange", "south"));
        layout.standAt("npc.contest_host", -40, y + 1, 61, 0f);
        souls.add(new int[]{sx1 - 1, y + 1, sz0 + 1});
        // notice board with the fair's banners
        for (int x = -30; x <= -26; x++) { c.set(x, y + 1, 58, "spruce_fence"); c.set(x, y + 2, 58, "spruce_planks"); c.set(x, y + 3, 58, "spruce_planks"); }
        c.set(-28, y + 2, 59, Blocks.wallBanner("lime", "south"));
        // stalls
        Builders.Stall harv = Builders.marketStall(c, -48, y, 78, "east", "purple", "white", seed);
        layout.standAt("npc.harvesta", harv.vendor()[0], harv.vendor()[1], harv.vendor()[2], -90f);
        Builders.Stall hand = Builders.marketStall(c, -30, y, 78, "east", "green", "white", seed + 1);
        layout.standAt("npc.farmhand", hand.vendor()[0], hand.vendor()[1], hand.vendor()[2], -90f);
        // hay seating and pumpkins for show
        for (int z = 68; z <= 72; z += 2) {
            c.set(-24, y + 1, z, "hay_block[axis=z]");
            c.set(-20, y + 1, z, "hay_block[axis=z]");
        }
        c.set(-22, y + 1, 84, "carved_pumpkin[facing=east]");
        c.set(-21, y + 1, 84, "jack_o_lantern[facing=east]");
        Builders.campfire(c, -22, y, 76);
        for (int[] p : new int[][]{{-54, 56}, {-18, 56}, {-54, 84}, {-18, 84}}) Builders.lampPost(c, p[0], y, p[1], "post", "north");
        layout.zone("barn.fair", "Fairgrounds", 'e', zoneBox(f, y), null, Layout.Zone.SAFE);
    }

    private void soulOnGround(int x, int z) {
        int g = Builders.ground(c, x, z, 140, 30);
        if (g == Integer.MIN_VALUE) return;
        if (!BlockInfo.of(c.get(x, g, z)).cube) c.set(x, g, z, "coarse_dirt");
        for (int k = 1; k <= 2; k++) if (!c.isAir(x, g + k, z) && Builders.natural(c.get(x, g + k, z))) c.set(x, g + k, z, "air");
        souls.add(new int[]{x, g, z});
    }
}
