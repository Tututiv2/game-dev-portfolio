package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.CircusArena;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Floor I / Master I: Grinko (research 04 sec 3.13, Bonzo). P1: he chases, throws dark-orb skulls (1,598 on F1) and
 * keeps raising Circus Undead (45,000 HP; 15M on M1). When his first life runs out he refuses to die: three seconds
 * of immunity, full health, faster. P2: balloons, and every ten seconds the Balloon Barrage - he plants himself in the
 * ring's centre and fires balloons in every direction. The seven pillars stop balloons: hide behind one.
 */
public final class GrinkoFight extends FightBase {

    public static final String NAME = "Grinko";

    /** Hit sizes, public so model.Balance's test checks the numbers the fight actually swings (TUNE). */
    public static final double SKULL = 0.6, SKULL_P2 = SKULL * 1.4, BARRAGE = SKULL * 1.6;   // x floor boss damage
    /** Steps (4 a second) between dark-orb skulls. */
    public static final int SKULL_EVERY = 16;
    public static final double UNDEAD_HP = 45_000, UNDEAD_HP_M = 15_000_000, UNDEAD_DMG = 1_200, UNDEAD_DMG_M = 20_000;

    static final class S {
        LivingEntity boss;
        int phase;          // 0 intro, 1 first life, -1 reviving, 2 second life
        final Set<UUID> undead = new HashSet<>();
        long barrageAt = -1;
    }

