package com.skyclone.mod.fishing.bayou;

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
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * THE BACKWATER BAYOU - the fishing island. A low, wet floating island around one big brown bayou:
 * <ul>
 *   <li><b>Bayou Docks</b> (east): a mud-brick fishing town on a flat terrace - the Tackle-smith's
 *       house, the bait stall, the Scrapper's junk shack, the warden's lookout tower, a well, drying
 *       racks, and the portal home.</li>
 *   <li><b>Bayou Waters</b> (centre): the big brown water, crossed by a long boardwalk from the dock to
 *       the far shore with a covered fishing platform halfway; a sunken boat and a rock islet.</li>
 *   <li><b>Lotus Lagoon</b> (north): clear water full of lily pads around a gazebo islet, cherry trees.</li>
 *   <li><b>Moonlit Marsh</b> (west): mud flats and channels, mangroves with hanging moss, soul lanterns,
 *       a ruined hut, the round Moon Pool and three lava vents where lava striders surf.</li>
 * </ul>
 * Pure: gen toolkit only. Preview: {@code bash tools/preview.sh com.skyclone.mod.fishing.bayou.BayouGenerator}.
 */
public final class BayouGenerator implements IslandGenerator {

    static final int W = 64;            // water level (the water surface block)
    static final int TOWN_Y = 67;       // the terrace the town stands on
    static final int DECK = W + 2;      // boardwalk / dock deck height
    /**
     * Centre line (z) of the boardwalk. It leaves the dock at its NORTH corner on purpose: the path
     * from spawn runs west along z 2..4, so a boardwalk on that line put a deck in front of every
     * forward cast (playtest: 0 bites in 15 casts). Off-axis, the walk ends at the dock's open west
     * edge facing water, and the boardwalk is far enough north (8 blocks off the walk line) that a
     * cast 25 degrees off straight still clears it (BayouCastTest measures both).
     */
    static final int BW_Z = -6;
    /** The dock runs from the boardwalk's north rail to z 9; the path from spawn meets it at z 2..4. */
    static final int DOCK_N = BW_Z - 2, DOCK_S = 9;
    /**
     * Rail on every fishing edge (dock, boardwalk, platform). A fence is 1.5 tall and sits just under
     * eye height, so it caught any flat cast from more than a block away; a top slab is 1.0 tall
     * (still not walkable over - step height is 0.6) and a flat cast clears it from ~5 blocks back.
     */
    static final String RAIL = "spruce_slab[type=top,waterlogged=false]";

    // water bodies
    static final int BAYOU_X = 12, BAYOU_Z = 12;
    static final double BAYOU_RX = 54, BAYOU_RZ = 44;
    static final int LAGOON_X = -8, LAGOON_Z = -80;
    static final double LAGOON_RX = 30, LAGOON_RZ = 22;
    static final int MOON_X = -92, MOON_Z = 0;
    static final int LIGHT_X = 118, LIGHT_Z = -64;
    /** Cottage corners {x, z, door 0=south 1=north}. */
    static final int[][] COTTAGES = {{54, -62, 0}, {76, -56, 0}, {52, 74, 1}, {90, 52, 1}};

    @Override public String islandId() { return "backwater_bayou"; }

    @Override public int version() { return 2; }   // 2: off-axis boardwalk, low rails, dock ladder

    @Override public Box bounds() { return new Box(-180, 0, -155, 180, 150, 155); }

    private Terrain.Heightmap h;
    private int[] depth;       // water depth per heightmap column (0 = land)
    private long seed;

    private int di(int x, int z) {
        int ix = x - h.minX, iz = z - h.minZ;
        if (ix < 0 || iz < 0 || ix >= h.width || iz >= h.depth) return -1;
        return iz * h.width + ix;
    }

    boolean wet(int x, int z) { int i = di(x, z); return i >= 0 && depth[i] > 0; }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        this.seed = seed;
        // ---------------------------------------------------------------- 1. shape
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 140; s.radiusZ = 116; s.baseY = 67;
        s.hills = 3.5; s.hillScale = 38; s.cliff = 0.82; s.coastDrop = 3;
        s.coastNoise = 0.13; s.coastScale = 46; s.undersideDepth = 46; s.undersideNoise = 0.5;
        h = Terrain.island(s);

        Box town = Box.of(72, 0, -28, 124, 0, 32);
        h.flatten(town, TOWN_Y, 8);
        // cottages and the lighthouse get level plots before the water is laid out
        List<Box> plots = new ArrayList<>();
        for (int[] p : COTTAGES) plots.add(Box.of(p[0] - 1, 0, p[1] - 1, p[0] + 7, 0, p[1] + 7));
        plots.add(Box.of(LIGHT_X - 5, 0, LIGHT_Z - 5, LIGHT_X + 5, 0, LIGHT_Z + 5));
        for (Box b : plots) { int a = h.average(b); if (a != Terrain.Heightmap.NONE) h.flatten(b, Math.max(W + 2, a), 4); }

