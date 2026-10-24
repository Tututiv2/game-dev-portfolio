package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Rend</b> (bow ultimate, book text): "Use Left Click ability to rip your arrows out of nearby enemies. Each arrow
 * deals 5..25% of your last critical shot ON THE TARGET, up to 5 arrows. 2s cooldown."
 * Wave 5 replaces the wave-1 simplification (5 nearest mobs, your last crit on anything): every arrow of yours that
 * lands is counted on its target together with your last critical shot on THAT target; a left click with the bow
 * rips them out of every such enemy within {@code rend-radius} blocks, then forgets them.
 */
final class Rend implements Listener {

    /** Arrows of one player stuck in one target, and the player's last crit on it. */
    private static final class Stuck { int arrows; double lastCrit; long until; }

    /** target uuid -> shooter uuid -> stuck arrows. */
    private static final Map<UUID, Map<UUID, Stuck>> STUCK = new HashMap<>();
    static final int MAX_ARROWS = 5;
    private static final long KEEP_MS = 60_000;

    static void shutdown() { STUCK.clear(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        if (ctx.kind != DamageContext.Kind.PROJECTILE || ctx.victim == null || ctx.victim instanceof Player) return;
        Player p = ctx.playerAttacker();
        if (p == null || !EnchantingModule.dials().effects.get()) return;
        if (Gear.level(Gear.held(p), "ultimate_rend") <= 0 && Gear.level(ctx.weapon, "ultimate_rend") <= 0) return;
        if (STUCK.size() > 2000) prune();
        Stuck s = STUCK.computeIfAbsent(ctx.victim.getUniqueId(), k -> new HashMap<>()).computeIfAbsent(p.getUniqueId(), k -> new Stuck());
        s.arrows++;
        if (ctx.crit) s.lastCrit = Math.max(0, ctx.finalDamage);
        s.until = System.currentTimeMillis() + KEEP_MS;
    }

    /** Left click with a Rend bow (cooldown checked by the caller). Returns how many enemies were rent. */
    static int use(Player p, double pct) {
        double r = Math.max(2, Math.min(32, EnchantingModule.dials().rendRadius.get()));
        long now = System.currentTimeMillis();
        int n = 0;
        for (Entity x : p.getNearbyEntities(r, r, r)) {
            if (!(x instanceof LivingEntity le) || x instanceof Player || !le.isValid()) continue;
            Map<UUID, Stuck> by = STUCK.get(le.getUniqueId());
            Stuck s = by == null ? null : by.get(p.getUniqueId());
            if (s == null || s.until < now) continue;
            double dmg = Wave5Math.rendDamage(s.lastCrit, pct, s.arrows, MAX_ARROWS);
            by.remove(p.getUniqueId());
            if (dmg <= 0) continue;
            CombatEnchants.proc(le, dmg, p);
            le.getWorld().spawnParticle(Particle.CRIT, le.getLocation().add(0, le.getHeight() / 2, 0), 8, 0.3, 0.3, 0.3, 0.1);
            n++;
        }
        if (n > 0) Msg.sound(p, Sound.ENTITY_ARROW_HIT_PLAYER, 0.6f);
        return n;
    }

    /** Arrows a player has in a target right now (admin/tests). */
    static int stuck(UUID target, UUID shooter) {
        Map<UUID, Stuck> by = STUCK.get(target);
        Stuck s = by == null ? null : by.get(shooter);
        return s == null || s.until < System.currentTimeMillis() ? 0 : s.arrows;
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Map<UUID, Stuck>>> it = STUCK.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<UUID, Stuck>> en = it.next();
            en.getValue().values().removeIf(s -> s.until < now);
            Entity t = Bukkit.getEntity(en.getKey());
            if (en.getValue().isEmpty() || t == null || !t.isValid()) it.remove();
        }
    }
}
