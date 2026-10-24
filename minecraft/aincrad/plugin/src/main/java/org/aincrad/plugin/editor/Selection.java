package org.aincrad.plugin.editor;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * A cuboid selection: two corners in one world, plus the operations people actually reach for
 * (expand, contract, shift).
 *
 * <p>Corners are stored exactly as clicked rather than pre-sorted, because "corner 1" and "corner 2"
 * need to stay stable in messages - if the numbers silently swapped every time you clicked, the
 * feedback would be impossible to follow. Sorting happens on read, through {@link #minX()} and
 * friends.</p>
 */
public final class Selection {

    private final World world;
    private Integer x1, y1, z1;
    private Integer x2, y2, z2;

    public Selection(World world) {
        this.world = world;
    }

    public World world() {
        return world;
    }

    public void setCorner1(int x, int y, int z) {
        x1 = x;
        y1 = y;
        z1 = z;
    }

    public void setCorner2(int x, int y, int z) {
        x2 = x;
        y2 = y;
        z2 = z;
    }

    public boolean hasCorner1() {
        return x1 != null;
    }

    public boolean hasCorner2() {
        return x2 != null;
    }

    public boolean isComplete() {
        return hasCorner1() && hasCorner2();
    }

    public int minX() {
        return Math.min(x1, x2);
    }

    public int minY() {
        return Math.min(y1, y2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int maxY() {
        return Math.max(y1, y2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    public int corner1X() {
        return x1;
    }

    public int corner1Y() {
        return y1;
    }

    public int corner1Z() {
        return z1;
    }

    public int corner2X() {
        return x2;
    }

    public int corner2Y() {
        return y2;
    }

    public int corner2Z() {
        return z2;
    }

    public int width() {
        return maxX() - minX() + 1;
    }

    public int height() {
        return maxY() - minY() + 1;
    }

    public int length() {
        return maxZ() - minZ() + 1;
    }

    /** Block count. A long because 3,000 x 300 x 3,000 overflows an int and people do try it. */
    public long volume() {
        return (long) width() * height() * length();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    public Location centre() {
        return new Location(world,
                (minX() + maxX()) / 2.0 + 0.5,
                (minY() + maxY()) / 2.0 + 0.5,
                (minZ() + maxZ()) / 2.0 + 0.5);
    }

    /**
     * Grows the selection by {@code amount} along {@code face}. A negative amount shrinks, which is
     * how {@code //contract} is implemented - one code path, so the two commands cannot disagree.
     *
     * <p>Whichever corner currently sits on that side is the one that moves, so expanding "up" always
     * raises the top regardless of which corner happened to be clicked first.</p>
     */
    public void expand(BlockFace face, int amount) {
        int dx = face.getModX() * amount;
        int dy = face.getModY() * amount;
        int dz = face.getModZ() * amount;
        if (dx != 0) {
            if (dx > 0) {
                if (x1 >= x2) {
                    x1 += dx;
                } else {
                    x2 += dx;
                }
            } else {
                if (x1 <= x2) {
                    x1 += dx;
                } else {
                    x2 += dx;
                }
            }
        }
        if (dy != 0) {
            if (dy > 0) {
                if (y1 >= y2) {
                    y1 += dy;
                } else {
                    y2 += dy;
                }
            } else {
                if (y1 <= y2) {
                    y1 += dy;
                } else {
                    y2 += dy;
                }
            }
        }
        if (dz != 0) {
            if (dz > 0) {
                if (z1 >= z2) {
                    z1 += dz;
                } else {
                    z2 += dz;
                }
            } else {
                if (z1 <= z2) {
                    z1 += dz;
                } else {
                    z2 += dz;
                }
            }
        }
    }

    /** Expands both ways along an axis at once - {@code //expand 10 up down}, or vertical-to-limits. */
    public void expandBoth(BlockFace face, int amount) {
        expand(face, amount);
        expand(face.getOppositeFace(), amount);
    }

    public void shift(BlockFace face, int amount) {
        int dx = face.getModX() * amount;
        int dy = face.getModY() * amount;
        int dz = face.getModZ() * amount;
        x1 += dx;
        x2 += dx;
        y1 += dy;
        y2 += dy;
        z1 += dz;
        z2 += dz;
    }

    /** Clamps Y into the world's legal range, so a vertical expand cannot create an invalid box. */
    public void clampVertical() {
        int lo = world.getMinHeight();
        int hi = world.getMaxHeight() - 1;
        y1 = Math.max(lo, Math.min(hi, y1));
        y2 = Math.max(lo, Math.min(hi, y2));
    }

    public Selection copy() {
        Selection s = new Selection(world);
        if (isComplete()) {
            s.setCorner1(x1, y1, z1);
            s.setCorner2(x2, y2, z2);
        } else if (hasCorner1()) {
            s.setCorner1(x1, y1, z1);
        }
        return s;
    }

    @Override
    public String toString() {
        if (!isComplete()) {
            return "incomplete";
        }
        return width() + "x" + height() + "x" + length() + " ("
                + minX() + "," + minY() + "," + minZ() + " to "
                + maxX() + "," + maxY() + "," + maxZ() + ")";
    }
}
