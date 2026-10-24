package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.ability.AbilityService.Use;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Handlers for every weapons ability that ships in items.json. Each one returns true when the
 * ability happened (core then charges mana and starts the cooldown) and false when it could not
 * (no target, not enough coins...), in which case nothing is charged.
 *
 * <p>Numbers are research 06's unless a {@code // TUNE} says why not.
 */
final class Abilities implements Listener {

    final Set<String> registered = new TreeSet<>();
    final Map<String, AbilityService.Handler> handlers = new HashMap<>();
    private final Buffs buffs;
    private final Bows bows;
    private final Passives passives;
    private final Map<UUID, int[]> jerryStreak = new HashMap<>();
    private final Map<UUID, Long> jerryLast = new HashMap<>();
    private final Map<UUID, Integer> healCharges = new HashMap<>();
    private final Map<UUID, Long> healChargeAt = new HashMap<>();
    private org.bukkit.NamespacedKey pearlKey;

    Abilities(Buffs buffs, Bows bows, Passives passives) {
        this.buffs = buffs;
        this.bows = bows;
        this.passives = passives;
    }

    /** Ability ids are prefixed with the module ("weapons_leap") so no other module's handler can collide. */
    static final String PREFIX = "weapons_";

    void reg(String shortId, AbilityService.Handler h) {
        String id = PREFIX + shortId;
        registered.add(id);
        handlers.put(id, h);
        Sky.abilities().register(id, u -> {
            boolean ok = h.use(u);
            if (ok) {
                WeaponData d = WeaponsModule.data(u.player());
                if (d != null) d.abilitiesCast++;
            }
            return ok;
        });
    }

    static double dmg(Use u) { return u.ability().damage; }

    static double sc(Use u) { return u.ability().scaling; }

    static String name(Use u) { return u.ability().name; }

