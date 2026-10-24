package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * Island-wide passes: the roads that tie the districts together (with lamps), the sewer under the
 * village, forests and meadows, boulders and bushes, the leftover fairy souls and the island zone.
 */
final class HubWorld {

    private HubWorld() {}

    static final Palette ROAD = Palette.of("dirt_path:6,gravel:2,coarse_dirt:2,cobblestone:1");

    static void roads(HubCtx g) {
        int[][][] routes = {
                {{-97, 0}, {-104, 0}},                                             // village -> portal plaza
                {{-173, 0}, {-183, -4}, {-191, -9}},                               // portal plaza -> foraging camp
                {{0, -99}, {-22, -108}, {-52, -113}, {-80, -120}},                 // village -> colosseum
                {{-108, -145}, {-126, -138}, {-143, -129}},                        // colosseum -> ruins
                {{50, -99}, {57, -100}, {65, -100}},                               // village -> mining district
                {{99, 0}, {118, -10}, {139, -24}},                                 // village -> graveyard
                {{189, 29}, {186, 60}, {178, 95}},                                 // graveyard -> farm
                {{50, 99}, {78, 104}, {100, 106}},                                 // village -> farm gate
                {{0, 99}, {12, 114}, {30, 126}, {38, 134}},                        // village -> fishing outpost
                {{-50, 99}, {-70, 112}, {-91, 126}},                               // village -> wizard tower
                {{-100, 133}, {-128, 150}, {-160, 168}},                           // wizard tower -> dark auction
                {{-162, 182}, {-142, 188}, {-125, 192}},                           // dark auction -> hedge witch
                {{-137, 45}, {-126, 80}, {-108, 110}},                             // portal plaza -> wilderness
                {{-30, 126}, {-12, 140}, {-2, 148}},                               // wizard road -> lake shore
                {{127, -100}, {148, -97}, {165, -97}},                             // mining district -> old keep
                {{44, 162}, {54, 180}, {62, 197}},                                 // fishing outpost -> carnival
        };
        for (int[][] r : routes) {
            g.road(r, 4, ROAD);
            // lamps every ~18 blocks beside the road
            double acc = 0;
            for (int i = 0; i + 1 < r.length; i++) {
                double len = Math.hypot(r[i + 1][0] - r[i][0], r[i + 1][1] - r[i][1]);
                for (double t = 0; t < len; t += 1) {
                    acc += 1;
                    if (acc < 18) continue;
                    acc = 0;
                    double fx = r[i][0] + (r[i + 1][0] - r[i][0]) * t / len, fz = r[i][1] + (r[i + 1][1] - r[i][1]) * t / len;
                    double nx = -(r[i + 1][1] - r[i][1]) / len, nz = (r[i + 1][0] - r[i][0]) / len;
                    int lx = (int) Math.round(fx + nx * 4), lz = (int) Math.round(fz + nz * 4);
                    if (g.roads.covers(lx, lz) || g.res.covers(lx, lz, 1) || g.lampNear(lx, lz, 6)) continue;
                    g.lamp(lx, lz, "post", "north");
                }
            }
        }
    }

    /** Where a road cuts into a slope, open the cut to the sky: no overhangs or stray blocks over roads. */
    static void openCuts(HubCtx g) {
        g.roads.cells.forEach((k, y) -> {
            int x = (int) (k >> 32), z = (int) k;
            for (int a = 4; a <= 30; a++) {
                if (g.c.isAir(x, y + a, z)) break;
                if (!com.skyclone.gen.Builders.natural(g.c.get(x, y + a, z))) break;
                g.c.set(x, y + a, z, "air");
            }
        });
    }

