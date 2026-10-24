package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.ReforgeDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** {@code /sb selftest reforges}: does the data hang together on the live server? */
final class SelfTests {

    private SelfTests() {}

    static void register() {
        Sky.commands().selfTest("reforges.data-complete", SelfTests::dataComplete);
        Sky.commands().selfTest("reforges.stones-exist", SelfTests::stonesExist);
        Sky.commands().selfTest("reforges.registered-with-core", SelfTests::registered);
        Sky.commands().selfTest("reforges.formula-research-numbers", SelfTests::formulas);
        Sky.commands().selfTest("reforges.live-item-sheet", SelfTests::liveSheet);
        Sky.commands().selfTest("reforges.hub-anchors", SelfTests::anchors);
        Sky.commands().selfTest("reforges.menus-build", SelfTests::menus);
        Sky.commands().selfTest("reforges.own-costs-resolve", SelfTests::ownCosts);
        Sky.commands().selfTest("reforges.wave3-upgrades", SelfTests::wave3);
        Sky.commands().selfTest("reforges.wave5-anvil", SelfTests::wave5Anvil);
        Sky.commands().selfTest("reforges.wave5-bonuses", SelfTests::wave5Bonuses);
        Sky.commands().selfTest("reforges.power-scrolls", SelfTests::powerScrolls);
    }

    /**
     * Wave 5 anvil upgrades on real registered items: Silex lifts a pickaxe/drill at Efficiency V to VI, the
     * Transmission Tuner and Phasewarp Merger write the keys the weapons module reads, Bookworm's book adds
     * vacuum damage to core's sheet, and a Farmer's Almanac on a vacuum adds Farming Fortune.
     */
    static String wave5Anvil() {
        ItemDef pick = null;
        for (ItemDef x : Sky.items().all()) if ((x.category == ItemCategory.PICKAXE || x.category == ItemCategory.DRILL) && x.isUnique()) { pick = x; break; }
        ItemDef silex = Sky.items().def("SIL_EX");
        if (silex == null) return "SIL_EX is not registered";
        if (pick != null) {
            ItemStack it = Sky.items().create(pick.id);
            if (ReforgesModule.MODS.plan(pick, Sky.items().data(it), Sky.items().rarity(it), silex).ok()
                    && Sky.items().data(it).enchant("efficiency") < ItemMods.SILEX_FROM) return "Silex accepted below Efficiency V on " + pick.id;
            Sky.items().update(it, d -> d.enchants.put("efficiency", 5));
            ItemMods.Plan pl = ReforgesModule.MODS.plan(pick, Sky.items().data(it), Sky.items().rarity(it), silex);
            if (!pl.ok()) return "Silex refused on " + pick.id + " at Efficiency V: " + pl.error();
            Sky.items().update(it, pl.change());
            if (Sky.items().data(it).enchant("efficiency") != 6) return "Silex did not raise Efficiency V to VI";
        }
        ItemDef aote = Sky.items().def("ASPECT_OF_THE_VOID"), tuner = Sky.items().def("TRANSMISSION_TUNER"), merger = Sky.items().def("ETHERWARP_MERGER");
        if (aote != null && tuner != null && merger != null) {
            ItemStack it = Sky.items().create(aote.id);
            for (int i = 0; i < ItemMods.TUNER_MAX; i++) {
                ItemMods.Plan pl = ReforgesModule.MODS.plan(aote, Sky.items().data(it), Sky.items().rarity(it), tuner);
                if (!pl.ok()) return "tuner #" + (i + 1) + " refused: " + pl.error();
                Sky.items().update(it, pl.change());
            }
            if (ReforgesModule.MODS.plan(aote, Sky.items().data(it), Sky.items().rarity(it), tuner).ok()) return "a fifth Transmission Tuner was accepted";
            if (ItemMods.count(Sky.items().data(it), ItemMods.TUNER_KEY) != ItemMods.TUNER_MAX) return "tuned_transmission != 4";
            ItemMods.Plan m = ReforgesModule.MODS.plan(aote, Sky.items().data(it), Sky.items().rarity(it), merger);
            if (!m.ok()) return "merger refused: " + m.error();
            Sky.items().update(it, m.change());
            if (!ItemMods.flag(Sky.items().data(it), ItemMods.MERGER_KEY)) return "ethermerge not set";
        }
        ItemDef vac = null;
        for (ItemDef x : Sky.items().all()) if (x.category == ItemCategory.VACUUM && x.isUnique()) { vac = x; break; }
        ItemDef worm = Sky.items().def("BOOKWORM_BOOK"), alm = Sky.items().def("FARMING_FOR_DUMMIES");
        if (vac != null && worm != null) {
            ItemStack it = Sky.items().create(vac.id);
            double dmg = Sky.items().sheet(it).damage();
            ItemMods.Plan pl = ReforgesModule.MODS.plan(vac, Sky.items().data(it), Sky.items().rarity(it), worm);
            if (!pl.ok()) return "Bookworm's book refused on " + vac.id + ": " + pl.error();
            Sky.items().update(it, pl.change());
            if (Math.abs(Sky.items().sheet(it).damage() - dmg - ItemMods.BOOKWORM_DAMAGE) > 0.01) return "Bookworm's book did not add 10 vacuum damage";
            if (alm != null) {
                double ff = Sky.items().sheet(it).total(Stat.FARMING_FORTUNE);
                ItemMods.Plan pa = ReforgesModule.MODS.plan(vac, Sky.items().data(it), Sky.items().rarity(it), alm);
                if (!pa.ok()) return "almanac refused on a vacuum: " + pa.error();
                Sky.items().update(it, pa.change());
                if (Math.abs(Sky.items().sheet(it).total(Stat.FARMING_FORTUNE) - ff - 1) > 0.01) return "almanac on a vacuum did not add +1 Farming Fortune";
            }
        }
        return null;
    }

