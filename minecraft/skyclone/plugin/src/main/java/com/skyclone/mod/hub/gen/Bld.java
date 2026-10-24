package com.skyclone.mod.hub.gen;

import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

/**
 * Building styles of the hub and the one place a building is reserved, built and handed back as a
 * {@link Room}. Every building in the hub goes through {@link #build}, so nothing can overlap.
 */
public final class Bld {

    private Bld() {}

    /** A building style: walls, frame logs, foundation, floor, roof family and shape. */
    public static final class Style {
        Fill wall, foundation, floor;
        String frame, roof, roofStyle = "gable", window = "glass_pane", doorWood = "oak", roofFull;
        int floors = 1, floorHeight = 5;
        boolean chimney;

        Style copy() {
            Style s = new Style();
            s.wall = wall; s.foundation = foundation; s.floor = floor; s.frame = frame; s.roof = roof;
            s.roofStyle = roofStyle; s.window = window; s.doorWood = doorWood; s.floors = floors;
            s.floorHeight = floorHeight; s.chimney = chimney; s.roofFull = roofFull;
            return s;
        }

        public Style floors(int n, int h) { Style s = copy(); s.floors = n; s.floorHeight = h; return s; }
        public Style roof(String family, String style) { Style s = copy(); s.roof = family; s.roofStyle = style; return s; }
        public Style chimney() { Style s = copy(); s.chimney = true; return s; }
        public Style wall(String spec) { Style s = copy(); s.wall = Palette.of(spec); return s; }
        public Style frame(String log) { Style s = copy(); s.frame = log; return s; }
        public Style floor(String spec) { Style s = copy(); s.floor = Palette.of(spec); return s; }
        public Style door(String wood) { Style s = copy(); s.doorWood = wood; return s; }
        public Style window(String w) { Style s = copy(); s.window = w; return s; }
    }

    static Style style(String wall, String frame, String foundation, String floor, String roof, String roofStyle, String door) {
        Style s = new Style();
        s.wall = Palette.of(wall);
        s.frame = frame;
        s.foundation = Palette.of(foundation);
        s.floor = Palette.of(floor);
        s.roof = roof;
        s.roofStyle = roofStyle;
        s.doorWood = door;
        return s;
    }

    /** White plaster and dark timber: the village's everyday houses. */
    public static final Style TIMBER = style("white_terracotta:5,calcite:3,smooth_quartz:1", "dark_oak_log",
            "cobblestone:3,stone_bricks:2,mossy_cobblestone:1", "spruce_planks", "dark_oak", "gable", "dark_oak");
    /** Warm brick with spruce beams. */
    public static final Style BRICK = style("bricks:6,granite:1,terracotta:1", "spruce_log",
            "stone_bricks:3,cobblestone:1", "oak_planks", "spruce", "gable", "spruce");
    /** Pale stone civic buildings (bank, election hall). */
    public static final Style CIVIC = style("polished_diorite:4,smooth_quartz:3,calcite:1", "stripped_birch_log",
            "polished_andesite:3,stone_bricks:1", "polished_andesite:3,smooth_stone:1", "deepslate_tile", "hip", "dark_oak");
    /** Dark stone with dark wood (auction house, library). */
    public static final Style SLATE = style("deepslate_bricks:4,polished_deepslate:2,cracked_deepslate_bricks:1", "stripped_dark_oak_log",
            "cobbled_deepslate:2,deepslate_bricks:1", "dark_oak_planks", "dark_oak", "hip", "dark_oak");
    /** Grey stone brick with oak (smithy, chapel). */
    public static final Style STONE = style("stone_bricks:6,mossy_stone_bricks:2,cracked_stone_bricks:1", "oak_log",
            "cobblestone:3,mossy_cobblestone:1", "spruce_planks", "stone_brick", "gable", "spruce");
    /** Plain wood cabins (foraging camp, farm, fishing). */
    public static final Style CABIN = style("spruce_planks:5,stripped_spruce_log:1", "spruce_log",
            "cobblestone:3,mossy_cobblestone:1", "spruce_planks", "spruce", "gable", "spruce");
    /** Sandstone (museum). */
    public static final Style SAND = style("smooth_sandstone:5,cut_sandstone:2,sandstone:1", null,
            "chiseled_stone_bricks:1,stone_bricks:4", "polished_andesite:2,smooth_stone:2,polished_diorite:1", "sandstone", "hip", "birch");
    /** Birch and mossy stone (pet care, greenhouse). */
    public static final Style BIRCH = style("birch_planks:5,stripped_birch_log:1", "birch_log",
            "mossy_cobblestone:2,cobblestone:2", "birch_planks", "spruce", "gable", "birch");
    /** Red barn boards. */
    public static final Style BARN = style("red_terracotta:4,stripped_spruce_log:1", "dark_oak_log",
            "cobblestone:3,stone:1", "spruce_planks", "dark_oak", "gable", "spruce");
    /** Mud brick / mangrove (alchemist, hedge witch). */
    public static final Style MUD = style("mud_bricks:5,packed_mud:2", "mangrove_log",
            "cobblestone:2,mossy_cobblestone:2", "mangrove_planks", "mangrove", "gable", "mangrove");

    /**
     * Reserve and build a building with its ground floor at the village level of {@code y}.
     * {@code x, z} is the north-west wall corner; {@code w} runs along x, {@code d} along z.
     */
    public static Room build(HubCtx g, String name, int x, int y, int z, int w, int d, String door, Style s) {
        return build(g, name, x, y, z, w, d, door, s, false);
    }

    /** As {@link #build}, optionally letting the toolkit furnish the corners (homes). */
    public static Room build(HubCtx g, String name, int x, int y, int z, int w, int d, String door, Style s, boolean furnish) {
        Builders.House b = new Builders.House(x, y, z, w, d);
        b.floors = s.floors; b.floorHeight = s.floorHeight; b.door = door;
        b.wall = s.wall; b.frame = s.frame; b.foundation = s.foundation; b.floor = s.floor;
        b.roof = s.roof; b.roofStyle = s.roofStyle; b.window = s.window; b.doorWood = s.doorWood;
        b.roofFull = s.roofFull; b.chimney = s.chimney; b.furnish = furnish; b.seed = g.seed ^ name.hashCode();
        g.reserve(name, b.reserve());
        b.build(g.c);
        // a lantern either side of the door outside, and the step cleared of plants
        int sx = b.doorStep[0], sz = b.doorStep[2];
        String side = Blocks.cw(door);
        for (int k : new int[]{-2, 2}) {
            int lx = sx - Blocks.dx(door) + Blocks.dx(side) * k, lz = sz - Blocks.dz(door) + Blocks.dz(side) * k;
            int ly = b.y + 3;
            int ox = lx + Blocks.dx(door), oz = lz + Blocks.dz(door);
            if (g.c.isAir(ox, ly, oz) && com.skyclone.gen.BlockInfo.of(g.c.get(lx, ly, lz)).cube) g.c.set(ox, ly, oz, Blocks.wallTorch(door));
        }
        return new Room(g, b);
    }
}
