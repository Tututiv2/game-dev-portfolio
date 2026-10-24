package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor IV: Bramblemaw's grove (research 04 sec 3.13: spirit animals, the Spirit Bear and Bow, green healing and
 * orange burning circles). A round clearing walled by a tangle of logs and leaves, a mound in the middle where
 * Bramblemaw roots itself, two green and two orange circles painted into the moss. Points: spawn, boss, chests,
 * circle.green.0/1, circle.orange.0/1, animal.0..7.
 */
public final class GroveArena implements BossArena {

    public static final GroveArena INSTANCE = new GroveArena();
    public static final int CIRCLE_R = 4;

    @Override public String id() { return "f4_grove"; }

    @Override public int sizeX() { return 73; }

    @Override public int sizeZ() { return 73; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        int cx = ox + 36, cz = oz + 36, r = 33;
        Palette moss = Palette.of("moss_block:5,grass_block[snowy=false]:3,podzol[snowy=false]:1,rooted_dirt:1");
        Palette wall = Palette.of("dark_oak_log[axis=y]:3,azalea_leaves[distance=1,persistent=true,waterlogged=false]:3,oak_leaves[distance=1,persistent=true,waterlogged=false]:2,mossy_cobblestone:1");
        Palette trim = Palette.of("mossy_cobblestone:2,dark_oak_log[axis=y]:1");
        Palette roof = Palette.of("azalea_leaves[distance=1,persistent=true,waterlogged=false]:4,flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]:2,shroomlight:1");
        ArenaKit.round(c, cx, cz, r, fy, 16, moss, wall, trim, roof);
        // grass tufts and flowers (decoration only, never on the circles or the mound)
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d < 7 || d > r - 2) continue;
                long h = (x * 73856093L) ^ (z * 19349663L) ^ seed;
                int roll = (int) Math.floorMod(h, 100);
                if (roll < 6) c.set(x, fy + 1, z, "short_grass");
                else if (roll < 8) c.set(x, fy + 1, z, roll == 6 ? "blue_orchid" : "allium");
            }
        // the mound
        for (int x = cx - 5; x <= cx + 5; x++)
            for (int z = cz - 5; z <= cz + 5; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 5) continue;
                int top = fy + (d < 2.5 ? 3 : d < 4 ? 2 : 1);
                for (int y = fy + 1; y <= top; y++) c.set(x, y, z, y == top ? "moss_block" : "rooted_dirt");
                c.set(x, top + 1, z, "air");
            }
        // painted circles: green heals the players in it, orange burns the spirit animals in it
        double[] ga = {Math.PI / 4, Math.PI * 5 / 4}, oa = {Math.PI * 3 / 4, Math.PI * 7 / 4};
        for (int i = 0; i < 2; i++) {
            int gx = cx + (int) Math.round(Math.cos(ga[i]) * 18), gz = cz + (int) Math.round(Math.sin(ga[i]) * 18);
            ArenaKit.disc(c, gx, gz, CIRCLE_R, fy, Palette.of("moss_block"));
            ArenaKit.ring(c, gx, gz, CIRCLE_R - 0.6, CIRCLE_R, fy, "lime_concrete");
            c.set(gx, fy, gz, "verdant_froglight[axis=y]");
            for (int y = fy + 1; y <= fy + 4; y++) c.set(gx, y, gz, "air");
            out.put("circle.green." + i, gx, fy + 1, gz);
            int px = cx + (int) Math.round(Math.cos(oa[i]) * 18), pz = cz + (int) Math.round(Math.sin(oa[i]) * 18);
            ArenaKit.disc(c, px, pz, CIRCLE_R, fy, Palette.of("coarse_dirt"));
            ArenaKit.ring(c, px, pz, CIRCLE_R - 0.6, CIRCLE_R, fy, "orange_concrete");
            c.set(px, fy, pz, "ochre_froglight[axis=y]");
            out.put("circle.orange." + i, px, fy + 1, pz);
        }
        // a ring of old stumps where the spirit animals climb out
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * 2 * k / 8 + Math.PI / 8;
            int ax = cx + (int) Math.round(Math.cos(a) * 26), az = cz + (int) Math.round(Math.sin(a) * 26);
            c.set(ax + 1, fy + 1, az, "dark_oak_log[axis=y]");
            c.set(ax + 1, fy + 2, az, "lantern[hanging=false,waterlogged=false]");
            out.put("animal." + k, ax, fy + 1, az);
        }
        out.put("spawn", cx, fy + 1, cz + 28);
        out.put("boss", cx, fy + 4, cz);
        out.put("chests", cx, fy + 1, cz - 22);
    }
}
