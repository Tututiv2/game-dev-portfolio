package org.aincrad.plugin.district;

import org.aincrad.plugin.generation.BlockEdit;
import org.aincrad.plugin.generation.GenerationScheduler;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.mask.ChunkCoord;
import org.aincrad.plugin.mask.MaskResult;
import org.aincrad.plugin.terrain.TerrainProfile;
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
 * Plans the whole Town parcel graph, then builds one house per parcel. Ledger features are keyed
 * per parcel (not per chunk) because a parcel is the unit the spec cares about - it is what carries
 * a stable id, what the building-count acceptance test counts, and what an owner edit would
 * protect. Parcels are sorted before enqueue so output does not depend on scheduling.
 */
public final class DistrictGenerationPhase {

    private static final String PHASE_ID = "phase9_districts";
    private static final String FEATURE_VERSION = "district_building:v1";

    private record DistrictSpec(String id, int x0, int z0, int x1, int z1,
                                 Parcel.ParcelRole role, int target) {
    }

    /**
     * Targets are the upper bound of each district's own range in
     * FLOOR-01-RECONSTRUCTION-SPEC.md 7.1, which sums to 1,150 - inside the spec's overall
     * 1,100-1,350 parcel-ledger requirement. Planning deliberately over-produces and is then
     * thinned to target by an even stride, so the gaps land as scattered yards and gardens rather
     * than one bald patch at whichever corner the planner happened to finish on.
     */
    private static final List<DistrictSpec> DISTRICTS = List.of(
            new DistrictSpec("market", 110, 4320, 410, 4680, Parcel.ParcelRole.SHOP, 270),
            new DistrictSpec("inncraft", -430, 4280, -110, 4700, Parcel.ParcelRole.WORKSHOP, 230),
            new DistrictSpec("church", -380, 4680, -70, 4910, Parcel.ParcelRole.HOME, 110),
            new DistrictSpec("residential", 50, 4660, 410, 4920, Parcel.ParcelRole.HOME, 360),
            new DistrictSpec("northgate", -420, 4000, 420, 4320, Parcel.ParcelRole.SHOP, 180)
    );

    private final GenerationLedger ledger;
    private final TerrainProfile profile;
    private final MaskResult mask;
    private final GenerationScheduler scheduler;
    private final Logger logger;
    private final long worldSeed;

    private List<Parcel> lastPlan = List.of();
    private int lastTwoStorey;
    private int lastCellar;

    public DistrictGenerationPhase(GenerationLedger ledger, TerrainProfile profile, MaskResult mask,
                                    GenerationScheduler scheduler, Logger logger, long worldSeed) {
        this.ledger = ledger;
        this.profile = profile;
        this.mask = mask;
        this.scheduler = scheduler;
        this.logger = logger;
        this.worldSeed = worldSeed;
    }

    /** Plans every district and resolves overlaps into a final non-overlapping parcel ledger. */
    public List<Parcel> plan() {
        List<Parcel> all = new ArrayList<>();
        for (DistrictSpec d : DISTRICTS) {
            List<Parcel> planned = DistrictPlanner.plan(d.id(), d.x0(), d.z0(), d.x1(), d.z1(),
                    worldSeed, d.role());
            List<Parcel> kept = new ArrayList<>();
            for (Parcel p : planned) {
                // Whole parcel must sit in mask-authorized chunks.
                boolean inMask = true;
                for (int cx = Math.floorDiv(p.minX(), 16); cx <= Math.floorDiv(p.maxX(), 16) && inMask; cx++) {
                    for (int cz = Math.floorDiv(p.minZ(), 16); cz <= Math.floorDiv(p.maxZ(), 16); cz++) {
                        if (!mask.contains(cx, cz)) {
                            inMask = false;
                            break;
                        }
                    }
                }
                if (!inMask) {
                    continue;
                }
                boolean clash = false;
                for (Parcel q : all) {
                    if (p.overlaps(q)) {
                        clash = true;
                        break;
                    }
                }
                if (!clash) {
                    for (Parcel q : kept) {
                        if (p.overlaps(q)) {
                            clash = true;
                            break;
                        }
                    }
                }
                if (!clash) {
                    kept.add(p);
                }
            }
            all.addAll(thinToTarget(kept, d.target()));
        }
        all.sort(Comparator.comparing(Parcel::id));
        lastPlan = all;
        return all;
    }