    void registerAll() {
        pearlKey = new org.bukkit.NamespacedKey(Sky.plugin(), "weapons_pearl");

        // ---- movement & buffs -----------------------------------------------------------------
        reg("speed_boost", u -> {
            buffs.buff(u.player(), "quickstep", Stat.SPEED, 100, 30);   // research 06: +100 Speed 30s
            Fx.sound(u.player().getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.6f);
            return true;
        });
        AbilityService.Handler blink = u -> {
            if (!Fx.blink(u.player(), Teleporters.blinkRange(u.item()))) return false;   // research 06: 8 blocks (+1 per tuner), +50 Speed 3s
            buffs.buff(u.player(), "blink", Stat.SPEED, 50, 3);
            return true;
        };
        reg("instant_transmission", blink);
        reg("instant_transmission_void", blink);
        // Riftwalker Blade, sneak: Phasewarp when merged, otherwise the ordinary blink (charged as a blink, see costs below)
        reg("ether_transmission_void", u -> {
            if (!Teleporters.merged(u.item())) return blink.use(u);
            return Teleporters.ether(u.player(), Teleporters.etherRange(u.item()));
        });
        // Phasewarp Conduit: the stand-alone warp (research 06: 57 blocks, 180 mana)
        reg("ether_transmission", u -> Teleporters.ether(u.player(), Teleporters.etherRange(u.item())));
        Sky.abilities().addCostModifier("weapons_unmerged_warp", (use, cost) -> {
            if (use.ability() == null || !"weapons_ether_transmission_void".equals(use.ability().id) || Teleporters.merged(use.item())) return cost;
            if (use.itemDef() != null) for (com.skyclone.api.item.AbilityDef a : use.itemDef().abilities) {
                if ("weapons_instant_transmission_void".equals(a.id)) return a.mana;   // an unmerged blade blinks at the blink price
            }
            return cost;
        });
        reg("parley", u -> {
            Player p = u.player();
            Fx.sound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
            Fx.burst(p.getLocation().add(0, 2, 0), Particle.HAPPY_VILLAGER, 8, 0.4);
            Msg.send(p, "&a[Jester] &fHmm? &7...yes, quite.");
            return true;
        });
        reg("bad_health", u -> {
            Player p = u.player();
            if (Sky.vitals().health(p) <= 100) { Msg.error(p, "You don't have enough health to pay the Blood Price!"); return false; }
            Sky.vitals().damage(p, 100, "blood price");
            int str = WeaponMath.badHealthStrength(Sky.vitals().health(p), Sky.vitals().maxHealth(p));
            buffs.buff(p, "bad_health", Stat.STRENGTH, str, 5);
            Msg.send(p, "&cBlood Price! &7+" + str + " Strength for 5s.");
            Fx.dust(p.getLocation().add(0, 1, 0), Color.RED, 1.5f, 15, 0.4);
            return true;
        });
        reg("enrage", u -> {
            Player p = u.player();
            List<LivingEntity> mobs = Fx.mobsNear(p.getLocation(), 10);   // research 06: 10 blocks, -10% for 10s
            for (LivingEntity m : mobs) {
                buffs.mark(m, "enrage:" + p.getUniqueId(), 1, 10);
                if (m instanceof Mob mob) mob.setTarget(p);
            }
            Fx.ring(p.getLocation(), 10, Particle.ANGRY_VILLAGER, 40);
            Fx.sound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1f);
            return true;
        });
        reg("ragnarock", u -> {
            Player p = u.player();
            long start = Buffs.now();
            Msg.send(p, "&6Doomcall! &7Stay unhurt for 3s...");
            new BukkitRunnable() {
                int ticks;
                @Override public void run() {
                    if (!p.isOnline()) { cancel(); return; }
                    if (passives.lastHurt(p) > start) {
                        Msg.send(p, "&cDoomcall interrupted!");
                        cancel();
                        return;
                    }
                    Fx.ring(p.getLocation(), 1.2, Particle.LAVA, 6);
                    if ((ticks += 5) >= 60) {   // research 06: 3s channel, then 10s
                        doomcall(p);
                        cancel();
                    }
                }
            }.runTaskTimer(Sky.plugin(), 5L, 5L);
            return true;
        });
        reg("extreme_focus", u -> {
            Player p = u.player();
            String island = null;
            try { island = Sky.worlds().islandOf(p.getWorld()); } catch (RuntimeException ignored) { }
            if (!"the_end".equals(island)) { Msg.error(p, "Focus Burn only works in The End!"); return false; }
            return focusBurn(p, true);
        });
        reg("extreme_focus_bow", u -> focusBurn(u.player(), false));
        reg("hellstorm", u -> {
            Player p = u.player();
            buffs.buff(p, "hellstorm", Stat.ABILITY_DAMAGE, 5, 30);   // research 06: +5 AD, double damage taken, 30s
            buffs.flag(p, "hellstorm", 1, 30);
            Fx.burst(p.getLocation().add(0, 1, 0), Particle.FLAME, 30, 0.6);
            return true;
        });
        reg("life_blood", u -> {
            Player p = u.player();
            double cost = Sky.vitals().maxHealth(p) * 0.10;   // research 06: 10% max health, +30 Strength 10s
            if (Sky.vitals().health(p) <= cost) { Msg.error(p, "You don't have enough health!"); return false; }
            Sky.vitals().damage(p, cost, "blood pact");
            int n = 0;
            for (Player o : p.getWorld().getPlayers()) {
                if (o == p || o.getLocation().distanceSquared(p.getLocation()) > 100) continue;
                buffs.buff(o, "life_blood", Stat.STRENGTH, 30, 10);
                Msg.send(o, "&c" + p.getName() + " &7gave you &c+30❁ Strength &7for 10s!");
                n++;
            }
            Msg.send(p, n == 0 ? "&7No allies nearby to empower." : "&7Empowered &a" + n + " &7allies.");
            return true;
        });
        reg("recall", u -> {
            Sky.worlds().sendHome(u.player());
            return true;
        });

