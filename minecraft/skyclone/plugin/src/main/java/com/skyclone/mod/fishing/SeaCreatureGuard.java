package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The vanilla side-effects of sea creatures' entity types, which reach the player OUTSIDE the
 * damage pipeline and so ignore Defense (the vitals safety net converts vanilla damage as a
 * percentage of max health). Playtest "loops": a Lv15 Sea Witch took a 693 HP / 706 Defense player
 * to 2 HP and killed one that kept fishing. Core's {@code MobListener.onMobPotion} fixed the potion
 * itself while its thrower is alive; this closes the rest of that class for sea creatures:
 * <ul>
 *   <li><b>Orphaned shots.</b> A potion or arrow still in the air when its creature dies (the usual
 *       case: you kill the witch while her bottle is flying) has no live shooter, so core no longer
 *       recognises it: the splash landed as vanilla Instant Damage / Poison, an arrow as plain
 *       PROJECTILE environment damage. Every projectile a sea creature launches is tagged here; a
 *       tagged splash never applies a vanilla effect and a tagged shot whose creature is gone fizzles.</li>
 *   <li><b>On-hit effects.</b> Wither (Wither Skeleton types: Fire Eel, Thunder, ...), Poison arrows
 *       (Bogged), Slowness arrows (Stray types) are added by vanilla right after a hit the pipeline
 *       already charged; Poison and Wither then tick as Defense-ignoring vanilla damage.</li>
 *   <li><b>Elder Guardian curse.</b> Mining Fatigue III to every player within 50 blocks every
 *       minute, which slows the vanilla swing and is not part of the reference creature.</li>
 *   <li><b>Guardian thorns.</b> Hitting a Guardian whose spikes are out hurts the attacker with
 *       vanilla THORNS from the guardian, which core routes as a full mob hit (def.damage per swing).</li>
 * </ul>
 */
final class SeaCreatureGuard implements Listener {

    /** On a projectile: the id of the sea creature that launched it. */
    static final NamespacedKey SHOT = new NamespacedKey("skyclone", "fishing_shot");

    /** Player -> server tick of the last hit a sea creature (or its shot) landed on them. */
    private static final Map<UUID, Integer> LAST_HIT = new HashMap<>();

    /** Elder Guardian curse radius in vanilla (50) plus a block of slack. */
    private static final double CURSE_RADIUS = 51;

    static boolean isSea(Entity e) {
        return e instanceof LivingEntity le && Fishing.isSeaCreature(Sky.mobs().defOf(le));
    }

    /** True when {@code e} is a sea creature still alive in the pipeline. */
    private static boolean liveSea(Object e) {
        return e instanceof LivingEntity le && le.isValid() && !le.isDead() && isSea(le);
    }

    /** Mark a projectile as launched by a sea creature (no-op otherwise). Public for the self-test. */
    static void tag(Projectile proj, LivingEntity shooter) {
        MobDef d = shooter == null ? null : Sky.mobs().defOf(shooter);
        if (!Fishing.isSeaCreature(d)) return;
        proj.getPersistentDataContainer().set(SHOT, PersistentDataType.STRING, d.id);
    }

    static boolean tagged(Entity e) {
        return e != null && e.getPersistentDataContainer().has(SHOT, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof LivingEntity le) tag(e.getEntity(), le);
    }

    /**
     * After core (HIGH) has charged a live thrower's splash, no sea-creature potion applies a vanilla
     * effect to anyone, live thrower or not. Core reads the intensities before it zeroes them, so
     * this must run after it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion tp = e.getPotion();
        if (!tagged(tp) && !isSea(tp.getShooter() instanceof Entity s ? s : null)) return;
        for (LivingEntity le : e.getAffectedEntities()) e.setIntensity(le, 0);
    }

    /** Before core (HIGH): guardian thorns, and shots whose creature is gone. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Entity d = e.getDamager();
        if (e.getCause() == EntityDamageEvent.DamageCause.THORNS && isSea(d)) { e.setCancelled(true); return; }
        if (d instanceof Projectile proj && tagged(proj)) {
            ProjectileSource src = proj.getShooter();
            if (!liveSea(src)) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitLanded(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager();
        boolean sea = d instanceof Projectile proj ? tagged(proj) || isSea(proj.getShooter() instanceof Entity s ? s : null) : isSea(d);
        if (sea) LAST_HIT.put(p.getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null) return;
        EntityPotionEffectEvent.Action a = e.getAction();
        if (a != EntityPotionEffectEvent.Action.ADDED && a != EntityPotionEffectEvent.Action.CHANGED) return;
        EntityPotionEffectEvent.Cause c = e.getCause();
        if (c == EntityPotionEffectEvent.Cause.ATTACK || c == EntityPotionEffectEvent.Cause.ARROW) {
            Integer at = LAST_HIT.get(p.getUniqueId());
            if (at != null && at == Bukkit.getCurrentTick()) { e.setCancelled(true); return; }
        }
        if (c == EntityPotionEffectEvent.Cause.PLUGIN || c == EntityPotionEffectEvent.Cause.COMMAND) return;
        if (e.getNewEffect().getType().equals(PotionEffectType.MINING_FATIGUE) && elderNear(p)) e.setCancelled(true);
    }

    private static boolean elderNear(Player p) {
        for (UUID id : SeaCreatures.LIVE.keySet()) {
            Entity ent = Bukkit.getEntity(id);
            if (ent instanceof org.bukkit.entity.ElderGuardian && ent.getWorld() == p.getWorld()
                    && ent.getLocation().distanceSquared(p.getLocation()) <= CURSE_RADIUS * CURSE_RADIUS) return true;
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        LAST_HIT.remove(e.getPlayer().getUniqueId());
    }
}
