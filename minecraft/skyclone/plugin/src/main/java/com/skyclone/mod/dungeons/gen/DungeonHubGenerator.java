package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

/**
 * The Dungeon Hub (research 04 sec 3.1): a gothic town square on a dark floating island. North: the Catacombs
 * mausoleum, whose great arch is the entrance (walking into it opens the floor selector) with the Gatekeeper beside
 * it. West: the Party Finder board. East: the Class Altar (five pedestals). South: the Provisioner's shop, a vault
 * for unclaimed reward chests (reserved for the loot half) and the portal back to the Hub. Graves, dead trees and
 * soul lanterns everywhere. Pure.
 */
public final class DungeonHubGenerator implements IslandGenerator {

    @Override public String islandId() { return "dungeon_hub"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-110, 10, -110, 110, 150, 110); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 92; shape.radiusZ = 96; shape.baseY = 70;
        shape.hills = 3; shape.cliff = 0.6; shape.undersideDepth = 34; shape.coastNoise = 0.16;
        Terrain.Heightmap h = Terrain.island(shape);

        Box plaza = Box.of(-26, 0, -22, 26, 0, 22);
        Box gatePlot = Box.of(-24, 0, -80, 24, 0, -40);
        Box boardPlot = Box.of(-66, 0, -14, -36, 0, 14);
        Box altarPlot = Box.of(38, 0, -18, 66, 0, 18);
        Box shopPlot = Box.of(24, 0, 28, 52, 0, 52);
        Box vaultPlot = Box.of(-52, 0, 28, -24, 0, 52);
        Box spawnPlot = Box.of(-12, 0, 44, 12, 0, 80);
        int y = h.average(plaza);
        for (Box b : new Box[]{plaza, gatePlot, boardPlot, altarPlot, shopPlot, vaultPlot, spawnPlot}) h.flatten(b, y, 6);

        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.top = Palette.of("podzol:3,coarse_dirt:3,grass_block:2,rooted_dirt:1");
        rules.soil = Palette.of("dirt:3,coarse_dirt:1");
        rules.stone = Palette.of("deepslate:6,tuff:3,cobbled_deepslate:1");
        rules.cliff = Palette.of("deepslate:5,tuff:3,cobbled_deepslate:2");
        rules.underside = Palette.of("deepslate:5,tuff:3,blackstone:2,cobbled_deepslate:1");
        Terrain.fill(c, h, rules);

        Reservations res = new Reservations();
        Palette paving = Palette.of("deepslate_tiles:5,polished_deepslate:3,cracked_deepslate_tiles:1,deepslate_bricks:2");
        Palette blackstone = Palette.of("polished_blackstone_bricks:6,cracked_polished_blackstone_bricks:2,blackstone:1");

