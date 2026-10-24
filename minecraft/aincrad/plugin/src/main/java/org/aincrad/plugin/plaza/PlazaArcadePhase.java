package org.aincrad.plugin.plaza;

import org.aincrad.plugin.generation.BlockEdit;
import org.aincrad.plugin.generation.GenerationScheduler;
import org.aincrad.plugin.geometry.BlockColumn;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.mask.ChunkCoord;
import org.aincrad.plugin.mask.MaskResult;
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
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Central Plaza's covered arcade: the spec's "80-100 modules" ringing the paved disc between
 * the plaza edge (r=108) and the arcade outer radius (r=136). Each module is a pair of piers with a
 * lintel and a roof bay, giving the required 4 blocks of clear pedestrian height.
 *
 * <p>Modules are placed at even angular intervals so the count is exact and reportable rather than
 * emergent, and the paving beneath them is graded to the same flat deck as the plaza so the whole
 * ring is step-free.</p>
 */
public final class PlazaArcadePhase {

    private static final String PHASE_ID = "phase7_plaza_arcade";
    private static final String FEATURE_VERSION = "plaza_arcade:v1";

    private static final int CX = 0, CZ = 4416;
    private static final int DECK_Y = 322;
    private static final int INNER_R = 112;
    private static final int OUTER_R = 132;
    private static final int MODULES = 90;          // inside the spec's 80-100
    private static final int PIER_TOP = DECK_Y + 4; // 4 clear pedestrian blocks
    private static final int ROOF_Y = DECK_Y + 5;

    private final GenerationLedger ledger;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public PlazaArcadePhase(GenerationLedger ledger, MaskResult mask,
                             GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public int moduleCount() {
        return MODULES;
    }

    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        for (int x = CX - OUTER_R - 2; x <= CX + OUTER_R + 2; x++) {
            for (int z = CZ - OUTER_R - 2; z <= CZ + OUTER_R + 2; z++) {
                double r = Math.hypot(x - CX, z - CZ);
                if (r < INNER_R - 2 || r > OUTER_R + 2) {
                    continue;
                }
                ChunkCoord coord = new ChunkCoord(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (!mask.contains(coord.x(), coord.z())) {
                    continue;
                }
                byChunk.computeIfAbsent(coord, k -> new ArrayList<>()).add(new BlockColumn(x, z));
            }
        }

        List<ChunkCoord> sorted = new ArrayList<>(byChunk.keySet());
        sorted.sort(Comparator.comparingInt(ChunkCoord::x).thenComparingInt(ChunkCoord::z));
        Deque<ChunkCoord> pending = new ArrayDeque<>(sorted);
        int total = pending.size();
        logger.info("Plaza arcade: " + MODULES + " modules over " + total + " chunks.");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/plaza/arcade/chunk_" + coord.x() + "_" + coord.z();
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent()) {
                            if (existing.get().state() == GenerationLedger.FeatureState.COMMITTED) {
                                continue;
                            }
                        } else {
                            ledger.planFeature(featureId, FEATURE_VERSION, PHASE_ID,
                                    Set.of(new int[]{coord.x(), coord.z()}), System.currentTimeMillis());
                        }
                    } catch (SQLException e) {
                        logger.log(Level.SEVERE, "Ledger error planning " + featureId, e);
                        continue;
                    }
                    scheduler.submit(new ArcadeChunkJob(coord, featureId, byChunk.get(coord)));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    /** True when this column carries a pier of one of the evenly spaced modules. */
    private static boolean isPier(int x, int z, int radius) {
        double r = Math.hypot(x - CX, z - CZ);
        if (Math.abs(r - radius) > 1.0) {
            return false;
        }
        double ang = Math.toDegrees(Math.atan2(z - CZ, x - CX));
        if (ang < 0) {
            ang += 360;
        }
        double step = 360.0 / MODULES;
        double off = ang % step;
        return off < step * 0.35;
    }

    private final class ArcadeChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        ArcadeChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
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
            List<BlockEdit> edits = new ArrayList<>();
            for (BlockColumn c : columns) {
                int x = c.x(), z = c.z();
                double r = Math.hypot(x - CX, z - CZ);
                if (r < INNER_R - 1 || r > OUTER_R + 1) {
                    continue;
                }
                // Flat arcade floor, level with the plaza deck.
                edits.add(new BlockEdit(x, DECK_Y, z, Material.POLISHED_ANDESITE));
                for (int y = DECK_Y + 1; y <= ROOF_Y + 2; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.AIR));
                }
                boolean inner = isPier(x, z, INNER_R);
                boolean outer = isPier(x, z, OUTER_R);
                if (inner || outer) {
                    for (int y = DECK_Y + 1; y <= PIER_TOP; y++) {
                        edits.add(new BlockEdit(x, y, z, Material.POLISHED_ANDESITE));
                    }
                    if (outer) {
                        edits.add(new BlockEdit(x, PIER_TOP + 1, z, Material.LANTERN));
                    }
                }
                // Roof bay spanning the full arcade width.
                if (r >= INNER_R && r <= OUTER_R) {
                    edits.add(new BlockEdit(x, ROOF_Y, z, Material.DEEPSLATE_TILES));
                }
            }
            return edits.iterator();
        }

        @Override
        public void onComplete() {
            try {
                ledger.tryTransition(featureId, GenerationLedger.FeatureState.COMMITTED,
                        hash(coord), null, System.currentTimeMillis());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Ledger error committing " + featureId, e);
            }
        }
    }

    private String hash(ChunkCoord coord) {
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
