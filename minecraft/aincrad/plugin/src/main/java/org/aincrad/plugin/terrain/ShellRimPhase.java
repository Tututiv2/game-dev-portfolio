package org.aincrad.plugin.terrain;

import org.aincrad.plugin.ledger.GenerationLedger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes Floor One an actual floating island: removes everything beyond the shell radius, opens the
 * void beneath the floor, and finishes the underside with an armoured skin, radial ribs and
 * concentric reinforcement rings.
 *
 * <p><b>Why this exists.</b> The datapack originally filled every column solid from Y=-64 to the
 * surface, so the floor read as a flat plate resting on infinite rock — the single most important
 * visual of the setting was simply absent, and the owner spotted it immediately. The datapack now
 * generates the void for new chunks, but chunks that already exist (the whole Town) still have
 * their original solid fill and have to be carved.</p>
 *
 * <p><b>Bounded by radius on purpose.</b> Carving the full Town slice is roughly 290 block edits per
 * column, about 587 million edits and close to five hours. This phase therefore takes a radius band,
 * so the dramatic part — the drop just outside the southern wall, which is where a player standing on
 * the wall walk actually looks over — can be done in minutes, and the rest run later as a long job.
 * Chunks are loaded, edited, saved and unloaded one at a time so memory stays flat.</p>
 */
public final class ShellRimPhase {

    private static final String FEATURE_PREFIX = "f01/shell/rim/chunk_";
    private static final String FEATURE_VERSION = "shell_rim:v1";
    private static final String PHASE_ID = "phase1_shell_and_rim";

    private static final int MIN_Y = -64;
    private static final int TOP_Y = 420;          // clear up to Floor Two's plate line
    private static final int SHELL_R = FloorOneTerrain.FLOOR_RADIUS;
    private static final int RIM_INNER = 4936;
    private static final int[] RING_RADII = {1100, 2200, 3300, 4200, 4840};

    private final World world;
    private final GenerationLedger ledger;
    private final Logger logger;

    private boolean running;
    private long chunksDone;
    private long editsApplied;
    private BukkitRunnable task;

    public ShellRimPhase(World world, GenerationLedger ledger, Logger logger) {
        this.world = world;
        this.ledger = ledger;
        this.logger = logger;
    }

    public boolean isRunning() {
        return running;
    }

    public long chunksDone() {
        return chunksDone;
    }

    public long editsApplied() {
        return editsApplied;
    }

    /** Chunks whose nearest corner falls inside the requested radius band. */
    private List<int[]> chunksInBand(int minR, int maxR) {
        List<int[]> out = new ArrayList<>();
        int cr = maxR / 16 + 2;
        for (int cx = -cr; cx <= cr; cx++) {
            for (int cz = -cr; cz <= cr; cz++) {
                int nearX = Math.max(Math.abs(cx * 16 + 8) - 8, 0);
                int nearZ = Math.max(Math.abs(cz * 16 + 8) - 8, 0);
                double near = Math.hypot(nearX, nearZ);
                double far = Math.hypot(Math.abs(cx * 16 + 8) + 8, Math.abs(cz * 16 + 8) + 8);
                if (far >= minR && near <= maxR) {
                    out.add(new int[]{cx, cz});
                }
            }
        }
        out.sort((a, b) -> {
            long ra = (long) a[0] * a[0] + (long) a[1] * a[1];
            long rb = (long) b[0] * b[0] + (long) b[1] * b[1];
            return ra != rb ? Long.compare(ra, rb)
                    : (a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        });
        return out;
    }

    public int start(Plugin plugin, int minR, int maxR, int chunksPerTick) {
        if (running) {
            logger.warning("Shell/rim phase already running.");
            return -1;
        }
        List<int[]> chunks = chunksInBand(minR, maxR);
        running = true;
        chunksDone = 0;
        editsApplied = 0;
        logger.info("Shell/rim: " + chunks.size() + " chunks in band r=" + minR + ".." + maxR);

        java.util.Deque<int[]> pending = new java.util.ArrayDeque<>(chunks);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < chunksPerTick; i++) {
                    int[] c = pending.poll();
                    if (c == null) {
                        logger.info("Shell/rim finished: " + chunksDone + " chunks, "
                                + editsApplied + " edits");
                        running = false;
                        cancel();
                        return;
                    }
                    try {
                        processChunk(c[0], c[1]);
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Shell/rim chunk " + c[0] + "," + c[1] + " failed", e);
                    }
                    chunksDone++;
                }
                if (chunksDone % 500 == 0) {
                    logger.info("Shell/rim " + chunksDone + "/" + chunks.size()
                            + "  " + editsApplied + " edits");
                }
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
        return chunks.size();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        running = false;
    }

