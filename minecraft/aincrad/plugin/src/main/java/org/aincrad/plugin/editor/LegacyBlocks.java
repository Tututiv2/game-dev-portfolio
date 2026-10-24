package org.aincrad.plugin.editor;

import java.util.HashMap;
import java.util.Map;

/**
 * Pre-1.13 numeric block ids to modern block states.
 *
 * <p><b>Why this is needed.</b> The downloaded SAO Floor One save is a mixed 1.12.2 / 1.16.5 world:
 * 7,217 of its chunks were re-saved in the flattened format when someone opened it in 1.16.5, and 2,773
 * were never loaded and so are still stored as numeric ids with a 4-bit metadata nibble. Without this
 * table those chunks import as holes.</p>
 *
 * <p><b>Why it is safe to hand-write.</b> Reimplementing Mojang's whole flattening from memory would be
 * reckless - it is roughly 700 ids and a mistake shows up as silently wrong blocks rather than an error.
 * But this table does not have to be general: a census of the actual legacy chunks found exactly
 * <b>135 distinct id:meta pairs</b>, of which twelve account for 99.7% of 44,059,930 blocks. So the
 * table covers what is really there, and anything outside it is <em>counted and reported by id</em>
 * rather than guessed at.</p>
 *
 * <p>The census also showed what those chunks are: 46% stone, 41% water, 5% bedrock, then dirt, gravel
 * and ores. They are unvisited wilderness and seabed, not buildings - worth knowing, because it means
 * the 28% that currently fails to import is terrain rather than content.</p>
 */
public final class LegacyBlocks {

    private LegacyBlocks() {
    }

    /** Key is {@code id << 4 | meta}. */
    private static final Map<Integer, String> MAP = new HashMap<>();

    private static void put(int id, int meta, String state) {
        MAP.put((id << 4) | meta, state);
    }

    /** Registers the same state for every metadata value of an id. */
    private static void putAll(int id, String state) {
        for (int meta = 0; meta < 16; meta++) {
            MAP.putIfAbsent((id << 4) | meta, state);
        }
    }

