package com.skyclone.mod.enchanting;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Pure rules behind the wave-5 enchants (no Bukkit, unit tested): which creatures are TIMID for Stealth, the
 * Stealth roll, Pressure Resistance totals for Scuba, and the served-visitor count Green Thumb reads back out of
 * the garden's {@code garden:unique:N} requirement.
 */
public final class Wave5Math {

    private Wave5Math() {}

    /** Vanilla creatures that run from a player on sight (they have an avoid-player goal). */
    public static final Set<String> VANILLA_AVOIDERS = Set.of("RABBIT", "FOX", "OCELOT", "ARMADILLO");

    /** Mob flags that mark a creature as timid (any module may set one on its MobDef). */
    public static final Set<String> TIMID_FLAGS = Set.of("timid", "skittish", "flees");

    /**
     * A sky mob is timid when its MobDef says so: a timid flag, {@code extra.timid: true}, or a hunting critter
     * ({@code extra.critter: true}) that is not aggressive.
     */
    public static boolean timidDef(Collection<String> flags, boolean extraTimid, boolean critter) {
        boolean aggressive = false;
        if (flags != null) {
            for (String f : flags) {
                if (f == null) continue;
                String l = f.toLowerCase(Locale.ROOT);
                if (TIMID_FLAGS.contains(l)) return true;
                if (l.equals("aggressive") || l.equals("hostile")) aggressive = true;
            }
        }
        if (extraTimid) return true;
        return critter && !aggressive;
    }

    /** True when a vanilla entity type (enum name) runs from players. */
    public static boolean vanillaAvoider(String entityType) {
        return entityType != null && VANILLA_AVOIDERS.contains(entityType.toUpperCase(Locale.ROOT));
    }

    /** The Stealth roll: {@code roll} in [0,1) holds the creature still when it is below {@code pct}%. */
    public static boolean holdsStill(double pct, double roll) {
        return pct > 0 && roll * 100.0 < Math.min(100.0, pct);
    }

    /**
     * Largest n in [0, cap] with {@code atLeast.test(n)} true, for a monotone predicate ("served at least n unique
     * visitors"). Binary search: 8 checks for cap 255. Returns 0 when even 1 fails.
     */
    public static int largestTrue(IntPredicate atLeast, int cap) {
        int lo = 0, hi = Math.max(0, cap);
        if (hi == 0 || !atLeast.test(1)) return 0;
        lo = 1;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (atLeast.test(mid)) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /**
     * Rend: each of your arrows stuck in a target (at most {@code maxArrows}) deals {@code pct}% of your last critical
     * shot ON THAT TARGET. 0 when you never crit it or no arrow of yours is in it.
     */
    public static double rendDamage(double lastCritOnTarget, double pct, int arrowsStuck, int maxArrows) {
        if (lastCritOnTarget <= 0 || pct <= 0 || arrowsStuck <= 0) return 0;
        return lastCritOnTarget * pct / 100.0 * Math.min(arrowsStuck, Math.max(1, maxArrows));
    }

    /** Paleontologist: the bonus chance of a Suspicious Scrap on top of the base roll ({@code pct}% of base). */
    public static double paleontologistChance(double base, double pct) {
        return Math.max(0, base) * Math.max(0, pct) / 100.0;
    }

    /** Parse "pressure_resistance:N" style key parts; N defaults to 1, bad numbers to MAX (closed by default). */
    public static double threshold(String[] parts) {
        if (parts == null || parts.length < 2) return 1;
        try { return Double.parseDouble(parts[1].trim()); } catch (NumberFormatException e) { return Double.MAX_VALUE; }
    }
}
