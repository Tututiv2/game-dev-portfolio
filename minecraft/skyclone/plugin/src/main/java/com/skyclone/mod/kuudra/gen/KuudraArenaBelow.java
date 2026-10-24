package com.skyclone.mod.kuudra.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;

/** Preview helper only: the arena cut at the cavern floor, showing the Lair and the Gullet. */
public final class KuudraArenaBelow implements IslandGenerator {
    private final KuudraArenaCutaway inner = new KuudraArenaCutaway(57, "kuudra_below");
    @Override public String islandId() { return inner.islandId(); }
    @Override public int version() { return inner.version(); }
    @Override public Box bounds() { return inner.bounds(); }
    @Override public void generate(VoxelCanvas c, Layout l, long seed) { inner.generate(c, l, seed); }
}
