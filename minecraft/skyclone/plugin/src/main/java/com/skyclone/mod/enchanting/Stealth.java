package com.skyclone.mod.enchanting;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <b>Stealth</b> (boots, I-VI: 10..60%, research 01 "Hunting: timid creatures"): a TIMID creature has that chance to
 * hold still instead of fleeing.
 * <ul>
 *   <li>Timid = a sky mob whose MobDef carries flag {@code timid}/{@code skittish}/{@code flees}, {@code extra.timid},
 *       or is a non-aggressive hunting critter ({@code extra.critter}); an entity with scoreboard tag {@code timid};
 *       or a vanilla creature that runs from players on sight (rabbit, fox, ocelot, armadillo).</li>
 *   <li>Coming within {@code stealth.radius} blocks rolls ONCE per creature per {@code stealth.reroll-seconds}; a hit
 *       on a passive animal (which would make it panic and bolt) rolls too.</li>
 *   <li>Holding still = {@code Mob#setAware(false)} for {@code stealth.hold-seconds} (gravity and knockback still
 *       apply, its own AI does not), plus scoreboard tag {@code skyclone_calm} so a module that moves its creatures
 *       itself (hunting) can skip its flee step while the tag is on. Every calm is undone on expiry, on module
 *       disable and, should the server die mid-calm, when the chunk loads again.</li>
 * </ul>
 */
final class Stealth implements Listener {

    static final String CALM_TAG = "skyclone_calm";
    static final String TIMID_TAG = "timid";

    /** Entity uuid -> calm until (ms). */
    private static final Map<UUID, Long> CALM = new HashMap<>();
    /** Entity uuid -> no new roll before (ms). */
    private static final Map<UUID, Long> ROLLED = new HashMap<>();
    private static int task = -1;
    private static long ticks;

