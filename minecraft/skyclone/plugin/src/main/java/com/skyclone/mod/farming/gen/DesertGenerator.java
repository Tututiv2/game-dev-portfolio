package com.skyclone.mod.farming.gen;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE MUSHROOM DESERT (research 03 section 6): badlands of banded terracotta mesas around a low sandy basin.
 * The Desert Settlement and its cactus field greet you in the south; the Oasis in the middle has sugar cane
 * and a cocoa grove; the Mushroom Gorge cuts west between two mesas, floored with mycelium, giant mushrooms
 * and mooshrooms, ending at the Glowing Grotto; the Trapper's Den sits on a terrace in the north-east;
 * Shepherd's Keep in the east; the Treasure Hunter camp and the Soul Hollow (nether wart) in the south-west.
 */
public final class DesertGenerator implements IslandGenerator {

    @Override public String islandId() { return "mushroom_desert"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-235, 0, -235, 235, 220, 235); }

    private VoxelCanvas c;
    private Terrain.Heightmap h;
    private Layout layout;
    private Reservations res;
    private final List<int[]> souls = new ArrayList<>();
    private Builders.PathResult paths;

    private static final String[] BANDS = {"terracotta", "orange_terracotta", "orange_terracotta", "yellow_terracotta", "terracotta",
            "red_terracotta", "white_terracotta", "terracotta", "brown_terracotta", "orange_terracotta", "light_gray_terracotta", "red_terracotta"};

    /** A mesa: centre, radius, height, terrace step. */
    private record Mesa(int x, int z, double r, int height, int step) {}

    @Override
    public void generate(VoxelCanvas canvas, Layout layout, long seed) {
        this.c = canvas;
        this.layout = layout;
        this.res = new Reservations();
        souls.clear();

        // ---- 1. terrain: a low basin + layered mesas -------------------------------------------------------
        Terrain.IslandShape s = new Terrain.IslandShape(seed);
        s.radiusX = 178; s.radiusZ = 168; s.baseY = 70;
        s.hills = 5; s.hillScale = 50; s.cliff = 0.75; s.coastNoise = 0.14; s.coastScale = 50;
        s.undersideDepth = 44; s.coastDrop = 3;
        h = Terrain.island(s);
        Noise edge = new Noise(Hash.salt(seed, "mesa"));
        List<Mesa> mesas = List.of(
                new Mesa(-20, -135, 60, 44, 7),     // the great north mesa
                new Mesa(-120, -95, 52, 38, 6),     // north-west
                new Mesa(118, -82, 50, 18, 6),      // trapper's terrace (one step)
                new Mesa(-150, 15, 38, 34, 6),      // west (the gorge's south wall)
                new Mesa(-105, -35, 34, 30, 6),     // the gorge's north wall
                new Mesa(150, 95, 30, 22, 5),       // south-east butte
                new Mesa(70, -150, 32, 30, 6));
        h.forEach((x, z, top, bottom) -> {
            double add = 0;
            for (Mesa m : mesas) {
                double d = Math.hypot(x - m.x, z - m.z) / (m.r * (1 + 0.22 * edge.fbm2(x / 30.0, z / 30.0, 3)));
                if (d >= 1) continue;
                double t = Noise.smoothstep(0.0, 0.55, 1 - d);
                double v = Math.floor(t * m.height / m.step + 0.15) * m.step;
                add = Math.max(add, v);
            }
            if (add > 0) h.set(x, z, top + (int) add, bottom);
        });

        // plots
        int yLow = 70;
        Box settlement = Box.of(-40, 0, 92, 40, 0, 140);
        Box portals = Box.of(-26, 0, 142, 26, 0, 156);
        Box cactus = Box.of(48, 0, 92, 90, 0, 128);
        Box oasis = Box.of(-40, 0, -10, 40, 0, 60);
        Box keep = Box.of(104, 0, 20, 146, 0, 66);
        Box camp = Box.of(-120, 0, 92, -76, 0, 130);
        Box hollow = Box.of(-70, 0, 46, -48, 0, 68);
        for (Box b : List.of(settlement, portals, cactus, oasis, keep, camp)) h.flatten(b, yLow, 8);
        h.flatten(hollow, yLow - 4, 5);
        // the trapper's den on top of its terrace
        Box den = Box.of(96, 0, -96, 136, 0, -64);
        int denY = h.average(Box.of(108, 0, -86, 128, 0, -76));
        if (denY == Terrain.Heightmap.NONE) denY = yLow + 18;
        h.flatten(den, denY, 6);
        // the gorge: a winding trench from the oasis west between two mesas
        int gorgeY = yLow - 8;
        int[][] gorge = {{-44, 14}, {-70, 6}, {-100, 0}, {-130, -10}, {-158, -18}};
        for (int i = 0; i + 1 < gorge.length; i++) carveTrench(gorge[i], gorge[i + 1], 11, gorgeY, i == 0 ? yLow : gorgeY);

        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        Fill band = (x, y, z) -> BANDS[Math.floorMod(y / 2 + (int) Math.round(edge.noise2(x / 60.0, z / 60.0) * 1.5), BANDS.length)];
        rules.top = Palette.of("red_sand:7,sand:2");
        rules.soil = band;
        rules.soilDepth = 3;
        rules.stone = band;
        rules.cliff = band;
        rules.steepSlope = 2;
        rules.underside = Palette.of("terracotta:4,red_sandstone:3,stone:2,granite:1");
        Terrain.fill(c, h, rules);
        // mesa tops get a crust of red sand / dead bushes, the gorge floor mycelium
        h.forEach((x, z, top, bottom) -> {
            if (top >= yLow + 12 && h.slope(x, z) < 2) c.set(x, top, z, Hash.chance(seed, x, z, 0.3) ? "coarse_dirt" : "red_sand");
            if (top <= gorgeY + 1 && top >= gorgeY - 2 && x < -40 && h.slope(x, z) < 2)
                c.set(x, top, z, Hash.chance(seed ^ 5, x, z, 0.35) ? "podzol" : "mycelium");
        });

        // the oasis basin is green
        h.forEach((x, z, top, bottom) -> {
            double d = Math.hypot(x, (z - 22) * 0.8);
            if (d > 34 || top > yLow + 1) return;
            double p = Noise.smoothstep(34, 18, d);
            if (Hash.unit(seed ^ 0x6a55L, x, z) < p) c.set(x, top, z, "grass_block");
        });

        // ---- 2. the oasis --------------------------------------------------------------------------------------------
        res.reserve("oasis.pool", Box.of(-14, 0, 6, 14, 0, 34));
        int pool = Terrain.pond(c, h, 0, 20, 12, 4, seed);
        Box caneA = Box.of(-36, 0, 0, -20, 0, 40);
        res.reserve("oasis.cane", grow(caneA, 1));
        FarmKit.caneRows(c, caneA.minX(), caneA.minZ(), caneA.maxX(), caneA.maxZ(), yLow, "sand");
        res.reserve("oasis.cocoa", Box.of(20, 0, -4, 40, 0, 48));
        for (int x : new int[]{24, 30, 36}) cocoaColumnWall(x, -2, 44, yLow, 5);
        for (int[] p : new int[][]{{-16, 0}, {14, -2}, {-18, 42}, {16, 44}, {0, 2}, {-6, 40}})
            if (h.top(p[0], p[1]) != Terrain.Heightmap.NONE && !h.wet(p[0], p[1])) Trees.grow(c, "palm", p[0], h.top(p[0], p[1]), p[1], seed ^ p[0] * 31L, 1.0);
        layout.zone("desert.oasis", "Oasis", 'b', Box.of(oasis.minX(), yLow - 10, oasis.minZ(), oasis.maxX(), yLow + 25, oasis.maxZ()), null);
        if (pool != Terrain.Heightmap.NONE) {
            // a little jetty into the pool
            for (int z = 31; z <= 36; z++) for (int x = -1; x <= 1; x++) c.set(x, pool + 1, z, "jungle_planks");
            souls.add(new int[]{0, pool + 1, 31});
        }
        souls.add(new int[]{40, yLow, 46});

        // ---- 3. the settlement (spawn) ---------------------------------------------------------------------------
        res.reserve("settlement.square", Box.of(-12, 0, 110, 12, 0, 132));
        pave(Box.of(-12, 0, 110, 12, 0, 132), yLow, Palette.of("smooth_sandstone:4,cut_sandstone:2,sandstone:2,chiseled_sandstone:1"));
        Builders.well(c, 0, yLow, 120);
        layout.standAt("spawn", 0, yLow, 129, 180f);
        for (int[] p : new int[][]{{-11, 111}, {11, 111}, {-11, 131}, {11, 131}}) Builders.lampPost(c, p[0], yLow, p[1], "torch", "north");
        Palette sandWall = Palette.of("smooth_sandstone:5,cut_sandstone:2,chiseled_sandstone:1");
        int[][] houses = {{-36, 96, 9, 7}, {-36, 112, 8, 8}, {-34, 126, 9, 7}, {20, 96, 9, 8}, {22, 114, 8, 7}, {24, 128, 8, 7}};
        String[] doors = {"east", "east", "east", "west", "west", "west"};
        for (int i = 0; i < houses.length; i++) {
            Builders.House hs = new Builders.House(houses[i][0], yLow, houses[i][1], houses[i][2], houses[i][3]);
            hs.door = doors[i]; hs.wall = sandWall; hs.frame = null; hs.roof = "sandstone"; hs.roofStyle = "flat";
            hs.foundation = Palette.of("sandstone:3,cut_sandstone:1"); hs.floor = Palette.of("birch_planks:3,sandstone:1");
            hs.doorWood = "birch"; hs.seed = seed + i; hs.floors = i % 3 == 1 ? 2 : 1;
            if (res.reserve("house" + i, hs.reserve())) {
                hs.build(c);
                Builders.path(c, new int[][]{{hs.doorStep[0], hs.doorStep[2]}, {hs.doorStep[0] + (doors[i].equals("east") ? 8 : -8), hs.doorStep[2]}},
                        3, Palette.of("smooth_sandstone:2,sand:1"), "sandstone_stairs", null);
                if (i == 1) souls.add(new int[]{hs.inside[0], hs.inside[1] + 4, hs.inside[2] + 1});
            }
        }
        Builders.Stall st1 = Builders.marketStall(c, -6, yLow, 104, "south", "orange", "white", seed);
        Builders.Stall st2 = Builders.marketStall(c, 6, yLow, 104, "south", "red", "yellow", seed + 1);
        layout.standAt("stall.1", st1.vendor()[0], st1.vendor()[1], st1.vendor()[2], 0f);
        layout.standAt("stall.2", st2.vendor()[0], st2.vendor()[1], st2.vendor()[2], 0f);
        layout.zone("desert.settlement", "Desert Settlement", 'e', Box.of(settlement.minX(), yLow - 4, settlement.minZ(), settlement.maxX(), yLow + 30, 140),
                null, Layout.Zone.SAFE);

        // cactus field east of the settlement
        res.reserve("cactus", grow(cactus, 1));
        FarmKit.cactusField(c, cactus.minX(), cactus.minZ(), cactus.maxX(), cactus.maxZ(), yLow);
        layout.zone("desert.cactus", "Cactus Field", 'a', Box.of(cactus.minX(), yLow - 3, cactus.minZ(), cactus.maxX(), yLow + 20, cactus.maxZ()), null);
        souls.add(new int[]{cactus.maxX() + 1, yLow, cactus.maxZ() + 1});

        // portals
        res.reserve("portals", portals);
        pave(portals, yLow, Palette.of("smooth_sandstone:3,cut_sandstone:1"));
        layout.area("portal.hub", FarmKit.portal(c, -12, yLow, 150, true, Palette.of("chiseled_sandstone:1,cut_sandstone:3"), "nether_portal"));
        layout.area("portal.barn", FarmKit.portal(c, 12, yLow, 150, true, Palette.of("oak_log[axis=y]:3,stripped_oak_log[axis=y]:1"), "nether_portal"));
        c.set(-12, yLow + 6, 150, Blocks.lantern(false));
        c.set(12, yLow + 6, 150, Blocks.lantern(false));
        souls.add(new int[]{24, yLow, 154});

        // ---- 4. the gorge ------------------------------------------------------------------------------------------
        Box gorgeBox = Box.of(-170, gorgeY - 2, -34, -44, gorgeY + 40, 30);
        layout.zone("desert.gorge", "Mushroom Gorge", 'c', gorgeBox, null);
        Random gr = new Random(Hash.salt(seed, "gorge"));
        int giant = 0;
        for (int tries = 0; tries < 400 && giant < 16; tries++) {
            int x = -155 + gr.nextInt(105), z = -30 + gr.nextInt(55);
            int t = h.top(x, z);
            if (t == Terrain.Heightmap.NONE || t > gorgeY + 1 || h.slope(x, z) > 1 || nearGorgeAxis(gorge, x, z) < 3) continue;
            String b = BlockState.name(c.get(x, t, z));
            if (!b.equals("mycelium") && !b.equals("podzol")) continue;
            Trees.grow(c, gr.nextBoolean() ? "red_mushroom" : "brown_mushroom", x, t, z, seed ^ (x * 131L + z), 1.1 + gr.nextDouble() * 0.6);
            giant++;
        }
        // small mushrooms scattered on the floor (harvestable) + a planted bed
        h.forEach((x, z, top, bottom) -> {
            if (x > -44 || top > gorgeY + 1 || top < gorgeY - 2) return;
            String b = BlockState.name(c.get(x, top, z));
            if ((b.equals("mycelium") || b.equals("podzol")) && c.isAir(x, top + 1, z) && Hash.chance(seed ^ 0x77L, x, z, 0.08))
                c.set(x, top + 1, z, Hash.chance(seed, x, 3, z, 0.5) ? "red_mushroom" : "brown_mushroom");
        });
        Box bed = Box.of(-92, 0, -6, -76, 0, 6);
        res.reserve("gorge.bed", grow(bed, 1));
        FarmKit.mushroomBed(c, bed.minX(), bed.minZ(), bed.maxX(), bed.maxZ(), groundOr(-84, 0, gorgeY), seed);
        layout.area("gorge.mooshrooms", Box.of(-150, gorgeY - 1, -28, -60, gorgeY + 3, 20));
        souls.add(new int[]{-120, gorgeY, 10});

        // the glowing grotto at the end of the gorge
        int gx = -160, gz = -18, gy = groundOr(gx, gz, gorgeY);
        grotto(gx, gy, gz, seed);

        // ---- 5. the trapper's den --------------------------------------------------------------------------------------
        res.reserve("den", den);
        pave(Box.of(104, 0, -88, 130, 0, -70), denY, Palette.of("coarse_dirt:3,podzol:2,spruce_planks:1,gravel:1"));
        Builders.House cabin = new Builders.House(106, denY, -94, 9, 7);
        cabin.door = "south"; cabin.wall = Palette.of("spruce_planks:4,stripped_spruce_log[axis=y]:1"); cabin.frame = "spruce_log";
        cabin.roof = "spruce"; cabin.chimney = true; cabin.seed = seed;
        cabin.build(c);
        Builders.House shop = new Builders.House(120, denY, -94, 9, 7);
        shop.door = "south"; shop.wall = Palette.of("spruce_planks:3,dark_oak_planks:1"); shop.frame = "dark_oak_log";
        shop.roof = "dark_oak"; shop.seed = seed + 9;
        shop.build(c);
        layout.standAt("npc.trapper", 112, denY, -80, 180f);
        layout.standAt("warp.trapper", 116, denY, -74, 0f);
        layout.standAt("npc.surveyor", shop.inside[0] - 1, shop.inside[1], shop.inside[2], 180f);
        layout.standAt("npc.fur_trader", 126, denY, -80, 180f);
        layout.standAt("npc.rancher", cabin.inside[0], cabin.inside[1], cabin.inside[2], 180f);
        Builders.campfire(c, 118, denY, -78);
        for (int x = 104; x <= 108; x++) { c.set(x, denY + 1, -72, "spruce_fence"); c.set(x, denY + 2, -72, x % 2 == 0 ? "brown_wool" : "spruce_fence"); }
        for (int[] p : new int[][]{{104, -70}, {130, -70}, {130, -88}}) Builders.lampPost(c, p[0], denY, p[1], "post", "north");
        Builders.crates(c, 128, denY, -76, seed);
        souls.add(new int[]{cabin.inside[0] + 2, cabin.inside[1], cabin.inside[2] - 1});
        ramp(97, 102, -26, denY, yLow, seed);
        layout.zone("desert.den", "Trapper's Den", '2', Box.of(den.minX(), denY - 4, den.minZ(), den.maxX(), denY + 25, den.maxZ()), null, Layout.Zone.SAFE);

        // ---- 6. shepherd's keep ----------------------------------------------------------------------------------------
        res.reserve("keep", keep);
        Box fold = FarmKit.pen(c, 112, 24, 144, 62, yLow, "spruce_fence", "spruce", "west", "grass_block", seed ^ 0x5eedL);
        layout.area("keep.sheep", fold);
        Builders.House hut = new Builders.House(104, yLow, 24, 7, 6);
        hut.door = "south"; hut.roof = "spruce"; hut.wall = Palette.of("cobblestone:2,spruce_planks:3"); hut.seed = seed;
        if (res.reserve("keep.hut", Box.of(102, 0, 22, 110, 0, 31))) hut.build(c);
        layout.standAt("npc.shepherd", 108, yLow, 40, -90f);
        souls.add(new int[]{143, yLow, 61});
        layout.zone("desert.keep", "Shepherd's Keep", 'a', Box.of(keep.minX(), yLow - 4, keep.minZ(), keep.maxX(), yLow + 25, keep.maxZ()), null);

        // ---- 7. treasure camp and the soul hollow ---------------------------------------------------------------------------
        res.reserve("camp", camp);
        pave(Box.of(-112, 0, 100, -84, 0, 122), yLow, Palette.of("sand:3,red_sand:2,gravel:1,coarse_dirt:1"));
        // the dig: a shallow pit with bones
        for (int x = -106; x <= -94; x++) for (int z = 104; z <= 114; z++) {
            boolean rim = x == -106 || x == -94 || z == 104 || z == 114;
            c.set(x, yLow, z, rim ? "sandstone" : "sand");
            c.set(x, yLow - 1, z, "sandstone");
            if (!rim) c.set(x, yLow, z, Hash.chance(seed, x, z, 0.12) ? "bone_block[axis=x]" : Hash.chance(seed ^ 3, x, z, 0.08) ? "suspicious_sand[dusted=0]" : "sand");
        }
        for (int[] t : new int[][]{{-90, 102}, {-90, 116}}) tent(t[0], yLow, t[1]);
        Builders.campfire(c, -100, yLow, 119);
        layout.standAt("npc.treasure_hunter", -96, yLow, 118, 0f);
        souls.add(new int[]{-111, yLow, 121});
        layout.zone("desert.camp", "Treasure Hunter Camp", '6', Box.of(camp.minX(), yLow - 4, camp.minZ(), camp.maxX(), yLow + 20, camp.maxZ()), null);

        res.reserve("hollow", grow(hollow, 2));
        FarmKit.wartBed(c, hollow.minX(), hollow.minZ(), hollow.maxX(), hollow.maxZ(), yLow - 4);
        for (int[] p : new int[][]{{hollow.minX() - 1, hollow.minZ() - 1}, {hollow.maxX() + 1, hollow.maxZ() + 1}})
            c.set(p[0], yLow - 3, p[1], Blocks.soulLantern(false));
        souls.add(new int[]{hollow.minX() - 1, yLow - 4, hollow.maxZ() + 1});
        layout.zone("desert.hollow", "Soul Hollow", '4', Box.of(hollow.minX() - 3, yLow - 8, hollow.minZ() - 3, hollow.maxX() + 3, yLow + 12, hollow.maxZ() + 3), null);

        // ---- 8. roads ----------------------------------------------------------------------------------------------------------
        Fill road = Palette.of("smooth_sandstone:3,sand:2,red_sand:1,coarse_dirt:1");
        paths = Builders.path(c, new int[][]{{0, 146}, {0, 134}}, 5, road, "sandstone_stairs", null);
        Builders.path(c, new int[][]{{0, 108}, {0, 70}, {0, 50}}, 5, road, "sandstone_stairs", paths);                 // to the oasis
        Builders.path(c, new int[][]{{-14, 120}, {-40, 120}, {-70, 112}, {-84, 112}}, 4, road, "sandstone_stairs", paths);   // to the camp
        Builders.path(c, new int[][]{{-40, 120}, {-46, 90}, {-58, 72}}, 3, road, "sandstone_stairs", paths);                 // to the hollow
        Builders.path(c, new int[][]{{14, 120}, {44, 120}, {47, 110}}, 4, road, "sandstone_stairs", paths);                  // cactus
        Builders.path(c, new int[][]{{20, 56}, {60, 46}, {96, 44}, {111, 43}}, 4, road, "sandstone_stairs", paths);          // keep
        Builders.path(c, new int[][]{{-18, 50}, {-44, 48}, {-50, 14}}, 4, road, "sandstone_stairs", paths);                  // gorge mouth
        Builders.path(c, new int[][]{{-50, 14}, {-70, 6}, {-100, 0}, {-130, -10}, {-145, -17}}, 5, Palette.of("coarse_dirt:3,podzol:1,gravel:1"),
                "spruce_stairs", paths);                                                                                      // gorge floor
        Builders.path(c, new int[][]{{20, -6}, {50, -18}, {80, -24}, {99, -24}}, 4, road, "sandstone_stairs", paths);  // den ramp foot
        Builders.path(c, new int[][]{{0, 50}, {0, 34}}, 3, road, "sandstone_stairs", paths);
        FarmKit.lampsAlong(c, new int[][]{{0, 108}, {0, 50}}, 14, 3, "torch");
        FarmKit.lampsAlong(c, new int[][]{{20, -6}, {50, -18}, {80, -24}}, 14, 3, "torch");
        FarmKit.lampsAlong(c, new int[][]{{-50, 14}, {-70, 6}, {-100, 0}, {-130, -10}}, 12, 6, "soul");

        // ---- 9. dressing: cacti and dead bushes in the open, rabbits' dunes ------------------------------------------------------
        h.forEach((x, z, top, bottom) -> {
            if (res.covers(x, z, 3) || paths.near(x, z, 2) || h.wet(x, z) || h.slope(x, z) > 1) return;
            String b = BlockState.name(c.get(x, top, z));
            if (!b.equals("red_sand") && !b.equals("sand")) return;
            if (!c.isAir(x, top + 1, z)) return;
            double u = Hash.unit(seed ^ 0xdeadL, x, z);
            if (u < 0.012) c.set(x, top + 1, z, "dead_bush");
            else if (u < 0.016 && top < yLow + 6 && lonely(x, top + 1, z)) { c.set(x, top + 1, z, "cactus"); if (u < 0.014) c.set(x, top + 2, z, "cactus"); }
            else if (u < 0.018) c.set(x, top + 1, z, "short_dry_grass");
        });
        for (int i = 0; i < 6; i++) {
            int x = -30 + i * 14, z = 70 + (i % 2) * 8;
            if (h.top(x, z) != Terrain.Heightmap.NONE && !res.covers(x, z, 2) && !paths.near(x, z, 3))
                Trees.grow(c, i % 3 == 0 ? "acacia" : "dead", x, h.top(x, z), z, seed ^ i, 0.9);
        }
        // oasis grass, and hoodoos (banded rock spires) as landmarks across the open badlands
        h.forEach((x, z, top, bottom) -> {
            if (!"grass_block".equals(BlockState.name(c.get(x, top, z))) || !c.isAir(x, top + 1, z) || paths.near(x, z, 1)) return;
            double u = Hash.unit(seed ^ 0x1234L, x, z);
            if (u < 0.25) c.set(x, top + 1, z, u < 0.04 ? "fern" : "short_grass");
        });
        Random hr = new Random(Hash.salt(seed, "hoodoo"));
        int hoodoos = 0;
        for (int tries = 0; tries < 300 && hoodoos < 14; tries++) {
            int x = -150 + hr.nextInt(300), z = -120 + hr.nextInt(230);
            int t = h.top(x, z);
            if (t == Terrain.Heightmap.NONE || t > yLow + 3 || h.slope(x, z) > 1 || !h.inland(x, z, 8)) continue;
            if (res.covers(x, z, 8) || paths.near(x, z, 6) || Math.hypot(x, z - 22) < 40) continue;
            double r = 1.5 + hr.nextDouble() * 2.5;
            int ht = 8 + hr.nextInt(16);
            for (int yy = t - 2; yy <= t + ht; yy++) {
                double rr = r * (1 - 0.35 * (yy - t) / (double) ht) + (yy == t + ht ? 1 : 0);
                Shapes.disk(c, x, yy, z, rr, (xx, y2, zz) -> BANDS[Math.floorMod(y2 / 2, BANDS.length)]);
            }
            Shapes.disk(c, x, t + ht + 1, z, r * 0.8, Fill.of("red_sand"));
            res.reserve("hoodoo" + hoodoos, Box.of(x - 5, 0, z - 5, x + 5, 0, z + 5));
            hoodoos++;
        }

        layout.area("desert.rabbits", Box.of(-60, yLow - 2, 60, 60, yLow + 6, 90));

        // more souls on the mesas' reachable steps and corners
        soulOnGround(60, 70);
        soulOnGround(-60, 30);
        soulOnGround(90, -30);

        // ---- 10. trapper hunting grounds --------------------------------------------------------------------------------------------
        Map<String, Object[]> hunts = new LinkedHashMap<>();
        hunts.put("oasis", new Object[]{"The Oasis", Box.of(-40, 0, -10, 40, 0, 62), yLow});
        hunts.put("gorge", new Object[]{"Mushroom Gorge", Box.of(-150, 0, -26, -60, 0, 22), gorgeY});
        hunts.put("dunes", new Object[]{"Settlement Dunes", Box.of(-60, 0, 60, 60, 0, 92), yLow});
        hunts.put("keep", new Object[]{"Shepherd's Keep", Box.of(86, 0, 0, 104, 0, 80), yLow});
        hunts.put("badlands", new Object[]{"Red Badlands", Box.of(40, 0, -40, 100, 0, 20), yLow});
        StringBuilder areas = new StringBuilder();
        for (Map.Entry<String, Object[]> e : hunts.entrySet()) {
            Box b = (Box) e.getValue()[1];
            int want = (int) e.getValue()[2];
            List<int[]> spots = spots(b, want, 10, seed ^ e.getKey().hashCode());
            if (spots.isEmpty()) continue;
            int i = 1;
            for (int[] sp : spots) layout.standAt("trapper.spot." + e.getKey() + "." + i++, sp[0], sp[1], sp[2], 0f);
            if (areas.length() > 0) areas.append(',');
            areas.append(e.getKey()).append(':').append(e.getValue()[0]);
        }
        layout.data("trapper.areas", areas.toString());

        // ---- 11. layout facts ---------------------------------------------------------------------------------------------------------
        Box isl = h.box();
        layout.zone("mushroom_desert", "Mushroom Desert", '6', new Box(isl.minX(), 0, isl.minZ(), isl.maxX(), 255, isl.maxZ()), null);
        layout.zone("desert.mesa", "Red Mesas", 'c', new Box(isl.minX(), yLow + 14, isl.minZ(), isl.maxX(), 255, isl.maxZ()), null);
        layout.data("reservations", res.summary());
        FarmKit.souls(c, layout, souls);
    }

    // =========================================================================================================

    private static Box grow(Box b, int n) { return Box.of(b.minX() - n, b.minY(), b.minZ() - n, b.maxX() + n, b.maxY(), b.maxZ() + n); }

    /** Lower the heightmap along a segment to {@code floor}, blending the walls. */
    private void carveTrench(int[] a, int[] b, int halfWidth, int floor, int startFloor) {
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int n = (int) Math.ceil(len);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int cx = (int) Math.round(a[0] + (b[0] - a[0]) * t), cz = (int) Math.round(a[1] + (b[1] - a[1]) * t);
            int fy = (int) Math.round(startFloor + (floor - startFloor) * t);
            for (int dz = -halfWidth - 8; dz <= halfWidth + 8; dz++)
                for (int dx = -halfWidth - 8; dx <= halfWidth + 8; dx++) {
                    int x = cx + dx, z = cz + dz;
                    if (!h.has(x, z)) continue;
                    double d = Math.hypot(dx, dz);
                    int top = h.top(x, z);
                    int want;
                    if (d <= halfWidth) want = fy;
                    else if (d <= halfWidth + 8) want = (int) Math.round(fy + (top - fy) * Noise.smoothstep(halfWidth, halfWidth + 8, d));
                    else continue;
                    if (want < top) h.setTop(x, z, want);
                }
        }
    }

    private static double nearGorgeAxis(int[][] pts, int x, int z) {
        double best = 1e9;
        for (int i = 0; i + 1 < pts.length; i++) {
            double ax = pts[i][0], az = pts[i][1], bx = pts[i + 1][0], bz = pts[i + 1][1];
            double vx = bx - ax, vz = bz - az, wx = x - ax, wz = z - az;
            double t = Math.max(0, Math.min(1, (vx * wx + vz * wz) / (vx * vx + vz * vz)));
            best = Math.min(best, Math.hypot(x - (ax + vx * t), z - (az + vz * t)));
        }
        return best;
    }

    private int groundOr(int x, int z, int fallback) {
        int g = Builders.ground(c, x, z, 200, 20);
        return g == Integer.MIN_VALUE ? fallback : g;
    }

    private void pave(Box b, int y, Fill f) {
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                c.set(x, y, z, f.at(x, y, z));
                Builders.footing(c, x, y, z, Fill.of("sandstone"), 10);
                for (int k = 1; k <= 6; k++) if (Builders.natural(c.get(x, y + k, z))) c.set(x, y + k, z, "air");
            }
    }

    /**
     * A switchback-free straight ramp going north (toward -z) from z0 at ground {@code fromY} up to {@code toY}:
     * one block of rise every two blocks, a stair at every rise, railings, lit. Carves and fills as needed.
     */
    private void ramp(int x0, int x1, int z0, int toY, int fromY, long seed) {
        Fill deck = Palette.of("smooth_sandstone:3,cut_sandstone:2");
        int len = (toY - fromY) * 2 + 2;
        for (int k = 0; k <= len; k++) {
            int z = z0 - k;
            int y = Math.min(toY, fromY + k / 2);
            boolean rise = k % 2 == 1 && y < toY;            // the next cell is one higher: put a stair here
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                boolean rail = x == x0 - 1 || x == x1 + 1;
                c.set(x, y, z, rail ? "cut_red_sandstone" : deck.at(x, y, z));
                Builders.footing(c, x, y, z, Palette.of("red_sandstone:2,terracotta:1"), 30);
                for (int hh = 1; hh <= 5; hh++) c.set(x, y + hh, z, "air");
                if (rail) c.set(x, y + 1, z, "red_sandstone_wall");
                else if (rise) c.set(x, y + 1, z, Blocks.stairs("sandstone_stairs", "north"));
            }
            if (k % 10 == 4) { c.set(x0 - 1, y + 2, z, Blocks.lantern(false)); c.set(x1 + 1, y + 2, z, Blocks.lantern(false)); }
        }
    }

    /** A cocoa wall running along z (logs at x), cocoa on the west and east faces. */
    private void cocoaColumnWall(int x, int z0, int z1, int y, int height) {
        for (int z = z0; z <= z1; z++) {
            for (int dx = -2; dx <= 2; dx++) {
                c.set(x + dx, y, z, dx == 0 ? "jungle_wood" : "grass_block");
                Builders.footing(c, x + dx, y, z, Fill.of("dirt"), 8);
                for (int k = 1; k <= height + 1; k++) c.set(x + dx, y + k, z, "air");
            }
            boolean post = Math.floorMod(z - z0, 7) == 0;
            for (int k = 1; k <= height; k++) {
                c.set(x, y + k, z, "jungle_log[axis=y]");
                if (!post) {
                    c.set(x - 1, y + k, z, "cocoa[age=2,facing=east]");
                    c.set(x + 1, y + k, z, "cocoa[age=2,facing=west]");
                }
            }
            c.set(x, y + height + 1, z, post ? "jungle_leaves[persistent=true]" : Blocks.slab("jungle", "bottom"));
            if (post) c.set(x, y + height + 2, z, "jungle_leaves[persistent=true]");
        }
    }

    private boolean lonely(int x, int y, int z) {
        for (int d = 0; d < 4; d++) if (!c.isAir(x + Blocks.DX[d], y, z + Blocks.DZ[d])) return false;
        return true;
    }

    /** A ridge tent along z: sloped wool roof, closed back gable, open front (north). */
    private void tent(int x, int y, int z) {
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int top = y + 3 - Math.abs(dx);
                String wool = dx == 0 ? "brown_wool" : "white_wool";
                c.set(x + dx, top, z + dz, wool);
                if (dz == 2) for (int yy = y + 1; yy < top; yy++) c.set(x + dx, yy, z + dz, "white_wool");     // back wall
                else for (int yy = y + 1; yy < top; yy++) c.set(x + dx, yy, z + dz, "air");
                if (Math.abs(dx) == 2) for (int yy = y + 1; yy < top + 0; yy++) c.set(x + dx, yy, z + dz, "white_wool");
            }
        c.set(x, y + 1, z + 1, "brown_carpet");
    }

    /** A hollow dome of terracotta lined with shroomlight: the Glowing Grotto (chum vendor inside). */
    private void grotto(int cx, int y, int cz, long seed) {
        res.reserve("grotto", Box.of(cx - 12, 0, cz - 12, cx + 12, 0, cz + 12));
        Shapes.dome(c, cx, y, cz, 11, Palette.of("terracotta:3,brown_terracotta:2,mushroom_stem:1"), 2.5);
        for (int x = cx - 11; x <= cx + 11; x++)
            for (int z = cz - 11; z <= cz + 11; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 11) continue;
                c.set(x, y, z, d < 8.6 ? (Hash.chance(seed, x, z, 0.5) ? "mycelium" : "podzol") : "terracotta");
                Builders.footing(c, x, y, z, Fill.of("terracotta"), 12);
                if (d < 8.6) for (int k = 1; k <= 9; k++) if (Math.hypot(Math.hypot(x - cx, z - cz), k) < 8.4) c.set(x, y + k, z, "air");
            }
        // glowing ceiling and mushrooms
        for (int[] p : new int[][]{{0, 0}, {3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) c.set(cx + p[0], y + (p[0] == 0 ? 9 : 8), cz + p[1], "shroomlight");
        for (int x = cx - 6; x <= cx + 6; x += 2) for (int z = cz - 6; z <= cz + 6; z += 3)
            if (Math.hypot(x - cx, z - cz) < 7 && c.isAir(x, y + 1, z) && !(x == cx + 2 && z == cz))
                c.set(x, y + 1, z, Hash.chance(seed, x, z, 0.5) ? "red_mushroom" : "brown_mushroom");
        // entrance on the east side, toward the gorge
        for (int x = cx + 7; x <= cx + 12; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            c.set(x, y, z, "podzol");
            for (int k = 1; k <= 3; k++) c.set(x, y + k, z, "air");
        }
        for (int x = cx + 7; x <= cx + 10; x++) for (int z = cz - 2; z <= cz + 2; z++) c.set(x, y + 4, z, "terracotta");
        c.set(cx + 10, y + 3, cz, "shroomlight");
        layout.standAt("npc.chum_vendor", cx + 2, y, cz, 90f);
        souls.add(new int[]{cx - 5, y, cz + 2});
        layout.zone("desert.grotto", "Glowing Grotto", 'd', Box.of(cx - 11, y - 2, cz - 11, cx + 11, y + 12, cz + 11), null);
    }

    /** Up to {@code n} flat, open, reachable-looking ground spots in a box at about {@code wantY}. */
    private List<int[]> spots(Box b, int wantY, int n, long seed) {
        List<int[]> out = new ArrayList<>();
        Random r = new Random(seed);
        for (int tries = 0; tries < 2000 && out.size() < n; tries++) {
            int x = b.minX() + r.nextInt(b.maxX() - b.minX() + 1), z = b.minZ() + r.nextInt(b.maxZ() - b.minZ() + 1);
            int t = h.top(x, z);
            if (t == Terrain.Heightmap.NONE || h.wet(x, z) || h.slope(x, z) > 1 || Math.abs(t - wantY) > 3) continue;
            if (res.covers(x, z, 1)) continue;
            int g = Builders.ground(c, x, z, t + 3, t - 3);
            if (g == Integer.MIN_VALUE || !BlockInfo.of(c.get(x, g, z)).cube) continue;
            if (!c.isAir(x, g + 1, z) && Builders.natural(c.get(x, g + 1, z))) c.set(x, g + 1, z, "air");
            if (!c.isAir(x, g + 1, z) || !c.isAir(x, g + 2, z)) continue;
            boolean close = false;
            for (int[] o : out) if (Math.abs(o[0] - x) + Math.abs(o[2] - z) < 8) close = true;
            if (close) continue;
            out.add(new int[]{x, g, z});
        }
        return out;
    }

    private void soulOnGround(int x, int z) {
        int g = Builders.ground(c, x, z, 220, 20);
        if (g == Integer.MIN_VALUE) return;
        if (!BlockInfo.of(c.get(x, g, z)).cube) c.set(x, g, z, "sandstone");
        for (int k = 1; k <= 2; k++) if (!c.isAir(x, g + k, z) && Builders.natural(c.get(x, g + k, z))) c.set(x, g + k, z, "air");
        souls.add(new int[]{x, g, z});
    }
}
