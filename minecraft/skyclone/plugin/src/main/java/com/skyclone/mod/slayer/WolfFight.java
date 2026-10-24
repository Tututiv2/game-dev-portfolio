package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Msg;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;

/**
 * Fenris Packlord (research 04 sec 2.7 / 6.2):
 * <pre>
 * all  Agile: small and fast (MobDef speed)
 * T2+  True Damage: 10 / 50 / 200 per second to everyone within 4 blocks, ignoring Defense
 * T3+  Call the Pups at 50%: invulnerable and rooted, 5 pups one per second, each with 10% of its max health;
 *      it regenerates 0.5%/s while any pup lives (research "(?)", TUNE); resumes when the pack is dead
 * </pre>
 */
final class WolfFight extends BossFight {

    private boolean pupsCalled;
    private long pupsStart = -1;
    private int pupsSpawned;

    WolfFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        super(mod, owner, type, tier);
        barColor = BossBar.Color.WHITE;
    }

    @Override
    void onBegin() {
        if (boss instanceof Wolf w) {
            try { w.setAngry(true); } catch (RuntimeException ignored) { }
        }
    }

    @Override
    void step() {
        Player o = owner();
        if (o == null) return;
        Location b = boss.getLocation();
        if (tier.trueDps > 0 && every(1)) {
            for (Player p : playersNear(b, 4)) {
                hurtTrue(p, tier.trueDps);
                Fx.particle(p.getLocation().add(0, 1, 0), Particle.DAMAGE_INDICATOR, 3, 0.3, 0.05);
            }
        }
        if (pupsStart >= 0) {
            long since = ticks - pupsStart;
            if (pupsSpawned < 5 && since % 4 == 0) {
                Location at = around(b, 1.5, 3);
                LivingEntity pup = spawnMinion("slayer_wolf_pup", at, max() * 0.10);
                if (pup instanceof Wolf w) {
                    try { w.setAngry(true); } catch (RuntimeException ignored) { }
                }
                pupsSpawned++;
                Fx.sound(at, Sound.ENTITY_WOLF_GROWL, 1f, 1.5f);
            }
            if (every(1)) heal(max() * 0.005);
            Fx.particle(b.clone().add(0, 1.2, 0), Particle.CLOUD, 3, 0.3, 0.01);
            if (pupsSpawned >= 5 && liveMinions("slayer_wolf_pup") == 0) {
                pupsStart = -1;
                immune = null;
                setImmobile(false);
                Msg.send(o, "&aThe pack is down! &7The Packlord is vulnerable again.");
                Fx.sound(b, Sound.ENTITY_WOLF_BIG_GROWL, 1f, 0.8f);
            }
        }
    }

    @Override
    void onHit(DamageContext ctx) {
        if (tierNo >= 3 && !pupsCalled && clampAt(ctx, 0.5)) {
            pupsCalled = true;
            pupsStart = ticks;
            pupsSpawned = 0;
            immune = "Call the Pups";
            setImmobile(true);
            Player o = owner();
            if (o != null) Fx.warn(o, "&f&lCALL THE PUPS!", "&7Kill the whole pack!");
            Fx.sound(boss.getLocation(), Sound.ENTITY_WOLF_BIG_GROWL, 1.2f, 0.6f);
        }
    }

    @Override
    void minionAttacking(LivingEntity m, Player target, DamageContext ctx) {
        super.minionAttacking(m, target, ctx);
        // pups bite for half the Packlord's damage (TUNE: not published)
        ctx.base = tier.dps * 0.5;
    }
}
