package com.skyclone.mod.island.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * The genre's starter Private Island, one copy per profile (INSTANCED). PURE: no Bukkit.
 *
 * <p>Map (north = -z):
 * <pre>
 *            NW islet + lookout          starter house (2 floors, chests)
 *                 \ bridge                    |
 *   [portal]--spawn plaza--Gerald ---- path ----+---- minion terrace (5 marked 5x5 pads)
 *                    |                          |
 *              wheat/carrot farm ---- pond + dock ---- quarry mound (regenerating rock)
 * </pre>
 * Everything a player starts with is in reach of spawn; the rest of the 161-wide build area
 * (grown to 241 by Island Size upgrades) is open sky for them to fill.
 *
 * <p>Preview: {@code bash tools/preview.sh com.skyclone.mod.island.gen.PrivateIslandGenerator}
 */
public final class PrivateIslandGenerator implements IslandGenerator {

    public static final String ISLAND = "private_island";
    /** Bump to rebuild the TEMPLATE (new instances get it; built instances keep their blocks - player builds are never wiped). */
    public static final int VERSION = 6;   // v4 (2026-10-04): the Portal Isle; v5: bridges are a true 3-wide deck on any heading (IslandBridge); v6 (2026-10-06): the Far Court
    /** Half-width of the largest build area (Island Size X = 241 wide, research 05 §6.4). */
    public static final int MAX_HALF = 120;
    /** Fairy souls hidden on the island. */
    public static final int FAIRY_SOULS = 15;

    @Override public String islandId() { return ISLAND; }

    @Override public int version() { return VERSION; }

    @Override public Box bounds() { return new Box(-108, 20, -72, 72, 140, 72); }   // v6: west to -108 for the Far Court

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---------------------------------------------------------------- 1. terrain shape
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 41; shape.radiusZ = 35; shape.baseY = 70;
        shape.hills = 5; shape.hillScale = 34; shape.cliff = 0.5; shape.coastNoise = 0.15; shape.coastScale = 30;
        shape.undersideDepth = 28; shape.undersideNoise = 0.5;
        Terrain.Heightmap h = Terrain.island(shape);

        // ---------------------------------------------------------------- 2. plots (flatten BEFORE fill)
        Box plaza = Box.of(-32, 0, -6, -20, 0, 6);
        Box portalPlot = Box.of(-37, 0, -4, -33, 0, 4);
        Box housePlot = Box.of(-2, 0, -28, 13, 0, -14);
        Box minionPlot = Box.of(13, 0, -10, 33, 0, 8);
        Box farmPlot = Box.of(-24, 0, 10, -10, 0, 21);
        int py = clampY(h.average(plaza), 70);
        h.flatten(plaza, py, 5);
        h.flatten(portalPlot, py, 2);
        int hy = clampY(h.average(housePlot), 71);
        h.flatten(housePlot, hy, 5);
        int my = clampY(h.average(minionPlot), 70);
        h.flatten(minionPlot, my, 5);
        int fy = clampY(h.average(farmPlot), 70);
        h.flatten(farmPlot, fy, 4);

        // ---------------------------------------------------------------- 3. terrain + pond
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        rules.cliff = Palette.of("stone:8,andesite:4,cobblestone:2,mossy_cobblestone:2,tuff:1");
        rules.underside = Palette.of("stone:6,andesite:3,cobblestone:2,tuff:2,dirt:2,coal_ore:1");
        Terrain.fill(c, h, rules);
        int pondX = 2, pondZ = 19;
        int water = Terrain.pond(c, h, pondX, pondZ, 5.5, 3, seed);

        // the little islet to the north-west, its own heightmap so it stays separate
        Terrain.IslandShape isle = new Terrain.IslandShape(Hash.salt(seed, "islet"));
        isle.centerX = -49; isle.centerZ = -33; isle.radiusX = 8; isle.radiusZ = 7; isle.baseY = py + 3;
        isle.hills = 2; isle.hillScale = 12; isle.cliff = 0.6; isle.coastNoise = 0.12; isle.undersideDepth = 16;
        Terrain.Heightmap ih = Terrain.island(isle);
        Box isletTop = Box.of(-52, 0, -36, -46, 0, -30);
        int iy = clampY(ih.average(isletTop), py + 3);
        ih.flatten(isletTop, iy, 2);
        Terrain.fill(c, ih, rules);

        Reservations res = new Reservations();
        List<int[]> souls = new ArrayList<>();

        // ---------------------------------------------------------------- 4. spawn plaza + portal
        res.reserve("plaza", Box.of(plaza.minX(), py - 4, plaza.minZ(), plaza.maxX(), py + 8, plaza.maxZ()));
        Palette paving = Palette.of("stone_bricks:6,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) {
                double d = Math.hypot((x + 26) / 6.6, z / 6.6);
                if (d > 1.0) continue;                                      // round plaza, grass corners
                c.set(x, py, z, d > 0.86 ? "polished_andesite" : paving.at(x, py, z));
            }
        // a ring of seats and planters
        Builders.bench(c, -28, py, -5, "south", 3, "spruce");
        Builders.bench(c, -26, py, 5, "north", 3, "spruce");
        Builders.flowerPot(c, -21, py, -4, "red_tulip");
        Builders.flowerPot(c, -21, py, 4, "blue_orchid");
        Builders.lampPost(c, -30, py, -4, "post", "north");
        Builders.lampPost(c, -30, py, 4, "post", "north");
        Builders.lampPost(c, -21, py, -2, "post", "north");

