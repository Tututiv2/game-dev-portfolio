package com.secondwatch.content;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Approach, stop short, watch.
 *
 * <p>The one rule that has to hold in every branch below: it must never, under
 * any circumstance, end up closer than {@link Follower#STOP_DISTANCE}. The
 * moment a Follower crosses that line the players learn it can, and everything
 * this entity is worth comes from their belief that it will not.
 */
public class WatchFromDistanceGoal extends Goal {

    /**
     * Hysteresis, so it does not jitter forward and back on the boundary. It
     * closes to STOP_DISTANCE and only starts walking again if the player opens
     * the gap by more than this.
     */
    private static final double SLACK = 3.0;

    private final Follower mob;
    private Player target;

    public WatchFromDistanceGoal(Follower mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        target = mob.level().getNearestPlayer(mob, Follower.NOTICE_DISTANCE);
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return target != null
                && target.isAlive()
                && mob.distanceTo(target) <= Follower.NOTICE_DISTANCE;
    }

    @Override
    public void stop() {
        target = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) return;

        // Always face them. A thing that watches without approaching reads as
        // intent; a thing that wanders while facing away reads as scenery.
        mob.getLookControl().setLookAt(target, 30.0f, 30.0f);

        double distance = mob.distanceTo(target);

        if (distance > Follower.STOP_DISTANCE + SLACK) {
            mob.getNavigation().moveTo(target, 1.0);
        } else {
            // Close enough. Stand still and let them decide what that means.
            mob.getNavigation().stop();
        }
    }
}
