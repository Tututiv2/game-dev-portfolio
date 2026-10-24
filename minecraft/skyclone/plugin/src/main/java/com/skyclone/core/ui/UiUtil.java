package com.skyclone.core.ui;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.logging.Level;

/** Small shared helpers for the UI core. */
final class UiUtil {

    private UiUtil() {}

    static boolean empty(ItemStack it) { return it == null || it.isEmpty(); }

    static ItemStack copy(ItemStack it) { return empty(it) ? null : it.clone(); }

    /** Same item and same amount (used to make sure a delayed action still sees what was clicked). */
    static boolean sameStack(ItemStack a, ItemStack b) {
        if (empty(a) || empty(b)) return empty(a) && empty(b);
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    /**
     * Put an item in the player's inventory; whatever does not fit goes to their item stash
     * ({@code /pickupstash}), and only past a full stash drops at their feet, owned by them.
     */
    static void give(Player p, ItemStack item) {
        if (empty(item)) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack over : left.values()) {
            if (empty(over)) continue;
            int notStashed = com.skyclone.core.combat.ItemStash.put(p, over, true);
            if (notStashed <= 0) continue;
            ItemStack rest = over.clone();
            rest.setAmount(notStashed);
            p.getWorld().dropItem(p.getLocation(), rest, drop -> {
                drop.setOwner(p.getUniqueId());
                drop.setPickupDelay(0);
            });
        }
    }

    /** Run on the main thread: now if already there, next tick otherwise. */
    static void main(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(Sky.plugin(), r);
    }

    static void later(Runnable r) { Bukkit.getScheduler().runTask(Sky.plugin(), r); }

    static void warn(String what, Throwable t) {
        try { Sky.log().log(Level.WARNING, "[ui] " + what, t); }
        catch (Throwable ignored) { t.printStackTrace(); }
    }

    static void info(String what) {
        try { Sky.log().info("[ui] " + what); } catch (Throwable ignored) { }
    }
}
