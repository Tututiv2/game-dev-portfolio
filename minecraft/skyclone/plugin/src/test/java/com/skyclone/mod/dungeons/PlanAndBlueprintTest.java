package com.skyclone.mod.dungeons;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skyclone.mod.dungeons.gen.DefaultArena;
import com.skyclone.mod.dungeons.gen.DungeonPlan;
import com.skyclone.mod.dungeons.gen.Geo;
import com.skyclone.mod.dungeons.gen.PlanGenerator;
import com.skyclone.mod.dungeons.gen.RunBlueprint;
import com.skyclone.mod.dungeons.gen.RunBuilder;
import com.skyclone.mod.dungeons.model.FloorDef;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-run map generator and the block builder, on the shipping floors.json. */
class PlanAndBlueprintTest {

    static List<FloorDef> floors() throws Exception {
        try (InputStream in = PlanAndBlueprintTest.class.getClassLoader().getResourceAsStream("data/dungeons/floors.json")) {
            assertNotNull(in, "floors.json on the classpath");
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            List<FloorDef> out = new ArrayList<>();
            for (JsonElement e : arr) out.add(new Gson().fromJson(e, FloorDef.class));
            return out;
        }
    }

    @Test
    void fifteenFloors() throws Exception {
        List<FloorDef> f = floors();
        assertEquals(15, f.size());
        Set<String> ids = new HashSet<>();
        for (FloorDef d : f) ids.add(d.id);
        assertTrue(ids.containsAll(List.of("E", "F1", "F7", "M1", "M7")));
    }

    @Test
    void everyPlanIsValid() throws Exception {
        Random r = new Random(11);
        for (FloorDef f : floors()) {
            for (int i = 0; i < 25; i++) {
                DungeonPlan p = PlanGenerator.generate(f, r.nextLong());
                assertTrue(p.solvable(), f.id + " solvable");
                for (int x = 0; x < p.w; x++) for (int z = 0; z < p.h; z++) assertTrue(p.cellRoom[x][z] >= 0, "no empty cells");
                for (DungeonPlan.Room room : p.rooms) {
                    if (room.type.leaf) assertEquals(1, p.doorsOf(room.index).size(), f.id + " " + room.type + " is a leaf");
                    else assertFalse(p.doorsOf(room.index).isEmpty(), "every room has a door");
                }
                int keys = 0;
                boolean blood = false;
                for (DungeonPlan.Room room : p.rooms) { if (room.witherKeyFor >= 0) keys++; if (room.bloodKey) blood = true; }
                assertEquals(p.witherDoors, keys, "one key per wither door");
                assertTrue(blood, "a blood key exists");
                assertTrue(p.witherDoors >= Math.min(1, f.witherDoorsMin), "wither doors placed");
                DungeonPlan.Door bd = p.doorsOf(p.bloodRoom).get(0);
                assertEquals(DungeonPlan.DoorKind.BLOOD, bd.kind);
                int puzzles = p.count(DungeonPlan.RoomType.PUZZLE);
                assertTrue(puzzles >= Math.min(f.puzzlesMin, f.puzzlePool.size()) && puzzles <= f.puzzlesMax, f.id + " puzzles " + puzzles);
            }
        }
    }

    @Test
    void deterministic() throws Exception {
        FloorDef f = floors().get(7);
        assertEquals(PlanGenerator.generate(f, 42).ascii(), PlanGenerator.generate(f, 42).ascii());
    }

    @Test
    void blueprintsHaveWhatTheRunNeeds() throws Exception {
        Random r = new Random(5);
        for (FloorDef f : floors()) {
            if (f.master) continue;
            for (int i = 0; i < 3; i++) {
                DungeonPlan p = PlanGenerator.generate(f, r.nextLong());
                RunBlueprint bp = RunBuilder.build(p, f, f.isEntrance() ? null : DefaultArena.INSTANCE);
                assertTrue(bp.totalSecrets() > 0, f.id + " has secrets");
                assertNotNull(bp.start().points.get("mort"));
                assertNotNull(bp.blood().boxes.get("boss_portal"));
                if (!f.isEntrance()) assertNotNull(bp.arenaPoints.get("spawn"));
                for (RunBlueprint.RoomInfo ri : bp.rooms) {
                    assertFalse(ri.data.containsKey("tile_error"), ri.tile + ": " + ri.data.get("tile_error"));
                    int[] s = ri.points.get("spawn");
                    assertNotNull(s);
                    DungeonPlan.RoomType t = p.rooms.get(ri.index).type;
                    if (t == DungeonPlan.RoomType.NORMAL) assertFalse(ri.mobSpots.isEmpty(), ri.tile + " has mob spots");
                    for (int[] m : ri.mobSpots) {
                        assertTrue(bp.canvas.isAir(m[0], m[1], m[2]) && bp.canvas.isAir(m[0], m[1] + 1, m[2]), "mob spot free");
                        assertFalse(bp.canvas.isAir(m[0], m[1] - 1, m[2]), "mob spot has a floor");
                    }
                }
                // locked doors are filled, normal doors are open
                for (RunBlueprint.DoorInfo d : bp.doors) {
                    String b = bp.canvas.get(d.opening.minX(), Geo.FLOOR_Y + 1, d.opening.minZ());
                    if (d.kind == DungeonPlan.DoorKind.NORMAL) assertTrue(bp.canvas.isAir(d.opening.minX(), Geo.FLOOR_Y + 1, d.opening.minZ()), "open door");
                    else assertTrue(b.contains("coal_block") || b.contains("red_terracotta"), "locked door filled: " + b);
                }
            }
        }
    }
}
