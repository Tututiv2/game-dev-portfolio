package com.skyclone.mod.mining;

import com.google.gson.Gson;
import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The module's loot tables (data/mining/loot.json): weighted rolls, given through Sky.loot(). */
final class MiningLoot {

    /** {@code zoneGem}: the crystal-caverns zone (by its gem) this line belongs to; see {@link MiningMath#zoneWeight}. */
    static final class Line { public String item; public int min = 1; public int max = 1; public double weight = 1; public int amount; public String zoneGem; }
    /**
     * {@code hotmXp}: Heart of the Mountain XP paid with the table (Nucleus 800, Lapis corpse 100). {@code always}: lines
     * given every time on top of the rolls (the Nucleus' one-of-each Fine gem set).
     */
    static final class Table {
        public int[] powder; public int miningXp; public double hotmXp; public int[] rolls = {1, 1};
        public List<Line> always = new ArrayList<>(); public List<Line> table = new ArrayList<>(); public String key;
    }
    static final class Goblin { public int[] powder; }
    static final class Excavator {
        public double fossilChance; public List<String> fossils = new ArrayList<>(); public int[] rolls = {10, 14}; public List<Line> table = new ArrayList<>();
        /** research 2.14: every roll has this chance (1 in 500,000) to become {@link #dye} instead. */
        public double dyeChance; public String dye;
    }
    static final class File {
        public Table treasure_chest;
        public Table nucleus;
        public Map<String, Table> corpse = new LinkedHashMap<>();
        public Map<String, Goblin> goblins = new LinkedHashMap<>();
        public List<Line> raffle = new ArrayList<>();
        public List<Line> fetchur = new ArrayList<>();
        public Excavator excavator;
    }

    final File f;
    private final MiningModule m;
    private final Random rnd = new Random();

    MiningLoot(MiningModule m, Reader r) {
        this.m = m;
        this.f = new Gson().fromJson(r, File.class);
    }

    Line pick(List<Line> table) {
        double total = 0;
        for (Line l : table) total += l.weight;
        double x = rnd.nextDouble() * total;
        for (Line l : table) { x -= l.weight; if (x <= 0) return l; }
        return table.get(table.size() - 1);
    }

    /** Weighted pick where zone lines are boosted in their own zone and damped elsewhere (treasure chests). */
    Line pick(List<Line> table, String zoneGem) {
        if (zoneGem == null) return pick(table);
        double boost = m.dials.chestZoneBoost.get(), off = m.dials.chestOffZone.get();
        double total = 0;
        for (Line l : table) total += MiningMath.zoneWeight(l.weight, l.zoneGem, zoneGem, boost, off);
        if (total <= 0) return pick(table);
        double x = rnd.nextDouble() * total;
        for (Line l : table) { x -= MiningMath.zoneWeight(l.weight, l.zoneGem, zoneGem, boost, off); if (x <= 0) return l; }
        return table.get(table.size() - 1);
    }

    int between(int[] range) { return range == null || range.length < 2 ? 0 : MiningMath.between(range[0], range[1], rnd); }

    /**
     * Give one rolled line. ZONE_GEM_ROUGH / _FLAWED / _FINE and ZONE_CRYSTAL resolve to {@code zoneGem};
     * MITHRIL_POWDER / GLACITE_POWDER / GEMSTONE_POWDER and COINS are paid directly. Returns a chat line.
     */
    String give(Player p, Line l, String zoneGem, LootSource src) {
        String id = l.item;
        if (zoneGem == null && id.startsWith("ZONE_")) zoneGem = "RUBY";
        if (zoneGem != null) {
            id = switch (id) {
                case "ZONE_GEM_ROUGH" -> "ROUGH_" + zoneGem + "_GEM";
                case "ZONE_GEM_FLAWED" -> "FLAWED_" + zoneGem + "_GEM";
                case "ZONE_GEM_FINE" -> "FINE_" + zoneGem + "_GEM";
                case "ZONE_CRYSTAL" -> zoneGem + "_CRYSTAL";
                default -> id;
            };
        }
        int n = MiningMath.between(l.min, l.max, rnd);
        if (id.endsWith("_POWDER")) {
            String type = MiningUtil.parsePowder(id);
            long got = m.hotm.grant(p, type, n, "loot", true);
            return MiningUtil.powderColor(type) + "+" + Text.commas(got) + " " + MiningUtil.powderName(type);
        }
        if ("COINS".equals(id)) {
            Sky.economy().deposit(p, n, "mining: loot");
            return "&6+" + Text.commas(n) + " Coins";
        }
        if (!MiningUtil.exists(id)) return "&8(missing item " + id + ")";
        int left = n;
        while (left > 0) {
            int k = Math.min(64, left);
            Sky.loot().give(p, id, k, src);
            left -= k;
        }
        return MiningUtil.itemName(id) + " &8x" + n;
    }

    String rollRaffle(Player p) { return f.raffle.isEmpty() ? "" : give(p, pick(f.raffle), null, LootSource.EVENT); }

    /** Roll a whole table: powder, xp and N lines. Returns the chat lines. */
    List<String> rollTable(Player p, Table t, String zoneGem, String powderType, LootSource src) {
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        if (t.powder != null && powderType != null) {
            long got = m.hotm.grant(p, powderType, between(t.powder), "loot", true);
            out.add(MiningUtil.powderColor(powderType) + "+" + Text.commas(got) + " " + MiningUtil.powderName(powderType));
        }
        if (t.miningXp > 0) {
            Sky.service(com.skyclone.api.skill.SkillService.class).addXp(p, com.skyclone.api.skill.SkillType.MINING, t.miningXp, "mining:loot");
            out.add("&3+" + Text.commas(t.miningXp) + " Mining XP");
        }
        if (t.hotmXp > 0) {
            m.hotm.addXp(p, t.hotmXp, "loot");
            out.add("&5+" + Text.commas((long) t.hotmXp) + " Heart of the Mountain XP");
        }
        if (t.always != null) for (Line l : t.always) out.add(give(p, l, zoneGem, src));
        int rolls = between(t.rolls);
        for (int i = 0; i < rolls && !t.table.isEmpty(); i++) out.add(give(p, pick(t.table, zoneGem), zoneGem, src));
        return out;
    }
}