    /** Wave 5 reforge bonuses: Mantid/Chomp maths at the shipping numbers, other-stat rows present, owners flagged. */
    static String wave5Bonuses() {
        ReforgeData.Row mantid = d().rows.get("mantid"), chomp = d().rows.get("chomp"), spook = d().rows.get("greater_spook"),
                maj = d().rows.get("majestic"), blood = d().rows.get("blood_soaked");
        if (mantid == null || chomp == null || spook == null || maj == null || blood == null) return "a wave-5 reforge row is missing";
        double per = ReforgeData.bonusValue(mantid, Rarity.LEGENDARY, 0, -1), cap = ReforgeData.bonusValue(mantid, Rarity.LEGENDARY, 2, -1);
        if (per != 0.25 || cap != 5) return "Mantid should be +0.25 per pest up to 5, got " + per + " / " + cap;
        if (Bonuses.mantidBonus(4, per, cap) != 1.0 || Bonuses.mantidBonus(40, per, cap) != 5.0) return "Mantid stacking maths wrong";
        List<Long> st = new ArrayList<>(List.of(10L, 20L, 30L));
        if (Bonuses.activeStacks(st, 20L) != 1) return "Mantid expiry pruning wrong";
        if (Bonuses.chompPct(5, ReforgeData.bonusValue(chomp, Rarity.EPIC, 0, -1), ReforgeData.bonusValue(chomp, Rarity.EPIC, 1, -1)) != 5
                || Bonuses.chompPct(99, 1, 20) != 20) return "Chomp maths wrong";
        if (ReforgeData.otherStat(spook, Rarity.LEGENDARY, "fear") != 5) return "Greater Spook LEGENDARY should carry fear 5 for the weapons module";
        if (ReforgeData.statsFor(maj, Rarity.LEGENDARY).getOrDefault("HUNTING_FORTUNE", 0.0) <= 0) return "Majestic lost its Hunting Fortune row";
        Map<Stat, Double> mj = Sky.items().reforge("majestic").statsFor(Rarity.LEGENDARY);
        if (mj.getOrDefault(Stat.HUNTING_FORTUNE, 0.0) <= 0) return "core's Majestic reforge has no Hunting Fortune";
        if (!"enchanting".equals(ReforgeData.bonusState(blood))) return "Blood-Soaked should be marked as the enchanting module's";
        for (String id : List.of("mantid", "chomp", "spiritual", "majestic"))
            if (!d().rows.get(id).bonusImpl || !Bonuses.IMPLEMENTED.contains(id)) return id + " not flagged implemented";
        if (!Sky.requirements().known(Bonuses.SAFARI_PREFIX))
            Sky.log().warning("[reforges] Majestic does nothing yet: no module answers requirement '" + Bonuses.SAFARI_KEY
                    + "' (hunting, reforges request 16)");
        return null;
    }

