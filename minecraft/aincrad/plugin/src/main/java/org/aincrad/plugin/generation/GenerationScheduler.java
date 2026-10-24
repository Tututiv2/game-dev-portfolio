package org.aincrad.plugin.generation;

import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs on the main thread, once per server tick, applying at most perTickBudget block edits
 * before yielding control back to the tick loop - this is the "block-budget slicing" contract
 * from PRISON-SERVER-MASTER-MAP.md 3 and the project's own generation_contract, re-implemented
 * here under this project's namespace (Shadow Prison's code itself was not touched or copied).
 * A single sequential scheduler trivially satisfies "produce identical output regardless of
 * worker count" for this phase, since there is only one worker (the main thread already owns all
 * world mutation in Bukkit); parallel precomputation of edits, if ever needed, must still funnel
 * through this same single apply path.
 */
public final class GenerationScheduler extends BukkitRunnable {

    public interface FeatureJob {
        String featureId();

        Iterator<BlockEdit> edits();

        void onComplete();
    }

    private final World world;
    private final int perTickBudget;
    private final Queue<FeatureJob> pendingJobs = new ArrayDeque<>();
    private final Logger logger;

    private FeatureJob currentJob;
    private Iterator<BlockEdit> currentEdits;
    private long totalEditsApplied = 0;
    private long totalJobsCompleted = 0;

    public GenerationScheduler(World world, int perTickBudget, Logger logger) {
        this.world = world;
        this.perTickBudget = perTickBudget;
        this.logger = logger;
    }

    public void submit(FeatureJob job) {
        pendingJobs.add(job);
    }

    public boolean hasWork() {
        return currentJob != null || !pendingJobs.isEmpty();
    }

    public int getPendingJobCount() {
        return pendingJobs.size();
    }

    public long getTotalEditsApplied() {
        return totalEditsApplied;
    }

    public long getTotalJobsCompleted() {
        return totalJobsCompleted;
    }

    @Override
    public void run() {
        int budgetRemaining = perTickBudget;
        while (budgetRemaining > 0) {
            if (currentJob == null) {
                currentJob = pendingJobs.poll();
                if (currentJob == null) {
                    return;
                }
                try {
                    currentEdits = currentJob.edits();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to compute edits for feature " + currentJob.featureId(), e);
                    currentJob = null;
                    continue;
                }
            }
            if (!currentEdits.hasNext()) {
                try {
                    currentJob.onComplete();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "onComplete failed for feature " + currentJob.featureId(), e);
                }
                totalJobsCompleted++;
                currentJob = null;
                currentEdits = null;
                continue;
            }
            BlockEdit edit = currentEdits.next();
            if (edit.blockData() == null) {
                world.getBlockAt(edit.x(), edit.y(), edit.z()).setType(edit.material(), false);
            } else {
                try {
                    world.getBlockAt(edit.x(), edit.y(), edit.z())
                            .setBlockData(org.bukkit.Bukkit.createBlockData(edit.blockData()), false);
                } catch (IllegalArgumentException e) {
                    // A malformed state string must not abort a whole feature; fall back to the
                    // plain material and report it once so the bad string gets fixed.
                    logger.warning("Invalid block data '" + edit.blockData() + "' at "
                            + edit.x() + "," + edit.y() + "," + edit.z() + "; placed plain "
                            + edit.material());
                    world.getBlockAt(edit.x(), edit.y(), edit.z()).setType(edit.material(), false);
                }
            }
            totalEditsApplied++;
            budgetRemaining--;
        }
    }
}
