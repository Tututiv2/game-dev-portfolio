package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.inventory.ItemStack;

/**
 * KALHUIKI_MASK (armor owns the item; shown as the Tidecarver mask): "While wearing this mask, members of the Kalhuiki
 * Tribe will not attack you!" Our tribe is the Tidecarver Tribesmen of the Jungle and the temple court (family
 * {@code ch_kalhuiki}): they never pick a masked player as a target and their blows on one are cancelled. The Door
 * Guardian is a guardian, not a member, and still fights. Dial {@code hollows.kalhuiki-mask}.
 */
final class Kalhuiki implements Listener {

    static final String FAMILY = "ch_kalhuiki", MASK = "KALHUIKI_MASK";

    private final HollowsModule m;

    Kalhuiki(HollowsModule m) { this.m = m; }

    static boolean masked(Player p) {
        ItemStack h = p.getInventory().getHelmet();
        return h != null && MASK.equals(Sky.items().idOf(h));
    }

    private boolean tribe(LivingEntity e) {
        if (e == null || !Sky.mobs().isSkyMob(e)) return false;
        MobDef d = Sky.mobs().defOf(e);
        return d != null && FAMILY.equals(d.familyId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!m.dials.kalhuikiMask.get() || !(e.getTarget() instanceof Player p) || !(e.getEntity() instanceof LivingEntity le)) return;
        if (tribe(le) && masked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (!m.dials.kalhuikiMask.get()) return;
        Player victim = e.ctx.playerVictim();
        if (victim == null || !tribe(e.ctx.attacker) || !masked(victim)) return;
        e.setCancelled(true);
        if (e.ctx.attacker instanceof Mob mob && victim.equals(mob.getTarget())) mob.setTarget(null);
    }
}
