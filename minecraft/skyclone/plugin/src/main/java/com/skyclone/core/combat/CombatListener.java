package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext.Kind;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla interception in game worlds (name starts with "sb_" or the world service knows it):
 * <ul>
 *   <li>player -&gt; sky mob: vanilla damage set to 0 (knockback + hurt flash stay), routed through playerAttack</li>
 *   <li>player arrow / trident -&gt; sky mob: same, PROJECTILE, using the bow captured at shot time</li>
 *   <li>sky mob -&gt; player: vanilla zeroed (or cancelled), routed through damagePlayer(def.damage)</li>
 *   <li>environment -&gt; player: a % of max health per cause (dials); void = instant death</li>
 *   <li>anything else touching a sky mob: cancelled (its vanilla health is a gauge that must never move)</li>
 * </ul>
 */
final class CombatListener implements Listener {

    private record Shot(ItemStack bow, double force, long at) {}

    private final CombatServiceImpl combat;
    private final MobServiceImpl mobs;
    private final Map<UUID, Shot> shots = new HashMap<>();
    private final Map<UUID, Map<String, Long>> envCooldown = new HashMap<>();

    /** Player uuid -> epoch millis until which mobs cannot hurt them (set on SkyDeath). */
    static final Map<UUID, Long> GRACE = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkyDeath(com.skyclone.api.event.SkyEvents.SkyDeath e) {
        double s = CombatDials.RESPAWN_GRACE.get();
        if (s > 0) GRACE.put(e.player.getUniqueId(), System.currentTimeMillis() + (long) (s * 1000));
    }

    /** SAFE zone (dial) or respawn grace: no sky mob may target or hurt this player. */
    static boolean protectedFromMobs(Player p) {
        Long until = GRACE.get(p.getUniqueId());
        if (until != null) {
            if (until > System.currentTimeMillis()) return true;
            GRACE.remove(p.getUniqueId());
        }
        if (!CombatDials.SAFE_ZONE_PROTECTS.get() || !Sky.has(WorldService.class)) return false;
        try {
            Layout.Zone z = Sky.worlds().zoneAt(p.getLocation());
            return z != null && z.flags() != null && z.flags().contains(Layout.Zone.SAFE);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    CombatListener(CombatServiceImpl combat, MobServiceImpl mobs) {
        this.combat = combat;
        this.mobs = mobs;
    }

    static boolean isGameWorld(World w) {
        if (w == null) return false;
        if (w.getName().startsWith("sb_")) return true;
        if (!Sky.has(WorldService.class)) return false;
        try { return Sky.worlds().islandOf(w) != null; } catch (RuntimeException e) { return false; }
    }

    // ---- bows ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getProjectile() instanceof AbstractArrow arrow)) return;
        ItemStack bow = e.getBow();
        long now = System.currentTimeMillis();
        if (shots.size() > 512) shots.values().removeIf(s -> now - s.at > 60_000);
        shots.put(arrow.getUniqueId(), new Shot(bow == null ? null : bow.clone(), e.getForce(), now));
    }

    // ---- damage ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (!isGameWorld(victim.getWorld())) return;
        LiveMob lm = mobs.get(victim);
        if (lm == null && !(victim instanceof Player)
                && victim.getPersistentDataContainer().has(CombatKeys.MOB, PersistentDataType.STRING)) {
            e.setCancelled(true);   // a dying or ghost sky mob: never let vanilla touch it
            return;
        }
        if (e instanceof EntityDamageByEntityEvent ee) {
            byEntity(ee, victim, lm);
            return;
        }
        if (lm != null) {
            e.setCancelled(true);
            DamageCause c = e.getCause();
            if (c == DamageCause.VOID || c == DamageCause.KILL) mobs.remove(victim);
            return;
        }
        if (victim instanceof Player p) environment(e, p);
    }

