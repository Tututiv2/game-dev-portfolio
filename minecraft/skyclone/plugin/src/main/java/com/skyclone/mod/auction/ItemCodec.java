package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Turns an escrowed item into a database string and back, and makes safe DISPLAY copies.
 *
 * <p>The stored bytes are the item itself (Paper's {@code serializeAsBytes}, which carries every
 * component and the persistent data container), so a unique item comes back with the SAME uuid it
 * left the seller with. That is correct, not a dupe: the original left the seller's inventory in
 * the same tick it was escrowed. Display copies shown in menus have the skyclone tags stripped, so
 * even an icon that somehow escaped a menu would be a dead vanilla item, not a second copy.
 */
public final class ItemCodec {

    private ItemCodec() {}

    public static String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    public static ItemStack decode(String b64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
    }

    /** A copy for a menu: lore re-rendered by core, then every skyclone: PDC key removed. */
    public static ItemStack display(ItemStack real) {
        ItemStack c = real.clone();
        try { Sky.items().refresh(c); } catch (Throwable ignored) { }
        ItemMeta meta = c.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            for (NamespacedKey k : new ArrayList<>(pdc.getKeys())) {
                if ("skyclone".equals(k.getNamespace())) pdc.remove(k);
            }
            c.setItemMeta(meta);
        }
        return c;
    }

    /** '&amp;' coded display name: the item's custom name, else its def name, else the material. */
    public static String name(ItemStack stack, String itemId) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component dn = meta.displayName();
            if (dn != null) return Text.toLegacy(dn);
        }
        var def = itemId == null ? null : Sky.items().def(itemId);
        if (def != null && def.name != null) return def.rarity.code() + def.name;
        return "&f" + Text.titleCase(stack.getType().name());
    }

    /**
     * Lower-case plain name and lore, newline separated: what search matches against. The internal id is
     * deliberately NOT included: ids keep the reference game's names (CONTRACT 1.1), and a search for a
     * reference name must not find the renamed item.
     */
    public static String searchText(ItemStack stack, String name, String itemId) {
        StringBuilder sb = new StringBuilder(Text.strip(name).toLowerCase(Locale.ROOT));
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta == null ? null : meta.lore();
        if (lore != null) for (Component c : lore) sb.append('\n').append(Text.plain(c).toLowerCase(Locale.ROOT));
        String s = sb.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
