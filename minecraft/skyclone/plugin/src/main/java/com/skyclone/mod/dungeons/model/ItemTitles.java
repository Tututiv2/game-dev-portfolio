package com.skyclone.mod.dungeons.model;

/**
 * How a loot line names an item whose own name is shared by a whole family (pure, so {@code ItemTitlesTest} checks it
 * offline). Every enchanted book level is registered as "Enchanted Book" with its enchant in the lore, so a reward chest
 * that listed "Enchanted Book" said nothing about what dropped (wave-5 regression: Combo I). Slayer and shops already
 * title books "Enchanted Book (Smite VI)"; the dungeon's chests, chat lines, RNG meter and secrets now do too.
 */
public final class ItemTitles {

    private ItemTitles() {}

    /** "Enchanted Book (Combo I)"; the base name alone when there is no enchant name. */
    public static String withEnchant(String base, String enchantName, int level) {
        String n = base == null ? "" : base;
        if (enchantName == null || enchantName.isBlank()) return n;
        return n + " (" + enchantName.trim() + (level > 0 ? " " + roman(level) : "") + ")";
    }

    /** "ultimate_combo" -> "Combo" fallback when no enchant def is registered (the ultimate prefix is not a name). */
    public static String enchantFallback(String enchantId) {
        if (enchantId == null || enchantId.isBlank()) return null;
        String s = enchantId.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("ultimate_")) s = s.substring("ultimate_".length());
        StringBuilder b = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static final int[] V = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] S = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    public static String roman(int n) {
        if (n <= 0) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < V.length; i++) while (n >= V[i]) { n -= V[i]; sb.append(S[i]); }
        return sb.toString();
    }
}
