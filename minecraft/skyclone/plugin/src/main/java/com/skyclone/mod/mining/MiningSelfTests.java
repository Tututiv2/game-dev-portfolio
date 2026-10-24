package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live checks: /sb selftest mining. Every one returns null when it passes. */
final class MiningSelfTests {

    private MiningSelfTests() {}

    static void register(MiningModule m) {
        Sky.commands().selfTest("mining.formulas", MiningSelfTests::formulas);
        Sky.commands().selfTest("mining.hotm-tree", () -> tree(m));
        Sky.commands().selfTest("mining.block-table", () -> blocks(m));
        Sky.commands().selfTest("mining.drops-exist", () -> drops(m));
        Sky.commands().selfTest("mining.commissions", () -> commissions(m));
        Sky.commands().selfTest("mining.forge-recipes", () -> forge(m));
        Sky.commands().selfTest("mining.npc-anchors", MiningSelfTests::anchors);
        Sky.commands().selfTest("mining.mineshaft-layout", MiningSelfTests::mineshaft);
        Sky.commands().selfTest("mining.shops", MiningSelfTests::shops);
        Sky.commands().selfTest("mining.commissions-fillable", () -> fillable(m));
        Sky.commands().selfTest("mining.island-xp", () -> islandXp(m));
        Sky.commands().selfTest("mining.fossil-essence", () -> fossilEssence(m));
        Sky.commands().selfTest("mining.reforge-finds", MiningSelfTests::reforgeFinds);
        Sky.commands().selfTest("mining.commission-hud", () -> commissionHud(m));
        // wave 5
        Sky.commands().selfTest("mining.island-speed", () -> islandSpeed(m));
        Sky.commands().selfTest("mining.gauntlet-spill", MiningSelfTests::gauntletSpill);
        Sky.commands().selfTest("mining.structure-quests", () -> structureQuests(m));
        Sky.commands().selfTest("mining.pet-hooks", MiningSelfTests::petHooks);
        Sky.commands().selfTest("mining.chisel-gems", MiningSelfTests::chiselGems);
        Sky.commands().selfTest("mining.ore-exp", MiningSelfTests::oreExp);
        Sky.commands().selfTest("mining.fossil-muncher", () -> muncher(m));
        // wave 5, second pass
        Sky.commands().selfTest("mining.ability-data", () -> abilityData(m));
        Sky.commands().selfTest("mining.excavator-pool", () -> excavatorPool(m));
        Sky.commands().selfTest("mining.corpse-tag", () -> corpseTag(m));
        // wave-5 regression: the Goblin Raid is fought in one district by a mostly-Lv5 roster
        Sky.commands().selfTest("mining.raid-roster", MiningSelfTests::raidRoster);
        Sky.commands().selfTest("mining.raid-zones", MiningSelfTests::raidZones);
        Sky.commands().selfTest("mining.raid-waves", () -> raidWaves(m));
        // real-game audit 2026-10-06 (lane 3): forge slots, Nucleus bundle, worms, chests, Fiesta, Cold, corpses
        Sky.commands().selfTest("mining.audit-2026-10-06", () -> audit(m));
    }

    /** The audit rows as the LIVE dials and data see them (a dial an admin changed on purpose reports here too). */
    static String audit(MiningModule m) {
        if (m.forge.slotsAt(1, 0) != 0 || m.forge.slotsAt(2, 0) != 2 || m.forge.slotsAt(5, 2) != 5 || m.forge.slotsAt(7, 0) != 7)
            return "forge slots should be 0/2/5/7 at HOTM 1/2/5(Core II)/7, got " + m.forge.slotsAt(1, 0) + "/" + m.forge.slotsAt(2, 0)
                    + "/" + m.forge.slotsAt(5, 2) + "/" + m.forge.slotsAt(7, 0);
        MiningLoot.Table n = m.loot.f.nucleus;
        if (n == null) return "no nucleus table";
        if (n.hotmXp != 800 || n.always.size() != 5 || n.rolls[0] != 14 || n.rolls[1] != 19) return "nucleus bundle: 800 HOTM XP, 5 Fine gems, 14-19 rolls";
        double total = 0, alloy = 0;
        for (MiningLoot.Line l : n.table) {
            if ("PRECURSOR_APPARATUS".equals(l.item)) return "the Nucleus drops the Precursor Apparatus a run consumes";
            total += l.weight;
            if ("DIVAN_ALLOY".equals(l.item)) alloy += l.weight;
        }
        double pa = MiningMath.bundleChance(alloy, total, n.rolls[0], n.rolls[1]);
        if (pa < 0.0008 || pa > 0.0010) return "Varnas Alloy per Nucleus run " + pa + " (reference 0.09%)";
        for (MiningLoot.Line l : n.always) if (!MiningUtil.exists(l.item)) return "missing Nucleus gem " + l.item;
        if (m.dials.wormCooldown.get() != 30) return "mining.worm-cooldown-seconds is " + m.dials.wormCooldown.get() + " (reference 30)";
        if (Math.abs(m.dials.wormChance.get() - 1.0 / 600) > 1e-9) return "mining.worm-chance is " + m.dials.wormChance.get() + " (reference 1/600)";
        if (Math.abs(m.dials.chestChance.get() - 0.002) > 1e-9) return "mining.chest-chance is " + m.dials.chestChance.get() + " (reference 1/500)";
        if (Math.abs(MiningMath.fiestaChance(9, 0, m.dials.fiestaDropDivisor.get()) - 0.0009) > 1e-9) return "Jubilee drop at BP 9 should be 0.09%";
        if (m.dials.fiestaDropMult.get() != 1.0) return "mining.fiesta-drop-mult is " + m.dials.fiestaDropMult.get() + " (the rework has no doubled drops)";
        if (Math.abs(MiningMath.coldSpeedMultiplier(50, m.dials.coldSpeedPerPoint.get()) - 0.75) > 1e-9) return "50 Cold should cost 25% Mining Speed";
        MiningLoot.Table lapis = m.loot.f.corpse.get("lapis");
        if (lapis == null || lapis.hotmXp != 100 || lapis.powder == null || lapis.powder[0] != 500) return "Lapis corpse: 500 powder + 100 HOTM XP";
        return null;
    }

