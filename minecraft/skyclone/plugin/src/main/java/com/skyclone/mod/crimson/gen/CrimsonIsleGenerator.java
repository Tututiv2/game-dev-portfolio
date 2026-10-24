package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Hash;

import java.util.List;

/**
 * The Crimson Isle: a nether island split between two rival factions, generated whole from code.
 * Pure Java (no Bukkit) so {@code tools/preview.sh} renders exactly what the game builds.
 *
 * <p>Order: terrain shape (plots flattened first) - fill and carve (crater, Magma Chamber, tunnel,
 * pools) - structures (each reserves its box first) - roads - district surfaces - flora - souls.
 *
 * <p>Anchors other modules use: {@code spawn}, {@code portal.hub} (area), shop anchors listed in the
 * shops hand-off ({@code npc.peddler_dragontail} ...), {@code npc.trophy_keeper}; the crimson module's
 * own NPCs, boss anchors {@code boss.*}, {@code dojo.*}, lava fishing areas {@code fishing.lava.*}.
 */
public final class CrimsonIsleGenerator implements IslandGenerator {

    public static final int SOULS_WANTED = 29;

    @Override public String islandId() { return "crimson_isle"; }

    /**
     * 3: Cinder Shore + Belly are NO_MOBS zones and the marsh bulls' box stops at x -128 (layout-only change).
     * 4 (wave 5): Opal veins in the Smoldering Tomb's walls (area gather.tomb_opal) and Sulphur Ore (sponge) on the
     * volcano flanks and the lava pools' shores.
     */
    @Override public int version() { return 5; }   // 5: warp.skull (Forgotten Skull fast travel)

    @Override public Box bounds() { return CPlan.BOUNDS; }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        CCtx x = new CCtx(c, layout, seed);
        CTerrain.shape(x);
        CTerrain.fill(x);
        CStronghold.build(x);
        CVolcano.build(x);
        CTowns.drakehold(x);
        CTowns.carmine(x);
        CSites.build(x);
        CBelly.build(x);
        CRoads.build(x);
        CVolcano.streaks(x);
        CMinerals.sulphur(x);
        CTerrain.repaint(x);
        CTerrain.flora(x);
        souls(x);
        layout.data("reservations", x.res.summary());
        layout.data("walk.ignore", "crimson.chamber_center,crimson.matriarch");
    }

    /** Builder-offered spots first, then wild spots on open ground until there are {@link #SOULS_WANTED}. */
    private static void souls(CCtx x) {
        List<int[]> spots = x.soulSpots;
        int[][] wild = {{-170, -172}, {-62, -32}, {72, 34}, {-62, 196}, {-210, -60}, {60, -20}, {-30, 60}, {200, -60}, {-120, 60}};
        for (int[] w : wild) {
            if (spots.size() >= SOULS_WANTED) break;
            for (int tries = 0; tries < 30; tries++) {
                int px = w[0] + (int) ((Hash.unit(x.seed, w[0], tries, w[1]) - 0.5) * 16);
                int pz = w[1] + (int) ((Hash.unit(x.seed, w[1], tries, w[0]) - 0.5) * 16);
                if (!x.h.has(px, pz) || x.h.wet(px, pz) || x.h.slope(px, pz) > 1 || !x.h.inland(px, pz, 3)) continue;
                int g = x.ground(px, pz);
                String b = BlockState.name(x.c.get(px, g, pz));
                if (b.equals("lava") || b.equals("magma_block")) continue;
                if (!x.c.isAir(px, g + 2, pz)) continue;
                if (!x.c.isAir(px, g + 1, pz)) {
                    String p = BlockState.name(x.c.get(px, g + 1, pz));
                    if (!(p.endsWith("roots") || p.endsWith("fungus") || p.endsWith("grass") || p.endsWith("bush") || p.endsWith("mushroom"))) continue;
                    x.c.set(px, g + 1, pz, "air");
                }
                spots.add(new int[]{px, g, pz});
                break;
            }
        }
        int n = 0;
        for (int[] s : spots) {
            if (n >= SOULS_WANTED) break;
            n++;
            x.layout.standAt("fairy_soul." + n, s[0], s[1], s[2], 0f);
        }
        x.layout.data("crimson.souls", String.valueOf(n));
    }
}
