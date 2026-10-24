package com.skyclone.mod.mayors;

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
 * The Winter Workshop ({@code jerrys_workshop}): a snowy island with a lit spawn plaza around a
 * decorated great tree, Gerald's Workshop (the siege target) to the east, the emporium to the north,
 * a festival hall to the south where the seasonal vendors keep their off-season stalls, a frozen
 * fishing pond and Mount Gerald. Pure Java: {@code bash tools/preview.sh com.skyclone.mod.mayors.WinterWorkshopGenerator}.
 */
public final class WinterWorkshopGenerator implements IslandGenerator {

    @Override public String islandId() { return "jerrys_workshop"; }

    @Override public int version() { return 2; }

    @Override public Box bounds() { return new Box(-104, 16, -104, 104, 160, 104); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---- 1. terrain ----
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 84; shape.radiusZ = 76; shape.baseY = 70;
        shape.hills = 4; shape.hillScale = 40; shape.cliff = 0.5; shape.undersideDepth = 30; shape.coastNoise = 0.16;
        Terrain.Heightmap h = Terrain.island(shape);

        Box plaza = Box.of(-52, 0, -12, -28, 0, 12);
        Box workshopPlot = Box.of(2, 0, -11, 30, 0, 11);
        Box emporiumPlot = Box.of(-49, 0, -44, -32, 0, -28);
        Box hallPlot = Box.of(-53, 0, 19, -28, 0, 36);
        Box stallPlot = Box.of(-27, 0, -22, -19, 0, -14);
        int py = h.average(plaza);
        h.flatten(plaza, py, 6);
        h.flatten(stallPlot, py, 3);
        int wy = h.average(workshopPlot);
        h.flatten(workshopPlot, wy, 7);
        int ey = h.average(emporiumPlot);
        h.flatten(emporiumPlot, ey, 5);
        int hy = h.average(hallPlot);
        h.flatten(hallPlot, hy, 5);

        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.top = Palette.of("snow_block:10,packed_ice:1");
        rules.soil = Palette.of("dirt:4,snow_block:1");
        rules.cliff = Palette.of("stone:6,andesite:3,snow_block:2,packed_ice:1");
        rules.underside = Palette.of("stone:6,andesite:3,packed_ice:2,tuff:2");
        Terrain.fill(c, h, rules);

        Reservations res = new Reservations();

        // ---- 2. Mount Gerald (decorative peak, NE) ----
        int mx = 42, mz = -40;
        Box mountBox = Box.of(mx - 19, 0, mz - 19, mx + 19, 0, mz + 19);
        int my = h.average(Box.of(mx - 6, 0, mz - 6, mx + 6, 0, mz + 6)) - 3;
        res.reserve("mount", new Box(mountBox.minX(), my - 4, mountBox.minZ(), mountBox.maxX(), my + 40, mountBox.maxZ()));
        Shapes.cone(c, mx, my, mz, 17, 30, Palette.of("snow_block:6,packed_ice:2,stone:2,andesite:1"));
        Shapes.cone(c, mx, my + 22, mz, 6, 10, Fill.of("snow_block"));

        // ---- 2b. Glacial Cave: a tunnel into Mount Gerald's south face and an ice chamber (Frostbitten Aleks) ----
        int[] cave = glacialCave(c, mx, mz, seed);

        // ---- 3. spawn plaza + great tree ----
        res.reserve("plaza", new Box(plaza.minX(), py - 2, plaza.minZ(), plaza.maxX(), py + 40, plaza.maxZ()));
        Palette paving = Palette.of("polished_diorite:4,snow_block:2,smooth_quartz:1,packed_ice:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, py, z, paving.at(x, py, z));
        int tx = -40, tz = 0;
        Shapes.ring(c, tx + 0.5, py, tz + 0.5, 4.5, 1.2, Fill.of("spruce_planks"));
        Trees.grow(c, "mega_spruce", tx, py, tz, seed, 1.25);
        decorateTree(c, tx, py, tz, seed);
        for (int[] l : new int[][]{{-51, -11}, {-51, 11}, {-29, -11}, {-29, 11}, {-40, -11}, {-40, 11}})
            Builders.lampPost(c, l[0], py, l[1], "street", "north");
        Builders.bench(c, -46, py, -8, "south", 3, "spruce");
        Builders.bench(c, -36, py, 8, "north", 3, "spruce");

        // portal back to the Hub on the plaza's west side: a framed 3x4 opening along z
        Palette frame = Palette.of("packed_ice:3,blue_ice:2,polished_diorite:1");
        int px = -51;
        for (int y = py + 1; y <= py + 4; y++) { c.set(px, y, -2, frame.at(px, y, -2)); c.set(px, y, 2, frame.at(px, y, 2)); }
        for (int z = -2; z <= 2; z++) c.set(px, py + 5, z, frame.at(px, py + 5, z));
        for (int z = -1; z <= 1; z++)
            for (int y = py + 1; y <= py + 4; y++) c.set(px, y, z, "nether_portal[axis=z]");
        layout.area("portal.hub", Box.of(px, py + 1, -1, px, py + 4, 1));

        // ---- 4. Gerald's Workshop (the siege target) ----
        Builders.House shop = new Builders.House(5, wy, -8, 23, 17);
        shop.floors = 2; shop.floorHeight = 5; shop.door = "west";
        shop.wall = Palette.of("spruce_planks:6,stripped_spruce_log[axis=y]:1");
        shop.frame = "dark_oak_log"; shop.roof = "red_nether_brick"; shop.roofFull = "red_nether_bricks";
        shop.roofStyle = "gable"; shop.chimney = true; shop.chimneyBlock = "bricks"; shop.seed = seed;
        boolean shopOk = res.reserve("workshop", shop.reserve());
        if (shopOk) shop.build(c);
        int[] shopStep = shopOk ? shop.doorStep : new int[]{4, wy, 0};

        // ---- 5. Ingvar's Emporium (north) ----
        Builders.House emp = new Builders.House(-46, ey, -41, 11, 9);
        emp.door = "south"; emp.wall = Palette.of("stripped_birch_log[axis=y]:2,birch_planks:5");
        emp.frame = "spruce_log"; emp.roof = "spruce"; emp.roofStyle = "hip"; emp.furnish = false; emp.seed = seed;
        boolean empOk = res.reserve("emporium", emp.reserve());
        if (empOk) emp.build(c);

        // ---- 6. Festival Hall (south): seasonal vendors keep their stalls here ----
        Builders.House hall = new Builders.House(-50, hy, 22, 19, 11);
        hall.door = "north"; hall.floorHeight = 5;
        hall.wall = Palette.of("spruce_planks:5,stripped_dark_oak_log[axis=y]:1");
        hall.frame = "dark_oak_log"; hall.roof = "dark_oak"; hall.roofStyle = "gable"; hall.furnish = false; hall.seed = seed;
        boolean hallOk = res.reserve("hall", hall.reserve());
        if (hallOk) hall.build(c);

        // ---- 7. Marnie's stall ----
        Builders.Stall stall = null;
        if (res.reserve("stall", Box.of(-26, py - 12, -21, -20, py + 5, -15)))
            stall = Builders.marketStall(c, -23, py, -18, "south", "red", "white", seed);

        // ---- 8. frozen pond ----
        int pcx = -8, pcz = 34;
        Terrain.pond(c, h, pcx, pcz, 7, 3, seed);
        int pondTop = h.top(pcx, pcz);
        for (int x = pcx - 8; x <= pcx + 8; x++)
            for (int z = pcz - 8; z <= pcz + 8; z++) {
                if (!"water".equals(c.get(x, pondTop, z)) && !c.get(x, pondTop, z).startsWith("water")) continue;
                boolean hole = Math.abs(x - pcx) <= 1 && Math.abs(z - pcz) <= 1;
                if (!hole && Hash.chance(seed, x * 7, z * 13, 0.85)) c.set(x, pondTop, z, "ice");
            }
        res.reserve("pond", Box.of(pcx - 8, pondTop - 4, pcz - 8, pcx + 8, pondTop + 2, pcz + 8));
        layout.area("pond", Box.of(pcx - 7, pondTop - 3, pcz - 7, pcx + 7, pondTop + 1, pcz + 7));

        // ---- 9. paths ----
        Palette path = Palette.of("packed_ice:3,snow_block:2,polished_andesite:2,gravel:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{-28, 0}, {-12, 1}, {shopStep[0] - 3, shopStep[2]}, {shopStep[0], shopStep[2]}},
                3, path, "spruce_stairs", null);
        if (empOk) Builders.path(c, new int[][]{{-40, -12}, {-41, -22}, {emp.doorStep[0], emp.doorStep[2] + 2}, {emp.doorStep[0], emp.doorStep[2]}},
                3, path, "spruce_stairs", paths);
        if (hallOk) Builders.path(c, new int[][]{{-40, 12}, {-41, 17}, {hall.doorStep[0], hall.doorStep[2] - 1}, {hall.doorStep[0], hall.doorStep[2]}},
                3, path, "spruce_stairs", paths);
        Builders.path(c, new int[][]{{-29, -8}, {-23, -13}}, 2, path, "spruce_stairs", paths);
        Builders.path(c, new int[][]{{-30, 10}, {-18, 22}, {pcx - 6, pcz - 8}}, 2, path, "spruce_stairs", paths);
        int[][] lamps = {{-18, 3}, {-6, 3}, {-36, -20}, {-36, 18}, {-20, 18}};
        for (int[] l : lamps) {
            int g = Builders.ground(c, l[0], l[1], 140, 30);
            if (g != Integer.MIN_VALUE && !paths.near(l[0], l[1], 0)) Builders.lampPost(c, l[0], g, l[1], "street", "south");
        }

        // ---- 10. trees ----
        Trees.forest(c, h, h.box(), seed, 0.03, 6, "spruce:5,pine:3,mega_spruce:1", 3,
                (x, z) -> res.covers(x, z, 4) || paths.near(x, z, 3));

        // ---- 11. anchors ----
        layout.standAt("spawn", -34, py, 5, 90f);
        int jg = Builders.ground(c, shopStep[0] - 4, shopStep[2] + 3, 140, 30);
        layout.standAt("npc.jerry", shopStep[0] - 4, jg == Integer.MIN_VALUE ? wy : jg, shopStep[2] + 3, 270f);
        int tg = Builders.ground(c, shopStep[0] - 1, shopStep[2], 140, 30);
        layout.standAt("siege.target", shopStep[0] - 1, tg == Integer.MIN_VALUE ? wy : tg, shopStep[2], 90f);
        if (empOk) layout.standAt("npc.einary", emp.x + 5, emp.y, emp.z + 3, 0f);
        if (stall != null) layout.standAt("npc.sherry", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 0f);
        if (hallOk) {
            String[] vendors = {"npc.fear_mongerer", "npc.baker", "npc.zoo", "npc.mythology", "npc.stonks"};
            for (int i = 0; i < vendors.length; i++) {
                int vx = hall.x + 2 + i * 3 + (i >= 3 ? 1 : 0), vz = hall.z + hall.depth - 3;
                layout.standAt(vendors[i], vx, hall.y, vz, 180f);
                c.set(vx, hall.y + 4, vz + 1, "lantern[hanging=true]");
            }
            // the carnival troupe waits along the west wall while the Fairground perk is off
            String[] troupe = {"npc.carnival_leader", "npc.carnival_pirate", "npc.carnival_tokens"};
            for (int i = 0; i < troupe.length; i++) {
                int vz = hall.z + 2 + i * 2;
                layout.standAt(troupe[i], hall.x + 2, hall.y, vz, -90f);
                c.set(hall.x + 2, hall.y + 4, vz, "lantern[hanging=true]");
            }
            // ...and the cowboy (Zombie Shootout) against the east wall
            layout.standAt("npc.carnival_cowboy", hall.x + hall.width - 3, hall.y, hall.z + 2, 90f);
            c.set(hall.x + hall.width - 3, hall.y + 4, hall.z + 2, "lantern[hanging=true]");
            layout.standAt("npc.carnival_fisher", hall.x + hall.width - 3, hall.y, hall.z + 4, 90f);
            c.set(hall.x + hall.width - 3, hall.y + 4, hall.z + 4, "lantern[hanging=true]");
            // stall counters along the back wall, one gap per vendor
            for (int x = hall.x + 1; x <= hall.x + hall.width - 2; x++) c.set(x, hall.y + 1, hall.z + hall.depth - 2, "spruce_trapdoor[half=top,facing=north,open=false]");
        }
        // shops-run seasonal vendors: the ice carver by the pond, the frozen trader in the Glacial Cave
        int icx = pcx + 10, icz = pcz;
        int icg = Builders.ground(c, icx, icz, 140, 30);
        if (icg != Integer.MIN_VALUE) layout.standAt("npc.ice_carver", icx, icg, icz, 90f);
        if (cave != null) {
            layout.standAt("npc.frozen_trader", cave[0], cave[1], cave[2] - 5, 0f);
            layout.zone("glacial_cave", "Glacial Cave", 'b', new Box(cave[0] - 6, cave[1] - 1, cave[2] - 6, cave[0] + 6, cave[1] + 7, cave[2] + 14), null, Layout.Zone.SAFE);
            layout.area("glacial_cave", new Box(cave[0] - 5, cave[1], cave[2] - 5, cave[0] + 5, cave[1] + 6, cave[2] + 5));
        }
        int[] angles = {0, 25, 50, -20, -45, 75};
        int k = 1;
        for (int a : angles) {
            double rad = Math.toRadians(a);
            int x = (int) Math.round(Math.cos(rad) * 58), z = (int) Math.round(Math.sin(rad) * 58);
            int g = Builders.ground(c, x, z, 140, 30);
            if (g == Integer.MIN_VALUE || Math.abs(g - wy) > 14) continue;
            layout.standAt("siege.spawn." + k++, x, g, z, 180f);
        }
        layout.data("walk.ignore", "siege.spawn.");

        // ---- 12. zones ----
        Box island = h.box();
        layout.zone("jerrys_workshop", "Winter Workshop", 'b', new Box(island.minX(), 0, island.minZ(), island.maxX(), 255, island.maxZ()), null);
        layout.zone("workshop_plaza", "Workshop Plaza", 'b', new Box(plaza.minX(), py - 3, plaza.minZ(), plaza.maxX(), py + 30, plaza.maxZ()), null, Layout.Zone.SAFE);
        if (shopOk) { Box b = shop.reserve(); layout.zone("geralds_workshop", "Gerald's Workshop", 'c', new Box(b.minX() - 6, wy - 3, b.minZ() - 4, b.maxX(), shop.peakY, b.maxZ()), null); }
        if (empOk) { Box b = emp.reserve(); layout.zone("emporium", "Ingvar's Emporium", 'd', new Box(b.minX(), ey - 2, b.minZ(), b.maxX(), emp.peakY, b.maxZ()), null, Layout.Zone.SAFE); }
        if (hallOk) { Box b = hall.reserve(); layout.zone("festival_hall", "Festival Hall", '6', new Box(b.minX(), hy - 2, b.minZ(), b.maxX(), hall.peakY, b.maxZ()), null, Layout.Zone.SAFE); }
        layout.zone("geralds_pond", "Gerald's Pond", '3', Box.of(pcx - 9, pondTop - 4, pcz - 9, pcx + 9, pondTop + 8, pcz + 9), null);
        layout.zone("mount_gerald", "Mount Gerald", 'f', new Box(mountBox.minX(), my, mountBox.minZ(), mountBox.maxX(), my + 45, mountBox.maxZ()), null);
        layout.area("siege", Box.of(-30, wy - 6, -60, 75, wy + 12, 60));
        layout.data("reservations", res.summary());
    }

    /**
     * Carve the Glacial Cave into the mountain's south face: a 3-wide, 4-tall tunnel running north from
     * open ground into an 11x11 ice chamber. Returns the chamber's floor block {x, y, z} (centre), or null when
     * the ground south of the mountain is missing.
     */
    private static int[] glacialCave(VoxelCanvas c, int mx, int mz, long seed) {
        int ex = mx, ez = mz + 19;
        int g = Builders.ground(c, ex, ez, 150, 30);
        if (g == Integer.MIN_VALUE) return null;
        int fy = g;                                  // tunnel and chamber floor sit at the entrance's ground level
        int cz = mz + 2;                             // chamber centre, inside the cone
        Palette wall = Palette.of("packed_ice:5,blue_ice:3,snow_block:1");
        Palette floor = Palette.of("packed_ice:3,blue_ice:1,snow_block:1");
        // chamber: 11x11, 6 tall, ice shell
        for (int x = mx - 6; x <= mx + 6; x++)
            for (int z = cz - 6; z <= cz + 6; z++)
                for (int y = fy; y <= fy + 7; y++) {
                    boolean shell = Math.abs(x - mx) == 6 || Math.abs(z - cz) == 6 || y == fy || y == fy + 7;
                    c.set(x, y, z, shell ? (y == fy ? floor.at(x, y, z) : wall.at(x, y, z)) : "air");
                }
        // tunnel south to the entrance (and a little past it, so the mouth is never sealed)
        for (int z = cz + 6; z <= ez + 2; z++)
            for (int x = mx - 2; x <= mx + 2; x++)
                for (int y = fy; y <= fy + 5; y++) {
                    boolean shell = Math.abs(x - mx) == 2 || y == fy || y == fy + 5;
                    String cur = c.get(x, y, z);
                    boolean solid = cur != null && !cur.equals("air");
                    if (!shell) c.set(x, y, z, "air");
                    else if (y == fy) c.set(x, y, z, floor.at(x, y, z));
                    else if (solid) c.set(x, y, z, wall.at(x, y, z));   // line the rock, never build walls in open air
                }
        // light, icicles and a counter for the trader
        for (int[] l : new int[][]{{mx - 4, cz - 4}, {mx + 4, cz - 4}, {mx - 4, cz + 4}, {mx + 4, cz + 4}})
            c.set(l[0], fy + 6, l[1], "lantern[hanging=true]");
        for (int z = cz + 7; z <= ez; z += 4) c.set(mx, fy + 4, z, "lantern[hanging=true]");
        for (int x = mx - 5; x <= mx + 5; x++)
            for (int z = cz - 5; z <= cz + 5; z++)
                if (Hash.chance(seed, x * 11, z * 5, 0.12)) c.set(x, fy + 6, z, "pointed_dripstone[vertical_direction=down,thickness=tip]");
        for (int x = mx - 2; x <= mx + 2; x++) c.set(x, fy + 1, cz - 4, "spruce_trapdoor[half=top,facing=south,open=false]");
        c.set(mx - 5, fy + 1, cz - 5, "blue_ice"); c.set(mx + 5, fy + 1, cz - 5, "blue_ice");
        c.set(mx - 5, fy + 1, cz + 5, "chest[facing=east]");
        return new int[]{mx, fy, cz};
    }

    /** Ornaments on the great tree: coloured lights replace some leaves; a star on top. */
    private static void decorateTree(VoxelCanvas c, int tx, int py, int tz, long seed) {
        String[] lights = {"red_concrete", "yellow_concrete", "sea_lantern", "glowstone", "blue_concrete", "lime_concrete"};
        int top = Integer.MIN_VALUE;
        for (int y = py + 1; y <= py + 40; y++)
            for (int x = tx - 7; x <= tx + 8; x++)
                for (int z = tz - 7; z <= tz + 8; z++) {
                    String b = c.get(x, y, z);
                    if (b == null || !b.contains("leaves")) continue;
                    top = Math.max(top, y);
                    if (Hash.chance(seed, x * 31 + y, z * 17 - y, 0.09)) {
                        int i = (int) Math.floorMod(Hash.hash(seed, x, y, z), lights.length);
                        c.set(x, y, z, lights[i]);
                    }
                }
        if (top != Integer.MIN_VALUE) {
            for (int x = tx - 1; x <= tx + 2; x++)
                for (int z = tz - 1; z <= tz + 2; z++)
                    if (c.get(x, top, z) != null && c.get(x, top, z).contains("leaves")) { c.set(x, top + 1, z, "gold_block"); return; }
        }
    }
}
