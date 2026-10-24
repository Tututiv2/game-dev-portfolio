package com.thestill.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidTest {

    private static Cuboid box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Cuboid("world", x1, y1, z1, x2, y2, z2);
    }

    @Test
    @DisplayName("corners may be given in any order")
    void normalisesCorners() {
        Cuboid ascending = box(0, 0, 0, 10, 10, 10);
        Cuboid descending = box(10, 10, 10, 0, 0, 0);
        assertEquals(ascending, descending);
        assertEquals(0, descending.minX());
        assertEquals(10, descending.maxX());
    }

    @Test
    @DisplayName("bounds are inclusive on both ends")
    void boundsAreInclusive() {
        Cuboid cuboid = box(0, 0, 0, 4, 4, 4);
        assertTrue(cuboid.contains(0, 0, 0), "min corner is inside");
        assertTrue(cuboid.contains(4, 4, 4), "max corner is inside");
        assertFalse(cuboid.contains(5, 4, 4));
        assertFalse(cuboid.contains(-1, 0, 0));
        // 5x5x5, not 4x4x4 - an exclusive max would silently lose a whole face
        // of every mine, which is the kind of off-by-one nobody notices for months.
        assertEquals(125L, cuboid.volume());
    }

    @Test
    @DisplayName("contains checks the world, not just the coordinates")
    void worldMatters() {
        Cuboid here = box(0, 0, 0, 10, 10, 10);
        assertTrue(here.contains("world", 5, 5, 5));
        assertFalse(here.contains("nether", 5, 5, 5));
    }

    @Test
    @DisplayName("volume does not overflow int")
    void volumeIsLong() {
        // 4000 x 300 x 4000 = 4.8e9, well past Integer.MAX_VALUE
        Cuboid huge = box(0, 0, 0, 3999, 299, 3999);
        assertEquals(4_800_000_000L, huge.volume());
        assertTrue(huge.volume() > Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("containsFully accepts nesting and rejects a partial overlap")
    void containment() {
        Cuboid parent = box(0, 0, 0, 100, 100, 100);
        assertTrue(parent.containsFully(box(10, 10, 10, 20, 20, 20)));
        assertTrue(parent.containsFully(parent), "a box contains itself");
        assertFalse(parent.containsFully(box(90, 90, 90, 110, 110, 110)), "pokes out");
        assertFalse(parent.containsFully(new Cuboid("nether", 10, 10, 10, 20, 20, 20)));
    }

    @Test
    @DisplayName("intersects is true for a shared face and false for adjacency")
    void intersection() {
        Cuboid left = box(0, 0, 0, 10, 10, 10);
        assertTrue(left.intersects(box(10, 10, 10, 20, 20, 20)), "sharing one block counts");
        assertFalse(left.intersects(box(11, 0, 0, 20, 10, 10)), "touching but disjoint");
        assertFalse(left.intersects(box(0, 11, 0, 10, 20, 10)), "stacked but disjoint");
        assertFalse(left.intersects(new Cuboid("nether", 0, 0, 0, 10, 10, 10)));
    }

    @Test
    @DisplayName("expanded grows horizontally and vertically by different amounts")
    void expansion() {
        Cuboid grown = box(0, 0, 0, 10, 10, 10).expanded(4, 2);
        assertEquals(-4, grown.minX());
        assertEquals(-2, grown.minY());
        assertEquals(-4, grown.minZ());
        assertEquals(14, grown.maxX());
        assertEquals(12, grown.maxY());
        assertEquals(14, grown.maxZ());
    }

    @Test
    @DisplayName("blockAt visits every block exactly once")
    void blockAtIsABijection() {
        Cuboid cuboid = box(-3, 60, 7, 2, 63, 11); // 6 x 4 x 5 = 120
        assertEquals(120L, cuboid.volume());

        Set<String> seen = new HashSet<>();
        for (long i = 0; i < cuboid.volume(); i++) {
            int[] at = cuboid.blockAt(i, false);
            assertTrue(cuboid.contains(at[0], at[1], at[2]),
                    "index " + i + " produced a block outside the cuboid");
            assertTrue(seen.add(at[0] + "," + at[1] + "," + at[2]),
                    "index " + i + " repeated a block");
        }
        assertEquals(120, seen.size(), "every block reached");
    }

    @Test
    @DisplayName("blockAt fromTop starts at the ceiling")
    void blockAtFromTop() {
        Cuboid cuboid = box(0, 60, 0, 3, 63, 3);
        assertEquals(63, cuboid.blockAt(0, true)[1], "first block is the top layer");
        assertEquals(60, cuboid.blockAt(0, false)[1], "first block is the bottom layer");
        // A mine refill runs top-down so players are never buried mid-fill.
        assertEquals(60, cuboid.blockAt(cuboid.volume() - 1, true)[1]);
    }

    @Test
    @DisplayName("blockAt refuses an index outside the volume")
    void blockAtRejectsBadIndex() {
        Cuboid cuboid = box(0, 0, 0, 1, 1, 1);
        assertThrows(IndexOutOfBoundsException.class, () -> cuboid.blockAt(-1, false));
        assertThrows(IndexOutOfBoundsException.class, () -> cuboid.blockAt(8, false));
    }

    @Test
    @DisplayName("chunk coverage matches the block bounds, including negatives")
    void chunkCoverage() {
        // Blocks 0..15 are chunk 0; -1 is chunk -1. Arithmetic shift, not division,
        // or everything left of the origin lands in the wrong chunk.
        assertEquals(0, box(0, 0, 0, 15, 0, 15).minChunkX());
        assertEquals(0, box(0, 0, 0, 15, 0, 15).maxChunkX());
        assertEquals(1L, box(0, 0, 0, 15, 0, 15).chunkCount());

        Cuboid straddling = box(-1, 0, -1, 16, 0, 16);
        assertEquals(-1, straddling.minChunkX());
        assertEquals(1, straddling.maxChunkX());
        assertEquals(9L, straddling.chunkCount(), "3x3 chunks");
    }

    @Test
    @DisplayName("chunk keys are unique per chunk and survive negative coordinates")
    void chunkKeysAreUnique() {
        Set<Long> keys = new HashSet<>();
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                assertTrue(keys.add(Cuboid.chunkKey(x, z)), "collision at " + x + "," + z);
            }
        }
        assertEquals(81 * 81, keys.size());
        assertEquals(Cuboid.chunkKey(-1, -1), Cuboid.chunkKeyOfBlock(-16, -16));
        assertEquals(Cuboid.chunkKey(0, 0), Cuboid.chunkKeyOfBlock(15, 15));
        assertEquals(Cuboid.chunkKey(-1, 0), Cuboid.chunkKeyOfBlock(-1, 0));
    }
}
