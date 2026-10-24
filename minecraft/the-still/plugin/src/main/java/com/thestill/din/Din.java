package com.thestill.din;

/**
 * One player's noise, right now.
 *
 * <p>Pure state and arithmetic — no Bukkit, no scheduler, no player. That is
 * deliberate: this is the number the entire game hangs off, so it should be
 * provable at a desk rather than only observable on a server.
 *
 * <p>Din is a <em>moment</em>. It accrues per block broken and bleeds away a few
 * points a second the instant you stop, so it measures what you are doing, never
 * what you have done. (Heat, in Phase 4, is the opposite — it measures the room
 * and it lasts hours. Do not merge them.)
 */
public final class Din {

    /** Hard ceiling. Above CHOIR there is nothing louder to become. */
    public static final double MAX = 4000.0;

    private double value;
    private DinBand band = DinBand.QUIET;

    /**
     * Adds noise.
     *
     * @param amount points of Din; typically base 1.0 per block, scaled by
     *               enchants and by how many blocks an AoE break actually broke
     * @return the band after this, which may be the same one
     */
    public DinBand add(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return band;
        }
        return set(value + amount);
    }

    /**
     * Bleeds noise off.
     *
     * @param perSecond decay rate, 4/sec in the spec
     * @param seconds   elapsed time
     */
    public DinBand decay(double perSecond, double seconds) {
        if (!Double.isFinite(perSecond) || !Double.isFinite(seconds)
                || perSecond <= 0 || seconds <= 0) {
            return band;
        }
        return set(value - perSecond * seconds);
    }

    private DinBand set(double raw) {
        // Clamped both ends, and non-finite input can never get in: a NaN Din
        // would put a player in an undefined band forever and there would be no
        // way back out short of a database edit.
        value = Double.isFinite(raw) ? Math.clamp(raw, 0.0, MAX) : value;
        band = DinBand.next(band, value);
        return band;
    }

    public double value() {
        return value;
    }

    public DinBand band() {
        return band;
    }

    /** The sell multiplier to stamp onto anything broken <em>at this instant</em>. */
    public double multiplier() {
        return band.multiplier();
    }

    /**
     * How far through the current band, 0..1.
     *
     * <p>Per band rather than across the whole range, so the bossbar refills
     * from empty at every threshold and each band feels like its own climb
     * instead of one long crawl to CHOIR.
     */
    public double progressThroughBand() {
        DinBand[] all = DinBand.values();
        int index = band.ordinal();
        double floor = band.threshold();
        if (index + 1 >= all.length) {
            // In CHOIR there is no next threshold; scale over the same span as
            // the band below so the bar still moves rather than sitting full.
            double span = band.threshold() - all[index - 1].threshold();
            return Math.clamp((value - floor) / span, 0.0, 1.0);
        }
        double ceiling = all[index + 1].threshold();
        return Math.clamp((value - floor) / (ceiling - floor), 0.0, 1.0);
    }

    /** Resets to silence. Used on death and on quit. */
    public void clear() {
        value = 0.0;
        band = DinBand.QUIET;
    }

    @Override
    public String toString() {
        return String.format("Din[%.1f %s x%.2f]", value, band.label(), band.multiplier());
    }
}
