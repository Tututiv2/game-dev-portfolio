package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Live checks for {@code /sb selftest armor}. Each returns null on pass or what is wrong. */
final class ArmorSelfTests {

    private ArmorSelfTests() {}

    static void register() {
        Sky.commands().selfTest("armor.sets-registered", ArmorSelfTests::setsRegistered);
        Sky.commands().selfTest("armor.bonus-ids-exist", ArmorSelfTests::bonusIds);
        Sky.commands().selfTest("armor.research-set-totals", ArmorSelfTests::setTotals);
        Sky.commands().selfTest("armor.formulas", ArmorSelfTests::formulas);
        Sky.commands().selfTest("armor.recipe-ingredients-exist", ArmorSelfTests::recipeIngredients);
        Sky.commands().selfTest("armor.recipe-unlocks", ArmorSelfTests::recipeUnlocks);
        Sky.commands().selfTest("armor.items-render", ArmorSelfTests::itemsRender);
        Sky.commands().selfTest("armor.menus-build", ArmorSelfTests::menusBuild);
        Sky.commands().selfTest("armor.requirement-resolvers", ArmorSelfTests::requirementResolvers);
        // wave 5
        Sky.commands().selfTest("armor.upgrade-links", ArmorSelfTests::upgradeLinks);
        Sky.commands().selfTest("armor.shop-offers", ArmorSelfTests::shopOffers);
        Sky.commands().selfTest("armor.fear", ArmorSelfTests::fear);
        Sky.commands().selfTest("armor.cap-bonuses", ArmorSelfTests::capBonuses);
        Sky.commands().selfTest("armor.blaze-pet-hook", ArmorSelfTests::blazePet);
        // wave 5, second pass
        Sky.commands().selfTest("armor.magma-lord-tiers", ArmorSelfTests::magmaLordTiers);
        Sky.commands().selfTest("armor.magic-find-vs", ArmorSelfTests::magicFindVs);
        Sky.commands().selfTest("armor.peace-treaty", ArmorSelfTests::peaceTreaty);
        Sky.commands().selfTest("armor.bat-swarm", ArmorSelfTests::batSwarm);
        Sky.commands().selfTest("armor.nether-lord", ArmorSelfTests::netherLord);
        Sky.commands().selfTest("armor.great-spook-staff", ArmorSelfTests::greatSpookStaff);
    }

    /** Every piece in sets.json is a registered armour item, and multi-piece sets share their setId. */
    static String setsRegistered() {
        ArmorSets s = ArmorModule.sets();
        int sets = 0;
        List<String> bad = new ArrayList<>();
        for (ArmorSets.SetInfo x : s.all()) {
            if (x.isSet) sets++;
            for (String id : x.pieces) {
                ItemDef d = Sky.items().def(id);
                if (d == null) { bad.add(id + " missing"); continue; }
                if (d.category == null || !d.category.isArmor()) bad.add(id + " not armour (" + d.category + ")");
                if (x.isSet && !x.id.equals(d.setId)) bad.add(id + " setId " + d.setId + " != " + x.id);
            }
        }
        if (sets < 100) return "only " + sets + " multi-piece sets loaded (expected 122)";
        return bad.isEmpty() ? null : bad.size() + " problem(s): " + head(bad);
    }

    /**
     * Wave 3: every requirement an armour piece carries has a resolver. A key nobody resolves fails
     * closed, so the piece could never be worn for stats; core describes such a key as "&c" + key.
     */
    static String requirementResolvers() {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (ArmorSets.SetInfo x : ArmorModule.sets().all())
            for (String id : x.pieces) {
                ItemDef d = Sky.items().def(id);
                if (d != null && d.requirements != null) keys.addAll(d.requirements);
            }
        List<String> bad = new ArrayList<>();
        for (String k : keys) if (("&c" + k).equals(Sky.requirements().describe(k))) bad.add(k);
        if (keys.isEmpty()) return "no requirements found on armour pieces";
        return bad.isEmpty() ? null : bad.size() + " of " + keys.size() + " requirement keys have no resolver: " + head(bad);
    }