    @Override public BossArena arena() { return CircusArena.INSTANCE; }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        Kit.announce(c, "&c&lGRINKO", "&7" + c.floor().display(), Sound.ENTITY_EVOKER_PREPARE_SUMMON);
        Kit.dialogue(c, NAME, List.of("Gather round, gather round! Tonight's act: the last show of your lives!",
                "My parents wanted a necromancer. The circus wanted a clown. You get BOTH!"), () -> {
            s.boss = spawnBoss(c, "dboss_grinko", c.point("boss"), bossHp(c), bossDmg(c));
            if (s.boss == null) { c.complete(); return; }
            s.phase = 1;
            Kit.sound(c, Sound.ENTITY_WITHER_SPAWN, 1.4f);
        });
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        if (s.boss == null || !Kit.alive(s.boss)) return;
        long t = c.steps();
        String ph = s.phase == 2 ? "&cSecond life" : s.phase == -1 ? "&eReviving..." : "&7First life";
        Kit.bar(c, NAME, s.boss, ph, s.phase == 2 ? BossBar.Color.PURPLE : BossBar.Color.RED);
        if (s.phase == -1) return;
        double skull = bossDmg(c) * SKULL;                            // research: 1,598 of 2,664 on F1
        if (t % SKULL_EVERY == 0) {
            Player p = pick(c, rng);
            if (p != null) Kit.bolt(c, s.boss.getEyeLocation(), p.getEyeLocation(), 1.1, 40, 1.1, Particle.SMOKE, Color.fromRGB(40, 20, 60),
                    q -> Kit.hit(q, skull));
        }
        if (s.phase == 1 && t % (c.master() ? 20 : 32) == 0) {
            s.undead.removeIf(u -> { org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(u); return e == null || !e.isValid(); });
            if (s.undead.size() < undeadCap(c.master(), Kit.members(c))) {
                Location at = undeadSpot(c, s.boss.getLocation());
                LivingEntity u = c.spawnExact("dboss_grinko_undead", at, Kit.nm(c, UNDEAD_HP, UNDEAD_HP_M), Kit.nm(c, UNDEAD_DMG, UNDEAD_DMG_M));
                if (u != null) { s.undead.add(u.getUniqueId()); Fx.particle(at, Particle.SOUL, 20, 0.5, 0.05); }
                c.bossSay(NAME, rng.nextBoolean() ? "Up you get, my friend!" : "Another volunteer from the audience!");
            }
        }
        if (s.phase == 2) {
            if (t % 12 == 6) {
                Player p = pick(c, rng);
                if (p != null) Kit.bolt(c, s.boss.getEyeLocation(), p.getEyeLocation(), 0.9, 40, 1.3, Particle.HAPPY_VILLAGER, Color.fromRGB(230, 40, 40),
                        q -> Kit.hit(q, bossDmg(c) * SKULL_P2));
            }
            if (s.barrageAt < 0 && t % 40 == 0) {
                // the Balloon Barrage: to the centre, a two-second wind-up, balloons in sixteen directions
                Location centre = c.point("centre");
                s.boss.teleport(centre);
                Kit.freeze(s.boss, true);
                s.barrageAt = t + 8;
                c.title("&c⭕ BALLOON BARRAGE", "&7Hide behind a pillar!");
                Kit.sound(c, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.6f);
            }
            if (s.barrageAt >= 0 && t >= s.barrageAt) {
                s.barrageAt = -1;
                Location from = s.boss.getLocation().add(0, 1.4, 0);
                int n = c.master() ? 24 : 16;
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    double pitch = c.master() ? (rng.nextDouble() * 0.6) : (rng.nextDouble() * 0.25);
                    Location to = from.clone().add(Math.cos(a) * 10, pitch * 10, Math.sin(a) * 10);
                    Kit.bolt(c, from, to, 0.8, 40, 1.4, Particle.HAPPY_VILLAGER, i % 2 == 0 ? Color.RED : Color.WHITE, q -> Kit.hit(q, bossDmg(c) * BARRAGE));
                }
                Kit.later(c, 20, () -> Kit.freeze(s.boss, false));
            }
        }
    }

    /** Radius of the ring of spots the Circus Undead rise on: outside the pillars (17 + 1.6), inside the benches (25). */
    public static final double UNDEAD_RING = 21;

    /**
     * Circus Undead standing at once: 4 (6 on Master Mode) for a full party, scaled by the run's members and never under
     * 2 (wave-4 endgame: four of them on one solo player was 570 a second after Defense).
     */
    public static int undeadCap(boolean master, int members) {
        int full = master ? 6 : 4;
        int n = Math.max(1, Math.min(com.skyclone.mod.dungeons.model.Balance.FULL_PARTY, members));
        return Math.max(2, (int) Math.ceil(full * n / (double) com.skyclone.mod.dungeons.model.Balance.FULL_PARTY));
    }

    /**
     * wave-4 endgame: the undead used to rise within 3 blocks of Grinko, who chases the party, so they rose on top of
     * whoever had just arrived (melee died in 8 s). They now climb out of the sawdust on a ring between the pillars and
     * the benches, at the one of 14 spots farthest from every living player and never within 10 blocks of the arrival
     * point. Falls back to beside Grinko if the arena points are missing.
     */
    private Location undeadSpot(BossContext c, Location fallback) {
        Location centre = c.point("centre"), arrival = c.point("spawn");
        if (centre == null) return fallback.clone().add(rng.nextInt(7) - 3, 0, rng.nextInt(7) - 3);
        Location best = null;
        double bestD = -1;
        for (int k = 0; k < 14; k++) {
            double a = Math.PI * 2 * k / 14 - Math.PI / 2;
            Location at = centre.clone().add(Math.cos(a) * UNDEAD_RING, 0, Math.sin(a) * UNDEAD_RING);
            if (arrival != null && Kit.flat(at, arrival) < 10) continue;
            double d = Double.MAX_VALUE;
            for (Player p : c.alive()) if (p.getWorld().equals(at.getWorld())) d = Math.min(d, Kit.flat(at, p.getLocation()));
            d += rng.nextDouble();                                      // ties (no one in the arena yet) vary
            if (d > bestD) { bestD = d; best = at; }
        }
        return best == null ? fallback.clone() : best;
    }

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        if (!is(s.boss, dc.victim)) return;
        if (s.phase == -1) { Kit.immune(dc, "Grinko is getting back up!"); return; }
        if (s.phase == 1 && dc.finalDamage >= Kit.hp(s.boss)) {
            dc.finalDamage = Math.max(0, Kit.hp(s.boss) - 1);
            s.phase = -1;
            Kit.freeze(s.boss, true);
            Kit.announce(c, "&c&lGRINKO REVIVES!", "&7His mask will not let him die.", Sound.ENTITY_EVOKER_CAST_SPELL);
            c.bossSay(NAME, "Ha! You thought that was the finale? That was the INTERVAL!");
            Fx.particle(s.boss.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 80, 0.6, 0.4);
            Kit.later(c, 60, () -> {
                healFull(s.boss);
                Kit.freeze(s.boss, false);
                Kit.speed(s.boss, 1.25);
                Kit.scale(s.boss, 1.25);
                s.phase = 2;
                c.bossSay(NAME, "Now for the balloons!");
            });
        }
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (is(s.boss, mob)) win(c, NAME, "The show... must... go... on...");
        else s.undead.remove(mob.getUniqueId());
    }

    @Override
    public void onPlayerDeath(BossContext c, Player p) {
        if (Sky.profiles().isReady(p)) c.bossSay(NAME, "And for my next trick, " + p.getName() + " disappears!");
    }
}
