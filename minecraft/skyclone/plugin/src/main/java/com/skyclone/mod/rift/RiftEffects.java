package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Wave 5: the Rift effects of other modules' items, applied where the Rift owns the rule (accessories hand-off
 * "hooked effects nobody applies yet", rift part; research 06 lore):
 * <ul>
 *   <li>Rift monsters' melee hits take Rift Time (research 01 sec 2.3: "damage taken removes time"), and the
 *       belts (Silkrider Safety Belt, Leech Belt, Vermin Belt, Golden Belt) take "1 less".</li>
 *   <li>Perma-Jelled Garlic-Flavored ... Gummy Polar Bear: +3 Combat XP from Rift kills.</li>
 *   <li>Punchcard Artifact: punch another player in the Rift and you both regain 25 s (once per player per
 *       visit, up to 20 players).</li>
 * </ul>
 * Golden Belt (+5% Rift Time, 3% faster drain), Satelite (20% slower in elevated areas) and the Respiration line
 * (Rift Time holds still for the first 10/20/30 s of a visit) live in RiftStats / RiftTravel.drainHere.
 */
final class RiftEffects implements Listener {

    static final String[] BELTS = {"SILKRIDER_SAFETY_BELT", "LEECH_BELT", "VERMIN_BELT", "GOLDEN_BELT"};

    private final RiftModule m;

    RiftEffects(RiftModule m) { this.m = m; }

    /** In the accessory bag (or inventory), or worn in an equipment slot. */
    static boolean has(Player p, String id) {
        try { if (Sky.service(Features.Accessories.class).has(p, id)) return true; } catch (RuntimeException ignored) { }
        return RiftCat.wears(p, id);
    }

    static boolean belt(Player p) {
        for (String b : BELTS) if (has(p, b)) return true;
        return false;
    }

    /** Respiration Talisman / Ring / Artifact: seconds the Rift Time holds still on entry (10/20/30, best one). */
    static int respiration(Player p) {
        if (has(p, "RESPIRATION_ARTIFACT")) return 30;
        if (has(p, "RESPIRATION_RING")) return 20;
        if (has(p, "RESPIRATION_TALISMAN")) return 10;
        return 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player victim = ctx.playerVictim();
        if (victim == null || ctx.cancelled || ctx.kind != DamageContext.Kind.MELEE || m.session(victim) == null) return;
        MobDef d = ctx.attacker instanceof LivingEntity le ? Sky.mobs().defOf(le) : null;
        if (!RiftCombat.isRiftMob(d)) return;
        double t = m.dials.meleeHitTime.get() - (belt(victim) ? 1 : 0);
        if (t <= 0) return;
        m.travel.addTime(victim, -t);
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || !RiftCombat.isRiftMob(e.def) || m.session(e.killer) == null) return;
        if (has(e.killer, "GARLIC_FLAVORED_GUMMY_BEAR")) e.combatXp += 3;
    }

    /** Core cancels PvP outside PVP zones; the punch still counts for the artifact, so this listens to cancelled hits. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPunch(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a) || !(e.getEntity() instanceof Player b) || a == b) return;
        RiftSession sa = m.session(a);
        if (sa == null || m.session(b) == null || !has(a, "PUNCHCARD_ARTIFACT")) return;
        if (sa.punched.contains(b.getUniqueId()) || sa.punched.size() >= 20) return;
        sa.punched.add(b.getUniqueId());
        double t = m.dials.punchSeconds.get();
        m.travel.addTime(a, t);
        m.travel.addTime(b, t);
        Msg.send(a, "&aPunched in! &7You and &f" + b.getName() + " &7regain &a" + (int) t + "sф&7. &8(" + sa.punched.size() + "/20 this visit)");
        Msg.send(b, "&f" + a.getName() + " &7punched in! You both regain &a" + (int) t + "sф&7.");
        Msg.sound(a, Sound.BLOCK_NOTE_BLOCK_BELL, 1.6f);
    }
}
