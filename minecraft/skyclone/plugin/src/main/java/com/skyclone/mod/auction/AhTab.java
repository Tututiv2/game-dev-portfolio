package com.skyclone.mod.auction;

import com.skyclone.api.item.ItemCategory;

/**
 * The six browser tabs of the genre's Auction House (research 05 section 3.6), with the glass
 * colour the browser's frame takes while the tab is open. Pure: decided from the item's category
 * and whether its material is a block.
 */
public enum AhTab {
    WEAPONS("Weapons", "&6", "GOLDEN_SWORD", "ORANGE_STAINED_GLASS_PANE",
            "Swords, bows, wands and every other way to hit something."),
    ARMOR("Armor", "&b", "DIAMOND_CHESTPLATE", "LIGHT_BLUE_STAINED_GLASS_PANE",
            "Helmets, chestplates, leggings, boots and equipment."),
    ACCESSORIES("Accessories", "&2", "EMERALD", "LIME_STAINED_GLASS_PANE",
            "Talismans, rings, artifacts and relics."),
    CONSUMABLES("Consumables", "&c", "APPLE", "RED_STAINED_GLASS_PANE",
            "Potions, books, scrolls, bait and other things that get used up."),
    BLOCKS("Blocks", "&e", "COBBLESTONE", "YELLOW_STAINED_GLASS_PANE",
            "Anything you can place."),
    TOOLS_MISC("Tools & Misc", "&d", "STICK", "MAGENTA_STAINED_GLASS_PANE",
            "Tools, pets, minions, stones and everything else.");

    public final String label;
    public final String color;
    public final String icon;
    public final String glass;
    public final String blurb;

    AhTab(String label, String color, String icon, String glass, String blurb) {
        this.label = label;
        this.color = color;
        this.icon = icon;
        this.glass = glass;
        this.blurb = blurb;
    }

    public static AhTab of(ItemCategory cat, boolean isBlock) {
        if (cat == null) cat = ItemCategory.MATERIAL;
        if (cat.isWeapon() || cat == ItemCategory.ARROW) return WEAPONS;
        if (cat.isArmor() || cat.isEquipment()) return ARMOR;
        if (cat == ItemCategory.ACCESSORY) return ACCESSORIES;
        switch (cat) {
            case CONSUMABLE, POTION, ENCHANTED_BOOK, TRAVEL_SCROLL, BAIT, DUNGEON_PASS -> { return CONSUMABLES; }
            default -> { }
        }
        if ((cat == ItemCategory.MATERIAL || cat == ItemCategory.NONE) && isBlock) return BLOCKS;
        return TOOLS_MISC;
    }

    public static AhTab parse(String s) {
        if (s == null) return null;
        for (AhTab t : values()) if (t.name().equalsIgnoreCase(s) || t.label.equalsIgnoreCase(s)) return t;
        return null;
    }
}
