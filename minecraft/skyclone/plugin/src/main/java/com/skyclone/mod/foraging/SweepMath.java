package com.skyclone.mod.foraging;

import java.util.random.RandomGenerator;

/**
 * Every foraging formula, pure (no Bukkit) so the unit tests run the shipping numbers.
 * Sources: research 07 §4.3 (Sweep, Foraging Fortune), §4.4 (Tree Gifts), §4.5 (Heart of the Forest),
 * Appendix A.
 */
public final class SweepMath {

    private SweepMath() {}

    /** Most extra logs one swing can take on a zero-toughness tree (Hub/Park): 1 + 35 = 36 logs. */
    public static final int ZERO_TOUGHNESS_CAP = 36;
    /** Most extra logs on a tough (Moonmarsh) tree. */
    public static final int TOUGH_EXTRA_CAP = 20;

    /**
     * Logs felled by one swing.
     * <pre>
     *   effective = Sweep x (thrown ? 1 - throwPenalty : 1) x (wrongOrder ? 0.5 : 1)
     *   toughness 0 : clamp(1 + floor(effective), 1, 36)
     *   toughness t : 1 + min(20, floor(effective / (1 + t/10)))
     * </pre>
     */
    public static int logsPerSwing(double sweep, int toughness, boolean thrown, double throwPenalty, boolean wrongOrder) {
        double eff = Math.max(0, sweep);
        if (thrown) eff *= Math.max(0, 1 - throwPenalty);
        if (wrongOrder) eff *= 0.5;
        if (toughness <= 0) return clamp(1 + (int) Math.floor(eff + 1e-9), 1, ZERO_TOUGHNESS_CAP);
        int extra = (int) Math.floor(eff / (1 + toughness / 10.0) + 1e-9);
        return 1 + Math.min(TOUGH_EXTRA_CAP, extra);
    }

    /**
     * A TOUGH tree (fig/mangrove/spiral) whose toughness something lowered (Grizzly Bear's "tough claws",
     * wave 5): always the tough formula {@code 1 + min(20, floor(effective / (1 + t/10)))}, so a cut never
     * jumps to the 36-log Park cap, with a fractional toughness allowed.
     */
    public static int logsPerSwingTough(double sweep, double toughness, boolean thrown, double throwPenalty, boolean wrongOrder) {
        double eff = Math.max(0, sweep);
        if (thrown) eff *= Math.max(0, 1 - throwPenalty);
        if (wrongOrder) eff *= 0.5;
        int extra = (int) Math.floor(eff / (1 + Math.max(0, toughness) / 10.0) + 1e-9);
        return 1 + Math.min(TOUGH_EXTRA_CAP, extra);
    }

    /** Sweep needed for {@code logs} logs per swing on a tree of this toughness. */
    public static double sweepFor(int logs, int toughness) {
        if (toughness <= 0) return Math.max(0, logs - 1);
        return Math.max(0, logs - 1) * (1 + toughness / 10.0);
    }

    /** Drops for ONE log: 1 x (1 + fortune/100), the fraction a chance at one more. */
    public static int dropsForLog(double fortune, RandomGenerator rng) {
        double v = 1 + Math.max(0, fortune) / 100.0;
        int whole = (int) Math.floor(v);
        return whole + (rng.nextDouble() < v - whole ? 1 : 0);
    }

    /** Drops for {@code logs} logs. */
    public static int drops(int logs, double fortune, RandomGenerator rng) {
        int n = 0;
        for (int i = 0; i < logs; i++) n += dropsForLog(fortune, rng);
        return n;
    }

    /** Expected drops (for menus): logs x (1 + fortune/100). */
    public static double expectedDrops(int logs, double fortune) { return logs * (1 + Math.max(0, fortune) / 100.0); }

    // ---- Tree Gifts (research 07 §4.4) ----------------------------------------------------

    /** Gift multiplier from a contributor's share of a tree: &lt;10% nothing, 10% x1, 20% x1.5, 33% x2. */
    public static double giftShare(double fraction) {
        if (fraction >= 1.0 / 3.0 - 1e-9) return 2.0;
        if (fraction >= 0.20) return 1.5;
        if (fraction >= 0.10) return 1.0;
        return 0;
    }

    // ---- Heart of the Forest (research 07 §4.5) --------------------------------------------

    /** Cumulative HotF XP to REACH each tier 1..8 (index 0 = tier 1). */
    public static final long[] HOTF_CUMULATIVE = {0, 3_000, 12_000, 37_000, 97_000, 197_000, 347_000, 547_000};
    /** Tokens gained on reaching tier 1..8. */
    public static final int[] HOTF_TOKENS = {1, 2, 2, 2, 2, 2, 2, 2};
    /** SkyBlock XP for reaching tier 1..8. */
    public static final int[] HOTF_SBXP = {35, 45, 60, 75, 90, 110, 130, 180};

    /** Tier (1..8) for a total HotF XP. */
    public static int hotfTier(long xp) {
        int t = 1;
        for (int i = 0; i < HOTF_CUMULATIVE.length; i++) if (xp >= HOTF_CUMULATIVE[i]) t = i + 1;
        return t;
    }

    /** Total tokens earned at a tier. */
    public static int tokensAt(int tier) {
        int n = 0;
        for (int i = 0; i < Math.min(tier, HOTF_TOKENS.length); i++) n += HOTF_TOKENS[i];
        return n;
    }

    /** Perk cost to go from level {@code current} to current+1: floor((next + 1) ^ e). */
    public static long perkCost(int current, double exponent) {
        int next = current + 1;
        return (long) Math.floor(Math.pow(next + 1, exponent));
    }

    /** Keystone cost (Timber): (next + 7) ^ 5. */
    public static long keystoneCost(int current) {
        int next = current + 1;
        return (long) Math.pow(next + 7, 5);
    }

    /** Daily Wishes, the one linear perk: 200 + 18n. */
    public static long linearCost(int current, long base, long step) {
        return base + step * (current + 1);
    }

    /** Sum of every level's cost from 0 to max. */
    public static long totalCost(int max, double exponent) {
        long t = 0;
        for (int i = 0; i < max; i++) t += perkCost(i, exponent);
        return t;
    }

    // ---- Ember Trials (TUNE: the reference's per-tier deliveries are not in the research) ---

    /** Logs to deliver for badge tier {@code tier} (1..29): 64, 128, 192... TUNE. */
    public static int trialLogs(int tier) { return 64 * Math.max(1, tier); }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
