package com.auraqol.skyblock;

import java.util.Map;

/**
 * The parts of a SkyBlock item's custom data that decide its price, copied
 * out of NBT into plain Java so the pricing rules can be tested without a
 * game. Built by {@code SkyblockItems} (the only class that touches NBT).
 *
 * @param id           the SkyBlock id, e.g. {@code HYPERION}, {@code PET}, {@code ENCHANTED_BOOK}
 * @param enchantments {@code enchantments} compound, name to level (book pricing)
 * @param petInfo      the raw {@code petInfo} JSON string, or null
 * @param runes        {@code runes} compound, type to level (rune pricing)
 * @param cakeYear     {@code new_years_cake}, or 0
 * @param potion       {@code potion} name, or null
 * @param potionLevel  {@code potion_level}, or 0
 * @param enhanced     potion flag
 * @param extended     potion flag
 * @param splash       potion flag
 * @param displayName  hover name with colour codes stripped (pets carry their level here)
 * @param count        stack size
 */
public record SbItem(String id, Map<String, Integer> enchantments, String petInfo,
                     Map<String, Integer> runes, int cakeYear, String potion, int potionLevel,
                     boolean enhanced, boolean extended, boolean splash,
                     String displayName, int count) {

    public SbItem {
        enchantments = enchantments == null ? Map.of() : enchantments;
        runes = runes == null ? Map.of() : runes;
        count = Math.max(1, count);
    }

    /** A plain item: just an id. Mostly for tests. */
    public static SbItem of(String id, int count) {
        return new SbItem(id, null, null, null, 0, null, 0, false, false, false, null, count);
    }
}