        // portal to the hub: valid obsidian frame (so neighbour updates never pop it), opening 3 wide x 4 tall on the z axis
        res.reserve("portal", Box.of(-38, py - 4, -4, -33, py + 8, 4));
        Palette base = Palette.of("polished_blackstone:3,polished_blackstone_bricks:2,gilded_blackstone:1");
        for (int z = -4; z <= 4; z++)
            for (int x = -37; x <= -33; x++) c.set(x, py, z, base.at(x, py, z));
        int portalX = -35;
        for (int y = py + 1; y <= py + 5; y++) { c.set(portalX, y, -2, "obsidian"); c.set(portalX, y, 2, "obsidian"); }
        for (int z = -2; z <= 2; z++) { c.set(portalX, py, z, "obsidian"); c.set(portalX, py + 5, z, "obsidian"); }
        for (int z = -1; z <= 1; z++)
            for (int y = py + 1; y <= py + 4; y++) c.set(portalX, y, z, "nether_portal[axis=z]");
        // pillars and crown so it reads from across the island
        for (int sz : new int[]{-3, 3}) {
            for (int y = py + 1; y <= py + 6; y++) c.set(portalX, y, sz, y == py + 6 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
            c.set(portalX, py + 7, sz, Blocks.soulLantern(false));
        }
        for (int z = -2; z <= 2; z++) c.set(portalX, py + 6, z, Blocks.slab("polished_blackstone_brick", "bottom"));
        c.set(portalX, py + 6, 0, "crying_obsidian");
        Builders.wallBanner(c, portalX + 1, py + 4, -3, "purple", "east");
        Builders.wallBanner(c, portalX + 1, py + 4, 3, "purple", "east");
        Builders.lampPost(c, -33, py, -4, "soul", "east");
        Builders.lampPost(c, -33, py, 4, "soul", "east");
        layout.area("portal.hub", Box.of(portalX, py + 1, -1, portalX, py + 4, 1));
        souls.add(new int[]{-37, py, -4});                                   // tucked behind the portal pillar

        // ---------------------------------------------------------------- 5. starter house
        Builders.House house = new Builders.House(0, hy, -26, 11, 8);
        house.floors = 2;
        house.door = "south";
        house.wall = Palette.of("oak_planks:6,stripped_oak_log[axis=y]:1,spruce_planks:1");
        house.frame = "spruce_log";
        house.roof = "spruce";
        house.roofStyle = "gable";
        house.chimney = true;
        house.seed = seed;
        boolean houseOk = res.reserve("house", house.reserve());
        if (houseOk) {
            house.build(c);
            // the starter chests: a pair against the west wall, a workbench + furnace on the east
            c.set(1, hy + 1, -22, "chest[facing=east,type=right]");
            c.set(1, hy + 1, -21, "chest[facing=east,type=left]");
            c.set(9, hy + 1, -22, "crafting_table");
            c.set(9, hy + 1, -21, "furnace[facing=west,lit=false]");
            c.set(9, hy + 2, -21, "potted_cornflower");
            // upstairs: a bed, a bookshelf and the loft soul
            int up = hy + house.floorHeight;
            c.set(8, up + 1, -24, "red_bed[facing=north,part=head]");
            c.set(8, up + 1, -23, "red_bed[facing=north,part=foot]");
            c.set(9, up + 1, -20, "bookshelf");
            c.set(9, up + 2, -20, "bookshelf");
            c.set(5, up + 3, -22, Blocks.lantern(true));
            souls.add(new int[]{9, up, -24});
            // a porch: two lanterns and a barrel by the door
            int[] ds = house.doorStep;
            c.set(ds[0] - 2, hy + 1, ds[2], "barrel[facing=up,open=false]");
            c.set(ds[0] + 2, hy + 1, ds[2], Blocks.lantern(false));
            souls.add(new int[]{-1, hy, -29});                               // behind the house, in the north-west corner
        }
        // a well and a woodpile in the yard east of the house
        int wx = 17, wz = -20;
        int wg = groundAt(c, wx, wz, 80);
        if (res.reserve("well", Box.of(wx - 3, wg - 3, wz - 3, wx + 3, wg + 5, wz + 3))) {
            for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
                c.set(wx + dx, wg, wz + dz, Math.abs(dx) == 3 || Math.abs(dz) == 3 ? "coarse_dirt" : "cobblestone");
                clearAbove(c, wx + dx, wg, wz + dz, 5);
            }
            Builders.well(c, wx, wg, wz);
            c.set(wx + 3, wg + 1, wz - 2, "oak_log[axis=x]");
            c.set(wx + 3, wg + 1, wz - 1, "oak_log[axis=x]");
            c.set(wx + 3, wg + 2, wz - 2, "oak_log[axis=x]");
            souls.add(new int[]{wx - 3, wg, wz + 3});                        // at the corner of the well yard
        }

        // ---------------------------------------------------------------- 6. minion terrace
        res.reserve("minions", Box.of(minionPlot.minX(), my - 4, minionPlot.minZ(), minionPlot.maxX(), my + 6, minionPlot.maxZ()));
        int[][] pads = {{16, -5}, {23, -5}, {30, -5}, {19, 3}, {26, 3}};
        Palette ring = Palette.of("stone_bricks:5,mossy_stone_bricks:2,cracked_stone_bricks:1");
        for (int i = 0; i < pads.length; i++) {
            int px = pads[i][0], pz = pads[i][1];
            // a 7x7 stone-brick frame flush with the ground around the 5x5 work area the minion needs
            for (int dz = -3; dz <= 3; dz++)
                for (int dx = -3; dx <= 3; dx++) {
                    boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    int x = px + dx, z = pz + dz;
                    c.set(x, my, z, edge ? ring.at(x, my, z) : "grass_block");
                    c.set(x, my - 1, z, "dirt");
                    clearAbove(c, x, my, z, 4);
                }
            c.set(px, my, pz, "polished_andesite");
            layout.standAt("minion.pad." + (i + 1), px, my, pz, 0f);
        }
        Builders.lampPost(c, 14, my, -9, "street", "east");
        Builders.lampPost(c, 32, my, -1, "street", "west");
        Builders.lampPost(c, 14, my, 7, "street", "east");
        Builders.wallBanner(c, 14, my + 3, -8, "lime", "south");
        souls.add(new int[]{33, my, 7});                                     // the far corner of the terrace

