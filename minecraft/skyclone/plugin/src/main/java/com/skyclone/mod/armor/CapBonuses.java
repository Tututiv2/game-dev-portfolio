package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.stat.Stat;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static com.skyclone.mod.armor.SetIds.*;

/**
 * Wave 5: the two set bonuses that RAISE A CORE CAP for the wearer only, done in this module without
 * touching core (hand-off request 12 had asked core for per-player caps).
 *
 * <ul>
 *   <li><b>Young Blood</b> "+100 Walk Speed Cap": core sets the movement attribute's BASE from
 *       min(Speed, {@code stats.speed-cap}); this adds a transient ADD_SCALAR modifier worth the speed above
 *       that cap the set lets through ({@link SetMath#speedCapScalar}). Refreshed every 5 ticks; removed the
 *       moment the set comes off, on quit and on disable.</li>
 *   <li><b>Newton's Demise</b> (Thermodynamic) "+50 Attack Speed Cap": core rate-limits melee hits per
 *       attacker per mob with {@code base / (1 + min(AS, cap)/100)} ticks and cancels a click that comes too
 *       soon. For a wearer whose Attack Speed is above core's cap, the click core refused lands anyway once
 *       the set's shorter window has passed ({@link SetMath#newtonMayLand}) through
 *       {@code Sky.combat().playerAttack} - the same call core makes - and clicks inside the shorter window
 *       are cancelled before core sees them, so a wearer never hits faster than the raised cap allows.</li>
 * </ul>
 */
final class CapBonuses implements Listener {

    static final NamespacedKey SPEED_KEY = new NamespacedKey("skyclone", "armor_speed_cap");

    // ================================================================================ Young Blood

