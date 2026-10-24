package com.secondwatch.content;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * Harmless, and it goes wherever the light is.
 *
 * <p>It exists to punish the solution. Light is how you survive the Crawler, so
 * light is what the players build their whole night around - and this is the
 * thing that turns a torch into a liability by making it <em>visible</em>. A lit
 * base at night four is knee-deep in these, they have never hurt anybody, and
 * they are a signpost pointing at exactly where the two of you sleep.
 *
 * <p>It must stay completely harmless. The moment it does damage it becomes a
 * pest to be cleared and the players just kill them all; as an inert, growing,
 * pointing mass it is something they have to live with.
 */
public class Swarm extends Silverfish {

    /** How far it will travel toward a light it can sense. */
    private static final double SEEK_RANGE = 24.0;

    public Swarm(EntityType<? extends Silverfish> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createSwarmAttributes() {
        return Silverfish.createAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.ATTACK_DAMAGE, 0.0)
                .add(Attributes.MAX_HEALTH, 4.0)
                .add(Attributes.FOLLOW_RANGE, SEEK_RANGE);
    }

    @Override
    protected void registerGoals() {
        // Not calling super: Silverfish's own goals include attacking and
        // summoning friends out of stone, and neither belongs here.
        this.goalSelector.addGoal(0, new SeekTheLightGoal(this));
        this.goalSelector.addGoal(1, new RandomStrollGoal(this, 0.6));
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    /**
     * Head for whoever is standing in the brightest square.
     *
     * <p>Approximated by "the nearest player who is lit", which is cheap and,
     * more importantly, is the version the players can actually reason about:
     * carry a torch and these follow you.
     */
    private static class SeekTheLightGoal extends Goal {

        private final Swarm mob;
        private Player target;

        SeekTheLightGoal(Swarm mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, SEEK_RANGE);
            if (nearest == null || !nearest.isAlive()) return false;
            if (!Sight.isLit(nearest)) return false;
            target = nearest;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive()
                    && Sight.isLit(target)
                    && mob.distanceTo(target) <= SEEK_RANGE;
        }

        @Override
        public void stop() {
            target = null;
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (target == null) return;
            mob.getLookControl().setLookAt(target, 20.0f, 20.0f);
            if (mob.distanceTo(target) > 2.0) {
                mob.getNavigation().moveTo(target, 1.0);
            }
        }
    }
}
