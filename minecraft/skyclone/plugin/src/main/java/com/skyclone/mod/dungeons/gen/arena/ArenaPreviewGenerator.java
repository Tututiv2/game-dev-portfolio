package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.Map;

/**
 * PREVIEW ONLY (never registered): one boss arena alone, so {@code tools/preview.sh} can render it and flood-check it.
 * The seed picks the floor: 1..7 = F1..F7, 8 = M7 (the Hollow King's chamber gets the chests).
 * {@code bash tools/preview.sh com.skyclone.mod.dungeons.gen.arena.ArenaPreviewGenerator 7}
 * Every arena point becomes an anchor {@code pt.<name>}; points that are reached by teleport or sit behind a
 * barrier by design (balconies, F7's later chambers, floating guardians) are in {@code walk.ignore}.
 */
public final class ArenaPreviewGenerator implements IslandGenerator {

    public static final BossArena[] ARENAS = {CircusArena.INSTANCE, CourtArena.INSTANCE, TombArena.INSTANCE, GroveArena.INSTANCE,
            MirrorArena.INSTANCE, ColossusArena.INSTANCE, WitherArena.INSTANCE};

    @Override public String islandId() { return "boss_arena"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-8, 30, -8, 170, 140, 210); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        int n = (int) Math.max(1, Math.min(8, seed <= 0 ? 1 : seed));
        FloorDef f = new FloorDef();
        f.number = Math.min(7, n);
        f.master = n == 8;
        f.id = (f.master ? "M" : "F") + f.number;
        BossArena a = ARENAS[f.number - 1];
        BossArena.Points pts = new BossArena.Points();
        a.build(c, 0, 70, 0, 1L, f, pts);
        for (Map.Entry<String, int[]> e : pts.map().entrySet()) {
            int[] p = e.getValue();
            if (e.getKey().equals("spawn")) layout.anchor("spawn", p[0] + 0.5, p[1], p[2] + 0.5, 0f);
            else layout.anchor("pt." + e.getKey(), p[0] + 0.5, p[1], p[2] + 0.5, 0f);
        }
        layout.data("arena", a.id() + " " + a.sizeX() + "x" + a.sizeZ());
        layout.data("walk.ignore", "pt.barrier.,pt.guardian.,pt.ceiling.,pt.pillar.,pt.p2.,pt.p3.,pt.p4.,pt.p5.,pt.p1.podium.,pt.p1.crystal.,"
                + (f.number == 2 || f.number == 3 ? "pt.boss," : "") + (f.number == 6 ? "pt.boss," : "") + (f.number == 7 ? "pt.chests," : ""));
    }
}
