package com.skyclone.mod.soulflow;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The clockwork: who is in combat, the leaving-combat Overflow conversion, the per-5s combat drains (and the stats
 * the two drain accessories grant while paid), and the Soulguard Sprig's invulnerability window.
 */
final class SoulflowRuntime implements Listener {

    private final SoulflowService svc;
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private final Set<UUID> wasInCombat = new HashSet<>();
    /** player -> drain id -> last charge ms. */
    private final Map<UUID, Map<String, Long>> lastCharge = new HashMap<>();
    /** player -> drain id -> paid-until ms. */
    private final Map<UUID, Map<String, Long>> paidUntil = new HashMap<>();
    private final Map<UUID, Long> outMsg = new HashMap<>();
    /** Drain id -> resolved stats. */
    private final Map<String, Map<Stat, Double>> drainStats = new LinkedHashMap<>();
    private final Map<UUID, Long> wardUntil = new HashMap<>();
    private final Map<UUID, Long> weakUntil = new HashMap<>();

    SoulflowRuntime(SoulflowService svc) {
        this.svc = svc;
        for (SoulflowConfig.Drain d : svc.config().drains) {
            Map<Stat, Double> m = new EnumMap<>(Stat.class);
            for (Map.Entry<String, Double> e : d.stats.entrySet()) {
                try { m.put(Stat.valueOf(e.getKey()), e.getValue()); }
                catch (IllegalArgumentException ex) { Sky.log().warning("[soulflow] drain " + d.id + ": unknown stat " + e.getKey()); }
            }
            drainStats.put(d.id, m);
        }
    }

