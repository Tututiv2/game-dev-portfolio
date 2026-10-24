package org.aincrad.plugin.avenue;

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
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The processional avenue: the 17-block-wide civic axis linking the Black Iron Palace's ceremonial
 * stair to the Central Plaza's northern edge. Until this phase existed the two monuments sat 87
 * blocks apart across raw, ungraded terrain and did not connect as a designed route at all.
 *
 * <p>Graded dead flat at {@code Y=322} - the same deck level as both the plaza and the foot of the
 * Palace stair - so the entire spawn-to-Palace walk is step-free, satisfying the spec's "a player
 * must be able to reach every required service without parkour". Lamp posts are placed every 14
 * blocks along both kerbs, inside the spec's 12-16-block interval for grand avenues, and provide
 * real block light rather than relying on a resource pack.</p>
 */
public final class ProcessionalAvenuePhase {

    private static final String PHASE_ID = "phase5_processional_avenue";
    private static final String FEATURE_VERSION = "processional_avenue:v1";

    private static final int DECK_Y = 322;
    private static final int HALF_WIDTH = 8;        // 17 blocks inclusive of centre
    private static final int KERB_X = 9;            // kerb + lamp line just outside the carriageway
    private static final int Z_START = 4228;        // just south of the Palace stair run
    private static final int Z_END = 4310;          // 2 blocks into the plaza disc for a clean join
    private static final int LAMP_SPACING = 14;

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public ProcessionalAvenuePhase(GenerationLedger ledger, TerrainProfile profile, MaskResult mask,
                                    GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.profile = profile;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        for (int x = -KERB_X; x <= KERB_X; x++) {
            for (int z = Z_START; z <= Z_END; z++) {
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
        logger.info("Processional avenue: " + total + " chunks, Z " + Z_START + ".." + Z_END
                + " graded flat at Y=" + DECK_Y + ".");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/avenue/chunk_" + coord.x() + "_" + coord.z();
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
                    scheduler.submit(new AvenueChunkJob(coord, featureId, byChunk.get(coord)));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    private final class AvenueChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        AvenueChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
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
                int x = c.x();
                int z = c.z();
                int terrain = profile.surfaceY(x, z);
                boolean kerb = Math.abs(x) == KERB_X || Math.abs(x) == HALF_WIDTH;

                // Cut anything above the deck, fill anything below it.
                for (int y = DECK_Y + 1; y <= Math.max(terrain, DECK_Y) + 5; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.AIR));
                }
                for (int y = Math.min(terrain, DECK_Y - 1); y < DECK_Y; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.STONE_BRICKS));
                }
                edits.add(new BlockEdit(x, DECK_Y, z,
                        kerb ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE));

                // Lamp posts along both kerbs at the spec's grand-avenue interval.
                if (Math.abs(x) == KERB_X && Math.floorMod(z - Z_START, LAMP_SPACING) == 0) {
                    for (int y = DECK_Y + 1; y <= DECK_Y + 4; y++) {
                        edits.add(new BlockEdit(x, y, z, Material.POLISHED_BLACKSTONE_WALL));
                    }
                    edits.add(new BlockEdit(x, DECK_Y + 5, z, Material.LANTERN));
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
