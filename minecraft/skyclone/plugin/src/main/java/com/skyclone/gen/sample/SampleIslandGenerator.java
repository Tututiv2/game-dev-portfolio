package com.skyclone.gen.sample;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

/**
 * A small worked example of the builder toolkit — copy its shape for a real island:
 * terrain from a heightmap, plots flattened BEFORE the terrain is written, every structure
 * reserved before it is built, paths between door steps, trees kept off plots and paths,
 * lights, and anchors placed from what the builders report (never hand-typed coordinates).
 *
 * <p>Preview it: {@code bash tools/preview.sh com.skyclone.gen.sample.SampleIslandGenerator}
 */
public final class SampleIslandGenerator implements IslandGenerator {

    @Override public String islandId() { return "sample"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-72, 16, -72, 72, 130, 72); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---- 1. terrain shape (numbers only) ----
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 46; shape.radiusZ = 38; shape.baseY = 70;
        shape.hills = 6; shape.cliff = 0.55; shape.undersideDepth = 26; shape.coastNoise = 0.18;
        Terrain.Heightmap h = Terrain.island(shape);

        // ---- 2. plots: flatten first, so the terrain fill already fits the buildings ----
        Box plaza = Box.of(-30, 0, -5, -20, 0, 5);
        Box housePlot = Box.of(3, 0, -18, 17, 0, -5);
        Box stallPlot = Box.of(-29, 0, -13, -21, 0, -7);
        Box towerPlot = Box.of(17, 0, 7, 27, 0, 17);
        Box portalPlot = Box.of(-9, 0, 20, 1, 0, 28);
        int plazaY = h.average(plaza);
        h.flatten(plaza, plazaY, 6);
        h.flatten(stallPlot, plazaY, 3);
        int houseY = h.average(housePlot);
        h.flatten(housePlot, houseY, 6);
        int towerY = h.average(towerPlot);
        h.flatten(towerPlot, towerY, 5);
        int portalY = h.average(portalPlot);
        h.flatten(portalPlot, portalY, 5);

        // ---- 3. write the terrain, then carve a pond ----
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        Terrain.fill(c, h, rules);
        Terrain.pond(c, h, -4, -10, 5, 3, seed);

        Reservations res = new Reservations();

