package com.skyclone.mod.dungeons.loot;

import com.google.gson.Gson;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Reward-chest tables (research 04 sec 3.14), loaded from {@code data/dungeons/loot.json} which
 * {@code tools/gen/dungeons/gen_content.py} writes from NEU's per-floor RNG list. PURE: no Bukkit.
 *
 * <p>Rules: Wood needs the boss dead and class milestone 2; Gold and Diamond need the boss dead; Emerald needs an A
 * (230), Obsidian an S (270), Bedrock an S+ (300); Master Mode grants every chest regardless of score. A chest rolls
 * {@code slots} distinct entries from those whose {@code minChest} is at or below it, by weight; entries that sit in
 * exactly this chest weigh x4 and in the one below x2, so a better chest leans toward its own prizes. A floor whose
 * entries carry a per-chest {@code weights}/{@code costs} table (F6, from the wiki) rolls those numbers instead. Chest luck
 * (S+, Boss Luck, treasure accessories) raises the weight of rare entries. The price to open is the chest's base
 * cost plus the coin cost of what it rolled.
 */
public final class LootTables {

    public enum Chest {
        WOOD("Wood", "&f", "CHEST"), GOLD("Gold", "&6", "GOLD_BLOCK"), DIAMOND("Diamond", "&b", "DIAMOND_BLOCK"),
        EMERALD("Emerald", "&a", "EMERALD_BLOCK"), OBSIDIAN("Obsidian", "&5", "OBSIDIAN"), BEDROCK("Bedrock", "&8", "BEDROCK");
        public final String display, colour, base;
        Chest(String display, String colour, String base) { this.display = display; this.colour = colour; this.base = base; }
        public String label() { return colour + display + " Chest"; }
        public static Chest parse(String s) { try { return valueOf(s.toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return null; } }
    }

    public static final class ChestDef {
        public double cost;
        public int undead, witherMin, witherMax, slots = 1;
    }

    public static final class Entry {
        public String item;
        public int amount = 1;
        public double rng;
        public String minChest = "WOOD";
        public double weight = 1;
        public double cost;
        public String gate;
        /**
         * Optional per-chest table (2026-10-06, wiki floor pages): chest name -> weight in THAT chest's own table, and
         * chest name -> coin cost when it rolls there. With {@code weights} set the entry sits only in the chests it
         * names, at exactly that weight (no x4/x2 lean), so a floor that carries the wiki's per-chest percentages rolls
         * the wiki's odds. Without it the entry uses {@code minChest} + {@code weight} + {@code cost} as before.
         */
        public Map<String, Double> weights;
        public Map<String, Double> costs;

        public Chest min() { Chest c = Chest.parse(minChest); return c == null ? Chest.WOOD : c; }

        /** Can this entry roll in chest {@code c}? */
        public boolean inChest(Chest c) {
            if (weights != null && !weights.isEmpty()) return weights.getOrDefault(c.name(), 0.0) > 0;
            return min().ordinal() <= c.ordinal();
        }

        /** This entry's weight in chest {@code c} before luck (0 when it cannot roll there). */
        public double weightIn(Chest c) {
            if (weights != null && !weights.isEmpty()) return weights.getOrDefault(c.name(), 0.0);
            if (min().ordinal() > c.ordinal()) return 0;
            int gap = c.ordinal() - min().ordinal();
            return weight * (gap == 0 ? 4 : gap == 1 ? 2 : 1);
        }

        /** The coin cost this entry adds when it rolls in chest {@code c}. */
        public double costIn(Chest c) {
            if (costs != null) { Double v = costs.get(c.name()); if (v != null) return v; }
            return cost;
        }
    }

    public static final class FloorLoot {
        public Map<String, ChestDef> chests = new LinkedHashMap<>();
        public List<Entry> entries = new ArrayList<>();

        public ChestDef chest(Chest c) { return chests.get(c.name()); }

        public double maxRng() { double m = 1; for (Entry e : entries) m = Math.max(m, e.rng); return m; }

        public Entry entry(String item) { for (Entry e : entries) if (e.item.equals(item)) return e; return null; }
    }

    public static final class Data {
        public List<String> chestOrder = new ArrayList<>();
        public Map<String, String> unlock = new LinkedHashMap<>();
        public Map<String, FloorLoot> floors = new LinkedHashMap<>();
    }

    /** One rolled chest: what it holds and what it costs to open. Gson-friendly (stored at the vault keeper). */
    public static final class Rolled {
        public String chest;
        public double cost;
        public List<String> items = new ArrayList<>();      // "ID:amount"
        public int undead, wither;
        public boolean opened;
        public String rngItem;