        // ---- the square ----
        res.reserve("plaza", plaza);
        for (int x = plaza.minX(); x <= plaza.maxX(); x++)
            for (int z = plaza.minZ(); z <= plaza.maxZ(); z++) {
                boolean ring = Math.abs(x) == 26 || Math.abs(z) == 22;
                c.set(x, y, z, ring ? "polished_blackstone" : paving.at(x, y, z));
                for (int k = 1; k <= 6; k++) c.set(x, y + k, z, "air");
            }
        Builders.fountain(c, 0, y, 0, 4, Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1"), seed);
        c.set(0, y + 4, 0, "soul_lantern[hanging=false,waterlogged=false]");
        for (int[] p : new int[][]{{-24, -20}, {-24, 20}, {24, -20}, {24, 20}, {-24, 0}, {24, 0}, {0, 20}})
            Builders.lampPost(c, p[0], y, p[1], "soul", "north");
        // benches around the fountain
        Builders.bench(c, -8, y, -6, "south", 3, "dark_oak");
        Builders.bench(c, 6, y, 6, "north", 3, "dark_oak");

        // ---- the Catacombs mausoleum (north) ----
        res.reserve("mausoleum", Box.of(-22, y - 10, -78, 22, y + 30, -42));
        int fz = -48;          // facade plane
        for (int x = -20; x <= 20; x++)
            for (int z = -76; z <= fz; z++)
                for (int k = 0; k <= 18; k++) {
                    boolean shell = Math.abs(x) == 20 || z == -76 || z == fz || k == 18;
                    if (k == 0) c.set(x, y, z, "polished_blackstone");
                    else if (shell) c.set(x, y + k, z, k == 1 || k == 17 ? "chiseled_polished_blackstone" : blackstone.at(x, y + k, z));
                    else c.set(x, y + k, z, "air");
                }
        // stepped roof
        for (int step = 0; step < 6; step++) {
            int w = 20 - step * 3, zz0 = -76 + step * 2, zz1 = fz - step * 2;
            if (w < 2) break;
            for (int x = -w; x <= w; x++) for (int z = zz0; z <= zz1; z++) c.set(x, y + 19 + step, z, step % 2 == 0 ? "deepslate_tiles" : "polished_blackstone_bricks");
        }
        for (int[] s : new int[][]{{-20, fz}, {20, fz}, {-20, -76}, {20, -76}}) {
            Shapes.cylinder(c, s[0], y + 1, s[1], 1.5, 24, Fill.of("polished_blackstone_bricks"), 0);
            for (int k = 25; k <= 28; k++) c.set(s[0], y + k, s[1], k == 28 ? "soul_lantern[hanging=false,waterlogged=false]" : "polished_blackstone_wall");
        }
        // the great arch (7 wide, 10 high) into a dark vestibule
        for (int x = -3; x <= 3; x++)
            for (int k = 1; k <= 10; k++)
                for (int z = fz - 8; z <= fz; z++) {
                    boolean archTop = k == 10 || (k == 9 && Math.abs(x) == 3);
                    if (archTop) { c.set(x, y + k, z, "chiseled_polished_blackstone"); continue; }
                    c.set(x, y + k, z, z == fz - 8 ? (k % 3 == 0 ? "crying_obsidian" : "obsidian") : "air");
                }
        for (int z = fz - 7; z <= fz; z++) for (int x = -3; x <= 3; x++) c.set(x, y, z, (x + z) % 2 == 0 ? "black_concrete" : "polished_blackstone");
        for (int k = 1; k <= 9; k++) { c.set(-4, y + k, fz + 1, "polished_blackstone_wall"); c.set(4, y + k, fz + 1, "polished_blackstone_wall"); }
        c.set(-4, y + 10, fz + 1, "soul_lantern[hanging=false,waterlogged=false]");
        c.set(4, y + 10, fz + 1, "soul_lantern[hanging=false,waterlogged=false]");
        for (int k = 3; k <= 8; k += 5) {
            c.set(-2, y + k, fz - 7, "soul_wall_torch[facing=south]");
            c.set(2, y + k, fz - 7, "soul_wall_torch[facing=south]");
        }
        // big skull ornament above the arch
        c.set(0, y + 12, fz + 1, "wither_skeleton_wall_skull[facing=south]");
        c.set(-1, y + 12, fz + 1, "skeleton_wall_skull[facing=south]");
        c.set(1, y + 12, fz + 1, "skeleton_wall_skull[facing=south]");
        Box gateZone = Box.of(-3, y + 1, fz - 7, 3, y + 9, fz - 3);
        layout.area("catacombs_gate", gateZone);
        layout.zone("catacombs_gate", "Catacombs Gate", 'c', gateZone, null, Layout.Zone.SAFE);
        // a forecourt between the square and the arch
        for (int x = -10; x <= 10; x++)
            for (int z = fz + 1; z <= plaza.minZ() - 1; z++) {
                c.set(x, y, z, Math.abs(x) <= 2 ? "polished_blackstone" : paving.at(x, y, z));
                for (int k = 1; k <= 5; k++) c.set(x, y + k, z, "air");
            }
        for (int z = fz + 3; z <= plaza.minZ() - 2; z += 6) {
            Builders.lampPost(c, -9, y, z, "soul", "east");
            Builders.lampPost(c, 9, y, z, "soul", "west");
        }
        layout.standAt("npc.gatekeeper", 6, y, fz + 4, 180f);

        // ---- the Party Finder board (west) ----
        res.reserve("party_board", boardPlot);
        int bx = -52;
        for (int z = -6; z <= 6; z++)
            for (int k = 1; k <= 7; k++) {
                boolean frame = Math.abs(z) == 6 || k == 1 || k == 7;
                c.set(bx, y + k, z, frame ? "stripped_dark_oak_log[axis=y]" : (k % 2 == 0 && Math.abs(z) % 3 == 1 ? "white_wool" : "dark_oak_planks"));
            }
        for (int z = -7; z <= 7; z++) c.set(bx, y + 8, z, Blocks.slab("dark_oak", "bottom"));
        for (int z = -4; z <= 4; z += 4) c.set(bx + 1, y + 6, z, "red_wall_banner[facing=east]");
        for (int x = bx + 1; x <= -27; x++) for (int z = -3; z <= 3; z++) c.set(x, y, z, paving.at(x, y, z));
        Builders.lampPost(c, bx + 2, y, -8, "soul", "east");
        Builders.lampPost(c, bx + 2, y, 8, "soul", "east");
        layout.standAt("npc.party_board", bx + 3, y, 0, 90f);

        // ---- the Class Altar (east): five pedestals on an arc ----
        res.reserve("altar", altarPlot);
        int ax = 52;
        for (int x = 40; x <= 64; x++)
            for (int z = -16; z <= 16; z++) {
                double d = Math.hypot(x - ax, z);
                if (d <= 14) c.set(x, y, z, d > 13 ? "polished_blackstone" : paving.at(x, y, z));
            }
        String[] classBlocks = {"pink_concrete", "light_blue_concrete", "red_concrete", "orange_concrete", "light_gray_concrete"};
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(-60 + i * 30);
            int px = ax + (int) Math.round(Math.cos(a) * 9), pz = (int) Math.round(Math.sin(a) * 9);
            c.set(px, y + 1, pz, "polished_blackstone_bricks");
            c.set(px, y + 2, pz, "chiseled_polished_blackstone");
            c.set(px, y + 3, pz, classBlocks[i]);
            c.set(px, y + 4, pz, "candle[candles=4,lit=true,waterlogged=false]");
        }
        c.set(ax + 3, y + 1, 0, "lodestone");
        c.set(ax + 3, y + 2, 0, "soul_lantern[hanging=false,waterlogged=false]");
        for (int x = 27; x <= 40; x++) for (int z = -3; z <= 3; z++) c.set(x, y, z, paving.at(x, y, z));
        layout.standAt("npc.class_altar", ax + 1, y, 0, 90f);
        layout.standAt("npc.essence_shop", ax - 4, y, 12, 180f);

