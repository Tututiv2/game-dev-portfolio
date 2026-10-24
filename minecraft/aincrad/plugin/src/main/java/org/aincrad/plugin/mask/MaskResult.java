package org.aincrad.plugin.mask;

import java.util.Set;

public record MaskResult(
        Set<ChunkCoord> chunks,
        int townCoreCount,
        int total,
        int expectedTotal,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        int expectedMinChunkX,
        int expectedMaxChunkX,
        int expectedMinChunkZ,
        int expectedMaxChunkZ
) {
    public boolean countMatches() {
        return total == expectedTotal;
    }

    public boolean boundsMatch() {
        return minChunkX == expectedMinChunkX && maxChunkX == expectedMaxChunkX
                && minChunkZ == expectedMinChunkZ && maxChunkZ == expectedMaxChunkZ;
    }

    public boolean passes() {
        return countMatches() && boundsMatch();
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunks.contains(new ChunkCoord(chunkX, chunkZ));
    }
}
