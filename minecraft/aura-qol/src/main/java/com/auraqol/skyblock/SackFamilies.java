package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A raw crop or ore and its compacted forms, with how many raw items each one
 * is worth, so the Sacks panel can show "Wheat 12,000 + Enchanted Wheat 300 +
 * Enchanted Hay Bale 2 = 111,200 wheat". Pure data.
 *
 * <p>The multipliers were computed from the NEU repo's crafting recipes
 * ({@code SkyblockClone/research/data/neu-repo/items/*.json}, single-ingredient
 * recipes chained down to the raw item) on 2026-10-08 - e.g. Enchanted Hay
 * Bale = 160 Enchanted Wheat = 25,600 Wheat - and the ids are Hypixel's
 * ({@code INK_SACK:3}, not NEU's {@code INK_SACK-3}). Every id is checked against the live items API
 * by {@code LiveSourcesTest}; legacy ids that still exist but no longer trade (HAY_BLOCK,
 * ENCHANTED_HAY_BLOCK - the old "Enchanted Hay Bale" - and TIGHTLY_TIED_HAY_BALE) are left out.
 */
public final class SackFamilies {

    private SackFamilies() {
    }

    public record Member(String id, long mult) {
    }

    /** {@code name} is the raw item's name, which the total is counted in. */
    public record Family(String name, List<Member> members) {

        public Member member(String id) {
            for (Member m : members) {
                if (m.id().equals(id)) {
                    return m;
                }
            }
            return null;
        }

        public List<String> ids() {
            List<String> out = new ArrayList<>(members.size());
            for (Member m : members) {
                out.add(m.id());
            }
            return out;
        }
    }

    /** Every gemstone type, upper case as in the ids. Declared before the static block that reads it. */
    private static final String[] GEMS = {"RUBY", "AMBER", "AMETHYST", "JADE", "SAPPHIRE", "TOPAZ", "JASPER",
            "OPAL", "ONYX", "AQUAMARINE", "CITRINE", "PERIDOT"};

    private static final Map<String, Family> BY_NAME = new LinkedHashMap<>();
    private static final Map<String, Family> BY_ITEM = new HashMap<>();

    private static void fam(String name, Object... idMult) {
        List<Member> ms = new ArrayList<>();
        for (int i = 0; i < idMult.length; i += 2) {
            ms.add(new Member((String) idMult[i], ((Number) idMult[i + 1]).longValue()));
        }
        Family f = new Family(name, Collections.unmodifiableList(ms));
        BY_NAME.put(name, f);
        for (Member m : ms) {
            BY_ITEM.putIfAbsent(m.id(), f);
        }
    }

    static {
        // --- Garden crops (Agronomy sack) ---
        fam("Wheat", "WHEAT", 1, "ENCHANTED_WHEAT", 160, "ENCHANTED_HAY_BALE", 25_600);
        fam("Seeds", "SEEDS", 1, "ENCHANTED_SEEDS", 160, "BOX_OF_SEEDS", 25_600);
        fam("Carrot", "CARROT_ITEM", 1, "ENCHANTED_CARROT", 160, "ENCHANTED_GOLDEN_CARROT", 25_600);
        fam("Potato", "POTATO_ITEM", 1, "ENCHANTED_POTATO", 160, "ENCHANTED_BAKED_POTATO", 25_600);
        fam("Pumpkin", "PUMPKIN", 1, "ENCHANTED_PUMPKIN", 160, "POLISHED_PUMPKIN", 25_600);
        fam("Melon", "MELON", 1, "MELON_BLOCK", 9, "ENCHANTED_MELON", 160, "ENCHANTED_MELON_BLOCK", 25_600);
        fam("Sugar Cane", "SUGAR_CANE", 1, "ENCHANTED_SUGAR", 160, "ENCHANTED_SUGAR_CANE", 25_600);
        fam("Cactus", "CACTUS", 1, "ENCHANTED_CACTUS_GREEN", 160, "ENCHANTED_CACTUS", 25_600);
        fam("Cocoa Beans", "INK_SACK:3", 1, "ENCHANTED_COCOA", 160, "ENCHANTED_COOKIE", 25_600);
        fam("Nether Wart", "NETHER_STALK", 1, "ENCHANTED_NETHER_STALK", 160, "MUTANT_NETHER_STALK", 25_600);
        fam("Red Mushroom", "RED_MUSHROOM", 1, "HUGE_MUSHROOM_2", 9, "ENCHANTED_RED_MUSHROOM", 160,
                "ENCHANTED_HUGE_MUSHROOM_2", 25_600);
        fam("Brown Mushroom", "BROWN_MUSHROOM", 1, "HUGE_MUSHROOM_1", 9, "ENCHANTED_BROWN_MUSHROOM", 160,
                "ENCHANTED_HUGE_MUSHROOM_1", 25_600);
        fam("Sunflower", "DOUBLE_PLANT", 1, "ENCHANTED_SUNFLOWER", 160, "COMPACTED_SUNFLOWER", 25_600);
        fam("Moonflower", "MOONFLOWER", 1, "ENCHANTED_MOONFLOWER", 160, "COMPACTED_MOONFLOWER", 25_600);
        fam("Wild Rose", "WILD_ROSE", 1, "ENCHANTED_WILD_ROSE", 160, "COMPACTED_WILD_ROSE", 25_600);
        // --- Mining (Mining sack and friends) ---
        fam("Cobblestone", "COBBLESTONE", 1, "ENCHANTED_COBBLESTONE", 160);
        fam("Coal", "COAL", 1, "ENCHANTED_COAL", 160, "ENCHANTED_COAL_BLOCK", 25_600);
        fam("Iron Ingot", "IRON_INGOT", 1, "ENCHANTED_IRON", 160, "ENCHANTED_IRON_BLOCK", 25_600);
        fam("Gold Ingot", "GOLD_INGOT", 1, "ENCHANTED_GOLD", 160, "ENCHANTED_GOLD_BLOCK", 25_600);
        fam("Diamond", "DIAMOND", 1, "ENCHANTED_DIAMOND", 160, "ENCHANTED_DIAMOND_BLOCK", 25_600);
        fam("Lapis Lazuli", "INK_SACK:4", 1, "ENCHANTED_LAPIS_LAZULI", 160, "ENCHANTED_LAPIS_LAZULI_BLOCK", 25_600);
        fam("Redstone", "REDSTONE", 1, "ENCHANTED_REDSTONE", 160, "ENCHANTED_REDSTONE_BLOCK", 25_600);
        fam("Emerald", "EMERALD", 1, "ENCHANTED_EMERALD", 160, "ENCHANTED_EMERALD_BLOCK", 25_600);
        fam("Nether Quartz", "QUARTZ", 1, "ENCHANTED_QUARTZ", 160, "ENCHANTED_QUARTZ_BLOCK", 25_600);
        fam("Obsidian", "OBSIDIAN", 1, "ENCHANTED_OBSIDIAN", 160);
        fam("End Stone", "ENDER_STONE", 1, "ENCHANTED_ENDSTONE", 160);
        fam("Netherrack", "NETHERRACK", 1, "ENCHANTED_NETHERRACK", 160);
        fam("Glowstone Dust", "GLOWSTONE_DUST", 1, "ENCHANTED_GLOWSTONE_DUST", 160, "ENCHANTED_GLOWSTONE", 25_600);
        fam("Mithril", "MITHRIL_ORE", 1, "ENCHANTED_MITHRIL", 160);
        fam("Titanium", "TITANIUM_ORE", 1, "ENCHANTED_TITANIUM", 160);
        fam("Hard Stone", "HARD_STONE", 1, "ENCHANTED_HARD_STONE", 576, "CONCENTRATED_STONE", 331_776);
        fam("Glacite", "GLACITE", 1, "ENCHANTED_GLACITE", 160);
        fam("Umber", "UMBER", 1, "ENCHANTED_UMBER", 160);
        fam("Tungsten", "TUNGSTEN", 1, "ENCHANTED_TUNGSTEN", 160);
        fam("Ice", "ICE", 1, "PACKED_ICE", 9, "ENCHANTED_ICE", 160, "ENCHANTED_PACKED_ICE", 25_600);
        fam("Sand", "SAND", 1, "ENCHANTED_SAND", 160);
        fam("Mycelium", "MYCEL", 1, "ENCHANTED_MYCELIUM", 160, "ENCHANTED_MYCELIUM_CUBE", 25_600);
        // --- Gemstones (Gemstone sack): Rough -> Flawed (80) -> Fine (80) ---
        for (String g : GEMS) {
            String n = g.charAt(0) + g.substring(1).toLowerCase(Locale.ROOT);
            fam(n, "ROUGH_" + g + "_GEM", 1, "FLAWED_" + g + "_GEM", 80, "FINE_" + g + "_GEM", 6_400,
                    "FLAWLESS_" + g + "_GEM", 512_000);
        }
    }

    public static Family byName(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** The family an item id belongs to, or null. */
    public static Family forItem(String id) {
        return id == null ? null : BY_ITEM.get(id);
    }

    public static List<Family> all() {
        return List.copyOf(BY_NAME.values());
    }

    // ------------------------------------------------------------ what you are collecting

    /**
     * The crop family a held farming tool harvests, from its SkyBlock id:
     * Euclid/Gauss/Pythagorean/Turing/Newton hoes are THEORETICAL_HOE_<CROP>_<tier>,
     * the dicers, knife, chopper and cutter are named per crop.
     */
    public static Family forTool(String toolId) {
        if (toolId == null) {
            return null;
        }
        String t = toolId.toUpperCase(Locale.ROOT);
        if (t.startsWith("THEORETICAL_HOE_WHEAT")) {
            return byName("Wheat");
        }
        if (t.startsWith("THEORETICAL_HOE_CARROT")) {
            return byName("Carrot");
        }
        if (t.startsWith("THEORETICAL_HOE_POTATO")) {
            return byName("Potato");
        }
        if (t.startsWith("THEORETICAL_HOE_CANE")) {
            return byName("Sugar Cane");
        }
        if (t.startsWith("THEORETICAL_HOE_WARTS")) {
            return byName("Nether Wart");
        }
        if (t.startsWith("MELON_DICER")) {
            return byName("Melon");
        }
        if (t.startsWith("PUMPKIN_DICER")) {
            return byName("Pumpkin");
        }
        if (t.startsWith("CACTUS_KNIFE")) {
            return byName("Cactus");
        }
        if (t.startsWith("COCO_CHOPPER")) {
            return byName("Cocoa Beans");
        }
        // Newer per-crop tools follow no single scheme; a crop word in the id is enough.
        if (t.contains("SUNFLOWER")) {
            return byName("Sunflower");
        }
        if (t.contains("MOONFLOWER")) {
            return byName("Moonflower");
        }
        if (t.contains("WILD_ROSE") || t.contains("ROSE")) {
            return byName("Wild Rose");
        }
        return null;
    }

    /**
     * The family a broken block yields, from its registry id ("minecraft:wheat").
     * {@code miningIsland}: stone on the Dwarven Mines / Crystal Hollows / Glacite
     * drops Hard Stone, elsewhere Cobblestone.
     */
    public static Family forBlock(String blockId, boolean miningIsland) {
        if (blockId == null) {
            return null;
        }
        String b = blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId;
        if (b.endsWith("_stained_glass") || b.endsWith("_stained_glass_pane")) {
            String colour = b.substring(0, b.indexOf("_stained_glass"));
            String gem = switch (colour) {
                case "red" -> "RUBY";
                case "orange" -> "AMBER";
                case "purple" -> "AMETHYST";
                case "lime" -> "JADE";
                case "light_blue" -> "SAPPHIRE";
                case "yellow" -> "TOPAZ";
                case "magenta" -> "JASPER";
                case "white" -> "OPAL";
                case "black" -> "ONYX";
                case "blue" -> "AQUAMARINE";
                case "brown" -> "CITRINE";
                case "green" -> "PERIDOT";
                default -> null;
            };
            return gem == null ? null : byName(gem.charAt(0) + gem.substring(1).toLowerCase(Locale.ROOT));
        }
        return switch (b) {
            case "wheat" -> byName("Wheat");
            case "carrots" -> byName("Carrot");
            case "potatoes" -> byName("Potato");
            case "pumpkin" -> byName("Pumpkin");
            case "melon" -> byName("Melon");
            case "sugar_cane" -> byName("Sugar Cane");
            case "cactus" -> byName("Cactus");
            case "cocoa" -> byName("Cocoa Beans");
            case "nether_wart" -> byName("Nether Wart");
            case "red_mushroom", "red_mushroom_block" -> byName("Red Mushroom");
            case "brown_mushroom", "brown_mushroom_block" -> byName("Brown Mushroom");
            case "sunflower" -> byName("Sunflower");
            case "stone", "cobblestone", "infested_stone" -> byName(miningIsland ? "Hard Stone" : "Cobblestone");
            case "coal_ore", "deepslate_coal_ore", "coal_block" -> byName("Coal");
            case "iron_ore", "deepslate_iron_ore", "iron_block" -> byName("Iron Ingot");
            case "gold_ore", "deepslate_gold_ore", "gold_block" -> byName("Gold Ingot");
            case "diamond_ore", "deepslate_diamond_ore", "diamond_block" -> byName("Diamond");
            case "lapis_ore", "deepslate_lapis_ore", "lapis_block" -> byName("Lapis Lazuli");
            case "redstone_ore", "deepslate_redstone_ore", "redstone_block" -> byName("Redstone");
            case "emerald_ore", "deepslate_emerald_ore", "emerald_block" -> byName("Emerald");
            case "nether_quartz_ore", "quartz_block" -> byName("Nether Quartz");
            case "obsidian" -> byName("Obsidian");
            case "end_stone" -> byName("End Stone");
            case "netherrack" -> byName("Netherrack");
            case "glowstone" -> byName("Glowstone Dust");
            // Dwarven Mines mithril: grey wool, cyan terracotta, the prismarines, light blue wool.
            case "gray_wool", "cyan_terracotta", "prismarine", "prismarine_bricks", "dark_prismarine",
                 "light_blue_wool" -> byName("Mithril");
            case "polished_diorite" -> byName("Titanium");
            case "packed_ice" -> byName("Glacite");
            case "ice" -> byName("Ice");
            case "sand" -> byName("Sand");
            case "mycelium" -> byName("Mycelium");
            default -> null;
        };
    }
}
