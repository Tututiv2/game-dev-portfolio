package com.thestill.fog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The wire format, pinned.
 *
 * <p>This is the one part of the fog lamp where being wrong is expensive rather than ugly:
 * the client reads 240 biome containers back to back with no resynchronisation point, so a
 * single extra or missing byte in section 0 does not corrupt section 0, it shreds the other
 * 239 and drops the player with a decode error. These tests are the reason the format was
 * read off {@code javap -c} instead of remembered.
 */
class BiomeBufferTest {

    @Test
    void aSectionIsOneZeroByteAndAVarint() {
        // bits-per-entry 0 (single-value palette), then the palette, which for a single
        // value is just the id. No storage: a zero-bit BitStorage has an empty long array,
        // and the array is written FIXED SIZE, so there is not even a length prefix.
        assertArrayEquals(new byte[] {0, 7}, BiomeBuffer.uniform(1, 7));
    }

    @Test
    void everySectionIsWritten() {
        // Not just the ones near the player. The client reads exactly as many containers as
        // the world has sections and a short buffer is a decode error, not a partial update.
        byte[] buffer = BiomeBuffer.uniform(240, 12);
        assertEquals(480, buffer.length);
        for (int i = 0; i < 240; i++) {
            assertEquals(0, buffer[i * 2]);
            assertEquals(12, buffer[i * 2 + 1]);
        }
    }

    @Test
    void aBigIdIsATwoByteVarint() {
        // 200 = 0b11001000 -> low seven bits with the continuation flag, then the rest.
        assertArrayEquals(new byte[] {0, (byte) 0xC8, 0x01}, BiomeBuffer.uniform(1, 200));
        assertEquals(1, BiomeBuffer.varIntSize(127));
        assertEquals(2, BiomeBuffer.varIntSize(128));
        assertEquals(3, BiomeBuffer.varIntSize(16384));
    }

    @Test
    void sectionsAreAWholeMultipleOfTheVarintSize() {
        // The stride has to be the SAME for every section — an id that crosses the 128
        // boundary must not make section 3 start one byte early. It is the same id for all
        // of them here, but the arithmetic that sizes the array has to agree with the
        // arithmetic that fills it, and this is the assertion that they do.
        byte[] buffer = BiomeBuffer.uniform(5, 300);
        assertEquals(15, buffer.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, buffer[i * 3]);
        }
    }

    @Test
    void aMissingBiomeIsRefusedRatherThanEncoded() {
        // A datapack biome that did not load reads back as -1 from the registry, and -1
        // encodes as a perfectly valid five-byte varint the client will accept and then
        // look up as garbage. Refusing turns a missing datapack into a startup message.
        assertThrows(IllegalArgumentException.class, () -> BiomeBuffer.uniform(240, -1));
    }

    @Test
    void aWorldWithNoSectionsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BiomeBuffer.uniform(0, 3));
    }
}
