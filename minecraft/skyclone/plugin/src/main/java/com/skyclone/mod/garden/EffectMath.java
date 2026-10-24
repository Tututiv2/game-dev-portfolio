package com.skyclone.mod.garden;

/**
 * Pure numbers behind the garden's accessory, equipment, pet and reforge effects (wave 5). No Bukkit,
 * so the unit tests run them as they are.
 */
public final class EffectMath {

    private EffectMath() {}

    // ---- Lotus (our "Peony") and Blossom equipment: "Complete Garden Visitor Offers to gain Farming Fortune" ----

    /**
     * Visitors served (while the piece is worn) at which a piece gains one more step of Farming Fortune.
     * Fitted to the reference items' own lore in research/data/auctions_page0.json: a Peony piece shows
     * "+7, next +8 at 150" with 117 served and "+9, next +10 at 500" with 480; a Blossom piece "+18 (12 steps),
     * next at 1,500" with 1,244 and "MAXED OUT" (15 steps) with 2,544. 750 and 2,000 are the only two thresholds
     * the samples do not pin. // TUNE
     */
    public static final int[] PIECE_THRESHOLDS = {1, 5, 10, 25, 50, 75, 100, 150, 250, 500, 750, 1000, 1500, 2000, 2500};

    /** Farming Fortune one step is worth: Peony (LOTUS_*) +1, Blossom +1.5 (reference lore "Next Upgrade: +1 / +1.5"). */
    public static double pieceStep(String itemId) {
        if (itemId == null) return 0;
        if (itemId.startsWith("LOTUS_")) return 1.0;
        if (itemId.startsWith("BLOSSOM_")) return 1.5;
        return 0;
    }

    public static boolean isGrowingPiece(String itemId) { return pieceStep(itemId) > 0; }

    /** Steps reached with {@code served} visitors served. */
    public static int pieceSteps(long served) {
        int n = 0;
        for (int t : PIECE_THRESHOLDS) if (served >= t) n++;
        return n;
    }

    public static double pieceBonus(String itemId, long served) { return pieceSteps(served) * pieceStep(itemId); }

    /** The next threshold, or -1 when maxed. */
    public static int nextThreshold(long served) {
        for (int t : PIECE_THRESHOLDS) if (served < t) return t;
        return -1;
    }

    // ---- talisman families ---------------------------------------------------------------------------

    /** Copper Talisman / Ring / Artifact: RARE-or-better visitors arrive 4 / 8 / 12% more often (NEU lore). */
    public static final double[] COPPER_PCT = {0, 4, 8, 12};
    /** Gratitude Talisman / Ring / Artifact: visitors 1 / 3 / 5% faster while you are in the Garden (NEU lore). */
    public static final double[] GRATITUDE_PCT = {0, 1, 3, 5};
    /** Bioanalysis Talisman / Ring / Artifact: crops 5 / 10 / 15% more likely to mutate (NEU lore). */
    public static final double[] BIOANALYSIS_PCT = {0, 5, 10, 15};

    /** Value for a family tier (0 = none owned, 1..3 = highest owned member), clamped. */
    public static double familyValue(double[] table, int tier) {
        return table[Math.max(0, Math.min(table.length - 1, tier))];
    }

    // ---- pests ---------------------------------------------------------------------------------------

    /**
     * A pest's guaranteed crop drop (lane 9, G1; research farming_passive.md 1.3 and the wiki's pest pages):
     * {@code base + fortune / scale} ENCHANTED crops (Fly at 2,520 Farming Fortune: 1 + 2520/52.5 = 49). The fraction
     * is paid as a chance at one more, so the average is exact. {@code mult} is the {@code garden.pest-crop-mult} dial.
     */
    public static long pestEnchantedCrops(double base, double scale, double fortune, double mult, double roll) {
        if (scale <= 0 && base <= 0) return 0;
        double raw = (Math.max(0, base) + (scale > 0 ? Math.max(0, fortune) / scale : 0)) * Math.max(0, mult);
        long whole = (long) Math.floor(raw);
        return whole + (roll < raw - whole ? 1 : 0);
    }

    // ---- Sowdust and chip levels (lane 13; wiki "Sowdust" and "Garden Chips") ------------------------------

    /**
     * Sowdust for one Garden crop block broken: {@code ceil(totalFortune / 100)}, halved for Sugar Cane and Cactus,
     * times the boosts ({@code boostPct}: the Elephant's Abundant Harvest, the Earthy reforge's +5%).
     */
    public static double cropSowdust(double totalFortune, boolean halved, double boostPct) {
        double v = Math.ceil(Math.max(0, totalFortune) / 100.0);
        if (halved) v /= 2.0;
        return v * (1 + Math.max(0, boostPct) / 100.0);
    }

