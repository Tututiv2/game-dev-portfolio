package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wave 5 live self-tests: mini-boss spoils, the damage ledger, X/Y/Z + Synthesizers, the attribute hooks. */
final class Wave5Tests {

    private Wave5Tests() {}

    static void register() {
        Sky.commands().selfTest("crimson.boss-loot-table", Wave5Tests::lootTable);
        Sky.commands().selfTest("crimson.faction-spoils-rules", Wave5Tests::spoilsRules);
        Sky.commands().selfTest("crimson.boss-damage-ledger", Wave5Tests::ledger);
        Sky.commands().selfTest("crimson.synthesizer", Wave5Tests::synthesizer);
        Sky.commands().selfTest("crimson.attribute-hooks", Wave5Tests::attributeHooks);
    }

    /** Every spoils line names a registered item; both factions have a table; champions are the factions' rival bosses. */
    private static String lootTable() {
        CrimsonTables.BossLoot bl = BossLoot.table();
        for (String f : CrimsonRules.FACTIONS) {
            List<CrimsonTables.LootLine> own = bl.faction.get(f);
            if (own == null || own.isEmpty()) return "no faction spoils for " + f;
            String rival = Factions.info(f) == null ? null : Factions.info(f).rival_boss;
            if (rival == null || Sky.mobs().def(rival) == null) return f + " has no rival boss def (" + rival + ")";
            if (!bl.champion.containsKey(rival)) return "no champion spoils for " + rival + " (the " + f + "' rival)";
        }
        for (String boss : bl.champion.keySet()) {
            MobDef d = Sky.mobs().def(boss);
            if (d == null || !d.has("boss")) return "champion spoils for " + boss + ", which is not a mini-boss";
        }
        StringBuilder missing = new StringBuilder();
        java.util.List<CrimsonTables.LootLine> all = new java.util.ArrayList<>(bl.quest);
        bl.faction.values().forEach(all::addAll);
        bl.champion.values().forEach(all::addAll);
        for (CrimsonTables.LootLine l : all) {
            if (l.item == null || l.min < 1 || l.max < l.min || !(l.chance > 0) || l.chance > 1) return "bad spoils line " + l.item;
            if (!Sky.items().exists(l.item)) missing.append(l.item).append(' ');
        }
        if (missing.length() > 0) return "spoils items not registered: " + missing.toString().trim();
        if (!(bl.min_damage_percent >= 0 && bl.min_damage_percent <= 50)) return "min_damage_percent " + bl.min_damage_percent;
        return null;
    }

    /** The rules the game runs: rival champion the right way round, spoils per faction, the share threshold. */
    private static String spoilsRules() {
        CrimsonTables.Factions f = CrimsonModule.tables.factions;
        if (!CrimsonRules.rivalChampion(f.factions, CrimsonRules.MAGE, "crimson_barbarian_duke")) return "a mage killing the Duke is not a rival-champion kill";
        if (CrimsonRules.rivalChampion(f.factions, CrimsonRules.BARBARIAN, "crimson_barbarian_duke")) return "a barbarian killing his own Duke counts as a rival kill";
        CrimsonRules.Spoils m = CrimsonRules.spoils(f, CrimsonRules.MAGE, "crimson_barbarian_duke", true);
        if (!m.champion() || !m.quest()) return "mage vs Duke with the quest open: champion=" + m.champion() + " quest=" + m.quest();
        int expect = f.boss_loot.faction.get(CrimsonRules.MAGE).size() + f.boss_loot.champion.get("crimson_barbarian_duke").size() + f.boss_loot.quest.size();
        if (m.lines().size() != expect) return "mage vs Duke: " + m.lines().size() + " lines, want " + expect;
        if (!CrimsonRules.spoils(f, null, "crimson_ashfang", true).lines().isEmpty()) return "an unpledged player got faction spoils";
        CrimsonRules.Spoils b = CrimsonRules.spoils(f, CrimsonRules.BARBARIAN, "crimson_ashfang", false);
        if (b.champion() || b.lines().stream().anyMatch(l -> "ENCHANTED_MYCELIUM".equals(l.item))) return "a barbarian got mage spoils from the Cindermaw";
        if (CrimsonRules.sharesKill(0.5, 100, 1, false)) return "0.5% of the health shares a 1% kill";
        if (!CrimsonRules.sharesKill(1, 100, 1, false)) return "exactly 1% does not share";
        if (!CrimsonRules.sharesKill(0, 100, 1, true)) return "the killer does not share";
        if (CrimsonRules.sharesKill(Double.NaN, 100, 1, false)) return "NaN damage shares";
        return null;
    }

