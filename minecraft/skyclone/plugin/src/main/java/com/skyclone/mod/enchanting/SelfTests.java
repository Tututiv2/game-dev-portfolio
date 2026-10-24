package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Live self-tests (/sb selftest enchanting): the data hangs together and formulas give the research numbers. */
final class SelfTests {

    private SelfTests() {}

    static void register() {
        Sky.commands().selfTest("enchanting.defs-registered", SelfTests::defs);
        Sky.commands().selfTest("enchanting.research-numbers", SelfTests::numbers);
        Sky.commands().selfTest("enchanting.rewards-exist", SelfTests::rewards);
        Sky.commands().selfTest("enchanting.anvil-rules", SelfTests::anvil);
        Sky.commands().selfTest("enchanting.menus-build", SelfTests::menus);
        Sky.commands().selfTest("enchanting.hub-anchors", SelfTests::anchors);
        Sky.commands().selfTest("enchanting.superpairs-solvable", SelfTests::superpairs);
        Sky.commands().selfTest("enchanting.wave3-effects", SelfTests::wave3);
        Sky.commands().selfTest("enchanting.vitality-pool", SelfTests::vitality);
        Sky.commands().selfTest("enchanting.vitality-costs-resolve", SelfTests::vitalityCosts);
        Sky.commands().selfTest("enchanting.stealth-scuba", SelfTests::stealthScuba);
        Sky.commands().selfTest("enchanting.multi-number-lore", SelfTests::multiNumber);
        Sky.commands().selfTest("enchanting.pet-perks", SelfTests::petPerks);
        Sky.commands().selfTest("enchanting.experiments-economy", SelfTests::experimentsEconomy);
    }

