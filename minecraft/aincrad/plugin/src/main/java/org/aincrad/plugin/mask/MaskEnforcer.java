package org.aincrad.plugin.mask;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Detects chunks generated outside the <em>currently authorized</em> scope.
 *
 * <p>The authorized scope is not fixed. Until 2026-07-24 it was the 7,912-chunk Town review slice;
 * the owner has since authorized full Floor One, so it is now the whole 5,000-block disc. This class
 * takes the scope as a parameter rather than hardcoding the slice, because when the review-slice
 * mask was the only thing it knew about, the first full-floor run produced <b>3,000 SEVERE log lines
 * for 3,000 legitimately-generated chunks</b> — correct behaviour against a stale authorization, and
 * enough log churn to measurably slow generation down.</p>
 *
 * <p>Anything beyond the floor's own radius is still a real violation in either mode: there is no
 * ground out there and nothing should ever generate past the shell.</p>
 */
public final class MaskEnforcer implements Listener {

    public enum Scope {
        /** Only the Town review slice is authorized. */
        REVIEW_SLICE,
        /** All chunks intersecting the Floor One disc are authorized. */
        FULL_FLOOR
    }

    private static final int FLOOR_RADIUS = 5000;
    private static final int LOG_LIMIT = 25;

    private final MaskResult mask;
    private final NamespacedKey aincradKey;
    private final Logger logger;
    private final Scope scope;
    private final AtomicInteger violationCount = new AtomicInteger(0);
    private final AtomicInteger logged = new AtomicInteger(0);

    public MaskEnforcer(MaskResult mask, NamespacedKey aincradKey, Logger logger, Scope scope) {
        this.mask = mask;
        this.aincradKey = aincradKey;
        this.logger = logger;
        this.scope = scope;
    }

    private boolean authorized(int chunkX, int chunkZ) {
        // Never authorized anywhere: beyond the floor's physical radius.
        int nx = Math.max(Math.abs(chunkX * 16 + 8) - 8, 0);
        int nz = Math.max(Math.abs(chunkZ * 16 + 8) - 8, 0);
        if (Math.hypot(nx, nz) > FLOOR_RADIUS) {
            return false;
        }
        return scope == Scope.FULL_FLOOR || (mask != null && mask.contains(chunkX, chunkZ));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!world.getKey().equals(aincradKey) || !event.isNewChunk()) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        if (authorized(chunkX, chunkZ)) {
            return;
        }
        int count = violationCount.incrementAndGet();
        // Rate-limited: a systemic problem would otherwise bury the log and slow the run.
        if (logged.get() < LOG_LIMIT) {
            logged.incrementAndGet();
            logger.severe("MASK VIOLATION #" + count + ": new chunk at (" + chunkX + ", " + chunkZ
                    + ") is outside the authorized scope (" + scope + ")"
                    + (logged.get() == LOG_LIMIT ? " - further violations counted but not logged" : ""));
        }
    }

    public int getViolationCount() {
        return violationCount.get();
    }

    public Scope getScope() {
        return scope;
    }
}
