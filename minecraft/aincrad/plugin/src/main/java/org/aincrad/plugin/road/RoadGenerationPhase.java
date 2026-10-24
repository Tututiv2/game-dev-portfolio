package org.aincrad.plugin.road;

import org.aincrad.plugin.generation.BlockEdit;
import org.aincrad.plugin.generation.GenerationScheduler;
import org.aincrad.plugin.geometry.BlockColumn;
import org.aincrad.plugin.geometry.GeometryUtils;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.mask.ChunkCoord;
import org.aincrad.plugin.mask.MaskResult;
import org.aincrad.plugin.terrain.TerrainProfile;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hand-authored road/path generation phase for the review slice: rasterizes the Main North Road
 * and its Horunka/Medai branch stubs (docs road layout) into thick polyline columns, filters them
 * down to the authorized 7,912-chunk mask, and resurfaces each surviving column with andesite at
 * TerrainProfile's already-generated surface height. This pass never builds up or digs down and
 * never adds curbs/gutters/lamps/shoulders - those are documented follow-ups, not part of this
 * pass's scope. One ledger feature per chunk (shared id space across all three routes - a chunk
 * touched by more than one route is still a single feature, and a column touched by more than one
 * route is resurfaced once), sorted before enqueue and planned incrementally off the main thread's
 * synchronous path (see {@link #enqueueAllIncrementally}) for the same reason
 * {@code TerrainShapingPhase} does: a synchronous burst across every mask chunk froze the server
 * for ~30s previously and must not happen again. Already-COMMITTED chunks are skipped so a restart
 * never repaints a finished road chunk.
 */
public final class RoadGenerationPhase {

    private static final String PHASE_ID = "phase5_roads";
    private static final String FEATURE_VERSION_V1 = "road_surface:v1";
    private static final String FEATURE_VERSION = "road_surface:v2";
    private static final Material ROAD_SURFACE_MATERIAL = Material.ANDESITE;

    /** Route A: "Main North Road" - primary, width_blocks=9 (half-width 4.5). */
    private static final List<double[]> MAIN_NORTH_ROAD = List.of(
            new double[]{0, 4000},
            new double[]{-80, 3160},
            new double[]{-240, 2240},
            new double[]{-160, 1240},
            new double[]{-320, 240},
            new double[]{-720, -860}
    );
    private static final double MAIN_NORTH_ROAD_HALF_WIDTH = 4.5;

    /**
     * Route B: "Northwest Gate to Horunka" branch - primary, width_blocks=7 (half-width 3.5).
     * First point moved (-380,4090) -> (-380,4000) so the road leaves through its own gate; at
     * 4090 it crossed the north wall line at X=-453, 73 blocks off-gate. See layout JSON
     * routes[west_route_to_horunka].start_point_note.
     */
    private static final List<double[]> HORUNKA_BRANCH = List.of(
            new double[]{-380, 4000},
            new double[]{-840, 3520},
            new double[]{-1520, 2960},
            new double[]{-2160, 2380},
            new double[]{-2780, 1840}
    );
    private static final double HORUNKA_BRANCH_HALF_WIDTH = 3.5;

    /** Route C: "Northeast Gate to Medai" branch - primary, width_blocks=7 (half-width 3.5).
     *  First point moved (380,4090) -> (380,4000); see {@link #HORUNKA_BRANCH}. */
    private static final List<double[]> MEDAI_BRANCH = List.of(
            new double[]{380, 4000},
            new double[]{960, 3380},
            new double[]{1520, 2480},
            new double[]{1960, 1440},
            new double[]{2560, -500}
    );
    private static final double MEDAI_BRANCH_HALF_WIDTH = 3.5;

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public RoadGenerationPhase(GenerationLedger ledger, TerrainProfile profile, MaskResult mask,
                                GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.profile = profile;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /**
     * Rasterizes all three routes, filters every column to the authorized mask (by the chunk it
     * falls in), merges the three routes' surviving columns into one set (so overlaps between
     * routes are resurfaced only once), and groups the result by chunk. Most of Route A's points
     * fall outside the mask - only the portion near (0,4000)..(-80,3160) is expected to survive.
     */
    private Map<ChunkCoord, List<BlockColumn>> computeMaskedColumnsByChunk() {
        Set<BlockColumn> merged = new LinkedHashSet<>();
        merged.addAll(GeometryUtils.thickPolylineColumns(MAIN_NORTH_ROAD, MAIN_NORTH_ROAD_HALF_WIDTH));
        merged.addAll(GeometryUtils.thickPolylineColumns(HORUNKA_BRANCH, HORUNKA_BRANCH_HALF_WIDTH));
        merged.addAll(GeometryUtils.thickPolylineColumns(MEDAI_BRANCH, MEDAI_BRANCH_HALF_WIDTH));

        Map<ChunkCoord, List<BlockColumn>> byChunk = new LinkedHashMap<>();
        for (BlockColumn column : merged) {
            int chunkX = Math.floorDiv(column.x(), 16);
            int chunkZ = Math.floorDiv(column.z(), 16);
            if (!mask.contains(chunkX, chunkZ)) {
                continue;
            }
            byChunk.computeIfAbsent(new ChunkCoord(chunkX, chunkZ), key -> new ArrayList<>()).add(column);
        }
        return byChunk;
    }

    /**
     * Plans and submits mask-touching road chunks incrementally, chunksPerTick per tick, rather
     * than synchronously in the calling (command-handling) thread - mirrors
     * {@code TerrainShapingPhase#enqueueAllIncrementally}'s reasoning exactly. The (comparatively
     * cheap) rasterize-and-filter pass runs once, synchronously, up front, since it is pure
     * in-memory geometry over a handful of polylines (not a per-chunk ledger/DB operation); only
     * the per-chunk ledger planning and job submission is spread across ticks. Returns immediately
     * with the number of road chunks considered (not necessarily all newly enqueued -
     * already-COMMITTED chunks are skipped as they're processed).
     */
    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        Map<ChunkCoord, List<BlockColumn>> columnsByChunk = computeMaskedColumnsByChunk();
        List<ChunkCoord> sorted = new ArrayList<>(columnsByChunk.keySet());
        sorted.sort(Comparator.comparingInt(ChunkCoord::x).thenComparingInt(ChunkCoord::z));
        Deque<ChunkCoord> pending = new ArrayDeque<>(sorted);
        int totalToConsider = pending.size();

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/road/chunk_" + coord.x() + "_" + coord.z();
                    List<BlockColumn> columns = columnsByChunk.get(coord);
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent()) {
                            GenerationLedger.FeatureRecord record = existing.get();
                            if (FEATURE_VERSION_V1.equals(record.featureVersion())) {
                                // v1 -> v2: reroutes both branch stubs onto their relocated gates,
                                // and repaints the north-gate surface that the wall v2 migration
                                // wrongly grassed over.
                                if (!ledger.migrateFeatureVersion(featureId, FEATURE_VERSION_V1,
                                        FEATURE_VERSION, System.currentTimeMillis())) {
                                    logger.warning("Refused to migrate " + featureId
                                            + " (owner-protected or already migrated); skipping.");
                                    continue;
                                }
                            } else if (record.state() == GenerationLedger.FeatureState.COMMITTED) {
                                continue;
                            }
                        } else {
                            Set<int[]> chunkAsSet = Set.of(new int[]{coord.x(), coord.z()});
                            ledger.planFeature(featureId, FEATURE_VERSION, PHASE_ID, chunkAsSet,
                                    System.currentTimeMillis());
                        }
                    } catch (SQLException e) {
                        logger.log(Level.SEVERE, "Ledger error planning " + featureId, e);
                        continue;
                    }
                    scheduler.submit(new RoadResurfaceJob(coord, featureId, columns));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return totalToConsider;
    }

    private final class RoadResurfaceJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        RoadResurfaceJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
            this.coord = coord;
            this.featureId = featureId;
            this.columns = columns;
        }

        @Override
        public String featureId() {
            return featureId;
        }

        @Override
        public Iterator<BlockEdit> edits() {
            List<BlockEdit> edits = new ArrayList<>(columns.size());
            for (BlockColumn column : columns) {
                int surfaceY = profile.surfaceY(column.x(), column.z());
                edits.add(new BlockEdit(column.x(), surfaceY, column.z(), ROAD_SURFACE_MATERIAL));
            }
            return edits.iterator();
        }

        @Override
        public void onComplete() {
            try {
                ledger.tryTransition(featureId, GenerationLedger.FeatureState.COMMITTED,
                        hashChunkFeature(coord, columns), null, System.currentTimeMillis());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Ledger error committing " + featureId, e);
            }
        }
    }

    private String hashChunkFeature(ChunkCoord coord, List<BlockColumn> columns) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder input = new StringBuilder();
            input.append(FEATURE_VERSION).append(':').append(coord.x()).append(':').append(coord.z());
            List<BlockColumn> sortedColumns = new ArrayList<>(columns);
            sortedColumns.sort(Comparator.comparingInt(BlockColumn::x).thenComparingInt(BlockColumn::z));
            for (BlockColumn column : sortedColumns) {
                input.append(';').append(column.x()).append(',').append(column.z());
            }
            digest.update(input.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
