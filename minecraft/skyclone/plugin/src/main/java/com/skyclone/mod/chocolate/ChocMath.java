package com.skyclone.mod.chocolate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * Every Chocolate Factory formula, pure (no Bukkit), so unit tests and self-tests check the same numbers the game
 * uses. Numbers are from research/08 section 6.6 and the generated {@code factory.json}; design values are // TUNE.
 */
public final class ChocMath {

    private ChocMath() {}

    public static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"};

    public static int rarityRank(String rarity) {
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(rarity)) return i;
        return 0;
    }

    // ---- costs ----------------------------------------------------------------------------------------------

    /** Chocolate to take an employee from {@code level} to {@code level + 1} (0 -> 1 is the hire cost). */
    public static double employeeCost(ChocCatalog.Employee e, double growth, int level) {
        return Math.ceil(e.baseCost * Math.pow(growth, Math.max(0, level)));
    }

    /** Cost of {@code n} levels in a row starting at {@code level}. */
    public static double employeeCost(ChocCatalog.Employee e, double growth, int level, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += employeeCost(e, growth, level + i);
        return sum;
    }

    /** Employee level cap: base + per-prestige (CF1 120 ... CF6 220), plus Rabbit Youth for Bro / Cousin / Sis. TUNE. */
    public static int employeeCap(ChocCatalog.Factory f, ChocCatalog.Employee e, int prestige, int youth) {
        return f.levelCapBase + f.levelCapPerPrestige * Math.max(1, prestige) + (e.youth ? Math.max(0, youth) : 0);
    }

    /** Hand-Baked Chocolate: reaching level L costs {@code 500 x (L - 1)} (research/08 6.6). */
    public static double handBakedCost(ChocCatalog.Upgrade u, int currentLevel) {
        return u.costPerLevel * Math.max(1, currentLevel);
    }

    /** Barn capacity at a level (level 1 = 20, +2 per level). */
    public static int barnCapacity(ChocCatalog.Upgrade barn, int level) {
        return barn.start + (int) Math.round(barn.perLevel) * Math.max(0, level - 1);
    }

    /** Barn level L -> L+1 (L2 costs 5,000 per research/08 6.6; growth TUNE). */
    public static double barnCost(ChocCatalog.Upgrade barn, int level) {
        return Math.ceil(barn.baseCost * Math.pow(barn.growth, Math.max(0, level - 1)));
    }

    /** Time Tower / Shrine / Coach level L -> L+1 (0 -> 1 is the base cost). TUNE growth. */
    public static double upgradeCost(ChocCatalog.Upgrade u, int level) {
        return Math.ceil(u.baseCost * Math.pow(u.growth, Math.max(0, level)));
    }

    public static double hitmanSlotCost(ChocCatalog.Factory f, int ownedSlots) {
        if (ownedSlots < 0 || ownedSlots >= f.hitmanCosts.size()) return -1;
        return f.hitmanCosts.get(ownedSlots);
    }

    // ---- production -----------------------------------------------------------------------------------------

    /** Prestige bonus to the multiplier (CF1 0, CF2 +0.1 ... CF6 +1.5 from NEU prestigeMultipliers). */
    public static double prestigeBonus(ChocCatalog catalog, int prestige) {
        ChocCatalog.Prestige p = catalog.prestige(prestige);
        return p == null ? 0 : p.bonus;
    }

    /** Chocolate needed THIS prestige to reach {@code next}, or -1 at the top. */
    public static double prestigeRequirement(ChocCatalog catalog, int next) {
        ChocCatalog.Prestige p = catalog.prestige(next);
        return p == null ? -1 : p.requirement;
    }

    /** Sum of employee levels x their CpS. */
    public static double employeeCps(ChocCatalog.Factory f, Map<String, Integer> levels) {
        double s = 0;
        for (ChocCatalog.Employee e : f.employees) s += e.cps * (double) levels.getOrDefault(e.id, 0);
        return s;
    }

    /** CpS and multiplier added by the rabbits found. */
    public static double[] rabbitBonus(ChocCatalog catalog, Collection<String> found) {
        double cps = 0, mult = 0;
        for (String id : found) {
            ChocCatalog.Rabbit r = catalog.rabbit(id);
            if (r == null) continue;
            cps += r.cps;
            mult += r.mult;
        }
        return new double[]{cps, mult};
    }

    /** Value of a mythic ability the player owns (0 when the rabbit is missing). */
    public static double ability(ChocCatalog catalog, Collection<String> found, String key) {
        double v = 0;
        for (String id : found) {
            ChocCatalog.Rabbit r = catalog.rabbit(id);
            if (r != null && key.equals(r.ability)) v += r.abilityValue;
        }
        return v;
    }

    /**
     * Everything that feeds the per-second number. {@code base x multiplier x speed}: multiplier is the genre's
     * "1.000x" (1 + prestige + rabbits + coach + cookie + Time Tower + mixin + extra), speed is the Chocolate Speed
     * attribute (+0.25% per level).
     */
    public record Production(double employeeCps, double rabbitCps, double talismanCps, double shardCps,
                             double prestigeBonus, double rabbitMult, double coachBonus, double cookieBonus,
                             double timeTowerBonus, double mixinBonus, double extraMult, double speedBonus,
                             double scale) {
        public double baseCps() { return employeeCps + rabbitCps + talismanCps + shardCps; }
        public double multiplier() {
            return 1.0 + prestigeBonus + rabbitMult + coachBonus + cookieBonus + timeTowerBonus + mixinBonus + extraMult;
        }
        /** The multiplier with the Time Tower switched off (offline catch-up after it expired). */
        public double multiplierWithoutTower() { return multiplier() - timeTowerBonus; }
        public double cps() { return baseCps() * multiplier() * (1 + speedBonus) * scale; }
        public double cpsWithoutTower() { return baseCps() * multiplierWithoutTower() * (1 + speedBonus) * scale; }
    }

    /**
     * Chocolate made between two instants given when the Time Tower ends. Splits the window so an offline
     * catch-up pays the tower only for the part of the window it was really running.
     */
    public static double produce(Production p, long fromMs, long toMs, long towerUntilMs) {
        if (toMs <= fromMs) return 0;
        long towerEnd = Math.min(toMs, Math.max(fromMs, towerUntilMs));
        double towerSec = (towerEnd - fromMs) / 1000.0;
        double plainSec = (toMs - towerEnd) / 1000.0;
        double withTower = p.timeTowerBonus > 0 ? p.cps() : p.cpsWithoutTower();
        return towerSec * withTower + plainSec * p.cpsWithoutTower();
    }

    // ---- rabbits from eggs ----------------------------------------------------------------------------------

    /**
     * Rarity weights for one egg: base per-mille odds; the Rabbit Shrine multiplies RARE and up by
     * {@code 1 + shrine}; each Rabbit Luck attribute multiplies its own rarity by {@code 1 + luck}.
     */
    public static double[] eggWeights(Map<String, Integer> odds, double shrineBonus, double legendaryLuck,
                                       double mythicLuck, double divineLuck) {
        double[] w = new double[RARITIES.length];
        for (int i = 0; i < RARITIES.length; i++) {
            double base = odds.getOrDefault(RARITIES[i], 0);
            if (i >= 2) base *= 1 + Math.max(0, shrineBonus);
            if (i == 4) base *= 1 + Math.max(0, legendaryLuck);
            if (i == 5) base *= 1 + Math.max(0, mythicLuck);
            if (i == 6) base *= 1 + Math.max(0, divineLuck);
            w[i] = base;
        }
        return w;
    }

    public static int pickWeighted(double[] weights, double roll01) {
        double total = 0;
        for (double w : weights) total += Math.max(0, w);
        if (total <= 0) return 0;
        double x = roll01 * total;
        for (int i = 0; i < weights.length; i++) {
            x -= Math.max(0, weights[i]);
            if (x < 0) return i;
        }
        return weights.length - 1;
    }

    /**
     * Which rabbit an egg holds.
     *
     * @param island        island the egg was found on (null for Bounty Hunter / fished eggs): its residents can appear
     * @param islandExists  whether a resident's island is in the game; residents of missing islands count as "anywhere"
     * @param owned         rabbits already found
     * @param residentChance chance the egg holds one of this island's residents (TUNE)
     * @param uniqueBonus   chance to re-pick among rabbits not found yet when the first pick is a duplicate
     */
    public static ChocCatalog.Rabbit rollEgg(ChocCatalog catalog, String island, Predicate<String> islandExists,
                                             Collection<String> owned, double[] weights, double residentChance,
                                             double uniqueBonus, DoubleSupplier rng) {
        if (island != null && rng.getAsDouble() < residentChance) {
            List<ChocCatalog.Rabbit> res = catalog.residents(island);
            if (!res.isEmpty()) return pickWithBonus(res, owned, uniqueBonus, rng);
        }
        int idx = pickWeighted(weights, rng.getAsDouble());
        for (int step = idx; step >= 0; step--) {
            List<ChocCatalog.Rabbit> pool = new ArrayList<>();
            for (ChocCatalog.Rabbit r : catalog.rabbits) {
                if (!r.rarity.equals(RARITIES[step]) || !r.fromEggs()) continue;
                if (r.resident()) {
                    boolean anyExists = false;
                    for (String i : r.islands) if (islandExists.test(i)) { anyExists = true; break; }
                    if (anyExists) continue;   // a real resident: only on its own island
                }
                pool.add(r);
            }
            if (!pool.isEmpty()) return pickWithBonus(pool, owned, uniqueBonus, rng);
        }
        return null;
    }

    static ChocCatalog.Rabbit pickWithBonus(List<ChocCatalog.Rabbit> pool, Collection<String> owned, double uniqueBonus,
                                            DoubleSupplier rng) {
        ChocCatalog.Rabbit first = pool.get((int) Math.min(pool.size() - 1, Math.floor(rng.getAsDouble() * pool.size())));
        if (!owned.contains(first.id)) return first;
        if (rng.getAsDouble() >= uniqueBonus) return first;
        List<ChocCatalog.Rabbit> fresh = new ArrayList<>();
        for (ChocCatalog.Rabbit r : pool) if (!owned.contains(r.id)) fresh.add(r);
        if (fresh.isEmpty()) return first;
        return fresh.get((int) Math.min(fresh.size() - 1, Math.floor(rng.getAsDouble() * fresh.size())));
    }

    /** Chocolate a duplicate rabbit pays: seconds of current production, never below the rarity floor. TUNE. */
    public static double duplicateReward(ChocCatalog.Factory f, String rarity, double cps, double bonusFraction) {
        double sec = f.duplicateSeconds.getOrDefault(rarity, 120.0);
        double floor = f.duplicateFloor.getOrDefault(rarity, 50.0);
        return Math.floor(Math.max(floor, cps * sec) * (1 + Math.max(0, bonusFraction)));
    }

    // ---- the egg clock --------------------------------------------------------------------------------------

    public static final int MIN_PER_DAY = 1440;
    public static final int DAYS_PER_MONTH = 31;
    public static final int DAYS_PER_YEAR = 372;

    /** Absolute in-game minute since year 1, Early Spring 1st, 00:00. */
    public static long absMinute(int year, int month, int day, int hour, int minute) {
        long dayIndex = (long) (year - 1) * DAYS_PER_YEAR + (long) (month - 1) * DAYS_PER_MONTH + (day - 1);
        return dayIndex * MIN_PER_DAY + hour * 60L + minute;
    }

    /** The day index of the current spawn of a meal that appears at {@code hour}: an egg lives until the next one. */
    public static long mealInstance(long absMinute, int hour) {
        return Math.floorDiv(absMinute - hour * 60L, MIN_PER_DAY);
    }

    public static long instanceStart(long instance, int hour) { return instance * MIN_PER_DAY + hour * 60L; }

    /** Month (1-12) of an absolute minute. */
    public static int monthOf(long absMinute) {
        long dayIndex = Math.floorDiv(absMinute, MIN_PER_DAY);
        return (int) (Math.floorMod(dayIndex, DAYS_PER_YEAR) / DAYS_PER_MONTH) + 1;
    }

    /** The Hunt runs Early Spring 1st to Late Spring 31st (research/05 7.4). */
    public static boolean springAt(long absMinute) {
        int m = monthOf(absMinute);
        return m >= 1 && m <= 3;
    }

    /** Minutes until the next spawn of a meal (0..1439). */
    public static long minutesUntilNext(long absMinute, int hour) {
        long next = instanceStart(mealInstance(absMinute, hour) + 1, hour);
        return next - absMinute;
    }

    // ---- strays ---------------------------------------------------------------------------------------------

    /** Seconds of production a plain stray pays: 5..50 (research/08 6.6). */
    public static int straySeconds(double roll01) {
        int[] steps = {5, 10, 15, 20, 30, 40, 50};
        return steps[(int) Math.min(steps.length - 1, Math.floor(roll01 * steps.length))];
    }

    /** Golden stray kinds: Jackpot 480 s of CpS, Chocolate Mountain 1,500 s (research/08 6.6), plus two of ours. */
    public static final String[] GOLDEN = {"jackpot", "mountain", "golden_click", "dorado"};
    /** TUNE: weights of the golden kinds. */
    public static final double[] GOLDEN_WEIGHTS = {50, 10, 25, 15};

    public static double goldenSeconds(String kind) {
        return switch (kind) {
            case "jackpot" -> 480;
            case "mountain" -> 1500;
            default -> 0;
        };
    }
}
