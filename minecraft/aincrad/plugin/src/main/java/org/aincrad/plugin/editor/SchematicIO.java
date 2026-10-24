package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes clipboards as files, so a selection becomes a reusable <em>part</em>.
 *
 * <p>This is the piece the build generator needs: a generator that assembles buildings from modular
 * parts is only as good as its library, and a library is just a folder of these. Saving is therefore
 * not a convenience feature here - it is the input format for everything that comes after.</p>
 *
 * <h2>Why a custom format rather than .schem</h2>
 * <p>WorldEdit's Sponge schematic format is NBT wrapped around a varint block array with its own
 * palette and metadata conventions. Supporting it would mean matching another project's evolving spec
 * to interoperate with a plugin that is deliberately not installed. What is actually needed is: block
 * states, dimensions, and the paste offset. That is four numbers and a list of strings, so the format
 * is written plainly and documented here rather than borrowed.</p>
 *
 * <pre>
 *   magic   "AEDS"            4 bytes, so a wrong file fails immediately instead of decoding to noise
 *   version int               1
 *   w,h,l   int x3            dimensions
 *   ox,oy,oz int x3           offset from the copying player to the minimum corner
 *   n       int               palette size
 *   n x     UTF               block state strings, e.g. "minecraft:oak_stairs[facing=north]"
 *   w*h*l   short             palette indices, in x + w*(z + l*y) order
 * </pre>
 *
 * <p>Gzipped, because block data is extremely repetitive - a 32x16x32 house part drops from about
 * 32 KB to under 2 KB.</p>
 */
public final class SchematicIO {

    private static final int MAGIC = 0x41454453;      // "AEDS"
    private static final int VERSION = 1;
    public static final String EXTENSION = ".aeds";

    private SchematicIO() {
    }

    /** Rejects anything that could escape the schematics folder or upset the filesystem. */
    public static String sanitise(String name) {
        String cleaned = name.toLowerCase().replaceAll("[^a-z0-9._/-]", "_");
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_");
        }
        while (cleaned.startsWith("/") || cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    public static File fileFor(File root, String name) {
        String safe = sanitise(name);
        if (!safe.endsWith(EXTENSION)) {
            safe = safe + EXTENSION;
        }
        return new File(root, safe);
    }

    public static void save(Clipboard clip, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target.toPath()))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(clip.width());
            out.writeInt(clip.height());
            out.writeInt(clip.length());
            out.writeInt(clip.offsetX());
            out.writeInt(clip.offsetY());
            out.writeInt(clip.offsetZ());
            List<BlockData> palette = clip.palette();
            out.writeInt(palette.size());
            for (BlockData data : palette) {
                out.writeUTF(data.getAsString());
            }
            for (short cell : clip.cells()) {
                out.writeShort(cell);
            }
        }
    }

    public static Clipboard load(File source) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(source.toPath()))))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not an editor schematic (wrong magic bytes)");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("schematic version " + version + ", this build reads " + VERSION);
            }
            int w = in.readInt();
            int h = in.readInt();
            int l = in.readInt();
            int ox = in.readInt();
            int oy = in.readInt();
            int oz = in.readInt();
            long cells = (long) w * h * l;
            if (w <= 0 || h <= 0 || l <= 0 || cells > 64_000_000L) {
                throw new IOException("implausible dimensions " + w + "x" + h + "x" + l);
            }
            int paletteSize = in.readInt();
            if (paletteSize <= 0 || paletteSize > Short.MAX_VALUE) {
                throw new IOException("implausible palette size " + paletteSize);
            }
            List<BlockData> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                String state = in.readUTF();
                BlockData data;
                try {
                    data = Bukkit.createBlockData(state);
                } catch (IllegalArgumentException ex) {
                    // A part saved on an older build may name a block this one lacks. Substituting air
                    // keeps the rest of the part usable and leaves a visible gap rather than aborting.
                    data = Bukkit.createBlockData(org.bukkit.Material.AIR);
                }
                palette.add(data);
            }
            short[] data = new short[(int) cells];
            for (int i = 0; i < data.length; i++) {
                data[i] = in.readShort();
            }
            return new Clipboard(w, h, l, ox, oy, oz, data, palette);
        }
    }

    /** Every saved part under a folder, as names without the extension. */
    public static List<String> list(File root, String subfolder) {
        File dir = subfolder == null || subfolder.isBlank() ? root : new File(root, sanitise(subfolder));
        File[] files = dir.listFiles((d, n) -> n.endsWith(EXTENSION));
        List<String> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                out.add(f.getName().substring(0, f.getName().length() - EXTENSION.length()));
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
