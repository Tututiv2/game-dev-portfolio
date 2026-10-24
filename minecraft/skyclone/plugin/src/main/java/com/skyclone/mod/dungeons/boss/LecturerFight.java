package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.TombArena;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Floor III / Master III: the Lecturer (research 04 sec 3.13, The Professor). P1: four Guardians on pedestals -
 * Healthy (Giga Heal restores every guardian unless it is hit 10 times while it charges), Reinforced (TNT, Rogue
 * Guardians), Chaos (anger builds while you stand near it, then it discharges) and Laser (a 10-second focus beam of
 * 175 true damage a second, then it overheats). Anyone standing on a pedestal is body-slammed for 1,500. A guardian
 * that dies comes back after 10 s at 80% (90% on Master Mode) unless all four die inside that window.
 * P2: the bars drop and the Lecturer fights until 25%. P3: he absorbs his guardians - heals, lasers, TNT.
 */
public final class LecturerFight extends FightBase {

    public static final String NAME = "The Lecturer";

    static final class S {
        LivingEntity boss;
        int phase;                                  // 0 intro, 1 guardians, 2 himself, -3 transforming, 3 guardian form
        final Map<String, LivingEntity> guards = new LinkedHashMap<>();
        final Map<String, Long> deadAt = new HashMap<>();
        final Set<UUID> rogues = new HashSet<>();
        long healUntil = -1;
        int healHits;
        int anger;
        long laserUntil = -1, laserCool;
        Player laserTarget;
    }

    @Override public BossArena arena() { return TombArena.INSTANCE; }

    /** A full party's window to finish the other guardians after one falls (research: 10 s). */
    public static final double WINDOW_SECONDS = 10;
    /** The Laser Guardian's beam: true damage per second (research 175), and its first lock waits this long (steps). */
    public static final double LASER_TRUE = 175;
    public static final int LASER_FIRST_STEPS = 16;

    /**
     * wave-4 endgame: the window grows for smaller parties ({@code Balance.guardianWindowSeconds}): a party of five
     * splits across the four pedestals, a solo player kills them one after another and could never finish four
     * guardians inside 10 s.
     */
    static int windowSteps(BossContext c) {
        return (int) Math.round(com.skyclone.mod.dungeons.model.Balance.guardianWindowSeconds(Kit.members(c), WINDOW_SECONDS) * 4);
    }

    // TUNE: research gives none. Unscaled: spawnExact applies the boss-HP dial itself (review fix - it was applied twice).
    private static double guardHp(BossContext c, String k) { return c.floor().bossHp * (k.equals("reinforced") ? 0.5 : 0.3); }

