package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.foraging.gen.MarshGenerator;
import com.skyclone.mod.foraging.gen.ParkGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Live checks the integrator runs with {@code /sb selftest foraging}. */
final class ForagingSelfTests {

    private ForagingSelfTests() {}

    static final List<String> MY_ITEMS = List.of("WOODEN_AXE", "STONE_AXE", "GOLDEN_AXE", "IRON_AXE", "DIAMOND_AXE", "ROOKIE_AXE",
            "PROMISING_AXE", "SWEET_AXE", "EFFICIENT_AXE", "JUNGLE_AXE", "TREECAPITATOR_AXE", "SCULPTORS_AXE", "SERIOUSLY_DAMAGED_AXE",
            "DECENT_AXE", "FIG_AXE", "FIGSTONE_AXE", "HELIX_CHOPPER", "PARK_JUNGLE_TRAVEL_SCROLL", "PARK_CAVE_TRAVEL_SCROLL");

    static final List<String> PARK_ANCHORS = List.of("spawn", "warp.jungle", "warp.howl", "spawn.alpha", "npc.axe_trader", "npc.park_ranger",
            "npc.spruce_keeper", "npc.thicket_warden", "npc.harpist", "npc.ember_trials", "npc.jungle_elder",
            "npc.merry_norseman", "npc.strategist", "npc.shaman");
    static final List<String> MARSH_ANCHORS = List.of("spawn", "warp.canyon", "npc.hotf_keeper", "npc.marsh_axewright", "npc.sapwitch", "npc.contest_host");