        // ---------------------------------------------------------------- 7. farm
        res.reserve("farm", Box.of(farmPlot.minX(), fy - 4, farmPlot.minZ(), farmPlot.maxX(), fy + 6, farmPlot.maxZ()));
        int fx0 = farmPlot.minX() + 1, fx1 = farmPlot.maxX() - 1, fz0 = farmPlot.minZ() + 1, fz1 = farmPlot.maxZ() - 1;
        String[] crops = {"wheat[age=7]", "carrots[age=7]", "potatoes[age=7]", "wheat[age=5]"};
        for (int z = fz0; z <= fz1; z++)
            for (int x = fx0; x <= fx1; x++) {
                clearAbove(c, x, fy, z, 4);
                if (x == (fx0 + fx1) / 2) {                                  // irrigation channel
                    c.set(x, fy, z, "water");
                    c.set(x, fy - 1, z, "dirt");
                    continue;
                }
                c.set(x, fy, z, "farmland[moisture=7]");
                c.set(x, fy - 1, z, "dirt");
                int band = (z - fz0) / 3;
                c.set(x, fy + 1, z, crops[Math.floorMod(band + (x < (fx0 + fx1) / 2 ? 0 : 1), crops.length)]);
            }
        // a plank walkway across the channel and a fence around the field with a gate facing the path
        int midX = (fx0 + fx1) / 2;
        c.set(midX, fy, fz0, "spruce_planks");
        c.set(midX, fy + 1, fz0, "air");
        List<int[]> fence = Builders.fenceLine(c, new int[][]{{farmPlot.minX(), farmPlot.minZ()}, {farmPlot.maxX(), farmPlot.minZ()},
                {farmPlot.maxX(), farmPlot.maxZ()}, {farmPlot.minX(), farmPlot.maxZ()}}, "oak_fence", true);
        Builders.gate(c, midX, fy + 1, farmPlot.minZ(), "oak", "north");
        c.set(midX, fy, farmPlot.minZ(), "dirt_path");
        Builders.lampPost(c, farmPlot.minX(), fy, farmPlot.minZ() - 1, "torch", "north");
        Builders.lampPost(c, farmPlot.maxX(), fy, farmPlot.minZ() - 1, "torch", "north");
        c.set(farmPlot.maxX() - 1, fy + 1, farmPlot.maxZ() + 1, "composter");
        c.set(farmPlot.minX() + 1, fy + 1, farmPlot.maxZ() + 1, "hay_block[axis=y]");
        souls.add(new int[]{farmPlot.minX(), fy, farmPlot.maxZ() + 2});      // outside the far fence corner
        if (fence.isEmpty()) layout.data("warn.fence", "farm fence empty");

        // ---------------------------------------------------------------- 8. pond dock
        if (water != Terrain.Heightmap.NONE) {
            res.reserve("pond", Box.of(pondX - 8, water - 5, pondZ - 8, pondX + 8, water + 4, pondZ + 8));
            int dockX = pondX, startZ = pondZ - 9, endZ = pondZ;
            for (int z = startZ; z <= endZ; z++)
                for (int x = dockX; x <= dockX + 1; x++) {
                    if (c.isAir(x, water + 1, z) || "water".equals(c.get(x, water, z))) {
                        if ("water".equals(c.get(x, water, z))) c.set(x, water, z, "spruce_planks");
                    }
                }
            // posts + lanterns at the dock end, a barrel to sit on
            c.set(dockX - 1, water, endZ, "spruce_planks");
            c.set(dockX + 2, water, endZ, "spruce_planks");
            c.set(dockX - 1, water + 1, endZ, "spruce_fence");
            c.set(dockX + 2, water + 1, endZ, "spruce_fence");
            c.set(dockX - 1, water + 2, endZ, Blocks.lantern(false));
            c.set(dockX + 2, water + 2, endZ, Blocks.lantern(false));
            layout.standAt("dock", dockX, water, endZ - 1, 0f);
            souls.add(new int[]{dockX + 1, water, endZ});                    // the very end of the dock
            layout.area("pond", Box.of(pondX - 7, water - 4, pondZ - 7, pondX + 7, water, pondZ + 7));
            c.set(pondX - 5, water + 1, pondZ - 6, "lily_pad");
            c.set(pondX + 4, water + 1, pondZ + 2, "lily_pad");
            c.set(pondX - 3, water + 1, pondZ + 4, "lily_pad");
        }

        // ---------------------------------------------------------------- 9. quarry mound (regenerating rock)
        int qx = 23, qz = 19;
        int qg = groundAt(c, qx, qz, 70);
        res.reserve("quarry", Box.of(qx - 7, qg - 4, qz - 6, qx + 7, qg + 8, qz + 6));
        Palette rock = Palette.of("stone:10,cobblestone:5,andesite:3,coal_ore:2,iron_ore:1,mossy_cobblestone:1");
        Shapes.ellipsoid(c, qx, qg + 0.5, qz, 6.2, 3.4, 5.2, rock, 0);
        // a stepped ledge up the west face so the top is walkable
        for (int s = 0; s < 3; s++) {
            int sx = qx - 6 + s, sy = qg + s;
            for (int sz = qz - 1; sz <= qz + 1; sz++) {
                c.set(sx, sy, sz, rock.at(sx, sy, sz));
                for (int a = 1; a <= 3; a++) c.set(sx, sy + a, sz, "air");
            }
        }
        int qtop = c.topY(qx, qz, qg, qg + 8);
        c.set(qx - 1, qtop + 1, qz + 2, "lava_cauldron");                     // a lit kettle on the summit
        Builders.lampPost(c, qx - 8, groundAt(c, qx - 8, qz - 4, 76), qz - 4, "post", "north");
        layout.area("quarry", Box.of(qx - 7, qg - 2, qz - 6, qx + 7, qg + 5, qz + 6));
        souls.add(new int[]{qx + 1, qtop, qz});                              // the summit, up the ledge

