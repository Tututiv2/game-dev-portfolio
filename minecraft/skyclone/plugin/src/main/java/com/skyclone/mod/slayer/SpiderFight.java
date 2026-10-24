package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Msg;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.util.Vector;

/**
 * Broodweaver Patriarch (research 04 sec 2.6 / 6.2):
 * <pre>
 * all  Backstab: hits from behind +20/30/40/60/80%; leaps behind you every 8 s
 * T2+  Paralytic Venom on hit: -Vitality%, -Speed% for 5 s (T5 also strips Absorption)
 * T3+  Web Cocoon at 66% and 33%: invulnerable, lays 2/3/4 egg sacs (3 hits each, T5 9); 15 s to pop them or they
 *      hatch into broodlings; it heals 1%/s while cocooned
 * T4+  Bat Barrage every 20 s: 3 bats (T5 5); each live bat costs you 0.1% (T5 0.2%) max health per second
 * T5   Bound in Death: at 0 HP it fuses with the Elder Broodmother into the Twinbound Brood (20M HP, 4,400 DPS,
 *      passives only)
 * </pre>
 */
final class SpiderFight extends BossFight {

    private boolean web66, web33;
    private long cocoonStart = -1;
    private boolean brood;           // phase 2 (T5)
    private boolean transforming;

    SpiderFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        super(mod, owner, type, tier);
        barColor = BossBar.Color.RED;
    }

    @Override
    void step() {
        Player o = owner();
        if (o == null || transforming) return;
        Location b = boss.getLocation();

        // leap behind the player
        if (!brood && cocoonStart < 0 && every(8) && o.getWorld() == boss.getWorld() && o.getLocation().distance(b) < 16) {
            Vector back = o.getLocation().getDirection().setY(0);
            if (back.lengthSquared() < 0.01) back = new Vector(0, 0, 1);
            back.normalize().multiply(-2.2);
            Location to = ground(o.getLocation().clone().add(back).add(0, 1, 0));
            to.setDirection(o.getLocation().toVector().subtract(to.toVector()));
            Fx.particle(b.clone().add(0, 0.5, 0), Particle.ITEM_COBWEB, 12, 0.5, 0.05);
            teleportBoss(to);
            Fx.sound(to, Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.6f);
        }

        // cocoon
        if (cocoonStart >= 0) {
            Fx.disc(b, 3, Particle.ITEM_COBWEB, 10);
            if (every(1)) heal(max() * 0.01);
            int left = liveMinions("slayer_egg_sac");
            if (left == 0) {
                endCocoon(o, true);
            } else if (ticks - cocoonStart >= 60) {           // 15 s
                for (LivingEntity sac : minionsOf("slayer_egg_sac")) {
                    Location at = sac.getLocation();
                    Sky.mobs().remove(sac);
                    Fx.particle(at, Particle.ITEM_COBWEB, 20, 0.4, 0.1);
                    for (int i = 0; i < 2; i++) spawnMinion("slayer_broodling", ground(at.clone()), Math.max(500, tier.health * 0.01));
                }
                Msg.send(o, "&cThe egg sacs hatched!");
                endCocoon(o, false);
            }
        }

        // bat barrage
        if (tierNo >= 4 && !brood && every(20)) {
            int n = tierNo >= 5 ? 5 : 3;
            for (int i = 0; i < n; i++) {
                LivingEntity bat = spawnMinion("slayer_bat", b.clone().add(0, 2.5, 0), 1);
                if (bat != null) {
                    final LivingEntity fb = bat;
                    Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                        if (fb.isValid() && !fb.isDead()) Sky.mobs().remove(fb);
                    }, 20L * 20);
                }
            }
            Fx.warn(o, "&5&lBAT BARRAGE", "&7Swat the bats!");
            Fx.sound(b, Sound.ENTITY_BAT_TAKEOFF, 1f, 0.6f);
        }
        if (every(1)) {
            int bats = liveMinions("slayer_bat");
            if (bats > 0) hurtPercent(o, bats * (tierNo >= 5 ? 0.2 : 0.1), "bats");
        }
    }

    private void startCocoon(Player o) {
        int[] sac = SlayerMath.eggSacs(tierNo);
        if (sac[0] <= 0) return;
        cocoonStart = ticks;
        immune = "Web Cocoon";
        setImmobile(true);
        Location b = boss.getLocation();
        for (int i = 0; i < sac[0]; i++) {
            double a = Math.PI * 2 * i / sac[0];
            Location at = b.clone().add(Math.cos(a) * 3, 2.2, Math.sin(a) * 3);
            LivingEntity e = spawnMinion("slayer_egg_sac", at, sac[1]);
            if (e != null) {
                e.setGravity(false);
                if (e instanceof Slime s) s.setSize(2);
                e.setGlowing(true);
            }
        }
        if (o != null) Fx.warn(o, "&f&lWEB COCOON", "&7Pop the egg sacs before they hatch!");
        Fx.sound(b, Sound.BLOCK_SLIME_BLOCK_PLACE, 1f, 0.5f);
    }

    private void endCocoon(Player o, boolean popped) {
        cocoonStart = -1;
        immune = null;
        setImmobile(false);
        if (popped && o != null) Msg.send(o, "&aYou destroyed the egg sacs! The Patriarch is vulnerable again.");
    }

    @Override
    void onHit(DamageContext ctx) {
        if (brood) return;
        if (tierNo >= 3) {
            if (!web66 && clampAt(ctx, 0.66)) { web66 = true; Bukkit.getScheduler().runTask(Sky.plugin(), () -> startCocoon(owner())); return; }
            if (!web33 && clampAt(ctx, 0.33)) { web33 = true; Bukkit.getScheduler().runTask(Sky.plugin(), () -> startCocoon(owner())); return; }
        }
        if (tierNo == 5 && !transforming && ctx.finalDamage >= hp()) {
            ctx.finalDamage = Math.max(0, hp() - 1);
            transforming = true;
            immune = "Bound in Death";
            Bukkit.getScheduler().runTask(Sky.plugin(), this::transform);
        }
    }

    private void transform() {
        Player o = owner();
        if (ended || boss == null) return;
        setImmobile(true);
        Location at = boss.getLocation();
        if (o != null) {
            Fx.title(o, "&4&lBOUND IN DEATH", "&7The &cElder Broodmother &7awakens...", 5, 40, 10);
        }
        Fx.sound(at, Sound.ENTITY_WITHER_SPAWN, 1f, 0.5f);
        for (int i = 0; i < 8; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> Fx.ring(at, 3.0 - k * 0.3, Fx.rgb(90, 0, 0), 32), i * 5L);
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (ended) return;
            LivingEntity old = boss;
            LivingEntity nb;
            try {
                nb = Sky.mobs().spawn("slayer_spider_5b", at, o);
            } catch (RuntimeException e) {
                nb = null;
            }
            if (nb == null) {      // could not fuse: let the fight finish on the original body
                transforming = false;
                immune = null;
                setImmobile(false);
                return;
            }
            Sky.mobs().setMaxHealth(nb, 20_000_000 * Math.max(0.0001, SlayerDials.BOSS_HP_SCALE.get()));
            mod.fightIndex.remove(old.getUniqueId());
            if (label != null) { try { label.remove(); } catch (RuntimeException ignored) { } label = null; }
            Sky.mobs().remove(old);
            boss = nb;
            mod.fightIndex.put(nb.getUniqueId(), this);
            if (SlayerDials.COOP_HELP.get() && o != null) {
                String pid = Sky.profiles().profileId(o);
                Sky.mobs().setAllies(nb, pl -> pid != null && pid.equals(Sky.profiles().profileId(pl)));
            }
            brood = true;
            transforming = false;
            immune = null;
            status = "&4Twinbound";
            barColor = BossBar.Color.PURPLE;
            Fx.particle(at.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 1, 0, 0);
            if (o != null) Msg.send(o, "&4&lThe Twinbound Brood &crises! &7It only has its passives left - finish it!");
        }, 40L);
    }

    @Override
    void onStrike(Player target, DamageContext ctx) {
        // backstab: the attacker is behind the target
        Vector facing = target.getLocation().getDirection().setY(0);
        Vector toBoss = boss.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() > 0.01 && toBoss.lengthSquared() > 0.01 && facing.normalize().dot(toBoss.normalize()) < -0.3) {
            ctx.additive += SlayerMath.backstab(tierNo);
            Sky.vitals().flash(target, "&c&lBACKSTAB!", 15);
        }
        if (tierNo >= 2) {
            double[] v = SlayerMath.venom(tierNo);
            mod.debuffs.venom(target, SlayerMath.venomVitality(v[0], webbedCells(target)), v[1], tierNo >= 5, 5000);
        }
        if (brood) ctx.multiplicative *= 1.0;   // the brood's 4,400 DPS is its own MobDef damage
    }

    /**
     * Tarantula pet "Webbed Cells" (pets request 10, research 06: "Vitality reduction is 30% less effective against
     * you"): the % the active pet's perk gives, 0 with no pets module or another pet.
     */
    static double webbedCells(Player p) {
        try {
            return Math.max(0, Sky.service(com.skyclone.api.feature.Features.Pets.class).abilityValue(p, "tarantula_webbed_cells", "a"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    void minionDamaged(LivingEntity m, DamageContext ctx) {
        var d = Sky.mobs().defOf(m);
        if (d == null) return;
        if (d.id.equals("slayer_egg_sac")) {
            ctx.finalDamage = 1;                  // every hit counts as one, whatever it dealt
            Fx.particle(m.getLocation().add(0, 0.5, 0), Particle.ITEM_COBWEB, 6, 0.3, 0.05);
        }
    }
}
