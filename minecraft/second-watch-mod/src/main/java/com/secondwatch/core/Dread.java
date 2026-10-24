package com.secondwatch.core;

/**
 * The escalation clock.
 *
 * <p>Two rules, both load-bearing:
 *
 * <p><b>It never goes down.</b> Not when you get to safety, not when you sleep,
 * not when you do everything right. A horror game that lets you buy relief has
 * taught the player that the situation is negotiable, and once it is negotiable
 * it is not frightening. Doing well here buys you TIME, never ground.
 *
 * <p><b>Night number sets a floor.</b> So a group that plays perfectly still
 * arrives at the ending, just later and in better shape. This is what keeps a
 * recorded session watchable - the arc happens whether or not the players are
 * good at it.
 */
public final class Dread {

    private final RunRules rules;
    private int night = 1;
    private double accumulated;

    public Dread(RunRules rules) {
        this.rules = rules;
    }

    /**
     * The floor imposed by simply having survived this long.
     *
     * <p>Explicitly scheduled rather than a linear ramp. A ramp looks tidy and
     * quietly skips tiers on short runs - a four-night run stepped straight from
     * SOCIAL to HUNT, which meant AUTHORED never happened, which meant the
     * Crawler never spawned, which meant light was never worth carrying. An
     * entire mechanic was missing and nothing failed.
     *
     * <p>So the schedule is stated instead of derived, and it guarantees the two
     * nights that carry meaning: the first is always ordinary, and the
     * second-to-last always brings the thing with teeth.
     */
    private double nightFloor() {
        return floorOf(scheduledTier(night, rules.nights()));
    }

    /**
     * Which tier a given night is guaranteed to reach.
     *
     * <ul>
     *   <li>night 1 is always DENIABLE - the control sample, and the most
     *       important ten minutes in the mod</li>
     *   <li>the last night is always HUNT</li>
     *   <li>the one before it is always AUTHORED, so the Crawler always gets a
     *       night and light always becomes a currency</li>
     *   <li>middle nights fill in ARGUABLE and SOCIAL; with only one middle
     *       night it is SOCIAL, because the Mimic teaches the law and a run
     *       without it never explains itself</li>
     * </ul>
     */
    public static Tier scheduledTier(int night, int nights) {
        if (night <= 1) return Tier.DENIABLE;
        if (night >= nights) return Tier.HUNT;
        if (night == nights - 1) return Tier.AUTHORED;
        return (night == 2 && nights >= 6) ? Tier.ARGUABLE : Tier.SOCIAL;
    }

    /** The bottom of a tier's range. */
    public static double floorOf(Tier tier) {
        return switch (tier) {
            case DENIABLE -> 0.0;
            case ARGUABLE -> 0.20;
            case SOCIAL -> 0.45;
            case AUTHORED -> 0.70;
            case HUNT -> 0.95;
        };
    }

    /** Current dread, 0..1. */
    public double value() {
        return Math.min(1.0, Math.max(accumulated, nightFloor()));
    }

    public int night() {
        return night;
    }

    /**
     * Feed the clock. Called when a player does whatever this run's
     * {@link Trigger} is - talks, lights, digs, stands still, separates.
     *
     * <p>Negative amounts are rejected rather than clamped. Silently accepting
     * a negative here would be exactly the "relief" this class exists to
     * forbid, and it would fail quietly, which is the worst way to fail.
     */
    public void feed(double amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("dread cannot be fed a negative amount: " + amount);
        }
        accumulated = Math.min(1.0, accumulated + amount);
    }

    /** Move to the next night. */
    public void advanceNight() {
        if (night >= rules.nights()) {
            night = rules.nights();
            accumulated = 1.0;
            return;
        }
        night++;
    }

    public boolean isLastNight() {
        return night >= rules.nights();
    }

    public Tier tier() {
        double v = value();
        if (v < 0.20) return Tier.DENIABLE;
        if (v < 0.45) return Tier.ARGUABLE;
        if (v < 0.70) return Tier.SOCIAL;
        if (v < 0.95) return Tier.AUTHORED;
        return Tier.HUNT;
    }

    /**
     * What the entity is permitted to do right now.
     *
     * <p>The ordering matters more than the numbers: the phantom does not get a
     * body until SOCIAL, which means the players have already spent two tiers
     * learning that this world produces small wrong things. By the time it wears
     * a person, they have been trained to recognise its handwriting. That is why
     * the split lands instead of reading as a gimmick.
     */
    public enum Tier {
        /** Misplaced objects. A sound with no source. Reads as Minecraft jank. */
        DENIABLE,
        /** Blocks and geometry disagree between the two clients. */
        ARGUABLE,
        /** The phantom gets a body and a voice. The split becomes possible. */
        SOCIAL,
        /** It stops improvising and starts steering you somewhere. */
        AUTHORED,
        /** It stops pretending. */
        HUNT;

        public boolean allowsPhantomBody() {
            return ordinal() >= SOCIAL.ordinal();
        }

        public boolean allowsPhantomVoice() {
            return ordinal() >= SOCIAL.ordinal();
        }

        public boolean allowsLuring() {
            return ordinal() >= AUTHORED.ordinal();
        }
    }
}
