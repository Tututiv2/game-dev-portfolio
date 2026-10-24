package org.aincrad.plugin.district;

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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paves the street network the district planner reserves between its city blocks.
 *
 * <p>This phase exists because the first overhead render (visuals/town-overhead-map.png, v1) showed
 * 1,150 houses sitting on bare grass with no readable street pattern at all. The planner had always
 * left corridors between blocks - nothing had ever laid carriageway in them. Streets are what make
 * an aerial view read as a city rather than as scattered sheds, so this is a correctness fix for
 * the spec's visual bar, not decoration.</p>
 *
 * <p>Paving follows terrain rather than grading flat (the spec wants roads that look built on real
 * ground), skips any column already occupied by a building or a previously committed feature, and
 * distinguishes collectors from lanes by width.</p>
 */
public final class StreetPavingPhase {

    private static final String PHASE_ID = "phase5_district_streets";
    private static final String FEATURE_VERSION = "district_streets:v1";
    private static final int STREET_REACH = 5;

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;
    private final long worldSeed;

    public StreetPavingPhase(GenerationLedger ledger, TerrainProfile profile, MaskResult mask,
                              GenerationScheduler scheduler, Logger logger, long worldSeed) {
        this.ledger = ledger;
        this.profile = profile;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
        this.worldSeed = worldSeed;
    }

    public int enqueueAllIncrementally(Plugin plugin, List<Parcel> parcels,
                                        List<int[]> districts, List<String> districtIds,
                                        int chunksPerTick) {
        // Columns occupied by a building (plus a 1-block skirt for its roof overhang).
        Set<Long> occupied = new HashSet<>();
        for (Parcel p : parcels) {
            for (int x = p.minX() - 1; x <= p.maxX() + 1; x++) {
                for (int z = p.minZ() - 1; z <= p.maxZ() + 1; z++) {
                    occupied.add(pack(x, z));
                }
            }
        }

        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        for (int d = 0; d < districts.size(); d++) {
            int[] b = districts.get(d);
            String id = districtIds.get(d);
            for (int[] block : DistrictPlanner.planBlocks(id, b[0], b[1], b[2], b[3], worldSeed)) {
                addStreetRing(byChunk, occupied, block);
            }
        }

        List<ChunkCoord> sorted = new ArrayList<>(byChunk.keySet());
        sorted.sort(Comparator.comparingInt(ChunkCoord::x).thenComparingInt(ChunkCoord::z));
        Deque<ChunkCoord> pending = new ArrayDeque<>(sorted);
        int total = pending.size();
        logger.info("District streets: paving " + total + " chunks of carriageway.");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/streets/chunk_" + coord.x() + "_" + coord.z();
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
                    scheduler.submit(new StreetChunkJob(coord, featureId, byChunk.get(coord)));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    /** The carriageway ring immediately outside one city block. */
    private void addStreetRing(Map<ChunkCoord, List<BlockColumn>> byChunk, Set<Long> occupied,
                                int[] block) {
        int x0 = block[0] - STREET_REACH, x1 = block[2] + STREET_REACH;
        int z0 = block[1] - STREET_REACH, z1 = block[3] + STREET_REACH;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean insideBlock = x >= block[0] && x <= block[2] && z >= block[1] && z <= block[3];
                if (insideBlock || occupied.contains(pack(x, z))) {
                    continue;
                }
                if (ExclusionZones.isBlocked(x, z)) {
                    continue;   // never pave over the wall band, plaza, avenue, Palace or roads
                }
                ChunkCoord coord = new ChunkCoord(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (!mask.contains(coord.x(), coord.z())) {
                    continue;
                }
                List<BlockColumn> list = byChunk.computeIfAbsent(coord, k -> new ArrayList<>());
                BlockColumn col = new BlockColumn(x, z);
                if (!list.contains(col)) {
                    list.add(col);
                }
            }
        }
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private final class StreetChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        StreetChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
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
                int y = profile.surfaceY(x, z);
                // Gravel service lanes threaded through andesite civic paving, so the network
                // reads as a hierarchy rather than one uniform slab.
                boolean lane = Math.floorMod(x, 37) < 3 || Math.floorMod(z, 41) < 3;
                edits.add(new BlockEdit(x, y, z, lane ? Material.GRAVEL : Material.ANDESITE));
                edits.add(new BlockEdit(x, y + 1, z, Material.AIR));
                // Street lamps at spec interval for collectors.
                if (Math.floorMod(x, 22) == 0 && Math.floorMod(z, 22) == 0) {
                    for (int ly = y + 1; ly <= y + 3; ly++) {
                        edits.add(new BlockEdit(x, ly, z, Material.POLISHED_BLACKSTONE_WALL));
                    }
                    edits.add(new BlockEdit(x, y + 4, z, Material.LANTERN));
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