    /** Every set / item the bonus code names exists (a typo would silently disable a bonus). */
    static String bonusIds() {
        List<String> bad = new ArrayList<>();
        for (String id : SetIds.ALL_SETS) if (!ArmorModule.sets().has(id)) bad.add("set " + id);
        for (String id : SetIds.ALL_ITEMS) if (!Sky.items().exists(id)) bad.add("item " + id);
        for (String id : List.of("ARMOR_OF_MAGMA_CHESTPLATE", "ARMOR_OF_YOG_CHESTPLATE", "SUNFLOWER_HEAD")) if (!Sky.items().exists(id)) bad.add("item " + id);
        if (!bad.isEmpty()) return "unknown ids in bonus code: " + head(bad);
        List<String> soft = new ArrayList<>();
        for (String id : SetIds.FOREIGN_ITEMS) if (!Sky.items().exists(id)) soft.add(id);
        // foreign items (weapons / garden) are other modules' - report them in the pass line only
        if (!soft.isEmpty()) Sky.log().info("[armor] bonus code refers to items another module has not registered yet: " + soft);
        return null;
    }

    /** Full-set stat totals equal research 06 §3.2. */
    static String setTotals() {
        String r;
        if ((r = expect("superior_dragon", Map.of(Stat.SPEED, 12.0, Stat.DEFENSE, 600.0, Stat.HEALTH, 450.0, Stat.STRENGTH, 40.0,
                Stat.CRIT_DAMAGE, 40.0, Stat.CRIT_CHANCE, 8.0, Stat.INTELLIGENCE, 100.0))) != null) return r;
        if ((r = expect("young_dragon", Map.of(Stat.SPEED, 80.0, Stat.DEFENSE, 500.0, Stat.HEALTH, 350.0))) != null) return r;
        if ((r = expect("protector_dragon", Map.of(Stat.DEFENSE, 600.0, Stat.HEALTH, 350.0))) != null) return r;
        if ((r = expect("lapis_armor", Map.of(Stat.DEFENSE, 120.0, Stat.MINING_SPEED, 80.0, Stat.MINING_FORTUNE, 8.0))) != null) return r;
        if ((r = expect("hardened_diamond", Map.of(Stat.DEFENSE, 330.0))) != null) return r;
        if ((r = expect("golem_armor", Map.of(Stat.DEFENSE, 250.0, Stat.HEALTH, 205.0))) != null) return r;
        if ((r = expect("cropie", Map.of(Stat.FARMING_FORTUNE, 90.0, Stat.HEALTH, 550.0, Stat.DEFENSE, 120.0, Stat.SPEED, 15.0))) != null) return r;
        if ((r = expect("thunder", Map.of(Stat.HEALTH, 850.0, Stat.DEFENSE, 750.0, Stat.SEA_CREATURE_CHANCE, 16.0, Stat.STRENGTH, 100.0))) != null) return r;
        return null;
    }

    static String expect(String setId, Map<Stat, Double> want) {
        ArmorSets.SetInfo s = ArmorModule.sets().get(setId);
        if (s == null) return "set " + setId + " missing";
        Map<Stat, Double> got = new EnumMap<>(Stat.class);
        for (String id : s.pieces) {
            ItemDef d = Sky.items().def(id);
            if (d != null && d.stats != null) d.stats.forEach((k, v) -> got.merge(k, v, Double::sum));
        }
        for (Map.Entry<Stat, Double> e : want.entrySet()) {
            double g = got.getOrDefault(e.getKey(), 0.0);
            if (Math.abs(g - e.getValue()) > 0.001) return setId + " " + e.getKey() + " = " + g + ", research says " + e.getValue();
        }
        return null;
    }

