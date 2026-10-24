package com.skyclone.mod.hollows.gen;

import java.util.List;

/**
 * The crystal caverns' zones and what lives in them. Shared by the generator (palettes, gem veins)
 * and the module (sidebar, guide menu, self-tests). Pure Java.
 *
 * <p>Zone ids deliberately carry the keyword the mining module reads to decide a zone's gemstone
 * and its heat ("jungle", "mithril", "precursor", "goblin", "magma", "fairy"); display names are ours.
 */
public final class Zones {

    private Zones() {}

    public record Zone(String id, String name, char color, String gem, String glass) {}

    public static final Zone JUNGLE = new Zone("jungle", "Jungle", 'a', "AMETHYST", "purple");
    public static final Zone MITHRIL = new Zone("mithril_deposits", "Mithril Deposits", '2', "JADE", "lime");
    public static final Zone PRECURSOR = new Zone("precursor_remnants", "Relic Remnants", 'b', "SAPPHIRE", "light_blue");
    public static final Zone GOBLIN = new Zone("goblin_holdout", "Goblin Holdout", '6', "AMBER", "orange");
    public static final Zone MAGMA = new Zone("magma_fields", "Magma Fields", 'c', "TOPAZ", "yellow");
    public static final Zone NUCLEUS = new Zone("crystal_nucleus", "Crystal Nucleus", 'd', "RUBY", "red");

    public static final List<Zone> QUADRANTS = List.of(JUNGLE, MITHRIL, PRECURSOR, GOBLIN, MAGMA);

    /** Below this the Magma Fields begin (matches the mining module's default heat line). */
    public static final int MAGMA_TOP = 64;
    public static final int NUCLEUS_R = 46;

    /** The zone a block belongs to (crisp quadrants, matching the Layout zone boxes). */
    public static Zone at(int x, int y, int z) {
        if (y <= MAGMA_TOP) return MAGMA;
        if (x * x + z * z <= NUCLEUS_R * NUCLEUS_R) return NUCLEUS;
        if (z < 0) return x < 0 ? JUNGLE : MITHRIL;
        return x < 0 ? GOBLIN : PRECURSOR;
    }

    /** Stained-glass colour -> gemstone (the mining module's block table, documented in the hand-off). */
    public static String gemOfGlass(String color) {
        return switch (color) {
            case "red" -> "RUBY";
            case "orange" -> "AMBER";
            case "purple" -> "AMETHYST";
            case "lime" -> "JADE";
            case "light_blue" -> "SAPPHIRE";
            case "yellow" -> "TOPAZ";
            case "magenta" -> "JASPER";
            case "black" -> "ONYX";
            case "blue" -> "AQUAMARINE";
            case "brown" -> "CITRINE";
            case "green" -> "PERIDOT";
            default -> null;
        };
    }
}
