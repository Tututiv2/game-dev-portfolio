package com.thestill.mine;

import com.thestill.core.Service;
import com.thestill.rank.RankService;
import com.thestill.region.Cuboid;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.region.RegionType;
import com.thestill.descent.DescentService;
import org.bukkit.World;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the Depths, and re-attaches their ore tables on every boot.
 *
 * <p>The split matters. A region's <em>definition</em> is persisted — it is a row
 * in the database with explicit coordinates, created once. Its <em>ore table</em>
 * is code, rebuilt from {@link DepthPlan} at startup. So retuning an ore mix is
 * an edit and a restart, and it never moves a mine that already exists.
 */
public final class DepthSetup implements Service {

    private final Logger log;
    private final RegionService regions;
    private final MineService mines;
    private final DescentService descent;

    public DepthSetup(Logger log, RegionService regions, MineService mines, DescentService descent) {
        this.log = log;
        this.regions = regions;
        this.mines = mines;
        this.descent = descent;
    }

    @Override
    public void start() {
        int attached = attachOreTables();
        log.info("attached ore tables to " + attached + " depth(s)");
        // Mines eject to their own entrance rather than to spawn, so a refill
        // moves you a few blocks instead of across the world.
        mines.setEjectTarget(region -> descent.entryPointOf(region));
    }

    /**
     * Re-registers an ore table for every persisted ore body.
     *
     * <p>Which is <em>not</em> the same set as "every depth". A mine is all ore, so
     * the depth region is the ore body. A plains is mostly landscape with a quarry
     * cut into it, so its ore body is the nested pit and the depth around it holds
     * no table at all — a plains that refilled as a whole would erase its own
     * ground every forty minutes.
     *
     * <p>Having an ore table is therefore the single fact that says "blocks here
     * come back", which is why {@code MineListener} keys mining and building on it
     * rather than on a region type.
     */
    public int attachOreTables() {
        int attached = 0;
        for (Region region : regions.index().all()) {
            if (region.type() != RegionType.DEPTH) {
                continue;
            }
            int number = region.state().get(RankService.DEPTH_NUMBER);
            if (number <= 0) {
                log.warning("region " + region.id() + " is a depth but has no depth.number; "
                        + "it will not refill");
                continue;
            }
            String oreBodyId = DepthPlan.isPlains(number)
                    ? DepthPlan.pitIdFor(number) : region.id();
            if (regions.index().byId(oreBodyId) == null) {
                log.warning("depth " + region.id() + " expects an ore body at '" + oreBodyId
                        + "' and there is none; run /still setup depths to create it");
                continue;
            }
            mines.setOreTable(oreBodyId, DepthPlan.tableFor(number));
            attached++;
        }
        return attached;
    }

    /**
     * Creates any missing depths, up to {@code count}.
     *
     * <p>Idempotent: a depth that already exists is left exactly as it is,
     * coordinates included. Re-running this after changing {@link DepthPlan}'s
     * geometry will <em>not</em> move existing mines, which is the intended
     * behaviour — the world players have been digging in should not move because
     * a constant changed.
     *
     * @return how many were created
     */
    public Result createDepths(World world, int count) {
        int created = 0;
        int skipped = 0;
        StringBuilder failures = new StringBuilder();

        for (int depth = 1; depth <= count; depth++) {
            String id = DepthPlan.regionIdFor(depth);
            try {
                if (regions.index().byId(id) == null) {
                    Cuboid bounds = DepthPlan.boundsFor(world.getName(), depth);
                    Region region = regions.define(id, RegionType.DEPTH, null, bounds);
                    region.state().set(RankService.DEPTH_NUMBER, depth);
                    region.state().set(RankService.REQUIRED_RANK,
                            DepthPlan.requiredRankFor(depth));
                    region.state().set(MineService.KIND,
                            DepthPlan.kindOf(depth).name().toLowerCase());
                    descent.setEntryPoint(region, DepthPlan.entryFor(world, depth));
                    // A mine IS its own ore body. Only a plains hands its table to
                    // a child, and forgetting this branch leaves twenty depths that
                    // are regions with rank gates and nothing to dig — they never
                    // appear in /still mine status and never fill, which reads as
                    // an empty world rather than as an error.
                    if (!DepthPlan.isPlains(depth)) {
                        mines.setOreTable(id, DepthPlan.tableFor(depth));
                    }
                    created++;
                } else {
                    skipped++;
                }
                // Checked separately from the depth, not nested inside the "was
                // just created" branch. A world built before plains had quarries
                // has all 26 depths and none of the pits, and the only way it ever
                // gets them is if re-running setup can add a missing child to a
                // depth that already exists.
                if (DepthPlan.isPlains(depth) && createPit(world, depth)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "could not create " + id, e);
                failures.append(id).append(' ');
            }
        }
        // One flush for the whole batch rather than one per depth.
        regions.flushDirty();
        return new Result(created, skipped, failures.toString().trim());
    }

    /**
     * The quarry inside a plains depth — the part that holds ore and refills.
     *
     * <p>It carries its own {@code depth.required-rank} because rank checks read
     * one region's state and do not walk up the chain: a pit that inherited
     * nothing would default to rank 0 and hand D25's ore to a brand-new player.
     *
     * @return true if it was created just now
     */
    private boolean createPit(World world, int depth) {
        String pitId = DepthPlan.pitIdFor(depth);
        if (regions.index().byId(pitId) != null) {
            return false;
        }
        Region pit = regions.define(pitId, RegionType.SHAFT, DepthPlan.regionIdFor(depth),
                DepthPlan.pitBoundsFor(world.getName(), depth));
        pit.state().set(RankService.DEPTH_NUMBER, depth);
        pit.state().set(RankService.REQUIRED_RANK, DepthPlan.requiredRankFor(depth));
        // Where a refill puts the people standing in it. Without this the fallback
        // is the region's own centre, which is the middle of the ore about to be
        // replaced — it would bury exactly the players it is trying to save.
        descent.setEntryPoint(pit, DepthPlan.entryFor(world, depth));
        mines.setOreTable(pitId, DepthPlan.tableFor(depth));
        return true;
    }

    public record Result(int created, int skipped, String failed) {
    }
}
