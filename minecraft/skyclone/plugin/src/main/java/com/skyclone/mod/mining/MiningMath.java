package com.skyclone.mod.mining;

import java.util.Random;

/**
 * Every mining formula, pure (no Bukkit), so unit tests run the shipping numbers.
 * Sources: research/07 section 2.3 (ticks to break), 2.4 (fortune, pristine, spread),
 * 2.7.3 (perk cost), 2.13 (mineshaft pity) and Appendix A.
 */
public final class MiningMath {

    private MiningMath() {}

    /** Ticks to break: round(strength * 30 / speed), floor of 4 ticks. 0 = instamine. Integer.MAX_VALUE = never. */
    public static int ticksToBreak(double strength, double speed, boolean sixtyRule) {
        if (speed <= 0) return Integer.MAX_VALUE;
        if (instamine(strength, speed, sixtyRule)) return 0;
        long t = Math.round(strength * 30.0 / speed);
        return (int) Math.max(4, Math.min(Integer.MAX_VALUE - 1, t));
    }

    /** Instamine when speed > 30 x strength (standard) or 60 x strength (ores, obsidian, gems, metals). */
    public static boolean instamine(double strength, double speed, boolean sixtyRule) {
        return speed > (sixtyRule ? 60.0 : 30.0) * strength;
    }

    /** Speed needed to reach the 4-tick floor: ceil(20 x strength / 3). */
    public static double speedForFloor(double strength) {
        return Math.ceil(20.0 * strength / 3.0);
    }

    /**
     * Fortune: drops = base x (1 + fortune/100), the fractional part is the chance of one more.
     * {@code roll} in [0,1).
     */
    public static int fortuneDrops(int base, double fortune, double roll) {
        if (base <= 0) return 0;
        double v = base * (1.0 + Math.max(0, fortune) / 100.0);
        int whole = (int) Math.floor(v);
        return whole + (roll < (v - whole) ? 1 : 0);
    }

    /** Expected rough-equivalent per base gem (research 2.4): (1 + P*0.79/100) x (1 + F/100). */
    public static double expectedRough(double pristine, double fortune) {
        return (1.0 + pristine * 0.79 / 100.0) * (1.0 + fortune / 100.0);
    }

    /** How many of {@code count} rough gems turn Flawed with {@code pristine}% each. */
    public static int pristineProcs(int count, double pristine, Random rnd) {
        int n = 0;
        double p = Math.max(0, Math.min(100, pristine)) / 100.0;
        for (int i = 0; i < count; i++) if (rnd.nextDouble() < p) n++;
        return n;
    }

    /** Mining Spread: floor(spread/100) extra blocks plus (spread mod 100)% chance of one more. */
    public static int spreadBlocks(double spread, double roll) {
        if (spread <= 0) return 0;
        int whole = (int) Math.floor(spread / 100.0);
        double frac = (spread - whole * 100.0) / 100.0;
        return whole + (roll < frac ? 1 : 0);
    }

    /** HOTM perk cost of buying {@code nextLevel}: floor((nextLevel + 1) ^ e). */
    public static long perkCost(int nextLevel, double exponent) {
        return (long) Math.floor(Math.pow(nextLevel + 1, exponent) + 1e-9);
    }

    /** Cumulative HOTM XP thresholds from the per-tier table; tier is 1..n. */
    public static int hotmTier(double xp, int[] perTier) {
        double cum = 0;
        int tier = 0;
        for (int need : perTier) {
            cum += need;
            if (xp >= cum) tier++; else break;
        }
        return Math.max(1, tier);
    }

    /** XP needed (cumulative) to REACH tier t (1-based). */
    public static double xpForTier(int tier, int[] perTier) {
        double cum = 0;
        for (int i = 0; i < Math.min(tier, perTier.length); i++) cum += perTier[i];
        return cum;
    }

    /** Mineshaft exit powder (research 2.13): 1,000 + 500/scrap + 250/corpse + 10/cold. */
    public static long mineshaftExitPowder(int scraps, int corpses, int cold) {
        return 1000L + 500L * scraps + 250L * corpses + 10L * Math.max(0, cold);
    }

    /** Divisor of the Mining Fiesta drop chance since the Aug-2026 rework (SkyblockMoney/work/mining.md 4; was 2,000). */
    public static final double FIESTA_DIVISOR = 10_000;

    /** Mining Fiesta Refined Mineral / Glossy Gemstone chance per block: BreakingPower / 10,000 x (1 + bonus). */
    public static double fiestaChance(int breakingPower, double bonusMultiplier) {
        return fiestaChance(breakingPower, bonusMultiplier, FIESTA_DIVISOR);
    }

