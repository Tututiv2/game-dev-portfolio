package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * The Garden: one per profile (an INSTANCED island). A 5x5 grid of 96x96 plots around the Barn
 * plot. The plots start overgrown (meadow, groves, flower fields, boulders, ponds) and become the
 * player's to build in once bought at the Desk; the Barn plot holds the barn and Desk, the
 * composter shed, the Copper Market stall, the Pestwarden's hut, the greenhouse, a water tower,
 * a pond and the portal back to the Hub.
 *
 * <p>Pure Java (no Bukkit): {@code bash tools/preview.sh com.skyclone.mod.garden.GardenGenerator}.
 */
public final class GardenGenerator implements IslandGenerator {

    public static final String ISLAND = "garden";
    static final int Y = PlotGrid.GROUND_Y;

    @Override public String islandId() { return ISLAND; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(PlotGrid.MIN - 4, 30, PlotGrid.MIN - 4, PlotGrid.MAX + 4, 140, PlotGrid.MAX + 4); }

    /** Plot ids in grid order, from the data's numbering (template-only copy so the generator needs no data file). */
    static final String[][] PLOT_IDS = new String[5][5];
    static final int[][] PLOT_NUMBER = new int[5][5];

    static {
        String[] ids = {"expert_1", "advanced_4", "advanced_6", "advanced_8", "expert_3",
                "advanced_1", "intermediate_1", "beginner_2", "intermediate_3", "advanced_10",
                "advanced_2", "beginner_1", null, "beginner_4", "advanced_11",
                "advanced_3", "intermediate_2", "beginner_3", "intermediate_4", "advanced_12",
                "expert_2", "advanced_5", "advanced_7", "advanced_9", "expert_4"};
        int[] nums = {21, 13, 9, 14, 22, 15, 5, 1, 6, 16, 10, 2, 0, 3, 11, 17, 7, 4, 8, 18, 23, 19, 12, 20, 24};
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++) {
                PLOT_IDS[c][r] = ids[r * 5 + c];
                PLOT_NUMBER[c][r] = nums[r * 5 + c];
            }
    }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        Noise noise = new Noise(Hash.salt(seed, "garden"));
        base(c, seed, noise);
        paths(c, seed);
        List<int[]> souls = new ArrayList<>();
        for (int col = 0; col < 5; col++)
            for (int row = 0; row < 5; row++) {
                if (PlotGrid.isBarn(col, row)) continue;
                overgrowth(c, seed, noise, col, row);
                plotLayout(layout, col, row);
            }
        barnPlot(c, layout, seed, souls);
        rimSouls(c, souls);
        // a soul hidden in the far south-east plot, tucked behind a boulder in its corner
        int px = PlotGrid.inner(4, 4).maxX() - 3, pz = PlotGrid.inner(4, 4).maxZ() - 3;
        Shapes.ellipsoid(c, px - 2.5, Y + 1, pz - 2.5, 2.2, 1.6, 2.2, Palette.of("mossy_cobblestone:3,andesite:2,cobblestone:1"), 0);
        clearAbove(c, px, Y, pz, 3);
        c.set(px, Y, pz, "grass_block");
        souls.add(new int[]{px, Y, pz});
        for (int i = 0; i < souls.size(); i++) {
            int[] s = souls.get(i);
            layout.standAt("fairy_soul." + (i + 1), s[0], s[1], s[2], 0f);
        }
        Box all = new Box(PlotGrid.MIN, 0, PlotGrid.MIN, PlotGrid.MAX, 255, PlotGrid.MAX);
        layout.zone("garden", "The Garden", 'a', all, null);
        layout.data("plots", "5x5 of " + PlotGrid.SIZE + "; barn at 2,2");
    }

    // =====================================================================================
    // the slab of land, its rocky rim, and the path grid
    // =====================================================================================

    private void base(VoxelCanvas c, long seed, Noise noise) {
        Palette rimRock = Palette.of("stone:6,andesite:3,tuff:2,cobblestone:1,dirt:1");
        for (int z = PlotGrid.MIN; z <= PlotGrid.MAX; z++)
            for (int x = PlotGrid.MIN; x <= PlotGrid.MAX; x++) {
                int d = Math.min(Math.min(x - PlotGrid.MIN, PlotGrid.MAX - x), Math.min(z - PlotGrid.MIN, PlotGrid.MAX - z));
                c.set(x, Y, z, "grass_block");
                c.set(x, Y - 1, z, "dirt");
                c.set(x, Y - 2, z, "dirt");
                c.set(x, Y - 3, z, d < 3 ? rimRock.at(x, Y - 3, z) : "stone");
                if (d < 5) {
                    double n = noise.fbm2(x / 9.0, z / 9.0, 3) * 0.5 + 0.5;
                    int depth = (int) Math.round((5 - d) * 2.2 + n * 9);
                    for (int k = 1; k <= depth; k++) c.set(x, Y - 3 - k, z, rimRock.at(x, Y - 4 - k, z));
                    if (d == 0 && Hash.chance(seed, x, z, 0.08)) c.set(x, Y - 4 - depth, z, "hanging_roots");
                } else if (Hash.chance(Hash.salt(seed, "under"), x, z, 0.004)) {
                    // the occasional rock hanging under the island, so it looks like it floats
                    int len = 3 + (int) (Hash.unit(seed, x, 7, z) * 8);
                    for (int k = 1; k <= len; k++) c.set(x, Y - 3 - k, z, rimRock.at(x, Y - 4 - k, z));
                }
            }
    }

    private void paths(VoxelCanvas c, long seed) {
        Palette inner = Palette.of("dirt_path:7,coarse_dirt:2,gravel:1");
        Palette rim = Palette.of("gravel:4,coarse_dirt:2,dirt_path:3,cobblestone:1");
        for (int z = PlotGrid.MIN; z <= PlotGrid.MAX; z++)
            for (int x = PlotGrid.MIN; x <= PlotGrid.MAX; x++) {
                if (!PlotGrid.onPath(x, z)) continue;
                int d = Math.min(Math.min(x - PlotGrid.MIN, PlotGrid.MAX - x), Math.min(z - PlotGrid.MIN, PlotGrid.MAX - z));
                c.set(x, Y, z, d < PlotGrid.BORDER ? rim.at(x, Y, z) : inner.at(x, Y, z));
                if (d == 0) c.set(x, Y + 1, z, "spruce_fence");
            }
        // lamps: at each inner grid crossing and halfway along every path segment
        for (int i = 1; i < 5; i++) {
            int b = PlotGrid.MIN + i * PlotGrid.SIZE;            // first block east/south of a boundary
            for (int j = 0; j <= 5; j++) {
                int a = PlotGrid.MIN + j * PlotGrid.SIZE;
                if (j > 0 && j < 5) {
                    lamp(c, b - 2, a - 2);                       // crossing, NW quarter of the 4x4 square
                }
                if (j < 5) {
                    lamp(c, b - 2, a + PlotGrid.SIZE / 2);       // along a north-south path
                    lamp(c, a + PlotGrid.SIZE / 2, b - 2);       // along an east-west path
                }
            }
        }
        // rim lamps every 48 blocks, on the inner edge of the rim path
        for (int t = PlotGrid.MIN + 24; t < PlotGrid.MAX; t += 48) {
            lamp(c, t, PlotGrid.MIN + 1);
            lamp(c, t, PlotGrid.MAX - 1);
            lamp(c, PlotGrid.MIN + 1, t);
            lamp(c, PlotGrid.MAX - 1, t);
        }
    }

    private static void lamp(VoxelCanvas c, int x, int z) {
        if (c.isAir(x, Y, z)) return;
        c.set(x, Y + 1, z, "cobblestone_wall");
        c.set(x, Y + 2, z, "spruce_fence");
        c.set(x, Y + 3, z, Blocks.lantern(false));
    }

    // =====================================================================================
    // an overgrown plot
    // =====================================================================================

    private static final String[] KINDS = {"meadow", "grove", "flowers", "orchard", "rocky", "wetland", "birches", "meadow"};

    static String kindOf(long seed, int col, int row) {
        return KINDS[(int) ((Hash.hash(Hash.salt(seed, "plotkind"), col, row) >>> 1) % KINDS.length)];
    }

    private void overgrowth(VoxelCanvas c, long seed, Noise noise, int col, int row) {
        Box in = PlotGrid.inner(col, row);
        String kind = kindOf(seed, col, row);
        long ps = Hash.salt(seed, "plot" + col + "," + row);
        int cx = PlotGrid.centerX(col), cz = PlotGrid.centerZ(row);
        String[] flowerSet = switch ((int) ((ps >>> 3) % 4)) {
            case 0 -> new String[]{"poppy", "dandelion", "oxeye_daisy"};
            case 1 -> new String[]{"cornflower", "azure_bluet", "allium"};
            case 2 -> new String[]{"red_tulip", "orange_tulip", "white_tulip", "pink_tulip"};
            default -> new String[]{"lily_of_the_valley", "blue_orchid", "dandelion", "poppy"};
        };
        double grass = switch (kind) { case "flowers" -> 0.25; case "rocky" -> 0.18; case "wetland" -> 0.40; default -> 0.33; };
        double flowers = switch (kind) { case "flowers" -> 0.14; case "meadow" -> 0.045; default -> 0.025; };
        // ponds first (wetland), so plants are not put in water
        List<int[]> ponds = new ArrayList<>();
        if (kind.equals("wetland")) {
            int n = 2 + (int) ((ps >>> 7) % 2);
            for (int i = 0; i < n; i++) {
                int px = in.minX() + 12 + (int) (Hash.unit(ps, i, 1, 2) * (in.maxX() - in.minX() - 24));
                int pz = in.minZ() + 12 + (int) (Hash.unit(ps, i, 3, 4) * (in.maxZ() - in.minZ() - 24));
                if (Math.abs(px - cx) < 10 && Math.abs(pz - cz) < 10) px += 18;
                ponds.add(new int[]{px, pz, 3 + (int) (Hash.unit(ps, i, 5, 6) * 3)});
            }
            for (int[] p : ponds) pond(c, p[0], p[1], p[2], ps);
        }
        for (int z = in.minZ(); z <= in.maxZ(); z++)
            for (int x = in.minX(); x <= in.maxX(); x++) {
                if (!"grass_block".equals(c.get(x, Y, z))) continue;
                if (Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 2) continue;      // keep the plot centre clear
                double patch = noise.fbm2(x / 11.0, z / 11.0, 2) * 0.5 + 0.5;
                double u = Hash.unit(ps, x, 1, z);
                if (u < flowers * (0.5 + patch)) {
                    c.set(x, Y + 1, z, flowerSet[(int) (Hash.unit(ps, x, 2, z) * flowerSet.length)]);
                } else if (u < flowers * (0.5 + patch) + grass * (0.4 + patch)) {
                    double v = Hash.unit(ps, x, 3, z);
                    if (v < 0.14) { c.set(x, Y + 1, z, "tall_grass[half=lower]"); c.set(x, Y + 2, z, "tall_grass[half=upper]"); }
                    else if (v < 0.22) c.set(x, Y + 1, z, "fern");
                    else if (v < 0.27) c.set(x, Y + 1, z, "bush");
                    else c.set(x, Y + 1, z, "short_grass");
                } else if (kind.equals("flowers") && patch > 0.62 && Hash.chance(ps, x, 9, z, 0.5)) {
                    c.set(x, Y + 1, z, "pink_petals[facing=north,flower_amount=" + (1 + (int) (Hash.unit(ps, x, 4, z) * 4)) + "]");
                } else if (kind.equals("grove") && patch < 0.3 && Hash.chance(ps, x, 8, z, 0.4)) {
                    c.set(x, Y + 1, z, "leaf_litter[facing=north,segment_amount=" + (1 + (int) (Hash.unit(ps, x, 4, z) * 4)) + "]");
                }
                if (kind.equals("rocky") && Hash.chance(ps, x, 5, z, 0.03)) c.set(x, Y, z, Hash.chance(ps, x, 6, z, 0.5) ? "coarse_dirt" : "moss_block");
            }
        // boulders
        int boulders = kind.equals("rocky") ? 9 : 2;
        for (int i = 0; i < boulders; i++) {
            int bx = in.minX() + 6 + (int) (Hash.unit(ps, i, 11, 12) * (in.maxX() - in.minX() - 12));
            int bz = in.minZ() + 6 + (int) (Hash.unit(ps, i, 13, 14) * (in.maxZ() - in.minZ() - 12));
            if (Math.abs(bx - cx) < 6 && Math.abs(bz - cz) < 6) continue;
            if (nearPond(ponds, bx, bz, 4)) continue;
            double r = 1.4 + Hash.unit(ps, i, 15, 16) * (kind.equals("rocky") ? 1.8 : 0.8);
            for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) clearAbove(c, bx + dx, Y, bz + dz, 2);
            Shapes.ellipsoid(c, bx + 0.5, Y + 0.6, bz + 0.5, r, r * 0.8, r * 1.1, Palette.of("mossy_cobblestone:3,andesite:3,stone:2,cobblestone:1"), 0);
        }
        // trees
        String treeMix;
        int treeCount;
        switch (kind) {
            case "grove" -> { treeMix = "oak:3,big_oak:1,dark_oak:1"; treeCount = 26; }
            case "orchard" -> { treeMix = "oak:5,azalea:2"; treeCount = 18; }
            case "birches" -> { treeMix = "birch:6,oak:1"; treeCount = 24; }
            case "flowers" -> { treeMix = "cherry:2,azalea:3"; treeCount = 7; }
            case "rocky" -> { treeMix = "spruce:3,pine:2"; treeCount = 9; }
            case "wetland" -> { treeMix = "oak:2,birch:1"; treeCount = 6; }
            default -> { treeMix = "oak:4,birch:2"; treeCount = 10; }
        }
        Palette mix = Palette.of(treeMix);
        int placed = 0;
        for (int i = 0; i < treeCount * 4 && placed < treeCount; i++) {
            int tx = in.minX() + 6 + (int) (Hash.unit(ps, i, 21, 22) * (in.maxX() - in.minX() - 12));
            int tz = in.minZ() + 6 + (int) (Hash.unit(ps, i, 23, 24) * (in.maxZ() - in.minZ() - 12));
            if (Math.abs(tx - cx) < 8 && Math.abs(tz - cz) < 8) continue;
            if (nearPond(ponds, tx, tz, 3)) continue;
            if (!"grass_block".equals(c.get(tx, Y, tz)) || !c.isAir(tx, Y + 3, tz)) continue;
            String type = mix.at(tx, Y, tz);
            clearAbove(c, tx, Y, tz, 2);
            if (Trees.grow(c, type, tx, Y, tz, Hash.hash(ps, tx, 0, tz), 0.85 + Hash.unit(ps, tx, 3, tz) * 0.4)) placed++;
        }
    }

    private static boolean nearPond(List<int[]> ponds, int x, int z, int margin) {
        for (int[] p : ponds) if (Math.hypot(x - p[0], z - p[1]) <= p[2] + margin) return true;
        return false;
    }

    private static void pond(VoxelCanvas c, int px, int pz, int r, long seed) {
        for (int dz = -r - 2; dz <= r + 2; dz++)
            for (int dx = -r - 2; dx <= r + 2; dx++) {
                double d = Math.hypot(dx, dz) + Hash.unit(seed, px + dx, 9, pz + dz) * 0.8;
                int x = px + dx, z = pz + dz;
                if (d <= r) {
                    clearAbove(c, x, Y, z, 2);
                    c.set(x, Y, z, "water");
                    c.set(x, Y - 1, z, d <= r - 1.5 ? "water" : "clay");
                    c.set(x, Y - 2, z, "clay");
                    if (d < r - 1 && Hash.chance(seed, x, 4, z, 0.07)) c.set(x, Y + 1, z, "lily_pad");
                } else if (d <= r + 1.3) {
                    c.set(x, Y, z, Hash.chance(seed, x, 5, z, 0.5) ? "mud" : "grass_block");
                    if (Hash.chance(seed, x, 6, z, 0.25) && c.isAir(x, Y + 1, z)) {
                        c.set(x, Y + 1, z, "sugar_cane[age=0]");
                        c.set(x, Y + 2, z, "sugar_cane[age=0]");
                    }
                }
            }
    }

    private static void clearAbove(VoxelCanvas c, int x, int y, int z, int h) {
        for (int k = 1; k <= h; k++) if (!c.isAir(x, y + k, z)) c.set(x, y + k, z, "air");
    }

    private void plotLayout(Layout layout, int col, int row) {
        String id = PLOT_IDS[col][row];
        int num = PLOT_NUMBER[col][row];
        Box in = PlotGrid.inner(col, row);
        layout.standAt("plot." + id, PlotGrid.centerX(col), Y, PlotGrid.centerZ(row), 0f);
        layout.area("plot." + id, new Box(in.minX(), Y + 1, in.minZ(), in.maxX(), Y + 24, in.maxZ()));
        layout.zone("garden_plot_" + id, "Plot - " + num, 'a', in, null, Layout.Zone.BUILD);
    }

    // =====================================================================================
    // the Barn plot
    // =====================================================================================

    private void barnPlot(VoxelCanvas c, Layout layout, long seed, List<int[]> souls) {
        Box in = PlotGrid.inner(2, 2);   // -46..45
        Reservations res = new Reservations();
        Palette yard = Palette.of("grass_block:12,moss_block:1");
        for (int z = in.minZ(); z <= in.maxZ(); z++)
            for (int x = in.minX(); x <= in.maxX(); x++) c.set(x, Y, z, yard.at(x, Y, z));

        // ---- barn ----
        res.reserve("barn", BarnBuilder.BOX);
        BarnBuilder.build(c, "default", seed);
        // the courtyard in front of the barn, where visitors wait
        Palette cobble = Palette.of("cobblestone:4,stone_bricks:3,mossy_cobblestone:1,andesite:1");
        Box court = Box.of(-16, Y, -13, 16, Y, -3);
        res.reserve("court", court);
        for (int z = court.minZ(); z <= court.maxZ(); z++)
            for (int x = court.minX(); x <= court.maxX(); x++) c.set(x, Y, z, cobble.at(x, Y, z));
        int[][] visitorSpots = {{-13, -6}, {-8, -9}, {-3, -6}, {3, -6}, {8, -9}, {13, -6}};
        for (int i = 0; i < visitorSpots.length; i++)
            layout.standAt("visitor." + (i + 1), visitorSpots[i][0], Y, visitorSpots[i][1], 180f);
        Builders.bench(c, -15, Y, -12, "south", 3, "spruce");
        Builders.bench(c, 13, Y, -12, "south", 3, "spruce");
        for (int[] lp : new int[][]{{-16, -3}, {16, -3}, {-16, -13}, {16, -13}}) Builders.lampPost(c, lp[0], Y, lp[1], "post", "south");

        // ---- spawn plaza with a well ----
        Box plaza = Box.of(-9, Y, 6, 9, Y, 24);
        res.reserve("plaza", plaza);
        Palette pave = Palette.of("stone_bricks:5,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) {
                double d = Math.hypot(x, (z - 15) * 1.0);
                if (d <= 9.4) c.set(x, Y, z, pave.at(x, Y, z));
            }
        Builders.well(c, 0, Y, 15);
        for (int[] lp : new int[][]{{-7, 9}, {7, 9}, {-7, 21}, {7, 21}}) Builders.lampPost(c, lp[0], Y, lp[1], "street", lp[0] < 0 ? "east" : "west");
        layout.standAt("spawn", 0, Y, 26, 180f);
        layout.zone("garden_barn", "The Garden", 'a', new Box(in.minX() - 2, Y - 8, in.minZ() - 2, in.maxX() + 2, Y + 60, in.maxZ() + 2),
                null, Layout.Zone.SAFE, Layout.Zone.NO_MOBS);

        // ---- portal back to the Hub ----
        Box portalPlot = Box.of(-6, Y, 33, 6, Y, 43);
        res.reserve("portal", portalPlot);
        Palette pf = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1");
        for (int z = 33; z <= 41; z++)
            for (int x = -5; x <= 5; x++) c.set(x, Y, z, Palette.of("polished_blackstone:3,blackstone:1").at(x, Y, z));
        int pz = 39;
        for (int yy = Y + 1; yy <= Y + 5; yy++) { c.set(-3, yy, pz, pf.at(-3, yy, pz)); c.set(3, yy, pz, pf.at(3, yy, pz)); }
        for (int x = -3; x <= 3; x++) c.set(x, Y + 6, pz, pf.at(x, Y + 6, pz));
        c.set(0, Y + 7, pz, "chiseled_polished_blackstone");
        for (int x = -2; x <= 2; x++) for (int yy = Y + 1; yy <= Y + 5; yy++) c.set(x, yy, pz, "nether_portal[axis=x]");
        Builders.lampPost(c, -5, Y, 37, "soul", "north");
        Builders.lampPost(c, 5, Y, 37, "soul", "north");
        layout.area("portal.hub", Box.of(-2, Y + 1, pz, 2, Y + 4, pz));
        souls.add(new int[]{4, Y, 41});                            // behind the portal frame

        // ---- greenhouse (west of the barn) ----
        Box gh = GreenhouseBuilder.BOX;
        res.reserve("greenhouse", gh);
        int[] botanist = GreenhouseBuilder.build(c, "default");
        c.set(GreenhouseBuilder.X1 + 1, Y, (GreenhouseBuilder.Z0 + GreenhouseBuilder.Z1) / 2, "polished_andesite");   // door step
        layout.standAt("npc.botanist", botanist[0], botanist[1], botanist[2], 90f);
        souls.add(new int[]{-45, Y, -45});                         // the nook behind the greenhouse

        // ---- water tower (east of the barn) ----
        Box wt = Box.of(19, Y, -36, 29, Y + 16, -26);
        res.reserve("watertower", wt);
        int[] top = waterTower(c, 24, -31);
        souls.add(top);

        // ---- composter shed ----
        Box shed = Box.of(22, Y, -14, 38, Y + 7, -2);
        res.reserve("shed", shed);
        int[] compStand = composterShed(c, shed);
        layout.standAt("composter", compStand[0], compStand[1], compStand[2], 0f);
        souls.add(new int[]{37, Y, -3});

        // ---- Copper Market stall ----
        Box stallBox = Box.of(-30, Y, -2, -20, Y + 6, 8);
        res.reserve("market", stallBox);
        Builders.Stall stall = Builders.marketStall(c, -25, Y, 3, "east", "orange", "white", seed);
        layout.standAt("npc.market", stall.vendor()[0], stall.vendor()[1], stall.vendor()[2], 90f);
        Builders.crates(c, -29, Y, -1, seed);
        Builders.lampPost(c, -21, Y, -1, "street", "east");
        souls.add(new int[]{-29, Y, 7});

        // ---- the Pestwarden's hut ----
        Builders.House hut = new Builders.House(24, Y, 14, 9, 7);
        hut.door = "west";
        hut.wall = Palette.of("mossy_cobblestone:2,spruce_planks:4,stripped_spruce_log:1");
        hut.frame = "dark_oak_log";
        hut.roof = "spruce";
        hut.roofStyle = "gable";
        hut.chimney = true;
        hut.seed = seed;
        if (res.reserve("hut", hut.reserve())) hut.build(c);
        layout.standAt("npc.pestwarden", hut.inside[0], hut.inside[1], hut.inside[2], 90f);
        // pest trophies outside: a cauldron and a fence pen
        c.set(23, Y + 1, 23, "cauldron");
        Builders.fenceLine(c, new int[][]{{34, 23}, {38, 23}, {38, 27}, {34, 27}}, "spruce_fence", true);

        // ---- pond (south-west) ----
        Box pondBox = Box.of(-38, Y, 16, -18, Y + 4, 34);
        res.reserve("pond", pondBox);
        pond(c, -28, 25, 6, Hash.salt(seed, "barnpond"));
        for (int x = -24; x <= -20; x++) c.set(x, Y + 1, 25, Blocks.slab("spruce", "bottom"));   // a little jetty
        souls.add(new int[]{-36, Y, 31});
        Builders.bench(c, -33, Y, 17, "south", 3, "oak");

        // ---- windmill (north-east corner) ----
        Builders.Tower mill = new Builders.Tower(38, Y, -38);
        mill.radius = 3; mill.height = 14; mill.top = "battlements"; mill.door = "west";
        mill.wall = Palette.of("white_concrete:4,calcite:2,stripped_birch_log[axis=y]:1");
        if (res.reserve("mill", mill.reserve())) {
            mill.build(c);
            sails(c, 38, Y + 11, -38 + 4);
            souls.add(new int[]{mill.platform[0], mill.platform[1], mill.platform[2]});
        }

        // ---- the barn's own souls and spots ----
        souls.add(new int[]{BarnBuilder.LOFT_SPOT[0], BarnBuilder.LOFT_SPOT[1], BarnBuilder.LOFT_SPOT[2]});
        souls.add(new int[]{5, Y, BarnBuilder.Z0 - 2});              // behind the barn, by the back door
        layout.standAt("desk", BarnBuilder.DESK_STAND[0], BarnBuilder.DESK_STAND[1], BarnBuilder.DESK_STAND[2], 180f);
        layout.standAt("npc.barn_hall", BarnBuilder.HALL_SPOT[0], BarnBuilder.HALL_SPOT[1], BarnBuilder.HALL_SPOT[2], 180f);

        // ---- hay stacks and a nook between them ----
        for (int[] hb : new int[][]{{-18, -15}, {-18, -16}, {-19, -15}, {-18, -17}, {-20, -17}, {-20, -16}})
            c.set(hb[0], Y + 1, hb[1], "hay_block[axis=y]");
        c.set(-18, Y + 2, -16, "hay_block[axis=x]");

        // ---- paths: plaza to everything, and out to the plot grid on every side ----
        Palette path = Palette.of("dirt_path:6,coarse_dirt:2,gravel:1");
        Builders.PathResult pr = Builders.path(c, new int[][]{{0, 6}, {0, -2}}, 3, path, "cobblestone_stairs", null);
        Builders.path(c, new int[][]{{0, 25}, {0, 32}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{-9, 15}, {-19, 12}, {-19, 3}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{-19, 12}, {-33, 11}, {-47, 11}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{-17, -8}, {-21, -14}, {-21, -18}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{9, 15}, {20, 12}, {20, 8}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{20, 8}, {33, 8}, {47, 8}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{21, 17}, {18, 17}, {18, 9}}, 2, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{17, -8}, {28, -8}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{17, -10}, {21, -16}, {21, -24}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{-6, 32}, {-20, 34}, {-36, 36}, {-36, 47}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{6, 32}, {20, 34}, {30, 40}, {30, 47}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{-17, -16}, {-17, -40}, {-17, -48}}, 3, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{17, -16}, {17, -24}}, 2, path, "cobblestone_stairs", pr);
        Builders.path(c, new int[][]{{17, -37}, {17, -48}}, 3, path, "cobblestone_stairs", pr);
        for (int[] lp : new int[][]{{-34, 4}, {34, 10}, {-15, -30}, {-15, -44}, {15, -42}, {-24, 36}, {24, 37}, {-2, 30}})
            Builders.lampPost(c, lp[0], Y, lp[1], "post", "south");

        // ---- flowers, bushes and a few trees on the yard ----
        Palette beds = Palette.of("poppy:2,dandelion:2,cornflower:1,oxeye_daisy:1,short_grass:6");
        for (int z = in.minZ(); z <= in.maxZ(); z++)
            for (int x = in.minX(); x <= in.maxX(); x++) {
                if (!c.get(x, Y, z).startsWith("grass_block") || !c.isAir(x, Y + 1, z)) continue;
                if (res.covers(x, z, 1) || pr.near(x, z, 1)) continue;
                if (Hash.chance(seed, x, 31, z, 0.18)) c.set(x, Y + 1, z, beds.at(x, Y + 1, z));
            }
        Palette yardTrees = Palette.of("oak:3,birch:2,cherry:1");
        List<int[]> planted = new ArrayList<>();
        for (int z = in.minZ() + 3; z <= in.maxZ() - 3; z++)
            for (int x = in.minX() + 3; x <= in.maxX() - 3; x++) {
                if (!Hash.chance(Hash.salt(seed, "yardtrees"), x, z, 0.012)) continue;
                if (res.covers(x, z, 5) || pr.near(x, z, 4) || !"grass_block".equals(c.get(x, Y, z))) continue;
                boolean near = false;
                for (int[] p : planted) if (Math.abs(p[0] - x) < 7 && Math.abs(p[1] - z) < 7) { near = true; break; }
                if (near) continue;
                clearAbove(c, x, Y, z, 2);
                Trees.grow(c, yardTrees.at(x, Y, z), x, Y, z, Hash.hash(seed, x, 5, z), 0.9);
                planted.add(new int[]{x, z});
            }
        layout.data("reservations", res.summary());
    }

    /** A wooden water tower on four legs with a ladder; returns the platform spot. */
    private int[] waterTower(VoxelCanvas c, int cx, int cz) {
        int deck = Y + 10;
        for (int[] leg : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}})
            for (int yy = Y + 1; yy < deck; yy++) c.set(cx + leg[0], yy, cz + leg[1], "stripped_spruce_log[axis=y]");
        // cross braces
        for (int t = -2; t <= 2; t++) {
            c.set(cx + t, Y + 5, cz - 3, "spruce_fence");
            c.set(cx + t, Y + 5, cz + 3, "spruce_fence");
        }
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++) c.set(cx + dx, deck, cz + dz, Math.abs(dx) == 4 || Math.abs(dz) == 4 ? "spruce_planks" : "spruce_planks");
        // railing (leave the ladder gap)
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++)
                if ((Math.abs(dx) == 4 || Math.abs(dz) == 4) && !(dx == 0 && dz == 4)) c.set(cx + dx, deck + 1, cz + dz, "spruce_fence");
        Shapes.cylinder(c, cx, deck + 1, cz, 2.6, 5, Fill.of("spruce_planks"), 1);
        for (int yy = deck + 1; yy <= deck + 5; yy++) c.set(cx + 3, yy, cz, "stripped_spruce_log[axis=y]");
        Shapes.cone(c, cx, deck + 6, cz, 3.4, 3, Fill.of("dark_oak_planks"));
        // ladder up the south face, against a post
        for (int yy = Y + 1; yy <= deck; yy++) {
            c.set(cx, yy, cz + 4, "spruce_planks");
            c.set(cx, yy, cz + 5, Blocks.ladder("south"));
        }
        c.set(cx, deck, cz + 5, Blocks.ladder("south"));
        c.set(cx, deck + 1, cz + 4, "air");
        return new int[]{cx - 2, deck, cz + 3};
    }

    /** An open shed with the composter; returns where to stand to use it. */
    private int[] composterShed(VoxelCanvas c, Box b) {
        int x0 = b.minX(), x1 = b.maxX(), z0 = b.minZ(), z1 = b.maxZ();
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) c.set(x, Y, z, Palette.of("coarse_dirt:3,podzol:2,spruce_planks:2").at(x, Y, z));
        for (int[] p : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {(x0 + x1) / 2, z0}, {(x0 + x1) / 2, z1}})
            for (int yy = Y + 1; yy <= Y + 4; yy++) c.set(p[0], yy, p[1], "spruce_log[axis=y]");
        for (int z = z0 - 1; z <= z1 + 1; z++)
            for (int x = x0 - 1; x <= x1 + 1; x++) c.set(x, Y + 5, z, Blocks.slab("spruce", "bottom"));
        // back wall of barrels and bins
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            c.set(x, Y + 1, z0, "spruce_planks");
            c.set(x, Y + 2, z0, x % 3 == 0 ? "spruce_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]" : "spruce_planks");
        }
        int mx = (x0 + x1) / 2;
        c.set(mx - 1, Y + 1, z0 + 1, "composter[level=8]");
        c.set(mx, Y + 1, z0 + 1, "composter[level=8]");
        c.set(mx + 1, Y + 1, z0 + 1, "composter[level=8]");
        c.set(x0 + 1, Y + 1, z0 + 1, "barrel[facing=up,open=false]");
        c.set(x0 + 1, Y + 2, z0 + 1, "barrel[facing=up,open=false]");
        c.set(x0 + 2, Y + 1, z0 + 1, "hay_block[axis=y]");
        c.set(x1 - 1, Y + 1, z0 + 1, "hay_block[axis=x]");
        c.set(x1 - 2, Y + 1, z0 + 1, "barrel[facing=up,open=false]");
        c.set(mx, Y + 4, (z0 + z1) / 2, Blocks.lantern(true));
        return new int[]{mx, Y, z0 + 4};
    }

    /** Four windmill sails on the south face of the tower. */
    private static void sails(VoxelCanvas c, int cx, int cy, int z) {
        c.set(cx, cy, z, "spruce_log[axis=z]");
        for (int k = 1; k <= 6; k++) {
            c.set(cx, cy + k, z, "spruce_fence");
            c.set(cx, cy - k, z, "spruce_fence");
            c.set(cx + k, cy, z, "spruce_fence");
            c.set(cx - k, cy, z, "spruce_fence");
            if (k >= 2) {
                c.set(cx + 1, cy + k, z, "white_wool");
                c.set(cx - 1, cy - k, z, "white_wool");
                c.set(cx + k, cy - 1, z, "white_wool");
                c.set(cx - k, cy + 1, z, "white_wool");
            }
        }
    }

    /** Four souls in the far corners of the garden, on the rim path inside the fence. */
    private void rimSouls(VoxelCanvas c, List<int[]> souls) {
        int a = PlotGrid.MIN + 1, b = PlotGrid.MAX - 1;
        souls.add(new int[]{a, Y, a});
        souls.add(new int[]{b, Y, a});
        souls.add(new int[]{a, Y, b});
        souls.add(new int[]{b, Y, b});
    }
}
