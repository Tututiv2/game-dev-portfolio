package com.skyclone.api.skill;

import com.skyclone.api.stat.Stat;

/**
 * The skills. {@code cosmetic} skills do not count toward skill average.
 *
 * <p>{@code maxLevel} is the HIGHEST level the reference data defines, not the starting cap: Foraging is 57
 * (players start capped at 50 and raise it through log collections), Farming 60 (cap 50, raised by contest
 * medals), Taming 60 (cap 50, raised by the pet collector). The starting cap lives in the skills module's data
 * ({@code SkillService.cap}). HUNTING was added in wave 5 (max 50, research/data/skills.json); it is marked
 * cosmetic so adding it does not lower anybody's skill average. // TUNE: flip to false if the reference counts it.
 */
public enum SkillType {
    FARMING("Farming", 60, Stat.FARMING_WISDOM, false, "GOLDEN_HOE"),
    MINING("Mining", 60, Stat.MINING_WISDOM, false, "STONE_PICKAXE"),
    COMBAT("Combat", 60, Stat.COMBAT_WISDOM, false, "STONE_SWORD"),
    FORAGING("Foraging", 57, Stat.FORAGING_WISDOM, false, "JUNGLE_SAPLING"),
    FISHING("Fishing", 50, Stat.FISHING_WISDOM, false, "FISHING_ROD"),
    ENCHANTING("Enchanting", 60, Stat.ENCHANTING_WISDOM, false, "ENCHANTING_TABLE"),
    ALCHEMY("Alchemy", 50, Stat.ALCHEMY_WISDOM, false, "BREWING_STAND"),
    CARPENTRY("Carpentry", 50, Stat.CARPENTRY_WISDOM, true, "CRAFTING_TABLE"),
    TAMING("Taming", 60, Stat.TAMING_WISDOM, false, "SPAWN_EGG"),
    RUNECRAFTING("Runecrafting", 25, Stat.RUNECRAFTING_WISDOM, true, "MAGMA_CREAM"),
    SOCIAL("Social", 25, Stat.SOCIAL_WISDOM, true, "EMERALD"),
    HUNTING("Hunting", 50, Stat.HUNTING_WISDOM, true, "LEAD");

    public final String display;
    public final int maxLevel;
    public final Stat wisdom;
    public final boolean cosmetic;
    public final String icon;

    SkillType(String display, int maxLevel, Stat wisdom, boolean cosmetic, String icon) {
        this.display = display;
        this.maxLevel = maxLevel;
        this.wisdom = wisdom;
        this.cosmetic = cosmetic;
        this.icon = icon;
    }

    public static SkillType parse(String s) {
        try { return valueOf(s.trim().toUpperCase()); } catch (Exception e) { return null; }
    }
}