    // ---- combat -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        long now = System.currentTimeMillis();
        Player atk = ctx.playerAttacker();
        Player vic = ctx.playerVictim();
        if (atk != null && vic == null && ctx.victim != null) lastHit.put(atk.getUniqueId(), now);
        if (vic != null && ctx.attacker != null && atk == null) lastHit.put(vic.getUniqueId(), now);
    }

    boolean inCombat(Player p) {
        Long t = lastHit.get(p.getUniqueId());
        return t != null && SoulflowMath.inCombat(t, SoulflowDials.COMBAT_SECONDS.get(), System.currentTimeMillis());
    }

    /** Admin/testing: put a player in combat now. */
    void markCombat(Player p) { lastHit.put(p.getUniqueId(), System.currentTimeMillis()); }

    /** Admin/testing: drop a player out of combat now (the next tick converts). */
    void clearCombat(Player p) { lastHit.remove(p.getUniqueId()); }

    /** Every second from the module's task. */
    void tick() {
        if (!SoulflowDials.on()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            UUID id = p.getUniqueId();
            boolean in = inCombat(p);
            if (in) wasInCombat.add(id);
            else if (wasInCombat.remove(id)) leftCombat(p);
            if (SoulflowDials.DRAINS.get()) drains(p, in, now);
        }
    }

    // ---- Overflow ------------------------------------------------------------------------------------

    double boostPercent(Player p) {
        double sum = 0;
        for (SoulflowConfig.Boost b : svc.config().overflowBoosts) {
            if (b.requirement == null || b.requirement.isBlank()) continue;
            try { if (Sky.requirements().check(p, b.requirement)) sum += b.percent; } catch (RuntimeException ignored) { }
        }
        return sum;
    }

    double overflowTarget(Player p, SoulflowConfig.Tier t) {
        if (t == null) return 0;
        return SoulflowMath.overflowTarget(t.overflow * Math.max(0, SoulflowDials.OVERFLOW_SCALE.get()), boostPercent(p));
    }

    /** The leaving-combat conversion. Returns the conversion made (units 0 = nothing happened). */
    SoulflowMath.Conversion leftCombat(Player p) {
        SoulflowMath.Conversion none = new SoulflowMath.Conversion(0, 0);
        if (!SoulflowDials.OVERFLOW.get() || !svc.toggle(p, "overflow", true)) return none;
        SoulflowConfig.Tier t = svc.tier(p);
        SoulflowData d = svc.data(p);
        if (t == null || d == null) return none;
        SoulflowMath.Conversion c = SoulflowMath.convert(overflowTarget(p, t), t.units, d.overflow, d.pool);
        if (!c.happened()) return c;
        d.pool -= c.units();
        d.spent += c.units();
        d.converted += c.units();
        double gained = c.overflowAfter() - d.overflow;
        d.overflow = c.overflowAfter();
        svc.show(p, "&3+" + Math.round(gained) + "ʬ Overflow &8(-" + SoulflowMath.units(c.units()) + ")");
        return c;
    }

    // ---- drains ------------------------------------------------------------------------------------

    boolean applies(Player p, SoulflowConfig.Drain d, boolean in) {
        if (!in) return false;
        if (d.ownerGated && !SoulflowDials.OWNER_GATED_DRAINS.get()) return false;
        if (d.toggle && !svc.toggle(p, d.id, d.defaultOn)) return false;
        if (d.sneaking && !p.isSneaking()) return false;
        if (d.item != null) {
            try { if (!Sky.service(Features.Accessories.class).has(p, d.item)) return false; }
            catch (RuntimeException e) { return false; }
        }
        if (d.set != null && Sky.items().setPieces(p, d.set) < Math.max(1, d.pieces)) return false;
        return true;
    }

    private void drains(Player p, boolean in, long now) {
        UUID id = p.getUniqueId();
        Map<String, Long> last = lastCharge.computeIfAbsent(id, k -> new HashMap<>());
        Map<String, Long> paid = paidUntil.computeIfAbsent(id, k -> new HashMap<>());
        boolean statsChanged = false;
        for (SoulflowConfig.Drain d : svc.config().drains) {
            boolean wasPaid = paid.getOrDefault(d.id, 0L) > now;
            boolean hasStats = !drainStats.get(d.id).isEmpty();
            if (!applies(p, d, in)) {
                // switched off / stopped sneaking / left combat: the bonus stops now
                if (wasPaid) { paid.remove(d.id); statsChanged |= hasStats; }
                continue;
            }
            long lastMs = last.getOrDefault(d.id, 0L);
            if (!SoulflowMath.due(lastMs, d.every, now)) {
                // still inside a period already paid for (toggling on and off never charges twice)
                if (!wasPaid) { paid.put(d.id, lastMs + d.every * 1000L + 1500L); statsChanged |= hasStats; }
                continue;
            }
            if (svc.consume(p, SoulflowDials.scaled(d.cost), "drain:" + d.id)) {
                last.put(d.id, now);
                paid.put(d.id, now + d.every * 1000L + 1500L);
                if (!wasPaid) statsChanged |= hasStats;
            } else {
                if (wasPaid) { paid.remove(d.id); statsChanged |= hasStats; }
                Long m = outMsg.get(id);
                if (m == null || now - m >= 30_000L) {
                    outMsg.put(id, now);
                    Msg.send(p, "&cYour " + drainName(d) + " &cis out of Soulstream!");
                }
            }
        }
        if (statsChanged) Sky.stats().invalidate(p);
    }

    static String drainName(SoulflowConfig.Drain d) {
        if (d.item != null) {
            var def = Sky.items().def(d.item);
            if (def != null) return def.rarity.code() + com.skyclone.api.ui.Text.strip(def.name);
        }
        return "&6" + (d.label != null ? d.label : d.id);
    }

    boolean paid(Player p, String drainId) {
        Map<String, Long> m = paidUntil.get(p.getUniqueId());
        return m != null && m.getOrDefault(drainId, 0L) > System.currentTimeMillis();
    }

    /** Stat provider: the two drain accessories' bonuses while paid. */
    void stats(Player p, StatMap into) {
        if (!SoulflowDials.on()) return;
        Map<String, Long> m = paidUntil.get(p.getUniqueId());
        if (m == null || m.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (e.getValue() <= now) continue;
            Map<Stat, Double> s = drainStats.get(e.getKey());
            if (s != null) for (Map.Entry<Stat, Double> st : s.entrySet()) into.add(st.getKey(), st.getValue());
        }
    }

    // ---- Soulguard Sprig (Soul Esoward) ------------------------------------------------------------

    boolean soulward(AbilityService.Use u) {
        Player p = u.player();
        long now = System.currentTimeMillis();
        long ward = (long) (Math.max(0, SoulflowDials.SOULWARD_SECONDS.get()) * 1000);
        long weak = (long) (Math.max(0, SoulflowDials.SOULWARD_WEAK_SECONDS.get()) * 1000);
        wardUntil.put(p.getUniqueId(), now + ward);
        weakUntil.put(p.getUniqueId(), now + ward + weak);
        try {
            p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);
        } catch (RuntimeException ignored) { }
        Msg.sound(p, Sound.PARTICLE_SOUL_ESCAPE, 1.0f);
        Msg.send(p, "&3Soulguard! &7Nothing can touch you for &a" + Math.round(ward / 1000.0) + "s&7.");
        return true;
    }

    boolean warded(Player p) {
        Long t = wardUntil.get(p.getUniqueId());
        return t != null && t > System.currentTimeMillis();
    }

    boolean weakened(Player p) {
        Long t = weakUntil.get(p.getUniqueId());
        return t != null && t > System.currentTimeMillis() && !warded(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWardDamage(SkyEvents.Damage e) {
        Player vic = e.ctx.playerVictim();
        Player atk = e.ctx.playerAttacker();
        if (vic != null && warded(vic)) { e.setCancelled(true); return; }
        if (atk != null && warded(atk)) e.setCancelled(true);
    }

    /** Combat modifier: halved outgoing damage in the 2s after the ward. */
    void modify(DamageContext ctx) {
        Player atk = ctx.playerAttacker();
        if (atk == null || !weakened(atk)) return;
        ctx.multiplicative *= Math.max(0, SoulflowDials.SOULWARD_WEAK_MULT.get());
    }

    void forget(Player p) {
        UUID id = p.getUniqueId();
        lastHit.remove(id);
        wasInCombat.remove(id);
        lastCharge.remove(id);
        paidUntil.remove(id);
        outMsg.remove(id);
        wardUntil.remove(id);
        weakUntil.remove(id);
    }
}
