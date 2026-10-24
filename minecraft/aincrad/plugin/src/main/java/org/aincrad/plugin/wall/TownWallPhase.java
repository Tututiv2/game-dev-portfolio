package org.aincrad.plugin.wall;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Town of Beginnings outer wall, v2.
 *
 * <p><b>Shape.</b> The wall is the D-shape defined authoritatively by
 * {@code floor-01-layout.json:settlements[town_of_beginnings].city_envelope}: a straight north
 * wall at {@code Z=4000} spanning {@code X=-500..500}, straight side walls at {@code X=+/-500}
 * running south to {@code Z=4500}, and a southern semicircle of radius 500 centered
 * {@code (0,4500)}. v1 of this class built a full circle instead - see
 * {@code docs/ARCHITECTURE-DECISION.md} 12 for why that reading was wrong (it left both flanking
 * gates outside the wall and the north_gate_services district 153 blocks outside it).</p>
 *
 * <p><b>Migration.</b> Because v1's circular wall is already built and COMMITTED, this phase is a
 * real migration, not a fresh build: for every affected chunk it first <em>demolishes</em> the v1
 * circle (clearing its columns back to terrain) and then builds the v2 D-shape, in that order
 * within a single chunk job. Ledger features are moved v1 -> v2 through
 * {@link GenerationLedger#migrateFeatureVersion} which refuses to touch owner-protected or
 * already-migrated features. Verified before writing this: the v1 circle does not intersect any
 * road, the plaza, or the palace, so restoring grass under demolished columns cannot damage
 * another committed feature.</p>
 *
 * <p><b>Level crest.</b> v1 built every column from its own terrain height, so the wall top
 * followed the terrain noise and undulated. v2 levels the crest within 32x32 cells (each cell's
 * crest sits a fixed height above that cell's highest wall column), producing a stepped,
 * architectural silhouette while keeping the parapet within the spec's 13-16-blocks-above-grade
 * band instead of the 13-31 a single global crest would have produced.</p>
 */
public final class TownWallPhase {

    private static final String PHASE_ID = "phase_tob_wall";
    private static final String FEATURE_VERSION_V1 = "tob_wall:v1";
    private static final String FEATURE_VERSION_V2 = "tob_wall:v2";
    private static final String FEATURE_VERSION = "tob_wall:v3";

    private static final double CENTER_X = 0.0;
    private static final double CENTER_Z = 4500.0;
    private static final double RADIUS = 500.0;
    private static final double HALF_WIDTH = 3.0;
    private static final int NORTH_WALL_Z = 4000;
    private static final int MIN_X = -500;
    private static final int MAX_X = 500;

    /** Gate openings: {x, z, halfWidth}. Values from the layout JSON, which is geometry authority. */
    private static final double[][] GATES = {
            {0.0, 4000.0, 10.0},
            {-380.0, 4000.0, 7.5},
            {380.0, 4000.0, 7.5},
    };

    /**
     * The gate openings the <em>v1</em> circular wall used. These columns must be excluded from
     * demolition: v1 never placed wall there, so "demolishing" them would clear whatever else
     * legitimately occupies that ground. This is not hypothetical - the v2 migration omitted this
     * filter and grassed over the Main North Road where it passes through the north gate
     * (verify-world sample at (0,4000) went ANDESITE -> GRASS_BLOCK between boot-test10 and
     * boot-test11). v3 restores the exclusion.
     */
    private static final double[][] V1_GATES = {
            {0.0, 4000.0, 10.0},
            {-380.0, 4090.0, 7.5},
            {380.0, 4090.0, 7.5},
    };

    private static final int CREST_CELL = 32;
    private static final int PARAPET_ABOVE_CREST = 14;

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public TownWallPhase(GenerationLedger ledger, TerrainProfile profile, MaskResult mask,
                          GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.profile = profile;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** The v2 D-shape wall footprint: north wall + two side walls + southern semicircle. */
    private Set<BlockColumn> rasterizeNewWall() {
        Set<BlockColumn> columns = new LinkedHashSet<>();
        int halfWidthInt = (int) Math.ceil(HALF_WIDTH);

        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int dz = -halfWidthInt; dz <= halfWidthInt; dz++) {
                columns.add(new BlockColumn(x, NORTH_WALL_Z + dz));
            }
        }
        for (int z = NORTH_WALL_Z; z <= (int) CENTER_Z; z++) {
            for (int dx = -halfWidthInt; dx <= halfWidthInt; dx++) {
                columns.add(new BlockColumn(MIN_X + dx, z));
                columns.add(new BlockColumn(MAX_X + dx, z));
            }
        }
        for (BlockColumn c : GeometryUtils.thickArcColumns(CENTER_X, CENTER_Z, RADIUS, HALF_WIDTH, 0, 180)) {
            if (c.z() >= CENTER_Z) {
                columns.add(c);
            }
        }
        return columns;
    }

    /** The v1 full-circle footprint, needed only so this migration can demolish it. */
    private Set<BlockColumn> rasterizeLegacyCircleWall() {
        return GeometryUtils.thickArcColumns(CENTER_X, CENTER_Z, RADIUS, HALF_WIDTH, 0, 360);
    }

    private static boolean isInsideAnyGate(int x, int z) {
        return isInsideAnyOf(GATES, x, z);
    }

    private static boolean isInsideAnyV1Gate(int x, int z) {
        return isInsideAnyOf(V1_GATES, x, z);
    }

    private static boolean isInsideAnyOf(double[][] gates, int x, int z) {
        for (double[] gate : gates) {
            if (Math.hypot(x - gate[0], z - gate[1]) < gate[2]) {
                return true;
            }
        }
        return false;
    }

    private static ChunkCoord chunkOf(BlockColumn column) {
        return new ChunkCoord(Math.floorDiv(column.x(), 16), Math.floorDiv(column.z(), 16));
    }

    private static long crestCellKey(int x, int z) {
        return (((long) Math.floorDiv(x, CREST_CELL)) << 32) ^ (Math.floorDiv(z, CREST_CELL) & 0xffffffffL);
    }

    /**
     * Plans and submits affected chunks incrementally (chunksPerTick per tick), never synchronously
     * - the same main-thread-freeze lesson as every other phase here.
     */
    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        Set<BlockColumn> newColumns = new LinkedHashSet<>();
        for (BlockColumn c : rasterizeNewWall()) {
            if (isInsideAnyGate(c.x(), c.z())) {
                continue;
            }
            if (mask.contains(chunkOf(c).x(), chunkOf(c).z())) {
                newColumns.add(c);
            }
        }

        Set<BlockColumn> legacyColumns = new LinkedHashSet<>();
        for (BlockColumn c : rasterizeLegacyCircleWall()) {
            // v1 never built wall inside its own gate openings, so those columns are not ours to
            // clear - see V1_GATES.
            if (isInsideAnyV1Gate(c.x(), c.z())) {
                continue;
            }
            if (mask.contains(chunkOf(c).x(), chunkOf(c).z())) {
                legacyColumns.add(c);
            }
        }

        // Level the crest per 32x32 cell: crest = highest terrain among that cell's wall columns.
        Map<Long, Integer> crestByCell = new HashMap<>();
        for (BlockColumn c : newColumns) {
            long key = crestCellKey(c.x(), c.z());
            int surfaceY = profile.surfaceY(c.x(), c.z());
            crestByCell.merge(key, surfaceY, Math::max);
        }

        Map<ChunkCoord, List<BlockColumn>> buildByChunk = new HashMap<>();
        for (BlockColumn c : newColumns) {
            buildByChunk.computeIfAbsent(chunkOf(c), k -> new ArrayList<>()).add(c);
        }
        Map<ChunkCoord, List<BlockColumn>> demolishByChunk = new HashMap<>();
        for (BlockColumn c : legacyColumns) {
            demolishByChunk.computeIfAbsent(chunkOf(c), k -> new ArrayList<>()).add(c);
        }

        Set<ChunkCoord> affected = new HashSet<>(buildByChunk.keySet());
        affected.addAll(demolishByChunk.keySet());
        List<ChunkCoord> sorted = new ArrayList<>(affected);
        sorted.sort(Comparator.comparingInt(ChunkCoord::x).thenComparingInt(ChunkCoord::z));
        Deque<ChunkCoord> pending = new ArrayDeque<>(sorted);
        int totalToConsider = pending.size();

        logger.info("Town wall v2: " + newColumns.size() + " wall columns, "
                + legacyColumns.size() + " legacy v1 columns to demolish, "
                + totalToConsider + " chunks affected.");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/wall/chunk_" + coord.x() + "_" + coord.z();
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent()) {
                            GenerationLedger.FeatureRecord record = existing.get();
                            String from = record.featureVersion();
                            if (FEATURE_VERSION_V1.equals(from) || FEATURE_VERSION_V2.equals(from)) {
                                if (!ledger.migrateFeatureVersion(featureId, from,
                                        FEATURE_VERSION, System.currentTimeMillis())) {
                                    logger.warning("Refused to migrate " + featureId
                                            + " (owner-protected or already migrated); skipping.");
                                    continue;
                                }
                            } else if (record.state() == GenerationLedger.FeatureState.COMMITTED) {
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
                    scheduler.submit(new WallChunkJob(coord, featureId,
                            buildByChunk.getOrDefault(coord, List.of()),
                            demolishByChunk.getOrDefault(coord, List.of()),
                            crestByCell));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return totalToConsider;
    }

    private final class WallChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> build;
        private final List<BlockColumn> demolish;
        private final Map<Long, Integer> crestByCell;

        WallChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> build,
                      List<BlockColumn> demolish, Map<Long, Integer> crestByCell) {
            this.coord = coord;
            this.featureId = featureId;
            this.build = build;
            this.demolish = demolish;
            this.crestByCell = crestByCell;
        }

        @Override
        public String featureId() {
            return featureId;
        }

        @Override
        public Iterator<BlockEdit> edits() {
            List<BlockEdit> edits = new ArrayList<>();
            Set<BlockColumn> buildSet = new HashSet<>(build);

            // 1. Demolish v1 columns back to bare terrain, skipping any column v2 will rebuild.
            for (BlockColumn c : demolish) {
                if (buildSet.contains(c)) {
                    continue;
                }
                int surfaceY = profile.surfaceY(c.x(), c.z());
                for (int y = surfaceY + 1; y <= surfaceY + 20; y++) {
                    edits.add(new BlockEdit(c.x(), y, c.z(), Material.AIR));
                }
                edits.add(new BlockEdit(c.x(), surfaceY, c.z(), Material.GRASS_BLOCK));
            }

            // 2. Build the v2 wall with a level crest per 32x32 cell.
            for (BlockColumn c : build) {
                int surfaceY = profile.surfaceY(c.x(), c.z());
                int crest = crestByCell.getOrDefault(crestCellKey(c.x(), c.z()), surfaceY)
                        + PARAPET_ABOVE_CREST;
                for (int y = surfaceY; y <= crest; y++) {
                    edits.add(new BlockEdit(c.x(), y, c.z(), Material.STONE_BRICKS));
                }
                // Clear any leftover v1 stone above the new level crest.
                for (int y = crest + 1; y <= crest + 6; y++) {
                    edits.add(new BlockEdit(c.x(), y, c.z(), Material.AIR));
                }
                if ((c.x() + c.z()) % 2 == 0) {
                    edits.add(new BlockEdit(c.x(), crest + 1, c.z(), Material.STONE_BRICK_WALL));
                }
            }
            return edits.iterator();
        }

        @Override
        public void onComplete() {
            try {
                ledger.tryTransition(featureId, GenerationLedger.FeatureState.COMMITTED,
                        hashChunkFeature(coord, build), null, System.currentTimeMillis());
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
            Set<String> sorted = new TreeSet<>();
            for (BlockColumn c : columns) {
                sorted.add(c.x() + "," + c.z());
            }
            for (String s : sorted) {
                input.append(';').append(s);
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
