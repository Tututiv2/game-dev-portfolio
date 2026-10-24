package org.aincrad.plugin.palace;

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
 * Black Iron Palace - exterior massing (podium, ceremonial stair, colonnade, main hall shell,
 * drum, roof terrace, dome). Interiors (Monument Hall, processional nave, side galleries,
 * prison-processing hall, offices, sealed resurrection-chamber reference, maintenance corridors,
 * underground water seam) are deliberately NOT built here - the hall is left hollow so a later
 * interiors phase can fill it without demolishing anything.
 *
 * <p><b>Locked inputs</b> (layout JSON): position {@code (0,324,4136)}, podium {@code 328 x 170},
 * main hall {@code 224 x 152}, drum radius {@code 84}, dome apex {@code Y=386}, palette
 * blackstone/deepslate/iron/tinted glass.</p>
 *
 * <p><b>Derived massing decision.</b> A true hemisphere over the locked 84-block drum radius would
 * apex at {@code Y=452}, which would punch through Floor Two's structural plate at {@code Y=420}.
 * The locked {@code Y=386} apex therefore forces a shallow crown. Rather than stretch a
 * 1:5-proportioned saucer across the whole drum, this build puts a properly-proportioned dome of
 * radius 48 (rise 20, about 1:2.4) at the centre and treats the drum's outer ring
 * ({@code r=48..84}) as a flat roof terrace. That honours every locked number, keeps 34 blocks of
 * clearance under the plate, and still reads as a dome rather than a lid.</p>
 */
public final class PalaceExteriorPhase {

    private static final String PHASE_ID = "phase8_palace_exterior";
    private static final String FEATURE_VERSION_V1 = "palace_exterior:v1";
    private static final String FEATURE_VERSION = "palace_exterior:v2";

    private static final int CX = 0;
    private static final int CZ = 4136;

    private static final int PODIUM_HALF_W = 164;   // 328 wide
    private static final int PODIUM_HALF_D = 85;    // 170 deep
    private static final int HALL_HALF_W = 112;     // 224 wide
    private static final int HALL_HALF_D = 76;      // 152 deep
    private static final int DRUM_R = 84;
    private static final int DOME_R = 48;

    private static final int DECK_Y = 328;          // podium deck (4-7 above surrounding grade)
    private static final int HALL_TOP_Y = 354;
    private static final int DRUM_TOP_Y = 366;
    private static final int APEX_Y = 386;
    private static final int DOME_RISE = APEX_Y - DRUM_TOP_Y;