    /** The six power scrolls exist, carry their reference numbers, and bind only to RIGHT CLICK ability items. */
    static String powerScrolls() {
        Map<String, double[]> want = Map.of("RUBY", new double[]{0, 0, 5, 1, 0}, "SAPPHIRE", new double[]{0, 0, 5, 0, 5},
                "AMBER", new double[]{30, 5, 0, 0, 0}, "AMETHYST", new double[]{10, 5, 0, 0, 0},
                "JASPER", new double[]{10, 5, 0, 0, 0}, "OPAL", new double[]{10, 5, 0, 0, 0});
        for (Map.Entry<String, double[]> e : want.entrySet()) {
            ItemDef sd = Sky.items().def(e.getKey() + "_POWER_SCROLL");
            if (sd == null) return e.getKey() + "_POWER_SCROLL is not registered";
            Bonuses.Scroll s = Bonuses.Scroll.of(sd.extra);
            if (s == null) return sd.id + " has no scroll data";
            double[] w = e.getValue();
            if (s.value() != w[0] || s.seconds() != w[1] || s.cooldown() != w[2] || s.healMissingPct() != w[3] || s.mana() != w[4])
                return sd.id + " numbers differ from the reference lore: " + s;
        }
        ItemDef ruby = Sky.items().def("RUBY_POWER_SCROLL");
        ItemDef withAbility = null, without = null;
        for (ItemDef x : Sky.items().all()) {
            if (!x.isUnique() || x.category == null || !x.category.isWeapon()) continue;
            if (withAbility == null && ItemMods.rightClickAbility(x)) withAbility = x;
            if (without == null && !ItemMods.rightClickAbility(x)) without = x;
        }
        if (withAbility != null) {
            ItemStack it = Sky.items().create(withAbility.id);
            ItemMods.Plan pl = ReforgesModule.MODS.plan(withAbility, Sky.items().data(it), Sky.items().rarity(it), ruby);
            if (!pl.ok()) return "Ruby scroll refused on " + withAbility.id + ": " + pl.error();
            Sky.items().update(it, pl.change());
            if (!"RUBY_POWER_SCROLL".equals(ItemMods.str(Sky.items().data(it), ItemMods.SCROLL_KEY))) return "power_ability_scroll not written";
            if (new BonusLore().lines(withAbility, Sky.items().data(it)).stream().noneMatch(l -> l.contains("Ruby Power Scroll")))
                return "scroll lore line missing";
        }
        if (without != null) {
            ItemStack it = Sky.items().create(without.id);
            if (ReforgesModule.MODS.plan(without, Sky.items().data(it), Sky.items().rarity(it), ruby).ok()) return "scroll accepted on " + without.id + " (no RIGHT CLICK ability)";
        }
        return null;
    }

    private static ReforgeData d() { return ReforgesModule.DATA; }

    static String dataComplete() {
        long bs = d().rows.values().stream().filter(r -> !r.isStone()).count();
        long st = d().rows.values().stream().filter(ReforgeData.Row::isStone).count();
        if (bs != 50) return "expected 50 blacksmith reforges (research 01 §13.2), got " + bs;
        if (st != 85) return "expected 85 stone reforges (research 01 §13.3), got " + st;
        for (ReforgeData.Row r : d().rows.values()) {
            if (r.name == null || r.name.isBlank()) return "reforge " + r.id + " has no name";
            if (r.stats.isEmpty() && r.bonus == null) return "reforge " + r.id + " has neither stats nor a bonus";
            if (r.bonus != null) for (ReforgeData.Bonus b : r.bonus.values())
                if (b.text == null || b.text.contains("?")) return "reforge " + r.id + " bonus text did not fill: " + b.text;
            if (r.types == null && r.itemIds.isEmpty() && r.materials.isEmpty()) return "reforge " + r.id + " fits nothing";
        }
        for (String id : Bonuses.IMPLEMENTED) if (!d().rows.containsKey(id)) return "implemented bonus " + id + " has no reforge row";
        if (d().gems.types.size() < 12) return "expected 12 gem types, got " + d().gems.types.size();
        if (d().stars.size() < 500) return "star ladders look short: " + d().stars.size();
        if (d().slots.size() < 450) return "gem layouts look short: " + d().slots.size();
        return null;
    }

