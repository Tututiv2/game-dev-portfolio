package com.skyclone.mod.hunting.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

/**
 * The Critter Safari: an entrance plaza (the desks and the Huntmaster) ringed by four biomes, one per
 * compass point - the Forest (north, birds and a birdfeeder), the Cavern (east, under a stone dome), the
 * Icy biome (south, snow, ice spikes and campfires) and the Haunted biome (west, dark woods and a mansion).
 * Research 03 section 25: Cavern 9 critters, Forest 9, Haunted 10, Icy 9. Pure (no Bukkit): preview with
 * {@code bash tools/preview.sh com.skyclone.mod.hunting.gen.SafariGenerator}.
 */
public final class SafariGenerator implements IslandGenerator {

    public static final String ID = "critter_safari";

    @Override public String islandId() { return ID; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-124, 10, -124, 124, 150, 124); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---- 1. terrain ----
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 108; shape.radiusZ = 108; shape.baseY = 70;
        shape.hills = 4; shape.hillScale = 40; shape.cliff = 0.5; shape.undersideDepth = 30; shape.coastNoise = 0.16;
        Terrain.Heightmap h = Terrain.island(shape);

        Box plaza = Box.of(-15, 0, -15, 15, 0, 15);
        int y = h.average(plaza);
        h.flatten(plaza, y, 10);
        Box mansionPlot = Box.of(-74, 0, -9, -56, 0, 9);
        h.flatten(mansionPlot, y, 6);
        Box cavePlot = Box.of(40, 0, -24, 86, 0, 24);
        h.flatten(cavePlot, y, 8);

        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        Terrain.fill(c, h, rules);

        // ---- 2. biome surfaces (quadrants by the stronger axis) ----
        Palette snow = Palette.of("snow_block:10,packed_ice:1");
        Palette haunted = Palette.of("podzol:5,coarse_dirt:3,soul_soil:1,mycelium:1");
        Palette cave = Palette.of("stone:6,gravel:2,moss_block:2,andesite:1");
        Box isl = h.box();
        h.forEach((x, z, top, bottom) -> {
            if (plaza.grow(3).containsXZ(x, z)) return;
            String b = biome(x, z);
            String cur = c.get(x, top, z);
            if (!"grass_block".equals(cur)) return;
            switch (b) {
                case "icy" -> c.set(x, top, z, snow.at(x, top, z));
                case "haunted" -> c.set(x, top, z, haunted.at(x, top, z));
                case "cavern" -> c.set(x, top, z, cave.at(x, top, z));
                default -> { }
            }
        });

        Reservations res = new Reservations();
        res.reserve("plaza", new Box(plaza.minX(), y - 2, plaza.minZ(), plaza.maxX(), y + 10, plaza.maxZ()));

        // ---- 3. the entrance plaza ----
        Palette paving = Palette.of("mud_bricks:5,packed_mud:3,bricks:1,mossy_stone_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) {
                c.set(x, y, z, paving.at(x, y, z));
                for (int k = 1; k <= 6; k++) if (!c.isAir(x, y + k, z)) c.set(x, y + k, z, "air");
            }
        Builders.fountain(c, 0, y, 0, 3, Palette.of("mud_bricks:4,mossy_stone_bricks:1"), seed);
        for (int[] q : new int[][]{{-14, -14}, {14, -14}, {-14, 14}, {14, 14}, {-14, 0}, {14, 0}, {0, 14}})
            Builders.lampPost(c, q[0], y, q[1], "post", "north");
        // the four desks: little canopied counters
        int[][] desks = {{-8, -8}, {8, -8}, {8, 8}, {-8, 8}};
        for (int[] d : desks) desk(c, d[0], y, d[1]);
        Builders.bench(c, -4, y, 5, "north", 3, "spruce");
        Builders.crates(c, 11, y, -11, seed);

        // portal back to the hub, north-west corner, facing south
        Palette frame = Palette.of("mud_bricks:3,polished_blackstone_bricks:1");
        int pz = -13;
        for (int yy = y + 1; yy <= y + 4; yy++) { c.set(-12, yy, pz, frame.at(-12, yy, pz)); c.set(-8, yy, pz, frame.at(-8, yy, pz)); }
        for (int x = -12; x <= -8; x++) c.set(x, y + 5, pz, frame.at(x, y + 5, pz));
        for (int x = -11; x <= -9; x++) for (int yy = y + 1; yy <= y + 4; yy++) c.set(x, yy, pz, "nether_portal[axis=x]");
        layout.area("portal.hub", Box.of(-11, y + 1, pz, -9, y + 4, pz));

