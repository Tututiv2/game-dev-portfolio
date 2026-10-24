package com.thestill.fog;

/**
 * The wire format of a chunk's biomes, built by hand.
 *
 * <p>This is the payload of {@code ClientboundChunksBiomesPacket.ChunkBiomeData} — a
 * biome-only chunk update, which is the one packet in the game that changes what
 * weather a player is standing in without resending a single block.
 *
 * <p><b>Why it is written here rather than borrowed from the server.</b> Mojang's own
 * serialiser reads the chunk out of the world, so using it would mean changing the
 * world's biomes to send the packet — and the world is shared. A lamp that repaints
 * the world is a lamp that lights up everyone standing near you, which is the exact
 * thing this whole class exists to avoid. So the buffer is built from nothing and sent
 * to one connection, and the server's own copy of the world is never touched. That is
 * also what makes the failure mode impossible rather than merely unlikely: nothing was
 * changed, so nothing can be left changed. Any chunk reload, relog, crash or restart
 * puts the player back in the real weather for free.
 *
 * <p><b>The format, read out of Paper 26.1.2 with {@code javap -c} rather than guessed.</b>
 * {@code ChunkBiomeData.extractChunkData} walks the chunk's sections bottom to top and
 * writes each section's biome {@code PalettedContainer}; {@code PalettedContainer$Data.write}
 * is exactly three things — {@code writeByte(bitsPerEntry)}, {@code palette.write(buf, idMap)},
 * {@code writeFixedSizeLongArray(storage.getRaw())}. For a section that is entirely one
 * biome that comes to:
 *
 * <pre>
 *   byte    0x00        bits per entry — zero, meaning a single-value palette
 *   varint  biomeId     the palette, which for a single value is just the id
 *   (nothing)           ZeroBitStorage has an empty long[], and the array is FIXED SIZE,
 *                       so there is no length prefix and no data
 * </pre>
 *
 * <p>Two bytes per section for a small id, repeated once per section, with no header and
 * no trailer — the packet's own {@code writeByteArray} supplies the length. Note the
 * <em>fixed size</em> long array: an earlier reading of this assumed the usual
 * length-prefixed {@code writeLongArray} and would have put one extra zero byte in every
 * section. The client reads the sections back to back with no resynchronisation point, so
 * one wrong byte does not corrupt one section, it shreds the other 239 and drops the
 * player with a decode error. This is why the shape was taken off the bytecode.
 */
public final class BiomeBuffer {

    private BiomeBuffer() {
    }

    /**
     * A whole chunk of one biome.
     *
     * <p>Every section, not just the ones near the player, because the client reads
     * exactly {@code sections} containers back to back and a short buffer is a decode
     * error rather than a partial update. In a 3,840-block dimension that is 240 sections
     * and about 480 bytes — smaller than the chunk packet it replaces by three orders of
     * magnitude, which is the entire reason this packet exists.
     *
     * @param sections how many 16-block sections the world has, i.e.
     *                 {@code (maxHeight - minHeight) / 16}
     * @param biomeId  the biome's numeric id in the server's biome registry
     */
    public static byte[] uniform(int sections, int biomeId) {
        if (sections <= 0) {
            throw new IllegalArgumentException("a world with " + sections + " sections");
        }
        if (biomeId < 0) {
            // A datapack biome that failed to load reads back as -1 from the registry, and
            // -1 encodes as a five-byte varint the client will happily accept and then look
            // up as garbage. Refusing here turns a missing datapack into a startup message
            // instead of a client-side mystery.
            throw new IllegalArgumentException("biome id " + biomeId + " — is the datapack loaded?");
        }
        int perSection = 1 + varIntSize(biomeId);
        byte[] out = new byte[sections * perSection];
        int at = 0;
        for (int i = 0; i < sections; i++) {
            out[at++] = 0;                       // bits per entry: single-value palette
            at = writeVarInt(out, at, biomeId);  // the palette
            // and nothing else: a zero-bit storage is an empty fixed-size long array
        }
        return out;
    }

    /** Bytes a Minecraft varint takes. Seven bits of payload per byte, top bit is "more". */
    public static int varIntSize(int value) {
        int bytes = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            remaining >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /** Writes one varint at {@code at}, and returns where the next thing goes. */
    public static int writeVarInt(byte[] out, int at, int value) {
        int remaining = value;
        int cursor = at;
        while ((remaining & ~0x7F) != 0) {
            out[cursor++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out[cursor++] = (byte) remaining;
        return cursor;
    }
}
