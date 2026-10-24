package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Quiver Arrow: how a DRAWN bow shoots straight from the quiver.
 *
 * <p>Vanilla only lets a bow draw when an arrow item is in the inventory, but the storage module puts
 * every arrow a player earns or picks up into the quiver (String III onwards). So without this a Hunter
 * Bow stops working the moment the quiver unlocks. The reference answers it the same way we do: while
 * you hold a bow and carry no loose arrows, hotbar slot 9 (normally the SkyBlock Menu) shows a "Quiver
 * Arrow" with your selected arrow and count; the bow draws from it, and every shot really takes one
 * arrow out of the quiver ({@link Bows#onShoot}). Switch away from the bow and the SkyBlock Menu comes back.
 *
 * <p>The proxy is a {@code TIPPED_ARROW} with no potion and the plain arrow model: core never converts
 * a tipped arrow into a sky item and never counts it as {@code ARROW}, so it can not be sold, stored,
 * crafted or counted as a real arrow. It can not be moved, dropped, swapped or kept on death, a 10-tick
 * pass re-checks every holder, and it is stripped on profile unload, quit, join and disable (a crash
 * leaves one in a saved inventory: the join sweep deletes it).
 *
 * <p>Slot 9 is borrowed through core's documented switch, the player PDC flag {@code skyclone:no_sbmenu};
 * we add our own marker so we only ever lift a suppression we set. Inside a dungeon slot 9 is the map,
 * so the proxy takes the first empty slot instead.
 */
final class QuiverArrow implements Listener {

    static final NamespacedKey PROXY = NamespacedKey.fromString("skyclone:weapons_quiver_arrow");
    private static final NamespacedKey SUPPRESS = NamespacedKey.fromString("skyclone:no_sbmenu");
    private static final NamespacedKey OURS = NamespacedKey.fromString("skyclone:weapons_quiver_sbmenu");
    private static final NamespacedKey STAR = NamespacedKey.fromString("skyclone:sbmenu");
    static final int MENU_SLOT = 8;

    private final Traits traits;
    /** The SkyBlock Menu star we lifted out of slot 9, to put straight back. */
    private final Map<UUID, ItemStack> stashedStar = new HashMap<>();
    /** Players who (may) carry a proxy: the 10-tick pass checks them even when no longer holding a bow. */
    private final Set<UUID> holders = new HashSet<>();

    QuiverArrow(Traits traits) {
        this.traits = traits;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tickAll, 20L, 10L);
    }

    // ---- the item ------------------------------------------------------------------------------

    static boolean isProxy(ItemStack s) {
        if (s == null || s.getType() != Material.TIPPED_ARROW || !s.hasItemMeta()) return false;
        return s.getItemMeta().getPersistentDataContainer().has(PROXY, PersistentDataType.BYTE);
    }

    static ItemStack build(ItemDef arrow, int count) {
        String name = (arrow.rarity == null ? "&f" : arrow.rarity.code()) + arrow.name;
        List<String> lore = new ArrayList<>();
        lore.add("&8Quiver Arrow");
        lore.add("");
        lore.add("&7Arrows left: &a" + Text.commasInt(count));
        if (arrow.damage > 0) lore.add("&7Arrow Damage: &c+" + Text.commasInt(arrow.damage));
        lore.add("");
        lore.add("&7This item is in your inventory because");
        lore.add("&7you are holding your bow. Switch your");
        lore.add("&7held item to see the SkyBlock Menu.");
        lore.add("");
        lore.add("&eUse /arrows to choose your arrows.");
        ItemStack it = Icons.of(Material.TIPPED_ARROW, name, lore);
        ItemMeta meta = it.getItemMeta();
        try { meta.setItemModel(NamespacedKey.minecraft("arrow")); } catch (Throwable ignored) { }
        meta.getPersistentDataContainer().set(PROXY, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(PROXY_ID, PersistentDataType.STRING, arrow.id);
        it.setItemMeta(meta);
        it.setAmount(Math.max(1, Math.min(64, count)));
        return it;
    }

    private static final NamespacedKey PROXY_ID = NamespacedKey.fromString("skyclone:weapons_quiver_arrow_id");

    private static boolean isStar(ItemStack s) {
        return s != null && s.getType() == Material.NETHER_STAR && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(STAR, PersistentDataType.BYTE);
    }

    private static boolean realArrow(ItemStack s) {
        if (s == null || isProxy(s)) return false;
        Material m = s.getType();
        return m == Material.ARROW || m == Material.TIPPED_ARROW || m == Material.SPECTRAL_ARROW;
    }

    // ---- when is one wanted -----------------------------------------------------------------------

    /** The arrow the proxy should show, or null when the player should not have one. */
    private ItemDef wanted(Player p) {
        if (!WeaponDials.QUIVER_DRAW.get() || !p.isOnline() || p.isDead()) return null;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return null;
        if (WeaponsModule.data(p) == null) return null;               // profile not ready
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BOW) return null;   // crossbows load at a different moment: not supported
        if (traits.of(Traits.idOf(hand)).isShortbow()) return null;   // shortbows never draw
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getContents()) if (realArrow(s)) return null;   // vanilla draws from those
        return Quiver.pick(p);
    }

    // ---- sync ---------------------------------------------------------------------------------------

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean holding = p.getInventory().getItemInMainHand().getType() == Material.BOW;
            if (!holding && !holders.contains(p.getUniqueId())) continue;
            try { sync(p, false); } catch (RuntimeException ex) {
                Sky.log().warning("[weapons] quiver arrow sync for " + p.getName() + ": " + ex.getMessage());
            }
        }
    }

    void syncSoon(Player p, boolean force) {
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) sync(p, force); });
    }

    /** Put, refresh or remove the proxy. {@code force} re-sends the slot (after a shot the client counted one down). */
    void sync(Player p, boolean force) {
        ItemDef arrow = wanted(p);
        if (arrow == null) { removeAll(p); return; }
        PlayerInventory inv = p.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (!isProxy(inv.getItem(i))) continue;
            if (slot < 0) slot = i; else inv.setItem(i, null);
        }
        if (isProxy(p.getItemOnCursor())) p.setItemOnCursor(null);
        int count = Quiver.available(p, arrow.id);
        if (count <= 0) { removeAll(p); return; }
        ItemStack want = build(arrow, count);
        if (slot < 0) {
            slot = chooseSlot(p);
            if (slot < 0) return;   // nowhere to put it: the bow simply can not draw (same as vanilla with a full bar)
        } else if (!force) {
            ItemStack cur = inv.getItem(slot);
            if (cur != null && cur.getAmount() == want.getAmount() && arrow.id.equals(proxyId(cur))) {
                holders.add(p.getUniqueId());
                return;
            }
        }
        inv.setItem(slot, want);
        holders.add(p.getUniqueId());
    }

    private static String proxyId(ItemStack s) {
        try { return s.getItemMeta().getPersistentDataContainer().get(PROXY_ID, PersistentDataType.STRING); }
        catch (RuntimeException e) { return null; }
    }

    private int chooseSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean dungeon = false;
        if (Sky.has(Features.Dungeons.class)) {
            try { dungeon = Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException ignored) { }
        }
        ItemStack nine = inv.getItem(MENU_SLOT);
        if (!dungeon && inv.getHeldItemSlot() != MENU_SLOT && (isStar(nine) || nine == null || nine.getType().isAir())) {
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            if (!pdc.has(SUPPRESS)) {
                pdc.set(SUPPRESS, PersistentDataType.BYTE, (byte) 1);
                pdc.set(OURS, PersistentDataType.BYTE, (byte) 1);
            }
            if (isStar(nine)) stashedStar.put(p.getUniqueId(), nine.clone());
            return MENU_SLOT;
        }
        for (int i = 9; i < 36; i++) { ItemStack s = inv.getItem(i); if (s == null || s.getType().isAir()) return i; }
        for (int i = 0; i < 9; i++) {
            if (i == inv.getHeldItemSlot()) continue;
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) return i;
        }
        return -1;
    }

    /** Strip every proxy and give slot 9 back to the SkyBlock Menu. */
    void removeAll(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isProxy(inv.getItem(i))) inv.setItem(i, null);
        if (isProxy(p.getItemOnCursor())) p.setItemOnCursor(null);
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(OURS)) {
            pdc.remove(OURS);
            pdc.remove(SUPPRESS);
        }
        ItemStack star = stashedStar.remove(p.getUniqueId());
        if (star != null) {
            ItemStack nine = inv.getItem(MENU_SLOT);
            if (nine == null || nine.getType().isAir()) inv.setItem(MENU_SLOT, star);
        }
        holders.remove(p.getUniqueId());
    }

    void removeEverywhere() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { removeAll(p); } catch (RuntimeException ignored) { }
        }
    }

    int holderCount() { return holders.size(); }

    // ---- triggers -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) { syncSoon(e.getPlayer(), false); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { removeAll(e.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { removeAll(e.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnload(SkyEvents.ProfileUnload e) { if (e.player != null) removeAll(e.player); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        if (e.player == null) return;
        removeAll(e.player);
        syncSoon(e.player, false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { syncSoon(e.getPlayer(), false); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { syncSoon(e.getPlayer(), false); }

    // ---- guards: the proxy never leaves its slot ----------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean here = isProxy(e.getCurrentItem()) || isProxy(e.getCursor());
        boolean key = (e.getClick() == ClickType.NUMBER_KEY || e.getAction() == InventoryAction.HOTBAR_SWAP)
                && e.getHotbarButton() >= 0 && isProxy(p.getInventory().getItem(e.getHotbarButton()));
        boolean off = e.getClick() == ClickType.SWAP_OFFHAND && isProxy(p.getInventory().getItemInOffHand());
        if (here || key || off) e.setCancelled(true);
        if (holders.contains(p.getUniqueId()) || here) syncSoon(p, false);   // real arrows moved in or out
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (isProxy(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (!isProxy(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        syncSoon(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isProxy(e.getMainHandItem()) || isProxy(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPick(PlayerPickItemEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        int t = e.getTargetSlot(), s = e.getSourceSlot();
        if ((t >= 0 && t < inv.getSize() && isProxy(inv.getItem(t))) || (s >= 0 && s < inv.getSize() && isProxy(inv.getItem(s)))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(QuiverArrow::isProxy);
        e.getItemsToKeep().removeIf(QuiverArrow::isProxy);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isProxy(e.getEntity().getItemStack())) e.setCancelled(true);
    }
}