    /** The bonus formulas give research 06's numbers for documented inputs. */
    static String formulas() {
        List<String> bad = new ArrayList<>();
        check(bad, "protector 600 def, 50% missing", SetMath.protectorBonus(600, 500, 1000, 1), 300);
        check(bad, "emerald 1,050,000 (cap)", SetMath.emeraldBonus(1_050_000, 3000, 350), 350);
        check(bad, "emerald 300,000", SetMath.emeraldBonus(300_000, 3000, 350), 100);
        check(bad, "blaze aura 3% of 10,000", SetMath.blazeAuraDamage(10_000, 3, 0, 500), 300);
        check(bad, "blaze aura capped at 500", SetMath.blazeAuraDamage(1_000_000, 3, 0, 500), 500);
        check(bad, "frozen blaze 300 + 3% of 5,000", SetMath.blazeAuraDamage(5_000, 3, 300, 500), 450);
        check(bad, "blaze cap with 10,000 rods", SetMath.blazeAuraCap(10_000, 500, 100), 700);
        check(bad, "crystal at light 15", SetMath.crystalFactor(15), 2.0);
        check(bad, "crystal at light 0", SetMath.crystalFactor(0), 0.0);
        check(bad, "goblin 150 int", SetMath.goblinMiningSpeed(150), 10);
        check(bad, "vindicate 600 defense", SetMath.vindicateSpeed(600), 12);
        check(bad, "rekindle 4/4, not burning (research +200%)", SetMath.rekindleBonus(4, 50, 0, 1.2, 50), 200);
        check(bad, "rekindle 4/4, burning 60s (cap +50%)", SetMath.rekindleBonus(4, 50, 60, 1.2, 50), 250);
        check(bad, "abyssal breath 20 levels", SetMath.maxAirTicks(20, 15), 6300);
        check(bad, "crown of avarice coins", SetMath.crownCoins(false, true, false, 2, 5, 2), 5);
        check(bad, "cropie tiered 4 pieces", SetMath.tieredThirds(4, BonusStats.farmingFortuneFull(SetIds.CROPIE)), 45);
        check(bad, "cropie tiered 2 pieces", SetMath.tieredThirds(2, BonusStats.farmingFortuneFull(SetIds.CROPIE)), 15);
        check(bad, "fermento full ff = 205", 130 + SetMath.tieredThirds(4, BonusStats.farmingFortuneFull(SetIds.FERMENTO)), 205);
        check(bad, "tux cheap 1,000 hp -> 75", 1000 * SetMath.tuxedoHealthFactor(1000, 75), 75);
        check(bad, "berserk full", SetMath.berserkFactor(4), 0.5);
        check(bad, "magma kills 2,500 (cap 200)", SetMath.perKills(2500, 10, 200), 200);
        check(bad, "static charges after 75s", SetMath.staticCharges(75, 30, 2), 2);
        check(bad, "mithril cap 10,000 hit / 1,000 hp", SetMath.capHit(10_000, 1000, 0.4), 400);
        return bad.isEmpty() ? null : head(bad);
    }

    private static void check(List<String> bad, String what, double got, double want) {
        if (Math.abs(got - want) > 1e-6) bad.add(what + ": got " + got + ", want " + want);
    }

    /** Every ingredient of every armor recipe is a registered item. */
    static String recipeIngredients() {
        List<String> missing = new ArrayList<>();
        int n = 0;
        for (Recipe r : Sky.recipes().all()) {
            if (r.id == null || !r.id.startsWith("armor:")) continue;
            n++;
            List<String> cells = new ArrayList<>(r.inputs == null ? List.of() : r.inputs);
            if (r.grid != null) for (String c : r.grid) if (c != null && !c.isEmpty()) cells.add(c);
            for (String c : cells) {
                if (Sky.items().exists(c)) continue;
                int i = c.lastIndexOf(':');
                String id = i > 0 ? c.substring(0, i) : c;
                if (!Sky.items().exists(id) && !missing.contains(id)) missing.add(id);
            }
        }
        if (n < 250) return "only " + n + " armor recipes registered (expected 321)";
        return missing.isEmpty() ? null : missing.size() + " ingredient id(s) not registered by any module: " + head(missing);
    }

