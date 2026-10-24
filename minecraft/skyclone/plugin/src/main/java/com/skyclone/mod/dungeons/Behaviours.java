package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.model.Balance;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Miniboss gimmicks (research 04 sec 3.10): the Fallen Explorer calls lightning, the Gilded Monarch regenerates, the
 * Furious Excavator throws potions (heals itself, slows you), the Shade Stalker vanishes and strikes from behind, the
 * Frostbound Explorer breathes an ice beam. Cadences and numbers are TUNE and live in {@link Balance#pattern} so
 * {@code BalanceTest} measures the same fight that ships (a duel is decided by rate, not hits: playtest-endgame).
 */
final class Behaviours {

    private Behaviours() {}

    static final Balance.Pattern EXPLORER = Balance.pattern("dungeons_explorer"), MONARCH = Balance.pattern("dungeons_monarch"),
            EXCAVATOR = Balance.pattern("dungeons_excavator"), SHADE = Balance.pattern("dungeons_shade"), FROST = Balance.pattern("dungeons_frost");

    private static final Map<UUID, Long> NEXT = new HashMap<>();

    private static boolean ready(LivingEntity mob, long everyMs) {
        long now = System.currentTimeMillis();
        Long n = NEXT.get(mob.getUniqueId());
        if (n == null) { NEXT.put(mob.getUniqueId(), now + everyMs / 2); return false; }
        if (now < n) return false;
        NEXT.put(mob.getUniqueId(), now + everyMs);
        return true;
    }

    private static Player target(LivingEntity mob, double range) {
        if (mob instanceof Mob m && m.getTarget() instanceof Player p && p.getLocation().distanceSquared(mob.getLocation()) <= range * range) return p;
        Player best = null;
        double bd = range * range;
        for (Player p : mob.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(mob.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    static void register() {
        Sky.mobs().registerBehaviour("dungeons_boss", new MobBehaviour() { });
        Sky.mobs().registerBehaviour("dungeons_explorer", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (!ready(mob, (long) EXPLORER.specialMs())) return;
                Player t = target(mob, 14);
                if (t == null) return;
                t.getWorld().strikeLightningEffect(t.getLocation());
                Sky.combat().damagePlayer(t, def.damage * EXPLORER.specialFactor(), mob, DamageContext.Kind.MOB);
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) { NEXT.remove(mob.getUniqueId()); }
        });
        Sky.mobs().registerBehaviour("dungeons_monarch", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (!ready(mob, (long) MONARCH.regenMs())) return;
                double max = Sky.mobs().maxHealth(mob), hp = Sky.mobs().health(mob);
                Sky.mobs().setHealth(mob, Math.min(max, hp + max * MONARCH.regenFrac()));
                Fx.particle(mob.getLocation().add(0, 1, 0), Particle.WAX_ON, 15, 0.5, 0.05);
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) { NEXT.remove(mob.getUniqueId()); }
        });
        Sky.mobs().registerBehaviour("dungeons_excavator", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (!ready(mob, (long) EXCAVATOR.regenMs())) return;
                double max = Sky.mobs().maxHealth(mob), hp = Sky.mobs().health(mob);
                Sky.mobs().setHealth(mob, Math.min(max, hp + max * EXCAVATOR.regenFrac()));
                Fx.particle(mob.getLocation().add(0, 1, 0), Particle.HEART, 5, 0.4, 0.05);
                for (Player p : mob.getWorld().getPlayers())
                    if (p.getLocation().distanceSquared(mob.getLocation()) < 36)
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true));
                Fx.sound(mob.getLocation(), Sound.ENTITY_SPLASH_POTION_BREAK, 1f, 1f);
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) { NEXT.remove(mob.getUniqueId()); }
        });
        Sky.mobs().registerBehaviour("dungeons_shade", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                Player t = target(mob, 16);
                boolean close = t != null && t.getLocation().distanceSquared(mob.getLocation()) < 36;
                if (!close) mob.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 30, 0, false, false));
                else mob.removePotionEffect(PotionEffectType.INVISIBILITY);
                if (t == null || !ready(mob, (long) SHADE.specialMs())) return;
                Vector back = t.getLocation().getDirection().setY(0).normalize().multiply(-1.5);
                Location to = t.getLocation().add(back);
                to.setDirection(t.getLocation().toVector().subtract(to.toVector()));
                mob.teleport(to);
                Fx.particle(to.clone().add(0, 1, 0), Particle.LARGE_SMOKE, 20, 0.3, 0.02);
                Sky.combat().damagePlayer(t, def.damage * SHADE.specialFactor(), mob, DamageContext.Kind.MOB);
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) { NEXT.remove(mob.getUniqueId()); }
        });
        Sky.mobs().registerBehaviour("dungeons_frost", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (!ready(mob, (long) FROST.specialMs())) return;
                Player t = target(mob, 12);
                if (t == null) return;
                Fx.line(mob.getEyeLocation(), t.getLocation().add(0, 1, 0), Color.fromRGB(150, 220, 255), 0.3);
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3, false, true));
                Sky.combat().damagePlayer(t, def.damage * FROST.specialFactor(), mob, DamageContext.Kind.MOB);
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) { NEXT.remove(mob.getUniqueId()); }
        });
    }
}
