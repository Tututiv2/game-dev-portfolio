package org.aincrad.plugin.editor;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads Minecraft region files and their chunk NBT directly, without the server's help.
 *
 * <p>This exists so worlds saved by <em>other</em> versions can be read - specifically the 1.16.5 SAO
 * Floor One world the owner downloaded. The server cannot open those chunks: Paper will happily
 * data-fix a whole world it owns, but there is no API for "read one chunk out of a foreign save that
 * is not registered as a dimension". Parsing the format is the direct route, and the format is small.</p>
 *
 * <h2>Format notes that actually matter</h2>
 * <ul>
 *   <li>A region header is 1,024 entries of (3-byte sector offset, 1-byte sector count). A zero offset
 *       means that chunk was never written - this is how "which chunks exist" is answered without
 *       asking the server, the same trick {@link FloorMapBoard} uses.</li>
 *   <li>Each chunk payload is a 4-byte length, a 1-byte compression id (1 = gzip, 2 = zlib), then NBT.</li>
 *   <li>NBT strings are modified UTF-8 with a two-byte length, which is exactly what
 *       {@link DataInputStream#readUTF()} reads. No hand-rolled string decoding needed.</li>
 *   <li><b>Block state packing changed in 1.16.</b> Before it, indices straddled long boundaries; from
 *       1.16 they do not, and each long simply holds {@code floor(64 / bits)} indices. Both layouts are
 *       implemented here and chosen by DataVersion, because guessing wrong does not throw - it produces
 *       plausible-looking scrambled terrain, which is far worse than an error.</li>
 * </ul>
 */
public final class RegionReader {

    /** 1.16 is DataVersion 2566+; the packing change landed in 20w17a / DataVersion 2529. */
    private static final int NON_STRADDLING_PACKING_FROM = 2529;

    private RegionReader() {
    }

    // ------------------------------------------------------------------ NBT

    private static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4;
    private static final int TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8;
    private static final int TAG_LIST = 9, TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asCompound(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static int asInt(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                byte[] out = new byte[in.readInt()];
                in.readFully(out);
                return out;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> out = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
                for (int i = 0; i < n; i++) {
                    out.add(readPayload(in, elementType));
                }
                return out;
            }
            case TAG_COMPOUND: {
                Map<String, Object> out = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == TAG_END) {
                        return out;
                    }
                    String name = in.readUTF();
                    out.put(name, readPayload(in, t));
                }
            }
            case TAG_INT_ARRAY: {
                int[] out = new int[in.readInt()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = in.readInt();
                }
                return out;
            }
            case TAG_LONG_ARRAY: {
                long[] out = new long[in.readInt()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = in.readLong();
                }
                return out;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    public static Map<String, Object> readNbt(byte[] raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(raw)))) {
            int type = in.readUnsignedByte();
            in.readUTF();                       // root name, always empty in practice
            Object root = readPayload(in, type);
            return asCompound(root);
        }
    }

    // ------------------------------------------------------------------ region files

    /** Which chunks a region file actually contains, from its header alone. */
    public static List<int[]> listChunks(File regionFile, int rx, int rz) throws IOException {
        byte[] header = new byte[4096];
        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            raf.readFully(header);
        }
        List<int[]> out = new ArrayList<>();
        for (int local = 0; local < 1024; local++) {
            int at = local * 4;
            int offset = ((header[at] & 0xFF) << 16) | ((header[at + 1] & 0xFF) << 8) | (header[at + 2] & 0xFF);
            if (offset == 0 || (header[at + 3] & 0xFF) == 0) {
                continue;
            }
            out.add(new int[]{(rx << 5) + (local & 31), (rz << 5) + (local >> 5)});
        }
        return out;
    }

    /**
     * Where a world keeps its region files. A custom dimension puts them under
     * {@code <level>/dimensions/<namespace>/<path>/region}; the primary world keeps them at the top
     * level. Both layouts are tried, so callers do not each have to know that.
     *
     * <p>Takes the folder and key as plain values rather than a {@code World} so this class stays a
     * pure format reader with no server dependency - which is what lets it read foreign saves.</p>
     */
    public static File regionFolder(File worldFolder, String namespace, String key) {
        File direct = new File(worldFolder, "region");
        if (direct.isDirectory()) {
            return direct;
        }
        return new File(worldFolder, "dimensions/" + namespace + "/" + key + "/region");
    }

    /** Raw chunk NBT, or null when that chunk is absent or unreadable. */
    public static Map<String, Object> readChunk(File regionFile, int cx, int cz) throws IOException {
        int local = (cz & 31) * 32 + (cx & 31);
        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            raf.seek(local * 4L);
            int b0 = raf.readUnsignedByte();
            int b1 = raf.readUnsignedByte();
            int b2 = raf.readUnsignedByte();
            int sectors = raf.readUnsignedByte();
            int offset = (b0 << 16) | (b1 << 8) | b2;
            if (offset == 0 || sectors == 0) {
                return null;
            }
            raf.seek(offset * 4096L);
            int length = raf.readInt();
            int compression = raf.readUnsignedByte();
            if (length <= 1 || length > 1 << 24) {
                return null;
            }
            byte[] payload = new byte[length - 1];
            raf.readFully(payload);
            byte[] raw = decompress(payload, compression);
            return raw == null ? null : readNbt(raw);
        }
    }

    private static byte[] decompress(byte[] payload, int compression) throws IOException {
        try (var source = new ByteArrayInputStream(payload)) {
            var stream = switch (compression) {
                case 1 -> new GZIPInputStream(source);
                case 2 -> new InflaterInputStream(source);
                default -> null;
            };
            if (stream == null) {
                return null;      // uncompressed (3) and custom codecs are not produced by vanilla saves
            }
            try (stream) {
                return stream.readAllBytes();
            }
        }
    }

    // ------------------------------------------------------------------ decoding blocks

    /** One decoded chunk: block state strings addressable by world coordinate. */
    public static final class DecodedChunk {
        public final int chunkX;
        public final int chunkZ;
        /** Lowest and highest Y that carry block data. Set once decoding finishes. */
        public int minY;
        public int maxY;
        /** Section index by (y >> 4) - minSection, each 4,096 palette indices, or null when absent. */
        private final Map<Integer, short[]> sections = new HashMap<>();
        private final Map<Integer, String[]> palettes = new HashMap<>();

        DecodedChunk(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        /** Block state string, e.g. "minecraft:oak_stairs[facing=north]", or null for absent/air. */
        public String stateAt(int localX, int y, int localZ) {
            int section = y >> 4;
            short[] cells = sections.get(section);
            if (cells == null) {
                return null;
            }
            String[] palette = palettes.get(section);
            int index = ((y & 15) * 256) + (localZ * 16) + localX;
            int id = cells[index] & 0xFFFF;
            return id < palette.length ? palette[id] : null;
        }

        public boolean hasAnySection() {
            return !sections.isEmpty();
        }
    }

    /**
     * Decodes a chunk's block sections.
     *
     * @return null when the chunk carries no block data at all
     */
    public static DecodedChunk decode(Map<String, Object> chunkNbt, int cx, int cz) {
        return decode(chunkNbt, cx, cz, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Decodes only the sections within a Y band, given as section indices ({@code y >> 4}).
     *
     * <p>The Aincrad dimension is 2,032 blocks tall, so a modern save writes 127 sections per chunk -
     * and a reader that only cares about the surface would otherwise unpack all of them, allocating
     * about a megabyte per chunk to look at three sections. Callers that know their Y range say so.</p>
     */
    public static DecodedChunk decode(Map<String, Object> chunkNbt, int cx, int cz,
                                      int minSectionY, int maxSectionY) {
        if (chunkNbt == null) {
            return null;
        }
        int dataVersion = asInt(chunkNbt.get("DataVersion"), 0);
        Map<String, Object> level = asCompound(chunkNbt.get("Level"));
        // 1.18+ flattened the root and renamed the section list; both layouts are accepted so this
        // reader is not silently useless on a modern save.
        Map<String, Object> holder = level != null ? level : chunkNbt;
        List<Object> sectionList = asList(holder.get("Sections"));
        boolean modern = false;
        if (sectionList == null) {
            sectionList = asList(holder.get("sections"));
            modern = sectionList != null;
        }
        if (sectionList == null) {
            return null;
        }

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        DecodedChunk out = new DecodedChunk(cx, cz);
        for (Object element : sectionList) {
            Map<String, Object> section = asCompound(element);
            if (section == null) {
                continue;
            }
            int sectionY = asInt(section.get("Y"), Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE || sectionY < minSectionY || sectionY > maxSectionY) {
                continue;
            }

            List<Object> paletteList;
            long[] packed;
            if (modern) {
                Map<String, Object> states = asCompound(section.get("block_states"));
                if (states == null) {
                    continue;
                }
                paletteList = asList(states.get("palette"));
                Object data = states.get("data");
                packed = data instanceof long[] longs ? longs : null;
            } else {
                paletteList = asList(section.get("Palette"));
                Object data = section.get("BlockStates");
                packed = data instanceof long[] longs ? longs : null;
            }
            if (paletteList == null || paletteList.isEmpty()) {
                // Pre-1.13 section: numeric ids in Blocks, a 4-bit metadata nibble in Data, and an
                // optional Add nibble for ids above 255. Decoded into the same palette-index form the
                // flattened path produces, so everything downstream is unaware of the difference.
                Object rawBlocks = section.get("Blocks");
                if (!(rawBlocks instanceof byte[] blocks) || blocks.length < 4096) {
                    continue;
                }
                byte[] meta = section.get("Data") instanceof byte[] d ? d : null;
                byte[] add = section.get("Add") instanceof byte[] ad ? ad : null;
                java.util.List<String> legacyPalette = new ArrayList<>();
                java.util.Map<String, Short> legacyIndex = new HashMap<>();
                legacyPalette.add("minecraft:air");
                legacyIndex.put("minecraft:air", (short) 0);
                short[] legacyCells = new short[4096];
                for (int i = 0; i < 4096; i++) {
                    int id = blocks[i] & 0xFF;
                    if (add != null) {
                        int high = nibble(add, i);
                        id |= high << 8;
                    }
                    if (id == 0) {
                        continue;
                    }
                    int m = meta == null ? 0 : nibble(meta, i);
                    String state = LegacyBlocks.lookup(id, m);
                    if (state == null) {
                        unmappedLegacy.merge(id + ":" + m, 1, Integer::sum);
                        continue;
                    }
                    Short idx = legacyIndex.get(state);
                    if (idx == null) {
                        idx = (short) legacyPalette.size();
                        legacyPalette.add(state);
                        legacyIndex.put(state, idx);
                    }
                    legacyCells[i] = idx;
                }
                if (legacyPalette.size() == 1) {
                    continue;      // the whole section mapped to nothing
                }
                out.sections.put(sectionY, legacyCells);
                out.palettes.put(sectionY, legacyPalette.toArray(new String[0]));
                minY = Math.min(minY, sectionY << 4);
                maxY = Math.max(maxY, (sectionY << 4) + 15);
                continue;
            }

            String[] palette = new String[paletteList.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = stateString(asCompound(paletteList.get(i)));
            }

            short[] cells;
            if (packed == null || palette.length == 1) {
                // A single-entry palette needs no data array: every block in the section is that block.
                cells = new short[4096];
            } else {
                cells = unpack(packed, palette.length,
                        dataVersion >= NON_STRADDLING_PACKING_FROM || modern);
                if (cells == null) {
                    continue;
                }
            }
            out.sections.put(sectionY, cells);
            out.palettes.put(sectionY, palette);
            minY = Math.min(minY, sectionY << 4);
            maxY = Math.max(maxY, (sectionY << 4) + 15);
        }
        if (!out.hasAnySection()) {
            return null;
        }
        out.minY = minY;
        out.maxY = maxY;
        return out;
    }

    /** Little-endian 4-bit array as used by pre-1.13 Data/Add/light arrays. */
    private static int nibble(byte[] packed, int index) {
        int b = packed[index >> 1] & 0xFF;
        return (index & 1) == 0 ? (b & 0x0F) : (b >> 4);
    }

    /** Legacy ids the table does not cover, as "id:meta" -> count. Reported, never silently dropped. */
    private static final Map<String, Integer> unmappedLegacy = new java.util.concurrent.ConcurrentHashMap<>();

    public static Map<String, Integer> unmappedLegacyIds() {
        return new java.util.TreeMap<>(unmappedLegacy);
    }

    /** Renders a palette entry as a vanilla block-state string. */
    private static String stateString(Map<String, Object> entry) {
        if (entry == null) {
            return "minecraft:air";
        }
        Object name = entry.get("Name");
        if (name == null) {
            name = entry.get("name");
        }
        String base = name instanceof String s ? s : "minecraft:air";
        Map<String, Object> props = asCompound(entry.get("Properties"));
        if (props == null) {
            props = asCompound(entry.get("properties"));
        }
        if (props == null || props.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue()));
        }
        return sb.append(']').toString();
    }

    /**
     * Unpacks 4,096 palette indices.
     *
     * @param nonStraddling true for 1.16 and later, where an index never spans two longs
     */
    static short[] unpack(long[] packed, int paletteSize, boolean nonStraddling) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        if (paletteSize <= 1) {
            bits = 4;
        }
        long mask = (1L << bits) - 1;
        short[] out = new short[4096];
        if (nonStraddling) {
            int perLong = 64 / bits;
            int index = 0;
            for (long value : packed) {
                for (int slot = 0; slot < perLong && index < 4096; slot++) {
                    out[index++] = (short) ((value >>> (slot * bits)) & mask);
                }
                if (index >= 4096) {
                    break;
                }
            }
            return index == 4096 ? out : null;
        }
        // Pre-1.16: a continuous bit stream, so an index can span the boundary between two longs.
        for (int index = 0; index < 4096; index++) {
            long bitStart = (long) index * bits;
            int longIndex = (int) (bitStart >> 6);
            int bitOffset = (int) (bitStart & 63);
            if (longIndex >= packed.length) {
                return null;
            }
            long value = packed[longIndex] >>> bitOffset;
            if (bitOffset + bits > 64) {
                if (longIndex + 1 >= packed.length) {
                    return null;
                }
                value |= packed[longIndex + 1] << (64 - bitOffset);
            }
            out[index] = (short) (value & mask);
        }
        return out;
    }
}
