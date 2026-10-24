package com.skyclone.mod.hollows;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hollows.gen.Zones;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /sb selftest hollows}: does the data hang together on the live server. */
final class HollowsTests {

    private HollowsTests() {}

    static final String[] MOB_IDS = {"ch_automaton", "ch_automaton_elite", "ch_sludge", "ch_sludge_10", "ch_sludge_100", "ch_yog", "ch_thyst",
            "ch_goblin", "ch_goblin_knifethrower", "ch_goblin_firethrower", "ch_goblin_flamethrower", "ch_goblin_bomber", "ch_goblin_cutthroat",
            "ch_treasurite_grunt", "ch_treasurite_sebastian", "ch_treasurite_viper", "ch_treasurite_wendy", "ch_boss_corleone",
            "ch_key_guardian", "ch_temple_guardian", "ch_magmaw", "ch_magmaw_blaze", "ch_butterfly", "ch_goblin_queen", "ch_goblin_brood_guard",
            "ch_magmaw_bat", "ch_kalhuiki_tribesman",
            "gt_glacite_walker", "gt_glacite_bowman", "gt_glacite_mage", "gt_glacite_caver", "gt_glacite_mutt"};

    static final String[] CH_ANCHORS = {"spawn", "nucleus", "npc.hollows_merchant", "npc.hollows_guide", "npc.temple_altar", "npc.odala",
            "npc.keeper_lapis", "npc.keeper_emerald", "npc.keeper_diamond", "npc.keeper_gold", "npc.professor", "npc.goblin_king",
            "boss.magmaw", "boss.gang_boss", "boss.goblin_queen", "npc.gemcutter", "npc.tribal_trader"};
    static final String[] GT_ANCHORS = {"spawn", "npc.camp_excavator", "npc.camp_quartermaster", "npc.camp_commissions", "npc.camp_supplies", "npc.fossil_researcher", "npc.old_lupo"};

