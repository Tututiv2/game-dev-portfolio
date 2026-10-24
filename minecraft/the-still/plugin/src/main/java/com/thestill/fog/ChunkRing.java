package com.thestill.fog;

import java.util.Arrays;

/**
 * Which chunks the lamp is currently painting, as packed coordinates.
 *
 * <p>Deliberately free of Bukkit so the arithmetic can be tested without a server, which
 * is where the two bugs in this file's history would have been caught. Both were sign
 * bugs: the depths run to x −1920, and a pack that used {@code (x << 32) | z} without
 * masking turned every negative z into a chunk on the far side of the world, so the lamp
 * lit nothing and reported success.
 *
 * <p>The ring is a square, not a circle. Chunks are square, the client's own view is a
 * square, and a circular ring only saves a fifth of the packets while adding a radius test
 * that has to agree with the shed calculation below — two definitions of one outline, which
 * this project has already been bitten by once on the rig deck.
 */
public final class ChunkRing {

    private ChunkRing() {
    }

    /** Every chunk within {@code radius} of one, row-major and therefore stable. */
    public static long[] around(int chunkX, int chunkZ, int radius) {
        int span = radius * 2 + 1;
        long[] out = new long[span * span];
        int at = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out[at++] = key(chunkX + dx, chunkZ + dz);
            }
        }
        return out;
    }

    /**
     * What was in {@code before} and is not in {@code after} — the chunks the player has
     * walked out of.
     *
     * <p>These have to be put back explicitly. A biome packet is remembered by the client
     * until something replaces it, so without this the lamp leaves a permanent trail of
     * clear weather behind whoever is carrying it, which is both a giveaway and a
     * navigation aid nobody earned.
     */
    public static long[] shed(long[] before, long[] after) {
        if (before.length == 0) {
            return before;
        }
        long[] sorted = after.clone();
        Arrays.sort(sorted);
        long[] out = new long[before.length];
        int at = 0;
        for (long key : before) {
            if (Arrays.binarySearch(sorted, key) < 0) {
                out[at++] = key;
            }
        }
        return Arrays.copyOf(out, at);
    }

    /** Packs a chunk coordinate. Masked, because {@code z} is signed and often negative. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
