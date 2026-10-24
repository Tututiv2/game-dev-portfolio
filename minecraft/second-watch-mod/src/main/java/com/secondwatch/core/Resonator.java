package com.secondwatch.core;

/**
 * The crafted counter to the Quiet, and the reason crafting exists in this mod
 * at all.
 *
 * <p>You cannot damage a thing made of absence, so you do not try - you force it
 * to be heard. A resonator is a placed device that drives sound into the
 * suppression field hard enough that the Quiet is briefly audible, and it cannot
 * survive being perceived for long.
 *
 * <p>The cost is the design: <b>it is extremely loud, and loud is how everything
 * in this world finds you.</b> On a TALKING or DIGGING run it is a dinner bell.
 * So the fight is not "hit the boss", it is "decide when you can afford to make
 * the single most dangerous noise in the game", twice, with somebody you cannot
 * currently talk to.
 */
public final class Resonator {

    /** How long one firing forces sound into the field. */
    public static final long ACTIVE_MS = 6_000L;

    /** How long before it can be fired again. Long enough to be a decision. */
    public static final long COOLDOWN_MS = 45_000L;

    /** It only reaches so far into the silence. You have to place it close. */
    public static final double RANGE = 20.0;

    /** Exposure delivered per second while active and in range. */
    public static final double EXPOSURE_PER_SECOND = 0.25;

    private static final long NEVER = Long.MIN_VALUE;

    private final double x;
    private final double z;
    private long firedAtMs = NEVER;

    public Resonator(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public boolean canFire(long nowMs) {
        return firedAtMs == NEVER || nowMs - firedAtMs >= COOLDOWN_MS;
    }

    /**
     * Fire it.
     *
     * @return true if it actually fired
     */
    public boolean fire(long nowMs) {
        if (!canFire(nowMs)) return false;
        firedAtMs = nowMs;
        return true;
    }

    public boolean isActive(long nowMs) {
        return firedAtMs != NEVER && nowMs - firedAtMs < ACTIVE_MS;
    }

    /**
     * Exposure to apply this tick.
     *
     * @param deltaMs time since the last call
     */
    public double exposureThisTick(Quiet quiet, long nowMs, long deltaMs) {
        if (deltaMs < 0) throw new IllegalArgumentException("time does not run backwards");
        if (!isActive(nowMs)) return 0.0;
        if (quiet.distanceTo(x, z) > RANGE) return 0.0;
        return EXPOSURE_PER_SECOND * (deltaMs / 1000.0);
    }

    /**
     * How many full firings it takes to end the Quiet, assuming every one lands.
     * Used by the test suite to keep the fight from silently becoming a slog or
     * a formality when the constants get tuned.
     */
    public static double firingsToEnd() {
        double perFiring = EXPOSURE_PER_SECOND * (ACTIVE_MS / 1000.0);
        return Quiet.EXPOSURE_TO_END / perFiring;
    }
}
