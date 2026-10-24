package com.skyclone.core.combat;

import com.skyclone.api.mob.DropDef;

/** Pure loot-roll maths (research 01 §9). No Bukkit. */
public final class LootMath {

    private LootMath() {}

    /**
     * Effective drop chance. Magic Find multiplies the chance by (1 + mf/100) only for drops
     * whose BASE chance is below {@code threshold} (5% in the genre) and that allow it.
     * Result is clamped to 1.
     */
    public static double chance(DropDef d, double magicFind, double threshold, double mfCap) {
        double c = Math.max(0, d.chance);
        if (d.magicFind && c < threshold) {
            double mf = Math.max(0, Math.min(magicFind, mfCap));
            c *= 1 + mf / 100.0;
        }
        return Math.min(1.0, c);
    }

    /** True when Magic Find changed this drop's chance (so the message shows "(+X% ✯ Magic Find)"). */
    public static boolean mfApplies(DropDef d, double magicFind, double threshold) {
        return d.magicFind && d.chance < threshold && magicFind > 0;
    }

    /** Amount between min..max inclusive from a uniform [0,1) roll. Tolerates max &lt; min. */
    public static int amount(DropDef d, double roll) {
        int lo = Math.max(0, Math.min(d.min, d.max));
        int hi = Math.max(d.min, d.max);
        if (hi <= lo) return lo;
        int span = hi - lo + 1;
        int k = (int) Math.floor(roll * span);
        if (k >= span) k = span - 1;
        return lo + k;
    }
}
