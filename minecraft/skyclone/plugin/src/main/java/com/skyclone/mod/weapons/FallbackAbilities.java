package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.ability.AbilityService.Use;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Abilities of the shortbows the brief names (Oblivion / Bloodsucker / Bullet Storm) and of the
 * slayer and dungeon weapons this module ships as FALLBACKS. If the slayer or dungeons module
 * registers those items itself, its defs carry its own ability ids and these handlers simply never
 * fire.
 */
final class FallbackAbilities {

    private final Abilities host;
    private final Buffs buffs;
    private final Bows bows;
    private final Passives passives;
    private final Map<UUID, Integer> halberdStreak = new HashMap<>();
    private final Map<UUID, Long> halberdLast = new HashMap<>();
    private final Map<UUID, Location> sinOrigin = new HashMap<>();
    private final Map<UUID, Long> sinUntil = new HashMap<>();

    FallbackAbilities(Abilities host, Buffs buffs, Bows bows, Passives passives) {
        this.host = host;
        this.buffs = buffs;
        this.bows = bows;
        this.passives = passives;
    }

    void registerAll() {
        // ---- shortbow abilities ----------------------------------------------------------------
        host.reg("nasty_bite", u -> {
            Bows.Opts o = new Bows.Opts();
            o.nastyBite = true;
            o.ignoreCooldown = true;
            return bows.shoot(u.player(), u.item(), o);
        });
        host.reg("salvation", u -> {
            Player p = u.player();
            // research 06: castable after landing 3 hits AND with 1 Soulflow to pay; otherwise the click is a normal shot.
            // The soulflow module takes the Soulstream on AbilityUsed (its "soft" table entry), so this only checks it.
            if (bows.terminatorCharge(p) < 3 || !SoulflowLink.canPay(p, 1)) {
                if (bows.terminatorCharge(p) >= 3) SoulflowLink.tellShort(p, "Judgement Beam");
                bows.shoot(p, u.item(), new Bows.Opts());
                return false;
            }
            bows.resetTerminator(p);
            Bows.Shot s = new Bows.Shot();
            s.shooter = p.getUniqueId();
            s.bowId = Traits.idOf(u.item());
            s.bow = u.item().clone();
            s.forceCrit = true;   // the beam always crits
            s.salvationBeam = true;
            s.secondary = true;
            List<LivingEntity> line = Fx.line(p.getEyeLocation(), p.getEyeLocation().getDirection(), 30, 1.0);
            int n = 0;
            for (LivingEntity m : line) {
                if (n++ >= 5) break;   // pierces 5
                bows.hitWith(s, p, m, 1.0);
            }
            Fx.beam(p.getEyeLocation(), p.getEyeLocation().getDirection(), 30, Particle.END_ROD);
            Fx.sound(p.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1f, 1.6f);
            return true;
        });
        host.reg("rapid_fire", u -> {
            Player p = u.player();
            ItemStack bow = u.item().clone();
            new BukkitRunnable() {
                int n;
                @Override public void run() {
                    if (!p.isOnline() || n++ >= 40) { cancel(); return; }   // research 06: 5 arrows/s for 8s at 70%
                    if (!bows.isSame(p, bow)) { cancel(); return; }
                    Bows.Opts o = new Bows.Opts();
                    o.mult = 0.7;
                    o.ignoreCooldown = true;
                    o.quiet = true;
                    if (!bows.shoot(p, p.getInventory().getItemInMainHand(), o)) cancel();
                }
            }.runTaskTimer(Sky.plugin(), 0L, 4L);
            return true;
        });

        // ---- slayer weapons --------------------------------------------------------------------
        host.reg("halberd_throw", u -> {
            Player p = u.player();
            int streak = halberdStreak.getOrDefault(p.getUniqueId(), 0);
            double mult = 0.10 * WeaponMath.doubling(streak);   // research 06: 10% melee, x2 per throw, max 16x
            ItemStack weapon = u.item().clone();
            Missile m = missile(p, 1.4, Particle.CRIT, Material.DIAMOND_AXE);
            m.pierce = 99;
            m.life = 20;
            m.onHit = (e, back) -> Fx.melee(p, e, weapon, mult);
            m.launch();
            halberdStreak.put(p.getUniqueId(), streak + 1);
            halberdLast.put(p.getUniqueId(), Buffs.now());
            return true;
        });
        Sky.abilities().addCostModifier("weapons_halberd", (use, cost) -> {
            if (!(Abilities.PREFIX + "halberd_throw").equals(use.ability().id)) return cost;
            UUID id = use.player().getUniqueId();
            Long last = halberdLast.get(id);
            if (last == null || Buffs.now() - last > WeaponDials.HALBERD_RESET_SECONDS.get() * 1000) {
                halberdStreak.remove(id);
                return cost;
            }
            return cost * WeaponMath.doubling(halberdStreak.getOrDefault(id, 0));
        });
        host.reg("me_smash_head", u -> {
            buffs.flag(u.player(), "smash", 1, 30);
            Msg.send(u.player(), "&6Big Bonk! &7Your next hit lands double.");
            Fx.sound(u.player().getLocation(), Sound.ENTITY_PLAYER_BURP, 1f, 0.8f);
            return true;
        });
        AbilityService.Handler soulcry = u -> {
            double fer = u.ability().id.endsWith("atomsplit") ? 400 : u.ability().id.endsWith("vorpal") ? 300 : 200;   // research 06
            buffs.flag(u.player(), "soulcry", fer, 4);   // research 06: Ferocity vs Endermen only, 4s (Daggers.onSoulcry strikes)
            Fx.burst(u.player().getLocation().add(0, 1, 0), Particle.SOUL, 20, 0.5);
            Fx.sound(u.player().getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 0.8f, 0.6f);
            return true;
        };
        host.reg("soulcry", soulcry);
        host.reg("soulcry_vorpal", soulcry);
        host.reg("soulcry_atomsplit", soulcry);
        host.reg("attunement", u -> {
            ItemDef def = u.itemDef();
            String base = def.extra.has("attunement") ? def.extra.get("attunement").getAsString() : "ashen";
            boolean ashenPair = base.equals("ashen") || base.equals("auric");
            String a = ashenPair ? "ashen" : "spirit", b = ashenPair ? "auric" : "crystal";
            String cur;
            try {
                JsonElement e = Sky.items().data(u.item()).extra.get("attunement");
                cur = e == null ? a : e.getAsString();
            } catch (RuntimeException ex) { cur = a; }
            String next = cur.equals(a) ? b : a;
            Sky.items().update(u.item(), d -> d.extra.addProperty("attunement", next));
            Msg.send(u.player(), "&7Attuned: &6" + Text.titleCase(next));
            Fx.sound(u.player().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            return true;
        });
        host.reg("sinrecall", u -> {
            Player p = u.player();
            UUID id = p.getUniqueId();
            Long until = sinUntil.get(id);
            boolean recast = until != null && until > Buffs.now();
            if (!recast) sinOrigin.put(id, p.getLocation().clone());
            Location from = p.getLocation().clone().add(0, 1, 0);
            Vector dir = p.getLocation().getDirection();
            if (!Fx.blink(p, 4)) return false;   // research 06: 4 blocks
            passives.cleaving(true);
            try {
                for (LivingEntity m : Fx.line(from, dir, 4.5, 1.2)) Fx.melee(p, m, u.item(), 1.0);
            } finally {
                passives.cleaving(false);
            }
            Fx.beam(from, dir, 4, Particle.WITCH);
            sinUntil.put(id, Buffs.now() + 1000);
            long stamp = Buffs.now();
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                Long u2 = sinUntil.get(id);
                if (u2 == null || u2 - 1000 != stamp || !p.isOnline()) return;   // recast since
                Location o = sinOrigin.remove(id);
                sinUntil.remove(id);
                if (o != null && o.getWorld() == p.getWorld()) { p.setFallDistance(0); p.teleport(o); }
            }, 21L);   // research 06: recast within 1s or warp back
            return true;
        });
        Sky.abilities().addCostModifier("weapons_sinrecall", (use, cost) -> {
            if (!(Abilities.PREFIX + "sinrecall").equals(use.ability().id)) return cost;
            Long until = sinUntil.get(use.player().getUniqueId());
            return until != null && until > Buffs.now() ? cost * 1.5 : cost;   // research 06: 1.5x mana on recast
        });
        host.reg("gravity_storm", u -> {
            Player p = u.player();
            Location c = Fx.aim(p, 20);
            new BukkitRunnable() {
                int t;
                @Override public void run() {
                    if (t++ >= 10 || !p.isOnline()) { cancel(); return; }
                    for (LivingEntity m : Fx.mobsNear(c, 8)) {
                        Vector v = c.toVector().subtract(m.getLocation().toVector());
                        if (v.lengthSquared() > 0.5) m.setVelocity(v.normalize().multiply(0.6).setY(0.1));
                    }
                    c.getWorld().spawnParticle(Particle.PORTAL, c, 40, 2, 1, 2, 0.5);
                }
            }.runTaskTimer(Sky.plugin(), 0L, 4L);
            return true;
        });
        host.reg("cells_alignment", u -> {
            buffs.flag(u.player(), "aligned", 1, 6);   // research 06: 6s, damage split over 3s
            Msg.send(u.player(), "&dCell Sync! &7Incoming hits are spread out for 6s.");
            return true;
        });
        for (String wid : List.of("healing", "mending", "restoration", "atonement")) {
            host.reg("wand_heal_" + wid, u -> {
                ItemDef def = u.itemDef();
                double hps = def.extra.has("healPerSecond") ? def.extra.get("healPerSecond").getAsDouble() : 60;
                int secs = def.extra.has("healSeconds") ? def.extra.get("healSeconds").getAsInt() : 5;
                Player p = u.player();
                long token = Buffs.now();
                buffs.flag(p, "wandheal", token, secs + 1);   // wand heals don't stack: a new cast replaces the old one
                new BukkitRunnable() {
                    int n;
                    @Override public void run() {
                        Double cur = buffs.flagValue(p, "wandheal");
                        if (!p.isOnline() || n++ >= secs || cur == null || cur != token) { cancel(); return; }
                        Sky.vitals().heal(p, hps);
                        Fx.burst(p.getLocation().add(0, 1.2, 0), Particle.HEART, 2, 0.3);
                    }
                }.runTaskTimer(Sky.plugin(), 0L, 20L);
                return true;
            });
        }

