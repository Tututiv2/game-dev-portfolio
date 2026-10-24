package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plot presets: ready-made farm layouts a player can lay on an owned plot from the Desk (the
 * reference makes you build every farm by hand; this goes further, research 08 1.2 "lanes of 1-3
 * crop rows"). Pure: writes ground (y &lt;= GROUND_Y) and plants into a canvas in TEMPLATE coords; the
 * runtime copies it over the plot and clears everything above ground it did not write.
 *
 * <p>Crops are planted young (or as bare mature stems) so a preset is infrastructure, not free crops.
 */
public final class PlotPresets {

    private PlotPresets() {}

    public record Preset(String id, String name, String crop, String icon, String about) {}

    public static final Map<String, Preset> ALL = new LinkedHashMap<>();

    private static void p(String id, String name, String crop, String icon, String about) { ALL.put(id, new Preset(id, name, crop, icon, about)); }

    static {
        p("clear", "Clear Plot", null, "SHEARS", "Flattens the plot to fresh grass.");
        p("wheat", "Wheat Lanes", "WHEAT", "WHEAT", "Tilled lanes of wheat with water every 9 rows.");
        p("carrot", "Carrot Lanes", "CARROT", "CARROT", "Tilled lanes of carrots with water every 9 rows.");
        p("potato", "Potato Lanes", "POTATO", "POTATO", "Tilled lanes of potatoes with water every 9 rows.");
        p("pumpkin", "Pumpkin Patch", "PUMPKIN", "PUMPKIN", "Stem rows with room for the pumpkins to grow.");
        p("melon", "Melon Patch", "MELON", "MELON", "Stem rows with room for the melons to grow.");
        p("sugar_cane", "Cane Channels", "SUGAR_CANE", "SUGAR_CANE", "Water channels lined with sugar cane.");
        p("cactus", "Cactus Field", "CACTUS", "CACTUS", "A sand field of spaced-out cacti.");
        p("cocoa", "Cocoa Grove", "COCOA_BEANS", "COCOA_BEANS", "Jungle log pillars wrapped in cocoa pods.");
        p("mushroom", "Mushroom Beds", "MUSHROOM", "RED_MUSHROOM", "Mycelium beds sown with red and brown mushrooms.");
        p("nether_wart", "Wart Beds", "NETHER_WART", "NETHER_WART", "Soul sand beds of nether wart.");
        p("sunflower", "Sunflower Rows", "SUNFLOWER", "SUNFLOWER", "Rows of sunflowers.");
        p("moonflower", "Moonflower Rows", "MOONFLOWER", "BLUE_ORCHID", "Rows of moonflowers.");
        p("wild_rose", "Wild Rose Rows", "WILD_ROSE", "ROSE_BUSH", "Rows of wild roses.");
    }

    /** Write a preset over a plot's inner box. */
    public static void write(VoxelCanvas c, Box in, String preset) {
        int y = PlotGrid.GROUND_Y;
        for (int z = in.minZ(); z <= in.maxZ(); z++)
            for (int x = in.minX(); x <= in.maxX(); x++) {
                int lx = x - in.minX(), lz = z - in.minZ();
                c.set(x, y - 1, z, "dirt");
                c.set(x, y - 2, z, "dirt");
                c.set(x, y - 3, z, "stone");
                switch (preset) {
                    case "wheat", "carrot", "potato" -> {
                        String crop = preset.equals("wheat") ? "wheat" : preset.equals("carrot") ? "carrots" : "potatoes";
                        if (lz % 9 == 4) c.set(x, y, z, "water");
                        else { c.set(x, y, z, "farmland[moisture=7]"); c.set(x, y + 1, z, crop + "[age=0]"); }
                    }
                    case "nether_wart" -> {
                        if (lz % 10 == 9) c.set(x, y, z, "dirt_path");
                        else { c.set(x, y, z, "soul_sand"); c.set(x, y + 1, z, "nether_wart[age=0]"); }
                    }
                    case "pumpkin", "melon" -> {
                        int k = lz % 5;
                        if (k == 0) c.set(x, y, z, "water");
                        else if (k == 1 || k == 3) { c.set(x, y, z, "farmland[moisture=7]"); c.set(x, y + 1, z, preset + "_stem[age=7]"); }
                        else c.set(x, y, z, "dirt");
                    }
                    case "sugar_cane" -> {
                        if (lz % 3 == 0) c.set(x, y, z, "water");
                        else { c.set(x, y, z, "sand"); c.set(x, y - 1, z, "sandstone"); c.set(x, y + 1, z, "sugar_cane[age=0]"); }
                    }
                    case "cactus" -> {
                        c.set(x, y, z, "sand");
                        c.set(x, y - 1, z, "sandstone");
                        if ((lx + lz) % 2 == 0 && lx % 2 == 0) c.set(x, y + 1, z, "cactus[age=0]");
                    }
                    case "cocoa" -> {
                        c.set(x, y, z, "grass_block");
                        if (lx % 3 == 1 && lz % 3 == 1)
                            for (int h = 1; h <= 4; h++) c.set(x, y + h, z, "jungle_log[axis=y]");
                    }
                    case "mushroom" -> {
                        c.set(x, y, z, "mycelium");
                        if ((lx + 2 * lz) % 3 == 0) c.set(x, y + 1, z, (lx + lz) % 2 == 0 ? "red_mushroom" : "brown_mushroom");
                    }
                    case "sunflower", "wild_rose" -> {
                        c.set(x, y, z, "grass_block");
                        if (lx % 2 == 0 && lz % 2 == 0) {
                            String b = preset.equals("sunflower") ? "sunflower" : "rose_bush";
                            c.set(x, y + 1, z, b + "[half=lower]");
                            c.set(x, y + 2, z, b + "[half=upper]");
                        }
                    }
                    case "moonflower" -> {
                        c.set(x, y, z, "grass_block");
                        if (lx % 2 == 0 && lz % 2 == 0) c.set(x, y + 1, z, "blue_orchid");
                    }
                    default -> c.set(x, y, z, "grass_block");
                }
            }
        if (preset.equals("cocoa")) {
            // pods on every side of every pillar, facing the log
            for (int z = in.minZ(); z <= in.maxZ(); z++)
                for (int x = in.minX(); x <= in.maxX(); x++) {
                    int lx = x - in.minX(), lz = z - in.minZ();
                    if (!(lx % 3 == 1 && lz % 3 == 1)) continue;
                    for (int h = 1; h <= 4; h++) {
                        pod(c, x + 1, y + h, z, "west", in);
                        pod(c, x - 1, y + h, z, "east", in);
                        pod(c, x, y + h, z + 1, "north", in);
                        pod(c, x, y + h, z - 1, "south", in);
                    }
                }
        }
    }

    private static void pod(VoxelCanvas c, int x, int y, int z, String facing, Box in) {
        if (x < in.minX() || x > in.maxX() || z < in.minZ() || z > in.maxZ()) return;
        if (!c.isAir(x, y, z)) return;
        c.set(x, y, z, "cocoa[age=0,facing=" + facing + "]");
    }
}