        public Chest tier() { return Chest.parse(chest); }
    }

    private static Data data = new Data();

    public static Data load(Reader r) {
        Data d = new Gson().fromJson(r, Data.class);
        if (d != null && d.floors != null) data = d;
        return data;
    }

    public static Data data() { return data; }

    public static FloorLoot floor(String id) { return data.floors.get(id == null ? "" : id.toUpperCase(Locale.ROOT)); }

    /** The chests a completed run unlocks, best last. */
    public static List<Chest> unlocked(FloorLoot f, boolean master, int score, int milestone) {
        List<Chest> out = new ArrayList<>();
        if (f == null) return out;
        for (Chest c : Chest.values()) {
            if (f.chest(c) == null) continue;
            boolean ok = master || switch (c) {
                case WOOD -> milestone >= 2;
                case GOLD, DIAMOND -> true;
                case EMERALD -> score >= 230;
                case OBSIDIAN -> score >= 270;
                case BEDROCK -> score >= 300;
            };
            if (ok) out.add(c);
        }
        return out;
    }

    /**
     * Roll one chest. {@code allowed} filters entries (item exists, collection gate met); {@code luck} is chest
     * quality points; {@code forced} is the RNG-meter item to guarantee in this chest (or null); {@code essenceMult}
     * scales essence.
     */
    public static Rolled roll(FloorLoot f, Chest c, Random rng, Predicate<Entry> allowed, double luck, double luckStep, String forced, double essenceMult) {
        Rolled out = new Rolled();
        out.chest = c.name();
        ChestDef def = f.chest(c);
        if (def == null) return out;
        out.cost = def.cost;
        out.undead = (int) Math.max(1, Math.round(def.undead * essenceMult));
        out.wither = (int) Math.max(1, Math.round((def.witherMin + (def.witherMax > def.witherMin ? rng.nextInt(def.witherMax - def.witherMin + 1) : 0)) * essenceMult));
        double max = f.maxRng();
        List<Entry> pool = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (Entry e : f.entries) {
            if (!e.inChest(c) || !allowed.test(e)) continue;
            double wt = e.weightIn(c);
            if (wt <= 0) continue;
            wt *= 1 + luck * luckStep * (e.rng / max);
            pool.add(e);
            w.add(wt);
        }
        int slots = Math.max(1, def.slots);
        if (forced != null) {
            Entry fe = f.entry(forced);
            if (fe != null && fe.inChest(c)) {
                add(out, fe, c);
                out.rngItem = fe.item;
                int i = pool.indexOf(fe);
                if (i >= 0) { pool.remove(i); w.remove(i); }
                slots--;
            }
        }
        for (int s = 0; s < slots && !pool.isEmpty(); s++) {
            double total = 0;
            for (double x : w) total += x;
            double r = rng.nextDouble() * total;
            int pick = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) { r -= w.get(i); if (r <= 0) { pick = i; break; } }
            add(out, pool.get(pick), c);
            pool.remove(pick);
            w.remove(pick);
        }
        return out;
    }

    private static void add(Rolled out, Entry e, Chest c) {
        out.items.add(e.item + ":" + Math.max(1, e.amount));
        out.cost += e.costIn(c);
    }

    /**
     * The exact chance that {@code item} is in a fresh roll of chest {@code c} (no luck, no meter), for one slot: its
     * weight over the chest's total. With several slots it is a lower bound per slot. Used by tests and /sb dungeons.
     */
    public static double firstRollChance(FloorLoot f, Chest c, String item, Predicate<Entry> allowed) {
        double total = 0, mine = 0;
        for (Entry e : f.entries) {
            if (!e.inChest(c) || !allowed.test(e)) continue;
            double w = e.weightIn(c);
            total += w;
            if (e.item.equals(item)) mine += w;
        }
        return total <= 0 ? 0 : mine / total;
    }

    public static String idOf(String cell) { int i = cell.lastIndexOf(':'); return i < 0 ? cell : cell.substring(0, i); }

    public static int amountOf(String cell) {
        int i = cell.lastIndexOf(':');
        try { return i < 0 ? 1 : Integer.parseInt(cell.substring(i + 1)); } catch (NumberFormatException e) { return 1; }
    }

    /** RNG meter XP a run adds (research (?): the run's score, on S runs and better). */
    public static double meterGain(int score, int minScore, double scale) { return score >= minScore ? score * scale : 0; }
}
