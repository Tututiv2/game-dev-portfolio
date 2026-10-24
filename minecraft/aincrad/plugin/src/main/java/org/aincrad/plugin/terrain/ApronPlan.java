package org.aincrad.plugin.terrain;

import org.aincrad.plugin.editor.RegionReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Works out where a belt of new natural land should go around an existing island of terrain, how high
 * it should be, and how thick. Reads the world's region files directly and writes nothing.
 *
 * <p><b>Why it reads files rather than the world.</b> The area to be measured is thousands of chunks.
 * {@code getHighestBlockYAt} and {@code isChunkGenerated} have each already killed this server with a
 * watchdog timeout (docs/ARCHITECTURE-DECISION.md 20), and both would additionally <em>generate</em>
 * chunks as a side effect of asking about them. Region files answer the same questions exactly, from an
 * async thread, and cost nothing when the answer is "there is nothing there".</p>
 *
 * <h2>The shape</h2>
 * <p>An offset outline, not a rectangle. Every column gets its distance to the nearest existing land
 * (a chamfer distance transform, which also carries <em>which</em> land column is nearest), and the new
 * coastline is drawn where that distance crosses a noise-wobbled radius. Because the radius is measured
 * from the land itself, the result follows the island's real outline instead of boxing it in - and the
 * wobble means the edge reads as a coast rather than as a build error.</p>
 *
 * <p>Columns enclosed by land are excluded by a flood fill inward from the outer boundary, so a village
 * courtyard or a lake in the middle of the imported build never gets filled in. Only land reachable from
 * open air grows.</p>
 *
 * <h2>The seam</h2>
 * <p>Height at the seam is the nearest land column's own ground height, blurred over an 11x11 window so
 * one tall block cannot pull a ridge out of the new terrain, and the noise that makes the apron hilly is
 * ramped in from zero over the first 20 blocks. The join is therefore continuous by construction rather
 * than by tuning.</p>
 *
 * <p>"Ground height" deliberately means the topmost <em>natural</em> block, found by descending past up
 * to 40 blocks of structure. Taking the literal highest block would blend the apron up to the ridge of
 * whatever house happens to sit on the island's edge.</p>
 */
public final class ApronPlan {

    /** How much bigger the island gets, by area, when no margin is given. */
    private static final double DEFAULT_GROWTH = 1.5;
    /** The wobbling coastline reaches between these fractions of the nominal margin. */
    private static final double R_MIN = 0.72;
    private static final double R_MAX = 1.28;

    /**
     * Y window the surface search decodes. This dimension is 2,032 blocks tall, so decoding whole
     * columns would mean unpacking 127 sections per chunk to look at the three that matter.
     */
    private static final int SEARCH_TOP = 470;
    private static final int SEARCH_BOTTOM = 250;
    /** How far below the first solid block to keep looking for natural ground. */
    private static final int STRUCTURE_DEPTH = 40;

    /** Every 64th land chunk is probed full-depth to find what underside the existing land has. */
    private static final int UNDERSIDE_SAMPLE_EVERY = 64;

    public static final int MAX_LAND_CHUNKS = 60_000;
    /**
     * Ceiling on the working area. The plan keeps six arrays over every column in the outer box, so this
     * is a memory limit rather than an arbitrary one: 6 million columns is about 100 MB while planning,
     * and it is released as soon as the write finishes.
     */
    public static final long MAX_COLUMNS = 6_000_000L;

    private static final short NO_GROUND = Short.MIN_VALUE;
    private static final short UNTRUSTED = -1;

    private static final int LAND = 1, APRON = 2, WATER = 4, TRUSTED = 8, CLEAR = 16;

    /** Y band the clear passes will touch. Wide enough for the plate and anything built on it. */
    private static final int CLEAR_TOP = 476;
    private static final int CLEAR_BOTTOM = 150;

    /** Chamfer weights: 3 orthogonal, 4 diagonal, so distances come out in thirds of a block. */
    private static final int ORTHO = 3, DIAG = 4;
    private static final int FAR = 1 << 26;

    /** Base names that count as natural ground - the surface the apron continues outward. */
    private static final Set<String> GROUND = Set.of(
            "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:podzol", "minecraft:mycelium", "minecraft:dirt_path", "minecraft:farmland",
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:clay",
            "minecraft:mud", "minecraft:stone", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:deepslate",
            "minecraft:snow_block", "minecraft:terracotta", "minecraft:packed_mud");

    /**
     * Ground that tells us the height but must not be copied outward. A dirt path continuing into
     * empty countryside is a road to nowhere, and a field of farmland with no village is worse.
     */
    private static final Map<String, String> SUBSTITUTE = Map.of(
            "minecraft:dirt_path", "minecraft:grass_block",
            "minecraft:farmland", "minecraft:grass_block",
            "minecraft:cobblestone", "minecraft:stone",
            "minecraft:mossy_cobblestone", "minecraft:stone",
            "minecraft:terracotta", "minecraft:coarse_dirt",
            "minecraft:packed_mud", "minecraft:coarse_dirt",
            "minecraft:clay", "minecraft:gravel");

    /** Something the owner needs to read and act on, rather than a stack trace. */
    public static final class Fault extends Exception {
        public Fault(String message) {
            super(message);
        }
    }

