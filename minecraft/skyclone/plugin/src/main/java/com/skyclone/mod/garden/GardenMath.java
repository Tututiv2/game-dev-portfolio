package com.skyclone.mod.garden;

/**
 * Every formula of the garden in one pure class (no Bukkit), so the unit tests can check them
 * against the research numbers. Section references are research/08 1.x.
 */
public final class GardenMath {

    private GardenMath() {}

    // ---- garden levels (1.5): perLevel[i] = XP for level i+1, perLevel[0] = 0 ----------------

    public static long totalXpFor(int level, long[] perLevel) {
        long t = 0;
        for (int i = 0; i < Math.min(level, perLevel.length); i++) t += perLevel[i];
        return t;
    }

    public static int level(double xp, long[] perLevel) {
        int lvl = 1;
        long need = 0;
        for (int i = 1; i < perLevel.length; i++) {
            need += perLevel[i];
            if (xp >= need) lvl = i + 1; else break;
        }
        return lvl;
    }

    public static int maxLevel(long[] perLevel) { return perLevel.length; }

    /** XP into the current level. */
    public static double into(double xp, long[] perLevel) {
        int l = level(xp, perLevel);
        return xp - totalXpFor(l, perLevel);
    }

    /** XP the current level needs in full (0 at max). */
    public static long needed(double xp, long[] perLevel) {
        int l = level(xp, perLevel);
        return l >= perLevel.length ? 0 : perLevel[l];
    }

    /** +10 Crop Growth per garden level above I (1.2). */
    public static int cropGrowth(int level) { return Math.max(0, 10 * (level - 1)); }

    /** Regrow time in seconds after Crop Growth (1 Crop Growth = +1% speed, capped at +210). */
    public static double regrowSeconds(double baseSeconds, double cropGrowth) {
        return baseSeconds / (1 + Math.min(210, Math.max(0, cropGrowth)) / 100.0);
    }

    // ---- crop milestones (1.8): tiers[i] = amount needed for tier i+1 (not cumulative) ---------

    public static int milestoneTier(long count, long[] tiers) {
        long need = 0;
        int t = 0;
        for (long a : tiers) {
            need += a;
            if (count >= need) t++; else break;
        }
        return t;
    }

    /** Cumulative amount that completes tier {@code tier} (1-based). */
    public static long milestoneThreshold(int tier, long[] tiers) {
        long need = 0;
        for (int i = 0; i < Math.min(tier, tiers.length); i++) need += tiers[i];
        return need;
    }

    /** Garden XP for completing milestone tier N (1.8 clone default: 10 + 5 x floor(N/5)). */
    public static int milestoneGardenXp(int tier) { return 10 + 5 * (tier / 5); }

    // ---- farming fortune (1.3) -----------------------------------------------------------------

    /** drops = base x (1 + floor(F/100) + [roll &lt; F mod 100 / 100]). */
    public static int fortuneDrops(int base, double fortune, double roll) {
        if (fortune < 0) fortune = 0;
        double m = fortune / 100.0;
        int guaranteed = (int) Math.floor(m);
        double chance = m - guaranteed;
        return base * (1 + guaranteed + (roll < chance ? 1 : 0));
    }

    // ---- pests (1.12) --------------------------------------------------------------------------

    /** Farming Fortune penalty in percent for {@code live} pests at {@code bpc} Bonus Pest Chance. */
    public static int penalty(int[][] table, double bpc, int live) {
        if (table == null || table.length == 0 || live < 0) return 0;
        int row = (int) Math.max(0, Math.min(table.length - 1, Math.floor(bpc / 100)));
        int[] r = table[row];
        return r[Math.min(r.length - 1, live)];
    }

    /** Pests per successful spawn roll: one, plus one per 100 Bonus Pest Chance, plus a chance for the rest. */
    public static int pestsPerSpawn(double bpc, double roll) {
        if (bpc <= 0) return 1;
        int extra = (int) Math.floor(bpc / 100);
        double rem = (bpc - extra * 100) / 100.0;
        return 1 + extra + (roll < rem ? 1 : 0);
    }

    /** Cooldown after a spawn: base x repellent, minus reductions, clamped. */
    public static double pestCooldown(double baseSeconds, double repellentFactor, double reductionFraction, double min, double max) {
        return pestCooldown(baseSeconds, repellentFactor, reductionFraction, 0, min, max);
    }

