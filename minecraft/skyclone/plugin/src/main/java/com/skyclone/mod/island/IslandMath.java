package com.skyclone.mod.island;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The private island's numbers, with no Bukkit in sight so unit tests can run them.
 *
 * <p>Island Size (research 05 §6.4, "Profile upgrades"): tier 0 is 161 blocks wide and each of the ten
 * Community Shop tiers adds 8, reaching 241 at tier X. Guests Limit adds slots on top of a base the
 * research does not state (TUNE dial, 5).
 */
public final class IslandMath {

    private IslandMath() {}

    /** Grid spacing of the instanced world. Never change it once islands exist: slots are stored as grid cells. */
    public static final int SPACING = 1024;
    /** Research 05 §6.4: Island Size 0 = 161 x 161. */
    public static final int BASE_SIZE = 161;
    /** Research 05 §6.4: Island Size X = 241 x 241 over ten tiers, so +8 per tier. */
    public static final int SIZE_PER_TIER = 8;
    public static final int MAX_TIER = 10;

    /** Width of the build area for an Island Size tier (0..10), using {@code base}/{@code perTier}. */
    public static int sizeForTier(int tier, int base, int perTier) {
        int t = Math.max(0, Math.min(MAX_TIER, tier));
        return base + t * perTier;
    }

    /** Width for a tier when an explicit per-tier table (tier 1..n -> width) is known; falls back to the formula. */
    public static int sizeForTier(int tier, int base, int perTier, int[] table) {
        if (tier <= 0) return base;
        if (table != null && tier <= table.length && table[tier - 1] > 0) return table[tier - 1];
        return sizeForTier(tier, base, perTier);
    }

    /** Half width (blocks either side of the centre column) of an odd-width square. */
    public static int half(int size) { return Math.max(0, (size - 1) / 2); }

    /** Instance origin coordinate (x or z) of the grid cell containing {@code coord}. */
    public static long originOf(double coord, int spacing) {
        int sp = Math.max(16, spacing);
        return Math.round(coord / sp) * (long) sp;
    }

    /** Is a template-relative block position inside the build square of {@code size} and the y range? */
    public static boolean insideBuild(double dx, double y, double dz, int size, int minY, int maxY) {
        int h = half(size);
        return Math.floor(dx) >= -h && Math.floor(dx) <= h && Math.floor(dz) >= -h && Math.floor(dz) <= h
                && y >= minY && y <= maxY + 1;
    }

    /** Guest slots: base plus the Guests Limit upgrade's extra slots. */
    public static int guestLimit(int base, int extra) { return Math.max(0, base) + Math.max(0, extra); }

    // ---- visitor policy ---------------------------------------------------------------------

    public static final List<String> VISITOR_MODES = List.of("anyone", "coop", "nobody");

    public static String normalizeVisitors(String v) {
        if (v == null) return "anyone";
        String s = v.trim().toLowerCase(Locale.ROOT);
        return VISITOR_MODES.contains(s) ? s : "anyone";
    }

    /** The stricter of two modes (nobody > coop > anyone). */
    public static String stricter(String a, String b) {
        String x = normalizeVisitors(a), y = normalizeVisitors(b);
        return VISITOR_MODES.indexOf(x) >= VISITOR_MODES.indexOf(y) ? x : y;
    }

    /** May a non-member visit under this mode? ("coop" admits only co-op members, who are not guests.) */
    public static boolean guestsAllowed(String mode) { return "anyone".equals(normalizeVisitors(mode)); }

    public static String visitorsLabel(String mode) {
        return switch (normalizeVisitors(mode)) {
            case "coop" -> "&eCo-op only";
            case "nobody" -> "&cNobody";
            default -> "&aAnyone";
        };
    }

    // ---- starter kit ---------------------------------------------------------------------------

    public record KitEntry(String id, int amount) {}

    /** "ROOKIE_PICKAXE:1,BREAD:8" -> entries. Bad amounts become 1; blanks are skipped; ids upper-cased. */
    public static List<KitEntry> parseKit(String spec) {
        List<KitEntry> out = new ArrayList<>();
        if (spec == null) return out;
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int i = p.lastIndexOf(':');
            String id = p;
            int n = 1;
            if (i > 0) {
                try { n = Integer.parseInt(p.substring(i + 1).trim()); id = p.substring(0, i).trim(); }
                catch (NumberFormatException e) { id = p; n = 1; }
            }
            if (id.isEmpty()) continue;
            out.add(new KitEntry(id.toUpperCase(Locale.ROOT), Math.max(1, Math.min(64, n))));
        }
        return out;
    }

    // ---- quarry --------------------------------------------------------------------------------

    /** Blocks the island quarry grows back (material names, lower case). */
    public static final List<String> QUARRY_BLOCKS = List.of("stone", "cobblestone", "andesite", "coal_ore", "iron_ore",
            "mossy_cobblestone", "diorite", "granite", "tuff");

    public static boolean regrows(String materialName) {
        return materialName != null && QUARRY_BLOCKS.contains(materialName.toLowerCase(Locale.ROOT));
    }
}
