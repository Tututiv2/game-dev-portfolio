package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Trees;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * The Graveyard (east): walled field of graves, a chapel with a bell tower, the gravedigger's shed,
 * the Catacombs gate, and a mausoleum whose stair leads down into the Crypts under it.
 */
final class HubEast {

    private HubEast() {}

    static final int MAUS_X = 166, MAUS_Z = -12;   // mausoleum north-west corner (11 x 11)
    static final int GATE_Z = -24;                  // the west gate's centre line

    static void graveyard(HubCtx g) {
        int G = g.graveY;
        Box b = GRAVEYARD;
        // ground: dark soil, gravel walks
        Palette soil = Palette.of("grass_block:5,podzol:3,coarse_dirt:2,rooted_dirt:1");
        for (int z = b.minZ() + 1; z <= b.maxZ() - 1; z++)
            for (int x = b.minX() + 1; x <= b.maxX() - 1; x++) g.paveCell(x, G, z, soil);
        Palette walk = Palette.of("gravel:4,coarse_dirt:1,cobblestone:1");
        g.pave(b.minX() + 1, GATE_Z - 1, b.maxX() - 1, GATE_Z + 1, G, walk);
        g.pave(188, b.minZ() + 1, 190, b.maxZ() - 1, G, walk);
        g.keepOut(Box.of(b.minX(), 0, GATE_Z - 1, b.maxX(), 0, GATE_Z + 1));
        g.keepOut(Box.of(188, 0, b.minZ(), 190, 0, b.maxZ()));
        // the wall, with the west gate and a north-east gap to the farm road
        Builders.fenceLine(g.c, new int[][]{{b.minX(), b.minZ()}, {b.maxX(), b.minZ()}, {b.maxX(), b.maxZ()}, {b.minX(), b.maxZ()}},
                "mossy_cobblestone_wall", true);
        for (int z = GATE_Z - 2; z <= GATE_Z + 2; z++) { g.c.set(b.minX(), G + 1, z, "air"); g.c.set(b.minX(), G, z, "gravel"); }
        for (int x = 187; x <= 191; x++) { g.c.set(x, G + 1, b.maxZ(), "air"); g.c.set(x, G, b.maxZ(), "gravel"); }
        for (int s : new int[]{-3, 3}) {
            int z = GATE_Z + s;
            for (int y = G + 1; y <= G + 4; y++) g.c.set(b.minX(), y, z, "mossy_stone_bricks");
            g.c.set(b.minX(), G + 5, z, Blocks.soulLantern(false));
        }
        for (int z = GATE_Z - 3; z <= GATE_Z + 3; z++) g.c.set(b.minX(), G + 5, z, z == GATE_Z - 3 || z == GATE_Z + 3 ? Blocks.soulLantern(false) : "dark_oak_slab[type=bottom,waterlogged=false]");
        g.zone("graveyard", "Graveyard", 'c', Box.of(b.minX(), G - 3, b.minZ(), b.maxX(), G + 40, b.maxZ()));

        chapel(g, G);
        mausoleum(g, G);
        catacombsGate(g, G);
        shed(g, G);

        // rows of graves where nothing else stands
        String[] stones = {"stone_brick_wall", "andesite_wall", "cobblestone_wall", "mossy_stone_brick_wall", "chiseled_stone_bricks", "cracked_stone_bricks", "polished_andesite"};
        Palette mound = Palette.of("coarse_dirt:3,podzol:2,rooted_dirt:2,soul_soil:1");
        for (int x = b.minX() + 5; x <= b.maxX() - 4; x += 4)
            for (int z = b.minZ() + 4; z <= b.maxZ() - 4; z += 5) {
                if (g.res.covers(x, z, 2) || g.roads.near(x, z, 2) || Math.abs(z - GATE_Z) <= 3 || Math.abs(x - 189) <= 3) continue;
                double r = g.rnd("grave", x, z);
                if (r < 0.12) continue;
                g.c.set(x, G + 1, z, stones[(int) (r * 997) % stones.length]);
                for (int k = 1; k <= 2; k++) {
                    g.c.set(x, G, z + k, mound.at(x, G, z + k));
                    g.clearAbove(x, G, z + k, 2);
                }
                if (r > 0.9) g.c.set(x, G + 1, z + 1, "candle[candles=3,lit=true,waterlogged=false]");
                else if (r > 0.8) g.c.set(x, G + 1, z + 2, "wither_rose");
                else if (r > 0.74) g.c.set(x, G + 1, z + 1, "dead_bush");
            }
        // a few dead trees and lights
        for (int[] t : new int[][]{{150, -60}, {232, -8}, {205, 20}, {170, 18}, {226, -40}})
            if (!g.res.covers(t[0], t[1], 2)) Trees.grow(g.c, "dead", t[0], g.ground(t[0], t[1]), t[1], g.seed + t[0], 1.0);
        for (int x = b.minX() + 10; x <= b.maxX() - 6; x += 16) { g.lamp(x, GATE_Z + 3, "soul", "north"); g.lamp(x, GATE_Z - 3, "soul", "south"); }
        g.area("graveyard", Box.of(b.minX() + 1, G + 1, b.minZ() + 1, b.maxX() - 1, G + 3, b.maxZ() - 1));
        g.keepOut(b);
        // fairy souls: a corner among the graves and behind the shed
        g.soulOnGround(b.maxX() - 2, b.minZ() + 2);
        g.soulOnGround(b.minX() + 2, b.maxZ() - 2);
    }

