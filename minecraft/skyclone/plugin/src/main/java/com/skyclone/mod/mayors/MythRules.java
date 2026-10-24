package com.skyclone.mod.mayors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Mythological Ritual's arithmetic, pure (wave 5, reference wiki "Mythological Ritual" / "Griffin Burrow" /
 * the three spades): how long a burrow chain is, which creature pool a Griffin opens, how Tracking tilts it, and what
 * a treasure burrow's coins are worth. {@link Mythology} only asks these questions.
 */
final class MythRules {

    private MythRules() {}

    /** The spades, weakest first (reference: chain 4 / 6 / 8, Echo cooldown 3 / 2 / 1 s). */
    static final List<String> SPADES = List.of("ANCESTRAL_SPADE", "ARCHAIC_SPADE", "DEIFIC_SPADE");

    static boolean isSpade(String id) { return id != null && SPADES.contains(id); }

    /** Creatures whose first variant is Legendary or better: the ones Tracking makes likelier. */
    static final List<String> RARE_KINDS = List.of("sphinx", "inquisitor", "manticore", "minos");

    /** Burrows in a chain dug with {@code spade}: the spade's length, +bonus with the Erudite reforge, capped. */
    static int chainLength(EventData.Myth m, String spade, boolean erudite) {
        Integer base = spade == null ? null : m.chainBySpade.get(spade);
        int n = base == null ? Math.max(1, m.chainLength) : base;
        if (erudite) n += Math.max(0, m.eruditeBonus);
        return Math.max(1, Math.min(Math.max(1, m.chainMax), n));
    }

    /**
     * The creature pool a Griffin of rarity index {@code rarity} (0 Common .. 5 Mythic) opens, with Tracking applied:
     * each point of Tracking makes the Legendary-and-up creatures 1% likelier (TUNE: the reference names the stat
     * "Tracking on Griffin Burrows" without publishing its formula).
     */
    static Map<String, Integer> pool(EventData.Myth m, int rarity, double tracking) {
        Map<String, Integer> base = m.pools.get(String.valueOf(Math.max(0, Math.min(5, rarity)) + 1));
        Map<String, Integer> out = new LinkedHashMap<>();
        if (base == null) return out;
        double boost = 1 + Math.max(0, tracking) / 100.0;
        for (Map.Entry<String, Integer> e : base.entrySet()) {
            int w = Math.max(0, e.getValue());
            out.put(e.getKey(), RARE_KINDS.contains(e.getKey()) ? (int) Math.round(w * boost) : w);
        }
        return out;
    }

    /** A weighted pick; "hunter" when the pool is empty. */
    static String pick(Map<String, Integer> pool, Random r) {
        int total = 0;
        for (int w : pool.values()) total += Math.max(0, w);
        if (total <= 0) return "hunter";
        int x = r.nextInt(total);
        for (Map.Entry<String, Integer> e : pool.entrySet()) {
            x -= Math.max(0, e.getValue());
            if (x < 0) return e.getKey();
        }
        return "hunter";
    }

    /** The variant number (1..6) a Griffin of rarity index 0..5 meets. */
    static int variant(int rarity) { return Math.max(0, Math.min(5, rarity)) + 1; }

    /** Treasure coins: the rolled amount x2 with the Crown of Greed, then +greedPct% (Deadly Greed). */
    static long treasureCoins(int rolled, boolean crownOfGreed, double greedPct) {
        double c = Math.max(0, rolled) * (crownOfGreed ? 2 : 1);
        return Math.round(c * (1 + Math.max(0, greedPct) / 100.0));
    }

    /** Does a burrow hold a creature? The first burrow of a chain is always treasure (the reference's start burrow). */
    static boolean creatureBurrow(boolean first, double mobChance, double roll) { return !first && roll < mobChance; }

    /** Echo cooldown seconds per spade (reference item lore: 3 / 2 / 1). */
    static int echoCooldown(String spade) {
        int i = SPADES.indexOf(spade);
        return i < 0 ? 3 : 3 - i;
    }
}