    /** Carving pass: the sewer under the village's east side. */
    static void sewer(HubCtx g) {
        int V = g.V, F = V - 7;
        Palette wall = Palette.of("bricks:3,mossy_stone_bricks:2,stone_bricks:2,cracked_stone_bricks:1");
        // the stair down (north from z = -11)
        for (int k = 1; k <= 7; k++) {
            int z = -10 - k, y = V - k;
            for (int x = 86; x <= 90; x++)
                for (int yy = y - 1; yy <= y + 5; yy++) {
                    boolean side = x == 86 || x == 90;
                    if (side || yy == y - 1) { if (yy < V || side && yy == V + 1) g.c.set(x, yy, z, side && yy == V + 1 ? "stone_brick_wall" : wall.at(x, yy, z)); }
                    else if (yy == y) g.c.set(x, yy, z, Blocks.stairs("stone_brick", "south"));
                    else if (yy <= V + 4) g.c.set(x, yy, z, "air");
                }
        }
        // A rail round the open stairwell, open only at the south end where the stair starts. The hole is
        // z -11..-15 (from k = 6 the street is a roof over the stair). The rail used to be only what the
        // loop above leaves (the first four steps' sides) plus a cross rail at z -18, two blocks past the
        // hole: the last open row, z -15, had no rail on either side and nothing on its north edge, so
        // walking from the Graveyard to the Library the player stepped off the street into a 6-block drop
        // (playtest "loops" 2026-10-03). Now both sides and the north edge sit right on the hole.
        for (int z = -16; z <= -11; z++) {
            g.c.set(86, V + 1, z, "stone_brick_wall");
            g.c.set(90, V + 1, z, "stone_brick_wall");
        }
        for (int x = 86; x <= 90; x++) g.c.set(x, V + 1, -16, "stone_brick_wall");
        // the tunnel north with a water channel down the middle
        for (int z = -18; z >= -42; z--)
            for (int x = 85; x <= 91; x++)
                for (int y = F - 1; y <= F + 5; y++) {
                    boolean in = x >= 86 && x <= 90 && y >= F + 1 && y <= F + 4;
                    if (in) { g.c.set(x, y, z, "air"); continue; }
                    if (y == F && x == 88) { g.c.set(x, y, z, "water"); continue; }
                    if (y == F - 1 && x == 88) { g.c.set(x, y, z, wall.at(x, y, z)); continue; }
                    g.c.set(x, y, z, y == F ? "stone_bricks" : wall.at(x, y, z));
                }
        for (int z = -20; z >= -42; z -= 6) g.c.set(88, F + 4, z, Blocks.lantern(true));
        for (int z = -24; z >= -40; z -= 8) { g.c.set(86, F + 1, z, "barrel[facing=up,open=false]"); }
        g.c.set(89, F + 1, -42, "chest[facing=south,type=single,waterlogged=false]");
        g.soul(87, F, -41);
        g.area("sewer", Box.of(86, F + 1, -42, 90, F + 3, -18));
        g.zone("sewer", "Sewer", '8', Box.of(85, F - 1, -43, 91, F + 5, -17));
    }

    static void nature(HubCtx g) {
        java.util.function.BiPredicate<Integer, Integer> keep = (x, z) -> g.treeBlocked(x, z, 4) || VILLAGE.grow(4).containsXZ(x, z)
                || PORTALS.grow(6).containsXZ(x, z) || !safe(x, z);
        // the Forest (foraging: oak and birch, thick)
        Box forest = Box.of(-268, 0, -122, -176, 0, 110);
        Trees.forest(g.c, g.h, forest, g.seed + 1001, 0.07, 5, "oak:5,birch:3,big_oak:2", 4, keep);
        // the Wilderness (dark oak and spruce with mushrooms)
        Box wild = Box.of(-250, 0, 96, -60, 0, 250);
        Trees.forest(g.c, g.h, wild, g.seed + 1002, 0.05, 6, "dark_oak:4,spruce:3,mega_spruce:1,birch:1", 4,
                (x, z) -> keep.test(x, z) || Math.hypot(x - LAKE_X, z - LAKE_Z) < LAKE_R + 8);
        Palette under = Palette.of("red_mushroom:1,brown_mushroom:2,fern:4,dead_bush:1");
        for (int z = wild.minZ(); z <= wild.maxZ(); z++)
            for (int x = wild.minX(); x <= wild.maxX(); x++) {
                if (g.rnd("wildfloor", x, z) > 0.03 || keep.test(x, z) || !g.h.has(x, z) || g.h.wet(x, z)) continue;
                int y = g.h.top(x, z);
                if (!BlockState.name(g.c.get(x, y, z)).equals("grass_block") || !g.c.isAir(x, y + 1, z)) continue;
                g.c.set(x, y, z, g.rnd("podzol", x, z) < 0.5 ? "podzol" : "grass_block");
                g.c.set(x, y + 1, z, under.at(x, y + 1, z));
            }
        // spruce up the mountain's lower slopes
        Box mtn = Box.of(MX - MR, 0, MZ - MR, MX + MR, 0, MZ + MR);
        Trees.forest(g.c, g.h, mtn, g.seed + 1004, 0.016, 7, "spruce:3,pine:2,birch:1", 4,
                (x, z) -> keep.test(x, z) || g.h.top(x, z) > 140 || MINING.grow(6).containsXZ(x, z));
        // the ruins hill: birch and dark oak
        Box rh = Box.of(RUINS_X - 45, 0, RUINS_Z - 45, RUINS_X + 45, 0, RUINS_Z + 45);
        Trees.forest(g.c, g.h, rh, g.seed + 1005, 0.02, 7, "birch:3,dark_oak:1,oak:1", 4, keep);
        // meadows everywhere else: a scattering of trees and azalea bushes
        Box all = Box.of(-ISLAND_R, 0, -ISLAND_R, ISLAND_R, 0, ISLAND_R);
        Trees.forest(g.c, g.h, all, g.seed + 1006, 0.0045, 11, "oak:4,birch:3,big_oak:1,azalea:2,cherry:1", 6,
                (x, z) -> keep.test(x, z) || forest.containsXZ(x, z) || wild.containsXZ(x, z) || mtn.containsXZ(x, z) || rh.containsXZ(x, z));
        // boulders
        for (int z = -ISLAND_R; z <= ISLAND_R; z += 7)
            for (int x = -ISLAND_R; x <= ISLAND_R; x += 7) {
                if (g.rnd("boulder", x, z) > 0.035 || keep.test(x, z) || g.h.wet(x, z) || !g.h.inland(x, z, 4)) continue;
                int y = g.h.top(x, z);
                if (y == Terrain.Heightmap.NONE || !BlockState.name(g.c.get(x, y, z)).equals("grass_block")) continue;
                double r = 1.3 + g.rnd("boulder-r", x, z) * 1.6;
                Shapes.ellipsoid(g.c, x, y + 0.4, z, r, r * 0.75, r, Palette.of("mossy_cobblestone:3,stone:3,andesite:2,cobblestone:1"), 0);
            }
        Terrain.scatter(g.c, g.h, g.seed + 1007, 0.004, Palette.of("azalea:2,flowering_azalea:1,sweet_berry_bush[age=3]:1"), "grass_block");
        // the forest's trees count for foraging
        g.area("forest", Box.of(forest.minX(), g.V - 20, forest.minZ(), forest.maxX(), g.V + 60, forest.maxZ()));
        g.zone("forest", "Forest", '2', Box.of(forest.minX(), g.V - 30, forest.minZ(), forest.maxX(), g.V + 80, forest.maxZ()));
    }