        // ---------------------------------------------------------------- 2. water mask
        depth = new int[h.width * h.depth];
        Noise n = new Noise(Hash.salt(seed, "bayou-water"));
        Noise m = new Noise(Hash.salt(seed, "bayou-marsh"));
        h.forEach((x, z, top, bottom) -> {
            if (!h.inland(x, z, 7)) return;
            if (town.grow(3).containsXZ(x, z)) return;
            for (Box b : plots) if (b.grow(4).containsXZ(x, z)) return;
            int d = waterDepth(x, z, n, m);
            if (d > 0) depth[di(x, z)] = d;
        });
        h.forEach((x, z, top, bottom) -> { int i = di(x, z); if (depth[i] > 0) h.setTop(x, z, W - depth[i]); });
        // banks: every land column touching water sits at least one above the water line
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z)) return;
            boolean near = false;
            for (int dz = -2; dz <= 2 && !near; dz++) for (int dx = -2; dx <= 2; dx++) if (wet(x + dx, z + dz)) { near = true; break; }
            if (near && top < W + 1) h.setTop(x, z, W + 1);
            if (near && top > W + 3 && !town.grow(9).containsXZ(x, z)) h.setTop(x, z, W + 2 + (top - W - 2) / 3);
        });
        // the lagoon islet stays dry
        for (int dz = -5; dz <= 5; dz++) for (int dx = -5; dx <= 5; dx++)
            if (dx * dx + dz * dz <= 22) { int i = di(LAGOON_X + dx, LAGOON_Z + dz); if (i >= 0) { depth[i] = 0; h.setTop(LAGOON_X + dx, LAGOON_Z + dz, W + 2); } }

        // ---------------------------------------------------------------- 3. paint the ground, pour the water
        paint(c);
        h.forEach((x, z, top, bottom) -> {
            if (!wet(x, z)) return;
            for (int y = top + 1; y <= W; y++) c.set(x, y, z, "water");
            h.setWet(x, z, true);
        });
        List<int[]> vents = lavaVents(c);

        // ---------------------------------------------------------------- 4. the town
        Reservations res = new Reservations();
        int Y = TOWN_Y;
        Box plaza = Box.of(86, 0, -4, 104, 0, 12);
        res.reserve("plaza", plaza);
        Palette paving = Palette.of("mud_bricks:5,packed_mud:3,coarse_dirt:1,cobblestone:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, Y, z, paving.at(x, Y, z));
        Builders.well(c, 97, Y, 4);

        Builders.House roddy = new Builders.House(76, Y, -24, 11, 8);
        roddy.door = "south"; roddy.roof = "mangrove"; roddy.roofStyle = "gable"; roddy.chimney = true; roddy.seed = seed;
        roddy.wall = Palette.of("mangrove_planks:5,stripped_mangrove_log[axis=y]:1");
        roddy.frame = "mangrove_log"; roddy.foundation = Palette.of("mud_bricks:3,cobblestone:1");
        roddy.floor = Fill.of("spruce_planks"); roddy.doorWood = "mangrove";
        if (res.reserve("tackle_shop", roddy.reserve())) roddy.build(c);

        Builders.House shack = new Builders.House(103, Y, -23, 9, 7);
        shack.door = "south"; shack.roof = "spruce"; shack.roofStyle = "gable"; shack.seed = seed + 7;
        shack.wall = Palette.of("oak_planks:3,mossy_cobblestone:1,stripped_oak_log[axis=y]:1");
        shack.frame = "spruce_log"; shack.foundation = Palette.of("mossy_cobblestone:2,cobblestone:1");
        shack.doorWood = "spruce";
        if (res.reserve("junk_shack", shack.reserve())) shack.build(c);

        Builders.Stall stall = null;
        Box stallBox = Box.of(77, Y - 12, 14, 83, Y + 4, 19);
        if (res.reserve("bait_stall", stallBox)) stall = Builders.marketStall(c, 80, Y, 16, "north", "lime", "white", seed);

        Builders.Tower tower = new Builders.Tower(113, Y, 21);
        tower.radius = 3; tower.height = 14; tower.top = "battlements"; tower.door = "west";
        tower.wall = Palette.of("spruce_planks:4,stripped_spruce_log[axis=y]:2,mossy_cobblestone:1");
        tower.floor = Fill.of("spruce_planks"); tower.doorWood = "spruce";
        if (res.reserve("lookout", tower.reserve())) tower.build(c);

        // portal home: a mossy arch at the east end of the terrace, open to the west
        Box portalPad = Box.of(114, 0, -1, 123, 0, 9);
        res.reserve("portal", portalPad);
        Palette pad = Palette.of("mossy_stone_bricks:4,stone_bricks:3,cracked_stone_bricks:1");
        for (int x = portalPad.minX(); x <= portalPad.maxX(); x++)
            for (int z = portalPad.minZ(); z <= portalPad.maxZ(); z++) c.set(x, Y, z, pad.at(x, Y, z));
        int px = 120;
        Palette frame = Palette.of("mossy_stone_bricks:3,mossy_cobblestone:1");
        for (int y = Y + 1; y <= Y + 5; y++) { c.set(px, y, 2, frame.at(px, y, 2)); c.set(px, y, 6, frame.at(px, y, 6)); }
        for (int z = 2; z <= 6; z++) c.set(px, Y + 5, z, frame.at(px, Y + 5, z));
        c.set(px, Y + 6, 4, "mossy_stone_brick_wall");
        c.set(px, Y + 7, 4, Blocks.lantern(false));
        for (int z = 3; z <= 5; z++) for (int y = Y + 1; y <= Y + 4; y++) c.set(px, y, z, "nether_portal[axis=z]");
        Builders.wallBanner(c, px - 1, Y + 4, 2, "green", "west");
        Builders.wallBanner(c, px - 1, Y + 4, 6, "green", "west");
        Builders.lampPost(c, 116, Y, 1, "post", "west");
        Builders.lampPost(c, 116, Y, 7, "post", "west");
        layout.area("portal.hub", Box.of(px, Y + 1, 3, px, Y + 4, 5));

        // town dressing
        Box hall = Box.of(87, 0, 17, 103, 0, 27);
        if (res.reserve("market_hall", hall)) SwampKit.marketHall(c, 88, 18, 102, 26, Y);
        SwampKit.dryingRack(c, 74, Y, 27);
        SwampKit.dryingRack(c, 98, Y, -10);
        Builders.crates(c, 106, Y, 11, seed);
        Builders.campfire(c, 80, Y, 3);
        Builders.bench(c, 78, Y, 6, "north", 3, "spruce");
        c.set(105, Y + 1, 30, "hay_block[axis=y]");      // a hay stack you can climb
        c.set(106, Y + 1, 30, "hay_block[axis=y]");
        c.set(106, Y + 2, 30, "hay_block[axis=y]");
        c.set(108, Y + 1, 9, "composter");
        c.set(88, Y + 1, -3, "barrel[facing=up,open=false]");
        c.set(88, Y + 2, -3, "barrel[facing=up,open=false]");
        for (int[] lp : new int[][]{{86, -4}, {104, -4}, {86, 12}, {104, 12}})
            Builders.lampPost(c, lp[0], Y, lp[1], "street", lp[0] < 95 ? "east" : "west");

        // ---------------------------------------------------------------- 5. dock, boardwalk, fishing platform
        int shoreX = 72;
        for (int x = 72; x > 20; x--) if (wet(x, 3)) { shoreX = x + 1; break; }
        int dockW = shoreX - 17, dockE = shoreX;
        Palette deck = Palette.of("spruce_planks:5,oak_planks:2,stripped_spruce_wood[axis=y]:1");
        for (int x = dockW; x <= dockE; x++)
            for (int z = DOCK_N; z <= DOCK_S; z++) {
                if (!wet(x, z) && h.top(x, z) >= DECK) continue;
                c.set(x, DECK, z, deck.at(x, DECK, z));
                for (int a = 1; a <= 3; a++) if (Builders.natural(c.get(x, DECK + a, z))) c.set(x, DECK + a, z, "air");
                boolean edge = z == DOCK_N || z == DOCK_S || x == dockW;
                if (edge && (x - dockW) % 4 == 0) SwampKit.stilt(c, x, DECK, z, "spruce_log");
                boolean boardwalkJoin = x == dockW && Math.abs(z - BW_Z) <= 1;
                boolean ladder = x == dockW && z == 8;
                if (edge && !boardwalkJoin && !ladder) {
                    c.set(x, DECK + 1, z, RAIL);
                    // lanterns on the corner posts and every 8 along the sides, none along the west
                    // (fishing) edge: at eye height they are what a flat cast hits
                    boolean lamp = x == dockW ? z == DOCK_N || z == DOCK_S : (x - dockW) % 8 == 4;
                    if (lamp) c.set(x, DECK + 2, z, Blocks.lantern(false));
                }
            }
        // a ladder down the west face (the stilts under that edge are a solid log wall) so anyone who
        // goes over the low rail can climb back out instead of swimming for the bank
        for (int y = W - 1; y <= DECK; y++)
            if (BlockInfo.of(c.get(dockW, y, 8)).cube)
                c.set(dockW - 1, y, 8, "ladder[facing=west,waterlogged=" + (y <= W) + "]");
        res.reserve("dock", Box.of(dockW - 1, 0, DOCK_N - 1, dockE, 0, DOCK_S + 1));
        c.set(dockW + 3, DECK + 1, 7, "barrel[facing=up,open=false]");
        c.set(dockW + 4, DECK + 1, 7, "barrel[facing=up,open=false]");
        Builders.bench(c, dockW + 7, DECK, -2, "south", 3, "spruce");

        int westX = -40;
        for (int x = -10; x > -90; x--) if (!wet(x, BW_Z) && h.has(x, BW_Z)) { westX = x; break; }
        int westY = Math.max(W + 1, h.top(westX, BW_Z));
        Builders.bridge(c, dockW, DECK, BW_Z, westX, westY, BW_Z, 3, deck, RAIL, "spruce_log", "spruce_stairs");
        for (int x = westX + 2; x < dockW; x += 6) { SwampKit.stilt(c, x, DECK, BW_Z - 2, "spruce_log"); SwampKit.stilt(c, x, DECK, BW_Z + 2, "spruce_log"); }
        // halfway fishing platform with a roof (low rails all round, so it fishes off every side)
        int midX = (dockW + westX) / 2;
        for (int x = midX - 5; x <= midX + 5; x++)
            for (int z = BW_Z - 6; z <= BW_Z + 6; z++) {
                c.set(x, DECK, z, deck.at(x, DECK, z));
                for (int a = 1; a <= 4; a++) c.set(x, DECK + a, z, "air");
                boolean zEdge = z == BW_Z - 6 || z == BW_Z + 6, xEdge = x == midX - 5 || x == midX + 5;
                boolean opening = xEdge && Math.abs(z - BW_Z) <= 1;
                if ((zEdge || xEdge) && !opening) c.set(x, DECK + 1, z, RAIL);
                if (zEdge && xEdge) SwampKit.stilt(c, x, DECK, z, "spruce_log");
            }
        SwampKit.gazebo(c, midX, DECK, BW_Z - 4, "spruce");
        c.set(midX + 4, DECK + 1, BW_Z + 5, "barrel[facing=up,open=false]");
        Builders.bench(c, midX - 3, DECK, BW_Z + 5, "north", 2, "spruce");
        res.reserve("boardwalk_w", Box.of(westX, 0, BW_Z - 3, midX - 7, 0, BW_Z + 3));
        res.reserve("boardwalk_e", Box.of(midX + 7, 0, BW_Z - 3, dockW - 2, 0, BW_Z + 3));
        res.reserve("platform", Box.of(midX - 6, 0, BW_Z - 7, midX + 6, 0, BW_Z + 7));

        // lagoon: islet with a gazebo, reached by a footbridge from the south shore
        int lagoonShoreZ = LAGOON_Z + 26;
        for (int z = LAGOON_Z + 6; z < LAGOON_Z + 50; z++) if (!wet(LAGOON_X, z)) { lagoonShoreZ = z; if (!wet(LAGOON_X, z + 1)) break; }
        Builders.bridge(c, LAGOON_X, Math.max(W + 1, h.top(LAGOON_X, lagoonShoreZ)), lagoonShoreZ, LAGOON_X, W + 2, LAGOON_Z + 4, 3,
                Palette.of("cherry_planks:4,birch_planks:1"), "cherry_fence", "cherry_log", "cherry_stairs");
        for (int z = LAGOON_Z + 6; z < lagoonShoreZ; z += 5) { SwampKit.stilt(c, LAGOON_X - 1, W + 2, z, "cherry_log"); SwampKit.stilt(c, LAGOON_X + 1, W + 2, z, "cherry_log"); }
        SwampKit.gazebo(c, LAGOON_X, W + 2, LAGOON_Z, "cherry");
        res.reserve("islet", Box.of(LAGOON_X - 6, 0, LAGOON_Z - 6, LAGOON_X + 6, 0, LAGOON_Z + 6));
        res.reserve("islet_bridge", Box.of(LAGOON_X - 2, 0, LAGOON_Z + 7, LAGOON_X + 2, 0, lagoonShoreZ));

        // cottages and the lighthouse on their plots
        String[] roofs = {"spruce", "mangrove", "dark_oak", "oak"};
        String[] walls = {"spruce_planks:4,stripped_spruce_log[axis=y]:1", "mangrove_planks:4,mud_bricks:1",
                "oak_planks:3,mossy_cobblestone:1", "birch_planks:3,stripped_birch_log[axis=y]:1"};
        List<Builders.House> cottages = new ArrayList<>();
        for (int i = 0; i < COTTAGES.length; i++) {
            int[] p = COTTAGES[i];
            int gy = h.top(p[0] + 3, p[1] + 3);
            if (gy == Terrain.Heightmap.NONE) continue;
            Builders.House hs = new Builders.House(p[0], gy, p[1], 7, 6);
            hs.door = p[2] == 0 ? "south" : "north"; hs.roof = roofs[i % 4]; hs.roofStyle = i % 2 == 0 ? "gable" : "hip";
            hs.wall = Palette.of(walls[i % 4]); hs.frame = "spruce_log"; hs.seed = seed + 31 * i; hs.chimney = i % 2 == 1;
            if (res.reserve("cottage" + i, hs.reserve())) { hs.build(c); cottages.add(hs); }
        }
        int[] lightTop = null;
        int lgy = h.top(LIGHT_X, LIGHT_Z);
        if (lgy != Terrain.Heightmap.NONE && res.reserve("lighthouse", Box.of(LIGHT_X - 5, 0, LIGHT_Z - 5, LIGHT_X + 5, 0, LIGHT_Z + 5)))
            lightTop = SwampKit.lighthouse(c, LIGHT_X, lgy, LIGHT_Z, 22);
        // boats tied up along the dock
        for (int[] b : new int[][]{{dockW + 4, DOCK_N - 3}, {dockW + 10, DOCK_N - 3}, {dockW + 7, DOCK_S + 3}})
            if (wet(b[0], b[1]) && wet(b[0], b[1] - 2) && wet(b[0], b[1] + 2)) SwampKit.boat(c, b[0], W, b[1], "east");

        // ---------------------------------------------------------------- 6. paths
        Palette walk = Palette.of("dirt_path:6,coarse_dirt:2,mud:1,packed_mud:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{90, -4}, {roddy.doorStep[0], -10}, {roddy.doorStep[0], roddy.doorStep[2] + 1}}, 3, walk, "spruce_slab", null);
        Builders.path(c, new int[][]{{100, -4}, {shack.doorStep[0], -10}, {shack.doorStep[0], shack.doorStep[2] + 1}}, 3, walk, "spruce_slab", paths);
        if (stall != null) Builders.path(c, new int[][]{{88, 12}, {stall.customer()[0], stall.customer()[2]}}, 3, walk, "spruce_slab", paths);
        Builders.path(c, new int[][]{{104, 10}, {tower.doorStep[0] - 3, tower.doorStep[2]}, {tower.doorStep[0], tower.doorStep[2]}}, 3, walk, "spruce_slab", paths);
        Builders.path(c, new int[][]{{104, 4}, {114, 4}}, 3, walk, "spruce_slab", paths);
        Builders.path(c, new int[][]{{86, 3}, {shoreX + 1, 3}}, 3, walk, "spruce_slab", paths);
        Builders.path(c, new int[][]{{96, -5}, {92, -30}, {70, -42}, {40, -50}, {12, -52}, {LAGOON_X, lagoonShoreZ + 1}}, 3, walk, "spruce_slab", paths);
        Builders.path(c, new int[][]{{96, 13}, {92, 34}, {76, 60}, {40, 70}, {0, 72}, {-36, 66}, {-56, 44}, {-62, 18}}, 3, walk, "spruce_slab", paths);
        Builders.path(c, new int[][]{{westX - 1, BW_Z}, {westX - 8, BW_Z + 2}, {-58, 8}, {-62, 18}}, 3, walk, "spruce_slab", paths);
        int[][] links = {{57, -44}, {79, -40}, {55, 69}, {93, 44}};
        for (int i = 0; i < cottages.size(); i++) {
            Builders.House hs = cottages.get(i);
            int[] to = links[Math.min(i, links.length - 1)];
            int dz = hs.door.equals("south") ? 2 : -2;
            Builders.path(c, new int[][]{{hs.doorStep[0], hs.doorStep[2] + dz}, {to[0], to[1]}}, 2, walk, "spruce_slab", paths);
        }
        if (lightTop != null) Builders.path(c, new int[][]{{LIGHT_X, LIGHT_Z + 6}, {104, -40}, {94, -28}}, 2, walk, "spruce_slab", paths);

        // ---------------------------------------------------------------- 7. set pieces
        int[] wreckAt = null;
        int wx = 34, wz = 34;
        if (wet(wx, wz) && h.top(wx, wz) <= W - 3) wreckAt = SwampKit.wreck(c, wx, h.top(wx, wz), wz, "west");
        int[] rockTop = rockIslet(c, -18, 34);
        int[] ruinIn = null;
        int rx = -122, rz = 30;
        int rg = h.top(rx + 3, rz + 3);
        if (rg != Terrain.Heightmap.NONE && !wet(rx + 3, rz + 3)) {
            ruinIn = SwampKit.ruin(c, rx, rg, rz, seed);
            res.reserve("ruin", Box.of(rx - 1, 0, rz - 1, rx + 8, 0, rz + 7));
        }
        int[] logIn = null;
        int lx = 20, lz = 92;
        if (h.has(lx + 4, lz) && !wet(lx + 4, lz)) {
            int lg = Math.max(h.top(lx, lz), h.top(lx + 8, lz));
            for (int x = lx; x < lx + 9; x++) for (int dz = -1; dz <= 1; dz++) {
                if (h.top(x, lz + dz) < lg) Builders.footing(c, x, lg + 1, lz + dz, Fill.of("dirt"), 4);
                c.set(x, lg, lz + dz, "grass_block");
            }
            logIn = SwampKit.hollowLog(c, lx, lg, lz, 9);
            res.reserve("hollow_log", Box.of(lx - 1, 0, lz - 2, lx + 9, 0, lz + 2));
        }
        // the Moon Pool shrine (a stone ring in the middle of the round marsh pool)
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int x = MOON_X + dx, z = MOON_Z + dz;
            for (int y = h.top(x, z) + 1; y <= W; y++) c.set(x, y, z, "mossy_stone_bricks");
            c.set(x, W + 1, z, dx == 0 && dz == 0 ? "chiseled_stone_bricks" : Blocks.slab("mossy_stone_brick", "bottom"));
        }
        c.set(MOON_X, W + 2, MOON_Z, Blocks.soulLantern(false));

        // ---------------------------------------------------------------- 8. plants
        Box isle = h.box();
        Box bayouLand = Box.of(-48, 0, isle.minZ(), 70, 0, isle.maxZ());
        Trees.forest(c, h, bayouLand, seed, 0.022, 7, "oak:6,dark_oak:3,big_oak:1,dead:1", 3,
                (x, z) -> res.covers(x, z, 6) || paths.near(x, z, 3) || nearWater(x, z, 1) || lagoonArea(x, z));
        Trees.forest(c, h, Box.of(-60, 0, -125, 70, 0, -40), seed + 3, 0.02, 6, "cherry:4,azalea:2,birch:1", 3,
                (x, z) -> res.covers(x, z, 6) || paths.near(x, z, 3) || nearWater(x, z, 1) || !lagoonArea(x, z));
        Trees.forest(c, h, Box.of(72, 0, isle.minZ(), isle.maxX(), 0, isle.maxZ()), seed + 5, 0.02, 7, "oak:4,dark_oak:2,birch:1", 4,
                (x, z) -> res.covers(x, z, 7) || paths.near(x, z, 3) || town.grow(2).containsXZ(x, z));
        List<int[]> mangroves = new ArrayList<>();
        for (int z = isle.minZ(); z <= isle.maxZ(); z++)
            for (int x = isle.minX(); x <= -54; x++) {
                if (!Hash.chance(Hash.salt(seed, "mangrove"), x, z, 0.012)) continue;
                if (!h.has(x, z) || !h.inland(x, z, 5) || res.covers(x, z, 5) || paths.near(x, z, 4) || nearVent(vents, x, z, 6)) continue;
                if (Math.hypot(x - MOON_X, z - MOON_Z) < 13) continue;
                boolean close = false;
                for (int[] p : mangroves) if (Math.abs(p[0] - x) < 9 && Math.abs(p[1] - z) < 9) { close = true; break; }
                if (close) continue;
                SwampKit.mangrove(c, x, h.top(x, z), z, seed, 0.9 + Hash.unit(seed, x, 5, z) * 0.5);
                mangroves.add(new int[]{x, z});
            }
        // a big mangrove with a root cage worth looking inside
        int bigX = -104, bigZ = -48;
        if (h.has(bigX, bigZ)) { SwampKit.mangrove(c, bigX, h.top(bigX, bigZ), bigZ, seed + 99, 1.5); mangroves.add(new int[]{bigX, bigZ}); }
        SwampKit.vines(c, new Box(-48, W - 2, isle.minZ(), isle.maxX(), W + 30, isle.maxZ()), seed, 0.08);

        groundCover(c, res, paths);
        int[][] hotspots = {{30, 28}, {-6, -18}, {-24, -84}, {10, -72}, {MOON_X + 5, MOON_Z + 5}, {-116, -22}};
        waterPlants(c, hotspots);

        // marsh lights
        for (int[] p : new int[][]{{-70, 10}, {-84, -22}, {-110, 4}, {-96, 34}, {-128, -40}, {-76, 52}, {-118, 60}}) {
            int g = h.top(p[0], p[1]);
            if (g == Terrain.Heightmap.NONE || wet(p[0], p[1]) || !c.isAir(p[0], g + 1, p[1])) continue;
            SwampKit.postLantern(c, p[0], g, p[1], true);
        }
        // lamps along the long paths
        for (int[] p : new int[][]{{80, -38}, {50, -48}, {20, -54}, {86, 44}, {58, 66}, {20, 73}, {-18, 72}, {-48, 58}, {-62, 30}}) {
            int x = p[0], z = p[1] + 3;
            int g = h.top(x, z);
            if (g == Terrain.Heightmap.NONE || wet(x, z) || paths.covers(x, z) || !c.isAir(x, g + 1, z)) continue;
            Builders.lampPost(c, x, g, z, "post", "north");
        }

        // ---------------------------------------------------------------- 9. anchors
        layout.standAt("spawn", 89, Y, 4, 90f);
        // where the straight walk west from spawn ends: at the dock's low rail, open water ahead
        layout.standAt("fishing.spot.docks", dockW + 1, DECK, 3, 90f);
        layout.standAt("npc.roddy", roddy.inside[0], roddy.inside[1], roddy.inside[2], 180f);
        layout.standAt("npc.scrapper", shack.inside[0], shack.inside[1], shack.inside[2], 180f);
        if (stall != null) layout.standAt("npc.bait_shop", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 180f);
        layout.standAt("npc.warden", tower.doorStep[0] - 2, tower.doorStep[1], tower.doorStep[2] - 2, 90f);

        for (int i = 0; i < hotspots.length; i++) layout.standAt("fishing.hotspot." + (i + 1), hotspots[i][0], W - 1, hotspots[i][1], 0f);
        layout.data("walk.ignore", "fishing.hotspot.");

        List<int[]> souls = new ArrayList<>();
        if (tower.platform != null) souls.add(tower.platform);
        souls.add(new int[]{dockW + 6, h.top(dockW + 6, 0), 0});                         // under the dock
        if (lightTop != null) souls.add(lightTop);                                           // the lighthouse gallery
        souls.add(new int[]{LAGOON_X + 1, W + 2, LAGOON_Z + 1});                             // lagoon gazebo
        souls.add(new int[]{midX + 4, DECK + 1, BW_Z + 5});                                         // on the platform barrel
        int[] bigRoots = groundNear(bigX + 4, bigZ + 4);
        if (bigRoots != null) souls.add(bigRoots);                                           // at the big mangrove's roots
        if (!vents.isEmpty()) souls.add(new int[]{vents.get(0)[0] + 3, vents.get(0)[1], vents.get(0)[2]});
        if (ruinIn != null) souls.add(new int[]{ruinIn[0] + 1, ruinIn[1], ruinIn[2] + 1});
        if (logIn != null) souls.add(logIn);
        if (wreckAt != null) souls.add(new int[]{wreckAt[0], wreckAt[1], wreckAt[2]});
        if (rockTop != null) souls.add(rockTop);
        int[] ledge = ledge(-96, 92);
        if (ledge != null) souls.add(ledge);
        souls.add(new int[]{80, Y, 21});                                                     // behind the bait stall
        int[] cherry = groundNear(34, -96);
        if (cherry != null) souls.add(cherry);
        souls.add(new int[]{106, Y + 2, 30});                                                // on the hay stack
        souls.add(new int[]{MOON_X, W + 1, MOON_Z + 1});                                     // the Moon Pool shrine
        int[] nw = groundNear(-60, -100);
        if (nw != null) souls.add(nw);
        int[] se = groundNear(110, 70);
        if (se != null) souls.add(se);
        for (int i = 0; i < souls.size(); i++) {
            int[] p = souls.get(i);
            layout.standAt("fairy_soul." + (i + 1), p[0], p[1], p[2], 0f);
        }

        // ---------------------------------------------------------------- 10. zones & areas
        layout.zone("backwater_bayou", "Backwater Bayou", 'a', new Box(isle.minX(), 0, isle.minZ(), isle.maxX(), 255, isle.maxZ()), null);
        layout.zone("bayou_waters", "Bayou Waters", '3', Box.of(BAYOU_X - 60, W - 10, BAYOU_Z - 50, BAYOU_X + 58, W + 24, BAYOU_Z + 50), null);
        layout.zone("bayou_docks", "Bayou Docks", 'e', Box.of(town.minX() - 2, Y - 8, town.minZ(), town.maxX() + 6, Y + 40, town.maxZ()), null, Layout.Zone.SAFE);
        layout.zone("moonlit_marsh", "Moonlit Marsh", '5', Box.of(isle.minX(), W - 12, isle.minZ(), -50, W + 40, isle.maxZ()), null);
        layout.zone("lotus_lagoon", "Lotus Lagoon", 'd', Box.of(LAGOON_X - 40, W - 10, LAGOON_Z - 32, LAGOON_X + 40, W + 30, LAGOON_Z + 30), null);
        layout.area("bayou_water", Box.of(BAYOU_X - 50, W, BAYOU_Z - 40, BAYOU_X + 50, W, BAYOU_Z + 40));
        layout.area("lagoon_water", Box.of(LAGOON_X - 26, W, LAGOON_Z - 18, LAGOON_X + 26, W, LAGOON_Z + 18));
        layout.area("marsh_water", Box.of(MOON_X - 8, W, MOON_Z - 8, MOON_X + 8, W, MOON_Z + 8));
        layout.area("town", new Box(town.minX(), Y + 1, town.minZ(), town.maxX(), Y + 4, town.maxZ()));
        layout.data("reservations", res.summary());
    }

    // =================================================================================== helpers

    private int waterDepth(int x, int z, Noise n, Noise m) {
        int best = 0;
        double wob = 1 + 0.2 * n.fbm2(x / 30.0, z / 30.0, 3);
        double db = Math.sqrt(sq((x - BAYOU_X) / BAYOU_RX) + sq((z - BAYOU_Z) / BAYOU_RZ)) / wob;
        if (db < 1) best = Math.max(best, 1 + (int) Math.round(5 * (1 - db * db)));
        double dl = Math.sqrt(sq((x - LAGOON_X) / LAGOON_RX) + sq((z - LAGOON_Z) / LAGOON_RZ)) / (1 + 0.12 * n.fbm2(x / 20.0, z / 20.0, 2));
        if (dl < 1) best = Math.max(best, 2 + (int) Math.round(4 * (1 - dl * dl)));
        if (x < -54) {
            double v = m.fbm2(x / 15.0, z / 15.0, 3);
            if (v > 0.16) best = Math.max(best, v > 0.4 ? 2 : 1);
            double dm = Math.hypot(x - MOON_X, z - MOON_Z) / (9 * (1 + 0.1 * n.noise2(x / 5.0, z / 5.0)));
            if (dm < 1) best = Math.max(best, 1 + (int) Math.round(3 * (1 - dm * dm)));
            double dp = Math.hypot(x + 116, z + 22) / 7.0;
            if (dp < 1) best = Math.max(best, 1 + (int) Math.round(2 * (1 - dp * dp)));
        }
        return best;
    }

    private static double sq(double v) { return v * v; }

    /** Slope against dry neighbours only, so a bank next to the water is not painted as a cliff. */
    private int drySlope(int x, int z) {
        int t = h.top(x, z), m = 0;
        for (int d = 0; d < 4; d++) {
            int nx = x + Blocks.DX[d], nz = z + Blocks.DZ[d];
            if (!h.has(nx, nz)) return 99;
            if (wet(nx, nz)) continue;
            m = Math.max(m, Math.abs(h.top(nx, nz) - t));
        }
        return m;
    }

    private boolean nearWater(int x, int z, int r) {
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) if (wet(x + dx, z + dz)) return true;
        return false;
    }

    private static boolean lagoonArea(int x, int z) {
        return Math.hypot((x - LAGOON_X) / 1.2, z - LAGOON_Z) < 46 && z < -40;
    }

    private static boolean nearVent(List<int[]> vents, int x, int z, int r) {
        for (int[] v : vents) if (Math.abs(v[0] - x) <= r && Math.abs(v[2] - z) <= r) return true;
        return false;
    }

    /** Paint every column: wet beds, marsh mud, lagoon sand, grass, cliffs, stone, the hanging underside. */
    private void paint(VoxelCanvas c) {
        Palette bedBayou = Palette.of("mud:4,clay:2,dirt:2,gravel:1");
        Palette bedLagoon = Palette.of("sand:4,clay:2,gravel:1");
        Palette bedMarsh = Palette.of("mud:5,clay:1,muddy_mangrove_roots:1");
        Palette marsh = Palette.of("mud:4,grass_block:3,podzol:2,moss_block:1,coarse_dirt:1");
        Palette bank = Palette.of("mud:2,grass_block:3,coarse_dirt:1");
        Palette grass = Palette.of("grass_block:10,coarse_dirt:1,podzol:1,moss_block:1");
        Palette lagoonShore = Palette.of("sand:3,grass_block:1");
        Palette cliff = Palette.of("stone:5,mossy_cobblestone:3,andesite:2,tuff:1");
        Palette stone = Palette.of("stone:12,andesite:3,tuff:2,gravel:1");
        Palette under = Palette.of("stone:6,andesite:3,tuff:2,mud:1,dirt:1");
        h.forEach((x, z, top, bottom) -> {
            boolean wetCol = wet(x, z);
            boolean marshy = x < -50;
            boolean lagoon = lagoonArea(x, z);
            int slope = wetCol ? 0 : drySlope(x, z);
            String surface;
            if (wetCol) surface = (marshy ? bedMarsh : lagoon ? bedLagoon : bedBayou).at(x, top, z);
            else if (slope >= 3) surface = cliff.at(x, top, z);
            else if (marshy) surface = marsh.at(x, top, z);
            else if (lagoon && top <= W + 2) surface = lagoonShore.at(x, top, z);
            else if (top <= W + 1) surface = bank.at(x, top, z);
            else surface = grass.at(x, top, z);
            for (int y = bottom; y <= top; y++) {
                int d = top - y;
                String b;
                if (y - bottom < 6 && d > 3) b = under.at(x, y, z);
                else if (d == 0) b = surface;
                else if (d <= 3) b = marshy || wetCol ? (d == 1 ? "mud" : "dirt") : (slope >= 3 ? cliff.at(x, y, z) : "dirt");
                else b = stone.at(x, y, z);
                c.set(x, y, z, b);
            }
        });
    }

    /** Three lava vents in the marsh: 3x3 lava pools in basalt rims, sunk into the ground. */
    private List<int[]> lavaVents(VoxelCanvas c) {
        List<int[]> out = new ArrayList<>();
        Palette rim = Palette.of("basalt[axis=y]:3,magma_block:2,blackstone:1");
        for (int[] v : new int[][]{{-108, -30}, {-72, 40}, {-130, 12}}) {
            int vx = v[0], vz = v[1];
            boolean ok = true;
            int g = h.top(vx, vz);
            for (int dz = -3; dz <= 3 && ok; dz++) for (int dx = -3; dx <= 3; dx++)
                if (!h.inland(vx + dx, vz + dz, 3) || wet(vx + dx, vz + dz) || Math.abs(h.top(vx + dx, vz + dz) - g) > 2) { ok = false; break; }
            if (!ok) continue;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                int x = vx + dx, z = vz + dz;
                boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                for (int y = h.top(x, z) + 1; y <= g + 3; y++) c.set(x, y, z, "air");
                if (inner) {
                    c.set(x, g - 2, z, "magma_block");
                    c.set(x, g - 1, z, "lava");
                    c.set(x, g, z, "lava");
                    h.setTop(x, z, g);
                    h.setWet(x, z, true);
                } else {
                    c.set(x, g, z, rim.at(x, g, z));
                    c.set(x, g - 1, z, "blackstone");
                    h.setTop(x, z, g);
                }
            }
            out.add(new int[]{vx, g, vz});
        }
        return out;
    }

    /** A pile of mossy rocks rising out of the water. Returns the block to stand on at its top. */
    private int[] rockIslet(VoxelCanvas c, int x, int z) {
        if (!wet(x, z)) return null;
        int bed = h.top(x, z);
        Palette rock = Palette.of("mossy_cobblestone:3,stone:3,andesite:2,mossy_stone_bricks:1");
        Shapes.ellipsoid(c, x, bed + 1, z, 3.2, (W + 2 - bed), 2.6, rock, 0);
        for (int y = bed + 1; y <= W + 1; y++) c.set(x + 3, y, z, rock.at(x + 3, y, z));   // a step up out of the water
        int top = c.topY(x, z, bed, W + 10);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            int t = c.topY(x + dx, z + dz, bed, W + 10);
            if (t > W && BlockState.name(c.get(x + dx, t, z + dz)).contains("cobblestone") && c.isAir(x + dx, t + 1, z + dz)
                    && Hash.chance(seed, x + dx, z + dz, 0.4)) c.set(x + dx, t + 1, z + dz, "moss_carpet");
        }
        c.set(x, top + 1, z, "air");
        return new int[]{x, top, z};
    }

    /** A dry land block near (x, z), searching outward. */
    private int[] groundNear(int x, int z) {
        for (int r = 0; r < 10; r++)
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx, zz = z + dz;
                if (!h.has(xx, zz) || wet(xx, zz) || !h.inland(xx, zz, 2) || h.slope(xx, zz) > 1) continue;
                return new int[]{xx, h.top(xx, zz), zz};
            }
        return null;
    }

    /** A grassy spot right at the coast near (x, z) - a cliff-edge hiding place. */
    private int[] ledge(int x, int z) {
        for (int r = 0; r < 20; r++)
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx, zz = z + dz;
                if (!h.has(xx, zz) || wet(xx, zz) || h.inland(xx, zz, 2) || !h.inland(xx, zz, 1)) continue;
                if (h.slope(xx, zz) > 2) continue;
                return new int[]{xx, h.top(xx, zz), zz};
            }
        return null;
    }

    private void groundCover(VoxelCanvas c, Reservations res, Builders.PathResult paths) {
        long s = Hash.salt(seed, "cover");
        Palette marshPlants = Palette.of("short_grass:20,fern:6,blue_orchid:3,brown_mushroom:2,red_mushroom:1,firefly_bush:2,moss_carpet:5,bush:2");
        Palette landPlants = Palette.of("short_grass:24,fern:3,blue_orchid:2,lily_of_the_valley:1,firefly_bush:1,bush:3,dandelion:1");
        Palette lagoonPlants = Palette.of("short_grass:16,pink_petals[facing=north,flower_amount=4]:6,lily_of_the_valley:2,azure_bluet:2,wildflowers[facing=east,flower_amount=3]:2");
        h.forEach((x, z, top, bottom) -> {
            if (wet(x, z) || paths.covers(x, z) || res.covers(x, z, 0)) return;
            if (!c.isAir(x, top + 1, z)) return;
            String ground = BlockState.name(c.get(x, top, z));
            if (!(ground.equals("grass_block") || ground.equals("mud") || ground.equals("podzol") || ground.equals("moss_block")
                    || ground.equals("coarse_dirt") || ground.equals("sand"))) return;
            boolean shore = top == W + 1 && nearWater(x, z, 1);
            double u = Hash.unit(s, x, z);
            if (shore && u < 0.16 && !ground.equals("sand")) {
                int hgt = 2 + (int) (Hash.unit(s, x, 9, z) * 2);
                for (int i = 1; i <= hgt; i++) c.set(x, top + i, z, "sugar_cane");
                return;
            }
            boolean marsh = x < -50;
            boolean lagoon = lagoonArea(x, z);
            double chance = marsh ? 0.42 : lagoon ? 0.32 : 0.30;
            if (u >= chance) return;
            Palette p = marsh ? marshPlants : lagoon ? lagoonPlants : landPlants;
            String plant = p.pick(s, x, top, z);
            if (ground.equals("sand") && !plant.startsWith("short_grass")) return;
            c.set(x, top + 1, z, plant);
        });
    }

    private void waterPlants(VoxelCanvas c, int[][] hotspots) {
        long s = Hash.salt(seed, "water-plants");
        h.forEach((x, z, top, bottom) -> {
            if (!wet(x, z)) return;
            int d = W - top;
            boolean marsh = x < -50;
            boolean lagoon = lagoonArea(x, z);
            boolean clearOfHotspots = true;
            for (int[] hs : hotspots) if (Math.abs(hs[0] - x) <= 5 && Math.abs(hs[1] - z) <= 5) { clearOfHotspots = false; break; }
            double u = Hash.unit(s, x, z);
            double pads = lagoon ? 0.14 : marsh ? 0.09 : 0.05;
            if (clearOfHotspots && d <= 4 && u < pads && c.isAir(x, W + 1, z) && !SwampKit.water(c, x, W + 1, z))
                c.set(x, W + 1, z, "lily_pad");
            if (d >= 2 && SwampKit.water(c, x, top + 1, z)) {
                double v = Hash.unit(s, x, 3, z);
                if (v < (lagoon ? 0.22 : 0.3)) c.set(x, top + 1, z, "seagrass");
                else if (lagoon && v < 0.27) c.set(x, top + 1, z, "sea_pickle[pickles=3,waterlogged=true]");
                else if (!lagoon && d >= 3 && v < 0.33 && SwampKit.water(c, x, top + 2, z)) {
                    c.set(x, top + 1, z, "tall_seagrass[half=lower]");
                    c.set(x, top + 2, z, "tall_seagrass[half=upper]");
                }
            }
        });
    }
}
