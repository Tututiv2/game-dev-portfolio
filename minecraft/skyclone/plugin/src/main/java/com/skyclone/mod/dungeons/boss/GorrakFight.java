package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.ColossusArena;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Floor VI / Master VI: Gorrak (research 04 sec 3.13, Sadan). P1: two minutes of Terracottas growing out of the
 * planters; every kill takes a second off the clock and the planter regrows its terracotta 10 s later. P2: four giants
 * wake in their alcoves - Sparkling (throws its sword along a line), Beam (a laser whose damage ramps while it stays
 * on you), Strider (Stomp, a wide ring) and Rosy (lobs boulders). P3: Gorrak climbs down from his throne as the Giant
 * One and uses all four kits at once. Research HP: Gorrak 40M (800M), giants ~25M each (TUNE; 600M on M6).
 */
public final class GorrakFight extends FightBase {

    public static final String NAME = "Gorrak";

    static final class S {
        LivingEntity boss;
        int phase;                                      // 0 intro, 1 terracottas, 2 giants, 3 the Giant One
        long endAt;
        final Map<UUID, Integer> terra = new HashMap<>();
        final Map<Integer, Long> regrow = new HashMap<>();
        final Map<String, LivingEntity> giants = new LinkedHashMap<>();
        UUID laserOn;
        int laserRamp;
        /** M6 phase 3: when each slain Mutant Giant rises again at its pillar (steps). */
        final Map<String, Long> mutantRise = new HashMap<>();
    }

    /** M6 (wave 5, research 04 sec 3.13): the giants are Mutant Giants with all four kits; they rise again beside Gorrak. */
    static String mutantName(String k) {
        return switch (k) {
            case "diamond" -> "Mutant Sparkling Giant";
            case "laser" -> "Mutant Beam Giant";
            case "bigfoot" -> "Mutant Strider Giant";
            default -> "Mutant Rosy Giant";
        };
    }

    private LivingEntity spawnGiant(BossContext c, String k) {
        double dmg = switch (k) { case "bigfoot" -> 10_000; case "laser" -> 8_000; default -> 9_000; };
        LivingEntity g = c.spawnExact("dboss_giant_" + k, c.point("giant." + k),
                c.master() ? DDials.MUTANT_GIANT_HP.get() : 25_000_000, Kit.nm(c, dmg, dmg * 20));
        if (g != null && c.master()) try { Sky.mobs().setDisplayName(g, "&4" + mutantName(k)); } catch (RuntimeException ignored) { }
        return g;
    }

    /** A giant's attacks: its own kit; on Master Mode a Mutant Giant uses all four (the laser stays the Beam Giant's). */
    private void giantKits(BossContext c, S s, String k, LivingEntity g, long t, double mul) {
        if (!c.master()) { kit(c, s, k, g, t, mul); return; }
        for (String kk : ColossusArena.GIANTS) if (!kk.equals("laser") || k.equals("laser")) kit(c, s, kk, g, t, mul);
    }

    @Override public BossArena arena() { return ColossusArena.INSTANCE; }

