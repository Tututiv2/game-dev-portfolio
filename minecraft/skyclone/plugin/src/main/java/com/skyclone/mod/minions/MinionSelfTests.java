package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Menu;
import com.skyclone.mod.minions.MinionCatalog.Drop;
import com.skyclone.mod.minions.MinionCatalog.FuelDef;
import com.skyclone.mod.minions.MinionCatalog.TierDef;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import com.skyclone.mod.minions.MinionStore.Placed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /sb selftest minions - the data hangs together and the formulas give the research numbers. */
final class MinionSelfTests {

    private MinionSelfTests() {}

    /** A fixed environment: no bonuses, every item stacks to 64 and sells for its registered NPC price. */
    static final class StubEnv implements MinionSim.Env {
        double speed;
        @Override public double speedBonus(Placed m, TypeDef t) { return speed; }
        @Override public double outputMult(Placed m, TypeDef t) { return 1; }
        @Override public boolean solarActive() { return true; }
        @Override public int maxStack(String itemId) { return 64; }
        @Override public double npcPrice(String itemId) {
            var d = Sky.items().def(itemId);
            return d == null ? 1 : d.npcSell;
        }
        @Override public boolean known(String itemId) { return true; }
    }

    static Placed fake(String type, int tier, long start) {
        Placed m = new Placed();
        m.id = "selftest-" + type;
        m.type = type;
        m.tier = tier;
        m.world = "none";
        m.lastUpdate = start;
        return m;
    }

    static void register(MinionsModule mod) {
        MinionCatalog cat = mod.cat;

        Sky.commands().selfTest("minions.types-complete", () -> {
            if (cat.types.size() < 61) return "only " + cat.types.size() + " minion types (research: 61)";
            List<String> bad = new ArrayList<>();
            int items = 0;
            for (TypeDef t : cat.types()) {
                if (t.drops.isEmpty()) bad.add(t.id + " has no drops");
                if (t.tiers.isEmpty() || t.tiers.get(0).tier != 1) bad.add(t.id + " does not start at tier I");
                for (int i = 0; i < t.tiers.size(); i++) {
                    TierDef td = t.tiers.get(i);
                    if (td.tier != i + 1) bad.add(t.id + " tier gap at " + (i + 1));
                    if (td.tba <= 0 || td.storage <= 0) bad.add(t.id + " " + td.tier + " has no TBA/storage");
                    if (!Sky.items().exists(t.itemId(td.tier))) bad.add(t.itemId(td.tier) + " not registered");
                    else items++;
                }
            }
            if (items < 719) bad.add("only " + items + " minion items registered (reference: 719)");
            return bad.isEmpty() ? null : bad.size() + " problems: " + String.join("; ", bad.subList(0, Math.min(6, bad.size())));
        });

        Sky.commands().selfTest("minions.drops-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (TypeDef t : cat.types())
                for (Drop d : t.drops) if (!Sky.items().exists(d.item) && !missing.contains(d.item)) missing.add(d.item + " (" + t.id + ")");
            return missing.isEmpty() ? null : "minions would make unregistered items: " + String.join(", ", missing);
        });

        Sky.commands().selfTest("minions.gear-exists", () -> {
            List<String> missing = new ArrayList<>();
            for (FuelDef f : cat.fuels.values()) if (!Sky.items().exists(f.id)) missing.add(f.id);
            for (UpgradeDef u : cat.upgrades.values()) if (!Sky.items().exists(u.id)) missing.add(u.id);
            // fuels/upgrades other modules own (slayer drops, armour) are reported but tolerated
            Set<String> foreign = Set.of("COAL", "CHARCOAL", "COAL_BLOCK", "ENCHANTED_BREAD", "ENCHANTED_COAL", "ENCHANTED_CHARCOAL",
                    "ENCHANTED_EGG", "HAMSTER_WHEEL", "FOUL_FLESH", "KRAMPUS_HELMET", "POWER_CRYSTAL", "SCORCHED_POWER_CRYSTAL");
            List<String> mine = new ArrayList<>();
            for (String id : missing) if (!foreign.contains(id)) mine.add(id);
            if (!mine.isEmpty()) return "own items not registered: " + String.join(", ", mine);
            if (!missing.isEmpty()) Sky.log().info("[minions] fuels/upgrades owned elsewhere and not registered yet: " + missing);
            return null;
        });