        // ---- 4. Cavern biome (east): a stone dome over the plot, open to the west ----
        int cx = 63;
        Shapes.dome(c, cx, y, 0, 24, Palette.of("stone:6,andesite:3,tuff:2,deepslate:1"), 3);
        // the doorway and a few skylights
        for (int z = -3; z <= 3; z++) for (int x = cx - 26; x <= cx - 18; x++) for (int yy = y + 1; yy <= y + 5; yy++) c.set(x, yy, z, "air");
        for (int[] s : new int[][]{{cx - 6, -6}, {cx + 7, 5}, {cx, 9}}) for (int yy = y + 10; yy <= y + 26; yy++)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!c.isAir(s[0] + dx, yy, s[1] + dz) && yy > y + 14) c.set(s[0] + dx, yy, s[1] + dz, "air");
        for (int i = 0; i < 26; i++) {
            int x = cx - 16 + (int) Math.floorMod(Hash.salt(seed, "crys" + i), 33L);
            int z = -16 + (int) Math.floorMod(Hash.salt(seed, "crz" + i), 33L);
            if ((x - cx) * (x - cx) + z * z > 17 * 17) continue;
            int g = Builders.ground(c, x, z, y + 8, y - 8);
            if (g == Integer.MIN_VALUE) continue;
            c.set(x, g + 1, z, i % 3 == 0 ? "amethyst_cluster" : i % 3 == 1 ? "glow_lichen" : "pointed_dripstone");
        }
        for (int[] l : new int[][]{{cx - 10, -8}, {cx + 8, -8}, {cx - 10, 8}, {cx + 8, 8}, {cx, 0}}) {
            int g = Builders.ground(c, l[0], l[1], y + 8, y - 8);
            if (g != Integer.MIN_VALUE) Builders.lampPost(c, l[0], g, l[1], "soul", "north");
        }
        res.reserve("cavern", Box.of(cx - 24, y - 2, -24, cx + 24, y + 26, 24));

        // ---- 5. Haunted biome (west): a dark mansion ----
        Builders.House mansion = new Builders.House(-72, y, -7, 15, 13);
        mansion.floors = 2;
        mansion.door = "east";
        mansion.wall = Palette.of("dark_oak_planks:5,stripped_dark_oak_log[axis=y]:1,cracked_stone_bricks:1");
        mansion.frame = "dark_oak_log";
        mansion.roof = "dark_oak";
        mansion.roofStyle = "hip";
        mansion.window = "black_stained_glass_pane";
        mansion.doorWood = "dark_oak";
        mansion.seed = seed;
        if (res.reserve("mansion", mansion.reserve())) mansion.build(c);

        // ---- 6. Icy biome (south): ice spikes and campfires ----
        for (int i = 0; i < 14; i++) {
            int x = -40 + (int) Math.floorMod(Hash.salt(seed, "spx" + i), 80L);
            int z = 30 + (int) Math.floorMod(Hash.salt(seed, "spz" + i), 60L);
            if (Math.abs(x) < 6 || res.covers(x, z, 3)) continue;
            int g = Builders.ground(c, x, z, y + 12, y - 12);
            if (g == Integer.MIN_VALUE || !"icy".equals(biome(x, z))) continue;
            Shapes.cone(c, x, g + 1, z, 1.6 + (i % 3) * 0.6, 5 + (i % 4) * 2, Fill.of("packed_ice"));
        }
        for (int[] f : new int[][]{{-14, 40}, {16, 52}, {-6, 70}, {20, 80}}) {
            int g = Builders.ground(c, f[0], f[1], y + 12, y - 12);
            if (g != Integer.MIN_VALUE) Builders.campfire(c, f[0], g, f[1]);
        }

        // ---- 7. Forest biome (north): a birdfeeder ----
        int fz = -48;
        int fg = Builders.ground(c, 6, fz, y + 12, y - 12);
        if (fg != Integer.MIN_VALUE) {
            c.set(6, fg + 1, fz, "oak_fence");
            c.set(6, fg + 2, fz, "oak_fence");
            c.set(6, fg + 3, fz, "oak_slab[type=bottom]");
            c.set(6, fg + 4, fz, "composter[level=4]");
        }

        // ---- 8. roads from the plaza to every biome ----
        Palette road = Palette.of("dirt_path:6,coarse_dirt:2,gravel:1");
        Builders.PathResult roads = Builders.path(c, new int[][]{{0, -16}, {0, -40}, {-6, -70}}, 3, road, "cobblestone_stairs", null);
        Builders.path(c, new int[][]{{16, 0}, {cx - 26, 0}}, 3, road, "cobblestone_stairs", roads);
        Builders.path(c, new int[][]{{0, 16}, {2, 44}, {-4, 76}}, 3, road, "cobblestone_stairs", roads);
        Builders.path(c, new int[][]{{-16, 0}, {mansion.doorStep[0] + 2, mansion.doorStep[2]}, {mansion.doorStep[0], mansion.doorStep[2]}},
                3, road, "cobblestone_stairs", roads);

        // ---- 9. trees and plants per biome, kept off the plaza, the roads and the buildings ----
        Terrain.grassAndFlowers(c, h, seed, 0.18, 0.04);
        Box north = Box.of(isl.minX(), 0, isl.minZ(), isl.maxX(), 0, -20);
        Box south = Box.of(isl.minX(), 0, 20, isl.maxX(), 0, isl.maxZ());
        Box west = Box.of(isl.minX(), 0, -40, -20, 0, 40);
        java.util.function.BiPredicate<Integer, Integer> keep = (x, z) -> res.covers(x, z, 4) || roads.near(x, z, 3) || plaza.grow(5).containsXZ(x, z);
        Trees.forest(c, h, north, seed, 0.03, 5, "oak:4,birch:3,big_oak:1,cherry:1", 3, (x, z) -> keep.test(x, z) || !"forest".equals(biome(x, z)));
        Trees.forest(c, h, south, seed + 1, 0.025, 5, "spruce:4,pine:3,mega_spruce:1", 3, (x, z) -> keep.test(x, z) || !"icy".equals(biome(x, z)));
        Trees.forest(c, h, west, seed + 2, 0.03, 5, "dark_oak:5,dead:2,brown_mushroom:1", 3, (x, z) -> keep.test(x, z) || !"haunted".equals(biome(x, z)));

        // ---- 10. what the game needs ----
        layout.standAt("spawn", 0, y, 11, 180f);
        layout.standAt("npc.safari_receptionist", -8, y, -6, 180f);
        layout.standAt("npc.safari_manager", 8, y, -6, 180f);
        layout.standAt("npc.safari_perks", 8, y, 6, 0f);
        layout.standAt("npc.safari_warden", -8, y, 6, 0f);
        layout.standAt("npc.huntmaster", 4, y, 12, 180f);
        int top = y + 10, bot = y - 14;
        layout.area("biome.forest", Box.of(-60, bot, -100, 60, top, -24));
        layout.area("biome.cavern", Box.of(cx - 18, y - 8, -18, cx + 18, y + 6, 18));
        layout.area("biome.icy", Box.of(-60, bot, 24, 60, top, 100));
        layout.area("biome.haunted", Box.of(-104, bot, -40, -24, top, 40));
        layout.area("plaza", plaza);
        String req = "skill:hunting:15";
        layout.zone("critter_safari", "Critter Safari", 'a', new Box(isl.minX() - 4, 0, isl.minZ() - 4, isl.maxX() + 4, 255, isl.maxZ() + 4), null);
        layout.zone("safari_entrance", "Safari Entrance", 'e', new Box(-18, y - 4, -18, 18, y + 14, 18), null, Layout.Zone.SAFE);
        layout.zone("safari_forest", "Forest Biome", '2', Box.of(-60, 0, -110, 60, 200, -24), req);
        layout.zone("safari_cavern", "Cavern Biome", '8', Box.of(cx - 26, 0, -26, cx + 26, 200, 26), req);
        layout.zone("safari_icy", "Icy Biome", 'b', Box.of(-60, 0, 24, 60, 200, 110), req);
        layout.zone("safari_haunted", "Haunted Biome", '5', Box.of(-110, 0, -40, -24, 200, 40), req);
        layout.data("reservations", res.summary());
    }

    /** Which biome a column belongs to: the stronger compass axis. */
    static String biome(int x, int z) {
        if (Math.abs(x) >= Math.abs(z)) return x > 0 ? "cavern" : "haunted";
        return z < 0 ? "forest" : "icy";
    }

    private static void desk(VoxelCanvas c, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) c.set(x + dx, y + 1, z + (z < 0 ? -1 : 1), "spruce_slab[type=top]");
        for (int[] p : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int k = 1; k <= 3; k++) c.set(x + p[0], y + k, z + p[1], "spruce_fence");
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) c.set(x + dx, y + 4, z + dz, ((dx + dz) & 1) == 0 ? "green_wool" : "white_wool");
        c.set(x, y + 3, z, "lantern[hanging=true]");
    }
}
