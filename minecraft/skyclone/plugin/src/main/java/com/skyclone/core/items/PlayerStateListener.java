package com.skyclone.core.items;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.skyclone.api.event.SkyEvents;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Stats invalidation and the vitals rules that vanilla would otherwise break: no hunger, no
 * vanilla regen, no vanilla death.
 */
final class PlayerStateListener implements Listener {

    private final StatServiceImpl stats;
    private final VitalsServiceImpl vitals;

    PlayerStateListener(StatServiceImpl stats, VitalsServiceImpl vitals) {
        this.stats = stats;
        this.vitals = vitals;
    }

    // ---- stats invalidation --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(PlayerArmorChangeEvent e) { stats.invalidate(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) { stats.invalidate(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwap(PlayerSwapHandItemsEvent e) { stats.invalidate(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) { stats.invalidate(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p) stats.invalidate(p); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) stats.invalidate(p); }

    // ---- lifecycle -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setFoodLevel(20);
        p.setSaturation(5f);
        stats.invalidate(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        stats.recompute(e.player);
        vitals.attachProfile(e.player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(SkyEvents.ProfileUnload e) {
        vitals.detachProfile(e.player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // stay one tick so ProfileUnload listeners can still read stats/vitals
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(ItemsCore.plugin(), () -> {
            if (p.isOnline()) return;
            stats.forget(p);
            vitals.forget(p);
        });
    }

    // ---- vitals rules --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p) || !ItemsDials.flag(ItemsDials.DISABLE_HUNGER, true)) return;
        if (e.getFoodLevel() < 20) {
            e.setCancelled(true);
            p.setFoodLevel(20);
        }
    }

    /** The gauge is driven by the custom pool; vanilla regen would desync it. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof Player) e.setCancelled(true);
    }

    /**
     * Safety net, after combat had its turn (HIGHEST, ignoreCancelled): any vanilla damage still
     * landing on a player is converted proportionally into custom damage, and its vanilla amount is
     * zeroed so knockback and the red flash survive but vanilla can never kill.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !ItemsDials.flag(ItemsDials.VANILLA_DAMAGE_NET, true)) return;
        double fin = e.getFinalDamage();
        if (!(fin > 0)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        String cause = causeName(c);
        if (c == EntityDamageEvent.DamageCause.VOID || c == EntityDamageEvent.DamageCause.KILL
                || c == EntityDamageEvent.DamageCause.SUICIDE || c == EntityDamageEvent.DamageCause.WORLD_BORDER) {
            e.setCancelled(true);
            vitals.kill(p, cause);
            return;
        }
        double vmax = 20;
        try {
            var a = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (a != null && a.getValue() > 0) vmax = a.getValue();
        } catch (RuntimeException ignored) {}
        double dmg = fin / vmax * vitals.maxHealth(p);
        e.setDamage(0);
        double abs = vitals.absorption(p);
        if (abs > 0) {
            double used = Math.min(abs, dmg);
            vitals.addAbsorption(p, -used);
            dmg -= used;
        }
        vitals.markHurt(p);
        if (dmg <= 0) return;
        double left = vitals.health(p) - dmg;
        if (left <= 0) {
            vitals.setHealth(p, 0);
            vitals.kill(p, cause);
        } else {
            vitals.setHealth(p, left);
        }
    }

    static String causeName(EntityDamageEvent.DamageCause c) {
        return switch (c) {
            case VOID -> "Fell into the void";
            case FALL -> "Fall damage";
            case LAVA -> "Lava";
            case FIRE, FIRE_TICK -> "Burned";
            case DROWNING -> "Drowned";
            case SUFFOCATION -> "Suffocated";
            case STARVATION -> "Starved";
            case POISON -> "Poison";
            case WITHER -> "Wither";
            case LIGHTNING -> "Lightning";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "Explosion";
            case FREEZE -> "Froze";
            case CONTACT -> "Pricked";
            default -> com.skyclone.api.ui.Text.titleCase(c.name());
        };
    }

    /** Should never happen: vanilla killed a player anyway. Keep everything, no coin loss, full heal. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        ItemsCore.plugin().getLogger().warning("[vitals] vanilla death of " + e.getEntity().getName()
                + " got past the vitals pipeline (" + e.getEntity().getLastDamageCause() + "); inventory kept");
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
        Player p = e.getEntity();
        Bukkit.getScheduler().runTaskLater(ItemsCore.plugin(), () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Location to = vitals.respawnPoint(p);
        if (to != null) e.setRespawnLocation(to);
        vitals.parkIfLimbo(p, to);
        Bukkit.getScheduler().runTaskLater(ItemsCore.plugin(), () -> {
            if (!p.isOnline()) return;
            vitals.setHealth(p, vitals.maxHealth(p));
            vitals.addMana(p, vitals.maxMana(p));
            p.setFoodLevel(20);
        }, 1L);
    }
}
