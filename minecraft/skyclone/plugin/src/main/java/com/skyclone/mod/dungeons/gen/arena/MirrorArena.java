package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor V: Lurid's hall of mirrors (research 04 sec 3.13: eight copies, the real one's colour is the wool block near
 * the ceiling's centre). A square, low-lit hall with a glass lantern well in the middle of the ceiling: the run sets
 * the wool block in it ({@code ceiling.wool}) to the real Lurid's colour. Points: spawn, boss, chests, ceiling.wool,
 * clone.0..8.
 */
public final class MirrorArena implements BossArena {

    public static final MirrorArena INSTANCE = new MirrorArena();
    public static final int H = 15;

    @Override public String id() { return "f5_mirror"; }

    @Override public int sizeX() { return 63; }

    @Override public int sizeZ() { return 63; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        int x0 = ox, x1 = ox + 62, z0 = oz, z1 = oz + 62, cx = ox + 31, cz = oz + 31;
        Palette wall = Palette.of("polished_deepslate:3,deepslate_tiles:3,tinted_glass:1");
        Palette trim = Palette.of("chiseled_deepslate");
        ArenaKit.hall(c, x0, z0, x1, z1, fy, H, Palette.of("black_concrete"), wall, trim, Palette.of("deepslate_tiles"), "sea_lantern", 7);
        // a checkered mirror floor
        for (int x = x0 + 1; x < x1; x++)
            for (int z = z0 + 1; z < z1; z++) c.set(x, fy, z, ((x + z) & 1) == 0 ? "black_glazed_terracotta" : "polished_blackstone");
        // the ceiling well: a 5x5 shaft up through the ceiling, glass walls, the wool block at its top
        for (int x = cx - 2; x <= cx + 2; x++)
            for (int z = cz - 2; z <= cz + 2; z++) {
                boolean edge = Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2;
                for (int y = fy + H; y <= fy + H + 3; y++) c.set(x, y, z, edge ? "glass" : "air");
                c.set(x, fy + H + 4, z, "deepslate_tiles");
            }
        c.set(cx, fy + H + 3, cz, "white_wool");
        c.set(cx + 1, fy + H + 3, cz + 1, "sea_lantern");
        c.set(cx - 1, fy + H + 3, cz - 1, "sea_lantern");
        out.put("ceiling.wool", cx, fy + H + 3, cz);
        // nine standing mirrors round the room (tinted glass slabs on stone frames) where the copies appear
        for (int k = 0; k < 9; k++) {
            double a = Math.PI * 2 * k / 9;
            int px = cx + (int) Math.round(Math.cos(a) * 12), pz = cz + (int) Math.round(Math.sin(a) * 12);
            out.put("clone." + k, px, fy + 1, pz);
            int mx = cx + (int) Math.round(Math.cos(a) * 24), mz = cz + (int) Math.round(Math.sin(a) * 24);
            for (int y = fy + 1; y <= fy + 4; y++) c.set(mx, y, mz, y == fy + 1 || y == fy + 4 ? "polished_deepslate" : "tinted_glass");
        }
        // low lights at the walls
        for (int x = x0 + 4; x < x1; x += 6) { c.set(x, fy + 3, z0, "verdant_froglight[axis=y]"); c.set(x, fy + 3, z1, "pearlescent_froglight[axis=y]"); }
        for (int z = z0 + 4; z < z1; z += 6) { c.set(x0, fy + 3, z, "ochre_froglight[axis=y]"); c.set(x1, fy + 3, z, "pearlescent_froglight[axis=y]"); }
        out.put("spawn", cx, fy + 1, z1 - 5);
        out.put("boss", cx, fy + 1, cz);
        out.put("chests", cx, fy + 1, z0 + 8);
    }
}
