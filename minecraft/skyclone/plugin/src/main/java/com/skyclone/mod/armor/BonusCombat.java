package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

import static com.skyclone.mod.armor.SetIds.*;

/**
 * Damage-shaped bonuses: one DamageModifier at priority 200 (sets, CONTRACT §5) for outgoing and
 * incoming numbers, plus a {@link SkyEvents.Damage} listener for things that need the FINAL number
 * (caps, reflection, "ignore this hit").
 */
final class BonusCombat implements Listener {

    static void register() {
        Sky.combat().addModifier("armor_sets", 200, BonusCombat::modify);
    }

    static void modify(DamageContext ctx) {
        if (!ArmorDials.ENABLED.get()) return;
        Player attacker = ctx.playerAttacker();
        Player victim = ctx.playerVictim();
        if (attacker != null && victim == null) outgoing(attacker, ctx);
        else if (victim != null && attacker == null && ctx.attacker != null) incoming(victim, ctx);
    }

    // ---- a player hits a mob -----------------------------------------------------------------

    private static void outgoing(Player p, DamageContext ctx) {
        Worn w = Worn.of(p);
        if (!w.any()) return;
        MobDef def = ArmorUtil.def(ctx.victim);
        boolean weaponHit = ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.PROJECTILE;
        String weapon = ctx.weapon == null ? null : Sky.items().idOf(ctx.weapon);
        markTarget(p, w, def);

        // Dashing: glass cannon damage bonus on every kind of hit the player deals.
        if (ctx.kind != DamageContext.Kind.TRUE) {
            if (w.full(TUX_ELEGANT)) ctx.additive += ArmorDials.TUX_ELEGANT_DMG.get();
            else if (w.full(TUX_FANCY)) ctx.additive += ArmorDials.TUX_FANCY_DMG.get();
            else if (w.full(TUX_CHEAP)) ctx.additive += ArmorDials.TUX_CHEAP_DMG.get();
        }
        if (w.full(STRONG) && ctx.kind == DamageContext.Kind.MELEE && AOTE.equals(weapon)) ctx.flatBonus += ArmorDials.STRONG_AOTE_DAMAGE.get();
        if (w.full(SUPERIOR) && ctx.kind == DamageContext.Kind.MAGIC && AOTD.equals(weapon)) ctx.additive += ArmorDials.SUPERIOR_AOTD_PCT.get();
        if (ctx.kind == DamageContext.Kind.PROJECTILE && w.pieces(GRUNT) > 0) ctx.additive += SetMath.perPiece(w.pieces(GRUNT), ArmorDials.GRUNT_ARROW_PCT.get());
        if (ArmorUtil.seaCreature(def) && w.pieces(BACKWATER) > 0) ctx.additive += SetMath.perPiece(w.pieces(BACKWATER), ArmorDials.BACKWATER_DMG.get());
        if (ArmorUtil.magmatic(def)) {
            if (w.full(THUNDER)) ctx.multiplicative *= ArmorDials.THUNDER_DEALT.get();
            if (w.lordsBlessing()) ctx.multiplicative *= ArmorDials.MAGMA_LORD_DEALT.get();
        }
        // Rekindle (wave 3): more damage to a target that is ablaze, more still the longer YOU burn.
        int rekindle = w.pieces(REKINDLED);
        if (rekindle > 0 && ctx.kind != DamageContext.Kind.TRUE && ctx.victim.getFireTicks() > 0) {
            ArmorState.P st = ArmorState.peek(p.getUniqueId());
            double burning = st == null || st.burningSince <= 0 ? 0 : (System.currentTimeMillis() - st.burningSince) / 1000.0;
            ctx.additive += SetMath.rekindleBonus(rekindle, ArmorDials.REKINDLE_PCT.get(), burning,
                    ArmorDials.REKINDLE_FIRE_PER_SEC.get(), ArmorDials.REKINDLE_FIRE_CAP.get());
            ctx.tags.add("rekindle");
        }
        // Static Charge: discharge on a weapon hit.
        if (weaponHit && w.full(THUNDER)) {
            ArmorState.P st = ArmorState.of(p.getUniqueId());
            int charges = SetMath.staticCharges((System.currentTimeMillis() - st.thunderSince) / 1000,
                    ArmorDials.THUNDER_SECONDS.get(), ArmorDials.THUNDER_CAP.get());
            if (charges > 0) {
                ctx.additive += charges * ArmorDials.THUNDER_PER_CHARGE.get();
                ctx.tags.add("static_charge");
                st.thunderSince = System.currentTimeMillis();
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, ctx.victim.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 1.6f);
            }
        }
    }

    /**
     * Remember the last hit on a magmatic / mythic creature (shown by {@code /sb armor info}). Wave 5: the
     * "Magic Find vs" bonuses no longer ride on this as a timed stat; they go through core's per-target loot
     * hook ({@link #magicFindVs}), so they apply to exactly that creature's drops and nothing else.
     */
    private static void markTarget(Player p, Worn w, MobDef def) {
        if (def == null) return;
        long now = System.currentTimeMillis();
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        if (ArmorUtil.magmatic(def)) st.magmaticHit = now;
        if (ArmorUtil.mythic(def)) st.mythicHit = now;
    }

    /**
     * Core's per-target Magic Find hook ({@code Sky.loot().addMagicFindModifier("armor_sets", ...)}): Lord's
     * Blessing +20 vs magmatic, Familiarity +2.5/Mythos piece and the Crown of Greed +10 vs mythological.
     * Called once per kill on the main thread; never throws.
     */
    static Double magicFindVs(Player p, MobDef def) {
        try {
            if (!ArmorDials.ENABLED.get() || p == null || def == null) return 0.0;
            Worn w = Worn.of(p);
            if (!w.any()) return 0.0;
            return SetMath.magicFindVs(ArmorUtil.magmatic(def), ArmorUtil.mythic(def), w.lordsBlessing(), w.pieces(MYTHOS),
                    w.wears(CROWN_GREED), ArmorDials.MAGMA_LORD_MF.get(), ArmorDials.FAMILIARITY_MF.get(), ArmorDials.CROWN_GREED_MF.get());
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    // ---- a mob hits a player -----------------------------------------------------------------

    private static void incoming(Player p, DamageContext ctx) {
        Worn w = Worn.of(p);
        if (!w.any()) return;
        MobDef def = ArmorUtil.def(ctx.attacker);
        if (def == null) return;
        // Rekindle, 2+ pieces: a burning mob's melee hit sets you alight (the price of the bonus above).
        if (w.pieces(REKINDLED) >= 2 && ctx.kind == DamageContext.Kind.MOB && ctx.attacker.getFireTicks() > 0) {
            int ticks = (int) Math.round(ArmorDials.REKINDLE_IGNITE_SECONDS.get() * 20);
            if (p.getFireTicks() < ticks) p.setFireTicks(ticks);
        }
        if (ArmorUtil.seaCreature(def) && w.pieces(ANGLER) > 0)
            ctx.multiplicative *= Math.max(0, 1 - SetMath.perPiece(w.pieces(ANGLER), ArmorDials.ANGLER_REDUCE.get()) / 100.0);
        if (ArmorUtil.magmatic(def)) {
            if (w.full(THUNDER)) ctx.multiplicative *= ArmorDials.THUNDER_TAKEN.get();
            if (w.lordsBlessing()) ctx.multiplicative *= ArmorDials.MAGMA_LORD_TAKEN.get();
        }
        if (ArmorUtil.mythic(def)) {
            int n = w.pieces(MYTHOS) + w.pieces(CHALLENGER);
            if (n > 0) ctx.multiplicative *= Math.max(0, 1 - SetMath.perPiece(n, ArmorDials.UNEARTHED_PCT.get()) / 100.0);
            if (w.wears(CROWN_GREED)) ctx.multiplicative *= 1.25;
            if (w.wears(CROWN_AVARICE) || w.wears(CROWN_AVARICE_2)) ctx.multiplicative *= 1.5;
        }
        if (ArmorUtil.ghost(def) && w.pieces(SORROW) >= 2) ctx.multiplicative *= ArmorDials.SORROW_GHOST_MULT.get();
    }

    // ---- after modifiers: caps, shields, reflection ----------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (!ArmorDials.ENABLED.get()) return;
        DamageContext ctx = e.ctx;
        Player victim = ctx.playerVictim();
        if (victim != null) defend(victim, ctx);
        Player attacker = ctx.playerAttacker();
        if (attacker != null && ctx.playerVictim() == null) attack(attacker, ctx);
    }

    private void defend(Player p, DamageContext ctx) {
        Worn w = Worn.of(p);
        if (!w.any()) return;
        long now = System.currentTimeMillis();
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        double max = Sky.vitals().maxHealth(p), hp = Sky.vitals().health(p);

        // Block Damage (Guardian Chestplate): ignore the first hit taken at full health.
        if (w.wears(GUARDIAN_CHEST) && hp >= max - 0.01 && now >= st.blockDamageReady && ctx.finalDamage > 0) {
            st.blockDamageReady = now + ArmorDials.BLOCK_DAMAGE_COOLDOWN.get() * 1000L;
            ctx.cancelled = true;
            p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
            return;
        }
        // Bone Shield (Skeleton's Helmet): a bone eats the hit.
        if (w.wears(SKELETON_HELMET) && ctx.finalDamage > 0) {
            regrowBones(st, now);
            if (st.bones > 0) {
                st.bones--;
                if (st.nextBone <= now) st.nextBone = now + ArmorDials.BONE_SHIELD_SECONDS.get() * 1000L;
                ctx.cancelled = true;
                p.playSound(p.getLocation(), Sound.ENTITY_SKELETON_HURT, 1f, 1.4f);
                p.getWorld().spawnParticle(Particle.ITEM, p.getLocation().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0.05,
                        new org.bukkit.inventory.ItemStack(org.bukkit.Material.BONE));
                return;
            }
        }
        // Mithril's Protection: no single hit above 40% of max health.
        if (w.wears(MITHRIL_COAT) && ctx.finalDamage > max * ArmorDials.MITHRIL_CAP.get()) {
            ctx.finalDamage = SetMath.capHit(ctx.finalDamage, max, ArmorDials.MITHRIL_CAP.get());
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, true, false, true));
        }
        // Projectile Absorption (Zombie armor): heal 10/s for 5 s after a projectile hit.
        if (w.full(ZOMBIE) && ctx.kind == DamageContext.Kind.PROJECTILE && now >= st.zombieHealUntil) {
            st.zombieHealUntil = now + 5000;
            for (int i = 1; i <= 5; i++) {
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (p.isOnline() && Worn.of(p).full(ZOMBIE)) Sky.vitals().heal(p, ArmorDials.ZOMBIE_HEAL.get());
                }, 20L * i);
            }
        }
        // Detonate (Creeper Pants): blast monsters away when this hit drops you under 20%.
        if (w.wears(CREEPER_PANTS) && now >= st.detonateReady && hp - ctx.finalDamage < max * 0.2 && hp - ctx.finalDamage > 0) {
            st.detonateReady = now + ArmorDials.DETONATE_COOLDOWN.get() * 1000L;
            p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 3, 1, 0.5, 1, 0);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
            for (Entity ent : p.getNearbyEntities(5, 3, 5)) {
                if (!(ent instanceof LivingEntity le) || ent instanceof Player) continue;
                if (!Sky.mobs().isSkyMob(le)) continue;
                Vector push = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 0.001) le.setVelocity(push.normalize().multiply(1.4).setY(0.5));
                Sky.combat().rawDamage(le, ArmorDials.CREEPER_DAMAGE.get(), p, DamageContext.Kind.MAGIC);
            }
        }
        // Deflect (Cactus): throw a third back at the attacker. Runs after the hit is settled.
        if (w.full(CACTUS) && ctx.attacker != null && !(ctx.attacker instanceof Player) && ctx.finalDamage > 0) {
            LivingEntity src = ctx.attacker;
            double back = ctx.finalDamage * ArmorDials.CACTUS_REFLECT.get();
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                if (src.isValid() && Sky.mobs().isSkyMob(src)) Sky.combat().rawDamage(src, back, p, DamageContext.Kind.TRUE);
            });
        }
    }

    private void attack(Player p, DamageContext ctx) {
        Worn w = Worn.of(p);
        if (!w.any()) return;
        // Regenerative Howl: a Ferocity strike adds a Defense stack.
        if (ctx.kind == DamageContext.Kind.FEROCITY && w.full(WEREWOLF)) {
            ArmorState.P st = ArmorState.of(p.getUniqueId());
            long now = System.currentTimeMillis();
            ArmorState.live(st.howl, now);
            if (st.howl.size() >= ArmorDials.WEREWOLF_MAX.get()) st.howl.pollFirst();
            st.howl.addLast(now + ArmorDials.WEREWOLF_SECONDS.get() * 1000L);
            Sky.stats().invalidate(p);
        }
        // Spiky (Pufferfish Hat): sometimes prick everything near the target.
        if (ctx.kind == DamageContext.Kind.MELEE && (w.wears(PUFFER_HAT) || w.wears(PUFFER_HAT_2))
                && ThreadLocalRandom.current().nextDouble() < ArmorDials.SPIKY_CHANCE.get()) {
            double dmg = 10 + 0.2 * Sky.stats().get(p, Stat.STRENGTH);
            LivingEntity target = ctx.victim;
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                for (Entity ent : target.getNearbyEntities(3, 2, 3)) {
                    if (ent instanceof LivingEntity le && !(ent instanceof Player) && Sky.mobs().isSkyMob(le)) {
                        Sky.combat().rawDamage(le, dmg, p, DamageContext.Kind.MAGIC);
                    }
                }
            });
        }
    }

    static void regrowBones(ArmorState.P st, long now) {
        int max = ArmorDials.BONE_SHIELD_MAX.get();
        if (st.bones < 0) { st.bones = max; return; }
        while (st.bones < max && st.nextBone > 0 && now >= st.nextBone) {
            st.bones++;
            st.nextBone = st.bones < max ? st.nextBone + ArmorDials.BONE_SHIELD_SECONDS.get() * 1000L : 0;
        }
    }
}
