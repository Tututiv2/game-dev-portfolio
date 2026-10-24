package com.skyclone.mod.farming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure arithmetic behind the effects other modules' items and pets have on farming (wave 5): per-crop fortune
 * written as {@code otherStats} on accessories / pets, Overbloom, and the trapper's pelt bonuses. No Bukkit, so
 * the unit tests run the same code the server does.
 */
public final class PerkMath {

    private PerkMath() {}

    /**
     * The {@code otherStats} keys that mean "fortune for this crop", most specific first. The accessories data spells
     * melon as the slice ({@code MELON_SLICE_FORTUNE}) and cocoa as the beans; the older spellings are accepted too.
     * Only the FIRST key present counts, so an item carrying two spellings is never paid twice.
     */
    public static List<String> statKeys(Crop c) {
        return switch (c) {
            case MELON -> List.of("MELON_SLICE_FORTUNE", "MELON_FORTUNE");
            case COCOA -> List.of("COCOA_BEANS_FORTUNE", "COCOA_FORTUNE");
            case NETHER_WART -> List.of("NETHER_WART_FORTUNE", "NETHER_STALK_FORTUNE");
            case MUSHROOM -> List.of("MUSHROOM_FORTUNE", "RED_MUSHROOM_FORTUNE");
            default -> List.of(c.name() + "_FORTUNE");
        };
    }

    /** This crop's fortune in an {@code otherStats} object (0 when absent). Keys are matched case-blind. */
    public static double cropFortune(JsonObject otherStats, Crop c) {
        if (otherStats == null) return 0;
        for (String k : statKeys(c)) {
            double v = number(otherStats, k);
            if (v != 0) return v;
        }
        return 0;
    }

    /** A number under {@code key} (case-blind), 0 when missing or not a number. */
    public static double number(JsonObject o, String key) {
        if (o == null || key == null) return 0;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(key)) continue;
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return v.getAsDouble();
        }
        return 0;
    }

    /** True when this otherStats object carries any crop fortune or Overbloom (worth indexing). */
    public static boolean farmingRelevant(JsonObject otherStats) {
        if (otherStats == null) return false;
        if (number(otherStats, "OVERBLOOM") != 0) return true;
        for (Crop c : Crop.values()) if (cropFortune(otherStats, c) != 0) return true;
        return false;
    }

    /** An accessory that carries farming numbers: its id, family (accessory upgrade line) and rank in it. */
    public record Accessory(String id, String family, int rank, JsonObject otherStats) {
        String familyKey() { return family == null || family.isBlank() ? id : family; }
    }

    /**
     * The reference's accessory-family rule: of several accessories in one upgrade line (Cropling Talisman, Squash
     * Ring, Fermenta Artifact) only the highest counts. Input = the ones the player has; output = what counts, in
     * input order of each family's winner.
     */
    public static List<Accessory> counted(List<Accessory> owned) {
        Map<String, Accessory> best = new LinkedHashMap<>();
        for (Accessory a : owned) {
            Accessory cur = best.get(a.familyKey());
            if (cur == null || a.rank() > cur.rank()) best.put(a.familyKey(), a);
        }
        return new ArrayList<>(best.values());
    }

    /**
     * The level whose stats a pet shows (mirrors the pets module's rule): plain pets 1..100; a curve "min:max:..."
     * (the level-200 dragons) maps min..max onto 1..100 and anything below min onto 1.
     */
    public static int petStatLevel(String curve, int level) {
        if (curve == null || curve.isEmpty()) return Math.max(1, Math.min(100, level));
        String[] s = curve.split(":");
        try {
            int min = Integer.parseInt(s[0]), max = Integer.parseInt(s[1]);
            if (level < min) return 1;
            if (level <= max) return level - min + 1;
            return max - min + 1;
        } catch (RuntimeException e) {
            return Math.max(1, Math.min(100, level));
        }
    }

    /** Linear interpolation between a pet's level-1 and level-100 numbers. */
    public static double petLerp(double at1, double at100, int statLevel) {
        int l = Math.max(1, Math.min(100, statLevel));
        return at1 + (at100 - at1) * (l - 1) / 99.0;
    }

    /**
     * Overbloom ("chance for rare crops", research 01 stat table) as a multiplier on the Harvest Fair's rare-crop
     * chance: {@code 1 + overbloom x percentPerPoint / 100}. TUNE: the reference gives no formula.
     */
    public static double overbloomMultiplier(double overbloom, double percentPerPoint) {
        return Math.max(0, 1 + Math.max(0, overbloom) * percentPerPoint / 100.0);
    }

    /**
     * Pelts a tracker quest pays: the quarry's own pelts, +1 for each flat bonus (Tracker's Plushie, Pelt Belt), then
     * x the Trapper Crest's multiplier (1.1), rounded half up. TUNE: the reference does not say which applies first;
     * flat-then-multiply is the more generous order and never pays less than the quarry's own number.
     */
    public static long pelts(long base, int flatBonus, double multiplier) {
        double v = (Math.max(0, base) + Math.max(0, flatBonus)) * Math.max(1.0, multiplier);
        return Math.max(base, (long) Math.floor(v + 0.5));
    }

    /** Masked Hopper's Cape: which medal the bonus pays - one of the best kind the bracket gave ("gold" / "silver" / "bronze"). */
    public static String bonusMedal(int gold, int silver, int bronze) {
        if (gold > 0) return "gold";
        if (silver > 0) return "silver";
        if (bronze > 0) return "bronze";
        return null;
    }
}
