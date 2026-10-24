package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.OffsetCanvas;

/**
 * A DEV-ONLY "island" that stands every barn skin and every greenhouse skin side by side on a flat lawn, so
 * the shapes can be looked at offline: {@code bash tools/preview.sh com.skyclone.mod.garden.SkinShowcase}.
 * Never registered as an island. Each barn's Desk spot and loft spot is an anchor, so the walk check also
 * proves the loft stairs still reach the loft under every roof.
 */
public final class SkinShowcase implements IslandGenerator {

    static final int Y = PlotGrid.GROUND_Y;
    static final int COLS = 5, STEP_X = 40, STEP_Z = 34;

    @Override public String islandId() { return "garden_skins"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-30, 40, -30, COLS * STEP_X + 10, 120, 8 * STEP_Z + 60); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        Box b = bounds();
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                c.set(x, Y - 2, z, "stone");
                c.set(x, Y - 1, z, "dirt");
                c.set(x, Y, z, "grass_block");
            }
        layout.standAt("spawn", 0, Y, -20, 0f);
        int i = 0;
        for (String skin : BarnBuilder.SKINS.keySet()) {
            int dx = (i % COLS) * STEP_X, dz = (i / COLS) * STEP_Z + 50;
            OffsetCanvas oc = new OffsetCanvas(c, dx, 0, dz);
            BarnBuilder.build(oc, skin, 0L);
            layout.standAt("barn." + skin + ".desk", BarnBuilder.DESK_STAND[0] + dx, BarnBuilder.DESK_STAND[1], BarnBuilder.DESK_STAND[2] + dz, 0f);
            layout.standAt("barn." + skin + ".loft", BarnBuilder.LOFT_SPOT[0] + dx, BarnBuilder.LOFT_SPOT[1], BarnBuilder.LOFT_SPOT[2] + dz, 0f);
            i++;
        }
        int rows = (i + COLS - 1) / COLS;
        int j = 0;
        for (String skin : GreenhouseBuilder.SKINS.keySet()) {
            // greenhouse template sits at x -44..-22, z -42..-20: shift it onto the lawn
            int dx = (j % COLS) * STEP_X + 44, dz = rows * STEP_Z + 50 + 42 + (j / COLS) * 30;
            OffsetCanvas oc = new OffsetCanvas(c, dx, 0, dz);
            int[] bot = GreenhouseBuilder.build(oc, skin);
            oc.set(GreenhouseBuilder.X1 + 1, Y, GreenhouseBuilder.CZ, "polished_andesite");
            layout.standAt("greenhouse." + skin + ".botanist", bot[0] + dx, bot[1], bot[2] + dz, 0f);
            j++;
        }
    }
}
