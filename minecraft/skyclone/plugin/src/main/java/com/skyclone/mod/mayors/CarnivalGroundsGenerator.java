package com.skyclone.mod.mayors;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

/**
 * The Carnival Grounds ({@code carnival}): a flat fairground island where the three Carnival booths are played
 * in the world, not in a menu. Pure Java: {@code bash tools/preview.sh com.skyclone.mod.mayors.CarnivalGroundsGenerator}.
 * <ul>
 *   <li><b>Fruit Digging</b> (north): a 7x7 patch of real sand ({@link #DIG_X0}..+6, {@link #DIG_Z0}..+6 at y {@link #Y}),
 *       fenced, under a striped canopy. What a player digs up is shown to that player only (block changes), so any
 *       number of people can dig the same patch at once.</li>
 *   <li><b>Zombie Shootout</b> (east): a firing stand facing two walkways of different height and distance with a
 *       back wall of redstone lamps. Zombies and lit lamps are per-player too.</li>
 *   <li><b>Catch a Fish</b> (south): a dock over a 13x9 pond.</li>
 *   <li>The Ringmaster, the Scorekeeper and the Token Trader on the plaza; a portal back to the Hub in the west.</li>
 * </ul>
 * Layout contract read by {@code CarnivalGames}: area {@code carnival.dig} (the 49 sand blocks), anchor
 * {@code carnival.shoot.stand}, areas {@code carnival.shoot.lane1} / {@code lane2} (feet space the zombies walk),
 * anchors {@code lamp.1..5} (lamp block centres), anchor {@code carnival.fish.stand}, area {@code carnival.fish.pond}
 * (the water). Everything is flat at {@link #Y} so the numbers stay readable.
 */
public final class CarnivalGroundsGenerator implements IslandGenerator {

    public static final String ID = "carnival";
    /** Ground level: a person stands at Y + 1. */
    public static final int Y = 70;
    public static final int DIG_X0 = -3, DIG_Z0 = -31, DIG_SIZE = 7;
    public static final int STAND_X = 24, LANE1_X = 33, LANE2_X = 39, WALL_X = 42;
    public static final int[][] LAMPS = {{-6, 7}, {-3, 9}, {0, 6}, {3, 10}, {6, 8}};   // {z, height above Y}
    public static final int POND_X0 = -6, POND_X1 = 6, POND_Z0 = 26, POND_Z1 = 34;

    @Override public String islandId() { return ID; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-72, 20, -72, 72, 120, 72); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---- 1. a flat, round fairground ----
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 58; shape.radiusZ = 54; shape.baseY = Y;
        shape.hills = 2; shape.hillScale = 30; shape.cliff = 0.4; shape.undersideDepth = 24; shape.coastNoise = 0.12;
        Terrain.Heightmap h = Terrain.island(shape);
        h.flatten(Box.of(-50, 0, -44, 50, 0, 44), Y, 6);
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.top = Palette.of("grass_block:12,coarse_dirt:1");
        Terrain.fill(c, h, rules);

        Palette paving = Palette.of("smooth_sandstone:5,cut_sandstone:2,sandstone:1");
        Palette path = Palette.of("dirt_path:5,coarse_dirt:1,gravel:1");

        // ---- 2. plaza + carousel ----
        for (int x = -12; x <= 12; x++)
            for (int z = -12; z <= 12; z++)
                if (x * x + z * z <= 150) c.set(x, Y, z, paving.at(x, Y, z));
        carousel(c, 0, 0);
        // paths to each booth and to the portal
        Builders.PathResult paths = Builders.path(c, new int[][]{{0, -11}, {0, -20}}, 3, path, "oak_stairs", null);
        Builders.path(c, new int[][]{{11, 0}, {STAND_X - 2, 0}}, 3, path, "oak_stairs", paths);
        Builders.path(c, new int[][]{{0, 11}, {0, 21}}, 3, path, "oak_stairs", paths);
        Builders.path(c, new int[][]{{-11, 0}, {-38, 0}}, 3, path, "oak_stairs", paths);

