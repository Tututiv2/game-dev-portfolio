package com.skyclone.mod.dungeons;

import com.skyclone.mod.dungeons.model.ItemTitles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** wave-5 regression: reward chests named a Combo I book plain "Enchanted Book". */
class ItemTitlesTest {

    @Test
    void bookNamesItsEnchant() {
        assertEquals("Enchanted Book (Combo I)", ItemTitles.withEnchant("Enchanted Book", "Combo", 1));
        assertEquals("Enchanted Book (Smite VI)", ItemTitles.withEnchant("Enchanted Book", "Smite", 6));
        assertEquals("Enchanted Book (Feather Falling X)", ItemTitles.withEnchant("Enchanted Book", " Feather Falling ", 10));
    }

    @Test
    void nonBooksAreUntouched() {
        assertEquals("Wither Essence", ItemTitles.withEnchant("Wither Essence", null, 0));
        assertEquals("Enchanted Book", ItemTitles.withEnchant("Enchanted Book", "  ", 3));
        assertEquals("Enchanted Book (Rejuvenate)", ItemTitles.withEnchant("Enchanted Book", "Rejuvenate", 0));
    }

    @Test
    void fallbackDropsTheUltimatePrefix() {
        assertEquals("Combo", ItemTitles.enchantFallback("ultimate_combo"));
        assertEquals("Infinite Quiver", ItemTitles.enchantFallback("infinite_quiver"));
        assertEquals("XLIX", ItemTitles.roman(49));
    }
}