        // ---- the Provisioner (south-east) ----
        Builders.House shop = new Builders.House(30, y, 32, 13, 11);
        shop.door = "west";
        shop.wall = Palette.of("deepslate_bricks:5,polished_deepslate:2");
        shop.frame = "dark_oak_log";
        shop.roof = "deepslate_tile";
        shop.roofStyle = "gable";
        shop.foundation = Palette.of("polished_blackstone:1");
        shop.seed = seed;
        if (res.reserve("shop", shop.reserve())) shop.build(c);
        Builders.Stall stall = null;
        if (res.reserve("shop_stall", Box.of(21, y - 12, 34, 27, y + 4, 42)))
            stall = Builders.marketStall(c, 24, y, 38, "west", "purple", "black", seed);
        if (stall != null) layout.standAt("npc.provisioner", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 90f);
        else layout.standAt("npc.provisioner", 22, y, 38, 90f);

        // ---- the Vault (south-west, reserved for unclaimed reward chests) ----
        Builders.House vault = new Builders.House(-48, y, 32, 15, 13);
        vault.door = "east";
        vault.wall = Palette.of("polished_blackstone_bricks:5,gilded_blackstone:1");
        vault.frame = "dark_oak_log";
        vault.roof = "deepslate_tile";
        vault.roofStyle = "hip";
        vault.foundation = Palette.of("polished_blackstone:1");
        vault.seed = seed;
        if (res.reserve("vault", vault.reserve())) vault.build(c);
        if (vault.inside != null) layout.standAt("npc.croesus", vault.inside[0], vault.inside[1], vault.inside[2], 90f);
        else layout.standAt("npc.croesus", -30, y, 40, 90f);

