package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * A copied cuboid, stored relative to the player's position at copy time.
 *
 * <p>Storing an <em>origin offset</em> rather than absolute coordinates is what makes paste land
 * predictably: whatever spatial relationship the build had to you when you copied it, it has to you
 * again when you paste. Copy a house while standing at its front door and it pastes with its front
 * door at your feet, at any rotation.</p>
 *
 * <p>Air is stored, not skipped. Skipping air makes paste behave like a stamp that cannot erase, so
 * pasting a room's interior would leave the old furniture floating inside it. {@code //paste air} keeps
 * the air (a true copy) and plain {@code //paste} skips it (a stamp) - both behaviours exist because
 * both are wanted, but the data has to contain air for the choice to be available at all.</p>
 */
public final class Clipboard {

    private final int width;
    private final int height;
    private final int length;
    /** Offset from the copy-time player position to the minimum corner. */
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    /** Palette indices, indexed [x][y][z] flattened as x + width * (z + length * y). */
    private final short[] cells;
    private final List<BlockData> palette;

    Clipboard(int width, int height, int length, int offsetX, int offsetY, int offsetZ,
              short[] cells, List<BlockData> palette) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.cells = cells;
        this.palette = palette;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    public int offsetZ() {
        return offsetZ;
    }

    public long volume() {
        return (long) width * height * length;
    }

    public List<BlockData> palette() {
        return palette;
    }

    public short[] cells() {
        return cells;
    }

    private int index(int x, int y, int z) {
        return x + width * (z + length * y);
    }

    public BlockData at(int x, int y, int z) {
        return palette.get(cells[index(x, y, z)] & 0xFFFF);
    }

    /** Number of non-air cells, for honest "pasted N blocks" reporting. */
    public long solidCount() {
        long n = 0;
        for (short cell : cells) {
            if (!palette.get(cell & 0xFFFF).getMaterial().isAir()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Rotates about the vertical axis. Only multiples of 90 are accepted: anything else would have to
     * resample and would turn straight walls into staircases.
     *
     * <p>Block states are rotated too, via {@link BlockData#rotate}, so stairs, doors, signs, logs and
     * fences all end up facing the right way. That is the part a naive clipboard gets wrong.</p>
     */
    public Clipboard rotated(int degrees) {
        int turns = ((degrees / 90) % 4 + 4) % 4;
        if (turns == 0) {
            return this;
        }
        Clipboard result = this;
        for (int i = 0; i < turns; i++) {
            result = result.rotatedOnce();
        }
        return result;
    }

    /** One clockwise quarter turn: (x,z) -> (length-1-z, x). */
    private Clipboard rotatedOnce() {
        int nw = length;
        int nl = width;
        short[] out = new short[cells.length];
        List<BlockData> newPalette = new ArrayList<>(palette.size());
        short[] remap = new short[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            BlockData rotated = palette.get(i).clone();
            rotated.rotate(org.bukkit.block.structure.StructureRotation.CLOCKWISE_90);
            remap[i] = (short) newPalette.size();
            newPalette.add(rotated);
        }
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int nx = length - 1 - z;
                    int nz = x;
                    out[nx + nw * (nz + nl * y)] = remap[cells[index(x, y, z)] & 0xFFFF];
                }
            }
        }
        // The offset rotates with the contents, otherwise a rotated paste drifts sideways.
        int noX = -(offsetZ + length - 1);
        int noZ = offsetX;
        return new Clipboard(nw, height, nl, noX, offsetY, noZ, out, newPalette);
    }

    /** Mirrors along an axis: "x", "z" or "y". Block states are mirrored with the geometry. */
    public Clipboard flipped(String axis) {
        short[] out = new short[cells.length];
        List<BlockData> newPalette = new ArrayList<>(palette.size());
        org.bukkit.block.structure.Mirror mirror = switch (axis) {
            case "x" -> org.bukkit.block.structure.Mirror.FRONT_BACK;
            case "z" -> org.bukkit.block.structure.Mirror.LEFT_RIGHT;
            default -> org.bukkit.block.structure.Mirror.NONE;
        };
        for (BlockData data : palette) {
            BlockData copy = data.clone();
            if (mirror != org.bukkit.block.structure.Mirror.NONE) {
                copy.mirror(mirror);
            }
            newPalette.add(copy);
        }
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int nx = axis.equals("x") ? width - 1 - x : x;
                    int ny = axis.equals("y") ? height - 1 - y : y;
                    int nz = axis.equals("z") ? length - 1 - z : z;
                    out[nx + width * (nz + length * ny)] = cells[index(x, y, z)];
                }
            }
        }
        int noX = axis.equals("x") ? -(offsetX + width - 1) : offsetX;
        int noY = axis.equals("y") ? -(offsetY + height - 1) : offsetY;
        int noZ = axis.equals("z") ? -(offsetZ + length - 1) : offsetZ;
        return new Clipboard(width, height, length, noX, noY, noZ, out, newPalette);
    }

    static BlockData air() {
        return Bukkit.createBlockData(Material.AIR);
    }
}
