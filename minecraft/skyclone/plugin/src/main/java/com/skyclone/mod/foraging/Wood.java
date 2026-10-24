package com.skyclone.mod.foraging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every choppable wood, as data (pure: no Bukkit). A log block's material name maps to the item it
 * drops, the Foraging XP per log (research 07 §4.1), its toughness (§4.4; 0 for Hub/Park wood) and the
 * leaves that mark a real tree.
 */
public enum Wood {
    OAK("LOG", 6, 0, false, "Oak", "OAK_LOG", "OAK_WOOD"),
    SPRUCE("LOG:1", 6, 0, false, "Spruce", "SPRUCE_LOG", "SPRUCE_WOOD"),
    BIRCH("LOG:2", 6, 0, false, "Birch", "BIRCH_LOG", "BIRCH_WOOD"),
    JUNGLE("LOG:3", 6, 0, false, "Jungle", "JUNGLE_LOG", "JUNGLE_WOOD"),
    ACACIA("LOG_2", 6, 0, false, "Acacia", "ACACIA_LOG", "ACACIA_WOOD"),
    DARK_OAK("LOG_2:1", 6, 0, false, "Dark Oak", "DARK_OAK_LOG", "DARK_OAK_WOOD"),
    /** Moonmarsh only: the fig trunks are stripped spruce. */
    FIG("FIG_LOG", 15, 10, true, "Fig", "STRIPPED_SPRUCE_LOG", "STRIPPED_SPRUCE_WOOD"),
    /** Moonmarsh only. */
    MANGROVE("MANGROVE_LOG", 20, 50, true, "Mangrove", "MANGROVE_LOG", "MANGROVE_WOOD"),
    /** Moonmarsh canyon only: the spiral's red core is stripped mangrove, its pale corkscrew stripped birch. */
    HELIX("HELIX_LOG", 25, 150, true, "Spiral", "STRIPPED_MANGROVE_LOG", "STRIPPED_MANGROVE_WOOD", "STRIPPED_BIRCH_LOG", "STRIPPED_BIRCH_WOOD");

    public final String itemId;
    public final double xp;
    public final int toughness;
    /** Only choppable on the Moonmarsh island. */
    public final boolean marsh;
    public final String display;
    public final List<String> blocks;

    Wood(String itemId, double xp, int toughness, boolean marsh, String display, String... blocks) {
        this.itemId = itemId; this.xp = xp; this.toughness = toughness; this.marsh = marsh; this.display = display;
        this.blocks = List.of(blocks);
    }

    private static final Map<String, Wood> BY_BLOCK = new LinkedHashMap<>();
    static { for (Wood w : values()) for (String b : w.blocks) BY_BLOCK.put(b, w); }

    /** The wood of a block material name ("OAK_LOG"), or null. {@code marshIsland} enables the Moonmarsh woods. */
    public static Wood ofBlock(String material, boolean marshIsland) {
        if (material == null) return null;
        Wood w = BY_BLOCK.get(material.toUpperCase(Locale.ROOT));
        if (w == null) return null;
        if (w.marsh != marshIsland) {
            // mangrove logs are ordinary wood nowhere else; stripped logs elsewhere are buildings
            return null;
        }
        return w;
    }

    public static Wood ofItem(String itemId) {
        for (Wood w : values()) if (w.itemId.equals(itemId)) return w;
        return null;
    }

    // ---- cutting order (research 07 §4.4): fig trunk -> branches, mangrove branches -> trunk, spiral beige -> red ----

    /**
     * The cutting stage a log of this wood belongs to (0 is cut first), from its block name and its log axis
     * ('x', 'y', 'z'; 'y' for wood blocks). Zero-toughness woods have one stage.
     */
    public int stage(String material, char axis) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        boolean upright = axis == 'y' || m.endsWith("_WOOD");
        return switch (this) {
            case FIG -> upright ? 0 : 1;                       // trunk, then branches
            case MANGROVE -> upright ? 1 : 0;                  // branches, then trunk (the roots are not wood here)
            case HELIX -> m.startsWith("STRIPPED_BIRCH") ? 0 : 1;   // beige corkscrew, then the red core
            default -> 0;
        };
    }

    /** Stages this wood has (1 = no order). */
    public int stages() { return toughness > 0 ? 2 : 1; }

    /** What the player calls a stage ("trunk", "branches"...). */
    public String stageName(int stage) {
        return switch (this) {
            case FIG -> stage == 0 ? "trunk" : "branches";
            case MANGROVE -> stage == 0 ? "branches" : "trunk";
            case HELIX -> stage == 0 ? "pale corkscrew" : "red core";
            default -> "logs";
        };
    }

    /** The order as one line for menus ("Trunk -> Branches"). */
    public String orderLine() {
        if (stages() < 2) return "";
        return Character.toUpperCase(stageName(0).charAt(0)) + stageName(0).substring(1) + " → " + stageName(1);
    }

    /** Plank item ids for the Efficient Axe (reference legacy ids), or null when the wood has none. */
    public String plankItem() {
        return switch (this) {
            case OAK -> "WOOD";
            case SPRUCE -> "WOOD:1";
            case BIRCH -> "WOOD:2";
            case JUNGLE -> "WOOD:3";
            case ACACIA -> "WOOD:4";
            case DARK_OAK -> "WOOD:5";
            default -> null;
        };
    }

    /** Is this block name the leaves that make a log component a TREE (not a building's beam)? */
    public static boolean isLeaves(String material) {
        return material != null && (material.endsWith("_LEAVES") || material.equals("MANGROVE_ROOTS")
                || material.equals("BROWN_MUSHROOM_BLOCK") || material.equals("RED_MUSHROOM_BLOCK"));
    }

    /** Small flowers the foraging zones let you pick (1 Foraging XP, research 07 §4.1). */
    public static boolean isFlower(String material) {
        if (material == null) return false;
        return switch (material) {
            case "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP",
                 "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY" -> true;
            default -> false;
        };
    }
}