    static void chapel(HubCtx g, int G) {
        Room r = Bld.build(g, "chapel", 204, G, -72, 15, 23, "south", Bld.STONE.floors(1, 8).roof("deepslate_tile", "gable").window("gray_stained_glass_pane"));
        r.floor(Palette.of("stone_bricks:3,polished_andesite:1"));
        r.runner(0, r.vMax - 3, "black_carpet");
        for (int v = 3; v <= r.vMax - 5; v += 2)
            for (int u = r.uMin + 1; u <= r.uMax - 1; u++) {
                if (Math.abs(u) <= 1) continue;
                r.set(u, 1, v, Blocks.stairs("dark_oak", r.back()));
            }
        r.rect(-2, r.vMax - 2, 2, r.vMax, 1, "polished_blackstone_bricks");
        r.set(0, 2, r.vMax - 1, "candle[candles=4,lit=true,waterlogged=false]");
        r.set(-2, 2, r.vMax - 2, "candle[candles=2,lit=true,waterlogged=false]");
        r.set(2, 2, r.vMax - 2, "candle[candles=2,lit=true,waterlogged=false]");
        r.backBanner(0, 5, "black");
        r.chandeliers(6);
        r.npc("npc.mourner", 3, r.vMax - 3, r.back());
        g.zone("chapel", "Chapel", 'c', r.box().grow(1), Layout.Zone.SAFE);
        // the bell tower beside it, with a walkable top
        Builders.Tower t = new Builders.Tower(226, G, -66);
        t.radius = 3; t.height = 19; t.top = "battlements"; t.door = "west"; t.round = false;
        t.wall = Palette.of("stone_bricks:5,mossy_stone_bricks:2,cracked_stone_bricks:1");
        g.reserve("bell_tower", t.reserve());
        t.build(g.c);
        g.c.set(226, G + 17, -66, "bell[attachment=floor,facing=north,powered=false]");
        g.soul(t.platform[0], t.platform[1], t.platform[2]);
        g.road(new int[][]{{t.doorStep[0] - 1, t.doorStep[2]}, {221, t.doorStep[2]}, {221, -47}, {189, -47}}, 2, Palette.of("gravel:3,coarse_dirt:1"));
        HubVillage.walk(g, r, 189, -40, Palette.of("gravel:3,coarse_dirt:1"));
    }

