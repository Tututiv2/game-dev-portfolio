package com.skyclone.mod.armor;

import java.util.List;

/**
 * Every set id and item id the bonus code refers to, in one place, so the self-test
 * {@code armor.bonus-ids-exist} can prove none of them is a typo against sets.json / the registry.
 */
final class SetIds {

    private SetIds() {}

    // dragons
    static final String YOUNG = "young_dragon", OLD = "old_dragon", STRONG = "strong_dragon", UNSTABLE = "unstable_dragon",
            WISE = "wise_dragon", PROTECTOR = "protector_dragon", HOLY = "holy_dragon", SUPERIOR = "superior_dragon", ENDER = "end";
    // combat / misc
    static final String GOLEM = "golem_armor", MERCENARY = "mercenary", EMERALD = "emerald_armor", SPEEDSTER = "speedster",
            CACTUS = "cactus", MUSHROOM = "mushroom", RABBIT = "rabbit", ZOMBIE = "zombie", GROWTH = "growth",
            MAGMA = "armor_of_magma", ARACHNE = "arachne", CRYSTAL = "crystal", WEREWOLF = "werewolf", FAIRY = "fairy",
            TUX_CHEAP = "cheap_tuxedo", TUX_FANCY = "fancy_tuxedo", TUX_ELEGANT = "elegant_tuxedo",
            CHALLENGER = "challenger", MYTHOS = "mythos", MINOS = "minos_hunter",
            BLAZE = "blaze", FROZEN_BLAZE = "frozen_blaze", EMBER = "ember", BERSERKER = "berserker", RAMPART = "rampart",
            SHIMMER = "shimmering_light", HEAVY = "heavy", SUPER_HEAVY = "super_heavy", GRUNT = "skeleton_grunt";
    // mining
    static final String MINER = "miner_outfit", LAPIS = "lapis_armor", GLACITE = "glacite", GOBLIN = "goblin", HEAT = "heat",
            TANK_MINER = "tank_miner", YOG = "armor_of_yog", MINERAL = "mineral", GLOSSY = "glossy_mineral", SORROW = "sorrow";
    // farming
    static final String MELON = "melon", CROPIE = "cropie", SQUASH = "squash", FERMENTO = "fermento", HELIANTHUS = "helianthus";
    // fishing
    static final String ANGLER = "angler", BACKWATER = "backwater", SALMON = "salmon", SALMON_NEW = "salmon_new", SPONGE = "sponge",
            SHARK = "shark_scale", DIVER = "diver", ABYSSAL = "abyssal", NUTCRACKER = "nutcracker", SNOW_SUIT = "snow_suit",
            THUNDER = "thunder", MAGMA_LORD = "magma_lord", BRONZE = "bronze_hunter", SILVER = "silver_hunter",
            GOLD_HUNTER = "gold_hunter", DIAMOND_HUNTER = "diamond_hunter";
    // wave 3: bonuses that used to be lore only
    static final String ROTTEN = "rotten", REKINDLED = "rekindled_ember", GREAT_SPOOK = "great_spook", SNORKELING = "snorkeling",
            STARLIGHT = "starlight";
    // wave 5: Newton's Demise
    static final String THERMODYNAMIC = "thermodynamic";

    static final List<String> ALL_SETS = List.of(YOUNG, OLD, STRONG, UNSTABLE, WISE, PROTECTOR, HOLY, SUPERIOR, ENDER,
            GOLEM, MERCENARY, EMERALD, SPEEDSTER, CACTUS, MUSHROOM, RABBIT, ZOMBIE, GROWTH, MAGMA, ARACHNE, CRYSTAL, WEREWOLF, FAIRY,
            TUX_CHEAP, TUX_FANCY, TUX_ELEGANT, CHALLENGER, MYTHOS, MINOS, BLAZE, FROZEN_BLAZE, EMBER, BERSERKER, RAMPART,
            SHIMMER, HEAVY, SUPER_HEAVY, GRUNT, MINER, LAPIS, GLACITE, GOBLIN, HEAT, TANK_MINER, YOG, MINERAL, GLOSSY, SORROW,
            MELON, CROPIE, SQUASH, FERMENTO, HELIANTHUS, ANGLER, BACKWATER, SALMON, SALMON_NEW, SPONGE, SHARK, DIVER, ABYSSAL,
            NUTCRACKER, SNOW_SUIT, THUNDER, MAGMA_LORD, BRONZE, SILVER, GOLD_HUNTER, DIAMOND_HUNTER,
            ROTTEN, REKINDLED, GREAT_SPOOK, SNORKELING, STARLIGHT, THERMODYNAMIC);

