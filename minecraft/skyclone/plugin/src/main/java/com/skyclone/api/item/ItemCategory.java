package com.skyclone.api.item;

/**
 * What an item IS, which decides where it can be worn, which reforges and enchants apply, and
 * the word after the rarity on the last lore line ("LEGENDARY SWORD"). {@code label} is that word;
 * empty for plain materials, which show only the rarity.
 */
public enum ItemCategory {
    // weapons
    SWORD("SWORD"), LONGSWORD("LONGSWORD"), BOW("BOW"), WAND("WAND"), DUNGEON_WEAPON("DUNGEON SWORD"),
    // armour (vanilla slots)
    HELMET("HELMET"), CHESTPLATE("CHESTPLATE"), LEGGINGS("LEGGINGS"), BOOTS("BOOTS"),
    // equipment (extra slots, in the equipment menu)
    NECKLACE("NECKLACE"), CLOAK("CLOAK"), BELT("BELT"), GLOVES("GLOVES"),
    // tools
    PICKAXE("PICKAXE"), DRILL("DRILL"), GAUNTLET("GAUNTLET"), AXE("AXE"), HOE("HOE"), SHOVEL("SHOVEL"),
    SHEARS("SHEARS"), FISHING_ROD("FISHING ROD"), FISHING_WEAPON("FISHING WEAPON"), VACUUM("VACUUM"),
    // progression
    ACCESSORY("ACCESSORY"), PET_ITEM("PET ITEM"), PET("PET"), MINION("MINION"), REFORGE_STONE("REFORGE STONE"),
    ENCHANTED_BOOK(""), POWER_STONE("POWER STONE"), GEMSTONE("GEMSTONE"), TRAVEL_SCROLL("TRAVEL SCROLL"),
    DEPLOYABLE("DEPLOYABLE"), CONSUMABLE("CONSUMABLE"), POTION("POTION"), ARROW("ARROW"), BAIT("BAIT"),
    SACK("SACK"), BACKPACK("BACKPACK"), COSMETIC("COSMETIC"), MEMENTO("MEMENTO"), DUNGEON_PASS("DUNGEON PASS"),
    SLAYER_ITEM("ITEM"), MINION_UPGRADE("ITEM"), FUEL("FUEL"), CHISEL("CHISEL"),
    // everything else
    MATERIAL(""), NONE("");

    public final String label;

    ItemCategory(String label) { this.label = label; }

    public boolean isArmor() { return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS; }
    public boolean isEquipment() { return this == NECKLACE || this == CLOAK || this == BELT || this == GLOVES; }
    public boolean isMeleeWeapon() { return this == SWORD || this == LONGSWORD || this == DUNGEON_WEAPON || this == FISHING_WEAPON || this == GAUNTLET; }
    public boolean isWeapon() { return isMeleeWeapon() || this == BOW || this == WAND; }
    public boolean isTool() {
        return this == PICKAXE || this == DRILL || this == GAUNTLET || this == AXE || this == HOE || this == SHOVEL
                || this == SHEARS || this == FISHING_ROD || this == VACUUM;
    }
    /** Non-stackable by nature: gets a uuid and a stack size of 1. */
    public boolean isUniqueByNature() {
        return isWeapon() || isArmor() || isEquipment() || isTool() || this == ACCESSORY || this == PET
                || this == MINION || this == BACKPACK || this == SACK || this == DEPLOYABLE;
    }

    public static ItemCategory parse(String s) {
        if (s == null) return MATERIAL;
        try { return valueOf(s.trim().toUpperCase().replace(' ', '_')); }
        catch (IllegalArgumentException e) { return MATERIAL; }
    }
}
