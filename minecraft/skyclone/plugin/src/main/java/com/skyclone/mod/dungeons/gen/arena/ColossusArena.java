package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor VI: Gorrak's sculpture hall (research 04 sec 3.13: terracottas grown from flower pots, four giants, then
 * the Giant One). A tall terracotta hall: the throne at the north end, two giant alcoves on each long wall, and two
 * rows of planters the terracottas climb out of. Points: spawn, boss (throne), chests, pot.0..11,
 * giant.diamond|laser|bigfoot|boulder.
 */
public final class ColossusArena implements BossArena {

    public static final ColossusArena INSTANCE = new ColossusArena();
    public static final String[] GIANTS = {"diamond", "laser", "bigfoot", "boulder"};
    static final int H = 30;

    @Override public String id() { return "f6_colossus"; }

    @Override public int sizeX() { return 81; }

    @Override public int sizeZ() { return 121; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        int x0 = ox + 10, x1 = ox + 70, z0 = oz, z1 = oz + 120, cx = ox + 40;
        Palette floorP = Palette.of("terracotta:3,orange_terracotta:2,brown_terracotta:1,smooth_sandstone:1");
        Palette wall = Palette.of("terracotta:3,red_terracotta:2,orange_terracotta:1,cut_red_sandstone:1");
        Palette trim = Palette.of("chiseled_red_sandstone");
        ArenaKit.hall(c, x0, z0, x1, z1, fy, H, floorP, wall, trim, Palette.of("cut_red_sandstone"), "shroomlight", 6);
        // four alcoves, 9 deep, cut into the long walls (the giants stand in them)
        int[] az = {z0 + 40, z0 + 78};
        int g = 0;
        for (int zc : az) {
            for (int side = 0; side < 2; side++) {
                int wx = side == 0 ? x0 : x1, dir = side == 0 ? -1 : 1;
                for (int d = 0; d <= 9; d++) {
                    int x = wx + dir * d;
                    for (int z = zc - 7; z <= zc + 7; z++) {
                        c.set(x, fy - 1, z, "stone");
                        c.set(x, fy, z, floorP.at(x, fy, z));
                        boolean edge = d == 9 || z == zc - 7 || z == zc + 7;
                        for (int y = fy + 1; y < fy + H; y++) c.set(x, y, z, edge ? wall.at(x, y, z) : "air");
                        c.set(x, fy + H, z, "cut_red_sandstone");
                    }
                }
                int gx = wx + dir * 5;
                ArenaKit.fill(c, gx - 2, fy, zc - 2, gx + 2, fy, zc + 2, "chiseled_red_sandstone");
                c.set(gx, fy + H - 1, zc, "shroomlight");
                out.put("giant." + GIANTS[g++], gx, fy + 1, zc);
            }
        }
        // the throne dais at the north end
        ArenaKit.fill(c, x0 + 1, fy + 1, z0 + 1, x1 - 1, fy + 4, z0 + 12, "smooth_red_sandstone");
        ArenaKit.stairs(c, cx - 4, cx + 4, z0 + 16, fy + 1, 4, "north", "red_sandstone_stairs", "smooth_red_sandstone");
        ArenaKit.fill(c, cx - 3, fy + 5, z0 + 2, cx + 3, fy + 14, z0 + 3, "chiseled_red_sandstone");
        ArenaKit.fill(c, cx - 2, fy + 15, z0 + 2, cx + 2, fy + 16, z0 + 3, "gold_block");
        // two rows of planters
        for (int i = 0; i < 6; i++) {
            int z = z0 + 30 + i * 14;
            for (int side = 0; side < 2; side++) {
                int px = side == 0 ? x0 + 9 : x1 - 9;
                c.set(px, fy + 1, z, "chiseled_red_sandstone");
                c.set(px, fy + 2, z, "potted_dead_bush");
                out.put("pot." + (i * 2 + side), px + (side == 0 ? 2 : -2), fy + 1, z);
            }
        }
        // braziers between the planters
        for (int z = z0 + 24; z < z1 - 4; z += 14) {
            c.set(cx - 14, fy + 1, z, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
            c.set(cx + 14, fy + 1, z, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
        }
        out.put("spawn", cx, fy + 1, z1 - 6);
        out.put("boss", cx, fy + 5, z0 + 7);
        out.put("drop", cx, fy + 1, z0 + 50);
        out.put("chests", cx, fy + 1, z0 + 60);
    }
}
