package com.skyclone.mod.collections;

import java.util.Locale;
import java.util.Map;

/** Pure tier/progress arithmetic. No Bukkit, so it is unit-tested directly. */
public final class CollectionMath {

    private CollectionMath() {}

    /** Highest tier whose threshold {@code total} has reached (0 = none). */
    public static int tierFor(CollectionCatalog.Coll c, long total) {
        int t = 0;
        for (CollectionCatalog.Tier tier : c.tiers) {
            if (total >= tier.amount) t = tier.tier; else break;
        }
        return t;
    }

    /** Threshold of the tier after {@code tier}, or -1 when maxed. */
    public static long nextThreshold(CollectionCatalog.Coll c, int tier) {
        CollectionCatalog.Tier next = c.tier(tier + 1);
        return next == null ? -1 : next.amount;
    }

    /** Genre progress: total / next threshold (not the delta from the previous tier), clamped to [0,1]. */
    public static double progress(CollectionCatalog.Coll c, long total) {
        int t = tierFor(c, total);
        long next = nextThreshold(c, t);
        if (next <= 0) return 1.0;
        return Math.max(0, Math.min(1, (double) total / next));
    }

    /** Fraction toward an arbitrary tier's threshold. */
    public static double progressTo(CollectionCatalog.Tier tier, long total) {
        if (tier.amount <= 0) return 1.0;
        return Math.max(0, Math.min(1, (double) total / tier.amount));
    }

    /** Overflow-safe add; collections never go negative and stop at a trillion. */
    public static long add(long total, long delta) {
        long r = total + delta;
        if (delta > 0 && r < total) r = MAX;
        return Math.max(0, Math.min(MAX, r));
    }

    public static final long MAX = 1_000_000_000_000L;

    /** Sum of SkyBlock XP for tiers 1..tier. */
    public static int sbxpThrough(CollectionCatalog.Coll c, int tier) {
        int s = 0;
        for (CollectionCatalog.Tier t : c.tiers) if (t.tier <= tier) s += t.sbxp;
        return s;
    }

    /**
     * How many collection units one kill of mob {@code mobId} is worth for boss collection {@code c}
     * (0 = not this boss). Mob ids are lower_snake; a token match means the id contains the token as
     * a whole "_"-separated word ("catacombs_f1_bonzo" matches "bonzo" and "f1"). Master-mode bosses
     * ("..._master_..." / "m1") count {@code masterWeight}; Kuudra counts by its tier word.
     */
    public static int bossUnits(CollectionCatalog.Coll c, String mobId, int masterWeight) {
        if (c == null || !c.isBoss() || mobId == null) return 0;
        String[] words = mobId.toLowerCase(Locale.ROOT).split("[_:\\-]+");
        boolean hit = false;
        boolean master = false;
        for (String w : words) {
            if (w.equals("master") || w.equals("mm")) master = true;
            for (String tok : c.boss.match) {
                if (w.equals(tok)) hit = true;
                // "f1" also accepts the master floor "m1"
                if (tok.length() == 2 && tok.charAt(0) == 'f' && w.equals("m" + tok.charAt(1))) { hit = true; master = true; }
            }
        }
        if (!hit) return 0;
        Map<String, Integer> tw = c.boss.tierWeights;
        if (tw != null && !tw.isEmpty()) {
            for (String w : words) { Integer v = tw.get(w); if (v != null) return v; }
            return 1;
        }
        return master ? Math.max(1, masterWeight) : 1;
    }

    private static final int[] ROMAN_V = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_S = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    public static String roman(int n) {
        if (n <= 0) return "0";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ROMAN_V.length; i++) while (n >= ROMAN_V[i]) { n -= ROMAN_V[i]; sb.append(ROMAN_S[i]); }
        return sb.toString();
    }

    /** "7" or "VII" -> 7; anything else -> -1. */
    public static int parseTier(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        String u = s.trim().toUpperCase(Locale.ROOT);
        int total = 0;
        int i = 0;
        outer:
        while (i < u.length()) {
            for (int k = 0; k < ROMAN_S.length; k++) {
                if (u.startsWith(ROMAN_S[k], i)) { total += ROMAN_V[k]; i += ROMAN_S[k].length(); continue outer; }
            }
            return -1;
        }
        return roman(total).equals(u) ? total : -1;
    }
}
