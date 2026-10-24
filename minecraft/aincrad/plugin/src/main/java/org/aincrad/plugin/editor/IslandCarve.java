package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns solid ground into a floating island by voiding everything below a shell of rock.
 *
 * <p>Aincrad's floors are plates hanging in the sky, and every version of this world so far has been
 * solid rock all the way to bedrock - which is why it has never read as Aincrad from below. This carve
 * keeps a band of rock under each surface column and deletes the rest.</p>
 *
 * <p><b>The underside has to be irregular.</b> A constant thickness produces a perfectly flat disc,
 * which looks like a bug rather than a landmass. Thickness is therefore varied by a cheap positional
 * hash, and tapered hard near the boundary so the island thins to a rim instead of ending in a
 * cliff-edged slab.</p>
 *
 * <p>Chunk handling follows the same rule as everything else here: chunks are loaded asynchronously and
 * read only once resident. Nothing touches an unloaded chunk from the main thread.</p>
 */
public final class IslandCarve {

    /** Rock left under the surface at the island's centre. */
    public static final int DEFAULT_THICKNESS = 90;
    /** Columns within this distance of the edge taper down to a thin rim. */
    private static final int TAPER = 40;
    private static final int MAX_CHUNKS = 30_000;

    private IslandCarve() {
    }

    /**
     * @param minX,minZ,maxX,maxZ block bounds to carve under
     * @param thickness           rock kept beneath each surface column at the centre
     */
    public static void start(Plugin plugin, Editor editor, Player player, World world,
                             int minX, int minZ, int maxX, int maxZ, int thickness) {
        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;
        long chunkCount = (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
        if (chunkCount > MAX_CHUNKS) {
            Editor.tell(player, "<red>That is " + Editor.format(chunkCount)
                    + " chunks, over the " + Editor.format(MAX_CHUNKS) + " limit.</red> Do it in pieces.");
            return;
        }

        List<long[]> chunks = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                chunks.add(new long[]{cx, cz});
            }
        }
        Editor.tell(player, "Carving the underside across " + Editor.plural(chunks.size(), "chunk")
                + ", keeping about " + thickness + " blocks of rock. "
                + Editor.runnable("//cancel", "//cancel") + " stops it.");
        Editor.tell(player, "<red>Not undoable at this size</red> <gray>- it is a deletion, so there is "
                + "nothing to record.");

        int minY = world.getMinHeight();
        BlockData air = Bukkit.createBlockData(Material.AIR);
        // A cursor rather than a loop: one chunk per tick, each waiting on its own async load.
        new Runnable() {
            int index;
            long voided;
            long lastReport;

            @Override
            public void run() {
                if (index >= chunks.size() || !player.isOnline()) {
                    Editor.tell(player, "<green>Underside carved.</green> "
                            + Editor.plural(voided, "block") + " removed. It is an island now.");
                    return;
                }
                long[] c = chunks.get(index++);
                int cx = (int) c[0];
                int cz = (int) c[1];
                Runnable self = this;
                world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (chunk != null) {
                                voided += carveChunk(editor, player, world, cx, cz,
                                        minX, minZ, maxX, maxZ, thickness, minY, air);
                            }
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 2000) {
                                lastReport = now;
                                player.sendActionBar(net.kyori.adventure.text.Component.text(
                                        "Carving island  " + index + "/" + chunks.size() + " chunks  "
                                                + Editor.format(voided) + " blocks removed"));
                            }
                            Bukkit.getScheduler().runTask(plugin, self);
                        }));
            }
        }.run();
    }

    /** Queues the air writes for one chunk. Returns how many blocks it will remove. */
    private static long carveChunk(Editor editor, Player player, World world, int cx, int cz,
                                   int minX, int minZ, int maxX, int maxZ,
                                   int thickness, int minY, BlockData air) {
        int fromX = Math.max(minX, cx << 4);
        int toX = Math.min(maxX, (cx << 4) + 15);
        int fromZ = Math.max(minZ, cz << 4);
        int toZ = Math.min(maxZ, (cz << 4) + 15);
        List<EditQueue.Write> writes = new ArrayList<>();

        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                int surface = world.getHighestBlockYAt(x, z);
                if (surface <= minY) {
                    continue;               // already void
                }
                int edge = Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
                double taper = edge >= TAPER ? 1.0 : 0.15 + 0.85 * (edge / (double) TAPER);
                int wobble = (int) (hash(x, z) % 17) - 8;
                int keep = Math.max(4, (int) (thickness * taper) + wobble);
                int floor = surface - keep;
                for (int y = floor; y > minY; y--) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        continue;           // already hollow; caves and the void below
                    }
                    writes.add(new EditQueue.Write(x, y, z, air));
                }
            }
        }
        if (writes.isEmpty()) {
            return 0;
        }
        editor.queue().submitWithoutUndo(player, world, "carve underside", new EditQueue.WriteSource() {
            @Override
            public long estimate() {
                return writes.size();
            }

            @Override
            public java.util.Iterator<EditQueue.Write> iterator() {
                return writes.iterator();
            }
        });
        return writes.size();
    }

    /** Cheap positional hash, so the underside is irregular without a noise library. */
    private static long hash(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L;
        h ^= (h >>> 31);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return h & Long.MAX_VALUE;
    }
}
