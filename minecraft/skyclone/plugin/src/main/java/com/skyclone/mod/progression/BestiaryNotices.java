package com.skyclone.mod.progression;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure: coalescing of Bestiary family tier-ups into one chat box.
 *
 * <p>Every family tier-up printed its own 8-line "BESTIARY Zombie I➜II / REWARDS ..." box, and a dungeon room
 * (a dozen families, most of them new) printed about ten of them back to back - 80 lines that scrolled every
 * other message off the chat (wave-5 regression playtest, sidebar / chat-noise note). Tier-ups that land
 * close together are now collected and announced once: a lone tier-up still gets the reference's full box,
 * two or more share one box that names each family and sums the SkyBlock XP. Nothing here touches Bukkit, so
 * the unit tests run it with no server.
 */
final class BestiaryNotices {

    private BestiaryNotices() {}

    /** One family going from tier {@code from} to tier {@code to} (0 = never killed before). */
    record TierUp(String key, String name, int from, int to) {
        int tiers() { return Math.max(0, to - from); }
    }

    /**
     * Add a tier-up to the pending list. The same family twice in one window becomes one entry spanning both
     * (lowest from, highest to), so "Zombie I➜II" then "II➜III" reads "Zombie I➜III".
     */
    static void merge(List<TierUp> pending, TierUp t) {
        if (t == null || t.tiers() <= 0) return;
        for (int i = 0; i < pending.size(); i++) {
            TierUp o = pending.get(i);
            if (o.key().equals(t.key())) {
                pending.set(i, new TierUp(o.key(), o.name(), Math.min(o.from(), t.from()), Math.max(o.to(), t.to())));
                return;
            }
        }
        pending.add(t);
    }

    /** Total family tiers gained across the batch. */
    static int totalTiers(List<TierUp> ups) {
        int n = 0;
        for (TierUp u : ups) n += u.tiers();
        return n;
    }

    /** Headline of the combined box: "BESTIARY 10 families (7 new!)". */
    static String headline(List<TierUp> ups) {
        int fresh = 0;
        for (TierUp u : ups) if (u.from() == 0) fresh++;
        return "&3&lBESTIARY &b" + ups.size() + " families" + (fresh > 0 ? " &8(" + fresh + " new!)" : "");
    }

    /**
     * Body of the combined box: the families {@code perLine} to a line (at most {@code maxLines} lines, then
     * "...and N more"), then REWARDS with the summed SkyBlock XP and where the per-family bonuses are listed.
     */
    static List<String> body(List<TierUp> ups, int xpPerTier, int perLine, int maxLines) {
        int per = Math.max(1, perLine);
        int lines = Math.max(1, maxLines);
        List<String> out = new ArrayList<>();
        int shown = Math.min(ups.size(), per * lines);
        StringBuilder sb = null;
        for (int i = 0; i < shown; i++) {
            if (i % per == 0) {
                if (sb != null) out.add(sb.toString());
                sb = new StringBuilder("  ");
            } else sb.append("&7, ");
            sb.append(entry(ups.get(i)));
        }
        if (sb != null) out.add(sb.toString());
        if (ups.size() > shown) out.add("  &7...and " + (ups.size() - shown) + " more &8(/bestiary)");
        out.add("&a&lREWARDS");
        out.add("  &8+&b" + totalTiers(ups) * Math.max(0, xpPerTier) + " SkyBlock XP");
        out.add("  &7Bonuses vs each family &8(/bestiary)");
        return out;
    }

    /** "&bZombie &80➜&bI" (tier 0 written as 0, as the single box does). */
    static String entry(TierUp u) {
        return "&b" + u.name() + " &8" + roman(u.from()) + "➜&b" + roman(u.to());
    }

    static String roman(int n) {
        if (n <= 0) return "0";
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { sb.append(s[i]); n -= v[i]; }
        return sb.toString();
    }
}