    /** Every unlock key on an armor recipe is a collection key whose collection item exists. */
    static String recipeUnlocks() {
        List<String> bad = new ArrayList<>();
        for (Recipe r : Sky.recipes().all()) {
            if (r.id == null || !r.id.startsWith("armor:") || r.unlock == null) continue;
            if (!r.unlock.startsWith("collection:")) { bad.add(r.id + " " + r.unlock); continue; }
            String rest = r.unlock.substring("collection:".length());
            String item = rest.substring(0, rest.lastIndexOf(':'));
            if (!Sky.items().exists(item) && !bad.contains(item)) bad.add(item);
        }
        return bad.isEmpty() ? null : "unlock collections without an item: " + head(bad);
    }

    /** Every armor piece renders (create + lore) without throwing. */
    static String itemsRender() {
        int n = 0;
        for (ArmorSets.SetInfo x : ArmorModule.sets().all()) for (String id : x.pieces) {
            try {
                var st = Sky.items().create(id);
                if (st.getItemMeta() == null || st.getItemMeta().lore() == null) return id + " rendered without lore";
                n++;
            } catch (RuntimeException e) {
                return id + " failed to render: " + e;
            }
        }
        return n < 500 ? "only " + n + " pieces rendered" : null;
    }

    /** The browser builds every category list and every set icon without a player. */
    static String menusBuild() {
        try {
            for (String cat : ArmorSets.CATEGORIES) ArmorMenus.category(null, cat, 0);
            for (ArmorSets.SetInfo x : ArmorModule.sets().all()) { ArmorMenus.setIcon(x); ArmorMenus.upgradeLines(x); }
            ArmorMenus.recipe(null, "LAPIS_ARMOR_HELMET", () -> null);
        } catch (RuntimeException e) {
            return "menu build threw: " + e;
        }
        return null;
    }

    // =========================================================================== wave 5

    /**
     * The upgrade graph: Angler upgrades into Backwater (the bayou scrapper's trade), the farming ladder is one
     * line, every link names a known set, links are symmetric, and every line passes through its own set.
     */
    static String upgradeLinks() {
        ArmorSets s = ArmorModule.sets();
        List<String> bad = new ArrayList<>();
        ArmorSets.SetInfo angler = s.get(SetIds.ANGLER), back = s.get(SetIds.BACKWATER);
        if (angler == null || !angler.upgradeInto.contains(SetIds.BACKWATER)) bad.add("angler does not upgrade into backwater");
        if (back == null || !back.upgradeFrom.contains(SetIds.ANGLER)) bad.add("backwater not made from angler");
        if (!s.upgradeLine(SetIds.FERMENTO).containsAll(List.of(SetIds.CROPIE, SetIds.SQUASH, SetIds.FERMENTO, SetIds.HELIANTHUS)))
            bad.add("farming ladder line " + s.upgradeLine(SetIds.FERMENTO));
        for (ArmorSets.SetInfo x : s.all()) {
            for (String up : x.upgradeInto) {
                ArmorSets.SetInfo y = s.get(up);
                if (y == null) bad.add(x.id + " -> unknown " + up);
                else if (!y.upgradeFrom.contains(x.id)) bad.add(x.id + " -> " + up + " not mirrored");
            }
            for (String dn : x.upgradeFrom) if (!s.has(dn)) bad.add(x.id + " <- unknown " + dn);
            if (!s.upgradeLine(x.id).contains(x.id)) bad.add(x.id + " missing from its own line");
        }
        return bad.isEmpty() ? null : head(bad);
    }