    static void startSpeedTask() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) speedCap(p);
        }, 40L, 5L);
    }

    static double speedCapScalarFor(Player p) {
        if (!ArmorDials.ENABLED.get() || !Sky.profiles().isReady(p)) return 0;
        Worn w = Worn.of(p);
        if (!w.full(YOUNG)) return 0;
        return SetMath.speedCapScalar(Sky.stats().get(p, Stat.SPEED), ArmorDials.CORE_SPEED_CAP.get(), ArmorDials.YOUNG_SPEED_CAP.get());
    }

    static void speedCap(Player p) {
        double want = 0;
        try { want = speedCapScalarFor(p); } catch (RuntimeException ignored) { }
        setSpeedModifier(p, want);
    }

    static void setSpeedModifier(Player p, double amount) {
        AttributeInstance a = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (a == null) return;
        AttributeModifier cur = a.getModifier(SPEED_KEY);
        if (cur != null && Math.abs(cur.getAmount() - amount) < 1e-6) return;
        if (cur != null) a.removeModifier(SPEED_KEY);
        if (amount > 1e-6) a.addTransientModifier(new AttributeModifier(SPEED_KEY, amount, AttributeModifier.Operation.ADD_SCALAR));
    }

    /** The modifier currently on the player (0 when none), for /sb armor info. */
    static double speedModifier(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MOVEMENT_SPEED);
        AttributeModifier m = a == null ? null : a.getModifier(SPEED_KEY);
        return m == null ? 0 : m.getAmount();
    }

    // ================================================================================ Newton's Demise

    /** Per attacker, per mob: core's last landed melee tick, our last landed tick, the event we armed. */
    private static final class Track {
        long coreHit = -1, ourHit = -1, landedTick = -1;
        EntityDamageByEntityEvent armed;
    }

    private static final Map<UUID, Map<UUID, Track>> TRACKS = new HashMap<>();
    private static boolean injecting;

    /** {ourWindow, coreWindow} when the wearer's Attack Speed is above core's cap and the set shortens the window; else null. */
    static int[] windows(Player p) {
        if (!ArmorDials.ENABLED.get() || !Worn.of(p).full(THERMODYNAMIC)) return null;
        double as = Sky.stats().get(p, Stat.ATTACK_SPEED);
        double cap = ArmorDials.CORE_AS_CAP.get();
        if (as <= cap) return null;
        int base = ArmorDials.CORE_HIT_TICKS.get(), min = ArmorDials.CORE_HIT_MIN.get();
        int core = SetMath.hitWindow(base, as, cap, min);
        int ours = SetMath.hitWindow(base, as, cap + ArmorDials.NEWTON_AS_CAP.get(), min);
        return ours < core ? new int[]{ours, core} : null;
    }

    private static Track track(Player p, LivingEntity v, boolean create) {
        Map<UUID, Track> m = TRACKS.get(p.getUniqueId());
        if (m == null) {
            if (!create) return null;
            TRACKS.put(p.getUniqueId(), m = new HashMap<>());
        }
        Track t = m.get(v.getUniqueId());
        if (t == null && create) {
            if (m.size() > 32) prune(m);
            m.put(v.getUniqueId(), t = new Track());
        }
        return t;
    }

    private static void prune(Map<UUID, Track> m) {
        long now = Bukkit.getCurrentTick();
        for (Iterator<Track> it = m.values().iterator(); it.hasNext(); ) {
            Track t = it.next();
            if (Math.max(t.coreHit, t.ourHit) < now - 200) it.remove();
        }
    }

    private static Player meleeAttacker(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return null;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity v) || v instanceof Player) return null;
        return Sky.mobs().isSkyMob(v) ? p : null;
    }

    /** Before core (HIGH): a click inside the set's own window is refused here, so core never sees it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClickEarly(EntityDamageByEntityEvent e) {
        Player p = meleeAttacker(e);
        if (p == null) return;
        int[] w = windows(p);
        if (w == null) return;
        Track t = track(p, (LivingEntity) e.getEntity(), true);
        long now = Bukkit.getCurrentTick();
        if (t.ourHit >= 0 && now - t.ourHit < w[0]) { e.setCancelled(true); return; }
        t.armed = e;
    }

    /** Core's melee pipeline fires this inside its handler: that is how we know core let the click land. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLanded(SkyEvents.Damage e) {
        if (injecting) return;
        DamageContext ctx = e.ctx;
        if (ctx.kind != DamageContext.Kind.MELEE) return;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim == null || ctx.victim instanceof Player) return;
        Track t = track(p, ctx.victim, false);
        if (t != null) t.landedTick = Bukkit.getCurrentTick();
    }

    /** After everyone: core landed it (record), or core's own window refused it (land it ourselves). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickLate(EntityDamageByEntityEvent e) {
        Player p = meleeAttacker(e);
        if (p == null) return;
        LivingEntity v = (LivingEntity) e.getEntity();
        Track t = track(p, v, false);
        if (t == null || t.armed != e) return;
        t.armed = null;
        long now = Bukkit.getCurrentTick();
        // core let it through (its pipeline fired, or it left the event standing with damage 0)
        if (t.landedTick == now || !e.isCancelled()) { t.coreHit = now; t.ourHit = now; return; }
        int[] w = windows(p);
        if (w == null) return;
        long sinceCore = t.coreHit < 0 ? -1 : now - t.coreHit;
        long sinceOurs = t.ourHit < 0 ? Long.MAX_VALUE : now - t.ourHit;
        if (!SetMath.newtonMayLand(sinceCore, sinceOurs, w[1], w[0])) return;
        if (!v.isValid() || v.isDead()) return;
        UUID owner = Sky.mobs().owner(v);
        if (owner != null && !owner.equals(p.getUniqueId())) return;
        t.ourHit = now;
        injecting = true;
        try {
            Sky.combat().playerAttack(p, v, p.getInventory().getItemInMainHand(), DamageContext.Kind.MELEE);
        } finally {
            injecting = false;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        TRACKS.remove(e.getPlayer().getUniqueId());
        setSpeedModifier(e.getPlayer(), 0);
    }

    static void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) setSpeedModifier(p, 0);
        TRACKS.clear();
    }

    /** For /sb armor info. */
    static String describe(Player p) {
        int[] w = windows(p);
        return "speed-cap modifier +" + Math.round(speedModifier(p) * 1000) / 10.0 + "%"
                + (w == null ? ", Newton's Demise idle" : ", hit window " + w[0] + " ticks (core " + w[1] + ")");
    }
}
