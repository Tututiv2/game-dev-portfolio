package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor II: Veilwright's lecture court (research 04 sec 3.13: "Scarf behind a barrier; 4 Undead minions fight").
 * A long nave with four raised pedestals where his students stand, and a balcony at the north end behind iron bars
 * across the only stair; the bars ({@code barrier.a}..{@code barrier.b}) are removed when the students fall.
 * Points: spawn, boss (balcony), drop (where he lands), barrier.a/b, minion.0..3, chests.
 */
public final class CourtArena implements BossArena {

    public static final CourtArena INSTANCE = new CourtArena();

    @Override public String id() { return "f2_court"; }

    @Override public int sizeX() { return 61; }

    @Override public int sizeZ() { return 75; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        int x0 = ox, x1 = ox + 60, z0 = oz, z1 = oz + 74, cx = ox + 30;
        Palette floorP = Palette.of("polished_blackstone_bricks:4,blackstone:2,cracked_polished_blackstone_bricks:1");
        Palette wall = Palette.of("deepslate_bricks:5,cracked_deepslate_bricks:1,deepslate_tiles:2");
        Palette trim = Palette.of("polished_deepslate");
        ArenaKit.hall(c, x0, z0, x1, z1, fy, 18, floorP, wall, trim, Palette.of("deepslate_tiles"), "shroomlight", 6);
        // a carpet aisle down the middle
        for (int z = z0 + 14; z < z1; z++) for (int x = cx - 2; x <= cx + 2; x++) c.set(x, fy, z, x == cx - 2 || x == cx + 2 ? "gold_block" : "red_nether_bricks");
        // the balcony: solid to fy+6 across the north end, glass front, a throne
        int by = fy + 6;
        ArenaKit.fill(c, x0 + 1, fy + 1, z0 + 1, x1 - 1, by, z0 + 12, "deepslate_bricks");
        ArenaKit.fill(c, x0 + 1, by, z0 + 1, x1 - 1, by, z0 + 12, Palette.of("polished_blackstone:3,gilded_blackstone:1"));
        for (int x = x0 + 1; x <= x1 - 1; x++) if (x < cx - 2 || x > cx + 2) { c.set(x, by + 1, z0 + 12, "tinted_glass"); c.set(x, by + 2, z0 + 12, "tinted_glass"); }
        c.set(cx, by + 1, z0 + 3, "dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
        ArenaKit.fill(c, cx - 1, by + 1, z0 + 2, cx + 1, by + 4, z0 + 2, "gilded_blackstone");
        // the stair down to the nave (5 wide), and the iron bars at its foot
        ArenaKit.stairs(c, cx - 2, cx + 2, z0 + 18, fy + 1, 6, "north", "polished_blackstone_brick_stairs", "deepslate_bricks");
        ArenaKit.fill(c, cx - 2, fy + 1, z0 + 19, cx + 2, fy + 5, z0 + 19, "iron_bars");
        out.put("barrier.a", cx - 2, fy + 1, z0 + 19);
        out.put("barrier.b", cx + 2, fy + 5, z0 + 19);
        // four pedestals for the students
        int[][] ped = {{cx - 16, z0 + 26}, {cx + 16, z0 + 26}, {cx - 16, z0 + 48}, {cx + 16, z0 + 48}};
        for (int i = 0; i < 4; i++) {
            int px = ped[i][0], pz = ped[i][1];
            ArenaKit.fill(c, px - 2, fy + 1, pz - 2, px + 2, fy + 1, pz + 2, "polished_blackstone");
            ArenaKit.fill(c, px - 1, fy + 1, pz - 1, px + 1, fy + 1, pz + 1, "chiseled_polished_blackstone");
            c.set(px - 2, fy + 2, pz - 2, "soul_lantern[hanging=false,waterlogged=false]");
            c.set(px + 2, fy + 2, pz + 2, "soul_lantern[hanging=false,waterlogged=false]");
            out.put("minion." + i, px, fy + 2, pz);
        }
        // pillars and braziers along the side walls
        for (int z = z0 + 18; z <= z1 - 8; z += 10) {
            ArenaKit.column(c, x0 + 3, z, 1.2, fy + 1, 17, Palette.of("polished_basalt[axis=y]"));
            ArenaKit.column(c, x1 - 3, z, 1.2, fy + 1, 17, Palette.of("polished_basalt[axis=y]"));
            c.set(x0 + 5, fy + 1, z, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
            c.set(x1 - 5, fy + 1, z, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
        }
        out.put("spawn", cx, fy + 1, z1 - 6);
        out.put("boss", cx, by + 1, z0 + 6);
        out.put("drop", cx, fy + 1, z0 + 30);
        out.put("chests", cx, fy + 1, z0 + 38);
    }
}
