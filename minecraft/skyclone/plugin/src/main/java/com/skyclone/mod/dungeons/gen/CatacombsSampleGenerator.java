package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.puzzle.PuzzleBuilders;

import java.util.ArrayList;

/**
 * PREVIEW ONLY (never registered as an island): one complete generated run, so {@code tools/preview.sh} can render a
 * real map and flood-check it. The seed picks the layout:
 * {@code bash tools/preview.sh com.skyclone.mod.dungeons.gen.CatacombsSampleGenerator 7}.
 * Anchors: spawn = the start room, one anchor per room centre, the blood portal and the arena spawn. Locked doors are
 * opened in the preview copy so the walk check can see every room.
 */
public final class CatacombsSampleGenerator implements IslandGenerator {

    @Override public String islandId() { return "catacombs_sample"; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return Geo.RUN_BOUNDS; }

    /** A floor that uses every puzzle (6x6, three of them per run). */
    public static FloorDef sampleFloor() {
        FloorDef f = new FloorDef();
        f.id = "F6";
        f.name = "Floor VI (sample)";
        f.number = 6;
        f.gridW = 6;
        f.gridH = 6;
        f.puzzlesMin = 3;
        f.puzzlesMax = 4;
        f.witherDoorsMin = 2;
        f.witherDoorsMax = 3;
        f.trapRooms = 1;
        f.trapChance = 1;
        f.minibossRooms = 2;
        f.theme = "deep";
        f.puzzlePool = new ArrayList<>(PuzzleBuilders.IDS);
        return f;
    }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        FloorDef f = sampleFloor();
        DungeonPlan plan = PlanGenerator.generate(f, seed);
        RunBlueprint bp = RunBuilder.build(plan, f, DefaultArena.INSTANCE);
        // ceilings are left out of the preview copy so top.png shows the rooms (the real run has them)
        bp.canvas.forEach((x, y, z, b) -> { if (y < Geo.CEIL_Y || x >= Geo.arenaX(plan.w)) c.set(x, y, z, b); });
        for (RunBlueprint.DoorInfo d : bp.doors) if (d.kind != DungeonPlan.DoorKind.NORMAL) c.fill(d.opening, "air");
        int[] s = bp.start().points.get("spawn");
        layout.anchor("spawn", s[0] + 0.5, s[1], s[2] + 0.5, 0f);
        for (RunBlueprint.RoomInfo r : bp.rooms) {
            DungeonPlan.Room room = plan.rooms.get(r.index);
            int[] p = r.points.getOrDefault("spawn", r.center);
            String pre = room.type == DungeonPlan.RoomType.PUZZLE ? "puzzle." : "room.";
            layout.anchor(pre + r.index + "." + room.type.name().toLowerCase() + "." + (r.tile == null ? "none" : r.tile), p[0] + 0.5, p[1], p[2] + 0.5, 0f);
        }
        if (bp.arena != null) {
            int[] a = bp.arenaPoints.get("spawn");
            layout.anchor("arena.spawn", a[0] + 0.5, a[1], a[2] + 0.5, 0f);
        }
        layout.data("plan", String.join(" / ", plan.ascii()));
        // puzzle rooms are sealed by design (teleport goal, ice boards, boulders), the arena is reached by portal
        layout.data("walk.ignore", "arena.,puzzle.");
    }
}