    /** Every merchant offer is for a piece of its own set and pays in registered items (Backwater: Angler piece + Rusty Coin). */
    static String shopOffers() {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (ArmorSets.SetInfo x : ArmorModule.sets().all()) for (ArmorSets.Shop sh : x.shops) {
            n++;
            if (!x.pieces.contains(sh.piece)) bad.add(x.id + " sells foreign " + sh.piece);
            if (sh.npc == null || sh.npc.isBlank()) bad.add(sh.piece + " has no merchant name");
            for (String cell : sh.items) {
                String id = cell.contains(":") ? cell.substring(0, cell.lastIndexOf(':')) : cell;
                if (!Sky.items().exists(id)) bad.add(sh.piece + " costs unregistered " + id);
            }
        }
        ArmorSets.SetInfo back = ArmorModule.sets().get(SetIds.BACKWATER);
        if (back == null || back.shops.size() != 4) bad.add("backwater offers: " + (back == null ? "none" : back.shops.size()));
        else if (!ArmorMenus.costText(back.shops.get(0)).contains("Rusty Coin") && Sky.items().exists("RUSTY_COIN"))
            bad.add("backwater cost text: " + ArmorMenus.costText(back.shops.get(0)));
        if (n == 0) return "no shop offers loaded";
        return bad.isEmpty() ? null : head(bad);
    }

    /** Great Spook pieces carry 5 Fear each (research 06), the reforge adds 4/5, and the reader sees them. */
    static String fear() {
        List<String> bad = new ArrayList<>();
        double set = 0;
        for (String id : List.of("GREAT_SPOOK_HELMET", "GREAT_SPOOK_CHESTPLATE", "GREAT_SPOOK_LEGGINGS", "GREAT_SPOOK_BOOTS"))
            set += Fear.other(Sky.items().def(id), "FEAR");
        if (set != 20) bad.add("Great Spook set Fear " + set + " != 20");
        if (SetMath.reforgeFear("greater_spook", 3) != 4 || SetMath.reforgeFear("greater_spook", 5) != 5 || SetMath.reforgeFear("clean", 4) != 0)
            bad.add("reforge fear table");
        if (Fear.other(Sky.items().def("DIVER_HELMET"), "PRESSURE_RESISTANCE") != 15) bad.add("Diver helmet Pressure Resistance");
        if (BonusListener.otherStatLore(Sky.items().def("GREAT_SPOOK_HELMET")).isEmpty()) bad.add("no Fear lore line");
        return bad.isEmpty() ? null : head(bad);
    }

    /** Young Blood +100 Speed cap and Newton's Demise +50 Attack Speed cap, against core's live dials. */
    static String capBonuses() {
        List<String> bad = new ArrayList<>();
        double cap = ArmorDials.CORE_SPEED_CAP.get();
        if (SetMath.speedCapScalar(cap + 100, cap, 100) <= 0) bad.add("speed above core cap gives nothing");
        if (SetMath.speedCapScalar(cap, cap, 100) != 0) bad.add("speed at the cap is boosted");
        if (Math.abs(SetMath.speedCapScalar(9999, 400, 100) - 0.25) > 1e-9) bad.add("500/400 - 1 != 0.25");
        if (SetMath.hitWindow(10, 150, 100, 2) != 5 || SetMath.hitWindow(10, 150, 150, 2) != 4) bad.add("hit windows at 150 AS");
        if (!SetMath.newtonMayLand(4, 4, 5, 4) || SetMath.newtonMayLand(5, 5, 5, 4) || SetMath.newtonMayLand(3, 3, 5, 4))
            bad.add("newtonMayLand cases");
        if (!ArmorModule.sets().has(SetIds.THERMODYNAMIC) || !ArmorModule.sets().has(SetIds.YOUNG)) bad.add("sets missing");
        return bad.isEmpty() ? null : head(bad);
    }

    /** The Blaze pet perk key armor reads exists in the pets data, and the boost is a plain percentage. */
    static String blazePet() {
        if (Math.abs(SetMath.petBoosted(100, 40) - 140) > 1e-9 || SetMath.petBoosted(100, 0) != 100) return "petBoosted";
        try (var in = Sky.plugin().getResource("data/pets/pets.json")) {
            if (in == null) return null; // pets module not shipped: the hook reads 0
            String all = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return all.contains("\"blaze_bling_armor\"") ? null : "pets data has no blaze_bling_armor perk";
        } catch (java.io.IOException e) {
            return "cannot read pets data: " + e;
        }
    }