        // ---- 4. spawn plaza with a fountain ----
        res.reserve("plaza", plaza);
        Palette paving = Palette.of("stone_bricks:5,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, plazaY, z, paving.at(x, plazaY, z));
        Builders.fountain(c, -25, plazaY, 0, 2, Palette.of("stone_bricks:4,mossy_stone_bricks:1"), seed);
        for (int[] p : new int[][]{{-30, -5}, {-30, 5}, {-20, -5}, {-20, 5}})
            Builders.lampPost(c, p[0], plazaY, p[1], "post", "north");

        // ---- 5. a cottage ----
        Builders.House house = new Builders.House(6, houseY, -15, 9, 7);
        house.floors = 2;
        house.door = "south";
        house.wall = Palette.of("oak_planks:6,stripped_oak_log[axis=y]:1");
        house.roof = "dark_oak";
        house.roofStyle = "gable";
        house.chimney = true;
        house.seed = seed;
        if (res.reserve("cottage", house.reserve())) house.build(c);

        // ---- 6. a market stall by the plaza ----
        Builders.Stall stall = null;
        Box stallBox = Box.of(-27, plazaY - 12, -13, -23, plazaY + 4, -8);
        if (res.reserve("stall", stallBox))
            stall = Builders.marketStall(c, -25, plazaY, -10, "south", "red", "white", seed);

        // ---- 7. a lookout tower with a walkable top ----
        Builders.Tower tower = new Builders.Tower(22, towerY, 12);
        tower.radius = 3; tower.height = 14; tower.top = "battlements"; tower.door = "west";
        if (res.reserve("tower", tower.reserve())) tower.build(c);

        // ---- 8. the portal back to the hub: a framed 3x3 opening ----
        res.reserve("portal", portalPlot);
        Palette frame = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1");
        for (int x = -7; x <= -1; x++)
            for (int z = 22; z <= 26; z++) c.set(x, portalY, z, Palette.of("polished_blackstone:3,blackstone:1").at(x, portalY, z));
        int pz = 24;
        for (int y = portalY + 1; y <= portalY + 4; y++) {
            c.set(-6, y, pz, frame.at(-6, y, pz));
            c.set(-2, y, pz, frame.at(-2, y, pz));
        }
        for (int x = -6; x <= -2; x++) c.set(x, portalY + 5, pz, frame.at(x, portalY + 5, pz));
        for (int x = -5; x <= -3; x++)
            for (int y = portalY + 1; y <= portalY + 4; y++) c.set(x, y, pz, "nether_portal[axis=x]");
        Builders.lampPost(c, -7, portalY, 22, "soul", "north");
        Builders.lampPost(c, -1, portalY, 22, "soul", "north");
        layout.area("portal.hub", Box.of(-5, portalY + 1, pz, -3, portalY + 4, pz));

        // ---- 9. paths between the places people walk to ----
        Palette path = Palette.of("dirt_path:6,coarse_dirt:2,gravel:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{-19, 0}, {-6, 2}, {house.doorStep[0], house.doorStep[2] + 3}, {house.doorStep[0], house.doorStep[2] + 1}},
                3, path, "cobblestone_stairs", null);
        Builders.path(c, new int[][]{{-12, 1}, {4, 8}, {tower.doorStep[0] - 3, tower.doorStep[2]}, {tower.doorStep[0] - 1, tower.doorStep[2]}},
                3, path, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-22, 6}, {-14, 14}, {-4, 19}}, 3, path, "cobblestone_stairs", paths);
        Builders.lampPost(c, -6, groundAt(c, -6, 5), 5, "street", "south");
        Builders.lampPost(c, 6, groundAt(c, 6, 11), 11, "street", "north");

        // ---- 10. plants and trees, kept off plots and paths ----
        Terrain.grassAndFlowers(c, h, seed, 0.22, 0.035);
        Trees.forest(c, h, h.box(), seed, 0.035, 5, "oak:5,birch:3,big_oak:1,spruce:1", 3,
                (x, z) -> res.covers(x, z, 2) || paths.near(x, z, 2));
        Builders.crates(c, -20, plazaY, -12, seed);
        Builders.bench(c, -22, plazaY, 4, "north", 2, "spruce");

        // ---- 11. what the rest of the game needs to know ----
        layout.standAt("spawn", -29, plazaY, 0, -90f);
        layout.standAt("npc.sample_villager", house.inside[0], house.inside[1], house.inside[2], 180f);
        if (stall != null) layout.standAt("npc.sample_vendor", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 0f);
        layout.standAt("lookout.top", tower.platform[0], tower.platform[1], tower.platform[2], 0f);
        // A deliberately broken anchor, kept for reference: it floats 40 blocks above the island,
        // so the preview marks it UNREACHABLE with a red X and exits 1. Uncomment to see it.
        // layout.standAt("npc.unreachable_example", 0, 110, 0, 0f);

        Box island = h.box();
        layout.zone("sample", "Sample Isle", 'a', new Box(island.minX(), 0, island.minZ(), island.maxX(), 255, island.maxZ()), null);
        layout.zone("plaza", "Spawn Plaza", 'e', new Box(plaza.minX(), plazaY - 2, plaza.minZ(), plaza.maxX(), plazaY + 12, plaza.maxZ()), null, Layout.Zone.SAFE);
        Box hb = house.reserve();
        layout.zone("cottage", "Cottage", 'b', new Box(hb.minX(), houseY - 2, hb.minZ(), hb.maxX(), house.peakY, hb.maxZ()), null);
        layout.area("plaza", plaza);
        layout.data("reservations", res.summary());
    }

    private static int groundAt(VoxelCanvas c, int x, int z) {
        int g = Builders.ground(c, x, z, 120, 30);
        return g == Integer.MIN_VALUE ? 70 : g;
    }
}