    static String stonesExist() {
        List<String> missing = new ArrayList<>();
        for (ReforgeData.Row r : d().stones()) if (!Sky.items().exists(r.stone)) missing.add(r.stone);
        for (String id : List.of("HOT_POTATO_BOOK", "FUMING_POTATO_BOOK", "RECOMBOBULATOR_3000", "THE_ART_OF_WAR", "THE_ART_OF_PEACE",
                "FIRST_MASTER_STAR", "FIFTH_MASTER_STAR", "TALISMAN_ENRICHMENT_STRENGTH", "POLARVOID_BOOK", "BOOK_OF_STATS",
                "REVERSE_REFORGE_STONE_CORE", "SIL_EX", "RUBY_POWER_SCROLL", "SAPPHIRE_POWER_SCROLL", "AMBER_POWER_SCROLL",
                "AMETHYST_POWER_SCROLL", "JASPER_POWER_SCROLL", "OPAL_POWER_SCROLL"))
            if (!Sky.items().exists(id)) missing.add(id);
        for (String e : ReforgeData.ESSENCES) if (!Sky.items().exists("ESSENCE_" + e)) missing.add("ESSENCE_" + e);
        for (String id : d().shop.keySet()) if (!Sky.items().exists(id)) missing.add("shop:" + id);
        for (String e : d().enrichments.keySet()) if (!Sky.items().exists("TALISMAN_ENRICHMENT_" + e.toUpperCase())) missing.add("enrich:" + e);
        return missing.isEmpty() ? null : missing.size() + " items missing: " + missing.subList(0, Math.min(8, missing.size()));
    }

    static String registered() {
        for (ReforgeData.Row r : d().rows.values()) {
            ReforgeDef def = Sky.items().reforge(r.id);
            if (def == null) return "core does not know reforge " + r.id;
            if (r.stone != null && !r.stone.equals(def.stoneItemId)) return "reforge " + r.id + " registered with a different stone";
        }
        return null;
    }

    static String formulas() {
        ReforgeData.Row spicy = d().rows.get("spicy");
        if (spicy == null) return "no spicy";
        Map<Stat, Double> sp = Sky.items().reforge("spicy").statsFor(Rarity.LEGENDARY);
        if (sp.getOrDefault(Stat.CRIT_DAMAGE, 0.0) != 80 || sp.getOrDefault(Stat.STRENGTH, 0.0) != 10)
            return "Spicy LEGENDARY should be +10 Str +80 CD (research 01 §13.2), got " + sp;
        if (ReforgeData.blacksmithCost(Rarity.LEGENDARY) != 5000 || ReforgeData.blacksmithCost(Rarity.COMMON) != 250)
            return "blacksmith costs differ from research 01 §13.1";
        ReforgeData.Row fabled = d().byStone.get("DRAGON_CLAW");
        if (fabled == null || ReforgeData.stoneCost(fabled, Rarity.LEGENDARY) != 1_000_000) return "Dragon Claw LEGENDARY apply cost should be 1,000,000";
        if (d().gemValue("PERFECT_RUBY_GEM", Rarity.MYTHIC, "RUBY") != 30) return "Perfect Ruby on MYTHIC should be 30 Health (research 01 §17.2)";
        if (d().gemValue("FINE_JASPER_GEM", Rarity.EPIC, "COMBAT") != 5) return "Fine Jasper on EPIC should be 5 Strength";
        if (d().gemValue("ROUGH_RUBY_GEM", Rarity.DIVINE, "RUBY") != 7) return "Rough Ruby on DIVINE should fall back to the MYTHIC 7";
        if (d().removalFee("FLAWLESS_JADE_GEM") != 100_000) return "Flawless removal fee should be 100,000";
        ReforgeData.StarEntry hyp = d().stars.get("HYPERION");
        if (hyp == null || hyp.costs.size() != 5 || hyp.costs.get(4).get(0).amount != 1500 || !"WITHER".equals(hyp.costs.get(4).get(0).essence))
            return "Hyperion star 5 should cost 1,500 Wither essence (research 01 §16.1)";
        ReforgeData.StarEntry crim = d().stars.get("CRIMSON_CHESTPLATE");
        if (crim == null || crim.costs.size() != 10) return "Crimson chestplate should have 10 essence stars";
        ReforgeData.Row hyper = d().rows.get("hyper");
        if (ReforgeData.bonusValue(hyper, Rarity.MYTHIC, 0, -1) != 6) return "Hyper MYTHIC bonus should be +6 Speed";
        return null;
    }