    // =========================================================================== wave 5, second pass

    /** Lord's Blessing needs 5 pieces with the Magma Lord Necklace (4 when nobody registers the necklace); Fireproof 2. */
    static String magmaLordTiers() {
        List<String> bad = new ArrayList<>();
        if (SetMath.magmaLordPieces(4, true) != 5 || SetMath.magmaLordPieces(1, true) != 2 || SetMath.magmaLordPieces(0, true) != 0)
            bad.add("piece count with necklace");
        if (SetMath.magmaLordNeed(5, true) != 5 || SetMath.magmaLordNeed(5, false) != 4) bad.add("need");
        int need = SetMath.magmaLordNeed(ArmorDials.MAGMA_LORD_PIECES.get(), Sky.items().exists(SetIds.MAGMA_LORD_NECKLACE));
        if (need < 2) bad.add("dial makes Lord's Blessing cheaper than Fireproof: " + need);
        if (!lore("MAGMA_LORD_BOOTS").contains("Necklace")) bad.add("lore does not name the necklace");
        if (!Sky.items().exists(SetIds.MAGMA_LORD_NECKLACE)) Sky.log().info("[armor] MAGMA_LORD_GAUNTLET not registered: Lord's Blessing falls back to 4 pieces");
        return bad.isEmpty() ? null : head(bad);
    }

    /** The per-target Magic Find hook: magmatic / mythological only, numbers from the dials, 0 for anything else. */
    static String magicFindVs() {
        List<String> bad = new ArrayList<>();
        if (SetMath.magicFindVs(true, false, true, 0, false, 20, 2.5, 10) != 20) bad.add("Lord's Blessing vs magmatic");
        if (SetMath.magicFindVs(true, false, false, 0, false, 20, 2.5, 10) != 0) bad.add("magmatic without the blessing");
        if (SetMath.magicFindVs(false, true, true, 4, true, 20, 2.5, 10) != 20) bad.add("mythic: 4 x 2.5 + 10");
        if (SetMath.magicFindVs(false, false, true, 4, true, 20, 2.5, 10) != 0) bad.add("ordinary mob gets MF");
        com.skyclone.api.mob.MobDef plain = new com.skyclone.api.mob.MobDef();
        plain.id = "armor_selftest_plain";
        if (BonusCombat.magicFindVs(null, plain) != 0) bad.add("null player");
        com.skyclone.api.mob.MobDef lava = new com.skyclone.api.mob.MobDef();
        lava.id = "armor_selftest_magma_slug";
        lava.flags.add("sea_creature");
        if (!ArmorUtil.magmatic(lava)) bad.add("a sea creature named magma is not magmatic");
        return bad.isEmpty() ? null : head(bad);
    }

    /** Peace Treaty: 2+ pieces of ONE Hunter set; every Hunter piece says so. */
    static String peaceTreaty() {
        List<String> bad = new ArrayList<>();
        if (!SetMath.peaceTreaty(2, 0, 2, 0, 0) || SetMath.peaceTreaty(2, 1, 1, 0, 0) || SetMath.peaceTreaty(0, 4)) bad.add("threshold");
        for (String set : List.of(SetIds.BRONZE, SetIds.SILVER, SetIds.GOLD_HUNTER, SetIds.DIAMOND_HUNTER)) {
            ArmorSets.SetInfo x = ArmorModule.sets().get(set);
            if (x == null) { bad.add(set + " missing"); continue; }
            for (String piece : x.pieces) if (!lore(piece).contains("Peace Treaty")) bad.add(piece + " lore");
        }
        return bad.isEmpty() ? null : head(bad);
    }

