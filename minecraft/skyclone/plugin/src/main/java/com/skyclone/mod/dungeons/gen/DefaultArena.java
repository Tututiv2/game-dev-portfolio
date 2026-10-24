package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * The arena used for any floor whose boss has no registered {@code BossFloor} yet: a round, walled, roofed hall with a
 * ring of pillars and braziers. Pure. Points: spawn (south end), boss (centre), chests (north end).
 */
public final class DefaultArena implements BossArena {

    public static final DefaultArena INSTANCE = new DefaultArena();

    @Override public String id() { return "default"; }

    @Override public int sizeX() { return 61; }

    @Override public int sizeZ() { return 61; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        Theme t = Theme.of(floor.theme);
        int cx = ox + 30, cz = oz + 30;
        int r = 28;
        Palette floorP = Palette.of("polished_andesite:3,stone_bricks:3,smooth_stone:2,andesite:1");
        for (int x = ox; x < ox + 61; x++)
            for (int z = oz; z < oz + 61; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > r + 1.5) continue;
                c.set(x, fy - 1, z, "stone");
                c.set(x, fy, z, d < 6 ? ((x + z) % 2 == 0 ? t.accent : "polished_blackstone") : floorP.at(x, fy, z));
                c.set(x, fy + 22, z, t.ceiling.at(x, fy + 22, z));
                if (d > r) for (int y = fy + 1; y < fy + 22; y++) c.set(x, y, z, y == fy + 1 || y == fy + 21 ? t.trim.at(x, y, z) : t.wall.at(x, y, z));
            }
        for (int k = 0; k < 10; k++) {
            double a = Math.PI * 2 * k / 10;
            int px = cx + (int) Math.round(Math.cos(a) * 20), pz = cz + (int) Math.round(Math.sin(a) * 20);
            Shapes.cylinder(c, px, fy + 1, pz, 1.6, 21, t.pillar, 0);
            c.set(px + 2, fy + 1, pz, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
            c.set(px, fy + 19, pz + 2, t.lightHanging);
            c.set(px, fy + 20, pz + 2, "iron_chain[axis=y]");
            c.set(px, fy + 21, pz + 2, "iron_chain[axis=y]");
        }
        // ring of red terracotta marking the fight circle
        for (int k = 0; k < 120; k++) {
            double a = Math.PI * 2 * k / 120;
            c.set(cx + (int) Math.round(Math.cos(a) * 14), fy, cz + (int) Math.round(Math.sin(a) * 14), "red_terracotta");
        }
        out.put("spawn", cx, fy + 1, cz + 24);
        out.put("boss", cx, fy + 1, cz);
        out.put("chests", cx, fy + 1, cz - 22);
    }
}
