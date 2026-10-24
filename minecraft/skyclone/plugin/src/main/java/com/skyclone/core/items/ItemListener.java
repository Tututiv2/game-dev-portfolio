package com.skyclone.core.items;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Vanilla -> sky conversion. Item entities are converted the moment they spawn (block drops, mob
 * drops), and a player's own inventory is swept one tick after anything that can put a vanilla
 * stack in it (pickup, click, drag, close, join). Menu inventories are never touched — only the
 * player's own inventory and ender chest are ever scanned.
 */
final class ItemListener implements Listener {

    private final ItemServiceImpl items;
    private final DupeScanner dupes;
    private final Set<UUID> pending = new LinkedHashSet<>();

    ItemListener(ItemServiceImpl items, DupeScanner dupes) {
        this.items = items;
        this.dupes = dupes;
    }

    /** Called every tick by ItemsCore. */
    void tick() {
        if (pending.isEmpty()) return;
        UUID[] ids = pending.toArray(new UUID[0]);
        pending.clear();
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            try { items.sweep(p, false); } catch (RuntimeException e) {
                items.warnThrottled("sweep", "inventory sweep failed: " + e);
            }
        }
    }

    void schedule(Player p) {
        if (p != null) pending.add(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        if (!ItemsDials.flag(ItemsDials.CONVERT_VANILLA, true)) return;
        Item ent = e.getEntity();
        ItemStack s = ent.getItemStack();
        ItemStack c = items.convert(s);
        if (c == null) return;
        List<ItemStack> extra = items.convertExtras(s);
        ent.setItemStack(c);
        for (ItemStack x : extra) ent.getWorld().dropItem(ent.getLocation(), x);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (ItemsDials.flag(ItemsDials.CONVERT_VANILLA, true)) {
            Item ent = e.getItem();
            ItemStack s = ent.getItemStack();
            if (items.idOf(s) == null) {
                ItemStack c = items.convert(s);
                if (c != null && items.convertExtras(s).isEmpty()) ent.setItemStack(c);
            }
        }
        schedule(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) schedule(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) schedule(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        // a Menu's free slots are returned by the menu itself; the sweep only reads the player's own inventory
        if (e.getPlayer() instanceof Player p) schedule(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // next tick: convert, re-render everything (renames, new lore), then scan for dupes
        Bukkit.getScheduler().runTaskLater(items.plugin(), () -> {
            if (!p.isOnline()) return;
            try {
                items.sweep(p, false);
                items.refreshInventory(p, null);
                dupes.scan(List.of(p), "join");
            } catch (RuntimeException ex) {
                items.warnThrottled("join-sweep", "join sweep failed: " + ex);
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
