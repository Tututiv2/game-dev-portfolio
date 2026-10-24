package com.skyclone.mod.dungeons.model;

/**
 * Pure numbers of the Catacombs (research 04 sec 3.3, 3.12, 3.16). No Bukkit: unit-tested offline.
 *
 * <p>The XP table is the reference table (identical to NEU constants/leveling.json "catacombs"), shared
 * by the Catacombs skill and all five class levels. The dungeon stat boost is the research column
 * "Dungeon stat boost" (percent added to a dungeon item's base stats while inside the Catacombs).
 */
public final class CataMath {

    private CataMath() {}

    /** XP needed for each level 1..50 (index 0 = level 1). */
    public static final long[] LEVEL_XP = {
            50, 75, 110, 160, 230, 330, 470, 670, 950, 1340,
            1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000, 27600, 38000,
            52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000,
            1065000, 1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000,
            15000000, 19000000, 24000000, 30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000};

    /** Beyond 50 every level costs this much (cosmetic levels). */
    public static final long OVERFLOW_XP = 200_000_000L;

    public static final int MAX_LEVEL = 50;

    /** Cumulative XP to REACH level {@code lvl} (0 for level 0). Level 50 = 569,809,640. */
    public static long cumulative(int lvl) {
        long t = 0;
        for (int i = 0; i < Math.min(lvl, MAX_LEVEL); i++) t += LEVEL_XP[i];
        if (lvl > MAX_LEVEL) t += (long) (lvl - MAX_LEVEL) * OVERFLOW_XP;
        return t;
    }

    /** Level for a total XP, capped at {@code cap} (50 normally; higher caps count overflow levels). */
    public static int level(double xp, int cap) {
        int lvl = 0;
        double left = xp;
        while (lvl < cap) {
            long need = lvl < MAX_LEVEL ? LEVEL_XP[lvl] : OVERFLOW_XP;
            if (left < need) break;
            left -= need;
            lvl++;
        }
        return lvl;
    }

    public static int level(double xp) { return level(xp, MAX_LEVEL); }

    /** XP into the current level. */
    public static double into(double xp, int cap) { return xp - cumulative(level(xp, cap)); }

    /** XP the next level costs (0 when capped). */
    public static double next(double xp, int cap) {
        int l = level(xp, cap);
        if (l >= cap) return 0;
        return l < MAX_LEVEL ? LEVEL_XP[l] : OVERFLOW_XP;
    }

    /** 0..1 progress to the next level (1 when capped). */
    public static double progress(double xp, int cap) {
        double n = next(xp, cap);
        return n <= 0 ? 1 : Math.min(1, into(xp, cap) / n);
    }

    /**
     * Dungeon stat boost in percent at a Catacombs level (research 04 sec 3.3): 15/20/25/31/38/46/55/65/75/85
     * for levels 1-10, then +10 per level to 485 at 50. Level 0 = no boost.
     */
    public static double statBoostPercent(int level) {
        if (level <= 0) return 0;
        int[] early = {15, 20, 25, 31, 38, 46, 55, 65, 75, 85};
        if (level <= 10) return early[level - 1];
        return 85 + 10.0 * (Math.min(level, MAX_LEVEL) - 10);
    }

    // ---- score (research 04 sec 3.12) ----------------------------------------------------------

    /** Skill: 100 - 2 per death - 14 per failed/unfinished puzzle, floor 20 (the genre's known minimum; TUNE). */
    public static int skillScore(int deaths, int failedPuzzles, boolean spiritFirstDeath, int floorMin) {
        return skillScore(deaths, failedPuzzles, spiritFirstDeath, floorMin, 14);
    }

    public static int skillScore(int deaths, int failedPuzzles, boolean spiritFirstDeath, int floorMin, int puzzlePenalty) {
        double d = deaths * 2.0;
        if (spiritFirstDeath && deaths > 0) d -= 1;
        int s = (int) Math.floor(100 - d - (double) puzzlePenalty * failedPuzzles);
        return Math.max(floorMin, Math.min(100, s));
    }

    /** Exploration: floor(60 x cleared/total) + floor(min(40, 40 x secrets / (req x totalSecrets))). */
    public static int explorationScore(int clearedRooms, int totalRooms, int secretsFound, int totalSecrets, double secretPct) {
        int rooms = totalRooms <= 0 ? 60 : (int) Math.floor(60.0 * Math.min(clearedRooms, totalRooms) / totalRooms);
        int secrets;
        double needed = secretPct * totalSecrets;
        if (needed <= 0) secrets = 40;
        else secrets = (int) Math.floor(Math.min(40.0, 40.0 * secretsFound / needed));
        return rooms + secrets;
    }

    /**
     * Speed: 100 inside the limit; past it -1 per 12 s down to 90, -1 per 24 s down to 80, -1 per 30 s down to 70,
     * then -1 per 40 s. Never below 0.
     */
    public static int speedScore(long seconds, long limitSeconds) {
        long over = seconds - limitSeconds;
        if (over <= 0) return 100;
        double lost;
        if (over <= 120) lost = over / 12.0;
        else if (over <= 120 + 240) lost = 10 + (over - 120) / 24.0;
        else if (over <= 360 + 300) lost = 20 + (over - 360) / 30.0;
        else lost = 30 + (over - 660) / 40.0;
        return (int) Math.max(0, Math.floor(100 - lost));
    }

    /** Bonus: crypts (max 5) + mimic 2 + mayor perk + prince 1, capped at 17. */
    public static int bonusScore(int crypts, boolean mimic, int perkBonus, boolean prince) {
        int b = Math.min(5, Math.max(0, crypts)) + (mimic ? 2 : 0) + Math.max(0, perkBonus) + (prince ? 1 : 0);
        return Math.min(17, b);
    }

    public static String grade(int score) {
        if (score >= 300) return "S+";
        if (score >= 270) return "S";
        if (score >= 230) return "A";
        if (score >= 160) return "B";
        if (score >= 100) return "C";
        return "D";
    }

    /** Legacy colour code of a grade. */
    public static String gradeColor(String grade) {
        return switch (grade) {
            case "S+" -> "&6";
            case "S" -> "&e";
            case "A" -> "&5";
            case "B" -> "&a";
            case "C" -> "&9";
            default -> "&c";
        };
    }

    /**
     * Run XP: base x clamp(score / 300, minFactor, 1) (research 04 sec 3.16, (?) scaling), +40% for each of the
     * first 5 completions of a real day.
     */
    public static double runXp(double baseXp, int score, boolean dailyBonus, double minFactor) {
        double f = Math.max(minFactor, Math.min(1.0, score / 300.0));
        return baseXp * f * (dailyBonus ? 1.4 : 1.0);
    }
}