    static void mausoleum(HubCtx g, int G) {
        int x0 = MAUS_X, z0 = MAUS_Z, x1 = x0 + 10, z1 = z0 + 10;
        int C = cryptFloor(g);
        g.reserve("mausoleum", Box.of(x0 - 1, C - 2, z0 - 1, x1 + 1, G + 12, z1 + 1));
        Palette wall = Palette.of("stone_bricks:4,mossy_stone_bricks:2,cracked_stone_bricks:2,chiseled_stone_bricks:1");
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                g.c.set(x, G, z, edge ? wall.at(x, G, z) : "polished_andesite");
                Builders.footing(g.c, x, G, z, wall, 10);
                for (int y = G + 1; y <= G + 6; y++) g.c.set(x, y, z, edge ? wall.at(x, y, z) : "air");
                g.c.set(x, G + 7, z, edge ? Blocks.slab("stone_brick", "bottom") : "stone_bricks");
            }
        // a pyramid cap
        for (int k = 1; k <= 4; k++)
            for (int z = z0 + k; z <= z1 - k; z++)
                for (int x = x0 + k; x <= x1 - k; x++) g.c.set(x, G + 7 + k, z, k == 4 ? "chiseled_stone_bricks" : "stone_bricks");
        // the west doorway (3 wide) with soul lanterns
        int dz = z0 + 5;
        for (int z = dz - 1; z <= dz + 1; z++) for (int y = G + 1; y <= G + 3; y++) g.c.set(x0, y, z, "air");
        g.c.set(x0 - 1, G + 4, dz - 2, Blocks.soulLantern(false));
        g.c.set(x0 - 1, G + 4, dz + 2, Blocks.soulLantern(false));
        g.c.set(x0, G + 4, dz - 2, "chiseled_stone_bricks");
        g.c.set(x0, G + 4, dz + 2, "chiseled_stone_bricks");
        for (int z = dz - 1; z <= dz + 1; z++) g.c.set(x0 - 1, G, z, "polished_andesite");
        // the stair down: 3 wide, heading east from just inside the door, one step per block
        int steps = G - C;
        Palette sw = Palette.of("stone_bricks:4,cracked_stone_bricks:2,mossy_stone_bricks:1");
        for (int k = 1; k <= steps; k++) {
            int x = x0 + 1 + k, y = G - k;
            for (int z = dz - 2; z <= dz + 2; z++) {
                boolean side = Math.abs(z - dz) == 2;
                for (int yy = y - 1; yy <= y + 5; yy++) {
                    if (side || yy == y + 5 || yy == y - 1) { if (x > x1 || yy <= G - 1) g.c.set(x, yy, z, sw.at(x, yy, z)); }
                    else if (yy == y) g.c.set(x, yy, z, Blocks.stairs("stone_brick", "west"));
                    else g.c.set(x, yy, z, "air");
                }
            }
            if (k % 4 == 2) g.c.set(x, y + 4, dz, Blocks.soulLantern(true));
        }
        g.c.set(x0 + 2, G + 6, dz, Blocks.soulLantern(true));
        g.c.set(x0 + 2, G + 1, z0 + 1, "skeleton_skull[rotation=8]");
        g.c.set(x0 + 2, G + 1, z1 - 1, "skeleton_skull[rotation=0]");
        g.soul(x1 - 1, G, z0 + 1);
    }

    static int cryptFloor(HubCtx g) { return g.graveY - 15; }

    /** Carving pass: the Crypts under the graveyard. */
    static void crypts(HubCtx g) {
        int G = g.graveY, C = cryptFloor(g);
        int sx = MAUS_X + 1 + (G - C);           // first cell east of the stair's foot
        int dz = MAUS_Z + 5;
        Palette wall = Palette.of("stone_bricks:4,cracked_stone_bricks:3,mossy_stone_bricks:2,chiseled_stone_bricks:1");
        Palette floor = Palette.of("stone_bricks:3,gravel:1,cracked_stone_bricks:2,soul_soil:1,cobblestone:1");
        // halls: {x1, z1, x2, z2}
        int[][] halls = {
                {sx, dz - 2, 232, dz + 2},             // main east-west hall
                {198, -62, 202, dz},                    // north branch
                {198, dz, 202, 22},                     // south branch
                {218, -50, 222, dz},                    // second north branch
                {218, dz, 222, 16},                     // second south branch
                {198, -40, 222, -36},                   // cross gallery north
                {198, 6, 222, 10},                      // cross gallery south
        };
        int[][] rooms = {VAULT, {200, 22, 6}, {220, -50, 5}, {220, 16, 5}, {232, dz, 5}};
        for (int[] h : halls) carveBox(g, h[0], h[1], h[2], h[3], C, 4, wall, floor);
        for (int[] r : rooms) carveBox(g, r[0] - r[2], r[1] - r[2], r[0] + r[2], r[1] + r[2], C, 5, wall, floor);
        // decoration: hanging soul lanterns, cobwebs in upper corners, skulls and coffins in wall niches
        for (int[] h : halls) {
            for (int x = h[0]; x <= h[2]; x += 6)
                for (int z = h[1]; z <= h[3]; z += 6) {
                    int cx = Math.min(h[2], x + 1), cz = Math.min(h[3], z + 1);
                    if (g.c.isAir(cx, C + 4, cz)) g.c.set(cx, C + 4, cz, Blocks.soulLantern(true));
                }
        }
        int[] corners = {0};
        Box all = Box.of(sx - 1, C, -70, 240, C + 6, 30);
        for (int z = all.minZ(); z <= all.maxZ(); z++)
            for (int x = all.minX(); x <= all.maxX(); x++) {
                if (!g.c.isAir(x, C + 4, z)) continue;
                int solid = 0;
                for (int d = 0; d < 4; d++) if (!g.c.isAir(x + Blocks.DX[d], C + 4, z + Blocks.DZ[d])) solid++;
                if (solid >= 2 && g.rnd("web", x, z) < 0.35) { g.c.set(x, C + 4, z, "cobweb"); corners[0]++; }
                if (solid >= 1 && g.c.isAir(x, C + 1, z) && g.rnd("bones", x, z) < 0.03)
                    g.c.set(x, C + 1, z, g.rnd("bk", x, z) < 0.5 ? "skeleton_skull[rotation=4]" : "bone_block[axis=y]");
            }
        // coffins along the main hall
        for (int x = sx + 4; x <= 228; x += 7)
            for (int s : new int[]{-1, 1}) {
                int z = dz + s * 2;
                if (g.c.isAir(x, C + 1, z)) { g.c.set(x, C + 1, z, Blocks.stairs("spruce", s < 0 ? "south" : "north", "top", "straight")); }
            }
        g.c.set(234, C + 1, dz, "chest[facing=west,type=single,waterlogged=false]");
        g.c.set(200, C + 1, -66, "chest[facing=south,type=single,waterlogged=false]");
        g.area("crypt", Box.of(sx, C + 1, -68, 238, C + 3, 28));
        g.zone("crypt", "Crypts", 'c', Box.of(sx - 1, C - 1, -70, 240, C + 7, 30));
        // The landing: the stair down from the mausoleum and the first stretch of the hall, where
        // /warp crypt puts you (warp.crypt, LANDING_WARP blocks in). SAFE, so core spawns no ghoul in
        // it, returns any that walk in, and lets none target or hit a player standing in it.
        // Playtest "loops" 2026-10-03: the warp landed inside the spawn area, among the Crypt Ghouls
        // and the Golden Ghoul, and killed a 600 HP / 700 Defense player within a second, twice.
        g.zone("crypt_landing", "Crypt Entrance", 'c', cryptLanding(g), Layout.Zone.SAFE);
        // The Golden Ghoul's vault: the dead-end room at the top of the north branch, 47 blocks up a side
        // corridor from the main hall. Wave-4 playtest: the Golden Ghoul (Lv60, 800 a hit) spawned anywhere in
        // the Crypt and patrolled the main hall about 14 blocks past the landing, where everyone grinds Crypt
        // Ghouls; a 600 HP / 630 Defense Ghoul-slayer grinder died three times in six minutes. It spawns only
        // here now (spawns.json area crypt_vault), and GoldenVault keeps it from fighting anyone outside.
        g.area("crypt_vault", goldenVault(g));
        g.zone("crypt_vault", "Golden Ghoul's Vault", '6', goldenVault(g).grow(2));
        g.soul(204, C, 26);
        g.soul(224, C, -54);
    }

    /** The Golden Ghoul's vault room {centre x, centre z, half size}: the north branch's dead end. */
    static final int[] VAULT = {200, -62, 6};

    /** The vault's floor space (feet-space y, like the crypt area): the room minus its outer ring. */
    public static Box goldenVault(HubCtx g) {
        int C = cryptFloor(g);
        int r = VAULT[2] - 1;
        return Box.of(VAULT[0] - r, C + 1, VAULT[1] - r, VAULT[0] + r, C + 3, VAULT[1] + r);
    }

    /** Blocks east of the stair's foot that the warp.crypt anchor stands. */
    static final int LANDING_WARP = 3;
    /** Blocks east of the stair's foot the SAFE landing reaches (the hall's spawns start past it). */
    static final int LANDING_REACH = 7;

    /** The Crypt landing: the whole stairwell under the mausoleum floor plus the first hall cells. */
    public static Box cryptLanding(HubCtx g) {
        int G = g.graveY, C = cryptFloor(g);
        int sx = MAUS_X + 1 + (G - C), dz = MAUS_Z + 5;
        return Box.of(MAUS_X + 2, C - 1, dz - 3, sx + LANDING_REACH, G - 1, dz + 3);
    }

    /** Carve a hall: air inside, a floor, and walls/ceiling of brick that never seal an earlier hall. */
    static void carveBox(HubCtx g, int x1, int z1, int x2, int z2, int C, int height, Palette wall, Palette floor) {
        for (int z = z1 - 1; z <= z2 + 1; z++)
            for (int x = x1 - 1; x <= x2 + 1; x++) {
                boolean in = x >= x1 && x <= x2 && z >= z1 && z <= z2;
                for (int y = C; y <= C + height + 1; y++) {
                    if (in && y > C && y <= C + height) g.c.set(x, y, z, "air");
                    else if (in && y == C) g.c.set(x, y, z, floor.at(x, y, z));
                    else if (!g.c.isAir(x, y, z)) {
                        String n = BlockState.name(g.c.get(x, y, z));
                        if (!n.contains("stone_brick") && !n.contains("stairs") && !n.contains("lantern")) g.c.set(x, y, z, wall.at(x, y, z));
                    }
                }
            }
    }

    static void catacombsGate(HubCtx g, int G) {
        int x = 156, z = -64;
        g.reserve("catacombs_gate", Box.of(x - 7, G - 3, z - 8, x + 7, G + 13, z + 4));
        Palette d = Palette.of("deepslate_bricks:3,cracked_deepslate_bricks:2,polished_deepslate:1,deepslate_tiles:1");
        // a deep stone gateway set into a mound
        for (int dz = -7; dz <= 0; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                int bx = x + dx, bz = z + dz;
                int hgt = 9 - Math.abs(dx) / 2 + dz / 3;
                for (int y = G + 1; y <= G + hgt; y++) g.c.set(bx, y, bz, d.at(bx, y, bz));
                Builders.footing(g.c, bx, G + 1, bz, d, 6);
            }
        for (int dx = -2; dx <= 2; dx++)
            for (int y = G + 1; y <= G + 5; y++) for (int dz = -2; dz <= 0; dz++) g.c.set(x + dx, y, z + dz, "air");
        for (int dx = -2; dx <= 2; dx++) g.c.set(x + dx, G + 5, z - 2, "iron_bars");
        for (int dx = -2; dx <= 2; dx++) for (int y = G + 1; y <= G + 4; y++) g.c.set(x + dx, y, z - 3, "iron_bars");
        g.c.set(x - 3, G + 6, z + 1, "wither_skeleton_wall_skull[facing=south]");
        g.c.set(x + 3, G + 6, z + 1, "wither_skeleton_wall_skull[facing=south]");
        g.c.set(x, G + 7, z + 1, "skeleton_wall_skull[facing=south]");
        g.c.set(x - 3, G + 1, z + 1, Blocks.soulLantern(false));
        g.c.set(x + 3, G + 1, z + 1, Blocks.soulLantern(false));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) g.c.set(x + dx, G, z + dz, "polished_deepslate");
        g.npc("npc.dungeon_guide", x + 1, G, z + 2, 0f);
        g.road(new int[][]{{x, z + 4}, {x, GATE_Z - 2}}, 3, Palette.of("gravel:3,cobblestone:1"));
    }

    static void shed(HubCtx g, int G) {
        Room r = Bld.build(g, "gravedigger_shed", 148, G, 6, 9, 7, "north", Bld.CABIN);
        r.set(-2, 1, 3, "composter[level=4]");
        r.set(2, 1, 3, "barrel[facing=up,open=false]");
        g.npc("npc.gravedigger", r.house.doorStep[0] + 2, G, r.house.doorStep[2] - 1, 180f);
        g.road(new int[][]{{r.house.doorStep[0], r.house.doorStep[2] - 1}, {r.house.doorStep[0], GATE_Z + 2}}, 2, Palette.of("gravel:3,coarse_dirt:1"));
    }

}
