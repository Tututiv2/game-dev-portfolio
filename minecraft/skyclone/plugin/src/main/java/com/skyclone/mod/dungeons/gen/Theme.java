package com.skyclone.mod.dungeons.gen;

import com.skyclone.gen.Palette;

/**
 * Block palettes per floor theme. Cracked STONE bricks are reserved for weak walls (the breakable kind), so no theme
 * uses them in plain walls.
 */
public final class Theme {

    public final String id;
    public final Palette wall, floor, ceiling, trim, pillar, rubble;
    public final String accent, light, lightHanging, stairs, slab, carpet, weakWall, fence;

    private Theme(String id, String wall, String floor, String ceiling, String trim, String pillar, String rubble,
                  String accent, String light, String lightHanging, String stairs, String slab, String carpet, String fence) {
        this.id = id;
        this.wall = Palette.of(wall);
        this.floor = Palette.of(floor);
        this.ceiling = Palette.of(ceiling);
        this.trim = Palette.of(trim);
        this.pillar = Palette.of(pillar);
        this.rubble = Palette.of(rubble);
        this.accent = accent;
        this.light = light;
        this.lightHanging = lightHanging;
        this.stairs = stairs;
        this.slab = slab;
        this.carpet = carpet;
        this.weakWall = "cracked_stone_bricks";
        this.fence = fence;
    }

    public static final Theme CRYPT = new Theme("crypt",
            "stone_bricks:7,mossy_stone_bricks:2,andesite:1",
            "stone_bricks:5,cobblestone:2,andesite:2,gravel:1,mossy_cobblestone:1",
            "stone_bricks:5,cobblestone:3,andesite:1",
            "polished_andesite:3,chiseled_stone_bricks:1",
            "stone_bricks:4,polished_andesite:1",
            "cobblestone:4,gravel:3,andesite:2,mossy_cobblestone:1",
            "chiseled_stone_bricks", "lantern[hanging=false,waterlogged=false]", "lantern[hanging=true,waterlogged=false]",
            "stone_brick_stairs", "stone_brick_slab", "gray_carpet", "spruce_fence");

    public static final Theme RUIN = new Theme("ruin",
            "mossy_stone_bricks:4,mossy_cobblestone:3,stone_bricks:2,tuff:1",
            "moss_block:2,coarse_dirt:3,cobblestone:2,mossy_cobblestone:2,rooted_dirt:1",
            "mossy_cobblestone:3,cobblestone:3,tuff:2",
            "tuff_bricks:3,chiseled_tuff:1",
            "mossy_stone_bricks:3,tuff_bricks:2",
            "mossy_cobblestone:3,cobblestone:2,gravel:2,moss_block:1",
            "chiseled_tuff", "lantern[hanging=false,waterlogged=false]", "lantern[hanging=true,waterlogged=false]",
            "mossy_stone_brick_stairs", "mossy_stone_brick_slab", "green_carpet", "dark_oak_fence");

    public static final Theme DEEP = new Theme("deep",
            "deepslate_bricks:5,cracked_deepslate_bricks:2,deepslate_tiles:2,polished_deepslate:1",
            "deepslate_tiles:4,cobbled_deepslate:3,polished_deepslate:2,tuff:1",
            "deepslate_bricks:4,cobbled_deepslate:3",
            "polished_deepslate:3,chiseled_deepslate:1",
            "deepslate_tiles:3,polished_deepslate:2",
            "cobbled_deepslate:4,tuff:2,gravel:2",
            "chiseled_deepslate", "soul_lantern[hanging=false,waterlogged=false]", "soul_lantern[hanging=true,waterlogged=false]",
            "deepslate_brick_stairs", "deepslate_brick_slab", "blue_carpet", "dark_oak_fence");

    public static final Theme WITHER = new Theme("wither",
            "polished_blackstone_bricks:5,cracked_polished_blackstone_bricks:2,blackstone:2,gilded_blackstone:1",
            "polished_blackstone:4,blackstone:3,polished_blackstone_bricks:2,basalt[axis=y]:1",
            "blackstone:4,polished_blackstone_bricks:3",
            "polished_blackstone:3,chiseled_polished_blackstone:1",
            "polished_basalt[axis=y]:3,polished_blackstone:2",
            "blackstone:4,basalt[axis=y]:2,gravel:1",
            "chiseled_polished_blackstone", "soul_lantern[hanging=false,waterlogged=false]", "soul_lantern[hanging=true,waterlogged=false]",
            "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "black_carpet", "crimson_fence");

    public static Theme of(String id) {
        if (id == null) return CRYPT;
        return switch (id) {
            case "ruin" -> RUIN;
            case "deep" -> DEEP;
            case "wither" -> WITHER;
            default -> CRYPT;
        };
    }
}