    private void spawnGuard(BossContext c, S s, String k, double frac) {
        LivingEntity g = c.spawnExact("dboss_guardian_" + k, c.point("guardian." + k), guardHp(c, k), Kit.nm(c, 1_500, 30_000));
        if (g == null) return;
        if (frac < 1) Sky.mobs().setHealth(g, Kit.max(g) * frac);
        g.setGravity(false);
        s.guards.put(k, g);
        s.deadAt.remove(k);
        Fx.particle(g.getLocation(), Particle.BUBBLE_POP, 40, 1, 0.1);
    }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        s.boss = spawnBoss(c, "dboss_lecturer", c.point("boss"), bossHp(c), bossDmg(c));
        if (s.boss == null) { c.complete(); return; }
        Kit.freeze(s.boss, true);
        Kit.announce(c, "&3&lTHE LECTURER", "&7" + c.floor().display(), Sound.ENTITY_ELDER_GUARDIAN_CURSE);
        Kit.dialogue(c, NAME, List.of("Ah, latecomers. Do take a seat. Permanently.",
                "Allow me to introduce my four teaching assistants."), () -> {
            for (String k : TombArena.GUARDIANS) spawnGuard(c, s, k, 1);
            s.phase = 1;
            s.laserCool = c.steps() + LASER_FIRST_STEPS;
            c.title("&3The Guardians wake", "&7Kill all four within &e" + windowSteps(c) / 4 + "s &7of each other!");
        });
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        if (s.boss == null || !Kit.alive(s.boss)) return;
        long t = c.steps();
        if (s.phase == 1) guardians(c, s, t);
        else if (s.phase == 2 || s.phase == 3) professor(c, s, t);
    }

    private void guardians(BossContext c, S s, long t) {
        int alive = 0;
        for (LivingEntity g : s.guards.values()) if (Kit.alive(g)) alive++;
        c.bossBar("&3&l" + NAME + " &7- &bGuardians: &a" + alive + "&7/4", Math.max(0.05f, alive / 4f), BossBar.Color.BLUE);
        // body slam on the pedestals
        if (t % 8 == 0) for (Map.Entry<String, LivingEntity> en : s.guards.entrySet()) {
            LivingEntity g = en.getValue();
            if (!Kit.alive(g)) continue;
            Location base = c.point("guardian." + en.getKey());
            for (Player p : c.alive())
                if (Kit.flat(p.getLocation(), base) < 3.5 && Math.abs(p.getLocation().getY() - (base.getY() - 2)) < 2.5) {
                    Kit.hit(p, Kit.nm(c, 1_500, 30_000));
                    Kit.knock(p, base, 1.2, 0.5);
                    Sky.vitals().flash(p, "&cGet off the pedestal!", 30);
                }
        }
        LivingEntity healthy = s.guards.get("healthy"), reinforced = s.guards.get("reinforced"), chaos = s.guards.get("chaos"), laser = s.guards.get("laser");
        // Healthy: Giga Heal
        if (Kit.alive(healthy)) {
            if (s.healUntil < 0 && t % 80 == 40) {
                s.healUntil = t + 24;
                s.healHits = 0;
                c.title("&aGIGA HEAL", "&7Hit the Healthy Guardian &e10 &7times!");
                Kit.glow(healthy, true);
            }
            if (s.healUntil >= 0) {
                Fx.particle(healthy.getLocation().add(0, 0.5, 0), Particle.HEART, 3, 0.6, 0);
                if (t >= s.healUntil) {
                    s.healUntil = -1;
                    Kit.glow(healthy, false);
                    if (s.healHits < 10) {
                        for (LivingEntity g : s.guards.values()) if (Kit.alive(g)) healFull(g);
                        c.broadcast("&aThe Healthy Guardian restored every guardian! &7(" + s.healHits + "/10 hits)");
                        Kit.sound(c, Sound.ENTITY_PLAYER_LEVELUP, 0.6f);
                    } else c.broadcast("&eGiga Heal interrupted!");
                }
            }
        }
        // Reinforced: TNT and rogue guardians
        if (Kit.alive(reinforced)) {
            if (t % 28 == 14) {
                Player p = pick(c, rng);
                if (p != null) Kit.circle(c, p.getLocation(), 4, 40, Color.RED, q -> Kit.hit(q, bossDmg(c) * 0.5),
                        () -> Fx.particle(p.getLocation(), Particle.EXPLOSION_EMITTER, 1, 0, 0));
            }
            if (t % 60 == 30) {
                s.rogues.removeIf(u -> { org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(u); return e == null || !e.isValid(); });
                if (s.rogues.size() < 3) {
                    boolean elder = s.rogues.size() == 2;
                    LivingEntity r = c.spawnExact(elder ? "dboss_elder_guardian" : "dboss_rogue_guardian", reinforced.getLocation().add(0, -2, 3),
                            Kit.nm(c, elder ? 400_000 : 120_000, elder ? 80_000_000 : 24_000_000), Kit.nm(c, elder ? 1_600 : 900, elder ? 32_000 : 18_000));
                    if (r != null) s.rogues.add(r.getUniqueId());
                }
            }
        }
        // Chaos: anger builds from players standing near it
        if (Kit.alive(chaos) && t % 4 == 0) {
            s.anger += Kit.within(c, chaos.getLocation(), 7).size();
            if (s.anger >= 10) {
                s.anger = 0;
                Fx.particle(chaos.getLocation(), Particle.SONIC_BOOM, 1, 0, 0);
                Kit.sound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f);
                for (Player p : Kit.within(c, chaos.getLocation(), 9)) { Kit.hit(p, bossDmg(c) * 0.6); Kit.knock(p, chaos.getLocation(), 1.4, 0.6); }
            } else if (s.anger >= 7) Fx.particle(chaos.getLocation(), Particle.ANGRY_VILLAGER, 3, 0.8, 0);
        }
        // Laser: a 10-second focus beam, then a 5-second overheat
        if (Kit.alive(laser)) {
            if (s.laserUntil < 0 && t >= s.laserCool) {
                s.laserTarget = pick(c, rng);
                if (s.laserTarget != null) { s.laserUntil = t + 40; c.broadcast("&cThe Laser Guardian locks on to &b" + s.laserTarget.getName() + "&c!"); }
            }
            if (s.laserUntil >= 0) {
                Player p = s.laserTarget;
                if (p == null || !p.isOnline() || c.isGhost(p) || t >= s.laserUntil) { s.laserUntil = -1; s.laserCool = t + 20; }
                else if (t % 4 == 0) {
                    // wave-4 endgame: the beam needs a line of sight (pillars and pedestals are cover, as they are for
                    // every bolt); unblockable it was 175 true a second for 10 s against a solo 983 HP player
                    boolean seen = laser.hasLineOfSight(p);
                    Fx.line(laser.getEyeLocation(), p.getEyeLocation(), seen ? Color.fromRGB(255, 60, 60) : Color.fromRGB(90, 90, 90), 0.4);
                    if (seen) Kit.hitTrue(p, Kit.nm(c, LASER_TRUE, 3_500));
                }
            }
        }
        // respawns: anything dead for 10 s comes back unless all four are down
        boolean allDead = true;
        for (String k : TombArena.GUARDIANS) if (Kit.alive(s.guards.get(k))) allDead = false;
        if (allDead && s.guards.size() == 4) { secondPhase(c, s); return; }
        for (String k : TombArena.GUARDIANS) {
            Long d = s.deadAt.get(k);
            if (d != null && t - d >= windowSteps(c)) {
                spawnGuard(c, s, k, c.master() ? 0.9 : 0.8);
                c.broadcast("&3The &b" + Character.toUpperCase(k.charAt(0)) + k.substring(1) + " Guardian &3returns!");
            }
        }
    }

    private void secondPhase(BossContext c, S s) {
        s.phase = 2;
        fillBox(c, "barrier.a", "barrier.b", Material.AIR);
        Kit.announce(c, "&3&lCLASS DISMISSED?", "&7The Lecturer steps down.", Sound.ENTITY_ELDER_GUARDIAN_AMBIENT);
        c.bossSay(NAME, "You broke my assistants. I shall have to teach this one myself.");
        Location drop = c.point("drop");
        if (drop != null) s.boss.teleport(drop);
        Kit.freeze(s.boss, false);
    }

    private void professor(BossContext c, S s, long t) {
        Kit.bar(c, NAME, s.boss, s.phase == 3 ? "&3Guardian form" : "&7Lecturing", s.phase == 3 ? BossBar.Color.BLUE : BossBar.Color.RED);
        if (t % 16 == 0) {
            Player p = pick(c, rng);
            if (p != null) Kit.bolt(c, s.boss.getEyeLocation(), p.getEyeLocation(), 1.1, 40, 1.2, Particle.ENCHANT, Color.fromRGB(80, 200, 200),
                    q -> Kit.hit(q, bossDmg(c) * 0.5));
        }
        if (s.phase == 2 && Kit.frac(s.boss) <= 0.25) {
            s.phase = -3;
            Kit.freeze(s.boss, true);
            Kit.announce(c, "&3&lGUARDIAN FORM", "&7He absorbs his guardians!", Sound.ENTITY_ELDER_GUARDIAN_CURSE);
            c.bossSay(NAME, "If you want the answers, you will have to go through ALL of us at once!");
            for (Player p : c.players()) Kit.effect(p, org.bukkit.potion.PotionEffectType.MINING_FATIGUE, 60, 1);
            Kit.later(c, 60, () -> { Kit.scale(s.boss, 1.6); Kit.freeze(s.boss, false); s.phase = 3; });
            return;
        }
        if (s.phase != 3) return;
        if (t % 40 == 0) Sky.mobs().setHealth(s.boss, Math.min(Kit.max(s.boss), Kit.hp(s.boss) + Kit.max(s.boss) * 0.02));
        if (t % 28 == 14) {
            Player p = pick(c, rng);
            if (p != null) Kit.circle(c, p.getLocation(), 4, 36, Color.RED, q -> Kit.hit(q, bossDmg(c) * 0.6), null);
        }
        if (t % 24 == 0) {
            Player p = pick(c, rng);
            if (p != null) Kit.beam(c, s.boss.getEyeLocation(), p.getEyeLocation(), 30, Color.fromRGB(255, 60, 60), q -> Kit.hitTrue(q, Kit.nm(c, 400, 8_000)));
        }
    }

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        if (is(s.boss, dc.victim)) {
            if (s.phase == 1 || s.phase == 0) Kit.immune(dc, "Defeat his four guardians first!");
            else if (s.phase == -3) Kit.immune(dc, "He is transforming!");
            return;
        }
        if (s.healUntil >= 0 && dc.victim.equals(s.guards.get("healthy")) && dc.playerAttacker() != null) {
            s.healHits++;
            if (s.healHits == 10) c.title("&eGiga Heal interrupted!", "");
        }
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (is(s.boss, mob)) { win(c, NAME, "I... have nothing more... to teach..."); return; }
        for (Map.Entry<String, LivingEntity> en : s.guards.entrySet())
            if (mob.equals(en.getValue())) {
                s.deadAt.put(en.getKey(), c.steps());
                c.broadcast("&3The &b" + Character.toUpperCase(en.getKey().charAt(0)) + en.getKey().substring(1) + " Guardian &3falls! &7(" + windowSteps(c) / 4 + "s to finish the others)");
            }
        s.rogues.remove(mob.getUniqueId());
    }
}
