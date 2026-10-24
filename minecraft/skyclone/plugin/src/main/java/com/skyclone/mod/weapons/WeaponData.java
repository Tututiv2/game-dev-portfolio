package com.skyclone.mod.weapons;

/**
 * Per-player weapons state (member key "weapons"). Gson-friendly: add fields, never retype them.
 */
public final class WeaponData {
    /** Preferred arrow id ("GOLD_TIPPED_ARROW"); null/empty = use whatever comes first. */
    public String selectedArrow;
    /** Use arrow poisons found in the inventory/quiver. */
    public boolean usePoisons = true;
    /** Lifetime arrows shot (shown in the arsenal menu). */
    public long arrowsShot;
    /** Lifetime ability casts (shown in the arsenal menu). */
    public long abilitiesCast;
    /** Primal Fears slain (the Eerie pet's Fearcreasing counts the first 150). Wave 5. */
    public long primalFears;

    public WeaponData() {}
}
