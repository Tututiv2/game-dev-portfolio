package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import com.skyclone.mod.combatzones.gen.EndGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Live checks (/sb selftest combatzones). Null = pass. */
final class SelfTests {

    private SelfTests() {}

    static final String[] OWNED = {"SUMMONING_EYE", "SLEEPING_EYE", "REMNANT_OF_THE_EYE", "YOUNG_FRAGMENT", "OLD_FRAGMENT", "STRONG_FRAGMENT",
            "UNSTABLE_FRAGMENT", "WISE_FRAGMENT", "PROTECTOR_FRAGMENT", "SUPERIOR_FRAGMENT", "HOLY_FRAGMENT", "CRYSTAL_FRAGMENT", "MITE_GEL",
            "ENDSTONE_ROSE", "ARACHNE_FRAGMENT", "ARACHNE_KEEPER_FRAGMENT", "ARACHNE_CRYSTAL", "SOUL_STRING", "DARK_QUEENS_SOUL_DROP",
            "ARACHNE_FANG", "SPIDERS_DEN_TOP_TRAVEL_SCROLL", "ARACHNE_SANCTUARY_TRAVEL_SCROLL", "DRAGON_NEST_TRAVEL_SCROLL", "VOID_SEPULTURE_TRAVEL_SCROLL", "RITUAL_RESIDUE"};

