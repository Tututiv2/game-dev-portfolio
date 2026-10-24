package com.skyclone.mod.kuudra.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;

/**
 * Preview helper only (never registered as an island): the arena with its dome lifted off and,
 * with {@code below}, everything above the cavern floor removed so the Lair and the Gullet show.
 * {@code bash tools/preview.sh com.skyclone.mod.kuudra.gen.KuudraArenaCutaway}
 */
public final class KuudraArenaCutaway implements IslandGenerator {

    private final int maxY;
    private final String id;

    public KuudraArenaCutaway() { this(80, "kuudra_cutaway"); }

    KuudraArenaCutaway(int maxY, String id) { this.maxY = maxY; this.id = id; }

    @Override public String islandId() { return id; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new KuudraArenaGenerator().bounds(); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        VoxelCanvas cut = new VoxelCanvas() {
            @Override public void set(int x, int y, int z, String block) { if (y <= maxY) c.set(x, y, z, block); }
            @Override public String get(int x, int y, int z) { return y <= maxY ? c.get(x, y, z) : "air"; }
        };
        new KuudraArenaGenerator().generate(cut, layout, seed);
    }
}