    /** The remaining hidden fairy souls: lawns behind village buildings and ledges near the coast. */
    static void souls(HubCtx g) {
        int[][] village = {{-47, -48}, {47, -48}, {48, 46}, {-47, 47}, {97, -47}, {-97, -47}, {97, 47}, {-6, -97}, {36, 97}};
        for (int[] p : village) hiddenSoul(g, p[0], p[1], 4);
        hiddenSoul(g, PORTALS.minX() + 2, PORTALS.minZ() + 2, 2);
        for (int a = 0; a < 6; a++) {
            double ang = Math.toRadians(25 + a * 60);
            int x = (int) Math.round(Math.cos(ang) * 262), z = (int) Math.round(Math.sin(ang) * 262);
            hiddenSoul(g, x, z, 8);
        }
    }

    /**
     * Wave 3: the reference hub hides 80 fairy souls (research 03 section 4.2) and ours had 42. Tops the
     * count up to {@code target} with souls scattered over the open country, each at least 30 blocks from
     * every other soul, in a seeded order so the layout is stable. Runs after {@link #souls}, so the
     * souls players have already found keep their numbers (1..42 are unchanged).
     */
    static void moreSouls(HubCtx g, int target) {
        List<double[]> have = new ArrayList<>();
        for (Map.Entry<String, com.skyclone.api.world.gen.Layout.Anchor> e : g.layout.anchors().entrySet())
            if (e.getKey().startsWith("fairy_soul.")) have.add(new double[]{e.getValue().x(), e.getValue().z()});
        List<int[]> cand = new ArrayList<>();
        for (int gz = -288; gz <= 288; gz += 18)
            for (int gx = -288; gx <= 288; gx += 18)
                if (gx * gx + gz * gz <= (ISLAND_R - 16) * (ISLAND_R - 16)) cand.add(new int[]{gx, gz});
        cand.sort((a, b) -> Double.compare(g.rnd("soul.more", a[0], a[1]), g.rnd("soul.more", b[0], b[1])));
        for (int[] c : cand) {
            if (g.soulCount() >= target) return;
            if (near(have, c[0], c[1], 30)) continue;
            int before = g.soulCount();
            hiddenSoul(g, c[0], c[1], 5);
            if (g.soulCount() > before) {
                com.skyclone.api.world.gen.Layout.Anchor a = g.layout.anchor("fairy_soul." + g.soulCount());
                have.add(new double[]{a.x(), a.z()});
            }
        }
    }

    private static boolean near(List<double[]> pts, double x, double z, double r) {
        for (double[] p : pts) if ((p[0] - x) * (p[0] - x) + (p[1] - z) * (p[1] - z) < r * r) return true;
        return false;
    }

