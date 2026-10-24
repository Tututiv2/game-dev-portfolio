package com.skyclone.api.ui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Menu icons in the genre's style. All text is '&amp;' coded; lore lines are never italic. */
public final class Icons {

    private Icons() {}

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, name, List.of(lore));
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(name));
            meta.lore(Text.lines(lore));
            hide(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Player head with a base64 skin texture. */
    public static ItemStack skull(String texture, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        if (texture != null && !texture.isEmpty()) applyTexture(meta, texture);
        meta.displayName(Text.c(name));
        meta.lore(Text.lines(lore));
        hide(meta);
        it.setItemMeta(meta);
        return it;
    }

    /** Head of a real player (their skin). */
    public static ItemStack playerHead(UUID owner, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
        meta.displayName(Text.c(name));
        meta.lore(Text.lines(lore));
        hide(meta);
        it.setItemMeta(meta);
        return it;
    }

    public static void applyTexture(SkullMeta meta, String texture) {
        // Deterministic uuid per texture so identical heads stack.
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes()), "skull");
        profile.setProperty(new ProfileProperty("textures", texture));
        meta.setPlayerProfile(profile);
    }

    public static void hide(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.setEnchantmentGlintOverride(true); it.setItemMeta(meta); }
        return it;
    }

    /** Black stained glass with a blank name: the filler behind every menu. */
    public static ItemStack filler() { return of(Material.BLACK_STAINED_GLASS_PANE, " "); }

    public static ItemStack glass(Material pane, String name, String... lore) { return of(pane, name, lore); }

    public static ItemStack close() { return of(Material.BARRIER, "&cClose"); }

    /** "Go Back" arrow, "&amp;7To {where}". */
    public static ItemStack back(String where) { return of(Material.ARROW, "&aGo Back", "&7To " + where); }

    public static ItemStack nextPage(int page) { return of(Material.ARROW, "&aNext Page", "&ePage " + (page + 1)); }

    public static ItemStack prevPage(int page) { return of(Material.ARROW, "&aPrevious Page", "&ePage " + (page - 1)); }

    /** Append lore lines to a copy of an item. */
    public static ItemStack withLore(ItemStack base, List<String> extra) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(Text.lines(extra));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack amount(ItemStack it, int n) {
        ItemStack c = it.clone();
        c.setAmount(Math.max(1, Math.min(99, n)));
        return c;
    }
}
