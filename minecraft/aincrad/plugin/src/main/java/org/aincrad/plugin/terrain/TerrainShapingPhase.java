package org.aincrad.plugin.terrain;

import org.aincrad.plugin.generation.BlockEdit;
import org.aincrad.plugin.generation.GenerationScheduler;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.mask.ChunkCoord;
import org.bukkit.Material;
import org.bukkit.World;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase 2 (macro terrain / Town foundation grading) for the review slice: adjusts the flat
 * Y=319 baseline per-column to docs/floor-01-layout.json's south_grasslands formula (the entire
 * mask lies inside that one region). One ledger feature per chunk, sorted before enqueue per the
 * generation_contract's determinism rule; already-COMMITTED chunks are skipped so a restart never
 * repaints finished terrain.
 */
public final class TerrainShapingPhase {

    private static final String PHASE_ID = "phase2_macro_terrain";
    private static final String FEATURE_VERSION = "terrain_shape:v1";
    private static final int GRASS_Y = 319;

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public TerrainShapingPhase(GenerationLedger ledger, TerrainProfile profile,
                                GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.profile = profile;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /**
     * Plans and submits mask chunks incrementally, chunksPerTick per tick, rather than doing all
     * of it synchronously in the calling (command-handling) thread. A first version of this did
     * all 7,912 chunks' ledger planning synchronously in one command call and froze the main
     * thread for ~30 seconds (Paper's watchdog logged repeated "server has not responded" thread
     * dumps, server/boot-test8.log) - the block-edit budget was already respected, but the
     * one-time planning burst was not. This spreads planning across ticks the same way.
     * Returns immediately with the number of chunks considered (not necessarily all newly
     * enqueued - already-COMMITTED chunks are skipped as they're processed).
     */
    public int enqueueAllIncrementally(Set<ChunkCoord> maskChunks, Plugin plugin, int chunksPerTick) {
        List<ChunkCoord> sorted = new ArrayList<>(maskChunks);
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
                    String featureId = "f01/terrain/shape/chunk_" + coord.x() + "_" + coord.z();
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent() && existing.get().state() == GenerationLedger.FeatureState.COMMITTED) {
                            continue;
                        }
                        if (existing.isEmpty()) {
                            Set<int[]> chunkAsSet = Set.of(new int[]{coord.x(), coord.z()});
                            ledger.planFeature(featureId, FEATURE_VERSION, PHASE_ID, chunkAsSet,
                                    System.currentTimeMillis());
                        }
                    } catch (SQLException e) {
                        logger.log(Level.SEVERE, "Ledger error planning " + featureId, e);
                        continue;
                    }
                    scheduler.submit(new ChunkShapingJob(coord, featureId));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return totalToConsider;
    }

    private final class ChunkShapingJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;

        ChunkShapingJob(ChunkCoord coord, String featureId) {
            this.coord = coord;
            this.featureId = featureId;
        }

        @Override
        public String featureId() {
            return featureId;
        }

        @Override
        public Iterator<BlockEdit> edits() {
            List<BlockEdit> edits = new ArrayList<>();
            int baseX = coord.x() * 16;
            int baseZ = coord.z() * 16;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    int targetSurfaceY = profile.surfaceY(x, z);
                    if (targetSurfaceY == GRASS_Y) {
                        continue;
                    }
                    if (targetSurfaceY > GRASS_Y) {
                        for (int y = GRASS_Y; y < targetSurfaceY; y++) {
                            edits.add(new BlockEdit(x, y, z, Material.DIRT));
                        }
                        edits.add(new BlockEdit(x, targetSurfaceY, z, Material.GRASS_BLOCK));
                    } else {
                        for (int y = targetSurfaceY + 1; y <= GRASS_Y; y++) {
                            edits.add(new BlockEdit(x, y, z, Material.AIR));
                        }
                        edits.add(new BlockEdit(x, targetSurfaceY, z, Material.GRASS_BLOCK));
                    }
                }
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