    private void byEntity(EntityDamageByEntityEvent e, LivingEntity victim, LiveMob lm) {
        Entity damager = e.getDamager();
        LivingEntity source = CombatServiceImpl.resolveAttacker(damager);
        Player attacker = source instanceof Player p ? p : null;

        if (lm != null) {
            if (attacker == null) { e.setCancelled(true); return; }          // mob vs mob, tnt, etc.
            if (e.getCause() == DamageCause.ENTITY_SWEEP_ATTACK && CombatDials.CANCEL_SWEEP.get()) { e.setCancelled(true); return; }
            if (lm.dead || !mobs.canDamage(attacker, lm, true)) { e.setCancelled(true); return; }
            ItemStack weapon;
            Kind kind;
            if (damager instanceof Projectile proj) {
                weapon = projectileWeapon(proj, attacker);
                if (weapon == null) { e.setCancelled(true); return; }      // snowballs, eggs, hooks: no damage
                kind = Kind.PROJECTILE;
            } else {
                // Per-attacker hit-rate limit (vanilla invulnerability is off on sky mobs).
                if (!lm.tryHit(attacker.getUniqueId(), org.bukkit.Bukkit.getCurrentTick(), invulnTicks(attacker))) {
                    e.setCancelled(true);
                    return;
                }
                weapon = attacker.getInventory().getItemInMainHand();
                kind = Kind.MELEE;
            }
            e.setDamage(0);
            victim.setMaximumNoDamageTicks(0);
            route(() -> combat.playerAttack(attacker, victim, weapon, kind));
            return;
        }

        if (victim instanceof Player pv) {
            LiveMob src = source == null ? null : mobs.get(source);
            if (src != null) {
                if (src.dead) { e.setCancelled(true); return; }
                if (protectedFromMobs(pv)) { e.setCancelled(true); return; }
                boolean keep = CombatDials.MOB_KNOCKBACK.get();
                if (keep) e.setDamage(0); else e.setCancelled(true);
                double base = src.def.damage;
                if (keep) route(() -> combat.damagePlayer(pv, base, damager, Kind.MOB));
                else combat.damagePlayer(pv, base, damager, Kind.MOB);
                return;
            }
            if (attacker != null) {
                if (attacker == pv || !combat.pvpAllowed(attacker, pv)) { e.setCancelled(true); return; }
                ItemStack weapon;
                Kind kind;
                if (damager instanceof Projectile proj) {
                    weapon = projectileWeapon(proj, attacker);
                    if (weapon == null) { e.setCancelled(true); return; }
                    kind = Kind.PROJECTILE;
                } else {
                    weapon = attacker.getInventory().getItemInMainHand();
                    kind = Kind.MELEE;
                }
                e.setDamage(0);
                route(() -> combat.playerAttack(attacker, pv, weapon, kind));
                return;
            }
            // Anything else (vanilla creature, explosion entity, firework): environment rules.
            environment(e, pv);
        }
        // Victim is neither a sky mob nor a player (NPC, armour stand, pet): not ours.
    }

    private void route(Runnable r) {
        combat.inVanillaEvent = true;
        try { r.run(); } finally { combat.inVanillaEvent = false; }
    }

    /** The bow captured when the arrow was shot (scaled by draw), a thrown trident's own stack. */
    private ItemStack projectileWeapon(Projectile proj, Player shooter) {
        if (proj instanceof Trident t) return t.getItemStack();
        if (proj instanceof AbstractArrow) {
            Shot s = shots.remove(proj.getUniqueId());
            if (s != null) {
                if (CombatDials.BOW_FORCE_SCALING.get()) combat.pendingMultiplier = Math.max(0.1, Math.min(1.0, s.force));
                return s.bow == null ? new ItemStack(Material.BOW) : s.bow;
            }
            ItemStack hand = shooter.getInventory().getItemInMainHand();
            if (hand.getType() == Material.BOW || hand.getType() == Material.CROSSBOW) return hand;
            return new ItemStack(Material.ARROW);   // dispenser-less fallback: base 5 damage
        }
        return null;
    }

