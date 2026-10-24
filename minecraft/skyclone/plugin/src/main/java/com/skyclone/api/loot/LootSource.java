package com.skyclone.api.loot;

/**
 * Where an item came from. Decides whether it counts toward collections: only NATURAL sources
 * do — you cannot buy your way up a collection.
 */
public enum LootSource {
    MOB_DROP(true), MINING(true), FARMING(true), FORAGING(true), FISHING(true), MINION(true),
    SLAYER(true), DUNGEON(true), PET(true), GARDEN(true), EVENT(true), PICKUP(true),
    CRAFT(false), NPC_SHOP(false), BAZAAR(false), AUCTION(false), TRADE(false), SACK(false),
    STORAGE(false), REWARD(false), ADMIN(false), OTHER(false);

    public final boolean natural;

    LootSource(boolean natural) { this.natural = natural; }
}
