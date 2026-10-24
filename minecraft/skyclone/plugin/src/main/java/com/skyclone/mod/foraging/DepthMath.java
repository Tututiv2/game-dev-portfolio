package com.skyclone.mod.foraging;

/**
 * Diving under the foraging islands (research 01 §2.2: Respiration base 30, Pressure Resistance base 0;
 * research 03: the Spring Depths "need Respiration + Pressure Resistance"; the Moonmarsh's depths lie
 * under its loch). Pure, so the unit tests run the shipping numbers. The research gives the stats but no
 * formulas, so the shapes below are ours (// TUNE, every knob is a dial):
 * <ul>
 *   <li><b>Breath</b>: vanilla air is 300 ticks for the base 30 Respiration, so one point = 10 ticks
 *       (half a second). Respiration IV (+60) triples it.</li>
 *   <li><b>Pressure</b>: {@code depth x per-block} % below the water surface. Pressure Resistance cancels it
 *       point for point; what is left hurts (a fraction of max health each second) and slows you.</li>
 * </ul>
 */
public final class DepthMath {

    private DepthMath() {}

    /** Research 01 §2.2: everyone starts with 30 Respiration. */
    public static final double BASE_RESPIRATION = 30;

    /** Air ticks for a Respiration value (min one second). */
    public static int maxAirTicks(double respiration, double ticksPerPoint) {
        return (int) Math.max(20, Math.round(Math.max(0, respiration) * Math.max(0, ticksPerPoint)));
    }

    /** Pressure at {@code depth} blocks below the surface. */
    public static double pressure(double depth, double perBlock) { return Math.max(0, depth) * Math.max(0, perBlock); }

    /** What resistance does not cancel. */
    public static double effective(double pressure, double resistance) { return Math.max(0, pressure - Math.max(0, resistance)); }

    /**
     * Fraction of max health lost per second: {@code percentPer10} % for every 10 points of uncancelled
     * pressure, capped at 20 % a second.
     */
    public static double damageFraction(double effective, double percentPer10) {
        if (effective <= 0) return 0;
        return Math.min(0.20, effective / 10.0 * Math.max(0, percentPer10) / 100.0);
    }

    /** Pressure Resistance needed to dive {@code depth} blocks with no harm. */
    public static double resistanceFor(double depth, double perBlock) { return pressure(depth, perBlock); }
}