    /** Booking, a take-over transfer and the ranking, on a made-up fight id that no real boss can have. */
    private static String ledger() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        try {
            BossLoot.book(a, p1, 100, 1000);
            BossLoot.book(a, p2, 300, 1000);
            BossLoot.book(b, p1, 250, 1000);
            BossLoot.transfer(a, b);
            if (!BossLoot.ledger(a).isEmpty()) return "the old copy kept its ledger after a take-over";
            Map<UUID, Double> l = BossLoot.ledger(b);
            if (Math.abs(l.getOrDefault(p1, 0.0) - 350) > 1e-6 || Math.abs(l.getOrDefault(p2, 0.0) - 300) > 1e-6) return "merged ledger " + l;
            List<Map.Entry<UUID, Double>> r = CrimsonRules.ranking(l);
            if (!r.get(0).getKey().equals(p1)) return "ranking does not put the top damage first";
            return null;
        } finally {
            BossLoot.forget(a);
            BossLoot.forget(b);
        }
    }

    /** X/Y/Z exist and drop; a Synthesizer's fed points land on its stat sheet and never pass the cap. */
    private static String synthesizer() {
        for (String x : new String[]{"X", "Y", "Z"}) if (!Sky.items().exists(x)) return x + " is not registered";
        boolean x = false, y = false, z = false;
        for (MobDef d : Sky.mobs().all()) {
            if (d.id == null || !d.id.startsWith("crimson_") || d.drops == null) continue;
            for (DropDef dr : d.drops) { x |= "X".equals(dr.item); y |= "Y".equals(dr.item); z |= "Z".equals(dr.item); }
        }
        if (!x || !y || !z) return "no crimson mob drops " + (!x ? "X " : "") + (!y ? "Y " : "") + (!z ? "Z" : "");
        if (!Sky.items().exists("SYNTHESIZER_V3")) return null;          // accessories absent: nothing to wear
        ItemStack it = Sky.items().create("SYNTHESIZER_V3", 1);
        double before = Sky.items().sheet(it).total(Stat.INTELLIGENCE);
        Sky.items().update(it, d -> { d.extra.addProperty(CrimsonRules.synthKey("Z"), 25); d.extra.addProperty(CrimsonRules.synthKey("X"), 999); });
        ItemStatSheet s = Sky.items().sheet(it);
        if (Math.abs(s.total(Stat.INTELLIGENCE) - before - 25) > 1e-6) return "25 Z gave " + (s.total(Stat.INTELLIGENCE) - before) + " Intelligence";
        if (s.part(Stat.HEALTH, ItemStatSheet.Source.OTHER) > 40 + 1e-6) return "Health passed the v3 cap of 40: " + s.part(Stat.HEALTH, ItemStatSheet.Source.OTHER);
        if (CrimsonRules.synthTake(28, 30, 10) != 2) return "feeding past the cap";
        return null;
    }

    /** The maths behind Crimson Serendipity, Essence of Crimson and the reads that must never throw. */
    private static String attributeHooks() {
        if (Math.abs(CrimsonRules.vanquisherChance(0.002, 20) - 0.0024) > 1e-12) return "Serendipity +20% on 0.2% != 0.24%";
        if (CrimsonRules.vanquisherChance(0.002, -50) != 0.002) return "a negative attribute lowered the Vanquisher odds";
        if (CrimsonRules.percentBonus(10, 25, 0.99) != 2 || CrimsonRules.percentBonus(10, 25, 0.1) != 3) return "+25% of 10 essence is not 2 + a 50% third";
        if (CrimsonRules.percentBonus(10, 0, 0) != 0) return "a 0% bonus paid out";
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            double v = Attrs.value(p, "crimson_essence") + Attrs.value(p, "matriarch_cubs") + Attrs.pet(p, "kuudra_crimson");
            if (!Double.isFinite(v) || v < 0) return "attribute read for " + p.getName() + " = " + v;
            break;
        }
        return null;
    }
}