    private void processChunk(int cx, int cz) {
        String featureId = FEATURE_PREFIX + cx + "_" + cz;
        try {
            var existing = ledger.getFeature(featureId);
            if (existing.isPresent()
                    && existing.get().state() == GenerationLedger.FeatureState.COMMITTED) {
                return;
            }
            if (existing.isEmpty()) {
                ledger.planFeature(featureId, FEATURE_VERSION, PHASE_ID,
                        java.util.Set.of(new int[]{cx, cz}), System.currentTimeMillis());
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ledger error for " + featureId, e);
        }

        boolean wasLoaded = world.isChunkLoaded(cx, cz);
        world.getChunkAt(cx, cz);
        int edits = 0;
        int baseX = cx * 16, baseZ = cz * 16;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx, z = baseZ + dz;
                double r = Math.hypot(x, z);

                if (r > SHELL_R) {
                    // Beyond the shell there is no world at all - this is what creates the edge.
                    for (int y = MIN_Y; y <= TOP_Y; y++) {
                        edits += clear(x, y, z);
                    }
                    continue;
                }

                int underY = FloorOneTerrain.undersideY(x, z);

                // Open the void beneath the shell.
                for (int y = MIN_Y; y < underY; y++) {
                    edits += clear(x, y, z);
                }

                // Armoured skin, 3 blocks, with rib and ring accents so the belly has structure
                // rather than reading as a uniform slab.
                boolean rib = isRib(x, z);
                boolean ring = isRing(r);
                Material skin = (rib && ring) ? Material.IRON_BLOCK
                        : rib ? Material.POLISHED_BLACKSTONE_BRICKS
                        : ring ? Material.CUT_COPPER
                        : Material.DEEPSLATE_BRICKS;
                for (int y = underY; y < underY + 3; y++) {
                    edits += set(x, y, z, skin);
                }
                // Ribs hang a little below the skin so they cast a silhouette from underneath.
                if (rib) {
                    edits += set(x, underY - 1, z, Material.POLISHED_BLACKSTONE);
                    edits += set(x, underY - 2, z, Material.POLISHED_BLACKSTONE);
                }

                // Rim guardrail, so the walkable overlook band does not just end in a fall.
                if (r >= RIM_INNER && r <= SHELL_R - 2) {
                    int top = world.getHighestBlockYAt(x, z);
                    if (top >= 300 && top <= 340 && Math.floorMod(x + z, 2) == 0) {
                        edits += set(x, top + 1, z, Material.POLISHED_ANDESITE_SLAB);
                    }
                }
            }
        }
        editsApplied += edits;
        if (!wasLoaded) {
            world.unloadChunk(cx, cz, true);
        }
    }

    /** 24 radial ribs, one every 15 degrees, per the layout JSON's support_ribs block. */
    private static boolean isRib(int x, int z) {
        double r = Math.hypot(x, z);
        if (r < 60) {
            return true;   // central boss where all ribs converge
        }
        double ang = Math.toDegrees(Math.atan2(z, x));
        if (ang < 0) {
            ang += 360;
        }
        double off = Math.abs((ang % 15.0) - 7.5);
        // Constant-width ribs: the angular tolerance must shrink as radius grows.
        double halfWidthDeg = Math.toDegrees(7.5 / Math.max(1.0, r));
        return (7.5 - off) < halfWidthDeg;
    }

    private static boolean isRing(double r) {
        for (int ringR : RING_RADII) {
            if (Math.abs(r - ringR) <= 7) {
                return true;
            }
        }
        return false;
    }

    private int clear(int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        if (!b.getType().isAir()) {
            b.setType(Material.AIR, false);
            return 1;
        }
        return 0;
    }

    private int set(int x, int y, int z, Material m) {
        Block b = world.getBlockAt(x, y, z);
        if (b.getType() != m) {
            b.setType(m, false);
            return 1;
        }
        return 0;
    }
}
