package org.aincrad.plugin.palace;

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
 * Black Iron Palace interiors: every space the spec names, laid out as a real circulation graph
 * rather than decorated voids.
 *
 * <pre>
 *   N   [ sealed          ][  Prison-processing hall  ][ offices E1 ]   <- Z 4069..4090
 *       [ resurrection    ][                          ][ offices E2 ]   <- Z 4092..4105
 *       ------------------- maintenance cross corridor -------------
 *                     (  Monument Hall rotunda, open to the dome  )
 *       ------------------- maintenance cross corridor -------------
 *       [ west gallery ][      processional nave      ][ east gallery ]
 *   S                    ^ grand entrance archway
 * </pre>
 *
 * <p>Circulation is a connected graph from the south archway: perimeter corridor -> nave ->
 * galleries and cross corridors -> Monument Hall -> processing hall -> offices. The resurrection
 * chamber is the single deliberate exception: it is sealed behind solid iron with no door at all,
 * because the spec calls for a "locked resurrection-chamber reference", and an advertised door
 * that opened onto nothing would violate the project's own no-fake-doors rule.</p>
 *
 * <p>The Monument Hall rotunda has no ceiling of its own - it opens the full height into the drum
 * and dome built by {@link PalaceExteriorPhase}, roughly 58 blocks from floor to apex. Every other
 * room is capped at {@link #ROOM_CEIL_Y} so they read as rooms rather than slices of one hangar.</p>
 */
public final class PalaceInteriorPhase {

    private static final String PHASE_ID = "phase10_palace_interior";
    private static final String FEATURE_VERSION_V1 = "palace_interior:v1";
    private static final String FEATURE_VERSION = "palace_interior:v2";

    private static final int FLOOR_Y = 328;
    private static final int ROOM_CEIL_Y = 345;
    private static final int ARCH_TOP = 344;

    // Hall interior void (walls sit outside these bounds).
    private static final int HALL_MIN_X = -109, HALL_MAX_X = 109;
    private static final int HALL_MIN_Z = 4063, HALL_MAX_Z = 4209;
    // Inner partition rectangle; the gap to the hall wall is the maintenance corridor.
    private static final int IN_MIN_X = -104, IN_MAX_X = 104;
    private static final int IN_MIN_Z = 4068, IN_MAX_Z = 4204;

    /**
     * Monument Hall: the spec's "60 x 46 central chamber". v1 modelled this as a circle of radius
     * 60 centred on the dome, which was wrong twice over - the spec asks for a rectangle, and a
     * 60-radius circle spans Z 4076..4196, bleeding straight through the cross-corridor walls into
     * the processional nave and the prison-processing hall. Because the "inside the rotunda" test
     * suppressed partitions and ceilings, that silently deleted the nave's side walls and its
     * ceiling (caught by probes at (17,330,4190) and (0,345,4190) in boot-test17.log). v2 uses the
     * rectangle the spec actually specifies, sized to sit exactly inside the central band.
     */
    private static final int MON_MIN_X = -30, MON_MAX_X = 30;
    private static final int MON_MIN_Z = 4113, MON_MAX_Z = 4159;
    private static final int WALL_MON_X = 31;
    private static final int ROT_CX = 0, ROT_CZ = 4136;

    private static final int WALL_N_ROOMS = 4106;   // processing hall <-> north cross corridor
    private static final int WALL_CROSS_N = 4112;   // north cross corridor <-> rotunda band
    private static final int WALL_CROSS_S = 4160;   // rotunda band <-> south cross corridor
    private static final int WALL_S_ROOMS = 4166;   // south cross corridor <-> nave band
    private static final int WALL_PROC_X = 63;      // processing hall <-> flanking rooms
    private static final int WALL_NAVE_X = 17;      // nave <-> galleries
    private static final int WALL_SIDE_SPLIT_Z = 4091;

    private static final int DOOR_HALF = 4;         // 9-wide ceremonial openings
    private static final int DOOR_TOP = FLOOR_Y + 6;

    // Underground sealed water-access seam running toward the hidden-dungeon intake (144,315,4024).
    private static final int SEAM_Y = 315, SEAM_TOP = 319;
    private static final int SEAM_TRUNK_Z0 = 4053, SEAM_TRUNK_Z1 = 4057;
    private static final int SEAM_BRANCH_X0 = 142, SEAM_BRANCH_X1 = 146;
    private static final int SEAM_BRANCH_Z_END = 4024;
    private static final int SHAFT_X0 = -1, SHAFT_X1 = 1, SHAFT_Z0 = 4065, SHAFT_Z1 = 4067;

    private final GenerationLedger ledger;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public PalaceInteriorPhase(GenerationLedger ledger, MaskResult mask,
                                GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        addRegion(byChunk, HALL_MIN_X, HALL_MAX_X, HALL_MIN_Z, HALL_MAX_Z + 3);
        addRegion(byChunk, 0, SEAM_BRANCH_X1, SEAM_TRUNK_Z0 - 1, SEAM_TRUNK_Z1 + 1);
        addRegion(byChunk, SEAM_BRANCH_X0 - 1, SEAM_BRANCH_X1 + 1, SEAM_BRANCH_Z_END - 1, SEAM_TRUNK_Z1);

        List<ChunkCoord> sorted = new ArrayList<>(byChunk.keySet());
        sorted.sort(Comparator.comparingInt(ChunkCoord::x).thenComparingInt(ChunkCoord::z));
        Deque<ChunkCoord> pending = new ArrayDeque<>(sorted);
        int total = pending.size();
        logger.info("Palace interiors: " + total + " chunks (hall + underground water seam).");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/palace/interior/chunk_" + coord.x() + "_" + coord.z();
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent()) {
                            GenerationLedger.FeatureRecord record = existing.get();
                            if (FEATURE_VERSION_V1.equals(record.featureVersion())) {
                                // v1 -> v2: Monument Hall corrected from an oversized circle to the
                                // spec's 60x46 rectangle, restoring the nave walls and ceiling it
                                // had been silently eating.
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
                            ledger.planFeature(featureId, FEATURE_VERSION, PHASE_ID,
                                    Set.of(new int[]{coord.x(), coord.z()}), System.currentTimeMillis());
                        }
                    } catch (SQLException e) {
                        logger.log(Level.SEVERE, "Ledger error planning " + featureId, e);
                        continue;
                    }
                    scheduler.submit(new InteriorChunkJob(coord, featureId, byChunk.get(coord)));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    private void addRegion(Map<ChunkCoord, List<BlockColumn>> byChunk, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
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

    private static boolean inRotunda(int x, int z) {
        return x >= MON_MIN_X && x <= MON_MAX_X && z >= MON_MIN_Z && z <= MON_MAX_Z;
    }

    /** The sealed resurrection chamber - west of the processing hall, north half. */
    private static boolean inSealedChamber(int x, int z) {
        return x >= IN_MIN_X && x <= -(WALL_PROC_X + 1)
                && z >= IN_MIN_Z + 1 && z < WALL_SIDE_SPLIT_Z;
    }

    private static boolean isDoorway(int x, int z) {
        boolean axial = Math.abs(x) <= DOOR_HALF;
        if (axial && (z == IN_MAX_Z || z == WALL_S_ROOMS || z == WALL_CROSS_S
                || z == WALL_CROSS_N || z == WALL_N_ROOMS)) {
            return true;
        }
        // Nave <-> galleries.
        if (Math.abs(x) == WALL_NAVE_X && z >= 4180 && z <= 4184) {
            return true;
        }
        // Galleries <-> perimeter maintenance corridor.
        if (Math.abs(x) == IN_MAX_X && z >= 4180 && z <= 4184) {
            return true;
        }
        // Processing hall <-> east offices (two rooms). West side is sealed on purpose.
        if (x == WALL_PROC_X && ((z >= 4075 && z <= 4079) || (z >= 4095 && z <= 4099))) {
            return true;
        }
        if (x == -WALL_PROC_X && z >= 4095 && z <= 4099) {
            return true;
        }
        // East offices split.
        if (z == WALL_SIDE_SPLIT_Z && x >= 70 && x <= 74) {
            return true;
        }
        // Monument Hall <-> side galleries.
        if (Math.abs(x) == WALL_MON_X && z >= ROT_CZ - DOOR_HALF && z <= ROT_CZ + DOOR_HALF) {
            return true;
        }
        return false;
    }

    private static boolean isPartitionWall(int x, int z) {
        boolean insideInner = x >= IN_MIN_X && x <= IN_MAX_X && z >= IN_MIN_Z && z <= IN_MAX_Z;
        if (!insideInner) {
            return false;
        }
        if (inRotunda(x, z)) {
            return false;
        }
        // Inner perimeter rectangle.
        if (x == IN_MIN_X || x == IN_MAX_X || z == IN_MIN_Z || z == IN_MAX_Z) {
            return true;
        }
        // Cross-corridor walls.
        if (z == WALL_N_ROOMS || z == WALL_CROSS_N || z == WALL_CROSS_S || z == WALL_S_ROOMS) {
            return true;
        }
        // Processing hall flanks.
        if (Math.abs(x) == WALL_PROC_X && z > IN_MIN_Z && z < WALL_N_ROOMS) {
            return true;
        }
        // Side-room split.
        if (z == WALL_SIDE_SPLIT_Z && Math.abs(x) > WALL_PROC_X) {
            return true;
        }
        // Nave flanks.
        if (Math.abs(x) == WALL_NAVE_X && z > WALL_S_ROOMS && z < IN_MAX_Z) {
            return true;
        }
        // Monument Hall <-> side galleries.
        if (Math.abs(x) == WALL_MON_X && z > WALL_CROSS_N && z < WALL_CROSS_S) {
            return true;
        }
        return false;
    }

    private final class InteriorChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        InteriorChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
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
                buildHall(edits, c.x(), c.z());
                buildSeam(edits, c.x(), c.z());
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

    private void buildHall(List<BlockEdit> edits, int x, int z) {
        // Grand entrance archway carved through the exterior south wall.
        if (Math.abs(x) <= 8 && z >= 4210 && z <= 4212) {
            for (int y = FLOOR_Y + 1; y <= ARCH_TOP; y++) {
                edits.add(new BlockEdit(x, y, z, Material.AIR));
            }
            edits.add(new BlockEdit(x, FLOOR_Y, z, Material.POLISHED_BLACKSTONE));
            return;
        }
        if (x < HALL_MIN_X || x > HALL_MAX_X || z < HALL_MIN_Z || z > HALL_MAX_Z) {
            return;
        }

        boolean rotunda = inRotunda(x, z);

        // --- Floor ---
        Material floor;
        if (rotunda) {
            double r = Math.hypot(x - ROT_CX, z - ROT_CZ);
            int band = ((int) (r / 8)) % 2;
            floor = band == 0 ? Material.POLISHED_BLACKSTONE : Material.DEEPSLATE_TILES;
            if (r <= 6) {
                floor = Material.CHISELED_POLISHED_BLACKSTONE;   // the Monument itself
            }
        } else {
            floor = Material.DEEPSLATE_TILES;
        }
        edits.add(new BlockEdit(x, FLOOR_Y, z, floor));

        // --- Partitions and doorways ---
        if (isPartitionWall(x, z)) {
            boolean sealedFace = inSealedChamber(x - 1, z) || inSealedChamber(x + 1, z)
                    || inSealedChamber(x, z - 1) || inSealedChamber(x, z + 1);
            Material wall = sealedFace ? Material.IRON_BLOCK : Material.POLISHED_BLACKSTONE_BRICKS;
            boolean door = isDoorway(x, z);
            for (int y = FLOOR_Y + 1; y < ROOM_CEIL_Y; y++) {
                if (door && y <= DOOR_TOP) {
                    edits.add(new BlockEdit(x, y, z, Material.AIR));
                } else {
                    edits.add(new BlockEdit(x, y, z, wall));
                }
            }
        }

        // --- Ceiling: rooms are capped; the rotunda stays open to the dome ---
        if (!rotunda) {
            boolean coffer = Math.floorMod(x, 12) == 0 && Math.floorMod(z, 12) == 0;
            edits.add(new BlockEdit(x, ROOM_CEIL_Y, z,
                    coffer ? Material.SEA_LANTERN : Material.DEEPSLATE_TILES));
            for (int y = ROOM_CEIL_Y + 1; y <= 353; y++) {
                edits.add(new BlockEdit(x, y, z, Material.AIR));
            }
        }

        // --- Nave colonnade ---
        boolean naveCol = Math.abs(x) == 12 && z > WALL_S_ROOMS && z < IN_MAX_Z
                && Math.floorMod(z, 8) == 0;
        if (naveCol) {
            for (int y = FLOOR_Y + 1; y <= ROOM_CEIL_Y - 1; y++) {
                edits.add(new BlockEdit(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS));
            }
            edits.add(new BlockEdit(x, FLOOR_Y + 5, z, Material.LANTERN));
        }

        // --- Monument Hall colonnade: piers set just inside its perimeter ---
        if (rotunda) {
            boolean onPierLine = (Math.abs(x) == MON_MAX_X - 3 && z > MON_MIN_Z + 2 && z < MON_MAX_Z - 2)
                    || ((z == MON_MIN_Z + 3 || z == MON_MAX_Z - 3) && Math.abs(x) < MON_MAX_X - 3);
            boolean spaced = Math.floorMod(x, 10) == 0 && Math.floorMod(z - MON_MIN_Z, 10) == 0;
            if (onPierLine && (Math.floorMod(x, 10) == 0 || Math.floorMod(z - MON_MIN_Z, 10) == 0) && !spaced) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 18; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS));
                }
                edits.add(new BlockEdit(x, FLOOR_Y + 19, z, Material.SEA_LANTERN));
            }
        }

        // --- Prison-processing hall: a raised dais and counter line, ready for later systems ---
        boolean procHall = Math.abs(x) < WALL_PROC_X && z > IN_MIN_Z && z < WALL_N_ROOMS;
        if (procHall && z >= 4072 && z <= 4074 && Math.abs(x) <= 40) {
            edits.add(new BlockEdit(x, FLOOR_Y + 1, z, Material.POLISHED_BLACKSTONE));
            if (z == 4074) {
                edits.add(new BlockEdit(x, FLOOR_Y + 2, z, Material.POLISHED_BLACKSTONE_SLAB));
            }
        }
        if (procHall && z == 4082 && Math.floorMod(x + 60, 10) == 0 && Math.abs(x) <= 50) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                edits.add(new BlockEdit(x, y, z, Material.IRON_BARS));
            }
        }

        // --- Maintenance shaft down to the water seam, in the perimeter corridor ---
        if (x >= SHAFT_X0 && x <= SHAFT_X1 && z >= SHAFT_Z0 && z <= SHAFT_Z1) {
            for (int y = SEAM_TOP; y < FLOOR_Y; y++) {
                edits.add(new BlockEdit(x, y, z, Material.AIR));
            }
            edits.add(new BlockEdit(x, FLOOR_Y, z, Material.AIR));   // open the floor
            if (x == 0 && z == SHAFT_Z0) {
                for (int y = SEAM_Y + 1; y <= FLOOR_Y; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.LADDER,
                            "minecraft:ladder[facing=south]"));
                }
            }
        }
    }

    /** Sealed maintenance/water-access seam toward the hidden-dungeon intake at (144,315,4024). */
    private void buildSeam(List<BlockEdit> edits, int x, int z) {
        boolean trunk = x >= 0 && x <= SEAM_BRANCH_X1 && z >= SEAM_TRUNK_Z0 && z <= SEAM_TRUNK_Z1;
        boolean branch = x >= SEAM_BRANCH_X0 && x <= SEAM_BRANCH_X1
                && z >= SEAM_BRANCH_Z_END && z <= SEAM_TRUNK_Z1;
        if (!trunk && !branch) {
            return;
        }
        for (int y = SEAM_Y; y <= SEAM_TOP; y++) {
            boolean shell = y == SEAM_Y || y == SEAM_TOP;
            edits.add(new BlockEdit(x, y, z, shell ? Material.DEEPSLATE_BRICKS : Material.AIR));
        }
        // Sealed conduit run + lighting along one side. Deliberately a sealed pipe, not flowing
        // water: the spec forbids any leak into the shell below.
        boolean condSide = (trunk && z == SEAM_TRUNK_Z0) || (branch && x == SEAM_BRANCH_X0);
        if (condSide) {
            edits.add(new BlockEdit(x, SEAM_Y + 1, z, Material.CUT_COPPER));
            if (Math.floorMod(x + z, 12) == 0) {
                edits.add(new BlockEdit(x, SEAM_Y + 3, z, Material.LANTERN));
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
