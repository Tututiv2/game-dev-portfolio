package com.skyclone.mod.dungeons.model;

/**
 * Wave 5 (2026-10-06): the small, pure rules behind the dungeon accessory / pet / star / Master Mode effects, kept here
 * so they can be unit-tested offline ({@code DungeonPerksTest}) and read the same way by the live code.
 * Sources: research 04 sec 3.4, 3.5, 3.12, 3.15; research 01 sec 16; the pets module's {@code pets.json}.
 */
public final class DungeonPerks {

    private DungeonPerks() {}

    // ---- stars -------------------------------------------------------------------------------------------------------

    /**
     * Fraction added to a dungeon item's base stats by its stars INSIDE the Catacombs (on top of the Catacombs-level
     * boost). Research 04 sec 3.15: +10% per essence star in the Catacombs (2% of which the reforges module already
     * applies everywhere, so this adds {@code starBoost - 0.02}); master stars (stars 6-10) +5% each, and research 01
     * sec 16.2: <b>only in Master Mode</b>. Stars are capped at 5 + 5.
     */
    public static double starFraction(int stars, boolean masterRun, double starBoost, double masterStarBoost) {
        int s = Math.max(0, stars);
        int essence = Math.min(5, s);
        int master = Math.min(5, Math.max(0, s - 5));
        double f = essence * Math.max(0, starBoost - 0.02);
        if (masterRun) f += master * Math.max(0, masterStarBoost);
        return f;
    }

    // ---- accessories -------------------------------------------------------------------------------------------------

    /**
     * General's Medallion: dungeon gear's base stats +1% per DIGIT of the player's lifetime secrets found, max 5%
     * (research 04 sec 3.15). 0 secrets = 0%, 9 = 1%, 10 = 2%, 12,345 = 5%, 1,000,000 = 5%.
     */
    public static int medallionPercent(long totalSecrets) {
        if (totalSecrets <= 0) return 0;
        return Math.min(5, Long.toString(totalSecrets).length());
    }

    /** Bone Necklace: a Tank's Diversion reaches this many blocks further (the accessories data says +15; TUNE). */
    public static final double BONE_NECKLACE_DIVERSION_BONUS = 15;

    // ---- pets ----------------------------------------------------------------------------------------------------------

    /** Spirit pet "Half Life" (the first death of the run costs 1 Skill score): Legendary (or higher) Spirit only. */
    public static boolean halfLife(String petType, String rarity) {
        if (petType == null || !petType.equalsIgnoreCase("SPIRIT")) return false;
        if (rarity == null) return false;
        String r = rarity.toUpperCase(java.util.Locale.ROOT);
        return r.equals("LEGENDARY") || r.equals("MYTHIC");
    }

    /**
     * Jellyfish "Stored Charge": every {@code per} (2,000) health a Healer heals on allies trims {@code secondsPer}
     * seconds off their Wish cooldown, at most {@code cap} (30 s) per cooldown. Returns the TOTAL trim in seconds for
     * {@code healedSinceWish} health healed since the last Wish.
     */
    public static double storedChargeTrim(double healedSinceWish, double per, double secondsPer, double cap) {
        if (!(healedSinceWish > 0) || !(per > 0) || !(secondsPer > 0)) return 0;
        return Math.min(Math.max(0, cap), Math.floor(healedSinceWish / per) * secondsPer);
    }

    /** Jellyfish "Strong Brew": dungeon potion stats x (1 + pct/100). */
    public static double strongBrew(double potionValue, double pct) {
        return potionValue * (1 + Math.max(0, pct) / 100.0);
    }

    /** Spirit "Restless": ghost ability cooldowns shortened by {@code pct}% (never below 1 second). */
    public static long restlessCooldown(long cooldownMs, double pct) {
        double f = 1 - Math.max(0, Math.min(90, pct)) / 100.0;
        return Math.max(1000L, Math.round(cooldownMs * f));
    }

    // ---- classes -------------------------------------------------------------------------------------------------------

    /**
     * Healer "Overheal" (research 04 sec 3.4): healing past full health becomes absorption, up to 20% of the target's
     * max health, +0.4% per Healer level (TUNE: research gives the step, not whether 20% is the level-0 or the cap value).
     */
    public static double overhealCapFraction(int healerLevel) {
        return 0.20 + 0.004 * Math.max(0, Math.min(50, healerLevel));
    }

    /** Overflow absorption granted for a heal: {@code min(overflow, cap - current absorption)}, never negative. */
    public static double overheal(double health, double max, double heal, double absorption, double capFraction) {
        double overflow = health + heal - max;
        if (!(overflow > 0) || !(max > 0)) return 0;
        double room = max * capFraction - Math.max(0, absorption);
        return Math.max(0, Math.min(overflow, room));
    }

    /**
     * Berserk "Indomitable" (research 04 sec 3.4: "gain a % of Strength as Defense"): 10% of Strength, +0.2% per Berserk
     * level (TUNE: the research gives no percentage).
     */
    public static double indomitable(double strength, int berserkLevel) {
        if (!(strength > 0)) return 0;
        return strength * (0.10 + 0.002 * Math.max(0, Math.min(50, berserkLevel)));
    }

    // ---- floors --------------------------------------------------------------------------------------------------------

    /**
     * The floor that must have been completed before {@code floorId} opens (the reference's floor gate): F2..F7 need the
     * floor below, M2..M7 the Master floor below (M1's own requirement, F7, is in its data). Null when none.
     */
    public static String previousFloor(String floorId) {
        if (floorId == null || floorId.length() < 2) return null;
        char kind = Character.toUpperCase(floorId.charAt(0));
        int n;
        try { n = Integer.parseInt(floorId.substring(1)); } catch (NumberFormatException e) { return null; }
        if ((kind != 'F' && kind != 'M') || n <= 1 || n > 7) return null;
        return String.valueOf(kind) + (n - 1);
    }
}