    private static final int WALL_THICKNESS = 3;
    private static final int STAIR_HALF_W = 20;     // broad ceremonial stair on the plaza side
    private static final int STAIR_RUN = 6;         // 6 steps x 1-block risers: 328 -> 322

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public PalaceExteriorPhase(GenerationLedger ledger, TerrainProfile profile, MaskResult mask,
                                GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.profile = profile;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    private static int domeSurfaceY(double radius) {
        if (radius > DOME_R) {
            return -1;
        }
        double frac = radius / DOME_R;
        return DRUM_TOP_Y + (int) Math.round(DOME_RISE * Math.sqrt(Math.max(0.0, 1.0 - frac * frac)));
    }

    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        // Footprint = podium, plus the southern stair run that spills past its edge.
        int minX = CX - PODIUM_HALF_W;
        int maxX = CX + PODIUM_HALF_W;
        int minZ = CZ - PODIUM_HALF_D;
        int maxZ = CZ + PODIUM_HALF_D + 12;

        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
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
        logger.info("Black Iron Palace exterior: " + total + " chunks in footprint.");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/palace/chunk_" + coord.x() + "_" + coord.z();
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(featureId);
                        if (existing.isPresent()) {
                            GenerationLedger.FeatureRecord record = existing.get();
                            if (FEATURE_VERSION_V1.equals(record.featureVersion())) {
                                // v1 -> v2: rebuild the ceremonial stair with walkable 1-block risers.
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
                    scheduler.submit(new PalaceChunkJob(coord, featureId, byChunk.get(coord)));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    private final class PalaceChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        PalaceChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
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
                buildColumn(edits, c.x(), c.z());
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

    private void buildColumn(List<BlockEdit> edits, int x, int z) {
        int dx = x - CX;
        int dz = z - CZ;
        int terrain = profile.surfaceY(x, z);
        boolean onPodium = Math.abs(dx) <= PODIUM_HALF_W && Math.abs(dz) <= PODIUM_HALF_D;

        // --- Ceremonial stair, south (plaza-facing) side, outside the podium ---
        // v1 used 2-block risers, which a player cannot walk up without jumping - a direct
        // violation of the spec's "must be able to reach every required service without parkour".
        // v2 uses 1-block risers over a 6-step run, landing exactly on the processional avenue /
        // plaza deck level (Y=322).
        if (!onPodium && Math.abs(dx) <= STAIR_HALF_W
                && dz > PODIUM_HALF_D && dz <= PODIUM_HALF_D + STAIR_RUN) {
            int step = dz - PODIUM_HALF_D;              // 1..6
            int stepY = DECK_Y - step;                  // 327..322
            for (int y = Math.min(terrain, stepY); y <= stepY; y++) {
                edits.add(new BlockEdit(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS));
            }
            for (int y = stepY + 1; y <= Math.max(terrain, stepY) + 4; y++) {
                edits.add(new BlockEdit(x, y, z, Material.AIR));
            }
            return;
        }
        if (!onPodium) {
            return;
        }

        // --- Podium: cut down to the deck, fill up to it ---
        for (int y = DECK_Y + 1; y <= Math.max(terrain, DECK_Y) + 4; y++) {
            edits.add(new BlockEdit(x, y, z, Material.AIR));
        }
        boolean podiumEdge = Math.abs(dx) >= PODIUM_HALF_W - 1 || Math.abs(dz) >= PODIUM_HALF_D - 1;
        for (int y = Math.min(terrain, DECK_Y - 1); y < DECK_Y; y++) {
            edits.add(new BlockEdit(x, y, z,
                    podiumEdge ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_BRICKS));
        }
        edits.add(new BlockEdit(x, DECK_Y, z, Material.POLISHED_BLACKSTONE_BRICKS));

        double radius = Math.hypot(dx, dz);
        boolean inHall = Math.abs(dx) <= HALL_HALF_W && Math.abs(dz) <= HALL_HALF_D;
        boolean hallWall = inHall
                && (Math.abs(dx) > HALL_HALF_W - WALL_THICKNESS || Math.abs(dz) > HALL_HALF_D - WALL_THICKNESS);

        // --- Colonnade on the southern terrace, between hall and podium edge ---
        if (!inHall && dz >= HALL_HALF_D + 4 && dz <= HALL_HALF_D + 5
                && Math.floorMod(dx, 10) <= 1 && Math.abs(dx) <= HALL_HALF_W) {
            for (int y = DECK_Y + 1; y <= DECK_Y + 12; y++) {
                edits.add(new BlockEdit(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS));
            }
            edits.add(new BlockEdit(x, DECK_Y + 13, z, Material.DEEPSLATE_TILES));
        }

        // --- Main hall shell ---
        if (hallWall) {
            for (int y = DECK_Y + 1; y <= HALL_TOP_Y; y++) {
                boolean windowBand = (y >= DECK_Y + 8 && y <= DECK_Y + 18) && Math.floorMod(dx + dz, 9) < 3;
                edits.add(new BlockEdit(x, y, z,
                        windowBand ? Material.TINTED_GLASS : Material.POLISHED_BLACKSTONE_BRICKS));
            }
        } else if (inHall) {
            for (int y = DECK_Y + 1; y <= HALL_TOP_Y; y++) {
                edits.add(new BlockEdit(x, y, z, Material.AIR));   // hollow, for a later interiors phase
            }
            if (radius > DRUM_R) {
                edits.add(new BlockEdit(x, HALL_TOP_Y, z, Material.DEEPSLATE_TILES)); // hall roof
            }
        }

        // --- Drum, roof terrace, dome ---
        if (radius <= DRUM_R) {
            boolean drumWall = radius > DRUM_R - WALL_THICKNESS;
            if (drumWall) {
                for (int y = HALL_TOP_Y + 1; y <= DRUM_TOP_Y; y++) {
                    boolean glassBand = y >= HALL_TOP_Y + 4 && y <= HALL_TOP_Y + 8
                            && Math.floorMod(dx + dz, 7) < 2;
                    edits.add(new BlockEdit(x, y, z,
                            glassBand ? Material.TINTED_GLASS : Material.POLISHED_BLACKSTONE_BRICKS));
                }
            } else {
                for (int y = HALL_TOP_Y + 1; y <= DRUM_TOP_Y; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.AIR));
                }
                if (radius > DOME_R) {
                    edits.add(new BlockEdit(x, DRUM_TOP_Y, z, Material.DEEPSLATE_TILES)); // roof terrace
                }
            }
            if (radius <= DOME_R) {
                // Close the shell against the next ring out so a steep slope leaves no gaps.
                int here = domeSurfaceY(radius);
                int outer = domeSurfaceY(Math.min(radius + 1.0, DOME_R));
                for (int y = Math.min(outer, here); y <= here; y++) {
                    edits.add(new BlockEdit(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS));
                }
                if (radius <= 2.0) {
                    edits.add(new BlockEdit(x, here + 1, z, Material.IRON_BLOCK)); // restrained crown
                }
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
