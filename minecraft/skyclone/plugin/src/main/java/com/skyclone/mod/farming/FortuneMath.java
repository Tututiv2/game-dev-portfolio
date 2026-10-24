package com.skyclone.mod.farming;

/**
 * Fortune and tool-progress maths. Pure (no Bukkit) so the unit tests run the shipping numbers.
 *
 * <p>Research 08 section 1.3: {@code drops = base x (1 + floor(F/100) + (rand < frac(F/100) ? 1 : 0))} -
 * "260 fortune yields 2 guaranteed extra drops plus a 60% chance for a third".
 */
public final class FortuneMath {

    private FortuneMath() {}

    /** Drops for one harvested block. {@code r} is a uniform random in [0,1). Negative fortune counts as 0. */
    public static int roll(int base, double fortune, double r) {
        if (base <= 0) return 0;
        double f = Math.max(0, fortune) / 100.0;
        long guaranteed = (long) Math.floor(f);
        double chance = f - guaranteed;
        long mult = 1 + guaranteed + (r < chance ? 1 : 0);
        return (int) Math.min(Integer.MAX_VALUE, base * mult);
    }

    /** Expected drops for one block (for menus and the ghost model). */
    public static double expected(double base, double fortune) {
        return base * (1 + Math.max(0, fortune) / 100.0);
    }

    /** Number of decimal digits of a non-negative count (0 has no digits: an unused counter gives nothing). */
    public static int digits(long n) {
        if (n <= 0) return 0;
        return (int) Math.floor(Math.log10((double) n)) + 1;
    }

    /** Logarithmic Counter: +16 Crop Fortune per digit of the tool's counter (research 08 1.4: 1,234,567 -> +112). */
    public static int counterFortune(long counter, int perDigit) { return digits(counter) * perDigit; }

    /** Collection Analysis: +8 Crop Fortune per digit of the player's collection (research 08 1.4). */
    public static int analysisFortune(long collection, int perDigit) { return digits(collection) * perDigit; }

    /**
     * Tool level from its XP. {@code table[i]} is the XP to go from level i+1 to i+2 (skyhanni Garden.json
     * hoe_exp_levels, 49 steps = levels 1-50). Returns the level (1-based).
     */
    public static int toolLevel(double xp, int[] table) {
        int lvl = 1;
        double left = xp;
        for (int need : table) {
            if (left < need) break;
            left -= need;
            lvl++;
        }
        return lvl;
    }

    /** XP into the current level (index 0) and XP needed for the next (index 1; 0 at max). */
    public static double[] toolProgress(double xp, int[] table) {
        double left = xp;
        for (int need : table) {
            if (left < need) return new double[]{left, need};
            left -= need;
        }
        return new double[]{left, 0};
    }

    /**
     * Highest level a tool of this Mk can reach (garden_max.md corrections): Mk I 15, Mk II 30, Mk III 40, and each
     * Overclocker 3000 on a Mk III raises it by one, at most {@code maxOverclocks} (10, so 50). A tool with no Mk (0)
     * levels to the table's end. Never above {@code tableMax} (the XP table's last level).
     */
    public static int levelCap(int mk, int overclocks, int capMk1, int capMk2, int capMk3, int maxOverclocks, int tableMax) {
        int cap = switch (mk) {
            case 1 -> capMk1;
            case 2 -> capMk2;
            case 3 -> capMk3 + Math.max(0, Math.min(overclocks, maxOverclocks));
            default -> tableMax;
        };
        return Math.max(1, Math.min(cap, tableMax));
    }

    /** Why an Overclocker 3000 cannot go on this tool, or null when it can (Mk III, level at least the Mk III cap, room left). */
    public static String overclockRefusal(int mk, int level, int overclocks, int capMk3, int maxOverclocks) {
        if (mk < 3) return "Only Mk III farming tools can be overclocked.";
        if (overclocks >= maxOverclocks) return "This tool already has the maximum Overclockers.";
        if (level < capMk3) return "The tool must be level " + capMk3 + " first.";
        return null;
    }

    /** {@link #toolLevel(double, int[])} held at {@code cap}. */
    public static int toolLevel(double xp, int[] table, int cap) { return Math.min(toolLevel(xp, table), Math.max(1, cap)); }

    /** {@link #toolProgress(double, int[])} that reads MAXED (index 1 = 0) once the level sits at {@code cap}. */
    public static double[] toolProgress(double xp, int[] table, int cap) {
        if (toolLevel(xp, table) >= cap) return new double[]{0, 0};
        return toolProgress(xp, table);
    }

    /** Total XP that reaches level {@code level} from level 1 (the XP a capped tool stops collecting at). */
    public static double xpToReach(int level, int[] table) {
        double sum = 0;
        for (int i = 0; i < Math.min(level - 1, table.length); i++) sum += table[i];
        return sum;
    }

    /**
     * New XP after adding {@code add} to a tool capped at {@code cap}: it stops at the XP that reaches the cap (so an
     * upgrade to the next Mk starts levelling from there instead of jumping). XP already past the cap (old saves) is
     * kept, never lowered. A cap at the table's end does not clamp (overflow XP is harmless there).
     */
    public static double cappedXp(double old, double add, int cap, int[] table) {
        if (cap >= table.length + 1) return old + add;
        double top = xpToReach(cap, table);
        if (old >= top) return old;
        return Math.min(old + add, top);
    }

    /** Crop Fortune a tool's level adds (TUNE: +2 per level above 1). */
    public static int levelFortune(int level, int perLevel) { return Math.max(0, level - 1) * perLevel; }

    /**
     * Harvesta's Personal Bests perk: +0.01 Crop Fortune per {@code step} crops of the player's best Harvest Fair score
     * for that crop, capped at {@code max} (skyhanni Garden.json personal_best_increment; the 100 cap is reached at the
     * reference's top scores, e.g. 1,000,000 Wheat at step 100). Unknown step = nothing.
     */
    public static double personalBestFortune(long best, int step, double max) {
        if (best <= 0 || step <= 0) return 0;
        return Math.min(max, Math.floor(best / (double) step) * 0.01);
    }

    /** Crop milestone tier for a crop count (garden cumulative-step table; tier 0 below the first step). */
    public static int milestone(long count, int[] steps) {
        int tier = 0;
        long need = 0;
        for (int s : steps) {
            need += s;
            if (count < need) break;
            tier++;
        }
        return tier;
    }
}
