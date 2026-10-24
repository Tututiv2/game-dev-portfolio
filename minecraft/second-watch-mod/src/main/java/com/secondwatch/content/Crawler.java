package com.secondwatch.content;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * The one with teeth, and the reason light is worth carrying.
 *
 * <p>Everything else in this bestiary refuses to touch you, which is what makes
 * them frightening - but a world where nothing can actually hurt you eventually
 * stops being tense, and the players work that out faster than anyone expects.
 * The Crawler is the exception that keeps the rest honest: fast, aggressive, and
 * completely stopped by a torch.
 *
 * <p>It turns light into a currency rather than a convenience. Coal is now the
 * thing standing between you and the only monster that bites, which means every
 * decision to go further into the dark has a price attached, and the endless
 * night has a clock on it that the players can feel in their inventory.
 *
 * <p>It freezes rather than flees, because a monster that runs away is a monster
 * you have beaten. This one waits at the edge of the torchlight.
 */
public class Crawler extends Spider {

    public Crawler(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createCrawlerAttributes() {
        return Spider.createAttributes()
                // Genuinely faster than a walking player. In the dark you do not
                // outrun this, you get to light or you do not.
                .add(Attributes.MOVEMENT_SPEED, 0.36)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MAX_HEALTH, 14.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        // Priority 0: outranks every inherited spider goal, including attack.
        this.goalSelector.addGoal(0, new FreezeInLightGoal(this));
        super.registerGoals();
    }

    /**
     * Stops it dead in lit ground.
     *
     * <p>Holds MOVE, LOOK and JUMP so nothing underneath can quietly keep
     * pathing while this is active - a Crawler that creeps forward one block at
     * a time inside a lit room breaks the only promise the mod makes about
     * safety, and that promise is what the entire light economy rests on.
     */
    private static class FreezeInLightGoal extends Goal {

        private final Crawler mob;

        FreezeInLightGoal(Crawler mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET));
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
        public void start() {
            mob.getNavigation().stop();
            mob.setTarget(null);
        }

        @Override
        public void tick() {
            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        }
    }
}
