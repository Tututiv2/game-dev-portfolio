package com.skyclone.mod.fishing;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Every fishing formula, as pure functions (no Bukkit) so unit tests and self-tests check the
 * research numbers directly. References are to research/07 §3.
 */
public final class FishMath {

    private FishMath() {}

    /** A random source in [0,1). Lets tests feed fixed rolls. */
    @FunctionalInterface
    public interface Rng { double next(); }

    // ---- §3.2 Fishing Speed -------------------------------------------------------------------

    /**
     * Longest base wait in ticks: 400, cut by Lure ("cuts the longest possible wait by v%"), never
     * below the shortest base wait (200).
     */
    public static int maxBaseTicks(double lurePercent) {
        double max = 400.0 * (1.0 - Math.max(0, Math.min(100, lurePercent)) / 100.0);
        return (int) Math.max(200, Math.round(max));
    }

    /** BaseTicks = uniform(200, maxBase). */
    public static int baseTicks(Rng rng, double lurePercent) {
        int max = maxBaseTicks(lurePercent);
        return 200 + (int) Math.floor(rng.next() * (max - 200 + 1));
    }

    /**
     * Ticks = BaseTicks x (1 - FS / Cap), 0 once FS reaches the cap (research: "if FS >= Cap:
     * Ticks = 0"). {@code minTicks} is the floor the game enforces (a dial; 1 by default).
     */
    public static int waitTicks(int baseTicks, double fishingSpeed, double cap, int minTicks) {
        if (cap <= 0) return Math.max(minTicks, baseTicks);
        double f = Math.max(0, fishingSpeed) / cap;
        int t = f >= 1 ? 0 : (int) Math.round(baseTicks * (1 - f));
        return Math.max(minTicks, t);
    }

    /** Average wait in seconds (for menus): midpoint of the base range, scaled by speed. */
    public static double averageWaitSeconds(double fishingSpeed, double cap, double lurePercent) {
        double mid = (200 + maxBaseTicks(lurePercent)) / 2.0;
        return waitTicks((int) Math.round(mid), fishingSpeed, cap, 0) / 20.0;
    }

    // ---- §3.3 chances -------------------------------------------------------------------------

    /** Sea Creature Chance as a probability: SCC% capped at 100, divided on penalised islands. */
    public static double seaCreatureProbability(double scc, double locationDivisor) {
        double v = Math.max(0, scc) / Math.max(1, locationDivisor);
        return Math.min(100, v) / 100.0;
    }

    /** A plain "X% chance" stat (Treasure, Double Hook) as a probability, capped at 100%. */
    public static double percent(double stat) {
        return Math.max(0, Math.min(100, stat)) / 100.0;
    }

    /** Weighted pick. Returns null for an empty or all-zero list. */
    public static <T> T pick(List<T> entries, ToDoubleFunction<T> weight, Rng rng) {
        double total = 0;
        for (T e : entries) total += Math.max(0, weight.applyAsDouble(e));
        if (total <= 0) return null;
        double r = rng.next() * total;
        for (T e : entries) {
            double w = Math.max(0, weight.applyAsDouble(e));
            if (r < w) return e;
            r -= w;
        }
        return entries.get(entries.size() - 1);
    }

    /** P(entry) = weight / sum, as a fraction. */
    public static <T> double share(List<T> entries, ToDoubleFunction<T> weight, T which) {
        double total = 0;
        for (T e : entries) total += Math.max(0, weight.applyAsDouble(e));
        return total <= 0 ? 0 : Math.max(0, weight.applyAsDouble(which)) / total;
    }

    /** A replacement pool's chance: base + bonuses, capped (shark 80%, hotspot 85% - research §3.3/§3.8). */
    public static double replaceChance(double base, double bonus, double cap) {
        return Math.max(0, Math.min(cap, base + bonus));
    }

    // ---- §3.5 treasure ------------------------------------------------------------------------

