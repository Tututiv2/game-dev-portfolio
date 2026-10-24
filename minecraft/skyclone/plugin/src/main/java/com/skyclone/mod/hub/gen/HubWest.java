package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * The west and north-west: the Foraging Camp at the edge of the Forest, the Colosseum and the
 * Ruins on their hill.
 */
final class HubWest {

    private HubWest() {}

    // =======================================================================================
    // Foraging Camp
    // =======================================================================================

    static void camp(HubCtx g) {
        int Y = g.campY;
        Box b = CAMP;
        Palette ground = Palette.of("coarse_dirt:3,podzol:2,grass_block:3,dirt_path:1");
        for (int z = b.minZ(); z <= b.maxZ(); z++) for (int x = b.minX(); x <= b.maxX(); x++) if (g.rnd("campg", x, z) < 0.7) g.paveCell(x, Y, z, ground);
        Room shop = Bld.build(g, "lumber_shop", b.minX() + 4, Y, b.minZ() + 2, 11, 9, "east", Bld.CABIN.chimney().wall("oak_planks:3,stripped_oak_log:1"));
        shop.set(-2, 1, 4, "oak_log[axis=y]"); shop.set(2, 1, 4, "birch_log[axis=y]"); shop.set(2, 2, 4, "spruce_log[axis=y]");
        for (int u = shop.uMin; u <= shop.uMax; u++) shop.set(u, 1, shop.vMax, (u & 1) == 0 ? "barrel[facing=up,open=false]" : "stripped_oak_log[axis=y]");
        shop.npc("npc.lumber_merchant", 0, 5);
        Room work = Bld.build(g, "carpenter", b.minX() + 4, Y, b.minZ() + 20, 13, 9, "east", Bld.CABIN.wall("spruce_planks:3,stripped_spruce_log:1,oak_planks:1"));
        work.set(-3, 1, 4, "crafting_table"); work.set(3, 1, 4, "stonecutter[facing=" + work.back() + "]");
        work.set(-3, 1, 6, "smithing_table"); work.set(3, 1, 6, "loom[facing=" + work.back() + "]");
        work.set(0, 1, work.vMax, "chest[facing=" + work.back() + ",type=single,waterlogged=false]");
        work.npc("npc.carpenter", 0, 5);
        // log piles, a sawmill platform and the camp fire
        String[] logs = {"oak_log", "birch_log", "spruce_log", "dark_oak_log", "jungle_log", "acacia_log"};
        int k = 0;
        for (int z = b.minZ() + 2; z <= b.minZ() + 14; z += 6) {
            int x0 = b.maxX() - 14;
            g.reserve("logpile." + k, Box.of(x0 - 1, Y - 2, z - 1, x0 + 5, Y + 4, z + 2));
            String l = logs[k++ % logs.length];
            for (int x = x0; x <= x0 + 4; x++) for (int y = Y + 1; y <= Y + 2; y++) { g.c.set(x, y, z, l + "[axis=x]"); g.c.set(x, y, z + 1, l + "[axis=x]"); }
            g.c.set(x0 + 1, Y + 3, z, l + "[axis=x]"); g.c.set(x0 + 2, Y + 3, z, l + "[axis=x]");
        }
        int sx = b.maxX() - 10, sz = b.maxZ() - 8;
        g.reserve("sawmill", Box.of(sx - 3, Y - 2, sz - 3, sx + 3, Y + 6, sz + 3));
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) { g.c.set(sx + dx, Y, sz + dz, "spruce_planks"); g.c.set(sx + dx, Y + 5, sz + dz, Blocks.slab("spruce", "bottom")); }
        for (int[] q : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) for (int y = Y + 1; y <= Y + 4; y++) g.c.set(sx + q[0], y, sz + q[1], "spruce_log[axis=y]");
        g.c.set(sx, Y + 1, sz, "stonecutter[facing=north]");
        g.c.set(sx + 1, Y + 1, sz, "oak_log[axis=x]");
        g.c.set(sx, Y + 4, sz, Blocks.lantern(true));
        int fx = b.minX() + 26, fz = b.minZ() + 16;
        g.reserve("campfire", Box.of(fx - 3, Y - 2, fz - 3, fx + 3, Y + 3, fz + 3));
        Builders.campfire(g.c, fx, Y, fz);
        g.npc("npc.lumber_jack", fx + 1, Y, fz + 3, 180f);
        g.zone("foraging_camp", "Foraging Camp", '2', Box.of(b.minX(), Y - 6, b.minZ(), b.maxX(), Y + 30, b.maxZ()), Layout.Zone.SAFE);
        g.keepOut(b);
        g.soulOnGround(b.minX() + 1, b.maxZ() - 1);
    }

    // =======================================================================================
    // Colosseum
    // =======================================================================================

    static void colosseum(HubCtx g) {
        int Y = g.colY;
        int cx = COLOSSEUM.centerX(), cz = COLOSSEUM.centerZ();
        int ax = 16, az = 12, tiers = 7;
        g.reserve("colosseum", Box.of(COLOSSEUM.minX(), Y - 6, COLOSSEUM.minZ(), COLOSSEUM.maxX(), Y + 14, COLOSSEUM.maxZ()));
        Palette sand = Palette.of("sand:5,smooth_sandstone:1,gravel:1,coarse_dirt:1");
        Palette stone = Palette.of("stone_bricks:4,cracked_stone_bricks:2,mossy_stone_bricks:1,smooth_sandstone:2");
        int[] soulCell = null;
        for (int dz = -28; dz <= 28; dz++)
            for (int dx = -30; dx <= 30; dx++) {
                int k = ring(dx, dz, ax, az, tiers + 3);
                if (k < 0) continue;
                int x = cx + dx, z = cz + dz;
                boolean gate = Math.abs(dx) <= 2 || Math.abs(dz) <= 2;
                if (k == 0 || gate && k <= tiers + 3) {
                    g.paveCell(x, Y, z, k == 0 ? sand : Fill.of("smooth_sandstone"));
                    Builders.footing(g.c, x, Y, z, stone, 8);
                    for (int y = Y + 1; y <= Y + (k >= tiers + 2 ? 4 : 9); y++) g.c.set(x, y, z, "air");
                    if (gate && k >= tiers + 2) for (int y = Y + 5; y <= Y + 10; y++) g.c.set(x, y, z, stone.at(x, y, z));
                    continue;
                }
                for (int y = Y - 1; y >= Y - 8; y--) { if (!g.c.isAir(x, y, z)) break; g.c.set(x, y, z, stone.at(x, y, z)); }
                if (k == 1) {
                    for (int y = Y; y <= Y + 2; y++) g.c.set(x, y, z, stone.at(x, y, z));
                    for (int y = Y + 3; y <= Y + 8; y++) g.c.set(x, y, z, "air");
                } else if (k <= tiers + 1) {
                    int top = Y + k - 1;
                    for (int y = Y; y < top; y++) g.c.set(x, y, z, stone.at(x, y, z));
                    g.c.set(x, top, z, Blocks.stairs("stone_brick", Blocks.dirOf(dx, dz)));
                    for (int y = top + 1; y <= top + 4; y++) g.c.set(x, y, z, "air");
                    if (k == tiers + 1 && soulCell == null && dx > 6 && dz < -6) soulCell = new int[]{x, top, z};
                } else {
                    int top = Y + tiers + 3 + ((k == tiers + 3 && ((dx + dz) & 1) == 0) ? 1 : 0);
                    for (int y = Y; y <= top; y++) g.c.set(x, y, z, stone.at(x, y, z));
                }
            }
        // braziers in the arena and banners over the gates
        for (int[] q : new int[][]{{-10, -6}, {10, -6}, {-10, 6}, {10, 6}}) {
            g.c.set(cx + q[0], Y + 1, cz + q[1], "polished_andesite");
            g.c.set(cx + q[0], Y + 2, cz + q[1], Blocks.campfire(true, "north"));
        }
        for (String d : new String[]{"north", "south", "east", "west"}) {
            int r = (d.equals("north") || d.equals("south") ? az : ax) + tiers + 3;
            int bx = cx + Blocks.dx(d) * r, bz = cz + Blocks.dz(d) * r;
            g.c.set(bx, Y + 11, bz, Blocks.banner("red", HubNorth.bannerRot(d)));
        }
        if (soulCell != null) g.soul(soulCell[0], soulCell[1], soulCell[2]);
        g.area("colosseum", Box.of(cx - ax + 1, Y + 1, cz - az + 1, cx + ax - 1, Y + 3, cz + az - 1));
        g.zone("colosseum", "Colosseum", 'c', Box.of(COLOSSEUM.minX(), Y - 6, COLOSSEUM.minZ(), COLOSSEUM.maxX(), Y + 30, COLOSSEUM.maxZ()));
        g.keepOut(COLOSSEUM);
    }

    /** Smallest k >= 0 with (dx/(ax+k))^2 + (dz/(az+k))^2 <= 1, or -1 beyond {@code max}. */
    static int ring(int dx, int dz, int ax, int az, int max) {
        for (int k = 0; k <= max; k++) {
            double a = ax + k, b = az + k;
            if ((dx * dx) / (a * a) + (dz * dz) / (b * b) <= 1.0) return k;
        }
        return -1;
    }

    // =======================================================================================
    // Ruins
    // =======================================================================================

    static void ruins(HubCtx g) {
        int cx = RUINS_X, cz = RUINS_Z;
        int R = g.ruinsY;
        g.reserve("ruins", Box.of(cx - 16, R - 8, cz - 12, cx + 16, R + 14, cz + 12));
        Palette old = Palette.of("mossy_stone_bricks:4,cracked_stone_bricks:3,stone_bricks:2,mossy_cobblestone:2,andesite:1");
        // temple floor
        for (int dz = -11; dz <= 11; dz++)
            for (int dx = -15; dx <= 15; dx++) {
                int x = cx + dx, z = cz + dz;
                if (g.rnd("rfloor", x, z) < 0.12) continue;
                g.paveCell(x, R, z, old);
            }
        // broken walls: columns of random height around the edge, with gaps
        for (int dz = -11; dz <= 11; dz++)
            for (int dx = -15; dx <= 15; dx++) {
                boolean edge = Math.abs(dx) == 15 || Math.abs(dz) == 11;
                if (!edge || Math.abs(dx) <= 2 || Math.abs(dz) <= 2) continue;
                int x = cx + dx, z = cz + dz;
                int hgt = (int) (g.rnd("rwall", x, z) * 7) - 1;
                for (int y = R + 1; y <= R + hgt; y++) g.c.set(x, y, z, old.at(x, y, z));
            }
        // a colonnade, some standing, some broken, with rubble at their feet
        for (int dx = -10; dx <= 10; dx += 5)
            for (int dz : new int[]{-6, 6}) {
                int x = cx + dx, z = cz + dz;
                int hgt = 2 + (int) (g.rnd("rcol", x, z) * 8);
                g.c.set(x, R + 1, z, "chiseled_stone_bricks");
                for (int y = R + 2; y <= R + hgt; y++) g.c.set(x, y, z, y == R + 9 ? "chiseled_stone_bricks" : "stone_brick_wall");
                if (hgt < 5) { g.c.set(x + 1, R + 1, z, "cobblestone_slab[type=bottom,waterlogged=false]"); g.c.set(x, R + 1, z + (dz > 0 ? 1 : -1), "mossy_cobblestone"); }
            }
        // the altar
        g.c.set(cx, R + 1, cz, "chiseled_stone_bricks");
        g.c.set(cx, R + 2, cz, "lodestone");
        g.c.set(cx - 1, R + 1, cz, Blocks.stairs("stone_brick", "east"));
        g.c.set(cx + 1, R + 1, cz, Blocks.stairs("stone_brick", "west"));
        // the old watchtower, the hermit beside it
        Builders.Tower t = new Builders.Tower(cx + 24, R, cz - 14);
        t.radius = 4; t.height = 20; t.top = "battlements"; t.door = "west"; t.round = false;
        t.wall = old;
        g.reserve("watchtower", t.reserve());
        t.build(g.c);
        g.soul(t.platform[0], t.platform[1], t.platform[2]);
        g.npc("npc.hermit", t.doorStep[0] - 1, t.doorStep[1], t.doorStep[2] + 2, HubCtx.yaw("west"));
        g.soul(cx - 14, R, cz + 10);
        g.area("ruins", Box.of(cx - 40, R - 12, cz - 40, cx + 40, R + 8, cz + 40));
        g.zone("ruins", "Ruins", 'c', Box.of(cx - 44, R - 25, cz - 44, cx + 44, R + 40, cz + 44));
        g.keepOut(Box.of(cx - 16, 0, cz - 12, cx + 16, 0, cz + 12));
    }
}