        // ---- dungeon weapons ------------------------------------------------------------------------
        host.reg("guided_bat", u -> {
            Player p = u.player();
            Missile m = missile(p, 0.9, Particle.SOUL, Material.BAT_SPAWN_EGG);
            m.guided = true;
            m.life = 80;
            m.onLand = at -> {
                for (LivingEntity e : Fx.mobsNear(at, 3)) Fx.magic(p, e, Abilities.dmg(u), Abilities.sc(u), Abilities.name(u));
                Fx.burst(at, Particle.EXPLOSION, 1, 0);
                Fx.sound(at, Sound.ENTITY_BAT_DEATH, 1f, 1f);
            };
            m.launch();
            return true;
        });
        host.reg("ice_spray", u -> {
            Player p = u.player();
            for (LivingEntity m : Fx.cone(p, WeaponDials.CONE_RANGE.get(), 45)) {
                Fx.magic(p, m, Abilities.dmg(u), Abilities.sc(u), Abilities.name(u));
                buffs.freeze(m, 5);   // research 06: frozen 5s, +10% damage taken
                buffs.mark(m, "frozen_amp", 1, 5);
            }
            Fx.beam(p.getEyeLocation(), p.getEyeLocation().getDirection(), WeaponDials.CONE_RANGE.get(), Particle.SNOWFLAKE);
            Fx.sound(p.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 1f, 0.8f);
            return true;
        });
        host.reg("showtime", u -> {
            Player p = u.player();
            Missile m = missile(p, 1.0, Particle.FIREWORK, Material.RED_WOOL);
            m.gravity = 0.02;
            m.onLand = at -> {
                for (LivingEntity e : Fx.mobsNear(at, 3.5)) Fx.magic(p, e, Abilities.dmg(u), Abilities.sc(u), Abilities.name(u));
                Fx.burst(at, Particle.EXPLOSION, 2, 0.5);
                Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
            };
            m.launch();
            return true;
        });
        host.reg("giants_slam", u -> {
            Player p = u.player();
            for (LivingEntity m : Fx.mobsNear(p.getLocation(), 6)) Fx.magic(p, m, Abilities.dmg(u), Abilities.sc(u), Abilities.name(u));
            Fx.burst(p.getLocation(), Particle.EXPLOSION_EMITTER, 1, 0);
            Fx.ring(p.getLocation(), 6, Particle.CLOUD, 50);
            Fx.sound(p.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f);
            return true;
        });
        host.reg("eye_beam", u -> {
            Player p = u.player();
            for (LivingEntity m : Fx.line(p.getEyeLocation(), p.getEyeLocation().getDirection(), 15, 1.0))
                Fx.magic(p, m, Abilities.dmg(u), Abilities.sc(u), Abilities.name(u));
            Fx.beam(p.getEyeLocation(), p.getEyeLocation().getDirection(), 15, Particle.ELECTRIC_SPARK);
            return true;
        });
        AbilityService.Handler skulls = u -> {
            Player p = u.player();
            int n = u.ability().id.endsWith("witherlord") ? 3 : 1;
            double[] yaws = WeaponMath.spread(n, 8);
            for (double yaw : yaws) {
                Location from = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.8));
                Missile m = new Missile(p, from, Bows.rotateYaw(p.getEyeLocation().getDirection(), yaw).multiply(1.4));
                m.particle = Particle.SMOKE;
                m.item(Material.WITHER_SKELETON_SKULL);
                m.onHit = (e, back) -> Fx.magic(p, e, Abilities.dmg(u), Abilities.sc(u), Abilities.name(u));
                m.launch();
            }
            Fx.sound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.7f, 1.2f);
            return true;
        };
        host.reg("dreadlord", skulls);
        host.reg("witherlord", skulls);
        host.reg("shadow_fury", u -> {
            Player p = u.player();
            List<LivingEntity> targets = new ArrayList<>(Fx.mobsNear(p.getLocation(), 12));   // research 06: 5 enemies, 12 blocks
            if (targets.isEmpty()) { Msg.error(p, "No enemies nearby!"); return false; }
            ItemStack weapon = u.item().clone();
            List<LivingEntity> pick = targets.subList(0, Math.min(5, targets.size()));
            for (int i = 0; i < pick.size(); i++) {
                LivingEntity m = pick.get(i);
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (!p.isOnline() || !m.isValid()) return;
                    Fx.behind(p, m);
                    buffs.freeze(m, 1);
                    Fx.melee(p, m, weapon, 1.0);
                    Fx.burst(m.getLocation().add(0, 1, 0), Particle.LARGE_SMOKE, 8, 0.3);
                }, i * 5L);
            }
            return true;
        });
        host.reg("shadowstep", u -> {
            Player p = u.player();
            LivingEntity t = Fx.looking(p, 20);   // research 06: 20 blocks, +25 Strength 10s
            if (t == null) { Msg.error(p, "Look at an enemy within 20 blocks!"); return false; }
            Fx.behind(p, t);
            buffs.buff(p, "shadowstep", Stat.STRENGTH, 25, 10);
            Fx.sound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
            return true;
        });
        host.reg("livid_throw", u -> {
            Player p = u.player();
            ItemStack weapon = u.item().clone();
            Missile m = missile(p, 1.6, Particle.CRIT, Material.IRON_SWORD);
            m.onHit = (e, back) -> Fx.melee(p, e, weapon, 1.0);
            m.launch();
            return true;
        });
        AbilityService.Handler reave = u -> {
            Player p = u.player();
            double mult = u.ability().id.endsWith("fel") ? 1.35 : 1.25;   // research 06: 125% / 135%
            passives.cleaving(true);
            try {
                for (LivingEntity m : Fx.cone(p, 5, 80)) Fx.melee(p, m, u.item(), mult);
            } finally {
                passives.cleaving(false);
            }
            Fx.ring(p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2)), 2.5, Particle.SWEEP_ATTACK, 8);
            Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
            return true;
        };
        host.reg("reaving_strike", reave);
        host.reg("reaving_strike_fel", reave);
        host.reg("bonemerang", u -> {
            Player p = u.player();
            Bows.Shot s = new Bows.Shot();
            s.shooter = p.getUniqueId();
            s.bowId = Traits.idOf(u.item());
            s.bow = u.item().clone();
            s.secondary = true;
            Missile m = missile(p, 1.0, Particle.CRIT, Material.BONE);
            m.returnAfter = 10;   // TUNE: "a short distance" read as 10 blocks
            m.pierce = 10;
            m.life = 50;
            m.onHit = (e, back) -> bows.hitWith(s, p, e, back ? 2.0 : 1.0);   // research 06: double on the way back
            m.launch();
            return true;
        });
        host.reg("creeper_veil", u -> {
            Player p = u.player();
            if (buffs.has(p, "veil")) {   // right click while active: drop it, half the cooldown
                buffs.clear(p, "veil");
                Msg.send(p, "&7Ward veil dropped.");
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> Sky.abilities().setCooldown(p, Abilities.PREFIX + "creeper_veil", 5));
                return true;
            }
            int layers = (int) Math.max(1, Math.floor(Sky.stats().get(p, Stat.VITALITY) / 30.0));   // research 06: 30 Vitality per layer
            buffs.flag(p, "veil", layers, 10);
            buffs.flag(p, "veil_until", Buffs.now() + 10_000, 10);
            Msg.send(p, "&aWard Veil! &7" + layers + " layers for 10s. You cannot attack while it holds.");
            Fx.dust(p.getLocation().add(0, 1, 0), Color.GREEN, 2f, 30, 0.6);
            return true;
        });
        AbilityService.Handler roses = u -> {
            Player p = u.player();
            boolean bouquet = u.ability().id.endsWith("petal_barrage");
            double cost = bouquet ? 250 : 100;   // research 06: health cost 250 / 100
            if (Sky.vitals().health(p) <= cost) { Msg.error(p, "You don't have enough health!"); return false; }
            double bonus = bouquet ? 1 + WeaponMath.petalBonus(Sky.vitals().health(p), Sky.vitals().maxHealth(p)) / 100.0 : 1;
            Sky.vitals().damage(p, cost, "thorns");
            int hops = bouquet ? 5 : 3;
            int volleys = bouquet ? 3 : 1;
            ItemStack weapon = u.item().clone();
            for (int v = 0; v < volleys; v++) {
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> Fx.healthCost(() -> chainRose(p, weapon, hops, bonus)), v * 4L);
            }
            return true;
        };
        host.reg("heat_seeking_rose", roses);
        host.reg("petal_barrage", roses);
    }

    private void chainRose(Player p, ItemStack weapon, int hops, double bonus) {
        if (!p.isOnline()) return;
        LivingEntity cur = Fx.looking(p, 15);
        if (cur == null) {
            List<LivingEntity> near = Fx.cone(p, 10, 40);
            if (near.isEmpty()) return;
            cur = near.get(0);
        }
        Set<UUID> done = new HashSet<>();
        Location from = p.getEyeLocation();
        for (int h = 0; h < hops && cur != null; h++) {
            done.add(cur.getUniqueId());
            Fx.beam(from, cur.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()),
                    from.distance(cur.getLocation()), Particle.DUST_PLUME);
            Fx.melee(p, cur, weapon, bonus * WeaponMath.hopMultiplier(h, WeaponDials.ROSE_HOP_BONUS.get()));
            from = cur.getLocation().add(0, 1, 0);
            LivingEntity next = null;
            for (LivingEntity m : Fx.mobsNear(cur.getLocation(), 8)) if (!done.contains(m.getUniqueId())) { next = m; break; }
            cur = next;
        }
        Fx.sound(p.getLocation(), Sound.BLOCK_AZALEA_LEAVES_BREAK, 1f, 1.4f);
    }

    private Missile missile(Player p, double speed, Particle particle, Material item) {
        Location from = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.8));
        Missile m = new Missile(p, from, p.getEyeLocation().getDirection().multiply(speed));
        m.particle = particle;
        if (item != null) m.item(item);
        return m;
    }
}
