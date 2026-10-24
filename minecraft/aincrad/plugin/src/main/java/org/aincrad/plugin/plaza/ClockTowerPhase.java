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
 * Central Plaza clock tower and Teleport Gate, built as one structure: an open-ground-floor
 * "Arrival Rotunda" standing over the gate, with the clock/bell tower rising from it.
 *
 * <p><b>Why combined.</b> The spec puts the Teleport Gate "at plaza centre/beneath tower" and the
 * clock tower "centred over or immediately adjacent to" it, and the spawn text has the player
 * materialising "beneath or immediately beside the Arrival Rotunda/Teleport Gate". A tower whose
 * ground floor is an open colonnade over the gate satisfies all three at once.</p>
 *
 * <p><b>Known spec tension, deliberately surfaced rather than silently resolved.</b> The same
 * section also demands a "minimum uninterrupted crowd surface: 140 x 140 blocks". A 23x23 tower at
 * the exact plaza centre cannot coexist with a literally unobstructed 140x140 square centred there.
 * This build treats the rotunda's twelve perimeter piers as a colonnade rather than an obstruction
 * - the paved deck is continuous and walkable straight through in every direction, so crowd flow
 * and evacuation are not blocked - but the strict reading is not met, and the owner may prefer to
 * move the tower off-centre instead. Recorded in docs/ARCHITECTURE-DECISION.md.</p>
 *
 * <p><b>Not built here:</b> gate runtime behaviour (persistence, reservation, item serialisation,
 * destination unlock) is server logic, not geometry, and remains unimplemented. Bell-level interior
 * access is also not built - there is deliberately no ladder or stair advertised, rather than a
 * broken one.</p>
 */
public final class ClockTowerPhase {

    private static final String PHASE_ID = "phase7_clock_tower_and_gate";
    private static final String FEATURE_VERSION = "clock_tower_gate:v1";

    private static final int CX = 0;
    private static final int CZ = 4416;
    private static final int DECK_Y = 322;

    /** 23x23 rather than the spec's 22x22, so the tower is exactly symmetric about the X=0 axis. */
    private static final int ROTUNDA_HALF = 11;
    private static final int SHAFT_HALF = 7;

    private static final int ROTUNDA_TOP = 336;     // open colonnade 323..336
    private static final int CEILING_Y = 337;
    private static final int SHAFT_TOP = 350;
    private static final int CLOCK_BOTTOM = 351;
    private static final int CLOCK_TOP = 359;
    private static final int CLOCK_CENTRE_Y = 355;
    private static final int CLOCK_RADIUS = 4;
    private static final int BELL_BOTTOM = 360;
    private static final int BELL_TOP = 363;
    private static final int APEX_Y = 366;          // 44 blocks above the deck, per spec

    // Teleport Gate: 9 x 7 clear floor, 12 blocks of visual height.
    private static final int GATE_HALF_X = 4;       // 9 wide
    private static final int GATE_HALF_Z = 3;       // 7 deep
    private static final int GATE_TOP = 334;

    private final GenerationLedger ledger;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;

