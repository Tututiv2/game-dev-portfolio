package com.thestill.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Item building for menus.
 *
 * <p>Every path through this class kills italics explicitly. Minecraft renders
 * custom item names and lore in italic by default, and a component that forgets
 * to say otherwise looks subtly wrong next to one that remembered. Shadow Prison
 * accumulated eight near-copies of its gradient helper and seven of them missed
 * this — which is the argument for having exactly one of these.
 */
public final class MenuItems {

    private MenuItems() {
    }

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) {
                List<Component> clean = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    clean.add(line.decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(clean);
            }
        });
        return stack;
    }

    public static ItemStack of(Material material, Component name) {
        return of(material, name, List.of());
    }

    public static ItemStack label(Material material, String name, NamedTextColor colour,
                                  String... loreLines) {
        List<Component> lore = new ArrayList<>(loreLines.length);
        for (String line : loreLines) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        return of(material, Component.text(name, colour), lore);
    }

    public static ItemStack pane(Material material) {
        // A single space, not empty: an empty name renders as the material's
        // vanilla name, which reads as a real button rather than as padding.
        return of(material, Component.text(" "));
    }

    public static ItemStack filler() {
        return pane(Material.GRAY_STAINED_GLASS_PANE);
    }

    /**
     * Grey lore lines from plain strings — Shadow Prison's {@code MenuItems.lore}.
     *
     * <p>Ported so the cosmetic menus can be a literal transcription of theirs. Every
     * lore line in those two files is grey unless it says otherwise, and reproducing that
     * with a {@code Component.text(..., GRAY)} per line invites one of them to drift.
     */
    public static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            out.add(Component.text(line, NamedTextColor.GRAY));
        }
        return out;
    }

    /**
     * The enchanted shimmer Shadow Prison uses to mark the selected tile in a palette.
     *
     * <p>Over there it is {@code addEnchant(UNBREAKING, 1, true)} plus
     * {@code HIDE_ENCHANTS}, which is the pre-1.20.5 way of getting a glint with no
     * tooltip line. {@code setEnchantmentGlintOverride} is the same picture with none of
     * the mechanism — the item carries no enchantment at all, so nothing downstream can
     * ever read one off a menu tile.
     */
    public static ItemStack glint(ItemStack stack) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return stack;
    }

    public static ItemStack back() {
        return of(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW));
    }
}
