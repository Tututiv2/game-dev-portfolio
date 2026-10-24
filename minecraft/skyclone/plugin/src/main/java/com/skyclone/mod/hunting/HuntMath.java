package com.skyclone.mod.hunting;

import java.util.Locale;

/**
 * Every hunting number in one pure place (no Bukkit), so the unit tests run the shipping math.
 *
 * <p>Sources: attribute levelling is the reference table ({@code neu-repo/constants/attribute_shards.json}
 * {@code attribute_levelling}); capture XP is research 02 section 2.4.8 (Common 75 ... Legendary 1,000);
 * Hunting Fortune "every 100 = +1 shard" is research 01. Syphon XP and fusion costs are not published
 * and are marked TUNE.
 */
public final class HuntMath {

    private HuntMath() {}

    public static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"};

    /** Shards needed to go from level i to i+1 (index 0 = the first level), per rarity. Reference table. */
    static final int[][] LEVELLING = {
            {1, 3, 5, 6, 7, 8, 10, 14, 18, 24},     // COMMON    = 96 to max
            {1, 2, 3, 4, 5, 6, 7, 8, 12, 16},       // UNCOMMON  = 64
            {1, 2, 3, 3, 4, 4, 5, 6, 8, 12},        // RARE      = 48
            {1, 1, 2, 2, 3, 3, 4, 4, 5, 7},         // EPIC      = 32
            {1, 1, 1, 2, 2, 2, 3, 3, 4, 5},         // LEGENDARY = 24
    };

    public static final int MAX_LEVEL = 10;

    /** Hunting XP for capturing one shard, by rarity (research 02 section 2.4.8). */
    static final double[] CAPTURE_XP = {75, 150, 300, 500, 1000};
    /** Hunting XP per shard syphoned. // TUNE: research says "small amounts (Common ~10)". */
    static final double[] SYPHON_XP = {10, 25, 50, 100, 200};
    /** Shards of each input a fusion consumes. // TUNE: the reference shows a per-shard fuse amount; not in the data. */
    static final int[] FUSE_COST = {5, 4, 3, 2, 1};
    /** Weight of each rarity when a habitat picks which critter to spawn. // TUNE */
    static final int[] SPAWN_WEIGHT = {50, 26, 14, 7, 3};
    /** Safari Essence per capture, by rarity. // TUNE */
    static final int[] SAFARI_ESSENCE = {1, 2, 4, 8, 16};