        // ---- spawn and the portal back to the Hub (south) ----
        res.reserve("spawn", spawnPlot);
        for (int x = -6; x <= 6; x++) for (int z = 44; z <= 72; z++) c.set(x, y, z, Math.abs(x) <= 1 ? "polished_blackstone" : paving.at(x, y, z));
        int pz = 70;
        Palette frame = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1");
        for (int k = 1; k <= 5; k++) { c.set(-3, y + k, pz, frame.at(-3, y + k, pz)); c.set(3, y + k, pz, frame.at(3, y + k, pz)); }
        for (int x = -3; x <= 3; x++) c.set(x, y + 6, pz, frame.at(x, y + 6, pz));
        for (int x = -2; x <= 2; x++) for (int k = 1; k <= 5; k++) c.set(x, y + k, pz, "purple_stained_glass_pane");
        Builders.lampPost(c, -5, y, pz - 1, "soul", "north");
        Builders.lampPost(c, 5, y, pz - 1, "soul", "north");
        layout.area("portal.hub", Box.of(-2, y + 1, pz - 1, 2, y + 4, pz));
        for (int z = 48; z <= 64; z += 8) { Builders.lampPost(c, -6, y, z, "soul", "east"); Builders.lampPost(c, 6, y, z, "soul", "west"); }
        layout.standAt("spawn", 0, y, 56, 180f);

        // ---- paths ----
        Palette path = Palette.of("polished_blackstone:3,blackstone:2,gravel:1,coarse_dirt:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{0, 44}, {0, 23}}, 3, path, "polished_blackstone_stairs", null);
        Builders.path(c, new int[][]{{22, 23}, {28, 30}, {shop.doorStep == null ? 24 : shop.doorStep[0] - 1, shop.doorStep == null ? 36 : shop.doorStep[2]}}, 3, path, "polished_blackstone_stairs", paths);
        Builders.path(c, new int[][]{{-22, 23}, {-28, 30}, {vault.doorStep == null ? -30 : vault.doorStep[0] + 1, vault.doorStep == null ? 38 : vault.doorStep[2]}}, 3, path, "polished_blackstone_stairs", paths);

        // ---- graves, dead trees ----
        Palette graveTop = Palette.of("skeleton_skull[rotation=8]:1,candle[candles=1,lit=true,waterlogged=false]:2,air:4");
        long gs = Hash.salt(seed, "graves");
        for (int x = -88; x <= 88; x += 4)
            for (int z = -88; z <= 88; z += 3) {
                if (!Hash.chance(gs, x, z, 0.28)) continue;
                int t = h.top(x, z);
                if (t == Terrain.Heightmap.NONE || h.slope(x, z) > 1) continue;
                if (res.covers(x, z, 4) || paths.near(x, z, 3)) continue;
                if (Math.abs(x) < 30 && z > 20 && z < 84) continue;                   // the spawn avenue
                String ground = c.get(x, t, z);
                if (ground == null || ground.contains("air")) continue;
                c.set(x, t + 1, z, "stone_brick_wall");
                c.set(x, t + 2, z, Hash.chance(gs, x, 7, z, 0.5) ? "stone_brick_slab[type=bottom,waterlogged=false]" : "mossy_stone_brick_wall");
                String top = graveTop.at(x, t + 3, z);
                if (!top.equals("air") && c.isAir(x, t + 3, z) && top.startsWith("candle") && c.get(x, t + 2, z).contains("slab")) c.set(x, t + 3, z, top);
            }
        Trees.forest(c, h, h.box(), seed, 0.02, 9, "dead:5,dark_oak:1,spruce:1", 3,
                (x, z) -> res.covers(x, z, 5) || paths.near(x, z, 4) || (Math.abs(x) < 30 && z > 20 && z < 84));
        Terrain.grassAndFlowers(c, h, seed, 0.08, 0.004);

        // ---- zones ----
        Box island = h.box();
        layout.zone("dungeon_hub", "Dungeon Hub", 'c', new Box(island.minX(), 0, island.minZ(), island.maxX(), 255, island.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("dungeon_square", "Gravewatch Square", 'c', new Box(plaza.minX(), y - 3, plaza.minZ(), plaza.maxX(), y + 20, plaza.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("mausoleum", "Catacombs Mausoleum", '5', new Box(-20, y - 3, -76, 20, y + 26, fz + 6), null, Layout.Zone.SAFE);
        layout.zone("class_altar", "Class Altar", 'd', new Box(altarPlot.minX(), y - 3, altarPlot.minZ(), altarPlot.maxX(), y + 15, altarPlot.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("party_board", "Party Board", 'e', new Box(boardPlot.minX(), y - 3, boardPlot.minZ(), boardPlot.maxX(), y + 15, boardPlot.maxZ()), null, Layout.Zone.SAFE);
        layout.data("reservations", res.summary());
    }
}
