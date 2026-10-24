package com.skyclone.core.items;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Stops vanilla mechanics from eating or altering CUSTOM sky items: an Enchanted Diamond is a
 * DIAMOND underneath, and without these guards it would craft into a vanilla diamond block, burn
 * in a furnace, get vanilla-enchanted on an anvil or be placed and lost.
 *
 * <p>"Custom" means a sky id different from {@code vanillaId(material)}: a sky WHEAT (which is
 * just the vanilla wheat with lore) behaves exactly like vanilla wheat. Escape hatches on the
 * ItemDef: tag {@code placeable} (may be placed as a block), {@code consumable} (may be eaten /
 * drunk; categories CONSUMABLE and POTION are allowed too), {@code throwable} (may be thrown).
 * All guards run at HIGHEST with ignoreCancelled, so a module that handled the event itself and
 * cancelled it is never second-guessed.
 */
final class VanillaGuardListener implements Listener {

    private final ItemServiceImpl items;

    VanillaGuardListener(ItemServiceImpl items) { this.items = items; }

    private static boolean on() { return ItemsDials.flag(ItemsDials.VANILLA_GUARDS, true); }

    /** A sky item that is NOT just the vanilla item of its material. */
    private boolean custom(ItemStack s) {
        String id = items.idOf(s);
        return id != null && !id.equals(items.vanillaId(s.getType()));
    }

    private ItemDef defOf(ItemStack s) { return items.def(items.idOf(s)); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!on()) return;
        ItemStack s = e.getItemInHand();
        if (!custom(s)) return;
        ItemDef d = defOf(s);
        if (d != null && d.has(ItemServiceImpl.TAG_PLACEABLE)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        if (!on()) return;
        for (ItemStack s : e.getInventory().getMatrix()) {
            if (custom(s)) { e.getInventory().setResult(null); return; }
        }
    }

    /** Anvil, smithing table, grindstone (all share this handler list). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareResult(PrepareInventoryResultEvent e) {
        if (!on()) return;
        InventoryType t = e.getInventory().getType();
        boolean strict = t == InventoryType.ANVIL || t == InventoryType.SMITHING || t == InventoryType.GRINDSTONE;
        ItemStack[] c = e.getInventory().getContents();
        for (ItemStack s : c) {
            if (s == null) continue;
            if (strict ? items.idOf(s) != null : custom(s)) { e.setResult(null); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchantTable(PrepareItemEnchantEvent e) {
        if (!on()) return;
        if (items.idOf(e.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent e) {
        if (!on()) return;
        if (custom(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFuel(FurnaceBurnEvent e) {
        if (!on()) return;
        if (custom(e.getFuel())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!on()) return;
        ItemStack s = e.getItem();
        if (!custom(s)) return;
        ItemDef d = defOf(s);
        if (d != null && (d.has("consumable") || d.category == ItemCategory.CONSUMABLE || d.category == ItemCategory.POTION)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThrow(PlayerLaunchProjectileEvent e) {
        if (!on()) return;
        ItemStack s = e.getItemStack();
        if (!custom(s)) return;
        ItemDef d = defOf(s);
        if (d != null && d.has("throwable")) return;
        e.setCancelled(true);
    }

    /** Right-click-on-block uses that consume or transform the item (bone meal, buckets, eggs, flint & steel...). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (!on() || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack s = e.getItem();
        if (s == null || !custom(s) || !blockUse(s.getType())) return;
        ItemDef d = defOf(s);
        if (d != null && (d.has(ItemServiceImpl.TAG_PLACEABLE) || d.has("usable"))) return;
        e.setUseItemInHand(Event.Result.DENY);
    }

    private static boolean blockUse(Material m) {
        String n = m.name();
        return n.equals("BONE_MEAL") || n.equals("FLINT_AND_STEEL") || n.equals("FIRE_CHARGE") || n.endsWith("_BUCKET")
                || n.endsWith("_SPAWN_EGG") || n.equals("ENDER_EYE") || n.equals("ARMOR_STAND") || n.endsWith("ITEM_FRAME")
                || n.equals("PAINTING") || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("MINECART")
                || n.equals("END_CRYSTAL") || n.equals("FIREWORK_ROCKET") || n.equals("GLASS_BOTTLE") || n.equals("HONEYCOMB")
                || n.equals("WRITABLE_BOOK") || n.equals("LEAD") || n.equals("BRUSH") || n.endsWith("_DYE") || n.equals("INK_SAC")
                || n.equals("GLOW_INK_SAC") || n.equals("SNOWBALL") || n.equals("EGG");
    }
}