    static void register(HollowsModule m) {
        var c = Sky.commands();
        c.selfTest("hollows.mobs-and-drops", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : MOB_IDS) {
                MobDef d = Sky.mobs().def(id);
                if (d == null) { bad.add("mob " + id); continue; }
                for (DropDef dr : d.drops) if (!Sky.items().exists(dr.item)) bad.add(id + " drop " + dr.item);
            }
            for (DropDef dr : m.magmaw.table.perHit) if (!Sky.items().exists(dr.item)) bad.add("magmaw per-hit " + dr.item);
            return bad.isEmpty() ? null : "missing: " + String.join(", ", bad.subList(0, Math.min(12, bad.size())));
        });
        c.selfTest("hollows.quest-items", () -> {
            List<String> bad = new ArrayList<>();
            for (Lore.Quest q : Lore.QUESTS) if (!Sky.items().exists(q.crystal())) bad.add(q.crystal());
            for (String id : Lore.KEEPER_TOOLS.values()) if (!Sky.items().exists(id)) bad.add(id);
            for (String id : Lore.ROBOT_PARTS) if (!Sky.items().exists(id)) bad.add(id);
            for (String id : Lore.GOBLIN_EGGS) if (!Sky.items().exists(id)) bad.add(id);
            for (String id : List.of(Lore.METAL_DETECTOR, Lore.APPARATUS, "JUNGLE_KEY", "SLUDGE_JUICE")) if (!Sky.items().exists(id)) bad.add(id);
            return bad.isEmpty() ? null : "missing items: " + bad;
        });
        c.selfTest("hollows.anchors", () -> {
            List<String> bad = new ArrayList<>();
            check(bad, "crystal_hollows", CH_ANCHORS, List.of("portal.hub", "portal.dwarven_mines", "nucleus", "varnas.hall", "boss.magmaw", "boss.goblin_queen", "mobs.goblin_queen_den"));
            check(bad, "glacite_tunnels", GT_ANCHORS, List.of("portal.hub", "portal.dwarven_mines", "base_camp"));
            Layout ch = Sky.worlds().layout("crystal_hollows");
            if (ch != null) {
                long t = ch.anchors().keySet().stream().filter(n -> n.startsWith("varnas.treasure.")).count();
                if (t < 12) bad.add("only " + t + " varnas.treasure spots");
                for (Waypoints.Place p : Waypoints.CAVERNS) if (ch.anchor(p.anchor()) == null) bad.add("waypoint " + p.anchor());
                if (TempleTraps.parse(ch.data().get("hollows.temple.traps")).size() != 4) bad.add("temple traps missing (island built before generator v2?)");
            }
            Layout gt = Sky.worlds().layout("glacite_tunnels");
            if (gt != null) {
                for (Waypoints.Place p : Waypoints.TUNNELS) if (gt.anchor(p.anchor()) == null) bad.add("tunnels waypoint " + p.anchor());
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        c.selfTest("hollows.spawn-rules", () -> {
            List<String> bad = new ArrayList<>();
            try (var in = Sky.plugin().getResource("data/hollows/spawns.json")) {
                if (in == null) return "spawns.json missing";
                SpawnRule[] rules = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), SpawnRule[].class);
                for (SpawnRule r : rules) {
                    if (Sky.mobs().def(r.mob) == null) bad.add(r.id + ": mob " + r.mob);
                    Layout lay = Sky.worlds().layout(r.island);
                    if (lay != null && r.area != null && lay.area(r.area) == null) bad.add(r.id + ": area " + r.area);
                }
            } catch (Exception e) { return "spawns.json: " + e; }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        c.selfTest("hollows.fairy-souls", () -> {
            for (String isl : List.of("crystal_hollows", "glacite_tunnels")) {
                Layout lay = Sky.worlds().layout(isl);
                if (lay == null) return isl + " has no layout yet";
                long n = lay.anchors().keySet().stream().filter(s -> s.startsWith("fairy_soul.")).count();
                if (n < 10 || n > 40) return isl + " has " + n + " fairy souls (want 10-40)";
            }
            return null;
        });
        c.selfTest("hollows.magmaw-numbers", () -> {
            // research (NEU BAL_BOSS): 200 health, 1 per hit, Flawed Ruby/Topaz 5% and Yoggie 4.5% per hit
            MobDef d = Sky.mobs().def("ch_magmaw");
            if (d == null) return "no ch_magmaw";
            if (d.health != 200) return "health " + d.health + " (want 200)";
            if (Lore.hitsToKill(d.health, 1.0) != 200) return "hitsToKill(200,1) != 200";
            Map<String, Double> want = Map.of("FLAWED_RUBY_GEM", 0.05, "FLAWED_TOPAZ_GEM", 0.05, "YOGGIE", 0.045);
            for (var e : want.entrySet()) {
                DropDef dr = m.magmaw.table.perHit.stream().filter(x -> x.item.equals(e.getKey())).findFirst().orElse(null);
                if (dr == null || Math.abs(dr.chance - e.getValue()) > 1e-9) return "per-hit " + e.getKey() + " = " + (dr == null ? "missing" : dr.chance);
            }
            if (Math.abs(Lore.expectedDrops(200, 1, 0.05) - 10) > 1e-9) return "expected flawed gems per fight != 10";
            return null;
        });
        c.selfTest("hollows.goblin-queen", () -> {
            // the Queen's Den is the second Amber route and the main Goblin Egg source
            MobDef q = Sky.mobs().def(GoblinQueen.MOB), g = Sky.mobs().def(GoblinQueen.GUARD);
            if (q == null || g == null) return "missing " + (q == null ? GoblinQueen.MOB : GoblinQueen.GUARD);
            if (!q.behaviours.contains("hollows_goblin_queen")) return "queen has no hollows_goblin_queen behaviour";
            if (!q.has("boss")) return "queen is not flagged boss";
            if (!q.familyId().contains("goblin") || !g.familyId().contains("goblin")) return "queen/guard family must contain 'goblin' (commissions)";
            if (!Sky.items().exists("GOBLIN_EGG")) return "GOBLIN_EGG is not an item";
            double queenEgg = q.drops.stream().filter(x -> x.item.equals("GOBLIN_EGG")).mapToDouble(x -> x.chance).max().orElse(0);
            double guardEgg = g.drops.stream().filter(x -> x.item.equals("GOBLIN_EGG")).mapToDouble(x -> x.chance).max().orElse(0);
            double holdoutEgg = Sky.mobs().def("ch_goblin") == null ? 0 : Sky.mobs().def("ch_goblin").drops.stream()
                    .filter(x -> x.item.equals("GOBLIN_EGG")).mapToDouble(x -> x.chance).max().orElse(0);
            if (queenEgg < 1.0) return "queen GOBLIN_EGG chance " + queenEgg + " (want guaranteed)";
            if (guardEgg <= holdoutEgg) return "brood guard egg chance " + guardEgg + " not above the Holdout goblin's " + holdoutEgg;
            if (GoblinQueen.waves() != 3) return "guard waves " + GoblinQueen.waves() + " (want 3: 75/50/25%)";
            return null;
        });
        // ---- wave 5 ----
        c.selfTest("hollows.lobby-clock", () -> {
            // research: Day 19 closes to newcomers, Day 35 forced shutdown, <4 players after Day 19 closes it
            long day = 20 * 60_000L;
            if (LobbyMath.day(19 * day + 5, 5, day) != 19) return "day() off by one";
            if (LobbyMath.stage(18, 19, 35) != LobbyMath.Stage.OPEN || LobbyMath.stage(19, 19, 35) != LobbyMath.Stage.CLOSING
                    || LobbyMath.stage(35, 19, 35) != LobbyMath.Stage.SHUTDOWN) return "stages wrong";
            if (!LobbyMath.shouldClose(LobbyMath.Stage.CLOSING, 3, 4) || LobbyMath.shouldClose(LobbyMath.Stage.CLOSING, 4, 4)
                    || LobbyMath.shouldClose(LobbyMath.Stage.OPEN, 0, 4)) return "close rule wrong";
            if (LobbyMath.mayEnter(LobbyMath.Stage.CLOSING, true, false, false) || !LobbyMath.mayEnter(LobbyMath.Stage.CLOSING, true, false, true))
                return "newcomer/party rule wrong";
            if (m.dials.lobbyCloseDay.get() >= m.dials.lobbyShutdownDay.get()) return "close day must come before shutdown day";
            if (!Sky.requirements().known(Lobbies.REQ)) return "requirement prefix " + Lobbies.REQ + " not registered";
            if (m.dials.lobbyRotation.get() && (m.caverns.requirement == null || !m.caverns.requirement.contains(Lobbies.REQ)))
                return "caverns requirement lacks the lobby gate: " + m.caverns.requirement;
            return null;
        });
        c.selfTest("hollows.temple-parkour", () -> {
            Layout ch = Sky.worlds().layout("crystal_hollows");
            if (ch == null) return "no caverns layout";
            if (!"1".equals(ch.data().get("hollows.temple.inner"))) return "caverns built before generator v3 (no inner temple): /sb hollows rebuild crystal_hollows";
            for (String a : List.of("parkour.start", "parkour.cp.1", "temple.guardian", "temple.exit_to", "npc.temple_altar"))
                if (ch.anchor(a) == null) return "missing anchor " + a;
            for (String a : List.of("temple.hall", "temple.pit", "temple.sanctum", "temple.exit"))
                if (ch.area(a) == null) return "missing area " + a;
            Box pit = ch.area("temple.pit"), hall = ch.area("temple.hall");
            if (!hall.contains(pit.minX(), pit.minY(), pit.minZ()) || !hall.contains(pit.maxX(), pit.maxY(), pit.maxZ())) return "pit not inside the hall";
            Layout.Anchor start = ch.anchor("parkour.start");
            if (pit.contains(start.x(), start.y(), start.z())) return "start pad inside the fall box";
            if (Sky.mobs().def("ch_temple_guardian") == null) return "no ch_temple_guardian";
            if (Sky.worlds().isBuilt("crystal_hollows")) {
                var w = Sky.worlds().world("crystal_hollows");
                if (w == null) return "caverns world not loaded";
                if (!w.getBlockAt((int) Math.floor(start.x()), (int) Math.floor(start.y()) - 1, (int) Math.floor(start.z())).getType().isSolid())
                    return "nothing under parkour.start in the built world";
            }
            return null;
        });
        c.selfTest("hollows.varnas-treasure", () -> {
            Layout ch = Sky.worlds().layout("crystal_hollows");
            if (ch == null) return "no caverns layout";
            if (ch.data().get("hollows.varnas.buried") == null) return "caverns built before generator v3 (no buried chests)";
            List<Layout.Anchor> spots = ch.anchors().values().stream().filter(a -> a.name().startsWith("varnas.treasure.")).toList();
            if (spots.size() < 12) return "only " + spots.size() + " treasure spots";
            if (!Sky.worlds().isBuilt("crystal_hollows")) return null;
            var w = Sky.worlds().world("crystal_hollows");
            if (w == null) return "caverns world not loaded";
            int bad = 0;
            for (Layout.Anchor a : spots) {
                int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
                if (w.getBlockAt(x, y - 2, z).getType() != org.bukkit.Material.CHEST) bad++;
            }
            if (bad > 0 && m.varnas.openTiles() == 0) return bad + "/" + spots.size() + " treasure spots have no chest buried under them";
            if (Lore.KEEPER_TOOLS.values().stream().anyMatch(id -> !Sky.items().exists(id))) return "a lost tool item is missing";
            return null;
        });
        c.selfTest("hollows.kalhuiki-and-bats", () -> {
            MobDef t = Sky.mobs().def("ch_kalhuiki_tribesman"), b = Sky.mobs().def("ch_magmaw_bat");
            if (t == null || b == null) return "missing tribesman/ember bat";
            if (!Kalhuiki.FAMILY.equals(t.familyId())) return "tribesman family " + t.familyId();
            if (!Sky.items().exists(Kalhuiki.MASK)) return "KALHUIKI_MASK is not an item (armor module)";
            if (!b.behaviours.contains("hollows_ember_bat")) return "ember bat has no burning-touch behaviour";
            if (m.dials.odalaSludge.get() <= 0) return "odala price";
            return null;
        });
        c.selfTest("hollows.passes", () -> {
            // research 03: Crystal Hollows = Mining XII + HotM 4 + a 10,000-coin 6 h pass; Glacite Tunnels = HotM 7 + Secret Railroad Pass
            for (String k : List.of(Passes.CH_REQ, Passes.GT_REQ)) if (!Sky.requirements().known(k)) return "requirement " + k + " not registered";
            if (m.dials.passRequired.get() && !String.valueOf(m.caverns.requirement).contains(Passes.CH_REQ)) return "caverns requirement lacks the pass: " + m.caverns.requirement;
            if (m.dials.railPassRequired.get() && !String.valueOf(m.tunnels.requirement).contains(Passes.GT_REQ)) return "tunnels requirement lacks the rail pass: " + m.tunnels.requirement;
            if (m.dials.passPrice.get() < 0 || m.dials.passHours.get() <= 0) return "pass price/hours";
            if (!Sky.items().exists(Passes.RAIL_ITEM)) return Passes.RAIL_ITEM + " missing (mining module) - the rail pass gate stays open";
            return null;
        });
        c.selfTest("hollows.gem-blocks-match-mining", () -> {
            // the generator paints gems as stained glass by colour; the mining module must read the same colours as the same gems
            try (var in = Sky.plugin().getResource("data/mining/blocks.json")) {
                if (in == null) return "data/mining/blocks.json not found (mining module missing?)";
                JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                JsonArray blocks = root.getAsJsonArray("blocks");
                List<String> bad = new ArrayList<>();
                for (String color : List.of("red", "orange", "purple", "lime", "light_blue", "yellow", "magenta", "black", "blue", "brown", "green")) {
                    String mat = color.toUpperCase() + "_STAINED_GLASS";
                    String gem = null;
                    for (JsonElement el : blocks) {
                        JsonObject b = el.getAsJsonObject();
                        if (b.has("gem") && b.getAsJsonArray("materials").toString().contains("\"" + mat + "\"")) { gem = b.get("gem").getAsString(); break; }
                    }
                    if (gem == null || !gem.equals(Zones.gemOfGlass(color))) bad.add(color + " -> " + gem + " (we paint " + Zones.gemOfGlass(color) + ")");
                }
                return bad.isEmpty() ? null : String.join("; ", bad);
            } catch (Exception e) { return "blocks.json: " + e; }
        });
    }

    private static void check(List<String> bad, String island, String[] anchors, List<String> areas) {
        Layout lay = Sky.worlds().layout(island);
        if (lay == null) { bad.add(island + " has no layout"); return; }
        for (String a : anchors) if (lay.anchor(a) == null) bad.add(island + ":" + a);
        for (String a : areas) if (lay.area(a) == null) bad.add(island + ": area " + a);
    }
}