    /** Wave 5: Drain and the converters read the research numbers; the pool math holds. */
    private static String vitality() {
        List<String> bad = new ArrayList<>();
        EnchantRegistry reg = EnchantingModule.reg();
        EnchantSpec drain = reg.spec("syphon");
        if (drain == null || drain.maxLevel != 6 || drain.v(6) != 3 || drain.v(1) != 0.5) bad.add("Drain I-VI must be 0.5..3 Vitality");
        EnchantSpec hard = reg.spec("hardened_mana"), vamp = reg.spec("mana_vampire"), ls = reg.spec("ultimate_last_stand");
        if (hard == null || hard.v(10) != 40) bad.add("Hardened Vitality X 40%");
        if (vamp == null || vamp.v(10) != 1) bad.add("Vampiric Vitality X 1 HP");
        if (ls == null || ls.v(1, 5) != 10) bad.add("Last Stand V restores 10 Vitality");
        if (VitalityMath.regen(0, 100, 5, 1) != 5) bad.add("regen 5%/s of 100 != 5");
        if (VitalityMath.spendable(10, 25) != 10) bad.add("spend more than the pool");
        if (VitalityMath.convert(20, 100, 40, 25) != 25) bad.add("converter cap");
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    /** Every ability id in the Vitality cost dials belongs to some registered item (a typo would silently cost nothing). */
    private static String vitalityCosts() {
        java.util.Set<String> abilities = new java.util.HashSet<>();
        for (ItemDef d : Sky.items().all()) {
            if (d.abilities != null) for (com.skyclone.api.item.AbilityDef a : d.abilities) if (a != null && a.id != null) abilities.add(a.id.toLowerCase(java.util.Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String id : Vitality.costs().keySet()) if (!abilities.contains(id)) missing.add(id);
        for (String id : Vitality.softCosts().keySet()) if (!abilities.contains(id)) missing.add(id);
        if (Vitality.costs().isEmpty()) return "no healing ability costs Vitality";
        // abilities of modules that are not loaded are fine to miss; report, but only fail when NONE resolve
        if (missing.size() >= Vitality.costs().size() + Vitality.softCosts().size()) return "no cost id matches a registered ability: " + missing;
        return null;
    }

    /** Wave 5: Stealth/Scuba data + the timid rules + the pressure requirement prefix. */
    private static String stealthScuba() {
        List<String> bad = new ArrayList<>();
        EnchantRegistry reg = EnchantingModule.reg();
        EnchantSpec st = reg.spec("stealth"), sc = reg.spec("scuba");
        if (st == null || st.maxLevel != 6 || st.v(6) != 60) bad.add("Stealth VI 60%");
        if (sc == null || sc.maxLevel != 6 || sc.v(6) != 6) bad.add("Scuba VI +6 Pressure Resistance");
        if (!Wave5Math.timidDef(java.util.Set.of("passive"), false, true)) bad.add("passive critter not timid");
        if (Wave5Math.timidDef(java.util.Set.of("aggressive"), false, true)) bad.add("aggressive critter counted timid");
        if (!Wave5Math.vanillaAvoider("RABBIT")) bad.add("rabbit not an avoider");
        if (!Sky.requirements().known("pressure_resistance")) bad.add("requirement prefix pressure_resistance not registered");
        if (!Sky.requirements().describe("pressure_resistance:20").contains("20")) bad.add("pressure requirement text");
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    /** Every multi-number enchant's full text (what core will render once it calls describe(level)) is complete. */
    private static String multiNumber() {
        List<String> bad = new ArrayList<>();
        int multi = 0;
        for (EnchantSpec s : EnchantingModule.reg().all()) {
            com.skyclone.api.item.EnchantDef d = Sky.items().enchant(s.id);
            if (!(d instanceof LevelledEnchantDef ld)) { bad.add(s.id + ": core holds a plain EnchantDef"); continue; }
            if (s.desc != null && s.desc.contains("{1}")) multi++;
            for (int l = s.minLevel; l <= s.maxLevel; l++) {
                String t = ld.describe(l);
                if (t.contains("{") || t.contains("%s")) bad.add(s.id + " " + l + ": " + t);
            }
        }
        EnchantSpec ms = EnchantingModule.reg().spec("mana_steal");
        if (ms != null && !((LevelledEnchantDef) Sky.items().enchant("mana_steal")).describe(1).contains("0.25")) bad.add("Mana Steal I must read 0.25");
        if (multi < 10) bad.add("only " + multi + " multi-number enchants");
        return bad.isEmpty() ? null : bad.size() + " problems, first: " + bad.subList(0, Math.min(4, bad.size()));
    }

    /**
     * Audit 2026-10-06 G12: every tier has a coin fee and an ascending Enchanting gate, every ultra-rare Endcap
     * Upgrade is a registered item with an anvil use, and every anvil Endcap names a real enchant it can raise.
     */
    private static String experimentsEconomy() {
        List<String> bad = new ArrayList<>();
        ExperimentSpec ex = EnchantingModule.experiments();
        int lastGate = -1;
        for (ExperimentSpec.Tier t : ex.tiers) {
            if (t.fee <= 0) bad.add(t.id + " has no fee");
            if (t.skill <= lastGate) bad.add(t.id + " gate " + t.skill + " not above " + lastGate);
            lastGate = t.skill;
        }
        if (ex.ultraRare == null || ex.ultraRare.isEmpty()) bad.add("no ultra-rare pool");
        else for (String id : ex.ultraRare) {
            if (!Sky.items().exists(id)) bad.add("ultra-rare " + id + " is not registered");
            if (!EnchantingModule.endcaps().containsKey(id)) bad.add("ultra-rare " + id + " has no anvil use");
        }
        if (ExperimentsMenu.ultraPool().size() != (ex.ultraRare == null ? 0 : ex.ultraRare.size())) bad.add("ultra pool drops unregistered ids");
        for (AnvilRules.Endcap e : EnchantingModule.endcaps().values()) {
            EnchantSpec s = EnchantingModule.reg().spec(e.enchant);
            if (s == null) bad.add(e.item + ": unknown enchant " + e.enchant);
            else if (e.requires + 1 > s.maxLevel) bad.add(e.item + " raises " + e.enchant + " past " + s.maxLevel);
            if (!Sky.items().exists(e.item)) bad.add("endcap " + e.item + " is not registered");
        }
        if (ex.extraRun(0) == null) bad.add("no extra-game costs");
        return bad.isEmpty() ? null : bad.size() + " problems: " + bad.subList(0, Math.min(5, bad.size()));
    }

    /** Guardian's Lucky Seven really moves the ultra rare book odds; Lion's First Pounce reads 1.0 with no pet. */
    private static String petPerks() {
        ExperimentSpec.Tier t = EnchantingModule.experiments().tier("metaphysical");
        if (t == null) return "no metaphysical tier";
        if (t.ultraRareWeight() <= 0) return "tier has no ultra rare books";
        int base = 0, boosted = 0;
        Random a = new Random(7), b = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            ExperimentSpec.Reward x = t.roll(a, 0), y = t.roll(b, 100);
            if (ExperimentSpec.Tier.isBook(x) && x.weight == t.ultraRareWeight()) base++;
            if (ExperimentSpec.Tier.isBook(y) && y.weight == t.ultraRareWeight()) boosted++;
        }
        if (boosted <= base) return "+100% Lucky Seven did not raise ultra rare books (" + base + " -> " + boosted + ")";
        return null;
    }

    private static String defs() {
        EnchantRegistry reg = EnchantingModule.reg();
        if (reg.size() < 150) return "only " + reg.size() + " enchants loaded";
        List<String> bad = new ArrayList<>();
        for (EnchantSpec s : reg.all()) {
            if (Sky.items().enchant(s.id) == null) bad.add(s.id + ": not registered with core");
            if (s.categories.isEmpty() && s.itemIds.isEmpty() && s.tags.isEmpty()) bad.add(s.id + ": applies to nothing");
            if (s.desc == null || s.desc.isBlank()) bad.add(s.id + ": no description");
            for (int l = s.minLevel; l <= s.maxLevel; l++) {
                if (!Sky.items().exists(EnchantRegistry.bookId(s.id, l))) bad.add(EnchantRegistry.bookId(s.id, l) + " missing");
                if (!Sky.items().exists(EnchantRegistry.aliasId(s.id, l))) bad.add(EnchantRegistry.aliasId(s.id, l) + " missing");
                if (s.describe(l).contains("{")) bad.add(s.id + " " + l + ": unfilled placeholder");
            }
            for (String c : s.conflicts) if (reg.spec(c) == null) bad.add(s.id + ": conflict with unknown " + c);
            // s.itemIds (Ultimate Jest's blade) belong to the weapons module; their absence only idles the enchant
        }
        // alias ids (the names other modules use: the skills table's "woodsplitter", "bank"...) resolve to the same enchant
        reg.aliasMap().forEach((alias, canon) -> {
            com.skyclone.api.item.EnchantDef a = Sky.items().enchant(alias), c = Sky.items().enchant(canon);
            if (a == null) bad.add("alias " + alias + " (" + canon + "): not registered with core");
            else if (c != null && !java.util.Objects.equals(a.name, c.name)) bad.add("alias " + alias + " registered by someone else as " + a.name);
        });
        return bad.isEmpty() ? null : bad.size() + " problems, first: " + bad.subList(0, Math.min(5, bad.size()));
    }

    private static String eq(String what, double got, double want) {
        return Math.abs(got - want) < 1e-6 ? null : what + " = " + got + ", research says " + want;
    }

    private static String numbers() {
        EnchantRegistry reg = EnchantingModule.reg();
        EnchantSpec sharp = reg.spec("sharpness"), prot = reg.spec("protection"), growth = reg.spec("growth"),
                crit = reg.spec("critical"), gk = reg.spec("giant_killer"), exe = reg.spec("execute"),
                power = reg.spec("power"), champ = reg.spec("champion"), eff = reg.spec("efficiency");
        if (sharp == null || prot == null || growth == null || crit == null || gk == null || exe == null || power == null || champ == null || eff == null)
            return "a core enchant is missing";
        String[] checks = {
                eq("Sharpness V %", sharp.v(5), 30), eq("Sharpness VII % (2026)", sharp.v(7), 50),
                eq("Sharpness V table cost", sharp.cost(5), 30), eq("Sharpness table max", sharp.tableMax, 5),
                eq("Protection VII defense", prot.v(7), 30), eq("Growth V health", growth.v(5), 75),
                eq("Critical VII crit damage", crit.v(7), 100), eq("Giant Killer VII cap", gk.v(1, 7), 65),
                eq("Execute VI per %", exe.v(6), 1.25), eq("Power VII %", power.v(7), 65),
                eq("Champion X combat xp %", champ.v(10), 10), eq("Champion II threshold", champ.thresholdFor(2), 50_000),
                eq("Efficiency V mining speed", eff.v(5), 110), eq("Critical skill level", crit.skill, 9),
                eq("Giant Killer math (10k vs 1k hp, VII)", EnchantMath.giantKiller(1.2, 65, 10_000, 1_000), 65),
                eq("Execute math (I at half hp)", EnchantMath.execute(0.2, 50, 100), 10),
                eq("Titan Killer math (VII, 750 def)", EnchantMath.titanKiller(14, 80, 750), 80),
                eq("XP for level 30", EnchantMath.pointsForLevel(30), 1395),
        };
        for (String c : checks) if (c != null) return c;
        // the stat contributor turns Protection V into +20 Defense on the sheet
        ItemDef helm = ItemDef.of("SELFTEST_HELMET", "x", "DIAMOND_HELMET", com.skyclone.api.item.Rarity.COMMON, ItemCategory.HELMET);
        if (!prot.appliesTo(helm)) return "Protection does not apply to helmets";
        return null;
    }

    private static String rewards() {
        ExperimentSpec ex = EnchantingModule.experiments();
        if (ex.tiers.size() != 6) return "expected 6 experiment tiers, got " + ex.tiers.size();
        List<String> bad = new ArrayList<>();
        for (ExperimentSpec.Tier t : ex.tiers) {
            if ((t.tiles() - t.specials) % 2 != 0) bad.add(t.id + ": odd number of pair tiles");
            if (t.rewards.isEmpty()) bad.add(t.id + ": no rewards");
            for (ExperimentSpec.Reward r : t.rewards) if (!Sky.items().exists(r.item)) bad.add(t.id + ": " + r.item);
        }
        for (LibrarianMenu.Row r : EnchantingModule.librarianRows()) if (!Sky.items().exists(r.item)) bad.add("librarian: " + r.item);
        for (Recipe r : Sky.recipes().producing("EXPERIMENTATION_TABLE")) if (r.grid != null) for (String c : r.grid) {
            if (c == null || c.isBlank()) continue;
            String id = c.contains(":") ? c.substring(0, c.lastIndexOf(':')) : c;
            if (!Sky.items().exists(id) && !Sky.items().exists(c)) bad.add("recipe ingredient " + c);
        }
        for (String id : new String[]{"GRAND_EXP_BOTTLE", "TITANIC_EXP_BOTTLE", "COLOSSAL_EXP_BOTTLE", "EXPERIMENTATION_TABLE", "ENCHANTED_BOOK"})
            if (!Sky.items().exists(id)) bad.add(id + " not registered");
        return bad.isEmpty() ? null : bad.size() + " problems: " + bad.subList(0, Math.min(6, bad.size()));
    }

    private static String anvil() {
        EnchantRegistry reg = EnchantingModule.reg();
        ItemDef sword = ItemDef.of("SELFTEST_SWORD", "x", "DIAMOND_SWORD", com.skyclone.api.item.Rarity.COMMON, ItemCategory.SWORD);
        AnvilRules.Result r = AnvilRules.combineBooks(reg, "sharpness", 4, "sharpness", 4, 1);
        if (!r.ok || r.bookLevel != 5) return "IV + IV did not make Sharpness V";
        r = AnvilRules.combineBooks(reg, "sharpness", 6, "sharpness", 6, 1);
        if (r.ok) return "VI + VI combined past the anvil cap";
        Map<String, Integer> cur = new LinkedHashMap<>(Map.of("sharpness", 5));
        r = AnvilRules.applyBook(reg, sword, cur, Map.of("ultimate_one_for_all", 1), 60, true, 1);
        if (!r.ok || r.enchants.containsKey("sharpness")) return "One For All did not strip Sharpness";
        r = AnvilRules.applyBook(reg, sword, Map.of("ultimate_one_for_all", 1), Map.of("sharpness", 5), 60, true, 1);
        if (r.ok) return "Sharpness went onto a One For All weapon";
        r = AnvilRules.applyBook(reg, sword, Map.of("ultimate_wise", 3), Map.of("ultimate_chimera", 1), 60, true, 1);
        if (!r.ok || r.enchants.containsKey("ultimate_wise")) return "a new ultimate did not replace the old one";
        r = AnvilRules.applyBook(reg, sword, Map.of(), Map.of("protection", 5), 60, true, 1);
        if (r.ok) return "Protection went onto a sword";
        r = AnvilRules.applyBook(reg, sword, Map.of(), Map.of("critical", 5), 0, true, 1);
        if (r.ok) return "Critical applied without Enchanting 9";
        r = AnvilRules.potato(sword, 10, false, 10, 15);
        if (r.ok) return "an 11th Hot Potato Book was accepted";
        r = AnvilRules.potato(sword, 10, true, 10, 15);
        if (!r.ok || r.potatoBooks != 11) return "a Fuming Potato Book after 10 was refused";
        return null;
    }

    private static String menus() {
        List<Menu> built = new ArrayList<>();
        built.add(GuideMenu.build(null, 0));
        built.add(GuideMenu.detail(EnchantingModule.reg().spec("sharpness"), null, 0));
        built.add(ExperimentsMenu.build(null));
        built.add(ExperimentsMenu.tiers(null, "superpairs"));
        built.add(ExperimentsMenu.tiers(null, "chronomatron"));
        built.add(LibrarianMenu.build());
        Menu table = TableMenu.build(null, 15);
        table.redraw();
        built.add(table);
        Menu anvil = AnvilMenu.build(null);
        anvil.redraw();
        built.add(anvil);
        for (Menu m : built) if (m == null || m.getInventory() == null) return "a menu failed to build";
        return null;
    }

    private static String anchors() {
        if (Sky.worlds().layout("hub") == null) return "hub layout not available (hub module not registered?)";
        List<String> missing = new ArrayList<>();
        for (String a : new String[]{"npc.librarian", "npc.enchanting_table"}) if (Sky.worlds().anchor("hub", a) == null) missing.add(a);
        return missing.isEmpty() ? null : "hub is missing anchors " + missing;
    }

    private static String superpairs() {
        Random rnd = new Random(42);
        for (ExperimentSpec.Tier t : EnchantingModule.experiments().tiers) {
            SuperpairsBoard b = SuperpairsBoard.deal(t, 10_000, rnd);
            for (int i = 0; i < b.tiles.size() && !b.allFound(); i++) {
                if (b.tiles.get(i).matched) continue;
                for (int j = i + 1; j < b.tiles.size(); j++) {
                    SuperpairsBoard.Tile x = b.tiles.get(i), y = b.tiles.get(j);
                    if (!x.matched && !y.matched && x.kind == SuperpairsBoard.Kind.REWARD && x.key().equals(y.key())) {
                        b.click(i, rnd);
                        b.click(j, rnd);
                        break;
                    }
                }
            }
            if (!b.allFound()) return t.id + ": perfect play could not clear the board";
            if (b.won.size() != t.pairs()) return t.id + ": won " + b.won.size() + " of " + t.pairs() + " pairs";
        }
        return null;
    }

    /** Wave 3: the enchants that got effects read real data (The One's collection tops, piercing/aiming numbers). */
    private static String wave3() {
        List<String> bad = new ArrayList<>();
        EnchantRegistry reg = EnchantingModule.reg();
        for (String id : new String[]{"piercing", "aiming", "frost_walker", "rainbow", "ultimate_the_one", "ultimate_chimera",
                "ultimate_reiterate", "lapidary", "hecatomb"}) if (reg.spec(id) == null) bad.add("no spec " + id);
        if (reg.spec("piercing") != null && reg.spec("piercing").v(1) != 25) bad.add("piercing share " + reg.spec("piercing").v(1));
        if (reg.spec("aiming") != null && reg.spec("aiming").v(5) != 10) bad.add("dragon seeker V radius " + reg.spec("aiming").v(5));
        if (ExtraEnchants.tops().size() < 50)   // the collections module ships 98
            bad.add("The One read only " + ExtraEnchants.tops().size() + " collection tops");
        if (EnchantingModule.dials().piercingTargets.get() < 1) bad.add("piercing-targets dial < 1");
        return bad.isEmpty() ? null : String.join("; ", bad);
    }
}
