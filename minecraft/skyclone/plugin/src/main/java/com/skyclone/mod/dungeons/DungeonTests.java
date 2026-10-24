package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.dungeons.ext.Bosses;
import com.skyclone.mod.dungeons.gen.DefaultArena;
import com.skyclone.mod.dungeons.gen.DungeonPlan;
import com.skyclone.mod.dungeons.gen.PlanGenerator;
import com.skyclone.mod.dungeons.gen.RunBlueprint;
import com.skyclone.mod.dungeons.gen.RunBuilder;
import com.skyclone.mod.dungeons.model.CataMath;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.puzzle.GridPuzzles;
import com.skyclone.mod.dungeons.puzzle.PuzzleBuilders;
import com.skyclone.mod.dungeons.puzzle.Strangers;
import com.skyclone.mod.dungeons.puzzle.TicTacToe;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Live self-tests ({@code /sb selftest dungeons}). */
final class DungeonTests {

    private DungeonTests() {}

    static void register() {
        Sky.commands().selfTest("dungeons.floors-loaded", () -> {
            List<String> want = List.of("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7");
            for (String id : want) {
                FloorDef f = Floors.get(id);
                if (f == null) return "floor " + id + " missing";
                if (f.mobPool.isEmpty()) return id + " has no mobs";
                if (f.puzzlePool.size() < f.puzzlesMin) return id + " has fewer puzzles than it needs";
                if (!f.isEntrance() && (f.bossMob == null || f.bossHp <= 0)) return id + " has no boss";
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.xp-table", () -> {
            if (CataMath.cumulative(50) != 569_809_640L) return "level 50 cumulative " + CataMath.cumulative(50) + " != 569,809,640";
            if (CataMath.level(4385) != 10) return "4,385 XP should be level 10";
            if (CataMath.statBoostPercent(50) != 485) return "stat boost at 50 should be 485%";
            if (!CataMath.grade(300).equals("S+") || !CataMath.grade(269).equals("A")) return "grade thresholds wrong";
            if (CataMath.speedScore(600 + 120, 600) != 90) return "speed score 2 min over should be 90";
            if (CataMath.explorationScore(10, 10, 15, 50, 0.3) != 100) return "exploration at 30% of 50 secrets should be 100";
            return null;
        });
        Sky.commands().selfTest("dungeons.mobs-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (FloorDef f : Floors.all()) {
                List<String> ids = new ArrayList<>(f.mobPool);
                ids.addAll(f.minibossPool);
                ids.addAll(f.bloodPool);
                if (f.bossMob != null) ids.add(f.bossMob);
                for (String id : ids) if (Sky.mobs().def(id) == null && !missing.contains(id)) missing.add(id);
            }
            for (String id : List.of("dungeon_crypt_undead", "dungeon_mimic", "dungeon_puzzle_blaze")) if (Sky.mobs().def(id) == null) missing.add(id);
            return missing.isEmpty() ? null : "unregistered mobs: " + missing;
        });
        Sky.commands().selfTest("dungeons.items-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : List.of("SPIRIT_LEAP", "SUPERBOOM_TNT", "DUNGEON_DECOY", "INFLATABLE_JERRY", "REVIVE_STONE", "POTION_DUNGEON_1", "POTION_DUNGEON_7"))
                if (!Sky.items().exists(id)) missing.add(id);
            for (DungeonItems.Offer o : DungeonItems.SHOP)
                if (o.amount() > 0 && Sky.items().exists(o.id()) && o.coins() <= 0) missing.add(o.id() + " (free in the shop)");
            return missing.isEmpty() ? null : "problems: " + missing;
        });
        Sky.commands().selfTest("dungeons.plans-valid", () -> {
            Random r = new Random(7);
            for (FloorDef f : Floors.all()) {
                for (int i = 0; i < 3; i++) {
                    DungeonPlan p = PlanGenerator.generate(f, r.nextLong());
                    if (!p.solvable()) return f.id + " plan not solvable";
                    if (p.doorsOf(p.bloodRoom).size() != 1) return f.id + " blood room has " + p.doorsOf(p.bloodRoom).size() + " doors";
                    if (p.count(DungeonPlan.RoomType.PUZZLE) < Math.min(f.puzzlesMin, f.puzzlePool.size())) return f.id + " has too few puzzles";
                    for (int x = 0; x < p.w; x++) for (int z = 0; z < p.h; z++) if (p.cellRoom[x][z] < 0) return f.id + " leaves an empty cell";
                }
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.blueprints-build", () -> {
            for (String id : List.of("E", "F1", "F5", "F7")) {
                FloorDef f = Floors.get(id);
                DungeonPlan p = PlanGenerator.generate(f, 1234 + id.hashCode());
                RunBlueprint bp = RunBuilder.build(p, f, f.isEntrance() ? null : Bosses.forFloor(f) == null ? DefaultArena.INSTANCE : Bosses.forFloor(f).arena());
                if (bp.totalSecrets() <= 0) return id + ": no secrets";
                if (bp.start().points.get("mort") == null) return id + ": start room has no Mort spot";
                if (!f.isEntrance() && (bp.arena == null || bp.arenaPoints.get("spawn") == null)) return id + ": no boss arena";
                if (bp.blood().boxes.get("boss_portal") == null) return id + ": blood room has no portal";
                for (RunBlueprint.RoomInfo ri : bp.rooms) {
                    if (ri.data.containsKey("tile_error")) return id + ": tile " + ri.tile + " failed: " + ri.data.get("tile_error");
                    if (p.rooms.get(ri.index).type == DungeonPlan.RoomType.PUZZLE && ri.points.get("puzzle.chest") == null) return id + ": puzzle without chest";
                    if (p.rooms.get(ri.index).type == DungeonPlan.RoomType.NORMAL && ri.mobSpots.isEmpty()) return id + ": room " + ri.tile + " has no mob spots";
                }
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.puzzles-solvable", () -> {
            Random r = new Random(3);
            for (int i = 0; i < 20; i++) {
                long s = r.nextLong();
                if (Strangers.solutions(Strangers.generate(s)) != 1) return "three strangers not unique (seed " + s + ")";
                if (GridPuzzles.water(s).solve() == null) return "water board unsolvable";
                int h = GridPuzzles.teleport(s).hops();
                if (h < 2 || h > 5) return "teleport maze hops " + h;
                if (GridPuzzles.icePath(s).solve() < 4) return "ice path too easy/unsolvable";
                for (int n : new int[]{3, 5, 7}) { GridPuzzles.IceFill f = GridPuzzles.iceFill(s, n); if (!f.valid(f.solution())) return "ice fill " + n + " invalid"; }
            }
            if (GridPuzzles.boulderSolve(GridPuzzles.boulder(5), 60_000) < 1) return "boulder unsolvable";
            // the computer never loses at Tic Tac Toe against random play
            for (int g = 0; g < 30; g++) {
                int[] b = new int[9];
                b[TicTacToe.bestMove(b, g)] = TicTacToe.COMPUTER;
                while (TicTacToe.winner(b) == 0 && !TicTacToe.full(b)) {
                    List<Integer> free = new ArrayList<>();
                    for (int k = 0; k < 9; k++) if (b[k] == 0) free.add(k);
                    b[free.get(r.nextInt(free.size()))] = TicTacToe.PLAYER;
                    if (TicTacToe.winner(b) != 0 || TicTacToe.full(b)) break;
                    b[TicTacToe.bestMove(b, g)] = TicTacToe.COMPUTER;
                }
                if (TicTacToe.winner(b) == TicTacToe.PLAYER) return "tic tac toe AI lost";
            }
            for (String id : PuzzleBuilders.IDS) if (PuzzleBuilders.name(id).equals(id)) return "puzzle " + id + " has no name";
            return null;
        });
        Sky.commands().selfTest("dungeons.hub-layout", () -> {
            WorldService w = Sky.worlds();
            Layout l = w.layout("dungeon_hub");
            if (l == null) return "dungeon_hub has no layout yet";
            for (String a : List.of("spawn", "npc.gatekeeper", "npc.party_board", "npc.class_altar", "npc.provisioner", "npc.croesus", "npc.essence_shop"))
                if (l.anchor(a) == null) return "missing anchor " + a;
            if (l.area("portal.hub") == null || l.area("catacombs_gate") == null) return "missing portal.hub or catacombs_gate";
            Layout hub = w.layout("hub");
            if (hub != null && hub.anchor("npc.dungeon_guide") == null) return "hub has no npc.dungeon_guide";
            boolean warp = false;
            for (WorldService.WarpInfo wi : w.warpInfos()) if (wi.id().equals("dungeon_hub")) warp = true;
            return warp ? null : "warp dungeon_hub is not registered";
        });
        Sky.commands().selfTest("dungeons.requirements", () -> {
            String d = Sky.requirements().describe("catacombs:5");
            if (d == null || !d.contains("5")) return "catacombs resolver: " + d;
            String c = Sky.requirements().describe("class:mage:3");
            if (c == null || !c.contains("Mage")) return "class resolver: " + c;
            if (!Sky.has(com.skyclone.api.feature.Features.Dungeons.class)) return "Features.Dungeons not provided";
            return null;
        });
    }
}
