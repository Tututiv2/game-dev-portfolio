package org.aincrad.plugin.render;

import org.aincrad.plugin.mask.MaskResult;
import org.bukkit.Material;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Orthographic top-down render of the generated slice, read straight out of the live world. This
 * is a review artifact rather than decoration: it is produced from the same blocks a player would
 * stand on, so it cannot flatter the build the way a hand-drawn plan could.
 *
 * <p>Two images are written - a colour map keyed by surface material, and a greyscale elevation
 * map - plus a shaded relief applied to the colour map so streets, roofs and terrain read apart at
 * a glance.</p>
 */
public final class OverheadMapRenderer {

    private static final Map<Material, Color> PALETTE = new HashMap<>();

    static {
        PALETTE.put(Material.GRASS_BLOCK, new Color(106, 148, 74));
        PALETTE.put(Material.DIRT, new Color(134, 96, 67));
        PALETTE.put(Material.STONE, new Color(125, 125, 125));
        PALETTE.put(Material.ANDESITE, new Color(136, 136, 136));
        PALETTE.put(Material.POLISHED_ANDESITE, new Color(160, 160, 158));
        PALETTE.put(Material.STONE_BRICKS, new Color(122, 122, 122));
        PALETTE.put(Material.STONE_BRICK_WALL, new Color(110, 110, 110));
        PALETTE.put(Material.DEEPSLATE_TILES, new Color(60, 60, 64));
        PALETTE.put(Material.POLISHED_BLACKSTONE, new Color(48, 44, 50));
        PALETTE.put(Material.POLISHED_BLACKSTONE_BRICKS, new Color(42, 38, 44));
        PALETTE.put(Material.DEEPSLATE_BRICKS, new Color(54, 54, 58));
        PALETTE.put(Material.IRON_BLOCK, new Color(216, 216, 216));
        PALETTE.put(Material.SEA_LANTERN, new Color(240, 240, 210));
        PALETTE.put(Material.BRICKS, new Color(150, 84, 68));
        PALETTE.put(Material.BRICK_STAIRS, new Color(150, 84, 68));
        PALETTE.put(Material.GRANITE, new Color(158, 108, 88));
        PALETTE.put(Material.GRANITE_STAIRS, new Color(158, 108, 88));
        PALETTE.put(Material.MUD_BRICKS, new Color(140, 116, 96));
        PALETTE.put(Material.MUD_BRICK_STAIRS, new Color(140, 116, 96));
        PALETTE.put(Material.DEEPSLATE_TILE_STAIRS, new Color(58, 58, 62));
        PALETTE.put(Material.CUT_COPPER, new Color(190, 110, 74));
        PALETTE.put(Material.CUT_COPPER_STAIRS, new Color(190, 110, 74));
        PALETTE.put(Material.CALCITE, new Color(224, 224, 218));
        PALETTE.put(Material.SMOOTH_SANDSTONE, new Color(216, 204, 162));
        PALETTE.put(Material.POLISHED_DIORITE, new Color(210, 210, 212));
        PALETTE.put(Material.WHITE_TERRACOTTA, new Color(210, 178, 162));
        PALETTE.put(Material.DARK_OAK_LOG, new Color(74, 56, 34));
        PALETTE.put(Material.SPRUCE_LOG, new Color(96, 74, 46));
        PALETTE.put(Material.GLASS_PANE, new Color(190, 214, 220));
        PALETTE.put(Material.TINTED_GLASS, new Color(70, 66, 74));
        PALETTE.put(Material.LANTERN, new Color(250, 214, 140));
        PALETTE.put(Material.OAK_STAIRS, new Color(160, 130, 82));
        PALETTE.put(Material.CAMPFIRE, new Color(120, 90, 60));
        // Added after the first render showed magenta streaks along every service lane: the lanes
        // are gravel and gravel was simply missing from this map. Magenta means "unmapped
        // material", so it is a renderer gap, not a world defect - but it looked like one.
        PALETTE.put(Material.GRAVEL, new Color(146, 142, 138));
        PALETTE.put(Material.COBBLESTONE, new Color(128, 128, 128));
        PALETTE.put(Material.POLISHED_BLACKSTONE_WALL, new Color(48, 44, 50));
        PALETTE.put(Material.POLISHED_BLACKSTONE_SLAB, new Color(48, 44, 50));
        PALETTE.put(Material.STONE_BRICK_SLAB, new Color(122, 122, 122));
        PALETTE.put(Material.SMOOTH_STONE_SLAB, new Color(158, 158, 158));
        PALETTE.put(Material.CHISELED_POLISHED_BLACKSTONE, new Color(56, 50, 58));
        PALETTE.put(Material.SPRUCE_PLANKS, new Color(114, 84, 48));
        PALETTE.put(Material.DARK_OAK_PLANKS, new Color(66, 43, 20));
        PALETTE.put(Material.OAK_DOOR, new Color(162, 130, 78));
        PALETTE.put(Material.BARREL, new Color(124, 94, 52));
        PALETTE.put(Material.CRAFTING_TABLE, new Color(134, 96, 60));
        PALETTE.put(Material.SMITHING_TABLE, new Color(60, 62, 74));
        PALETTE.put(Material.CAULDRON, new Color(70, 70, 74));
        PALETTE.put(Material.LECTERN, new Color(150, 116, 66));
        PALETTE.put(Material.BELL, new Color(226, 186, 96));
        PALETTE.put(Material.WHITE_CONCRETE, new Color(230, 230, 230));
        PALETTE.put(Material.BLACK_CONCRETE, new Color(24, 24, 26));
        PALETTE.put(Material.RED_BED, new Color(168, 54, 50));
        PALETTE.put(Material.IRON_BARS, new Color(160, 160, 164));
        PALETTE.put(Material.LADDER, new Color(150, 120, 70));
        PALETTE.put(Material.OAK_PRESSURE_PLATE, new Color(160, 130, 82));
    }

