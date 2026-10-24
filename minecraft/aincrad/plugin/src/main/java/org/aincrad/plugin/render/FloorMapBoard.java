package org.aincrad.plugin.render;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A large, physically-mounted wall map of the floor, assembled from a grid of item-framed maps.
 *
 * <p><b>Why not an ordinary map item.</b> Vanilla maps only show terrain the holder has personally
 * explored, so a fresh map of a 1,000-block town is a blank sheet - useless as an overview, which is
 * exactly what was wanted. Each tile here instead carries a {@link MapRenderer} that paints pixels
 * sampled straight from world blocks, so the whole board is revealed the moment it is built and is
 * identical for every player who walks up to it.</p>
 *
 * <h2>Why this class is a scheduler and not a method</h2>
 * <p>The first version was a plain {@code build()} that walked every board pixel calling
 * {@link World#getHighestBlockYAt(int, int)}. That is a trap: for a column in a chunk that is not
 * yet generated, {@code getHighestBlockYAt} <em>synchronously generates the chunk</em> on the main
 * thread. A 5x3 board at 6 blocks/pixel is 245,760 columns spread over 57,600 chunks, most of them
 * ungenerated, so the call blocked the server thread for 60 seconds until the watchdog killed the
 * process and dumped every thread. The stack in that dump was
 * {@code sampleColour -> CraftWorld.getHighestBlockYAt -> ServerChunkCache.syncLoad}.</p>
 *
 * <p>So the rules this class now obeys:</p>
 * <ul>
 *   <li>Never touch a chunk that is not already generated. Ungenerated area is painted as void;
 *       building a map must not silently generate 57,600 chunks of world.</li>
 *   <li>Never ask the <em>server</em> which chunks exist. {@link World#isChunkGenerated} looks like a
 *       cheap lookup but is a blocking round-trip into the chunk system, and calling it in bulk
 *       produced a second watchdog crash with its own stack
 *       ({@code CraftWorld.isChunkGenerated -> BlockableEventLoop.managedBlock}). Region-file headers
 *       are read directly instead, off the main thread - see {@code beginRegionScan}.</li>
 *   <li>Never load a chunk synchronously - {@code getChunkAtAsync(x, z, false)} does the I/O off
 *       the main thread, with a bounded number of requests in flight.</li>
 *   <li>Do a fixed, small amount of work per tick, so the server keeps ticking throughout and the
 *       player who ran the command stays connected.</li>
 * </ul>
 *
 * <p>Sampling is <b>chunk-driven, not pixel-driven</b>: each generated chunk is visited once and
 * its 256 columns are accumulated into whichever board pixels they fall in. That reads each chunk
 * exactly once instead of once per sample, and means the cost scales with how much world actually
 * exists rather than with board resolution.</p>
 */
public final class FloorMapBoard {

    private static final int TILE = 128;

    /** Area that has never been generated. Matches the PNG renderer's out-of-mask colour. */
    private static final Color VOID_COLOUR = new Color(14, 14, 18);

    // Per-tick budgets. See the class comment for what happens without them. The work is disk-bound,
    // not CPU-bound, so the in-flight cap is set to keep Paper's I/O threads saturated rather than to
    // limit CPU: an earlier cap of 64 left the queue draining to empty between ticks.
    private static final int MAX_IN_FLIGHT = 192;      // keep the I/O queue deep
    private static final int DRAIN_PER_TICK = 192;     // snapshots folded in per tick
    private static final int TILES_PER_TICK = 2;       // map/frame creation during finish

    /** RGB -> map palette index. {@link MapPalette#matchColor} is a linear scan of 256 colours. */
    private static final Map<Integer, Byte> PALETTE_CACHE = new HashMap<>();

    private static Session active;

    private FloorMapBoard() {
    }

    public static boolean isBuilding() {
        return active != null;
    }

    /** Cancels an in-progress build. Returns false if nothing was running. */
    public static boolean cancel() {
        if (active == null) {
            return false;
        }
        active.finishTask();
        active = null;
        return true;
    }

    /**
     * Starts building the board. Returns a human-readable plan line, or null if a build is already
     * running.
     *
     * @param centreX,centreZ         world point the whole board is centred on
     * @param blocksPerPixel          world scale; 6 means one tile covers 768 blocks
     * @param cols,rows               tile grid size
     * @param boardX,boardTopY,boardZ where the physical board is built; frames face south
     */
    public static String start(Plugin plugin, World world, CommandSender feedback,
                               int centreX, int centreZ, int blocksPerPixel,
                               int cols, int rows, int boardX, int boardTopY, int boardZ) {
        if (active != null) {
            return null;
        }
        Session session = new Session(plugin, world, feedback, centreX, centreZ, blocksPerPixel,
                cols, rows, boardX, boardTopY, boardZ);
        active = session;
        session.buildBacking();
        session.beginRegionScan();
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, session, 1L, 1L);
        return session.planLine();
    }

    /** Paints a pre-computed pixel buffer; costs almost nothing per tick. */
    private static final class CachedTileRenderer extends MapRenderer {
        private final byte[] pixels;   // TILE * TILE palette indices
        private boolean drawn;

        CachedTileRenderer(byte[] pixels) {
            super(false);
            this.pixels = pixels;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, org.bukkit.entity.Player player) {
            if (drawn) {
                return;
            }
            for (int px = 0; px < TILE; px++) {
                for (int py = 0; py < TILE; py++) {
                    canvas.setPixel(px, py, pixels[py * TILE + px]);
                }
            }
            drawn = true;
        }
    }

    /** One board build in progress. Runs a slice of work per tick. */
    private static final class Session implements Runnable {

        private final Plugin plugin;
        private final World world;
        private final CommandSender feedback;
        private final int blocksPerPixel;
        private final int cols;
        private final int rows;
        private final int boardX;
        private final int boardTopY;
        private final int boardZ;

        private final int width;        // board pixels
        private final int height;
        private final int originX;      // world coord of board pixel (0,0)
        private final int originZ;

        // Colour and height accumulators, one slot per board pixel.
        private final int[] rSum;
        private final int[] gSum;
        private final int[] bSum;
        private final int[] samples;
        private final int[] hSum;

        private final int minCx;
        private final int maxCx;
        private final int minCz;
        private final int maxCz;
        private final int totalChunks;

        private volatile boolean regionScanDone;
        private int generated;
        private int accumulated;
        private int inFlight;
        /**
         * Chunks that exist on disk but could not be read. {@code getChunkAtAsync(x, z, false)}
         * returns null for a chunk saved at a partial generation status - the pregeneration run was
         * interrupted more than once, so a band of chunks sits at proto status. Refusing to finish
         * them is correct (that would be generation, not drawing), but they must be counted and
         * reported rather than quietly appearing as void.
         */
        private int unreadable;
        /** Generated chunk coords found by the scan but not yet read. Packed cx/cz. */
        private final java.util.ArrayDeque<Long> pending = new java.util.ArrayDeque<>();
        private final ConcurrentLinkedQueue<org.bukkit.Chunk> ready = new ConcurrentLinkedQueue<>();

        private int[] pixelHeight;       // filled during finish
        private int tilesBuilt;
        private int finishCursor;
        private long lastReport;

        private BukkitTask task;

        Session(Plugin plugin, World world, CommandSender feedback, int centreX, int centreZ,
                int blocksPerPixel, int cols, int rows, int boardX, int boardTopY, int boardZ) {
            this.plugin = plugin;
            this.world = world;
            this.feedback = feedback;
            this.blocksPerPixel = blocksPerPixel;
            this.cols = cols;
            this.rows = rows;
            this.boardX = boardX;
            this.boardTopY = boardTopY;
            this.boardZ = boardZ;

            this.width = cols * TILE;
            this.height = rows * TILE;
            this.originX = centreX - width * blocksPerPixel / 2;
            this.originZ = centreZ - height * blocksPerPixel / 2;

            int pixels = width * height;
            this.rSum = new int[pixels];
            this.gSum = new int[pixels];
            this.bSum = new int[pixels];
            this.samples = new int[pixels];
            this.hSum = new int[pixels];

            this.minCx = Math.floorDiv(originX, 16);
            this.maxCx = Math.floorDiv(originX + width * blocksPerPixel - 1, 16);
            this.minCz = Math.floorDiv(originZ, 16);
            this.maxCz = Math.floorDiv(originZ + height * blocksPerPixel - 1, 16);
            this.totalChunks = (maxCx - minCx + 1) * (maxCz - minCz + 1);
        }

        String planLine() {
            return cols + "x" + rows + " tiles at " + blocksPerPixel + " blocks/pixel = "
                    + (width * blocksPerPixel) + "x" + (height * blocksPerPixel)
                    + " blocks, over a " + totalChunks + "-chunk footprint (generated area only).";
        }

        /** Backing wall and cornice. Small and bounded, so it is fine to do in one go. */
        void buildBacking() {
            for (int c = -1; c <= cols; c++) {
                for (int r = -1; r <= rows; r++) {
                    boolean edge = c < 0 || c >= cols || r < 0 || r >= rows;
                    world.getBlockAt(boardX + c, boardTopY - r, boardZ).setType(
                            edge ? Material.POLISHED_BLACKSTONE : Material.DEEPSLATE_TILES, false);
                    world.getBlockAt(boardX + c, boardTopY - r, boardZ + 1)
                            .setType(Material.AIR, false);
                }
            }
            for (int c = -1; c <= cols; c++) {
                world.getBlockAt(boardX + c, boardTopY + 1, boardZ)
                        .setType(Material.POLISHED_BLACKSTONE, false);
                world.getBlockAt(boardX + c, boardTopY + 2, boardZ)
                        .setType(Material.CHISELED_POLISHED_BLACKSTONE, false);
            }
            // Remove frames from a previous build so re-running does not stack them.
            Location centre = new Location(world, boardX + cols / 2.0, boardTopY - rows / 2.0, boardZ + 1);
            for (var e : world.getNearbyEntities(centre, cols + 4, rows + 4, 3)) {
                if (e.getType() == EntityType.ITEM_FRAME || e.getType() == EntityType.GLOW_ITEM_FRAME) {
                    e.remove();
                }
            }
        }

        @Override
        public void run() {
            try {
                if (pixelHeight != null) {
                    finishTiles();
                    return;
                }
                if (!regionScanDone) {
                    return;          // the off-thread region-header scan has not reported yet
                }
                drainReady();
                issueLoads();
                report();
                if (inFlight == 0 && pending.isEmpty() && ready.isEmpty()) {
                    beginFinish();
                }
            } catch (RuntimeException ex) {
                say("Map board failed: " + ex);
                plugin.getLogger().severe("Map board build aborted: " + ex);
                finishTask();
                active = null;
            }
        }

        /**
         * Works out which chunks exist by reading region-file headers directly, off the main thread.
         *
         * <p>The obvious API for this, {@link World#isChunkGenerated}, is unusable in bulk: it is not
         * a lookup but a blocking round-trip into the chunk system, and 3,000 calls in a tick stalled
         * the server for 45 seconds - a second watchdog crash with a different stack from the first:
         * {@code CraftWorld.isChunkGenerated -> BlockableEventLoop.managedBlock}. An {@code .mca}
         * header is 1,024 four-byte entries whose sector offset is zero when the chunk was never
         * written, so ~70 8KB reads answer the same question exactly, without touching the server at
         * all.</p>
         */
        private void beginRegionScan() {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                java.util.List<Long> found = new java.util.ArrayList<>();
                int regions = 0;
                java.io.File dir = regionFolder(world);
                for (int rx = minCx >> 5; rx <= (maxCx >> 5); rx++) {
                    for (int rz = minCz >> 5; rz <= (maxCz >> 5); rz++) {
                        java.io.File file = new java.io.File(dir, "r." + rx + "." + rz + ".mca");
                        if (!file.isFile()) {
                            continue;
                        }
                        byte[] header = new byte[4096];
                        try (java.io.DataInputStream in = new java.io.DataInputStream(
                                new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
                            in.readFully(header);
                        } catch (java.io.IOException ex) {
                            continue;      // truncated or unreadable region: treat as absent
                        }
                        regions++;
                        for (int local = 0; local < 1024; local++) {
                            int at = local * 4;
                            int sectorOffset = ((header[at] & 0xFF) << 16)
                                    | ((header[at + 1] & 0xFF) << 8) | (header[at + 2] & 0xFF);
                            if (sectorOffset == 0 || (header[at + 3] & 0xFF) == 0) {
                                continue;  // never written
                            }
                            int cx = (rx << 5) + (local & 31);
                            int cz = (rz << 5) + (local >> 5);
                            if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) {
                                continue;
                            }
                            found.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                        }
                    }
                }
                final int regionCount = regions;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pending.addAll(found);
                    generated = found.size();
                    regionScanDone = true;
                    say("Found " + generated + " generated chunks across " + regionCount
                            + " region files. Reading them now.");
                });
            });
        }

        /** Region directory for this world; the two possible layouts live in {@code RegionReader}. */
        private static java.io.File regionFolder(World world) {
            return org.aincrad.plugin.editor.RegionReader.regionFolder(
                    world.getWorldFolder().getAbsoluteFile(),
                    world.getKey().getNamespace(), world.getKey().getKey());
        }

        /** Keeps the async chunk-read queue topped up from the pending list. */
        private void issueLoads() {
            while (inFlight < MAX_IN_FLIGHT && !pending.isEmpty()) {
                long packed = pending.removeLast();
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                inFlight++;
                world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, error) -> {
                    if (chunk != null) {
                        ready.add(chunk);
                    } else {
                        // Nothing to draw; still has to release its slot or loading stalls.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            inFlight--;
                            unreadable++;
                        });
                    }
                });
            }
        }

        /** Snapshots and accumulates whatever finished loading. Main thread, bounded work. */
        private void drainReady() {
            org.bukkit.Chunk chunk;
            int done = 0;
            while (done < DRAIN_PER_TICK && (chunk = ready.poll()) != null) {
                inFlight--;
                done++;
                accumulate(chunk.getChunkSnapshot(true, false, false), chunk.getX(), chunk.getZ());
                accumulated++;
            }
        }

        /** Folds one chunk's 256 surface columns into the board's pixel accumulators. */
        private void accumulate(ChunkSnapshot snap, int cx, int cz) {
            int baseX = cx << 4;
            int baseZ = cz << 4;
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight() - 1;
            for (int i = 0; i < 16; i++) {
                int wx = baseX + i;
                int px = Math.floorDiv(wx - originX, blocksPerPixel);
                if (px < 0 || px >= width) {
                    continue;
                }
                for (int j = 0; j < 16; j++) {
                    int wz = baseZ + j;
                    int py = Math.floorDiv(wz - originZ, blocksPerPixel);
                    if (py < 0 || py >= height) {
                        continue;
                    }
                    int y = Math.min(maxY, snap.getHighestBlockYAt(i, j));
                    if (y < minY) {
                        continue;      // empty column (void below the floor plate)
                    }
                    // The heightmap can point one above the top solid block depending on which
                    // heightmap the server filled, so step down until a real block is found.
                    Material m = snap.getBlockType(i, y, j);
                    while (m.isAir() && y > minY) {
                        m = snap.getBlockType(i, --y, j);
                    }
                    if (m.isAir()) {
                        continue;      // genuinely empty column: leave it void
                    }
                    Color colour = OverheadMapRenderer.colourFor(m);
                    int idx = py * width + px;
                    rSum[idx] += colour.getRed();
                    gSum[idx] += colour.getGreen();
                    bSum[idx] += colour.getBlue();
                    hSum[idx] += y;
                    samples[idx]++;
                }
            }
        }

        /** Resolves accumulators into a per-pixel height field, ready for shading. */
        private void beginFinish() {
            pixelHeight = new int[width * height];
            for (int i = 0; i < pixelHeight.length; i++) {
                pixelHeight[i] = samples[i] == 0 ? Integer.MIN_VALUE : hSum[i] / samples[i];
            }
            say("Read " + accumulated + " of " + generated + " chunks on disk"
                    + (unreadable > 0 ? " (" + unreadable
                        + " are only partly generated and are drawn as void)" : "")
                    + ". Painting " + (cols * rows) + " tiles...");
        }

        /** Creates a few map views and frames per tick until the grid is filled. */
        private void finishTiles() {
            int madeThisTick = 0;
            while (madeThisTick < TILES_PER_TICK && finishCursor < cols * rows) {
                int c = finishCursor % cols;
                int r = finishCursor / cols;
                finishCursor++;
                madeThisTick++;

                byte[] pixels = new byte[TILE * TILE];
                for (int ty = 0; ty < TILE; ty++) {
                    for (int tx = 0; tx < TILE; tx++) {
                        int px = c * TILE + tx;
                        int py = r * TILE + ty;
                        pixels[ty * TILE + tx] = paletteIndex(shadedColour(px, py));
                    }
                }

                MapView view = Bukkit.createMap(world);
                view.setScale(MapView.Scale.FARTHEST);
                view.setTrackingPosition(false);
                view.setUnlimitedTracking(false);
                view.setLocked(true);
                for (MapRenderer existing : view.getRenderers()) {
                    view.removeRenderer(existing);      // drop the vanilla terrain renderer
                }
                view.addRenderer(new CachedTileRenderer(pixels));

                ItemStack item = new ItemStack(Material.FILLED_MAP);
                if (item.getItemMeta() instanceof MapMeta meta) {
                    meta.setMapView(view);
                    item.setItemMeta(meta);
                }

                Location loc = new Location(world, boardX + c, boardTopY - r, boardZ + 1);
                ItemFrame frame = world.spawn(loc, ItemFrame.class, f -> {
                    f.setFacingDirection(BlockFace.SOUTH, true);
                    f.setItem(item, false);
                    f.setVisible(false);      // hide the border so tiles butt together
                    f.setFixed(true);
                    f.setInvulnerable(true);
                });
                if (frame != null) {
                    tilesBuilt++;
                }
            }
            if (finishCursor >= cols * rows) {
                say("Map board built: " + tilesBuilt + " tiles covering "
                        + (width * blocksPerPixel) + "x" + (height * blocksPerPixel) + " blocks, from "
                        + accumulated + " chunks. Stands at (" + boardX + ", " + boardTopY + ", "
                        + boardZ + ") facing south.");
                plugin.getLogger().info("Floor map board: " + tilesBuilt + " tiles, "
                        + accumulated + " of " + generated + " chunks read, "
                        + unreadable + " partly generated and skipped.");
                finishTask();
                active = null;
            }
        }

        /** Average colour of a pixel's columns, with a north-west relief shade. */
        private Color shadedColour(int px, int py) {
            int idx = py * width + px;
            if (samples[idx] == 0) {
                return VOID_COLOUR;
            }
            int n = samples[idx];
            int h = pixelHeight[idx];
            int nw = (px > 0 && py > 0) ? pixelHeight[(py - 1) * width + (px - 1)] : h;
            if (nw == Integer.MIN_VALUE) {
                nw = h;
            }
            double shade = 1.0 + Math.max(-0.25, Math.min(0.25, (h - nw) * 0.08));
            return new Color(
                    clamp((int) (rSum[idx] / (double) n * shade)),
                    clamp((int) (gSum[idx] / (double) n * shade)),
                    clamp((int) (bSum[idx] / (double) n * shade)));
        }

        private void report() {
            long now = System.currentTimeMillis();
            if (now - lastReport < 4000) {
                return;
            }
            lastReport = now;
            int pct = generated == 0 ? 100 : (int) (accumulated * 100L / generated);
            say("Reading world: " + pct + "% (" + accumulated + "/" + generated + " chunks)");
        }

        private void say(String message) {
            if (feedback != null) {
                feedback.sendMessage("[map] " + message);
            }
        }

        void finishTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    private static byte paletteIndex(Color colour) {
        return PALETTE_CACHE.computeIfAbsent(colour.getRGB(), rgb -> MapPalette.matchColor(new Color(rgb)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
