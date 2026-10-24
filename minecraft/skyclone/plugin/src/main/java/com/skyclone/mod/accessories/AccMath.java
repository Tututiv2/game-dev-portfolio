package com.skyclone.mod.accessories;

import com.skyclone.api.item.Rarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure formulas of the accessory system (no Bukkit), so unit tests can pin them to the research numbers.
 *
 * <ul>
 *   <li>{@link #multiplier}: research/01 section 20.2, research/06 section 7.2:
 *       {@code 29.97 * (ln(0.0019 * MP + 1))^1.2}. M(250) = 9.642, M(1000) = 32.312.</li>
 *   <li>Accessory Power per rarity: research/06 section 7.2 table.</li>
 *   <li>Bag slots: research/06 section 7.3 (base 9, Redstone collection II-IX +6 each, redstone exchange +4,
 *       six account upgrades of +2 (here: SkyBlock level milestones), and up to 99 bought pairs).</li>
 * </ul>
 */
public final class AccMath {

    private AccMath() {}

    /** Accessory Power by rarity ordinal (COMMON..ADMIN). research/06 7.2 + research/01 20.1 (Ultimate 1, Admin 22). */
    public static final int[] DEFAULT_MP = {3, 5, 8, 12, 16, 22, 28, 3, 5, 1, 22};

    /** The stat multiplier a Power gets from Accessory Power. */
    public static double multiplier(double mp) {
        if (!(mp > 0)) return 0;
        return 29.97 * Math.pow(Math.log(0.0019 * mp + 1), 1.2);
    }

    /** One tuning point per {@code mpPerPoint} Accessory Power (research: 10). */
    public static int tuningPoints(int mp, int mpPerPoint) {
        if (mp <= 0 || mpPerPoint <= 0) return 0;
        return mp / mpPerPoint;
    }

    public static int mpFor(Rarity r, int[] table) {
        if (r == null) return 0;
        int i = r.ordinal();
        return i < table.length ? table[i] : 0;
    }

    /**
     * Total bag slots.
     * @param redstoneTier the player's Redstone collection tier (0..)
     * @param perTier slots per Redstone tier from II to IX
     * @param milestones SkyBlock-level milestones reached (each worth {@code perMilestone})
     */
    public static int bagSlots(int base, int redstoneTier, int perTier, int milestones, int perMilestone,
                               boolean exchange, int exchangeSlots, int boughtPairs) {
        int redstone = Math.max(0, Math.min(redstoneTier, 9) - 1) * Math.max(0, perTier);
        int total = Math.max(0, base) + redstone + Math.max(0, milestones) * Math.max(0, perMilestone)
                + (exchange ? Math.max(0, exchangeSlots) : 0) + Math.max(0, boughtPairs) * 2;
        return Math.max(0, total);
    }

    /**
     * Coin cost of the {@code n}-th bought slot pair (1-based), {@code base * n^2} rounded to 100.
     * TUNE: research gives only the total (~1.76B for all 99 pairs); base 5,360 reproduces that total.
     */
    public static long pairCost(int n, double base) {
        if (n <= 0) return 0;
        double c = base * (double) n * n;
        return Math.max(100, Math.round(c / 100.0) * 100);
    }

    /** Number of decimal digits in a counter (0 has none). */
    /**
     * A tiered ("per piece, up to N") bonus shared with the armour module: armour pays for its own
     * pieces, so equipment pays only for what is left under the cap. 4 armour + 4 equipment on an
     * 8-tier bonus = 4 here; 4 armour + 4 equipment on a 4-tier bonus = 0 here.
     */
    /** Vanquished piece bonus: +1 per {@code every} counted, at most {@code cap}. */
    static int pieceBonus(long count, int every, int cap) {
        if (count <= 0 || every <= 0 || cap <= 0) return 0;
        return (int) Math.min(cap, count / every);
    }

    static int tieredEquipPieces(int armor, int equip, int cap) {
        int a = Math.max(0, armor), e = Math.max(0, equip), c = Math.max(0, cap);
        return Math.max(0, Math.min(a + e, c) - Math.min(a, c));
    }

    public static int digits(long n) {
        if (n <= 0) return 0;
        return String.valueOf(n).length();
    }

    // ---- wave 5 -----------------------------------------------------------------------------------

    /** Abicase: +1 Accessory Power per {@code per} contacts (research 06: floor(contacts / 2)). */
    static int abicaseMp(int contacts, double per) {
        if (contacts <= 0 || !(per > 0)) return 0;
        return (int) Math.floor(contacts / per);
    }

    /** Runebook: rarity steps above its own, one per digit of the Runic kill count, at most {@code cap}. */
    static int runicSteps(long count, int cap) {
        return Math.max(0, Math.min(Math.max(0, cap), digits(count)));
    }

    /**
     * Fire damage taken multiplier: Scourge Cloak's flat {@code pct} (10,000% = x101) plus Delirium Necklace's
     * {@code perStrength} % for every point of Strength on the piece. Never below 1.
     */
    static double fireTakenMult(double pct, double perStrength, double strengthOnPiece) {
        double add = Math.max(0, pct) + Math.max(0, perStrength) * Math.max(0, strengthOnPiece);
        return 1 + add / 100.0;
    }

    /** Bat Person: bats you have summoned = the Bat pet (1) + a bat-swarm mask + guided bats still flying. */
    static int bats(boolean batPet, int maskBats, int guided) {
        return (batPet ? 1 : 0) + Math.max(0, maskBats) + Math.max(0, guided);
    }

    /** Haunted Skull position on its circle: angle after {@code t} of {@code period} seconds (one full turn). */
    static double[] orbit(double t, double period, double radius) {
        double a = period > 0 ? 2 * Math.PI * (t / period) : 0;
        return new double[]{Math.cos(a) * radius, Math.sin(a) * radius};
    }

    /**
     * Highest bounded count {@code n} in [0, max] for which {@code atLeast(n)} holds, given that it is monotone
     * (true up to the answer, false above). Used to read a contact count through the {@code abiphone:contacts:N}
     * requirement key, which only answers "at least N?". 0 when even 1 fails.
     */
    static int countByThreshold(java.util.function.IntPredicate atLeast, int max) {
        int lo = 0, hi = Math.max(0, max);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (atLeast.test(mid)) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** A bag entry for family selection. */
    public record Candidate<T>(String family, int rank, int rarity, T item) {}

    /**
     * Only the best accessory of each family counts: highest rank (Talisman &lt; Ring &lt; Artifact &lt; Relic),
     * ties broken by effective rarity (a recombobulated copy beats a plain one), then first seen.
     * Returns the winners in first-seen family order.
     */
    public static <T> List<T> pickBest(Collection<Candidate<T>> all) {
        Map<String, Candidate<T>> best = new LinkedHashMap<>();
        for (Candidate<T> c : all) {
            Candidate<T> cur = best.get(c.family());
            if (cur == null || c.rank() > cur.rank() || (c.rank() == cur.rank() && c.rarity() > cur.rarity())) {
                best.put(c.family(), c);
            }
        }
        List<T> out = new ArrayList<>(best.size());
        for (Candidate<T> c : best.values()) out.add(c.item());
        return out;
    }

    /** Gravity Talisman: +max next to the spawn, falling by 1 per {@code blocksPerStep}, never below min. */
    public static double gravity(double distance, double min, double max, double blocksPerStep) {
        if (blocksPerStep <= 0) return min;
        double v = max - Math.floor(Math.max(0, distance) / blocksPerStep);
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Ancient Cloak: is this hit from a health-costing ability of {@code weapon}? Two ways in, because the
     * weapons module deals ability damage two ways: inside the handler as tagged MAGIC damage (Eruption) — the
     * cost check armed it a moment ago — or as melee hits scheduled a few ticks later (the rose chains), which
     * count while {@code windowMs} has not run out since core confirmed the ability ran (AbilityUsed).
     */
    public static boolean healthAbilityHit(boolean abilityTagged, String weapon, String item, long armedAt, long usedAt,
                                           long now, long windowMs) {
        if (weapon == null || !weapon.equals(item)) return false;
        boolean during = abilityTagged && armedAt > 0 && now - armedAt >= 0 && now - armedAt <= 250;
        boolean after = usedAt > 0 && now - usedAt >= 0 && now - usedAt <= Math.max(0, windowMs);
        return during || after;
    }

    /** Catacombs Expert Ring / Professor's books: XP after the percentages of every counted one (summed). */
    public static double dungeonXp(double amount, double pctSum) {
        if (amount <= 0) return amount;
        return amount * (1 + Math.max(0, pctSum) / 100.0);
    }

    /** Spread the allocated tuning points over the available ones, in a fixed stat order. */
    public static Map<String, Integer> clampTuning(Map<String, Integer> allocated, List<String> order, int available) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int left = Math.max(0, available);
        for (String k : order) {
            int want = Math.max(0, allocated.getOrDefault(k, 0));
            int got = Math.min(want, left);
            if (got > 0) out.put(k, got);
            left -= got;
        }
        return out;
    }
}
