package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.ext.BossFloor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Random;
import java.util.function.Supplier;

/**
 * What every scripted floor shares: per-run state stored in {@link BossContext#state()} (one {@link BossFloor}
 * instance serves every run), the floor's research HP/damage, world-block edits inside the arena, and clean-up.
 */
abstract class FightBase implements BossFloor {

    protected final Random rng = new Random();

    @SuppressWarnings("unchecked")
    protected static <T> T st(BossContext c, Supplier<T> make) {
        return (T) c.state().computeIfAbsent("fight", k -> make.get());
    }

    /**
     * The floor's boss health (research 04 sec 3.13) times the boss-HP dial and the party-size scale (wave-4 endgame:
     * research HP is sized for five; {@code dungeons.solo-boss-hp-scale} at one member up to 1.0 at five).
     */
    protected static double bossHp(BossContext c) { return c.floor().bossHp * Kit.hpScale() * Kit.partyHp(c); }

    protected static double bossDmg(BossContext c) { return c.floor().bossDamage; }

    /**
     * Spawn the floor's boss with its research health. {@code dmg} is the floor's headline damage (what the scripted
     * attacks are sized from); the body's own contact hit is {@code dmg x boss-melee-share x boss-damage-scale}.
     */
    protected static LivingEntity spawnBoss(BossContext c, String mobId, Location at, double hp, double dmg) {
        MobDef d = Sky.mobs().def(mobId);
        if (d == null || at == null) return null;
        // integrator: the boss body's melee follows dungeons.boss-damage-scale like its scripted attacks do.
        // fixer: and it is only a SHARE of the headline number. Swinging the full research figure (2,664 on F1) every
        // second took 364 a hit off a 588 HP / 630 Defense player, so Grinko killed him in two swings, or one swing
        // after a skull - the "one-shot" the integration pass saw. See model.Balance and /sb dungeons balance.
        double mul = (d.damage > 0 ? dmg * Kit.meleeShare() / d.damage : Kit.meleeShare()) * Kit.dmgScale();
        return c.spawnBoss(mobId, at, hp, mul);
    }

    /** Fill the box between two arena points with a material (barriers coming down, gates opening, crushers). */
    protected static void fillBox(BossContext c, String a, String b, Material m) {
        Location la = c.point(a), lb = c.point(b);
        if (la == null || lb == null) return;
        fill(la, lb, m.createBlockData());
    }

    protected static void fill(Location la, Location lb, BlockData data) {
        int x0 = Math.min(la.getBlockX(), lb.getBlockX()), x1 = Math.max(la.getBlockX(), lb.getBlockX());
        int y0 = Math.min(la.getBlockY(), lb.getBlockY()), y1 = Math.max(la.getBlockY(), lb.getBlockY());
        int z0 = Math.min(la.getBlockZ(), lb.getBlockZ()), z1 = Math.max(la.getBlockZ(), lb.getBlockZ());
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) la.getWorld().getBlockAt(x, y, z).setBlockData(data, false);
    }

    protected static Block block(Location l) { return l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }

    /** The boss died: last words, a fanfare, then the framework's completion. */
    protected static void win(BossContext c, String name, String last) {
        if (last != null) c.bossSay(name, last);
        Kit.announce(c, "&a&l" + name.toUpperCase() + " DEFEATED", "&7The way out is open.", Sound.UI_TOAST_CHALLENGE_COMPLETE);
        c.complete();
    }

    protected static String time(long steps) {
        long s = Math.max(0, steps) / 4;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    protected static boolean is(LivingEntity e, Object o) { return e != null && e.equals(o); }

    protected static void healFull(LivingEntity e) { if (e != null && e.isValid()) Sky.mobs().setHealth(e, Sky.mobs().maxHealth(e)); }

    protected static Player pick(BossContext c, Random r) { return Kit.random(c, r); }

    @Override
    public void stop(BossContext c) {
        Kit.clear(c.runId());
        Hooks.clear(c.runId());
    }
}