    /** Everything the scan and the plan found, for reporting before anything is written. */
    public record Report(int landChunks, int scannedChunks, long landColumns,
                         int landMinX, int landMaxX, int landMinZ, int landMaxZ,
                         int medianGroundY, int undersideY, int waterY,
                         Map<String, Long> surfaceMix, boolean capped,
                         int margin, boolean marginChosen, int thickness,
                         int outMinX, int outMaxX, int outMinZ, int outMaxZ,
                         long apronColumns, long terrainBlocks, long waterBlocks,
                         long clearColumns, long clearBlocks, int existingChunks,
                         int trees, long treeBlocks, int chunkCount, int keptChunks, int chunksOnDisk,
                         double areaGrowth, long millis) {

        /** Blocks the in-server pass writes. Chunks removed by the trim tool are not counted here. */
        public long totalBlocks() {
            return terrainBlocks + waterBlocks + treeBlocks + clearBlocks;
        }

        /** Chunks the trim tool would delete from the region files. */
        public int trimmedChunks() {
            return Math.max(0, chunksOnDisk - keptChunks);
        }
    }

    // ------------------------------------------------------------------ state

    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int width;
    private final int depth;

    private final byte[] flags;
    private final short[] height;
    private final short[] mat;
    private final byte[] keep;
    private final short[] existTop;
    private final short[] existBottom;
    private final String[] palette;
    private final int waterY;
    private final long[] chunkList;
    private final long[] keptList;
    private final Map<Long, List<int[]>> treesByChunk;
    private final Report report;