        Sky.commands().selfTest("minions.recipes", () -> {
            int missing = 0;
            List<String> ex = new ArrayList<>();
            // tiers skipped because an INGREDIENT's owner module is not installed (slayer drops...) are reported
            // by /sb minions recipes; this test fails only when our own registration lost a recipe
            java.util.Set<String> blocked = new java.util.HashSet<>();
            for (String s : mod.recipesSkipped) blocked.add(s.substring(0, Math.max(0, s.indexOf(':'))));
            for (TypeDef t : cat.types()) {
                for (TierDef td : t.tiers) {
                    String id = t.itemId(td.tier);
                    boolean has = Sky.recipes().producing(id).stream().anyMatch(r -> r.type == Recipe.Type.CRAFTING);
                    if (!has && td.tier <= 11 && !blocked.contains(id)) {
                        missing++;
                        if (ex.size() < 6) ex.add(id);
                    }
                }
                if (t.unlock == null) ex.add(t.id + " has no unlock key");
                else if (!t.unlock.startsWith("collection:") && !t.unlock.startsWith("slayer:") && !t.unlock.startsWith("skill:"))
                    ex.add(t.id + " odd unlock " + t.unlock);
            }
            if (missing > 0) return missing + " craftable tiers have no recipe (skipped: " + mod.recipesSkipped.size() + "), e.g. " + ex;
            return ex.isEmpty() ? null : String.join("; ", ex);
        });

        Sky.commands().selfTest("minions.formula", () -> {
            // research 02 section 4.1: Cobblestone I, TBA 14 s -> one cobblestone every 28 s.
            TypeDef cob = cat.type("COBBLESTONE");
            if (cob == null || cob.tier(1).tba != 14) return "Cobblestone I TBA is not 14s";
            double ph = MinionSim.perHour(cob, 1, 0, 1).get("COBBLESTONE");
            if (Math.abs(ph - 3600.0 / 28) > 1e-6) return "Cobblestone I makes " + ph + "/h, expected 128.57";
            // +25% (Enchanted Lava Bucket) divides the TBA: 14 / 1.25 = 11.2 s
            if (Math.abs(MinionSim.effectiveTba(14, 25) - 11.2) > 1e-9) return "speed formula wrong";
            // slot table (research 02 section 4.3)
            if (cat.placeableFor(0) != 5 || cat.placeableFor(5) != 6 || cat.placeableFor(649) != 25 || cat.placeableFor(650) != 26)
                return "slot table wrong: " + cat.placeableFor(0) + "/" + cat.placeableFor(5) + "/" + cat.placeableFor(650);
            if (cat.sbxpForTier(1) != 1 || cat.sbxpForTier(11) != 12 || cat.sbxpForTier(12) != 24) return "SkyBlock XP per tier wrong";
            // one simulated hour: 128 harvests worth, but tier I holds 64 -> full
            long t0 = 1_000_000_000_000L;
            Placed m = fake("COBBLESTONE", 1, t0);
            MinionSim.Result r = MinionSim.advance(cat, m, t0 + 3_600_000L, new StubEnv(), false);
            if (r.harvests != 128) return "1h of Cobblestone I = " + r.harvests + " harvests, expected 128";
            if (m.count("COBBLESTONE") != 64 || !r.full) return "storage cap failed: " + m.count("COBBLESTONE");
            // timed fuel: 1 Coal (30 min, +5%) over an hour powers exactly half of it
            Placed f = fake("COBBLESTONE", 1, t0);
            f.fuel = "COAL";
            f.fuelCount = 1;
            f.fuelLeftMs = 1_800_000L;
            double powered = MinionSim.burnFuel(cat, f, 3600, true);
            if (Math.abs(powered - 1800) > 1e-6 || f.fuel != null) return "coal burned " + powered + "s";
            return null;
        });

