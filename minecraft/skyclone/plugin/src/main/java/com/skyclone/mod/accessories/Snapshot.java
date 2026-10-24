package com.skyclone.mod.accessories;

import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.StatMap;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One evaluation of a player's bag + equipment: which accessories count, the Accessory Power they give,
 * their summed stat sheets, and every live effect. Rebuilt when the bag changes and every few seconds
 * (requirements and rarities can change under it). Read-only once built.
 */
final class Snapshot {

    record Active(int slot, ItemStack stack, ItemDef def, Rarity rarity, int mp) {}

    final List<Active> active = new ArrayList<>();
    /** Bag slot -> why it does not count. */
    final Map<Integer, String> inactive = new HashMap<>();
    final Set<String> activeIds = new HashSet<>();
    final Set<String> bagIds = new HashSet<>();
    final Map<Rarity, Integer> countByRarity = new EnumMap<>(Rarity.class);
    /** Accessory Power outside dungeons (rift prism included). */
    int mp;
    /** Extra Accessory Power while inside a dungeon (dungeon accessories count double). */
    int mpDungeonExtra;
    /** Summed stat sheets of the counted accessories (base + reforge + enrichment). */
    final StatMap stats = new StatMap();
    /** Every live effect: counted accessories + worn equipment (set bonuses deduplicated by name). */
    final List<Fx> fx = new ArrayList<>();
    /** Equipment pieces that count (requirements met), slot -> stack. */
    final Map<String, ItemStack> equipment = new HashMap<>();
    /** Abicase (wave 5): Ringphone contacts read at build time and the Accessory Power they added. */
    int contacts, abicaseMp;
    int compactorSlots;
    int deletorSlots;
    long builtAt;

    boolean has(String type) {
        for (Fx f : fx) if (f.type.equals(type)) return true;
        return false;
    }

    /** Does any live effect (set bonuses included) depend on this condition kind ("mining", "class")? */
    boolean usesCond(String kind) {
        List<Fx> flat = new ArrayList<>();
        for (Fx f : fx) f.flatten(flat);
        for (Fx f : flat) if (f.when.uses(kind)) return true;
        return false;
    }
}
