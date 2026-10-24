package com.skyclone.mod.foraging;

/**
 * Honey hives and lathered trees (research 07 §4.1 "Honeycomb: Galatea hives", §4.5 the daily Honeycomb
 * perk: "hives refill 20% faster / critters 20% faster / +2 honeycomb / 25% 2nd critter"; NEU pot lore:
 * 60 / 30 / 15 minutes, Behemoth at once). Pure. The base hive numbers are ours (// TUNE, dials).
 */
public final class HiveMath {

    private HiveMath() {}

    /** The four faces of the daily Honeycomb perk (research 07 §4.5). */
    public static final int DAILY_REFILL = 0, DAILY_LURE = 1, DAILY_COMB = 2, DAILY_SECOND = 3;
    /** The four faces of the daily Fortune perk: +50 Fig / Mangrove / Spiral Fortune, or +10 Sweep. */
    public static final int DAILY_FIG = 0, DAILY_MANGROVE = 1, DAILY_HELIX = 2, DAILY_SWEEP = 3;

    /** Milliseconds until a harvested hive is full again: base x 0.8 with the daily refill face. */
    public static long refillMillis(double baseSeconds, boolean dailyFaster) {
        double s = Math.max(1, baseSeconds) * (dailyFaster ? 0.8 : 1.0);
        return Math.round(s * 1000);
    }

    /** Honeycomb in one harvest: base, +2 with the daily face, doubled by Honey Haul. */
    public static int honeycomb(int base, boolean dailyExtra, boolean doubled) {
        int n = Math.max(0, base) + (dailyExtra ? 2 : 0);
        return doubled ? n * 2 : n;
    }

    /**
     * How long a lathered tree takes to lure its critter: the pot's minutes, cut by every "attract mobs faster"
     * percent (Honeycomb talismans 5/10/15, Hivehog attribute, the daily face 20), never below 10 % of the wait.
     */
    public static long latherMillis(double minutes, double fasterPercent) {
        if (minutes <= 0) return 0;
        double k = Math.max(0.10, 1 - Math.max(0, fasterPercent) / 100.0);
        return Math.round(minutes * 60_000 * k);
    }

    /**
     * A stable per-player, per-day pick among {@code n} faces (the daily random perks): the same all day,
     * different between players and days.
     */
    public static int dailyPick(long uuidMost, long uuidLeast, long epochDay, String salt, int n) {
        long h = uuidMost * 0x9E3779B97F4A7C15L ^ uuidLeast ^ (epochDay * 0xC2B2AE3D27D4EB4FL) ^ (salt == null ? 0 : salt.hashCode() * 0x165667B19E3779F9L);
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33;
        return (int) Math.floorMod(h, (long) Math.max(1, n));
    }

    /** Digits in a collection count, capped at 10M (8 digits): the "+X per digit" attributes. */
    public static int digits(long count) {
        if (count <= 0) return 0;
        return String.valueOf(Math.min(count, 10_000_000L)).length();
    }
}