    /** Mining Fiesta drop chance per block with the divisor from the dial: BreakingPower / divisor x (1 + bonus). */
    public static double fiestaChance(int breakingPower, double bonusMultiplier, double divisor) {
        if (divisor <= 0 || breakingPower <= 0) return 0;
        return breakingPower / divisor * (1.0 + bonusMultiplier);
    }

    /** True when {@code seconds} have passed since {@code last} (null = never happened). */
    public static boolean cooldownOver(Long last, long now, int seconds) {
        return last == null || now - last >= Math.max(0, seconds) * 1000L;
    }

    /** Cold slows mining continuously: 1 - cold x pct/100 (0.5% per Cold: x0.75 at 50, x0.5 at 100), never below 0. */
    public static double coldSpeedMultiplier(int cold, double pctPerPoint) {
        return Math.max(0.0, Math.min(1.0, 1.0 - Math.max(0, cold) * Math.max(0, pctPerPoint) / 100.0));
    }

    /**
     * Forge slots (wiki /The_Forge): none below the unlock tier (2), then one per HOTM tier - 2 at HOTM 2 .. 7 at
     * HOTM 7 - capped at {@code max}. {@code coreSlot} (dial, off by default) adds the old Core of the Mountain II slot.
     */
    public static int forgeSlots(int tier, int unlockTier, int max, boolean coreSlot, int coreLevel) {
        int n = tier < unlockTier ? 0 : Math.min(Math.max(0, max), tier);
        if (n > 0 && coreSlot && coreLevel >= 2) n++;
        return n;
    }

    /**
     * Chance that a bundle of {@code minRolls..maxRolls} rolls (uniform) on a weighted table contains at least one
     * line of weight {@code weight} out of {@code total}: 1 - mean over n of (1 - w/W)^n.
     */
    public static double bundleChance(double weight, double total, int minRolls, int maxRolls) {
        if (total <= 0 || weight <= 0 || maxRolls < minRolls) return 0;
        double p = weight / total, miss = 0;
        for (int n = minRolls; n <= maxRolls; n++) miss += Math.pow(1 - p, n);
        return 1 - miss / (maxRolls - minRolls + 1);
    }

    /**
     * A rare-find chance (treasure goblin, cave worm) with a reforge's "+pct% more often" folded INTO the roll:
     * {@code base x mult x (1 + pct/100)}, so every other multiplier (Cave Luck, Tunnel Vision, Sky Mall x10)
     * multiplies the bonus too. {@code alreadyPaid} is a chance some OTHER roll already pays for the same bonus on
     * the same block (see {@link #reforgesExtraRoll}); it is taken off so the expectation stays exact rather than
     * paying the bonus twice. Clamped to [0, 1].
     */
    public static double findChance(double base, double mult, double pct, double alreadyPaid) {
        double full = Math.max(0, base) * Math.max(0, mult) * (1.0 + Math.max(0, pct) / 100.0);
        return Math.max(0, Math.min(1, full - Math.max(0, alreadyPaid)));
    }

    /**
     * The chance the reforges module's interim extra roll ({@code Bonuses.rareFinds}) pays for a "+pct%" bonus:
     * {@code base x pct / 100}, clamped - the same formula as its {@code extraChance}.
     */
    public static double reforgesExtraRoll(double base, double pct) {
        return Math.max(0, Math.min(1, Math.max(0, base) * Math.max(0, pct) / 100.0));
    }

    /** Uniform integer in [min, max]. */
    public static int between(int min, int max, Random rnd) {
        if (max <= min) return min;
        return min + rnd.nextInt(max - min + 1);
    }

    /** "1,234" style formatting without Bukkit. */
    public static String commas(long v) {
        return String.format(java.util.Locale.US, "%,d", v);
    }

    /** Percent of a commission done, floored and clamped to 0..100; a target of 0 or less counts as done. */
    public static int commissionPercent(int progress, int target) {
        if (target <= 0) return 100;
        return (int) Math.max(0, Math.min(100, Math.floor(100.0 * progress / target)));
    }