    /** Sowdust for one greenhouse harvest: {@code 5 x round(base / 5 x cropMult) x (1 + fortune/100)}, times the boosts. */
    public static double greenhouseSowdust(double baseCrops, double cropMult, double totalFortune, double boostPct) {
        double v = 5 * Math.round(Math.max(0, baseCrops) / 5.0 * Math.max(0, cropMult)) * (1 + Math.max(0, totalFortune) / 100.0);
        return v * (1 + Math.max(0, boostPct) / 100.0);
    }

    /** Chip rarity index (0 Rare, 1 Epic, 2 Legendary) for {@code redeemed} chips; -1 when none is redeemed. */
    public static int chipRarity(int redeemed, int[] chipsFor) {
        int r = -1;
        for (int i = 0; i < chipsFor.length; i++) if (redeemed >= chipsFor[i]) r = i;
        return r;
    }

    /** Highest level a chip of rarity {@code rarity} may reach (0 when not redeemed). */
    public static int chipCap(int rarity, int[] caps) {
        if (rarity < 0 || caps.length == 0) return 0;
        return caps[Math.min(rarity, caps.length - 1)];
    }

    /** Sowdust to go from {@code level} to {@code level + 1} ({@code costs[0]} buys level 2), or -1 past the table. */
    public static long chipLevelCost(int level, long[] costs) {
        int i = level - 1;
        return level < 1 || i >= costs.length ? -1 : costs[i];
    }

    /** Total Sowdust from level 1 to {@code level}. */
    public static long chipCostTo(int level, long[] costs) {
        long t = 0;
        for (int l = 1; l < level && l - 1 < costs.length; l++) t += costs[l - 1];
        return t;
    }

    /** Cropie Squashbuckle in the Greenhouse: chance (0-1) of a Squash per crop harvested with {@code pieces} worn. */
    public static double greenhouseSquash(int pieces, double[] pctByPieces) {
        if (pctByPieces == null || pctByPieces.length == 0) return 0;
        return pctByPieces[Math.max(0, Math.min(pctByPieces.length - 1, pieces))] / 100.0;
    }

    /** Pests handed to Phillip for the Pesthunter's Bonus: all of them, at most {@code maxPests}. */
    public static long pestsToHandIn(long balance, int maxPests) { return Math.max(0, Math.min(balance, maxPests)); }

    /** Pesthunter's equipment "Eradicator": fortune against pests for {@code pieces} worn (0-4). // TUNE */
    public static double eradicatorFortune(int pieces, double perPiece) {
        return Math.max(0, Math.min(4, pieces)) * perPiece;
    }

    /** Overbloom raises a rare drop's chance: {@code chance x (1 + overbloom/100)} (research 08 1.12). */
    public static double overbloomChance(double chance, double overbloom) {
        return chance * (1 + Math.max(0, overbloom) / 100.0);
    }

    /**
     * The EXTRA chance to roll when the base roll already happened elsewhere (core rolls pest drops at their
     * base chance): {@code P(extra) = (c*(1+o) - c) / (1 - c)} so the two rolls together hit {@code c*(1+o)}.
     */
    public static double topUpChance(double chance, double overbloom) {
        double want = Math.min(1.0, overbloomChance(chance, overbloom));
        if (chance >= 1 || want <= chance) return 0;
        return (want - chance) / (1 - chance);
    }

    /** Rose Dragon "Rosy Scales": per crop milestone tier reached, across every crop. */
    public static double perMilestone(double perTier, int tiers) { return Math.max(0, perTier) * Math.max(0, tiers); }

    // ---- garden chips (the reference chips' own lore, NEU items/*_GARDEN_CHIP.json) ------------------------

    /** Hypercharge: temporary Farming Fortune buffs are {@code pct}% stronger, i.e. that much more of them. */
    public static double hypercharge(double temporaryFortune, double pct) {
        return Math.max(0, temporaryFortune) * Math.max(0, pct) / 100.0;
    }

    /**
     * Quickdraw: "decreases the time for Visitors to appear when harvesting crops by N%". While someone is harvesting,
     * every {@code elapsedMillis} of the visitor clock also takes {@code elapsed x N/(100-N)} off the wait, so a wait
     * spent harvesting throughout is exactly N% shorter. Capped at 90%.
     */
    public static long quickdrawExtraMillis(long elapsedMillis, double pct) {
        double f = Math.max(0, Math.min(90, pct)) / 100.0;
        return Math.round(Math.max(0, elapsedMillis) * f / (1 - f));
    }

    /** Pestwarden's Bonus: Farming Fortune for handing in {@code pests} Pests, capped (wiki: 5 a Pest, at most 200). */
    public static double pesthunterBonus(long pests, double perPest, double cap) {
        return Math.max(0, Math.min(Math.max(0, cap), Math.max(0, pests) * Math.max(0, perPest)));
    }

    /** Mosquito "Buzzing Trader": Sugar Cane Fortune per unique visitor served, capped at 175 (reference lore). */
    public static double buzzinBarterer(double perVisitor, int unique) { return Math.min(175, Math.max(0, perVisitor) * Math.max(0, unique)); }
}
