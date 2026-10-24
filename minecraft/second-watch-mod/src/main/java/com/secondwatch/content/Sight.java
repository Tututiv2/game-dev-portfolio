package com.secondwatch.content;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Shared answers to the two questions this mod's entire bestiary is built on:
 * <b>is anybody looking at me</b>, and <b>am I standing in the light</b>.
 *
 * <p>Both are deliberately generous in the mob's disfavour. A Watcher that moves
 * while somebody can technically see it, or a Crawler that charges out of a
 * square the player thought was lit, does not read as a scary rule - it reads as
 * a broken one, and a broken rule is the one thing that ends the spell for good.
 * So when in doubt: it is observed, and it is lit.
 */
public final class Sight {

    /** Roughly a 110-degree cone. Wider than the game's FOV, on purpose. */
    private static final double COS_HALF_FOV = Math.cos(Math.toRadians(55));

    /** Past this a person is a shape, and a shape cannot be said to be watched. */
    private static final double MAX_SIGHT = 64.0;

    private Sight() {
    }

    /** Is any living player currently looking at this entity? */
    public static boolean isObserved(LivingEntity entity) {
        for (Player player : entity.level().players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (observedBy(entity, player)) return true;
        }
        return false;
    }

    public static boolean observedBy(LivingEntity entity, Player player) {
        if (player.level() != entity.level()) return false;

        double distSq = player.distanceToSqr(entity);
        if (distSq > MAX_SIGHT * MAX_SIGHT) return false;

        Vec3 toEntity = entity.position().subtract(player.position());
        if (toEntity.lengthSqr() < 1.0e-4) return true;

        if (player.getLookAngle().dot(toEntity.normalize()) < COS_HALF_FOV) return false;

        return player.hasLineOfSight(entity);
    }

    /** Block light where this entity is standing, 0..15. */
    public static int lightAt(LivingEntity entity) {
        return entity.level().getBrightness(LightLayer.BLOCK, entity.blockPosition());
    }

    /**
     * The threshold everything in the bestiary agrees on.
     *
     * <p>Seven, so that a single torch (14, falling off by one per block) buys a
     * genuinely useful pocket of safety rather than a square you have to stand
     * exactly on. Light has to be worth carrying or the whole economy of the
     * night collapses.
     */
    public static final int LIT = 7;

    public static boolean isLit(LivingEntity entity) {
        return lightAt(entity) >= LIT;
    }
}
