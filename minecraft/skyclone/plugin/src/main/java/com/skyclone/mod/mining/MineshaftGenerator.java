package com.skyclone.mod.mining;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/**
 * The frozen mineshaft (reference: Glacite Mineshaft), an INSTANCED island: one template, one copy per
 * player. A grid of timber-shored tunnels cut into tuff and deepslate, with ore pockets in the walls.
 * The pockets are areas "vein.N": every time a player enters, the mining module refills them with the
 * shaft's variant (titanium, umber, tungsten, any of the twelve gems, or the fairy mix), so the template
 * never changes and the variety comes from the refill. Corpses stand at "corpse.N"; the ladder out is
 * area "exit". Pure Java (no Bukkit) so tools/preview.sh can render and flood-check it.
 */
public final class MineshaftGenerator implements IslandGenerator {

    static final int FLOOR = 49;
    static final int[] LINES = {-20, 0, 20};
    static final int REACH = 30;

    @Override public String islandId() { return "mineshaft"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-40, 40, -40, 40, 70, 40); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        Palette rock = Palette.of("tuff:5,deepslate:4,cobbled_deepslate:2,polished_tuff:1");
        Palette floor = Palette.of("polished_deepslate:4,deepslate_tiles:3,cracked_deepslate_tiles:1,gravel:1");
        // ---- 1. the rock mass (additive) ----
        for (int x = -36; x <= 36; x++)
            for (int z = -36; z <= 36; z++)
                for (int y = 44; y <= 62; y++) c.set(x, y, z, rock.at(x, y, z));

        // ---- 2. carve the tunnels: a 3x3 grid of 5-wide corridors ----
        for (int line : LINES) {
            carve(c, -REACH, line - 2, REACH, line + 2);      // east-west
            carve(c, line - 2, -REACH, line + 2, REACH);      // north-south
        }
        for (int x = -REACH; x <= REACH; x++)
            for (int z = -REACH; z <= REACH; z++)
                if (open(x, z)) c.set(x, FLOOR, z, floor.at(x, FLOOR, z));
        // a little rimestone frost on the floor edges
        for (int x = -REACH; x <= REACH; x++)
            for (int z = -REACH; z <= REACH; z++)
                if (open(x, z) && !open(x + 1, z) && Hash.chance(seed, x * 31 + 7, z, 0.25)) c.set(x, FLOOR, z, "packed_ice");

        // ---- 3. ore pockets in the corridor walls ----
        int n = 0;
        for (int line : LINES) {
            for (int t = -REACH + 4; t <= REACH - 4; t += 7) {
                if (near(t)) continue;
                for (int side : new int[]{-1, 1}) {
                    if (!Hash.chance(seed, line * 97 + t, side, 0.8)) continue;
                    // pocket beside an east-west corridor
                    int z0 = side < 0 ? line - 5 : line + 3;
                    Box b1 = Box.of(t - 1, FLOOR + 1, z0, t + 1, FLOOR + 3, z0 + 2);
                    c.fill(b1, "packed_ice");
                    layout.area("vein." + (++n), b1);
                    // pocket beside a north-south corridor
                    int x0 = side < 0 ? line - 5 : line + 3;
                    Box b2 = Box.of(x0, FLOOR + 1, t - 1, x0 + 2, FLOOR + 3, t + 1);
                    c.fill(b2, "packed_ice");
                    layout.area("vein." + (++n), b2);
                }
            }
        }

        // ---- 4. timber shoring and lights ----
        for (int line : LINES) {
            for (int t = -REACH + 2; t <= REACH - 2; t += 6) {
                if (near(t)) continue;
                beam(c, t, line, true);
                beam(c, line, t, false);
            }
        }

        // ---- 5. the way out: a ladder at the west end ----
        for (int y = FLOOR + 1; y <= FLOOR + 5; y++) c.set(-REACH, y, 0, "ladder[facing=east]");
        c.set(-REACH - 1, FLOOR + 6, 0, "air");
        c.set(-REACH + 1, FLOOR + 1, 2, "lantern");
        layout.area("exit", Box.of(-REACH, FLOOR + 1, -1, -REACH + 1, FLOOR + 4, 1));

        // ---- 6. what the module needs ----
        layout.standAt("spawn", -REACH + 3, FLOOR, 0, -90f);
        layout.standAt("exit", -REACH + 1, FLOOR, 1, 90f);
        int[][] corpses = {{0, 0}, {20, 20}, {-20, 20}, {20, -20}, {0, 20}, {20, 0}};
        for (int i = 0; i < corpses.length; i++) layout.standAt("corpse." + (i + 1), corpses[i][0] + 1, FLOOR, corpses[i][1] + 1, 0f);
        layout.zone("mineshaft", "Rimestone Mineshaft", 'b', Box.of(-36, 44, -36, 36, 62, 36), null);
        layout.data("walk.ignore", "vein.");
    }

    /** Is (x, z) inside a corridor? */
    static boolean open(int x, int z) {
        if (Math.abs(x) > REACH || Math.abs(z) > REACH) return false;
        for (int l : LINES) if (Math.abs(z - l) <= 2 || Math.abs(x - l) <= 2) return true;
        return false;
    }

    private static boolean near(int t) {
        for (int l : LINES) if (Math.abs(t - l) <= 4) return true;
        return false;
    }

    private static void carve(VoxelCanvas c, int x1, int z1, int x2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                for (int y = FLOOR + 1; y <= FLOOR + 5; y++) c.set(x, y, z, "air");
    }

    /** A timber frame across a corridor: posts at both walls, a beam under the ceiling, a hanging lantern. */
    private static void beam(VoxelCanvas c, int along, int line, boolean eastWest) {
        for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
            set(c, eastWest, along, line - 2, y, "stripped_dark_oak_log[axis=y]");
            set(c, eastWest, along, line + 2, y, "stripped_dark_oak_log[axis=y]");
        }
        for (int o = -2; o <= 2; o++) set(c, eastWest, along, line + o, FLOOR + 5, eastWest ? "dark_oak_log[axis=z]" : "dark_oak_log[axis=x]");
        set(c, eastWest, along, line, FLOOR + 4, "lantern[hanging=true]");
    }

    private static void set(VoxelCanvas c, boolean eastWest, int along, int across, int y, String b) {
        if (eastWest) c.set(along, y, across, b); else c.set(across, y, along, b);
    }
}
