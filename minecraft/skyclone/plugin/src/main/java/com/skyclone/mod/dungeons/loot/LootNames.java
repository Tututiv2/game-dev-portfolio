package com.skyclone.mod.dungeons.loot;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.model.ItemTitles;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Names for dungeon loot as the player reads them (wave-5 regression): an enchanted book is "Enchanted Book (Combo I)"
 * in the reward chest, the opening chat line, the RNG meter and a secret's reward line, never plain "Enchanted Book".
 * The item itself keeps the reference's name; only what a menu or chat line shows is titled.
 */
public final class LootNames {

    private LootNames() {}

    /** "Enchanted Book (Combo I)", uncoloured; the item's own name for anything that is not a book. */
    public static String plain(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null) return id == null ? "?" : Text.titleCase(id);
        String base = Text.strip(d.name == null ? Text.titleCase(d.id) : d.name);
        return ItemTitles.withEnchant(base, enchantName(d), level(d));
    }

    /** {@link #plain} in the item's rarity colour. */
    public static String colored(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        return (d == null || d.rarity == null ? "&f" : d.rarity.code()) + plain(id);
    }

    /** A display copy of {@code stack} whose title names the enchant (books only; anything else is returned as is). */
    public static ItemStack titled(ItemStack stack, String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (stack == null || enchantName(d) == null) return stack;
        ItemStack c = stack.clone();
        ItemMeta meta = c.getItemMeta();
        if (meta == null) return c;
        meta.displayName(Text.c(colored(id)));
        c.setItemMeta(meta);
        return c;
    }

    static String enchantName(ItemDef d) {
        if (d == null || d.extra == null || !d.extra.has("enchant")) return null;
        try {
            String ench = d.extra.get("enchant").getAsString();
            com.skyclone.api.item.EnchantDef ed = Sky.items().enchant(ench);
            if (ed != null && ed.name != null && !ed.name.isBlank()) return Text.strip(ed.name);
            return ItemTitles.enchantFallback(ench);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int level(ItemDef d) {
        try { return d != null && d.extra != null && d.extra.has("level") ? d.extra.get("level").getAsInt() : 0; }
        catch (RuntimeException e) { return 0; }
    }
}
