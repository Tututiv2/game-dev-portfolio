package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.CourtArena;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floor II / Master II: Veilwright (research 04 sec 3.13, Scarf). P1: he watches from his barred balcony while his
 * four students fight - Warrior (charges), Archer (arrows), Mage (fire circles) and Priest (heals the others and
 * raises a fallen one: kill it first). Research HP 500k/400k/400k/600k (75M/60M/60M/90M on M2). On Master Mode they
 * come in pairs and he stays immune until four have fallen. P2: the bars come down and he fights: Skull Barrage and
 * the Soul Sand Blizzard (true damage and Slowness around him); at half health he raises two students once more.
 */
public final class VeilwrightFight extends FightBase {

    public static final String NAME = "Veilwright";
    static final String[] KIND = {"warrior", "archer", "mage", "priest"};

    /** Student attack cadences in boss steps (4 a second) and sizes (x the student's damage); BalanceTest reads them. */
    public static final int CHARGE_EVERY = 24, ARROW_EVERY = 12, FIRE_EVERY = 20;
    public static final double CHARGE_FACTOR = 1.5, FIRE_FACTOR = 1.5;

    static final class S {
        LivingEntity boss;
        int phase;                                   // 0 intro, 1 students, 2 himself
        final Map<LivingEntity, String> students = new LinkedHashMap<>();
        final Map<String, Long> fallenAt = new LinkedHashMap<>();
        final List<String> raised = new ArrayList<>();
        int killed;
        boolean raisedAtHalf;
    }

    @Override public BossArena arena() { return CourtArena.INSTANCE; }

    static double hp(BossContext c, String k) {
        return switch (k) {
            case "warrior" -> Kit.nm(c, 500_000, 75_000_000);
            case "priest" -> Kit.nm(c, 600_000, 90_000_000);
            default -> Kit.nm(c, 400_000, 60_000_000);
        };
    }

    static double dmg(BossContext c, String k) {
        return switch (k) {
            case "warrior" -> Kit.nm(c, 2_880, 54_000);
            case "archer" -> Kit.nm(c, 2_752, 51_600);
            case "mage" -> Kit.nm(c, 2_432, 45_600);
            default -> Kit.nm(c, 1_920, 36_000);
        };
    }