    /** A soul on open ground near (x, z): not in a building, not on a road. */
    static void hiddenSoul(HubCtx g, int x, int z, int search) {
        for (int r = 0; r <= search; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int px = x + dx, pz = z + dz;
                    if (!g.h.has(px, pz) || g.h.wet(px, pz) || g.res.covers(px, pz, 0) || g.roads.covers(px, pz)) continue;
                    int y = g.ground(px, pz);
                    if (!g.c.isAir(px, y + 2, pz)) continue;
                    String top = BlockState.name(g.c.get(px, y, pz));
                    if (top.endsWith("_leaves") || top.endsWith("_log")) continue;
                    g.soul(px, y, pz);
                    return;
                }
    }

    /**
     * Fast-travel points inside each district ({@code warp.<zoneId>}); the hub module registers a
     * sub-warp for each, unlocked once the player has walked into that zone.
     */
    static void warps(HubCtx g) {
        openSpot(g, "warp.portal_plaza", PORTALS.centerX() + 6, 6);
        openSpot(g, "warp.mining_district", 92, -100);
        g.npc("warp.coal_mine", 77, g.miningY, -140, 180f);
        g.npc("warp.summit", MX, g.summitY, MZ + 6, 180f);
        openSpot(g, "warp.graveyard", GRAVEYARD.minX() + 4, HubEast.GATE_Z);
        g.npc("warp.crypt", HubEast.MAUS_X + 1 + (g.graveY - HubEast.cryptFloor(g)) + HubEast.LANDING_WARP, HubEast.cryptFloor(g), HubEast.MAUS_Z + 5, -90f);
        openSpot(g, "warp.farm", 106, 100);
        openSpot(g, "warp.fishing_outpost", 40, 152);
        // Wave-4 playtest: these two landed facing a wall at arm's length (the tower; the hall's side), and
        // /warp da landed outside the Dark Auction's SAFE zone. Chosen by Landing now: inside the district's
        // SAFE zone, stood back, facing the landmark (the tower; the hall's door).
        // the door side (east) of the plot only: the north-west corner is the Rift arch and its Warden
        Box doorSide = Box.of(WIZARD.centerX() + HubSouth.WIZARD_TOWER_R + 2, 0, WIZARD.minZ(), WIZARD.maxX(), 0, WIZARD.maxZ());
        Landing.place(g, "warp.wizard_tower", doorSide, WIZARD.maxX() - 1, WIZARD.centerZ(),
                WIZARD.centerX() + 0.5, WIZARD.centerZ() + 0.5, Landing.WIZARD_REACH, Landing.WIZARD_GAP);
        int[] ds = g.daDoorStep;
        Landing.place(g, "warp.dark_auction", g.daSafe, ds[0] + 7, ds[2], ds[0] + 0.5, ds[2] + 0.5, 0.5, Landing.DA_GAP);
        openSpot(g, "warp.foraging_camp", CAMP.maxX() - 4, CAMP.centerZ());
        g.npc("warp.colosseum", COLOSSEUM.centerX(), g.colY, COLOSSEUM.centerZ() + 4, 180f);
        openSpot(g, "warp.ruins", RUINS_X, RUINS_Z + 9);
        openSpot(g, "warp.castle", CASTLE.centerX(), CASTLE.maxZ() - 10);
        openSpot(g, "warp.carnival", CARNIVAL.centerX(), CARNIVAL.centerZ());
        openSpot(g, "warp.forest", -200, 40);
        openSpot(g, "warp.wilderness", -150, 120);
        openSpot(g, "warp.museum", 9, 32);
    }

    /** An anchor on open ground near (x, z) (roads allowed, buildings and water not). */
    static void openSpot(HubCtx g, String name, int x, int z) {
        for (int r = 0; r <= 10; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int px = x + dx, pz = z + dz;
                    if (!g.h.has(px, pz) || g.h.wet(px, pz)) continue;
                    int y = g.ground(px, pz);
                    if (Math.abs(y - g.h.top(px, pz)) > 3) continue;
                    boolean open = true;
                    for (int yy = y + 1; yy <= y + 10 && open; yy++)
                        if (com.skyclone.gen.BlockInfo.of(g.c.get(px, yy, pz)).top16 != 0) open = false;
                    if (!open) continue;
                    String top = BlockState.name(g.c.get(px, y, pz));
                    if (top.endsWith("_leaves") || top.endsWith("_log") || top.contains("water")) continue;
                    g.npc(name, px, y, pz, 180f);
                    return;
                }
        throw new IllegalStateException("no open spot for " + name);
    }

    static void zones(HubCtx g) {
        g.zone("hub", "Hub", 'b', Box.of(-ISLAND_R - 30, 0, -ISLAND_R - 30, ISLAND_R + 30, 255, ISLAND_R + 30));
        g.layout.data("hub.village-y", String.valueOf(g.V));
        g.layout.data("walk.ignore", "label.,fishing.hotspot.,archery.");
    }

}
