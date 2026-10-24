package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Real blocks with a meaning: any enchanting table opens "Enchant Item" (bookshelf power counted the
 * vanilla way), any anvil opens our Anvil, and a placed Experimentation Table (a head block tagged in
 * its PDC) opens the Experimentation Table. Listens at NORMAL: core denies vanilla use at HIGH.
 */
final class BlockListener implements Listener {

    private static NamespacedKey tableKey() { return new NamespacedKey(Sky.plugin(), "enchanting_exp_table"); }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return;   // let builders place against it
        Material m = b.getType();
        // consumeClick (core wave 5, our request 15): the held item's RIGHT_CLICK ability must not ALSO fire for the
        // click that opened a menu (PLAYTEST wave-4 issue 10: Shadow Fury from the enchanting table).
        if (m == Material.ENCHANTING_TABLE) {
            e.setCancelled(true);
            Sky.abilities().consumeClick(p);
            if (!EnchantingModule.ready(p)) return;
            TableMenu.open(p, bookshelfPower(b.getLocation()));
        } else if (m == Material.ANVIL || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL) {
            e.setCancelled(true);
            Sky.abilities().consumeClick(p);
            if (!EnchantingModule.ready(p)) return;
            AnvilMenu.open(p);
        } else if ((m == Material.PLAYER_HEAD || m == Material.PLAYER_WALL_HEAD) && isExpTable(b)) {
            e.setCancelled(true);
            Sky.abilities().consumeClick(p);
            if (!EnchantingModule.ready(p)) return;
            ExperimentsMenu.open(p);
        }
    }

    static boolean isExpTable(Block b) {
        BlockState st = b.getState(false);
        return st instanceof TileState ts && ts.getPersistentDataContainer().has(tableKey(), PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!"EXPERIMENTATION_TABLE".equals(Sky.items().idOf(e.getItemInHand()))) return;
        BlockState st = e.getBlockPlaced().getState(false);
        if (st instanceof TileState ts) {
            ts.getPersistentDataContainer().set(tableKey(), PersistentDataType.BYTE, (byte) 1);
            ts.update();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if ((b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD) && isExpTable(b)) {
            e.setDropItems(false);
            Sky.loot().give(e.getPlayer(), "EXPERIMENTATION_TABLE", 1, LootSource.OTHER);
        }
    }

    /** Vanilla bookshelf power: bookshelves two blocks out, at table height and one above (max 15). */
    static int bookshelfPower(Location table) {
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    if (table.clone().add(dx, dy, dz).getBlock().getType() == Material.BOOKSHELF) n++;
                }
            }
        }
        return Math.min(15, n);
    }

    /** Bookshelf power of an enchanting table within 6 blocks, else {@code fallback}. */
    static int nearbyPower(Player p, int fallback) {
        Location l = p.getLocation();
        for (int dx = -6; dx <= 6; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -6; dz <= 6; dz++) {
            Block b = l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
            if (b.getType() == Material.ENCHANTING_TABLE) return Math.max(fallback, bookshelfPower(b.getLocation()));
        }
        return fallback;
    }

    /** Make sure the hub library has a working table at its anchor (only fills an empty spot). */
    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (!"hub".equals(e.islandId) || e.instanceKey != null || !EnchantingModule.dials().placeHubTable.get()) return;
        Location a = Sky.worlds().anchor("hub", "npc.enchanting_table");
        if (a == null) return;
        Block at = a.getBlock();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            if (at.getRelative(dx, dy, dz).getType() == Material.ENCHANTING_TABLE) return;   // the builder placed one
        }
        if (at.getType().isAir() && at.getRelative(0, -1, 0).getType().isSolid()) at.setType(Material.ENCHANTING_TABLE, false);
    }
}
