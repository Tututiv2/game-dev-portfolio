package com.skyclone.mod.farming;

import java.util.List;
import java.util.Locale;

/**
 * The thirteen crops (research 08 section 1.2; Mushroom counts once, Red and Brown share it). Pure Java:
 * block names are Minecraft block ids, so the unit tests and the generators can use this table too.
 *
 * <p>Numbers:
 * <ul>
 *   <li>base drops per block: research 08 1.2 "Base drops per break" (Wheat 1 + 1-2 Seeds, Carrot/Potato 1-4,
 *       Melon 3-7, Cocoa 3, Wart 2-3, Cane/Cactus 2, flowers 2, Pumpkin/Mushroom 1).</li>
 *   <li>Farming XP per block: research 02 2.4.1 (manual XP column; Sugar Cane and Cactus 2, Mushroom 6,
 *       Pumpkin 4.5, rest 4).</li>
 *   <li>regrow seconds: TUNE (research 08 1.2 "clone default: regrow on a timer so a player can walk a lane
 *       and back without waiting"). Multiplied by the dial {@code farming.regrow-multiplier}.</li>
 * </ul>
 */
public enum Crop {
    //            display        drop item         collection item         min max   xp   regrow shape        blocks                         contest drop  turbo enchant
    WHEAT("Wheat", "WHEAT", "WHEAT", 1, 1, 4, 8, Shape.AGEABLE, List.of("wheat"), "CROPIE", "turbo_wheat", "WHEAT"),
    CARROT("Carrot", "CARROT_ITEM", "CARROT_ITEM", 1, 4, 4, 8, Shape.AGEABLE, List.of("carrots"), "CROPIE", "turbo_carrot", "CARROT"),
    POTATO("Potato", "POTATO_ITEM", "POTATO_ITEM", 1, 4, 4, 8, Shape.AGEABLE, List.of("potatoes"), "CROPIE", "turbo_potato", "POTATO"),
    PUMPKIN("Pumpkin", "PUMPKIN", "PUMPKIN", 1, 1, 4.5, 12, Shape.BLOCK, List.of("pumpkin"), "SQUASH", "turbo_pumpkin", "PUMPKIN"),
    MELON("Melon", "MELON", "MELON", 3, 7, 4, 12, Shape.BLOCK, List.of("melon"), "SQUASH", "turbo_melon", "MELON"),
    SUGAR_CANE("Sugar Cane", "SUGAR_CANE", "SUGAR_CANE", 2, 2, 2, 6, Shape.STACK, List.of("sugar_cane"), "FERMENTO", "turbo_cane", "SUGAR_CANE"),
    CACTUS("Cactus", "CACTUS", "CACTUS", 2, 2, 2, 6, Shape.STACK, List.of("cactus"), "FERMENTO", "turbo_cactus", "CACTUS"),
    COCOA("Cocoa Beans", "INK_SACK:3", "INK_SACK:3", 3, 3, 4, 10, Shape.AGEABLE, List.of("cocoa"), "SQUASH", "turbo_coco", "COCOA_BEANS"),
    MUSHROOM("Mushroom", "RED_MUSHROOM", "MUSHROOM_COLLECTION", 1, 1, 6, 14, Shape.PLANT, List.of("red_mushroom", "brown_mushroom"), "FERMENTO", "turbo_mushrooms", "MUSHROOM"),
    NETHER_WART("Nether Wart", "NETHER_STALK", "NETHER_STALK", 2, 3, 4, 10, Shape.AGEABLE, List.of("nether_wart"), "FERMENTO", "turbo_warts", "NETHER_WART"),
    SUNFLOWER("Sunflower", "DOUBLE_PLANT", "DOUBLE_PLANT", 2, 2, 4, 10, Shape.TALL, List.of("sunflower"), "HELIANTHUS", "turbo_sunflower", "SUNFLOWER"),
    MOONFLOWER("Moonflower", "MOONFLOWER", "MOONFLOWER", 2, 2, 4, 10, Shape.PLANT, List.of("blue_orchid"), "HELIANTHUS", "turbo_moonflower", "MOONFLOWER"),
    WILD_ROSE("Wild Rose", "WILD_ROSE", "WILD_ROSE", 2, 2, 4, 10, Shape.TALL, List.of("rose_bush"), "HELIANTHUS", "turbo_rose", "WILD_ROSE");