        // ---------------------------------------------------------------- 10. bridge to the islet + lookout
        int[] mainEnd = landEnd(h, -49, -33, 0, 0, 2);
        int[] isleEnd = landEnd(ih, 0, 0, -49, -33, 1);
        Builders.PathResult paths = new Builders.PathResult();
        int[][] bridgeLine = new int[2][];
        if (mainEnd != null && isleEnd != null) {
            // v1-v4 laid the bridge first and the path after, which re-cut the ground under the bridge head into a
            // step; the ends recorded for repairing those islands are the ground BEFORE the path (IslandBridges)
            layout.data(IslandBridge.DATA_ISLET + IslandBridge.LEGACY, IslandBridge.encode(mainEnd[0], groundAt(c, mainEnd[0], mainEnd[1], 90),
                    mainEnd[1], isleEnd[0], groundAt(c, isleEnd[0], isleEnd[1], 90), isleEnd[1]));
            // a path from the plaza to the bridge head, THEN the bridge on the ground the path left
            Builders.path(c, new int[][]{{-26, -6}, {-28, -14}, {mainEnd[0], mainEnd[1]}}, 3,
                    Palette.of("dirt_path:6,coarse_dirt:2,gravel:1"), "cobblestone_stairs", paths);
            int y1 = groundAt(c, mainEnd[0], mainEnd[1], 90), y2 = groundAt(c, isleEnd[0], isleEnd[1], 90);
            IslandBridge.build(c, mainEnd[0], y1, mainEnd[1], isleEnd[0], y2, isleEnd[1]);
            layout.data(IslandBridge.DATA_ISLET, IslandBridge.encode(mainEnd[0], y1, mainEnd[1], isleEnd[0], y2, isleEnd[1]));
            bridgeLine[0] = mainEnd; bridgeLine[1] = isleEnd;
        }
        Builders.Tower look = new Builders.Tower(-50, iy, -34);
        look.radius = 2; look.height = 9; look.top = "battlements"; look.door = "east";
        look.wall = Palette.of("stone_bricks:5,mossy_stone_bricks:3,cracked_stone_bricks:1");
        if (res.reserve("lookout", look.reserve())) {
            look.build(c);
            souls.add(new int[]{look.platform[0], look.platform[1], look.platform[2]});
        }
        souls.add(new int[]{-54, groundAt(c, -54, -30, 100), -30});         // the islet's far (west) edge, behind the tower
        if (mainEnd != null) souls.add(new int[]{mainEnd[0] + 3, groundAt(c, mainEnd[0] + 3, mainEnd[1] + 2, 90), mainEnd[1] + 2});  // beside the bridge head

        // ---------------------------------------------------------------- 10b. the Portal Isle (v4)
        // Research 03 §1.1: "a frame appears on the Private Island for every island you have unlocked". The frames are
        // built here for every destination; the module LIGHTS each one per profile at runtime (visited + warp requirement).
        int[][] portalBridge = new int[2][];
        Terrain.Heightmap ph = portalIsle(c, layout, res, rules, py, seed);
        if (ph != null) {
            int[] pMain = landEnd(h, PISLE_X + 14, PISLE_Z, -14, 14, 2);
            int[] pIsle = landEnd(ph, -14, 14, PISLE_X + 14, PISLE_Z, 1);
            if (pMain != null && pIsle != null) {
                layout.data(IslandBridge.DATA_PORTAL_ISLE + IslandBridge.LEGACY, IslandBridge.encode(pMain[0], groundAt(c, pMain[0], pMain[1], 90),
                        pMain[1], pIsle[0], groundAt(c, pIsle[0], pIsle[1], 90), pIsle[1]));
                // plaza -> west of the farm fence -> bridge head, and from the bridge foot into the courtyard; paths
                // first, so the bridge is laid level with the ground they leave (see the islet bridge above)
                Builders.path(c, new int[][]{{-27, 6}, {-30, 13}, {-29, 21}, {pMain[0], pMain[1]}}, 3,
                        Palette.of("dirt_path:6,coarse_dirt:2,gravel:1"), "cobblestone_stairs", paths);
                Builders.path(c, new int[][]{{pIsle[0], pIsle[1]}, {PISLE_X + 15, PISLE_Z}}, 3,
                        Palette.of("dirt_path:6,coarse_dirt:2,gravel:1"), "cobblestone_stairs", paths);
                int y1 = groundAt(c, pMain[0], pMain[1], 90), y2 = groundAt(c, pIsle[0], pIsle[1], 90);
                IslandBridge.build(c, pMain[0], y1, pMain[1], pIsle[0], y2, pIsle[1]);
                layout.data(IslandBridge.DATA_PORTAL_ISLE, IslandBridge.encode(pMain[0], y1, pMain[1], pIsle[0], y2, pIsle[1]));
                portalBridge[0] = pMain; portalBridge[1] = pIsle;
                layout.standAt("portal_isle", PISLE_X + 13, groundAt(c, PISLE_X + 13, PISLE_Z, 90), PISLE_Z, 90f);
                dropLoose(c, Box.of(Math.min(pMain[0], pIsle[0]) - 3, Math.min(y1, y2) - 2, Math.min(pMain[1], pIsle[1]) - 3,
                        Math.max(pMain[0], pIsle[0]) + 3, Math.max(y1, y2) + 4, Math.max(pMain[1], pIsle[1]) + 3));
            } else layout.data("warn.portal_isle", "no bridge");
        }

