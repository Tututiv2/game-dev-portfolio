package com.secondwatch.core;

/**
 * THE QUIET. The thing the Understudy has been walking you toward for five
 * nights while carefully never going there itself.
 *
 * <p>It is built as the exact inverse of the phantom, and that inversion is the
 * whole point of having a boss at all:
 *
 * <table border="1">
 *   <caption>the two entities</caption>
 *   <tr><th></th><th>Understudy</th><th>Quiet</th></tr>
 *   <tr><td>made of</td><td>stolen sound</td><td>the absence of it</td></tr>
 *   <tr><td>finds you by</td><td>listening</td><td>already knowing</td></tr>
 *   <tr><td>speed</td><td>walks like a person</td><td>slower, and it does not stop</td></tr>
 *   <tr><td>you beat it by</td><td>noticing</td><td>making it audible</td></tr>
 * </table>
 *
 * <p><b>Why this is not a bait-and-switch.</b> Five nights of learning to read a
 * voice would be wasted by a boss that simply ignores voice - that is the trap
 * the "different boss" idea usually falls into. It is avoided here by making the
 * skill <em>misfire</em> rather than go unused: players spend the whole run
 * learning that a voice cutting out means the phantom is up to something, and
 * inside the Quiet's field every voice cuts out, so their trained instinct
 * ("that's the Understudy, stay calm, find the seam") is precisely the wrong
 * response at precisely the wrong moment.
 *
 * <p>And it is foreshadowed from night one by absence: the Understudy will not
 * approach it, so the journal entry saying <i>it never comes down here</i> is
 * TRUE. It is not a false lead. It is why the last group went down there.
 */
public final class Quiet {

    /** Where it is. It does not teleport and it does not hurry. */
    private double x;
    private double z;

    private int phase = 1;
    private double exposure;

    /** Phases it advances through as the players force it to be heard. */
    public static final int MAX_PHASE = 3;

    /**
     * Total exposure needed to end it. Deliberately not "health": you are not
     * damaging it, you are making it audible, and the thing cannot survive being
     * perceived for long enough.
     */
    public static final double EXPOSURE_TO_END = 3.0;

    /**
     * Radius of total silence, by phase. It grows, so the safe pocket the
     * players learned in phase 1 has quietly stopped being safe by phase 3 -
     * without the mod ever telling them.
     */
    private static final double[] SILENCE_RADIUS = {0, 18.0, 26.0, 34.0};

    /**
     * Beyond the silence radius, sound fades back in over this distance rather
     * than snapping on. A hard edge reads as a bug; a gradient reads as dread,
     * and it gives players a usable gradient to navigate by when they have
     * nothing else.
     */
    public static final double FALLOFF = 22.0;

    /**
     * How close the Understudy will get. It herds you toward this circle and
     * will not cross it, which is what makes its behaviour legible in hindsight.
     */
    public double avoidRadius() {
        return silenceRadius() + FALLOFF;
    }

    public Quiet(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public int phase() {
        return phase;
    }

    public double exposure() {
        return exposure;
    }

    public boolean ended() {
        return exposure >= EXPOSURE_TO_END;
    }

    public double silenceRadius() {
        return SILENCE_RADIUS[Math.min(phase, MAX_PHASE)];
    }

    public double distanceTo(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * How much of a player's audio is eaten at this position: 1.0 is total
     * silence, 0.0 is untouched.
     *
     * <p>This applies to EVERYTHING - ambience, mobs, footsteps, music, and the
     * players' own voice chat. Especially the voice chat. A field that spared
     * the one channel the players rely on would be a boss that politely lets you
     * coordinate, and the entire point of this fight is that it does not.
     */
    public double suppressionAt(double px, double pz) {
        double d = distanceTo(px, pz);
        double r = silenceRadius();
        if (d <= r) return 1.0;
        if (d >= r + FALLOFF) return 0.0;
        double t = (d - r) / FALLOFF;
        // Smoothstep: no audible seam at either end of the gradient.
        return 1.0 - (t * t * (3 - 2 * t));
    }

    /** True where two players cannot hear each other at all. */
    public boolean silences(double px, double pz) {
        return suppressionAt(px, pz) >= 1.0;
    }

    /**
     * Feed exposure while a resonator is forcing sound into the field.
     *
     * <p>Advancing a phase makes it LOUDER and BIGGER, not weaker. Every stage
     * of winning makes the situation worse, which is the only honest way to
     * escalate a fight in a horror game - a boss that gets easier as it dies
     * turns the last minute of a five-night run into a victory lap.
     */
    public void expose(double amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("exposure cannot be negative: " + amount);
        }
        exposure = Math.min(EXPOSURE_TO_END, exposure + amount);
        int earned = 1 + (int) Math.floor(exposure / (EXPOSURE_TO_END / MAX_PHASE));
        phase = Math.min(MAX_PHASE, Math.max(phase, earned));
    }

    /** It walks toward a point. Slowly, and without ever stopping. */
    public void stepToward(double tx, double tz, double distance) {
        if (distance < 0) throw new IllegalArgumentException("the Quiet does not walk backwards");
        double dx = tx - x;
        double dz = tz - z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return;
        double step = Math.min(distance, len);
        x += (dx / len) * step;
        z += (dz / len) * step;
    }
}
