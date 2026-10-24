package com.thestill.mine;

import com.thestill.region.Cuboid;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A resumable, time-budgeted refill of one mine.
 *
 * <p>A depth can be millions of blocks. Filling that in one pass freezes the
 * server for seconds, and "the server hangs every time a mine resets" is the
 * kind of thing players leave over. So the fill is a <em>cursor</em>: each tick
 * it places blocks until its time budget runs out, then stops mid-mine and
 * carries on next tick.
 *
 * <p>Two decisions keep it cheap:
 *
 * <ul>
 *   <li><b>Chunk-major order.</b> Iterating the cuboid linearly hops between
 *       chunks on almost every block. Walking one chunk at a time keeps every
 *       write inside the same chunk section.
 *   <li><b>No physics.</b> {@code setType(material, false)} skips the block
 *       update cascade. With physics on, refilling a mine schedules millions of
 *       neighbour updates and the server never catches up.
 * </ul>
 */
public final class MineFill {

    /** Blocks placed between clock checks. Calling nanoTime per block costs more than the block. */
    private static final int BLOCKS_PER_TIME_CHECK = 512;

    private final World world;
    private final Cuboid bounds;
    private final OreTable table;
    private final RandomGenerator random;

    private final List<long[]> chunks = new ArrayList<>();
    private int chunkCursor;
    private int blockCursor;
    private long placed;
    private final long totalBlocks;

    public MineFill(World world, Cuboid bounds, OreTable table, RandomGenerator random) {
        this.world = world;
        this.bounds = bounds;
        this.table = table;
        this.random = random;
        this.totalBlocks = bounds.volume();

        for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
            for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
                chunks.add(new long[]{chunkX, chunkZ});
            }
        }
    }

    public boolean isDone() {
        return chunkCursor >= chunks.size();
    }

    public long placed() {
        return placed;
    }

    public long totalBlocks() {
        return totalBlocks;
    }

    public double progress() {
        return totalBlocks == 0 ? 1.0 : (double) placed / totalBlocks;
    }

    /**
     * Places blocks until the budget is spent or the mine is full.
     *
     * @param budgetNanos how long this tick may spend. 2ms of a 50ms tick is a
     *                    reasonable ceiling — invisible to players, and a
     *                    million-block mine still finishes in under a minute.
     * @return true when the whole mine is filled
     */
    public boolean step(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        int sinceTimeCheck = 0;

        while (chunkCursor < chunks.size()) {
            long[] chunkCoords = chunks.get(chunkCursor);
            int chunkX = (int) chunkCoords[0];
            int chunkZ = (int) chunkCoords[1];

            // Intersection of this chunk with the mine.
            int fromX = Math.max(bounds.minX(), chunkX << 4);
            int toX = Math.min(bounds.maxX(), (chunkX << 4) + 15);
            int fromZ = Math.max(bounds.minZ(), chunkZ << 4);
            int toZ = Math.min(bounds.maxZ(), (chunkZ << 4) + 15);
            int width = toX - fromX + 1;
            int depth = toZ - fromZ + 1;
            int height = bounds.height();
            int blocksInChunk = width * depth * height;

            // Load once per chunk rather than implicitly on the first getBlockAt.
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (!chunk.isLoaded()) {
                chunk.load(true);
            }

            while (blockCursor < blocksInChunk) {
                int index = blockCursor;
                int y = index / (width * depth);
                int rest = index % (width * depth);
                int x = rest / depth;
                int z = rest % depth;

                Block block = world.getBlockAt(fromX + x, bounds.minY() + y, fromZ + z);
                Material material = table.roll(random);
                if (block.getType() != material) {
                    // physics=false: see the class docs. This is the single most
                    // important argument in the file.
                    block.setType(material, false);
                }

                blockCursor++;
                placed++;

                if (++sinceTimeCheck >= BLOCKS_PER_TIME_CHECK) {
                    sinceTimeCheck = 0;
                    if (System.nanoTime() >= deadline) {
                        return false; // resume here next tick
                    }
                }
            }

            blockCursor = 0;
            chunkCursor++;
        }
        return true;
    }
}