        Sky.commands().selfTest("minions.compaction", () -> {
            if (!cat.enchantedRules.containsKey("COBBLESTONE")) return "no Ultra Compactor rule for cobblestone";
            MinionCatalog.Rule rule = cat.enchantedRules.get("COBBLESTONE");
            if (rule.amount != 160 || !"ENCHANTED_COBBLESTONE".equals(rule.to)) return "cobblestone rule is " + rule.amount + " -> " + rule.to;
            long t0 = 1_000_000_000_000L;
            Placed m = fake("COBBLESTONE", 11, t0);
            m.upgrade1 = "SUPER_COMPACTOR_3000";
            MinionSim.Result r = new MinionSim.Result();
            MinionSim.deliver(cat, m, Map.of("COBBLESTONE", 1600L), MinionSim.capacity(cat, m), new StubEnv(), r);
            if (m.count("ENCHANTED_COBBLESTONE") != 10 || m.count("COBBLESTONE") != 0)
                return "1600 cobble compacted into " + m.count("ENCHANTED_COBBLESTONE") + " enchanted + " + m.count("COBBLESTONE");
            for (MinionCatalog.Rule x : cat.enchantedRules.values())
                if (!Sky.items().exists(x.to) || !Sky.items().exists(x.from)) return "compaction rule uses an unregistered item: " + x.from + " -> " + x.to;
            return null;
        });

        Sky.commands().selfTest("minions.beacons", () -> {
            // research 02 section 4.6: Beacon I-V = +2/+4/+6/+8/+10% while a Power Crystal (48 h) runs it
            for (int n = 1; n <= 5; n++) {
                UpgradeDef u = cat.upgrade("BEACON_" + n);
                if (u == null || !"beacon".equals(u.slot)) return "BEACON_" + n + " is not a beacon";
                if (u.speed != 2 * n) return "BEACON_" + n + " gives " + u.speed + "%, research says " + (2 * n) + "%";
                if (!Sky.items().exists(u.id)) return "BEACON_" + n + " not registered";
            }
            UpgradeDef pc = cat.upgrade("POWER_CRYSTAL");
            if (pc == null || pc.seconds != 48 * 3600) return "Power Crystal does not last 48 h";
            long t0 = 1_000_000_000_000L;
            MinionStore s = new MinionStore();
            MinionStore.Crystal c = new MinionStore.Crystal();
            c.id = "selftest";
            c.item = "BEACON_3";
            s.crystals.put(c.id, c);
            s.beaconPoweredUntil = t0 + 3_600_000L;
            MinionManager.Env env = mod.manager.new Env(new MinionManager.Prof("selftest", null, List.of()), s);
            TypeDef cob = cat.type("COBBLESTONE");
            Placed m = fake("COBBLESTONE", 1, t0);
            m.shipping = "BUDGET_HOPPER";   // keeps producing when full, so every harvest is counted
            env.stepStart = t0;
            if (env.breakdown(m, cob).getOrDefault("Beacon III", 0.0) != 6) return "a powered Beacon III does not give +6%";
            env.stepStart = t0 + 7_200_000L;
            if (env.breakdown(m, cob).containsKey("Beacon III")) return "an unpowered Beacon still boosts";
            // 2 h with power for the first hour only: 3600 x 1.06 / 28 + 3600 / 28 = 264.86 harvests
            MinionSim.Result r = mod.manager.advance(m, t0 + 7_200_000L, env, false);
            if (r.harvests != 264) return "2 h with 1 h of Beacon III power = " + r.harvests + " harvests, expected 264";
            // profile stat: tier x dial (Health x2), Scorched x dial, nothing unpowered or unchosen
            double per = mod.dials.beaconStatPerTier.get(), sc = mod.dials.beaconScorchedStat.get();
            if (mod.manager.beaconStatValue(s, t0) != 0) return "a Beacon with no stat chosen still boosts a stat";
            s.beaconStat = "STRENGTH";
            if (Math.abs(mod.manager.beaconStatValue(s, t0) - Math.round(3 * per * 10) / 10.0) > 1e-9)
                return "Beacon III Strength = " + mod.manager.beaconStatValue(s, t0) + ", expected " + (3 * per);
            s.beaconStat = "HEALTH";
            s.beaconScorched = true;
            double hp = Math.round(6 * per * sc * 10) / 10.0;
            if (Math.abs(mod.manager.beaconStatValue(s, t0) - hp) > 1e-9)
                return "scorched Beacon III Health = " + mod.manager.beaconStatValue(s, t0) + ", expected " + hp;
            if (mod.manager.beaconStatValue(s, t0 + 7_200_000L) != 0) return "an unpowered Beacon still boosts a stat";
            return null;
        });

