package com.secondwatch.content;

import com.secondwatch.core.Utterance;
import com.secondwatch.game.RunState;
import com.secondwatch.game.Runs;
import com.secondwatch.game.Voice;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * It says things you said.
 *
 * <p>The phantom's trick worn by something that is visibly not a person, and
 * that difference is the whole point of it existing separately. The phantom
 * works because you believe it is your friend; the Mimic works because you can
 * SEE it is not, and it is using your friend's voice anyway. One is a
 * deception, the other is a thing wearing a stolen thing, and the second is
 * colder.
 *
 * <p>It is also the mod teaching its own rules. A player who has heard a Mimic
 * play back a sentence they said an hour ago has learned the law - <i>it can
 * only repeat</i> - from a monster rather than from a tooltip, and they learned
 * it somewhere safe enough to think about it.
 *
 * <p>It never attacks. It wanders, it looks at you, and every so often it speaks
 * in a voice it has no right to.
 */
public class Mimic extends Drowned {

    /** Minimum gap between lines. Scarcity is most of what sells it. */
    private static final int COOLDOWN_TICKS = 20 * 25;

    /** It only speaks to somebody close enough for the voice to matter. */
    private static final double SPEAK_RANGE = 24.0;

    private int speakCooldown = 20 * 8;

    public Mimic(EntityType<? extends Drowned> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createMimicAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.22)
                .add(Attributes.FOLLOW_RANGE, SPEAK_RANGE)
                .add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WaterAvoidingRandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, (float) SPEAK_RANGE));
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;

        if (speakCooldown > 0) {
            speakCooldown--;
            return;
        }

        RunState run = Runs.get();
        if (run == null || !run.started() || !Voice.ready()) return;

        Player nearest = level().getNearestPlayer(this, SPEAK_RANGE);
        if (!(nearest instanceof ServerPlayer listener) || !listener.isAlive()) return;

        // Everything it can say came out of the corpus, which is the law made
        // mechanical - there is no branch anywhere that lets it invent.
        List<Utterance> pool = run.corpus().eligible(
                listener.getName().getString(), run.rules(), run.runNowMs());

        if (pool.isEmpty()) {
            // Nothing of theirs is old enough yet. Try again shortly rather than
            // burning the full cooldown on silence.
            speakCooldown = 20 * 5;
            return;
        }

        Utterance line = pool.get(this.random.nextInt(pool.size()));
        run.corpus().markUsed(line);

        // In the listener's OWN voice, from where this thing is standing. The
        // first time a player hears themselves come out of the dark, the game
        // has explained its entire premise without a word of text.
        Voice.speakLine(listener, line,
                this.getX(), this.getY() + 1.2, this.getZ(),
                false, false, true);

        speakCooldown = COOLDOWN_TICKS;
    }
}
