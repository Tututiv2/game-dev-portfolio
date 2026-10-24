package com.skyclone.api.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** One click on a menu slot. */
public record MenuClick(Player player, Menu menu, int slot, ClickType type, ItemStack current, ItemStack cursor) {
    public boolean left() { return type.isLeftClick(); }
    public boolean right() { return type.isRightClick(); }
    public boolean shift() { return type.isShiftClick(); }
}