        Sky.commands().selfTest("minions.menus-build", () -> {
            Placed m = fake("WHEAT", 5, System.currentTimeMillis());
            m.upgrade1 = "AUTO_SMELTER";
            m.fuel = "ENCHANTED_LAVA_BUCKET";
            m.fuelCount = 1;
            m.shipping = "BUDGET_HOPPER";
            m.storage.add(new MinionStore.Slot("WHEAT", 32));
            MinionManager.Env env = mod.manager.new Env(new MinionManager.Prof("selftest", null, List.of()), new MinionStore());
            Menu menu = new Menu("Self-test", 6);
            mod.menus.drawMinion(menu, null, m, env);
            if (menu.get(MinionMenus.INFO) == null || menu.get(MinionMenus.FUEL) == null) return "minion menu missing icons";
            return null;
        });

        // ---- wave 5 ----

        Sky.commands().selfTest("minions.wave5-items", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : List.of(MinionManager.FREE_WILL, "SUPER_JACOB_SYSTEM", "JACOBS_PARTICIPATION_MEDAL"))
                if (!Sky.items().exists(id)) bad.add(id + " not registered (ours)");
            // what they make / need, owned elsewhere: the ticket (farming/materials), the chair (crafting), the Postcard
            for (String id : List.of("JACOBS_TICKET", MinionManager.MINION_CHAIR, MinionManager.POSTCARD))
                if (!Sky.items().exists(id)) bad.add(id + " not registered (owner module missing?)");
            UpgradeDef sj = cat.upgrade("SUPER_JACOB_SYSTEM");
            if (sj == null || sj.perSecond == null || sj.perSecond.every != 10800 || !sj.onePerProfile) bad.add("Super Fairground System is not 1 ticket / 3 h, one per island");
            FuelDef medal = cat.fuel("JACOBS_PARTICIPATION_MEDAL");
            if (medal == null || medal.seconds != 72 * 3600 || !"FARMING".equals(medal.onlyCategory) || medal.perSecond == null)
                bad.add("the participation medal is not a 3-day farming-only ticket fuel");
            UpgradeDef fw = cat.upgrade(MinionManager.FREE_WILL);
            if (fw == null || !"freewill".equals(fw.slot) || fw.speed != 10) bad.add("Free Will is not +10% speed");
            TypeDef ghast = cat.type("GHAST");
            if (ghast == null || !"GHAST".equals(ghast.visual)) bad.add("the Ghast minion has no ghast to show");
            for (String id : List.of("SUPER_JACOB_SYSTEM", "JACOBS_PARTICIPATION_MEDAL"))
                if (Sky.recipes().producing(id).isEmpty()) bad.add(id + " has no recipe (/sb minions recipes)");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("minions.chair-freewill", () -> {
            long t0 = 1_000_000_000_000L;
            // a seated minion makes nothing, burns no fuel and does not count toward the cap (research 4.3)
            Placed m = fake("COBBLESTONE", 1, t0);
            m.seated = true;
            m.chair = MinionManager.MINION_CHAIR;
            m.fuel = "COAL";
            m.fuelCount = 1;
            m.fuelLeftMs = 1_800_000L;
            MinionSim.Result r = MinionSim.advance(cat, m, t0 + 3_600_000L, new StubEnv(), false);
            if (r.harvests != 0 || m.stored() != 0) return "a seated minion still worked: " + r.harvests + " harvests";
            if (m.fuelLeftMs != 1_800_000L || m.lastUpdate != t0 + 3_600_000L) return "a seated minion burnt fuel or did not advance its clock";
            MinionStore s = new MinionStore();
            s.placed.put(m.id, m);
            Placed w = fake("WHEAT", 1, t0);
            w.id = "selftest-working";
            s.placed.put(w.id, w);
            if (s.working() != 1) return "2 placed, 1 seated counts as " + s.working() + " working";
            if (!MinionManager.attachments(m).contains(MinionManager.MINION_CHAIR)) return "the chair would not come back on pick-up";
            // Free Will odds: 50% at I, +4% a tier, never over 100
            if (MinionSim.freeWillChance(1, 50, 4) != 50 || MinionSim.freeWillChance(11, 50, 4) != 90
                    || MinionSim.freeWillChance(12, 50, 4) != 94 || MinionSim.freeWillChance(12, 90, 10) != 100)
                return "Free Will chance table wrong";
            // a loyal minion is 10% faster: the speed breakdown names it
            Placed loyal = fake("COBBLESTONE", 1, t0);
            loyal.freeWill = true;
            MinionManager.Env env = mod.manager.new Env(new MinionManager.Prof("selftest", null, List.of()), new MinionStore());
            if (env.breakdown(loyal, cat.type("COBBLESTONE")).getOrDefault("Free Will", 0.0) != 10) return "Free Will does not add +10% speed";
            // the minion menu draws a seated, loyal minion with its chair and Free Will icons
            Menu menu = new Menu("Self-test", 6);
            m.freeWill = true;
            mod.menus.drawMinion(menu, null, m, env);
            if (menu.get(MinionMenus.CHAIR) == null || menu.get(MinionMenus.FREEWILL) == null || menu.get(MinionMenus.STATUS) == null)
                return "seated/loyal minion menu missing icons";
            return null;
        });

