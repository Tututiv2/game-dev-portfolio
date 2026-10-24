package org.aincrad.plugin.npc;

/**
 * A logical town inhabitant. This is a <em>record</em>, not an entity - the spec caps the town at
 * 160 simultaneously active NPCs out of 1,200-1,800 logical ones and explicitly forbids using
 * armour stands as a mass population system, so the default state of an inhabitant is data sitting
 * in a table costing zero ticks.
 *
 * <p>{@code globalFloor} is part of the identity rather than inferred from position, because ten
 * floors share one dimension and one (chunkX, chunkZ) column: an NPC on Floor 1 and one on Floor 7
 * can occupy the same X/Z, so any floor-sensitive lookup keyed only on coordinates would confuse
 * them.</p>
 */
public record NpcRecord(
        String id,
        int globalFloor,
        String homeParcelId,
        Occupation occupation,
        int x,
        int y,
        int z,
        String scheduleId,
        String version
) {
    public enum Occupation {RESIDENT, SHOPKEEPER, INNKEEPER, ARTISAN, CIVIC_STAFF, SERVICE_STAFF}

    /** The 10-chunk cluster used for the spec's local activation budget. */
    public long clusterKey() {
        long cx = Math.floorDiv(Math.floorDiv(x, 16), 10);
        long cz = Math.floorDiv(Math.floorDiv(z, 16), 10);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }
}