    /** Every raider is registered, in the Goblin Raiders family, the bulk are Lv5, and nothing hits like the old Warlord. */
    static String raidRoster() {
        double sum = 0;
        for (int i = 0; i < MiningMath.RAID_IDS.length; i++) {
            String id = MiningMath.RAID_IDS[i];
            MobDef d = Sky.mobs().def(id);
            if (d == null) return id + " is not registered";
            if (!"goblin_raiders".equals(d.familyId())) return id + " family " + d.familyId() + " (bestiary family is goblin_raiders)";
            if (!d.id.startsWith("mining_raid")) return id + " does not start with mining_raid (raid score + commission key)";
            if (d.level > 150) return id + " is Lv" + d.level + " (reference raiders top out at Lv150)";
            boolean heavy = MiningMath.raidHeavy(id);
            if (!heavy && d.damage > 250) return id + " deals " + d.damage + ": a light raider must be fightable while mining";
            sum += MiningMath.RAID_WEIGHTS[i];
        }
        for (String w : List.of("mining_raid_weakling", "mining_raid_weakling_bow")) {
            MobDef d = Sky.mobs().def(w);
            if (d.level != 5 || d.damage > 60) return w + " is Lv" + d.level + " / " + d.damage + " damage (reference raid weaklings are Lv5)";
        }
        if (Math.abs(sum - 1) > 1e-9) return "raid weights sum to " + sum;
        if (MiningMath.RAID_WEIGHTS[0] + MiningMath.RAID_WEIGHTS[1] < 0.6) return "weaklings are under 60% of a raid";
        for (String old : List.of("mining_raid_warlord", "mining_raid_goblin"))
            if (Sky.mobs().def(old) != null) return old + " still registered (the Lv90 / Lv40 raiders that killed miners in seconds)";
        return null;
    }

    /** At least one raid district exists on the Dwarven Mines and none of them is a SAFE town. */
    static String raidZones() {
        Layout l = Sky.worlds().layout("dwarven_mines");
        if (l == null) return null;   // island not generated on this server: nothing to raid
        int found = 0;
        for (String id : MiningMath.RAID_ZONES) {
            if (l.area(id) == null) continue;
            found++;
            for (Layout.Zone z : l.zones())
                if (z.id().equals(id) && z.flags() != null && (z.flags().contains(Layout.Zone.SAFE) || z.flags().contains(Layout.Zone.NO_MOBS)))
                    return id + " is a SAFE/NO_MOBS zone: raiders could never spawn there";
        }
        return found == 0 ? "no raid district (" + MiningMath.RAID_ZONES + ") has an area on dwarven_mines" : null;
    }

