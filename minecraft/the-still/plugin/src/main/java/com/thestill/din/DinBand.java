package com.thestill.din;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The five states of noise.
 *
 * <p>Din is a <em>moment</em>: it spikes while you work and it is gone seconds
 * after you stop. The band is what the rest of the game reads — spawn pressure,
 * the payout multiplier, the bossbar, the audio layer, whether other players can
 * see you are in trouble.
 *
 * <p>Thresholds are the spec's: 120 / 400 / 900 / 1800, multipliers x1.0 at rest
 * to x4.0 at CHOIR. The ceiling is deliberately somewhere almost nobody survives.
 */
public enum DinBand {

    /** Silent. Nothing is coming for you and nothing is paying you extra either. */
    QUIET(0, 1.00, "Quiet", NamedTextColor.GRAY),

    /** Something noticed. The first band where the world starts leaning toward you. */
    STIRRING(120, 1.50, "Stirring", TextColor.color(0xC9B47A)),

    /** They know where you are. */
    DRAWN(400, 2.20, "Drawn", TextColor.color(0xE39A4A)),

    /** Continuous pressure. This is the band the design wants people arguing about. */
    SWARM(900, 3.00, "Swarm", TextColor.color(0xD8685A)),

    /** The shaft comes. Thirty seconds, ten times pay, almost nobody walks out. */
    CHOIR(1800, 4.00, "Choir", TextColor.color(0xA5382A));

    /**
     * Fraction of a threshold you must fall back below to drop a band.
     *
     * <p>Without it, a value sitting exactly on a boundary flickers between two
     * bands every tick — the bossbar strobes, the audio layer stutters on and
     * off, and spawn pressure oscillates. The spec asks for 5% and 5% is enough:
     * you cross up at 900 but do not drop back until 855.
     */
    public static final double HYSTERESIS = 0.05;

    private final double threshold;
    private final double multiplier;
    private final String label;
    private final TextColor colour;

    DinBand(double threshold, double multiplier, String label, TextColor colour) {
        this.threshold = threshold;
        this.multiplier = multiplier;
        this.label = label;
        this.colour = colour;
    }

    /** Din at or above which this band begins. */
    public double threshold() {
        return threshold;
    }

    /** Sell multiplier while in this band. Stamped into the item at break time, never at sell time. */
    public double multiplier() {
        return multiplier;
    }

    public String label() {
        return label;
    }

    public TextColor colour() {
        return colour;
    }

    public boolean isAtLeast(DinBand other) {
        return ordinal() >= other.ordinal();
    }

    /** The band a value falls in, ignoring hysteresis. Use {@link Din} for live state. */
    public static DinBand forValue(double din) {
        DinBand found = QUIET;
        for (DinBand band : values()) {
            if (din >= band.threshold) {
                found = band;
            }
        }
        return found;
    }

    /**
     * The band, given which one we are currently in.
     *
     * <p>Rising uses the plain threshold; falling requires dropping a further
     * {@link #HYSTERESIS} below it. That asymmetry is the whole point — going
     * loud should be instant, going quiet should have to be meant.
     */
    public static DinBand next(DinBand current, double din) {
        DinBand raw = forValue(din);
        if (raw.ordinal() >= current.ordinal()) {
            return raw; // rising, or unchanged: no deadband on the way up
        }
        // Falling: stay put until clear of the current band's floor by the deadband.
        double releaseAt = current.threshold * (1.0 - HYSTERESIS);
        return din >= releaseAt ? current : raw;
    }
}
