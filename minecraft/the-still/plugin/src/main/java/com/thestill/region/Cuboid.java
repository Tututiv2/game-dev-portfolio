package com.thestill.region;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

/**
 * An immutable, world-scoped axis-aligned box with inclusive bounds.
 *
 * <p>Coordinates are stored explicitly and never derived from an ordering or an
 * index. Shadow Prison computed mine positions from their iteration-order grid
 * index, so deleting one mine silently relocated every mine after it — the
 * physical world moved because a map key was removed.
 */
public final class Cuboid {

    /**
     * Rejects absurd regions at definition time. 65,536 chunks is a 4096x4096
     * block area, far past any shaft or rig, and small enough that the spatial
     * index stays cheap. Without a cap, one mistyped coordinate builds a
     * world-sized index and the server dies on load with no useful message.
     */
    static final int MAX_CHUNKS = 65_536;

    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public Cuboid(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = Objects.requireNonNull(world, "world");
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public static Cuboid between(Location a, Location b) {
        World worldA = a.getWorld();
        World worldB = b.getWorld();
        if (worldA == null || worldB == null || !worldA.equals(worldB)) {
            throw new IllegalArgumentException("corners are in different worlds");
        }
        return new Cuboid(worldA.getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    public String world() {
        return world;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    // ---- containment ----------------------------------------------------

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && contains(x, y, z);
    }

    public boolean contains(Location location) {
        World locationWorld = location.getWorld();
        return locationWorld != null
                && world.equals(locationWorld.getName())
                && contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** True if {@code other} lies entirely inside this box. Used to validate child regions. */
    public boolean containsFully(Cuboid other) {
        return world.equals(other.world)
                && other.minX >= minX && other.maxX <= maxX
                && other.minY >= minY && other.maxY <= maxY
                && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    /** True if the boxes share at least one block. Used to reject overlapping siblings. */
    public boolean intersects(Cuboid other) {
        return world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /**
     * A copy grown by {@code horizontal} blocks on X/Z and {@code vertical} on Y.
     *
     * <p>The shell this produces is how "near a shaft but not in it" gets asked —
     * build shields, spawn rims, approach warnings.
     */
    public Cuboid expanded(int horizontal, int vertical) {
        return new Cuboid(world,
                minX - horizontal, minY - vertical, minZ - horizontal,
                maxX + horizontal, maxY + vertical, maxZ + horizontal);
    }

    // ---- measurements ---------------------------------------------------

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    /** Long, not int: a large region overflows a 32-bit block count. */
    public long volume() {
        return (long) width() * height() * depth();
    }

    /**
     * Maps a linear index onto a block position, for whole-region sweeps like a
     * mine reset.
     *
     * @param fromTop iterate downward from the ceiling, which is the order a
     *                mine refill wants so players are never buried mid-fill
     * @return {x, y, z}
     */
    public int[] blockAt(long index, boolean fromTop) {
        long volume = volume();
        if (index < 0 || index >= volume) {
            throw new IndexOutOfBoundsException("index " + index + " outside volume " + volume);
        }
        long layer = (long) width() * depth();
        long y = index / layer;
        long withinLayer = index % layer;
        long x = withinLayer / depth();
        long z = withinLayer % depth();
        return new int[]{
                minX + (int) x,
                fromTop ? maxY - (int) y : minY + (int) y,
                minZ + (int) z
        };
    }

    public Location center(World bukkitWorld) {
        return new Location(bukkitWorld,
                (minX + maxX) / 2.0 + 0.5,
                (minY + maxY) / 2.0,
                (minZ + maxZ) / 2.0 + 0.5);
    }

    // ---- chunk coverage -------------------------------------------------

    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    public long chunkCount() {
        return (long) (maxChunkX() - minChunkX() + 1) * (maxChunkZ() - minChunkZ() + 1);
    }

    /** Packs chunk coordinates into one long. The index key. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static long chunkKeyOfBlock(int blockX, int blockZ) {
        return chunkKey(blockX >> 4, blockZ >> 4);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cuboid c
                && world.equals(c.world)
                && minX == c.minX && minY == c.minY && minZ == c.minZ
                && maxX == c.maxX && maxY == c.maxY && maxZ == c.maxZ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return world + " [" + minX + "," + minY + "," + minZ + " .. "
                + maxX + "," + maxY + "," + maxZ + "]";
    }
}
