package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Season of Gerald siege's arithmetic, pure (wave 5). The reference runs five rounds in about an hour with some
 * eight minutes of breaks, so a round is a long fight, not one burst: each round's attackers march in over several
 * waves ({@link #split}), the boss with the last one, and the next wave comes when the field is nearly clear or a
 * timer runs out ({@link #nextWaveDue}). After the round every defender is paid in gifts by damage rank; how many and
 * which colour also answer to Carnival Perks, the Snow set and a Reindeer pet ({@link #giftCount}, {@link #upgradeToRed}).
 */
final class SiegeRules {

    private SiegeRules() {}

    /**
     * Split one round's attackers into {@code parts} waves: each kind spread as evenly as it goes (the remainder lands
     * on the later waves, so the pressure rises), every boss id in the last wave. Counts are preserved exactly.
     */
    static List<Map<String, Integer>> split(Map<String, Integer> round, int parts, Set<String> bosses) {
        int n = Math.max(1, parts);
        List<Map<String, Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new LinkedHashMap<>());
        for (Map.Entry<String, Integer> e : round.entrySet()) {
            int count = Math.max(0, e.getValue());
            if (count == 0) continue;
            if (bosses.contains(e.getKey())) { out.get(n - 1).merge(e.getKey(), count, Integer::sum); continue; }
            int base = count / n, extra = count % n;
            for (int i = 0; i < n; i++) {
                int c = base + (i >= n - extra ? 1 : 0);
                if (c > 0) out.get(i).merge(e.getKey(), c, Integer::sum);
            }
        }
        out.removeIf(Map::isEmpty);
        return out;
    }

    /** The next wave marches in once the field is down to a quarter of the last wave, or after the gap. */
    static boolean nextWaveDue(int alive, int lastWaveSize, long now, long dueAt) {
        return now >= dueAt || alive <= Math.max(0, lastWaveSize / 4);
    }

    /**
     * Gifts one defender gets for a round: 1, plus a whole gift per full 100% of bonus and a {@code roll} at the rest.
     * Bonus % = Present Connoisseur (+2 a tier) + Snow set (+5 a piece) + a Reindeer's Gift Sense.
     */
    static int giftCount(double bonusPct, double roll) {
        double b = Math.max(0, bonusPct) / 100.0;
        int whole = (int) Math.floor(b);
        return 1 + whole + (roll < b - whole ? 1 : 0);
    }

    /** Red Fad: a non-Red gift becomes Red with +1% a tier, unless the round lost too many gifts for Reds at all. */
    static String upgradeToRed(String gift, boolean noRed, double redFadPct, double roll) {
        if (noRed || "RED_GIFT".equals(gift)) return gift;
        return roll < Math.max(0, redFadPct) / 100.0 ? "RED_GIFT" : gift;
    }

    /** Gift opening: coins and skill XP x (1 + talisman% + Professional Gifter%). */
    static long giftScaled(int rolled, double talismanPct, double gifterPct) {
        return Math.round(Math.max(0, rolled) * (1 + (Math.max(0, talismanPct) + Math.max(0, gifterPct)) / 100.0));
    }
}
