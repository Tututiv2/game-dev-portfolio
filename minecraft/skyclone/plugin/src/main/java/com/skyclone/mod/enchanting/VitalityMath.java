package com.skyclone.mod.enchanting;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure numbers behind the Vitality pool (research 01 section 6.2, post-June-2026): the pool's max is the Vitality
 * stat (base 100) and it regenerates 5% of max per second; healing abilities spend it; Drain and Last Stand refill
 * it; the four converter enchants turn what is SPENT into a 10 s buff (Hardened -> Defense, Strong -> Strength,
 * Vivacious -> Attack Speed) or an instant heal (Vampiric). No Bukkit here - unit tested.
 */
public final class VitalityMath {

    private VitalityMath() {}

    /** The pool after {@code seconds} of regeneration at {@code pctPerSecond}% of max per second. */
    public static double regen(double current, double max, double pctPerSecond, double seconds) {
        if (max <= 0) return 0;
        double cur = Math.max(0, Math.min(current, max));
        return Math.min(max, cur + max * Math.max(0, pctPerSecond) / 100.0 * Math.max(0, seconds));
    }

    /** Clamp a pool to a (possibly shrunk) max - taking off a Vitality piece never leaves the pool above it. */
    public static double clamp(double current, double max) {
        return Math.max(0, Math.min(current, Math.max(0, max)));
    }

    /** What actually leaves the pool when {@code cost} is charged: all of it, or what is left (never negative). */
    public static double spendable(double current, double cost) {
        return Math.max(0, Math.min(Math.max(0, current), Math.max(0, cost)));
    }

    /**
     * A converter's buff after another {@code spent} Vitality: {@code pct}% of it is added to what is still running,
     * capped at {@code cap} (the book's "Max 25 Defense" is per piece, so the caller passes cap x pieces).
     */
    public static double convert(double running, double spent, double pct, double cap) {
        if (spent <= 0 || pct <= 0 || cap <= 0) return Math.max(0, Math.min(running, Math.max(0, cap)));
        return Math.min(cap, Math.max(0, running) + spent * pct / 100.0);
    }

    /**
     * Parse the cost dial: {@code "weapons_wand_heal_healing=25, slayer_orb=50"} -> ability id to Vitality.
     * Bad entries are skipped (a typo in a dial must never break abilities).
     */
    public static Map<String, Double> parseCosts(String text) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String part : text.split("[,;]")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) continue;
            String id = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            try {
                double v = Double.parseDouble(t.substring(eq + 1).trim());
                if (v > 0 && Double.isFinite(v) && !id.isEmpty()) out.put(id, v);
            } catch (NumberFormatException ignored) { /* skip it */ }
        }
        return out;
    }

    /** Research 01 section 6.2 healing-ability costs, keyed by OUR ability ids (weapons / slayer data). */
    public static final String DEFAULT_COSTS =
            "weapons_wand_heal_healing=25, weapons_wand_heal_mending=25, weapons_wand_heal_restoration=25, "
            + "weapons_wand_heal_atonement=25, weapons_instant_heal=25, weapons_instant_heal_ornate=25, "
            + "weapons_instant_heal_florid=25, slayer_orb=50, slayer_flare=10, weapons_nasty_bite=10, "
            + "slayer_grimoire_heal=60";

    /** Charged but never refused for lack of Vitality (the ability still happens, the pool just empties). */
    public static final String DEFAULT_SOFT_COSTS = "dungeons_wither_impact=50, weapons_creeper_veil=30";

    /** Action-bar text: "&4 80/100♨" (whole numbers, like core's health part). */
    public static String bar(double current, double max) {
        return "&4" + Math.round(Math.floor(current)) + "/" + Math.round(Math.floor(max)) + "♨ Vitality";
    }
}