        Sky.commands().selfTest("minions.winter-present", () -> {
            // once per SkyBlock year, only with the crystal, never two waiting at once
            if (!MinionManager.presentDue(true, 3, false, 4)) return "a new year did not bring a present";
            if (MinionManager.presentDue(true, 4, false, 4)) return "two presents in one year";
            if (MinionManager.presentDue(true, 3, true, 4)) return "a second present while one is unclaimed";
            if (MinionManager.presentDue(false, 3, false, 4)) return "a present without a Winter Crystal";
            if (!MinionManager.presentDue(true, 0, false, 1)) return "the first year with a fresh island got none";
            UpgradeDef wc = cat.upgrade(MinionManager.WINTER_CRYSTAL);
            if (wc == null || !"crystal".equals(wc.slot) || wc.speed != 5 || wc.types == null || !wc.types.contains("SNOW"))
                return "Winter Crystal is not +5% Snow minions";
            String item = mod.dials.winterPresentItem.get();
            if (!Sky.items().exists(item)) return "the present's item " + item + " is not registered (dial minions.winter-present-item)";
            return null;
        });

        Sky.commands().selfTest("minions.jacob-tickets", () -> {
            long t0 = 1_000_000_000_000L;
            // Super Fairground Entertainment System: one ticket per 3 h of work, farming minions only
            Placed wheat = fake("WHEAT", 1, t0);
            wheat.upgrade1 = "SUPER_JACOB_SYSTEM";
            wheat.shipping = "BUDGET_HOPPER";     // keeps working when full
            MinionSim.Result r = MinionSim.advance(cat, wheat, t0 + 9 * 3_600_000L, new StubEnv(), false);
            long tickets = r.produced.getOrDefault("JACOBS_TICKET", 0L);
            if (tickets != 3) return "9 h with the Super Fairground System made " + tickets + " tickets, expected 3";
            if (cat.upgrade("SUPER_JACOB_SYSTEM").appliesTo(cat.type("COBBLESTONE"))) return "the Super Fairground System works in a mining minion";
            // the medal: one ticket per 4 h of BURN, and nothing in a non-farming minion (the fuel still burns)
            Placed w2 = fake("WHEAT", 1, t0);
            w2.fuel = "JACOBS_PARTICIPATION_MEDAL";
            w2.fuelCount = 1;
            w2.fuelLeftMs = 72 * 3_600_000L;
            w2.shipping = "BUDGET_HOPPER";
            MinionSim.Result r2 = MinionSim.advance(cat, w2, t0 + 96 * 3_600_000L, new StubEnv(), false);
            long t2 = r2.produced.getOrDefault("JACOBS_TICKET", 0L);
            if (t2 != 18) return "a medal over 96 h (72 h of burn) made " + t2 + " tickets, expected 18";
            Placed cob = fake("COBBLESTONE", 1, t0);
            cob.fuel = "JACOBS_PARTICIPATION_MEDAL";
            cob.fuelCount = 1;
            cob.fuelLeftMs = 72 * 3_600_000L;
            cob.shipping = "BUDGET_HOPPER";
            MinionSim.Result r3 = MinionSim.advance(cat, cob, t0 + 8 * 3_600_000L, new StubEnv(), false);
            if (r3.produced.containsKey("JACOBS_TICKET")) return "the medal made tickets in a Cobblestone Minion";
            // pet perks: the keys the pets module answers for
            if (!MinionSim.petAbilityKeys(cat.type("PIG")).containsAll(List.of("pigman_bacon_farmer", "rabbit_efficient_farming")))
                return "Pig minion pet keys wrong: " + MinionSim.petAbilityKeys(cat.type("PIG"));
            if (!MinionSim.petAbilityKeys(cat.type("COBBLESTONE")).isEmpty()) return "a Cobblestone minion has a pet perk";
            return null;
        });
    }
}