    /**
     * The sidebar's one commission line, in slot order (the same order as the tab widget and /commissions):
     * {@code "&9Commissions: &aDONE&7, &e45%"}. One line whatever the slot count (2-4), because the mining
     * section must stay within 3 lines to fit the sidebar's 5 free lines. Empty when there is nothing to show.
     */
    public static String commissionLine(java.util.List<MiningData.Commission> active) {
        if (active == null || active.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("&9Commissions: ");
        boolean first = true;
        for (MiningData.Commission c : active) {
            if (c == null) continue;
            if (!first) sb.append("&7, ");
            first = false;
            sb.append(c.done() ? "&aDONE" : "&e" + commissionPercent(c.progress, c.target) + "%");
        }
        return first ? "" : sb.toString();
    }

    // ---- wave 5 -------------------------------------------------------------------------------------

    /**
     * Private-island Mining Speed: the factor to multiply the player's BLOCK_BREAK_SPEED by so that vanilla, which
     * breaks a block once its per-tick progress has summed to 1, takes exactly {@code ticks} ticks. {@code base} is
     * vanilla's per-tick progress for this block, player and tool WITHOUT our factor ({@code Block#getBreakSpeed}).
     * {@code ticks == 0} (instamine) asks for a whole block in one tick. Clamped to [0.001, 1000] (the attribute caps
     * at 1024). Returns 1 when the base is unusable.
     */
    public static double breakSpeedScale(int ticks, double base) {
        if (!(base > 0) || ticks == Integer.MAX_VALUE || ticks < 0) return 1.0;
        double want = ticks == 0 ? 1.05 : 1.0 / ticks * 1.0001;   // a hair over, so float sums never need one extra tick
        return Math.max(0.001, Math.min(1000.0, want / base));
    }

    /** How many ticks vanilla needs at {@code perTick} progress (what the client will actually take). */
    public static int vanillaTicks(double perTick) {
        if (!(perTick > 0)) return Integer.MAX_VALUE;
        return perTick >= 1.0 ? 0 : (int) Math.ceil(1.0 / perTick - 1e-9);
    }

    static final String[] GEM_QUALITIES = {"ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT"};

    /** "FINE_JADE_GEM" -> 2 (ROUGH 0 .. PERFECT 4), -1 when it is not a gem id. */
    public static int gemQuality(String gemId) {
        if (gemId == null || !gemId.endsWith("_GEM")) return -1;
        for (int i = 0; i < GEM_QUALITIES.length; i++) if (gemId.startsWith(GEM_QUALITIES[i] + "_")) return i;
        return -1;
    }

    /** "FINE_JADE_GEM" -> "JADE", null when it is not a gem id. */
    public static String gemType(String gemId) {
        int q = gemQuality(gemId);
        if (q < 0) return null;
        String rest = gemId.substring(GEM_QUALITIES[q].length() + 1);
        return rest.length() > 4 ? rest.substring(0, rest.length() - 4) : null;
    }

    /**
     * Gemstone Gauntlet "Reduced To Atoms": the rough gems one slotted gem spills from a mob hit. The reference says
     * only "the same Gemstones as your filled slots"; we spill that gem's ROUGH form, one per quality step
     * (Rough 1 .. Perfect 5) times {@code mult}. // TUNE: research has no amount.
     */
    public static int gauntletSpill(String gemId, double mult) {
        int q = gemQuality(gemId);
        if (q < 0) return 0;
        return (int) Math.max(0, Math.round((q + 1) * Math.max(0, mult)));
    }

    /**
     * Which crystal a crystal-caverns structure holds, from its anchor or zone name (reference: Jungle Temple ->
     * Amethyst, Mines of Divan -> Jade, Lost Precursor City -> Sapphire, Goblin Queen's Den / King -> Amber,
     * Khazad-dum -> Topaz). Matches our names and the reference's; null for anything else.
     */
    public static String structureGem(String name) {
        if (name == null) return null;
        String s = name.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("jungle") || s.contains("temple")) return "AMETHYST";
        if (s.contains("varnas") || s.contains("divan")) return "JADE";
        if (s.contains("relic") || s.contains("precursor")) return "SAPPHIRE";
        if (s.contains("goblin")) return "AMBER";
        if (s.contains("ember") || s.contains("khazad") || s.contains("magmaw") || s.contains("bal_")) return "TOPAZ";
        return null;
    }

    /**
     * A treasure chest line that belongs to a zone (robot parts to the precursor ruins, goblin eggs to the goblin
     * holdout...) is {@code boost} times as likely in its own zone and {@code off} times as likely elsewhere.
     * Lines with no zone keep their weight. // TUNE: research lists the loot, not the per-zone odds.
     */
    public static double zoneWeight(double weight, String lineZoneGem, String chestZoneGem, double boost, double off) {
        if (lineZoneGem == null || lineZoneGem.isEmpty()) return Math.max(0, weight);
        return Math.max(0, weight) * Math.max(0, lineZoneGem.equalsIgnoreCase(chestZoneGem) ? boost : off);
    }