    /** Put real modifiers on a real sword stack and read the sheet core renders from it. */
    static String liveSheet() {
        ItemDef sword = null;
        for (String cand : List.of("ASPECT_OF_THE_END", "ROGUE_SWORD", "IRON_SWORD", "DIAMOND_SWORD")) {
            ItemDef x = Sky.items().def(cand);
            if (x != null && x.category == ItemCategory.SWORD && x.isUnique()) { sword = x; break; }
        }
        if (sword == null) for (ItemDef x : Sky.items().all()) if (x.category == ItemCategory.SWORD && x.isUnique()) { sword = x; break; }
        if (sword == null) return null;   // no weapons registered: nothing to measure (weapons module absent)
        ItemStack it = Sky.items().create(sword.id);
        double baseStr = Sky.items().sheet(it).total(Stat.STRENGTH);
        double baseDmg = Sky.items().sheet(it).damage();
        Sky.items().update(it, x -> { x.reforge = "spicy"; x.potatoBooks = 10; x.extra.addProperty("art_of_war", 1); });
        ItemStatSheet sh = Sky.items().sheet(it);
        Rarity r = Sky.items().rarity(it);
        double rf = Sky.items().reforge("spicy").statsFor(r).getOrDefault(Stat.STRENGTH, 0.0);
        double want = baseStr + rf + 20 + ReforgeData.ART_OF_WAR_STRENGTH;
        if (Math.abs(sh.total(Stat.STRENGTH) - want) > 0.01)
            return sword.id + " strength " + sh.total(Stat.STRENGTH) + " != base " + baseStr + " + spicy " + rf + " + potato 20 + art 5";
        if (sh.part(Stat.STRENGTH, ItemStatSheet.Source.REFORGE) != rf) return "reforge part not shown in brackets";
        if (Math.abs(sh.damage() - (baseDmg + 20)) > 0.01) return "potato damage missing: " + sh.damage();
        Sky.items().update(it, x -> { x.reforge = "suspicious"; x.potatoBooks = 0; x.extra.remove("art_of_war"); });
        double sus = ReforgeData.statsFor(d().rows.get("suspicious"), r).getOrDefault("DAMAGE", 0.0);
        if (Math.abs(Sky.items().sheet(it).damage() - (baseDmg + sus)) > 0.01) return "Suspicious +" + sus + " weapon damage missing";
        return null;
    }