    /**
     * As above, then {@code flatSeconds} off (the hunting Pest Cooldown attribute: 0.5 s a level, 5 s at X) before the
     * floor, so Pest Vest + 3 Pesthunter + 4 Squeaky (135 s) plus the attribute at X reach the 130 s floor.
     */
    public static double pestCooldown(double baseSeconds, double repellentFactor, double reductionFraction, double flatSeconds, double min, double max) {
        double v = baseSeconds * Math.max(1, repellentFactor) * (1 - Math.max(0, Math.min(0.9, reductionFraction))) - Math.max(0, flatSeconds);
        return Math.max(min, Math.min(max, v));
    }

    // ---- composter (1.11) ----------------------------------------------------------------------

    public record CompostRun(int made, double organic, double fuel, double progress) {}

    /**
     * Advance the composter by {@code elapsed} seconds. It never ticks: this is a pure function of
     * elapsed time. A compost needs a full interval of work AND its organic matter and fuel.
     * {@code progress} is the work already done towards the next one.
     */
    public static CompostRun compost(double organic, double fuel, double progress, double elapsed,
                                     double interval, double organicPer, double fuelPer, int limit) {
        if (interval <= 0) interval = 1;
        int canAfford = (int) Math.floor(Math.min(organicPer <= 0 ? Double.MAX_VALUE : organic / organicPer,
                fuelPer <= 0 ? Double.MAX_VALUE : fuel / fuelPer));
        if (canAfford <= 0) return new CompostRun(0, organic, fuel, 0);
        double work = progress + Math.max(0, elapsed);
        int made = (int) Math.min(Math.min(canAfford, limit), Math.floor(work / interval));
        double left = work - made * interval;
        if (made >= canAfford || made >= limit) left = 0;
        // the next one only progresses if it can be paid for
        if (canAfford - made <= 0) left = 0;
        return new CompostRun(made, organic - made * organicPer, fuel - made * fuelPer, Math.min(left, interval));
    }

    public static double composterInterval(double baseSeconds, double speedPercent) {
        return baseSeconds / (1 + Math.max(0, speedPercent) / 100.0);
    }

    // ---- greenhouse (1.15) ---------------------------------------------------------------------

    /** T = base / (1 + 0.025c + 0.0025g + 0.001a + U), never below the minimum. */
    public static double stageSeconds(double base, double min, int uniqueCrops, double growth, double attribute, double u) {
        double t = base / (1 + 0.025 * Math.min(12, uniqueCrops) + 0.0025 * growth + 0.001 * attribute + u);
        return Math.max(min, t);
    }

    // ---- visitors (1.10) -----------------------------------------------------------------------

    /**
     * Raw crops a visitor asks for (clone formula): 150 x 1.32^(level-1), times the rarity's size,
     * times the crop's base drops (so a melon request is as much work as a wheat one), times a
     * roll in [0.7, 1.3). TUNE: the research gives only the shape ("1-4 minutes of income").
     */
    public static long requestRaw(int level, double size, double cropBaseDrops, double roll) {
        double v = 150 * Math.pow(1.32, Math.max(0, level - 1)) * size * Math.max(1, cropBaseDrops) * (0.7 + 0.6 * roll);
        return Math.max(10, Math.round(v));
    }

    /** Copper for an offer: the rarity range, rising with garden level. */
    public static int offerCopper(int min, int max, int level, double roll) {
        double t = Math.max(0, Math.min(1, (level - 1) / 14.0));
        double v = min + (max - min) * t;
        return (int) Math.max(1, Math.round(v * (0.9 + 0.2 * roll)));
    }

    /** Index into a plot group's cost list for the next plot bought in that group. */
    public static int plotCostIndex(int boughtInGroup, int costs) { return Math.max(0, Math.min(costs - 1, boughtInGroup)); }

    /** Crop Analyzer level from mutations analysed: one level per threshold reached. */
    public static int analyzerLevel(int analysed, int[] thresholds) {
        int l = 0;
        for (int t : thresholds) if (analysed >= t) l++;
        return l;
    }

    public static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        if (n <= 0) return String.valueOf(n);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { n -= v[i]; b.append(s[i]); }
        return b.toString();
    }
}
