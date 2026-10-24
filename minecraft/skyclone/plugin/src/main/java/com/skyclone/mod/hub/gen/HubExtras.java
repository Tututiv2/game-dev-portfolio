package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * Landmarks that fill the open country: the Old Keep (a walled castle north-east of the village,
 * with four climbable corner towers) and the Carnival grounds south-east of the lake.
 */
final class HubExtras {

    private HubExtras() {}

    static void castle(HubCtx g) {
        int Y = g.castleY;
        Box b = CASTLE;
        int x0 = b.minX() + 2, z0 = b.minZ() + 2, x1 = b.maxX() - 2, z1 = b.maxZ() - 2;   // outer wall face
        Palette wall = Palette.of("stone_bricks:5,mossy_stone_bricks:2,cracked_stone_bricks:2,andesite:1");
        g.reserve("castle.n", Box.of(x0 - 5, Y - 8, z0 - 5, x1 + 5, Y + 24, z0 + 5));
        g.reserve("castle.s", Box.of(x0 - 5, Y - 8, z1 - 5, x1 + 5, Y + 24, z1 + 5));
        g.reserve("castle.w", Box.of(x0 - 5, Y - 8, z0 + 6, x0 + 5, Y + 24, z1 - 6));
        g.reserve("castle.e", Box.of(x1 - 5, Y - 8, z0 + 6, x1 + 5, Y + 24, z1 - 6));
        int gx = (x0 + x1) / 2;
        // courtyard
        Palette yard = Palette.of("grass_block:4,coarse_dirt:2,gravel:2,cobblestone:1");
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) g.paveCell(x, Y, z, yard);
        // curtain wall: two thick, nine tall, crenellated, gate in the south
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                int edge = Math.min(Math.min(x - x0, x1 - x), Math.min(z - z0, z1 - z));
                if (edge > 1) continue;
                boolean gate = z >= z1 - 1 && Math.abs(x - gx) <= 2;
                Builders.footing(g.c, x, Y, z, wall, 10);
                g.c.set(x, Y, z, wall.at(x, Y, z));
                for (int y = Y + 1; y <= Y + 9; y++) g.c.set(x, y, z, gate && y <= Y + 5 ? "air" : wall.at(x, y, z));
                if (edge == 0 && ((x + z) & 1) == 0) g.c.set(x, Y + 10, z, wall.at(x, Y + 10, z));
            }
        for (int x = gx - 2; x <= gx + 2; x++) g.c.set(x, Y + 5, z1 - 1, "iron_bars");
        g.c.set(gx - 3, Y + 6, z1 + 1, Blocks.wallBanner("blue", "south"));
        g.c.set(gx + 3, Y + 6, z1 + 1, Blocks.wallBanner("blue", "south"));
        g.c.set(gx - 3, Y + 4, z1 + 1, Blocks.wallTorch("south"));
        g.c.set(gx + 3, Y + 4, z1 + 1, Blocks.wallTorch("south"));
        // corner towers; each door faces into the courtyard through a passage cut in the wall
        int[][] corners = {{x0, z0, 1, 1}, {x1, z0, -1, 1}, {x0, z1, 1, -1}, {x1, z1, -1, -1}};
        for (int[] q : corners) {
            Builders.Tower t = new Builders.Tower(q[0], Y, q[1]);
            t.radius = 4; t.height = 15; t.top = "battlements"; t.round = false; t.wall = wall;
            t.door = q[3] > 0 ? "south" : "north";
            t.build(g.c);
            int sx = t.doorStep[0], sz = t.doorStep[2];
            for (int k = 0; k <= 3; k++) {
                int cz = sz + q[3] * k;
                for (int dx = 0; dx <= 1; dx++) {
                    int cx = sx + q[2] * dx;
                    g.c.set(cx, Y, cz, "cobblestone");
                    for (int y = Y + 1; y <= Y + 3; y++) g.c.set(cx, y, cz, "air");
                }
            }
            g.soul(t.platform[0], t.platform[1], t.platform[2]);
        }
        // the keep
        Room k = Bld.build(g, "castle_keep", gx - 10, Y, z0 + 8, 21, 15, "south", Bld.STONE.floors(2, 6).roof("deepslate_tile", "hip"));
        k.floor(Palette.of("stone_bricks:3,polished_andesite:1"));
        k.runner(1, k.vMax - 2, "red_carpet");
        k.rect(-2, k.vMax - 1, 2, k.vMax, 1, "polished_blackstone_bricks");
        k.set(0, 2, k.vMax, "stone_brick_stairs[facing=" + k.fwd() + ",half=bottom,shape=straight,waterlogged=false]");
        for (int v = 3; v <= k.vMax - 3; v += 4) { k.set(k.uMin + 1, 1, v, "chiseled_stone_bricks"); k.set(k.uMax - 1, 1, v, "chiseled_stone_bricks"); }
        k.backBanner(-4, 4, "blue"); k.backBanner(4, 4, "blue");
        k.chandeliers(5);
        k.npc("npc.castellan", 0, k.vMax - 3);
        Upstairs.furnish(g, k, "keep");   // the castellan's study upstairs
        // training dummies and a well in the yard
        for (int x = x0 + 6; x <= x0 + 12; x += 3) {
            g.c.set(x, Y + 1, z1 - 7, "oak_fence");
            g.c.set(x, Y + 2, z1 - 7, "hay_block[axis=y]");
            g.c.set(x, Y + 3, z1 - 7, "carved_pumpkin[facing=south]");
        }
        Builders.well(g.c, x1 - 9, Y, z1 - 8);
        g.road(new int[][]{{gx, z1 + 2}, {gx, z1 + 8}}, 5, Palette.of("gravel:3,cobblestone:2"));
        g.area("castle", Box.of(x0 + 2, Y + 1, z0 + 2, x1 - 2, Y + 3, z1 - 2));
        g.zone("castle", "Old Keep", '9', Box.of(b.minX(), Y - 8, b.minZ(), b.maxX(), Y + 30, b.maxZ()));
        g.keepOut(b.grow(3));
    }

    static void carnival(HubCtx g) {
        int Y = g.carnivalY;
        Box b = CARNIVAL;
        g.reserve("carnival", Box.of(b.minX(), Y - 6, b.minZ(), b.maxX(), Y + 16, b.maxZ()));
        Palette ground = Palette.of("grass_block:5,dirt_path:2,coarse_dirt:1");
        for (int z = b.minZ(); z <= b.maxZ(); z++) for (int x = b.minX(); x <= b.maxX(); x++) g.paveCell(x, Y, z, ground);
        tent(g, b.minX() + 9, Y, b.minZ() + 9, "red", "white", "south");
        tent(g, b.maxX() - 9, Y, b.minZ() + 9, "blue", "yellow", "south");
        tent(g, b.minX() + 9, Y, b.maxZ() - 9, "purple", "white", "north");
        // the carousel
        int cx = b.maxX() - 10, cz = b.maxZ() - 10;
        Shapes.disk(g.c, cx, Y, cz, 6.4, Fill.of("smooth_quartz"));
        for (int y = Y + 1; y <= Y + 5; y++) g.c.set(cx, y, cz, "stripped_birch_log[axis=y]");
        for (int a = 0; a < 8; a++) {
            int px = cx + (int) Math.round(Math.cos(a * Math.PI / 4) * 4.5), pz = cz + (int) Math.round(Math.sin(a * Math.PI / 4) * 4.5);
            for (int y = Y + 1; y <= Y + 4; y++) g.c.set(px, y, pz, "birch_fence");
        }
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 6.4) continue;
                int y = Y + 5 + (d < 2 ? 2 : d < 4 ? 1 : 0);
                g.c.set(cx + dx, y, cz + dz, (Math.floorMod((int) Math.round(Math.atan2(dz, dx) * 4 / Math.PI), 2) == 0) ? "red_wool" : "yellow_wool");
                for (int yy = Y + 5; yy < y; yy++) g.c.set(cx + dx, yy, cz + dz, "birch_planks");
            }
        g.c.set(cx, Y + 8, cz, "gold_block");
        // game stalls
        Builders.Stall s1 = Builders.marketStall(g.c, (b.minX() + b.maxX()) / 2, Y, b.minZ() + 4, "south", "orange", "white", g.seed + 71);
        Builders.Stall s2 = Builders.marketStall(g.c, (b.minX() + b.maxX()) / 2 - 2, Y, b.maxZ() - 4, "north", "lime", "white", g.seed + 72);
        g.npc("npc.carnival_barker", s1.vendor()[0], Y, s1.vendor()[2], 0f);
        g.npc("npc.carnival_games", s2.vendor()[0], Y, s2.vendor()[2], 180f);
        for (int x = b.minX() + 2; x <= b.maxX() - 2; x += 10) for (int z : new int[]{b.minZ() + 1, b.maxZ() - 1}) { g.clearAbove(x, Y, z, 6); Builders.lampPost(g.c, x, Y, z, "street", "north"); }
        g.soulOnGround(b.minX() + 1, b.maxZ() - 1);
        g.area("carnival", Box.of(b.minX() + 1, Y + 1, b.minZ() + 1, b.maxX() - 1, Y + 3, b.maxZ() - 1));
        g.zone("carnival", "Carnival", 'd', Box.of(b.minX(), Y - 6, b.minZ(), b.maxX(), Y + 30, b.maxZ()), Layout.Zone.SAFE);
        g.keepOut(b.grow(3));
    }

    /** A round striped tent with an opening facing {@code door}. */
    static void tent(HubCtx g, int cx, int y, int cz, String a, String b, String door) {
        Palette stripes = Palette.of(a + "_wool:1," + b + "_wool:1");
        for (int dz = -5; dz <= 5; dz++)
            for (int dx = -5; dx <= 5; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 5.4) continue;
                int x = cx + dx, z = cz + dz;
                g.c.set(x, y, z, "spruce_planks");
                boolean opening = Math.abs(door.equals("south") || door.equals("north") ? dx : dz) <= 1
                        && (door.equals("south") ? dz > 0 : door.equals("north") ? dz < 0 : door.equals("east") ? dx > 0 : dx < 0);
                if (d > 4.4) for (int h = 1; h <= 3; h++) g.c.set(x, y + h, z, opening ? "air" : (((int) Math.floor(Math.atan2(dz, dx) * 3)) & 1) == 0 ? a + "_wool" : b + "_wool");
            }
        for (int h = 0; h < 6; h++) {
            double r = 5.6 - h;
            for (int dz = -6; dz <= 6; dz++)
                for (int dx = -6; dx <= 6; dx++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > r || d < r - 1.6) continue;
                    g.c.set(cx + dx, y + 4 + h, cz + dz, stripes.at(cx + dx, y + 4 + h, cz + dz));
                }
        }
        g.c.set(cx, y + 9, cz, Blocks.banner(a, 0));
        g.c.set(cx, y + 1, cz, "spruce_fence");
        g.c.set(cx, y + 2, cz, Blocks.lantern(false));
    }
}