    private ApronPlan(int minX, int minZ, int maxX, int maxZ, byte[] flags, short[] height,
                      short[] mat, byte[] keep, short[] existTop, short[] existBottom,
                      String[] palette, int waterY, long[] chunkList, long[] keptList,
                      Map<Long, List<int[]>> treesByChunk, Report report) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.width = maxX - minX + 1;
        this.depth = maxZ - minZ + 1;
        this.flags = flags;
        this.height = height;
        this.mat = mat;
        this.keep = keep;
        this.existTop = existTop;
        this.existBottom = existBottom;
        this.palette = palette;
        this.waterY = waterY;
        this.chunkList = chunkList;
        this.keptList = keptList;
        this.treesByChunk = treesByChunk;
        this.report = report;
    }

    public Report report() {
        return report;
    }

    public int waterY() {
        return waterY;
    }

    /** Block-state names the apron's surface draws from, indexed by {@link #materialAt}. */
    public String[] palette() {
        return palette.clone();
    }

    /** Chunks that contain at least one apron column, in the order they should be written. */
    public long[] chunks() {
        return chunkList;
    }

    public static int chunkX(long packed) {
        return (int) (packed >>> 32) - (1 << 30);
    }

    public static int chunkZ(long packed) {
        return (int) (packed & 0xFFFFFFFFL) - (1 << 30);
    }

    private static long packChunk(int cx, int cz) {
        return ((long) (cx + (1 << 30)) << 32) | (cz + (1 << 30) & 0xFFFFFFFFL);
    }

    /** Chunks that keep at least one column of island, so the trim tool must not delete them. */
    public long[] keptChunks() {
        return keptList;
    }

    /** -1 when this column is outside the planned box. */
    public int indexOf(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return -1;
        }
        return (x - minX) + width * (z - minZ);
    }

    /** New land: this column gets a surface, soil and rock written into it. */
    public boolean isBelt(int index) {
        return (flags[index] & APRON) != 0;
    }

    /** Beyond the coastline but in a chunk that survives, so everything in it comes out. */
    public boolean isClear(int index) {
        return (flags[index] & CLEAR) != 0;
    }

    /** {@link Short#MIN_VALUE} when nothing stands in this column. */
    public int existingTopAt(int index) {
        return existTop[index];
    }

    public int existingBottomAt(int index) {
        return existBottom[index];
    }

    public static boolean nothingThere(int existing) {
        return existing == NO_GROUND;
    }

    public int surfaceYAt(int index) {
        return height[index];
    }

    public int materialAt(int index) {
        return mat[index];
    }

    public int keepAt(int index) {
        return keep[index] & 0xFF;
    }

    public boolean waterAt(int index) {
        return (flags[index] & WATER) != 0;
    }

    /** Trees whose trunk sits in this chunk, as {@code {x, z, trunkHeight, baseY}}. */
    public List<int[]> treesIn(int cx, int cz) {
        List<int[]> found = treesByChunk.get(packChunk(cx, cz));
        return found == null ? List.of() : found;
    }

    // ================================================================== building

    /** The box the source island is taken from, or {@link #unbounded()} to follow the land wherever it goes. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {

        public static Bounds unbounded() {
            return new Bounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        public boolean isUnbounded() {
            return minX == Integer.MIN_VALUE;
        }

        boolean holds(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** Whether any column of this chunk is inside. */
        boolean touchesChunk(int cx, int cz) {
            int fromX = cx << 4;
            int fromZ = cz << 4;
            return fromX + 15 >= minX && fromX <= maxX && fromZ + 15 >= minZ && fromZ <= maxZ;
        }
    }

    /**
     * Measures the island inside {@code bounds} and plans an apron around it.
     *
     * <p>Runs entirely on the calling thread, which must not be the main thread: it reads files and
     * spends seconds on array work.</p>
     *
     * <p><b>Why bounds are usually needed.</b> The obvious approach - walk outward from the player over
     * whatever land is connected - does not work on this world, and finding that out cost a real
     * experiment: the imported build turns out to sit inside a much larger slab of leftover terrain from
     * the interrupted pregeneration run, so an unbounded walk returns 50,489 chunks spanning 6,944 x
     * 7,664 blocks instead of the build. Which part of a continuous landmass is "the island" is a
     * decision, not a measurement, so the caller supplies it - the same way {@code //island} takes the
     * selection rather than guessing a radius.</p>
     *
     * @param marginArg    explicit belt width in blocks, or null to derive one from {@link #DEFAULT_GROWTH}
     * @param thicknessArg explicit rock depth, or null to match the existing land's underside
     */
    public static ApronPlan build(File regionDir, long worldSeed, int startCx, int startCz,
                                  Bounds bounds, Integer marginArg, Integer thicknessArg,
                                  Consumer<String> progress) throws Fault {
        long began = System.currentTimeMillis();
        Survey survey = survey(regionDir, startCx, startCz, bounds, progress);
        progress.accept("Measured " + survey.chunks.size() + " land chunks. Planning the coastline.");
        return plan(regionDir, survey, worldSeed, marginArg, thicknessArg, began, progress);
    }

    // ------------------------------------------------------------------ stage 1: read the land

    /** One land chunk's 256 columns: ground height, and the palette id of its surface. */
    private record ChunkColumns(short[] groundY, short[] material) {
    }

    private static final class Survey {
        final Map<Long, ChunkColumns> chunks = new HashMap<>();
        final List<String> palette = new ArrayList<>();
        final Map<String, Short> paletteIndex = new HashMap<>();
        final Map<String, Long> surfaceMix = new HashMap<>();
        final Map<Integer, Integer> waterLevels = new HashMap<>();
        final List<Integer> undersides = new ArrayList<>();
        /**
         * Ground heights as a histogram over the search window rather than a list. A list would be one
         * boxed Integer per column - tens of millions of objects for a large island, purely to compute
         * one median.
         */
        final int[] groundHistogram = new int[SEARCH_TOP - SEARCH_BOTTOM + 1];
        Bounds bounds = Bounds.unbounded();
        int scanned;
        long landColumns;
        boolean capped;
        /** Tight to the columns that actually hold land, not to their chunks. */
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    }

    private static Survey survey(File regionDir, int startCx, int startCz, Bounds bounds,
                                 Consumer<String> progress) throws Fault {
        if (regionDir == null || !regionDir.isDirectory()) {
            throw new Fault("This world has no region folder on disk yet, so there is nothing to measure.");
        }
        Survey s = new Survey();
        s.bounds = bounds;
        long start = findLand(regionDir, startCx, startCz, bounds);

        ArrayDeque<Long> frontier = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        frontier.add(start);
        seen.add(start);
        int sinceReport = 0;

        while (!frontier.isEmpty()) {
            if (s.chunks.size() >= MAX_LAND_CHUNKS) {
                s.capped = true;
                break;
            }
            long key = frontier.poll();
            int cx = chunkX(key);
            int cz = chunkZ(key);
            s.scanned++;
            ChunkColumns columns = readChunk(regionDir, cx, cz, s);
            if (columns == null) {
                continue;                       // void, or nothing recognisable: the island stops here
            }
            s.chunks.put(key, columns);
            if (s.chunks.size() % UNDERSIDE_SAMPLE_EVERY == 0) {
                int underside = probeUnderside(regionDir, cx, cz);
                if (underside != Integer.MIN_VALUE) {
                    s.undersides.add(underside);
                }
            }
            for (long neighbour : new long[]{packChunk(cx + 1, cz), packChunk(cx - 1, cz),
                    packChunk(cx, cz + 1), packChunk(cx, cz - 1)}) {
                if (bounds.touchesChunk(chunkX(neighbour), chunkZ(neighbour)) && seen.add(neighbour)) {
                    frontier.add(neighbour);
                }
            }
            if (++sinceReport >= 1500) {
                sinceReport = 0;
                progress.accept("Measuring the existing land: " + s.chunks.size()
                        + " chunks so far (" + s.scanned + " looked at)");
            }
        }
        if (s.chunks.isEmpty()) {
            throw new Fault("Nothing here has natural ground in it, so there is no island to grow.");
        }
        return s;
    }

    /**
     * Finds a land chunk to start from, spiralling out from where the player is standing. Flying over
     * a gap in the terrain should not be a failure - it is the normal way to look at a build.
     */
    private static long findLand(File regionDir, int cx, int cz, Bounds bounds) throws Fault {
        // A throwaway survey, so probing does not contribute the start chunk's columns to the real
        // tally twice - once here and once when the walk reaches it.
        Survey scratch = new Survey();
        scratch.bounds = bounds;
        for (int ring = 0; ring <= 4; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    if (bounds.touchesChunk(cx + dx, cz + dz)
                            && readChunk(regionDir, cx + dx, cz + dz, scratch) != null) {
                        return packChunk(cx + dx, cz + dz);
                    }
                }
            }
        }
        throw new Fault(bounds.isUnbounded()
                ? "There is no land within 4 chunks of you. Stand on the island you want to grow."
                : "There is no land within 4 chunks of you inside your selection. Stand on the build "
                        + "you selected.");
    }

    /** Ground height and surface material for one chunk's columns, or null when it holds no land. */
    private static ChunkColumns readChunk(File regionDir, int cx, int cz, Survey s) {
        File file = new File(regionDir, "r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!file.isFile()) {
            return null;
        }
        RegionReader.DecodedChunk chunk;
        try {
            Map<String, Object> nbt = RegionReader.readChunk(file, cx, cz);
            if (nbt == null) {
                return null;
            }
            chunk = RegionReader.decode(nbt, cx, cz, SEARCH_BOTTOM >> 4, SEARCH_TOP >> 4);
        } catch (IOException | RuntimeException ex) {
            return null;                        // unreadable chunk: treat as void, never as a failure
        }
        if (chunk == null) {
            return null;
        }
        int top = Math.min(chunk.maxY, SEARCH_TOP);
        int bottom = Math.max(chunk.minY, SEARCH_BOTTOM);
        short[] groundY = new short[256];
        short[] material = new short[256];
        int found = 0;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int slot = lz * 16 + lx;
                groundY[slot] = NO_GROUND;
                if (!s.bounds.holds((cx << 4) + lx, (cz << 4) + lz)) {
                    continue;               // outside the selection: not part of the source island
                }
                int firstSolid = Integer.MIN_VALUE;
                int waterTop = Integer.MIN_VALUE;
                for (int y = top; y >= bottom; y--) {
                    String state = chunk.stateAt(lx, y, lz);
                    if (state == null) {
                        continue;
                    }
                    String base = baseName(state);
                    if (isAir(base)) {
                        continue;
                    }
                    if (base.equals("minecraft:water") || base.equals("minecraft:ice")) {
                        if (waterTop == Integer.MIN_VALUE) {
                            waterTop = y;
                        }
                        continue;
                    }
                    if (firstSolid == Integer.MIN_VALUE) {
                        firstSolid = y;
                    }
                    if (GROUND.contains(base)) {
                        groundY[slot] = (short) y;
                        material[slot] = intern(s, SUBSTITUTE.getOrDefault(base, base));
                        s.surfaceMix.merge(base, 1L, Long::sum);
                        s.groundHistogram[y - SEARCH_BOTTOM]++;
                        found++;
                        break;
                    }
                    if (firstSolid - y > STRUCTURE_DEPTH) {
                        break;                  // 40 blocks of build and still no soil: give up on it
                    }
                }
                if (groundY[slot] == NO_GROUND && firstSolid != Integer.MIN_VALUE) {
                    // Solid but unrecognised - a building, or a block the ground set does not name. It
                    // still counts as land, so the apron will not grow underneath it, but its height is
                    // not trusted for the seam.
                    groundY[slot] = (short) firstSolid;
                    material[slot] = UNTRUSTED;
                    found++;
                }
                if (groundY[slot] != NO_GROUND) {
                    int wx = (cx << 4) + lx;
                    int wz = (cz << 4) + lz;
                    s.minX = Math.min(s.minX, wx);
                    s.maxX = Math.max(s.maxX, wx);
                    s.minZ = Math.min(s.minZ, wz);
                    s.maxZ = Math.max(s.maxZ, wz);
                }
                if (waterTop != Integer.MIN_VALUE) {
                    s.waterLevels.merge(waterTop, 1, Integer::sum);
                }
            }
        }
        if (found == 0) {
            return null;
        }
        s.landColumns += found;
        return new ChunkColumns(groundY, material);
    }

    /** Lowest solid block in this chunk's centre column, to see what underside the island has. */
    private static int probeUnderside(File regionDir, int cx, int cz) {
        File file = new File(regionDir, "r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!file.isFile()) {
            return Integer.MIN_VALUE;
        }
        try {
            Map<String, Object> nbt = RegionReader.readChunk(file, cx, cz);
            if (nbt == null) {
                return Integer.MIN_VALUE;
            }
            RegionReader.DecodedChunk chunk = RegionReader.decode(nbt, cx, cz, -4, SEARCH_BOTTOM >> 4);
            if (chunk == null) {
                return Integer.MIN_VALUE;
            }
            for (int y = Math.max(chunk.minY, -64); y <= Math.min(chunk.maxY, SEARCH_BOTTOM); y++) {
                String state = chunk.stateAt(8, y, 8);
                if (state != null && !isAir(baseName(state))) {
                    return y;
                }
            }
        } catch (IOException | RuntimeException ex) {
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ stage 2: plan the belt

    /**
     * Vanilla small-oak canopy, as offsets from the top trunk block. Held here rather than in the
     * generator because the plan counts these blocks for the progress estimate and the generator writes
     * them; two hand-written copies of the same shape would eventually disagree.
     */
    public static final int[][] CANOPY = buildCanopy();

    private static int[][] buildCanopy() {
        List<int[]> out = new ArrayList<>();
        for (int dy = -2; dy <= -1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                        continue;                       // clipped corners
                    }
                    if (dx == 0 && dz == 0) {
                        continue;                       // the trunk
                    }
                    out.add(new int[]{dx, dy, dz});
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    out.add(new int[]{dx, 0, dz});
                }
            }
        }
        out.add(new int[]{0, 1, 0});
        out.add(new int[]{1, 1, 0});
        out.add(new int[]{-1, 1, 0});
        out.add(new int[]{0, 1, 1});
        out.add(new int[]{0, 1, -1});
        return out.toArray(new int[0][]);
    }

    /** How far from the coastline trees, water and full-thickness rock stop. */
    private static final int RIM = 26;
    /** One tree per this many eligible columns. */
    private static final int TREE_SPACING = 170;

    /**
     * Whether a grass column carries a plant. Deterministic and shared, because the plan counts these
     * for the progress estimate and the generator places them - a second copy of the rule would make
     * the two disagree about how much work there is.
     */
    public static boolean hasPlant(int x, int z) {
        return hash(x, z, 13) % 100 < 16;
    }

    /** One plant in eight is a flower rather than grass. */
    public static boolean isFlower(int x, int z) {
        return hash(x, z, 17) % 8 == 0;
    }

    private static ApronPlan plan(File regionDir, Survey s, long worldSeed,
                                  Integer marginArg, Integer thicknessArg,
                                  long began, Consumer<String> progress) throws Fault {
        int landW = s.maxX - s.minX + 1;
        int landD = s.maxZ - s.minZ + 1;
        boolean marginChosen = marginArg == null;
        int margin = marginChosen ? derivedMargin(landW, landD) : marginArg;
        margin = Math.max(16, Math.min(2000, margin));

        int pad = (int) Math.ceil(margin * R_MAX) + 4;
        int minX = s.minX - pad;
        int maxX = s.maxX + pad;
        int minZ = s.minZ - pad;
        int maxZ = s.maxZ + pad;
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        long columns = (long) width * depth;
        if (columns > MAX_COLUMNS) {
            throw new Fault("A " + margin + "-block belt around a " + landW + "x" + landD
                    + " island means working over " + String.format("%,d", columns)
                    + " columns, past the " + String.format("%,d", MAX_COLUMNS)
                    + " this can hold in memory. Use a smaller margin, or do it in halves.");
        }
        int n = (int) columns;

        int landChunks = s.chunks.size();
        int medianGround = histogramMedian(s.groundHistogram, SEARCH_BOTTOM, 319);
        int underside = s.undersides.isEmpty() ? Integer.MIN_VALUE : median(s.undersides, 0);
        // Water level from the build's own lakes where that is credible, and it often is not. On the
        // imported world the commonest water surface comes out at Y=319, which is the ground level -
        // wells, irrigated farmland and ground-level streams all vote for it. Taken literally that makes
        // every column the noise dips below the median into a lake, flooding most of the new belt. A
        // water level at or above the surrounding ground is therefore treated as a failed measurement.
        int measuredWater = commonest(s.waterLevels, Integer.MIN_VALUE);
        boolean waterCredible = measuredWater > SEARCH_BOTTOM && measuredWater < medianGround - 1;
        int waterY = waterCredible ? measuredWater
                : Math.min(FloorOneTerrain.WATER_Y, medianGround - 3);
        int thickness = thicknessArg != null ? thicknessArg
                : underside == Integer.MIN_VALUE ? 48
                : Math.max(24, Math.min(72, medianGround - underside));
        thickness = Math.max(6, Math.min(110, thickness));

        byte[] flags = new byte[n];
        short[] height = new short[n];
        short[] mat = new short[n];
        byte[] keep = new byte[n];

        short dominant = dominantMaterial(s);
        intern(s, "minecraft:grass_block");
        short gravelId = intern(s, "minecraft:gravel");
        short sandId = intern(s, "minecraft:sand");
        // Resolved once per palette entry rather than per column: this is tested 1.2 million times.
        boolean[] plantableMat = new boolean[s.palette.size()];
        for (int id = 0; id < plantableMat.length; id++) {
            String name = s.palette.get(id);
            plantableMat[id] = name.equals("minecraft:grass_block") || name.equals("minecraft:podzol");
        }

        for (Map.Entry<Long, ChunkColumns> entry : s.chunks.entrySet()) {
            int baseX = (chunkX(entry.getKey()) << 4) - minX;
            int baseZ = (chunkZ(entry.getKey()) << 4) - minZ;
            short[] groundY = entry.getValue().groundY();
            short[] material = entry.getValue().material();
            for (int lz = 0; lz < 16; lz++) {
                int row = (baseZ + lz) * width + baseX;
                for (int lx = 0; lx < 16; lx++) {
                    int slot = lz * 16 + lx;
                    if (groundY[slot] == NO_GROUND) {
                        continue;
                    }
                    int i = row + lx;
                    height[i] = groundY[slot];
                    if (material[slot] == UNTRUSTED) {
                        flags[i] = LAND;
                        mat[i] = dominant;
                    } else {
                        flags[i] = (byte) (LAND | TRUSTED);
                        mat[i] = material[slot];
                    }
                }
            }
        }
        s.chunks.clear();                       // 9 MB of per-chunk arrays no longer needed
        progress.accept("Smoothing the seam.");

        short[] seam = blurLandHeights(flags, height, width, depth, medianGround);
        progress.accept("Tracing the new coastline.");

        int[] near = new int[n];
        short[] dist = chamfer(flags, near, width, depth);
        boolean[] outside = reachableFromOutside(flags, width, depth);

        // What is already standing in the belt. On this world that is not void but a slab of flat
        // leftover terrain from the interrupted pregeneration, so the apron has to replace it rather
        // than fill around it - and replacing it means knowing exactly how far up and down it goes.
        progress.accept("Reading what is already standing out there.");
        short[] existTop = new short[n];
        short[] existBottom = new short[n];
        int existingChunks = readExisting(regionDir, minX, minZ, width, depth, existTop, existBottom,
                progress);

        GradientNoise2D coast = new GradientNoise2D(worldSeed + 909);
        GradientNoise2D macro = new GradientNoise2D(worldSeed + 4141);
        GradientNoise2D micro = new GradientNoise2D(worldSeed + 5252);

        long apronColumns = 0;
        long terrainBlocks = 0;
        long waterBlocks = 0;
        long clearBlocks = 0;
        long clearColumns = 0;
        int chunkStrideX = (width >> 4) + 2;
        boolean[] chunkKept = new boolean[chunkStrideX * ((depth >> 4) + 2)];
        boolean[] chunkWork = new boolean[chunkKept.length];
        int chunkOriginX = minX >> 4;
        int chunkOriginZ = minZ >> 4;
        List<int[]> trees = new ArrayList<>();
        long treeBlocks = 0;

        // Pass one: the belt. Which chunks survive the trim is decided by this, so it has to finish
        // before anything can be marked for clearing.
        for (int z = 0; z < depth; z++) {
            int worldZ = minZ + z;
            int row = z * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if ((flags[i] & LAND) != 0) {
                    chunkKept[cell(worldZ, minX + x, chunkStrideX, chunkOriginX, chunkOriginZ)] = true;
                    continue;
                }
                if (!outside[i]) {
                    continue;                   // a courtyard the outside cannot reach: leave it alone
                }
                int worldX = minX + x;
                double d = dist[i] / (double) ORTHO;
                double radius = margin * (R_MIN + (R_MAX - R_MIN)
                        * unit(coast.noise(worldX / 210.0, worldZ / 210.0)));
                if (d > radius) {
                    continue;                   // past the coast: pass two decides whether to clear it
                }
                int source = near[i];
                int seamY = source >= 0 ? seam[source] : medianGround;
                short baseMat = source >= 0 ? mat[source] : dominant;

                double ramp = Math.min(1.0, d / 20.0);
                double y = seamY
                        + macro.noise(worldX / 170.0, worldZ / 170.0) * 8.0 * ramp
                        + micro.noise(worldX / 38.0, worldZ / 38.0) * 2.5 * ramp;

                // The outer band lifts into a flat lip, the way the floor's own rim does in
                // FloorOneTerrain: an edge that reads as the built structure of a sky island rather
                // than as terrain that happens to stop.
                double outward = radius - d;
                if (outward < RIM) {
                    double t = smoothstep(1.0 - outward / RIM);
                    y = y * (1 - t) + (seamY + 2) * t;
                }
                int surfaceY = (int) Math.round(Math.max(292, Math.min(404, y)));

                boolean submerged = surfaceY < waterY && outward >= RIM;
                short columnMat = baseMat;
                if (submerged) {
                    columnMat = (hash(worldX, worldZ, 7) & 3) == 0 ? sandId : gravelId;
                }

                double taper = Math.min(1.0, outward / 44.0);
                int wobble = (int) (hash(worldX, worldZ, 11) % 17) - 8;
                int rock = Math.max(5, (int) (thickness * (0.16 + 0.84 * taper)) + wobble);
                rock = Math.min(rock, 255);

                flags[i] = (byte) (APRON | (submerged ? WATER : 0));
                height[i] = (short) surfaceY;
                mat[i] = columnMat;
                keep[i] = (byte) rock;
                apronColumns++;
                terrainBlocks += rock + 1;
                if (submerged) {
                    waterBlocks += waterY - surfaceY;
                }
                // Whatever used to stand here above the new surface or below the new underside has to
                // go, or the belt would be new terrain buried inside the old flat slab.
                if (existTop[i] != NO_GROUND) {
                    clearBlocks += Math.max(0, existTop[i] - surfaceY);
                    clearBlocks += Math.max(0, (surfaceY - rock - 1) - existBottom[i] + 1);
                }
                int cell = cell(worldZ, worldX, chunkStrideX, chunkOriginX, chunkOriginZ);
                chunkKept[cell] = true;
                chunkWork[cell] = true;

                boolean grassy = !submerged && plantableMat[columnMat];
                if (grassy && hasPlant(worldX, worldZ)) {
                    terrainBlocks++;
                }
                boolean plantable = grassy && surfaceY > waterY && outward > RIM;
                if (plantable && hash(worldX, worldZ, 3) % TREE_SPACING == 0) {
                    int trunk = 4 + (int) (hash(worldX, worldZ, 5) % 3);
                    trees.add(new int[]{worldX, worldZ, trunk, surfaceY + 1});
                    treeBlocks += trunk + CANOPY.length;
                }
            }
            if ((z & 255) == 0 && z > 0) {
                progress.accept("Tracing the new coastline: " + (z * 100 / depth) + "%");
            }
        }
        if (apronColumns == 0) {
            throw new Fault("That margin produced no new land at all. Try a larger number.");
        }

        // Pass two: land beyond the new coastline that sits in a chunk we are keeping. Whole chunks
        // outside the island are the trim tool's job - it removes them from the region files, which is
        // thousands of times cheaper than writing air over them. But a chunk straddling the coastline
        // cannot be deleted wholesale, so the part of it past the edge is cleared here, block by block.
        // Without this the island would end in a set of 16-block square steps.
        for (int z = 0; z < depth; z++) {
            int worldZ = minZ + z;
            int row = z * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if (flags[i] != 0 || existTop[i] == NO_GROUND) {
                    continue;                   // land, belt, or nothing there to remove
                }
                int worldX = minX + x;
                if (!chunkKept[cell(worldZ, worldX, chunkStrideX, chunkOriginX, chunkOriginZ)]) {
                    continue;                   // the whole chunk goes, so do not write air into it
                }
                flags[i] = CLEAR;
                clearColumns++;
                clearBlocks += existTop[i] - existBottom[i] + 1;
                chunkWork[cell(worldZ, worldX, chunkStrideX, chunkOriginX, chunkOriginZ)] = true;
            }
        }

        long[] chunkList = orderedChunks(chunkWork, chunkStrideX, chunkOriginX, chunkOriginZ);
        long[] keptList = orderedChunks(chunkKept, chunkStrideX, chunkOriginX, chunkOriginZ);
        Map<Long, List<int[]>> treesByChunk = new HashMap<>();
        for (int[] tree : trees) {
            treesByChunk.computeIfAbsent(packChunk(tree[0] >> 4, tree[1] >> 4),
                    key -> new ArrayList<>()).add(tree);
        }
        int onDisk = countChunksOnDisk(regionDir);

        double landArea = (double) landW * landD;
        double newArea = landArea + apronColumns;
        Report report = new Report(landChunks, s.scanned, s.landColumns,
                s.minX, s.maxX, s.minZ, s.maxZ,
                medianGround, underside, waterY,
                topSurfaces(s.surfaceMix), s.capped,
                margin, marginChosen, thickness,
                minX, maxX, minZ, maxZ,
                apronColumns, terrainBlocks, waterBlocks,
                clearColumns, clearBlocks, existingChunks,
                trees.size(), treeBlocks, chunkList.length, keptList.length, onDisk,
                newArea / landArea, System.currentTimeMillis() - began);

        return new ApronPlan(minX, minZ, maxX, maxZ, flags, height, mat, keep, existTop, existBottom,
                s.palette.toArray(new String[0]), waterY, chunkList, keptList, treesByChunk, report);
    }

    private static int cell(int worldZ, int worldX, int strideX, int originX, int originZ) {
        return ((worldZ >> 4) - originZ) * strideX + ((worldX >> 4) - originX);
    }

    /**
     * Highest and lowest existing block in every column of the outer box.
     *
     * <p>Read straight out of the region files, one chunk at a time, and only over the band terrain can
     * occupy. Asking the server instead would mean {@code getHighestBlockYAt} on tens of thousands of
     * chunks, which is the exact call that has twice frozen this server long enough for the watchdog to
     * kill it.</p>
     *
     * @return how many chunks in the box actually exist on disk
     */
    private static int readExisting(File regionDir, int minX, int minZ, int width, int depth,
                                    short[] top, short[] bottom, Consumer<String> progress) {
        java.util.Arrays.fill(top, NO_GROUND);
        java.util.Arrays.fill(bottom, NO_GROUND);
        int minCx = minX >> 4;
        int maxCx = (minX + width - 1) >> 4;
        int minCz = minZ >> 4;
        int maxCz = (minZ + depth - 1) >> 4;
        int found = 0;
        int done = 0;
        int total = (maxCx - minCx + 1) * (maxCz - minCz + 1);

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (++done % 4000 == 0) {
                    progress.accept("Reading existing terrain: " + (done * 100 / total) + "%");
                }
                File file = new File(regionDir, "r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
                if (!file.isFile()) {
                    continue;
                }
                RegionReader.DecodedChunk chunk;
                try {
                    Map<String, Object> nbt = RegionReader.readChunk(file, cx, cz);
                    if (nbt == null) {
                        continue;
                    }
                    chunk = RegionReader.decode(nbt, cx, cz, CLEAR_BOTTOM >> 4, CLEAR_TOP >> 4);
                } catch (IOException | RuntimeException ex) {
                    continue;
                }
                if (chunk == null) {
                    continue;
                }
                found++;
                int hi = Math.min(chunk.maxY, CLEAR_TOP);
                int lo = Math.max(chunk.minY, CLEAR_BOTTOM);
                for (int lz = 0; lz < 16; lz++) {
                    int wz = (cz << 4) + lz;
                    if (wz < minZ || wz >= minZ + depth) {
                        continue;
                    }
                    int row = (wz - minZ) * width;
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = (cx << 4) + lx;
                        if (wx < minX || wx >= minX + width) {
                            continue;
                        }
                        int i = row + (wx - minX);
                        int highest = Integer.MIN_VALUE;
                        int lowest = Integer.MIN_VALUE;
                        for (int y = hi; y >= lo; y--) {
                            String state = chunk.stateAt(lx, y, lz);
                            if (state == null || isAir(baseName(state))) {
                                continue;
                            }
                            if (highest == Integer.MIN_VALUE) {
                                highest = y;
                            }
                            lowest = y;
                        }
                        if (highest != Integer.MIN_VALUE) {
                            top[i] = (short) highest;
                            bottom[i] = (short) lowest;
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Every chunk present in the world's region files, so the report can say how many the trim removes. */
    private static int countChunksOnDisk(File regionDir) {
        File[] files = regionDir.listFiles((d, name) -> name.startsWith("r.") && name.endsWith(".mca"));
        if (files == null) {
            return 0;
        }
        int total = 0;
        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            try {
                total += RegionReader.listChunks(file, Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])).size();
            } catch (IOException | RuntimeException ex) {
                // An unreadable region file simply does not contribute to the count.
            }
        }
        return total;
    }

    /** Belt width that makes the island {@link #DEFAULT_GROWTH} times its current area. */
    private static int derivedMargin(int w, int d) {
        double sum = (double) w + d;
        double root = Math.sqrt(sum * sum + 4.0 * (DEFAULT_GROWTH - 1.0) * (double) w * d);
        return (int) Math.round((root - sum) / 4.0);
    }

    /**
     * Ground heights averaged over an 11x11 window, so the seam follows the landscape rather than
     * individual blocks. Separable, and it only ever averages columns whose height is trusted - a
     * building's roof must not be allowed to pull the new terrain up with it.
     */
    private static short[] blurLandHeights(byte[] flags, short[] height, int width, int depth,
                                           int fallback) {
        final int r = 5;
        int n = width * depth;
        short[] rowSum = new short[n];
        byte[] rowCount = new byte[n];
        for (int z = 0; z < depth; z++) {
            int row = z * width;
            for (int x = 0; x < width; x++) {
                int sum = 0;
                int count = 0;
                int from = Math.max(0, x - r);
                int to = Math.min(width - 1, x + r);
                for (int sx = from; sx <= to; sx++) {
                    int j = row + sx;
                    if ((flags[j] & TRUSTED) != 0) {
                        sum += height[j];
                        count++;
                    }
                }
                rowSum[row + x] = (short) sum;
                rowCount[row + x] = (byte) count;
            }
        }
        short[] out = new short[n];
        for (int z = 0; z < depth; z++) {
            int row = z * width;
            int from = Math.max(0, z - r);
            int to = Math.min(depth - 1, z + r);
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if ((flags[i] & LAND) == 0) {
                    continue;
                }
                int sum = 0;
                int count = 0;
                for (int sz = from; sz <= to; sz++) {
                    int j = sz * width + x;
                    sum += rowSum[j];
                    count += rowCount[j] & 0xFF;
                }
                out[i] = (short) (count == 0 ? fallback : Math.round(sum / (float) count));
            }
        }
        return out;
    }

    /**
     * Distance from every void column to the nearest land column, and which column that is.
     *
     * <p>A two-pass chamfer transform rather than a queue-based flood: it is a fixed two sweeps over the
     * array regardless of shape, and the 3-4 weighting approximates true distance closely enough that
     * the coastline has no diagonal facets. A 4-connected Manhattan flood would have produced a
     * visibly diamond-cornered island.</p>
     *
     * @return distances in thirds of a block
     */
    private static short[] chamfer(byte[] flags, int[] near, int width, int depth) {
        int n = width * depth;
        short[] dist = new short[n];
        final short far = 30_000;
        for (int i = 0; i < n; i++) {
            if ((flags[i] & LAND) != 0) {
                dist[i] = 0;
                near[i] = i;
            } else {
                dist[i] = far;
                near[i] = -1;
            }
        }
        for (int z = 0; z < depth; z++) {
            int row = z * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if (dist[i] == 0) {
                    continue;
                }
                if (x > 0) {
                    take(dist, near, i, i - 1, ORTHO);
                }
                if (z > 0) {
                    take(dist, near, i, i - width, ORTHO);
                    if (x > 0) {
                        take(dist, near, i, i - width - 1, DIAG);
                    }
                    if (x < width - 1) {
                        take(dist, near, i, i - width + 1, DIAG);
                    }
                }
            }
        }
        for (int z = depth - 1; z >= 0; z--) {
            int row = z * width;
            for (int x = width - 1; x >= 0; x--) {
                int i = row + x;
                if (dist[i] == 0) {
                    continue;
                }
                if (x < width - 1) {
                    take(dist, near, i, i + 1, ORTHO);
                }
                if (z < depth - 1) {
                    take(dist, near, i, i + width, ORTHO);
                    if (x < width - 1) {
                        take(dist, near, i, i + width + 1, DIAG);
                    }
                    if (x > 0) {
                        take(dist, near, i, i + width - 1, DIAG);
                    }
                }
            }
        }
        return dist;
    }

    /** Adopts the neighbour's nearest-land answer when going through it is shorter. */
    private static void take(short[] dist, int[] near, int i, int from, int cost) {
        int candidate = dist[from] + cost;
        if (candidate < dist[i]) {
            dist[i] = (short) candidate;
            near[i] = near[from];
        }
    }

    /**
     * Which void columns can be reached from open air without crossing land. Everything else is a
     * courtyard, a village square or a lake enclosed by the build, and must be left alone - filling
     * those in would wreck the interior of the thing we are trying to frame.
     */
    private static boolean[] reachableFromOutside(byte[] flags, int width, int depth) {
        int n = width * depth;
        boolean[] out = new boolean[n];
        int[] stack = new int[n];
        int top = 0;
        for (int x = 0; x < width; x++) {
            top = push(flags, out, stack, top, x);
            top = push(flags, out, stack, top, (depth - 1) * width + x);
        }
        for (int z = 0; z < depth; z++) {
            top = push(flags, out, stack, top, z * width);
            top = push(flags, out, stack, top, z * width + width - 1);
        }
        while (top > 0) {
            int i = stack[--top];
            int x = i % width;
            if (x > 0) {
                top = push(flags, out, stack, top, i - 1);
            }
            if (x < width - 1) {
                top = push(flags, out, stack, top, i + 1);
            }
            if (i >= width) {
                top = push(flags, out, stack, top, i - width);
            }
            if (i + width < n) {
                top = push(flags, out, stack, top, i + width);
            }
        }
        return out;
    }

    private static int push(byte[] flags, boolean[] out, int[] stack, int top, int i) {
        if (out[i] || (flags[i] & LAND) != 0) {
            return top;
        }
        out[i] = true;
        stack[top++] = i;
        return top;
    }

    private static long[] orderedChunks(boolean[] used, int strideX, int originX, int originZ) {
        int rows = used.length / strideX;
        List<Long> out = new ArrayList<>();
        // X outer, Z inner, matching Shapes' traversal so every chunk is entered exactly once.
        for (int gx = 0; gx < strideX; gx++) {
            for (int gz = 0; gz < rows; gz++) {
                if (used[gz * strideX + gx]) {
                    out.add(packChunk(originX + gx, originZ + gz));
                }
            }
        }
        long[] array = new long[out.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = out.get(i);
        }
        return array;
    }

    private static short dominantMaterial(Survey s) {
        String best = null;
        long bestCount = -1;
        for (Map.Entry<String, Long> e : s.surfaceMix.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return intern(s, best == null ? "minecraft:grass_block"
                : SUBSTITUTE.getOrDefault(best, best));
    }

    private static Map<String, Long> topSurfaces(Map<String, Long> mix) {
        Map<String, Long> out = new LinkedHashMap<>();
        mix.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static int histogramMedian(int[] counts, int offset, int fallback) {
        long total = 0;
        for (int c : counts) {
            total += c;
        }
        if (total == 0) {
            return fallback;
        }
        long half = total / 2;
        long running = 0;
        for (int i = 0; i < counts.length; i++) {
            running += counts[i];
            if (running > half) {
                return offset + i;
            }
        }
        return fallback;
    }

    private static int median(List<Integer> values, int fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    private static int commonest(Map<Integer, Integer> votes, int fallback) {
        int best = fallback;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static double unit(double noise) {
        return Math.max(0.0, Math.min(1.0, (noise + 1.0) / 2.0));
    }

    private static double smoothstep(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3 - 2 * c);
    }

    /** Cheap positional hash, so scatter and wobble are irregular without a noise lookup. */
    static long hash(int x, int z, int salt) {
        long h = x * 341873128712L + z * 132897987541L + salt * 1234567891L;
        h ^= (h >>> 31);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return h & Long.MAX_VALUE;
    }

    private static short intern(Survey s, String name) {
        Short existing = s.paletteIndex.get(name);
        if (existing != null) {
            return existing;
        }
        short id = (short) s.palette.size();
        s.palette.add(name);
        s.paletteIndex.put(name, id);
        return id;
    }

    private static boolean isAir(String base) {
        return base.equals("minecraft:air") || base.equals("minecraft:cave_air")
                || base.equals("minecraft:void_air");
    }

    private static String baseName(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }
}
