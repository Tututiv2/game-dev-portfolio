package com.secondwatch.content;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * It only moves when nobody is looking at it.
 *
 * <p>The oldest trick in horror and still the best, because it makes the player
 * the mechanism: nothing happens to you, you do it to yourself by turning
 * around. And in co-op it becomes something a single player cannot have at all -
 * two people can cover two directions, so a Watcher is only dangerous when you
 * have both looked at the same thing at the same time, which is exactly what two
 * frightened people do.
 *
 * <p>It never touches you. It closes to eight blocks and then simply stands
 * there being nearer than it was.
 */
public class Watcher extends Husk {

    /** It will not come closer than this, however long you look away. */
    public static final double PERSONAL_SPACE = 8.0;

    public static final double NOTICE_DISTANCE = 48.0;

    public Watcher(EntityType<? extends Husk> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createWatcherAttributes() {
        return Zombie.createAttributes()
                // Quick, because it only ever gets to move in the gaps.
                .add(Attributes.MOVEMENT_SPEED, 0.34)
                .add(Attributes.FOLLOW_RANGE, NOTICE_DISTANCE)
                .add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new ApproachWhileUnobservedGoal(this));
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    /**
     * Move only in the gaps. Freeze completely - not slow, <em>stopped</em> -
     * the instant anybody can see it.
     */
    private static class ApproachWhileUnobservedGoal extends Goal {

        private final Watcher mob;
        private Player target;

        ApproachWhileUnobservedGoal(Watcher mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            target = mob.level().getNearestPlayer(mob, NOTICE_DISTANCE);
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive()
                    && mob.distanceTo(target) <= NOTICE_DISTANCE;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            target = null;
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (target == null) return;

            if (Sight.isObserved(mob)) {
                // Absolutely still. Any drift at all and a player watching
                // carefully sees it move, which turns the rule from unnerving
                // into merely buggy.
                mob.getNavigation().stop();
                mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
                mob.getLookControl().setLookAt(target, 10.0f, 10.0f);
                return;
            }

            if (mob.distanceTo(target) > PERSONAL_SPACE) {
                mob.getNavigation().moveTo(target, 1.0);
            } else {
                mob.getNavigation().stop();
            }
        }
    }
}
