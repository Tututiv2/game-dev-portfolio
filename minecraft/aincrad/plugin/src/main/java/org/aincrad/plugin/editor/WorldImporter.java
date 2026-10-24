package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Brings a world saved by another Minecraft version into this one.
 *
 * <p>Built for the 1.16.5 SAO Floor One world the owner found. Reading it is {@link RegionReader}'s
 * job; this class deals with the two things that make importing across nine versions actually hard.</p>
 *
 * <h2>Block names move</h2>
 * <p>Between 1.16.5 and 26.1.2 a handful of blocks were renamed, and the downloaded world uses several
 * of them heavily - {@code minecraft:grass} alone accounts for 114,693 surface blocks and became
 * {@code short_grass} in 1.20.3. A translator that only knew current names would turn all of that into
 * holes. So every unrecognised name goes through a rename table, then a property-stripping retry, and
 * anything still unknown is <b>counted and reported by name</b> rather than quietly skipped. The report
 * is the point: it tells us exactly what to add to the table instead of leaving us to notice gaps by
 * eye.</p>
 *
 * <h2>Scanning first is not optional</h2>
 * <p>Their world is 7,217 chunks. Importing blind would mean discovering halfway through that a third
 * of the blocks are unknown. {@code //import <name>} therefore only ever <em>scans</em>, and reports
 * what it found; writing requires {@code //import <name> confirm}. Nothing is written by accident.</p>
 */
public final class WorldImporter {

    /**
     * 1.16.5 -> current renames. Deliberately short: it holds only what is actually needed, and the
     * scan's "unknown blocks" report is how anything missing gets found rather than by guesswork.
     */
    private static final Map<String, String> RENAMES = Map.ofEntries(
            Map.entry("minecraft:grass", "minecraft:short_grass"),          // renamed in 1.20.3
            Map.entry("minecraft:grass_path", "minecraft:dirt_path"),       // renamed in 1.17
            Map.entry("minecraft:scute", "minecraft:turtle_scute"),
            Map.entry("minecraft:cauldron", "minecraft:cauldron"),          // levels handled separately
            Map.entry("minecraft:sign", "minecraft:oak_sign"),
            Map.entry("minecraft:wall_sign", "minecraft:oak_wall_sign"),
            Map.entry("minecraft:rose_bush", "minecraft:rose_bush"),
            Map.entry("minecraft:snow_block", "minecraft:snow_block"));

    private final Plugin plugin;
    private final Editor editor;

    /** Resolved block data per source state string. A null value means "known to be untranslatable". */
    private final Map<String, BlockData> translated = new HashMap<>();
    private final Map<String, Integer> unknown = new TreeMap<>();

    public WorldImporter(Plugin plugin, Editor editor) {
        this.plugin = plugin;
        this.editor = editor;
    }

    /** Where importable worlds are looked for: {@code server/imports/<name>}. */
    public File importsRoot() {
        return new File(plugin.getDataFolder().getParentFile().getParentFile(), "imports");
    }

    /**
     * Finds the region directory inside an extracted save, accepting either {@code <dir>/region} or a
     * single wrapper folder such as {@code <dir>/F1 not finished/region}, because that is how these
     * archives are actually laid out.
     */
    public File findRegionDir(File base) {
        File direct = new File(base, "region");
        if (direct.isDirectory()) {
            return direct;
        }
        File[] children = base.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                File nested = new File(child, "region");
                if (nested.isDirectory()) {
                    return nested;
                }
            }
        }
        return null;
    }

    public record Scan(int regionFiles, int chunks, int decoded, int minX, int maxX, int minZ, int maxZ,
                       int minY, int maxY, long solidBlocks, long surfaceColumns,
                       Map<String, Long> topSurface, Map<String, Integer> unknownNames,
                       int dataVersion) {
    }

    /** Reads everything, writes nothing. Runs off the main thread; the callback returns to it. */
    public void scan(CommandSender to, File regionDir, java.util.function.Consumer<Scan> then) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Scan result;
            try {
                result = doScan(regionDir);
            } catch (RuntimeException ex) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        Editor.tell(to, "<red>Scan failed: " + ex));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> then.accept(result));
        });
    }

    private Scan doScan(File regionDir) {
        File[] files = regionDir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca"));
        if (files == null) {
            files = new File[0];
        }
        int chunks = 0;
        int decoded = 0;
        long solid = 0;
        long columns = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int dataVersion = 0;
        Map<String, Long> surface = new HashMap<>();

        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            int rx;
            int rz;
            try {
                rx = Integer.parseInt(parts[1]);
                rz = Integer.parseInt(parts[2]);
            } catch (RuntimeException ex) {
                continue;
            }
            List<int[]> present;
            try {
                present = RegionReader.listChunks(file, rx, rz);
            } catch (IOException ex) {
                continue;
            }
            for (int[] coord : present) {
                chunks++;
                Map<String, Object> nbt;
                try {
                    nbt = RegionReader.readChunk(file, coord[0], coord[1]);
                } catch (IOException ex) {
                    continue;
                }
                if (nbt == null) {
                    continue;
                }
                if (dataVersion == 0) {
                    dataVersion = RegionReader.asInt(nbt.get("DataVersion"), 0);
                }
                RegionReader.DecodedChunk chunk = RegionReader.decode(nbt, coord[0], coord[1]);
                if (chunk == null) {
                    continue;
                }
                decoded++;
                minX = Math.min(minX, coord[0] << 4);
                maxX = Math.max(maxX, (coord[0] << 4) + 15);
                minZ = Math.min(minZ, coord[1] << 4);
                maxZ = Math.max(maxZ, (coord[1] << 4) + 15);
                minY = Math.min(minY, chunk.minY);
                maxY = Math.max(maxY, chunk.maxY);

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        String top = null;
                        for (int y = chunk.maxY; y >= chunk.minY; y--) {
                            String state = chunk.stateAt(lx, y, lz);
                            if (state == null || isAirName(state)) {
                                continue;
                            }
                            solid++;
                            if (top == null) {
                                top = state;
                            }
                        }
                        if (top != null) {
                            columns++;
                            surface.merge(baseName(top), 1L, Long::sum);
                        }
                    }
                }
            }
        }

        // Resolve every distinct surface name so the unknown-block report is real rather than a guess.
        Map<String, Integer> unknownNames = new TreeMap<>();
        for (String name : surface.keySet()) {
            if (resolveOffThread(name) == null) {
                unknownNames.put(name, surface.get(name).intValue());
            }
        }

        Map<String, Long> top = new java.util.LinkedHashMap<>();
        surface.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> top.put(e.getKey(), e.getValue()));

        return new Scan(files.length, chunks, decoded,
                minX == Integer.MAX_VALUE ? 0 : minX, maxX == Integer.MIN_VALUE ? 0 : maxX,
                minZ == Integer.MAX_VALUE ? 0 : minZ, maxZ == Integer.MIN_VALUE ? 0 : maxZ,
                minY == Integer.MAX_VALUE ? 0 : minY, maxY == Integer.MIN_VALUE ? 0 : maxY,
                solid, columns, top, unknownNames, dataVersion);
    }

    public void report(CommandSender to, Scan scan, String name) {
        Editor.tell(to, "<aqua>Scanned <white>" + name + "</white>");
        Editor.raw(to, "  <gray>Saved by Minecraft data version <white>" + scan.dataVersion()
                + "</white>" + versionHint(scan.dataVersion()));
        Editor.raw(to, "  <gray>Region files <white>" + scan.regionFiles() + "</white>, chunks with data "
                + "<white>" + Editor.format(scan.decoded()) + "</white> of " + Editor.format(scan.chunks()));
        Editor.raw(to, "  <gray>Extent X <white>" + scan.minX() + ".." + scan.maxX() + "</white> ("
                + (scan.maxX() - scan.minX() + 1) + " wide), Z <white>" + scan.minZ() + ".." + scan.maxZ()
                + "</white> (" + (scan.maxZ() - scan.minZ() + 1) + " deep)");
        Editor.raw(to, "  <gray>Height <white>" + scan.minY() + ".." + scan.maxY() + "</white>");
        Editor.raw(to, "  <gray>Solid blocks <white>" + Editor.format(scan.solidBlocks())
                + "</white>, surface columns <white>" + Editor.format(scan.surfaceColumns()) + "</white>");
        Editor.raw(to, "  <gray>Most common at the surface:");
        int shown = 0;
        for (Map.Entry<String, Long> e : scan.topSurface().entrySet()) {
            if (shown++ >= 8) {
                break;
            }
            Editor.raw(to, "     <white>" + String.format("%,10d", e.getValue()) + "</white>  "
                    + String.format("%5.1f%%", 100.0 * e.getValue() / Math.max(1, scan.surfaceColumns()))
                    + "  <gray>" + e.getKey().replace("minecraft:", ""));
        }
        if (scan.unknownNames().isEmpty()) {
            Editor.raw(to, "  <green>Every surface block name exists in this version. "
                    + "Nothing would be lost.");
        } else {
            Editor.raw(to, "  <yellow>" + scan.unknownNames().size()
                    + " block name(s) do not exist in this version and would be skipped:");
            scan.unknownNames().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> Editor.raw(to, "     <yellow>" + Editor.format(e.getValue())
                            + "</yellow> x " + e.getKey()));
        }
    }

    private static String versionHint(int dataVersion) {
        if (dataVersion == 0) {
            return "";
        }
        if (dataVersion == 2586) {
            return " <dark_gray>(1.16.5)";
        }
        if (dataVersion < 2529) {
            return " <dark_gray>(pre-1.16 block packing)";
        }
        return "";
    }

    // ------------------------------------------------------------------ translation

    static boolean isAirName(String state) {
        return state.startsWith("minecraft:air") || state.startsWith("minecraft:cave_air")
                || state.startsWith("minecraft:void_air");
    }

    static String baseName(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /**
     * Resolves a source block state into one this version understands.
     *
     * <p>Bukkit's block-data factory is safe to call from any thread, so the scan can pre-resolve names
     * without bouncing to the main thread for each of them.</p>
     *
     * @return null when this version has no equivalent
     */
    public BlockData resolve(String sourceState) {
        return translated.computeIfAbsent(sourceState, this::resolveOffThread);
    }

    private BlockData resolveOffThread(String sourceState) {
        BlockData direct = tryCreate(sourceState);
        if (direct != null) {
            return direct;
        }
        String base = baseName(sourceState);
        String renamedBase = RENAMES.get(base);
        if (renamedBase != null && !renamedBase.equals(base)) {
            String swapped = sourceState.replaceFirst(java.util.regex.Pattern.quote(base), renamedBase);
            BlockData renamed = tryCreate(swapped);
            if (renamed != null) {
                return renamed;
            }
            BlockData bare = tryCreate(renamedBase);
            if (bare != null) {
                return bare;
            }
        }
        // Properties change more often than names do, so a name that exists with unfamiliar properties
        // is far better placed in its default state than dropped entirely.
        BlockData stripped = tryCreate(base);
        if (stripped != null) {
            return stripped;
        }
        synchronized (unknown) {
            unknown.merge(base, 1, Integer::sum);
        }
        return null;
    }

    private static BlockData tryCreate(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Map<String, Integer> unknownEncountered() {
        synchronized (unknown) {
            return new TreeMap<>(unknown);
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Streams one source chunk's non-air blocks as a write source, offset into this world.
     *
     * <p>One chunk per queued operation keeps each unit of work small enough that {@link EditQueue}'s
     * per-tick budget stays meaningful, and means a failure part-way through leaves a clean boundary
     * rather than half a chunk.</p>
     */
    public EditQueue.WriteSource writesFor(RegionReader.DecodedChunk chunk, int dx, int dy, int dz) {
        int baseX = (chunk.chunkX << 4) + dx;
        int baseZ = (chunk.chunkZ << 4) + dz;
        List<EditQueue.Write> writes = new ArrayList<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = chunk.minY; y <= chunk.maxY; y++) {
                    String state = chunk.stateAt(lx, y, lz);
                    if (state == null || isAirName(state)) {
                        continue;
                    }
                    BlockData data = resolve(state);
                    if (data == null) {
                        continue;
                    }
                    writes.add(new EditQueue.Write(baseX + lx, y + dy, baseZ + lz, data));
                }
            }
        }
        return new EditQueue.WriteSource() {
            @Override
            public long estimate() {
                return writes.size();
            }

            @Override
            public java.util.Iterator<EditQueue.Write> iterator() {
                return writes.iterator();
            }
        };
    }

    /** Sanity ceiling: refuse a Y offset that would push the build outside the world. */
    public String checkOffset(Player player, Scan scan, int dy) {
        int lo = scan.minY() + dy;
        int hi = scan.maxY() + dy;
        int worldLo = player.getWorld().getMinHeight();
        int worldHi = player.getWorld().getMaxHeight() - 1;
        if (lo < worldLo || hi > worldHi) {
            return "A Y offset of " + dy + " would put the build at Y " + lo + ".." + hi
                    + ", but this world only allows " + worldLo + ".." + worldHi + ".";
        }
        return null;
    }

    public static Material fallbackMaterial() {
        return Material.STONE;
    }
}
