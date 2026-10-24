package com.skyclone.mod.combatzones.gen;

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
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;
import com.skyclone.gen.util.LongSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Spider's Den: a dark, web-strung mountain on a rainy island. A road spirals from the den
 * entrance up to the Top of the Nest (the Broodmother's plateau); a ring road around the foot
 * links the Gravel Mines quarry, the slime hill, the archaeologists' camp, Granny's cottage,
 * the far-side portals and the Silkmother's Burrow, a tunnel into the mountain that opens on
 * her Sanctuary (the summoning altar). Pure: no Bukkit.
 *
 * <p>Preview: {@code bash tools/preview.sh com.skyclone.mod.combatzones.gen.DenGenerator}
 */
public final class DenGenerator implements IslandGenerator {

    public static final String ID = "spiders_den";

    /** Mountain centre, radius and height. */
    static final int CX = 0, CZ = -12;
    static final double R = 112, H = 104, BASE = 73;

    @Override public String islandId() { return ID; }

    /** 2: hunting grounds (playtest-loops 2026-10-03) + Builders.well's ladder fix for Granny's well. */
    /** Relics hidden on the island (the reference hunt has 28). */
    public static final int RELICS = 28;

    // v3 (finish wave 2026-10-03): 28 relic skulls for Professor Dunmore's hunt (layout data "relics")
    // v4 (wave 5): Fenn's Crib on the plaza's west side (npc.ike / npc.michael, zone fenns_crib)
    @Override public int version() { return 4; }

    /** Fenn's Crib (wave 5): the awning's corner posts, on the Den Entrance plaza. */
    static final int CRIB_X0 = -10, CRIB_X1 = -6, CRIB_Z0 = 116, CRIB_Z1 = 120;

    @Override public Box bounds() { return new Box(-175, 0, -175, 175, 200, 175); }

    static double mountain(double r) {
        if (r >= R) return 0;
        return H * Math.pow(1 - r / R, 1.4);
    }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---------------------------------------------------------------- 1. island + mountain
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 148; shape.radiusZ = 136; shape.baseY = (int) BASE;
        shape.hills = 5; shape.hillScale = 44; shape.cliff = 0.55; shape.coastNoise = 0.17;
        shape.undersideDepth = 40; shape.coastDrop = 5;
        Terrain.Heightmap h = Terrain.island(shape);
        Noise rough = new Noise(Hash.salt(seed, "den-rough"));
        h.forEach((x, z, top, bottom) -> {
            double r = Math.hypot(x - CX, z - CZ);
            if (r >= R) return;
            double f = 1 - r / R;
            double crag = rough.ridged2(x / 24.0, z / 24.0, 4) * 13 * f * f + rough.fbm2(x / 11.0, z / 11.0, 3) * 3 * f;
            double m = mountain(r) + crag;
            // the north and east faces break into ledges and short cliffs; south and west stay walkable slopes
            double th = Math.atan2(x - CX, z - CZ);                       // 0 = south
            double mask = Noise.smoothstep(0.1, 0.7, -Math.cos(th - 0.6)) * Noise.smoothstep(0.15, 0.4, f);
            if (mask > 0) {
                double rel = m / 7.0, fl = Math.floor(rel), fr = rel - fl;
                double terr = (fl + Noise.smoothstep(0.62, 1.0, fr)) * 7.0;
                m = m + (terr - m) * mask;
            }
            h.setTop(x, z, (int) Math.round(top + m));
        });

        // ---------------------------------------------------------------- 2. the spiral road
        List<Kit.RoadPoint> spiral = new ArrayList<>();
        double turns = 1.35, r0 = 92, r1 = 24;
        int n = 1400;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double r = r0 + (r1 - r0) * t;
            double th = t * turns * Math.PI * 2;                 // 0 = south, counter-clockwise from above
            double x = CX + Math.sin(th) * r, z = CZ + Math.cos(th) * r;
            spiral.add(new Kit.RoadPoint(x, z, BASE + 1 + mountain(r)));
        }
        Kit.gradeRoad(h, spiral, 3.0, 6);
        int plateauY = (int) Math.round(spiral.get(n).y());

        // nest plateau at the road's end
        for (int z = CZ - 32; z <= CZ + 32; z++)
            for (int x = CX - 32; x <= CX + 32; x++) {
                double d = Math.hypot(x - CX, z - CZ);
                if (!h.has(x, z) || d > 30) continue;
                int t = h.top(x, z);
                if (d <= 22) h.setTop(x, z, plateauY);
                else h.setTop(x, z, (int) Math.round(plateauY + (t - plateauY) * Noise.smoothstep(22, 30, d)));
            }

        // ---------------------------------------------------------------- 3. plots
        Box plaza = Box.of(-12, 0, 104, 12, 0, 122);
        int plazaY = h.average(plaza);
        h.flatten(plaza, plazaY, 7);
        Box cottagePlot = Box.of(-130, 0, 0, -112, 0, 18);
        int cottageY = h.average(cottagePlot);
        h.flatten(cottagePlot, cottageY, 6);
        Box campPlot = Box.of(-86, 0, 78, -62, 0, 98);
        int campY = h.average(campPlot);
        h.flatten(campPlot, campY, 6);
        Box farPlot = Box.of(-88, 0, -100, -64, 0, -86);
        int farY = h.average(farPlot);
        h.flatten(farPlot, farY, 7);
        Box burrowPlot = Box.of(-7, 0, -124, 7, 0, -112);
        int burrowY = h.average(burrowPlot);
        h.flatten(burrowPlot, burrowY, 6);

        // quarry: a flat rim, then a pit with a ramp in from the west
        final int qx = 118, qz = -6;
        Box quarryRim = Box.of(qx - 24, 0, qz - 24, qx + 24, 0, qz + 24);
        int rimY = h.average(quarryRim);
        final int quarryFloor = rimY - 9;
        for (int z = qz - 27; z <= qz + 27; z++)
            for (int x = qx - 27; x <= qx + 27; x++) {
                if (!h.has(x, z)) continue;
                double d = Math.hypot(x - qx, z - qz);
                if (d <= 17) h.setTop(x, z, quarryFloor);
                else if (d <= 21) h.setTop(x, z, (int) Math.round(quarryFloor + (rimY - quarryFloor) * (d - 17) / 4.0));
                else if (d <= 27) h.setTop(x, z, (int) Math.round(rimY + (h.top(x, z) - rimY) * Noise.smoothstep(21, 27, d)));
            }
        for (int x = qx - 30; x <= qx - 12; x++)                   // the ramp
            for (int z = qz - 2; z <= qz + 2; z++) {
                if (!h.has(x, z)) continue;
                double t = (x - (qx - 30)) / 18.0;
                h.setTop(x, z, (int) Math.round(rimY + (quarryFloor - rimY) * Noise.smoothstep(0, 1, t)));
            }

        // slime hill
        final int sx = 92, sz = 80;
        Kit.bump(h, sx, sz, 24, 11);

        // ---------------------------------------------------------------- 4. write the terrain
        Palette lowTop = Palette.of("podzol:4,coarse_dirt:3,grass_block:2,moss_block:1,rooted_dirt:1");
        Palette midTop = Palette.of("coarse_dirt:3,stone:3,gravel:2,andesite:2,podzol:1,tuff:1");
        Palette highTop = Palette.of("stone:3,andesite:2,tuff:3,cobblestone:1,gravel:1,deepslate:1");
        Palette nestTop = Palette.of("deepslate:3,tuff:3,cobbled_deepslate:2,gravel:1,coarse_dirt:1");
        Palette gravel = Palette.of("gravel:7,coarse_dirt:1,andesite:1");
        Palette slimeTop = Palette.of("moss_block:5,grass_block:3,mossy_cobblestone:1");
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        Noise patch = new Noise(Hash.salt(seed, "den-patch"));
        Palette scree = Palette.of("gravel:4,cobblestone:2,coarse_dirt:2,moss_block:1");
        Palette lichen = Palette.of("moss_block:3,podzol:2,coarse_dirt:2,mossy_cobblestone:2");
        rules.top = (x, y, z) -> {
            if (inQuarry(x, z, qx, qz)) return gravel.at(x, y, z);
            double pv = patch.fbm2(x / 19.0, z / 19.0, 3);
            if (y >= 90 && y < plateauY - 1 && pv > 0.32) return (pv > 0.45 ? lichen : scree).at(x, y, z);
            if (Math.hypot(x - sx, z - sz) < 18) return slimeTop.at(x, y, z);
            if (y >= plateauY - 1) return nestTop.at(x, y, z);
            if (y < 84) return lowTop.at(x, y, z);
            if (y < 104) return midTop.at(x, y, z);
            return highTop.at(x, y, z);
        };
        rules.soil = Palette.of("dirt:3,coarse_dirt:2,gravel:1");
        rules.soilDepth = 2;
        rules.stone = Palette.of("stone:8,andesite:3,tuff:2,deepslate:1,cobblestone:1");
        Palette cliff = Palette.of("stone:4,andesite:3,tuff:3,cobblestone:1,mossy_cobblestone:1,deepslate:1");
        rules.cliff = (x, y, z) -> inQuarry(x, z, qx, qz) ? gravel.at(x, y, z) : cliff.at(x, y, z);
        rules.steepSlope = 3;
        rules.underside = Palette.of("stone:5,tuff:3,andesite:3,deepslate:2,cobblestone:1");
        Terrain.fill(c, h, rules);

        Reservations res = new Reservations();

        // ---------------------------------------------------------------- 5. carve: burrow + sanctuary
        int cavY = burrowY;
        final int cavX = 0, cavZ = -54;
        Palette caveFloor = Palette.of("cobbled_deepslate:3,deepslate:2,gravel:1,tuff:1");
        double[][] tunnel = {
                {0, burrowY, -118}, {0, burrowY, -106}, {-3, burrowY, -96}, {2, burrowY, -84}, {0, cavY, -72}
        };
        Kit.tunnel(c, tunnel, 3.2, 6, caveFloor);
        // two side chambers (the Burrow proper, where keepers lurk)
        Kit.cavern(c, -13, burrowY, -92, 8.5, 8, caveFloor, seed);
        Kit.cavern(c, 13, burrowY, -84, 8, 8, caveFloor, seed ^ 7);
        Kit.cavern(c, cavX, cavY, cavZ, 18, 15, Palette.of("polished_deepslate:2,deepslate_tiles:2,cobbled_deepslate:1"), seed);
        res.reserve("sanctuary", Box.of(cavX - 22, cavY - 2, cavZ - 22, cavX + 22, cavY + 18, cavZ + 22));

        // ---------------------------------------------------------------- 6. structures
        // 6a. den entrance: plaza, gate arch, portal home, lights
        res.reserve("plaza", Box.of(plaza.minX() - 2, plazaY - 3, plaza.minZ() - 2, plaza.maxX() + 2, plazaY + 12, plaza.maxZ() + 6));
        Palette paving = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:2,cobbled_deepslate:2,polished_andesite:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++)
            for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, plazaY, z, paving.at(x, plazaY, z));
        Palette darkFrame = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1,chiseled_polished_blackstone:1");
        Kit.portal(c, layout, "portal.hub", 0, plazaY, 125, true, 3, darkFrame, "nether_portal", paving);
        c.set(-3, plazaY + 7, 125, "cobweb");
        c.set(3, plazaY + 7, 125, "cobweb");
        Builders.wallBanner(c, -1, plazaY + 6, 124, "white", "north");
        Builders.wallBanner(c, 1, plazaY + 6, 124, "white", "north");
        // gate arch at the plaza's north edge, over the road to the mountain
        Shapes.arch(c, -6, 103, 6, 103, plazaY + 1, 9, 2, 2, darkFrame);
        for (int x = -4; x <= 4; x += 4) Kit.hangingLantern(c, x, plazaY + 8, 103, 1, true);
        for (int[] lp : new int[][]{{-11, 106}, {11, 106}, {-11, 121}, {11, 121}})
            Builders.lampPost(c, lp[0], plazaY, lp[1], "soul", "north");
        Builders.bench(c, -9, plazaY, 113, "east", 3, "dark_oak");
        Builders.bench(c, 9, plazaY, 111, "west", 3, "dark_oak");
        Builders.crates(c, 10, plazaY, 117, seed);
        // a web-draped notice board
        c.set(-10, plazaY + 1, 109, "dark_oak_fence");
        c.set(-10, plazaY + 2, 109, "dark_oak_planks");
        c.set(-10, plazaY + 3, 109, "cobweb");
        // Fenn's Crib (wave 5): a dark-oak awning on the plaza's west side where Fenn and Lark teach mob types
        // (the reference's Michael's Crib). Posts at the four corners, a slab roof, a spider-skull sign-post.
        for (int[] post : new int[][]{{CRIB_X0, CRIB_Z0}, {CRIB_X1, CRIB_Z0}, {CRIB_X0, CRIB_Z1}, {CRIB_X1, CRIB_Z1}})
            for (int y = 1; y <= 3; y++) c.set(post[0], plazaY + y, post[1], "dark_oak_fence");
        for (int z = CRIB_Z0; z <= CRIB_Z1; z++)
            for (int x = CRIB_X0; x <= CRIB_X1; x++) c.set(x, plazaY + 4, z, (x + z) % 2 == 0 ? "dark_oak_slab[type=bottom]" : "spruce_slab[type=bottom]");
        c.set(CRIB_X0, plazaY + 1, CRIB_Z0 + 2, "barrel[facing=up]");
        c.set(CRIB_X0, plazaY + 2, CRIB_Z0 + 2, "potted_dead_bush");           // not a skull: skulls here are relics
        Kit.hangingLantern(c, (CRIB_X0 + CRIB_X1) / 2, plazaY + 4, (CRIB_Z0 + CRIB_Z1) / 2, 0, true);

        // 6b. Granny's cottage (west)
        Builders.House cottage = new Builders.House(-127, cottageY, 3, 11, 9);
        cottage.floors = 2; cottage.door = "east"; cottage.roof = "spruce"; cottage.roofStyle = "gable";
        cottage.wall = Palette.of("spruce_planks:5,stripped_spruce_log[axis=y]:1,mossy_cobblestone:1");
        cottage.frame = "dark_oak_log"; cottage.window = "brown_stained_glass_pane"; cottage.chimney = true;
        cottage.chimneyBlock = "mossy_cobblestone"; cottage.seed = seed; cottage.doorWood = "spruce";
        if (res.reserve("cottage", cottage.reserve())) cottage.build(c);
        // garden fence + a well
        Builders.fenceLine(c, new int[][]{{-114, 1}, {-112, 1}, {-112, 17}, {-114, 17}}, "spruce_fence", false);
        Builders.well(c, -118, cottageY, 15);
        Builders.lampPost(c, -114, cottageY, 12, "soul", "east");

        // 6c. archaeologists' camp (south-west)
        res.reserve("camp", Box.of(campPlot.minX(), campY - 3, campPlot.minZ(), campPlot.maxX(), campY + 8, campPlot.maxZ()));
        tent(c, -82, campY, 82, "brown_wool", "white_wool");
        tent(c, -82, campY, 90, "green_wool", "white_wool");
        tent(c, -72, campY, 82, "brown_wool", "light_gray_wool");
        Builders.campfire(c, -70, campY, 91);
        Builders.crates(c, -66, campY, 84, seed ^ 3);
        // the dig: a shallow pit with sieves of gravel and suspicious finds
        for (int z = 92; z <= 96; z++)
            for (int x = -80; x <= -73; x++) {
                c.set(x, campY, z, (x + z) % 3 == 0 ? "suspicious_gravel" : "gravel");
                c.set(x, campY + 1, z, "air");
            }
        c.set(-81, campY + 1, 94, "scaffolding");
        c.set(-72, campY + 1, 94, "scaffolding");
        c.set(-72, campY + 2, 94, "scaffolding");
        c.set(-64, campY + 1, 91, "decorated_pot");
        c.set(-64, campY + 1, 93, "decorated_pot");
        Builders.lampPost(c, -68, campY, 80, "soul", "south");

        // 6d. far side: two portals onward (the End, the Crimson Isle)
        res.reserve("farside", Box.of(farPlot.minX(), farY - 3, farPlot.minZ(), farPlot.maxX(), farY + 10, farPlot.maxZ()));
        Palette farFloor = Palette.of("polished_blackstone:3,blackstone:2,cobbled_deepslate:1");
        for (int z = farPlot.minZ(); z <= farPlot.maxZ(); z++)
            for (int x = farPlot.minX(); x <= farPlot.maxX(); x++) c.set(x, farY, z, farFloor.at(x, farY, z));
        Kit.portal(c, layout, "portal.the_end", -82, farY, -97, true, 3,
                Palette.of("end_stone_bricks:4,purpur_block:2,purpur_pillar[axis=y]:1"), "nether_portal", farFloor);
        Kit.portal(c, layout, "portal.crimson_isle", -70, farY, -97, true, 3,
                Palette.of("nether_bricks:4,red_nether_bricks:2,cracked_nether_bricks:1"), "nether_portal", farFloor);
        c.set(-82, farY + 6, -97, "end_rod[facing=up]");
        c.set(-70, farY + 6, -97, "magma_block");
        Builders.lampPost(c, -87, farY, -88, "soul", "north");
        Builders.lampPost(c, -65, farY, -88, "soul", "north");
        Builders.banner(c, -76, farY, -99, "purple", 0);

        // 6e. burrow mouth: a dark stone portal-arch over the tunnel
        res.reserve("burrow_mouth", Box.of(-8, burrowY - 3, -125, 8, burrowY + 9, -110));
        Shapes.arch(c, -5, -116, 5, -116, burrowY + 1, 8, 2, 2, Palette.of("cobbled_deepslate:3,deepslate_bricks:2,mossy_cobblestone:1"));
        for (int dz : new int[]{-119, -113}) for (int dx : new int[]{-5, 5}) c.set(dx, burrowY + 7, dz, "cobweb");
        Builders.lampPost(c, -6, burrowY, -121, "soul", "north");
        Builders.lampPost(c, 6, burrowY, -121, "soul", "north");
        // tunnel lighting + webs
        for (double[] tp : new double[][]{{0, -104}, {-2, -94}, {1, -86}, {0, -77}})
            Kit.hangingLantern(c, (int) tp[0], topAir(c, (int) tp[0], burrowY, (int) tp[1]) + 1, (int) tp[1], 1, true);
        webWalls(c, Box.of(-24, burrowY, -126, 24, burrowY + 9, -66), seed, 0.18);

        // 6f. the Sanctuary altar
        int ay = cavY;
        Palette dais = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1");
        for (int z = cavZ - 4; z <= cavZ + 4; z++)
            for (int x = cavX - 4; x <= cavX + 4; x++) {
                boolean edge = Math.abs(x - cavX) == 4 || Math.abs(z - cavZ) == 4;
                c.set(x, ay + 1, z, edge ? Blocks.slab("polished_blackstone_brick", "bottom") : dais.at(x, ay + 1, z));
            }
        c.set(cavX, ay + 2, cavZ, "lodestone");                       // the altar stone players click
        for (int[] s : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {   // four calling sockets
            c.set(cavX + s[0], ay + 2, cavZ + s[1], "chiseled_polished_blackstone");
            c.set(cavX + s[0], ay + 3, cavZ + s[1], "soul_lantern[hanging=false,waterlogged=false]");
        }
        // silk curtains hanging from the dome
        int ceil = topAir(c, cavX, ay, cavZ) + 1;
        for (int k = 1; k <= 4; k++) c.set(cavX, ceil - k, cavZ, k == 4 ? "cobweb" : "iron_chain[axis=y]");
        Noise silk = new Noise(Hash.salt(seed, "silk"));
        for (int z = cavZ - 17; z <= cavZ + 17; z++)
            for (int x = cavX - 17; x <= cavX + 17; x++) {
                int top = topAir(c, x, ay, z);
                if (top <= ay + 4) continue;
                if (silk.noise2(x / 3.0, z / 3.0) > 0.45 && Math.abs(x - cavX) + Math.abs(z - cavZ) > 7) {
                    c.set(x, top, z, "cobweb");
                    if (silk.noise2(x / 2.0, z / 5.0) > 0.6) c.set(x, top - 1, z, "cobweb");
                }
            }
        for (int[] lp : new int[][]{{-11, -11}, {11, -11}, {-11, 11}, {11, 11}, {0, 13}})
            Kit.hangingLantern(c, cavX + lp[0], topAir(c, cavX + lp[0], ay, cavZ + lp[1]) + 1, cavZ + lp[1], 2, true);
        // bone piles and egg sacs around the walls
        java.util.Random rr = Hash.random(seed, "sanct");
        for (int i = 0; i < 26; i++) {
            double a = rr.nextDouble() * Math.PI * 2, d = 12 + rr.nextDouble() * 4;
            int x = cavX + (int) Math.round(Math.sin(a) * d), z = cavZ + (int) Math.round(Math.cos(a) * d);
            if (!c.isAir(x, ay + 1, z) || !c.isAir(x, ay + 2, z)) continue;
            if (Math.abs(x - cavX) <= 3 && z > cavZ + 10) continue;          // keep the way in clear
            c.set(x, ay + 1, z, i % 3 == 0 ? "bone_block[axis=y]" : i % 3 == 1 ? "white_wool" : "cobweb");
        }

        // 6g. Top of the Nest, its crown of rock teeth, and spires down the slopes
        nest(c, plateauY, seed);
        crown(c, h, plateauY, spiral.get(n), seed);
        List<int[]> spireTips = spires(c, h, seed, res);
        for (int i = 0; i + 1 < spireTips.size(); i += 2) {               // web sails strung between pairs
            int[] a = spireTips.get(i), b = spireTips.get(i + 1);
            if (Math.hypot(a[0] - b[0], a[2] - b[2]) > 26) continue;
            Shapes.line(c, a[0], a[1], a[2], b[0], b[1], b[2], "cobweb");
        }

        // 6h. the quarry: scaffolds, rails, a hut for the quarryman
        res.reserve("quarry", Box.of(qx - 22, quarryFloor - 2, qz - 22, qx + 22, rimY + 10, qz + 22));
        for (int[] sc : new int[][]{{qx + 12, qz - 10}, {qx + 14, qz + 6}, {qx - 2, qz + 15}}) {
            for (int k = 1; k <= rimY - quarryFloor + 1; k++) c.set(sc[0], quarryFloor + k, sc[1], "scaffolding");
        }
        for (int x = qx - 8; x <= qx + 10; x++) c.set(x, quarryFloor + 1, qz + 9, "rail[shape=east_west]");
        c.set(qx + 10, quarryFloor + 1, qz + 8, "barrel[facing=up,open=false]");
        Builders.crates(c, qx + 6, quarryFloor, qz - 12, seed ^ 11);
        for (int[] lp : new int[][]{{qx - 10, qz - 10}, {qx + 10, qz - 8}, {qx - 8, qz + 11}, {qx + 9, qz + 12}})
            Builders.lampPost(c, lp[0], quarryFloor, lp[1], "post", "north");
        Builders.House shed = new Builders.House(qx - 15, rimY, qz - 22, 6, 5);
        shed.door = "south"; shed.roof = "spruce"; shed.roofStyle = "flat"; shed.wall = Palette.of("spruce_planks:4,cobblestone:1");
        shed.frame = "spruce_log"; shed.seed = seed; shed.furnish = true;
        if (res.reserve("shed", shed.reserve())) shed.build(c);

        // 6i. slime hill: a slick pool on top, moss and slime
        int pond = Terrain.pond(c, h, sx, sz, 5, 2, seed);
        java.util.Random sr = Hash.random(seed, "slime");
        for (int i = 0; i < 40; i++) {
            int x = sx - 17 + sr.nextInt(35), z = sz - 17 + sr.nextInt(35);
            if (h.wet(x, z)) continue;
            int g = Kit.ground(c, x, z, 140, -1);
            if (g < 0 || !c.isAir(x, g + 1, z)) continue;
            c.set(x, g + 1, z, sr.nextInt(6) == 0 ? "slime_block" : "moss_carpet");
        }

        // ---------------------------------------------------------------- 7. roads
        Palette roadFill = Palette.of("coarse_dirt:4,gravel:3,cobblestone:2,andesite:1,dirt_path:2");
        Builders.PathResult paths = Builders.path(c, Kit.polyline(spiral, 6), 5, roadFill, "cobblestone_stairs", null);
        int[][] ring = {
                {0, 102}, {-26, 100}, {-50, 92}, {-61, 88},              // plaza -> camp
                {-90, 72}, {-104, 46}, {-110, 24}, {-111, 9},           // -> cottage door
                {-112, -14}, {-104, -46}, {-90, -72}, {-77, -85},       // -> far side
                {-56, -104}, {-30, -116}, {-9, -118}, {0, -118},        // -> burrow
                {9, -118}, {36, -114}, {66, -98}, {88, -74}, {100, -46}, {92, -22}, {90, -6},  // -> quarry ramp
                {98, 18}, {98, 44}, {84, 60}, {64, 84}, {36, 98}, {12, 102}  // -> slime hill -> plaza
        };
        Builders.path(c, ring, 4, roadFill, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{0, 103}, {0, 92}, {0, 81}}, 5, roadFill, "cobblestone_stairs", paths);       // plaza to the climb
        Builders.path(c, new int[][]{{qx - 30, qz}, {qx - 22, qz}, {qx - 12, qz}, {qx - 2, qz}}, 4, gravel, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-61, 88}, {-68, 88}, {-74, 86}}, 3, roadFill, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-111, 9}, {-113, 8}, {cottage.doorStep[0], cottage.doorStep[2]}}, 3, roadFill, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{-77, -85}, {-76, -88}}, 4, roadFill, "cobblestone_stairs", paths);
        Builders.path(c, new int[][]{{84, 60}, {90, 66}, {sx, sz - 7}}, 3, roadFill, "cobblestone_stairs", paths);
        // lights along both roads, just off the edge
        lampsAlong(c, Kit.polyline(spiral, 2), 16, 4, paths, res);
        lampsAlong(c, ring, 18, 4, paths, res);

        // ---------------------------------------------------------------- 7b. hunting grounds
        // Each band's spiders live in a few compact, open patches beside a road (see HuntingGrounds):
        // a respawn lands where the kill happened, instead of anywhere on a 560-block ring.
        HuntingGrounds.Surface surf = new HuntingGrounds.Surface(c, h, h.box());
        Kit.Keepout groundKeep = (x, z) -> res.covers(x, z, 3) || paths.near(x, z, 2) || inBox(x, z, plaza, 8)
                || inQuarry(x, z, qx, qz) || Math.hypot(x - sx, z - sz) < 26 || nearSpire(spireTips, x, z, 7);
        Box mountainBox = Box.of(CX - (int) R, 0, CZ - (int) R, CX + (int) R, 0, CZ + (int) R);
        Kit.Keepout lowKeep = (x, z) -> groundKeep.blocked(x, z) || Math.hypot(x - CX, z - CZ) > R;
        List<HuntingGrounds.Ground> lowGrounds = HuntingGrounds.choose(c, surf, mountainBox, LOW_MIN_Y, LOW_MAX_Y, LOW_GROUNDS, 64,
                0, plaza.minZ(), 70, 14, lowKeep, paths, "");
        List<HuntingGrounds.Ground> midGrounds = HuntingGrounds.choose(c, surf, mountainBox, MID_MIN_Y, MID_MAX_Y, MID_GROUNDS, 48,
                0, plaza.minZ(), 110, 14, groundKeep, paths, "");
        List<HuntingGrounds.Ground> highGrounds = HuntingGrounds.choose(c, surf, mountainBox, MID_MAX_Y + 1, plateauY - 2, HIGH_GROUNDS, 40,
                0, plaza.minZ(), 400, 14, groundKeep, paths, "");
        List<HuntingGrounds.Ground> allGrounds = new ArrayList<>(lowGrounds);
        allGrounds.addAll(midGrounds);
        allGrounds.addAll(highGrounds);
        Kit.Keepout clearings = (x, z) -> {        // no trees, boulders or webs for a spider to climb into
            for (HuntingGrounds.Ground g : allGrounds) if (g.covers(x, z, HuntingGrounds.CLEAR - HuntingGrounds.RADIUS)) return true;
            return false;
        };

        // ---------------------------------------------------------------- 8. greenery and webs
        Kit.Keepout keep = (x, z) -> res.covers(x, z, 5) || paths.near(x, z, 4) || clearings.blocked(x, z);
        Kit.Keepout treeClear = (x, z) -> {        // tested at the trunk: leave room for the canopy
            for (HuntingGrounds.Ground g : allGrounds) if (g.covers(x, z, HuntingGrounds.CLEAR - HuntingGrounds.RADIUS + 5)) return true;
            return false;
        };
        Trees.forest(c, h, Box.of(-160, 0, -160, 160, 0, 160), seed, 0.022, 5, "dark_oak:4,spruce:4,dead:3,pine:2,mega_spruce:1", 3,
                (x, z) -> res.covers(x, z, 7) || paths.near(x, z, 6) || h.top(x, z) > 104 || Math.hypot(x - CX, z - CZ) < 30
                        || inQuarry(x, z, qx, qz) || Math.hypot(x - sx, z - sz) < 8 || treeClear.blocked(x, z));
        Trees.forest(c, h, Box.of(-120, 0, -120, 120, 0, 120), seed ^ 5, 0.006, 8, "dead:1", 3,
                (x, z) -> res.covers(x, z, 6) || paths.near(x, z, 5) || h.top(x, z) <= 104 || h.top(x, z) >= plateauY - 2
                        || treeClear.blocked(x, z));
        Terrain.scatter(c, h, seed, 0.06, Fill.of("dead_bush"), "coarse_dirt");
        Terrain.scatter(c, h, seed ^ 1, 0.05, Palette.of("short_grass:3,fern:2"), "podzol");
        Terrain.scatter(c, h, seed ^ 2, 0.05, Palette.of("short_grass:3,fern:1,brown_mushroom:1"), "grass_block");
        h.forEach((x, z, top, bottom) -> {                      // webs on the slopes, never on a road
            if (top < 82 || keep.blocked(x, z) || h.wet(x, z)) return;
            if (!c.isAir(x, top + 1, z)) return;
            double chance = top > 104 ? 0.05 : 0.025;
            if (Hash.chance(Hash.salt(seed, "webs"), x, z, chance)) c.set(x, top + 1, z, "cobweb");
        });
        boulders(c, h, seed, keep);

        // ---------------------------------------------------------------- 9. anchors
        layout.standAt("spawn", 0, plazaY, 116, 180f);
        layout.standAt("npc.den_guide", -6, plazaY, 112, 90f);
        layout.standAt("npc.spider_tamer", 7, plazaY, 107, 180f);
        layout.standAt("npc.ike", CRIB_X0 + 1, plazaY, CRIB_Z0 + 1, -90f);          // Fenn, facing east onto the plaza
        layout.standAt("npc.michael", CRIB_X1 - 1, plazaY, CRIB_Z1 - 1, -90f);      // Lark
        layout.standAt("npc.archaeologist", -70, campY, 87, 180f);
        if (cottage.inside != null) layout.standAt("npc.granny", cottage.inside[0], cottage.inside[1], cottage.inside[2], 90f);
        layout.standAt("npc.quarryman", qx - 13, rimY, qz - 14, 90f);
        layout.standAt("npc.crystal_mender", cavX + 6, cavY, cavZ + 10, 180f);
        layout.standAt("altar.arachne", cavX, ay + 1, cavZ + 2, 180f);
        layout.standAt("boss.arachne", cavX, cavY, cavZ - 9, 0f);
        layout.standAt("boss.broodmother", CX, plateauY, CZ, 0f);
        layout.standAt("warp.nest", (int) Math.round(spiral.get(n - 30).x()), plateauY, (int) Math.round(spiral.get(n - 30).z()), 0f);
        layout.standAt("warp.sanctuary", cavX, cavY, cavZ + 14, 180f);
        layout.data("altar.arachne.block", cavX + "," + (ay + 2) + "," + cavZ);

        // mob spawn points. Every one stands on a cell a player can walk to from spawn (checked here
        // with the same flood preview uses), so the walk check no longer skips them. The mountain
        // bands are hunting grounds: spawn.mob.<band>.<ground>.<n>, one rule set per ground
        // (spawns.json), so a ground's kills respawn in that ground.
        LongSet reach = HuntingGrounds.reachable(c, layout);
        putGrounds(layout, c, surf, "spawn.mob.low.", lowGrounds, LOW_MIN_Y, LOW_MAX_Y, lowKeep, reach);
        putGrounds(layout, c, surf, "spawn.mob.mid.", midGrounds, MID_MIN_Y, MID_MAX_Y, groundKeep, reach);
        putGrounds(layout, c, surf, "spawn.mob.high.", highGrounds, MID_MAX_Y + 1, plateauY - 2, groundKeep, reach);
        put(layout, "spawn.mob.nest.", HuntingGrounds.reachableOnly(Kit.spots(c, h, Box.of(CX - 21, 0, CZ - 21, CX + 21, 0, CZ + 21), plateauY - 1, plateauY + 1, 14, 5, seed ^ 3,
                (x, z) -> Math.hypot(x - CX, z - CZ) < 7), reach));
        put(layout, "spawn.mob.gravel.", HuntingGrounds.reachableOnly(Kit.spots(c, h, Box.of(qx - 16, 0, qz - 16, qx + 16, 0, qz + 16), quarryFloor, quarryFloor, 12, 5, seed ^ 4, null), reach));
        put(layout, "spawn.mob.slime.", HuntingGrounds.reachableOnly(Kit.spots(c, h, Box.of(sx - 20, 0, sz - 20, sx + 20, 0, sz + 20), 70, 120, 14, 5, seed ^ 5,
                (x, z) -> paths.near(x, z, 2) || h.wet(x, z)), reach));
        List<int[]> keepers = new ArrayList<>();
        for (int[] ch : new int[][]{{-13, -92}, {13, -84}})
            for (int[] o : new int[][]{{-4, 0}, {4, 0}, {0, -4}, {0, 4}, {-3, 3}})
                keepers.add(new int[]{ch[0] + o[0], burrowY, ch[1] + o[1]});
        put(layout, "spawn.mob.burrow.", keepers);
        Box isl = h.box();

        fairySouls(c, layout, h, seed, plazaY, cottage, campY, farY, burrowY, cavY, plateauY, quarryFloor, rimY, spiral, paths, res);

        // relics: Professor Dunmore's hunt. RELICS skulls on reachable ground, off the roads, out of the
        // structures and clear of every anchor; recorded as data "relics" = x,y,z;... (the skull block).
        // Placed after the fairy souls so nothing later clears a skull. Found by right-click (Relics.java).
        List<int[]> relicSpots = HuntingGrounds.reachableOnly(Kit.spots(c, h, Box.of(-150, 0, -150, 150, 0, 150), 66, plateauY + 2, RELICS * 3, 14,
                seed ^ 0x7E11CL, (x, z) -> paths.near(x, z, 3) || res.covers(x, z, 1)), reach);
        StringBuilder rel = new StringBuilder();
        int rn = 0;
        for (int[] rs : relicSpots) {
            if (rn >= RELICS) break;
            if (!c.isAir(rs[0], rs[1] + 1, rs[2]) || !c.isAir(rs[0], rs[1] + 2, rs[2])) continue;
            boolean nearAnchor = false;
            for (Layout.Anchor an : layout.anchors().values())
                if (Math.abs(an.x() - 0.5 - rs[0]) <= 2 && Math.abs(an.z() - 0.5 - rs[2]) <= 2) { nearAnchor = true; break; }
            if (nearAnchor) continue;
            c.set(rs[0], rs[1] + 1, rs[2], "skeleton_skull[rotation=" + Math.floorMod(rs[0] * 7 + rs[2] * 3, 16) + "]");
            rel.append(rel.length() == 0 ? "" : ";").append(rs[0]).append(',').append(rs[1] + 1).append(',').append(rs[2]);
            rn++;
        }
        layout.data("relics", rel.toString());

        // ---------------------------------------------------------------- 10. zones and areas
        Box all = new Box(isl.minX(), 0, isl.minZ(), isl.maxX(), 255, isl.maxZ());
        layout.zone("spiders_den", "Spider's Den", 'c', all, null);
        layout.zone("spider_mound", "Spider Mound", 'c', new Box(CX - 100, 60, CZ - 100, CX + 100, plateauY - 3, CZ + 100), null);
        layout.zone("top_of_the_nest", "Top of the Nest", '4', new Box(CX - 30, plateauY - 2, CZ - 30, CX + 30, 255, CZ + 30), null);
        layout.zone("den_entrance", "Den Entrance", 'a', new Box(plaza.minX() - 3, plazaY - 4, plaza.minZ() - 2, plaza.maxX() + 3, plazaY + 14, plaza.maxZ() + 6), null, Layout.Zone.SAFE);
        layout.zone("fenns_crib", "Fenn's Crib", 'a', new Box(CRIB_X0, plazaY - 1, CRIB_Z0, CRIB_X1, plazaY + 5, CRIB_Z1), null, Layout.Zone.SAFE);
        layout.zone("gravel_mines", "Gravel Mines", '7', new Box(qx - 26, quarryFloor - 3, qz - 26, qx + 26, rimY + 14, qz + 26), null);
        layout.zone("grannys_cottage", "Granny's Cottage", 'e', new Box(-134, cottageY - 4, -4, -108, cottageY + 16, 22), null, Layout.Zone.SAFE);
        layout.zone("archaeologists_camp", "Archaeologist's Camp", 'e', new Box(campPlot.minX() - 2, campY - 4, campPlot.minZ() - 2, campPlot.maxX() + 2, campY + 12, campPlot.maxZ() + 2), null, Layout.Zone.SAFE);
        layout.zone("slime_hill", "Slime Hill", 'a', new Box(sx - 24, 60, sz - 24, sx + 24, 140, sz + 24), null);
        layout.zone("the_far_side", "The Far Side", '5', new Box(farPlot.minX() - 4, farY - 4, farPlot.minZ() - 4, farPlot.maxX() + 4, farY + 14, farPlot.maxZ() + 4), null, Layout.Zone.SAFE);
        layout.zone("silkmothers_burrow", "Silkmother's Burrow", '5', new Box(-25, burrowY - 3, -126, 25, burrowY + 12, -70), null);
        layout.zone("silkmothers_sanctuary", "Silkmother's Sanctuary", 'd', new Box(cavX - 22, cavY - 3, cavZ - 22, cavX + 22, cavY + 18, cavZ + 20), null);
        layout.area("nest_top", new Box(CX - 21, plateauY + 1, CZ - 21, CX + 21, plateauY + 3, CZ + 21));
        layout.area("sanctuary", new Box(cavX - 16, cavY + 1, cavZ - 16, cavX + 16, cavY + 3, cavZ + 16));
        layout.area("gravel_mines", new Box(qx - 16, quarryFloor + 1, qz - 16, qx + 16, quarryFloor + 3, qz + 16));
        layout.area("slime_hill", new Box(sx - 20, BASE_INT - 4, sz - 20, sx + 20, 120, sz + 20));
        layout.area("gravel.blocks", new Box(qx - 21, quarryFloor - 1, qz - 21, qx + 21, rimY + 1, qz + 21));
        layout.data("plateauY", String.valueOf(plateauY));
        layout.data("reservations", res.summary());
    }

    private static final int BASE_INT = (int) BASE;

    static boolean inQuarry(int x, int z, int qx, int qz) { return Math.hypot(x - qx, z - qz) <= 21.5; }

    static boolean inBox(int x, int z, Box b, int m) {
        return x >= b.minX() - m && x <= b.maxX() + m && z >= b.minZ() - m && z <= b.maxZ() + m;
    }

    /** Low band: feet y 77..100 (ground 76..99). Mid 100..117. High from 118 to just under the plateau. */
    static final int LOW_MIN_Y = 76, LOW_MAX_Y = 99, MID_MIN_Y = 100, MID_MAX_Y = 117;
    /** Ground counts per band. spawns.json names rules per ground (low a-d, mid a-c, high a-b): keep them in step. */
    static final int LOW_GROUNDS = 4, MID_GROUNDS = 3, HIGH_GROUNDS = 2;
    /** Anchors per ground. */
    static final int GROUND_ANCHORS = 12;

    /** Every hunting-ground anchor prefix the spawn rules name ("spawn.mob.low.a." ...). */
    public static List<String> groundPrefixes() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < LOW_GROUNDS; i++) out.add("spawn.mob.low." + (char) ('a' + i) + ".");
        for (int i = 0; i < MID_GROUNDS; i++) out.add("spawn.mob.mid." + (char) ('a' + i) + ".");
        for (int i = 0; i < HIGH_GROUNDS; i++) out.add("spawn.mob.high." + (char) ('a' + i) + ".");
        return out;
    }

    static void putGrounds(Layout layout, VoxelCanvas c, HuntingGrounds.Surface surf, String prefix, List<HuntingGrounds.Ground> grounds,
                           int minY, int maxY, Kit.Keepout keep, LongSet reach) {
        for (HuntingGrounds.Ground g : grounds) {
            put(layout, prefix + g.id() + ".", HuntingGrounds.spots(c, surf, g, minY, maxY, GROUND_ANCHORS, 4, keep, reach));
            layout.data("ground." + prefix.substring("spawn.mob.".length()) + g.id(), g.x() + "," + g.z() + "," + HuntingGrounds.RADIUS + "," + g.score() + "," + g.cliffs());
        }
    }

    static boolean nearSpire(List<int[]> tips, int x, int z, int r) {
        for (int[] t : tips) if (Math.hypot(t[0] - x, t[2] - z) < r) return true;
        return false;
    }

    static void put(Layout layout, String prefix, List<int[]> pts) {
        int i = 1;
        for (int[] p : pts) layout.standAt(prefix + (i++), p[0], p[1], p[2], 0f);
    }

    /** y of the highest air cell above a cave floor at (x, floorY, z) — i.e. just under the ceiling. */
    static int topAir(VoxelCanvas c, int x, int floorY, int z) {
        int y = floorY + 1;
        while (y < floorY + 40 && c.isAir(x, y + 1, z)) y++;
        return y;
    }

    /** A canvas tent on ground y: an A-frame of wool, 5 long, open at the south end. */
    static void tent(VoxelCanvas c, int x, int y, int z, String wool, String trim) {
        for (int dz = 0; dz < 5; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int hgt = 3 - Math.abs(dx);
                for (int k = 1; k <= hgt; k++) {
                    boolean shell = Math.abs(dx) == 2 || k == hgt;
                    boolean back = dz == 0;
                    if (shell || back) c.set(x + dx, y + k, z + dz, Math.abs(dx) == 2 && k == 1 ? trim : wool);
                    else c.set(x + dx, y + k, z + dz, "air");
                }
            }
            c.set(x, y, z + dz, "spruce_planks");
        }
        c.set(x - 1, y + 1, z + 1, "white_bed[facing=south,part=head]");
        c.set(x - 1, y + 1, z + 2, "white_bed[facing=south,part=foot]");
        c.set(x + 1, y + 1, z + 1, "barrel[facing=up,open=false]");
        c.set(x, y + 1, z + 5, "spruce_fence");
        c.set(x, y + 2, z + 5, "lantern[hanging=false,waterlogged=false]");
    }

    /** The Broodmother's nest: a ring of tangled logs, egg sacs, webs and bones on the summit. */
    static void nest(VoxelCanvas c, int y, long seed) {
        java.util.Random r = Hash.random(seed, "nest");
        Noise n = new Noise(Hash.salt(seed, "nestring"));
        // a broken ring of dark logs
        for (int a = 0; a < 360; a += 3) {
            double rad = Math.toRadians(a);
            if (Math.sin(rad * 3 + 0.4) > 0.82) continue;                 // three gaps to walk through
            double d = 17 + n.noise2(a / 20.0, 0) * 1.5;
            int x = CX + (int) Math.round(Math.sin(rad) * d), z = CZ + (int) Math.round(Math.cos(rad) * d);
            int hgt = 1 + (int) Math.round(1.5 + n.noise2(a / 9.0, 3) * 1.5);
            char axis = Math.abs(Math.sin(rad)) > Math.abs(Math.cos(rad)) ? 'z' : 'x';
            for (int k = 1; k <= hgt; k++) c.set(x, y + k, z, "dark_oak_log[axis=" + axis + "]");
            if (hgt >= 3 && a % 9 == 0) c.set(x, y + hgt + 1, z, "cobweb");
        }
        // egg sacs and bones inside
        for (int i = 0; i < 18; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 9;
            int x = CX + (int) Math.round(Math.sin(a) * d), z = CZ + (int) Math.round(Math.cos(a) * d);
            if (!c.isAir(x, y + 1, z)) continue;
            switch (i % 4) {
                case 0 -> { c.set(x, y + 1, z, "white_wool"); c.set(x, y + 2, z, "cobweb"); }
                case 1 -> c.set(x, y + 1, z, "bone_block[axis=" + (r.nextBoolean() ? 'x' : 'z') + "]");
                case 2 -> c.set(x, y + 1, z, "cobweb");
                default -> c.set(x, y + 1, z, "skeleton_skull[rotation=" + r.nextInt(16) + "]");
            }
        }
        // a dead tree hung with silk as the summit landmark, behind the boss spot
        int tx = CX + 9, tz = CZ - 9;
        for (int k = 1; k <= 11; k++) c.set(tx, y + k, tz, "dark_oak_log[axis=y]");
        int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 4; i++) {
            int ly = y + 6 + i;
            for (int s = 1; s <= 3; s++) {
                int ax = tx + arms[i][0] * s, az = tz + arms[i][1] * s;
                c.set(ax, ly, az, "dark_oak_log[axis=" + (arms[i][0] != 0 ? 'x' : 'z') + "]");
                c.set(ax, ly - 1, az, "cobweb");
            }
        }
        c.set(tx, y + 12, tz, "cobweb");
        for (int[] l : new int[][]{{-12, -4}, {12, 4}, {-4, 12}, {5, -13}}) {
            int x = CX + l[0], z = CZ + l[1];
            c.set(x, y + 1, z, "polished_blackstone_wall");
            c.set(x, y + 2, z, "polished_blackstone_wall");
            c.set(x, y + 3, z, "soul_lantern[hanging=false,waterlogged=false]");
        }
    }


    /** Jagged rock teeth around the summit plateau, leaving the road's arrival open. */
    static void crown(VoxelCanvas c, Terrain.Heightmap h, int y, Kit.RoadPoint roadEnd, long seed) {
        java.util.Random r = Hash.random(seed, "crown");
        Palette rock = Palette.of("deepslate:3,tuff:3,cobbled_deepslate:2,blackstone:1");
        double gap = Math.atan2(roadEnd.x() - CX, roadEnd.z() - CZ);
        for (int i = 0; i < 22; i++) {
            double a = i / 22.0 * Math.PI * 2 + r.nextDouble() * 0.15;
            double da = Math.abs(Math.atan2(Math.sin(a - gap), Math.cos(a - gap)));
            if (da < 0.45) continue;
            double d = 25 + r.nextDouble() * 3;
            int x = CX + (int) Math.round(Math.sin(a) * d), z = CZ + (int) Math.round(Math.cos(a) * d);
            int g = Builders.ground(c, x, z, 220, 60);
            if (g == Integer.MIN_VALUE) continue;
            Kit.spike(c, x, z, 1.6 + r.nextDouble() * 1.4, g + 5 + r.nextInt(8), rock);
        }
    }

    /** Six to eight jagged spires on the slopes; returns their tips {x,y,z}. */
    static List<int[]> spires(VoxelCanvas c, Terrain.Heightmap h, long seed, Reservations res) {
        java.util.Random r = Hash.random(seed, "spires");
        Palette rock = Palette.of("stone:3,andesite:2,tuff:3,cobblestone:1,deepslate:1");
        List<int[]> tips = new ArrayList<>();
        double[][] at = {{0.3, 62}, {1.2, 70}, {2.0, 55}, {2.6, 78}, {3.5, 64}, {4.3, 58}, {5.1, 74}, {5.8, 66}};
        for (double[] a : at) {
            int x = CX + (int) Math.round(Math.sin(a[0]) * a[1]), z = CZ + (int) Math.round(Math.cos(a[0]) * a[1]);
            if (res.covers(x, z, 6)) continue;
            int g = Builders.ground(c, x, z, 220, 50);
            if (g == Integer.MIN_VALUE) continue;
            int hgt = 13 + r.nextInt(12);
            double rad = 2.6 + r.nextDouble() * 1.6;
            Kit.spike(c, x, z, rad, g + hgt, rock);
            Kit.spike(c, x + 1, z - 1, rad * 0.55, g + hgt + 4, rock);
            tips.add(new int[]{x, g + hgt - 3, z});
        }
        return tips;
    }

    /** Webs on the walls of a tunnel/cave system: air cells next to solid rock, by chance. */
    static void webWalls(VoxelCanvas c, Box b, long seed, double chance) {
        long s = Hash.salt(seed, "webwalls");
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int y = b.minY() + 3; y <= b.maxY(); y++) {
                    if (!c.isAir(x, y, z) || !Hash.chance(s, x, y, z, chance)) continue;
                    if (!c.isAir(x, y + 1, z) && (!c.isAir(x + 1, y, z) || !c.isAir(x - 1, y, z) || !c.isAir(x, y, z + 1) || !c.isAir(x, y, z - 1)))
                        c.set(x, y, z, "cobweb");
                }
    }

    /** Soul lamp posts beside a road every {@code every} blocks, {@code off} blocks to its side. */
    static void lampsAlong(VoxelCanvas c, int[][] pts, int every, int off, Builders.PathResult paths, Reservations res) {
        lampsAlongStyle(c, pts, every, off, paths, res, false);
    }

    static void lampsAlongStyle(VoxelCanvas c, int[][] pts, int every, int off, Builders.PathResult paths, Reservations res, boolean end) {
        double acc = 0;
        boolean left = true;
        for (int i = 0; i + 1 < pts.length; i++) {
            double dx = pts[i + 1][0] - pts[i][0], dz = pts[i + 1][1] - pts[i][1];
            double len = Math.hypot(dx, dz);
            if (len < 0.01) continue;
            for (double s = 0; s < len; s += 1) {
                acc += 1;
                if (acc < every) continue;
                int px = (int) Math.round(pts[i][0] + dx * s / len), pz = (int) Math.round(pts[i][1] + dz * s / len);
                double nx = -dz / len, nz = dx / len;
                int sgn = left ? 1 : -1;
                int lx = (int) Math.round(px + nx * off * sgn), lz = (int) Math.round(pz + nz * off * sgn);
                if (paths.covers(lx, lz) || res.covers(lx, lz, 1)) continue;
                int g = Builders.ground(c, lx, lz, 200, 20);
                if (g == Integer.MIN_VALUE || !c.isAir(lx, g + 1, lz) || !c.isAir(lx, g + 2, lz)) continue;
                int pathY = paths.covers(px, pz) ? paths.height(px, pz) : g;
                if (Math.abs(g - pathY) > 3) continue;
                if (end) EndGenerator.endLamp(c, lx, g, lz);
                else Builders.lampPost(c, lx, g, lz, "soul", Blocks.dirOf((int) Math.round(-nx * sgn * 10), (int) Math.round(-nz * sgn * 10)));
                acc = 0;
                left = !left;
            }
        }
    }

    /** Mossy boulders half-buried in the slopes. */
    static void boulders(VoxelCanvas c, Terrain.Heightmap h, long seed, Kit.Keepout keep) {
        java.util.Random r = Hash.random(seed, "boulders");
        Palette rock = Palette.of("cobblestone:3,mossy_cobblestone:3,andesite:2,tuff:2,stone:1");
        for (int i = 0; i < 70; i++) {
            int x = -140 + r.nextInt(281), z = -130 + r.nextInt(261);
            if (!h.has(x, z) || keep.blocked(x, z) || !h.inland(x, z, 4)) continue;
            int t = h.top(x, z);
            double rad = 1.4 + r.nextDouble() * 1.6;
            Shapes.sphere(c, x, t + rad * 0.3, z, rad, rock);
        }
    }

    /** 19 hidden souls: ledges, corners and the backs of things, all reachable on foot. */
    static void fairySouls(VoxelCanvas c, Layout layout, Terrain.Heightmap h, long seed, int plazaY, Builders.House cottage,
                           int campY, int farY, int burrowY, int cavY, int plateauY, int quarryFloor, int rimY,
                           List<Kit.RoadPoint> spiral, Builders.PathResult paths, Reservations res) {
        List<int[]> souls = new ArrayList<>();
        souls.add(new int[]{11, plazaY, 121});                                    // plaza corner behind the crates
        souls.add(new int[]{-131, Kit.ground(c, -131, 18, 140, 70), 18});        // behind Granny's cottage
        souls.add(new int[]{-86, campY, 97});                                     // camp, behind the tents
        souls.add(new int[]{-87, farY, -86});                                     // far side corner
        souls.add(new int[]{-20, burrowY, -95});                                  // burrow west chamber, far wall
        souls.add(new int[]{20, burrowY, -82});                                   // burrow east chamber
        souls.add(new int[]{14, cavY, -66});                                      // sanctuary, behind the pillar line
        souls.add(new int[]{-15, cavY, -46});                                     // sanctuary, west wall
        souls.add(new int[]{CX - 15, plateauY, CZ + 8});                         // inside the nest ring
        souls.add(new int[]{CX + 10, plateauY, CZ - 11});                        // at the dead tree's roots
        souls.add(new int[]{118 + 15, quarryFloor, -6 + 5});                     // quarry floor, east corner
        souls.add(new int[]{118 - 4, quarryFloor, -6 - 15});                     // quarry floor, north corner
        souls.add(new int[]{92 + 6, Kit.ground(c, 98, 80, 140, 80), 80});        // slime hill by the pool
        // three on the climb, a few blocks off the road on the downhill side
        for (double t : new double[]{0.25, 0.55, 0.8}) {
            Kit.RoadPoint p = spiral.get((int) (t * (spiral.size() - 1)));
            double dx = p.x() - CX, dz = p.z() - CZ, d = Math.hypot(dx, dz);
            int x = (int) Math.round(p.x() + dx / d * 6), z = (int) Math.round(p.z() + dz / d * 6);
            souls.add(new int[]{x, Kit.ground(c, x, z, 200, (int) p.y()), z});
        }
        // three out in the wilds near the coast
        for (int[] w : new int[][]{{-40, -122}, {128, 40}, {-120, -50}}) {
            int g = Kit.ground(c, w[0], w[1], 200, -1);
            if (g > 0) souls.add(new int[]{w[0], g, w[1]});
        }
        int i = 1;
        for (int[] s : souls) {
            if (s[1] < 0) continue;
            // make sure the cell is free (move off a web or plant)
            for (int k = 1; k <= 2; k++) if (!c.isAir(s[0], s[1] + k, s[2])) c.set(s[0], s[1] + k, s[2], "air");
            layout.standAt("fairy_soul." + (i++), s[0], s[1], s[2], 0f);
        }
    }
}
