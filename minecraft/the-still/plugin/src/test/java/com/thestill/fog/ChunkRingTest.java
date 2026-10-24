package com.thestill.fog;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkRingTest {

    @Test
    void theRingIsASquareOfTheRightSize() {
        assertEquals(9, ChunkRing.around(0, 0, 1).length);
        assertEquals(49, ChunkRing.around(0, 0, 3).length);
        assertEquals(1, ChunkRing.around(5, -9, 0).length);
    }

    @Test
    void negativeCoordinatesSurviveThePack() {
        // The depths run to x -1920 and z -1920, so most of the world is negative. A pack
        // of (x << 32) | z without the mask sign-extends z into the high half and turns
        // every southern chunk into one on the far side of the world — the lamp then lights
        // nothing at all and reports that it worked.
        for (int x : new int[] {0, 1, -1, 120, -120, Integer.MIN_VALUE / 2}) {
            for (int z : new int[] {0, 1, -1, 120, -120, Integer.MIN_VALUE / 2}) {
                long key = ChunkRing.key(x, z);
                assertEquals(x, ChunkRing.chunkX(key), "x round trip at " + x + "," + z);
                assertEquals(z, ChunkRing.chunkZ(key), "z round trip at " + x + "," + z);
            }
        }
    }

    @Test
    void theRingIsCentredWhereItWasAsked() {
        long[] ring = ChunkRing.around(-40, -7, 2);
        Set<Long> keys = new HashSet<>();
        Arrays.stream(ring).forEach(keys::add);
        assertEquals(25, keys.size(), "no duplicates");
        assertTrue(keys.contains(ChunkRing.key(-40, -7)), "the centre is in it");
        assertTrue(keys.contains(ChunkRing.key(-42, -9)), "and both far corners");
        assertTrue(keys.contains(ChunkRing.key(-38, -5)));
    }

    @Test
    void steppingOneChunkShedsExactlyOneEdge() {
        // The chunks the player walked out of. These have to be put back explicitly: a
        // biome packet is remembered by the client until something replaces it, so without
        // the shed set the lamp leaves a permanent trail of clear weather behind whoever
        // is carrying it.
        long[] before = ChunkRing.around(0, 0, 3);
        long[] after = ChunkRing.around(1, 0, 3);
        long[] gone = ChunkRing.shed(before, after);

        assertEquals(7, gone.length, "one column of a 7-wide ring");
        for (long key : gone) {
            assertEquals(-3, ChunkRing.chunkX(key), "and it is the trailing column");
        }
    }

    @Test
    void nothingIsShedWhenNothingMoves() {
        long[] ring = ChunkRing.around(12, -300, 2);
        assertEquals(0, ChunkRing.shed(ring, ring).length);
        assertEquals(0, ChunkRing.shed(new long[0], ring).length);
    }

    @Test
    void everythingIsShedWhenTheRingMovesRightAway() {
        // Teleporting out — the winch, a respawn — is a ring that shares nothing with the
        // old one, and every chunk of the old one has to be restored.
        long[] before = ChunkRing.around(0, 0, 2);
        long[] after = ChunkRing.around(500, 500, 2);
        assertEquals(before.length, ChunkRing.shed(before, after).length);
    }
}
