package org.aincrad.plugin.safety;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforces the Town safe polygon: no hostile spawning, no PvP, no fire spread, no explosion damage
 * inside the zone. Counters are exposed so {@code /aincrad verify spawn} can report what the guard
 * has actually blocked rather than merely asserting that it is installed.
 */
public final class SafeZoneGuard implements Listener {

    private final NamespacedKey aincradKey;
    private final AtomicLong blockedSpawns = new AtomicLong();
    private final AtomicLong blockedPvp = new AtomicLong();
    private final AtomicLong blockedFire = new AtomicLong();
    private final AtomicLong blockedExplosions = new AtomicLong();

    public SafeZoneGuard(NamespacedKey aincradKey) {
        this.aincradKey = aincradKey;
    }

    private boolean inZone(Location loc) {
        return loc.getWorld() != null
                && loc.getWorld().getKey().equals(aincradKey)
                && SafeZone.isSafe(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) {
            return;
        }
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        if (inZone(event.getLocation())) {
            event.setCancelled(true);
            blockedSpawns.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        boolean hostileSource = source instanceof Player || source instanceof Monster;
        if (hostileSource && inZone(event.getEntity().getLocation())) {
            event.setCancelled(true);
            blockedPvp.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            blockedFire.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
            return;   // deliberate player action, e.g. lighting a campfire
        }
        if (inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            blockedFire.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType().name().contains("FIRE") && inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            blockedFire.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (inZone(event.getLocation())) {
            event.blockList().clear();
            blockedExplosions.incrementAndGet();
        }
    }

    public long blockedSpawns() {
        return blockedSpawns.get();
    }

    public long blockedPvp() {
        return blockedPvp.get();
    }

    public long blockedFire() {
        return blockedFire.get();
    }

    public long blockedExplosions() {
        return blockedExplosions.get();
    }
}