        // ---- 3. portal back to the Hub (west) ----
        Palette frame = Palette.of("red_terracotta:2,yellow_terracotta:2,white_terracotta:1");
        int px = -42;
        for (int y = Y + 1; y <= Y + 4; y++) { c.set(px, y, -2, frame.at(px, y, -2)); c.set(px, y, 2, frame.at(px, y, 2)); }
        for (int z = -2; z <= 2; z++) c.set(px, Y + 5, z, frame.at(px, Y + 5, z));
        for (int z = -1; z <= 1; z++) for (int y = Y + 1; y <= Y + 4; y++) c.set(px, y, z, "nether_portal[axis=z]");
        for (int z = -2; z <= 2; z++) c.set(px, Y, z, "chiseled_sandstone");
        layout.area("portal.hub", Box.of(px, Y + 1, -1, px, Y + 4, 1));

        // ---- 4. Fruit Digging (north) ----
        int x0 = DIG_X0, z0 = DIG_Z0, x1 = DIG_X0 + DIG_SIZE - 1, z1 = DIG_Z0 + DIG_SIZE - 1;
        for (int x = x0 - 3; x <= x1 + 3; x++)
            for (int z = z0 - 3; z <= z1 + 3; z++) c.set(x, Y, z, "smooth_sandstone");
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) { c.set(x, Y, z, "sand"); c.set(x, Y - 1, z, "sandstone"); }
        // fence ring two blocks out, open on the south side
        for (int x = x0 - 2; x <= x1 + 2; x++)
            for (int z = z0 - 2; z <= z1 + 2; z++) {
                boolean edge = x == x0 - 2 || x == x1 + 2 || z == z0 - 2 || z == z1 + 2;
                boolean gate = z == z1 + 2 && Math.abs(x) <= 1;
                if (edge && !gate) c.set(x, Y + 1, z, "oak_fence");
            }
        canopy(c, x0 - 3, z0 - 3, x1 + 3, z1 + 3, Y + 6, "orange", "white");
        layout.area("carnival.dig", Box.of(x0, Y, z0, x1, Y, z1));
        layout.standAt("npc.carnival_pirate", x1 + 4, Y, z1 + 4, 180f);
        Builders.crates(c, x0 - 3, Y, z1 + 5, seed);

        // ---- 5. Zombie Shootout (east) ----
        // the firing stand: a raised quartz block with a rail behind and to the sides
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 1; dx++) c.set(STAND_X + dx, Y, dz, "smooth_quartz");
        for (int dz = -2; dz <= 2; dz++) c.set(STAND_X - 2, Y + 1, dz, dz == 0 ? "air" : "birch_fence");
        c.set(STAND_X - 1, Y + 1, -2, "birch_fence"); c.set(STAND_X - 1, Y + 1, 2, "birch_fence");
        canopy(c, STAND_X - 3, -3, STAND_X + 2, 3, Y + 5, "red", "white");
        // gallery floor
        for (int x = STAND_X + 3; x <= WALL_X; x++)
            for (int z = -11; z <= 11; z++) c.set(x, Y, z, Hash.chance(seed, x, z, 0.2) ? "coarse_dirt" : "podzol");
        // lane 1: a one-high walkway of planks; lane 2: a four-high wall with planks on top
        for (int z = -8; z <= 8; z++) {
            c.set(LANE1_X, Y + 1, z, "spruce_planks");
            for (int y = Y + 1; y <= Y + 3; y++) c.set(LANE2_X, y, z, "stone_bricks");
            c.set(LANE2_X, Y + 4, z, "spruce_planks");
        }
        for (int z : new int[]{-9, 9}) { c.set(LANE1_X, Y + 1, z, "hay_block"); c.set(LANE1_X, Y + 2, z, "carved_pumpkin[facing=west]"); }
        for (int z : new int[]{-9, 9}) for (int y = Y + 1; y <= Y + 5; y++) c.set(LANE2_X, y, z, "stripped_dark_oak_log[axis=y]");
        // the back wall with five lamps
        for (int z = -11; z <= 11; z++)
            for (int y = Y + 1; y <= Y + 11; y++) c.set(WALL_X, y, z, (z + y) % 2 == 0 ? "dark_oak_planks" : "spruce_planks");
        for (int z = -11; z <= 11; z++) c.set(WALL_X, Y + 12, z, "dark_oak_slab[type=bottom]");
        for (int i = 0; i < LAMPS.length; i++) {
            int lz = LAMPS[i][0], ly = Y + LAMPS[i][1];
            c.set(WALL_X, ly, lz, "redstone_lamp[lit=false]");
            layout.anchor("lamp." + (i + 1), WALL_X + 0.5, ly + 0.5, lz + 0.5, 0f);
        }
        // side fences of the gallery
        for (int x = STAND_X + 3; x < WALL_X; x++) { c.set(x, Y + 1, -11, "spruce_fence"); c.set(x, Y + 1, 11, "spruce_fence"); }
        layout.standAt("carnival.shoot.stand", STAND_X, Y, 0, -90f);
        layout.area("carnival.shoot.lane1", Box.of(LANE1_X, Y + 2, -8, LANE1_X, Y + 2, 8));
        layout.area("carnival.shoot.lane2", Box.of(LANE2_X, Y + 5, -8, LANE2_X, Y + 5, 8));
        layout.standAt("npc.carnival_cowboy", STAND_X - 4, Y, -5, -90f);

        // ---- 6. Catch a Fish (south) ----
        for (int x = POND_X0 - 2; x <= POND_X1 + 2; x++)
            for (int z = POND_Z0 - 2; z <= POND_Z1 + 2; z++) {
                boolean water = x >= POND_X0 && x <= POND_X1 && z >= POND_Z0 && z <= POND_Z1;
                if (water) {
                    for (int y = Y - 3; y <= Y; y++) c.set(x, y, z, y == Y - 3 ? "sand" : "water");
                    c.set(x, Y - 4, z, "stone");
                } else {
                    for (int y = Y - 3; y <= Y; y++) c.set(x, y, z, y == Y ? (Hash.chance(seed, x * 3, z, 0.3) ? "moss_block" : "mud_bricks") : "stone");
                }
            }
        // reeds around the rim
        for (int x = POND_X0 - 2; x <= POND_X1 + 2; x++)
            for (int z = POND_Z0 - 2; z <= POND_Z1 + 2; z++) {
                boolean rim = x == POND_X0 - 2 || x == POND_X1 + 2 || z == POND_Z1 + 2;
                if (rim && Hash.chance(seed, x * 7, z * 5, 0.35)) c.set(x, Y + 1, z, Hash.chance(seed, x, z, 0.5) ? "fern" : "short_grass");
            }
        for (int x = POND_X0; x <= POND_X1; x += 4) c.set(x, Y + 1, POND_Z1, "lily_pad");
        // the dock: planks over the north rim with a rail
        for (int x = -2; x <= 2; x++) for (int z = POND_Z0 - 4; z <= POND_Z0 - 2; z++) c.set(x, Y, z, "spruce_planks");
        for (int z = POND_Z0 - 4; z <= POND_Z0 - 2; z++) { c.set(-2, Y + 1, z, "spruce_fence"); c.set(2, Y + 1, z, "spruce_fence"); }
        canopy(c, -3, POND_Z0 - 6, 3, POND_Z0 - 2, Y + 5, "cyan", "white");
        layout.standAt("carnival.fish.stand", 0, Y, POND_Z0 - 2, 0f);
        layout.area("carnival.fish.pond", Box.of(POND_X0, Y - 2, POND_Z0, POND_X1, Y, POND_Z1));
        layout.standAt("npc.carnival_fisher", -5, Y, POND_Z0 - 4, 0f);

        // ---- 7. troupe on the plaza ----
        layout.standAt("npc.carnival_leader", -8, Y, -6, 90f);
        layout.standAt("npc.carnival_goals", -8, Y, 6, 90f);
        layout.standAt("npc.carnival_tokens", 8, Y, 7, 270f);
        Builders.Stall s1 = Builders.marketStall(c, -16, Y, -8, "south", "purple", "white", seed + 3);
        Builders.Stall s2 = Builders.marketStall(c, 16, Y, 10, "north", "lime", "white", seed + 5);
        layout.standAt("spawn", -34, Y, 3, -90f);

        // ---- 8. lights, tents, trees ----
        for (int[] l : new int[][]{{-20, -4}, {-20, 4}, {6, -16}, {-6, -16}, {16, -4}, {16, 4}, {6, 16}, {-6, 16}, {-32, -5}, {-32, 5}})
            if (!paths.near(l[0], l[1], 0)) Builders.lampPost(c, l[0], Y, l[1], "street", "south");
        tent(c, -26, -24, "red", "white");
        tent(c, -26, 24, "blue", "yellow");
        tent(c, 24, 26, "purple", "white");
        tent(c, 24, -26, "green", "white");
        Trees.forest(c, h, h.box(), seed, 0.02, 5, "oak:3,birch:2", 3,
                (x, z) -> (Math.abs(x) <= 46 && Math.abs(z) <= 42) || paths.near(x, z, 3));

        // ---- 9. zones ----
        Box island = h.box();
        layout.zone("carnival", "Carnival Grounds", 'd', new Box(island.minX(), 0, island.minZ(), island.maxX(), 255, island.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("fruit_digging", "Fruit Digging", '6', Box.of(x0 - 3, Y - 2, z0 - 3, x1 + 3, Y + 8, z1 + 6), null, Layout.Zone.SAFE);
        layout.zone("zombie_shootout", "Zombie Shootout", 'c', Box.of(STAND_X - 4, Y - 2, -11, WALL_X, Y + 14, 11), null, Layout.Zone.SAFE);
        layout.zone("catch_a_fish", "Catch a Fish", '3', Box.of(POND_X0 - 3, Y - 4, POND_Z0 - 7, POND_X1 + 3, Y + 8, POND_Z1 + 3), null, Layout.Zone.SAFE);
        layout.data("walk.ignore", "lamp.");
    }

    /** A flat striped canopy on four fence posts. */
    static void canopy(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, String a, String b) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) c.set(x, y, z, ((x - x0) / 2) % 2 == 0 ? a + "_wool" : b + "_wool");
        for (int[] p : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}})
            for (int yy = Y + 1; yy < y; yy++) c.set(p[0], yy, p[1], "oak_fence");
    }

    /** A small round tent (decoration, closed). */
    static void tent(VoxelCanvas c, int cx, int cz, String a, String b) {
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 4.4) continue;
                int top = Y + 1 + (int) Math.round((4.4 - d) * 1.3);
                String wool = (Math.floorMod((int) Math.round(Math.atan2(dz, dx) * 4 / Math.PI), 2) == 0) ? a + "_wool" : b + "_wool";
                c.set(cx + dx, Y, cz + dz, "spruce_planks");
                // the roof is two blocks thick so neighbouring rings always touch (no floating stripes)
                int low = d > 3.6 ? Y + 1 : Math.max(Y + 1, top - 2);
                for (int y = low; y <= top; y++) c.set(cx + dx, y, cz + dz, wool);
            }
        c.set(cx, Y + 8, cz, "lantern");
    }

    /** The carousel in the middle of the plaza (decoration). */
    static void carousel(VoxelCanvas c, int cx, int cz) {
        Shapes.disk(c, cx, Y, cz, 6.4, Fill.of("smooth_quartz"));
        for (int y = Y + 1; y <= Y + 5; y++) c.set(cx, y, cz, "stripped_birch_log[axis=y]");
        for (int a = 0; a < 8; a++) {
            int px = cx + (int) Math.round(Math.cos(a * Math.PI / 4) * 4.5), pz = cz + (int) Math.round(Math.sin(a * Math.PI / 4) * 4.5);
            for (int y = Y + 1; y <= Y + 4; y++) c.set(px, y, pz, "birch_fence");
        }
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 6.4) continue;
                int y = Y + 5 + (d < 2 ? 2 : d < 4 ? 1 : 0);
                c.set(cx + dx, y, cz + dz, (Math.floorMod((int) Math.round(Math.atan2(dz, dx) * 4 / Math.PI), 2) == 0) ? "magenta_wool" : "yellow_wool");
                for (int yy = Y + 5; yy < y; yy++) c.set(cx + dx, yy, cz + dz, "birch_planks");
            }
        c.set(cx, Y + 8, cz, "gold_block");
    }
}
