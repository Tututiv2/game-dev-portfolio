package org.aincrad.plugin.editor;

import org.bukkit.block.data.BlockData;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * Every write source in the editor, built from one shared traversal.
 *
 * <p><b>Chunk ordering.</b> The traversal walks whole 16x16 chunk columns before moving on, rather
 * than sweeping X then Z across the whole box. The natural nested-loop order revisits every chunk in a
 * row once per Z step, which would make {@link EditQueue} load and unload the same chunks repeatedly on
 * any selection wider than 16 blocks. Ordering by chunk means each chunk is entered exactly once.</p>
 *
 * <p>Shapes are expressed as a test on a coordinate, so a sphere, a cylinder and a hollow box all reuse
 * the same iterator and cannot disagree about ordering or bounds.</p>
 */
public final class Shapes {

    private Shapes() {
    }

    /** True when this coordinate is part of the shape. */
    public interface Test {
        boolean inside(int x, int y, int z);
    }

    /** What to write at a coordinate that passed the test. */
    public interface Supplier {
        BlockData dataAt(int x, int y, int z);
    }

    /**
     * Bounded, chunk-ordered traversal that emits only coordinates passing {@code test}.
     *
     * @param estimate reported to the player for progress; for shapes this is the enclosing box, so a
     *                 sphere's progress bar advances faster than linearly. Honest and cheap beats exact
     *                 and slow, and the final message reports the true changed count anyway.
     */
    public static EditQueue.WriteSource region(int minX, int minY, int minZ,
                                               int maxX, int maxY, int maxZ,
                                               Test test, Supplier supplier,
                                               BlockMask mask, long estimate) {
        return new EditQueue.WriteSource() {
            @Override
            public long estimate() {
                return estimate;
            }

            @Override
            public BlockMask mask() {
                return mask;
            }

            @Override
            public Iterator<EditQueue.Write> iterator() {
                return new ChunkOrderedIterator(minX, minY, minZ, maxX, maxY, maxZ, test, supplier);
            }
        };
    }

    private static final class ChunkOrderedIterator implements Iterator<EditQueue.Write> {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final Test test;
        private final Supplier supplier;
        private final int minCx, maxCx, minCz, maxCz;

        private int cx, cz;
        private int x, y, z;
        private int chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ;
        private EditQueue.Write next;
        private boolean exhausted;

        ChunkOrderedIterator(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                             Test test, Supplier supplier) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.test = test;
            this.supplier = supplier;
            this.minCx = minX >> 4;
            this.maxCx = maxX >> 4;
            this.minCz = minZ >> 4;
            this.maxCz = maxZ >> 4;
            this.cx = minCx;
            this.cz = minCz;
            enterChunk();
            advance();
        }

        private void enterChunk() {
            chunkMinX = Math.max(minX, cx << 4);
            chunkMaxX = Math.min(maxX, (cx << 4) + 15);
            chunkMinZ = Math.max(minZ, cz << 4);
            chunkMaxZ = Math.min(maxZ, (cz << 4) + 15);
            x = chunkMinX;
            z = chunkMinZ;
            y = minY;
        }

        /** Steps the cursor one position, rolling over y -> z -> x -> chunk. */
        private boolean step() {
            y++;
            if (y > maxY) {
                y = minY;
                z++;
                if (z > chunkMaxZ) {
                    z = chunkMinZ;
                    x++;
                    if (x > chunkMaxX) {
                        cz++;
                        if (cz > maxCz) {
                            cz = minCz;
                            cx++;
                            if (cx > maxCx) {
                                return false;
                            }
                        }
                        enterChunk();
                    }
                }
            }
            return true;
        }

        private void advance() {
            while (!exhausted) {
                if (test.inside(x, y, z)) {
                    BlockData data = supplier.dataAt(x, y, z);
                    if (data != null) {
                        next = new EditQueue.Write(x, y, z, data);
                        if (!step()) {
                            exhausted = true;
                        }
                        return;
                    }
                }
                if (!step()) {
                    exhausted = true;
                    next = null;
                    return;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public EditQueue.Write next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            EditQueue.Write out = next;
            next = null;
            advance();
            return out;
        }
    }

    // ---------------------------------------------------------------- shape tests

    public static final Test ALL = (x, y, z) -> true;

    /** Only the six outer faces of the box. */
    public static Test shell(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return (x, y, z) -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
    }

    /** The four vertical sides, no floor or ceiling. */
    public static Test walls(int minX, int minZ, int maxX, int maxZ) {
        return (x, y, z) -> x == minX || x == maxX || z == minZ || z == maxZ;
    }

    /** Ellipsoid, radii in blocks. Half-block bias makes odd and even diameters both look right. */
    public static Test ellipsoid(double cxd, double cyd, double czd, double rx, double ry, double rz,
                                 boolean hollow) {
        double ix = Math.max(0.0001, rx);
        double iy = Math.max(0.0001, ry);
        double iz = Math.max(0.0001, rz);
        return (x, y, z) -> {
            double dx = (x - cxd) / ix;
            double dy = (y - cyd) / iy;
            double dz = (z - czd) / iz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d > 1.0) {
                return false;
            }
            if (!hollow) {
                return true;
            }
            // A shell one block thick: inside the surface but not inside the next radius in.
            double sx = (x - cxd) / (ix - 1);
            double sy = (y - cyd) / (iy - 1);
            double sz = (z - czd) / (iz - 1);
            if (ix <= 1 || iy <= 1 || iz <= 1) {
                return true;
            }
            return sx * sx + sy * sy + sz * sz > 1.0;
        };
    }

    /** Vertical cylinder of given radii, full height of the given Y range. */
    public static Test cylinder(double cxd, double czd, double rx, double rz, boolean hollow) {
        double ix = Math.max(0.0001, rx);
        double iz = Math.max(0.0001, rz);
        return (x, y, z) -> {
            double dx = (x - cxd) / ix;
            double dz = (z - czd) / iz;
            double d = dx * dx + dz * dz;
            if (d > 1.0) {
                return false;
            }
            if (!hollow) {
                return true;
            }
            if (ix <= 1 || iz <= 1) {
                return true;
            }
            double sx = (x - cxd) / (ix - 1);
            double sz = (z - czd) / (iz - 1);
            return sx * sx + sz * sz > 1.0;
        };
    }

    /** Square pyramid rising from baseY, shrinking by one block per level on every side. */
    public static Test pyramid(int cxi, int baseY, int czi, int size, boolean hollow) {
        return (x, y, z) -> {
            int level = y - baseY;
            if (level < 0 || level > size) {
                return false;
            }
            int reach = size - level;
            int dx = Math.abs(x - cxi);
            int dz = Math.abs(z - czi);
            if (dx > reach || dz > reach) {
                return false;
            }
            if (!hollow) {
                return true;
            }
            return dx == reach || dz == reach || level == 0;
        };
    }

    // ---------------------------------------------------------------- suppliers

    public static Supplier pattern(BlockPattern pattern, Random random) {
        if (pattern.isSingle()) {
            BlockData one = pattern.single();
            return (x, y, z) -> one;
        }
        return (x, y, z) -> pattern.next(random);
    }

    /** Reads from a clipboard placed with its minimum corner at (ox,oy,oz). */
    public static Supplier clipboard(Clipboard clip, int ox, int oy, int oz, boolean includeAir) {
        return (x, y, z) -> {
            BlockData data = clip.at(x - ox, y - oy, z - oz);
            if (!includeAir && data.getMaterial().isAir()) {
                return null;         // null means "leave this block alone"
            }
            return data;
        };
    }
}
