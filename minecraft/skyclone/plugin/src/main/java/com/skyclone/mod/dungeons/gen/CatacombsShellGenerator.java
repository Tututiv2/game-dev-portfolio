package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;

/**
 * The instanced island {@code catacombs}. Core runs this once per run slot; it builds only a small closed waiting
 * chamber (the {@code spawn} anchor), because the rooms are generated PER RUN by {@link RunBuilder} and placed by the
 * dungeons module itself (core's instances share one template, research of a fresh map per run is the point).
 *
 * <p>{@link #bounds()} is the whole footprint any run may write ({@link Geo#RUN_BOUNDS}), so when core reuses a released
 * slot it clears the previous run's blocks for us. Pure.
 */
public final class CatacombsShellGenerator implements IslandGenerator {

    @Override public String islandId() { return "catacombs"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return Geo.RUN_BOUNDS; }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        int x0 = Geo.STAGE_X - 4, z0 = Geo.STAGE_Z - 4, y = Geo.FLOOR_Y;
        for (int x = x0; x <= x0 + 8; x++)
            for (int z = z0; z <= z0 + 8; z++) {
                c.set(x, y, z, "polished_blackstone_bricks");
                c.set(x, y + 6, z, "polished_blackstone_bricks");
                boolean edge = x == x0 || x == x0 + 8 || z == z0 || z == z0 + 8;
                for (int h = 1; h <= 5; h++) if (edge) c.set(x, y + h, z, h == 3 && (x == x0 + 4 || z == z0 + 4) ? "soul_lantern[hanging=false,waterlogged=false]" : "polished_blackstone_bricks");
            }
        c.set(x0 + 4, y + 5, z0 + 4, "soul_lantern[hanging=true,waterlogged=false]");
        layout.standAt("spawn", x0 + 4, y, z0 + 4, 0f);
        layout.standAt("npc.mort", x0 + 2, y, z0 + 2, 0f);
        Box all = Geo.RUN_BOUNDS;
        layout.zone("catacombs", "The Catacombs", 'c', new Box(all.minX(), 0, all.minZ(), all.maxX(), 255, all.maxZ()), null, Layout.Zone.NO_FALL);
        layout.data("walk.ignore", "npc.mort");
    }
}
