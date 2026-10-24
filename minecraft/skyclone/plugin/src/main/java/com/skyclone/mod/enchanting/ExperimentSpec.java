package com.skyclone.mod.enchanting;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * data/enchanting/experiments.json: the Experimentation Table tiers (research 08 §3.4: grid size,
 * add-on rounds, Enchanting level) and their reward pools (built by the generator from the books the
 * research lists as "Experiments" drops). Click counts, XP and weights are TUNE.
 */
public final class ExperimentSpec {

    public List<Tier> tiers = new ArrayList<>();
    /** Enchanting XP per add-on round cleared, by tier index. */
    public int[] addonRoundXp = new int[0];
    /** Superpairs completion bonus = pairXp x this, when every pair is found. */
    public double completionXpFactor = 2.0;
    /** Endcap Upgrades a Superpairs game can deal as one ultra-rare pair (tiers with {@link Tier#ultraRareChance} > 0). */
    public List<String> ultraRare = new ArrayList<>();
    /** Extra games bought (XP levels + bits) once the charges are spent, in order, per UTC day. */
    public List<ExtraRun> extraRuns = new ArrayList<>();

    public static final class ExtraRun {
        public int levels;
        public long bits;
    }

    /** The {@code n}-th extra game of the day (0-based), or null when there is none. */
    public ExtraRun extraRun(int n) {
        return extraRuns == null || n < 0 || n >= extraRuns.size() ? null : extraRuns.get(n);
    }

    public static final class Tier {
        public String id;
        public String name;
        public int skill;
        /** Superpairs starting cost in coins. */
        public long fee;
        /** Percent per Superpairs game that one pair is an Endcap Upgrade from {@link ExperimentSpec#ultraRare}. */
        public double ultraRareChance;
        public int rows;
        public int cols;
        public int chronomatronRounds;
        public int ultrasequencerRounds;
        public int clicks;
        public int pairXp;
        public int specials;
        public int keys;
        public List<Reward> rewards = new ArrayList<>();

        public int tiles() { return rows * cols; }

        public int pairs() { return (tiles() - specials) / 2; }

        public Reward roll(Random rnd) { return roll(rnd, 0); }

        /**
         * Weighted roll; {@code ultraRareBoostPct} raises the weight of the ULTRA RARE books (an enchanted book whose
         * weight is the pool's lowest book weight) by that percent - the Guardian pet's Lucky Seven perk.
         */
        public Reward roll(Random rnd, double ultraRareBoostPct) {
            int floor = ultraRareWeight();
            double mult = 1 + Math.max(0, ultraRareBoostPct) / 100.0;
            double total = 0;
            for (Reward r : rewards) total += weightOf(r, floor, mult);
            if (total <= 0) return null;
            double x = rnd.nextDouble() * total;
            for (Reward r : rewards) {
                x -= weightOf(r, floor, mult);
                if (x < 0) return r;
            }
            return rewards.get(rewards.size() - 1);
        }

        /** The lowest weight any enchanted book in this pool has (its "ultra rare" drops), or -1 when none. */
        public int ultraRareWeight() {
            int min = Integer.MAX_VALUE;
            for (Reward r : rewards) if (isBook(r) && r.weight > 0) min = Math.min(min, r.weight);
            return min == Integer.MAX_VALUE ? -1 : min;
        }

        public static boolean isBook(Reward r) { return r != null && r.item != null && r.item.startsWith("ENCHANTMENT_"); }

        private static double weightOf(Reward r, int floor, double mult) {
            double w = Math.max(0, r.weight);
            return isBook(r) && r.weight == floor ? w * mult : w;
        }
    }

    public static final class Reward {
        public String item;
        public int amount = 1;
        public int weight = 1;

        public String key() { return item + "x" + amount; }
    }

    public Tier tier(String id) {
        for (Tier t : tiers) if (t.id.equalsIgnoreCase(id)) return t;
        return null;
    }

    public int index(Tier t) { return tiers.indexOf(t); }

    public int addonXp(int tierIndex) {
        if (addonRoundXp == null || addonRoundXp.length == 0) return 50;
        return addonRoundXp[Math.max(0, Math.min(tierIndex, addonRoundXp.length - 1))];
    }
}
