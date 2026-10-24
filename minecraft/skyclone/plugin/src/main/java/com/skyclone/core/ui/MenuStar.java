package com.skyclone.core.ui;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The SkyBlock Menu nether star, locked in hotbar slot 9 (index 8) for every player whose profile is
 * loaded. It cannot be dropped, moved, stored, swapped, picked over, or lost on death; a repair pass
 * every 40 ticks puts it back and deletes strays. Clicking it opens {@code Features.SkyMenu}.
 *
 * <p>A module that needs slot 9 for something else (a dungeon class item, a minigame) can switch the
 * star off for one player by setting the player PDC key {@code skyclone:no_sbmenu} (any byte value);
 * removing the key brings it back on the next repair pass.
 */
public final class MenuStar implements Listener {

    public static final int SLOT = 8;
    static final NamespacedKey KEY = NamespacedKey.fromString("skyclone:sbmenu");
    /** Player PDC flag that suppresses the star for that player. */
    public static final NamespacedKey SUPPRESS = NamespacedKey.fromString("skyclone:no_sbmenu");

    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private BukkitTask task;

    MenuStar(JavaPlugin plugin) { this.plugin = plugin; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::repairAll, 40L, 40L); }

    void stop() { if (task != null) { task.cancel(); task = null; } }

    // ---- the item ------------------------------------------------------------------------------

    public static ItemStack star() {
        ItemStack it = Icons.of(Material.NETHER_STAR, "&aSkyBlock Menu &7(Click)",
                "&7View all of your SkyBlock progress,",
                "&7including your Skills, Collections,",
                "&7Recipes, and more!",
                "",
                "&eClick to open!");
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static boolean isStar(ItemStack it) {
        if (it == null || it.getType() != Material.NETHER_STAR || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    private static boolean enabled() {
        try { return UiDials.star.get(); } catch (Throwable t) { return true; }
    }

    private static boolean wanted(Player p) {
        if (!enabled()) return false;
        if (p.getPersistentDataContainer().has(SUPPRESS)) return false;
        try { return Sky.profiles().isLoaded(p); } catch (Throwable t) { return true; }
    }

    // ---- repair ---------------------------------------------------------------------------------

    void repairAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { repair(p); } catch (Throwable t) { UiUtil.warn("star repair for " + p.getName(), t); }
        }
    }

    /** Exactly one star, in slot 8, or none at all when not wanted. Items found in slot 8 are moved, never deleted. */
    void repair(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == SLOT) continue;
            if (isStar(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isStar(p.getItemOnCursor())) p.setItemOnCursor(null);
        ItemStack cur = inv.getItem(SLOT);
        if (!wanted(p)) {
            if (isStar(cur)) inv.setItem(SLOT, null);
            return;
        }
        if (isStar(cur)) {
            if (cur.getAmount() != 1) { cur.setAmount(1); inv.setItem(SLOT, cur); }
            return;
        }
        inv.setItem(SLOT, star());
        if (!UiUtil.empty(cur)) UiUtil.give(p, cur);
    }

    private void repairSoon(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) repair(p); });
    }

    // ---- opening the SkyBlock Menu ----------------------------------------------------------------

    static void openSkyMenu(Player p) {
        if (Sky.has(Features.SkyMenu.class)) {
            try { Sky.service(Features.SkyMenu.class).open(p); return; }
            catch (Throwable t) { UiUtil.warn("SkyMenu.open threw", t); }
        }
        placeholder().open(p);
    }

    static Menu placeholder() {
        Menu m = new Menu("SkyBlock Menu", 3);
        m.chrome();
        m.set(13, Icons.of(Material.BARRIER, "&cSkyBlock Menu unavailable",
                "&7The menu module is not loaded",
                "&7on this server right now.",
                "",
                "&7Ask an admin to check the logs."));
        return m;
    }

    private void openDebounced(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastOpen.get(p.getUniqueId());
        if (last != null && now - last < 300) return;
        lastOpen.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) openSkyMenu(p); });
    }

    // ---- guards ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean starHere = isStar(e.getCurrentItem());
        boolean starCursor = isStar(e.getCursor());
        boolean keySwap = (e.getClick() == ClickType.NUMBER_KEY || e.getAction() == InventoryAction.HOTBAR_SWAP)
                && e.getHotbarButton() == SLOT && isStar(p.getInventory().getItem(SLOT));
        boolean offhandSwap = e.getClick() == ClickType.SWAP_OFFHAND && isStar(p.getInventory().getItemInOffHand());
        if (!starHere && !starCursor && !keySwap && !offhandSwap) return;
        e.setCancelled(true);
        if (starHere && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)
                && e.getClickedInventory() instanceof PlayerInventory) {
            openDebounced(p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (isStar(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (isStar(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            repairSoon(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isStar(e.getMainHandItem()) || isStar(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPick(PlayerPickItemEvent e) {
        if (!isStar(e.getPlayer().getInventory().getItem(SLOT))) return;
        if (e.getTargetSlot() == SLOT || e.getSourceSlot() == SLOT) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isStar(e.getItem())) return;
        e.setCancelled(true);
        Action a = e.getAction();
        if (a != Action.PHYSICAL) openDebounced(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        guardEntity(e.getPlayer(), e.getRightClicked(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        guardEntity(e.getPlayer(), e.getRightClicked(), e);
    }

    /** Item frames, armour stands, allays... must never receive the star. NPC clicks are left alone. */
    private void guardEntity(Player p, Entity target, PlayerInteractEntityEvent e) {
        ItemStack hand = e.getHand() == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (!isStar(hand)) return;
        if (NpcServiceImpl.isNpc(target)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (isStar(e.getPlayerItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(MenuStar::isStar);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isStar(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (isStar(e.getItem().getItemStack())) { e.setCancelled(true); e.getItem().remove(); }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { repairSoon(e.getPlayer()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { repairSoon(e.getPlayer()); }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) { repairSoon(e.player); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastOpen.remove(e.getPlayer().getUniqueId()); }

    /** For the self-test: the item really carries the tag. */
    static String selfTest() {
        ItemStack s = star();
        if (!isStar(s)) return "star() is not recognised by isStar()";
        if (isStar(new ItemStack(Material.NETHER_STAR))) return "a plain nether star counts as the menu star";
        String name = Text.plain(s.getItemMeta().displayName());
        if (!name.startsWith("SkyBlock Menu")) return "unexpected star name: " + name;
        return null;
    }
}
