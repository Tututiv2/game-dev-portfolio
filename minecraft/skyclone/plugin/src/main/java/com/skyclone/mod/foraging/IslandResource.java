package com.skyclone.mod.foraging;

import java.util.Locale;

/**
 * The Moonmarsh's "island resources" (research 07 §4.1): things you pick rather than chop. Pure data,
 * no Bukkit. Each grows as one vanilla block that the generator plants and {@link TreeRegen} regrows.
 *
 * <p>Foraging XP is the research's 15-16 band (which one gets 16 is ours). Amount: a sea pickle cluster
 * gives one Sea Lumies per pickle in it; everything else gives one. HotF's Collector perk can double it.
 */
public enum IslandResource {
    /** Velvet Lilac (ref. Lushlilac): the purple meadow flowers of the marsh. Two blocks tall. */
    LUSHLILAC("LUSHLILAC", "LILAC", 16, "Velvet Lilac", false),
    /** Sea Lumies: glowing sea pickles on the floor of the Dimwater Loch. */
    SEA_LUMIES("SEA_LUMIES", "SEA_PICKLE", 15, "Sea Lumies", false),
    /** Ruby Veilshroom: red mushrooms in the shade of the canyon's spiral trees. */
    RUBY_VEILSHROOM("RUBY_VEILSHROOM", "RED_MUSHROOM", 15, "Ruby Veilshroom", true);

    public final String itemId;
    public final String block;
    public final double xp;
    public final String display;
    /** Grows in the Torrent Canyon (else the marsh proper). */
    public final boolean canyon;

    IslandResource(String itemId, String block, double xp, String display, boolean canyon) {
        this.itemId = itemId; this.block = block; this.xp = xp; this.display = display; this.canyon = canyon;
    }

    /** The resource a block material name is, or null. */
    public static IslandResource ofBlock(String material) {
        if (material == null) return null;
        String m = material.toUpperCase(Locale.ROOT);
        for (IslandResource r : values()) if (r.block.equals(m)) return r;
        return null;
    }

    /** Collector (HotF): +2% per level chance to double (research 07 §4.5). */
    public static double collectorChance(int level) { return Math.max(0, level) * 0.02; }
}