        // ---------------------------------------------------------------- 10c. the Far Court (v6)
        // Four more destinations (the Garden, Backwater Bayou, Lotus Atoll, Torrhus Canyon) on a small islet west of the
        // Portal Isle, so islands built before v6 can be given it by filling EMPTY sky only (IslandPortals.retrofit).
        int[][] farBridge = new int[2][];
        Terrain.Heightmap fh = ph == null ? null : farCourt(c, layout, res, rules, py, seed);
        if (fh != null) {
            int[] fIsle = landEnd(ph, FAR_X + 8, FAR_Z, PISLE_X - 13, PISLE_Z, 2);      // the Portal Isle's west coast
            int[] fFar = landEnd(fh, PISLE_X - 13, PISLE_Z, FAR_X + 8, FAR_Z, 1);      // the Far Court's east coast
            if (fIsle != null && fFar != null) {
                Builders.path(c, new int[][]{{PISLE_X - 14, PISLE_Z}, {fIsle[0], fIsle[1]}}, 3,
                        Palette.of("dirt_path:6,coarse_dirt:2,gravel:1"), "cobblestone_stairs", paths);
                Builders.path(c, new int[][]{{fFar[0], fFar[1]}, {FAR_X + 10, FAR_Z}}, 3,
                        Palette.of("dirt_path:6,coarse_dirt:2,gravel:1"), "cobblestone_stairs", paths);
                int y1 = groundAt(c, fIsle[0], fIsle[1], 90), y2 = groundAt(c, fFar[0], fFar[1], 90);
                IslandBridge.build(c, fIsle[0], y1, fIsle[1], fFar[0], y2, fFar[1]);
                layout.data(IslandBridge.DATA_FAR_COURT, IslandBridge.encode(fIsle[0], y1, fIsle[1], fFar[0], y2, fFar[1]));
                farBridge[0] = fIsle; farBridge[1] = fFar;
                dropLoose(c, Box.of(Math.min(fIsle[0], fFar[0]) - 3, Math.min(y1, y2) - 2, Math.min(fIsle[1], fFar[1]) - 3,
                        Math.max(fIsle[0], fFar[0]) + 3, Math.max(y1, y2) + 4, Math.max(fIsle[1], fFar[1]) + 3));
            } else layout.data("warn.far_court", "no bridge");
        }