    static void register(CombatzonesModule m) {
        Sky.commands().selfTest("combatzones.items-exist", () -> {
            List<String> miss = new ArrayList<>();
            for (String id : OWNED) if (!Sky.items().exists(id)) miss.add(id);
            for (MobDef d : Sky.mobs().all()) {
                if (d.extra == null || !d.extra.has("zone")) continue;
                for (DropDef dd : d.drops) if (!Sky.items().exists(dd.item)) miss.add(d.id + ":" + dd.item);
            }
            for (DragonMath.Variant v : DragonMath.VARIANTS)
                if (Sky.mobs().def("end_dragon_" + v.id()) == null) miss.add("mob end_dragon_" + v.id());
            return miss.isEmpty() ? null : "missing: " + miss;
        });
        Sky.commands().selfTest("combatzones.dragon-flight", () -> {
            // the flight model against the BUILT nest: towers under its clearance, perches every cycle
            org.bukkit.Location pod = m.fight.podium();
            if (pod == null) return null;                                     // The End not built yet
            List<String> bad = new ArrayList<>();
            for (org.bukkit.Location c : Sky.worlds().anchors(EndGenerator.ID, "crystal.")) {
                double h = Math.hypot(c.getX() - pod.getX(), c.getZ() - pod.getZ());
                if (h < DragonFlight.TOWER_IN || h > DragonFlight.TOWER_OUT) bad.add("crystal outside the tower ring at r=" + (int) h);
                // anchor = 1 above the bedrock cap; the iron-bar cages end 3 above it
                if (c.getY() + 3 > pod.getY() + DragonFlight.TOWER_ALT) bad.add("crystal tower top +" + (int) (c.getY() + 3 - pod.getY()) + " above clearance");
            }
            DragonFlight f = new DragonFlight(pod.getX(), pod.getY(), pod.getZ(), pod.getX(), pod.getY() + 19, pod.getZ(), 1.0, new java.util.Random(1));
            int perches = 0;
            DragonFlight.Mode prev = f.mode();
            for (int t = 0; t < 20 * 180; t++) {
                f.tick();
                if (f.mode() == DragonFlight.Mode.PERCH && prev != DragonFlight.Mode.PERCH) perches++;
                prev = f.mode();
                if (f.horizontalFromCentre() > DragonFlight.ORBIT_RADIUS + 8) { bad.add("strayed " + (int) f.horizontalFromCentre() + " from the podium"); break; }
            }
            if (perches < 5) bad.add("only " + perches + " perches in 3 min");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("combatzones.pruned-drops", () ->
                m.prunedDrops.isEmpty() ? null : "loot ids whose owner module is missing (removed from tables): " + m.prunedDrops);
        Sky.commands().selfTest("combatzones.spawn-rules", () -> {
            List<String> bad = new ArrayList<>();
            int n = 0;
            for (String mob : new String[]{"den_splitter_spider_4", "den_dasher_spider_42", "den_weaver_spider_45", "den_flint_skeleton", "den_rain_slime",
                    "den_arachnes_keeper", "end_enderman_42", "end_zealot_55", "end_seer", "end_obsidian_defender", "end_zealot_bruiser", "end_voidling_fanatic"}) {
                if (Sky.mobs().def(mob) == null) bad.add(mob);
                n++;
            }
            return bad.isEmpty() ? null : "spawned mobs missing: " + bad + " of " + n;
        });
        Sky.commands().selfTest("combatzones.anchors", () -> {
            List<String> miss = new ArrayList<>();
            check(miss, DenGenerator.ID, List.of("spawn", "portal.hub", "portal.the_end", "portal.crimson_isle", "npc.spider_tamer", "npc.crystal_mender",
                    "npc.den_guide", "npc.granny", "npc.archaeologist", "npc.quarryman", "altar.arachne", "boss.arachne", "boss.broodmother",
                    "warp.nest", "warp.sanctuary", "npc.ike", "npc.michael"), List.of("spawn.mob.low.", "spawn.mob.mid.", "spawn.mob.high.", "spawn.mob.nest.", "spawn.mob.gravel.",
                    "spawn.mob.slime.", "spawn.mob.burrow.", "fairy_soul."), 19);
            check(miss, EndGenerator.ID, List.of("spawn", "portal.hub", "npc.pearl_dealer", "npc.end_archer", "npc.end_guide", "npc.lone_adventurer",
                    "npc.void_keeper", "altar.dragon", "boss.dragon", "boss.end_stone_protector", "warp.drag", "warp.void"),
                    List.of("spawn.mob.main.", "spawn.mob.zealot.", "spawn.mob.seer.", "spawn.mob.defender.", "spawn.mob.bruiser.", "spawn.mob.void.",
                            "crystal.", "fairy_soul."), 12);
            // hunting grounds: each ground the spawn rules name must hold a pack's worth of anchors
            Layout den = Sky.worlds().layout(DenGenerator.ID);
            if (den != null) for (String pre : DenGenerator.groundPrefixes()) {
                long k = den.anchors().keySet().stream().filter(a -> a.startsWith(pre)).count();
                if (k < 8) miss.add(DenGenerator.ID + ":" + pre + "* has " + k + " anchors (< 8)");
            }
            Layout end = Sky.worlds().layout(EndGenerator.ID);
            if (end != null && (end.data().get("altar.dragon.frames") == null || end.data().get("altar.dragon.frames").split(";").length != 8))
                miss.add("the_end: 8 altar frames");
            if (end != null && Sky.worlds().anchors(EndGenerator.ID, "crystal.").size() != 6) miss.add("the_end: 6 crystals");
            // warp arrivals stand on SAFE pads with no mob anchor within aggro reach (playtest: /warp drag next to a Zealot)
            String arrival = end == null ? null : EndGenerator.arrivalProblem(end);
            // Professor Dunmore's relic hunt: the built Den must carry every relic
            if (den != null && Relics.all().size() != DenGenerator.RELICS) miss.add("spiders_den: " + Relics.all().size() + "/" + DenGenerator.RELICS + " relics (rebuild the Den: v3)");
            if (arrival != null) miss.add("the_end arrivals: " + arrival);
            // the Dragon's altar: all 8 frames in reach and in view from the pedestal, checked on the BUILT world
            org.bukkit.World endWorld = Sky.worlds().world(EndGenerator.ID);
            if (end != null && endWorld != null) {
                String reach = EndGenerator.altarReachProblem(end, (x, y, z) -> !endWorld.getBlockAt(x, y, z).getType().isAir());
                if (reach != null) miss.add("the_end altar (rebuild the End: v4): " + reach);
            }
            return miss.isEmpty() ? null : String.join(", ", miss);
        });
        Sky.commands().selfTest("combatzones.research-numbers", () -> {
            List<String> bad = new ArrayList<>();
            expect(bad, "end_zealot_55", 13000, 1250);
            expect(bad, "end_zealot_bruiser", 65000, 2500);
            expect(bad, "den_broodmother_12", 6000, 300);
            expect(bad, "den_arachne_300", 20000, 1500);
            expect(bad, "den_arachne_500", 100000, 3000);
            expect(bad, "end_dragon_superior", 12_000_000, 1650);
            expect(bad, "end_dragon_old", 15_000_000, 1100);
            expect(bad, "end_dragon_young", 7_500_000, 1100);
            double w = 0;
            for (DragonMath.Variant v : DragonMath.VARIANTS) w += v.weight();
            if (w != 100) bad.add("variant weights sum " + w + " != 100");
            if (DragonMath.quality(8, 1) != 1000) bad.add("quality(8 eyes, 1st) != 1000");
            DragonMath.Award a = DragonMath.award("young", DragonMath.quality(1, 1), 1, new Random(1), id -> true, 8);
            if (!"YOUNG_DRAGON_HELMET".equals(a.item())) bad.add("1 eye + 1st should buy the helmet (295), got " + a.item());
            DragonMath.Award f = DragonMath.award("young", DragonMath.quality(0, 4), 0, new Random(1), id -> true, 8);
            if (f.item() != null || f.fragments() != 5) bad.add("4th place, no eyes should give 5 fragments, got " + f);
            if (DragonMath.abilityDamage(DragonMath.RUSH, DragonMath.variant("young"), 0.4) != 825) bad.add("rush below half HP != 825");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("combatzones.menus-build", () -> {
            var players = org.bukkit.Bukkit.getOnlinePlayers();
            for (org.bukkit.entity.Player p : players) {
                if (!Sky.profiles().isReady(p)) continue;
                m.menus.main(p); m.menus.dragon(p); m.menus.dragonLoot(p); m.menus.silkmother(p); m.menus.scrolls(p);
                m.menus.loot(p, "x", "den_arachne_500", m.menus::main);
                m.essence.keeper(p); m.essence.altar(p); m.essence.shop(p); m.tavi.menu(p); m.mobTypes.menu(p);
                return null;
            }
            return null;   // nobody online: nothing to build against (menus need a viewer)
        });
        // ---- wave 5
        Sky.commands().selfTest("combatzones.dragon-essence", () -> {
            List<String> bad = new ArrayList<>();
            if (DraconicMath.totalSbXp() != 185) bad.add("perk SB XP total " + DraconicMath.totalSbXp() + " != 185 (research 02)");
            if (DraconicMath.PERKS.size() != 9) bad.add(DraconicMath.PERKS.size() + " perks, NEU ESSENCE_DRAGON has 9");
            for (String id : new String[]{DragonEssence.ESSENCE_ITEM, DragonEssence.RESIDUE})
                if (!Sky.items().exists(id)) bad.add("item " + id + " missing");
            List<String> refused = new ArrayList<>();
            for (String id : DraconicMath.SACRIFICE.keySet()) if (!Sky.items().exists(id)) refused.add(id);
            if (refused.size() > 6) bad.add("the altar names " + refused.size() + " unregistered items: " + refused);
            if (Sky.worlds().layout(EndGenerator.ID) != null && m.essence.altarLocation() == null)
                bad.add("the_end layout has no altar.draconic (rebuild the End: v5)");
            if (DraconicMath.essenceFor("SUPERIOR_DRAGON_CHESTPLATE", 1, 1) != 240) bad.add("superior chestplate != 240 essence");
            if (Math.abs(DraconicMath.bonusChance(80, 0.0005, 0.5, 10) - 0.044) > 1e-9) bad.add("bonus chance 80 ess + Sacrificer != 4.4%");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("combatzones.tavi-quests", () -> {
            List<String> bad = new ArrayList<>();
            for (ZoneQuests.Quest q : ZoneQuests.QUESTS) {
                boolean any = false;
                for (MobDef d : Sky.mobs().all()) if (q.counts(d.id)) { any = true; break; }
                if (!any) bad.add(q.id() + ": no registered mob matches " + q.prefixes());
                if (q.item() != null && !Sky.items().exists(q.item())) bad.add(q.id() + ": reward " + q.item() + " missing");
            }
            if (!Sky.items().exists(Adventurer.VOID_SWORD)) bad.add("VOID_SWORD not registered (weapons)");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("combatzones.mob-types", () -> {
            List<String> bad = new ArrayList<>();
            for (MobDef d : Sky.mobs().all()) {
                if (d.extra == null || !d.extra.has("zone")) continue;
                if (m.essence.typesOf(d).isEmpty()) bad.add(d.id);
            }
            int typed = m.mobTypes.byType().size();
            if (typed < 8) bad.add("only " + typed + " mob types carry a registered mob");
            return bad.isEmpty() ? null : "zone mobs with no mob type: " + bad;
        });
        Sky.commands().selfTest("combatzones.walkable", () -> {
            List<String> bad = new ArrayList<>();
            for (var gen : List.of(new DenGenerator(), new EndGenerator())) {
                IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
                Walkability.Options o = new Walkability.Options();
                o.skipFloating = true;
                Walkability.Report rep = Walkability.analyse(r.canvas, r.layout, o);
                for (Walkability.AnchorResult ar : rep.anchors)
                    if (ar.status() == Walkability.Status.UNREACHABLE || ar.status() == Walkability.Status.INSIDE_SOLID) bad.add(gen.islandId() + ":" + ar.name());
                for (Walkability.PortalResult pr : rep.portals) if (!pr.reachable()) bad.add(gen.islandId() + ":" + pr.name());
                if (rep.spawnProblem != null) bad.add(gen.islandId() + ": " + rep.spawnProblem);
            }
            return bad.isEmpty() ? null : "unreachable: " + bad;
        });
    }

    private static void expect(List<String> bad, String id, double hp, double dmg) {
        MobDef d = Sky.mobs().def(id);
        if (d == null) { bad.add(id + " missing"); return; }
        if (d.health != hp || d.damage != dmg) bad.add(id + " " + d.health + "/" + d.damage + " != " + hp + "/" + dmg);
    }

    private static void check(List<String> miss, String island, List<String> anchors, List<String> prefixes, int souls) {
        Layout l = Sky.worlds().layout(island);
        if (l == null) { miss.add(island + ": no layout"); return; }
        for (String a : anchors) if (l.anchor(a) == null && l.area(a) == null) miss.add(island + ":" + a);
        for (String p : prefixes) if (l.anchors().keySet().stream().noneMatch(k -> k.startsWith(p))) miss.add(island + ":" + p + "*");
        long n = l.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
        if (n != souls) miss.add(island + ": " + n + " fairy souls, expected " + souls);
    }

    static SpawnRule unused() { return null; }
}
