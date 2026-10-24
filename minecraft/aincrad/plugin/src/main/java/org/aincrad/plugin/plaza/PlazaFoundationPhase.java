package org.aincrad.plugin.plaza;

import org.aincrad.plugin.generation.BlockEdit;
import org.aincrad.plugin.generation.GenerationScheduler;
import org.aincrad.plugin.geometry.BlockColumn;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase for the Central Plaza's paved foundation disc only - per this task's scope, the clock
 * tower, Teleport Gate structure, Black Iron Palace, and arcades are separate, larger follow-up
 * phases. This phase paints a single filled disc of radius {@link #PAVED_RADIUS} centered at
 * {@link #CENTER_X}, {@link #CENTER_Z} with the concentric stone-band pattern required by
 * FLOOR-01-RECONSTRUCTION-SPEC.md's "concentric stone pattern" note, replacing whatever surface
 * block is at {@link TerrainProfile#surfaceY(int, int)} for each column with the paving material.
 * No lamps/trees/benches are placed here - paving only.
 *
 * <p>Mirrors {@link org.aincrad.plugin.terrain.TerrainShapingPhase}'s resumability contract: one
 * ledger feature per chunk, sorted before enqueue, already-COMMITTED chunks skipped, one
 * {@link GenerationScheduler.FeatureJob} submitted per chunk, and a SHA-256 content hash committed
 * on completion. Planning itself is spread across ticks via {@link #enqueueAllIncrementally} so a
 * restart or a large plaza never blocks the main thread synchronously (the same lesson learned
 * from a real ~30s freeze during terrain-phase planning in this project).</p>
 */
public final class PlazaFoundationPhase {

    private static final String PHASE_ID = "phase_plaza_foundation";
    private static final String FEATURE_VERSION_V1 = "plaza_foundation:v1";
    private static final String FEATURE_VERSION = "plaza_foundation:v2";

    private static final int CENTER_X = 0;
    private static final int CENTER_Z = 4416;
    private static final double PAVED_RADIUS = 108.0;

    /**
     * The plaza is a graded platform at a single level, not a paved skin over the terrain noise.
     * v1 paved each column at its own {@code TerrainProfile.surfaceY}, so the "crowd surface"
     * undulated across roughly a 15-block spread - visibly wrong for a monumental civic space and
     * a poor floor for the required 140x140 unobstructed crowd area. v2 cuts and fills to a flat
     * deck.
     *
     * <p>Deck level 322 is taken from the layout JSON's own Town centre elevation
     * ({@code town_of_beginnings.center = [0,322,4500]}) and is consistent with the documented
     * arrival point {@code (0,323,4416)} - a player standing on a Y=322 deck occupies Y=323.</p>
     */
    private static final int DECK_Y = 322;

    // Concentric radial bands, per FLOOR-01-RECONSTRUCTION-SPEC.md's concentric stone pattern
    // requirement. Upper bounds are inclusive; a column's radial distance from center selects the
    // band it falls in.
    private static final double BAND_1_OUTER = 24.0;
    private static final double BAND_2_OUTER = 68.0;
    private static final double BAND_3_OUTER = 84.0;
    // BAND_4_OUTER == PAVED_RADIUS (108.0)

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public PlazaFoundationPhase(GenerationLedger ledger, TerrainProfile profile,
                                 GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.profile = profile;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /**
     * Rasterizes the filled plaza disc into per-chunk column groups, filtered defensively to
     * mask-authorized chunks only (the disc is comfortably inside town_core's Z=3904..5039 span,
     * but every phase in this project must still filter by {@link MaskResult#contains(int, int)}).
     * Plans and submits one ledger feature/job per touched chunk incrementally, chunksPerTick per
     * tick, rather than synchronously in the calling thread. Returns immediately with the number
     * of chunks considered (not necessarily all newly enqueued - already-COMMITTED chunks are
     * skipped as they're processed).
     */
    public int enqueueAllIncrementally(MaskResult mask, Plugin plugin, int chunksPerTick) {
        Map<ChunkCoord, List<BlockColumn>> byChunk = rasterizeByChunk(mask);

        List<ChunkCoord> sorted = new ArrayList<>(byChunk.keySet());
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
                    List<BlockColumn> columns = byChunk.get(coord);
                    String featureId = "f01/tob/plaza/chunk_" + coord.x() + "_" + coord.z();
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent()) {
                            GenerationLedger.FeatureRecord record = existing.get();
                            if (FEATURE_VERSION_V1.equals(record.featureVersion())) {
                                // v1 -> v2: regrade the undulating paved skin into a flat deck.
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
                            java.util.Set<int[]> chunkAsSet = java.util.Set.of(new int[]{coord.x(), coord.z()});
                            ledger.planFeature(featureId, FEATURE_VERSION, PHASE_ID, chunkAsSet,
                                    System.currentTimeMillis());
                        }
                    } catch (SQLException e) {
                        logger.log(Level.SEVERE, "Ledger error planning " + featureId, e);
                        continue;
                    }
                    scheduler.submit(new PlazaPavingJob(coord, columns, featureId));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return totalToConsider;
    }

    /**
     * Builds the filled disc via a direct bounded loop (not {@code GeometryUtils.thickArcColumns},
     * which rasterizes a ring rather than a filled area), then groups the resulting columns by the
     * chunk they belong to, dropping any column whose chunk is not mask-authorized.
     */
    private Map<ChunkCoord, List<BlockColumn>> rasterizeByChunk(MaskResult mask) {
        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        int radiusFloor = (int) Math.floor(PAVED_RADIUS);
        for (int dx = -radiusFloor; dx <= radiusFloor; dx++) {
            for (int dz = -radiusFloor; dz <= radiusFloor; dz++) {
                if (Math.hypot(dx, dz) > PAVED_RADIUS) {
                    continue;
                }
                int x = CENTER_X + dx;
                int z = CENTER_Z + dz;
                int chunkX = Math.floorDiv(x, 16);
                int chunkZ = Math.floorDiv(z, 16);
                if (!mask.contains(chunkX, chunkZ)) {
                    continue;
                }
                ChunkCoord coord = new ChunkCoord(chunkX, chunkZ);
                byChunk.computeIfAbsent(coord, unused -> new ArrayList<>()).add(new BlockColumn(x, z));
            }
        }
        return byChunk;
    }

    /** Selects the paving material for a column at the given radial distance from plaza center. */
    private static Material pavingMaterialFor(double radialDistance) {
        if (radialDistance <= BAND_1_OUTER) {
            return Material.POLISHED_ANDESITE;
        } else if (radialDistance <= BAND_2_OUTER) {
            return Material.ANDESITE;
        } else if (radialDistance <= BAND_3_OUTER) {
            return Material.POLISHED_ANDESITE;
        } else {
            return Material.ANDESITE;
        }
    }

    private final class PlazaPavingJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final List<BlockColumn> columns;
        private final String featureId;

        PlazaPavingJob(ChunkCoord coord, List<BlockColumn> columns, String featureId) {
            this.coord = coord;
            this.columns = columns;
            this.featureId = featureId;
        }

        @Override
        public String featureId() {
            return featureId;
        }

        @Override
        public Iterator<BlockEdit> edits() {
            List<BlockEdit> edits = new ArrayList<>();
            for (BlockColumn column : columns) {
                int x = column.x();
                int z = column.z();
                double radialDistance = Math.hypot(x - CENTER_X, z - CENTER_Z);
                Material paving = pavingMaterialFor(radialDistance);
                int surfaceY = profile.surfaceY(x, z);

                // CUT: strip anything standing above the deck (including v1's paving, which was
                // laid at the old undulating terrain height).
                for (int y = DECK_Y + 1; y <= Math.max(surfaceY, DECK_Y) + 4; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.AIR));
                }
                // FILL: where natural ground sits below the deck, build a sub-base up to it so the
                // platform is solid rather than a paved lid over a void. The outermost ring uses
                // stone brick so the exposed embankment face reads as a deliberate retaining course.
                boolean rim = radialDistance > PAVED_RADIUS - 2.0;
                Material subBase = rim ? Material.STONE_BRICKS : Material.DEEPSLATE_TILES;
                for (int y = Math.min(surfaceY, DECK_Y - 1); y < DECK_Y; y++) {
                    edits.add(new BlockEdit(x, y, z, subBase));
                }
                // DECK.
                edits.add(new BlockEdit(x, DECK_Y, z, paving));
            }
            return edits.iterator();
        }

        @Override
        public void onComplete() {
            try {
                ledger.tryTransition(featureId, GenerationLedger.FeatureState.COMMITTED,
                        hashChunkFeature(coord), null, System.currentTimeMillis());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Ledger error committing " + featureId, e);
            }
        }
    }

    private String hashChunkFeature(ChunkCoord coord) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((FEATURE_VERSION + ":" + coord.x() + ":" + coord.z()).getBytes(StandardCharsets.UTF_8));
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