    /** Bat Swarm: homing step lands on the target, respawn timing, and the masks' damage dials/lore agree. */
    static String batSwarm() {
        List<String> bad = new ArrayList<>();
        double[] at = SetMath.batStep(new double[]{0, 0, 0}, new double[]{10, 0, 0}, 0.7);
        if (Math.abs(at[0] - 0.7) > 1e-9) bad.add("step " + at[0]);
        at = SetMath.batStep(new double[]{9.8, 0, 0}, new double[]{10, 0, 0}, 0.7);
        if (at[0] != 10) bad.add("does not land");
        if (!SetMath.batReady(0, 5, 10) || SetMath.batReady(1000, 5000, 10) || !SetMath.batReady(1000, 11000, 10)) bad.add("respawn");
        if (!lore("WITCH_MASK").contains("4,000") || !lore("VAMPIRE_WITCH_MASK").contains("3,000")) bad.add("mask lore numbers");
        if (ArmorDials.BAT_SWARM_WITCH_DAMAGE.get() <= ArmorDials.BAT_SWARM_VAMPIRE_DAMAGE.get()) bad.add("witch should out-hit vampire witch");
        // playtest 065: the pop is bat-sized, never the TNT sprite that filled a third of the screen at 1-2 blocks
        for (org.bukkit.Particle b : BatSwarm.BURST)
            for (org.bukkit.Particle huge : BatSwarm.SCREEN_FILLING) if (b == huge) bad.add("burst uses " + huge);
        if (BatSwarm.BURST.length == 0 || BatSwarm.BURST_SPREAD > 0.5) bad.add("burst not bat-sized");
        return bad.isEmpty() ? null : head(bad);
    }

    /** Nether Lord: the disc, the crash-recovery cell format, and the zones it never runs in. */
    static String netherLord() {
        List<String> bad = new ArrayList<>();
        if (SetMath.discOffsets(2).size() != 13 || SetMath.discOffsets(0).size() != 1 || SetMath.discOffsets(1).size() != 5) bad.add("disc sizes");
        int[] c = NetherLord.parse("12,-3,40");
        if (c == null || c[0] != 12 || c[1] != -3 || c[2] != 40 || NetherLord.parse("x,1") != null) bad.add("cell parse");
        if (!NetherLord.DISABLED_ISLANDS.contains("private_island") || !NetherLord.DISABLED_ISLANDS.contains("garden")) bad.add("disabled islands");
        ItemDef chest = Sky.items().def("EMBER_CHESTPLATE");
        if (chest == null || chest.setBonusLore == null || !String.join(" ", chest.setBonusLore).contains("obsidian"))
            bad.add("Ember lore does not mention the obsidian");
        return bad.isEmpty() ? null : head(bad);
    }

    /** The Great Spook Staff is registered (extras.json), carries Fear 10, and both its abilities are ours. */
    static String greatSpookStaff() {
        ItemDef d = Sky.items().def(SetIds.GREAT_SPOOK_STAFF);
        if (d == null) return "GREAT_SPOOK_STAFF not registered";
        List<String> bad = new ArrayList<>();
        if (Fear.other(d, "FEAR") != 10) bad.add("Fear " + Fear.other(d, "FEAR"));
        java.util.Set<String> ab = new java.util.HashSet<>();
        if (d.abilities != null) for (var a : d.abilities) ab.add(a.id);
        if (!ab.contains("armor_spooker") || !ab.contains("armor_spook")) bad.add("abilities " + ab);
        if (BonusListener.otherStatLore(d).isEmpty()) bad.add("no Fear lore line on the staff");
        ItemDef helm = Sky.items().def("GREAT_SPOOK_HELMET");
        if (helm == null || !"Fearsome".equals(helm.setBonusName) || !lore("GREAT_SPOOK_HELMET").contains("Spooky"))
            bad.add("Great Spook needs both Fearsome and Spooky");
        return bad.isEmpty() ? null : head(bad);
    }

    private static String lore(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null || d.lore == null ? "" : String.join(" ", d.lore);
    }

    private static String head(List<String> l) {
        return String.join(", ", l.subList(0, Math.min(8, l.size()))) + (l.size() > 8 ? " (+" + (l.size() - 8) + " more)" : "");
    }
}