    private OverheadMapRenderer() {
    }

    /** Shared block-to-colour lookup, so the in-world map board matches the PNG renders exactly. */
    public static Color colourFor(Material m) {
        return PALETTE.getOrDefault(m, new Color(150, 60, 160));
    }

    public record Result(File colourMap, File elevationMap, int width, int height,
                          int sampled, int minY, int maxY) {
    }

    /**
     * @param step sample every Nth block; 1 is full resolution
     */
    public static Result render(World world, MaskResult mask, File outputDir, int minX, int minZ,
                                 int maxX, int maxZ, int step) throws IOException {
        int w = (maxX - minX) / step + 1;
        int h = (maxZ - minZ) / step + 1;
        BufferedImage colour = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage elevation = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        int[][] heights = new int[w][h];
        Color[][] colours = new Color[w][h];
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, sampled = 0;

        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < h; iz++) {
                int x = minX + ix * step;
                int z = minZ + iz * step;
                if (!mask.contains(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) {
                    colours[ix][iz] = new Color(18, 18, 22);   // outside the authorized mask
                    heights[ix][iz] = Integer.MIN_VALUE;
                    continue;
                }
                int y = world.getHighestBlockYAt(x, z);
                Material m = world.getBlockAt(x, y, z).getType();
                Color base = PALETTE.getOrDefault(m, new Color(150, 60, 160));  // magenta = unmapped
                colours[ix][iz] = base;
                heights[ix][iz] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                sampled++;
            }
        }

        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < h; iz++) {
                Color base = colours[ix][iz];
                int y = heights[ix][iz];
                if (y == Integer.MIN_VALUE) {
                    colour.setRGB(ix, iz, base.getRGB());
                    elevation.setRGB(ix, iz, 0);
                    continue;
                }
                // Shaded relief: compare against the neighbour to the north-west.
                int ny = (ix > 0 && iz > 0 && heights[ix - 1][iz - 1] != Integer.MIN_VALUE)
                        ? heights[ix - 1][iz - 1] : y;
                double shade = 1.0 + Math.max(-0.35, Math.min(0.35, (y - ny) * 0.12));
                int r = clamp((int) (base.getRed() * shade));
                int g = clamp((int) (base.getGreen() * shade));
                int b = clamp((int) (base.getBlue() * shade));
                colour.setRGB(ix, iz, new Color(r, g, b).getRGB());

                int span = Math.max(1, maxY - minY);
                int v = clamp(255 * (y - minY) / span);
                elevation.setRGB(ix, iz, new Color(v, v, v).getRGB());
            }
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("could not create " + outputDir);
        }
        File colourFile = new File(outputDir, "town-overhead-map.png");
        File elevationFile = new File(outputDir, "town-elevation-map.png");
        ImageIO.write(colour, "png", colourFile);
        ImageIO.write(elevation, "png", elevationFile);
        return new Result(colourFile, elevationFile, w, h, sampled, minY, maxY);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
