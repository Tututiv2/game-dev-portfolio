package com.thestill.fog;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * The lamp with no server internals in it — and it lights up the room, not the person.
 *
 * <p>Pure Bukkit: repaint the biome in the world, then re-send the chunks so clients
 * notice. That last step is not optional and it is the trap this project already fell
 * into once — a biome change is server-side until the chunk is re-sent, so without it
 * {@code setBiome} looks like a silent no-op to everybody who was already standing there.
 * See {@code StillWorld.paintBiomesNow} and the CLAUDE.md section it is written up in.
 *
 * <p><b>Two costs, stated plainly rather than hidden.</b>
 *
 * <ol>
 *   <li><b>It is not per-player.</b> The biome is world state, so anyone standing beside
 *       the lamp holder sees exactly as far as they do. On a dev server with one person
 *       that is invisible; on a live one it turns a personal tool into a shared floodlight
 *       and quietly undoes the reason the lamp is interesting.</li>
 *   <li><b>A crash can leave paint behind.</b> {@link #douse} puts the biome back, and
 *       {@code FogService.stop} douses everybody, but a {@code kill -9} between the two
 *       leaves a patch of the world permanently clear. {@code /still setup biomes} repaints
 *       the whole dimension in seconds and is the fix.</li>
 * </ol>
 *
 * <p>Neither cost applies to {@link PacketBiomeChannel}, which is why that is tried first.
 * This is here so that "the internals moved" degrades to a worse lamp instead of no lamp.
 */
final class WorldBiomeChannel implements BiomeChannel {

    /**
     * Biomes are stored on a 4x4x4 grid, so painting every fourth block is complete
     * coverage and sixty-four times cheaper. Getting this wrong in either direction is
     * expensive: 1 wastes 63 out of every 64 calls, and 5 leaves unpainted cells that show
     * up as a checkerboard of weather.
     */
    private static final int CELL = 4;

    /**
     * How far above and below the player to paint, in blocks.
     *
     * <p>Fog is sampled at the camera, so strictly only the cell you are standing in
     * matters — but you fall, and a cell boundary crossed mid-drop would flicker the
     * weather. A column is cheap and steady.
     */
    private static final int COLUMN = 32;

    private final Logger log;
    private final org.bukkit.block.Biome lamplight;
    private final org.bukkit.block.Biome depths;

    WorldBiomeChannel(Logger log, org.bukkit.block.Biome lamplight,
                      org.bukkit.block.Biome depths) {
        this.log = log;
        this.lamplight = lamplight;
        this.depths = depths;
    }

    @Override
    public String describe() {
        return "world repaint (no server internals) — WARNING: not per-player, "
                + "anyone standing next to the holder sees the same distance";
    }

    @Override
    public boolean light(Player player, long[] chunks) {
        return paint(player, chunks, lamplight);
    }

    @Override
    public void douse(Player player, long[] chunks) {
        paint(player, chunks, depths);
    }

    private boolean paint(Player player, long[] chunks, org.bukkit.block.Biome biome) {
        if (biome == null) {
            return false;
        }
        World world = player.getWorld();
        int centreY = player.getLocation().getBlockY();
        int fromY = Math.max(world.getMinHeight(), centreY - COLUMN);
        int toY = Math.min(world.getMaxHeight() - 1, centreY + COLUMN);
        try {
            for (long key : chunks) {
                int baseX = ChunkRing.chunkX(key) << 4;
                int baseZ = ChunkRing.chunkZ(key) << 4;
                for (int x = baseX; x < baseX + 16; x += CELL) {
                    for (int z = baseZ; z < baseZ + 16; z += CELL) {
                        for (int y = fromY; y <= toY; y += CELL) {
                            world.setBiome(x, y, z, biome);
                        }
                    }
                }
            }
            for (long key : chunks) {
                world.refreshChunk(ChunkRing.chunkX(key), ChunkRing.chunkZ(key));
            }
            return true;
        } catch (Throwable t) {
            log.warning("fog lamp: world repaint failed for " + player.getName()
                    + " — " + t);
            return false;
        }
    }
}
