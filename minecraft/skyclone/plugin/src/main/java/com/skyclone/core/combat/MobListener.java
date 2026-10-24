package com.skyclone.core.combat;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/** Lifecycle of sky mobs: no vanilla drops, no burning, no conversions, no griefing, no ghosts. */
final class MobListener implements Listener {

    private final MobServiceImpl mobs;

    MobListener(MobServiceImpl mobs) { this.mobs = mobs; }

    private static boolean tagged(Entity e) {
        return e.getPersistentDataContainer().has(CombatKeys.MOB, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        if (le instanceof Player || !tagged(le)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        LiveMob lm = mobs.get(le);
        if (lm != null && !lm.dead) {
            // Killed by something outside the pipeline (/kill, another plugin): no loot.
            lm.dead = true;
            mobs.untrack(lm, false);
        }
    }

    /**
     * Potions thrown by sky mobs (witches): no vanilla effect lands on anyone. Vanilla Instant Damage
     * and Poison ticks reach players as plain vanilla damage, which the vitals safety net converts
     * proportionally (1 of 20 vanilla hearts = 5% of max health) ignoring Defense, and Poison keeps
     * ticking down to the vanilla floor. A harmful splash instead hits once for the mob's damage
     * through the normal pipeline. (Playtest "loops": a Lv15 Sea Witch took a 693 HP / 706 Defense
     * player to 2 HP, and killed one that kept fishing.)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobPotion(org.bukkit.event.entity.PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof LivingEntity shooter) || !tagged(shooter)) return;
        boolean harmful = false;
        for (org.bukkit.potion.PotionEffect pe : e.getPotion().getEffects()) {
            org.bukkit.potion.PotionEffectType t = pe.getType();
            if (t.equals(org.bukkit.potion.PotionEffectType.POISON) || t.equals(org.bukkit.potion.PotionEffectType.INSTANT_DAMAGE)
                    || t.equals(org.bukkit.potion.PotionEffectType.SLOWNESS) || t.equals(org.bukkit.potion.PotionEffectType.WEAKNESS)
                    || t.equals(org.bukkit.potion.PotionEffectType.WITHER)) { harmful = true; break; }
        }
        LiveMob lm = mobs.get(shooter);
        for (LivingEntity le : e.getAffectedEntities()) {
            double intensity = e.getIntensity(le);
            e.setIntensity(le, 0);
            if (!harmful || !(le instanceof Player p) || lm == null || lm.dead || intensity <= 0) continue;
            if (CombatListener.protectedFromMobs(p)) continue;
            com.skyclone.api.Sky.combat().damagePlayer(p, lm.def.damage * Math.min(1.0, intensity), e.getPotion(),
                    com.skyclone.api.combat.DamageContext.Kind.MOB);
        }
    }

    /** Player uuid -> server tick of the last melee/projectile hit by a sky mob. */
    private final java.util.Map<java.util.UUID, Integer> lastMobHit = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMobHitPlayer(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager();
        if (d instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Entity s) d = s;
        if (tagged(d)) lastMobHit.put(p.getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
    }

    /**
     * On-hit vanilla effects from sky mobs (cave spider Poison, wither skeleton Wither, husk Hunger,
     * stray Slowness arrows): cancelled. Poison/Wither ticks reach players as vanilla damage that the
     * vitals safety net converts at 5% of MAX health per point, ignoring Defense. (Playtest "endgame":
     * two F1 Cellar Spiders poisoned a 944 HP / 678 Defense player for ~45 HP a tick, and the run
     * failed to a Lv20 spider pack in under a minute.) The hit itself still lands through the pipeline.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobHitEffect(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null) return;
        org.bukkit.event.entity.EntityPotionEffectEvent.Cause c = e.getCause();
        if (c != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK
                && c != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ARROW) return;
        Integer at = lastMobHit.get(p.getUniqueId());
        if (at != null && at == org.bukkit.Bukkit.getCurrentTick()) e.setCancelled(true);
    }

    @EventHandler
    public void onQuitClearHit(org.bukkit.event.player.PlayerQuitEvent e) {
        lastMobHit.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent e) {
        LiveMob lm = mobs.get(e.getEntity());
        if (lm != null && !lm.dead) {
            lm.dead = true;
            mobs.untrack(lm, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        LiveMob lm = mobs.get(e.getEntity());
        if (lm != null && !lm.def.has("burns")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (tagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent e) {
        if (tagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (tagged(e.getEntity())) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (tagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(EntityDropItemEvent e) {
        if (tagged(e.getEntity())) e.setCancelled(true);
    }

    /** Sky mobs only ever target players; passive ones never; owned ones only their owner. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        LiveMob lm = mobs.get(e.getEntity());
        if (lm == null) return;
        LivingEntity t = e.getTarget();
        if (t == null) return;
        if (!(t instanceof Player p) || lm.def.has("passive")) { e.setCancelled(true); return; }
        if (CombatListener.protectedFromMobs(p)) { e.setCancelled(true); return; }
        if (!lm.allows(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        int n = mobs.cleanGhosts(e.getEntities());
        if (n > 0) com.skyclone.api.Sky.log().fine("[mobs] removed " + n + " ghost entities in a loaded chunk");
    }
}