    static void register() {
        Sky.commands().selfTest("foraging.items-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : MY_ITEMS) if (!Sky.items().exists(id)) bad.add(id);
            for (Wood w : Wood.values()) if (!Sky.items().exists(w.itemId)) bad.add(w.itemId + "(materials)");
            for (ForagingNpcs.Quest q : ForagingNpcs.QUESTS) {
                if (!Sky.items().exists(q.wood())) bad.add(q.wood());
                if (q.finalItem() != null && !Sky.items().exists(q.finalItem())) bad.add(q.finalItem());
            }
            for (ForagingNpcs.Offer o : ForagingNpcs.AXE_TRADER) if (!Sky.items().exists(o.item())) bad.add(o.item());
            return bad.isEmpty() ? null : "missing items: " + bad;
        });
        Sky.commands().selfTest("foraging.recipes-and-drops", () -> {
            List<String> bad = new ArrayList<>();
            for (Recipe r : Sky.recipes().all()) {
                if (r.id == null || !r.id.startsWith("foraging:")) continue;
                if (!Sky.items().exists(r.result)) bad.add(r.id + " -> " + r.result);
                for (String cell : r.grid) {
                    if (cell == null || cell.isEmpty()) continue;
                    String id = Sky.items().exists(cell) ? cell : cell.substring(0, Math.max(0, cell.lastIndexOf(':')));
                    if (!Sky.items().exists(id)) bad.add(r.id + " needs " + cell);
                }
            }
            int mobs = 0;
            for (MobDef d : Sky.mobs().all()) {
                if (!d.id.startsWith("park_") && !d.id.startsWith("marsh_")) continue;
                mobs++;
                for (DropDef dr : d.drops) if (!Sky.items().exists(dr.item)) bad.add(d.id + " drops " + dr.item);
                if (d.drops.isEmpty()) bad.add(d.id + " has no drops left");
            }
            if (mobs < 3) bad.add("only " + mobs + " foraging mobs registered");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.park-layout", () -> {
            String miss = layoutCheck(ParkGenerator.ID, PARK_ANCHORS, List.of("portal.hub", "howling_cave", "howling_cave.deep"), 12);
            if (miss != null) return miss;
            String arrival = ParkGenerator.arrivalProblem(Sky.worlds().layout(ParkGenerator.ID));
            return arrival == null ? null : "park /warp howl arrival (rebuild the Park at v2?): " + arrival;
        });
        Sky.commands().selfTest("foraging.marsh-layout", () -> layoutCheck(MarshGenerator.ID, MARSH_ANCHORS,
                List.of("portal.hub", "mobs.loch", "mobs.ruins.west", "mobs.ruins.south", "mobs.ruins.east"), 12));
        Sky.commands().selfTest("foraging.marsh-wave3", () -> {
            List<String> bad = new ArrayList<>();
            for (IslandResource r : IslandResource.values()) if (!Sky.items().exists(r.itemId)) bad.add("item " + r.itemId);
            for (String m : List.of("marsh_tidetot", "marsh_seacurse", "marsh_hydrospear", "marsh_chillblade", "marsh_chillshot"))
                if (Sky.mobs().def(m) == null) bad.add("mob " + m);
            if (HotfPerk.byId("collector") == null) bad.add("Collector perk");
            for (String e : List.of("karma", "petalfall", "ultimate_missile")) if (Sky.items().enchant(e) == null) bad.add("enchant " + e + " (enchanting data)");
            if (IslandResource.ofBlock("SEA_PICKLE") != IslandResource.SEA_LUMIES || IslandResource.collectorChance(50) != 1.0) bad.add("resource table");
            HotfAbilities.Ability toss = HotfAbilities.byId("axe_toss"), mana = HotfAbilities.byId("mana_toss");
            if (toss == null || toss.cooldown(1) != 120 || toss.duration(1) != 10 || mana == null || mana.tier() != 6 || mana.cooldown(1) != 60)
                bad.add("axe ability numbers (research 07 §4.5)");
            Layout l = Sky.worlds().layout(MarshGenerator.ID);
            if (l != null && l.data() != null) {
                for (String k : List.of("marsh.lilacs", "marsh.lumies", "marsh.veilshrooms")) {
                    String v = l.data().get(k);
                    if (v == null || Integer.parseInt(v) < 20) bad.add(k + "=" + v + " (rebuild the Moonmarsh: generator v2)");
                }
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        // ---- wave 5 -------------------------------------------------------------------------
        Sky.commands().selfTest("foraging.hives", () -> {
            List<String> bad = new ArrayList<>();
            if (!Sky.items().exists(Hives.HONEYCOMB)) bad.add("item HONEYCOMB (materials)");
            for (String id : List.of("FUN_SIZED_POT_OF_HONEYCOMB", "FAMILY_SIZED_POT_OF_HONEYCOMB", "JUMBO_POT_OF_HONEYCOMB", "BEHEMOTH_POT_OF_HONEYCOMB")) {
                ItemDef d = Sky.items().def(id);
                if (d == null || d.extra == null || !d.extra.has("latherMinutes")) bad.add("pot " + id);
            }
            for (Wood w : List.of(Wood.FIG, Wood.MANGROVE, Wood.HELIX)) if (Hives.pick(w) == null) bad.add("no hunting critter for " + w + " lathering");
            if (HiveMath.refillMillis(300, true) != 240_000 || HiveMath.honeycomb(3, true, false) != 5) bad.add("hive math");
            Layout l = Sky.worlds().layout(MarshGenerator.ID);
            if (l != null && l.data() != null && (l.area("honey_hives") == null || Integer.parseInt(l.data().getOrDefault("marsh.hives", "0")) < 4))
                bad.add("Moonmarsh has no hives (rebuild it: generator v3)");
            Layout t = Sky.worlds().layout(Chopper.TORRHUS);
            if (t != null && t.area("honey_hives") == null) bad.add("torrhus_canyon has no honey_hives area");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.depths", () -> {
            List<String> bad = new ArrayList<>();
            if (DepthMath.maxAirTicks(30, 10) != 300 || DepthMath.effective(60, 40) != 20) bad.add("depth math");
            for (String z : List.of("dimwater_depths", "torrhus_canyon_depths"))
                if (!FDials.depthZones.get().contains(z)) bad.add("dial foraging.depth-zones lacks " + z);
            Layout l = Sky.worlds().layout(MarshGenerator.ID);
            if (l != null && l.data() != null && l.data().get("marsh.depths_floor") == null) bad.add("Moonmarsh has no depths (rebuild it: generator v3)");
            Layout t = Sky.worlds().layout(Chopper.TORRHUS);
            if (t != null && t.zones().stream().noneMatch(z -> "torrhus_canyon_depths".equals(z.id()))) bad.add("torrhus_canyon has no torrhus_canyon_depths zone");
            for (String id : List.of("PRESSURE_TALISMAN", "RESPIRATION_TALISMAN", "DIVER_HELMET"))
                if (!Sky.items().exists(id)) bad.add("item " + id);
            if (Bonuses.otherStat(Sky.items().def("PRESSURE_TALISMAN"), "PRESSURE_RESISTANCE") <= 0) bad.add("PRESSURE_TALISMAN has no otherStats.PRESSURE_RESISTANCE");
            for (String p : List.of("pressure_resistance", "iron_lungs")) if (HotfPerk.byId(p) == null) bad.add("perk " + p);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.contest-wave5", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : List.of(Contest.MARSH_COUPON, Contest.CANYON_COUPON, Contest.MARSH_PRIZE, Contest.CANYON_PRIZE))
                if (!Sky.items().exists(id)) bad.add("item " + id);
            int shown = 0;
            for (Contest.Offer o : Contest.HERBALIST) if (Sky.items().exists(o.item())) shown++;
            for (Contest.Offer o : Contest.CURATOR) if (Sky.items().exists(o.item())) shown++;
            if (shown < 12) bad.add("only " + shown + " shop offers have their item");
            for (String t : Contest.TICKETS) if (t != null && !Sky.items().exists(t)) bad.add("ticket " + t + " (hunting)");
            if (Contest.fishPoints(true, false, null, false) != 10 || Contest.fishPoints(false, true, "outstanding", true) != 750) bad.add("fish points");
            if (Contest.coupons(7) != 45 || Contest.bits(0) != 15) bad.add("coupon/bit tables");
            for (String p : List.of("starlyn_supreme", "two_for_one", "hunters_luck", "daily_grove", "daily_hive")) if (HotfPerk.byId(p) == null) bad.add("perk " + p);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.bonus-sources", () -> {
            List<String> bad = new ArrayList<>();
            java.util.Set<Integer> slots = new java.util.HashSet<>();
            for (HotfPerk p : HotfPerk.ALL) if (!slots.add(p.slot())) bad.add("two perks on slot " + p.slot());
            for (String fam : List.of("MOONGLADE", "TORRHUS", "PRESSURE", "RESPIRATION", "LUMBERJACK", "HONEYCOMB"))
                for (String s : List.of("_TALISMAN", "_RING", "_ARTIFACT")) if (!Sky.items().exists(fam + s)) bad.add("accessory " + fam + s);
            if (Bonuses.otherStat(Sky.items().def("MOONGLADE_TALISMAN"), "FIG_FORTUNE") != 5) bad.add("MOONGLADE_TALISMAN fig fortune not readable");
            if (Bonuses.otherStat(Sky.items().def("TORRHUS_ARTIFACT"), "HELIX_FORTUNE") != 15) bad.add("TORRHUS_ARTIFACT helix fortune not readable");
            for (String id : List.of("MANGROVE_VINE", "TORRHUS_BELT")) if (!Sky.items().exists(id)) bad.add("belt " + id);
            if (Sky.items().enchant("scuba") == null || Sky.items().enchant("respiration") == null) bad.add("enchants scuba/respiration (enchanting data)");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        // ---- wave 5, second pass
        Sky.commands().selfTest("foraging.boosters", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : List.of("SWEEP_BOOSTER", "SWEEP_BOOSTER_UNCOMMON", "FORAGING_FORTUNE_BOOSTER", "FORAGING_FORTUNE_BOOSTER_UNCOMMON",
                    "FORAGING_WISDOM_BOOSTER", "FORAGING_WISDOM_BOOSTER_UNCOMMON", "HUNTING_WISDOM_BOOSTER_COMMON", "HUNTING_WISDOM_BOOSTER_UNCOMMON",
                    "FIGHTING_BOOSTER", "FIGHTING_BOOSTER_UNCOMMON")) {
                ItemDef d = Sky.items().def(id);
                if (d == null) { bad.add("missing " + id); continue; }
                if (Boosters.boosterKind(d) == null || Boosters.boosterTier(d) < 1) bad.add(id + " has no booster kind/tier (another module registered it first?)");
            }
            for (String id : List.of(Boosters.FAVOR, "TREE_THE_FISH")) if (!Sky.items().exists(id)) bad.add("missing " + id);
            if (!Boosters.gear(Sky.items().def("TREECAPITATOR_AXE"))) bad.add("the Timberfeller is not foraging gear");
            if (Sky.items().exists("ASPECT_OF_THE_END") && Boosters.gear(Sky.items().def("ASPECT_OF_THE_END"))) bad.add("a sword counts as foraging gear");
            if (BoosterMath.check(BoosterMath.Kind.SWEEP, 2, null, 0) == null) bad.add("an Uncommon booster went on a bare item");
            if (BoosterMath.check(BoosterMath.Kind.SWEEP, 2, BoosterMath.Kind.SWEEP, 1) != null) bad.add("Common -> Uncommon upgrade refused");
            if (BoosterMath.check(BoosterMath.Kind.SWEEP, 1, BoosterMath.Kind.FIGHTING, 1) == null) bad.add("two boosters on one item");
            // the stat lands on the item: a Timberfeller with an Uncommon Sweep booster has 2 more Sweep than without
            if (Sky.items().exists("TREECAPITATOR_AXE")) {
                org.bukkit.inventory.ItemStack a = Sky.items().create("TREECAPITATOR_AXE", 1), b = Sky.items().create("TREECAPITATOR_AXE", 1);
                Sky.items().update(b, x -> { x.extra.addProperty(Boosters.K_KIND, "SWEEP"); x.extra.addProperty(Boosters.K_TIER, 2); });
                double diff = Sky.items().sheet(b).total(com.skyclone.api.stat.Stat.SWEEP) - Sky.items().sheet(a).total(com.skyclone.api.stat.Stat.SWEEP);
                double want = BoosterMath.Kind.SWEEP.uncommon * FDials.boosterScale.get();
                if (FDials.boosters.get() && Math.abs(diff - want) > 1e-6) bad.add("Uncommon Sweep booster gave " + diff + " Sweep, want " + want);
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.gift-tables", () -> {
            List<String> bad = new ArrayList<>();
            for (Wood w : List.of(Wood.FIG, Wood.MANGROVE, Wood.HELIX)) {
                for (GiftTables.Creature c : GiftTables.creatures(w))
                    if (Sky.mobs().def(c.mob()) == null && (c.fallback() == null || Sky.mobs().def(c.fallback()) == null)) bad.add(w + " creature " + c.mob() + " not registered");
                int ok = 0;
                for (GiftTables.Extra e : GiftTables.extras(w)) for (String id : e.ids()) if (Sky.items().exists(id)) { ok++; break; }
                if (ok < 5) bad.add(w + ": only " + ok + " bonus drops resolve");
            }
            if (GiftTables.pick(Wood.FIG, 1, 0.117) == null || GiftTables.pick(Wood.FIG, 1, 0.21) != null) bad.add("fig creature odds are not 20.1%");
            if (BoosterMath.extrapolate(new double[]{1, 2, 3, 4, 5}, 6) != 6) bad.add("Karma VI is not 6%");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.wyrm-hollow", () -> {
            List<String> bad = new ArrayList<>();
            if (!Sky.items().exists(ForagingNpcs.JADE_EGG)) bad.add("pets did not register " + ForagingNpcs.JADE_EGG);
            for (String[] m : ForagingNpcs.JADE_MATS) if (!Sky.items().exists(m[0])) bad.add("missing " + m[0]);
            for (Contest.Offer o : Contest.HERBALIST) if (Contest.resolve(o.item()) == null) bad.add("Herbalist sells unknown " + o.item());
            for (Contest.Offer o : Contest.CURATOR) if (Contest.resolve(o.item()) == null) bad.add("Curator sells unknown " + o.item());
            Layout l = Sky.worlds().layout(MarshGenerator.ID);
            if (l != null) {
                if (l.anchor("npc.jade_dragon") == null) bad.add("Moonmarsh built without the Wyrm's Hollow (rebuild at v4)");
                int hot = 0;
                for (String k : l.anchors().keySet()) if (k.startsWith("fishing.hotspot.")) hot++;
                if (hot < 2) bad.add("only " + hot + " fishing.hotspot anchors");
                if (l.area("lava_vent") == null) bad.add("no lava vent");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.npc-anchors", () -> {
            List<String> bad = new ArrayList<>();
            for (NpcDef d : Sky.npcs().all()) {
                if (!d.id.startsWith("foraging_")) continue;
                Layout l = Sky.worlds().layout(d.island);
                if (l == null) { bad.add(d.id + ": no layout for " + d.island); continue; }
                if (l.anchor(d.anchor) == null) bad.add(d.id + ": " + d.island + " has no " + d.anchor + (d.island.equals("hub") ? " (hub builder request)" : ""));
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("foraging.formulas", () -> {
            // research 07 §4.3: zero toughness 1 + Sweep, cap 36; tough trees cap +20
            if (SweepMath.logsPerSwing(0, 0, false, 0.5, false) != 1) return "sweep 0 should fell 1 log";
            if (SweepMath.logsPerSwing(25, 0, false, 0.5, false) != 26) return "Timberfeller (25 Sweep) should fell 26 logs";
            if (SweepMath.logsPerSwing(500, 0, false, 0.5, false) != 36) return "cap must be 36 logs";
            if (SweepMath.logsPerSwing(25, 0, true, 0.5, false) != 13) return "thrown 25 Sweep should fell 13 logs";
            if (SweepMath.logsPerSwing(20, 10, false, 0.5, false) != 11) return "fig, 20 Sweep: 1 + 20/2 = 11";
            if (SweepMath.logsPerSwing(320, 150, false, 0.5, false) != 21) return "helix 320 Sweep should cap at 21";
            if (SweepMath.giftShare(0.09) != 0 || SweepMath.giftShare(0.10) != 1 || SweepMath.giftShare(0.2) != 1.5 || SweepMath.giftShare(0.34) != 2)
                return "tree gift shares wrong";
            if (SweepMath.hotfTier(37_000) != 4 || SweepMath.hotfTier(546_999) != 7 || SweepMath.hotfTier(547_000) != 8) return "HotF tiers wrong";
            if (SweepMath.perkCost(0, 3.0) != 8 || SweepMath.keystoneCost(0) != 32768 || SweepMath.linearCost(0, 200, 18) != 218) return "HotF costs wrong";
            if (SweepMath.dropsForLog(100, new Random(1)) != 2) return "100 Foraging Fortune must double drops";
            if (SweepMath.tokensAt(8) != 15) return "15 tokens by tier VIII";
            ItemDef t = Sky.items().def("TREECAPITATOR_AXE");
            if (t == null || t.stats.getOrDefault(com.skyclone.api.stat.Stat.SWEEP, 0.0) != 25) return "Timberfeller must carry +25 Sweep";
            return null;
        });
    }

    private static String layoutCheck(String island, List<String> anchors, List<String> areas, int minSouls) {
        Layout l = Sky.worlds().layout(island);
        if (l == null) return island + " has no layout yet (build it: /sb island build " + island + ")";
        List<String> bad = new ArrayList<>();
        for (String a : anchors) if (l.anchor(a) == null) bad.add(a);
        for (String a : areas) if (l.area(a) == null) bad.add("area " + a);
        long souls = l.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
        if (souls < minSouls) bad.add("only " + souls + " fairy souls");
        return bad.isEmpty() ? null : island + " missing: " + bad;
    }
}