    private void terracotta(BossContext c, S s, int pot) {
        Location at = c.point("pot." + pot);
        if (at == null) return;
        boolean archer = pot % 3 == 2;
        LivingEntity e = c.spawnExact(archer ? "dboss_terracotta_archer" : "dboss_terracotta", at,
                Kit.nm(c, archer ? 220_000 : 300_000, archer ? 22_000_000 : 30_000_000), Kit.nm(c, archer ? 2_200 : 2_500, archer ? 44_000 : 50_000));
        if (e != null) { s.terra.put(e.getUniqueId(), pot); Fx.particle(at, Particle.BLOCK, 30, 0.4, 0.1, org.bukkit.Material.TERRACOTTA); }
    }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        s.boss = spawnBoss(c, "dboss_gorrak", c.point("boss"), bossHp(c), bossDmg(c));
        if (s.boss == null) { c.complete(); return; }
        Kit.announce(c, "&6&lGORRAK", "&7" + c.floor().display(), Sound.ENTITY_IRON_GOLEM_REPAIR);
        Kit.dialogue(c, NAME, List.of("So you have come to see my collection. Every piece hand-sculpted.",
                "Every piece... ALIVE. Children, greet our guests!"), () -> {
            int pots = c.master() ? 12 : 8;
            for (int i = 0; i < pots; i++) terracotta(c, s, i);
            s.phase = 1;
            s.endAt = c.steps() + DDials.SADAN_TERRACOTTA_SECONDS.get() * 4L;
        });
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        if (s.boss == null || !Kit.alive(s.boss)) return;
        long t = c.steps();
        if (s.phase == 1) {
            long left = s.endAt - t;
            c.bossBar("&6&l" + NAME + " &7- &eTerracottas &7- &a" + time(left), Math.max(0.02f, left / (DDials.SADAN_TERRACOTTA_SECONDS.get() * 4f)), BossBar.Color.YELLOW);
            for (Map.Entry<Integer, Long> r : new ArrayList<>(s.regrow.entrySet()))
                if (t >= r.getValue()) { s.regrow.remove(r.getKey()); terracotta(c, s, r.getKey()); }
            if (left <= 0) giants(c, s);
            return;
        }
        if (s.phase == 2) {
            int alive = 0;
            for (LivingEntity g : s.giants.values()) if (Kit.alive(g)) alive++;
            c.bossBar("&6&l" + NAME + " &7- &cGiants awake: &e" + alive + "&7/4", Math.max(0.02f, alive / 4f), BossBar.Color.RED);
            for (Map.Entry<String, LivingEntity> g : s.giants.entrySet()) if (Kit.alive(g.getValue())) giantKits(c, s, g.getKey(), g.getValue(), t, 1);
            if (alive == 0) theGiantOne(c, s);
            return;
        }
        if (s.phase == 3) {
            Kit.bar(c, "The Giant One", s.boss, c.master() ? "&4Mutant Giants at his side" : "&6All four kits", BossBar.Color.RED);
            for (String k : ColossusArena.GIANTS) kit(c, s, k, s.boss, t, 1.2);
            if (c.master()) {
                // M6: the Mutant Giants stand again at their pillars beside him, and rise again after each death
                for (Map.Entry<String, Long> r : new ArrayList<>(s.mutantRise.entrySet())) {
                    if (t < r.getValue()) continue;
                    s.mutantRise.remove(r.getKey());
                    LivingEntity g = spawnGiant(c, r.getKey());
                    if (g != null) { s.giants.put(r.getKey(), g); c.broadcast("&4" + mutantName(r.getKey()) + " &7rises again beside Gorrak!"); }
                }
                for (Map.Entry<String, LivingEntity> g : s.giants.entrySet())
                    if (Kit.alive(g.getValue()) && !g.getKey().equals("laser")) kit(c, s, g.getKey(), g.getValue(), t, 1);
            }
        }
    }

    private void giants(BossContext c, S s) {
        s.phase = 2;
        for (UUID u : s.terra.keySet()) { org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(u); if (e instanceof LivingEntity le) Sky.mobs().remove(le); }
        s.terra.clear();
        s.regrow.clear();
        Kit.announce(c, "&c&lTHE GIANTS AWAKEN", "&7Four of them. In the alcoves.", Sound.ENTITY_WARDEN_EMERGE);
        c.bossSay(NAME, "Enough of the small ones. Meet my masterpieces!");
        if (c.master()) c.title("&4&lMUTANT GIANTS", "&7Every one of them knows every trick.");
        for (String k : ColossusArena.GIANTS) {
            LivingEntity g = spawnGiant(c, k);
            if (g != null) s.giants.put(k, g);
        }
    }

    private void theGiantOne(BossContext c, S s) {
        s.phase = 3;
        Kit.announce(c, "&4&lTHE GIANT ONE", "&7Gorrak takes the field himself.", Sound.ENTITY_ENDER_DRAGON_GROWL);
        c.bossSay(NAME, "You broke my children. I will break YOU.");
        Location drop = c.point("drop");
        if (drop != null) s.boss.teleport(drop);
        if (c.master()) {
            c.bossSay(NAME, "Rise, my children. Rise beside your father!");
            long at = c.steps() + 12;           // after a 3 s beat
            for (String k : ColossusArena.GIANTS) s.mutantRise.put(k, at);
        }
    }

    /** One giant's attack on its own timer (also Gorrak's, in phase 3). */
    private void kit(BossContext c, S s, String k, LivingEntity g, long t, double mul) {
        double base = bossDmg(c) * mul;
        switch (k) {
            case "diamond" -> {
                if (t % 24 != 0) return;
                Player p = Kit.nearest(c, g.getLocation());
                if (p == null) return;
                Location a = g.getLocation().add(0, 1, 0), b = p.getLocation().add(0, 1, 0);
                Location far = a.clone().add(b.toVector().subtract(a.toVector()).normalize().multiply(45));
                Kit.beam(c, a, far, 8, Color.fromRGB(120, 220, 255), q -> { });
                Kit.later(c, 8, () -> {
                    Fx.line(a, far, Color.AQUA, 0.3);
                    for (Player q : c.alive()) if (Kit.nearSegment(q.getLocation().add(0, 1, 0), a, far, 1.6)) Kit.hit(q, base * 1.2);
                    Kit.sound(c, Sound.ITEM_TRIDENT_THROW, 0.6f);
                });
            }
            case "laser" -> {
                Player p = Kit.nearest(c, g.getLocation());
                if (p == null) return;
                if (!p.getUniqueId().equals(s.laserOn)) { s.laserOn = p.getUniqueId(); s.laserRamp = 0; }
                if (t % 2 == 0) Fx.line(g.getEyeLocation(), p.getEyeLocation(), Color.RED, 0.5);
                if (t % 4 == 0) { s.laserRamp++; Kit.hitTrue(p, Kit.nm(c, 800, 16_000) * mul * (1 + 0.2 * s.laserRamp)); }
            }
            case "bigfoot" -> {
                if (t % 32 != 16) return;
                c.title("&c&lSTOMP", "&7Get away from the Strider Giant!");
                Kit.circle(c, g.getLocation(), 9, 40, Color.fromRGB(150, 90, 40), q -> { Kit.hit(q, base * 1.5); q.setVelocity(q.getVelocity().setY(1.0)); },
                        () -> Fx.particle(g.getLocation(), Particle.EXPLOSION_EMITTER, 2, 2, 0));
            }
            case "boulder" -> {
                if (t % 20 != 10) return;
                Player p = pick(c, rng);
                if (p != null) Kit.circle(c, p.getLocation(), 3.5, 40, Color.fromRGB(255, 120, 180), q -> Kit.hit(q, base * 1.3),
                        () -> Fx.particle(p.getLocation(), Particle.BLOCK, 60, 1.5, 0.2, org.bukkit.Material.PINK_TERRACOTTA));
            }
            default -> { }
        }
    }

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        if (is(s.boss, dc.victim) && s.phase != 3) Kit.immune(dc, s.phase == 1 ? "Survive the terracottas!" : "Defeat the giants first!");
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (is(s.boss, mob)) { win(c, NAME, "My... masterpiece... crumbles..."); return; }
        Integer pot = s.terra.remove(mob.getUniqueId());
        if (pot != null && s.phase == 1) {
            s.endAt -= 4;
            s.regrow.put(pot, c.steps() + 40);
        }
        for (Map.Entry<String, LivingEntity> g : new ArrayList<>(s.giants.entrySet())) {
            if (!g.getValue().equals(mob)) continue;
            s.giants.remove(g.getKey());
            if (s.phase == 3 && c.master()) s.mutantRise.put(g.getKey(), c.steps() + Math.max(1, DDials.MUTANT_GIANT_SECONDS.get()) * 4L);
        }
    }
}
