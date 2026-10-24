package com.skyclone.mod.mines;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Live checks, run with /sb selftest mines. Each returns null on pass. */
final class MinesSelfTests {

    private MinesSelfTests() {}

    static void register(MinesModule m) {
        Sky.commands().selfTest("mines.mobs-registered", () -> {
            if (m.mobIds().size() < 19) return "only " + m.mobIds().size() + " mobs read from data/mines/mobs.json (19 expected: wave 3 removed the Lv70+ Crystal Hollows goblins)";
            List<String> bad = new ArrayList<>();
            for (String id : m.mobIds()) if (Sky.mobs().def(id) == null) bad.add(id);
            return bad.isEmpty() ? null : "not registered: " + bad;
        });
        Sky.commands().selfTest("mines.drops-exist", () -> {
            Set<String> missing = new java.util.TreeSet<>();
            for (String id : m.mobIds()) for (DropDef d : MinesModule.dropsOf(id)) if (!Sky.items().exists(d.item)) missing.add(d.item);
            return missing.isEmpty() ? null : "drop items not registered by their owners: " + missing;
        });
        Sky.commands().selfTest("mines.research-numbers", () -> {
            // research 03 §9.2 / §10.2: a known input gives the documented number
            String r = expect("deep_caverns_lapis_zombie", 7, 200, 50, 12);
            if (r == null) r = expect("deep_caverns_emerald_slime_15", 15, 250, 150, 15);
            if (r == null) r = expect("dwarven_ghost", 250, 1_000_000, 1000, 100);
            // NEU ICE_WALKER_MONSTER (wave 3): 888 HP and 40 combat XP replace the research's (?) 3,000 / 30
            if (r == null) r = expect("dwarven_ice_walker", 45, 888, 500, 40);
            // wave 3: only the Lv25 goblins live in the Dwarven Mines (Lv70-200 goblins are Crystal Hollows mobs)
            if (r == null) for (String id : m.mobIds()) {
                MobDef d = Sky.mobs().def(id);
                if (d != null && "goblin".equals(d.familyId()) && d.level > 25) { r = id + " is Lv" + d.level + ": only Lv25 goblins belong here"; break; }
            }
            if (r == null) {
                MobDef g = Sky.mobs().def("dwarven_ghost");
                double volta = g.drops.stream().filter(d -> d.item.equals("VOLTA")).mapToDouble(d -> d.chance).findFirst().orElse(-1);
                if (Math.abs(volta - 0.002) > 1e-9) r = "Ghost Volta chance " + volta + " (research 0.2%)";
            }
            if (r == null && Places.layers() != 6) r = "Deep Caverns has " + Places.layers() + " layers, research says 6";
            return r;
        });
        Sky.commands().selfTest("mines.anchors", () -> {
            List<String> bad = new ArrayList<>();
            for (String island : Places.ISLANDS) {
                Layout l = Sky.worlds().layout(island);
                if (l == null) { bad.add(island + ": no layout"); continue; }
                for (String a : Places.requiredAnchors(island)) if (l.anchor(a) == null) bad.add(island + ":" + a);
                for (String a : Places.requiredPortals(island)) if (l.area(a) == null) bad.add(island + ":" + a);
                long souls = l.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
                if (souls < 10 || souls > 40) bad.add(island + ": " + souls + " fairy souls");
            }
            return bad.isEmpty() ? null : "missing: " + bad;
        });
        Sky.commands().selfTest("mines.ore-and-spawn-areas", () -> {
            List<String> bad = new ArrayList<>();
            for (String island : Places.ISLANDS) {
                Layout l = Sky.worlds().layout(island);
                if (l == null) { bad.add(island + ": no layout"); continue; }
                for (String a : Places.requiredOreAreas(island)) if (l.area(a) == null) bad.add(island + ":" + a);
            }
            for (SpawnRule r : m.spawnRules()) {
                Layout l = Sky.worlds().layout(r.island);
                if (l == null || l.area(r.area) == null) bad.add("spawn " + r.id + " -> " + r.island + ":" + r.area);
                if (Sky.mobs().def(r.mob) == null) bad.add("spawn " + r.id + " -> unknown mob " + r.mob);
            }
            return bad.isEmpty() ? null : "missing: " + bad;
        });
        Sky.commands().selfTest("mines.stations-safe", () -> {
            List<String> bad = new ArrayList<>();
            for (String st : Places.stations()) {
                org.bukkit.Location l = Sky.worlds().anchor(Places.DWARVEN, "station." + st);
                if (l == null) bad.add("station." + st + " missing");
                else if (!MineMobs.safe(l)) bad.add("station." + st);
            }
            for (int n = 1; n <= Places.layers(); n++) {
                org.bukkit.Location l = Sky.worlds().anchor(Places.DEEP, "lift." + n);
                if (l == null) bad.add("lift." + n + " missing");
                else if (!MineMobs.safe(l)) bad.add("lift." + n);
            }
            org.bukkit.Location dl = Sky.worlds().anchor(Places.DWARVEN, "lift.dwarven");
            if (dl == null) bad.add("lift.dwarven missing");
            else if (!MineMobs.safe(dl)) bad.add("lift.dwarven");
            return bad.isEmpty() ? null : "arrival points not in a SAFE zone (rebuild the island?): " + bad;
        });
        Sky.commands().selfTest("mines.warps", () -> {
            List<String> bad = new ArrayList<>();
            for (String w : List.of("gold_mine", "gold", "deep_caverns", "deep", "dwarven_mines", "mines", "forge", "camp"))
                if (!Sky.worlds().warps().contains(w)) bad.add(w);
            return bad.isEmpty() ? null : "warps not registered: " + bad;
        });
        Sky.commands().selfTest("mines.menus-build", () -> {
            MinesData d = new MinesData();
            d.visit(1); d.visit(3);
            if (m.travel.buildLift(null, d, "deep_caverns").size() != 54) return "lift menu size";
            if (m.travel.buildMinecart(null, "dwarven_mines").size() != 54) return "minecart menu size";
            if (m.travel.buildOverview(null, d).size() != 54) return "overview menu size";
            return null;
        });
        // ---- wave 5: real rides ----
        Sky.commands().selfTest("mines.rail-network", () -> {
            // every station has a laid line from the depot, and every pair of stations joins into one rideable track
            Layout l = Sky.worlds().layout(Places.DWARVEN);
            if (l == null) return "no dwarven_mines layout";
            String missing = l.data().get("rail.missing");
            if (missing == null) return "no rail data in the layout (rebuild the island: generator v4)";
            if (!missing.isBlank()) return "stations with no line (they fall back to a teleport): " + missing;
            List<String> bad = new ArrayList<>();
            for (String a : Places.stations()) {
                List<int[]> la = com.skyclone.mod.mines.gen.RailRoute.decode(l.data().get("rail." + a));
                if (la.isEmpty() || !com.skyclone.mod.mines.gen.RailRoute.contiguous(la)) { bad.add(a + " (line)"); continue; }
                for (String b : Places.stations()) {
                    if (a.equals(b)) continue;
                    List<double[]> pts = Travel.cartPath(a, b);
                    if (pts == null || pts.size() < 2) bad.add(a + "->" + b);
                }
            }
            return bad.isEmpty() ? null : "unrideable: " + bad;
        });
        Sky.commands().selfTest("mines.lift-shafts", () -> {
            double[] dc = Travel.shaft(Places.DEEP, "lift.shaft"), dw = Travel.shaft(Places.DWARVEN, "lift.dwarven.shaft");
            if (dc == null) return "deep_caverns has no lift.shaft data (rebuild: generator v2)";
            if (dw == null || dw.length < 4) return "dwarven_mines has no lift.dwarven.shaft data (rebuild: generator v4)";
            if (dw[3] - dw[2] < 20) return "The Lift's shaft is only " + (dw[3] - dw[2]) + " blocks tall";
            if (dc[2] >= Travel.stop(Places.layers())) return "the Deep Caverns shaft bottom " + dc[2] + " is not below the sanctuary";
            for (String a : List.of("lift.dwarven", "npc.lift.dwarven")) if (Sky.worlds().anchor(Places.DWARVEN, a) == null) return "missing anchor " + a;
            return null;
        });
        Sky.commands().selfTest("mines.ride-math", () -> {
            // a 3-cell line that climbs one block: wheels on the rail top, the edge into the high cell already high
            List<int[]> cells = List.of(new int[]{0, 10, 0}, new int[]{1, 10, 0}, new int[]{2, 11, 0});
            List<double[]> w = com.skyclone.mod.mines.gen.RailRoute.waypoints(cells);
            double top = com.skyclone.mod.mines.gen.RailRoute.RAIL_TOP;
            if (Math.abs(w.get(0)[1] - (11 + top)) > 1e-9) return "flat cell wheel height " + w.get(0)[1];
            if (Math.abs(w.get(w.size() - 1)[1] - (12 + top)) > 1e-9) return "high cell wheel height " + w.get(w.size() - 1)[1];
            double v = 0, s = 0, len = 40;
            for (int t = 0; t < 2000 && s < len; t++) { v = com.skyclone.mod.mines.gen.RailRoute.nextSpeed(v, 1.0, 0.05, len - s, 0.05); s += v; }
            if (s < len) return "a 40-block ride never arrives";
            if (v > 0.5) return "the cart arrives at " + v + " b/t instead of braking";
            return Travel.parseShaft("3,4,5,60") == null ? "shaft data does not parse" : null;
        });
        Sky.commands().selfTest("mines.hollows-pass", () -> {
            if (!"skill:mining:12 & hotm:4".equals(MinesModule.withoutPass("skill:mining:12 & mines:hollows_pass & hotm:4"))) return "withoutPass keeps the pass";
            if (MinesModule.withoutPass("mines:hollows_pass") != null) return "a pass-only requirement should leave nothing";
            if (Sky.npcs() == null) return "no npc service";
            MinesData d = new MinesData();
            if (d.hollowsPass(System.currentTimeMillis())) return "a new player already holds a pass";
            d.hollowsPassUntil = System.currentTimeMillis() + 1000;
            if (!d.hollowsPass(System.currentTimeMillis())) return "a running pass reads as expired";
            if (m.dials.hollowsPassPrice.get() < 0 || m.dials.hollowsPassHours.get() <= 0) return "pass dials out of range";
            return null;
        });
        Sky.commands().selfTest("mines.fares", () -> {
            if (Places.fare(0, 0, 500) != 0) return "free ride is not free";
            if (Places.fare(10, 0.5, 100) != 60) return "fare formula: 10 + 0.5*100 should be 60";
            if (Places.fare(-50, 0, 0) != 0) return "fare went negative";
            return null;
        });
    }

    private static String expect(String id, int level, double hp, double dmg, double xp) {
        MobDef d = Sky.mobs().def(id);
        if (d == null) return id + " missing";
        if (d.level != level || d.health != hp || d.damage != dmg || d.combatXp != xp)
            return id + " is Lv" + d.level + " " + d.health + "hp " + d.damage + "dmg " + d.combatXp + "xp; research Lv" + level + " " + hp + "/" + dmg + "/" + xp;
        return null;
    }
}
