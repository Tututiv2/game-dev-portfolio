package com.secondwatch.core;

/**
 * Turns "what the players are doing" into dread.
 *
 * <p>This is the join that makes a run feel caused rather than scheduled. Before
 * it, the night escalated on a timer and the rolled {@link Trigger} was
 * flavour text in a dead stranger's journal. After it, the journal entry is
 * <em>instructions</em> - and ignoring them costs you time you can measure.
 *
 * <p>The rates are deliberately small. A player who trips their trigger
 * constantly for a whole chapter adds roughly {@link #FULL_CHAPTER_CONTRIBUTION}
 * to the clock - meaningful, but never enough to skip an act. Dread is still
 * mostly a function of how many nights you have survived, because a run that can
 * be rushed by being careless is a run where the ending arrives before the
 * story does.
 */
public final class TriggerModel {

    /**
     * Dread per second, per player, while fully tripping the trigger.
     *
     * <p>Chosen from the chapter length: 20 minutes of constant exposure is
     * about half a tier, so obeying the journal buys you roughly one extra
     * chapter across a run. That is worth reading a book for, and it is not
     * worth playing the whole night in silence for.
     */
    public static final double RATE_PER_SECOND = 0.0004;

    /** What a whole chapter of relentless carelessness is worth. */
    public static final double FULL_CHAPTER_CONTRIBUTION = RATE_PER_SECOND * 20 * 60;

    /** The light level at or above which a square counts as lit. */
    public static final int LIT = 7;

    /** Digging is counted per block rather than per second. */
    public static final double PER_BLOCK = 0.0006;

    /** Below this a player counts as standing still. */
    public static final double STILL_SPEED = 0.02;

    /** Being further apart than this counts as separated. */
    public static final double SEPARATION_DISTANCE = 40.0;

    /**
     * A snapshot of one player, in the terms the triggers care about.
     *
     * @param speaking        talked during this sample window
     * @param lightLevel      block light where they stand, 0..15
     * @param speed           blocks per tick they moved
     * @param partnerDistance how far from the other player
     * @param blocksBroken    blocks broken during this sample window
     */
    public record Conditions(
            boolean speaking,
            int lightLevel,
            double speed,
            double partnerDistance,
            int blocksBroken) {
    }

    private TriggerModel() {
    }

    /**
     * How much dread this player's behaviour is worth over {@code seconds}.
     *
     * <p>Never negative. Doing the right thing earns you nothing - it only fails
     * to cost you - because {@link Dread} does not go down and this must not be
     * the thing that sneaks a way around that.
     */
    public static double feed(Trigger trigger, Conditions c, double seconds) {
        if (seconds < 0) throw new IllegalArgumentException("time does not run backwards");

        return switch (trigger) {
            case TALKING -> c.speaking() ? RATE_PER_SECOND * seconds : 0.0;

            case LIGHT -> c.lightLevel() >= LIT ? RATE_PER_SECOND * seconds : 0.0;

            case DARKNESS -> c.lightLevel() < LIT ? RATE_PER_SECOND * seconds : 0.0;

            case STILLNESS -> c.speed() < STILL_SPEED ? RATE_PER_SECOND * seconds : 0.0;

            case SEPARATION -> c.partnerDistance() > SEPARATION_DISTANCE
                    ? RATE_PER_SECOND * seconds : 0.0;

            // Per block, not per second: a player who mines one block a minute
            // is not being reckless, and a player strip-mining is very much is.
            case DIGGING -> PER_BLOCK * Math.max(0, c.blocksBroken());
        };
    }
}
