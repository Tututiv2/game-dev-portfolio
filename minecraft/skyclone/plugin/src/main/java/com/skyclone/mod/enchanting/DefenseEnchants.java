package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Armour enchants that react to the player being hit: conditional Defense (Blast / Projectile /
 * Fire Protection, Tidal, Last Stand) as a DamageModifier on incoming hits, and Thorns, Riposte,
 * Last Stand's trigger, No Pain No Gain and Reflection after the hit lands.
 */
final class DefenseEnchants implements Listener {

    /** player -> attackers that already hit them (Riposte fires on the FIRST hit from each enemy). */
    private static final Map<UUID, Set<UUID>> SEEN = new HashMap<>();
    /** player -> last time they took a hit (Respite's "out of combat"). */
    static final Map<UUID, Long> LAST_HURT = new HashMap<>();

    void register() {
        Sky.combat().addModifier("enchanting_armor", 100, this::incoming);
    }

    private void incoming(DamageContext ctx) {
        Player p = ctx.playerVictim();
        if (p == null || ctx.kind == DamageContext.Kind.TRUE || !EnchantingModule.dials().effects.get()) return;
        double defense = Sky.stats().get(p, Stat.DEFENSE);
        double extra = 0;
        LivingEntity a = ctx.attacker;
        if (a != null && a.getType() == EntityType.CREEPER) extra += Gear.wornSum(p, "blast_protection");
        if (ctx.kind == DamageContext.Kind.PROJECTILE || (a != null && isShooter(a.getType()))) extra += Gear.wornSum(p, "projectile_protection");
        if (a != null && isSeaCreature(a)) extra += defense * Gear.wornSum(p, "tidal") / 100.0;
        double lastStand = Buffs.get(p.getUniqueId(), "last_stand");
        if (lastStand > 0) extra += defense * lastStand / 100.0;
        if (extra > 0) ctx.multiplicative *= EnchantMath.extraDefenseMultiplier(defense, extra);

        // Fire Protection: True Defense against burning, as the same (100+x) curve.
        if (ctx.kind == DamageContext.Kind.ENVIRONMENT && (p.getFireTicks() > 0 || p.getLocation().getBlock().getType() == Material.LAVA)) {
            double td = Sky.stats().get(p, Stat.TRUE_DEFENSE);
            double fire = Gear.wornSum(p, "fire_protection");
            if (fire > 0) ctx.multiplicative *= EnchantMath.extraDefenseMultiplier(td, fire);
        }
    }

    static boolean isShooter(EntityType t) {
        return t == EntityType.SKELETON || t == EntityType.STRAY || t == EntityType.BOGGED || t == EntityType.PILLAGER
                || t == EntityType.BLAZE || t == EntityType.GHAST;
    }

    static boolean isSeaCreature(LivingEntity a) {
        MobDef d = Sky.mobs().defOf(a);
        return d != null && d.has("sea_creature");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player p = ctx.playerVictim();
        if (p == null || !EnchantingModule.ready(p)) return;
        UUID pu = p.getUniqueId();
        LAST_HURT.put(pu, System.currentTimeMillis());
        if (!EnchantingModule.dials().effects.get() || CombatEnchants.inProc) return;
        LivingEntity a = ctx.attacker;
        double dealt = Math.max(0, ctx.finalDamage);
        ThreadLocalRandom r = ThreadLocalRandom.current();

        if (a != null && !(a instanceof Player) && Sky.mobs().isSkyMob(a)) {
            // Thorns: 50% chance to reflect a share of the hit
            double thorns = Gear.wornSum(p, "thorns");
            if (thorns > 0 && r.nextBoolean()) CombatEnchants.proc(a, dealt * thorns / 100.0, p);
            // Riposte (Counter-Strike): first hit from each enemy grants Defense for 7s
            Set<UUID> seen = SEEN.computeIfAbsent(pu, x -> new HashSet<>());
            if (seen.size() > 200) seen.clear();
            if (seen.add(a.getUniqueId())) {
                double cs = Gear.wornSum(p, "counter_strike");
                if (cs > 0) { Buffs.set(pu, "counter_strike", cs, 7000); Sky.stats().invalidate(p); }
            }
            // No Pain No Gain: chance for experience when a mob hits you
            double npng = Gear.wornSum(p, "ultimate_no_pain_no_gain");
            if (npng > 0 && r.nextDouble() * 100 < Math.min(100, npng)) {
                EnchantSpec s = EnchantingModule.reg().spec("ultimate_no_pain_no_gain");
                int orbs = s == null ? 10 : (int) Math.round(s.v(1, Gear.wornMax(p, "ultimate_no_pain_no_gain")));
                EnchantingModule.giveExp(p, orbs);
            }
            // Reflection: arrows cost their shooter Intelligence x value
            if (ctx.kind == DamageContext.Kind.PROJECTILE || isShooter(a.getType())) {
                double mult = Gear.wornSum(p, "reflection", 2);
                if (mult > 0) CombatEnchants.proc(a, Sky.stats().get(p, Stat.INTELLIGENCE) * mult, p);
            }
        }
        // Last Stand: dropping below 40% health
        double ls = Gear.wornSum(p, "ultimate_last_stand");
        if (ls > 0) {
            double max = Sky.vitals().maxHealth(p);
            double after = Sky.vitals().health(p) - dealt;
            if (max > 0 && after < max * 0.4 && Buffs.cooldown(pu, "last_stand", 30_000)) {
                Buffs.set(pu, "last_stand", ls, 10_000);
                double vit = Vitality.restore(p, Gear.wornSum(p, "ultimate_last_stand", 1));   // "Regen 10 Vitality"
                Sky.vitals().flash(p, "&dLast Stand! &a+" + EnchantSpec.fmt(ls) + "% Defense"
                        + (vit > 0 ? " &4+" + EnchantSpec.fmt(Math.round(vit * 10) / 10.0) + "♨" : ""), 40);
            }
        }
    }

    static boolean outOfCombat(Player p, long millis) {
        Long t = LAST_HURT.get(p.getUniqueId());
        return t == null || System.currentTimeMillis() - t > millis;
    }

    static void forget(UUID p) { SEEN.remove(p); LAST_HURT.remove(p); }
}
