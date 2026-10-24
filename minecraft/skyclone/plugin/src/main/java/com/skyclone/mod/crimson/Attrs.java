package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wave 5: other modules' effects that land in THIS module's systems.
 * <ul>
 *   <li>Hunting attributes ({@code Features.Hunting.attributeLevel(p, "value:<key>")} = level x per-level x
 *       echo, i.e. the number to apply): {@code crimson_essence} (+x% Crimson Essence), {@code crimson_serendipity}
 *       (+x% Vanquisher odds, {@link Bosses#vanquisherChance}), {@code matriarch_cubs} (+x% chance of a second
 *       Heavy Pearl, {@link Pearls#collect}).</li>
 *   <li>The Leviathan pet's "Crimson Tithe" ({@code Features.Pets.abilityValue(p, "kuudra_crimson", "a")}: +a%
 *       crimson essence) for essence earned outside the Leviathan hunt's chests - the kuudra module already
 *       applies it to its own chests (source DUNGEON), so those are skipped here to never pay it twice.</li>
 * </ul>
 * Every read is defensive: a missing or throwing provider reads as 0.
 */
final class Attrs implements Listener {

    static final String ESSENCE = "ESSENCE_CRIMSON";

    /** A hunting attribute's applied value ("value:" form), 0 when the hunting module is absent. */
    static double value(Player p, String attr) {
        if (p == null) return 0;
        try {
            double v = Sky.service(Features.Hunting.class).attributeLevel(p, "value:" + attr);
            return Double.isFinite(v) ? Math.max(0, v) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The active pet's perk number, 0 when none. */
    static double pet(Player p, String key) {
        try {
            double v = Sky.service(Features.Pets.class).abilityValue(p, key, "a");
            return Double.isFinite(v) ? Math.max(0, v) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** "+x% more Crimson Essence" for a gain of this source: hunting's attribute always, the pet's outside the hunt's chests. */
    static double essenceBonusPercent(Player p, LootSource source) {
        double pct = value(p, "crimson_essence");
        if (source != LootSource.DUNGEON) pct += pet(p, "kuudra_crimson");
        return pct;
    }

    /**
     * Earned Crimson Essence (natural sources only: drops, the hunt, fishing...) is topped up by the bonus.
     * Runs at LOWEST, before the reforges module pours the essence into the pouch at LOW; the bonus goes
     * straight into the pouch through {@code Features.Essence}, so it never re-fires this event.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGain(SkyEvents.ItemGain e) {
        if (e.player == null || e.amount <= 0 || !ESSENCE.equals(e.itemId)) return;
        if (e.source == null || !e.source.natural) return;
        if (!CrimsonModule.ready(e.player)) return;
        int bonus = CrimsonRules.percentBonus(e.amount, essenceBonusPercent(e.player, e.source), ThreadLocalRandom.current().nextDouble());
        if (bonus <= 0) return;
        String type = essenceType();
        try {
            Sky.service(Features.Essence.class).addEssence(e.player, type, bonus);
        } catch (RuntimeException ex) {
            return;
        }
        Sky.vitals().flash(e.player, "&c+" + bonus + " bonus Crimson Essence", 30);
    }

    // ---- Ghastly Distraction (Dive Ghast shard): mining tomb opal pacifies enemies for x seconds -------------

    private static final java.util.Map<java.util.UUID, Long> PACIFIED = new java.util.HashMap<>();

    /**
     * Called after a player mines an Opal vein in the Smoldering Tomb. With the attribute, every mob within
     * 16 blocks that is hunting them drops the hunt and none may target or hit them for {@code value} seconds;
     * attacking anything ends it early. Slayer bosses and their minibosses are not fooled (reference lore).
     */
    static void ghastlyDistraction(Player p) {
        double secs = value(p, "ghastly_distraction");
        if (!(secs > 0)) return;
        PACIFIED.put(p.getUniqueId(), System.currentTimeMillis() + (long) (secs * 1000));
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(16, 8, 16))
            if (e instanceof org.bukkit.entity.Mob m && m.getTarget() == p && fooled(m)) m.setTarget(null);
        Sky.vitals().flash(p, "&dGhastly Distraction: &7unseen for &a" + com.skyclone.api.ui.Text.commas(secs) + "s", 30);
    }

    static boolean pacified(Player p) {
        Long until = PACIFIED.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) { PACIFIED.remove(p.getUniqueId()); return false; }
        return true;
    }

    private static boolean fooled(org.bukkit.entity.LivingEntity mob) {
        com.skyclone.api.mob.MobDef d;
        try { d = Sky.mobs().defOf(mob); } catch (RuntimeException ex) { return true; }
        return d == null || d.slayer == null || !(d.has("boss") || d.has("miniboss") || d.has("slayer_mini"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && !PACIFIED.isEmpty() && pacified(p) && e.getEntity() instanceof org.bukkit.entity.LivingEntity le && fooled(le))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        if (PACIFIED.isEmpty() || e.ctx == null) return;
        Player attacker = e.ctx.playerAttacker();
        if (attacker != null) { PACIFIED.remove(attacker.getUniqueId()); return; }   // attacking ends it
        Player victim = e.ctx.playerVictim();
        if (victim != null && e.ctx.attacker != null && pacified(victim) && fooled(e.ctx.attacker)) e.setCancelled(true);
    }

    /** The pouch type of Crimson Essence ({@code extra.essence} on the item, "CRIMSON" by the id). */
    static String essenceType() {
        ItemDef d = Sky.items().def(ESSENCE);
        if (d != null && d.extra != null && d.extra.has("essence")) {
            try { return d.extra.get("essence").getAsString(); } catch (RuntimeException ignored) { }
        }
        return "CRIMSON";
    }
}
