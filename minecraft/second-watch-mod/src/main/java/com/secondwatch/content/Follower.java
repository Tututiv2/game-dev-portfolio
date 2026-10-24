package com.secondwatch.content;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * It walks toward you, stops at twenty blocks, and does nothing.
 *
 * <p>The whole design is the refusal. A monster that charges you resolves the
 * situation - you fight it or you run, and either way the tension is spent
 * inside three seconds. A monster that closes to a fixed distance and then just
 * stands there never resolves anything, so the players carry it with them: they
 * look over their shoulder, they describe it to each other, they argue about
 * whether it is closer than it was.
 *
 * <p>By night four there are eleven of them and they still have not done
 * anything, which is worse.
 *
 * <p>Built on {@link Zombie} on purpose rather than as a shortcut. It inherits a
 * person-shaped silhouette that every player already reads instantly, and at
 * forty blocks in the dark the shape is the entire effect - a bespoke model
 * would cost a week and be less legible. What is custom is the behaviour, and
 * the behaviour is the point: it never attacks, it never runs, and it never
 * closes the distance.
 */
public class Follower extends Zombie {

    /** It comes this close and not one block closer. */
    public static final double STOP_DISTANCE = 20.0;

    /** Past this it loses interest, so a run does not accumulate a permanent tail. */
    public static final double NOTICE_DISTANCE = 64.0;

    public Follower(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createFollowerAttributes() {
        return Zombie.createAttributes()
                // Slower than a walking player. It can never catch anybody, and
                // that is a promise the design makes and must keep.
                .add(Attributes.MOVEMENT_SPEED, 0.19)
                .add(Attributes.FOLLOW_RANGE, NOTICE_DISTANCE)
                .add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    /**
     * Deliberately does not call {@code super.registerGoals()}.
     *
     * <p>Zombie's own goals include melee attack and target selection, and
     * inheriting even one of them would turn this into an ordinary monster. The
     * absence of an attack goal IS this entity.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WatchFromDistanceGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, (float) NOTICE_DISTANCE));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    /** Belt and braces: even if something hands it a target, it cannot hurt you. */
    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    /**
     * The sun is not coming back, but if somebody ends the run these must not all
     * catch fire at dawn - a field of them burning is comic, and the mod cannot
     * afford to be funny at that exact moment.
     */
    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    public boolean isBaby() {
        return false;
    }
}
