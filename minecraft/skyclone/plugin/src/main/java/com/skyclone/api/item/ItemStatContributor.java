package com.skyclone.api.item;

/**
 * Adds stats to ONE item instance — e.g. Protection V adds Defense, a Fine Jasper gem adds
 * Strength, five stars add 10% to dungeon stats. The SAME sheet feeds the lore AND the player's
 * stats, so what the tooltip says is exactly what the player gets.
 *
 * <p>{@code rarity} is the effective rarity (after recombobulation). Must be pure and fast.
 */
@FunctionalInterface
public interface ItemStatContributor {
    void contribute(ItemDef def, ItemData data, Rarity rarity, ItemStatSheet sheet);
}