    static {
        // --- the twelve that are 99.7% of the legacy blocks
        put(1, 0, "minecraft:stone");
        put(1, 1, "minecraft:granite");
        put(1, 2, "minecraft:polished_granite");
        put(1, 3, "minecraft:diorite");
        put(1, 4, "minecraft:polished_diorite");
        put(1, 5, "minecraft:andesite");
        put(1, 6, "minecraft:polished_andesite");
        putAll(1, "minecraft:stone");
        putAll(7, "minecraft:bedrock");
        putAll(13, "minecraft:gravel");
        putAll(3, "minecraft:dirt");
        put(3, 1, "minecraft:coarse_dirt");
        put(3, 2, "minecraft:podzol");
        putAll(16, "minecraft:coal_ore");
        putAll(15, "minecraft:iron_ore");
        putAll(2, "minecraft:grass_block");

        // Water and lava: meta 0 is a full source block, 1-7 are flowing levels, 8+ are "falling".
        // Level maps straight across, so this is one loop rather than sixteen entries.
        for (int meta = 0; meta < 16; meta++) {
            int level = meta & 7;
            put(8, meta, "minecraft:water[level=" + level + "]");
            put(9, meta, "minecraft:water[level=" + level + "]");
            put(10, meta, "minecraft:lava[level=" + level + "]");
            put(11, meta, "minecraft:lava[level=" + level + "]");
        }

        // --- everything else the census turned up
        putAll(4, "minecraft:cobblestone");
        put(5, 0, "minecraft:oak_planks");
        put(5, 1, "minecraft:spruce_planks");
        put(5, 2, "minecraft:birch_planks");
        put(5, 3, "minecraft:jungle_planks");
        put(5, 4, "minecraft:acacia_planks");
        put(5, 5, "minecraft:dark_oak_planks");
        putAll(5, "minecraft:oak_planks");
        putAll(12, "minecraft:sand");
        put(12, 1, "minecraft:red_sand");
        putAll(14, "minecraft:gold_ore");
        putAll(21, "minecraft:lapis_ore");
        putAll(24, "minecraft:sandstone");
        putAll(30, "minecraft:cobweb");
        put(31, 0, "minecraft:dead_bush");
        put(31, 1, "minecraft:short_grass");
        put(31, 2, "minecraft:fern");
        putAll(31, "minecraft:short_grass");
        putAll(49, "minecraft:obsidian");
        putAll(56, "minecraft:diamond_ore");
        putAll(73, "minecraft:redstone_ore");
        putAll(74, "minecraft:redstone_ore");
        putAll(78, "minecraft:snow");
        putAll(79, "minecraft:ice");
        putAll(80, "minecraft:snow_block");
        putAll(82, "minecraft:clay");
        putAll(85, "minecraft:oak_fence");
        putAll(87, "minecraft:netherrack");
        putAll(88, "minecraft:soul_sand");
        putAll(89, "minecraft:glowstone");
        put(98, 0, "minecraft:stone_bricks");
        put(98, 1, "minecraft:mossy_stone_bricks");
        put(98, 2, "minecraft:cracked_stone_bricks");
        put(98, 3, "minecraft:chiseled_stone_bricks");
        putAll(98, "minecraft:stone_bricks");
        putAll(97, "minecraft:infested_stone");
        putAll(99, "minecraft:brown_mushroom_block");
        putAll(100, "minecraft:red_mushroom_block");
        putAll(110, "minecraft:mycelium");
        putAll(112, "minecraft:nether_bricks");
        putAll(121, "minecraft:end_stone");
        putAll(129, "minecraft:emerald_ore");
        putAll(145, "minecraft:anvil");
        putAll(153, "minecraft:nether_quartz_ore");
        putAll(159, "minecraft:white_terracotta");
        putAll(161, "minecraft:acacia_leaves");
        putAll(165, "minecraft:slime_block");
        put(168, 0, "minecraft:prismarine");
        put(168, 1, "minecraft:prismarine_bricks");
        put(168, 2, "minecraft:dark_prismarine");
        putAll(168, "minecraft:prismarine");
        putAll(169, "minecraft:sea_lantern");
        putAll(172, "minecraft:terracotta");
        putAll(174, "minecraft:packed_ice");
        putAll(179, "minecraft:red_sandstone");
        putAll(208, "minecraft:dirt_path");
        putAll(212, "minecraft:frosted_ice");
        putAll(213, "minecraft:magma_block");

        // Logs and leaves: the low two bits pick the species, the rest is axis or decay state, which we
        // deliberately drop - an axis that is one quarter-turn out is invisible in bulk terrain, whereas
        // dropping the block entirely leaves a hole.
        String[] oldWoods = {"oak", "spruce", "birch", "jungle"};
        String[] newWoods = {"acacia", "dark_oak", "oak", "oak"};
        for (int meta = 0; meta < 16; meta++) {
            put(17, meta, "minecraft:" + oldWoods[meta & 3] + "_log");
            put(18, meta, "minecraft:" + oldWoods[meta & 3] + "_leaves");
            put(162, meta, "minecraft:" + newWoods[meta & 3] + "_log");
            put(161, meta, "minecraft:" + newWoods[meta & 3] + "_leaves");
        }

        // Wool and the other sixteen-colour families share one meta-to-colour order.
        String[] colours = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int meta = 0; meta < 16; meta++) {
            put(35, meta, "minecraft:" + colours[meta] + "_wool");
            put(159, meta, "minecraft:" + colours[meta] + "_terracotta");
            put(95, meta, "minecraft:" + colours[meta] + "_stained_glass");
            put(160, meta, "minecraft:" + colours[meta] + "_stained_glass_pane");
            put(171, meta, "minecraft:" + colours[meta] + "_carpet");
        }

        putAll(20, "minecraft:glass");
        putAll(102, "minecraft:glass_pane");
        putAll(41, "minecraft:gold_block");
        putAll(42, "minecraft:iron_block");
        putAll(45, "minecraft:bricks");
        putAll(47, "minecraft:bookshelf");
        putAll(48, "minecraft:mossy_cobblestone");
        putAll(52, "minecraft:spawner");
        putAll(57, "minecraft:diamond_block");
        putAll(58, "minecraft:crafting_table");
        putAll(60, "minecraft:farmland");
        putAll(61, "minecraft:furnace");
        putAll(86, "minecraft:carved_pumpkin");
        putAll(91, "minecraft:jack_o_lantern");
        putAll(103, "minecraft:melon");
        putAll(111, "minecraft:lily_pad");
        putAll(133, "minecraft:emerald_block");
        putAll(152, "minecraft:redstone_block");
        putAll(155, "minecraft:quartz_block");
        putAll(173, "minecraft:coal_block");
    }

    /** @return a modern block state string, or null when this id is not in the table */
    public static String lookup(int id, int meta) {
        String exact = MAP.get((id << 4) | (meta & 15));
        if (exact != null) {
            return exact;
        }
        return MAP.get(id << 4);
    }

    public static boolean isAir(int id) {
        return id == 0;
    }
}
