package com.secondwatch.content;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * Too tall, too slow, and it will not step into the light.
 *
 * <p>The Crawler makes light valuable. The Tall One makes it <em>insufficient</em>
 * - because it does not freeze in a lit square, it simply walks around it and
 * waits on the other side. A torch buys you a room. It does not buy you a route.
 *
 * <p>Slow on purpose. You can always outwalk it, which means every death to this
 * thing is a death by getting cornered, and getting cornered is a decision the
 * player made three minutes earlier. That is a far better death than being
 * out-sprinted, because the player can see exactly where they went wrong and
 * will describe it to their friend at length.
 *
 * <p>Built on {@link EnderMan} for the silhouette and, more usefully, for the
 * stare mechanic that comes free with it.
 */
public class TallOne extends EnderMan {

    public TallOne(EntityType<? extends EnderMan> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createTallAttributes() {
        return EnderMan.createAttributes()
                // Slower than a walking player, always. You outwalk it or you
                // made a mistake with a wall.
                .add(Attributes.MOVEMENT_SPEED, 0.20)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new RefuseLitGroundGoal(this));
        super.registerGoals();
    }

    /**
     * It will not stand in the light - but unlike the Crawler it is not stopped
     * by it, only turned. Standing in a torch-lit doorway makes it go round.
     */
    private static class RefuseLitGroundGoal extends Goal {

        private final TallOne mob;

        RefuseLitGroundGoal(TallOne mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return Sight.isLit(mob);
        }

        @Override
        public boolean canContinueToUse() {
            return Sight.isLit(mob);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            // Back out of the lit square rather than freezing in it. Keeping the
            // target means it is still hunting - it just cannot come through
            // here, which is the difference between a wall and a win.
            mob.getNavigation().stop();
            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.4));
        }
    }
}