    /**
     * Treasure quality: "good", "great" or "outstanding" from the base weights; Blessed Bait /
     * Blessing raise the chance of great-or-better by {@code blessed} (0.5 = +50%).
     */
    public static String treasureQuality(Map<String, Double> weights, double blessed, Rng rng) {
        return treasureQuality(weights, blessed, 0, 0, rng);
    }

    /**
     * Same, with the separate "x% more likely to be GREAT" / "... OUTSTANDING" bonuses some sources give
     * (hunting attributes Mantis Shrimp / Blue Crab, Litterbug / Maggot on junk): fractions, 0.1 = +10%.
     */
    public static String treasureQuality(Map<String, Double> weights, double blessed, double greatOnly, double outOnly, Rng rng) {
        double good = weights.getOrDefault("good", 85.0);
        double b = 1 + Math.max(0, blessed);
        double great = weights.getOrDefault("great", 13.0) * b * (1 + Math.max(0, greatOnly));
        double out = weights.getOrDefault("outstanding", 2.0) * b * (1 + Math.max(0, outOnly));
        double r = rng.next() * (good + great + out);
        if (r < out) return "outstanding";
        if (r < out + great) return "great";
        return "good";
    }

    /** P(quality) for the same weights (menus and tests). */
    public static double treasureShare(Map<String, Double> weights, double blessed, double greatOnly, double outOnly, String q) {
        double good = weights.getOrDefault("good", 85.0);
        double b = 1 + Math.max(0, blessed);
        double great = weights.getOrDefault("great", 13.0) * b * (1 + Math.max(0, greatOnly));
        double out = weights.getOrDefault("outstanding", 2.0) * b * (1 + Math.max(0, outOnly));
        double t = good + great + out;
        return switch (q) { case "outstanding" -> out / t; case "great" -> great / t; default -> good / t; };
    }

    // ---- wave 5: Puddle Jumper ride, geysers, thunder bottles, husks -----------------------------

    /**
     * Where a Puddle Jumper is, relative to where it took off, {@code t} (0..1) through its leap: a
     * three-quarter loop of {@code radius} round the take-off point, rising to {@code height} at the
     * middle and landing back at take-off height. Returns {dx, dy, dz}. Pure, so the ride is testable.
     */
    public static double[] rideOffset(double t, double radius, double height) {
        double c = Math.max(0, Math.min(1, t));
        double a = c * Math.PI * 1.5;                            // round a circle whose edge is the take-off point
        return new double[]{radius * (1 - Math.cos(a)), height * Math.sin(c * Math.PI), -radius * Math.sin(a)};
    }