    // single items with behaviour
    static final String MITHRIL_COAT = "MITHRIL_COAT", SKELETON_HELMET = "SKELETON_HELMET", GUARDIAN_CHEST = "GUARDIAN_CHESTPLATE",
            CREEPER_PANTS = "CREEPER_LEGGINGS", SPIDER_BOOTS = "SPIDER_BOOTS", SPRING_BOOTS = "SPRING_BOOTS",
            RANCHERS_BOOTS = "RANCHERS_BOOTS", VAMPIRE_MASK = "VAMPIRE_MASK", VAMPIRE_WITCH_MASK = "VAMPIRE_WITCH_MASK",
            PUFFER_HAT = "PUFFERFISH_HAT", PUFFER_HAT_2 = "PUFFERFISH_HAT_CELEBRATION", FISH_HAT = "FISH_HAT",
            MAGMA_FISH_HAT = "MAGMA_FISH_HAT", SALMON_HAT = "SALMON_HAT", SALMON_HAT_2 = "SALMON_HAT_CELEBRATION",
            CROWN_GREED = "CROWN_OF_GREED", CROWN_AVARICE = "CROWN_OF_AVARICE", CROWN_AVARICE_2 = "CROWN_OF_AVARICE_CELEBRATION",
            SLUG_BOOTS = "SLUG_BOOTS", MUSIC_PANTS = "MUSIC_PANTS", WITCH_MASK = "WITCH_MASK";

    static final List<String> ALL_ITEMS = List.of(MITHRIL_COAT, SKELETON_HELMET, GUARDIAN_CHEST, CREEPER_PANTS, SPIDER_BOOTS,
            SPRING_BOOTS, RANCHERS_BOOTS, VAMPIRE_MASK, VAMPIRE_WITCH_MASK, PUFFER_HAT, PUFFER_HAT_2, FISH_HAT, MAGMA_FISH_HAT,
            SALMON_HAT, SALMON_HAT_2, CROWN_GREED, CROWN_AVARICE, CROWN_AVARICE_2, SLUG_BOOTS, MUSIC_PANTS, WITCH_MASK);

    /** Item ids other modules own that bonus code refers to (checked softly by the self-test). */
    static final String AOTE = "ASPECT_OF_THE_END", AOTD = "ASPECT_OF_THE_DRAGON", STARLIGHT_WAND = "STARLIGHT_WAND";
    static final String CROPIE_ITEM = "CROPIE", SQUASH_ITEM = "SQUASH", FERMENTO_ITEM = "FERMENTO", HELIANTHUS_ITEM = "HELIANTHUS";
    /** Wave 5: the Magma Lord "Necklace" (reference id keeps "GAUNTLET"; accessories registers it) and the Great Spook Staff. */
    static final String MAGMA_LORD_NECKLACE = "MAGMA_LORD_GAUNTLET", GREAT_SPOOK_STAFF = "GREAT_SPOOK_STAFF";
    static final List<String> FOREIGN_ITEMS = List.of(AOTE, AOTD, STARLIGHT_WAND, CROPIE_ITEM, SQUASH_ITEM, FERMENTO_ITEM, HELIANTHUS_ITEM,
            MAGMA_LORD_NECKLACE, GREAT_SPOOK_STAFF);
}