    private void student(BossContext c, S s, String k, Location at, double hpFrac) {
        LivingEntity e = c.spawnExact("dboss_veil_" + k, at, hp(c, k), dmg(c, k));
        if (e == null) return;
        if (hpFrac < 1) com.skyclone.api.Sky.mobs().setHealth(e, Kit.max(e) * hpFrac);
        s.students.put(e, k);
        Fx.particle(at.clone().add(0, 1, 0), Particle.SOUL_FIRE_FLAME, 30, 0.5, 0.05);
    }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        s.boss = spawnBoss(c, "dboss_veilwright", c.point("boss"), bossHp(c), bossDmg(c));
        if (s.boss == null) { c.complete(); return; }
        Kit.freeze(s.boss, true);
        Kit.announce(c, "&c&lVEILWRIGHT", "&7" + c.floor().display(), Sound.ENTITY_WITHER_AMBIENT);
        Kit.dialogue(c, NAME, List.of("Class is in session. Today's lesson: how to die quietly.",
                "My students will be marking your work. Harshly."), () -> {
            for (int i = 0; i < 4; i++) {
                Location at = c.point("minion." + i);
                student(c, s, KIND[i], at, 1);
                if (c.master()) student(c, s, KIND[i], at.clone().add(1.5, 0, 0), 1);
            }
            s.phase = 1;
            c.title("&eHis students attack!", "&7Kill the &6Priest &7first.");
        });
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        if (s.boss == null || !Kit.alive(s.boss)) return;
        long t = c.steps();
        if (s.phase == 1) {
            s.students.keySet().removeIf(e -> !e.isValid() || e.isDead());
            c.bossBar("&c&l" + NAME + " &7- &eStudents left: &c" + s.students.size(), Math.max(0.05f, s.students.size() / (c.master() ? 8f : 4f)), BossBar.Color.YELLOW);
            for (Map.Entry<LivingEntity, String> en : new ArrayList<>(s.students.entrySet())) studentAct(c, s, en.getKey(), en.getValue(), t);
            if (t % 60 == 30) c.bossSay(NAME, List.of("Sloppy footwork.", "I have seen better form from a skeleton. I have taught better skeletons.",
                    "Detention for all of you.").get(rng.nextInt(3)));
            // a fallen student rises again while the Priest lives (once each)
            boolean priest = s.students.containsValue("priest");
            for (Map.Entry<String, Long> f : new ArrayList<>(s.fallenAt.entrySet())) {
                if (priest && !s.raised.contains(f.getKey()) && t - f.getValue() >= 40) {
                    s.raised.add(f.getKey());
                    s.fallenAt.remove(f.getKey());
                    student(c, s, f.getKey(), c.point("minion." + indexOf(f.getKey())), 0.5);
                    c.broadcast("&eThe &6Priest &eraises the &c" + cap(f.getKey()) + "&e!");
                }
            }
            boolean enough = !c.master() || s.killed >= 4;
            if (s.students.isEmpty() && enough) secondPhase(c, s);
            return;
        }
        if (s.phase != 2) return;
        Kit.bar(c, NAME, s.boss, "&cExamination", BossBar.Color.RED);
        for (Map.Entry<LivingEntity, String> en : new ArrayList<>(s.students.entrySet()))
            if (en.getKey().isValid()) studentAct(c, s, en.getKey(), en.getValue(), t);
        if (t % 24 == 0) {
            // Skull Barrage: five skulls, each bursting where it lands
            for (int i = 0; i < 5; i++) {
                Player p = pick(c, rng);
                if (p == null) break;
                Kit.later(c, i * 3, () -> Kit.bolt(c, s.boss.getEyeLocation(), p.getEyeLocation(), 1.0, 40, 1.4, Particle.SMOKE, Color.fromRGB(60, 60, 60),
                        q -> { Kit.hit(q, bossDmg(c) * 0.5); Fx.particle(q.getLocation(), Particle.EXPLOSION, 1, 0, 0); }));
            }
        }
        if (t % 48 == 24) {
            c.title("&6SOUL SAND BLIZZARD", "&7Get away from Veilwright!");
            Kit.freeze(s.boss, true);
            Kit.circle(c, s.boss.getLocation(), 8, 50, Color.fromRGB(110, 80, 50), q -> {
                Kit.pct(q, 0.10);
                Kit.effect(q, PotionEffectType.SLOWNESS, 60, 2);
            }, () -> { Kit.freeze(s.boss, false); Fx.particle(s.boss.getLocation(), Particle.CAMPFIRE_COSY_SMOKE, 60, 4, 0.02); });
        }
        if (!s.raisedAtHalf && Kit.frac(s.boss) < 0.5) {
            s.raisedAtHalf = true;
            c.bossSay(NAME, "Rise, my students! Your grades depend on it!");
            student(c, s, KIND[rng.nextInt(3)], c.point("minion.0"), 1);
            student(c, s, KIND[rng.nextInt(3)], c.point("minion.3"), 1);
        }
    }

    private static int indexOf(String k) { for (int i = 0; i < KIND.length; i++) if (KIND[i].equals(k)) return i; return 0; }

    private static String cap(String k) { return Character.toUpperCase(k.charAt(0)) + k.substring(1); }

    private void studentAct(BossContext c, S s, LivingEntity e, String k, long t) {
        if (!Kit.alive(e)) return;
        switch (k) {
            case "warrior" -> {
                if (t % CHARGE_EVERY != 0) return;
                Player p = Kit.nearest(c, e.getLocation());
                if (p == null) return;
                Vector v = p.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
                if (v.lengthSquared() < 0.1) return;
                e.setVelocity(v.normalize().multiply(1.6).setY(0.3));
                Fx.particle(e.getLocation(), Particle.CLOUD, 10, 0.3, 0.05);
                Kit.later(c, 10, () -> { if (Kit.alive(e)) for (Player q : Kit.within(c, e.getLocation(), 2.8)) Kit.hit(q, dmg(c, k) * CHARGE_FACTOR); });
            }
            case "archer" -> {
                if (t % ARROW_EVERY != ARROW_EVERY / 2) return;
                Player p = Kit.nearest(c, e.getLocation());
                if (p != null) Kit.bolt(c, e.getEyeLocation(), p.getEyeLocation(), 1.4, 45, 1.0, Particle.CRIT, null, q -> Kit.hit(q, dmg(c, k)));
            }
            case "mage" -> {
                if (t % FIRE_EVERY != FIRE_EVERY / 2) return;
                Player p = pick(c, rng);
                if (p != null) Kit.circle(c, p.getLocation(), 3, 30, Color.ORANGE, q -> Kit.hit(q, dmg(c, k) * FIRE_FACTOR),
                        () -> Fx.particle(p.getLocation(), Particle.FLAME, 40, 1.5, 0.05));
            }
            case "priest" -> {
                if (t % 16 != 8) return;
                for (LivingEntity o : s.students.keySet()) {
                    if (!Kit.alive(o) || o.equals(e)) continue;
                    com.skyclone.api.Sky.mobs().setHealth(o, Math.min(Kit.max(o), Kit.hp(o) + Kit.max(o) * 0.05));
                    Fx.line(e.getEyeLocation(), o.getEyeLocation(), Color.fromRGB(255, 230, 120), 0.6);
                }
            }
            default -> { }
        }
    }

    private void secondPhase(BossContext c, S s) {
        s.phase = 2;
        fillBox(c, "barrier.a", "barrier.b", Material.AIR);
        Kit.announce(c, "&c&lVEILWRIGHT DESCENDS", "&7The bars are down.", Sound.ENTITY_WITHER_BREAK_BLOCK);
        c.bossSay(NAME, "Fine. If you want something done properly...");
        Location drop = c.point("drop");
        if (drop != null) s.boss.teleport(drop);
        Kit.freeze(s.boss, false);
    }

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        if (is(s.boss, dc.victim) && s.phase != 2) Kit.immune(dc, c.master() ? "Defeat four of his students first!" : "Defeat his students first!");
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (is(s.boss, mob)) { win(c, NAME, "Class... dismissed..."); return; }
        String k = s.students.remove(mob);
        if (k != null) {
            s.killed++;
            if (s.phase == 1) s.fallenAt.put(k, c.steps());
            if (k.equals("priest")) c.broadcast("&aThe Priest is down: the students can no longer be healed!");
        }
    }
}