    /**
     * Wave 3: a Polarvoid Book on a real registered drill shows in core's sheet (+10 Mining Speed,
     * +5 Mining Fortune), the Book of Stats lore line renders, and the mining block table the
     * mining-island bonuses read is present.
     */
    static String wave3() {
        ItemDef drill = null;
        for (ItemDef x : Sky.items().all()) if (x.category == ItemCategory.DRILL && x.isUnique()) { drill = x; break; }
        if (drill != null) {
            ItemStack it = Sky.items().create(drill.id);
            double sp = Sky.items().sheet(it).total(Stat.MINING_SPEED), mf = Sky.items().sheet(it).total(Stat.MINING_FORTUNE);
            ItemDef book = Sky.items().def("POLARVOID_BOOK");
            ItemMods.Plan pl = ReforgesModule.MODS.plan(drill, Sky.items().data(it), Sky.items().rarity(it), book);
            if (!pl.ok()) return "Polarvoid refused on " + drill.id + ": " + pl.error();
            Sky.items().update(it, pl.change());
            double sp2 = Sky.items().sheet(it).total(Stat.MINING_SPEED), mf2 = Sky.items().sheet(it).total(Stat.MINING_FORTUNE);
            if (Math.abs(sp2 - sp - ItemMods.POLARVOID_SPEED) > 0.01 || Math.abs(mf2 - mf - ItemMods.POLARVOID_FORTUNE) > 0.01)
                return drill.id + " Polarvoid: speed " + sp + "->" + sp2 + ", fortune " + mf + "->" + mf2;
        }
        ItemDef sword = null;
        for (ItemDef x : Sky.items().all()) if (x.category == ItemCategory.SWORD && x.isUnique()) { sword = x; break; }
        if (sword != null) {
            ItemStack it = Sky.items().create(sword.id);
            Sky.items().update(it, x -> x.extra.addProperty("stats_book", 1234));
            List<String> lore = new BonusLore().lines(sword, Sky.items().data(it));
            if (lore.stream().noneMatch(l -> l.contains("Kills") && l.contains("1,234"))) return "Book of Stats line missing: " + lore;
        }
        if (MiningBlocks.breakingPower("MITHRIL_ORE").isEmpty()) return "data/mining/blocks.json unreadable: Mithraic/Scraped/Royal/Stellar do nothing";
        if (MiningBlocks.mithrilPowder("TITANIUM_ORE") <= 0) return "titanium has no mithril powder in the mining table";
        return null;
    }

    static String anchors() {
        Layout l = Sky.worlds().layout("hub");
        if (l == null) return Sky.worlds().island("hub") == null ? null : "hub layout not generated yet";
        List<String> miss = new ArrayList<>();
        for (String a : List.of("npc.blacksmith", "npc.reforge_anvil")) if (l.anchor(a) == null) miss.add(a);
        return miss.isEmpty() ? null : "hub is missing anchors " + miss;
    }

    static String menus() {
        Player any = Bukkit.getOnlinePlayers().stream().filter(Sky.profiles()::isReady).findFirst().orElse(null);
        List<Menu> built = new ArrayList<>();
        built.add(Menus.buildHub(any));
        built.add(Menus.buildBlacksmith(any));
        built.add(Menus.buildAnvil(any));
        built.add(Menus.buildGems(any));
        if (any != null) built.add(Menus.buildEssence(any));
        for (Menu m : built) {
            m.redraw();
            if (m.get(m.closeSlot()) == null) return m.title() + " has no Close button";
        }
        for (ReforgeData.Row r : d().rows.values()) Menus.guideIcon(r);
        return null;
    }

    /** Ids this module OWNS that its own cost tables reference must resolve; foreign ones are only counted. */
    static String ownCosts() {
        Set<String> badEssence = new TreeSet<>();
        Set<String> foreign = new TreeSet<>();
        for (ReforgeData.StarEntry e : d().stars.values()) for (List<ReforgeData.Cost> step : e.costs) for (ReforgeData.Cost c : step) {
            if (c.essence != null && !Arrays.asList(ReforgeData.ESSENCES).contains(c.essence)) badEssence.add(c.essence);
            if (c.item != null && !Sky.items().exists(c.item)) foreign.add(c.item);
        }
        for (List<ReforgeData.Slot> sl : d().slots.values()) for (ReforgeData.Slot s : sl) for (ReforgeData.Cost c : s.costs)
            if (c.item != null && !Sky.items().exists(c.item)) foreign.add(c.item);
        if (!badEssence.isEmpty()) return "star costs use essence types we do not store: " + badEssence;
        if (!foreign.isEmpty()) Sky.log().warning("[reforges] star/slot costs reference " + foreign.size() + " ids no module registered: " + foreign);
        // Fossil Essence is ours to store, but its reference source is mining's Fossil Excavator table (hand-off request 15)
        if (!Boolean.TRUE.equals(MiningBlocks.excavatorGives("ESSENCE_FOSSIL")))
            Sky.log().warning("[reforges] nothing gives ESSENCE_FOSSIL yet: data/mining/loot.json excavator.table has no ESSENCE_FOSSIL line"
                    + " (reforges request 15); the Fossil Researcher's trades cannot be paid");
        return null;
    }
}