    /**
     * Deterministically reduce an over-produced district to its spec target by keeping an even
     * stride through the list. Dropped plots simply stay unbuilt - they read as yards, gardens and
     * side courts, which is what a real town has between terraces anyway.
     */
    private static List<Parcel> thinToTarget(List<Parcel> planned, int target) {
        if (planned.size() <= target) {
            return planned;
        }
        List<Parcel> out = new ArrayList<>(target);
        double stride = planned.size() / (double) target;
        for (int i = 0; i < target; i++) {
            out.add(planned.get(Math.min(planned.size() - 1, (int) Math.floor(i * stride))));
        }
        return out;
    }

    /** District rectangles and ids, for the street-paving phase. */
    public List<int[]> districtBounds() {
        List<int[]> out = new ArrayList<>();
        for (DistrictSpec d : DISTRICTS) {
            out.add(new int[]{d.x0(), d.z0(), d.x1(), d.z1()});
        }
        return out;
    }

    public List<String> districtIds() {
        List<String> out = new ArrayList<>();
        for (DistrictSpec d : DISTRICTS) {
            out.add(d.id());
        }
        return out;
    }

    public TerrainProfile terrainProfile() {
        return profile;
    }

    public long worldSeed() {
        return worldSeed;
    }

    public List<Parcel> lastPlan() {
        return lastPlan;
    }

    public int lastTwoStorey() {
        return lastTwoStorey;
    }

    public int lastCellar() {
        return lastCellar;
    }

    public int enqueueAllIncrementally(Plugin plugin, int parcelsPerTick) {
        List<Parcel> parcels = plan();
        Deque<Parcel> pending = new ArrayDeque<>(parcels);
        int total = pending.size();
        lastTwoStorey = 0;
        lastCellar = 0;
        logger.info("Districts: planned " + total + " non-overlapping parcels across "
                + DISTRICTS.size() + " districts.");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = parcelsPerTick;
                while (budget > 0 && !pending.isEmpty()) {
                    Parcel p = pending.poll();
                    budget--;
                    try {
                        Optional<GenerationLedger.FeatureRecord> existing = ledger.getFeature(p.id());
                        if (existing.isPresent()) {
                            if (existing.get().state() == GenerationLedger.FeatureState.COMMITTED) {
                                continue;
                            }
                        } else {
                            Set<int[]> chunks = new java.util.HashSet<>();
                            for (int cx = Math.floorDiv(p.minX(), 16); cx <= Math.floorDiv(p.maxX(), 16); cx++) {
                                for (int cz = Math.floorDiv(p.minZ(), 16); cz <= Math.floorDiv(p.maxZ(), 16); cz++) {
                                    chunks.add(new int[]{cx, cz});
                                }
                            }
                            ledger.planFeature(p.id(), FEATURE_VERSION, PHASE_ID, chunks,
                                    System.currentTimeMillis());
                        }
                    } catch (SQLException e) {
                        logger.log(Level.SEVERE, "Ledger error planning " + p.id(), e);
                        continue;
                    }
                    scheduler.submit(new ParcelJob(p));
                }
                if (pending.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return total;
    }

    private final class ParcelJob implements GenerationScheduler.FeatureJob {
        private final Parcel parcel;

        ParcelJob(Parcel parcel) {
            this.parcel = parcel;
        }

        @Override
        public String featureId() {
            return parcel.id();
        }

        @Override
        public Iterator<BlockEdit> edits() {
            List<BlockEdit> edits = new ArrayList<>();
            BuildingBuilder.Stats stats = BuildingBuilder.build(edits, parcel, profile, worldSeed);
            if (stats.twoStorey()) {
                lastTwoStorey++;
            }
            if (stats.cellar()) {
                lastCellar++;
            }
            return edits.iterator();
        }

        @Override
        public void onComplete() {
            try {
                ledger.tryTransition(parcel.id(), GenerationLedger.FeatureState.COMMITTED,
                        hash(parcel), null, System.currentTimeMillis());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Ledger error committing " + parcel.id(), e);
            }
        }
    }

    private String hash(Parcel p) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((FEATURE_VERSION + ":" + p.id() + ":" + p.minX() + "," + p.minZ()
                    + "," + p.maxX() + "," + p.maxZ() + ":" + p.variant())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChunkCoord chunkOf(int x, int z) {
        return new ChunkCoord(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }
}
