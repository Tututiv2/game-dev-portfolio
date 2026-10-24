package com.skyclone.gen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a block string means physically, approximated from its name and state. Shared by the
 * walkability flood, the floating-block search, the renderer and the connection fixer, so they
 * all agree on what "solid" means.
 *
 * <p>{@link #top16}: the collision height inside its own cell in sixteenths.
 * 0 = no collision (air, flowers, doors, gates — feet and head may be in it),
 * 1..15 = a low block you stand ON inside the same cell (carpet 1, slab 8, bottom stairs 8, farmland 15),
 * 16 = full top (stand in the cell above), 24 = fence/wall (1.5 tall: blocks walking, stand on top at +0.5),
 * -1 = blocks movement but is not something to stand on (hanging lantern, chain, cactus, end rod).
 */
public final class BlockInfo {

    public final String name;
    public final int top16;
    public final boolean water, lava, climb;
    /** Full opaque-ish cube: used for render culling and fence/pane connection. */
    public final boolean cube;
    /** Decorative hanging/attached things that may legitimately float (leaves, vines, lanterns, chains). */
    public final boolean floatOk;
    /** Not drawn by the renderer (air, light, barrier, structure_void). */
    public final boolean invisible;
    /** Plant-like / tiny things the renderer draws as a sprite rather than a cube. */
    public final boolean sprite;
    /** Render height in sixteenths (16 = full cube). */
    public final int renderH;

    private BlockInfo(String name, int top16, boolean water, boolean lava, boolean climb, boolean cube,
                      boolean floatOk, boolean invisible, boolean sprite, int renderH) {
        this.name = name; this.top16 = top16; this.water = water; this.lava = lava; this.climb = climb;
        this.cube = cube; this.floatOk = floatOk; this.invisible = invisible; this.sprite = sprite; this.renderH = renderH;
    }

    public boolean isAir() { return name.equals("air") || name.equals("cave_air") || name.equals("void_air"); }

    /** Feet or head may occupy this cell. Water is passable (you swim); lava is not. */
    public boolean passable() { return top16 == 0 && !lava; }

    public boolean liquid() { return water || lava; }

    private static final ConcurrentHashMap<String, BlockInfo> CACHE = new ConcurrentHashMap<>();

    public static final BlockInfo AIR = new BlockInfo("air", 0, false, false, false, false, true, true, false, 0);

    public static BlockInfo of(String state) {
        if (state == null) return AIR;
        BlockInfo i = CACHE.get(state);
        if (i == null) {
            i = classify(state);
            CACHE.put(state, i);
        }
        return i;
    }

    private static final Set<String> AIRS = Set.of("air", "cave_air", "void_air");
    private static final Set<String> INVISIBLE = Set.of("light", "structure_void", "barrier");
    private static final Set<String> WATERY = Set.of("water", "bubble_column", "kelp", "kelp_plant", "seagrass", "tall_seagrass");
    private static final Set<String> CLIMB = Set.of("ladder", "vine", "scaffolding", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "cave_vines", "cave_vines_plant");
    private static final Set<String> PASS_EXACT = Set.of(
            "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush", "bush", "firefly_bush", "short_dry_grass",
            "tall_dry_grass", "leaf_litter", "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
            "crimson_roots", "warped_roots", "nether_sprouts", "wheat", "carrots", "potatoes", "beetroots", "melon_stem",
            "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem", "sugar_cane", "nether_wart", "sweet_berry_bush",
            "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch", "copper_torch",
            "copper_wall_torch", "lever", "tripwire", "tripwire_hook", "redstone_wire", "rail", "powered_rail",
            "detector_rail", "activator_rail", "cobweb", "fire", "soul_fire", "glow_lichen", "sculk_vein",
            "hanging_roots", "spore_blossom", "pale_hanging_moss", "big_dripleaf_stem", "small_dripleaf",
            "nether_portal", "end_portal", "end_gateway", "frogspawn", "dandelion", "poppy", "blue_orchid", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "torchflower", "torchflower_crop", "pitcher_crop", "pink_petals", "wildflowers",
            "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant", "open_eyeblossom", "closed_eyeblossom",
            "cactus_flower", "powder_snow", "light", "structure_void", "string", "cave_vines", "cave_vines_plant",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "vine", "ladder",
            "bamboo_sapling", "azalea_leaves_placeholder", "dried_ghast_placeholder");
    private static final Set<String> FLOAT_OK = Set.of("vine", "glow_lichen", "cave_vines", "cave_vines_plant",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "hanging_roots",
            "pale_hanging_moss", "spore_blossom", "cobweb", "bell", "end_rod", "lightning_rod", "light", "barrier",
            "structure_void");

    private static BlockInfo classify(String state) {
        String n = BlockState.name(state);
        if (AIRS.contains(n)) return new BlockInfo(n, 0, false, false, false, false, true, true, false, 0);
        if (INVISIBLE.contains(n)) {
            int t = n.equals("barrier") ? 16 : 0;
            return new BlockInfo(n, t, false, false, false, false, true, true, false, 0);
        }
        if (WATERY.contains(n) || "true".equals(BlockState.prop(state, "waterlogged")) && isPlantLike(n))
            return new BlockInfo(n, 0, true, false, false, false, false, false, n.contains("kelp") || n.contains("seagrass"), 16);
        if (n.equals("lava")) return new BlockInfo(n, 0, false, true, false, false, false, false, false, 16);

        boolean floatOk = FLOAT_OK.contains(n) || n.endsWith("_leaves") || n.endsWith("lantern") && !n.equals("jack_o_lantern") && !n.equals("sea_lantern")
                || n.endsWith("chain") && !n.contains("command") || n.endsWith("_banner") || n.endsWith("torch") || n.endsWith("_sign")
                || n.endsWith("_hanging_sign");

        if (CLIMB.contains(n)) return new BlockInfo(n, 0, false, false, true, false, floatOk, false, true, 16);

        // --- no collision: plants, torches, signs, buttons, plates, doors and gates (doors/gates count as passable) ---
        if (PASS_EXACT.contains(n) || n.endsWith("_sapling") || n.endsWith("_sign") || n.endsWith("_banner")
                || n.endsWith("_button") || n.endsWith("_pressure_plate") || n.endsWith("_coral") || n.endsWith("_coral_fan")
                || n.endsWith("_coral_wall_fan") || n.endsWith("_door") || n.endsWith("_fence_gate") || n.endsWith("_tulip")) {
            boolean sprite = !(n.endsWith("_door") || n.endsWith("_fence_gate"));
            int rh = sprite ? 10 : 16;
            return new BlockInfo(n, 0, false, false, false, false, floatOk, false, sprite, rh);
        }
        if (n.endsWith("_trapdoor")) {
            boolean open = "true".equals(BlockState.prop(state, "open"));
            boolean top = "top".equals(BlockState.prop(state, "half"));
            if (open) return new BlockInfo(n, 0, false, false, true, false, false, false, false, 16);
            return top ? new BlockInfo(n, 16, false, false, false, false, false, false, false, 16)
                       : new BlockInfo(n, 3, false, false, false, false, false, false, false, 3);
        }
        if (n.equals("snow")) {
            int layers = parseInt(BlockState.prop(state, "layers"), 1);
            int t = (layers - 1) * 2;
            return new BlockInfo(n, t, false, false, false, false, false, false, false, Math.max(2, layers * 2));
        }
        if (n.endsWith("_slab")) {
            String type = BlockState.prop(state, "type");
            if ("top".equals(type)) return new BlockInfo(n, 16, false, false, false, false, false, false, false, 16);
            if ("double".equals(type)) return new BlockInfo(n, 16, false, false, false, true, false, false, false, 16);
            return new BlockInfo(n, 8, false, false, false, false, false, false, false, 8);
        }
        if (n.endsWith("_stairs")) {
            boolean top = "top".equals(BlockState.prop(state, "half"));
            return top ? new BlockInfo(n, 16, false, false, false, false, false, false, false, 16)
                       : new BlockInfo(n, 8, false, false, false, false, false, false, false, 16);
        }
        if (n.endsWith("_carpet")) return new BlockInfo(n, 1, false, false, false, false, false, false, false, 1);
        if (n.endsWith("_bed")) return new BlockInfo(n, 9, false, false, false, false, false, false, false, 9);
        if (n.endsWith("_fence") || n.endsWith("_wall") && !n.endsWith("_wall_banner")) {
            return new BlockInfo(n, 24, false, false, false, false, false, false, false, 16);
        }
        if (n.endsWith("_pane") || n.equals("iron_bars") || n.endsWith("_bars"))
            return new BlockInfo(n, 16, false, false, false, false, false, false, false, 16);
        if (n.endsWith("lantern") && !n.equals("jack_o_lantern") && !n.equals("sea_lantern")) {
            boolean hanging = "true".equals(BlockState.prop(state, "hanging"));
            return new BlockInfo(n, hanging ? -1 : 7, false, false, false, false, true, false, true, 8);
        }
        if (n.endsWith("chain") && !n.contains("command")) return new BlockInfo(n, -1, false, false, false, false, true, false, true, 16);
        if (n.endsWith("_head") || n.endsWith("_skull")) {
            boolean wall = n.contains("_wall_");
            return new BlockInfo(n, wall ? -1 : 8, false, false, false, false, wall, false, false, 8);
        }
        if (n.startsWith("potted_") || n.equals("flower_pot")) return new BlockInfo(n, 6, false, false, false, false, false, false, false, 6);
        if (n.endsWith("candle")) return new BlockInfo(n, 6, false, false, false, false, false, false, true, 6);
        if (n.endsWith("_candle_cake") || n.equals("cake")) return new BlockInfo(n, 8, false, false, false, false, false, false, false, 8);
        switch (n) {
            case "farmland", "dirt_path", "honey_block" -> { return new BlockInfo(n, 15, false, false, false, true, false, false, false, 15); }
            case "soul_sand", "mud" -> { return new BlockInfo(n, 14, false, false, false, true, false, false, false, 16); }
            case "chest", "trapped_chest", "ender_chest" -> { return new BlockInfo(n, 14, false, false, false, false, false, false, false, 14); }
            case "enchanting_table" -> { return new BlockInfo(n, 12, false, false, false, false, false, false, false, 12); }
            case "daylight_detector" -> { return new BlockInfo(n, 6, false, false, false, false, false, false, false, 6); }
            case "stonecutter" -> { return new BlockInfo(n, 9, false, false, false, false, false, false, false, 9); }
            case "lily_pad" -> { return new BlockInfo(n, 1, false, false, false, false, false, false, false, 1); }
            case "sea_pickle" -> { return new BlockInfo(n, 6, false, false, false, false, false, false, true, 6); }
            case "turtle_egg", "sniffer_egg" -> { return new BlockInfo(n, 7, false, false, false, false, false, false, false, 7); }
            case "campfire", "soul_campfire" -> { return new BlockInfo(n, 7, false, false, false, false, false, false, false, 7); }
            case "end_portal_frame" -> { return new BlockInfo(n, 13, false, false, false, true, false, false, false, 13); }
            case "brewing_stand" -> { return new BlockInfo(n, 14, false, false, false, false, false, false, true, 14); }
            case "cactus", "end_rod", "lightning_rod", "bell", "pointed_dripstone", "bamboo", "conduit", "big_dripleaf" -> {
                return new BlockInfo(n, -1, false, false, false, false, FLOAT_OK.contains(n), false, true, 16);
            }
            case "repeater", "comparator" -> { return new BlockInfo(n, 2, false, false, false, false, false, false, false, 2); }
            case "dirt", "grass_block" -> { /* fall through to full */ }
            default -> { }
        }
        boolean glass = n.endsWith("glass") || n.equals("ice") || n.endsWith("_leaves");
        return new BlockInfo(n, 16, false, false, false, !glass || n.endsWith("_leaves"), floatOk, false, false, 16);
    }

    private static boolean isPlantLike(String n) {
        return n.contains("coral") || n.contains("seagrass") || n.contains("kelp") || n.equals("sea_pickle");
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