    /**
     * Chisel gem slots (research 2.14). Aquamarine: chance of +1 charge at the start, 30% (Rough) to 100% (Perfect),
     * linear between - the research gives exactly those two ends. Citrine: treasure cells highlighted (1..5 by
     * quality). Onyx: extra treasure rolls (1,1,2,2,3). Peridot: +% Fossil Essence (5..25). The last three are
     * // TUNE: the research names the effect, not the number. Unknown / non-gem ids give 0.
     */
    public static double chiselAquamarine(String gemId) { int q = gemQuality(gemId); return q < 0 ? 0 : 0.30 + 0.175 * q; }

    public static int chiselCitrine(String gemId) { int q = gemQuality(gemId); return q < 0 ? 0 : q + 1; }

    public static int chiselOnyx(String gemId) { int q = gemQuality(gemId); return q < 0 ? 0 : (q + 2) / 2; }

    public static double chiselPeridot(String gemId) { int q = gemQuality(gemId); return q < 0 ? 0 : 5.0 * (q + 1); }

    /** Vanilla experience range of an ore block (by material name), {0,0} for anything that drops none. */
    public static int[] oreExp(String material) {
        if (material == null) return new int[]{0, 0};
        String m = material.toUpperCase(java.util.Locale.ROOT);
        if (m.endsWith("_BLOCK") || !m.contains("ORE")) return new int[]{0, 0};
        if (m.contains("COAL")) return new int[]{0, 2};
        if (m.contains("DIAMOND") || m.contains("EMERALD")) return new int[]{3, 7};
        if (m.contains("LAPIS") || m.contains("QUARTZ")) return new int[]{2, 5};
        if (m.contains("REDSTONE")) return new int[]{1, 5};
        if (m.contains("NETHER_GOLD")) return new int[]{0, 1};
        return new int[]{0, 0};   // iron / gold / copper ore drop raw metal and no experience
    }

    // ---- Goblin Raid (wave-5 regression fix) ---------------------------------------------------

    /**
     * The reference's "Goblin Raiders" bestiary family (NEU constants/bestiary.json, mining_3): two Lv5
     * weaklings (melee + bow) are the bulk of a raid, with a Lv60 Battler, a Lv90 Creepertamer and two
     * Lv150 heavies (Murderlover = our Goblin Cutthroat, and the Golem) mixed in. Weights are ours. TUNE
     */
    public static final String[] RAID_IDS = {
            "mining_raid_weakling", "mining_raid_weakling_bow", "mining_raid_battler",
            "mining_raid_creepertamer", "mining_raid_cutthroat", "mining_raid_golem"};
    /** Spawn weight per {@link #RAID_IDS} entry. TUNE */
    public static final double[] RAID_WEIGHTS = {0.40, 0.30, 0.14, 0.08, 0.04, 0.04};
    /** Entries from this index on are "heavies" (Lv90+), of which only a few may be alive at once. */
    public static final int RAID_FIRST_HEAVY = 3;
    /** Mining districts a raid can hit (reference: the boss bar names the raided area). The Goblin Burrows
     *  already teems with goblins and the Great Ice Wall is Ice Walker ground, so neither is raided. TUNE */
    public static final java.util.List<String> RAID_ZONES = java.util.List.of(
            "royal_mines", "cliffside_veins", "ramparts_quarry", "upper_mines", "far_reserve", "lava_springs");

    /** Which raider a roll in [0,1) picks; with {@code heavyAllowed} false the roll is spread over the light ones only. */
    public static String raidPick(double roll, boolean heavyAllowed) {
        int n = heavyAllowed ? RAID_IDS.length : RAID_FIRST_HEAVY;
        double total = 0;
        for (int i = 0; i < n; i++) total += RAID_WEIGHTS[i];
        double x = Math.max(0, Math.min(0.999999, roll)) * total;
        for (int i = 0; i < n; i++) { x -= RAID_WEIGHTS[i]; if (x < 0) return RAID_IDS[i]; }
        return RAID_IDS[n - 1];
    }

    public static boolean raidHeavy(String id) {
        for (int i = RAID_FIRST_HEAVY; i < RAID_IDS.length; i++) if (RAID_IDS[i].equals(id)) return true;
        return false;
    }

    /** How many raiders the next wave adds: up to {@code wave}, never past {@code cap} alive. */
    public static int raidWave(int alive, int cap, int wave) {
        return Math.max(0, Math.min(Math.max(0, wave), cap - Math.max(0, alive)));
    }
}
