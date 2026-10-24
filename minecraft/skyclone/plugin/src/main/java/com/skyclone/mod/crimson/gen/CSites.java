package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/** The isle's named places outside the towns: tomb, skull, dojo, ruins, dukedom, courtyard, crossroads. */
final class CSites {

    private CSites() {}

    static void build(CCtx x) {
        crossroads(x);
        tomb(x);
        skull(x);
        dojo(x);
        ashfang(x);
        dukedom(x);
        courtyard(x);
        districtZones(x);
    }

    // ---- crossroads in the fields -----------------------------------------------------------

    private static void crossroads(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.CROSSROADS);
        x.res.reserve("crossroads", Box.of(-6, y - 4, -18, 6, y + 8, -6));
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 6.3) continue;
                c.set(dx, y, -12 + dz, d > 5.3 ? "polished_blackstone_bricks" : CKit.ROAD.at(dx, y, dz));
                Builders.footing(c, dx, y, -12 + dz, Fill.of("blackstone"), 8);
            }
        CKit.signpost(c, 0, y, -12);
        CKit.brazier(c, -4, y, -16, false);
        CKit.brazier(c, 4, y, -8, false);
        x.stops.put("crossroads", new int[]{0, y, -12});
    }

    // ---- Smoldering Tomb: a sunken court with a crypt beneath ----------------------------------

    private static void tomb(CCtx x) {
        VoxelCanvas c = x.c;
        int ty = x.y(CPlan.TOMB);
        int px0 = -4, px1 = 34, pz0 = 148, pz1 = 186;
        int pf = ty - 8;                                   // pit floor
        x.res.reserve("tomb_pit", Box.of(px0 - 2, ty - 24, 138, px1 + 2, ty + 10, pz1 + 2));
        Palette wall = Palette.of("red_sandstone:4,cut_red_sandstone:3,chiseled_red_sandstone:1,smooth_red_sandstone:2");
        Palette floor = Palette.of("smooth_red_sandstone:3,red_sandstone:2,polished_blackstone:2,soul_soil:1");
        for (int z = pz0 - 1; z <= pz1 + 1; z++)
            for (int xx = px0 - 1; xx <= px1 + 1; xx++) {
                boolean edge = xx < px0 || xx > px1 || z < pz0 || z > pz1;
                for (int y = pf - 3; y <= ty + 4; y++) {
                    if (edge) { if (y <= ty) c.set(xx, y, z, wall.at(xx, y, z)); else if (y == ty + 1 && ((xx + z) & 1) == 0) c.set(xx, y, z, "red_sandstone_wall"); }
                    else if (y < pf) c.set(xx, y, z, "blackstone");
                    else if (y == pf) c.set(xx, y, z, floor.at(xx, y, z));
                    else c.set(xx, y, z, "air");
                }
            }
        // stair ramp down the north side
        for (int z = pz0 - 1 - (ty - pf); z <= pz0 - 1; z++) {
            int f = pf + (pz0 - 1 - z);
            for (int xx = 12; xx <= 18; xx++) {
                boolean side = xx == 12 || xx == 18;
                if (side) { for (int y = f; y <= ty + 1; y++) c.set(xx, y, z, wall.at(xx, y, z)); continue; }
                c.set(xx, f, z, Blocks.stairs("red_sandstone", "north"));
                Builders.footing(c, xx, f, z, Fill.of("red_sandstone"), 14);
                for (int y = f + 1; y <= ty + 4; y++) c.set(xx, y, z, "air");
            }
        }
        // pillars along the pit walls, soul fires between them
        for (int xx = px0 + 2; xx <= px1 - 2; xx += 6) {
            for (int zz : new int[]{pz0, pz1}) {
                if (zz == pz0 && xx >= 11 && xx <= 19) continue;
                for (int y = pf + 1; y <= pf + 7; y++) c.set(xx, y, zz, y == pf + 7 ? "chiseled_red_sandstone" : "cut_red_sandstone");
            }
        }
        for (int[] b : new int[][]{{2, 158}, {28, 158}, {2, 178}, {28, 178}}) CKit.brazier(c, b[0], pf, b[1], true);
        // the crypt below, reached by a stairwell in a shrine
        int cf = pf - 9;                                   // crypt floor
        int cx0 = -1, cx1 = 31, cz0 = 168, cz1 = 184;
        for (int z = cz0 - 1; z <= cz1 + 1; z++)
            for (int xx = cx0 - 1; xx <= cx1 + 1; xx++) {
                boolean edge = xx < cx0 || xx > cx1 || z < cz0 || z > cz1;
                for (int y = cf - 1; y <= cf + 7; y++) {
                    if (edge || y == cf - 1 || y == cf + 7) c.set(xx, y, z, CKit.BLACK_BRICK.at(xx, y, z));
                    else if (y == cf) c.set(xx, y, z, ((xx + z) & 1) == 0 ? "polished_blackstone" : "red_nether_bricks");
                    else c.set(xx, y, z, "air");
                }
            }
        for (int xx = cx0 + 4; xx <= cx1 - 4; xx += 6)
            for (int zz : new int[]{cz0 + 4, cz1 - 4}) {
                for (int y = cf + 1; y <= cf + 6; y++) c.set(xx, y, zz, "polished_blackstone_bricks");
                c.set(xx, cf + 5, zz + 1, "soul_lantern[hanging=false,waterlogged=false]");
                c.set(xx, cf + 4, zz + 1, "polished_blackstone_bricks");
            }
        // sarcophagi
        for (int xx : new int[]{5, 13, 21}) {
            for (int dx = 0; dx <= 2; dx++) {
                c.set(xx + dx, cf + 1, 176, "polished_blackstone");
                c.set(xx + dx, cf + 2, 176, Blocks.slab("polished_blackstone_brick", "bottom"));
            }
        }
        // stairwell from the pit floor at z=160 down to the crypt (south), 3 wide
        int steps = pf - cf;
        for (int i = 0; i < steps; i++) {
            int z = 160 + i;
            int f = pf - i;
            for (int xx = 14; xx <= 16; xx++) {
                c.set(xx, f, z, Blocks.stairs("polished_blackstone_brick", "north"));
                for (int y = f + 1; y <= f + 4; y++) c.set(xx, y, z, "air");
            }
            for (int xx : new int[]{13, 17}) for (int y = f; y <= f + 4; y++) c.set(xx, y, z, CKit.BLACK_BRICK.at(xx, y, z));
            for (int xx = 13; xx <= 17; xx++) c.set(xx, f + 5, z, CKit.BLACK_BRICK.at(xx, f + 5, z));
        }
        for (int xx = 14; xx <= 16; xx++) for (int y = cf + 1; y <= cf + 4; y++) c.set(xx, y, 160 + steps, "air");
        // a shrine over the stairwell mouth
        for (int y = pf + 1; y <= pf + 6; y++) {
            c.set(12, y, 159, "cut_red_sandstone");
            c.set(18, y, 159, "cut_red_sandstone");
        }
        for (int xx = 12; xx <= 18; xx++) c.set(xx, pf + 6, 159, xx == 15 ? "chiseled_red_sandstone" : "smooth_red_sandstone");
        c.set(15, pf + 7, 159, "soul_campfire[lit=true,facing=north,signal_fire=false,waterlogged=false]");

        // ---- Opal veins (generator v4): white gemstone clusters set into the pit and crypt walls, mined by the
        // crimson module inside area gather.tomb_opal (the reference's Smoldering Tomb opals, research 07).
        int opals = 0;
        // pit walls: west (x = px0-1) and east (x = px1+1) faces, south face (z = pz1+1); never the ramp side
        int[][] pitVeins = {{px0 - 1, 156}, {px0 - 1, 167}, {px0 - 1, 179}, {px1 + 1, 153}, {px1 + 1, 164}, {px1 + 1, 176}};
        for (int[] v : pitVeins) opals += opalVein(c, v[0], pf + 2, v[1], true);
        for (int vx : new int[]{6, 12, 18, 24}) opals += opalVein(c, vx, pf + 2, pz1 + 1, false);   // between the wall pillars
        // crypt walls: west (x = cx0-1), east (x = cx1+1) and the far south wall (z = cz1+1)
        opals += opalVein(c, cx0 - 1, cf + 2, 172, true);
        opals += opalVein(c, cx0 - 1, cf + 2, 180, true);
        opals += opalVein(c, cx1 + 1, cf + 2, 172, true);
        opals += opalVein(c, cx1 + 1, cf + 2, 180, true);
        opals += opalVein(c, 9, cf + 2, cz1 + 1, false);
        opals += opalVein(c, 25, cf + 2, cz1 + 1, false);
        x.layout.area("gather.tomb_opal", Box.of(px0 - 1, cf, pz0 - 1, px1 + 1, pf + 6, pz1 + 1));
        x.layout.data("crimson.tomb.opals", String.valueOf(opals));

        x.layout.area("mobs.smoldering_tomb", Box.of(px0 + 1, pf, pz0 + 1, px1 - 1, pf + 4, pz1 - 1));
        x.layout.area("mobs.tomb_depths", Box.of(cx0 + 1, cf, cz0 + 1, cx1 - 1, cf + 3, cz1 - 1));
        x.layout.area("mobs.bezal_flats", Box.of(-60, ty - 6, 100, -24, ty + 10, 132));
        x.layout.zone("crimson_tomb", "Smoldering Tomb", 'c', Box.of(-20, cf - 4, 132, 50, ty + 24, 198), null);
        x.layout.standAt("npc.frantic_engineer", 21, ty, 137, 180f);
        x.layout.standAt("crimson.tomb_crypt", 6, cf, 170, 0f);
        x.stops.put("tomb", new int[]{15, ty, 136});
        x.layout.standAt("warp.tomb", 15, ty, 135, 0f);
        x.soul(cx1 - 1, cf, cz1 - 1);
        x.soul(px1 - 1, pf, pz0 + 1);
    }

    /**
     * A 2 x 2 opal vein replacing wall blocks, from {@code (x, y, z)} up one and along the wall (z when
     * {@code alongZ}, else x). Only replaces solid wall blocks, so it can never open a hole. Returns blocks placed.
     */
    private static int opalVein(VoxelCanvas c, int x, int y, int z, boolean alongZ) {
        int n = 0;
        for (int a = 0; a <= 1; a++)
            for (int dy = 0; dy <= 1; dy++) {
                int bx = alongZ ? x : x + a, bz = alongZ ? z + a : z;
                if (c.isAir(bx, y + dy, bz)) continue;
                c.set(bx, y + dy, bz, "white_stained_glass");
                n++;
            }
        return n;
    }

    // ---- the Forgotten Skull and the leviathan dock -------------------------------------------

    private static void skull(CCtx x) {
        VoxelCanvas c = x.c;
        int sy = x.y(CPlan.SKULL);
        int cx = 156, cz = -130;
        double rx = 13, ry = 12, rz = 12;
        int cy = sy + 9;
        x.res.reserve("skull", Box.of(cx - 15, sy - 6, cz - 15, cx + 15, sy + 24, cz + 16));
        Palette bone = Palette.of("bone_block[axis=y]:8,calcite:2,bone_block[axis=x]:1");
        for (int dy = -(int) ry; dy <= ry; dy++) {
            int y = cy + dy;
            if (y < sy) continue;
            for (int dz = -(int) rz; dz <= rz + 2; dz++)
                for (int dx = -(int) rx; dx <= rx; dx++) {
                    double ez = dz > 0 ? dz / (rz + (dy < 0 ? 2.5 : 0)) : dz / rz;       // a jaw that juts south
                    double d = (dx / rx) * (dx / rx) + (dy / ry) * (dy / ry) + ez * ez;
                    if (d > 1) continue;
                    boolean shell = d > 0.72;
                    int bx = cx + dx, bz = cz + dz;
                    if (y == sy) { c.set(bx, y, bz, shell ? bone.at(bx, y, bz) : "polished_blackstone"); Builders.footing(c, bx, y, bz, Fill.of("blackstone"), 10); continue; }
                    c.set(bx, y, bz, shell ? bone.at(bx, y, bz) : "air");
                }
        }
        // the mouth: a doorway on the south face with teeth
        int mz = cz + (int) rz + 2;
        for (int z = cz + 6; z <= mz + 1; z++)
            for (int dx = -3; dx <= 3; dx++)
                for (int y = sy + 1; y <= sy + 4; y++) c.set(cx + dx, y, z, "air");
        for (int dx = -3; dx <= 3; dx++) { c.set(cx + dx, sy, mz + 1, "polished_blackstone"); Builders.footing(c, cx + dx, sy, mz + 1, Fill.of("blackstone"), 8); }
        // eyes: deep sockets lit from within
        for (int side : new int[]{-1, 1}) {
            int ex = cx + side * 5, ey = cy + 3;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx * dx + dy * dy > 5) continue;
                    for (int z = cz + 7; z <= cz + 13; z++) c.set(ex + dx, ey + dy, z, "air");
                    c.set(ex + dx, ey + dy, cz + 6, dx == 0 && dy == 0 ? "shroomlight" : "black_concrete");
                }
        }
        // inside: lanterns, the devotee and the armour-smith at their benches
        c.set(cx - 6, sy + 1, cz - 4, "smithing_table");
        c.set(cx - 7, sy + 1, cz - 4, "anvil[facing=north]");
        c.set(cx + 6, sy + 1, cz - 4, "enchanting_table");
        for (int[] l : new int[][]{{-8, 0}, {8, 0}, {0, -8}}) c.set(cx + l[0], sy + 1, cz + l[1], "soul_lantern[hanging=false,waterlogged=false]");
        x.layout.standAt("npc.devotee", cx + 5, sy, cz - 2, 180f);
        x.layout.standAt("npc.crimson_armorsmith", cx - 5, sy, cz - 2, 180f);
        x.stops.put("skull", new int[]{cx, sy, mz + 2});
        x.layout.standAt("warp.skull", cx, sy, mz + 2, 180f);   // the Dusty Travel Scroll's fast travel point, at the mouth
        x.soul(cx + 2, sy, cz - 3);
        x.layout.zone("crimson_skull", "Forgotten Skull", '7', Box.of(130, sy - 10, -160, 182, sy + 30, -100), null);

        // ---- the leviathan dock: a pier out past the north coast ----
        int px = cx;
        int pz = cz - 16;
        while (x.h.has(px, pz - 1) && pz > CPlan.BOUNDS.minZ() + 30) pz--;
        int coastY = x.ground(px, pz + 1);
        int deckY = Math.max(coastY, CPlan.BASE_Y - 2);
        int end = pz - 14;
        for (int z = pz + 2; z >= end; z--)
            for (int dx = -2; dx <= 2; dx++) {
                boolean rail = Math.abs(dx) == 2;
                c.set(px + dx, deckY, z, rail ? "crimson_planks" : (z % 3 == 0 ? "stripped_crimson_stem[axis=z]" : "crimson_planks"));
                for (int h = 1; h <= 3; h++) if (Builders.natural(c.get(px + dx, deckY + h, z)) && !rail) c.set(px + dx, deckY + h, z, "air");
                if (rail) c.set(px + dx, deckY + 1, z, z % 4 == 0 ? "crimson_fence" : "nether_brick_fence");
                if (rail && z % 6 == 0) {
                    c.set(px + dx, deckY + 2, z, "soul_lantern[hanging=false,waterlogged=false]");
                    for (int h = 1; h <= 6; h++) c.set(px + dx, deckY - h, z, "crimson_stem[axis=y]");
                }
            }
        for (int dx = -2; dx <= 2; dx++) c.set(px + dx, deckY + 1, end - 1, "nether_brick_fence");
        x.layout.standAt("npc.leviathan_dock", px, deckY, end + 2, 0f);
        x.stops.put("dock", new int[]{px, deckY, pz + 3});
        x.layout.area("leviathan_passage", Box.of(px - 2, deckY + 1, end, px + 2, deckY + 3, end + 4));
        x.layout.zone("crimson_dock", "Leviathan's Hollow", 'c', Box.of(px - 6, deckY - 8, end - 2, px + 6, deckY + 12, pz + 3), null);
        x.res.reserve("dock", Box.of(px - 3, deckY - 8, end - 2, px + 3, deckY + 6, pz + 3));
    }

    // ---- the Dojo -----------------------------------------------------------------------------

    static final int DOJO_X0 = 88, DOJO_X1 = 112, DOJO_Z0 = -110, DOJO_Z1 = -86;

    private static void dojo(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.DOJO);
        x.res.reserve("dojo_arena", Box.of(DOJO_X0 - 1, y - 6, DOJO_Z0 - 1, DOJO_X1 + 1, y + 8, DOJO_Z1 + 3));
        for (int z = DOJO_Z0; z <= DOJO_Z1; z++)
            for (int xx = DOJO_X0; xx <= DOJO_X1; xx++) {
                boolean wall = xx == DOJO_X0 || xx == DOJO_X1 || z == DOJO_Z0 || z == DOJO_Z1;
                boolean border = xx == DOJO_X0 + 1 || xx == DOJO_X1 - 1 || z == DOJO_Z0 + 1 || z == DOJO_Z1 - 1;
                c.set(xx, y, z, wall ? "dark_oak_planks" : border ? "red_terracotta" : (((xx + z) & 1) == 0 ? "smooth_stone" : "white_terracotta"));
                Builders.footing(c, xx, y, z, Fill.of("blackstone"), 10);
                for (int h = 1; h <= 6; h++) c.set(xx, y + h, z, "air");
                if (!wall) continue;
                boolean gateS = z == DOJO_Z1 && Math.abs(xx - 100) <= 2;
                boolean gateN = z == DOJO_Z0 && Math.abs(xx - 100) <= 1;
                if (gateS || gateN) continue;
                boolean post = (xx - DOJO_X0) % 4 == 0 && (z == DOJO_Z0 || z == DOJO_Z1) || (z - DOJO_Z0) % 4 == 0 && (xx == DOJO_X0 || xx == DOJO_X1);
                for (int h = 1; h <= 3; h++) c.set(xx, y + h, z, post ? "stripped_dark_oak_log[axis=y]" : (h == 2 ? "red_wool" : "dark_oak_planks"));
                c.set(xx, y + 4, z, Blocks.slab("dark_oak", "bottom"));
            }
        // torii at the south gate
        for (int side : new int[]{-3, 3}) for (int h = 1; h <= 6; h++) c.set(100 + side, y + h, DOJO_Z1 + 2, "red_concrete");
        for (int dx = -4; dx <= 4; dx++) { c.set(100 + dx, y + 7, DOJO_Z1 + 2, "black_concrete"); if (Math.abs(dx) <= 3) c.set(100 + dx, y + 5, DOJO_Z1 + 2, "red_concrete"); }
        for (int dx = -3; dx <= 3; dx++) for (int dz = 1; dz <= 3; dz++) { c.set(100 + dx, y, DOJO_Z1 + dz, "polished_blackstone_bricks"); Builders.footing(c, 100 + dx, y, DOJO_Z1 + dz, Fill.of("blackstone"), 8); }
        // the master's hall to the north
        Builders.House hall = new Builders.House(90, y, -124, 21, 11);
        hall.door = "south"; hall.wall = Palette.of("white_terracotta:4,stripped_dark_oak_log[axis=y]:1");
        hall.frame = "dark_oak_log"; hall.foundation = Palette.of("polished_blackstone:3,blackstone:1");
        hall.floor = Fill.of("spruce_planks"); hall.roof = "dark_oak"; hall.roofStyle = "hip"; hall.overhang = 2;
        hall.doorWood = "dark_oak"; hall.window = "red_stained_glass_pane"; hall.seed = x.seed;
        if (x.res.reserve("dojo_hall", hall.reserve())) hall.build(c);
        x.layout.standAt("npc.dojo_master", hall.inside[0], hall.inside[1], hall.inside[2], 0f);
        x.layout.standAt("dojo.center", 100, y, -98, 180f);
        x.layout.standAt("dojo.spectate", 100, y, DOJO_Z1 + 3, 180f);
        x.layout.area("dojo.arena", Box.of(DOJO_X0 + 1, y + 1, DOJO_Z0 + 1, DOJO_X1 - 1, y + 4, DOJO_Z1 - 1));
        x.layout.data("dojo.floor_y", String.valueOf(y));
        x.layout.zone("crimson_dojo", "Dojo", 'e', Box.of(84, y - 4, -126, 120, y + 20, -80), null, Layout.Zone.SAFE);
        x.stops.put("dojo", new int[]{100, y, DOJO_Z1 + 4});
        x.soul(DOJO_X0 + 2, y, DOJO_Z0 + 2);
        for (int[] p : new int[][]{{86, -84}, {116, -84}}) Builders.lampPost(c, p[0], y, p[1], "post", "south");
    }

    // ---- Ruins of the Twin Pyres (the Cindermaw) -------------------------------------------------

    private static void ashfang(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.ASHFANG);
        int cx = 111, cz = 161;
        x.res.reserve("ashfang", Box.of(cx - 17, y - 6, cz - 17, cx + 17, y + 12, cz + 17));
        Palette fl = Palette.of("blackstone:3,cracked_polished_blackstone_bricks:3,polished_basalt[axis=y]:2,basalt[axis=y]:1");
        long s = Hash.salt(x.seed, "ashfang");
        for (int dz = -16; dz <= 16; dz++)
            for (int dx = -16; dx <= 16; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 16 || Hash.unit(s, dx, dz) < (d - 12) / 6) continue;
                int bx = cx + dx, bz = cz + dz;
                c.set(bx, y, bz, fl.at(bx, y, bz));
                Builders.footing(c, bx, y, bz, Fill.of("blackstone"), 8);
                for (int h = 1; h <= 4; h++) if (Builders.natural(c.get(bx, y + h, bz))) c.set(bx, y + h, bz, "air");
            }
        for (int a = 0; a < 12; a++) {
            double ang = a * Math.PI / 6;
            int px = cx + (int) Math.round(Math.cos(ang) * 13), pz = cz + (int) Math.round(Math.sin(ang) * 13);
            CKit.ruinPillar(c, px, y, pz, 9, CKit.BLACK_BRICK, x.seed + a);
        }
        // the twin pyres
        for (int side : new int[]{-4, 4}) {
            for (int h = 1; h <= 3; h++) c.set(cx + side, y + h, cz, "polished_blackstone_bricks");
            c.set(cx + side, y + 4, cz, "magma_block");
            c.set(cx + side, y + 5, cz, Blocks.campfire(true, "north"));
        }
        // broken wall fragments
        for (int[] w : new int[][]{{-14, -6, 1}, {10, 12, 0}, {-8, 14, 0}}) {
            for (int i = 0; i < 6; i++) {
                int bx = cx + w[0] + (w[2] == 1 ? 0 : i), bz = cz + w[1] + (w[2] == 1 ? i : 0);
                int hh = 1 + (int) (Hash.unit(s, bx, bz) * 4);
                for (int h = 1; h <= hh; h++) c.set(bx, y + h, bz, CKit.BLACK_BRICK.at(bx, y + h, bz));
                Builders.footing(c, bx, y + 1, bz, Fill.of("blackstone"), 6);
            }
        }
        x.layout.standAt("boss.ashfang", cx, y, cz + 3, 0f);
        x.layout.zone("crimson_ashfang", "Ruins of the Twin Pyres", 'c', Box.of(cx - 22, y - 6, cz - 22, cx + 22, y + 24, cz + 22), null);
        x.stops.put("ashfang.w", new int[]{cx - 16, y, cz});
        x.stops.put("ashfang.e", new int[]{cx + 16, y, cz - 2});
        x.soul(cx + 13, y, cz + 3);
    }

    // ---- The Dukedom: the barbarian duke's ruined keep -----------------------------------------------

    private static void dukedom(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.DUKEDOM);
        int x0 = -206, x1 = -164, z0 = 114, z1 = 158;
        x.res.reserve("dukedom", Box.of(x0 - 1, y - 6, z0 - 1, x1 + 1, y + 12, z1 + 1));
        Palette stone = Palette.of("blackstone:4,cobblestone:2,mossy_cobblestone:1,polished_blackstone_bricks:2,cracked_polished_blackstone_bricks:2");
        long s = Hash.salt(x.seed, "dukedom");
        for (int z = z0; z <= z1; z++)
            for (int xx = x0; xx <= x1; xx++) {
                boolean wall = xx == x0 || xx == x1 || z == z0 || z == z1;
                c.set(xx, y, z, wall ? stone.at(xx, y, z) : (Hash.unit(s, xx, z) < 0.25 ? "gravel" : "packed_mud"));
                Builders.footing(c, xx, y, z, Fill.of("blackstone"), 8);
                for (int h = 1; h <= 4; h++) if (Builders.natural(c.get(xx, y + h, z))) c.set(xx, y + h, z, "air");
                if (!wall) continue;
                boolean gate = (xx == x1 && Math.abs(z - 136) <= 3) || (z == z0 && Math.abs(xx - (x0 + x1) / 2) <= 2);
                if (gate) continue;
                int hh = 3 + (int) Math.round(4 * (0.5 + 0.5 * Math.sin(xx * 0.4 + z * 0.3)) * Hash.unit(s, xx, 7, z));
                for (int h = 1; h <= hh; h++) c.set(xx, y + h, z, stone.at(xx, y + h, z));
            }
        // throne dais on the north side
        int tx = (x0 + x1) / 2;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 0; dz <= 2; dz++) c.set(tx + dx, y + 1, z0 + 2 + dz, dz == 2 ? Blocks.stairs("polished_blackstone_brick", "north") : "polished_blackstone_bricks");
        c.set(tx, y + 2, z0 + 3, Blocks.stairs("blackstone", "south"));
        c.set(tx - 1, y + 2, z0 + 3, "polished_blackstone_wall");
        c.set(tx + 1, y + 2, z0 + 3, "polished_blackstone_wall");
        c.set(tx, y + 2, z0 + 2, "polished_blackstone_bricks");
        c.set(tx, y + 3, z0 + 2, "gold_block");
        CKit.bannerPole(c, tx - 3, y + 1, z0 + 2, "orange", 0);
        CKit.bannerPole(c, tx + 3, y + 1, z0 + 2, "orange", 0);
        // camp: tents and fires
        for (int[] t : new int[][]{{-198, 150}, {-176, 150}, {-198, 124}}) tent(c, t[0], y, t[1]);
        Builders.campfire(c, -187, y, 146);
        x.layout.standAt("boss.barbarian_duke", tx, y, 136, 0f);
        x.layout.area("mobs.dukedom", Box.of(x0 + 2, y, z0 + 6, x1 - 2, y + 4, z1 - 2));
        x.layout.zone("crimson_dukedom", "The Dukedom", 'c', Box.of(x0 - 8, y - 6, z0 - 8, x1 + 8, y + 20, z1 + 8), null);
        x.stops.put("dukedom", new int[]{x1 + 3, y, 136});
        x.soul(x0 + 2, y, z1 - 2);
    }

    private static void tent(VoxelCanvas c, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++)
            for (int h = 1; h <= 3; h++) {
                int w = 3 - h;
                for (int dz = -w; dz <= w; dz++) {
                    boolean edge = Math.abs(dz) == w;
                    if (edge || dx == -2 || dx == 2) c.set(x + dx, y + h, z + dz, (dx + h) % 2 == 0 ? "red_wool" : "brown_wool");
                }
            }
        c.set(x + 2, y + 1, z, "air");
        c.set(x + 2, y + 2, z, "air");
    }

    // ---- the mages' Courtyard -------------------------------------------------------------------------

    private static void courtyard(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.COURTYARD);
        int x0 = 160, x1 = 210, z0 = 96, z1 = 146;
        int cx = 185, cz = 121;
        x.res.reserve("courtyard_plaza", Box.of(cx - 8, y - 6, cz - 8, cx + 8, y + 10, cz + 8));
        for (int dz = -7; dz <= 7; dz++)
            for (int dx = -7; dx <= 7; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 7.3) continue;
                c.set(cx + dx, y, cz + dz, d > 6.3 ? "chiseled_polished_blackstone" : (((dx + dz) & 1) == 0 ? "red_nether_bricks" : "polished_blackstone_bricks"));
                Builders.footing(c, cx + dx, y, cz + dz, Fill.of("blackstone"), 8);
            }
        // the garden wall: low red brick with crimson fence on top, gates north/west/south
        for (int z = z0; z <= z1; z++)
            for (int xx = x0; xx <= x1; xx++) {
                boolean wall = xx == x0 || xx == x1 || z == z0 || z == z1;
                if (!wall) continue;
                boolean gate = (z == z0 && Math.abs(xx - cx) <= 2) || (xx == x0 && Math.abs(z - cz) <= 2) || (z == z1 && Math.abs(xx - cx) <= 2);
                if (gate) continue;
                int g = x.ground(xx, z, y + 12);
                c.set(xx, g + 1, z, "red_nether_bricks");
                c.set(xx, g + 2, z, "red_nether_bricks");
                c.set(xx, g + 3, z, "red_nether_brick_wall");
            }
        // planters with fungi and statues of the mage order
        for (int[] p : new int[][]{{170, 106}, {200, 106}, {170, 136}, {200, 136}}) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                int g = x.ground(p[0] + dx, p[1] + dz, y + 12);
                c.set(p[0] + dx, g + 1, p[1] + dz, rim ? "polished_blackstone_bricks" : "crimson_nylium");
            }
            int g = x.ground(p[0], p[1], y + 12);
            CKit.fungus(c, p[0], g, p[1], Hash.hash(x.seed, p[0], 1, p[1]), 0.9, false);
            x.res.reserve("courtyard_planter" + p[0] + p[1], Box.of(p[0] - 3, g - 2, p[1] - 3, p[0] + 3, g + 12, p[1] + 3));
        }
        for (int[] st : new int[][]{{cx - 10, cz}, {cx + 10, cz}}) {
            int g = x.ground(st[0], st[1], y + 12);
            c.set(st[0], g + 1, st[1], "polished_blackstone_bricks");
            c.set(st[0], g + 2, st[1], "blackstone_wall");
            c.set(st[0], g + 3, st[1], "polished_blackstone");
            c.set(st[0], g + 4, st[1], "crying_obsidian");
            x.res.reserve("courtyard_statue" + st[0], Box.of(st[0] - 1, g - 2, st[1] - 1, st[0] + 1, g + 6, st[1] + 1));
        }
        x.layout.standAt("boss.mage_outlaw", cx, y, cz, 270f);
        x.layout.area("mobs.courtyard", Box.of(x0 + 2, y - 3, z0 + 2, x1 - 2, y + 6, z1 - 2));
        x.layout.zone("crimson_courtyard", "Courtyard", 'c', Box.of(x0 - 4, y - 8, z0 - 4, x1 + 4, y + 24, z1 + 4), null);
        x.stops.put("courtyard.w", new int[]{x0 - 3, y, cz});
        x.stops.put("courtyard.n", new int[]{cx, y, z0 - 3});
        x.soul(x1 - 2, y, z1 - 2);
    }

    // ---- district zones + spawn areas in the open -----------------------------------------------------

    private static void districtZones(CCtx x) {
        int b = CPlan.BASE_Y;
        x.layout.zone("crimson_isle", "Crimson Isle", 'c', CPlan.BOUNDS, null);
        x.layout.zone("crimson_fields", "Crimson Fields", 'c', Box.of(-112, b - 30, -66, 112, b + 60, 36), null);
        x.layout.zone("crimson_marsh", "Mystic Marsh", '5', Box.of(-240, b - 30, -215, -80, b + 60, -54), null);
        x.layout.zone("crimson_desert", "Burning Desert", '6', Box.of(-160, b - 30, 88, -30, b + 60, 225), null);
        x.layout.area("mobs.crimson_fields", Box.of(-80, b - 8, -58, 80, b + 14, 30));
        x.layout.area("mobs.fields_sky", Box.of(-70, b - 8, -55, 70, b + 14, 20));
        x.layout.area("mobs.burning_desert", Box.of(-150, b - 8, 92, -50, b + 14, 205));
        // East edge at -128, not -90: the old box swallowed Cinder Shore's lava pool (x -103..-93), so Lv80 Mushroom
        // Bulls spawned and wandered beside the fishers (wave-4 playtest). -128 keeps every spawn spot >= 24 blocks
        // (mob.aggro-range 16 + a margin) from the pool and 8 outside the Cinder Shore NO_MOBS zone.
        x.layout.area("mobs.mystic_marsh", Box.of(-215, b - 10, -195, -128, b + 12, -58));
        // where the crimson module lets players gather (break handler + regeneration)
        x.layout.area("gather.volcano", Box.of(-78, b - 6, -222, 78, x.rimY + 2, -64));
        x.layout.area("gather.desert", Box.of(-160, b - 12, 88, -30, b + 16, 225));
        x.layout.area("gather.marsh", Box.of(-240, b - 12, -215, -80, b + 16, -54));
    }
}