        // ---- magic: areas ---------------------------------------------------------------------
        reg("iron_punch", u -> {
            Player p = u.player();
            Location c = p.getLocation();
            for (LivingEntity m : Fx.mobsNear(c, 4)) Fx.magic(p, m, dmg(u), sc(u), name(u));   // TUNE: hexagon read as radius 4
            Fx.ring(c, 3.5, Particle.CLOUD, 30);
            Fx.sound(c, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.7f);
            return true;
        });
        reg("dragon_rage", u -> {
            Player p = u.player();
            for (LivingEntity m : Fx.cone(p, WeaponDials.CONE_RANGE.get(), 45)) {
                Fx.magic(p, m, dmg(u), sc(u), name(u));
                Fx.knockback(m, p.getLocation(), 2.2, 0.6);   // research 06: large knockback
            }
            Fx.beam(p.getEyeLocation(), p.getEyeLocation().getDirection(), WeaponDials.CONE_RANGE.get(), Particle.FLAME);
            Fx.sound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.3f);
            return true;
        });
        reg("lightning_strike", u -> {
            Player p = u.player();
            List<LivingEntity> near = Fx.mobsNear(p.getLocation(), 10);   // research 06: nearest monster within 10
            if (near.isEmpty()) { Msg.error(p, "There are no monsters nearby!"); return false; }
            LivingEntity m = near.get(0);
            m.getWorld().strikeLightningEffect(m.getLocation());
            Fx.magic(p, m, dmg(u), sc(u), name(u));
            return true;
        });
        reg("starfall", u -> {
            Player p = u.player();
            Location at = Fx.aim(p, 20);
            pulses(p, at, 3, 5, 20, m -> Fx.magic(p, m, dmg(u), sc(u), name(u)), Particle.END_ROD);   // research 06: 3 blocks, 5s, per second
            return true;
        });
        reg("volcano_explode", u -> {
            Player p = u.player();
            if (Sky.vitals().health(p) <= 1000) { Msg.error(p, "You need more than 1,000 health to erupt!"); return false; }
            Sky.vitals().damage(p, 1000, "eruption");   // research 06: health cost 1,000
            Fx.healthCost(() -> {
                for (LivingEntity m : Fx.mobsNear(p.getLocation(), 4)) {
                    Fx.magic(p, m, dmg(u), sc(u), name(u));
                    m.setFireTicks(60);
                }
            });
            Fx.burst(p.getLocation().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 1, 0);
            Fx.burst(p.getLocation(), Particle.LAVA, 30, 2);
            Fx.sound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
            return true;
        });
        reg("fire_veil", u -> {
            Player p = u.player();
            new BukkitRunnable() {
                int t;
                @Override public void run() {
                    if (!p.isOnline() || t >= 100) { cancel(); return; }
                    Fx.ring(p.getLocation(), 2.5, Particle.FLAME, 16);
                    if (t % 20 == 0) for (LivingEntity m : Fx.mobsNear(p.getLocation(), 3.5)) Fx.magic(p, m, dmg(u), sc(u), name(u));
                    t += 4;
                }
            }.runTaskTimer(Sky.plugin(), 0L, 4L);   // research 06: 5s, damage every second
            return true;
        });
        reg("fire_freeze", u -> {
            Player p = u.player();
            Location c = p.getLocation();
            new BukkitRunnable() {
                int t;
                @Override public void run() {
                    if (t >= 100) {
                        for (LivingEntity m : Fx.mobsNear(c, 5)) buffs.freeze(m, 10);   // research 06: r5, after 5s, frozen 10s
                        Fx.ring(c, 5, Particle.SNOWFLAKE, 60);
                        Fx.sound(c, Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
                        cancel();
                        return;
                    }
                    Fx.ring(c, 5, t % 20 == 0 ? Particle.FLAME : Particle.SNOWFLAKE, 30);
                    t += 10;
                }
            }.runTaskTimer(Sky.plugin(), 0L, 10L);
            return true;
        });
        reg("burning_souls", u -> {
            Player p = u.player();
            buffs.buff(p, "burning_souls", Stat.DEFENSE, 75, 5);   // research 06: +75 Defense 5s, up to 30,000 over 5s
            double per = dmg(u) / 5.0;
            pulses(p, null, 6, 5, 20, m -> Fx.magic(p, m, per, sc(u), name(u)), Particle.SOUL_FIRE_FLAME);
            return true;
        });
        reg("leap", u -> leap(u.player(), dmg(u), sc(u), name(u)));
        reg("leap_silk", u -> leap(u.player(), dmg(u), sc(u), name(u)));

        // ---- magic: projectiles -----------------------------------------------------------------
        reg("ink_bomb", u -> {
            Player p = u.player();
            Missile m = missile(p, 1.2, 0.03, Particle.SQUID_INK, Material.INK_SAC);
            m.onLand = at -> {
                for (LivingEntity e : Fx.mobsNear(at, 3)) {
                    Fx.magic(p, e, dmg(u), sc(u), name(u));
                    e.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
                }
                Fx.burst(at, Particle.SQUID_INK, 40, 1.5);
                Fx.sound(at, Sound.ENTITY_SQUID_SQUIRT, 1f, 0.8f);
            };
            m.launch();
            return true;
        });
        reg("fire_blast", u -> {
            Player p = u.player();
            for (int i = 0; i < 3; i++) {   // research 06: 3 fireballs, rapid succession
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (!p.isOnline()) return;
                    Missile m = missile(p, 1.4, 0, Particle.FLAME, Material.FIRE_CHARGE);
                    m.onHit = (e, back) -> { Fx.magic(p, e, dmg(u), sc(u), name(u)); e.setFireTicks(40); };
                    m.launch();
                    Fx.sound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.2f);
                }, i * 5L);
            }
            return true;
        });
        AbilityService.Handler iceBolt = u -> {
            Player p = u.player();
            boolean glacial = u.ability().id.endsWith("glacial");
            Missile m = missile(p, 1.5, 0, Particle.SNOWFLAKE, Material.ICE);
            m.onHit = (e, back) -> {
                Fx.magic(p, e, dmg(u), sc(u), name(u));
                e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));   // research 06: slow 5s
            };
            if (glacial) m.onLand = at -> {
                for (LivingEntity e : Fx.mobsNear(at, 3)) Fx.magic(p, e, dmg(u), sc(u), name(u));   // research 06: 3-block burst
                Fx.burst(at, Particle.SNOWFLAKE, 40, 1.5);
            };
            m.launch();
            Fx.sound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 1.6f);
            return true;
        };
        reg("ice_bolt", iceBolt);
        reg("ice_bolt_glacial", iceBolt);
        reg("terrain_toss", u -> {
            Player p = u.player();
            Material block = p.getLocation().subtract(0, 1, 0).getBlock().getType();
            if (!block.isItem() || block.isAir()) block = Material.PACKED_ICE;
            Missile m = missile(p, 1.1, 0.05, Particle.CLOUD, block);
            m.onLand = at -> {
                for (LivingEntity e : Fx.mobsNear(at, 3.5)) Fx.magic(p, e, dmg(u), sc(u), name(u));
                Fx.burst(at, Particle.EXPLOSION, 2, 0.5);
                Fx.sound(at, Sound.BLOCK_STONE_BREAK, 1f, 0.6f);
            };
            m.launch();
            return true;
        });
        reg("molten_wave", u -> {
            Player p = u.player();
            Vector dir = p.getLocation().getDirection().setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
            Missile m = new Missile(p, p.getLocation().add(0, 0.6, 0), dir.normalize().multiply(1.0));
            m.hitRadius = 2.0;
            m.pierce = 50;
            m.life = 14;
            m.particle = Particle.DUST_PLUME;
            m.onHit = (e, back) -> Fx.magic(p, e, dmg(u), sc(u), name(u));
            m.launch();
            Fx.sound(p.getLocation(), Sound.ITEM_BUCKET_EMPTY_LAVA, 1f, 1f);
            return true;
        });
        reg("rapid_fire_jerry", u -> {
            Player p = u.player();
            Missile m = missile(p, 1.6, 0, Particle.HAPPY_VILLAGER, Material.EMERALD);
            m.onHit = (e, back) -> Fx.magic(p, e, dmg(u), sc(u), name(u));
            m.launch();
            p.setVelocity(p.getLocation().getDirection().multiply(-0.6).setY(0.15));   // research 06: knocks you back
            jerryLast.put(p.getUniqueId(), Buffs.now());
            jerryStreak.computeIfAbsent(p.getUniqueId(), k -> new int[1])[0]++;
            Fx.sound(p.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1f, 1.4f);
            return true;
        });
        Sky.abilities().addCostModifier("weapons_jerry", (use, cost) -> {
            if (!(PREFIX + "rapid_fire_jerry").equals(use.ability().id)) return cost;
            UUID id = use.player().getUniqueId();
            Long last = jerryLast.get(id);
            int[] s = jerryStreak.get(id);
            if (last == null || s == null || Buffs.now() - last > 4000) { jerryStreak.remove(id); return cost; }   // resets after 4s
            return WeaponMath.streakCost(cost, s[0], 30);
        });
        reg("coin_conversion", u -> {
            Player p = u.player();
            if (!Sky.economy().withdraw(p, 100_000, "weapons: Transmute cast (" + u.itemDef().id + ")")) {   // research 06: 100,000 coins
                Msg.error(p, "You need 100,000 coins in your purse to cast this!");
                return false;
            }
            Missile m = missile(p, 1.5, 0, Particle.WAX_ON, Material.GOLD_NUGGET);
            m.onHit = (e, back) -> { Fx.magic(p, e, dmg(u), sc(u), name(u)); Fx.knockback(e, p.getLocation(), 1.6, 0.4); };
            m.launch();
            return true;
        });
        AbilityService.Handler rayOfHope = u -> {
            Player p = u.player();
            Missile m = missile(p, 1.8, 0, Particle.END_ROD, Material.SUNFLOWER);
            m.onLand = at -> {
                for (LivingEntity e : Fx.mobsNear(at, 2.5)) Fx.magic(p, e, dmg(u), sc(u), name(u));
                Fx.burst(at, Particle.END_ROD, 20, 1);
            };
            m.pierce = 0;
            m.launch();
            return true;
        };
        reg("ray_of_hope", rayOfHope);
        reg("ray_of_hope_general", rayOfHope);
        reg("firestorm", u -> {
            Player p = u.player();
            Missile m = missile(p, 1.3, 0.02, Particle.FLAME, Material.BLAZE_POWDER);
            m.onLand = at -> pulses(p, at, 7, 10, 20, e -> Fx.magic(p, e, dmg(u) / 10.0, sc(u), name(u)), Particle.FLAME);   // research 06: 10s, r7
            m.launch();
            return true;
        });
        reg("thwack", u -> {
            Player p = u.player();
            ItemStack weapon = u.item().clone();
            Missile m = missile(p, 1.4, 0.0, Particle.CRIT, Material.STICK);
            m.returnAfter = 12;
            m.pierce = 99;
            m.onHit = (e, back) -> Fx.melee(p, e, weapon, 1.0);
            m.launch();
            return true;
        });
        reg("flay", u -> flay(u, false));
        reg("flay_flaming", u -> flay(u, true));

        // ---- heals ---------------------------------------------------------------------------------
        reg("instant_heal", u -> zombieHeal(u.player(), 320, 7, 64, 4));          // research 06
        reg("instant_heal_ornate", u -> zombieHeal(u.player(), 420, 7, 84, 5));
        reg("instant_heal_florid", u -> zombieHeal(u.player(), 500, 8, 100, 5));

        // ---- bows -----------------------------------------------------------------------------------
        reg("ender_warp", u -> {
            Player p = u.player();
            EnderPearl pearl = p.launchProjectile(EnderPearl.class);
            pearl.getPersistentDataContainer().set(pearlKey, PersistentDataType.STRING, p.getUniqueId().toString());
            return true;
        });
    }

    // ---- shared pieces ------------------------------------------------------------------------------

    private Missile missile(Player p, double speed, double gravity, Particle particle, Material item) {
        Location from = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.8));
        Missile m = new Missile(p, from, p.getEyeLocation().getDirection().multiply(speed));
        m.gravity = gravity;
        m.particle = particle;
        if (item != null) m.item(item);
        return m;
    }

    /** {@code times} pulses every {@code every} ticks around {@code at} (null = follow the player). */
    private void pulses(Player p, Location at, double radius, int times, long every, java.util.function.Consumer<LivingEntity> hit, Particle part) {
        new BukkitRunnable() {
            int n;
            @Override public void run() {
                if (!p.isOnline() || n++ >= times) { cancel(); return; }
                Location c = at != null ? at : p.getLocation();
                for (LivingEntity m : Fx.mobsNear(c, radius)) hit.accept(m);
                Fx.ring(c, radius, part, (int) (radius * 10));
                if (at != null) c.getWorld().spawnParticle(part, c.clone().add(0, 2, 0), 20, radius / 2, 1, radius / 2, 0.01);
            }
        }.runTaskTimer(Sky.plugin(), 0L, every);
    }

    private boolean focusBurn(Player p, boolean reduction) {
        double mana = Sky.vitals().mana(p);
        double[] f = WeaponMath.focusBurn(mana);
        if (f[1] <= 0) { Msg.error(p, "You need at least 100 mana to burn!"); return false; }
        Sky.vitals().useMana(p, mana);
        buffs.flag(p, "focus_next", f[1], 60);   // TUNE: the boosted hit is kept for 60s
        if (reduction) buffs.flag(p, "focus_dr", f[0], 5);
        Msg.send(p, "&5Focus Burn! &7Burned &b" + Text.commasInt(mana) + " mana&7: next hit &a+" + (int) f[1] + "%"
                + (reduction ? "&7, &a" + (int) f[0] + "% &7less damage taken for 5s." : "&7."));
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.PORTAL, 40, 0.5);
        return true;
    }

    private void doomcall(Player p) {
        Msg.send(p, "&6Doomcall! &7Your weapon's Strength surges for &a10s&7.");
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.LAVA, 20, 0.5);
        Fx.sound(p.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 1f, 0.6f);
        // the held-stat provider reads this: weapon Strength x (multiplier) while active
        DoomcallHolder.set(p, WeaponDials.RAGNAROCK_BONUS.get());
        Sky.stats().invalidate(p);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, 201L);
    }

    private boolean leap(Player p, double damage, double scaling, String name) {
        Vector v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() > 1e-4) v.normalize().multiply(1.0);
        v.setY(1.0);
        p.setVelocity(v);
        Fx.sound(p.getLocation(), Sound.ENTITY_GOAT_LONG_JUMP, 1f, 1f);
        new BukkitRunnable() {
            int t;
            @Override public void run() {
                t++;
                if (!p.isOnline() || t > 80) { cancel(); return; }
                if (t > 4 && ((org.bukkit.entity.Entity) p).isOnGround()) {
                    for (LivingEntity m : Fx.mobsNear(p.getLocation(), 4)) {   // TUNE: "nearby" read as 4 blocks
                        Fx.magic(p, m, damage, scaling, name);
                        buffs.freeze(m, 1);   // research 06: frozen 1s
                    }
                    p.setFallDistance(0);
                    Fx.ring(p.getLocation(), 3, Particle.CLOUD, 30);
                    Fx.sound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
                    cancel();
                }
            }
        }.runTaskTimer(Sky.plugin(), 1L, 1L);
        return true;
    }

    private boolean flay(Use u, boolean flaming) {
        Player p = u.player();
        ItemStack weapon = u.item();
        int sea = 0;
        passives.cleaving(true);
        try {
            for (LivingEntity m : Fx.cone(p, 6, 70)) {   // TUNE: arc = 6 blocks, 140 degrees
                Fx.melee(p, m, weapon, 1.0);
                if (MobTypes.is("sea_creature", m)) sea++;
            }
        } finally {
            passives.cleaving(false);
        }
        if (flaming && sea > 0) {
            double pct = Math.min(25, sea) / 100.0;   // research 06: 1% per sea creature, max 25%
            Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * pct);
        }
        Fx.beam(p.getEyeLocation(), p.getEyeLocation().getDirection(), 6, flaming ? Particle.FLAME : Particle.SOUL);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
        return true;
    }

    /** Mend Flesh: charges that return one at a time (TUNE: the reference shows only the heal amounts). */
    private boolean zombieHeal(Player p, double self, double radius, double others, int maxCharges) {
        UUID id = p.getUniqueId();
        long now = Buffs.now();
        long recharge = (long) (WeaponDials.HEAL_CHARGE_SECONDS.get() * 1000);
        int charges = healCharges.getOrDefault(id, maxCharges);
        Long at = healChargeAt.get(id);
        if (at != null && charges < maxCharges) {
            int back = (int) ((now - at) / Math.max(1, recharge));
            if (back > 0) {
                charges = Math.min(maxCharges, charges + back);
                healChargeAt.put(id, charges >= maxCharges ? null : at + back * recharge);
            }
        }
        if (charges <= 0) { Msg.error(p, "No heal charges left! One returns every " + (recharge / 1000) + "s."); return false; }
        charges--;
        healCharges.put(id, charges);
        if (healChargeAt.get(id) == null) healChargeAt.put(id, now);
        Sky.vitals().heal(p, self);
        for (Player o : p.getWorld().getPlayers()) {
            if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= radius * radius) Sky.vitals().heal(o, others);
        }
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.HEART, 6, 0.5);
        Msg.send(p, "&aMend Flesh! &7Healed &c" + (int) self + "❤&7. Charges: &e" + charges + "&7/" + maxCharges);
        return true;
    }

    // ---- Pearl Shot landing -------------------------------------------------------------------------

    @EventHandler
    public void onPearl(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl) || pearlKey == null) return;
        String owner = pearl.getPersistentDataContainer().get(pearlKey, PersistentDataType.STRING);
        if (owner == null) return;
        Player p = Bukkit.getPlayer(UUID.fromString(owner));
        if (p == null) return;
        Location at = pearl.getLocation();
        for (LivingEntity m : Fx.mobsNear(at, 8)) {   // research 06: r8, 10% of their health, max 500
            Sky.combat().rawDamage(m, WeaponMath.pearlDamage(Sky.mobs().maxHealth(m)), p, DamageContext.Kind.MAGIC);
        }
        Fx.burst(at, Particle.PORTAL, 60, 2);
    }

    /** Simple holder so the stat provider can find the Doomcall multiplier (kept out of Buffs' numeric map). */
    static final class DoomcallHolder {
        private static final Map<UUID, double[]> M = new HashMap<>();
        static void set(Player p, double mult) { M.put(p.getUniqueId(), new double[]{mult, Buffs.now() + 10_000}); }
        static double get(Player p) {
            double[] v = M.get(p.getUniqueId());
            if (v == null || v[1] < Buffs.now()) { M.remove(p.getUniqueId()); return 0; }
            return v[0];
        }
    }
}