    public static int rarityIndex(String r) {
        if (r == null) return 0;
        String u = r.toUpperCase(Locale.ROOT);
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(u)) return i;
        return 0;
    }

    /** Attribute level reached after syphoning {@code shards} shards of a shard of this rarity. */
    public static int level(String rarity, int shards) {
        int[] t = LEVELLING[rarityIndex(rarity)];
        int need = 0;
        int lvl = 0;
        for (int step : t) {
            need += step;
            if (shards >= need) lvl++;
            else break;
        }
        return lvl;
    }

    /** Total shards syphoned to reach {@code level}. */
    public static int shardsFor(String rarity, int level) {
        int[] t = LEVELLING[rarityIndex(rarity)];
        int sum = 0;
        for (int i = 0; i < Math.min(level, t.length); i++) sum += t[i];
        return sum;
    }

    /** Shards still needed for the next level, or 0 at max. */
    public static int toNext(String rarity, int shards) {
        int lvl = level(rarity, shards);
        if (lvl >= MAX_LEVEL) return 0;
        return shardsFor(rarity, lvl + 1) - shards;
    }

    public static int maxShards(String rarity) { return shardsFor(rarity, MAX_LEVEL); }

    public static double captureXp(String rarity) { return CAPTURE_XP[rarityIndex(rarity)]; }

    public static double syphonXp(String rarity) { return SYPHON_XP[rarityIndex(rarity)]; }

    public static int fuseCost(String rarity) { return FUSE_COST[rarityIndex(rarity)]; }

    public static int spawnWeight(String rarity) { return SPAWN_WEIGHT[rarityIndex(rarity)]; }

    public static int safariEssence(String rarity) { return SAFARI_ESSENCE[rarityIndex(rarity)]; }

    /**
     * Shards from one capture: 1, plus one per full 100 Hunting Fortune, plus one more with the remainder
     * as a percent chance ({@code roll} in [0,1)). Research 01: "Hunting Fortune - every 100 = +1 Attribute Shard".
     */
    public static int shardsPerCatch(double huntingFortune, double roll) {
        if (huntingFortune <= 0 || !Double.isFinite(huntingFortune)) return 1;
        double hf = Math.min(huntingFortune, 10_000);
        int n = 1 + (int) Math.floor(hf / 100.0);
        double rest = (hf % 100.0) / 100.0;
        if (roll < rest) n++;
        return n;
    }

    /** "2", "0.5", "1.25" - no trailing zeros. */
    public static String fmt(double v) {
        if (!Double.isFinite(v)) return "0";
        double r = Math.round(v * 1000.0) / 1000.0;
        if (r == Math.rint(r)) return String.valueOf((long) r);
        String s = String.format(Locale.ROOT, "%.3f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Charm Chance from the Hunting skill perk: +0.04 per level (core-wave5 hand-off, skills data). */
    public static double skillCharm(int huntingLevel) { return Math.max(0, huntingLevel) * 0.04; }

    /**
     * Lasso stamina in seconds before the next reel window: base by rarity, divided by how much faster the
     * lasso (and Hunter's Suppress) drains it. {@code fasterPct} 40 = 40% faster.
     */
    public static double lassoStamina(double baseSeconds, double fasterPct) {
        double f = 1 + Math.max(0, fasterPct) / 100.0;
        return Math.max(0.4, baseSeconds / f);
    }

    /** Fraction of a net catch made by one throw: pull vs the critter's required pull (1.0 = caught). */
    public static double netProgress(double pull, double required) {
        if (required <= 0) return 1;
        return Math.max(0.05, pull / required);
    }

    // ------------------------------------------------------------ water critters (playtest wave 5, finding 2)
    // A Fishing Net reaches ~7 blocks, but vanilla fish AI dives 8-12 blocks and wanders off across a lagoon, so a
    // player on a pier waited a minute per catch. Water critters now appear near the hunter and are kept shallow
    // and near where they appeared. // TUNE (the reference's critters idle near the surface; no numbers exist).

    /** Horizontal distance band (blocks) from a hunter in which a water critter appears: inside one net throw. */
    public static final int WATER_SPAWN_MIN = 3, WATER_SPAWN_MAX = 6;

    /** Upward velocity (blocks/tick) given each second to a water critter {@code depth} blocks under the surface. */
    public static double surfacePush(int depth, int maxDepth) {
        if (depth <= Math.max(0, maxDepth)) return 0;
        return Math.min(0.5, 0.15 * (depth - Math.max(0, maxDepth)));
    }

    /** Horizontal velocity (blocks/tick) back toward its spawn spot for a critter {@code dist} blocks from it. */
    public static double leashPull(double dist, double leash) {
        if (dist <= Math.max(1, leash)) return 0;
        return Math.min(0.4, 0.06 * (dist - Math.max(1, leash)));
    }

    /** True when nudging is not enough (squid AI overrides velocity; a dive can outrun the push): put it back. */
    public static boolean snapBack(int depth, int maxDepth, double dist, double leash) {
        return depth > Math.max(0, maxDepth) + 4 || dist > Math.max(1, leash) * 2;
    }

    /** Capsule catch chance in the Safari, by rarity. // TUNE: "rarer shards are less likely to be caught". */
    public static double capsuleChance(String rarity) {
        return switch (rarityIndex(rarity)) {
            case 0 -> 0.90;
            case 1 -> 0.70;
            case 2 -> 0.50;
            case 3 -> 0.35;
            default -> 0.20;
        };
    }
}
