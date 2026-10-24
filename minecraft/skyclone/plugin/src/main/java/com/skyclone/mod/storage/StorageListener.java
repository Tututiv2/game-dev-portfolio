package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Sacks absorbing loot and pickups, ender chest blocks, right-clicking sack/backpack items, and session cleanup. */
final class StorageListener implements Listener {

    private final StorageModule mod;

    StorageListener(StorageModule mod) { this.mod = mod; }

    /**
     * After the deletor (accessories, HIGH) and before collections (MONITOR, which still counts a cancelled gain).
     * A partial fit stores what fits and re-gives the rest as a SACK-sourced gain, which this listener ignores.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGain(SkyEvents.ItemGain e) {
        if (e.player == null || e.source == null || e.amount <= 0) return;
        if (e.source == LootSource.SACK || e.source == LootSource.STORAGE) return;
        if (e.source == LootSource.ADMIN && !StorageDials.SACK_ADMIN.get()) return;
        if (!StorageDials.SACK_LOOT.get()) return;
        if (!mod.catalog().sackable(e.itemId) && !mod.catalog().isArrow(e.itemId)) return;
        if (e.stack != null && !Codec.sackable(e.stack)) return;
        int taken = mod.sacks().absorb(e.player, e.stack, e.itemId, e.amount);
        if (taken <= 0) return;
        e.setCancelled(true);
        if (taken < e.amount && e.stack != null) {
            ItemStack rest = e.stack.clone();
            rest.setAmount(e.amount - taken);
            Sky.loot().give(e.player, rest, LootSource.SACK);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !StorageDials.SACK_PICKUP.get() || !Store.ready(p)) return;
        Item ent = e.getItem();
        ItemStack s = ent.getItemStack();
        if (Codec.empty(s) || !Codec.sackable(s)) return;
        String id = Codec.id(s);
        if (!mod.catalog().sackable(id) && !mod.catalog().isArrow(id)) return;
        int amount = s.getAmount();
        int taken = mod.sacks().absorb(p, s, id, amount);
        if (taken <= 0) return;
        e.setCancelled(true);
        try { p.playPickupItemAnimation(ent, taken); } catch (Throwable ignored) { }
        if (taken >= amount) {
            ent.remove();
        } else {
            ItemStack rest = s.clone();
            rest.setAmount(amount - taken);
            ent.setItemStack(rest);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.4f);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (a == Action.RIGHT_CLICK_BLOCK && b != null && b.getType() == Material.ENDER_CHEST && StorageDials.EC_BLOCK.get() && !p.isSneaking()) {
            e.setCancelled(true);
            mod.storageMenus().openStorage(p);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (Codec.empty(hand)) return;
        String id = Sky.items().idOf(hand);
        if (id == null) return;
        if (mod.catalog().isSack(id)) {
            e.setCancelled(true);
            Catalog.Sack s = mod.catalog().sacks.get(id);
            mod.sackMenus().openFamily(p, s.family(), 0);
        } else if (mod.catalog().isBackpack(id)) {
            e.setCancelled(true);
            mod.storageMenus().openStorage(p);
            com.skyclone.api.ui.Msg.send(p, "&eClick an empty &6Backpack Slot &eto put your backpack in it.");
        }
    }

    /** Fires before core saves on quit and at shutdown: flush any storage menu still open. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnload(SkyEvents.ProfileUnload e) {
        mod.flushOpen(e.player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        java.util.UUID id = e.getPlayer().getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> mod.forget(id));
    }
}