    public ClockTowerPhase(GenerationLedger ledger, MaskResult mask,
                            GenerationScheduler scheduler, Logger logger) {
        this.ledger = ledger;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public int enqueueAllIncrementally(Plugin plugin, int chunksPerTick) {
        Map<ChunkCoord, List<BlockColumn>> byChunk = new HashMap<>();
        for (int x = CX - ROTUNDA_HALF; x <= CX + ROTUNDA_HALF; x++) {
            for (int z = CZ - ROTUNDA_HALF; z <= CZ + ROTUNDA_HALF; z++) {
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
        logger.info("Clock tower + Teleport Gate: " + total + " chunks, apex Y=" + APEX_Y
                + " (" + (APEX_Y - DECK_Y) + " above deck).");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = chunksPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    ChunkCoord coord = pending.poll();
                    budget--;
                    String featureId = "f01/tob/plaza/clocktower/chunk_" + coord.x() + "_" + coord.z();
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
                    scheduler.submit(new TowerChunkJob(coord, featureId, byChunk.get(coord)));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    /** Twelve perimeter piers: four corners plus two per side, leaving 13-block axial openings. */
    private static boolean isRotundaPier(int dx, int dz) {
        int[] anchors = {-10, -8, 8, 10};
        for (int ax : anchors) {
            for (int az : anchors) {
                boolean onRing = Math.abs(ax) == 10 || Math.abs(az) == 10;
                if (onRing && Math.abs(dx - ax) <= 1 && Math.abs(dz - az) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Analog dial, drawn on a vertical plane. {@code u} is the horizontal axis of that face. */
    private static Material clockFaceMaterial(int u, int y) {
        int dy = y - CLOCK_CENTRE_Y;
        double dist = Math.hypot(u, dy);
        if (dist > CLOCK_RADIUS + 1) {
            return null;
        }
        if (dist > CLOCK_RADIUS) {
            return Material.POLISHED_BLACKSTONE;            // bezel
        }
        // Hands frozen at 10:10 - the conventional display position, and readable at distance.
        int[][] hourHand = {{-1, 1}, {-1, 2}};
        int[][] minuteHand = {{1, 1}, {1, 2}, {2, 3}};
        if (u == 0 && dy == 0) {
            return Material.BLACK_CONCRETE;
        }
        for (int[] h : hourHand) {
            if (u == h[0] && dy == h[1]) {
                return Material.BLACK_CONCRETE;
            }
        }
        for (int[] m : minuteHand) {
            if (u == m[0] && dy == m[1]) {
                return Material.BLACK_CONCRETE;
            }
        }
        // Hour markers at 12/3/6/9.
        if ((u == 0 && Math.abs(dy) == CLOCK_RADIUS - 1) || (dy == 0 && Math.abs(u) == CLOCK_RADIUS - 1)) {
            return Material.BLACK_CONCRETE;
        }
        return Material.WHITE_CONCRETE;
    }

    private final class TowerChunkJob implements GenerationScheduler.FeatureJob {
        private final ChunkCoord coord;
        private final String featureId;
        private final List<BlockColumn> columns;

        TowerChunkJob(ChunkCoord coord, String featureId, List<BlockColumn> columns) {
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
                build(edits, c.x(), c.z());
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

    private void build(List<BlockEdit> edits, int x, int z) {
        int dx = x - CX;
        int dz = z - CZ;
        boolean inShaft = Math.abs(dx) <= SHAFT_HALF && Math.abs(dz) <= SHAFT_HALF;
        boolean shaftWall = inShaft && (Math.abs(dx) == SHAFT_HALF || Math.abs(dz) == SHAFT_HALF);

        // --- Teleport Gate, on the plaza deck at the exact arrival point ---
        boolean gateFloor = Math.abs(dx) <= GATE_HALF_X && Math.abs(dz) <= GATE_HALF_Z;
        if (gateFloor) {
            edits.add(new BlockEdit(x, DECK_Y, z, Material.POLISHED_BLACKSTONE));
        }
        boolean gatePost = Math.abs(dx) == GATE_HALF_X + 1 && Math.abs(dz) <= GATE_HALF_Z;
        if (gatePost) {
            for (int y = DECK_Y + 1; y < GATE_TOP; y++) {
                edits.add(new BlockEdit(x, y, z, Material.DEEPSLATE_BRICKS));
            }
            edits.add(new BlockEdit(x, GATE_TOP, z, Material.IRON_BLOCK));
            if (Math.abs(dz) == GATE_HALF_Z) {
                edits.add(new BlockEdit(x, GATE_TOP + 1, z, Material.LANTERN));
            }
        }
        // Lintel across the two open faces, so the gate reads as an archway rather than two posts.
        if (Math.abs(dx) <= GATE_HALF_X && Math.abs(dz) == GATE_HALF_Z + 1) {
            edits.add(new BlockEdit(x, GATE_TOP, z, Material.DEEPSLATE_BRICKS));
        }

        // --- Rotunda: open colonnade, piers only ---
        if (isRotundaPier(dx, dz)) {
            for (int y = DECK_Y + 1; y <= ROTUNDA_TOP; y++) {
                edits.add(new BlockEdit(x, y, z, Material.POLISHED_ANDESITE));
            }
            if (Math.abs(dx) == 10 && Math.abs(dz) == 10) {
                edits.add(new BlockEdit(x, ROTUNDA_TOP + 1, z, Material.LANTERN));
            }
        }

        // --- Rotunda ceiling / tower base slab ---
        edits.add(new BlockEdit(x, CEILING_Y, z, Material.DEEPSLATE_TILES));

        if (!inShaft) {
            return;
        }

        // --- Tower shaft ---
        for (int y = CEILING_Y + 1; y <= SHAFT_TOP; y++) {
            edits.add(new BlockEdit(x, y, z, shaftWall ? Material.POLISHED_ANDESITE : Material.AIR));
        }

        // --- Clock band, with a dial on each of the four faces ---
        for (int y = CLOCK_BOTTOM; y <= CLOCK_TOP; y++) {
            if (!shaftWall) {
                edits.add(new BlockEdit(x, y, z, Material.AIR));
                continue;
            }
            Material face = null;
            if (Math.abs(dz) == SHAFT_HALF && Math.abs(dx) < SHAFT_HALF) {
                face = clockFaceMaterial(dx, y);
            } else if (Math.abs(dx) == SHAFT_HALF && Math.abs(dz) < SHAFT_HALF) {
                face = clockFaceMaterial(dz, y);
            }
            edits.add(new BlockEdit(x, y, z, face != null ? face : Material.POLISHED_ANDESITE));
        }

        // --- Bell level: open arches on all four sides, bell at the centre ---
        for (int y = BELL_BOTTOM; y <= BELL_TOP; y++) {
            boolean cornerPost = Math.abs(dx) >= SHAFT_HALF - 1 && Math.abs(dz) >= SHAFT_HALF - 1;
            if (cornerPost) {
                edits.add(new BlockEdit(x, y, z, Material.POLISHED_ANDESITE));
            } else if (shaftWall && y == BELL_TOP) {
                edits.add(new BlockEdit(x, y, z, Material.POLISHED_ANDESITE));   // arch heads
            } else {
                edits.add(new BlockEdit(x, y, z, Material.AIR));
            }
        }
        if (dx == 0 && dz == 0) {
            edits.add(new BlockEdit(x, BELL_BOTTOM, z, Material.POLISHED_BLACKSTONE));
            edits.add(new BlockEdit(x, BELL_BOTTOM + 1, z, Material.BELL));
        }

        // --- Roof: stepped pyramid to the 44-block apex, glowing crown ---
        for (int y = BELL_TOP + 1; y <= APEX_Y; y++) {
            int inset = y - BELL_TOP;                      // 1..3
            int half = SHAFT_HALF - inset * 2;             // 5, 3, 1
            boolean solid = Math.abs(dx) <= half && Math.abs(dz) <= half;
            if (solid) {
                boolean crown = y == APEX_Y && Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                edits.add(new BlockEdit(x, y, z, crown ? Material.SEA_LANTERN : Material.DEEPSLATE_TILES));
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