    /** Hit-rate window: combat.mob-invuln-ticks divided by the attacker's Bonus Attack Speed. */
    private static int invulnTicks(Player attacker) {
        int base = Math.max(1, CombatDials.MOB_INVULN_TICKS.get());
        double as = 0;
        try { as = Sky.stats().get(attacker, Stat.ATTACK_SPEED); } catch (RuntimeException ignored) { }
        return DamageMath.invulnTicks(base, as, CombatDials.ATTACK_SPEED_CAP.get(), Math.max(1, CombatDials.MOB_INVULN_MIN.get()));
    }

    // ---- environment ----------------------------------------------------------------------------

    private void environment(EntityDamageEvent e, Player p) {
        if (!CombatServiceImpl.damageable(p)) return;
        DamageCause cause = e.getCause();
        switch (cause) {
            case VOID -> {
                e.setCancelled(true);
                combat.killOnce(p, "void");
                return;
            }
            case KILL, SUICIDE -> {
                e.setCancelled(true);
                combat.killOnce(p, "kill");
                return;
            }
            default -> { }
        }
        double vanilla = Math.max(0, e.getDamage());
        String group;
        double pct;
        switch (cause) {
            case FALL -> {
                if (zoneFlag(p, Layout.Zone.NO_FALL)) { e.setCancelled(true); return; }
                group = "fall";
                pct = CombatDials.FALL_PCT_PER_BLOCK.get() * vanilla;   // vanilla fall damage = blocks beyond 3 (after feather falling etc.)
            }
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE, MELTING -> {
                group = cause == DamageCause.LAVA ? "lava" : "fire";
                pct = CombatDials.FIRE_PCT.get() * vanilla;
            }
            case DROWNING, DRYOUT -> { group = "drowning"; pct = CombatDials.DROWN_PCT.get() * vanilla; }
            case SUFFOCATION, CRAMMING, FLY_INTO_WALL -> { group = "suffocation"; pct = CombatDials.SUFFOCATE_PCT.get() * vanilla; }
            default -> { group = cause.name().toLowerCase(); pct = CombatDials.OTHER_PCT.get() * vanilla; }
        }
        if (pct <= 0) { e.setCancelled(true); return; }
        long tick = org.bukkit.Bukkit.getCurrentTick();
        Map<String, Long> cd = envCooldown.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        Long last = cd.get(group);
        if (last != null && tick - last < Math.max(0, CombatDials.ENV_COOLDOWN.get()) && !group.equals("fall")) {
            e.setCancelled(true);
            return;
        }
        cd.put(group, tick);
        e.setDamage(0);   // keep vanilla's hurt flash/sound, never vanilla health
        double max;
        try { max = Sky.vitals().maxHealth(p); } catch (RuntimeException ex) { return; }
        double amount = DamageMath.percentOfMax(max, pct);
        route(() -> combat.environment(p, amount, group));
    }

    private static boolean zoneFlag(Player p, String flag) {
        if (!Sky.has(WorldService.class)) return false;
        try {
            Layout.Zone z = Sky.worlds().zoneAt(p.getLocation());
            return z != null && z.flags() != null && z.flags().contains(flag);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---- housekeeping -------------------------------------------------------------------------
    // (The vanilla ATTACK_SPEED attribute — no 1.9 attack-charge bar — is owned by the stats
    //  service in core.items, dial stats.attack-speed-attribute.)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        envCooldown.remove(e.getPlayer().getUniqueId());
        combat.debug.remove(e.getPlayer().getUniqueId());
        combat.forget(e.getPlayer().getUniqueId());
        mobs.forget(e.getPlayer().getUniqueId());
        if (CombatCore.loot != null) CombatCore.loot.forget(e.getPlayer().getUniqueId());
        ItemStash.forget(e.getPlayer().getUniqueId());
    }

    void purge() {
        long now = System.currentTimeMillis();
        for (Iterator<Shot> it = shots.values().iterator(); it.hasNext(); ) if (now - it.next().at > 60_000) it.remove();
        envCooldown.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
    }
}
