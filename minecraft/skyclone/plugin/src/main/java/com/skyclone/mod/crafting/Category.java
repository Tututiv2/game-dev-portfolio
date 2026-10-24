package com.skyclone.mod.crafting;

import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import org.bukkit.Material;

import java.util.Map;

/**
 * Recipe Book categories (research/08 §11.5: Farming, Mining, Combat, Fishing, Foraging, Enchanting,
 * Alchemy, Carpentry, Slayer, Special) plus Rift and Vanilla. A recipe's category comes from what
 * unlocks it: a Diamond collection tier means Mining, a slayer level means Slayer.
 */
enum Category {
    FARMING("Farming", Material.GOLDEN_HOE, "&a", "Crops, animals and the tools to grow them."),
    MINING("Mining", Material.STONE_PICKAXE, "&b", "Ores, gemstones and mining gear."),
    COMBAT("Combat", Material.STONE_SWORD, "&c", "Mob drops, weapons and armor."),
    FORAGING("Foraging", Material.JUNGLE_SAPLING, "&2", "Logs and everything carved from them."),
    FISHING("Fishing", Material.FISHING_ROD, "&3", "Sea catches, rods and lures."),
    ENCHANTING("Enchanting", Material.ENCHANTING_TABLE, "&d", "Arcane gear and enchanting tools."),
    ALCHEMY("Alchemy", Material.BREWING_STAND, "&e", "Brewing ingredients and their products."),
    CARPENTRY("Carpentry", Material.CRAFTING_TABLE, "&6", "Furniture for your island."),
    SLAYER("Slayer", Material.ROTTEN_FLESH, "&4", "Gear made from boss drops."),
    RIFT("Rift", Material.PURPLE_DYE, "&5", "Strange things from the other side."),
    SPECIAL("Special", Material.NETHER_STAR, "&f", "Everything that fits nowhere else."),
    VANILLA("Vanilla", Material.GRASS_BLOCK, "&7", "Blocks, tools and basics you always know.");

    final String display;
    final Material icon;
    final String color;
    final String blurb;

    Category(String display, Material icon, String color, String blurb) {
        this.display = display;
        this.icon = icon;
        this.color = color;
        this.blurb = blurb;
    }

    /**
     * @param unlock      the recipe's requirement key (may be null or "a & b")
     * @param vanilla     a vanilla recipe
     * @param collections collection id -> reference category (FARMING/MINING/COMBAT/FORAGING/FISHING/RIFT)
     * @param result      the result def (for unlock-free recipes), may be null
     */
    static Category of(String unlock, boolean vanilla, Map<String, String> collections, ItemDef result) {
        if (vanilla) return VANILLA;
        if (unlock != null && !unlock.isBlank()) {
            for (String part : unlock.split("&")) {
                Category c = fromKey(part.trim(), collections);
                if (c != null) return c;
            }
        }
        if (result == null) return SPECIAL;
        if (result.has("furniture")) return CARPENTRY;
        ItemCategory ic = result.category;
        if (ic == null) return SPECIAL;
        return switch (ic) {
            case SWORD, LONGSWORD, BOW, WAND, DUNGEON_WEAPON, HELMET, CHESTPLATE, LEGGINGS, BOOTS, ARROW,
                 NECKLACE, CLOAK, BELT, GLOVES -> COMBAT;
            case PICKAXE, DRILL, GAUNTLET, GEMSTONE, CHISEL, FUEL -> MINING;
            case HOE, VACUUM -> FARMING;
            case AXE, SHEARS -> FORAGING;
            case FISHING_ROD, FISHING_WEAPON, BAIT -> FISHING;
            case POTION -> ALCHEMY;
            case ENCHANTED_BOOK -> ENCHANTING;
            case SLAYER_ITEM -> SLAYER;
            default -> SPECIAL;
        };
    }

    static Category fromKey(String key, Map<String, String> collections) {
        String[] parts = key.split(":");
        if (parts.length == 0) return null;
        switch (parts[0]) {
            case "collection" -> {
                if (parts.length < 3) return null;
                String id = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
                if (id.startsWith("BOSS_")) return COMBAT;          // dungeon boss collections (collections module)
                String cat = collections.get(id);
                if (cat == null) return null;
                try { return valueOf(cat); } catch (IllegalArgumentException e) { return null; }
            }
            case "slayer" -> { return SLAYER; }
            case "hotm", "mining" -> { return MINING; }
            case "hotf", "foraging" -> { return FORAGING; }
            case "trophy" -> { return FISHING; }
            case "garden" -> { return FARMING; }
            case "skill" -> {
                if (parts.length < 2) return null;
                return switch (parts[1].toLowerCase()) {
                    case "farming" -> FARMING;
                    case "mining" -> MINING;
                    case "combat" -> COMBAT;
                    case "foraging" -> FORAGING;
                    case "fishing" -> FISHING;
                    case "enchanting" -> ENCHANTING;
                    case "alchemy" -> ALCHEMY;
                    case "carpentry" -> CARPENTRY;
                    default -> null;
                };
            }
            default -> { return null; }
        }
    }
}
