package com.shadowprison.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE visual language. Every menu, item and message in the plugin is styled through here, so
 * the server reads as one product — which is most of what "feels like Hypixel" means. SP1 had
 * eight near-copies of its gradient helper, seven rendering italic in lore.
 *
 * <p>Conventions (Hypixel-style): a bold coloured name; grey description lines; a blank line;
 * then the call to action in yellow ("Click to …"). Italic is ALWAYS off.
 */
public final class Ui {

    public static final TextColor PRIMARY = TextColor.color(0xB388FF);   // Shadow violet
    public static final TextColor ACCENT = TextColor.color(0xFFD45E);    // gold
    public static final TextColor GOOD = TextColor.color(0x55FF7F);
    public static final TextColor BAD = TextColor.color(0xFF5F5F);
    public static final TextColor MUTED = NamedTextColor.GRAY;
    public static final TextColor DIM = NamedTextColor.DARK_GRAY;
    public static final TextColor ACTION = NamedTextColor.YELLOW;

    private Ui() {
    }

    public static Component text(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    public static Component bold(String s, TextColor color) {
        return text(s, color).decoration(TextDecoration.BOLD, true);
    }

    /** Menu title: plain, readable, one colour. */
    public static Component title(String s) {
        return Component.text(s, NamedTextColor.DARK_GRAY);
    }

    /** Per-character gradient, bold, never italic. THE gradient helper — do not write another. */
    public static Component gradient(String s, TextColor from, TextColor to, boolean bold) {
        TextComponent.Builder out = Component.text().decoration(TextDecoration.ITALIC, false);
        int n = s.length();
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0f : (float) i / (n - 1);
            Component c = Component.text(String.valueOf(s.charAt(i)), TextColor.lerp(t, from, to));
            out.append(bold ? c.decoration(TextDecoration.BOLD, true) : c);
        }
        return out.build();
    }

    public static ItemStack pane() {
        return icon(Material.BLACK_STAINED_GLASS_PANE).name(Component.text(" ")).hideAll().build();
    }

    public static Icon icon(Material material) {
        return new Icon(material);
    }

    /** Fluent icon builder. Lore lines are added in order; blank() inserts the standard gap. */
    public static final class Icon {
        private final ItemStack stack;
        private Component name;
        private final List<Component> lore = new ArrayList<>();
        private boolean hide;
        private boolean glint;
        private int amount = 1;

        private Icon(Material material) {
            this.stack = new ItemStack(material);
        }

        public Icon name(Component name) {
            this.name = name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
            return this;
        }

        public Icon name(String s, TextColor color) {
            return name(bold(s, color));
        }

        public Icon line(String s) {
            lore.add(text(s, MUTED));
            return this;
        }

        public Icon line(Component c) {
            lore.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            return this;
        }

        /** "Label: value" — grey label, coloured value. */
        public Icon stat(String label, String value, TextColor valueColor) {
            lore.add(text(label + ": ", MUTED).append(text(value, valueColor)));
            return this;
        }

        public Icon blank() {
            lore.add(Component.empty());
            return this;
        }

        /** The call to action, always last: "Click to open!" */
        public Icon action(String s) {
            lore.add(text(s, ACTION));
            return this;
        }

        public Icon glint(boolean on) {
            this.glint = on;
            return this;
        }

        public Icon amount(int n) {
            this.amount = Math.max(1, Math.min(99, n));
            return this;
        }

        public Icon hideAll() {
            this.hide = true;
            return this;
        }

        public ItemStack build() {
            ItemStack out = stack.clone();
            out.setAmount(amount);
            out.editMeta(meta -> {
                if (name != null) {
                    meta.displayName(name);
                }
                if (!lore.isEmpty()) {
                    meta.lore(lore);
                }
                meta.addItemFlags(ItemFlag.values());
                if (hide) {
                    meta.setHideTooltip(true);   // border panes: no tooltip at all, not an empty box
                }
                if (glint) {
                    meta.setEnchantmentGlintOverride(true);
                }
            });
            return out;
        }
    }
}
