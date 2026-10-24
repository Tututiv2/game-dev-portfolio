package com.skyclone.api.item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An enchantment as DATA, registered by the enchanting module through
 * {@code Sky.items().registerEnchant(def)} so core can render it in lore. Its EFFECT lives in that
 * module (a DamageModifier, an ItemStatContributor, a listener...).
 */
public class EnchantDef {
    public String id;              // "sharpness"
    public String name;            // "Sharpness"
    public int maxLevel;           // highest level obtainable at all
    public int tableMaxLevel;      // highest from the enchanting table (higher only from books/drops)
    public boolean ultimate;       // only one ultimate per item; rendered &d&l
    public Set<ItemCategory> appliesTo = new HashSet<>();
    public Set<String> conflicts = new HashSet<>();
    /** Lore template with "%s" for the level's value: "&7Increases melee damage dealt by &a%s%&7." */
    public String description = "";
    /** Value per level, index 0 = level 1. */
    public double[] values = new double[0];
    /** Enchanting XP-level cost per level at the table (index 0 = level 1). */
    public int[] tableCost = new int[0];
    public List<String> notes = new ArrayList<>();

    public double value(int level) {
        if (values.length == 0 || level <= 0) return 0;
        return values[Math.min(level, values.length) - 1];
    }
}