    /** How a crop is harvested and restored. */
    public enum Shape {
        /** age property; harvest at max age, reset to 0 (wheat, carrots, potatoes, wart, cocoa). */
        AGEABLE,
        /** the block itself is the crop and comes back whole (melon, pumpkin). */
        BLOCK,
        /** a column: everything above the root is taken, the root stays (sugar cane, cactus). */
        STACK,
        /** a one-block plant that disappears and comes back (mushrooms, moonflower). */
        PLANT,
        /** a two-block flower: both halves go and come back (sunflower, wild rose). */
        TALL
    }

    public final String display;
    /** Reference item id of the main drop ("INK_SACK:3" for cocoa). Red mushrooms; brown is picked by block. */
    public final String dropId;
    /** Collection key (collections module) the drop counts toward; used by Collection Analysis. */
    public final String collectionId;
    public final int minDrops, maxDrops;
    public final double xp;
    public final double regrowSeconds;
    public final Shape shape;
    /** Minecraft block ids (lower case, no namespace) that ARE this crop. */
    public final List<String> blocks;
    /** Harvest Fair rare drop rolled while farming this crop during a contest. */
    public final String contestDrop;
    /** Turbo enchant id (enchanting module data) that adds Crop Fortune for this crop. */
    public final String turboEnchant;
    /** Key in the garden crop-milestone table (tables.json cropMilestones). */
    public final String milestoneKey;

    Crop(String display, String dropId, String collectionId, int min, int max, double xp, double regrow, Shape shape,
         List<String> blocks, String contestDrop, String turbo, String milestoneKey) {
        this.display = display; this.dropId = dropId; this.collectionId = collectionId;
        this.minDrops = min; this.maxDrops = max; this.xp = xp; this.regrowSeconds = regrow; this.shape = shape;
        this.blocks = blocks; this.contestDrop = contestDrop; this.turboEnchant = turbo; this.milestoneKey = milestoneKey;
    }

    /** Average base drops per harvested block (also the ghost competitors' yield multiplier). */
    public double averageDrops() { return (minDrops + maxDrops) / 2.0; }

    /** Crops that can be picked for a Harvest Fair (the 2025 flowers only with the dial on). */
    public static List<Crop> contestCrops(boolean flowers) {
        return flowers ? List.of(values())
                : List.of(WHEAT, CARROT, POTATO, PUMPKIN, MELON, SUGAR_CANE, CACTUS, COCOA, MUSHROOM, NETHER_WART);
    }

    /** The crop a block id belongs to ("minecraft:wheat", "WHEAT", "wheat[age=7]"), or null. */
    public static Crop ofBlock(String blockId) {
        if (blockId == null) return null;
        String b = blockId.toLowerCase(Locale.ROOT);
        if (b.startsWith("minecraft:")) b = b.substring(10);
        int br = b.indexOf('[');
        if (br >= 0) b = b.substring(0, br);
        for (Crop cr : values()) if (cr.blocks.contains(b)) return cr;
        return null;
    }

    /** Lenient lookup by enum name, display name or drop id ("cane", "Sugar Cane", "INK_SACK:3"). */
    public static Crop parse(String s) {
        if (s == null) return null;
        String k = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (Crop c : values()) {
            if (c.name().equals(k) || c.dropId.equals(s.trim().toUpperCase(Locale.ROOT))
                    || c.display.toUpperCase(Locale.ROOT).replace(' ', '_').equals(k)) return c;
        }
        return switch (k) {
            case "CANE" -> SUGAR_CANE;
            case "WART", "WARTS", "NETHER_STALK" -> NETHER_WART;
            case "COCOA_BEANS", "COCO" -> COCOA;
            case "MUSHROOMS", "RED_MUSHROOM", "BROWN_MUSHROOM" -> MUSHROOM;
            case "ROSE" -> WILD_ROSE;
            case "CARROTS" -> CARROT;
            case "POTATOES" -> POTATO;
            default -> null;
        };
    }
}
