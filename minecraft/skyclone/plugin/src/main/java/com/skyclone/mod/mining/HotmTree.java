package com.skyclone.mod.mining;

import com.google.gson.Gson;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Heart of the Mountain tree as data (data/mining/hotm.json, generated from the reference layout by
 * tools/gen/mining/gen_mining.py). Pure Java: no Bukkit, so the cost/value tables are unit tested.
 *
 * <p>Layout: 7 columns (x 0..6) by 10 tiers. A perk can be unlocked when the player's HOTM tier reaches
 * the perk's tier AND an orthogonally adjacent perk is unlocked (tier 1 is the root).
 */
public final class HotmTree {

    public static final class Perk {
        public String id;
        public String name;
        public int x;
        public int tier;
        public int maxLevel;
        public boolean ability;
        public String desc;
        /** Powder type for leveled perks (MITHRIL / GEMSTONE / GLACITE). */
        public String powder;
        /** costs[L] = price to go from level L to L+1; costs[0] = 0 means "a token". */
        public long[] costs;
        /** Core of the Mountain only: powder type of costs[L]. */
        public List<String> powders;
        /** values[L] = the perk's number at level L (0..max+1, +1 for the Blue Cheese omelette). */
        public double[] values;
        public double[] values_fishingspeed;
        public double[] values_seacreaturechance;
        public double[] values_chance;
        public double[] values_locks;

        /** Pickaxe abilities only: the number / duration (s) / cooldown (s) at ability level 1..3 (index 0..2). */
        public double[] abilityValue;
        public double[] abilityDuration;
        public double[] abilityCooldown;

        /** {@code which} = "value" | "duration" | "cooldown" at ability level {@code lvl} (1..3, clamped); NaN when absent. */
        public double ability(String which, int lvl) {
            double[] a = switch (which) {
                case "value" -> abilityValue;
                case "duration" -> abilityDuration;
                case "cooldown" -> abilityCooldown;
                default -> null;
            };
            return a == null || a.length == 0 ? Double.NaN : at(a, lvl - 1);
        }

        public boolean core() { return "core_of_the_mountain".equals(id); }

        public double value(int level) { return at(values, level); }

        public long costToNext(int level) {
            if (costs == null || level < 0 || level >= costs.length) return -1;
            return costs[level];
        }

        public String powderFor(int level) {
            if (powders != null && level >= 0 && level < powders.size()) return powders.get(level);
            return powder == null ? "MITHRIL" : powder;
        }

        static double at(double[] a, int level) {
            if (a == null || a.length == 0) return 0;
            return a[Math.max(0, Math.min(a.length - 1, level))];
        }
    }

    public int[] xp;
    public int[] tokens;
    public int[] skyblockXp;
    public List<Perk> perks = new ArrayList<>();

    private transient Map<String, Perk> byId;
    private transient Perk[][] grid;

    public static HotmTree load(Reader r) {
        HotmTree t = new Gson().fromJson(r, HotmTree.class);
        t.index();
        return t;
    }

    void index() {
        byId = new LinkedHashMap<>();
        grid = new Perk[11][7];
        for (Perk p : perks) {
            byId.put(p.id, p);
            if (p.tier >= 1 && p.tier <= 10 && p.x >= 0 && p.x < 7) grid[p.tier][p.x] = p;
        }
    }

    public Perk perk(String id) { return byId.get(id); }

    public Perk at(int tier, int x) {
        if (tier < 1 || tier > 10 || x < 0 || x > 6) return null;
        return grid[tier][x];
    }

    public int maxTier() { return xp.length; }

    public int tier(double hotmXp) { return MiningMath.hotmTier(hotmXp, xp); }

    public double xpForTier(int tier) { return MiningMath.xpForTier(tier, xp); }

    /** Tokens from tiers reached plus Core of the Mountain levels 5, 7 (+1 each) and 10 (+2). */
    public int tokensFor(int tier, int coreLevel) {
        int n = 0;
        for (int i = 0; i < Math.min(tier, tokens.length); i++) n += tokens[i];
        if (coreLevel >= 5) n++;
        if (coreLevel >= 7) n++;
        if (coreLevel >= 10) n += 2;
        return n;
    }

    /** Adjacent (orthogonal) perks of one perk. */
    public List<Perk> neighbours(Perk p) {
        List<Perk> out = new ArrayList<>(4);
        int[][] d = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] o : d) {
            Perk n = at(p.tier + o[0], p.x + o[1]);
            if (n != null) out.add(n);
        }
        return out;
    }

    /** Can this perk be unlocked given the levels already held? (tier and adjacency, not tokens) */
    public boolean reachable(Perk p, Map<String, Integer> levels, int playerTier) {
        if (playerTier < p.tier) return false;
        if (p.tier == 1) return true;
        for (Perk n : neighbours(p)) if (levels.getOrDefault(n.id, 0) > 0) return true;
        return false;
    }

    /** Total powder of each type needed to max every leveled perk (sanity number for self-tests). */
    public Map<String, Long> totalCosts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Perk p : perks) {
            if (p.costs == null) continue;
            for (int l = 0; l < p.costs.length; l++) m.merge(p.powderFor(l), p.costs[l], Long::sum);
        }
        return m;
    }
}
