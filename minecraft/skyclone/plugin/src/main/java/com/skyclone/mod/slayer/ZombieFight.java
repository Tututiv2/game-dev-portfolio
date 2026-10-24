package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Ghoul Horror (research 04 sec 2.5 / 6.2):
 * <pre>
 * T1+  Life Drain every 4 s: 0.5 s of the tier's DPS (dial, Defense applies), the boss heals what it took
 * T2+  Pestilence every 1 s: 15% of the tier's DPS within 5 blocks, -25% Defense for 2 s
 *      Melee carries the rest, so melee + drain + Pestilence = the stated DPS (SlayerMath.ghoulMeleeShare)
 * T3+  every 40 s: MAD (5 s warning) then ENRAGED 12 s: x1.4 speed, x2 damage
 * T5   (Penitent Horror) regen 0.1%/s, immune to ability damage, TNT on marked circles every 4 s (2 s under 33%)
 *      - a TNT that lands on someone heals it 500,000; Thermonuclear every 30 s: rooted, a 7-block ring closes over
 *      5 s, then lightning hits everything inside
 * </pre>
 */
final class ZombieFight extends BossFight {

    private int enrageState;        // 0 calm, 1 mad, 2 enraged
    private long enrageMark;        // tick count when the current state began
    private final List<Tnt> tnts = new ArrayList<>();
    private long nukeStart = -1;
    private Location nukeAt;

    private static final class Tnt {
        Location at;
        long landsAt;
    }

    ZombieFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        super(mod, owner, type, tier);
        barColor = BossBar.Color.GREEN;
    }

    @Override
    void onBegin() {
        Player o = owner();
        if (o != null && tierNo == 5) {
            Fx.title(o, "&4&lPENITENT HORROR", "&7Keep moving. It feeds on TNT.", 5, 50, 10);
        }
    }

    @Override
    void step() {
        Player o = owner();
        if (o == null) return;
        Location b = boss.getLocation();

        // Life Drain (T1+): research 2.5 "drains health and heals the boss by the same amount".
        // TUNE (the wiki gives no number): slayer.zombie-drain-seconds (default 0.5) seconds of the tier's stated DPS
        // every 4 s, as NORMAL damage so Defense counts; the boss heals exactly what was taken. Was 1.5x DPS as true
        // damage (~45 true dps at T3 on top of melee: 600 HP died in ~13 s whatever the Defense, playtest 2026-10-02).
        if (every(SlayerMath.DRAIN_INTERVAL) && o.getWorld() == boss.getWorld() && o.getLocation().distance(b) < 12) {
            double dealt = hurt(o, SlayerMath.lifeDrain(tier.dps, SlayerDials.ZOMBIE_DRAIN_SECONDS.get()));
            if (dealt > 0) {
                heal(dealt * Math.max(0.0001, SlayerDials.BOSS_HP_SCALE.get()));
                Fx.line(o.getLocation().add(0, 1, 0), b.clone().add(0, 1.2, 0), Fx.rgb(170, 0, 0), 0.4);
                Fx.sound(o, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.6f, 0.6f);
                Sky.vitals().flash(o, "&c&lLIFE DRAIN! &7-" + Text.commasInt(dealt) + "❤", 20);
            }
        }

        // Pestilence (T2+)
        if (tierNo >= 2 && every(1)) {
            Fx.particle(b.clone().add(0, 0.5, 0), Particle.ITEM_SLIME, 16, 2.2, 0.01);
            for (Player p : playersNear(b, 5)) {
                hurt(p, SlayerMath.pestilence(tier.dps));
                mod.debuffs.shred(p, 0.25, 2000);
            }
        }

        // Enrage (T3-T4)
        if (tierNo >= 3 && tierNo <= 4) enrage(o, b);

        if (tierNo == 5) penitent(o, b);
    }

    private void enrage(Player o, Location b) {
        long secs = (ticks - enrageMark) / 4;
        switch (enrageState) {
            case 0 -> {
                if (secs >= 40) {
                    enrageState = 1;
                    enrageMark = ticks;
                    Fx.warn(o, "&c&lMAD!", "&7It is about to enrage...");
                    Fx.sound(b, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 0.6f);
                }
            }
            case 1 -> {
                Fx.particle(b.clone().add(0, 2.2, 0), Particle.ANGRY_VILLAGER, 2, 0.4, 0);
                if (secs >= 5) {
                    enrageState = 2;
                    enrageMark = ticks;
                    status = "&4&lENRAGED";
                    barColor = BossBar.Color.RED;
                    setSpeedMultiplier(1.4);
                    Fx.warn(o, "&4&lENRAGED!", "&7Double damage for 12 seconds - run!");
                    Fx.sound(b, Sound.ENTITY_RAVAGER_ROAR, 1f, 0.8f);
                }
            }
            default -> {
                Fx.particle(b.clone().add(0, 1, 0), Particle.FLAME, 6, 0.4, 0.02);
                if (secs >= 12) {
                    enrageState = 0;
                    enrageMark = ticks;
                    status = "";
                    barColor = BossBar.Color.GREEN;
                    setSpeedMultiplier(1.0);
                }
            }
        }
    }

    private void penitent(Player o, Location b) {
        // Redemption: 0.1% of max per second
        if (every(1)) heal(max() * 0.001);

        // Thermonuclear every 30 s
        if (nukeStart < 0 && elapsedSeconds() > 0 && every(30)) {
            nukeStart = ticks;
            nukeAt = b.clone();
            setImmobile(true);
            immune = null;
            Fx.warn(o, "&4&lTHERMONUCLEAR", "&7Get more than &c7 blocks &7away!");
            Fx.sound(b, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.5f);
        }
        if (nukeStart >= 0) {
            long t = ticks - nukeStart;              // steps since start, 20 steps = 5 s
            double r = 7.0 * Math.min(1, t / 20.0);
            Fx.ring(nukeAt, 7, Fx.rgb(60, 60, 60), 48);
            Fx.ring(nukeAt, Math.max(0.5, r), Fx.rgb(230, 30, 30), 40);
            if (t % 4 == 0 && t < 20) {
                for (Player p : playersNear(nukeAt, 30)) {
                    Fx.title(p, "&4&l" + (5 - t / 4), "&cTHERMONUCLEAR", 0, 22, 2);
                }
            }
            if (t >= 20) {
                try { nukeAt.getWorld().strikeLightningEffect(nukeAt); } catch (RuntimeException ignored) { }
                Fx.particle(nukeAt.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 2, 1.5, 0);
                for (Player p : playersNear(nukeAt, 7)) {
                    hurtPercent(p, 75, "thermonuclear");          // TUNE: "kill/huge damage", ignores protection
                }
                nukeStart = -1;
                setImmobile(false);
            }
        }

        // TNT volley: every 4 s, every 2 s under a third of its health
        double interval = frac() < 1.0 / 3.0 ? 2 : 4;
        if (nukeStart < 0 && every(interval)) {
            for (Player p : playersNear(b, 25)) {
                Tnt tnt = new Tnt();
                tnt.at = ground(p.getLocation().clone().add(0, 0.5, 0));
                tnt.landsAt = ticks + 6;    // 1.5 s warning
                tnts.add(tnt);
            }
            Fx.sound(b, Sound.ENTITY_TNT_PRIMED, 1f, 1f);
        }
        Iterator<Tnt> it = tnts.iterator();
        while (it.hasNext()) {
            Tnt tnt = it.next();
            Fx.ring(tnt.at, 2, Fx.rgb(255, 80, 0), 20);
            if (ticks >= tnt.landsAt) {
                it.remove();
                Fx.particle(tnt.at.clone().add(0, 0.5, 0), Particle.EXPLOSION, 3, 0.6, 0);
                Fx.sound(tnt.at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.1f);
                boolean hit = false;
                for (Player p : playersNear(tnt.at, 2.2)) {
                    hit = true;
                    hurtPercent(p, 20, "tnt");                     // TUNE
                }
                if (hit) {
                    heal(500_000 * Math.max(0.0001, SlayerDials.BOSS_HP_SCALE.get()));
                    Player o2 = owner();
                    if (o2 != null) Sky.vitals().flash(o2, "&4&lREDEMPTION! &7It healed &a500k❤", 30);
                }
            }
        }
    }

    @Override
    void onHit(DamageContext ctx) {
        if (tierNo == 5 && ctx.kind == DamageContext.Kind.MAGIC) {
            ctx.cancelled = true;
            Player p = ctx.playerAttacker();
            if (p != null) Sky.vitals().flash(p, "&cImmune to ability damage!", 20);
        }
    }

    @Override
    void onStrike(Player target, DamageContext ctx) {
        // The tier's stated DPS is the whole bill: melee carries what Life Drain and Pestilence do not (wave-4 loops,
        // the abilities were stacked on top: x1.55 the stated damage at T3).
        ctx.multiplicative *= SlayerMath.ghoulMeleeShare(tierNo, SlayerDials.ZOMBIE_DRAIN_SECONDS.get());
        if (enrageState == 2) ctx.multiplicative *= 2;   // TUNE: research "x~2"
    }
}
