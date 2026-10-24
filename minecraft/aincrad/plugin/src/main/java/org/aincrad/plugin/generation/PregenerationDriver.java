package org.aincrad.plugin.generation;

import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.terrain.FloorOneTerrain;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whole-floor pregeneration with real chunk lifecycle management.
 *
 * <p>Every earlier phase in this project loaded chunks and left them loaded, which is fine for the
 * 7,912-chunk review slice and fatal for the 308,028-chunk floor - it simply will not fit in memory.
 * This driver loads one chunk, edits it, then saves and unloads it before moving on, so peak memory
 * stays flat regardless of how many chunks remain.</p>
 *
 * <p><b>Resumable by cursor, not by row.</b> Chunk order is deterministic (sorted by ring then x/z),
 * so progress is a single integer stored in the ledger. Storing 308k per-chunk rows would work but
 * costs far more than it buys; an interrupted run at worst redoes a handful of chunks, and every
 * edit is idempotent.</p>
 *
 * <p><b>Committed chunks are skipped.</b> The Town's 7,912 chunks were shaped with the older
 * {@code TerrainProfile} and are committed; re-shaping them with the whole-floor function would
 * destroy finished work, so any chunk with a committed terrain feature is passed over untouched.</p>
 */
public final class PregenerationDriver {

    public static final String CURSOR_KEY = "fullfloor_terrain_cursor";
    private static final String FEATURE_PREFIX = "f01/terrain/shape/chunk_";

    private final World world;
    private final GenerationLedger ledger;
    private final FloorOneTerrain terrain;
    private final Logger logger;

    private List<int[]> order = List.of();
    private int cursor;
    private long chunksDone;
    private long editsApplied;
    private long startedAtMillis;
    private boolean running;
    private BukkitRunnable task;

    public PregenerationDriver(World world, GenerationLedger ledger, FloorOneTerrain terrain,
                                Logger logger) {
        this.world = world;
        this.ledger = ledger;
        this.terrain = terrain;
        this.logger = logger;
    }

    /** All chunks intersecting the floor disc, in a deterministic ring-outward order. */
    public List<int[]> buildOrder() {
        List<int[]> chunks = new ArrayList<>(320_000);
        int cr = FloorOneTerrain.FLOOR_RADIUS / 16 + 1;
        for (int cx = -cr; cx <= cr; cx++) {
            for (int cz = -cr; cz <= cr; cz++) {
                int nx = Math.max(Math.abs(cx * 16 + 8) - 8, 0);
                int nz = Math.max(Math.abs(cz * 16 + 8) - 8, 0);
                if (Math.hypot(nx, nz) <= FloorOneTerrain.FLOOR_RADIUS) {
                    chunks.add(new int[]{cx, cz});
                }
            }
        }
        chunks.sort((a, b) -> {
            long ra = (long) a[0] * a[0] + (long) a[1] * a[1];
            long rb = (long) b[0] * b[0] + (long) b[1] * b[1];
            if (ra != rb) {
                return Long.compare(ra, rb);
            }
            if (a[0] != b[0]) {
                return Integer.compare(a[0], b[0]);
            }
            return Integer.compare(a[1], b[1]);
        });
        order = chunks;
        return chunks;
    }

    public int totalChunks() {
        return order.isEmpty() ? buildOrder().size() : order.size();
    }

    public int cursor() {
        return cursor;
    }

    public boolean isRunning() {
        return running;
    }

    public long chunksDone() {
        return chunksDone;
    }

    public long editsApplied() {
        return editsApplied;
    }

    public double chunksPerSecond() {
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        return elapsed <= 0 ? 0 : chunksDone * 1000.0 / elapsed;
    }

    public void loadCursor() {
        try {
            cursor = ledger.getMeta(CURSOR_KEY).map(Integer::parseInt).orElse(0);
        } catch (SQLException | NumberFormatException e) {
            cursor = 0;
        }
    }