    void register() {
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), Stealth::tick, 10L, 10L);
    }

    static void shutdown() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        for (UUID u : new ArrayList<>(CALM.keySet())) release(Bukkit.getEntity(u));
        CALM.clear();
        ROLLED.clear();
        HIT_ROLL.clear();
    }

    private static boolean on() { return EnchantingModule.dials().effects.get(); }

    /** A creature Stealth applies to. */
    static boolean timid(LivingEntity e) {
        if (e == null || e instanceof Player || !(e instanceof Mob)) return false;
        if (e.getScoreboardTags().contains(TIMID_TAG)) return true;
        MobDef md = Sky.mobs().defOf(e);
        if (md != null) return Wave5Math.timidDef(md.flags, flag(md, "timid"), flag(md, "critter"));
        if (e instanceof Tameable t && t.isTamed()) return false;
        return Wave5Math.vanillaAvoider(e.getType().name());
    }

    private static boolean flag(MobDef md, String key) {
        if (md.extra == null || !md.extra.has(key)) return false;
        JsonElement j = md.extra.get(key);
        try { return j.isJsonPrimitive() && j.getAsBoolean(); } catch (RuntimeException ex) { return false; }
    }

    static boolean calm(UUID entity) {
        Long t = CALM.get(entity);
        return t != null && t > System.currentTimeMillis();
    }

    /** Roll Stealth for {@code p} against {@code e}; true when it now holds still. */
    static boolean roll(Player p, LivingEntity e, boolean force) {
        int lvl = Gear.wornMax(p, "stealth");
        EnchantSpec s = lvl <= 0 ? null : EnchantingModule.reg().spec("stealth");
        if (s == null || !(e instanceof Mob)) return false;
        long now = System.currentTimeMillis();
        UUID u = e.getUniqueId();
        if (calm(u)) return true;
        if (!force) {
            Long next = ROLLED.get(u);
            if (next != null && next > now) return false;
        }
        if (ROLLED.size() > 4096) ROLLED.values().removeIf(t -> t < now);
        ROLLED.put(u, now + Math.max(1, EnchantingModule.dials().stealthRerollSeconds.get()) * 1000L);
        if (!Wave5Math.holdsStill(s.v(lvl), ThreadLocalRandom.current().nextDouble())) return false;
        hold(e, now + Math.max(1, EnchantingModule.dials().stealthHoldSeconds.get()) * 1000L);
        return true;
    }

    private static void hold(LivingEntity e, long until) {
        if (!(e instanceof Mob m)) return;
        if (CALM.size() > 2048) return;   // a runaway: never hold more than this at once
        boolean wasCalm = CALM.containsKey(e.getUniqueId());
        if (!wasCalm && !m.isAware()) return;   // some other system already froze it: leave it alone
        m.setAware(false);
        try { m.getPathfinder().stopPathfinding(); } catch (RuntimeException ignored) { }
        Vector v = m.getVelocity();
        m.setVelocity(new Vector(0, Math.min(0, v.getY()), 0));
        m.addScoreboardTag(CALM_TAG);
        CALM.put(e.getUniqueId(), until);
        e.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, e.getLocation().add(0, e.getHeight() + 0.3, 0), 3, 0.2, 0.1, 0.2, 0);
    }

    private static void release(Entity e) {
        if (e instanceof Mob m && m.getScoreboardTags().contains(CALM_TAG)) {
            m.setAware(true);
            m.removeScoreboardTag(CALM_TAG);
        }
    }

    // ---- triggers ------------------------------------------------------------------------------------------

    /** Approach: every half second, players in Stealth boots roll against timid creatures around them. */
    private static void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Long>> it = CALM.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> en = it.next();
            if (en.getValue() > now) continue;
            it.remove();
            release(Bukkit.getEntity(en.getKey()));
        }
        if (!on()) return;
        double r = Math.max(1, Math.min(24, EnchantingModule.dials().stealthRadius.get()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EnchantingModule.ready(p) || Gear.wornMax(p, "stealth") <= 0) continue;
            for (Entity x : p.getNearbyEntities(r, Math.min(8, r), r)) {
                if (x instanceof LivingEntity le && timid(le)) roll(p, le, false);
            }
        }
        if (ticks % 120 == 0 && ROLLED.size() > 256) ROLLED.values().removeIf(t -> t < now);
    }

    /**
     * Hit: a struck passive animal (or timid creature) would bolt; Stealth may keep it in place. Heard from both the
     * vanilla event (core may cancel it and route the hit itself) and core's SkyEvents.Damage; one roll per hit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity v) || v instanceof Player) return;
        Player p = null;
        if (e.getDamager() instanceof Player pp) p = pp;
        else if (e.getDamager() instanceof org.bukkit.entity.Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Player pp) p = pp;
        }
        hit(p, v);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkyHit(com.skyclone.api.event.SkyEvents.Damage e) {
        if (e.ctx.victim != null && !(e.ctx.victim instanceof Player)) hit(e.ctx.playerAttacker(), e.ctx.victim);
    }

    /** entity uuid -> last hit roll (ms): the two hit paths of one blow roll once. */
    private static final Map<UUID, Long> HIT_ROLL = new HashMap<>();

    private static void hit(Player p, LivingEntity v) {
        if (p == null || !on() || Gear.wornMax(p, "stealth") <= 0) return;
        boolean panicker = v instanceof Animals && !(v instanceof Tameable t && t.isTamed()) && Sky.mobs().defOf(v) == null;
        if (!panicker && !timid(v)) return;
        long now = System.currentTimeMillis();
        Long last = HIT_ROLL.get(v.getUniqueId());
        if (last != null && now - last < 250) return;
        if (HIT_ROLL.size() > 2048) HIT_ROLL.clear();
        HIT_ROLL.put(v.getUniqueId(), now);
        roll(p, v, true);
    }

    /** Crash recovery: a creature saved while calm comes back aware. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) {
            if (x.getScoreboardTags().contains(CALM_TAG) && !CALM.containsKey(x.getUniqueId())) release(x);
        }
    }
}
