package com.shadowprison.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** One slot: what it shows and what a click does. Decorations have no action. */
public record Button(ItemStack icon, Action action) {

    @FunctionalInterface
    public interface Action {
        void click(Player player, ClickType click);
    }

    public static Button of(ItemStack icon) {
        return new Button(icon, null);
    }

    public static Button of(ItemStack icon, Action action) {
        return new Button(icon, action);
    }

    /** Same action for every click type. */
    public static Button of(ItemStack icon, java.util.function.Consumer<Player> onClick) {
        return new Button(icon, (p, c) -> onClick.accept(p));
    }
}