    /** Waves respect the cap, the heavy limit holds, and the raid never spawns anything for a player outside the district. */
    static String raidWaves(MiningModule m) {
        if (MiningMath.raidWave(0, 20, 6) != 6 || MiningMath.raidWave(17, 20, 6) != 3 || MiningMath.raidWave(25, 20, 6) != 0)
            return "raidWave ignores the cap";
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++)
            if (MiningMath.raidHeavy(MiningMath.raidPick(rnd.nextDouble(), false))) return "a heavy raider was picked with heavies disallowed";
        if (!MiningMath.raidHeavy(MiningMath.raidPick(0.999, true))) return "the top roll never reaches a heavy";
        if (m.dials.raidHeavyMax.get() < 0 || m.dials.raidCap.get() < 0 || m.dials.raidWave.get() < 0) return "a raid dial is negative";
        return null;
    }

    /** Every pickaxe ability reads its value / duration / cooldown from hotm.json, and the menus print those numbers. */
    static String abilityData(MiningModule m) {
        int n = 0;
        for (HotmTree.Perk pk : m.hotm.tree.perks) {
            if (!pk.ability) continue;
            n++;
            for (String w : List.of("value", "duration", "cooldown"))
                for (int lvl = 1; lvl <= 3; lvl++)
                    if (Double.isNaN(pk.ability(w, lvl))) return pk.id + " has no " + w + " at level " + lvl + " in hotm.json";
            if (m.abilities.describe(pk.id, 1).isEmpty()) return pk.id + " has no description";
        }
        if (n != 6) return n + " abilities in the tree (want 6)";
        if (m.abilities.num("mining_speed_boost", "value", 1) != 200 || m.abilities.num("mining_speed_boost", "duration", 3) != 20)
            return "Mining Speed Boost is not 200% / 20s at level 3";
        if (m.abilities.baseCooldown("pickobulus", 3) != 40) return "Pickblast level 3 cooldown " + m.abilities.baseCooldown("pickobulus", 3);
        if (!String.join(" ", m.abilities.describe("maniac_miner", 2)).contains("+10")) return "Maniac Miner II lore does not say +10";
        return null;
    }

    /** The excavator pool: Paleontologist book, the Stonefin curio, 1/500,000 Fossil Dye; a roll never yields a missing item. */
    static String excavatorPool(MiningModule m) {
        MiningLoot.Excavator ex = m.loot.f.excavator;
        if (ex == null) return "no excavator table";
        if (Math.abs(ex.dyeChance - 1.0 / 500_000) > 1e-12 || !"DYE_FOSSIL".equals(ex.dye)) return "dye roll is " + ex.dye + " @ " + ex.dyeChance;
        for (String id : List.of("DYE_FOSSIL", "FOSSIL_THE_FISH")) if (!MiningUtil.exists(id)) return id + " not registered";
        boolean book = false, fish = false;
        for (MiningLoot.Line l : ex.table) { book |= l.item.startsWith("ENCHANTMENT_PALEONTOLOGIST"); fish |= "FOSSIL_THE_FISH".equals(l.item); }
        if (!book || !fish) return "pool lacks " + (!book ? "the Paleontologist book" : "the Stonefin curio");
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 5000; i++) {
            MiningLoot.Line l = m.excavator.excavatorRoll(ex, r);
            if (l != null && !Excavator.givable(l.item)) return "rolled a missing item " + l.item;
        }
        if (!MiningUtil.exists("ENCHANTMENT_PALEONTOLOGIST_1")) return "ENCHANTMENT_PALEONTOLOGIST_1 not registered (enchanting registers book ids) - the excavator skips that line";
        return null;
    }

    /** Frozen corpses carry skyclone:frozen_corpse (accessories' Organ Donor); the Muncher body exists iff its anchor does. */
    static String corpseTag(MiningModule m) {
        if (!"skyclone:frozen_corpse".equals(Tunnels.corpseKey().toString())) return "corpse key is " + Tunnels.corpseKey();
        boolean anchor = Sky.worlds().anchor("glacite_tunnels", "npc.fossil_muncher") != null;
        if (anchor) m.registerMuncherBody();
        if (anchor != m.muncherBodyRegistered()) return "Fossil Muncher body " + (anchor ? "missing although" : "registered although no") + " anchor npc.fossil_muncher";
        return null;
    }

    /** Fossil Muncher: research's daily 25,000 Rimestone Powder + 100 Fossil Essence, and it has fossils to eat. */
    static String muncher(MiningModule m) {
        if (m.dials.muncherPowder.get() <= 0 || m.dials.muncherEssence.get() < 0) return "muncher rewards disabled";
        if (!MiningUtil.exists("ESSENCE_FOSSIL")) return "ESSENCE_FOSSIL missing (the reforges essence pouch registers it)";
        int fossils = 0;
        for (String id : Excavator.DONATABLE) if (MiningUtil.exists(id)) fossils++;
        return fossils == 0 ? "no fossil item exists for it to eat" : null;
    }

    /**
     * Private-island Mining Speed: the block-break factor makes vanilla take exactly the formula's ticks (stone with a
     * drill, hand-speed base), stone/cobble have a strength row there and island-specific meanings (gray wool =
     * mithril, glass = gems) and dirt do not.
     */
    static String islandSpeed(MiningModule m) {
        BlockTable.Def stone = m.islandMining.speedDef(Material.STONE);
        if (stone == null) return "STONE has no strength row on the private island";
        if (m.islandMining.speedDef(Material.COBBLESTONE) == null) return "COBBLESTONE has no strength row";
        if (m.islandMining.speedDef(Material.DIRT) != null) return "DIRT would break at Mining Speed";
        if (m.islandMining.speedDef(Material.GRAY_WOOL) != null) return "GRAY_WOOL (mithril only in the mines) would break at Mining Speed";
        if (m.islandMining.speedDef(Material.RED_STAINED_GLASS) != null) return "glass (gems only in the caverns) would break at Mining Speed";
        double hand = 1.0 / 1.5 / 100.0;   // vanilla per-tick progress: stone, no correct tool (a drill is a prismarine shard)
        for (double speed : new double[]{50, 450, 1200, 3000}) {
            int t = MiningMath.ticksToBreak(stone.strength, speed, stone.kind.sixtyRule());
            if (t == Integer.MAX_VALUE) continue;
            int got = MiningMath.vanillaTicks(hand * MiningMath.breakSpeedScale(t, hand));
            if (got != t) return "speed " + speed + ": formula " + t + " ticks, vanilla would take " + got;
        }
        if (MiningMath.breakSpeedScale(Integer.MAX_VALUE, hand) != 1.0) return "an unbreakable block must keep vanilla speed";
        // the End quarry's End Stone (strength 30, 30x rule): a 450-speed drill takes 2 -> the 4-tick floor; over 900 instamines
        BlockTable.Def es = IslandSpeed.END_STONE;
        if (MiningMath.ticksToBreak(es.strength, 450, es.kind.sixtyRule()) != 4) return "End Stone at 450 speed is not 4 ticks";
        if (MiningMath.ticksToBreak(es.strength, 901, es.kind.sixtyRule()) != 0) return "End Stone at 901 speed does not instamine";
        if (m.dials.endQuarrySpeed.get() && Sky.worlds().area(IslandSpeed.END, IslandSpeed.QUARRY) == null && Sky.worlds().anchor(IslandSpeed.END, "spawn") != null)
            return "the End is built but has no area " + IslandSpeed.QUARRY + " (combatzones) - quarry speed does nothing";
        return null;
    }

    /** Gemstone Gauntlet: slotted gems spill their rough form, one per quality step; every rough gem exists. */
    static String gauntletSpill() {
        if (!MiningUtil.exists("GEMSTONE_GAUNTLET")) return "GEMSTONE_GAUNTLET missing";
        Map<String, Integer> got = Finds.gauntletSpill(List.of("PERFECT_RUBY_GEM", "ROUGH_JADE_GEM", "FINE_RUBY_GEM", "NOT_A_GEM"), 1.0);
        if (!Integer.valueOf(8).equals(got.get("ROUGH_RUBY_GEM")) || !Integer.valueOf(1).equals(got.get("ROUGH_JADE_GEM")) || got.size() != 2)
            return "spill of Perfect+Fine Ruby and Rough Jade = " + got + " (want ROUGH_RUBY_GEM 8, ROUGH_JADE_GEM 1)";
        for (String g : List.of("RUBY", "AMETHYST", "JADE", "AMBER", "SAPPHIRE", "TOPAZ", "JASPER", "OPAL", "ONYX", "AQUAMARINE", "CITRINE", "PERIDOT"))
            if (!MiningUtil.exists("ROUGH_" + g + "_GEM")) return "ROUGH_" + g + "_GEM missing";
        return null;
    }

    /**
     * Structure quests (mining side): each Nucleus crystal maps to a structure the Wishing Compass can point at (in
     * the built caverns layout when there is one), every crystal item exists, and a treasure chest's zone loot is
     * likelier in its own zone than elsewhere.
     */
    static String structureQuests(MiningModule m) {
        Map<String, String> want = Map.of("structure.jungle_temple", "AMETHYST", "structure.mines_of_varnas", "JADE",
                "structure.lost_relic_city", "SAPPHIRE", "structure.goblin_burrow", "AMBER", "structure.goblin_queen_den", "AMBER",
                "structure.emberdeep", "TOPAZ");
        for (Map.Entry<String, String> e : want.entrySet())
            if (!e.getValue().equals(MiningMath.structureGem(e.getKey()))) return e.getKey() + " maps to " + MiningMath.structureGem(e.getKey());
        if (MiningMath.structureGem("structure.gang_hideout") != null || MiningMath.structureGem("structure.fairy_grotto_1") != null)
            return "a structure that holds no crystal maps to one";
        for (String g : Hollows.NUCLEUS_GEMS) if (!MiningUtil.exists(g + "_CRYSTAL")) return g + "_CRYSTAL missing";
        Layout l = Sky.worlds().layout("crystal_hollows");
        if (l != null) {
            Set<String> have = new HashSet<>();
            for (String a : l.anchors().keySet()) {
                String g = a.startsWith("structure.") ? MiningMath.structureGem(a) : null;
                if (g != null) have.add(g);
            }
            for (String g : Hollows.NUCLEUS_GEMS)
                if (!have.contains(g)) return "no structure.* anchor in the caverns holds the " + g + " crystal (the compass cannot point at it)";
        }
        MiningLoot.Table chest = m.loot.f.treasure_chest;
        if (chest == null) return "no treasure_chest table";
        int zoned = 0;
        for (MiningLoot.Line line : chest.table) {
            if (line.zoneGem == null) continue;
            zoned++;
            if (!Hollows.NUCLEUS_GEMS.contains(line.zoneGem)) return line.item + " has unknown zoneGem " + line.zoneGem;
            if (!MiningUtil.exists(line.item)) return line.item + " missing";
            double in = MiningMath.zoneWeight(line.weight, line.zoneGem, line.zoneGem, m.dials.chestZoneBoost.get(), m.dials.chestOffZone.get());
            double out = MiningMath.zoneWeight(line.weight, line.zoneGem, "RUBY", m.dials.chestZoneBoost.get(), m.dials.chestOffZone.get());
            if (in < out) return line.item + " is likelier outside its zone (" + in + " < " + out + ")";
        }
        if (zoned < 6) return "only " + zoned + " treasure chest lines belong to a zone";
        return null;
    }

    /** Every pet ability key mining reads exists in the pets data, so a rename on that side shows up here. */
    static String petHooks() {
        List<String> keys = List.of("bal_dispersion", "glacite_golem_frozen_perfection", "glacite_golem_powder_powered", "mammoth_corpse_crusher",
                "mammoth_wooly_coat", "mole_nucleic_veins", "scatha_burrowing", "scatha_bejeweled_eyes", "mithril_golem_the_smell_of_powder",
                "bingo_climber", "penguin_thick_blubber");
        java.io.InputStream in = Sky.plugin().getResource("data/pets/pets.json");
        if (in == null) return null;   // no pets data: MiningUtil.pet answers 0 and nothing breaks
        String text;
        try (in) { text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { return "pets.json unreadable: " + e; }
        List<String> missing = new ArrayList<>();
        for (String k : keys) if (!text.contains("\"" + k + "\"")) missing.add(k);
        return missing.isEmpty() ? null : "pet ability keys not in data/pets/pets.json: " + missing;
    }

    /** Chisel gem slots: Aquamarine 30% at Rough and 100% at Perfect (research 2.14), Peridot scales essence lines. */
    static String chiselGems() {
        if (Math.abs(MiningMath.chiselAquamarine("ROUGH_AQUAMARINE_GEM") - 0.30) > 1e-9) return "Rough Aquamarine is not 30%";
        if (Math.abs(MiningMath.chiselAquamarine("PERFECT_AQUAMARINE_GEM") - 1.0) > 1e-9) return "Perfect Aquamarine is not 100%";
        Map<String, String> best = Excavator.chiselGems(List.of("ROUGH_ONYX_GEM", "FINE_ONYX_GEM", "FLAWED_PERIDOT_GEM"));
        if (!"FINE_ONYX_GEM".equals(best.get("ONYX")) || !"FLAWED_PERIDOT_GEM".equals(best.get("PERIDOT"))) return "best chisel gems = " + best;
        MiningLoot.Line ess = new MiningLoot.Line();
        ess.item = "ESSENCE_FOSSIL";
        ess.min = 10;
        ess.max = 40;
        MiningLoot.Line up = Excavator.withEssence(ess, 25);
        if (up.min != 12 || up.max != 50) return "Peridot +25% on 10-40 essence = " + up.min + "-" + up.max;
        MiningLoot.Line other = new MiningLoot.Line();
        other.item = "ENCHANTED_GLACITE";
        if (Excavator.withEssence(other, 25) != other) return "Peridot changed a non-essence line";
        return null;
    }

    /** Ores give their vanilla experience (coal 0-2, diamond 3-7, ...); blocks and iron give none. */
    static String oreExp() {
        int[] c = MiningMath.oreExp("COAL_ORE"), d = MiningMath.oreExp("DEEPSLATE_DIAMOND_ORE");
        int[] b = MiningMath.oreExp("DIAMOND_BLOCK"), i = MiningMath.oreExp("IRON_ORE");
        if (c[0] != 0 || c[1] != 2) return "coal ore exp " + c[0] + "-" + c[1];
        if (d[0] != 3 || d[1] != 7) return "deepslate diamond ore exp " + d[0] + "-" + d[1];
        if (b[1] != 0 || i[1] != 0) return "a block / iron ore gives exp";
        return null;
    }

    /**
     * Commission progress is visible without opening /commissions (wave-4 playtest #13): the sidebar line formats,
     * and the tab widget sits early enough that the 40-line tab footer cannot crowd it out (it did at 300).
     */
    static String commissionHud(MiningModule m) {
        MiningData.Commission a = new MiningData.Commission("x", 4);
        a.progress = 4;
        MiningData.Commission b = new MiningData.Commission("y", 10);
        b.progress = 3;
        String line = MiningMath.commissionLine(List.of(a, b));
        if (!line.equals("&9Commissions: &aDONE&7, &e30%")) return "sidebar line reads '" + line + "'";
        int pr = m.dials.tabPriority.get();
        if (pr > 150) return "mining.tab-priority is " + pr + ": past ~150 the other widgets fill the tab footer first and Commissions is dropped";
        return null;
    }

    /** The Fossil Excavator is a real source of Fossil Essence (research 2.14: 10-40 per roll). */
    static String fossilEssence(MiningModule m) {
        MiningLoot.Excavator ex = m.loot.f.excavator;
        if (ex == null || ex.table.isEmpty()) return "no excavator table";
        MiningLoot.Line line = null;
        for (MiningLoot.Line l : ex.table) if ("ESSENCE_FOSSIL".equals(l.item)) line = l;
        if (line == null) return "the excavator never gives ESSENCE_FOSSIL";
        if (line.min != 10 || line.max != 40) return "ESSENCE_FOSSIL rolls " + line.min + "-" + line.max + ", research 2.14 says 10-40";
        if (line.weight <= 0) return "ESSENCE_FOSSIL has weight " + line.weight;
        if (!MiningUtil.exists("ESSENCE_FOSSIL")) return "ESSENCE_FOSSIL is not registered (reforges owns it)";
        if (!MiningUtil.exists("SUSPICIOUS_SCRAP")) return "SUSPICIOUS_SCRAP missing";
        return null;
    }

    /** Ambered / Blazing values read from the reforges data, and the fold-in maths (no double pay). */
    static String reforgeFinds() {
        if (ReforgeFinds.table().isEmpty()) return "no ambered/blazing rows read from data/reforges/reforges.json";
        double amb = ReforgeFinds.value(ReforgeFinds.AMBERED, com.skyclone.api.item.Rarity.COMMON, 0, -1);
        double ambL = ReforgeFinds.value(ReforgeFinds.AMBERED, com.skyclone.api.item.Rarity.LEGENDARY, 0, -1);
        if (amb != 10 || ambL != 30) return "Ambered goblin % reads " + amb + " / " + ambL + ", research 01 says 10 (C) / 30 (L)";
        double step = ReforgeFinds.value(ReforgeFinds.BLAZING, com.skyclone.api.item.Rarity.RARE, 0, -1);
        double per = ReforgeFinds.value(ReforgeFinds.BLAZING, com.skyclone.api.item.Rarity.RARE, 1, -1);
        if (step != 1 || Math.abs(per - 0.05) > 1e-9) return "Blazing reads " + per + "% per " + step + " Heat, research 01 says 0.05% per 1";
        // fold-in: shared share + mining's share == the whole bonus
        double base = 0.0004, mult = 2.5, pct = 30;
        double whole = base * mult * 1.3;
        double split = MiningMath.findChance(base, mult, pct, MiningMath.reforgesExtraRoll(base, pct)) + MiningMath.reforgesExtraRoll(base, pct);
        if (Math.abs(split - whole) > 1e-12) return "Ambered fold-in pays " + split + ", expected " + whole;
        if (MiningMath.findChance(base, mult, 0, 0) != base * mult) return "no-bonus chance changed";
        return null;
    }

    /** Private-island mining: the table rows it borrows pay the reference XP, and non-mining blocks pay none. */
    static String islandXp(MiningModule m) {
        Object[][] want = {{"COBBLESTONE", 1.0}, {"STONE", 1.0}, {"COAL_ORE", 5.0}, {"IRON_ORE", 5.0}, {"GOLD_ORE", 6.0},
                {"DIAMOND_ORE", 10.0}, {"EMERALD_ORE", 9.0}, {"OBSIDIAN", 20.0}};
        for (Object[] w : want) {
            org.bukkit.Material mat = org.bukkit.Material.valueOf((String) w[0]);
            BlockTable.Def d = m.islandMining.def(mat);
            if (d == null) return w[0] + " pays no Mining XP on the island";
            if (Math.abs(d.xp - (Double) w[1]) > 1e-9) return w[0] + " pays " + d.xp + " XP, expected " + w[1];
        }
        for (String none : new String[]{"OAK_LOG", "DIRT", "GRASS_BLOCK", "SAND", "WHEAT", "DIAMOND_BLOCK", "COAL_BLOCK"})
            if (m.islandMining.def(org.bukkit.Material.valueOf(none)) != null) return none + " must not pay Mining XP";
        if (m.islandMining.def(org.bukkit.Material.GRAY_WOOL) != null) return "GRAY_WOOL (mithril in the mines) must not pay on the island";
        return null;
    }

    /** Every Pickaxe Smith / Hollows Merchant line: result and every price item exist. */
    static String shops() {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (Recipe r : Sky.recipes().byType(Recipe.Type.NPC_SHOP)) {
            if (r.id == null || !(r.id.startsWith("mining_smith:") || r.id.startsWith("mining_hollows:"))) continue;
            n++;
            if (!MiningUtil.exists(r.result)) bad.add(r.id + " -> " + r.result);
            for (String c : r.inputs) {
                String[] cc = MiningUtil.cell(c);
                if (cc != null && !MiningUtil.exists(cc[0])) bad.add(r.id + " needs " + cc[0]);
            }
        }
        if (n == 0) return "no mining shop lines registered";
        return bad.isEmpty() ? null : "shop lines with missing items: " + bad;
    }

    /**
     * Every commission island can fill its slots with commissions this server can actually finish
     * (Commissions.possible: the mob, item or block exists). Lists the impossible ones it hides.
     */
    static String fillable(MiningModule m) {
        List<String> problems = new ArrayList<>();
        for (String island : Commissions.ISLANDS) {
            int ok = 0;
            List<String> hidden = new ArrayList<>();
            for (Commissions.Def d : m.comms.f.commissions) {
                if (!island.equals(d.island) || d.event != null) continue;
                if (m.comms.possible(d)) ok++; else hidden.add(d.id);
            }
            if (ok < 4) problems.add(island + " has only " + ok + " finishable commissions (hidden: " + hidden + ")");
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** Research 07 numbers through the shipping formulas. */
    static String formulas() {
        if (MiningMath.ticksToBreak(500, 500, true) != 30) return "mithril 500 @ 500 speed should be 30 ticks";
        if (MiningMath.ticksToBreak(6000, 500, true) != 360) return "glacite @ 500 should be 360 ticks";
        if (MiningMath.ticksToBreak(2000, 8000, true) != 8) return "titanium @ 8000 should be 8 ticks";
        if (MiningMath.ticksToBreak(50, 1000, false) != 4) return "hard stone @ 1000 should hit the 4-tick floor";
        if (MiningMath.ticksToBreak(15, 451, false) != 0) return "stone should instamine at 451";
        if (MiningMath.ticksToBreak(30, 1800, true) == 0) return "ores need MORE than 60x strength to instamine";
        if (MiningMath.fortuneDrops(1, 250, 0.4) != 4 || MiningMath.fortuneDrops(1, 250, 0.6) != 3) return "250 fortune = 3 + 50% of a 4th";
        if (MiningMath.perkCost(2, 3.0) != 27 || MiningMath.perkCost(10, 4.0) != 14641) return "perk cost formula";
        if (MiningMath.mineshaftExitPowder(2, 3, 40) != 1000 + 1000 + 750 + 400) return "mineshaft exit powder";
        if (Math.abs(MiningMath.fiestaChance(9, 0) - 0.0009) > 1e-9) return "fiesta chance at BP 9 should be 0.09% (BP/10,000)";
        return null;
    }

    static String tree(MiningModule m) {
        HotmTree t = m.hotm.tree;
        if (t.perks.size() != 46) return "expected 46 perks, got " + t.perks.size();
        if (t.maxTier() != 10) return "expected 10 tiers";
        if (t.xpForTier(10) != 1_247_000) return "HOTM 10 should need 1,247,000 XP (research 2.7.1), got " + t.xpForTier(10);
        if (t.tier(12_000) != 3) return "12,000 XP should be tier 3";
        if (t.tokensFor(10, 10) != 23) return "max tokens should be 23, got " + t.tokensFor(10, 10);
        HotmTree.Perk ms = t.perk("mining_speed");
        if (ms == null || ms.value(50) != 1000 || ms.costToNext(1) != 27) return "mining_speed table wrong";
        HotmTree.Perk ti = t.perk("titanium_insanium");
        if (ti == null || Math.abs(ti.value(50) - 7.0) > 1e-6) return "Titanium Fever at 50 should be 7%";
        // every perk reachable from the root through neighbours
        Set<String> seen = new HashSet<>();
        List<HotmTree.Perk> q = new ArrayList<>();
        for (HotmTree.Perk p : t.perks) if (p.tier == 1) { q.add(p); seen.add(p.id); }
        while (!q.isEmpty()) {
            HotmTree.Perk p = q.remove(0);
            for (HotmTree.Perk n : t.neighbours(p)) if (seen.add(n.id)) q.add(n);
        }
        if (seen.size() != t.perks.size()) return "unreachable perks: " + (t.perks.size() - seen.size());
        for (String a : PickaxeAbilities.ALL) if (t.perk(a) == null || !t.perk(a).ability) return "ability perk missing: " + a;
        return null;
    }

    static String blocks(MiningModule m) {
        for (String isl : List.of("hub", "gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels", "mineshaft"))
            if (!m.blocks.islands().contains(isl)) return "no mineable blocks for " + isl;
        for (BlockTable.Def d : m.blocks.all()) {
            for (String mat : d.materials) if (Material.matchMaterial(mat) == null) return d.id + ": unknown material " + mat;
            if (d.strength <= 0) return d.id + ": strength";
        }
        if (m.blocks.lookup("glacite_tunnels", "COBBLESTONE") == null || !"tungsten".equals(m.blocks.lookup("glacite_tunnels", "COBBLESTONE").id))
            return "cobblestone should be Tungsten in the frozen tunnels";
        if (!"hard_stone".equals(m.blocks.lookup("crystal_hollows", "GRAY_WOOL").id)) return "gray wool should be Hard Stone in the crystal caverns";
        if (!"mithril_gray".equals(m.blocks.lookup("dwarven_mines", "GRAY_WOOL").id)) return "gray wool should be Mithril in the Dwarven Mines";
        return null;
    }

    static String drops(MiningModule m) {
        List<String> missing = new ArrayList<>();
        for (BlockTable.Def d : m.blocks.all()) if (!MiningUtil.exists(d.drop)) missing.add("block:" + d.drop);
        List<MiningLoot.Line> lines = new ArrayList<>();
        MiningLoot.File f = m.loot.f;
        if (f.treasure_chest != null) lines.addAll(f.treasure_chest.table);
        if (f.nucleus != null) lines.addAll(f.nucleus.table);
        for (MiningLoot.Table t : f.corpse.values()) { lines.addAll(t.table); if (t.key != null) lines.add(line(t.key)); }
        lines.addAll(f.raffle);
        if (f.excavator != null) { lines.addAll(f.excavator.table); for (String s : f.excavator.fossils) lines.add(line(s)); }
        for (MiningLoot.Line l : lines) {
            String id = l.item;
            if (id.startsWith("ZONE_") || id.endsWith("_POWDER") || "COINS".equals(id)) continue;
            if (!MiningUtil.exists(id)) missing.add("loot:" + id);
        }
        for (String g : Hollows.NUCLEUS_GEMS) if (!MiningUtil.exists(g + "_CRYSTAL")) missing.add(g + "_CRYSTAL");
        for (MobDef md : Sky.mobs().all()) {
            if (md.id == null || !md.id.startsWith("mining_")) continue;
            for (DropDef dd : md.drops) if (!MiningUtil.exists(dd.item) && !dd.item.contains(";")) missing.add("mob:" + dd.item);
        }
        for (String fuel : Drills.FUELS.keySet()) if (!MiningUtil.exists(fuel) && fuel.startsWith("VOLTA")) missing.add("fuel:" + fuel);
        return missing.isEmpty() ? null : "missing items: " + missing;
    }

    private static MiningLoot.Line line(String id) { MiningLoot.Line l = new MiningLoot.Line(); l.item = id; return l; }

    static String commissions(MiningModule m) {
        Set<String> kinds = Set.of("MINE", "COLLECT", "KILL", "HIT", "EVENT", "RAFFLE", "POWDER", "CHEST", "CRYSTAL", "SCRAP", "MINESHAFT", "CORPSE");
        for (Commissions.Def d : m.comms.f.commissions) {
            if (!kinds.contains(d.kind)) return d.id + ": unknown kind " + d.kind;
            if (!m.comms.f.rewards.containsKey(d.island)) return d.id + ": no rewards for " + d.island;
            if ("MINE".equals(d.kind)) for (String k : d.keys()) if (m.blocks.byId(k) == null) return d.id + ": unknown block " + k;
            if ("COLLECT".equals(d.kind) || "CRYSTAL".equals(d.kind)) for (String k : d.keys()) if (!MiningUtil.exists(k)) return d.id + ": unknown item " + k;
        }
        if (m.comms.f.milestones.size() != 6) return "expected 6 milestones";
        for (Commissions.Milestone ms : m.comms.f.milestones)
            if (ms.reward != null && ms.reward.equals(ms.reward.toUpperCase()) && !MiningUtil.exists(ms.reward)) return "milestone reward missing: " + ms.reward;
        return null;
    }

    static String forge(MiningModule m) {
        List<Recipe> all = Sky.recipes().byType(Recipe.Type.FORGE);
        if (all.isEmpty()) return "no FORGE recipes registered (registration runs one tick after boot)";
        for (Recipe r : m.forge.ownRecipes()) {
            if (!r.result.startsWith("PERFECT_") || !r.result.endsWith("_GEM")) continue;
            if (Sky.recipes().producing(r.result).stream().noneMatch(x -> x.type == Recipe.Type.FORGE)) return "no forge recipe makes " + r.result;
        }
        for (String core : List.of("REFINED_MITHRIL", "REFINED_TITANIUM", "MITHRIL_DRILL_1", "TITANIUM_DRILL_4", "DIVAN_DRILL", "GEMSTONE_DRILL_1"))
            if (Sky.recipes().producing(core).stream().noneMatch(x -> x.type == Recipe.Type.FORGE))
                return "no forge recipe makes " + core + " (" + m.forge.summary() + ")";
        // every recipe in our data whose items exist is in the Forge menu, as OURS (HOTM gate kept)
        List<Recipe> menu = m.forge.recipes();
        for (Recipe r : m.forge.ownRecipes()) {
            if (m.forge.missing.stream().anyMatch(x -> x.startsWith(r.result + " "))) continue;
            if (menu.stream().noneMatch(x -> x == r)) return "the Forge menu does not offer our " + r.result + " recipe (" + m.forge.summary() + ")";
        }
        return null;
    }

    static String anchors() {
        List<String> missing = new ArrayList<>();
        String[][] need = {{"dwarven_mines", "npc.mine_king"}, {"dwarven_mines", "npc.commissions"}, {"dwarven_mines", "npc.forge"},
                {"dwarven_mines", "npc.hotm"}, {"crystal_hollows", "npc.hollows_merchant"},
                // wave 5: the village regulars now have bodies
                {"dwarven_mines", "npc.fetcher"}, {"dwarven_mines", "npc.riddlewright"}, {"dwarven_mines", "npc.drill_mechanic"}};
        for (String[] n : need) {
            Layout l = Sky.worlds().layout(n[0]);
            if (l == null) { missing.add(n[0] + " (no layout yet)"); continue; }
            if (l.anchor(n[1]) == null) missing.add(n[0] + ":" + n[1]);
        }
        Layout hub = Sky.worlds().layout("hub");
        if (hub != null && hub.area("coal_mine") == null) missing.add("hub:area coal_mine");
        return missing.isEmpty() ? null : "missing: " + missing;
    }

    static String mineshaft() {
        Layout l = Sky.worlds().layout("mineshaft");
        if (l == null) return "mineshaft template has no layout";
        if (l.anchor("spawn") == null || l.area("exit") == null) return "mineshaft needs spawn + exit";
        int veins = 0;
        for (Map.Entry<String, ?> e : l.areas().entrySet()) if (e.getKey().startsWith("vein.")) veins++;
        if (veins < 10) return "only " + veins + " vein pockets";
        for (int i = 1; i <= 4; i++) if (l.anchor("corpse." + i) == null) return "missing corpse." + i;
        return null;
    }
}