    /**
     * Choose up to {@code n} geyser sites from candidate columns {x, z}: candidates are taken in a fixed
     * hashed order (so the same lava gives the same geysers on every boot) and each must be at least
     * {@code spacing} blocks from every site already chosen.
     */
    public static List<int[]> pickSites(List<int[]> candidates, int n, double spacing) {
        List<int[]> sorted = new java.util.ArrayList<>(candidates);
        sorted.sort(java.util.Comparator.comparingLong(c -> mix(c[0] * 31L + c[1] * 1_000_003L)));
        List<int[]> out = new java.util.ArrayList<>();
        double s2 = spacing * spacing;
        for (int[] c : sorted) {
            if (out.size() >= n) break;
            boolean ok = true;
            for (int[] o : out) {
                double dx = c[0] - o[0], dz = c[1] - o[1];
                if (dx * dx + dz * dz < s2) { ok = false; break; }
            }
            if (ok) out.add(c);
        }
        return out;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /** Thunder charge one slain lava sea creature adds: its Fishing XP x mult, +1% per Pulse Ring (the reference). */
    public static long thunderCharge(double fishingXp, double mult, boolean pulseRing) {
        double v = Math.max(0, fishingXp) * Math.max(0, mult);
        if (pulseRing) v *= 1.01;
        return Math.round(v);
    }

    /** Slugfish wait (ms): 20 s, cut by the Slug pet's "a% less time" (capped at 90%). */
    public static long slugPatienceMs(double baseMs, double petPercent) {
        return Math.round(baseMs * (1 - Math.max(0, Math.min(90, petPercent)) / 100.0));
    }

    // ---- §3.7 trophy fishing ------------------------------------------------------------------

    /** chance = base% x (1 + TrophyChance/100), as a probability (research §3.7). */
    public static double trophyChance(double basePercent, double trophyChanceStat) {
        return Math.max(0, Math.min(1, basePercent / 100.0 * (1 + Math.max(0, trophyChanceStat) / 100.0)));
    }

    /** Mana Ray: needs 1,200 mana; chance = mana / 1000 percent (1.2% at 1,200). */
    public static double manaRayChance(double mana, double trophyChanceStat) {
        if (mana < 1200) return 0;
        return trophyChance(mana / 1000.0, trophyChanceStat);
    }

    /** Golden Fish: 0 before {@code startMin}, rising linearly to 100% at {@code fullMin} minutes of lava fishing. */
    public static double goldenFishChance(double minutesFishing, double startMin, double fullMin) {
        if (minutesFishing < startMin) return 0;
        if (minutesFishing >= fullMin) return 1;
        return (minutesFishing - startMin) / Math.max(0.0001, fullMin - startMin);
    }

    /**
     * Trophy tier with pity (research §3.7): Diamond 0.2%, Gold 2%, Silver 25%, else Bronze;
     * guaranteed Gold on the 100th catch without one, Diamond on the 600th. {@code charm} is the
     * Charm enchant's bonus fraction (0.12 = +12%) on the three higher tiers.
     *
     * @param sinceGold    trophy catches since the last Gold-or-better
     * @param sinceDiamond trophy catches since the last Diamond
     */
    public static String trophyTier(Rng rng, int sinceGold, int sinceDiamond, double charm,
                                    Map<String, Double> chances, Map<String, Integer> pity) {
        return trophyTier(rng, sinceGold, sinceDiamond, charm, 0, chances, pity);
    }

    /**
     * As above, plus {@code goldDiamond}: an extra multiplier (fraction) on the Gold and Diamond odds only
     * (Spinosaurus "Trophy Chase": "Gold and Diamond trophy fish are a% more likely").
     */
    public static String trophyTier(Rng rng, int sinceGold, int sinceDiamond, double charm, double goldDiamond,
                                    Map<String, Double> chances, Map<String, Integer> pity) {
        int pd = pity.getOrDefault("DIAMOND", 600);
        int pg = pity.getOrDefault("GOLD", 100);
        if (sinceDiamond + 1 >= pd) return "DIAMOND";
        double m = 1 + Math.max(0, charm);
        double gd = 1 + Math.max(0, goldDiamond);
        double d = chances.getOrDefault("DIAMOND", 0.002) * m * gd;
        double g = chances.getOrDefault("GOLD", 0.02) * m * gd;
        double s = chances.getOrDefault("SILVER", 0.25) * m;
        double r = rng.next();
        if (r < d) return "DIAMOND";
        if (sinceGold + 1 >= pg) return "GOLD";
        if (r < d + g) return "GOLD";
        if (r < d + g + s) return "SILVER";
        return "BRONZE";
    }

    /** Magmafish from filleting one trophy fish of this tier. */
    public static int fillet(Map<String, Integer> fillet, String tier) {
        Integer v = fillet == null ? null : fillet.get(tier.toLowerCase(java.util.Locale.ROOT));
        return v == null ? 0 : v;
    }

    // ---- misc ---------------------------------------------------------------------------------

    /** "Fortune"-style rounding: 2.4 -> 2 plus a 40% chance of a third. */
    public static int fortuneRound(double v, Rng rng) {
        int whole = (int) Math.floor(v);
        return whole + (rng.next() < v - whole ? 1 : 0);
    }

    public static int between(int min, int max, Rng rng) {
        if (max <= min) return min;
        return min + (int) Math.floor(rng.next() * (max - min + 1));
    }

    // ---- chum buckets (reference lore: "+1 Chum, up to +4 total from buckets"; Glowy Chum Bait "+2") ----------

    /**
     * Chum a slain sea creature drops: +{@code perBucket} for every placed bucket in reach, at most {@code cap}
     * from buckets, plus the bait's own chum (Glowy Chum Bait) - which, like the buckets', only drops when at
     * least one bucket is in reach unless {@code baitNeedsBucket} is false ("Obtained from killing a sea creature
     * with a nearby Chum Bucket").
     */
    public static int chumDrop(int bucketsInReach, int perBucket, int cap, int baitChum, boolean baitNeedsBucket) {
        int fromBuckets = Math.max(0, Math.min(Math.max(0, bucketsInReach) * Math.max(0, perBucket), Math.max(0, cap)));
        int bait = Math.max(0, baitChum);
        if (baitNeedsBucket && bucketsInReach <= 0) bait = 0;
        return fromBuckets + bait;
    }

    /** Fishing Speed from Chumcap Buckets in reach: +{@code per} each, at most {@code cap} (reference: +4, up to +16). */
    public static double chumcapSpeed(int chumcapsInReach, double per, double cap) {
        return Math.max(0, Math.min(Math.max(0, chumcapsInReach) * per, cap));
    }

    /**
     * Buckets a profile recorded as placed but that are not standing any more (multiset difference
     * recorded - standing): each is handed back once. A standing bucket with no record never pays out.
     */
    public static List<String> missingBuckets(List<String> recorded, List<String> standing) {
        List<String> left = new java.util.ArrayList<>(standing);
        List<String> out = new java.util.ArrayList<>();
        for (String r : recorded) if (!left.remove(r)) out.add(r);
        return out;
    }

    /** {coins, fishingXp} for depositing {@code chum} into a bucket; the Luminous Bracelet doubles it into someone else's. */
    public static double[] chumDeposit(int chum, double coinsEach, double xpEach, boolean othersBucket, boolean bracelet,
                                       double braceletCoins, double braceletXp) {
        int n = Math.max(0, chum);
        double coins = n * coinsEach, xp = n * xpEach;
        if (othersBucket && bracelet) { coins += n * braceletCoins; xp += n * braceletXp; }
        return new double[]{coins, xp};
    }

    // ---- audit 2026-10-06: Moby-Duck, the Bayou guide's chapters -------------------------------

    /** A carried Moby-Duck's bonus after aging by {@code add}, capped at {@code max} (it evolves there). */
    public static double mobyAge(double current, double add, double max) {
        // rounded to 1e-6: 0.01 steps summed in floating point must reach the cap exactly
        double v = Math.round((Math.max(0, current) + Math.max(0, add)) * 1e6) / 1e6;
        return Math.min(max, v);
    }

    /** Fishing Wisdom from the Moby-Duck right now: the read buff while it lasts, plus Bookworm for good. */
    public static double mobyWisdom(double buff, long until, long now, boolean bookworm, double bookwormWisdom) {
        return (now < until ? Math.max(0, buff) : 0) + (bookworm ? bookwormWisdom : 0);
    }

    /** "12.3" (one decimal, no trailing ".0" for whole numbers). */
    public static String oneDecimal(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    static final List<String> RARITY_ORDER = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    /** Is {@code have} at least {@code need}? Unknown rarities never pass. */
    public static boolean rarityAtLeast(String have, String need) {
        if (have == null || need == null) return false;
        int h = RARITY_ORDER.indexOf(have.toUpperCase(java.util.Locale.ROOT)), n = RARITY_ORDER.indexOf(need.toUpperCase(java.util.Locale.ROOT));
        return h >= 0 && n >= 0 && h >= n;
    }

    /**
     * The chapter a player is on: {@code claimed} chapters are done; returns the index of the next one, or
     * {@code chapters} when every chapter has been claimed.
     */
    public static int currentChapter(int claimed, int chapters) {
        return Math.max(0, Math.min(claimed, chapters));
    }
}
