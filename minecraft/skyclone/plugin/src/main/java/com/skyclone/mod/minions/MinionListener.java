package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Placing, clicking, protecting. Everything that comes in from the world. */
final class MinionListener implements Listener {

    private final MinionsModule mod;

    MinionListener(MinionsModule mod) { this.mod = mod; }

    // ---- profiles ----------------------------------------------------------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        MinionStore s = mod.manager.store(p);
        mod.manager.indexStore(s);
        if (!s.placed.isEmpty()) mod.manager.welcome.put(Sky.profiles().profileId(p), p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline() || !Sky.profiles().isReady(p)) return;
            mod.manager.grantPendingXp(p);
            MinionStore st = mod.manager.store(p);
            if (mod.dials.starter.get() && !st.starterGiven) {
                st.starterGiven = true;
                if (st.placed.isEmpty() && st.crafted.isEmpty()) {
                    Sky.loot().give(p, mod.manager.minionItem("COBBLESTONE", 1, 0, false), LootSource.REWARD);
                    Msg.send(p, "&eYou received a " + MinionText.name(mod.cat.type("COBBLESTONE"), 1)
                            + "&e! Place it on your island and it will mine for you, even while you are offline.");
                }
            }
        }, 60L);
    }

    // ---- unique crafts -------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = false)
    public void onGain(SkyEvents.ItemGain e) {
        if (e.source != LootSource.CRAFT && e.source != LootSource.NPC_SHOP) return;
        TypeDef t = mod.cat.typeOfItem(e.itemId);
        if (t == null) return;
        int tier = MinionCatalog.tierOfItem(e.itemId);
        mod.manager.markCrafted(e.player, t.id, tier, true);
    }

    // ---- placing minions and crystals ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() == null) return;
        ItemStack hand = e.getItem();
        if (hand == null) return;
        String id = Sky.items().idOf(hand);
        if (id == null) return;
        boolean minion = mod.cat.isMinionItem(id);
        UpgradeDef u = mod.cat.upgrade(id);
        boolean crystal = MinionCatalog.isDeployable(u);
        boolean chest = u != null && "chest".equals(u.slot);
        if (!minion && !crystal && !chest) return;
        // a click on a minion's storage chest is that chest's (LOW handler below), never a placement against it
        if (mod.manager.chestTag(e.getClickedBlock()) != null && !e.getPlayer().isSneaking()) return;
        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        var face = e.getBlockFace();
        var slot = e.getHand();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (minion) mod.manager.tryPlace(p, hand, slot, b, face);
            else if (chest) mod.manager.tryPlaceChest(p, hand, slot, b, face);
            else mod.manager.tryPlaceCrystal(p, hand, slot, b, face);
        });
    }

    // ---- wave 5: the physical storage chest beside a minion ---------------------------------------------------

    /**
     * Right-clicking a minion's storage chest opens the minion's chest storage (never the vanilla chest, which
     * is always empty). LOW, so it runs before core's HIGH container guard; sneaking with a block in hand still
     * places against it like any block.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onChestClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.CHEST) return;
        String[] tag = mod.manager.chestTag(b);
        if (tag == null) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        ItemStack hand = e.getItem();
        if (e.getPlayer().isSneaking() && hand != null && hand.getType().isBlock() && !hand.getType().isAir()) return;
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) mod.manager.clickChest(p, b, tag); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.CHEST) return;
        String[] tag = mod.manager.chestTag(b);
        if (tag == null) return;
        e.setCancelled(true);                 // the minion code takes it off (or refuses), never a vanilla drop
        mod.manager.breakChest(e.getPlayer(), b, tag);
    }

    /** A hopper must never fill (or drain) a minion's chest block: it is empty decoration with a meaning. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestHopper(org.bukkit.event.inventory.InventoryMoveItemEvent e) {
        if (isMinionChestInv(e.getDestination()) || isMinionChestInv(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (isMinionChestInv(e.getInventory())) e.setCancelled(true);
    }

    private boolean isMinionChestInv(org.bukkit.inventory.Inventory inv) {
        if (inv == null || inv.getType() != org.bukkit.event.inventory.InventoryType.CHEST) return false;
        org.bukkit.Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) return false;
        return mod.manager.chestTag(l.getBlock()) != null;
    }

    /** A chest placed beside a minion's chest must not join it into a double chest (its half would be ours). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestNeighbour(org.bukkit.event.block.BlockPlaceEvent e) {
        Block placed = e.getBlockPlaced();
        if (placed.getType() != Material.CHEST && placed.getType() != Material.TRAPPED_CHEST) return;
        boolean near = false;
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            Block n = placed.getRelative(f);
            if (n.getType() == Material.CHEST && mod.manager.chestTag(n) != null) { near = true; singleChest(n); }
        }
        if (near) singleChest(placed);
    }

    private static void singleChest(Block b) {
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest c && c.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            c.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
            b.setBlockData(c, false);
        }
    }

    // ---- clicking a minion ----------------------------------------------------------------------------------

    private String[] tag(Entity ent, org.bukkit.NamespacedKey key) {
        if (!(ent instanceof ArmorStand)) return null;
        String v = ent.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (v == null) return null;
        int i = v.indexOf('|');
        return i < 0 ? null : new String[]{v.substring(0, i), v.substring(i + 1)};
    }

    private void open(Player p, Entity ent) {
        String[] t = tag(ent, mod.visuals.standKey);
        if (t != null) {
            if (!Sky.profiles().isReady(p)) return;
            if (!t[0].equals(Sky.profiles().profileId(p))) { Msg.error(p, "This minion belongs to someone else!"); return; }
            MinionStore.Placed m = mod.manager.store(p).placed.get(t[1]);
            if (m == null) { ent.remove(); return; }
            mod.menus.openMinion(p, t[1]);
            return;
        }
        String[] gift = tag(ent, mod.visuals.presentKey);
        if (gift != null) { mod.manager.claimPresent(p, gift[0], ent); return; }
        String[] c = tag(ent, mod.visuals.crystalKey);
        if (c != null) {
            if (!Sky.profiles().isReady(p)) return;
            if (!c[0].equals(Sky.profiles().profileId(p))) { Msg.error(p, "This crystal belongs to someone else!"); return; }
            mod.menus.openCrystal(p, c[1]);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (isOurs(ent)) {
            e.setCancelled(true);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) open(e.getPlayer(), ent);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (isOurs(ent) || isMob(ent)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (isOurs(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity ent = e.getEntity();
        if (!isOurs(ent) && !isMob(ent)) return;
        e.setCancelled(true);
        if (isOurs(ent) && e.getDamager() instanceof Player p) open(p, ent);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (isOurs(e.getEntity()) || isMob(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onShear(PlayerShearEntityEvent e) {
        if (isMob(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDrop(EntityDropItemEvent e) {
        if (isMob(e.getEntity())) e.setCancelled(true);   // chickens laying eggs
    }

    private boolean isOurs(Entity e) {
        if (!(e instanceof ArmorStand)) return false;
        var pdc = e.getPersistentDataContainer();
        return pdc.has(mod.visuals.standKey, PersistentDataType.STRING) || pdc.has(mod.visuals.crystalKey, PersistentDataType.STRING)
                || pdc.has(mod.visuals.presentKey, PersistentDataType.STRING);
    }

    private boolean isMob(Entity e) {
        return e != null && e.getPersistentDataContainer().has(mod.visuals.mobKey, PersistentDataType.STRING);
    }

    // ---- the blocks minions place: never a source of free drops ---------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!mod.manager.isVisualBlock(e.getBlock())) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        mod.manager.forgetVisual(e.getPlayer(), e.getBlock());
    }

    /** What a minion's decorative plants drop when they pop off (never anything a player throws). */
    private static final java.util.Set<Material> POP_DROPS = java.util.EnumSet.of(Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROT, Material.POTATO, Material.POISONOUS_POTATO, Material.CACTUS, Material.SUGAR_CANE, Material.NETHER_WART,
            Material.RED_MUSHROOM, Material.BROWN_MUSHROOM, Material.SUNFLOWER, Material.LILY_PAD, Material.DANDELION, Material.POPPY,
            Material.ALLIUM, Material.AZURE_BLUET, Material.BLUE_ORCHID, Material.OXEYE_DAISY, Material.RED_TULIP,
            Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.COCOA_BEANS);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        if (item.getThrower() != null || item.getOwner() != null) return;      // thrown or owned: a player's item
        if (!POP_DROPS.contains(item.getItemStack().getType())) return;
        Block b = e.getLocation().getBlock();
        if (mod.manager.isVisualBlock(b) || mod.manager.isVisualBlock(b.getRelative(0, -1, 0))
                || mod.manager.isVisualBlock(b.getRelative(0, 1, 0))) {
            e.setCancelled(true);   // a crop/cactus a minion planted popped off: it is decoration, not loot
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        // sugar cane / cactus a minion planted must not grow free extra blocks
        Block b = e.getBlock();
        if (mod.manager.isVisualBlock(b) || mod.manager.isVisualBlock(b.getRelative(0, -1, 0))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (mod.manager.isVisualBlock(e.getSource())) e.setCancelled(true);   // minion mushrooms do not spread
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        Block b = e.getBlock();
        if (mod.manager.isVisualBlock(b) || mod.manager.isVisualBlock(b.getRelative(0, 1, 0))) e.setCancelled(true);
    }

    /**
     * A piston would carry a minion's block (a Diamond Minion's ore) out of the protected spot, where breaking
     * it pays out a real diamond. Minion blocks do not move.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (mod.manager.isVisualBlock(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (mod.manager.isVisualBlock(b)) { e.setCancelled(true); return; }
    }

    /** An explosion on the island (BUILD zones allow them) must not turn minion blocks into drops. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> mod.manager.isVisualBlock(b) || mod.manager.chestTag(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> mod.manager.isVisualBlock(b) || mod.manager.chestTag(b) != null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mod.manager.forget(e.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        // bodies are not persistent; anything carrying our tag that we do not track is a leftover
        for (Entity ent : e.getChunk().getEntities()) {
            if (isOurs(ent) && !mod.manager.stands.containsValue(ent.getUniqueId())
                    && !mod.manager.crystalStands.containsValue(ent.getUniqueId())
                    && !mod.manager.presentStands.containsValue(ent.getUniqueId())) ent.remove();
            else if (isMob(ent)) ent.remove();
        }
    }
}