        // ---------------------------------------------------------------- 11. paths between everything
        Palette path = Palette.of("dirt_path:6,coarse_dirt:2,gravel:1");
        int doorX = houseOk ? house.doorStep[0] : 5, doorZ = houseOk ? house.doorStep[2] : -16;
        Builders.path(c, new int[][]{{-20, 0}, {-8, -2}, {2, -6}, {doorX, doorZ + 3}, {doorX, doorZ + 1}}, 3, path, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{2, -6}, {8, -2}, {12, -1}}, 3, path, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-20, 2}, {-14, 6}, {midXFarm(farmPlot), farmPlot.minZ() - 2}}, 3, path, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-8, -2}, {-2, 6}, {pondX, pondZ - 10}}, 3, path, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{8, -2}, {12, 8}, {qx - 8, qz}}, 3, path, "cobblestone_stairs", paths);
        // lamps along the main road (every ~12 blocks)
        for (int[] lp : new int[][]{{-12, 3}, {-1, -9}, {9, 2}, {-5, 7}, {11, 12}, {-28, -11}}) {
            int g = groundAt(c, lp[0], lp[1], 90);
            if (!paths.covers(lp[0], lp[1]) && !res.covers(lp[0], lp[1], 0)) Builders.lampPost(c, lp[0], g, lp[1], "street", "south");
        }

        // ---------------------------------------------------------------- 12. trees, plants, rocks
        // the island's signature oak by the plaza (the first log every player breaks)
        int ox = -15, oz = -12;
        int og = groundAt(c, ox, oz, 90);
        Trees.grow(c, "big_oak", ox, og, oz, Hash.salt(seed, "first-oak"), 1.0);
        res.reserve("first-oak", Box.of(ox - 4, og, oz - 4, ox + 4, og + 14, oz + 4));
        souls.add(new int[]{ox + 3, og, oz - 3});                            // in the shade behind the big oak's trunk
        Terrain.grassAndFlowers(c, h, seed, 0.20, 0.04);
        Terrain.grassAndFlowers(c, ih, seed + 7, 0.25, 0.05);
        if (ph != null) Terrain.grassAndFlowers(c, ph, seed + 13, 0.22, 0.05);
        if (fh != null) Terrain.grassAndFlowers(c, fh, seed + 23, 0.22, 0.06);
        // a wooded rim (north and east especially) around an open meadow left for the player to build on
        java.util.function.BiPredicate<Integer, Integer> keep = (x, z) -> res.covers(x, z, 3) || paths.near(x, z, 2)
                || Math.hypot(x - ox, z - oz) < 7 || nearSegment(bridgeLine, x, z, 4) || nearSegment(portalBridge, x, z, 4);
        Trees.forest(c, h, h.box(), seed, 0.09, 4, "oak:5,birch:3,big_oak:1,spruce:1", 2,
                (x, z) -> keep.test(x, z) || rimDistance(x, z) < 0.66 || (z > 4 && x > -6 && x < 12));
        Trees.forest(c, h, h.box(), seed + 11, 0.012, 6, "oak:3,birch:2", 3,
                (x, z) -> keep.test(x, z) || rimDistance(x, z) >= 0.66);
        Trees.forest(c, ih, ih.box(), seed + 3, 0.05, 4, "birch:2,oak:1", 2,
                (x, z) -> res.covers(x, z, 3) || paths.near(x, z, 2));
        if (ph != null) Trees.forest(c, ph, ph.box(), seed + 17, 0.05, 4, "birch:2,oak:1,azalea:1", 2,
                (x, z) -> res.covers(x, z, 3) || paths.near(x, z, 2) || nearSegment(farBridge, x, z, 4));
        if (fh != null) Trees.forest(c, fh, fh.box(), seed + 19, 0.05, 4, "cherry:1,birch:1,oak:1", 2,
                (x, z) -> res.covers(x, z, 3) || paths.near(x, z, 2) || nearSegment(farBridge, x, z, 4));
        // a few mossy boulders along the south and north coasts
        Palette mossy = Palette.of("mossy_cobblestone:3,cobblestone:2,stone:2,andesite:1");
        for (int[] b : new int[][]{{-6, 28}, {16, 28}, {-30, 18}, {30, -20}, {-20, -24}}) {
            if (!h.inland(b[0], b[1], 2) || res.covers(b[0], b[1], 2) || paths.near(b[0], b[1], 2)) continue;
            int g = groundAt(c, b[0], b[1], 90);
            Shapes.ellipsoid(c, b[0], g + 0.6, b[1], 1.8, 1.3, 1.6, mossy, 0);
        }
        souls.add(new int[]{-6, groundAt(c, -6, 26, 90), 26});                // by the south coast rocks
        souls.add(new int[]{30, groundAt(c, 30, -17, 90), -17});              // the north-east cliff top
        souls.add(new int[]{-8, groundAt(c, -8, -20, 90), -20});              // between the oak and the house

        // ---------------------------------------------------------------- 13. Gerald's corner of the plaza
        c.set(-22, py + 1, 5, "barrel[facing=up,open=false]");
        c.set(-23, py + 1, 6, "potted_oxeye_daisy");

        // ---------------------------------------------------------------- 14. layout
        layout.standAt("spawn", -25, py, 0, -90f);
        layout.standAt("npc.island_helper", -22, py, 3, 135f);
        if (houseOk) layout.standAt("house.inside", house.inside[0], house.inside[1], house.inside[2], 180f);

        int n = 0;
        for (int[] s : souls) {
            int sx = s[0], sz = s[2];
            int sy = s[1];
            // stand on the highest full block at or just below the hint
            int g = Builders.ground(c, sx, sz, sy + 1, sy - 6);
            if (g == Integer.MIN_VALUE) continue;
            if (!c.isAir(sx, g + 1, sz) && !isPlant(c.get(sx, g + 1, sz))) continue;
            if (isPlant(c.get(sx, g + 1, sz))) c.set(sx, g + 1, sz, "air");
            if (!c.isAir(sx, g + 2, sz)) continue;
            n++;
            layout.standAt("fairy_soul." + n, sx, g, sz, 0f);
            if (n >= FAIRY_SOULS) break;
        }
        layout.data("fairy_souls", Integer.toString(n));

        Box minionArea = Box.of(minionPlot.minX(), my + 1, minionPlot.minZ(), minionPlot.maxX(), my + 4, minionPlot.maxZ());
        layout.area("minions", minionArea);
        layout.area("farm", Box.of(farmPlot.minX(), fy, farmPlot.minZ(), farmPlot.maxX(), fy + 3, farmPlot.maxZ()));
        layout.area("plaza", Box.of(plaza.minX(), py + 1, plaza.minZ(), plaza.maxX(), py + 4, plaza.maxZ()));
        if (houseOk) {
            Box hb = house.reserve();
            layout.area("house", Box.of(hb.minX(), hy, hb.minZ(), hb.maxX(), house.peakY, hb.maxZ()));
        }
        // the default build area: Island Size 0 = 161 wide (research 05 §6.4: 161 -> 241). The module shrinks/grows it per profile.
        layout.area("build", Box.of(-80, 1, -80, 80, 255, 80));
        layout.zone("private_island", "Private Island", 'a', Box.of(-MAX_HALF, 0, -MAX_HALF, MAX_HALF, 319, MAX_HALF), null, Layout.Zone.BUILD);
        layout.data("walk.ignore", "house.");
        layout.data("reservations", res.summary());
    }

    // ---------------------------------------------------------------- the Portal Isle

    /**
     * Destination portals, in the order they stand: north row west->east, then south row west->east.
     * {warp id, frame-corner palette, crown block, carpet colour}. The module reads {@link #PORTALS} to know which
     * warp each {@code isportal.<warp>} area leads to.
     */
    public static final String[][] PORTALS = {
            {"barn", "hay_block[axis=y]:2,stripped_oak_log[axis=y]:1", "oak_planks", "yellow"},
            {"desert", "red_sandstone:3,cut_red_sandstone:1", "red_mushroom_block", "red"},
            {"park", "birch_log[axis=y]:2,moss_block:1", "birch_planks", "lime"},
            {"gold_mine", "gold_block:1,yellow_terracotta:2", "raw_gold_block", "yellow"},
            {"deep_caverns", "deepslate_bricks:3,lapis_block:1", "polished_deepslate", "blue"},
            {"dwarven_mines", "prismarine_bricks:3,dark_prismarine:1", "light_blue_terracotta", "cyan"},
            {"spiders_den", "mossy_cobblestone:2,tuff:1", "mossy_stone_bricks", "black"},
            {"the_end", "end_stone_bricks:3,purpur_block:1", "purpur_pillar[axis=y]", "magenta"},
            {"crimson_isle", "red_nether_bricks:3,nether_wart_block:1", "crimson_planks", "red"},
            {"dungeon_hub", "polished_deepslate:2,cracked_deepslate_bricks:2", "chiseled_deepslate", "gray"},
    };
    /** Centre of the Portal Isle (south-west of the main island, joined by a bridge from beside the farm). */
    public static final int PISLE_X = -50, PISLE_Z = 40;
    /** Prefix of the walk-in areas and the standing anchors in front of them: {@code isportal.<warp>}. Not {@code portal.}: core would warp without the visited check. */
    public static final String PORTAL_AREA = "isportal.";

    /** Builds the islet, its courtyard and every portal frame (unlit); returns its heightmap (null if it failed). */
    private static Terrain.Heightmap portalIsle(VoxelCanvas c, Layout layout, Reservations res, Terrain.SurfaceRules rules, int py, long seed) {
        int cx = PISLE_X, cz = PISLE_Z;
        Terrain.IslandShape s = new Terrain.IslandShape(Hash.salt(seed, "portal-isle"));
        s.centerX = cx; s.centerZ = cz; s.radiusX = 19; s.radiusZ = 12; s.baseY = py - 1;
        s.hills = 1; s.hillScale = 14; s.cliff = 0.6; s.coastNoise = 0.07; s.coastScale = 20; s.undersideDepth = 18; s.undersideNoise = 0.5;
        Terrain.Heightmap ph = Terrain.island(s);
        Box court = Box.of(cx - 14, 0, cz - 7, cx + 16, 0, cz + 7);
        int gy = clampY(ph.average(court), py - 1);
        ph.flatten(court, gy, 3);
        Terrain.fill(c, ph, rules);
        if (!res.reserve("portal_isle", Box.of(cx - 14, gy - 4, cz - 7, cx + 16, gy + 8, cz + 7))) return ph;

        // courtyard: stone paving with a darker border, a little fountain in the middle
        Palette paving = Palette.of("stone_bricks:5,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1");
        for (int z = cz - 5; z <= cz + 5; z++)
            for (int x = cx - 13; x <= cx + 16; x++) {
                boolean edge = z == cz - 5 || z == cz + 5 || x == cx + 16;
                c.set(x, gy, z, edge ? "polished_andesite" : paving.at(x, gy, z));
                c.set(x, gy - 1, z, "stone");
                clearAbove(c, x, gy, z, 6);
            }
        Builders.fountain(c, cx, gy, cz, 2, Palette.of("stone_bricks:3,mossy_stone_bricks:1"), seed);
        Builders.lampPost(c, cx + 15, gy, cz - 4, "street", "west");
        Builders.lampPost(c, cx + 15, gy, cz + 4, "street", "west");

        // two rows of five portals facing each other across the courtyard; pillars are shared between neighbours
        portalRow(c, layout, PORTALS, 0, 5, cx - 13, cz - 6, 1, gy);
        portalRow(c, layout, PORTALS, 5, 5, cx - 13, cz + 6, -1, gy);
        layout.area("portal_isle", Box.of(cx - 13, gy + 1, cz - 6, cx + 16, gy + 6, cz + 6));
        layout.data("portal_isle.y", Integer.toString(gy));
        return ph;
    }

    /**
     * One row of {@code count} portal frames standing on z = {@code rz}, facing the courtyard ({@code dz}: +1 = south,
     * -1 = north). Pillars at {@code firstPillarX + 5i} are shared between neighbours; opening i is the 2x3 hole at
     * x = firstPillarX + 2 + 5i. Each portal is described by defs[from + i] = {warp, corner palette, crown, carpet}.
     */
    private static void portalRow(VoxelCanvas c, Layout layout, String[][] defs, int from, int count, int firstPillarX, int rz, int dz, int gy) {
        for (int i = 0; i <= count; i++) {
            int x = firstPillarX + 5 * i;
            for (int y = gy - 1; y <= gy + 5; y++) c.set(x, y, rz, y == gy + 5 ? "chiseled_stone_bricks" : "stone_bricks");
            c.set(x, gy + 6, rz, Blocks.lantern(false));
        }
        for (int i = 0; i < count; i++) {
            String[] d = defs[from + i];
            int px = firstPillarX + 2 + 5 * i;                            // opening is px..px+1
            Palette corner = Palette.of(d[1]);
            for (int x = px - 1; x <= px + 2; x++) {
                boolean side = x == px - 1 || x == px + 2;
                for (int y = gy; y <= gy + 4; y++) {
                    boolean cap = y == gy || y == gy + 4;
                    if (side && cap) c.set(x, y, rz, corner.at(x, y, rz));      // corners carry the destination's colours
                    else if (side || cap) c.set(x, y, rz, "obsidian");         // a VALID 2x3 frame, so the lit portal never pops
                    else c.set(x, y, rz, "air");                                // lit per profile by the module
                }
                c.set(x, gy + 5, rz, d[2]);
                c.set(x, gy - 1, rz, "stone");
            }
            // a runner in the destination's colour leading into the portal
            c.set(px, gy + 1, rz + dz, d[3] + "_carpet");
            c.set(px + 1, gy + 1, rz + dz, d[3] + "_carpet");
            layout.area(PORTAL_AREA + d[0], Box.of(px, gy + 1, rz, px + 1, gy + 3, rz));
            layout.standAt(PORTAL_AREA + d[0], px, gy, rz + dz * 2, dz > 0 ? 180f : 0f);
        }
    }

    // ---------------------------------------------------------------- the Far Court (v6)

    /**
     * Wave 5 destinations: the Garden, Backwater Bayou, Lotus Atoll and Torrhus Canyon. They stand on their own
     * islet (the Far Court) west of the Portal Isle rather than on it, so an island built before v6 can be given
     * them by filling empty sky only - nothing a player built on the Portal Isle is ever in the way.
     * North row west->east, then south row west->east.
     */
    public static final String[][] FAR_PORTALS = {
            {"garden", "moss_block:2,stripped_jungle_log[axis=y]:1", "moss_block", "green"},
            {"backwater_bayou", "mud_bricks:2,mangrove_log[axis=y]:1", "mangrove_planks", "brown"},
            {"lotus_atoll", "pink_terracotta:2,white_terracotta:1", "cherry_planks", "pink"},
            {"torrhus_canyon", "terracotta:2,orange_terracotta:1", "smooth_red_sandstone", "orange"},
    };
    /** Centre of the Far Court (west of the Portal Isle, joined by a straight bridge from its courtyard). */
    public static final int FAR_X = -88, FAR_Z = 40;

    /** Every portal the module lights: the Portal Isle's ten, then the Far Court's four. */
    public static final String[][] ALL_PORTALS = concat(PORTALS, FAR_PORTALS);

    private static String[][] concat(String[][] a, String[][] b) {
        String[][] out = new String[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Builds the Far Court islet, its little courtyard and four portal frames (unlit); returns its heightmap. */
    private static Terrain.Heightmap farCourt(VoxelCanvas c, Layout layout, Reservations res, Terrain.SurfaceRules rules, int py, long seed) {
        int cx = FAR_X, cz = FAR_Z;
        Terrain.IslandShape s = new Terrain.IslandShape(Hash.salt(seed, "far-court"));
        s.centerX = cx; s.centerZ = cz; s.radiusX = 14; s.radiusZ = 11; s.baseY = py - 1;
        s.hills = 1; s.hillScale = 12; s.cliff = 0.6; s.coastNoise = 0.06; s.coastScale = 18; s.undersideDepth = 15; s.undersideNoise = 0.5;
        Terrain.Heightmap fh = Terrain.island(s);
        Box court = Box.of(cx - 8, 0, cz - 7, cx + 10, 0, cz + 7);
        int gy = clampY(fh.average(court), py - 1);
        fh.flatten(court, gy, 3);
        Terrain.fill(c, fh, rules);
        if (!res.reserve("far_court", Box.of(cx - 8, gy - 4, cz - 7, cx + 10, gy + 8, cz + 7))) return fh;

        Palette paving = Palette.of("stone_bricks:5,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1");
        for (int z = cz - 5; z <= cz + 5; z++)
            for (int x = cx - 7; x <= cx + 9; x++) {
                boolean edge = z == cz - 5 || z == cz + 5 || x == cx - 7 || x == cx + 9;
                c.set(x, gy, z, edge ? "polished_andesite" : paving.at(x, gy, z));
                c.set(x, gy - 1, z, "stone");
                clearAbove(c, x, gy, z, 6);
            }
        // a planter of the four places' colours in the middle, and lamps by the arrival
        c.set(cx, gy + 1, cz, "flowering_azalea");
        for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) c.set(cx + o[0], gy + 1, cz + o[1], "moss_carpet");
        Builders.lampPost(c, cx + 8, gy, cz - 4, "street", "west");
        Builders.lampPost(c, cx + 8, gy, cz + 4, "street", "west");
        Builders.lampPost(c, cx - 6, gy, cz, "street", "east");

        portalRow(c, layout, FAR_PORTALS, 0, 2, cx - 5, cz - 6, 1, gy);
        portalRow(c, layout, FAR_PORTALS, 2, 2, cx - 5, cz + 6, -1, gy);
        layout.area("far_court", Box.of(cx - 7, gy + 1, cz - 6, cx + 9, gy + 6, cz + 6));
        layout.standAt("far_court", cx + 7, gy, cz, 90f);
        layout.data("far_court.y", Integer.toString(gy));
        return fh;
    }

    // ---------------------------------------------------------------- helpers

    /** Remove single blocks with nothing on any side (a bridge rail post left hanging past the coast). */
    private static void dropLoose(VoxelCanvas c, Box b) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    if (c.isAir(x, y, z)) continue;
                    if (c.isAir(x + 1, y, z) && c.isAir(x - 1, y, z) && c.isAir(x, y + 1, z) && c.isAir(x, y - 1, z)
                            && c.isAir(x, y, z + 1) && c.isAir(x, y, z - 1)) c.set(x, y, z, "air");
                }
    }

    private static int clampY(int y, int fallback) { return y == Terrain.Heightmap.NONE ? fallback : y; }

    private static int midXFarm(Box farm) { return (farm.minX() + 1 + farm.maxX() - 1) / 2; }

    static int groundAt(VoxelCanvas c, int x, int z, int from) {
        int g = Builders.ground(c, x, z, from + 30, 20);
        return g == Integer.MIN_VALUE ? 70 : g;
    }

    /** 0 at the island centre, ~1 at the coast of the main ellipse. */
    private static double rimDistance(int x, int z) { return Math.hypot(x / 41.0, z / 35.0); }

    private static boolean nearSegment(int[][] seg, int x, int z, double r) {
        if (seg[0] == null || seg[1] == null) return false;
        double ax = seg[0][0], az = seg[0][1], bx = seg[1][0], bz = seg[1][1];
        double vx = bx - ax, vz = bz - az, len2 = vx * vx + vz * vz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / len2));
        return Math.hypot(x - (ax + vx * t), z - (az + vz * t)) < r;
    }

    private static void clearAbove(VoxelCanvas c, int x, int y, int z, int n) {
        for (int a = 1; a <= n; a++) c.set(x, y + a, z, "air");
    }

    private static boolean isPlant(String b) {
        if (b == null) return false;
        String n = b.startsWith("minecraft:") ? b.substring(10) : b;
        int i = n.indexOf('[');
        if (i >= 0) n = n.substring(0, i);
        return n.equals("short_grass") || n.equals("tall_grass") || n.equals("fern") || n.equals("large_fern")
                || n.endsWith("tulip") || n.equals("dandelion") || n.equals("poppy") || n.equals("cornflower")
                || n.equals("oxeye_daisy") || n.equals("azure_bluet") || n.equals("allium") || n.equals("blue_orchid")
                || n.equals("lily_of_the_valley") || n.equals("grass");
    }

    /**
     * Walk from (fromX, fromZ) toward (toX, toZ) and return the first column that is land on {@code h}
     * and at least {@code margin} blocks inland, stepping {@code margin} further in for a solid footing.
     */
    static int[] landEnd(Terrain.Heightmap h, int fromX, int fromZ, int toX, int toZ, int margin) {
        double len = Math.hypot(toX - fromX, toZ - fromZ);
        int n = (int) Math.ceil(len);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int x = (int) Math.round(fromX + (toX - fromX) * t), z = (int) Math.round(fromZ + (toZ - fromZ) * t);
            if (h.has(x, z) && h.inland(x, z, margin)) {
                double t2 = Math.min(1, t + margin / len);
                return new int[]{(int) Math.round(fromX + (toX - fromX) * t2), (int) Math.round(fromZ + (toZ - fromZ) * t2)};
            }
        }
        return null;
    }
}
