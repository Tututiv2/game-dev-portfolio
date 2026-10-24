package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * The south: the Farm (fields of every crop, pens, barn, silo, windmill, contest stage), the hub
 * lake with the Fishing Outpost and its ship, and the Wilderness (wizard tower, the Dark Auction
 * house, the hedge witch's hut).
 */
final class HubSouth {

    private HubSouth() {}

    // =======================================================================================
    // Farm
    // =======================================================================================

    static void farm(HubCtx g) {
        int F = g.farmY;
        Box b = FARM;
        // fields: {x1, z1, x2, z2}, crop
        Object[][] fields = {
                {112, 102, 129, 115, "wheat"}, {134, 102, 151, 115, "carrots"}, {156, 102, 173, 115, "potatoes"},
                {112, 120, 129, 133, "pumpkin"}, {134, 120, 151, 133, "melon"}, {156, 120, 173, 133, "sugar_cane"},
                {178, 102, 190, 113, "cocoa"}, {178, 118, 190, 127, "cactus"},
        };
        for (Object[] f : fields) field(g, F, (int) f[0], (int) f[1], (int) f[2], (int) f[3], (String) f[4]);
        // farm tracks between the fields
        Palette track = Palette.of("dirt_path:5,coarse_dirt:2,gravel:1");
        g.road(new int[][]{{108, 98}, {108, 138}, {196, 138}}, 3, track);
        g.road(new int[][]{{108, 117}, {194, 117}}, 3, track);
        g.road(new int[][]{{176, 98}, {176, 138}}, 3, track);
        g.road(new int[][]{{108, 98}, {194, 98}}, 3, track);
        // barn, silo, windmill, pens, stage
        Room barn = Bld.build(g, "barn", 182, F, 144, 17, 13, "west", Bld.BARN.floors(1, 7).roof("dark_oak", "gable"));
        barn.floor(Palette.of("spruce_planks:2,hay_block[axis=y]:1"));
        for (int v = 2; v <= barn.vMax; v += 2) { barn.set(barn.uMin, 1, v, "hay_block[axis=y]"); barn.set(barn.uMin, 2, v, "hay_block[axis=y]"); barn.set(barn.uMax, 1, v, "hay_block[axis=y]"); }
        barn.set(0, 1, barn.vMax, "barrel[facing=up,open=false]");
        barn.set(2, 1, barn.vMax, "composter[level=6]");
        barn.chandeliers(5);
        barn.npc("npc.farmhand", 0, 6);
        g.road(new int[][]{{barn.house.doorStep[0] - 1, barn.house.doorStep[2]}, {178, barn.house.doorStep[2]}, {178, 138}}, 3, track);
        g.reserve("silo", Box.of(201, F - 4, 145, 209, F + 22, 153));
        Shapes.cylinder(g.c, 205, F + 1, 149, 3.6, 15, Palette.of("white_terracotta:3,light_gray_terracotta:1"), 1);
        Shapes.disk(g.c, 205, F, 149, 3.6, Fill.of("cobblestone"));
        Shapes.dome(g.c, 205, F + 16, 149, 3.6, Palette.of("cut_copper:2,exposed_cut_copper:1"), 0);
        g.c.set(202, F + 1, 149, "air"); g.c.set(202, F + 2, 149, "air"); g.c.set(201, F, 149, "cobblestone");
        g.soul(205, F, 149);
        windmill(g, F, 194, 186);
        pens(g, F);
        stage(g, F);
        // the merchant's stall at the farm gate and a scarecrow or two
        Builders.Stall st = Builders.marketStall(g.c, 104, F, 108, "west", "yellow", "white", g.seed + 41);
        g.reserve("farm_stall", st.box());
        g.npc("npc.farm_merchant", st.vendor()[0], F, st.vendor()[2], HubCtx.yaw("west"));
        scarecrow(g, 121, F, 109);
        scarecrow(g, 143, F, 126);
        g.zone("farm", "Farm", 'e', Box.of(b.minX(), F - 4, b.minZ(), b.maxX(), F + 30, b.maxZ()));
        g.area("farm", Box.of(110, F + 1, 100, 192, F + 3, 135));
        g.keepOut(b);
        g.soulOnGround(192, 134);
        g.soulOnGround(110, 135);
    }

    static void field(HubCtx g, int F, int x1, int z1, int x2, int z2, String crop) {
        g.reserve("field." + crop, Box.of(x1 - 1, F - 3, z1 - 1, x2 + 1, F + 4, z2 + 1));
        // a log curb around the field
        for (int x = x1 - 1; x <= x2 + 1; x++) { curb(g, x, F, z1 - 1, 'x'); curb(g, x, F, z2 + 1, 'x'); }
        for (int z = z1; z <= z2; z++) { curb(g, x1 - 1, F, z, 'z'); curb(g, x2 + 1, F, z, 'z'); }
        for (int z = z1; z <= z2; z++)
            for (int x = x1; x <= x2; x++) {
                g.clearAbove(x, F, z, 4);
                int col = x - x1;
                boolean water = col % 5 == 2;
                switch (crop) {
                    case "pumpkin", "melon" -> {
                        if (water) { g.c.set(x, F, z, "water"); continue; }
                        boolean stem = col % 5 == 1 || col % 5 == 3;
                        if (stem) {
                            g.c.set(x, F, z, "farmland[moisture=7]");
                            String fruitDir = col % 5 == 1 ? "west" : "east";
                            g.c.set(x, F + 1, z, "attached_" + crop + "_stem[facing=" + fruitDir + "]");
                        } else {
                            g.c.set(x, F, z, "dirt");
                            if ((z & 1) == 0) g.c.set(x, F + 1, z, crop.equals("melon") ? "melon" : "pumpkin");
                        }
                    }
                    case "sugar_cane" -> {
                        if (water) { g.c.set(x, F, z, "water"); continue; }
                        g.c.set(x, F, z, "sand");
                        if (col % 5 == 1 || col % 5 == 3) for (int y = F + 1; y <= F + 3; y++) g.c.set(x, y, z, "sugar_cane[age=0]");
                    }
                    case "cocoa" -> {
                        g.c.set(x, F, z, "podzol");
                        if (col % 4 == 1 && (z - z1) % 4 == 1) {
                            for (int y = F + 1; y <= F + 4; y++) {
                                g.c.set(x, y, z, "jungle_log[axis=y]");
                                for (int d = 0; d < 4; d++) {
                                    int ax = x + Blocks.DX[d], az = z + Blocks.DZ[d];
                                    if (y <= F + 3 && g.c.isAir(ax, y, az)) g.c.set(ax, y, az, "cocoa[age=2,facing=" + Blocks.DIRS[(d + 2) & 3] + "]");
                                }
                            }
                            g.c.set(x, F + 5, z, "jungle_leaves[distance=1,persistent=true,waterlogged=false]");
                        }
                    }
                    case "cactus" -> {
                        g.c.set(x, F, z, "sand");
                        if (col % 3 == 1 && (z - z1) % 3 == 1) for (int y = F + 1; y <= F + 3; y++) g.c.set(x, y, z, "cactus[age=0]");
                    }
                    default -> {
                        if (water) { g.c.set(x, F, z, "water"); continue; }
                        g.c.set(x, F, z, "farmland[moisture=7]");
                        g.c.set(x, F + 1, z, crop + "[age=7]");
                    }
                }
            }
    }

    static void curb(HubCtx g, int x, int y, int z, char axis) {
        g.c.set(x, y, z, "stripped_oak_log[axis=" + axis + "]");
        g.clearAbove(x, y, z, 3);
        Builders.footing(g.c, x, y, z, Fill.of("dirt"), 8);
    }

    static void scarecrow(HubCtx g, int x, int y, int z) {
        g.c.set(x, y + 1, z, "oak_fence");
        g.c.set(x, y + 2, z, "hay_block[axis=y]");
        g.c.set(x, y + 3, z, "carved_pumpkin[facing=south]");
        g.c.set(x - 1, y + 2, z, "oak_fence");
        g.c.set(x + 1, y + 2, z, "oak_fence");
    }

    static void windmill(HubCtx g, int F, int x, int z) {
        Builders.Tower t = new Builders.Tower(x, F, z);
        t.radius = 4; t.height = 15; t.top = "cone"; t.door = "north";
        t.wall = Palette.of("white_terracotta:3,calcite:2,stone_bricks:1");
        t.roofFill = Palette.of("dark_oak_planks:3,spruce_planks:1");
        g.reserve("windmill", Box.of(x - 7, F - 12, z - 7, x + 7, F + 30, z + 7));
        t.build(g.c);
        // the sails: a hub on the south face and four arms of fence and wool
        int hy = F + 12, hz = z + 5;
        g.c.set(x, hy, hz, "stripped_oak_log[axis=z]");
        for (int k = 1; k <= 6; k++) {
            g.c.set(x + k, hy, hz, "oak_fence"); g.c.set(x - k, hy, hz, "oak_fence");
            g.c.set(x, hy + k, hz, "oak_fence"); g.c.set(x, hy - k, hz, "oak_fence");
            if (k >= 2) {
                g.c.set(x + k, hy + 1, hz, "white_wool"); g.c.set(x - k, hy - 1, hz, "white_wool");
                g.c.set(x - 1, hy + k, hz, "white_wool"); g.c.set(x + 1, hy - k, hz, "white_wool");
            }
        }
        g.road(new int[][]{{t.doorStep[0], t.doorStep[2] - 1}, {x, 170}, {178, 160}, {178, 140}}, 2, Palette.of("dirt_path:5,coarse_dirt:2"));
    }

    static void pens(HubCtx g, int F) {
        String[] kinds = {"cow", "pig", "chicken", "sheep"};
        int x0 = 112;
        for (int i = 0; i < kinds.length; i++) {
            int x1 = x0 + i * 16, x2 = x1 + 13, z1 = 144, z2 = 158;
            g.reserve("pen." + kinds[i], Box.of(x1 - 1, F - 3, z1 - 1, x2 + 1, F + 4, z2 + 1));
            for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) { g.clearAbove(x, F, z, 3); g.c.set(x, F, z, kinds[i].equals("pig") ? (((x + z) % 4 == 0) ? "mud" : "coarse_dirt") : "grass_block"); }
            Builders.fenceLine(g.c, new int[][]{{x1 - 1, z1 - 1}, {x2 + 1, z1 - 1}, {x2 + 1, z2 + 1}, {x1 - 1, z2 + 1}}, "oak_fence", true);
            int gx = (x1 + x2) / 2;
            Builders.gate(g.c, gx, F + 1, z1 - 1, "oak", "north");
            g.c.set(x1 + 1, F + 1, z2 - 1, kinds[i].equals("chicken") ? "hay_block[axis=y]" : "water_cauldron[level=3]");
            g.c.set(x2 - 1, F + 1, z2 - 1, "hay_block[axis=y]");
            g.area("farm.pen." + kinds[i], Box.of(x1 + 1, F + 1, z1 + 1, x2 - 1, F + 2, z2 - 2));
        }
    }

    static void stage(HubCtx g, int F) {
        int x = 160, z = 165;
        g.reserve("contest_stage", Box.of(x - 7, F - 3, z - 5, x + 7, F + 9, z + 5));
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                g.clearAbove(x + dx, F, z + dz, 6);
                if (Math.abs(dz) <= 3 && Math.abs(dx) <= 5) g.c.set(x + dx, F + 1, z + dz, "spruce_planks");
            }
        for (int dx = -5; dx <= 5; dx++) g.c.set(x + dx, F + 1, z - 4, Blocks.stairs("spruce", "south"));
        for (int dx : new int[]{-5, 5}) for (int dz : new int[]{-3, 3}) for (int y = F + 2; y <= F + 5; y++) g.c.set(x + dx, y, z + dz, "stripped_spruce_log[axis=y]");
        for (int dz = -3; dz <= 3; dz++) for (int dx = -5; dx <= 5; dx++) g.c.set(x + dx, F + 6, z + dz, ((dx + 6) & 1) == 0 ? "yellow_wool" : "green_wool");
        g.c.set(x - 2, F + 2, z + 2, "composter[level=7]");
        g.c.set(x + 2, F + 2, z + 2, "hay_block[axis=y]");
        g.c.set(x, F + 2, z + 3, "bell[attachment=floor,facing=north,powered=false]");
        g.c.set(x, F + 5, z, Blocks.lantern(true));
        g.npc("npc.contest_host", x, F + 1, z, 180f);
        g.road(new int[][]{{x, z - 6}, {x, 140}}, 3, Palette.of("dirt_path:5,coarse_dirt:2"));
    }

    // =======================================================================================
    // Lake + Fishing Outpost
    // =======================================================================================

    static void lake(HubCtx g) {
        int level = Terrain.pond(g.c, g.h, LAKE_X, LAKE_Z, LAKE_R, 6, g.seed + 501);
        if (level == Terrain.Heightmap.NONE) throw new IllegalStateException("hub lake did not fit");
        g.keepOut(Box.of(LAKE_X - LAKE_R - 6, 0, LAKE_Z - LAKE_R - 6, LAKE_X + LAKE_R + 6, 0, LAKE_Z + LAKE_R + 6));
        int O = g.outpostY;
        // the fisherman's hut
        Room hut = Bld.build(g, "fishing_hut", 34, O, 136, 13, 9, "south", Bld.CABIN.chimney().wall("spruce_planks:3,stripped_spruce_log:1,cyan_terracotta:1"));
        hut.set(-3, 1, 4, "barrel[facing=up,open=false]"); hut.set(3, 1, 4, "barrel[facing=up,open=false]");
        hut.set(3, 2, 4, "sea_pickle[pickles=3,waterlogged=false]");
        for (int u = hut.uMin; u <= hut.uMax; u++) hut.set(u, 1, hut.vMax, (u & 1) == 0 ? "chest[facing=" + hut.back() + ",type=single,waterlogged=false]" : "barrel[facing=" + hut.back() + ",open=false]");
        hut.npc("npc.fish_merchant", 0, 5);
        // an aquarium tank beside the hut
        g.reserve("aquarium", Box.of(48, O - 3, 146, 56, O + 5, 152));
        for (int z = 147; z <= 151; z++)
            for (int x = 49; x <= 55; x++) {
                boolean edge = x == 49 || x == 55 || z == 147 || z == 151;
                g.c.set(x, O, z, "dark_prismarine");
                for (int y = O + 1; y <= O + 3; y++) g.c.set(x, y, z, edge ? "glass" : "water");
                g.c.set(x, O + 4, z, edge ? Blocks.slab("dark_prismarine", "bottom") : "sea_lantern");
            }
        g.c.set(51, O + 1, 149, "tube_coral_block"); g.c.set(53, O + 1, 149, "brain_coral");
        g.c.set(52, O + 1, 149, "sea_pickle[pickles=4,waterlogged=true]");
        g.npc("npc.aquarist", 52, O, 145, 180f);
        // the dock running west out over the water
        int dz = 172, deck = level + 1;
        int dx1 = 34, dx0 = 8;
        g.reserve("dock", Box.of(dx0 - 1, level - 6, dz - 2, dx1, deck + 6, dz + 2));
        for (int x = dx0; x <= dx1; x++)
            for (int z = dz - 1; z <= dz + 1; z++) {
                g.c.set(x, deck, z, "spruce_planks");
                for (int a = 1; a <= 3; a++) if (Builders.natural(g.c.get(x, deck + a, z))) g.c.set(x, deck + a, z, "air");
                if ((x - dx0) % 5 == 0 && z != dz) for (int y = deck - 1; y >= level - 5; y--) { if (!g.c.isAir(x, y, z) && !g.c.get(x, y, z).startsWith("water")) break; g.c.set(x, y, z, "spruce_log[axis=y]"); }
            }
        for (int x = dx0; x <= dx1 - 4; x += 5) {
            g.c.set(x, deck + 1, dz - 1, "spruce_fence"); g.c.set(x, deck + 2, dz - 1, Blocks.lantern(false));
        }
        g.npc("npc.fishing_guide", dx0 + 1, deck, dz + 1, HubCtx.yaw("west"));
        g.road(new int[][]{{dx1 + 1, dz}, {40, 160}, {40, hut.house.doorStep[2] + 1}}, 3, Palette.of("dirt_path:4,gravel:1"));
        ship(g, level, dx0 - 2, dz + 6);
        hotspots(g, level);
        g.area("pond", Box.of(LAKE_X - LAKE_R - 2, level - 6, LAKE_Z - LAKE_R - 2, LAKE_X + LAKE_R + 2, level + 1, LAKE_Z + LAKE_R + 2));
        g.zone("fishing_outpost", "Fishing Outpost", '3', Box.of(LAKE_X - LAKE_R - 6, level - 8, OUTPOST.minZ(), OUTPOST.maxX(), level + 30, LAKE_Z + LAKE_R + 6));
        g.keepOut(OUTPOST);
    }

    /**
     * Three spots on the open water for the fishing module's hotspots ({@code fishing.hotspot.N},
     * on the water block, walk-check ignored): well away from the dock and the ship.
     */
    static void hotspots(HubCtx g, int level) {
        int[][] want = {{-16, 168}, {-12, 198}, {12, 204}};
        int n = 0;
        for (int[] w : want) {
            search:
            for (int r = 0; r <= 8; r++)
                for (int dz = -r; dz <= r; dz++)
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int x = w[0] + dx, z = w[1] + dz;
                        if (g.res.covers(x, z, 4)) continue;
                        boolean open = true;
                        for (int a = -2; a <= 2 && open; a++)
                            for (int b = -2; b <= 2 && open; b++)
                                if (!g.c.get(x + a, level, z + b).startsWith("water") || !g.c.isAir(x + a, level + 1, z + b)) open = false;
                        if (!open) continue;
                        g.layout.standAt("fishing.hotspot." + (++n), x, level, z, 0f);
                        break search;
                    }
        }
    }

    /** A moored sailing boat south of the dock end; its gangplank meets the dock. */
    static void ship(HubCtx g, int level, int x0, int z0) {
        int deck = level + 1;
        int len = 15, half = 3;
        g.reserve("ship", Box.of(x0 - 1, level - 4, z0 - half, x0 + len, deck + 14, z0 + half + 1));
        for (int i = 0; i < len; i++) {
            int w = i < 2 ? 1 : i > len - 3 ? 2 : half;
            for (int s = -w; s <= w; s++) {
                int x = x0 + i, z = z0 + s;
                boolean rail = Math.abs(s) == w;
                g.c.set(x, level - 1, z, "dark_oak_planks");
                g.c.set(x, level, z, "dark_oak_planks");
                g.c.set(x, deck, z, rail ? "dark_oak_planks" : "spruce_planks");
                if (rail) g.c.set(x, deck + 1, z, "dark_oak_fence");
            }
        }
        int mx = x0 + len / 2;
        for (int y = deck + 1; y <= deck + 11; y++) g.c.set(mx, y, z0, "dark_oak_fence");
        for (int y = deck + 4; y <= deck + 10; y++) for (int s = -2; s <= 2; s++) if (s != 0) g.c.set(mx, y, z0 + s, y == deck + 10 ? "dark_oak_fence" : "white_wool");
        g.c.set(mx, deck + 12, z0, "cyan_banner[rotation=4]");
        // gangplank north to the dock (the dock deck is at the same height)
        for (int z = z0 - half; z >= z0 - half - 3; z--) { g.c.set(x0 + 3, deck, z, "spruce_slab[type=top,waterlogged=false]"); g.c.set(x0 + 3, deck + 1, z, "air"); }
        g.c.set(x0 + len - 2, deck + 1, z0, "barrel[facing=up,open=false]");
        // the captain at the wheel end of the deck: sails you to the fishing islands
        g.c.set(x0 + 4, deck + 1, z0, "spruce_fence");
        g.c.set(x0 + 4, deck + 2, z0, "lantern[hanging=false,waterlogged=false]");
        g.npc("npc.ship_captain", x0 + 5, deck, z0 + 1, 180f);
        g.soul(x0 + len - 3, deck, z0 + 1);
    }

    // =======================================================================================
    // Wilderness
    // =======================================================================================

    /** The Wizard Tower's radius (the warp stands back from it to frame it, see {@link Landing}). */
    static final int WIZARD_TOWER_R = 6;

    static void wilderness(HubCtx g) {
        // the wizard's tower
        int W = g.wizardY;
        Builders.Tower t = new Builders.Tower(WIZARD.centerX(), W, WIZARD.centerZ());
        t.radius = WIZARD_TOWER_R; t.height = 30; t.top = "cone"; t.door = "east"; t.floorHeight = 6;
        t.wall = Palette.of("deepslate_bricks:3,polished_deepslate:1,amethyst_block:1,deepslate_tiles:1");
        t.roofFill = Palette.of("blue_concrete:2,light_blue_terracotta:1,purple_concrete:1");
        t.window = "light_blue_stained_glass_pane";
        g.reserve("wizard_tower", t.reserve());
        t.build(g.c);
        g.npc("npc.wizard", t.inside[0], t.inside[1], t.inside[2], HubCtx.yaw("east"));
        int topFloor = W + ((t.height - 1) / t.floorHeight) * t.floorHeight;
        g.soul(t.inside[0], topFloor, t.inside[2]);
        Builders.Stall st = Builders.marketStall(g.c, t.doorStep[0] + 5, W, t.doorStep[2] + 6, "north", "purple", "blue", g.seed + 61);
        g.reserve("scroll_stall", st.box());
        g.npc("npc.scroll_seller", st.vendor()[0], W, st.vendor()[2], 180f);
        riftArch(g, W);
        g.zone("wizard_tower", "Wizard Tower", 'd', Box.of(WIZARD.minX(), W - 4, WIZARD.minZ(), WIZARD.maxX(), W + 50, WIZARD.maxZ()), Layout.Zone.SAFE);

        // the Dark Auction house: a black stone hall with a guarded iron door
        int D = g.daY;
        Bld.Style dark = Bld.style("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:2,blackstone:1", "stripped_dark_oak_log",
                "blackstone:2,polished_blackstone:1", "dark_oak_planks", "blackstone", "hip", "dark_oak").floors(1, 6).window("iron_bars");
        Room da = Bld.build(g, "dark_auction", DARK_AUCTION.minX() + 4, D, DARK_AUCTION.minZ() + 4, 19, 15, "east", dark);
        da.floor(Palette.of("dark_oak_planks:3,polished_blackstone:1"));
        for (int v = 3; v <= 9; v += 2) for (int u = -5; u <= 5; u++) if (Math.abs(u) > 1) da.set(u, 1, v, Blocks.stairs("dark_oak", da.back()));
        da.rect(-3, 12, 3, da.vMax, 1, "polished_blackstone_bricks");
        da.set(0, 2, 12, "lectern[facing=" + da.back() + ",has_book=true,powered=false]");
        da.chandeliers(5);
        da.runner(1, 11, "red_carpet");
        g.area("dark_auction", da.box());
        int[] ds = da.house.doorStep;
        g.npc("npc.dark_auction", ds[0] + 1, D, ds[2] - 2, HubCtx.yaw("east"));
        g.npc("npc.shady_dealer", ds[0] + 4, D, ds[2] + 4, HubCtx.yaw("west"));
        g.c.set(ds[0] + 1, D + 1, ds[2] + 2, Blocks.soulLantern(false));
        // SAFE over the whole plot, not just the hall + 2: the forecourt is where /warp da lands, and the
        // Wilderness round it is a spawn area (wolves; Wraiths and witches in the Spooky Festival). The wave-4
        // playtest landed one block from a Lv50 Wraith (260 a hit) outside the old hall-sized zone.
        g.daDoorStep = ds;
        g.daSafe = Box.of(DARK_AUCTION.minX(), D - 4, DARK_AUCTION.minZ(), DARK_AUCTION.maxX(), Math.max(D + 16, da.box().maxY() + 2), DARK_AUCTION.maxZ());
        g.zone("dark_auction", "Dark Auction", '5', g.daSafe, Layout.Zone.SAFE);

        // the hedge witch's crooked hut
        int H = g.witchY;
        Room hut = Bld.build(g, "witch_hut", WITCH.minX() + 4, H, WITCH.minZ() + 4, 11, 9, "north", Bld.MUD.roof("dark_oak", "gable").chimney());
        hut.set(-2, 1, 4, "water_cauldron[level=2]");
        hut.set(2, 1, 4, "brewing_stand[has_bottle_0=true,has_bottle_1=true,has_bottle_2=true]");
        hut.set(0, 1, hut.vMax, "potted_dead_bush");
        hut.set(hut.uMin, 2, 2, "cobweb");
        hut.npc("npc.hedgewitch", 0, 5);
        g.soulOnGround(WITCH.maxX() + 1, WITCH.maxZ() + 1);

        Box wild = Box.of(-245, 0, 96, -60, 0, 245);
        g.area("wilderness", Box.of(wild.minX(), g.V - 6, wild.minZ(), wild.maxX(), g.V + 30, wild.maxZ()));
        g.zone("wilderness", "Wilderness", 'c', Box.of(wild.minX(), g.V - 20, wild.minZ(), wild.maxX(), g.V + 60, wild.maxZ()));
    }

    /**
     * Where the Rift gate stands: the arch's plane is x = ARCH_X, its legs at z ARCH_Z +- 3. It was a
     * decoration at the south-west corner, right on the road to the Dark Auction; a live gate there
     * would spend a Planar Infusion on anyone walking to the auction, so it moved to the north-west
     * corner, off every road.
     */
    public static final int ARCH_X = WIZARD.minX() + 2, ARCH_Z = WIZARD.minZ() + 5;

    /**
     * The glowing arch beside the Wizard Tower, which is the reference game's way into the Rift
     * (research 03 section 1.1; the rift module's Warden of the Arch stands by it). Wave 3 made it
     * a real gate: the inside of the arch is a portal sheet and area {@code gate.rift} warps on
     * contact through the hub's {@code TravelSpots}, so the Rift's own {@code rift:pass} requirement
     * is checked before anything happens and the visit is charged on arrival by the rift module.
     * Only cells a vanilla portal would keep are filled: each must have a block or another portal
     * cell above, below and on both sides along the plane, so a neighbour update can never pop it.
     */
    static void riftArch(HubCtx g, int W) {
        int ax = ARCH_X, az = ARCH_Z, half = 4, rise = 7, n = 2 * half - 1, top = rise + 1;
        g.reserve("rift_arch", Box.of(ax - 1, W - 2, az - half - 2, ax + 3, W + rise + 3, az + half + 2));
        Shapes.arch(g.c, ax, az - half, ax, az + half, W, rise, 1, 1, Palette.of("crying_obsidian:2,obsidian:3,amethyst_block:1"));
        // the threshold: an amethyst sill under the sheet and a step of polished deepslate either side
        for (int z = az - half + 1; z <= az + half - 1; z++)
            for (int dx = -1; dx <= 1; dx++) g.c.set(ax + dx, W, z, dx == 0 ? "amethyst_block" : "polished_deepslate");
        int z0 = az - half + 1;
        boolean[][] sheet = new boolean[n][top + 1];          // [z - z0][h], h = 1..top
        for (int i = 0; i < n; i++) {
            int roof = 0;
            for (int h = 1; h <= top && roof == 0; h++) if (!g.c.isAir(ax, W + h, z0 + i)) roof = h;
            for (int h = 1; h < roof; h++) sheet[i][h] = true;
        }
        // prune cells a vanilla portal would drop (open along the plane or above/below)
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < n; i++)
                for (int h = 1; h <= top; h++) {
                    if (!sheet[i][h]) continue;
                    if (!held(g, sheet, ax, W, z0, i - 1, h) || !held(g, sheet, ax, W, z0, i + 1, h)
                            || !held(g, sheet, ax, W, z0, i, h - 1) || !held(g, sheet, ax, W, z0, i, h + 1)) {
                        sheet[i][h] = false;
                        changed = true;
                    }
                }
        }
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++)
            for (int h = 1; h <= top; h++) {
                if (!sheet[i][h]) continue;
                g.c.set(ax, W + h, z0 + i, "nether_portal[axis=z]");
                if (h == 1 && sheet[i][2]) { minZ = Math.min(minZ, z0 + i); maxZ = Math.max(maxZ, z0 + i); }
            }
        if (minZ > maxZ) throw new IllegalStateException("the rift arch has no walk-through portal sheet");
        // feet and head height of every column a player fits through
        g.area("gate.rift", Box.of(ax, W + 1, minZ, ax, W + 2, maxZ));
        // the step in front (east, the tower side), the floating sign above it, the Warden's spot
        g.npc("gate.rift", ax + 2, W, az, 90f);
        g.layout.anchor("label.gate.rift", ax + 1.5, W + rise + 1.3, az + 0.5, -90f);
        g.npc("npc.rift_warden", ax + 3, W, az - half - 2, -90f);
        for (int s : new int[]{-half - 1, half + 1}) {
            g.c.set(ax + 1, W + 1, az + s, "amethyst_block");
            g.c.set(ax + 1, W + 2, az + s, "amethyst_cluster[facing=up,waterlogged=false]");
        }
    }

    /** Whether sheet cell (i, h) of the rift arch is held: a solid block there, or another sheet cell. */
    private static boolean held(HubCtx g, boolean[][] sheet, int ax, int W, int z0, int i, int h) {
        if (i >= 0 && i < sheet.length && h >= 1 && h < sheet[i].length && sheet[i][h]) return true;
        return !g.c.isAir(ax, W + h, z0 + i);
    }
}