    private void saveCursor() {
        try {
            ledger.putMeta(CURSOR_KEY, Integer.toString(cursor));
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not persist pregeneration cursor", e);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        running = false;
        saveCursor();
        logger.info("Pregeneration paused at cursor " + cursor + "/" + totalChunks()
                + " (" + chunksDone + " chunks this run, " + editsApplied + " edits)");
    }

    /**
     * @param chunksPerTick how many chunks to process per server tick
     * @param limit         stop after this many chunks this run (0 = run to completion)
     */
    public void start(Plugin plugin, int chunksPerTick, int limit) {
        if (running) {
            logger.warning("Pregeneration already running.");
            return;
        }
        if (order.isEmpty()) {
            buildOrder();
        }
        loadCursor();
        running = true;
        chunksDone = 0;
        editsApplied = 0;
        startedAtMillis = System.currentTimeMillis();
        int total = order.size();
        logger.info("Pregeneration starting at cursor " + cursor + "/" + total
                + (limit > 0 ? (", limit " + limit + " chunks this run") : ", running to completion"));

        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < chunksPerTick; i++) {
                    if (cursor >= total || (limit > 0 && chunksDone >= limit)) {
                        logger.info("Pregeneration run finished: cursor " + cursor + "/" + total
                                + ", " + chunksDone + " chunks, " + editsApplied + " edits, "
                                + String.format("%.1f", chunksPerSecond()) + " chunks/s");
                        stop();
                        return;
                    }
                    int[] c = order.get(cursor);
                    cursor++;
                    try {
                        processChunk(c[0], c[1]);
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Chunk " + c[0] + "," + c[1] + " failed", e);
                    }
                    chunksDone++;
                }
                if (chunksDone % 2000 == 0) {
                    saveCursor();
                    logger.info("Pregen " + cursor + "/" + total + "  "
                            + String.format("%.1f", cursor * 100.0 / total) + "%  "
                            + String.format("%.1f", chunksPerSecond()) + " chunks/s");
                }
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void processChunk(int cx, int cz) {
        String featureId = FEATURE_PREFIX + cx + "_" + cz;
        try {
            var existing = ledger.getFeature(featureId);
            if (existing.isPresent()
                    && existing.get().state() == GenerationLedger.FeatureState.COMMITTED) {
                return;   // Town review slice, already shaped - never re-shape committed work
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ledger check failed for " + featureId, e);
        }

        boolean wasLoaded = world.isChunkLoaded(cx, cz);
        world.getChunkAt(cx, cz);
        int edits = 0;
        int baseX = cx * 16, baseZ = cz * 16;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx, z = baseZ + dz;
                if (!FloorOneTerrain.inPlayableDisc(x, z)) {
                    continue;
                }
                int target = terrain.surfaceY(x, z);
                FloorOneTerrain.Region region = terrain.regionAt(x, z);

                // The datapack lays a flat solid column with grass at 319; reshape to target.
                if (target > 319) {
                    for (int y = 319; y < target; y++) {
                        setIfNeeded(x, y, z, Material.DIRT);
                        edits++;
                    }
                } else if (target < 319) {
                    for (int y = target + 1; y <= 319; y++) {
                        setIfNeeded(x, y, z, Material.AIR);
                        edits++;
                    }
                }
                setIfNeeded(x, target, z, surfaceMaterial(region));
                edits++;

                // Fill lake and marsh basins to the water datum.
                if (region == FloorOneTerrain.Region.LAKE || region == FloorOneTerrain.Region.MARSH) {
                    for (int y = target + 1; y <= FloorOneTerrain.WATER_Y; y++) {
                        setIfNeeded(x, y, z, Material.WATER);
                        edits++;
                    }
                }
            }
        }
        editsApplied += edits;

        // Save and release, so peak memory does not grow with the number of chunks processed.
        if (!wasLoaded) {
            world.unloadChunk(cx, cz, true);
        }
    }

    private void setIfNeeded(int x, int y, int z, Material material) {
        Block b = world.getBlockAt(x, y, z);
        if (b.getType() != material) {
            b.setType(material, false);
        }
    }

    private static Material surfaceMaterial(FloorOneTerrain.Region region) {
        return switch (region) {
            case LAKE, MARSH -> Material.GRAVEL;
            case CANYON -> Material.STONE;
            case MOUNTAIN -> Material.STONE;
            case RUINS -> Material.COARSE_DIRT;
            case LABYRINTH_SHELF -> Material.STONE;
            case RIM -> Material.STONE_BRICKS;
            case FOREST -> Material.PODZOL;
            default -> Material.GRASS_BLOCK;
        };
    }
}
