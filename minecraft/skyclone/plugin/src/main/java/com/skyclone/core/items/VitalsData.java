package com.skyclone.core.items;

/**
 * Persisted as member data "vitals" so a relog keeps current health and mana.
 * Negative = never set (a fresh profile starts full).
 */
public class VitalsData {
    public double health = -1;
    public double mana = -1;
    public double absorption = 0;
    /**
     * The maxima {@link #health} / {@link #mana} were last measured against, so a change of max
     * (armour swap, relog before every provider is back) rescales the fill fraction instead of
     * clamping. Negative = unknown (old saves): the first change just clamps.
     */
    public double maxHealth = -1;
    public double maxMana = -1;

    public VitalsData() {}
}
