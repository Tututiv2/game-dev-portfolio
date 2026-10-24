package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor III: the Lecturer's drowned lecture hall (research 04 sec 3.13: four Guardians on pedestals, the Professor
 * behind a barrier). Prismarine nave, four raised pedestals in the corners (standing on one gets you body-slammed),
 * and the same barred balcony as floor II. Points: spawn, boss, drop, barrier.a/b, guardian.healthy|reinforced|chaos|
 * laser, chests.
 */
public final class TombArena implements BossArena {

    public static final TombArena INSTANCE = new TombArena();
    public static final String[] GUARDIANS = {"healthy", "reinforced", "chaos", "laser"};

    @Override public String id() { return "f3_tomb"; }

    @Override public int sizeX() { return 71; }

    @Override public int sizeZ() { return 79; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        int x0 = ox, x1 = ox + 70, z0 = oz, z1 = oz + 78, cx = ox + 35;
        Palette floorP = Palette.of("prismarine_bricks:4,dark_prismarine:2,prismarine:2");
        Palette wall = Palette.of("prismarine:4,prismarine_bricks:3,dark_prismarine:1");
        Palette trim = Palette.of("dark_prismarine");
        ArenaKit.hall(c, x0, z0, x1, z1, fy, 20, floorP, wall, trim, Palette.of("dark_prismarine"), "sea_lantern", 6);
        // shallow channels of "water" glass along the nave (cosmetic, walkable)
        for (int z = z0 + 16; z < z1 - 2; z++) { c.set(cx - 9, fy, z, "light_blue_stained_glass"); c.set(cx + 9, fy, z, "light_blue_stained_glass"); }
        int by = fy + 5;
        ArenaKit.fill(c, x0 + 1, fy + 1, z0 + 1, x1 - 1, by, z0 + 12, "dark_prismarine");
        ArenaKit.fill(c, x0 + 1, by, z0 + 1, x1 - 1, by, z0 + 12, Palette.of("prismarine_bricks:3,sea_lantern:1"));
        for (int x = x0 + 1; x <= x1 - 1; x++) if (x < cx - 2 || x > cx + 2) { c.set(x, by + 1, z0 + 12, "light_blue_stained_glass"); c.set(x, by + 2, z0 + 12, "light_blue_stained_glass"); }
        ArenaKit.fill(c, cx - 3, by + 1, z0 + 2, cx + 3, by + 1, z0 + 3, "bookshelf");
        c.set(cx, by + 1, z0 + 5, "lectern[facing=south,has_book=false,powered=false]");
        ArenaKit.stairs(c, cx - 2, cx + 2, z0 + 17, fy + 1, 5, "north", "prismarine_brick_stairs", "dark_prismarine");
        ArenaKit.fill(c, cx - 2, fy + 1, z0 + 18, cx + 2, fy + 5, z0 + 18, "iron_bars");
        out.put("barrier.a", cx - 2, fy + 1, z0 + 18);
        out.put("barrier.b", cx + 2, fy + 5, z0 + 18);
        // four guardian pedestals: raised 2 blocks, sea lanterns at the corners
        int[][] ped = {{x0 + 14, z0 + 30}, {x1 - 14, z0 + 30}, {x0 + 14, z1 - 16}, {x1 - 14, z1 - 16}};
        for (int i = 0; i < 4; i++) {
            int px = ped[i][0], pz = ped[i][1];
            ArenaKit.fill(c, px - 3, fy + 1, pz - 3, px + 3, fy + 2, pz + 3, "dark_prismarine");
            ArenaKit.fill(c, px - 2, fy + 2, pz - 2, px + 2, fy + 2, pz + 2, "prismarine_bricks");
            for (int[] d : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) c.set(px + d[0], fy + 2, pz + d[1], "sea_lantern");
            out.put("guardian." + GUARDIANS[i], px, fy + 4, pz);
        }
        // columns
        for (int z = z0 + 20; z <= z1 - 6; z += 12) {
            ArenaKit.column(c, x0 + 3, z, 1.2, fy + 1, 19, Palette.of("prismarine_bricks"));
            ArenaKit.column(c, x1 - 3, z, 1.2, fy + 1, 19, Palette.of("prismarine_bricks"));
        }
        out.put("spawn", cx, fy + 1, z1 - 5);
        out.put("boss", cx, by + 1, z0 + 7);
        out.put("drop", cx, fy + 1, z0 + 34);
        out.put("chests", cx, fy + 1, z0 + 44);
    }
}
